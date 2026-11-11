import { exactFields, isObject } from './contracts'
import { isKnowledgeScope, type KnowledgeScope } from './knowledgeScope'

export interface KnowledgeWorksetSource extends KnowledgeScope { title: string; createdAt: string }
export interface KnowledgeWorkset { query: string; sources: KnowledgeWorksetSource[]; savedAt: string }
export type KnowledgeWorksetOpen = { outcome: 'CANCELLED'; workset: null } | { outcome: 'OPENED'; workset: KnowledgeWorkset }
const unicode = (value: string) => !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)
export function validWorksetInput(query: string, sources: KnowledgeScope[]): boolean {
  return typeof query === 'string' && [...query].length <= 128 && unicode(query) && !/[\x00-\x1f\x7f-\x9f]/.test(query)
    && sources.length >= 1 && sources.length <= 10 && sources.every(source => source !== null && isKnowledgeScope(source))
    && new Set(sources.map(source => source.documentId)).size === sources.length
}
export function isKnowledgeWorkset(value: unknown): value is KnowledgeWorkset {
  if (!isObject(value) || !exactFields(value, ['query', 'sources', 'savedAt']) || typeof value.query !== 'string'
    || typeof value.savedAt !== 'string' || value.savedAt.length > 64 || !/(?:Z|\+00:00)$/.test(value.savedAt)
    || !Number.isFinite(Date.parse(value.savedAt)) || !Array.isArray(value.sources)) return false
  return value.sources.every(source => isObject(source) && exactFields(source, ['documentId', 'sourceRevision', 'title', 'createdAt'])
    && isKnowledgeScope({ documentId: source.documentId, sourceRevision: source.sourceRevision })
    && typeof source.title === 'string' && !!source.title.trim() && [...source.title].length <= 160 && unicode(source.title)
    && !/[\x00-\x1f\x7f-\x9f]/.test(source.title) && typeof source.createdAt === 'string' && source.createdAt.length <= 64 && Number.isFinite(Date.parse(source.createdAt)))
    && validWorksetInput(value.query, value.sources.map(source => ({ documentId: source.documentId, sourceRevision: source.sourceRevision })))
}
export function isKnowledgeWorksetOpen(value: unknown): value is KnowledgeWorksetOpen {
  return isObject(value) && exactFields(value, ['outcome', 'workset'])
    && (value.outcome === 'CANCELLED' ? value.workset === null : value.outcome === 'OPENED' && isKnowledgeWorkset(value.workset))
}
