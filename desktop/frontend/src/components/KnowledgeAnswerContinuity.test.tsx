import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { App } from '../app/App'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import { AnswerHistoryService } from '../test/answerHistoryFixtures'
import { KnowledgeAnswer } from './KnowledgeAnswer'

const clients: WorkspaceClient[] = []
const click = async (name: string | RegExp, root?: HTMLElement) => { await act(async () => fireEvent.click((root ? within(root) : screen).getByRole('button', { name }))) }
const navigate = async (name: string) => { await act(async () => fireEvent.click(screen.getByRole('link', { name }))) }
const edit = () => {
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '要保留的新问题😀' } })
  fireEvent.change(screen.getByLabelText('回答检索关键词'), { target: { value: '本项目' } })
}
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); vi.restoreAllMocks(); vi.useRealTimers(); history.replaceState(null, '', '#/assistant') })
it('knowledge continuity keeps unsent input, explicit source scope and answer identity across navigation and confirmed new-question transitions', async () => {
  const port = new AnswerHistoryService(); port.document.currentReadyRevision = '1'
  const bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/knowledge'); render(<App bridge={bridge} />); await act(async () => port.session())
  await click(/fixture.md/); edit()
  const answer = screen.getByRole('region', { name: '基于文档回答' })
  await click('限定为当前文档', answer)
  await navigate('对话'); await navigate('知识库')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('要保留的新问题😀')
  expect(within(answer).getByText('资料范围：fixture.md · 源版本 1')).toBeTruthy()
  await click('查看问答历史'); await click(port.entry().question); await click('取消')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('要保留的新问题😀')
  await click('基于知识回答'); expect(screen.getByRole('region', { name: '当前回答对应的问题' }).textContent).toContain('要保留的新问题😀')
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '下一题还未提交' } })
  await click('使用全部资料', answer); await navigate('助手'); await navigate('知识库')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('下一题还未提交')
  expect(screen.getByRole('region', { name: '当前回答对应的问题' }).textContent).toContain('源版本 1')
  expect(within(answer).getByText('资料范围：全部可用资料')).toBeTruthy()
  expect(screen.getByRole('button', { name: '打开引用来源' })).toBeTruthy()
  await click('开始新问题'); await click('取消'); expect(screen.getByRole('region', { name: '当前回答对应的问题' })).toBeTruthy()
  await click('开始新问题'); await click('清空当前问题')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe(''); expect(screen.queryByRole('button', { name: '打开引用来源' })).toBeNull()
  expect(screen.getByRole('region', { name: '问答历史' })).toBeTruthy()
  expect(port.sent.filter(r => r.method === 'knowledge.answerSubmit')).toHaveLength(1)
  expect(port.sent.some(r => r.method === 'knowledge.answerHistoryDelete')).toBe(false)
})
it('knowledge continuity retains an accepted task after leaving mid-submit, resumes observation and recovers failed status reads without replay', async () => {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  let accepted!: (task: KnowledgeAnswerTask) => void
  const submit = vi.spyOn(bridge, 'submitKnowledgeAnswer').mockImplementation(() => new Promise(resolve => { accepted = resolve }))
  const get = vi.spyOn(bridge, 'getKnowledgeAnswer').mockRejectedValueOnce(new BridgeError('RuntimeUnavailable', '合成状态读取失败。')).mockResolvedValue(port.entry().task)
  const props = { bridge, enabled: true, open: async () => {} }
  const view = render(<KnowledgeAnswer {...props} visible />); edit(); vi.useFakeTimers()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '基于知识回答' })); fireEvent.click(screen.getByRole('button', { name: '基于知识回答' })) })
  view.rerender(<KnowledgeAnswer {...props} visible={false} />)
  await act(async () => accepted({ ...port.entry().task, status: 'QUEUED', result: null }))
  await act(async () => vi.advanceTimersByTimeAsync(5000)); expect(get).not.toHaveBeenCalled(); expect(submit).toHaveBeenCalledTimes(1)
  view.rerender(<KnowledgeAnswer {...props} visible />); await act(async () => vi.advanceTimersByTimeAsync(1001))
  expect(screen.getByRole('alert').textContent).toContain('合成状态读取失败')
  expect((screen.getByRole('button', { name: '基于知识回答' }) as HTMLButtonElement).disabled).toBe(true)
  await click('重新核对回答状态'); expect(screen.getByRole('button', { name: '打开引用来源' })).toBeTruthy()
  expect(screen.getByRole('region', { name: '当前回答对应的问题' }).textContent).toContain('要保留的新问题😀')
  expect(submit).toHaveBeenCalledTimes(1); expect(get).toHaveBeenCalledTimes(2)
})
it('knowledge continuity keeps unknown submissions blocked while browsing history and discards late task replies on session replacement', async () => {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const submit = vi.spyOn(bridge, 'submitKnowledgeAnswer').mockRejectedValueOnce(new BridgeError('OutcomeUnknown', '合成提交结果未知。'))
  const props = { bridge, enabled: true, open: async () => {} }
  const view = render(<KnowledgeAnswer {...props} visible />); edit(); await click('基于知识回答')
  view.rerender(<KnowledgeAnswer {...props} visible={false} />); view.rerender(<KnowledgeAnswer {...props} visible />)
  expect(screen.getByText(/本次提交结果未确认，本页已阻止重复提交/)).toBeTruthy()
  await click('查看问答历史'); await click(port.entry().question); await click('读取历史并替换草稿')
  await click('基于知识回答'); await click('开始新问题'); expect(submit).toHaveBeenCalledTimes(1)
  expect((screen.getByRole('button', { name: '基于知识回答' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'))
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('')
  let accepted!: (task: KnowledgeAnswerTask) => void
  submit.mockImplementationOnce(() => new Promise(resolve => { accepted = resolve }))
  edit(); await click('基于知识回答')
  await act(async () => { port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'); accepted(port.entry().task) })
  expect(screen.queryByRole('region', { name: '当前回答对应的问题' })).toBeNull()
  expect(screen.queryByRole('button', { name: '打开引用来源' })).toBeNull()
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('')
})
