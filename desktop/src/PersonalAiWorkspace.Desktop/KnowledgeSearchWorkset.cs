using System;
using System.Collections.Generic;
using System.Globalization;
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

internal sealed record KnowledgeWorksetSource(string DocumentId, string SourceRevision, string Title, string CreatedAt)
{
    public override string ToString() => "KnowledgeWorksetSource[redacted]";
}
internal sealed record KnowledgeSearchWorkset(string Query, KnowledgeWorksetSource[] Sources, DateTimeOffset SavedAt)
{
    public override string ToString() => "KnowledgeSearchWorkset[redacted]";
}
internal interface IKnowledgeSearchWorksetFiles
{
    Task<bool> SaveAsync(KnowledgeSearchWorkset workset, CancellationToken token);
    Task<KnowledgeSearchWorkset?> OpenAsync(CancellationToken token);
}
internal static class KnowledgeWorksetFormat
{
    internal const int MaximumBytes = 32 * 1024;
    private const string Header = "# Workspace knowledge search v1\n\n```json\n", Footer = "\n```\n";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    internal static WorkspaceOperationException Invalid() => new("KnowledgeWorksetInvalid", "检索工作集文件无效、过大或版本不支持，当前检索未改变。");
    internal static bool ValidInput(string? query, IReadOnlyList<KnowledgeSourceScope>? sources)
    {
        if (query is null || query.Length > 256 || query.Any(char.IsControl) || query.EnumerateRunes().Count() > 128
            || sources is null || sources.Count is < 1 or > 10) return false;
        try { _ = new UTF8Encoding(false, true).GetByteCount(query); }
        catch (EncoderFallbackException) { return false; }
        return sources.All(source => source is not null && WorkspaceBridge.CanonicalId(source.DocumentId)
            && source.SourceRevision is "1" or "2" or "3" or "4" or "5" or "6" or "7" or "8" or "9" or "10")
            && sources.Select(source => source.DocumentId).Distinct(StringComparer.Ordinal).Count() == sources.Count;
    }
    internal static void Validate(KnowledgeSearchWorkset workset)
    {
        if (workset.Sources is null || workset.Sources.Any(source => source is null)
            || !ValidInput(workset.Query, workset.Sources.Select(source => new KnowledgeSourceScope(source.DocumentId, source.SourceRevision)).ToArray())
            || workset.SavedAt == default || workset.SavedAt.Offset != TimeSpan.Zero) throw Invalid();
        foreach (var source in workset.Sources)
        {
            if (string.IsNullOrWhiteSpace(source.Title) || source.Title.Length > 320 || source.Title.EnumerateRunes().Count() > 160
                || source.Title.Any(char.IsControl) || source.CreatedAt is null || source.CreatedAt.Length > 64
                || !DateTimeOffset.TryParse(source.CreatedAt, CultureInfo.InvariantCulture, DateTimeStyles.None, out _)) throw Invalid();
            try { _ = new UTF8Encoding(false, true).GetByteCount(source.Title); }
            catch (EncoderFallbackException) { throw Invalid(); }
        }
    }
    internal static string Format(KnowledgeSearchWorkset workset)
    {
        Validate(workset);
        string text = Header + JsonSerializer.Serialize(workset, Json) + Footer;
        if (Encoding.UTF8.GetByteCount(text) > MaximumBytes) throw Invalid();
        return text;
    }
    internal static async Task<KnowledgeSearchWorkset> ReadAsync(Stream stream, CancellationToken token)
    {
        try
        {
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
            static bool Fields(JsonElement value, params string[] names) => value.ValueKind == JsonValueKind.Object
                && value.EnumerateObject().Count() == names.Length && names.All(name => value.TryGetProperty(name, out _));
            var root = json.RootElement;
            if (!Fields(root, "query", "sources", "savedAt") || root.GetProperty("sources").ValueKind != JsonValueKind.Array
                || root.GetProperty("sources").GetArrayLength() is < 1 or > 10
                || root.GetProperty("sources").EnumerateArray().Any(source => !Fields(source, "documentId", "sourceRevision", "title", "createdAt"))) throw Invalid();
            var workset = root.Deserialize<KnowledgeSearchWorkset>(Json) ?? throw Invalid();
            Validate(workset); return workset;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { throw Invalid(); }
    }
}
internal sealed class NativeKnowledgeSearchWorksetFiles(Func<Window?> owner) : IKnowledgeSearchWorksetFiles
{
    public async Task<bool> SaveAsync(KnowledgeSearchWorkset workset, CancellationToken token)
    {
        string text = KnowledgeWorksetFormat.Format(workset);
        token.ThrowIfCancellationRequested(); var window = owner() ?? throw KnowledgeWorksetFormat.Invalid();
        var picker = new SaveFileDialog { Title = "保存检索工作集 · 明文副本 · 请选择新文件", Filter = "检索工作集 (*.workspace-search.md)|*.workspace-search.md",
            DefaultExt = ".workspace-search.md", AddExtension = true, FileName = "knowledge.workspace-search.md", OverwritePrompt = true };
        bool selected = picker.ShowDialog(window) == true; token.ThrowIfCancellationRequested(); window.Activate();
        if (!selected) return false;
        if (!picker.FileName.EndsWith(".workspace-search.md", StringComparison.OrdinalIgnoreCase)) throw KnowledgeWorksetFormat.Invalid();
        await NativeKnowledgeAnswerExport.SaveNewAsync(picker.FileName, text, token); return true;
    }
    public async Task<KnowledgeSearchWorkset?> OpenAsync(CancellationToken token)
    {
        token.ThrowIfCancellationRequested(); var window = owner() ?? throw KnowledgeWorksetFormat.Invalid();
        var picker = new OpenFileDialog { Title = "打开检索工作集 · 核对后恢复", Filter = "检索工作集 (*.workspace-search.md)|*.workspace-search.md",
            Multiselect = false, CheckFileExists = true };
        bool selected = picker.ShowDialog(window) == true; token.ThrowIfCancellationRequested(); window.Activate();
        if (!selected) return null;
        using var file = NativeKnowledgeSourceFiles.OpenSearchWorkset(picker.FileName);
        return await KnowledgeWorksetFormat.ReadAsync(file, token);
    }
}
