import { exactFields, isObject } from './contracts'
import { financeId } from './finance'

export const transactionTypes = ['ALL', 'INCOME', 'EXPENSE', 'ADJUSTMENT', 'TRANSFER', 'REFUND'] as const
export interface FinanceTransactionQuery { page: number; size: number; accountId: string; startDate: string; endDate: string; type: typeof transactionTypes[number]; cashFlowMonth?: string; cashFlowAsOf?: string }
export interface FinanceTransaction { kind: 'TRANSACTION' | 'TRANSFER'; id: string; type: Exclude<FinanceTransactionQuery['type'], 'ALL'>; amount: string; currency: string; transactedAt: string; description: string | null; accountId: string; accountName: string; toAccountId: string | null; toAccountName: string | null }
export interface FinanceTransactionPage extends FinanceTransactionQuery { items: FinanceTransaction[]; total: string }
const date = (text: unknown): text is string => typeof text === 'string' && (text === '' || /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(text)
  && !text.startsWith('0000') && !Number.isNaN(Date.parse(text)) && new Date(text).toISOString().slice(0, 10) === text)
export const financeTimeKey = (text: string) => (text.length === 16 ? text + ':00' : text).padEnd(20, '.').padEnd(29, '0')
export function cashFlowCutoff(month: string, asOf: string) {
  const next = new Date(month + '-01T00:00:00Z'); next.setUTCMonth(next.getUTCMonth() + 1)
  const upper = next.toISOString().slice(0, 10) + 'T00:00'
  return financeTimeKey(asOf) < financeTimeKey(upper) ? asOf : upper
}
function validCashFlow(value: Record<string, unknown>) {
  if (Object.hasOwn(value, 'cashFlowMonth') !== Object.hasOwn(value, 'cashFlowAsOf')
    || value.cashFlowMonth !== undefined && typeof value.cashFlowMonth !== 'string'
    || value.cashFlowAsOf !== undefined && typeof value.cashFlowAsOf !== 'string') return false
  const month = value.cashFlowMonth ?? '', asOf = value.cashFlowAsOf ?? ''
  if (month === '' && asOf === '') return true
  if (typeof month !== 'string' || !/^[0-9]{4}-[0-9]{2}$/.test(month) || !date(month + '-01') || month >= '9999-12'
    || typeof asOf !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/.test(asOf) || !date(asOf.slice(0, 10))) return false
  return month <= asOf.slice(0, 7) && month >= '1900-01' && month <= '2199-12' && value.startDate === month + '-01'
    && value.endDate === '' && (value.type === 'INCOME' || value.type === 'EXPENSE')
}
export function isFinanceTransactionQuery(value: unknown): value is FinanceTransactionQuery {
  return isObject(value) && Number.isInteger(value.page) && (value.page as number) >= 0 && (value.page as number) <= 100000
    && Number.isInteger(value.size) && (value.size as number) >= 1 && (value.size as number) <= 20
    && (value.accountId === '' || financeId(value.accountId)) && date(value.startDate) && date(value.endDate)
    && (!value.startDate || !value.endDate || value.startDate <= value.endDate) && transactionTypes.includes(value.type as FinanceTransactionQuery['type']) && validCashFlow(value)
}
export function isFinanceTransaction(value: unknown): value is FinanceTransaction {
  return isObject(value) && exactFields(value, ['kind', 'id', 'type', 'amount', 'currency', 'transactedAt', 'description', 'accountId', 'accountName', 'toAccountId', 'toAccountName'])
    && ['TRANSACTION', 'TRANSFER'].includes(value.kind as string) && financeId(value.id) && financeId(value.accountId)
    && typeof value.accountName === 'string' && value.accountName.length > 0 && value.accountName.length <= 256
    && transactionTypes.slice(1).includes(value.type as FinanceTransaction['type'])
    && typeof value.amount === 'string' && /^-?[0-9]{1,30}(?:\.[0-9]{1,8})?$/.test(value.amount)
    && typeof value.currency === 'string' && /^[A-Z]{3}$/.test(value.currency)
    && typeof value.transactedAt === 'string' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/.test(value.transactedAt) && date(value.transactedAt.slice(0, 10))
    && (value.description === null || typeof value.description === 'string' && value.description.length <= 500)
    && (value.kind === 'TRANSFER' ? value.type === 'TRANSFER' && financeId(value.toAccountId) && value.toAccountId !== value.accountId
      && typeof value.toAccountName === 'string' && value.toAccountName.length > 0 && value.toAccountName.length <= 256
      : value.toAccountId === null && value.toAccountName === null)
}
export function isFinanceTransactionPage(value: unknown): value is FinanceTransactionPage {
  const base = ['page', 'size', 'accountId', 'startDate', 'endDate', 'type', 'items', 'total']
  return isObject(value) && (exactFields(value, base) || exactFields(value, [...base, 'cashFlowMonth', 'cashFlowAsOf'])) && isFinanceTransactionQuery(value)
    && typeof value.total === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value.total) && BigInt(value.total) <= 9223372036854775807n
    && Array.isArray(value.items) && value.items.length <= value.size && value.items.every(item => isFinanceTransaction(item)
      && (value.type === 'ALL' || item.type === value.type) && (!value.accountId || item.accountId === value.accountId || item.toAccountId === value.accountId)
      && (!value.startDate || item.transactedAt.slice(0, 10) >= value.startDate) && (!value.endDate || item.transactedAt.slice(0, 10) <= value.endDate)
      && (!value.cashFlowMonth || item.kind === 'TRANSACTION' && item.currency === 'CNY'
        && financeTimeKey(item.transactedAt) < financeTimeKey(cashFlowCutoff(value.cashFlowMonth, value.cashFlowAsOf!))))
    && new Set(value.items.map(item => `${item.kind}:${item.id}`)).size === value.items.length
}
