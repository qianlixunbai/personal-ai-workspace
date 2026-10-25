import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import type { FinanceDashboard as Dashboard } from '../bridge/financeDashboard'
import { FinanceReferenceAllocation } from './FinanceReferenceAllocation'
import { FinanceCashChart, FinanceDonut } from './FinanceCharts'
import { Icon, type IconName } from './Icon'
import type { FinanceIntent } from '../pages/financeNavigation'
import { invalidFinanceIdentity } from '../pages/financeNavigation'

const states = { DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请先进行原生身份绑定。', BINDING_INVALID: 'Finance 身份或数据集已改变，读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用。', READY: '已验证当前绑定身份' }
export function FinanceDashboard({ bridge, enabled, openSettings, navigate, initialMonth = '', invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; navigate: (intent: FinanceIntent, month: string) => void; initialMonth?: string; invalidateNavigation: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null), [value, setValue] = useState<Dashboard | null>(null)
  const [working, setWorking] = useState(false), [error, setError] = useState(''), [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false)
  const [range, setRange] = useState(12)
  const [month, setMonth] = useState(initialMonth), [netOpen, setNetOpen] = useState(false)
  const detailHeading = useRef<HTMLHeadingElement>(null), opener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setOverview(null); setValue(null); setError(''); setWorking(false); setMonth(''); setNetOpen(false); opener.current = null }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(v => v + 1); else setError('Runtime 暂时不可用，金融快照已清除。') })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    if (enabled) void refresh()
    else { generation.current++; inFlight.current = false; setOverview(null); setValue(null); setError(''); setWorking(false); setMonth(''); setNetOpen(false) }
  }, [enabled, bridge, runtimeReady])
  useEffect(() => { if (value && (month || netOpen)) detailHeading.current?.focus() }, [month, netOpen, value])
  async function refresh() {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setOverview(null); setValue(null)
    try {
      const status = await bridge.financeOverview()
      if (current !== generation.current) return
      if (status.state !== 'READY') { setOverview(status); setMonth(''); setNetOpen(false); invalidateNavigation(); return }
      const result = await bridge.financeDashboard()
      if (current !== generation.current) return
      setOverview(status); setValue(result)
    } catch (problem) {
      if (current === generation.current) { setError(problem instanceof Error ? problem.message : 'Dashboard 读取失败，请重试。'); if (invalidFinanceIdentity(problem)) { setMonth(''); setNetOpen(false); invalidateNavigation() } }
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const a = value?.allocation
  const open = (intent: FinanceIntent) => navigate(intent, month)
  const transactions = (selectedMonth: string, type: 'INCOME' | 'EXPENSE') => {
    if (value) open({ source: 'dashboard', target: 'transactions', query: { page: 0, size: 10, accountId: '', startDate: selectedMonth + '-01', endDate: '', type, cashFlowMonth: selectedMonth, cashFlowAsOf: value.asOf } })
  }
  const chooseMonth = (next: string, trigger?: HTMLElement) => { opener.current = trigger ?? null; setNetOpen(false); setMonth(next) }
  const closeDetail = () => { setMonth(''); setNetOpen(false); opener.current?.focus() }
  const flow = netOpen && value ? { month: value.asOf.slice(0, 7), income: value.monthIncome, expense: value.monthExpense, net: value.monthNet } : (value?.twelveMonths ?? value?.monthlyCashFlow)?.find(f => f.month === month)
  return <section className="finance-page finance-dashboard" aria-label="Finance 只读财务总览" aria-busy={working}>
    <div className="dashboard-heading"><div><h2>Finance Dashboard <span>· 财务总览</span></h2>
      <p role="status"><Icon name="shield" />{working ? '正在验证身份并读取财务总览…' : !enabled ? states.DISABLED : overview ? `${states[overview.state]}${overview.username ? ` · ${overview.username}` : ''}` : '等待 Dashboard 读取。'}</p></div>
      <div className="dashboard-actions"><button className="text-button" onClick={openSettings}>Finance 配置与身份绑定</button><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}><Icon name="refresh" />刷新 Dashboard</button></div>
    </div>
    {value && <p className="snapshot-label">快照截至 {value.asOf} · {value.businessZone} · 只读</p>}
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button></div>}
    {value && a && <>
      <dl className="dashboard-metrics">
        <Metric icon="coins" title="CNY 账户总余额" english="Total account balance" amount={value.cnyAccountBalance} tone="balance" note="使用中与停用 CNY 账户" onClick={() => open({ source: 'dashboard', target: 'accounts' })} />
        <Metric icon="income" title="本月收入" english="Month income" amount={value.monthIncome} tone="income" note="本月普通收入 · INCOME" onClick={() => transactions(value.asOf.slice(0, 7), 'INCOME')} />
        <Metric icon="expense" title="本月支出" english="Month expense" amount={value.monthExpense} tone="expense" note="本月普通支出 · EXPENSE" onClick={() => transactions(value.asOf.slice(0, 7), 'EXPENSE')} />
        <Metric icon="status" title="本月净现金流" english="Month net cash flow" amount={value.monthNet} tone={value.monthNet.startsWith('-') ? 'expense' : 'net'} note="普通收入 − 普通支出" expanded={netOpen} onClick={trigger => { opener.current = trigger; setMonth(''); setNetOpen(true) }} />
      </dl>
      {flow && <section className="card cash-flow-detail" aria-labelledby="cash-flow-detail-title" onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); closeDetail() } }}>
        <div className="card-heading"><h3 id="cash-flow-detail-title" tabIndex={-1} ref={detailHeading}>{flow.month} {netOpen ? '净现金流组成' : '月度收支明细'}</h3><button className="secondary" onClick={closeDetail}>关闭收支明细</button></div>
        <dl className="finance-summary"><div><dt>收入 · Income</dt><dd className="finance-amount amount-income">{flow.income} CNY</dd></div><div><dt>支出 · Expense</dt><dd className="finance-amount amount-expense">{flow.expense} CNY</dd></div><div><dt>净额 · Net</dt><dd className="finance-amount">{flow.net} CNY</dd></div></dl>
        <p className="hint">普通 CNY 收入 − 支出；截至 {value.asOf}（不含），历史月同时截至下一月初。不代表账户余额变化、总资产增长或投资收益。</p>
        <div className="finance-toolbar"><button onClick={() => transactions(flow.month, 'INCOME')}>查看 {flow.month} 收入明细</button><button className="secondary" onClick={() => transactions(flow.month, 'EXPENSE')}>查看 {flow.month} 支出明细</button></div>
      </section>}
      <div className="dashboard-grid">
      {value.twelveMonths && value.twelveMonthPlot ? <section className="card dashboard-trend"><div className="card-heading"><h3>月度收支对比 <small>Monthly income & expense</small></h3><div className="finance-toolbar" role="group" aria-label="现金流时间范围">{[6, 12].map(n => <button className="secondary" key={n} aria-pressed={range === n} onClick={() => setRange(n)}>近 {n} 个月</button>)}</div></div>
        <FinanceCashChart months={value.twelveMonths.slice(-range)} plot={{ ...value.twelveMonthPlot, income: value.twelveMonthPlot.income.slice(-range), expense: value.twelveMonthPlot.expense.slice(-range), net: value.twelveMonthPlot.net.slice(-range) }} selected={month} onSelect={chooseMonth} />
        <details className="dashboard-details"><summary>查看精确收支台账</summary><div className="finance-table-scroll" tabIndex={0} aria-label="月度精确收支"><table><thead><tr><th>月份</th><th>收入</th><th>支出</th><th>净额</th><th>交易数</th></tr></thead><tbody>{value.twelveMonths.slice(-range).map(f => <tr key={f.month}><td>{f.month}</td><td className="finance-amount">{f.income}</td><td className="finance-amount">{f.expense}</td><td className="finance-amount">{f.net}</td><td>{f.transactionCount}</td></tr>)}</tbody></table></div></details>
        <p className="hint">普通 CNY 收支，当前月截至 {value.asOf}（不含），历史月截至下一月初；未来记录、转账、退款、调整及投资交易不计入。共享比例轴，极大金额会使小金额图形接近零，请查看精确台账。</p>
      </section> : <>
      <section className="card dashboard-trend"><div className="card-heading"><h3>收入与支出趋势 <small>Cash flow</small></h3><span className="period-label">近 6 个月 · CNY</span></div>
        <div className="chart-legend"><span className="income-key">收入 Income</span><span className="expense-key">支出 Expense</span></div>
        <CashFlowChart value={value} selected={month} onSelect={chooseMonth} />
        <div className="cash-flow-months" role="group" aria-label="选择现金流月份">{value.monthlyCashFlow.map(f => <button key={f.month} className="secondary" aria-pressed={month === f.month} aria-controls="cash-flow-detail-title" aria-label={`${f.month} 收入 ${f.income} CNY，支出 ${f.expense} CNY，净额 ${f.net} CNY`} onClick={event => chooseMonth(f.month, event.currentTarget)}>{f.month}<span>{month === f.month ? '已选中' : '查看明细'}</span></button>)}</div>
        <p className="chart-caption">同一比例展示收支，零线已标出；精确金额见明细。</p>
        <details className="dashboard-details"><summary>查看六个月精确收支</summary><div className="finance-table-scroll" tabIndex={0} aria-label="六个月收支表格"><table>
        <thead><tr><th>月份</th><th>收入</th><th>支出</th><th>净收支</th></tr></thead><tbody>{value.monthlyCashFlow.map(f => <tr key={f.month}><td>{f.month}</td><td className="finance-amount">{f.income}</td><td className="finance-amount">{f.expense}</td><td className="finance-amount">{f.net}</td></tr>)}</tbody>
      </table></div></details><p className="hint">当前月及此前五个月；无交易月份由服务端补零。本月截至快照时刻（不含）。仅普通 CNY 收支，不含转账表与投资交易，不代表余额变化或投资收益。</p></section>
      </>}
      {value.expenseBreakdown && <FinanceDonut title="本月支出结构" value={value.expenseBreakdown} unit="CNY" note="全部合格普通 CNY 支出。类别不可用归入未分类；超过十二类合并为其余类别。选择图例查看详情；流水暂不支持类别筛选。" />}
      {value.accountStructure && <FinanceDonut title="账户数量结构" value={value.accountStructure} note="包含全部币种及使用中、停用账户；这是账户数量，带符号余额不参与占比。选择图例查看类型数量，在 Accounts 查看账户详情。" />}
      <FinanceReferenceAllocation value={a} openAssets={() => open({ source: 'dashboard', target: 'assets' })} />
      <section className="card dashboard-recent"><div className="card-heading"><h3>最近五条普通交易 · CNY <small>Recent transactions</small></h3><button className="text-button" onClick={() => open({ source: 'dashboard', target: 'transactions' })}>查看交易<Icon name="arrow" /></button></div>{value.recentTransactions.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="最近普通交易表格"><table><thead><tr><th>时间 / ID</th><th>类型</th><th>账户</th><th>分类</th><th>金额 · CNY</th></tr></thead><tbody>{value.recentTransactions.map(t => <tr key={t.id}><td><button className="finance-account-link" aria-label={`${t.transactedAt} · 查看普通交易 ${t.id} 详情`} onClick={() => open({ source: 'dashboard', target: 'transactions', selection: { kind: 'TRANSACTION', id: t.id } })}>{t.transactedAt}</button><small>{t.id}</small></td><td><span className={`transaction-kind ${t.type === 'INCOME' ? 'kind-income' : t.type === 'EXPENSE' ? 'kind-expense' : ''}`}>{t.type}</span></td><td>{t.accountName ?? '未知账户'}</td><td>{t.categoryName ?? '未知分类'}</td><td className={`finance-amount ${t.type === 'INCOME' ? 'amount-income' : t.type === 'EXPENSE' ? 'amount-expense' : ''}`}>{t.amount}</td></tr>)}</tbody></table></div> : <div className="dashboard-empty"><Icon name="transactions" /><p>当前用户没有 CNY 普通交易。</p></div>}
        <p className="hint">按交易时间、ID 降序。保留历史 REFUND / ADJUSTMENT / TRANSFER，也可能显示未来日期记录；这些记录不因此计入本月收支。</p></section>
      <section className="card dashboard-accounts"><div className="card-heading"><h3>账户概览 <small>Accounts</small></h3><Icon name="accounts" /></div>
        <div className="account-count"><span className="metric-icon"><Icon name="accounts" /></span><div><strong>{overview?.accountCount}</strong><small>当前绑定用户的账户</small></div></div>
        <p className="account-scope"><Icon name="shield" />{overview?.username}</p>
        <p className="hint">在账户页面查看余额、状态与详情。总览余额仅包含 CNY 账户，包含使用中与停用账户。</p>
        <button className="account-entry" onClick={() => open({ source: 'dashboard', target: 'accounts' })}><Icon name="accounts" /><span>查看全部账户<small>Accounts overview</small></span><Icon name="arrow" /></button>
      </section>
      </div>
      <div className="dashboard-disclosure"><Icon name="shield" /><p>人工参考分布不是完整净资产配置，也不是实时行情。未定价资产不视为价值零；人工价格更新时间和新鲜度未知，资产记录更新时间不能证明价格新鲜度。不合并账户余额、投资成本或行情估值。</p></div>
    </>}
  </section>
}

function Metric({ icon, title, english, amount, tone, note, onClick, expanded }: { icon: IconName; title: string; english: string; amount: string; tone: string; note: string; onClick: (trigger: HTMLElement) => void; expanded?: boolean }) {
  return <div className={`metric-card metric-${tone}`}><dt><span className="metric-icon"><Icon name={icon} /></span><span>{title}<small>{english}</small></span></dt><dd>{amount} CNY</dd><p>{note}</p><button className="metric-hit" aria-label={`${title} · 查看明细`} aria-expanded={expanded} onClick={event => onClick(event.currentTarget)}><span>查看明细 →</span></button>{tone === 'balance' && <svg className="balance-motif" viewBox="0 0 150 80" aria-hidden="true"><path d="M5 80V65m16 15V50m16 30V58m16 22V40m16 40V47m16 33V25m16 55V32m16 48V10m16 70V18m16 62V0" stroke="currentColor" strokeWidth="7" strokeLinecap="round" /></svg>}</div>
}

function CashFlowChart({ value, selected, onSelect }: { value: Dashboard; selected: string; onSelect: (month: string) => void }) {
  // Only bounded, non-authoritative server coordinates are used for geometry.
  const y = (coordinate: number) => 20 + coordinate * .016
  const x = (index: number) => 36 + index * 106
  const path = (points: number[]) => points.map((point, i) => `${i ? 'L' : 'M'}${x(i)},${y(point)}`).join(' ')
  const zero = y(value.cashFlowPlot.zero)
  return <svg className="cash-flow-chart" viewBox="0 0 602 218" role="img" aria-label="六个月收入和支出趋势图；选择月份按钮可读取精确明细">
    <defs>{['income', 'expense'].map(kind => <linearGradient id={`cash-${kind}`} key={kind} x1="0" y1="0" x2="0" y2="1"><stop className={`chart-${kind}`} stopOpacity=".22" /><stop offset="1" className={`chart-${kind}`} stopOpacity=".01" /></linearGradient>)}</defs>
    {[20, 60, 100, 140, 180].map(v => <line className="chart-grid" key={v} x1="36" x2="566" y1={v} y2={v} />)}
    <line className="chart-zero" x1="36" x2="566" y1={zero} y2={zero} /><text className="chart-label" x="12" y={zero + 4}>0</text>
    {(['income', 'expense'] as const).map(kind => <g key={kind}><path d={`${path(value.cashFlowPlot[kind])} L566,${zero} L36,${zero} Z`} fill={`url(#cash-${kind})`} /><path d={path(value.cashFlowPlot[kind])} fill="none" className={`chart-line chart-${kind}`} />{value.cashFlowPlot[kind].map((point, i) => <g key={i} onClick={() => onSelect(value.monthlyCashFlow[i]!.month)} className="chart-pick"><circle cx={x(i)} cy={y(point)} r="12" fill="transparent" /><circle cx={x(i)} cy={y(point)} r={selected === value.monthlyCashFlow[i]!.month ? 7 : 4} className={`chart-point chart-${kind}${selected === value.monthlyCashFlow[i]!.month ? ' chart-selected' : ''}`}><title>{value.monthlyCashFlow[i]!.month} {kind === 'income' ? '收入' : '支出'} {value.monthlyCashFlow[i]![kind]} CNY</title></circle></g>)}</g>)}
    {value.monthlyCashFlow.map((flow, i) => <text className="chart-label" key={flow.month} x={x(i)} y="207" textAnchor="middle">{flow.month}</text>)}
  </svg>
}
