import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import { transactionTypes } from '../bridge/financeTransactions'
import type { FinanceTransaction, FinanceTransactionPage, FinanceTransactionQuery } from '../bridge/financeTransactions'

const initial: FinanceTransactionQuery = { page: 0, size: 10, accountId: '', startDate: '', endDate: '', type: 'ALL' }
const labels = { ALL: '全部类型', INCOME: '收入', EXPENSE: '支出', ADJUSTMENT: '余额调整', TRANSFER: '转账', REFUND: '退款' }
const states = { READY: '当前绑定身份已验证', DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请先完成原生身份绑定。', BINDING_INVALID: 'Finance 绑定或数据集已改变，交易读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。' }
const accountText = (row: FinanceTransaction) => `${row.accountName} · ID ${row.accountId}${row.kind === 'TRANSFER' ? ` → ${row.toAccountName} · ID ${row.toAccountId}` : ''}`
const timeText = (value: string) => value.replace('T', ' ')

export function FinanceTransactions({ bridge, enabled, openSettings }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null)
  const [list, setList] = useState<FinanceTransactionPage | null>(null)
  const [detail, setDetail] = useState<FinanceTransaction | null>(null)
  const [selected, setSelected] = useState<Pick<FinanceTransaction, 'kind' | 'id'> | null>(null)
  const [query, setQuery] = useState(initial)
  const [draft, setDraft] = useState(initial)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false)
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setSelected(null); setQuery(initial); setDraft(initial); setError(''); setWorking(false) }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(value => value + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    if (enabled) void refresh(initial, null)
    else { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setWorking(false) }
  }, [enabled, bridge, runtimeReady])

  async function refresh(next = query, selection = selected) {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setOverview(null); setList(null); setDetail(null); setQuery(next); setDraft(next); setSelected(selection)
    try {
      const status = await bridge.financeOverview()
      if (current !== generation.current) return
      if (status.state !== 'READY') { setOverview(status); setSelected(null); return }
      const rows = await bridge.financeTransactions(next)
      if (current !== generation.current) return
      const record = selection ? await bridge.financeTransaction(selection.kind, selection.id) : null
      if (current !== generation.current) return
      setOverview(status); setList(rows); setDetail(record)
      if (record) setTimeout(() => { if (current === generation.current) heading.current?.focus() }, 0)
    } catch (problem) {
      if (current === generation.current) setError(problem instanceof Error ? problem.message : '交易读取失败，请重试。')
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }

  return <section className="finance-page" aria-label="Finance 只读交易流水" aria-busy={working}>
    <section className="card">
      <div className="card-heading"><h2>交易流水</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}>刷新交易</button></div>
      <p role="status">{working ? '正在验证身份并读取交易…' : overview ? `${states[overview.state]}${overview.username ? ` · ${overview.username}` : ''}` : '等待交易读取。'}</p>
      <p className="hint">只读原币金额；按交易日期、ID 降序排列，同值按来源排序。日期按 Finance 原始本地时间显示，不转换时区。</p>
      <form onSubmit={event => { event.preventDefault(); void refresh({ ...draft, page: 0 }, null) }}>
        <fieldset className="finance-toolbar" disabled={!enabled || working}>
          <label>账户 ID<input aria-label="交易账户 ID" inputMode="numeric" maxLength={19} placeholder="留空为全部账户" value={draft.accountId} onChange={e => setDraft({ ...draft, accountId: e.target.value })} /></label>
          <label>开始日期<input aria-label="交易开始日期" type="date" min="0001-01-01" max="9999-12-31" value={draft.startDate} onChange={e => setDraft({ ...draft, startDate: e.target.value })} /></label>
          <label>结束日期（含当天）<input aria-label="交易结束日期" type="date" min="0001-01-01" max="9999-12-31" value={draft.endDate} onChange={e => setDraft({ ...draft, endDate: e.target.value })} /></label>
          <label>交易类型<select aria-label="交易类型" value={draft.type} onChange={e => setDraft({ ...draft, type: e.target.value as FinanceTransactionQuery['type'] })}>{transactionTypes.map(type => <option key={type} value={type}>{labels[type]}</option>)}</select></label>
          <label>每页<select aria-label="每页交易数" value={draft.size} onChange={e => setDraft({ ...draft, size: Number(e.target.value) })}><option value={2}>2 条</option><option value={10}>10 条</option><option value={20}>20 条</option></select></label>
          <button type="submit">应用筛选</button><button type="button" className="secondary" onClick={() => { void refresh(initial, null) }}>清除筛选</button>
        </fieldset>
      </form>
      <p className="hint">账户 ID 可在 Accounts 账户列表查看；转账匹配转出或转入账户。</p>
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><div className="finance-toolbar"><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button><button disabled={!enabled || working} onClick={() => { void refresh(query, null) }}>返回交易列表</button></div></div>}
    {list && <section className="card" aria-label="交易列表">
      {list.items.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="交易表格，可横向滚动"><table>
        <thead><tr><th>交易日期 / 明细</th><th>交易类型</th><th>关联账户</th><th className="finance-amount">精确金额</th></tr></thead>
        <tbody>{list.items.map(row => <tr key={`${row.kind}:${row.id}`}><td>
          <button className="finance-account-link" aria-pressed={selected?.kind === row.kind && selected.id === row.id} onClick={() => { void refresh(query, row) }}>{timeText(row.transactedAt)}</button>
          <small>{row.kind === 'TRANSFER' ? '转账' : '流水'} ID {row.id}</small></td>
          <td>{labels[row.type]} · {row.type}</td><td>{accountText(row)}</td><td className="finance-amount">{row.amount} {row.currency}</td></tr>)}</tbody>
      </table></div> : <p>当前筛选下没有交易。</p>}
      <div className="pagination"><button disabled={working || query.page === 0} onClick={() => { void refresh({ ...query, page: query.page - 1 }, null) }}>上一页</button>
        <span>第 {query.page + 1} 页 · 共 {list.total} 笔交易</span>
        <button disabled={working || query.page >= 100000 || BigInt((query.page + 1) * query.size) >= BigInt(list.total)} onClick={() => { void refresh({ ...query, page: query.page + 1 }, null) }}>下一页</button></div>
    </section>}
    {detail && <section className="card finance-detail" aria-labelledby="finance-transaction-title">
      <div className="card-heading"><h2 id="finance-transaction-title" ref={heading} tabIndex={-1}>交易详情</h2><span className="shell-badge">只读交易</span></div>
      <dl className="finance-summary"><div><dt>来源 / 系统内 ID</dt><dd>{detail.kind} · {detail.id}</dd></div>
        <div><dt>交易类型</dt><dd>{labels[detail.type]} · {detail.type}</dd></div><div><dt>交易日期</dt><dd>{timeText(detail.transactedAt)}</dd></div>
        <div><dt>{detail.kind === 'TRANSFER' ? '转出 → 转入账户' : '关联账户'}</dt><dd>{accountText(detail)}</dd></div>
        <div className="finance-total"><dt>精确金额 / 币种</dt><dd className="finance-amount">{detail.amount} {detail.currency}</dd></div>
        <div><dt>备注</dt><dd style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detail.description ?? '未提供备注'}</dd></div>
      </dl>
      {detail.type === 'TRANSFER' && <p className="hint">转账独立展示，不计作收入或支出。{detail.kind === 'TRANSACTION' && '历史流水未提供转入账户，不推断另一端。'}</p>}
      <p className="hint">当前来源模型没有交易状态字段。</p>
    </section>}
  </section>
}
