using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceTransaction(string Kind, string Id, string Type, string Amount, string Currency,
    string TransactedAt, string? Description, string AccountId, string AccountName, string? ToAccountId, string? ToAccountName);
public sealed record FinanceTransactionQuery(int Page, int Size, string AccountId, string StartDate, string EndDate, string Type,
    string CashFlowMonth = "", string CashFlowAsOf = "");
public sealed record FinanceTransactionPage(FinanceTransaction[] Items, int Page, int Size, string Total,
    string AccountId, string StartDate, string EndDate, string Type, string CashFlowMonth = "", string CashFlowAsOf = "");

public sealed partial class RuntimeClient
{
    public static bool ValidFinanceTransactionQuery(FinanceTransactionQuery query) => query.Page is >= 0 and <= 100000
        && query.Size is >= 1 and <= 20 && (query.AccountId == "" || ValidFinanceId(query.AccountId))
        && ValidFinanceDate(query.StartDate) && ValidFinanceDate(query.EndDate)
        && (query.StartDate == "" || query.EndDate == "" || string.CompareOrdinal(query.StartDate, query.EndDate) <= 0)
        && query.Type is "ALL" or "INCOME" or "EXPENSE" or "ADJUSTMENT" or "TRANSFER" or "REFUND"
        && ValidFinanceCashFlow(query);

    private static bool ValidFinanceCashFlow(FinanceTransactionQuery q)
    {
        if (q.CashFlowMonth == "" && q.CashFlowAsOf == "") return true;
        if (!Regex.IsMatch(q.CashFlowMonth, @"\A[0-9]{4}-[0-9]{2}\z") || !ValidFinanceDate(q.CashFlowMonth + "-01")
            || string.CompareOrdinal(q.CashFlowMonth, "9999-12") >= 0 || !DashboardTime(q.CashFlowAsOf)
            || q.StartDate != q.CashFlowMonth + "-01" || q.EndDate != "" || q.Type is not ("INCOME" or "EXPENSE")) return false;
        var month = DateTime.Parse(q.CashFlowMonth + "-01", CultureInfo.InvariantCulture);
        var current = DateTime.Parse(q.CashFlowAsOf[..7] + "-01", CultureInfo.InvariantCulture);
        int distance = (current.Year - month.Year) * 12 + current.Month - month.Month;
        return distance >= 0 && month.Year is >= 1900 and <= 2199;
    }
    // Preserve nanoseconds for exclusive comparisons; DateTime alone has only 100ns precision.
    private static string FinanceTimeKey(string text) => (text.Length == 16 ? text + ":00" : text).PadRight(20, '.').PadRight(29, '0');
    private static string FinanceCashFlowCutoff(FinanceTransactionQuery q)
    {
        var next = DateTime.Parse(q.CashFlowMonth + "-01", CultureInfo.InvariantCulture).AddMonths(1).ToString("yyyy-MM-dd'T'HH:mm", CultureInfo.InvariantCulture);
        return string.CompareOrdinal(FinanceTimeKey(q.CashFlowAsOf), FinanceTimeKey(next)) < 0 ? q.CashFlowAsOf : next;
    }

    private static bool ValidFinanceDate(string text) => text == "" || Regex.IsMatch(text, @"\A[0-9]{4}-[0-9]{2}-[0-9]{2}\z")
        && DateOnly.TryParseExact(text, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _);

    public async Task<FinanceTransactionPage> ListFinanceTransactionsAsync(FinanceTransactionQuery query, CancellationToken token)
    {
        if (!ValidFinanceTransactionQuery(query)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson(FormattableString.Invariant($"/transactions?page={query.Page}&size={query.Size}&accountId={query.AccountId}&startDate={query.StartDate}&endDate={query.EndDate}&type={query.Type}&cashFlowMonth={query.CashFlowMonth}&cashFlowAsOf={query.CashFlowAsOf}"), token);
        var root = body.RootElement;
        var scoped = root.TryGetProperty("cashFlowMonth", out _);
        if (scoped) MemoryFields(root, "items", "page", "size", "total", "accountId", "startDate", "endDate", "type", "cashFlowMonth", "cashFlowAsOf");
        else MemoryFields(root, "items", "page", "size", "total", "accountId", "startDate", "endDate", "type");
        if ((scoped ? String(root, "cashFlowMonth") : "") != query.CashFlowMonth
            || (scoped ? String(root, "cashFlowAsOf") : "") != query.CashFlowAsOf) throw Invalid();
        if (MemoryInteger(root, "page") != query.Page || MemoryInteger(root, "size") != query.Size
            || String(root, "accountId") != query.AccountId || String(root, "startDate") != query.StartDate
            || String(root, "endDate") != query.EndDate || String(root, "type") != query.Type) throw Invalid();
        var items = Property(root, "items");
        if (items.ValueKind != JsonValueKind.Array || items.GetArrayLength() > query.Size) throw Invalid();
        var parsed = items.EnumerateArray().Select(ParseFinanceTransaction).ToArray();
        if (parsed.Select(x => (x.Kind, x.Id)).Distinct().Count() != parsed.Length
            || parsed.Any(x => query.Type != "ALL" && x.Type != query.Type
                || query.AccountId != "" && x.AccountId != query.AccountId && x.ToAccountId != query.AccountId
                || query.StartDate != "" && string.CompareOrdinal(x.TransactedAt[..10], query.StartDate) < 0
                || query.EndDate != "" && string.CompareOrdinal(x.TransactedAt[..10], query.EndDate) > 0
                || query.CashFlowMonth != "" && (x.Kind != "TRANSACTION" || x.Currency != "CNY"
                    || string.CompareOrdinal(FinanceTimeKey(x.TransactedAt), FinanceTimeKey(FinanceCashFlowCutoff(query))) >= 0))) throw Invalid();
        return new(parsed, query.Page, query.Size, FinanceCount(root, "total"), query.AccountId, query.StartDate, query.EndDate, query.Type, query.CashFlowMonth, query.CashFlowAsOf);
    }

    public async Task<FinanceTransaction> GetFinanceTransactionAsync(string kind, string id, CancellationToken token)
    {
        if (kind is not ("TRANSACTION" or "TRANSFER") || !ValidFinanceId(id)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson("/transactions/" + kind + "/" + id, token);
        var result = ParseFinanceTransaction(body.RootElement);
        if (result.Kind != kind || result.Id != id) throw Invalid();
        return result;
    }

    private static FinanceTransaction ParseFinanceTransaction(JsonElement value)
    {
        MemoryFields(value, "kind", "id", "type", "amount", "currency", "transactedAt", "description", "accountId", "accountName", "toAccountId", "toAccountName");
        string kind = String(value, "kind"), id = String(value, "id"), type = String(value, "type"), currency = String(value, "currency"),
            time = String(value, "transactedAt"), account = String(value, "accountId"), name = String(value, "accountName");
        string? Optional(string key) => Property(value, key).ValueKind == JsonValueKind.Null ? null : String(value, key);
        string? description = Optional("description"), toId = Optional("toAccountId"), toName = Optional("toAccountName");
        if (kind is not ("TRANSACTION" or "TRANSFER") || !ValidFinanceId(id) || !ValidFinanceId(account) || name.Length is < 1 or > 256
            || type is not ("INCOME" or "EXPENSE" or "ADJUSTMENT" or "TRANSFER" or "REFUND") || description?.Length > 500
            || !Regex.IsMatch(currency, @"\A[A-Z]{3}\z") || time.Length is < 16 or > 29
            || !Regex.IsMatch(time, @"\A[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]{1,9})?)?\z")
            || !DateTime.TryParse(time, CultureInfo.InvariantCulture, DateTimeStyles.None, out _)
            || (kind == "TRANSFER" ? type != "TRANSFER" || toId is null || !ValidFinanceId(toId) || toId == account || toName is null || toName.Length is < 1 or > 256
                : toId is not null || toName is not null)) throw Invalid();
        return new(kind, id, type, FinanceAmount(value, "amount"), currency, time, description, account, name, toId, toName);
    }
}
