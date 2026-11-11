import { diffLines } from 'diff'
import { unicode, type MemoryDraft } from '../bridge/memory'

export interface MemoryMergePart { base: string; local: string; latest: string; result: string | null }
export interface MemoryMerge { type: MemoryMergePart; title: MemoryMergePart; content: MemoryMergePart[] }
interface Edit { start: number; end: number; text: string; side: 'local' | 'latest' }
const part = (base: string, local: string, latest: string): MemoryMergePart => ({ base, local, latest,
  result: local === latest ? local : local === base ? latest : latest === base ? local : null })

function edits(base: string, next: string, side: Edit['side']): Edit[] {
  const changes = diffLines(base, next, { timeout: 100, maxEditLength: 4_000 })
  if (!changes) throw new Error('内容差异超出本次合并范围，请继续手工对照；草稿未改变。')
  const result: Edit[] = []
  let offset = 0, edit: Edit | null = null
  for (const change of changes) {
    if (!change.added && !change.removed) { edit = null; offset += change.value.length; continue }
    if (!edit) { edit = { start: offset, end: offset, text: '', side }; result.push(edit) }
    if (change.added) edit.text += change.value
    else { offset += change.value.length; edit.end = offset }
  }
  return result
}

// Two nonempty ranges may be adjacent. Insertions touching either boundary are
// deliberately reviewed together: their order relative to a replacement is ambiguous.
const overlaps = (a: Edit, b: Edit) => a.start === a.end || b.start === b.end
  ? a.start <= b.end && b.start <= a.end : a.start < b.end && b.start < a.end
function apply(base: string, start: number, end: number, changes: Edit[]): string {
  let offset = start, result = ''
  for (const edit of changes) { result += base.slice(offset, edit.start) + edit.text; offset = edit.end }
  return result + base.slice(offset, end)
}

export function prepareMemoryMerge(base: MemoryDraft, local: MemoryDraft, latest: MemoryDraft): MemoryMerge {
  if ([base, local, latest].some(value => value.content.length > 2000 || !unicode(value.content)))
    throw new Error('正文超出合并范围或含无效字符，请继续手工对照；草稿未改变。')
  const content: MemoryMergePart[] = []
  const changes = [...edits(base.content, local.content, 'local'), ...edits(base.content, latest.content, 'latest')]
    .sort((a, b) => a.start - b.start || a.end - b.end)
  let offset = 0, index = 0
  while (index < changes.length) {
    const group = [changes[index++]!]
    while (index < changes.length && group.some(change => overlaps(change, changes[index]!))) group.push(changes[index++]!)
    const start = group[0]!.start, end = Math.max(...group.map(change => change.end))
    if (start > offset) { const unchanged = base.content.slice(offset, start); content.push(part(unchanged, unchanged, unchanged)) }
    content.push(part(base.content.slice(start, end),
      apply(base.content, start, end, group.filter(change => change.side === 'local')),
      apply(base.content, start, end, group.filter(change => change.side === 'latest'))))
    offset = end
  }
  if (offset < base.content.length || !content.length) {
    const unchanged = base.content.slice(offset); content.push(part(unchanged, unchanged, unchanged))
  }
  return { type: part(base.type, local.type, latest.type), title: part(base.title, local.title, latest.title), content }
}

export function resolveMemoryMerge(merge: MemoryMerge, choices: Record<string, string>): MemoryDraft | null {
  const value = (part: MemoryMergePart, id: string) => part.result ?? choices[id] ?? null
  const type = value(merge.type, 'type'), title = value(merge.title, 'title')
  const content = merge.content.map((part, index) => value(part, `content-${index}`))
  if ((type !== 'PROJECT_NOTE' && type !== 'PREFERENCE') || title === null || content.some(value => value === null)) return null
  return { type, title, content: content.join('') }
}
