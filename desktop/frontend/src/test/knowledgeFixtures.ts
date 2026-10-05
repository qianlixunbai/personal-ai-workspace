import { Port } from './fixtures'
import type { KnowledgeDocument, KnowledgeDetail, KnowledgeJob, KnowledgePreview } from '../bridge/knowledge'
export const knowledgeDocument = (): KnowledgeDocument => ({ documentId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', title: 'fixture.md', status: 'ACTIVE', metadataVersion: '9007199254740993', currentReadyRevision: '1', createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z', processingState: 'READY', requestId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' })
export class KnowledgeService extends Port {
  document = knowledgeDocument(); deleted = false; unknown = false
  job(): KnowledgeJob { return { requestId: this.document.requestId!, documentId: this.document.documentId, state: 'READY', errorCode: null, sourceRevision: '1' } }
  detail(): KnowledgeDetail { return { document: { ...this.document }, revisions: [{ sourceRevision: '1', sourceType: 'MARKDOWN', byteLength: 20 }], job: this.job() } }
  preview(): KnowledgePreview { return { documentId: this.document.documentId, sourceRevision: '1', offset: 0, text: '# Heading\n<script>window.pwned=1</script>\n![x](https://invalid/image)', nextOffset: null, locators: [{ type: 'MARKDOWN_SECTION_LINES', startLine: 1, endLine: 2, startOffset: 0, endOffset: 100, section: 'line-1', heading: 'Heading' }], parserVersion: 'text-1', normalizationVersion: 'lf-1' } }
  override postMessage(value: unknown) {
    super.postMessage(value); const r = value as { method: string; payload: Record<string, unknown> }; let result: unknown
    if (r.method === 'knowledge.list') { const items = !this.deleted && r.payload.status === this.document.status ? [{ ...this.document }] : []; result = { items, total: items.length, page: r.payload.page, limit: 20 } }
    else if (r.method === 'knowledge.get') result = this.detail()
    else if (r.method === 'knowledge.preview') result = this.preview()
    else if (r.method === 'knowledge.import') result = this.unknown ? { outcome: 'UNKNOWN', requestId: this.document.requestId, job: null } : { outcome: 'ACCEPTED', requestId: this.document.requestId, job: this.job() }
    else if (r.method === 'knowledge.importState') result = this.job()
    else if (r.method === 'knowledge.archive' || r.method === 'knowledge.restore') { this.document.status = r.method === 'knowledge.archive' ? 'ARCHIVED' : 'ACTIVE'; this.document.metadataVersion = (BigInt(this.document.metadataVersion) + 1n).toString(); result = { ...this.document } }
    else if (r.method === 'knowledge.delete') { this.deleted = true; result = { deleted: true } }
    else if (r.method.startsWith('native.')) result = { opened: true }
    else return
    this.reply(this.sent.length - 1, result)
  }
}
