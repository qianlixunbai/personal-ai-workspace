using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;
public static partial class RuntimeLifecycleProbe
{
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    private static async Task VerifyFinanceHistory(AssistantApp app, RuntimeClient client, Mmf3AcceptanceLaunch launch, bool bob, bool uiOnly, Func<string, Task> mutate)
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task Wait(string text) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            try { while (await core.ExecuteScriptAsync("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))") != "true") await Task.Delay(100, timeout.Token); }
            catch (OperationCanceledException) { Console.WriteLine("FNEXT8_WAIT_TIMEOUT_" + text); throw; }
        }
        async Task Click(string text) {
            string b = "[...document.querySelectorAll('button')].find(b=>b.textContent===" + JsonSerializer.Serialize(text) + "&&!b.disabled)";
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            try { while (await core.ExecuteScriptAsync("Boolean(" + b + ")") != "true") await Task.Delay(100, timeout.Token); }
            catch (OperationCanceledException) { Console.WriteLine("FNEXT8_CLICK_TIMEOUT_" + text); throw; }
            await core.ExecuteScriptAsync(b + ".click()");
        }
        if (!uiOnly) {
        var q = new FinanceHistoryQuery("", "", "", "", "", "", "", "", 2);
        var records = new List<FinanceHistoryItem>(); string cursor = "";
        do { var page = await client.ListFinanceHistoryAsync(q with { Cursor = cursor }, CancellationToken.None); records.AddRange(page.Records); cursor = page.NextCursor ?? ""; } while (cursor != "");
        Assert.Equal(bob ? new[] { "201", "16" } : new[] { "108", "107", "103", "102", "101", "22", "21", "17", "15", "14", "13", "12", "11" }, records.Select(p => p.LogicalTransactionId));
        // Ties use descending logical ID, independent of physical correction creation times.
        await core.ExecuteScriptAsync("location.hash='#/finance'"); await Click("Investment History · 投资交易历史");
        await Wait(bob ? "交易 #201" : "交易 #103");
        if (bob) {
            await Click("交易 #201"); await Wait("STANDALONE_REVERSAL"); await Wait("Bob private reversal");
            Assert.DoesNotContain(records, p => p.Account.Id != "8");
            foreach (string id in new[] { "101", "103", "106" }) {
                Assert.Equal(DesktopError.FinanceTransactionNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceHistoryDetailAsync(id, CancellationToken.None))).Error);
                Assert.Equal(DesktopError.FinanceTransactionNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceHistoryTimelineAsync(id, CancellationToken.None))).Error);
            }
            Assert.Equal("999.00", (await client.GetFinancePortfolioAsync(CancellationToken.None)).OpenTotalCost);
            Console.WriteLine("FNEXT8_BOB_REAL_NATIVE_HISTORY_AUDIT_ALICE_FACTS_DENIED_PASS"); return;
        }
        var all = q with { Size = 20 };
        var exact = await client.GetFinanceHistoryDetailAsync("11", CancellationToken.None);
        Assert.Equal("9007199254740994.12345678", exact.OriginalBusinessValues.Quantity); Assert.Null(exact.PostingReceipt); Assert.Null(exact.EffectiveBusinessValues);
        var original = await client.GetFinanceHistoryDetailAsync("101", CancellationToken.None);
        Assert.Equal("UNCHANGED", original.CorrectionStatus); Assert.Equal("2026-10-02T09:00:00.123456Z", original.EffectiveTradeTime);
        Assert.Equal("1.00000000", original.PostingReceipt!.PositionQuantityAfter); Assert.Equal("3.00000000", original.CurrentPosition.Quantity);
        var reversed = await client.GetFinanceHistoryDetailAsync("102", CancellationToken.None);
        Assert.Equal("REVERSED", reversed.CorrectionStatus); Assert.False(reversed.Effective); Assert.Null(reversed.EffectiveBusinessValues);
        Assert.Equal("104", reversed.Correction!.ReversalTransactionId); Assert.Null(reversed.Correction.ReplacementTransactionId);
        Assert.Equal("3.00000000", reversed.PostingReceipt!.PositionQuantityAfter); Assert.Equal("2.00000000", reversed.CorrectionFinalReceipt!.PositionQuantityAfter);
        var replaced = await client.GetFinanceHistoryDetailAsync("103", CancellationToken.None);
        Assert.Equal("REPLACED", replaced.CorrectionStatus); Assert.True(replaced.Effective);
        Assert.Equal("1.00000000", replaced.OriginalBusinessValues.Quantity); Assert.Equal("3.00000000", replaced.EffectiveBusinessValues!.Quantity);
        Assert.Equal("-40.00", replaced.PostingReceipt!.AccountBalanceAfter); Assert.Equal("-44.00", replaced.CorrectionFinalReceipt!.AccountBalanceAfter);
        Assert.Equal("33.00", replaced.CurrentPosition.TotalCost); Assert.Equal("105", replaced.Correction!.ReversalTransactionId); Assert.Equal("106", replaced.Correction.ReplacementTransactionId);
        var audit = await client.GetFinanceHistoryTimelineAsync("103", CancellationToken.None);
        Assert.Equal(new[] { "ORIGINAL_POSTING", "REPLACEMENT_COMMAND" }, audit.Events.Select(e => e.EventKind));
        Assert.Null(audit.Events[1].PhysicalFactId); Assert.Equal(new[] { "GROUPED_REVERSAL", "REPLACEMENT_FACT" }, audit.Events[1].Facts.Select(f => f.Role));
        Assert.Equal(new[] { "105", "106" }, audit.Events[1].Facts.Select(f => f.PhysicalFactId)); Assert.Equal("NOT_APPLICABLE", audit.Events[1].Facts[0].ReceiptApplicability);
        Assert.Equal("11.00", (await client.GetFinanceHistoryDetailAsync("107", CancellationToken.None)).OriginalBusinessValues.ReleasedCostAmount);
        var dividend = await client.GetFinanceHistoryDetailAsync("108", CancellationToken.None); Assert.Null(dividend.OriginalBusinessValues.Quantity); Assert.Null(dividend.OriginalBusinessValues.UnitPrice); Assert.Equal("0.00", dividend.OriginalBusinessValues.RealizedProfitLoss);
        var range = await client.ListFinanceHistoryAsync(all with { From = "2026-10-02T09:00:00.123456Z", To = "2026-10-03T09:00:00Z" }, CancellationToken.None);
        Assert.Equal("101", Assert.Single(range.Records).LogicalTransactionId);
        foreach (var filter in new[] { all with { PositionId = "18" }, all with { AccountId = "6", InstrumentId = "17" } })
            Assert.Equal(5, (await client.ListFinanceHistoryAsync(filter, CancellationToken.None)).Records.Length);
        foreach (string status in new[] { "UNCHANGED", "REVERSED", "REPLACED" })
            Assert.All((await client.ListFinanceHistoryAsync(all with { CorrectionStatus = status }, CancellationToken.None)).Records, p => Assert.Equal(status, p.CorrectionStatus));
        Assert.Equal("108", Assert.Single((await client.ListFinanceHistoryAsync(all with { Type = "DIVIDEND" }, CancellationToken.None)).Records).LogicalTransactionId);
        Console.WriteLine("FNEXT8_REAL_SOURCE_CURSOR_TIE_ORDER_FILTERS_UTC_INCLUSIVE_EXCLUSIVE_EXACT_DETAIL_FACTS_RECEIPTS_AUDIT_PASS");

        using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
        async Task Check(string suffix, HttpStatusCode expected, bool auth = true, HttpMethod? method = null) {
            using var request = new HttpRequestMessage(method ?? HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/investment/transactions" + suffix);
            if (auth) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(expected, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
        }
        var first = await client.ListFinanceHistoryAsync(q, CancellationToken.None);
        foreach (string suffix in new[] { "/201", "/201/audit-timeline", "/104", "/105/audit-timeline", "/106", "?positionId=16", "?accountId=8", "?instrumentId=15", "/9223372036854775807" }) await Check(suffix, HttpStatusCode.NotFound);
        foreach (string suffix in new[] { "?userId=2", "?type=REVERSAL", "?correctionStatus=DELETED", "?size=21", "?size=2&size=3", "?cursor=", "?from=2026-10-02", "?from=2026-10-03T00:00:00Z&to=2026-10-03T00:00:00Z", "/103?userId=2", "/103/audit-timeline?userId=2", "?type=BUY&cursor=" + first.NextCursor }) await Check(suffix, HttpStatusCode.BadRequest);
        string raw = Encoding.UTF8.GetString(Convert.FromBase64String(first.NextCursor!.Replace('-', '+').Replace('_', '/').PadRight((first.NextCursor.Length + 3) / 4 * 4, '=')));
        foreach (string bad in new[] { raw.Replace("\"version\":1", "\"version\":1.5"), raw.Replace("\"version\":1", "\"version\":1,\"version\":1"), raw + "{}" })
            await Check("?cursor=" + Convert.ToBase64String(Encoding.UTF8.GetBytes(bad)).TrimEnd('=').Replace('+', '-').Replace('/', '_'), HttpStatusCode.BadRequest);
        foreach (string suffix in new[] { "", "/103", "/103/audit-timeline" }) {
            await Check(suffix, HttpStatusCode.Unauthorized, false);
            foreach (var method in new[] { HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete, HttpMethod.Head, HttpMethod.Options }) await Check(suffix, HttpStatusCode.MethodNotAllowed, method: method);
        }
        Console.WriteLine("FNEXT8_REAL_ALICE_BOB_ISOLATION_PHYSICAL_IDS_DENIED_AUTH_METHODS_CURSOR_FINGERPRINT_NO_STORE_PASS");
        } else { await core.ExecuteScriptAsync("location.hash='#/finance'"); await Click("Investment History · 投资交易历史"); await Wait("交易 #103"); }
        await Click("交易 #103"); await Wait("REPLACEMENT_COMMAND"); await Wait("GROUPED_REVERSAL"); await Wait("Synthetic original <script>"); await Wait("-44.00 CNY");
        await Click("刷新投资历史"); await Wait("REPLACEMENT_COMMAND");
        foreach (int width in new[] { 1280, 800 }) {
            app.Workspace.Width = width; await Task.Delay(300);
            Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            await using var png = File.Create(Path.Combine(Environment.CurrentDirectory, ".verification", "fnext8-history-" + width + ".png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, png);
        }
        await Click("Investment Positions · 投资持仓"); await Wait("合成审计持仓"); await Click("合成审计持仓 · #18"); await Click("查看此持仓投资历史"); await Wait("本页 5 笔逻辑交易");
        await Click("清除历史筛选"); await Wait("交易 #103");
        await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('select[aria-label=\"每页投资历史数\"]');e.value='2';e.dispatchEvent(new Event('change',{bubbles:true}));})()");
        await Click("应用历史筛选"); await Wait("本页 2 笔逻辑交易"); await Click("下一页"); await Wait("第 2 页"); await Click("上一页"); await Wait("第 1 页");
        await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('input[aria-label=\"历史 Position ID\"]');const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;setter.call(e,'16');e.dispatchEvent(new Event('input',{bubbles:true}));})()");
        await Click("应用历史筛选"); await Wait("不存在或无权访问"); await Click("清除历史筛选"); await Wait("本页 10 笔逻辑交易");
        Console.WriteLine("FNEXT8_REAL_WEBVIEW_HISTORY_DETAIL_GROUPED_TIMELINE_POSITION_NAV_REFRESH_PAGING_ERROR_RETRY_RESPONSIVE_PASS");
        await Click("Portfolio Summary · 投资组合统计"); await Wait("9007199254741044.42 CNY"); await Wait("2 / 6");
        Assert.Equal("-2.00", (await client.GetFinancePortfolioAsync(CancellationToken.None)).CumulativeRealizedProfitLoss);
        Assert.Equal("9007199254740961.30", (await client.GetFinanceOverviewAsync(CancellationToken.None)).CnyBalance!.Balance);
        Assert.NotEmpty((await client.ListFinanceTransactionsAsync(new(0, 10, "", "", "", "ALL"), CancellationToken.None)).Items);
        Assert.Equal("UNSUPPORTED", (await client.GetFinanceBudgetsAsync(CancellationToken.None)).State);
        Assert.Equal("9007199254740993.100000000000", (await client.GetFinanceAssetAsync("1", CancellationToken.None)).ManualMarketValue);
        string binding = Path.Combine(launch.Root, "data", "finance-identity", "binding.json"); var hash = SHA256.HashData(await File.ReadAllBytesAsync(binding));
        await Click("Investment History · 投资交易历史"); await Wait("交易 #103"); await Click("交易 #103"); await Wait("REPLACEMENT_COMMAND");
        using var old = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
        await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(50))) while (!app.DevelopmentRuntime.Snapshot().CanQueryStorage || app.DevelopmentRuntime.OwnedProcessId == old.Id) await Task.Delay(100, timeout.Token);
        Assert.True(old.WaitForExit(10000)); await Wait("交易 #103"); await Click("交易 #103"); await Wait("REPLACEMENT_COMMAND");
        Assert.Equal(hash, SHA256.HashData(await File.ReadAllBytesAsync(binding)));
        Assert.Equal("REPLACED", (await client.GetFinanceHistoryDetailAsync("103", CancellationToken.None)).CorrectionStatus);
        Console.WriteLine("FNEXT8_REAL_PORTFOLIO_CORE_FINANCE_REGRESSION_RUNTIME_RESTART_DURABLE_HISTORY_BINDING_UNCHANGED_PASS");
        await mutate("identity-change");
        try {
            await Click("刷新投资历史"); await Wait("数据集已改变");
            Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('.finance-page').innerText.includes('REPLACEMENT_COMMAND')&&!document.querySelector('.finance-page').innerText.includes('投资交易详情 · #103')"));
        } finally { await mutate("identity-restore"); }
        await Click("刷新投资历史"); await Wait("交易 #103"); await Click("交易 #103"); await Wait("REPLACEMENT_COMMAND");
        Console.WriteLine("FNEXT8_REAL_IDENTITY_CHANGE_CLEARS_HISTORY_DETAIL_AUDIT_RESTORED_READ_PASS");
    }
#endif
}
