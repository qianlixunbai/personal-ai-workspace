import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation, KnowledgeAnswerTask, KnowledgeAnswerHistoryPage } from '../bridge/knowledgeAnswer'
import { KnowledgeScopePicker } from './KnowledgeScopePicker'
import type { KnowledgeScope } from '../bridge/knowledgeScope'
import type { KnowledgeDocument } from '../bridge/knowledge'
import { AnswerContent } from './AnswerContent'
import { ConfirmationDialog } from './ConfirmationDialog'
import { KnowledgeAnswerExcerpt } from './KnowledgeAnswerExcerpt'
import { readAuthorizedAnswerHistory } from './memoryAnswerSource'
import { KnowledgeAnswerHistorySearch } from './KnowledgeAnswerHistorySearch'
import { KnowledgeResearchNotebook } from './KnowledgeResearchNotebook'
import { KnowledgeAnswerRecheck } from './KnowledgeAnswerRecheck'

export function KnowledgeAnswer({ bridge, enabled, visible, open, currentDocument, onMemoryDraft }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean; open: (citation: KnowledgeAnswerCitation) => Promise<void>; currentDocument?: KnowledgeDocument | undefined; onMemoryDraft?: ((content: string, role: 'ASSISTANT') => void) | undefined }) {
  const [scope, setScope] = useState<KnowledgeScope | null>(null), [scopeTitle, setScopeTitle] = useState('')
  const [question, setQuestion] = useState(''), [query, setQuery] = useState(''), [task, setTask] = useState<KnowledgeAnswerTask | null>(null)
  const [knownTaskId, setKnownTaskId] = useState<string | null>(null), [cancelRequested, setCancelRequested] = useState(false)
  const cancelledIds = useRef(new Set<string>())
  const [working, setBusy] = useState(false), [error, setError] = useState('')
  const [researchBusy, setResearchBusy] = useState(false), [recheckBlocked, setRecheckBlocked] = useState(false)
  const busy = working || researchBusy || recheckBlocked
  const [exportNotice, setExportNotice] = useState('')
  const [history, setHistory] = useState<KnowledgeAnswerHistoryPage | null>(null), [deleteId, setDeleteId] = useState<string | null>(null)
  const [historySearch, setHistorySearch] = useState(''), [historyQuery, setHistoryQuery] = useState<string | null>(null)
  const [pendingHistory, setPendingHistory] = useState<string | null>(null)
  const [confirmNew, setConfirmNew] = useState(false), [unknownSubmission, setUnknownSubmission] = useState(false)
  const [answerInput, setAnswerInput] = useState<{ question: string; query: string; scope: KnowledgeScope | null } | null>(null)
  const savedDraft = useRef<{ question: string; query: string; scope: KnowledgeScope | null }>({ question: '', query: '', scope: null })
  const generation = useRef(0), composing = useRef(false), actionLock = useRef(false)
  const active = task?.status === 'QUEUED' || task?.status === 'RUNNING' || !!knownTaskId && !task
  const dirty = question !== savedDraft.current.question || query !== savedDraft.current.query || scope?.documentId !== savedDraft.current.scope?.documentId || scope?.sourceRevision !== savedDraft.current.scope?.sourceRevision
  const clear = () => { generation.current++; composing.current = false; actionLock.current = false; cancelledIds.current.clear(); setKnownTaskId(null); setCancelRequested(false); savedDraft.current = { question: '', query: '', scope: null }; setPendingHistory(null); setConfirmNew(false); setUnknownSubmission(false); setAnswerInput(null); setQuestion(''); setQuery(''); setTask(null); setBusy(false); setError(''); setHistory(null); setDeleteId(null); setScope(null); setScopeTitle(''); setExportNotice('') }
  useEffect(() => { setExportNotice('') }, [task?.taskId])
  const checked = (result: KnowledgeAnswerTask, admittedScope = answerInput?.scope ?? null) => {
    if (admittedScope && result.result?.citations.some(c => c.documentId !== admittedScope.documentId || c.sourceRevision !== admittedScope.sourceRevision)) throw new Error('返回的引用超出了所选资料范围。')
    return result
  }
  useEffect(() => { const unsubscribe = bridge.onSession(() => { clear(); setHistorySearch(''); setHistoryQuery(null) }); return () => { generation.current++; unsubscribe() } }, [bridge])
  // Navigation pauses polling and transient confirmations, not the submitted task.
  // All state remains owned by this bridge session; only session replacement clears it.
  useEffect(() => { if (!visible) { composing.current = false; setPendingHistory(null); setConfirmNew(false); setDeleteId(null) } }, [visible])
  useEffect(() => {
    if (!visible || !enabled || !active || busy || error || !task) return
    const epoch = generation.current, id = task.taskId
    let disposed = false
    const timer = setTimeout(() => {
      void bridge.getKnowledgeAnswer(id).then(result => { if (!disposed && epoch === generation.current) setTask(checked(result)) })
        .catch(e => { if (!disposed && epoch === generation.current) setError(e instanceof Error ? e.message : '回答状态暂时不可用。') })
    }, 1000)
    return () => { disposed = true; clearTimeout(timer) }
  }, [bridge, visible, enabled, active, busy, error, task, answerInput])
  const submit = async () => {
    if (!enabled || !visible || actionLock.current || busy || active || unknownSubmission || composing.current || !question.trim() || !query.trim()) return
    const epoch = ++generation.current, input = { question, query, scope }
    actionLock.current = true; setBusy(true); setTask(null); setKnownTaskId(null); setCancelRequested(false); setAnswerInput(input); setError('')
    let accepted = false
    try {
      const result = await bridge.submitKnowledgeAnswer(question, query, scope)
      if (epoch === generation.current) {
        // The bridge has validated the receipt and ID. Preserve that identity even
        // when the result fails the additional explicit-scope check below.
        accepted = true; setKnownTaskId(result.taskId); savedDraft.current = input
        setTask(checked(result, input.scope))
      }
    }
    catch (e) { if (epoch === generation.current) { setUnknownSubmission(!accepted && (!(e instanceof BridgeError) || ['OutcomeUnknown', 'RuntimeUnavailable', 'ClientTimeout', 'InvalidResponse'].includes(e.code))); setError(e instanceof Error ? e.message : '基于知识回答暂时不可用。') } }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const cancel = async () => {
    const id = knownTaskId ?? task?.taskId
    if (!enabled || !visible || !id || !active || busy || actionLock.current || cancelledIds.current.has(id)) return
    const epoch = ++generation.current; actionLock.current = true; setBusy(true); setError('')
    cancelledIds.current.add(id); setCancelRequested(true)
    try { const result = await bridge.cancelKnowledgeAnswer(id); if (epoch === generation.current) setTask(checked(result)) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '取消状态暂时无法确认。') }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const checkTask = async () => {
    const id = knownTaskId ?? task?.taskId
    if (!enabled || !visible || !id || !active || actionLock.current) return
    const epoch = ++generation.current; actionLock.current = true; setBusy(true); setError('')
    try { const result = await bridge.getKnowledgeAnswer(id); if (epoch === generation.current) setTask(checked(result)) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '回答状态仍无法确认，请稍后主动核对。') }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const newQuestion = () => {
    if (!enabled || !visible || actionLock.current || active || unknownSubmission) return
    generation.current++; savedDraft.current = { question: '', query: '', scope: null }
    setQuestion(''); setQuery(''); setScope(null); setScopeTitle(''); setTask(null); setKnownTaskId(null); setCancelRequested(false); setAnswerInput(null); setError(''); setExportNotice(''); setConfirmNew(false)
  }
  const historyPage = async (page: number, search: string | null) => search === null ? bridge.knowledgeAnswerHistory(page) : (await bridge.searchKnowledgeAnswers(search, page)).results
  const loadHistory = async (page = 0, search: string | null = historyQuery) => {
    if (!enabled || !visible || busy || active || actionLock.current) return
    const epoch = ++generation.current; actionLock.current = true; setBusy(true); setError(''); setDeleteId(null); setHistory(null)
    try {
      let result = await historyPage(page, search)
      if (epoch !== generation.current) return
      if (page > 0 && !result.items.length) result = await historyPage(Math.max(0, Math.ceil(result.total / 20) - 1), search)
      if (epoch === generation.current) { setHistory(result); setHistoryQuery(search) }
    }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '历史记录暂时不可用。') }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const readHistory = async (id: string) => {
    if (!enabled || !visible || busy || active || actionLock.current) return
    const epoch = ++generation.current; actionLock.current = true; setBusy(true); setError(''); setDeleteId(null)
    try { const result = await readAuthorizedAnswerHistory(bridge, id, () => { if (epoch !== generation.current) throw new Error('问答读取已失效') }); if (epoch === generation.current) { savedDraft.current = { question: result.question, query: result.query, scope: result.scope ?? null }; setAnswerInput(savedDraft.current); setQuestion(result.question); setQuery(result.query); setTask(result.task); setKnownTaskId(id); setCancelRequested(cancelledIds.current.has(id)); setScope(result.scope ?? null); setScopeTitle(result.task.result?.citations[0]?.title ?? '') } }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '读取历史回答失败。') }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const removeHistory = async () => {
    if (!deleteId || !enabled || !visible || busy || active || actionLock.current) return
    const id = deleteId, epoch = ++generation.current; actionLock.current = true; setBusy(true); setError('')
    try {
      // Reading a source may have replaced the native history-page grant since
      // these search rows were shown. Reacquire only the confirmed record.
      await readAuthorizedAnswerHistory(bridge, id, () => { if (epoch !== generation.current) throw new Error('删除核对已失效') })
      if (epoch !== generation.current) return
      await bridge.deleteKnowledgeAnswerHistory(id); if (epoch !== generation.current) return; setDeleteId(null); if (task?.taskId === id) { setTask(null); setKnownTaskId(null); setCancelRequested(false); setAnswerInput(null); savedDraft.current = { question: '', query: '', scope: null }; if (!dirty) { setQuestion(''); setQuery(''); setScope(null); setScopeTitle('') } }; setHistory(null); const result = await historyPage(0, historyQuery); if (epoch === generation.current) setHistory(result)
    }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '删除结果暂时无法确认，请刷新历史记录。') }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  const draftMemory = () => {
    if (!enabled || !visible || busy || task?.status !== 'SUCCEEDED' || !task.result) return
    // Use the validated answer, never unsent question edits or rendered Markdown.
    onMemoryDraft?.(task.result.answer, 'ASSISTANT')
  }
  const exportAnswer = async () => {
    if (!enabled || !visible || busy || task?.status !== 'SUCCEEDED' || actionLock.current) return
    const epoch = ++generation.current; actionLock.current = true; setBusy(true); setError(''); setExportNotice('')
    try { const result = await bridge.exportKnowledgeAnswer(task.taskId); if (epoch === generation.current) setExportNotice(result.outcome === 'SAVED' ? '已保存问答笔记，包含引用原文。' : '已取消导出。') }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error && 'code' in e && e.code === 'OutcomeUnknown' ? '导出结果暂时无法确认，请先检查保存位置和原生窗口；不会自动重试。' : `${e instanceof Error ? e.message : '导出未完成。'} 请核对引用来源，并选择新的 .md 文件；已有文件不会被覆盖。`) }
    finally { if (epoch === generation.current) { actionLock.current = false; setBusy(false) } }
  }
  return <section className="card knowledge-answer" aria-labelledby="knowledge-answer-title">
    <h2 id="knowledge-answer-title">基于文档回答</h2>
    <KnowledgeScopePicker scope={scope} title={scopeTitle} current={currentDocument} disabled={!enabled || busy || active} change={(next, title) => { setScope(next); setScopeTitle(title) }} />
    <p className="hint">输入问题和关键词，从匹配的可用文档中寻找答案，并附上引用来源。首个长片段可能改用相关句子及相邻上下文；预算不足时会拒绝回答。关键词不会自动改写。</p>
    <p className="hint">问题、回答和引用保存在本机当前资料配置中，重启后可查看，最多 1000 条。现有备份不包含问答历史；删除源文档后，其历史引用可能无法打开。历史不会自动用于新问题或重新执行。</p>
    <button disabled={!enabled || busy || active} onClick={() => { void loadHistory(0, null) }}>查看问答历史</button>
    <KnowledgeAnswerHistorySearch id="knowledge-history-query" value={historySearch} change={setHistorySearch} disabled={!enabled || !visible || busy || active} run={value => { void loadHistory(0, value) }} />
    <button disabled={!enabled || busy || active || unknownSubmission} onClick={() => { if (question || query || scope || task) setConfirmNew(true); else newQuestion() }}>开始新问题</button>
    <p className="hint">切换工作区页面会保留当前问题、范围和回答；返回后继续核对处理中任务。关闭应用或连接会话重建不会保留未提交草稿。离开页面不表示任务已取消。</p>
    {unknownSubmission && <p role="alert">本次提交结果未确认，本页已阻止重复提交。可查看历史记录并到设置检查运行状态；相同问题的旧记录不能证明本次任务已完成。</p>}
    {confirmNew && <ConfirmationDialog title="开始新问题？" confirmText="清空当前问题" cancel={() => setConfirmNew(false)} confirm={newQuestion}>
      <p>清空当前问题、关键词、资料范围和页面中的回答。已经保存的问答历史保留，不会提交或重新生成回答。</p>
    </ConfirmationDialog>}
    {history && <section aria-label="问答历史"><p>{historyQuery !== null ? `匹配“${historyQuery}” · ` : ''}共 {history.total} 条，第 {history.page + 1} 页</p>
      {history.items.length === 0 && <p>{historyQuery !== null ? '没有匹配的问答记录' : '暂无问答记录'}</p>}
      <ul>{history.items.map(row => <li key={row.taskId}>
        <button disabled={!enabled || busy || active} onClick={() => { if (dirty) setPendingHistory(row.taskId); else void readHistory(row.taskId) }}>{row.question}</button>
        <span> · {new Date(row.createdAt).toLocaleString()} · {({ QUEUED: '尚未完成', SUCCEEDED: '已完成', FAILED: '未完成', CANCELLED: '已取消', TIMED_OUT: '已超时' } as Record<string, string>)[row.status]}</span>
        <button aria-label={`删除记录：${row.question}`} disabled={!enabled || busy || active || row.status === 'QUEUED'} onClick={() => setDeleteId(row.taskId)}>删除记录</button>
      </li>)}</ul>
      <button disabled={!enabled || busy || active || history.page === 0} onClick={() => { void loadHistory(history.page - 1) }}>上一页问答</button>
      <button disabled={!enabled || busy || active || (history.page + 1) * history.limit >= history.total} onClick={() => { void loadHistory(history.page + 1) }}>下一页问答</button>
      {deleteId && <div role="group" aria-label="确认删除问答"><p>删除这条问题、回答和引用记录？源文档会保留。</p><button disabled={!enabled || busy || active} onClick={() => { void removeHistory() }}>确认删除问答</button><button disabled={busy} onClick={() => setDeleteId(null)}>保留记录</button></div>}
    </section>}
    <KnowledgeResearchNotebook bridge={bridge} rows={history?.items ?? []} enabled={enabled && !working && !active && !recheckBlocked} visible={visible} onBusy={setResearchBusy} />
    <KnowledgeAnswerRecheck bridge={bridge} taskId={task?.status === 'SUCCEEDED' ? task.taskId : null}
      enabled={enabled && !working && !researchBusy && !active && !unknownSubmission} visible={visible} open={open} onBlocked={setRecheckBlocked} />
    {pendingHistory && <ConfirmationDialog title="替换尚未提交的问题草稿？" confirmText="读取历史并替换草稿" cancel={() => setPendingHistory(null)} confirm={() => { const id = pendingHistory; setPendingHistory(null); void readHistory(id) }}>
      <p>当前问题、检索关键词或资料范围有未提交的修改。读取历史成功后会替换这些内容；读取失败仍保留草稿。不会提交问题或重新生成回答。</p>
    </ConfirmationDialog>}
    <form onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }} onKeyDown={e => { if (e.key === 'Enter' && (e.nativeEvent.isComposing || composing.current)) e.preventDefault() }} onSubmit={e => { e.preventDefault(); void submit() }}>
      <label htmlFor="knowledge-answer-question">想了解什么</label>
      <textarea id="knowledge-answer-question" value={question} maxLength={3000} disabled={!enabled || busy || active} autoComplete="off" spellCheck={false} onChange={e => setQuestion(e.target.value)} />
      <label htmlFor="knowledge-answer-query">回答检索关键词</label>
      <input id="knowledge-answer-query" value={query} disabled={!enabled || busy || active} autoComplete="off" spellCheck={false} onChange={e => setQuery(e.target.value)} />
      <button type="submit" className="primary" disabled={!enabled || busy || active || unknownSubmission || !question.trim() || !query.trim()}>基于知识回答</button>
    </form>
    <p role="status" aria-live="polite">{busy ? '正在处理…' : task?.status === 'QUEUED' ? '回答任务已排队' : task?.status === 'RUNNING' ? '正在生成回答' : task?.status === 'SUCCEEDED' ? '回答完成' : task?.status === 'CANCELLED' ? '回答已取消' : task?.status === 'TIMED_OUT' ? '回答超时' : task?.status === 'FAILED' ? '回答失败' : ''}</p>
    {knownTaskId && <p className="hint">原回答任务 ID：{knownTaskId}{!task ? ' · 返回内容待核对，未采用回答或引用。' : ''}</p>}
    {active && <button disabled={!enabled || busy || cancelRequested} onClick={() => { void cancel() }}>取消知识回答</button>}
    {active && cancelRequested && <p className="hint">已请求取消，不会重复发送。取消请求不代表任务已结束，请核对原任务状态。</p>}
    {active && (error || cancelRequested) && <button disabled={!enabled || busy} onClick={() => { void checkTask() }}>重新核对回答状态</button>}
    {(error || task?.error) && <p role="alert" className="error">{error || task?.error?.message}</p>}
    {(task || knownTaskId) && answerInput && <section aria-label="当前回答对应的问题"><h3>当前回答对应的问题</h3><p dir="auto">{answerInput.question}</p>
      <p>检索关键词：{answerInput.query}</p><p className="hint">回答资料范围：{answerInput.scope ? `${answerInput.scope.documentId} · 源版本 ${answerInput.scope.sourceRevision}` : '全部可用资料'}。上方草稿修改不会改变这份回答。</p></section>}
    {task?.result && <><AnswerContent key={task.taskId} text={task.result.answer} />
      <button disabled={!enabled || busy} onClick={() => { void exportAnswer() }}>导出问答笔记…</button>
      <p className="hint">保存原问题、回答和引用原文为明文 Markdown。以已保存的问答为准，不包含编辑框中的后续修改；仅保存到新文件。这不是完整资料备份。</p>
      {exportNotice && <p role="status">{exportNotice}</p>}
      {onMemoryDraft && task.status === 'SUCCEEDED' && <div>
        <KnowledgeAnswerExcerpt bridge={bridge} task={task} enabled={enabled && visible && !busy} onDraft={onMemoryDraft} />
        <button disabled={!enabled || !visible || busy} onClick={draftMemory}>整理为记忆草稿</button>
        <p className="hint">仅将回答原文送入记忆编辑器，不会自动保存或调用模型。回答未经核实，请先核对引用来源；引用链接不会随草稿保存，后续来源变化也不会自动更新记忆。</p>
      </div>}
      <h3>引用来源</h3>
      <p className="hint">以下范围为实际引用的原文片段，不一定是完整文档。请打开来源核对适用条件；源预览可能显示额外上下文。</p>
      <ol>{task.result.citations.map(c => <li key={`${c.documentId}/${c.sourceRevision}/${c.startOffset}`}><h4>{c.title}</h4>
        <p className="knowledge-location">{c.sourceType} · 源版本 {c.sourceRevision} · 行 {c.startLine}–{c.endLine} · 文本偏移 {c.startOffset}–{c.endOffset}{c.heading ? ` · ${c.heading}` : ''}</p>
        <button disabled={!enabled || busy} onClick={() => { void open(c) }}>打开引用来源</button>
      </li>)}</ol></>}
  </section>
}
