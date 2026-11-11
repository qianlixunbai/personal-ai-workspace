import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { OperationPage } from './OperationPage'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { Port, status } from '../test/fixtures'
// Vision owns its picker/status flow in its own integrated fixture; these tests protect text editing.
vi.mock('../components/VisionPanel', () => ({ VisionPanel: () => null }))
const id = '33333333-3333-4333-8333-333333333333'
const memory = { memoryId: '44444444-4444-4444-8444-444444444444', revision: '9007199254740993', position: 0, title: '<img onerror=alert(1)> &lt;上下文\u202e' }
const queued = { operationId: id, status: 'QUEUED', result: null, error: null }
const result = { ...queued, status: 'SUCCEEDED', result: '<script>alert(1)</script><img onerror=x> &lt;\u202e完成' }
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers(); localStorage.clear(); history.replaceState(null, '', '/'); vi.restoreAllMocks() })
function page(kind: 'assistant' | 'translate' = 'assistant') {
  const port = new Port(); const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<OperationPage kind={kind} bridge={bridge} enabled status={status} />)
  return { port, bridge, input: screen.getByRole('textbox', { name: kind === 'assistant' ? '问题' : '待翻译文本' }) }
}
async function reply(port: Port, value: unknown, index = port.sent.length - 1) { await act(async () => { port.reply(index, value) }) }
async function reject(port: Port, code: string, message: string, index = port.sent.length - 1) {
  const request = port.sent[index]!
  await act(async () => { port.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code, message } }) })
}
async function choose(port: Port) {
  fireEvent.click(screen.getByRole('button', { name: '选择记忆…' }))
  expect(port.sent.at(-1)?.method).toBe('assistant.selectMemories')
  await reply(port, { changed: true, selectedMemoryRefs: [memory] })
}
it('formats an assistant answer while copying the original operation and leaves translation literal', async () => {
  const { port, input } = page()
  const markdown = '# 合成回答\n\n**完整原文**\n\n```txt\n  保留缩进\n```'
  fireEvent.change(input, { target: { value: '合成问题' } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' }))
  await reply(port, { ...result, result: markdown })
  expect(screen.getByRole('heading', { name: '合成回答' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '查看原文' }))
  expect(screen.getByLabelText('回答原文').textContent).toBe(markdown)
  fireEvent.click(screen.getByRole('button', { name: '复制结果' }))
  expect(port.sent.at(-1)).toMatchObject({ method: 'operations.copyResult', payload: { operationId: id } })
  await reply(port, { copied: true })
  cleanup()
  const translation = page('translate')
  fireEvent.change(translation.input, { target: { value: '合成翻译原文' } }); fireEvent.click(screen.getByRole('button', { name: '开始翻译' }))
  await reply(translation.port, { ...result, result: markdown })
  expect(document.querySelector('.operation-result')?.textContent).toBe(markdown)
  expect(screen.queryByRole('button', { name: '查看原文' })).toBeNull()
})
it('displays explicit title as text, keeps decimal revision exact, clears selection immediately after admission and next Ask has zero memories', async () => {
  const { port, input } = page(); await choose(port)
  expect(screen.getByText(memory.title).querySelector('img')).toBeNull()
  fireEvent.change(input, { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' }))
  expect(port.sent.at(-1)?.payload).toEqual({ mode: 'Ask', text: 'synthetic', selectedMemoryRefs: [{ memoryId: memory.memoryId, revision: memory.revision, position: 0 }] })
  await reply(port, result); expect(screen.getByText('仅使用你本次选择的记忆')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '发送问题' })); expect((port.sent.at(-1)?.payload as { selectedMemoryRefs: unknown[] }).selectedMemoryRefs).toEqual([])
  await reply(port, result)
})
it('retains stale selection and blocks submit until explicit reselect or clear', async () => {
  const { port, input } = page(); await choose(port)
  fireEvent.change(input, { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' }))
  const request = port.sent.at(-1)!
  await act(async () => { port.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: 'MemorySelectionStale', message: 'Review selected Memory.' } }) })
  expect(screen.getByText(memory.title)).toBeTruthy(); expect(screen.getByRole('button', { name: '发送问题' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '清除记忆' })); expect(screen.getByRole('button', { name: '发送问题' }).hasAttribute('disabled')).toBe(false)
})
it('POST timeout blocks resending, mode changes and selection changes while retaining unconfirmed input', async () => {
  vi.useFakeTimers(); const { port, input } = page(); await choose(port)
  fireEvent.change(input, { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
  expect(screen.getByText(/执行结果待确认，不会自动重发/)).toBeTruthy(); expect(screen.getByText(memory.title)).toBeTruthy()
  expect(screen.getByRole('button', { name: '发送问题' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('button', { name: '总结文字' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('button', { name: '清除记忆' }).hasAttribute('disabled')).toBe(true)
  expect((input as HTMLTextAreaElement).readOnly).toBe(true)
  expect(screen.queryByRole('button', { name: '核对原任务状态' })).toBeNull()
  expect(screen.getByText(/尚无可核对的任务编号/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: '发送问题' })); fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true })
  fireEvent.click(screen.getByRole('button', { name: '总结文字' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
  expect(port.sent.filter(x => x.method === 'assistant.submit')).toHaveLength(1)
  expect(port.sent.some(x => x.method === 'operations.get')).toBe(false)
})
it('summary status failures can be checked by original ID without replay and missing entries stay unresolved', async () => {
  vi.useFakeTimers(); const { port, input } = page()
  fireEvent.click(screen.getByRole('button', { name: '总结文字' }))
  fireEvent.change(input, { target: { value: '需要保留的合成摘要原文' } })
  fireEvent.click(screen.getByRole('button', { name: '生成摘要' })); await reply(port, queued)
  await act(async () => { await vi.advanceTimersByTimeAsync(350) })
  expect(port.sent.at(-1)).toMatchObject({ method: 'operations.get', payload: { operationId: id } })
  await reject(port, 'RuntimeUnavailable', '合成读取失败')
  const before = port.sent.length
  await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
  expect(port.sent).toHaveLength(before)
  expect(screen.getByRole('button', { name: '生成摘要' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '核对原任务状态' }))
  fireEvent.click(screen.getByRole('button', { name: '正在核对…' }))
  expect(port.sent).toHaveLength(before + 1)
  await reject(port, 'OPERATION_NOT_FOUND', '合成任务已过期')
  expect(screen.getByText('合成任务已过期')).toBeTruthy()
  expect(screen.getByRole('button', { name: '生成摘要' }).hasAttribute('disabled')).toBe(true)
  fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true })
  fireEvent.click(screen.getByRole('button', { name: '核对原任务状态' }))
  await reply(port, { ...queued, status: 'RUNNING' })
  await act(async () => { await vi.advanceTimersByTimeAsync(350) })
  await reply(port, result)
  expect(screen.getByRole('button', { name: '生成摘要' }).hasAttribute('disabled')).toBe(false)
  expect(screen.getByRole('button', { name: '复制结果' })).toBeTruthy()
  expect((input as HTMLTextAreaElement).value).toBe('需要保留的合成摘要原文')
  expect(port.sent.filter(x => x.method === 'assistant.submit')).toHaveLength(1)
  expect(port.sent.filter(x => x.method === 'operations.get').every(x => (x.payload as { operationId: string }).operationId === id)).toBe(true)
  expect(port.sent.some(x => x.method === 'operations.cancel' || x.method === 'models.mutate')).toBe(false)
})
it('translation checks uncertain cancellation without cancelling twice and ignores late cancellation failure after completion', async () => {
  vi.useFakeTimers(); const { port, input } = page('translate')
  fireEvent.change(input, { target: { value: 'synthetic translation' } })
  fireEvent.click(screen.getByRole('button', { name: '开始翻译' })); await reply(port, queued)
  fireEvent.click(screen.getByRole('button', { name: '取消' }))
  await reject(port, 'RuntimeUnavailable', '合成取消回执丢失')
  expect(screen.getByRole('button', { name: '开始翻译' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByLabelText('目标语言').hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '核对原任务状态' })); await reply(port, { ...queued, status: 'RUNNING' })
  expect(screen.getByText('已请求取消，模型可能仍在处理。')).toBeTruthy()
  expect(screen.getByRole('button', { name: '取消' }).hasAttribute('disabled')).toBe(true)
  await act(async () => { await vi.advanceTimersByTimeAsync(350) })
  await reply(port, { ...queued, status: 'CANCELLED', error: { code: 'Cancelled', message: '已取消' } })
  expect(screen.getByText('已取消，模型可能仍在处理。')).toBeTruthy()
  expect(screen.getByRole('button', { name: '开始翻译' }).hasAttribute('disabled')).toBe(false)
  expect(port.sent.filter(x => x.method === 'operations.cancel')).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: '开始翻译' })); await reply(port, queued)
  fireEvent.click(screen.getByRole('button', { name: '取消' })); const cancelIndex = port.sent.length - 1
  await act(async () => { await vi.advanceTimersByTimeAsync(350) })
  await reply(port, result)
  await reject(port, 'ClientTimeout', '迟到的合成取消失败', cancelIndex)
  expect(screen.getByRole('button', { name: '复制结果' })).toBeTruthy()
  expect(screen.queryByText('迟到的合成取消失败')).toBeNull()
  expect(screen.getByRole('button', { name: '开始翻译' }).hasAttribute('disabled')).toBe(false)
})
it('Enter and Ctrl+Enter during composition never submit; committed composition supports Ctrl+Enter and normal multiline paste', async () => {
  const { port, input } = page()
  fireEvent.compositionStart(input); fireEvent.compositionUpdate(input, { data: 'synthetic candidate' })
  fireEvent.change(input, { target: { value: '中文\nsynthetic' } })
  fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true, isComposing: true }); fireEvent.keyDown(input, { key: 'Enter' }); expect(port.sent).toHaveLength(0)
  fireEvent.compositionEnd(input); fireEvent.keyDown(input, { key: 'Enter', keyCode: 229, ctrlKey: true }); expect(port.sent).toHaveLength(0)
  fireEvent.keyDown(input, { key: 'Enter', shiftKey: true }); expect(port.sent).toHaveLength(0)
  fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true }); expect(port.sent[0]?.payload).toMatchObject({ text: '中文\nsynthetic' }); await reply(port, result)
})
it('route change keeps a bounded composer and accepted task without resubmit/cancel; session rotation clears state and never replays', async () => {
  vi.useFakeTimers()
  const port = new Port(); const bridge = new WorkspaceClient(port); clients.push(bridge); render(<App bridge={bridge} />)
  await act(async () => { port.session(); port.reply(0) })
  fireEvent.change(screen.getByLabelText('问题'), { target: { value: 'synthetic route text' } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' })); await reply(port, queued)
  await act(async () => { location.hash = '#/translate'; window.dispatchEvent(new Event('hashchange')) })
  const hiddenRequests = port.sent.length
  await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
  expect(port.sent).toHaveLength(hiddenRequests)
  await act(async () => { location.hash = '#/assistant'; window.dispatchEvent(new Event('hashchange')) })
  expect((screen.getByLabelText('问题') as HTMLTextAreaElement).value).toBe('synthetic route text')
  expect(port.sent.some(x => x.method === 'operations.cancel')).toBe(false)
  await act(async () => { await vi.advanceTimersByTimeAsync(350) })
  await reject(port, 'RuntimeUnavailable', '合成读取失败')
  await act(async () => { location.hash = '#/translate'; window.dispatchEvent(new Event('hashchange')) })
  await act(async () => { location.hash = '#/assistant'; window.dispatchEvent(new Event('hashchange')) })
  expect(screen.getByRole('button', { name: '发送问题' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '核对原任务状态' }))
  const oldCheck = port.sent.length - 1
  await act(async () => { port.session('55555555-5555-4555-8555-555555555555'); port.reply(port.sent.length - 1) })
  await reply(port, result, oldCheck)
  expect((screen.getByLabelText('问题') as HTMLTextAreaElement).value).toBe('')
  expect(screen.queryByRole('button', { name: '复制结果' })).toBeNull()
  expect(port.sent.filter(x => x.method === 'assistant.submit')).toHaveLength(1)
})
