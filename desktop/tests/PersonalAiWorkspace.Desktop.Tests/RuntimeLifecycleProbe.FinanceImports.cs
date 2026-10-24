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
    private static string ImportId(int n) => "00000000-0000-0000-0001-" + n.ToString("D12");
    private static async Task VerifyFinanceImports(AssistantApp app, RuntimeClient client, Mmf3AcceptanceLaunch launch, bool bob, Func<string, Task> mutate)
    {
        var core = app.Workspace!.Browser.CoreWebView2;
        async Task Wait(string text) {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            try { while (await core.ExecuteScriptAsync("Boolean(document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(text) + "))") != "true") await Task.Delay(100, timeout.Token); }
            catch (OperationCanceledException) { Console.WriteLine("FNEXT9_WAIT_TIMEOUT_" + text); throw; }
        }
        async Task Click(string text) {
            string b="[...document.querySelectorAll('button')].find(b=>b.textContent==="+JsonSerializer.Serialize(text)+"&&!b.disabled)";
            using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(40));
            while(await core.ExecuteScriptAsync("Boolean("+b+")")!="true") await Task.Delay(100,timeout.Token);
            await core.ExecuteScriptAsync(b+".click()");
        }
        var batches=new List<FinanceImportBatch>();
        for(int p=0;;p++) { var page=await client.ListFinanceImportsAsync(p,2,CancellationToken.None); batches.AddRange(page.Items); if(page.Items.Length<2) break; }
        Assert.Equal(bob ? new[]{ImportId(201)} : new[]{102,101,106,105,104,103}.Select(ImportId), batches.Select(x=>x.ImportBatchId));
        foreach(var b in batches) Assert.Equal("CONFIRMED",b.Status);
        Assert.Empty((await client.ListFinanceImportsAsync(999,10,CancellationToken.None)).Items);
        var own=await client.GetFinanceImportReceiptAsync(ImportId(bob?201:101),0,0,CancellationToken.None);
        Assert.True(own.CompleteReceipt);
        var foreign=await Assert.ThrowsAsync<DesktopException>(()=>client.GetFinanceImportReceiptAsync(ImportId(bob?101:201),0,0,CancellationToken.None));
        var missing=await Assert.ThrowsAsync<DesktopException>(()=>client.GetFinanceImportReceiptAsync(ImportId(999),0,0,CancellationToken.None));
        Assert.Equal(DesktopError.FinanceImportNotFound,foreign.Error); Assert.Equal(foreign.Error,missing.Error);
        await core.ExecuteScriptAsync("location.hash='#/finance'"); await Click("Import History · 导入历史"); await Wait(bob?"bob-import.csv":"bank-statement.csv");
        if(bob) {
            await Click("bob-import.csv"); await Wait("Confirmation Receipt · 确认回执"); await Wait("20101");
            Assert.DoesNotContain("bank-statement.csv",await core.ExecuteScriptAsync("document.body.innerText"));
            Console.WriteLine("FNEXT9_BOB_NATIVE_BINDING_OWN_RECEIPT_ALICE_DENIED_PASS"); return;
        }
        var r=own.Receipt;
        Assert.Equal("bank-statement.csv",r.SourceFileName); Assert.Equal("9007199254740993.30",r.AccountImpacts[0].BalanceAfter);
        Assert.Equal("9007199254740993.10",r.AccountImpacts[0].BalanceBefore);Assert.Equal("0.20",r.AccountImpacts[0].Delta);
        Assert.Equal("-1.25",r.AccountImpacts[1].Delta);Assert.Equal("8.75",r.AccountImpacts[1].BalanceAfter);
        Assert.Equal(new[]{2,4},r.Transactions.Select(x=>x.RowNumber)); Assert.Equal(new[]{"10101","10102"},r.Transactions.Select(x=>x.TransactionId));
        var large=await client.GetFinanceImportReceiptAsync(ImportId(102),0,0,CancellationToken.None);
        var end=await client.GetFinanceImportReceiptAsync(ImportId(102),1,1,CancellationToken.None);
        Assert.False(large.CompleteReceipt);Assert.False(end.CompleteReceipt);Assert.Equal(25,large.AccountImpactCount);
        Assert.Equal(20,large.Receipt.Transactions.Length);Assert.Equal(5,end.Receipt.Transactions.Length);Assert.Equal(5,end.Receipt.AccountImpacts.Length);
        Assert.Equal(50,end.Receipt.Transactions[^1].RowNumber);Assert.Equal("10225",end.Receipt.Transactions[^1].TransactionId);
        Assert.Equal(large.Receipt.ResultDigest,end.Receipt.ResultDigest);
        foreach(int n in new[]{103,104,105,106}) Assert.Equal(DesktopError.FinanceImportReceiptInvalid,
            (await Assert.ThrowsAsync<DesktopException>(()=>client.GetFinanceImportReceiptAsync(ImportId(n),0,0,CancellationToken.None))).Error);
        Console.WriteLine("FNEXT9_REAL_POSTGRESQL_PAGING_TIES_EXACT_RECEIPT_V20_DELETED_TRANSACTION_FULL_DIGEST_SEGMENTS_MISSING_CORRUPT_DENIED_PASS");
        using var http=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false});
        async Task Check(string suffix,HttpStatusCode expected,bool auth=true,HttpMethod? method=null) {
            using var request=new HttpRequestMessage(method??HttpMethod.Get,"http://127.0.0.1:18765/api/v1/finance/imports/transactions/batches"+suffix);
            if(auth)request.Headers.Authorization=new AuthenticationHeaderValue("Bearer",app.DevelopmentRuntime!.ReadCredential());
            using var response=await http.SendAsync(request);Assert.Equal(expected,response.StatusCode);Assert.True(response.Headers.CacheControl?.NoStore);
        }
        foreach(string suffix in new[]{"?userId=2","?page=-1","?size=21","?page=0&page=1","?page=1.0","?status=EXPIRED","/1", "/"+ImportId(101)+"?userId=2", "/"+ImportId(101)+"?transactionPage=1", "/"+ImportId(102)+"?transactionPage=500"})await Check(suffix,HttpStatusCode.BadRequest);
        foreach(string suffix in new[]{"","/"+ImportId(101)}) {
            await Check(suffix,HttpStatusCode.Unauthorized,false);
            foreach(var method in new[]{HttpMethod.Post,HttpMethod.Put,HttpMethod.Patch,HttpMethod.Delete,HttpMethod.Head,HttpMethod.Options})await Check(suffix,HttpStatusCode.MethodNotAllowed,method:method);
        }
        Console.WriteLine("FNEXT9_REAL_HTTP_STRICT_UUID_PAGING_USER_SCOPE_AUTH_NO_STORE_GET_ONLY_PASS");
        await Click("bank-statement.csv");await Wait("9007199254740993.30");await Wait("当前显示完整回执。");
        Assert.DoesNotContain("D:\\private",await core.ExecuteScriptAsync("document.body.innerText"));
        await Click("many-rows.csv");await Wait("当前为分段视图");await Click("下一段行映射");await Wait("行映射第 2 / 2 页");await Wait("10225");
        await Click("下一段账户影响");await Wait("账户影响第 2 / 2 页");await Wait("1025");
        await Click("刷新导入历史");await Wait("行映射第 1 / 2 页");
        await Click("bad-digest.csv");await Wait("确认回执证据缺失或不一致");
        Assert.Equal("false",await core.ExecuteScriptAsync("Boolean(document.querySelector('[aria-label=\"Confirmation Receipt\"]'))"));
        await Click("返回导入批次列表");await Wait("bank-statement.csv");await Click("bank-statement.csv");await Wait("9007199254740993.30");
        foreach(int width in new[]{1280,800}) {
            app.Workspace.Width=width;await Task.Delay(250);Assert.Equal("true",await core.ExecuteScriptAsync("document.documentElement.scrollWidth<=document.documentElement.clientWidth"));
            await using var png=File.Create(Path.Combine(Environment.CurrentDirectory,".verification","fnext9-imports-"+width+".png"));
            await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png,png);
        }
        // Reuse core reads, without repeating prior milestone matrices.
        Assert.Equal("READY",(await client.GetFinanceOverviewAsync(CancellationToken.None)).State);
        Assert.NotEmpty((await client.ListFinanceAccountsAsync(0,10,"ALL","ID_ASC",CancellationToken.None)).Items);
        Assert.NotEmpty((await client.ListFinanceTransactionsAsync(new(0,10,"","","","ALL"),CancellationToken.None)).Items);
        Assert.NotEmpty((await client.ListFinanceAssetsAsync(new(0,10,"ALL","ID_ASC"),CancellationToken.None)).Items);
        Assert.NotEmpty((await client.ListFinancePositionsAsync(new("ALL","","","",10),CancellationToken.None)).Records);
        Assert.Equal("CNY",(await client.GetFinancePortfolioAsync(CancellationToken.None)).Currency);
        Assert.Equal("REPLACED",(await client.GetFinanceHistoryDetailAsync("103",CancellationToken.None)).CorrectionStatus);
        var digest=r.ResultDigest;
        using(var old=Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value)) {
            await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);await old.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(20));
        }
        using(var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(40))) while(!app.DevelopmentRuntime.Snapshot().CanQueryStorage)await Task.Delay(100,timeout.Token);
        Assert.Equal(digest,(await client.GetFinanceImportReceiptAsync(ImportId(101),0,0,CancellationToken.None)).Receipt.ResultDigest);
        await Click("刷新导入历史");await Wait("bank-statement.csv");await Click("bank-statement.csv");await Wait("9007199254740993.30");
        await mutate("identity-change");
        try { await Click("刷新导入历史");await Wait("绑定或数据集已改变");Assert.Equal("false",await core.ExecuteScriptAsync("Boolean(document.querySelector('[aria-label=\"Confirmation Receipt\"]'))")); }
        finally { await mutate("identity-restore"); }
        await Click("刷新导入历史");await Wait("bank-statement.csv");
        Console.WriteLine("FNEXT9_REAL_WPF_WEBVIEW_RECEIPT_SEGMENTS_ERROR_RECOVERY_RESPONSIVE_CORE_READS_RUNTIME_RESTART_IDENTITY_CLEAR_PASS");
    }
}
#endif
