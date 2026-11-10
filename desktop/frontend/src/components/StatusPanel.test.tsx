import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { ModelSnapshot } from '../bridge/models'
import { storageRequirements, type StorageStatus, type StorageStartupStatus } from '../bridge/storage'
import { Port, status } from '../test/fixtures'
import { SettingsPage } from '../pages/SettingsPage'
import { StatusPanel, observationFreshMs } from './StatusPanel'

const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })
const host: StorageStartupStatus = { runtime: 'AVAILABLE', failure: 'NONE', configurationTrust: 'NOT_CHECKED', canQueryStorage: true,
  configurationAction: 'TRUSTED_CONTEXT_REQUIRED', confirmationRequired: true }
const model: ModelSnapshot = { status: { configuredModel: 'synthetic:latest', configuredDigest: 'a'.repeat(64), configuredRunner: 'ggml',
  activeModel: 'synthetic:latest', activeDigest: 'a'.repeat(64), activeRunner: 'ggml', selectionRevision: 1, installed: 'TRUE', loaded: 'UNKNOWN',
  ready: true, reserved: 0, queued: 0, executing: 0, draining: 0, switching: false, uncertain: false,
  recoveryGeneration: '44444444-4444-4444-8444-444444444444', validationRequired: false, error: null },
  catalog: { selectionRevision: 1, models: [] }, error: null }
const storage = (): StorageStatus => ({ checkedAt: new Date().toISOString(), backend: 'SQLITE', configurationTrust: 'NOT_CHECKED', postgresqlConfigured: false,
  connection: 'NOT_CONFIGURED', workspaceSchema: 'NOT_CHECKED', knowledgeSchema: 'NOT_CHECKED', financeEnabled: false, financeSchema: 'DISABLED',
  migration: 'EXPLICIT_APPROVAL_REQUIRED', encryptedBackup: 'MAINTENANCE_ONLY', restore: 'BLOCKED_SECURITY_REQUIREMENTS', securityRequirements: [...storageRequirements], errorCode: 'NONE' })
async function connected() {
  const port = new Port(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const pending = bridge.bootstrap(); port.reply(0); await pending
  return { port, bridge }
}
const card = (name: string) => within(screen.getByRole('article', { name }))

it('reuses separate checks, preserves partial results, and routes to existing controls without opening protected actions', async () => {
  const { port, bridge } = await connected(), navigate = vi.fn()
  render(<SettingsPage bridge={bridge} status={status} busy={false} open={vi.fn()} refresh={vi.fn()} onRuntimeUnconfirmed={vi.fn()} navigate={navigate} />)
  expect(port.sent).toHaveLength(1)
  expect(card('模型与 Ollama').getByText('尚未检查')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '重新检查存储' }))
  await act(async () => { port.reply(1, host) })
  await act(async () => { port.reply(2, storage()) })
  expect(card('数据存储').getByText('当前使用 SQLite')).toBeTruthy()
  expect(card('数据存储').getByText(/未验证 SQLite 读写/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '只读刷新模型' }))
  await act(async () => { port.reply(3, { ...model, catalog: null, error: { code: 'ProviderUnavailable', message: '合成目录查询失败。' } }) })
  expect(card('模型与 Ollama').getByText('模型检查部分失败')).toBeTruthy()
  expect(card('数据存储').getByText('当前使用 SQLite')).toBeTruthy()
  expect(card('模型与 Ollama').getByText(/合成目录查询失败/)).toBeTruthy()
  expect(card('模型与 Ollama').getByText(/检查回执/).querySelector('time')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '只读刷新模型' }))
  await act(async () => { port.reply(4, { ...model, status: { ...model.status, ready: false, uncertain: true, error: 'MODEL_EXECUTION_UNCERTAIN' } }) })
  expect(card('模型与 Ollama').getByText('模型执行仍受阻断')).toBeTruthy()
  for (const label of ['恢复暂不可用', '迁移与切换未开放', '正式备份与恢复未开放'])
    expect((screen.getByText(label) as HTMLButtonElement).disabled).toBe(true)
  const group = document.getElementById('settings-connection') as HTMLDetailsElement
  group.open = false
  fireEvent.click(screen.getByRole('button', { name: '查看存储检查' }))
  expect(group.open).toBe(true); expect(document.activeElement?.id).toBe('storage-title')
  fireEvent.click(screen.getByRole('button', { name: '查看财务连接' }))
  expect(document.activeElement?.id).toBe('settings-finance-title')
  fireEvent.click(screen.getByRole('button', { name: '打开知识库' }))
  expect(navigate).toHaveBeenCalledWith('knowledge')
  expect(port.sent.map(request => request.method)).toEqual(['shell.bootstrap', 'storage.startupStatus', 'storage.status', 'models.inspect', 'models.inspect'])
  fireEvent.click(screen.getByText('高级模型操作与恢复'))
  fireEvent.click(screen.getByRole('button', { name: '查看阻断请求（只读）' }))
  await act(async () => { port.reply(5, { recordState: 'INCOMPLETE', recoverySupported: false, requests: [] }) })
  expect(card('模型与 Ollama').getByText('需要重新检查')).toBeTruthy()
  expect(card('模型与 Ollama').getByText(/执行记录检查不能证明当前模型可用/)).toBeTruthy()
  expect(port.sent[5]!.method).toBe('models.inspectRecovery')
})

it('ages receipt and source times across remounts without polling and keeps PostgreSQL preparation distinct from feature readiness', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-10T12:00:00Z'))
  const { port, bridge } = await connected()
  let view = render(<StatusPanel bridge={bridge} status={status} />)
  const pg: StorageStatus = { ...storage(), backend: 'POSTGRESQL', postgresqlConfigured: true, configurationTrust: 'TRUSTED_LOCAL', connection: 'CONNECTED', workspaceSchema: 'V1_V2_READY', knowledgeSchema: 'READY' }
  await act(async () => {
    const data = bridge.storageStatus(); port.reply(1, pg); await data
    const pending = bridge.inspectModels(); port.reply(2, model); await pending
  })
  expect(card('数据存储').getByText('PostgreSQL 本次可连接')).toBeTruthy()
  expect(screen.getByText(/具体记录、私有源文件与读写仍由功能页面确认/)).toBeTruthy()
  expect(card('模型与 Ollama').getByText('检查时通过模型验证')).toBeTruthy()
  expect(screen.getByText(/不证明远端执行结束或其他客户端空闲/)).toBeTruthy()
  const stamp = card('模型与 Ollama').getByText(/检查回执/).querySelector('time')!.dateTime
  view.unmount()
  await act(async () => { await vi.advanceTimersByTimeAsync(observationFreshMs + 5_000) })
  view = render(<StatusPanel bridge={bridge} status={status} />)
  expect(card('模型与 Ollama').getByText('结果已过期')).toBeTruthy()
  expect(card('模型与 Ollama').getByText(/检查回执/).querySelector('time')!.dateTime).toBe(stamp)
  expect(port.sent).toHaveLength(3)
  await act(async () => {
    const shell = bridge.refreshStatus(); port.reply(3); await shell
    const data = bridge.storageStatus(); port.reply(4, pg); await data
  })
  expect(card('Java Runtime').getByText('本次检查可访问')).toBeTruthy()
  expect(card('数据存储').getByText('结果已过期')).toBeTruthy()
  await act(async () => {
    const data = bridge.storageStatus(); port.reply(5, { ...pg, checkedAt: new Date(Date.now() + 60_000).toISOString() }); await data
  })
  expect(card('数据存储').getByText('检查时间待核对')).toBeTruthy()
  expect(port.sent).toHaveLength(6)
  view.unmount()
})

it('discards late checks after lifecycle and session changes, exposes failed checks, and ignores old native completion', async () => {
  const { port, bridge } = await connected()
  render(<StatusPanel bridge={bridge} status={status} />)
  let pending!: Promise<ModelSnapshot>
  await act(async () => { pending = bridge.inspectModels() })
  await act(async () => {
    port.emit({ type: 'storage.lifecycle', version: 1, sessionId: port.sent[0]!.sessionId,
      result: { ...host, runtime: 'UNAVAILABLE', failure: 'ADMISSION_DENIED', canQueryStorage: false } })
    port.reply(1, model); await pending
  })
  expect(card('启动观察').getByText('启动准入未通过')).toBeTruthy()
  expect(card('模型与 Ollama').getByText('需要重新检查')).toBeTruthy()
  await act(async () => {
    const shell = bridge.refreshStatus(); port.reply(2); await shell
    const data = bridge.storageStatus()
    const rejected = expect(data).rejects.toMatchObject({ code: 'InvalidResponse' })
    port.reply(3, { ...storage(), unexpected: 'untrusted-field' }); await rejected
  })
  expect(card('数据存储').getByText('检查失败')).toBeTruthy()
  expect(card('数据存储').getByText(/失败回执/)).toBeTruthy()
  let native!: Promise<unknown>, rejectedNative!: Promise<unknown>
  await act(async () => {
    native = bridge.open('native.openCredentialFlow'); rejectedNative = expect(native).rejects.toBeTruthy()
    pending = bridge.inspectModels()
  })
  const rejectedModel = expect(pending).rejects.toBeTruthy()
  await act(async () => {
    port.session('22222222-2222-4222-8222-222222222222')
    const shell = bridge.bootstrap(); port.reply(6); await shell
    await rejectedNative; await rejectedModel
    port.reply(4, { opened: true }); port.reply(5, model)
  })
  expect(card('Java Runtime').getByText('本次检查可访问')).toBeTruthy()
  expect(card('模型与 Ollama').getByText('尚未检查')).toBeTruthy()
  expect(card('数据存储').getByText('尚未检查')).toBeTruthy()
  expect(port.sent).toHaveLength(7)
})
