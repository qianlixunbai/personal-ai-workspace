import { expect, it } from 'vitest'
import { isKnowledgeSearchResult, isKnowledgeSearchStatus } from './knowledgeSearch'
import { KnowledgeService } from '../test/knowledgeFixtures'

it('accepts only minimal bounded lexical hits and rejects internal/old fields', () => {
  const hit = new KnowledgeService().hit(); expect(isKnowledgeSearchResult({ hits: [hit] })).toBe(true)
  for (const field of ['sourceDigest', 'representationDigest', 'corpusFingerprint', 'tokens', 'score', 'rowid', 'path']) expect(isKnowledgeSearchResult({ hits: [{ ...hit, [field]: 'private' }] })).toBe(false)
  expect(isKnowledgeSearchResult({ hits: Array(11).fill(hit) })).toBe(false); expect(isKnowledgeSearchResult({ hits: [hit, hit] })).toBe(false)
  expect(isKnowledgeSearchResult({ hits: [{ ...hit, snippet: 'x'.repeat(385) }] })).toBe(false)
})
it('requires Unicode-safe snippet-relative ordered highlights and exact status fields', () => {
  const hit = { ...new KnowledgeService().hit(), snippet: '😀budget', highlightRanges: [{ start: 0, end: 2 }] }
  expect(isKnowledgeSearchResult({ hits: [hit] })).toBe(true)
  for (const highlightRanges of [[{ start: 1, end: 2 }], [{ start: 0, end: 1 }], [{ start: 0, end: 9 }], [{ start: 2, end: 4 }, { start: 3, end: 5 }]]) expect(isKnowledgeSearchResult({ hits: [{ ...hit, highlightRanges }] })).toBe(false)
  expect(isKnowledgeSearchStatus({ state: 'BUILDING', indexedDocuments: 0, indexedChunks: 0 })).toBe(true)
  expect(isKnowledgeSearchStatus({ state: 'READY', indexedDocuments: 1, indexedChunks: 2, fingerprint: 'secret' })).toBe(false)
  expect(isKnowledgeSearchStatus({ state: 'STALE', indexedDocuments: 1, indexedChunks: 2 })).toBe(false)
})
