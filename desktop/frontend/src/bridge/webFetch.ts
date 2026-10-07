import { exactFields, isId, isObject, isSafeError } from './contracts'
import type { SafeError } from './contracts'

export const webFetchMethods = ['web.fetchSubmit', 'web.fetchGet', 'web.fetchCancel'] as const
export const webFetchCodes = ['WebDisabled', 'WebTargetInvalid', 'WebTargetNotPublic', 'WebDnsFailed', 'WebTlsFailed', 'WebTimeout', 'WebRedirectDenied', 'WebResponseTooLarge', 'WebContentTypeUnsupported', 'WebContentInvalid', 'WebFetchFailed', 'WebFetchNotFound'] as const
export interface WebFetchResult {
  requestedUrl: string; finalUrl: string; hostname: string; title: string; acquiredAt: string
  contentType: 'text/html' | 'text/plain' | 'application/xhtml+xml'; extractionVersion: 'web-extract-1'
  text: string; titleTruncated: boolean; textTruncated: boolean
}
export interface WebFetchOperation {
  operationId: string; state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  result: WebFetchResult | null; error: SafeError | null
}
export interface WebFetchSubmission {
  outcome: 'ACCEPTED' | 'CANCELLED' | 'UNKNOWN'; operationId: string | null; operation: WebFetchOperation | null
}
const unicode = (s: string) => !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(s)
const bytes = (s: string) => new TextEncoder().encode(s).length
const url = (v: unknown, host: string): v is string => typeof v === 'string' && v.length <= 2048
  && /^[\x21-\x7e]+$/.test(v) && v.startsWith(`https://${host}/`)
function isResult(v: unknown): v is WebFetchResult {
  if (!isObject(v) || !exactFields(v, ['requestedUrl', 'finalUrl', 'hostname', 'title', 'acquiredAt', 'contentType', 'extractionVersion', 'text', 'titleTruncated', 'textTruncated'])
    || typeof v.hostname !== 'string' || v.hostname.length > 253 || !/^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(v.hostname)) return false
  return url(v.requestedUrl, v.hostname) && url(v.finalUrl, v.hostname)
    && typeof v.title === 'string' && unicode(v.title) && [...v.title].length <= 160 && bytes(v.title) <= 640
    && typeof v.text === 'string' && unicode(v.text) && v.text.length <= 4096 && bytes(v.text) <= 8192
    && typeof v.acquiredAt === 'string' && v.acquiredAt.length <= 40 && Number.isFinite(Date.parse(v.acquiredAt))
    && ['text/html', 'text/plain', 'application/xhtml+xml'].includes(String(v.contentType)) && v.extractionVersion === 'web-extract-1'
    && typeof v.titleTruncated === 'boolean' && typeof v.textTruncated === 'boolean'
}
export function isWebFetchOperation(v: unknown): v is WebFetchOperation {
  if (!isObject(v) || !exactFields(v, ['operationId', 'state', 'result', 'error']) || !isId(v.operationId)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(String(v.state))) return false
  if (v.state === 'SUCCEEDED') return isResult(v.result) && v.error === null
  if (v.result !== null) return false
  if (v.state !== 'FAILED') return v.error === null
  return isSafeError(v.error) && [...webFetchCodes, 'Unauthorized', 'PolicyDenied', 'InvalidRequest', 'QueueFull', 'InternalError'].includes(v.error.code)
}
export function isWebFetchSubmission(v: unknown): v is WebFetchSubmission {
  if (!isObject(v) || !exactFields(v, ['outcome', 'operationId', 'operation'])) return false
  if (v.outcome === 'CANCELLED') return v.operationId === null && v.operation === null
  if (!isId(v.operationId)) return false
  return v.outcome === 'UNKNOWN' ? v.operation === null
    : v.outcome === 'ACCEPTED' && isWebFetchOperation(v.operation) && v.operation.operationId === v.operationId
}
