import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgePreview } from '../bridge/knowledge'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { KnowledgeVersionComparison } from './KnowledgeVersionComparison'
import { compareSources, readComparisonSource } from './knowledgeComparison'

afterEach(cleanup)
const detail = new KnowledgeService().detail()
detail.revisions.push({ sourceRevision: '2', sourceType: 'TXT', byteLength: 100 })
detail.document.currentReadyRevision = '2'
const part = (revision: string, text: string, offset = 0, nextOffset: number | null = null): KnowledgePreview => ({ documentId: detail.document.documentId, sourceRevision: revision, text, offset, nextOffset, locators: [], parserVersion: 'text-1', normalizationVersion: 'lf-1' })
const click = async (label: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name: label })) }) }

it('compares full paged text without interpreting HTML and opens the exact old/new Unicode offsets', async () => {
  const shared = '标题😀\n', oldText = '<img src=x onerror=alert(1)>旧条款\n', newText = '新条款\n'
  const previewKnowledge = vi.fn(async (_id: string, revision: string, offset: number) => offset === 0 ? part(revision, shared, 0, shared.length) : part(revision, revision === '1' ? oldText : newText, shared.length))
  const bridge = { previewKnowledge } as unknown as WorkspaceClient, open = vi.fn(async () => {})
  const props = { bridge, detail, open, enabled: true, disabled: false }
  const view = render(<KnowledgeVersionComparison {...props} />)
  expect(previewKnowledge).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('对比源版本'))
  await click('开始对比'); await screen.findByText(/新增 1 行，删除 1 行/)
  expect(previewKnowledge.mock.calls.map(c => c.slice(1))).toEqual([['1', 0], ['1', shared.length], ['2', 0], ['2', shared.length]])
  expect(document.querySelector('img,script')).toBeNull()
  await click('打开版本 1 第 2 行原文'); expect(open).toHaveBeenLastCalledWith('1', shared.length)
  await click('打开版本 2 第 2 行原文'); expect(open).toHaveBeenLastCalledWith('2', shared.length)
  view.rerender(<KnowledgeVersionComparison {...props} disabled />)
  expect(screen.getByRole('region', { name: '版本对比结果' })).toBeTruthy()
  view.rerender(<KnowledgeVersionComparison {...props} enabled={false} />)
  expect(screen.queryByRole('region', { name: '版本对比结果' })).toBeNull()
})

it('waits for an outstanding read on stop, suppresses late data, and ignores an unmounted result', async () => {
  let resolve!: (value: KnowledgePreview) => void
  const previewKnowledge = vi.fn(() => new Promise<KnowledgePreview>(done => { resolve = done }))
  const props = { bridge: { previewKnowledge } as unknown as WorkspaceClient, detail, open: vi.fn(async () => {}), enabled: true, disabled: false }
  const view = render(<KnowledgeVersionComparison {...props} />)
  fireEvent.click(screen.getByText('对比源版本')); await click('开始对比'); await click('停止对比')
  expect((screen.getByRole('button', { name: '开始对比' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => { resolve(part('1', '迟到内容')) })
  expect(previewKnowledge).toHaveBeenCalledTimes(1); expect(screen.getByText('已停止对比。')).toBeTruthy()
  expect(screen.queryByRole('region', { name: '版本对比结果' })).toBeNull()
  await click('开始对比'); view.unmount(); await act(async () => { resolve(part('1', '另一条迟到内容')) })
  expect(previewKnowledge).toHaveBeenCalledTimes(2)
})

it('rejects wrong version, nonadvancing pages, size and page limits instead of comparing prefixes', async () => {
  const id = detail.document.documentId, check = () => {}
  await expect(readComparisonSource({ previewKnowledge: vi.fn(async () => part('2', 'wrong')) }, id, '1', check)).rejects.toThrow('不完整')
  await expect(readComparisonSource({ previewKnowledge: vi.fn(async () => part('1', 'x', 0, 0)) }, id, '1', check)).rejects.toThrow('不完整')
  let calls = 0
  await expect(readComparisonSource({ previewKnowledge: vi.fn(async (_id, r, offset) => { calls++; return part(r, 'x'.repeat(4096), offset, offset + 4096) }) }, id, '1', check)).rejects.toThrow('范围')
  expect(calls).toBe(25)
  calls = 0
  await expect(readComparisonSource({ previewKnowledge: vi.fn(async (_id, r, offset) => { calls++; return part(r, 'x', offset, offset + 1) }) }, id, '1', check)).rejects.toThrow('范围')
  expect(calls).toBe(128)
})

it('keeps whitespace and final newline differences, and bounds highly divergent documents', async () => {
  const parts = await compareSources('中文😀 \n末行', '中文😀\n末行\n')
  expect(parts.filter(p => !p.added).map(p => p.value).join('')).toBe('中文😀 \n末行')
  expect(parts.filter(p => !p.removed).map(p => p.value).join('')).toBe('中文😀\n末行\n')
  expect(parts.some(p => p.added)).toBe(true)
  await expect(compareSources('old\n'.repeat(1100), 'new\n'.repeat(1100))).rejects.toThrow('范围')
})
