export const nativeMethods = [
  'native.openLegacyAssistant', 'native.openConversations', 'native.openMemory',
  'native.openBrowserPairing', 'native.openMemoryBackup', 'native.openWorkspaceBackup', 'native.openCredentialFlow',
] as const
export type NativeMethod = typeof nativeMethods[number]
export type Method = 'shell.bootstrap' | 'shell.refreshStatus' | NativeMethod
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
export function isStatus(value: unknown): value is ShellStatus {
  return isObject(value) && exactFields(value, ['bridgeVersion', 'applicationVersion', 'runtime', 'credential', 'webView', 'nativeEntries'])
    && value.bridgeVersion === 1 && typeof value.applicationVersion === 'string' && /^\d+(\.\d+){2,3}$/.test(value.applicationVersion)
    && ['Available', 'Unavailable'].includes(String(value.runtime))
    && ['Ready', 'Missing', 'Invalid', 'Unavailable'].includes(String(value.credential)) && value.webView === 'Available'
    && Array.isArray(value.nativeEntries) && value.nativeEntries.length <= nativeMethods.length
    && new Set(value.nativeEntries).size === value.nativeEntries.length
    && value.nativeEntries.every(method => nativeMethods.includes(method as NativeMethod))
}
