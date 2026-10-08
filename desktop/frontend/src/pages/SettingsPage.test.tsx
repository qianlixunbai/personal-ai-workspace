import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port, status } from '../test/fixtures'
import type { ModelSnapshot } from '../bridge/models'
import { SettingsPage } from './SettingsPage'

afterEach(() => { cleanup(); vi.useRealTimers() })
it('uses typed native model intents, preserves unknown residency and blocks replay or stale session responses', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session()
  const snapshot: ModelSnapshot = { status: { configuredModel: 'a:latest', configuredDigest: 'a'.repeat(64), activeModel: 'a:latest', activeDigest: 'a'.repeat(64),
    selectionRevision: 1, installed: 'TRUE', loaded: 'UNKNOWN', ready: true, reserved: 0, queued: 0, executing: 0, draining: 0, switching: false, uncertain: false,
    recoveryGeneration: '44444444-4444-4444-8444-444444444444', validationRequired: false, error: null }, catalog: { selectionRevision: 1,
    models: [{ handle: '33333333-3333-4333-8333-333333333333', model: 'b:latest', digest: 'b'.repeat(64), contextLimit: 32768, completion: true, localSourceVerified: true, providerDeclaredVision: true }] }, error: null }
  render(<SettingsPage bridge={bridge} status={status} busy={false} open={vi.fn()} refresh={vi.fn()} />)
  fireEvent.click(screen.getByText('只读刷新模型')); expect(port.sent[0]).toMatchObject({ method: 'models.inspect', payload: {} })
  await act(async () => { port.reply(0, snapshot) })
  expect(screen.getByText('Installed / Unknown / 未知 / Ready')).toBeTruthy()
  fireEvent.change(screen.getByRole('combobox'), { target: { value: snapshot.catalog!.models[0]!.handle } })
  expect(screen.getByText(/Provider-declared Vision: Yes · Workspace Vision Not Validated/)).toBeTruthy()
  fireEvent.click(screen.getByText('Explicit Switch'))
  expect(port.sent[1]).toMatchObject({ method: 'models.mutate', payload: { action: 'SWITCH', handle: snapshot.catalog!.models[0]!.handle, expectedSelectionRevision: 1, recoveryGeneration: null } })
  expect(Object.keys(port.sent[1]!.payload as object)).toEqual(['action', 'handle', 'expectedSelectionRevision', 'recoveryGeneration'])
  expect((screen.getByText('Explicit Switch') as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { port.reply(1, { outcome: 'UNKNOWN', snapshot: { ...snapshot, catalog: null } }) })
  expect(screen.getByText(/提交结果未知。仅核对当前状态/)).toBeTruthy(); expect(port.sent).toHaveLength(2)
  expect((screen.getByText('Release 指定单模型') as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByText('只读刷新模型'))
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); port.reply(2, snapshot) })
  expect(screen.queryByText('Installed / Unknown / 未知 / Ready')).toBeNull()
  const pending = bridge.inspectModels(); const rejected = expect(pending).rejects.toMatchObject({ code: 'InvalidResponse' })
  port.reply(3, { ...snapshot, catalog: { ...snapshot.catalog, endpoint: 'http://remote.invalid' } }); await rejected
  bridge.dispose()
})
