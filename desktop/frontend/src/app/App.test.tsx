import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { App } from './App'
import { WorkspaceClient } from '../bridge/client'
import { Port, status } from '../test/fixtures'
import { readTheme, saveTheme } from './theme'
import { pageFromHash } from './navigation'
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); localStorage.clear(); history.replaceState(null, '', '/'); vi.restoreAllMocks() })
async function shell() { const port = new Port(); const client = new WorkspaceClient(port); clients.push(client); render(<App bridge={client} />); await act(async () => { port.session(); port.reply() }); return { port, client } }
it('shows the five real navigation routes with safe status and no fake domain data', async () => {
  await shell(); expect(screen.getByRole('navigation').querySelectorAll('a')).toHaveLength(5)
  expect(screen.getByText('可连接')).toBeTruthy(); expect(screen.getByText('有效')).toBeTruthy()
  for (const route of ['assistant', 'conversations', 'memory', 'translate', 'settings']) {
    await act(async () => { location.hash = `#/${route}`; window.dispatchEvent(new Event('hashchange')) })
    expect(screen.getByRole('heading', { level: 1 }).textContent?.toLowerCase()).toBe(route)
    expect(screen.getByRole('navigation').querySelector('[aria-current="page"]')?.getAttribute('href')).toBe(`#/${route}`)
  }
  expect(document.querySelectorAll('textarea')).toHaveLength(3); expect(document.querySelector('iframe')).toBeNull(); expect(screen.queryByText('Knowledge')).toBeNull(); expect(screen.queryByText('Finance')).toBeNull()
  expect(pageFromHash('#/unknown')).toBe('assistant')
})
it('native entry buttons issue only fixed methods and refresh displays unavailable honestly', async () => {
  const { port } = await shell(); expect(screen.queryByRole('button', { name: /打开原生/ })).toBeNull()
  await act(async () => { location.hash = '#/settings'; window.dispatchEvent(new Event('hashchange')) })
  for (const [title, method] of [['凭据管理', 'native.openCredentialFlow'], ['Browser Pairing', 'native.openBrowserPairing'], ['Memory Backup', 'native.openMemoryBackup'], ['Workspace Backup', 'native.openWorkspaceBackup']]) {
    fireEvent.click(screen.getByRole('button', { name: `打开${title}` })); expect(port.sent.at(-1)?.method).toBe(method)
    await act(async () => { port.reply(port.sent.length - 1, { opened: true }) })
  }
  fireEvent.click(screen.getByRole('button', { name: '刷新状态' })); expect(port.sent.at(-1)?.method).toBe('shell.refreshStatus')
  await act(async () => { port.reply(port.sent.length - 1, { ...status, runtime: 'Unavailable', credential: 'Missing' }) })
  expect(screen.getByText('不可连接')).toBeTruthy(); expect(screen.getByText('未导入')).toBeTruthy()
})
it('renders hostile error text as text without script or markup', async () => {
  const { port } = await shell(); await act(async () => { location.hash = '#/settings'; window.dispatchEvent(new Event('hashchange')) }); fireEvent.click(screen.getByRole('button', { name: '打开凭据管理' }))
  const request = port.sent[1]!
  await act(async () => { port.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: 'NATIVE_UNAVAILABLE', message: '<script>window.pwned=1</script><img src=x onerror=alert(1)>' } }) })
  expect(screen.getByRole('alert').textContent).toContain('<script>'); expect(screen.getByRole('alert').querySelector('script,img')).toBeNull()
})
it('persists only bounded theme preference and handles unavailable storage', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'); await shell(); fireEvent.click(screen.getByRole('button', { name: '切换到深色主题' }))
  expect(storage.mock.calls).toEqual([['workspace.theme', 'dark']]); expect(localStorage.length).toBe(1); expect(readTheme()).toBe('dark')
  storage.mockImplementation(() => { throw new Error('unavailable') }); expect(() => saveTheme('light')).not.toThrow()
})
it('shows a native opening instruction when run outside Desktop', () => {
  const client = new WorkspaceClient(undefined); clients.push(client); render(<App bridge={client} />)
  expect(screen.getByRole('alert').textContent).toContain('通过桌面'); expect(screen.queryByRole('button', { name: /打开原生/ })).toBeNull()
})
