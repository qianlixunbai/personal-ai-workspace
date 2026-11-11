import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeDetail, KnowledgeLocator, KnowledgePreview } from '../bridge/knowledge'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { KnowledgePage } from '../pages/KnowledgePage'
import { KnowledgeSourceOutline } from './KnowledgeSourceOutline'

const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }

// These are saved Runtime locator fixtures, not a client-side Markdown parser.
function source(blocks: { text: string; heading: string | null; sectionLine: number | null }[]) {
  let text = '', line = 1
  const locators: KnowledgeLocator[] = []
  for (const block of blocks) {
    const start = text.length, count = block.text.split('\n').length - 1
    text += block.text
    locators.push({ type: 'MARKDOWN_SECTION_LINES', startLine: line, endLine: line + count - 1,
      startOffset: start, endOffset: text.length, section: block.sectionLine === null ? null : `line-${block.sectionLine}`, heading: block.heading })
    line += count
  }
  return { text, locators }
}
class OutlineService extends KnowledgeService {
  sources = {
    '1': source([
      { text: '合成前言\n', heading: null, sectionLine: null },
      { text: '# 长章节\n' + Array.from({ length: 15 }, (_, i) => `正文 ${i}\n`).join(''), heading: '长章节', sectionLine: 2 },
      { text: '延续正文\n', heading: '长章节', sectionLine: 2 },
      { text: '## 同名\n第一处正文\n', heading: '同名', sectionLine: 19 },
      { text: '## 同名\n第二处正文\n', heading: '同名', sectionLine: 21 },
      { text: '# <img src=x>\n' + 'z'.repeat(5000) + '\n', heading: '<img src=x>', sectionLine: 23 },
    ]),
    '2': source([{ text: '# 新版\n合成新版正文\n', heading: '新版', sectionLine: 1 }]),
  }
  hold = false
  failOffset: number | null = null
  override detail(): KnowledgeDetail {
    return { document: { ...this.document, currentReadyRevision: '2' }, job: null,
      revisions: [{ sourceRevision: '1', sourceType: 'MARKDOWN', byteLength: 6000 }, { sourceRevision: '2', sourceType: 'MARKDOWN', byteLength: 50 }] }
  }
  respond(index: number) {
    const request = this.sent[index]!, payload = request.payload as { sourceRevision: '1' | '2'; offset: number }
    if (payload.offset === this.failOffset) {
      this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false,
        error: { code: 'KnowledgeNotFound', message: '合成源版本暂不可读' } }); return
    }
    const data = this.sources[payload.sourceRevision], offset = payload.offset
    const locator = data.locators.find(item => item.startOffset <= offset && offset < item.endOffset)
    const end = Math.min(offset + 4096, locator?.endOffset ?? data.text.length)
    const result: KnowledgePreview = { documentId: this.document.documentId, sourceRevision: payload.sourceRevision, offset,
      text: data.text.slice(offset, end), nextOffset: end < data.text.length ? end : null, locators: locator ? [locator] : [],
      parserVersion: 'text-1', normalizationVersion: 'lf-1' }
    this.reply(index, result)
  }
  override postMessage(value: unknown) {
    const request = value as Record<string, unknown>
    if (request.method !== 'knowledge.preview') { super.postMessage(value); return }
    this.sent.push(request)
    if (!this.hold) this.respond(this.sent.length - 1)
  }
}
function client(port: OutlineService) { const bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); return bridge }

it('reads the selected version outline, opens duplicate headings precisely and returns without losing the prior reader on failure', async () => {
  const port = new OutlineService(), bridge = client(port)
  render(<KnowledgePage bridge={bridge} enabled visible />)
  await screen.findByRole('button', { name: /fixture.md/ }); await click(/fixture.md/)
  await act(async () => fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '1' } }))
  await click('预览源文本')
  const before = port.sent.length
  await click('读取章节目录')
  const outline = screen.getByRole('region', { name: '资料章节目录' })
  expect(within(outline).getAllByRole('listitem')).toHaveLength(4)
  expect(within(outline).getAllByRole('button', { name: /长章节/ })).toHaveLength(1)
  expect(within(outline).getByText('<img src=x>').querySelector('img')).toBeNull()
  const requests = port.sent.slice(before).filter(row => row.method === 'knowledge.preview')
  expect(requests.map(row => (row.payload as { offset: number }).offset)).toEqual([...port.sources['1'].locators.map(row => row.startOffset), port.sources['1'].text.length])
  expect(requests.every(row => (row.payload as { sourceRevision: string }).sourceRevision === '1')).toBe(true)
  fireEvent.change(screen.getByLabelText('筛选目录标题'), { target: { value: '同名' } })
  expect(within(outline).getAllByRole('listitem')).toHaveLength(2)
  await click('同名 · 第 21 行')
  expect(screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')?.textContent).toBe('## 同名\n第二处正文\n')
  expect(port.sent.filter(row => row.method === 'knowledge.preview').at(-1)?.payload).toMatchObject({ sourceRevision: '1', offset: port.sources['1'].locators[4]!.startOffset })
  await click('返回上一处来源')
  expect(screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')?.textContent).toBe('合成前言\n')
  port.failOffset = port.sources['1'].locators[3]!.startOffset
  await click('同名 · 第 19 行')
  expect(screen.getByText('合成源版本暂不可读')).toBeTruthy()
  expect(screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')?.textContent).toBe('合成前言\n')
  expect(screen.getByRole('button', { name: '返回上一处来源' }).hasAttribute('disabled')).toBe(true)
  await act(async () => fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '2' } }))
  expect(screen.queryByRole('region', { name: '资料章节目录' })).toBeNull()
  await click('读取章节目录'); await click('新版 · 第 1 行')
  expect(port.sent.filter(row => row.method === 'knowledge.preview').at(-1)?.payload).toMatchObject({ sourceRevision: '2', offset: 0 })
  expect(screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')?.textContent).toBe(port.sources['2'].text)
  expect(port.sent.every(row => ['knowledge.list', 'knowledge.get', 'knowledge.preview', 'knowledge.searchStatus'].includes(String(row.method)))).toBe(true)
})

it('stops directory reads and discards late replies across visibility or session changes without showing partial headings', async () => {
  const port = new OutlineService(), bridge = client(port); port.hold = true
  const props = { bridge, documentId: port.document.documentId, revision: '1', disabled: false, open: async () => {} }
  const view = render(<KnowledgeSourceOutline {...props} enabled />)
  await click('读取章节目录'); await click('停止读取目录')
  await act(async () => port.respond(0))
  expect(screen.getByText('目录读取已停止。')).toBeTruthy(); expect(port.sent).toHaveLength(1)
  expect(screen.queryByRole('region', { name: '资料章节目录' })).toBeNull()
  await click('读取章节目录'); await act(async () => port.respond(1))
  expect(port.sent).toHaveLength(3)
  view.rerender(<KnowledgeSourceOutline {...props} enabled={false} />)
  await act(async () => port.respond(2))
  expect(port.sent).toHaveLength(3); expect(screen.queryByRole('region', { name: '资料章节目录' })).toBeNull()
  view.rerender(<KnowledgeSourceOutline {...props} enabled />)
  await click('读取章节目录')
  await act(async () => { port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'); port.respond(3) })
  expect(port.sent).toHaveLength(4); expect(screen.queryByRole('region', { name: '资料章节目录' })).toBeNull()
  expect(screen.queryByRole('alert')).toBeNull()
})
