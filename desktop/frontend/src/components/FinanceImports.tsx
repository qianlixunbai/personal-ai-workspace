import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import type { FinanceImportPage, FinanceImportSegment } from '../bridge/financeImports'

const states = { READY: '当前绑定身份已验证', DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请完成原生身份绑定。', BINDING_INVALID: 'Finance 绑定或数据集已改变，读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。' }
export function FinanceImports({ bridge, enabled, openSettings }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null)
  const [list, setList] = useState<FinanceImportPage | null>(null)
  const [detail, setDetail] = useState<FinanceImportSegment | null>(null)
  const [page, setPage] = useState(0), [size, setSize] = useState(10)
  const [selected, setSelected] = useState<string | null>(null)
  const [rowPage, setRowPage] = useState(0), [impactPage, setImpactPage] = useState(0)
  const [working, setWorking] = useState(false), [error, setError] = useState(''), [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false), heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    const reset = () => {
      generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setSelected(null)
      setPage(0); setRowPage(0); setImpactPage(0); setWorking(false); setError('')
    }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(n => n + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    if (enabled) void refresh(0, size, null, 0, 0)
    else { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setSelected(null); setPage(0); setRowPage(0); setImpactPage(0); setWorking(false); setError('') }
  }, [enabled, bridge, runtimeReady])
  async function refresh(nextPage = page, nextSize = size, id = selected, rows = rowPage, impacts = impactPage) {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setOverview(null); setList(null); setDetail(null); setError(''); setWorking(true)
    setPage(nextPage); setSize(nextSize); setSelected(id); setRowPage(rows); setImpactPage(impacts)
    try {
      const identity = await bridge.financeOverview()
      if (current !== generation.current) return
      if (identity.state !== 'READY') { setOverview(identity); setSelected(null); return }
      const batches = await bridge.financeImports(nextPage, nextSize)
      if (current !== generation.current) return
      const receipt = id ? await bridge.financeImportReceipt(id, rows, impacts) : null
      if (current !== generation.current) return
      setOverview(identity); setList(batches); setDetail(receipt)
      if (receipt) setTimeout(() => { if (current === generation.current) heading.current?.focus() }, 0)
    } catch (problem) { if (current === generation.current) setError(problem instanceof Error ? problem.message : '导入历史读取失败，请重试。') }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const r = detail?.receipt
  return <section className="finance-page" aria-label="Import History 只读导入历史" aria-busy={working}>
    <section className="card"><div className="card-heading"><h2>Import History · 导入历史</h2><button disabled={!enabled || working} onClick={() => { void refresh(0, size, selected, 0, 0) }}>刷新导入历史</button></div>
      <p role="status">{working ? '正在验证身份并读取导入历史…' : overview ? states[overview.state] : '等待读取导入历史。'}</p>
      {overview?.state === 'READY' && <p>当前 Finance 用户：{overview.username}</p>}
      <p className="hint">只展示已确认的 Batch。Session 是导入会话，Preview 是确认前的预览；Confirmation Receipt 是历史提交证明。未完成、过期或废弃的预览不会出现在这里。</p>
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试导入历史</button><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh(page, size, null, 0, 0) }}>返回导入批次列表</button></div>}
    {list && <section className="card"><div className="card-heading"><h2>已确认导入批次</h2><label>每页<select aria-label="每页导入批次数" value={size} disabled={working} onChange={e => { void refresh(0, Number(e.target.value), null, 0, 0) }}><option value={2}>2 条</option><option value={10}>10 条</option><option value={20}>20 条</option></select></label></div>
      {list.items.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="导入批次表格，可横向滚动"><table><thead><tr><th>原始文件 / Batch ID</th><th>确认时间 · UTC</th><th>状态</th><th>总行数</th><th>警告数</th><th>Contract Version</th></tr></thead><tbody>{list.items.map(b => <tr key={b.importBatchId}>
        <td><button className="finance-account-link" aria-pressed={selected === b.importBatchId} onClick={() => { void refresh(page, size, b.importBatchId, 0, 0) }}>{b.sourceFileName}</button><small>{b.importBatchId}</small></td><td>{b.confirmedAt}</td><td>{b.status}</td><td>{b.totalRows}</td><td>{b.warningCount}</td><td>{b.contractVersion}</td>
      </tr>)}</tbody></table></div> : <p>当前绑定用户没有已确认的导入批次。</p>}
      <div className="pagination"><button disabled={working || page === 0} onClick={() => { void refresh(page - 1, size, null, 0, 0) }}>上一页</button><span>第 {page + 1} 页 · 共 {list.total} 个已确认批次</span><button disabled={working || page >= 100000 || BigInt((page + 1) * size) >= BigInt(list.total)} onClick={() => { void refresh(page + 1, size, null, 0, 0) }}>下一页</button></div>
      <p className="hint">按确认时间、Batch ID 降序；翻页重新读取服务端。文件名仅显示安全名称，私有路径会隐藏。</p>
    </section>}
    {detail && r && <section className="card finance-detail" aria-label="Confirmation Receipt"><h2 ref={heading} tabIndex={-1}>Confirmation Receipt · 确认回执</h2>
      <p>历史提交时的证明，不代表账户当前状态。金额是服务端保存的精确十进制快照；页面不重算余额。</p>
      <dl className="finance-summary">{([
        ['Batch ID · 已确认批次', r.importBatchId], ['Session ID · 原导入会话', r.importSessionId], ['Source File Name', r.sourceFileName], ['File Digest · SHA-256', r.fileDigest],
        ['Confirmed At · UTC', r.confirmedAt], ['Contract Version', r.contractVersion], ['状态', r.status], ['总行数 / 接受数 / 创建数 / 跳过数', `${r.totalRows} / ${r.acceptedRows} / ${r.createdCount} / ${r.skippedCount}`],
        ['Warning Count', r.warningCount], ['Result Digest · 已验证完整历史回执', r.resultDigest],
      ] as const).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
      <p role="status">{detail.completeReceipt ? '当前显示完整回执。' : '服务端已校验完整回执；当前为分段视图，以下表格仅显示本页。请分别翻页查看全部证据。'}</p>
      <h3>Source Row → Created Transaction · 历史行映射</h3><p>Transaction ID 是确认时创建的历史标识；交易后来修改或删除不会改写本回执。</p>
      <div className="finance-table-scroll" tabIndex={0} aria-label="回执行映射"><table><thead><tr><th>Source Row Number</th><th>Created Transaction ID</th></tr></thead><tbody>{r.transactions.map(t => <tr key={t.rowNumber}><td>{t.rowNumber}</td><td>{t.transactionId}</td></tr>)}</tbody></table></div>
      <div className="pagination"><button disabled={working || rowPage === 0} onClick={() => { void refresh(page, size, selected, rowPage - 1, impactPage) }}>上一段行映射</button><span>行映射第 {rowPage + 1} / {Math.ceil(r.createdCount / detail.segmentSize)} 页 · 共 {r.createdCount} 行</span><button disabled={working || (rowPage + 1) * detail.segmentSize >= r.createdCount} onClick={() => { void refresh(page, size, selected, rowPage + 1, impactPage) }}>下一段行映射</button></div>
      <h3>Account Impact · 历史账户影响</h3><p className="hint">Balance Before / Delta / Balance After 均来自确认时保存的回执，单位沿用对应 Account 的账务单位；不跨账户汇总或折算币种。</p>
      <div className="finance-table-scroll" tabIndex={0} aria-label="回执账户影响"><table><thead><tr><th>Account ID</th><th>Row Count</th><th>Balance Before</th><th>Delta</th><th>Balance After · 历史确认后</th></tr></thead><tbody>{r.accountImpacts.map(a => <tr key={a.accountId}><td>{a.accountId}</td><td>{a.rowCount}</td><td className="finance-amount">{a.balanceBefore}</td><td className="finance-amount">{a.delta}</td><td className="finance-amount">{a.balanceAfter}</td></tr>)}</tbody></table></div>
      <div className="pagination"><button disabled={working || impactPage === 0} onClick={() => { void refresh(page, size, selected, rowPage, impactPage - 1) }}>上一段账户影响</button><span>账户影响第 {impactPage + 1} / {Math.ceil(detail.accountImpactCount / detail.segmentSize)} 页 · 共 {detail.accountImpactCount} 个账户</span><button disabled={working || (impactPage + 1) * detail.segmentSize >= detail.accountImpactCount} onClick={() => { void refresh(page, size, selected, rowPage, impactPage + 1) }}>下一段账户影响</button></div>
    </section>}
  </section>
}
