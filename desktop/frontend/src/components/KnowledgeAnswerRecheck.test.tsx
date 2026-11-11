import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation, KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import { AnswerHistoryService } from '../test/answerHistoryFixtures'
import { KnowledgeAnswer } from './KnowledgeAnswer'
import { KnowledgeAnswerRecheck } from './KnowledgeAnswerRecheck'

const newerId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
class RecheckService extends AnswerHistoryService {
  state: KnowledgeAnswerTask['status'] = 'SUCCEEDED'
  submitUnknown = false; cancelUnknown = false; wrongScope = false; holdSubmit = false; unscoped = false; reuseOriginalId = false
  override entry(id?: string) { const entry = super.entry(id); if (this.unscoped) delete entry.scope; return entry }
  newer(): KnowledgeAnswerTask {
    const original = super.entry().task
    return { ...original, taskId: this.reuseOriginalId ? original.taskId : newerId, status: this.state, result: this.state === 'SUCCEEDED'
      ? { answer: '新版预算为八百元。[1]', citations: original.result!.citations.map(c => ({ ...c, sourceRevision: this.wrongScope ? '1' : '2' })) } : null }
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }
    if (!['knowledge.answerSubmit', 'knowledge.answerGet', 'knowledge.answerCancel'].includes(request.method)) { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
    if (request.method === 'knowledge.answerSubmit' && this.holdSubmit) return
    queueMicrotask(() => {
      if (request.method === 'knowledge.answerSubmit' && this.submitUnknown || request.method === 'knowledge.answerCancel' && this.cancelUnknown) {
        const sent = this.sent[index]!
        this.emit({ version: 1, sessionId: sent.sessionId, requestId: sent.requestId, ok: false, error: { code: 'OutcomeUnknown', message: '合成回执未知。' } })
      } else this.reply(index, this.newer())
    })
  }
}
const clients: WorkspaceClient[] = []
const setup = () => { const port = new RecheckService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); return { port, bridge } }
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
const calls = (port: RecheckService, method: string) => port.sent.filter(r => r.method === method)
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); vi.restoreAllMocks(); vi.useRealTimers() })

it('rechecks the saved question on a confirmed newer revision, preserving the draft and both exact citation destinations', async () => {
  const { port, bridge } = setup(), open = vi.fn(async (_citation: KnowledgeAnswerCitation) => {})
  render(<KnowledgeAnswer bridge={bridge} enabled visible open={open} />)
  await click('查看问答历史'); await click(port.entry().question)
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '尚未发送的新问题😀' } })
  fireEvent.change(screen.getByLabelText('回答检索关键词'), { target: { value: '另一个关键词' } })
  await click('准备新版复核')
  expect(screen.getByRole('dialog').textContent).toContain('源版本 1 → 2')
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(0)
  await click('取消'); expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(0)
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  expect(calls(port, 'knowledge.answerSubmit').map(r => r.payload)).toEqual([{ question: port.entry().question, query: port.entry().query,
    scope: { documentId: port.document.documentId, sourceRevision: '2' } }])
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('尚未发送的新问题😀')
  expect((screen.getByLabelText('回答检索关键词') as HTMLInputElement).value).toBe('另一个关键词')
  const original = within(screen.getByRole('article', { name: '原回答' }))
  fireEvent.click(original.getByRole('button', { name: '查看原文' }))
  expect(original.getByLabelText('回答原文').textContent).toBe(port.answerText)
  expect(screen.getByRole('article', { name: '新版回答' }).textContent).toContain('八百元')
  await click('核对原回答引用 1'); await click('核对新版回答引用 1')
  expect(open.mock.calls.map(args => args[0].sourceRevision)).toEqual(['1', '2'])
  expect(calls(port, 'knowledge.answerHistoryDelete')).toHaveLength(0)
  expect(screen.getByText(/两份回答文字不同/)).toBeTruthy()
})

it('refuses unavailable, unscoped or unchanged sources and revalidates a changed version before any model request', async () => {
  const { port, bridge } = setup(), props = { bridge, taskId: port.entry().task.taskId, enabled: true, visible: true, open: async () => {}, onBlocked: vi.fn() }
  const view = render(<KnowledgeAnswerRecheck {...props} />)
  port.unscoped = true; await click('准备新版复核'); expect(screen.getByRole('alert').textContent).toContain('全库回答不能自动改成单份资料')
  port.unscoped = false; port.document.currentReadyRevision = '1'; await click('准备新版复核')
  expect(screen.getByRole('alert').textContent).toContain('没有比原回答更新')
  port.document.currentReadyRevision = '2'; port.document.status = 'ARCHIVED'; await click('准备新版复核')
  expect(screen.getByRole('alert').textContent).toContain('已删除、归档')
  port.document.status = 'ACTIVE'; port.missingSource = true; await click('准备新版复核')
  expect(screen.getByRole('alert').textContent).toContain('原回答的资料版本已不可用')
  port.missingSource = false; await click('准备新版复核')
  port.document.metadataVersion = '9007199254740994'; port.document.title = 'changed.md'
  await click('确认版本并生成独立回答')
  expect(screen.getByRole('alert').textContent).toContain('确认期间已变化')
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(0)
  await click('准备新版复核'); view.rerender(<KnowledgeAnswerRecheck {...props} visible={false} />)
  view.rerender(<KnowledgeAnswerRecheck {...props} />); expect(screen.queryByRole('dialog')).toBeNull()
  port.holdHistory = true; await click('准备新版复核')
  const held = port.sent.length - 1
  view.rerender(<KnowledgeAnswerRecheck {...props} visible={false} />); view.rerender(<KnowledgeAnswerRecheck {...props} />)
  await act(async () => port.reply(held, port.entry()))
  expect(screen.queryByRole('dialog')).toBeNull(); expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(0)
})

it('retains a new task across navigation, rejects out-of-scope evidence and reconciles one cancellation without replay', async () => {
  const { port, bridge } = setup(), props = { bridge, taskId: port.entry().task.taskId, enabled: true, visible: true, open: async () => {}, onBlocked: vi.fn() }
  const view = render(<KnowledgeAnswerRecheck {...props} />)
  vi.useFakeTimers(); port.state = 'RUNNING'
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  view.rerender(<KnowledgeAnswerRecheck {...props} visible={false} />)
  await act(async () => vi.advanceTimersByTimeAsync(5000)); expect(calls(port, 'knowledge.answerGet')).toHaveLength(0)
  view.rerender(<KnowledgeAnswerRecheck {...props} />)
  port.state = 'SUCCEEDED'; port.wrongScope = true
  await click('核对新版任务状态')
  expect(screen.queryByRole('article', { name: '新版回答' })).toBeNull()
  expect(screen.getByRole('alert').textContent).toContain('引用范围不符')
  expect((screen.getByRole('button', { name: '准备新版复核' }) as HTMLButtonElement).disabled).toBe(true)
  port.cancelUnknown = true; await click('取消新版回答'); await click('取消新版回答')
  expect(calls(port, 'knowledge.answerCancel')).toHaveLength(1)
  view.rerender(<KnowledgeAnswerRecheck {...props} visible={false} />); view.rerender(<KnowledgeAnswerRecheck {...props} />)
  expect((screen.getByRole('button', { name: '取消新版回答' }) as HTMLButtonElement).disabled).toBe(true)
  port.wrongScope = false; await click('核对新版任务状态')
  expect(screen.getByRole('article', { name: '新版回答' })).toBeTruthy()
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(1)
  expect(calls(port, 'knowledge.answerGet').every(r => (r.payload as { taskId: string }).taskId === newerId)).toBe(true)
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
  port.reuseOriginalId = true; await click('准备新版复核'); await click('确认版本并生成独立回答')
  expect(screen.queryByRole('button', { name: '取消新版回答' })).toBeNull()
  expect(screen.queryByRole('button', { name: '核对新版任务状态' })).toBeNull()
  expect(screen.queryByRole('article', { name: '新版回答' })).toBeNull()
  expect((screen.getByRole('button', { name: '准备新版复核' }) as HTMLButtonElement).disabled).toBe(true)
})

it('blocks unknown submissions including the normal form, and ignores late receipts after a bridge session changes', async () => {
  const { port, bridge } = setup(), props = { bridge, enabled: true, visible: true, open: async () => {} }
  const view = render(<KnowledgeAnswer {...props} />)
  await click('查看问答历史'); await click(port.entry().question)
  port.submitUnknown = true; await click('准备新版复核'); await click('确认版本并生成独立回答')
  view.rerender(<KnowledgeAnswer {...props} visible={false} />); view.rerender(<KnowledgeAnswer {...props} />)
  expect((screen.getByRole('button', { name: '基于知识回答' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: '准备新版复核' }) as HTMLButtonElement).disabled).toBe(true)
  await click('准备新版复核'); await click('基于知识回答'); expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(1)
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
  await click('查看问答历史'); await click(port.entry().question)
  port.submitUnknown = false; port.holdSubmit = true
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  const held = port.sent.length - 1
  await act(async () => { port.session('ffffffff-ffff-4fff-8fff-ffffffffffff'); port.reply(held, port.newer()) })
  expect(within(screen.getByRole('region', { name: '资料新版回答复核' })).queryByRole('article')).toBeNull()
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('')
})

it('clears a finished comparison when the user starts a new question', async () => {
  const { port, bridge } = setup()
  render(<KnowledgeAnswer bridge={bridge} enabled visible open={async () => {}} />)
  await click('查看问答历史'); await click(port.entry().question)
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  expect(screen.getByRole('article', { name: '原回答' })).toBeTruthy()
  expect(screen.getByRole('article', { name: '新版回答' })).toBeTruthy()
  await click('开始新问题'); await click('清空当前问题')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('')
  expect(screen.queryByRole('article', { name: '原回答' })).toBeNull()
  expect(screen.queryByRole('article', { name: '新版回答' })).toBeNull()
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(1)
  expect(calls(port, 'knowledge.answerHistoryDelete')).toHaveLength(0)
})

it('clears a finished comparison when another saved answer is opened', async () => {
  const { port, bridge } = setup(); port.historySize = 2
  render(<KnowledgeAnswer bridge={bridge} enabled visible open={async () => {}} />)
  await click('查看问答历史'); await click(port.entry().question)
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  expect(screen.getByRole('article', { name: '新版回答' })).toBeTruthy()
  await click(`${port.entry().question} 2`)
  expect(screen.queryByRole('article', { name: '原回答' })).toBeNull()
  expect(screen.queryByRole('article', { name: '新版回答' })).toBeNull()
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(1)
  expect(calls(port, 'knowledge.answerHistoryDelete')).toHaveLength(0)
})

it('keeps an in-flight recheck bound to its task if the selected original changes', async () => {
  const { port, bridge } = setup(), onBlocked = vi.fn()
  const props = { bridge, taskId: port.entry().task.taskId, enabled: true, visible: true, open: async () => {}, onBlocked }
  port.state = 'RUNNING'
  const view = render(<KnowledgeAnswerRecheck {...props} />)
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  view.rerender(<KnowledgeAnswerRecheck {...props} taskId={null} />)
  expect(screen.getByRole('button', { name: '核对新版任务状态' })).toBeTruthy()
  expect((screen.getByRole('button', { name: '准备新版复核' }) as HTMLButtonElement).disabled).toBe(true)
  expect(onBlocked.mock.calls.at(-1)?.[0]).toBe(true)
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(1)
})

it('keeps an unknown recheck blocked if the selected original changes', async () => {
  const { port, bridge } = setup(), onBlocked = vi.fn()
  const props = { bridge, taskId: port.entry().task.taskId, enabled: true, visible: true, open: async () => {}, onBlocked }
  port.submitUnknown = true
  const view = render(<KnowledgeAnswerRecheck {...props} />)
  await click('准备新版复核'); await click('确认版本并生成独立回答')
  view.rerender(<KnowledgeAnswerRecheck {...props} taskId={null} />)
  expect(screen.getByText(/提交结果未知，不能通过相似历史推断结果或再次提交/)).toBeTruthy()
  expect((screen.getByRole('button', { name: '准备新版复核' }) as HTMLButtonElement).disabled).toBe(true)
  expect(onBlocked.mock.calls.at(-1)?.[0]).toBe(true)
  expect(calls(port, 'knowledge.answerSubmit')).toHaveLength(1)
})
