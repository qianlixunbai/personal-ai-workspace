import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeDetail, KnowledgeList, KnowledgePreview } from '../bridge/knowledge'
import { ConfirmationDialog } from '../components/ConfirmationDialog'
import { KnowledgeSearch } from '../components/KnowledgeSearch'
import { KnowledgeAnswer } from '../components/KnowledgeAnswer'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'

const labels = { PENDING: '等待处理', PARSING: '正在解析', READY: '可用', FAILED: '导入失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
export function KnowledgePage({ bridge, enabled, visible }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean }) {
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED'>('ACTIVE'), [page, setPage] = useState(0)
  const [list, setList] = useState<KnowledgeList | null>(null), [detail, setDetail] = useState<KnowledgeDetail | null>(null)
  const [preview, setPreview] = useState<KnowledgePreview | null>(null), [revision, setRevision] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false), [stale, setStale] = useState(false), [unknownRequest, setUnknownRequest] = useState<string | null>(null)
  const generation = useRef(0), selection = useRef(0), offsets = useRef<number[]>([]), selected = useRef<string | null>(null)
  const filter = useRef<HTMLSelectElement>(null)
  const [corpusEpoch, setCorpusEpoch] = useState(0)
  const document = detail?.document
  const processing = document?.processingState === 'PENDING' || document?.processingState === 'PARSING'
  const report = (e: unknown) => { setError(e instanceof Error ? e.message : 'Knowledge 操作暂时不可用。'); if (e instanceof BridgeError && ['KnowledgeRevisionConflict', 'OutcomeUnknown', 'KnowledgeDeleteIncomplete'].includes(e.code)) setStale(true) }
  const refreshList = async () => { const epoch = generation.current; const result = await bridge.listKnowledge(status, page); if (epoch === generation.current) { setList(result); setCorpusEpoch(n => n + 1) } }
  const load = async (id: string, reset = true) => {
    const epoch = generation.current, pick = ++selection.current; selected.current = id
    if (reset) { setPreview(null); offsets.current = [] }
    const result = await bridge.getKnowledge(id)
    if (epoch !== generation.current || pick !== selection.current) return
    setDetail(result); setRevision(result.document.currentReadyRevision ?? ''); setStale(false)
  }
  const run = async (work: () => Promise<void>) => {
    if (busy) return; const epoch = generation.current; setBusy(true); setError(''); setNotice('')
    try { await work() } catch (e) { if (epoch === generation.current) report(e) }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  useEffect(() => {
    const clear = () => { generation.current++; selection.current++; selected.current = null; offsets.current = []; setList(null); setDetail(null); setPreview(null); setRevision(''); setError(''); setNotice(''); setBusy(false); setStale(false); setUnknownRequest(null); setConfirmDelete(false) }
    const unsubscribe = bridge.onSession(clear); return () => { clear(); unsubscribe() }
  }, [bridge])
  useEffect(() => {
    if (!enabled || !visible) return
    const epoch = generation.current; let disposed = false
    void bridge.listKnowledge(status, page).then(result => { if (!disposed && epoch === generation.current) setList(result) }).catch(e => { if (!disposed && epoch === generation.current) report(e) })
    return () => { disposed = true }
  }, [bridge, enabled, visible, status, page])
  useEffect(() => {
    if (!enabled || !visible || !processing || !document) return
    const epoch = generation.current, pick = selection.current, id = document.documentId
    const timer = setTimeout(() => { void bridge.getKnowledge(id).then(result => { if (epoch === generation.current && pick === selection.current) { setDetail(result); setRevision(result.document.currentReadyRevision ?? ''); void refreshList().catch(report) } }).catch(e => { if (epoch === generation.current && pick === selection.current) report(e) }) }, 1000)
    return () => clearTimeout(timer)
  }, [bridge, enabled, visible, document, processing])
  const importSource = (update: boolean) => run(async () => {
    const epoch = generation.current
    const result = await bridge.importKnowledge(update && document ? document.documentId : null, update && document ? document.metadataVersion : null)
    if (epoch !== generation.current) return
    if (result.outcome === 'CANCELLED') { setNotice('已取消文件选择。'); return }
    if (result.outcome === 'UNKNOWN') { setUnknownRequest(result.requestId); setNotice('导入结果暂无法确认；请检查导入状态，文件不会自动重发。'); return }
    setUnknownRequest(null); setStatus('ACTIVE'); setPage(0); await load(result.job!.documentId); await refreshList(); setNotice('导入已接受。')
  })
  const showPreview = (offset: number) => run(async () => {
    if (!document || !revision) return
    const epoch = generation.current, pick = selection.current; const result = await bridge.previewKnowledge(document.documentId, revision, offset)
    if (epoch === generation.current && pick === selection.current) setPreview(result)
  })
  const mutation = (action: 'archive' | 'restore' | 'delete') => run(async () => {
    if (!document) return
    const epoch = generation.current, id = document.documentId
    if (action === 'delete') { await bridge.deleteKnowledge(id, document.metadataVersion); if (epoch !== generation.current) return; selection.current++; selected.current = null; setDetail(null); setPreview(null); filter.current?.focus(); setNotice('文档及保留的源版本已删除。') }
    else { await (action === 'archive' ? bridge.archiveKnowledge(id, document.metadataVersion) : bridge.restoreKnowledge(id, document.metadataVersion)); if (epoch !== generation.current) return; await load(id); setNotice(action === 'archive' ? '文档已归档。' : '文档已恢复。') }
    await refreshList()
  })
  const openHit = (hit: Pick<KnowledgeSearchHit, 'documentId' | 'sourceRevision' | 'startOffset'>) => run(async () => {
    const epoch = generation.current; await load(hit.documentId)
    if (epoch !== generation.current) return
    const pick = selection.current; const result = await bridge.previewKnowledge(hit.documentId, hit.sourceRevision, hit.startOffset)
    if (epoch !== generation.current || pick !== selection.current) return
    setRevision(hit.sourceRevision); setPreview(result)
    setTimeout(() => { if (epoch === generation.current && pick === selection.current) window.document.querySelector<HTMLPreElement>('.knowledge-detail pre')?.focus() }, 0)
  })
  return <section className="knowledge-page" aria-label="Knowledge 管理">
    <div className="card card-heading memory-toolbar"><div><h2>参考来源</h2><p className="hint">明确导入 · 私有源副本 · 独立版本 · 本机确定性处理</p></div><div className="conversation-actions">
      <button className="primary" disabled={!enabled || busy} onClick={() => { void importSource(false) }}>导入 TXT / Markdown</button>
      <button disabled={!enabled || busy} onClick={() => { void run(async () => { await bridge.open('native.openKnowledgeBackup') }) }}>Knowledge Backup…</button></div></div>
    <p className="hint">Workspace Backup v1 只包含 Memory + Conversation。Knowledge 文档使用独立 Knowledge Backup；源文件、数据库和备份均为受 OS 账户权限保护的明文。</p>
    <KnowledgeSearch bridge={bridge} enabled={enabled && !busy} visible={visible} corpusEpoch={corpusEpoch} open={openHit} />
    <KnowledgeAnswer bridge={bridge} enabled={enabled && !busy} visible={visible} open={openHit} />
    <div className="knowledge-layout">
      <section className="card knowledge-list" aria-labelledby="knowledge-list-title"><h2 id="knowledge-list-title">文档列表</h2>
        <label htmlFor="knowledge-status">生命周期</label><select id="knowledge-status" ref={filter} value={status} disabled={busy} onChange={e => { setStatus(e.target.value as typeof status); setPage(0) }}><option value="ACTIVE">ACTIVE · 使用中</option><option value="ARCHIVED">ARCHIVED · 已归档</option></select>
        <button disabled={!enabled || busy} onClick={() => { void run(refreshList) }}>刷新列表</button>
        <ul>{list?.items.map(d => <li key={d.documentId}><button className="memory-select" disabled={busy} aria-pressed={document?.documentId === d.documentId} onClick={() => { void run(() => load(d.documentId)) }}>{d.title}<span>{labels[d.processingState]} · 源版本 {d.currentReadyRevision ?? '无'}</span></button></li>)}</ul>
        {list?.items.length === 0 && <p>此生命周期中没有文档。</p>}
        <div className="pagination"><button disabled={!enabled || busy || page === 0} onClick={() => setPage(page - 1)}>上一页文档</button><span>{page + 1} / {Math.max(1, Math.ceil((list?.total ?? 0) / 20))}</span><button disabled={!enabled || busy || !list || (page + 1) * 20 >= list.total} onClick={() => setPage(page + 1)}>下一页文档</button></div>
      </section>
      <section className="card knowledge-detail" aria-labelledby="knowledge-detail-title"><h2 id="knowledge-detail-title">{document?.title ?? '选择一个文档'}</h2>
        {document && <><p role="status" aria-live="polite">{document.status} · {labels[document.processingState]} · metadataVersion {document.metadataVersion}</p>
          {processing && <p role="progressbar" aria-label="Knowledge 导入处理" aria-valuetext={labels[document.processingState]}>等待完整 READY 发布；旧 READY 版本仍可预览。</p>}
          {detail.job?.errorCode && <p className="error" role="alert">本次处理未完成：{detail.job.errorCode}。已存在的 READY 版本保持可用；可显式重新选择文件导入。</p>}
          {stale && <p role="alert">请刷新详情确认当前状态后再修改；不会自动重试。</p>}
          <div className="conversation-actions"><button disabled={!enabled || busy} onClick={() => { void run(() => load(document.documentId)) }}>刷新详情</button>
            <button disabled={!enabled || busy || processing || stale || document.status !== 'ACTIVE'} onClick={() => { void importSource(true) }}>导入新源版本</button>
            <button disabled={!enabled || busy || processing || stale} onClick={() => { void mutation(document.status === 'ACTIVE' ? 'archive' : 'restore') }}>{document.status === 'ACTIVE' ? '归档' : '恢复'}</button>
            <button className="destructive" disabled={!enabled || busy || processing || stale} onClick={() => setConfirmDelete(true)}>物理删除…</button>
            {processing && document.requestId && <button disabled={!enabled || busy} onClick={() => { void run(async () => { await bridge.cancelKnowledgeImport(document.documentId, document.requestId!); await load(document.documentId) }) }}>取消导入</button>}</div>
          <label htmlFor="knowledge-revision">已发布源版本</label><select id="knowledge-revision" value={revision} disabled={busy || !detail.revisions.length} onChange={e => { setRevision(e.target.value); setPreview(null); offsets.current = [] }}>{!detail.revisions.length && <option value="">尚无 READY 版本</option>}{detail.revisions.map(r => <option key={r.sourceRevision} value={r.sourceRevision}>版本 {r.sourceRevision} · {r.sourceType} · {r.byteLength} bytes</option>)}</select>
          <button disabled={!enabled || busy || !revision} onClick={() => { offsets.current = []; void showPreview(0) }}>预览源文本</button>
          {preview && <section aria-label="纯文本预览"><p className="knowledge-location">{preview.locators.map(l => `${l.type} · 行 ${l.startLine}–${l.endLine}${l.heading ? ` · ${l.heading}` : ''}`).join('')} · 文本偏移 {preview.offset}</p><pre tabIndex={0}>{preview.text}</pre>
            <div className="pagination"><button disabled={busy || offsets.current.length === 0} onClick={() => { const previous = offsets.current.pop()!; void showPreview(previous) }}>上一页文本</button><button disabled={busy || preview.nextOffset === null} onClick={() => { offsets.current = [...offsets.current.slice(-255), preview.offset]; void showPreview(preview.nextOffset!) }}>下一页文本</button></div></section>}
        </>}
      </section>
    </div>
    {unknownRequest && <button disabled={busy || !enabled} onClick={() => { void run(async () => { const job = await bridge.knowledgeImportState(unknownRequest); setUnknownRequest(null); await load(job.documentId); await refreshList() }) }}>检查导入状态</button>}
    {error && <p role="alert" className="error">{error}</p>}<p role="status">{notice}</p>
    {confirmDelete && document && <ConfirmationDialog title="物理删除 Knowledge 文档？" confirmText="确认删除" cancel={() => setConfirmDelete(false)} confirm={() => { setConfirmDelete(false); void mutation('delete') }}><p>将删除此文档的所有源版本、私有源文件、normalized text 和 locator 数据。此操作无法撤销，不承诺取证级擦除。</p></ConfirmationDialog>}
  </section>
}
