import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port, status } from '../test/fixtures'
import type { ModelSnapshot } from '../bridge/models'
import { SettingsPage } from './SettingsPage'
import { App } from '../app/App'

afterEach(() => { cleanup(); vi.useRealTimers(); history.replaceState(null, '', '#/assistant') })
const snapshot: ModelSnapshot = { status: { configuredModel: 'a:latest', configuredDigest: 'a'.repeat(64), configuredRunner: 'ggml', activeModel: 'a:latest', activeDigest: 'a'.repeat(64), activeRunner: 'ggml',
  selectionRevision: 1, installed: 'TRUE', loaded: 'UNKNOWN', ready: true, reserved: 0, queued: 0, executing: 0, draining: 0, switching: false, uncertain: false,
  recoveryGeneration: '44444444-4444-4444-8444-444444444444', validationRequired: false, error: null }, catalog: { selectionRevision: 1,
  models: [{ handle: '33333333-3333-4333-8333-333333333333', model: 'b:latest', runner: 'ggml', digest: 'b'.repeat(64), contextLimit: 32768, completion: true, localSourceVerified: true, providerDeclaredVision: true }] }, error: null }

it('uses typed native model intents, preserves unknown residency and blocks replay or stale session responses', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  render(<SettingsPage bridge={bridge} status={status} busy={false} open={vi.fn()} refresh={vi.fn()} onRuntimeUnconfirmed={vi.fn()} />)
  fireEvent.click(screen.getByText('只读刷新模型')); expect(port.sent[0]).toMatchObject({ method: 'models.inspect', payload: {} })
  await act(async () => { port.reply(0, snapshot) })
  expect(screen.getByText('已安装 / 尚未确认 / 本次报告 Ready')).toBeTruthy()
  fireEvent.change(screen.getByRole('combobox'), { target: { value: snapshot.catalog!.models[0]!.handle } })
  expect(screen.getByText(/图片能力尚未验收/)).toBeTruthy()
  fireEvent.click(screen.getByText('切换模型…'))
  expect(port.sent[1]).toMatchObject({ method: 'models.mutate', payload: { action: 'SWITCH', handle: snapshot.catalog!.models[0]!.handle, expectedSelectionRevision: 1, recoveryGeneration: null } })
  expect(Object.keys(port.sent[1]!.payload as object)).toEqual(['action', 'handle', 'expectedSelectionRevision', 'recoveryGeneration'])
  expect((screen.getByText('切换模型…') as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { port.reply(1, { outcome: 'UNKNOWN', snapshot: { ...snapshot, catalog: null } }) })
  expect(screen.getByText(/提交结果未知。仅核对当前状态/)).toBeTruthy(); expect(port.sent).toHaveLength(2)
  expect(screen.queryByText('本次报告 Ready')).toBeNull()
  expect(screen.getByText(/下一步：显式只读刷新以核对本次模型可用性/)).toBeTruthy()
  expect((screen.getByText('释放所选模型…') as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByText('只读刷新模型'))
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(2, snapshot) })
  expect(screen.queryByText('已安装 / 尚未确认 / 本次报告 Ready')).toBeNull()
  const pending = bridge.inspectModels(); const rejected = expect(pending).rejects.toMatchObject({ code: 'InvalidResponse' })
  port.reply(3, { ...snapshot, catalog: { ...snapshot.catalog, endpoint: 'http://remote.invalid' } }); await rejected
  fireEvent.click(screen.getByText('只读刷新模型'))
  await act(async () => { port.reply(4, { ...snapshot, status: { ...snapshot.status, ready: false, uncertain: true, error: 'MODEL_EXECUTION_UNCERTAIN' } }) })
  fireEvent.change(screen.getByRole('combobox'), { target: { value: snapshot.catalog!.models[0]!.handle } })
  const warning = screen.getByRole('alert')
  expect(warning.textContent).toContain('AI 请求、切换和释放已暂停')
  expect(warning.closest('details')).toBeNull()
  expect((screen.getByText('切换模型…') as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByText('释放所选模型…') as HTMLButtonElement).disabled).toBe(true)
  expect(port.sent.filter(request => request.method === 'models.mutate')).toHaveLength(1)
  fireEvent.click(screen.getByText('只读刷新模型'))
  await act(async () => {
    port.emit({ type: 'storage.lifecycle', version: 1, sessionId: '22222222-2222-4222-8222-222222222222', result: {
      runtime: 'UNAVAILABLE', failure: 'PROCESS_EXITED', configurationTrust: 'NOT_CHECKED', canQueryStorage: false,
      configurationAction: 'NATIVE_SETUP_AVAILABLE', confirmationRequired: true } })
    port.reply(5, snapshot)
  })
  expect(screen.queryByText('本次报告 Ready')).toBeNull()
  expect(port.sent.filter(request => request.method === 'models.mutate')).toHaveLength(1)
  bridge.dispose()
})

it('invalidates runtime and model observations on failed checks and requires an explicit retry', async () => {
  history.replaceState(null, '', '#/settings')
  const port = new Port(), bridge = new WorkspaceClient(port)
  render(<App bridge={bridge} />)
  await act(async () => { port.session(); port.reply(0, { ...status, applicationBuild: '1.0.0+' + 'a'.repeat(40) }) })
  expect(screen.getByText('本次检查可访问')).toBeTruthy()
  expect(screen.getByText('1.0.0+' + 'a'.repeat(40))).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
  expect(screen.queryByText('本次检查可访问')).toBeNull()
  await act(async () => { port.emit({ version: 1, sessionId: port.sent[1]!.sessionId, requestId: port.sent[1]!.requestId,
    ok: false, error: { code: 'RuntimeUnavailable', message: 'Runtime 暂时不可用。' } }) })
  expect(screen.queryByText('本次检查可访问')).toBeNull()
  expect((screen.getByRole('button', { name: '只读刷新模型' }) as HTMLButtonElement).disabled).toBe(true)
  expect(port.sent.map(request => request.method)).toEqual(['shell.bootstrap', 'shell.refreshStatus'])
  fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
  await act(async () => { port.reply(2, { ...status, applicationVersion: 'Unknown' }) })
  expect(screen.getByText('未取得可靠构建身份')).toBeTruthy()
  expect(screen.getByText('未取得')).toBeTruthy()
  expect(screen.getByText('本次检查可访问')).toBeTruthy()
  expect(port.sent.every(request => Object.keys(request.payload as object).length === 0)).toBe(true)

  const storage = screen.getByRole('button', { name: '重新检查存储' }) as HTMLButtonElement
  const model = screen.getByRole('button', { name: '只读刷新模型' }) as HTMLButtonElement
  const refresh = screen.getByRole('button', { name: '刷新状态' })
  const observationFailure = { runtime: 'UNAVAILABLE', failure: 'STARTUP_CAUSE_UNAVAILABLE', configurationTrust: 'NOT_CHECKED', canQueryStorage: false,
    configurationAction: 'NATIVE_SETUP_AVAILABLE', confirmationRequired: true }
  fireEvent.click(model)
  await act(async () => { port.reply(3, snapshot) })
  expect(screen.getByText('本次报告 Ready')).toBeTruthy()
  fireEvent.click(storage)
  await act(async () => { port.reply(4, observationFailure) })
  expect(screen.queryByText('本次检查可访问')).toBeNull()
  expect(screen.queryByText('本次报告 Ready')).toBeNull()
  expect(screen.queryByText(/可继续发起文本任务/)).toBeNull()
  expect(screen.getByRole('button', { name: 'Runtime 待确认，查看本机运行状态' }).querySelector('.dot.online')).toBeNull()
  expect(model.disabled).toBe(true); expect(storage.disabled).toBe(false)

  fireEvent.click(refresh)
  await act(async () => { port.reply(5, status) })
  fireEvent.click(model)
  fireEvent.click(storage)
  await act(async () => { port.reply(7, { ...observationFailure, runtime: 'AVAILABLE', failure: 'NONE', canQueryStorage: true }) })
  await act(async () => { port.emit({ version: 1, sessionId: port.sent[8]!.sessionId, requestId: port.sent[8]!.requestId,
    ok: false, error: { code: 'RuntimeUnavailable', message: 'Runtime 暂时不可用。' } }) })
  expect(model.disabled).toBe(true)
  expect(screen.queryByText('本次检查可访问')).toBeNull()
  expect(screen.getByRole('alert').textContent).toContain('Runtime 暂时不可用。')
  fireEvent.click(refresh)
  await act(async () => { port.reply(9, status); port.reply(6, snapshot) })
  expect(model.disabled).toBe(false)
  expect(screen.queryByText('本次报告 Ready')).toBeNull()
  expect(port.sent.slice(3).map(request => request.method)).toEqual([
    'models.inspect', 'storage.startupStatus', 'shell.refreshStatus', 'models.inspect', 'storage.startupStatus', 'storage.status', 'shell.refreshStatus',
  ])
  bridge.dispose()
})
