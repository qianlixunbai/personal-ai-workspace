import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ConversationsPage } from './ConversationsPage'
import { WorkspaceClient } from '../bridge/client'
import type { ConversationStatus, TurnStatus } from '../bridge/conversations'
import { conversation, history, uuid } from '../test/conversationFixtures'
import { memoryItem } from '../test/memoryFixtures'
import { Port } from '../test/fixtures'
const clients: WorkspaceClient[] = []
const memory = { memoryId: uuid(900), revision: '9007199254740993', position: 0, title: '<img onerror=x> Synthetic Memory' }
class Service extends Port {
  total = 1
  turns = 1
  state: TurnStatus = 'SUCCEEDED'
  lifecycle: ConversationStatus = 'ACTIVE'
  title = conversation.title
  answerText: string | null = null
  exportOutcome: 'SAVED' | 'CANCELLED' = 'SAVED'
  error: string | null = null
  getError: string | null = null
  createError: string | null = null
  holdGet = false
  holdSend = false
  holdMemory = false
  holdSearch = false
  searchId = conversation.id
  searchSequence = 11
  searchScopeMismatch = false
  memoryError: string | null = null
  referencedMemory = { ...memoryItem(), id: uuid(5000), revision: '9223372036854775807' }
  admittedSequences = [1]
  choiceChanged = true
  postMessage(message: unknown) {
    super.postMessage(message)
    const index = this.sent.length - 1, request = this.sent[index]!, method = String(request.method)
    const payload = request.payload as Record<string, unknown>
    if (this.holdGet && method === 'conversations.get' || this.holdSend && method === 'conversations.send' || this.holdMemory && method === 'memory.get' || this.holdSearch && method === 'conversations.search') return
    queueMicrotask(() => {
      let result: unknown
      const item = { ...conversation, title: this.title, status: this.lifecycle }
      if (method === 'conversations.search') {
        const query = String(payload.query)
        result = { query, scope: this.searchScopeMismatch ? 'ARCHIVED' : payload.scope, page: payload.page, limit: 10, hasMore: false,
          items: [{ conversation: { ...item, id: this.searchId }, turnId: uuid(100 + this.searchSequence), sequence: this.searchSequence,
            messageId: uuid(2000 + this.searchSequence * 2), role: 'USER', snippet: `前${query}后<script>字面文本</script>`, matchStart: 1, matchEnd: 1 + query.length, before: false, after: false }] }
      } else if (method === 'conversations.list') {
        const page = Number(payload.page)
        result = { items: Array.from({ length: Math.min(10, Math.max(0, this.total - page * 10)) }, (_, i) => ({ ...item, id: uuid(page * 10 + i + 1), status: payload.status, title: `Synthetic conversation ${page * 10 + i + 1}` })), total: this.total, page, limit: 10 }
      } else if (method === 'conversations.get') {
        if (this.getError) { this.reply(index, undefined, { ok: false, error: { code: this.getError, message: `Safe ${this.getError}` } }); return }
        const detail = history(this.turns, Number(payload.page), this.state)
        if (this.answerText) for (const turn of detail.turns) if (turn.assistantMessage) turn.assistantMessage.content = this.answerText
        result = { ...detail, conversation: { ...item, id: payload.conversationId } }
      }
      else if (method === 'memory.get') {
        if (this.memoryError) { this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: this.memoryError, message: `Safe ${this.memoryError}` } }); return }
        result = this.referencedMemory
      }
      else if (method === 'conversations.export') result = { outcome: this.exportOutcome }
      else if (method === 'conversations.clearMemories') result = { cleared: true }
      else if (method === 'conversations.selectMemories') result = { changed: this.choiceChanged, selectedMemoryRefs: this.choiceChanged ? [memory] : [] }
      else if (method === 'conversations.send') {
        if (this.error) { this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: this.error, message: `Safe ${this.error}` } }); return }
        this.turns++; this.state = 'PENDING'; result = { accepted: true, conversationId: payload.conversationId, turnId: uuid(this.turns + 100), admittedSequences: this.admittedSequences, memoryCount: (payload.selectedMemoryRefs as unknown[]).length }
      } else if (method === 'conversations.create') {
        if (this.createError) { this.reply(index, undefined, { ok: false, error: { code: this.createError, message: `Safe ${this.createError}` } }); return }
        this.turns = 0; result = { ...item, id: uuid(++this.total) }
      }
      else if (method === 'conversations.rename') { this.title = String(payload.title); result = { ...item, id: payload.conversationId, title: this.title } }
      else if (method === 'conversations.archive' || method === 'conversations.unarchive') { this.lifecycle = method === 'conversations.archive' ? 'ARCHIVED' : 'ACTIVE'; result = { ...item, id: payload.conversationId, status: this.lifecycle } }
      else if (method === 'conversations.delete') { this.total = 0; result = { deleted: true } }
      else if (method === 'conversations.cancelPending') { this.state = 'CANCELLED'; result = { requested: true } }
      this.reply(index, result)
    })
  }
}
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(x => x.dispose()); vi.useRealTimers(); vi.restoreAllMocks() })
async function setup(config: Partial<Service> = {}) {
  const port = Object.assign(new Service(), config), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<ConversationsPage bridge={bridge} enabled visible />)
  await screen.findByLabelText('会话消息')
  return { port, bridge, view, input: screen.getByLabelText('会话消息') as HTMLTextAreaElement }
}
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
it('cancel carries only authorized Conversation/Turn identity and renders durable cancel; reload reconstructs without send replay', async () => {
  const { port } = await setup({ state: 'PENDING' }); await click('取消生成')
  expect(port.sent.find(x => x.method === 'conversations.cancelPending')?.payload).toEqual({ conversationId: conversation.id, turnId: uuid(101) })
  expect(screen.getByRole('heading', { name: '第 1 轮 · 已取消' })).toBeTruthy()
  await act(async () => { port.session(uuid(909)) }); await screen.findByLabelText('会话消息')
  expect(port.sent.some(x => x.method === 'conversations.send')).toBe(false); expect(location.hash).not.toContain(conversation.id)
})
