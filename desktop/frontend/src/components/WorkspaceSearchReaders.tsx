import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { Conversation, ConversationSearchHit, Turn } from '../bridge/conversations'
import type { KnowledgeDetail, KnowledgePreview } from '../bridge/knowledge'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'
import { KnowledgeSourcePreview } from './KnowledgeSourcePreview'
import type { ConversationReference } from './conversationReferences'
import { conversationSourceTitle, readConversationSource, type MemoryConversationSource } from './memoryConversationSource'
import { ConversationReferenceMessage } from './ConversationReferenceMessage'

export const searchReadError = (problem: unknown) => problem instanceof BridgeError ? problem.message : '读取暂时不可用，请重试。'

export function WorkspaceConversationReader({ bridge, hit, query, source, prepare }: {
  bridge: WorkspaceClient; hit: ConversationSearchHit; query: string; source?: MemoryConversationSource | undefined
  prepare?: ((reference: ConversationReference) => void) | undefined
}) {
  const [value, setValue] = useState<{ conversation: Conversation; turn: Turn } | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let disposed = false
    setValue(null); setError('')
    void (async () => {
      if (source) {
        const result = await readConversationSource(bridge, source, () => { if (disposed) throw new Error('Reader closed') })
        if (disposed) return
        if (result.original !== source.excerpt || conversationSourceTitle(result.conversation.title) !== source.title) {
          setError('待插入的历史消息或会话名称已变化，请移除后重新选择。'); return
        }
        setValue({ conversation: result.conversation, turn: result.turn }); return
      }
      const detail = await bridge.getConversation(hit.conversation.id, Math.floor((hit.sequence - 1) / 10))
      if (disposed) return
      const turn = detail.turns.find(item => item.turnId === hit.turnId && item.sequence === hit.sequence)
      const message = hit.role === 'USER' ? turn?.userMessage : turn?.assistantMessage
      if (!turn || turn.status === 'PENDING' || !message || message.messageId !== hit.messageId || message.role !== hit.role || !message.content.includes(query)) {
        setError('该匹配消息已不可用，请重新查找。'); return
      }
      setValue({ conversation: detail.conversation, turn })
    })().catch(problem => { if (!disposed) setError(searchReadError(problem)) })
    return () => { disposed = true }
  }, [bridge, hit, query, source])
  return <section className="memory-preview" aria-label="查找对话原文" aria-busy={!value && !error}>
    {!value && !error && <p role="status">正在读取命中轮次…</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {value && <><h3 dir="auto">{value.conversation.title}</h3>
      <p className="hint">第 {value.turn.sequence} 轮 · {value.conversation.status === 'ARCHIVED' ? '已归档' : '活动会话'} · 只读查看，当前会话与草稿保持不变。</p>
      {[value.turn.userMessage, value.turn.assistantMessage].map(message => {
        if (!message) return null
        const chosen = message.messageId === hit.messageId, match = chosen ? message.content.indexOf(query) : -1
        const ranges = source && chosen ? [{ start: source.startOffset, end: source.endOffset }] : match < 0 ? [] : [{ start: match, end: match + query.length }]
        return <div key={message.messageId}><h4>{message.role === 'USER' ? '用户消息' : '助手回复'}{chosen ? source ? ' · 所选参考消息' : ' · 命中消息' : ''}</h4>
          {chosen ? <ConversationReferenceMessage conversation={value.conversation} turn={value.turn} message={message} hit={hit} ranges={ranges} prepare={prepare} />
            : <pre tabIndex={0} dir="auto">{message.content}</pre>}</div>
      })}
      {!value.turn.assistantMessage && <p className="hint">此轮没有已保存的助手回复。</p>}
    </>}
  </section>
}

export function WorkspaceKnowledgeReader({ bridge, hit, prepare, excerpt = false }: {
  bridge: WorkspaceClient; hit: KnowledgeSearchHit; prepare?: ((reference: ConversationReference) => void) | undefined; excerpt?: boolean | undefined
}) {
  const [detail, setDetail] = useState<KnowledgeDetail | null>(null)
  const [preview, setPreview] = useState<KnowledgePreview | null>(null)
  const [previous, setPrevious] = useState<number[]>([])
  const [busy, setBusy] = useState(true), [error, setError] = useState('')
  const generation = useRef(0), lock = useRef(true)
  useEffect(() => {
    const token = ++generation.current
    void (async () => {
      try {
        const result = await bridge.getKnowledge(hit.documentId)
        if (token !== generation.current) return
        if (!result.revisions.some(revision => revision.sourceRevision === hit.sourceRevision)) {
          setError('命中的资料版本已不可用，请重新查找。未回退到当前版本。'); return
        }
        const page = await bridge.previewKnowledge(hit.documentId, hit.sourceRevision, hit.startOffset)
        if (token === generation.current) { setDetail(result); setPreview(page) }
      } catch (problem) { if (token === generation.current) setError(searchReadError(problem)) }
      finally { if (token === generation.current) { lock.current = false; setBusy(false) } }
    })()
    return () => { generation.current++ }
  }, [bridge, hit])
  const navigate = async (offset: number, mode: 'push' | 'back' | 'before' = 'push') => {
    if (lock.current || !preview) return
    const destination = mode === 'back' ? previous.at(-1) : offset
    if (destination === undefined) return
    const token = generation.current
    lock.current = true; setBusy(true); setError('')
    try {
      const result = await (mode === 'before' ? bridge.previewKnowledgeBefore(hit.documentId, hit.sourceRevision, destination)
        : bridge.previewKnowledge(hit.documentId, hit.sourceRevision, destination))
      if (token !== generation.current) return
      setPrevious(mode === 'back' ? previous.slice(0, -1) : [...previous, preview.offset].slice(-32)); setPreview(result)
    } catch (problem) { if (token === generation.current) setError(searchReadError(problem)) }
    finally { if (token === generation.current) { lock.current = false; setBusy(false) } }
  }
  return <section className="memory-preview" aria-label="查找资料原文" aria-busy={busy}>
    {busy && <p role="status">正在读取资料原文…</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {detail && preview && <><h3 dir="auto">{detail.document.title}</h3>
      {detail.document.status === 'ARCHIVED' && <p className="hint">这份资料现已归档。</p>}
      <KnowledgeSourcePreview preview={preview} target={{ ...hit, kind: excerpt ? 'excerpt' : 'search' }} currentRevision={detail.document.currentReadyRevision}
        disabled={busy} hasPrevious={previous.length > 0} navigate={navigate} excerptPurpose="conversation"
        saveExcerpt={prepare ? (start, end) => {
          if (lock.current) return
          const startOffset = preview.offset + start, endOffset = preview.offset + end
          prepare({ kind: 'knowledge', source: { documentId: preview.documentId, sourceRevision: preview.sourceRevision,
            title: detail.document.title, startOffset, endOffset, excerpt: preview.text.slice(start, end) },
            hit: { ...hit, title: detail.document.title, startOffset, endOffset,
              startLine: preview.locators[0]?.startLine ?? hit.startLine, endLine: preview.locators.at(-1)?.endLine ?? hit.endLine } })
        } : undefined} />
    </>}
  </section>
}
