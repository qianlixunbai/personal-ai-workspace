import { exactFields, isId, isObject } from './contracts'
import { isConversation, type Conversation, type TurnStatus } from './conversations'

export interface ConversationMemoryUsageHit {
  conversation: Conversation; turnId: string; sequence: number; status: Exclude<TurnStatus, 'PENDING'>; revision: string
}
export interface ConversationMemoryUsagePage {
  memoryId: string; revision: string | null; items: ConversationMemoryUsageHit[]; hasMore: boolean; page: number; limit: 10
}
export const usageRevision = (value: unknown): value is string => typeof value === 'string'
  && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
export function isConversationMemoryUsagePage(value: unknown): value is ConversationMemoryUsagePage {
  if (!isObject(value) || !exactFields(value, ['memoryId', 'revision', 'items', 'hasMore', 'page', 'limit'])
    || !isId(value.memoryId) || value.revision !== null && !usageRevision(value.revision)
    || !Number.isInteger(value.page) || (value.page as number) < 0 || (value.page as number) > 99999 || value.limit !== 10
    || typeof value.hasMore !== 'boolean' || !Array.isArray(value.items) || value.items.length > 10
    || value.hasMore && value.items.length !== 10) return false
  return value.items.every(hit => isObject(hit) && exactFields(hit, ['conversation', 'turnId', 'sequence', 'status', 'revision'])
    && isConversation(hit.conversation) && isId(hit.turnId) && Number.isInteger(hit.sequence) && (hit.sequence as number) >= 1 && (hit.sequence as number) <= 1000
    && ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(String(hit.status)) && usageRevision(hit.revision)
    && (value.revision === null || hit.revision === value.revision)) && new Set(value.items.map(hit => hit.turnId)).size === value.items.length
}
