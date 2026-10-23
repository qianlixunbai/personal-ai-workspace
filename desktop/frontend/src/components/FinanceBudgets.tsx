import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceBudgetAvailability } from '../bridge/finance'

const states = {
  DISABLED: 'Finance 已关闭或尚未配置。请先打开 Finance 配置。',
  NOT_BOUND: '尚未确认 Finance 用户，请先完成原生身份绑定。',
  BINDING_INVALID: 'Finance 绑定或数据集已改变，预算读取已拒绝。',
  UNAVAILABLE: 'Finance 数据库暂时不可用，请检查连接后重试。',
  UNSUPPORTED: '当前绑定身份已验证 · 预算功能暂不可用',
}

export function FinanceBudgets({ bridge, enabled, openSettings }: { bridge: WorkspaceClient; enabled: boolean; openSettings: () => void }) {
  const [snapshot, setSnapshot] = useState<FinanceBudgetAvailability | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [runtimeReady, setRuntimeReady] = useState(0)
  const generation = useRef(0), inFlight = useRef(false)

  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setSnapshot(null); setWorking(false); setError('') }
    const session = bridge.onSession(reset)
    const runtime = bridge.onStorageStartup(status => { reset(); if (status.canQueryStorage) setRuntimeReady(value => value + 1) })
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  useEffect(() => {
    if (enabled) void refresh()
    else { generation.current++; inFlight.current = false; setSnapshot(null); setWorking(false); setError('') }
  }, [enabled, bridge, runtimeReady])

  async function refresh() {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current
    setWorking(true); setError(''); setSnapshot(null)
    try {
      const result = await bridge.financeBudgets()
      if (current === generation.current) setSnapshot(result)
    } catch (problem) {
      if (current === generation.current) setError(problem instanceof Error ? problem.message : '预算状态读取失败，请重试。')
    } finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }

  return <section className="finance-page" aria-label="Finance 只读预算" aria-busy={working}>
    <section className="card">
      <div className="card-heading"><h2>Budgets · 预算</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void refresh() }}>刷新预算</button></div>
      <p role="status">{working ? '正在验证身份并读取预算状态…' : !enabled ? states.DISABLED : snapshot ? states[snapshot.state] : '等待预算状态读取。'}</p>
      {snapshot?.state === 'UNSUPPORTED' && <>
        <p>当前 Finance 来源尚未提供预算模型及统计规则，暂时无法展示预算列表与详情。</p>
        <p className="hint">这不表示当前用户没有预算。预算金额、币种、周期、适用分类、已使用金额、剩余金额及使用比例均未提供，暂不能判断是否超支。</p>
      </>}
      {snapshot?.state === 'UNAVAILABLE' && <button disabled={working} onClick={() => { void refresh() }}>重试</button>}
      <div className="finance-toolbar"><span className="shell-badge">只读预算</span><button className="secondary" onClick={openSettings}>Finance 配置与身份绑定</button></div>
    </section>
    {error && <div className="error" role="alert"><p>{error}</p><button disabled={!enabled || working} onClick={() => { void refresh() }}>重试</button></div>}
  </section>
}
