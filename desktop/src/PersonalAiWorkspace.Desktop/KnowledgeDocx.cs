using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using W = DocumentFormat.OpenXml.Wordprocessing;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

// Converts only an explicitly selected, held local file. No Office process, network,
// temporary extracted parts, original-path reopen or document mutation is involved.
internal static class KnowledgeDocx
{
    internal const string Notice="仅保存正文文字摘录，不保存 Word 原文件。段落按原顺序排列，表格单元格用双引号与制表符分隔。图片、页眉页脚、脚注、批注、自动编号和版式不导入；引用位置只对应本摘录，不是 Word 页码。请检查预览后再确认。";
    private const int ExpandedBytes=16*1024*1024, PartBytes=4*1024*1024, TextUnits=480_000;
    internal sealed record Excerpt(string Filename,string Text)
    { public override string ToString()=>"WordExcerpt[redacted]"; }

    internal static Excerpt Read(Stream input,string filename,CancellationToken token)
    {
        long started=Stopwatch.GetTimestamp();
        void Check(){token.ThrowIfCancellationRequested();if(Stopwatch.GetElapsedTime(started)>TimeSpan.FromSeconds(10))throw new DesktopException(DesktopError.ClientTimeout);}
        try{
            if(!filename.EndsWith(".docx",StringComparison.OrdinalIgnoreCase))throw new InvalidDataException();
            string outputName=filename+".txt";RuntimeClient.ValidateKnowledgeFilename(outputName);
            if(!input.CanSeek||input.Length<=0||input.Length>RuntimeClient.MaximumKnowledgeSourceBytes)throw new InvalidDataException();
            Check();input.Position=0;using var copy=new MemoryStream();byte[] buffer=new byte[65536];int count;
            while((count=input.Read(buffer,0,buffer.Length))>0){Check();if(copy.Length+count>RuntimeClient.MaximumKnowledgeSourceBytes)throw new InvalidDataException();copy.Write(buffer,0,count);}
            byte[] bytes=copy.ToArray();
            // Check actual expanded sizes as well as ZIP metadata before the SDK opens parts.
            using(var zip=new ZipArchive(new MemoryStream(bytes,false),ZipArchiveMode.Read)){
                if(zip.Entries.Count is <3 or >256)throw new InvalidDataException();
                long total=0;var names=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach(var entry in zip.Entries){
                    Check();if(!names.Add(entry.FullName)||entry.Length>PartBytes||entry.FullName.Contains('\\')||entry.FullName.StartsWith('/')||Array.Exists(entry.FullName.Split('/'),p=>p==".."))throw new InvalidDataException();
                    using var part=entry.Open();long size=0;
                    while((count=part.Read(buffer,0,buffer.Length))>0){Check();size+=count;total+=count;if(size>PartBytes||total>ExpandedBytes)throw new InvalidDataException();}
                    if(size!=entry.Length)throw new InvalidDataException();
                }
            }
            using var document=WordprocessingDocument.Open(new MemoryStream(bytes,false),false,new OpenSettings{AutoSave=false,MaxCharactersInPart=PartBytes});
            if(document.DocumentType!=WordprocessingDocumentType.Document||document.MainDocumentPart is not { } main)throw new InvalidDataException();
            using var reader=OpenXmlReader.Create(main);
            var body=new StringBuilder();int bodyDepth=-1,cellDepth=-1,tableDepth=-1,ignoredDepth=-1,nodes=0,lines=0;
            bool bodySeen=false,hasText=false;
            void Append(string value){if(body.Length+value.Length>TextUnits)throw new InvalidDataException();body.Append(value);}
            while(reader.Read()){
                Check();if(++nodes>200_000||reader.Depth>64)throw new InvalidDataException();
                if(ignoredDepth>=0){if(reader.IsEndElement&&reader.Depth==ignoredDepth)ignoredDepth=-1;continue;}
                if(reader.ElementType==typeof(W.Body)){
                    if(reader.IsStartElement){if(bodySeen)throw new InvalidDataException();bodySeen=true;bodyDepth=reader.Depth;}else bodyDepth=-1;
                    continue;
                }
                if(bodyDepth<0)continue;
                if(reader.IsStartElement){
                    // Do not silently accept a change, evaluate a field, or select an alternate representation.
                    string name=reader.LocalName;
                    // These are document content, not formatting. Reading only w:t would silently
                    // discard equations and glyphs whose Unicode value depends on an installed font.
                    if(reader.ElementType==typeof(W.SymbolChar)||name is "oMath" or "oMathPara")
                        throw new DesktopException(DesktopError.KnowledgeDocxUnsupported);
                    if(name is "ins" or "del" or "moveFrom" or "moveTo" or "fldChar" or "fldSimple" or "instrText" or "altChunk" or "AlternateContent" or "sdt" or "vanish" or "webHidden" or "gridSpan" or "vMerge" or "hMerge" || name.EndsWith("Change",StringComparison.Ordinal))
                        throw new DesktopException(DesktopError.KnowledgeDocxUnsupported);
                    if(reader.ElementType==typeof(W.Drawing)||name is "pict" or "object"){
                        Append("[图片或对象未导入]");ignoredDepth=reader.Depth;continue;
                    }
                    if(reader.ElementType==typeof(W.Table)){
                        if(tableDepth>=0)throw new DesktopException(DesktopError.KnowledgeDocxUnsupported);
                        tableDepth=reader.Depth;Append("\n");
                    }else if(reader.ElementType==typeof(W.TableCell)){
                        if(cellDepth>=0)throw new InvalidDataException();cellDepth=reader.Depth;Append("\"");
                    }else if(reader.ElementType==typeof(W.Text)){
                        string text=reader.GetText();hasText|=!string.IsNullOrWhiteSpace(text);Append(cellDepth>=0?text.Replace("\"","\"\"",StringComparison.Ordinal):text);
                    }else if(reader.ElementType==typeof(W.TabChar))Append("\t");
                    else if(reader.ElementType==typeof(W.Break)||reader.ElementType==typeof(W.CarriageReturn))Append("\n");
                    else if(reader.ElementType==typeof(W.NoBreakHyphen))Append("‑");
                    else if(reader.ElementType==typeof(W.SoftHyphen))Append("\u00ad");
                    else if(name is "footnoteReference" or "endnoteReference")Append("[注释正文未导入]");
                    else if(reader.ElementType==typeof(OpenXmlUnknownElement))throw new DesktopException(DesktopError.KnowledgeDocxUnsupported);
                }else if(reader.IsEndElement){
                    if(reader.ElementType==typeof(W.Paragraph)){Append("\n");if(++lines>90_000)throw new InvalidDataException();}
                    else if(reader.ElementType==typeof(W.TableCell)){
                        if(body.Length>0&&body[^1]=='\n')body.Length--;Append("\"\t");cellDepth=-1;
                    }else if(reader.ElementType==typeof(W.TableRow)){
                        if(body.Length>0&&body[^1]=='\t')body.Length--;Append("\n");
                    }else if(reader.ElementType==typeof(W.Table)){tableDepth=-1;Append("\n");}
                }
            }
            if(!bodySeen||!hasText)throw new InvalidDataException();
            string result="Word 正文摘录\n来源文件："+filename+"\n原文件 SHA-256："+Convert.ToHexString(SHA256.HashData(bytes))+"\n提取格式：docx-body-v1\n"+Notice+"\n\n--- 正文 ---\n"+body;
            var utf8=new UTF8Encoding(false,true);if(utf8.GetByteCount(result)>2*1024*1024)throw new InvalidDataException();
            foreach(char c in result)if(char.IsControl(c)&&c!='\n'&&c!='\t')throw new InvalidDataException();
            Check();return new(outputName,result);
        }catch(DesktopException){throw;}catch(OperationCanceledException){throw;}
        catch(Exception){throw new DesktopException(DesktopError.KnowledgeDocxInvalid);}
    }
}
