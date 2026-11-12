import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeDocument } from '../bridge/knowledge'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { KnowledgePage } from './KnowledgePage'

class DocumentService extends KnowledgeService {
  hold = false
  rows: KnowledgeDocument[] = Array.from({ length: 45 }, (_, i) => i === 0 ? { ...this.document } : ({ ...this.document,
    documentId: `dddddddd-dddd-4ddd-8ddd-${String(i + 1).padStart(12, '0')}`,
    title: i < 20 ? `Other ${i}.txt` : `Budget 预算 ${i}.txt`, status: i < 43 ? 'ACTIVE' : 'ARCHIVED',
    currentReadyRevision: null, processingState: i % 2 ? 'FAILED' : 'PARSING',
  }))
  result(status: string, page: number, query = '') {
    const rows = this.rows.filter(row => row.status === status && row.title.toLowerCase().includes(query.toLowerCase()))
    return { items: rows.slice(page * 20, (page + 1) * 20), total: rows.length, page, limit: 20 }
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: { status: string; page: number; query?: string } }
    if (request.method !== 'knowledge.list') { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>)
    if (!this.hold) this.reply(this.sent.length - 1, this.result(request.payload.status, request.payload.page, request.payload.query))
  }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function setup() {
  const port = new DocumentService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />)
  await screen.findByRole('button', { name: /fixture.md/ })
  return { port, bridge, view, input: screen.getByLabelText('按文档名称查找') as HTMLInputElement }
}
it('explicitly finds names across pages and statuses, preserves source reading, and distinguishes no match from an empty library', async () => {
  const { port, input } = await setup()
  await click(/fixture.md/); await click('预览源文本')
  const before = port.sent.filter(r => r.method === 'knowledge.list').length
  fireEvent.change(input, { target: { value: 'BUDGET 预算' } })
  expect(port.sent.filter(r => r.method === 'knowledge.list')).toHaveLength(before)
  await click('查找名称')
  expect(screen.getByText(/23 份/)).toBeTruthy()
  expect(screen.getByRole('heading', { name: 'fixture.md' })).toBeTruthy()
  expect(screen.getByText(/window.pwned=1/)).toBeTruthy()
  expect(screen.getByRole('button', { name: /Budget 预算 20.txt/ }).textContent).toContain('正在解析')
  expect(screen.getByRole('button', { name: /Budget 预算 21.txt/ }).textContent).toContain('导入失败')
  await click('下一页文档')
  expect(within(screen.getByRole('list', { name: '文档列表' })).getAllByRole('button')).toHaveLength(3)
  expect(port.sent.at(-1)?.payload).toEqual({ status: 'ACTIVE', page: 1, query: 'BUDGET 预算' })
  await act(async () => fireEvent.change(screen.getByLabelText('文档状态'), { target: { value: 'ARCHIVED' } }))
  expect(screen.getByText(/2 份/)).toBeTruthy()
  expect(port.sent.filter(r => r.method === 'knowledge.list').at(-1)?.payload).toEqual({ status: 'ARCHIVED', page: 0, query: 'BUDGET 预算' })
  fireEvent.change(input, { target: { value: 'missing' } }); await click('查找名称')
  expect(screen.getByText(/当前状态下没有名称匹配/)).toBeTruthy()
  expect(screen.queryByText('没有已归档的文档。')).toBeNull()
  await click('清除名称筛选')
  expect(input.value).toBe(''); expect(screen.getByRole('button', { name: /Budget 预算 43.txt/ })).toBeTruthy()
  expect(port.sent.some(r => r.method === 'knowledge.search' || r.method === 'knowledge.answerSubmit')).toBe(false)
})
it('ignores late filter results, retains the submitted filter on navigation, and clears private queries on session change', async () => {
  const { port, bridge, view, input } = await setup(); port.hold = true
  fireEvent.change(input, { target: { value: 'old' } }); await click('查找名称')
  const old = port.sent.length - 1
  fireEvent.change(input, { target: { value: '预算' } }); await click('查找名称')
  const latest = port.sent.length - 1
  await act(async () => { port.reply(latest, port.result('ACTIVE', 0, '预算')); port.reply(old, port.result('ACTIVE', 0, 'old')) })
  expect(screen.getByText(/23 份/)).toBeTruthy()
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  expect(port.sent.filter(r => r.method === 'knowledge.list').at(-1)?.payload).toEqual({ status: 'ACTIVE', page: 0, query: '预算' })
  const read = port.sent.length - 1
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
  await act(async () => port.reply(read, port.result('ACTIVE', 0, '预算')))
  expect(input.value).toBe(''); expect(screen.queryByText(/^名称筛选：/)).toBeNull()
  expect(screen.queryByRole('button', { name: /Budget 预算 20.txt/ })).toBeNull()
})
