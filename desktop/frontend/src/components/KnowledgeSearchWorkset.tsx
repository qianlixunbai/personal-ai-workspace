import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import { validWorksetInput, type KnowledgeWorkset } from '../bridge/knowledgeWorkset'
import type { KnowledgeSearchSource } from './KnowledgeSearchScopePicker'
import { ConfirmationDialog } from './ConfirmationDialog'
import { reviewKnowledgeWorkset, worksetDraftKey, type WorksetReviewRow } from './knowledgeWorksetReview'

export function KnowledgeSearchWorkset({ bridge, query, sources, enabled, visible, corpusEpoch, restore }: {
  bridge: WorkspaceClient; query: string; sources: KnowledgeSearchSource[] | null; enabled: boolean; visible: boolean; corpusEpoch: number
  restore: (query: string, sources: KnowledgeSearchSource[], expected: string) => boolean
}) {
  const [busy, setBusy] = useState(false), [notice, setNotice] = useState(''), [error, setError] = useState('')
  const [review, setReview] = useState<{ workset: KnowledgeWorkset; rows: WorksetReviewRow[]; original: string } | null>(null)
  const generation = useRef(0), viewGeneration = useRef(0), lock = useRef(false)
  const current = useRef({ available: enabled && visible, key: worksetDraftKey(query, sources) })
  current.current = { available: enabled && visible, key: worksetDraftKey(query, sources) }
  useEffect(() => {
    const off = bridge.onSession(() => { generation.current++; lock.current = false; setBusy(false); setReview(null); setNotice(''); setError('') })
    return () => { generation.current++; off() }
  }, [bridge])
  useEffect(() => { if (!enabled || !visible) { viewGeneration.current++; setReview(null) } }, [enabled, visible])
  useEffect(() => { viewGeneration.current++; setReview(null) }, [corpusEpoch])
  const canSave = sources !== null && !sources.some(source => source.unavailableReason) && validWorksetInput(query, sources.map(source => source.scope))
  const fileAction = async (saving: boolean) => {
    if (!current.current.available || lock.current || saving && !canSave) return
    const token = ++generation.current, view = viewGeneration.current, original = current.current.key
    const deadline = Date.now() + 330_000
    lock.current = true; setBusy(true); setNotice(''); setError(''); setReview(null)
    const check = () => {
      if (token !== generation.current || !current.current.available || view !== viewGeneration.current || original !== current.current.key)
        throw new Error('当前检索或页面已变化，未恢复文件内容。请重新打开工作集。')
      if (Date.now() > deadline) throw new Error('工作集核对已超时，当前检索保持原样。')
    }
    try {
      if (saving) {
        const result = await bridge.saveKnowledgeSearchWorkset(query, sources!.map(source => ({ ...source.scope })))
        if (token !== generation.current) return
        setNotice(result.outcome === 'SAVED' ? '检索工作集已保存为新文件。后续编辑不会更新这份快照。' : '已取消保存，当前检索保持原样。')
      } else {
        const result = await bridge.openKnowledgeSearchWorkset(); check()
        if (result.outcome === 'CANCELLED') { setNotice('已取消打开，当前检索保持原样。'); return }
        const reviewDeadline = Date.now() + 30_000
        const reviewCheck = () => { check(); if (Date.now() > reviewDeadline) throw new Error('资料核对超时，当前检索保持原样，请稍后重新打开文件。') }
        const rows = await reviewKnowledgeWorkset(bridge, result.workset, reviewCheck); check()
        setReview({ workset: result.workset, rows, original })
      }
    } catch (problem) {
      if (token === generation.current) setError(saving && problem instanceof BridgeError && ['OutcomeUnknown', 'KnowledgeWorksetSaveUnknown'].includes(problem.code)
        ? '保存结果未确认，请先检查所选位置；不会自动重试或覆盖文件，当前检索仍保留。'
        : problem instanceof Error ? problem.message : '工作集文件操作未完成，当前检索保持原样。')
    } finally { if (token === generation.current) { lock.current = false; setBusy(false) } }
  }
  return <details className="input-details">
    <summary>保存或恢复检索工作集</summary>
    <p className="hint">手动保存关键词及最多 10 份明确资料版本为明文文件，供关闭应用后重新打开。只创建新文件，不覆盖旧文件；不包含资料正文、命中片段、问答或在途任务，不是自动保存或完整备份。</p>
    <button disabled={!enabled || !visible || busy || !canSave} onClick={() => { void fileAction(true) }}>保存检索工作集…</button>
    <button disabled={!enabled || !visible || busy} onClick={() => { void fileAction(false) }}>打开检索工作集…</button>
    {busy && <p role="status">正在处理工作集文件或核对资料…</p>}
    {notice && <p role="status">{notice}</p>}{error && <p role="alert">{error}</p>}
    {review && <ConfirmationDialog title="恢复这份检索工作集？" confirmText="替换检索清单，不执行查询" cancel={() => setReview(null)} confirm={() => {
      if (!current.current.available || !restore(review.workset.query, review.rows.map(row => row.source), review.original)) {
        setError('当前检索已变化，未覆盖新的内容。请重新打开工作集。'); setReview(null); return
      }
      setReview(null); setNotice('检索清单已恢复，尚未发起查询。请处理不可用项目，再明确点击检索。')
    }}>
      <p>保存时间：{new Date(review.workset.savedAt).toLocaleString()}。替换当前关键词、范围和检索结果；既有导入和问答任务不变。</p>
      <p>当前关键词：<bdi>{query || '（空）'}</bdi>；将恢复：<bdi>{review.workset.query || '（空）'}</bdi></p>
      <ul>{review.rows.map(row => <li key={row.source.scope.documentId}><bdi>{row.source.title}</bdi> · 源版本 {row.source.scope.sourceRevision}<p>{row.note}</p></li>)}</ul>
      <p>文件中的名称和定位可编辑，不是来源证明。核对仅使用当前资料配置；未通过项目会留在范围中并阻止查询，需明确更新版本或移除，不会自动扩大范围。恢复不自动查询、问答、上传或重放任务。</p>
    </ConfirmationDialog>}
  </details>
}
