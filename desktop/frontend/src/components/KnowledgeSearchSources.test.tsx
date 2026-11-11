import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeScope } from '../bridge/knowledgeScope'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'
import { KnowledgeService, knowledgeDocument } from '../test/knowledgeFixtures'
import { KnowledgeSearch } from './KnowledgeSearch'

const docs = Array.from({ length: 11 }, (_, i) => ({ ...knowledgeDocument(), documentId: `dddddddd-dddd-4ddd-8ddd-${String(i + 1).padStart(12, '0')}`, title: `资料${i + 1}.md` }))
class SourcesPort extends KnowledgeService {
  holdSearch = false
  holdStatus = false
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: { scope?: KnowledgeScope } }
    if (request.method === 'knowledge.searchStatus' && this.holdStatus) { this.sent.push(value as Record<string, unknown>); return }
    if (request.method !== 'knowledge.search') { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>)
    if (this.holdSearch) return
    const index = this.sent.length - 1, scope = request.payload.scope
    if (scope?.documentId === docs[1]!.documentId) {
      this.emit({ version: 1, sessionId: this.sent[index]!.sessionId, requestId: this.sent[index]!.requestId,
        ok: false, error: { code: 'KnowledgeRevisionConflict', message: '该资料已更新，请重新选择版本。' } }); return
    }
    this.reply(index, { hits: scope?.documentId === docs[2]!.documentId ? [] : [{ ...this.hit(), documentId: scope?.documentId ?? docs[0]!.documentId, title: docs[0]!.title }] })
  }
  searches() { return this.sent.filter(request => request.method === 'knowledge.search').map(request => request.payload) }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
function setup() {
  const port = new SourcesPort(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const props = { bridge, enabled: true, visible: true, corpusEpoch: 0, currentDocument: docs[0]!, open: vi.fn(async (_hit: KnowledgeSearchHit) => {}) }
  const view = render(<KnowledgeSearch {...props} />)
  return { port, props, view }
}

it('queries a chosen set at pinned versions, keeps partial failures distinct from empty hits, and never broadens an empty scope', async () => {
  const { port, props, view } = setup()
  for (const doc of docs.slice(0, 3)) { view.rerender(<KnowledgeSearch {...props} currentDocument={doc} />); await click('加入检索范围') }
  view.rerender(<KnowledgeSearch {...props} currentDocument={{ ...docs[1]!, currentReadyRevision: '2' }} />)
  fireEvent.change(screen.getByLabelText('检索关键词'), { target: { value: '预算' } }); await click('检索')
  expect(port.searches()).toEqual(docs.slice(0, 3).map(doc => ({ query: '预算', limit: 10, scope: { documentId: doc.documentId, sourceRevision: '1' } })))
  expect(screen.getByRole('heading', { name: '所选资料检索 · 2 / 3 份查询成功' })).toBeTruthy()
  expect(within(screen.getByRole('region', { name: '检索分组：资料2.md' })).getByRole('alert').textContent).toContain('已更新')
  expect(within(screen.getByRole('region', { name: '检索分组：资料3.md' })).getByText('本份资料没有满足全部关键词的片段。')).toBeTruthy()
  await click('打开此位置'); expect(props.open.mock.calls[0]?.[0]).toMatchObject({ documentId: docs[0]!.documentId, sourceRevision: '1' })
  for (const doc of docs.slice(0, 3)) await click(`移出检索范围：${doc.title}`)
  expect((screen.getByRole('button', { name: '检索' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { fireEvent.submit(screen.getByLabelText('检索关键词').closest('form')!) })
  expect(port.searches()).toHaveLength(3)
  await click('使用全部资料'); await click('检索')
  expect(port.searches().at(-1)).toEqual({ query: '预算', limit: 10 })
})

it('drops obsolete group responses and stops further reads on corpus or session changes while bounding explicit selections', async () => {
  const { port, props, view } = setup()
  for (const doc of docs.slice(0, 10)) { view.rerender(<KnowledgeSearch {...props} currentDocument={doc} />); await click('加入检索范围') }
  view.rerender(<KnowledgeSearch {...props} currentDocument={docs[10]!} />)
  expect((screen.getByRole('button', { name: '加入检索范围' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.getByRole('list', { name: '已选检索资料' }).children).toHaveLength(10)
  fireEvent.change(screen.getByLabelText('检索关键词'), { target: { value: '预算' } })
  port.holdSearch = true; await click('检索'); const first = port.sent.length - 1
  view.rerender(<KnowledgeSearch {...props} corpusEpoch={1} />)
  await act(async () => { port.reply(first, { hits: [{ ...port.hit(), documentId: docs[0]!.documentId }] }) })
  expect(port.searches()).toHaveLength(1); expect(screen.queryByRole('region', { name: /检索分组/ })).toBeNull()
  port.holdStatus = true
  view.rerender(<KnowledgeSearch {...props} corpusEpoch={1} enabled={false} />)
  view.rerender(<KnowledgeSearch {...props} corpusEpoch={1} />)
  const statusRequest = port.sent.at(-1)!
  await click('检索'); const second = port.sent.length - 1
  await act(async () => { port.emit({ version: 1, sessionId: statusRequest.sessionId, requestId: statusRequest.requestId,
    ok: false, error: { code: 'RuntimeUnavailable', message: '索引状态读取失败。' } }); port.reply(second, { hits: [] }) })
  expect(port.searches()).toHaveLength(2); expect(screen.queryByRole('region', { name: /检索分组/ })).toBeNull()
  await click('检索'); const third = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(third, { hits: [] }) })
  expect(port.searches()).toHaveLength(3); expect(screen.queryByRole('list', { name: '已选检索资料' })).toBeNull()
  expect((screen.getByLabelText('检索关键词') as HTMLInputElement).value).toBe('')
})
