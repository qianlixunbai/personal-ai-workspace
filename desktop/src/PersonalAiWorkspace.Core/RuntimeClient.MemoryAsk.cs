using System.Net;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    public async Task<RuntimeTask> SubmitMemoryAskAsync(MemoryAskInput input, CancellationToken cancellationToken)
    {
        input.Validate();
        var payload = JsonSerializer.SerializeToUtf8Bytes(new
        {
            question = input.Question,
            memories = input.Memories.Select(x => new { id = x.Id, revision = x.Revision }).ToArray(),
            profile = "chat.balanced"
        });
        using var body = await SendAsync(HttpMethod.Post, "/api/v1/memory/ask/tasks", payload, true,
            HttpStatusCode.Accepted, cancellationToken, validate: (response, document) =>
            {
                var task = ParseTask(document.RootElement, null, "ask", "memory-ask-v1");
                if (response.Headers.Location?.OriginalString != $"/api/v1/tasks/{task.TaskId:D}") throw Invalid();
            }, endpointErrorMap: MemoryAskError);
        return ParseTask(body.RootElement, null, "ask", "memory-ask-v1");
    }
    private static DesktopError MemoryAskError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase");
        _ = String(root, "message"); _ = String(root, "phase");
        return ((int)status, String(root, "code")) switch
        {
            (409, "MEMORY_SELECTION_STALE") => DesktopError.MemorySelectionStale,
            (400 or 413, "INVALID_REQUEST") => DesktopError.MemoryAskBudget,
            (503, "MEMORY_STORAGE_UNAVAILABLE") => DesktopError.MemoryStorageUnavailable,
            (503, "MEMORY_SCHEMA_UNSUPPORTED") => DesktopError.MemorySchemaUnsupported,
            (403, "POLICY_DENIED") => DesktopError.PolicyDenied,
            (429, "QUEUE_FULL") => DesktopError.QueueFull,
            (503, "PROVIDER_UNAVAILABLE") => DesktopError.ProviderUnavailable,
            (503, "MODEL_UNAVAILABLE") => DesktopError.ModelUnavailable,
            (504, "TASK_TIMEOUT") => DesktopError.TimedOut,
            (500, "INTERNAL_ERROR") => DesktopError.InternalError,
            (500, "PROVIDER_RESPONSE_INVALID") => DesktopError.ProviderResponseInvalid,
            _ => throw Invalid()
        };
    }
}
