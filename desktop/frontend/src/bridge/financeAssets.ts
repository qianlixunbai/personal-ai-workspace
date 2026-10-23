import { exactFields, isObject } from './contracts'
import { financeId } from './finance'

export const assetTypes = ['ALL', 'STOCK', 'ETF', 'FUND', 'BOND', 'GOLD', 'CRYPTO', 'CASH', 'OTHER'] as const
export interface FinanceAssetQuery { page: number; size: number; type: typeof assetTypes[number]; sort: 'CREATED_DESC' | 'ID_ASC' | 'NAME_ASC' }
export interface FinanceAssetReference {
  quoteCurrency: string | null; quotePrice: string | null; quoteTime: string | null; quoteFetchedAt: string | null; quoteProvider: string | null
  quoteFreshness: 'FRESH' | 'STALE' | 'NEVER_FETCHED'; fxRequired: boolean; fxBaseCurrency: string | null; fxQuoteCurrency: string | null
  fxRate: string | null; fxRateTime: string | null; fxFetchedAt: string | null; fxProvider: string | null; fxFreshness: 'FRESH' | 'STALE' | 'NEVER_FETCHED'
  nativeMarketValue: string | null; baseCurrency: 'CNY'; baseCurrencyMarketValue: string | null
  valuationFreshness: 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE'; calculatedAt: string; formulaVersion: 'REFERENCE_VALUATION_V1'; warnings: string[]
}
export interface FinanceAsset {
  id: string; name: string; symbol: string | null; type: Exclude<FinanceAssetQuery['type'], 'ALL'>; market: string | null; currency: string
  quantity: string; avgCost: string; currentPrice: string | null; manualMarketValue: string | null; positionMode: 'LEGACY' | 'TRANSACTION_DRIVEN'
  createdAt: string; updatedAt: string; referenceValuation: FinanceAssetReference | null
}
export interface FinanceAssetPage extends FinanceAssetQuery { items: FinanceAsset[]; total: string }
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.length <= max
const decimal = (v: unknown) => typeof v === 'string' && /^-?[0-9]{1,60}(?:\.[0-9]{1,28})?$/.test(v)
const currency = (v: unknown) => typeof v === 'string' && /^[A-Z]{3}$/.test(v)
const time = (v: unknown, instant = true) => typeof v === 'string' && v.length <= 40 && v.includes('T') && (!instant || v.endsWith('Z')) && !Number.isNaN(Date.parse(v))
const nullable = (v: unknown, check: (value: unknown) => boolean) => v === null || check(v)
export function isFinanceAssetQuery(v: unknown): v is FinanceAssetQuery {
  return isObject(v) && Number.isInteger(v.page) && (v.page as number) >= 0 && (v.page as number) <= 100000
    && Number.isInteger(v.size) && (v.size as number) >= 1 && (v.size as number) <= 20
    && assetTypes.includes(v.type as FinanceAssetQuery['type']) && ['CREATED_DESC', 'ID_ASC', 'NAME_ASC'].includes(v.sort as string)
}
function isReference(v: unknown): v is FinanceAssetReference {
  if (!isObject(v) || !exactFields(v, ['quoteCurrency', 'quotePrice', 'quoteTime', 'quoteFetchedAt', 'quoteProvider', 'quoteFreshness', 'fxRequired',
    'fxBaseCurrency', 'fxQuoteCurrency', 'fxRate', 'fxRateTime', 'fxFetchedAt', 'fxProvider', 'fxFreshness', 'nativeMarketValue', 'baseCurrency',
    'baseCurrencyMarketValue', 'valuationFreshness', 'calculatedAt', 'formulaVersion', 'warnings'])) return false
  return ['quoteCurrency', 'fxBaseCurrency', 'fxQuoteCurrency'].every(k => nullable(v[k], currency))
    && ['quotePrice', 'fxRate', 'nativeMarketValue', 'baseCurrencyMarketValue'].every(k => nullable(v[k], decimal))
    && ['quoteTime', 'quoteFetchedAt', 'fxRateTime', 'fxFetchedAt'].every(k => nullable(v[k], time))
    && ['quoteProvider', 'fxProvider'].every(k => nullable(v[k], x => text(x, 30)))
    && ['quoteFreshness', 'fxFreshness'].every(k => ['FRESH', 'STALE', 'NEVER_FETCHED'].includes(v[k] as string))
    && typeof v.fxRequired === 'boolean' && v.baseCurrency === 'CNY' && v.formulaVersion === 'REFERENCE_VALUATION_V1' && time(v.calculatedAt)
    && ['FRESH', 'STALE', 'PARTIAL', 'UNAVAILABLE'].includes(v.valuationFreshness as string)
    && (['FRESH', 'STALE'].includes(v.valuationFreshness as string) === (v.baseCurrencyMarketValue !== null))
    && Array.isArray(v.warnings) && v.warnings.length <= 8 && v.warnings.every(w => ['INVALID_INPUT', 'QUOTE_MISSING', 'QUOTE_STALE', 'FX_MISSING', 'FX_STALE'].includes(w))
}
export function isFinanceAsset(v: unknown): v is FinanceAsset {
  return isObject(v) && exactFields(v, ['id', 'name', 'symbol', 'type', 'market', 'currency', 'quantity', 'avgCost', 'currentPrice',
    'manualMarketValue', 'positionMode', 'createdAt', 'updatedAt', 'referenceValuation'])
    && financeId(v.id) && text(v.name, 100) && v.name.length > 0 && nullable(v.symbol, x => text(x, 30)) && nullable(v.market, x => text(x, 30))
    && assetTypes.slice(1).includes(v.type as FinanceAsset['type']) && currency(v.currency) && decimal(v.quantity) && decimal(v.avgCost)
    && nullable(v.currentPrice, decimal) && nullable(v.manualMarketValue, decimal) && ((v.currentPrice === null) === (v.manualMarketValue === null))
    && ['LEGACY', 'TRANSACTION_DRIVEN'].includes(v.positionMode as string) && time(v.createdAt, false) && time(v.updatedAt, false)
    && (v.referenceValuation === null || ['STOCK', 'ETF'].includes(v.type as string) && typeof v.market === 'string' && v.market.trim().toUpperCase() === 'US' && isReference(v.referenceValuation))
}
export function isFinanceAssetPage(v: unknown): v is FinanceAssetPage {
  return isObject(v) && exactFields(v, ['page', 'size', 'type', 'sort', 'items', 'total']) && isFinanceAssetQuery(v)
    && typeof v.total === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(v.total) && BigInt(v.total) <= 9223372036854775807n
    && Array.isArray(v.items) && v.items.length <= v.size && v.items.every(a => isFinanceAsset(a) && (v.type === 'ALL' || a.type === v.type))
    && new Set(v.items.map(a => a.id)).size === v.items.length
}
