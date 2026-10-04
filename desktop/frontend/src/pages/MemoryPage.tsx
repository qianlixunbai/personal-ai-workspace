import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import { emptyMemory, validDraft, validQuery } from '../bridge/memory'
import type { MemoryDraft, MemoryItem, MemoryList, MemoryQuery, MemoryStatus, MemoryType } from '../bridge/memory'
import { ConfirmationDialog } from '../components/ConfirmationDialog'

export type MemoryLeaveGuard = (leave: () => void) => void
const message = (problem: unknown) => problem instanceof BridgeError ? problem.message : 'Memory 暂时不可用，请显式刷新或重新加载。'
type Confirmation = { title: string; text: string; confirmText: string; action: () => void } | null
export function MemoryPage({ bridge, enabled, visible, registerLeave, openBackup }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean; registerLeave: (guard: MemoryLeaveGuard) => () => void
  openBackup: () => void
}) {
  const [query, setQuery] = useState<MemoryQuery>({ query: '', status: 'ACTIVE', type: null, page: 0 })
  const [search, setSearch] = useState('')
  const [list, setList] = useState<MemoryList | null>(null)
  const [loaded, setLoaded] = useState<MemoryItem | null>(null)
  const [draft, setDraft] = useState<MemoryDraft>(emptyMemory)
  const [stale, setStale] = useState(false)
  const [missing, setMissing] = useState(false)
  const [unknown, setUnknown] = useState(false)
  const [busy, setBusy] = useState(false)
  const [listLoading, setListLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmation, setConfirmation] = useState<Confirmation>(null)
  const generation = useRef(0)
  const listGeneration = useRef(0)
  const actionLock = useRef(false)
  const queried = useRef(false)
  const queryRef = useRef(query); queryRef.current = query
  const editor = useRef<HTMLInputElement>(null)
  const newButton = useRef<HTMLButtonElement>(null)
  const focusNext = useRef<'editor' | 'new' | null>(null)
  const signalQueue = useRef<Promise<unknown>>(Promise.resolve())
  const signalledDirty = useRef(false)
  const baseline = loaded ?? emptyMemory()
  const dirty = draft.type !== baseline.type || draft.title !== baseline.title || draft.content !== baseline.content
  const blocked = stale || missing || unknown
  useEffect(() => {
    if (!busy && !confirmation && focusNext.current) {
      (focusNext.current === 'editor' ? editor.current : newButton.current)?.focus(); focusNext.current = null
    }
  }, [busy, confirmation])
  const loadEditor = (item: MemoryItem | null) => {
    setLoaded(item); setDraft(item ? { type: item.type, title: item.title, content: item.content } : emptyMemory())
    setStale(false); setMissing(false); setUnknown(false); setError('')
  }
  useEffect(() => {
    if (!bridge.ready || dirty === signalledDirty.current) return
    signalledDirty.current = dirty
    const token = generation.current
    signalQueue.current = signalQueue.current.catch(() => {}).then(() => {
      if (token !== generation.current) return
      return bridge.memoryEditorState(dirty)
    }).catch(() => { if (token === generation.current) setError('原生未保存状态保护未能确认。请保持窗口打开并显式 Save 或丢弃修改。') })
  }, [bridge, dirty])
  const requestDiscard = (action: () => void, reload = false) => {
    if (actionLock.current || confirmation) return
    if (!dirty) { action(); return }
    setConfirmation({ title: reload ? '重新加载 Memory？' : '丢弃未保存的修改？',
      text: reload ? 'Reload 将丢弃本地未保存的修改，并读取 Runtime 最新版本。' : '继续将丢弃本地未保存的修改。', confirmText: '丢弃并继续', action })
  }
  useEffect(() => registerLeave(leave => requestDiscard(() => {
    loadEditor(null); setNotice('已丢弃本地修改。'); leave()
  })), [registerLeave, dirty, confirmation, busy])
  const fetchList = async (view = queryRef.current) => {
    const token = ++listGeneration.current
    setListLoading(true)
    try {
      let result = await bridge.listMemory(view)
      if (token !== listGeneration.current) return
      if (result.page > 0 && result.page * 20 >= result.total) {
        view = { ...view, page: Math.max(0, Math.ceil(result.total / 20) - 1) }
        result = await bridge.listMemory(view)
        if (token !== listGeneration.current) return
      }
      queryRef.current = view; setQuery(view); setList(result)
    } catch (problem) { if (token === listGeneration.current) setList(null); throw problem }
    finally { if (token === listGeneration.current) setListLoading(false) }
  }
  const refresh = async (view = queryRef.current) => {
    const token = generation.current
    setError('')
    try { await fetchList(view); if (token === generation.current) setNotice('列表已刷新；编辑器中的修改保持原样。') }
    catch (problem) { if (token === generation.current) setError(message(problem)) }
  }
  useEffect(() => bridge.onSession(() => {
    generation.current++; listGeneration.current++; actionLock.current = false; queried.current = false; signalledDirty.current = false
    setBusy(false); setListLoading(false); setList(null); loadEditor(null); setSearch(''); setConfirmation(null); setNotice('')
    queryRef.current = { query: '', status: 'ACTIVE', type: null, page: 0 }; setQuery(queryRef.current)
  }), [bridge])
  useEffect(() => {
    if (enabled && visible && !queried.current) { queried.current = true; void refresh() }
  }, [enabled, visible])
  useEffect(() => () => { generation.current++; listGeneration.current++ }, [])
  const run = async (operation: () => Promise<void>, mutation = false, affectsLoaded = true) => {
    if (actionLock.current || !enabled) return
    actionLock.current = true; setBusy(true); setError(''); setNotice('')
    const token = generation.current
    try { await operation() }
    catch (problem) {
      if (token !== generation.current) return
      if (problem instanceof BridgeError) {
        if (affectsLoaded && problem.code === 'MemoryRevisionConflict') setStale(true)
        if (affectsLoaded && problem.code === 'MemoryNotFound') setMissing(true)
        if (mutation && ['OutcomeUnknown', 'ClientTimeout', 'RuntimeUnavailable', 'InvalidResponse'].includes(problem.code)) setUnknown(true)
      }
      setError(message(problem))
    } finally { if (token === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const refreshAfterMutation = async () => {
    const token = generation.current
    try { await fetchList() }
    catch (problem) { if (token === generation.current) setError('操作已成功。列表刷新失败：' + message(problem)) }
  }
  const get = (id: string) => { void run(async () => {
    const token = generation.current
    const item = await bridge.getMemory(id)
    if (token !== generation.current) return
    loadEditor(item); setNotice('已加载 Runtime 保存的 Memory。'); focusNext.current = 'editor'
  }, false, id === loaded?.id) }
  const save = () => { if (blocked || !dirty || !validDraft(draft)) return; void run(async () => {
    const token = generation.current
    const item = loaded ? await bridge.updateMemory(loaded.id, loaded.revision, draft) : await bridge.createMemory(draft)
    if (token !== generation.current) return
    loadEditor(item); setNotice('Memory 已保存。'); await refreshAfterMutation(); focusNext.current = 'editor'
  }, true) }
  const lifecycle = () => { if (!loaded || blocked) return; void run(async () => {
    const token = generation.current
    const item = loaded.status === 'ACTIVE' ? await bridge.archiveMemory(loaded.id, loaded.revision) : await bridge.restoreMemory(loaded.id, loaded.revision)
    if (token !== generation.current) return
    if (dirty) setLoaded(item); else loadEditor(item)
    setNotice('Memory 状态已更新。' + (dirty ? '本地修改仍未保存。' : '')); await refreshAfterMutation(); focusNext.current = 'editor'
  }, true) }
  const remove = () => { if (!loaded || blocked || actionLock.current) return
    setConfirmation({ title: '永久删除 Memory？', confirmText: '永久删除', text: '此 Memory 将不再出现在后续搜索或选择中。当前 Workspace 内删除不可撤销；不保证磁盘取证级擦除。' + (dirty ? '未保存的修改也将丢弃。' : ''), action: () => { void run(async () => {
      const token = generation.current
      await bridge.deleteMemory(loaded.id, loaded.revision)
      if (token !== generation.current) return
      loadEditor(null); setNotice('Memory 已删除。'); await refreshAfterMutation(); focusNext.current = 'new'
    }, true) } })
  }
  const searchNow = () => {
    if (!validQuery(search)) { setError('搜索文字必须是有效 Unicode，最多 160 个 Unicode 字符。'); return }
    void refresh({ ...queryRef.current, query: search, page: 0 })
  }
  const disabled = !enabled || busy
  return <div className="memory-page">
    <div className="card-heading memory-toolbar"><p className="hint">手动保存 · 明确选择 · 无自动记忆</p><div>
      <button className="secondary" disabled={disabled} onClick={openBackup}>Memory Backup / Restore</button>{' '}
    </div></div>
    <div className="memory-layout">
      <section className="card memory-list" aria-labelledby="memory-list-title"><h2 id="memory-list-title">Memory 列表</h2>
        <form onSubmit={event => { event.preventDefault(); if (!disabled && !listLoading) searchNow() }}>
          <label htmlFor="memory-search">搜索 Memory</label><input id="memory-search" value={search} disabled={disabled} onChange={event => setSearch(event.target.value)} autoComplete="off"
            onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault() }} />
          <div className="operation-actions"><button disabled={disabled || listLoading}>Search</button><button type="button" disabled={disabled || listLoading} onClick={() => { void refresh() }}>Refresh</button></div>
        </form><p className="hint">区分大小写的字面子串搜索，匹配标题和正文。点击 Search 或按 Enter 执行。</p>
        <div className="memory-filters"><label htmlFor="memory-status">Status</label><select id="memory-status" value={query.status} disabled={disabled || listLoading} onChange={event => { void refresh({ ...queryRef.current, status: event.target.value as MemoryStatus, page: 0 }) }}>
          <option>ACTIVE</option><option>ARCHIVED</option></select>
          <label htmlFor="memory-filter-type">Type filter</label><select id="memory-filter-type" value={query.type ?? 'ALL'} disabled={disabled || listLoading} onChange={event => { void refresh({ ...queryRef.current, type: event.target.value === 'ALL' ? null : event.target.value as MemoryType, page: 0 }) }}>
            <option>ALL</option><option>PREFERENCE</option><option>PROJECT_NOTE</option></select></div>
        <ul aria-label="Memory list" aria-busy={listLoading}>{list?.items.map(item => <li key={item.id}><button className="memory-select" aria-label={`${item.title} ${item.type} ${item.status} Revision ${item.revision} ${item.updatedAt}`} aria-pressed={loaded?.id === item.id} disabled={disabled || listLoading}
          onClick={() => { if (loaded?.id !== item.id) requestDiscard(() => get(item.id)) }}>{item.title}<span>{item.type} · {item.status} · Revision {item.revision}</span><span>{item.updatedAt}</span></button></li>)}</ul>
        {!listLoading && list?.total === 0 && <p>当前条件下没有 Memory。</p>}
        <div className="pagination" aria-label="Memory pagination"><button disabled={disabled || listLoading || !list || list.page === 0} onClick={() => { void refresh({ ...queryRef.current, page: query.page - 1 }) }}>Previous</button>
          <span>Page {query.page + 1} / {Math.max(1, Math.ceil((list?.total ?? 0) / 20))} · {list?.total ?? 0}</span>
          <button disabled={disabled || listLoading || !list || (list.page + 1) * 20 >= list.total} onClick={() => { void refresh({ ...queryRef.current, page: query.page + 1 }) }}>Next</button></div>
      </section>
      <section className="card memory-editor" aria-labelledby="memory-editor-title"><div className="card-heading"><h2 id="memory-editor-title">{loaded ? 'Memory editor' : 'New Memory'}</h2><button ref={newButton} disabled={disabled} onClick={() => requestDiscard(() => { loadEditor(null); setNotice('New · 点击 Save 才会创建。'); editor.current?.focus() })}>New</button></div>
        <p className="memory-state" aria-live="polite">{loaded ? `${loaded.status} · MANUAL · Revision ${loaded.revision}` : 'New · 尚未保存'}{dirty && ' · 未保存'}{stale && ' · stale：需重新加载'}{missing && ' · missing：已不存在'}{unknown && ' · outcome unknown：需显式检查'}</p>
        {stale && <p className="error">Memory 已被其他客户端修改。草稿保持原样；请显式 Reload 或丢弃后选择其他条目。</p>}
        {missing && <p className="error">Memory 已不存在。草稿保持原样；不会自动重新创建，请显式 New 或选择其他条目。</p>}
        {unknown && <p className="error">无法确认操作是否已提交。不会自动重试。请 Refresh 检查列表，再 Reload；新建结果不明时请先检查，再显式 New。</p>}
        <label htmlFor="memory-editor-type">Memory type</label><select id="memory-editor-type" value={draft.type} disabled={disabled} onChange={event => setDraft(previous => ({ ...previous, type: event.target.value as MemoryType }))}>
          <option>PREFERENCE</option><option>PROJECT_NOTE</option></select>
        <label htmlFor="memory-title">Memory title</label><input ref={editor} id="memory-title" value={draft.title} disabled={disabled} autoComplete="off" onChange={event => setDraft(previous => ({ ...previous, title: event.target.value }))} />
        <p className="hint">{[...draft.title].length} / 160 Unicode 字符</p>
        <label htmlFor="memory-content">Memory content</label><textarea id="memory-content" value={draft.content} readOnly={disabled} autoComplete="off" spellCheck={false} onChange={event => setDraft(previous => ({ ...previous, content: event.target.value }))} />
        <p className="hint">{[...draft.content].length} / 2000 Unicode 字符 · {draft.content.length} / 2000 UTF-16 单位 · {new TextEncoder().encode(draft.content).length} / 8192 UTF-8 字节。仅显式 Save，不自动保存。</p>
        {dirty && !validDraft(draft) && <p className="hint">标题和正文不能为空，并须符合以上 Unicode 与长度限制；原文不会被截断。</p>}
        {loaded && <dl className="memory-metadata"><div><dt>ID</dt><dd>{loaded.id}</dd></div><div><dt>Created</dt><dd>{loaded.createdAt}</dd></div><div><dt>Updated</dt><dd>{loaded.updatedAt}</dd></div></dl>}
        <div className="operation-actions"><button className="primary" disabled={disabled || blocked || !dirty || !validDraft(draft)} onClick={save}>Save</button>
          <button disabled={disabled || blocked || !loaded} onClick={lifecycle}>{loaded?.status === 'ARCHIVED' ? 'Restore' : 'Archive'}</button>
          <button className="destructive" disabled={disabled || blocked || !loaded} onClick={remove}>Delete</button>
          <button disabled={disabled || !loaded || missing} onClick={() => { if (loaded) requestDiscard(() => get(loaded.id), true) }}>Reload</button></div>
      </section>
    </div>{error && <p role="alert" className="error">{error}</p>}<p role="status">{notice}</p>
    {confirmation && <ConfirmationDialog title={confirmation.title} confirmText={confirmation.confirmText} cancel={() => setConfirmation(null)} confirm={() => { const action = confirmation.action; setConfirmation(null); action() }}><p>{confirmation.text}</p></ConfirmationDialog>}
  </div>
}
