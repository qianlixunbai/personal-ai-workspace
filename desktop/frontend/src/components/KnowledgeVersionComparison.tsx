import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeDetail } from '../bridge/knowledge'
import { compareSources, readComparisonSource, type ComparisonPart } from './knowledgeComparison'

export function KnowledgeVersionComparison({ bridge, detail, enabled, disabled, open }: {
  bridge: WorkspaceClient; detail: KnowledgeDetail; enabled: boolean; disabled: boolean
  open: (revision: string, offset: number) => Promise<void>
}) {
  const revisions = [...detail.revisions].sort((a, b) => Number(a.sourceRevision) - Number(b.sourceRevision))
  const [before, setBefore] = useState(revisions.at(-2)?.sourceRevision ?? '')
  const [after, setAfter] = useState(revisions.at(-1)?.sourceRevision ?? '')
  const [parts, setParts] = useState<ComparisonPart[] | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const epoch = useRef(0), running = useRef(false), cancelled = useRef(false)
  useEffect(() => () => { epoch.current++ }, [])
  useEffect(() => {
    if (!enabled) { epoch.current++; running.current = false; setBusy(false); setParts(null); setNotice('') }
  }, [enabled])
  const compare = async () => {
    if (!enabled || disabled || running.current || !before || !after || before === after) return
    const request = ++epoch.current, deadline = Date.now() + 30_000
    const check = () => {
      if (request !== epoch.current || cancelled.current) throw new Error('已停止对比。')
      if (Date.now() > deadline) throw new Error('读取原文超时，请稍后重新对比。未显示部分结果。')
    }
    running.current = true; cancelled.current = false; setBusy(true); setParts(null); setError(''); setNotice('正在读取两个完整版本…')
    try {
      const oldText = await readComparisonSource(bridge, detail.document.documentId, before, check)
      const newText = await readComparisonSource(bridge, detail.document.documentId, after, check)
      check()
      const result = await compareSources(oldText, newText)
      check(); setParts(result); setNotice('两个版本已完整读取，对比完成。')
    } catch (e) { if (request === epoch.current) { setError(cancelled.current ? '' : e instanceof Error ? e.message : '对比暂时不可用。'); setNotice(cancelled.current ? '已停止对比。' : '') } }
    finally { if (request === epoch.current) { running.current = false; setBusy(false) } }
  }
  const change = (value: string, side: 'before' | 'after') => {
    epoch.current++; setParts(null); setError(''); setNotice('')
    if (side === 'before') setBefore(value); else setAfter(value)
  }
  if (revisions.length < 2) return null
  const added = parts?.filter(p => p.added).reduce((sum, p) => sum + p.count, 0) ?? 0
  const removed = parts?.filter(p => p.removed).reduce((sum, p) => sum + p.count, 0) ?? 0
  return <details className="knowledge-comparison advanced-section">
    <summary>对比源版本</summary>
    <p className="hint">按行核对已保存的文本，保留空格与换行差异。Word 比较的是已确认的正文摘录，包含来源说明；不比较图片、版式或原文件。旧问答仍引用原版本。</p>
    <div className="knowledge-reader-tools">
      <label>基准版本<select value={before} disabled={!enabled || disabled || busy} onChange={e => change(e.target.value, 'before')}>{revisions.map(r => <option key={r.sourceRevision} value={r.sourceRevision}>版本 {r.sourceRevision}</option>)}</select></label>
      <label>目标版本<select value={after} disabled={!enabled || disabled || busy} onChange={e => change(e.target.value, 'after')}>{revisions.map(r => <option key={r.sourceRevision} value={r.sourceRevision}>版本 {r.sourceRevision}</option>)}</select></label>
      <button disabled={!enabled || disabled || busy || !before || !after || before === after} onClick={() => { void compare() }}>开始对比</button>
      {busy && <button onClick={() => { cancelled.current = true; setNotice('正在停止，等待读取结束…') }}>停止对比</button>}
    </div>
    <p className="hint">适合中短篇资料。文档过长、分节过多或差异过大时，请分别预览原文。</p>
    <p role="status">{notice}</p>
    {error && <p className="error" role="alert">{error}</p>}
    {parts && <section aria-label="版本对比结果">
      <p>版本 {before} → 版本 {after} · 新增 {added} 行，删除 {removed} 行{added === 0 && removed === 0 ? ' · 文本完全一致' : ''}</p>
      <p className="hint">替换显示为删除旧行与新增新行；移动也可能这样显示。</p>
      {parts.map((part, index) => {
        const revision = part.removed ? before : after, offset = part.removed ? part.oldOffset : part.newOffset
        const content = <><p>{part.removed ? '− 删除' : part.added ? '+ 新增' : '= 未变'} · 版本 {revision} · 从第 {part.removed ? part.oldLine : part.newLine} 行起 · {part.count} 行</p>
          <pre tabIndex={0}>{part.value}</pre>{!part.value.endsWith('\n') && <p className="hint">此段末尾没有换行符。</p>}
          <button disabled={!enabled || disabled || busy} onClick={() => { void open(revision, offset) }}>打开版本 {revision} 第 {part.removed ? part.oldLine : part.newLine} 行原文</button></>
        return part.added || part.removed ? <div className="knowledge-comparison-change" key={index}>{content}</div> : <details key={index}><summary>未变的 {part.count} 行（展开查看）</summary>{content}</details>
      })}
    </section>}
  </details>
}
