import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import { isConversationSearchQuery, type ConversationSearchHit, type ConversationSearchPage, type ConversationSearchScope } from '../bridge/conversations'

export function ConversationSearch({ bridge, enabled, visible, onOpen }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean
  onOpen: (hit: ConversationSearchHit) => Promise<void>
}) {
  const [query, setQuery] = useState(''), [scope, setScope] = useState<ConversationSearchScope>('ALL')
  const [result, setResult] = useState<ConversationSearchPage | null>(null), [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const generation = useRef(0), lock = useRef(false)
  const clear = () => { generation.current++; lock.current = false; setResult(null); setBusy(false); setError('') }
  useEffect(() => {
    clear(); setQuery(''); setScope('ALL')
    const unsubscribe = bridge.onSession(() => { clear(); setQuery(''); setScope('ALL') })
    return () => { generation.current++; unsubscribe() }
  }, [bridge, visible])
  const search = async (page = 0) => {
    if (!enabled || !visible || lock.current || !isConversationSearchQuery(query)) return
    const epoch = ++generation.current; lock.current = true; setBusy(true); setError(''); setResult(null)
    try { const next = await bridge.searchConversations(query, scope, page); if (epoch === generation.current) setResult(next) }
    catch (problem) { if (epoch === generation.current) setError(problem instanceof Error ? problem.message : '查找暂时不可用，请重试。') }
    finally { if (epoch === generation.current) { lock.current = false; setBusy(false) } }
  }
  const open = async (hit: ConversationSearchHit) => {
    if (!enabled || !visible || lock.current) return
    const epoch = generation.current; lock.current = true; setBusy(true); setError('')
    try { await onOpen(hit) }
    catch (problem) { if (epoch === generation.current) setError(problem instanceof Error ? problem.message : '无法打开原会话，请重新查找。') }
    finally { if (epoch === generation.current) { lock.current = false; setBusy(false) } }
  }
  return <details className="conversation-search"><summary>查找历史消息</summary>
    <form onSubmit={event => { event.preventDefault(); void search() }}>
      <label htmlFor="conversation-search-query">原文关键词<input id="conversation-search-query" value={query} maxLength={200} disabled={!enabled || busy}
        onChange={event => { clear(); setQuery(event.target.value) }} /></label>
      <label htmlFor="conversation-search-scope">查找范围<select id="conversation-search-scope" value={scope} disabled={!enabled || busy}
        onChange={event => { clear(); setScope(event.target.value as ConversationSearchScope) }}><option value="ALL">全部会话</option><option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option></select></label>
      <p className="muted">按连续原文匹配，区分大小写，最多 100 个字。查找已结束轮次中的提问和回答。</p>
      <div className="conversation-actions"><button type="submit" disabled={!enabled || busy || !isConversationSearchQuery(query)}>查找消息</button>
        <button type="button" disabled={busy || (!query && !result)} onClick={() => { clear(); setQuery('') }}>清除查找</button></div>
    </form>
    {busy && <p role="status">正在读取…</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {result && <div aria-label="历史消息查找结果">
      <p role="status">第 {result.page + 1} 页 · {result.items.length} 条匹配消息</p>
      {result.items.length === 0 && <p>没有匹配消息。可调整关键词或查找范围。</p>}
      <ul>{result.items.map(hit => <li key={hit.messageId}>
        <button disabled={!enabled || busy} onClick={() => { void open(hit) }}>
          <bdi>{hit.conversation.title}</bdi> · 第 {hit.sequence} 轮 · {hit.role === 'USER' ? '你' : '助手'}{hit.conversation.status === 'ARCHIVED' ? ' · 已归档' : ''}
        </button>
        <pre dir="auto">{hit.before ? '…' : ''}{hit.snippet.slice(0, hit.matchStart)}<mark>{hit.snippet.slice(hit.matchStart, hit.matchEnd)}</mark>{hit.snippet.slice(hit.matchEnd)}{hit.after ? '…' : ''}</pre>
      </li>)}</ul>
      <div className="pagination"><button disabled={!enabled || busy || result.page === 0} onClick={() => { void search(result.page - 1) }}>上一页匹配</button>
        <button disabled={!enabled || busy || !result.hasMore || result.page >= 199999} onClick={() => { void search(result.page + 1) }}>下一页匹配</button></div>
    </div>}
  </details>
}
