import { useEffect, useRef, useState } from 'react'
import type { KnowledgePreview } from '../bridge/knowledge'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'
import { HighlightedText } from './HighlightedText'
import { unicode } from '../bridge/memory'

export type KnowledgeSourceTarget = Pick<KnowledgeSearchHit, 'documentId' | 'sourceRevision' | 'startOffset' | 'endOffset' | 'startLine' | 'endLine'> & { kind: 'citation' | 'search' | 'find' | 'line' }

export function KnowledgeSourcePreview({ preview, target, currentRevision, disabled, hasPrevious, navigate, saveExcerpt }: {
  preview: KnowledgePreview; target: KnowledgeSourceTarget | null; currentRevision: string | null
  disabled: boolean; hasPrevious: boolean; navigate: (offset: number, history?: 'push' | 'back' | 'before') => Promise<void>
  saveExcerpt?: ((start: number, end: number) => void) | undefined
}) {
  const text = useRef<HTMLPreElement>(null)
  const [selection, setSelection] = useState<{ preview: KnowledgePreview; start: number; end: number } | null>(null)
  const excerpt = selection?.preview === preview && !disabled ? selection : null
  const source = target?.documentId === preview.documentId && target.sourceRevision === preview.sourceRevision ? target : null
  const start = source ? Math.max(source.startOffset, preview.offset) - preview.offset : 0
  const end = source ? Math.min(source.endOffset, preview.offset + preview.text.length) - preview.offset : 0
  const ranges = start < end ? [{ start, end }] : []
  const sectionStart = preview.locators[0]?.startOffset
  const label = source?.kind === 'citation' ? '引用' : source?.kind === 'find' ? '查找命中' : source?.kind === 'line' ? '行号定位' : '检索命中'
  useEffect(() => { text.current?.focus(); if (text.current) text.current.scrollTop = 0 }, [preview])
  useEffect(() => {
    setSelection(null)
    if (disabled || !saveExcerpt) return
    const read = () => {
      const selected = window.getSelection(), root = text.current
      if (!root || !selected || selected.rangeCount !== 1 || selected.isCollapsed) { setSelection(null); return }
      const range = selected.getRangeAt(0)
      if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) { setSelection(null); return }
      const before = range.cloneRange(); before.selectNodeContents(root); before.setEnd(range.startContainer, range.startOffset)
      const start = before.toString().length, content = range.toString(), end = start + content.length
      // DOM selection must map exactly to the already validated, immutable preview.
      if (!content.trim() || !unicode(content) || preview.text.slice(start, end) !== content) { setSelection(null); return }
      setSelection({ preview, start, end })
    }
    document.addEventListener('selectionchange', read)
    return () => document.removeEventListener('selectionchange', read)
  }, [preview, disabled, !!saveExcerpt])
  return <section aria-label="纯文本预览">
    <p className="knowledge-location">源版本 {preview.sourceRevision}{currentRevision && currentRevision !== preview.sourceRevision ? ` · 当前可用版本为 ${currentRevision}，此处仍显示所选旧版本` : ''} · 文本偏移 {preview.offset}–{preview.offset + preview.text.length}</p>
    <p className="knowledge-location">{preview.locators.map(l => `${l.type} · 行 ${l.startLine}–${l.endLine}${l.heading ? ` · ${l.heading}` : ''}`).join('')}</p>
    {source && <p className="hint" id="knowledge-source-range">{label}范围：行 {source.startLine}–{source.endLine}，文本偏移 {source.startOffset}–{source.endOffset}。{ranges.length ? `高亮为本页与${label}范围重合的原文，其余为上下文。` : `本页为上下文，未包含${label}范围。`}</p>}
    <pre ref={text} tabIndex={0} aria-describedby={source ? 'knowledge-source-range' : undefined}><HighlightedText text={preview.text} ranges={ranges} /></pre>
    {saveExcerpt && <div>
      <button disabled={!excerpt} onMouseDown={event => event.preventDefault()} onClick={() => { if (excerpt) saveExcerpt(excerpt.start, excerpt.end) }}>将选中原文整理为记忆草稿</button>
      <p className="hint">{excerpt ? `已选中原文，文本偏移 ${preview.offset + excerpt.start}–${preview.offset + excerpt.end}。` : '先在本页原文中选择需要保留的文字。'}草稿包含所选原文、资料名称和源版本；需审阅并明确保存，之后仍须主动选择记忆才会用于问答。</p>
    </div>}
    <div className="pagination">
      <button disabled={disabled || preview.offset === 0} onClick={() => { void navigate(preview.offset, 'before') }}>阅读前文</button>
      <button disabled={disabled || !hasPrevious} onClick={() => { void navigate(0, 'back') }}>上一页文本</button>
      <button disabled={disabled || preview.nextOffset === null} onClick={() => { if (preview.nextOffset !== null) void navigate(preview.nextOffset) }}>下一页文本</button>
      {sectionStart !== undefined && <button disabled={disabled || sectionStart === preview.offset} onClick={() => { void navigate(sectionStart) }}>从本节开头阅读</button>}
      {source && <button disabled={disabled || preview.offset === source.startOffset} onClick={() => { void navigate(source.startOffset) }}>返回{label}位置</button>}
    </div>
  </section>
}
