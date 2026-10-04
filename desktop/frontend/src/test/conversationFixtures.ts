import type { Conversation, ConversationDetail, TurnStatus } from '../bridge/conversations'
export const uuid = (number: number) => `${number.toString(16).padStart(8, '0')}-1111-4111-8111-111111111111`
export const conversation: Conversation = { id: uuid(1), title: 'Synthetic conversation', status: 'ACTIVE', createdAt: '2026-10-04T00:00:00+00:00', updatedAt: '2026-10-04T00:00:01+00:00' }
export function history(totalTurns = 1, page = 0, state: TurnStatus = 'SUCCEEDED', maximum = false): ConversationDetail {
  return { conversation, totalTurns, page, limit: 10, turns: Array.from({ length: Math.min(10, Math.max(0, totalTurns - page * 10)) }, (_, index) => {
    const sequence = page * 10 + index + 1
    const status = sequence === totalTurns ? state : 'SUCCEEDED'
    return { turnId: uuid(sequence + 100), sequence, status, createdAt: conversation.createdAt, updatedAt: conversation.updatedAt,
      userMessage: { messageId: uuid(sequence * 2 + 2000), role: 'USER', content: maximum ? '\u0001'.repeat(8192) : `Synthetic user ${sequence}`, createdAt: conversation.createdAt },
      assistantMessage: status === 'SUCCEEDED' ? { messageId: uuid(sequence * 2 + 2001), role: 'ASSISTANT', content: maximum ? '\u0001'.repeat(8192) : `Synthetic answer ${sequence}`, createdAt: conversation.updatedAt } : null,
      failureCode: status === 'FAILED' ? 'EXECUTION_INTERRUPTED' : null,
      memoryReferences: Array.from({ length: 4 }, (_, position) => ({ memoryId: uuid(5000 + position), revision: '9223372036854775807', position })), canCancel: status === 'PENDING' }
  }) }
}
