import { useCallback, useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import { validContent } from '../bridge/memory'
import { checkCandidateDuplicates } from './memoryCandidate'
import type { CandidateDuplicateCheck } from './memoryCandidate'

// Optional, read-only review for the editor's exact current body. Saving remains
// the existing explicit action; this check never merges, rewrites, or persists.
export function MemoryDraftDuplicateCheck({ bridge, enabled, content, openMemory }: {
  bridge: WorkspaceClient; enabled: boolean; content: string; openMemory: (id: string) => void
}) {
  const [result, setResult] = useState<CandidateDuplicateCheck | null>(null)
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)
  const generation = useRef(0)
  const locked = useRef(false)
  const clear = useCallback(() => {
    generation.current++; locked.current = false
    setResult(null); setError(''); setChecking(false)
  }, [])
  useEffect(() => {
    clear()
    return () => { generation.current++ }
  }, [bridge, content, clear])
  useEffect(() => bridge.onSession(clear), [bridge, clear])
  useEffect(() => {
    if (!enabled && locked.current) {
      generation.current++; locked.current = false; setChecking(false)
      setError('检查已中断，不能判断是否已有相同正文。请重新检查。')
    }
  }, [enabled])

  const check = async () => {
    if (!enabled || locked.current || !validContent(content)) return
    const token = ++generation.current
    locked.current = true; setChecking(true); setResult(null); setError('')
    try {
      const found = await checkCandidateDuplicates(bridge, content, () => token === generation.current)
      if (token === generation.current) setResult(found)
    } catch {
      if (token === generation.current) setError('未能完成重复检查，不能判断是否已有相同正文。请重新检查。')
    } finally {
      if (token === generation.current) { locked.current = false; setChecking(false) }
    }
  }

  return <section className="input-details" aria-label="草稿重复检查" aria-busy={checking}>
    <button type="button" disabled={!enabled || checking || !validContent(content)} onClick={() => { void check() }}>检查草稿是否重复</button>
    <p className="hint">可选检查，不调用模型。只比较使用中的记忆正文，最多核对 20 条匹配记录；不检查语义相似或已归档记忆。编辑正文后需重新检查。</p>
    {checking && <p role="status">正在检查草稿正文…</p>}
    {!checking && !result && !error && <p className="hint">尚未检查重复。检查不会保存草稿。</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {result && <>
      {result.matches.length > 0 ? <><p>已找到正文完全相同的记忆：</p><ul>{result.matches.map(item => <li key={item.id}>
        <bdi>{item.title}</bdi> · {item.type === 'PREFERENCE' ? '个人偏好' : '项目笔记'} · 版本 {item.revision}{' '}
        <button type="button" disabled={!enabled || checking} onClick={() => { if (enabled && !locked.current) openMemory(item.id) }}>打开已有记忆：{item.title}</button>
      </li>)}</ul></>
        : <p>本次已核对的 {result.checked} 条记录中，未发现正文完全相同项。</p>}
      {!result.complete && <p role="alert" className="error">检查不完整：存在未检查、已变更或读取失败的记录，不能据此排除重复。</p>}
      <p className="hint">搜索返回 {result.total} 条匹配记录，本次核对 {result.checked} 条。结果只对应检查时的正文；不会自动合并、覆盖或保存。</p>
    </>}
  </section>
}
