import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation, KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'

export function KnowledgeAnswer({ bridge, enabled, visible, open }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean; open: (citation: KnowledgeAnswerCitation) => Promise<void> }) {
  const [question, setQuestion] = useState(''), [query, setQuery] = useState(''), [task, setTask] = useState<KnowledgeAnswerTask | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), composing = useRef(false)
  const active = task?.status === 'QUEUED' || task?.status === 'RUNNING'
  const clear = () => { generation.current++; composing.current = false; setQuestion(''); setQuery(''); setTask(null); setBusy(false); setError('') }
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  useEffect(() => { if (!visible) clear() }, [visible])
  useEffect(() => {
    if (!visible || !enabled || !active || busy || error || !task) return
    const epoch = generation.current, id = task.taskId
    const timer = setTimeout(() => {
      void bridge.getKnowledgeAnswer(id).then(result => { if (epoch === generation.current) setTask(result) })
        .catch(e => { if (epoch === generation.current) setError(e instanceof Error ? e.message : '回答状态暂时不可用。') })
    }, 1000)
    return () => clearTimeout(timer)
  }, [bridge, visible, enabled, active, busy, error, task])
  const submit = async () => {
    if (!enabled || !visible || busy || active || composing.current || !question.trim() || !query.trim()) return
    const epoch = ++generation.current; setBusy(true); setTask(null); setError('')
    try { const result = await bridge.submitKnowledgeAnswer(question, query); if (epoch === generation.current) setTask(result) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '基于知识回答暂时不可用。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const cancel = async () => {
    if (!task || !active || busy) return
    const epoch = ++generation.current; setBusy(true); setError('')
    try { const result = await bridge.cancelKnowledgeAnswer(task.taskId); if (epoch === generation.current) setTask(result) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '取消状态暂时无法确认。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  return <section className="card knowledge-answer" aria-labelledby="knowledge-answer-title">
    <h2 id="knowledge-answer-title">基于知识回答 / Ask Knowledge</h2>
    <p className="hint">分别输入问题和检索关键词。采用确定性词法 AND 匹配，仅从当前 ACTIVE / READY 来源回答；不会自动改写关键词。</p>
    <form onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }} onKeyDown={e => { if (e.key === 'Enter' && (e.nativeEvent.isComposing || composing.current)) e.preventDefault() }} onSubmit={e => { e.preventDefault(); void submit() }}>
      <label htmlFor="knowledge-answer-question">问题 / Question</label>
      <textarea id="knowledge-answer-question" value={question} maxLength={3000} disabled={!enabled || busy || active} autoComplete="off" spellCheck={false} onChange={e => setQuestion(e.target.value)} />
      <label htmlFor="knowledge-answer-query">回答检索关键词 / Retrieval Keywords</label>
      <input id="knowledge-answer-query" value={query} disabled={!enabled || busy || active} autoComplete="off" spellCheck={false} onChange={e => setQuery(e.target.value)} />
      <button type="submit" className="primary" disabled={!enabled || busy || active || !question.trim() || !query.trim()}>基于知识回答</button>
    </form>
    <p role="status" aria-live="polite">{busy ? '正在处理…' : task?.status === 'QUEUED' ? '回答任务已排队' : task?.status === 'RUNNING' ? '正在生成回答' : task?.status === 'SUCCEEDED' ? '回答完成' : task?.status === 'CANCELLED' ? '回答已取消' : task?.status === 'TIMED_OUT' ? '回答超时' : task?.status === 'FAILED' ? '回答失败' : ''}</p>
    {active && <button disabled={!enabled || busy} onClick={() => { void cancel() }}>取消知识回答</button>}
    {(error || task?.error) && <p role="alert" className="error">{error || task?.error?.message}</p>}
    {task?.result && <><p className="knowledge-snippet" style={{ whiteSpace: 'pre-wrap' }}>{task.result.answer}</p><h3>引用来源 / Sources</h3>
      <p className="hint">以下范围为回答引用的证据；源预览可能显示额外结构上下文。</p>
      <ol>{task.result.citations.map(c => <li key={`${c.documentId}/${c.sourceRevision}/${c.startOffset}`}><h4>{c.title}</h4>
        <p className="knowledge-location">{c.sourceType} · 源版本 {c.sourceRevision} · 行 {c.startLine}–{c.endLine} · 文本偏移 {c.startOffset}–{c.endOffset}{c.heading ? ` · ${c.heading}` : ''}</p>
        <button disabled={!enabled || busy} onClick={() => { void open(c) }}>打开引用来源</button>
      </li>)}</ol></>}
  </section>
}
