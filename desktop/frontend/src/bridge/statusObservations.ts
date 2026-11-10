import type { ShellStatus } from './contracts'
import type { ModelSnapshot } from './models'
import type { StorageStartupStatus, StorageStatus } from './storage'

export interface Observation<T> {
  phase: 'unseen' | 'checking' | 'observed' | 'failed' | 'invalidated'
  value: T | null
  receivedAt: number | null
  reason: string | null
}
interface Results { shell: ShellStatus; startup: StorageStartupStatus; storage: StorageStatus; model: ModelSnapshot }
export type WorkspaceObservations = { [K in keyof Results]: Observation<Results[K]> }
const empty = <T>(): Observation<T> => ({ phase: 'unseen', value: null, receivedAt: null, reason: null })
const initial = (): WorkspaceObservations => ({ shell: empty(), startup: empty(), storage: empty(), model: empty() })

/** Display-only observations of existing validated bridge calls. Never polls or authorizes an operation. */
export class StatusObservations {
  private state = initial()
  private revisions = { shell: 0, startup: 0, storage: 0, model: 0 }
  private listeners = new Set<() => void>()
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish() { this.listeners.forEach(listener => listener()) }
  private set<K extends keyof Results>(key: K, observation: Observation<Results[K]>) {
    this.state = { ...this.state, [key]: observation }
    this.publish()
  }
  reset() {
    for (const key of Object.keys(this.revisions) as (keyof Results)[]) this.revisions[key]++
    this.state = initial()
    this.publish()
  }
  invalidate(keys: (keyof Results)[], reason: string) {
    for (const key of keys) {
      this.revisions[key]++
      if (this.state[key].phase !== 'unseen')
        this.state = { ...this.state, [key]: { ...this.state[key], phase: 'invalidated', value: null, reason } }
    }
    this.publish()
  }
  lifecycle(value: StorageStartupStatus) {
    this.invalidate(['shell', 'startup', 'storage', 'model'], 'Runtime 生命周期已变化，请重新检查。')
    this.set('startup', { phase: 'observed', value, receivedAt: Date.now(), reason: null })
  }
  async track<K extends keyof Results>(key: K, request: () => Promise<Results[K]>): Promise<Results[K]> {
    if (key === 'shell') this.invalidate(['storage', 'model'], '连接正在重新检查，请随后核对各项状态。')
    if (key === 'startup') this.invalidate(['storage'], '正在重新检查 Runtime 启动观察。')
    const revision = ++this.revisions[key]
    this.set(key, { phase: 'checking', value: null, receivedAt: null, reason: null })
    try {
      const value = await request()
      if (revision === this.revisions[key]) {
        this.set(key, { phase: 'observed', value, receivedAt: Date.now(), reason: null })
        if (key === 'shell' && ((value as ShellStatus).runtime !== 'Available' || (value as ShellStatus).credential !== 'Ready')
          || key === 'startup' && !(value as StorageStartupStatus).canQueryStorage)
          this.invalidate(['storage', 'model'], 'Runtime 或原生认证尚未确认，请先核对连接。')
      }
      return value
    } catch (problem) {
      if (revision === this.revisions[key]) {
        this.set(key, { phase: 'failed', value: null, receivedAt: Date.now(), reason: problem instanceof Error ? problem.message : '本次检查失败。' })
        if (key === 'shell' || key === 'startup') this.invalidate(['storage', 'model'], '连接检查未完成，请先核对连接。')
        if (key === 'storage') this.invalidate(['model'], '存储检查失败，请先核对连接与存储。')
      }
      throw problem
    }
  }
}
