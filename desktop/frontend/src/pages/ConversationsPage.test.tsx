import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
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
  error: string | null = null
  holdGet = false
  holdSend = false
  holdMemory = false
  memoryError: string | null = null
  referencedMemory = { ...memoryItem(), id: uuid(5000), revision: '9223372036854775807' }
  admittedSequences = [1]
  choiceChanged = true
  postMessage(message: unknown) {
    super.postMessage(message)
    const index = this.sent.length - 1, request = this.sent[index]!, method = String(request.method)
    const payload = request.payload as Record<string, unknown>
    if (this.holdGet && method === 'conversations.get' || this.holdSend && method === 'conversations.send' || this.holdMemory && method === 'memory.get') return
    queueMicrotask(() => {
      let result: unknown
      const item = { ...conversation, title: this.title, status: this.lifecycle }
      if (method === 'conversations.list') {
        const page = Number(payload.page)
        result = { items: Array.from({ length: Math.min(10, Math.max(0, this.total - page * 10)) }, (_, i) => ({ ...item, id: uuid(page * 10 + i + 1), status: payload.status, title: `Synthetic conversation ${page * 10 + i + 1}` })), total: this.total, page, limit: 10 }
      } else if (method === 'conversations.get') result = { ...history(this.turns, Number(payload.page), this.state), conversation: { ...item, id: payload.conversationId } }
      else if (method === 'memory.get') {
        if (this.memoryError) { this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: this.memoryError, message: `Safe ${this.memoryError}` } }); return }
        result = this.referencedMemory
      }
      else if (method === 'conversations.clearMemories') result = { cleared: true }
      else if (method === 'conversations.selectMemories') result = { changed: this.choiceChanged, selectedMemoryRefs: this.choiceChanged ? [memory] : [] }
      else if (method === 'conversations.send') {
        if (this.error) { this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: this.error, message: `Safe ${this.error}` } }); return }
        this.turns++; this.state = 'PENDING'; result = { accepted: true, conversationId: payload.conversationId, turnId: uuid(this.turns + 100), admittedSequences: this.admittedSequences, memoryCount: (payload.selectedMemoryRefs as unknown[]).length }
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
it('inspects historical memory references on demand without replacing versions, selecting context or retaining late previews', async () => {
  const { port, bridge, view, input } = await setup({ total: 2, turns: 2 })
  fireEvent.change(input, { target: { value: 'Unsent synthetic draft' } }); await click('选择记忆…')
  const before = port.sent.length
  const original = { ...port.referencedMemory, title: '<img onerror=x> Reference title', content: 'Exact reference body\n<script>literal</script>' }
  port.referencedMemory = original
  const openTurn = async (sequence: number) => {
    await act(async () => { fireEvent.click(within(screen.getByRole('article', { name: `第 ${sequence} 轮 · 已完成` })).getByRole('button', { name: '查看记忆引用' })) })
  }
  await openTurn(1)
  expect(port.sent.filter(x => x.method === 'memory.get')).toHaveLength(0)
  expect(screen.getByRole('region', { name: '本轮记忆引用' }).textContent).toContain('没有保存历史正文')
  await click('查看记忆 1')
  const body = screen.getByLabelText('引用记忆正文')
  expect(body.textContent).toBe(original.content); expect(body.querySelector('script')).toBeNull()
  expect(screen.getByRole('heading', { name: original.title }).querySelector('img')).toBeNull()
  expect(screen.getByText('当前保存正文（版本与本轮引用一致）。')).toBeTruthy()
  expect(port.sent.at(-1)?.payload).toEqual({ memoryId: uuid(5000) })
  expect(input.value).toBe('Unsent synthetic draft'); expect(screen.getByText(memory.title)).toBeTruthy()

  port.referencedMemory = { ...original, id: uuid(5001), revision: '9223372036854775806', title: 'Different version title', content: 'Different version body' }
  await click('查看记忆 2')
  expect(screen.getByRole('alert').textContent).toContain('已变更或归档')
  expect(screen.queryByLabelText('引用记忆正文')).toBeNull(); expect(screen.queryByText('Different version title')).toBeNull()
  port.referencedMemory = { ...original, id: uuid(5002), status: 'ARCHIVED' }
  await click('查看记忆 3'); expect(screen.queryByLabelText('引用记忆正文')).toBeNull()
  port.memoryError = 'MemoryNotFound'; await click('查看记忆 4')
  expect(screen.getByRole('alert').textContent).toContain('已删除或不存在')
  port.memoryError = 'MemoryStorageUnavailable'; await click('查看记忆 1')
  expect(screen.getByRole('alert').textContent).toBe('Safe MemoryStorageUnavailable')
  expect(port.sent.filter(x => x.method === 'memory.get')).toHaveLength(5)
  port.memoryError = null; port.referencedMemory = { ...original, id: uuid(5555) }
  await click('查看记忆 2'); expect(screen.queryByLabelText('引用记忆正文')).toBeNull()

  port.referencedMemory = original
  await openTurn(2)
  expect(screen.getAllByRole('region', { name: '本轮记忆引用' })).toHaveLength(1)
  expect(screen.queryByRole('region', { name: '对话引用记忆只读预览' })).toBeNull()
  port.holdMemory = true; await click('查看记忆 1')
  const closedRequest = port.sent.length - 1
  await click('收起记忆引用')
  await act(async () => { port.reply(closedRequest, original) })
  expect(screen.queryByLabelText('引用记忆正文')).toBeNull()
  expect(port.sent.slice(before).every(x => x.method === 'memory.get')).toBe(true)

  port.holdMemory = false; await openTurn(1); await click('查看记忆 1')
  await click('刷新')
  expect(screen.queryByRole('region', { name: '本轮记忆引用' })).toBeNull()
  expect(input.value).toBe('Unsent synthetic draft'); expect(screen.getByText(memory.title)).toBeTruthy()

  await openTurn(1); port.holdMemory = true; await click('查看记忆 1')
  const switchedRequest = port.sent.length - 1
  await click('Synthetic conversation 2 2026/10/4')
  await act(async () => { port.reply(switchedRequest, original) })
  expect(screen.queryByLabelText('引用记忆正文')).toBeNull()

  await openTurn(1); await click('查看记忆 1')
  const hiddenRequest = port.sent.length - 1
  view.rerender(<ConversationsPage bridge={bridge} enabled visible={false} />)
  await act(async () => { port.reply(hiddenRequest, original) })
  view.rerender(<ConversationsPage bridge={bridge} enabled visible />)
  await screen.findByLabelText('会话消息')
  expect(screen.queryByRole('region', { name: '本轮记忆引用' })).toBeNull()

  port.holdMemory = false; await openTurn(1); await click('查看记忆 1')
  expect(screen.getByLabelText('引用记忆正文')).toBeTruthy()
  view.rerender(<ConversationsPage bridge={bridge} enabled={false} visible />)
  expect(screen.queryByRole('region', { name: '本轮记忆引用' })).toBeNull()
  view.rerender(<ConversationsPage bridge={bridge} enabled visible />)
  await screen.findByLabelText('会话消息')
  await openTurn(1); port.holdMemory = true; await click('查看记忆 1')
  const sessionRequest = port.sent.length - 1
  await act(async () => { port.session(uuid(909)) })
  await act(async () => { port.reply(sessionRequest, original) })
  await screen.findByLabelText('会话消息')
  expect(screen.queryByRole('region', { name: '本轮记忆引用' })).toBeNull()
  expect(port.sent.slice(before).every(x => ['memory.get', 'conversations.get', 'conversations.list', 'conversations.clearMemories'].includes(String(x.method)))).toBe(true)
})
it('jumps directly to a durable turn, returns to latest, preserves the draft and ignores a late jump after switching conversations', async () => {
  const { port, input } = await setup({ total: 2, turns: 1000 })
  fireEvent.change(input, { target: { value: 'Unsent synthetic draft' } })
  await click('选择记忆…')
  const before = port.sent.length
  const jumpInput = screen.getByLabelText('跳转到轮次') as HTMLInputElement
  fireEvent.change(jumpInput, { target: { value: '12' } })
  await act(async () => { fireEvent.submit(jumpInput.closest('form')!) })
  const turn = await screen.findByRole('article', { name: '第 12 轮 · 已完成' })
  expect(document.activeElement).toBe(turn)
  expect(turn.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  expect(port.sent.slice(before).map(x => ({ method: x.method, payload: x.payload }))).toEqual([
    { method: 'conversations.get', payload: { conversationId: conversation.id, page: 1 } },
  ])
  expect(screen.getAllByRole('article')).toHaveLength(10)
  expect(input.value).toBe('Unsent synthetic draft'); expect(screen.getByText(memory.title)).toBeTruthy()
  const afterJump = port.sent.length
  for (const value of ['0', '1001', '1.5', '1e2']) {
    fireEvent.change(jumpInput, { target: { value } })
    expect(screen.getByRole('button', { name: '跳转' }).hasAttribute('disabled')).toBe(true)
    fireEvent.submit(jumpInput.closest('form')!)
  }
  expect(port.sent).toHaveLength(afterJump)
  await click('回到最新')
  expect(document.activeElement).toBe(await screen.findByRole('article', { name: '第 1000 轮 · 已完成' }))
  expect(port.sent.slice(afterJump).map(x => x.payload)).toEqual([
    { conversationId: conversation.id, page: 0 }, { conversationId: conversation.id, page: 99 },
  ])
  expect(input.value).toBe('Unsent synthetic draft'); expect(screen.getByText(memory.title)).toBeTruthy()
  expect(port.sent.slice(before).every(x => x.method === 'conversations.get')).toBe(true)

  port.holdGet = true
  fireEvent.change(jumpInput, { target: { value: '12' } }); await click('跳转')
  const late = port.sent.length - 1
  const scrolls = vi.mocked(HTMLElement.prototype.scrollIntoView).mock.calls.length
  await click('Synthetic conversation 2 2026/10/4')
  port.holdGet = false
  await act(async () => { port.reply(late, history(1000, 1)) })
  await screen.findByLabelText('会话消息')
  expect((screen.getByLabelText('跳转到轮次') as HTMLInputElement).value).toBe('')
  expect(screen.queryByRole('article', { name: '第 12 轮 · 已完成' })).toBeNull()
  expect(vi.mocked(HTMLElement.prototype.scrollIntoView).mock.calls).toHaveLength(scrolls)
  expect(screen.queryByText('已定位到第 12 轮。')).toBeNull()
})
it('opens only list page zero and latest of 1000 turns, and retains only ten rendered turns while navigating history', async () => {
  const { port } = await setup({ total: 25, turns: 1000 })
  expect(port.sent.filter(x => x.method === 'conversations.get').map(x => x.payload)).toEqual([{ conversationId: conversation.id, page: 0 }, { conversationId: conversation.id, page: 99 }])
  expect(screen.getAllByRole('article')).toHaveLength(10)
  await click('更早'); expect(screen.getAllByRole('article')).toHaveLength(10)
  expect(port.sent.at(-1)?.payload).toEqual({ conversationId: conversation.id, page: 98 })
  await click('更新'); expect(screen.getAllByRole('article')).toHaveLength(10)
  await click('下一页'); expect(port.sent.filter(x => x.method === 'conversations.list').at(-1)?.payload).toEqual({ status: 'ACTIVE', page: 1 })
})
it('accepted send clears exact untrimmed draft and per-turn Memory; subsequent Turn has no Memory', async () => {
  const { port, input } = await setup({ turns: 3, admittedSequences: [1, 3] }); await click('选择记忆…')
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
  expect(screen.getAllByText('当时接纳的上下文范围未记录。')).toHaveLength(3)
  fireEvent.change(input, { target: { value: '  Synthetic中文\nuser  ' } }); await click('发送')
  expect(port.sent.find(x => x.method === 'conversations.send')?.payload).toEqual({ conversationId: conversation.id, message: '  Synthetic中文\nuser  ', selectedMemoryRefs: [{ memoryId: memory.memoryId, revision: memory.revision, position: 0 }] })
  expect(input.value).toBe(''); expect(screen.getByText('仅使用你本次选择的记忆')).toBeTruthy(); expect(screen.getByRole('button', { name: '发送' }).hasAttribute('disabled')).toBe(true)
  const receipt = screen.getByRole('heading', { name: '第 4 轮 · 生成中' }).closest('article')!
  expect(within(receipt).getByText('本次接纳上下文：历史 2 轮 · 显式 Memory 1 条。')).toBeTruthy()
  expect(within(receipt).getByText('第 1、3 轮（每轮包含用户消息与助手回复）。')).toBeTruthy()
  port.state = 'FAILED'; await click('刷新')
  expect(screen.getByText('本次接纳上下文：历史 2 轮 · 显式 Memory 1 条。')).toBeTruthy()
  expect(screen.getByRole('heading', { name: '第 4 轮 · 失败' })).toBeTruthy()
  port.admittedSequences = []; fireEvent.change(input, { target: { value: 'Synthetic next' } }); await click('发送')
  expect((port.sent.filter(x => x.method === 'conversations.send').at(-1)?.payload as { selectedMemoryRefs: unknown[] }).selectedMemoryRefs).toEqual([])
  expect(screen.queryByText('本次接纳上下文：历史 2 轮 · 显式 Memory 1 条。')).toBeNull()
  expect(screen.getByText('本次接纳上下文：历史 0 轮 · 显式 Memory 0 条。')).toBeTruthy()
  expect(screen.getByText('未纳入历史轮次。')).toBeTruthy()
  port.state = 'SUCCEEDED'; await click('刷新'); port.error = 'OutcomeUnknown'
  fireEvent.change(input, { target: { value: 'Synthetic uncertain' } }); await click('发送')
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
})
it('explicit continuous memory reuses exact references, preserves picker cancel and returns to one-turn use on opt-out', async () => {
  const { port, input } = await setup(); await click('选择记忆…')
  const option = () => screen.getByRole('checkbox', { name: '在当前会话连续使用这组记忆' }) as HTMLInputElement
  expect(option().checked).toBe(false)
  fireEvent.click(option()); expect(option().checked).toBe(true)
  port.choiceChanged = false; await click('更换记忆…'); expect(option().checked).toBe(true)
  const refs = [{ memoryId: memory.memoryId, revision: memory.revision, position: 0 }]
  for (const message of ['Synthetic first', 'Synthetic follow-up']) {
    fireEvent.change(input, { target: { value: message } }); await click('发送')
    expect(port.sent.filter(x => x.method === 'conversations.send').at(-1)?.payload).toEqual({ conversationId: conversation.id, message, selectedMemoryRefs: refs, retainMemories: true })
    expect(input.value).toBe(''); expect(screen.getByText('连续使用 1 条')).toBeTruthy(); expect(option().checked).toBe(true)
    port.state = 'SUCCEEDED'; await click('刷新')
  }
  expect(port.sent.filter(x => x.method === 'conversations.selectMemories')).toHaveLength(2)
  expect(port.sent.filter(x => x.method === 'conversations.send')).toHaveLength(2)
  fireEvent.click(option()); expect(screen.getByText(/当前选择仅用于下一次发送/)).toBeTruthy()
  fireEvent.change(input, { target: { value: 'Synthetic last selected turn' } }); await click('发送')
  expect(port.sent.filter(x => x.method === 'conversations.send').at(-1)?.payload).toEqual({ conversationId: conversation.id, message: 'Synthetic last selected turn', selectedMemoryRefs: refs })
  expect(screen.queryByRole('checkbox')).toBeNull()
  port.state = 'SUCCEEDED'; await click('刷新')
  fireEvent.change(input, { target: { value: 'Synthetic without memory' } }); await click('发送')
  expect(port.sent.filter(x => x.method === 'conversations.send').at(-1)?.payload).toEqual({ conversationId: conversation.id, message: 'Synthetic without memory', selectedMemoryRefs: [] })
})
it('continuous memory stops on changed selection, stale, unknown, switch, session reset and navigation during admission', async () => {
  const { port, bridge, view, input } = await setup({ total: 2 })
  const option = () => screen.getByRole('checkbox', { name: '在当前会话连续使用这组记忆' }) as HTMLInputElement
  const selectAndRetain = async () => { await click('选择记忆…'); fireEvent.click(option()) }
  await selectAndRetain(); await click('更换记忆…'); expect(option().checked).toBe(false)
  fireEvent.click(option()); port.error = 'MemorySelectionStale'
  fireEvent.change(input, { target: { value: 'Synthetic draft' } }); await click('发送')
  expect(option().checked).toBe(false); expect(option().disabled).toBe(true); expect(input.value).toBe('Synthetic draft')
  port.choiceChanged = false; await click('更换记忆…'); expect(option().disabled).toBe(true)
  await click('清除记忆'); expect(screen.queryByRole('checkbox')).toBeNull()
  port.choiceChanged = true; await selectAndRetain(); port.error = 'OutcomeUnknown'; await click('发送')
  expect(screen.queryByRole('checkbox')).toBeNull(); expect(input.value).toBe('Synthetic draft')
  expect(port.sent.filter(x => x.method === 'conversations.send')).toHaveLength(2)
  expect(port.sent.some(x => x.method === 'conversations.clearMemories')).toBe(true)
  port.error = null; await selectAndRetain(); await click('Synthetic conversation 2 2026/10/4')
  expect(screen.queryByRole('checkbox')).toBeNull()
  await selectAndRetain(); await act(async () => { port.session(uuid(909)) }); await screen.findByLabelText('会话消息')
  expect(screen.queryByRole('checkbox')).toBeNull()
  await selectAndRetain(); port.holdSend = true
  fireEvent.change(screen.getByLabelText('会话消息'), { target: { value: 'Synthetic delayed' } }); await click('发送')
  const index = port.sent.map(x => x.method).lastIndexOf('conversations.send')
  const clearCount = port.sent.filter(x => x.method === 'conversations.clearMemories').length
  view.rerender(<ConversationsPage bridge={bridge} enabled visible={false} />)
  await act(async () => {})
  expect(port.sent.filter(x => x.method === 'conversations.clearMemories')).toHaveLength(clearCount + 1)
  view.rerender(<ConversationsPage bridge={bridge} enabled visible />); await screen.findByLabelText('会话消息')
  await act(async () => { port.reply(index, { accepted: true, conversationId: conversation.id, turnId: uuid(103), admittedSequences: [1], memoryCount: 1 }) })
  expect(screen.queryByRole('checkbox')).toBeNull(); expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
  expect(port.sent.filter(x => x.method === 'conversations.send')).toHaveLength(3)
  expect(port.sent.some(x => ['memory.create', 'memory.update', 'conversations.cancelPending'].includes(String(x.method)))).toBe(false)
})
it.each(['OutcomeUnknown'])('%s preserves draft, clears unsafe selection, refreshes history and never resends', async error => {
  const { port, input } = await setup({ error }); await click('选择记忆…'); fireEvent.change(input, { target: { value: 'Synthetic draft' } }); await click('发送')
  expect(input.value).toBe('Synthetic draft'); expect(screen.getByText('仅使用你本次选择的记忆')).toBeTruthy()
  expect(port.sent.filter(x => x.method === 'conversations.send')).toHaveLength(1)
  expect(screen.getByRole('alert').textContent).toContain(error)
  expect(port.sent.filter(x => x.method === 'conversations.get').length).toBeGreaterThan(1)
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
})
it('clears receipts on conversation switch, reload and leaving the page, and isolates late admissions', async () => {
  const { port, bridge, view, input } = await setup({ total: 2 })
  fireEvent.change(input, { target: { value: 'Synthetic send' } }); await click('发送')
  expect(screen.getByText(/本次接纳上下文/)).toBeTruthy()
  await click('Synthetic conversation 2 2026/10/4')
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
  await click('Synthetic conversation 1 2026/10/4')
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
  port.state = 'SUCCEEDED'; await click('刷新'); port.holdSend = true
  fireEvent.change(screen.getByLabelText('会话消息'), { target: { value: 'Synthetic delayed' } }); await click('发送')
  const index = port.sent.map(x => x.method).lastIndexOf('conversations.send')
  // A navigation invalidates the view generation while the same bridge session remains live.
  view.rerender(<ConversationsPage bridge={bridge} enabled visible={false} />)
  view.rerender(<ConversationsPage bridge={bridge} enabled visible />); await screen.findByLabelText('会话消息')
  await act(async () => { port.reply(index, { accepted: true, conversationId: conversation.id, turnId: uuid(103), admittedSequences: [1], memoryCount: 0 }) })
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
  port.holdSend = false
  fireEvent.change(screen.getByLabelText('会话消息'), { target: { value: 'Synthetic current' } }); await click('发送')
  expect(screen.getByText(/本次接纳上下文/)).toBeTruthy()
  await act(async () => { port.session(uuid(909)) }); await screen.findByLabelText('会话消息')
  expect(screen.queryByText(/本次接纳上下文/)).toBeNull()
  expect(port.sent.filter(x => x.method === 'conversations.send')).toHaveLength(3)
})
it('stale retains metadata, requires Review or Clear and native picker Cancel preserves selection', async () => {
  const { port, input } = await setup({ error: 'MemorySelectionStale' }); await click('选择记忆…')
  port.choiceChanged = false; await click('更换记忆…'); expect(screen.getByText(memory.title)).toBeTruthy()
  fireEvent.change(input, { target: { value: 'Synthetic' } }); await click('发送'); expect(screen.getByText(/记忆已变更/)).toBeTruthy()
  expect(screen.getByRole('button', { name: '发送' }).hasAttribute('disabled')).toBe(true)
  await click('清除记忆'); expect(screen.getByText('仅使用你本次选择的记忆')).toBeTruthy()
})
it('switch discards draft/Memory and clears both host scopes without cancelling execution', async () => {
  const { port, input } = await setup({ total: 2 }); await click('选择记忆…'); fireEvent.change(input, { target: { value: 'Synthetic A draft' } })
  await click('Synthetic conversation 2 2026/10/4'); expect((screen.getByLabelText('会话消息') as HTMLTextAreaElement).value).toBe(''); expect(screen.getByText('仅使用你本次选择的记忆')).toBeTruthy()
  expect(port.sent.some(x => x.method === 'conversations.cancelPending')).toBe(false)
  expect(port.sent.filter(x => x.method === 'conversations.clearMemories').slice(-2).map(x => x.payload)).toEqual([{ conversationId: uuid(1) }, { conversationId: uuid(2) }])
})
it('creates and manually renames, archive blocks send, unarchive keeps same identity and Delete needs explicit safe modal confirmation', async () => {
  const { port } = await setup(); await click('新建对话'); await click('重命名')
  fireEvent.change(screen.getByLabelText(/会话标题/), { target: { value: 'Synthetic renamed' } }); await click('保存标题'); expect(screen.getByRole('heading', { name: 'Synthetic renamed' })).toBeTruthy()
  await click('归档'); expect((screen.getByLabelText('会话消息') as HTMLTextAreaElement).readOnly).toBe(true)
  expect(port.sent.some(x => x.method === 'conversations.cancelPending')).toBe(false); await click('恢复')
  await click('删除'); expect(screen.getByRole('dialog')).toBeTruthy(); expect(document.activeElement?.textContent).toBe('取消')
  expect(port.sent.some(x => x.method === 'conversations.delete')).toBe(false)
  expect(screen.getByText('删除后无法从当前 Workspace 恢复。')).toBeTruthy(); await click('永久删除')
  expect(screen.queryByLabelText('会话消息')).toBeNull(); expect(screen.getByRole('button', { name: '新建对话' })).toBe(document.activeElement)
})
it('IME guards and input budgets preserve exact input without submission or truncation', async () => {
  const { port, input } = await setup(); fireEvent.compositionStart(input); fireEvent.change(input, { target: { value: '中文\nSynthetic' } })
  fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true, isComposing: true }); fireEvent.keyDown(input, { key: 'Enter' })
  fireEvent.compositionEnd(input); fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true, keyCode: 229 })
  expect(port.sent.some(x => x.method === 'conversations.send')).toBe(false)
  fireEvent.change(input, { target: { value: 'x'.repeat(3001) } }); expect(input.value.length).toBe(3001); expect(screen.getByRole('button', { name: '发送' }).hasAttribute('disabled')).toBe(true)
  fireEvent.change(input, { target: { value: '中'.repeat(2000) } }); expect(screen.getByRole('button', { name: '发送' }).hasAttribute('disabled')).toBe(true)
})
it('cancel carries only authorized Conversation/Turn identity and renders durable cancel; reload reconstructs without send replay', async () => {
  const { port } = await setup({ state: 'PENDING' }); await click('取消生成')
  expect(port.sent.find(x => x.method === 'conversations.cancelPending')?.payload).toEqual({ conversationId: conversation.id, turnId: uuid(101) })
  expect(screen.getByRole('heading', { name: '第 1 轮 · 已取消' })).toBeTruthy()
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
