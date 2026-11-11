import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import { readSourceOutline, type SourceOutlineEntry } from './knowledgeOutline'

export function KnowledgeSourceOutline({ bridge, documentId, revision, enabled, disabled, open }: {
  bridge: WorkspaceClient; documentId: string; revision: string; enabled: boolean; disabled: boolean
  open: (offset: number) => Promise<void>
}) {
  const [entries, setEntries] = useState<SourceOutlineEntry[] | null>(null)
  const [query, setQuery] = useState(''), [page, setPage] = useState(0)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const epoch = useRef(0), running = useRef(false), stopped = useRef(false)
  const clear = () => {
    epoch.current++; running.current = false; stopped.current = true
    setEntries(null); setQuery(''); setPage(0); setBusy(false); setError(''); setNotice('')
  }
  useEffect(() => {
    clear()
    const off = bridge.onSession(clear)
    return () => { epoch.current++; stopped.current = true; off() }
  }, [bridge, documentId, revision])
  useEffect(() => { if (!enabled) clear() }, [enabled])
  const read = async () => {
    if (!enabled || disabled || running.current) return
    const request = ++epoch.current, deadline = Date.now() + 30_000
    running.current = true; stopped.current = false
    setBusy(true); setEntries(null); setQuery(''); setPage(0); setError(''); setNotice('正在读取所选版本的章节目录…')
    const check = () => {
      if (request !== epoch.current || stopped.current) throw new Error('目录读取已停止。')
      if (Date.now() > deadline) throw new Error('目录读取超时，未显示部分目录。请稍后主动重试。')
    }
    try {
      const result = await readSourceOutline(bridge, documentId, revision, check)
      check(); setEntries(result)
      setNotice(result.length ? `已读取源版本 ${revision} 的目录，共 ${result.length} 个标题。` : '此版本没有可用的 Markdown 标题。可使用原文查找或行号定位。')
    } catch (problem) {
      if (request === epoch.current) {
        setError(stopped.current ? '' : problem instanceof Error ? problem.message : '资料目录暂时不可用。')
        setNotice(stopped.current ? '目录读取已停止。' : '')
      }
    } finally {
      if (request === epoch.current) { running.current = false; setBusy(false) }
    }
  }
  const filtered = entries?.filter(entry => entry.heading.includes(query.trim())) ?? []
  return <details className="advanced-section">
    <summary>章节目录</summary>
    <p className="hint">按已保存 Markdown 的标题顺序浏览源版本 {revision}。点击标题读取该版本原文；不会换成新版或生成摘要。</p>
    <div className="conversation-actions">
      <button disabled={!enabled || disabled || busy} onClick={() => { void read() }}>{entries === null ? '读取章节目录' : '重新读取目录'}</button>
      {busy && <button onClick={() => { stopped.current = true; setNotice('正在停止，等待读取结束…') }}>停止读取目录</button>}
    </div>
    <p role="status" aria-live="polite">{notice}</p>
    {error && <p role="alert" className="error">{error}</p>}
    {entries !== null && entries.length > 0 && <section aria-label="资料章节目录" aria-busy={busy}>
      <label>筛选目录标题<input value={query} maxLength={160} disabled={!enabled || disabled || busy}
        onChange={event => { setQuery(event.target.value); setPage(0) }} /></label>
      <p className="hint">标题按字面筛选，区分大小写；只筛选目录，不搜索正文。</p>
      {filtered.length === 0 ? <p>没有匹配的标题。</p> : <>
        <ol start={page * 20 + 1}>{filtered.slice(page * 20, (page + 1) * 20).map(entry => <li key={entry.offset}>
          <button disabled={!enabled || disabled || busy} onClick={() => { if (enabled && !disabled && !running.current) void open(entry.offset) }}>
            <bdi>{entry.heading || '（空标题）'}</bdi> · 第 {entry.line} 行
          </button>
        </li>)}</ol>
        <div className="pagination">
          <button disabled={!enabled || disabled || busy || page === 0} onClick={() => setPage(value => value - 1)}>上一页目录</button>
          <span>{page + 1} / {Math.ceil(filtered.length / 20)}</span>
          <button disabled={!enabled || disabled || busy || (page + 1) * 20 >= filtered.length} onClick={() => setPage(value => value + 1)}>下一页目录</button>
        </div>
      </>}
    </section>}
    <p className="hint">沿用资料解析时识别的 # 标题，不补造未识别的标题；长标题可能缩略。最多检查 128 个原文分段，超限或读取失败时不展示部分目录。离页或切换版本后清除目录。</p>
  </details>
}
