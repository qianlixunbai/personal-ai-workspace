import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceMarketStatus } from '../bridge/financeMarketData'
import type { FinancePositionDetail } from '../bridge/financeInvestments'

const results: Record<string, string> = {
  IDLE: '已读取本地状态，未连接服务商。', UPDATED: '已获取服务商报价；请以报价时间和过期状态为准。', CACHED: '缓存仍有效，未重复请求服务商。',
  DISABLED: '外部请求关闭，请先在原生窗口配置。', UNSUPPORTED: 'M1 仅支持 US / USD 的 STOCK、ETF；其他市场不会按同名代码猜测。',
  RATE_LIMITED: '已达到额度或限流冷却期；稍后主动刷新，原缓存保留。', ACCESS_DENIED: '服务商拒绝访问，请核对 Key、套餐及数据权限。',
  LICENSE_UNVERIFIED: '展示与缓存许可未核验，服务端已阻止真实请求及 Twelve Data 缓存展示。保存 Key 或用户勾选不能获得授权。',
  PROVIDER_FAILED: '服务商不可用或网络失败，原缓存保留；不会自动重试。', IDENTITY_MISMATCH: '返回的市场、币种或标的类型不符，已拒绝写入缓存。', INVALID_DATA: '服务商报价或时间无效，已拒绝写入缓存。',
}
export function FinanceMarketData({ bridge, enabled, position, updated }: { bridge: WorkspaceClient; enabled: boolean; position?: FinancePositionDetail | null; updated: () => void }) {
  const [value, setValue] = useState<FinanceMarketStatus | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), inFlight = useRef(false), runtime = useRef(true)
  useEffect(() => {
    const clear = () => { generation.current++; inFlight.current = false; setValue(null); setError(''); setBusy(false) }
    const session = bridge.onSession(() => { runtime.current = true; clear() })
    const storage = bridge.onStorageStartup(s => { runtime.current = s.canQueryStorage; clear() })
    return () => { generation.current++; session(); storage() }
  }, [bridge])
  useEffect(() => { if (!enabled) { generation.current++; inFlight.current = false; setValue(null); setError(''); setBusy(false) } }, [enabled])
  async function run(action: 'read' | 'configure' | 'fx' | 'quote') {
    if (!enabled || !runtime.current || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setBusy(true); setError('')
    try {
      if (action === 'configure') await bridge.open('native.openMarketDataSettings')
      if (current !== generation.current) return
      const result = action === 'quote' || action === 'fx' ? await bridge.financeMarketRefresh(action === 'quote' ? position!.positionId : '') : await bridge.financeMarketData()
      if (current !== generation.current) return
      setValue(result)
      if (action === 'quote' || action === 'fx') updated()
    } catch (problem) { if (current === generation.current) { setValue(null); setError(problem instanceof Error ? problem.message : '行情操作未完成。') } }
    finally { if (current === generation.current) { inFlight.current = false; setBusy(false) } }
  }
  const supported = position && position.instrument.market === 'US' && position.instrument.quoteCurrency === 'USD' && ['STOCK', 'ETF'].includes(position.instrument.assetClass)
  return <section className="card" aria-label="真实行情与汇率" aria-busy={busy}>
    <div className="card-heading"><h3>行情与汇率 · Twelve Data</h3><span className="shell-badge">真实 Provider · NOT RUN</span></div>
    <p role="status">展示与缓存许可：未核验 · 真实刷新及 Twelve Data 缓存展示已锁定。已有参考值来自旧缓存，不表示本次联网成功。</p>
    <ol><li>查看配置状态；Key 已保存只表示凭据存在，不表示验证成功。</li><li>取得账号针对股票、ETF、USD/CNY 的展示及缓存期限许可，并完成独立核验。本版本没有将勾选或套餐名称转换为授权的入口。</li><li>许可核验及受控真实 Provider 验证完成后，才可解锁逐持仓刷新；当前请核对旧缓存来源与时间。</li></ol>
    <div className="finance-toolbar">
      <button className="secondary" disabled={!enabled || busy || !runtime.current} onClick={() => { void run('configure') }}>配置行情服务</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current} onClick={() => { void run('read') }}>查看行情状态与缓存汇率</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current || !value?.enabled} onClick={() => { void run('fx') }}>主动刷新 USD/CNY</button>
      {position && <button className="secondary" disabled={!enabled || busy || !runtime.current || !value?.enabled || !supported} onClick={() => { void run('quote') }}>主动刷新 {position.instrument.symbol} 行情与汇率</button>}
    </div>
    {position && !supported && <p className="hint">此标的市场 / 币种 / 类型不在 M1 支持范围内。</p>}
    {value && <><p role="status">{value.enabled ? '外部请求已启用' : '外部请求关闭'} · {value.keyConfigured ? 'Key 已安全保存（未验证）' : '未配置 Key'} · 历史保留设置 {value.retentionHours} 小时（不代表获得缓存许可）。{value.result.startsWith('PARTIAL_') ? `股票报价可用；汇率刷新未完成。${results[value.result.slice(8)]}` : results[value.result]}</p>
      {value.fx ? <dl className="finance-summary"><div><dt>USD/CNY · 服务商缓存</dt><dd>1 USD = {value.fx.rate} CNY · {value.fx.freshness === 'STALE' ? '已过期' : '缓存有效'}</dd></div><div><dt>来源 / 报价 / 获取时间（UTC）</dt><dd>Twelve Data / {value.fx.time} / {value.fx.fetchedAt}</dd></div></dl> : <p>Twelve Data 汇率不展示：许可未核验。旧缓存汇率请在持仓详情查看来源和时间。</p>}</>}
    {error && <p className="error" role="alert">{error} 结果未知时请先读取状态，不要重复刷新。</p>}
    {!position && <p className="hint">当前可在投资持仓查看旧缓存来源、时间和估值。真实逐项刷新须先完成许可核验与受控 Provider 验证；合成标的不保证能真实报价。</p>}
  </section>
}
