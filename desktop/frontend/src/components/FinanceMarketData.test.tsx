import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { FinanceMarketData } from './FinanceMarketData'

afterEach(cleanup)
it('keeps unreviewed licenses locked and suppresses old M1 provider cache across sessions', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  let updates = 0
  const value = { provider: 'TWELVE_DATA', enabled: false, keyConfigured: false, retentionHours: 24, result: 'IDLE', fx: null }
  const view = render(<FinanceMarketData bridge={bridge} enabled updated={() => { updates++ }} />)
  const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }))
  const reply = async (v: unknown) => act(async () => { port.reply(port.sent.length - 1, v) })
  try {
    expect(port.sent).toHaveLength(0)
    expect((screen.getByRole('button', { name: '主动刷新 USD/CNY' }) as HTMLButtonElement).disabled).toBe(true)
    click('配置行情服务'); expect(port.sent.at(-1)).toMatchObject({ method: 'native.openMarketDataSettings', payload: {} })
    await reply({ opened: true }); expect(port.sent.at(-1)).toMatchObject({ method: 'finance.marketData', payload: {} }); await reply(value)
    expect(screen.getByText(/外部请求关闭 · 未配置 Key/)).toBeTruthy()
    click('查看行情状态与缓存汇率'); await reply({ ...value, enabled: true, keyConfigured: true })
    const sentBefore = port.sent.length
    click('主动刷新 USD/CNY'); expect(port.sent).toHaveLength(sentBefore)
    click('查看行情状态与缓存汇率')
    const stale = { ...value, enabled: true, keyConfigured: true, result: 'PROVIDER_FAILED', fx: { rate: '7.123456789012', time: '2026-10-23T14:00:00Z', fetchedAt: '2026-10-23T14:00:01Z', freshness: 'STALE' } }
    await reply(stale); expect(screen.queryByText(/1 USD =/)).toBeNull(); expect(updates).toBe(0)
    click('查看行情状态与缓存汇率'); const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, stale) })
    expect(screen.queryByText(/1 USD =/)).toBeNull(); expect(updates).toBe(0)
  } finally { view.unmount(); bridge.dispose() }
})
