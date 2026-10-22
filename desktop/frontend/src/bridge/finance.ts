import { isObject as object, exactFields as fields } from './contracts'

export interface FinanceAccount { id: string; name: string; type: string; currency: 'CNY'; balance: string; status: string }
export interface FinanceOverview { state: 'READY' | 'DISABLED' | 'NOT_BOUND' | 'BINDING_INVALID' | 'UNAVAILABLE'; account: FinanceAccount | null; cnyBalance: { currency: 'CNY'; balance: string } | null }
const amount = (value: unknown): value is string => typeof value === 'string' && /^-?\d{1,30}(?:\.\d{1,8})?$/.test(value)
export function isFinanceOverview(value: unknown): value is FinanceOverview {
  if (!object(value) || !fields(value, ['state', 'account', 'cnyBalance'])) return false
  if (value.state !== 'READY') return ['DISABLED', 'NOT_BOUND', 'BINDING_INVALID', 'UNAVAILABLE'].includes(value.state as string) && value.account === null && value.cnyBalance === null
  const account = value.account; const total = value.cnyBalance
  return object(total) && fields(total, ['currency', 'balance']) && total.currency === 'CNY' && amount(total.balance)
    && (account === null || object(account) && fields(account, ['id', 'name', 'type', 'currency', 'balance', 'status'])
      && typeof account.id === 'string' && /^[1-9]\d{0,18}$/.test(account.id) && typeof account.name === 'string' && account.name.length <= 256
      && typeof account.type === 'string' && account.currency === 'CNY' && amount(account.balance) && typeof account.status === 'string')
}
