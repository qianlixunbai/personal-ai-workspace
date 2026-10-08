using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    public const long MaximumSelectionRevision = 9007199254740991;
    public async Task<ModelCatalog> GetModelCatalogAsync(CancellationToken token)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/models/catalog", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: ModelHttpError, maximumResponse: 65536, requestTimeout: TimeSpan.FromSeconds(155));
        var root = body.RootElement; MemoryFields(root, "selectionRevision", "models");
        long revision = ModelRevision(root, "selectionRevision"); var models = Property(root, "models");
        if (models.ValueKind != JsonValueKind.Array || models.GetArrayLength() > 64) throw Invalid();
        var entries = new List<ModelCatalogEntry>(); var handles = new HashSet<string>(); var names = new HashSet<string>();
        foreach (var item in models.EnumerateArray())
        {
            MemoryFields(item, "handle", "model", "digest", "contextLimit", "completion", "localSourceVerified", "providerDeclaredVision");
            string handle = String(item, "handle"), model = String(item, "model"), digest = String(item, "digest");
            if (!ModelId(handle) || !ModelName(model) || !ModelDigest(digest) || !handles.Add(handle) || !names.Add(model)
                || !Property(item, "contextLimit").TryGetInt64(out long context) || context < 1 || context > MaximumSelectionRevision
                || !ModelBoolean(item, "completion") || !ModelBoolean(item, "localSourceVerified")) throw Invalid();
            entries.Add(new(handle, model, digest, context, true, true, ModelBoolean(item, "providerDeclaredVision")));
        }
        return new(revision, entries.ToArray());
    }
    public async Task<ModelStatus> GetModelStatusAsync(CancellationToken token, bool recovery = false)
    {
        using var body = await SendAsync(HttpMethod.Get, recovery ? "/api/v1/models/recovery" : "/api/v1/models/status", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: ModelHttpError, maximumResponse: 4096, requestTimeout: TimeSpan.FromSeconds(155));
        return ParseModelStatus(body.RootElement);
    }
    public async Task<ModelStatus> MutateModelAsync(ModelIntent intent, CancellationToken token)
    {
        string route = intent.Action switch { ModelAction.SWITCH or ModelAction.RELEASE_OLD_THEN_SWITCH => "selection",
            ModelAction.RELEASE => "release", ModelAction.VALIDATE => "validation", ModelAction.RECOVER => "recovery", _ => throw new DesktopException(DesktopError.InvalidRequest) };
        if (!ModelId(intent.CatalogHandle) || !ModelName(intent.CandidateModel) || !ModelDigest(intent.CandidateDigest)
            || intent.ExpectedSelectionRevision < 0 || intent.ExpectedSelectionRevision > MaximumSelectionRevision
            || (intent.ExpectedActiveModel is null) != (intent.ExpectedActiveDigest is null)
            || intent.ExpectedActiveModel is { } old && (!ModelName(old) || !ModelDigest(intent.ExpectedActiveDigest!))
            || (intent.Action == ModelAction.RECOVER ? !ModelId(intent.RecoveryGeneration) : intent.RecoveryGeneration is not null)
            || intent.ExternalConfirmed != (intent.Action is ModelAction.RELEASE or ModelAction.RELEASE_OLD_THEN_SWITCH or ModelAction.RECOVER))
            throw new DesktopException(DesktopError.InvalidRequest);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new { action = intent.Action.ToString(), catalogHandle = intent.CatalogHandle,
            candidateModel = intent.CandidateModel, candidateDigest = intent.CandidateDigest, expectedSelectionRevision = intent.ExpectedSelectionRevision,
            expectedActiveModel = intent.ExpectedActiveModel, expectedActiveDigest = intent.ExpectedActiveDigest,
            recoveryGeneration = intent.RecoveryGeneration, externalConfirmed = intent.ExternalConfirmed });
        try
        {
            using var body = await SendAsync(HttpMethod.Post, "/api/v1/models/" + route, payload, true, HttpStatusCode.OK, token,
                endpointErrorMap: ModelHttpError, maximumResponse: 4096, requestTimeout: TimeSpan.FromSeconds(155));
            return ParseModelStatus(body.RootElement);
        }
        catch (DesktopException e) when (e.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse)
        { throw new DesktopException(DesktopError.OutcomeUnknown); }
        catch (InvalidOperationException) { throw new DesktopException(DesktopError.OutcomeUnknown); }
    }
    internal static bool ModelId(string? value) => value is not null && Guid.TryParseExact(value, "D", out var id) && id != Guid.Empty && id.ToString("D") == value;
    internal static bool ModelName(string value) => value.Length <= 256 && Regex.IsMatch(value, @"\A[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)?:[a-z0-9][a-z0-9._-]*\z")
        && !value.Contains("..") && !value.EndsWith(":cloud") && !value.EndsWith("-cloud") && !value.EndsWith(":local");
    internal static bool ModelDigest(string value) => Regex.IsMatch(value, @"\A[0-9a-f]{64}\z");
    private static long ModelRevision(JsonElement root, string field)
    { if (!Property(root, field).TryGetInt64(out long value) || value < 0 || value > MaximumSelectionRevision) throw Invalid(); return value; }
    private static bool ModelBoolean(JsonElement root, string field) => Property(root, field).ValueKind switch
    { JsonValueKind.True => true, JsonValueKind.False => false, _ => throw Invalid() };
    private static string? ModelNullable(JsonElement root, string field) => Property(root, field).ValueKind == JsonValueKind.Null ? null : String(root, field);
    private static ModelStatus ParseModelStatus(JsonElement root)
    {
        MemoryFields(root, "configuredModel", "configuredDigest", "activeModel", "activeDigest", "selectionRevision", "installed", "loaded", "ready",
            "reserved", "queued", "executing", "draining", "switching", "uncertain", "recoveryGeneration", "validationRequired", "error");
        string? configured = ModelNullable(root, "configuredModel"), digest = ModelNullable(root, "configuredDigest"), active = ModelNullable(root, "activeModel"), activeDigest = ModelNullable(root, "activeDigest");
        if (configured is not null && !ModelName(configured) || digest is not null && !ModelDigest(digest)
            || configured is null && digest is not null || (active is null) != (activeDigest is null)
            || active is not null && (!ModelName(active) || !ModelDigest(activeDigest!))) throw Invalid();
        ModelFact Fact(string field) { string text = String(root, field); return Enum.TryParse<ModelFact>(text, out var fact) && fact.ToString() == text ? fact : throw Invalid(); }
        int Count(string field) { if (!Property(root, field).TryGetInt32(out int count) || count < 0 || count > 34) throw Invalid(); return count; }
        string generation = String(root, "recoveryGeneration"); if (!ModelId(generation)) throw Invalid();
        string? error = ModelNullable(root, "error"); if (error is not null) _ = ModelCode(error);
        var result = new ModelStatus(configured, digest, active, activeDigest, ModelRevision(root, "selectionRevision"), Fact("installed"), Fact("loaded"), ModelBoolean(root, "ready"),
            Count("reserved"), Count("queued"), Count("executing"), Count("draining"), ModelBoolean(root, "switching"), ModelBoolean(root, "uncertain"), generation, ModelBoolean(root, "validationRequired"), error);
        if (result.Ready && (active is null || result.Switching || result.Uncertain || result.ValidationRequired)) throw Invalid();
        return result;
    }
    private static DesktopError ModelCode(string code) => code switch
    {
        "MODEL_SWITCH_CONFLICT" => DesktopError.ModelSwitchConflict, "MODEL_SELECTION_REVISION_CONFLICT" => DesktopError.ModelSelectionRevisionConflict,
        "MODEL_EXECUTION_UNCERTAIN" => DesktopError.ModelExecutionUncertain, "MODEL_STATE_UNAVAILABLE" => DesktopError.ModelStateUnavailable,
        "MODEL_CONFIGURATION_INVALID" => DesktopError.ModelConfigurationInvalid, "MODEL_IDENTITY_CHANGED" => DesktopError.ModelIdentityChanged,
        "MODEL_CATALOG_STALE" => DesktopError.ModelCatalogStale, "MODEL_CATALOG_LIMIT_EXCEEDED" => DesktopError.ModelCatalogLimitExceeded,
        _ => MapError(code)
    };
    private static DesktopError ModelHttpError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase"); string code = String(root, "code");
        if (String(root, "message").Length > 256 || String(root, "phase").Length > 64) throw Invalid();
        int expected = code switch {
            "MODEL_SWITCH_CONFLICT" or "MODEL_SELECTION_REVISION_CONFLICT" or "MODEL_EXECUTION_UNCERTAIN" or "MODEL_CATALOG_STALE" or "MODEL_CATALOG_LIMIT_EXCEEDED" => 409,
            "MODEL_STATE_UNAVAILABLE" or "MODEL_CONFIGURATION_INVALID" or "MODEL_IDENTITY_CHANGED" or "MODEL_UNAVAILABLE" or "PROVIDER_UNAVAILABLE" => 503,
            "INVALID_REQUEST" => 400, "POLICY_DENIED" => 403, "UNAUTHORIZED" => 401, "TASK_TIMEOUT" => 504,
            "INTERNAL_ERROR" or "PROVIDER_RESPONSE_INVALID" => 500, _ => throw Invalid() };
        if ((int)status != expected) throw Invalid(); return ModelCode(code);
    }
}
