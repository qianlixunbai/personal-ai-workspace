import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'
import { assetTypes } from '../bridge/financeAssets'
import type { FinanceAsset, FinanceAssetPage, FinanceAssetQuery, FinanceAssetReference } from '../bridge/financeAssets'
import { invalidFinanceIdentity } from '../pages/financeNavigation'

const initial: FinanceAssetQuery = { page: 0, size: 10, type: 'ALL', sort: 'CREATED_DESC' }
const types = { ALL: '全部类型', STOCK: '股票', ETF: 'ETF', FUND: '基金', BOND: '债券', GOLD: '黄金', CRYPTO: '加密资产', CASH: '现金资产', OTHER: '其他' }
const states = { READY: '当前绑定身份已验证', DISABLED: 'Finance 已关闭或尚未配置。', NOT_BOUND: '尚未确认 Finance 用户，请先完成原生身份绑定。', BINDING_INVALID: 'Finance 绑定或数据集已改变，资产读取已拒绝。', UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。' }
const freshness = { FRESH: '缓存有效', STALE: '缓存已过期', NEVER_FETCHED: '未获取', PARTIAL: '输入不完整', UNAVAILABLE: '暂无可用估值' }
const warnings: Record<string, string> = { INVALID_INPUT: '数量无效，不能估值。', QUOTE_MISSING: '未提供可用参考行情。', QUOTE_STALE: '参考行情已过期。', FX_MISSING: '缺少换算汇率，CNY 参考值未知。', FX_STALE: '参考汇率已过期。' }
const amount = (value: string | null, currency: string | null) => value === null ? '未提供' : `${value}${currency ? ` ${currency}` : ''}`
const localTime = (value: string) => value.replace('T', ' ')

export function FinanceAssets({ bridge, enabled, openSettings, invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void; invalidateNavigation?: () => void }) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null)
  const [list, setList] = useState<FinanceAssetPage | null>(null)
  const [detail, setDetail] = useState<FinanceAsset | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState(initial)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false), heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setOverview(null); setList(null); setDetail(null); setSelected(null); setQuery(initial); setError(''); setWorking(false) }
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
    setWorking(true); setError(''); setOverview(null); setList(null); setDetail(null); setQuery(next); setSelected(selection)
    try {
      const status = await bridge.financeOverview()
      if (current !== generation.current) return
      if (status.state !== 'READY') { setOverview(status); setSelected(null); invalidateNavigation?.(); return }
      const rows = await bridge.financeAssets(next)
      if (current !== generation.current) return
      const record = selection ? await bridge.financeAsset(selection) : null
      if (current !== generation.current) return
      setOverview(status); setList(rows); setDetail(record)
      if (record) setTimeout(() => { if (current === generation.current) heading.current?.focus() }, 0)
    } catch (problem) {
      if (current === generation.current) { setError(problem instanceof Error ? problem.message : '资产读取失败，请重试。'); if (invalidFinanceIdentity(problem)) { setSelected(null); invalidateNavigation?.() } }
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }

  return <section className="finance-page" aria-label="Finance 只读资产" aria-busy={working}>
    <section className="card">
      <div className="card-heading"><h2>资产</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}>刷新资产</button></div>
      <p role="status">{working ? '正在验证身份并读取资产…' : overview ? `${states[overview.state]}${overview.username ? ` · ${overview.username}` : ''}` : '等待资产读取。'}</p>
      <p className="hint">人工价格估值与市场参考估值分别展示，不合计不同币种。刷新仅重读数据库缓存，不获取或更新行情。</p>
      <div className="finance-toolbar">
        <label>资产类型<select aria-label="资产类型" value={query.type} disabled={!enabled || working} onChange={e => { void refresh({ ...query, page: 0, type: e.target.value as FinanceAssetQuery['type'] }, null) }}>{assetTypes.map(type => <option key={type} value={type}>{types[type]}</option>)}</select></label>
        <label>排序<select aria-label="资产排序" value={query.sort} disabled={!enabled || working} onChange={e => { void refresh({ ...query, page: 0, sort: e.target.value as FinanceAssetQuery['sort'] }, null) }}><option value="CREATED_DESC">创建时间降序</option><option value="ID_ASC">资产 ID 升序</option><option value="NAME_ASC">名称升序</option></select></label>
        <label>每页<select aria-label="每页资产数" value={query.size} disabled={!enabled || working} onChange={e => { void refresh({ ...query, page: 0, size: Number(e.target.value) }, null) }}><option value={2}>2 条</option><option value={10}>10 条</option><option value={20}>20 条</option></select></label>
        <button className="secondary" disabled={!enabled || working} onClick={() => { void refresh(initial, null) }}>清除筛选</button>
      </div>
      <button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><div className="finance-toolbar"><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button><button disabled={!enabled || working} onClick={() => { void refresh(query, null) }}>返回资产列表</button></div></div>}
    {list && <section className="card" aria-label="资产列表">
      {list.items.length ? <div className="finance-table-scroll" tabIndex={0} aria-label="资产表格，可横向滚动"><table>
        <thead><tr><th>资产名称 / ID</th><th>类型 / 市场</th><th className="finance-amount">人工价格估值</th><th className="finance-amount">市场参考估值</th></tr></thead>
        <tbody>{list.items.map(row => <tr key={row.id}><td><button className="finance-account-link" aria-pressed={selected === row.id} onClick={() => { void refresh(query, row.id) }}>{row.name}</button><small>ID {row.id} · {row.symbol ?? '未提供代码'}</small></td>
          <td>{types[row.type]} · {row.market ?? '未提供市场'}<small>资产币种 {row.currency}</small></td>
          <td className="finance-amount">{amount(row.manualMarketValue, row.currency)}<small>人工价格 × 当前数量</small></td>
          <td className="finance-amount">{row.referenceValuation ? <>{amount(row.referenceValuation.baseCurrencyMarketValue, 'CNY')}<small>{freshness[row.referenceValuation.valuationFreshness]}</small></> : '来源不支持'}</td></tr>)}</tbody>
      </table></div> : <p>当前筛选下没有资产。</p>}
      <div className="pagination"><button disabled={working || query.page === 0} onClick={() => { void refresh({ ...query, page: query.page - 1 }, null) }}>上一页</button><span>第 {query.page + 1} 页 · 共 {list.total} 项资产</span>
        <button disabled={working || query.page >= 100000 || BigInt((query.page + 1) * query.size) >= BigInt(list.total)} onClick={() => { void refresh({ ...query, page: query.page + 1 }, null) }}>下一页</button></div>
    </section>}
    {detail && <section className="card finance-detail" aria-labelledby="finance-asset-title">
      <div className="card-heading"><h2 id="finance-asset-title" ref={heading} tabIndex={-1}>资产详情 · {detail.name}</h2><span className="shell-badge">只读资产</span></div>
      <dl className="finance-summary"><div><dt>系统内 ID / 代码</dt><dd>{detail.id} · {detail.symbol ?? '未提供'}</dd></div><div><dt>类型 / 市场 / 币种</dt><dd>{types[detail.type]} · {detail.market ?? '未提供'} · {detail.currency}</dd></div>
        <div><dt>数量</dt><dd className="finance-amount">{detail.quantity}</dd></div><div><dt>平均成本单价</dt><dd className="finance-amount">{amount(detail.avgCost, detail.currency)}</dd></div>
        <div><dt>人工参考单价</dt><dd className="finance-amount">{amount(detail.currentPrice, detail.currency)}</dd></div><div><dt>人工价格估值</dt><dd className="finance-amount">{amount(detail.manualMarketValue, detail.currency)}</dd></div>
        <div><dt>数量模式</dt><dd>{detail.positionMode === 'LEGACY' ? '历史资产 · LEGACY' : '交易投影 · TRANSACTION_DRIVEN'}</dd></div>
        <div><dt>记录创建 / 更新时间（来源本地时间）</dt><dd>{localTime(detail.createdAt)} / {localTime(detail.updatedAt)}</dd></div></dl>
      <p className="hint">人工价格估值由服务端按来源规则计算，不是账户余额或实时净值。来源没有人工价格的专属报价时间；记录更新时间不能视为估值时间。</p>
      {detail.referenceValuation ? <ReferenceDetails value={detail.referenceValuation} /> : <p className="hint">来源仅为 US 股票 / ETF 提供缓存市场参考估值；此资产不支持该估值。</p>}
    </section>}
  </section>
}

function ReferenceDetails({ value: r }: { value: FinanceAssetReference }) {
  return <section aria-label="市场参考估值"><h3>市场参考估值 · {freshness[r.valuationFreshness]}</h3>
    <dl className="finance-summary"><div><dt>参考单价 / 状态</dt><dd className="finance-amount">{amount(r.quotePrice, r.quoteCurrency)} · {freshness[r.quoteFreshness]}</dd></div>
      <div><dt>行情来源 / 报价时间 / 获取时间（UTC）</dt><dd>{r.quoteProvider ?? '未提供'} / {r.quoteTime ?? '未提供'} / {r.quoteFetchedAt ?? '未提供'}</dd></div>
      <div><dt>原币参考市值</dt><dd className="finance-amount">{amount(r.nativeMarketValue, r.quoteCurrency)}</dd></div>
      <div><dt>参考汇率 / 状态</dt><dd>{r.fxRate === null ? '未提供' : `1 ${r.fxBaseCurrency} = ${r.fxRate} ${r.fxQuoteCurrency}`} · {freshness[r.fxFreshness]}</dd></div>
      <div><dt>汇率来源 / 报价时间 / 获取时间（UTC）</dt><dd>{r.fxProvider ?? '未提供'} / {r.fxRateTime ?? '未提供'} / {r.fxFetchedAt ?? '未提供'}</dd></div>
      <div><dt>CNY 市场参考估值</dt><dd className="finance-amount">{amount(r.baseCurrencyMarketValue, r.baseCurrency)}</dd></div>
      <div><dt>服务端计算时间（UTC）</dt><dd>{r.calculatedAt}</dd></div></dl>
    {r.warnings.length > 0 && <p role="status">{r.warnings.map(code => warnings[code]).join(' ')}</p>}
    <p className="hint">当前数量 × 缓存行情 × 行情币种至 CNY 汇率，由来源估值服务计算并四舍五入到 2 位。{!r.fxRequired && r.quotePrice !== null && 'CNY 行情使用系统恒等汇率。'}计算时间不是报价时间；缓存有效也不代表实时。参考数据不替代账务事实，未计算收益率。公式版本 {r.formulaVersion}。</p>
  </section>
}
