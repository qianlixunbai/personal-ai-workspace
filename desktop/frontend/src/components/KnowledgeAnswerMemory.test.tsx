import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { AnswerHistoryService } from '../test/answerHistoryFixtures'
import { KnowledgeAnswerExcerpt } from './KnowledgeAnswerExcerpt'
import { MemorySourceReview } from './MemorySourceReview'
import { formatAnswerSource } from './memoryAnswerSource'

const clients: WorkspaceClient[] = []
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges(); clients.splice(0).forEach(c => c.dispose()); history.replaceState(null, '', '#/assistant') })
async function select(start: number, end: number) {
  const pre = screen.getByLabelText('可选取的回答原文')
  await act(async () => { const range = document.createRange(); range.setStart(pre.firstChild!, start); range.setEnd(pre.firstChild!, end)
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range); document.dispatchEvent(new Event('selectionchange')) })
}
it('saves a reviewed answer excerpt in Memory and reopens its question and exact old-version citations without a model', async () => {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/knowledge'); render(<App bridge={bridge} />); await act(async () => port.session())
  await click('查看问答历史'); await click(port.entry().question)
  await click('摘录并保留问答出处'); await select(0, 10)
  const draft = screen.getByLabelText('带问答出处的草稿预览').textContent!
  await click('核对问答并送入记忆草稿')
  expect(location.hash).toBe('#/memory'); expect(port.memory.items).toHaveLength(0)
  const content = screen.getByLabelText('记忆内容') as HTMLTextAreaElement
  expect(content.value).toBe(draft)
  fireEvent.change(screen.getByLabelText('记忆标题'), { target: { value: '问答摘录笔记' } })
  await click('保存'); await click('新建'); await click(/问答摘录笔记/)
  await click('查看原问答与引用'); expect(screen.getByLabelText('所记回答片段').textContent).toBe(port.answerText.slice(0, 10))
  fireEvent.click(screen.getByText('阅读原问题与完整回答')); expect(screen.getByText(port.entry().question)).toBeTruthy()
  await click('核对引用原文 1'); expect(screen.getByLabelText('问答引用原文').textContent).toBe(port.sourceText.slice(7))
  expect(port.sent.filter(r => r.method === 'knowledge.preview').map(r => (r.payload as Record<string, unknown>).sourceRevision)).toEqual(['1', '1'])
  fireEvent.change(content, { target: { value: '人工改写' + draft } }); await click('查看原问答与引用')
  expect(screen.getByText(/正文与原回答不同/)).toBeTruthy(); expect(content.value).toBe('人工改写' + draft)
  port.missingSource = true; await click('核对引用原文 1'); expect(screen.getByRole('alert').textContent).toContain('未回退到新版')
  port.missingHistory = true; await click('查看原问答与引用'); expect(screen.getByRole('alert').textContent).toContain('已删除或不可用')
  expect(port.sent.filter(r => r.method === 'memory.create')).toHaveLength(1)
  expect(port.sent.some(r => /answerSubmit|proposeMemory|conversations.send|selectMemories/.test(String(r.method)))).toBe(false)
  expect(document.querySelector('script,img,iframe')).toBeNull()
})
it('rejects oversized, split-character and changed answers and drops reads after closing, editing or session replacement', async () => {
  const port = new AnswerHistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const receive = vi.fn(); port.answerText = '中'.repeat(2000) + '😀'
  let task = port.entry().task
  const view = render(<KnowledgeAnswerExcerpt bridge={bridge} task={task} enabled onDraft={receive} />)
  await click('摘录并保留问答出处'); await click('选择整条回答')
  expect((screen.getByRole('button', { name: '核对问答并送入记忆草稿' }) as HTMLButtonElement).disabled).toBe(true)
  await select(2000, 2001); expect(screen.queryByLabelText('带问答出处的草稿预览')).toBeNull()
  await select(0, 10); port.answerText = '变更后'; await click('核对问答并送入记忆草稿')
  expect(screen.getByRole('alert').textContent).toContain('超出原回答'); expect(receive).not.toHaveBeenCalled()
  task = port.entry().task; view.rerender(<KnowledgeAnswerExcerpt bridge={bridge} task={task} enabled onDraft={receive} />)
  await click('摘录并保留问答出处'); await click('选择整条回答'); port.holdHistory = true
  await click('核对问答并送入记忆草稿'); let pending = port.sent.length - 1
  await click('收起回答摘录'); await act(async () => port.reply(pending, port.entry())); expect(receive).not.toHaveBeenCalled()
  view.unmount()
  const content = formatAnswerSource({ taskId: task.taskId, excerpt: port.answerText, startOffset: 0, endOffset: port.answerText.length })
  const reader = render(<MemorySourceReview bridge={bridge} content={content} />)
  await click('查看原问答与引用'); pending = port.sent.length - 1
  reader.rerender(<MemorySourceReview bridge={bridge} content={'编辑后' + content} />)
  await act(async () => port.reply(pending, port.entry())); expect(screen.queryByLabelText('所记回答片段')).toBeNull()
  await click('查看原问答与引用'); pending = port.sent.length - 1
  await act(async () => { port.session('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'); port.reply(pending, port.entry()) })
  expect(screen.queryByLabelText('所记回答片段')).toBeNull()
  reader.rerender(<MemorySourceReview bridge={bridge} content={content + content} />)
  expect(screen.queryByRole('button', { name: '查看原问答与引用' })).toBeNull()
  expect(port.sent.every(r => r.method === 'knowledge.answerHistoryGet')).toBe(true)
})
