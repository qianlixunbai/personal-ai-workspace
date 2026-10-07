import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceClient } from './client'
import { Port, status } from '../test/fixtures'
const clients: WorkspaceClient[] = []
export function connected() { const port = new Port(); const client = new WorkspaceClient(port); clients.push(client); port.session(); return { port, client } }
afterEach(() => { clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })
describe('versioned bridge', () => {
  it('keeps Web fetch intent typed, waits for native approval, validates polling and suppresses replaced sessions without replay', async () => {
    vi.useFakeTimers()
    const { port, client } = connected()
    const operationId = '33333333-3333-4333-8333-333333333333'
    const url = 'https://Example.COM.:443/a%2fb?x=1&x=2&q=a+b'
    const result = { requestedUrl: 'https://example.com/a%2fb?x=1&x=2&q=a+b', finalUrl: 'https://example.com/a%2fb?x=1&x=2&q=a+b', hostname: 'example.com', title: 'Public title', acquiredAt: '2026-10-08T01:00:00Z', contentType: 'text/plain', extractionVersion: 'web-extract-1', text: 'Public evidence', titleTruncated: false, textTruncated: false }
    const queued = { operationId, state: 'QUEUED', result: null, error: null }
    const submit = client.submitWebFetch(url)
    expect(port.sent[0]).toMatchObject({ method: 'web.fetchSubmit', payload: { url } })
    expect(Object.keys(port.sent[0]!.payload as object)).toEqual(['url'])
    await vi.advanceTimersByTimeAsync(60_000)
    port.reply(0, { outcome: 'ACCEPTED', operationId, operation: queued })
    await expect(submit).resolves.toEqual({ outcome: 'ACCEPTED', operationId, operation: queued })
    const get = client.getWebFetch(operationId); port.reply(1, { ...queued, state: 'SUCCEEDED', result })
    await expect(get).resolves.toMatchObject({ result: { text: 'Public evidence' } })
    const cancel = client.cancelWebFetch(operationId); port.reply(2, { ...queued, state: 'CANCELLED' })
    await expect(cancel).resolves.toMatchObject({ state: 'CANCELLED', result: null })
    const denied = client.submitWebFetch(url); port.reply(3, { outcome: 'CANCELLED', operationId: null, operation: null })
    await expect(denied).resolves.toMatchObject({ outcome: 'CANCELLED' })
    const unknown = client.submitWebFetch(url); port.reply(4, { outcome: 'UNKNOWN', operationId, operation: null })
    await expect(unknown).resolves.toMatchObject({ outcome: 'UNKNOWN', operationId })
    for (const malformed of [{ ...queued, operationId: crypto.randomUUID() }, { ...queued, state: 'RUNNING', result },
      { ...queued, state: 'CANCELLED', result }, { ...queued, state: 'SUCCEEDED', result: { ...result, text: '界'.repeat(3000) } },
      { ...queued, state: 'SUCCEEDED', result: { ...result, headers: {} } }, { ...queued, state: 'SUCCEEDED', result: { ...result, contentType: 'application/pdf' } }]) {
      const index = port.sent.length; const bad = client.getWebFetch(operationId)
      const rejected = expect(bad).rejects.toMatchObject({ code: 'InvalidResponse' }); port.reply(index, malformed); await rejected
    }
    const errorIndex = port.sent.length; const blocked = client.getWebFetch(operationId)
    const rejected = expect(blocked).rejects.toMatchObject({ code: 'WebTargetNotPublic' })
    const envelope = port.sent[errorIndex]!
    port.emit({ version: 1, sessionId: envelope.sessionId, requestId: envelope.requestId, ok: false, error: { code: 'WebTargetNotPublic', message: '目标被拒绝。' } })
    await rejected
    const lateIndex = port.sent.length; const late = client.getWebFetch(operationId); const replaced = expect(late).rejects.toThrow('重新加载')
    port.session('22222222-2222-4222-8222-222222222222'); await replaced
    port.reply(lateIndex, { ...queued, state: 'SUCCEEDED', result })
    const expiry = client.submitWebFetch(url); const expired = expect(expiry).rejects.toMatchObject({ code: 'OutcomeUnknown' })
    const before = port.sent.length; await vi.advanceTimersByTimeAsync(80_000); await expired; expect(port.sent).toHaveLength(before)
    await expect(client.submitWebFetch('a'.repeat(2049))).rejects.toMatchObject({ code: 'InvalidRequest' })
    expect(port.sent).toHaveLength(before)
  })
  it('correlates out of order status and native responses with empty payloads', async () => {
    const { port, client } = connected(); const first = client.bootstrap(); const second = client.open('native.openMemoryBackup')
    port.reply(1, { opened: true }); port.reply(0); await expect(second).resolves.toEqual({ opened: true }); await expect(first).resolves.toEqual(status)
    expect(port.sent.every(request => Object.keys(request).length === 5 && JSON.stringify(request.payload) === '{}')).toBe(true)
  })
  it.each([{ ...status, bearer: 'forbidden' }, { ...status, credential: 'All models ready' }, { ...status, nativeEntries: ['native.fetch'] }, { ...status, bridgeVersion: 2 }])('rejects invalid safe-status responses', async invalid => {
    const { port, client } = connected(); const request = client.bootstrap(); const checked = expect(request).rejects.toThrow('无效响应'); port.reply(0, invalid); await checked
  })
  it('ignores wrong session, version and request then accepts the correlated reply', async () => {
    const { port, client } = connected(); const request = client.bootstrap(); let finished = false; void request.then(() => { finished = true })
    port.reply(0, status, { sessionId: '22222222-2222-4222-8222-222222222222' }); port.reply(0, status, { requestId: crypto.randomUUID() }); port.reply(0, status, { version: 2 })
    await Promise.resolve(); expect(finished).toBe(false); port.reply(); await expect(request).resolves.toEqual(status)
  })
  it('invalidates pending requests on reload without replay', async () => {
    const { port, client } = connected(); const request = client.open('native.openMemoryBackup'); const checked = expect(request).rejects.toThrow('重新加载')
    port.session('22222222-2222-4222-8222-222222222222'); await checked; port.reply(0, { opened: true }); expect(port.sent).toHaveLength(1)
    const next = client.bootstrap(); port.reply(1); await expect(next).resolves.toEqual(status)
  })
  it('times out and bounds pending work', async () => {
    vi.useFakeTimers(); const { client } = connected(); const requests = Array.from({ length: 8 }, () => client.bootstrap().catch(error => error.message))
    await expect(client.bootstrap()).rejects.toThrow('暂时不可用'); await vi.advanceTimersByTimeAsync(15_000); expect((await Promise.all(requests)).every(message => message.includes('超时'))).toBe(true)
  })
  it('has no browser fallback transport or generic method', async () => {
    const client = new WorkspaceClient(undefined); await expect(client.bootstrap()).rejects.toThrow('尚未连接')
    const { port, client: live } = connected(); await expect(live.open('native.fetch' as never)).rejects.toThrow('不可用'); expect(port.sent).toHaveLength(0)
  })
  it.each(['native.openLegacyAssistant', 'native.openConversations', 'native.openMemory'])('rejects retired native method %s without sending a WebMessage', async method => {
    const { port, client } = connected(); await expect(client.open(method as never)).rejects.toThrow('不可用'); expect(port.sent).toHaveLength(0)
  })
})
