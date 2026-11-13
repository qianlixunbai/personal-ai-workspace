using System.IO;
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


}
