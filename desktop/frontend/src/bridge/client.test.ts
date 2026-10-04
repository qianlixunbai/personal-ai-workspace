import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceClient } from './client'
import { Port, status } from '../test/fixtures'
const clients: WorkspaceClient[] = []
export function connected() { const port = new Port(); const client = new WorkspaceClient(port); clients.push(client); port.session(); return { port, client } }
afterEach(() => { clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })
describe('versioned bridge', () => {
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
