import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import type { FinanceDashboard as Dashboard } from '../bridge/financeDashboard'

const states = { DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请先进行原生身份绑定。', BINDING_INVALID: 'Finance 身份或数据集已改变，读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用。', READY: '已验证当前绑定身份' }
export function FinanceDashboard({ bridge, enabled, openSettings, openAssets }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; openAssets: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null), [value, setValue] = useState<Dashboard | null>(null)
  const [working, setWorking] = useState(false), [error, setError] = useState(''), [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setOverview(null); setValue(null); setError(''); setWorking(false) }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(v => v + 1); else setError('Runtime 暂时不可用，金融快照已清除。') })
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
      if (status.state !== 'READY') { setOverview(status); return }
      const result = await bridge.financeDashboard()
      if (current !== generation.current) return
      setOverview(status); setValue(result)
    } catch (problem) {
      if (current === generation.current) setError(problem instanceof Error ? problem.message : 'Dashboard 读取失败，请重试。')
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const a = value?.allocation
  return <section className="finance-page" aria-label="Finance 只读财务总览" aria-busy={working}>
    <section className="card"><div className="card-heading"><h2>Dashboard · 财务总览</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}>刷新 Dashboard</button></div>
      <p role="status">{working ? '正在验证身份并读取财务总览…' : !enabled ? states.DISABLED : overview ? `${states[overview.state]}${overview.username ? ` · ${overview.username}` : ''}` : '等待 Dashboard 读取。'}</p>
      {value && <p className="hint">快照截至 {value.asOf} · {value.businessZone}；手动刷新重新读取服务端。</p>}
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button></div>}
    {value && a && <>
      <section className="card"><h3>CNY 账户与本月收支</h3><dl className="finance-summary">
        <div><dt>CNY 账户总余额</dt><dd className="finance-amount">{value.cnyAccountBalance} CNY</dd></div>
        <div><dt>Month Income · 本月收入</dt><dd className="finance-amount">{value.monthIncome} CNY</dd></div>
        <div><dt>Month Expense · 本月支出</dt><dd className="finance-amount">{value.monthExpense} CNY</dd></div>
        <div><dt>Month Net · 本月净收支</dt><dd className="finance-amount">{value.monthNet} CNY</dd></div>
      </dl><p className="hint">余额包含使用中与停用 CNY 账户。月收支仅统计普通交易表 INCOME / EXPENSE，自本月首日 00:00 至本次快照时刻（不含）；不合并转账表与投资交易。</p></section>
      <section className="card"><h3>六个月普通交易收支趋势 · CNY</h3><div className="finance-table-scroll" tabIndex={0} aria-label="六个月收支表格"><table>
        <thead><tr><th>月份</th><th>收入</th><th>支出</th><th>净收支</th></tr></thead><tbody>{value.monthlyCashFlow.map(f => <tr key={f.month}><td>{f.month}</td><td className="finance-amount">{f.income}</td><td className="finance-amount">{f.expense}</td><td className="finance-amount">{f.net}</td></tr>)}</tbody>
      </table></div><p className="hint">当前月及此前五个月；无交易月份由服务端补零。这是普通 CNY 收支统计，不代表账户余额变化或投资收益。</p></section>
      <section className="card"><h3>人工参考资产分布</h3><p>符合条件 {a.total} / 全部 {a.assetTotal} 项资产；当前显示 {a.items.length} 项。完整分母：{a.referenceTotal} CNY。</p>
        {!a.complete && <p role="status">部分视图：仅显示 ID 升序前 20 项，百分比仍基于全部符合条件资产；当前列表不代表完整 100% 配置。</p>}
        {a.items.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="人工参考资产分布表格"><table><thead><tr><th>资产 / ID</th><th>人工参考价值 · CNY</th><th>占全部符合条件资产</th></tr></thead><tbody>{a.items.map(item => <tr key={item.id}><td>{item.name}<small>{item.id}</small></td><td className="finance-amount">{item.value}</td><td className="finance-amount">{item.percentage}%</td></tr>)}</tbody></table></div> : <p>没有符合人工参考分布条件的资产，无法形成分布。</p>}
        <p className="hint">排除（按顺序互斥计数）：非 CNY {a.excludedCurrency}；未定价 {a.excludedUnpriced}；数量缺失或非正 {a.excludedQuantity}；参考价值非正 {a.excludedValue}。</p>
        <p className="hint">不是完整净资产配置，不是实时行情。未定价资产不视为价值零。人工价格更新时间和新鲜度未知；资产记录更新时间不能证明价格新鲜度。不合并账户余额、投资成本或行情估值。</p>
        <button className="secondary" onClick={openAssets}>进入 Assets 查看详细数据</button>
      </section>
      <section className="card"><h3>最近五条普通交易 · CNY</h3>{value.recentTransactions.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="最近普通交易表格"><table><thead><tr><th>时间 / ID</th><th>类型</th><th>账户</th><th>分类</th><th>金额 · CNY</th></tr></thead><tbody>{value.recentTransactions.map(t => <tr key={t.id}><td>{t.transactedAt}<small>{t.id}</small></td><td>{t.type}</td><td>{t.accountName ?? '未知账户'}</td><td>{t.categoryName ?? '未知分类'}</td><td className="finance-amount">{t.amount}</td></tr>)}</tbody></table></div> : <p>当前用户没有 CNY 普通交易。</p>}
        <p className="hint">按交易时间、ID 降序。保留历史 REFUND / ADJUSTMENT / TRANSFER，也可能显示未来日期记录；这些记录不因此计入本月收支。</p></section>
    </>}
  </section>
}
