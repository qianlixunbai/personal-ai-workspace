import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceMarketStatus } from '../bridge/financeMarketData'
import type { FinancePositionDetail } from '../bridge/financeInvestments'

const results: Record<string, string> = {
  IDLE: '已读取本地配置；本次没有请求服务商。', SAVED: 'Key 已安全保存；请主动刷新以验证实际数据访问权限。',
  UPDATED: '已获取并校验 Massive 日终参考价；不是实时行情。', KEY_REQUIRED: '请先在原生窗口保存 Massive API Key。',
  UNSUPPORTED: '仅支持 US / USD 的 STOCK、ETF；其他市场不会按同名代码猜测。',
  RATE_LIMITED: 'Basic 共用每分钟 5 次 REST 请求；请等待至少 60 秒再主动刷新。股票身份校验也占用请求。',
  ACCESS_DENIED: 'Massive 拒绝本次访问。请在官方账号检查 Key 和对应 Stocks / Currencies Basic 权限；本客户端不会购买或升级套餐。',
  LICENSE_UNVERIFIED: '本次用途没有经过许可审查，服务端已拒绝。', NO_DATA: '过去 14 天没有可用日终数据，或代码不受支持。合成持仓可改用 AAPL / SPY 查看入口。',
  PROVIDER_FAILED: '服务商不可用或网络失败；本次没有取得报价，不会自动重试。',
  IDENTITY_MISMATCH: '服务商返回的标的、市场、币种或类型不符，未展示报价。', INVALID_DATA: '服务商价格、日期或数据格式无效，未展示报价。',
}
export function FinanceMarketData({ bridge, enabled, position }: { bridge: WorkspaceClient; enabled: boolean; position?: FinancePositionDetail | null; updated: () => void }) {
  const [value, setValue] = useState<FinanceMarketStatus | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), inFlight = useRef(false), runtime = useRef(true)
  useEffect(() => {
    const clear = () => { generation.current++; inFlight.current = false; setValue(null); setError(''); setBusy(false) }
    const session = bridge.onSession(() => { runtime.current = true; clear() })
    const storage = bridge.onStorageStartup(s => { runtime.current = s.canQueryStorage; clear() })
    return () => { generation.current++; session(); storage() }
  }, [bridge])
  useEffect(() => { if (!enabled) { generation.current++; inFlight.current = false; setValue(null); setError(''); setBusy(false) } }, [enabled])
  useEffect(() => { generation.current++; inFlight.current = false; setValue(null); setBusy(false) }, [bridge, enabled, position?.positionId])
  async function run(action: 'read' | 'configure' | 'fx' | 'quote' | 'AAPL' | 'SPY') {
    if (!enabled || !runtime.current || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setBusy(true); setError('')
    try {
      if (action === 'configure') await bridge.open('native.openMarketDataSettings')
      if (current !== generation.current) return
      const result = action === 'AAPL' || action === 'SPY' ? await bridge.financeMarketReference(action)
        : action === 'quote' || action === 'fx' ? await bridge.financeMarketRefresh(action === 'quote' ? position!.positionId : '') : await bridge.financeMarketData()
      if (current !== generation.current) return
      setValue(result)
    } catch (problem) { if (current === generation.current) { setValue(null); setError(problem instanceof Error ? problem.message : '行情操作未完成。') } }
    finally { if (current === generation.current) { inFlight.current = false; setBusy(false) } }
  }
  const supported = position && position.instrument.market === 'US' && position.instrument.quoteCurrency === 'USD' && ['STOCK', 'ETF'].includes(position.instrument.assetClass)
  return <section className="card" aria-label="真实行情与汇率" aria-busy={busy}>
    <div className="card-heading"><h3>免费日终行情 · Massive</h3><span className="shell-badge">{value?.providerValidation === 'RESPONSE_VALIDATED' ? '本次响应已校验' : '本次 Provider · NOT RUN'}</span></div>
    <p>个人非商业、仅本人屏幕显示已开放。先读取配置状态，或配置免费 Basic Key，再主动刷新；报价不进入持仓人民币估值，不持久缓存，不支持离线回看。</p>
    <p className="hint">美股 / ETF 使用纽约交易日期，Basic 在纽约午夜后提供前一日数据；周末、节假日回看最近 14 天。FX 为 UTC 日参考价。以下服务商数据与原 Finance 合成旧缓存分开显示。</p>
    <div className="finance-toolbar">
      <button className="secondary" disabled={!enabled || busy || !runtime.current} onClick={() => { void run('configure') }}>配置行情服务</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current} onClick={() => { void run('read') }}>读取配置状态</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current || value?.enabled === false} onClick={() => { void run('AAPL') }}>查看 AAPL 日终价</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current || value?.enabled === false} onClick={() => { void run('SPY') }}>查看 SPY ETF 日终价</button>
      <button className="secondary" disabled={!enabled || busy || !runtime.current || value?.enabled === false} onClick={() => { void run('fx') }}>主动刷新 USD/CNY</button>
      {position && <button className="secondary" disabled={!enabled || busy || !runtime.current || value?.enabled === false || !supported} onClick={() => { void run('quote') }}>主动刷新 {position.instrument.symbol} 日终价</button>}
    </div>
    {position && !supported && <p className="hint">此持仓的市场 / 币种 / 类型不在支持范围内；仍可查看上方固定美股与 ETF。</p>}
    {value && <><p role="status">{value.keyConfigured ? 'Massive Key 已安全保存' : '未配置 Massive Key'}。{results[value.result]}</p>
      {[value.quote, value.fx].filter(v => v !== null).map(v => <dl className="finance-summary" key={v.symbol}>
        <div><dt>{v.symbol} · Massive 真实日终参考</dt><dd>{v.symbol === 'USD/CNY' ? `1 USD = ${v.price} CNY` : `${v.price} USD`}</dd></div>
        <div><dt>{v.symbol === 'USD/CNY' ? '参考日期' : '交易日期'} / 时区 / 来源</dt><dd>{v.date} / {v.timeZone} / Massive · {v.exchange} · 未复权日线 close</dd></div>
        <div><dt>日线窗口起点 / 本次获取时间（UTC）</dt><dd>{v.time} / {v.fetchedAt}</dd></div>
      </dl>)}
      {!value.quote && !value.fx && <p>尚无本次可展示的服务商报价；原 Finance 旧缓存请在持仓详情查看来源和时间。</p>}</>}
    {error && <p className="error" role="alert">{error} 结果未知时请先读取状态，不要重复刷新。</p>}
    {!position && <p className="hint">AAPL / SPY 为固定行情查看入口，不会创建持仓。选中投资持仓后可刷新其日终价；合成代码不保证存在真实行情。</p>}
  </section>
}
