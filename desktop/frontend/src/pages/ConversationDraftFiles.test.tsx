import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { ConversationsPage } from './ConversationsPage'
import { WorkspaceClient } from '../bridge/client'
import type { ConversationDraftFile } from '../bridge/conversations'
import { conversation, history, uuid } from '../test/conversationFixtures'
import { Port } from '../test/fixtures'

class DraftService extends Port {
  saved: ConversationDraftFile | null = null
  cancel = false; hold = false; saveUnknown = false
  postMessage(value: unknown) {
    super.postMessage(value)
    const index = this.sent.length - 1, { method, payload } = value as { method: string; payload: Record<string, unknown> }
    if (method === 'conversations.openDraft' && this.hold) return
    queueMicrotask(() => {
      if (method === 'conversations.list') this.reply(index, { items: [conversation], total: 1, page: 0, limit: 10 })
      else if (method === 'conversations.get') this.reply(index, history())
      else if (method === 'conversations.clearMemories') this.reply(index, { cleared: true })
      else if (method === 'conversations.selectMemories') this.reply(index, { changed: true, selectedMemoryRefs: [{ memoryId: uuid(800), revision: '1', position: 0, title: '先前选择' }] })
      else if (method === 'conversations.saveDraft') {
        if (this.saveUnknown) { this.reply(index, null, { ok: false, error: { code: 'ConversationDraftSaveUnknown', message: '未确认保存' } }); return }
        if (!this.cancel) this.saved = { conversationId: String(payload.conversationId), conversationCreatedAt: conversation.createdAt, conversationTitle: conversation.title,
          message: String(payload.message), totalTurns: 1, savedAt: '2026-11-10T00:00:00Z' }
        this.reply(index, { outcome: this.cancel ? 'CANCELLED' : 'SAVED' })
      } else if (method === 'conversations.openDraft') this.reply(index, this.cancel ? { outcome: 'CANCELLED', draft: null } : { outcome: 'OPENED', draft: this.saved })
    })
  }
}
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function mount(port = new DraftService()) {
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<ConversationsPage bridge={bridge} enabled visible />)
  const input = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.click(screen.getByText('保存或恢复草稿文件'))
  return { port, bridge, view, input }
}
const writes = (port: DraftService) => port.sent.filter(request => request.method === 'conversations.saveDraft')
it('saves only an explicit snapshot and restores its exact text after reopening into the same conversation with review and no send', async () => {
  const first = await mount(), original = ' 问题\n中文😀<script>仅文本</script>\n参考摘录：\n资料 ID：可编辑定位\n '
  fireEvent.change(first.input, { target: { value: original } }); expect(writes(first.port)).toHaveLength(0)
  await click('保存草稿为新文件…'); expect(first.input.value).toBe(original); expect(first.port.saved?.message).toBe(original)
  fireEvent.change(first.input, { target: { value: '后来编辑，不在已存文件中' } }); expect(first.port.saved?.message).toBe(original)
  first.view.unmount(); first.bridge.dispose()
  const next = await mount(Object.assign(new DraftService(), { saved: first.port.saved }))
  fireEvent.change(next.input, { target: { value: '重开后写的新草稿' } })
  await click('选择记忆…'); await click('打开草稿文件…')
  let dialog = screen.getByRole('dialog', { name: '恢复文件中的会话草稿？' })
  expect(dialog.querySelectorAll('pre')[1]?.textContent).toBe(original)
  expect(dialog.querySelector('script,img,iframe')).toBeNull(); expect(next.input.value).toBe('重开后写的新草稿')
  await click('取消'); expect(next.input.value).toBe('重开后写的新草稿')
  await click('打开草稿文件…'); dialog = screen.getByRole('dialog')
  const clears = next.port.sent.filter(request => request.method === 'conversations.clearMemories').length
  await click('替换当前消息草稿')
  expect(next.input.value).toBe(original); expect(screen.queryByRole('dialog')).toBeNull()
  expect(next.port.sent.filter(request => request.method === 'conversations.clearMemories')).toHaveLength(clears + 1)
  expect(screen.queryByText('先前选择')).toBeNull(); expect(writes(next.port)).toHaveLength(0)
  expect(next.port.sent.some(request => request.method === 'conversations.send' || String(request.method).startsWith('memory.'))).toBe(false)
})

it('preserves later drafts, rejects mismatched files, retains cancelled or uncertain saves and ignores late open results after session reset', async () => {
  const { port, input } = await mount()
  fireEvent.change(input, { target: { value: 'x'.repeat(3001) } })
  expect((screen.getByRole('button', { name: '保存草稿为新文件…' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(input, { target: { value: '原草稿' } }); await click('保存草稿为新文件…')
  port.cancel = true; await click('保存草稿为新文件…'); await click('打开草稿文件…')
  expect(screen.queryByRole('dialog')).toBeNull(); expect(input.value).toBe('原草稿')
  port.cancel = false; port.saveUnknown = true; await click('保存草稿为新文件…')
  expect(screen.getByRole('alert').textContent).toContain('保存结果未确认'); expect(writes(port)).toHaveLength(3)
  expect(input.value).toBe('原草稿'); port.saveUnknown = false
  await click('打开草稿文件…'); fireEvent.change(input, { target: { value: '后来草稿' } }); await click('替换当前消息草稿')
  expect(input.value).toBe('后来草稿'); expect(screen.getByRole('alert').textContent).toContain('会话或草稿已变化')
  port.saved = { ...port.saved!, conversationId: uuid(2) }; await click('打开草稿文件…')
  expect(screen.queryByRole('dialog')).toBeNull(); expect(input.value).toBe('后来草稿')
  port.saved = { ...port.saved!, conversationId: conversation.id, conversationCreatedAt: '2026-10-03T00:00:00Z' }; await click('打开草稿文件…')
  expect(screen.getByRole('alert').textContent).toContain('身份不一致'); expect(input.value).toBe('后来草稿')
  port.hold = true; await click('打开草稿文件…'); const held = port.sent.length - 1
  await act(async () => port.session('22222222-2222-4222-8222-222222222222'))
  await act(async () => port.reply(held, { outcome: 'OPENED', draft: { ...port.saved!, conversationCreatedAt: conversation.createdAt } }))
  expect(screen.queryByRole('dialog')).toBeNull(); expect((await screen.findByLabelText('会话消息') as HTMLTextAreaElement).value).toBe('')
  expect(port.sent.some(request => request.method === 'conversations.send' || String(request.method).startsWith('memory.'))).toBe(false)
})
