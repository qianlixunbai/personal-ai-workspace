import { exactFields, isObject } from './contracts'

export interface FinanceMarketStatus {
  licenseStatus?: 'UNVERIFIED'; providerValidation?: 'NOT_RUN'; dataContext?: 'SYNTHETIC' | 'UNVERIFIED'; provider: 'TWELVE_DATA'; enabled: boolean; keyConfigured: boolean; retentionHours: number; result: string
  fx: { rate: string; time: string; fetchedAt: string; freshness: 'FRESH' | 'STALE' } | null
}
const instant = (v: unknown) => typeof v === 'string' && v.length <= 40 && v.endsWith('Z') && !Number.isNaN(Date.parse(v))
export function isFinanceMarketStatus(v: unknown): v is FinanceMarketStatus {
  if (!isObject(v) || !(exactFields(v, ['provider', 'enabled', 'keyConfigured', 'retentionHours', 'result', 'fx']) || exactFields(v, ['provider', 'enabled', 'keyConfigured', 'retentionHours', 'result', 'fx', 'licenseStatus', 'providerValidation', 'dataContext']) && v.licenseStatus === 'UNVERIFIED' && v.providerValidation === 'NOT_RUN' && ['SYNTHETIC', 'UNVERIFIED'].includes(v.dataContext as string) && v.enabled === false && v.fx === null)
    || v.provider !== 'TWELVE_DATA' || typeof v.enabled !== 'boolean' || typeof v.keyConfigured !== 'boolean'
    || v.enabled && !v.keyConfigured || !Number.isInteger(v.retentionHours) || (v.retentionHours as number) < 1 || (v.retentionHours as number) > 168
    || typeof v.result !== 'string' || !['IDLE', 'SAVED', 'UPDATED', 'CACHED', 'DISABLED', 'UNSUPPORTED', 'RATE_LIMITED', 'ACCESS_DENIED', 'PROVIDER_FAILED', 'IDENTITY_MISMATCH', 'INVALID_DATA', 'LICENSE_UNVERIFIED'].includes(v.result.replace(/^PARTIAL_/, ''))) return false
  const f = v.fx
  return f === null || isObject(f) && exactFields(f, ['rate', 'time', 'fetchedAt', 'freshness'])
    && typeof f.rate === 'string' && /^[0-9]{1,20}(?:\.[0-9]{1,12})?$/.test(f.rate) && /[1-9]/.test(f.rate)
    && instant(f.time) && instant(f.fetchedAt) && ['FRESH', 'STALE'].includes(f.freshness as string)
}
