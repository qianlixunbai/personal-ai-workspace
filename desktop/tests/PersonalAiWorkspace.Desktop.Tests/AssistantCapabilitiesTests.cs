using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class AssistantCapabilitiesTests
{
    private static readonly Guid Id = Guid.NewGuid();
    private static string Token => new('t', 43);
    private static string Capability(AssistantAction action) => action == AssistantAction.Ask ? "ask" : "summarize";
    private static string Envelope(AssistantAction action, string status, string? error = null) => JsonSerializer.Serialize(new
    {
        taskId = Id, capability = Capability(action), status,
        profile = new { id = action == AssistantAction.Ask ? "chat.balanced" : "summarize.fast", version = "m1.5-1", locality = "LOCAL" },
        promptVersion = Capability(action) + "-v1", createdAt = "2026-10-02T01:00:00Z",
        finishedAt = status is "QUEUED" or "RUNNING" ? null : "2026-10-02T01:00:01Z",
        result = status == "SUCCEEDED" ? "private-output-marker" : null,
        error = error is null ? null : new { code = error, message = "private-provider-body", phase = "PROVIDER" }
    });
    private static HttpResponseMessage Response(HttpStatusCode status, string body)
    {
        var response = RuntimeClientTests.Response(status, body);
        if (status == HttpStatusCode.Accepted) response.Headers.Location = new Uri($"/api/v1/tasks/{Id:D}", UriKind.Relative);
        return response;
    }
    [Theory]
    [InlineData(AssistantAction.Summarize)]
    [InlineData(AssistantAction.Ask)]
    public async Task SubmitPollSuccessUsesOneClientAndOnlyCapabilityFields(AssistantAction action)
    {
        int calls = 0;
        using var client = new RuntimeClient(new RuntimeClientTests.Handler(async (request, _) =>
        {
            calls++;
            Assert.Equal("http://127.0.0.1:8765", request.RequestUri!.GetLeftPart(UriPartial.Authority));
            Assert.Equal(Token, request.Headers.Authorization!.Parameter);
            if (request.Method == HttpMethod.Post)
            {
                Assert.Equal($"/api/v1/{Capability(action)}/tasks", request.RequestUri.AbsolutePath);
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
                Assert.Equal(2, body.RootElement.EnumerateObject().Count());
                Assert.Equal("private-input-marker", body.RootElement.GetProperty(action == AssistantAction.Ask ? "question" : "text").GetString());
                Assert.Equal(action == AssistantAction.Ask ? "chat.balanced" : "summarize.fast", body.RootElement.GetProperty("profile").GetString());
                return Response(HttpStatusCode.Accepted, Envelope(action, "QUEUED"));
            }
            Assert.Equal($"/api/v1/tasks/{Id:D}", request.RequestUri.AbsolutePath);
            return Response(HttpStatusCode.OK, Envelope(action, calls == 2 ? "RUNNING" : "SUCCEEDED"));
        }), () => Token);
        var states = new List<TaskState>();
        var input = new AssistantInput(action, "private-input-marker");
        var result = await new AssistantOperation(client, TimeSpan.FromMilliseconds(1)).RunAsync(input, t => states.Add(t.Status), CancellationToken.None);
        Assert.Equal(new[] { TaskState.QUEUED, TaskState.RUNNING, TaskState.SUCCEEDED }, states);
        Assert.Equal("private-output-marker", result.Result);
        Assert.DoesNotContain("private", input.ToString());
        Assert.DoesNotContain("private", result.ToString());
    }
    [Theory]
    [InlineData(AssistantAction.Summarize)]
    [InlineData(AssistantAction.Ask)]
    public async Task CancelPendingSubmissionObtainsIdentityAndDeletesSameCapability(AssistantAction action)
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int deletes = 0;
        using var client = new RuntimeClient(new RuntimeClientTests.Handler(async (request, _) =>
        {
            if (request.Method == HttpMethod.Post)
            {
                entered.SetResult(); await release.Task;
                return Response(HttpStatusCode.Accepted, Envelope(action, "QUEUED"));
            }
            Assert.Equal(HttpMethod.Delete, request.Method);
            Assert.EndsWith($"/{Id:D}", request.RequestUri!.AbsolutePath);
            deletes++;
            return Response(HttpStatusCode.OK, Envelope(action, "CANCELLED", "TASK_CANCELLED"));
        }), () => Token);
        var operation = new AssistantOperation(client);
        var running = operation.RunAsync(new(action, "private input"), _ => { }, CancellationToken.None);
        await entered.Task;
        operation.RequestCancel(); release.SetResult();
        Assert.Equal(TaskState.CANCELLED, (await running).Status);
        Assert.Equal(1, deletes);
    }
    [Theory]
    [InlineData(AssistantAction.Summarize)]
    [InlineData(AssistantAction.Ask)]
    public async Task SharedErrorsAndMalformedResponsesRemainControlled(AssistantAction action)
    {
        foreach (var (status, code, expected) in new[] {
            (401, "UNAUTHORIZED", DesktopError.Unauthorized), (503, "PROVIDER_UNAVAILABLE", DesktopError.ProviderUnavailable),
            (503, "MODEL_UNAVAILABLE", DesktopError.ModelUnavailable), (429, "QUEUE_FULL", DesktopError.QueueFull) })
        {
            using var client = new RuntimeClient(new RuntimeClientTests.Handler((_, _) => Task.FromResult(Response((HttpStatusCode)status,
                JsonSerializer.Serialize(new { code, message = "private body " + Token })))), () => Token);
            var error = await Assert.ThrowsAsync<DesktopException>(() => new AssistantOperation(client).RunAsync(new(action, "private input"), _ => { }, CancellationToken.None));
            Assert.Equal(expected, error.Error);
            Assert.DoesNotContain("private", error.ToString());
            Assert.DoesNotContain(Token, error.ToString());
        }
        using var offline = new RuntimeClient(new RuntimeClientTests.Handler((_, _) => throw new HttpRequestException("private raw error")), () => Token);
        Assert.Equal(DesktopError.RuntimeUnavailable, (await Assert.ThrowsAsync<DesktopException>(() => new AssistantOperation(offline)
            .RunAsync(new(action, "x"), _ => { }, CancellationToken.None))).Error);
        foreach (var body in new[] { "{broken", Envelope(action, "SUCCEEDED").Replace("LOCAL", "CLOUD"),
            Envelope(action, "SUCCEEDED").Replace(Capability(action) + "-v1", ""),
            Envelope(action, "SUCCEEDED").Replace("private-output-marker", new string('x', 8193)) })
        {
            using var client = new RuntimeClient(new RuntimeClientTests.Handler((_, _) => Task.FromResult(Response(HttpStatusCode.Accepted, body))), () => Token);
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => new AssistantOperation(client)
                .RunAsync(new(action, "x"), _ => { }, CancellationToken.None))).Error);
        }
    }
    [Theory]
    [InlineData(AssistantAction.Summarize)]
    [InlineData(AssistantAction.Ask)]
    public async Task PollCannotSwitchCapabilityOrDisplayAnotherActionsResult(AssistantAction action)
    {
        using var client = new RuntimeClient(new RuntimeClientTests.Handler((request, _) => Task.FromResult(Response(
            request.Method == HttpMethod.Post ? HttpStatusCode.Accepted : HttpStatusCode.OK,
            Envelope(request.Method == HttpMethod.Post ? action : action == AssistantAction.Ask ? AssistantAction.Summarize : AssistantAction.Ask,
                request.Method == HttpMethod.Post ? "QUEUED" : "SUCCEEDED")))), () => Token);
        var shown = new List<TaskState>();
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => new AssistantOperation(client, TimeSpan.FromMilliseconds(1))
            .RunAsync(new(action, "x"), t => shown.Add(t.Status), CancellationToken.None))).Error);
        Assert.DoesNotContain(TaskState.SUCCEEDED, shown);
    }
    [Fact]
    public void CapabilityInputBudgetsRejectEmptyCharactersAndUtf8Overflow()
    {
        foreach (var input in new[] { new AssistantInput(AssistantAction.Summarize, " "),
            new AssistantInput(AssistantAction.Summarize, new string('x', 6001)), new AssistantInput(AssistantAction.Summarize, new string('中', 2300)),
            new AssistantInput(AssistantAction.Ask, new string('x', 3001)), new AssistantInput(AssistantAction.Ask, new string('中', 2000)) })
            Assert.Equal(DesktopError.InvalidRequest, Assert.Throws<DesktopException>(input.Validate).Error);
        new SummarizeInput(new string('x', 6000)).Validate();
        new AskInput(new string('x', 3000)).Validate();
    }
}
