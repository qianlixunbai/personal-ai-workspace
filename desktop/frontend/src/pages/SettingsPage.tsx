import type { NativeMethod, ShellStatus } from '../bridge/contracts'
import { StatusPanel } from '../components/StatusPanel'
import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { ModelAction, ModelFact, ModelSnapshot } from '../bridge/models'
const maintenance: { method: NativeMethod; title: string; description: string }[] = [
  { method: 'native.openCredentialFlow', title: '凭据管理', description: '在原生 Assistant 中导入或忘记 Windows Credential Manager 的本机凭据。' },
  { method: 'native.openBrowserPairing', title: 'Browser Pairing', description: '在原生窗口中明确授权浏览器扩展使用翻译。' },
  { method: 'native.openMemoryBackup', title: 'Memory Backup', description: '导出或恢复 Memory；文件选择、正文与恢复验证由原生窗口和 Runtime 管理。' },
  { method: 'native.openWorkspaceBackup', title: 'Workspace Backup', description: 'Workspace Backup v1：只包含 Memory + Conversation，不包含 Knowledge；恢复到新建或空目录。' },
  { method: 'native.openKnowledgeBackup', title: 'Knowledge Backup', description: '独立导出、验证或恢复 Knowledge 文档及私有源版本；恢复到新建或空目录。' },
]
export function SettingsPage({ bridge, status, busy, open, refresh }: {
  bridge: WorkspaceClient; status: ShellStatus | null; busy: boolean; open: (method: NativeMethod) => void; refresh: () => void
}) {
  return <><section className="card" aria-labelledby="settings-status-title"><div className="card-heading"><h2 id="settings-status-title">本机运行状态</h2><button className="secondary" onClick={refresh} disabled={busy || !status}>刷新状态</button></div>
    <StatusPanel status={status} /><dl className="status-list"><div><dt>Application version</dt><dd>{status?.applicationVersion ?? '等待检查'}</dd></div><div><dt>Credential</dt><dd>{status?.credential ?? '等待检查'}</dd></div><div><dt>WebView</dt><dd>{status?.webView ?? '等待检查'}</dd></div></dl>
    <p className="hint">Runtime 可连接与凭据状态不代表所有模型或能力就绪。模型可用性会在具体操作中确认。</p></section>
    <ModelManagement bridge={bridge} enabled={!!status && !busy} />
    <section aria-labelledby="maintenance-title"><h2 id="maintenance-title" className="section-title">原生维护入口</h2><div className="maintenance-list">{maintenance.map(entry => <article className="maintenance-row" key={entry.method}>
      <div><h3>{entry.title}</h3><p>{entry.description}</p></div><button className="secondary" onClick={() => open(entry.method)} disabled={busy || !status?.nativeEntries.includes(entry.method)}>打开<span className="sr-only"> {entry.title}</span></button>
    </article>)}</div></section>
    <section className="card settings-notices" aria-labelledby="settings-privacy-title"><h2 id="settings-privacy-title">本机数据与隐私</h2>
      <p>Local-first：Memory 与 Conversation 由当前 Runtime 的本机 SQLite 保存；Knowledge 使用独立数据库及私有源副本。数据目录由当前 Runtime 管理。</p>
      <p>数据库与 Memory / Workspace 备份包含明文个人内容，请像个人文档一样保护备份。OS 账户与文件权限提供访问边界；当前不提供数据库加密、磁盘取证级擦除或同账户进程隔离。</p>
      <p>React 编辑草稿与搜索只在内存中；关闭或重新加载前会保护未保存的 Memory 修改。凭据、配对秘密、文件路径与备份字节留在原生维护流程。</p>
      <p>Browser companion 仅支持 Translate。Memory 仅由用户明确保存，Ask / Conversation 逐次明确选择。</p>
      <p>关闭 Main Workspace 只关闭此窗口；Desktop 托盘仍可使用。Runtime 与 Ollama 由外部管理。</p>
    </section></>
}

function ModelManagement({ bridge, enabled }: { bridge: WorkspaceClient; enabled: boolean }) {
  const [snapshot, setSnapshot] = useState<ModelSnapshot | null>(null)
  const [handle, setHandle] = useState('')
  const [working, setWorking] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const generation = useRef(0)
  const inFlight = useRef(false)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setWorking(false); setSnapshot(null); setHandle(''); setMessage(''); setError('') }
    const unsubscribe = bridge.onSession(reset)
    return () => { generation.current++; unsubscribe() }
  }, [bridge])
  const inspect = async (recovery = false) => {
    if (inFlight.current || !enabled) return
    inFlight.current = true; const current = ++generation.current; setWorking(true); setError(''); setMessage('正在只读核对模型状态…')
    try {
      const next = await bridge.inspectModels(recovery)
      if (current === generation.current) { setSnapshot(next); setHandle(''); setMessage('已只读刷新；未加载模型。') }
    } catch (problem) { if (current === generation.current) { setSnapshot(null); setError(problem instanceof Error ? problem.message : '模型状态不可用。'); setMessage('') } }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const mutate = async (action: ModelAction, target: string) => {
    if (inFlight.current || !enabled || !snapshot || !target) return
    inFlight.current = true; const current = ++generation.current; setWorking(true); setError(''); setMessage('请在原生确认窗口核对精确操作。')
    const request = { action, handle: target, expectedSelectionRevision: snapshot.status.selectionRevision,
      recoveryGeneration: action === 'RECOVER' ? snapshot.status.recoveryGeneration : null }
    try {
      const result = await bridge.mutateModel(request)
      if (current !== generation.current) return
      if (result.outcome !== 'CANCELLED') { setSnapshot(result.snapshot); setHandle('') }
      setMessage(result.outcome === 'CANCELLED' ? '已拒绝或确认过期；未提交操作。' : result.outcome === 'UNKNOWN'
        ? '提交结果未知。仅核对当前状态，不自动重发；请只读刷新后人工核对 revision。' : '操作已完成；请刷新列表后继续。')
    } catch (problem) { if (current === generation.current) { setSnapshot(null); setHandle(''); setError(problem instanceof Error ? problem.message : '模型操作被拒绝。'); setMessage('') } }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const s = snapshot?.status
  const selected = snapshot?.catalog?.models.find(model => model.handle === handle)
  const configured = snapshot?.catalog?.models.find(model => model.model === s?.configuredModel && (!s.configuredDigest || model.digest === s.configuredDigest))
  const idle = !!s && s.reserved + s.queued + s.executing + s.draining === 0 && !s.switching
  const disabled = !enabled || working
  const fact = (value: ModelFact | undefined, yes: string, no: string) => value === 'TRUE' ? yes : value === 'FALSE' ? no : 'Unknown / 未知'
  return <section className="card" aria-labelledby="models-title" aria-busy={working}>
    <div className="card-heading"><h2 id="models-title">Native Model Management</h2><button className="secondary" disabled={disabled} onClick={() => { void inspect() }}>只读刷新模型</button></div>
    <dl className="status-list">
      <div><dt>Current Active Model</dt><dd>{s?.activeModel ?? '无已验证 Active / 尚未核对'}</dd></div>
      <div><dt>Configured Model</dt><dd>{s?.configuredModel ?? 'Unknown / 未知'} · revision {s?.selectionRevision ?? '未知'}</dd></div>
      <div><dt>Installed / Loaded / Ready</dt><dd>{fact(s?.installed, 'Installed', 'Missing')} / {fact(s?.loaded, 'Loaded', 'Unloaded')} / {s ? s.ready ? 'Ready' : 'Not Ready' : 'Unknown'}</dd></div>
      <div><dt>本地执行 / 排队 / draining</dt><dd>{s ? `${s.executing} / ${s.queued} / ${s.draining}（reservation ${s.reserved}）` : 'Unknown'}</dd></div>
      <div><dt>远端执行</dt><dd>{s?.uncertain ? '完成未知；AI、切换与释放 STOP' : s ? '无 unresolved guard；不证明其他客户端空闲' : 'Unknown'}</dd></div>
      <div><dt>Recovery generation</dt><dd>{s?.recoveryGeneration ?? '尚未核对'}</dd></div>
    </dl>
    {s?.error && <p role="alert" className="error">模型受控状态：{s.error}</p>}
    {snapshot?.error && <p role="alert" className="error">{snapshot.error.message}</p>}
    <label>Installed Models <select value={handle} disabled={disabled || !snapshot?.catalog} onChange={event => setHandle(event.target.value)}>
      <option value="">明确选择一个已核验的本地候选</option>{snapshot?.catalog?.models.map(model => <option key={model.handle} value={model.handle}>{model.model}</option>)}
    </select></label>
    {snapshot?.catalog?.models.length === 0 && <p className="hint">没有符合当前本地 source / completion / context 策略的候选。</p>}
    {selected && <p className="hint">Local source metadata verified · Completion · Context {selected.contextLimit} · Provider-declared Vision: {selected.providerDeclaredVision ? 'Yes' : 'No'} · Workspace Vision Not Validated</p>}
    <div className="card-heading">
      <button disabled={disabled || !selected || !idle || s?.uncertain} onClick={() => { void mutate('SWITCH', handle) }}>Explicit Switch</button>
      <button className="secondary" disabled={disabled || !selected || !idle || s?.uncertain || !s?.activeModel || s.activeModel === selected.model} onClick={() => { void mutate('RELEASE_OLD_THEN_SWITCH', handle) }}>释放旧 Active 后切换</button>
      <button className="secondary" disabled={disabled || !selected || !idle || s?.uncertain} onClick={() => { void mutate('RELEASE', handle) }}>Release 指定单模型</button>
    </div>
    <div className="card-heading">
      <button className="secondary" disabled={disabled || !configured || !idle || s?.uncertain} onClick={() => { if (configured) void mutate('VALIDATE', configured.handle) }}>确认原选择的验证加载</button>
      <button className="secondary" disabled={disabled} onClick={() => { void inspect(true) }}>核对外部恢复（只读）</button>
      <button className="secondary" disabled={disabled || !configured || !idle || !s?.uncertain} onClick={() => { if (configured) void mutate('RECOVER', configured.handle) }}>确认外部完成事实并恢复 guard</button>
    </div>
    <p className="hint">普通切换不会主动释放旧模型。加载仍可能触发 Ollama 自动驱逐旧模型或其他客户端驻留模型；失败不自动恢复旧显存状态。每次操作均须原生确认。</p>
    <p className="hint">恢复前须自行协调共享客户端，确认原请求完成或原服务及 runner 已结束；本地 lease 未退出时保持 STOP。Workspace 不停止或重启外部进程，清除 guard 不证明 Text Ready，不重放原任务。</p>
    <p className="hint">只支持已安装且通过审核的本地 metadata 子集；外部 local-only 配置未验证。Provider 声明 Vision 不表示 Workspace 支持图片处理。</p>
    {error && <p role="alert" className="error">{error}</p>}<p role="status">{message}</p>
  </section>
}
