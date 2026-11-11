import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation, KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import type { KnowledgeScope } from '../bridge/knowledgeScope'
import { KnowledgeService, knowledgeDocument } from '../test/knowledgeFixtures'
import { KnowledgeSearch } from './KnowledgeSearch'
import { KnowledgeSourceAnswers } from './KnowledgeSourceAnswers'

const docs = Array.from({ length: 3 }, (_, i) => ({ ...knowledgeDocument(), documentId: `aaaaaaaa-aaaa-4aaa-8aaa-${String(i + 1).padStart(12, '0')}`, title: `项目${i + 1}.md` }))
const sources = docs.map(doc => ({ title: doc.title, scope: { documentId: doc.documentId, sourceRevision: '1' } }))
function answer(index: number, status: 'RUNNING' | 'SUCCEEDED' | 'CANCELLED' = 'SUCCEEDED'): KnowledgeAnswerTask {
  const doc = docs[index]!
  return { taskId: `dddddddd-dddd-4ddd-8ddd-${String(index + 1).padStart(12, '0')}`, status,
    result: status === 'SUCCEEDED' ? { answer: `项目 ${index + 1} 的预算有独立条件。`, citations: [{ documentId: doc.documentId, title: doc.title, sourceRevision: '1', sourceType: 'MARKDOWN', startOffset: 5, endOffset: 30, startLine: 1, endLine: 2, heading: 'Heading', locator: { type: 'MARKDOWN_SECTION_LINES', startOffset: 0, endOffset: 100, startLine: 1, endLine: 2, section: 'line-1' } }] } : null,
    error: status === 'CANCELLED' ? { code: 'Cancelled', message: '已取消。' } : null }
}
class AnswersPort extends KnowledgeService {
  override postMessage(value: unknown) {
    if (['knowledge.answerSubmit', 'knowledge.answerGet', 'knowledge.answerCancel'].includes((value as { method: string }).method)) this.sent.push(value as Record<string, unknown>)
    else super.postMessage(value)
  }
  requests(method: string) { return this.sent.filter(request => request.method === method) }
  fail(code: string, message = '本份资料暂时不可用。') {
    const request = this.sent.at(-1)!
    this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code, message } })
  }
}
const clients: WorkspaceClient[] = []
beforeEach(() => vi.useFakeTimers())
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); vi.useRealTimers() })
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
const reply = async (port: AnswersPort, task: KnowledgeAnswerTask, index = port.sent.length - 1) => { await act(async () => { port.reply(index, task) }) }
const tick = async () => { await act(async () => { await vi.advanceTimersByTimeAsync(1001) }) }
function setup() {
  const port = new AnswersPort(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const props = { bridge, enabled: true, visible: true, sources, searchQuery: '预算', open: vi.fn(async (_citation: KnowledgeAnswerCitation) => {}) }
  const view = render(<KnowledgeSourceAnswers {...props} />)
  return { port, props, view }
}
async function start() {
  await click('准备用所选资料逐份提问')
  fireEvent.change(screen.getByLabelText('逐份回答的问题'), { target: { value: '预算适用哪些条件？' } })
  await click('确认清单并逐份回答')
}

it('prepares search selections without a model call, pins every request, pauses on missing evidence and opens exact citations', async () => {
  const port = new AnswersPort(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const openCitation = vi.fn(async (_citation: KnowledgeAnswerCitation) => {})
  const props = { bridge, enabled: true, visible: true, corpusEpoch: 0, open: vi.fn(async () => {}), openCitation }
  const view = render(<KnowledgeSearch {...props} currentDocument={docs[0]!} />)
  expect((screen.getByRole('button', { name: '准备用所选资料逐份提问' }) as HTMLButtonElement).disabled).toBe(true)
  for (const doc of docs) { view.rerender(<KnowledgeSearch {...props} currentDocument={doc} />); await click('加入检索范围') }
  fireEvent.change(screen.getByLabelText('检索关键词'), { target: { value: '预算' } })
  await click('准备用所选资料逐份提问')
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(0)
  view.rerender(<KnowledgeSearch {...props} currentDocument={{ ...docs[1]!, currentReadyRevision: '2' }} />)
  await click('更新当前资料的所选版本')
  fireEvent.change(screen.getByLabelText('逐份回答的问题'), { target: { value: '预算适用哪些条件？' } })
  await click('确认清单并逐份回答'); await reply(port, answer(0, 'RUNNING'))
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(1)
  await tick(); expect(port.requests('knowledge.answerGet').at(-1)?.payload).toEqual({ taskId: answer(0).taskId })
  await reply(port, answer(0)); expect(port.requests('knowledge.answerSubmit')).toHaveLength(2)
  await act(async () => { port.fail('InvalidRequest', '本份资料没有匹配证据。') })
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(2)
  expect(within(screen.getByRole('region', { name: '逐份回答：项目2.md' })).getByRole('alert').textContent).toContain('没有匹配证据')
  expect(within(screen.getByRole('region', { name: '逐份回答：项目3.md' })).getByText('尚未提交')).toBeTruthy()
  await click('打开本份引用位置 1'); expect(openCitation).toHaveBeenCalledWith(answer(0).result!.citations[0])
  await click('继续尚未完成的资料'); await reply(port, answer(2))
  expect(port.requests('knowledge.answerSubmit').map(request => request.payload)).toEqual(sources.map(source => ({ question: '预算适用哪些条件？', query: '预算', scope: source.scope })))
  expect(screen.getByText(/本轮 2 \/ 3 份回答成功/)).toBeTruthy()
})

it('keeps receipts across navigation, requires an explicit resume, cancels once and drops late session responses', async () => {
  const { port, props, view } = setup(); await start()
  const submitted = port.sent.length - 1
  view.rerender(<KnowledgeSourceAnswers {...props} visible={false} />)
  await reply(port, answer(0, 'RUNNING'), submitted); await tick()
  view.rerender(<KnowledgeSourceAnswers {...props} />); await tick()
  expect(port.requests('knowledge.answerGet')).toHaveLength(0)
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(1)
  await click('取消本份回答')
  await act(async () => { port.fail('OutcomeUnknown', '取消结果未知。') })
  expect((screen.getByRole('button', { name: '取消本份回答' }) as HTMLButtonElement).disabled).toBe(true)
  await click('核对本份原任务'); await reply(port, answer(0, 'CANCELLED'))
  expect(port.requests('knowledge.answerCancel')).toHaveLength(1)
  expect(port.requests('knowledge.answerGet').at(-1)?.payload).toEqual({ taskId: answer(0).taskId })
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(1)
  await click('继续尚未完成的资料')
  const second = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(second, answer(1)) })
  expect(screen.queryByRole('list', { name: '本轮问答资料' })).toBeNull()
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(2)
})

it('blocks all continuation and replacement after an unknown submission without guessing a history match', async () => {
  const { port, props, view } = setup(); await start()
  await act(async () => { port.fail('OutcomeUnknown', '请求可能已受理。') })
  expect(screen.getByText(/提交结果未知，整轮已阻止/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: '继续尚未完成的资料' })).toBeNull()
  expect((screen.getByRole('button', { name: '准备用所选资料逐份提问' }) as HTMLButtonElement).disabled).toBe(true)
  view.rerender(<KnowledgeSourceAnswers {...props} visible={false} />)
  view.rerender(<KnowledgeSourceAnswers {...props} />)
  await act(async () => { fireEvent.submit(screen.getByLabelText('逐份回答的问题').closest('form')!) })
  await tick()
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(1)
  expect(port.requests('knowledge.answerHistory')).toHaveLength(0)
})

it('rejects escaped citations and mismatched task IDs, reconciling only the original ID before any later submission', async () => {
  const { port } = setup(); await start()
  await reply(port, { ...answer(0), result: answer(1).result })
  expect(screen.queryByText('项目 2 的预算有独立条件。')).toBeNull()
  expect(screen.getByText('已提交，结果待核对')).toBeTruthy()
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(1)
  await click('核对本份原任务'); await reply(port, answer(1))
  expect(screen.getByText('工作区返回了无效响应，请重新打开。')).toBeTruthy()
  await click('核对本份原任务'); await reply(port, answer(0))
  expect(port.requests('knowledge.answerGet').map(request => request.payload)).toEqual([{ taskId: answer(0).taskId }, { taskId: answer(0).taskId }])
  expect(port.requests('knowledge.answerSubmit')).toHaveLength(1)
  await click('继续尚未完成的资料')
  expect((port.requests('knowledge.answerSubmit')[1]?.payload as { scope: KnowledgeScope }).scope).toEqual(sources[1]!.scope)
})
