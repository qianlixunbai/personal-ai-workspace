import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation } from '../bridge/knowledgeAnswer'
import { readMemorySource } from './memoryKnowledgeSource'

export function KnowledgeAnswerEvidence({ bridge, citations, enabled = true }: { bridge: WorkspaceClient; citations: KnowledgeAnswerCitation[]; enabled?: boolean }) {
  const [selected, setSelected] = useState<number | null>(null), [original, setOriginal] = useState('')
  const [currentRevision, setCurrentRevision] = useState<string | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0)
  const clear = () => { generation.current++; setSelected(null); setOriginal(''); setCurrentRevision(null); setBusy(false); setError('') }
  useEffect(clear, [citations, enabled])
  useEffect(() => { const off = bridge.onSession(clear); return () => { generation.current++; off() } }, [bridge])
  const read = async (index: number) => {
    if (!enabled) return
    const citation = citations[index]
    if (!citation) return
    const token = ++generation.current, deadline = Date.now() + 30_000
    const check = () => { if (token !== generation.current) throw new Error('Citation closed'); if (Date.now() > deadline) throw new Error('引用原文读取超时，请主动重试。') }
    setSelected(index); setOriginal(''); setBusy(true); setError('')
    try {
      const detail = await bridge.getKnowledge(citation.documentId)
      check()
      if (detail.document.documentId !== citation.documentId || !detail.revisions.some(r => r.sourceRevision === citation.sourceRevision))
        throw new Error('引用资料或原版本已不可用，未回退到新版。')
      const result = await readMemorySource(bridge, { ...citation, excerpt: '' }, check)
      check(); setOriginal(result.text); setCurrentRevision(detail.document.currentReadyRevision)
    } catch (problem) { if (token === generation.current) setError(problem instanceof Error ? problem.message : '引用原文暂时不可用。') }
    finally { if (token === generation.current) setBusy(false) }
  }
  return <section aria-label="问答引用核对" aria-busy={busy}>
    <h4>原回答的引用</h4><p className="hint">逐项读取当时版本的引用原文；定位一致不证明回答被原文支持。资料删除后可能无法回查。</p>
    <ol>{citations.map((citation, index) => <li key={`${citation.documentId}/${citation.sourceRevision}/${citation.startOffset}`}>
      <p><bdi>{citation.title}</bdi> · 源版本 {citation.sourceRevision} · 行 {citation.startLine}–{citation.endLine} · 偏移 {citation.startOffset}–{citation.endOffset}</p>
      <button disabled={!enabled} onClick={() => { void read(index) }}>核对引用原文 {index + 1}</button>
      {selected === index && <>{busy && <p role="status">正在读取引用原文…</p>}{error && <p role="alert">{error}</p>}
        {original && <><p className="hint">已读取引用版本 {citation.sourceRevision}；当前可用版本 {currentRevision ?? '无'}。</p><pre tabIndex={0} dir="auto" aria-label="问答引用原文">{original}</pre></>}
        <button onClick={clear}>收起引用原文</button></>}
    </li>)}</ol>
  </section>
}
