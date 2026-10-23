using System.Globalization;
using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceAccount(string Id, string Name, string Type, string Currency, string Balance, string Status);
public sealed record FinanceBalance(string Currency, string Balance);
public sealed record FinanceOverview(string State, FinanceAccount? Account, FinanceBalance? CnyBalance, string? Username, string? AccountCount);
public sealed record FinanceAccountPage(FinanceAccount[] Items, int Page, int Size, string Total, string Status, string Sort);
public sealed record FinanceBudgetAvailability(string State);

public sealed partial class RuntimeClient
{
    public async Task<FinanceBudgetAvailability> GetFinanceBudgetsAsync(CancellationToken token)
    {
        using var body = await FinanceJson("/budgets", token);
        MemoryFields(body.RootElement, "state");
        string state = String(body.RootElement, "state");
        if (state is not ("UNSUPPORTED" or "DISABLED" or "NOT_BOUND" or "BINDING_INVALID" or "UNAVAILABLE")) throw Invalid();
        return new(state);
    }

    public async Task<FinanceOverview> GetFinanceOverviewAsync(CancellationToken token)
    {
        using var body = await FinanceJson("/overview", token);
        var root = body.RootElement;
        MemoryFields(root, "state", "account", "cnyBalance", "username", "accountCount");
        string state = String(root, "state");
        var account = Property(root, "account"); var total = Property(root, "cnyBalance");
        if (state != "READY")
        {
            if (state is not ("DISABLED" or "NOT_BOUND" or "BINDING_INVALID" or "UNAVAILABLE")
                || account.ValueKind != JsonValueKind.Null || total.ValueKind != JsonValueKind.Null
                || Property(root, "username").ValueKind != JsonValueKind.Null || Property(root, "accountCount").ValueKind != JsonValueKind.Null) throw Invalid();
            return new(state, null, null, null, null);
        }
        MemoryFields(total, "currency", "balance");
        string username = String(root, "username");
        if (String(total, "currency") != "CNY" || username.Length is < 1 or > 256) throw Invalid();
        var parsed = account.ValueKind == JsonValueKind.Null ? null : ParseFinanceAccount(account);
        if (parsed is not null && parsed.Currency != "CNY") throw Invalid();
        return new("READY", parsed, new("CNY", FinanceAmount(total)), username, FinanceCount(root, "accountCount"));
    }

    public async Task<FinanceAccountPage> ListFinanceAccountsAsync(int page, int size, string status, string sort, CancellationToken token)
    {
        if (!ValidFinanceQuery(page, size, status, sort)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson(FormattableString.Invariant($"/accounts?page={page}&size={size}&status={status}&sort={sort}"), token);
        var root = body.RootElement;
        MemoryFields(root, "items", "page", "size", "total", "status", "sort");
        var items = Property(root, "items");
        if (MemoryInteger(root, "page") != page || MemoryInteger(root, "size") != size
            || String(root, "status") != status || String(root, "sort") != sort
            || items.ValueKind != JsonValueKind.Array || items.GetArrayLength() > size) throw Invalid();
        var parsed = items.EnumerateArray().Select(ParseFinanceAccount).ToArray();
        if (parsed.Select(x => x.Id).Distinct().Count() != parsed.Length || parsed.Any(x => status != "ALL" && x.Status != status)) throw Invalid();
        return new(parsed, page, size, FinanceCount(root, "total"), status, sort);
    }

    public async Task<FinanceAccount> GetFinanceAccountAsync(string id, CancellationToken token)
    {
        if (!ValidFinanceId(id)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson("/accounts/" + id, token);
        var account = ParseFinanceAccount(body.RootElement);
        if (account.Id != id) throw Invalid();
        return account;
    }

    public static bool ValidFinanceId(string id) => Regex.IsMatch(id, @"\A[1-9][0-9]{0,18}\z")
        && long.TryParse(id, NumberStyles.None, CultureInfo.InvariantCulture, out _);
    public static bool ValidFinanceQuery(int page, int size, string status, string sort) => page is >= 0 and <= 100000
        && size is >= 1 and <= 20 && status is "ALL" or "ACTIVE" or "INACTIVE" && sort is "ID_ASC" or "ID_DESC" or "NAME_ASC";

    private Task<JsonDocument> FinanceJson(string path, CancellationToken token) => SendAsync(HttpMethod.Get, "/api/v1/finance" + path,
        null, true, HttpStatusCode.OK, token, endpointErrorMap: FinanceError, maximumResponse: 60 * 1024, requestTimeout: TimeSpan.FromSeconds(35));
    private static string FinanceAmount(JsonElement value, string key = "balance")
    {
        string text = String(value, key);
        if (!Regex.IsMatch(text, @"\A-?[0-9]{1,30}(?:\.[0-9]{1,8})?\z")) throw Invalid();
        return text;
    }
    private static string FinanceCount(JsonElement value, string key)
    {
        string text = String(value, key);
        if (!Regex.IsMatch(text, @"\A(?:0|[1-9][0-9]{0,18})\z") || !long.TryParse(text, out _)) throw Invalid();
        return text;
    }
    private static FinanceAccount ParseFinanceAccount(JsonElement value)
    {
        MemoryFields(value, "id", "name", "type", "currency", "balance", "status");
        string id = String(value, "id"), name = String(value, "name"), currency = String(value, "currency"), type = String(value, "type"), status = String(value, "status");
        if (!ValidFinanceId(id) || name.Length is < 1 or > 256 || !Regex.IsMatch(currency, @"\A[A-Z]{3}\z")
            || type.Length is < 1 or > 50 || status is not ("ACTIVE" or "INACTIVE")) throw Invalid();
        return new(id, name, type, currency, FinanceAmount(value), status);
    }
    private static DesktopError FinanceError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase");
        _ = String(root, "message"); _ = String(root, "phase");
        return ((int)status, String(root, "code")) switch
        {
            (400, "INVALID_REQUEST") => DesktopError.InvalidRequest,
            (403, "FINANCE_NOT_BOUND") => DesktopError.FinanceNotBound,
            (403, "FINANCE_BINDING_INVALID") => DesktopError.FinanceBindingInvalid,
            (404, "FINANCE_ACCOUNT_NOT_FOUND") => DesktopError.FinanceAccountNotFound,
            (404, "FINANCE_TRANSACTION_NOT_FOUND") => DesktopError.FinanceTransactionNotFound,
            (404, "FINANCE_ASSET_NOT_FOUND") => DesktopError.FinanceAssetNotFound,
            (503, "FINANCE_UNAVAILABLE") => DesktopError.FinanceUnavailable,
            (403, "POLICY_DENIED") => DesktopError.PolicyDenied,
            (500, "INTERNAL_ERROR") => DesktopError.InternalError,
            _ => throw Invalid()
        };
    }
}
