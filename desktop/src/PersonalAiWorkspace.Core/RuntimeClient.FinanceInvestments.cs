using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinancePositionQuery(string Status, string AccountId, string InstrumentId, string Cursor, int Size);
public sealed record FinancePositionPage(FinancePosition[] Records, string? NextCursor, bool HasMore, int Size);
public sealed record FinancePositionAccount(string Id, string DisplayName, string Type, string Status);
public sealed record FinanceInstrument(string Id, string Symbol, string Name, string Market, string AssetClass, string QuoteCurrency, string Status);
public sealed record FinancePositionWarning(string Code, string Component);
public sealed record FinancePositionReference(string BaseCurrency, string? Value, string Freshness, FinancePositionWarning[] Warnings);
public sealed record FinancePosition(string PositionId, string PositionMode, FinancePositionAccount Account, FinanceInstrument Instrument,
    string? Quantity, string? AverageCost, string? TotalCost, string? CumulativeRealizedProfitLoss, string Status, FinancePositionReference ReferenceValuation);
public sealed record FinanceManualReference(string? CurrentPrice, string? MarketValue);
public sealed record FinancePositionDetail(string PositionId, string PositionMode, FinancePositionAccount Account, FinanceInstrument Instrument,
    string? Quantity, string? AverageCost, string? TotalCost, string? CumulativeRealizedProfitLoss, string Status,
    FinanceManualReference ManualReference, FinancePositionCachedReference ReferenceValuation);
public sealed record FinancePositionCachedReference(bool AccountingTruth, string? QuotePrice, string? QuoteCurrency, string? QuoteTime,
    string? QuoteFetchedAt, string? QuoteProvider, string? FxRate, string? FxBaseCurrency, string? FxQuoteCurrency,
    string? FxRateTime, string? FxFetchedAt, string? FxProvider, string BaseCurrency, string? BaseCurrencyValue,
    string Freshness, FinancePositionWarning[] Warnings);

public sealed partial class RuntimeClient
{
    private static bool PositionCursor(string value) => Regex.IsMatch(value, @"\A[A-Za-z0-9_-]{1,2048}\z");
    public static bool ValidFinancePositionQuery(FinancePositionQuery q) => q.Status is "OPEN" or "CLOSED" or "ALL"
        && q.Size is >= 1 and <= 20 && (q.AccountId == "" || ValidFinanceId(q.AccountId))
        && (q.InstrumentId == "" || ValidFinanceId(q.InstrumentId)) && (q.Cursor == "" || PositionCursor(q.Cursor));

    public async Task<FinancePositionPage> ListFinancePositionsAsync(FinancePositionQuery query, CancellationToken token)
    {
        if (!ValidFinancePositionQuery(query)) throw new DesktopException(DesktopError.InvalidRequest);
        string path = FormattableString.Invariant($"/investment/positions?status={query.Status}&size={query.Size}")
            + (query.AccountId == "" ? "" : "&accountId=" + query.AccountId)
            + (query.InstrumentId == "" ? "" : "&instrumentId=" + query.InstrumentId)
            + (query.Cursor == "" ? "" : "&cursor=" + Uri.EscapeDataString(query.Cursor));
        using var body = await FinanceJson(path, token);
        var root = body.RootElement;
        MemoryFields(root, "records", "nextCursor", "hasMore", "size");
        var rows = Property(root, "records"); var more = Property(root, "hasMore");
        string? next = AssetText(root, "nextCursor", 2048);
        if (MemoryInteger(root, "size") != query.Size || rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > query.Size
            || more.ValueKind is not (JsonValueKind.True or JsonValueKind.False) || more.GetBoolean() != (next is not null)
            || next is not null && (!PositionCursor(next) || next == query.Cursor) || more.GetBoolean() && rows.GetArrayLength() != query.Size) throw Invalid();
        var records = rows.EnumerateArray().Select(ParseFinancePosition).ToArray();
        if (records.Select(p => p.PositionId).Distinct().Count() != records.Length || records.Any(p =>
            query.Status != "ALL" && p.Status != query.Status || query.AccountId != "" && p.Account.Id != query.AccountId
            || query.InstrumentId != "" && p.Instrument.Id != query.InstrumentId)) throw Invalid();
        return new(records, next, more.GetBoolean(), query.Size);
    }

    public async Task<FinancePositionDetail> GetFinancePositionAsync(string id, CancellationToken token)
    {
        if (!ValidFinanceId(id)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson("/investment/positions/" + id, token);
        var r = body.RootElement;
        PositionFields(r, "manualReference", "referenceValuation");
        var (account, instrument) = PositionIdentity(r);
        if (String(r, "positionId") != id) throw Invalid();
        var manual = Property(r, "manualReference"); MemoryFields(manual, "currentPrice", "marketValue");
        return new(id, "TRANSACTION_DRIVEN", account, instrument, AssetDecimal(r, "quantity"), AssetDecimal(r, "averageCost"),
            AssetDecimal(r, "totalCost"), AssetDecimal(r, "cumulativeRealizedProfitLoss"), String(r, "status"),
            new(AssetDecimal(manual, "currentPrice"), AssetDecimal(manual, "marketValue")), ParsePositionCachedReference(Property(r, "referenceValuation")));
    }

    private static void PositionFields(JsonElement r, params string[] additional) => MemoryFields(r,
        new[] { "positionId", "positionMode", "account", "instrument", "quantity", "averageCost", "totalCost", "cumulativeRealizedProfitLoss", "status" }.Concat(additional).ToArray());

    private static (FinancePositionAccount, FinanceInstrument) PositionIdentity(JsonElement r)
    {
        var a = Property(r, "account"); var i = Property(r, "instrument");
        MemoryFields(a, "id", "displayName", "type", "status");
        MemoryFields(i, "id", "symbol", "name", "market", "assetClass", "quoteCurrency", "status");
        if (!ValidFinanceId(String(r, "positionId")) || String(r, "positionMode") != "TRANSACTION_DRIVEN"
            || String(r, "status") is not ("OPEN" or "CLOSED") || !ValidFinanceId(String(a, "id")) || !ValidFinanceId(String(i, "id"))
            || String(a, "status") is not ("ACTIVE" or "INACTIVE") || String(i, "status") is not ("ACTIVE" or "INACTIVE")
            || String(i, "assetClass") is not ("STOCK" or "ETF" or "FUND" or "BOND" or "CRYPTO")
            || String(i, "market") is not ("US" or "HK" or "CN" or "JP" or "KR" or "CRYPTO" or "OTC" or "FUND" or "OTHER" or "UNKNOWN")) throw Invalid();
        string Text(JsonElement value, string key, int limit) {
            string text = AssetText(value, key, limit) ?? throw Invalid();
            return text.Length > 0 ? text : throw Invalid();
        }
        return (new(String(a, "id"), Text(a, "displayName", 100), Text(a, "type", 50), String(a, "status")),
            new(String(i, "id"), Text(i, "symbol", 30), Text(i, "name", 100), String(i, "market"), String(i, "assetClass"),
                AssetCurrency(i, "quoteCurrency") ?? throw Invalid(), String(i, "status")));
    }
    private static FinancePosition ParseFinancePosition(JsonElement r)
    {
        PositionFields(r, "referenceValuation"); var (account, instrument) = PositionIdentity(r);
        var reference = Property(r, "referenceValuation"); MemoryFields(reference, "baseCurrency", "value", "freshness", "warnings");
        ValidatePositionReference(reference, "value");
        return new(String(r, "positionId"), "TRANSACTION_DRIVEN", account, instrument, AssetDecimal(r, "quantity"), AssetDecimal(r, "averageCost"),
            AssetDecimal(r, "totalCost"), AssetDecimal(r, "cumulativeRealizedProfitLoss"), String(r, "status"),
            new("CNY", AssetDecimal(reference, "value"), String(reference, "freshness"), PositionWarnings(reference)));
    }
    private static void ValidatePositionReference(JsonElement r, string valueKey)
    {
        string state = String(r, "freshness");
        if (String(r, "baseCurrency") != "CNY" || state is not ("FRESH" or "STALE" or "PARTIAL" or "UNAVAILABLE")
            || (state is "FRESH" or "STALE") != (AssetDecimal(r, valueKey) is not null)) throw Invalid();
    }
    private static FinancePositionWarning[] PositionWarnings(JsonElement r, int maximum = 8)
    {
        var warnings = Property(r, "warnings");
        if (warnings.ValueKind != JsonValueKind.Array || warnings.GetArrayLength() > maximum) throw Invalid();
        return warnings.EnumerateArray().Select(w => {
            MemoryFields(w, "code", "component"); string code = String(w, "code"), component = String(w, "component");
            if (code is not ("INVALID_INPUT" or "QUOTE_MISSING" or "QUOTE_STALE" or "FX_MISSING" or "FX_STALE")
                || component is not ("VALUATION" or "QUOTE" or "FX")) throw Invalid();
            return new FinancePositionWarning(code, component);
        }).ToArray();
    }
    private static FinancePositionCachedReference ParsePositionCachedReference(JsonElement r)
    {
        MemoryFields(r, "accountingTruth", "quotePrice", "quoteCurrency", "quoteTime", "quoteFetchedAt", "quoteProvider", "fxRate", "fxBaseCurrency",
            "fxQuoteCurrency", "fxRateTime", "fxFetchedAt", "fxProvider", "baseCurrency", "baseCurrencyValue", "freshness", "warnings");
        ValidatePositionReference(r, "baseCurrencyValue");
        if (Property(r, "accountingTruth").ValueKind != JsonValueKind.False) throw Invalid();
        return new(false, AssetDecimal(r, "quotePrice"), AssetCurrency(r, "quoteCurrency"), AssetTime(r, "quoteTime"), AssetTime(r, "quoteFetchedAt"),
            AssetText(r, "quoteProvider", 30), AssetDecimal(r, "fxRate"), AssetCurrency(r, "fxBaseCurrency"), AssetCurrency(r, "fxQuoteCurrency"),
            AssetTime(r, "fxRateTime"), AssetTime(r, "fxFetchedAt"), AssetText(r, "fxProvider", 30), "CNY", AssetDecimal(r, "baseCurrencyValue"),
            String(r, "freshness"), PositionWarnings(r));
    }
}
