using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceAccount(string Id, string Name, string Type, string Currency, string Balance, string Status);
public sealed record FinanceBalance(string Currency, string Balance);
public sealed record FinanceOverview(string State, FinanceAccount? Account, FinanceBalance? CnyBalance);

public sealed partial class RuntimeClient
{
    public async Task<FinanceOverview> GetFinanceOverviewAsync(CancellationToken token)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/finance/overview", null, true, HttpStatusCode.OK, token,
            maximumResponse: 8192, requestTimeout: TimeSpan.FromSeconds(35));
        var root = body.RootElement;
        MemoryFields(root, "state", "account", "cnyBalance");
        string state = String(root, "state");
        var account = Property(root, "account"); var total = Property(root, "cnyBalance");
        if (state != "READY")
        {
            if (state is not ("DISABLED" or "NOT_BOUND" or "BINDING_INVALID" or "UNAVAILABLE")
                || account.ValueKind != JsonValueKind.Null || total.ValueKind != JsonValueKind.Null) throw Invalid();
            return new(state, null, null);
        }
        string Amount(JsonElement value) { string text = String(value, "balance"); if (!Regex.IsMatch(text, @"\A-?\d{1,30}(?:\.\d{1,8})?\z")) throw Invalid(); return text; }
        MemoryFields(total, "currency", "balance");
        if (String(total, "currency") != "CNY") throw Invalid();
        FinanceAccount? parsed = null;
        if (account.ValueKind != JsonValueKind.Null)
        {
            MemoryFields(account, "id", "name", "type", "currency", "balance", "status");
            string id = String(account, "id"), name = String(account, "name");
            if (!Regex.IsMatch(id, @"\A[1-9]\d{0,18}\z") || name.Length > 256 || String(account, "currency") != "CNY") throw Invalid();
            parsed = new(id, name, String(account, "type"), "CNY", Amount(account), String(account, "status"));
        }
        return new("READY", parsed, new("CNY", Amount(total)));
    }
}
