import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { KnowledgePage } from '../pages/KnowledgePage'
import { WorkspaceClient } from '../bridge/client'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { isKnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import type { KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import type { KnowledgePreview } from '../bridge/knowledge'

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
class ReaderService extends AnswerService {
  prefix = '# Heading\n' + '前文。'.repeat(1361)
  evidence = '仅限😀本项目，预算为五百元。\n<script>不适用于旧项目。</script>'
  source = this.prefix + this.evidence + '后文。'.repeat(1500)
  holdPreview = false; failPreview = false; wrongVersion = false
  override answer(): KnowledgeAnswerTask {
    const task = super.answer(), citation = task.result!.citations[0]!
    return { ...task, result: { ...task.result!, citations: [{ ...citation, startOffset: this.prefix.length, endOffset: this.prefix.length + this.evidence.length, startLine: 2, endLine: 3, locator: { ...citation.locator, endLine: 3, endOffset: this.source.length } }] } }
  }
  page(offset: number, revision = '1'): KnowledgePreview {
    const source = revision === '1' ? this.source : '# Heading\n新版正文。'
    let end = Math.min(source.length, offset + 4096)
    if (end < source.length && /[\uDC00-\uDFFF]/u.test(source[end]!)) end--
    return { ...this.preview(), sourceRevision: revision, offset, text: source.slice(offset, end), nextOffset: end < source.length ? end : null, locators: [{ type: 'MARKDOWN_SECTION_LINES', startLine: 1, endLine: 3, startOffset: 0, endOffset: source.length, section: 'line-1', heading: 'Heading' }] }
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: { offset: number; sourceRevision: string } }
    if (request.method !== 'knowledge.preview') { super.postMessage(value); return }
    this.sent.push(value as typeof this.sent[number])
    if (this.holdPreview) return
    if (this.failPreview) {
      this.failPreview = false; const pending = this.sent.at(-1)!
      this.emit({ version: 1, sessionId: pending.sessionId, requestId: pending.requestId, ok: false, error: { code: 'KnowledgeStorageUnavailable', message: '读取暂时不可用。' } }); return
    }
    const page = this.page(request.payload.offset, request.payload.sourceRevision)
    this.reply(this.sent.length - 1, this.wrongVersion ? { ...page, sourceRevision: '2' } : page)
  }
}
class HistoryService extends AnswerService {
  deleted = false; holdHistory = false
  exportOutcome: 'SAVED' | 'CANCELLED' = 'SAVED'; holdExport = false
  scope: { documentId: string; sourceRevision: string } | null = null
  override postMessage(value: unknown) {
    const request = value as { method: string }
    if (request.method === 'knowledge.answerExport') { this.sent.push(value as typeof this.sent[number]); if (!this.holdExport) this.reply(this.sent.length - 1, { outcome: this.exportOutcome }); return }
    if (!request.method.startsWith('knowledge.answerHistory')) { super.postMessage(value); return }
    this.sent.push(value as typeof this.sent[number]); const index = this.sent.length - 1
    if (request.method === 'knowledge.answerHistory') this.reply(index, { items: this.deleted ? [] : [{ taskId: this.answer().taskId, question: '保存的问题 <script>fixture</script>', status: 'SUCCEEDED', createdAt: '2026-11-09T00:00:00Z' }], total: this.deleted ? 0 : 1, page: 0, limit: 20 })
    else if (request.method === 'knowledge.answerHistoryGet' && !this.holdHistory) this.reply(index, { question: '保存的问题 <script>fixture</script>', query: 'budget', task: this.answer(), scope: this.scope })
    else if (request.method === 'knowledge.answerHistoryDelete') { this.deleted = true; this.reply(index, { deleted: true }) }
  }
}
it('exports the admitted saved answer by ID, handles native cancel and unknown outcome, and drops late session results', async () => {
  const port = new HistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<KnowledgePage bridge={bridge} enabled visible />)
  expect(screen.queryByRole('button', { name: '导出问答笔记…' })).toBeNull()
  await readerClick('查看问答历史'); await readerClick('保存的问题 <script>fixture</script>')
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '未提交的新草稿' } })
  await readerClick('导出问答笔记…')
  expect(port.sent.find(r => r.method === 'knowledge.answerExport')?.payload).toEqual({ taskId: port.answer().taskId })
  expect(screen.getByText('已保存问答笔记，包含引用原文。')).toBeTruthy()
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('未提交的新草稿')
  port.exportOutcome = 'CANCELLED'; await readerClick('导出问答笔记…'); expect(screen.getByText('已取消导出。')).toBeTruthy()
  port.holdExport = true; vi.useFakeTimers(); await readerClick('导出问答笔记…')
  expect((screen.getByRole('button', { name: '导出问答笔记…' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => vi.advanceTimersByTimeAsync(300001))
  expect(screen.getByRole('alert').textContent).toContain('先检查保存位置和原生窗口')
  expect(port.sent.filter(r => r.method === 'knowledge.answerExport')).toHaveLength(3)
  await readerClick('导出问答笔记…'); const pending = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(pending, { outcome: 'SAVED' }) })
  expect(screen.queryByText('已保存问答笔记，包含引用原文。')).toBeNull()
  expect(screen.queryByRole('button', { name: '导出问答笔记…' })).toBeNull()
  expect(port.sent.some(r => r.method === 'knowledge.answerSubmit')).toBe(false)
  const malformed = bridge.exportKnowledgeAnswer(port.answer().taskId).catch(e => e)
  await act(async () => port.reply(port.sent.length - 1, { outcome: 'SAVED', path: 'private' }))
  expect((await malformed).code).toBe('OutcomeUnknown')
})
it('reopens saved answer and exact citation without model submission, then deletes only after confirmation', async () => {
  const port = new HistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<KnowledgePage bridge={bridge} enabled visible />)
  await readerClick('查看问答历史'); await readerClick('保存的问题 <script>fixture</script>')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('保存的问题 <script>fixture</script>')
  expect(screen.getByText('<script>grounded answer</script> [link](https://invalid)')).toBeTruthy()
  await readerClick('打开引用来源'); expect(document.querySelector('script,a,img,iframe')).toBeNull()
  expect(port.sent.some(r => r.method === 'knowledge.answerSubmit')).toBe(false)
  await readerClick('删除记录：保存的问题 <script>fixture</script>'); expect(port.deleted).toBe(false)
  await readerClick('确认删除问答'); expect(port.deleted).toBe(true); expect(screen.getByText('暂无问答记录')).toBeTruthy()
  expect(port.sent.some(r => r.method === 'knowledge.delete')).toBe(false)
})
it('clears loaded history and ignores a late historical answer after session rotation', async () => {
  const port = new HistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<KnowledgePage bridge={bridge} enabled visible />); await readerClick('查看问答历史'); port.holdHistory = true
  await readerClick('保存的问题 <script>fixture</script>'); const index = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(index, { question: '保存的问题', query: 'budget', task: port.answer() }) })
  expect(screen.queryByRole('region', { name: '问答历史' })).toBeNull(); expect(screen.queryByText('<script>grounded answer</script> [link](https://invalid)')).toBeNull()
})
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); vi.useRealTimers(); vi.restoreAllMocks() })
const readerClick = async (name: string) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
it('keeps an explicit answer scope through source refresh and restores the saved scope without resubmitting', async () => {
  const port = new HistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<KnowledgePage bridge={bridge} enabled visible />)
  const choose = await screen.findByRole('button', { name: /fixture.md/ }); await act(async () => fireEvent.click(choose))
  const answer = within(screen.getByRole('region', { name: '基于文档回答' }))
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '保留问题草稿' } })
  fireEvent.change(screen.getByLabelText('回答检索关键词'), { target: { value: 'budget' } })
  await act(async () => fireEvent.click(answer.getByRole('button', { name: '限定为当前文档' })))
  port.document.currentReadyRevision = '2'; await readerClick('刷新详情')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('保留问题草稿')
  await readerClick('基于知识回答')
  expect(port.sent.find(r => r.method === 'knowledge.answerSubmit')?.payload).toEqual({ question: '保留问题草稿', query: 'budget', scope: { documentId: port.document.documentId, sourceRevision: '1' } })
  port.scope = { documentId: port.document.documentId, sourceRevision: '1' }
  await readerClick('查看问答历史'); await readerClick('保存的问题 <script>fixture</script>')
  expect(answer.getByText(/资料范围：fixture.md · 源版本 1/)).toBeTruthy()
  expect(port.sent.filter(r => r.method === 'knowledge.answerSubmit')).toHaveLength(1)
  await act(async () => port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'))
  expect(answer.getByText('资料范围：全部可用资料')).toBeTruthy()
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('')
})
const readerAnswer = async () => {
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '适用条件是什么？' } })
  fireEvent.change(screen.getByLabelText('回答检索关键词'), { target: { value: '预算' } })
  await readerClick('基于知识回答'); await readerClick('打开引用来源')
}
it('source reader marks only exact old-version evidence and supports Unicode-safe context navigation without losing failed back reads', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'), port = new ReaderService(); port.document.currentReadyRevision = '2'
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ })
  await readerAnswer()
  const sourceText = () => screen.getByRole('region', { name: '纯文本预览' }).querySelector('pre')!
  expect(sourceText().textContent).toBe(port.page(port.prefix.length).text)
  expect(sourceText().querySelector('mark')?.textContent).toBe(port.evidence)
  expect(document.querySelector('script,a,img,iframe')).toBeNull()
  expect(document.activeElement).toBe(sourceText())
  expect(screen.getByText(/当前可用版本为 2，此处仍显示所选旧版本/)).toBeTruthy()
  await readerClick('下一页文本')
  const context = sourceText().textContent
  expect(sourceText().querySelector('mark')).toBeNull(); expect(screen.getByText(/本页为上下文，未包含引用范围/)).toBeTruthy()
  port.failPreview = true; await readerClick('上一页文本')
  expect(sourceText().textContent).toBe(context); expect(screen.getByRole('alert').textContent).toBe('读取暂时不可用。')
  await readerClick('上一页文本')
  expect(sourceText().querySelector('mark')?.textContent).toBe(port.evidence)
  await readerClick('从本节开头阅读')
  expect(sourceText().textContent).toBe(port.source.slice(0, 4095)); expect(sourceText().querySelector('mark')?.textContent).toBe('仅限')
  await readerClick('下一页文本')
  expect(port.sent.filter(request => request.method === 'knowledge.preview').at(-1)?.payload).toEqual({ documentId: port.document.documentId, sourceRevision: '1', offset: 4095 })
  expect(sourceText().querySelector('mark')?.textContent).toBe(port.evidence.slice(2))
  expect(sourceText().textContent).not.toMatch(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u)
  await readerClick('返回引用位置'); expect(sourceText().querySelector('mark')?.textContent).toBe(port.evidence)
  fireEvent.change(screen.getByLabelText('已发布源版本'), { target: { value: '2' } })
  expect(screen.queryByRole('region', { name: '纯文本预览' })).toBeNull()
  await readerClick('预览源文本'); expect(sourceText().textContent).toBe('# Heading\n新版正文。')
  expect(sourceText().querySelector('mark')).toBeNull(); expect(screen.queryByRole('button', { name: '返回引用位置' })).toBeNull()
  expect(storage).not.toHaveBeenCalled()
})
it('source reader rejects wrong-version replies and ignores late reads after leaving the page or replacing the session', async () => {
  const port = new ReaderService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); port.holdPreview = true
  const view = render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ })
  await readerAnswer(); const pending = port.sent.length - 1
  view.rerender(<KnowledgePage bridge={bridge} enabled visible={false} />)
  view.rerender(<KnowledgePage bridge={bridge} enabled visible />)
  await act(async () => port.reply(pending, port.page(port.prefix.length)))
  expect(screen.queryByRole('region', { name: '纯文本预览' })).toBeNull()
  port.holdPreview = false; port.wrongVersion = true
  await readerAnswer()
  expect(screen.getByRole('alert')).toBeTruthy(); expect(screen.queryByRole('region', { name: '纯文本预览' })).toBeNull()
  port.holdPreview = true; await readerClick('打开引用来源'); const oldSession = port.sent.length - 1
  await act(async () => { port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'); port.reply(oldSession, port.page(port.prefix.length)) })
  expect(screen.queryByRole('region', { name: '纯文本预览' })).toBeNull()
  expect(screen.queryByRole('button', { name: '返回引用位置' })).toBeNull()
})
it('explicit K3 flow guards both IME controls, renders plain answer, opens admitted source and clears on session/page leave', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'), port = new AnswerService(); port.document.currentReadyRevision = '2'
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ })
  const question = screen.getByLabelText('想了解什么'), query = screen.getByLabelText('回答检索关键词')
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
it('K3 running model identity failure restores input without an answer, citations or replay', async () => {
  const port = new AnswerService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  render(<KnowledgePage bridge={bridge} enabled visible />); await screen.findByRole('button', { name: /fixture.md/ })
  vi.useFakeTimers(); port.hold = true
  const question = screen.getByLabelText('想了解什么') as HTMLTextAreaElement, query = screen.getByLabelText('回答检索关键词') as HTMLInputElement
  const submit = screen.getByRole('button', { name: '基于知识回答' }) as HTMLButtonElement
  fireEvent.change(question, { target: { value: 'question' } }); fireEvent.change(query, { target: { value: 'budget' } })
  const running: KnowledgeAnswerTask = { ...port.answer(), status: 'RUNNING', result: null }
  await act(async () => { fireEvent.click(submit); port.reply(port.sent.length - 1, running) })
  expect(screen.getByText('正在生成回答')).toBeTruthy(); expect(submit.disabled).toBe(true)
  expect(question.disabled).toBe(true); expect(query.disabled).toBe(true)
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  expect(port.sent.at(-1)).toMatchObject({ method: 'knowledge.answerGet', payload: { taskId: running.taskId } })
  const failed: KnowledgeAnswerTask = { ...running, status: 'FAILED', error: { code: 'ModelIdentityChanged', message: '模型 identity/digest 已改变；保持暂停，请重新核对。' } }
  await act(async () => port.reply(port.sent.length - 1, failed))
  expect(screen.getByText('回答失败')).toBeTruthy(); expect(screen.getByRole('alert').textContent).toBe(failed.error!.message)
  expect(screen.queryByText('正在生成回答')).toBeNull(); expect(screen.queryByRole('button', { name: '取消知识回答' })).toBeNull()
  expect(screen.queryByText(port.answer().result!.answer)).toBeNull(); expect(screen.queryByRole('heading', { name: '引用来源' })).toBeNull()
  expect(screen.queryByRole('button', { name: '打开引用来源' })).toBeNull()
  expect(submit.disabled).toBe(false); expect(question.disabled).toBe(false); expect(query.disabled).toBe(false)
  const sent = port.sent.length
  await act(async () => vi.advanceTimersByTimeAsync(15001))
  expect(port.sent).toHaveLength(sent); expect(port.sent.filter(r => r.method === 'knowledge.answerSubmit')).toHaveLength(1)
  await act(async () => { fireEvent.click(submit); port.reply(port.sent.length - 1, failed) })
  expect(port.sent.filter(r => r.method === 'knowledge.answerSubmit')).toHaveLength(2)
  expect(screen.getByText('回答失败')).toBeTruthy(); expect(screen.getByRole('alert').textContent).toBe(failed.error!.message)
  expect(submit.disabled).toBe(false)
})
it('lost K3 submission is OutcomeUnknown without replay and task contracts stay strict', async () => {
  vi.useFakeTimers(); const port = new AnswerService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); port.hold = true
  const pending = bridge.submitKnowledgeAnswer('question', 'budget').catch(e => e)
  await vi.advanceTimersByTimeAsync(15001); expect((await pending).code).toBe('OutcomeUnknown'); expect(port.sent.filter(r => r.method === 'knowledge.answerSubmit')).toHaveLength(1)
  const answer = port.answer(); expect(isKnowledgeAnswerTask(answer)).toBe(true)
  expect(isKnowledgeAnswerTask({ ...answer, result: { ...answer.result, sourceDigest: 'private' } })).toBe(false)
  expect(isKnowledgeAnswerTask({ ...answer, result: { ...answer.result, citations: [] } })).toBe(false)
  const citation = answer.result!.citations[0]!
  expect(isKnowledgeAnswerTask({ ...answer, result: { ...answer.result, citations: [{ ...citation, sourceDigest: 'private' }] } })).toBe(false)
  expect(isKnowledgeAnswerTask({ ...answer, result: { ...answer.result, citations: [{ ...citation, locator: { ...citation.locator, endOffset: citation.endOffset - 1 } }] } })).toBe(false)
  const failed = { ...answer, status: 'FAILED', result: null, error: { code: 'ModelIdentityChanged', message: '请检查模型安全状态。' } }
  for (const code of ['ModelStateUnavailable', 'ModelIdentityChanged', 'ModelExecutionUncertain', 'QueueFull', 'KnowledgeInterrupted', 'KnowledgeStorageUnavailable', 'KnowledgeLimitExceeded']) {
    expect(isKnowledgeAnswerTask({ ...failed, error: { ...failed.error, code } })).toBe(true)
  }
  for (const code of ['UnknownFailure', 'ModelSwitchConflict', 'ModelSelectionRevisionConflict', 'ModelConfigurationInvalid']) {
    expect(isKnowledgeAnswerTask({ ...failed, error: { ...failed.error, code } })).toBe(false)
  }
  expect(isKnowledgeAnswerTask({ ...failed, sourceDigest: 'private' })).toBe(false)
  expect(isKnowledgeAnswerTask({ ...failed, error: { ...failed.error, phase: 'EXECUTION' } })).toBe(false)
  expect(isKnowledgeAnswerTask({ ...failed, result: answer.result })).toBe(false)
})
