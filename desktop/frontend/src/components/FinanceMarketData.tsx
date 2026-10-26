import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceMarketStatus } from '../bridge/financeMarketData'
import type { FinancePositionDetail } from '../bridge/financeInvestments'

const results: Record<string, string> = {
  IDLE: '已读取本地状态，未连接服务商。', UPDATED: '已获取服务商报价；请以报价时间和过期状态为准。', CACHED: '缓存仍有效，未重复请求服务商。',
  DISABLED: '外部请求关闭，请先在原生窗口配置。', UNSUPPORTED: 'M1 仅支持 US / USD 的 STOCK、ETF；其他市场不会按同名代码猜测。',
  RATE_LIMITED: '已达到额度或限流冷却期；稍后主动刷新，原缓存保留。', ACCESS_DENIED: '服务商拒绝访问，请核对 Key、套餐及数据权限。',
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
    <div className="card-heading"><h3>行情与汇率 · Twelve Data</h3><span className="shell-badge">参考估值</span></div>
    <p className="hint">读取页面与原刷新按钮只读缓存。行情刷新仅在原生窗口启用后联网；不自动轮询。缓存有效 ≠ 实时，休市或延迟数据仍以服务商报价时间为准。</p>
    <div className="finance-toolbar">
      <button className="secondary" disabled={!enabled || busy || !runtime.current} onClick={() => { void run('configure') }}>配置行情服务</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current} onClick={() => { void run('read') }}>查看行情状态与缓存汇率</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current || !value?.enabled} onClick={() => { void run('fx') }}>主动刷新 USD/CNY</button>
      {position && <button className="secondary" disabled={!enabled || busy || !runtime.current || !value?.enabled || !supported} onClick={() => { void run('quote') }}>主动刷新 {position.instrument.symbol} 行情与汇率</button>}
    </div>
    {position && !supported && <p className="hint">此标的市场 / 币种 / 类型不在 M1 支持范围内。</p>}
    {value && <><p role="status">{value.enabled ? '外部请求已启用' : '外部请求关闭'} · {value.keyConfigured ? 'Key 已安全保存' : '未配置 Key'} · 保留 {value.retentionHours} 小时。{value.result.startsWith('PARTIAL_') ? `股票报价可用；汇率刷新未完成。${results[value.result.slice(8)]}` : results[value.result]}</p>
      {value.fx ? <dl className="finance-summary"><div><dt>USD/CNY · 服务商缓存</dt><dd>1 USD = {value.fx.rate} CNY · {value.fx.freshness === 'STALE' ? '已过期' : '缓存有效'}</dd></div><div><dt>来源 / 报价 / 获取时间（UTC）</dt><dd>Twelve Data / {value.fx.time} / {value.fx.fetchedAt}</dd></div></dl> : <p>尚无 M1 缓存汇率；原 Finance 缓存仍由持仓详情显示。</p>}</>}
    {error && <p className="error" role="alert">{error} 结果未知时请先读取状态，不要重复刷新。</p>}
    {!position && <p className="hint">进入投资持仓并选择详情后，可逐项主动刷新美股 / ETF；Portfolio 覆盖率随 Java 重新估值更新。</p>}
  </section>
}
