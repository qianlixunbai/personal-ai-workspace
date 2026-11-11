using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed class KnowledgeSourceFile(Stream stream,string filename):IDisposable
{
    internal Stream Stream {get;}=stream;
    internal string Filename {get;}=filename;
    public override string ToString()=>"KnowledgeSourceFile[redacted]";
    public void Dispose()=>Stream.Dispose();
}
internal interface IKnowledgeSourceFiles {
    Task<KnowledgeSourceFile?> PickAsync(CancellationToken token);
    Task<KnowledgeSourceFile[]> PickManyAsync(CancellationToken token)=>throw new DesktopException(DesktopError.KnowledgeFileUnavailable);
}
internal sealed class NativeKnowledgeSourceFiles(Func<Window?> owner):IKnowledgeSourceFiles
{
    internal const int MaximumSelectedFiles=10;
    internal const long MaximumSelectedBytes=32*1024*1024;
    private int selecting;
    public async Task<KnowledgeSourceFile?> PickAsync(CancellationToken token)
    {
        var result=await PickAsync(false,token);return result.Length==0?null:result[0];
    }
    public Task<KnowledgeSourceFile[]> PickManyAsync(CancellationToken token)=>PickAsync(true,token);
    private async Task<KnowledgeSourceFile[]> PickAsync(bool many,CancellationToken token)
    {
        if(Interlocked.CompareExchange(ref selecting,1,0)!=0)throw new DesktopException(DesktopError.QueueFull);
        var result=new List<KnowledgeSourceFile>();bool transferred=false;
        try{
        token.ThrowIfCancellationRequested();var window=owner()??throw new DesktopException(DesktopError.KnowledgeFileUnavailable);
        var picker=new OpenFileDialog{Title=many?"选择待导入资料 · 最多 10 份，正文合计 32 MiB":"导入资料 · TXT / Markdown / Word / PDF 正文",Filter="支持的资料 (*.txt;*.md;*.markdown;*.docx;*.pdf)|*.txt;*.md;*.markdown;*.docx;*.pdf|Word 正文摘录 (*.docx)|*.docx|PDF 文字层 (*.pdf)|*.pdf",Multiselect=many,CheckFileExists=true};
        bool selected=picker.ShowDialog(window)==true;token.ThrowIfCancellationRequested();window.Activate();
        if(!selected)return [];
        if(picker.FileNames.Length>MaximumSelectedFiles)throw new DesktopException(DesktopError.KnowledgeLimitExceeded);
        long total=0;
        foreach(var path in picker.FileNames){
            token.ThrowIfCancellationRequested();
            var source=await ReadSelectedAsync(path,window,token);
            if(source is null)return []; // Cancelling a Word/PDF preview cancels the whole selection, before upload.
            result.Add(source);total+=source.Stream.Length;
            if(total>MaximumSelectedBytes)throw new DesktopException(DesktopError.KnowledgeSourceTooLarge);
        }
        token.ThrowIfCancellationRequested();transferred=true;return result.ToArray();
        }finally{if(!transferred)foreach(var file in result)file.Dispose();Volatile.Write(ref selecting,0);}
    }
    private static async Task<KnowledgeSourceFile?> ReadSelectedAsync(string path,Window window,CancellationToken token)
    {
        bool pdf=path.EndsWith(".pdf",StringComparison.OrdinalIgnoreCase);
        if(!pdf&&!path.EndsWith(".docx",StringComparison.OrdinalIgnoreCase))return Open(path);
        using var file=OpenRegular(path,RuntimeClient.MaximumKnowledgeSourceBytes);
        string text,filename;
        if(pdf){var excerpt=await KnowledgePdf.ReadAsync(file,Path.GetFileName(path),token);text=excerpt.Text;filename=excerpt.Filename;}
        else{var excerpt=await Task.Run(()=>KnowledgeDocx.Read(file,Path.GetFileName(path),token));text=excerpt.Text;filename=excerpt.Filename;}
        token.ThrowIfCancellationRequested();
        if(!KnowledgeExcerptPreview.Confirm(window,pdf?"PDF":"Word",pdf?KnowledgePdf.Notice:KnowledgeDocx.Notice,text,token))return null;
        return new KnowledgeSourceFile(new MemoryStream(new UTF8Encoding(false,true).GetBytes(text),false),filename);
    }
    internal static KnowledgeSourceFile Open(string path)
    {
        string filename=Path.GetFileName(path);RuntimeClient.ValidateKnowledgeFilename(filename);
        return new(OpenRegular(path,RuntimeClient.MaximumKnowledgeSourceBytes),filename);
    }
    internal static FileStream OpenBackup(string path)=>OpenRegular(path,RuntimeClient.MaximumKnowledgeBackupBytes);
    internal static FileStream OpenConversationDraft(string path)
    {
        if (!path.EndsWith(".workspace-draft.md", StringComparison.OrdinalIgnoreCase)) throw ConversationDraftFormat.Invalid();
        return OpenRegular(path, ConversationDraftFormat.MaximumBytes);
    }
    internal static FileStream OpenSearchWorkset(string path)
    {
        if (!path.EndsWith(".workspace-search.md", StringComparison.OrdinalIgnoreCase)) throw KnowledgeWorksetFormat.Invalid();
        return OpenRegular(path, KnowledgeWorksetFormat.MaximumBytes);
    }
    internal static FileStream OpenVisionImage(string path)
    {
        try { return OpenRegular(path,4*1024*1024); }
        catch(Exception) { throw new DesktopException(DesktopError.InvalidRequest); }
    }
    private static FileStream OpenRegular(string path,long maximum)
    {
        SafeFileHandle? handle=null;FileStream? file=null;
        try {
            LocalPath(path);
            // OPEN_REPARSE_POINT binds validation to the object actually opened, never a Runtime path reopen.
            handle=CreateFile(path,0x80000000,1,IntPtr.Zero,3,0x00200000|0x08000000|0x40000000,IntPtr.Zero);
            if(handle.IsInvalid||GetFileType(handle)!=1||!GetFileInformationByHandleEx(handle,9,out var info,(uint)Marshal.SizeOf<AttributeTag>())
                ||(info.Attributes&((uint)FileAttributes.ReparsePoint|(uint)FileAttributes.Directory))!=0)throw new IOException();
            var resolved=new StringBuilder(32768);uint length=GetFinalPathNameByHandle(handle,resolved,(uint)resolved.Capacity,0);
            if(length==0||length>=resolved.Capacity)throw new IOException();string final=resolved.ToString();
            if(!final.StartsWith(@"\\?\",StringComparison.Ordinal)||final.StartsWith(@"\\?\UNC\",StringComparison.OrdinalIgnoreCase))throw new IOException();
            LocalPath(final[4..]);file=new FileStream(handle,FileAccess.Read,65536,true);handle=null;
            if(file.Length<=0)throw new DesktopException(DesktopError.KnowledgeInvalidSource);
            if(file.Length>maximum)throw new DesktopException(maximum==RuntimeClient.MaximumKnowledgeSourceBytes?DesktopError.KnowledgeSourceTooLarge:DesktopError.KnowledgeBackupTooLarge);
            var result=file;file=null;return result;
        }catch(DesktopException){throw;}catch(Exception){throw new DesktopException(DesktopError.KnowledgeInvalidSource);}
        finally{file?.Dispose();handle?.Dispose();}
    }
    private static void LocalPath(string path)
    {
        if(!Path.IsPathFullyQualified(path)||path.StartsWith(@"\\",StringComparison.Ordinal)||path.Contains('\0'))throw new IOException();
        if(new DriveInfo(Path.GetPathRoot(path)!).DriveType==DriveType.Network)throw new IOException();
        for(string? part=path;part is not null;part=Path.GetDirectoryName(part))
            if((File.GetAttributes(part)&FileAttributes.ReparsePoint)!=0)throw new IOException();
    }
    [StructLayout(LayoutKind.Sequential)]private struct AttributeTag {internal uint Attributes;internal uint Tag;}
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]private static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template);
    [DllImport("kernel32.dll",SetLastError=true)]private static extern uint GetFileType(SafeFileHandle file);
    [DllImport("kernel32.dll",SetLastError=true)]private static extern bool GetFileInformationByHandleEx(SafeFileHandle file,int kind,out AttributeTag info,uint size);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]private static extern uint GetFinalPathNameByHandle(SafeFileHandle file,StringBuilder path,uint size,uint flags);
}
