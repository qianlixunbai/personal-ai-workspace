import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import type { FinancePositionDetail, FinancePositionPage, FinancePositionQuery, PositionCachedReference } from '../bridge/financeInvestments'
import { FinanceHoldingsChart } from './FinanceCharts'
import { FinanceMarketData } from './FinanceMarketData'
import { FinanceAmount, FinanceDataContext, quoteSource } from './FinanceAmount'
import { invalidFinanceIdentity, type FinanceIntent } from '../pages/financeNavigation'

const initial: FinancePositionQuery = { status: 'OPEN', accountId: '', instrumentId: '', cursor: '', size: 10 }
const states = { READY: '当前绑定身份已验证', DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请完成原生身份绑定。', BINDING_INVALID: 'Finance 绑定或数据集已改变，持仓读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。' }
const freshness = { FRESH: '缓存有效', STALE: '缓存已过期', PARTIAL: '输入不完整', UNAVAILABLE: '暂无可用估值' }
const warnings = { INVALID_INPUT: '数量无效，不能估值。', QUOTE_MISSING: '未提供可用参考行情。', QUOTE_STALE: '参考行情已过期。', FX_MISSING: '缺少换算汇率，CNY 参考值未知。', FX_STALE: '参考汇率已过期。' }
const amount = (v: string | null, currency = '') => <FinanceAmount value={v} currency={currency} />

export function FinanceInvestments({ bridge, enabled, openSettings, openHistory, intent, invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; openHistory?: (id: string, query: FinancePositionQuery) => void; intent?: Extract<FinanceIntent, { target: 'investments' }> | undefined; invalidateNavigation?: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null)
  const [list, setList] = useState<FinancePositionPage | null>(null)
  const [detail, setDetail] = useState<FinancePositionDetail | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState(initial)
  const [history, setHistory] = useState<string[]>([])
  const [account, setAccount] = useState(''), [instrument, setInstrument] = useState('')
  const [working, setWorking] = useState(false), [error, setError] = useState('')
  const [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false), heading = useRef<HTMLHeadingElement>(null)
  const entry = useRef(intent)

  useEffect(() => {
    const reset = () => {
      generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setSelected(null)
      entry.current = undefined
      setQuery(initial); setHistory([]); setAccount(''); setInstrument(''); setError(''); setWorking(false)
    }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(v => v + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    const pending = entry.current; entry.current = undefined
    if (enabled) { const next = pending?.query ?? initial; setAccount(next.accountId); setInstrument(next.instrumentId); void refresh(next, pending?.selection ?? null, []) }
    else { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setSelected(null); setQuery(initial); setHistory([]); setAccount(''); setInstrument(''); setWorking(false) }
  }, [enabled, bridge, runtimeReady])

  async function refresh(next = query, selection = selected, previous = history) {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setOverview(null); setList(null); setDetail(null); setQuery(next); setHistory(previous); setSelected(selection)
    try {
      const status = await bridge.financeOverview()
      if (current !== generation.current) return
      if (status.state !== 'READY') { setOverview(status); setSelected(null); setQuery(initial); setHistory([]); setAccount(''); setInstrument(''); invalidateNavigation?.(); return }
      const rows = await bridge.financePositions(next)
      if (current !== generation.current) return
      const record = selection ? await bridge.financePosition(selection) : null
      if (current !== generation.current) return
      setOverview(status); setList(rows); setDetail(record)
      if (record) setTimeout(() => { if (current === generation.current) heading.current?.focus() }, 0)
    } catch (problem) {
      if (current === generation.current) { setError(problem instanceof Error ? problem.message : '持仓读取失败，请重试。'); if (invalidFinanceIdentity(problem)) { setSelected(null); setQuery(initial); setHistory([]); setAccount(''); setInstrument(''); invalidateNavigation?.() } }
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  function filter(next: FinancePositionQuery) { void refresh({ ...next, cursor: '' }, null, []) }

  return <section className="finance-page" aria-label="Finance 只读投资持仓" aria-busy={working}>
    <section className="card">
      <div className="card-heading"><h2>投资持仓</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh({ ...query, cursor: '' }, selected, []) }}>刷新持仓</button></div>
      <p role="status">{working ? '正在验证身份并读取持仓…' : overview ? `${states[overview.state]}${overview.username ? ` · ${overview.username}` : ''}` : '等待持仓读取。'}</p>
      <p className="hint">仅包含交易驱动持仓（TRANSACTION_DRIVEN），全部状态也不包含 LEGACY 资产。账务事实、人工参考与缓存市场参考估值分别展示。刷新只重读缓存。</p>
      <p className="hint">当前筛选：{query.status} · 账户 {query.accountId || '全部'} · Instrument {query.instrumentId || '全部'}</p>
      <div className="finance-toolbar">
        <label>持仓状态<select aria-label="持仓状态" value={query.status} disabled={!enabled || working} onChange={e => filter({ ...query, status: e.target.value as FinancePositionQuery['status'] })}><option value="OPEN">持有 · OPEN</option><option value="CLOSED">已平仓 · CLOSED</option><option value="ALL">全部状态 · ALL</option></select></label>
        <label>账户 ID<input aria-label="持仓账户 ID" inputMode="numeric" maxLength={19} value={account} disabled={!enabled || working} onChange={e => setAccount(e.target.value)} /></label>
        <label>Instrument ID<input aria-label="持仓 Instrument ID" inputMode="numeric" maxLength={19} value={instrument} disabled={!enabled || working} onChange={e => setInstrument(e.target.value)} /></label>
        <button className="secondary" disabled={!enabled || working} onClick={() => filter({ ...query, accountId: account, instrumentId: instrument })}>应用持仓筛选</button>
        <label>每页<select aria-label="每页持仓数" value={query.size} disabled={!enabled || working} onChange={e => filter({ ...query, size: Number(e.target.value) })}><option value={2}>2 条</option><option value={10}>10 条</option><option value={20}>20 条</option></select></label>
        <button className="secondary" disabled={!enabled || working} onClick={() => { setAccount(''); setInstrument(''); filter(initial) }}>清除持仓筛选</button>
      </div>
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><div className="finance-toolbar"><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button><button disabled={!enabled || working} onClick={() => { void refresh({ ...query, cursor: '' }, null, []) }}>返回持仓首页</button></div></div>}
    <FinanceDataContext context={overview?.dataContext} />
    <FinanceMarketData bridge={bridge} enabled={enabled} position={detail} updated={() => { void refresh() }} />
    {list && <FinanceHoldingsChart value={list} onSelect={id => { void refresh(query, id) }} />}
    {list && <section className="card" aria-label="持仓列表">
      {list.records.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="持仓表格，可横向滚动"><table>
        <thead><tr><th>Instrument / 持仓 ID</th><th>账户 / 状态</th><th className="finance-amount">数量</th><th className="finance-amount">账务总成本 · CNY</th><th className="finance-amount">市场参考 · CNY</th></tr></thead>
        <tbody>{list.records.map(p => <tr key={p.positionId}>
          <td><button className="finance-account-link" aria-pressed={selected === p.positionId} onClick={() => { void refresh(query, p.positionId) }}>{p.instrument.name} · #{p.positionId}</button><small>{p.instrument.symbol} · {p.instrument.market} · Instrument {p.instrument.id}</small></td>
          <td>{p.account.displayName}<small>账户 {p.account.id} · {p.status}</small></td><td className="finance-amount">{amount(p.quantity)}</td><td className="finance-amount">{amount(p.totalCost)}</td>
          <td className="finance-amount">{amount(p.referenceValuation.value)}<small>{freshness[p.referenceValuation.freshness]}</small></td>
        </tr>)}</tbody></table></div> : <p>当前筛选下没有投资持仓。</p>}
      <div className="pagination"><button disabled={working || history.length === 0} onClick={() => { void refresh({ ...query, cursor: history[history.length - 1]! }, null, history.slice(0, -1)) }}>上一页</button>
        <span>第 {history.length + 1} 页 · 本页 {list.records.length} 项持仓</span>
        <button disabled={working || !list.hasMore || history.length >= 100} onClick={() => { if (list.nextCursor) void refresh({ ...query, cursor: list.nextCursor }, null, [...history, query.cursor]) }}>下一页</button></div>
      <p className="hint">按 Instrument、账户、持仓 ID 升序读取；返回时重新查询。筛选或每页数量变化及手动刷新均从首页开始，不保留旧页快照。最多保留本次 100 页的返回位置。</p>
    </section>}
    {detail && <section className="card finance-detail" aria-labelledby="finance-position-title">
      <div className="card-heading"><h2 id="finance-position-title" ref={heading} tabIndex={-1}>持仓详情 · {detail.instrument.name} · #{detail.positionId}</h2><span className="shell-badge">只读持仓</span></div>
      <div className="finance-toolbar">{openHistory && <button className="secondary" onClick={() => openHistory(detail.positionId, query)}>查看此持仓投资历史</button>}<button className="secondary" onClick={() => { setDetail(null); setSelected(null) }}>返回持仓列表</button></div>
      <h3>投资账务事实</h3><dl className="finance-summary">
        <div><dt>Position ID / 模式 / 状态</dt><dd>{detail.positionId} / {detail.positionMode} / {detail.status}</dd></div>
        <div><dt>账户 ID / 名称 / 类型 / 状态</dt><dd>{detail.account.id} / {detail.account.displayName} / {detail.account.type} / {detail.account.status}</dd></div>
        <div><dt>Instrument ID / 代码 / 名称</dt><dd>{detail.instrument.id} / {detail.instrument.symbol} / {detail.instrument.name}</dd></div>
        <div><dt>市场 / 资产类别 / 报价币种 / 状态</dt><dd>{detail.instrument.market} / {detail.instrument.assetClass} / {detail.instrument.quoteCurrency} / {detail.instrument.status}</dd></div>
        <div><dt>持仓数量</dt><dd className="finance-amount">{amount(detail.quantity)}</dd></div>
        <div><dt>平均成本 · average_cost</dt><dd className="finance-amount">{amount(detail.averageCost, 'CNY')}</dd></div>
        <div><dt>账务总成本 · total_cost</dt><dd className="finance-amount">{amount(detail.totalCost, 'CNY')}</dd></div>
        <div><dt>累计已实现盈亏 · cumulative_realized_profit_loss</dt><dd className="finance-amount">{amount(detail.cumulativeRealizedProfitLoss, 'CNY')}</dd></div>
      </dl><p className="hint">当前来源投资账本以 CNY 记账；Instrument 报价币种用于市场参考。总成本与已实现盈亏来自服务端持仓投影，不以当前价格推算，不计算收益率。</p>
      <h3>人工参考信息</h3><dl className="finance-summary"><div><dt>人工参考单价</dt><dd className="finance-amount">{amount(detail.manualReference.currentPrice, 'CNY')}</dd></div><div><dt>来源持久化人工参考市值</dt><dd className="finance-amount">{amount(detail.manualReference.marketValue, 'CNY')}</dd></div></dl>
      <p className="hint">按原详情服务返回持久化值，可能陈旧；来源没有提供独立人工报价时间，不用这些值计算盈亏。</p>
      <CachedReference value={detail.referenceValuation} />
    </section>}
  </section>
}

function CachedReference({ value: r }: { value: PositionCachedReference }) {
  return <section aria-label="持仓缓存市场参考估值"><h3>缓存市场参考估值 · {freshness[r.freshness]}</h3><dl className="finance-summary">
    <div><dt>行情单价 / 币种</dt><dd className="finance-amount">{amount(r.quotePrice, r.quoteCurrency ?? '')}</dd></div>
    <div><dt>行情来源 / 报价时间 / 获取时间（UTC）</dt><dd>{quoteSource(r.quoteProvider)} / {r.quoteTime ?? '未提供'} / {r.quoteFetchedAt ?? '未提供'}</dd></div>
    <div><dt>参考汇率</dt><dd>{r.fxRate === null ? '未知 / 未提供' : `1 ${r.fxBaseCurrency} = ${r.fxRate} ${r.fxQuoteCurrency}`}</dd></div>
    <div><dt>汇率来源 / 报价时间 / 获取时间（UTC）</dt><dd>{quoteSource(r.fxProvider)} / {r.fxRateTime ?? '未提供'} / {r.fxFetchedAt ?? '未提供'}</dd></div>
    <div><dt>CNY 市场参考值</dt><dd className="finance-amount">{amount(r.baseCurrencyValue, r.baseCurrency)}</dd></div>
  </dl>{r.warnings.length > 0 && <p role="status">{r.warnings.map(w => `${warnings[w.code]} [${w.component}]`).join(' ')}</p>}
    <p className="hint">投资账务事实 ≠ 人工价格估值 ≠ 缓存市场参考估值。缓存价格与汇率仅供参考，过期仍明确标识。来源未提供原币参考市值及估值计算时间，不在前端推算。</p>
  </section>
}
