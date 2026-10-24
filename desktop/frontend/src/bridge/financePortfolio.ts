import { exactFields, isObject } from './contracts'
import type { PositionWarning } from './financeInvestments'

export interface FinancePortfolio {
  currency: 'CNY'; positionCount: number; openPositionCount: number; closedPositionCount: number
  openTotalCost: string; cumulativeRealizedProfitLoss: string
  referenceValuation: { baseCurrency: 'CNY'; value: string | null; valuedPositionCount: number; totalOpenPositionCount: number
    freshness: 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE'; warnings: PositionWarning[] }
}
const count = (v: unknown): v is number => Number.isInteger(v) && (v as number) >= 0 && (v as number) <= 2147483647
const amount = (v: unknown) => typeof v === 'string' && /^-?[0-9]{1,60}\.[0-9]{2}$/.test(v)
export function isFinancePortfolio(v: unknown): v is FinancePortfolio {
  if (!isObject(v) || !exactFields(v, ['currency', 'positionCount', 'openPositionCount', 'closedPositionCount', 'openTotalCost', 'cumulativeRealizedProfitLoss', 'referenceValuation'])
    || v.currency !== 'CNY' || !count(v.positionCount) || !count(v.openPositionCount) || !count(v.closedPositionCount)
    || v.openPositionCount + v.closedPositionCount !== v.positionCount || !amount(v.openTotalCost) || !amount(v.cumulativeRealizedProfitLoss)) return false
  const r = v.referenceValuation
  return isObject(r) && exactFields(r, ['baseCurrency', 'value', 'valuedPositionCount', 'totalOpenPositionCount', 'freshness', 'warnings'])
    && r.baseCurrency === 'CNY' && count(r.valuedPositionCount) && r.totalOpenPositionCount === v.openPositionCount && r.valuedPositionCount <= v.openPositionCount
    && (r.valuedPositionCount === 0 ? r.value === null && r.freshness === 'UNAVAILABLE'
      : amount(r.value) && (r.valuedPositionCount < v.openPositionCount ? r.freshness === 'PARTIAL' : r.freshness === 'FRESH' || r.freshness === 'STALE'))
    && Array.isArray(r.warnings) && r.warnings.every(w => isObject(w) && exactFields(w, ['code', 'component'])
      && ['INVALID_INPUT', 'QUOTE_MISSING', 'QUOTE_STALE', 'FX_MISSING', 'FX_STALE'].includes(w.code as string) && ['VALUATION', 'QUOTE', 'FX'].includes(w.component as string))
}
