using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceMarketFx(string Rate, string Time, string FetchedAt, string Freshness);
public sealed record FinanceMarketStatus(string Provider, bool Enabled, bool KeyConfigured, int RetentionHours, string Result, FinanceMarketFx? Fx);

public sealed partial class RuntimeClient
{
    public Task<FinanceMarketStatus> GetFinanceMarketDataAsync(CancellationToken token) => MarketRequest(HttpMethod.Get, "", null, token);
    public Task<FinanceMarketStatus> RefreshFinanceMarketDataAsync(string positionId, CancellationToken token)
    {
        if (positionId != "" && !ValidFinanceId(positionId)) throw new DesktopException(DesktopError.InvalidRequest);
        return MarketRequest(HttpMethod.Post, "/refresh", JsonSerializer.SerializeToUtf8Bytes(new { positionId }), token);
    }
    // Native window only; no WebMessage configuration command.
    public async Task<FinanceMarketStatus> ConfigureFinanceMarketDataAsync(bool enabled, bool licensed, string apiKey, int retentionHours, CancellationToken token)
    {
        if (!Regex.IsMatch(apiKey, @"\A[A-Za-z0-9_-]{0,256}\z") || retentionHours is < 1 or > 168)
            throw new DesktopException(DesktopError.InvalidRequest);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new { enabled, licensed, apiKey, retentionHours });
        try { return await MarketRequest(HttpMethod.Post, "/configuration", payload, token); }
        finally { System.Security.Cryptography.CryptographicOperations.ZeroMemory(payload); }
    }
    private async Task<FinanceMarketStatus> MarketRequest(HttpMethod method, string path, byte[]? payload, CancellationToken token)
    {
        using var body = await SendAsync(method, "/api/v1/finance/market-data" + path, payload, true, HttpStatusCode.OK, token,
            endpointErrorMap: FinanceError, maximumResponse: 8192, requestTimeout: TimeSpan.FromSeconds(30));
        var r = body.RootElement;
        MemoryFields(r, "provider", "enabled", "keyConfigured", "retentionHours", "result", "fx");
        string result = String(r, "result");
        string baseResult = result.StartsWith("PARTIAL_", StringComparison.Ordinal) ? result[8..] : result;
        if (String(r, "provider") != "TWELVE_DATA" || Property(r, "enabled").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || Property(r, "keyConfigured").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || MemoryInteger(r, "retentionHours") is < 1 or > 168
            || baseResult is not ("IDLE" or "SAVED" or "UPDATED" or "CACHED" or "DISABLED" or "UNSUPPORTED" or "RATE_LIMITED" or "ACCESS_DENIED" or "PROVIDER_FAILED" or "IDENTITY_MISMATCH" or "INVALID_DATA")) throw Invalid();
        var f = Property(r, "fx"); FinanceMarketFx? fx = null;
        if (f.ValueKind != JsonValueKind.Null)
        {
            MemoryFields(f, "rate", "time", "fetchedAt", "freshness");
            string rate = String(f, "rate"), time = String(f, "time"), fetched = String(f, "fetchedAt"), freshness = String(f, "freshness");
            if (!Regex.IsMatch(rate, @"\A[0-9]{1,20}(?:\.[0-9]{1,12})?\z") || !rate.Any(c => c is >= '1' and <= '9')
                || !MarketInstant(time) || !MarketInstant(fetched) || freshness is not ("FRESH" or "STALE")) throw Invalid();
            fx = new(rate, time, fetched, freshness);
        }
        bool enabled = Property(r, "enabled").GetBoolean(), configured = Property(r, "keyConfigured").GetBoolean();
        if (enabled && !configured) throw Invalid();
        return new("TWELVE_DATA", enabled, configured, MemoryInteger(r, "retentionHours"), result, fx);
    }
    private static bool MarketInstant(string s) => s.Length <= 40 && s.EndsWith('Z') && DateTimeOffset.TryParse(s, out _);
}
