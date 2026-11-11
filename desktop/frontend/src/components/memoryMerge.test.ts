import { expect, it } from 'vitest'
import { prepareMemoryMerge, resolveMemoryMerge } from './memoryMerge'
import type { MemoryDraft } from '../bridge/memory'

const draft = (content: string): MemoryDraft => ({ type: 'PROJECT_NOTE', title: '项目计划', content })
it('combines independent edits without changing whitespace, line endings, Unicode or duplicating identical changes', () => {
  const base = draft('开头\r\n预算=10\r\n  注释 😀\r\n工期=3')
  const local = { ...base, title: '本地标题', content: base.content.replace('预算=10', '预算=12') }
  const latest: MemoryDraft = { ...base, type: 'PREFERENCE', content: base.content.replace('工期=3', '工期=4') }
  expect(resolveMemoryMerge(prepareMemoryMerge(base, local, latest), {})).toEqual({ type: 'PREFERENCE', title: '本地标题', content: '开头\r\n预算=12\r\n  注释 😀\r\n工期=4' })
  const inserted = draft('第一行\n共同新增\n最后一行\n')
  expect(resolveMemoryMerge(prepareMemoryMerge(draft('第一行\n最后一行\n'), inserted, inserted), {})).toEqual(inserted)
  // Different adjacent lines can be kept independently; no blank separator is required.
  expect(resolveMemoryMerge(prepareMemoryMerge(draft('A\nB\n'), draft('甲\nB\n'), draft('A\n乙\n')), {})?.content).toBe('甲\n乙\n')
})

it('requires a decision for deletion versus editing and competing insertions, preserving full alternatives', () => {
  const merge = prepareMemoryMerge(draft('A\nB\nC\n'), draft('A\nC\n'), draft('A\n最新 B\nC\n'))
  const index = merge.content.findIndex(part => part.result === null)
  expect(merge.content[index]).toMatchObject({ base: 'B\n', local: '', latest: '最新 B\n' })
  expect(resolveMemoryMerge(merge, {})).toBeNull()
  expect(resolveMemoryMerge(merge, { [`content-${index}`]: '' })?.content).toBe('A\nC\n')
  expect(resolveMemoryMerge(merge, { [`content-${index}`]: '手工保留\n' })?.content).toBe('A\n手工保留\nC\n')
  const insertions = prepareMemoryMerge(draft('A\n'), draft('A\n本地新增\n'), draft('A\n最新新增\n'))
  expect(insertions.content.find(part => part.result === null)).toMatchObject({ base: '', local: '本地新增\n', latest: '最新新增\n' })
  expect(resolveMemoryMerge(insertions, {})).toBeNull()
  expect(() => prepareMemoryMerge(draft('A'), draft('x'.repeat(2001)), draft('B'))).toThrow(/超出合并范围/)
})
