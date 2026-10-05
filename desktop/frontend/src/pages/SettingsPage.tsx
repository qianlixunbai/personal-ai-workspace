import type { NativeMethod, ShellStatus } from '../bridge/contracts'
import { StatusPanel } from '../components/StatusPanel'
const maintenance: { method: NativeMethod; title: string; description: string }[] = [
  { method: 'native.openCredentialFlow', title: '凭据管理', description: '在原生 Assistant 中导入或忘记 Windows Credential Manager 的本机凭据。' },
  { method: 'native.openBrowserPairing', title: 'Browser Pairing', description: '在原生窗口中明确授权浏览器扩展使用翻译。' },
  { method: 'native.openMemoryBackup', title: 'Memory Backup', description: '导出或恢复 Memory；文件选择、正文与恢复验证由原生窗口和 Runtime 管理。' },
  { method: 'native.openWorkspaceBackup', title: 'Workspace Backup', description: 'Workspace Backup v1：只包含 Memory + Conversation，不包含 Knowledge；恢复到新建或空目录。' },
  { method: 'native.openKnowledgeBackup', title: 'Knowledge Backup', description: '独立导出、验证或恢复 Knowledge 文档及私有源版本；恢复到新建或空目录。' },
]
export function SettingsPage({ status, busy, open, refresh }: {
  status: ShellStatus | null; busy: boolean; open: (method: NativeMethod) => void; refresh: () => void
}) {
  return <><section className="card" aria-labelledby="settings-status-title"><div className="card-heading"><h2 id="settings-status-title">本机运行状态</h2><button className="secondary" onClick={refresh} disabled={busy || !status}>刷新状态</button></div>
    <StatusPanel status={status} /><dl className="status-list"><div><dt>Application version</dt><dd>{status?.applicationVersion ?? '等待检查'}</dd></div><div><dt>Credential</dt><dd>{status?.credential ?? '等待检查'}</dd></div><div><dt>WebView</dt><dd>{status?.webView ?? '等待检查'}</dd></div></dl>
    <p className="hint">Runtime 可连接与凭据状态不代表所有模型或能力就绪。模型可用性会在具体操作中确认。</p></section>
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
