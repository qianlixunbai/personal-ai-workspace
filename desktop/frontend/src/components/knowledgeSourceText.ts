import type { WorkspaceClient } from '../bridge/client'

export const sourceTextLimits = { units: 100_000, pages: 128, lines: 10_000 }

// Explicit, bounded reading of one immutable version through the existing bridge.
// Callers receive a complete text or an error, never a silently truncated prefix.
export async function readSourceText(bridge: Pick<WorkspaceClient, 'previewKnowledge'>, documentId: string, revision: string, check: () => void, limitMessage: string): Promise<string> {
  let text = '', offset = 0
  for (let page = 0; page < sourceTextLimits.pages; page++) {
    check()
    const part = await bridge.previewKnowledge(documentId, revision, offset)
    check()
    if (part.documentId !== documentId || part.sourceRevision !== revision || part.offset !== offset || !part.text.length || part.nextOffset !== null && part.nextOffset !== offset + part.text.length)
      throw new Error('版本原文读取不完整，请刷新文档后再试。')
    text += part.text
    if (text.length > sourceTextLimits.units) throw new Error(limitMessage)
    if (part.nextOffset === null) {
      if (text.split('\n').length > sourceTextLimits.lines) throw new Error(limitMessage)
      return text
    }
    offset = part.nextOffset
  }
  throw new Error(limitMessage)
}
