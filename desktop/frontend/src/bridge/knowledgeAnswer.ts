import { exactFields, isId, isObject, isSafeError } from './contracts'
import type { SafeError, TaskStatus } from './contracts'
import { decimal } from './knowledge'

export interface KnowledgeAnswerLocator { type: 'TXT_LINES' | 'MARKDOWN_SECTION_LINES'; startLine: number; endLine: number; startOffset: number; endOffset: number; section: string | null }
export interface KnowledgeAnswerCitation { documentId: string; title: string; sourceRevision: string; sourceType: 'TXT' | 'MARKDOWN'; startOffset: number; endOffset: number; startLine: number; endLine: number; heading: string | null; locator: KnowledgeAnswerLocator }
export interface KnowledgeAnswerTask { taskId: string; status: TaskStatus; result: { answer: string; citations: KnowledgeAnswerCitation[] } | null; error: SafeError | null }
const int = (v: unknown, min: number, max: number): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const unicode = (s: string) => !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(s)
function isCitation(v: unknown): v is KnowledgeAnswerCitation {
  if (!isObject(v) || !exactFields(v, ['documentId', 'title', 'sourceRevision', 'sourceType', 'startOffset', 'endOffset', 'startLine', 'endLine', 'heading', 'locator']) || !isId(v.documentId) || !decimal(v.sourceRevision) || BigInt(v.sourceRevision) > 10n || !['TXT', 'MARKDOWN'].includes(String(v.sourceType)) || typeof v.title !== 'string' || !v.title.trim() || [...v.title].length > 160 || !unicode(v.title) || /[\x00-\x1f\x7f-\x9f/\\:]/.test(v.title) || !/\.(txt|md|markdown)$/i.test(v.title) || !int(v.startOffset, 0, 1000000) || !int(v.endOffset, v.startOffset + 1, Math.min(v.startOffset + 4096, 1000000)) || !int(v.startLine, 1, 100000) || !int(v.endLine, v.startLine, 100000) || v.heading !== null && (typeof v.heading !== 'string' || !unicode(v.heading) || [...v.heading].length > 160)) return false
  const l = v.locator
  return isObject(l) && exactFields(l, ['type', 'startLine', 'endLine', 'startOffset', 'endOffset', 'section']) && l.type === (v.sourceType === 'TXT' ? 'TXT_LINES' : 'MARKDOWN_SECTION_LINES') && int(l.startOffset, 0, v.startOffset) && int(l.endOffset, v.endOffset, 1000000) && int(l.startLine, 1, v.startLine) && int(l.endLine, v.endLine, 100000) && (l.section === null || typeof l.section === 'string' && /^line-[1-9][0-9]{0,5}$/.test(l.section) && Number(l.section.slice(5)) <= l.startLine) && (v.sourceType !== 'TXT' || l.section === null && v.heading === null)
}
export function isKnowledgeAnswerTask(v: unknown): v is KnowledgeAnswerTask {
  if (!isObject(v) || !exactFields(v, ['taskId', 'status', 'result', 'error']) || !isId(v.taskId) || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(String(v.status))) return false
  if (v.status === 'SUCCEEDED') {
    const r = v.result
    return v.error === null && isObject(r) && exactFields(r, ['answer', 'citations']) && typeof r.answer === 'string' && !!r.answer.trim() && r.answer.length <= 2048 && unicode(r.answer) && Array.isArray(r.citations) && r.citations.length >= 1 && r.citations.length <= 10 && r.citations.every(isCitation) && new Set(r.citations.map(c => `${c.documentId}/${c.sourceRevision}/${c.startOffset}`)).size === r.citations.length
  }
  if (v.result !== null) return false
  if (v.status === 'QUEUED' || v.status === 'RUNNING') return v.error === null
  return isSafeError(v.error) && (v.status === 'CANCELLED' ? v.error.code === 'Cancelled' : v.status === 'TIMED_OUT' ? v.error.code === 'TimedOut' : ['ProviderUnavailable', 'ModelUnavailable', 'ProviderResponseInvalid', 'PolicyDenied', 'InvalidRequest', 'InternalError'].includes(v.error.code))
}
