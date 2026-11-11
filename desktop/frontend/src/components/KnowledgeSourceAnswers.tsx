import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerCitation, KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import { maximumSearchSources, type KnowledgeSearchSource } from './KnowledgeSearchScopePicker'
import { AnswerContent } from './AnswerContent'
import { ConfirmationDialog } from './ConfirmationDialog'
import { KnowledgeAnswerEvidence } from './KnowledgeAnswerEvidence'

type AnswerRow = KnowledgeSearchSource & {
  phase: 'waiting' | 'submitting' | 'submitted' | 'rejected' | 'unknown'
  taskId: string | null; task: KnowledgeAnswerTask | null; error: string; cancelRequested: boolean
}
type AnswerBatch = { question: string; query: string; rows: AnswerRow[] }
type Preparation = { sources: KnowledgeSearchSource[]; query: string } & ({ kind: 'selected' } | { kind: 'failed'; question: string })
const active = (task: KnowledgeAnswerTask | null) => task?.status === 'QUEUED' || task?.status === 'RUNNING'
const awaiting = (row: AnswerRow) => row.phase === 'submitted' && (!row.task || active(row.task))
const failed = (row: AnswerRow) => row.phase === 'rejected' || row.task?.status === 'FAILED' || row.task?.status === 'CANCELLED' || row.task?.status === 'TIMED_OUT'
const labels = { QUEUED: '已排队', RUNNING: '正在回答', SUCCEEDED: '回答完成', FAILED: '回答失败', CANCELLED: '已取消', TIMED_OUT: '已超时' }

// Each row owns one existing, single-source answer request. Retrying confirmed
// failures requires preparing and explicitly submitting a new batch. Never infer
// a task ID from history or combine independent answers into one citation set.
export function KnowledgeSourceAnswers({ bridge, enabled, visible, sources, searchQuery, open }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean; sources: KnowledgeSearchSource[] | null; searchQuery: string
  open?: ((citation: KnowledgeAnswerCitation) => Promise<void>) | undefined
}) {
  const [selection, setSelection] = useState<KnowledgeSearchSource[] | null>(null)
  const [question, setQuestion] = useState(''), [query, setQuery] = useState('')
  const [batch, setBatch] = useState<AnswerBatch | null>(null), batchRef = useRef<AnswerBatch | null>(null)
  const [busy, setBusy] = useState(false), [running, setRunning] = useState(false), [notice, setNotice] = useState('')
  const [replacement, setReplacement] = useState<Preparation | null>(null)
  const generation = useRef(0), lock = useRef(false), continuing = useRef(false), composing = useRef(false)
  const availability = useRef(enabled && visible); availability.current = enabled && visible
  const update = (value: AnswerBatch | null) => { batchRef.current = value; setBatch(value) }
  const patchRow = (index: number, change: Partial<AnswerRow>) => {
    const value = batchRef.current
    if (value) update({ ...value, rows: value.rows.map((row, i) => i === index ? { ...row, ...change } : row) })
  }
  const pause = () => { continuing.current = false; setRunning(false) }
  useEffect(() => {
    const off = bridge.onSession(() => {
      generation.current++; lock.current = false; continuing.current = false; composing.current = false
      update(null); setSelection(null); setQuestion(''); setQuery(''); setBusy(false); setRunning(false); setNotice(''); setReplacement(null)
    })
    return () => { generation.current++; continuing.current = false; off() }
  }, [bridge])
  useEffect(() => { if (!enabled || !visible) { pause(); setReplacement(null) } }, [enabled, visible])
  const unresolved = batch?.rows.some(row => row.phase === 'unknown' || row.phase === 'submitting' || awaiting(row)) ?? false
  const pending = batch?.rows.some(row => row.phase === 'waiting' || awaiting(row)) ?? false
  const failedRows = batch?.rows.filter(failed) ?? []
  const eligible = sources !== null && sources.length > 0 && sources.length <= maximumSearchSources && !sources.some(source => source.unavailableReason)
  const prepare = (next: Preparation) => {
    if (lock.current || unresolved || !availability.current) return
    if (next.kind === 'failed') {
      if (!batchRef.current || pending) return
      setQuestion(next.question)
    }
    generation.current++; update(null); setSelection(next.sources); setQuery(next.query); setNotice(''); setReplacement(null)
  }
  const validate = (task: KnowledgeAnswerTask, row: AnswerRow) => {
    if (row.taskId && task.taskId !== row.taskId) throw new Error('任务编号与原请求不一致，未采用返回内容。')
    if (task.result?.citations.some(c => c.documentId !== row.scope.documentId || c.sourceRevision !== row.scope.sourceRevision))
      throw new Error('引用超出了本份资料的明确版本，未采用返回内容。')
    return task
  }
  const drive = async () => {
    if (!availability.current || lock.current || !batchRef.current || batchRef.current.rows.some(row => row.phase === 'unknown')) return
    const epoch = generation.current
    lock.current = true; continuing.current = true; setBusy(true); setRunning(true); setNotice('')
    let polls = 0
    const current = () => epoch === generation.current
    const proceed = () => current() && continuing.current && availability.current
    try {
      while (proceed()) {
        const value = batchRef.current!
        const index = value.rows.findIndex(row => row.phase === 'waiting' || awaiting(row))
        if (index < 0) break
        const row = value.rows[index]!
        if (row.phase === 'waiting') {
          patchRow(index, { phase: 'submitting', error: '' })
          try {
            const task = await bridge.submitKnowledgeAnswer(value.question, value.query, row.scope)
            if (!current()) return
            // Preserve the known ID even if its citation scope fails validation.
            try { validate(task, row) } catch (problem) {
              patchRow(index, { phase: 'submitted', taskId: task.taskId, error: String(problem instanceof Error ? problem.message : problem) })
              setNotice('本份回答的来源核对失败，后续已暂停。请先核对原任务。'); break
            }
            patchRow(index, { phase: 'submitted', taskId: task.taskId, task })
            if (task.status !== 'SUCCEEDED' && !active(task)) { setNotice('本份回答未完成，后续已暂停。可以保留该结果并明确继续剩余资料。'); break }
          } catch (problem) {
            if (!current()) return
            const unknown = !(problem instanceof BridgeError) || ['OutcomeUnknown', 'RuntimeUnavailable', 'ClientTimeout', 'InvalidResponse'].includes(problem.code)
            patchRow(index, { phase: unknown ? 'unknown' : 'rejected', error: problem instanceof Error ? problem.message : '提交结果无法确认。' })
            setNotice(unknown ? '提交结果未知，整轮已阻止继续和重发。请查看既有问答历史及运行状态；相似历史不能证明本次结果。' : '本份请求未受理，后续已暂停。核对原因后可明确继续剩余资料。')
            break
          }
        } else if (row.taskId) {
          if (++polls > 120) { setNotice('等待已暂停。请核对原任务；不会重新提交问题。'); break }
          await new Promise<void>(resolve => setTimeout(resolve, 1000))
          if (!proceed()) break
          try {
            const task = await bridge.getKnowledgeAnswer(row.taskId)
            if (!current()) return
            validate(task, row); patchRow(index, { task, error: '' })
            if (task.status !== 'SUCCEEDED' && !active(task)) { setNotice('本份回答未完成，后续已暂停。可以保留该结果并明确继续剩余资料。'); break }
          } catch (problem) {
            if (!current()) return
            patchRow(index, { error: problem instanceof Error ? problem.message : '原任务状态暂时无法确认。' })
            setNotice('原任务状态待核对，后续已暂停。'); break
          }
        }
      }
    } finally {
      if (current()) { lock.current = false; pause(); setBusy(false) }
    }
  }
  const start = () => {
    if (!availability.current || lock.current || batchRef.current || composing.current || !selection?.length
      || !question.trim() || question.length > 3000 || !query.trim() || [...query].length > 128) return
    update({ question, query, rows: selection.map(source => ({ ...source, phase: 'waiting', taskId: null, task: null, error: '', cancelRequested: false })) })
    void drive()
  }
  const inspect = async (index: number, cancel: boolean) => {
    const row = batchRef.current?.rows[index]
    if (!availability.current || lock.current || !row?.taskId || !awaiting(row) || cancel && row.cancelRequested) return
    const epoch = generation.current
    lock.current = true; setBusy(true); pause()
    if (cancel) patchRow(index, { cancelRequested: true })
    try {
      const task = cancel ? await bridge.cancelKnowledgeAnswer(row.taskId) : await bridge.getKnowledgeAnswer(row.taskId)
      if (epoch !== generation.current) return
      validate(task, row); patchRow(index, { task, error: '' })
      setNotice(cancel ? '已核对取消请求的返回状态。后续仍暂停；只有显示“已取消”才表示任务取消完成。' : '已核对原任务。后续仍暂停，继续需要明确操作。')
    } catch (problem) {
      if (epoch === generation.current) patchRow(index, { error: problem instanceof Error ? problem.message : '原任务状态暂时无法确认。' })
    } finally { if (epoch === generation.current) { lock.current = false; setBusy(false) } }
  }
  const unknown = batch?.rows.some(row => row.phase === 'unknown') ?? false
  return <section aria-label="所选资料逐份问答">
    <button disabled={!enabled || !visible || busy || unresolved || !eligible} onClick={() => {
      if (!eligible || !sources) return
      const next: Preparation = { kind: 'selected', sources: sources.map(source => ({ title: source.title, scope: { ...source.scope } })), query: searchQuery }
      if (selection) setReplacement(next); else prepare(next)
    }}>准备用所选资料逐份提问</button>
    <p className="hint">先在检索范围中明确选择资料，再核对问题与版本。每份独立检索和回答，最多 10 次模型请求；不会合成为一份跨资料结论。</p>
    {replacement && <ConfirmationDialog title={replacement.kind === 'failed' ? '重新准备失败的资料？' : '重新准备所选资料？'} confirmText="替换清单并保留问题" cancel={() => setReplacement(null)} confirm={() => prepare(replacement)}>
      {replacement.kind === 'failed' ? <>
        <p>仅准备以下 {replacement.sources.length} 份已失败、取消、超时或未受理的资料，保留原问题、关键词和源版本。成功项不会加入新清单。</p>
        <ul aria-label="待重试资料">{replacement.sources.map(source => <li key={source.scope.documentId}><bdi>{source.title}</bdi> · 源版本 {source.scope.sourceRevision}</li>)}</ul>
        <p>将替换本轮清单和页面结果，已保存问答历史不变。此操作不会提交问题；核对后还需明确点击“确认清单并逐份回答”。</p>
      </> : <p>将替换本轮清单、关键词和页面结果，保留问题正文；已保存问答历史不变。此操作不会提交问题。</p>}
    </ConfirmationDialog>}
    {selection && <>
      <h3>逐份问答清单 · {selection.length} 份</h3>
      <ul aria-label="本轮问答资料">{selection.map(source => <li key={source.scope.documentId}><bdi>{source.title}</bdi> · 源版本 {source.scope.sourceRevision}</li>)}</ul>
      <p className="hint">本清单固定到准备时的版本，上方检索范围的后续修改不改变本轮。每份使用下方关键词重新取证，未命中或版本失效会明确失败，范围不会扩大。</p>
      <form onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }} onSubmit={event => { event.preventDefault(); start() }}
        onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || composing.current)) event.preventDefault() }}>
        <label>逐份回答的问题<textarea value={question} maxLength={3000} disabled={!enabled || !!batch} onChange={event => setQuestion(event.target.value)} /></label>
        <label>逐份回答的关键词<input value={query} disabled={!enabled || !!batch} onChange={event => setQuery(event.target.value)} /></label>
        {!batch && <button type="submit" disabled={!enabled || !visible || !question.trim() || !query.trim() || [...query].length > 128}>确认清单并逐份回答</button>}
      </form>
      <p className="hint">离开页面或服务不可用会暂停后续请求；返回后需明确继续。暂停不会取消已经发出的任务。清单和草稿仅保留在当前连接会话，已受理问答仍可从既有问答历史逐条查看。</p>
    </>}
    {batch && <>
      <p role="status">本轮 {batch.rows.filter(row => row.task?.status === 'SUCCEEDED').length} / {batch.rows.length} 份回答成功{running ? ' · 正在逐份处理' : pending ? ' · 后续已暂停' : ' · 本轮处理已停止'}。</p>
      {batch.rows.some(row => row.task?.status !== 'SUCCEEDED') && <p className="hint">结果不完整不等于所有资料均无证据。请逐项查看未受理、失败、取消或尚未提交的资料。</p>}
      {running && <button onClick={() => { pause(); setNotice('已暂停后续请求；在途请求仍可能返回，未宣称取消。') }}>暂停本轮</button>}
      {!running && pending && !unknown && <button disabled={!enabled || !visible || busy} onClick={() => { void drive() }}>继续尚未完成的资料</button>}
      {!running && !pending && !unresolved && failedRows.length > 0 && <button disabled={!enabled || !visible || busy} onClick={() => setReplacement({
        kind: 'failed', question: batch.question, query: batch.query,
        sources: failedRows.map(row => ({ title: row.title, scope: { ...row.scope } })),
      })}>仅重新准备失败项</button>}
      {notice && <p role={unknown ? 'alert' : 'status'}>{notice}</p>}
      {batch.rows.map((row, index) => <section key={row.scope.documentId} aria-label={`逐份回答：${row.title}`}>
        <h4>{row.title} · 源版本 {row.scope.sourceRevision}</h4>
        <p>{row.task ? labels[row.task.status] : ({ waiting: '尚未提交', submitting: '正在提交', rejected: '请求未受理', unknown: '提交结果未知', submitted: '已提交，结果待核对' })[row.phase]}</p>
        {(row.error || row.task?.error) && <p role="alert">{row.error || row.task?.error?.message}</p>}
        {row.taskId && <p className="hint">任务 ID：{row.taskId}</p>}
        {awaiting(row) && <>
          <button disabled={!enabled || !visible || busy} onClick={() => { void inspect(index, false) }}>核对本份原任务</button>
          <button disabled={!enabled || !visible || busy || row.cancelRequested} onClick={() => { void inspect(index, true) }}>取消本份回答</button>
          {row.cancelRequested && <p className="hint">已请求取消，不会重复发送；请核对原任务的最终状态。</p>}
          {running && <p className="hint">如需取消，先暂停本轮，待在途请求返回后取消本份回答。</p>}
        </>}
        {row.task?.result && <>
          <AnswerContent text={row.task.result.answer} />
          <KnowledgeAnswerEvidence bridge={bridge} citations={row.task.result.citations} enabled={enabled && visible && !busy} />
          {open && row.task.result.citations.map((citation, i) => <button key={`${citation.startOffset}/${i}`} disabled={!enabled || !visible || busy} onClick={() => { void open(citation) }}>打开本份引用位置 {i + 1}</button>)}
        </>}
      </section>)}
    </>}
  </section>
}
