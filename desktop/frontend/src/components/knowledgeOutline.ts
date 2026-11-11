import type { WorkspaceClient } from '../bridge/client'

export interface SourceOutlineEntry { heading: string; line: number; offset: number }
export const sourceOutlineLimit = 128

// Reuse the Runtime's existing structural locators, not a second Markdown parser.
// Jump over the remaining body of each locator; retain only heading metadata.
export async function readSourceOutline(
  bridge: Pick<WorkspaceClient, 'previewKnowledge'>, documentId: string, revision: string, check: () => void,
): Promise<SourceOutlineEntry[]> {
  const entries: SourceOutlineEntry[] = []
  let offset = 0
  for (let count = 0; count < sourceOutlineLimit; count++) {
    check()
    const part = await bridge.previewKnowledge(documentId, revision, offset)
    check()
    if (part.documentId !== documentId || part.sourceRevision !== revision || part.offset !== offset)
      throw new Error('目录的源版本或位置不一致，未显示目录。')
    // A long final locator can end beyond the returned preview. The next read
    // at its end is then the Runtime's empty EOF preview.
    if (offset > 0 && part.text === '' && part.nextOffset === null && part.locators.length === 0) return entries
    const locator = part.locators[0]
    if (part.text.length === 0 || part.locators.length !== 1 || !locator || locator.type !== 'MARKDOWN_SECTION_LINES'
      || locator.startOffset !== offset || locator.endOffset < offset + part.text.length
      || part.nextOffset !== null && part.nextOffset !== offset + part.text.length)
      throw new Error('资料目录读取不完整，未显示部分目录。')
    // Long sections have multiple locators carrying the same section identity.
    // Only the locator that starts on the heading's own line is an outline row.
    if (locator.section === `line-${locator.startLine}` && locator.heading !== null)
      entries.push({ heading: locator.heading, line: locator.startLine, offset })
    if (part.nextOffset === null) {
      if (locator.endOffset !== offset + part.text.length) throw new Error('资料目录结束位置不一致。')
      return entries
    }
    offset = locator.endOffset
  }
  throw new Error('此版本超过 128 个原文分段，暂不显示部分目录。请使用原文查找或行号定位。')
}
