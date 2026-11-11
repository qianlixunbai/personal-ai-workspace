import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeSearchHit, KnowledgeSearchResult, KnowledgeSearchStatus } from '../bridge/knowledgeSearch'
import { HighlightedText } from './HighlightedText'
import { KnowledgeSearchScopePicker, type KnowledgeSearchSource } from './KnowledgeSearchScopePicker'
import type { KnowledgeDocument } from '../bridge/knowledge'

const labels = { READY: '可用', BUILDING: '正在重建', STALE: '等待重建', FAILED: '重建失败' }
type SearchGroup = KnowledgeSearchSource & { hits: KnowledgeSearchHit[] | null; error: string | null }
export function KnowledgeSearch({ bridge, enabled, visible, corpusEpoch, open, currentDocument }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean; corpusEpoch: number; open: (hit: KnowledgeSearchHit) => Promise<void>; currentDocument?: KnowledgeDocument | undefined }) {
  const [sources, setSources] = useState<KnowledgeSearchSource[] | null>(null), [groups, setGroups] = useState<SearchGroup[] | null>(null)
  const [query, setQuery] = useState(''), [status, setStatus] = useState<KnowledgeSearchStatus | null>(null), [result, setResult] = useState<KnowledgeSearchResult | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), input = useRef<HTMLInputElement>(null), results = useRef<HTMLHeadingElement>(null)
  const available = useRef(enabled && visible); available.current = enabled && visible
  const clear = () => { generation.current++; setQuery(''); setResult(null); setGroups(null); setStatus(null); setBusy(false); setError(''); setSources(null) }
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  useEffect(() => { if (!visible) clear() }, [visible])
  useEffect(() => { generation.current++; setResult(null); setGroups(null); setBusy(false) }, [corpusEpoch])
  useEffect(() => {
    if (!enabled || !visible) return
    let disposed = false; let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try { const s = await bridge.knowledgeSearchStatus(); if (disposed) return; setStatus(s); if (s.state !== 'READY') { generation.current++; setResult(null); setGroups(null); setBusy(false); timer = setTimeout(() => { void poll() }, 1000) } }
      catch (e) { if (!disposed) { generation.current++; setResult(null); setGroups(null); setBusy(false); setError(e instanceof Error ? e.message : '索引状态暂时不可用。') } }
    }
    void poll(); return () => { disposed = true; clearTimeout(timer) }
  }, [bridge, enabled, visible, corpusEpoch, status?.state])
  const search = async () => {
    if (busy || !available.current) return
    if (!query.trim() || [...query].length > 128) { setError('请输入 1–128 个 Unicode 字符的关键词。'); return }
    if (sources?.length === 0) { setError('请先选择检索资料；空范围不会自动变为全部资料。'); return }
    const epoch = ++generation.current; setBusy(true); setResult(null); setGroups(null); setError('')
    try {
      if (sources === null) {
        const hits = await bridge.searchKnowledge(query, null)
        if (epoch !== generation.current) return
        setResult(hits)
      } else {
        let collected: SearchGroup[] = sources.map(source => ({ ...source, hits: null, error: null }))
        setGroups(collected)
        for (let index = 0; index < collected.length; index++) {
          if (epoch !== generation.current || !available.current) break
          const source = collected[index]!
          try {
            const hits = await bridge.searchKnowledge(query, source.scope)
            if (epoch !== generation.current) return
            if (hits.hits.some(hit => hit.documentId !== source.scope.documentId || hit.sourceRevision !== source.scope.sourceRevision))
              throw new BridgeError('InvalidResponse', '返回的资料范围不一致，本份结果未采用。')
            collected = collected.map((group, i) => i === index ? { ...group, hits: hits.hits } : group)
          } catch (e) {
            if (epoch !== generation.current) return
            collected = collected.map((group, i) => i === index ? { ...group, error: e instanceof Error ? e.message : '本份资料检索失败。' } : group)
            if (e instanceof BridgeError && e.code.startsWith('KnowledgeIndex')) {
              setGroups(collected); setError('索引暂不可用，后续资料未查询。请检查索引状态后显式检索。'); break
            }
          }
          setGroups(collected)
        }
      }
      setTimeout(() => { if (epoch === generation.current) results.current?.focus() }, 0)
    }
    catch (e) { if (epoch === generation.current) { setError(e instanceof Error ? e.message : '词法检索暂时不可用。'); if (e instanceof BridgeError && e.code.startsWith('KnowledgeIndex')) setStatus({ state: 'STALE', indexedDocuments: 0, indexedChunks: 0 }) } }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const rebuild = async () => { const epoch = ++generation.current; setBusy(true); setResult(null); setGroups(null); setError(''); try { const s = await bridge.rebuildKnowledgeSearch(); if (epoch === generation.current) setStatus(s) } catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '索引重建暂时不可用。') } finally { if (epoch === generation.current) setBusy(false) } }
  const hitsList = (hits: KnowledgeSearchHit[], label: string) => <ol tabIndex={0} aria-label={label}>{hits.map(hit => <li key={`${hit.documentId}/${hit.sourceRevision}/${hit.startOffset}`}>
    <h4>{hit.title}</h4><p className="knowledge-location">{hit.sourceType} · 源版本 {hit.sourceRevision} · 行 {hit.startLine}–{hit.endLine} · 文本偏移 {hit.startOffset}–{hit.endOffset}{hit.heading ? ` · ${hit.heading}` : ''}</p>
    <p className="knowledge-snippet"><HighlightedText text={hit.snippet} ranges={hit.highlightRanges} /></p><button disabled={!enabled || busy} onClick={() => { void open(hit) }}>打开此位置</button>
  </li>)}</ol>
  return <section className="card knowledge-search" aria-labelledby="knowledge-search-title">
    <div className="knowledge-search-tools"><h2 id="knowledge-search-title">查找资料</h2><p id="knowledge-search-help" className="sr-only">输入关键词，查找使用中的文档。</p>
    <form onKeyDown={e => { if (e.key === 'Enter' && e.nativeEvent.isComposing) e.preventDefault() }} onSubmit={e => { e.preventDefault(); void search() }}><label className="sr-only" htmlFor="knowledge-search-query">检索关键词</label>
      <input id="knowledge-search-query" placeholder="检索关键词" aria-describedby="knowledge-search-help" ref={input} value={query} disabled={!enabled || busy} autoComplete="off" spellCheck={false} onChange={e => { generation.current++; setQuery(e.target.value); setResult(null); setGroups(null); setError('') }} />
      <button type="submit" disabled={!enabled || busy || !query.trim() || sources?.length === 0}>检索</button></form></div>
    <KnowledgeSearchScopePicker sources={sources} current={currentDocument} disabled={!enabled || busy} change={next => { generation.current++; setSources(next); setResult(null); setGroups(null); setError('') }} />
    <div className="knowledge-search-meta"><p role="status" aria-live="polite">索引：{status ? labels[status.state] : '等待状态'}{busy ? ' · 正在处理' : ''}</p>
    {status?.state === 'BUILDING' && <p role="progressbar" aria-label="关键词索引重建">索引正在重建，完成后请显式检索。</p>}
    <details className="input-details"><summary>检索说明与索引维护</summary><p className="hint">所有关键词需出现在同一片段中；只检索当前可用版本，关键词仅保留在本页。</p><button disabled={!enabled || busy} onClick={() => { void rebuild() }}>重建关键词索引</button></details></div>
    {error && <p role="alert" className="error">{error}</p>}
    {result && <><h3 ref={results} tabIndex={-1}>检索结果 · {result.hits.length}</h3>{result.hits.length === 0 && <p>没有满足全部关键词的当前来源。</p>}
      {hitsList(result.hits, '检索结果列表')}</>}
    {groups && <>
      <h3 ref={results} tabIndex={-1}>所选资料检索 · {groups.filter(group => group.hits !== null).length} / {groups.length} 份查询成功</h3>
      <p className="hint">按所选资料分组，每份最多 10 个命中；每组保留该次查询的版本和排序。查询期间资料可能变更，比较结论前请重新核对来源。</p>
      {groups.some(group => group.error || group.hits === null) && <p role="status">结果尚不完整。请逐项查看失败或未查询的资料，不能据此认定全部所选资料均无匹配。</p>}
      {groups.map(group => <section key={group.scope.documentId} aria-label={`检索分组：${group.title}`}>
        <h4>{group.title} · 源版本 {group.scope.sourceRevision}</h4>
        {group.error ? <p role="alert" className="error">{group.error} 请核对这份资料；范围未扩大。</p>
          : group.hits === null ? <p>{busy ? '等待查询' : '尚未查询，请显式重新检索。'}</p>
          : group.hits.length === 0 ? <p>本份资料没有满足全部关键词的片段。</p>
          : hitsList(group.hits, `${group.title} 的检索结果`)}
      </section>)}
    </>}
  </section>
}
