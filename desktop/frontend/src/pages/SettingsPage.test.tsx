import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { SettingsPage } from './SettingsPage'
import { status } from '../test/fixtures'
afterEach(cleanup)
it.each(['Ready', 'Missing', 'Invalid', 'Unavailable'] as const)('displays real %s credential, application version and WebView without configuration claims', credential => {
  render(<SettingsPage status={{ ...status, runtime: 'Unavailable', credential }} busy={false} open={() => {}} refresh={() => {}} />)
  expect(screen.getByText('不可连接')).toBeTruthy(); expect(screen.getByText('1.0.0.0')).toBeTruthy(); expect(screen.getByText('Available')).toBeTruthy()
  expect(screen.getByText(/不代表所有模型或能力就绪/)).toBeTruthy(); expect(document.querySelector('input,select,textarea')).toBeNull()
})
it('refreshes explicitly and offers fixed native maintenance and accurate privacy notices', () => {
  const open = vi.fn(), refresh = vi.fn(); render(<SettingsPage status={status} busy={false} open={open} refresh={refresh} />)
  fireEvent.click(screen.getByRole('button', { name: '刷新状态' })); expect(refresh).toHaveBeenCalledOnce()
  for (const [name, method] of [['凭据管理', 'native.openCredentialFlow'], ['Browser Pairing', 'native.openBrowserPairing'], ['Memory Backup', 'native.openMemoryBackup'], ['Workspace Backup', 'native.openWorkspaceBackup']]) {
    fireEvent.click(screen.getByRole('button', { name: `打开${name}` })); expect(open).toHaveBeenLastCalledWith(method)
  }
  expect(screen.getByText(/明文个人内容/)).toBeTruthy(); expect(screen.getByText(/Browser companion 仅支持 Translate/)).toBeTruthy()
})
