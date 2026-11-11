import { isId } from '../bridge/contracts'
import type { WorkspaceClient } from '../bridge/client'
import type { Conversation, Message, Turn } from '../bridge/conversations'
import { unicode } from '../bridge/memory'

// An editable locator in the body, never trusted provenance or model context authorization.
export interface MemoryConversationSource {
  title: string; conversationId: string; turnId: string; sequence: number; messageId: string; role: Message['role']
  startOffset: number; endOffset: number; excerpt: string
}
const marker = '\n\n对话出处：'
export const conversationSourceTitle = (title: string) => title.replace(/[\r\n]+/g, ' ')
export function formatConversationSource(conversation: Conversation, turn: Turn, message: Message, start: number, end: number) {
  return `${message.content.slice(start, end)}${marker}${conversationSourceTitle(conversation.title)}\n会话 ID：${conversation.id}\n轮次 ID：${turn.turnId}；第 ${turn.sequence} 轮\n消息 ID：${message.messageId}；角色：${message.role}\n文本偏移：${start}–${end}（不含结束位置）`
}
export function conversationSourceHint(content: string): { kind: 'absent' } | { kind: 'invalid' } | { kind: 'found'; source: MemoryConversationSource } {
  const start = content.indexOf(marker)
  if (start < 0) return { kind: 'absent' }
  if (content.length > 20_000 || !unicode(content) || content.indexOf(marker, start + marker.length) >= 0) return { kind: 'invalid' }
  const match = /^\n\n对话出处：([^\r\n]+)\n会话 ID：([^\r\n]+)\n轮次 ID：([^\r\n]+)；第 ([1-9][0-9]*) 轮\n消息 ID：([^\r\n]+)；角色：(USER|ASSISTANT)\n文本偏移：(0|[1-9][0-9]*)–([1-9][0-9]*)（不含结束位置）(?:\n|$)/.exec(content.slice(start))
  if (!match) return { kind: 'invalid' }
  const [, title, conversationId, turnId, sequenceText, messageId, role, from, to] = match
  const sequence = Number(sequenceText), startOffset = Number(from), endOffset = Number(to)
  if (!title?.trim() || [...title].length > 160 || !isId(conversationId) || !isId(turnId) || !isId(messageId)
    || !Number.isInteger(sequence) || sequence > 1000 || !Number.isInteger(startOffset) || !Number.isInteger(endOffset)
    || startOffset < 0 || endOffset <= startOffset || endOffset > 8192) return { kind: 'invalid' }
  return { kind: 'found', source: { title, conversationId, turnId, sequence, messageId, role: role as Message['role'], startOffset, endOffset, excerpt: content.slice(0, start) } }
}
export async function readConversationSource(bridge: Pick<WorkspaceClient, 'listConversations' | 'getConversation'>, source: MemoryConversationSource, check: () => void) {
  const deadline = Date.now() + 30_000
  const current = () => { check(); if (Date.now() > deadline) throw new Error('定位原会话超时，请主动重新核对。') }
  let listed = false
  // An editable locator grants no native session authority. The normal list must
  // expose this exact ID before reading its original turn, including after reopen.
  for (const status of ['ACTIVE', 'ARCHIVED'] as const) {
    for (let page = 0; page < 100; page++) {
      current(); const result = await bridge.listConversations(status, page); current()
      if (result.items.some(item => item.id === source.conversationId)) { listed = true; break }
      if ((page + 1) * result.limit >= result.total) break
    }
    if (listed) break
  }
  if (!listed) throw new Error('当前会话列表中未找到原会话，可能已删除或不可用；未改查同名会话或其他消息。请刷新后重新核对。')
  const page = Math.floor((source.sequence - 1) / 10)
  const detail = await bridge.getConversation(source.conversationId, page)
  current()
  const turn = detail.turns.find(item => item.turnId === source.turnId && item.sequence === source.sequence)
  const message = source.role === 'USER' ? turn?.userMessage : turn?.assistantMessage
  if (detail.conversation.id !== source.conversationId || detail.page !== page || !turn || turn.status === 'PENDING'
    || !message || message.messageId !== source.messageId || message.role !== source.role)
    throw new Error('所记会话、轮次或消息已不可用；未改查其他消息或最新一轮。')
  if (source.endOffset > message.content.length || !unicode(message.content.slice(0, source.startOffset)) || !unicode(message.content.slice(source.endOffset)))
    throw new Error('所记范围超出原消息或切断了字符，请核对正文中的位置。')
  return { conversation: detail.conversation, turn, message, original: message.content.slice(source.startOffset, source.endOffset) }
}
