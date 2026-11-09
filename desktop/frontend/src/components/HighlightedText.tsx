import { Fragment } from 'react'

// Reuse the Knowledge search renderer for exact, already-resolved UTF-16 ranges.
// Text remains React text nodes; no HTML, Markdown, regex or query normalization.
export function HighlightedText({ text, ranges }: { text: string; ranges: { start: number; end: number }[] }) {
  let offset = 0
  const pieces = ranges.map((range, index) => {
    const plain = text.slice(offset, range.start); offset = range.end
    return <Fragment key={index}>{plain}<mark>{text.slice(range.start, range.end)}</mark></Fragment>
  })
  return <>{pieces}{text.slice(offset)}</>
}
