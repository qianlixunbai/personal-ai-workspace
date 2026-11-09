import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation, KnowledgeAnswerTask, KnowledgeAnswerHistoryPage } from '../bridge/knowledgeAnswer'
import { KnowledgeScopePicker } from './KnowledgeScopePicker'
import type { KnowledgeScope } from '../bridge/knowledgeScope'
import type { KnowledgeDocument } from '../bridge/knowledge'
import { AnswerContent } from './AnswerContent'

export function KnowledgeAnswer({ bridge, enabled, visible, open, currentDocument, onMemoryDraft }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean; open: (citation: KnowledgeAnswerCitation) => Promise<void>; currentDocument?: KnowledgeDocument | undefined; onMemoryDraft?: ((content: string, role: 'ASSISTANT') => void) | undefined }) {
  const [scope, setScope] = useState<KnowledgeScope | null>(null), [scopeTitle, setScopeTitle] = useState('')
  const [question, setQuestion] = useState(''), [query, setQuery] = useState(''), [task, setTask] = useState<KnowledgeAnswerTask | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [exportNotice, setExportNotice] = useState('')
  const [history, setHistory] = useState<KnowledgeAnswerHistoryPage | null>(null), [deleteId, setDeleteId] = useState<string | null>(null)
  const generation = useRef(0), composing = useRef(false)
  const active = task?.status === 'QUEUED' || task?.status === 'RUNNING'
  const clear = () => { generation.current++; composing.current = false; setQuestion(''); setQuery(''); setTask(null); setBusy(false); setError(''); setHistory(null); setDeleteId(null); setScope(null); setScopeTitle(''); setExportNotice('') }
  useEffect(() => { setExportNotice('') }, [task?.taskId])
  const checked = (result: KnowledgeAnswerTask) => {
    if (scope && result.result?.citations.some(c => c.documentId !== scope.documentId || c.sourceRevision !== scope.sourceRevision)) throw new Error('返回的引用超出了所选资料范围。')
    return result
  }
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  useEffect(() => { if (!visible) clear() }, [visible])
  useEffect(() => {
    if (!visible || !enabled || !active || busy || error || !task) return
    const epoch = generation.current, id = task.taskId
    const timer = setTimeout(() => {
      void bridge.getKnowledgeAnswer(id).then(result => { if (epoch === generation.current) setTask(checked(result)) })
        .catch(e => { if (epoch === generation.current) setError(e instanceof Error ? e.message : '回答状态暂时不可用。') })
    }, 1000)
    return () => clearTimeout(timer)
  }, [bridge, visible, enabled, active, busy, error, task, scope])
  const submit = async () => {
    if (!enabled || !visible || busy || active || composing.current || !question.trim() || !query.trim()) return
    const epoch = ++generation.current; setBusy(true); setTask(null); setError('')
    try { const result = await bridge.submitKnowledgeAnswer(question, query, scope); if (epoch === generation.current) setTask(checked(result)) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '基于知识回答暂时不可用。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const cancel = async () => {
    if (!task || !active || busy) return
    const epoch = ++generation.current; setBusy(true); setError('')
    try { const result = await bridge.cancelKnowledgeAnswer(task.taskId); if (epoch === generation.current) setTask(checked(result)) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '取消状态暂时无法确认。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const loadHistory = async (page = 0) => {
    if (!enabled || !visible || busy || active) return
    const epoch = ++generation.current; setBusy(true); setError(''); setDeleteId(null)
    try { const result = await bridge.knowledgeAnswerHistory(page); if (epoch === generation.current) setHistory(result) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '历史记录暂时不可用。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const readHistory = async (id: string) => {
    if (!enabled || !visible || busy || active) return
    const epoch = ++generation.current; setBusy(true); setError(''); setDeleteId(null)
    try { const result = await bridge.getKnowledgeAnswerHistory(id); if (epoch === generation.current) { setQuestion(result.question); setQuery(result.query); setTask(result.task); setScope(result.scope ?? null); setScopeTitle(result.task.result?.citations[0]?.title ?? '') } }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '读取历史回答失败。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const removeHistory = async () => {
    if (!deleteId || !enabled || !visible || busy || active) return
    const id = deleteId, epoch = ++generation.current; setBusy(true); setError('')
    try { await bridge.deleteKnowledgeAnswerHistory(id); if (epoch !== generation.current) return; setDeleteId(null); if (task?.taskId === id) { setTask(null); setQuestion(''); setQuery('') }; const result = await bridge.knowledgeAnswerHistory(0); if (epoch === generation.current) setHistory(result) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '删除结果暂时无法确认，请刷新历史记录。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const draftMemory = () => {
    if (!enabled || !visible || busy || task?.status !== 'SUCCEEDED' || !task.result) return
    // Use the validated answer, never unsent question edits or rendered Markdown.
    onMemoryDraft?.(task.result.answer, 'ASSISTANT')
  }
  const exportAnswer = async () => {
    if (!enabled || !visible || busy || task?.status !== 'SUCCEEDED') return
    const epoch = ++generation.current; setBusy(true); setError(''); setExportNotice('')
    try { const result = await bridge.exportKnowledgeAnswer(task.taskId); if (epoch === generation.current) setExportNotice(result.outcome === 'SAVED' ? '已保存问答笔记，包含引用原文。' : '已取消导出。') }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error && 'code' in e && e.code === 'OutcomeUnknown' ? '导出结果暂时无法确认，请先检查保存位置和原生窗口；不会自动重试。' : `${e instanceof Error ? e.message : '导出未完成。'} 请核对引用来源，并选择新的 .md 文件；已有文件不会被覆盖。`) }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  return <section className="card knowledge-answer" aria-labelledby="knowledge-answer-title">
    <h2 id="knowledge-answer-title">基于文档回答</h2>
    <KnowledgeScopePicker scope={scope} title={scopeTitle} current={currentDocument} disabled={!enabled || busy || active} change={(next, title) => { generation.current++; setScope(next); setScopeTitle(title); setTask(null); setError('') }} />
    <p className="hint">输入问题和关键词，从匹配的可用文档中寻找答案，并附上引用来源。首个长片段可能改用相关句子及相邻上下文；预算不足时会拒绝回答。关键词不会自动改写。</p>
    <p className="hint">问题、回答和引用保存在本机当前资料配置中，重启后可查看，最多 1000 条。现有备份不包含问答历史；删除源文档后，其历史引用可能无法打开。历史不会自动用于新问题或重新执行。</p>
    <button disabled={!enabled || busy || active} onClick={() => { void loadHistory() }}>查看问答历史</button>
    {history && <section aria-label="问答历史"><p>共 {history.total} 条，第 {history.page + 1} 页</p>
      {history.items.length === 0 && <p>暂无问答记录</p>}
      <ul>{history.items.map(row => <li key={row.taskId}>
        <button disabled={!enabled || busy || active} onClick={() => { void readHistory(row.taskId) }}>{row.question}</button>
        <span> · {new Date(row.createdAt).toLocaleString()} · {({ QUEUED: '尚未完成', SUCCEEDED: '已完成', FAILED: '未完成', CANCELLED: '已取消', TIMED_OUT: '已超时' } as Record<string, string>)[row.status]}</span>
        <button aria-label={`删除记录：${row.question}`} disabled={!enabled || busy || active || row.status === 'QUEUED'} onClick={() => setDeleteId(row.taskId)}>删除记录</button>
      </li>)}</ul>
      <button disabled={!enabled || busy || active || history.page === 0} onClick={() => { void loadHistory(history.page - 1) }}>上一页问答</button>
      <button disabled={!enabled || busy || active || (history.page + 1) * history.limit >= history.total} onClick={() => { void loadHistory(history.page + 1) }}>下一页问答</button>
      {deleteId && <div role="group" aria-label="确认删除问答"><p>删除这条问题、回答和引用记录？源文档会保留。</p><button disabled={!enabled || busy || active} onClick={() => { void removeHistory() }}>确认删除问答</button><button disabled={busy} onClick={() => setDeleteId(null)}>保留记录</button></div>}
    </section>}
    <form onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }} onKeyDown={e => { if (e.key === 'Enter' && (e.nativeEvent.isComposing || composing.current)) e.preventDefault() }} onSubmit={e => { e.preventDefault(); void submit() }}>
      <label htmlFor="knowledge-answer-question">想了解什么</label>
      <textarea id="knowledge-answer-question" value={question} maxLength={3000} disabled={!enabled || busy || active} autoComplete="off" spellCheck={false} onChange={e => setQuestion(e.target.value)} />
      <label htmlFor="knowledge-answer-query">回答检索关键词</label>
      <input id="knowledge-answer-query" value={query} disabled={!enabled || busy || active} autoComplete="off" spellCheck={false} onChange={e => setQuery(e.target.value)} />
      <button type="submit" className="primary" disabled={!enabled || busy || active || !question.trim() || !query.trim()}>基于知识回答</button>
    </form>
    <p role="status" aria-live="polite">{busy ? '正在处理…' : task?.status === 'QUEUED' ? '回答任务已排队' : task?.status === 'RUNNING' ? '正在生成回答' : task?.status === 'SUCCEEDED' ? '回答完成' : task?.status === 'CANCELLED' ? '回答已取消' : task?.status === 'TIMED_OUT' ? '回答超时' : task?.status === 'FAILED' ? '回答失败' : ''}</p>
    {active && <button disabled={!enabled || busy} onClick={() => { void cancel() }}>取消知识回答</button>}
    {(error || task?.error) && <p role="alert" className="error">{error || task?.error?.message}</p>}
    {task?.result && <><AnswerContent key={task.taskId} text={task.result.answer} />
      <button disabled={!enabled || busy} onClick={() => { void exportAnswer() }}>导出问答笔记…</button>
      <p className="hint">保存原问题、回答和引用原文为明文 Markdown。以已保存的问答为准，不包含编辑框中的后续修改；仅保存到新文件。这不是完整资料备份。</p>
      {exportNotice && <p role="status">{exportNotice}</p>}
      {onMemoryDraft && task.status === 'SUCCEEDED' && <div>
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
