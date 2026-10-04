import type { ShellStatus, WebViewPort } from '../bridge/contracts'
export const status: ShellStatus = { bridgeVersion: 1, applicationVersion: '1.0.0.0', runtime: 'Available', credential: 'Ready', webView: 'Available', nativeEntries: ['native.openBrowserPairing', 'native.openMemoryBackup', 'native.openWorkspaceBackup', 'native.openCredentialFlow'] }
export class Port implements WebViewPort {
  listener: ((event: { data: unknown }) => void) | undefined
  sent: Record<string, unknown>[] = []
  postMessage(message: unknown) { this.sent.push(message as Record<string, unknown>) }
  addEventListener(_type: 'message', listener: (event: { data: unknown }) => void) { this.listener = listener }
  removeEventListener() { this.listener = undefined }
  emit(data: unknown) { this.listener?.({ data }) }
  session(id = '11111111-1111-4111-8111-111111111111') { this.emit({ type: 'shell.session', version: 1, sessionId: id }) }
  reply(index = 0, result: unknown = status, overrides = {}) {
    const request = this.sent[index]!
    this.emit({ version: 1, sessionId: request.sessionId, requestId: request.requestId, ok: true, result, ...overrides })
  }
}
