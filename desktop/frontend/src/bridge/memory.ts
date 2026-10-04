import { exactFields, isId, isObject } from './contracts'

export type MemoryType = 'PREFERENCE' | 'PROJECT_NOTE'
export type MemoryStatus = 'ACTIVE' | 'ARCHIVED'
export interface MemoryMetadata {
  id: string; type: MemoryType; title: string; status: MemoryStatus; revision: string
  source: 'MANUAL'; createdAt: string; updatedAt: string
}
export interface MemoryItem extends MemoryMetadata { content: string }
export interface MemoryList { items: MemoryMetadata[]; total: number; page: number; limit: 20 }
export interface MemoryDraft { type: MemoryType; title: string; content: string }
export interface MemoryQuery { query: string; status: MemoryStatus; type: MemoryType | null; page: number }
export const emptyMemory = (): MemoryDraft => ({ type: 'PROJECT_NOTE', title: '', content: '' })
export const isRevision = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
export const unicode = (value: string) => !value.includes('\0') && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(value)
// Java String.strip/Character.isWhitespace parity, including non-breaking space exceptions.
const nonBlank = (value: string) => !/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/.test(value)
export const validQuery = (value: string) => unicode(value) && [...value].length <= 160
export const validTitle = (value: unknown): value is string => typeof value === 'string' && unicode(value) && nonBlank(value) && [...value].length <= 160
export const validContent = (value: unknown): value is string => typeof value === 'string' && unicode(value) && nonBlank(value)
  && [...value].length <= 2000 && value.length <= 2000 && new TextEncoder().encode(value).length <= 8192
export const validDraft = (value: MemoryDraft) => ['PREFERENCE', 'PROJECT_NOTE'].includes(value.type) && validTitle(value.title) && validContent(value.content)
function time(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d{1,7})?(Z|\+00:00)$/.exec(value)
  if (!parts || Number(parts[1]) === 0) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.getUTCFullYear() === Number(parts[1]) && date.getUTCMonth() + 1 === Number(parts[2])
    && date.getUTCDate() === Number(parts[3]) && date.getUTCHours() === Number(parts[4]) && date.getUTCMinutes() === Number(parts[5]) && date.getUTCSeconds() === Number(parts[6])
}
const metadataFields = ['id', 'type', 'title', 'status', 'revision', 'source', 'createdAt', 'updatedAt']
function metadata(value: Record<string, unknown>): boolean {
  return isId(value.id) && (value.type === 'PREFERENCE' || value.type === 'PROJECT_NOTE') && validTitle(value.title)
    && (value.status === 'ACTIVE' || value.status === 'ARCHIVED') && isRevision(value.revision) && value.source === 'MANUAL'
    && time(value.createdAt) && time(value.updatedAt) && Date.parse(value.updatedAt) >= Date.parse(value.createdAt)
}
export function isMemoryMetadata(value: unknown): value is MemoryMetadata {
  return isObject(value) && exactFields(value, metadataFields) && metadata(value)
}
export function isMemoryItem(value: unknown): value is MemoryItem {
  return isObject(value) && exactFields(value, [...metadataFields, 'content']) && metadata(value) && validContent(value.content)
}
export function isMemoryList(value: unknown): value is MemoryList {
  if (!isObject(value) || !exactFields(value, ['items', 'total', 'page', 'limit']) || !Number.isInteger(value.total) || !Number.isInteger(value.page)
    || typeof value.total !== 'number' || value.total < 0 || value.total > 1000 || typeof value.page !== 'number' || value.page < 0 || value.page > 49
    || value.limit !== 20 || !Array.isArray(value.items) || value.items.length !== Math.min(20, Math.max(0, value.total - value.page * 20))) return false
  const items = value.items
  return items.every(isMemoryMetadata) && new Set(items.map(x => x.id)).size === items.length
    && items.every((x, i) => i === 0 || Date.parse(items[i - 1]!.updatedAt) > Date.parse(x.updatedAt)
      || Date.parse(items[i - 1]!.updatedAt) === Date.parse(x.updatedAt) && items[i - 1]!.id < x.id)
}
