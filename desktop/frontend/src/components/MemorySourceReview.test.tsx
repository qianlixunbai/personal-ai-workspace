import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { ConversationsPage } from '../pages/ConversationsPage'
import { MemorySourceReview } from './MemorySourceReview'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { MemoryService, memoryItem } from '../test/memoryFixtures'
import { conversation, history as conversationHistory } from '../test/conversationFixtures'

class SourceService extends KnowledgeService {
  memory = new MemoryService()
  source = '# 前言\n适用😀当前项目。\n<script>仅供核对。</script>\n后文。'
  start = this.source.indexOf('适用')
  end = this.source.indexOf('\n后文')
  split = this.source.indexOf('<script>')
  missing = false; missingRevision = false; wrongVersion = false; holdDetail = false; failTail = false
  authorized = false; holdList = false
  override session(id?: string) { this.authorized = false; super.session(id) }
  constructor() {
    super(); this.document.currentReadyRevision = '2'
    this.memory.items = [{ ...memoryItem(), content: this.note(), title: '摘录笔记' }]
    this.memory.sent = this.sent; this.memory.listener = event => this.emit(event.data)
  }
  note() {
    return `${this.source.slice(this.start, this.end)}\n\n出处：old-name.md\n资料 ID：${this.document.documentId}\n源版本：1；文本偏移：${this.start}–${this.end}（不含结束位置）\n人工批注：仍需核实。`
  }
  override job() { return { ...super.job(), sourceRevision: '2' } }
  override detail() { return { ...super.detail(), revisions: (this.missingRevision ? ['2'] : ['1', '2']).map(sourceRevision => ({ sourceRevision, sourceType: 'MARKDOWN' as const, byteLength: 200 })) } }
  reject(index: number, code: string) {
    const request = this.sent[index]!
    this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code, message: '合成资料读取失败。' } })
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }, { method, payload } = request
    if (method.startsWith('memory.') || method.startsWith('shell.')) { this.memory.postMessage(value); return }
    if (method.startsWith('conversations.')) {
      this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
      if (method === 'conversations.list') this.reply(index, { items: [conversation], total: 1, page: 0, limit: 10 })
      if (method === 'conversations.get') {
        const result = conversationHistory(), item = this.memory.items[0]!
        result.turns[0]!.memoryReferences = [{ memoryId: item.id, revision: memoryItem().revision, position: 0 }]
        this.reply(index, result)
      }
      if (method === 'conversations.clearMemories') this.reply(index, { cleared: true })
      return
    }
    if (method === 'knowledge.list') {
      this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
      if (this.holdList) return
      const items = !this.deleted && payload.status === this.document.status ? [{ ...this.document }] : []
      if (items.length) this.authorized = true
      this.reply(index, { items, total: items.length, page: payload.page, limit: 20 }); return
    }
    if (method === 'knowledge.get') {
      this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
      if (this.holdDetail) return
      if (this.missing || !this.authorized) this.reject(index, 'KnowledgeNotFound'); else this.reply(index, this.detail())
      return
    }
    if (method === 'knowledge.preview' || method === 'knowledge.previewBefore') {
      this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
      const before = method === 'knowledge.previewBefore', requested = Number(payload.offset)
      if (this.failTail && requested === this.split) { this.reject(index, 'KnowledgeStorageUnavailable'); return }
      const offset = before ? 0 : requested, end = before ? requested : offset < this.split ? this.split : this.source.length
      this.reply(index, { ...super.preview(), sourceRevision: this.wrongVersion ? '2' : String(payload.sourceRevision), offset,
        text: this.source.slice(offset, end), nextOffset: end < this.source.length ? end : null,
        locators: [{ ...super.preview().locators[0]!, endOffset: this.source.length, endLine: 4 }] })
      return
    }
    super.postMessage(value)
  }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string | RegExp, root?: HTMLElement) => {
  await act(async () => fireEvent.click((root ? within(root) : screen).getByRole('button', { name })))
}
const sourceReads = (port: SourceService) => port.sent.filter(request => String(request.method).startsWith('knowledge.'))

it('reads the exact saved source from Memory preview, edited draft and conversation reference without changing any content', async () => {
  const port = new SourceService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/memory'); const view = render(<App bridge={bridge} />); await act(async () => port.session())
  await screen.findByRole('button', { name: /摘录笔记 PROJECT_NOTE/ })
  const title = screen.getByLabelText('记忆标题') as HTMLInputElement, content = screen.getByLabelText('记忆内容') as HTMLTextAreaElement
  fireEvent.change(title, { target: { value: '保留中的草稿' } }); fireEvent.change(content, { target: { value: '未保存的正文' } })
  await click('预览正文')
  const reader = screen.getByRole('region', { name: '记忆只读预览' })
  expect(sourceReads(port)).toHaveLength(0)
  await click('查看资料出处', reader)
  expect(within(reader).getByLabelText('所记范围原文').textContent).toBe(port.source.slice(port.start, port.end))
  expect(within(reader).getByText(/逐字一致/)).toBeTruthy()
  expect(within(reader).getByText(/资料名称与正文记录不同/)).toBeTruthy()
  expect(within(reader).getByText(/所读源版本 1 · 当前可用版本 2/)).toBeTruthy()
  expect(title.value).toBe('保留中的草稿'); expect(content.value).toBe('未保存的正文')
  fireEvent.click(within(reader).getByText('阅读原文上下文'))
  await click('下一页文本', reader); await click('阅读前文', reader); await click('返回所记范围', reader)
  expect(sourceReads(port).filter(request => String(request.method).startsWith('knowledge.preview')).every(request => (request.payload as { sourceRevision: string }).sourceRevision === '1')).toBe(true)
  await click('收起预览')
  fireEvent.change(content, { target: { value: port.note().replace('适用', '不适用') } })
  const editor = screen.getByRole('region', { name: '新记忆' })
  await click('查看资料出处', editor)
  expect(within(editor).getByText(/正文与该范围原文不一致/)).toBeTruthy()
  expect(content.value).toBe(port.note().replace('适用', '不适用'))
  expect(title.value).toBe('保留中的草稿')
  view.unmount()

  port.document.status = 'ARCHIVED'; port.session('dddddddd-dddd-4ddd-8ddd-dddddddddddd')
  const conversationView = render(<ConversationsPage bridge={bridge} enabled visible />)
  await screen.findByLabelText('会话消息')
  await click('查看记忆引用'); await click('查看记忆 1')
  await screen.findByLabelText('引用记忆正文')
  const reference = screen.getByRole('region', { name: '对话引用记忆只读预览' })
  const draft = screen.getByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '仍未发送的问题' } })
  await click('查看资料出处', reference)
  expect(sourceReads(port).filter(request => request.method === 'knowledge.list').slice(-2).map(request => request.payload)).toEqual([
    { status: 'ACTIVE', page: 0 }, { status: 'ARCHIVED', page: 0 },
  ])
  expect(within(reference).getByLabelText('所记范围原文').textContent).toBe(port.source.slice(port.start, port.end))
  expect(draft.value).toBe('仍未发送的问题')
  const before = sourceReads(port).length
  port.memory.items[0]!.revision = '9007199254740994'
  await click('收起记忆引用'); await click('查看记忆引用'); await click('查看记忆 1')
  expect(screen.getByText(/历史正文未保存，无法展示当时内容/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: '查看资料出处' })).toBeNull()
  expect(sourceReads(port)).toHaveLength(before)
  expect(port.sent.some(request => /memory\.(create|update|archive|delete)|conversations\.send|selectMemories|answerSubmit|proposeMemory/.test(String(request.method)))).toBe(false)
  expect(document.querySelector('script,img,iframe')).toBeNull()
  conversationView.unmount()
})

it('rejects ambiguous source hints, missing versions and incomplete reads, and stops stale follow-up reads after editing or session changes', async () => {
  const port = new SourceService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const content = port.note(), view = render(<MemorySourceReview bridge={bridge} content={content} />)
  expect(sourceReads(port)).toHaveLength(0)
  for (const invalid of [content + content, content.replace(port.document.documentId, 'https://invalid'), content.replace(`–${port.end}`, '–999999')]) {
    view.rerender(<MemorySourceReview bridge={bridge} content={invalid} />)
    expect(screen.getByText(/无法确定要回查的位置/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '查看资料出处' })).toBeNull()
  }
  expect(sourceReads(port)).toHaveLength(0)
  view.rerender(<MemorySourceReview bridge={bridge} content={content} />)
  port.deleted = true; await click('查看资料出处')
  expect(screen.getByRole('alert').textContent).toContain('未找到原资料')
  expect(port.sent.filter(request => request.method === 'knowledge.get')).toHaveLength(0)
  port.deleted = false; port.holdList = true; await click('查看资料出处')
  const heldList = port.sent.length - 1
  view.rerender(<MemorySourceReview bridge={bridge} content={'编辑后' + content} />)
  await act(async () => port.reply(heldList, { items: [port.document], total: 1, page: 0, limit: 20 }))
  expect(port.sent.filter(request => request.method === 'knowledge.get')).toHaveLength(0)
  port.holdList = false; view.rerender(<MemorySourceReview bridge={bridge} content={content} />)
  port.missingRevision = true; await click('查看资料出处')
  expect(screen.getByRole('alert').textContent).toContain('未回退到当前版本')
  expect(port.sent.filter(request => request.method === 'knowledge.preview')).toHaveLength(0)
  port.missingRevision = false; port.missing = true; await click('查看资料出处')
  expect(screen.getByRole('alert').textContent).toContain('已删除或不可用')
  port.missing = false; port.failTail = true; await click('查看资料出处')
  expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByLabelText('所记范围原文')).toBeNull()
  port.failTail = false; port.wrongVersion = true; await click('查看资料出处')
  expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByLabelText('所记范围原文')).toBeNull()
  port.wrongVersion = false; port.holdDetail = true; await click('查看资料出处')
  let pending = port.sent.length - 1, before = port.sent.filter(request => request.method === 'knowledge.preview').length
  view.rerender(<MemorySourceReview bridge={bridge} content={'修改后的正文\n' + content} />)
  await act(async () => port.reply(pending, port.detail()))
  expect(port.sent.filter(request => request.method === 'knowledge.preview')).toHaveLength(before)
  expect(screen.queryByLabelText('所记范围原文')).toBeNull()
  await click('查看资料出处'); pending = port.sent.length - 1
  await act(async () => { port.reply(pending, port.detail()); port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee') })
  expect(port.sent.filter(request => request.method === 'knowledge.preview')).toHaveLength(before)
  expect(screen.queryByLabelText('所记范围原文')).toBeNull()
  port.holdDetail = false; await click('查看资料出处')
  expect(screen.getByLabelText('所记范围原文').textContent).toBe(port.source.slice(port.start, port.end))
  view.rerender(<MemorySourceReview bridge={bridge} content={content.replace(`–${port.end}`, `–${port.source.indexOf('😀') + 1}`)} />)
  await click('查看资料出处')
  expect(screen.getByRole('alert').textContent).toContain('范围切断了字符')
  expect(screen.queryByLabelText('所记范围原文')).toBeNull()
  view.rerender(<MemorySourceReview bridge={bridge} content={content} enabled={false} />)
  const count = sourceReads(port).length; await click('查看资料出处')
  expect(sourceReads(port)).toHaveLength(count)
  expect(screen.queryByLabelText('所记范围原文')).toBeNull()
  expect(sourceReads(port).every(request => ['knowledge.list', 'knowledge.get', 'knowledge.preview'].includes(String(request.method)))).toBe(true)
})
