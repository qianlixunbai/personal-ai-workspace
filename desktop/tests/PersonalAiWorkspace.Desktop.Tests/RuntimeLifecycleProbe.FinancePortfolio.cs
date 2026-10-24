#if RUNTIME_LIFECYCLE_PROBE && MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private static async Task VerifyFinancePortfolio(AssistantApp app, RuntimeClient client, Mmf3AcceptanceLaunch launch, bool bob)
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task Contains(string text) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            while (await core.ExecuteScriptAsync("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))") != "true") await Task.Delay(100, timeout.Token);
        }
        async Task Click(string text) {
            string button = "[...document.querySelectorAll('button')].find(b=>b.textContent===" + JsonSerializer.Serialize(text) + "&&!b.disabled)";
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            while (await core.ExecuteScriptAsync("Boolean(" + button + ")") != "true") await Task.Delay(100, timeout.Token);
            await core.ExecuteScriptAsync(button + ".click()");
        }
        await core.ExecuteScriptAsync("location.hash='#/finance'");
        await Click("Portfolio Summary · 投资组合统计");
        var p = await client.GetFinancePortfolioAsync(CancellationToken.None);
        Assert.Equal(bob ? "999.00" : "9007199254741011.42", p.OpenTotalCost);
        Assert.Equal(bob ? "0.00" : "-2.00", p.CumulativeRealizedProfitLoss);
        Assert.Equal(bob ? 1 : 6, p.PositionCount); Assert.Equal(bob ? 1 : 5, p.OpenPositionCount); Assert.Equal(bob ? 0 : 1, p.ClosedPositionCount);
        Assert.Equal(bob ? "699.30" : "6305039478318695.21", p.ReferenceValuation.Value);
        Assert.Equal(bob ? "STALE" : "PARTIAL", p.ReferenceValuation.Freshness);
        Assert.Equal(bob ? 1 : 2, p.ReferenceValuation.ValuedPositionCount); Assert.Equal(p.OpenPositionCount, p.ReferenceValuation.TotalOpenPositionCount);
        Assert.Contains(p.ReferenceValuation.Warnings, w => w.Code == "QUOTE_STALE"); Assert.Contains(p.ReferenceValuation.Warnings, w => w.Code == "FX_STALE");
        await Contains(p.OpenTotalCost + " CNY"); await Contains(p.ReferenceValuation.Value + " CNY");
        await Contains(bob ? "1 / 1" : "2 / 5"); await Contains(bob ? "STALE" : "不能作为完整组合总市值");
        if (bob) {
            Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('.finance-page').innerText.includes('9007199254741011.42')"));
            Console.WriteLine("FNEXT7_BOB_REAL_NATIVE_BOUND_PORTFOLIO_STALE_ALICE_TOTAL_ABSENT_PASS"); return;
        }
        Assert.Contains(p.ReferenceValuation.Warnings, w => w.Code == "FX_MISSING"); Assert.Contains(p.ReferenceValuation.Warnings, w => w.Code == "QUOTE_MISSING");
        // UI filtering and source Cursor pages cannot restrict the separate unfiltered portfolio query.
        await Click("Investment Positions · 投资持仓");
        await Contains("本页 5 项持仓");
        await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('select[aria-label=\"每页持仓数\"]');e.value='2';e.dispatchEvent(new Event('change',{bubbles:true}));})()");
        await Click("下一页"); await Contains("第 2 页");
        await Click("Portfolio Summary · 投资组合统计"); await Contains("9007199254741011.42 CNY"); await Contains("2 / 5");
        Console.WriteLine("FNEXT7_ALICE_REAL_SOURCE_FULL_SCOPE_CNY_COST_REALIZED_EXACT_PARTIAL_COVERAGE_WARNINGS_PASS");

        using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
        async Task Check(string suffix, HttpStatusCode expected, bool authenticated = true, HttpMethod? method = null) {
            using var request = new HttpRequestMessage(method ?? HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/investment/portfolio" + suffix);
            if (authenticated) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(expected, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
        }
        await Check("", HttpStatusCode.Unauthorized, false);
        foreach (string suffix in new[] { "?userId=2", "?status=OPEN", "?size=2", "?cursor=abc", "?accountId=6", "?instrumentId=11", "?x=", "?userId=1&userId=2" }) await Check(suffix, HttpStatusCode.BadRequest);
        foreach (var method in new[] { HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete, HttpMethod.Head, HttpMethod.Options }) await Check("", HttpStatusCode.MethodNotAllowed, method: method);
        Console.WriteLine("FNEXT7_PORTFOLIO_FIXED_GET_NO_PARAMETERS_NO_STORE_AUTH_WRITE_DENIAL_PASS");

        using var target = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(Environment.CurrentDirectory, ".verification", "fnext7-target.json")));
        string fixture = target.RootElement.GetProperty("root").GetString()!;
        async Task Identity(string action) {
            byte[] bytes = ProtectedData.Unprotect(await File.ReadAllBytesAsync(Path.Combine(fixture, "fixture.dpapi")), null, DataProtectionScope.CurrentUser);
            try {
                using var sources = TrustedDevelopmentRuntimeSources.Open();
                var start = new ProcessStartInfo(sources.Java) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
                foreach (string arg in new[] { "-cp", Path.Combine(fixture, "tooling", "classes") + ";" + Path.Combine(fixture, "tooling", "lib", "*"), "FinanceNextPreparation", action }) start.ArgumentList.Add(arg);
                using var process = Process.Start(start)!; await process.StandardInput.BaseStream.WriteAsync(bytes); process.StandardInput.Close();
                string result = await process.StandardOutput.ReadToEndAsync(), errors = await process.StandardError.ReadToEndAsync();
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(30)); Assert.Equal(0, process.ExitCode); Assert.Empty(errors); Assert.Equal("FNEXT_FIXTURE_PASS_" + action, result.Trim());
            } finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        string binding = Path.Combine(launch.Root, "data", "finance-identity", "binding.json");
        byte[] hash = SHA256.HashData(await File.ReadAllBytesAsync(binding));
        foreach (string action in new[] { "identity-change", "swap" }) {
            await Identity(action);
            try {
                await Check("", HttpStatusCode.Forbidden);
                await Click("刷新组合统计"); await Contains("组合统计读取已拒绝");
                Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('[aria-label=\"组合账务统计\"]')&&!document.querySelector('[aria-label=\"组合缓存市场参考估值\"]')"));
            } finally { await Identity(action == "swap" ? "unswap" : "identity-restore"); }
            await Click("刷新组合统计"); await Contains("9007199254741011.42 CNY");
        }
        foreach (int width in new[] { 1280, 800 }) {
            app.Workspace.Width = width; app.Workspace.Height = 900; await Task.Delay(250);
            await core.ExecuteScriptAsync("window.scrollTo(0,0)");
            Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            using var picture = File.Create(Path.Combine(Environment.CurrentDirectory, ".verification", $"fnext7-{width}.png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, picture);
        }
        using var old = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
        await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(70)))
            while (!app.DevelopmentRuntime.Snapshot().CanQueryStorage || app.DevelopmentRuntime.OwnedProcessId == old.Id) {
                Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('[aria-label=\"组合账务统计\"]')"));
                await Task.Delay(100, timeout.Token);
            }
        Assert.True(old.WaitForExit(10000)); await Contains("9007199254741011.42 CNY"); await Contains("2 / 5");
        Assert.Equal(hash, SHA256.HashData(await File.ReadAllBytesAsync(binding)));
        Console.WriteLine("FNEXT7_REAL_USER_DATASET_CHANGE_CLEAR_RETRY_RUNTIME_RESTART_RECHECK_NO_REBIND_1280_800_PASS");
    }
}
#endif
