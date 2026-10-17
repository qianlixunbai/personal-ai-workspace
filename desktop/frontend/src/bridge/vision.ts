import { exactFields, isId, isObject, isSafeError } from './contracts'
export const visionMethods = ['vision.readiness', 'vision.pick', 'vision.validate', 'vision.submit', 'vision.get', 'vision.cancel', 'vision.clear'] as const
export interface VisionReadiness { providerDeclaredVision: boolean; runtimeValidatedText: boolean; workspaceVisionExecutionValidated: boolean; available: boolean; selectionRevision: number; reason: string | null }
export interface VisionPick { changed: boolean; handle: string | null; mediaType: string | null; width: number; height: number }
export interface VisionTask { taskId: string; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'UNKNOWN'; result: string | null; error: { code: string; message: string } | null }
export interface VisionValidation { state: 'CANCELLED' | 'COMPLETED' | 'UNKNOWN'; readiness: VisionReadiness | null }
export function isVisionReadiness(v: unknown): v is VisionReadiness {
  return isObject(v) && exactFields(v, ['providerDeclaredVision', 'runtimeValidatedText', 'workspaceVisionExecutionValidated', 'available', 'selectionRevision', 'reason'])
    && typeof v.providerDeclaredVision === 'boolean' && typeof v.runtimeValidatedText === 'boolean' && typeof v.workspaceVisionExecutionValidated === 'boolean' && typeof v.available === 'boolean'
    && Number.isSafeInteger(v.selectionRevision) && (v.selectionRevision as number) >= 0
    && v.available === v.workspaceVisionExecutionValidated && (!v.available || v.providerDeclaredVision && v.runtimeValidatedText)
    && (v.reason === null ? v.available : !v.available && ['TEXT_UNAVAILABLE', 'MODEL_UNSUPPORTED', 'TOKEN_EVIDENCE_MISSING', 'VISION_UNVERIFIED'].includes(v.reason as string))
}
export function isVisionPick(v: unknown): v is VisionPick {
  return isObject(v) && exactFields(v, ['changed', 'handle', 'mediaType', 'width', 'height'])
    && (v.changed === false ? v.handle === null && v.mediaType === null && v.width === 0 && v.height === 0
      : v.changed === true && isId(v.handle) && ['image/png', 'image/jpeg'].includes(v.mediaType as string)
        && Number.isInteger(v.width) && Number.isInteger(v.height) && (v.width as number) > 0 && (v.height as number) > 0
        && (v.width as number) <= 4096 && (v.height as number) <= 4096 && (v.width as number) * (v.height as number) <= 8_000_000)
}
export function isVisionTask(v: unknown): v is VisionTask {
  if (!isObject(v) || !exactFields(v, ['taskId', 'status', 'result', 'error']) || !isId(v.taskId)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'UNKNOWN'].includes(v.status as string)) return false
  if (v.status === 'SUCCEEDED') return typeof v.result === 'string' && !!v.result.trim() && new TextEncoder().encode(v.result).length <= 8192 && v.error === null
  return v.result === null && (['QUEUED', 'RUNNING'].includes(v.status as string) ? v.error === null : isSafeError(v.error))
}
export function isVisionValidation(v: unknown): v is VisionValidation {
  return isObject(v) && exactFields(v, ['state', 'readiness']) && ['CANCELLED', 'COMPLETED', 'UNKNOWN'].includes(v.state as string)
    && (v.readiness === null ? v.state !== 'COMPLETED' : isVisionReadiness(v.readiness))
}
