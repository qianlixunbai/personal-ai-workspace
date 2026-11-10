import { diffLines, type Change } from 'diff'
import type { WorkspaceClient } from '../bridge/client'
import { readSourceText, sourceTextLimits } from './knowledgeSourceText'

const limitMessage = '这两个版本超出本次对比范围，请分别预览原文。未显示部分对比结果。'
export const comparisonLimits = sourceTextLimits

// Read immutable versions through the existing validated bridge. Never compare a prefix.
export async function readComparisonSource(bridge: Pick<WorkspaceClient, 'previewKnowledge'>, documentId: string, revision: string, check: () => void): Promise<string> {
  return readSourceText(bridge, documentId, revision, check, limitMessage)
}

export interface ComparisonPart extends Change { oldOffset: number; newOffset: number; oldLine: number; newLine: number }
export async function compareSources(before: string, after: string): Promise<ComparisonPart[]> {
  const changes = await new Promise<Change[] | undefined>(resolve => {
    diffLines(before, after, { timeout: 500, maxEditLength: 2_000, callback: resolve })
  })
  if (!changes || changes.length > 1_000) throw new Error(limitMessage)
  let oldOffset = 0, newOffset = 0, oldLine = 1, newLine = 1
  return changes.map(change => {
    const part = { ...change, oldOffset, newOffset, oldLine, newLine }
    const lines = change.value.split('\n').length - 1
    if (!change.added) { oldOffset += change.value.length; oldLine += lines }
    if (!change.removed) { newOffset += change.value.length; newLine += lines }
    return part
  })
}
