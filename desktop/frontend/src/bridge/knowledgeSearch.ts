import { exactFields, isId, isObject } from './contracts'
import { decimal } from './knowledge'

export interface KnowledgeSearchStatus { state: 'READY' | 'BUILDING' | 'STALE' | 'FAILED'; indexedDocuments: number; indexedChunks: number }
export interface KnowledgeSearchHit { documentId: string; title: string; sourceRevision: string; sourceType: 'TXT' | 'MARKDOWN'; startOffset: number; endOffset: number; startLine: number; endLine: number; heading: string | null; snippet: string; highlightRanges: { start: number; end: number }[] }
export interface KnowledgeSearchResult { hits: KnowledgeSearchHit[] }
const int = (v: unknown, min: number, max: number): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const unicode = (s: string) => !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(s)
const boundary = (s: string, offset: number) => offset === s.length || !/[\uDC00-\uDFFF]/u.test(s[offset] ?? '')
export function isKnowledgeSearchStatus(v: unknown): v is KnowledgeSearchStatus {
  return isObject(v) && exactFields(v, ['state', 'indexedDocuments', 'indexedChunks']) && ['READY', 'BUILDING', 'STALE', 'FAILED'].includes(String(v.state)) && int(v.indexedDocuments, 0, 500) && int(v.indexedChunks, v.indexedDocuments, 100000) && (v.state === 'READY' || v.indexedDocuments === 0 && v.indexedChunks === 0)
}
export function isKnowledgeSearchHit(v: unknown): v is KnowledgeSearchHit {
  if (!isObject(v) || !exactFields(v, ['documentId', 'title', 'sourceRevision', 'sourceType', 'startOffset', 'endOffset', 'startLine', 'endLine', 'heading', 'snippet', 'highlightRanges']) || !isId(v.documentId) || !decimal(v.sourceRevision) || BigInt(v.sourceRevision) > 10n || !['TXT', 'MARKDOWN'].includes(String(v.sourceType)) || typeof v.title !== 'string' || !v.title.trim() || v.title.length > 160 || !unicode(v.title) || /[\x00-\x1f\x7f-\x9f/\\:]/.test(v.title) || !int(v.startOffset, 0, 1000000) || !int(v.endOffset, v.startOffset + 1, 1000000) || !int(v.startLine, 1, 100000) || !int(v.endLine, v.startLine, 100000) || v.heading !== null && (typeof v.heading !== 'string' || v.heading.length > 96 || !unicode(v.heading)) || typeof v.snippet !== 'string' || v.snippet.length > 384 || !unicode(v.snippet) || !Array.isArray(v.highlightRanges) || v.highlightRanges.length > 16) return false
  const snippet = v.snippet; let previous = 0
  return v.highlightRanges.every(r => {
    if (!isObject(r) || !exactFields(r, ['start', 'end']) || !int(r.start, previous, snippet.length) || !int(r.end, r.start + 1, snippet.length) || !boundary(snippet, r.start) || !boundary(snippet, r.end)) return false
    previous = r.end; return true
  })
}
export function isKnowledgeSearchResult(v: unknown): v is KnowledgeSearchResult {
  return isObject(v) && exactFields(v, ['hits']) && Array.isArray(v.hits) && v.hits.length <= 10 && v.hits.every(isKnowledgeSearchHit) && new Set(v.hits.map(h => `${h.documentId}/${h.sourceRevision}/${h.startOffset}`)).size === v.hits.length
}
