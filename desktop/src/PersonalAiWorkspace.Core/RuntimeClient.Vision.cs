using System.Net;
using System.Text;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record VisionReadiness(bool ProviderDeclaredVision, bool RuntimeValidatedText,
    bool WorkspaceVisionExecutionValidated, bool Available, long SelectionRevision, string? Reason);
public sealed record VisionValidationIntent(string Action, Guid IntentId, Guid SessionId, string Model, string Runner,
    string Digest, string ProjectorDigest, long ExpectedSelectionRevision, string Generation, string Policy, DateTimeOffset ExpiresAt);

public sealed partial class RuntimeClient
{
    public async Task<VisionReadiness> GetVisionReadinessAsync(CancellationToken token)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/capabilities/vision/readiness", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: VisionError, maximumResponse: 4096);
        return ParseVisionReadiness(body.RootElement);
    }
    public async Task<VisionValidationIntent> PrepareVisionValidationAsync(Guid sessionId, CancellationToken token)
    {
        using var body = await SendAsync(HttpMethod.Get, $"/api/v1/vision/validation/intent?sessionId={sessionId:D}", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: VisionError, maximumResponse: 4096);
        var r = body.RootElement;
        MemoryFields(r, "action", "intentId", "sessionId", "model", "runner", "digest", "projectorDigest", "expectedSelectionRevision", "generation", "policy", "expiresAt");
        if (String(r, "action") != "VISION_VALIDATE" || !ModelId(String(r, "intentId")) || String(r, "sessionId") != sessionId.ToString("D")
            || String(r, "model") != "qwen3.5:4b" || String(r, "runner") != "llamacpp"
            || String(r, "digest") != "96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7"
            || String(r, "projectorDigest") != "db3830bf825a26fcfed35416378696a47e0cbcf49c5b652a6fe9db90541f86d9"
            || !String(r, "generation").StartsWith("am1-") || !ModelId(String(r, "generation")[4..])
            || String(r, "policy") != "v1-png-rgb-white-square-1024-context-8192"
            || !DateTimeOffset.TryParse(String(r, "expiresAt"), out var expires) || expires <= DateTimeOffset.UtcNow || expires > DateTimeOffset.UtcNow.AddSeconds(61)) throw Invalid();
        return new("VISION_VALIDATE", Guid.Parse(String(r, "intentId")), sessionId, String(r, "model"), String(r, "runner"), String(r, "digest"),
            String(r, "projectorDigest"), ModelRevision(r, "expectedSelectionRevision"), String(r, "generation"), String(r, "policy"), expires);
    }
    public async Task<VisionReadiness> ValidateVisionAsync(VisionValidationIntent frozen, CancellationToken token)
    {
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(frozen, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        using var body = await SendAsync(HttpMethod.Post, "/api/v1/vision/validation", bytes, true, HttpStatusCode.OK, token,
            endpointErrorMap: VisionError, maximumResponse: 4096, requestTimeout: TimeSpan.FromSeconds(155));
        return ParseVisionReadiness(body.RootElement);
    }
    public async Task<RuntimeTask> SubmitVisionAsync(Guid taskId, string question, string mediaType, byte[] image, long revision, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if (taskId == Guid.Empty || string.IsNullOrWhiteSpace(question) || question.Length > 1000 || Encoding.UTF8.GetByteCount(question) > 4096
            || mediaType is not ("image/png" or "image/jpeg") || image.Length is 0 or > 4 * 1024 * 1024 || revision < 0 || revision > MaximumSelectionRevision)
            throw new DesktopException(DesktopError.InvalidRequest);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new { taskId, question, image = new { mediaType, dataBase64 = image }, expectedSelectionRevision = revision });
        if (payload.Length > 6 * 1024 * 1024) throw new DesktopException(DesktopError.InvalidRequest);
        try {
            using var body = await SendAsync(HttpMethod.Post, "/api/v1/vision/tasks", payload, true, HttpStatusCode.Accepted, token,
                (response, document) => { if (response.Headers.Location?.OriginalString != $"/api/v1/tasks/{taskId:D}") throw Invalid(); },
                endpointErrorMap: VisionError, maximumResponse: 65536, requestTimeout: TimeSpan.FromSeconds(25));
            return ParseVisionTask(body.RootElement, taskId);
        } catch (DesktopException e) when (e.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse or DesktopError.InternalError or DesktopError.ProviderResponseInvalid) {
            throw new DesktopException(DesktopError.OutcomeUnknown);
        } catch (OperationCanceledException) { throw new DesktopException(DesktopError.OutcomeUnknown); }
    }
    public async Task<RuntimeTask> VisionTaskAsync(Guid taskId, bool cancel, CancellationToken token)
    {
        using var body = await SendAsync(cancel ? HttpMethod.Delete : HttpMethod.Get, $"/api/v1/tasks/{taskId:D}", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: VisionError, maximumResponse: 65536);
        return ParseVisionTask(body.RootElement, taskId);
    }
    private static VisionReadiness ParseVisionReadiness(JsonElement r)
    {
        MemoryFields(r, "providerDeclaredVision", "runtimeValidatedText", "workspaceVisionExecutionValidated", "available", "selectionRevision", "reason");
        bool declared = ModelBoolean(r, "providerDeclaredVision"), text = ModelBoolean(r, "runtimeValidatedText"), validated = ModelBoolean(r, "workspaceVisionExecutionValidated"), available = ModelBoolean(r, "available");
        string? reason = ModelNullable(r, "reason");
        if (validated && (!declared || !text) || available != validated || (reason is null) != available
            || reason is not null && reason is not ("TEXT_UNAVAILABLE" or "MODEL_UNSUPPORTED" or "TOKEN_EVIDENCE_MISSING" or "VISION_UNVERIFIED")) throw Invalid();
        return new(declared, text, validated, available, ModelRevision(r, "selectionRevision"), reason);
    }
    private static RuntimeTask ParseVisionTask(JsonElement r, Guid id)
    {
        MemoryFields(r, "taskId", "capability", "status", "profile", "promptVersion", "createdAt", "finishedAt", "result", "error");
        if (String(r, "taskId") != id.ToString("D") || String(r, "capability") != "vision" || String(r, "promptVersion") != "vision-v1") throw Invalid();
        var profile = Property(r, "profile"); MemoryFields(profile, "id", "version", "locality");
        if (String(profile, "id") != "vision.single" || String(profile, "locality") != "LOCAL" || !String(profile, "version").StartsWith("am1-") || !ModelId(String(profile, "version")[4..])) throw Invalid();
        if (!Enum.TryParse<TaskState>(String(r, "status"), out var state) || !Enum.IsDefined(state) || state.ToString() != String(r, "status")
            || !DateTimeOffset.TryParse(String(r, "createdAt"), out var created)) throw Invalid();
        bool terminal = state is not (TaskState.QUEUED or TaskState.RUNNING); var finished = Property(r, "finishedAt");
        if (terminal ? finished.ValueKind != JsonValueKind.String || !DateTimeOffset.TryParse(finished.GetString(), out var end) || end < created : finished.ValueKind != JsonValueKind.Null) throw Invalid();
        var result = Property(r, "result"); var error = Property(r, "error");
        if (state == TaskState.SUCCEEDED) {
            if (result.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(result.GetString()) || Encoding.UTF8.GetByteCount(result.GetString()!) > 8192 || error.ValueKind != JsonValueKind.Null) throw Invalid();
            return new(id, state, result.GetString(), null);
        }
        if (result.ValueKind != JsonValueKind.Null) throw Invalid();
        if (!terminal) { if (error.ValueKind != JsonValueKind.Null) throw Invalid(); return new(id, state, null, null); }
        MemoryFields(error, "code", "message", "phase"); string code = String(error, "code");
        if (String(error,"message").Length>256 || String(error,"phase").Length>64) throw Invalid();
        if (state == TaskState.CANCELLED && code != "TASK_CANCELLED" || state == TaskState.TIMED_OUT && code != "TASK_TIMEOUT"
            || state == TaskState.FAILED && code is "TASK_CANCELLED" or "TASK_TIMEOUT") throw Invalid();
        return new(id, state, null, code == "VISION_UNVERIFIED" ? DesktopError.ModelUnavailable : MapError(code));
    }
    private static DesktopError VisionError(HttpStatusCode status, JsonElement r)
    {
        MemoryFields(r, "code", "message", "phase"); string code = String(r, "code");
        if (code == "VISION_UNVERIFIED" && status == HttpStatusCode.ServiceUnavailable) return DesktopError.ModelUnavailable;
        if (code == "VISION_TOO_LARGE" && status == HttpStatusCode.RequestEntityTooLarge) return DesktopError.InvalidRequest;
        if (code == "DUPLICATE_TASK_ID" && status == HttpStatusCode.Conflict) return DesktopError.OutcomeUnknown;
        if (status == HttpStatusCode.NotFound && code == "TASK_NOT_FOUND") return DesktopError.TaskNotFound;
        if (status == HttpStatusCode.TooManyRequests && code == "QUEUE_FULL") return DesktopError.QueueFull;
        return ModelHttpError(status, r);
    }
}
