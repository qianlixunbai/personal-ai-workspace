import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { FinancePage } from './FinancePage'

afterEach(cleanup)

it('discards financial snapshots and late detail responses across runtime changes and permits an explicit retry', async () => {
  const port = new Port(); const bridge = new WorkspaceClient(port); port.session()
  const account = { id: '1', name: 'Alice account', type: 'BANK', currency: 'CNY', balance: '9007199254740993.10', status: 'ACTIVE' }
  const overview = { state: 'READY', account, cnyBalance: { currency: 'CNY', balance: '9007199254740993.30' }, username: 'Alice', accountCount: '1' }
  const reply = async (index: number, value: unknown) => { await act(async () => { port.reply(index, value) }) }
  const view = render(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
  try {
    await reply(0, overview)
    await reply(1, { items: [account], page: 0, size: 10, total: '1', status: 'ALL', sort: 'ID_ASC' })
    expect(screen.getByText('9007199254740993.30 CNY')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Alice account' }))
    expect(screen.queryByText('9007199254740993.30 CNY')).toBeNull()
    await reply(2, overview)
    await reply(3, { items: [account], page: 0, size: 10, total: '1', status: 'ALL', sort: 'ID_ASC' })
    expect(port.sent[4]).toMatchObject({ method: 'finance.get', payload: { accountId: '1' } })
    await act(async () => { port.emit({ type: 'storage.lifecycle', version: 1, sessionId: port.sent[0]!.sessionId,
      result: { runtime: 'STARTING', failure: 'STARTING', configurationTrust: 'NOT_CHECKED', canQueryStorage: false, configurationAction: 'NATIVE_SETUP_AVAILABLE', confirmationRequired: true } }) })
    await reply(4, account)
    expect(screen.queryByText('9007199254740993.10 CNY')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新 Finance' }))
    await reply(5, { state: 'BINDING_INVALID', account: null, cnyBalance: null, username: null, accountCount: null })
    expect(screen.getByText(/历史用户身份已改变/)).toBeTruthy()
    expect(screen.queryByText('Alice')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Transactions · 交易流水' }))
    const query = { page: 0, size: 10, accountId: '', startDate: '', endDate: '', type: 'ALL' }
    const transaction = { kind: 'TRANSFER', id: '1', type: 'TRANSFER', amount: '9007199254740993.10', currency: 'CNY', transactedAt: '2026-10-23T12:00', description: null, accountId: '1', accountName: 'Alice account', toAccountId: '2', toAccountName: 'Alice cash' }
    await reply(6, overview)
    await reply(7, { ...query, items: [transaction], total: '1' })
    expect(screen.getByText('9007199254740993.10 CNY')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '2026-10-23 12:00' }))
    expect(screen.queryByText('9007199254740993.10 CNY')).toBeNull()
    await reply(8, overview)
    await reply(9, { ...query, items: [transaction], total: '1' })
    expect(port.sent[10]).toMatchObject({ method: 'finance.transaction', payload: { kind: 'TRANSFER', id: '1' } })
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222') })
    await reply(10, transaction)
    expect(screen.queryByText('9007199254740993.10 CNY')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新交易' }))
    await reply(11, overview)
    await reply(12, { ...query, items: [transaction], total: '1' })
    expect(screen.getByText('9007199254740993.10 CNY')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Budgets · 预算' }))
    expect(port.sent[13]).toMatchObject({ method: 'finance.budgets', payload: {} })
    await reply(13, { state: 'UNSUPPORTED' })
    expect(screen.getByText(/这不表示当前用户没有预算/)).toBeTruthy()
    expect(screen.queryByText('9007199254740993.10 CNY')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新预算' }))
    await reply(14, { state: 'UNSUPPORTED', remaining: '0.00' })
    expect(screen.getByRole('alert')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await act(async () => { port.session('33333333-3333-4333-8333-333333333333') })
    await reply(15, { state: 'UNSUPPORTED' })
    expect(screen.queryByText(/这不表示当前用户没有预算/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新预算' }))
    await reply(16, { state: 'UNAVAILABLE' })
    expect(screen.getByText(/Finance 数据库暂时不可用/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await reply(17, { state: 'UNSUPPORTED' })
    expect(screen.getByText(/这不表示当前用户没有预算/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Assets · 资产' }))
    const asset = { id: '1', name: 'Alice asset', symbol: null, type: 'FUND', market: 'CN', currency: 'CNY', quantity: '9007199254740993.10', avgCost: '1.00000000', currentPrice: null, manualMarketValue: null, positionMode: 'LEGACY', createdAt: '2026-10-24T08:00', updatedAt: '2026-10-24T08:00', referenceValuation: null }
    const assets = { page: 0, size: 10, type: 'ALL', sort: 'CREATED_DESC', total: '1', items: [asset] }
    await reply(18, overview); await reply(19, assets)
    expect(screen.getByRole('button', { name: 'Alice asset' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Alice asset' }))
    await reply(20, overview); await reply(21, assets)
    expect(port.sent[22]).toMatchObject({ method: 'finance.asset', payload: { id: '1' } })
    await act(async () => { port.session('44444444-4444-4444-8444-444444444444') })
    await reply(22, asset)
    expect(screen.queryByText('Alice asset')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新资产' }))
    await reply(23, overview); await reply(24, assets)
    fireEvent.click(screen.getByRole('button', { name: 'Alice asset' }))
    await reply(25, overview); await reply(26, assets); await reply(27, asset)
    expect(screen.getByText('9007199254740993.10')).toBeTruthy()
    expect(screen.getAllByText('未提供').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: '刷新资产' }))
    await reply(28, { state: 'BINDING_INVALID', account: null, cnyBalance: null, username: null, accountCount: null })
    expect(screen.queryByText('9007199254740993.10')).toBeNull()
    expect(screen.getByText(/资产读取已拒绝/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Investment Positions · 投资持仓' }))
    const position = { positionId: '11', positionMode: 'TRANSACTION_DRIVEN', account: { id: '6', displayName: 'Alice brokerage', type: 'BROKERAGE', status: 'ACTIVE' },
      instrument: { id: '11', symbol: 'SYNUSD', name: 'Alice position', market: 'US', assetClass: 'STOCK', quoteCurrency: 'USD', status: 'ACTIVE' }, status: 'OPEN',
      quantity: '9007199254740993.12345678', averageCost: '1.00000000', totalCost: '9007199254740993.12', cumulativeRealizedProfitLoss: '1.00',
      referenceValuation: { baseCurrency: 'CNY', value: null, freshness: 'UNAVAILABLE', warnings: [{ code: 'QUOTE_MISSING', component: 'QUOTE' }] } }
    const positions = { records: [position], nextCursor: 'opaque_from_server', hasMore: true, size: 10 }
    const fullPage = { ...positions, records: Array.from({ length: 10 }, (_, n) => ({ ...position, positionId: String(11 + n) })) }
    await reply(29, overview); await reply(30, fullPage)
    expect(screen.getAllByText('9007199254740993.12345678').length).toBe(10)
    fireEvent.click(screen.getByRole('button', { name: '下一页' }))
    await reply(31, overview)
    expect(port.sent[32]).toMatchObject({ method: 'finance.positions', payload: { status: 'OPEN', cursor: 'opaque_from_server' } })
    await reply(32, { records: [], nextCursor: null, hasMore: false, size: 10 })
    fireEvent.change(screen.getByLabelText('持仓状态'), { target: { value: 'ALL' } })
    await reply(33, overview)
    expect(port.sent[34]).toMatchObject({ method: 'finance.positions', payload: { status: 'ALL', cursor: '' } })
    const finalPage = { records: [position], nextCursor: null, hasMore: false, size: 10 }
    await reply(34, finalPage)
    expect(screen.getByRole('button', { name: '上一页' }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Alice position · #11' }))
    await reply(35, overview); await reply(36, finalPage)
    expect(port.sent[37]).toMatchObject({ method: 'finance.position', payload: { id: '11' } })
    const detail = { ...position, manualReference: { currentPrice: null, marketValue: null }, referenceValuation: {
      accountingTruth: false, quotePrice: null, quoteCurrency: null, quoteTime: null, quoteFetchedAt: null, quoteProvider: null,
      fxRate: null, fxBaseCurrency: null, fxQuoteCurrency: null, fxRateTime: null, fxFetchedAt: null, fxProvider: null,
      baseCurrency: 'CNY', baseCurrencyValue: null, freshness: 'UNAVAILABLE', warnings: [{ code: 'QUOTE_MISSING', component: 'QUOTE' }] } }
    await act(async () => { port.session('55555555-5555-4555-8555-555555555555') })
    await reply(37, detail)
    expect(screen.queryByText('9007199254740993.12345678')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新持仓' }))
    await reply(38, overview); await reply(39, finalPage)
    fireEvent.click(screen.getByRole('button', { name: 'Alice position · #11' }))
    await reply(40, overview); await reply(41, finalPage); await reply(42, detail)
    expect(screen.getAllByText('9007199254740993.12 CNY').length).toBeGreaterThan(0)
    expect(screen.getAllByText('未知 / 未提供').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: '刷新持仓' }))
    await reply(43, { state: 'BINDING_INVALID', account: null, cnyBalance: null, username: null, accountCount: null })
    expect(screen.queryByText('9007199254740993.12345678')).toBeNull()
    expect(screen.getByText(/持仓读取已拒绝/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '刷新持仓' }))
    await reply(44, overview); await reply(45, { ...finalPage, records: [{ ...position, quantity: 9007199254740992 }] })
    expect(screen.getByRole('alert')).toBeTruthy()
    expect(screen.queryByText('9007199254740993.12345678')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await reply(46, overview); await reply(47, finalPage)
    expect(screen.getByText('9007199254740993.12345678')).toBeTruthy()
    view.unmount()
  } finally { bridge.dispose() }
})
