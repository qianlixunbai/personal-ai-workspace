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
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges(); clients.splice(0).forEach(c => c.dispose()); history.replaceState(null, '', '#/assistant') })
async function mount() {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/conversations'); render(<App bridge={bridge} />); await act(async () => port.session())
  const draft = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '继续讨论适用范围。' } }); return { port, draft }
}
async function choose(port: AnswerHistoryService) {
  await click('浏览已保存的问答'); await click('阅读问答：' + port.entry().question)
  await click('摘录并保留问答出处'); await click('选择整条回答'); await click('核对并加入回答参考')
}
const basket = () => screen.getByRole('region', { name: '待插入的会话参考' })
it('browses saved answers across pages, reviews old citations and explicitly inserts only selected answer text into the current conversation', async () => {
  const { port, draft } = await mount(); port.historySize = 21
  await click('准备会话参考…'); await click('浏览已保存的问答'); await click('下一页问答浏览')
  await click('阅读问答：' + port.entry().question + ' 21')
  expect(port.sent.filter(r => r.method === 'knowledge.answerHistoryGet').at(-1)?.payload).toEqual({ taskId: '00000000-0000-4000-8000-000000000020' })
  await click('核对引用原文 1'); expect(screen.getByLabelText('问答引用原文').textContent).toBe(port.sourceText.slice(7))
  await click('摘录并保留问答出处')
  await act(async () => {
    const pre = screen.getByLabelText('可选取的回答原文'), range = document.createRange()
    range.setStart(pre.firstChild!, 0); range.setEnd(pre.firstChild!, 10)
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range); document.dispatchEvent(new Event('selectionchange'))
  })
  await click('核对并加入回答参考'); await click('核对并加入回答参考')
  expect(within(basket()).getAllByRole('button', { name: '移除参考' })).toHaveLength(1)
  expect(draft.value).toBe('继续讨论适用范围。')
  await click('回查来源', basket()); expect(screen.getByLabelText('待插入回答片段').textContent).toBe(port.answerText.slice(0, 10))
  const final = screen.getByLabelText('完整消息预览').textContent!
  expect(final).toContain('未经事实核实'); expect(final).not.toContain(port.sourceText); expect(final).not.toContain('<script>')
  await click('核对来源并插入草稿'); expect(draft.value).toBe(final); expect(screen.queryByRole('dialog')).toBeNull()
  expect(port.sent.some(r => /answerSubmit|proposeMemory|conversations.send|selectMemories|memory.create/.test(String(r.method)))).toBe(false)
})
it('preserves the draft when an answer changes or disappears and ignores insertion after draft edits, closing and session rotation', async () => {
  const { port, draft } = await mount(), original = draft.value
  await click('准备会话参考…'); await choose(port)
  port.answerText += '变化'; await click('核对来源并插入草稿')
  expect(within(basket()).getByRole('alert').textContent).toContain('问答或引用已变化'); expect(draft.value).toBe(original)
  await click('移除参考', basket()); await click('收起问答阅读')
  await click('阅读问答：' + port.entry().question); await click('摘录并保留问答出处'); await click('选择整条回答'); await click('核对并加入回答参考')
  port.missingHistory = true; await click('核对来源并插入草稿'); expect(draft.value).toBe(original)
  expect(within(basket()).getByRole('alert').textContent).toContain('已删除或不可用')
  port.missingHistory = false; port.holdHistory = true; await click('核对来源并插入草稿'); let pending = port.sent.length - 1
  fireEvent.change(draft, { target: { value: '新的手动草稿' } })
  await act(async () => port.reply(pending, port.entry())); expect(draft.value).toBe('新的手动草稿')
  await click('关闭参考准备'); port.holdHistory = false
  await click('准备会话参考…'); await choose(port); port.holdHistory = true
  await click('核对来源并插入草稿'); pending = port.sent.length - 1
  await click('关闭参考准备'); await act(async () => port.reply(pending, port.entry())); expect(draft.value).toBe('新的手动草稿')
  port.holdHistory = false; await click('准备会话参考…'); await choose(port); port.holdHistory = true
  await click('核对来源并插入草稿'); pending = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(pending, port.entry()) })
  expect(screen.queryByRole('dialog')).toBeNull(); expect(draft.value).not.toContain('参考摘录')
  expect(port.sent.some(r => /answerSubmit|proposeMemory|conversations.send|selectMemories|memory.create/.test(String(r.method)))).toBe(false)
})
