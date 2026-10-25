using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinancePortfolio(string Currency, int PositionCount, int OpenPositionCount, int ClosedPositionCount,
    string OpenTotalCost, string CumulativeRealizedProfitLoss, FinancePortfolioReference ReferenceValuation, [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)] FinanceDistribution? StatusDistribution = null, [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)] FinanceDistribution? CoverageDistribution = null);
public sealed record FinancePortfolioReference(string BaseCurrency, string? Value, int ValuedPositionCount,
    int TotalOpenPositionCount, string Freshness, FinancePositionWarning[] Warnings);

public sealed partial class RuntimeClient
{
    public async Task<FinancePortfolio> GetFinancePortfolioAsync(CancellationToken token)
    {
        using var body = await FinanceJson("/investment/portfolio", token);
        var r = body.RootElement;
        bool analytics = r.TryGetProperty("statusDistribution", out _);
        if (analytics) MemoryFields(r, "currency", "positionCount", "openPositionCount", "closedPositionCount", "openTotalCost", "cumulativeRealizedProfitLoss", "referenceValuation", "statusDistribution", "coverageDistribution");
        else MemoryFields(r, "currency", "positionCount", "openPositionCount", "closedPositionCount", "openTotalCost", "cumulativeRealizedProfitLoss", "referenceValuation");
        int count = MemoryInteger(r, "positionCount"), open = MemoryInteger(r, "openPositionCount"), closed = MemoryInteger(r, "closedPositionCount");
        var v = Property(r, "referenceValuation");
        MemoryFields(v, "baseCurrency", "value", "valuedPositionCount", "totalOpenPositionCount", "freshness", "warnings");
        int valued = MemoryInteger(v, "valuedPositionCount"), total = MemoryInteger(v, "totalOpenPositionCount");
        string state = String(v, "freshness");
        string? value = Property(v, "value").ValueKind == JsonValueKind.Null ? null : PortfolioAmount(v, "value");
        if (String(r, "currency") != "CNY" || String(v, "baseCurrency") != "CNY" || count < 0 || open < 0 || closed < 0
            || (long)open + closed != count || total != open || valued < 0 || valued > total
            || (valued == 0 ? state != "UNAVAILABLE" || value is not null
                : value is null || (valued < total ? state != "PARTIAL" : state is not ("FRESH" or "STALE")))) throw Invalid();
        if (analytics) {
            foreach (string key in new[] { "statusDistribution", "coverageDistribution" }) AnalyticsDistribution(Property(r, key));
            if (String(Property(r, "statusDistribution"), "total") != count.ToString(System.Globalization.CultureInfo.InvariantCulture)
                || String(Property(r, "coverageDistribution"), "total") != open.ToString(System.Globalization.CultureInfo.InvariantCulture)) throw Invalid();
        }
        return new("CNY", count, open, closed, PortfolioAmount(r, "openTotalCost"), PortfolioAmount(r, "cumulativeRealizedProfitLoss"),
            new("CNY", value, valued, total, state, PositionWarnings(v, int.MaxValue)),
            analytics ? Property(r, "statusDistribution").Deserialize<FinanceDistribution>(HistoryJson) : null,
            analytics ? Property(r, "coverageDistribution").Deserialize<FinanceDistribution>(HistoryJson) : null);
    }

    private static string PortfolioAmount(JsonElement r, string key)
    {
        string value = String(r, key);
        if (!Regex.IsMatch(value, @"\A-?[0-9]{1,60}\.[0-9]{2}\z")) throw Invalid();
        return value;
    }
}
