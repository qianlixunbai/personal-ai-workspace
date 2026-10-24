using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record FinanceHistoryQuery(string PositionId, string AccountId, string InstrumentId, string Type,
    string CorrectionStatus, string From, string To, string Cursor, int Size);
public sealed record FinanceHistoryAccount(string Id, string DisplayName);
public sealed record FinanceHistoryInstrument(string Id, string Symbol, string Name, string Market, string AssetClass, string QuoteCurrency);
public sealed record FinanceHistoryItem(string LogicalTransactionId, string PositionId, FinanceHistoryAccount Account, FinanceHistoryInstrument Instrument,
    string TransactionType, string EffectiveTradeTime, string? Quantity, string? UnitPrice, string? GrossAmount, string? FeeAmount, string? TaxAmount,
    string? NetAmount, string? ReleasedCostAmount, string? RealizedProfitLoss, string CorrectionStatus, bool Effective, string? CorrectionCreatedAt);
public sealed record FinanceHistoryPage(FinanceHistoryItem[] Records, string? NextCursor, bool HasMore, int Size);
public sealed record FinanceHistoryValues(string? Quantity, string? UnitPrice, string? GrossAmount, string? FeeAmount, string? TaxAmount,
    string? NetAmount, string? ReleasedCostAmount, string? RealizedProfitLoss, string? Note, string? ExternalReference);
public sealed record FinanceHistoryReceipt(string? AccountBalanceAfter, string? PositionQuantityAfter, string? PositionAverageCostAfter,
    string? PositionTotalCostAfter, string? PositionRealizedProfitLossAfter, string? PositionStatusAfter);
public sealed record FinanceHistoryPosition(string? Quantity, string? AverageCost, string? TotalCost, string? CumulativeRealizedProfitLoss, string Status);
public sealed record FinanceHistoryCorrection(string CreatedAt, string? Reason, string ReversalTransactionId, string? ReplacementTransactionId);
public sealed record FinanceHistoryDetail(string LogicalTransactionId, string TransactionType, string CorrectionStatus, bool Effective,
    string EffectiveTradeTime, string? SettlementTime, FinanceHistoryAccount Account, FinanceHistoryInstrument Instrument,
    FinanceHistoryValues OriginalBusinessValues, FinanceHistoryValues? EffectiveBusinessValues, FinanceHistoryReceipt? PostingReceipt,
    FinanceHistoryReceipt? CorrectionFinalReceipt, FinanceHistoryCorrection? Correction, FinanceHistoryPosition CurrentPosition);
public sealed record FinanceHistoryFact(string Role, string PhysicalFactId, string FactType, string ReceiptApplicability, FinanceHistoryValues BusinessValues);
public sealed record FinanceHistoryEvent(string EventKind, string CreatedAt, string? PhysicalFactId, string? OriginalFactId, string? FactType,
    string? Reason, string ReceiptApplicability, FinanceHistoryValues? BusinessValues, FinanceHistoryReceipt? PostingReceipt,
    FinanceHistoryReceipt? CorrectionFinalReceipt, FinanceHistoryFact[] Facts);
public sealed record FinanceHistoryTimeline(string LogicalTransactionId, string CorrectionStatus, FinanceHistoryEvent[] Events, FinanceHistoryPosition CurrentPosition);

public sealed partial class RuntimeClient
{
    private static readonly JsonSerializerOptions HistoryJson = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    private static readonly string[] HistoryAmounts = ["quantity", "unitPrice", "grossAmount", "feeAmount", "taxAmount", "netAmount", "releasedCostAmount", "realizedProfitLoss"];
    private static bool HistoryType(string t) => t is "OPENING_POSITION" or "BUY" or "SELL" or "DIVIDEND";
    private static bool HistoryStatus(string s) => s is "UNCHANGED" or "REVERSED" or "REPLACED";
    // Explicit UTC instants, retaining up to nanosecond source precision. Never convert to a local natural day.
    private static bool HistoryInstant(string s) => Regex.IsMatch(s, @"\A[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z\z")
        && DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out _);
    public static bool ValidFinanceHistoryQuery(FinanceHistoryQuery q) => q.Size is >= 1 and <= 20
        && new[] { q.PositionId, q.AccountId, q.InstrumentId }.All(s => s == "" || ValidFinanceId(s))
        && (q.Type == "" || HistoryType(q.Type)) && (q.CorrectionStatus == "" || HistoryStatus(q.CorrectionStatus))
        && (q.From == "" || HistoryInstant(q.From)) && (q.To == "" || HistoryInstant(q.To))
        && (q.Cursor == "" || PositionCursor(q.Cursor));

    public async Task<FinanceHistoryPage> ListFinanceHistoryAsync(FinanceHistoryQuery q, CancellationToken token)
    {
        if (!ValidFinanceHistoryQuery(q)) throw new DesktopException(DesktopError.InvalidRequest);
        string path = "/investment/transactions?size=" + q.Size.ToString(CultureInfo.InvariantCulture);
        foreach (var (key, value) in new[] { ("positionId", q.PositionId), ("accountId", q.AccountId), ("instrumentId", q.InstrumentId), ("type", q.Type),
            ("correctionStatus", q.CorrectionStatus), ("from", q.From), ("to", q.To), ("cursor", q.Cursor) })
            if (value != "") path += "&" + key + "=" + Uri.EscapeDataString(value);
        using var body = await FinanceJson(path, token); var r = body.RootElement;
        MemoryFields(r, "records", "nextCursor", "hasMore", "size");
        var rows = Property(r, "records"); string? next = AssetText(r, "nextCursor", 2048);
        if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > q.Size || MemoryInteger(r, "size") != q.Size
            || Property(r, "hasMore").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || Property(r, "hasMore").GetBoolean() != (next is not null) || next is not null && (!PositionCursor(next) || next == q.Cursor)
            || next is not null && rows.GetArrayLength() != q.Size) throw Invalid();
        foreach (var p in rows.EnumerateArray()) {
            MemoryFields(p, new[] { "logicalTransactionId", "positionId", "account", "instrument", "transactionType", "effectiveTradeTime", "correctionStatus", "effective", "correctionCreatedAt" }.Concat(HistoryAmounts).ToArray());
            HistoryId(p, "logicalTransactionId"); HistoryId(p, "positionId"); HistoryIdentity(p); HistoryState(p); HistoryTime(p, "effectiveTradeTime"); HistoryTime(p, "correctionCreatedAt", true);
            foreach (string k in HistoryAmounts) HistoryDecimal(p, k, k is "quantity" or "unitPrice" ? 8 : 2);
            if ((String(p, "correctionStatus") == "UNCHANGED") != (Property(p, "correctionCreatedAt").ValueKind == JsonValueKind.Null)) throw Invalid();
            if (q.PositionId != "" && String(p, "positionId") != q.PositionId || q.AccountId != "" && String(Property(p, "account"), "id") != q.AccountId
                || q.InstrumentId != "" && String(Property(p, "instrument"), "id") != q.InstrumentId || q.Type != "" && String(p, "transactionType") != q.Type
                || q.CorrectionStatus != "" && String(p, "correctionStatus") != q.CorrectionStatus) throw Invalid();
        }
        var result = r.Deserialize<FinanceHistoryPage>(HistoryJson)!;
        if (result.Records.Select(p => p.LogicalTransactionId).Distinct().Count() != result.Records.Length) throw Invalid();
        return result;
    }
    public async Task<FinanceHistoryDetail> GetFinanceHistoryDetailAsync(string id, CancellationToken token)
    {
        if (!ValidFinanceId(id)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson("/investment/transactions/" + id, token); var r = body.RootElement;
        MemoryFields(r, "logicalTransactionId", "transactionType", "correctionStatus", "effective", "effectiveTradeTime", "settlementTime", "account", "instrument",
            "originalBusinessValues", "effectiveBusinessValues", "postingReceipt", "correctionFinalReceipt", "correction", "currentPosition");
        if (HistoryId(r, "logicalTransactionId") != id) throw Invalid();
        HistoryIdentity(r); HistoryState(r); HistoryTime(r, "effectiveTradeTime"); HistoryTime(r, "settlementTime", true);
        HistoryValues(Property(r, "originalBusinessValues")); HistoryValues(Property(r, "effectiveBusinessValues"), true);
        HistoryReceipt(Property(r, "postingReceipt")); HistoryReceipt(Property(r, "correctionFinalReceipt")); HistoryPosition(Property(r, "currentPosition"));
        var c = Property(r, "correction"); string status = String(r, "correctionStatus");
        if (c.ValueKind != JsonValueKind.Null) {
            MemoryFields(c, "createdAt", "reason", "reversalTransactionId", "replacementTransactionId"); HistoryTime(c, "createdAt");
            AssetText(c, "reason", 1000); HistoryId(c, "reversalTransactionId"); HistoryId(c, "replacementTransactionId", true);
        }
        if ((status == "UNCHANGED") != (c.ValueKind == JsonValueKind.Null) || (status == "REPLACED") != (Property(r, "effectiveBusinessValues").ValueKind != JsonValueKind.Null)
            || status == "UNCHANGED" && Property(r, "correctionFinalReceipt").ValueKind != JsonValueKind.Null
            || status != "UNCHANGED" && (Property(r, "correctionFinalReceipt").ValueKind == JsonValueKind.Null
                || (status == "REPLACED") != (Property(c, "replacementTransactionId").ValueKind != JsonValueKind.Null))) throw Invalid();
        return r.Deserialize<FinanceHistoryDetail>(HistoryJson)!;
    }
    public async Task<FinanceHistoryTimeline> GetFinanceHistoryTimelineAsync(string id, CancellationToken token)
    {
        if (!ValidFinanceId(id)) throw new DesktopException(DesktopError.InvalidRequest);
        using var body = await FinanceJson("/investment/transactions/" + id + "/audit-timeline", token); var r = body.RootElement;
        MemoryFields(r, "logicalTransactionId", "correctionStatus", "events", "currentPosition");
        if (HistoryId(r, "logicalTransactionId") != id || !HistoryStatus(String(r, "correctionStatus"))) throw Invalid();
        HistoryPosition(Property(r, "currentPosition")); var events = Property(r, "events");
        if (events.ValueKind != JsonValueKind.Array || events.GetArrayLength() != (String(r, "correctionStatus") == "UNCHANGED" ? 1 : 2)) throw Invalid();
        foreach (var e in events.EnumerateArray()) {
            MemoryFields(e, "eventKind", "createdAt", "physicalFactId", "originalFactId", "factType", "reason", "receiptApplicability", "businessValues", "postingReceipt", "correctionFinalReceipt", "facts");
            HistoryTime(e, "createdAt"); HistoryId(e, "physicalFactId", true); HistoryId(e, "originalFactId", true); AssetText(e, "reason", 1000);
            string? t = AssetText(e, "factType", 30); if (t is not null && !HistoryType(t) && t != "REVERSAL") throw Invalid();
            if (String(e, "receiptApplicability") is not ("POSTING_TIME" or "CORRECTION_FINAL")) throw Invalid();
            HistoryValues(Property(e, "businessValues"), true); HistoryReceipt(Property(e, "postingReceipt")); HistoryReceipt(Property(e, "correctionFinalReceipt"));
            var facts = Property(e, "facts"); if (facts.ValueKind != JsonValueKind.Array || facts.GetArrayLength() > 2) throw Invalid();
            foreach (var f in facts.EnumerateArray()) {
                MemoryFields(f, "role", "physicalFactId", "factType", "receiptApplicability", "businessValues"); HistoryId(f, "physicalFactId"); HistoryValues(Property(f, "businessValues"));
                if (String(f, "role") is not ("GROUPED_REVERSAL" or "REPLACEMENT_FACT") || String(f, "receiptApplicability") is not ("NOT_APPLICABLE" or "CORRECTION_FINAL")
                    || !HistoryType(String(f, "factType")) && String(f, "factType") != "REVERSAL") throw Invalid();
            }
        }
        var p = r.Deserialize<FinanceHistoryTimeline>(HistoryJson)!; var original = p.Events[0];
        if (original.EventKind != "ORIGINAL_POSTING" || original.PhysicalFactId != id || original.OriginalFactId is not null || original.FactType is null || !HistoryType(original.FactType)
            || original.BusinessValues is null || original.CorrectionFinalReceipt is not null || original.ReceiptApplicability != "POSTING_TIME" || original.Facts.Length != 0) throw Invalid();
        if (p.Events.Length == 2) {
            var c = p.Events[1];
            if (c.OriginalFactId != id || c.PostingReceipt is not null || c.CorrectionFinalReceipt is null || c.ReceiptApplicability != "CORRECTION_FINAL") throw Invalid();
            if (p.CorrectionStatus == "REVERSED") {
                if (c.EventKind != "STANDALONE_REVERSAL" || c.PhysicalFactId is null || c.PhysicalFactId == id || c.FactType != "REVERSAL" || c.BusinessValues is null || c.Facts.Length != 0) throw Invalid();
            } else if (c.EventKind != "REPLACEMENT_COMMAND" || c.PhysicalFactId is not null || c.FactType is not null || c.BusinessValues is not null || c.Facts.Length != 2
                || c.Facts[0].Role != "GROUPED_REVERSAL" || c.Facts[0].FactType != "REVERSAL" || c.Facts[0].ReceiptApplicability != "NOT_APPLICABLE"
                || c.Facts[1].Role != "REPLACEMENT_FACT" || c.Facts[1].FactType != original.FactType || c.Facts[1].ReceiptApplicability != "CORRECTION_FINAL"
                || new[] { id, c.Facts[0].PhysicalFactId, c.Facts[1].PhysicalFactId }.Distinct().Count() != 3) throw Invalid();
        }
        return p;
    }
    private static string? HistoryId(JsonElement r, string key, bool nullable = false) {
        string? v = AssetText(r, key, 19); if (v is null ? !nullable : !ValidFinanceId(v)) throw Invalid(); return v;
    }
    private static void HistoryTime(JsonElement r, string key, bool nullable = false) {
        string? v = AssetText(r, key, 40); if (v is null ? !nullable : !HistoryInstant(v)) throw Invalid();
    }
    private static void HistoryDecimal(JsonElement r, string key, int scale) {
        string? v = AssetText(r, key, 100); if (v is not null && !Regex.IsMatch(v, @"\A-?[0-9]{1,60}\.[0-9]{" + scale + @"}\z")) throw Invalid();
    }
    private static void HistoryState(JsonElement r) {
        if (!HistoryType(String(r, "transactionType")) || !HistoryStatus(String(r, "correctionStatus"))
            || Property(r, "effective").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || Property(r, "effective").GetBoolean() != (String(r, "correctionStatus") != "REVERSED")) throw Invalid();
    }
    private static void HistoryIdentity(JsonElement r) {
        var a = Property(r, "account"); var i = Property(r, "instrument"); MemoryFields(a, "id", "displayName");
        MemoryFields(i, "id", "symbol", "name", "market", "assetClass", "quoteCurrency"); HistoryId(a, "id"); HistoryId(i, "id");
        foreach (var (v, key, max) in new[] { (a, "displayName", 100), (i, "symbol", 30), (i, "name", 100) })
            if (string.IsNullOrEmpty(AssetText(v, key, max))) throw Invalid();
        if (AssetCurrency(i, "quoteCurrency") is null || String(i, "market") is not ("US" or "HK" or "CN" or "JP" or "KR" or "CRYPTO" or "OTC" or "FUND" or "OTHER" or "UNKNOWN")
            || String(i, "assetClass") is not ("STOCK" or "ETF" or "FUND" or "BOND" or "CRYPTO")) throw Invalid();
    }
    private static void HistoryValues(JsonElement r, bool nullable = false) {
        if (nullable && r.ValueKind == JsonValueKind.Null) return;
        MemoryFields(r, HistoryAmounts.Concat(new[] { "note", "externalReference" }).ToArray());
        foreach (string k in HistoryAmounts) HistoryDecimal(r, k, k is "quantity" or "unitPrice" ? 8 : 2);
        AssetText(r, "note", 1000); AssetText(r, "externalReference", 100);
    }
    private static void HistoryReceipt(JsonElement r) {
        if (r.ValueKind == JsonValueKind.Null) return;
        MemoryFields(r, "accountBalanceAfter", "positionQuantityAfter", "positionAverageCostAfter", "positionTotalCostAfter", "positionRealizedProfitLossAfter", "positionStatusAfter");
        foreach (string k in new[] { "accountBalanceAfter", "positionQuantityAfter", "positionAverageCostAfter", "positionTotalCostAfter", "positionRealizedProfitLossAfter" })
            HistoryDecimal(r, k, k is "positionQuantityAfter" or "positionAverageCostAfter" ? 8 : 2);
        string? s = AssetText(r, "positionStatusAfter", 10); if (s is not (null or "OPEN" or "CLOSED")) throw Invalid();
    }
    private static void HistoryPosition(JsonElement r) {
        MemoryFields(r, "quantity", "averageCost", "totalCost", "cumulativeRealizedProfitLoss", "status");
        foreach (string k in new[] { "quantity", "averageCost", "totalCost", "cumulativeRealizedProfitLoss" }) HistoryDecimal(r, k, k is "quantity" or "averageCost" ? 8 : 2);
        if (String(r, "status") is not ("OPEN" or "CLOSED")) throw Invalid();
    }
}
