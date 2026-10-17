import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { storageRequirements, type StorageStatus } from '../bridge/storage'
import { StorageSettings } from './StorageSettings'

afterEach(cleanup)
it('checks through the bridge, distinguishes prepared from active, clears failures and discards stale session results', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const snapshot: StorageStatus = { checkedAt: '2026-10-18T00:00:00Z', backend: 'SQLITE', postgresqlConfigured: false,
    connection: 'NOT_CONFIGURED', workspaceSchema: 'NOT_CHECKED', knowledgeSchema: 'NOT_CHECKED', financeEnabled: false,
    financeSchema: 'DISABLED', migration: 'EXPLICIT_APPROVAL_REQUIRED', encryptedBackup: 'MAINTENANCE_ONLY', restore: 'BLOCKED_SECURITY_REQUIREMENTS',
    securityRequirements: [...storageRequirements], errorCode: 'NONE' }
  render(<StorageSettings bridge={bridge} enabled />)
  const check = screen.getByRole('button', { name: '重新检查存储' })
  expect((screen.getByText('迁移与切换未开放') as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByText('正式备份与恢复未开放') as HTMLButtonElement).disabled).toBe(true)
  expect(port.sent).toHaveLength(0)
  fireEvent.click(check); expect(port.sent[0]).toMatchObject({ method: 'storage.status', payload: {} })
  expect((check as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { port.reply(0, snapshot) })
  expect(screen.getByText('SQLite')).toBeTruthy(); expect(screen.getByText('未配置')).toBeTruthy()
  fireEvent.click(check)
  const prepared = { ...snapshot, postgresqlConfigured: true, connection: 'CONNECTED', workspaceSchema: 'V1_V2_READY', knowledgeSchema: 'READY' }
  await act(async () => { port.reply(1, prepared) })
  expect(screen.getByText('数据库结构已准备；当前尚未启用')).toBeTruthy()
  expect(screen.getByText('SQLite')).toBeTruthy()
  fireEvent.click(check)
  await act(async () => { port.emit({ version: 1, sessionId: port.sent[2]!.sessionId, requestId: port.sent[2]!.requestId,
    ok: false, error: { code: 'RuntimeUnavailable', message: 'Runtime 暂时不可用。' } }) })
  expect(screen.getByRole('alert').textContent).toContain('Runtime 暂时不可用')
  expect(screen.queryByText('可连接')).toBeNull()
  fireEvent.click(check)
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(3, prepared) })
  expect(screen.queryByText('可连接')).toBeNull(); expect(screen.queryByRole('alert')).toBeNull()
  expect(port.sent).toHaveLength(4); bridge.dispose()
})
