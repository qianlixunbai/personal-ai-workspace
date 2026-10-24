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

internal enum NativeWorkspaceEntry { LegacyAssistant, Conversations, Memory, BrowserPairing, MemoryBackup, WorkspaceBackup, CredentialFlow, KnowledgeBackup, DatabaseSettings, FinanceBinding }
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
    WorkspaceWebFetch? WebFetch => null;
    WorkspaceModels? Models => null;
    WorkspaceVision? Vision => null;
    Task<ShellStatus> StatusAsync(CancellationToken cancellation);
    Task<StorageStatus> StorageStatusAsync(CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceOverview> FinanceOverviewAsync(CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinancePortfolio> FinancePortfolioAsync(CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceBudgetAvailability> FinanceBudgetsAsync(CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceAccountPage> FinanceAccountsAsync(int page, int size, string status, string sort, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceAccount> FinanceAccountAsync(string id, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceTransactionPage> FinanceTransactionsAsync(FinanceTransactionQuery query, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceTransaction> FinanceTransactionAsync(string kind, string id, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceAssetPage> FinanceAssetsAsync(FinanceAssetQuery query, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinanceAsset> FinanceAssetAsync(string id, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinancePositionPage> FinancePositionsAsync(FinancePositionQuery query, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<FinancePositionDetail> FinancePositionAsync(string id, CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    Task<StorageStartupStatus> StorageStartupStatusAsync(CancellationToken cancellation) => throw new DesktopException(DesktopError.RuntimeUnavailable);
    event Action<StorageStartupStatus>? StorageStartupChanged { add { } remove { } }
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
    private readonly WorkspaceWebFetch? webFetch;
    private readonly WorkspaceModels? models;
    private readonly Action<string,string> visionCleared;
    private readonly Action<StorageStartupStatus> storageChanged;
    internal bool EditorDirty { get; private set; }
    private readonly Action<string> send;
    private readonly HashSet<string> requests = new(StringComparer.Ordinal);
    private CancellationTokenSource sessionLifetime = new();
    private string? document;
    private bool ready;
    private int pending;
    internal string SessionId { get; private set; } = "";
    internal bool HasBootstrapped { get; private set; }
    internal static IReadOnlyDictionary<string, NativeWorkspaceEntry> NativeMethods { get; } =
        new System.Collections.ObjectModel.ReadOnlyDictionary<string, NativeWorkspaceEntry>(new Dictionary<string, NativeWorkspaceEntry>
        {
            ["native.openBrowserPairing"] = NativeWorkspaceEntry.BrowserPairing,
            ["native.openMemoryBackup"] = NativeWorkspaceEntry.MemoryBackup,
            ["native.openWorkspaceBackup"] = NativeWorkspaceEntry.WorkspaceBackup,
            ["native.openKnowledgeBackup"] = NativeWorkspaceEntry.KnowledgeBackup,
            ["native.openCredentialFlow"] = NativeWorkspaceEntry.CredentialFlow,
            ["native.openDatabaseSettings"] = NativeWorkspaceEntry.DatabaseSettings,
            ["native.openFinanceBinding"] = NativeWorkspaceEntry.FinanceBinding
        });

    internal WorkspaceBridge(WorkspaceContentPolicy policy, IWorkspaceNativeActions native, Action<string> send)
    {
        this.policy = policy; this.native = native; operations = native.Operations; conversations = native.Conversations; memory = native.Memory; knowledge=native.Knowledge; webFetch = native.WebFetch; models = native.Models; this.send = send;
        visionCleared=(session,handle)=>{if(ready&&session==SessionId)Send(new {type="vision.cleared",version=Version,sessionId=session,handle});};
        if(native.Vision is {} vision)vision.Cleared+=visionCleared;
        var context = SynchronizationContext.Current;
        storageChanged = status =>
        {
            string session = SessionId;
            void Publish()
            {
                if (ready && session == SessionId) Send(new { type="storage.lifecycle",version=Version,sessionId=session,result=status });
            }
            try { if (context is null) Publish(); else context.Post(_ => Publish(), null); }
            catch (InvalidOperationException) { /* Desktop dispatcher is closing; never retain raw output. */ }
        };
        native.StorageStartupChanged += storageChanged;
    }

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
        webFetch?.BeginSession(SessionId);
        models?.BeginSession(SessionId);
        native.Vision?.BeginSession(SessionId);
    }

    internal void Ready(string currentDocument)
    {
        if (document is null || !policy.SameDocument(document, currentDocument)) return;
        ready = true;
        Send(new { type = "shell.session", version = Version, sessionId = SessionId });
    }

    internal void Invalidate()
    {
        webFetch?.EndSession(SessionId);
        models?.EndSession(SessionId);
        native.Vision?.EndSession(SessionId);
        operations?.EndSession(SessionId);
        conversations?.EndSession(SessionId);
        memory?.EndSession(SessionId);
        knowledge?.EndSession(SessionId);
        ready = false; HasBootstrapped = false; document = null; SessionId = ""; requests.Clear(); pending = 0;
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
        bool bootstrapSucceeded = false;
        try
        {
            object result;
            if (VisionMethods.Contains(method))
            {
                var vision=native.Vision??throw new WorkspaceOperationException("NATIVE_UNAVAILABLE","Vision 暂时不可用。");
                result=method switch {
                    "vision.readiness"=>await vision.ReadinessAsync(session,cancellation),
                    "vision.pick"=>await vision.PickAsync(session,cancellation),
                    "vision.validate"=>await vision.ValidateAsync(session,cancellation),
                    "vision.clear"=>vision.Clear(session),
                    "vision.submit"=>await vision.SubmitAsync(session,payload.GetProperty("handle").GetString()!,payload.GetProperty("question").GetString()!,cancellation),
                    _=>await vision.TaskAsync(session,payload.TryGetProperty("taskId",out var visionId)?visionId.GetString():null,method=="vision.cancel",cancellation)
                };
            }
            else if (method == "storage.status") result = await native.StorageStatusAsync(cancellation);
            else if (method == "storage.startupStatus") result = await native.StorageStartupStatusAsync(cancellation);
            else if (method == "finance.overview") result = await native.FinanceOverviewAsync(cancellation);
            else if (method == "finance.portfolio") result = await native.FinancePortfolioAsync(cancellation);
            else if (method == "finance.budgets") result = await native.FinanceBudgetsAsync(cancellation);
            else if (method == "finance.list") result = await native.FinanceAccountsAsync(payload.GetProperty("page").GetInt32(), payload.GetProperty("size").GetInt32(), payload.GetProperty("status").GetString()!, payload.GetProperty("sort").GetString()!, cancellation);
            else if (method == "finance.get") result = await native.FinanceAccountAsync(payload.GetProperty("accountId").GetString()!, cancellation);
            else if (method == "finance.transactions") result = await native.FinanceTransactionsAsync(TransactionQuery(payload), cancellation);
            else if (method == "finance.transaction") result = await native.FinanceTransactionAsync(payload.GetProperty("kind").GetString()!, payload.GetProperty("id").GetString()!, cancellation);
            else if (method == "finance.positions") result = await native.FinancePositionsAsync(PositionQuery(payload), cancellation);
            else if (method == "finance.position") result = await native.FinancePositionAsync(payload.GetProperty("id").GetString()!, cancellation);
            else if (method == "finance.assets") result = await native.FinanceAssetsAsync(AssetQuery(payload), cancellation);
            else if (method == "finance.asset") result = await native.FinanceAssetAsync(payload.GetProperty("id").GetString()!, cancellation);
            else if (ModelMethods.Contains(method))
            {
                if (models is null) throw new WorkspaceOperationException("NATIVE_UNAVAILABLE", "模型管理暂时不可用。");
                result = method == "models.mutate"
                    ? await models.MutateAsync(session, payload.GetProperty("action").GetString()!, payload.GetProperty("handle").GetString()!,
                        payload.GetProperty("expectedSelectionRevision").GetInt64(), payload.GetProperty("recoveryGeneration").ValueKind == JsonValueKind.Null ? null : payload.GetProperty("recoveryGeneration").GetString(), cancellation)
                    : await models.InspectAsync(session, cancellation, method == "models.inspectRecovery");
            }
            else if (WebFetchMethods.Contains(method))
            {
                if (webFetch is null) throw new WorkspaceOperationException("NATIVE_UNAVAILABLE", "公共网络访问暂时不可用。");
                result = method == "web.fetchSubmit"
                    ? await webFetch.SubmitAsync(session, payload.GetProperty("url").GetString()!, cancellation)
                    : await webFetch.OperationAsync(session, payload.GetProperty("operationId").GetString()!, method == "web.fetchCancel", cancellation);
            }
            else if(KnowledgeMethods.Contains(method))
            {
                if(knowledge is null)throw new WorkspaceOperationException("NATIVE_UNAVAILABLE","Knowledge 暂时不可用。");
                string? id=payload.TryGetProperty("documentId",out var documentId)&&documentId.ValueKind==JsonValueKind.String?documentId.GetString():null;
                result=method switch {
                    "knowledge.answerSubmit"=>await knowledge.AnswerSubmitAsync(session,payload.GetProperty("question").GetString()!,payload.GetProperty("query").GetString()!,cancellation),
                    "knowledge.answerGet"=>await knowledge.AnswerTaskAsync(session,payload.GetProperty("taskId").GetString()!,false,cancellation),
                    "knowledge.answerCancel"=>await knowledge.AnswerTaskAsync(session,payload.GetProperty("taskId").GetString()!,true,cancellation),
                    "knowledge.search"=>await knowledge.SearchAsync(session,payload.GetProperty("query").GetString()!,payload.GetProperty("limit").GetInt32(),cancellation),
                    "knowledge.searchStatus"=>await knowledge.SearchStatusAsync(session,cancellation),
                    "knowledge.rebuildSearchIndex"=>await knowledge.RebuildSearchAsync(session,cancellation),
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
            bootstrapSucceeded = method == "shell.bootstrap";
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
        if (ready && session == SessionId && !cancellation.IsCancellationRequested)
        {
            Send(response, method, session, requestId);
            if (bootstrapSucceeded) HasBootstrapped = true;
        }
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
    private static FinanceTransactionQuery TransactionQuery(JsonElement payload) => new(payload.GetProperty("page").GetInt32(),
        payload.GetProperty("size").GetInt32(), payload.GetProperty("accountId").GetString()!, payload.GetProperty("startDate").GetString()!,
        payload.GetProperty("endDate").GetString()!, payload.GetProperty("type").GetString()!);

    private static bool ValidPayload(string method, JsonElement payload)
    {
        if(VisionMethods.Contains(method)) return method switch {
            "vision.submit"=>Fields(payload,"handle","question")&&Text(payload,"handle",36,out var imageHandle)&&CanonicalId(imageHandle)
                &&Text(payload,"question",1000,out var question)&&!string.IsNullOrWhiteSpace(question)&&Encoding.UTF8.GetByteCount(question)<=4096,
            "vision.get" or "vision.cancel"=>method=="vision.get"&&Fields(payload)||Fields(payload,"taskId")&&Text(payload,"taskId",36,out var taskId)&&CanonicalId(taskId),
            _=>Fields(payload)
        };
        if (ModelMethods.Contains(method)) return method != "models.mutate" ? Fields(payload)
            : Fields(payload, "action", "handle", "expectedSelectionRevision", "recoveryGeneration")
              && Text(payload, "action", 23, out var action) && action is "SWITCH" or "RELEASE_OLD_THEN_SWITCH" or "RELEASE" or "VALIDATE" or "RECOVER"
              && Text(payload, "handle", 36, out var handle) && CanonicalId(handle)
              && payload.GetProperty("expectedSelectionRevision").ValueKind == JsonValueKind.Number
              && payload.GetProperty("expectedSelectionRevision").TryGetInt64(out long modelRevision) && modelRevision >= 0 && modelRevision <= RuntimeClient.MaximumSelectionRevision
              && (action == "RECOVER" ? Text(payload, "recoveryGeneration", 36, out var generation) && CanonicalId(generation)
                  : payload.GetProperty("recoveryGeneration").ValueKind == JsonValueKind.Null);
        if (method == "web.fetchSubmit") return Fields(payload, "url") && Text(payload, "url", 2048, out _);
        if (method is "web.fetchGet" or "web.fetchCancel")
            return Fields(payload, "operationId") && Text(payload, "operationId", 36, out var webId) && CanonicalId(webId);
        if(KnowledgeMethods.Contains(method))return ValidKnowledgePayload(method,payload);
        if (MemoryMethods.Contains(method)) return ValidMemoryPayload(method, payload);
        if (ConversationMethods.Contains(method)) return ValidConversationPayload(method, payload);
        if (method == "finance.get") return Fields(payload, "accountId") && Text(payload, "accountId", 19, out var accountId) && RuntimeClient.ValidFinanceId(accountId);
        if (method == "finance.position") return Fields(payload, "id") && Text(payload, "id", 19, out var positionId) && RuntimeClient.ValidFinanceId(positionId);
        if (method == "finance.positions") return Fields(payload, "status", "accountId", "instrumentId", "cursor", "size")
            && payload.GetProperty("size").ValueKind == JsonValueKind.Number && payload.GetProperty("size").TryGetInt32(out _)
            && new[] { "status", "accountId", "instrumentId", "cursor" }.All(key => payload.GetProperty(key).ValueKind == JsonValueKind.String)
            && RuntimeClient.ValidFinancePositionQuery(PositionQuery(payload));
        if (method == "finance.asset") return Fields(payload, "id") && Text(payload, "id", 19, out var assetId) && RuntimeClient.ValidFinanceId(assetId);
        if (method == "finance.assets") return Fields(payload, "page", "size", "type", "sort")
            && payload.GetProperty("page").ValueKind == JsonValueKind.Number && payload.GetProperty("page").TryGetInt32(out _)
            && payload.GetProperty("size").ValueKind == JsonValueKind.Number && payload.GetProperty("size").TryGetInt32(out _)
            && new[] { "type", "sort" }.All(key => payload.GetProperty(key).ValueKind == JsonValueKind.String)
            && RuntimeClient.ValidFinanceAssetQuery(AssetQuery(payload));
        if (method == "finance.transaction") return Fields(payload, "kind", "id") && Text(payload, "kind", 11, out var kind)
            && kind is "TRANSACTION" or "TRANSFER" && Text(payload, "id", 19, out var transactionId) && RuntimeClient.ValidFinanceId(transactionId);
        if (method == "finance.transactions") return Fields(payload, "page", "size", "accountId", "startDate", "endDate", "type")
            && payload.GetProperty("page").ValueKind == JsonValueKind.Number && payload.GetProperty("page").TryGetInt32(out _)
            && payload.GetProperty("size").ValueKind == JsonValueKind.Number && payload.GetProperty("size").TryGetInt32(out _)
            && new[] { "accountId", "startDate", "endDate", "type" }.All(key => payload.GetProperty(key).ValueKind == JsonValueKind.String)
            && RuntimeClient.ValidFinanceTransactionQuery(TransactionQuery(payload));
        if (method == "finance.list") return Fields(payload, "page", "size", "status", "sort")
            && payload.GetProperty("page").ValueKind == JsonValueKind.Number && payload.GetProperty("page").TryGetInt32(out int financePage)
            && payload.GetProperty("size").ValueKind == JsonValueKind.Number && payload.GetProperty("size").TryGetInt32(out int financeSize)
            && Text(payload, "status", 8, out var financeStatus) && Text(payload, "sort", 8, out var financeSort)
            && RuntimeClient.ValidFinanceQuery(financePage, financeSize, financeStatus, financeSort);
        if (method is "shell.bootstrap" or "shell.refreshStatus" or "storage.status" or "storage.startupStatus" or "finance.overview" or "finance.budgets" or "finance.portfolio" || NativeMethods.ContainsKey(method)
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
    private static FinancePositionQuery PositionQuery(JsonElement p) => new(p.GetProperty("status").GetString()!, p.GetProperty("accountId").GetString()!,
        p.GetProperty("instrumentId").GetString()!, p.GetProperty("cursor").GetString()!, p.GetProperty("size").GetInt32());
    private static FinanceAssetQuery AssetQuery(JsonElement p) => new(p.GetProperty("page").GetInt32(), p.GetProperty("size").GetInt32(), p.GetProperty("type").GetString()!, p.GetProperty("sort").GetString()!);
    internal static readonly IReadOnlySet<string> WebFetchMethods = new HashSet<string>(StringComparer.Ordinal)
    { "web.fetchSubmit", "web.fetchGet", "web.fetchCancel" };
    internal static readonly IReadOnlySet<string> VisionMethods=new HashSet<string>(StringComparer.Ordinal)
    {"vision.readiness","vision.pick","vision.validate","vision.submit","vision.get","vision.cancel","vision.clear"};
    internal static readonly IReadOnlySet<string> ModelMethods = new HashSet<string>(StringComparer.Ordinal)
    { "models.inspect", "models.inspectRecovery", "models.mutate" };
    internal static readonly IReadOnlySet<string> KnowledgeMethods=new HashSet<string>(StringComparer.Ordinal)
    {"knowledge.list","knowledge.get","knowledge.import","knowledge.importState","knowledge.cancelImport","knowledge.archive","knowledge.restore","knowledge.delete","knowledge.preview","knowledge.search","knowledge.searchStatus","knowledge.rebuildSearchIndex","knowledge.answerSubmit","knowledge.answerGet","knowledge.answerCancel"};
    private static bool ValidKnowledgePayload(string method,JsonElement p)
    {
        if(method=="knowledge.answerSubmit")return Fields(p,"question","query")&&Text(p,"question",3000,out _)&&Text(p,"query",256,out var answerQuery)&&answerQuery.EnumerateRunes().Count()<=128&&!answerQuery.Any(char.IsControl);
        if(method is "knowledge.answerGet" or "knowledge.answerCancel")return Fields(p,"taskId")&&Text(p,"taskId",36,out var taskId)&&CanonicalId(taskId);
        if(method is "knowledge.searchStatus" or "knowledge.rebuildSearchIndex")return Fields(p);
        if(method=="knowledge.search")return Fields(p,"query","limit")&&Text(p,"query",256,out var query)&&query.EnumerateRunes().Count()<=128
            &&!query.Any(char.IsControl)&&p.GetProperty("limit").ValueKind==JsonValueKind.Number&&p.GetProperty("limit").TryGetInt32(out int limit)&&limit is >=1 and <=10;
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
    public void Dispose() { Invalidate();if(native.Vision is {} vision)vision.Cleared-=visionCleared;native.StorageStartupChanged-=storageChanged; sessionLifetime.Dispose(); }
}
