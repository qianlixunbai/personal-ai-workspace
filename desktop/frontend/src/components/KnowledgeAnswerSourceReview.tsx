import { useEffect, useMemo, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import { answerSourceHint, readAnswerSource } from './memoryAnswerSource'
import { AnswerContent } from './AnswerContent'
import { KnowledgeAnswerEvidence } from './KnowledgeAnswerEvidence'

export function KnowledgeAnswerSourceReview({ bridge, content, enabled = true }: { bridge: WorkspaceClient; content: string; enabled?: boolean }) {
  const hint = useMemo(() => answerSourceHint(content), [content])
  const [value, setValue] = useState<Awaited<ReturnType<typeof readAnswerSource>> | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), lock = useRef(false)
  const current = useRef({ content, enabled }); current.current = { content, enabled }
  const clear = () => { generation.current++; lock.current = false; setValue(null); setBusy(false); setError('') }
  useEffect(clear, [content, enabled])
  useEffect(() => { const off = bridge.onSession(clear); return () => { generation.current++; off() } }, [bridge])
  const inspect = async () => {
    if (!enabled || lock.current || hint.kind !== 'found') return
    const token = ++generation.current
    const valid = () => token === generation.current && current.current.enabled && current.current.content === content
    const check = () => { if (!valid()) throw new Error('Answer source closed') }
    lock.current = true; setBusy(true); setError(''); setValue(null)
    try { const result = await readAnswerSource(bridge, hint.source, check); check(); setValue(result) }
    catch (problem) { if (valid()) setError(problem instanceof Error ? problem.message : '原问答暂时无法读取。') }
    finally { if (valid()) { lock.current = false; setBusy(false) } }
  }
  if (hint.kind === 'absent') return null
  return <section className="memory-revision-review" aria-label="知识问答出处回查" aria-busy={busy}>
    <h4>知识问答出处</h4><p className="hint">正文中的定位可以编辑，不证明来源身份或回答正确。原问答删除后，保留的文字仍在，但无法通过此入口恢复记录。</p>
    {hint.kind === 'invalid' ? <p role="alert">问答出处格式或范围无效，无法确定原回答。请核对正文。</p> : <>
      <p>问答 ID：{hint.source.taskId} · 回答偏移 {hint.source.startOffset}–{hint.source.endOffset}</p>
      <button disabled={!enabled || busy} onClick={() => { void inspect() }}>查看原问答与引用</button>
      {(value || busy || error) && <button onClick={clear}>收起问答出处</button>}
      {busy && <p role="status">正在读取原问答…</p>}{error && <p role="alert">{error}</p>}
      {enabled && value && <>
        <p role="status">{value.original === hint.source.excerpt ? '出处之前的正文与原回答指定范围逐字一致；回答仍需核实。' : '出处之前的正文与原回答不同，请对照核实；记忆不会被替换。'}</p>
        <pre aria-label="所记回答片段" tabIndex={0} dir="auto">{value.original}</pre>
        <details><summary>阅读原问题与完整回答</summary><p dir="auto">{value.entry.question}</p><p>原检索关键词：{value.entry.query}</p>
          <p>原资料范围：{value.entry.scope ? `${value.entry.scope.documentId} · 源版本 ${value.entry.scope.sourceRevision}` : '全部可用资料'}</p>
          <AnswerContent text={value.entry.task.result!.answer} /></details>
        <KnowledgeAnswerEvidence bridge={bridge} citations={value.entry.task.result!.citations} enabled={enabled} />
      </>}
    </>}
  </section>
}
