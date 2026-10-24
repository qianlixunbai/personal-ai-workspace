#if RUNTIME_LIFECYCLE_PROBE && MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    // One acceptance flow using a NEW fixture; the second bound Profile proves both directions of ownership.
    private static async Task VerifyFinancePositionsBob(AssistantApp app, RuntimeClient client)
    {
        Assert.Equal("Synthetic Bob", (await client.GetFinanceOverviewAsync(CancellationToken.None)).Username);
        Assert.Equal("16", Assert.Single((await client.ListFinancePositionsAsync(new("ALL", "", "", "", 10), CancellationToken.None)).Records).PositionId);
        Assert.Equal("999.00", (await client.GetFinancePositionAsync("16", CancellationToken.None)).TotalCost);
        foreach (string id in new[] { "11", "9223372036854775807" })
            Assert.Equal(DesktopError.FinancePositionNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinancePositionAsync(id, CancellationToken.None))).Error);
        Assert.Equal(DesktopError.FinancePositionNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.ListFinancePositionsAsync(new("ALL", "6", "", "", 10), CancellationToken.None))).Error);
        Assert.Equal(DesktopError.FinancePositionNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.ListFinancePositionsAsync(new("ALL", "", "11", "", 10), CancellationToken.None))).Error);
        Console.WriteLine("FNEXT6_SECOND_NATIVE_BOUND_USER_SAME_RESOURCE_OWNED_AND_FOREIGN_POSITION_ACCOUNT_INSTRUMENT_DENIED_PASS");
    }

    private static async Task VerifyFinancePositions(AssistantApp app, RuntimeClient client, Mmf3AcceptanceLaunch launch, string receipt = "fnext6-target.json", string evidence = "fnext6")
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task WaitScript(string script) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            try { while (await core.ExecuteScriptAsync(script) != "true") await Task.Delay(100, timeout.Token); }
            catch (OperationCanceledException) { Console.WriteLine("FNEXT6_TIMEOUT_" + script); Console.WriteLine(await core.ExecuteScriptAsync("document.querySelector('.finance-page')?.innerText")); throw; }
        }
        async Task Click(string text) {
            string button = "[...document.querySelectorAll('button')].find(b=>b.textContent===" + JsonSerializer.Serialize(text) + "&&!b.disabled)";
            await WaitScript("Boolean(" + button + ")"); await core.ExecuteScriptAsync(button + ".click()");
        }
        async Task Contains(string text) => await WaitScript("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))");
        async Task Select(string label, string value) {
            await WaitScript("Boolean(document.querySelector('select[aria-label=" + JsonSerializer.Serialize(label) + "]:not(:disabled)'))");
            await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('select[aria-label=" + JsonSerializer.Serialize(label) + "]');e.value=" + JsonSerializer.Serialize(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}));})()");
            await WaitScript("document.querySelector('.finance-page').getAttribute('aria-busy')==='false'");
        }
        async Task Filter(string account, string instrument) {
            await core.ExecuteScriptAsync("(()=>{const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;for(const [label,value] of "
                + JsonSerializer.Serialize(new[] { new[] { "持仓账户 ID", account }, new[] { "持仓 Instrument ID", instrument } })
                + "){const e=document.querySelector('input[aria-label=\"'+label+'\"]');set.call(e,value);e.dispatchEvent(new Event('input',{bubbles:true}));}})()");
            await Click("应用持仓筛选");
            await WaitScript("document.querySelector('.finance-page').getAttribute('aria-busy')==='false'");
        }

        await Click("Investment Positions · 投资持仓"); await Contains("本页 5 项持仓");
        var query = new FinancePositionQuery("OPEN", "", "", "", 2);
        var first = await client.ListFinancePositionsAsync(query, CancellationToken.None);
        var second = await client.ListFinancePositionsAsync(query with { Cursor = first.NextCursor! }, CancellationToken.None);
        var last = await client.ListFinancePositionsAsync(query with { Cursor = second.NextCursor! }, CancellationToken.None);
        Assert.Equal(new[] { "11", "13", "14", "15", "17" }, first.Records.Concat(second.Records).Concat(last.Records).Select(p => p.PositionId));
        Assert.False(last.HasMore); Assert.Null(last.NextCursor);
        var all = await client.ListFinancePositionsAsync(query with { Status = "ALL", Size = 20 }, CancellationToken.None);
        Assert.Equal(6, all.Records.Length); Assert.Equal("12", Assert.Single(all.Records, p => p.Status == "CLOSED").PositionId);
        var exact = await client.GetFinancePositionAsync("11", CancellationToken.None);
        Assert.Equal("9007199254740993.12345678", exact.Quantity); Assert.Equal("1.00000000", exact.AverageCost);
        Assert.Equal("9007199254740993.12", exact.TotalCost); Assert.Equal("1.00", exact.CumulativeRealizedProfitLoss);
        Assert.Equal("123.45", exact.ManualReference.MarketValue); Assert.Equal("1.00000000", exact.ManualReference.CurrentPrice);
        Assert.Equal("USD", exact.Instrument.QuoteCurrency); Assert.Equal("6305039478318695.19", exact.ReferenceValuation.BaseCurrencyValue);
        Assert.Equal("STALE", exact.ReferenceValuation.Freshness); Assert.False(exact.ReferenceValuation.AccountingTruth);
        Assert.Contains(exact.ReferenceValuation.Warnings, w => w.Code == "QUOTE_STALE"); Assert.Contains(exact.ReferenceValuation.Warnings, w => w.Code == "FX_STALE");
        var closed = await client.GetFinancePositionAsync("12", CancellationToken.None);
        Assert.Equal(exact.Instrument.Id, closed.Instrument.Id); Assert.NotEqual(exact.Account.Id, closed.Account.Id);
        Assert.Equal("CLOSED", closed.Status); Assert.Equal("INACTIVE", closed.Account.Status); Assert.Equal("0.00000000", closed.Quantity);
        Assert.Equal("0.00", closed.TotalCost); Assert.Equal("-3.00", closed.CumulativeRealizedProfitLoss);
        var partial = await client.GetFinancePositionAsync("13", CancellationToken.None);
        Assert.Equal("2.34900019", partial.AverageCost); Assert.Equal("0.29", partial.TotalCost); Assert.Equal("0.00", partial.CumulativeRealizedProfitLoss);
        Assert.Equal("PARTIAL", partial.ReferenceValuation.Freshness); Assert.Null(partial.ReferenceValuation.BaseCurrencyValue); Assert.Null(partial.ManualReference.CurrentPrice);
        Assert.Contains(partial.ReferenceValuation.Warnings, w => w.Code == "FX_MISSING");
        var missing = await client.GetFinancePositionAsync("14", CancellationToken.None);
        Assert.Equal("UNAVAILABLE", missing.ReferenceValuation.Freshness); Assert.Null(missing.ReferenceValuation.QuotePrice); Assert.Null(missing.ReferenceValuation.BaseCurrencyValue);
        var cny = await client.GetFinancePositionAsync("15", CancellationToken.None);
        Assert.Equal("0.02", cny.ReferenceValuation.BaseCurrencyValue); Assert.Equal("SYSTEM_IDENTITY", cny.ReferenceValuation.FxProvider);
        Assert.Equal("FRESH", cny.ReferenceValuation.Freshness);
        Assert.Equal("UNAVAILABLE", (await client.GetFinancePositionAsync("17", CancellationToken.None)).ReferenceValuation.Freshness);
        Console.WriteLine("FNEXT6_REAL_SOURCE_SEEK_AND_FINANCIAL_PRECISION_COST_REALIZED_POSITIVE_NEGATIVE_ZERO_MANUAL_CACHE_NULL_FX_STALE_CNY_PASS");

        await Select("每页持仓数", "2"); await Click("下一页"); await Contains("第 2 页"); await Click("上一页"); await Contains("第 1 页");
        await Select("持仓状态", "ALL"); await Contains("本页 2 项持仓"); await Click("合成精确持仓 · #12"); await Contains("-3.00 CNY");
        await Select("持仓状态", "CLOSED"); await Contains("本页 1 项持仓");
        await Filter("6", "11"); await Contains("当前筛选下没有投资持仓");
        await Select("持仓状态", "OPEN"); await Contains("本页 1 项持仓");
        await Click("合成精确持仓 · #11"); await Contains("9007199254740993.12345678"); await Contains("6305039478318695.19 CNY");
        await Click("刷新持仓"); await Contains("持仓详情 · 合成精确持仓 · #11");
        await Click("清除持仓筛选"); await Click("合成缺汇率持仓 · #13"); await Contains("缺少换算汇率");
        await Filter("8", ""); await Contains("不存在或无权访问");
        await Click("清除持仓筛选"); await Click("合成精确持仓 · #11"); await Contains("SYNTHETIC_FIXED");
        Console.WriteLine("FNEXT6_REAL_WEBVIEW_TYPED_BRIDGE_LIST_DETAIL_FILTER_EMPTY_SAFE_BACK_REFRESH_ERROR_RETRY_PASS");

        using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
        async Task Check(string suffix, HttpStatusCode status, bool authenticated = true, HttpMethod? method = null) {
            using var request = new HttpRequestMessage(method ?? HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/investment/positions" + suffix);
            if (authenticated) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(status, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
        }
        await Check("/16", HttpStatusCode.NotFound); await Check("/9223372036854775807", HttpStatusCode.NotFound); await Check("/1", HttpStatusCode.NotFound); // LEGACY excluded.
        foreach (string suffix in new[] { "?accountId=8", "?instrumentId=15", "?accountId=9223372036854775807", "?instrumentId=9223372036854775807" }) await Check(suffix, HttpStatusCode.NotFound);
        await Check("", HttpStatusCode.Unauthorized, false);
        foreach (string suffix in new[] { "?userId=2", "?size=21", "?size=0", "?accountId=0", "?instrumentId=-1", "?status=LEGACY", "?cursor=bad", "?cursor=", "?status=", "?status=OPEN&status=CLOSED", "?size=2&size=3", "/0", "/11?userId=2", "?accountId=9223372036854775808", "?status=CLOSED&cursor=" + first.NextCursor, "?accountId=6&cursor=" + first.NextCursor, "?instrumentId=11&cursor=" + first.NextCursor }) await Check(suffix, HttpStatusCode.BadRequest);
        foreach (var method in new[] { HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch, HttpMethod.Head, HttpMethod.Options }) await Check("", HttpStatusCode.MethodNotAllowed, method: method);
        string raw = Encoding.UTF8.GetString(Convert.FromBase64String(first.NextCursor!.Replace('-', '+').Replace('_', '/').PadRight((first.NextCursor.Length + 3) / 4 * 4, '=')));
        foreach (string bad in new[] { raw.Replace("\"version\":1", "\"version\":1.5"), raw.Replace("\"version\":1", "\"version\":4294967297"), raw.Replace("\"version\":1", "\"version\":1,\"version\":1"), raw.Replace("\"instrumentId\":12", "\"instrumentId\":12.5"), raw + "{}" })
            await Check("?cursor=" + Convert.ToBase64String(Encoding.UTF8.GetBytes(bad)).TrimEnd('=').Replace('+', '-').Replace('/', '_'), HttpStatusCode.BadRequest);
        Console.WriteLine("FNEXT6_REAL_PERMISSION_METHOD_AUTH_NO_STORE_INVALID_PARAMS_FILTER_BOUND_AND_AMBIGUOUS_CURSOR_DENIED_PASS");

        using var target = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(Environment.CurrentDirectory, ".verification", receipt)));
        string fixture = target.RootElement.GetProperty("root").GetString()!;
        async Task Identity(string action) {
            byte[] bytes = ProtectedData.Unprotect(await File.ReadAllBytesAsync(Path.Combine(fixture, "fixture.dpapi")), null, DataProtectionScope.CurrentUser);
            try {
                using var sources = TrustedDevelopmentRuntimeSources.Open();
                var start = new ProcessStartInfo(sources.Java) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
                foreach (string arg in new[] { "-cp", Path.Combine(fixture, "tooling", "classes") + ";" + Path.Combine(fixture, "tooling", "lib", "*"), "FinanceNextPreparation", action }) start.ArgumentList.Add(arg);
                using var p = Process.Start(start)!; await p.StandardInput.BaseStream.WriteAsync(bytes); p.StandardInput.Close();
                string result = await p.StandardOutput.ReadToEndAsync(); string errors = await p.StandardError.ReadToEndAsync();
                await p.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(30)); Assert.Equal(0, p.ExitCode); Assert.Equal("FNEXT_FIXTURE_PASS_" + action, result.Trim()); Assert.Empty(errors);
            } finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        string binding = Path.Combine(launch.Root, "data", "finance-identity", "binding.json");
        byte[] hash = SHA256.HashData(await File.ReadAllBytesAsync(binding));
        foreach (string change in new[] { "identity-change", "swap" }) {
            await Identity(change);
            try {
                await Check("/11", HttpStatusCode.Forbidden); await Check("", HttpStatusCode.Forbidden);
                await Click("刷新持仓"); await Contains("持仓读取已拒绝");
                Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('.finance-detail')&&!document.querySelector('.finance-page table')"));
            } finally { await Identity(change == "swap" ? "unswap" : "identity-restore"); }
            await Click("刷新持仓"); await Contains("本页 5 项持仓"); await Click("合成精确持仓 · #11"); await Contains("9007199254740993.12345678");
        }
        Console.WriteLine("FNEXT6_REAL_USER_AND_DATASET_IDENTITY_CHANGE_FAIL_CLOSED_CLEAR_SNAPSHOT_SAFE_RETRY_NO_REBIND_PASS");

        foreach (int width in new[] { 1280, 800 }) {
            app.Workspace.Width = width; app.Workspace.Height = 900; await Task.Delay(250);
            Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            await core.ExecuteScriptAsync("document.querySelector('.finance-detail').scrollIntoView({block:'start'})");
            using var picture = File.Create(Path.Combine(Environment.CurrentDirectory, ".verification", $"{evidence}-{width}.png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, picture);
        }
        using var old = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
        await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(70)))
            while (!app.DevelopmentRuntime.Snapshot().CanQueryStorage || app.DevelopmentRuntime.OwnedProcessId == old.Id) await Task.Delay(100, timeout.Token);
        Assert.True(old.WaitForExit(10000)); await Contains("本页 5 项持仓"); await Click("合成精确持仓 · #11"); await Contains("6305039478318695.19 CNY");
        Assert.Equal(hash, SHA256.HashData(await File.ReadAllBytesAsync(binding)));
        await Click("Accounts · 账户"); await Contains("9007199254740993.30 CNY"); await Click("合成 CNY 账户"); await Contains("服务端账户 ID");
        await Click("Transactions · 交易流水"); await Contains("共 6 笔交易"); await Click("2026-10-23 12:00"); await Contains("F-next-3 expense");
        await Click("Assets · 资产");
        await Select("资产排序", "ID_ASC"); await Click("合成精确资产"); await Contains("9007199254740993.100000000000 CNY");
        await Click("Budgets · 预算"); await Contains("来源尚未提供预算模型");
        Console.WriteLine("FNEXT6_REAL_1280_800_RUNTIME_RESTART_RECOVERY_BINDING_UNCHANGED_ACCOUNTS_TRANSACTIONS_ASSETS_BUDGETS_REGRESSION_PASS");
    }
}
#endif
