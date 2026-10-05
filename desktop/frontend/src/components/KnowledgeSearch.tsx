import { Fragment, useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeSearchHit, KnowledgeSearchResult, KnowledgeSearchStatus } from '../bridge/knowledgeSearch'

const labels = { READY: '可用', BUILDING: '正在重建', STALE: '等待重建', FAILED: '重建失败' }
function Highlight({ hit }: { hit: KnowledgeSearchHit }) {
  let offset = 0
  const pieces = hit.highlightRanges.map((r, i) => { const plain = hit.snippet.slice(offset, r.start); offset = r.end; return <Fragment key={i}>{plain}<mark>{hit.snippet.slice(r.start, r.end)}</mark></Fragment> })
  return <>{pieces}{hit.snippet.slice(offset)}</>
}
export function KnowledgeSearch({ bridge, enabled, visible, corpusEpoch, open }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean; corpusEpoch: number; open: (hit: KnowledgeSearchHit) => Promise<void> }) {
  const [query, setQuery] = useState(''), [status, setStatus] = useState<KnowledgeSearchStatus | null>(null), [result, setResult] = useState<KnowledgeSearchResult | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), input = useRef<HTMLInputElement>(null), results = useRef<HTMLHeadingElement>(null)
  const clear = () => { generation.current++; setQuery(''); setResult(null); setStatus(null); setBusy(false); setError('') }
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  useEffect(() => { if (!visible) clear() }, [visible])
  useEffect(() => { generation.current++; setResult(null); setBusy(false) }, [corpusEpoch])
  useEffect(() => {
    if (!enabled || !visible) return
    let disposed = false; let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try { const s = await bridge.knowledgeSearchStatus(); if (disposed) return; setStatus(s); if (s.state !== 'READY') { setResult(null); timer = setTimeout(() => { void poll() }, 1000) } }
      catch (e) { if (!disposed) { setResult(null); setError(e instanceof Error ? e.message : '索引状态暂时不可用。') } }
    }
    void poll(); return () => { disposed = true; clearTimeout(timer) }
  }, [bridge, enabled, visible, corpusEpoch, status?.state])
  const search = async () => {
    if (busy || !enabled) return
    if (!query.trim() || [...query].length > 128) { setError('请输入 1–128 个 Unicode 字符的关键词。'); return }
    const epoch = ++generation.current; setBusy(true); setResult(null); setError('')
    try { const hits = await bridge.searchKnowledge(query); if (epoch === generation.current) { setResult(hits); setTimeout(() => { if (epoch === generation.current) results.current?.focus() }, 0) } }
    catch (e) { if (epoch === generation.current) { setError(e instanceof Error ? e.message : '词法检索暂时不可用。'); if (e instanceof BridgeError && e.code.startsWith('KnowledgeIndex')) setStatus({ state: 'STALE', indexedDocuments: 0, indexedChunks: 0 }) } }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const rebuild = async () => { const epoch = ++generation.current; setBusy(true); setResult(null); setError(''); try { const s = await bridge.rebuildKnowledgeSearch(); if (epoch === generation.current) setStatus(s) } catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '索引重建暂时不可用。') } finally { if (epoch === generation.current) setBusy(false) } }
  return <section className="card knowledge-search" aria-labelledby="knowledge-search-title">
    <h2 id="knowledge-search-title">关键词检索</h2><p className="hint">词法 AND 检索 · 仅 ACTIVE 文档的当前 READY 源版本 · 关键词仅保留在当前页面。</p>
    <form onKeyDown={e => { if (e.key === 'Enter' && e.nativeEvent.isComposing) e.preventDefault() }} onSubmit={e => { e.preventDefault(); void search() }}><label htmlFor="knowledge-search-query">检索关键词</label>
      <input id="knowledge-search-query" ref={input} value={query} disabled={!enabled || busy} autoComplete="off" spellCheck={false} onChange={e => { generation.current++; setQuery(e.target.value); setResult(null); setError('') }} />
      <button type="submit" disabled={!enabled || busy || !query.trim()}>检索</button></form>
    <p role="status" aria-live="polite">索引：{status ? labels[status.state] : '等待状态'}{busy ? ' · 正在处理' : ''}</p>
    {status?.state === 'BUILDING' && <p role="progressbar" aria-label="关键词索引重建">索引正在重建，完成后请显式检索。</p>}
    <button disabled={!enabled || busy} onClick={() => { void rebuild() }}>重建关键词索引</button>
    {error && <p role="alert" className="error">{error}</p>}
    {result && <><h3 ref={results} tabIndex={-1}>检索结果 · {result.hits.length}</h3>{result.hits.length === 0 && <p>没有满足全部关键词的当前来源。</p>}
      <ol>{result.hits.map(hit => <li key={`${hit.documentId}/${hit.sourceRevision}/${hit.startOffset}`}>
        <h4>{hit.title}</h4><p className="knowledge-location">{hit.sourceType} · 源版本 {hit.sourceRevision} · 行 {hit.startLine}–{hit.endLine} · 文本偏移 {hit.startOffset}–{hit.endOffset}{hit.heading ? ` · ${hit.heading}` : ''}</p>
        <p className="knowledge-snippet"><Highlight hit={hit} /></p><button disabled={!enabled || busy} onClick={() => { void open(hit) }}>打开此位置</button>
      </li>)}</ol></>}
  </section>
}
