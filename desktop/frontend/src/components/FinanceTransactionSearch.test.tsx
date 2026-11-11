import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { FinanceTransactions } from './FinanceTransactions'

const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const overview = { state: 'READY', account: null, cnyBalance: { currency: 'CNY', balance: '0.00' }, username: 'Synthetic', accountCount: '2' }
const text = "订单😀 %_&'#--"
const row = { kind: 'TRANSFER', id: '9007199254740993', type: 'TRANSFER', amount: '9007199254740993.10', currency: 'CNY', transactedAt: '2026-10-01T12:00',
  description: '前文' + text + '后文', accountId: '1', accountName: '转出', toAccountId: '2', toAccountName: '转入' }
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }))
async function setup() {
  const port = new Port(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<FinanceTransactions bridge={bridge} enabled openSettings={() => {}} />)
  const reply = async (value: unknown) => { await act(async () => port.reply(port.sent.length - 1, value)) }
  const page = (items: unknown[] = [], total = '0') => ({ ...port.sent.at(-1)!.payload as object, items, total })
  await reply(overview); await reply(page()); return { port, bridge, reply, page }
}
it('submits literal description search after IME, keeps the applied filter across pages and detail and clears explicitly', async () => {
  const { port, reply, page } = await setup()
  const input = screen.getByLabelText('流水备注关键词') as HTMLInputElement
  fireEvent.change(input, { target: { value: text } })
  fireEvent.change(screen.getByLabelText('交易账户 ID'), { target: { value: '2' } })
  fireEvent.change(screen.getByLabelText('每页交易数'), { target: { value: '2' } })
  const before = port.sent.length
  fireEvent.compositionStart(input); fireEvent.submit(input.closest('form')!)
  expect(port.sent).toHaveLength(before)
  fireEvent.compositionEnd(input); click('应用筛选'); await reply(overview)
  expect(port.sent.at(-1)).toMatchObject({ method: 'finance.transactions', payload: { query: text, page: 0, size: 2, accountId: '2' } })
  await reply(page([row], '21'))
  expect(screen.getByText(row.description)).toBeTruthy(); expect(screen.getByText('9007199254740993.10 CNY')).toBeTruthy()
  fireEvent.change(input, { target: { value: '尚未应用' } }); click('下一页'); await reply(overview)
  expect(port.sent.at(-1)).toMatchObject({ method: 'finance.transactions', payload: { query: text, page: 1, accountId: '2' } })
  await reply(page([row], '21')); expect(input.value).toBe(text)
  click('2026-10-01 12:00'); await reply(overview); await reply(page([row], '21'))
  expect(port.sent.at(-1)).toMatchObject({ method: 'finance.transaction', payload: { kind: 'TRANSFER', id: row.id } })
  await reply(row); expect(screen.getByRole('heading', { name: '交易详情' })).toBeTruthy()
  click('返回交易列表'); click('清除筛选'); await reply(overview)
  expect(port.sent.at(-1)).toMatchObject({ method: 'finance.transactions', payload: { query: '', page: 0, accountId: '' } })
  await reply(page()); expect(input.value).toBe('')
})
it('rejects a dropped search or unmatched description, validates input and discards responses after identity reset', async () => {
  const { port, bridge, reply, page } = await setup()
  const input = screen.getByLabelText('流水备注关键词') as HTMLInputElement
  for (const invalid of [' ', 'x\n', '中'.repeat(129), '\ud800']) {
    await expect(bridge.financeTransactions({ page: 0, size: 10, accountId: '', startDate: '', endDate: '', type: 'ALL', query: invalid })).rejects.toThrow()
  }
  fireEvent.change(input, { target: { value: '中'.repeat(129) } })
  expect((screen.getByRole('button', { name: '应用筛选' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(input, { target: { value: text } }); click('应用筛选'); await reply(overview)
  await reply({ ...page([row], '1'), query: '' })
  expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByText(row.description)).toBeNull()
  click('重试'); await reply(overview); await reply(page([{ ...row, description: 'unmatched' }], '1'))
  expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByText('unmatched')).toBeNull()
  click('重试'); await reply(overview)
  const late = port.sent.length - 1, response = page([row], '1')
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, response) })
  expect(input.value).toBe(''); expect(screen.queryByText(row.description)).toBeNull()
})
