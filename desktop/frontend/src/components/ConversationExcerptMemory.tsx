import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { Conversation, Message, Turn } from '../bridge/conversations'
import { unicode, validContent } from '../bridge/memory'
import { conversationSourceHint, formatConversationSource, readConversationSource } from './memoryConversationSource'

export function ConversationExcerptMemory({ bridge, conversation, turn, message, enabled, onDraft }: {
  bridge: WorkspaceClient; conversation: Conversation; turn: Turn; message: Message; enabled: boolean
  onDraft: (content: string, role: Message['role']) => void
}) {
  const [open, setOpen] = useState(false), [range, setRange] = useState<{ start: number; end: number } | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const text = useRef<HTMLPreElement>(null), button = useRef<HTMLButtonElement>(null)
  const generation = useRef(0), lock = useRef(false)
  const current = useRef({ enabled, conversation, turn, message }); current.current = { enabled, conversation, turn, message }
  const clear = () => { generation.current++; lock.current = false; setOpen(false); setRange(null); setBusy(false); setError('') }
  useEffect(clear, [enabled, conversation.id, turn.turnId, message.messageId, message.content])
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  useEffect(() => {
    if (!open || !enabled) return
    text.current?.focus()
    const select = () => {
      if (lock.current) return
      const selection = window.getSelection(), root = text.current
      if (!root || !selection || selection.rangeCount !== 1 || selection.isCollapsed) return
      const selected = selection.getRangeAt(0)
      if (!root.contains(selected.startContainer) || !root.contains(selected.endContainer)) return
      const before = selected.cloneRange(); before.selectNodeContents(root); before.setEnd(selected.startContainer, selected.startOffset)
      const start = before.toString().length, content = selected.toString(), end = start + content.length
      setRange(content.trim() && unicode(content) && message.content.slice(start, end) === content ? { start, end } : null)
      setError('')
    }
    document.addEventListener('selectionchange', select)
    return () => document.removeEventListener('selectionchange', select)
  }, [open, enabled, message.content])
  const draft = range ? formatConversationSource(conversation, turn, message, range.start, range.end) : ''
  const hint = conversationSourceHint(draft)
  const allowed = !!range && validContent(draft) && hint.kind === 'found'
  const prepare = async () => {
    if (!enabled || lock.current || !allowed || hint.kind !== 'found' || turn.status === 'PENDING') return
    const token = ++generation.current
    const valid = () => token === generation.current && current.current.enabled && current.current.conversation.id === conversation.id
      && current.current.turn.turnId === turn.turnId && current.current.message.messageId === message.messageId && current.current.message.content === message.content
    const check = () => { if (!valid()) throw new Error('当前原消息已变化，请重新选取。') }
    lock.current = true; setBusy(true); setError('')
    try {
      const value = await readConversationSource(bridge, hint.source, check)
      check()
      if (value.original !== hint.source.excerpt) throw new Error('选中片段与重新读取的原消息不一致，未送入记忆草稿。请刷新对话后重新选取。')
      onDraft(draft, message.role)
    } catch (problem) { if (valid()) setError(problem instanceof Error ? problem.message : '原消息暂时无法核对，草稿未改变。') }
    finally { if (valid()) { lock.current = false; setBusy(false) } }
  }
  return <div className="conversation-excerpt">
    <button ref={button} disabled={!enabled || turn.status === 'PENDING'} aria-expanded={open} onClick={() => {
      if (open) clear(); else { setOpen(true); setRange(null); setError('') }
    }}>{open ? '收起对话摘录' : '摘录并保留对话出处'}</button>
    {open && enabled && <section className="memory-preview" aria-label="对话摘录准备" aria-busy={busy}>
      <h4>从原消息选择片段</h4><p className="hint">在下方原文中选择要保留的文字，或选择整条消息。草稿预览包含精确位置；送入后仍须填写标题并明确保存。</p>
      <pre ref={text} tabIndex={0} dir="auto" aria-label="可选取的对话原文">{message.content}</pre>
      <button disabled={busy} onClick={() => { setRange({ start: 0, end: message.content.length }); setError('') }}>选择整条消息</button>
      {range && <><p className="hint">选中范围：{range.start}–{range.end}；含出处草稿共 {draft.length} 个 UTF-16 单元，上限 2000。</p>
        <pre tabIndex={0} dir="auto" aria-label="带对话出处的草稿预览">{draft}</pre>
        {!allowed && <p className="error">所选片段加出处超出记忆限制，或含重复出处标记。请缩小选区；不会截断文字或省略出处。</p>}</>}
      <p className="hint">出处是可编辑文字，不证明内容属实。助手回复需要人工核实；此次操作不会保存记忆、选择记忆或发送请求给模型。</p>
      <div className="operation-actions"><button disabled={busy || !allowed} onClick={() => { void prepare() }}>核对原消息并送入记忆草稿</button>
        <button onClick={() => { clear(); button.current?.focus() }}>取消对话摘录</button></div>
      {busy && <p role="status">正在核对原消息，当前草稿尚未改变…</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </section>}
  </div>
}
