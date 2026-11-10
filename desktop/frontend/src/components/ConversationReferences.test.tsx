import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { MemoryService, memoryItem } from '../test/memoryFixtures'
import { conversation, history as conversationHistory } from '../test/conversationFixtures'
import type { ConversationSearchHit } from '../bridge/conversations'

class ContextService extends KnowledgeService {
  memory = new MemoryService()
  source = '# 合成资料\n预算😀<script>仅作文本</script>\n后文。'
  holdDetail = false; holdSearch = false; missingRevision = false; missingMessage = false
  constructor() {
    super()
    this.memory.items = Array.from({ length: 22 }, (_, n) => ({ ...memoryItem(n + 1), title: `预算记忆 ${n + 1}`, content: `预算正文 ${n + 1}`, status: n === 21 ? 'ARCHIVED' as const : 'ACTIVE' as const }))
    this.memory.sent = this.sent; this.memory.listener = event => this.emit(event.data)
  }
  override hit() { return { ...super.hit(), startOffset: this.source.indexOf('预算'), endOffset: this.source.length, startLine: 2, endLine: 3, snippet: this.source, highlightRanges: [{ start: this.source.indexOf('预算'), end: this.source.indexOf('预算') + 2 }] } }
  override job() { return { ...super.job(), sourceRevision: this.document.currentReadyRevision } }
  override detail() { return { ...super.detail(), revisions: (this.missingRevision ? ['2'] : ['1', '2']).map(sourceRevision => ({ sourceRevision, sourceType: 'MARKDOWN' as const, byteLength: 200 })) } }
  conversationPage(page: number) {
    const detail = conversationHistory(11, page)
    detail.turns.forEach(turn => { turn.userMessage.content = this.missingMessage ? '消息已不同' : `预算 user ${turn.sequence}<script>字面文本</script>` })
    return detail
  }
  hits(page: number): ConversationSearchHit[] {
    return this.conversationPage(page).turns.map(turn => ({ conversation, turnId: turn.turnId, sequence: turn.sequence, messageId: turn.userMessage.messageId,
      role: 'USER', snippet: turn.userMessage.content, matchStart: 0, matchEnd: 2, before: false, after: false }))
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }, { method, payload } = request
    if (method.startsWith('memory.') || method.startsWith('shell.')) { this.memory.postMessage(value); return }
    if (method.startsWith('conversations.')) {
      this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
      if (method === 'conversations.list') this.reply(index, { items: [conversation], total: 1, page: 0, limit: 10 })
      if (method === 'conversations.search') this.reply(index, { query: payload.query, scope: payload.scope, page: payload.page, limit: 10,
        items: payload.query === '预算' && payload.scope !== 'ARCHIVED' ? this.hits(Number(payload.page)) : [], hasMore: payload.query === '预算' && payload.scope !== 'ARCHIVED' && payload.page === 0 })
      if (method === 'conversations.get') this.reply(index, this.conversationPage(Number(payload.page)))
      if (method === 'conversations.clearMemories') this.reply(index, { cleared: true })
      return
    }
    if (method === 'knowledge.get' && this.holdDetail || method === 'knowledge.search' && this.holdSearch) { this.sent.push(value as Record<string, unknown>); return }
    if (method === 'knowledge.preview' || method === 'knowledge.previewBefore') {
      this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
      const before = method === 'knowledge.previewBefore', requested = Number(payload.offset)
      const offset = before ? 0 : requested, end = before ? requested : this.source.length
      this.reply(index, { ...super.preview(), sourceRevision: payload.sourceRevision, offset, text: this.source.slice(offset, end), nextOffset: end < this.source.length ? end : null,
        locators: [{ ...super.preview().locators[0]!, endOffset: this.source.length, endLine: 3 }] }); return
    }
    super.postMessage(value)
  }
}
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string | RegExp, root?: HTMLElement) => { await act(async () => fireEvent.click((root ? within(root) : screen).getByRole('button', { name }))) }
const basket = () => screen.getByRole('region', { name: '待插入的会话参考' })
const open = async () => { await click('准备会话参考…'); return screen.getByRole('dialog', { name: '准备会话参考' }) }
const search = async (dialog: HTMLElement) => {
  fireEvent.change(within(dialog).getByLabelText('工作区关键词'), { target: { value: '预算' } }); await click('查找', dialog)
}
const addMemory = async (dialog: HTMLElement) => {
  await search(dialog); await click(/^预算记忆 1\s*项目笔记/, dialog); await click('加入会话参考', dialog)
}
async function addSource(dialog: HTMLElement, start = 0, end = 4) {
  await click(/fixture.md\s*源版本 1/, dialog)
  const pre = within(dialog).getByRole('region', { name: '纯文本预览' }).querySelector('pre')!
  const point = (offset: number): [Node, number] => {
    const walker = document.createTreeWalker(pre, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (offset <= node.textContent!.length) return [node, offset]
      offset -= node.textContent!.length
    }
    throw new Error('Invalid synthetic selection')
  }
  await act(async () => {
    const range = document.createRange(); range.setStart(...point(start)); range.setEnd(...point(end))
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
  })
  await click('将选中原文加入会话参考', dialog)
}
async function mount() {
  const port = new ContextService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/conversations'); render(<App bridge={bridge} />); await act(async () => port.session())
  const draft = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '我的未发送问题' } })
  return { port, draft }
}
function readOnly(port: ContextService, from: number) {
  expect(port.sent.slice(from).every(request => ['memory.list', 'memory.get', 'conversations.search', 'conversations.get', 'knowledge.search', 'knowledge.get', 'knowledge.preview', 'knowledge.previewBefore'].includes(String(request.method)))).toBe(true)
}

it('prepares explicit source copies, reviews/removes them, and inserts the exact preview into the original conversation draft without sending', async () => {
  const { port, draft } = await mount(), from = port.sent.length
  let dialog = await open(); await addMemory(dialog)
  await click('加入会话参考', dialog)
  expect(within(basket()).getAllByRole('button', { name: '移除参考' })).toHaveLength(1)
  expect(draft.value).toBe('我的未发送问题')
  port.document.currentReadyRevision = '2'
  await addSource(dialog)
  expect(within(dialog).getByText(/此处仍显示所选旧版本/)).toBeTruthy()
  let entries = within(basket()).getAllByRole('listitem')
  expect(entries).toHaveLength(2)
  expect(entries[1]!.textContent).toContain('源版本：1；文本偏移：7–11')
  expect(entries[1]!.querySelector('pre')?.textContent).toBe('预算😀')
  await click('回查来源', entries[0]); expect(within(dialog).getByLabelText('预览正文').textContent).toBe('预算正文 1')
  await click('回查来源', entries[1]); expect(within(dialog).getByRole('region', { name: '纯文本预览' }).textContent).toContain('源版本 1')
  await click('移除参考', entries[0]); expect(within(basket()).getAllByRole('listitem')).toHaveLength(1)
  // Close/cancel leaves the underlying draft intact and does not retain a basket.
  await click('关闭参考准备', dialog); expect(draft.value).toBe('我的未发送问题')
  dialog = await open(); expect(within(basket()).queryAllByRole('listitem')).toHaveLength(0)
  await addMemory(dialog); await addSource(dialog, 0, port.source.length - 7)
  const expected = within(basket()).getByLabelText('完整消息预览').textContent
  expect(expected).toContain('<script>仅作文本</script>'); expect(dialog.querySelector('script,img,iframe')).toBeNull()
  await act(async () => {
    fireEvent.click(within(dialog).getByRole('button', { name: '核对来源并插入草稿' }))
    fireEvent.click(within(dialog).getByRole('button', { name: '核对来源并插入草稿' }))
  })
  expect(screen.queryByRole('dialog')).toBeNull(); expect(draft.value).toBe(expected)
  expect(draft.value.startsWith('我的未发送问题\n\n参考摘录：')).toBe(true)
  expect(screen.getByText(/参考已插入会话草稿，尚未发送/)).toBeTruthy()
  readOnly(port, from)
  expect(port.sent.filter(request => request.method === 'knowledge.preview').every(request => (request.payload as { sourceRevision: string }).sourceRevision === '1')).toBe(true)
})

it('keeps drafts on source changes and input limits and discards late verification across close, newer drafts and sessions', async () => {
  const { port, draft } = await mount(), from = port.sent.length
  let dialog = await open(); await addMemory(dialog)
  port.memory.items[0]!.revision = '9007199254740994'
  await click('核对来源并插入草稿', dialog)
  expect(within(basket()).getByRole('alert').textContent).toContain('记忆已变更'); expect(draft.value).toBe('我的未发送问题')
  await click('移除参考', basket()); await addSource(dialog)
  port.document.currentReadyRevision = '2'; port.missingRevision = true
  const previewCount = port.sent.filter(request => request.method === 'knowledge.preview').length
  await click('核对来源并插入草稿', dialog)
  expect(within(basket()).getByRole('alert').textContent).toContain('未回退新版')
  expect(port.sent.filter(request => request.method === 'knowledge.preview')).toHaveLength(previewCount)
  await click('关闭参考准备', dialog); port.missingRevision = false
  fireEvent.change(draft, { target: { value: '中'.repeat(1900) } }); dialog = await open(); await addMemory(dialog)
  expect((within(basket()).getByRole('button', { name: '核对来源并插入草稿' }) as HTMLButtonElement).disabled).toBe(true)
  expect(within(basket()).getByRole('alert').textContent).toContain('不会截断')
  expect(within(basket()).getByLabelText('完整消息预览').textContent!.startsWith('中'.repeat(1900))).toBe(true)
  await click('关闭参考准备', dialog)
  fireEvent.change(draft, { target: { value: '原草稿' } }); dialog = await open(); await addMemory(dialog)
  // A later edit cannot be overwritten by the preparation's earlier snapshot.
  fireEvent.change(draft, { target: { value: '后来编辑' } }); await click('核对来源并插入草稿', dialog)
  expect(draft.value).toBe('后来编辑'); expect(within(basket()).getByRole('alert').textContent).toContain('当前会话或草稿已变化')
  await click('关闭参考准备', dialog); dialog = await open(); await search(dialog); await addSource(dialog)
  port.holdDetail = true; await click('核对来源并插入草稿', dialog)
  const held = port.sent.length - 1, beforeClose = port.sent.filter(request => request.method === 'knowledge.preview').length
  await act(async () => fireEvent(dialog, new Event('cancel', { cancelable: true })))
  dialog = await open(); await act(async () => port.reply(held, port.detail()))
  expect(draft.value).toBe('后来编辑'); expect(within(basket()).queryAllByRole('listitem')).toHaveLength(0)
  expect(port.sent.filter(request => request.method === 'knowledge.preview')).toHaveLength(beforeClose)
  port.holdDetail = false; await search(dialog); await addSource(dialog)
  port.holdDetail = true; await click('核对来源并插入草稿', dialog); const sessionHeld = port.sent.length - 1
  await act(async () => port.session('22222222-2222-4222-8222-222222222222'))
  await act(async () => port.reply(sessionHeld, port.detail()))
  expect(screen.queryByRole('dialog')).toBeNull(); expect((await screen.findByLabelText('会话消息') as HTMLTextAreaElement).value).toBe('')
  // Session bootstrap may rediscover a conversation but must never send or save.
  expect(port.sent.slice(from).some(request => /conversations\.(send|selectMemories)|memory\.(create|update)|answerSubmit|proposeMemory/.test(String(request.method)))).toBe(false)
})
