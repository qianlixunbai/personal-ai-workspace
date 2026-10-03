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
    private static string Profile(AssistantAction action) => action == AssistantAction.Ask ? "chat.balanced" : "summarize.fast";
    private static string Envelope(AssistantAction action, string status) => JsonSerializer.Serialize(new
    {
        taskId = Id, capability = Capability(action), status,
        profile = new { id = Profile(action), version = "m1.5-1", locality = "LOCAL" },
        promptVersion = Capability(action) + "-v1", createdAt = "2026-10-02T01:00:00Z",
        finishedAt = status is "QUEUED" or "RUNNING" ? null : "2026-10-02T01:00:01Z",
        result = status == "SUCCEEDED" ? "private-output-marker" : null,
        error = (object?)null
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
    public async Task SubmissionAndPollingUseCapabilityRouteBodyAndResultIdentity(AssistantAction action)
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
                Assert.Equal(Profile(action), body.RootElement.GetProperty("profile").GetString());
                return Response(HttpStatusCode.Accepted, Envelope(action, "QUEUED"));
            }
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Equal($"/api/v1/tasks/{Id:D}", request.RequestUri.AbsolutePath);
            return Response(HttpStatusCode.OK, Envelope(action, "SUCCEEDED"));
        }), () => Token);
        var input = new AssistantInput(action, "private-input-marker");
        var result = await new AssistantOperation(client, TimeSpan.FromMilliseconds(1)).RunAsync(input, _ => { }, CancellationToken.None);
        Assert.Equal(2, calls);
        Assert.Equal(Id, result.TaskId);
        Assert.Equal(TaskState.SUCCEEDED, result.Status);
        Assert.Equal("private-output-marker", result.Result);
        Assert.DoesNotContain("private", input.ToString());
        Assert.DoesNotContain("private", result.ToString());
    }
    [Theory]
    [InlineData(AssistantAction.Summarize)]
    [InlineData(AssistantAction.Ask)]
    public async Task CapabilityResponsesRejectInvalidIdentityPromptAndOutput(AssistantAction action)
    {
        var other = action == AssistantAction.Ask ? AssistantAction.Summarize : AssistantAction.Ask;
        int outputBytes = action == AssistantAction.Summarize ? 4096 : 8192;
        string valid = Envelope(action, "SUCCEEDED");
        (string CaseName, string Body)[] invalid =
        [
            ("wrong-submit-capability", Envelope(other, "SUCCEEDED")),
            ("wrong-profile", valid.Replace(Profile(action), Profile(other))),
            ("empty-prompt-version", valid.Replace(Capability(action) + "-v1", "")),
            ("original-8193-byte-output", valid.Replace("private-output-marker", new string('x', 8193))),
            ("capability-output-byte-limit", valid.Replace("private-output-marker", new string('x', outputBytes + 1))),
            ("utf8-output-byte-limit", valid.Replace("private-output-marker", new string('中', outputBytes / 3 + 1)))
        ];
        foreach (var (caseName, body) in invalid)
        {
            using var client = new RuntimeClient(new RuntimeClientTests.Handler((_, _) => Task.FromResult(Response(HttpStatusCode.Accepted, body))), () => Token);
            var error = await Record.ExceptionAsync(() => new AssistantOperation(client)
                .RunAsync(new(action, "x"), _ => { }, CancellationToken.None));
            Assert.True(error is DesktopException { Error: DesktopError.InvalidResponse },
                $"{action}/{caseName}: expected InvalidResponse; actual {error?.GetType().Name ?? "no exception"}.");
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
