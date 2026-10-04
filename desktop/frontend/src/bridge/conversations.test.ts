import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from './client'
import { conversationMethods } from './contracts'
import { conversationResponseBytes, isConversation, isConversationDetail, isConversationList } from './conversations'
import { conversation, history, uuid } from '../test/conversationFixtures'
import { Port } from '../test/fixtures'
const clients: WorkspaceClient[] = []
function connected() { const port = new Port(); const client = new WorkspaceClient(port); clients.push(client); port.session(); return { port, client } }
afterEach(() => { clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })

it('adds exactly eleven explicit Conversation methods', () => { expect(conversationMethods).toHaveLength(11); expect(conversationMethods.every(x => x.startsWith('conversations.'))).toBe(true) })
it.each(['PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'] as const)('validates %s with exact status/message invariants and historical decimal references', status => {
  const valid = history(1, 0, status); expect(isConversationDetail(valid)).toBe(true)
  expect(isConversationDetail({ ...valid, turns: [{ ...valid.turns[0], taskId: uuid(999) }] })).toBe(false)
  expect(isConversationDetail({ ...valid, turns: [{ ...valid.turns[0], assistantMessage: status === 'SUCCEEDED' ? null : history().turns[0]!.assistantMessage }] })).toBe(false)
})
it('rejects invalid identity, Unicode, enums, timestamps, pages, count, ordering, roles and duplicate refs', () => {
  for (const changes of [{ id: uuid(1).toUpperCase().replace('4111', 'FFFF') }, { title: 'x'.repeat(161) }, { title: '\uD800' }, { status: 'OTHER' }, { updatedAt: 'secret/path' }]) expect(isConversation({ ...conversation, ...changes })).toBe(false)
  const valid = history(2), first = valid.turns[0]!
  for (const changes of [{ page: 100 }, { limit: 100 }, { totalTurns: 1001 }, { taskId: uuid(9) }]) expect(isConversationDetail({ ...valid, ...changes })).toBe(false)
  for (const changes of [{ sequence: 0 }, { status: 'OTHER' }, { canCancel: true }, { failureCode: 'RAW_STACK' },
    { userMessage: { ...first.userMessage, role: 'SYSTEM' } }, { userMessage: { ...first.userMessage, content: '中'.repeat(8192) } },
    { memoryReferences: [{ memoryId: uuid(4), revision: '9223372036854775808', position: 0 }] },
    { memoryReferences: [{ memoryId: uuid(4), revision: '1', position: 1 }] }]) expect(isConversationDetail({ ...valid, turns: [{ ...first, ...changes }, valid.turns[1]] })).toBe(false)
  expect(isConversationDetail({ ...valid, turns: [first, first] })).toBe(false)
})
it('list is paged, uniquely identified and ordered with strict fields', () => {
  const older = { ...conversation, id: uuid(2), updatedAt: conversation.createdAt }
  const valid = { items: [conversation, older], total: 2, page: 0, limit: 10 }
  expect(isConversationList(valid)).toBe(true)
  expect(isConversationList({ ...valid, items: [older, conversation] })).toBe(false)
  expect(isConversationList({ ...valid, items: [conversation, conversation] })).toBe(false)
  expect(isConversationList({ ...valid, total: 3 })).toBe(false)
})
it('rejects normalized invalid calendar timestamps and accepts legal UTC precision', () => {
  for (const createdAt of ['2026-02-30T00:00:00Z', '2026-10-04T24:00:00Z', '0000-10-04T00:00:00Z', '2026-10-04T00:00:00+08:00'])
    expect(isConversation({ ...conversation, createdAt })).toBe(false)
  expect(isConversation({ ...conversation, createdAt: '2026-10-04T00:00:00.1234567+00:00' })).toBe(true)
})
it('large legal ten-turn page survives JSON and typed bridge without truncation, while ordinary response budgets stay small', async () => {
  const { port, client } = connected(); const value = history(10, 0, 'SUCCEEDED', true)
  const request = client.getConversation(conversation.id, 0)
  const envelope = { version: 1, sessionId: port.sent[0]!.sessionId, requestId: port.sent[0]!.requestId, ok: true, result: value }
  const encoded = JSON.stringify(envelope)
  expect(new TextEncoder().encode(encoded).length).toBeGreaterThan(983040)
  expect(new TextEncoder().encode(encoded).length).toBeLessThan(conversationResponseBytes)
  port.emit(JSON.parse(encoded)); expect((await request).turns).toEqual(value.turns)
})
it('correlates Conversation identity/page/status and rejects raw task field', async () => {
  const { port, client } = connected()
  const call = client.getConversation(conversation.id, 0); const checked = expect(call).rejects.toMatchObject({ code: 'InvalidResponse' })
  port.reply(0, { ...history(), conversation: { ...conversation, id: uuid(2) } }); await checked
  const list = client.listConversations('ARCHIVED', 0); const rejected = expect(list).rejects.toMatchObject({ code: 'InvalidResponse' }); port.reply(1, { items: [conversation], total: 1, page: 0, limit: 10 }); await rejected
})
it('reload or lost send response produces unknown outcome with no automatic replay and stale replies ignored', async () => {
  const { port, client } = connected(); const send = client.sendConversation(conversation.id, 'Synthetic draft', [])
  const checked = expect(send).rejects.toMatchObject({ code: 'OutcomeUnknown' }); port.session(uuid(9)); await checked
  port.reply(0, { accepted: true, conversationId: conversation.id, turnId: uuid(99) }); expect(port.sent).toHaveLength(1)
})
