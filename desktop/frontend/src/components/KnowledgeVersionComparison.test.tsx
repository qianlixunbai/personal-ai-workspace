import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgePreview } from '../bridge/knowledge'
import { KnowledgeService } from '../test/knowledgeFixtures'
import { KnowledgeVersionComparison } from './KnowledgeVersionComparison'

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
