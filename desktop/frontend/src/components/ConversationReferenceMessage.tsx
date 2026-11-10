import { useEffect, useRef, useState } from 'react'
import type { Conversation, ConversationSearchHit, Message, Turn } from '../bridge/conversations'
import { unicode } from '../bridge/memory'
import type { ConversationReference } from './conversationReferences'
import { conversationSourceTitle } from './memoryConversationSource'
import { HighlightedText } from './HighlightedText'

// Only the explicitly opened, identified message can be copied. Its paired message
// and historical Memory selections are context for reading, never implicit inputs.
export function ConversationReferenceMessage({ conversation, turn, message, hit, ranges, prepare }: {
  conversation: Conversation; turn: Turn; message: Message; hit: ConversationSearchHit
  ranges: { start: number; end: number }[]; prepare?: ((reference: ConversationReference) => void) | undefined
}) {
  const root = useRef<HTMLPreElement>(null)
  const [selection, setSelection] = useState<{ start: number; end: number } | null>(null)
  useEffect(() => {
    setSelection(null)
    if (!prepare) return
    const select = () => {
      const text = root.current, value = window.getSelection()
      if (!text || !value || value.rangeCount !== 1 || value.isCollapsed) { setSelection(null); return }
      const range = value.getRangeAt(0)
      if (!text.contains(range.startContainer) || !text.contains(range.endContainer)) { setSelection(null); return }
      const before = range.cloneRange(); before.selectNodeContents(text); before.setEnd(range.startContainer, range.startOffset)
      const start = before.toString().length, excerpt = range.toString(), end = start + excerpt.length
      setSelection(excerpt.trim() && unicode(excerpt) && message.content.slice(start, end) === excerpt ? { start, end } : null)
    }
    document.addEventListener('selectionchange', select)
    return () => document.removeEventListener('selectionchange', select)
  }, [message.content, message.messageId, !!prepare])
  const add = (start: number, end: number) => {
    if (!prepare || turn.status === 'PENDING' || message.messageId !== hit.messageId || message.role !== hit.role) return
    prepare({ kind: 'conversation', hit, source: { title: conversationSourceTitle(conversation.title), conversationId: conversation.id,
      turnId: turn.turnId, sequence: turn.sequence, messageId: message.messageId, role: message.role,
      startOffset: start, endOffset: end, excerpt: message.content.slice(start, end) } })
  }
  return <><pre ref={root} tabIndex={0} dir="auto" aria-label="命中的历史消息原文"><HighlightedText text={message.content} ranges={ranges} /></pre>
    {prepare && <div className="operation-actions">
      <button disabled={!selection} onMouseDown={event => event.preventDefault()} onClick={() => { if (selection) add(selection.start, selection.end) }}>将选中历史消息加入参考</button>
      <button onClick={() => add(0, message.content.length)}>将整条命中消息加入参考</button>
      <p className="hint">先选取这条消息中的文字，或明确加入整条消息。另一条消息和当时选择的记忆不会随之加入；助手回复仍需核实。</p>
    </div>}
  </>
}
