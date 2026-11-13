import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceClient } from './client'
import { Port, status } from '../test/fixtures'
import { conversation, uuid } from '../test/conversationFixtures'
const clients: WorkspaceClient[] = []
export function connected() { const port = new Port(); const client = new WorkspaceClient(port); clients.push(client); port.session(); return { port, client } }
afterEach(() => { clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })
describe('versioned bridge', () => {
  it('accepts only bounded, ordered conversation admission evidence and never replays unknown or late receipts', async () => {
    vi.useFakeTimers()
    const { port, client } = connected()
    const receipt = { accepted: true, conversationId: conversation.id, turnId: uuid(104), admittedSequences: [1, 3], memoryCount: 0 }
    const first = client.sendConversation(conversation.id, 'Synthetic', []); port.reply(0, receipt)
    await expect(first).resolves.toEqual(receipt)
    const empty = client.sendConversation(conversation.id, 'Synthetic', []); port.reply(1, { ...receipt, admittedSequences: [] })
    await expect(empty).resolves.toMatchObject({ admittedSequences: [], memoryCount: 0 })
    for (const invalid of [{ ...receipt, admittedSequences: [3, 1] }, { ...receipt, admittedSequences: [1, 1] },
      { ...receipt, admittedSequences: [0] }, { ...receipt, admittedSequences: [1001] }, { ...receipt, admittedSequences: [1.5] },
      { ...receipt, admittedSequences: ['1'] }, { ...receipt, admittedSequences: null }, { ...receipt, admittedSequences: Array(1001).fill(1) },
      { ...receipt, memoryCount: 5 }, { ...receipt, memoryCount: -1 }, { ...receipt, memoryCount: 1 },
      { ...receipt, conversationId: uuid(2) }, { ...receipt, taskId: uuid(900) }]) {
      const index = port.sent.length, request = client.sendConversation(conversation.id, 'Synthetic', [])
      const rejected = expect(request).rejects.toMatchObject({ code: 'OutcomeUnknown' }); port.reply(index, invalid); await rejected
    }
    const index = port.sent.length, request = client.sendConversation(conversation.id, 'Synthetic', [])
    const expired = expect(request).rejects.toMatchObject({ code: 'OutcomeUnknown' })
    await vi.advanceTimersByTimeAsync(15_000); await expired
    const count = port.sent.length; port.reply(index, receipt); expect(port.sent).toHaveLength(count)
    const old = client.sendConversation(conversation.id, 'Synthetic', []), oldIndex = port.sent.length - 1
    const replaced = expect(old).rejects.toMatchObject({ code: 'OutcomeUnknown' }); port.session(uuid(909)); await replaced
    port.reply(oldIndex, receipt); expect(port.sent).toHaveLength(count + 1)
  })
  it('invalidates pending requests on reload without replay', async () => {
    const { port, client } = connected(); const request = client.open('native.openMemoryBackup'); const checked = expect(request).rejects.toThrow('重新加载')
    port.session('22222222-2222-4222-8222-222222222222'); await checked; port.reply(0, { opened: true }); expect(port.sent).toHaveLength(1)
    const next = client.bootstrap(); port.reply(1); await expect(next).resolves.toEqual(status)
  })
})
