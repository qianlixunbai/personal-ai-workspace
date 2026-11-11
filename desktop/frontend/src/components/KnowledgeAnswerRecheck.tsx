import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeDocument } from '../bridge/knowledge'
import type { KnowledgeAnswerCitation, KnowledgeAnswerHistoryEntry, KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import type { KnowledgeScope } from '../bridge/knowledgeScope'
import { readAuthorizedAnswerHistory } from './memoryAnswerSource'
import { AnswerContent } from './AnswerContent'
import { ConfirmationDialog } from './ConfirmationDialog'

type Review = { original: KnowledgeAnswerHistoryEntry; document: KnowledgeDocument; scope: KnowledgeScope }
type Run = { review: Review; taskId: string | null; task: KnowledgeAnswerTask | null; uncertain: boolean }
const terminal = (task: KnowledgeAnswerTask | null) => !!task && !['QUEUED', 'RUNNING'].includes(task.status)

async function prepareReview(bridge: WorkspaceClient, taskId: string, check: () => void): Promise<Review> {
  const original = await readAuthorizedAnswerHistory(bridge, taskId, check)
  check()
  if (original.task.status !== 'SUCCEEDED' || !original.task.result || !original.scope)
    throw new Error('仅支持已完成且明确限定一份资料的原问答；全库回答不能自动改成单份资料。')
  const scope = original.scope
  if (original.task.result.citations.some(c => c.documentId !== scope.documentId || c.sourceRevision !== scope.sourceRevision))
    throw new Error('原回答引用与其资料范围不一致，未准备复核。')
  // A stored locator is not a current document grant. Reacquire it through the
  // ordinary ACTIVE list; never broaden to another document or archived source.
  for (let page = 0; page < 25; page++) {
    check(); const list = await bridge.listKnowledge('ACTIVE', page); check()
    const listed = list.items.find(item => item.documentId === scope.documentId)
    if (listed) {
      const detail = await bridge.getKnowledge(scope.documentId); check()
      const document = detail.document, revision = document.currentReadyRevision
      if (document.documentId !== scope.documentId || document.createdAt !== listed.createdAt
        || document.status !== 'ACTIVE' || document.processingState !== 'READY' || !revision)
        throw new Error('原资料当前不可用或仍在处理，未准备复核。')
      if (!detail.revisions.some(item => item.sourceRevision === scope.sourceRevision))
        throw new Error('原回答的资料版本已不可用，无法建立两版对照。')
      if (BigInt(revision) <= BigInt(scope.sourceRevision)) throw new Error('这份资料还没有比原回答更新的可用版本。')
      return { original, document, scope: { documentId: scope.documentId, sourceRevision: revision } }
    }
    if ((page + 1) * list.limit >= list.total) break
  }
  throw new Error('原资料已删除、归档或当前不可用，未改用其他资料。')
}

export function KnowledgeAnswerRecheck({ bridge, taskId, enabled, visible, open, onBlocked }: {
  bridge: WorkspaceClient; taskId: string | null; enabled: boolean; visible: boolean
  open: (citation: KnowledgeAnswerCitation) => Promise<void>; onBlocked: (blocked: boolean) => void
}) {
  const [review, setReview] = useState<Review | null>(null), [run, setRun] = useState<Run | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [cancelled, setCancelled] = useState(false)
  const generation = useRef(0), view = useRef(0), lock = useRef(false), cancelSent = useRef(false)
  const available = useRef(enabled && visible); available.current = enabled && visible
  const running = !!run && !terminal(run.task)
  const blocked = busy || !!review || running
  useEffect(() => { onBlocked(blocked) }, [blocked, onBlocked])
  useEffect(() => {
    const clear = () => {
      generation.current++; lock.current = false; cancelSent.current = false
      setReview(null); setRun(null); setBusy(false); setError(''); setCancelled(false)
    }
    const off = bridge.onSession(clear)
    return () => { generation.current++; off(); onBlocked(false) }
  }, [bridge, onBlocked])
  useEffect(() => { if (!enabled || !visible) { view.current++; setReview(null) } }, [enabled, visible])
  useEffect(() => { view.current++; setReview(null) }, [taskId])
  const readCheck = (epoch: number, page: number) => {
    const deadline = Date.now() + 30_000
    return () => {
      if (epoch !== generation.current || page !== view.current || !available.current) throw new Error('页面已变化，复核准备已停止；原问题和回答保留。')
      if (Date.now() > deadline) throw new Error('复核准备超时，尚未提交新问题。')
    }
  }
  const prepare = async () => {
    if (!available.current || !taskId || lock.current || running) return
    const epoch = generation.current, check = readCheck(epoch, view.current)
    lock.current = true; setBusy(true); setError(''); setReview(null)
    try { const next = await prepareReview(bridge, taskId, check); check(); setReview(next) }
    catch (problem) { if (epoch === generation.current) setError(problem instanceof Error ? problem.message : '未能准备新版复核。') }
    finally { if (epoch === generation.current) { lock.current = false; setBusy(false) } }
  }
  const accept = (previous: Run, task: KnowledgeAnswerTask) => {
    // Keep an admitted ID even when the additional scope check fails. It must be
    // reconciled by that ID, never replaced by a new submission or similar history.
    const next = { ...previous, taskId: previous.taskId ?? (task.taskId === previous.review.original.task.taskId ? null : task.taskId), uncertain: false }
    if (task.taskId !== next.taskId || task.taskId === previous.review.original.task.taskId
      || task.result?.citations.some(c => c.documentId !== previous.review.scope.documentId || c.sourceRevision !== previous.review.scope.sourceRevision)) {
      setRun({ ...next, task: null, uncertain: true }); setError('复核回执编号或引用范围不符，未采用新回答；只能核对原任务。'); return
    }
    setRun({ ...next, task }); setError(task.error?.message ?? '')
  }
  const submit = async () => {
    if (!available.current || !review || lock.current || running) return
    const epoch = generation.current, check = readCheck(epoch, view.current), prepared = review
    lock.current = true; setBusy(true); setReview(null); setError('')
    let attempted = false
    const next: Run = { review: prepared, taskId: null, task: null, uncertain: false }
    try {
      const latest = await prepareReview(bridge, prepared.original.task.taskId, check); check()
      if (JSON.stringify(latest) !== JSON.stringify(prepared)) throw new Error('原问答或资料版本在确认期间已变化，请重新准备复核。')
      cancelSent.current = false; setCancelled(false); setRun(next); attempted = true
      const task = await bridge.submitKnowledgeAnswer(prepared.original.question, prepared.original.query, prepared.scope)
      if (epoch === generation.current) accept(next, task)
    } catch (problem) {
      if (epoch !== generation.current) return
      if (attempted) {
        const uncertain = !(problem instanceof BridgeError) || ['OutcomeUnknown', 'RuntimeUnavailable', 'ClientTimeout', 'InvalidResponse'].includes(problem.code)
        setRun(uncertain ? { ...next, uncertain: true } : null)
      }
      setError(problem instanceof Error ? problem.message : '新版复核结果无法确认。')
    } finally { if (epoch === generation.current) { lock.current = false; setBusy(false) } }
  }
  const observe = async (cancelling = false) => {
    if (!available.current || !run?.taskId || !running || lock.current || cancelling && cancelSent.current) return
    const epoch = generation.current, previous = run
    lock.current = true; setBusy(true)
    if (cancelling) { cancelSent.current = true; setCancelled(true) }
    try {
      const result = cancelling ? await bridge.cancelKnowledgeAnswer(run.taskId) : await bridge.getKnowledgeAnswer(run.taskId)
      if (epoch === generation.current) accept(previous, result)
    } catch (problem) {
      if (epoch === generation.current) { setRun({ ...previous, uncertain: true }); setError(problem instanceof Error ? problem.message : '复核状态无法确认。') }
    } finally { if (epoch === generation.current) { lock.current = false; setBusy(false) } }
  }
  useEffect(() => {
    if (!enabled || !visible || !running || !run?.taskId || run.uncertain || busy) return
    const timer = setTimeout(() => { void observe() }, 1000)
    return () => clearTimeout(timer)
  }, [enabled, visible, running, run, busy])
  const readCitation = async (citation: KnowledgeAnswerCitation) => {
    if (!available.current || lock.current) return
    const epoch = generation.current
    try { await open(citation) }
    catch (problem) { if (epoch === generation.current) setError(problem instanceof Error ? problem.message : '引用来源暂时不可用。') }
  }
  const disabled = !enabled || !visible || busy
  const answer = (entry: KnowledgeAnswerTask, label: string, revision: string) => <article aria-label={label}>
    <h4>{label} · 源版本 {revision}</h4><p className="hint">问答 ID：{entry.taskId}</p>
    {entry.result && <><AnswerContent text={entry.result.answer} /><ol>{entry.result.citations.map((citation, index) => <li key={index}>
      {citation.title} · 源版本 {citation.sourceRevision} · 行 {citation.startLine}–{citation.endLine}
      <button disabled={disabled} onClick={() => { void readCitation(citation) }}>核对{label}引用 {index + 1}</button>
    </li>)}</ol></>}
  </article>
  return <section aria-label="资料新版回答复核">
    <h3>资料更新后复核回答</h3>
    <p className="hint">读取一条限定单份资料的已完成问答，用相同问题和关键词在更新的可用版本上独立回答。确认后才调用模型，保留旧回答和当前草稿。</p>
    <button disabled={disabled || running || !!review || !taskId} onClick={() => { void prepare() }}>准备新版复核</button>
    {review && <ConfirmationDialog title="用资料新版本复核原问题？" confirmText="确认版本并生成独立回答" cancel={() => setReview(null)} confirm={() => { void submit() }}>
      <p>{review.document.title}：源版本 {review.original.scope!.sourceRevision} → {review.scope.sourceRevision}</p>
      <p>原问题：{review.original.question}</p><p>原关键词：{review.original.query}</p>
      <p>提交前再次核对版本。只在这份资料的新版上检索；旧答案不作为模型输入，不会修改既有回答或记忆。</p>
    </ConfirmationDialog>}
    {busy && <p role="status">正在核对资料或问答状态…</p>}
    {error && <p role="alert">{error}</p>}
    {run && <>
      <p>本次复核问题：{run.review.original.question}</p><p>检索关键词：{run.review.original.query}</p>
      <p>{run.review.document.title} · 固定源版本 {run.review.original.scope!.sourceRevision} → {run.review.scope.sourceRevision}</p>
      {run.taskId && <p className="hint">新版复核任务 ID：{run.taskId}</p>}
      {running && <>
        <p role="status">{run.uncertain ? '复核结果待确认，不会重新提交。' : '新版回答正在处理。离页暂停状态读取，已提交任务可能继续执行。'}</p>
        {!run.taskId && run.uncertain && <p role="alert">提交结果未知，不能通过相似历史推断结果或再次提交；请到设置核对运行状态。</p>}
        {run.taskId && <button disabled={disabled} onClick={() => { void observe() }}>核对新版任务状态</button>}
        {run.taskId && <button disabled={disabled || cancelled} onClick={() => { void observe(true) }}>取消新版回答</button>}
        {cancelled && <p className="hint">已请求取消一次；取消回执不代表模型已停止。</p>}
      </>}
      {terminal(run.task) && run.task?.status !== 'SUCCEEDED' && <p role="status">新版复核{({ FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '超时' } as Record<string, string>)[run.task!.status]}，未产生可对照的新回答。旧回答保留。</p>}
      <div className="knowledge-answer-comparison">
        {answer(run.review.original.task, '原回答', run.review.original.scope!.sourceRevision)}
        {run.task?.status === 'SUCCEEDED' && answer(run.task, '新版回答', run.review.scope.sourceRevision)}
      </div>
      {run.task?.status === 'SUCCEEDED' && <p className="hint">{run.task.result!.answer === run.review.original.task.result!.answer ? '两份回答文字相同。' : '两份回答文字不同。'}回答也可能受检索片段、模型和生成差异影响，不能把文字差异直接等同于资料事实变化。请分别核对引用；两条记录可从问答历史重新打开、整理为记忆或加入研究笔记。</p>}
    </>}
  </section>
}
