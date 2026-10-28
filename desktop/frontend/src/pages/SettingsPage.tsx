import type { NativeMethod, ShellStatus } from '../bridge/contracts'
import { StatusPanel } from '../components/StatusPanel'
import { StorageSettings } from '../components/StorageSettings'
import { useEffect, useRef, useState } from 'react'
import { FinanceAccounts } from '../components/FinanceAccounts'
import type { WorkspaceClient } from '../bridge/client'
import type { ModelAction, ModelFact, ModelSnapshot } from '../bridge/models'
const maintenance: { method: NativeMethod; title: string; description: string }[] = [
  { method: 'native.openMemoryBackup', title: '记忆备份', description: '导出或恢复手动保存的记忆。' },
  { method: 'native.openWorkspaceBackup', title: '工作区备份', description: '包含记忆与对话；知识库需单独备份。' },
  { method: 'native.openKnowledgeBackup', title: '知识库备份', description: '导出或恢复文档与源版本。' },
]
export function SettingsPage({ bridge, status, busy, open, refresh }: {
  bridge: WorkspaceClient; status: ShellStatus | null; busy: boolean; open: (method: NativeMethod) => void; refresh: () => void
}) {
  return <div className="settings-page">
    <ModelManagement bridge={bridge} enabled={!!status && !busy} />
    <section className="card" aria-labelledby="shortcuts-title"><h2 id="shortcuts-title">快速助手</h2>
      <div className="shortcut-row"><span>选中文字后选择翻译、总结或解释</span><kbd>Ctrl + Alt + Shift + Q</kbd></div>
      <div className="shortcut-row"><span>直接翻译选中的文字</span><kbd>Ctrl + Alt + Shift + T</kbd></div>
      <p className="hint">也可从托盘打开。Esc 关闭浮窗并清除本次内容；结果需要手动复制。</p></section>
    <details id="settings-connection" className="settings-group"><summary>连接与存储<span>本机服务、数据库与凭据</span></summary>
      <section className="card"><div className="card-heading"><h2 id="settings-status-title" tabIndex={-1}>本机运行状态</h2><button className="secondary" onClick={refresh} disabled={busy || !status}>刷新状态</button></div>
        <StatusPanel status={status} /><p className="hint">服务连接成功不代表模型已就绪。</p>
        <div className="operation-actions"><button disabled={busy || !status?.nativeEntries.includes('native.openCredentialFlow')} onClick={() => open('native.openCredentialFlow')}>管理连接凭据…</button><button disabled={busy || !status?.nativeEntries.includes('native.openBrowserPairing')} onClick={() => open('native.openBrowserPairing')}>浏览器翻译配对…</button></div>
        <details className="input-details"><summary>连接诊断</summary><dl className="status-list"><div><dt>应用版本</dt><dd>{status?.applicationVersion ?? '等待检查'}</dd></div><div><dt>WebView2</dt><dd>{status?.webView ?? '等待检查'}</dd></div></dl></details></section>
      <StorageSettings bridge={bridge} enabled={!!status && !busy} />
    </details>
    <details className="settings-group"><summary>财务连接<span>独立数据库与身份绑定</span></summary><FinanceAccounts bridge={bridge} enabled={!!status && !busy} /></details>
    <section aria-labelledby="maintenance-title"><h2 id="maintenance-title" className="section-title">备份与恢复</h2><p className="hint backup-warning">备份包含明文个人内容，请妥善保管。恢复前请在原生窗口核对范围和目标位置。</p><div className="maintenance-list">{maintenance.map(entry => <article className="maintenance-row" key={entry.method}>
      <div><h3>{entry.title}</h3><p>{entry.description}</p></div><button className="secondary" onClick={() => open(entry.method)} disabled={busy || !status?.nativeEntries.includes(entry.method)}>打开<span className="sr-only"> {entry.title}</span></button>
    </article>)}</div></section>
    <details className="settings-group settings-notices"><summary id="settings-privacy-title">隐私与高级说明</summary>
      <p>Local-first：持久化引擎由当前 Runtime 配置决定，默认 SQLite；PostgreSQL 必须显式配置。Knowledge 私有源文件与数据目录由当前 Runtime 管理。</p>
      <p>数据库与 Memory / Workspace 备份包含明文个人内容，请像个人文档一样保护备份。OS 账户与文件权限提供访问边界；当前不提供数据库加密、磁盘取证级擦除或同账户进程隔离。</p>
      <p>React 编辑草稿与搜索只在内存中；关闭或重新加载前会保护未保存的 Memory 修改。凭据、配对秘密、文件路径与备份字节留在原生维护流程。</p>
      <p>Browser companion 仅支持 Translate。Memory 仅由用户明确保存，Ask / Conversation 逐次明确选择。</p>
      <p>关闭 Main Workspace 只关闭此窗口；Desktop 托盘仍可使用。由 Desktop 启动的开发 Runtime 随 Desktop 退出；Ollama 继续由外部管理。</p>
    </details></div>
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
      if (current === generation.current) { setSnapshot(next); setHandle(''); setMessage('状态已更新，未加载模型。') }
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
  const fact = (value: ModelFact | undefined, yes: string, no: string) => value === 'TRUE' ? yes : value === 'FALSE' ? no : '尚未确认'
  return <section className="card" aria-labelledby="models-title" aria-busy={working}>
    <div className="card-heading"><h2 id="models-title">本机模型</h2><button className="secondary" disabled={disabled} onClick={() => { void inspect() }}>只读刷新模型</button></div>
    <div className="model-overview"><div><span className="quiet-label">当前模型</span><strong>{s?.activeModel ?? (s?.configuredModel ? `${s.configuredModel}（配置选择）` : '尚未检查')}</strong></div><span className={`model-state ${s?.uncertain || s?.error ? 'needs-attention' : ''}`}>{!s ? '等待检查' : s.uncertain ? '执行结果待确认' : s.switching ? '正在切换' : s.ready ? '可以使用' : '尚未就绪'}</span></div>
    {!s && <p className="hint">只读刷新可查看当前模型，不会启动或切换模型。</p>}
    {s?.uncertain && <p role="alert" className="error">上一次模型执行是否完成尚未确认。AI 请求、切换和释放已暂停。请先核对外部服务；刷新不会清除此状态。<small>MODEL_EXECUTION_UNCERTAIN</small></p>}
    {s?.error && !s.uncertain && <p role="alert" className="error">模型状态需要处理：{s.error}</p>}
    {snapshot?.error && <p role="alert" className="error">{snapshot.error.message}</p>}
    <details className="input-details"><summary>模型诊断</summary>
    <dl className="status-list">
      <div><dt>已验证模型</dt><dd>{s?.activeModel ?? '尚无已验证模型'}</dd></div>
      <div><dt>配置选择</dt><dd>{s?.configuredModel ?? '尚未确认'} · 版本 {s?.selectionRevision ?? '未知'}</dd></div>
      <div><dt>安装 / 驻留 / 就绪</dt><dd>{fact(s?.installed, '已安装', '未安装')} / {fact(s?.loaded, '已加载', '未加载')} / {s ? s.ready ? '就绪' : '未就绪' : '尚未确认'}</dd></div>
      <div><dt>本地执行 / 排队 / draining</dt><dd>{s ? `${s.executing} / ${s.queued} / ${s.draining}（reservation ${s.reserved}）` : 'Unknown'}</dd></div>
      <div><dt>远端执行</dt><dd>{s?.uncertain ? '完成未知；AI、切换与释放 STOP' : s ? '无 unresolved guard；不证明其他客户端空闲' : 'Unknown'}</dd></div>
      <div><dt>Recovery generation</dt><dd>{s?.recoveryGeneration ?? '尚未核对'}</dd></div>
    </dl></details>
    {!!snapshot?.catalog?.models.length && <><label className="model-picker">本地模型 <select value={handle} disabled={disabled || !snapshot?.catalog} onChange={event => setHandle(event.target.value)}>
      <option value="">选择已安装的模型</option>{snapshot?.catalog?.models.map(model => <option key={model.handle} value={model.handle}>{model.model} · {model.runner}</option>)}
    </select></label>
    {selected && <details className="input-details"><summary>所选模型详情</summary><p className="hint">本地来源已核验 · 上下文 {selected.contextLimit} · 图片能力尚未验收</p><p className="hint">{selected.runner} · {selected.digest}</p></details>}
    <div className="card-heading">
      <button className="primary" disabled={disabled || !selected || !idle || s?.uncertain} onClick={() => { void mutate('SWITCH', handle) }}>切换模型…</button>
    </div>
    <p className="hint">加载可能影响其他共享客户端的模型驻留，每次操作都需要原生窗口确认。</p></>}
    {snapshot?.catalog?.models.length === 0 && <p className="hint">没有通过当前安全检查的本地模型。</p>}
    <details className="advanced-section"><summary>高级模型操作与恢复</summary>
    <div className="card-heading">
      <button className="secondary" disabled={disabled || !selected || !idle || s?.uncertain || !s?.activeModel || s.activeModel === selected.model && s.activeRunner === selected.runner && s.activeDigest === selected.digest} onClick={() => { void mutate('RELEASE_OLD_THEN_SWITCH', handle) }}>释放旧模型后切换…</button>
      <button className="secondary" disabled={disabled || !selected || !idle || s?.uncertain} onClick={() => { void mutate('RELEASE', handle) }}>释放所选模型…</button>
    </div>
    <div className="card-heading">
      <button className="secondary" disabled={disabled || !configured || !idle || s?.uncertain} onClick={() => { if (configured) void mutate('VALIDATE', configured.handle) }}>确认原选择的验证加载</button>
      <button className="secondary" disabled={disabled} onClick={() => { void inspect(true) }}>核对外部恢复（只读）</button>
      <button className="secondary" disabled={disabled || !configured || !idle || !s?.uncertain} onClick={() => { if (configured) void mutate('RECOVER', configured.handle) }}>确认外部完成事实并恢复 guard</button>
    </div>
    <p className="hint">普通切换不会主动释放旧模型。加载仍可能触发 Ollama 自动驱逐旧模型或其他客户端驻留模型；失败不自动恢复旧显存状态。每次操作均须原生确认。</p>
    <p className="hint">恢复前须自行协调共享客户端，确认原请求完成或原服务及 runner 已结束；本地 lease 未退出时保持 STOP。Workspace 不停止或重启外部进程，清除 guard 不证明 Text Ready，不重放原任务。</p>
    <p className="hint">只支持已安装且通过审核的本地 metadata 子集；外部 local-only 配置未验证。Provider 声明 Vision 不表示 Workspace 支持图片处理。</p>
    </details>
    {error && <p role="alert" className="error">{error}</p>}{message && <p role="status">{message}</p>}
  </section>
}
