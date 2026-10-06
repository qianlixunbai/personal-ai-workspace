import { exactFields, isId, isObject } from './contracts'

export const knowledgeMethods = ['knowledge.list', 'knowledge.get', 'knowledge.import', 'knowledge.importState', 'knowledge.cancelImport', 'knowledge.archive', 'knowledge.restore', 'knowledge.delete', 'knowledge.preview', 'knowledge.search', 'knowledge.searchStatus', 'knowledge.rebuildSearchIndex', 'knowledge.answerSubmit', 'knowledge.answerGet', 'knowledge.answerCancel'] as const
export const knowledgeCodes = ['KnowledgeInvalidSource', 'KnowledgeUnsupportedType', 'KnowledgeInvalidUtf8', 'KnowledgeSourceTooLarge', 'KnowledgeLimitExceeded', 'KnowledgeDuplicateSource', 'KnowledgeRevisionConflict', 'KnowledgeNotFound', 'KnowledgeQueueFull', 'KnowledgeIngestionFailed', 'KnowledgeInterrupted', 'KnowledgeCancelled', 'KnowledgeStorageUnavailable', 'KnowledgeSchemaUnsupported', 'KnowledgeDeleteIncomplete', 'KnowledgeSearchInvalid', 'KnowledgeQueryTooComplex', 'KnowledgeIndexNotReady', 'KnowledgeIndexUnavailable', 'KnowledgeIndexLimitExceeded', 'KnowledgeIndexRebuildFailed'] as const
export type KnowledgeState = 'PENDING' | 'PARSING' | 'READY' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export interface KnowledgeDocument { documentId: string; title: string; status: 'ACTIVE' | 'ARCHIVED'; metadataVersion: string; currentReadyRevision: string | null; createdAt: string; updatedAt: string; processingState: KnowledgeState; requestId: string | null }
export interface KnowledgeRevision { sourceRevision: string; sourceType: 'TXT' | 'MARKDOWN'; byteLength: number }
export interface KnowledgeJob { requestId: string; documentId: string; state: KnowledgeState; errorCode: string | null; sourceRevision: string | null }
export interface KnowledgeList { items: KnowledgeDocument[]; total: number; page: number; limit: 20 }
export interface KnowledgeDetail { document: KnowledgeDocument; revisions: KnowledgeRevision[]; job: KnowledgeJob | null }
export interface KnowledgeLocator { type: 'TXT_LINES' | 'MARKDOWN_SECTION_LINES'; startLine: number; endLine: number; startOffset: number; endOffset: number; section: string | null; heading: string | null }
export interface KnowledgePreview { documentId: string; sourceRevision: string; offset: number; text: string; nextOffset: number | null; locators: KnowledgeLocator[]; parserVersion: 'text-1'; normalizationVersion: 'lf-1' }
export interface KnowledgeImport { outcome: 'CANCELLED' | 'ACCEPTED' | 'UNKNOWN'; requestId: string | null; job: KnowledgeJob | null }
const states = ['PENDING', 'PARSING', 'READY', 'FAILED', 'CANCELLED', 'INTERRUPTED']
export const decimal = (v: unknown): v is string => typeof v === 'string' && /^[1-9][0-9]{0,18}$/.test(v) && BigInt(v) <= 9223372036854775807n
const revision = (v: unknown): v is string => decimal(v) && BigInt(v) <= 10n
const integer = (v: unknown, min: number, max: number): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const unicode = (s: string) => !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(s)
const filename = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0 && [...v].length <= 160 && unicode(v) && !/[\x00-\x1f\x7f-\x9f/\\:]/.test(v) && /\.(txt|md|markdown)$/i.test(v)
const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
export function isKnowledgeDocument(v: unknown): v is KnowledgeDocument {
  return isObject(v) && exactFields(v, ['documentId', 'title', 'status', 'metadataVersion', 'currentReadyRevision', 'createdAt', 'updatedAt', 'processingState', 'requestId']) && isId(v.documentId) && filename(v.title) && ['ACTIVE', 'ARCHIVED'].includes(String(v.status)) && decimal(v.metadataVersion) && (v.currentReadyRevision === null || revision(v.currentReadyRevision)) && time(v.createdAt) && time(v.updatedAt) && states.includes(String(v.processingState)) && (v.requestId === null || isId(v.requestId))
}
export function isKnowledgeJob(v: unknown): v is KnowledgeJob {
  if (!isObject(v) || !exactFields(v, ['requestId', 'documentId', 'state', 'errorCode', 'sourceRevision']) || !isId(v.requestId) || !isId(v.documentId) || !states.includes(String(v.state))) return false
  if (v.state === 'READY') return v.errorCode === null && revision(v.sourceRevision)
  return v.sourceRevision === null && (v.state === 'PENDING' || v.state === 'PARSING' ? v.errorCode === null : typeof v.errorCode === 'string' && ['KNOWLEDGE_INVALID_SOURCE', 'KNOWLEDGE_INVALID_UTF8', 'KNOWLEDGE_LIMIT_EXCEEDED', 'KNOWLEDGE_DUPLICATE_SOURCE', 'KNOWLEDGE_REVISION_CONFLICT', 'KNOWLEDGE_INGESTION_FAILED', 'KNOWLEDGE_STORAGE_UNAVAILABLE', 'KNOWLEDGE_INTERRUPTED', 'KNOWLEDGE_CANCELLED', 'KNOWLEDGE_QUEUE_FULL', 'KNOWLEDGE_SOURCE_TOO_LARGE'].includes(v.errorCode))
}
function isKnowledgeRevision(v: unknown): v is KnowledgeRevision {
  return isObject(v) && exactFields(v, ['sourceRevision', 'sourceType', 'byteLength']) && revision(v.sourceRevision) && (v.sourceType === 'TXT' || v.sourceType === 'MARKDOWN') && integer(v.byteLength, 1, 8 * 1024 * 1024)
}
export function isKnowledgeList(v: unknown): v is KnowledgeList {
  return isObject(v) && exactFields(v, ['items', 'total', 'page', 'limit']) && Array.isArray(v.items) && v.items.length <= 20 && v.items.every(isKnowledgeDocument) && new Set(v.items.map(x => x.documentId)).size === v.items.length && integer(v.total, v.items.length, 500) && integer(v.page, 0, 24) && v.limit === 20
}
export function isKnowledgeDetail(v: unknown): v is KnowledgeDetail {
  if (!isObject(v) || !exactFields(v, ['document', 'revisions', 'job']) || !isKnowledgeDocument(v.document) || !Array.isArray(v.revisions) || v.revisions.length > 10 || !v.revisions.every(isKnowledgeRevision)) return false
  const d = v.document
  return new Set(v.revisions.map(r => r.sourceRevision)).size === v.revisions.length && (d.currentReadyRevision === null || v.revisions.some(r => r.sourceRevision === d.currentReadyRevision)) && (v.job === null || isKnowledgeJob(v.job) && v.job.documentId === d.documentId && v.job.requestId === d.requestId && v.job.state === d.processingState)
}
export function isKnowledgeImport(v: unknown): v is KnowledgeImport {
  if (!isObject(v) || !exactFields(v, ['outcome', 'requestId', 'job'])) return false
  if (v.outcome === 'CANCELLED') return v.requestId === null && v.job === null
  if (v.outcome === 'UNKNOWN') return isId(v.requestId) && v.job === null
  return v.outcome === 'ACCEPTED' && isId(v.requestId) && isKnowledgeJob(v.job) && v.requestId === v.job.requestId
}
export function isKnowledgePreview(v: unknown): v is KnowledgePreview {
  if (!isObject(v) || !exactFields(v, ['documentId', 'sourceRevision', 'offset', 'text', 'nextOffset', 'locators', 'parserVersion', 'normalizationVersion']) || !isId(v.documentId) || !revision(v.sourceRevision) || !integer(v.offset, 0, 1000000) || typeof v.text !== 'string' || v.text.length > 4096 || !unicode(v.text) || v.nextOffset !== null && v.nextOffset !== v.offset + v.text.length || !Array.isArray(v.locators) || v.locators.length > 1 || v.parserVersion !== 'text-1' || v.normalizationVersion !== 'lf-1') return false
  const offset = v.offset
  return v.locators.every(l => isObject(l) && exactFields(l, ['type', 'startLine', 'endLine', 'startOffset', 'endOffset', 'section', 'heading']) && ['TXT_LINES', 'MARKDOWN_SECTION_LINES'].includes(String(l.type)) && integer(l.startLine, 1, 100000) && integer(l.endLine, l.startLine, 100000) && integer(l.startOffset, 0, offset) && integer(l.endOffset, offset + 1, 1000000) && (l.section === null || typeof l.section === 'string' && /^line-[1-9][0-9]{0,5}$/.test(l.section)) && (l.heading === null || typeof l.heading === 'string' && unicode(l.heading) && [...l.heading].length <= 160) && (l.type !== 'TXT_LINES' || l.section === null && l.heading === null))
}
