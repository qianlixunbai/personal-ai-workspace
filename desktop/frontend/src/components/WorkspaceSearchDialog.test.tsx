import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { MemoryService, memoryItem } from '../test/memoryFixtures'
import { conversation, history as conversationHistory } from '../test/conversationFixtures'
import type { ConversationSearchHit } from '../bridge/conversations'

class SearchService extends KnowledgeService {
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
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string | RegExp, root?: HTMLElement) => { await act(async () => fireEvent.click((root ? within(root) : screen).getByRole('button', { name }))) }
const requests = (port: SearchService, method: string) => port.sent.filter(request => request.method === method)
const open = async () => { await click('查找工作区'); return screen.getByRole('dialog', { name: '查找工作区' }) }
const search = async (dialog: HTMLElement, query = '预算') => {
  fireEvent.change(within(dialog).getByLabelText('工作区关键词'), { target: { value: query } }); await click('查找', dialog)
}

it('finds and reads all three domains with independent failure, paging and exact old-source context while preserving the Memory draft', async () => {
  const port = new SearchService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/memory'); render(<App bridge={bridge} />); await act(async () => port.session())
  await screen.findByRole('button', { name: /预算记忆 1 PROJECT_NOTE/ })
  const title = screen.getByLabelText('记忆标题') as HTMLInputElement, body = screen.getByLabelText('记忆内容') as HTMLTextAreaElement
  fireEvent.change(title, { target: { value: '未保存标题' } }); fireEvent.change(body, { target: { value: '未保存内容' } })
  await act(async () => fireEvent.click(screen.getAllByRole('button', { name: '预览正文' })[0]!))
  const opener = screen.getByRole('button', { name: '查找工作区' }); opener.focus()
  const before = port.sent.length, dialog = await open()
  expect(port.sent.length).toBe(before)
  fireEvent.change(within(dialog).getByLabelText('工作区关键词'), { target: { value: '预算' } })
  expect(port.sent.length).toBe(before)
  port.searchState = 'STALE'; await click('查找', dialog)
  expect(within(dialog).getByRole('button', { name: '重试资料查找' })).toBeTruthy()
  expect(within(dialog).getByText('共 21 条 · 第 1 页')).toBeTruthy()
  await click(/^预算记忆 1\s*项目笔记/, dialog)
  expect(within(dialog).getByLabelText('预览正文').textContent).toBe('预算正文 1')
  const ids = Array.from(document.querySelectorAll('[id]'), element => element.id)
  expect(new Set(ids).size).toBe(ids.length)
  await click('下一页记忆', dialog); await click(/^预算记忆 21\s*项目笔记/, dialog)
  expect(within(dialog).getByLabelText('预览正文').textContent).toBe('预算正文 21')
  await click('下一页消息', dialog); await click(/Synthetic conversation\s*第 11 轮/, dialog)
  expect((requests(port, 'conversations.get').at(-1)!.payload as { page: number }).page).toBe(1)
  const conversationReader = within(dialog).getByRole('region', { name: '查找对话原文' })
  expect(conversationReader.textContent).toContain('预算 user 11<script>字面文本</script>')
  expect(conversationReader.textContent).toContain('Synthetic answer 11')
  port.searchState = 'READY'; await click('重试资料查找', dialog)
  expect(requests(port, 'conversations.search')).toHaveLength(2)
  port.document.currentReadyRevision = '2'; await click(/fixture.md\s*源版本 1/, dialog)
  const source = within(dialog).getByRole('region', { name: '查找资料原文' })
  expect(source.textContent).toContain('当前可用版本为 2，此处仍显示所选旧版本')
  expect(source.querySelector('pre')!.textContent).toBe(port.source.slice(port.hit().startOffset))
  await click('阅读前文', dialog); await click('返回检索命中位置', dialog); await click('上一页文本', dialog)
  expect(port.sent.filter(request => String(request.method).startsWith('knowledge.preview')).every(request => (request.payload as { sourceRevision: string }).sourceRevision === '1')).toBe(true)
  expect(dialog.querySelector('script,img,iframe')).toBeNull()
  await click('关闭查找', dialog)
  expect(title.value).toBe('未保存标题'); expect(body.value).toBe('未保存内容'); expect(location.hash).toBe('#/memory')
  expect(document.activeElement).toBe(opener)
  const count = port.sent.length, reopened = await open()
  expect((within(reopened).getByLabelText('工作区关键词') as HTMLInputElement).value).toBe('')
  expect(within(reopened).queryByRole('region', { name: '工作区记忆结果' })).toBeNull(); expect(port.sent.length).toBe(count)
  const methods = port.sent.slice(before).map(request => String(request.method))
  expect(methods.every(method => ['memory.list', 'memory.get', 'conversations.search', 'conversations.get', 'knowledge.search', 'knowledge.get', 'knowledge.preview', 'knowledge.previewBefore'].includes(method))).toBe(true)
})

it('keeps conversation drafts, respects scopes and invalid queries, rejects stale hits, and stops late reads across close and session changes', async () => {
  const port = new SearchService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/conversations'); render(<App bridge={bridge} />); await act(async () => port.session())
  const draft = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '待发送的合成问题' } })
  let dialog = await open(), input = within(dialog).getByLabelText('工作区关键词')
  const baseline = port.sent.length
  for (const query of [' ', 'x'.repeat(101), '\uD800']) {
    fireEvent.change(input, { target: { value: query } }); expect((within(dialog).getByRole('button', { name: '查找' }) as HTMLButtonElement).disabled).toBe(true)
  }
  fireEvent.change(input, { target: { value: '预算' } }); fireEvent.compositionStart(input)
  await act(async () => fireEvent.submit(input.closest('form')!))
  expect(port.sent.length).toBe(baseline); fireEvent.compositionEnd(input)
  fireEvent.change(within(dialog).getByLabelText('记忆范围'), { target: { value: 'ARCHIVED' } })
  fireEvent.change(within(dialog).getByLabelText('对话范围'), { target: { value: 'ARCHIVED' } }); await click('查找', dialog)
  expect(within(dialog).getByText('共 1 条 · 第 1 页')).toBeTruthy(); expect(within(dialog).getByText('没有匹配消息。')).toBeTruthy()
  fireEvent.change(within(dialog).getByLabelText('记忆范围'), { target: { value: 'ACTIVE' } })
  fireEvent.change(within(dialog).getByLabelText('对话范围'), { target: { value: 'ALL' } }); await click('查找', dialog)
  port.memory.items[0]!.revision = '9007199254740994'; await click(/^预算记忆 1\s*项目笔记/, dialog)
  expect(within(dialog).getByRole('alert').textContent).toContain('这条记忆已变更')
  port.missingMessage = true; await click(/Synthetic conversation\s*第 1 轮/, dialog)
  expect(within(dialog).getByRole('alert').textContent).toContain('该匹配消息已不可用'); port.missingMessage = false
  port.document.currentReadyRevision = '2'; port.missingRevision = true; await click(/fixture.md\s*源版本 1/, dialog)
  expect(within(dialog).getByRole('alert').textContent).toContain('未回退到当前版本'); expect(requests(port, 'knowledge.preview')).toHaveLength(0)
  await click('收起查找预览', dialog); port.missingRevision = false; port.holdDetail = true
  await click(/fixture.md\s*源版本 1/, dialog)
  const oldDetail = port.sent.length - 1
  await act(async () => fireEvent(dialog, new Event('cancel', { cancelable: true }))); expect(draft.value).toBe('待发送的合成问题')
  dialog = await open(); await search(dialog)
  await act(async () => port.reply(oldDetail, port.detail()))
  expect(requests(port, 'knowledge.preview')).toHaveLength(0)
  expect(within(dialog).queryByRole('region', { name: '查找资料原文' })).toBeNull()
  await click(/fixture.md\s*源版本 1/, dialog); const sessionDetail = port.sent.length - 1
  await act(async () => port.session('22222222-2222-4222-8222-222222222222'))
  await act(async () => port.reply(sessionDetail, port.detail()))
  expect(screen.queryByRole('dialog')).toBeNull(); expect(requests(port, 'knowledge.preview')).toHaveLength(0)
  // A pending group may finish after close; it must not populate a reopened view.
  port.holdSearch = true; dialog = await open(); await search(dialog); const oldSearch = port.sent.length - 1
  expect((within(dialog).getByRole('button', { name: '查找' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => fireEvent.submit(within(dialog).getByLabelText('工作区关键词').closest('form')!))
  expect(port.sent.length - 1).toBe(oldSearch)
  await click('关闭查找', dialog); dialog = await open()
  await act(async () => port.reply(oldSearch, { hits: [port.hit()] }))
  expect(within(dialog).queryByRole('region', { name: '工作区资料结果' })).toBeNull()
  expect(port.sent.some(request => /memory\.(create|update|archive|delete)|conversations\.(send|selectMemories)|answerSubmit|proposeMemory|rebuildSearchIndex/.test(String(request.method)))).toBe(false)
})
