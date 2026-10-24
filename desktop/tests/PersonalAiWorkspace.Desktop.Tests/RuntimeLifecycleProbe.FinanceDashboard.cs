#if RUNTIME_LIFECYCLE_PROBE && MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
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
    private static async Task VerifyFinanceDashboard(AssistantApp app, RuntimeClient client, Mmf3AcceptanceLaunch launch, bool bob, Func<string, Task> mutate)
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task Wait(string expression) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            try { while (await core.ExecuteScriptAsync(expression) != "true") await Task.Delay(100, timeout.Token); }
            catch (OperationCanceledException) {
                Console.WriteLine("FNEXT10_WAIT_TIMEOUT_" + expression);
                Console.WriteLine(await core.ExecuteScriptAsync("document.querySelector('.finance-page')?.innerText")); throw;
            }
        }
        async Task Text(string text) => await Wait("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))");
        async Task Click(string text) {
            string b = "[...document.querySelectorAll('button')].find(b=>b.textContent===" + JsonSerializer.Serialize(text) + "&&!b.disabled)";
            await Wait("Boolean(" + b + ")"); await core.ExecuteScriptAsync(b + ".click()");
        }
        await core.ExecuteScriptAsync("location.hash='#/finance'"); await Click("Dashboard · 财务总览"); await Text("人工参考资产分布");
        var d = await client.GetFinanceDashboardAsync(CancellationToken.None);
        Assert.Equal("Asia/Shanghai", d.BusinessZone); Assert.Equal(6, d.MonthlyCashFlow.Length);
        Assert.Equal(bob ? "88.88" : "9007199254740961.30", d.CnyAccountBalance);
        await Text(d.CnyAccountBalance + " CNY");
        Assert.Equal(bob ? "1" : "24", d.Allocation.Total);
        Assert.Equal(bob, d.Allocation.Complete);
        if (bob) {
            Assert.DoesNotContain(d.RecentTransactions, t => t.Id == "307");
            Assert.Equal("88.89", d.MonthIncome);
            Console.WriteLine("FNEXT10_BOB_REAL_NATIVE_BINDING_DASHBOARD_ISOLATION_PASS"); return;
        }
        Assert.Equal("9007199254741170.38", d.MonthIncome); // Fixed noon boundary row is now in the past; Nov remains excluded.
        Assert.Equal("1.45", d.MonthExpense); Assert.Equal("9007199254741168.93", d.MonthNet);
        Assert.Equal(new[] { "307", "306", "305", "304", "303" }, d.RecentTransactions.Select(t => t.Id));
        Assert.Null(d.RecentTransactions[2].CategoryName); Assert.Null(d.RecentTransactions[3].CategoryName);
        await Text("部分视图"); await Text("未知分类"); await Click("刷新 Dashboard"); await Text("人工参考资产分布");
        foreach (int width in new[] { 1280, 800 }) {
            app.Workspace.Width = width; app.Workspace.Height = 850; await Task.Delay(200);
            Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            using var shot = File.Create(Path.Combine(Environment.CurrentDirectory, ".verification", $"fnext10-dashboard-{width}.png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, shot);
        }
        using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
        async Task Check(string suffix, HttpStatusCode expected, bool auth = true, HttpMethod? method = null) {
            using var request = new HttpRequestMessage(method ?? HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/dashboard" + suffix);
            if (auth) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(expected, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
        }
        await Check("", HttpStatusCode.OK);
        foreach (string suffix in new[] { "?userId=2", "?page=0", "?dataset=other", "?x=1&x=2" }) await Check(suffix, HttpStatusCode.BadRequest);
        await Check("", HttpStatusCode.Unauthorized, false);
        foreach (var method in new[] { HttpMethod.Post, HttpMethod.Head, HttpMethod.Options }) await Check("", HttpStatusCode.MethodNotAllowed, true, method);
        Console.WriteLine("FNEXT10_DASHBOARD_UI_AND_HTTP_PASS");
        await mutate("identity-change");
        try {
            await Click("刷新 Dashboard"); await Text("身份或数据集已改变");
            Assert.Equal("false", await core.ExecuteScriptAsync("document.querySelector('.finance-page').innerText.includes('人工参考资产分布')"));
            Assert.Equal(DesktopError.FinanceBindingInvalid, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceDashboardAsync(CancellationToken.None))).Error);
        } finally { await mutate("identity-restore"); }
        await Click("刷新 Dashboard"); await Text("人工参考资产分布");
        Console.WriteLine("FNEXT10_IDENTITY_CLEAR_AND_RECOVERY_PASS");
        using (var old = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value)) {
            await app.DevelopmentRuntime.StopAsync();
            await old.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(20));
            await Wait("!document.querySelector('.finance-page')?.innerText.includes('人工参考资产分布')");
            await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
        }
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60))) while (!app.DevelopmentRuntime.Snapshot().CanQueryStorage) await Task.Delay(100, timeout.Token);
        await Click("刷新 Dashboard"); await Text("人工参考资产分布");
        Assert.Equal(d.CnyAccountBalance, (await client.GetFinanceDashboardAsync(CancellationToken.None)).CnyAccountBalance);
        Assert.NotEmpty((await client.ListFinanceAccountsAsync(0,10,"ALL","ID_ASC",CancellationToken.None)).Items);
        Assert.NotEmpty((await client.ListFinanceTransactionsAsync(new(0,10,"","","","ALL"),CancellationToken.None)).Items);
        await Click("进入 Assets 查看详细数据"); await Text("共 35 项资产");
        Assert.NotEmpty((await client.ListFinancePositionsAsync(new("ALL","","","",10),CancellationToken.None)).Records);
        Assert.Equal("CNY", (await client.GetFinancePortfolioAsync(CancellationToken.None)).Currency);
        Assert.Equal("REPLACED", (await client.GetFinanceHistoryDetailAsync("103",CancellationToken.None)).CorrectionStatus);
        Assert.True((await client.GetFinanceImportReceiptAsync("00000000-0000-0000-0001-000000000101",0,0,CancellationToken.None)).CompleteReceipt);
        Console.WriteLine("FNEXT10_REAL_WPF_WEBVIEW_BRIDGE_SQL_DASHBOARD_REFRESH_PARTIAL_LAYOUT_HTTP_RUNTIME_IDENTITY_CORE_READS_PASS");
    }
}
#endif
