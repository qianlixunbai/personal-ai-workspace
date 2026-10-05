import { businessMethods, conversationMethods, memoryMethods, exactFields, isId, isMemoryChoice, isObject, isOperation, isSafeError, isStatus, nativeMethods } from './contracts'
import { knowledgeMethods, isKnowledgeDocument, isKnowledgeDetail, isKnowledgeList, isKnowledgePreview, isKnowledgeImport, isKnowledgeJob } from './knowledge'
import type { KnowledgeDocument, KnowledgeDetail, KnowledgeList, KnowledgePreview, KnowledgeImport, KnowledgeJob } from './knowledge'
import { isKnowledgeSearchResult, isKnowledgeSearchStatus } from './knowledgeSearch'
import type { KnowledgeSearchResult, KnowledgeSearchStatus } from './knowledgeSearch'
import { isMemoryItem, isMemoryList } from './memory'
import type { MemoryDraft, MemoryItem, MemoryList, MemoryQuery } from './memory'
import { conversationResponseBytes, isAdmission, isConversation, isConversationDetail, isConversationList } from './conversations'
import type { Conversation, ConversationAdmission, ConversationDetail, ConversationList, ConversationStatus } from './conversations'
import type { MemoryRef } from './contracts'
import type { AssistantSubmit, MemoryChoice, Method, NativeMethod, OperationView, ShellStatus, WebViewPort } from './contracts'

export class BridgeError extends Error { constructor(public code: string, message: string) { super(message) } }
const submission = (method: Method) => method === 'assistant.submit' || method === 'translate.submit' || method === 'conversations.send'
const memoryMutation = (method: Method) => ['memory.create', 'memory.update', 'memory.archive', 'memory.restore', 'memory.delete'].includes(method)
const knowledgeMutation = (method: Method) => ['knowledge.import', 'knowledge.archive', 'knowledge.restore', 'knowledge.delete'].includes(method)
const uncertain = (method: Method) => submission(method) || memoryMutation(method) || knowledgeMutation(method)
const unknownOutcome = () => new BridgeError('OutcomeUnknown', 'Outcome unknown：可能已提交成功。请检查 Runtime；不会自动重发。')

interface Pending { method: Method; documentId?: string; requestId?: string; sourceRevision?: string; expectedMetadataVersion?: string; offset?: number; operationId?: string; conversationId?: string; memoryId?: string; expectedRevision?: string; type?: string | null; page?: number; status?: string; resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }
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
  listConversations(status: ConversationStatus, page: number) { return this.request('conversations.list', { status, page }) as Promise<ConversationList> }
  getConversation(conversationId: string, page: number) { return this.request('conversations.get', { conversationId, page }) as Promise<ConversationDetail> }
  createConversation() { return this.request('conversations.create') as Promise<Conversation> }
  renameConversation(conversationId: string, title: string) { return this.request('conversations.rename', { conversationId, title }) as Promise<Conversation> }
  archiveConversation(conversationId: string) { return this.request('conversations.archive', { conversationId }) as Promise<Conversation> }
  unarchiveConversation(conversationId: string) { return this.request('conversations.unarchive', { conversationId }) as Promise<Conversation> }
  deleteConversation(conversationId: string) { return this.request('conversations.delete', { conversationId }) as Promise<{ deleted: true }> }
  selectConversationMemories(conversationId: string) { return this.request('conversations.selectMemories', { conversationId }) as Promise<MemoryChoice> }
  clearConversationMemories(conversationId: string) { return this.request('conversations.clearMemories', { conversationId }) as Promise<{ cleared: true }> }
  sendConversation(conversationId: string, message: string, selectedMemoryRefs: MemoryRef[]) { return this.request('conversations.send', { conversationId, message, selectedMemoryRefs }) as Promise<ConversationAdmission> }
  cancelConversationPending(conversationId: string, turnId: string) { return this.request('conversations.cancelPending', { conversationId, turnId }) as Promise<{ requested: true }> }
  listMemory(query: MemoryQuery) { return this.request('memory.list', query) as Promise<MemoryList> }
  getMemory(memoryId: string) { return this.request('memory.get', { memoryId }) as Promise<MemoryItem> }
  createMemory(draft: MemoryDraft) { return this.request('memory.create', draft) as Promise<MemoryItem> }
  updateMemory(memoryId: string, expectedRevision: string, draft: MemoryDraft) { return this.request('memory.update', { memoryId, expectedRevision, ...draft }) as Promise<MemoryItem> }
  archiveMemory(memoryId: string, expectedRevision: string) { return this.request('memory.archive', { memoryId, expectedRevision }) as Promise<MemoryItem> }
  restoreMemory(memoryId: string, expectedRevision: string) { return this.request('memory.restore', { memoryId, expectedRevision }) as Promise<MemoryItem> }
  deleteMemory(memoryId: string, expectedRevision: string) { return this.request('memory.delete', { memoryId, expectedRevision }) as Promise<{ deleted: true }> }
  memoryEditorState(dirty: boolean) { return this.request('memory.editorState', { dirty }) as Promise<{ acknowledged: true }> }
  listKnowledge(status: 'ACTIVE' | 'ARCHIVED', page: number) { return this.request('knowledge.list', { status, page }) as Promise<KnowledgeList> }
  searchKnowledge(query: string) { return this.request('knowledge.search', { query, limit: 10 }) as Promise<KnowledgeSearchResult> }
  knowledgeSearchStatus() { return this.request('knowledge.searchStatus') as Promise<KnowledgeSearchStatus> }
  rebuildKnowledgeSearch() { return this.request('knowledge.rebuildSearchIndex') as Promise<KnowledgeSearchStatus> }
  getKnowledge(documentId: string) { return this.request('knowledge.get', { documentId }) as Promise<KnowledgeDetail> }
  importKnowledge(documentId: string | null = null, expectedMetadataVersion: string | null = null) { return this.request('knowledge.import', { documentId, expectedMetadataVersion }) as Promise<KnowledgeImport> }
  knowledgeImportState(requestId: string) { return this.request('knowledge.importState', { requestId }) as Promise<KnowledgeJob> }
  cancelKnowledgeImport(documentId: string, requestId: string) { return this.request('knowledge.cancelImport', { documentId, requestId }) as Promise<KnowledgeJob> }
  archiveKnowledge(documentId: string, expectedMetadataVersion: string) { return this.request('knowledge.archive', { documentId, expectedMetadataVersion }) as Promise<KnowledgeDocument> }
  restoreKnowledge(documentId: string, expectedMetadataVersion: string) { return this.request('knowledge.restore', { documentId, expectedMetadataVersion }) as Promise<KnowledgeDocument> }
  deleteKnowledge(documentId: string, expectedMetadataVersion: string) { return this.request('knowledge.delete', { documentId, expectedMetadataVersion }) as Promise<{ deleted: true }> }
  previewKnowledge(documentId: string, sourceRevision: string, offset: number) { return this.request('knowledge.preview', { documentId, sourceRevision, offset }) as Promise<KnowledgePreview> }
  private request(method: Method, payload: unknown = {}): Promise<unknown> {
    if (!this.port || !this.session) return Promise.reject(new Error('工作区尚未连接。请从原生窗口重新打开。'))
    if (!['shell.bootstrap', 'shell.refreshStatus', ...nativeMethods, ...businessMethods, ...conversationMethods, ...memoryMethods, ...knowledgeMethods].includes(method) || this.pending.size >= 8)
      return Promise.reject(new Error('操作暂时不可用，请稍后重试。'))
    const requestId = crypto.randomUUID()
    const request = { version: 1, sessionId: this.session, requestId, method, payload }
    if (new TextEncoder().encode(JSON.stringify(request)).length > 32 * 1024)
      return Promise.reject(new BridgeError('InvalidRequest', '输入超出 bridge 传输预算，请缩短文本。'))
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(requestId); reject(uncertain(method) ? unknownOutcome()
        : new BridgeError('ClientTimeout', '操作等待超时，请检查原生窗口。')) }, method.startsWith('native.') || method.endsWith('.selectMemories') || method === 'knowledge.import' ? 300_000 : 15_000)
      this.pending.set(requestId, { method, ...(isObject(payload) ? {
        ...(typeof payload.documentId === 'string' ? { documentId: payload.documentId } : {}),
        ...(typeof payload.requestId === 'string' ? { requestId: payload.requestId } : {}),
        ...(typeof payload.sourceRevision === 'string' ? { sourceRevision: payload.sourceRevision } : {}),
        ...(typeof payload.expectedMetadataVersion === 'string' ? { expectedMetadataVersion: payload.expectedMetadataVersion } : {}),
        ...(typeof payload.offset === 'number' ? { offset: payload.offset } : {}),
        ...(typeof payload.operationId === 'string' ? { operationId: payload.operationId } : {}),
        ...(typeof payload.conversationId === 'string' ? { conversationId: payload.conversationId } : {}),
        ...(typeof payload.memoryId === 'string' ? { memoryId: payload.memoryId } : {}),
        ...(typeof payload.expectedRevision === 'string' ? { expectedRevision: payload.expectedRevision } : {}),
        ...(typeof payload.type === 'string' || payload.type === null ? { type: payload.type } : {}),
        ...(typeof payload.page === 'number' ? { page: payload.page } : {}),
        ...(typeof payload.status === 'string' ? { status: payload.status } : {}),
      } : {}), resolve, reject, timer })
      try { this.port!.postMessage(request) }
      catch { clearTimeout(timer); this.pending.delete(requestId); reject(uncertain(method) ? unknownOutcome() : new BridgeError('RuntimeUnavailable', '工作区通信不可用。')) }
    })
  }
  private receive = ({ data }: { data: unknown }) => {
    if (!isObject(data)) return
    const bytes = new TextEncoder().encode(JSON.stringify(data)).length
    if (bytes > conversationResponseBytes) return
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
    if (bytes > (pending.method === 'conversations.get' ? conversationResponseBytes : 64 * 1024)) return
    clearTimeout(pending.timer); this.pending.delete(data.requestId)
    if (data.ok === true && exactFields(data, ['version', 'sessionId', 'requestId', 'ok', 'result'])) {
      const valid = pending.method.startsWith('shell.') ? isStatus(data.result)
        : pending.method === 'knowledge.search' ? isKnowledgeSearchResult(data.result)
        : pending.method === 'knowledge.searchStatus' || pending.method === 'knowledge.rebuildSearchIndex' ? isKnowledgeSearchStatus(data.result)
        : pending.method === 'knowledge.list' ? isKnowledgeList(data.result) && data.result.page === pending.page && data.result.items.every(d => d.status === pending.status)
        : pending.method === 'knowledge.get' ? isKnowledgeDetail(data.result) && data.result.document.documentId === pending.documentId
        : pending.method === 'knowledge.preview' ? isKnowledgePreview(data.result) && data.result.documentId === pending.documentId && data.result.sourceRevision === pending.sourceRevision && data.result.offset === pending.offset
        : pending.method === 'knowledge.import' ? isKnowledgeImport(data.result) && (!pending.documentId || data.result.job === null || data.result.job.documentId === pending.documentId)
        : pending.method === 'knowledge.importState' || pending.method === 'knowledge.cancelImport' ? isKnowledgeJob(data.result) && data.result.requestId === pending.requestId && (!pending.documentId || data.result.documentId === pending.documentId)
        : pending.method === 'knowledge.archive' || pending.method === 'knowledge.restore' ? isKnowledgeDocument(data.result) && data.result.documentId === pending.documentId && BigInt(data.result.metadataVersion) > BigInt(pending.expectedMetadataVersion!) && data.result.status === (pending.method === 'knowledge.archive' ? 'ARCHIVED' : 'ACTIVE')
        : pending.method === 'knowledge.delete' ? isObject(data.result) && exactFields(data.result, ['deleted']) && data.result.deleted === true
        : pending.method === 'memory.list' ? isMemoryList(data.result) && data.result.page === pending.page && data.result.items.every(x => x.status === pending.status && (pending.type === null || x.type === pending.type))
        : ['memory.get', 'memory.create', 'memory.update', 'memory.archive', 'memory.restore'].includes(pending.method) ? isMemoryItem(data.result)
          && (!pending.memoryId || data.result.id === pending.memoryId)
          && (!pending.expectedRevision || BigInt(data.result.revision) > BigInt(pending.expectedRevision))
          && (pending.method === 'memory.create' || pending.method === 'memory.restore' ? data.result.status === 'ACTIVE' : pending.method === 'memory.archive' ? data.result.status === 'ARCHIVED' : true)
        : pending.method === 'memory.delete' ? isObject(data.result) && exactFields(data.result, ['deleted']) && data.result.deleted === true
        : pending.method === 'memory.editorState' ? isObject(data.result) && exactFields(data.result, ['acknowledged']) && data.result.acknowledged === true
        : pending.method === 'conversations.list' ? isConversationList(data.result) && data.result.page === pending.page && data.result.items.every(x => x.status === pending.status)
        : pending.method === 'conversations.get' ? isConversationDetail(data.result) && data.result.conversation.id === pending.conversationId && data.result.page === pending.page
        : pending.method === 'conversations.send' ? isAdmission(data.result) && data.result.conversationId === pending.conversationId
        : ['conversations.create', 'conversations.rename', 'conversations.archive', 'conversations.unarchive'].includes(pending.method) ? isConversation(data.result)
          && (!pending.conversationId || data.result.id === pending.conversationId)
          && (pending.method === 'conversations.archive' ? data.result.status === 'ARCHIVED' : pending.method === 'conversations.unarchive' || pending.method === 'conversations.create' ? data.result.status === 'ACTIVE' : true)
        : pending.method.endsWith('.selectMemories') ? isMemoryChoice(data.result)
        : pending.method === 'conversations.delete' ? isObject(data.result) && exactFields(data.result, ['deleted']) && data.result.deleted === true
        : pending.method === 'conversations.clearMemories' ? isObject(data.result) && exactFields(data.result, ['cleared']) && data.result.cleared === true
        : submission(pending.method) || pending.method === 'operations.get' ? isOperation(data.result) && (!pending.operationId || data.result.operationId === pending.operationId)
        : pending.method === 'operations.cancel' || pending.method === 'conversations.cancelPending' ? isObject(data.result) && exactFields(data.result, ['requested']) && data.result.requested === true
        : pending.method === 'operations.copyResult' ? isObject(data.result) && exactFields(data.result, ['copied']) && data.result.copied === true
        : isObject(data.result) && exactFields(data.result, ['opened']) && data.result.opened === true
      if (valid) {
        pending.resolve(data.result); return
      }
    } else if (data.ok === false && exactFields(data, ['version', 'sessionId', 'requestId', 'ok', 'error'])
      && isSafeError(data.error)) {
      pending.reject((memoryMutation(pending.method) || knowledgeMutation(pending.method)) && ['RuntimeUnavailable', 'ClientTimeout', 'InvalidResponse'].includes(data.error.code) ? unknownOutcome() : new BridgeError(data.error.code, data.error.message)); return
    }
    pending.reject(uncertain(pending.method) ? unknownOutcome() : new BridgeError('InvalidResponse', '工作区返回了无效响应，请重新打开。'))
  }
  private rejectPending(message: string) {
    this.pending.forEach(pending => { clearTimeout(pending.timer); pending.reject(uncertain(pending.method) ? unknownOutcome() : new Error(message)) })
    this.pending.clear()
  }
  dispose() {
    this.port?.removeEventListener('message', this.receive)
    this.rejectPending('工作区已关闭。'); this.listeners.clear(); this.session = null
  }
}
