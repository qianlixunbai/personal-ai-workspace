import { useEffect, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { HistoricalMemory } from '../bridge/conversations'
import type { MemoryItem } from '../bridge/memory'

function ReferencedMemory({ bridge, reference }: { bridge: WorkspaceClient; reference: HistoricalMemory }) {
  const [item, setItem] = useState<MemoryItem | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let disposed = false
    setItem(null); setError('')
    void bridge.getMemory(reference.memoryId).then(result => {
      if (disposed) return
      if (result.id !== reference.memoryId || result.revision !== reference.revision || result.status !== 'ACTIVE') {
        setError('这条记忆已变更或归档。历史正文未保存，无法展示当时内容；可到“我的记忆”查看当前记录。'); return
      }
      setItem(result)
    }).catch(problem => {
      if (disposed) return
      setError(problem instanceof BridgeError
        ? problem.code === 'MemoryNotFound' ? '这条记忆已删除或不存在。历史正文未保存，无法展示当时内容。' : problem.message
        : '记忆暂时无法读取，请收起后重试。')
    })
    return () => { disposed = true }
  }, [bridge, reference.memoryId, reference.revision])
  return <section className="memory-preview" aria-label="对话引用记忆只读预览" aria-busy={!item && !error}>
    <p className="hint">记录版本：{reference.revision} · 记忆标识：<bdi>{reference.memoryId}</bdi></p>
    {!item && !error && <p role="status">正在读取记忆…</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {item && <><h4 dir="auto">{item.title}</h4><p className="hint">当前保存正文（版本与本轮引用一致）。</p>
      <pre dir="auto" tabIndex={0} aria-label="引用记忆正文">{item.content}</pre></>}
  </section>
}

// Only the explicitly opened turn is mounted. Switching entries remounts the
// reader so late results cannot replace a different reference's content.
export function ConversationMemoryPreview({ bridge, turnId, references }: {
  bridge: WorkspaceClient; turnId: string; references: HistoricalMemory[]
}) {
  const [selected, setSelected] = useState<number | null>(null)
  const reference = selected === null ? null : references[selected]
  return <section id={`conversation-memory-${turnId}`} className="conversation-memory-references" aria-label="本轮记忆引用">
    <p className="hint">这里只保存了记忆标识和版本，没有保存历史正文。查看不会将记忆选入下一轮，也不证明当时接纳或执行成功。</p>
    <div className="operation-actions">{references.map((entry, index) => <button key={entry.memoryId} type="button"
      aria-expanded={selected === index} aria-controls={selected === index ? `conversation-memory-body-${turnId}` : undefined}
      onClick={() => setSelected(current => current === index ? null : index)}>{selected === index ? '收起' : '查看'}记忆 {index + 1}</button>)}</div>
    {reference && <div id={`conversation-memory-body-${turnId}`}>
      <ReferencedMemory key={`${reference.memoryId}:${reference.revision}`} bridge={bridge} reference={reference} />
    </div>}
  </section>
}
