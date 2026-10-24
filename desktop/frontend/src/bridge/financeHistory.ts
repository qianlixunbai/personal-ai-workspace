import { exactFields, isObject } from './contracts'
import { financeId } from './finance'

export interface FinanceHistoryQuery { positionId: string; accountId: string; instrumentId: string; type: string; correctionStatus: string; from: string; to: string; cursor: string; size: number }
interface Account { id: string; displayName: string }
interface Instrument { id: string; symbol: string; name: string; market: string; assetClass: string; quoteCurrency: string }
export interface HistoryValues { quantity: string | null; unitPrice: string | null; grossAmount: string | null; feeAmount: string | null; taxAmount: string | null; netAmount: string | null; releasedCostAmount: string | null; realizedProfitLoss: string | null; note: string | null; externalReference: string | null }
export interface HistoryReceipt { accountBalanceAfter: string | null; positionQuantityAfter: string | null; positionAverageCostAfter: string | null; positionTotalCostAfter: string | null; positionRealizedProfitLossAfter: string | null; positionStatusAfter: string | null }
export interface HistoryPosition { quantity: string | null; averageCost: string | null; totalCost: string | null; cumulativeRealizedProfitLoss: string | null; status: string }
export interface FinanceHistoryItem extends Omit<HistoryValues, 'note' | 'externalReference'> { logicalTransactionId: string; positionId: string; account: Account; instrument: Instrument; transactionType: string; effectiveTradeTime: string; correctionStatus: string; effective: boolean; correctionCreatedAt: string | null }
export interface FinanceHistoryPage { records: FinanceHistoryItem[]; nextCursor: string | null; hasMore: boolean; size: number }
export interface FinanceHistoryDetail { logicalTransactionId: string; transactionType: string; correctionStatus: string; effective: boolean; effectiveTradeTime: string; settlementTime: string | null; account: Account; instrument: Instrument; originalBusinessValues: HistoryValues; effectiveBusinessValues: HistoryValues | null; postingReceipt: HistoryReceipt | null; correctionFinalReceipt: HistoryReceipt | null; correction: { createdAt: string; reason: string | null; reversalTransactionId: string; replacementTransactionId: string | null } | null; currentPosition: HistoryPosition }
export interface HistoryFact { role: string; physicalFactId: string; factType: string; receiptApplicability: string; businessValues: HistoryValues }
export interface HistoryEvent { eventKind: string; createdAt: string; physicalFactId: string | null; originalFactId: string | null; factType: string | null; reason: string | null; receiptApplicability: string; businessValues: HistoryValues | null; postingReceipt: HistoryReceipt | null; correctionFinalReceipt: HistoryReceipt | null; facts: HistoryFact[] }
export interface FinanceHistoryTimeline { logicalTransactionId: string; correctionStatus: string; events: HistoryEvent[]; currentPosition: HistoryPosition }

const types = ['OPENING_POSITION', 'BUY', 'SELL', 'DIVIDEND']
const statuses = ['UNCHANGED', 'REVERSED', 'REPLACED']
const amounts = ['quantity', 'unitPrice', 'grossAmount', 'feeAmount', 'taxAmount', 'netAmount', 'releasedCostAmount', 'realizedProfitLoss']
const nullable = (v: unknown, check: (x: unknown) => boolean) => v === null || check(v)
const text = (v: unknown, max: number) => typeof v === 'string' && v.length <= max
export const historyInstant = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v) && !Number.isNaN(Date.parse(v))
const decimal = (v: unknown, scale: number) => nullable(v, x => typeof x === 'string' && new RegExp(`^-?[0-9]{1,60}\\.[0-9]{${scale}}$`).test(x))
const cursor = (v: unknown) => typeof v === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(v)
export function isFinanceHistoryQuery(v: unknown): v is FinanceHistoryQuery {
  return isObject(v) && exactFields(v, ['positionId', 'accountId', 'instrumentId', 'type', 'correctionStatus', 'from', 'to', 'cursor', 'size'])
    && ['positionId', 'accountId', 'instrumentId'].every(k => v[k] === '' || financeId(v[k])) && (v.type === '' || types.includes(v.type as string))
    && (v.correctionStatus === '' || statuses.includes(v.correctionStatus as string)) && ['from', 'to'].every(k => v[k] === '' || historyInstant(v[k]))
    && (v.cursor === '' || cursor(v.cursor)) && Number.isInteger(v.size) && (v.size as number) >= 1 && (v.size as number) <= 20
}
function identity(v: Record<string, unknown>) {
  const a = v.account, i = v.instrument
  return isObject(a) && exactFields(a, ['id', 'displayName']) && financeId(a.id) && text(a.displayName, 100) && a.displayName !== ''
    && isObject(i) && exactFields(i, ['id', 'symbol', 'name', 'market', 'assetClass', 'quoteCurrency']) && financeId(i.id)
    && text(i.symbol, 30) && i.symbol !== '' && text(i.name, 100) && i.name !== '' && typeof i.quoteCurrency === 'string' && /^[A-Z]{3}$/.test(i.quoteCurrency)
    && ['US', 'HK', 'CN', 'JP', 'KR', 'CRYPTO', 'OTC', 'FUND', 'OTHER', 'UNKNOWN'].includes(i.market as string) && ['STOCK', 'ETF', 'FUND', 'BOND', 'CRYPTO'].includes(i.assetClass as string)
}
const state = (v: Record<string, unknown>) => types.includes(v.transactionType as string) && statuses.includes(v.correctionStatus as string) && typeof v.effective === 'boolean' && v.effective === (v.correctionStatus !== 'REVERSED')
function values(v: unknown, allowNull = false): v is HistoryValues {
  return allowNull && v === null || isObject(v) && exactFields(v, [...amounts, 'note', 'externalReference']) && amounts.every(k => decimal(v[k], ['quantity', 'unitPrice'].includes(k) ? 8 : 2)) && nullable(v.note, x => text(x, 1000)) && nullable(v.externalReference, x => text(x, 100))
}
function receipt(v: unknown): v is HistoryReceipt | null {
  return v === null || isObject(v) && exactFields(v, ['accountBalanceAfter', 'positionQuantityAfter', 'positionAverageCostAfter', 'positionTotalCostAfter', 'positionRealizedProfitLossAfter', 'positionStatusAfter'])
    && ['accountBalanceAfter', 'positionQuantityAfter', 'positionAverageCostAfter', 'positionTotalCostAfter', 'positionRealizedProfitLossAfter'].every(k => decimal(v[k], ['positionQuantityAfter', 'positionAverageCostAfter'].includes(k) ? 8 : 2)) && [null, 'OPEN', 'CLOSED'].includes(v.positionStatusAfter as string | null)
}
function position(v: unknown): v is HistoryPosition {
  return isObject(v) && exactFields(v, ['quantity', 'averageCost', 'totalCost', 'cumulativeRealizedProfitLoss', 'status'])
    && ['quantity', 'averageCost', 'totalCost', 'cumulativeRealizedProfitLoss'].every(k => decimal(v[k], ['quantity', 'averageCost'].includes(k) ? 8 : 2)) && ['OPEN', 'CLOSED'].includes(v.status as string)
}
export function isFinanceHistoryPage(v: unknown, q: FinanceHistoryQuery): v is FinanceHistoryPage {
  return isObject(v) && exactFields(v, ['records', 'nextCursor', 'hasMore', 'size']) && v.size === q.size && typeof v.hasMore === 'boolean'
    && nullable(v.nextCursor, cursor) && v.hasMore === (v.nextCursor !== null) && v.nextCursor !== q.cursor && Array.isArray(v.records) && v.records.length <= q.size && (!v.hasMore || v.records.length === q.size)
    && v.records.every(p => isObject(p) && exactFields(p, ['logicalTransactionId', 'positionId', 'account', 'instrument', 'transactionType', 'effectiveTradeTime', ...amounts, 'correctionStatus', 'effective', 'correctionCreatedAt'])
      && financeId(p.logicalTransactionId) && financeId(p.positionId) && identity(p) && state(p) && historyInstant(p.effectiveTradeTime) && nullable(p.correctionCreatedAt, historyInstant)
      && (p.correctionStatus === 'UNCHANGED') === (p.correctionCreatedAt === null)
      && amounts.every(k => decimal(p[k], ['quantity', 'unitPrice'].includes(k) ? 8 : 2)) && (!q.positionId || p.positionId === q.positionId)
      && (!q.accountId || (p.account as Account).id === q.accountId) && (!q.instrumentId || (p.instrument as Instrument).id === q.instrumentId) && (!q.type || p.transactionType === q.type) && (!q.correctionStatus || p.correctionStatus === q.correctionStatus))
    && new Set(v.records.map(p => p.logicalTransactionId)).size === v.records.length
}
export function isFinanceHistoryDetail(v: unknown): v is FinanceHistoryDetail {
  if (!isObject(v) || !exactFields(v, ['logicalTransactionId', 'transactionType', 'correctionStatus', 'effective', 'effectiveTradeTime', 'settlementTime', 'account', 'instrument', 'originalBusinessValues', 'effectiveBusinessValues', 'postingReceipt', 'correctionFinalReceipt', 'correction', 'currentPosition'])
    || !financeId(v.logicalTransactionId) || !identity(v) || !state(v) || !historyInstant(v.effectiveTradeTime) || !nullable(v.settlementTime, historyInstant)
    || !values(v.originalBusinessValues) || !values(v.effectiveBusinessValues, true) || !receipt(v.postingReceipt) || !receipt(v.correctionFinalReceipt) || !position(v.currentPosition)) return false
  const c = v.correction
  return (v.correctionStatus === 'UNCHANGED') === (c === null) && (v.correctionStatus === 'REPLACED') === (v.effectiveBusinessValues !== null)
    && (c === null ? v.correctionFinalReceipt === null : isObject(c) && exactFields(c, ['createdAt', 'reason', 'reversalTransactionId', 'replacementTransactionId']) && historyInstant(c.createdAt) && nullable(c.reason, x => text(x, 1000))
      && financeId(c.reversalTransactionId) && nullable(c.replacementTransactionId, financeId) && (v.correctionStatus === 'REPLACED') === (c.replacementTransactionId !== null) && v.correctionFinalReceipt !== null)
}
export function isFinanceHistoryTimeline(v: unknown): v is FinanceHistoryTimeline {
  if (!isObject(v) || !exactFields(v, ['logicalTransactionId', 'correctionStatus', 'events', 'currentPosition']) || !financeId(v.logicalTransactionId) || !statuses.includes(v.correctionStatus as string) || !position(v.currentPosition)
    || !Array.isArray(v.events) || v.events.length !== (v.correctionStatus === 'UNCHANGED' ? 1 : 2)) return false
  for (const e of v.events) {
    if (!isObject(e) || !exactFields(e, ['eventKind', 'createdAt', 'physicalFactId', 'originalFactId', 'factType', 'reason', 'receiptApplicability', 'businessValues', 'postingReceipt', 'correctionFinalReceipt', 'facts'])
      || !historyInstant(e.createdAt) || !nullable(e.physicalFactId, financeId) || !nullable(e.originalFactId, financeId) || !nullable(e.factType, x => [...types, 'REVERSAL'].includes(x as string)) || !nullable(e.reason, x => text(x, 1000))
      || !['POSTING_TIME', 'CORRECTION_FINAL'].includes(e.receiptApplicability as string) || !values(e.businessValues, true) || !receipt(e.postingReceipt) || !receipt(e.correctionFinalReceipt) || !Array.isArray(e.facts) || e.facts.length > 2) return false
    if (!e.facts.every(f => isObject(f) && exactFields(f, ['role', 'physicalFactId', 'factType', 'receiptApplicability', 'businessValues']) && ['GROUPED_REVERSAL', 'REPLACEMENT_FACT'].includes(f.role as string)
      && financeId(f.physicalFactId) && [...types, 'REVERSAL'].includes(f.factType as string) && ['NOT_APPLICABLE', 'CORRECTION_FINAL'].includes(f.receiptApplicability as string) && values(f.businessValues))) return false
  }
  const [o, c] = v.events as HistoryEvent[]
  if (!o || o.eventKind !== 'ORIGINAL_POSTING' || o.physicalFactId !== v.logicalTransactionId || o.originalFactId !== null || !types.includes(o.factType ?? '') || o.receiptApplicability !== 'POSTING_TIME' || o.businessValues === null || o.correctionFinalReceipt !== null || o.facts.length) return false
  if (!c) return true
  if (c.originalFactId !== v.logicalTransactionId || c.postingReceipt !== null || c.correctionFinalReceipt === null || c.receiptApplicability !== 'CORRECTION_FINAL') return false
  return v.correctionStatus === 'REVERSED'
    ? c.eventKind === 'STANDALONE_REVERSAL' && c.physicalFactId !== null && c.physicalFactId !== v.logicalTransactionId && c.factType === 'REVERSAL' && c.businessValues !== null && !c.facts.length
    : c.eventKind === 'REPLACEMENT_COMMAND' && c.physicalFactId === null && c.factType === null && c.businessValues === null && c.facts.length === 2
      && c.facts[0]?.role === 'GROUPED_REVERSAL' && c.facts[0].factType === 'REVERSAL' && c.facts[0].receiptApplicability === 'NOT_APPLICABLE'
      && c.facts[1]?.role === 'REPLACEMENT_FACT' && c.facts[1].factType === o.factType && c.facts[1].receiptApplicability === 'CORRECTION_FINAL' && new Set([v.logicalTransactionId, ...c.facts.map(f => f.physicalFactId)]).size === 3
}
