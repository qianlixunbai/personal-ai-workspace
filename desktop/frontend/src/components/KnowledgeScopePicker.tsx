import type { KnowledgeDocument } from '../bridge/knowledge'
import type { KnowledgeScope } from '../bridge/knowledgeScope'

export function KnowledgeScopePicker({ scope, title, current, disabled, change }: {
  scope: KnowledgeScope | null; title: string; current?: KnowledgeDocument | undefined; disabled: boolean;
  change: (scope: KnowledgeScope | null, title: string) => void
}) {
  const available = current?.status === 'ACTIVE' && current.currentReadyRevision !== null
  return <div role="group" aria-label="资料范围">
    <p>资料范围：{scope ? `${title || scope.documentId} · 源版本 ${scope.sourceRevision}` : '全部可用资料'}</p>
    <button type="button" disabled={disabled || !available} onClick={() => {
      if (current && current.currentReadyRevision) change({ documentId: current.documentId, sourceRevision: current.currentReadyRevision }, current.title)
    }}>限定为当前文档</button>
    <button type="button" disabled={disabled || scope === null} onClick={() => change(null, '')}>使用全部资料</button>
    <p className="hint">从文档列表选择资料后，可明确限定范围。范围固定到所选版本；更新、归档或删除后需重新选择，不会自动扩大范围。</p>
  </div>
}
