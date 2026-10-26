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
    [Fact]
    public async Task MarketDataAllowsPersonalDisplayButRejectsBroaderRightsAndSecretBearingResponses()
    {
        string valid = """{"provider":"MASSIVE","enabled":true,"keyConfigured":true,"result":"UPDATED","quote":null,"fx":{"symbol":"USD/CNY","price":"7.123456789012","time":"2026-10-23T00:00:00Z","fetchedAt":"2026-10-26T14:00:01Z","exchange":"FOREX","date":"2026-10-23","timeZone":"UTC"},"rights":{"display":true,"displayMemory":true,"persistence":false,"offline":false,"valuation":false},"licenseStatus":"PERSONAL_DISPLAY_ONLY","providerValidation":"RESPONSE_VALIDATED","dataContext":"SYNTHETIC"}""";
        string response = valid;
        var calls = new List<string>();
        using var client = new RuntimeClient(new Handler(async (request, _) => {
            calls.Add(request.Method + " " + request.RequestUri!.PathAndQuery);
            if (request.Content is not null) {
                using var payload = JsonDocument.Parse(await request.Content.ReadAsStringAsync());
                if (request.RequestUri.AbsolutePath.EndsWith("/refresh")) {
                    if (payload.RootElement.TryGetProperty("referenceSymbol", out var symbol)) Assert.Equal("SPY", symbol.GetString());
                    else Assert.Equal("", payload.RootElement.GetProperty("positionId").GetString());
                } else { Assert.Single(payload.RootElement.EnumerateObject()); Assert.Equal("synthetic_key", payload.RootElement.GetProperty("apiKey").GetString()); }
            }
            return Response(HttpStatusCode.OK, response);
        }), () => Token);
        var shown = await client.RefreshFinanceMarketDataAsync("", CancellationToken.None);
        Assert.True(shown.Enabled); Assert.Equal("7.123456789012", shown.Fx!.Price); Assert.False(shown.Rights.Valuation);
        var stock = shown with { Fx = null, Quote = new("SPY", "100.12345678", "2026-10-23T04:00:00Z", "2026-10-26T14:00:01Z", "ARCX", "2026-10-23", "America/New_York") };
        response = JsonSerializer.Serialize(stock, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.Equal("SPY", (await client.RefreshFinanceMarketDataAsync("reference:SPY", CancellationToken.None)).Quote!.Symbol);
        response = valid.Replace("\"result\":\"UPDATED\"", "\"result\":\"SAVED\"").Replace("\"providerValidation\":\"RESPONSE_VALIDATED\"", "\"providerValidation\":\"NOT_RUN\"");
        using (var json = JsonDocument.Parse(response)) {
            var fields = json.RootElement.EnumerateObject().ToDictionary(p => p.Name, p => p.Value.Clone());
            fields["fx"] = JsonSerializer.SerializeToElement<object?>(null); response = JsonSerializer.Serialize(fields);
        }
        var saved = await client.ConfigureFinanceMarketDataAsync("synthetic_key", CancellationToken.None);
        Assert.True(saved.Enabled); Assert.Equal("PERSONAL_DISPLAY_ONLY", saved.LicenseStatus); Assert.Null(saved.Fx);
        Assert.Equal(new[] { "POST /api/v1/finance/market-data/refresh", "POST /api/v1/finance/market-data/refresh", "POST /api/v1/finance/market-data/configuration" }, calls);
        foreach (string bad in new[] { valid.Replace("\"provider\":", "\"apiKey\":\"synthetic_key\",\"provider\":"), valid.Replace("\"7.123456789012\"", "7.123456789012"),
            valid.Replace("\"valuation\":false", "\"valuation\":true"), valid.Replace("\"MASSIVE\"", "\"TWELVE_DATA\"") }) {
            response = bad;
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceMarketDataAsync(CancellationToken.None))).Error);
        }
    }
    [Fact]
    public async Task AnnualReadPreservesExactMoneyFutureUnknownsAndRejectsUnboundedOrConfusedContracts()
    {
        var months = Enumerable.Range(1, 12).Select(m => new FinanceMonth($"2026-{m:D2}", m > 10 ? "FUTURE" : "OBSERVED",
            m > 10 ? null : m == 1 ? "9007199254740993.10" : "0", m > 10 ? null : "0",
            m > 10 ? null : m == 1 ? "9007199254740993.10" : "0", m > 10 ? null : m == 1 ? "1" : "0")).ToArray();
        int?[] coordinates = Enumerable.Range(1, 12).Select(m => m > 10 ? (int?)null : 10000).ToArray();
        var annual = new FinanceAnnual("CNY", "Asia/Shanghai", "2026-10-24T12:00:00.123456789", 2026, 2026, [2026],
            "9007199254740993.10", "0", "9007199254740993.10", "1", months,
            new(coordinates, coordinates, coordinates, 10000, Enumerable.Range(0, 5).Select(i => new FinancePlotTick(i * 2500, "0")).ToArray()), new("EMPTY", "0", []));
        string valid = JsonSerializer.Serialize(annual, new JsonSerializerOptions(JsonSerializerDefaults.Web)), response = valid;
        using var client = new RuntimeClient(new Handler((request, _) => {
            Assert.Equal(HttpMethod.Get, request.Method); Assert.Equal("/api/v1/finance/annual?year=0", request.RequestUri!.PathAndQuery);
            return Task.FromResult(Response(HttpStatusCode.OK, response));
        }), () => Token);
        var result = await client.GetFinanceAnnualAsync(0, CancellationToken.None);
        Assert.Equal("9007199254740993.10", result.Income); Assert.Null(result.Months[10].Income);
        Assert.False(RuntimeClient.ValidFinanceYear(1899)); Assert.False(RuntimeClient.ValidFinanceYear(2200));
        Assert.True(RuntimeClient.ValidFinanceTransactionQuery(new(0, 10, "", "2025-01-01", "", "EXPENSE", "2025-01", annual.AsOf)));
        foreach (string bad in new[] { valid.Replace("\"9007199254740993.10\"", "9007199254740993.10"),
            valid.Replace("\"year\":2026", "\"year\":2025"), valid.Replace("\"zero\":10000", "\"zero\":10001"),
            valid.Replace("\"income\":null", "\"income\":\"0\""), valid.Replace("\"currency\":", "\"userId\":2,\"currency\":") }) {
            response = bad;
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceAnnualAsync(0, CancellationToken.None))).Error);
        }
    }

    [Fact]
    public async Task DashboardDrilldownRequiresEchoedScopeOrdinaryCnyAndExclusiveNanosecondBoundary()
    {
        var query = new FinanceTransactionQuery(0, 10, "", "2026-10-01", "", "INCOME", "2026-10", "2026-10-24T12:00:00.123456789");
        Assert.True(RuntimeClient.ValidFinanceTransactionQuery(query));
        Assert.False(RuntimeClient.ValidFinanceTransactionQuery(query with { CashFlowAsOf = "" }));
        Assert.False(RuntimeClient.ValidFinanceTransactionQuery(query with { EndDate = "2026-10-24" }));
        string valid = """
            {"page":0,"size":10,"total":"1","accountId":"","startDate":"2026-10-01","endDate":"","type":"INCOME",
             "cashFlowMonth":"2026-10","cashFlowAsOf":"2026-10-24T12:00:00.123456789",
             "items":[{"kind":"TRANSACTION","id":"1","type":"INCOME","amount":"9007199254740993.10","currency":"CNY",
             "transactedAt":"2026-10-24T12:00:00.123456788","description":null,"accountId":"1","accountName":"Synthetic",
             "toAccountId":null,"toAccountName":null}]}
            """;
        string response = valid;
        using var client = new RuntimeClient(new Handler((request, _) => {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.StartsWith("/api/v1/finance/transactions?", request.RequestUri!.PathAndQuery);
            Assert.Contains("cashFlowAsOf=" + query.CashFlowAsOf, request.RequestUri.Query);
            return Task.FromResult(Response(HttpStatusCode.OK, response));
        }), () => Token);
        Assert.Equal("9007199254740993.10", (await client.ListFinanceTransactionsAsync(query, CancellationToken.None)).Items[0].Amount);
        foreach (string bad in new[] { valid.Replace("\"CNY\"", "\"USD\""),
            valid.Replace("123456788", "123456789"), valid.Replace("\"cashFlowMonth\":\"2026-10\"", "\"cashFlowMonth\":\"2026-09\""),
            valid.Replace("\"9007199254740993.10\"", "9007199254740993.10") }) {
            response = bad;
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.ListFinanceTransactionsAsync(query, CancellationToken.None))).Error);
        }
    }

    [Fact]
    public async Task InvestmentAuditRejectsNumericMoneyAndConfusedPhysicalFacts()
    {
        var values = new FinanceHistoryValues("9007199254740993.12345678", "1.00000000", "9007199254740993.12", "0.00", "0.00", "0.00", "0.00", "0.00", null, null);
        var position = new FinanceHistoryPosition("1.00000000", "1.00000000", "1.00", "0.00", "OPEN");
        var timeline = new FinanceHistoryTimeline("101", "UNCHANGED", [new("ORIGINAL_POSTING", "2026-10-01T00:00:00.123456789Z", "101", null, "BUY", null, "POSTING_TIME", values, null, null, [])], position);
        string valid = JsonSerializer.Serialize(timeline, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase }), response = valid;
        using var client = new RuntimeClient(new Handler((request, _) => {
            Assert.Equal("/api/v1/finance/investment/transactions/101/audit-timeline", request.RequestUri!.PathAndQuery);
            return Task.FromResult(Response(HttpStatusCode.OK, response));
        }), () => Token);
        Assert.Equal(values.Quantity, (await client.GetFinanceHistoryTimelineAsync("101", CancellationToken.None)).Events[0].BusinessValues!.Quantity);
        foreach (string bad in new[] { valid.Replace("\"9007199254740993.12\"", "9007199254740993.12"),
            valid.Replace("\"physicalFactId\":\"101\"", "\"physicalFactId\":\"102\""), valid.Replace("ORIGINAL_POSTING", "REPLACEMENT_FACT"),
            valid.Replace("\"currentPosition\":", "\"userId\":2,\"currentPosition\":") }) {
            response = bad;
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceHistoryTimelineAsync("101", CancellationToken.None))).Error);
        }
    }

    [Fact]
    public async Task PortfolioKeepsPartialAmountsExactAndRejectsAmbiguousFinancialResponses()
    {
        string valid = """
            {"currency":"CNY","positionCount":3,"openPositionCount":2,"closedPositionCount":1,
             "openTotalCost":"9007199254740993.12","cumulativeRealizedProfitLoss":"-2.00",
             "referenceValuation":{"baseCurrency":"CNY","value":"123.45","valuedPositionCount":1,
               "totalOpenPositionCount":2,"freshness":"PARTIAL","warnings":[{"code":"QUOTE_STALE","component":"QUOTE"}]}}
            """;
        string response = valid;
        using var client = new RuntimeClient(new Handler((request, _) => {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Equal("/api/v1/finance/investment/portfolio", request.RequestUri!.PathAndQuery);
            return Task.FromResult(Response(HttpStatusCode.OK, response));
        }), () => Token);
        var value = await client.GetFinancePortfolioAsync(CancellationToken.None);
        Assert.Equal("9007199254740993.12", value.OpenTotalCost); Assert.Equal("123.45", value.ReferenceValuation.Value);
        foreach (string invalid in new[] { valid.Replace("\"9007199254740993.12\"", "9007199254740993.12"),
            valid.Replace("\"PARTIAL\"", "\"FRESH\""), valid.Replace("\"123.45\"", "null"),
            valid.Replace("\"positionCount\":3", "\"positionCount\":4"), valid.Replace("\"currency\":\"CNY\"", "\"currency\":\"USD\""),
            valid.Replace("\"currency\":", "\"userId\":2,\"currency\":"), valid.Replace("\"-2.00\"", "\"-2e0\"") })
        {
            response = invalid;
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinancePortfolioAsync(CancellationToken.None))).Error);
        }
    }

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
        var result = await new AssistantOperation(client, TimeSpan.FromMilliseconds(1)).RunAsync(new(AssistantAction.Translate, "private-input-marker", "zh-CN"),
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
        var operation = new AssistantOperation(client);
        var running = operation.RunAsync(new(AssistantAction.Translate, "x", "en"), _ => { }, CancellationToken.None);
        await admitted.Task;
        operation.RequestCancel();
        Assert.False(running.IsCompleted);
        Assert.Equal(0, deletes);
        release.SetResult();
        var cancelled = await running;
        Assert.Equal(TaskState.CANCELLED, cancelled.Status);
        Assert.Equal(DesktopError.Cancelled, cancelled.Error);
        Assert.Equal(1, deletes);
    }



    [Fact]
    public async Task OfflineIsControlledAndCredentialMissingNeverSendsHttp()
    {
        using var client = new RuntimeClient(new Handler((_, _) => throw new HttpRequestException("private-token-secret")), () => Token);
        var error = await Assert.ThrowsAsync<DesktopException>(() => new AssistantOperation(client)
            .RunAsync(new(AssistantAction.Translate, "x", "en"), _ => { }, CancellationToken.None));
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
    public async Task HealthNegotiatesJsonWithActuatorVendorDefault()
    {
        using var client = new RuntimeClient(new Handler((request, _) =>
        {
            Assert.Equal("/actuator/health", request.RequestUri!.AbsolutePath);
            Assert.Null(request.Headers.Authorization);
            var response = Response(HttpStatusCode.OK, "{\"status\":\"UP\"}");
            if (!request.Headers.Accept.Any(value => value.MediaType == "application/json"))
                response.Content.Headers.ContentType = new("application/vnd.spring-boot.actuator.v3+json");
            return Task.FromResult(response);
        }), () => throw new Xunit.Sdk.XunitException("Health must not read credentials"));
        await client.CheckHealthAsync(CancellationToken.None);
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
