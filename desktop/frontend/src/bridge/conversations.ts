import { exactFields, isId, isObject } from './contracts'

export type ConversationStatus = 'ACTIVE' | 'ARCHIVED'
export type TurnStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT'
export const failureCodes = ['EXECUTION_INTERRUPTED', 'PROVIDER_UNAVAILABLE', 'MODEL_UNAVAILABLE', 'QUEUE_FULL', 'POLICY_DENIED', 'EXECUTION_FAILED', 'STORAGE_UNAVAILABLE'] as const
export type FailureCode = typeof failureCodes[number]
export interface Conversation { id: string; title: string; status: ConversationStatus; createdAt: string; updatedAt: string }
export interface ConversationList { items: Conversation[]; total: number; page: number; limit: 10 }
export interface HistoricalMemory { memoryId: string; revision: string; position: number }
export interface Message { messageId: string; role: 'USER' | 'ASSISTANT'; content: string; createdAt: string }
export interface Turn {
  turnId: string; sequence: number; status: TurnStatus; createdAt: string; updatedAt: string
  userMessage: Message; assistantMessage: Message | null; failureCode: FailureCode | null
  memoryReferences: HistoricalMemory[]; canCancel: boolean
}
export interface ConversationDetail { conversation: Conversation; turns: Turn[]; totalTurns: number; page: number; limit: 10 }
export interface ConversationAdmission { accepted: true; conversationId: string; turnId: string }
export const conversationResponseBytes = 1024 * 1024
const integer = (value: unknown, max: number): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= max
const unicode = (value: string) => !value.includes('\0') && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(value)
function time(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 40) return false
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d{1,7})?(Z|\+00:00)$/.exec(value)
  if (!parts || Number(parts[1]) === 0) return false
  const parsed = new Date(value)
  return Number.isFinite(parsed.getTime()) && parsed.getUTCFullYear() === Number(parts[1]) && parsed.getUTCMonth() + 1 === Number(parts[2])
    && parsed.getUTCDate() === Number(parts[3]) && parsed.getUTCHours() === Number(parts[4]) && parsed.getUTCMinutes() === Number(parts[5]) && parsed.getUTCSeconds() === Number(parts[6])
}
export const isTitle = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0 && unicode(value) && [...value].length <= 160
function content(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 8192 && unicode(value) && new TextEncoder().encode(value).length <= 8192
    && !/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/.test(value)
}
export function isConversation(value: unknown): value is Conversation {
  return isObject(value) && exactFields(value, ['id', 'title', 'status', 'createdAt', 'updatedAt']) && isId(value.id) && isTitle(value.title)
    && ['ACTIVE', 'ARCHIVED'].includes(String(value.status)) && time(value.createdAt) && time(value.updatedAt)
    && Date.parse(value.createdAt) <= Date.parse(value.updatedAt)
}
function page(value: Record<string, unknown>, total: unknown, items: unknown): items is unknown[] {
  return integer(total, 1000) && integer(value.page, 99) && value.limit === 10 && Array.isArray(items)
    && items.length === Math.min(10, Math.max(0, total - value.page * 10))
}
export function isConversationList(value: unknown): value is ConversationList {
  if (!isObject(value) || !exactFields(value, ['items', 'total', 'page', 'limit']) || !page(value, value.total, value.items)) return false
  const items = value.items
  return items.every(isConversation) && new Set(items.map(x => x.id)).size === items.length
    && items.every((x, i) => i === 0 || Date.parse(items[i - 1]!.updatedAt) > Date.parse(x.updatedAt)
      || items[i - 1]!.updatedAt === x.updatedAt && items[i - 1]!.id < x.id)
}
function isMessage(value: unknown, role: 'USER' | 'ASSISTANT'): value is Message {
  return isObject(value) && exactFields(value, ['messageId', 'role', 'content', 'createdAt']) && isId(value.messageId)
    && value.role === role && content(value.content) && time(value.createdAt)
}
function isHistoricalMemory(value: unknown, position: number): value is HistoricalMemory {
  return isObject(value) && exactFields(value, ['memoryId', 'revision', 'position']) && isId(value.memoryId) && value.position === position
    && typeof value.revision === 'string' && /^[1-9][0-9]{0,18}$/.test(value.revision) && BigInt(value.revision) <= 9223372036854775807n
}
function isTurn(value: unknown): value is Turn {
  if (!isObject(value) || !exactFields(value, ['turnId', 'sequence', 'status', 'createdAt', 'updatedAt', 'userMessage', 'assistantMessage', 'failureCode', 'memoryReferences', 'canCancel'])
    || !isId(value.turnId) || !integer(value.sequence, 1000) || value.sequence === 0
    || !['PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(String(value.status))
    || !time(value.createdAt) || !time(value.updatedAt) || Date.parse(value.updatedAt) < Date.parse(value.createdAt)
    || !isMessage(value.userMessage, 'USER') || value.userMessage.createdAt !== value.createdAt
    || typeof value.canCancel !== 'boolean' || value.canCancel && value.status !== 'PENDING'
    || value.failureCode !== null && (value.status !== 'FAILED' || !failureCodes.includes(value.failureCode as FailureCode))
    || !Array.isArray(value.memoryReferences) || value.memoryReferences.length > 4 || !value.memoryReferences.every(isHistoricalMemory)
    || new Set(value.memoryReferences.map(x => x.memoryId)).size !== value.memoryReferences.length) return false
  if (value.status === 'SUCCEEDED') return isMessage(value.assistantMessage, 'ASSISTANT')
    && value.assistantMessage.messageId !== value.userMessage.messageId
    && Date.parse(value.assistantMessage.createdAt) >= Date.parse(value.createdAt) && Date.parse(value.assistantMessage.createdAt) <= Date.parse(value.updatedAt)
  return value.assistantMessage === null
}
export function isConversationDetail(value: unknown): value is ConversationDetail {
  if (!isObject(value) || !exactFields(value, ['conversation', 'turns', 'totalTurns', 'page', 'limit']) || !isConversation(value.conversation)
    || !page(value, value.totalTurns, value.turns) || !integer(value.page, 99)) return false
  const pageNumber = value.page
  if (!value.turns.every(isTurn) || !value.turns.every((turn, i) => turn.sequence === pageNumber * 10 + i + 1)) return false
  const ids = value.turns.flatMap(turn => [turn.userMessage.messageId, ...(turn.assistantMessage ? [turn.assistantMessage.messageId] : [])])
  return new Set(ids).size === ids.length && new Set(value.turns.map(turn => turn.turnId)).size === value.turns.length
    && value.turns.filter(turn => turn.status === 'PENDING').length <= 1
    && value.turns.every(turn => turn.status !== 'PENDING' || turn.sequence === value.totalTurns)
}
export function isAdmission(value: unknown): value is ConversationAdmission {
  return isObject(value) && exactFields(value, ['accepted', 'conversationId', 'turnId']) && value.accepted === true && isId(value.conversationId) && isId(value.turnId)
}
