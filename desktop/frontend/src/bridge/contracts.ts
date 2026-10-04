export const nativeMethods = [
  'native.openBrowserPairing', 'native.openMemoryBackup', 'native.openWorkspaceBackup', 'native.openCredentialFlow',
] as const
export type NativeMethod = typeof nativeMethods[number]
export const businessMethods = ['assistant.selectMemories', 'assistant.submit', 'translate.submit', 'operations.get', 'operations.cancel', 'operations.copyResult'] as const
export const conversationMethods = ['conversations.list', 'conversations.get', 'conversations.create', 'conversations.rename', 'conversations.archive', 'conversations.unarchive', 'conversations.delete', 'conversations.selectMemories', 'conversations.clearMemories', 'conversations.send', 'conversations.cancelPending'] as const
export const memoryMethods = ['memory.list', 'memory.get', 'memory.create', 'memory.update', 'memory.archive', 'memory.restore', 'memory.delete', 'memory.editorState'] as const
export type Method = 'shell.bootstrap' | 'shell.refreshStatus' | NativeMethod | typeof businessMethods[number] | typeof conversationMethods[number] | typeof memoryMethods[number]
export interface MemoryRef { memoryId: string; revision: string; position: number }
export interface SelectedMemory extends MemoryRef { title: string }
export interface MemoryChoice { changed: boolean; selectedMemoryRefs: SelectedMemory[] }
export type TaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT'
export interface SafeError { code: string; message: string }
export interface OperationView { operationId: string; status: TaskStatus; result: string | null; error: SafeError | null }
export interface AssistantSubmit { mode: 'Ask' | 'Summarize'; text: string; selectedMemoryRefs: MemoryRef[] }
export const safeCodes = [
  'NATIVE_UNAVAILABLE', 'OPERATION_NOT_FOUND', 'OPERATION_CAPACITY', 'MEMORY_SELECTION_REQUIRED', 'CLIPBOARD_UNAVAILABLE',
  'RuntimeUnavailable', 'Unauthorized', 'CredentialMissing', 'CredentialInvalid', 'CredentialStorage', 'QueueFull',
  'ProviderUnavailable', 'ModelUnavailable', 'PolicyDenied', 'InvalidRequest', 'InvalidResponse', 'TaskNotFound',
  'Cancelled', 'TimedOut', 'ClientTimeout', 'ProviderResponseInvalid', 'InternalError', 'OutcomeUnknown',
  'MemorySelectionStale', 'MemoryAskBudget', 'MemoryStorageUnavailable', 'MemorySchemaUnsupported',
  'MemoryInvalid', 'MemoryNotFound', 'MemoryRevisionConflict', 'MemoryLimitExceeded',
  'ConversationNotFound', 'ConversationInvalid', 'ConversationConflict', 'ConversationLimitExceeded', 'ConversationStorageUnavailable',
] as const
export interface ShellStatus {
  bridgeVersion: 1
  applicationVersion: string
  runtime: 'Available' | 'Unavailable'
  credential: 'Ready' | 'Missing' | 'Invalid' | 'Unavailable'
  webView: 'Available'
  nativeEntries: NativeMethod[]
}
export interface WebViewPort {
  postMessage(message: unknown): void
  addEventListener(type: 'message', listener: (event: { data: unknown }) => void): void
  removeEventListener(type: 'message', listener: (event: { data: unknown }) => void): void
}
declare global { interface Window { chrome?: { webview?: WebViewPort } } }
export const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
export const exactFields = (value: Record<string, unknown>, fields: string[]) => Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
export const isId = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value) && value !== '00000000-0000-0000-0000-000000000000'
export function isSafeError(value: unknown): value is SafeError {
  return isObject(value) && exactFields(value, ['code', 'message']) && safeCodes.includes(value.code as typeof safeCodes[number])
    && typeof value.message === 'string' && value.message.length <= 320
}
export function isMemoryChoice(value: unknown): value is MemoryChoice {
  if (!isObject(value) || !exactFields(value, ['changed', 'selectedMemoryRefs']) || typeof value.changed !== 'boolean'
    || !Array.isArray(value.selectedMemoryRefs) || value.selectedMemoryRefs.length > 4
    || !value.changed && value.selectedMemoryRefs.length !== 0) return false
  const ids = new Set<string>()
  return value.selectedMemoryRefs.every((ref, position) => {
    if (!isObject(ref) || !exactFields(ref, ['memoryId', 'revision', 'position', 'title']) || !isId(ref.memoryId)
      || ids.has(ref.memoryId) || ref.position !== position || typeof ref.revision !== 'string'
      || !/^[1-9][0-9]{0,18}$/.test(ref.revision) || BigInt(ref.revision) > 9223372036854775807n
      || typeof ref.title !== 'string' || [...ref.title].length > 160) return false
    ids.add(ref.memoryId); return true
  }) && (!value.changed || value.selectedMemoryRefs.length > 0)
}
export function isOperation(value: unknown): value is OperationView {
  if (!isObject(value) || !exactFields(value, ['operationId', 'status', 'result', 'error']) || !isId(value.operationId)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(String(value.status))) return false
  if (value.status === 'SUCCEEDED') return typeof value.result === 'string' && value.result.trim().length > 0
    && new TextEncoder().encode(value.result).length <= 8192 && value.error === null
  if (value.result !== null) return false
  if (value.status === 'QUEUED' || value.status === 'RUNNING') return value.error === null
  return isSafeError(value.error) && (value.status === 'CANCELLED' ? value.error.code === 'Cancelled'
    : value.status === 'TIMED_OUT' ? value.error.code === 'TimedOut' : !['Cancelled', 'TimedOut'].includes(value.error.code))
}
export function isStatus(value: unknown): value is ShellStatus {
  return isObject(value) && exactFields(value, ['bridgeVersion', 'applicationVersion', 'runtime', 'credential', 'webView', 'nativeEntries'])
    && value.bridgeVersion === 1 && typeof value.applicationVersion === 'string' && /^\d+(\.\d+){2,3}$/.test(value.applicationVersion)
    && ['Available', 'Unavailable'].includes(String(value.runtime))
    && ['Ready', 'Missing', 'Invalid', 'Unavailable'].includes(String(value.credential)) && value.webView === 'Available'
    && Array.isArray(value.nativeEntries) && value.nativeEntries.length <= nativeMethods.length
    && new Set(value.nativeEntries).size === value.nativeEntries.length
    && value.nativeEntries.every(method => nativeMethods.includes(method as NativeMethod))
}
