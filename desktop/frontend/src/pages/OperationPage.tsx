import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { OperationView, SelectedMemory, ShellStatus } from '../bridge/contracts'
import { StatusPanel } from '../components/StatusPanel'

type Phase = 'Idle' | 'Validating' | 'Submitting' | 'Queued' | 'Running' | 'Cancelling' | 'Succeeded' | 'Failed' | 'Cancelled' | 'TimedOut' | 'OutcomeUnknown'
const terminal = (operation: OperationView) => !['QUEUED', 'RUNNING'].includes(operation.status)
const phases = { QUEUED: 'Queued', RUNNING: 'Running', SUCCEEDED: 'Succeeded', FAILED: 'Failed', CANCELLED: 'Cancelled', TIMED_OUT: 'TimedOut' } as const
const statusText: Record<Phase, string> = {
  Idle: 'Idle · 等待输入', Validating: 'Validating · 检查输入', Submitting: 'Submitting · 正在提交',
  Queued: 'Queued · 等待本机执行', Running: 'Running · 正在本机执行',
  Cancelling: 'Cancelling · 已请求取消；这不保证 GPU 立即停止', Succeeded: 'Succeeded · 已完成',
  Failed: 'Failed · 操作失败', Cancelled: 'Cancelled · 已取消；这不保证 GPU 立即停止',
  TimedOut: 'TimedOut · 已超出执行时间预算', OutcomeUnknown: 'Outcome unknown · 未确认执行结果；不会自动重发',
}

export function OperationPage({ kind, bridge, enabled, status, openNative, visible = true }: {
  kind: 'assistant' | 'translate'; bridge: WorkspaceClient; enabled: boolean; status: ShellStatus | null; openNative: () => void; visible?: boolean
}) {
  const [text, setText] = useState('')
  const [mode, setMode] = useState<'Ask' | 'Summarize'>('Ask')
  const [language, setLanguage] = useState('zh-CN')
  const [memories, setMemories] = useState<SelectedMemory[]>([])
  const [stale, setStale] = useState(false)
  const [selecting, setSelecting] = useState(false)
  const [operation, setOperation] = useState<OperationView | null>(null)
  const [phase, setPhase] = useState<Phase>('Idle')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [copying, setCopying] = useState(false)
  const generation = useRef(0)
  const composing = useRef(false)
  const locked = useRef(false)
  const editor = useRef<HTMLTextAreaElement>(null)
  const active = ['Validating', 'Submitting', 'Queued', 'Running', 'Cancelling'].includes(phase)
  const busy = active || selecting
  const action = kind === 'translate' ? 'Translate' : mode
  const charLimit = action === 'Summarize' ? 6000 : action === 'Ask' ? 3000 : 4000
  const byteLimit = action === 'Summarize' ? 6656 : 5632
  const utf8Bytes = new TextEncoder().encode(text).length
  const invalid = !text.trim() || text.length > charLimit || utf8Bytes > byteLimit

  useEffect(() => {
    const reset = () => {
      generation.current++; locked.current = false; composing.current = false
      setText(''); setMemories([]); setStale(false); setOperation(null); setPhase('Idle')
      setError(''); setNotice(''); setSelecting(false); setCopying(false)
    }
    const unsubscribe = bridge.onSession(reset)
    return () => { generation.current++; unsubscribe() }
  }, [bridge])
  useEffect(() => {
    if (!operation || terminal(operation) || phase === 'OutcomeUnknown') return
    const current = generation.current
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const next = await bridge.getOperation(operation.operationId)
        if (disposed || current !== generation.current) return
        setOperation(next)
        setPhase(previous => previous === 'Cancelling' && !terminal(next) ? previous : phases[next.status])
        if (terminal(next)) { locked.current = false; if (next.error) setError(next.error.message) }
        else timer = setTimeout(() => { void poll() }, 350)
      } catch (problem) {
        if (!disposed && current === generation.current) {
          locked.current = false; setPhase('OutcomeUnknown')
          setError(problem instanceof BridgeError ? problem.message : '未确认 Runtime 执行状态，请检查本机 Runtime。')
        }
      }
    }
    timer = setTimeout(() => { void poll() }, 350)
    return () => { disposed = true; clearTimeout(timer) }
  }, [bridge, operation?.operationId, operation?.status, phase === 'OutcomeUnknown'])

  const submit = async () => {
    if (!enabled || locked.current || busy || composing.current || stale) return
    locked.current = true
    const current = ++generation.current
    setPhase('Validating'); setError(''); setNotice(''); setOperation(null)
    if (invalid) { locked.current = false; setPhase('Failed'); setError('请输入非空文本，并检查字符与 UTF-8 字节预算。'); return }
    setPhase('Submitting')
    try {
      const accepted = kind === 'translate' ? await bridge.submitTranslate(text, language)
        : await bridge.submitAssistant({ mode, text, selectedMemoryRefs: memories.map(({ memoryId, revision, position }) => ({ memoryId, revision, position })) })
      if (current !== generation.current) return
      setOperation(accepted); setPhase(phases[accepted.status]); setMemories([]); setStale(false)
      if (accepted.error) setError(accepted.error.message)
      locked.current = !terminal(accepted)
    } catch (problem) {
      if (current !== generation.current) return
      locked.current = false
      setPhase(problem instanceof BridgeError && problem.code === 'OutcomeUnknown' ? 'OutcomeUnknown' : 'Failed')
      setError(problem instanceof BridgeError ? problem.message : '操作暂时不可用，请检查工作区状态。')
      if (problem instanceof BridgeError && ['MemorySelectionStale', 'MEMORY_SELECTION_REQUIRED'].includes(problem.code)) setStale(true)
    } finally { if (current === generation.current) editor.current?.focus({ preventScroll: true }) }
  }
  const choose = async () => {
    if (!enabled || busy) return
    const current = generation.current
    setSelecting(true); setError('')
    try {
      const choice = await bridge.selectMemories()
      if (current === generation.current && choice.changed) { setMemories(choice.selectedMemoryRefs); setStale(false) }
    } catch (problem) { if (current === generation.current) setError(problem instanceof BridgeError ? problem.message : 'Memory 选择暂时不可用。') }
    finally { if (current === generation.current) { setSelecting(false); editor.current?.focus({ preventScroll: true }) } }
  }
  const cancel = async () => {
    if (!operation || terminal(operation) || phase === 'Cancelling') return
    const current = generation.current
    setPhase('Cancelling'); setError('')
    try { await bridge.cancelOperation(operation.operationId) }
    catch (problem) {
      if (current !== generation.current) return
      try {
        const truth = await bridge.getOperation(operation.operationId)
        if (current === generation.current) { setOperation(truth); setPhase(phases[truth.status]); locked.current = !terminal(truth) }
      } catch {
        if (current === generation.current) { setPhase('OutcomeUnknown'); locked.current = false; setError(problem instanceof BridgeError ? problem.message : '未确认取消结果。') }
      }
    }
    finally { if (current === generation.current) editor.current?.focus({ preventScroll: true }) }
  }
  const copy = async () => {
    if (!operation || operation.status !== 'SUCCEEDED' || copying) return
    const current = generation.current
    setCopying(true); setError(''); setNotice('')
    try { await bridge.copyResult(operation.operationId); if (current === generation.current) setNotice('结果已复制到系统剪贴板。') }
    catch (problem) { if (current === generation.current) setError(problem instanceof BridgeError ? problem.message : '结果复制暂时不可用。') }
    finally { if (current === generation.current) setCopying(false) }
  }
  return <>
    <section className="card operation-card" aria-labelledby={`${kind}-editor-title`}>
      <div className="card-heading"><h2 id={`${kind}-editor-title`}>{kind === 'assistant' ? '问答与摘要' : '翻译文本'}</h2>
        <button className="secondary" onClick={openNative} disabled={!enabled}>打开原生 Assistant</button></div>
      {kind === 'assistant' ? <>
        <label htmlFor="assistant-mode">操作</label><select id="assistant-mode" value={mode} disabled={busy} onChange={event => {
          generation.current++; setMode(event.target.value as 'Ask' | 'Summarize'); setMemories([]); setStale(false)
          setOperation(null); setPhase('Idle'); setError(''); setNotice('')
        }}><option value="Ask">Ask</option><option value="Summarize">Summarize</option></select>
        <p className="hint">{mode === 'Ask' ? '每次问答独立执行。需要上下文时，由你逐次选择 Memory。' : '将当前文本整理为摘要。'}</p>
        {mode === 'Ask' && <div className="memory-choice"><button className="secondary" onClick={() => { void choose() }} disabled={!enabled || busy}>
          {memories.length ? 'Review / Change Memory…' : 'Use Memory…'}</button>
          <button className="secondary" onClick={() => { setMemories([]); setStale(false); setError('') }} disabled={busy || !memories.length}>Clear Memory</button>
          <span>{memories.length ? `${memories.length} selected` : 'No Memory'}</span>
          {memories.length > 0 && <ol>{memories.map(memory => <li key={memory.memoryId}><bdi>{memory.title}</bdi> · Revision {memory.revision}</li>)}</ol>}
          {stale && <p role="alert">Selection stale · 重新选择或清除 Memory 后再提交。</p>}</div>}
      </> : <><label htmlFor="translate-language">目标语言</label><select id="translate-language" value={language} disabled={busy} onChange={event => setLanguage(event.target.value)}>
        <option value="zh-CN">中文 · zh-CN</option><option value="en">English · en</option><option value="ja">日本語 · ja</option></select></>}
      <label htmlFor={`${kind}-input`}>{action === 'Ask' ? '问题' : action === 'Summarize' ? '待摘要文本' : '待翻译文本'}</label>
      <textarea ref={editor} id={`${kind}-input`} value={text} readOnly={busy} autoComplete="off" spellCheck={false}
        aria-describedby={`${kind}-input-help`} onChange={event => setText(event.target.value)}
        onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
        onKeyDown={event => {
          if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !composing.current && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
            event.preventDefault(); void submit()
          }
        }} />
      <p className="hint" id={`${kind}-input-help`}>Enter / Shift+Enter 换行；Ctrl+Enter 提交。拼音选词期间不会提交。<br />
        {text.length} / {charLimit} UTF-16 · {utf8Bytes} / {byteLimit} UTF-8 bytes</p>
      <div className="operation-actions"><button className="primary" disabled={!enabled || busy || invalid || stale} onClick={() => { void submit() }}>Submit {action}</button>
        <button className="secondary" disabled={!enabled || !operation || terminal(operation) || phase === 'Cancelling'} onClick={() => { void cancel() }}>Cancel</button></div>
      <p className="execution-status" role="status" aria-live="polite">{statusText[phase]}</p>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
    {operation?.status === 'SUCCEEDED' && <section className="card result-card" aria-labelledby={`${kind}-result-title`}>
      <div className="card-heading"><h2 id={`${kind}-result-title`}>结果</h2><button className="secondary" onClick={() => { void copy() }} disabled={copying}>Copy result</button></div>
      <pre className="operation-result" dir="auto">{operation.result}</pre><p role="status">{notice}</p></section>}
    {visible && <section className="card compact-card"><h2>本机状态</h2><StatusPanel status={status} /></section>}
  </>
}
