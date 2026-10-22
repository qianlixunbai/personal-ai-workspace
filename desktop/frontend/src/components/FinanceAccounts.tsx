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
    <div className="card-heading"><h2 id="finance-title">Finance · 只读账户</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void run() }}>只读刷新账户</button></div>
    <p role="status">{snapshot ? messages[snapshot.state] : '点击只读刷新，核对当前 Finance 状态。'}</p>
    {snapshot?.state === 'READY' && <>
      <p>CNY 总余额：<strong>{snapshot.cnyBalance!.balance} CNY</strong></p>
      {snapshot.account ? <dl className="status-list">
        <div><dt>账户</dt><dd>{snapshot.account.name}</dd></div><div><dt>账户编号 / 类型</dt><dd>{snapshot.account.id} / {snapshot.account.type}</dd></div>
        <div><dt>余额</dt><dd>{snapshot.account.balance} CNY</dd></div><div><dt>账户状态</dt><dd>{snapshot.account.status}</dd></div>
      </dl> : <p>当前绑定用户没有 CNY 账户。</p>}
    </>}
    <button className="secondary" disabled={!enabled || working} onClick={() => { void run(true) }}>原生 Finance 身份绑定…</button>
    <p className="hint">显示首个 CNY 账户及服务端 CNY 总余额。金额由 Finance 服务端读取，金融写入尚未开放。</p>
    {error && <p role="alert" className="error">{error}</p>}
  </section>
}
