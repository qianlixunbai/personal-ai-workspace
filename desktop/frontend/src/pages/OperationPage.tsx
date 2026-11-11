import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { OperationView, SelectedMemory, ShellStatus } from '../bridge/contracts'
import { VisionPanel } from '../components/VisionPanel'
import { AnswerContent } from '../components/AnswerContent'

type Phase = 'Idle' | 'Validating' | 'Submitting' | 'Queued' | 'Running' | 'Cancelling' | 'Succeeded' | 'Failed' | 'Cancelled' | 'TimedOut' | 'OutcomeUnknown'
const terminal = (operation: OperationView) => !['QUEUED', 'RUNNING'].includes(operation.status)
const phases = { QUEUED: 'Queued', RUNNING: 'Running', SUCCEEDED: 'Succeeded', FAILED: 'Failed', CANCELLED: 'Cancelled', TIMED_OUT: 'TimedOut' } as const
const statusText: Record<Phase, string> = {
  Idle: '', Validating: '正在检查输入…', Submitting: '正在提交…',
  Queued: '正在排队…', Running: '正在生成…',
  Cancelling: '已请求取消，模型可能仍在处理。', Succeeded: '已完成',
  Failed: '操作未完成', Cancelled: '已取消，模型可能仍在处理。',
  TimedOut: '处理超时', OutcomeUnknown: '执行结果待确认，不会自动重发。',
}

export function OperationPage({ kind, bridge, enabled, visible = true }: {
  kind: 'assistant' | 'translate'; bridge: WorkspaceClient; enabled: boolean; status: ShellStatus | null; visible?: boolean
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
  const [checking, setChecking] = useState(false)
  const generation = useRef(0)
  const composing = useRef(false)
  const locked = useRef(false)
  const checkLock = useRef(false)
  const cancelRequested = useRef(false)
  const operationRef = useRef<OperationView | null>(null)
  const editor = useRef<HTMLTextAreaElement>(null)
  const active = ['Validating', 'Submitting', 'Queued', 'Running', 'Cancelling'].includes(phase)
  const unresolved = phase === 'OutcomeUnknown'
  const busy = active || selecting || checking
  const blocked = busy || unresolved
  const action = kind === 'translate' ? 'Translate' : mode
  const charLimit = action === 'Summarize' ? 6000 : action === 'Ask' ? 3000 : 4000
  const byteLimit = action === 'Summarize' ? 6656 : 5632
  const utf8Bytes = new TextEncoder().encode(text).length
  const invalid = !text.trim() || text.length > charLimit || utf8Bytes > byteLimit

  // Only a receipt for this operation can settle its local state. Neither a
  // cancel acknowledgement nor a missing/expired entry proves model completion.
  const observe = (next: OperationView) => {
    operationRef.current = next; setOperation(next)
    setPhase(!terminal(next) && cancelRequested.current ? 'Cancelling' : phases[next.status])
    locked.current = !terminal(next); setError(next.error?.message ?? '')
  }

  useEffect(() => {
    const reset = () => {
      generation.current++; locked.current = false; composing.current = false
      checkLock.current = false; cancelRequested.current = false; operationRef.current = null
      setText(''); setMemories([]); setStale(false); setOperation(null); setPhase('Idle')
      setError(''); setNotice(''); setSelecting(false); setCopying(false); setChecking(false)
    }
    const unsubscribe = bridge.onSession(reset)
    return () => { generation.current++; unsubscribe() }
  }, [bridge])
  useEffect(() => {
    if (!enabled || !visible || !operation || terminal(operation) || unresolved || checking) return
    const current = generation.current
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const next = await bridge.getOperation(operation.operationId)
        if (disposed || current !== generation.current) return
        observe(next)
        if (!terminal(next)) timer = setTimeout(() => { void poll() }, 350)
      } catch (problem) {
        if (!disposed && current === generation.current) {
          locked.current = true; setPhase('OutcomeUnknown')
          setError(problem instanceof BridgeError ? problem.message : '未确认 Runtime 执行状态，请检查本机 Runtime。')
        }
      }
    }
    timer = setTimeout(() => { void poll() }, 350)
    return () => { disposed = true; clearTimeout(timer) }
  }, [bridge, enabled, visible, operation?.operationId, operation?.status, unresolved, checking])

  const submit = async () => {
    if (!enabled || !visible || locked.current || blocked || composing.current || stale) return
    locked.current = true
    const current = ++generation.current
    cancelRequested.current = false; operationRef.current = null
    setPhase('Validating'); setError(''); setNotice(''); setOperation(null)
    if (invalid) { locked.current = false; setPhase('Failed'); setError('请输入内容，或缩短文字后重试。'); return }
    setPhase('Submitting')
    try {
      const accepted = kind === 'translate' ? await bridge.submitTranslate(text, language)
        : await bridge.submitAssistant({ mode, text, selectedMemoryRefs: memories.map(({ memoryId, revision, position }) => ({ memoryId, revision, position })) })
      if (current !== generation.current) return
      observe(accepted); setMemories([]); setStale(false)
    } catch (problem) {
      if (current !== generation.current) return
      const unknown = !(problem instanceof BridgeError) || ['OutcomeUnknown', 'RuntimeUnavailable', 'ClientTimeout', 'InvalidResponse'].includes(problem.code)
      locked.current = unknown
      setPhase(unknown ? 'OutcomeUnknown' : 'Failed')
      setError(problem instanceof BridgeError ? problem.message : '操作暂时不可用，请检查工作区状态。')
      if (problem instanceof BridgeError && ['MemorySelectionStale', 'MEMORY_SELECTION_REQUIRED'].includes(problem.code)) setStale(true)
    } finally { if (current === generation.current) editor.current?.focus({ preventScroll: true }) }
  }
  const checkOperation = async () => {
    const previous = operationRef.current
    if (!enabled || !visible || !unresolved || !previous || terminal(previous) || checkLock.current) return
    const current = generation.current
    checkLock.current = true; setChecking(true); setError('')
    try {
      const next = await bridge.getOperation(previous.operationId)
      if (current === generation.current) observe(next)
    } catch (problem) {
      if (current === generation.current) {
        locked.current = true; setPhase('OutcomeUnknown')
        setError(problem instanceof BridgeError ? problem.message : '仍无法确认原任务状态，请稍后主动核对。')
      }
    } finally {
      if (current === generation.current) { checkLock.current = false; setChecking(false) }
    }
  }
  const choose = async () => {
    if (!enabled || !visible || blocked || locked.current) return
    const current = generation.current
    setSelecting(true); setError('')
    try {
      const choice = await bridge.selectMemories()
      if (current === generation.current && choice.changed) { setMemories(choice.selectedMemoryRefs); setStale(false) }
    } catch (problem) { if (current === generation.current) setError(problem instanceof BridgeError ? problem.message : 'Memory 选择暂时不可用。') }
    finally { if (current === generation.current) { setSelecting(false); editor.current?.focus({ preventScroll: true }) } }
  }
  const cancel = async () => {
    if (!enabled || !visible || !operation || terminal(operation) || unresolved || cancelRequested.current) return
    const current = generation.current
    cancelRequested.current = true
    setPhase('Cancelling'); setError('')
    try { await bridge.cancelOperation(operation.operationId) }
    catch (problem) {
      if (current !== generation.current || operationRef.current && terminal(operationRef.current)) return
      locked.current = true; setPhase('OutcomeUnknown')
      setError(problem instanceof BridgeError ? problem.message : '未确认取消结果，请核对原任务。')
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
  const actions = <div className="operation-actions"><button className="primary" disabled={!enabled || blocked || invalid || stale} onClick={() => { void submit() }}>{action === 'Ask' ? '发送问题' : action === 'Summarize' ? '生成摘要' : '开始翻译'}</button>
    {active && <button className="secondary" disabled={!enabled || !operation || terminal(operation) || phase === 'Cancelling'} onClick={() => { void cancel() }}>取消</button>}</div>
  return <>
    <section className={`card operation-card${kind === 'assistant' ? ` assistant-task assistant-task-${mode.toLowerCase()}` : ''}`} aria-labelledby={`${kind}-editor-title`}>
      {kind === 'translate' && <div className="card-heading"><h2 id="translate-editor-title">翻译文本</h2><span className="quiet-label">本机处理</span></div>}
      {kind === 'assistant' ? <>
        <div className="assistant-task-heading"><h2 id="assistant-editor-title" className="sr-only">问答与摘要</h2>
        <div className="operation-modes" role="group" aria-label="助手操作">{(['Ask', 'Summarize'] as const).map(value => <button key={value} disabled={blocked} aria-pressed={mode === value} onClick={() => {
          if (mode === value || locked.current || blocked) return
          generation.current++; setMode(value); setMemories([]); setStale(false)
          setOperation(null); setPhase('Idle'); setError(''); setNotice('')
        }}>{value === 'Ask' ? '问一问' : '总结文字'}</button>)}</div><span className="quiet-label">本机处理 · 单次任务</span></div>
        <p className="hint">{mode === 'Ask' ? '每次独立回答。需要连续交流？请打开侧栏「对话」。' : '粘贴原文，提取要点。'}</p>
      </> : <><label htmlFor="translate-language">目标语言</label><select id="translate-language" value={language} disabled={blocked} onChange={event => setLanguage(event.target.value)}>
        <option value="zh-CN">简体中文</option><option value="en">英语</option><option value="ja">日语</option></select></>}
      <label htmlFor={`${kind}-input`}>{action === 'Ask' ? '问题' : action === 'Summarize' ? '待摘要文本' : '待翻译文本'}</label>
      <textarea ref={editor} id={`${kind}-input`} value={text} readOnly={blocked} autoComplete="off" spellCheck={false} placeholder={action === 'Ask' ? '有什么想了解的？' : '在这里粘贴原文…'}
        aria-describedby={`${kind}-input-help`} onChange={event => setText(event.target.value)}
        onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
        onKeyDown={event => {
          if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !composing.current && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
            event.preventDefault(); void submit()
          }
        }} />
      <div className="composer-meta" id={`${kind}-input-help`}><span>Enter 换行 · Ctrl+Enter 发送</span><span>{text.length} / {charLimit} 字符</span></div>
      {text.trim() && invalid && <p role="alert" className="error">内容过长，请缩短后再发送。原文不会被截断。</p>}
      {kind === 'assistant' && <div className="assistant-task-toolbar">
        {mode === 'Ask' && <div className="memory-choice"><button className="secondary" onClick={() => { void choose() }} disabled={!enabled || blocked}>
          {memories.length ? '更换记忆…' : '选择记忆…'}</button>
          {memories.length > 0 && <button className="secondary" onClick={() => { setMemories([]); setStale(false); setError('') }} disabled={blocked}>清除记忆</button>}
          <span>{memories.length ? `本次使用 ${memories.length} 条` : '仅使用你本次选择的记忆'}</span>
          {memories.length > 0 && <ol>{memories.map(memory => <li key={memory.memoryId}><bdi>{memory.title}</bdi> · 版本 {memory.revision}</li>)}</ol>}
          {stale && <p role="alert">记忆已变更，请重新选择或清除后再发送。</p>}</div>}
        {actions}
      </div>}
      <details className="input-details"><summary>输入限制</summary><p className="hint">{text.length} / {charLimit} UTF-16 · {utf8Bytes} / {byteLimit} UTF-8 字节。拼音选词期间不会发送。</p></details>
      {kind === 'translate' && actions}
      {phase !== 'Idle' && <p className="execution-status" role="status" aria-live="polite">{statusText[phase]}</p>}
      {unresolved && <section aria-label="核对原任务" aria-busy={checking}>
        <p>{operation ? '已保留原任务。核对只读取状态，不会重新发送内容或取消请求。' : '提交回执未确认，尚无可核对的任务编号。已保留输入和记忆选择，本页暂停再次发送。'}</p>
        {operation && <button className="secondary" disabled={!enabled || checking} onClick={() => { void checkOperation() }}>{checking ? '正在核对…' : '核对原任务状态'}</button>}
        <p className="hint">任务不可用或已过期不能证明模型已完成。请在<a href="#/settings">设置</a>中检查工作区和模型状态；切页、刷新状态或重开页面不会证明执行结束，也不会解除模型安全限制。</p>
      </section>}
      {error && <p role="alert" className="error">{error}</p>}
    </section>
    {operation?.status === 'SUCCEEDED' && <section className="card result-card" aria-labelledby={`${kind}-result-title`}>
      <div className="card-heading"><h2 id={`${kind}-result-title`}>结果</h2><button className="secondary" onClick={() => { void copy() }} disabled={copying}>复制结果</button></div>
      {kind === 'translate' ? <pre className="operation-result" dir="auto">{operation.result}</pre>
        : <AnswerContent key={operation.operationId} text={operation.result ?? ''} />}<p role="status">{notice}</p></section>}
    {kind === 'assistant' && <details className="advanced-section"><summary>图片问答 · 验收暂停</summary><VisionPanel bridge={bridge} enabled={enabled && visible} /></details>}
  </>
}
