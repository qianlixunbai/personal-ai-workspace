import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { ConversationMemoryUsageHit, ConversationMemoryUsagePage } from '../bridge/conversationMemoryUsage'
import type { Conversation, Turn } from '../bridge/conversations'
import type { MemoryItem } from '../bridge/memory'

const states = { SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '超时', PENDING: '处理中' }
type Review = { conversation: Conversation; turn: Turn; revision: string }
export function MemoryUsageReview({ bridge, memory, enabled }: { bridge: WorkspaceClient; memory: MemoryItem; enabled: boolean }) {
  const [result, setResult] = useState<ConversationMemoryUsagePage | null>(null)
  const [review, setReview] = useState<Review | null>(null)
  const [onlyCurrent, setOnlyCurrent] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const epoch = useRef(0), lock = useRef(false)
  const current = useRef({ enabled, id: memory.id, revision: memory.revision }); current.current = { enabled, id: memory.id, revision: memory.revision }
  const clear = () => { epoch.current++; lock.current = false; setResult(null); setReview(null); setBusy(false); setError('') }
  useEffect(clear, [enabled, memory.id, memory.revision])
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { epoch.current++; unsubscribe() } }, [bridge])
  const run = async (operation: (check: () => void) => Promise<void>) => {
    if (!enabled || lock.current) return
    const token = ++epoch.current, id = memory.id, revision = memory.revision
    const valid = () => token === epoch.current && current.current.enabled && current.current.id === id && current.current.revision === revision
    const check = () => { if (!valid()) throw new Error('查询范围已变化，请重新查看。') }
    lock.current = true; setBusy(true); setError(''); setReview(null)
    try { await operation(check) }
    catch (problem) { if (valid()) setError(problem instanceof BridgeError ? problem.message : problem instanceof Error ? problem.message : '使用记录暂时无法读取，请重新查询。') }
    finally { if (valid()) { lock.current = false; setBusy(false) } }
  }
  const query = (page: number, revision: string | null) => {
    if (!enabled || lock.current) return
    setResult(null)
    void run(async check => {
      const found = await bridge.getConversationMemoryUsage(memory.id, revision, page)
      check(); setResult(found)
    })
  }
  const inspect = (hit: ConversationMemoryUsageHit) => { void run(async check => {
    const detail = await bridge.getConversation(hit.conversation.id, Math.floor((hit.sequence - 1) / 10))
    check()
    const turn = detail.turns.find(item => item.turnId === hit.turnId && item.sequence === hit.sequence)
    if (detail.conversation.id !== hit.conversation.id || !turn || turn.status !== hit.status
      || !turn.memoryReferences.some(item => item.memoryId === memory.id && item.revision === hit.revision))
      throw new Error('这条轮次或记忆引用已不可用，请重新查询；没有改查其他会话。')
    setReview({ conversation: detail.conversation, turn, revision: hit.revision })
  }) }
  return <section className="memory-revision-review" aria-label="记忆使用记录" aria-busy={busy}>
    <h3>记忆使用记录</h3>
    <p className="hint">查找这条已存记忆在使用中及已归档会话里的选择记录，不改变编辑草稿。只显示已结束轮次；选择记录不证明模型实际采纳，也不覆盖文字参考或独立问答。</p>
    <label><input type="checkbox" checked={onlyCurrent} disabled={!enabled || busy} onChange={event => { setOnlyCurrent(event.target.checked); clear() }} />仅查看已存版本 {memory.revision}</label>
    <div className="operation-actions"><button disabled={!enabled || busy} onClick={() => query(0, onlyCurrent ? memory.revision : null)}>查询使用记录</button>
      {(result || review || busy || error) && <button onClick={clear}>收起使用记录</button>}</div>
    {result && <><p role="status">{result.revision === null ? '该记忆的所有记录版本' : `记忆版本 ${result.revision}`} · 第 {result.page + 1} 页</p>
      {!result.items.length && <p>本页没有已保存的选择记录。没有记录不表示记忆文字从未出现在历史消息中。</p>}
      <ul>{result.items.map(hit => <li key={hit.turnId}>
        <bdi>{hit.conversation.title}</bdi> · {hit.conversation.status === 'ARCHIVED' ? '已归档' : '使用中'} · 第 {hit.sequence} 轮 · {states[hit.status]} · 记忆版本 {hit.revision}
        <button disabled={!enabled || busy} aria-label={`查看 ${hit.conversation.title} 第 ${hit.sequence} 轮`} onClick={() => inspect(hit)}>查看原轮次</button>
      </li>)}</ul>
      <div className="pagination"><button disabled={!enabled || busy || result.page === 0} onClick={() => query(result.page - 1, result.revision)}>上一页使用记录</button>
        <button disabled={!enabled || busy || !result.hasMore || result.page >= 99999} onClick={() => query(result.page + 1, result.revision)}>下一页使用记录</button></div>
    </>}
    {review && <section aria-label="使用记忆的原轮次"><h4><bdi>{review.conversation.title}</bdi> · 第 {review.turn.sequence} 轮</h4>
      <p>{review.conversation.status === 'ARCHIVED' ? '已归档' : '使用中'} · {states[review.turn.status]} · 当时选择版本 {review.revision}</p>
      <p className="hint">历史只保留记忆标识和版本，未保存当时的记忆正文；下方为该轮已保存问答。历史不会随当前记忆的修订或删除而撤回。</p>
      <h5>用户消息</h5><pre tabIndex={0} dir="auto">{review.turn.userMessage.content}</pre>
      {review.turn.assistantMessage ? <><h5>助手回复</h5><pre tabIndex={0} dir="auto">{review.turn.assistantMessage.content}</pre></> : <p>此轮没有已保存的助手回复。</p>}
      <button onClick={() => setReview(null)}>收起原轮次</button>
    </section>}
    {busy && <p role="status">正在读取使用记录…</p>}{error && <p role="alert">{error}</p>}
  </section>
}
