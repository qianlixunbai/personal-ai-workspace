import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { OperationView } from '../bridge/contracts'
import { validDraft } from '../bridge/memory'
import type { MemoryDraft, MemoryType } from '../bridge/memory'
import { candidatePrompt, checkCandidateDuplicates, parseCandidate } from './memoryCandidate'
import type { CandidateDuplicateCheck, MemoryCandidate, MemorySourceRole } from './memoryCandidate'

const terminal = (value: OperationView) => !['QUEUED', 'RUNNING'].includes(value.status)
const safeError = (problem: unknown) => problem instanceof BridgeError ? problem.message : '候选生成暂时不可用，请检查本机模型状态。'

export function MemoryCandidatePanel({ bridge, enabled, draft, role, apply }: {
  bridge: WorkspaceClient; enabled: boolean; draft: MemoryDraft; role: MemorySourceRole; apply: (value: MemoryDraft) => void
}) {
  const [operation, setOperation] = useState<OperationView | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [unknown, setUnknown] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [checking, setChecking] = useState(false)
  const [candidate, setCandidate] = useState<MemoryCandidate | null>(null)
  const [rawResult, setRawResult] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [duplicateResult, setDuplicateResult] = useState<CandidateDuplicateCheck | null>(null)
  const [duplicateChecking, setDuplicateChecking] = useState(false)
  const [duplicateError, setDuplicateError] = useState('')
  const [duplicateAcknowledged, setDuplicateAcknowledged] = useState(false)
  const duplicateGeneration = useRef(0)
  const duplicateLock = useRef(false)
  const generation = useRef(0)
  const locked = useRef(false)
  const cancelled = useRef(false)
  const source = useRef<{ draft: MemoryDraft; role: MemorySourceRole } | null>(null)
  const prompt = candidatePrompt(draft.content, role)
  const active = submitting || !!operation && !terminal(operation)
  const unchanged = source.current?.role === role && source.current.draft.content === draft.content
    && source.current.draft.title === draft.title && source.current.draft.type === draft.type
  const needsDuplicateReview = !!duplicateError || !!duplicateResult && (!duplicateResult.complete || duplicateResult.matches.length > 0)
  const duplicateAllowsAdoption = !duplicateChecking && (!needsDuplicateReview || duplicateAcknowledged)

  useEffect(() => {
    duplicateGeneration.current++; duplicateLock.current = false
    setDuplicateResult(null); setDuplicateChecking(false); setDuplicateError(''); setDuplicateAcknowledged(false)
    return () => { duplicateGeneration.current++ }
  }, [bridge, candidate])
  useEffect(() => {
    if (!enabled && duplicateLock.current) {
      duplicateGeneration.current++; duplicateLock.current = false; setDuplicateChecking(false)
      setDuplicateError('检查已中断，不能判断是否已有相同正文。请重新检查。')
    }
  }, [enabled])
  const checkDuplicates = async () => {
    if (!enabled || !candidate || !validDraft(candidate.draft) || duplicateLock.current) return
    const token = ++duplicateGeneration.current
    duplicateLock.current = true; setDuplicateChecking(true); setDuplicateResult(null); setDuplicateError(''); setDuplicateAcknowledged(false)
    try {
      const result = await checkCandidateDuplicates(bridge, candidate.draft.content, () => token === duplicateGeneration.current)
      if (token === duplicateGeneration.current) setDuplicateResult(result)
    } catch { if (token === duplicateGeneration.current) setDuplicateError('未能完成重复检查，不能判断是否已有相同正文。请重试或明确选择继续。') }
    finally { if (token === duplicateGeneration.current) { duplicateLock.current = false; setDuplicateChecking(false) } }
  }

  const accept = (value: OperationView) => {
    setOperation(value); setUnknown(false); setError('')
    if (!terminal(value)) { setNotice(cancelled.current ? '已请求取消，等待任务结束。' : '正在用当前本机模型生成候选…'); return }
    locked.current = false; setCancelling(false)
    if (cancelled.current) { setCandidate(null); setNotice('任务已结束；已按取消意图丢弃候选。'); return }
    if (value.status !== 'SUCCEEDED') { setNotice('未生成候选，原草稿保留。'); setError(value.error?.message ?? '任务未完成。'); return }
    try {
      const parsed = parseCandidate(value.result!, source.current!.draft.content)
      setCandidate(parsed)
      setNotice(parsed ? '候选尚未保存。请对照摘录审阅、编辑，再采用到编辑器。' : '模型未提出适合长期保留的记忆；原草稿保持不变。')
    } catch {
      setRawResult(value.result!); setError('模型输出不符合候选格式、内容限制或原文摘录要求。未采用，未保存；可查看输出并手动整理原草稿。')
      setNotice('')
    }
  }

  useEffect(() => {
    const reset = () => {
      generation.current++; locked.current = false; cancelled.current = false; source.current = null
      setOperation(null); setSubmitting(false); setUnknown(false); setCancelling(false); setChecking(false)
      setCandidate(null); setRawResult(''); setError(''); setNotice('')
    }
    const unsubscribe = bridge.onSession(reset)
    return () => { generation.current++; unsubscribe() }
  }, [bridge])
  useEffect(() => {
    if (!operation || terminal(operation) || unknown) return
    const token = generation.current
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const value = await bridge.getOperation(operation.operationId)
        if (disposed || token !== generation.current) return
        accept(value)
        if (!terminal(value)) timer = setTimeout(() => { void poll() }, 500)
      } catch (problem) {
        if (!disposed && token === generation.current) { setUnknown(true); setError(safeError(problem)); setNotice('执行结果待确认，不会自动重发。') }
      }
    }
    timer = setTimeout(() => { void poll() }, 500)
    return () => { disposed = true; clearTimeout(timer) }
  }, [bridge, operation?.operationId, operation?.status, unknown])

  const generate = async () => {
    if (!enabled || !prompt || locked.current || active || unknown) return
    locked.current = true; cancelled.current = false
    const token = ++generation.current
    source.current = { draft: { ...draft }, role }
    setSubmitting(true); setOperation(null); setCandidate(null); setRawResult(''); setError(''); setNotice('正在提交候选生成请求…')
    try {
      const value = await bridge.proposeMemory(prompt)
      if (token === generation.current) accept(value)
    } catch (problem) {
      if (token !== generation.current) return
      // A failed submission may already have been accepted. Never automatically
      // retry, or enable another submit while this outcome remains uncertain.
      const uncertain = !(problem instanceof BridgeError) || ['OutcomeUnknown', 'RuntimeUnavailable', 'ClientTimeout', 'InvalidResponse'].includes(problem.code)
      setUnknown(uncertain); locked.current = uncertain; setError(safeError(problem))
      setNotice(uncertain ? '提交结果待确认，不会自动重发。请先检查本机任务状态。' : '未生成候选，原草稿保留。')
    } finally { if (token === generation.current) setSubmitting(false) }
  }
  const cancel = async () => {
    if (!operation || terminal(operation) || cancelled.current || !enabled) return
    cancelled.current = true; setCancelling(true); setCandidate(null)
    const token = generation.current
    setNotice('已请求取消；取消不是模型已停止的证明。')
    try { await bridge.cancelOperation(operation.operationId) }
    catch (problem) { if (token === generation.current) { setUnknown(true); setError(safeError(problem)); setNotice('取消结果待确认，请核对生成状态。') } }
  }
  const check = async () => {
    if (!operation || checking || !enabled) return
    const token = generation.current
    setChecking(true)
    try { const value = await bridge.getOperation(operation.operationId); if (token === generation.current) accept(value) }
    catch (problem) { if (token === generation.current) setError(safeError(problem)) }
    finally { if (token === generation.current) setChecking(false) }
  }
  return <section className="memory-candidate" aria-label="从对话生成记忆候选">
    <h3>从对话提炼记忆</h3>
    <p className="hint">只将下方编辑器中的当前正文发送给已配置的本机模型，生成一条待审阅候选。来源：{role === 'USER' ? '用户消息' : '助手回复（未经核实）'}。不会自动保存或选为上下文。</p>
    {!prompt && <p className="hint">正文为空、含无效字符或超出本次模型输入预算。请先手动精简正文；原文不会自动截断。</p>}
    <div className="operation-actions"><button type="button" disabled={!enabled || !prompt || active || unknown} onClick={() => { void generate() }}>生成记忆候选</button>
      {active && <button type="button" disabled={!enabled || !operation || cancelling} onClick={() => { void cancel() }}>取消生成候选</button>}
      {unknown && operation && <button type="button" disabled={!enabled || checking} onClick={() => { void check() }}>核对生成状态</button>}</div>
    {active && <p className="hint">离开或丢弃草稿会清除候选界面，但不保证已提交任务停止。</p>}
    {notice && <p role="status">{notice}</p>}{error && <p className="error" role="alert">{error}</p>}
    {rawResult && <details><summary>查看未采用的模型输出</summary><pre className="operation-result" dir="auto">{rawResult}</pre></details>}
    {candidate && <>
      <p className="hint">原文依据（摘录一致不代表候选推论正确）：</p><blockquote dir="auto">{candidate.evidence}</blockquote>
      <label htmlFor="candidate-type">候选类型</label><select id="candidate-type" value={candidate.draft.type} disabled={!enabled} onChange={event => setCandidate({ ...candidate, draft: { ...candidate.draft, type: event.target.value as MemoryType } })}>
        <option value="PREFERENCE">个人偏好</option><option value="PROJECT_NOTE">项目笔记</option></select>
      <label htmlFor="candidate-title">候选标题</label><input id="candidate-title" value={candidate.draft.title} disabled={!enabled} autoComplete="off" onChange={event => setCandidate({ ...candidate, draft: { ...candidate.draft, title: event.target.value } })} />
      <label htmlFor="candidate-content">候选正文</label><textarea id="candidate-content" value={candidate.draft.content} readOnly={!enabled} autoComplete="off" spellCheck={false} onChange={event => setCandidate({ ...candidate, draft: { ...candidate.draft, content: event.target.value } })} />
      {!unchanged && <p className="error">原草稿已变更，不能用本次候选覆盖。可保留参考，或重新生成。</p>}
      {!validDraft(candidate.draft) && <p className="error">请检查候选标题、正文及长度限制后再采用。</p>}
      <section aria-label="候选重复检查">
        <button type="button" disabled={!enabled || duplicateChecking || !validDraft(candidate.draft)} onClick={() => { void checkDuplicates() }}>检查已有记忆</button>
        <p className="hint">可选检查：只比较使用中的记忆正文，最多核对 20 条匹配记录；不检查语义相似或已归档记忆。编辑候选后需重新检查。</p>
        {duplicateChecking && <p role="status">正在检查可能重复的记忆…</p>}
        {!duplicateChecking && !duplicateResult && !duplicateError && <p className="hint">尚未检查重复。</p>}
        {duplicateError && <p role="alert" className="error">{duplicateError}</p>}
        {duplicateResult && <>
          {duplicateResult.matches.length > 0 ? <><p>已找到正文完全相同的记忆：</p><ul>{duplicateResult.matches.map(item => <li key={item.id}><bdi>{item.title}</bdi> · 版本 {item.revision}</li>)}</ul></>
            : <p>本次已核对的 {duplicateResult.checked} 条记录中，未发现正文完全相同项。</p>}
          {!duplicateResult.complete && <p role="alert" className="error">检查不完整：存在未检查、已变更或读取失败的记录，不能据此排除重复。</p>}
          <p className="hint">搜索返回 {duplicateResult.total} 条匹配记录，本次核对 {duplicateResult.checked} 条。检查后记录仍可能变化，不会自动合并或覆盖。</p>
        </>}
        {needsDuplicateReview && <label><input type="checkbox" checked={duplicateAcknowledged} disabled={!enabled}
          onChange={event => setDuplicateAcknowledged(event.target.checked)} />我已了解重复项或检查限制，仍要采用候选</label>}
      </section>
      <div className="operation-actions"><button type="button" disabled={!enabled || !unchanged || !validDraft(candidate.draft) || !duplicateAllowsAdoption} onClick={() => { if (unchanged && validDraft(candidate.draft) && !duplicateLock.current && duplicateAllowsAdoption) apply(candidate.draft) }}>采用候选到编辑器</button>
        <button type="button" onClick={() => { setCandidate(null); setNotice('已丢弃候选，原草稿保留。') }}>丢弃候选</button></div>
      <p className="hint">采用会替换编辑器中的类型、标题和正文；还需点击编辑器的“保存”才写入记忆。</p>
    </>}
  </section>
}
