import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerTask } from '../bridge/knowledgeAnswer'
import { unicode, validContent } from '../bridge/memory'
import { answerSourceHint, formatAnswerSource, readAnswerSource } from './memoryAnswerSource'

export function KnowledgeAnswerExcerpt({ bridge, task, enabled, onDraft }: {
  bridge: WorkspaceClient; task: KnowledgeAnswerTask; enabled: boolean; onDraft: (content: string, role: 'ASSISTANT') => void
}) {
  const [open, setOpen] = useState(false), [range, setRange] = useState<{ start: number; end: number } | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const text = useRef<HTMLPreElement>(null), generation = useRef(0), lock = useRef(false)
  const answer = task.result?.answer ?? ''
  const current = useRef({ enabled, task }); current.current = { enabled, task }
  const clear = () => { generation.current++; lock.current = false; setOpen(false); setRange(null); setBusy(false); setError('') }
  useEffect(clear, [enabled, task])
  useEffect(() => { const off = bridge.onSession(clear); return () => { generation.current++; off() } }, [bridge])
  useEffect(() => {
    if (!open || !enabled) return
    text.current?.focus()
    const select = () => {
      if (lock.current) return
      const selection = window.getSelection(), root = text.current
      if (!root || !selection || selection.rangeCount !== 1 || selection.isCollapsed) return
      const selected = selection.getRangeAt(0)
      if (!root.contains(selected.startContainer) || !root.contains(selected.endContainer)) return
      const before = selected.cloneRange(); before.selectNodeContents(root); before.setEnd(selected.startContainer, selected.startOffset)
      const start = before.toString().length, content = selected.toString(), end = start + content.length
      setRange(content.trim() && unicode(content) && answer.slice(start, end) === content ? { start, end } : null); setError('')
    }
    document.addEventListener('selectionchange', select)
    return () => document.removeEventListener('selectionchange', select)
  }, [open, enabled, answer])
  const draft = range ? formatAnswerSource({ taskId: task.taskId, startOffset: range.start, endOffset: range.end, excerpt: answer.slice(range.start, range.end) }) : ''
  const hint = answerSourceHint(draft), allowed = validContent(draft) && hint.kind === 'found'
  const prepare = async () => {
    if (!enabled || lock.current || !allowed || hint.kind !== 'found' || task.status !== 'SUCCEEDED') return
    const token = ++generation.current
    const valid = () => token === generation.current && current.current.enabled && current.current.task === task
    const check = () => { if (!valid()) throw new Error('当前回答已变化') }
    lock.current = true; setBusy(true); setError('')
    try {
      const result = await readAnswerSource(bridge, hint.source, check)
      check()
      if (result.entry.task.result!.answer !== answer || JSON.stringify(result.entry.task.result!.citations) !== JSON.stringify(task.result!.citations))
        throw new Error('原回答或引用已变化，未送入记忆草稿。请重新读取后选取。')
      onDraft(draft, 'ASSISTANT')
    } catch (problem) { if (valid()) setError(problem instanceof Error ? problem.message : '原问答无法核对，草稿未改变。') }
    finally { if (valid()) { lock.current = false; setBusy(false) } }
  }
  return <div className="conversation-excerpt">
    <button disabled={!enabled || task.status !== 'SUCCEEDED'} aria-expanded={open} onClick={() => { if (open) clear(); else setOpen(true) }}>{open ? '收起回答摘录' : '摘录并保留问答出处'}</button>
    {open && enabled && <section className="memory-preview" aria-label="回答记忆草稿准备" aria-busy={busy}>
      <p className="hint">选取回答片段或整条回答。出处保留原问答回查入口，送入记忆编辑器后仍需填写标题并明确保存。回答尚未经事实核实。</p>
      <pre ref={text} tabIndex={0} dir="auto" aria-label="可选取的回答原文">{answer}</pre>
      <button disabled={busy} onClick={() => { setRange({ start: 0, end: answer.length }); setError('') }}>选择整条回答</button>
      {range && <><pre tabIndex={0} dir="auto" aria-label="带问答出处的草稿预览">{draft}</pre><p className="hint">含出处 {draft.length} / 2000 UTF-16 单元。</p>
        {!allowed && <p role="alert">片段含出处后超限或无法准确定位，请缩短选段；内容不会截断。</p>}</>}
      <button disabled={busy || !allowed} onClick={() => { void prepare() }}>核对问答并送入记忆草稿</button>
      {busy && <p role="status">正在核对原问答…</p>}{error && <p role="alert">{error}</p>}
    </section>}
  </div>
}
