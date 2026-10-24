import type { FinanceTransaction, FinanceTransactionQuery } from '../bridge/financeTransactions'
import type { FinancePositionQuery } from '../bridge/financeInvestments'

export type FinanceView = 'dashboard' | 'accounts' | 'transactions' | 'assets' | 'investments' | 'portfolio' | 'history' | 'imports' | 'budgets'
// In-memory, single-use delivery. FinancePage consumes it on destination entry.
// It never carries identity authority or financial responses; each read revalidates the native binding.
export type FinanceIntent = { source: FinanceView } & (
  { target: 'dashboard'; month: string } |
  { target: 'accounts' | 'assets' } |
  { target: 'transactions'; query?: FinanceTransactionQuery; selection?: Pick<FinanceTransaction, 'kind' | 'id'> } |
  { target: 'investments'; query: FinancePositionQuery; selection?: string } |
  { target: 'history'; positionId: string }
)
export type FinanceReturn = { view: 'dashboard'; month: string } | { view: 'portfolio' } |
  { view: 'investments'; query: FinancePositionQuery; selection: string }
export const invalidFinanceIdentity = (problem: unknown) => problem instanceof Error && 'code' in problem
  && ['FinanceBindingInvalid', 'FinanceNotBound'].includes(String(problem.code))
