import { exactFields, isObject } from './contracts'
import { financeId } from './finance'

export interface DashboardFlow { month: string; income: string; expense: string; net: string }
export interface FinanceDashboard {
  currency: 'CNY'; asOf: string; businessZone: 'Asia/Shanghai'; cnyAccountBalance: string
  monthIncome: string; monthExpense: string; monthNet: string; monthlyCashFlow: DashboardFlow[]
  cashFlowPlot: { income: number[]; expense: number[]; zero: number }
  allocation: { items: { id: string; name: string; value: string; percentage: string }[]; total: string; complete: boolean
    referenceTotal: string; assetTotal: string; excludedCurrency: string; excludedUnpriced: string; excludedQuantity: string; excludedValue: string }
  recentTransactions: { id: string; type: string; amount: string; categoryName: string | null; accountName: string | null; transactedAt: string }[]
}
const amount = (v: unknown) => typeof v === 'string' && /^-?[0-9]{1,60}(?:\.[0-9]{1,16})?$/.test(v)
const count = (v: unknown): v is string => typeof v === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(v) && BigInt(v) <= 9223372036854775807n
const text = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= 256
const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(v) && !Number.isNaN(Date.parse(v))
export function isFinanceDashboard(v: unknown): v is FinanceDashboard {
  if (!isObject(v) || !exactFields(v, ['currency', 'asOf', 'businessZone', 'cnyAccountBalance', 'monthIncome', 'monthExpense', 'monthNet', 'monthlyCashFlow', 'allocation', 'recentTransactions', 'cashFlowPlot'])
    || v.currency !== 'CNY' || v.businessZone !== 'Asia/Shanghai' || !time(v.asOf)
    || !['cnyAccountBalance', 'monthIncome', 'monthExpense', 'monthNet'].every(k => amount(v[k]))
    || !Array.isArray(v.monthlyCashFlow) || v.monthlyCashFlow.length !== 6) return false
  const year = Number(v.asOf.slice(0, 4)), month = Number(v.asOf.slice(5, 7))
  if (!v.monthlyCashFlow.every((f, i) => {
    const d = new Date(Date.UTC(year, month - 6 + i, 1))
    return isObject(f) && exactFields(f, ['month', 'income', 'expense', 'net']) && f.month === d.toISOString().slice(0, 7)
      && amount(f.income) && amount(f.expense) && amount(f.net)
  })) return false
  const p = v.cashFlowPlot
  const coordinate = (n: unknown) => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 10000
  if (!isObject(p) || !exactFields(p, ['income', 'expense', 'zero']) || !coordinate(p.zero)
    || !['income', 'expense'].every(k => Array.isArray(p[k]) && p[k].length === 6 && p[k].every(coordinate))) return false
  const a = v.allocation
  if (!isObject(a) || !exactFields(a, ['items', 'total', 'complete', 'referenceTotal', 'assetTotal', 'excludedCurrency', 'excludedUnpriced', 'excludedQuantity', 'excludedValue'])
    || !count(a.total) || !count(a.assetTotal) || !count(a.excludedCurrency) || !count(a.excludedUnpriced) || !count(a.excludedQuantity) || !count(a.excludedValue)
    || BigInt(a.total) + BigInt(a.excludedCurrency) + BigInt(a.excludedUnpriced) + BigInt(a.excludedQuantity) + BigInt(a.excludedValue) !== BigInt(a.assetTotal)
    || !amount(a.referenceTotal) || typeof a.complete !== 'boolean' || a.complete !== (BigInt(a.total) <= 20n)
    || !Array.isArray(a.items) || BigInt(a.items.length) !== (BigInt(a.total) < 20n ? BigInt(a.total) : 20n)) return false
  let previous = 0n
  if (!a.items.every(item => {
    if (!isObject(item) || !exactFields(item, ['id', 'name', 'value', 'percentage']) || !financeId(item.id) || BigInt(item.id) <= previous
      || !text(item.name) || !amount(item.value) || typeof item.percentage !== 'string' || !/^(?:[0-9]{1,2}\.[0-9]{4}|100\.0000)$/.test(item.percentage)) return false
    previous = BigInt(item.id); return true
  })) return false
  return Array.isArray(v.recentTransactions) && v.recentTransactions.length <= 5
    && v.recentTransactions.every(t => isObject(t) && exactFields(t, ['id', 'type', 'amount', 'categoryName', 'accountName', 'transactedAt'])
      && financeId(t.id) && typeof t.type === 'string' && t.type.length > 0 && t.type.length <= 50 && amount(t.amount) && time(t.transactedAt)
      && (t.categoryName === null || text(t.categoryName)) && (t.accountName === null || text(t.accountName)))
    && new Set(v.recentTransactions.map(t => t.id)).size === v.recentTransactions.length
}
