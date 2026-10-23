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
    // Read-only reuse of the already confirmed F-next-1 synthetic Profile. No fixture mutations.
    private static void VerifyFinanceNextGui(AssistantApp app, Mmf3AcceptanceLaunch launch)
    {
        bool passed = false; string phase = "STARTUP";
        app.Startup += async (_, _) =>
        {
            try
            {
                async Task Wait(Func<Task<bool>> predicate)
                {
                    var watch = Stopwatch.StartNew();
                    while (!await predicate()) { Assert.True(watch.Elapsed < TimeSpan.FromSeconds(70), phase); await Task.Delay(100); }
                }
                await Wait(() => Task.FromResult(app.Workspace?.Host.IsReady is true && app.DevelopmentRuntime!.Snapshot().CanQueryStorage));
                var core = app.Workspace!.Browser.CoreWebView2;
                using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime!.ReadCredential);
                string binding = Path.Combine(launch.Root, "data", "finance-identity", "binding.json");
                string hash = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(binding)));
                async Task Click(string label)
                {
                    string button = "[...document.querySelectorAll('.finance-page button')].find(b=>b.textContent===" + JsonSerializer.Serialize(label) + "&&!b.disabled)";
                    await Wait(async () => await core.ExecuteScriptAsync("Boolean(" + button + ")") == "true");
                    await core.ExecuteScriptAsync(button + ".click()");
                }
                async Task Contains(string text) => await Wait(async () => await core.ExecuteScriptAsync(
                    "Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))") == "true");
                async Task Select(string label, string value)
                {
                    await core.ExecuteScriptAsync("(()=>{const e=document.querySelector('select[aria-label=" + JsonSerializer.Serialize(label) + "]');e.value="
                        + JsonSerializer.Serialize(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}));})()");
                    await Wait(async () => await core.ExecuteScriptAsync("document.querySelector('.finance-page').getAttribute('aria-busy')==='false'") == "true");
                }
                phase = "NAVIGATION_OVERVIEW";
                await core.ExecuteScriptAsync("document.querySelector('a[href=\"#/finance\"]').click()");
                var initial = await client.GetFinanceOverviewAsync(CancellationToken.None);
                Console.WriteLine("FNEXT2_INITIAL_RUNTIME_" + initial.State);
                await Contains("9007199254740993.30 CNY");
                await Contains("Synthetic Alice");
                var overview = await client.GetFinanceOverviewAsync(CancellationToken.None);
                Assert.Equal("3", overview.AccountCount);
                Assert.Equal("9007199254740993.30", overview.CnyBalance!.Balance);
                phase = "PAGING_FILTER_SORT_DETAIL";
                await Select("每页账户数", "2");
                await Contains("第 1 页 · 共 3 个账户");
                await Click("下一页"); await Contains("第 2 页 · 共 3 个账户"); await Contains("999.99 USD");
                await Click("USD excluded"); await Contains("该币种未折算为 CNY");
                await Select("账户状态", "INACTIVE"); await Contains("共 1 个账户");
                await Click("合成零钱"); await Contains("服务端账户 ID");
                Assert.Equal("true", await core.ExecuteScriptAsync("document.querySelector('.finance-detail').innerText.includes('0.20 CNY')"));
                await Select("账户状态", "ALL"); await Select("账户排序", "ID_DESC");
                Assert.Equal("true", await core.ExecuteScriptAsync("document.querySelector('.finance-account-link').textContent==='USD excluded'"));
                await Select("账户排序", "NAME_ASC");
                var byName = await client.ListFinanceAccountsAsync(0, 2, "ALL", "NAME_ASC", CancellationToken.None);
                Assert.Equal("true", await core.ExecuteScriptAsync("JSON.stringify([...document.querySelectorAll('.finance-account-link')].map(x=>x.textContent))===" + "JSON.stringify(" + JsonSerializer.Serialize(byName.Items.Select(x => x.Name)) + ")"));
                await Select("账户排序", "ID_ASC"); await Click("合成 CNY 账户"); await Contains("9007199254740993.10 CNY");
                var first = await client.ListFinanceAccountsAsync(0, 2, "ALL", "ID_ASC", CancellationToken.None);
                var second = await client.ListFinanceAccountsAsync(1, 2, "ALL", "ID_ASC", CancellationToken.None);
                Assert.Equal(new[] { "1", "2", "4" }, first.Items.Concat(second.Items).Select(x => x.Id));
                Assert.Equal("9007199254740993.10", (await client.GetFinanceAccountAsync("1", CancellationToken.None)).Balance);
                Console.WriteLine("FNEXT2_REAL_UI_OVERVIEW_PAGING_SORT_FILTER_DETAIL_EXACT_AMOUNTS_PASS");

                phase = "HTTP_BOUNDARIES";
                using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
                async Task Check(string path, HttpStatusCode expected)
                {
                    using var request = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/accounts" + path);
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime.ReadCredential());
                    using var response = await http.SendAsync(request);
                    Assert.Equal(expected, response.StatusCode);
                    Assert.True(response.Headers.CacheControl?.NoStore);
                }
                await Check("/3", HttpStatusCode.NotFound); // Bob's account.
                await Check("/9223372036854775807", HttpStatusCode.NotFound);
                foreach (string path in new[] { "/0", "/bad", "/9223372036854775808", "?page=-1", "?size=21", "?sort=id%3B--", "?status=DELETED", "?userId=2", "?page=0&page=1" })
                    await Check(path, HttpStatusCode.BadRequest);
                Assert.Empty((await client.ListFinanceAccountsAsync(100, 2, "ACTIVE", "ID_ASC", CancellationToken.None)).Items);
                var denied = await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceAccountAsync("3", CancellationToken.None));
                Assert.Equal(DesktopError.FinanceAccountNotFound, denied.Error);
                Console.WriteLine("FNEXT2_REAL_HTTP_USER_SCOPE_PARAMETER_LIMITS_AND_EMPTY_PAGE_PASS");

                phase = "BUDGET_AVAILABILITY";
                await core.ExecuteScriptAsync("[...document.querySelectorAll('button')].find(b=>b.textContent==='Budgets · 预算').click()");
                await Contains("这不表示当前用户没有预算");
                Assert.Equal("UNSUPPORTED", (await client.GetFinanceBudgetsAsync(CancellationToken.None)).State);
                foreach (string suffix in new[] { "?userId=2", "?page=0", "?period=MONTHLY", "?categoryId=1" })
                {
                    using var request = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:18765/api/v1/finance/budgets" + suffix);
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime.ReadCredential());
                    using var response = await http.SendAsync(request);
                    Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode); Assert.True(response.Headers.CacheControl?.NoStore);
                }
                using (var response = await http.GetAsync("http://127.0.0.1:18765/api/v1/finance/budgets"))
                    Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
                Assert.Equal("true", await core.ExecuteScriptAsync("!document.querySelector('.finance-page table')&&!document.querySelector('.finance-page .finance-amount')"));
                Console.WriteLine("FNEXT4_REAL_BUDGET_UNSUPPORTED_DISTINCT_FROM_EMPTY_NO_INVENTED_FINANCIAL_FACTS_AND_NO_CLIENT_USERID_PASS");

                phase = "LAYOUT";
                foreach (int width in new[] { 1280, 800 })
                {
                    app.Workspace.Width = width; app.Workspace.Height = 850;
                    await Task.Delay(250);
                    Assert.Equal("true", await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
                    using var image = new FileStream(Path.Combine(Environment.CurrentDirectory, ".verification", $"fnext4-{width}.png"), FileMode.Create);
                    await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                }
                phase = "RESTART_AND_EXISTING_STORES";
                using var old = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
                await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
                await Wait(() => Task.FromResult(app.DevelopmentRuntime.Snapshot().CanQueryStorage && app.DevelopmentRuntime.OwnedProcessId != old.Id));
                Assert.True(old.WaitForExit(10000));
                await Click("刷新预算"); await Contains("这不表示当前用户没有预算");
                Assert.Equal("UNSUPPORTED", (await client.GetFinanceBudgetsAsync(CancellationToken.None)).State);
                Console.WriteLine("FNEXT4_BUDGET_QUERY_AFTER_REAL_RUNTIME_RESTART_PASS");
                await core.ExecuteScriptAsync("[...document.querySelectorAll('button')].find(b=>b.textContent==='Transactions · 交易流水').click()");
                await Contains("9007199254740993.10 CNY");
                await Click("2026-10-23 12:00"); await Contains("F-next-3 expense");
                Assert.Equal("9007199254740993.10", (await client.GetFinanceTransactionAsync("TRANSACTION", "1", CancellationToken.None)).Amount);
                var hidden = await Assert.ThrowsAsync<DesktopException>(() => client.GetFinanceTransactionAsync("TRANSACTION", "3", CancellationToken.None));
                Assert.Equal(DesktopError.FinanceTransactionNotFound, hidden.Error);
                Console.WriteLine("FNEXT4_EXISTING_TRANSACTION_LIST_DETAIL_PRECISION_AND_OTHER_USER_DENIAL_PASS");
                var saved = JsonSerializer.Deserialize<FinanceStoreEvidence>(await File.ReadAllTextAsync(Path.Combine(launch.Root, "evidence", "finance-stores.json")))!;
                Assert.Equal(saved.BindingHash, hash);
                Assert.Equal(hash, Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(binding))));
                Assert.Equal(SyntheticMemory, (await client.GetMemoryAsync(saved.Memory, CancellationToken.None)).Content);
                Assert.Equal("F-next-1 persisted conversation", (await client.GetConversationAsync(saved.Conversation, 0, 10, CancellationToken.None)).Conversation.Title);
                Assert.Single((await client.SearchKnowledgeAsync("amber lighthouse", 10, CancellationToken.None)).Hits);
                Assert.Equal("READY", (await client.GetKnowledgeAsync(saved.Document, CancellationToken.None)).Document.ProcessingState);
                Console.WriteLine("FNEXT2_LAYOUT_RUNTIME_RESTART_BINDING_AND_EXISTING_WORKSPACE_STORES_PASS");
                passed = true;
            }
            catch (Exception error)
            {
                Console.WriteLine("FNEXT2_GUI_STOP_" + phase + "_" + (error is DesktopException desktop ? desktop.Error.ToString() : error.GetType().Name));
                if (app.Workspace?.Browser.CoreWebView2 is {} browser)
                {
                    Console.WriteLine(await browser.ExecuteScriptAsync("JSON.stringify({route:location.hash,finance:document.querySelector('.finance-page')?.innerText})"));
                    using var image = new FileStream(Path.Combine(Environment.CurrentDirectory, ".verification", "fnext2-failure.png"), FileMode.Create);
                    await browser.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                }
            }
            finally { try { await app.ExitAsync(); } catch { passed = false; app.Shutdown(10); } }
        };
        app.Run(); Assert.True(passed);
    }
}
#endif
