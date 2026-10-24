import { exactFields, isObject } from './contracts'
import { financeId } from './finance'
import { historyInstant } from './financeHistory'

export interface FinanceImportBatch { importBatchId: string; sourceFileName: string; confirmedAt: string; status: 'CONFIRMED'; totalRows: number; warningCount: number; contractVersion: string }
export interface FinanceImportPage { items: FinanceImportBatch[]; page: number; size: number; total: string }
export interface FinanceImportReceipt extends FinanceImportBatch { importSessionId: string; fileDigest: string; acceptedRows: number; createdCount: number; skippedCount: number; transactions: { rowNumber: number; transactionId: string }[]; accountImpacts: { accountId: string; rowCount: number; balanceBefore: string; delta: string; balanceAfter: string }[]; resultDigest: string }
export interface FinanceImportSegment { receipt: FinanceImportReceipt; transactionPage: number; accountImpactPage: number; segmentSize: number; accountImpactCount: number; completeReceipt: boolean }
export const batchId = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const integer = (v: unknown, min: number, max: number): v is number => Number.isInteger(v) && (v as number) >= min && (v as number) <= max
export const isImportQuery = (page: unknown, size: unknown) => integer(page, 0, 100000) && integer(size, 1, 20)
const fields = ['importBatchId', 'sourceFileName', 'confirmedAt', 'status', 'totalRows', 'warningCount', 'contractVersion']
function header(v: Record<string, unknown>) {
  return batchId(v.importBatchId) && v.status === 'CONFIRMED' && historyInstant(v.confirmedAt) && typeof v.sourceFileName === 'string'
    && v.sourceFileName.length > 0 && v.sourceFileName.length <= 255 && !/[\x00-\x1f\x7f/\\:]/.test(v.sourceFileName)
    && integer(v.totalRows, 1, 10000) && integer(v.warningCount, 0, v.totalRows)
    && typeof v.contractVersion === 'string' && v.contractVersion.length > 0 && v.contractVersion.length <= 100
}
export function isImportPage(v: unknown): v is FinanceImportPage {
  return isObject(v) && exactFields(v, ['items', 'page', 'size', 'total']) && isImportQuery(v.page, v.size)
    && typeof v.total === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(v.total) && BigInt(v.total) <= 9223372036854775807n
    && Array.isArray(v.items) && v.items.length <= (v.size as number) && v.items.every(b => isObject(b) && exactFields(b, fields) && header(b))
    && new Set(v.items.map(b => b.importBatchId)).size === v.items.length
}
export function isImportSegment(v: unknown): v is FinanceImportSegment {
  if (!isObject(v) || !exactFields(v, ['receipt', 'transactionPage', 'accountImpactPage', 'segmentSize', 'accountImpactCount', 'completeReceipt'])
    || !integer(v.transactionPage, 0, 499) || !integer(v.accountImpactPage, 0, 499) || v.segmentSize !== 20 || typeof v.completeReceipt !== 'boolean') return false
  const r = v.receipt
  if (!isObject(r) || !exactFields(r, [...fields, 'importSessionId', 'fileDigest', 'acceptedRows', 'createdCount', 'skippedCount', 'transactions', 'accountImpacts', 'resultDigest'])
    || !header(r) || !batchId(r.importSessionId) || r.contractVersion !== 'TRANSACTION_IMPORT_CONFIRM_V1'
    || ![r.fileDigest, r.resultDigest].every(d => typeof d === 'string' && /^[0-9a-f]{64}$/.test(d))
    || r.acceptedRows !== r.totalRows || r.createdCount !== r.totalRows || r.skippedCount !== 0
    || !integer(v.accountImpactCount, 1, r.totalRows as number) || v.transactionPage * 20 >= (r.totalRows as number) || v.accountImpactPage * 20 >= v.accountImpactCount
    || v.completeReceipt !== ((r.totalRows as number) <= 20 && v.accountImpactCount <= 20)) return false
  if (!Array.isArray(r.transactions) || r.transactions.length !== Math.min(20, (r.totalRows as number) - v.transactionPage * 20)
    || !r.transactions.every((t, i, rows) => isObject(t) && exactFields(t, ['rowNumber', 'transactionId']) && integer(t.rowNumber, 1, 2147483647)
      && financeId(t.transactionId) && (i === 0 || t.rowNumber > rows[i - 1].rowNumber))
    || new Set(r.transactions.map(t => t.transactionId)).size !== r.transactions.length) return false
  return Array.isArray(r.accountImpacts) && r.accountImpacts.length === Math.min(20, v.accountImpactCount - v.accountImpactPage * 20)
    && r.accountImpacts.every((a, i, rows) => isObject(a) && exactFields(a, ['accountId', 'rowCount', 'balanceBefore', 'delta', 'balanceAfter'])
      && financeId(a.accountId) && integer(a.rowCount, 1, r.totalRows as number) && (i === 0 || BigInt(a.accountId) > BigInt(rows[i - 1].accountId))
      && [a.balanceBefore, a.delta, a.balanceAfter].every(n => typeof n === 'string' && /^-?[0-9]{1,16}\.[0-9]{2}$/.test(n)))
}
