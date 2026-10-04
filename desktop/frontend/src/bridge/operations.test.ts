import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from './client'
import { isMemoryChoice, isOperation } from './contracts'
import { Port } from '../test/fixtures'
const id = '66666666-6666-4666-8666-666666666666'
const memoryId = '77777777-7777-4777-8777-777777777777'
const clients: WorkspaceClient[] = []
function connected() { const port = new Port(); const client = new WorkspaceClient(port); clients.push(client); port.session(); return { port, client } }
afterEach(() => { clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })
it('validates string revisions past JS integer precision and rejects overflow, unknown selection fields, hidden content and duplicate identities', () => {
  const ref = { memoryId, revision: '9007199254740993', position: 0, title: 'Synthetic title' }
  expect(isMemoryChoice({ changed: true, selectedMemoryRefs: [ref] })).toBe(true)
  for (const change of [{ revision: 1 }, { revision: '9223372036854775808' }, { revision: '01' }, { position: 1 }, { content: 'hidden preview' }])
    expect(isMemoryChoice({ changed: true, selectedMemoryRefs: [{ ...ref, ...change }] })).toBe(false)
  expect(isMemoryChoice({ changed: true, selectedMemoryRefs: [ref, { ...ref, position: 1 }] })).toBe(false)
})
it('rejects malformed terminal truth, oversized result and raw task identity', () => {
  const queued = { operationId: id, status: 'QUEUED', result: null, error: null }
  for (const change of [{ taskId: id }, { status: 'SUCCEEDED', result: null }, { result: 'unverified answer' },
    { status: 'CANCELLED', error: { code: 'InternalError', message: 'safe' } },
    { status: 'SUCCEEDED', result: 'x'.repeat(8193) }]) expect(isOperation({ ...queued, ...change })).toBe(false)
  expect(isOperation({ ...queued, status: 'SUCCEEDED', result: '\u0001'.repeat(8192) })).toBe(true)
})
it('correlates an operation snapshot to the requested operation ID', async () => {
  const { port, client } = connected(); const call = client.getOperation(id); const checked = expect(call).rejects.toMatchObject({ code: 'InvalidResponse' })
  port.reply(0, { operationId: memoryId, status: 'SUCCEEDED', result: 'Synthetic answer', error: null }); await checked
})
it('reload during submit reports unknown outcome and never replays the mutation or leaks result from the stale session', async () => {
  const { port, client } = connected(); const call = client.submitTranslate('Synthetic', 'en'); const checked = expect(call).rejects.toMatchObject({ code: 'OutcomeUnknown' })
  port.session('88888888-8888-4888-8888-888888888888'); await checked
  port.reply(0, { operationId: id, status: 'SUCCEEDED', result: 'Synthetic late answer', error: null }); expect(port.sent).toHaveLength(1)
})
it('timeout after submit is outcome unknown with no automatic retry', async () => {
  vi.useFakeTimers(); const { port, client } = connected(); const call = client.submitAssistant({ mode: 'Ask', text: 'Synthetic', selectedMemoryRefs: [] })
  const checked = expect(call).rejects.toMatchObject({ code: 'OutcomeUnknown' }); await vi.advanceTimersByTimeAsync(30_000); await checked; expect(port.sent).toHaveLength(1)
})
it('rejects unallowlisted error codes without displaying their message', async () => {
  const { port, client } = connected(); const call = client.getOperation(id); const checked = expect(call).rejects.toMatchObject({ code: 'InvalidResponse' })
  const request = port.sent[0]!
  port.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: 'RAW_PROVIDER_STACK', message: 'private path and credential' } }); await checked
})
it('rejects oversized JSON request before transmission with a known validation failure', async () => {
  const { port, client } = connected(); await expect(client.submitAssistant({ mode: 'Summarize', text: '\u0001'.repeat(6000), selectedMemoryRefs: [] })).rejects.toMatchObject({ code: 'InvalidRequest' })
  expect(port.sent).toHaveLength(0)
})
