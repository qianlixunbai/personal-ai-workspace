using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceSlice(string Key, string Label, string Value, string Percentage, int Start, int End);
public sealed record FinanceDistribution(string State, string Total, FinanceSlice[] Items);
public sealed record FinanceMonth(string Month, string State, string? Income, string? Expense, string? Net, string? TransactionCount);
public sealed record FinancePlotTick(int Coordinate, string Amount);
public sealed record FinancePlot(int?[] Income, int?[] Expense, int?[] Net, int Zero, FinancePlotTick[] Ticks);
public sealed record FinanceAnnual(string Currency, string BusinessZone, string AsOf, int CurrentYear, int Year, int[] Years,
    string Income, string Expense, string Net, string TransactionCount, FinanceMonth[] Months, FinancePlot Plot, FinanceDistribution ExpenseBreakdown);

public sealed partial class RuntimeClient
{
    public static bool ValidFinanceYear(int year) => year == 0 || year is >= 1900 and <= 2199;
    public async Task<FinanceAnnual> GetFinanceAnnualAsync(int year, CancellationToken token)
    {
        if (!ValidFinanceYear(year)) throw Invalid();
        using var body = await FinanceJson(FormattableString.Invariant($"/annual?year={year}"), token);
        var r = body.RootElement;
        MemoryFields(r, "currency", "businessZone", "asOf", "currentYear", "year", "years", "income", "expense", "net", "transactionCount", "months", "plot", "expenseBreakdown");
        var asOf = String(r, "asOf"); int current = MemoryInteger(r, "currentYear"), selected = MemoryInteger(r, "year");
        if (String(r, "currency") != "CNY" || String(r, "businessZone") != "Asia/Shanghai" || !DashboardTime(asOf)
            || current is < 1900 or > 2199 || current != int.Parse(asOf[..4], CultureInfo.InvariantCulture)
            || selected is < 1900 or > 2199 || selected > current || selected != (year == 0 ? current : year)) throw Invalid();
        var years = Property(r, "years");
        if (years.ValueKind != JsonValueKind.Array || years.GetArrayLength() is < 1 or > 300) throw Invalid();
        int previous = current + 1;
        foreach (var y in years.EnumerateArray()) {
            if (y.ValueKind != JsonValueKind.Number || !y.TryGetInt32(out int n) || n < 1900 || n >= previous) throw Invalid(); previous = n;
        }
        if (years[0].GetInt32() != current) throw Invalid();
        foreach (string key in new[] { "income", "expense", "net" }) DashboardAmount(r, key);
        FinanceCount(r, "transactionCount");
        var months = AnalyticsMonths(Property(r, "months"));
        for (int i = 0; i < 12; i++) if (months[i].Month != $"{selected:D4}-{i + 1:D2}"
            || (months[i].State == "FUTURE") != (string.CompareOrdinal(months[i].Month, asOf[..7]) > 0)) throw Invalid();
        AnalyticsPlot(Property(r, "plot"), months); AnalyticsDistribution(Property(r, "expenseBreakdown"));
        return r.Deserialize<FinanceAnnual>(HistoryJson)!;
    }
    private static bool PlotCoordinate(JsonElement v) => v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out int n) && n is >= 0 and <= 10000;
    private static FinanceMonth[] AnalyticsMonths(JsonElement rows) {
        if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() != 12) throw Invalid();
        foreach (var m in rows.EnumerateArray()) {
            MemoryFields(m, "month", "state", "income", "expense", "net", "transactionCount");
            if (!Regex.IsMatch(String(m, "month"), @"\A[12][0-9]{3}-(?:0[1-9]|1[0-2])\z")) throw Invalid();
            bool future = String(m, "state") == "FUTURE";
            if (!future && String(m, "state") != "OBSERVED") throw Invalid();
            foreach (string k in new[] { "income", "expense", "net", "transactionCount" }) {
                if (future) { if (Property(m, k).ValueKind != JsonValueKind.Null) throw Invalid(); }
                else if (k == "transactionCount") FinanceCount(m, k); else DashboardAmount(m, k);
            }
        }
        return rows.Deserialize<FinanceMonth[]>(HistoryJson)!;
    }
    private static void AnalyticsPlot(JsonElement p, FinanceMonth[] months) {
        MemoryFields(p, "income", "expense", "net", "zero", "ticks"); if (!PlotCoordinate(Property(p, "zero"))) throw Invalid();
        foreach (string k in new[] { "income", "expense", "net" }) {
            var points = Property(p, k); if (points.ValueKind != JsonValueKind.Array || points.GetArrayLength() != 12) throw Invalid();
            for (int i = 0; i < 12; i++) if (months[i].State == "FUTURE" ? points[i].ValueKind != JsonValueKind.Null : !PlotCoordinate(points[i])) throw Invalid();
        }
        var ticks = Property(p, "ticks"); if (ticks.ValueKind != JsonValueKind.Array || ticks.GetArrayLength() != 5) throw Invalid();
        int index = 0; foreach (var t in ticks.EnumerateArray()) { MemoryFields(t, "coordinate", "amount"); if (MemoryInteger(t, "coordinate") != index++ * 2500) throw Invalid(); DashboardAmount(t, "amount"); }
    }
    private static void AnalyticsDistribution(JsonElement d) {
        MemoryFields(d, "state", "total", "items"); DashboardAmount(d, "total");
        var items = Property(d, "items"); var state = String(d, "state"); var total = String(d, "total");
        if (items.ValueKind != JsonValueKind.Array || items.GetArrayLength() > 13) throw Invalid();
        if (state is "EMPTY" or "UNSUPPORTED_SIGNED") {
            if (items.GetArrayLength() != 0 || state == "EMPTY" && !Regex.IsMatch(total, @"\A0(?:\.0+)?\z")) throw Invalid(); return;
        }
        if (state != "COMPLETE" || items.GetArrayLength() == 0 || total.StartsWith('-') || Regex.IsMatch(total, @"\A0(?:\.0+)?\z")) throw Invalid();
        int end = 0; var keys = new HashSet<string>();
        foreach (var s in items.EnumerateArray()) {
            MemoryFields(s, "key", "label", "value", "percentage", "start", "end"); DashboardAmount(s, "value");
            if (String(s, "key").Length is < 1 or > 100 || !keys.Add(String(s, "key")) || String(s, "label").Length is < 1 or > 256
                || String(s, "value").StartsWith('-') || !Regex.IsMatch(String(s, "percentage"), @"\A(?:[0-9]{1,2}\.[0-9]{4}|100\.0000)\z")
                || !PlotCoordinate(Property(s, "start")) || !PlotCoordinate(Property(s, "end"))
                || MemoryInteger(s, "start") != end || MemoryInteger(s, "end") < end) throw Invalid();
            end = MemoryInteger(s, "end");
        }
        if (end != 10000) throw Invalid();
    }
}
