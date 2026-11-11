import type { KnowledgeDocument } from '../bridge/knowledge'
import type { KnowledgeScope } from '../bridge/knowledgeScope'

export interface KnowledgeSearchSource { scope: KnowledgeScope; title: string }
export const maximumSearchSources = 10

export function KnowledgeSearchScopePicker({ sources, current, disabled, change }: {
  sources: KnowledgeSearchSource[] | null; current?: KnowledgeDocument | undefined; disabled: boolean;
  change: (sources: KnowledgeSearchSource[] | null) => void
}) {
  const available = current?.status === 'ACTIVE' && current.currentReadyRevision !== null
  const chosen = sources?.find(source => source.scope.documentId === current?.documentId)
  const source = (): KnowledgeSearchSource | null => available && current?.currentReadyRevision
    ? { scope: { documentId: current.documentId, sourceRevision: current.currentReadyRevision }, title: current.title } : null
  return <div role="group" aria-label="资料范围">
    <p>{sources?.length === 1 ? `资料范围：${sources[0]!.title} · 源版本 ${sources[0]!.scope.sourceRevision}`
      : `检索范围：${sources === null ? '全部可用资料' : `已选择 ${sources.length} / ${maximumSearchSources} 份资料`}`}</p>
    <div className="conversation-actions">
      <button type="button" disabled={disabled || !available} onClick={() => { const next = source(); if (next) change([next]) }}>限定为当前文档</button>
      <button type="button" disabled={disabled || !available || chosen?.scope.sourceRevision === current?.currentReadyRevision
        || !chosen && (sources?.length ?? 0) >= maximumSearchSources} onClick={() => {
        const next = source(); if (!next) return
        change(chosen ? sources!.map(item => item.scope.documentId === next.scope.documentId ? next : item) : [...(sources ?? []), next])
      }}>{chosen ? '更新当前资料的所选版本' : '加入检索范围'}</button>
      <button type="button" disabled={disabled || sources === null} onClick={() => change(null)}>使用全部资料</button>
    </div>
    {sources !== null && <ul aria-label="已选检索资料">{sources.map(item => <li key={item.scope.documentId}>
      <bdi>{item.title}</bdi> · 源版本 {item.scope.sourceRevision}
      <button type="button" aria-label={`移出检索范围：${item.title}`} disabled={disabled} onClick={() => change(sources.filter(value => value.scope.documentId !== item.scope.documentId))}>移除</button>
    </li>)}</ul>}
    <p className="hint">从文档列表打开资料，再加入范围；每份固定到明确选择的版本。更新、归档或删除后须重新选择，不会自动改用其他版本或全部资料。知识问答的范围仍单独选择。</p>
    {sources?.length === 0 && <p>范围已清空。请加入资料，或明确选择“使用全部资料”后再检索。</p>}
  </div>
}
