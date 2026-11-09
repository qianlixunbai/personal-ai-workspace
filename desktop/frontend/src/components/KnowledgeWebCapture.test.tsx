import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgePage } from '../pages/KnowledgePage'
import { KnowledgeService } from '../test/knowledgeFixtures'

class WebCaptureService extends KnowledgeService {
  id = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'; unknownSave = false; holdFetch = false
  result = { requestedUrl: 'https://example.com/source', finalUrl: 'https://example.com/source', hostname: 'example.com', title: '<img>网页标题', acquiredAt: '2026-11-09T00:00:00Z', contentType: 'text/html', extractionVersion: 'web-extract-1', text: '<script>预算😀</script> [link](https://example.com/)', titleTruncated: false, textTruncated: true }
  fetched() { return { operationId: this.id, state: 'SUCCEEDED', result: this.result, error: null } }
  override postMessage(value: unknown) {
    const request = value as { method: string }
    if (!request.method.startsWith('web.')) { super.postMessage(value); return }
    this.sent.push(value as typeof this.sent[number])
    if (request.method === 'web.fetchSubmit' && this.holdFetch) return
    const result = request.method === 'web.fetchSubmit' ? { outcome: 'ACCEPTED', operationId: this.id, operation: this.fetched() }
      : request.method === 'web.saveKnowledge' || request.method === 'web.knowledgeState'
        ? request.method === 'web.saveKnowledge' && this.unknownSave ? { outcome: 'UNKNOWN', requestId: this.document.requestId, job: null }
          : { outcome: 'ACCEPTED', requestId: this.document.requestId, job: this.job() }
        : this.fetched()
    this.reply(this.sent.length - 1, result)
  }
}
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); vi.restoreAllMocks() })
const click = async (name: string) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function setup(port = new WebCaptureService()) {
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ })
  return { port, bridge, view }
}
it('fetch preview requires explicit actions, saves only the captured operation and opens the resulting local document', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'), { port } = await setup()
  const input = screen.getByLabelText('公共网页网址'); fireEvent.change(input, { target: { value: port.result.requestedUrl } })
  expect(port.sent.some(request => String(request.method).startsWith('web.'))).toBe(false)
  fireEvent.compositionStart(input); await act(async () => fireEvent.submit(input.closest('form')!)); fireEvent.compositionEnd(input)
  expect(port.sent.some(request => request.method === 'web.fetchSubmit')).toBe(false)
  await click('获取并预览网页')
  expect(screen.getByText(port.result.text)).toBeTruthy(); expect(document.querySelector('script,img,a')).toBeNull()
  expect(screen.getByText(/正文已截断，当前摘录不完整/)).toBeTruthy()
  expect(port.sent.some(request => request.method === 'web.saveKnowledge')).toBe(false)
  fireEvent.change(input, { target: { value: 'https://example.com/different' } })
  await click('保存网页摘录…'); expect(document.activeElement?.textContent).toBe('取消'); await click('取消')
  expect(port.sent.some(request => request.method === 'web.saveKnowledge')).toBe(false)
  await click('保存网页摘录…'); await click('确认保存摘录')
  expect(port.sent.filter(request => request.method === 'web.saveKnowledge')).toHaveLength(1)
  expect(port.sent.find(request => request.method === 'web.saveKnowledge')?.payload).toEqual({ operationId: port.id })
  await click('打开已保存资料'); await click('预览源文本')
  expect(port.sent.filter(request => request.method === 'knowledge.get').at(-1)?.payload).toEqual({ documentId: port.document.documentId })
  expect(port.sent.filter(request => request.method === 'knowledge.preview').at(-1)?.payload).toEqual({ documentId: port.document.documentId, sourceRevision: '1', offset: 0 })
  expect(port.sent.filter(request => request.method === 'web.fetchSubmit')).toHaveLength(1)
  expect(storage).not.toHaveBeenCalled(); expect(location.href).not.toContain('example.com')
})
it('unknown save can only check its result and late fetch responses cannot restore a departed page or session', async () => {
  const { port, bridge, view } = await setup(); port.unknownSave = true
  const input = screen.getByLabelText('公共网页网址')
  fireEvent.change(input, { target: { value: port.result.requestedUrl } }); await click('获取并预览网页')
  await click('保存网页摘录…'); await click('确认保存摘录')
  expect(screen.getByText(/保存结果尚未确认/)).toBeTruthy(); expect(screen.queryByRole('button', { name: '保存网页摘录…' })).toBeNull()
  await click('检查保存结果'); expect(screen.getByRole('button', { name: '打开已保存资料' })).toBeTruthy()
  expect(port.sent.filter(request => request.method === 'web.saveKnowledge')).toHaveLength(1)
  expect(port.sent.filter(request => request.method === 'web.knowledgeState')).toHaveLength(1)
  port.holdFetch = true; await click('获取并预览网页'); const pending = port.sent.indexOf(port.sent.filter(request => request.method === 'web.fetchSubmit').at(-1)!)
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  await act(async () => port.reply(pending, { outcome: 'ACCEPTED', operationId: port.id, operation: port.fetched() }))
  expect((input as HTMLInputElement).value).toBe(''); expect(screen.queryByText(port.result.text)).toBeNull()
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
  expect(screen.queryByRole('button', { name: '打开已保存资料' })).toBeNull()
})
