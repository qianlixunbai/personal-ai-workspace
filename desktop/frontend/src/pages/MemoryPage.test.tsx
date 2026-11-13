import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryPage } from './MemoryPage'
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
  const bridge = new WorkspaceClient(port); clients.push(bridge); port.session()
  const view = render(<MemoryPage bridge={bridge} enabled visible registerLeave={() => () => {}} openBackup={() => { void bridge.open('native.openMemoryBackup') }} />)
  await screen.findByRole('button', { name: /^Synthetic Memory 1 PROJECT_NOTE/ })
  return { port, bridge, view, title: screen.getByLabelText('记忆标题') as HTMLInputElement, content: screen.getByLabelText('记忆内容') as HTMLTextAreaElement }
}
const edit = (input: HTMLElement, value: string) => fireEvent.change(input, { target: { value } })
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
