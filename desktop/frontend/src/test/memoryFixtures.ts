import { Port, status } from './fixtures'
import type { MemoryItem } from '../bridge/memory'
export const memoryId = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
export const memoryItem = (n = 1): MemoryItem => ({ id: memoryId(n), type: 'PROJECT_NOTE', title: `Synthetic Memory ${n}`, content: 'Synthetic body',
  status: 'ACTIVE', revision: '9007199254740993', source: 'MANUAL', createdAt: '2026-10-05T00:00:00+00:00', updatedAt: '2026-10-05T00:00:00+00:00' })
export const metadata = ({ content: _content, ...item }: MemoryItem) => item
export class MemoryService extends Port {
  items: MemoryItem[] = [memoryItem()]
  error: string | null = null
  failList = false
  getError: string | null = null
  postMessage(request: unknown) {
    super.postMessage(request)
    const index = this.sent.length - 1, sent = this.sent[index]!, payload = sent.payload as Record<string, unknown>, method = String(sent.method)
    queueMicrotask(() => {
      const reject = (code: string) => this.emit({ version: 1, sessionId: sent.sessionId, requestId: sent.requestId, ok: false, error: { code, message: `Safe ${code}` } })
      if (method.startsWith('shell.')) { this.reply(index, status); return }
      if (method.startsWith('native.')) { this.reply(index, { opened: true }); return }
      if (method === 'memory.editorState') { this.reply(index, { acknowledged: true }); return }
      if (method === 'memory.list') {
        if (this.failList) { reject('MemoryStorageUnavailable'); return }
        const items = this.items.filter(x => x.status === payload.status && (payload.type === null || x.type === payload.type)
          && (x.title.includes(String(payload.query)) || x.content.includes(String(payload.query)))).sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt) || a.id.localeCompare(b.id))
        const page = Number(payload.page)
        this.reply(index, { items: items.slice(page * 20, page * 20 + 20).map(metadata), total: items.length, page, limit: 20 }); return
      }
      if (method === 'memory.get') {
        if (this.getError) { reject(this.getError); return }
        const item = this.items.find(x => x.id === payload.memoryId)
        if (!item) reject('MemoryNotFound'); else this.reply(index, item)
        return
      }
      if (this.error) { reject(this.error); return }
      if (method === 'memory.create') {
        const item = { ...memoryItem(99), ...payload, revision: '1' } as MemoryItem
        this.items.push(item); this.reply(index, item); return
      }
      const item = this.items.find(x => x.id === payload.memoryId)
      if (!item) { reject('MemoryNotFound'); return }
      if (method === 'memory.delete') { this.items = this.items.filter(x => x !== item); this.reply(index, { deleted: true }); return }
      const next: MemoryItem = { ...item, revision: String(BigInt(item.revision) + 1n),
        ...(method === 'memory.update' ? { type: payload.type, title: payload.title, content: payload.content } as Partial<MemoryItem> : { status: method === 'memory.archive' ? 'ARCHIVED' : 'ACTIVE' }) }
      this.items = this.items.map(x => x === item ? next : x); this.reply(index, next)
    })
  }
}
