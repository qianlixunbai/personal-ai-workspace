export interface SourceMatch {
  startOffset: number; endOffset: number; startLine: number; endLine: number
  snippet: string; snippetOffset: number; leading: boolean; trailing: boolean
}
export const sourceFindLimit = 200
export const validSourceQuery = (query: string) => query.trim().length > 0 && query.length <= 256 && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(query)

// Ordinal literal matches, including overlaps. All coordinates refer to normalized
// source text, never to case-folded text or the snippets displayed to the user.
export function findSourceMatches(text: string, query: string): { matches: SourceMatch[]; total: number } {
  if (!validSourceQuery(query)) throw new Error('请输入最多 256 个字符的完整查找短语。')
  const matches: SourceMatch[] = [], lineSpan = query.split('\n').length - 1
  let from = 0, lineOffset = 0, line = 1, total = 0
  for (let start = text.indexOf(query, from); start !== -1; start = text.indexOf(query, from)) {
    total++
    if (matches.length < sourceFindLimit) {
      for (; lineOffset < start; lineOffset++) if (text[lineOffset] === '\n') line++
      const end = start + query.length
      let left = Math.max(0, start - 60), right = Math.min(text.length, end + 60)
      if (left > 0 && /[\uDC00-\uDFFF]/u.test(text.charAt(left))) left--
      if (right < text.length && /[\uDC00-\uDFFF]/u.test(text.charAt(right))) right++
      matches.push({ startOffset: start, endOffset: end, startLine: line, endLine: line + lineSpan,
        snippet: text.slice(left, right), snippetOffset: left, leading: left > 0, trailing: right < text.length })
    }
    from = start + (text.codePointAt(start)! > 0xffff ? 2 : 1)
  }
  return { matches, total }
}
