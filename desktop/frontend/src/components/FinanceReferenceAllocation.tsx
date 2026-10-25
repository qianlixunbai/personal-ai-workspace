import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceDashboard } from '../bridge/financeDashboard'
import { invalidFinanceIdentity } from '../pages/financeNavigation'
import { Icon } from './Icon'

export function FinanceReferenceAllocation({ value: a, openAssets }: { value: FinanceDashboard['allocation']; openAssets?: () => void }) {
  return <section className="card dashboard-allocation"><div className="card-heading"><h3>人工参考资产分布 <small>Reference allocation</small></h3><Icon name="assets" /></div>
        <div className="allocation-coverage"><span className="coverage-icon"><Icon name="assets" /></span><div><strong>{a.total} <span>/ {a.assetTotal} 项</span></strong><small>符合条件 / 全部资产 · 显示 {a.items.length} 项</small></div><span className="coverage-tag">{a.complete ? '合格资产完整列表' : '部分展示'}</span></div>
        <p className="allocation-denominator">完整分母 <strong>{a.referenceTotal} CNY</strong></p>
        {!a.complete && <p className="coverage-notice" role="status">部分视图：ID 升序前 20 项，占比使用全部符合条件资产的完整分母，不代表完整 100% 配置。</p>}
        {a.items.length ? <ol className="allocation-list" tabIndex={0} aria-label="人工参考资产分布">{a.items.map(item => <li key={item.id}>
          <div><span className="allocation-name">{item.name}<small>ID {item.id}</small></span><strong>{item.percentage}%</strong></div>
          <svg className="allocation-bar" viewBox="0 0 100 1.6" preserveAspectRatio="none" aria-hidden="true"><rect width="100" height="1.6" rx=".8" className="bar-track" /><rect width={`${item.percentage}%`} height="1.6" rx=".8" className="bar-fill" /></svg>
          <small className="allocation-value">{item.value} CNY</small>
        </li>)}</ol> : <div className="dashboard-empty"><Icon name="assets" /><p>没有符合人工参考分布条件的资产，无法形成分布。</p></div>}
        <p className="hint">排除（按顺序互斥计数）：非 CNY {a.excludedCurrency}；未定价 {a.excludedUnpriced}；数量缺失或非正 {a.excludedQuantity}；参考价值非正 {a.excludedValue}。</p>
        {openAssets && <button className="text-button" onClick={openAssets}>进入 Assets 查看详细数据<Icon name="arrow" /></button>}
      </section>
}

export function FinanceAssetAllocation({ bridge, enabled, invalidateNavigation }: { bridge: WorkspaceClient; enabled: boolean; invalidateNavigation?: (() => void) | undefined }) {
  const [value, setValue] = useState<FinanceDashboard['allocation'] | null>(null), [working, setWorking] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), inFlight = useRef(false)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setValue(null); setWorking(false); setError('') }
    const a = bridge.onSession(reset), b = bridge.onStorageStartup(reset)
    return () => { generation.current++; a(); b() }
  }, [bridge])
  useEffect(() => { if (!enabled) { generation.current++; inFlight.current = false; setValue(null); setWorking(false); setError('') } }, [enabled])
  async function read() {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setValue(null); setError(''); setWorking(true)
    try { const result = await bridge.financeDashboard(); if (current === generation.current) setValue(result.allocation) }
    catch (p) { if (current === generation.current) { setError(p instanceof Error ? p.message : '人工参考分布读取失败。'); if (invalidFinanceIdentity(p)) invalidateNavigation?.() } }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  return <div aria-busy={working}><div className="finance-toolbar"><button className="secondary" disabled={!enabled || working} onClick={() => { void read() }}>{working ? '正在读取人工参考分布…' : '读取人工参考资产分布'}</button><span className="hint">独立读取全部合格资产分母，不受当前列表筛选或分页影响。</span></div>{error && <p role="alert" className="error">{error}</p>}{value && <FinanceReferenceAllocation value={value} />}</div>
}
