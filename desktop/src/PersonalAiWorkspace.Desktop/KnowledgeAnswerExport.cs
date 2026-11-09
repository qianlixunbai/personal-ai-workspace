using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal interface IKnowledgeAnswerExport
{
    Task<bool> SaveAsync(Guid taskId,string markdown,CancellationToken token);
}

internal static class KnowledgeAnswerMarkdown
{
    internal static string Format(KnowledgeAnswerHistoryEntry entry,IReadOnlyList<string> excerpts,DateTimeOffset exportedAt)
    {
        var result=entry.Task.Result??throw new DesktopException(DesktopError.KnowledgeExportFailed);
        if(excerpts.Count!=result.Citations.Count)throw new DesktopException(DesktopError.KnowledgeExportFailed);
        var note=new StringBuilder("# 知识问答笔记\n\n");
        note.Append("导出时间（UTC）：").Append(exportedAt.ToUniversalTime().ToString("O",CultureInfo.InvariantCulture)).Append("\n\n")
            .Append("问答 ID：").Append(entry.Task.TaskId.ToString("D")).Append("\n\n")
            .Append("这是已保存问答及引用摘录的明文副本，不是完整资料备份。引用可定位不代表回答的事实已经核实。\n\n")
            .Append("正文均按纯文本保留；代码块末尾的换行用于排版。偏移按规范化原文的 UTF-16 单位计数，起点包含、终点不包含。\n\n")
            .Append("## 当时的资料范围\n\n");
        if(entry.Scope is { } scope)note.Append("指定文档：").Append(scope.DocumentId).Append(" · 源版本 ").Append(scope.SourceRevision).Append("\n\n");
        else note.Append("全部可用资料（未限定单份文档；以下仅列出实际引用）。\n\n");
        Section(note,"问题",entry.Question);Section(note,"检索关键词",entry.Query);Section(note,"回答",result.Answer);
        note.Append("## 引用原文\n\n");
        for(int i=0;i<result.Citations.Count;i++){
            var citation=result.Citations[i];
            if(excerpts[i].Length!=citation.EndOffset-citation.StartOffset)throw new DesktopException(DesktopError.KnowledgeExportFailed);
            note.Append("### 来源 ").Append(i+1).Append("\n\n");
            Block(note,citation.Title);
            note.Append("文档 ID：").Append(citation.DocumentId).Append("\n\n源版本：").Append(citation.SourceRevision)
                .Append(" · 类型：").Append(citation.SourceType).Append(" · 行：").Append(citation.StartLine).Append('–').Append(citation.EndLine)
                .Append(" · UTF-16 范围：[").Append(citation.StartOffset).Append(", ").Append(citation.EndOffset).Append(")\n\n");
            if(citation.Heading is { } heading){note.Append("章节标题：\n\n");Block(note,heading);}
            Block(note,excerpts[i]);
        }
        return note.ToString();
    }
    private static void Section(StringBuilder note,string heading,string text)
    {note.Append("## ").Append(heading).Append("\n\n");Block(note,text);}
    private static void Block(StringBuilder note,string text)
        =>PlainTextMarkdown.Block(note,text);
}

internal sealed class NativeKnowledgeAnswerExport(Func<Window?> owner):IKnowledgeAnswerExport
{
    public async Task<bool> SaveAsync(Guid taskId,string markdown,CancellationToken token)
    {
        token.ThrowIfCancellationRequested();var window=owner()??throw new DesktopException(DesktopError.KnowledgeFileUnavailable);
        var picker=new SaveFileDialog{Title="导出问答笔记 · 明文 · 请选择新文件",Filter="Markdown (*.md)|*.md",DefaultExt=".md",AddExtension=true,
            FileName="knowledge-answer-"+taskId.ToString("D")+".md",OverwritePrompt=true};
        bool selected=picker.ShowDialog(window)==true;token.ThrowIfCancellationRequested();window.Activate();
        if(!selected)return false;
        await SaveNewAsync(picker.FileName,markdown,token);return true;
    }
    internal static Task SaveNewAsync(string destination,string markdown,CancellationToken token)
        => SaveNewAsync(destination,markdown,token,KnowledgeBackupWindow.ProtectFile);

    // The protection callback is internal for deterministic failure/cancellation tests on synthetic files.
    internal static async Task SaveNewAsync(string destination,string markdown,CancellationToken token,Action<FileStream> protectFile)
    {
        string? staging=null;
        try{
            token.ThrowIfCancellationRequested();
            if(!Path.IsPathFullyQualified(destination)||destination.StartsWith(@"\\",StringComparison.Ordinal)
                ||!string.Equals(Path.GetExtension(destination),".md",StringComparison.OrdinalIgnoreCase)
                ||destination.AsSpan(2).Contains(':')||File.Exists(destination)||Directory.Exists(destination))throw new IOException();
            string parent=Path.GetDirectoryName(destination)!;NoLinks(parent);
            if(new DriveInfo(Path.GetPathRoot(destination)!).DriveType==DriveType.Network)throw new IOException();
            // Same-directory staging and no replacement: existing user documents are never overwritten.
            string candidate=Path.Combine(parent,".knowledge-note-"+Guid.NewGuid().ToString("N")+".tmp");
            using(var file=new FileStream(candidate,FileMode.CreateNew,FileAccess.Write,FileShare.None,65536,true)){
                staging=candidate;protectFile(file);
                await file.WriteAsync(new UTF8Encoding(false,true).GetBytes(markdown),token);
                await file.FlushAsync(token);file.Flush(true);
            }
            token.ThrowIfCancellationRequested();NoLinks(parent);File.Move(staging,destination,false);staging=null;
        }
        catch(OperationCanceledException){throw;}
        catch(Exception){throw new DesktopException(DesktopError.KnowledgeFileUnavailable);}
        finally{if(staging is not null)try{NoLinks(staging);File.Delete(staging);}catch(Exception){}}
    }
    private static void NoLinks(string path)
    {for(string? part=path;part is not null;part=Path.GetDirectoryName(part))if((File.GetAttributes(part)&FileAttributes.ReparsePoint)!=0)throw new IOException();}
}
