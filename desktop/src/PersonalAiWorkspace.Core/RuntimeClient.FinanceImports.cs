using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceImportBatch(string ImportBatchId, string SourceFileName, string ConfirmedAt, string Status, int TotalRows, int WarningCount, string ContractVersion);
public sealed record FinanceImportPage(FinanceImportBatch[] Items, int Page, int Size, string Total);
public sealed record FinanceImportReference(int RowNumber, string TransactionId);
public sealed record FinanceImportImpact(string AccountId, int RowCount, string BalanceBefore, string Delta, string BalanceAfter);
public sealed record FinanceImportReceipt(string ImportSessionId, string ImportBatchId, string Status, string SourceFileName, string FileDigest,
    int TotalRows, int AcceptedRows, int CreatedCount, int SkippedCount, int WarningCount, FinanceImportReference[] Transactions,
    FinanceImportImpact[] AccountImpacts, string ConfirmedAt, string ContractVersion, string ResultDigest);
public sealed record FinanceImportSegment(FinanceImportReceipt Receipt, int TransactionPage, int AccountImpactPage, int SegmentSize, int AccountImpactCount, bool CompleteReceipt);

public sealed partial class RuntimeClient
{
    public static bool ValidFinanceBatchId(string id) => Regex.IsMatch(id, @"\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z");
    public static bool ValidFinanceImportQuery(int page, int size) => page is >= 0 and <= 100000 && size is >= 1 and <= 20;
    public async Task<FinanceImportPage> ListFinanceImportsAsync(int page, int size, CancellationToken token)
    {
        if (!ValidFinanceImportQuery(page, size)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson(FormattableString.Invariant($"/imports/transactions/batches?page={page}&size={size}"), token);
        var r = body.RootElement; MemoryFields(r, "items", "page", "size", "total");
        var items = Property(r, "items");
        if (MemoryInteger(r, "page") != page || MemoryInteger(r, "size") != size || items.ValueKind != JsonValueKind.Array || items.GetArrayLength() > size) throw Invalid();
        FinanceCount(r, "total");
        foreach (var b in items.EnumerateArray()) {
            MemoryFields(b, "importBatchId", "sourceFileName", "confirmedAt", "status", "totalRows", "warningCount", "contractVersion");
            ImportHeader(b);
        }
        var result = r.Deserialize<FinanceImportPage>(HistoryJson)!;
        if (result.Items.Select(b => b.ImportBatchId).Distinct().Count() != result.Items.Length) throw Invalid();
        return result;
    }
    public async Task<FinanceImportSegment> GetFinanceImportReceiptAsync(string id, int transactionPage, int accountImpactPage, CancellationToken token)
    {
        if (!ValidFinanceBatchId(id) || transactionPage is < 0 or > 499 || accountImpactPage is < 0 or > 499) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson(FormattableString.Invariant($"/imports/transactions/batches/{id}?transactionPage={transactionPage}&accountImpactPage={accountImpactPage}"), token);
        var r = body.RootElement;
        MemoryFields(r, "receipt", "transactionPage", "accountImpactPage", "segmentSize", "accountImpactCount", "completeReceipt");
        var b = Property(r, "receipt");
        MemoryFields(b, "importSessionId", "importBatchId", "status", "sourceFileName", "fileDigest", "totalRows", "acceptedRows", "createdCount", "skippedCount", "warningCount", "transactions", "accountImpacts", "confirmedAt", "contractVersion", "resultDigest");
        ImportHeader(b);
        if (String(b, "contractVersion") != "TRANSACTION_IMPORT_CONFIRM_V1" || String(b, "importBatchId") != id || !ValidFinanceBatchId(String(b, "importSessionId"))
            || new[] { "fileDigest", "resultDigest" }.Any(k => !Regex.IsMatch(String(b, k), @"\A[0-9a-f]{64}\z"))) throw Invalid();
        int count = MemoryInteger(b, "totalRows"), impactCount = MemoryInteger(r, "accountImpactCount");
        if (MemoryInteger(b, "acceptedRows") != count || MemoryInteger(b, "createdCount") != count || MemoryInteger(b, "skippedCount") != 0
            || MemoryInteger(r, "transactionPage") != transactionPage || MemoryInteger(r, "accountImpactPage") != accountImpactPage
            || MemoryInteger(r, "segmentSize") != 20 || impactCount < 1 || impactCount > count
            || transactionPage * 20 >= count || accountImpactPage * 20 >= impactCount
            || Property(r, "completeReceipt").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || Property(r, "completeReceipt").GetBoolean() != (count <= 20 && impactCount <= 20)) throw Invalid();
        var refs = Property(b, "transactions"); var impacts = Property(b, "accountImpacts");
        if (refs.ValueKind != JsonValueKind.Array || refs.GetArrayLength() != Math.Min(20, count - transactionPage * 20)
            || impacts.ValueKind != JsonValueKind.Array || impacts.GetArrayLength() != Math.Min(20, impactCount - accountImpactPage * 20)) throw Invalid();
        int last = 0;
        foreach (var row in refs.EnumerateArray()) {
            MemoryFields(row, "rowNumber", "transactionId"); int rowNumber = MemoryInteger(row, "rowNumber");
            if (rowNumber <= last || !ValidFinanceId(String(row, "transactionId"))) throw Invalid(); last = rowNumber;
        }
        long lastAccount = 0;
        foreach (var impact in impacts.EnumerateArray()) {
            MemoryFields(impact, "accountId", "rowCount", "balanceBefore", "delta", "balanceAfter");
            string account = String(impact, "accountId"); int rows = MemoryInteger(impact, "rowCount");
            if (!ValidFinanceId(account) || long.Parse(account, CultureInfo.InvariantCulture) <= lastAccount || rows < 1 || rows > count) throw Invalid();
            lastAccount = long.Parse(account, CultureInfo.InvariantCulture);
            foreach (string key in new[] { "balanceBefore", "delta", "balanceAfter" })
                if (!Regex.IsMatch(String(impact, key), @"\A-?[0-9]{1,16}\.[0-9]{2}\z")) throw Invalid();
        }
        var result = r.Deserialize<FinanceImportSegment>(HistoryJson)!;
        if (result.Receipt.Transactions.Select(x => x.TransactionId).Distinct().Count() != result.Receipt.Transactions.Length) throw Invalid();
        return result;
    }
    private static void ImportHeader(JsonElement b) {
        string name = String(b, "sourceFileName"), contract = String(b, "contractVersion");
        int count = MemoryInteger(b, "totalRows"), warnings = MemoryInteger(b, "warningCount");
        if (!ValidFinanceBatchId(String(b, "importBatchId")) || String(b, "status") != "CONFIRMED" || !HistoryInstant(String(b, "confirmedAt"))
            || name.Length is < 1 or > 255 || name.Any(c => char.IsControl(c) || c is '/' or '\\' or ':')
            || count is < 1 or > 10000 || warnings < 0 || warnings > count || contract.Length is < 1 or > 100) throw Invalid();
    }
}
