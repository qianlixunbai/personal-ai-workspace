import { exactFields, isId, isObject, isStatus, nativeMethods } from './contracts'
import type { Method, NativeMethod, ShellStatus, WebViewPort } from './contracts'

interface Pending { method: Method; resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }
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
  private request(method: Method): Promise<unknown> {
    if (!this.port || !this.session) return Promise.reject(new Error('工作区尚未连接。请从原生窗口重新打开。'))
    if (!['shell.bootstrap', 'shell.refreshStatus', ...nativeMethods].includes(method) || this.pending.size >= 8)
      return Promise.reject(new Error('操作暂时不可用，请稍后重试。'))
    const requestId = crypto.randomUUID()
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(requestId); reject(new Error('操作等待超时，请检查原生窗口后显式重试。')) }, method.startsWith('native.') ? 300_000 : 15_000)
      this.pending.set(requestId, { method, resolve, reject, timer })
      try { this.port!.postMessage({ version: 1, sessionId: this.session, requestId, method, payload: {} }) }
      catch { clearTimeout(timer); this.pending.delete(requestId); reject(new Error('工作区通信不可用。')) }
    })
  }
  private receive = ({ data }: { data: unknown }) => {
    if (!isObject(data) || JSON.stringify(data).length > 32 * 1024) return
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
      if (pending.method.startsWith('shell.') ? isStatus(data.result)
        : isObject(data.result) && exactFields(data.result, ['opened']) && data.result.opened === true) {
        pending.resolve(data.result); return
      }
    } else if (data.ok === false && exactFields(data, ['version', 'sessionId', 'requestId', 'ok', 'error'])
      && isObject(data.error) && exactFields(data.error, ['code', 'message']) && data.error.code === 'NATIVE_UNAVAILABLE'
      && typeof data.error.message === 'string' && data.error.message.length <= 160) {
      pending.reject(new Error(data.error.message)); return
    }
    pending.reject(new Error('工作区返回了无效响应，请重新打开。'))
  }
  private rejectPending(message: string) {
    this.pending.forEach(pending => { clearTimeout(pending.timer); pending.reject(new Error(message)) })
    this.pending.clear()
  }
  dispose() {
    this.port?.removeEventListener('message', this.receive)
    this.rejectPending('工作区已关闭。'); this.listeners.clear(); this.session = null
  }
}
