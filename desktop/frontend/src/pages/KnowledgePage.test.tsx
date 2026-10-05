import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
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
