import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { SelectedMemory } from '../bridge/contracts'
import { isTitle } from '../bridge/conversations'
import type { ConversationDetail, ConversationList, ConversationStatus, FailureCode } from '../bridge/conversations'

const failureText: Record<FailureCode, string> = {
  EXECUTION_INTERRUPTED: 'Runtime 重启中断了执行。', PROVIDER_UNAVAILABLE: '本机 Ollama 不可用。', MODEL_UNAVAILABLE: '本机模型不可用。',
  QUEUE_FULL: '执行队列已满。', POLICY_DENIED: '执行策略拒绝了请求。', EXECUTION_FAILED: '执行失败。', STORAGE_UNAVAILABLE: '存储暂时不可用。',
}
const safeText = (problem: unknown) => problem instanceof BridgeError ? problem.message : '工作区暂时不可用，请刷新后检查。'
type Pending = { turnId: string; canCancel: boolean } | null

export function ConversationsPage({ bridge, enabled, visible }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean
}) {
  const [status, setStatus] = useState<ConversationStatus>('ACTIVE')
  const [pages, setPages] = useState({ ACTIVE: 0, ARCHIVED: 0 })
  const [list, setList] = useState<ConversationList | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [detail, setDetail] = useState<ConversationDetail | null>(null)
  const [pending, setPending] = useState<Pending>(null)
  const [draft, setDraft] = useState('')
  const [memories, setMemories] = useState<SelectedMemory[]>([])
  const [stale, setStale] = useState(false)
  const [title, setTitle] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [busy, setBusy] = useState(false)
  const [listLoading, setListLoading] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pollingError, setPollingError] = useState(false)
  const generation = useRef(0)
  const listGeneration = useRef(0)
  const detailQueue = useRef<Promise<void>>(Promise.resolve())
  const selectedRef = useRef<string | null>(null)
  const detailRef = useRef<ConversationDetail | null>(null)
  const statusRef = useRef(status); statusRef.current = status
  const pagesRef = useRef(pages); pagesRef.current = pages
  const composing = useRef(false)
  const actionLock = useRef(false)
  const discover = useRef(true)
  const editor = useRef<HTMLTextAreaElement>(null)
  const newButton = useRef<HTMLButtonElement>(null)
  const renameInput = useRef<HTMLInputElement>(null)
  const deleteDialog = useRef<HTMLDialogElement>(null)
  const cancelDelete = useRef<HTMLButtonElement>(null)
  const deleteButton = useRef<HTMLButtonElement>(null)
  const sendAllowed = enabled && !busy && !historyLoading && !pending && !pollingError && !stale && detail?.conversation.status === 'ACTIVE'
  const bytes = new TextEncoder().encode(draft).length
  const invalid = !draft.trim() || draft.length > 3000 || bytes > 5632

  const loadList = async (filter = statusRef.current, page = pagesRef.current[filter]) => {
    const token = ++listGeneration.current
    setListLoading(true)
    try {
      const next = await bridge.listConversations(filter, page)
      if (token !== listGeneration.current) return
      if (page > 0 && page * 10 >= next.total) { setPages(previous => ({ ...previous, [filter]: Math.max(0, Math.ceil(next.total / 10) - 1) })); return }
      setList(next)
      if (discover.current) {
        discover.current = false
        if (next.items[0] && !selectedRef.current) await chooseConversation(next.items[0].id)
      }
    } catch (problem) { if (token === listGeneration.current) { setList(null); setError(safeText(problem)) } }
    finally { if (token === listGeneration.current) setListLoading(false) }
  }
  // Serial detail requests prevent overlapping polls/navigation. Only the current
  // page and a pending identity are retained; a latest-page probe is never appended.
  const loadDetail = (id: string, page?: number, observe = false): Promise<void> => {
    const token = generation.current
    const work = async () => {
      if (token !== generation.current || selectedRef.current !== id) return
      if (!observe) setHistoryLoading(true)
      try {
        let next = await bridge.getConversation(id, page ?? (observe && detailRef.current ? Math.max(0, Math.ceil(detailRef.current.totalTurns / 10) - 1) : 0))
        if (token !== generation.current) return
        const last = Math.max(0, Math.ceil(next.totalTurns / 10) - 1)
        if (page === undefined && last !== next.page) next = await bridge.getConversation(id, last)
        if (token !== generation.current) return
        const previous = detailRef.current
        if (observe && previous && previous.page !== next.page) {
          const updated = { ...previous, conversation: next.conversation, totalTurns: next.totalTurns }
          detailRef.current = updated; setDetail(updated)
        } else { detailRef.current = next; setDetail(next) }
        // Older-page reads keep the latest pending identity; opening/refresh always
        // discovers latest durable truth, including after a new document/session.
        if (next.page === Math.max(0, Math.ceil(next.totalTurns / 10) - 1)) {
          const turn = next.turns.find(x => x.status === 'PENDING')
          setPending(turn ? { turnId: turn.turnId, canCancel: turn.canCancel } : null)
        }
        if (next.conversation.status === 'ARCHIVED') { setMemories([]); setStale(false) }
        setPollingError(false)
      } catch (problem) {
        if (token === generation.current) { setError(safeText(problem)); setPollingError(true) }
      } finally { if (token === generation.current) setHistoryLoading(false) }
    }
    const result = detailQueue.current.then(work)
    detailQueue.current = result.catch(() => {})
    return result
  }
  const chooseConversation = async (id: string | null) => {
    const old = selectedRef.current
    const token = ++generation.current
    selectedRef.current = id; detailRef.current = null; actionLock.current = false; composing.current = false
    setSelected(id); setDetail(null); setPending(null); setDraft(''); setMemories([]); setStale(false)
    setTitle(null); setDeleting(false); setBusy(false); setError(''); setNotice(''); setPollingError(false); setHistoryLoading(!!id)
    try {
      if (old) await bridge.clearConversationMemories(old)
      if (id) await bridge.clearConversationMemories(id)
    } catch (problem) { if (token === generation.current) setError(safeText(problem)) }
    if (token !== generation.current) return
    if (id) await loadDetail(id)
  }
  useEffect(() => {
    const reset = () => {
      generation.current++; listGeneration.current++; selectedRef.current = null; detailRef.current = null
      actionLock.current = false; setStatus('ACTIVE'); setPages({ ACTIVE: 0, ARCHIVED: 0 }); setList(null)
      discover.current = true
      setSelected(null); setDetail(null); setPending(null); setDraft(''); setMemories([]); setStale(false)
      setTitle(null); setDeleting(false); setBusy(false); setHistoryLoading(false); setError(''); setNotice(''); setPollingError(false)
      composing.current = false
      if (visible && bridge.ready) void loadList('ACTIVE', 0)
    }
    const unsubscribe = bridge.onSession(reset)
    return () => { generation.current++; listGeneration.current++; unsubscribe() }
  }, [bridge, visible])
  useEffect(() => { if (enabled && visible) { void loadList(); if (selectedRef.current) void loadDetail(selectedRef.current) } }, [enabled, visible, status, pages[status]])
  useEffect(() => {
    if (!visible || !pending || !selected || pollingError) return
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      await loadDetail(selected, undefined, true)
      if (!disposed) timer = setTimeout(() => { void poll() }, 550)
    }
    timer = setTimeout(() => { void poll() }, 550)
    return () => { disposed = true; clearTimeout(timer) }
  }, [visible, selected, pending?.turnId, pollingError])
  useEffect(() => {
    if (deleting && deleteDialog.current && !deleteDialog.current.open) { deleteDialog.current.showModal(); cancelDelete.current?.focus() }
    else if (!deleting && deleteDialog.current?.open) { deleteDialog.current.close(); deleteButton.current?.focus() }
  }, [deleting])
  useEffect(() => { if (title !== null) renameInput.current?.focus() }, [title === null])

  const run = async (work: (id: string, token: number) => Promise<void>) => {
    if (!selectedRef.current || actionLock.current) return
    const id = selectedRef.current, token = generation.current
    actionLock.current = true; setBusy(true); setError(''); setNotice('')
    try { await work(id, token) }
    catch (problem) { if (token === generation.current) setError(safeText(problem)) }
    finally { if (token === generation.current) { actionLock.current = false; setBusy(false); editor.current?.focus({ preventScroll: true }) } }
  }
  const send = () => {
    if (!sendAllowed || invalid || composing.current) return
    void run(async (id, token) => {
      setNotice('Submitting…')
      try {
        await bridge.sendConversation(id, draft, memories.map(({ memoryId, revision, position }) => ({ memoryId, revision, position })))
        if (token !== generation.current) return
        setDraft(''); setMemories([]); setStale(false); setNotice('已接受；正在读取持久历史。')
      } catch (problem) {
        if (token !== generation.current) return
        if (problem instanceof BridgeError && ['MemorySelectionStale', 'MEMORY_SELECTION_REQUIRED'].includes(problem.code)) setStale(true)
        if (problem instanceof BridgeError && !['MemorySelectionStale', 'MEMORY_SELECTION_REQUIRED'].includes(problem.code)) {
          setMemories([]); setStale(false)
          try { await bridge.clearConversationMemories(id) } catch { /* Host also consumes uncertain selections. */ }
        }
        setError(safeText(problem)); setNotice('草稿已保留；请检查持久历史，不会自动重发。')
      }
      if (token === generation.current) { await loadDetail(id); await loadList() }
    })
  }
  const create = async () => {
    if (actionLock.current) return
    const token = generation.current
    actionLock.current = true; setBusy(true); setError('')
    try {
      const created = await bridge.createConversation()
      if (token !== generation.current) return
      setStatus('ACTIVE'); setPages(previous => ({ ...previous, ACTIVE: 0 }))
      await chooseConversation(created.id); await loadList('ACTIVE', 0); editor.current?.focus()
    } catch (problem) { if (token === generation.current) setError(safeText(problem)) }
    finally { actionLock.current = false; setBusy(false) }
  }
  return <div className="conversations-layout">
    <section className="card conversation-list" aria-labelledby="conversation-list-title">
      <div className="card-heading"><h2 id="conversation-list-title">会话列表</h2><button ref={newButton} className="primary" disabled={!enabled || busy} onClick={() => { void create() }}>新建对话</button></div>
      <div className="conversation-tabs" role="tablist" aria-label="会话状态">{(['ACTIVE', 'ARCHIVED'] as const).map(filter => <button key={filter} role="tab" aria-selected={status === filter}
        id={`conversation-tab-${filter}`} aria-controls="conversation-list-panel" disabled={busy} onClick={() => {
          setStatus(filter); setList(null); void chooseConversation(null)
        }} onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
          const other = filter === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE'; setStatus(other); setList(null); void chooseConversation(null); document.getElementById(`conversation-tab-${other}`)?.focus()
        } }} tabIndex={status === filter ? 0 : -1}>{filter === 'ACTIVE' ? '使用中' : '已归档'}</button>)}</div>
      <div id="conversation-list-panel" role="tabpanel" aria-labelledby={`conversation-tab-${status}`} aria-busy={listLoading}>
        {listLoading && <p>正在加载列表…</p>}
        <ul>{list?.items.map(item => <li key={item.id}><button className="conversation-select" aria-pressed={item.id === selected} disabled={busy}
          onClick={() => { void chooseConversation(item.id) }}><bdi>{item.title}</bdi>{' '}<span>{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</span></button></li>)}</ul>
        {list?.total === 0 && <p>还没有对话。点击「新建对话」，开始连续交流。</p>}
      </div>
      <div className="pagination"><button disabled={!enabled || busy || listLoading || pages[status] === 0} onClick={() => setPages(previous => ({ ...previous, [status]: previous[status] - 1 }))}>上一页</button>
        <span>{pages[status] + 1} / {Math.max(1, Math.ceil((list?.total ?? 0) / 10))}</span>
        <button disabled={!enabled || busy || listLoading || !list || (pages[status] + 1) * 10 >= list.total} onClick={() => setPages(previous => ({ ...previous, [status]: previous[status] + 1 }))}>下一页</button></div>
    </section>
    <section className="card conversation-detail" aria-labelledby="conversation-detail-title" aria-busy={historyLoading}>
      <div className="card-heading"><h2 id="conversation-detail-title"><bdi>{detail?.conversation.title ?? '选择或新建会话'}</bdi></h2>
        <button disabled={!enabled || busy} onClick={() => { setError(''); void loadList(); if (selected) void loadDetail(selected) }}>刷新</button></div>
      {!detail && <p className="empty-state">从左侧选择对话，或新建一个对话。每次使用记忆都需要你明确选择。</p>}
      {detail && <>
        <div className="conversation-actions"><span>{detail.conversation.status === 'ACTIVE' ? '使用中' : '已归档'}</span>
          <button disabled={busy} onClick={() => setTitle(detail.conversation.title)}>重命名</button>
          <button disabled={busy} onClick={() => { void run(async (id, token) => {
            const value = detail.conversation.status === 'ACTIVE' ? await bridge.archiveConversation(id) : await bridge.unarchiveConversation(id)
            if (token !== generation.current) return
            setMemories([]); setStale(false); setStatus(value.status); setPages(previous => ({ ...previous, [value.status]: 0 }))
            await loadDetail(id); await loadList(value.status, 0)
          }) }}>{detail.conversation.status === 'ACTIVE' ? '归档' : '恢复'}</button>
          <button ref={deleteButton} className="destructive" disabled={busy || !!pending} onClick={() => setDeleting(true)}>删除</button>
        </div>
        {title !== null && <form className="conversation-rename" onSubmit={event => { event.preventDefault(); if (!isTitle(title)) return; void run(async (id, token) => {
          await bridge.renameConversation(id, title); if (token !== generation.current) return
          setTitle(null); await loadDetail(id, detail.page); await loadList()
        }) }}><label htmlFor="conversation-title">会话标题（最多 160 个 Unicode 字符）</label><input ref={renameInput} id="conversation-title" autoComplete="off" value={title} onChange={event => setTitle(event.target.value)} />
          <button type="submit" disabled={busy || !isTitle(title)}>保存标题</button><button type="button" disabled={busy} onClick={() => { setTitle(null); editor.current?.focus() }}>取消重命名</button></form>}
        <div className="pagination"><button disabled={busy || historyLoading || detail.page === 0} onClick={() => { void loadDetail(detail.conversation.id, detail.page - 1) }}>更早</button>
          <span>第 {detail.page + 1} 页 / {Math.max(1, Math.ceil(detail.totalTurns / 10))} · {detail.totalTurns} 轮</span>
          <button disabled={busy || historyLoading || (detail.page + 1) * 10 >= detail.totalTurns} onClick={() => { void loadDetail(detail.conversation.id, detail.page + 1) }}>更新</button></div>
        <div className="conversation-history" tabIndex={0} aria-label="持久会话历史">{detail.turns.map(turn => <article key={turn.turnId} className="conversation-turn">
          <h3>第 {turn.sequence} 轮 · {({ PENDING: '生成中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '超时' })[turn.status]}</h3><h4>你</h4><pre dir="auto">{turn.userMessage.content}</pre>
          {turn.assistantMessage && <><h4>助手</h4><pre dir="auto">{turn.assistantMessage.content}</pre></>}
          {turn.failureCode && <p>{turn.failureCode} · {failureText[turn.failureCode]}</p>}
          {turn.status === 'PENDING' && <p>正在本机执行…</p>}
          {turn.memoryReferences.length > 0 && <p>本轮曾使用记忆 × {turn.memoryReferences.length}</p>}
        </article>)}{detail.totalTurns === 0 && <p>发送第一条消息开始会话。</p>}</div>
        <div className="conversation-composer">
        <label htmlFor="conversation-input">会话消息</label><textarea id="conversation-input" ref={editor} value={draft} readOnly={busy || detail.conversation.status !== 'ACTIVE'} autoComplete="off" spellCheck={false}
          aria-describedby="conversation-input-help" onChange={event => setDraft(event.target.value)} onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
          onKeyDown={event => { if (event.key === 'Enter' && event.ctrlKey && !composing.current && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) { event.preventDefault(); send() } }} />
        <p id="conversation-input-help" className="hint">Enter 换行 · Ctrl+Enter 发送 · {draft.length} / 3000 字符</p>
        {draft.trim() && invalid && <p role="alert" className="error">内容过长，请缩短后再发送。原文不会被截断。</p>}
        <div className="conversation-composer-toolbar">
        <div className="memory-choice"><button disabled={!enabled || historyLoading || pollingError || busy || !!pending || detail.conversation.status !== 'ACTIVE'} onClick={() => { void run(async (id, token) => {
          const choice = await bridge.selectConversationMemories(id)
          if (token === generation.current && choice.changed) { setMemories(choice.selectedMemoryRefs); setStale(false) }
        }) }}>{memories.length ? '更换记忆…' : '选择记忆…'}</button>
          <button disabled={busy || !memories.length && !stale} onClick={() => { void run(async (id, token) => {
            await bridge.clearConversationMemories(id); if (token === generation.current) { setMemories([]); setStale(false) }
          }) }}>清除记忆</button><span>{memories.length ? `本次使用 ${memories.length} 条` : '仅使用你本次选择的记忆'}</span>
          {memories.length > 0 && <ol>{memories.map(memory => <li key={memory.memoryId}><bdi>{memory.title}</bdi> · 版本 {memory.revision}</li>)}</ol>}
          {stale && <p role="alert">记忆已变更，请重新选择或清除后再发送。</p>}</div>
        <div className="conversation-send"><button className="primary" disabled={!sendAllowed || invalid} onClick={send}>发送</button>
          <button disabled={!pending?.canCancel || busy} onClick={() => { void run(async (id, token) => {
            try { await bridge.cancelConversationPending(id, pending!.turnId); if (token === generation.current) setNotice('已请求取消；等待持久终态。') }
            finally { if (token === generation.current) await loadDetail(id) }
          }) }}>取消生成</button></div>
        </div>
        <details className="input-details"><summary>输入限制</summary><p className="hint">{draft.length} / 3000 UTF-16 · {bytes} / 5632 UTF-8 字节。拼音选词期间不会发送。</p></details>
        </div>
        <p className="conversation-execution" role="status" aria-live="polite">{pending ? '正在生成…' : ''}</p>
      </>}
      {error && <p className="error" role="alert">{error}</p>}<p role="status">{notice}</p>
    </section>
    <dialog ref={deleteDialog} className="conversation-delete" aria-labelledby="conversation-delete-title" onCancel={event => { event.preventDefault(); if (!busy) setDeleting(false) }}>
      <h2 id="conversation-delete-title">永久删除会话</h2><p><bdi>{detail?.conversation.title}</bdi></p><p>删除后无法从当前 Workspace 恢复。</p>
      <button ref={cancelDelete} disabled={busy} onClick={() => setDeleting(false)}>取消</button>
      <button className="destructive" disabled={busy} onClick={() => { void run(async (id, token) => {
        await bridge.deleteConversation(id); if (token !== generation.current) return
        selectedRef.current = null; await chooseConversation(null); await loadList(); newButton.current?.focus()
      }) }}>永久删除</button>
    </dialog>
  </div>
}
