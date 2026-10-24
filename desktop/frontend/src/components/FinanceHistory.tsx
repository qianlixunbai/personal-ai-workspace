import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import { isFinanceHistoryQuery, type FinanceHistoryQuery, type FinanceHistoryPage, type FinanceHistoryDetail, type FinanceHistoryTimeline, type HistoryValues, type HistoryReceipt, type HistoryPosition } from '../bridge/financeHistory'
import { invalidFinanceIdentity } from '../pages/financeNavigation'

const initial: FinanceHistoryQuery = { positionId: '', accountId: '', instrumentId: '', type: '', correctionStatus: '', from: '', to: '', cursor: '', size: 10 }
const states = { READY: '当前绑定身份已验证', DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请完成原生身份绑定。', BINDING_INVALID: 'Finance 绑定或数据集已改变，读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。' }
const amount = (v: string | null, unit = 'CNY') => v === null ? 'null · 未知 / 未提供' : `${v}${unit ? ` ${unit}` : ''}`
const time = (v: string | null) => v === null ? 'null · 未提供' : v
const correctionText: Record<string, string> = { UNCHANGED: '未修正 · 原事实有效', REVERSED: '已冲正 · 逻辑交易不再有效，事实仍保留', REPLACED: '已替换 · 以替换事实生效，原事实仍保留' }

export function FinanceHistory({ bridge, enabled, openSettings, initialPosition = '', invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; initialPosition?: string; invalidateNavigation?: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null)
  const [list, setList] = useState<FinanceHistoryPage | null>(null)
  const [detail, setDetail] = useState<FinanceHistoryDetail | null>(null)
  const [timeline, setTimeline] = useState<FinanceHistoryTimeline | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState({ ...initial, positionId: initialPosition })
  const [filters, setFilters] = useState({ ...initial, positionId: initialPosition })
  const [pages, setPages] = useState<string[]>([])
  const [working, setWorking] = useState(false), [error, setError] = useState('')
  const [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false), heading = useRef<HTMLHeadingElement>(null)
  const entry = useRef(initialPosition)
  useEffect(() => {
    const reset = () => {
      generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setTimeline(null); setSelected(null)
      entry.current = ''
      setQuery(initial); setFilters(initial); setPages([]); setError(''); setWorking(false)
    }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(v => v + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    const start = { ...initial, positionId: entry.current }; entry.current = ''
    if (enabled) { setFilters(start); void refresh(start, null, []) }
    else { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setTimeline(null); setSelected(null); setQuery(initial); setFilters(initial); setPages([]); setWorking(false); setError('') }
  }, [enabled, bridge, runtimeReady])

  async function refresh(next = query, id = selected, back = pages) {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setOverview(null); setList(null); setDetail(null); setTimeline(null); setSelected(id); setQuery(next); setPages(back)
    try {
      const identity = await bridge.financeOverview()
      if (current !== generation.current) return
      if (identity.state !== 'READY') { setOverview(identity); setSelected(null); setQuery(initial); setFilters(initial); setPages([]); invalidateNavigation?.(); return }
      const records = await bridge.financeHistory(next)
      if (current !== generation.current) return
      const transaction = id ? await bridge.financeHistoryDetail(id) : null
      if (current !== generation.current) return
      const audit = id ? await bridge.financeHistoryTimeline(id) : null
      if (current !== generation.current) return
      // Never combine a detail and timeline that observed different correction states.
      if (transaction && audit && transaction.correctionStatus !== audit.correctionStatus) throw new Error('交易修正状态在读取期间变化，请刷新。')
      setOverview(identity); setList(records); setDetail(transaction); setTimeline(audit)
      if (transaction) setTimeout(() => { if (current === generation.current) heading.current?.focus() }, 0)
    } catch (problem) {
      if (current === generation.current) { setError(problem instanceof Error ? problem.message : '投资历史读取失败，请重试。'); if (invalidFinanceIdentity(problem)) { setSelected(null); setQuery(initial); setFilters(initial); setPages([]); invalidateNavigation?.() } }
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const field = (key: keyof FinanceHistoryQuery, value: string) => setFilters(v => ({ ...v, [key]: value }))
  function apply() {
    const q = { ...filters, cursor: '' }
    if (!isFinanceHistoryQuery(q)) { setError('请填写正整数 ID 和完整 UTC Instant，例如 2026-10-01T08:00:00Z。'); return }
    void refresh(q, null, [])
  }
  return <section className="finance-page" aria-label="Investment History 只读投资历史" aria-busy={working}>
    <section className="card"><div className="card-heading"><h2>Investment History · 投资交易历史</h2><button disabled={!enabled || working} onClick={() => { void refresh({ ...query, cursor: '' }, selected, []) }}>刷新投资历史</button></div>
      <p role="status">{working ? '正在验证身份并读取投资历史…' : overview ? states[overview.state] : '等待读取投资历史。'}</p>
      {overview?.state === 'READY' && <p>当前 Finance 用户：{overview.username}</p>}
      <p className="hint">逻辑交易与不可变物理事实分别展示。账务金额为来源 CNY；Instrument 报价币种不改变账本口径。全部成本、盈亏、回执和当前持仓均由服务端提供。</p>
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    <section className="card" aria-label="投资历史筛选"><form onSubmit={e => { e.preventDefault(); apply() }}><div className="finance-toolbar">
      {([['positionId', '历史 Position ID'], ['accountId', '历史 Account ID'], ['instrumentId', '历史 Instrument ID']] as const).map(([key, label]) => <label key={key}>{label}<input aria-label={label} inputMode="numeric" maxLength={19} value={filters[key]} disabled={working} onChange={e => field(key, e.target.value)} /></label>)}
      <label>交易类型<select aria-label="投资交易类型" value={filters.type} disabled={working} onChange={e => field('type', e.target.value)}><option value="">全部类型</option>{['OPENING_POSITION', 'BUY', 'SELL', 'DIVIDEND'].map(v => <option key={v}>{v}</option>)}</select></label>
      <label>修正状态<select aria-label="投资修正状态" value={filters.correctionStatus} disabled={working} onChange={e => field('correctionStatus', e.target.value)}><option value="">全部修正状态</option>{['UNCHANGED', 'REVERSED', 'REPLACED'].map(v => <option key={v}>{v}</option>)}</select></label>
      <label>起始 Instant · UTC<input aria-label="历史起始 UTC Instant" placeholder="2026-10-01T08:00:00Z" value={filters.from} disabled={working} maxLength={40} onChange={e => field('from', e.target.value)} /></label>
      <label>结束 Instant · UTC<input aria-label="历史结束 UTC Instant" placeholder="2026-10-02T08:00:00Z" value={filters.to} disabled={working} maxLength={40} onChange={e => field('to', e.target.value)} /></label>
      <label>每页<select aria-label="每页投资历史数" value={filters.size} disabled={working} onChange={e => setFilters(v => ({ ...v, size: Number(e.target.value) }))}><option value={2}>2 条</option><option value={10}>10 条</option><option value={20}>20 条</option></select></label>
      <button disabled={!enabled || working}>应用历史筛选</button><button type="button" className="secondary" disabled={!enabled || working} onClick={() => { setFilters(initial); void refresh(initial, null, []) }}>清除历史筛选</button>
    </div></form><p className="hint">有效交易时间区间为 [from, to)：包含起点，排除终点。使用带 Z 的 UTC Instant，保留原始精度，不按本地自然日转换。留空表示不限制。筛选和刷新从首页重新读取。</p></section>
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试投资历史</button></div>}
    {list && <section className="card"><h2>逻辑投资交易</h2>{list.records.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="投资历史表格，可横向滚动"><table><thead><tr><th>逻辑交易 / Instrument</th><th>账户 / Position</th><th>有效交易时间 · UTC</th><th>类型 / 修正</th><th>数量</th><th>净额 · CNY</th></tr></thead><tbody>
      {list.records.map(p => <tr key={p.logicalTransactionId}><td><button className="finance-account-link" aria-pressed={selected === p.logicalTransactionId} onClick={() => { void refresh(query, p.logicalTransactionId) }}>交易 #{p.logicalTransactionId}</button><small>{p.instrument.symbol} · {p.instrument.name} · Instrument {p.instrument.id}</small></td><td>{p.account.displayName}<small>账户 {p.account.id} · Position {p.positionId}</small></td><td>{p.effectiveTradeTime}</td><td>{p.transactionType}<small>{p.correctionStatus} · {p.effective ? '有效' : '不再有效'}</small></td><td className="finance-amount">{amount(p.quantity, '')}</td><td className="finance-amount">{amount(p.netAmount, '')}</td></tr>)}
    </tbody></table></div> : <p>当前筛选下没有投资交易历史。</p>}
      <div className="pagination"><button disabled={working || !pages.length} onClick={() => { void refresh({ ...query, cursor: pages[pages.length - 1]! }, null, pages.slice(0, -1)) }}>上一页</button><span>第 {pages.length + 1} 页 · 本页 {list.records.length} 笔逻辑交易</span><button disabled={working || !list.hasMore || pages.length >= 100} onClick={() => { if (list.nextCursor) void refresh({ ...query, cursor: list.nextCursor }, null, [...pages, query.cursor]) }}>下一页</button></div>
      <p className="hint">按有效交易时间、逻辑 ID 降序。使用来源不透明 Cursor，返回上一页也重新查询；最多保留本次 100 页返回位置。</p>
    </section>}
    {detail && <section className="card finance-detail" aria-label="投资交易详情"><h2 ref={heading} tabIndex={-1}>投资交易详情 · #{detail.logicalTransactionId}</h2><dl className="finance-summary">
      <div><dt>Logical Transaction ID / 类型</dt><dd>{detail.logicalTransactionId} / {detail.transactionType}</dd></div><div><dt>修正状态 / 有效性</dt><dd>{detail.correctionStatus} / {correctionText[detail.correctionStatus]}</dd></div>
      <div><dt>账户</dt><dd>{detail.account.id} / {detail.account.displayName}</dd></div><div><dt>Instrument</dt><dd>{detail.instrument.id} / {detail.instrument.symbol} / {detail.instrument.name} / {detail.instrument.market} / {detail.instrument.assetClass} / {detail.instrument.quoteCurrency}</dd></div>
      <div><dt>有效交易时间 · UTC</dt><dd>{detail.effectiveTradeTime}</dd></div><div><dt>结算时间 · UTC</dt><dd>{time(detail.settlementTime)}</dd></div>
    </dl><h3>原始交易事实</h3><Values value={detail.originalBusinessValues} />
      <h3>当前有效交易事实</h3>{detail.effectiveBusinessValues ? <Values value={detail.effectiveBusinessValues} /> : <p>{detail.correctionStatus === 'UNCHANGED' ? '来源 effectiveBusinessValues=null；未发生替换，原始事实仍有效。' : '不适用：已独立冲正，无当前有效业务事实。'}</p>}
      <h3>修正关系</h3>{detail.correction ? <dl className="finance-summary"><div><dt>修正创建时间 · UTC</dt><dd>{detail.correction.createdAt}</dd></div><div><dt>原因</dt><dd>{detail.correction.reason ?? 'null · 未提供'}</dd></div><div><dt>冲正物理事实 ID</dt><dd>{detail.correction.reversalTransactionId}</dd></div><div><dt>替换物理事实 ID</dt><dd>{detail.correction.replacementTransactionId ?? '不适用：独立冲正'}</dd></div></dl> : <p>不适用：未修正。</p>}
      <Receipt title="原始 Posting Receipt · 原过账时快照" value={detail.postingReceipt} absent="null · 来源未提供原始过账回执" />
      <Receipt title="Correction Final Receipt · 修正完成后快照" value={detail.correctionFinalReceipt} absent={detail.correctionStatus === 'UNCHANGED' ? '不适用：未修正' : 'null · 来源未提供'} />
      <CurrentPosition value={detail.currentPosition} />
    </section>}
    {timeline && <section className="card finance-detail" aria-label="Immutable Audit Timeline"><h2>Immutable Audit Timeline · 不可变审计时间线</h2><p>逻辑交易 #{timeline.logicalTransactionId} · {timeline.correctionStatus}。事件按来源语义分组；修正不是删除历史，Receipt 也不是当前余额。</p>
      <ol>{timeline.events.map((e, index) => <li key={index}><h3>{e.eventKind}</h3><dl className="finance-summary"><div><dt>事件创建时间 · UTC</dt><dd>{e.createdAt}</dd></div><div><dt>物理事实 ID / 原事实 ID</dt><dd>{e.physicalFactId ?? '不适用：替换命令'} / {e.originalFactId ?? '不适用：原始过账'}</dd></div><div><dt>事实类型 / 回执适用性</dt><dd>{e.factType ?? '不适用：命令'} / {e.receiptApplicability}</dd></div><div><dt>原因</dt><dd>{e.reason ?? 'null · 未提供'}</dd></div></dl>
        {e.businessValues && <Values value={e.businessValues} />}
        {e.postingReceipt && <Receipt title="Posting Receipt · 该原始过账快照" value={e.postingReceipt} absent="" />}
        {e.eventKind === 'ORIGINAL_POSTING' && !e.postingReceipt && <p>Posting Receipt：null · 来源未提供。</p>}
        {e.correctionFinalReceipt && <Receipt title="Correction Final Receipt · 该修正最终快照" value={e.correctionFinalReceipt} absent="" />}
        {e.facts.map(f => <section key={f.physicalFactId}><h4>{f.role} · 物理事实 #{f.physicalFactId}</h4><p>{f.factType} / {f.receiptApplicability}{f.role === 'GROUPED_REVERSAL' ? ' · 不适用独立 Receipt；使用替换命令最终回执。' : ' · 关联替换命令的最终回执。'}</p><Values value={f.businessValues} /></section>)}
      </li>)}</ol><CurrentPosition value={timeline.currentPosition} />
    </section>}
  </section>
}
function Values({ value: v }: { value: HistoryValues }) {
  return <dl className="finance-summary">{([['quantity', '数量'], ['unitPrice', '单价'], ['grossAmount', '总额'], ['feeAmount', '手续费'], ['taxAmount', '税费'], ['netAmount', '净额'], ['releasedCostAmount', '释放成本'], ['realizedProfitLoss', '已实现盈亏']] as const).map(([key, label]) => <div key={key}><dt>{label}</dt><dd className="finance-amount">{amount(v[key], key === 'quantity' ? '' : 'CNY')}</dd></div>)}<div><dt>备注</dt><dd>{v.note ?? 'null · 未提供'}</dd></div><div><dt>外部引用</dt><dd>{v.externalReference ?? 'null · 未提供'}</dd></div></dl>
}
function Receipt({ title, value: v, absent }: { title: string; value: HistoryReceipt | null; absent: string }) {
  return <section><h3>{title}</h3>{v ? <dl className="finance-summary">{([['accountBalanceAfter', '回执账户余额'], ['positionQuantityAfter', '回执持仓数量'], ['positionAverageCostAfter', '回执平均成本'], ['positionTotalCostAfter', '回执总成本'], ['positionRealizedProfitLossAfter', '回执累计已实现盈亏']] as const).map(([key, label]) => <div key={key}><dt>{label}</dt><dd className="finance-amount">{amount(v[key], key === 'positionQuantityAfter' ? '' : 'CNY')}</dd></div>)}<div><dt>回执持仓状态</dt><dd>{v.positionStatusAfter ?? 'null · 未提供'}</dd></div></dl> : <p>{absent}</p>}</section>
}
function CurrentPosition({ value: v }: { value: HistoryPosition }) {
  return <section><h3>当前持仓摘要 · 独立于历史回执</h3><dl className="finance-summary"><div><dt>当前数量</dt><dd className="finance-amount">{amount(v.quantity, '')}</dd></div><div><dt>当前平均成本</dt><dd className="finance-amount">{amount(v.averageCost)}</dd></div><div><dt>当前总成本</dt><dd className="finance-amount">{amount(v.totalCost)}</dd></div><div><dt>当前累计已实现盈亏</dt><dd className="finance-amount">{amount(v.cumulativeRealizedProfitLoss)}</dd></div><div><dt>当前状态</dt><dd>{v.status}</dd></div></dl></section>
}
