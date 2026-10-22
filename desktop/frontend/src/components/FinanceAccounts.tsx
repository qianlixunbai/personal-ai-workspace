import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { FinanceOverview } from '../bridge/finance'

export function FinanceAccounts({ bridge, enabled }: { bridge: WorkspaceClient; enabled: boolean }) {
  const [snapshot, setSnapshot] = useState<FinanceOverview | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const generation = useRef(0)
  const inFlight = useRef(false)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setSnapshot(null); setWorking(false); setError('') }
    const session = bridge.onSession(reset); const runtime = bridge.onStorageStartup(reset)
    return () => { generation.current++; session(); runtime() }
  }, [bridge])
  const run = async (binding = false) => {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setWorking(true); setSnapshot(null); setError('')
    try {
      if (binding) await bridge.open('native.openFinanceBinding')
      else { const next = await bridge.financeOverview(); if (current === generation.current) setSnapshot(next) }
    } catch (problem) { if (current === generation.current) setError(problem instanceof Error ? problem.message : 'Finance 读取未完成。') }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const messages = { DISABLED: 'Finance 已关闭。首次使用请在原生窗口确认独立数据库与业务用户。', NOT_BOUND: '尚未确认 Finance 用户，账户读取已拒绝。',
    BINDING_INVALID: 'Finance 绑定无效或数据集、历史用户身份已改变，账户读取已拒绝。请保留绑定文件并核对目标数据库。', UNAVAILABLE: 'Finance 数据库暂时不可用。恢复连接后可只读刷新。', READY: 'Finance 身份已验证 · 只读快照' }
  return <section className="card finance-accounts" aria-labelledby="finance-title" aria-busy={working}>
    <div className="card-heading"><h2 id="finance-title">Finance · 配置与连接</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void run() }}>检查 Finance 连接</button></div>
    <p role="status">{snapshot ? messages[snapshot.state] : '点击只读刷新，核对当前 Finance 状态。'}</p>
    <button className="secondary" disabled={!enabled || working} onClick={() => { void run(true) }}>原生 Finance 身份绑定…</button>
    <p className="hint">账户总览、列表与详情请从侧栏 Finance 打开。已有绑定不可自动更换；数据库凭据只在原生窗口管理。</p>
    {error && <p role="alert" className="error">{error}</p>}
  </section>
}
