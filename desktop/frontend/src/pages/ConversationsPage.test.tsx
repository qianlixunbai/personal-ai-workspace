import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ConversationsPage } from './ConversationsPage'
import { WorkspaceClient } from '../bridge/client'
import type { ConversationStatus, TurnStatus } from '../bridge/conversations'
import { conversation, history, uuid } from '../test/conversationFixtures'
import { Port } from '../test/fixtures'
const clients: WorkspaceClient[] = []
const memory = { memoryId: uuid(900), revision: '9007199254740993', position: 0, title: '<img onerror=x> Synthetic Memory' }
class Service extends Port {
  total = 1
  turns = 1
  state: TurnStatus = 'SUCCEEDED'
  lifecycle: ConversationStatus = 'ACTIVE'
  title = conversation.title
  error: string | null = null
  holdGet = false
  choiceChanged = true
  postMessage(message: unknown) {
    super.postMessage(message)
    const index = this.sent.length - 1, request = this.sent[index]!, method = String(request.method)
    const payload = request.payload as Record<string, unknown>
    if (this.holdGet && method === 'conversations.get') return
    queueMicrotask(() => {
      let result: unknown
      const item = { ...conversation, title: this.title, status: this.lifecycle }
      if (method === 'conversations.list') {
        const page = Number(payload.page)
        result = { items: Array.from({ length: Math.min(10, Math.max(0, this.total - page * 10)) }, (_, i) => ({ ...item, id: uuid(page * 10 + i + 1), status: payload.status, title: `Synthetic conversation ${page * 10 + i + 1}` })), total: this.total, page, limit: 10 }
      } else if (method === 'conversations.get') result = { ...history(this.turns, Number(payload.page), this.state), conversation: { ...item, id: payload.conversationId } }
      else if (method === 'conversations.clearMemories') result = { cleared: true }
      else if (method === 'conversations.selectMemories') result = { changed: this.choiceChanged, selectedMemoryRefs: this.choiceChanged ? [memory] : [] }
      else if (method === 'conversations.send') {
        if (this.error) { this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: this.error, message: `Safe ${this.error}` } }); return }
        this.turns++; this.state = 'PENDING'; result = { accepted: true, conversationId: payload.conversationId, turnId: uuid(this.turns + 100) }
      } else if (method === 'conversations.create') { this.turns = 0; result = item }
      else if (method === 'conversations.rename') { this.title = String(payload.title); result = { ...item, title: this.title } }
      else if (method === 'conversations.archive' || method === 'conversations.unarchive') { this.lifecycle = method === 'conversations.archive' ? 'ARCHIVED' : 'ACTIVE'; result = { ...item, status: this.lifecycle } }
      else if (method === 'conversations.delete') { this.total = 0; result = { deleted: true } }
      else if (method === 'conversations.cancelPending') { this.state = 'CANCELLED'; result = { requested: true } }
      this.reply(index, result)
    })
  }
}
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(x => x.dispose()); vi.useRealTimers(); vi.restoreAllMocks() })
async function setup(config: Partial<Service> = {}) {
  const port = Object.assign(new Service(), config), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<ConversationsPage bridge={bridge} enabled visible />)
  await screen.findByLabelText('会话消息')
  return { port, bridge, input: screen.getByLabelText('会话消息') as HTMLTextAreaElement }
}
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
it('opens only list page zero and latest of 1000 turns, and retains only ten rendered turns while navigating history', async () => {
  const { port } = await setup({ total: 25, turns: 1000 })
  expect(port.sent.filter(x => x.method === 'conversations.get').map(x => x.payload)).toEqual([{ conversationId: conversation.id, page: 0 }, { conversationId: conversation.id, page: 99 }])
  expect(screen.getAllByRole('article')).toHaveLength(10)
  await click('Older'); expect(screen.getAllByRole('article')).toHaveLength(10)
  expect(port.sent.at(-1)?.payload).toEqual({ conversationId: conversation.id, page: 98 })
  await click('Newer'); expect(screen.getAllByRole('article')).toHaveLength(10)
  await click('Next'); expect(port.sent.filter(x => x.method === 'conversations.list').at(-1)?.payload).toEqual({ status: 'ACTIVE', page: 1 })
})
it('accepted send clears exact untrimmed draft and per-turn Memory; subsequent Turn has no Memory', async () => {
  const { port, input } = await setup(); await click('Use Memory…')
  fireEvent.change(input, { target: { value: '  Synthetic中文\nuser  ' } }); await click('Send')
  expect(port.sent.find(x => x.method === 'conversations.send')?.payload).toEqual({ conversationId: conversation.id, message: '  Synthetic中文\nuser  ', selectedMemoryRefs: [{ memoryId: memory.memoryId, revision: memory.revision, position: 0 }] })
  expect(input.value).toBe(''); expect(screen.getByText('No Memory')).toBeTruthy(); expect(screen.getByRole('button', { name: 'Send' }).hasAttribute('disabled')).toBe(true)
  port.state = 'SUCCEEDED'; await click('Refresh'); fireEvent.change(input, { target: { value: 'Synthetic next' } }); await click('Send')
  expect((port.sent.filter(x => x.method === 'conversations.send').at(-1)?.payload as { selectedMemoryRefs: unknown[] }).selectedMemoryRefs).toEqual([])
})
it.each(['OutcomeUnknown'])('%s preserves draft, clears unsafe selection, refreshes history and never resends', async error => {
  const { port, input } = await setup({ error }); await click('Use Memory…'); fireEvent.change(input, { target: { value: 'Synthetic draft' } }); await click('Send')
  expect(input.value).toBe('Synthetic draft'); expect(screen.getByText('No Memory')).toBeTruthy()
  expect(port.sent.filter(x => x.method === 'conversations.send')).toHaveLength(1)
  expect(screen.getByRole('alert').textContent).toContain(error)
  expect(port.sent.filter(x => x.method === 'conversations.get').length).toBeGreaterThan(1)
})
it('stale retains metadata, requires Review or Clear and native picker Cancel preserves selection', async () => {
  const { port, input } = await setup({ error: 'MemorySelectionStale' }); await click('Use Memory…')
  port.choiceChanged = false; await click('Review / Change Memory…'); expect(screen.getByText(memory.title)).toBeTruthy()
  fireEvent.change(input, { target: { value: 'Synthetic' } }); await click('Send'); expect(screen.getByText(/Needs review/)).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Send' }).hasAttribute('disabled')).toBe(true)
  await click('Clear Memory'); expect(screen.getByText('No Memory')).toBeTruthy()
})
it('switch discards draft/Memory and clears both host scopes without cancelling execution', async () => {
  const { port, input } = await setup({ total: 2 }); await click('Use Memory…'); fireEvent.change(input, { target: { value: 'Synthetic A draft' } })
  await click('Synthetic conversation 2 ACTIVE'); expect((screen.getByLabelText('会话消息') as HTMLTextAreaElement).value).toBe(''); expect(screen.getByText('No Memory')).toBeTruthy()
  expect(port.sent.some(x => x.method === 'conversations.cancelPending')).toBe(false)
  expect(port.sent.filter(x => x.method === 'conversations.clearMemories').slice(-2).map(x => x.payload)).toEqual([{ conversationId: uuid(1) }, { conversationId: uuid(2) }])
})
it('creates and manually renames, archive blocks send, unarchive keeps same identity and Delete needs explicit safe modal confirmation', async () => {
  const { port } = await setup(); await click('New Conversation'); await click('Rename')
  fireEvent.change(screen.getByLabelText(/会话标题/), { target: { value: 'Synthetic renamed' } }); await click('Save title'); expect(screen.getByRole('heading', { name: 'Synthetic renamed' })).toBeTruthy()
  await click('Archive'); expect((screen.getByLabelText('会话消息') as HTMLTextAreaElement).readOnly).toBe(true)
  expect(port.sent.some(x => x.method === 'conversations.cancelPending')).toBe(false); await click('Unarchive')
  await click('Delete'); expect(screen.getByRole('dialog')).toBeTruthy(); expect(document.activeElement?.textContent).toBe('Cancel')
  expect(port.sent.some(x => x.method === 'conversations.delete')).toBe(false)
  expect(screen.getByText('删除后无法从当前 Workspace 恢复。')).toBeTruthy(); await click('永久删除')
  expect(screen.queryByLabelText('会话消息')).toBeNull(); expect(screen.getByRole('button', { name: 'New Conversation' })).toBe(document.activeElement)
})
it('IME guards and input budgets preserve exact input without submission or truncation', async () => {
  const { port, input } = await setup(); fireEvent.compositionStart(input); fireEvent.change(input, { target: { value: '中文\nSynthetic' } })
  fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true, isComposing: true }); fireEvent.keyDown(input, { key: 'Enter' })
  fireEvent.compositionEnd(input); fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true, keyCode: 229 })
  expect(port.sent.some(x => x.method === 'conversations.send')).toBe(false)
  fireEvent.change(input, { target: { value: 'x'.repeat(3001) } }); expect(input.value.length).toBe(3001); expect(screen.getByRole('button', { name: 'Send' }).hasAttribute('disabled')).toBe(true)
  fireEvent.change(input, { target: { value: '中'.repeat(2000) } }); expect(screen.getByRole('button', { name: 'Send' }).hasAttribute('disabled')).toBe(true)
})
it('cancel carries only authorized Conversation/Turn identity and renders durable cancel; reload reconstructs without send replay', async () => {
  const { port } = await setup({ state: 'PENDING' }); await click('Cancel pending')
  expect(port.sent.find(x => x.method === 'conversations.cancelPending')?.payload).toEqual({ conversationId: conversation.id, turnId: uuid(101) })
  expect(screen.getByRole('heading', { name: 'Turn 1 · CANCELLED' })).toBeTruthy()
  await act(async () => { port.session(uuid(909)) }); await screen.findByLabelText('会话消息')
  expect(port.sent.some(x => x.method === 'conversations.send')).toBe(false); expect(location.hash).not.toContain(conversation.id)
})
it('polls latest durable page without overlapping requests and stops after terminal truth', async () => {
  const { port } = await setup({ state: 'PENDING' }); vi.useFakeTimers(); port.holdGet = true
  await act(async () => { await vi.advanceTimersByTimeAsync(550) }); const count = port.sent.filter(x => x.method === 'conversations.get').length
  await act(async () => { await vi.advanceTimersByTimeAsync(2000) }); expect(port.sent.filter(x => x.method === 'conversations.get')).toHaveLength(count)
  await act(async () => { port.reply(port.sent.length - 1, history(1, 0, 'SUCCEEDED')) }); await act(async () => { await vi.advanceTimersByTimeAsync(1100) })
  expect(port.sent.filter(x => x.method === 'conversations.get')).toHaveLength(count)
})
