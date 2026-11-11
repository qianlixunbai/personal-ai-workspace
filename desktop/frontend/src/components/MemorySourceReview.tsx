import { useEffect, useMemo, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeDocument, KnowledgePreview } from '../bridge/knowledge'
import { KnowledgeSourcePreview } from './KnowledgeSourcePreview'
import { memorySourceHint, readMemorySource, type MemoryKnowledgeSource } from './memoryKnowledgeSource'
import { ConversationSourceReview } from './ConversationSourceReview'
import { KnowledgeAnswerSourceReview } from './KnowledgeAnswerSourceReview'

type Review = { content: string; source: MemoryKnowledgeSource; document: KnowledgeDocument; original: string; first: KnowledgePreview }

export function MemorySourceReview(props: { bridge: WorkspaceClient; content: string; enabled?: boolean }) {
  return <><KnowledgeMemorySourceReview {...props} /><ConversationSourceReview {...props} /><KnowledgeAnswerSourceReview {...props} /></>
}
function KnowledgeMemorySourceReview({ bridge, content, enabled = true }: { bridge: WorkspaceClient; content: string; enabled?: boolean }) {
  const hint = useMemo(() => memorySourceHint(content), [content])
  const [review, setReview] = useState<Review | null>(null), [preview, setPreview] = useState<KnowledgePreview | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), lock = useRef(false), offsets = useRef<number[]>([])
  const current = useRef({ content, enabled }); current.current = { content, enabled }
  const clear = () => { generation.current++; lock.current = false; offsets.current = []; setReview(null); setPreview(null); setBusy(false); setError('') }
  useEffect(() => { clear() }, [content, enabled])
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  const run = async (work: (check: () => void) => Promise<void>) => {
    if (!enabled || lock.current) return
    const token = ++generation.current, snapshot = content, deadline = Date.now() + 30_000
    const valid = () => token === generation.current && current.current.enabled && current.current.content === snapshot
    const check = () => {
      if (!valid()) throw new Error('Source review no longer current')
      if (Date.now() > deadline) throw new Error('原文读取超时，本次未完成核对。请稍后主动重试。')
    }
    lock.current = true; setBusy(true); setError('')
    try { await work(check) }
    catch (problem) {
      if (valid()) setError(problem instanceof BridgeError && problem.code === 'KnowledgeNotFound'
        ? '所记资料或源版本已删除或不可用，无法回查；未改查其他资料或新版。'
        : problem instanceof Error ? problem.message : '资料出处暂时无法读取。')
    } finally { if (valid()) { lock.current = false; setBusy(false) } }
  }
  const inspect = () => run(async check => {
    if (hint.kind !== 'found') return
    const source = hint.source
    setReview(null); setPreview(null); offsets.current = []
    const detail = await bridge.getKnowledge(source.documentId)
    check()
    if (detail.document.documentId !== source.documentId) throw new Error('返回的资料身份与正文记录不符，未显示原文。')
    if (!detail.revisions.some(revision => revision.sourceRevision === source.sourceRevision))
      throw new Error('所记源版本已不可用，未回退到当前版本。')
    const result = await readMemorySource(bridge, source, check)
    check()
    setReview({ content, source, document: detail.document, original: result.text, first: result.first }); setPreview(result.first)
  })
  const navigate = (offset: number, history: 'push' | 'back' | 'before' = 'push') => run(async check => {
    if (!review || review.content !== content || !preview) return
    const destination = history === 'back' ? offsets.current.at(-1) : offset
    if (destination === undefined) return
    const source = review.source
    const next = await (history === 'before' ? bridge.previewKnowledgeBefore(source.documentId, source.sourceRevision, destination)
      : bridge.previewKnowledge(source.documentId, source.sourceRevision, destination))
    check()
    if (history === 'back') offsets.current = offsets.current.slice(0, -1)
    else if (preview.offset !== next.offset) offsets.current = [...offsets.current.slice(-31), preview.offset]
    setPreview(next)
  })
  if (hint.kind === 'absent') return null
  const shown = enabled && review?.content === content ? review : null
  return <section className="memory-revision-review" aria-label="资料出处回查" aria-busy={busy}>
    <h4>资料出处</h4>
    <p className="hint">按正文中可编辑的出处定位，只读核对；不证明这条记忆最初来自该资料，也不验证内容是否属实。</p>
    {hint.kind === 'invalid' ? <p className="hint">出处格式不完整、范围无效或有多处记录，无法确定要回查的位置。请先核对正文中的资料 ID、版本与范围。</p> : <>
      <p>正文记录：<bdi>{hint.source.title}</bdi> · 源版本 {hint.source.sourceRevision} · 文本偏移 {hint.source.startOffset}–{hint.source.endOffset}</p>
      <details><summary>资料标识</summary><p><bdi>{hint.source.documentId}</bdi></p></details>
      <div className="operation-actions">
        <button disabled={!enabled || busy} onClick={() => { void inspect() }}>{shown ? '重新核对出处' : '查看资料出处'}</button>
        {(review || busy || error) && <button onClick={clear}>收起出处</button>}
      </div>
    </>}
    {busy && <p role="status">正在读取指定资料和版本…</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {shown && <>
      <p>读取到的资料：<bdi>{shown.document.title}</bdi> · {shown.document.status === 'ARCHIVED' ? '已归档' : '使用中'} · 所读源版本 {shown.source.sourceRevision} · 当前可用版本 {shown.document.currentReadyRevision ?? '无'}</p>
      {shown.document.title !== shown.source.title && <p className="hint">资料名称与正文记录不同；本次按资料 ID 和所记版本读取。</p>}
      <p role="status">{shown.original === shown.source.excerpt ? '出处之前的正文与该版本、该范围的原文逐字一致。' : '出处之前的正文与该范围原文不一致；可能经过编辑或定位记录有误。请对照核实，记忆不会被替换。'}</p>
      <p className="hint">出处之后的批注不参与对照。这里只对照文字，不推断其含义。</p>
      <pre tabIndex={0} dir="auto" aria-label="所记范围原文">{shown.original}</pre>
      <details><summary>阅读原文上下文</summary>
        {preview && <KnowledgeSourcePreview preview={preview} target={null} currentRevision={shown.document.currentReadyRevision}
          disabled={!enabled || busy} hasPrevious={offsets.current.length > 0} navigate={navigate} />}
        {preview?.offset !== shown.first.offset && <button disabled={!enabled || busy} onClick={() => { void navigate(shown.source.startOffset) }}>返回所记范围</button>}
      </details>
    </>}
  </section>
}
