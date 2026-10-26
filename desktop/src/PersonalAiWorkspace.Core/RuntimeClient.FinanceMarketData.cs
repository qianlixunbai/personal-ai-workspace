using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceMarketSample(string Symbol, string Price, string Time, string FetchedAt, string Exchange, string Date, string TimeZone);
public sealed record FinanceMarketRights(bool Display, bool DisplayMemory, bool Persistence, bool Offline, bool Valuation);
public sealed record FinanceMarketStatus(string Provider, bool Enabled, bool KeyConfigured, string Result, FinanceMarketSample? Quote,
    FinanceMarketSample? Fx, FinanceMarketRights Rights, string LicenseStatus, string ProviderValidation, string DataContext);

public sealed partial class RuntimeClient
{
    public Task<FinanceMarketStatus> GetFinanceMarketDataAsync(CancellationToken token) => MarketRequest(HttpMethod.Get, "", null, token);
    public async Task<FinanceMarketStatus> RefreshFinanceMarketDataAsync(string target, CancellationToken token)
    {
        byte[] payload;
        if (target is "reference:AAPL" or "reference:SPY")
            payload = JsonSerializer.SerializeToUtf8Bytes(new { referenceSymbol = target[10..] });
        else {
            if (target != "" && !ValidFinanceId(target)) throw new DesktopException(DesktopError.InvalidRequest);
            payload = JsonSerializer.SerializeToUtf8Bytes(new { positionId = target });
        }
        var response = await MarketRequest(HttpMethod.Post, "/refresh", payload, token);
        if (response.Result == "UPDATED" && (target == "" ? response.Fx is null : response.Quote is null
            || target.StartsWith("reference:", StringComparison.Ordinal) && response.Quote.Symbol != target[10..])) throw Invalid();
        return response;
    }
    // Native window only; no WebMessage configuration command or user-writable rights flags.
    public async Task<FinanceMarketStatus> ConfigureFinanceMarketDataAsync(string apiKey, CancellationToken token)
    {
        if (!Regex.IsMatch(apiKey, @"\A[A-Za-z0-9_-]{0,256}\z")) throw new DesktopException(DesktopError.InvalidRequest);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new { apiKey });
        try { return await MarketRequest(HttpMethod.Post, "/configuration", payload, token); }
        finally { System.Security.Cryptography.CryptographicOperations.ZeroMemory(payload); }
    }
    private async Task<FinanceMarketStatus> MarketRequest(HttpMethod method, string path, byte[]? payload, CancellationToken token)
    {
        using var body = await SendAsync(method, "/api/v1/finance/market-data" + path, payload, true, HttpStatusCode.OK, token,
            endpointErrorMap: FinanceError, maximumResponse: 8192, requestTimeout: TimeSpan.FromSeconds(30));
        var r = body.RootElement;
        MemoryFields(r, "provider", "enabled", "keyConfigured", "result", "quote", "fx", "rights", "licenseStatus", "providerValidation", "dataContext");
        string result = String(r, "result"), validation = String(r, "providerValidation"), context = String(r, "dataContext");
        if (String(r, "provider") != "MASSIVE" || String(r, "licenseStatus") != "PERSONAL_DISPLAY_ONLY"
            || Property(r, "enabled").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || Property(r, "keyConfigured").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || context is not ("SYNTHETIC" or "UNVERIFIED") || validation is not ("NOT_RUN" or "RESPONSE_VALIDATED")
            || result is not ("IDLE" or "SAVED" or "UPDATED" or "KEY_REQUIRED" or "UNSUPPORTED" or "RATE_LIMITED" or "ACCESS_DENIED" or "PROVIDER_FAILED" or "IDENTITY_MISMATCH" or "INVALID_DATA" or "NO_DATA" or "LICENSE_UNVERIFIED")) throw Invalid();
        var rights = Property(r, "rights");
        MemoryFields(rights, "display", "displayMemory", "persistence", "offline", "valuation");
        if (Property(rights, "display").ValueKind != JsonValueKind.True || Property(rights, "displayMemory").ValueKind != JsonValueKind.True
            || Property(rights, "persistence").ValueKind != JsonValueKind.False || Property(rights, "offline").ValueKind != JsonValueKind.False
            || Property(rights, "valuation").ValueKind != JsonValueKind.False) throw Invalid();
        var quote = MarketSample(Property(r, "quote"), false); var fx = MarketSample(Property(r, "fx"), true);
        bool enabled = Property(r, "enabled").GetBoolean(), configured = Property(r, "keyConfigured").GetBoolean();
        bool hasData = quote is not null || fx is not null;
        if (enabled != configured || quote is not null && fx is not null || hasData != (result == "UPDATED")
            || hasData != (validation == "RESPONSE_VALIDATED") || hasData && (!enabled || path != "/refresh")) throw Invalid();
        return new("MASSIVE", enabled, configured, result, quote, fx, new(true, true, false, false, false), "PERSONAL_DISPLAY_ONLY", validation, context);
    }
    private static FinanceMarketSample? MarketSample(JsonElement s, bool fx)
    {
        if (s.ValueKind == JsonValueKind.Null) return null;
        MemoryFields(s, "symbol", "price", "time", "fetchedAt", "exchange", "date", "timeZone");
        string symbol = String(s, "symbol"), price = String(s, "price"), time = String(s, "time"), fetched = String(s, "fetchedAt"),
            exchange = String(s, "exchange"), date = String(s, "date"), zone = String(s, "timeZone");
        if (!(fx ? symbol == "USD/CNY" : Regex.IsMatch(symbol, @"\A[A-Z][A-Z0-9.-]{0,14}\z"))
            || !Regex.IsMatch(price, fx ? @"\A[0-9]{1,20}(?:\.[0-9]{1,12})?\z" : @"\A[0-9]{1,20}(?:\.[0-9]{1,8})?\z")
            || !price.Any(c => c is >= '1' and <= '9') || !MarketInstant(time) || !MarketInstant(fetched)
            || !DateOnly.TryParseExact(date, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out _)
            || (fx ? zone != "UTC" || exchange != "FOREX" : zone != "America/New_York" || exchange is not ("XNAS" or "XNYS" or "ARCX" or "XASE" or "BATS" or "IEXG"))) throw Invalid();
        return new(symbol, price, time, fetched, exchange, date, zone);
    }
    private static bool MarketInstant(string s) => s.Length <= 40 && s.EndsWith('Z') && DateTimeOffset.TryParse(s, out _);
}
