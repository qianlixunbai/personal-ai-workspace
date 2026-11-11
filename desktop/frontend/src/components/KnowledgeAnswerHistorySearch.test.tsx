import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { AnswerHistoryService } from '../test/answerHistoryFixtures'

const clients: WorkspaceClient[] = []
const click = async (name: string | RegExp, root?: HTMLElement) => { await act(async () => fireEvent.click((root ? within(root) : screen).getByRole('button', { name }))) }
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.dispose()); history.replaceState(null, '', '#/assistant') })
it('searches saved answer text, keeps the submitted filter through paging and inserts reviewed results without sending the conversation', async () => {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  port.historySize = 21; port.enforceHistoryGrant = true; port.answerText += ' %_😀精确'
  history.replaceState(null, '', '#/conversations'); render(<App bridge={bridge} />); await act(async () => port.session())
  const draft = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '保留手动问题' } }); await click('准备会话参考…')
  const query = within(screen.getByRole('dialog')).getByLabelText('问答历史关键词')
  fireEvent.change(query, { target: { value: '%_😀精确' } })
  await click('查找已保存问答'); expect(draft.value).toBe('保留手动问题')
  fireEvent.change(query, { target: { value: '尚未提交的其他搜索' } })
  await click('下一页问答浏览')
  expect(port.sent.filter(r => r.method === 'knowledge.answerHistorySearch').map(r => r.payload)).toEqual([{ query: '%_😀精确', page: 0 }, { query: '%_😀精确', page: 1 }])
  await click('阅读问答：' + port.entry().question + ' 21')
  expect(screen.getByRole('region', { name: '已保存问答阅读' }).textContent).toContain('%_😀精确')
  await click('核对引用原文 1'); expect(screen.getByLabelText('问答引用原文').textContent).toBe(port.sourceText.slice(7))
  await click('摘录并保留问答出处'); await click('选择整条回答'); await click('核对并加入回答参考')
  const message = screen.getByLabelText('完整消息预览').textContent
  await click('核对来源并插入草稿'); expect(draft.value).toBe(message)
  expect(port.sent.some(r => /answerSubmit|conversations.send|memory.create/.test(String(r.method)))).toBe(false)
})
it('keeps knowledge question drafts while searching and rejects mismatched or late search responses', async () => {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/knowledge'); render(<App bridge={bridge} />); await act(async () => port.session())
  fireEvent.change(screen.getByLabelText('想了解什么'), { target: { value: '未提交的新问题' } })
  fireEvent.change(screen.getByLabelText('回答检索关键词'), { target: { value: '新关键词' } })
  const query = screen.getByLabelText('问答历史关键词')
  fireEvent.change(query, { target: { value: '没有这个结果' } }); await click('查找已保存问答')
  expect(screen.getByText('没有匹配的问答记录')).toBeTruthy()
  fireEvent.change(query, { target: { value: '原始问题' } }); await click('查找已保存问答')
  await click(port.entry().question); await click('取消')
  expect((screen.getByLabelText('想了解什么') as HTMLTextAreaElement).value).toBe('未提交的新问题')
  port.holdSearch = true; await click('查找已保存问答'); let index = port.sent.length - 1
  await act(async () => port.reply(index, { query: '不同搜索', results: { items: [], total: 0, page: 0, limit: 20 } }))
  expect(screen.getByRole('alert').textContent).toContain('无效响应')
  expect(screen.queryByRole('region', { name: '问答历史' })).toBeNull()
  await click('查找已保存问答'); index = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(index, { query: '原始问题', results: { items: [], total: 0, page: 0, limit: 20 } }) })
  expect(screen.queryByRole('region', { name: '问答历史' })).toBeNull()
  expect((screen.getByLabelText('问答历史关键词') as HTMLInputElement).value).toBe('')
  expect(port.sent.some(r => /answerSubmit|answerHistoryDelete/.test(String(r.method)))).toBe(false)
})
