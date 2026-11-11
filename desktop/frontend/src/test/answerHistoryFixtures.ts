import { KnowledgeService } from './knowledgeFixtures'
import { MemoryService } from './memoryFixtures'
import { conversation, history as conversationHistory } from './conversationFixtures'
import type { KnowledgeAnswerHistoryEntry } from '../bridge/knowledgeAnswer'

export class AnswerHistoryService extends KnowledgeService {
  memory = new MemoryService()
  answerText = '仅用于😀本项目。\n<script>回答仍需核实。</script>'
  sourceText = '# 旧版资料\n仅用于本项目，旧预算五百元。'
  holdHistory = false; missingHistory = false; missingSource = false; holdPreview = false
  constructor() { super(); this.memory.items = []; this.memory.sent = this.sent; this.memory.listener = event => this.emit(event.data); this.document.currentReadyRevision = '2' }
  entry(): KnowledgeAnswerHistoryEntry {
    return { question: '原始问题：适用范围？', query: '本项目', scope: { documentId: this.document.documentId, sourceRevision: '1' }, task: {
      taskId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', status: 'SUCCEEDED', error: null,
      result: { answer: this.answerText, citations: [{ documentId: this.document.documentId, title: this.document.title, sourceRevision: '1', sourceType: 'MARKDOWN',
        startOffset: 7, endOffset: this.sourceText.length, startLine: 2, endLine: 2, heading: '旧版资料',
        locator: { type: 'MARKDOWN_SECTION_LINES', startOffset: 0, endOffset: this.sourceText.length, startLine: 1, endLine: 2, section: 'line-1' } }] } } }
  }
  override detail() { return { ...super.detail(), revisions: (this.missingSource ? ['2'] : ['1', '2']).map(sourceRevision => ({ sourceRevision, sourceType: 'MARKDOWN' as const, byteLength: 100 })) } }
  override postMessage(value: unknown) {
    const { method, payload } = value as { method: string; payload: Record<string, unknown> }
    if (method.startsWith('memory.') || method.startsWith('shell.')) { this.memory.postMessage(value); return }
    if (!method.startsWith('knowledge.answer') && method !== 'knowledge.preview' && !method.startsWith('conversations.')) { super.postMessage(value); return }
    this.sent.push(value as Record<string, unknown>); const index = this.sent.length - 1
    if (method === 'knowledge.answerHistoryGet' && this.holdHistory || method === 'knowledge.preview' && this.holdPreview) return
    queueMicrotask(() => {
      if (method === 'knowledge.answerHistory') this.reply(index, { items: this.missingHistory ? [] : [{ taskId: this.entry().task.taskId, question: this.entry().question, status: 'SUCCEEDED', createdAt: '2026-11-11T00:00:00Z' }], total: this.missingHistory ? 0 : 1, page: payload.page, limit: 20 })
      else if (method === 'knowledge.answerHistoryGet') {
        if (this.missingHistory) { const request = this.sent[index]!; this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: false, error: { code: 'KnowledgeNotFound', message: '原问答已删除或不可用。' } }) }
        else this.reply(index, this.entry())
      } else if (method === 'knowledge.answerSubmit' || method === 'knowledge.answerGet') this.reply(index, this.entry().task)
      else if (method === 'knowledge.preview') {
        const offset = Number(payload.offset), end = Math.min(offset + 10, this.sourceText.length)
        this.reply(index, { ...super.preview(), offset, text: this.sourceText.slice(offset, end), sourceRevision: payload.sourceRevision,
          nextOffset: end < this.sourceText.length ? end : null, locators: [{ ...super.preview().locators[0]!, endOffset: this.sourceText.length }] })
      } else if (method === 'conversations.list') this.reply(index, { items: [conversation], total: 1, page: 0, limit: 10 })
      else if (method === 'conversations.get') { const detail = conversationHistory(1); detail.turns.forEach(turn => { turn.memoryReferences = [] }); this.reply(index, detail) }
      else if (method === 'conversations.clearMemories') this.reply(index, { cleared: true })
    })
  }
}
