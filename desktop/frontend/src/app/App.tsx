import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { NativeMethod, ShellStatus } from '../bridge/contracts'
import { pages, pageFromHash } from './navigation'
import { readTheme, saveTheme } from './theme'
import { WorkspacePage } from '../pages/WorkspacePage'
import { OperationPage } from '../pages/OperationPage'

export function App({ bridge }: { bridge: WorkspaceClient }) {
  const [page, setPage] = useState(() => pageFromHash(location.hash))
  const [theme, setTheme] = useState(readTheme)
  const [status, setStatus] = useState<ShellStatus | null>(null)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('正在连接工作区…')
  const generation = useRef(0)
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    let disposed = false
    const bootstrap = () => {
      const current = ++generation.current
      setStatus(null); setBusy(true); setError(''); setNotice('正在检查本机状态…')
      void bridge.bootstrap().then(result => { if (!disposed && current === generation.current) { setStatus(result); setNotice('工作区已连接') } })
        .catch(() => { if (!disposed && current === generation.current) { setError('工作区连接失败。请从原生窗口关闭后重新打开。'); setNotice('') } })
        .finally(() => { if (!disposed && current === generation.current) setBusy(false) })
    }
    const unsubscribe = bridge.onSession(bootstrap)
    if (bridge.ready) bootstrap()
    const timer = setTimeout(() => { if (!disposed && !bridge.ready) { setBusy(false); setError('无法连接工作区，请使用上方的原生 Assistant。'); setNotice('') } }, 12_000)
    if (!bridge.available) { setBusy(false); setError('请通过桌面 Main Workspace 打开此页面。'); setNotice('') }
    return () => { disposed = true; generation.current++; unsubscribe(); clearTimeout(timer) }
  }, [bridge])
  useEffect(() => {
    const route = () => { setPage(pageFromHash(location.hash)); heading.current?.focus({ preventScroll: true }) }
    window.addEventListener('hashchange', route)
    return () => window.removeEventListener('hashchange', route)
  }, [])
  useEffect(() => { document.documentElement.dataset.theme = theme }, [theme])
  const run = async (method?: NativeMethod) => {
    const current = generation.current
    setBusy(true); setError(''); setNotice(method ? '请在原生窗口中继续。' : '正在刷新本机状态…')
    try {
      if (method) await bridge.open(method)
      else { const result = await bridge.refreshStatus(); if (current === generation.current) setStatus(result) }
      if (current === generation.current) setNotice(method ? '原生入口已打开' : '状态已刷新')
    } catch (problem) { if (current === generation.current) { setError(problem instanceof Error ? problem.message : '操作暂时不可用。'); setNotice('') } }
    finally { if (current === generation.current) { setBusy(false); if (method) heading.current?.focus({ preventScroll: true }) } }
  }
  const current = pages.find(item => item.id === page)!
  return <div className="workspace">
    <a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); document.getElementById('main-content')?.focus() }}>跳到页面内容</a>
    <aside className="sidebar"><div className="brand"><span className="brand-mark" aria-hidden="true">P</span><div>Personal AI<span>你的本机工作区</span></div></div>
      <nav aria-label="工作区页面">{pages.map((item, index) => <a key={item.id} href={`#/${item.id}`} aria-current={item.id === page ? 'page' : undefined}>
        <span className="nav-number" aria-hidden="true">0{index + 1}</span>{item.name}</a>)}</nav>
      <div className="sidebar-bottom"><span className="local-badge"><span className="dot online" /> Local only</span><p>内容由本机 Runtime 管理</p>
        <button className="theme-button" onClick={() => { const next = theme === 'light' ? 'dark' : 'light'; saveTheme(next); setTheme(next) }}>切换到{theme === 'light' ? '深色' : '浅色'}主题</button></div>
    </aside>
    <main id="main-content" tabIndex={-1}><header className="page-header"><div><p className="eyebrow">MAIN WORKSPACE</p><h1 ref={heading} tabIndex={-1}>{current.name}</h1><p>{current.subtitle}</p></div><span className="shell-badge">原生功能可用</span></header>
      <div className="page-content" aria-busy={busy}>
        {(['assistant', 'translate'] as const).map(kind => <div key={kind} hidden={page !== kind}>
          <OperationPage kind={kind} bridge={bridge} enabled={!!status && !busy} status={status} visible={page === kind} openNative={() => { void run('native.openLegacyAssistant') }} /></div>)}
        {page !== 'assistant' && page !== 'translate' && <WorkspacePage page={page} status={status} busy={busy} open={method => { void run(method) }} refresh={() => { void run() }} />}
        {error && <p role="alert" className="error">{error}</p>}<p role="status" className="operation-status">{notice}</p>
      </div><footer>Personal AI Workspace<span>本机 · 明确选择 · 由你控制</span></footer>
    </main>
  </div>
}
