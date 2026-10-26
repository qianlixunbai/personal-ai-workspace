import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { FinancePage } from './FinancePage'
import { isFinanceDashboard } from '../bridge/financeDashboard'

afterEach(cleanup)

it('keeps dashboard strings, partial coverage and clears failed or late snapshots across session and runtime changes', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const overview = { state: 'READY', account: null, cnyBalance: { currency: 'CNY', balance: '0.00' }, username: 'Alice', accountCount: '0' }
  const dashboard = { currency: 'CNY', asOf: '2026-10-24T12:00:00', businessZone: 'Asia/Shanghai', cnyAccountBalance: '9007199254740993.30', monthIncome: '0', monthExpense: '0', monthNet: '0',
    cashFlowPlot: { income: [10000, 10000, 10000, 10000, 10000, 10000], expense: [10000, 10000, 10000, 10000, 10000, 10000], zero: 10000 },
    monthlyCashFlow: ['05', '06', '07', '08', '09', '10'].map(m => ({ month: `2026-${m}`, income: '0', expense: '0', net: '0' })),
    allocation: { items: Array.from({ length: 20 }, (_, i) => ({ id: String(i + 1), name: `Asset ${i}`, value: '1.000000000000', percentage: '4.7619' })), total: '21', complete: false, referenceTotal: '21', assetTotal: '22', excludedCurrency: '0', excludedUnpriced: '1', excludedQuantity: '0', excludedValue: '0' }, recentTransactions: [{ id: '1', type: 'TRANSFER', amount: '9007199254740993.10', categoryName: null, accountName: 'Alice', transactedAt: '2026-10-24T13:00' }] }
  const reply = async (v: unknown) => { await act(async () => { port.reply(port.sent.length - 1, v) }) }
  const view = render(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
  try {
    expect(screen.getByRole('button', { name: 'Dashboard · 财务总览' }).getAttribute('aria-current')).toBe('page')
    await reply(overview); await reply(dashboard)
    expect(isFinanceDashboard({ ...dashboard, cashFlowPlot: { ...dashboard.cashFlowPlot, zero: 10001 } })).toBe(false)
    expect(isFinanceDashboard({ ...dashboard, cashFlowPlot: { ...dashboard.cashFlowPlot, income: [0] } })).toBe(false)
    expect(screen.getByText('9007199254740993.30 CNY')).toBeTruthy(); expect(screen.getByText(/部分视图/)).toBeTruthy()
    expect(screen.getAllByText('4.7619%')).toHaveLength(20)
    const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }))
    const returnDashboard = async () => { click('返回财务总览'); await reply(overview); await reply(dashboard) }
    const emptyTransactions = async () => { await reply(overview); const q = port.sent.at(-1)!.payload as object; await reply({ ...q, items: [], total: '0' }) }
    click('CNY 账户总余额 · 查看明细'); await reply(overview)
    await reply({ items: [], page: 0, size: 10, total: '0', status: 'ALL', sort: 'ID_ASC' }); await returnDashboard()
    for (const type of ['INCOME', 'EXPENSE']) {
      click(type === 'INCOME' ? '本月收入 · 查看明细' : '本月支出 · 查看明细'); await reply(overview)
      expect(port.sent.at(-1)).toMatchObject({ method: 'finance.transactions', payload: { page: 0, type, startDate: '2026-10-01', endDate: '', cashFlowMonth: '2026-10', cashFlowAsOf: dashboard.asOf } })
      const q = port.sent.at(-1)!.payload as object; await reply({ ...q, items: [], total: '0' })
      expect(screen.getByText(/普通交易 TRANSACTION · CNY/)).toBeTruthy()
      click('清除筛选'); await emptyTransactions(); expect(screen.queryByText(/Finance 收支统计明细/)).toBeNull()
      await returnDashboard()
    }
    click('本月净现金流 · 查看明细'); expect(screen.getByRole('heading', { name: '2026-10 净现金流组成' })).toBeTruthy()
    click('查看 2026-10 收入明细'); await emptyTransactions(); await returnDashboard()
    const month = screen.getByRole('button', { name: /^2026-05 收入/ }); month.focus(); fireEvent.click(month)
    expect(month.getAttribute('aria-pressed')).toBe('true'); expect(screen.getByRole('heading', { name: '2026-05 月度收支明细' })).toBeTruthy()
    click('查看 2026-05 支出明细'); await emptyTransactions(); await returnDashboard()
    expect(screen.getByRole('button', { name: /^2026-05 收入/ }).getAttribute('aria-pressed')).toBe('true')
    click('关闭收支明细')
    click('2026-10-24T13:00 · 查看普通交易 1 详情'); await reply(overview)
    const q = port.sent.at(-1)!.payload as object
    await reply({ ...q, items: [], total: '0' })
    expect(port.sent.at(-1)).toMatchObject({ method: 'finance.transaction', payload: { kind: 'TRANSACTION', id: '1' } })
    await reply({ kind: 'TRANSACTION', id: '1', type: 'TRANSFER', amount: '9007199254740993.10', currency: 'CNY', transactedAt: '2026-10-24T13:00', description: 'Trusted detail from server', accountId: '1', accountName: 'Alice', toAccountId: null, toAccountName: null })
    expect(screen.getByText('TRANSACTION · 1')).toBeTruthy(); expect(screen.getByText('Trusted detail from server')).toBeTruthy()
    click('返回交易列表'); expect(screen.queryByRole('heading', { name: '交易详情' })).toBeNull(); await returnDashboard()
    fireEvent.click(screen.getByRole('button', { name: '刷新 Dashboard' })); await reply(overview); const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, dashboard) })
    expect(screen.queryByText('9007199254740993.30 CNY')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新 Dashboard' })); await reply(overview); await reply({ ...dashboard, monthNet: 0 })
    expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByText('人工参考资产分布')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试' })); await reply(overview); await reply(dashboard)
    await act(async () => { port.emit({ type: 'storage.lifecycle', version: 1, sessionId: '22222222-2222-4222-8222-222222222222', result: { runtime: 'UNAVAILABLE', failure: 'PROCESS_EXITED', configurationTrust: 'NOT_CHECKED', canQueryStorage: false, configurationAction: 'NATIVE_SETUP_AVAILABLE', confirmationRequired: true } }) })
    expect(screen.queryByText('人工参考资产分布')).toBeNull()
    view.rerender(<FinancePage bridge={bridge} enabled={false} openSettings={() => {}} />)
    expect(screen.queryByText('人工参考资产分布')).toBeNull()
  } finally { view.unmount(); bridge.dispose() }
})

it('keeps import receipt precision, empty history and identity clearing without accepting a late or numeric receipt', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const overview = { state: 'READY', account: null, cnyBalance: { currency: 'CNY', balance: '0.00' }, username: 'Alice', accountCount: '0' }
  const batch = { importBatchId: '00000000-0000-0000-0001-000000000101', sourceFileName: 'statement.csv', confirmedAt: '2026-10-23T12:00:00.123456Z', status: 'CONFIRMED', totalRows: 1, warningCount: 0, contractVersion: 'TRANSACTION_IMPORT_CONFIRM_V1' }
  const page = { items: [batch], page: 0, size: 10, total: '1' }
  const segment = { receipt: { ...batch, importSessionId: '00000000-0000-0000-0002-000000000101', fileDigest: 'a'.repeat(64), acceptedRows: 1, createdCount: 1, skippedCount: 0,
    transactions: [{ rowNumber: 2, transactionId: '9007199254740993' }], accountImpacts: [{ accountId: '1', rowCount: 1, balanceBefore: '9007199254740993.10', delta: '0.20', balanceAfter: '9007199254740993.30' }], resultDigest: 'b'.repeat(64) }, transactionPage: 0, accountImpactPage: 0, segmentSize: 20, accountImpactCount: 1, completeReceipt: true }
  const reply = async (v: unknown) => { await act(async () => { port.reply(port.sent.length - 1, v) }) }
  const view = render(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Import History · 导入历史' })); await reply(overview); await reply({ ...page, items: [], total: '0' })
    expect(screen.getByText('当前绑定用户没有已确认的导入批次。')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '刷新导入历史' })); await reply(overview); await reply(page)
    fireEvent.click(screen.getByRole('button', { name: 'statement.csv' })); await reply(overview); await reply(page); await reply(segment)
    expect(screen.getByText('9007199254740993.30')).toBeTruthy(); expect(screen.getByText('9007199254740993')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '刷新导入历史' })); await reply(overview); await reply(page); const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, segment) })
    expect(screen.queryByText('9007199254740993.30')).toBeNull(); expect(screen.queryByText('Confirmation Receipt · 确认回执')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新导入历史' })); await reply(overview); await reply(page)
    fireEvent.click(screen.getByRole('button', { name: 'statement.csv' })); await reply(overview); await reply(page)
    await reply({ ...segment, receipt: { ...segment.receipt, accountImpacts: [{ ...segment.receipt.accountImpacts[0], balanceAfter: 1 }] } })
    expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByText('Confirmation Receipt · 确认回执')).toBeNull()
    view.unmount()
  } finally { bridge.dispose() }
})

it('keeps investment facts and receipts exact and discards a late audit across identity changes', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const overview = { state: 'READY', account: null, cnyBalance: { currency: 'CNY', balance: '0.00' }, username: 'Alice', accountCount: '0' }
  const money = { quantity: '9007199254740993.12345678', unitPrice: '1.00000000', grossAmount: '9007199254740993.12', feeAmount: '0.00', taxAmount: '0.00', netAmount: '9007199254740993.12', releasedCostAmount: '0.00', realizedProfitLoss: '0.00' }
  const values = { ...money, note: 'original <script>', externalReference: null }
  const account = { id: '6', displayName: 'Synthetic account' }, instrument = { id: '11', symbol: 'SYN', name: 'Synthetic', market: 'US', assetClass: 'STOCK', quoteCurrency: 'USD' }
  const item = { ...money, logicalTransactionId: '101', positionId: '18', account, instrument, transactionType: 'BUY', effectiveTradeTime: '2026-10-02T09:00:00.123456Z', correctionStatus: 'UNCHANGED', effective: true, correctionCreatedAt: null }
  const page = { records: [item], nextCursor: null, hasMore: false, size: 10 }
  const currentPosition = { quantity: '1.00000000', averageCost: '1.00000000', totalCost: '1.00', cumulativeRealizedProfitLoss: '0.00', status: 'OPEN' }
  const detail = { logicalTransactionId: '101', account, instrument, transactionType: 'BUY', effectiveTradeTime: item.effectiveTradeTime, settlementTime: null, correctionStatus: 'UNCHANGED', effective: true, originalBusinessValues: values, effectiveBusinessValues: null, postingReceipt: null, correctionFinalReceipt: null, correction: null, currentPosition }
  const timeline = { logicalTransactionId: '101', correctionStatus: 'UNCHANGED', currentPosition, events: [{ eventKind: 'ORIGINAL_POSTING', createdAt: item.effectiveTradeTime, physicalFactId: '101', originalFactId: null, factType: 'BUY', reason: null, receiptApplicability: 'POSTING_TIME', businessValues: values, postingReceipt: null, correctionFinalReceipt: null, facts: [] }] }
  const reply = async (v: unknown) => { await act(async () => { port.reply(port.sent.length - 1, v) }) }
  const view = render(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Investment History · 投资交易历史' }))
    await reply(overview); await reply(page)
    expect(port.sent.at(-1)).toMatchObject({ method: 'finance.history', payload: { cursor: '', from: '', to: '' } })
    fireEvent.click(screen.getByRole('button', { name: '交易 #101' })); await reply(overview); await reply(page); await reply(detail); await reply(timeline)
    expect(screen.getByText('投资交易详情 · #101')).toBeTruthy(); expect(screen.getByText('ORIGINAL_POSTING')).toBeTruthy()
    expect(screen.getAllByText('9007199254740993.12345678').length).toBeGreaterThan(0)
    expect(screen.getByText(/未发生替换，原始事实仍有效/)).toBeTruthy()
    expect(document.querySelector('script')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新投资历史' })); await reply(overview); await reply(page); await reply(detail)
    const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, timeline) })
    expect(screen.queryByText('ORIGINAL_POSTING')).toBeNull(); expect(screen.queryByText('投资交易详情 · #101')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新投资历史' })); await reply(overview)
    await reply({ ...page, records: [{ ...item, quantity: 9007199254740992 }] })
    expect(screen.getByRole('alert')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试投资历史' })); await reply(overview); await reply(page)
    fireEvent.click(screen.getByRole('button', { name: '交易 #101' })); await reply(overview); await reply(page); await reply(detail)
    await reply({ ...timeline, events: [{ ...timeline.events[0], businessValues: { ...values, netAmount: 1 } }] })
    expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByText('ORIGINAL_POSTING')).toBeNull()
    view.unmount()
  } finally { bridge.dispose() }
})

it('keeps source portfolio amounts and coverage exact, clears snapshots, and retries invalid responses', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const overview = { state: 'READY', account: null, cnyBalance: { currency: 'CNY', balance: '0.00' }, username: 'Alice', accountCount: '0' }
  const portfolio = { currency: 'CNY', positionCount: 6, openPositionCount: 5, closedPositionCount: 1,
    openTotalCost: '9007199254741011.42', cumulativeRealizedProfitLoss: '-2.00',
    referenceValuation: { baseCurrency: 'CNY', value: '6305039478318695.21', valuedPositionCount: 2, totalOpenPositionCount: 5,
      freshness: 'PARTIAL', warnings: [{ code: 'QUOTE_STALE', component: 'QUOTE', positionId: '11', symbol: 'SPY' }, { code: 'QUOTE_STALE', component: 'QUOTE', positionId: '13', symbol: 'QQQ' }, { code: 'FX_MISSING', component: 'FX', positionId: '13', symbol: 'QQQ' }] } }
  const reply = async (value: unknown) => { await act(async () => { port.reply(port.sent.length - 1, value) }) }
  const view = render(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Portfolio Summary · 投资组合统计' }))
    await reply(overview)
    expect(port.sent.at(-1)).toMatchObject({ method: 'finance.portfolio', payload: {} })
    await reply(portfolio)
    expect(screen.getByText('9007199254741011.42 CNY')).toBeTruthy()
    expect(screen.getByText('6305039478318695.21 CNY')).toBeTruthy()
    expect(screen.getByText('2 / 5')).toBeTruthy(); expect(screen.getByText(/不能作为完整组合总市值/)).toBeTruthy()
    expect(screen.getByText('查看 QUOTE_STALE/QUOTE 完整详情')).toBeTruthy()
    expect(screen.getByText(/参考行情已过期，估值包含过期信息。 · 2 项持仓/)).toBeTruthy()
    expect(screen.getByText('9,007,199,254,741,011.42 CNY')).toBeTruthy()
    const position = { positionId: '11', positionMode: 'TRANSACTION_DRIVEN', account: { id: '6', displayName: 'Synthetic brokerage', type: 'BROKERAGE', status: 'ACTIVE' }, instrument: { id: '11', name: 'Synthetic position', symbol: 'SYN', market: 'US', assetClass: 'STOCK', quoteCurrency: 'USD', status: 'ACTIVE' }, quantity: '9007199254740993.12345678', averageCost: '1.00', totalCost: '9007199254740993.12', cumulativeRealizedProfitLoss: '1.00', status: 'OPEN', referenceValuation: { baseCurrency: 'CNY', value: null, freshness: 'UNAVAILABLE', warnings: [] } }
    const positions = { records: [position], nextCursor: null, hasMore: false, size: 10 }
    const detail = { ...position, manualReference: { currentPrice: null, marketValue: null }, referenceValuation: { accountingTruth: false, quotePrice: null, quoteCurrency: null, quoteTime: null, quoteFetchedAt: null, quoteProvider: null, fxRate: null, fxBaseCurrency: null, fxQuoteCurrency: null, fxRateTime: null, fxFetchedAt: null, fxProvider: null, baseCurrency: 'CNY', baseCurrencyValue: null, freshness: 'UNAVAILABLE', warnings: [] } }
    for (const status of ['ALL', 'CLOSED', 'OPEN']) {
      fireEvent.click(screen.getByRole('button', { name: `查看${status === 'ALL' ? '全部' : status}持仓` })); await reply(overview)
      expect(port.sent.at(-1)).toMatchObject({ method: 'finance.positions', payload: { status, cursor: '' } })
      await reply(status === 'OPEN' ? positions : { ...positions, records: [] })
      if (status === 'OPEN') {
        fireEvent.click(screen.getByRole('button', { name: 'Synthetic position · #11' })); await reply(overview); await reply(positions); await reply(detail)
        fireEvent.click(screen.getByRole('button', { name: '查看此持仓投资历史' })); await reply(overview)
        expect(port.sent.at(-1)).toMatchObject({ method: 'finance.history', payload: { positionId: '11' } })
        await reply({ records: [], nextCursor: null, hasMore: false, size: 10 })
        fireEvent.click(screen.getByRole('button', { name: '返回持仓上下文' })); await reply(overview); await reply(positions)
        expect(port.sent.at(-1)).toMatchObject({ method: 'finance.position', payload: { id: '11' } }); await reply(detail)
        expect(screen.getByRole('heading', { name: '持仓详情 · Synthetic position · #11' })).toBeTruthy()
      }
      fireEvent.click(screen.getByRole('button', { name: '返回投资组合' })); await reply(overview); await reply(portfolio)
    }
    fireEvent.click(screen.getByRole('button', { name: '刷新组合统计' }))
    expect(screen.queryByText('9007199254741011.42 CNY')).toBeNull()
    await reply(overview); const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222') })
    await act(async () => { port.reply(late, portfolio) })
    expect(screen.queryByText('6305039478318695.21 CNY')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新组合统计' }))
    await reply(overview); await reply({ ...portfolio, openTotalCost: 9007199254740992 })
    expect(screen.getByRole('alert')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await reply(overview); await reply({ ...portfolio, referenceValuation: { ...portfolio.referenceValuation, freshness: 'FRESH' } })
    expect(screen.getByRole('alert')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await reply(overview); await reply({ ...portfolio, referenceValuation: { ...portfolio.referenceValuation, value: null, valuedPositionCount: 0, freshness: 'UNAVAILABLE' } })
    expect(screen.getByText('未知 / 无可用估值')).toBeTruthy()
    expect(screen.getByText(/所有 OPEN 持仓均无法完成估值/)).toBeTruthy()
    const empty = { currency: 'CNY', positionCount: 0, openPositionCount: 0, closedPositionCount: 0, openTotalCost: '0.00', cumulativeRealizedProfitLoss: '0.00',
      referenceValuation: { baseCurrency: 'CNY', value: null, valuedPositionCount: 0, totalOpenPositionCount: 0, freshness: 'UNAVAILABLE', warnings: [] } }
    fireEvent.click(screen.getByRole('button', { name: '刷新组合统计' }))
    await reply(overview); await reply(empty)
    expect(screen.getByText(/当前组合为空/)).toBeTruthy(); expect(screen.getByText(/不表示行情读取失败/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '刷新组合统计' }))
    await reply(overview); await reply({ ...empty, positionCount: 1, closedPositionCount: 1, cumulativeRealizedProfitLoss: '-2.00' })
    expect(screen.getByText(/全部持仓已平仓/)).toBeTruthy(); expect(screen.getByText('-2.00 CNY')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '刷新组合统计' }))
    await reply({ state: 'BINDING_INVALID', account: null, cnyBalance: null, username: null, accountCount: null })
    expect(screen.getByText(/组合统计读取已拒绝/)).toBeTruthy()
    expect(screen.queryByText('9007199254741011.42 CNY')).toBeNull()
    view.unmount()
  } finally { bridge.dispose() }
})

it('discards financial snapshots and late detail responses across runtime changes and permits an explicit retry', async () => {
  const port = new Port(); const bridge = new WorkspaceClient(port); port.session()
  const account = { id: '1', name: 'Alice account', type: 'BANK', currency: 'CNY', balance: '9007199254740993.10', status: 'ACTIVE' }
  const overview = { state: 'READY', account, cnyBalance: { currency: 'CNY', balance: '9007199254740993.30' }, username: 'Alice', accountCount: '1' }
  const reply = async (index: number, value: unknown) => { await act(async () => { port.reply(index, value) }) }
  const view = render(<FinancePage bridge={bridge} enabled={false} openSettings={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: 'Accounts · 账户' }))
  view.rerender(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
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
    fireEvent.click(screen.getByRole('button', { name: 'Budgets · 预算 · 来源不支持' }))
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

it('rebuilds annual review with future unknowns, exact drilldown, restored year and session clearing', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const overview = { state: 'READY', account: null, cnyBalance: { currency: 'CNY', balance: '0.00' }, username: 'Alice', accountCount: '0' }
  const amount = '9007199254740993.10'
  const annual = (year: number) => ({ currency: 'CNY', businessZone: 'Asia/Shanghai', asOf: '2026-10-24T12:00:00.123456789', currentYear: 2026, year, years: [2026, 2025], income: amount, expense: '0', net: amount, transactionCount: '1',
    months: Array.from({ length: 12 }, (_, i) => ({ month: `${year}-${String(i + 1).padStart(2, '0')}`, state: year === 2026 && i > 9 ? 'FUTURE' : 'OBSERVED', income: year === 2026 && i > 9 ? null : i === 0 ? amount : '0', expense: year === 2026 && i > 9 ? null : '0', net: year === 2026 && i > 9 ? null : i === 0 ? amount : '0', transactionCount: year === 2026 && i > 9 ? null : i === 0 ? '1' : '0' })),
    plot: { income: Array.from({ length: 12 }, (_, i) => year === 2026 && i > 9 ? null : i === 0 ? 0 : 10000), expense: Array.from({ length: 12 }, (_, i) => year === 2026 && i > 9 ? null : 10000), net: Array.from({ length: 12 }, (_, i) => year === 2026 && i > 9 ? null : i === 0 ? 0 : 10000), zero: 10000, ticks: [0, 2500, 5000, 7500, 10000].map(coordinate => ({ coordinate, amount: '0' })) },
    expenseBreakdown: { state: 'EMPTY', total: '0', items: [] } })
  const reply = async (v: unknown) => { await act(async () => { port.reply(port.sent.length - 1, v) }) }
  const view = render(<FinancePage bridge={bridge} enabled openSettings={() => {}} />)
  try {
    fireEvent.click(screen.getByRole('button', { name: '年度分析 · Annual Review' })); await reply(overview); await reply(annual(2026))
    expect(screen.getByRole('button', { name: '2026-11 尚未发生' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getAllByText(amount + ' CNY').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: '上一年' })); await reply(overview); await reply(annual(2025))
    expect(screen.getByLabelText('财务年份').getAttribute('value') ?? (screen.getByLabelText('财务年份') as HTMLSelectElement).value).toBe('2025')
    fireEvent.click(screen.getByRole('button', { name: '台账选择 2025-01' }))
    fireEvent.click(screen.getByRole('button', { name: '查看 2025-01 收入明细' })); await reply(overview)
    const query = port.sent.at(-1)!.payload
    expect(query).toMatchObject({ cashFlowMonth: '2025-01', cashFlowAsOf: '2026-10-24T12:00:00.123456789', type: 'INCOME', startDate: '2025-01-01', endDate: '' })
    await reply({ ...(query as object), items: [], total: '0' })
    fireEvent.click(screen.getByRole('button', { name: '返回财务总览' })); await reply(overview); await reply(annual(2025))
    expect(screen.getByRole('heading', { name: '2025-01 月度收支明细' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '关闭年度月份明细' }))
    fireEvent.click(screen.getByRole('button', { name: '返回当前年' })); await reply(overview); await reply(annual(2026))
    fireEvent.click(screen.getByRole('button', { name: '刷新年度' })); await reply(overview); const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, annual(2026)) })
    expect(screen.queryByRole('table')).toBeNull(); expect(screen.queryByText(amount + ' CNY')).toBeNull()
  } finally { view.unmount(); bridge.dispose() }
})
