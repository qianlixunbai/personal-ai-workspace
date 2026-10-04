using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Desktop.Hosting;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal enum NativeWorkspaceEntry { LegacyAssistant, Conversations, Memory, BrowserPairing, MemoryBackup, WorkspaceBackup, CredentialFlow }
internal enum ShellRuntimeState { Available, Unavailable }
internal enum ShellCredentialState { Ready, Missing, Invalid, Unavailable }
internal sealed record ShellStatus(int BridgeVersion, string ApplicationVersion, ShellRuntimeState Runtime,
    ShellCredentialState Credential, string WebView, string[] NativeEntries);
internal interface IWorkspaceNativeActions
{
    WorkspaceOperations? Operations => null;
    Task<ShellStatus> StatusAsync(CancellationToken cancellation);
    Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation);
}

internal sealed class WorkspaceBridge : IDisposable
{
    internal const int Version = 1;
    internal const int MaximumBytes = 32 * 1024;
    internal const int MaximumResponseBytes = 64 * 1024; // 8192 output bytes can JSON-escape to 49152 bytes.
    internal const int MaximumRequestsPerSession = 4096;
    internal const int MaximumPending = 8;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    { Converters = { new System.Text.Json.Serialization.JsonStringEnumConverter() } };
    private readonly WorkspaceContentPolicy policy;
    private readonly IWorkspaceNativeActions native;
    private readonly WorkspaceOperations? operations;
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
    { this.policy = policy; this.native = native; operations = native.Operations; this.send = send; }

    internal void BeginDocument(string address)
    {
        Invalidate();
        if (!policy.Document(address)) return;
        document = address;
        SessionId = Guid.NewGuid().ToString("D");
        operations?.BeginSession(SessionId);
    }

    internal void Ready(string currentDocument)
    {
        if (document is null || !policy.SameDocument(document, currentDocument)) return;
        ready = true;
        Send(new { type = "shell.session", version = Version, sessionId = SessionId });
    }

    internal void Invalidate()
    {
        operations?.EndSession(SessionId);
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
        JsonElement payload;
        try
        {
            using var parsed = JsonDocument.Parse(message, new JsonDocumentOptions { MaxDepth = 6 });
            var root = parsed.RootElement;
            if (!Fields(root, "version", "sessionId", "requestId", "method", "payload")
                || !root.GetProperty("version").TryGetInt32(out int version) || version != Version
                || !Text(root, "sessionId", 36, out session) || session != SessionId
                || !Text(root, "requestId", 36, out requestId) || !CanonicalId(requestId)
                || !Text(root, "method", 64, out method)
                || !ValidPayload(method, root.GetProperty("payload")) || requests.Count >= MaximumRequestsPerSession
                || !requests.Add(requestId)) return;
            payload = root.GetProperty("payload").Clone();
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException) { return; }

        var cancellation = sessionLifetime.Token;
        pending++;
        object response;
        try
        {
            object result;
            if (BusinessMethods.Contains(method))
            {
                if (operations is null) throw new WorkspaceOperationException("NATIVE_UNAVAILABLE", "业务操作暂时不可用，请使用原生 Assistant。");
                result = method switch
                {
                    "assistant.selectMemories" => await operations.SelectAsync(session, cancellation),
                    "assistant.submit" => await operations.SubmitAsync(session,
                        new(payload.GetProperty("mode").GetString() == "Ask" ? AssistantAction.Ask : AssistantAction.Summarize,
                            payload.GetProperty("text").GetString()!),
                        payload.GetProperty("selectedMemoryRefs").EnumerateArray().Select(x => new SelectedMemoryRef(
                            x.GetProperty("memoryId").GetString()!, x.GetProperty("revision").GetString()!, x.GetProperty("position").GetInt32())).ToArray()),
                    "translate.submit" => await operations.SubmitAsync(session,
                        new(AssistantAction.Translate, payload.GetProperty("text").GetString()!, payload.GetProperty("targetLanguage").GetString()!), []),
                    "operations.get" => operations.Get(session, payload.GetProperty("operationId").GetString()!),
                    "operations.cancel" => operations.Cancel(session, payload.GetProperty("operationId").GetString()!),
                    "operations.copyResult" => operations.Copy(session, payload.GetProperty("operationId").GetString()!),
                    _ => throw new InvalidOperationException()
                };
            }
            else if (NativeMethods.TryGetValue(method, out var entry))
            {
                await native.OpenAsync(entry, cancellation);
                result = new { opened = true };
            }
            else
            {
                result = await native.StatusAsync(cancellation);
            }
            response = new { version = Version, sessionId = session, requestId, ok = true, result };
        }
        catch (OperationCanceledException) { return; }
        catch (DesktopException error)
        {
            response = new { version = Version, sessionId = session, requestId, ok = false,
                error = new WorkspaceSafeError(error.Error.ToString(), ErrorText.For(error.Error)) };
        }
        catch (WorkspaceOperationException error)
        {
            response = new { version = Version, sessionId = session, requestId, ok = false,
                error = new WorkspaceSafeError(error.Code, error.Message) };
        }
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
        if (Encoding.UTF8.GetByteCount(message) > MaximumResponseBytes) return;
        send(message);
    }
    internal static bool CanonicalId(string value) => Guid.TryParseExact(value, "D", out var id)
        && id != Guid.Empty && id.ToString("D") == value;
    internal static readonly IReadOnlySet<string> BusinessMethods = new HashSet<string>(StringComparer.Ordinal)
    { "assistant.selectMemories", "assistant.submit", "translate.submit", "operations.get", "operations.cancel", "operations.copyResult" };
    private static bool ValidPayload(string method, JsonElement payload)
    {
        if (method is "shell.bootstrap" or "shell.refreshStatus" || NativeMethods.ContainsKey(method)
            || method == "assistant.selectMemories") return Fields(payload);
        if (method is "operations.get" or "operations.cancel" or "operations.copyResult")
            return Fields(payload, "operationId") && Text(payload, "operationId", 36, out var id) && CanonicalId(id);
        if (method == "translate.submit")
            return Fields(payload, "text", "targetLanguage") && Text(payload, "text", 4000, out _)
                && Text(payload, "targetLanguage", 35, out _);
        if (method != "assistant.submit" || !Fields(payload, "mode", "text", "selectedMemoryRefs")
            || !Text(payload, "mode", 9, out var mode) || mode is not ("Ask" or "Summarize")
            || !Text(payload, "text", mode == "Ask" ? 3000 : 6000, out _)) return false;
        var references = payload.GetProperty("selectedMemoryRefs");
        if (references.ValueKind != JsonValueKind.Array || references.GetArrayLength() > 4
            || mode == "Summarize" && references.GetArrayLength() != 0) return false;
        int position = 0;
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var reference in references.EnumerateArray())
        {
            if (!Fields(reference, "memoryId", "revision", "position")
                || !Text(reference, "memoryId", 36, out var id) || !CanonicalId(id) || !ids.Add(id)
                || !Text(reference, "revision", 19, out var revision) || revision[0] is < '1' or > '9'
                || revision.Any(x => x is < '0' or > '9')
                || !long.TryParse(revision, NumberStyles.None, CultureInfo.InvariantCulture, out long number) || number <= 0
                || !reference.GetProperty("position").TryGetInt32(out int index) || index != position++) return false;
        }
        return true;
    }
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
