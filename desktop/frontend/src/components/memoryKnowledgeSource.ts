import { isId } from '../bridge/contracts'
import type { KnowledgePreview } from '../bridge/knowledge'
import { unicode } from '../bridge/memory'
import type { WorkspaceClient } from '../bridge/client'

// This is editable body text, never a trusted or persisted Knowledge association.
export interface MemoryKnowledgeSource {
  title: string; documentId: string; sourceRevision: string; startOffset: number; endOffset: number; excerpt: string
}
type SourceHint = { kind: 'absent' } | { kind: 'invalid' } | { kind: 'found'; source: MemoryKnowledgeSource }
const marker = '\n\n出处：'
export function formatMemorySource(source: MemoryKnowledgeSource): string {
  return `${source.excerpt}${marker}${source.title}\n资料 ID：${source.documentId}\n源版本：${source.sourceRevision}；文本偏移：${source.startOffset}–${source.endOffset}（不含结束位置）`
}
export function memorySourceHint(content: string): SourceHint {
  const start = content.indexOf(marker)
  if (start < 0) return { kind: 'absent' }
  if (content.indexOf(marker, start + marker.length) >= 0 || !unicode(content)) return { kind: 'invalid' }
  const match = /^\n\n出处：([^\r\n]+)\n资料 ID：([^\r\n]+)\n源版本：([1-9][0-9]*)；文本偏移：(0|[1-9][0-9]*)–([1-9][0-9]*)（不含结束位置）(?:\n|$)/.exec(content.slice(start))
  if (!match) return { kind: 'invalid' }
  const [, title, documentId, sourceRevision, from, to] = match
  const startOffset = Number(from), endOffset = Number(to)
  if (!title || [...title].length > 160 || !isId(documentId) || !sourceRevision || !/^(?:[1-9]|10)$/.test(sourceRevision)
    || !Number.isSafeInteger(startOffset) || !Number.isSafeInteger(endOffset) || startOffset < 0 || endOffset > 1_000_000
    || endOffset <= startOffset || endOffset - startOffset > 4096) return { kind: 'invalid' }
  return { kind: 'found', source: { title, documentId, sourceRevision, startOffset, endOffset, excerpt: content.slice(0, start) } }
}

// At most one original preview's worth of selected text, read through section boundaries.
// No partial prefix is returned as a completed comparison.
export async function readMemorySource(bridge: Pick<WorkspaceClient, 'previewKnowledge'>, source: MemoryKnowledgeSource, check: () => void) {
  let offset = source.startOffset, text = '', first: KnowledgePreview | null = null
  for (let page = 0; page < 16; page++) {
    check()
    const part = await bridge.previewKnowledge(source.documentId, source.sourceRevision, offset)
    check()
    if (part.documentId !== source.documentId || part.sourceRevision !== source.sourceRevision || part.offset !== offset
      || !part.text.length || part.nextOffset !== null && part.nextOffset !== offset + part.text.length)
      throw new Error('所记范围未能完整读取，未显示部分对照。')
    first ??= part
    text += part.text.slice(0, source.endOffset - offset)
    if (offset + part.text.length >= source.endOffset) {
      if (!unicode(text)) throw new Error('所记范围切断了字符，无法完成原文对照。请核对出处中的范围。')
      return { text, first }
    }
    if (part.nextOffset === null) throw new Error('所记范围超出了该版本原文，无法完成对照。请核对正文中的出处。')
    offset = part.nextOffset
  }
  throw new Error('所记范围跨越过多原文分节，本次未完成对照。请到资料页核对该版本。')
}
