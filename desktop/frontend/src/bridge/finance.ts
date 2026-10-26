import { isObject as object, exactFields as fields } from './contracts'

export interface FinanceBudgetAvailability { state: 'UNSUPPORTED' | 'DISABLED' | 'NOT_BOUND' | 'BINDING_INVALID' | 'UNAVAILABLE' }
export function isFinanceBudgetAvailability(value: unknown): value is FinanceBudgetAvailability {
  return object(value) && fields(value, ['state']) && ['UNSUPPORTED', 'DISABLED', 'NOT_BOUND', 'BINDING_INVALID', 'UNAVAILABLE'].includes(value.state as string)
}

export interface FinanceAccount { id: string; name: string; type: string; currency: string; balance: string; status: 'ACTIVE' | 'INACTIVE' }
export interface FinanceOverview { dataContext?: 'SYNTHETIC' | 'UNVERIFIED'; state: 'READY' | 'DISABLED' | 'NOT_BOUND' | 'BINDING_INVALID' | 'UNAVAILABLE'; account: FinanceAccount | null; cnyBalance: { currency: 'CNY'; balance: string } | null; username: string | null; accountCount: string | null }
export interface FinanceQuery { page: number; size: number; status: 'ALL' | 'ACTIVE' | 'INACTIVE'; sort: 'ID_ASC' | 'ID_DESC' | 'NAME_ASC' }
export interface FinanceAccountPage extends FinanceQuery { items: FinanceAccount[]; total: string }
const amount = (value: unknown): value is string => typeof value === 'string' && /^-?[0-9]{1,30}(?:\.[0-9]{1,8})?$/.test(value)
const count = (value: unknown): value is string => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
export const financeId = (value: unknown): value is string => count(value) && value !== '0'
export function isFinanceAccount(value: unknown): value is FinanceAccount {
  return object(value) && fields(value, ['id', 'name', 'type', 'currency', 'balance', 'status']) && financeId(value.id)
    && typeof value.name === 'string' && value.name.length > 0 && value.name.length <= 256
    && typeof value.type === 'string' && value.type.length > 0 && value.type.length <= 50
    && typeof value.currency === 'string' && /^[A-Z]{3}$/.test(value.currency) && amount(value.balance) && ['ACTIVE', 'INACTIVE'].includes(value.status as string)
}
export function isFinanceQuery(value: unknown): value is FinanceQuery {
  return object(value) && Number.isInteger(value.page) && (value.page as number) >= 0 && (value.page as number) <= 100000
    && Number.isInteger(value.size) && (value.size as number) >= 1 && (value.size as number) <= 20
    && ['ALL', 'ACTIVE', 'INACTIVE'].includes(value.status as string) && ['ID_ASC', 'ID_DESC', 'NAME_ASC'].includes(value.sort as string)
}
export function isFinanceAccountPage(value: unknown): value is FinanceAccountPage {
  return object(value) && fields(value, ['items', 'page', 'size', 'total', 'status', 'sort']) && isFinanceQuery(value)
    && count(value.total) && Array.isArray(value.items) && value.items.length <= value.size
    && value.items.every(item => isFinanceAccount(item) && (value.status === 'ALL' || item.status === value.status))
    && new Set(value.items.map(item => item.id)).size === value.items.length
}
export function isFinanceOverview(value: unknown): value is FinanceOverview {
  if (!object(value) || !(fields(value, ['state', 'account', 'cnyBalance', 'username', 'accountCount']) || fields(value, ['state', 'account', 'cnyBalance', 'username', 'accountCount', 'dataContext']) && ['SYNTHETIC', 'UNVERIFIED'].includes(value.dataContext as string))) return false
  if (value.state !== 'READY') return ['DISABLED', 'NOT_BOUND', 'BINDING_INVALID', 'UNAVAILABLE'].includes(value.state as string)
    && value.account === null && value.cnyBalance === null && value.username === null && value.accountCount === null
  const total = value.cnyBalance
  return object(total) && fields(total, ['currency', 'balance']) && total.currency === 'CNY' && amount(total.balance)
    && typeof value.username === 'string' && value.username.length > 0 && value.username.length <= 256 && count(value.accountCount)
    && (value.account === null || isFinanceAccount(value.account) && value.account.currency === 'CNY')
}
