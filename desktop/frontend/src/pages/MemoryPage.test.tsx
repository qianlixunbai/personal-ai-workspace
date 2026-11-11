import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryPage } from './MemoryPage'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { candidatePrompt, parseCandidate } from '../components/memoryCandidate'
import { MemoryService, memoryItem, metadata as memoryMetadata } from '../test/memoryFixtures'
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
it('finds related records for management while retaining drafts through preview revision archive and restore', async () => {
  const service = new MemoryService(); service.items = [memoryItem(), memoryItem(2)]
  const { bridge, port, title, content } = await setup(service)
  edit(title, 'Synthetic merged title'); edit(content, 'Synthetic merged body 😀')
  edit(screen.getByLabelText('搜索记忆'), 'java 测试'); await click('搜索')
  expect(screen.getByText('没有找到记忆。可在右侧手动保存偏好或项目笔记。')).toBeTruthy()
  await click('查找相关记忆')
  expect(port.sent.at(-1)?.payload).toEqual({ query: 'java 测试', type: null })
  const rows = within(screen.getByRole('list', { name: 'Memory list' })).getAllByRole('listitem')
  expect(rows[0]!.textContent).toContain('Synthetic Memory 2')
  expect(screen.queryByRole('button', { name: '下一页' })).toBeNull()
  await act(async () => fireEvent.click(within(rows[0]!).getByRole('button', { name: '预览正文' })))
  expect(screen.getByLabelText('预览正文').textContent).toBe('Synthetic body')
  expect(content.value).toBe('Synthetic merged body 😀')
  await click('用此草稿修订已有记忆'); await click(/^Synthetic Memory 2 PROJECT_NOTE/)
  await click('采用修订到编辑器')
  expect(port.sent.some(x => x.method === 'memory.update')).toBe(false)
  await click('保存')
  expect(service.items.find(x => x.id === memoryItem(2).id)?.content).toBe(content.value)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  expect(port.sent.filter(x => ['memory.list', 'memory.recall'].includes(String(x.method))).at(-1)?.method).toBe('memory.recall')
  await click('归档')
  expect(screen.queryByRole('button', { name: /^Synthetic merged title PROJECT_NOTE/ })).toBeNull()
  await act(async () => fireEvent.change(screen.getByLabelText('状态'), { target: { value: 'ARCHIVED' } }))
  expect(screen.getByRole('button', { name: '查找相关记忆' }).hasAttribute('disabled')).toBe(true)
  edit(screen.getByLabelText('搜索记忆'), ''); await click('搜索')
  expect(screen.getByRole('button', { name: /^Synthetic merged title PROJECT_NOTE ARCHIVED/ })).toBeTruthy()
  await click('恢复')
  expect(service.items.find(x => x.id === memoryItem(2).id)?.status).toBe('ACTIVE')
  await act(async () => fireEvent.change(screen.getByLabelText('状态'), { target: { value: 'ACTIVE' } }))
  service.items = Array.from({ length: 21 }, (_, index) => memoryItem(index + 1))
  edit(screen.getByLabelText('搜索记忆'), 'java'); await click('查找相关记忆')
  expect(screen.getByText('相关结果 20 / 21 · 仅显示前 20 条')).toBeTruthy()
  service.failList = true; await click('刷新')
  expect(screen.getByRole('alert').textContent).toContain('MemoryStorageUnavailable')
  expect(within(screen.getByRole('list', { name: 'Memory list' })).queryByRole('listitem')).toBeNull()
  service.failList = false
  await act(async () => fireEvent.change(screen.getByLabelText('类型'), { target: { value: 'PREFERENCE' } }))
  expect(screen.getByText(/没有词语匹配/)).toBeTruthy()
  let complete: (result: Awaited<ReturnType<WorkspaceClient['recallMemory']>>) => void = () => {}
  vi.spyOn(bridge, 'recallMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('刷新')
  await act(async () => port.session('00000000-0000-4000-8000-000000000009'))
  await act(async () => complete({ items: [memoryMetadata(memoryItem())], total: 1, limit: 20 }))
  expect(content.value).toBe(''); expect(title.value).toBe('')
  expect(screen.queryByText('相关结果 1 / 1')).toBeNull()
  expect(port.sent.every(x => String(x.method).startsWith('memory.'))).toBe(true)
})
it('ignores an obsolete dirty-state failure after Save but still reports the current notification failure', async () => {
  const { bridge, port, title, content } = await setup()
  let rejectOld: (problem: Error) => void = () => {}
  const notifications = vi.spyOn(bridge, 'memoryEditorState').mockImplementationOnce(() =>
    new Promise<{ acknowledged: true }>((_resolve, reject) => { rejectOld = reject }))
  edit(title, 'Synthetic saved current memory'); edit(content, 'Synthetic saved current body')
  await click('保存')
  expect(screen.getByText('Memory 已保存。')).toBeTruthy()
  expect(notifications.mock.calls.map(args => args[0])).toEqual([true])
  expect(port.sent.filter(request => request.method === 'memory.create')).toHaveLength(1)

  await act(async () => rejectOld(new Error('Late old dirty notification failure')))
  expect(notifications.mock.calls.map(args => args[0])).toEqual([true, false])
  expect(screen.queryByRole('alert')).toBeNull()
  expect(screen.getByText('Memory 已保存。')).toBeTruthy()
  expect(title.value).toBe('Synthetic saved current memory')
  expect(content.value).toBe('Synthetic saved current body')

  notifications.mockRejectedValueOnce(new Error('Current dirty notification failure'))
  await act(async () => { edit(content, 'Synthetic new unsaved edits') })
  expect(notifications.mock.calls.map(args => args[0])).toEqual([true, false, true])
  expect(screen.getByRole('alert').textContent).toContain('原生未保存状态保护未能确认')
  expect(content.value).toBe('Synthetic new unsaved edits')
  expect(port.items.find(item => item.title === title.value)?.content).toBe('Synthetic saved current body')
  expect(port.sent.filter(request => request.method === 'memory.create')).toHaveLength(1)
})
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
it('checks manual draft duplicates without a model or write and guards opening an existing record', async () => {
  const service = new MemoryService(), body = 'Manual original 😀 <img src=x> exact\nbody'
  service.items = [{ ...memoryItem(), content: body }, { ...memoryItem(2), title: body, content: 'Only the title matches' },
    { ...memoryItem(3), status: 'ARCHIVED', content: body }]
  const { port, title, content } = await setup(service)
  edit(content, body)
  expect(title.value).toBe('') // The body can be checked before choosing a title.
  expect(port.sent.some(x => x.method === 'memory.get')).toBe(false)
  const before = port.sent.length
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '检查草稿是否重复' })); fireEvent.click(screen.getByRole('button', { name: '检查草稿是否重复' })) })
  const review = screen.getByRole('region', { name: '草稿重复检查' })
  expect(within(review).getAllByRole('listitem')).toHaveLength(1)
  expect(within(review).getByText('Synthetic Memory 1')).toBeTruthy()
  expect(port.sent.slice(before).filter(x => ['memory.list', 'memory.get'].includes(String(x.method))).map(x => ({ method: x.method, payload: x.payload }))).toEqual([
    { method: 'memory.list', payload: { query: body, status: 'ACTIVE', type: null, page: 0 } },
    ...[1, 2].map(n => ({ method: 'memory.get', payload: { memoryId: memoryItem(n).id } })),
  ])
  expect(content.value).toBe(body); expect(review.querySelector('img')).toBeNull()
  await click('打开已有记忆：Synthetic Memory 1'); await click('取消')
  expect(content.value).toBe(body); expect(title.value).toBe('')
  expect(screen.getByRole('region', { name: '草稿重复检查' })).toBeTruthy()
  await click('打开已有记忆：Synthetic Memory 1'); await click('丢弃并继续')
  expect(title.value).toBe('Synthetic Memory 1'); expect(content.value).toBe(body)
  expect(screen.queryByRole('region', { name: '草稿重复检查' })).toBeNull()
  expect(port.sent.every(x => ['memory.list', 'memory.get', 'memory.editorState'].includes(String(x.method)))).toBe(true)
})
it('reports bounded or failed draft checks and discards late results after editing or leaving', async () => {
  const { port, bridge, view, content } = await setup()
  const body = 'Manual pending body'; edit(content, body); port.failList = true
  await click('检查草稿是否重复')
  const review = screen.getByRole('region', { name: '草稿重复检查' })
  expect(within(review).getByRole('alert').textContent).toContain('不能判断是否已有相同正文')
  expect(within(review).queryByText(/未发现正文完全相同项/)).toBeNull()
  port.failList = false
  port.items = Array.from({ length: 21 }, (_, i) => ({ ...memoryItem(i + 1), content: i === 20 ? body : body + ` other ${i}` }))
  const before = port.sent.length; await click('检查草稿是否重复')
  expect(port.sent.slice(before).filter(x => x.method === 'memory.get')).toHaveLength(20)
  expect(within(review).getByRole('alert').textContent).toContain('检查不完整')
  expect(within(review).getByText(/本次已核对的 20 条记录中/)).toBeTruthy()
  port.items = [{ ...memoryItem(), content: body }, { ...memoryItem(2), content: body + ' extra' }]
  let complete: (item: ReturnType<typeof memoryItem>) => void = () => {}
  const late = vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('检查草稿是否重复')
  edit(content, 'Edited while reading')
  const afterEdit = port.sent.length; await act(async () => complete(port.items[0]!))
  expect(port.sent).toHaveLength(afterEdit)
  expect(within(review).queryByRole('listitem')).toBeNull(); expect(within(review).queryByRole('alert')).toBeNull()
  expect(content.value).toBe('Edited while reading')
  port.items[0] = { ...port.items[0]!, content: content.value }
  late.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('检查草稿是否重复')
  view.rerender(<MemoryPage bridge={bridge} enabled visible={false} registerLeave={() => () => {}} openBackup={() => {}} />)
  await act(async () => complete(port.items[0]!))
  view.rerender(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => {}} />)
  expect(within(review).queryByRole('listitem')).toBeNull()
  expect(within(review).getByRole('alert').textContent).toContain('检查已中断')
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
it('refuses rewritten evidence and obvious translation without repairing, retrying or saving the candidate', async () => {
  const port = new ConversationMemoryService()
  const original = '我以前喜欢长段落，现在不再喜欢了。今后讨论琥珀项目时，我偏好简短要点。'
  const value = { type: 'PREFERENCE', title: 'Prefer short points for Amber project',
    content: 'User now prefers short bullet points instead of long paragraphs when discussing the Amber project.',
    evidence: '我现在不再喜欢长段落，今后讨论琥珀项目时，我偏好简短要点。' }
  port.detail.turns[0]!.userMessage.content = original
  port.candidateResult = JSON.stringify({ candidate: value })
  const { title, content } = await setupConversationDraft(port)
  await chooseMessage('USER'); await click('生成记忆候选')
  expect(screen.getByRole('alert').textContent).toContain('逐字一致的连续摘录')
  expect(screen.queryByLabelText('候选标题')).toBeNull()
  expect(content.value).toBe(original); expect(title.value).toBe('')
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(1)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update')).toBe(false)

  // A second explicit click with synthetic exact evidence isolates the language
  // guard; this is not an auto-repair or a real-model validation result.
  port.candidateResult = JSON.stringify({ candidate: { ...value, evidence: '今后讨论琥珀项目时，我偏好简短要点。' } })
  await click('生成记忆候选')
  expect(screen.getByRole('alert').textContent).toContain('请保持原文语言')
  expect(screen.queryByRole('button', { name: '采用候选到编辑器' })).toBeNull()
  expect(content.value).toBe(original)
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(2)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update')).toBe(false)
  edit(title, '琥珀项目文字偏好'); edit(content, '讨论琥珀项目时，我偏好简短要点。')
  await click('保存')
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(2)
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
  expect(screen.getByText(/已从来源填入草稿；尚未保存/)).toBeTruthy()
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  edit(title, 'Synthetic reviewed title'); edit(screen.getByLabelText('记忆类型'), 'PREFERENCE')
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '保存' })); fireEvent.click(screen.getByRole('button', { name: '保存' })) })
  expect(port.sent.filter(x => x.method === 'memory.create').map(x => x.payload)).toEqual([{ type: 'PREFERENCE', title: 'Synthetic reviewed title', content: original }])
  expect(screen.queryByText(/已从来源填入草稿；尚未保存/)).toBeNull()
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
it('revises a user-selected existing memory from an adopted candidate only on Save and reads back the same identity', async () => {
  const port = new ConversationMemoryService()
  const original = '现在我更喜欢详细的中文回答。'
  const candidate = { type: 'PREFERENCE', title: '新的回答偏好', content: '请用详细中文回答。', evidence: original }
  port.detail.turns[0]!.userMessage.content = original
  port.candidateResult = JSON.stringify({ candidate })
  port.items[0] = { ...memoryItem(), content: '旧偏好：简洁回答。\n<img src=x onerror=alert(1)>' }
  const saved = { ...port.items[0] }
  const { title, content } = await setupConversationDraft(port)
  await chooseMessage('USER'); await click('生成记忆候选'); await click('采用候选到编辑器')
  await click('用此草稿修订已有记忆')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('button', { name: '采用修订到编辑器' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.get')).toHaveLength(0)
  await click(/^Synthetic Memory 1 PROJECT_NOTE/)
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByLabelText('修订目标旧正文').textContent).toBe(saved.content)
  expect(screen.getByLabelText('修订目标旧正文').querySelector('img')).toBeNull()
  expect(content.value).toBe(candidate.content); expect(title.value).toBe(candidate.title)
  await click('取消选择修订目标'); expect(content.value).toBe(candidate.content)
  expect(document.activeElement).toBe(title)
  await click('用此草稿修订已有记忆'); await click(/^Synthetic Memory 1 PROJECT_NOTE/); await click('采用修订到编辑器')
  expect(document.activeElement).toBe(title)
  expect(screen.getByText(/使用中 · 版本 9007199254740993/)).toBeTruthy()
  expect(port.sent.some(x => ['memory.create', 'memory.update', 'memory.delete', 'memory.archive'].includes(String(x.method)))).toBe(false)
  edit(content, '  已审阅：请用详细中文回答。\n保留示例。  ')
  const revised = content.value
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '保存' })); fireEvent.click(screen.getByRole('button', { name: '保存' })) })
  expect(port.sent.filter(x => x.method === 'memory.update').map(x => x.payload)).toEqual([
    { memoryId: saved.id, expectedRevision: saved.revision, type: 'PREFERENCE', title: candidate.title, content: revised },
  ])
  expect(port.sent.some(x => x.method === 'memory.create')).toBe(false)
  expect(port.sent.filter(x => x.method === 'assistant.proposeMemory')).toHaveLength(1)
  expect(port.items).toHaveLength(1); expect(port.items[0]?.id).toBe(saved.id); expect(port.items[0]?.revision).toBe('9007199254740994')
  await click('新建'); await click(/^新的回答偏好 PREFERENCE/)
  expect(content.value).toBe(revised)
  expect(port.sent.filter(x => x.method === 'memory.get').at(-1)?.payload).toEqual({ memoryId: saved.id })
})
it('revision target review preserves drafts on stale archived missing and late reads, and cannot bypass conflict or unknown Save', async () => {
  const { port, bridge, view, title, content } = await setup()
  const draft = '  Synthetic corrected preference\nexact text  '
  edit(title, 'Synthetic revision'); edit(content, draft); await click('用此草稿修订已有记忆')
  port.items[0] = { ...port.items[0]!, revision: '9007199254740994' }
  await click(/^Synthetic Memory 1 PROJECT_NOTE/)
  expect(screen.getByRole('alert').textContent).toContain('修订目标已变更或归档')
  expect(screen.getByRole('button', { name: '采用修订到编辑器' }).hasAttribute('disabled')).toBe(true)
  expect(content.value).toBe(draft); await click('刷新')
  port.items[0] = { ...port.items[0]!, status: 'ARCHIVED' }; await click(/^Synthetic Memory 1 PROJECT_NOTE/)
  expect(screen.queryByLabelText('修订目标旧正文')).toBeNull()
  port.items[0] = { ...port.items[0]!, status: 'ACTIVE' }; port.getError = 'MemoryNotFound'; await click(/^Synthetic Memory 1 PROJECT_NOTE/)
  expect(screen.getByRole('alert').textContent).toContain('MemoryNotFound'); expect(content.value).toBe(draft)
  port.getError = null
  let complete: (item: ReturnType<typeof memoryItem>) => void = () => {}
  vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click(/^Synthetic Memory 1 PROJECT_NOTE/)
  view.rerender(<MemoryPage bridge={bridge} enabled visible={false} registerLeave={() => () => {}} openBackup={() => {}} />)
  await act(async () => complete(port.items[0]!))
  view.rerender(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => {}} />)
  expect(screen.queryByRole('region', { name: '修订目标审核' })).toBeNull(); expect(content.value).toBe(draft)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.update')).toBe(false)
  await click('用此草稿修订已有记忆'); await click(/^Synthetic Memory 1 PROJECT_NOTE/); await click('采用修订到编辑器')
  port.error = 'MemoryRevisionConflict'; await click('保存'); expect(content.value).toBe(draft)
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(screen.queryByRole('button', { name: '用此草稿修订已有记忆' })).toBeNull()
  await click('新建'); await click('丢弃并继续'); edit(title, 'Synthetic revision'); edit(content, draft)
  await click('用此草稿修订已有记忆'); await click(/^Synthetic Memory 1 PROJECT_NOTE/); await click('采用修订到编辑器')
  port.error = 'OutcomeUnknown'; await click('保存'); await click('刷新')
  expect(content.value).toBe(draft); expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(2)
  expect(port.sent.some(x => x.method === 'memory.create' || x.method === 'memory.restore')).toBe(false)
  await act(async () => port.session(uuid(909)))
  expect(content.value).toBe(''); expect(screen.queryByRole('region', { name: '修订目标审核' })).toBeNull()
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
it('compares and merges a conflicted draft before explicitly saving the same record at its reviewed version', async () => {
  const { port, title, content } = await setup(); await click(/Synthetic Memory 1/)
  edit(content, '  Synthetic dirty\nexact  ')
  const original = port.items[0]!
  port.items[0] = { ...original, revision: '9007199254740994', content: 'Synthetic latest' }
  await click('保存'); expect(content.value).toBe('  Synthetic dirty\nexact  ')
  for (const name of ['保存', '归档', '删除']) expect(screen.getByRole('button', { name }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  await click('对照最新'); expect(screen.getByLabelText('当前记忆已存正文').textContent).toBe('Synthetic latest')
  expect(content.value).toBe('  Synthetic dirty\nexact  ')
  await click('保留草稿，以此版本继续编辑'); await click('取消')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  await click('保留草稿，以此版本继续编辑')
  port.items[0] = { ...port.items[0]!, revision: '9007199254740995', content: 'Synthetic newer while reviewing' }
  await click('保留草稿并继续编辑')
  expect(screen.getByRole('status').textContent).toContain('请重新对照')
  expect(screen.getByLabelText('当前记忆已存正文').textContent).toBe('Synthetic newer while reviewing')
  expect(content.value).toBe('  Synthetic dirty\nexact  ')
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  edit(content, 'Synthetic newer while reviewing\nMerged local draft')
  const merged = content.value
  await click('保留草稿，以此版本继续编辑'); await click('保留草稿并继续编辑')
  expect(content.value).toBe(merged)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(false)
  port.items[0] = { ...port.items[0]!, revision: '9007199254740996', title: 'Synthetic latest title' }
  await click('保存') // A later concurrent edit is still rejected by the expected revision.
  expect(content.value).toBe(merged); expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  await click('对照最新'); edit(title, port.items[0]!.title)
  await click('保留草稿，以此版本继续编辑'); await click('保留草稿并继续编辑'); await click('保存')
  expect(port.items).toHaveLength(1)
  expect(port.items[0]).toMatchObject({ id: original.id, content: merged, title: 'Synthetic latest title', revision: '9007199254740997' })
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  expect(port.sent.filter(x => x.method === 'memory.update').map(x => (x.payload as { expectedRevision: string }).expectedRevision))
    .toEqual(['9007199254740993', '9007199254740995', '9007199254740996'])
  expect(screen.queryByRole('region', { name: '当前记忆最新内容对照' })).toBeNull()
})
it('reviews a three-way memory merge, adopts only a checked draft and saves separately without retrying unknown writes', async () => {
  const service = new MemoryService()
  service.items[0] = { ...service.items[0]!, content: '计划\n预算：10\n日期：周一\n结尾\n' }
  const { port, title, content } = await setup(service); await click(/Synthetic Memory 1/)
  const original = port.items[0]!
  edit(title, '本地项目标题'); edit(content, original.content.replace('10', '12'))
  port.items[0] = { ...original, title: '最新项目标题', type: 'PREFERENCE', revision: '9007199254740994', content: original.content.replace('10', '15').replace('结尾', '已存备注') }
  await click('保存'); await click('对照最新'); await click('准备三方合并')
  expect(screen.getByRole('button', { name: '重新核对并采用合并草稿' }).hasAttribute('disabled')).toBe(true)
  await click('取消合并预览'); expect(content.value).toBe(original.content.replace('10', '12'))
  await click('准备三方合并')
  await act(async () => fireEvent.click(within(screen.getByRole('region', { name: '记忆标题冲突' })).getByRole('button', { name: '保留本地修改' })))
  const conflict = screen.getByRole('region', { name: /正文片段 \d+冲突/ })
  expect(within(conflict).getByText('预算：12')).toBeTruthy(); expect(within(conflict).getByText('预算：15')).toBeTruthy()
  edit(within(conflict).getByRole('textbox'), '预算：13\n')
  const merged = '计划\n预算：13\n日期：周一\n已存备注\n'
  expect(screen.getByRole('region', { name: '完整合并预览' }).querySelector('pre')?.textContent).toBe(merged)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  await click('重新核对并采用合并草稿')
  expect(content.value).toBe(merged); expect(title.value).toBe('本地项目标题')
  expect((screen.getByLabelText('记忆类型') as HTMLSelectElement).value).toBe('PREFERENCE')
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  await click('保存')
  expect(port.items[0]).toMatchObject({ id: original.id, revision: '9007199254740995', content: merged, title: '本地项目标题', type: 'PREFERENCE' })
  expect(port.sent.filter(x => x.method === 'memory.update').map(x => (x.payload as { expectedRevision: string }).expectedRevision)).toEqual([original.revision, '9007199254740994'])
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  edit(content, merged + '后续修改'); port.error = 'OutcomeUnknown'
  await click('保存'); await click('对照最新')
  expect(screen.getByRole('button', { name: '准备三方合并' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(3)
})

it('invalidates three-way memory proposals on new edits, changed saved versions and late hidden reads', async () => {
  const service = new MemoryService(); service.items[0] = { ...service.items[0]!, content: '本地行\n中间行\n远端行\n' }
  const { port, bridge, view, content } = await setup(service); await click(/Synthetic Memory 1/)
  const original = port.items[0]!, local = original.content.replace('本地行', '本地修改')
  edit(content, local); port.items[0] = { ...original, revision: '9007199254740994', content: original.content.replace('远端行', '最新修改') }
  await click('保存'); await click('对照最新'); await click('准备三方合并')
  edit(content, local.replace('本地修改', '继续修改'))
  expect(screen.queryByRole('region', { name: '完整合并预览' })).toBeNull()
  await click('准备三方合并')
  let complete: (item: ReturnType<typeof memoryItem>) => void = () => {}
  vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('重新核对并采用合并草稿')
  const editedAgain = local.replace('本地修改', '核对期间的修改'); edit(content, editedAgain)
  await act(async () => complete(port.items[0]!))
  expect(content.value).toBe(editedAgain); expect(screen.getByText(/草稿已继续编辑，未采用旧合并结果/)).toBeTruthy()
  await click('准备三方合并')
  port.items[0] = { ...port.items[0]!, revision: '9007199254740995', content: original.content.replace('远端行', '远端再次修改') }
  await click('重新核对并采用合并草稿')
  expect(content.value).toBe(editedAgain); expect(screen.getByText(/已存内容再次变化，未采用合并结果/)).toBeTruthy()
  expect(screen.queryByRole('region', { name: '完整合并预览' })).toBeNull()
  await click('准备三方合并')
  vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('重新核对并采用合并草稿')
  view.rerender(<MemoryPage bridge={bridge} enabled visible={false} registerLeave={() => () => {}} openBackup={() => {}} />)
  await act(async () => complete(port.items[0]!))
  view.rerender(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => {}} />)
  expect(content.value).toBe(editedAgain)
  expect(screen.queryByRole('region', { name: '记忆冲突合并' })).toBeNull()
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
})

it('keeps archived and missing current comparisons read only and drops late hidden results', async () => {
  const { port, bridge, view, content } = await setup(); await click(/Synthetic Memory 1 /)
  edit(content, 'Synthetic retained comparison draft')
  port.items[0] = { ...port.items[0]!, status: 'ARCHIVED', revision: '9007199254740994' }
  await click('保存'); await click('对照最新')
  expect(screen.getByRole('button', { name: '保留草稿，以此版本继续编辑' }).hasAttribute('disabled')).toBe(true)
  let complete: (item: ReturnType<typeof memoryItem>) => void = () => {}
  vi.spyOn(bridge, 'getMemory').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  await click('对照最新')
  view.rerender(<MemoryPage bridge={bridge} enabled visible={false} registerLeave={() => () => {}} openBackup={() => {}} />)
  await act(async () => complete(port.items[0]!))
  view.rerender(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => {}} />)
  expect(screen.queryByRole('region', { name: '当前记忆最新内容对照' })).toBeNull()
  expect(content.value).toBe('Synthetic retained comparison draft')
  port.items = []; await click('对照最新')
  expect(screen.getByText(/原记录当前不存在/)).toBeTruthy()
  expect(screen.getByRole('button', { name: '保留草稿，另存为新记忆' })).toBeTruthy()
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
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
it('recovers a missing record draft as new only after confirmation and a separate explicit save', async () => {
  const { port, title, content } = await setup(); await click(/Synthetic Memory 1 /)
  const original = port.items[0]!
  const retained = { type: 'PREFERENCE', title: '  Synthetic 草稿 🧭  ', content: '  Synthetic local\n草稿 🧭  ' }
  edit(screen.getByLabelText('记忆类型'), retained.type); edit(title, retained.title); edit(content, retained.content)
  port.items = []; await click('保存')
  expect(screen.getByText(/原记录当前不存在/)).toBeTruthy()
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  await click('保留草稿，另存为新记忆'); await click('取消')
  expect(screen.getByText(original.id)).toBeTruthy(); expect(content.value).toBe(retained.content)
  await click('保留草稿，另存为新记忆'); await click('保留为新草稿')
  expect(screen.getByRole('heading', { name: '新记忆' })).toBeTruthy()
  expect(title.value).toBe(retained.title); expect(content.value).toBe(retained.content)
  expect((screen.getByLabelText('记忆类型') as HTMLSelectElement).value).toBe(retained.type)
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  expect(port.items).toHaveLength(0)
  await click('保存')
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  expect(port.sent.filter(x => x.method === 'memory.create').map(x => x.payload)).toEqual([retained])
  expect(port.items).toHaveLength(1); expect(port.items[0]!.id).not.toBe(original.id)
  expect(port.items[0]).toMatchObject({ ...retained, status: 'ACTIVE', revision: '1' })
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
})
it('does not offer missing draft recovery after an unknown write and clears a pending recovery on session reset', async () => {
  const { port, content } = await setup(); await click(/Synthetic Memory 1 /)
  edit(content, 'Synthetic uncertain update'); port.error = 'OutcomeUnknown'; await click('保存')
  port.error = null; port.items = []
  await click('重新加载'); await click('丢弃并继续')
  expect(content.value).toBe('Synthetic uncertain update')
  expect(screen.getByText(/此前的写入结果仍待确认/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: '保留草稿，另存为新记忆' })).toBeNull()
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  port.items = [memoryItem()]; await click('新建'); await click('丢弃并继续'); await click('刷新')
  await click(/Synthetic Memory 1 /); edit(content, 'Synthetic next draft'); port.items = []; await click('保存')
  await click('保留草稿，另存为新记忆')
  await act(async () => port.session('22222222-2222-4222-8222-222222222222'))
  expect(screen.queryByRole('dialog')).toBeNull(); expect(content.value).toBe('')
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
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
it.each(['failure', 'success'] as const)('keeps a confirmed save and current list when an earlier refresh returns a late %s', async outcome => {
  class HeldRefreshService extends MemoryService {
    holdNextList = false
    heldList = -1
    postMessage(request: unknown) {
      if (this.holdNextList && (request as { method: string }).method === 'memory.list') {
        this.holdNextList = false
        Port.prototype.postMessage.call(this, request)
        this.heldList = this.sent.length - 1
        return
      }
      super.postMessage(request)
    }
  }
  const service = new HeldRefreshService()
  const { port, content } = await setup(service)
  await click(/Synthetic Memory 1 /); edit(content, 'Saved while an earlier refresh is pending')
  const oldList = { items: service.items.map(memoryMetadata), total: 1, page: 0, limit: 20 }
  service.holdNextList = true
  await click('刷新')
  // Saving an editor is supported while a read-only list refresh is pending.
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(false)
  await click('保存')
  const currentRow = /^Synthetic Memory 1 PROJECT_NOTE ACTIVE Revision 9007199254740994 /
  await screen.findByRole('button', { name: currentRow })
  expect(screen.getByRole('status').textContent).toBe('Memory 已保存。')
  await act(async () => {
    const held = port.sent[service.heldList]!
    if (outcome === 'failure') port.emit({ version: 1, sessionId: held.sessionId, requestId: held.requestId, ok: false,
      error: { code: 'MemoryStorageUnavailable', message: 'Safe stale refresh failure' } })
    else port.reply(service.heldList, oldList)
  })
  expect(screen.queryByRole('alert')).toBeNull()
  expect(screen.getByRole('status').textContent).toBe('Memory 已保存。')
  expect(screen.getByRole('button', { name: currentRow })).toBeTruthy()
  expect(content.value).toBe('Saved while an earlier refresh is pending')
  expect(service.items[0]?.content).toBe(content.value)
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  expect(port.sent.filter(x => x.method === 'memory.list')).toHaveLength(3)
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
  edit(content, 'Synthetic unknown update draft'); port.error = 'OutcomeUnknown'; await click('保存')
  await click('对照最新')
  expect(content.value).toBe('Synthetic unknown update draft')
  expect(screen.getByRole('button', { name: '保留草稿，以此版本继续编辑' }).hasAttribute('disabled')).toBe(true)
  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
})
