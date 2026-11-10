using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;

namespace PersonalAiWorkspace.Desktop;

internal sealed record ConversationDraftFile(Guid ConversationId, DateTimeOffset ConversationCreatedAt,
    string ConversationTitle, string Message, DateTimeOffset SavedAt, int TotalTurns)
{
    public override string ToString() => "ConversationDraftFile[redacted]";
}
internal interface IConversationDraftFiles
{
    Task<bool> SaveAsync(ConversationDraftFile draft, CancellationToken token);
    Task<ConversationDraftFile?> OpenAsync(CancellationToken token);
}
internal static class ConversationDraftFormat
{
    internal const int MaximumBytes = 32 * 1024;
    private const string Header = "# Workspace conversation draft v1\n\n```json\n", Footer = "\n```\n";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    internal static WorkspaceOperationException Invalid() => new("ConversationDraftInvalid", "草稿文件无效、过大或版本不支持，当前草稿未改变。");
    internal static bool ValidMessage(string? message)
    {
        if (message is null || message.Length is < 1 or > 3000 || message.Contains('\0')) return false;
        try { return new UTF8Encoding(false, true).GetByteCount(message) <= 12000; }
        catch (EncoderFallbackException) { return false; }
    }
    internal static void Validate(ConversationDraftFile draft)
    {
        if (draft.ConversationId == Guid.Empty || !ValidMessage(draft.Message) || draft.ConversationTitle is null
            || draft.ConversationTitle.Length is < 1 or > 320 || draft.ConversationTitle.Contains('\0')
            || draft.ConversationTitle.EnumerateRunes().Count() > 160 || draft.TotalTurns is < 0 or > 1000
            || draft.ConversationCreatedAt == default || draft.SavedAt == default
            || draft.ConversationCreatedAt.Offset != TimeSpan.Zero || draft.SavedAt.Offset != TimeSpan.Zero) throw Invalid();
        try { _ = new UTF8Encoding(false, true).GetByteCount(draft.ConversationTitle); }
        catch (EncoderFallbackException) { throw Invalid(); }
    }
    internal static string Format(ConversationDraftFile draft)
    {
        Validate(draft);
        string text = Header + JsonSerializer.Serialize(draft, Json) + Footer;
        if (Encoding.UTF8.GetByteCount(text) > MaximumBytes) throw Invalid();
        return text;
    }
    internal static async Task<ConversationDraftFile> ReadAsync(Stream stream, CancellationToken token)
    {
        try
        {
            // Read one byte beyond the cap, never allocate based on file-supplied lengths.
            byte[] bytes = new byte[MaximumBytes + 1]; int count = 0;
            while (count < bytes.Length)
            {
                int read = await stream.ReadAsync(bytes.AsMemory(count), token);
                if (read == 0) break;
                count += read;
            }
            if (count > MaximumBytes) throw Invalid();
            string text = new UTF8Encoding(false, true).GetString(bytes, 0, count);
            if (!text.StartsWith(Header, StringComparison.Ordinal) || !text.EndsWith(Footer, StringComparison.Ordinal)) throw Invalid();
            using var json = JsonDocument.Parse(text[Header.Length..^Footer.Length], new JsonDocumentOptions { MaxDepth = 4 });
            string[] fields = ["conversationId", "conversationCreatedAt", "conversationTitle", "message", "savedAt", "totalTurns"];
            var root = json.RootElement;
            if (root.ValueKind != JsonValueKind.Object || root.EnumerateObject().Count() != fields.Length
                || !fields.All(field => root.TryGetProperty(field, out _))) throw Invalid();
            var draft = root.Deserialize<ConversationDraftFile>(Json) ?? throw Invalid();
            Validate(draft); return draft;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { throw Invalid(); }
    }
}
internal sealed class NativeConversationDraftFiles(Func<Window?> owner) : IConversationDraftFiles
{
    public async Task<bool> SaveAsync(ConversationDraftFile draft, CancellationToken token)
    {
        string text = ConversationDraftFormat.Format(draft);
        token.ThrowIfCancellationRequested(); var window = owner() ?? throw ConversationDraftFormat.Invalid();
        var picker = new SaveFileDialog { Title = "保存会话草稿 · 明文副本 · 请选择新文件", Filter = "Workspace 草稿 (*.workspace-draft.md)|*.workspace-draft.md",
            DefaultExt = ".workspace-draft.md", AddExtension = true, FileName = "conversation-" + draft.ConversationId.ToString("D") + ".workspace-draft.md", OverwritePrompt = true };
        bool selected = picker.ShowDialog(window) == true; token.ThrowIfCancellationRequested(); window.Activate();
        if (!selected) return false;
        if (!picker.FileName.EndsWith(".workspace-draft.md", StringComparison.OrdinalIgnoreCase))
            throw new WorkspaceOperationException("ConversationDraftInvalid", "请选择以 .workspace-draft.md 结尾的新文件，当前草稿仍保留。");
        await NativeKnowledgeAnswerExport.SaveNewAsync(picker.FileName, text, token); return true;
    }
    public async Task<ConversationDraftFile?> OpenAsync(CancellationToken token)
    {
        token.ThrowIfCancellationRequested(); var window = owner() ?? throw ConversationDraftFormat.Invalid();
        var picker = new OpenFileDialog { Title = "打开会话草稿 · 预览后确认恢复", Filter = "Workspace 草稿 (*.workspace-draft.md)|*.workspace-draft.md",
            Multiselect = false, CheckFileExists = true };
        bool selected = picker.ShowDialog(window) == true; token.ThrowIfCancellationRequested(); window.Activate();
        if (!selected) return null;
        using var file = NativeKnowledgeSourceFiles.OpenConversationDraft(picker.FileName);
        return await ConversationDraftFormat.ReadAsync(file, token);
    }
}
