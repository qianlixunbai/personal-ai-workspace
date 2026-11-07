import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryPage } from './MemoryPage'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { candidatePrompt, parseCandidate } from '../components/memoryCandidate'
import { MemoryService, memoryItem } from '../test/memoryFixtures'
import { conversation, history as conversationHistory, uuid } from '../test/conversationFixtures'
import { Port } from '../test/fixtures'
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(x => x.dispose()); vi.restoreAllMocks(); history.replaceState(null, '', '/') })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function setup(port = new MemoryService()) {
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => { void bridge.open('native.openMemoryBackup') }} />)
  await screen.findByRole('button', { name: /^Synthetic Memory 1 PROJECT_NOTE/ })
  return { port, bridge, view, title: screen.getByLabelText('记忆标题') as HTMLInputElement, content: screen.getByLabelText('记忆内容') as HTMLTextAreaElement }
}
const edit = (input: HTMLElement, value: string) => fireEvent.change(input, { target: { value } })
it('previews one search result without replacing a dirty draft, rejects changed revisions and discards late hidden reads', async () => {
  const service = new MemoryService()
  const body = '第一段\r\n[a+b] literal <img src=x onerror=alert(1)>\n第二段 [a+b]'
  service.items = [{ ...memoryItem(), content: body }, { ...memoryItem(2), content: '[a+b] second saved memory' }]
  const { port, bridge, view, title, content } = await setup(service)
  edit(title, 'Unsent draft title'); edit(content, 'Unsent draft body')
  edit(screen.getByLabelText('搜索记忆'), '[a+b]'); await click('搜索')
  expect(port.sent.filter(x => x.method === 'memory.get')).toHaveLength(0)
  const preview = async (number: number) => {
    const row = screen.getByRole('button', { name: new RegExp(`^Synthetic Memory ${number} PROJECT_NOTE`) }).closest('li')!
    await act(async () => fireEvent.click(within(row).getByRole('button', { name: '预览正文' })))
  }
  await preview(1)
  const first = screen.getByRole('region', { name: '记忆只读预览' })
  expect(within(first).getByLabelText('预览正文').textContent).toBe(body)
  expect([...first.querySelectorAll('mark')].map(x => x.textContent)).toEqual(['[a+b]', '[a+b]'])
  expect(first.querySelector('img')).toBeNull()
  expect(title.value).toBe('Unsent draft title'); expect(content.value).toBe('Unsent draft body')
  expect(screen.queryByRole('dialog')).toBeNull()
  edit(screen.getByLabelText('搜索记忆'), 'not submitted')
  expect(first.querySelectorAll('mark')).toHaveLength(2) // highlight the submitted query only
  await preview(2)
  expect(screen.getAllByRole('region', { name: '记忆只读预览' })).toHaveLength(1)
  expect(screen.getByLabelText('预览正文').textContent).toBe('[a+b] second saved memory')
  expect(port.sent.filter(x => x.method === 'memory.get')).toHaveLength(2)

  service.items[0] = { ...service.items[0]!, revision: '9007199254740994', content: '[a+b] changed after search' }
  await preview(1)
  expect(screen.getByRole('alert').textContent).toContain('已变更')
  expect(screen.queryByLabelText('预览正文')).toBeNull()
  await click('刷新')
  expect(screen.queryByRole('region', { name: '记忆只读预览' })).toBeNull()
  let complete: (item: ReturnType<typeof memoryItem>) => void = () => {}
  vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await preview(1)
  expect(screen.getByRole('region', { name: '记忆只读预览' }).getAttribute('aria-busy')).toBe('true')
  view.rerender(<MemoryPage bridge={bridge} enabled visible={false} registerLeave={() => () => {}} openBackup={() => {}} />)
  await act(async () => complete(service.items[0]!))
  view.rerender(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => {}} />)
  expect(screen.queryByRole('region', { name: '记忆只读预览' })).toBeNull()
  expect(title.value).toBe('Unsent draft title'); expect(content.value).toBe('Unsent draft body')
  expect(port.sent.every(x => ['memory.list', 'memory.get', 'memory.editorState'].includes(String(x.method)))).toBe(true)
})
class ConversationMemoryService extends MemoryService {
  detail = conversationHistory()
  candidateStatus: 'SUCCEEDED' | 'QUEUED' = 'SUCCEEDED'
  candidateResult = JSON.stringify({ candidate: null })
  candidateError: string | null = null
  holdCandidate = false
  postMessage(request: unknown) {
    const method = (request as { method: string }).method
    if (method === 'assistant.proposeMemory' || method.startsWith('operations.')) {
      Port.prototype.postMessage.call(this, request)
      const index = this.sent.length - 1, sent = this.sent[index]!
      if (this.holdCandidate) return
      queueMicrotask(() => {
        if (this.candidateError) { this.emit({ version: 1, sessionId: sent.sessionId, requestId: sent.requestId, ok: false, error: { code: this.candidateError, message: `Safe ${this.candidateError}` } }); return }
        this.reply(index, method === 'operations.cancel' ? { requested: true } : { operationId: uuid(801), status: this.candidateStatus,
          result: this.candidateStatus === 'SUCCEEDED' ? this.candidateResult : null, error: null })
      }); return
    }
    if (!method.startsWith('conversations.')) { super.postMessage(request); return }
    Port.prototype.postMessage.call(this, request)
    const index = this.sent.length - 1
    queueMicrotask(() => {
      if (method === 'conversations.list') this.reply(index, { items: [conversation], total: 1, page: 0, limit: 10 })
      else if (method === 'conversations.get') this.reply(index, this.detail)
      else if (method === 'conversations.clearMemories') this.reply(index, { cleared: true })
    })
  }
}
async function setupConversationDraft(port = new ConversationMemoryService()) {
  history.replaceState(null, '', '#/conversations')
  const bridge = new WorkspaceClient(port); clients.push(bridge)
  render(<App bridge={bridge} />); await act(async () => port.session())
  await screen.findByRole('article')
  return { port, bridge, title: screen.getByLabelText('记忆标题') as HTMLInputElement, content: screen.getByLabelText('记忆内容') as HTMLTextAreaElement }
}
const chooseMessage = async (role: 'USER' | 'ASSISTANT') => {
  const turn = screen.getByRole('article')
  await act(async () => fireEvent.click(within(turn).getAllByRole('button', { name: '保存为记忆草稿' })[role === 'USER' ? 0 : 1]!))
}
const openConversations = async () => { await act(async () => fireEvent.click(screen.getByRole('link', { name: '对话' }))) }
it('generates editable grounded memory candidates and saves only after explicit adoption and Save', async () => {
  const port = new ConversationMemoryService()
  const original = '我喜欢简洁的中文回答。\n项目使用本地模型。'
  const proposed = { type: 'PREFERENCE', title: '回答偏好', content: '偏好简洁的中文回答。', evidence: '我喜欢简洁的中文回答。' }
  port.detail.turns[0]!.userMessage.content = original
  port.candidateStatus = 'QUEUED'
  port.candidateResult = JSON.stringify({ candidate: proposed })
  const { title, content } = await setupConversationDraft(port)
  await chooseMessage('USER')
  expect(port.sent.some(x => x.method === 'assistant.proposeMemory')).toBe(false)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '生成记忆候选' })); fireEvent.click(screen.getByRole('button', { name: '生成记忆候选' })) })
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory').map(x => x.payload)).toEqual([{ text: candidatePrompt(original, 'USER') }])
  port.candidateStatus = 'SUCCEEDED'; await screen.findByLabelText('候选标题')
  expect(title.value).toBe(''); expect(content.value).toBe(original)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update')).toBe(false)
  expect(screen.getByText(proposed.evidence)).toBeTruthy()
  edit(screen.getByLabelText('候选标题'), '已审阅的偏好'); edit(screen.getByLabelText('候选正文'), '请使用简洁中文。')
  await click('采用候选到编辑器')
  expect(title.value).toBe('已审阅的偏好'); expect(content.value).toBe('请使用简洁中文。')
  expect(port.sent.some(x => x.method === 'memory.create')).toBe(false)
  await click('保存')
  expect(port.sent.filter(x => x.method === 'memory.create').map(x => x.payload)).toEqual([{ type: 'PREFERENCE', title: '已审阅的偏好', content: '请使用简洁中文。' }])

  await openConversations(); await chooseMessage('ASSISTANT')
  port.candidateResult = JSON.stringify({ candidate: null }); await click('生成记忆候选')
  expect(screen.getByText(/模型未提出适合长期保留/)).toBeTruthy()
  expect((port.sent.filter(x => x.method === 'assistant.proposeMemory').at(-1)?.payload as { text: string }).text).toContain('"role":"ASSISTANT"')
  port.candidateResult = JSON.stringify({ candidate: { ...proposed, evidence: 'not in source' } }); await click('生成记忆候选')
  expect(screen.getByRole('alert').textContent).toContain('未采用，未保存')
  expect(screen.queryByLabelText('候选标题')).toBeNull()
  expect(content.value).toBe('Synthetic answer 1')
  edit(content, original)
  port.candidateResult = JSON.stringify({ candidate: proposed }); await click('生成记忆候选')
  edit(content, 'Changed during review')
  expect(screen.getByRole('button', { name: '采用候选到编辑器' }).hasAttribute('disabled')).toBe(true)
  await click('丢弃候选'); expect(content.value).toBe('Changed during review')
  edit(content, '中'.repeat(3000))
  expect(screen.getByRole('button', { name: '生成记忆候选' }).hasAttribute('disabled')).toBe(true)
  expect(candidatePrompt('\ud800', 'USER')).toBeNull()
  expect(() => parseCandidate(JSON.stringify({ candidate: { ...proposed, content: 'x'.repeat(2001) } }), original)).toThrow()
  expect(() => parseCandidate('```json\n{"candidate":null}\n```', original)).toThrow()
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
  edit(content, original); edit(title, 'Synthetic uncertain original')
  await click('生成记忆候选'); port.error = 'OutcomeUnknown'; await click('保存')
  expect(screen.getByRole('button', { name: '采用候选到编辑器' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('button', { name: '生成记忆候选' }).hasAttribute('disabled')).toBe(true)
  await click('采用候选到编辑器'); expect(title.value).toBe('Synthetic uncertain original')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(2)
})
it('checks exact candidate duplicates with bounded reads, explicit review and no false completeness', async () => {
  const port = new ConversationMemoryService(), body = 'Synthetic duplicate 😀 body'
  port.candidateResult = JSON.stringify({ candidate: { type: 'PROJECT_NOTE', title: 'Candidate', content: body, evidence: 'Synthetic user 1' } })
  port.items = [
    { ...memoryItem(1), type: 'PREFERENCE', title: 'Already saved under another title', content: body },
    { ...memoryItem(2), title: body, content: 'Only the title matches' },
    { ...memoryItem(3), status: 'ARCHIVED', content: body },
    { ...memoryItem(4), content: body + ' extra' },
  ]
  const { bridge, title, content } = await setupConversationDraft(port)
  await chooseMessage('USER'); await click('生成记忆候选')
  const before = port.sent.length
  expect(screen.getByText('尚未检查重复。')).toBeTruthy()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '检查已有记忆' })); fireEvent.click(screen.getByRole('button', { name: '检查已有记忆' })) })
  expect(port.sent.slice(before).map(x => ({ method: x.method, payload: x.payload }))).toEqual([
    { method: 'memory.list', payload: { query: body, status: 'ACTIVE', type: null, page: 0 } },
    ...[1, 2, 4].map(n => ({ method: 'memory.get', payload: { memoryId: memoryItem(n).id } })),
  ])
  const review = screen.getByRole('region', { name: '候选重复检查' })
  expect(within(review).getAllByRole('listitem')).toHaveLength(1)
  expect(within(review).getByText('Already saved under another title')).toBeTruthy()
  expect(screen.getByRole('button', { name: '采用候选到编辑器' }).hasAttribute('disabled')).toBe(true)
  fireEvent.click(within(review).getByRole('checkbox'))
  expect(screen.getByRole('button', { name: '采用候选到编辑器' }).hasAttribute('disabled')).toBe(false)
  expect(title.value).toBe(''); expect(content.value).toBe('Synthetic user 1')

  edit(screen.getByLabelText('候选标题'), 'Edited candidate')
  expect(screen.getByText('尚未检查重复。')).toBeTruthy()
  port.items = Array.from({ length: 21 }, (_, i) => ({ ...memoryItem(i + 10), content: i === 20 ? body : body + ` different ${i}` }))
  const bounded = port.sent.length; await click('检查已有记忆')
  expect(port.sent.slice(bounded).filter(x => x.method === 'memory.get')).toHaveLength(20)
  expect(within(review).getByText(/本次已核对的 20 条记录中/)).toBeTruthy()
  expect(within(review).getByRole('alert').textContent).toContain('检查不完整')
  expect(screen.getByRole('button', { name: '采用候选到编辑器' }).hasAttribute('disabled')).toBe(true)

  port.failList = true; await click('检查已有记忆')
  expect(within(review).getByRole('alert').textContent).toContain('未能完成重复检查')
  expect(within(review).queryByText(/未发现正文完全相同项/)).toBeNull()
  port.failList = false; port.items = [{ ...memoryItem(51), content: body }]
  const changing = vi.spyOn(bridge, 'getMemory').mockResolvedValueOnce({ ...port.items[0]!, revision: '9007199254740994' })
  await click('检查已有记忆'); changing.mockRestore()
  expect(within(review).getByRole('alert').textContent).toContain('检查不完整')
  expect(within(review).queryByRole('listitem')).toBeNull()

  let complete: (item: ReturnType<typeof memoryItem>) => void = () => {}
  const late = vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('检查已有记忆')
  edit(screen.getByLabelText('候选正文'), 'Edited during duplicate read')
  const afterEdit = port.sent.length
  await act(async () => complete(port.items[0]!)); late.mockRestore()
  expect(port.sent).toHaveLength(afterEdit)
  expect(screen.getByText('尚未检查重复。')).toBeTruthy()
  expect(within(review).queryByRole('listitem')).toBeNull()

  edit(screen.getByLabelText('候选正文'), '😀'.repeat(160) + 'tail'); port.items = []
  await click('检查已有记忆')
  expect(port.sent.at(-1)?.payload).toEqual({ query: '😀'.repeat(160), status: 'ACTIVE', type: null, page: 0 })
  expect(within(review).queryByRole('alert')).toBeNull()
  expect(within(review).getByText(/搜索返回 0 条匹配记录/)).toBeTruthy()
  expect(port.sent.slice(before).every(x => ['memory.list', 'memory.get'].includes(String(x.method)))).toBe(true)
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(1)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update')).toBe(false)
})
it('keeps candidate cancellation, unknown outcomes and late task replies from writing or replacing drafts', async () => {
  const port = new ConversationMemoryService()
  const { bridge, content } = await setupConversationDraft(port)
  await chooseMessage('USER')
  const proposed = JSON.stringify({ candidate: { type: 'PROJECT_NOTE', title: 'Synthetic candidate', content: 'Synthetic reviewed text', evidence: 'Synthetic user 1' } })
  port.candidateStatus = 'QUEUED'; await click('生成记忆候选')
  await click('取消生成候选')
  port.candidateError = 'RuntimeUnavailable'
  await screen.findByText('执行结果待确认，不会自动重发。')
  const submissions = port.sent.filter(x => x.method === 'assistant.proposeMemory').length
  expect(screen.getByRole('button', { name: '生成记忆候选' }).hasAttribute('disabled')).toBe(true)
  port.candidateError = null; port.candidateStatus = 'SUCCEEDED'; port.candidateResult = proposed
  await click('核对生成状态')
  expect(screen.getByText('任务已结束；已按取消意图丢弃候选。')).toBeTruthy()
  expect(screen.queryByLabelText('候选标题')).toBeNull()
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(submissions)

  port.holdCandidate = true; await click('生成记忆候选')
  const late = port.sent.length - 1
  await openConversations(); await click('丢弃并继续')
  await act(async () => port.reply(late, { operationId: uuid(801), status: 'SUCCEEDED', result: proposed, error: null }))
  expect(screen.queryByLabelText('候选标题')).toBeNull()
  expect(content.value).toBe('')
  await chooseMessage('USER'); await click('生成记忆候选')
  const oldSession = port.sent.length - 1
  await act(async () => port.session(uuid(909)))
  await act(async () => port.reply(oldSession, { operationId: uuid(801), status: 'SUCCEEDED', result: proposed, error: null }))
  expect(screen.queryByLabelText('候选标题')).toBeNull()
  await openConversations(); await screen.findByRole('article'); await chooseMessage('USER')
  port.holdCandidate = false; port.candidateError = 'OutcomeUnknown'
  await click('生成记忆候选')
  const uncertainSubmissions = port.sent.filter(x => x.method === 'assistant.proposeMemory').length
  expect(screen.getByRole('button', { name: '生成记忆候选' }).hasAttribute('disabled')).toBe(true)
  await click('生成记忆候选')
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(uncertainSubmissions)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update' || x.method === 'conversations.send')).toBe(false)
  expect(bridge.ready).toBe(true)
})
it('hands USER and ASSISTANT history through App into the existing editor, and creates exactly once only after an edited Save', async () => {
  const port = new ConversationMemoryService()
  const original = '  Synthetic 用户 <script>instruction</script>\r\n\u0001\u202e  '
  port.detail.turns[0]!.userMessage.content = original
  port.detail.turns[0]!.assistantMessage!.content = '  Synthetic assistant <img onerror=x>\n请审阅  '
  const { title, content } = await setupConversationDraft(port)
  edit(screen.getByLabelText('会话消息'), 'Synthetic unsubmitted input')
  await chooseMessage('USER')
  expect(location.hash).toBe('#/memory'); expect(title.value).toBe(''); expect(content.value).toBe(original.replace('\r\n', '\n'))
  expect((screen.getByLabelText('记忆类型') as HTMLSelectElement).value).toBe('PROJECT_NOTE')
  expect(screen.getByText(/已从对话填入草稿；尚未保存/)).toBeTruthy()
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  edit(title, 'Synthetic reviewed title'); edit(screen.getByLabelText('记忆类型'), 'PREFERENCE')
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '保存' })); fireEvent.click(screen.getByRole('button', { name: '保存' })) })
  expect(port.sent.filter(x => x.method === 'memory.create').map(x => x.payload)).toEqual([{ type: 'PREFERENCE', title: 'Synthetic reviewed title', content: original }])
  expect(screen.queryByText(/已从对话填入草稿；尚未保存/)).toBeNull()
  await openConversations(); await screen.findByRole('article'); await chooseMessage('ASSISTANT')
  expect(title.value).toBe(''); expect(content.value).toBe(port.detail.turns[0]!.assistantMessage!.content)
  expect((screen.getByLabelText('记忆类型') as HTMLSelectElement).value).toBe('PROJECT_NOTE')
  expect(screen.getByText(/助手回复可能不准确/)).toBeTruthy(); expect(document.querySelector('script,img')).toBeNull()
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
  expect(port.sent.some(x => x.method === 'conversations.send' || x.method === 'conversations.selectMemories')).toBe(false)
})
it('protects a dirty retained hidden editor, supports cancel and explicit discard, and preserves the normal leave guard', async () => {
  const { port, title, content } = await setupConversationDraft()
  // Both pages stay mounted: seed the retained hidden editor to exercise its ownership boundary.
  edit(title, 'Synthetic retained title'); edit(content, 'Synthetic retained body'); edit(screen.getByLabelText('记忆类型'), 'PREFERENCE')
  await chooseMessage('ASSISTANT')
  expect(location.hash).toBe('#/conversations'); expect(screen.getByRole('dialog').closest('[hidden]')).toBeNull()
  await click('取消')
  expect(title.value).toBe('Synthetic retained title'); expect(content.value).toBe('Synthetic retained body')
  expect(location.hash).toBe('#/conversations'); expect(port.sent.some(x => x.method === 'memory.create')).toBe(false)
  await chooseMessage('ASSISTANT'); await click('丢弃并继续')
  expect(location.hash).toBe('#/memory'); expect(title.value).toBe(''); expect(content.value).toBe('Synthetic answer 1')
  await openConversations(); await click('取消'); expect(location.hash).toBe('#/memory'); expect(content.value).toBe('Synthetic answer 1')
  await openConversations(); await click('丢弃并继续'); expect(location.hash).toBe('#/conversations'); expect(content.value).toBe('')
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update')).toBe(false)
})
it('retains complete over-limit Unicode history and enforces existing validation before manual Save', async () => {
  const port = new ConversationMemoryService(), original = '😀'.repeat(1001)
  port.detail.turns[0]!.assistantMessage!.content = original
  const { title, content } = await setupConversationDraft(port); await chooseMessage('ASSISTANT'); edit(title, 'Synthetic reviewed')
  expect(content.value).toBe(original); expect(screen.getByRole('alert').textContent).toContain('原文已完整保留')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  edit(content, 'Synthetic invalid\ud800')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.some(x => x.method === 'memory.create')).toBe(false)
  edit(content, 'Synthetic reviewed content'); await click('保存')
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
  await openConversations(); await screen.findByRole('article'); port.detail = conversationHistory(1, 0, 'PENDING'); await click('刷新')
  expect(screen.queryByRole('button', { name: '保存为记忆草稿' })).toBeNull()
  port.detail = conversationHistory(1, 0, 'FAILED'); await click('刷新')
  expect(screen.getAllByRole('button', { name: '保存为记忆草稿' })).toHaveLength(1)
  await chooseMessage('USER'); expect(content.value).toBe('Synthetic user 1')
})
it('isolates session rotation and late reads, refuses handoff during editor operations, and never replays an unknown create', async () => {
  const { port, bridge, title, content } = await setupConversationDraft()
  edit(title, 'Synthetic old session'); edit(content, 'Synthetic old draft'); await chooseMessage('USER')
  await act(async () => port.session(uuid(901)))
  expect(screen.queryByRole('dialog')).toBeNull(); expect(content.value).toBe(''); expect(title.value).toBe('')
  expect(location.hash).toBe('#/conversations'); await screen.findByRole('article')
  await act(async () => fireEvent.click(screen.getByRole('link', { name: '记忆' })))
  await screen.findByRole('button', { name: /Synthetic Memory 1/ })
  await openConversations(); await screen.findByRole('article')
  let resolveRead: (item: ReturnType<typeof memoryItem>) => void = () => {}
  vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { resolveRead = resolve }))
  // Start a retained hidden editor read; a busy visible editor already blocks normal navigation.
  await act(async () => fireEvent.click(screen.getByRole('button', { name: /Synthetic Memory 1/, hidden: true })))
  await chooseMessage('ASSISTANT')
  expect(location.hash).toBe('#/conversations'); expect(content.value).toBe('')
  expect(screen.getByText('请先完成记忆编辑器中的操作，再重试。')).toBeTruthy()
  await act(async () => port.session(uuid(902))); await screen.findByRole('article'); await chooseMessage('ASSISTANT')
  await act(async () => resolveRead({ ...memoryItem(), content: 'Synthetic late old read' }))
  expect(content.value).toBe('Synthetic answer 1'); expect(title.value).toBe('')
  edit(title, 'Synthetic uncertain draft'); port.error = 'OutcomeUnknown'; await click('保存')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  await click('刷新'); expect(content.value).toBe('Synthetic answer 1')
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
})
it('creates only with Save, adopts canonical snapshot, signals dirty transitions and updates exact decimal revision', async () => {
  const { port, title, content } = await setup(); edit(title, 'Synthetic new'); edit(content, '  Synthetic exact\nbody  ')
  await act(async () => {}); expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  expect(port.sent.filter(x => x.method === 'memory.editorState').map(x => x.payload)).toEqual([{ dirty: true }])
  await click('保存'); expect(port.sent.find(x => x.method === 'memory.create')?.payload).toEqual({ type: 'PROJECT_NOTE', title: 'Synthetic new', content: '  Synthetic exact\nbody  ' })
  expect(content.value).toBe('  Synthetic exact\nbody  '); expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.editorState').at(-1)?.payload).toEqual({ dirty: false })
  await click('新建'); await click(/Synthetic Memory 1 /); edit(content, 'Synthetic update'); await click('保存')
  expect((port.sent.find(x => x.method === 'memory.update')?.payload as { expectedRevision: string }).expectedRevision).toBe('9007199254740993')
})
it('archive and restore with dirty edits change only snapshot status/revision, and Save uses new revision', async () => {
  const { port, content } = await setup(); await click(/Synthetic Memory 1/); edit(content, 'Synthetic local')
  await click('归档'); expect(content.value).toBe('Synthetic local'); expect(port.items[0]?.content).toBe('Synthetic body'); expect(port.items[0]?.status).toBe('ARCHIVED')
  await click('保存'); expect((port.sent.find(x => x.method === 'memory.update')?.payload as { expectedRevision: string }).expectedRevision).toBe('9007199254740994')
  edit(content, 'Synthetic restored draft'); await click('恢复'); expect(content.value).toBe('Synthetic restored draft'); expect(port.items[0]?.content).toBe('Synthetic local')
})
it.each(['MemoryRevisionConflict'])('%s retains exact draft, blocks mutations and resolves only explicitly', async error => {
  const { port, content } = await setup(); await click(/Synthetic Memory 1/); edit(content, '  Synthetic dirty\nexact  '); port.error = error
  await click('保存'); expect(content.value).toBe('  Synthetic dirty\nexact  ')
  for (const name of ['保存', '归档', '删除']) expect(screen.getByRole('button', { name }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  if (error !== 'MemoryNotFound') {
    port.error = null; port.items[0] = { ...port.items[0]!, content: 'Synthetic latest', revision: '9007199254740994' }
    await click('重新加载'); await click('取消'); expect(content.value).toBe('  Synthetic dirty\nexact  ')
    await click('重新加载'); await click('丢弃并继续'); expect(content.value).toBe('Synthetic latest')
  }
})
it('guards actual App sidebar and direct hash routing before changing visible route', async () => {
  history.replaceState(null, '', '#/memory'); const port = new MemoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  render(<App bridge={bridge} />); await act(async () => port.session()); await screen.findByRole('button', { name: /Synthetic Memory 1/ })
  edit(screen.getByLabelText('记忆标题'), 'Synthetic dirty'); edit(screen.getByLabelText('记忆内容'), 'Synthetic draft')
  const settings = screen.getByRole('link', { name: '设置' })
  settings.focus(); fireEvent.click(settings); await click('取消'); expect(location.hash).toBe('#/memory'); expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('记忆'); expect(document.activeElement).toBe(settings)
  fireEvent.change(screen.getByRole('combobox', { name: '工作区页面' }), { target: { value: 'finance' } }); await click('取消')
  expect(location.hash).toBe('#/memory'); expect((screen.getByLabelText('记忆内容') as HTMLTextAreaElement).value).toBe('Synthetic draft')
  fireEvent.click(screen.getByRole('button', { name: /.*查看本机运行状态/ })); await click('取消'); expect(location.hash).toBe('#/memory')
  act(() => { location.hash = '#/assistant'; window.dispatchEvent(new Event('hashchange')) }); await click('取消'); expect(location.hash).toBe('#/memory')
  fireEvent.click(screen.getByRole('button', { name: /.*查看本机运行状态/ })); await click('丢弃并继续'); expect(location.hash).toBe('#/settings')
  expect(document.activeElement).toBe(screen.getByRole('heading', { name: '本机运行状态' }))
})
it('delete explains physical semantics and dirty discard, cancel preserves, success clears and corrects empty tail page', async () => {
  const port = new MemoryService(); port.items = Array.from({ length: 21 }, (_, i) => memoryItem(i + 1)); const { content } = await setup(port)
  await click('下一页'); await click(/Synthetic Memory 21 /); edit(content, 'Synthetic delete dirty'); await click('删除')
  expect(screen.getByRole('dialog').textContent).toContain('磁盘取证级擦除'); expect(screen.getByRole('dialog').textContent).toContain('未保存的修改也将丢弃')
  await click('取消'); expect(content.value).toBe('Synthetic delete dirty'); await click('删除'); await click('永久删除')
  expect(content.value).toBe(''); expect(screen.getAllByRole('listitem')).toHaveLength(20)
  expect(port.sent.filter(x => x.method === 'memory.list').slice(-2).map(x => (x.payload as { page: number }).page)).toEqual([1, 0])
})
it('confirmed mutation remains successful when follow-up list fails and hostile input stays plain text', async () => {
  const { port, content, title } = await setup(); await click(/Synthetic Memory 1/)
  edit(title, '<script>evil</script>'); edit(content, '<img onerror=x>&amp;\u202e\u0001'); port.failList = true; await click('保存')
  expect(screen.getByRole('alert').textContent).toContain('操作已成功'); expect(screen.getByRole('status').textContent).toContain('已保存')
  expect(title.value).toBe('<script>evil</script>'); expect(content.value).toBe('<img onerror=x>&amp;\u202e\u0001'); expect(document.querySelector('script,img')).toBeNull()
})
it('session rotation clears editor and confirmation and suppresses an old pending refresh error', async () => {
  const { port, bridge, title, content } = await setup(); edit(title, 'Synthetic unsaved'); edit(content, 'Synthetic old draft')
  await click('新建'); expect(screen.getByRole('dialog')).toBeTruthy()
  let reject: (error: Error) => void = () => {}
  vi.spyOn(bridge, 'listMemory').mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
  await click('刷新')
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); reject(new Error('Synthetic old-session error')) })
  expect(title.value).toBe(''); expect(content.value).toBe(''); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByRole('alert')).toBeNull()
})
it('an ambiguous create freezes Save and requires explicit inspection and selection without replay', async () => {
  const { port, title, content } = await setup(); edit(title, 'Synthetic uncertain create'); edit(content, '  Synthetic exact draft  ')
  port.error = 'OutcomeUnknown'; await click('保存')
  expect(content.value).toBe('  Synthetic exact draft  '); expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  port.error = null; port.items.push({ ...memoryItem(2), title: title.value, content: content.value })
  await click('刷新'); expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  await click(/Synthetic uncertain create PROJECT_NOTE/); await click('取消'); expect(content.value).toBe('  Synthetic exact draft  ')
  await click(/Synthetic uncertain create PROJECT_NOTE/); await click('丢弃并继续')
  expect(screen.getByText(/使用中 · 版本/).textContent).not.toContain('操作结果待确认')
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
})
