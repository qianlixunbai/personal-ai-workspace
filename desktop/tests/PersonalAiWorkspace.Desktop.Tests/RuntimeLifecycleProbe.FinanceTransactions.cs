#if RUNTIME_LIFECYCLE_PROBE && MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    // One integrated flow on a NEW opt-in synthetic cluster; no writes to accepted Finance data.
    private static async Task VerifyFinanceTransactions(AssistantApp app, RuntimeClient client)
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task WaitScript(string script) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            while (await core.ExecuteScriptAsync(script) != "true") await Task.Delay(100, timeout.Token);
        }
        async Task Click(string text) {
            string button = "[...document.querySelectorAll('button')].find(b=>b.textContent===" + JsonSerializer.Serialize(text) + "&&!b.disabled)";
            await WaitScript("Boolean(" + button + ")"); await core.ExecuteScriptAsync(button + ".click()");
        }
        async Task Contains(string text) => await WaitScript("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))");
        async Task Field(string label, string value) => await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('[aria-label=" + JsonSerializer.Serialize(label) + "]');Object.getOwnPropertyDescriptor(e.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value').set.call(e," + JsonSerializer.Serialize(value) + ");e.dispatchEvent(new Event(e.tagName==='SELECT'?'change':'input',{bubbles:true}));})()");

        await Click("Transactions · 交易流水"); await Contains("共 6 笔交易");
        var query = new FinanceTransactionQuery(0, 2, "", "", "", "ALL");
        var first = await client.ListFinanceTransactionsAsync(query, CancellationToken.None);
        var second = await client.ListFinanceTransactionsAsync(query with { Page = 1 }, CancellationToken.None);
        Assert.Equal(new[] { "TRANSACTION:2", "TRANSACTION:1", "TRANSFER:1", "TRANSACTION:4" }, first.Items.Concat(second.Items).Select(x => x.Kind + ":" + x.Id));
        Assert.Equal("9007199254740993.10", (await client.GetFinanceTransactionAsync("TRANSACTION", "1", CancellationToken.None)).Amount);
        await Field("每页交易数", "2"); await Click("应用筛选"); await Contains("第 1 页 · 共 6 笔交易");
        await Click("下一页"); await Contains("第 2 页 · 共 6 笔交易");
        await Click("2026-10-23 12:00"); await Contains("只读交易"); await Contains("转出 → 转入账户"); await Contains("F-next-3 transfer");
        Assert.Equal("true", await core.ExecuteScriptAsync("document.querySelector('.finance-detail').innerText.includes('0.10 CNY')"));
        await Field("交易账户 ID", "2"); await Field("交易开始日期", "2026-10-23"); await Field("交易结束日期", "2026-10-23");
        await Field("交易类型", "TRANSFER"); await Click("应用筛选"); await Contains("共 1 笔交易");
        Assert.Single((await client.ListFinanceTransactionsAsync(query with { AccountId = "2", StartDate = "2026-10-23", EndDate = "2026-10-23", Type = "TRANSFER" }, CancellationToken.None)).Items);
        await Click("清除筛选"); await Contains("共 6 笔交易");
        await Click("2026-10-22 23:59:59.999999"); await Contains("只读交易"); await Contains("-0.10 CNY"); await Contains("未提供备注");
        await Click("2026-10-21 08:00"); await Contains("12.34 USD"); await Contains("Historical refund");
        await Click("2026-10-20 08:00"); await Contains("历史流水未提供转入账户");
        await Field("交易开始日期", "2026-10-24"); await Click("应用筛选"); await Contains("当前筛选下没有交易");
        await Field("交易结束日期", "2026-10-20"); await Click("应用筛选"); await Contains("交易筛选参数无效");
        await Click("清除筛选"); await Contains("共 6 笔交易");
        Console.WriteLine("FNEXT3_REAL_WINDOWS_LIST_DETAIL_PAGING_FILTERS_PRECISION_TRANSFER_LEGACY_REFUND_EMPTY_RETRY_PASS");

        using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
        async Task Check(string path, HttpStatusCode expected, bool authenticated = true) {
            using var request = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/transactions" + path);
            if (authenticated) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(expected, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
        }
        await Check("/TRANSACTION/3", HttpStatusCode.NotFound); await Check("/TRANSFER/2", HttpStatusCode.NotFound);
        await Check("/TRANSACTION/9223372036854775807", HttpStatusCode.NotFound);
        foreach (string suffix in new[] { "?userId=2", "?accountId=1&userId=2", "?page=0&page=1", "?size=21", "?type=OTHER", "?startDate=2026-02-30", "?startDate=2026-10-24&endDate=2026-10-23", "/TRANSFER/1?userId=2", "/TRANSFER/0", "/TRANSFER/9223372036854775808", "/OTHER/1" })
            await Check(suffix, HttpStatusCode.BadRequest);
        await Check("", HttpStatusCode.Unauthorized, false);
        Assert.Empty((await client.ListFinanceTransactionsAsync(query with { AccountId = "3" }, CancellationToken.None)).Items);
        Assert.Empty((await client.ListFinanceTransactionsAsync(query with { Page = 100 }, CancellationToken.None)).Items);
        foreach (var key in new[] { ("TRANSACTION", "3"), ("TRANSFER", "2") }) {
            var denied = await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceTransactionAsync(key.Item1, key.Item2, CancellationToken.None));
            Assert.Equal(DesktopError.FinanceTransactionNotFound, denied.Error);
        }
        Console.WriteLine("FNEXT3_REAL_HTTP_BOUND_USER_ONLY_NO_CLIENT_USERID_NO_STORE_PASS");
        foreach (int width in new[] { 1280, 800 }) {
            app.Workspace.Width = width; app.Workspace.Height = 850; await Task.Delay(200);
            Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            using var image = File.Create(Path.Combine(Environment.CurrentDirectory, ".verification", $"fnext3-{width}.png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
        }
    }
}
#endif
