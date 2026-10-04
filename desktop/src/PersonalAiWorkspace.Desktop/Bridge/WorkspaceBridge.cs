using System;
using System.Collections.Generic;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Desktop.Hosting;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal enum NativeWorkspaceEntry { LegacyAssistant, Conversations, Memory, BrowserPairing, MemoryBackup, WorkspaceBackup, CredentialFlow }
internal enum ShellRuntimeState { Available, Unavailable }
internal enum ShellCredentialState { Ready, Missing, Invalid, Unavailable }
internal sealed record ShellStatus(int BridgeVersion, string ApplicationVersion, ShellRuntimeState Runtime,
    ShellCredentialState Credential, string WebView, string[] NativeEntries);
internal interface IWorkspaceNativeActions
{
    Task<ShellStatus> StatusAsync(CancellationToken cancellation);
    Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation);
}

internal sealed class WorkspaceBridge : IDisposable
{
    internal const int Version = 1;
    internal const int MaximumBytes = 32 * 1024;
    internal const int MaximumRequestsPerSession = 4096;
    internal const int MaximumPending = 8;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    { Converters = { new System.Text.Json.Serialization.JsonStringEnumConverter() } };
    private readonly WorkspaceContentPolicy policy;
    private readonly IWorkspaceNativeActions native;
    private readonly Action<string> send;
    private readonly HashSet<string> requests = new(StringComparer.Ordinal);
    private CancellationTokenSource sessionLifetime = new();
    private string? document;
    private bool ready;
    private int pending;
    internal string SessionId { get; private set; } = "";
    internal static IReadOnlyDictionary<string, NativeWorkspaceEntry> NativeMethods { get; } =
        new System.Collections.ObjectModel.ReadOnlyDictionary<string, NativeWorkspaceEntry>(new Dictionary<string, NativeWorkspaceEntry>
        {
            ["native.openLegacyAssistant"] = NativeWorkspaceEntry.LegacyAssistant,
            ["native.openConversations"] = NativeWorkspaceEntry.Conversations,
            ["native.openMemory"] = NativeWorkspaceEntry.Memory,
            ["native.openBrowserPairing"] = NativeWorkspaceEntry.BrowserPairing,
            ["native.openMemoryBackup"] = NativeWorkspaceEntry.MemoryBackup,
            ["native.openWorkspaceBackup"] = NativeWorkspaceEntry.WorkspaceBackup,
            ["native.openCredentialFlow"] = NativeWorkspaceEntry.CredentialFlow
        });

    internal WorkspaceBridge(WorkspaceContentPolicy policy, IWorkspaceNativeActions native, Action<string> send)
    { this.policy = policy; this.native = native; this.send = send; }

    internal void BeginDocument(string address)
    {
        Invalidate();
        if (!policy.Document(address)) return;
        document = address;
        SessionId = Guid.NewGuid().ToString("D");
    }

    internal void Ready(string currentDocument)
    {
        if (document is null || !policy.SameDocument(document, currentDocument)) return;
        ready = true;
        Send(new { type = "shell.session", version = Version, sessionId = SessionId });
    }

    internal void Invalidate()
    {
        ready = false; document = null; SessionId = ""; requests.Clear(); pending = 0;
        sessionLifetime.Cancel(); sessionLifetime.Dispose(); sessionLifetime = new();
    }

    // Admission failures are silently dropped: no reflection, raw diagnostics or response oracle.
    internal async Task ReceiveAsync(string source, string currentDocument, string message)
    {
        if (!ready || document is null || !policy.SameDocument(source, document)
            || !policy.SameDocument(currentDocument, document) || message.Length > MaximumBytes
            || Encoding.UTF8.GetByteCount(message) > MaximumBytes || pending >= MaximumPending) return;
        string requestId, method, session;
        try
        {
            using var parsed = JsonDocument.Parse(message, new JsonDocumentOptions { MaxDepth = 4 });
            var root = parsed.RootElement;
            if (!Fields(root, "version", "sessionId", "requestId", "method", "payload")
                || !root.GetProperty("version").TryGetInt32(out int version) || version != Version
                || !Text(root, "sessionId", 36, out session) || session != SessionId
                || !Text(root, "requestId", 36, out requestId) || !CanonicalId(requestId)
                || !Text(root, "method", 64, out method)
                || method is not ("shell.bootstrap" or "shell.refreshStatus") && !NativeMethods.ContainsKey(method)
                || !Fields(root.GetProperty("payload")) || requests.Count >= MaximumRequestsPerSession
                || !requests.Add(requestId)) return;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException) { return; }

        var cancellation = sessionLifetime.Token;
        pending++;
        object response;
        try
        {
            if (NativeMethods.TryGetValue(method, out var entry))
            {
                await native.OpenAsync(entry, cancellation);
                response = new { version = Version, sessionId = session, requestId, ok = true, result = new { opened = true } };
            }
            else
            {
                var status = await native.StatusAsync(cancellation);
                response = new { version = Version, sessionId = session, requestId, ok = true, result = status };
            }
        }
        catch (OperationCanceledException) { return; }
        catch (Exception)
        {
            response = new { version = Version, sessionId = session, requestId, ok = false,
                error = new { code = "NATIVE_UNAVAILABLE", message = "原生操作暂时不可用，请从托盘打开 Assistant 后重试。" } };
        }
        finally { if (session == SessionId) pending--; }
        if (ready && session == SessionId && !cancellation.IsCancellationRequested) Send(response);
    }

    private void Send(object value)
    {
        string message = JsonSerializer.Serialize(value, Json);
        if (Encoding.UTF8.GetByteCount(message) > MaximumBytes) return;
        send(message);
    }
    internal static bool CanonicalId(string value) => Guid.TryParseExact(value, "D", out var id)
        && id != Guid.Empty && id.ToString("D") == value;
    private static bool Text(JsonElement root, string name, int maximum, out string value)
    {
        var field = root.GetProperty(name); value = field.ValueKind == JsonValueKind.String ? field.GetString()! : "";
        return value.Length is > 0 && value.Length <= maximum;
    }
    private static bool Fields(JsonElement value, params string[] expected)
    {
        if (value.ValueKind != JsonValueKind.Object) return false;
        var remaining = new HashSet<string>(expected, StringComparer.Ordinal);
        foreach (var field in value.EnumerateObject()) if (!remaining.Remove(field.Name)) return false;
        return remaining.Count == 0;
    }
    public void Dispose() { Invalidate(); sessionLifetime.Dispose(); }
}
