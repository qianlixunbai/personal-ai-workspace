import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeDocument } from '../bridge/knowledge'
import type { KnowledgeScope } from '../bridge/knowledgeScope'
import type { KnowledgeWorkset } from '../bridge/knowledgeWorkset'
import { KnowledgeService, knowledgeDocument } from '../test/knowledgeFixtures'
import { KnowledgeSearch } from './KnowledgeSearch'

const docs = Array.from({ length: 4 }, (_, index): KnowledgeDocument => ({ ...knowledgeDocument(),
  documentId: `dddddddd-dddd-4ddd-8ddd-${String(index + 1).padStart(12, '0')}`, title: `资料${index + 1}.md`, requestId: null }))
const snapshot = (): KnowledgeWorkset => ({ query: '预算', sources: docs.map(doc => ({ documentId: doc.documentId,
  sourceRevision: '1', title: doc.title, createdAt: doc.createdAt })), savedAt: '2026-11-11T00:00:00Z' })
// Synthetic file gateway only: this checks bridge/UI recovery across new clients,
// not Windows dialogs, on-disk persistence, or the native protected writer.
class WorksetPort extends KnowledgeService {
  documents = docs.map(doc => ({ ...doc }))
  constructor(readonly storage: { workset: KnowledgeWorkset | null }) { super() }
  holdOpen = false
  unknownSave = false
  invalidOpen = false
  revisions = new Map<string, string[]>()
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }
    if (!['knowledge.saveSearchWorkset', 'knowledge.openSearchWorkset', 'knowledge.list', 'knowledge.get', 'knowledge.search'].includes(request.method)) {
      super.postMessage(value); return
    }
    this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
    if (request.method === 'knowledge.saveSearchWorkset') {
      if (this.unknownSave) { this.emit({ version: 1, sessionId: this.sent[index]!.sessionId, requestId: this.sent[index]!.requestId,
        ok: false, error: { code: 'RuntimeUnavailable', message: '连接中断' } }); return }
      this.storage.workset = { query: request.payload.query as string, savedAt: '2026-11-11T00:00:00Z',
        sources: (request.payload.sources as KnowledgeScope[]).map(scope => { const doc = this.documents.find(doc => doc.documentId === scope.documentId)!
          return { ...scope, title: doc.title, createdAt: doc.createdAt } }) }
      this.reply(index, { outcome: 'SAVED' })
    } else if (request.method === 'knowledge.openSearchWorkset') {
      if (this.holdOpen) return
      this.reply(index, { outcome: 'OPENED', workset: this.invalidOpen ? { ...this.storage.workset, taskId: 'untrusted' } : this.storage.workset })
    } else if (request.method === 'knowledge.list') {
      const items = this.documents.filter(doc => doc.status === request.payload.status)
      this.reply(index, { items, total: items.length, page: request.payload.page, limit: 20 })
    } else if (request.method === 'knowledge.get') {
      const document = this.documents.find(doc => doc.documentId === request.payload.documentId)!
      this.reply(index, { document, job: null, revisions: (this.revisions.get(document.documentId) ?? ['1', ...(document.currentReadyRevision && document.currentReadyRevision !== '1' ? [document.currentReadyRevision] : [])])
        .map(sourceRevision => ({ sourceRevision, sourceType: 'MARKDOWN', byteLength: 20 })) })
    } else this.reply(index, { hits: [] })
  }
  requests(method: string) { return this.sent.filter(request => request.method === method) }
}
const clients: WorkspaceClient[] = []
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
const query = () => screen.getByLabelText('检索关键词') as HTMLInputElement
function setup(storage: { workset: KnowledgeWorkset | null }) {
  const port = new WorksetPort(storage), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const props = { bridge, enabled: true, visible: true, corpusEpoch: 0, currentDocument: docs[0]!, open: vi.fn(async () => {}) }
  const view = render(<KnowledgeSearch {...props} />)
  // Details is closed by default, as it is in the product.
  fireEvent.click(screen.getByText('保存或恢复检索工作集')); screen.getByText('保存或恢复检索工作集').closest('details')!.open = true
  return { port, bridge, props, view }
}

it('saves only explicit pinned inputs and restores into a fresh client after review without replaying work', async () => {
  const storage = { workset: null as KnowledgeWorkset | null }
  const first = setup(storage)
  await click('加入检索范围'); first.view.rerender(<KnowledgeSearch {...first.props} currentDocument={docs[1]!} />); await click('加入检索范围')
  fireEvent.change(query(), { target: { value: '预算' } }); await click('保存检索工作集…')
  expect(first.port.requests('knowledge.saveSearchWorkset')[0]!.payload).toEqual({ query: '预算', sources: docs.slice(0, 2).map(doc => ({ documentId: doc.documentId, sourceRevision: '1' })) })
  expect(screen.getByText(/工作集已保存为新文件/)).toBeTruthy()
  first.view.unmount(); first.bridge.dispose()
  const next = setup(storage); next.port.documents[0]!.title = '改名后的资料.md'
  fireEvent.change(query(), { target: { value: '当前草稿' } }); await click('打开检索工作集…')
  expect(query().value).toBe('当前草稿')
  expect(within(screen.getByRole('dialog')).getByText(/名称已由/)).toBeTruthy()
  await click('替换检索清单，不执行查询')
  expect(query().value).toBe('预算'); expect(screen.getByRole('list', { name: '已选检索资料' }).children).toHaveLength(2)
  expect(next.port.requests('knowledge.search')).toHaveLength(0)
  expect(next.port.sent.every(request => ['knowledge.searchStatus', 'knowledge.openSearchWorkset', 'knowledge.list', 'knowledge.get'].includes(request.method as string))).toBe(true)
  await click('检索')
  expect(next.port.requests('knowledge.search').map(request => request.payload)).toEqual(docs.slice(0, 2).map(doc => ({ query: '预算', limit: 10, scope: { documentId: doc.documentId, sourceRevision: '1' } })))
})

it('retains missing, changed, archived and different-identity entries as blocked until explicit scope edits', async () => {
  const { port, props, view } = setup({ workset: snapshot() })
  port.documents = [{ ...docs[0]!, currentReadyRevision: '2' }, { ...docs[2]!, status: 'ARCHIVED' }, { ...docs[3]!, createdAt: '2026-11-10T00:00:00Z' }]
  await click('打开检索工作集…')
  const dialog = within(screen.getByRole('dialog'))
  expect(dialog.getByText(/当前可用版本为 2/)).toBeTruthy(); expect(dialog.getByText(/未找到这份文档/)).toBeTruthy()
  expect(dialog.getByText(/文档已归档/)).toBeTruthy(); expect(dialog.getByText(/创建信息与文件不符/)).toBeTruthy()
  expect(port.requests('knowledge.get').map(request => request.payload)).toEqual([docs[0]!, docs[2]!].map(doc => ({ documentId: doc.documentId })))
  await click('替换检索清单，不执行查询')
  expect((screen.getByRole('button', { name: '检索' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: '准备用所选资料逐份提问' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { fireEvent.submit(query().closest('form')!) }); expect(port.requests('knowledge.search')).toHaveLength(0)
  for (const doc of docs.slice(1)) await click(`移出检索范围：${doc.title}`)
  view.rerender(<KnowledgeSearch {...props} currentDocument={port.documents[0]!} />)
  await click('更新当前资料的所选版本'); await click('检索')
  expect(port.requests('knowledge.search')[0]!.payload).toEqual({ query: '预算', limit: 10, scope: { documentId: docs[0]!.documentId, sourceRevision: '2' } })
  await click(`移出检索范围：${docs[0]!.title}`)
  expect((screen.getByRole('button', { name: '检索' }) as HTMLButtonElement).disabled).toBe(true)
})

it('preserves newer drafts on malformed or late opens, invalidates stale review, and never retries an unknown save', async () => {
  const { port, props, view } = setup({ workset: snapshot() })
  await click('加入检索范围'); fireEvent.change(query(), { target: { value: '新关键词' } })
  port.unknownSave = true; await click('保存检索工作集…')
  expect(screen.getByRole('alert').textContent).toContain('保存结果未确认'); expect(port.requests('knowledge.saveSearchWorkset')).toHaveLength(1)
  port.invalidOpen = true; await click('打开检索工作集…')
  expect(query().value).toBe('新关键词'); expect(screen.queryByRole('dialog')).toBeNull(); expect(port.requests('knowledge.list')).toHaveLength(0)
  port.invalidOpen = false; port.holdOpen = true; await click('打开检索工作集…'); const opened = port.sent.length - 1
  fireEvent.change(query(), { target: { value: '操作期间的新草稿' } })
  await act(async () => { port.reply(opened, { outcome: 'OPENED', workset: port.storage.workset }) })
  expect(query().value).toBe('操作期间的新草稿'); expect(screen.queryByRole('dialog')).toBeNull(); expect(port.requests('knowledge.list')).toHaveLength(0)
  port.holdOpen = false; await click('打开检索工作集…'); expect(screen.getByRole('dialog')).toBeTruthy()
  view.rerender(<KnowledgeSearch {...props} corpusEpoch={1} />); expect(screen.queryByRole('dialog')).toBeNull()
  await click('打开检索工作集…'); fireEvent.change(query(), { target: { value: '确认前的新草稿' } })
  await click('替换检索清单，不执行查询'); expect(query().value).toBe('确认前的新草稿')
  expect(port.requests('knowledge.search')).toHaveLength(0); expect(port.requests('knowledge.saveSearchWorkset')).toHaveLength(1)
})

it('reviews explicit version replacements and exclusions before committing one pinned scope, leaving the original file and draft intact on cancel', async () => {
  const storage = { workset: snapshot() }, original = structuredClone(storage.workset)
  const { port } = setup(storage)
  port.documents = [{ ...docs[0]!, title: '已更新资料.md', currentReadyRevision: '2' }, { ...docs[2]!, status: 'ARCHIVED' }, { ...docs[3]!, createdAt: '2026-11-10T00:00:00Z' }]
  port.revisions.set(docs[0]!.documentId, ['2']) // The saved version itself is unavailable.
  fireEvent.change(query(), { target: { value: '保留当前草稿' } })
  await click('打开检索工作集…')
  let row = within(screen.getByRole('group', { name: `恢复选择：${docs[0]!.title}` }))
  expect(row.getByRole('button', { name: '保留文件版本 1' }).getAttribute('aria-pressed')).toBe('true')
  expect(screen.getAllByRole('button', { name: '采用核对版本 2' })).toHaveLength(1)
  await act(async () => fireEvent.click(row.getByRole('button', { name: '采用核对版本 2' })))
  expect(query().value).toBe('保留当前草稿'); expect(port.requests('knowledge.search')).toHaveLength(0)
  await click('取消')
  expect(query().value).toBe('保留当前草稿'); expect(storage.workset).toEqual(original)
  await click('打开检索工作集…')
  row = within(screen.getByRole('group', { name: `恢复选择：${docs[0]!.title}` }))
  expect(row.getByRole('button', { name: '保留文件版本 1' }).getAttribute('aria-pressed')).toBe('true')
  await act(async () => fireEvent.click(row.getByRole('button', { name: '采用核对版本 2' })))
  for (const doc of docs.slice(1)) {
    const unavailable = within(screen.getByRole('group', { name: `恢复选择：${doc.title}` }))
    expect(unavailable.queryByRole('button', { name: /采用核对版本/ })).toBeNull()
    await act(async () => fireEvent.click(unavailable.getByRole('button', { name: '移出本次恢复' })))
  }
  expect(screen.getByText(/将恢复 1 \/ 4 份资料，0 项尚不可用/)).toBeTruthy()
  // The accepted choice is the revision the user saw, even if metadata later changes.
  port.documents[0]!.currentReadyRevision = '3'
  await click('替换检索清单，不执行查询')
  const selected = screen.getByRole('list', { name: '已选检索资料' })
  expect(selected.children).toHaveLength(1); expect(selected.textContent).toContain('已更新资料.md · 源版本 2')
  expect(port.requests('knowledge.search')).toHaveLength(0); expect(storage.workset).toEqual(original)
  await click('检索')
  expect(port.requests('knowledge.search').map(request => request.payload)).toEqual([{ query: '预算', limit: 10, scope: { documentId: docs[0]!.documentId, sourceRevision: '2' } }])
  expect(port.sent.some(request => ['knowledge.saveSearchWorkset', 'knowledge.archive', 'knowledge.restore', 'knowledge.delete', 'knowledge.answerSubmit'].includes(String(request.method)))).toBe(false)
})

it('allows reversible per-row choices and an explicitly empty scope without broadening to all documents', async () => {
  const { port } = setup({ workset: snapshot() })
  port.documents[0]!.currentReadyRevision = '2'
  await click('打开检索工作集…')
  const first = within(screen.getByRole('group', { name: `恢复选择：${docs[0]!.title}` }))
  await act(async () => fireEvent.click(first.getByRole('button', { name: '采用核对版本 2' })))
  await act(async () => fireEvent.click(first.getByRole('button', { name: '保留文件版本 1' })))
  expect(screen.getByText(/将恢复 4 \/ 4 份资料，1 项尚不可用/)).toBeTruthy()
  for (const doc of docs) await act(async () => fireEvent.click(within(screen.getByRole('group', { name: `恢复选择：${doc.title}` })).getByRole('button', { name: '移出本次恢复' })))
  expect(screen.getByText(/范围将清空，不会改成全部资料/)).toBeTruthy()
  await act(async () => fireEvent.click(first.getByRole('button', { name: '保留文件版本 1' })))
  expect(screen.getByText(/将恢复 1 \/ 4 份资料，1 项尚不可用/)).toBeTruthy()
  await act(async () => fireEvent.click(first.getByRole('button', { name: '移出本次恢复' })))
  await click('替换检索清单，不执行查询')
  expect(screen.getByRole('list', { name: '已选检索资料' }).children).toHaveLength(0)
  expect((screen.getByRole('button', { name: '检索' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: '准备用所选资料逐份提问' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: '保存检索工作集…' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => fireEvent.submit(query().closest('form')!))
  expect(port.requests('knowledge.search')).toHaveLength(0)
})
