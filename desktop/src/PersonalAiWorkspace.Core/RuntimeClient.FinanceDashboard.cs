using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceDashboard(string Currency, string AsOf, string BusinessZone, string CnyAccountBalance,
    string MonthIncome, string MonthExpense, string MonthNet, FinanceDashboardFlow[] MonthlyCashFlow,
    FinanceDashboardAllocation Allocation, FinanceDashboardRecent[] RecentTransactions);
public sealed record FinanceDashboardFlow(string Month, string Income, string Expense, string Net);
public sealed record FinanceDashboardAsset(string Id, string Name, string Value, string Percentage);
public sealed record FinanceDashboardAllocation(FinanceDashboardAsset[] Items, string Total, bool Complete, string ReferenceTotal,
    string AssetTotal, string ExcludedCurrency, string ExcludedUnpriced, string ExcludedQuantity, string ExcludedValue);
public sealed record FinanceDashboardRecent(string Id, string Type, string Amount, string? CategoryName, string? AccountName, string TransactedAt);

public sealed partial class RuntimeClient
{
    public async Task<FinanceDashboard> GetFinanceDashboardAsync(CancellationToken token)
    {
        using var body = await FinanceJson("/dashboard", token);
        var r = body.RootElement;
        MemoryFields(r, "currency", "asOf", "businessZone", "cnyAccountBalance", "monthIncome", "monthExpense", "monthNet", "monthlyCashFlow", "allocation", "recentTransactions");
        if (String(r, "currency") != "CNY" || String(r, "businessZone") != "Asia/Shanghai" || !DashboardTime(String(r, "asOf"))) throw Invalid();
        foreach (string k in new[] { "cnyAccountBalance", "monthIncome", "monthExpense", "monthNet" }) DashboardAmount(r, k);
        var flows = Property(r, "monthlyCashFlow");
        if (flows.ValueKind != JsonValueKind.Array || flows.GetArrayLength() != 6) throw Invalid();
        var current = DateTime.Parse(String(r, "asOf"), CultureInfo.InvariantCulture);
        int index = 0;
        foreach (var f in flows.EnumerateArray()) {
            MemoryFields(f, "month", "income", "expense", "net");
            if (String(f, "month") != current.AddMonths(index++ - 5).ToString("yyyy-MM", CultureInfo.InvariantCulture)) throw Invalid();
            foreach (string k in new[] { "income", "expense", "net" }) DashboardAmount(f, k);
        }
        var a = Property(r, "allocation");
        MemoryFields(a, "items", "total", "complete", "referenceTotal", "assetTotal", "excludedCurrency", "excludedUnpriced", "excludedQuantity", "excludedValue");
        long total = long.Parse(FinanceCount(a, "total"), CultureInfo.InvariantCulture);
        long assetTotal = long.Parse(FinanceCount(a, "assetTotal"), CultureInfo.InvariantCulture), excluded = 0;
        foreach (string k in new[] { "excludedCurrency", "excludedUnpriced", "excludedQuantity", "excludedValue" }) {
            long n = long.Parse(FinanceCount(a, k), CultureInfo.InvariantCulture);
            if (n > assetTotal - excluded) throw Invalid(); excluded += n;
        }
        if (total != assetTotal - excluded) throw Invalid();
        DashboardAmount(a, "referenceTotal"); var items = Property(a, "items"); var complete = Property(a, "complete");
        if (items.ValueKind != JsonValueKind.Array || items.GetArrayLength() != Math.Min(20L, total)
            || complete.ValueKind is not (JsonValueKind.True or JsonValueKind.False) || complete.GetBoolean() != (total <= 20)) throw Invalid();
        long previous = 0;
        foreach (var item in items.EnumerateArray()) {
            MemoryFields(item, "id", "name", "value", "percentage");
            string id = String(item, "id"), p = String(item, "percentage");
            if (!ValidFinanceId(id) || long.Parse(id, CultureInfo.InvariantCulture) <= previous || String(item, "name").Length is < 1 or > 256
                || !Regex.IsMatch(p, @"\A(?:[0-9]{1,2}\.[0-9]{4}|100\.0000)\z")) throw Invalid();
            previous = long.Parse(id, CultureInfo.InvariantCulture); DashboardAmount(item, "value");
        }
        var recent = Property(r, "recentTransactions");
        if (recent.ValueKind != JsonValueKind.Array || recent.GetArrayLength() > 5) throw Invalid();
        var ids = new HashSet<string>();
        foreach (var t in recent.EnumerateArray()) {
            MemoryFields(t, "id", "type", "amount", "categoryName", "accountName", "transactedAt");
            if (!ValidFinanceId(String(t, "id")) || !ids.Add(String(t, "id")) || String(t, "type").Length is < 1 or > 50
                || !DashboardTime(String(t, "transactedAt"))) throw Invalid();
            foreach (string k in new[] { "categoryName", "accountName" })
                if (Property(t, k).ValueKind != JsonValueKind.Null && String(t, k).Length is < 1 or > 256) throw Invalid();
            DashboardAmount(t, "amount");
        }
        return r.Deserialize<FinanceDashboard>(HistoryJson)!;
    }
    private static void DashboardAmount(JsonElement r, string k) {
        if (!Regex.IsMatch(String(r, k), @"\A-?[0-9]{1,60}(?:\.[0-9]{1,16})?\z")) throw Invalid();
    }
    private static bool DashboardTime(string s) => Regex.IsMatch(s, @"\A[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]{1,9})?)?\z")
        && DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out _);
}
