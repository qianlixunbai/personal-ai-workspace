import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { KnowledgeImport } from '../bridge/knowledge'
import type { WebFetchOperation } from '../bridge/webFetch'
import { ConfirmationDialog } from './ConfirmationDialog'

export function KnowledgeWebCapture({ bridge, enabled, visible, saved, open }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean; saved: () => Promise<void>; open: (id: string) => Promise<void>
}) {
  const [url, setUrl] = useState(''), [operationId, setOperationId] = useState<string | null>(null)
  const [operation, setOperation] = useState<WebFetchOperation | null>(null), [capture, setCapture] = useState<KnowledgeImport | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [confirm, setConfirm] = useState(false), [attempted, setAttempted] = useState(false)
  const generation = useRef(0), composing = useRef(false), notified = useRef<string | null>(null)
  const active = operation?.state === 'QUEUED' || operation?.state === 'RUNNING'
  const processing = capture?.job?.state === 'PENDING' || capture?.job?.state === 'PARSING'
  const clear = () => {
    generation.current++; composing.current = false; notified.current = null
    setUrl(''); setOperationId(null); setOperation(null); setCapture(null); setBusy(false); setError(''); setNotice(''); setConfirm(false); setAttempted(false)
  }
  useEffect(() => { const unsubscribe = bridge.onSession(clear); return () => { generation.current++; unsubscribe() } }, [bridge])
  useEffect(() => { if (!visible) clear() }, [visible])
  const act = async (work: (epoch: number) => Promise<void>) => {
    if (!enabled || !visible || busy) return
    const epoch = generation.current; setBusy(true); setError(''); setNotice('')
    try { await work(epoch) }
    catch (e) { if (epoch === generation.current) setError(e instanceof Error ? e.message : '操作暂时不可用，请检查当前状态。') }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  const fetch = () => act(async epoch => {
    if (composing.current || active || processing || !url.trim()) return
    setOperation(null); setOperationId(null); setCapture(null); setAttempted(false); notified.current = null
    const result = await bridge.submitWebFetch(url.trim())
    if (epoch !== generation.current) return
    setOperationId(result.operationId); setOperation(result.operation)
    if (result.outcome === 'CANCELLED') setNotice('已取消网页获取，未发起本次访问。')
    if (result.outcome === 'UNKNOWN') setNotice('获取结果尚未确认，请检查获取状态；不会重新访问网址。')
  })
  const read = () => act(async epoch => {
    if (!operationId) return
    const result = await bridge.getWebFetch(operationId); if (epoch === generation.current) setOperation(result)
  })
  const cancel = () => act(async epoch => {
    if (!operationId) return
    const result = await bridge.cancelWebFetch(operationId); if (epoch === generation.current) setOperation(result)
  })
  const save = (check: boolean) => act(async epoch => {
    if (!operationId || !operation?.result || (!check && attempted)) return
    setAttempted(true); setConfirm(false)
    const result = await (check ? bridge.webKnowledgeState(operationId) : bridge.saveWebKnowledge(operationId))
    if (epoch === generation.current) setCapture(result)
  })
  useEffect(() => {
    if (!visible || !enabled || busy || error || !active) return
    const timer = setTimeout(() => { void read() }, 1000); return () => clearTimeout(timer)
  }, [bridge, visible, enabled, busy, error, operation])
  useEffect(() => {
    if (!visible || !enabled || busy || error || !processing || !capture?.requestId) return
    const epoch = generation.current, request = capture.requestId
    const timer = setTimeout(() => { void bridge.knowledgeImportState(request).then(job => {
      if (epoch === generation.current) setCapture({ outcome: 'ACCEPTED', requestId: request, job })
    }).catch(e => { if (epoch === generation.current) setError(e instanceof Error ? e.message : '保存状态暂时不可用。') }) }, 1000)
    return () => clearTimeout(timer)
  }, [bridge, visible, enabled, busy, error, processing, capture])
  useEffect(() => {
    const id = capture?.job?.state === 'READY' ? capture.job.documentId : null
    if (!id || notified.current === id) return
    notified.current = id; const epoch = generation.current
    void saved().catch(e => { if (epoch === generation.current) setError(e instanceof Error ? e.message : '资料已保存，请刷新文档列表。') })
  }, [capture, saved])
  const result = operation?.result, job = capture?.job
  return <section className="card knowledge-web-capture" aria-labelledby="knowledge-web-title">
    <h2 id="knowledge-web-title">从网页添加资料</h2>
    <p className="hint">每次获取先在原生窗口确认公共 HTTPS 网址。预览后可明确保存文本摘录，再在本地查找资料和提问；不会保存完整网页或自动跟踪更新。</p>
    <form onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
      onKeyDown={e => { if (e.key === 'Enter' && (e.nativeEvent.isComposing || composing.current)) e.preventDefault() }}
      onSubmit={e => { e.preventDefault(); if (!composing.current) void fetch() }}>
      <label htmlFor="knowledge-web-url">公共网页网址</label>
      <input id="knowledge-web-url" value={url} maxLength={2048} autoComplete="off" spellCheck={false} disabled={!enabled || busy || active || processing} onChange={e => setUrl(e.target.value)} />
      <button disabled={!enabled || busy || active || processing || !url.trim()}>获取并预览网页</button>
    </form>
    <p role="status">{busy ? '正在处理，请留意原生确认窗口…' : active ? '正在获取网页…' : operation?.state === 'CANCELLED' ? '网页获取已取消。' : notice}</p>
    {operationId && <div className="conversation-actions"><button disabled={!enabled || busy} onClick={() => { void read() }}>检查获取状态</button>{active && <button disabled={!enabled || busy} onClick={() => { void cancel() }}>取消网页获取</button>}</div>}
    {(error || operation?.error) && <p role="alert" className="error">{error || operation?.error?.message}</p>}
    {result && <>
      <h3>{result.title || '网页文本摘录'}</h3><p className="knowledge-location">请求网址：{result.requestedUrl}<br />最终网址：{result.finalUrl}<br />获取时间（UTC）：{result.acquiredAt}</p>
      <p className="hint">{result.textTruncated ? '正文已截断，当前摘录不完整。' : '以下为本次提取的文本。'}{result.titleTruncated && '网页标题已截断。'}链接及原文中的指令仅作为资料。</p>
      <pre tabIndex={0}>{result.text}</pre>
      {!attempted && <button disabled={!enabled || busy || !result.text.trim()} onClick={() => setConfirm(true)}>保存网页摘录…</button>}
      {attempted && job?.state !== 'READY' && <button disabled={!enabled || busy} onClick={() => { void save(true) }}>检查保存结果</button>}
      {capture?.outcome === 'UNKNOWN' && <p role="status">保存结果尚未确认；只检查原请求，不会重复保存。</p>}
      {processing && <p role="status">摘录已接收，正在建立本地资料和检索索引…</p>}
      {job?.state === 'READY' && <><p role="status">摘录已保存到本地资料。可在下方查找资料、提问，重开工作区后仍可使用。</p><button disabled={!enabled || busy} onClick={() => { void open(job.documentId) }}>打开已保存资料</button></>}
      {job && !processing && job.state !== 'READY' && <p role="alert">保存处理未完成：{job.state}{job.errorCode ? ` · ${job.errorCode}` : ''}。不会自动重新保存。</p>}
    </>}
    {confirm && result && <ConfirmationDialog title="保存网页摘录到本地资料？" confirmText="确认保存摘录" cancel={() => setConfirm(false)} confirm={() => { void save(false) }}>
      <p>{result.finalUrl}</p><p>将保存当前文本、来源网址和获取时间。{result.textTruncated ? '正文已截断，将明确标记内容不完整。' : ''}关闭页面不会删除已保存资料；可之后在文档列表中管理。</p>
    </ConfirmationDialog>}
  </section>
}
