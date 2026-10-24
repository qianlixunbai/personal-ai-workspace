import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import type { FinancePortfolio as Portfolio } from '../bridge/financePortfolio'
import { invalidFinanceIdentity, type FinanceIntent } from '../pages/financeNavigation'

const states = { READY: '当前绑定身份已验证', DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请完成原生身份绑定。', BINDING_INVALID: 'Finance 绑定或数据集已改变，组合统计读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。' }
const freshness = { FRESH: '完整覆盖 · 缓存有效', STALE: '完整覆盖 · 缓存已过期', PARTIAL: '部分覆盖 · 仅含已估值部分', UNAVAILABLE: '暂无可用估值' }
const warnings = { INVALID_INPUT: '存在无效数量，相关持仓不能估值。', QUOTE_MISSING: '存在缺少可用参考行情的持仓。', QUOTE_STALE: '参考行情已过期，估值包含过期信息。', FX_MISSING: '存在缺少换算汇率的持仓，其 CNY 参考值未知。', FX_STALE: '参考汇率已过期，估值包含过期信息。' }

export function FinancePortfolio({ bridge, enabled, openSettings, navigate, invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; navigate: (intent: FinanceIntent) => void; invalidateNavigation: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null), [value, setValue] = useState<Portfolio | null>(null)
  const [working, setWorking] = useState(false), [error, setError] = useState(''), [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setOverview(null); setValue(null); setError(''); setWorking(false) }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(v => v + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    if (enabled) void refresh()
    else { generation.current++; inFlight.current = false; setOverview(null); setValue(null); setError(''); setWorking(false) }
  }, [enabled, bridge, runtimeReady])
  async function refresh() {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setOverview(null); setValue(null)
    try {
      const status = await bridge.financeOverview()
      if (current !== generation.current) return
      if (status.state !== 'READY') { setOverview(status); invalidateNavigation(); return }
      const result = await bridge.financePortfolio()
      if (current !== generation.current) return
      setOverview(status); setValue(result)
    } catch (problem) {
      if (current === generation.current) { setError(problem instanceof Error ? problem.message : '组合统计读取失败，请重试。'); if (invalidFinanceIdentity(problem)) invalidateNavigation() }
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const r = value?.referenceValuation
  return <section className="finance-page" aria-label="Finance 只读投资组合统计" aria-busy={working}>
    <section className="card"><div className="card-heading"><h2>Portfolio Summary · 投资组合统计</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}>刷新组合统计</button></div>
      <p role="status">{working ? '正在验证身份并读取组合统计…' : overview ? `${states[overview.state]}${overview.username ? ` · ${overview.username}` : ''}` : '等待组合统计读取。'}</p>
      <p className="hint">当前用户全部交易驱动持仓，不受持仓列表分页或筛选影响；不包含 LEGACY 资产。刷新只重读缓存。</p>
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button></div>}
    {value && r && <>
      <section className="card" aria-label="组合账务统计"><h3>投资账务事实 · CNY</h3>
        <div className="finance-toolbar">{(['ALL', 'OPEN', 'CLOSED'] as const).map(status => <button className="secondary" key={status} onClick={() => navigate({ source: 'portfolio', target: 'investments', query: { status, accountId: '', instrumentId: '', cursor: '', size: 10 } })}>查看{status === 'ALL' ? '全部' : status}持仓</button>)}<button className="secondary" onClick={() => navigate({ source: 'portfolio', target: 'history', positionId: '' })}>查看投资交易历史</button></div>
        {value.positionCount === 0 ? <p>当前组合为空：没有交易驱动持仓。</p> : value.openPositionCount === 0 && <p>全部持仓已平仓，没有应估值的 OPEN 持仓；累计已实现盈亏仍保留。</p>}
        <dl className="finance-summary"><div><dt>总持仓数量</dt><dd>{value.positionCount}</dd></div><div><dt>OPEN 持仓数量</dt><dd>{value.openPositionCount}</dd></div><div><dt>CLOSED 持仓数量</dt><dd>{value.closedPositionCount}</dd></div>
          <div><dt>OPEN 持仓总账务成本</dt><dd className="finance-amount">{value.openTotalCost} CNY</dd></div><div><dt>累计已实现盈亏 · 全部持仓</dt><dd className="finance-amount">{value.cumulativeRealizedProfitLoss} CNY</dd></div></dl>
        <p className="hint">沿用来源 CNY 账务口径，直接读取持仓投影。人工参考价格和市场估值不计入账务金额。</p>
      </section>
      <section className="card" aria-label="组合缓存市场参考估值"><h3>{r.freshness === 'PARTIAL' ? '已估值部分的缓存市场参考值' : '缓存市场参考估值'} · CNY</h3>
        <dl className="finance-summary"><div><dt>参考值</dt><dd className="finance-amount">{r.value === null ? '未知 / 无可用估值' : `${r.value} CNY`}</dd></div>
          <div><dt>已估值 / 应估值持仓</dt><dd>{r.valuedPositionCount} / {r.totalOpenPositionCount}</dd></div><div><dt>来源估值状态</dt><dd>{r.freshness} · {freshness[r.freshness]}</dd></div></dl>
        {r.totalOpenPositionCount === 0 ? <p>没有 OPEN 持仓，来源返回 0/0、null 与 UNAVAILABLE，不表示行情读取失败。</p>
          : r.freshness === 'PARTIAL' ? <p>此金额仅覆盖已估值持仓，不能作为完整组合总市值；其余持仓金额未知。</p>
          : r.freshness === 'UNAVAILABLE' && <p>所有 OPEN 持仓均无法完成估值，金额保持未知。</p>}
        {!!r.warnings.length && <ul>{r.warnings.map((w, i) => <li key={i}>{warnings[w.code]} · {w.code} / {w.component}</li>)}</ul>}
        <p className="hint">仅供市场参考；按来源逐持仓估值和舍入后汇总。缺失行情或汇率不按零计入，不推导未实现盈亏、收益率或净资产。PARTIAL 状态下仍需查看过期提示。</p>
      </section>
    </>}
  </section>
}
