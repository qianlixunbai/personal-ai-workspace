import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { KnowledgePage } from './KnowledgePage'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { findSourceMatches } from '../components/knowledgeSourceMatches'
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); vi.restoreAllMocks() })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function setup(port = new KnowledgeService()) { const bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ }); return { port, bridge } }
it('explicit keyword submit renders safe marks, keeps query ephemeral and opens the exact preview', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'); const { port } = await setup()
  const input = screen.getByLabelText('检索关键词'); fireEvent.change(input, { target: { value: '预算' } })
  expect(port.sent.some(r => r.method === 'knowledge.search')).toBe(false)
  await act(async () => fireEvent.submit(input.closest('form')!))
  expect(screen.getByText('预算', { selector: 'mark' })).toBeTruthy(); expect(document.querySelector('script,img,iframe')).toBeNull()
  expect(port.sent.find(r => r.method === 'knowledge.search')?.payload).toEqual({ query: '预算', limit: 10 })
  expect(location.href).not.toContain('预算'); expect(storage).not.toHaveBeenCalled()
  await click('打开此位置'); expect(port.sent.find(r => r.method === 'knowledge.preview')?.payload).toEqual({ documentId: port.document.documentId, sourceRevision: '1', offset: 0 })
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc')); expect((input as HTMLInputElement).value).toBe(''); expect(screen.queryByText('打开此位置')).toBeNull()
})
it('pins an explicitly selected source version without changing drafts or silently broadening after a refresh', async () => {
  const { port } = await setup(); await click(/fixture.md/)
  const search = within(screen.getByRole('region', { name: '查找资料' })), input = screen.getByLabelText('检索关键词')
  fireEvent.change(input, { target: { value: '预算' } })
  await act(async () => fireEvent.click(search.getByRole('button', { name: '限定为当前文档' })))
  await act(async () => fireEvent.submit(input.closest('form')!))
  expect(port.sent.filter(r => r.method === 'knowledge.search').at(-1)?.payload).toEqual({ query: '预算', limit: 10, scope: { documentId: port.document.documentId, sourceRevision: '1' } })
  const first = port.detail()
  vi.spyOn(port, 'detail').mockImplementation(() => ({ ...first, document: { ...port.document }, revisions: [...first.revisions, { sourceRevision: '2', sourceType: 'MARKDOWN', byteLength: 20 }] }))
  port.document.currentReadyRevision = '2'; await click('刷新详情')
  expect(search.getByText(/资料范围：fixture.md · 源版本 1/)).toBeTruthy()
  expect((input as HTMLInputElement).value).toBe('预算')
  await act(async () => fireEvent.click(search.getByRole('button', { name: '限定为当前文档' })))
  await act(async () => fireEvent.submit(input.closest('form')!))
  expect(search.getByRole('alert').textContent).toContain('返回的资料范围不一致')
  expect(search.queryByRole('button', { name: '打开此位置' })).toBeNull()
  await act(async () => fireEvent.click(search.getByRole('button', { name: '使用全部资料' })))
  expect((input as HTMLInputElement).value).toBe('预算')
  await act(async () => fireEvent.submit(input.closest('form')!))
  expect(port.sent.filter(r => r.method === 'knowledge.search').at(-1)?.payload).toEqual({ query: '预算', limit: 10 })
})
it('delete defaults to cancel, Escape returns focus and confirmation sends optimistic delete', async () => {
  const { port } = await setup(); await click(/fixture.md/); const button = screen.getByRole('button', { name: '物理删除…' }); button.focus(); await click('物理删除…')
  expect(document.activeElement?.textContent).toBe('取消'); fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' }); expect(document.activeElement).toBe(button)
  expect(port.sent.some(r => r.method === 'knowledge.delete')).toBe(false); await click('物理删除…'); await click('确认删除'); expect(port.deleted).toBe(true)
})
it('unknown import never automatically resends and explicit state lookup is read-only', async () => {
  const port = new KnowledgeService(); port.unknown = true; await setup(port); await click('导入文档')
  expect(port.sent.filter(r => r.method === 'knowledge.import')).toHaveLength(1); await click('检查导入状态'); expect(port.sent.filter(r => r.method === 'knowledge.import')).toHaveLength(1)
  expect(port.sent.find(r => r.method === 'knowledge.importState')?.payload).toEqual({ requestId: port.document.requestId })
})
it('session rotation clears loaded source and does not persist content or IDs', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'); const { port } = await setup(); await click(/fixture.md/); await click('预览源文本')
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc')); expect(screen.queryByText(/window.pwned=1/)).toBeNull(); expect(storage).not.toHaveBeenCalled()
  expect(location.hash).not.toContain(port.document.documentId)
})

class SourceFindService extends KnowledgeService {
  text = '# 旧版\n前文预算😀[x].\n<img src=x onerror=alert(1)>\n预算😀[x]. 预算😀[X].'
  boundary = this.text.indexOf('预算') + 2
  hold = false
  fail = false
  constructor() { super(); this.document.currentReadyRevision = '2' }
  override detail() { const result = super.detail(); result.revisions.push({ sourceRevision: '2', sourceType: 'MARKDOWN', byteLength: 10 }); return result }
  override postMessage(value: unknown) {
    const r = value as { method: string; payload: { documentId: string; sourceRevision: string; offset: number } }
    if (r.method !== 'knowledge.preview') { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>)
    if (this.hold) return
    const index = this.sent.length - 1, request = this.sent[index]!
    if (this.fail && r.payload.offset > 0) {
      this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: 'KnowledgeStorageUnavailable', message: '合成读取失败。' } }); return
    }
    const text = r.payload.sourceRevision === '1' ? this.text : '新版中没有该短语。', offset = r.payload.offset
    const end = offset < this.boundary ? Math.min(this.boundary, text.length) : text.length
    this.reply(index, { ...this.preview(), sourceRevision: r.payload.sourceRevision, offset, text: text.slice(offset, end), nextOffset: end < text.length ? end : null, locators: [] })
  }
}

class NavigationService extends SourceFindService {
  other = { ...this.document, documentId: 'ffffffff-ffff-4fff-8fff-ffffffffffff', title: '另一份资料.md', currentReadyRevision: '3' }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: { documentId: string } }
    if (['knowledge.get', 'knowledge.preview'].includes(request.method) && request.payload.documentId === this.other.documentId) {
      this.sent.push(value as typeof this.sent[number])
      this.reply(this.sent.length - 1, request.method === 'knowledge.get'
        ? { document: this.other, revisions: [{ sourceRevision: '3', sourceType: 'MARKDOWN', byteLength: 40 }], job: null }
        : { ...this.preview(), documentId: this.other.documentId, sourceRevision: '3', text: '另一份资料😀的引用。', locators: [] })
      return
    }
    super.postMessage(value)
  }
}

it('revisits exact sources across documents, preserves ranges and local back stacks, and keeps failed or late navigation isolated', async () => {
  const port = new NavigationService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />)
  await screen.findByRole('button', { name: /fixture.md/ }); await click(/fixture.md/)
  fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '1' } })
  fireEvent.click(screen.getByText('在此版本中查找'))
  fireEvent.change(screen.getByLabelText('原文查找短语'), { target: { value: '预算😀[x].' } })
  await click('查找此版本'); await click('打开第 1 处原文'); await click('下一页文本')
  vi.spyOn(port, 'hit').mockReturnValue({ ...port.hit(), documentId: port.other.documentId, title: port.other.title, sourceRevision: '3', startOffset: 0, endOffset: 11 })
  const search = screen.getByLabelText('检索关键词'); fireEvent.change(search, { target: { value: '预算' } })
  await act(async () => fireEvent.submit(search.closest('form')!)); await click('打开此位置')
  const source = () => screen.getByRole('region', { name: '纯文本预览' })
  const second = source().textContent
  port.fail = true; await click('返回上一处来源')
  expect(source().textContent).toBe(second)
  expect((screen.getByRole('button', { name: '返回上一处来源' }) as HTMLButtonElement).disabled).toBe(false)
  port.fail = false; await click('返回上一处来源')
  expect((screen.getByLabelText('已发布源版本') as HTMLSelectElement).value).toBe('1')
  expect(source().querySelector('mark')?.textContent).toBe('😀[x].')
  expect(source().textContent).toContain('查找命中范围')
  await click('上一页文本')
  expect(source().querySelector('mark')?.textContent).toBe('预算')
  await click('前进到下一处来源'); expect(source().textContent).toBe(second)
  port.hold = true; await click('返回上一处来源')
  const late = port.sent.length - 1
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  await act(async () => port.reply(late, { ...port.preview(), offset: 7, text: '预算', nextOffset: 9, locators: [] }))
  view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  expect(screen.queryByRole('region', { name: '纯文本预览' })).toBeNull()
  expect(screen.queryByRole('navigation', { name: '来源阅读记录' })).toBeNull()
  expect(port.sent.some(r => /answerSubmit|import|delete/.test(String(r.method)))).toBe(false)
})

it('locates source lines in the selected immutable version and retains reading on absent lines, read failure or departure', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'), port = new SourceFindService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />)
  await screen.findByRole('button', { name: /fixture.md/ }); await click(/fixture.md/)
  fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '1' } })
  fireEvent.click(screen.getByText('按原文行号定位'))
  const line = screen.getByLabelText('原文行号')
  fireEvent.change(line, { target: { value: '0' } })
  expect((screen.getByRole('button', { name: '定位原文行' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(line, { target: { value: '3' } }); await click('定位原文行')
  const source = screen.getByRole('region', { name: '纯文本预览' }), original = source.textContent
  expect(source.querySelector('mark')?.textContent).toBe('<img src=x onerror=alert(1)>')
  expect(source.textContent).toContain('行号定位范围：行 3–3')
  expect(port.sent.filter(r => r.method === 'knowledge.preview').at(-1)?.payload).toEqual({ documentId: port.document.documentId, sourceRevision: '1', offset: port.text.indexOf('<img') })
  expect(document.querySelector('img,script,iframe')).toBeNull()
  fireEvent.change(line, { target: { value: '999' } }); await click('定位原文行')
  expect(source.textContent).toBe(original); expect(screen.getByRole('alert').textContent).toContain('没有此行')
  port.fail = true; fireEvent.change(line, { target: { value: '2' } }); await click('定位原文行')
  expect(source.textContent).toBe(original); expect(screen.getByRole('alert').textContent).toBe('合成读取失败。')
  port.fail = false; port.hold = true; await click('定位原文行')
  const late = port.sent.length - 1, count = port.sent.filter(r => r.method === 'knowledge.preview').length
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  await act(async () => port.reply(late, { ...port.preview(), text: '预算', nextOffset: 2, locators: [] }))
  view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  expect(port.sent.filter(r => r.method === 'knowledge.preview')).toHaveLength(count)
  expect(screen.queryByRole('region', { name: '纯文本预览' })).toBeNull()
  expect(screen.queryByRole('alert')).toBeNull()
  expect((screen.getByLabelText('原文行号') as HTMLInputElement).value).toBe('')
  expect(storage).not.toHaveBeenCalled()
})

it('finds literal phrases across preview boundaries in the selected old version and opens exact highlighted source', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'), { port } = await setup(new SourceFindService())
  await click(/fixture.md/)
  fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '1' } })
  fireEvent.click(screen.getByText('在此版本中查找'))
  const input = screen.getByLabelText('原文查找短语')
  fireEvent.change(input, { target: { value: '预算😀[x].' } })
  expect(port.sent.filter(r => r.method === 'knowledge.preview')).toHaveLength(0)
  await click('查找此版本')
  expect(screen.getByText('已完整查找源版本 1，找到 2 处。')).toBeTruthy()
  const results = within(screen.getByRole('region', { name: '版本内查找结果' }))
  expect(results.getAllByText('预算😀[x].', { selector: 'mark' })).toHaveLength(2)
  expect(port.sent.filter(r => r.method === 'knowledge.preview').map(r => r.payload)).toEqual([
    { documentId: port.document.documentId, sourceRevision: '1', offset: 0 },
    { documentId: port.document.documentId, sourceRevision: '1', offset: 9 },
  ])
  expect(document.querySelector('img,script,iframe')).toBeNull()
  await click('打开第 1 处原文')
  const source = within(screen.getByRole('region', { name: '纯文本预览' }))
  expect(source.getByText('预算', { selector: 'mark' })).toBeTruthy()
  expect(source.getByText(/当前可用版本为 2/)).toBeTruthy()
  expect(source.getByText(/查找命中范围：行 2–2/)).toBeTruthy()
  await click('下一页文本')
  expect(source.getByText('😀[x].', { selector: 'mark' })).toBeTruthy()
  expect(storage).not.toHaveBeenCalled(); expect(location.href).not.toContain('预算')
  fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '2' } })
  expect(screen.queryByRole('region', { name: '版本内查找结果' })).toBeNull()
  expect((screen.getByLabelText('原文查找短语') as HTMLTextAreaElement).value).toBe('')
})

it('does not publish prefix results on failed reads, and stopping or leaving suppresses late pages without replay', async () => {
  const port = new SourceFindService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />)
  await screen.findByRole('button', { name: /fixture.md/ }); await click(/fixture.md/)
  fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '1' } })
  fireEvent.click(screen.getByText('在此版本中查找'))
  fireEvent.change(screen.getByLabelText('原文查找短语'), { target: { value: '预算' } })
  port.fail = true; await click('查找此版本')
  expect(screen.getByRole('alert').textContent).toBe('合成读取失败。')
  expect(screen.queryByText(/已完整查找/)).toBeNull()
  expect(screen.queryByRole('region', { name: '版本内查找结果' })).toBeNull()
  port.fail = false; port.hold = true
  await click('查找此版本'); const held = port.sent.length - 1
  await click('停止查找')
  expect((screen.getByRole('button', { name: '查找此版本' }) as HTMLButtonElement).disabled).toBe(true)
  const partial = { ...port.preview(), text: '预算', nextOffset: 2, locators: [] }
  await act(async () => port.reply(held, partial))
  expect(screen.getByText('已停止查找。')).toBeTruthy()
  expect(port.sent.filter(r => r.method === 'knowledge.preview')).toHaveLength(3)
  await click('查找此版本'); const late = port.sent.length - 1
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  await act(async () => port.reply(late, partial))
  view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  expect(screen.queryByRole('region', { name: '版本内查找结果' })).toBeNull()
  expect((screen.getByLabelText('原文查找短语') as HTMLTextAreaElement).value).toBe('')
  expect(port.sent.filter(r => r.method === 'knowledge.preview')).toHaveLength(4)
  port.hold = false
  fireEvent.change(screen.getByLabelText('原文查找短语'), { target: { value: '不存在' } }); await click('查找此版本')
  expect(screen.getByText('已完整查找源版本 1，找到 0 处。')).toBeTruthy()
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
  expect(screen.queryByText(/已完整查找/)).toBeNull()
  expect(port.sent.some(r => ['knowledge.answerSubmit', 'knowledge.rebuildSearchIndex', 'knowledge.import'].includes(r.method as string))).toBe(false)
})

it('keeps literal overlapping matches, multiline coordinates and Unicode snippets while bounding the displayed list', () => {
  const found = findSourceMatches('😀甲\n😀甲\n😀甲', '😀甲\n😀甲')
  expect(found.matches.map(m => [m.startOffset, m.endOffset, m.startLine, m.endLine])).toEqual([[0, 7, 1, 2], [4, 11, 2, 3]])
  const many = findSourceMatches('a'.repeat(100_000), 'aa')
  expect(many.total).toBe(99_999); expect(many.matches).toHaveLength(200)
  const text = '😀'.repeat(31) + 'a预算b' + '😀'.repeat(31), match = findSourceMatches(text, '预算').matches[0]!
  expect(match.snippet).not.toMatch(/^[\uDC00-\uDFFF]|[\uD800-\uDBFF]$/u)
  expect(match.snippet.slice(match.startOffset - match.snippetOffset, match.endOffset - match.snippetOffset)).toBe('预算')
  expect(() => findSourceMatches('anything', '')).toThrow('查找短语')
})
