import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { MemoryPage } from '../pages/MemoryPage'
import { MemoryService, memoryItem } from '../test/memoryFixtures'
import { conversation, history, uuid } from '../test/conversationFixtures'

class UsageService extends MemoryService {
  hold = false; wrongEcho = false; wrongReference = false
  override postMessage(request: unknown) {
    const value = request as Record<string, unknown>, payload = value.payload as Record<string, unknown>
    if (!['conversations.memoryUsage', 'conversations.get'].includes(String(value.method))) { super.postMessage(request); return }
    this.sent.push(value); const index = this.sent.length - 1
    if (this.hold) return
    queueMicrotask(() => {
      if (value.method === 'conversations.memoryUsage') {
        const page = payload.page as number, revision = payload.revision as string | null
        this.reply(index, { memoryId: this.wrongEcho ? uuid(900) : memoryItem().id, revision, page, limit: 10, hasMore: page === 0 && revision === null,
          items: Array.from({ length: page === 0 && revision === null ? 10 : 1 }, (_, index) => ({
            conversation: { ...conversation, status: 'ARCHIVED' }, turnId: uuid(page * 10 + index + 101), sequence: page * 10 + index + 1,
            status: 'SUCCEEDED', revision: revision ?? '1' })) }); return
      }
      const detail = history(11, payload.page as number)
      detail.conversation = { ...conversation, status: 'ARCHIVED' }
      detail.turns.forEach(turn => { turn.memoryReferences = [{ memoryId: this.wrongReference ? uuid(900) : memoryItem().id, revision: '1', position: 0 }] })
      this.reply(index, detail)
    })
  }
}
const clients: WorkspaceClient[] = []
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.dispose()) })
const click = async (name: string | RegExp) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
async function setup() {
  const port = new UsageService(), bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const props = { bridge, enabled: true, visible: true, registerLeave: () => () => {}, openBackup: () => {} }
  const view = render(<MemoryPage {...props} />)
  await screen.findByRole('button', { name: /^Synthetic Memory 1 PROJECT_NOTE/ }); await click(/^Synthetic Memory 1 PROJECT_NOTE/)
  return { port, bridge, props, view }
}
it('finds old and current selections across pages, rereads the exact archived turn and preserves an edited memory draft', async () => {
  const { port } = await setup()
  fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '未保存的修订正文' } })
  await click('查询使用记录'); await click('下一页使用记录'); await click('查看 Synthetic conversation 第 11 轮')
  const original = screen.getByRole('region', { name: '使用记忆的原轮次' })
  expect(within(original).getByText('Synthetic user 11')).toBeTruthy()
  expect(within(original).getByText('Synthetic answer 11')).toBeTruthy()
  expect((screen.getByLabelText('记忆内容') as HTMLTextAreaElement).value).toBe('未保存的修订正文')
  expect(port.sent.filter(x => x.method === 'conversations.get').at(-1)?.payload).toEqual({ conversationId: conversation.id, page: 1 })
  fireEvent.click(screen.getByRole('checkbox', { name: `仅查看已存版本 ${memoryItem().revision}` }))
  await click('查询使用记录')
  expect(port.sent.at(-1)?.payload).toEqual({ memoryId: memoryItem().id, revision: memoryItem().revision, page: 0 })
  expect(port.sent.some(x => ['memory.update', 'conversations.send', 'conversations.selectMemories'].includes(String(x.method)))).toBe(false)
})
it('rejects a mismatched response or changed reference and ignores a late result after leaving and returning', async () => {
  const { port, props, view } = await setup()
  port.wrongEcho = true; await click('查询使用记录'); expect(screen.getByRole('alert')).toBeTruthy()
  expect(screen.queryByRole('button', { name: /查看 Synthetic conversation/ })).toBeNull()
  port.wrongEcho = false; port.wrongReference = true; await click('查询使用记录'); await click('查看 Synthetic conversation 第 1 轮')
  expect(screen.getByRole('alert').textContent).toContain('引用已不可用')
  expect(screen.queryByText('Synthetic user 1')).toBeNull()
  port.hold = true; await click('查询使用记录'); const pending = port.sent.length - 1
  view.rerender(<MemoryPage {...props} visible={false} />); view.rerender(<MemoryPage {...props} />)
  await act(async () => port.reply(pending, { memoryId: memoryItem().id, revision: null, items: [], page: 0, limit: 10, hasMore: false }))
  expect(screen.queryByText('该记忆的所有记录版本 · 第 1 页')).toBeNull()
  expect(screen.getByRole('button', { name: '查询使用记录' }).hasAttribute('disabled')).toBe(false)
})
