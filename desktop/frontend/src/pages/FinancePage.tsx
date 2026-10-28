import { FinanceDashboardView } from '../components/FinanceAnnual'
import { FinanceImports } from '../components/FinanceImports'
import { FinanceHistory } from '../components/FinanceHistory'
import { FinancePortfolio } from '../components/FinancePortfolio'
import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceAccount, FinanceAccountPage, FinanceOverview, FinanceQuery } from '../bridge/finance'
import { FinanceTransactions } from '../components/FinanceTransactions'
import { FinanceBudgets } from '../components/FinanceBudgets'
import { FinanceAssets } from '../components/FinanceAssets'
import { FinanceInvestments } from '../components/FinanceInvestments'
import { Icon } from '../components/Icon'
import { invalidFinanceIdentity, type FinanceIntent, type FinanceReturn, type FinanceView } from './financeNavigation'

const initialQuery: FinanceQuery = { page: 0, size: 10, status: 'ALL', sort: 'ID_ASC' }
const states = {
  DISABLED: 'Finance 已关闭或尚未配置。请在 Settings 中打开 Finance 配置与身份绑定。',
  NOT_BOUND: '尚未确认 Finance 用户。请在原生窗口核对数据库和业务用户后明确绑定。',
  BINDING_INVALID: '数据集或历史用户身份已改变，账户读取已拒绝。请保留绑定文件并核对配置。',
  UNAVAILABLE: 'Finance 数据库暂时不可用。检查连接后可重试。',
  READY: '数据库已连接 · 已验证当前绑定身份',
}
const statusLabel = (status: string) => status === 'ACTIVE' ? '使用中 · ACTIVE' : '停用 · INACTIVE'
const typeLabel = (type: string) => ({ BANK: '银行账户', CASH: '现金', CREDIT_CARD: '信用卡', INVESTMENT: '投资账户', OTHER: '其他' }[type] ?? type)
const financeGroups = [
  { label: '总览', items: [{ id: 'dashboard', name: 'Dashboard', label: '财务总览' }] },
  { label: '日常账务', items: [{ id: 'accounts', name: 'Accounts', label: '账户' }, { id: 'transactions', name: 'Transactions', label: '交易流水' }, { id: 'assets', name: 'Assets', label: '资产' }] },
  { label: '投资', items: [{ id: 'investments', name: 'Investment Positions', label: '投资持仓' }, { id: 'portfolio', name: 'Portfolio Summary', label: '投资组合统计' }, { id: 'history', name: 'Investment History', label: '投资交易历史' }] },
  { label: '历史记录', items: [{ id: 'imports', name: 'Import History', label: '导入历史' }] },
  { label: '能力说明', items: [{ id: 'budgets', name: 'Budgets', label: '预算 · 来源不支持' }] },
] as const

export function FinancePage({ bridge, enabled, openSettings }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void }) {
  const [view, setView] = useState<FinanceView>('dashboard')
  const [intent, setIntent] = useState<FinanceIntent | null>(null)
  const [back, setBack] = useState<FinanceReturn[]>([])
  const [entry, setEntry] = useState(0)
  const content = useRef<HTMLDivElement>(null)
  const clearNavigation = () => { setIntent(null); setBack([]) }
  useEffect(() => {
    const session = bridge.onSession(clearNavigation)
    const runtime = bridge.onStorageStartup(clearNavigation)
    return () => { session(); runtime() }
  }, [bridge])
  useEffect(() => { if (!enabled) clearNavigation() }, [enabled])
  useEffect(() => { setIntent(null); if (entry) content.current?.focus() }, [entry])
  const enter = (next: FinanceIntent, previous: FinanceReturn) => {
    setBack(stack => [...stack, previous].slice(-2)); setIntent(next); setView(next.target); setEntry(n => n + 1)
  }
  const goBack = () => {
    const previous = back[back.length - 1]
    if (!previous) return
    setBack(back.slice(0, -1))
    setIntent(previous.view === 'dashboard' ? { source: view, target: 'dashboard', month: previous.month, annualYear: previous.annualYear }
      : previous.view === 'investments' ? { source: view, target: 'investments', query: previous.query, selection: previous.selection } : null)
    setView(previous.view); setEntry(n => n + 1)
  }
  const selectView = (next: FinanceView) => {
    clearNavigation(); setView(next); setEntry(n => n + 1)
  }
  return <div className="finance-layout">
    <nav className="finance-navigation" aria-label="Finance 视图">
      {financeGroups.map(group => <div className="finance-nav-group" key={group.label}><p className="nav-group-label">{group.label}</p>
        {group.items.map(item => <button key={item.id} aria-label={`${item.name} · ${item.label}`} aria-current={view === item.id ? 'page' : undefined} aria-pressed={view === item.id} onClick={() => selectView(item.id)}><Icon name={item.id} /><span className="finance-nav-copy">{item.label}</span></button>)}
      </div>)}
    </nav>
    <label className="finance-picker">选择 Finance 页面<select value={view} onChange={event => selectView(event.target.value as FinanceView)}>
      {financeGroups.map(group => <optgroup key={group.label} label={group.label}>{group.items.map(item => <option key={item.id} value={item.id}>{item.name} · {item.label}</option>)}</optgroup>)}
    </select></label>
    {back.length > 0 && <div className="finance-back"><button className="secondary" onClick={goBack}>返回{back[back.length - 1]!.view === 'dashboard' ? '财务总览' : back[back.length - 1]!.view === 'portfolio' ? '投资组合' : '持仓上下文'}</button><span className="hint">返回后重新验证身份并读取数据</span></div>}
    <div className="finance-content" ref={content} tabIndex={-1} key={entry}>
    {view === 'dashboard' ? <FinanceDashboardView bridge={bridge} enabled={enabled} openSettings={openSettings} initialMonth={intent?.target === 'dashboard' ? intent.month : ''} invalidateNavigation={clearNavigation} initialAnnualYear={intent?.target === 'dashboard' ? intent.annualYear : undefined} navigate={(next, month, annualYear) => enter(next, { view: 'dashboard', month, annualYear })} />
      : view === 'accounts' ? <FinanceAccountPage bridge={bridge} enabled={enabled} openSettings={openSettings} invalidateNavigation={clearNavigation} />
      : view === 'transactions' ? <FinanceTransactions bridge={bridge} enabled={enabled} openSettings={openSettings} intent={intent?.target === 'transactions' ? intent : undefined} invalidateNavigation={clearNavigation} />
      : view === 'assets' ? <FinanceAssets bridge={bridge} enabled={enabled} openSettings={openSettings} invalidateNavigation={clearNavigation} />
      : view === 'imports' ? <FinanceImports bridge={bridge} enabled={enabled} openSettings={openSettings} />
      : view === 'history' ? <FinanceHistory bridge={bridge} enabled={enabled} openSettings={openSettings} initialPosition={intent?.target === 'history' ? intent.positionId : ''} invalidateNavigation={clearNavigation} />
      : view === 'portfolio' ? <FinancePortfolio bridge={bridge} enabled={enabled} openSettings={openSettings} invalidateNavigation={clearNavigation} navigate={next => enter(next, { view: 'portfolio' })} />
      : view === 'investments' ? <FinanceInvestments bridge={bridge} enabled={enabled} openSettings={openSettings} intent={intent?.target === 'investments' ? intent : undefined} invalidateNavigation={clearNavigation} openHistory={(id, query) => enter({ source: 'investments', target: 'history', positionId: id }, { view: 'investments', query: { ...query, cursor: '' }, selection: id })} />
      : <FinanceBudgets bridge={bridge} enabled={enabled} openSettings={openSettings} />}
    </div>
  </div>
}

function FinanceAccountPage({ bridge, enabled, openSettings, invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; invalidateNavigation: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null)
  const [list, setList] = useState<FinanceAccountPage | null>(null)
  const [detail, setDetail] = useState<FinanceAccount | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState(initialQuery)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [refreshed, setRefreshed] = useState('')
  const [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0)
  const inFlight = useRef(false)
  const detailHeading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    const reset = () => {
      generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null)
      setSelected(null); setRefreshed(''); setError(''); setWorking(false)
    }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(value => value + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])

  // Finance is mounted only while visible; no hidden account cache survives navigation.
  useEffect(() => {
    if (enabled) void refresh(initialQuery, null)
    else { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setRefreshed(''); setWorking(false) }
  }, [enabled, bridge, runtimeReady])

  async function refresh(next = query, accountId: string | null = selected) {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setOverview(null); setList(null); setDetail(null); setRefreshed('')
    setQuery(next); setSelected(accountId)
    try {
      const snapshot = await bridge.financeOverview()
      if (current !== generation.current) return
      if (snapshot.state !== 'READY') { setOverview(snapshot); setSelected(null); invalidateNavigation(); return }
      // Publish the batch only after every requested read passes its own identity check.
      const accounts = await bridge.financeAccounts(next)
      if (current !== generation.current) return
      const account = accountId ? await bridge.financeAccount(accountId) : null
      if (current !== generation.current) return
      setOverview(snapshot); setList(accounts); setDetail(account); setRefreshed(new Date().toLocaleTimeString())
      if (account) setTimeout(() => { if (current === generation.current) detailHeading.current?.focus() }, 0)
    } catch (problem) {
      if (current === generation.current) { setError(problem instanceof Error ? problem.message : 'Finance 读取失败，请重试。'); if (invalidFinanceIdentity(problem)) { setSelected(null); invalidateNavigation() } }
    } finally {
      if (current === generation.current) { inFlight.current = false; setWorking(false) }
    }
  }

  return <section className="finance-page" aria-label="Finance 只读管理" aria-busy={working}>
    <section className="card finance-overview" aria-labelledby="finance-overview-title">
      <div className="card-heading"><h2 id="finance-overview-title">账户总览</h2>
        <button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}>刷新 Finance</button></div>
      <p role="status">{working ? '正在验证身份并读取账户…' : overview ? states[overview.state] : '等待刷新 Finance 状态。'}</p>
      {overview?.state === 'READY' && <>
        <dl className="finance-summary">
          <div><dt>当前 Finance 用户</dt><dd>{overview.username}</dd></div>
          <div><dt>账户数量 · 全部币种与状态</dt><dd>{overview.accountCount}</dd></div>
          <div className="finance-total"><dt>CNY 账户总余额</dt><dd className="finance-amount">{overview.cnyBalance!.balance} CNY</dd></div>
        </dl>
        {!overview.account && <p>当前绑定用户没有 CNY 账户。</p>}
        <p className="hint">包含使用中与停用的 CNY 账户；其他币种不折算。只读快照，以手动刷新结果为准。</p>
        <a href="#finance-account-list" onClick={e => { e.preventDefault(); document.getElementById('finance-account-list')?.focus() }}>查看账户列表</a>
      </>}
      <div className="finance-toolbar"><span className="hint">{refreshed ? `最近成功刷新：${refreshed}` : '尚无本次成功刷新'}</span>
        <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button></div>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><div className="finance-toolbar">
      <button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button>
      <button disabled={!enabled || working} onClick={() => { void refresh(query, null) }}>返回账户列表</button></div></div>}
    {overview?.state === 'READY' && <section className="card" aria-labelledby="finance-account-list">
      <h2 id="finance-account-list" tabIndex={-1}>账户列表</h2>
      <div className="finance-toolbar">
        <label>账户状态<select aria-label="账户状态" value={query.status} disabled={working} onChange={e => { void refresh({ ...query, page: 0, status: e.target.value as FinanceQuery['status'] }, null) }}>
          <option value="ALL">全部状态</option><option value="ACTIVE">使用中</option><option value="INACTIVE">停用</option></select></label>
        <label>排序<select aria-label="账户排序" value={query.sort} disabled={working} onChange={e => { void refresh({ ...query, page: 0, sort: e.target.value as FinanceQuery['sort'] }, null) }}>
          <option value="ID_ASC">账户 ID 升序</option><option value="ID_DESC">账户 ID 降序</option><option value="NAME_ASC">账户名称升序</option></select></label>
        <label>每页<select aria-label="每页账户数" value={query.size} disabled={working} onChange={e => { void refresh({ ...query, page: 0, size: Number(e.target.value) }, null) }}>
          <option value={2}>2 条</option><option value={10}>10 条</option><option value={20}>20 条</option></select></label>
      </div>
      {list && (list.items.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="账户表格，可横向滚动"><table>
        <thead><tr><th>账户名称 / ID</th><th>类型</th><th>状态</th><th>币种</th><th className="finance-amount">精确余额</th></tr></thead>
        <tbody>{list.items.map(account => <tr key={account.id}>
          <td><button className="finance-account-link" aria-pressed={selected === account.id} onClick={() => { void refresh(query, account.id) }}>{account.name}</button><small>账户 ID {account.id}</small></td>
          <td>{typeLabel(account.type)}</td><td>{statusLabel(account.status)}</td><td>{account.currency}</td><td className="finance-amount">{account.balance} {account.currency}</td>
        </tr>)}</tbody></table></div> : <p>当前筛选下没有账户。</p>)}
      {list && <div className="pagination">
        <button disabled={working || query.page === 0} onClick={() => { void refresh({ ...query, page: query.page - 1 }, null) }}>上一页</button>
        <span>第 {query.page + 1} 页 · 共 {list.total} 个账户</span>
        <button disabled={working || query.page >= 100000 || BigInt((query.page + 1) * query.size) >= BigInt(list.total)} onClick={() => { void refresh({ ...query, page: query.page + 1 }, null) }}>下一页</button>
      </div>}
    </section>}
    {detail && <section className="card finance-detail" aria-labelledby="finance-detail-title">
      <div className="card-heading"><h2 id="finance-detail-title" ref={detailHeading} tabIndex={-1}>{detail.name}</h2><button className="secondary" onClick={() => { setDetail(null); setSelected(null); document.getElementById('finance-account-list')?.focus() }}>返回账户列表</button></div>
      <dl className="finance-summary">
        <div><dt>服务端账户 ID</dt><dd>{detail.id}</dd></div><div><dt>账户类型</dt><dd>{typeLabel(detail.type)} · {detail.type}</dd></div>
        <div><dt>币种</dt><dd>{detail.currency}</dd></div><div><dt>账户状态</dt><dd>{statusLabel(detail.status)}</dd></div>
        <div className="finance-total"><dt>精确余额</dt><dd className="finance-amount">{detail.balance} {detail.currency}</dd></div>
      </dl>
      <p className="hint">此 ID 为系统内账户标识，不是银行卡号。{detail.currency !== 'CNY' && '该币种未折算为 CNY。'}</p>
    </section>}
  </section>
}
