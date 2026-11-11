import { useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import { isConversationSearchQuery } from '../bridge/conversations'
import type { ConversationSearchHit, ConversationSearchPage, ConversationSearchScope } from '../bridge/conversations'
import type { MemoryList, MemoryMetadata, MemoryStatus } from '../bridge/memory'
import type { KnowledgeSearchHit, KnowledgeSearchResult } from '../bridge/knowledgeSearch'
import { HighlightedText } from './HighlightedText'
import { MemoryPreview } from './MemoryPreview'
import { searchReadError, WorkspaceConversationReader, WorkspaceKnowledgeReader } from './WorkspaceSearchReaders'
import { messageWithReferences, referenceKey, referenceLocation, referenceText, referenceTitle, verifyReferences } from './conversationReferences'
import type { ContextPreparation, ConversationReference } from './conversationReferences'
import type { MemoryConversationSource } from './memoryConversationSource'
import { WorkspaceAnswerHistory, type AnswerSelection } from './WorkspaceAnswerHistory'

type Group<T> = { data: T | null; busy: boolean; error: string }
const empty = <T,>(): Group<T> => ({ data: null, busy: false, error: '' })
type Search = { query: string; memory: MemoryStatus; conversations: ConversationSearchScope }
type Selection = { kind: 'memory'; item: MemoryMetadata } | { kind: 'conversation'; hit: ConversationSearchHit; source?: MemoryConversationSource } | { kind: 'knowledge'; hit: KnowledgeSearchHit; excerpt?: boolean }

// Reading a result never changes the underlying editors. An optional conversation
// preparation flow stages text locally and requires a separate insertion action.
export function WorkspaceSearchDialog({ bridge, close, preparation }: {
  bridge: WorkspaceClient; close: () => void; preparation?: ContextPreparation
}) {
  const dialog = useRef<HTMLDialogElement>(null), input = useRef<HTMLInputElement>(null)
  const readerFocus = useRef<HTMLButtonElement>(null), resultFocus = useRef<HTMLButtonElement | null>(null)
  const closeRef = useRef(close); closeRef.current = close
  const generation = useRef(0), pending = useRef(0), composing = useRef(false)
  const [query, setQuery] = useState(''), [memoryStatus, setMemoryStatus] = useState<MemoryStatus>('ACTIVE')
  const [scope, setScope] = useState<ConversationSearchScope>('ALL'), [submitted, setSubmitted] = useState<Search | null>(null)
  const [memories, setMemories] = useState<Group<MemoryList>>(empty)
  const [conversations, setConversations] = useState<Group<ConversationSearchPage>>(empty)
  const [knowledge, setKnowledge] = useState<Group<KnowledgeSearchResult>>(empty)
  const [memoryPage, setMemoryPage] = useState(0), [conversationPage, setConversationPage] = useState(0)
  const [selection, setSelection] = useState<Selection | null>(null)
  const [answerSelection, setAnswerSelection] = useState<AnswerSelection | null>(null)
  const [references, setReferences] = useState<ConversationReference[]>([])
  const referencesRef = useRef(references)
  const [readerVersion, setReaderVersion] = useState(0)
  const [preparing, setPreparing] = useState(false), [preparationError, setPreparationError] = useState('')
  const [preparationNotice, setPreparationNotice] = useState('')
  const preparationLock = useRef(false), preparationGeneration = useRef(0)
  const preparationRef = useRef(preparation); preparationRef.current = preparation
  const message = messageWithReferences(preparation?.draft ?? '', references)
  const messageBytes = new TextEncoder().encode(message).length
  const tooLong = message.length > 3000 || messageBytes > 5632
  const busy = memories.busy || conversations.busy || knowledge.busy || preparing
  const valid = isConversationSearchQuery(query)
  useEffect(() => { if (selection) readerFocus.current?.focus() }, [selection])
  const choose = (value: Selection, button: HTMLButtonElement) => { resultFocus.current = button; setReaderVersion(value => value + 1); setSelection(value) }
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null, element = dialog.current
    element?.showModal(); input.current?.focus()
    const unsubscribe = bridge.onSession(() => { generation.current++; preparationGeneration.current++; closeRef.current() })
    return () => { generation.current++; preparationGeneration.current++; unsubscribe(); element?.close(); if (previous?.isConnected) previous.focus() }
  }, [bridge])
  const addReference = (reference: ConversationReference) => {
    if (!preparation || preparationLock.current) return
    setPreparationError('')
    if (referencesRef.current.some(item => referenceKey(item) === referenceKey(reference))) { setPreparationNotice('这段参考已在准备列表中。'); return }
    if (referencesRef.current.length >= 4) { setPreparationError('一次最多准备 4 段参考，请先移除不需要的内容。'); return }
    referencesRef.current = [...referencesRef.current, reference]
    setReferences(referencesRef.current); setPreparationNotice('已加入待审阅参考，尚未改变会话草稿。')
  }
  const insertReferences = async () => {
    if (!preparation || preparationLock.current || !references.length || tooLong) return
    const token = ++preparationGeneration.current, target = preparation, deadline = Date.now() + 30_000
    const current = () => token === preparationGeneration.current && preparationRef.current?.conversationId === target.conversationId
      && preparationRef.current?.draft === target.draft
    const check = () => {
      if (!current()) throw new Error('当前会话或草稿已变化，请重新准备参考。')
      if (Date.now() > deadline) throw new Error('核对来源超时，草稿未改变。请主动重试。')
    }
    preparationLock.current = true; setPreparing(true); setPreparationError(''); setPreparationNotice('正在重新核对所选来源…')
    try {
      await verifyReferences(bridge, references, check)
      check()
      if (!preparationRef.current!.apply(message)) throw new Error('当前会话或草稿已变化，未插入参考。请关闭后重新准备。')
      closeRef.current()
    } catch (problem) {
      if (token === preparationGeneration.current) { setPreparationError(problem instanceof Error ? problem.message : '来源暂时无法核对，草稿未改变。'); setPreparationNotice('') }
    } finally { if (token === preparationGeneration.current) { preparationLock.current = false; setPreparing(false) } }
  }
  const load = async <T,>(request: () => Promise<T>, publish: Dispatch<SetStateAction<Group<T>>>) => {
    const token = generation.current
    pending.current++; publish({ data: null, busy: true, error: '' })
    try { const data = await request(); if (token === generation.current) publish({ data, busy: false, error: '' }) }
    catch (problem) { if (token === generation.current) publish({ data: null, busy: false, error: searchReadError(problem) }) }
    finally { if (token === generation.current) pending.current-- }
  }
  const loadMemory = (search: Search, page: number) => {
    setMemoryPage(page)
    void load(() => bridge.listMemory({ query: search.query, status: search.memory, type: null, page }), setMemories)
  }
  const loadConversations = (search: Search, page: number) => {
    setConversationPage(page)
    void load(() => bridge.searchConversations(search.query, search.conversations, page), setConversations)
  }
  const search = () => {
    if (pending.current || !valid || composing.current) return
    generation.current++; setSelection(null)
    const value = { query, memory: memoryStatus, conversations: scope }
    setSubmitted(value); loadMemory(value, 0); loadConversations(value, 0)
    void load(() => bridge.searchKnowledge(value.query), setKnowledge)
  }
  const retry = (action: () => void) => { if (!pending.current) { setSelection(null); action() } }
  return <dialog ref={dialog} className="workspace-search-dialog" aria-labelledby="workspace-search-title"
    onCancel={event => { event.preventDefault(); closeRef.current() }}>
    <div className="workspace-search-heading"><h2 id="workspace-search-title">{preparation ? '准备会话参考' : '查找工作区'}</h2><button onClick={close}>{preparation ? '关闭参考准备' : '关闭查找'}</button></div>
    <p className="hint">{preparation ? '查找并阅读资料、记忆或历史消息，明确加入参考后预览完整消息。关闭会丢弃尚未插入的选项，原会话草稿保持不变。' : '查找已保存的记忆、对话消息和资料。打开结果只读查看，关闭后继续当前草稿。'}</p>
    <form onSubmit={event => { event.preventDefault(); search() }}>
      <label>工作区关键词<input ref={input} value={query} disabled={busy} aria-describedby="workspace-search-query-hint"
        onChange={event => setQuery(event.target.value)} onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
        onKeyDown={event => { if (event.key === 'Enter' && (composing.current || event.nativeEvent.isComposing)) event.preventDefault() }} /></label>
      <div className="workspace-search-filters"><label>记忆范围<select value={memoryStatus} disabled={busy} onChange={event => setMemoryStatus(event.target.value as MemoryStatus)}>
        <option value="ACTIVE">活动记忆</option><option value="ARCHIVED">归档记忆</option></select></label>
        <label>对话范围<select value={scope} disabled={busy} onChange={event => setScope(event.target.value as ConversationSearchScope)}>
          <option value="ALL">全部对话</option><option value="ACTIVE">活动对话</option><option value="ARCHIVED">归档对话</option></select></label>
        <button className="primary" type="submit" disabled={busy || !valid}>查找</button></div>
      <p id="workspace-search-query-hint" className="hint">请输入 1–100 个字；空白和大小写保留。{query && !valid ? '当前关键词为空白、过长或含无效字符。' : ''}</p>
    </form>
    {submitted && <><p className="hint" role="status">以下结果来自“{submitted.query}”。记忆和对话匹配连续原文；资料按关键词检索当前可用版本，最多显示 10 个片段。</p>
      <div className="workspace-search-layout"><div>
        <section aria-label="工作区记忆结果" aria-busy={memories.busy}>
          <h3>{submitted.memory === 'ACTIVE' ? '活动记忆' : '归档记忆'}</h3>
          {memories.busy && <p role="status">正在查找记忆…</p>}
          {memories.error && <p role="alert" className="error">{memories.error} <button disabled={busy} onClick={() => retry(() => loadMemory(submitted, memoryPage))}>重试记忆查找</button></p>}
          {memories.data && <><p className="hint">共 {memories.data.total} 条 · 第 {memoryPage + 1} 页</p><ul className="workspace-search-results">
            {memories.data.items.map(item => <li key={item.id}><button onClick={event => choose({ kind: 'memory', item }, event.currentTarget)}>
              <span dir="auto">{item.title}</span><small>{item.type === 'PREFERENCE' ? '偏好' : '项目笔记'} · 版本 {item.revision} · 查看正文</small></button></li>)}</ul>
            {memories.data.total === 0 && <p>没有匹配记忆。</p>}
            <div className="pagination"><button disabled={busy || memoryPage === 0} onClick={() => retry(() => loadMemory(submitted, memoryPage - 1))}>上一页记忆</button>
              <button disabled={busy || (memoryPage + 1) * 20 >= memories.data.total} onClick={() => retry(() => loadMemory(submitted, memoryPage + 1))}>下一页记忆</button></div></>}
        </section>
        <section aria-label="工作区对话结果" aria-busy={conversations.busy}>
          <h3>{submitted.conversations === 'ALL' ? '全部对话' : submitted.conversations === 'ACTIVE' ? '活动对话' : '归档对话'}</h3>
          {conversations.busy && <p role="status">正在查找对话…</p>}
          {conversations.error && <p role="alert" className="error">{conversations.error} <button disabled={busy} onClick={() => retry(() => loadConversations(submitted, conversationPage))}>重试对话查找</button></p>}
          {conversations.data && <><p className="hint">第 {conversationPage + 1} 页 · {conversations.data.items.length} 条消息</p><ul className="workspace-search-results">
            {conversations.data.items.map(hit => <li key={hit.messageId}><button onClick={event => choose({ kind: 'conversation', hit }, event.currentTarget)}>
              <span dir="auto">{hit.conversation.title}</span><small>第 {hit.sequence} 轮 · {hit.role === 'USER' ? '用户消息' : '助手回复'} · {hit.conversation.status === 'ARCHIVED' ? '已归档' : '活动'} · 查看整轮</small>
              <span className="workspace-search-snippet" dir="auto">{hit.before && '…'}<HighlightedText text={hit.snippet} ranges={[{ start: hit.matchStart, end: hit.matchEnd }]} />{hit.after && '…'}</span>
            </button></li>)}</ul>{!conversations.data.items.length && <p>没有匹配消息。</p>}
            <div className="pagination"><button disabled={busy || conversationPage === 0} onClick={() => retry(() => loadConversations(submitted, conversationPage - 1))}>上一页消息</button>
              <button disabled={busy || !conversations.data.hasMore || conversationPage >= 199999} onClick={() => retry(() => loadConversations(submitted, conversationPage + 1))}>下一页消息</button></div></>}
        </section>
        <section aria-label="工作区资料结果" aria-busy={knowledge.busy}>
          <h3>活动资料 · 最多 10 个片段</h3>
          {knowledge.busy && <p role="status">正在查找资料…</p>}
          {knowledge.error && <p role="alert" className="error">{knowledge.error} <button disabled={busy} onClick={() => retry(() => { void load(() => bridge.searchKnowledge(submitted.query), setKnowledge) })}>重试资料查找</button></p>}
          {knowledge.data && <><ul className="workspace-search-results">{knowledge.data.hits.map(hit => <li key={`${hit.documentId}/${hit.sourceRevision}/${hit.startOffset}`}><button onClick={event => choose({ kind: 'knowledge', hit }, event.currentTarget)}>
            <span dir="auto">{hit.title}</span><small>源版本 {hit.sourceRevision} · 行 {hit.startLine}–{hit.endLine} · 查看原文</small>
            <span className="workspace-search-snippet" dir="auto"><HighlightedText text={hit.snippet} ranges={hit.highlightRanges} /></span>
          </button></li>)}</ul>{!knowledge.data.hits.length && <p>没有匹配资料片段。</p>}</>}
        </section>
      </div><div className="workspace-search-reader">
        {!selection && <p className="hint">选择结果，在这里阅读。</p>}
        {selection && <><button ref={readerFocus} onClick={() => { setSelection(null); resultFocus.current?.focus() }}>收起查找预览</button>
          {selection.kind === 'memory' && <MemoryPreview key={`memory/${readerVersion}/${selection.item.id}/${selection.item.revision}`} bridge={bridge} metadata={selection.item} query={submitted.query} idPrefix="workspace-memory-preview"
            prepare={preparation && !preparing ? item => addReference({ kind: 'memory', item }) : undefined} />}
          {selection.kind === 'conversation' && <WorkspaceConversationReader key={`conversation/${readerVersion}/${selection.hit.messageId}`} bridge={bridge} hit={selection.hit} query={submitted.query}
            source={selection.source} prepare={preparation && !preparing ? addReference : undefined} />}
          {selection.kind === 'knowledge' && <WorkspaceKnowledgeReader key={`knowledge/${readerVersion}/${selection.hit.documentId}/${selection.hit.sourceRevision}/${selection.hit.startOffset}/${selection.hit.endOffset}`} bridge={bridge} hit={selection.hit} excerpt={selection.excerpt}
            prepare={preparation && !preparing ? addReference : undefined} />}
        </>}
      </div></div>
    </>}
    <WorkspaceAnswerHistory bridge={bridge} selected={answerSelection} choose={setAnswerSelection} disabled={preparing}
      prepare={preparation && !preparing ? addReference : undefined} />
    {preparation && <section className="conversation-reference-review" aria-label="待插入的会话参考" aria-busy={preparing}>
      <h3>准备给：<bdi>{preparation.title}</bdi></h3>
      <details><summary>目标会话标识</summary><p>{preparation.conversationId}</p></details>
      <p className="hint">这些文字副本将插入普通会话消息，不会自动保存为记忆，也不改变“选择记忆”中的选项。出处是可编辑的定位线索，不是已验证事实或知识问答引用。历史消息只加入明确选中的文字，不恢复当时的记忆选择或上下文权限。</p>
      <ol>{references.map(reference => <li key={referenceKey(reference)}>
        <h4 dir="auto">{referenceTitle(reference)}</h4><p className="hint">{referenceLocation(reference)}</p>
        <pre tabIndex={0} dir="auto">{referenceText(reference)}</pre>
        <div className="operation-actions"><button disabled={preparing} onClick={event => {
          if (reference.kind === 'answer') { setAnswerSelection({ taskId: reference.source.taskId, reference }); return }
          choose(reference.kind === 'memory' ? { kind: 'memory', item: reference.item } : reference.kind === 'conversation'
            ? { kind: 'conversation', hit: reference.hit, source: reference.source } : { kind: 'knowledge', hit: reference.hit, excerpt: true }, event.currentTarget)
        }}>回查来源</button>
          <button disabled={preparing} onClick={() => {
            referencesRef.current = referencesRef.current.filter(item => referenceKey(item) !== referenceKey(reference))
            setReferences(referencesRef.current); setPreparationError(''); setPreparationNotice('已移除参考，原草稿保持不变。')
          }}>移除参考</button></div>
      </li>)}</ol>
      {!references.length && <p>尚未加入参考。打开记忆正文，或在资料原文、命中的历史消息中选取需要的文字。</p>}
      {references.length > 0 && <><h4>插入后的完整消息</h4><pre tabIndex={0} dir="auto" aria-label="完整消息预览">{message}</pre>
        <p className="hint">{message.length} / 3000 UTF-16 · {messageBytes} / 5632 UTF-8 字节。另行选择的记忆和按预算纳入的历史不包含在此预览内；发送时仍会检查全部输入是否超限。</p>
        {tooLong && <p role="alert" className="error">完整消息超过输入限制，请移除参考，或关闭后缩短原草稿再重新准备。内容不会截断。</p>}
      </>}
      <button className="primary" disabled={busy || !references.length || tooLong} onClick={() => { void insertReferences() }}>核对来源并插入草稿</button>
      <p className="hint">插入后仍可编辑，只有点击会话的“发送”才提交。插入的文字不会随来源更新或删除而自动改变。</p>
      {preparationError && <p role="alert" className="error">{preparationError}</p>}
      {preparationNotice && <p role="status">{preparationNotice}</p>}
    </section>}
  </dialog>
}
