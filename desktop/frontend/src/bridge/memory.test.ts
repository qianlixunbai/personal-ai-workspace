import { afterEach, expect, it, vi } from 'vitest'
import { isMemoryItem, isMemoryList, isRevision, validDraft, validQuery } from './memory'
import { WorkspaceClient } from './client'
import { Port } from '../test/fixtures'
import { memoryItem, metadata } from '../test/memoryFixtures'
const item = memoryItem()
const list = { items: [metadata(item)], total: 1, page: 0, limit: 20 }
afterEach(() => vi.useRealTimers())
it('validates exact MANUAL full and metadata schemas and rejects list content overexposure', () => {
  expect(isMemoryItem(item)).toBe(true); expect(isMemoryList(list)).toBe(true)
  expect(isMemoryList({ ...list, items: [item] })).toBe(false); expect(isMemoryItem(metadata(item))).toBe(false)
  for (const invalid of [{ ...item, source: 'AUTO' }, { ...item, id: 'BAD' }, { ...item, status: 'DELETED' }, { ...item, type: 'FACT' }, { ...item, type: ['PROJECT_NOTE'] }, { ...item, status: ['ACTIVE'] }, { ...item, path: 'private' }, { ...item, revision: 1 }, { ...item, createdAt: '2026-02-30T00:00:00Z' }, { ...item, updatedAt: '2025-10-05T00:00:00Z' }]) expect(isMemoryItem(invalid)).toBe(false)
})
it.each(['0', '-1', '+1', '01', '1.0', '1e2', '9223372036854775808', 'x', ''])('rejects noncanonical or overflowing revision %s', revision => expect(isRevision(revision)).toBe(false))
it('preserves Int64 exactness and Unicode limits including Java whitespace parity', () => {
  expect(isRevision('9223372036854775807')).toBe(true); expect(isRevision('9007199254740993')).toBe(true)
  expect(validDraft({ type: 'PREFERENCE', title: '😀'.repeat(160), content: '😀'.repeat(1000) })).toBe(true)
  for (const draft of [{ ...item, title: '😀'.repeat(161) }, { ...item, content: '😀'.repeat(1001) }, { ...item, content: '\u0000x' }, { ...item, title: '\ud800' }, { ...item, content: ' \n\u001c' }]) expect(validDraft(draft)).toBe(false)
  expect(validDraft({ type: 'PROJECT_NOTE', title: '\u00a0', content: '\u0085' })).toBe(true)
  expect(validQuery('')).toBe(true); expect(validQuery('😀'.repeat(160))).toBe(true); expect(validQuery('😀'.repeat(161))).toBe(false)
})
it('rejects inconsistent pages, limits, duplicates, oversized totals and sort order', () => {
  for (const invalid of [{ ...list, total: 1001 }, { ...list, page: 50 }, { ...list, limit: 100 }, { ...list, total: 21 }, { ...list, items: [metadata(item), metadata(item)], total: 2 }, { ...list, items: [metadata(memoryItem(2)), metadata(item)], total: 2 }]) expect(isMemoryList(invalid)).toBe(false)
  expect(isMemoryList({ items: [], total: 20, page: 1, limit: 20 })).toBe(true)
})
it('checks response identity, monotonic revision, status and exact list projection before resolving', async () => {
  const port = new Port(), client = new WorkspaceClient(port); port.session()
  const pending = client.listMemory({ query: '', type: null, status: 'ACTIVE', page: 0 }); const checked = expect(pending).rejects.toMatchObject({ code: 'InvalidResponse' }); port.reply(0, { ...list, items: [item] }); await checked
  const update = client.updateMemory(item.id, item.revision, item); const uncertain = expect(update).rejects.toMatchObject({ code: 'OutcomeUnknown' }); port.reply(1, item); await uncertain
  const get = client.getMemory(item.id); const mismatch = expect(get).rejects.toMatchObject({ code: 'InvalidResponse' }); port.reply(2, memoryItem(2)); await mismatch
  client.dispose()
})
it.each(['create', 'update', 'archive', 'restore', 'delete'] as const)('%s timeout is uncertain and never replayed', async method => {
  vi.useFakeTimers(); const port = new Port(), client = new WorkspaceClient(port); port.session()
  const operation = method === 'create' ? client.createMemory(item) : method === 'update' ? client.updateMemory(item.id, item.revision, item)
    : method === 'archive' ? client.archiveMemory(item.id, item.revision) : method === 'restore' ? client.restoreMemory(item.id, item.revision) : client.deleteMemory(item.id, item.revision)
  const checked = expect(operation).rejects.toMatchObject({ code: 'OutcomeUnknown' }); await vi.advanceTimersByTimeAsync(15000); await checked
  expect(port.sent).toHaveLength(1); client.dispose()
})
