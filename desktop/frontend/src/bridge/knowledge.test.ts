import { expect, it } from 'vitest'
import { WorkspaceClient } from './client'
import { Port } from '../test/fixtures'
import { KnowledgeService, knowledgeDocument } from '../test/knowledgeFixtures'
import { isKnowledgeDocument, isKnowledgePreview, isKnowledgeDetail } from './knowledge'
it('rejects numeric versions, absolute paths, unknown fields and invalid locators', () => {
  const doc = knowledgeDocument(), fixture = new KnowledgeService()
  expect(isKnowledgeDocument(doc)).toBe(true); expect(isKnowledgeDocument({ ...doc, metadataVersion: 9007199254740993 })).toBe(false)
  expect(isKnowledgeDocument({ ...doc, title: 'C:\\private.txt' })).toBe(false); expect(isKnowledgeDocument({ ...doc, path: 'private' })).toBe(false)
  expect(isKnowledgeDetail(fixture.detail())).toBe(true); expect(isKnowledgePreview(fixture.preview())).toBe(true)
  expect(isKnowledgePreview({ ...fixture.preview(), locators: [{ ...fixture.preview().locators[0], type: 'PAGE' }] })).toBe(false)
})
it('correlates preview document/revision/range and suppresses previous-session replies', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session(); const fixture = new KnowledgeService()
  const pending = bridge.previewKnowledge(fixture.document.documentId, '1', 0); port.reply(0, { ...fixture.preview(), sourceRevision: '2' }); await expect(pending).rejects.toMatchObject({ code: 'InvalidResponse' })
  const late = bridge.getKnowledge(fixture.document.documentId); const rejected = expect(late).rejects.toThrow(); port.session('cccccccc-cccc-4ccc-8ccc-cccccccccccc'); port.reply(1, fixture.detail()); await rejected; bridge.dispose()
})
it('accepts only the UI revision metadata and rejects internal or source fields', () => {
  const detail = new KnowledgeService().detail(), revision = detail.revisions[0]
  expect(isKnowledgeDetail(detail)).toBe(true)
  const internal = { documentId: detail.document.documentId, originalFilename: detail.document.title, importedAt: detail.document.createdAt, sourceDigest: 'a'.repeat(64), representationDigest: 'b'.repeat(64), parserVersion: 'text-1', normalizationVersion: 'lf-1', lineCount: 2, path: 'C:\\private.txt', sourceBytes: 'native text', backupPath: 'C:\\private.backup' }
  for (const [key, value] of Object.entries(internal)) expect(isKnowledgeDetail({ ...detail, revisions: [{ ...revision, [key]: value }] })).toBe(false)
  expect(isKnowledgeDetail({ ...detail, revisions: [{ ...revision, ...internal }] })).toBe(false)
  for (const invalid of [{ ...revision, sourceRevision: 1 }, { ...revision, sourceType: 'PDF' }, { ...revision, sourceType: ['TXT'] }, { ...revision, byteLength: 0 }, { ...revision, byteLength: 8 * 1024 * 1024 + 1 }]) expect(isKnowledgeDetail({ ...detail, revisions: [invalid] })).toBe(false)
})
it('rejects the old digest-bearing knowledge.get response at the bridge client', async () => {
  const port = new Port(), bridge = new WorkspaceClient(port); port.session(); const detail = new KnowledgeService().detail()
  const pending = bridge.getKnowledge(detail.document.documentId)
  port.reply(0, { ...detail, revisions: [{ ...detail.revisions[0], documentId: detail.document.documentId, originalFilename: detail.document.title, importedAt: detail.document.createdAt, sourceDigest: 'a'.repeat(64), representationDigest: 'b'.repeat(64), parserVersion: 'text-1', normalizationVersion: 'lf-1', lineCount: 2 }] })
  await expect(pending).rejects.toMatchObject({ code: 'InvalidResponse' }); bridge.dispose()
})
