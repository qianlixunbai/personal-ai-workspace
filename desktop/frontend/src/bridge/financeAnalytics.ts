import { exactFields, isObject } from './contracts'

export interface FinanceSlice { key: string; label: string; value: string; percentage: string; start: number; end: number }
export interface FinanceDistribution { state: 'COMPLETE' | 'EMPTY' | 'UNSUPPORTED_SIGNED'; total: string; items: FinanceSlice[] }
export interface FinanceMonth { month: string; state: 'OBSERVED' | 'FUTURE'; income: string | null; expense: string | null; net: string | null; transactionCount: string | null }
export interface FinancePlot { income: (number | null)[]; expense: (number | null)[]; net: (number | null)[]; zero: number; ticks: { coordinate: number; amount: string }[] }
export interface FinanceAnnual {
  currency: 'CNY'; businessZone: 'Asia/Shanghai'; asOf: string; currentYear: number; year: number; years: number[]
  income: string; expense: string; net: string; transactionCount: string; months: FinanceMonth[]; plot: FinancePlot; expenseBreakdown: FinanceDistribution
}
export const analyticsAmount = (v: unknown): v is string => typeof v === 'string' && /^-?[0-9]{1,60}(?:\.[0-9]{1,16})?$/.test(v)
export const analyticsCount = (v: unknown): v is string => typeof v === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(v) && BigInt(v) <= 9223372036854775807n
export const analyticsYear = (v: unknown): v is number => Number.isInteger(v) && (v as number) >= 1900 && (v as number) <= 2199
const coordinate = (v: unknown): v is number => Number.isInteger(v) && (v as number) >= 0 && (v as number) <= 10000
export function isFinanceDistribution(v: unknown): v is FinanceDistribution {
  if (!isObject(v) || !exactFields(v, ['state', 'total', 'items']) || !analyticsAmount(v.total) || !Array.isArray(v.items) || v.items.length > 13) return false
  if (v.state === 'EMPTY' || v.state === 'UNSUPPORTED_SIGNED') return v.items.length === 0 && (v.state !== 'EMPTY' || /^0(?:\.0+)?$/.test(v.total))
  if (v.state !== 'COMPLETE' || v.items.length === 0 || v.total.startsWith('-') || /^0(?:\.0+)?$/.test(v.total)) return false
  let end = 0
  return v.items.every(s => {
    if (!isObject(s) || !exactFields(s, ['key', 'label', 'value', 'percentage', 'start', 'end'])
      || typeof s.key !== 'string' || s.key.length < 1 || s.key.length > 100 || typeof s.label !== 'string' || s.label.length < 1 || s.label.length > 256
      || !analyticsAmount(s.value) || s.value.startsWith('-') || typeof s.percentage !== 'string' || !/^(?:[0-9]{1,2}\.[0-9]{4}|100\.0000)$/.test(s.percentage)
      || !coordinate(s.start) || !coordinate(s.end) || s.start !== end || s.end < s.start) return false
    end = s.end; return true
  }) && end === 10000 && new Set(v.items.map(s => s.key)).size === v.items.length
}
export function isFinanceMonths(v: unknown): v is FinanceMonth[] {
  return Array.isArray(v) && v.length === 12 && v.every(m => isObject(m) && exactFields(m, ['month', 'state', 'income', 'expense', 'net', 'transactionCount'])
    && typeof m.month === 'string' && /^[12][0-9]{3}-(?:0[1-9]|1[0-2])$/.test(m.month)
    && (m.state === 'FUTURE' ? m.income === null && m.expense === null && m.net === null && m.transactionCount === null
      : m.state === 'OBSERVED' && analyticsAmount(m.income) && analyticsAmount(m.expense) && analyticsAmount(m.net) && analyticsCount(m.transactionCount)))
}
export function isFinancePlot(v: unknown, months: FinanceMonth[]): v is FinancePlot {
  return isObject(v) && exactFields(v, ['income', 'expense', 'net', 'zero', 'ticks']) && coordinate(v.zero)
    && ['income', 'expense', 'net'].every(k => Array.isArray(v[k]) && v[k].length === months.length && v[k].every((n, i) => months[i]!.state === 'FUTURE' ? n === null : coordinate(n)))
    && Array.isArray(v.ticks) && v.ticks.length === 5 && v.ticks.every((t, i) => isObject(t) && exactFields(t, ['coordinate', 'amount']) && t.coordinate === i * 2500 && analyticsAmount(t.amount))
}
export function isFinanceAnnual(v: unknown, requestedYear = 0): v is FinanceAnnual {
  if (!isObject(v) || !exactFields(v, ['currency', 'businessZone', 'asOf', 'currentYear', 'year', 'years', 'income', 'expense', 'net', 'transactionCount', 'months', 'plot', 'expenseBreakdown'])
    || v.currency !== 'CNY' || v.businessZone !== 'Asia/Shanghai' || typeof v.asOf !== 'string'
    || !/^[12][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]{1,9})?)?$/.test(v.asOf) || Number.isNaN(Date.parse(v.asOf))
    || !analyticsYear(v.currentYear) || v.currentYear !== Number(v.asOf.slice(0, 4)) || !analyticsYear(v.year) || v.year > v.currentYear || v.year !== (requestedYear || v.currentYear)
    || !Array.isArray(v.years) || v.years.length < 1 || v.years.length > 300 || v.years[0] !== v.currentYear || !v.years.every((y, i) => analyticsYear(y) && y <= (v.currentYear as number) && (i === 0 || y < (v.years as number[])[i - 1]!))
    || !analyticsAmount(v.income) || !analyticsAmount(v.expense) || !analyticsAmount(v.net) || !analyticsCount(v.transactionCount) || !isFinanceMonths(v.months)) return false
  return v.months.every((m, i) => m.month === `${v.year}-${String(i + 1).padStart(2, '0')}` && (m.state === 'FUTURE') === (m.month > (v.asOf as string).slice(0, 7)))
    && isFinancePlot(v.plot, v.months) && isFinanceDistribution(v.expenseBreakdown)
}
