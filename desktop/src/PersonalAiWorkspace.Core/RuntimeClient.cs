using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient : IDisposable
{
    public static readonly Uri Endpoint = new("http://127.0.0.1:8765");
    private readonly HttpClient http;
    private readonly Func<string?> credential;
    private const int MaximumResponse = 1024 * 1024;

    public RuntimeClient(Func<string?> credential) : this(new HttpClientHandler
    {
        UseProxy = false, AllowAutoRedirect = false, UseCookies = false
    }, credential) { }

    // Handler injection supports contract tests; callers cannot configure a remote base URL.
    public RuntimeClient(HttpMessageHandler handler, Func<string?> credential)
    {
        this.credential = credential;
        http = new HttpClient(handler) { BaseAddress = Endpoint, Timeout = TimeSpan.FromSeconds(8) };
    }

    public async Task CheckHealthAsync(CancellationToken cancellationToken)
    {
        using var body = await SendAsync(HttpMethod.Get, "/actuator/health", null, false, HttpStatusCode.OK, cancellationToken);
        if (String(body.RootElement, "status") != "UP") throw new DesktopException(DesktopError.RuntimeUnavailable);
    }

    public async Task CheckCredentialAsync(CancellationToken cancellationToken)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/providers/readiness", null, true, HttpStatusCode.OK, cancellationToken);
        var root = body.RootElement;
        if (String(root, "provider") != "ollama" || String(root, "profile") != "translate.fast"
            || Property(root, "available").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || Property(root, "modelAvailable").ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
    }

    public Task<RuntimeTask> SubmitAsync(TranslateInput input, CancellationToken cancellationToken) => SubmitTranslateAsync(input, cancellationToken);

    public Task<RuntimeTask> SubmitTranslateAsync(TranslateInput input, CancellationToken cancellationToken)
    {
        input.Validate();
        return SubmitTaskAsync("translate", new { text = input.Text, targetLanguage = input.TargetLanguage, profile = "translate.fast" }, cancellationToken);
    }
    public Task<RuntimeTask> SubmitSummarizeAsync(SummarizeInput input, CancellationToken cancellationToken)
    {
        input.Validate();
        return SubmitTaskAsync("summarize", new { text = input.Text, profile = "summarize.fast" }, cancellationToken);
    }
    public Task<RuntimeTask> SubmitAskAsync(AskInput input, CancellationToken cancellationToken)
    {
        input.Validate();
        return SubmitTaskAsync("ask", new { question = input.Question, profile = "chat.balanced" }, cancellationToken);
    }
    private async Task<RuntimeTask> SubmitTaskAsync(string capability, object request, CancellationToken cancellationToken)
    {
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(request);
        using var body = await SendAsync(HttpMethod.Post, $"/api/v1/{capability}/tasks", payload, true, HttpStatusCode.Accepted, cancellationToken,
            (response, document) =>
            {
                var task = ParseTask(document.RootElement, null, capability);
                if (response.Headers.Location?.OriginalString != $"/api/v1/tasks/{task.TaskId:D}") throw Invalid();
            });
        return ParseTask(body.RootElement, null, capability);
    }

    public Task<RuntimeTask> GetAsync(Guid taskId, CancellationToken cancellationToken) => TaskRequest(HttpMethod.Get, taskId, cancellationToken, null);
    public Task<RuntimeTask> CancelAsync(Guid taskId, CancellationToken cancellationToken) => TaskRequest(HttpMethod.Delete, taskId, cancellationToken, null);

    internal Task<RuntimeTask> GetAsync(Guid taskId, string capability, CancellationToken cancellationToken, string? promptVersion = null) => TaskRequest(HttpMethod.Get, taskId, cancellationToken, capability, promptVersion);
    internal Task<RuntimeTask> CancelAsync(Guid taskId, string capability, CancellationToken cancellationToken, string? promptVersion = null) => TaskRequest(HttpMethod.Delete, taskId, cancellationToken, capability, promptVersion);

    private async Task<RuntimeTask> TaskRequest(HttpMethod method, Guid id, CancellationToken cancellationToken, string? capability, string? promptVersion = null)
    {
        using var body = await SendAsync(method, $"/api/v1/tasks/{id:D}", null, true, HttpStatusCode.OK, cancellationToken);
        return ParseTask(body.RootElement, id, capability, promptVersion);
    }

    private async Task<JsonDocument> SendAsync(HttpMethod method, string path, byte[]? payload, bool authenticate,
        HttpStatusCode expected, CancellationToken cancellationToken, Action<HttpResponseMessage, JsonDocument>? validate = null,
        Func<JsonElement, DesktopError>? errorMap = null,
        Func<HttpStatusCode, JsonElement, DesktopError>? endpointErrorMap = null)
    {
        using var request = new HttpRequestMessage(method, path);
        // Actuator defaults to a vendor media type unless the client negotiates JSON.
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (authenticate)
        {
            var token = credential();
            if (token is null) throw new DesktopException(DesktopError.CredentialMissing);
            if (!CredentialFormat.Valid(token)) throw new DesktopException(DesktopError.CredentialInvalid);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }
        if (payload is not null)
        {
            request.Content = new ByteArrayContent(payload);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }
        try
        {
            // A linked deadline also bounds streamed body reads, not just response headers.
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            deadline.CancelAfter(TimeSpan.FromSeconds(8));
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            if (response.StatusCode == HttpStatusCode.Unauthorized) throw new DesktopException(DesktopError.Unauthorized);
            if (expected == HttpStatusCode.NoContent && response.StatusCode == expected)
            {
                using var empty = await response.Content.ReadAsStreamAsync(deadline.Token);
                if (await empty.ReadAsync(new byte[1], deadline.Token) != 0) throw Invalid();
                return JsonDocument.Parse("{}");
            }
            if (response.Content.Headers.ContentType?.MediaType != "application/json"
                || response.Content.Headers.ContentLength > MaximumResponse) throw Invalid();
            using var stream = await response.Content.ReadAsStreamAsync(deadline.Token);
            using var buffer = new MemoryStream();
            var chunk = new byte[8192];
            int count;
            while ((count = await stream.ReadAsync(chunk, deadline.Token)) != 0)
            {
                if (buffer.Length + count > MaximumResponse) throw Invalid();
                buffer.Write(chunk, 0, count);
            }
            var body = JsonDocument.Parse(buffer.ToArray(), new JsonDocumentOptions { MaxDepth = 24 });
            try
            {
                RejectDuplicates(body.RootElement);
                if (response.StatusCode != expected)
                {
                    if (endpointErrorMap is not null)
                        throw new DesktopException(endpointErrorMap(response.StatusCode, body.RootElement));
                    var code = String(body.RootElement, "code");
                    bool validStatus = (int)response.StatusCode switch
                    {
                        400 or 413 => code == "INVALID_REQUEST",
                        403 => code == "POLICY_DENIED",
                        404 => code == "TASK_NOT_FOUND",
                        429 => code == "QUEUE_FULL",
                        503 => code is "PROVIDER_UNAVAILABLE" or "MODEL_UNAVAILABLE",
                        504 => code == "TASK_TIMEOUT",
                        500 => code is "INTERNAL_ERROR" or "PROVIDER_RESPONSE_INVALID",
                        _ => false
                    };
                    if (!validStatus) throw Invalid();
                    throw new DesktopException(errorMap is null ? MapError(code) : errorMap(body.RootElement));
                }
                validate?.Invoke(response, body);
                return body;
            }
            catch { body.Dispose(); throw; }
        }
        catch (JsonException) { throw Invalid(); }
        catch (HttpRequestException) { throw new DesktopException(DesktopError.RuntimeUnavailable); }
        catch (IOException) { throw new DesktopException(DesktopError.RuntimeUnavailable); }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        { throw new DesktopException(DesktopError.ClientTimeout); }
    }

    private static RuntimeTask ParseTask(JsonElement root, Guid? expectedId, string? expectedCapability = null, string? expectedPromptVersion = null)
    {
        if (!Guid.TryParseExact(String(root, "taskId"), "D", out var id) || id == Guid.Empty || (expectedId.HasValue && id != expectedId)) throw Invalid();
        string capability = String(root, "capability");
        string profileId = capability switch { "translate" => "translate.fast", "summarize" => "summarize.fast", "ask" => "chat.balanced", _ => throw Invalid() };
        if (expectedCapability is not null && capability != expectedCapability) throw Invalid();
        string state = String(root, "status");
        if (!Enum.TryParse<TaskState>(state, false, out var status) || !Enum.IsDefined(status) || status.ToString() != state) throw Invalid();
        var profile = Property(root, "profile");
        if (String(profile, "id") != profileId || String(profile, "locality") != "LOCAL"
            || string.IsNullOrWhiteSpace(String(profile, "version"))
            || String(root, "promptVersion") != (expectedPromptVersion ?? capability + "-v1")) throw Invalid();
        if (!DateTimeOffset.TryParse(String(root, "createdAt"), out var created)) throw Invalid();
        var finished = Property(root, "finishedAt");
        bool terminal = status is not (TaskState.QUEUED or TaskState.RUNNING);
        if (terminal)
        {
            if (finished.ValueKind != JsonValueKind.String || !DateTimeOffset.TryParse(finished.GetString(), out var time) || time < created) throw Invalid();
        }
        else if (finished.ValueKind != JsonValueKind.Null) throw Invalid();
        var result = Property(root, "result");
        var error = Property(root, "error");
        if (status == TaskState.SUCCEEDED)
        {
            if (result.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(result.GetString()) || error.ValueKind != JsonValueKind.Null) throw Invalid();
            int outputBytes = capability == "summarize" ? 4096 : 8192;
            if (System.Text.Encoding.UTF8.GetByteCount(result.GetString()!) > outputBytes) throw Invalid();
            return new RuntimeTask(id, status, result.GetString(), null);
        }
        if (result.ValueKind != JsonValueKind.Null) throw Invalid();
        if (!terminal)
        {
            if (error.ValueKind != JsonValueKind.Null) throw Invalid();
            return new RuntimeTask(id, status, null, null);
        }
        string errorCode = String(error, "code");
        if (status == TaskState.CANCELLED && errorCode != "TASK_CANCELLED"
            || status == TaskState.TIMED_OUT && errorCode != "TASK_TIMEOUT"
            || status == TaskState.FAILED && errorCode is "TASK_CANCELLED" or "TASK_TIMEOUT") throw Invalid();
        return new RuntimeTask(id, status, null, MapError(errorCode));
    }

    private static DesktopError MapError(string code) => code switch
    {
        "PROVIDER_UNAVAILABLE" => DesktopError.ProviderUnavailable,
        "MODEL_UNAVAILABLE" => DesktopError.ModelUnavailable,
        "QUEUE_FULL" => DesktopError.QueueFull,
        "INVALID_REQUEST" => DesktopError.InvalidRequest,
        "POLICY_DENIED" => DesktopError.PolicyDenied,
        "TASK_CANCELLED" => DesktopError.Cancelled,
        "TASK_TIMEOUT" => DesktopError.TimedOut,
        "TASK_NOT_FOUND" => DesktopError.TaskNotFound,
        "UNAUTHORIZED" => DesktopError.Unauthorized,
        "PROVIDER_RESPONSE_INVALID" => DesktopError.ProviderResponseInvalid,
        "INTERNAL_ERROR" => DesktopError.InternalError,
        _ => throw Invalid()
    };

    private static JsonElement Property(JsonElement root, string name) => root.ValueKind == JsonValueKind.Object
        && root.TryGetProperty(name, out var value) ? value : throw Invalid();
    private static string String(JsonElement root, string name) => Property(root, name).ValueKind == JsonValueKind.String
        ? Property(root, name).GetString()! : throw Invalid();
    private static DesktopException Invalid() => new(DesktopError.InvalidResponse);
    private static void RejectDuplicates(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw Invalid();
                RejectDuplicates(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var child in element.EnumerateArray()) RejectDuplicates(child);
    }
    public void Dispose() => http.Dispose();
}
