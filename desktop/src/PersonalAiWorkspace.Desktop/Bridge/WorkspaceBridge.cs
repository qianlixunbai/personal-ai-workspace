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

internal enum NativeWorkspaceEntry { LegacyAssistant, Conversations, Memory, BrowserPairing, MemoryBackup, WorkspaceBackup, CredentialFlow, KnowledgeBackup }
internal enum ShellRuntimeState { Available, Unavailable }
internal enum ShellCredentialState { Ready, Missing, Invalid, Unavailable }
internal sealed record ShellStatus(int BridgeVersion, string ApplicationVersion, ShellRuntimeState Runtime,
    ShellCredentialState Credential, string WebView, string[] NativeEntries);
internal interface IWorkspaceNativeActions
{
    WorkspaceOperations? Operations => null;
    WorkspaceConversations? Conversations => null;
    WorkspaceMemory? Memory => null;
    WorkspaceKnowledge? Knowledge => null;
    Task<ShellStatus> StatusAsync(CancellationToken cancellation);
    Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation);
}

internal sealed class WorkspaceBridge : IDisposable
{
    internal const int Version = 1;
    internal const int MaximumBytes = 32 * 1024;
    internal const int MaximumResponseBytes = 64 * 1024; // 8192 output bytes can JSON-escape to 49152 bytes.
    // 20 messages * 8192 UTF-8 bytes * 6 worst JSON escaping = 983040.
    // 1920 title + <= 40 KiB for 10 turns, 40 refs, UUID/time/enum fields and envelope.
    // No truncation; all other methods retain 64 KiB.
    internal const int MaximumConversationResponseBytes = 1024 * 1024;
    internal const int MaximumRequestsPerSession = 4096;
    internal const int MaximumPending = 8;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    { Converters = { new System.Text.Json.Serialization.JsonStringEnumConverter() } };
    private readonly WorkspaceContentPolicy policy;
    private readonly IWorkspaceNativeActions native;
    private readonly WorkspaceOperations? operations;
    private readonly WorkspaceConversations? conversations;
    private readonly WorkspaceMemory? memory;
    private readonly WorkspaceKnowledge? knowledge;
    internal bool EditorDirty { get; private set; }
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
            ["native.openBrowserPairing"] = NativeWorkspaceEntry.BrowserPairing,
            ["native.openMemoryBackup"] = NativeWorkspaceEntry.MemoryBackup,
            ["native.openWorkspaceBackup"] = NativeWorkspaceEntry.WorkspaceBackup,
            ["native.openKnowledgeBackup"] = NativeWorkspaceEntry.KnowledgeBackup,
            ["native.openCredentialFlow"] = NativeWorkspaceEntry.CredentialFlow
        });

    internal WorkspaceBridge(WorkspaceContentPolicy policy, IWorkspaceNativeActions native, Action<string> send)
    { this.policy = policy; this.native = native; operations = native.Operations; conversations = native.Conversations; memory = native.Memory; knowledge=native.Knowledge; this.send = send; }

    internal void BeginDocument(string address)
    {
        Invalidate();
        if (!policy.Document(address)) return;
        document = address;
        EditorDirty = false;
        SessionId = Guid.NewGuid().ToString("D");
        operations?.BeginSession(SessionId);
        conversations?.BeginSession(SessionId);
        memory?.BeginSession(SessionId);
        knowledge?.BeginSession(SessionId);
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
        conversations?.EndSession(SessionId);
        memory?.EndSession(SessionId);
        knowledge?.EndSession(SessionId);
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
            if(KnowledgeMethods.Contains(method))
            {
                if(knowledge is null)throw new WorkspaceOperationException("NATIVE_UNAVAILABLE","Knowledge 暂时不可用。");
                string? id=payload.TryGetProperty("documentId",out var documentId)&&documentId.ValueKind==JsonValueKind.String?documentId.GetString():null;
                result=method switch {
                    "knowledge.list"=>await knowledge.ListAsync(session,payload.GetProperty("status").GetString()!,payload.GetProperty("page").GetInt32(),cancellation),
                    "knowledge.get"=>await knowledge.GetAsync(session,id!,cancellation),
                    "knowledge.preview"=>await knowledge.PreviewAsync(session,id!,payload.GetProperty("sourceRevision").GetString()!,payload.GetProperty("offset").GetInt32(),cancellation),
                    "knowledge.import"=>await knowledge.ImportAsync(session,id,payload.GetProperty("expectedMetadataVersion").ValueKind==JsonValueKind.Null?null:payload.GetProperty("expectedMetadataVersion").GetString(),cancellation),
                    "knowledge.importState"=>await knowledge.ImportStateAsync(session,payload.GetProperty("requestId").GetString()!,cancellation),
                    "knowledge.cancelImport"=>await knowledge.CancelAsync(session,id!,payload.GetProperty("requestId").GetString()!,cancellation),
                    _=>await knowledge.LifecycleAsync(session,id!,payload.GetProperty("expectedMetadataVersion").GetString()!,method,cancellation)
                };
            }
            else if (method == "memory.editorState")
            {
                EditorDirty = payload.GetProperty("dirty").GetBoolean();
                result = new { acknowledged = true };
            }
            else if (MemoryMethods.Contains(method))
            {
                if (memory is null) throw new WorkspaceOperationException("NATIVE_UNAVAILABLE", "Memory 暂时不可用，请打开原生窗口。");
                var id = method is "memory.list" or "memory.create" ? Guid.Empty : Guid.ParseExact(payload.GetProperty("memoryId").GetString()!, "D");
                result = method switch
                {
                    "memory.list" => await memory.ListAsync(session, ReadMemoryQuery(payload), cancellation),
                    "memory.get" => await memory.GetAsync(session, id, cancellation),
                    "memory.create" => await memory.CreateAsync(session, new(ReadMemoryType(payload), payload.GetProperty("title").GetString()!, payload.GetProperty("content").GetString()!), cancellation),
                    "memory.update" => await memory.UpdateAsync(session, id, new(ReadRevision(payload), ReadMemoryType(payload), payload.GetProperty("title").GetString()!, payload.GetProperty("content").GetString()!), cancellation),
                    _ => await memory.LifecycleAsync(session, id, ReadRevision(payload), method, cancellation)
                };
            }
            else if (ConversationMethods.Contains(method))
            {
                if (conversations is null) throw new WorkspaceOperationException("NATIVE_UNAVAILABLE", "Conversation 暂时不可用，请打开原生窗口。");
                var id = method is "conversations.list" or "conversations.create" ? Guid.Empty : Guid.ParseExact(payload.GetProperty("conversationId").GetString()!, "D");
                result = method switch
                {
                    "conversations.list" => await conversations.ListAsync(session, Enum.Parse<ConversationStatus>(payload.GetProperty("status").GetString()!), payload.GetProperty("page").GetInt32(), cancellation),
                    "conversations.get" => await conversations.GetAsync(session, id, payload.GetProperty("page").GetInt32(), cancellation),
                    "conversations.create" => await conversations.CreateAsync(session, cancellation),
                    "conversations.selectMemories" => await conversations.SelectAsync(session, id, cancellation),
                    "conversations.clearMemories" => conversations.ClearMemories(session, id),
                    "conversations.send" => await conversations.SendAsync(session, id, payload.GetProperty("message").GetString()!, ReadReferences(payload)),
                    "conversations.cancelPending" => await conversations.CancelAsync(session, id, Guid.ParseExact(payload.GetProperty("turnId").GetString()!, "D"), cancellation),
                    _ => await conversations.MutateAsync(session, id, method, method == "conversations.rename" ? payload.GetProperty("title").GetString() : null, cancellation)
                };
            }
            else if (BusinessMethods.Contains(method))
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
        if (ready && session == SessionId && !cancellation.IsCancellationRequested) Send(response, method, session, requestId);
    }

    private void Send(object value, string? method = null, string? session = null, string? requestId = null)
    {
        string message = JsonSerializer.Serialize(value, Json);
        if (Encoding.UTF8.GetByteCount(message) > (method == "conversations.get" ? MaximumConversationResponseBytes : MaximumResponseBytes))
        {
            if (requestId is null) return;
            message = JsonSerializer.Serialize(new { version = Version, sessionId = session, requestId, ok = false,
                error = new WorkspaceSafeError("InvalidResponse", ErrorText.For(DesktopError.InvalidResponse)) }, Json);
        }
        send(message);
    }
    internal static bool CanonicalId(string value) => Guid.TryParseExact(value, "D", out var id)
        && id != Guid.Empty && id.ToString("D") == value;
    internal static readonly IReadOnlySet<string> BusinessMethods = new HashSet<string>(StringComparer.Ordinal)
    { "assistant.selectMemories", "assistant.submit", "translate.submit", "operations.get", "operations.cancel", "operations.copyResult" };
    internal static readonly IReadOnlySet<string> ConversationMethods = new HashSet<string>(StringComparer.Ordinal)
    { "conversations.list", "conversations.get", "conversations.create", "conversations.rename", "conversations.archive",
      "conversations.unarchive", "conversations.delete", "conversations.selectMemories", "conversations.clearMemories",
      "conversations.send", "conversations.cancelPending" };
    internal static readonly IReadOnlySet<string> MemoryMethods = new HashSet<string>(StringComparer.Ordinal)
    { "memory.list", "memory.get", "memory.create", "memory.update", "memory.archive", "memory.restore", "memory.delete", "memory.editorState" };
    private static SelectedMemoryRef[] ReadReferences(JsonElement payload) => payload.GetProperty("selectedMemoryRefs").EnumerateArray()
        .Select(x => new SelectedMemoryRef(x.GetProperty("memoryId").GetString()!, x.GetProperty("revision").GetString()!, x.GetProperty("position").GetInt32())).ToArray();
    private static bool ValidPayload(string method, JsonElement payload)
    {
        if(KnowledgeMethods.Contains(method))return ValidKnowledgePayload(method,payload);
        if (MemoryMethods.Contains(method)) return ValidMemoryPayload(method, payload);
        if (ConversationMethods.Contains(method)) return ValidConversationPayload(method, payload);
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
        return ValidReferences(payload, mode == "Summarize");
    }
    private static MemoryType ReadMemoryType(JsonElement payload) => Enum.Parse<MemoryType>(payload.GetProperty("type").GetString()!);
    internal static readonly IReadOnlySet<string> KnowledgeMethods=new HashSet<string>(StringComparer.Ordinal)
    {"knowledge.list","knowledge.get","knowledge.import","knowledge.importState","knowledge.cancelImport","knowledge.archive","knowledge.restore","knowledge.delete","knowledge.preview"};
    private static bool ValidKnowledgePayload(string method,JsonElement p)
    {
        bool Id(string name)=>Text(p,name,36,out var id)&&CanonicalId(id);
        bool Version(string name)=>Text(p,name,19,out var v)&&v[0] is >= '1' and <= '9'&&v.All(x=>x is >= '0' and <= '9')&&long.TryParse(v,NumberStyles.None,CultureInfo.InvariantCulture,out _);
        if(method=="knowledge.list")return Fields(p,"status","page")&&Text(p,"status",8,out var s)&&s is "ACTIVE" or "ARCHIVED"&&Page(p)&&p.GetProperty("page").GetInt32()<25;
        if(method=="knowledge.importState")return Fields(p,"requestId")&&Id("requestId");
        if(method=="knowledge.import")return Fields(p,"documentId","expectedMetadataVersion")&&
            (p.GetProperty("documentId").ValueKind==JsonValueKind.Null?p.GetProperty("expectedMetadataVersion").ValueKind==JsonValueKind.Null:Id("documentId")&&Version("expectedMetadataVersion"));
        if(method=="knowledge.get")return Fields(p,"documentId")&&Id("documentId");
        if(method=="knowledge.cancelImport")return Fields(p,"documentId","requestId")&&Id("documentId")&&Id("requestId");
        if(method=="knowledge.preview")return Fields(p,"documentId","sourceRevision","offset")&&Id("documentId")&&Version("sourceRevision")&&long.Parse(p.GetProperty("sourceRevision").GetString()!,CultureInfo.InvariantCulture)<=10
            &&p.GetProperty("offset").ValueKind==JsonValueKind.Number&&p.GetProperty("offset").TryGetInt32(out int o)&&o is >=0 and <=1_000_000;
        return Fields(p,"documentId","expectedMetadataVersion")&&Id("documentId")&&Version("expectedMetadataVersion");
    }
    private static MemoryQuery ReadMemoryQuery(JsonElement payload) => new(payload.GetProperty("query").GetString()!,
        Enum.Parse<MemoryStatus>(payload.GetProperty("status").GetString()!),
        payload.GetProperty("type").ValueKind == JsonValueKind.Null ? null : ReadMemoryType(payload), payload.GetProperty("page").GetInt32(), WorkspaceMemory.PageSize);
    private static long ReadRevision(JsonElement payload) => long.Parse(payload.GetProperty("expectedRevision").GetString()!, NumberStyles.None, CultureInfo.InvariantCulture);
    private static bool ValidMemoryPayload(string method, JsonElement payload)
    {
        if (method == "memory.editorState") return Fields(payload, "dirty") && payload.GetProperty("dirty").ValueKind is JsonValueKind.True or JsonValueKind.False;
        if (method == "memory.list")
        {
            if (!Fields(payload, "query", "status", "type", "page") || payload.GetProperty("query").ValueKind != JsonValueKind.String
                || !Text(payload, "status", 8, out var status) || status is not ("ACTIVE" or "ARCHIVED")
                || payload.GetProperty("type").ValueKind != JsonValueKind.Null && (!Text(payload, "type", 12, out var type) || type is not ("PREFERENCE" or "PROJECT_NOTE"))
                || !Page(payload) || payload.GetProperty("page").GetInt32() > 49) return false;
            try { ReadMemoryQuery(payload).Validate(); return true; } catch (DesktopException) { return false; }
        }
        string[] fields = method switch
        {
            "memory.create" => ["type", "title", "content"],
            "memory.get" => ["memoryId"],
            "memory.update" => ["memoryId", "expectedRevision", "type", "title", "content"],
            _ => ["memoryId", "expectedRevision"]
        };
        if (!Fields(payload, fields)) return false;
        if (method != "memory.create" && (!Text(payload, "memoryId", 36, out var id) || !CanonicalId(id))) return false;
        if (method is not ("memory.get" or "memory.create") && (!Text(payload, "expectedRevision", 19, out var revision)
            || revision[0] is < '1' or > '9' || revision.Any(x => x is < '0' or > '9')
            || !long.TryParse(revision, NumberStyles.None, CultureInfo.InvariantCulture, out _))) return false;
        if (method is not ("memory.create" or "memory.update")) return true;
        if (!Text(payload, "type", 12, out var value) || value is not ("PREFERENCE" or "PROJECT_NOTE")
            || !Text(payload, "title", 320, out _) || !Text(payload, "content", 2000, out _)) return false;
        try { new MemoryCreateInput(ReadMemoryType(payload), payload.GetProperty("title").GetString()!, payload.GetProperty("content").GetString()!).Validate(); return true; }
        catch (DesktopException) { return false; }
    }
    private static bool ValidConversationPayload(string method, JsonElement payload)
    {
        if (method == "conversations.create") return Fields(payload);
        if (method == "conversations.list") return Fields(payload, "status", "page")
            && Text(payload, "status", 8, out var status) && status is "ACTIVE" or "ARCHIVED" && Page(payload);
        string[] fields = method switch
        {
            "conversations.get" => ["conversationId", "page"],
            "conversations.rename" => ["conversationId", "title"],
            "conversations.send" => ["conversationId", "message", "selectedMemoryRefs"],
            "conversations.cancelPending" => ["conversationId", "turnId"],
            _ => ["conversationId"]
        };
        if (!Fields(payload, fields) || !Text(payload, "conversationId", 36, out var id) || !CanonicalId(id)) return false;
        return method switch
        {
            "conversations.get" => Page(payload),
            // 160 Unicode scalar values may occupy 320 UTF-16 units. Core validates text.
            "conversations.rename" => Text(payload, "title", 320, out _),
            "conversations.send" => Text(payload, "message", 3000, out _) && ValidReferences(payload),
            "conversations.cancelPending" => Text(payload, "turnId", 36, out var turn) && CanonicalId(turn),
            _ => true
        };
    }
    private static bool Page(JsonElement payload) => payload.GetProperty("page").ValueKind == JsonValueKind.Number
        && payload.GetProperty("page").TryGetInt32(out var page) && page is >= 0 and <= 99;
    private static bool ValidReferences(JsonElement payload, bool empty = false)
    {
        var references = payload.GetProperty("selectedMemoryRefs");
        if (references.ValueKind != JsonValueKind.Array || references.GetArrayLength() > 4
            || empty && references.GetArrayLength() != 0) return false;
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
