import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeDocument } from '../bridge/knowledge'
import type { KnowledgeWorkset } from '../bridge/knowledgeWorkset'
import type { KnowledgeSearchSource } from './KnowledgeSearchScopePicker'

export interface WorksetReviewRow { source: KnowledgeSearchSource; note: string }
export const worksetDraftKey = (query: string, sources: KnowledgeSearchSource[] | null) => JSON.stringify({ query, sources })

export async function reviewKnowledgeWorkset(bridge: WorkspaceClient, workset: KnowledgeWorkset, check: () => void): Promise<WorksetReviewRow[]> {
  const found = new Map<string, KnowledgeDocument>(), wanted = new Set(workset.sources.map(source => source.documentId))
  let listFailure = ''
  // File IDs are untrusted. First use the existing paginated list to obtain current
  // document authority; never request arbitrary IDs solely because a file names them.
  try {
    for (const status of ['ACTIVE', 'ARCHIVED'] as const) {
      for (let page = 0; page < 25 && found.size < wanted.size; page++) {
        check(); const result = await bridge.listKnowledge(status, page); check()
        for (const document of result.items) if (wanted.has(document.documentId)) found.set(document.documentId, document)
        if ((page + 1) * result.limit >= result.total) break
      }
    }
  } catch (problem) { check(); listFailure = problem instanceof Error ? problem.message : '资料列表暂时不可用。' }
  const rows: WorksetReviewRow[] = []
  for (const saved of workset.sources) {
    check()
    const source: KnowledgeSearchSource = { title: saved.title, scope: { documentId: saved.documentId, sourceRevision: saved.sourceRevision } }
    const document = found.get(saved.documentId)
    let reason = '', note = ''
    if (!document) reason = listFailure ? `尚未完成资料核对：${listFailure}` : '当前资料列表中未找到这份文档，可能已删除或属于其他资料配置。'
    else if (document.createdAt !== saved.createdAt) reason = '文档创建信息与文件不符，未按相同文档恢复访问。'
    else {
      try {
        check(); const detail = await bridge.getKnowledge(saved.documentId); check()
        if (detail.document.createdAt !== saved.createdAt) reason = '文档身份在核对期间发生变化。'
        else if (!detail.revisions.some(revision => revision.sourceRevision === saved.sourceRevision)) reason = `保存的源版本 ${saved.sourceRevision} 已不可用。`
        else if (detail.document.status !== 'ACTIVE') reason = '文档已归档，原版本保留；需先明确恢复文档或移出范围。'
        else if (detail.document.currentReadyRevision !== saved.sourceRevision) reason = `当前可用版本为 ${detail.document.currentReadyRevision ?? '无'}，保存的版本 ${saved.sourceRevision} 不会自动替换。`
        else {
          source.title = detail.document.title
          note = detail.document.title === saved.title ? '文档身份和所选版本核对通过。' : `身份和版本核对通过；名称已由“${saved.title}”变为“${detail.document.title}”。`
        }
      } catch (problem) { check(); reason = problem instanceof Error ? problem.message : '文档版本暂时无法核对。' }
    }
    if (reason) source.unavailableReason = reason
    rows.push({ source, note: reason || note })
  }
  return rows
}
