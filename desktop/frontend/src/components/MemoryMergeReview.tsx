import { useEffect, useState } from 'react'
import { validDraft, type MemoryDraft, type MemoryItem } from '../bridge/memory'
import { prepareMemoryMerge, resolveMemoryMerge, type MemoryMerge } from './memoryMerge'

export function MemoryMergeReview({ base, draft, latest, enabled, apply }: {
  base: MemoryItem; draft: MemoryDraft; latest: MemoryItem; enabled: boolean; apply: (draft: MemoryDraft) => void
}) {
  const [merge, setMerge] = useState<MemoryMerge | null>(null)
  const [choices, setChoices] = useState<Record<string, string>>({}), [error, setError] = useState('')
  const inputs = JSON.stringify([base, draft, latest])
  const [preparedInputs, setPreparedInputs] = useState('')
  useEffect(() => { setMerge(null); setChoices({}); setError('') }, [inputs])
  const current = preparedInputs === inputs ? merge : null
  const fields = current ? [{ id: 'type', label: '记忆类型', part: current.type }, { id: 'title', label: '记忆标题', part: current.title },
    ...current.content.map((part, index) => ({ id: `content-${index}`, label: `正文片段 ${index + 1}`, part }))] : []
  const conflicts = fields.filter(field => field.part.result === null)
  const unresolved = conflicts.filter(field => choices[field.id] === undefined).length
  const result = current ? resolveMemoryMerge(current, choices) : null
  return <section className="memory-revision-review" aria-label="记忆冲突合并">
    <h3>合并本地与最新修改</h3>
    <p className="hint">以最初加载的版本 {base.revision} 为共同原文，对照本地草稿与已存版本 {latest.revision}。不同位置的修改可一起保留，重叠修改由你决定；文字合并不代表内容正确。</p>
    <button disabled={!enabled} onClick={() => {
      try { setMerge(prepareMemoryMerge(base, draft, latest)); setChoices({}); setPreparedInputs(inputs); setError('') }
      catch (problem) { setError(problem instanceof Error ? problem.message : '无法准备合并，草稿未改变。') }
    }}>准备三方合并</button>
    {error && <p role="alert">{error}</p>}
    {current && <>
      <p role="status">{conflicts.length === 0 ? '没有重叠修改，请核对完整合并结果。' : `共 ${conflicts.length} 处冲突，尚有 ${unresolved} 处待处理。`}</p>
      {conflicts.map(({ id, label, part }) => <section key={id} aria-label={`${label}冲突`}>
        <h4>{label}</h4>
        <details><summary>最初加载的原文</summary><pre dir="auto">{part.base || '（空）'}</pre></details>
        <p>本地修改</p><pre dir="auto">{part.local || '（删除此段）'}</pre>
        <p>最新已存修改</p><pre dir="auto">{part.latest || '（删除此段）'}</pre>
        <div className="operation-actions">
          <button disabled={!enabled} onClick={() => setChoices(value => ({ ...value, [id]: part.local }))}>保留本地修改</button>
          <button disabled={!enabled} onClick={() => setChoices(value => ({ ...value, [id]: part.latest }))}>采用最新修改</button>
        </div>
        {id !== 'type' && <label>{label}合并结果<textarea disabled={!enabled} value={choices[id] ?? ''} onChange={event => setChoices(value => ({ ...value, [id]: event.target.value }))} /></label>}
        <p className="hint">{choices[id] === undefined ? '尚未选择；可选择一边或手工填写保留的内容。' : '已处理，可继续修改。空内容表示删除这部分。'}</p>
      </section>)}
      {result && <section aria-label="完整合并预览">
        <h4>{result.title}</h4><p>{result.type === 'PREFERENCE' ? '个人偏好' : '项目笔记'}</p>
        <pre dir="auto">{result.content}</pre>
        {!validDraft(result) && <p role="alert">合并结果不符合标题或正文限制，请调整冲突内容或继续手工编辑。不会截断或采用。</p>}
      </section>}
      <button disabled={!enabled || !result || !validDraft(result)} onClick={() => { if (enabled && result && validDraft(result)) apply(result) }}>重新核对并采用合并草稿</button>
      <button disabled={!enabled} onClick={() => { setMerge(null); setChoices({}); setError('') }}>取消合并预览</button>
      <p className="hint">采用前再次核对已存版本，只更新编辑器；之后仍需点击“保存”。修改当前草稿或重新对照会使本次合并预览失效。</p>
    </>}
  </section>
}
