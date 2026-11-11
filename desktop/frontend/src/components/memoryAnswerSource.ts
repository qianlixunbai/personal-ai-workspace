import { isId } from '../bridge/contracts'
import type { WorkspaceClient } from '../bridge/client'
import { unicode } from '../bridge/memory'

// Editable navigation text, not authenticated provenance or a new persisted relation.
export interface MemoryAnswerSource { taskId: string; startOffset: number; endOffset: number; excerpt: string }
const marker = '\n\n知识问答出处：'
export const formatAnswerSource = (source: MemoryAnswerSource) => `${source.excerpt}${marker}\n问答 ID：${source.taskId}\n回答偏移：${source.startOffset}–${source.endOffset}（不含结束位置）`
export function answerSourceHint(content: string): { kind: 'absent' } | { kind: 'invalid' } | { kind: 'found'; source: MemoryAnswerSource } {
  const start = content.indexOf(marker)
  if (start < 0) return { kind: 'absent' }
  if (content.length > 20_000 || !unicode(content) || content.indexOf(marker, start + marker.length) >= 0) return { kind: 'invalid' }
  const match = /^\n\n知识问答出处：\n问答 ID：([^\r\n]+)\n回答偏移：(0|[1-9][0-9]*)–([1-9][0-9]*)（不含结束位置）(?:\n|$)/.exec(content.slice(start))
  if (!match) return { kind: 'invalid' }
  const [, taskId, from, to] = match, startOffset = Number(from), endOffset = Number(to)
  if (!isId(taskId) || !Number.isSafeInteger(startOffset) || !Number.isSafeInteger(endOffset) || endOffset <= startOffset || endOffset > 2048) return { kind: 'invalid' }
  return { kind: 'found', source: { taskId, startOffset, endOffset, excerpt: content.slice(0, start) } }
}
export async function readAuthorizedAnswerHistory(bridge: Pick<WorkspaceClient, 'knowledgeAnswerHistory' | 'getKnowledgeAnswerHistory'>, taskId: string, check: () => void) {
  const deadline = Date.now() + 30_000
  const current = () => { check(); if (Date.now() > deadline) throw new Error('定位原问答超时，请主动重新核对。') }
  // The native history grant covers only the last listed page. Editable locators,
  // old UI rows and staged references do not retain that grant across navigation.
  for (let page = 0; page < 50; page++) {
    current()
    const result = await bridge.knowledgeAnswerHistory(page)
    current()
    if (result.items.some(item => item.taskId === taskId)) {
      const entry = await bridge.getKnowledgeAnswerHistory(taskId)
      current()
      if (entry.task.taskId !== taskId) throw new Error('返回的问答编号与原记录不同，未采用内容。')
      return entry
    }
    if ((page + 1) * result.limit >= result.total) break
  }
  throw new Error('原问答已删除或不可用，或历史列表在读取时变化；未改查相似问题。请刷新后重新核对。')
}
export async function readAnswerSource(bridge: Pick<WorkspaceClient, 'knowledgeAnswerHistory' | 'getKnowledgeAnswerHistory'>, source: MemoryAnswerSource, check: () => void) {
  const entry = await readAuthorizedAnswerHistory(bridge, source.taskId, check)
  const answer = entry.task.result?.answer
  if (entry.task.taskId !== source.taskId || entry.task.status !== 'SUCCEEDED' || answer === undefined)
    throw new Error('所记问答已不可用或尚未成功完成；未改查其他回答。')
  if (source.startOffset < 0 || source.endOffset <= source.startOffset || source.endOffset > answer.length
    || !unicode(answer.slice(0, source.startOffset)) || !unicode(answer.slice(source.endOffset)))
    throw new Error('所记范围超出原回答或切断了字符，请核对正文中的位置。')
  return { entry, original: answer.slice(source.startOffset, source.endOffset) }
}
