import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { storageRequirements, type StorageStatus } from '../bridge/storage'
import { StorageSettings } from './StorageSettings'

afterEach(cleanup)
it('checks through the bridge, distinguishes prepared from active, clears failures and discards stale session results', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const snapshot: StorageStatus = { checkedAt: '2026-10-18T00:00:00Z', backend: 'SQLITE', configurationTrust: 'NOT_CHECKED', postgresqlConfigured: false,
    connection: 'NOT_CONFIGURED', workspaceSchema: 'NOT_CHECKED', knowledgeSchema: 'NOT_CHECKED', financeEnabled: false,
    financeSchema: 'DISABLED', migration: 'EXPLICIT_APPROVAL_REQUIRED', encryptedBackup: 'MAINTENANCE_ONLY', restore: 'BLOCKED_SECURITY_REQUIREMENTS',
    securityRequirements: [...storageRequirements], errorCode: 'NONE' }
  render(<StorageSettings bridge={bridge} enabled />)
  const check = screen.getByRole('button', { name: '重新检查存储' })
  const host = { runtime: 'AVAILABLE', failure: 'NONE', configurationTrust: 'NOT_CHECKED', canQueryStorage: true,
    configurationAction: 'TRUSTED_CONTEXT_REQUIRED', confirmationRequired: true }
  expect((screen.getByText('原生配置尚未开放') as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByText('迁移与切换未开放') as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByText('正式备份与恢复未开放') as HTMLButtonElement).disabled).toBe(true)
  expect(port.sent).toHaveLength(0)
  fireEvent.click(check); expect(port.sent[0]).toMatchObject({ method: 'storage.startupStatus', payload: {} })
  expect((check as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { port.reply(0, { ...host, runtime: 'UNAVAILABLE', failure: 'STARTUP_CAUSE_UNAVAILABLE', canQueryStorage: false }) })
  expect(screen.getByText('无法确认 Runtime 已启动；具体原因尚无受信证据')).toBeTruthy()
  expect(port.sent).toHaveLength(1); expect(screen.queryByText('SQLite')).toBeNull()
  fireEvent.click(check)
  await act(async () => { port.reply(1, host) })
  expect(port.sent[2]).toMatchObject({ method: 'storage.status', payload: {} })
  await act(async () => { port.reply(2, snapshot) })
  expect(screen.getByText('SQLite')).toBeTruthy(); expect(screen.getByText('未配置')).toBeTruthy()
  fireEvent.click(check)
  await act(async () => { port.reply(3, host) })
  const prepared = { ...snapshot, postgresqlConfigured: true, connection: 'CONNECTED', workspaceSchema: 'V1_V2_READY', knowledgeSchema: 'READY' }
  await act(async () => { port.reply(4, prepared) })
  expect(screen.getByText('数据库结构已准备；当前尚未启用')).toBeTruthy()
  expect(screen.getByText('SQLite')).toBeTruthy()
  fireEvent.click(check)
  await act(async () => { port.reply(5, host) })
  await act(async () => { port.emit({ version: 1, sessionId: port.sent[6]!.sessionId, requestId: port.sent[6]!.requestId,
    ok: false, error: { code: 'RuntimeUnavailable', message: 'Runtime 暂时不可用。' } }) })
  expect(screen.getByRole('alert').textContent).toContain('Runtime 暂时不可用')
  expect(screen.queryByText('可连接')).toBeNull()
  fireEvent.click(check)
  await act(async () => { port.reply(7, host) })
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(8, prepared) })
  expect(screen.queryByText('可连接')).toBeNull(); expect(screen.queryByRole('alert')).toBeNull()
  expect(screen.queryByText('已响应并通过原生认证')).toBeNull()
  fireEvent.click(check)
  await act(async () => { port.session('33333333-3333-4333-8333-333333333333'); port.reply(9, host) })
  expect(port.sent).toHaveLength(10); expect(screen.queryByText('已响应并通过原生认证')).toBeNull()
  const pending = bridge.storageStartupStatus(); const rejected = expect(pending).rejects.toMatchObject({ code: 'InvalidResponse' })
  port.reply(10, { ...host, password: 'synthetic-secret' }); await rejected
  fireEvent.click(check)
  await act(async () => { port.reply(11, { ...host, configurationAction: 'NATIVE_SETUP_AVAILABLE' }) })
  await act(async () => { port.reply(12, snapshot) })
  fireEvent.click(screen.getByRole('button', { name: '打开原生数据库设置' }))
  expect(port.sent[13]).toMatchObject({ method: 'native.openDatabaseSettings', payload: {} })
  expect(screen.queryByLabelText('密码')).toBeNull()
  await act(async () => { port.reply(13, { opened: true }) })
  expect(port.sent[14]).toMatchObject({ method: 'storage.startupStatus', payload: {} })
  await act(async () => { port.reply(14, host) })
  await act(async () => { port.reply(15, snapshot) })
  bridge.dispose()
})

it('observes lifecycle loss and clears an earlier database snapshot without issuing another SQL query', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const host = { runtime: 'AVAILABLE', failure: 'NONE', configurationTrust: 'NOT_CHECKED', canQueryStorage: true,
    configurationAction: 'TRUSTED_CONTEXT_REQUIRED', confirmationRequired: true }
  render(<StorageSettings bridge={bridge} enabled />)
  const notify = (result: unknown, sessionId = '11111111-1111-4111-8111-111111111111') =>
    port.emit({ type: 'storage.lifecycle', version: 1, sessionId, result })
  await act(async () => { notify({ ...host, runtime: 'STARTING', failure: 'STARTING', canQueryStorage: false }) })
  expect(screen.getByText('正在启动受控开发 Runtime')).toBeTruthy()
  expect(port.sent).toHaveLength(0)
  await act(async () => { notify(host) })
  expect(screen.getByText('已响应并通过原生认证')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '重新检查存储' }))
  await act(async () => { port.reply(0, host) })
  const snapshot = { checkedAt: '2026-10-18T00:00:00Z', backend: 'SQLITE', configurationTrust: 'NOT_CHECKED', postgresqlConfigured: false,
    connection: 'NOT_CONFIGURED', workspaceSchema: 'NOT_CHECKED', knowledgeSchema: 'NOT_CHECKED', financeEnabled: false, financeSchema: 'DISABLED',
    migration: 'EXPLICIT_APPROVAL_REQUIRED', encryptedBackup: 'MAINTENANCE_ONLY', restore: 'BLOCKED_SECURITY_REQUIREMENTS', securityRequirements: [...storageRequirements], errorCode: 'NONE' }
  await act(async () => { port.reply(1, snapshot) })
  expect(screen.getByText('SQLite')).toBeTruthy()
  await act(async () => { notify({ ...host, runtime: 'UNAVAILABLE', failure: 'PROCESS_EXITED', canQueryStorage: false }) })
  expect(screen.getByText('本次受控 Runtime 已退出')).toBeTruthy()
  expect(screen.queryByText('SQLite')).toBeNull(); expect(port.sent).toHaveLength(2)
  fireEvent.click(screen.getByRole('button', { name: '重新检查存储' }))
  await act(async () => { port.reply(2, host) })
  await act(async () => { notify({ ...host, runtime: 'UNAVAILABLE', failure: 'PROCESS_EXITED', canQueryStorage: false }); port.reply(3, snapshot) })
  expect(screen.queryByText('SQLite')).toBeNull()
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); notify(host) })
  expect(screen.queryByText('已响应并通过原生认证')).toBeNull()
  bridge.dispose()
})
