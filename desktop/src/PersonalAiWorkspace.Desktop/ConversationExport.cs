using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal interface IConversationExport
{
    Task<bool> SaveAsync(Guid conversationId, string markdown, CancellationToken token);
}

internal static class ConversationMarkdown
{
    internal const int MaximumBytes = 4 * 1024 * 1024;
    internal static async Task<string> ReadAsync(Guid id, Func<int, CancellationToken, Task<ConversationDetail>> read, CancellationToken token)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(30));
        try
        {
            var first = await read(0, deadline.Token);
            var turns = new List<ConversationTurn>(); var turnIds = new HashSet<Guid>(); var messageIds = new HashSet<Guid>();
            int bytes = 0;
            for (int page = 0; page < Math.Max(1, (first.TotalTurns + 9) / 10); page++)
            {
                var part = page == 0 ? first : await read(page, deadline.Token);
                RequireSame(first, part, id, page);
                foreach (var turn in part.Turns)
                {
                    if (turn.Status == ConversationTurnStatus.PENDING) throw NotReady();
                    if (turn.ConversationId != id || turn.Sequence != turns.Count + 1 || !turnIds.Add(turn.Id)
                        || !messageIds.Add(turn.UserMessage.Id) || turn.AssistantMessage is { } assistant && !messageIds.Add(assistant.Id))
                        throw new DesktopException(DesktopError.InvalidResponse);
                    bytes = checked(bytes + Encoding.UTF8.GetByteCount(turn.UserMessage.Content)
                        + (turn.AssistantMessage is null ? 0 : Encoding.UTF8.GetByteCount(turn.AssistantMessage.Content)));
                    if (bytes > MaximumBytes) throw new DesktopException(DesktopError.ConversationExportTooLarge);
                    turns.Add(turn);
                }
            }
            // Terminal turns are immutable in the Store. Recheck metadata and count after all pages;
            // a new turn, rename, archive or deletion cannot silently produce a mixed transcript.
            RequireSame(first, await read(0, deadline.Token), id, 0);
            deadline.Token.ThrowIfCancellationRequested();
            return Format(first.Conversation, turns, DateTimeOffset.UtcNow);
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        { throw new DesktopException(DesktopError.ConversationExportFailed); }
    }

    private static DesktopException NotReady() => new(DesktopError.ConversationExportNotReady);
    private static void RequireSame(ConversationDetail first, ConversationDetail part, Guid id, int page)
    {
        if (first.Conversation.Id != id || first.TotalTurns is < 0 or > 1000 || part.Conversation != first.Conversation
            || part.TotalTurns != first.TotalTurns || part.Page != page || part.Limit != 10
            || part.Turns.Count != Math.Min(10, Math.Max(0, first.TotalTurns - page * 10))) throw NotReady();
    }

    private static string Format(Conversation conversation, IReadOnlyList<ConversationTurn> turns, DateTimeOffset at)
    {
        static string Time(DateTimeOffset value) => value.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture);
        var note = new StringBuilder("# 会话笔记\n\n");
        PlainTextMarkdown.Block(note, conversation.Title);
        note.Append("会话 ID：").Append(conversation.Id.ToString("D")).Append("\n\n")
            .Append("状态：").Append(conversation.Status == ConversationStatus.ACTIVE ? "使用中" : "已归档")
            .Append(" · 总轮次：").Append(turns.Count).Append("\n\n")
            .Append("创建时间（UTC）：").Append(Time(conversation.CreatedAt)).Append("\n\n")
            .Append("最后更新（UTC）：").Append(Time(conversation.UpdatedAt)).Append("\n\n")
            .Append("记录核对时间（UTC）：").Append(Time(at)).Append("\n\n")
            .Append("这是核对时刻的完整持久会话明文副本，不是可恢复备份，不包含未发送草稿。消息按原文保存在纯文本代码块中，末尾换行仅用于排版。\n\n")
            .Append("历史回答可能包含个人信息或当时使用的记忆内容。下列记忆选择只保留 ID／版本／位置，不导出记忆正文，也不代表当时实际纳入模型上下文的范围。\n\n");
        if (turns.Count == 0) note.Append("此会话尚无持久轮次。\n\n");
        foreach (var turn in turns)
        {
            string status = turn.Status switch
            {
                ConversationTurnStatus.SUCCEEDED => "已完成", ConversationTurnStatus.FAILED => "失败",
                ConversationTurnStatus.CANCELLED => "已取消", ConversationTurnStatus.TIMED_OUT => "超时",
                _ => throw NotReady()
            };
            note.Append("## 第 ").Append(turn.Sequence).Append(" 轮 · ").Append(status).Append("\n\n")
                .Append("轮次 ID：").Append(turn.Id.ToString("D")).Append("\n\n")
                .Append("提交（UTC）：").Append(Time(turn.CreatedAt)).Append("\n\n")
                .Append("终态记录（UTC）：").Append(Time(turn.UpdatedAt)).Append("\n\n### 你\n\n");
            PlainTextMarkdown.Block(note, turn.UserMessage.Content);
            note.Append("### 助手\n\n");
            if (turn.AssistantMessage is { } assistant) PlainTextMarkdown.Block(note, assistant.Content);
            else note.Append("本轮没有持久助手回复。\n\n");
            if (turn.FailureCode.HasValue) note.Append("失败代码：").Append(turn.FailureCode.Value).Append("\n\n");
            if (turn.Memories is { Count: > 0 } memories)
            {
                note.Append("持久记录的显式记忆选择：\n\n");
                foreach (var memory in memories.OrderBy(x => x.Position)) note.Append("- 位置 ").Append(memory.Position)
                    .Append(" · ID：").Append(memory.MemoryId.ToString("D")).Append(" · 版本：")
                    .Append(memory.Revision.ToString(CultureInfo.InvariantCulture)).Append('\n');
                note.Append('\n');
            }
            if (note.Length > MaximumBytes) throw new DesktopException(DesktopError.ConversationExportTooLarge);
        }
        string text = note.ToString();
        if (new UTF8Encoding(false, true).GetByteCount(text) > MaximumBytes) throw new DesktopException(DesktopError.ConversationExportTooLarge);
        return text;
    }
}

internal sealed class NativeConversationExport(Func<Window?> owner) : IConversationExport
{
    public async Task<bool> SaveAsync(Guid conversationId, string markdown, CancellationToken token)
    {
        try
        {
            token.ThrowIfCancellationRequested(); var window = owner() ?? throw new DesktopException(DesktopError.ConversationExportFailed);
            var picker = new SaveFileDialog { Title = "导出完整会话 · 明文 · 请选择新文件", Filter = "Markdown (*.md)|*.md",
                DefaultExt = ".md", AddExtension = true, FileName = "conversation-" + conversationId.ToString("D") + ".md", OverwritePrompt = true };
            bool selected = picker.ShowDialog(window) == true; token.ThrowIfCancellationRequested(); window.Activate();
            if (!selected) return false;
            await NativeKnowledgeAnswerExport.SaveNewAsync(picker.FileName, markdown, token); return true;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { throw new DesktopException(DesktopError.ConversationExportFailed); }
    }
}
