import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { OperationPage } from './OperationPage'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { Port, status } from '../test/fixtures'
const id = '33333333-3333-4333-8333-333333333333'
const memory = { memoryId: '44444444-4444-4444-8444-444444444444', revision: '9007199254740993', position: 0, title: '<img onerror=alert(1)> &lt;上下文\u202e' }
const queued = { operationId: id, status: 'QUEUED', result: null, error: null }
const result = { ...queued, status: 'SUCCEEDED', result: '<script>alert(1)</script><img onerror=x> &lt;\u202e完成' }
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers(); localStorage.clear(); history.replaceState(null, '', '/'); vi.restoreAllMocks() })
function page(kind: 'assistant' | 'translate' = 'assistant') {
  const port = new Port(); const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<OperationPage kind={kind} bridge={bridge} enabled status={status} />)
  return { port, bridge, input: screen.getByRole('textbox') }
}
async function reply(port: Port, value: unknown, index = port.sent.length - 1) { await act(async () => { port.reply(index, value) }) }
async function choose(port: Port) {
  fireEvent.click(screen.getByRole('button', { name: 'Use Memory…' }))
  expect(port.sent.at(-1)?.method).toBe('assistant.selectMemories')
  await reply(port, { changed: true, selectedMemoryRefs: [memory] })
}
it('displays explicit title as text, keeps decimal revision exact, clears selection immediately after admission and next Ask has zero memories', async () => {
  const { port, input } = page(); await choose(port)
  expect(screen.getByText(memory.title).querySelector('img')).toBeNull()
  fireEvent.change(input, { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: 'Submit Ask' }))
  expect(port.sent.at(-1)?.payload).toEqual({ mode: 'Ask', text: 'synthetic', selectedMemoryRefs: [{ memoryId: memory.memoryId, revision: memory.revision, position: 0 }] })
  await reply(port, result); expect(screen.getByText('No Memory')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Submit Ask' })); expect((port.sent.at(-1)?.payload as { selectedMemoryRefs: unknown[] }).selectedMemoryRefs).toEqual([])
  await reply(port, result)
})
it('retains stale selection and blocks submit until explicit reselect or clear', async () => {
  const { port, input } = page(); await choose(port)
  fireEvent.change(input, { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: 'Submit Ask' }))
  const request = port.sent.at(-1)!
  await act(async () => { port.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: 'MemorySelectionStale', message: 'Review selected Memory.' } }) })
  expect(screen.getByText(memory.title)).toBeTruthy(); expect(screen.getByRole('button', { name: 'Submit Ask' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Clear Memory' })); expect(screen.getByRole('button', { name: 'Submit Ask' }).hasAttribute('disabled')).toBe(false)
})
it('POST timeout displays Outcome unknown without resend and retains unconfirmed Memory selection', async () => {
  vi.useFakeTimers(); const { port, input } = page(); await choose(port)
  fireEvent.change(input, { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: 'Submit Ask' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
  expect(screen.getByText(/Outcome unknown ·/)).toBeTruthy(); expect(screen.getByText(memory.title)).toBeTruthy()
  expect(port.sent.filter(x => x.method === 'assistant.submit')).toHaveLength(1)
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
  const port = new Port(); const bridge = new WorkspaceClient(port); clients.push(bridge); render(<App bridge={bridge} />)
  await act(async () => { port.session(); port.reply(0) })
  fireEvent.change(screen.getByLabelText('问题'), { target: { value: 'synthetic route text' } }); fireEvent.click(screen.getByRole('button', { name: 'Submit Ask' })); await reply(port, queued)
  await act(async () => { location.hash = '#/translate'; window.dispatchEvent(new Event('hashchange')) })
  await act(async () => { location.hash = '#/assistant'; window.dispatchEvent(new Event('hashchange')) })
  expect((screen.getByLabelText('问题') as HTMLTextAreaElement).value).toBe('synthetic route text')
  expect(port.sent.some(x => x.method === 'operations.cancel')).toBe(false)
  await act(async () => { port.session('55555555-5555-4555-8555-555555555555'); port.reply(port.sent.length - 1) })
  expect((screen.getByLabelText('问题') as HTMLTextAreaElement).value).toBe('')
  expect(port.sent.filter(x => x.method === 'assistant.submit')).toHaveLength(1)
})
