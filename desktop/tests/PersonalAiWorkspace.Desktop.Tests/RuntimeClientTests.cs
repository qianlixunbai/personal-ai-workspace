using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class RuntimeClientTests
{
    private static readonly Guid Id = Guid.NewGuid();
    private static string Token => new('t', 43);
    internal static string Envelope(string status, string? error = null, Guid? id = null) => JsonSerializer.Serialize(new
    {
        taskId = id ?? Id, capability = "translate", status,
        profile = new { id = "translate.fast", version = "m0-1", locality = "LOCAL" },
        promptVersion = "translate-v1", createdAt = "2026-10-01T10:00:00Z",
        finishedAt = status is "QUEUED" or "RUNNING" ? null : "2026-10-01T10:00:01Z",
        result = status == "SUCCEEDED" ? "private-result-marker" : null,
        error = error is null ? null : new { code = error, message = "private-provider-body", phase = "PROVIDER" }
    });
    internal static HttpResponseMessage Response(HttpStatusCode status, string body)
    {
        var response = new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        if (status == HttpStatusCode.Accepted) response.Headers.Location = new Uri($"/api/v1/tasks/{Id:D}", UriKind.Relative);
        return response;
    }
    internal sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) => send(request, token);
    }

    [Fact]
    public async Task SubmitPollAndSuccessFollowM0ContractWithoutModelOrBodyInDiagnostics()
    {
        var calls = new List<string>();
        using var client = new RuntimeClient(new Handler(async (request, _) =>
        {
            Assert.Equal("127.0.0.1", request.RequestUri!.Host);
            Assert.Equal(8765, request.RequestUri.Port);
            Assert.Equal(Token, request.Headers.Authorization!.Parameter);
            calls.Add(request.Method + " " + request.RequestUri.AbsolutePath);
            if (request.Method == HttpMethod.Post)
            {
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
                Assert.Equal("private-input-marker", body.RootElement.GetProperty("text").GetString());
                Assert.Equal("translate.fast", body.RootElement.GetProperty("profile").GetString());
                Assert.Equal("zh-CN", body.RootElement.GetProperty("targetLanguage").GetString());
                Assert.False(body.RootElement.TryGetProperty("model", out var ignoredModel));
                return Response(HttpStatusCode.Accepted, Envelope("QUEUED"));
            }
            return Response(HttpStatusCode.OK, Envelope(calls.Count == 2 ? "RUNNING" : "SUCCEEDED"));
        }), () => Token);
        var progress = new List<TaskState>();
        var result = await new TranslationOperation(client, TimeSpan.FromMilliseconds(1)).RunAsync(new("private-input-marker", "zh-CN"),
            task => progress.Add(task.Status), CancellationToken.None);
        Assert.Equal(new[] { TaskState.QUEUED, TaskState.RUNNING, TaskState.SUCCEEDED }, progress);
        Assert.Equal("private-result-marker", result.Result);
        Assert.Equal($"GET /api/v1/tasks/{Id:D}", calls[1]);
        Assert.DoesNotContain(result.Result!, result.ToString());
        Assert.DoesNotContain("private-input-marker", new TranslateInput("private-input-marker", "en").ToString());
    }

    [Fact]
    public async Task CancelWhilePostPendingWaitsForIdentityThenDeletesAcceptedTask()
    {
        var admitted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int deletes = 0;
        using var client = new RuntimeClient(new Handler(async (request, _) =>
        {
            if (request.Method == HttpMethod.Post)
            { admitted.SetResult(); await release.Task; return Response(HttpStatusCode.Accepted, Envelope("QUEUED")); }
            Assert.Equal(HttpMethod.Delete, request.Method);
            Assert.EndsWith($"/{Id:D}", request.RequestUri!.AbsolutePath);
            deletes++;
            return Response(HttpStatusCode.OK, Envelope("CANCELLED", "TASK_CANCELLED"));
        }), () => Token);
        var operation = new TranslationOperation(client);
        var running = operation.RunAsync(new("x", "en"), _ => { }, CancellationToken.None);
        await admitted.Task;
        operation.RequestCancel();
        release.SetResult();
        Assert.Equal(TaskState.CANCELLED, (await running).Status);
        Assert.Equal(1, deletes);
    }

    [Theory]
    [InlineData(401, "UNAUTHORIZED", DesktopError.Unauthorized)]
    [InlineData(404, "TASK_NOT_FOUND", DesktopError.TaskNotFound)]
    [InlineData(429, "QUEUE_FULL", DesktopError.QueueFull)]
    [InlineData(403, "POLICY_DENIED", DesktopError.PolicyDenied)]
    [InlineData(503, "PROVIDER_UNAVAILABLE", DesktopError.ProviderUnavailable)]
    [InlineData(503, "MODEL_UNAVAILABLE", DesktopError.ModelUnavailable)]
    public async Task HttpErrorsAreCategorizedAndRawBodyTokenNeverEnterException(int status, string code, DesktopError expected)
    {
        using var client = new RuntimeClient(new Handler((_, _) => Task.FromResult(Response((HttpStatusCode)status,
            JsonSerializer.Serialize(new { code, message = "private-input-marker " + Token, phase = "HTTP" })))), () => Token);
        var error = await Assert.ThrowsAsync<DesktopException>(() => client.GetAsync(Id, CancellationToken.None));
        Assert.Equal(expected, error.Error);
        Assert.DoesNotContain("private-input-marker", error.ToString());
        Assert.DoesNotContain(Token, error.ToString());
        Assert.Null(error.InnerException);
    }

    [Theory]
    [InlineData("FAILED", "PROVIDER_UNAVAILABLE", DesktopError.ProviderUnavailable)]
    [InlineData("FAILED", "MODEL_UNAVAILABLE", DesktopError.ModelUnavailable)]
    [InlineData("FAILED", "POLICY_DENIED", DesktopError.PolicyDenied)]
    [InlineData("TIMED_OUT", "TASK_TIMEOUT", DesktopError.TimedOut)]
    [InlineData("CANCELLED", "TASK_CANCELLED", DesktopError.Cancelled)]
    public async Task AcceptedTaskFailureIsReadFrom200Envelope(string status, string code, DesktopError expected)
    {
        using var client = new RuntimeClient(new Handler((_, _) => Task.FromResult(Response(HttpStatusCode.OK, Envelope(status, code)))), () => Token);
        var task = await client.GetAsync(Id, CancellationToken.None);
        Assert.True(task.Terminal);
        Assert.Equal(expected, task.Error);
        Assert.Null(task.Result);
        Assert.DoesNotContain("private-provider-body", task.ToString());
    }

    [Fact]
    public async Task OfflineIsControlledAndCredentialMissingNeverSendsHttp()
    {
        using var client = new RuntimeClient(new Handler((_, _) => throw new HttpRequestException("private-token-secret")), () => Token);
        var error = await Assert.ThrowsAsync<DesktopException>(() => client.GetAsync(Id, CancellationToken.None));
        Assert.Equal(DesktopError.RuntimeUnavailable, error.Error);
        Assert.DoesNotContain("private-token-secret", error.ToString());
        using var unpaired = new RuntimeClient(new Handler((_, _) => throw new Xunit.Sdk.XunitException("Must not send")), () => null);
        Assert.Equal(DesktopError.CredentialMissing,
            (await Assert.ThrowsAsync<DesktopException>(() => unpaired.GetAsync(Id, CancellationToken.None))).Error);
    }

    [Fact]
    public async Task MalformedUnknownDuplicateMismatchedAndOversizeResponsesFailClosed()
    {
        string valid = Envelope("SUCCEEDED");
        string[] invalid =
        [
            "{broken", "{}", valid.Replace("SUCCEEDED", "UNKNOWN"), valid.Replace("LOCAL", "CLOUD"),
            valid.Replace("private-result-marker", ""), valid.Replace("\"status\":", "\"status\":\"RUNNING\",\"status\":"),
            Envelope("CANCELLED", "PROVIDER_UNAVAILABLE"), Envelope("FAILED", "TASK_TIMEOUT"),
            Envelope("QUEUED").Replace("\"result\":null", "\"result\":\"stale-result\""),
            Envelope("SUCCEEDED", id: Guid.NewGuid()), " " + new string('x', 1024 * 1024)
        ];
        foreach (var body in invalid)
        {
            using var client = new RuntimeClient(new Handler((_, _) => Task.FromResult(Response(HttpStatusCode.OK, body))), () => Token);
            var error = await Assert.ThrowsAsync<DesktopException>(() => client.GetAsync(Id, CancellationToken.None));
            Assert.Equal(DesktopError.InvalidResponse, error.Error);
        }
    }

    [Fact]
    public async Task HealthDoesNotUseCredentialAndReadinessProvesAuthWhenProviderOffline()
    {
        using var client = new RuntimeClient(new Handler((request, _) =>
        {
            bool health = request.RequestUri!.AbsolutePath == "/actuator/health";
            Assert.Equal(health, request.Headers.Authorization is null);
            return Task.FromResult(Response(HttpStatusCode.OK, health ? "{\"status\":\"UP\"}" :
                "{\"provider\":\"ollama\",\"profile\":\"translate.fast\",\"available\":false,\"modelAvailable\":false,\"error\":{\"code\":\"PROVIDER_UNAVAILABLE\"}}"));
        }), () => Token);
        await client.CheckHealthAsync(CancellationToken.None);
        await client.CheckCredentialAsync(CancellationToken.None);
    }

    [Fact]
    public async Task RedirectAndForeignLocationNeverRedirectOrAcceptTask()
    {
        using var redirected = new RuntimeClient(new Handler((_, _) => Task.FromResult(Response(HttpStatusCode.Redirect, "{}"))), () => Token);
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => redirected.GetAsync(Id, CancellationToken.None))).Error);
        using var badLocation = new RuntimeClient(new Handler((_, _) =>
        {
            var response = Response(HttpStatusCode.Accepted, Envelope("QUEUED"));
            response.Headers.Location = new Uri("https://example.invalid/steal");
            return Task.FromResult(response);
        }), () => Token);
        Assert.Equal(DesktopError.InvalidResponse,
            (await Assert.ThrowsAsync<DesktopException>(() => badLocation.SubmitAsync(new("x", "en"), CancellationToken.None))).Error);
    }
}
