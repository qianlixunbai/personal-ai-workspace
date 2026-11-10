import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgePage } from '../pages/KnowledgePage'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { MemoryService } from '../test/memoryFixtures'

class ExcerptService extends KnowledgeService {
  memory = new MemoryService()
  oldText = '# 旧版条件\n只用于😀本项目。\n<script>需要人工核对。</script>\n保留后文。'
  readingRevision = '2'
  readingOffset = 0
  constructor() {
    super()
    this.document.currentReadyRevision = '2'
    this.memory.items = []; this.memory.sent = this.sent
    this.memory.listener = event => this.emit(event.data)
  }
  override job() { return { ...super.job(), sourceRevision: '2' } }
  override detail() { return { ...super.detail(), revisions: ['1', '2'].map(sourceRevision => ({ sourceRevision, sourceType: 'MARKDOWN' as const, byteLength: 8000 })) } }
  override preview() {
    const source = this.readingRevision === '1' ? this.oldText : '# 新版条件\n当前版本内容。'
    const text = source.slice(this.readingOffset, this.readingOffset + 4096), end = this.readingOffset + text.length
    return { ...super.preview(), sourceRevision: this.readingRevision, offset: this.readingOffset, text,
      nextOffset: end < source.length ? end : null, locators: [{ ...super.preview().locators[0]!, endLine: 10, endOffset: source.length }] }
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }
    if (request.method.startsWith('memory.') || request.method.startsWith('shell.')) { this.memory.postMessage(value); return }
    if (request.method === 'knowledge.preview') { this.readingRevision = String(request.payload.sourceRevision); this.readingOffset = Number(request.payload.offset) }
    super.postMessage(value)
  }
}
const clients: WorkspaceClient[] = []
const handoff = '将选中原文整理为记忆草稿'
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges(); clients.splice(0).forEach(c => c.dispose()); vi.restoreAllMocks() })
async function select(root: Node, start: number, end: number) {
  const point = (offset: number): [Node, number] => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (offset <= node.textContent!.length) return [node, offset]
      offset -= node.textContent!.length
    }
    throw new Error('Invalid test selection')
  }
  await act(async () => {
    const range = document.createRange(); range.setStart(...point(start)); range.setEnd(...point(end))
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
  })
}
async function oldPreview() {
  await screen.findByRole('button', { name: /fixture.md/ })
  await click(/fixture.md/)
  await act(async () => fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '1' } }))
  await click('预览源文本')
  return screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')!
}
it('reviews an exact old-source excerpt in the real Memory editor, saves once and reopens it without a model', async () => {
  const port = new ExcerptService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/knowledge'); render(<App bridge={bridge} />); await act(async () => port.session())
  await oldPreview()
  fireEvent.click(screen.getByText('在此版本中查找'))
  fireEvent.change(screen.getByLabelText('原文查找短语'), { target: { value: '只用于' } })
  await click('查找此版本'); await click('打开第 1 处原文')
  const pre = screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')!
  const start = port.oldText.indexOf('只用于'), end = port.oldText.indexOf('\n保留后文')
  expect(port.readingOffset).toBe(start)
  expect(pre.querySelector('mark')?.textContent).toBe('只用于')
  expect(screen.getByRole('button', { name: handoff }).hasAttribute('disabled')).toBe(true)
  await select(pre, 0, end - start); await click(handoff)
  expect(location.hash).toBe('#/memory')
  const expected = `${port.oldText.slice(start, end)}\n\n出处：fixture.md\n资料 ID：${port.document.documentId}\n源版本：1；文本偏移：${start}–${end}（不含结束位置）`
  const content = screen.getByLabelText('记忆内容') as HTMLTextAreaElement
  expect(content.value).toBe(expected)
  expect(screen.getByText(/出处是可编辑的文字记录/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: /生成记忆候选/ })).toBeNull()
  expect(port.sent.filter(r => r.method === 'memory.create')).toHaveLength(0)
  fireEvent.change(screen.getByLabelText('记忆标题'), { target: { value: '已核对的旧版条件' } })
  fireEvent.change(content, { target: { value: expected + '\n人工批注：只作项目参考。' } })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '保存' })); fireEvent.click(screen.getByRole('button', { name: '保存' })) })
  expect(port.sent.filter(r => r.method === 'memory.create')).toHaveLength(1)
  expect(port.memory.items[0]?.content).toBe(expected + '\n人工批注：只作项目参考。')
  await click('新建'); await click(/已核对的旧版条件/)
  expect(content.value).toBe(port.memory.items[0]!.content)
  expect(port.sent.filter(r => r.method === 'memory.get')).toHaveLength(1)
  expect(port.sent.some(r => /proposeMemory|answerSubmit|conversation\.submit|memory\.select|memory\.update/.test(String(r.method)))).toBe(false)
  expect(document.querySelector('script')).toBeNull()
})
it('keeps excerpt handoff bounded to this preview and session, protects existing drafts and never truncates oversized text', async () => {
  const port = new ExcerptService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  port.oldText = '中'.repeat(2001) + '😀'
  history.replaceState(null, '', '#/knowledge'); const view = render(<App bridge={bridge} />); await act(async () => port.session())
  const title = screen.getByLabelText('记忆标题') as HTMLInputElement, content = screen.getByLabelText('记忆内容') as HTMLTextAreaElement
  fireEvent.change(title, { target: { value: '已有草稿' } }); fireEvent.change(content, { target: { value: '保留内容' } })
  const pre = await oldPreview()
  await select(pre, 2001, 2002) // A split surrogate is not a valid excerpt.
  expect(screen.getByRole('button', { name: handoff }).hasAttribute('disabled')).toBe(true)
  await select(pre, 0, 2003); await click(handoff)
  await act(async () => fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '取消' })))
  expect(title.value).toBe('已有草稿'); expect(content.value).toBe('保留内容'); expect(location.hash).toBe('#/knowledge')
  await select(pre, 0, 2003); await click(handoff)
  await act(async () => fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '丢弃并继续' })))
  expect(content.value.startsWith(port.oldText + '\n\n出处：')).toBe(true)
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('alert').textContent).toContain('原文已完整保留')
  expect(port.sent.some(r => r.method === 'memory.create')).toBe(false)
  await act(async () => port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'))
  expect(content.value).toBe('')
  view.unmount()
  const receive = vi.fn(); const page = render(<KnowledgePage bridge={bridge} enabled visible onMemoryDraft={receive} />)
  const next = await oldPreview(); await select(next, 0, 2)
  page.rerender(<KnowledgePage bridge={bridge} enabled={false} visible onMemoryDraft={receive} />)
  await click(handoff); expect(receive).not.toHaveBeenCalled()
  page.rerender(<KnowledgePage bridge={bridge} enabled visible onMemoryDraft={receive} />)
  expect(screen.getByRole('button', { name: handoff }).hasAttribute('disabled')).toBe(true)
  await select(next, 0, 2)
  await act(async () => fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '2' } }))
  await click('预览源文本')
  expect(screen.getByRole('button', { name: handoff }).hasAttribute('disabled')).toBe(true)
  const outside = screen.getByRole('heading', { name: 'fixture.md' }); await select(outside, 0, 2)
  expect(screen.getByRole('button', { name: handoff }).hasAttribute('disabled')).toBe(true)
  await act(async () => port.session('dddddddd-dddd-4ddd-8ddd-dddddddddddd'))
  expect(screen.queryByRole('button', { name: handoff })).toBeNull(); expect(receive).not.toHaveBeenCalled()
})
