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
    // One integrated Assets flow, on a NEW opt-in synthetic fixture and NEW confirmed Profile.
    private static async Task VerifyFinanceAssets(AssistantApp app, RuntimeClient client, Mmf3AcceptanceLaunch launch)
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task WaitScript(string script) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
            try { while (await core.ExecuteScriptAsync(script) != "true") await Task.Delay(100, timeout.Token); }
            catch (OperationCanceledException) {
                Console.WriteLine("FNEXT5_WAIT_TIMEOUT_" + script);
                Console.WriteLine(await core.ExecuteScriptAsync("document.querySelector('.finance-page')?.innerText"));
                throw;
            }
        }
        async Task Click(string text) {
            string button = "[...document.querySelectorAll('button')].find(b=>b.textContent===" + JsonSerializer.Serialize(text) + "&&!b.disabled)";
            await WaitScript("Boolean(" + button + ")"); await core.ExecuteScriptAsync(button + ".click()");
        }
        async Task Contains(string text) => await WaitScript("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))");
        async Task Select(string label, string value) {
            await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('select[aria-label=" + JsonSerializer.Serialize(label) + "]');e.value=" + JsonSerializer.Serialize(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}));})()");
            await WaitScript("document.querySelector('.finance-page').getAttribute('aria-busy')==='false'");
        }
        await Click("Assets · 资产"); await Contains("共 5 项资产");
        var query = new FinanceAssetQuery(0, 2, "ALL", "CREATED_DESC");
        var first = await client.ListFinanceAssetsAsync(query, CancellationToken.None);
        var second = await client.ListFinanceAssetsAsync(query with { Page = 1 }, CancellationToken.None);
        Assert.Equal(new[] { "5", "4", "3", "2" }, first.Items.Concat(second.Items).Select(x => x.Id));
        var exact = await client.GetFinanceAssetAsync("1", CancellationToken.None);
        Assert.Equal("9007199254740993.100000000000", exact.ManualMarketValue); // Ignores stale persisted market_value=123.45.
        Assert.Equal("CNY", exact.Currency); Assert.Equal("USD", exact.ReferenceValuation!.QuoteCurrency);
        Assert.Equal("900719925474099.3100000000000000", exact.ReferenceValuation.NativeMarketValue);
        Assert.Equal("6305039478318695.17", exact.ReferenceValuation.BaseCurrencyMarketValue);
        Assert.Equal("SYNTHETIC_FIXED", exact.ReferenceValuation.QuoteProvider);
        Assert.Equal("STALE", exact.ReferenceValuation.ValuationFreshness);
        Assert.Equal("2026-10-01T08:00:00Z", exact.ReferenceValuation.QuoteTime);
        var partial = await client.GetFinanceAssetAsync("2", CancellationToken.None);
        Assert.Null(partial.CurrentPrice); Assert.Null(partial.ManualMarketValue);
        Assert.Equal("PARTIAL", partial.ReferenceValuation!.ValuationFreshness);
        Assert.Null(partial.ReferenceValuation.BaseCurrencyMarketValue); Assert.Contains("FX_MISSING", partial.ReferenceValuation.Warnings);
        var unsupported = await client.GetFinanceAssetAsync("3", CancellationToken.None);
        Assert.Null(unsupported.CurrentPrice); Assert.Null(unsupported.ManualMarketValue); Assert.Null(unsupported.ReferenceValuation);
        var missing = await client.GetFinanceAssetAsync("4", CancellationToken.None);
        Assert.Equal("0.000000000000", missing.ManualMarketValue); Assert.Null(missing.Symbol);
        Assert.Equal("UNAVAILABLE", missing.ReferenceValuation!.ValuationFreshness); Assert.Null(missing.ReferenceValuation.BaseCurrencyMarketValue);
        var cny = await client.GetFinanceAssetAsync("5", CancellationToken.None);
        Assert.Equal("0.0152415765279684", cny.ReferenceValuation!.NativeMarketValue);
        Assert.Equal("0.02", cny.ReferenceValuation.BaseCurrencyMarketValue); Assert.Equal("SYSTEM_IDENTITY", cny.ReferenceValuation.FxProvider);
        Console.WriteLine("FNEXT5_REAL_POSTGRESQL_EXACT_MANUAL_AND_REUSED_SOURCE_FX_VALUATION_NULL_PARTIAL_UNAVAILABLE_ROUNDING_PASS");

        await Select("每页资产数", "2"); await Click("下一页"); await Contains("第 2 页 · 共 5 项资产");
        await Click("合成缺汇率资产"); await Contains("缺少换算汇率"); await Contains("人工参考单价");
        await Select("资产类型", "BOND"); await Contains("当前筛选下没有资产");
        await Select("资产类型", "STOCK"); await Contains("共 2 项资产"); await Select("资产排序", "ID_ASC");
        Assert.Equal("true", await core.ExecuteScriptAsync("document.querySelector('.finance-account-link').textContent==='合成精确资产'"));
        await Click("合成精确资产"); await Contains("6305039478318695.17 CNY"); await Contains("SYNTHETIC_FIXED"); await Contains("缓存已过期");
        await Click("刷新资产"); await Contains("资产详情 · 合成精确资产");
        Console.WriteLine("FNEXT5_REAL_WEBVIEW_TYPED_BRIDGE_ASSET_PAGING_SORT_FILTER_EMPTY_DETAIL_REFRESH_PASS");

        using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
        async Task Check(string suffix, HttpStatusCode status, bool authenticated = true) {
            using var request = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/assets" + suffix);
            if (authenticated) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(status, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
        }
        await Check("/6", HttpStatusCode.NotFound); await Check("/9223372036854775807", HttpStatusCode.NotFound);
        await Check("", HttpStatusCode.Unauthorized, false);
        foreach (string suffix in new[] { "?userId=2", "?page=0&page=1", "?size=21", "?type=EQUITY", "?sort=price%3B--", "/1?userId=2", "/0" }) await Check(suffix, HttpStatusCode.BadRequest);
        Assert.Equal(DesktopError.FinanceAssetNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceAssetAsync("6", CancellationToken.None))).Error);
        using (var request = new HttpRequestMessage(HttpMethod.Post, "http://127.0.0.1:18765/api/v1/finance/assets")) {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime!.ReadCredential());
            using var response = await http.SendAsync(request); Assert.Equal(HttpStatusCode.MethodNotAllowed, response.StatusCode);
        }
        Console.WriteLine("FNEXT5_REAL_OTHER_USER_ASSET_DENIED_PARAMETERS_AUTHENTICATION_NO_STORE_PASS");

        // Change only the NEW synthetic user's identity, restore in finally; no rebinding or old Profile mutations.
        using var target = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(Environment.CurrentDirectory, ".verification", "fnext5-target.json")));
        string fixture = target.RootElement.GetProperty("root").GetString()!;
        async Task Identity(string action) {
            byte[] bytes = ProtectedData.Unprotect(await File.ReadAllBytesAsync(Path.Combine(fixture, "fixture.dpapi")), null, DataProtectionScope.CurrentUser);
            try {
                using var sources = TrustedDevelopmentRuntimeSources.Open();
                var start = new ProcessStartInfo(sources.Java) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
                foreach (string arg in new[] { "-cp", Path.Combine(fixture, "tooling", "classes") + ";" + Path.Combine(fixture, "tooling", "lib", "*"), "FinanceNextPreparation", action }) start.ArgumentList.Add(arg);
                using var p = Process.Start(start)!; await p.StandardInput.BaseStream.WriteAsync(bytes); p.StandardInput.Close();
                string result = await p.StandardOutput.ReadToEndAsync(); string errors = await p.StandardError.ReadToEndAsync();
                await p.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(30));
                Assert.Equal(0, p.ExitCode); Assert.Equal("FNEXT_FIXTURE_PASS_" + action, result.Trim()); Assert.Empty(errors);
            } finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        string binding = Path.Combine(launch.Root, "data", "finance-identity", "binding.json");
        byte[] hash = SHA256.HashData(await File.ReadAllBytesAsync(binding));
        await Identity("identity-change");
        try {
            await Check("/1", HttpStatusCode.Forbidden); await Check("", HttpStatusCode.Forbidden);
            await Click("刷新资产"); await Contains("资产读取已拒绝");
            Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('.finance-detail')&&!document.querySelector('.finance-page table')"));
        } finally { await Identity("identity-restore"); }
        await Click("清除筛选"); await Contains("共 5 项资产");
        await Click("合成精确资产"); await Contains("6305039478318695.17 CNY");
        Console.WriteLine("FNEXT5_REAL_IDENTITY_CHANGE_CLEARS_ASSETS_AND_DENIES_READ_THEN_SAFE_RETRY_WITHOUT_REBIND_PASS");

        foreach (int width in new[] { 1280, 800 }) {
            app.Workspace.Width = width; app.Workspace.Height = 900; await Task.Delay(250);
            Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            await core.ExecuteScriptAsync("document.querySelector('.finance-detail').scrollIntoView({block:'start'})");
            using var picture = File.Create(Path.Combine(Environment.CurrentDirectory, ".verification", $"fnext5-{width}.png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, picture);
        }
        using var old = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
        Console.WriteLine("FNEXT5_RUNTIME_RESTART_BEGIN");
        await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(70)))
            while (!app.DevelopmentRuntime.Snapshot().CanQueryStorage || app.DevelopmentRuntime.OwnedProcessId == old.Id) await Task.Delay(100, timeout.Token);
        Assert.True(old.WaitForExit(10000));
        Console.WriteLine("FNEXT5_RUNTIME_RESTART_READY");
        await Click("刷新资产"); await Contains("共 5 项资产"); await Click("合成精确资产"); await Contains("6305039478318695.17 CNY");
        Assert.Equal(hash, SHA256.HashData(await File.ReadAllBytesAsync(binding)));
        Assert.Equal("9007199254740993.30", (await client.GetFinanceOverviewAsync(CancellationToken.None)).CnyBalance!.Balance);
        await Click("Accounts · 账户"); await Contains("9007199254740993.30 CNY"); await Click("合成 CNY 账户"); await Contains("服务端账户 ID");
        await Click("Transactions · 交易流水"); await Contains("共 6 笔交易"); await Click("2026-10-23 12:00"); await Contains("F-next-3 expense");
        Assert.Equal("9007199254740993.10", (await client.GetFinanceTransactionAsync("TRANSACTION", "1", CancellationToken.None)).Amount);
        Assert.Equal(DesktopError.FinanceTransactionNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceTransactionAsync("TRANSACTION", "3", CancellationToken.None))).Error);
        Console.WriteLine("FNEXT5_REAL_RUNTIME_RESTART_ASSET_RECOVERY_BINDING_ACCOUNTS_TRANSACTIONS_BASIC_REGRESSION_PASS");
    }
}
#endif
