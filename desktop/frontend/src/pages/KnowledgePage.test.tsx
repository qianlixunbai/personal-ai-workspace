import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { KnowledgePage } from './KnowledgePage'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgeService } from '../test/knowledgeFixtures'
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
  const port = new KnowledgeService(); port.unknown = true; await setup(port); await click('导入 TXT / Markdown')
  expect(port.sent.filter(r => r.method === 'knowledge.import')).toHaveLength(1); await click('检查导入状态'); expect(port.sent.filter(r => r.method === 'knowledge.import')).toHaveLength(1)
  expect(port.sent.find(r => r.method === 'knowledge.importState')?.payload).toEqual({ requestId: port.document.requestId })
})
it('session rotation clears loaded source and does not persist content or IDs', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'); const { port } = await setup(); await click(/fixture.md/); await click('预览源文本')
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc')); expect(screen.queryByText(/window.pwned=1/)).toBeNull(); expect(storage).not.toHaveBeenCalled()
  expect(location.hash).not.toContain(port.document.documentId)
})
