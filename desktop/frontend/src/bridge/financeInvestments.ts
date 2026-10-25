import { analyticsAmount } from './financeAnalytics'
import { exactFields, isObject } from './contracts'
import { financeId } from './finance'

export interface FinancePositionQuery { status: 'OPEN' | 'CLOSED' | 'ALL'; accountId: string; instrumentId: string; cursor: string; size: number }
interface PositionAccount { id: string; displayName: string; type: string; status: 'ACTIVE' | 'INACTIVE' }
interface Instrument { id: string; symbol: string; name: string; market: string; assetClass: string; quoteCurrency: string; status: 'ACTIVE' | 'INACTIVE' }
export interface PositionWarning { code: 'INVALID_INPUT' | 'QUOTE_MISSING' | 'QUOTE_STALE' | 'FX_MISSING' | 'FX_STALE'; component: 'VALUATION' | 'QUOTE' | 'FX' }
interface PositionReference { baseCurrency: 'CNY'; value: string | null; freshness: 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE'; warnings: PositionWarning[] }
export interface FinancePosition {
  positionId: string; positionMode: 'TRANSACTION_DRIVEN'; account: PositionAccount; instrument: Instrument; status: 'OPEN' | 'CLOSED'
  quantity: string | null; averageCost: string | null; totalCost: string | null; cumulativeRealizedProfitLoss: string | null; referenceValuation: PositionReference
}
export interface PositionCachedReference extends Omit<PositionReference, 'value'> {
  accountingTruth: false; quotePrice: string | null; quoteCurrency: string | null; quoteTime: string | null; quoteFetchedAt: string | null; quoteProvider: string | null
  fxRate: string | null; fxBaseCurrency: string | null; fxQuoteCurrency: string | null; fxRateTime: string | null; fxFetchedAt: string | null; fxProvider: string | null; baseCurrencyValue: string | null
}
export interface FinancePositionDetail extends Omit<FinancePosition, 'referenceValuation'> {
  manualReference: { currentPrice: string | null; marketValue: string | null }; referenceValuation: PositionCachedReference
}
export interface FinanceHoldingPlot { items: { positionId: string; cost: number | null; reference: number | null }[]; zero: number; minimum: string; maximum: string }
export interface FinancePositionPage { comparison?: FinanceHoldingPlot; records: FinancePosition[]; nextCursor: string | null; hasMore: boolean; size: number }

const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 && v.length <= max
const nullable = (v: unknown, check: (x: unknown) => boolean) => v === null || check(v)
const decimal = (v: unknown) => typeof v === 'string' && /^-?[0-9]{1,60}(?:\.[0-9]{1,28})?$/.test(v)
const currency = (v: unknown) => typeof v === 'string' && /^[A-Z]{3}$/.test(v)
const instant = (v: unknown) => typeof v === 'string' && v.length <= 40 && v.includes('T') && v.endsWith('Z') && !Number.isNaN(Date.parse(v))
const cursor = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(v)
export function isFinancePositionQuery(v: unknown): v is FinancePositionQuery {
  return isObject(v) && exactFields(v, ['status', 'accountId', 'instrumentId', 'cursor', 'size'])
    && ['OPEN', 'CLOSED', 'ALL'].includes(v.status as string) && Number.isInteger(v.size) && (v.size as number) >= 1 && (v.size as number) <= 20
    && (v.accountId === '' || financeId(v.accountId)) && (v.instrumentId === '' || financeId(v.instrumentId)) && (v.cursor === '' || cursor(v.cursor))
}
function identity(v: Record<string, unknown>) {
  const a = v.account, i = v.instrument
  return financeId(v.positionId) && v.positionMode === 'TRANSACTION_DRIVEN' && ['OPEN', 'CLOSED'].includes(v.status as string)
    && ['quantity', 'averageCost', 'totalCost', 'cumulativeRealizedProfitLoss'].every(k => nullable(v[k], decimal))
    && isObject(a) && exactFields(a, ['id', 'displayName', 'type', 'status']) && financeId(a.id) && text(a.displayName, 100) && text(a.type, 50) && ['ACTIVE', 'INACTIVE'].includes(a.status as string)
    && isObject(i) && exactFields(i, ['id', 'symbol', 'name', 'market', 'assetClass', 'quoteCurrency', 'status'])
    && financeId(i.id) && text(i.symbol, 30) && text(i.name, 100) && currency(i.quoteCurrency) && ['ACTIVE', 'INACTIVE'].includes(i.status as string)
    && ['US', 'HK', 'CN', 'JP', 'KR', 'CRYPTO', 'OTC', 'FUND', 'OTHER', 'UNKNOWN'].includes(i.market as string) && ['STOCK', 'ETF', 'FUND', 'BOND', 'CRYPTO'].includes(i.assetClass as string)
}
function reference(v: Record<string, unknown>, value: string) {
  return v.baseCurrency === 'CNY' && nullable(v[value], decimal) && ['FRESH', 'STALE', 'PARTIAL', 'UNAVAILABLE'].includes(v.freshness as string)
    && (['FRESH', 'STALE'].includes(v.freshness as string) === (v[value] !== null)) && Array.isArray(v.warnings) && v.warnings.length <= 8
    && v.warnings.every(w => isObject(w) && exactFields(w, ['code', 'component']) && ['INVALID_INPUT', 'QUOTE_MISSING', 'QUOTE_STALE', 'FX_MISSING', 'FX_STALE'].includes(w.code as string) && ['VALUATION', 'QUOTE', 'FX'].includes(w.component as string))
}
const fields = ['positionId', 'positionMode', 'account', 'instrument', 'quantity', 'averageCost', 'totalCost', 'cumulativeRealizedProfitLoss', 'status', 'referenceValuation']
export function isFinancePosition(v: unknown): v is FinancePosition {
  return isObject(v) && exactFields(v, fields) && identity(v) && isObject(v.referenceValuation)
    && exactFields(v.referenceValuation, ['baseCurrency', 'value', 'freshness', 'warnings']) && reference(v.referenceValuation, 'value')
}
export function isFinancePositionDetail(v: unknown): v is FinancePositionDetail {
  if (!isObject(v) || !exactFields(v, [...fields, 'manualReference']) || !identity(v)) return false
  const m = v.manualReference, r = v.referenceValuation
  return isObject(m) && exactFields(m, ['currentPrice', 'marketValue']) && nullable(m.currentPrice, decimal) && nullable(m.marketValue, decimal)
    && isObject(r) && exactFields(r, ['accountingTruth', 'quotePrice', 'quoteCurrency', 'quoteTime', 'quoteFetchedAt', 'quoteProvider', 'fxRate',
      'fxBaseCurrency', 'fxQuoteCurrency', 'fxRateTime', 'fxFetchedAt', 'fxProvider', 'baseCurrency', 'baseCurrencyValue', 'freshness', 'warnings'])
    && r.accountingTruth === false && reference(r, 'baseCurrencyValue') && ['quotePrice', 'fxRate'].every(k => nullable(r[k], decimal))
    && ['quoteCurrency', 'fxBaseCurrency', 'fxQuoteCurrency'].every(k => nullable(r[k], currency))
    && ['quoteTime', 'quoteFetchedAt', 'fxRateTime', 'fxFetchedAt'].every(k => nullable(r[k], instant)) && ['quoteProvider', 'fxProvider'].every(k => nullable(r[k], x => text(x, 30)))
}
export function isFinancePositionPage(v: unknown, q: FinancePositionQuery): v is FinancePositionPage {
  return isObject(v) && (exactFields(v, ['records', 'nextCursor', 'hasMore', 'size']) || exactFields(v, ['records', 'nextCursor', 'hasMore', 'size', 'comparison'])) && v.size === q.size && typeof v.hasMore === 'boolean'
    && nullable(v.nextCursor, cursor) && v.hasMore === (v.nextCursor !== null) && v.nextCursor !== q.cursor
    && Array.isArray(v.records) && v.records.length <= q.size && (!v.hasMore || v.records.length === q.size)
    && v.records.every(p => isFinancePosition(p) && (q.status === 'ALL' || p.status === q.status) && (!q.accountId || p.account.id === q.accountId) && (!q.instrumentId || p.instrument.id === q.instrumentId))
    && new Set(v.records.map(p => p.positionId)).size === v.records.length
    && (!Object.hasOwn(v, 'comparison') || isFinanceHoldingPlot(v.comparison, v.records as FinancePosition[]))
}

function isFinanceHoldingPlot(v: unknown, records: FinancePosition[]): v is FinanceHoldingPlot {
  const coordinate = (n: unknown) => Number.isInteger(n) && (n as number) >= 0 && (n as number) <= 10000
  return isObject(v) && exactFields(v, ['items', 'zero', 'minimum', 'maximum']) && coordinate(v.zero) && analyticsAmount(v.minimum) && analyticsAmount(v.maximum)
    && Array.isArray(v.items) && v.items.length === records.length && v.items.every((p, i) => isObject(p) && exactFields(p, ['positionId', 'cost', 'reference']) && p.positionId === records[i]!.positionId
      && (records[i]!.totalCost === null ? p.cost === null : coordinate(p.cost)) && (records[i]!.referenceValuation.value === null ? p.reference === null : coordinate(p.reference)))
}
