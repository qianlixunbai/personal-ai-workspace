import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeJob } from '../bridge/knowledge'
import { Port } from '../test/fixtures'
import { KnowledgeBatchImport } from './KnowledgeBatchImport'

const ids = [1, 2, 3].map(n => `bbbbbbbb-bbbb-4bbb-8bbb-${String(n).padStart(12, '0')}`)
class BatchPort extends Port {
  holdSelection = false
  holdUpload = false
  reconcile = false
  job(id: string, state: 'READY' | 'PENDING' = 'READY'): KnowledgeJob {
    return { requestId: id, documentId: id.replace(/^b/, 'a'), state, sourceRevision: state === 'READY' ? '1' : null, errorCode: null }
  }
  selection() { return { files: ids.map((requestId, i) => ({ requestId, filename: `资料${i + 1}.md`, byteLength: 20 })) } }
  override postMessage(value: unknown) {
    super.postMessage(value)
    const request = value as { method: string; payload: { requestId?: string } }, index = this.sent.length - 1
    const id = request.payload.requestId!
    if (request.method === 'knowledge.selectFiles' && !this.holdSelection) this.reply(index, this.selection())
    if (request.method === 'knowledge.discardSelectedFiles') this.reply(index, { cleared: true })
    if (request.method === 'knowledge.importSelected' && !this.holdUpload) this.reply(index, id === ids[1]
      ? { outcome: 'UNKNOWN', requestId: id, job: null }
      : { outcome: 'ACCEPTED', requestId: id, job: this.job(id, id === ids[0] ? 'PENDING' : 'READY') })
    if (request.method === 'knowledge.importState') {
      if (id === ids[1] && !this.reconcile) this.emit({ version: 1, sessionId: this.sent[index]!.sessionId, requestId: this.sent[index]!.requestId,
        ok: false, error: { code: 'KnowledgeNotFound', message: '尚未查到原请求。' } })
      else this.reply(index, this.job(id))
    }
  }
  uploads() { return this.sent.filter(r => r.method === 'knowledge.importSelected').map(r => (r.payload as { requestId: string }).requestId) }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); vi.useRealTimers() })
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
function setup(port = new BatchPort()) {
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const props = { bridge, enabled: true, visible: true, onActive: vi.fn(), changed: vi.fn(), open: vi.fn() }
  const view = render(<KnowledgeBatchImport {...props} />)
  return { port, props, view }
}

it('reviews selected files, serializes parsing, stops on unknown, and reconciles without uploading twice', async () => {
  vi.useFakeTimers()
  const { port, props } = setup()
  await click('选择多份文件…'); expect(port.uploads()).toEqual([])
  expect(screen.getByRole('list', { name: '待导入清单' }).textContent).toContain('资料3.md')
  await click('确认并开始导入'); expect(port.uploads()).toEqual([ids[0]])
  await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
  expect(port.uploads()).toEqual([ids[0], ids[1]])
  expect((screen.getByRole('button', { name: '继续导入剩余文件' }) as HTMLButtonElement).disabled).toBe(true)
  await click('检查 资料2.md 的原请求')
  expect(screen.getByRole('status').textContent).toContain('仍无法确认')
  expect(port.uploads()).toEqual([ids[0], ids[1]])
  port.reconcile = true; await click('检查 资料2.md 的原请求')
  expect(port.uploads()).toEqual([ids[0], ids[1]])
  await click('继续导入剩余文件'); expect(port.uploads()).toEqual(ids)
  await click('查看 资料3.md'); expect(props.open).toHaveBeenCalledWith(port.job(ids[2]!).documentId)
  await click('结束本批'); expect(screen.queryByRole('list', { name: '待导入清单' })).toBeNull()
  expect(port.sent.filter(r => r.method === 'knowledge.discardSelectedFiles')).toHaveLength(1)
})

it('pauses when hidden and discards late old-session selection and upload responses', async () => {
  vi.useFakeTimers()
  const { port, props, view } = setup()
  port.holdSelection = true; await click('选择多份文件…')
  const oldPick = port.sent.length - 1
  act(() => port.session('22222222-2222-4222-8222-222222222222'))
  act(() => port.reply(oldPick, port.selection()))
  expect(screen.queryByRole('list', { name: '待导入清单' })).toBeNull()
  port.holdSelection = false; await click('选择多份文件…'); await click('确认并开始导入')
  view.rerender(<KnowledgeBatchImport {...props} visible={false} />)
  await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
  expect(port.uploads()).toEqual([ids[0]])
  view.rerender(<KnowledgeBatchImport {...props} />)
  port.holdUpload = true; await click('继续导入剩余文件')
  await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
  const oldUpload = port.sent.length - 1
  expect(port.uploads()).toEqual([ids[0], ids[1]])
  act(() => port.session('33333333-3333-4333-8333-333333333333'))
  act(() => port.reply(oldUpload, { outcome: 'ACCEPTED', requestId: ids[1], job: port.job(ids[1]!) }))
  await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
  expect(port.uploads()).toEqual([ids[0], ids[1]])
  expect(screen.queryByRole('list', { name: '待导入清单' })).toBeNull()
})
