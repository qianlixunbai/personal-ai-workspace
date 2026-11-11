import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import { emptyMemory, validContent, validDraft, validQuery } from '../bridge/memory'
import type { MemoryDraft, MemoryDraftSource, MemoryItem, MemoryList, MemoryMetadata, MemoryQuery, MemoryStatus, MemoryType } from '../bridge/memory'
import { ConfirmationDialog } from '../components/ConfirmationDialog'
import { MemoryPreview } from '../components/MemoryPreview'
import { MemoryCandidatePanel } from '../components/MemoryCandidatePanel'
import { MemoryDraftDuplicateCheck } from '../components/MemoryDraftDuplicateCheck'
import { MemorySourceReview } from '../components/MemorySourceReview'
import { MemoryUsageReview } from '../components/MemoryUsageReview'
import { MemoryMergeReview } from '../components/MemoryMergeReview'

export type MemoryLeaveGuard = (leave: () => void) => void
export type MemoryDraftReceiver = (draft: MemoryDraft, accepted: () => void, role: MemoryDraftSource) => boolean
const message = (problem: unknown) => problem instanceof BridgeError ? problem.message : 'Memory 暂时不可用，请显式刷新或重新加载。'
type Confirmation = { title: string; text: string; confirmText: string; action: () => void } | null
export function MemoryPage({ bridge, enabled, visible, registerLeave, registerDraft, openBackup }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean; registerLeave: (guard: MemoryLeaveGuard) => () => void
  registerDraft?: (receive: MemoryDraftReceiver) => () => void
  openBackup: () => void
}) {
  const [query, setQuery] = useState<MemoryQuery>({ query: '', status: 'ACTIVE', type: null, page: 0 })
  const [search, setSearch] = useState('')
  const [list, setList] = useState<MemoryList | null>(null)
  const [relatedSearch, setRelatedSearch] = useState(false)
  const relatedRef = useRef(false)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const [loaded, setLoaded] = useState<MemoryItem | null>(null)
  const [draft, setDraft] = useState<MemoryDraft>(emptyMemory)
  const draftRef = useRef(draft); draftRef.current = draft
  const [fromSource, setFromSource] = useState<MemoryDraftSource | null>(null)
  const [candidateEpoch, setCandidateEpoch] = useState(0)
  const [revisionReview, setRevisionReview] = useState<{ draft: MemoryDraft; target: MemoryItem | null } | null>(null)
  const [currentReview, setCurrentReview] = useState<MemoryItem | null>(null)
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
  const revisionGeneration = useRef(0)
  const actionLock = useRef(false)
  const queried = useRef(false)
  const queryRef = useRef(query); queryRef.current = query
  const editor = useRef<HTMLInputElement>(null)
  const newButton = useRef<HTMLButtonElement>(null)
  const focusNext = useRef<'editor' | 'new' | null>(null)
  const signalQueue = useRef<Promise<unknown>>(Promise.resolve())
  const signalGeneration = useRef(0)
  const signalledDirty = useRef(false)
  const baseline = loaded ?? emptyMemory()
  const dirty = draft.type !== baseline.type || draft.title !== baseline.title || draft.content !== baseline.content
  const blocked = stale || missing || unknown
  useEffect(() => {
    if (visible && !busy && !confirmation && focusNext.current) {
      (focusNext.current === 'editor' ? editor.current : newButton.current)?.focus(); focusNext.current = null
    }
  }, [busy, confirmation, visible, revisionReview])
  const loadEditor = (item: MemoryItem | null) => {
    revisionGeneration.current++; setRevisionReview(null); setCurrentReview(null)
    setCandidateEpoch(value => value + 1)
    setPreviewId(null)
    setLoaded(item); setDraft(item ? { type: item.type, title: item.title, content: item.content } : emptyMemory())
    setStale(false); setMissing(false); setUnknown(false); setError(''); setFromSource(null)
  }
  useEffect(() => {
    if (!bridge.ready || dirty === signalledDirty.current) return
    signalledDirty.current = dirty
    const token = generation.current
    // Keep native notifications ordered; only the latest one may report its failure.
    const signal = ++signalGeneration.current
    signalQueue.current = signalQueue.current.catch(() => {}).then(() => {
      if (token !== generation.current) return
      return bridge.memoryEditorState(dirty)
    }).catch(() => { if (token === generation.current && signal === signalGeneration.current) setError('原生未保存状态保护未能确认。请保持窗口打开并显式 Save 或丢弃修改。') })
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
  useEffect(() => registerDraft?.((incoming, accepted, role) => {
    if (!enabled || actionLock.current || confirmation) return false
    const token = generation.current
    requestDiscard(() => {
      if (token !== generation.current) return
      loadEditor(null); setDraft(incoming); setFromSource(role); setNotice(''); focusNext.current = 'editor'
      accepted()
    })
    return true
  }), [registerDraft, enabled, dirty, confirmation, busy])
  const fetchList = async (view = queryRef.current, related = relatedRef.current) => {
    setPreviewId(null)
    const token = ++listGeneration.current
    related = related && view.status === 'ACTIVE'
    if (related) view = { ...view, page: 0 }
    relatedRef.current = related; setRelatedSearch(related)
    queryRef.current = view; setQuery(view)
    setListLoading(true)
    try {
      let result: MemoryList = related ? { ...await bridge.recallMemory(view.query, view.type), page: 0 } : await bridge.listMemory(view)
      if (token !== listGeneration.current) return false
      if (result.page > 0 && result.page * 20 >= result.total) {
        view = { ...view, page: Math.max(0, Math.ceil(result.total / 20) - 1) }
        result = await bridge.listMemory(view)
        if (token !== listGeneration.current) return false
      }
      queryRef.current = view; setQuery(view); setList(result)
      return true
    } catch (problem) {
      if (token !== listGeneration.current) return false
      setList(null); throw problem
    }
    finally { if (token === listGeneration.current) setListLoading(false) }
  }
  const refresh = async (view = queryRef.current, related = relatedRef.current) => {
    const token = generation.current
    setError('')
    try { if (await fetchList(view, related) && token === generation.current) setNotice('列表已刷新；编辑器中的修改保持原样。') }
    catch (problem) { if (token === generation.current) setError(message(problem)) }
  }
  useEffect(() => bridge.onSession(() => {
    generation.current++; listGeneration.current++; actionLock.current = false; queried.current = false; signalledDirty.current = false
    setBusy(false); setListLoading(false); setList(null); loadEditor(null); setSearch(''); setConfirmation(null); setNotice('')
    relatedRef.current = false; setRelatedSearch(false)
    queryRef.current = { query: '', status: 'ACTIVE', type: null, page: 0 }; setQuery(queryRef.current)
  }), [bridge])
  useEffect(() => {
    if (enabled && visible && !queried.current) { queried.current = true; void refresh() }
  }, [enabled, visible])
  useEffect(() => { if (!enabled || !visible) setPreviewId(null) }, [enabled, visible])
  useEffect(() => {
    if (!enabled || !visible) { revisionGeneration.current++; setRevisionReview(null); setCurrentReview(null) }
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
  const chooseRevisionTarget = (metadata: MemoryMetadata) => {
    if (!revisionReview || actionLock.current || blocked || metadata.status !== 'ACTIVE') return
    const token = ++revisionGeneration.current, session = generation.current
    setRevisionReview(previous => previous ? { ...previous, target: null } : null)
    void run(async () => {
      let item: MemoryItem
      try { item = await bridge.getMemory(metadata.id) }
      catch (problem) { if (token === revisionGeneration.current && session === generation.current) throw problem; return }
      if (token !== revisionGeneration.current || session !== generation.current) return
      if (item.id !== metadata.id || item.revision !== metadata.revision || item.status !== 'ACTIVE') {
        setError('修订目标已变更或归档，请刷新列表后重新选择。草稿保持原样。'); return
      }
      setRevisionReview(previous => previous ? { ...previous, target: item } : null)
      setNotice('请对照目标旧内容与下方草稿，再采用修订；此时尚未写入。')
    }, false, false)
  }
  const beginRevision = () => {
    if (!enabled || !visible || actionLock.current || confirmation || loaded || fromSource || blocked || !validDraft(draft)) return
    revisionGeneration.current++; setPreviewId(null); setError(''); setNotice('')
    setRevisionReview({ draft: { ...draft }, target: null })
    if (queryRef.current.status !== 'ACTIVE') void refresh({ ...queryRef.current, status: 'ACTIVE', page: 0 })
  }
  const adoptRevision = () => {
    if (!enabled || !visible || actionLock.current || confirmation || blocked || !revisionReview?.target) return
    const { target, draft: replacement } = revisionReview
    loadEditor(target); setDraft(replacement)
    setNotice('已将草稿用于修订所选记忆，尚未保存。点击保存才会更新这条记忆，不会另建一条。')
    focusNext.current = 'editor'
  }
  const recoverDraftAsNew = () => {
    if (!enabled || !visible || actionLock.current || confirmation || !loaded || !missing || unknown) return
    const token = generation.current
    setConfirmation({ title: '保留草稿，另存为新记忆？', confirmText: '保留为新草稿',
      text: '保留当前类型、标题和正文，改为新记忆草稿。此操作不会写入；之后点击保存将创建新记录，不会恢复或覆盖原记录。', action: () => {
        if (token !== generation.current || actionLock.current) return
        const retained = { ...draft }
        loadEditor(null); setDraft(retained)
        setNotice('已保留为新记忆草稿，尚未保存。请检查内容，再点击保存创建新记录；原记录不会恢复。')
        focusNext.current = 'editor'
      } })
  }
  const reviewCurrent = () => {
    if (!enabled || !visible || actionLock.current || confirmation || !loaded) return
    const token = ++revisionGeneration.current, session = generation.current
    setCurrentReview(null)
    void run(async () => {
      let item: MemoryItem
      try { item = await bridge.getMemory(loaded.id) }
      catch (problem) { if (token === revisionGeneration.current && session === generation.current) throw problem; return }
      if (token !== revisionGeneration.current || session !== generation.current) return
      setCurrentReview(item)
      setNotice('已读取当前保存内容，草稿保持原样。请将需要保留的内容手工合并到下方草稿，再确认继续编辑。')
    })
  }
  const continueReviewed = () => {
    if (!enabled || !visible || actionLock.current || confirmation || unknown || missing || !loaded
      || !currentReview || loaded.id !== currentReview.id || currentReview.status !== 'ACTIVE') return
    const expected = currentReview, token = revisionGeneration.current, session = generation.current
    setConfirmation({ title: '保留草稿，以此版本继续编辑？', confirmText: '保留草稿并继续编辑',
      text: '请先将要保留的最新内容合并到草稿。确认仅更新编辑所依据的版本，不会写入；之后点击保存将用当前草稿更新同一条记忆。', action: () => {
        if (token !== revisionGeneration.current || session !== generation.current || actionLock.current) return
        void run(async () => {
          setCurrentReview(null)
          let latest: MemoryItem
          try { latest = await bridge.getMemory(expected.id) }
          catch (problem) { if (token === revisionGeneration.current && session === generation.current) throw problem; return }
          if (token !== revisionGeneration.current || session !== generation.current) return
          setCurrentReview(latest)
          if (Object.entries(expected).some(([key, value]) => latest[key as keyof MemoryItem] !== value)) {
            setNotice('这条记忆已变更，请重新对照最新内容再确认。草稿保持原样。'); return
          }
          setLoaded(latest); setStale(false)
          setNotice('已保留草稿并采用核对过的版本，尚未保存。点击保存才会更新同一条记忆。')
          focusNext.current = 'editor'
        })
      } })
  }
  const applyMergedDraft = (merged: MemoryDraft) => {
    if (!enabled || !visible || actionLock.current || confirmation || unknown || missing || !loaded || !validDraft(merged)
      || !currentReview || loaded.id !== currentReview.id || loaded.createdAt !== currentReview.createdAt || currentReview.status !== 'ACTIVE') return
    const expected = currentReview, original = draft, token = revisionGeneration.current, session = generation.current
    void run(async () => {
      let latest: MemoryItem
      try { latest = await bridge.getMemory(expected.id) }
      catch (problem) { if (token === revisionGeneration.current && session === generation.current) throw problem; return }
      if (token !== revisionGeneration.current || session !== generation.current) return
      if (Object.entries(original).some(([key, value]) => draftRef.current[key as keyof MemoryDraft] !== value)) {
        setNotice('草稿已继续编辑，未采用旧合并结果。请重新准备合并。'); return
      }
      setCurrentReview(latest)
      if (Object.entries(expected).some(([key, value]) => latest[key as keyof MemoryItem] !== value)) {
        setNotice('已存内容再次变化，未采用合并结果。请根据最新版本重新准备，草稿保持原样。'); return
      }
      setLoaded(latest); setDraft(merged); setStale(false); setCurrentReview(null)
      setNotice('已采用合并草稿，尚未保存。请核对内容，再点击保存更新同一条记忆。')
      focusNext.current = 'editor'
    })
  }
  const save = () => { if (revisionReview || blocked || !dirty || !validDraft(draft)) return; void run(async () => {
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
  const searchNow = (related = false) => {
    if (!validQuery(search)) { setError('搜索文字必须是有效 Unicode，最多 160 个 Unicode 字符。'); return }
    if (related && !search.trim()) { setError('请输入问题或关键词，再查找相关记忆。'); return }
    void refresh({ ...queryRef.current, query: search, page: 0 }, related)
  }
  const disabled = !enabled || busy
  return <div className="memory-page">
    <div className="card-heading memory-toolbar"><p className="hint">手动保存 · 明确选择 · 无自动记忆</p><div>
      <button className="secondary" disabled={disabled} onClick={openBackup}>备份与恢复…</button>{' '}
    </div></div>
    <div className="memory-layout">
      <section className="card memory-list" aria-labelledby="memory-list-title"><div className="card-heading"><h2 id="memory-list-title">我的记忆</h2><button type="button" disabled={disabled || listLoading} onClick={() => { void refresh() }}>刷新</button></div>
        <form onSubmit={event => { event.preventDefault(); if (!disabled && !listLoading) searchNow(relatedRef.current) }}>
          <label className="sr-only" htmlFor="memory-search">搜索记忆</label><input id="memory-search" placeholder="搜索标题和正文" aria-describedby="memory-search-help" value={search} disabled={disabled} onChange={event => setSearch(event.target.value)} autoComplete="off"
            onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault() }} />
          <div className="operation-actions"><button type="button" disabled={disabled || listLoading} onClick={() => searchNow()}>搜索</button>
            <button type="button" disabled={disabled || listLoading || query.status !== 'ACTIVE' || !search.trim()} onClick={() => searchNow(true)}>查找相关记忆</button></div>
        </form>
        <div className="memory-filters"><label htmlFor="memory-status">状态<select id="memory-status" value={query.status} disabled={disabled || listLoading || !!revisionReview} onChange={event => { void refresh({ ...queryRef.current, status: event.target.value as MemoryStatus, page: 0 }) }}>
          <option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option></select></label>
          <label htmlFor="memory-filter-type">类型<select id="memory-filter-type" value={query.type ?? 'ALL'} disabled={disabled || listLoading} onChange={event => { void refresh({ ...queryRef.current, type: event.target.value === 'ALL' ? null : event.target.value as MemoryType, page: 0 }) }}>
            <option value="ALL">全部</option><option value="PREFERENCE">个人偏好</option><option value="PROJECT_NOTE">项目笔记</option></select></label></div>
        <p id="memory-search-help" className="hint">{relatedSearch
          ? '相关查找：仅使用中记录，按词语匹配排序，并非语义判断；不会自动选用记忆。最多显示前 20 条，请细化关键词或筛选类型。'
          : '搜索标题和正文，匹配完整原文片段，区分大小写。相关查找只用于使用中的记忆。'}</p>
        <ul tabIndex={0} aria-label="Memory list" aria-busy={listLoading}>{list?.items.map(item => <li key={item.id}><button id={`memory-select-${item.id}`} className="memory-select" aria-label={`${item.title} ${item.type} ${item.status} Revision ${item.revision} ${item.updatedAt}`} aria-pressed={loaded?.id === item.id || revisionReview?.target?.id === item.id} disabled={disabled || listLoading || !!revisionReview && item.status !== 'ACTIVE'}
          onClick={() => { if (revisionReview) chooseRevisionTarget(item); else if (loaded?.id !== item.id) requestDiscard(() => get(item.id)) }}>{item.title}<span>{item.type === 'PREFERENCE' ? '个人偏好' : '项目笔记'} · 版本 {item.revision}</span><span>{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</span></button>
          <button type="button" className="memory-preview-toggle" disabled={disabled || listLoading} aria-expanded={previewId === item.id}
            aria-describedby={`memory-select-${item.id}`} aria-controls={previewId === item.id ? `memory-preview-${item.id}` : undefined}
            onClick={() => setPreviewId(current => current === item.id ? null : item.id)}>{previewId === item.id ? '收起预览' : '预览正文'}</button>
          {previewId === item.id && visible && enabled && !listLoading && <MemoryPreview bridge={bridge} metadata={item} query={relatedSearch ? '' : query.query} />}
        </li>)}</ul>
        {!listLoading && list?.total === 0 && <p className="empty-state">{relatedSearch ? '没有词语匹配，可换关键词或用原文搜索；不代表没有相关记忆。' : '没有找到记忆。可在右侧手动保存偏好或项目笔记。'}</p>}
        {relatedSearch ? <p aria-live="polite">相关结果 {list?.items.length ?? 0} / {list?.total ?? 0}{!listLoading && list && list.total > 20 && ' · 仅显示前 20 条'}</p>
        : <div className="pagination" aria-label="Memory pagination"><button disabled={disabled || listLoading || !list || list.page === 0} onClick={() => { void refresh({ ...queryRef.current, page: query.page - 1 }) }}>上一页</button>
          <span>{query.page + 1} / {Math.max(1, Math.ceil((list?.total ?? 0) / 20))} · {list?.total ?? 0}</span>
          <button disabled={disabled || listLoading || !list || (list.page + 1) * 20 >= list.total} onClick={() => { void refresh({ ...queryRef.current, page: query.page + 1 }) }}>下一页</button></div>}
      </section>
      <section className="card memory-editor" aria-labelledby="memory-editor-title"><div className="card-heading"><h2 id="memory-editor-title">{loaded ? '编辑记忆' : revisionReview ? '修订已有记忆' : '新记忆'}</h2><button ref={newButton} disabled={disabled || !!revisionReview} onClick={() => requestDiscard(() => { loadEditor(null); setNotice('点击保存后才会创建记忆。'); editor.current?.focus() })}>新建</button></div>
        {fromSource === 'KNOWLEDGE' ? <p className="hint">已填入你选中的资料原文和出处，尚未保存。请填写标题、核对并编辑内容，再点击保存。出处是可编辑的文字记录，可以主动按记录回查，但不会自动同步资料更新或建立受信引用关联；此内容仍需你核实。</p>
          : fromSource && <p className="hint">已从来源填入草稿；尚未保存，请检查内容。助手回复可能不准确，请审阅后再保存。来源角色仅用于本次草稿审阅；保存后标记为由你确认保存（MANUAL）。正文中的出处只是可编辑定位文字，不建立受信的会话、问答或引用关联。</p>}
        {fromSource && fromSource !== 'KNOWLEDGE' && !loaded && <MemoryCandidatePanel key={candidateEpoch} bridge={bridge} enabled={enabled && visible && !busy && !confirmation && !blocked} draft={draft} role={fromSource}
          apply={candidate => { if (blocked || actionLock.current) return; loadEditor(null); setDraft(candidate); setNotice('已采用模型候选，尚未保存。请检查内容后点击保存。' + (fromSource === 'ASSISTANT' ? '来源为助手回复，未经核实。' : '')); editor.current?.focus() }} />}
        {!loaded && !fromSource && !revisionReview && <div className="operation-actions">
          <button disabled={disabled || blocked || listLoading || !validDraft(draft)} onClick={beginRevision}>用此草稿修订已有记忆</button>
        </div>}
        {revisionReview && <section className="memory-revision-review" aria-label="修订目标审核">
          <p>从左侧选择一条使用中的记忆。当前草稿将完整替换它的类型、标题和正文，请对照旧内容后确认。</p>
          {revisionReview.target && <>
            <h3>目标旧内容：<bdi>{revisionReview.target.title}</bdi></h3>
            <p>{revisionReview.target.type === 'PREFERENCE' ? '个人偏好' : '项目笔记'} · 版本 {revisionReview.target.revision}</p>
            <pre aria-label="修订目标旧正文" dir="auto">{revisionReview.target.content}</pre>
          </>}
          <p className="hint">下方为待采用的新内容。采用仅准备编辑草稿，仍需点击“保存”才更新原记录。不会自动合并；保存后不保留可恢复的旧正文。</p>
          <div className="operation-actions"><button disabled={disabled || blocked || !revisionReview.target} onClick={adoptRevision}>采用修订到编辑器</button>
            <button disabled={disabled} onClick={() => { revisionGeneration.current++; setRevisionReview(null); setError(''); setNotice('已取消选择修订目标，原草稿保留。'); focusNext.current = 'editor' }}>取消选择修订目标</button></div>
        </section>}
        <p className="memory-state" aria-live="polite">{loaded ? `${loaded.status === 'ACTIVE' ? '使用中' : '已归档'} · 版本 ${loaded.revision}` : '尚未保存'}{dirty && ' · 未保存'}{stale && ' · 已变更，请核对最新内容'}{missing && ' · 已不存在'}{unknown && ' · 操作结果待确认'}</p>
        {loaded && <p className="hint">保存方式：由你确认保存（MANUAL），不代表内容已经核实。草稿来源角色不会持久保存；正文中的出处仅为可编辑定位文字，不建立受信的会话、问答或引用关联。</p>}
        {stale && <p className="error">Memory 已被其他客户端修改。可“对照最新”，准备三方合并或手工合并后继续编辑；重新加载会丢弃修改。</p>}
        {missing && <p className="error">原记录当前不存在。草稿保持原样。{unknown ? '此前的写入结果仍待确认，不能据此另存。' : '可选择“保留草稿，另存为新记忆”，再点击保存创建新记录；原记录不会恢复。'}</p>}
        {unknown && <p className="error">无法确认操作是否已提交。不会自动重试。请刷新检查列表，再重新加载；新建结果不明时请先检查，再新建。</p>}
        {currentReview && <section className="memory-revision-review" aria-label="当前记忆最新内容对照">
          <h3>已存标题：<bdi>{currentReview.title}</bdi></h3>
          <p>{currentReview.type === 'PREFERENCE' ? '个人偏好' : '项目笔记'} · {currentReview.status === 'ACTIVE' ? '使用中' : '已归档'} · 版本 {currentReview.revision}</p>
          <pre aria-label="当前记忆已存正文" dir="auto">{currentReview.content}</pre>
          <p className="hint">下方草稿仍可编辑。请手工合并需要保留的内容；对照不会自动替换或保存草稿。{currentReview.status === 'ARCHIVED' && '已归档记录仅供对照，不能采用为继续编辑的版本。'}</p>
          <button disabled={disabled || unknown || missing || currentReview.status !== 'ACTIVE'} onClick={continueReviewed}>保留草稿，以此版本继续编辑</button>
          {loaded && loaded.id === currentReview.id && loaded.createdAt === currentReview.createdAt && currentReview.status === 'ACTIVE' && <MemoryMergeReview
            base={loaded} draft={draft} latest={currentReview} enabled={enabled && visible && !busy && !confirmation && !unknown && !missing} apply={applyMergedDraft} />}
        </section>}
        <div className="memory-fields"><div><label htmlFor="memory-editor-type">记忆类型</label><select id="memory-editor-type" value={draft.type} disabled={disabled || !!revisionReview} onChange={event => setDraft(previous => ({ ...previous, type: event.target.value as MemoryType }))}>
          <option value="PREFERENCE">个人偏好</option><option value="PROJECT_NOTE">项目笔记</option></select></div>
          <div><label htmlFor="memory-title">记忆标题</label><input ref={editor} id="memory-title" value={draft.title} disabled={disabled || !!revisionReview} autoComplete="off" onChange={event => setDraft(previous => ({ ...previous, title: event.target.value }))} />
            <p className="hint">{[...draft.title].length} / 160 字符</p></div></div>
        <label htmlFor="memory-content">记忆内容</label><textarea id="memory-content" value={draft.content} readOnly={disabled || !!revisionReview} autoComplete="off" spellCheck={false} onChange={event => setDraft(previous => ({ ...previous, content: event.target.value }))} />
        <p className="hint">{[...draft.content].length} / 2000 字符 · 手动保存</p>
        <MemorySourceReview key={candidateEpoch} bridge={bridge} content={draft.content} enabled={enabled && visible && !busy && !confirmation && !revisionReview} />
        {loaded && <MemoryUsageReview key={`${loaded.id}:${loaded.revision}`} bridge={bridge} memory={loaded} enabled={enabled && visible && !busy && !confirmation && !revisionReview} />}
        {fromSource && !validContent(draft.content) && <p role="alert" className="error">来源原文已完整保留，但不符合 Memory 正文限制（2000 UTF-16 单位、8192 UTF-8 字节及有效 Unicode，且不能为空）。请修改至合法内容后保存。</p>}
        {!loaded && !revisionReview && validContent(draft.content) && <MemoryDraftDuplicateCheck key={`draft-duplicates-${candidateEpoch}`} bridge={bridge}
          enabled={enabled && visible && !busy && !confirmation && !blocked} content={draft.content}
          openMemory={id => requestDiscard(() => get(id))} />}
        <div className="operation-actions"><button className="primary" disabled={disabled || !!revisionReview || blocked || !dirty || !validDraft(draft)} onClick={save}>保存</button>
          <button disabled={disabled || blocked || !loaded} onClick={lifecycle}>{loaded?.status === 'ARCHIVED' ? '恢复' : '归档'}</button>
          <button className="destructive" disabled={disabled || blocked || !loaded} onClick={remove}>删除</button>
          <button disabled={disabled || !loaded || missing} onClick={() => { if (loaded) requestDiscard(() => get(loaded.id), true) }}>重新加载</button>
          {loaded && <button disabled={disabled} onClick={reviewCurrent}>对照最新</button>}
          {loaded && missing && !unknown && <button disabled={disabled} onClick={recoverDraftAsNew}>保留草稿，另存为新记忆</button>}</div>
        <details className="input-details"><summary>输入限制</summary><p className="hint">正文 {draft.content.length} / 2000 UTF-16 单位 · {new TextEncoder().encode(draft.content).length} / 8192 UTF-8 字节。不自动保存。</p></details>
        {dirty && !validDraft(draft) && <p className="hint">标题和正文不能为空，并须符合以上 Unicode 与长度限制；原文不会被截断。</p>}
        {loaded && <details className="input-details"><summary>记录详情</summary><dl className="memory-metadata"><div><dt>ID</dt><dd>{loaded.id}</dd></div><div><dt>创建时间</dt><dd>{loaded.createdAt}</dd></div><div><dt>更新时间</dt><dd>{loaded.updatedAt}</dd></div></dl></details>}
      </section>
    </div>{error && <p role="alert" className="error">{error}</p>}<p role="status">{notice}</p>
    {confirmation && createPortal(<ConfirmationDialog title={confirmation.title} confirmText={confirmation.confirmText} cancel={() => setConfirmation(null)} confirm={() => { const action = confirmation.action; setConfirmation(null); action() }}><p>{confirmation.text}</p></ConfirmationDialog>, document.body)}
  </div>
}
