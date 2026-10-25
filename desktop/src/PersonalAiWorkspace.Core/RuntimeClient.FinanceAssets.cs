using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceAssetQuery(int Page, int Size, string Type, string Sort);
public sealed record FinanceAssetPage(FinanceAsset[] Items, int Page, int Size, string Total, string Type, string Sort, [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)] FinanceDistribution? TypeDistribution = null);
public sealed record FinanceAsset(string Id, string Name, string? Symbol, string Type, string? Market, string Currency,
    string Quantity, string AvgCost, string? CurrentPrice, string? ManualMarketValue, string PositionMode,
    string CreatedAt, string UpdatedAt, FinanceAssetReference? ReferenceValuation);
public sealed record FinanceAssetReference(string? QuoteCurrency, string? QuotePrice, string? QuoteTime, string? QuoteFetchedAt,
    string? QuoteProvider, string QuoteFreshness, bool FxRequired, string? FxBaseCurrency, string? FxQuoteCurrency,
    string? FxRate, string? FxRateTime, string? FxFetchedAt, string? FxProvider, string FxFreshness,
    string? NativeMarketValue, string BaseCurrency, string? BaseCurrencyMarketValue, string ValuationFreshness,
    string CalculatedAt, string FormulaVersion, string[] Warnings);

public sealed partial class RuntimeClient
{
    public static bool ValidFinanceAssetQuery(FinanceAssetQuery q) => q.Page is >= 0 and <= 100000 && q.Size is >= 1 and <= 20
        && q.Type is "ALL" or "STOCK" or "ETF" or "FUND" or "BOND" or "GOLD" or "CRYPTO" or "CASH" or "OTHER"
        && q.Sort is "CREATED_DESC" or "ID_ASC" or "NAME_ASC";

    public async Task<FinanceAssetPage> ListFinanceAssetsAsync(FinanceAssetQuery query, CancellationToken token)
    {
        if (!ValidFinanceAssetQuery(query)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson(FormattableString.Invariant($"/assets?page={query.Page}&size={query.Size}&type={query.Type}&sort={query.Sort}"), token);
        var root = body.RootElement;
        bool analytics = root.TryGetProperty("typeDistribution", out var distribution);
        if (analytics) { MemoryFields(root, "items", "page", "size", "total", "type", "sort", "typeDistribution"); AnalyticsDistribution(distribution); }
        else MemoryFields(root, "items", "page", "size", "total", "type", "sort");
        var rows = Property(root, "items");
        if (MemoryInteger(root, "page") != query.Page || MemoryInteger(root, "size") != query.Size
            || String(root, "type") != query.Type || String(root, "sort") != query.Sort
            || rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > query.Size) throw Invalid();
        var items = rows.EnumerateArray().Select(ParseFinanceAsset).ToArray();
        if (items.Select(a => a.Id).Distinct().Count() != items.Length || items.Any(a => query.Type != "ALL" && a.Type != query.Type)) throw Invalid();
        return new(items, query.Page, query.Size, FinanceCount(root, "total"), query.Type, query.Sort, analytics ? distribution.Deserialize<FinanceDistribution>(HistoryJson) : null);
    }

    public async Task<FinanceAsset> GetFinanceAssetAsync(string id, CancellationToken token)
    {
        if (!ValidFinanceId(id)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson("/assets/" + id, token);
        var result = ParseFinanceAsset(body.RootElement);
        if (result.Id != id) throw Invalid();
        return result;
    }

    private static string? AssetText(JsonElement value, string key, int length = 100)
    {
        if (Property(value, key).ValueKind == JsonValueKind.Null) return null;
        string text = String(value, key);
        if (text.Length > length) throw Invalid();
        return text;
    }
    private static string? AssetDecimal(JsonElement value, string key)
    {
        string? text = AssetText(value, key);
        // Products retain all source BigDecimal digits (quantity x quote x FX); never parse as double/decimal.
        if (text is not null && !Regex.IsMatch(text, @"\A-?[0-9]{1,60}(?:\.[0-9]{1,28})?\z")) throw Invalid();
        return text;
    }
    private static string? AssetTime(JsonElement value, string key, bool instant = true)
    {
        string? text = AssetText(value, key, 40);
        if (text is not null && (!text.Contains('T') || instant && !text.EndsWith('Z')
            || !DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out _))) throw Invalid();
        return text;
    }
    private static string? AssetCurrency(JsonElement value, string key)
    {
        string? text = AssetText(value, key, 3);
        if (text is not null && !Regex.IsMatch(text, @"\A[A-Z]{3}\z")) throw Invalid();
        return text;
    }
    private static FinanceAsset ParseFinanceAsset(JsonElement r)
    {
        MemoryFields(r, "id", "name", "symbol", "type", "market", "currency", "quantity", "avgCost", "currentPrice",
            "manualMarketValue", "positionMode", "createdAt", "updatedAt", "referenceValuation");
        string id = String(r, "id"), name = String(r, "name"), type = String(r, "type"), mode = String(r, "positionMode");
        string? market = AssetText(r, "market", 30), price = AssetDecimal(r, "currentPrice"), manual = AssetDecimal(r, "manualMarketValue");
        if (!ValidFinanceId(id) || name.Length is < 1 or > 100 || type == "ALL" || !ValidFinanceAssetQuery(new(0, 1, type, "ID_ASC"))
            || mode is not ("LEGACY" or "TRANSACTION_DRIVEN") || (price is null) != (manual is null)) throw Invalid();
        var reference = Property(r, "referenceValuation");
        if (reference.ValueKind != JsonValueKind.Null && (type is not ("STOCK" or "ETF") || market?.Trim().ToUpperInvariant() != "US")) throw Invalid();
        return new(id, name, AssetText(r, "symbol", 30), type, market, AssetCurrency(r, "currency") ?? throw Invalid(),
            AssetDecimal(r, "quantity") ?? throw Invalid(), AssetDecimal(r, "avgCost") ?? throw Invalid(), price, manual, mode,
            AssetTime(r, "createdAt", false) ?? throw Invalid(), AssetTime(r, "updatedAt", false) ?? throw Invalid(),
            reference.ValueKind == JsonValueKind.Null ? null : ParseAssetReference(reference));
    }
    private static FinanceAssetReference ParseAssetReference(JsonElement r)
    {
        MemoryFields(r, "quoteCurrency", "quotePrice", "quoteTime", "quoteFetchedAt", "quoteProvider", "quoteFreshness", "fxRequired",
            "fxBaseCurrency", "fxQuoteCurrency", "fxRate", "fxRateTime", "fxFetchedAt", "fxProvider", "fxFreshness", "nativeMarketValue",
            "baseCurrency", "baseCurrencyMarketValue", "valuationFreshness", "calculatedAt", "formulaVersion", "warnings");
        string quoteState = String(r, "quoteFreshness"), fxState = String(r, "fxFreshness"), state = String(r, "valuationFreshness");
        var warnings = Property(r, "warnings");
        if (quoteState is not ("FRESH" or "STALE" or "NEVER_FETCHED") || fxState is not ("FRESH" or "STALE" or "NEVER_FETCHED")
            || state is not ("FRESH" or "STALE" or "PARTIAL" or "UNAVAILABLE") || String(r, "baseCurrency") != "CNY"
            || String(r, "formulaVersion") != "REFERENCE_VALUATION_V1" || warnings.ValueKind != JsonValueKind.Array || warnings.GetArrayLength() > 8
            || Property(r, "fxRequired").ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
        var codes = warnings.EnumerateArray().Select(w => w.ValueKind == JsonValueKind.String ? w.GetString()! : throw Invalid()).ToArray();
        if (codes.Any(c => c is not ("INVALID_INPUT" or "QUOTE_MISSING" or "QUOTE_STALE" or "FX_MISSING" or "FX_STALE"))) throw Invalid();
        string? baseValue = AssetDecimal(r, "baseCurrencyMarketValue");
        if ((state is "FRESH" or "STALE") != (baseValue is not null)) throw Invalid();
        return new(AssetCurrency(r, "quoteCurrency"), AssetDecimal(r, "quotePrice"), AssetTime(r, "quoteTime"), AssetTime(r, "quoteFetchedAt"),
            AssetText(r, "quoteProvider", 30), quoteState, Property(r, "fxRequired").GetBoolean(), AssetCurrency(r, "fxBaseCurrency"),
            AssetCurrency(r, "fxQuoteCurrency"), AssetDecimal(r, "fxRate"), AssetTime(r, "fxRateTime"), AssetTime(r, "fxFetchedAt"),
            AssetText(r, "fxProvider", 30), fxState, AssetDecimal(r, "nativeMarketValue"), "CNY", baseValue, state,
            AssetTime(r, "calculatedAt") ?? throw Invalid(), "REFERENCE_VALUATION_V1", codes);
    }
}
