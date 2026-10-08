import { exactFields, isId, isObject, isSafeError } from './contracts'
import type { SafeError } from './contracts'

export const modelMethods = ['models.inspect', 'models.inspectRecovery', 'models.mutate'] as const
export const modelActions = ['SWITCH', 'RELEASE_OLD_THEN_SWITCH', 'RELEASE', 'VALIDATE', 'RECOVER'] as const
export type ModelAction = typeof modelActions[number]
export const modelCodes = ['ModelSwitchConflict', 'ModelSelectionRevisionConflict', 'ModelExecutionUncertain', 'ModelStateUnavailable', 'ModelConfigurationInvalid', 'ModelIdentityChanged', 'ModelCatalogStale', 'ModelCatalogLimitExceeded'] as const
const runtimeCodes = ['MODEL_SWITCH_CONFLICT', 'MODEL_SELECTION_REVISION_CONFLICT', 'MODEL_EXECUTION_UNCERTAIN', 'MODEL_STATE_UNAVAILABLE', 'MODEL_CONFIGURATION_INVALID', 'MODEL_IDENTITY_CHANGED', 'MODEL_CATALOG_STALE', 'MODEL_CATALOG_LIMIT_EXCEEDED', 'MODEL_UNAVAILABLE', 'PROVIDER_UNAVAILABLE', 'POLICY_DENIED', 'INVALID_REQUEST', 'PROVIDER_RESPONSE_INVALID', 'INTERNAL_ERROR', 'TASK_TIMEOUT', 'TASK_CANCELLED', 'QUEUE_FULL']
export type ModelFact = 'TRUE' | 'FALSE' | 'UNKNOWN'
export interface ModelCatalogEntry { handle: string; model: string; digest: string; contextLimit: number; completion: true; localSourceVerified: true; providerDeclaredVision: boolean }
export interface ModelCatalog { selectionRevision: number; models: ModelCatalogEntry[] }
export interface ModelStatus {
  configuredModel: string | null; configuredDigest: string | null; activeModel: string | null; activeDigest: string | null
  selectionRevision: number; installed: ModelFact; loaded: ModelFact; ready: boolean; reserved: number; queued: number; executing: number; draining: number
  switching: boolean; uncertain: boolean; recoveryGeneration: string; validationRequired: boolean; error: string | null
}
export interface ModelSnapshot { status: ModelStatus; catalog: ModelCatalog | null; error: SafeError | null }
export interface ModelOutcome { outcome: 'COMPLETED' | 'CANCELLED' | 'UNKNOWN'; snapshot: ModelSnapshot | null }
export interface ModelRequest { action: ModelAction; handle: string; expectedSelectionRevision: number; recoveryGeneration: string | null }
const revision = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const name = (value: unknown): value is string => typeof value === 'string' && value.length <= 256 && /^[a-z0-9][a-z0-9._-]*(\/[a-z0-9][a-z0-9._-]*)?:[a-z0-9][a-z0-9._-]*$/.test(value)
  && !value.includes('..') && !value.endsWith(':cloud') && !value.endsWith('-cloud') && !value.endsWith(':local')
const digest = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
export function isModelRequest(value: unknown): value is ModelRequest {
  return isObject(value) && exactFields(value, ['action', 'handle', 'expectedSelectionRevision', 'recoveryGeneration'])
    && modelActions.includes(value.action as ModelAction) && isId(value.handle) && revision(value.expectedSelectionRevision)
    && (value.action === 'RECOVER' ? isId(value.recoveryGeneration) : value.recoveryGeneration === null)
}
export function isModelStatus(value: unknown): value is ModelStatus {
  if (!isObject(value) || !exactFields(value, ['configuredModel', 'configuredDigest', 'activeModel', 'activeDigest', 'selectionRevision', 'installed', 'loaded', 'ready', 'reserved', 'queued', 'executing', 'draining', 'switching', 'uncertain', 'recoveryGeneration', 'validationRequired', 'error'])) return false
  return (value.configuredModel === null ? value.configuredDigest === null : name(value.configuredModel) && (value.configuredDigest === null || digest(value.configuredDigest)))
    && (value.activeModel === null ? value.activeDigest === null : name(value.activeModel) && digest(value.activeDigest)) && revision(value.selectionRevision)
    && ['installed', 'loaded'].every(field => ['TRUE', 'FALSE', 'UNKNOWN'].includes(String(value[field])))
    && ['ready', 'switching', 'uncertain', 'validationRequired'].every(field => typeof value[field] === 'boolean')
    && ['reserved', 'queued', 'executing', 'draining'].every(field => typeof value[field] === 'number' && Number.isInteger(value[field]) && (value[field] as number) >= 0 && (value[field] as number) <= 34)
    && isId(value.recoveryGeneration) && (value.error === null || typeof value.error === 'string' && runtimeCodes.includes(value.error))
    && (!value.ready || value.activeModel !== null && !value.switching && !value.uncertain && !value.validationRequired)
}
export function isModelCatalog(value: unknown): value is ModelCatalog {
  if (!isObject(value) || !exactFields(value, ['selectionRevision', 'models']) || !revision(value.selectionRevision) || !Array.isArray(value.models) || value.models.length > 64) return false
  const handles = new Set<string>(), names = new Set<string>()
  return value.models.every(item => {
    if (!isObject(item) || !exactFields(item, ['handle', 'model', 'digest', 'contextLimit', 'completion', 'localSourceVerified', 'providerDeclaredVision'])
      || !isId(item.handle) || !name(item.model) || !digest(item.digest) || !revision(item.contextLimit) || item.contextLimit < 1
      || item.completion !== true || item.localSourceVerified !== true || typeof item.providerDeclaredVision !== 'boolean'
      || handles.has(item.handle) || names.has(item.model)) return false
    handles.add(item.handle); names.add(item.model); return true
  })
}
export function isModelSnapshot(value: unknown): value is ModelSnapshot {
  return isObject(value) && exactFields(value, ['status', 'catalog', 'error']) && isModelStatus(value.status)
    && (value.catalog === null || isModelCatalog(value.catalog) && value.catalog.selectionRevision === value.status.selectionRevision)
    && (value.error === null || isSafeError(value.error))
}
export function isModelOutcome(value: unknown): value is ModelOutcome {
  return isObject(value) && exactFields(value, ['outcome', 'snapshot']) && (value.outcome === 'CANCELLED' ? value.snapshot === null
    : value.outcome === 'COMPLETED' ? isModelSnapshot(value.snapshot) : value.outcome === 'UNKNOWN' && (value.snapshot === null || isModelSnapshot(value.snapshot)))
}
