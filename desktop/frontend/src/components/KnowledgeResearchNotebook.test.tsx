import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { AnswerHistoryService } from '../test/answerHistoryFixtures'

class ResearchService extends AnswerHistoryService {
  outcome: 'SAVED' | 'CANCELLED' | 'UNKNOWN' = 'SAVED'
  holdExport = false
  override postMessage(value: unknown) {
    const request = value as Record<string, unknown>
    if (request.method !== 'knowledge.researchExport') { super.postMessage(value); return }
    this.sent.push(request); const index = this.sent.length - 1
    if (this.holdExport) return
    queueMicrotask(() => this.outcome === 'UNKNOWN'
      ? this.reply(index, undefined, { ok: false, error: { code: 'OutcomeUnknown', message: '保存回执未知。' } })
      : this.reply(index, { outcome: this.outcome }))
  }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()); history.replaceState(null, '', '#/assistant') })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
const addFirst = async () => {
  const choices = screen.getByRole('list', { name: '可加入研究笔记的问答' })
  await act(async () => fireEvent.click(within(choices).getAllByRole('button', { name: '加入研究笔记' })[0]!))
}
async function setup(size = 1) {
  const port = new ResearchService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  port.historySize = size; port.enforceHistoryGrant = true
  history.replaceState(null, '', '#/knowledge'); render(<App bridge={bridge} />); await act(async () => port.session())
  await click('查看问答历史'); return { port, bridge }
}
it('collects saved answers across pages, preserves the question draft, orders IDs and saves once without model submission', async () => {
  const { port } = await setup(21)
  const draft = screen.getByLabelText('想了解什么') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '尚未提交的私人草稿' } })
  await addFirst(); await click('下一页问答'); await addFirst()
  expect(screen.getByText('研究清单：2 / 10 条')).toBeTruthy()
  await click('上移第 2 条问答')
  await click('核对并导出研究笔记…')
  expect(screen.getByText('研究笔记已保存，包含全部所选回答及引用原文。')).toBeTruthy()
  const exports = port.sent.filter(request => request.method === 'knowledge.researchExport')
  expect(exports).toHaveLength(1)
  expect(exports[0]!.payload).toEqual({ taskIds: ['00000000-0000-4000-8000-000000000020', port.entry().task.taskId] })
  expect(draft.value).toBe('尚未提交的私人草稿')
  expect(port.sent.some(request => ['knowledge.answerSubmit', 'conversations.send'].includes(String(request.method)))).toBe(false)
})
it('keeps the selection after missing history or cancelled save and blocks automatic retry of an uncertain save', async () => {
  const { port } = await setup()
  await addFirst(); port.missingHistory = true
  await click('核对并导出研究笔记…')
  expect(screen.getByRole('alert').textContent).toContain('原问答已删除或不可用')
  expect(port.sent.filter(request => request.method === 'knowledge.researchExport')).toHaveLength(0)
  expect(screen.getByText('研究清单：1 / 10 条')).toBeTruthy()
  port.missingHistory = false; port.outcome = 'CANCELLED'
  await click('核对并导出研究笔记…')
  expect(screen.getByText('已取消保存，研究清单仍保留。')).toBeTruthy()
  port.outcome = 'UNKNOWN'; await click('核对并导出研究笔记…')
  expect((screen.getByRole('button', { name: '核对并导出研究笔记…' }) as HTMLButtonElement).disabled).toBe(true)
  await click('核对并导出研究笔记…')
  expect(port.sent.filter(request => request.method === 'knowledge.researchExport')).toHaveLength(2)
  await click('我已检查保存位置和原生窗口')
  expect(port.sent.filter(request => request.method === 'knowledge.researchExport')).toHaveLength(2)
})
it('pauses reads across navigation, clears session-owned selection and ignores a late save receipt after reconnecting', async () => {
  const { port, bridge } = await setup()
  await expect(bridge.exportKnowledgeResearch([])).rejects.toThrow()
  await expect(bridge.exportKnowledgeResearch([port.entry().task.taskId, port.entry().task.taskId])).rejects.toThrow()
  await addFirst(); port.holdHistory = true
  const readStart = port.sent.length
  await click('核对并导出研究笔记…')
  const reading = port.sent.findIndex((request, index) => index >= readStart && request.method === 'knowledge.answerHistoryGet')
  expect(reading).toBeGreaterThanOrEqual(0)
  await act(async () => fireEvent.click(screen.getByRole('link', { name: '助手' })))
  await act(async () => fireEvent.click(screen.getByRole('link', { name: '知识库' })))
  await act(async () => port.reply(reading, port.entry()))
  expect(port.sent.filter(request => request.method === 'knowledge.researchExport')).toHaveLength(0)
  expect(screen.getByRole('alert').textContent).toContain('已暂停核对')
  port.holdHistory = false; port.holdExport = true; await click('核对并导出研究笔记…')
  const index = port.sent.findIndex(request => request.method === 'knowledge.researchExport')
  expect(index).toBeGreaterThanOrEqual(0)
  await act(async () => port.session('22222222-2222-4222-8222-222222222222'))
  await act(async () => port.reply(index, { outcome: 'SAVED' }))
  expect(screen.getByText('研究清单：0 / 10 条')).toBeTruthy()
  expect(screen.queryByText('研究笔记已保存，包含全部所选回答及引用原文。')).toBeNull()
  expect(screen.queryByRole('button', { name: '我已检查保存位置和原生窗口' })).toBeNull()
})
