import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeAnswerHistoryEntry, KnowledgeAnswerHistoryPage } from '../bridge/knowledgeAnswer'
import { readAuthorizedAnswerHistory } from './memoryAnswerSource'
import { AnswerContent } from './AnswerContent'

// Selection holds a preview, not export authority. Native rereads all histories
// and exact revision excerpts; only the ordered IDs cross the save boundary.
export function KnowledgeResearchNotebook({ bridge, rows, enabled, visible, onBusy }: {
  bridge: WorkspaceClient; rows: KnowledgeAnswerHistoryPage['items']; enabled: boolean; visible: boolean; onBusy: (busy: boolean) => void
}) {
  const [selected, setSelected] = useState<KnowledgeAnswerHistoryEntry[]>([])
  const [busy, setBusy] = useState(false), [notice, setNotice] = useState(''), [error, setError] = useState('')
  const [saveUnknown, setSaveUnknown] = useState(false)
  const generation = useRef(0), lock = useRef(false), paused = useRef(false), available = useRef(enabled && visible)
  available.current = enabled && visible
  const reportBusy = useRef(onBusy); reportBusy.current = onBusy
  useEffect(() => {
    const clear = () => {
      generation.current++; lock.current = false; setBusy(false); reportBusy.current(false)
      setSelected([]); setNotice(''); setError(''); setSaveUnknown(false)
    }
    const unsubscribe = bridge.onSession(clear)
    return () => { generation.current++; unsubscribe(); reportBusy.current(false) }
  }, [bridge])
  useEffect(() => { if (!enabled || !visible) paused.current = true }, [enabled, visible])
  const work = async (action: (check: () => void) => Promise<void>) => {
    if (!available.current || lock.current) return
    const epoch = generation.current
    const check = () => { if (epoch !== generation.current || !available.current || paused.current) throw new Error('已暂停核对；请返回资料页后重新操作。') }
    paused.current = false; lock.current = true; setBusy(true); reportBusy.current(true); setError(''); setNotice('')
    try { await action(check) }
    catch (problem) { if (epoch === generation.current) setError(problem instanceof Error ? problem.message : '未完成核对，清单已保留。') }
    finally { if (epoch === generation.current) { lock.current = false; setBusy(false); reportBusy.current(false) } }
  }
  const add = (id: string) => {
    if (selected.length >= 10 || selected.some(entry => entry.task.taskId === id) || saveUnknown) return
    void work(async check => {
      const entry = await readAuthorizedAnswerHistory(bridge, id, check)
      check()
      if (entry.task.status !== 'SUCCEEDED' || !entry.task.result) throw new Error('该问答尚未成功完成，未加入研究笔记。')
      setSelected(current => [...current, entry]); setNotice('已加入原始回答，可展开核对并调整顺序。')
    })
  }
  const move = (index: number, offset: number) => {
    if (busy || !available.current || saveUnknown) return
    setSelected(current => {
      const next = [...current], other = index + offset
      if (other < 0 || other >= next.length) return current
      ;[next[index], next[other]] = [next[other]!, next[index]!]
      return next
    })
  }
  const save = () => {
    if (!selected.length || saveUnknown) return
    void work(async check => {
      const deadline = Date.now() + 30_000
      const current = () => { check(); if (Date.now() > deadline) throw new Error('清单核对超时，尚未打开保存窗口；请重新核对。') }
      for (const original of selected) {
        const entry = await readAuthorizedAnswerHistory(bridge, original.task.taskId, current)
        current()
        if (JSON.stringify(entry) !== JSON.stringify(original)) throw new Error('所选问答已变化，未导出。请移除该条并从历史重新加入。')
      }
      current()
      const saveEpoch = generation.current
      try {
        const result = await bridge.exportKnowledgeResearch(selected.map(entry => entry.task.taskId))
        if (saveEpoch !== generation.current) return
        // Saving may finish while another page is visible. Preserve its actual receipt.
        setNotice(result.outcome === 'SAVED' ? '研究笔记已保存，包含全部所选回答及引用原文。' : '已取消保存，研究清单仍保留。')
      } catch (problem) {
        if (saveEpoch !== generation.current) return
        if (problem instanceof BridgeError && ['OutcomeUnknown', 'ClientTimeout', 'InvalidResponse'].includes(problem.code)) {
          setSaveUnknown(true)
          throw new Error('保存结果无法确认。请先检查原生窗口及所选文件；不会自动重试。')
        }
        throw problem
      }
    })
  }
  const disabled = !enabled || !visible || busy || saveUnknown
  return <section aria-label="资料研究笔记">
    <h3>整理研究笔记</h3>
    <p className="hint">从问答历史或搜索结果中选取最多 10 条已完成回答，可跨页选择并排序。导出前重新核对每条问答和引用原文，一次保存为明文 Markdown。</p>
    <ul aria-label="可加入研究笔记的问答">{rows.filter(row => row.status === 'SUCCEEDED').map(row => <li key={row.taskId}>
      <span>{row.question}</span>{' '}
      <button disabled={disabled || selected.length >= 10 || selected.some(entry => entry.task.taskId === row.taskId)} onClick={() => add(row.taskId)}>
        {selected.some(entry => entry.task.taskId === row.taskId) ? '已加入研究笔记' : '加入研究笔记'}
      </button>
    </li>)}</ul>
    <p>研究清单：{selected.length} / 10 条</p>
    <ol aria-label="研究笔记清单">{selected.map((entry, index) => <li key={entry.task.taskId}>
      <details><summary>{entry.question}</summary>
        <p>问答 ID：{entry.task.taskId}</p><p>检索关键词：{entry.query}</p>
        <p>资料范围：{entry.scope ? `${entry.scope.documentId} · 源版本 ${entry.scope.sourceRevision}` : '全部可用资料'}</p>
        <AnswerContent text={entry.task.result!.answer} />
        <ul>{entry.task.result!.citations.map((citation, i) => <li key={i}>{citation.title} · 源版本 {citation.sourceRevision} · 行 {citation.startLine}–{citation.endLine}</li>)}</ul>
      </details>
      <button aria-label={`上移第 ${index + 1} 条问答`} disabled={disabled || index === 0} onClick={() => move(index, -1)}>上移</button>
      <button aria-label={`下移第 ${index + 1} 条问答`} disabled={disabled || index === selected.length - 1} onClick={() => move(index, 1)}>下移</button>
      <button aria-label={`移除第 ${index + 1} 条问答`} disabled={disabled} onClick={() => { setSelected(current => current.filter(item => item.task.taskId !== entry.task.taskId)); setNotice(''); setError('') }}>移除</button>
    </li>)}</ol>
    <button disabled={disabled || !selected.length} onClick={save}>核对并导出研究笔记…</button>
    <p className="hint">保留各题原回答，不生成综合结论；任一回答或引用不可用时整份不导出。只写入新文件，不包含未发送草稿。清单在当前连接内跨页保留，关闭应用或重连后需要重新选择。</p>
    {busy && <p role="status">正在核对或等待原生保存窗口…</p>}
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert">{error}</p>}
    {saveUnknown && <button disabled={!enabled || !visible || busy} onClick={() => { setSaveUnknown(false); setError(''); setNotice('已记录检查完成；如需另存，请选择新的文件。') }}>我已检查保存位置和原生窗口</button>}
  </section>
}
