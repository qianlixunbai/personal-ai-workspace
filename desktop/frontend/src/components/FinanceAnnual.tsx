import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceAnnual as Annual } from '../bridge/financeAnalytics'
import { invalidFinanceIdentity, type FinanceIntent } from '../pages/financeNavigation'
import { FinanceCashChart, FinanceDonut } from './FinanceCharts'
import { FinanceDashboard } from './FinanceDashboard'

type Navigate = (intent: FinanceIntent, month: string, annualYear?: number | undefined) => void
export function FinanceDashboardView({ bridge, enabled, openSettings, navigate, invalidateNavigation, initialMonth = '', initialAnnualYear }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; navigate: Navigate; invalidateNavigation: () => void; initialMonth?: string; initialAnnualYear?: number | undefined }) {
  const [annual, setAnnual] = useState(initialAnnualYear !== undefined)
  return <><div className="finance-analysis-tabs" role="group" aria-label="财务分析视图"><button className="secondary" aria-pressed={!annual} onClick={() => setAnnual(false)}>财务总览</button><button className="secondary" aria-pressed={annual} onClick={() => setAnnual(true)}>年度分析 · Annual Review</button></div>
    {annual ? <FinanceAnnual bridge={bridge} enabled={enabled} navigate={navigate} invalidateNavigation={invalidateNavigation} initialYear={initialAnnualYear ?? 0} initialMonth={initialMonth} />
      : <FinanceDashboard bridge={bridge} enabled={enabled} openSettings={openSettings} navigate={navigate} invalidateNavigation={invalidateNavigation} initialMonth={initialMonth} />}</>
}
function FinanceAnnual({ bridge, enabled, navigate, invalidateNavigation, initialYear, initialMonth }: { bridge: WorkspaceClient; enabled: boolean; navigate: Navigate; invalidateNavigation: () => void; initialYear: number; initialMonth: string }) {
  const [value, setValue] = useState<Annual | null>(null), [year, setYear] = useState(initialYear), [month, setMonth] = useState(initialMonth)
  const [working, setWorking] = useState(false), [error, setError] = useState(''), [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false), detail = useRef<HTMLHeadingElement>(null), opener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setValue(null); setYear(0); setMonth(''); setWorking(false); setError(''); opener.current = null }
    const a = bridge.onSession(reset), b = bridge.onStorageStartup(s => { reset(); if (s.canQueryStorage) setRuntimeReady(n => n + 1); else setError('Runtime 暂时不可用，年度快照已清除。') })
    return () => { generation.current++; a(); b() }
  }, [bridge])
  useEffect(() => { if (enabled) void refresh(year); else { generation.current++; inFlight.current = false; setValue(null); setYear(0); setMonth(''); setWorking(false) } }, [enabled, bridge, runtimeReady])
  useEffect(() => { if (month && value) detail.current?.focus() }, [month, value])
  async function refresh(next: number) {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setWorking(true); setError(''); setValue(null); setYear(next)
    try {
      const status = await bridge.financeOverview(); if (current !== generation.current) return
      if (status.state !== 'READY') { setMonth(''); invalidateNavigation(); setError(`年度读取不可用 · ${status.state}`); return }
      const result = await bridge.financeAnnual(next); if (current !== generation.current) return
      setValue(result); setYear(result.year)
    } catch (p) { if (current === generation.current) { setError(p instanceof Error ? p.message : '年度读取失败。'); if (invalidFinanceIdentity(p)) { setMonth(''); invalidateNavigation() } } }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const changeYear = (next: number) => { setMonth(''); opener.current = null; void refresh(next) }
  const choose = (next: string, trigger?: HTMLElement) => { opener.current = trigger ?? null; setMonth(next) }
  const close = () => { setMonth(''); opener.current?.focus() }
  const flow = value?.months.find(m => m.month === month && m.state === 'OBSERVED')
  const transactions = (type: 'INCOME' | 'EXPENSE') => { if (flow && value) navigate({ source: 'dashboard', target: 'transactions', query: { page: 0, size: 10, accountId: '', startDate: flow.month + '-01', endDate: '', type, cashFlowMonth: flow.month, cashFlowAsOf: value.asOf } }, month, value.year) }
  return <section className="finance-page finance-annual" aria-label="年度财务记录" aria-busy={working}>
    <div className="dashboard-heading"><div><h2>年度财务记录 <span>· Annual Review</span></h2><p role="status">{working ? '正在验证身份并重建年度统计…' : value ? `${value.year} 年 · 普通 CNY 收入与支出` : '等待年度读取'}</p></div><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh(year) }}>刷新年度</button></div>
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh(year) }}>重试年度</button></div>}
    {value && <><div className="card finance-year-toolbar"><div className="finance-toolbar">
      <button className="secondary" disabled={working || value.year <= 1900} onClick={() => changeYear(value.year - 1)}>上一年</button>
      <label>财务年份<select aria-label="财务年份" value={value.year} disabled={working} onChange={e => changeYear(Number(e.target.value))}>{[...new Set([...value.years, value.year])].sort((a, b) => b - a).map(y => <option key={y} value={y}>{y} 年{y === value.currentYear ? ' · 当前年' : ''}</option>)}</select></label>
      <button className="secondary" disabled={working || value.year >= value.currentYear} onClick={() => changeYear(value.year + 1)}>下一年</button><button className="text-button" disabled={working || value.year === value.currentYear} onClick={() => changeYear(0)}>返回当前年</button>
    </div><p className="hint">有记录的年份来自当前用户已发生的普通 CNY 收支；可逐年查看空年。支持 1900–2199，未来年份不开放。当前年截至 {value.asOf}（不含）· {value.businessZone}。</p></div>
      <dl className="annual-metrics">{[['年度收入', value.income, 'income'], ['年度支出', value.expense, 'expense'], ['年度净现金流', value.net, 'net'], ['合格收支交易数', value.transactionCount, 'count']].map(([title, amount, tone]) => <div className={`metric-card metric-${tone}`} key={title}><dt>{title}</dt><dd>{amount}{tone === 'count' ? ' 笔' : ' CNY'}</dd></div>)}</dl>
      <section className="card"><div className="card-heading"><h3>{value.year} 年 · 十二个月收支</h3><span className="period-label">CNY · {value.year === value.currentYear ? '年内截至当前' : '历史年度'}</span></div><FinanceCashChart months={value.months} plot={value.plot} selected={month} onSelect={choose} label="年度十二个月收支" /></section>
      {flow && <section className="card cash-flow-detail" onKeyDown={e => { if (e.key === 'Escape') close() }}><div className="card-heading"><h3 ref={detail} tabIndex={-1}>{flow.month} 月度收支明细</h3><button className="secondary" onClick={close}>关闭年度月份明细</button></div>
        <dl className="finance-summary"><div><dt>收入</dt><dd className="finance-amount">{flow.income} CNY</dd></div><div><dt>支出</dt><dd className="finance-amount">{flow.expense} CNY</dd></div><div><dt>净现金流</dt><dd className="finance-amount">{flow.net} CNY</dd></div><div><dt>交易数</dt><dd>{flow.transactionCount}</dd></div></dl>
        <div className="finance-toolbar"><button onClick={() => transactions('INCOME')}>查看 {flow.month} 收入明细</button><button className="secondary" onClick={() => transactions('EXPENSE')}>查看 {flow.month} 支出明细</button></div></section>}
      <div className="finance-analysis-grid"><section className="card annual-ledger"><div className="card-heading"><h3>可审计月度台账</h3><span className="coverage-tag">{value.year} 年</span></div>
        <div className="finance-table-scroll annual-table-scroll" tabIndex={0} aria-label="年度月度台账，可横向滚动"><table><thead><tr><th>月份</th><th className="finance-amount">收入 · CNY</th><th className="finance-amount">支出 · CNY</th><th className="finance-amount">净现金流 · CNY</th><th className="finance-amount">收支交易数</th></tr></thead>
          <tbody>{value.months.map(m => <tr key={m.month} className={month === m.month ? 'ledger-selected' : ''}><td>{m.state === 'FUTURE' ? m.month : <button className="finance-account-link" aria-label={`台账选择 ${m.month}`} onClick={e => choose(m.month, e.currentTarget)}>{m.month}</button>}{m.state === 'FUTURE' && <small>尚未发生</small>}</td>{[m.income, m.expense, m.net, m.transactionCount].map((v, i) => <td key={i} className="finance-amount">{v ?? '—'}</td>)}</tr>)}</tbody>
          <tfoot><tr><th>年度合计</th>{[value.income, value.expense, value.net, value.transactionCount].map((v, i) => <th key={i} className="finance-amount">{v}</th>)}</tr></tfoot></table></div>
        <p className="hint">从持久化交易记录按年重建；当前月未结算，未来月份金额未知。零值仅代表已发生区间内没有合格金额。转账、退款、调整、投资交易与非 CNY 不计入；不是历史净资产或投资收益。进入流水和返回时重新读取，来源记录变化可能改变统计。</p>
      </section><FinanceDonut title="年度支出类别" value={value.expenseBreakdown} unit="CNY" note="当前年度全部合格支出；类别归属不可用归入未分类，超过十二类合并为其余类别。选择图例查看精确金额；当前流水没有类别筛选，因此不提供类别跳转。" /></div>
    </>}
  </section>
}
