import { useCallback, useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { NativeMethod, ShellStatus } from '../bridge/contracts'
import { pages, pageFromHash } from './navigation'
import { readTheme, saveTheme } from './theme'
import { SettingsPage } from '../pages/SettingsPage'
import { MemoryPage } from '../pages/MemoryPage'
import type { MemoryLeaveGuard } from '../pages/MemoryPage'
import { OperationPage } from '../pages/OperationPage'
import { ConversationsPage } from '../pages/ConversationsPage'
import { FinancePage } from '../pages/FinancePage'
import { KnowledgePage } from '../pages/KnowledgePage'
import { Icon } from '../components/Icon'
import { FinanceArtwork } from '../components/FinanceArtwork'

export function App({ bridge }: { bridge: WorkspaceClient }) {
  const [page, setPage] = useState(() => pageFromHash(location.hash))
  const [theme, setTheme] = useState(readTheme)
  const [status, setStatus] = useState<ShellStatus | null>(null)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('正在连接工作区…')
  const generation = useRef(0)
  const statusRequest = useRef(0)
  const heading = useRef<HTMLHeadingElement>(null)
  const pageRef = useRef(page); pageRef.current = page
  const leaveMemory = useRef<MemoryLeaveGuard>(leave => leave())
  const focusRuntime = useRef(false)
  const showRuntime = () => {
    const group = document.getElementById('settings-connection')
    if (group instanceof HTMLDetailsElement) group.open = true
    document.getElementById('settings-status-title')?.focus()
  }
  const registerLeave = useCallback((guard: MemoryLeaveGuard) => { leaveMemory.current = guard; return () => { leaveMemory.current = leave => leave() } }, [])
  const navigate = (next: typeof page, runtime = false) => {
    if (next === pageRef.current) {
      if (runtime) showRuntime()
      return
    }
    const commit = () => { focusRuntime.current = runtime; history.replaceState(null, '', `#/${next}`); setPage(next); heading.current?.focus({ preventScroll: true }) }
    if (pageRef.current === 'memory') leaveMemory.current(commit); else commit()
  }
  useEffect(() => {
    let disposed = false
    const bootstrap = () => {
      const current = ++generation.current
      const request = ++statusRequest.current
      setStatus(null); setBusy(true); setError(''); setNotice('正在检查本机状态…')
      void bridge.bootstrap().then(result => { if (!disposed && current === generation.current && request === statusRequest.current) { setStatus(result); setNotice('') } })
        .catch(() => { if (!disposed && current === generation.current) { setError('工作区连接失败。请从原生窗口关闭后重新打开。'); setNotice('') } })
        .finally(() => { if (!disposed && current === generation.current) setBusy(false) })
    }
    const unsubscribe = bridge.onSession(bootstrap)
    const unsubscribeLifecycle = bridge.onStorageStartup(() => {
      const current = generation.current, request = ++statusRequest.current
      void bridge.refreshStatus().then(result => {
        if (!disposed && current === generation.current && request === statusRequest.current) setStatus(result)
      }).catch(() => {
        if (!disposed && current === generation.current && request === statusRequest.current)
          setStatus(previous => previous ? { ...previous, runtime: 'Unavailable', credential: 'Unavailable' } : previous)
      })
    })
    if (bridge.ready) bootstrap()
    const timer = setTimeout(() => { if (!disposed && !bridge.ready) { setBusy(false); setError('无法连接工作区，请从托盘打开 Quick Assistant，或关闭后重新打开工作区。'); setNotice('') } }, 12_000)
    if (!bridge.available) { setBusy(false); setError('请通过桌面 Main Workspace 打开此页面。'); setNotice('') }
    return () => { disposed = true; generation.current++; unsubscribe(); unsubscribeLifecycle(); clearTimeout(timer) }
  }, [bridge])
  useEffect(() => {
    const route = () => {
      const next = pageFromHash(location.hash)
      if (next === pageRef.current) return
      if (pageRef.current === 'memory') history.replaceState(null, '', '#/memory')
      navigate(next)
    }
    window.addEventListener('hashchange', route)
    return () => window.removeEventListener('hashchange', route)
  }, [])
  useEffect(() => { document.documentElement.dataset.theme = theme }, [theme])
  useEffect(() => {
    if (page === 'settings' && focusRuntime.current) {
      focusRuntime.current = false
      showRuntime()
    }
  }, [page])
  const run = async (method?: NativeMethod) => {
    const current = generation.current
    const request = method ? statusRequest.current : ++statusRequest.current
    setBusy(true); setError(''); setNotice(method ? '请在原生窗口中继续。' : '正在刷新本机状态…')
    try {
      if (method) await bridge.open(method)
      else { const result = await bridge.refreshStatus(); if (current === generation.current && request === statusRequest.current) setStatus(result) }
      if (current === generation.current) setNotice(method ? '原生入口已打开' : '状态已刷新')
    } catch (problem) { if (current === generation.current) { setError(problem instanceof Error ? problem.message : '操作暂时不可用。'); setNotice('') } }
    finally { if (current === generation.current) { setBusy(false); if (method) heading.current?.focus({ preventScroll: true }) } }
  }
  const current = pages.find(item => item.id === page)!
  const runtimeLabel = status ? status.runtime === 'Available' ? '服务已连接' : '服务未连接' : '正在连接'
  return <div className={`workspace workspace-${page}`}>
    <a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); document.getElementById('main-content')?.focus() }}>跳到页面内容</a>
    <aside className="sidebar"><div className="brand"><span className="brand-mark"><Icon name="cube" /></span><div>个人 AI 工作区<span>Personal AI Workspace</span></div></div>
      <nav className="workspace-navigation" aria-label="工作区页面">{(['工作', '资料', '业务'] as const).map(group => <div className="nav-group" key={group}>
        <p className="nav-group-label">{group}</p>{pages.filter(item => item.group === group).map(item => <a key={item.id} href={`#/${item.id}`} title={item.label} aria-label={item.label} onClick={event => { event.preventDefault(); navigate(item.id) }} aria-current={item.id === page ? 'page' : undefined}>
          <Icon name={item.id} /><span className="nav-copy"><span className="nav-label">{item.label}</span></span></a>)}</div>)}</nav>
      <label className="workspace-picker">工作区页面<select value={page} onChange={event => navigate(event.target.value as typeof page)}>{pages.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
      <div className="sidebar-bottom">
        <button className="runtime-link" title={`${runtimeLabel}；查看本机运行状态，模型状态请在设置中检查`} aria-label={`${runtimeLabel}，查看本机运行状态`} onClick={() => navigate('settings', true)}><Icon name="status" /><span className="nav-copy"><span>{runtimeLabel}</span></span><span className={`dot ${status?.runtime === 'Available' ? 'online' : ''}`} /></button>
        <a className="settings-link" href="#/settings" title="设置" aria-label="设置" aria-current={page === 'settings' ? 'page' : undefined} onClick={event => { event.preventDefault(); navigate('settings') }}><Icon name="settings" /><span className="nav-copy"><span className="nav-label">设置</span></span></a>
        <button className="theme-button" title={`切换到${theme === 'light' ? '深色' : '浅色'}主题`} aria-label={`切换到${theme === 'light' ? '深色' : '浅色'}主题`} onClick={() => { const next = theme === 'light' ? 'dark' : 'light'; saveTheme(next); setTheme(next) }}><Icon name={theme === 'light' ? 'moon' : 'sun'} /><span className="nav-copy"><span>{theme === 'light' ? '深色模式' : '浅色模式'}</span></span></button>
        <span className="development-label">开发预览版</span></div>
    </aside>
    <main id="main-content" tabIndex={-1}><header className={`page-header ${page === 'finance' ? 'finance-hero' : ''}`}>
      {page === 'finance' && <FinanceArtwork />}
      <div className="page-heading-copy"><h1 ref={heading} tabIndex={-1}>{current.label}</h1><p>{current.subtitle}</p></div>
      {page === 'assistant' && <span className="shortcut-hint" title="在其他应用选中文字后使用；也可从托盘打开">快速助手 <kbd>Ctrl + Alt + Shift + Q</kbd></span>}</header>
      <div className="page-content" aria-busy={busy}>
        {(['assistant', 'translate'] as const).map(kind => <div key={kind} hidden={page !== kind}>
          <OperationPage kind={kind} bridge={bridge} enabled={!!status && !busy} status={status} visible={page === kind} /></div>)}
        <div hidden={page !== 'conversations'}><ConversationsPage bridge={bridge} enabled={!!status && !busy} visible={page === 'conversations'} /></div>
        <div hidden={page !== 'memory'}><MemoryPage bridge={bridge} enabled={!!status && !busy} visible={page === 'memory'} registerLeave={registerLeave} openBackup={() => { void run('native.openMemoryBackup') }} /></div>
        <div hidden={page !== 'knowledge'}><KnowledgePage bridge={bridge} enabled={!!status && !busy} visible={page === 'knowledge'} /></div>
        {page === 'finance' && <FinancePage bridge={bridge} enabled={!!status && !busy} openSettings={() => navigate('settings')} />}
        {page === 'settings' && <SettingsPage bridge={bridge} status={status} busy={busy} open={method => { void run(method) }} refresh={() => { void run() }} />}
        {error && <p role="alert" className="error">{error}</p>}{notice && <p role="status" className="operation-status">{notice}</p>}
      </div>
    </main>
  </div>
}
