import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { FinanceMarketData } from './FinanceMarketData'

afterEach(cleanup)
it('enables personal EOD display, keeps calculations closed and discards late session data', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const value = { provider: 'MASSIVE', enabled: false, keyConfigured: false, result: 'IDLE', quote: null, fx: null,
    rights: { display: true, displayMemory: true, persistence: false, offline: false, valuation: false },
    licenseStatus: 'PERSONAL_DISPLAY_ONLY', providerValidation: 'NOT_RUN', dataContext: 'SYNTHETIC' }
  const view = render(<FinanceMarketData bridge={bridge} enabled updated={() => { throw new Error('display cannot trigger valuation refresh') }} />)
  const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }))
  const reply = async (v: unknown) => act(async () => { port.reply(port.sent.length - 1, v) })
  try {
    expect(port.sent).toHaveLength(0); click('读取配置状态'); await reply(value)
    expect((screen.getByRole('button', { name: '主动刷新 USD/CNY' }) as HTMLButtonElement).disabled).toBe(true)
    click('配置行情服务'); expect(port.sent.at(-1)).toMatchObject({ method: 'native.openMarketDataSettings', payload: {} })
    await reply({ opened: true }); await reply({ ...value, enabled: true, keyConfigured: true })
    click('查看 SPY ETF 日终价'); expect(port.sent.at(-1)).toMatchObject({ method: 'finance.marketRefresh', payload: { referenceSymbol: 'SPY' } })
    const shown = { ...value, enabled: true, keyConfigured: true, result: 'UPDATED', providerValidation: 'RESPONSE_VALIDATED',
      quote: { symbol: 'SPY', price: '100.12345678', time: '2026-10-23T04:00:00Z', fetchedAt: '2026-10-26T14:00:00Z', exchange: 'ARCX', date: '2026-10-23', timeZone: 'America/New_York' } }
    await reply(shown)
    expect(screen.getByText('100.12345678 USD')).toBeTruthy(); expect(screen.getByText(/2026-10-23 \/ America\/New_York/)).toBeTruthy()
    expect(screen.getByText(/报价不进入持仓人民币估值/)).toBeTruthy()
    const fx = { symbol: 'USD/CNY', price: '', time: '2026-10-23T00:00:00Z', fetchedAt: '2026-10-26T14:00:00Z', exchange: 'FOREX', date: '2026-10-23', timeZone: 'UTC' }
    for (const price of ['7.' + '123456789'.repeat(5), '0.' + '0'.repeat(125) + '1']) {
      click('主动刷新 USD/CNY'); await reply({ ...shown, quote: null, fx: { ...fx, price } })
      expect(screen.getByText(`1 USD = ${price} CNY`)).toBeTruthy()
    }
    for (const price of ['0.' + '0'.repeat(126) + '1', '100000000000000000000', '0', 'NaN']) {
      click('主动刷新 USD/CNY'); await reply({ ...shown, quote: null, fx: { ...fx, price } })
      expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByText(`1 USD = ${price} CNY`)).toBeNull()
    }
    click('主动刷新 USD/CNY'); await reply({ ...value, enabled: true, keyConfigured: true, result: 'ACCESS_DENIED' })
    expect(screen.getByText(/Massive 拒绝本次访问/)).toBeTruthy()
    expect((screen.getByRole('button', { name: '查看 AAPL 日终价' }) as HTMLButtonElement).disabled).toBe(false)
    click('主动刷新 USD/CNY')
    const diagnostic = { ...value, enabled: true, keyConfigured: true, result: 'INVALID_DATA', providerValidation: 'NOT_VALIDATED', diagnostic: { stage: 'FOREX_BARS', reason: 'PRICE_SCALE' } }
    await reply(diagnostic)
    expect(screen.getByText(/FOREX_BARS \/ PRICE_SCALE/)).toBeTruthy(); expect(screen.getByText('本次未取得有效报价')).toBeTruthy()
    expect(screen.queryByText('本次 Provider · NOT RUN')).toBeNull()
    click('主动刷新 USD/CNY'); await reply({ ...diagnostic, diagnostic: { ...diagnostic.diagnostic, reason: 'synthetic_key' } })
    expect(screen.queryByText(/synthetic_key/)).toBeNull(); expect(screen.getByRole('alert')).toBeTruthy()
    click('查看 SPY ETF 日终价'); await reply({ ...shown, rights: { ...shown.rights, valuation: true } })
    expect(screen.queryByText('100.12345678 USD')).toBeNull(); expect(screen.getByRole('alert')).toBeTruthy()
    click('读取配置状态'); await reply({ ...value, enabled: true, keyConfigured: true })
    click('查看 SPY ETF 日终价'); const late = port.sent.length - 1
    await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(late, shown) })
    expect(screen.queryByText('100.12345678 USD')).toBeNull()
  } finally { view.unmount(); bridge.dispose() }
})
