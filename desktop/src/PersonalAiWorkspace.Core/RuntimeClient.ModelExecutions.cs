using System.Net;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    public async Task<ModelExecutionInspection> InspectModelExecutionsAsync(CancellationToken token)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/models/executions", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: ModelHttpError, maximumResponse: 65536);
        var root = body.RootElement;
        MemoryFields(root, "recordState", "recoverySupported", "requests");
        string state = String(root, "recordState");
        if (state is not ("NONE" or "CURRENT_SESSION" or "PREVIOUS_SESSION" or "LEGACY" or "INCOMPLETE" or "UNREADABLE" or "UNAVAILABLE")
            || ModelBoolean(root, "recoverySupported")) throw Invalid();
        var rows = Property(root, "requests");
        if (rows.ValueKind != System.Text.Json.JsonValueKind.Array || rows.GetArrayLength() > 128
            || (state is "CURRENT_SESSION" or "PREVIOUS_SESSION") != (rows.GetArrayLength() > 0)) throw Invalid();
        var entries = new List<ModelExecutionEntry>(); var ids = new HashSet<string>();
        foreach (var row in rows.EnumerateArray())
        {
            MemoryFields(row, "requestId", "operation", "model", "runner", "digest", "selectionRevision", "completion");
            string id = String(row, "requestId"), operation = String(row, "operation"), model = String(row, "model"),
                runner = String(row, "runner"), digest = String(row, "digest"), completion = String(row, "completion");
            if (!ModelId(id) || !ids.Add(id) || operation is not ("chat" or "unload")
                || !ModelName(model) || !ModelRunner(runner) || !ModelDigest(digest)
                || completion is not ("PREPARED" or "IN_PROGRESS" or "COMPLETED" or "NOT_SENT" or "UNKNOWN")
                || state == "PREVIOUS_SESSION" && completion != "UNKNOWN") throw Invalid();
            entries.Add(new(id, operation, model, runner, digest, ModelRevision(row, "selectionRevision"), completion));
        }
        return new(state, false, entries.ToArray());
    }
}
