import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { KnowledgePage } from '../pages/KnowledgePage'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { isKnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import type { KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'

class AnswerService extends KnowledgeService {
  hold = false
  answer(): KnowledgeAnswerTask {
    return { taskId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', status: 'SUCCEEDED', result: { answer: '<script>grounded answer</script> [link](https://invalid)', citations: [{ documentId: this.document.documentId, title: 'fixture.md', sourceRevision: '1', sourceType: 'MARKDOWN', startOffset: 5, endOffset: 30, startLine: 1, endLine: 2, heading: 'Heading', locator: { type: 'MARKDOWN_SECTION_LINES', startLine: 1, endLine: 2, startOffset: 0, endOffset: 100, section: 'line-1' } }] }, error: null }
  }
  override detail() { return { ...super.detail(), revisions: [{ sourceRevision: '1', sourceType: 'MARKDOWN' as const, byteLength: 20 }, { sourceRevision: '2', sourceType: 'MARKDOWN' as const, byteLength: 20 }] } }
  override postMessage(value: unknown) {
    const r = value as { method: string }
    if (['knowledge.answerSubmit', 'knowledge.answerGet', 'knowledge.answerCancel'].includes(r.method)) { super.postMessage(value); if (!this.hold) this.reply(this.sent.length - 1, this.answer()); return }
    if (r.method === 'knowledge.preview') { this.sent.push(value as typeof this.sent[number]); this.reply(this.sent.length - 1, { ...this.preview(), offset: 5, text: this.preview().text.slice(5) }); return }
    super.postMessage(value)
  }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); vi.useRealTimers(); vi.restoreAllMocks() })
it('explicit K3 flow guards both IME controls, renders plain answer, opens admitted source and clears on session/page leave', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'), port = new AnswerService(); port.document.currentReadyRevision = '2'
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ })
  const question = screen.getByLabelText('问题 / Question'), query = screen.getByLabelText('回答检索关键词 / Retrieval Keywords')
  fireEvent.change(question, { target: { value: '原始问题？' } }); fireEvent.change(query, { target: { value: '预算' } })
  expect(port.sent.some(r => r.method === 'knowledge.answerSubmit')).toBe(false)
  for (const input of [question, query]) {
    fireEvent.compositionStart(input); expect(fireEvent.keyDown(input, { key: 'Enter', isComposing: true })).toBe(false)
    await act(async () => fireEvent.submit(input.closest('form')!)); fireEvent.compositionEnd(input)
  }
  expect(port.sent.some(r => r.method === 'knowledge.answerSubmit')).toBe(false)
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '基于知识回答' })))
  expect(port.sent.find(r => r.method === 'knowledge.answerSubmit')?.payload).toEqual({ question: '原始问题？', query: '预算' })
  expect(screen.getByText(port.answer().result!.answer)).toBeTruthy(); expect(document.querySelector('script,a,img,iframe')).toBeNull()
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '打开引用来源' })))
  expect(port.sent.find(r => r.method === 'knowledge.preview')?.payload).toEqual({ documentId: port.document.documentId, sourceRevision: '1', offset: 5 })
  await act(async () => port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
  expect((question as HTMLTextAreaElement).value).toBe(''); expect((query as HTMLInputElement).value).toBe(''); expect(screen.queryByText('打开引用来源')).toBeNull()
  fireEvent.change(question, { target: { value: 'late question' } }); fireEvent.change(query, { target: { value: 'budget' } }); port.hold = true
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '基于知识回答' }))); const pending = port.sent.length - 1
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  await act(async () => port.reply(pending, port.answer())); view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  expect((question as HTMLTextAreaElement).value).toBe(''); expect((query as HTMLInputElement).value).toBe(''); expect(screen.queryByText('打开引用来源')).toBeNull()
  expect(storage).not.toHaveBeenCalled(); expect(location.href).not.toContain('预算'); expect(location.href).not.toContain(port.document.documentId)
})
it('lost K3 submission is OutcomeUnknown without replay and extra result fields are rejected', async () => {
  vi.useFakeTimers(); const port = new AnswerService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); port.hold = true
  const pending = bridge.submitKnowledgeAnswer('question', 'budget').catch(e => e)
  await vi.advanceTimersByTimeAsync(15001); expect((await pending).code).toBe('OutcomeUnknown'); expect(port.sent.filter(r => r.method === 'knowledge.answerSubmit')).toHaveLength(1)
  const answer = port.answer(); expect(isKnowledgeAnswerTask(answer)).toBe(true)
  expect(isKnowledgeAnswerTask({ ...answer, result: { ...answer.result, sourceDigest: 'private' } })).toBe(false)
  expect(isKnowledgeAnswerTask({ ...answer, result: { ...answer.result, citations: [] } })).toBe(false)
})
