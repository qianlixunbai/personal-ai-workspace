import { exactFields, isId, isObject } from './contracts'

export interface KnowledgeScope { documentId: string; sourceRevision: string }
export function isKnowledgeScope(v: unknown): v is KnowledgeScope | null {
  return v === null || isObject(v) && exactFields(v, ['documentId', 'sourceRevision']) && isId(v.documentId)
    && typeof v.sourceRevision === 'string' && /^(?:[1-9]|10)$/.test(v.sourceRevision)
}
