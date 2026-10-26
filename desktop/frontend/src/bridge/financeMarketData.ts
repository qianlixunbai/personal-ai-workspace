import { exactFields, isObject } from './contracts'

export interface FinanceMarketSample { symbol: string; price: string; time: string; fetchedAt: string; exchange: string; date: string; timeZone: string }
export interface FinanceMarketStatus {
  provider: 'MASSIVE'; enabled: boolean; keyConfigured: boolean; result: string
  quote: FinanceMarketSample | null; fx: FinanceMarketSample | null
  rights: { display: true; displayMemory: true; persistence: false; offline: false; valuation: false }
  licenseStatus: 'PERSONAL_DISPLAY_ONLY'; providerValidation: 'NOT_RUN' | 'RESPONSE_VALIDATED'; dataContext: 'SYNTHETIC' | 'UNVERIFIED'
}
const instant = (v: unknown) => typeof v === 'string' && v.length <= 40 && v.endsWith('Z') && !Number.isNaN(Date.parse(v))
function sample(v: unknown, fx: boolean): boolean {
  return v === null || isObject(v) && exactFields(v, ['symbol', 'price', 'time', 'fetchedAt', 'exchange', 'date', 'timeZone'])
    && typeof v.symbol === 'string' && (fx ? v.symbol === 'USD/CNY' : /^[A-Z][A-Z0-9.-]{0,14}$/.test(v.symbol))
    && typeof v.price === 'string' && (fx ? /^[0-9]{1,20}(?:\.[0-9]{1,12})?$/ : /^[0-9]{1,20}(?:\.[0-9]{1,8})?$/).test(v.price) && /[1-9]/.test(v.price)
    && instant(v.time) && instant(v.fetchedAt) && typeof v.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v.date) && !Number.isNaN(Date.parse(v.date))
    && (fx ? v.timeZone === 'UTC' && v.exchange === 'FOREX' : v.timeZone === 'America/New_York' && ['XNAS', 'XNYS', 'ARCX', 'XASE', 'BATS', 'IEXG'].includes(v.exchange as string))
}
export function isFinanceMarketStatus(v: unknown): v is FinanceMarketStatus {
  if (!isObject(v) || !exactFields(v, ['provider', 'enabled', 'keyConfigured', 'result', 'quote', 'fx', 'rights', 'licenseStatus', 'providerValidation', 'dataContext'])
    || v.provider !== 'MASSIVE' || typeof v.enabled !== 'boolean' || typeof v.keyConfigured !== 'boolean' || v.enabled !== v.keyConfigured
    || v.licenseStatus !== 'PERSONAL_DISPLAY_ONLY' || !['NOT_RUN', 'RESPONSE_VALIDATED'].includes(v.providerValidation as string)
    || !['SYNTHETIC', 'UNVERIFIED'].includes(v.dataContext as string) || typeof v.result !== 'string'
    || !['IDLE', 'SAVED', 'UPDATED', 'KEY_REQUIRED', 'UNSUPPORTED', 'RATE_LIMITED', 'ACCESS_DENIED', 'PROVIDER_FAILED', 'IDENTITY_MISMATCH', 'INVALID_DATA', 'NO_DATA', 'LICENSE_UNVERIFIED'].includes(v.result)
    || !sample(v.quote, false) || !sample(v.fx, true) || v.quote !== null && v.fx !== null) return false
  const r = v.rights, hasData = v.quote !== null || v.fx !== null
  return isObject(r) && exactFields(r, ['display', 'displayMemory', 'persistence', 'offline', 'valuation']) && r.display === true && r.displayMemory === true
    && r.persistence === false && r.offline === false && r.valuation === false
    && hasData === (v.result === 'UPDATED') && hasData === (v.providerValidation === 'RESPONSE_VALIDATED') && (!hasData || v.enabled)
}
