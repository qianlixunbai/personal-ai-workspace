import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryPage } from './MemoryPage'
import type { MemoryLeaveGuard } from './MemoryPage'
import { App } from '../app/App'
import { WorkspaceClient } from '../bridge/client'
import { MemoryService, memoryItem } from '../test/memoryFixtures'
const clients: WorkspaceClient[] = []
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => { cleanup(); clients.splice(0).forEach(x => x.dispose()); vi.restoreAllMocks(); history.replaceState(null, '', '/') })
const click = async (name: string | RegExp) => { await act(async () => fireEvent.click(screen.getByRole('button', { name }))) }
async function setup(port = new MemoryService()) {
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session(); let guard: MemoryLeaveGuard = leave => leave()
  render(<MemoryPage bridge={bridge} enabled visible registerLeave={next => { guard = next; return () => {} }} openBackup={() => { void bridge.open('native.openMemoryBackup') }} openNative={() => { void bridge.open('native.openMemory') }} />)
  await screen.findByRole('button', { name: /^Synthetic Memory 1 PROJECT_NOTE/ })
  return { port, bridge, leave: (action: () => void) => guard(action), title: screen.getByLabelText('Memory title') as HTMLInputElement, content: screen.getByLabelText('Memory content') as HTMLTextAreaElement }
}
const edit = (input: HTMLElement, value: string) => fireEvent.change(input, { target: { value } })
it('loads only one metadata page and gets body only after explicit selection', async () => {
  const { port, content } = await setup(); expect(port.sent.map(x => x.method)).toEqual(['memory.list']); expect(content.value).toBe('')
  await click(/Synthetic Memory 1/); expect(content.value).toBe('Synthetic body'); expect(port.sent.at(-1)?.method).toBe('memory.get')
})
it('creates only with Save, adopts canonical snapshot, signals dirty transitions and updates exact decimal revision', async () => {
  const { port, title, content } = await setup(); edit(title, 'Synthetic new'); edit(content, '  Synthetic exact\nbody  ')
  await act(async () => {}); expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(0)
  expect(port.sent.filter(x => x.method === 'memory.editorState').map(x => x.payload)).toEqual([{ dirty: true }])
  await click('Save'); expect(port.sent.find(x => x.method === 'memory.create')?.payload).toEqual({ type: 'PROJECT_NOTE', title: 'Synthetic new', content: '  Synthetic exact\nbody  ' })
  expect(content.value).toBe('  Synthetic exact\nbody  '); expect(screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.editorState').at(-1)?.payload).toEqual({ dirty: false })
  await click('New'); await click(/Synthetic Memory 1 /); edit(content, 'Synthetic update'); await click('Save')
  expect((port.sent.find(x => x.method === 'memory.update')?.payload as { expectedRevision: string }).expectedRevision).toBe('9007199254740993')
})
it('searches explicitly and preserves dirty editor through search, filters, refresh and pagination', async () => {
  const port = new MemoryService(); port.items = Array.from({ length: 21 }, (_, i) => memoryItem(i + 1))
  const { content } = await setup(port); await click(/Synthetic Memory 1 /); edit(content, 'Synthetic dirty')
  const search = screen.getByLabelText('搜索 Memory'); const count = port.sent.filter(x => x.method === 'memory.list').length
  edit(search, 'Synthetic'); expect(port.sent.filter(x => x.method === 'memory.list')).toHaveLength(count)
  await click('Search'); expect(port.sent.filter(x => x.method === 'memory.list').at(-1)?.payload).toEqual({ query: 'Synthetic', status: 'ACTIVE', type: null, page: 0 })
  await click('Next'); expect(screen.getAllByRole('listitem')).toHaveLength(1); expect(content.value).toBe('Synthetic dirty')
  await click('Previous'); await click('Refresh')
  await act(async () => { fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'ARCHIVED' } }) })
  await act(async () => { fireEvent.change(screen.getByLabelText('Type filter'), { target: { value: 'PREFERENCE' } }) })
  expect(content.value).toBe('Synthetic dirty'); expect(port.sent.filter(x => x.method === 'memory.list').at(-1)?.payload).toEqual({ query: 'Synthetic', status: 'ARCHIVED', type: 'PREFERENCE', page: 0 })
})
it('archive and restore with dirty edits change only snapshot status/revision, and Save uses new revision', async () => {
  const { port, content } = await setup(); await click(/Synthetic Memory 1/); edit(content, 'Synthetic local')
  await click('Archive'); expect(content.value).toBe('Synthetic local'); expect(port.items[0]?.content).toBe('Synthetic body'); expect(port.items[0]?.status).toBe('ARCHIVED')
  await click('Save'); expect((port.sent.find(x => x.method === 'memory.update')?.payload as { expectedRevision: string }).expectedRevision).toBe('9007199254740994')
  edit(content, 'Synthetic restored draft'); await click('Restore'); expect(content.value).toBe('Synthetic restored draft'); expect(port.items[0]?.content).toBe('Synthetic local')
})
it.each(['MemoryRevisionConflict', 'MemoryNotFound', 'OutcomeUnknown'])('%s retains exact draft, blocks mutations and resolves only explicitly', async error => {
  const { port, content } = await setup(); await click(/Synthetic Memory 1/); edit(content, '  Synthetic dirty\nexact  '); port.error = error
  await click('Save'); expect(content.value).toBe('  Synthetic dirty\nexact  ')
  for (const name of ['Save', 'Archive', 'Delete']) expect(screen.getByRole('button', { name }).hasAttribute('disabled')).toBe(true)
  expect(port.sent.filter(x => x.method === 'memory.update')).toHaveLength(1)
  if (error !== 'MemoryNotFound') {
    port.error = null; port.items[0] = { ...port.items[0]!, content: 'Synthetic latest', revision: '9007199254740994' }
    await click('Reload'); await click('取消'); expect(content.value).toBe('  Synthetic dirty\nexact  ')
    await click('Reload'); await click('丢弃并继续'); expect(content.value).toBe('Synthetic latest')
  }
})
it('protects selection, New, Reload and route leave with default-safe keyboard confirmation and exact cancel', async () => {
  const port = new MemoryService(); port.items.push(memoryItem(2)); const { content, leave } = await setup(port)
  await click(/Synthetic Memory 1 /); edit(content, 'Synthetic untouched draft')
  for (const name of [/Synthetic Memory 2 /, 'New', 'Reload']) {
    await click(name); expect(screen.getByRole('button', { name: '取消' })).toBe(document.activeElement)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' }); expect(content.value).toBe('Synthetic untouched draft')
  }
  const next = vi.fn(); act(() => leave(next)); await click('取消'); expect(next).not.toHaveBeenCalled()
  act(() => leave(next)); await click('丢弃并继续'); expect(next).toHaveBeenCalledOnce(); expect(content.value).toBe('')
})
it('guards actual App sidebar and direct hash routing before changing visible route', async () => {
  history.replaceState(null, '', '#/memory'); const port = new MemoryService(), bridge = new WorkspaceClient(port); clients.push(bridge)
  render(<App bridge={bridge} />); await act(async () => port.session()); await screen.findByRole('button', { name: /Synthetic Memory 1/ })
  edit(screen.getByLabelText('Memory title'), 'Synthetic dirty'); edit(screen.getByLabelText('Memory content'), 'Synthetic draft')
  fireEvent.click(screen.getByRole('link', { name: /Settings/ })); await click('取消'); expect(location.hash).toBe('#/memory'); expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Memory')
  act(() => { location.hash = '#/assistant'; window.dispatchEvent(new Event('hashchange')) }); await click('取消'); expect(location.hash).toBe('#/memory')
  fireEvent.click(screen.getByRole('link', { name: /Settings/ })); await click('丢弃并继续'); expect(location.hash).toBe('#/settings')
})
it('delete explains physical semantics and dirty discard, cancel preserves, success clears and corrects empty tail page', async () => {
  const port = new MemoryService(); port.items = Array.from({ length: 21 }, (_, i) => memoryItem(i + 1)); const { content } = await setup(port)
  await click('Next'); await click(/Synthetic Memory 21 /); edit(content, 'Synthetic delete dirty'); await click('Delete')
  expect(screen.getByRole('dialog').textContent).toContain('磁盘取证级擦除'); expect(screen.getByRole('dialog').textContent).toContain('未保存的修改也将丢弃')
  await click('取消'); expect(content.value).toBe('Synthetic delete dirty'); await click('Delete'); await click('永久删除')
  expect(content.value).toBe(''); expect(screen.getAllByRole('listitem')).toHaveLength(20)
  expect(port.sent.filter(x => x.method === 'memory.list').slice(-2).map(x => (x.payload as { page: number }).page)).toEqual([1, 0])
})
it('confirmed mutation remains successful when follow-up list fails and hostile input stays plain text', async () => {
  const { port, content, title } = await setup(); await click(/Synthetic Memory 1/)
  edit(title, '<script>evil</script>'); edit(content, '<img onerror=x>&amp;\u202e\u0001'); port.failList = true; await click('Save')
  expect(screen.getByRole('alert').textContent).toContain('操作已成功'); expect(screen.getByRole('status').textContent).toContain('已保存')
  expect(title.value).toBe('<script>evil</script>'); expect(content.value).toBe('<img onerror=x>&amp;\u202e\u0001'); expect(document.querySelector('script,img')).toBeNull()
})
it('list/search/editor never write browser storage or identities/queries into URL and backup uses fixed native entry', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem'); const { port, content } = await setup(); await click(/Synthetic Memory 1/); edit(content, 'Synthetic private draft'); edit(screen.getByLabelText('搜索 Memory'), 'Synthetic private query'); await click('Search'); await click('Memory Backup / Restore')
  expect(storage).not.toHaveBeenCalled(); expect(location.hash).toBe(''); expect(location.search).toBe(''); expect(port.sent.at(-1)?.method).toBe('native.openMemoryBackup')
})
it('session rotation clears editor and confirmation and suppresses an old pending refresh error', async () => {
  const { port, bridge, title, content } = await setup(); edit(title, 'Synthetic unsaved'); edit(content, 'Synthetic old draft')
  await click('New'); expect(screen.getByRole('dialog')).toBeTruthy()
  let reject: (error: Error) => void = () => {}
  vi.spyOn(bridge, 'listMemory').mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
  await click('Refresh')
  await act(async () => { port.session('22222222-2222-4222-8222-222222222222'); reject(new Error('Synthetic old-session error')) })
  expect(title.value).toBe(''); expect(content.value).toBe(''); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByRole('alert')).toBeNull()
})
it('an ambiguous create freezes Save and requires explicit inspection and selection without replay', async () => {
  const { port, title, content } = await setup(); edit(title, 'Synthetic uncertain create'); edit(content, '  Synthetic exact draft  ')
  port.error = 'OutcomeUnknown'; await click('Save')
  expect(content.value).toBe('  Synthetic exact draft  '); expect(screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true)
  port.error = null; port.items.push({ ...memoryItem(2), title: title.value, content: content.value })
  await click('Refresh'); expect(screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true)
  await click(/Synthetic uncertain create PROJECT_NOTE/); await click('取消'); expect(content.value).toBe('  Synthetic exact draft  ')
  await click(/Synthetic uncertain create PROJECT_NOTE/); await click('丢弃并继续')
  expect(screen.getByText(/MANUAL · Revision/).textContent).not.toContain('outcome unknown')
  expect(port.sent.filter(x => x.method === 'memory.create')).toHaveLength(1)
})
