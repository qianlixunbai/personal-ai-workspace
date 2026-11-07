import { Fragment, useEffect, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { MemoryItem, MemoryMetadata } from '../bridge/memory'

function Highlight({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>
  // Literal, case-sensitive matching, exactly as the existing Memory search contract.
  // Render text nodes only; neither saved text nor search text becomes HTML or a regex.
  return <>{text.split(query).map((part, index) => <Fragment key={index}>{index > 0 && <mark>{query}</mark>}{part}</Fragment>)}</>
}

// Mounted only by an explicit preview click. One preview is retained by the parent;
// closing, refreshing, filtering or leaving unmounts it and ignores a late response.
export function MemoryPreview({ bridge, metadata, query }: { bridge: WorkspaceClient; metadata: MemoryMetadata; query: string }) {
  const [item, setItem] = useState<MemoryItem | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let disposed = false
    setItem(null); setError('')
    void bridge.getMemory(metadata.id).then(result => {
      if (disposed) return
      if (result.id !== metadata.id || result.revision !== metadata.revision || result.status !== metadata.status) {
        setError('这条记忆已变更，请刷新列表后重新预览。'); return
      }
      setItem(result)
    }).catch(problem => {
      if (!disposed) setError(problem instanceof BridgeError ? problem.message : '预览暂时不可用，请收起后重新预览。')
    })
    return () => { disposed = true }
  }, [bridge, metadata.id, metadata.revision, metadata.status])
  return <section id={`memory-preview-${metadata.id}`} className="memory-preview" aria-label="记忆只读预览" aria-busy={!item && !error}>
    <p className="hint">只读预览；右侧未保存的编辑保持不变。</p>
    {!item && !error && <p role="status">正在读取记忆…</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {item && <><h3 dir="auto"><Highlight text={item.title} query={query} /></h3>
      <pre dir="auto" tabIndex={0} aria-label="预览正文"><Highlight text={item.content} query={query} /></pre></>}
  </section>
}
