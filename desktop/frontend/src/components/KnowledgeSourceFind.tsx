import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import { HighlightedText } from './HighlightedText'
import { readSourceText } from './knowledgeSourceText'
import { findSourceMatches, sourceFindLimit, validSourceQuery, type SourceMatch } from './knowledgeSourceMatches'

export function KnowledgeSourceFind({ bridge, documentId, revision, enabled, disabled, open }: {
  bridge: WorkspaceClient; documentId: string; revision: string; enabled: boolean; disabled: boolean
  open: (match: SourceMatch) => Promise<void>
}) {
  const [query, setQuery] = useState(''), [result, setResult] = useState<ReturnType<typeof findSourceMatches> | null>(null)
  const [page, setPage] = useState(0), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const epoch = useRef(0), running = useRef(false), stopped = useRef(false)
  useEffect(() => {
    const clear = () => { epoch.current++; stopped.current = true; setQuery(''); setResult(null); setPage(0); setError(''); setNotice('') }
    const unsubscribe = bridge.onSession(clear)
    return () => { epoch.current++; stopped.current = true; unsubscribe() }
  }, [bridge, documentId, revision])
  useEffect(() => {
    if (!enabled) { epoch.current++; stopped.current = true; setQuery(''); setResult(null); setPage(0); setError(''); setNotice('') }
  }, [enabled])
  const find = async () => {
    if (!enabled || disabled || running.current || !validSourceQuery(query)) return
    const request = ++epoch.current, deadline = Date.now() + 30_000
    const check = () => {
      if (request !== epoch.current || stopped.current) throw new Error('已停止查找。')
      if (Date.now() > deadline) throw new Error('原文读取超时，未显示部分查找结果。请稍后重试。')
    }
    running.current = true; stopped.current = false; setBusy(true); setResult(null); setPage(0); setError(''); setNotice('正在读取所选版本的完整原文…')
    try {
      const text = await readSourceText(bridge, documentId, revision, check, '此版本过长或分节过多，超出本次查找范围。请使用原文分页阅读；未显示部分查找结果。')
      check()
      const found = findSourceMatches(text, query)
      check(); setResult(found); setNotice(`已完整查找源版本 ${revision}，找到 ${found.total} 处。${found.total > sourceFindLimit ? `仅列出前 ${sourceFindLimit} 处，请使用更具体的短语。` : ''}`)
    } catch (e) {
      if (request === epoch.current) {
        setError(stopped.current ? '' : e instanceof Error ? e.message : '原文查找暂时不可用。')
        setNotice(stopped.current ? '已停止查找。' : '')
      }
    } finally {
      running.current = false; setBusy(false)
    }
  }
  const change = (value: string) => {
    epoch.current++; stopped.current = true; setQuery(value); setResult(null); setPage(0); setError(''); setNotice('')
  }
  return <details className="knowledge-source-find advanced-section">
    <summary>在此版本中查找</summary>
    <p className="hint">只查找所选源版本 {revision} 的已保存文本，区分大小写，按原样匹配空格、换行和符号。无需模型或检索索引。</p>
    <form onSubmit={e => { e.preventDefault(); void find() }} onKeyDown={e => { if (e.key === 'Enter' && e.nativeEvent.isComposing) e.preventDefault() }}>
      <label htmlFor="knowledge-source-query">原文查找短语</label>
      <textarea id="knowledge-source-query" rows={2} maxLength={256} value={query} disabled={!enabled || disabled} onChange={e => change(e.target.value)} />
      <div className="conversation-actions"><button disabled={!enabled || disabled || busy || !validSourceQuery(query)} type="submit">查找此版本</button>
        {busy && <button type="button" onClick={() => { stopped.current = true; setResult(null); setNotice('正在停止，等待读取结束…') }}>停止查找</button>}</div>
    </form>
    <p className="hint">适合中短篇资料，最多读取约 10 万字、1 万行或 128 页预览。只有原文完整读回后才显示结果；关闭页面后清除查找内容。</p>
    <p role="status">{notice}</p>
    {error && <p role="alert" className="error">{error}</p>}
    {result && result.total > 0 && <section aria-label="版本内查找结果">
      <ol start={page * 20 + 1}>{result.matches.slice(page * 20, (page + 1) * 20).map((match, index) => <li key={match.startOffset}>
        <p>源版本 {revision} · 行 {match.startLine}–{match.endLine}</p>
        <pre>{match.leading ? '…' : ''}<HighlightedText text={match.snippet} ranges={[{ start: match.startOffset - match.snippetOffset, end: match.endOffset - match.snippetOffset }]} />{match.trailing ? '…' : ''}</pre>
        <button disabled={!enabled || disabled || busy} onClick={() => { void open(match) }}>打开第 {page * 20 + index + 1} 处原文</button>
      </li>)}</ol>
      <div className="pagination"><button disabled={page === 0 || disabled || !enabled} onClick={() => setPage(page - 1)}>上一页命中</button><span>{page + 1} / {Math.ceil(result.matches.length / 20)}</span><button disabled={(page + 1) * 20 >= result.matches.length || disabled || !enabled} onClick={() => setPage(page + 1)}>下一页命中</button></div>
    </section>}
  </details>
}
