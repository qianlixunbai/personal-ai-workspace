import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerHistoryEntry, KnowledgeAnswerHistoryPage } from '../bridge/knowledgeAnswer'
import type { ConversationReference } from './conversationReferences'
import { AnswerContent } from './AnswerContent'
import { KnowledgeAnswerEvidence } from './KnowledgeAnswerEvidence'
import { KnowledgeAnswerExcerpt } from './KnowledgeAnswerExcerpt'
import { readAnswerSource } from './memoryAnswerSource'

export type AnswerSelection = { taskId: string; reference?: Extract<ConversationReference, { kind: 'answer' }> }
export function WorkspaceAnswerHistory({ bridge, selected, choose, prepare, disabled }: {
  bridge: WorkspaceClient; selected: AnswerSelection | null; choose: (value: AnswerSelection | null) => void
  prepare?: ((reference: ConversationReference) => void) | undefined; disabled: boolean
}) {
  const [list, setList] = useState<KnowledgeAnswerHistoryPage | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const generation = useRef(0), lock = useRef(false)
  useEffect(() => {
    const off = bridge.onSession(() => { generation.current++; lock.current = false; setList(null); setBusy(false); setError('') })
    return () => { generation.current++; off() }
  }, [bridge])
  const load = async (page: number) => {
    if (disabled || lock.current) return
    const token = ++generation.current
    lock.current = true; setBusy(true); setError('')
    try {
      let value = await bridge.knowledgeAnswerHistory(page)
      if (token !== generation.current) return
      if (page > 0 && !value.items.length) value = await bridge.knowledgeAnswerHistory(Math.max(0, Math.ceil(value.total / 20) - 1))
      if (token === generation.current) setList(value)
    } catch (problem) { if (token === generation.current) setError(problem instanceof Error ? problem.message : '问答历史暂时不可用。') }
    finally { if (token === generation.current) { lock.current = false; setBusy(false) } }
  }
  return <section aria-label="工作区问答历史" aria-busy={busy}>
    <h3>知识问答历史</h3><p className="hint">按保存时间逐页浏览，独立于上方关键词查找。阅读不会改变资料页的问题草稿或重新生成回答。</p>
    <button disabled={disabled || busy} onClick={() => { void load(0) }}>{list ? '刷新问答浏览' : '浏览已保存的问答'}</button>
    {error && <p role="alert">{error}</p>}
    {list && <><p>共 {list.total} 条 · 第 {list.page + 1} 页</p>
      {!list.items.length && <p>没有已保存的问答。</p>}
      <ul className="workspace-search-results">{list.items.map(row => <li key={row.taskId}><button aria-label={`阅读问答：${row.question}`} disabled={disabled || busy} onClick={() => choose({ taskId: row.taskId })}>
        阅读问答：{row.question}<small>{new Date(row.createdAt).toLocaleString()} · {row.status === 'SUCCEEDED' ? '已完成' : '未成功完成'}</small></button></li>)}</ul>
      <div className="pagination"><button disabled={disabled || busy || list.page === 0} onClick={() => { void load(list.page - 1) }}>上一页问答浏览</button>
        <button disabled={disabled || busy || (list.page + 1) * 20 >= list.total} onClick={() => { void load(list.page + 1) }}>下一页问答浏览</button></div>
    </>}
    {selected && <><button disabled={disabled} onClick={() => choose(null)}>收起问答阅读</button>
      <AnswerHistoryReader key={selected.taskId} bridge={bridge} selected={selected} prepare={prepare} disabled={disabled} /></>}
  </section>
}
function AnswerHistoryReader({ bridge, selected, prepare, disabled }: {
  bridge: WorkspaceClient; selected: AnswerSelection; prepare?: ((reference: ConversationReference) => void) | undefined; disabled: boolean
}) {
  const [entry, setEntry] = useState<KnowledgeAnswerHistoryEntry | null>(null), [error, setError] = useState('')
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    let disposed = false
    const off = bridge.onSession(() => { disposed = true; setEntry(null); setError('会话已变化，请重新打开问答。') })
    const check = () => { if (disposed) throw new Error('Reader closed') }
    setEntry(null); setError('')
    void (async () => {
      const reference = selected.reference
      const value = reference ? (await readAnswerSource(bridge, reference.source, check)).entry : await bridge.getKnowledgeAnswerHistory(selected.taskId)
      check()
      if (value.task.taskId !== selected.taskId || reference && JSON.stringify(value) !== JSON.stringify(reference.entry))
        throw new Error('待插入的问答记录或引用已变化，请移除后重新选择。')
      setEntry(value)
    })().catch(problem => { if (!disposed) setError(problem instanceof Error ? problem.message : '问答暂时无法读取。') })
    return () => { disposed = true; off() }
  }, [bridge, selected])
  useEffect(() => { if (entry || error) heading.current?.focus() }, [entry, error])
  return <section className="memory-preview" aria-label="已保存问答阅读" aria-busy={!entry && !error}>
    <h4 ref={heading} tabIndex={-1}>原问答</h4>
    {error && <p role="alert">{error}</p>}{!entry && !error && <p role="status">正在读取原问答…</p>}
    {entry && <><p dir="auto">{entry.question}</p><p>原检索关键词：{entry.query}</p>
      <p className="hint">资料范围：{entry.scope ? `${entry.scope.documentId} · 源版本 ${entry.scope.sourceRevision}` : '全部可用资料'}</p>
      {entry.task.status === 'SUCCEEDED' && entry.task.result ? <>
        {selected.reference && <pre tabIndex={0} dir="auto" aria-label="待插入回答片段">{selected.reference.source.excerpt}</pre>}
        <AnswerContent text={entry.task.result.answer} />
        {prepare && <KnowledgeAnswerExcerpt bridge={bridge} task={entry.task} enabled={!disabled} prepareReference={prepare} />}
        <KnowledgeAnswerEvidence bridge={bridge} citations={entry.task.result.citations} enabled={!disabled} />
      </> : <p role="status">该问答没有成功保存的回答，不能加入参考。{entry.task.error?.message}</p>}
    </>}
  </section>
}
