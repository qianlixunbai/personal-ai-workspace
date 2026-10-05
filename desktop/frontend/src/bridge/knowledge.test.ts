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
