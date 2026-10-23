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
    view.unmount()
  } finally { bridge.dispose() }
})
