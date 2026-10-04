import { businessMethods, exactFields, isId, isMemoryChoice, isObject, isOperation, isSafeError, isStatus, nativeMethods } from './contracts'
import type { AssistantSubmit, MemoryChoice, Method, NativeMethod, OperationView, ShellStatus, WebViewPort } from './contracts'

export class BridgeError extends Error { constructor(public code: string, message: string) { super(message) } }
const submission = (method: Method) => method === 'assistant.submit' || method === 'translate.submit'
const unknownOutcome = () => new BridgeError('OutcomeUnknown', 'Outcome unknown：可能已提交成功。请检查 Runtime；不会自动重发。')

interface Pending { method: Method; operationId?: string; resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }
export class WorkspaceClient {
  private session: string | null = null
  private pending = new Map<string, Pending>()
  private listeners = new Set<() => void>()
  constructor(private port: WebViewPort | undefined) { port?.addEventListener('message', this.receive) }
  get available() { return !!this.port }
  get ready() { return this.session !== null }
  onSession(listener: () => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  bootstrap() { return this.request('shell.bootstrap') as Promise<ShellStatus> }
  refreshStatus() { return this.request('shell.refreshStatus') as Promise<ShellStatus> }
  open(method: NativeMethod) { return this.request(method) as Promise<{ opened: true }> }
  selectMemories() { return this.request('assistant.selectMemories') as Promise<MemoryChoice> }
  submitAssistant(payload: AssistantSubmit) { return this.request('assistant.submit', payload) as Promise<OperationView> }
  submitTranslate(text: string, targetLanguage: string) { return this.request('translate.submit', { text, targetLanguage }) as Promise<OperationView> }
  getOperation(operationId: string) { return this.request('operations.get', { operationId }) as Promise<OperationView> }
  cancelOperation(operationId: string) { return this.request('operations.cancel', { operationId }) as Promise<{ requested: true }> }
  copyResult(operationId: string) { return this.request('operations.copyResult', { operationId }) as Promise<{ copied: true }> }
  private request(method: Method, payload: unknown = {}): Promise<unknown> {
    if (!this.port || !this.session) return Promise.reject(new Error('工作区尚未连接。请从原生窗口重新打开。'))
    if (!['shell.bootstrap', 'shell.refreshStatus', ...nativeMethods, ...businessMethods].includes(method) || this.pending.size >= 8)
      return Promise.reject(new Error('操作暂时不可用，请稍后重试。'))
    const requestId = crypto.randomUUID()
    const request = { version: 1, sessionId: this.session, requestId, method, payload }
    if (new TextEncoder().encode(JSON.stringify(request)).length > 32 * 1024)
      return Promise.reject(new BridgeError('InvalidRequest', '输入超出 bridge 传输预算，请缩短文本。'))
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(requestId); reject(submission(method) ? unknownOutcome()
        : new BridgeError('ClientTimeout', '操作等待超时，请检查原生窗口。')) }, method.startsWith('native.') || method === 'assistant.selectMemories' ? 300_000 : 15_000)
      this.pending.set(requestId, { method, ...(isObject(payload) && typeof payload.operationId === 'string' ? { operationId: payload.operationId } : {}), resolve, reject, timer })
      try { this.port!.postMessage(request) }
      catch { clearTimeout(timer); this.pending.delete(requestId); reject(submission(method) ? unknownOutcome() : new BridgeError('RuntimeUnavailable', '工作区通信不可用。')) }
    })
  }
  private receive = ({ data }: { data: unknown }) => {
    if (!isObject(data) || new TextEncoder().encode(JSON.stringify(data)).length > 64 * 1024) return
    if (data.type === 'shell.session') {
      if (!exactFields(data, ['type', 'version', 'sessionId']) || data.version !== 1 || !isId(data.sessionId)) return
      if (data.sessionId === this.session) return
      this.rejectPending('工作区已重新加载，请显式重试。')
      this.session = data.sessionId
      this.listeners.forEach(listener => listener())
      return
    }
    if (data.version !== 1 || data.sessionId !== this.session || !isId(data.requestId)) return
    const pending = this.pending.get(data.requestId)
    if (!pending) return
    clearTimeout(pending.timer); this.pending.delete(data.requestId)
    if (data.ok === true && exactFields(data, ['version', 'sessionId', 'requestId', 'ok', 'result'])) {
      const valid = pending.method.startsWith('shell.') ? isStatus(data.result)
        : pending.method === 'assistant.selectMemories' ? isMemoryChoice(data.result)
        : submission(pending.method) || pending.method === 'operations.get' ? isOperation(data.result) && (!pending.operationId || data.result.operationId === pending.operationId)
        : pending.method === 'operations.cancel' ? isObject(data.result) && exactFields(data.result, ['requested']) && data.result.requested === true
        : pending.method === 'operations.copyResult' ? isObject(data.result) && exactFields(data.result, ['copied']) && data.result.copied === true
        : isObject(data.result) && exactFields(data.result, ['opened']) && data.result.opened === true
      if (valid) {
        pending.resolve(data.result); return
      }
    } else if (data.ok === false && exactFields(data, ['version', 'sessionId', 'requestId', 'ok', 'error'])
      && isSafeError(data.error)) {
      pending.reject(new BridgeError(data.error.code, data.error.message)); return
    }
    pending.reject(submission(pending.method) ? unknownOutcome() : new BridgeError('InvalidResponse', '工作区返回了无效响应，请重新打开。'))
  }
  private rejectPending(message: string) {
    this.pending.forEach(pending => { clearTimeout(pending.timer); pending.reject(submission(pending.method) ? unknownOutcome() : new Error(message)) })
    this.pending.clear()
  }
  dispose() {
    this.port?.removeEventListener('message', this.receive)
    this.rejectPending('工作区已关闭。'); this.listeners.clear(); this.session = null
  }
}
