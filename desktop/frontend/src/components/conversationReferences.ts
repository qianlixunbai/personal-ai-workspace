import type { WorkspaceClient } from '../bridge/client'
import type { MemoryItem } from '../bridge/memory'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'
import { readMemorySource, type MemoryKnowledgeSource } from './memoryKnowledgeSource'

// Temporary text copies for a user-reviewed message, never native Memory selections
// or Runtime-owned Knowledge citations. No reference is persisted by this module.
export type ConversationReference = { kind: 'memory'; item: MemoryItem }
  | { kind: 'knowledge'; source: MemoryKnowledgeSource; hit: KnowledgeSearchHit }
export interface ContextPreparation {
  conversationId: string; title: string; draft: string
  apply: (message: string) => boolean
}
export const referenceKey = (reference: ConversationReference) => reference.kind === 'memory'
  ? `memory/${reference.item.id}/${reference.item.revision}`
  : `knowledge/${reference.source.documentId}/${reference.source.sourceRevision}/${reference.source.startOffset}/${reference.source.endOffset}`
export const referenceTitle = (reference: ConversationReference) => reference.kind === 'memory' ? reference.item.title : reference.source.title
export const referenceText = (reference: ConversationReference) => reference.kind === 'memory' ? reference.item.content : reference.source.excerpt
export function referenceLocation(reference: ConversationReference): string {
  return reference.kind === 'memory'
    ? `记忆 ID：${reference.item.id}；版本：${reference.item.revision}；${reference.item.status === 'ACTIVE' ? '活动' : '归档'}`
    : `资料 ID：${reference.source.documentId}；源版本：${reference.source.sourceRevision}；文本偏移：${reference.source.startOffset}–${reference.source.endOffset}（不含结束位置）`
}
export function messageWithReferences(draft: string, references: ConversationReference[]): string {
  if (!references.length) return draft
  return draft + (draft ? '\n\n' : '') + '参考摘录：\n' + references.map((reference, index) =>
    `\n[参考 ${index + 1}] ${JSON.stringify(referenceTitle(reference))}\n${referenceLocation(reference)}\n${referenceText(reference)}\n[参考 ${index + 1} 结束]`).join('\n')
}

export async function verifyReferences(bridge: WorkspaceClient, references: ConversationReference[], check: () => void) {
  for (const reference of references) {
    check()
    if (reference.kind === 'memory') {
      const item = await bridge.getMemory(reference.item.id)
      check()
      if (item.id !== reference.item.id || item.revision !== reference.item.revision || item.status !== reference.item.status
        || item.title !== reference.item.title || item.content !== reference.item.content)
        throw new Error('所选记忆已变更，未插入任何内容。请移除后重新查找并选择。')
    } else {
      const { source } = reference, detail = await bridge.getKnowledge(source.documentId)
      check()
      if (detail.document.documentId !== source.documentId || detail.document.title !== source.title
        || !detail.revisions.some(revision => revision.sourceRevision === source.sourceRevision))
        throw new Error('所选资料或源版本已变化或不可用，未插入任何内容，也未回退新版。请重新选择。')
      const original = await readMemorySource(bridge, source, check)
      check()
      if (original.text !== source.excerpt) throw new Error('所选范围与原文不一致，未插入任何内容。请重新选择。')
    }
  }
}
