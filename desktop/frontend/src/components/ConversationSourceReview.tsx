import { useEffect, useMemo, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import { conversationSourceHint, conversationSourceTitle, readConversationSource } from './memoryConversationSource'

type Review = Awaited<ReturnType<typeof readConversationSource>> & { content: string }
export function ConversationSourceReview({ bridge, content, enabled = true }: { bridge: WorkspaceClient; content: string; enabled?: boolean }) {
  const hint = useMemo(() => conversationSourceHint(content), [content])
  const [review, setReview] = useState<Review | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), lock = useRef(false)
  const current = useRef({ content, enabled }); current.current = { content, enabled }
  const clear = () => { generation.current++; lock.current = false; setReview(null); setBusy(false); setError('') }
  useEffect(clear, [content, enabled])
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  const inspect = async () => {
    if (!enabled || lock.current || hint.kind !== 'found') return
    const token = ++generation.current, snapshot = content
    const valid = () => token === generation.current && current.current.enabled && current.current.content === snapshot
    const check = () => { if (!valid()) throw new Error('对话出处已变化，请重新查看。') }
    lock.current = true; setBusy(true); setReview(null); setError('')
    try {
      const value = await readConversationSource(bridge, hint.source, check)
      check(); setReview({ ...value, content: snapshot })
    } catch (problem) {
      if (valid()) setError(problem instanceof BridgeError && problem.code === 'ConversationNotFound'
        ? '所记会话已删除或不可用，无法回查；未改查其他会话。' : problem instanceof Error ? problem.message : '对话出处暂时无法读取。')
    } finally { if (valid()) { lock.current = false; setBusy(false) } }
  }
  if (hint.kind === 'absent') return null
  const shown = enabled && review?.content === content ? review : null
  return <section className="memory-revision-review" aria-label="对话出处回查" aria-busy={busy}>
    <h4>对话出处</h4><p className="hint">按正文中的可编辑位置只读回查，不证明记忆最初来自此消息或内容属实，也不会把历史记忆选择用于当前请求。</p>
    {hint.kind === 'invalid' ? <p className="hint">对话出处格式不完整、范围无效或有多处记录，无法确定原消息。请先核对正文中的标识与位置。</p> : <>
      <p>正文记录：<bdi>{hint.source.title}</bdi> · 第 {hint.source.sequence} 轮 · {hint.source.role === 'USER' ? '用户消息' : '助手回复'} · 文本偏移 {hint.source.startOffset}–{hint.source.endOffset}</p>
      <details><summary>对话位置标识</summary><p>会话：<bdi>{hint.source.conversationId}</bdi><br />轮次：<bdi>{hint.source.turnId}</bdi><br />消息：<bdi>{hint.source.messageId}</bdi></p></details>
      <div className="operation-actions"><button disabled={!enabled || busy} onClick={() => { void inspect() }}>{shown ? '重新核对对话出处' : '查看对话出处'}</button>
        {(review || busy || error) && <button onClick={clear}>收起对话出处</button>}</div>
      {shown && <><p>读取到的会话：<bdi>{shown.conversation.title}</bdi> · {shown.conversation.status === 'ARCHIVED' ? '已归档' : '使用中'}</p>
        {conversationSourceTitle(shown.conversation.title) !== hint.source.title && <p className="hint">会话名称与正文记录不同；本次按精确标识读取。</p>}
        <p role="status">{shown.original === hint.source.excerpt ? '出处之前的正文与该消息、该范围的原文逐字一致。' : '出处之前的正文与所记消息范围不一致，请对照核实；记忆不会被替换。'}</p>
        <p className="hint">出处后的批注不参与对照。助手回复仍需人工核实。</p>
        <pre dir="auto" tabIndex={0} aria-label="对话所记范围原文">{shown.original}</pre>
        <details><summary>阅读原轮次上下文</summary>
          {[shown.turn.userMessage, shown.turn.assistantMessage].map(message => message && <div key={message.messageId}>
            <h5>{message.role === 'USER' ? '用户消息' : '助手回复'}{message.messageId === hint.source.messageId ? ' · 所记消息' : ''}</h5>
            <pre dir="auto" tabIndex={0}>{message.content}</pre></div>)}
          {!shown.turn.assistantMessage && <p className="hint">此轮没有已保存的助手回复。</p>}
        </details></>}
    </>}
    {busy && <p role="status">正在读取指定轮次与消息…</p>}
    {error && <p role="alert" className="error">{error}</p>}
  </section>
}
