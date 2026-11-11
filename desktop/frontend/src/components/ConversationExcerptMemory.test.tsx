import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { conversation, history as conversationHistory, uuid } from '../test/conversationFixtures'
import { MemoryService } from '../test/memoryFixtures'
import { ConversationExcerptMemory } from './ConversationExcerptMemory'
import { MemorySourceReview } from './MemorySourceReview'

class ExcerptService extends MemoryService {
  core = '需要核实😀 <script>这只是原文</script>。'
  source = '前文'.repeat(300) + this.core + ' tail'.repeat(600)
  hold = false; holdList = false; missing = false; wrongMessage = false
  known = new Set<string>()
  title = '项目\n讨论'; archived = false
  constructor() { super(); this.items = [] }
  override session(id?: string) { this.known.clear(); super.session(id) }
  detail(page = 1) {
    const detail = conversationHistory(11, page)
    detail.conversation = { ...conversation, title: this.title, status: this.archived ? 'ARCHIVED' : 'ACTIVE' }
    for (const turn of detail.turns) {
      turn.memoryReferences = []
      if (turn.assistantMessage) { turn.assistantMessage.content = this.source; if (this.wrongMessage) turn.assistantMessage.messageId = uuid(9999 + turn.sequence) }
    }
    return detail
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }
    if (!request.method.startsWith('conversations.')) { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
    if (request.method === 'conversations.get' && this.hold) return
    if (request.method === 'conversations.list' && this.holdList) return
    queueMicrotask(() => {
      if (request.method === 'conversations.list') {
        const item = this.detail().conversation
        const items = !this.missing && item.status === request.payload.status ? [item] : []
        for (const value of items) this.known.add(value.id)
        this.reply(index, { items, total: items.length, page: 0, limit: 10 })
      }
      else if (request.method === 'conversations.clearMemories') this.reply(index, { cleared: true })
      else if (request.method === 'conversations.get') {
        if (this.missing || !this.known.has(String(request.payload.conversationId))) {
          const pending = this.sent[index]!
          this.emit({ version: 1, sessionId: pending.sessionId, requestId: pending.requestId, ok: false, error: { code: 'ConversationNotFound', message: '合成消息不可用。' } })
        } else this.reply(index, this.detail(Number(request.payload.page)))
      }
    })
  }
  note(start = 600, end = 600 + this.core.length) {
    return `${this.source.slice(start, end)}\n\n对话出处：项目 讨论\n会话 ID：${conversation.id}\n轮次 ID：${uuid(111)}；第 11 轮\n消息 ID：${uuid(2023)}；角色：ASSISTANT\n文本偏移：${start}–${end}（不含结束位置）`
  }
}
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges(); clients.splice(0).forEach(client => client.dispose()); vi.restoreAllMocks(); history.replaceState(null, '', '#/assistant') })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function select(start: number, end: number) {
  await act(async () => {
    const node = screen.getByLabelText('可选取的对话原文').firstChild!, range = document.createRange()
    range.setStart(node, start); range.setEnd(node, end)
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
  })
}

it('extracts a long historical reply into a reviewed saved memory and reads its exact original without using a model', async () => {
  const port = new ExcerptService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/conversations'); render(<App bridge={bridge} />)
  await act(async () => port.session())
  const question = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(question, { target: { value: '保留未发送问题' } })
  const content = screen.getByLabelText('记忆内容') as HTMLTextAreaElement
  fireEvent.change(content, { target: { value: '原有记忆草稿' } })
  await act(async () => fireEvent.click(screen.getAllByRole('button', { name: '摘录并保留对话出处' })[1]!))
  const before = port.sent.length
  await click('选择整条消息')
  expect((screen.getByRole('button', { name: '核对原消息并送入记忆草稿' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.getByLabelText('带对话出处的草稿预览').textContent?.startsWith(port.source)).toBe(true)
  expect(port.sent).toHaveLength(before)
  await select(600, 600 + port.core.length)
  expect(screen.getByLabelText('带对话出处的草稿预览').textContent).toBe(port.note())
  await click('核对原消息并送入记忆草稿')
  await act(async () => fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '取消' })))
  expect(content.value).toBe('原有记忆草稿'); expect(question.value).toBe('保留未发送问题')
  await click('核对原消息并送入记忆草稿')
  await act(async () => fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '丢弃并继续' })))
  expect(location.hash).toBe('#/memory'); expect(content.value).toBe(port.note())
  expect(port.sent.filter(request => request.method === 'memory.create')).toHaveLength(0)
  fireEvent.change(screen.getByLabelText('记忆标题'), { target: { value: '带原消息位置的笔记' } })
  fireEvent.change(content, { target: { value: port.note() + '\n人工批注：稍后核实。' } })
  await click('保存')
  expect(port.sent.filter(request => request.method === 'memory.create')).toHaveLength(1)
  await click('新建'); await click(/带原消息位置的笔记 PROJECT_NOTE/)
  expect(content.value).toBe(port.note() + '\n人工批注：稍后核实。')
  const reads = port.sent.filter(request => request.method === 'conversations.get').length
  port.title = '后来改名'; port.archived = true; port.known.clear()
  await click('查看对话出处')
  expect(port.sent.filter(request => request.method === 'conversations.list').slice(-2).map(request => request.payload)).toEqual([
    { status: 'ACTIVE', page: 0 }, { status: 'ARCHIVED', page: 0 },
  ])
  expect(port.sent.filter(request => request.method === 'conversations.get')).toHaveLength(reads + 1)
  expect(port.sent.filter(request => request.method === 'conversations.get').at(-1)!.payload).toEqual({ conversationId: conversation.id, page: 1 })
  expect(screen.getByLabelText('对话所记范围原文').textContent).toBe(port.core)
  expect(screen.getByText(/与该消息、该范围的原文逐字一致/)).toBeTruthy()
  expect(screen.getByText(/会话名称与正文记录不同/)).toBeTruthy()
  fireEvent.click(screen.getByText('阅读原轮次上下文'))
  expect(within(screen.getByRole('region', { name: '对话出处回查' })).getByText('Synthetic user 11')).toBeTruthy()
  fireEvent.change(content, { target: { value: port.note().replace('需要核实', '已修改') } })
  await click('查看对话出处')
  expect(screen.getByText(/与所记消息范围不一致/)).toBeTruthy()
  expect(content.value).toBe(port.note().replace('需要核实', '已修改'))
  expect(question.value).toBe('保留未发送问题')
  expect(port.sent.some(request => /models\.|proposeMemory|assistant\.submit|conversations\.send|selectMemories/.test(String(request.method)))).toBe(false)
  expect(document.querySelector('script,img,iframe')).toBeNull()
})

it('blocks invalid excerpts and missing identities, and discards delayed reads after edits, disabled UI and session changes', async () => {
  const port = new ExcerptService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const source = port.detail(), turn = source.turns[0]!, message = turn.assistantMessage!, receive = vi.fn()
  const props = { bridge, conversation: source.conversation, turn, message, onDraft: receive }
  const view = render(<ConversationExcerptMemory {...props} enabled />)
  await click('摘录并保留对话出处')
  const emoji = port.source.indexOf('😀')
  await select(emoji, emoji + 1)
  expect((screen.getByRole('button', { name: '核对原消息并送入记忆草稿' }) as HTMLButtonElement).disabled).toBe(true)
  await select(600, 600 + port.core.length)
  port.hold = true; await click('核对原消息并送入记忆草稿')
  let pending = port.sent.length - 1
  view.rerender(<ConversationExcerptMemory {...props} enabled={false} />)
  await act(async () => port.reply(pending, port.detail()))
  expect(receive).not.toHaveBeenCalled(); expect(screen.queryByLabelText('带对话出处的草稿预览')).toBeNull()
  view.rerender(<ConversationExcerptMemory {...props} enabled />)
  await click('摘录并保留对话出处'); await select(600, 600 + port.core.length)
  port.hold = false; port.wrongMessage = true
  await click('核对原消息并送入记忆草稿')
  expect(screen.getByRole('alert').textContent).toContain('未改查其他消息')
  expect(receive).not.toHaveBeenCalled()
  view.unmount(); port.wrongMessage = false
  const content = port.note(), reader = render(<MemorySourceReview bridge={bridge} content={content} />)
  const before = port.sent.length
  reader.rerender(<MemorySourceReview bridge={bridge} content={content + content} />)
  expect(screen.getByText(/对话出处格式不完整/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: '查看对话出处' })).toBeNull(); expect(port.sent).toHaveLength(before)
  reader.rerender(<MemorySourceReview bridge={bridge} content={port.note(emoji, emoji + 1).replace('\ud83d', '改')} />)
  await click('查看对话出处')
  expect(screen.getByRole('alert').textContent).toContain('切断了字符')
  reader.rerender(<MemorySourceReview bridge={bridge} content={content} />)
  const detailReads = port.sent.filter(request => request.method === 'conversations.get').length
  port.missing = true; await click('查看对话出处')
  expect(screen.getByRole('alert').textContent).toContain('已删除或不可用')
  expect(port.sent.filter(request => request.method === 'conversations.get')).toHaveLength(detailReads)
  port.missing = false; port.holdList = true; await click('查看对话出处'); pending = port.sent.length - 1
  reader.rerender(<MemorySourceReview bridge={bridge} content={'编辑后' + content} />)
  await act(async () => port.reply(pending, { items: [port.detail().conversation], total: 1, page: 0, limit: 10 }))
  expect(port.sent.filter(request => request.method === 'conversations.get')).toHaveLength(detailReads)
  await click('查看对话出处'); pending = port.sent.length - 1
  await act(async () => { port.session(uuid(908)); port.reply(pending, { items: [port.detail().conversation], total: 1, page: 0, limit: 10 }) })
  expect(port.sent.filter(request => request.method === 'conversations.get')).toHaveLength(detailReads)
  port.holdList = false; reader.rerender(<MemorySourceReview bridge={bridge} content={content} />)
  port.missing = false; port.hold = true; await click('查看对话出处'); pending = port.sent.length - 1
  reader.rerender(<MemorySourceReview bridge={bridge} content={'编辑后' + content} />)
  await act(async () => port.reply(pending, port.detail()))
  expect(screen.queryByLabelText('对话所记范围原文')).toBeNull()
  await click('查看对话出处'); pending = port.sent.length - 1
  await act(async () => { port.session(uuid(909)); port.reply(pending, port.detail()) })
  expect(screen.queryByLabelText('对话所记范围原文')).toBeNull()
  const count = port.sent.length
  reader.rerender(<MemorySourceReview bridge={bridge} content={content} enabled={false} />)
  await click('查看对话出处'); expect(port.sent).toHaveLength(count)
  expect(port.sent.every(request => request.method === 'conversations.list' || request.method === 'conversations.get')).toBe(true)
})
