import type { NativeMethod, ShellStatus } from '../bridge/contracts'
import type { Page } from '../app/navigation'
import { StatusPanel } from '../components/StatusPanel'
const content = {
  assistant: { title: '从一个问题开始', description: '问答、摘要与逐次选择 Memory 已在原生 Assistant 中可用。', method: 'native.openLegacyAssistant', action: '打开原生 Assistant', label: '问答 · 摘要' },
  conversations: { title: '接着上次的对话', description: '查看已保存的对话、继续交流或管理归档，请打开当前 Conversations 窗口。', method: 'native.openConversations', action: '打开 Conversations', label: '已保存的对话' },
  memory: { title: '保留值得复用的内容', description: '创建、编辑和管理你明确保存的 Memory，请打开当前 Memory 窗口。', method: 'native.openMemory', action: '打开 Memory', label: '手动保存 · 明确选择' },
  translate: { title: '翻译当前选区', description: '使用原生 Assistant 的 Translate，或在其他应用选中文字后按 Ctrl + Alt + Shift + T。', method: 'native.openLegacyAssistant', action: '打开原生 Assistant', label: '桌面选区 · 手动输入' },
} as const
const maintenance: { method: NativeMethod; title: string; description: string }[] = [
  { method: 'native.openCredentialFlow', title: '凭据管理', description: '在原生 Assistant 中导入或忘记本机凭据。' },
  { method: 'native.openBrowserPairing', title: 'Browser Pairing', description: '明确授权浏览器扩展使用翻译。' },
  { method: 'native.openMemoryBackup', title: 'Memory Backup', description: '导出、验证与恢复 Memory。' },
  { method: 'native.openWorkspaceBackup', title: 'Workspace Backup', description: '导出或恢复 Memory 与已完成的对话。' },
]
export function WorkspacePage({ page, status, busy, open, refresh }: {
  page: Page; status: ShellStatus | null; busy: boolean; open: (method: NativeMethod) => void; refresh: () => void
}) {
  const enabled = (method: NativeMethod) => !busy && !!status?.nativeEntries.includes(method)
  if (page === 'settings') return <>
    <section className="card"><div className="card-heading"><h2>本机运行状态</h2><button className="secondary" onClick={refresh} disabled={busy || !status}>刷新状态</button></div>
      <StatusPanel status={status} /><p className="hint">连接与凭据状态不代表所有模型就绪。模型可用性会在具体操作中确认。</p></section>
    <section aria-labelledby="maintenance-title"><h2 id="maintenance-title" className="section-title">原生维护入口</h2><div className="maintenance-list">{maintenance.map(entry => <article className="maintenance-row" key={entry.method}>
      <div><h3>{entry.title}</h3><p>{entry.description}</p></div><button className="secondary" onClick={() => open(entry.method)} disabled={!enabled(entry.method)}>打开<span className="sr-only"> {entry.title}</span></button>
    </article>)}</div></section>
  </>
  const current = content[page]
  return <><section className="card feature-card"><span className="eyebrow">{current.label}</span><h2>{current.title}</h2><p>{current.description}</p>
    <button className="primary" onClick={() => open(current.method)} disabled={!enabled(current.method)}>{current.action}<span aria-hidden="true"> ↗</span></button>
    <p className="hint">此工作区页面提供入口；当前操作在原生窗口中完成。</p></section>
    <section className="card compact-card"><h2>本机状态</h2><StatusPanel status={status} /></section></>
}
