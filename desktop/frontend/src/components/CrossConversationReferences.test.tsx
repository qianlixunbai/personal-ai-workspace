import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { MemoryService } from '../test/memoryFixtures'
import { conversation, history as conversationHistory, uuid } from '../test/conversationFixtures'

class HistoryService extends MemoryService {
  sourceId = uuid(88)
  body = '说明\n旧计划😀<script>仅作文本</script>\n后续内容'
  holdSource = false; missing = false
  constructor() { super(); this.items = [] }
  detail(id: string, page = 0) {
    const source = id === this.sourceId, result = conversationHistory(source ? 11 : 1, page)
    result.conversation = { ...conversation, id, title: source ? '旧项目对话' : '当前草稿会话', status: source ? 'ARCHIVED' : 'ACTIVE' }
    for (const turn of result.turns) {
      if (source) {
        turn.turnId = uuid(300 + turn.sequence)
        turn.userMessage.messageId = uuid(400 + turn.sequence * 2); turn.userMessage.content = '旧计划提问仅供背景'
        turn.assistantMessage!.messageId = uuid(401 + turn.sequence * 2); turn.assistantMessage!.content = this.body
      } else turn.memoryReferences = []
    }
    return result
  }
  override postMessage(value: unknown) {
    const request = value as { method: string; payload: Record<string, unknown> }, { method, payload } = request
    if (!method.startsWith('conversations.') && method !== 'knowledge.search') { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
    if (method === 'conversations.get' && payload.conversationId === this.sourceId && this.holdSource) return
    queueMicrotask(() => {
      if (method === 'knowledge.search') this.reply(index, { hits: [] })
      else if (method === 'conversations.list') this.reply(index, { items: [this.detail(conversation.id).conversation], total: 1, page: 0, limit: 10 })
      else if (method === 'conversations.clearMemories') this.reply(index, { cleared: true })
      else if (method === 'conversations.get') {
        if (this.missing && payload.conversationId === this.sourceId) {
          const pending = this.sent[index]!
          this.emit({ version: 1, sessionId: pending.sessionId, requestId: pending.requestId, ok: false, error: { code: 'ConversationNotFound', message: '所选旧会话已不存在。' } })
        } else this.reply(index, this.detail(String(payload.conversationId), Number(payload.page)))
      } else if (method === 'conversations.search') {
        const source = this.detail(this.sourceId, 1), turn = source.turns[0]!, query = String(payload.query)
        const items = [turn.userMessage, turn.assistantMessage!].filter(message => message.content.includes(query)).map(message => ({
          conversation: source.conversation, turnId: turn.turnId, sequence: turn.sequence, messageId: message.messageId, role: message.role,
          snippet: message.content.slice(0, 100), matchStart: message.content.indexOf(query), matchEnd: message.content.indexOf(query) + query.length, before: false, after: message.content.length > 100,
        }))
        this.reply(index, { query, scope: payload.scope, page: payload.page, limit: 10, hasMore: false, items })
      }
    })
  }
}
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges(); clients.splice(0).forEach(client => client.dispose()); history.replaceState(null, '', '#/assistant') })
const click = async (name: string | RegExp, root?: HTMLElement) => { await act(async () => fireEvent.click((root ? within(root) : screen).getByRole('button', { name }))) }
const basket = () => screen.getByRole('region', { name: '待插入的会话参考' })
async function search(query = '旧计划') {
  fireEvent.change(screen.getByLabelText('工作区关键词'), { target: { value: query } }); await click('查找')
}
async function open() { await click('准备会话参考…'); await search() }
async function choose(assistant = true) {
  const group = screen.getByRole('region', { name: '工作区对话结果' })
  await click(assistant ? /第 11 轮 · 助手回复/ : /第 11 轮 · 用户消息/, group)
}
async function select(start: number, end: number) {
  const pre = screen.getByLabelText('命中的历史消息原文')
  const point = (offset: number): [Node, number] => {
    const walker = document.createTreeWalker(pre, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (offset <= node.textContent!.length) return [node, offset]
      offset -= node.textContent!.length
    }
    throw new Error('Synthetic range outside message')
  }
  await act(async () => {
    const range = document.createRange(); range.setStart(...point(start)); range.setEnd(...point(end))
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
  })
}
async function mount() {
  const port = new HistoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  history.replaceState(null, '', '#/conversations'); render(<App bridge={bridge} />); await act(async () => port.session())
  const draft = await screen.findByLabelText('会话消息') as HTMLTextAreaElement
  fireEvent.change(draft, { target: { value: '想继续讨论。' } })
  return { port, draft }
}
const assertNoSubmission = (port: HistoryService) => expect(port.sent.some(request => /models\.|proposeMemory|assistant\.submit|conversations\.send|selectMemories|memory\.(create|update)/.test(String(request.method)))).toBe(false)

it('copies only explicit old-message excerpts into the current draft after review, exact readback and source verification', async () => {
  const { port, draft } = await mount()
  await open(); await choose()
  const start = port.body.indexOf('旧计划'), end = port.body.indexOf('\n后续')
  await select(start, end); await click('将选中历史消息加入参考'); await click('将选中历史消息加入参考')
  expect(within(basket()).getAllByRole('listitem')).toHaveLength(1)
  const entry = within(basket()).getAllByRole('listitem')[0]!
  expect(entry.querySelector('pre')?.textContent).toBe(port.body.slice(start, end))
  expect(entry.textContent).toContain(`会话 ID：${port.sourceId}`)
  expect(entry.textContent).toContain(`消息 ID：${uuid(423)}；助手回复`)
  expect(draft.value).toBe('想继续讨论。')
  await choose(false); await click('将整条命中消息加入参考')
  expect(within(basket()).getAllByRole('listitem')).toHaveLength(2)
  await click('移除参考', within(basket()).getAllByRole('listitem')[1]!)
  // A reference must remain readable even after searching for a different phrase.
  await search('完全不同的词'); await click('回查来源', basket())
  expect(screen.getByLabelText('命中的历史消息原文').querySelector('mark')?.textContent).toBe(port.body.slice(start, end))
  const preview = within(basket()).getByLabelText('完整消息预览').textContent!
  expect(preview.startsWith('想继续讨论。\n\n参考摘录：')).toBe(true)
  expect(preview).not.toContain('旧计划提问仅供背景')
  const before = port.sent.length
  await click('核对来源并插入草稿')
  expect(draft.value).toBe(preview)
  expect(screen.queryByRole('dialog', { name: '准备会话参考' })).toBeNull()
  expect(screen.getByRole('heading', { name: '当前草稿会话' })).toBeTruthy()
  expect(port.sent.slice(before).map(request => ({ method: request.method, payload: request.payload }))).toEqual([
    { method: 'conversations.get', payload: { conversationId: port.sourceId, page: 1 } },
  ])
  expect(document.querySelector('script,img,iframe')).toBeNull(); assertNoSubmission(port)
})

it('keeps the target draft intact for oversized, changed or deleted sources and ignores closed, edited and replaced preparations', async () => {
  const { port, draft } = await mount(), original = port.body
  port.body = '旧计划' + 'a'.repeat(3000)
  await open(); await choose(); await click('将整条命中消息加入参考')
  expect(within(basket()).getByRole('alert').textContent).toContain('不会截断')
  expect((within(basket()).getByRole('button', { name: '核对来源并插入草稿' }) as HTMLButtonElement).disabled).toBe(true)
  expect(within(basket()).getByLabelText('完整消息预览').textContent).toContain(port.body)
  await click('关闭参考准备'); expect(draft.value).toBe('想继续讨论。')
  port.body = original
  await open(); await choose(false); await click('将整条命中消息加入参考'); await choose(); await click('将整条命中消息加入参考')
  port.body = original.replace('旧计划', '新计划')
  const before = port.sent.length
  await click('核对来源并插入草稿')
  expect(within(basket()).getByRole('alert').textContent).toContain('未插入任何内容')
  expect(port.sent.slice(before).filter(request => request.method === 'conversations.get')).toHaveLength(2)
  expect(draft.value).toBe('想继续讨论。')
  port.missing = true; await click('核对来源并插入草稿')
  expect(within(basket()).getByRole('alert').textContent).toContain('旧会话已不存在')
  expect(draft.value).toBe('想继续讨论。')
  await click('关闭参考准备'); port.missing = false; port.body = original
  await open(); await choose(); await click('将整条命中消息加入参考')
  fireEvent.change(draft, { target: { value: '后来编辑的问题' } }); await click('核对来源并插入草稿')
  expect(draft.value).toBe('后来编辑的问题')
  expect(within(basket()).getByRole('alert').textContent).toContain('当前会话或草稿已变化')
  await click('关闭参考准备'); await open(); await choose(); await click('将整条命中消息加入参考')
  port.holdSource = true; await click('核对来源并插入草稿')
  let pending = port.sent.length - 1
  await click('关闭参考准备'); await open()
  await act(async () => port.reply(pending, port.detail(port.sourceId, 1)))
  expect(within(basket()).queryAllByRole('listitem')).toHaveLength(0); expect(draft.value).toBe('后来编辑的问题')
  port.holdSource = false; await choose(); await click('将整条命中消息加入参考')
  port.holdSource = true; await click('核对来源并插入草稿'); pending = port.sent.length - 1
  await act(async () => { port.session(uuid(909)); port.reply(pending, port.detail(port.sourceId, 1)) })
  expect(screen.queryByRole('dialog', { name: '准备会话参考' })).toBeNull()
  expect((await screen.findByLabelText('会话消息') as HTMLTextAreaElement).value).toBe('')
  assertNoSubmission(port)
})
