import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeDetail, KnowledgeList, KnowledgePreview } from '../bridge/knowledge'
import { ConfirmationDialog } from '../components/ConfirmationDialog'
import { KnowledgeSearch } from '../components/KnowledgeSearch'
import { KnowledgeAnswer } from '../components/KnowledgeAnswer'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'
import { KnowledgeSourcePreview, type KnowledgeSourceTarget } from '../components/KnowledgeSourcePreview'
import { KnowledgeWebCapture } from '../components/KnowledgeWebCapture'

const labels = { PENDING: '等待处理', PARSING: '正在解析', READY: '可用', FAILED: '导入失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
export function KnowledgePage({ bridge, enabled, visible }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean }) {
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED'>('ACTIVE'), [page, setPage] = useState(0)
  const [list, setList] = useState<KnowledgeList | null>(null), [detail, setDetail] = useState<KnowledgeDetail | null>(null)
  const [preview, setPreview] = useState<KnowledgePreview | null>(null), [revision, setRevision] = useState('')
  const [sourceTarget, setSourceTarget] = useState<KnowledgeSourceTarget | null>(null)
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
    if (reset) { setPreview(null); setSourceTarget(null); offsets.current = [] }
    const result = await bridge.getKnowledge(id)
    if (epoch !== generation.current || pick !== selection.current) return false
    setDetail(result); setRevision(result.document.currentReadyRevision ?? ''); setStale(false)
    return true
  }
  const run = async (work: () => Promise<void>) => {
    if (busy) return; const epoch = generation.current; setBusy(true); setError(''); setNotice('')
    try { await work() } catch (e) { if (epoch === generation.current) report(e) }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  useEffect(() => {
    const clear = () => { generation.current++; selection.current++; selected.current = null; offsets.current = []; setList(null); setDetail(null); setPreview(null); setSourceTarget(null); setRevision(''); setError(''); setNotice(''); setBusy(false); setStale(false); setUnknownRequest(null); setConfirmDelete(false) }
    const unsubscribe = bridge.onSession(clear); return () => { clear(); unsubscribe() }
  }, [bridge])
  useEffect(() => {
    if (!visible) {
      selection.current++; offsets.current = []
      setPreview(null); setSourceTarget(null)
    }
  }, [visible])
  useEffect(() => {
    if (!enabled || !visible) return
    const epoch = generation.current; let disposed = false
    void bridge.listKnowledge(status, page).then(result => { if (!disposed && epoch === generation.current) setList(result) }).catch(e => { if (!disposed && epoch === generation.current) report(e) })
    return () => { disposed = true }
  }, [bridge, enabled, visible, status, page])
  useEffect(() => {
    if (!enabled || !visible || !processing || !document) return
    const epoch = generation.current, pick = selection.current, id = document.documentId
    const timer = setTimeout(() => { void bridge.getKnowledge(id).then(result => { if (epoch === generation.current && pick === selection.current) { setDetail(result); setRevision(current => current || result.document.currentReadyRevision || ''); void refreshList().catch(report) } }).catch(e => { if (epoch === generation.current && pick === selection.current) report(e) }) }, 1000)
    return () => clearTimeout(timer)
  }, [bridge, enabled, visible, document, processing, revision])
  const importSource = (update: boolean) => run(async () => {
    const epoch = generation.current
    const result = await bridge.importKnowledge(update && document ? document.documentId : null, update && document ? document.metadataVersion : null)
    if (epoch !== generation.current) return
    if (result.outcome === 'CANCELLED') { setNotice('已取消导入。'); return }
    if (result.outcome === 'UNKNOWN') { setUnknownRequest(result.requestId); setNotice('导入结果暂无法确认；请检查导入状态，文件不会自动重发。'); return }
    setUnknownRequest(null); setStatus('ACTIVE'); setPage(0); await load(result.job!.documentId); await refreshList(); setNotice('导入已接受。')
  })
  const showPreview = (offset: number, history: 'push' | 'back' | 'reset' = 'push') => run(async () => {
    if (!enabled || !visible || !document || !revision) return
    const destination = history === 'back' ? offsets.current.at(-1) : offset
    if (destination === undefined) return
    const epoch = generation.current, pick = selection.current
    const result = await bridge.previewKnowledge(document.documentId, revision, destination)
    if (epoch !== generation.current || pick !== selection.current) return
    // A failed read must leave both the displayed source and its back stack intact.
    if (history === 'reset') offsets.current = []
    else if (history === 'back') offsets.current = offsets.current.slice(0, -1)
    else if (preview && preview.offset !== destination) offsets.current = [...offsets.current.slice(-255), preview.offset]
    setPreview(result)
  })
  const mutation = (action: 'archive' | 'restore' | 'delete') => run(async () => {
    if (!document) return
    const epoch = generation.current, id = document.documentId
    if (action === 'delete') { await bridge.deleteKnowledge(id, document.metadataVersion); if (epoch !== generation.current) return; selection.current++; selected.current = null; offsets.current = []; setDetail(null); setPreview(null); setSourceTarget(null); filter.current?.focus(); setNotice('文档及保留的源版本已删除。') }
    else { await (action === 'archive' ? bridge.archiveKnowledge(id, document.metadataVersion) : bridge.restoreKnowledge(id, document.metadataVersion)); if (epoch !== generation.current) return; await load(id); setNotice(action === 'archive' ? '文档已归档。' : '文档已恢复。') }
    await refreshList()
  })
  const openHit = (hit: Pick<KnowledgeSearchHit, 'documentId' | 'sourceRevision' | 'startOffset' | 'endOffset' | 'startLine' | 'endLine'>, kind: KnowledgeSourceTarget['kind'] = 'search') => run(async () => {
    if (!enabled || !visible) return
    const epoch = generation.current, pick = selection.current + 1; const loaded = await load(hit.documentId)
    if (!loaded || epoch !== generation.current || pick !== selection.current) return
    const result = await bridge.previewKnowledge(hit.documentId, hit.sourceRevision, hit.startOffset)
    if (epoch !== generation.current || pick !== selection.current) return
    setRevision(hit.sourceRevision); setPreview(result)
    setSourceTarget({ documentId: hit.documentId, sourceRevision: hit.sourceRevision, startOffset: hit.startOffset, endOffset: hit.endOffset, startLine: hit.startLine, endLine: hit.endLine, kind })
  })
  return <section className="knowledge-page" aria-label="Knowledge 管理">
    <div className="card-heading memory-toolbar knowledge-toolbar"><div><h2>我的文档</h2><p className="hint">仅使用你主动导入的内容</p></div><div className="conversation-actions">
      <button className="primary" disabled={!enabled || busy} onClick={() => { void importSource(false) }}>导入文档</button>
      <button disabled={!enabled || busy} onClick={() => { void run(async () => { await bridge.open('native.openKnowledgeBackup') }) }}>知识库备份…</button></div></div>
    <p className="hint backup-warning">知识库需单独备份；源文件、数据库与备份均含明文内容，请妥善保管。</p>
    <p className="hint">支持 UTF-8 TXT、Markdown 和 Word（.docx）正文摘录。Word 导入须先预览确认，原文件、图片与版式不会保存到知识库。</p>
    <details className="advanced-section"><summary>从网页添加资料</summary><KnowledgeWebCapture bridge={bridge} enabled={enabled && !busy} visible={visible} saved={refreshList} open={id => run(async () => { await load(id) })} /></details>
    <KnowledgeSearch bridge={bridge} enabled={enabled && !busy} visible={visible} corpusEpoch={corpusEpoch} open={openHit} currentDocument={document} />
    <details className="advanced-section"><summary>向知识库提问</summary><KnowledgeAnswer bridge={bridge} enabled={enabled && !busy} visible={visible} open={hit => openHit(hit, 'citation')} currentDocument={document} /></details>
    <div className="knowledge-layout">
      <section className="card knowledge-list" aria-labelledby="knowledge-list-title"><h2 id="knowledge-list-title">文档列表</h2>
        <div className="knowledge-list-tools"><label className="sr-only" htmlFor="knowledge-status">文档状态</label><select id="knowledge-status" ref={filter} value={status} disabled={busy} onChange={e => { setStatus(e.target.value as typeof status); setPage(0) }}><option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option></select>
          <button disabled={!enabled || busy} onClick={() => { void run(refreshList) }}>刷新列表</button></div>
        <ul tabIndex={0} aria-label="文档列表">{list?.items.map(d => <li key={d.documentId}><button className="memory-select" disabled={busy} aria-pressed={document?.documentId === d.documentId} onClick={() => { void run(async () => { await load(d.documentId) }) }}>{d.title}<span>{labels[d.processingState]} · 源版本 {d.currentReadyRevision ?? '无'}</span></button></li>)}</ul>
        {list?.items.length === 0 && <p className="empty-state">{status === 'ACTIVE' ? '还没有文档。点击上方「导入文档」，添加第一份参考资料。' : '没有已归档的文档。'}</p>}
        <div className="pagination"><button disabled={!enabled || busy || page === 0} onClick={() => setPage(page - 1)}>上一页文档</button><span>{page + 1} / {Math.max(1, Math.ceil((list?.total ?? 0) / 20))}</span><button disabled={!enabled || busy || !list || (page + 1) * 20 >= list.total} onClick={() => setPage(page + 1)}>下一页文档</button></div>
      </section>
      <section className="card knowledge-detail" aria-labelledby="knowledge-detail-title"><h2 id="knowledge-detail-title">{document?.title ?? '选择一个文档'}</h2>
        {!document && <p className="empty-state">从左侧选择文档，查看版本与原文。</p>}
        {document && <><p role="status" aria-live="polite">{document.status === 'ACTIVE' ? '使用中' : '已归档'} · {labels[document.processingState]}</p>
          {processing && <p role="progressbar" aria-label="Knowledge 导入处理" aria-valuetext={labels[document.processingState]}>等待完整 READY 发布；旧 READY 版本仍可预览。</p>}
          {detail.job?.errorCode && <p className="error" role="alert">本次处理未完成：{detail.job.errorCode}。已存在的 READY 版本保持可用；可显式重新选择文件导入。</p>}
          {stale && <p role="alert">请刷新详情确认当前状态后再修改；不会自动重试。</p>}
          <div className="knowledge-reader-tools"><label htmlFor="knowledge-revision">已发布源版本<select id="knowledge-revision" value={revision} disabled={busy || !detail.revisions.length} onChange={e => { selection.current++; setRevision(e.target.value); setPreview(null); setSourceTarget(null); offsets.current = [] }}>{!detail.revisions.length && <option value="">尚无 READY 版本</option>}{detail.revisions.map(r => <option key={r.sourceRevision} value={r.sourceRevision}>版本 {r.sourceRevision} · {r.sourceType} · {r.byteLength} bytes</option>)}</select></label>
            <button disabled={!enabled || busy || !revision} onClick={() => { void showPreview(0, 'reset') }}>预览源文本</button></div>
          {preview && <KnowledgeSourcePreview preview={preview} target={sourceTarget} currentRevision={document.currentReadyRevision} disabled={!enabled || busy || !visible} hasPrevious={offsets.current.length > 0} navigate={showPreview} />}
          <div className="conversation-actions"><button disabled={!enabled || busy} onClick={() => { void run(async () => { await load(document.documentId) }) }}>刷新详情</button>
            <button disabled={!enabled || busy || processing || stale || document.status !== 'ACTIVE'} onClick={() => { void importSource(true) }}>导入新源版本</button>
            <button disabled={!enabled || busy || processing || stale} onClick={() => { void mutation(document.status === 'ACTIVE' ? 'archive' : 'restore') }}>{document.status === 'ACTIVE' ? '归档' : '恢复'}</button>
            <button className="destructive" disabled={!enabled || busy || processing || stale} onClick={() => setConfirmDelete(true)}>物理删除…</button>
            {processing && document.requestId && <button disabled={!enabled || busy} onClick={() => { void run(async () => { await bridge.cancelKnowledgeImport(document.documentId, document.requestId!); await load(document.documentId) }) }}>取消导入</button>}</div>
        </>}
      </section>
    </div>
    {unknownRequest && <button disabled={busy || !enabled} onClick={() => { void run(async () => { const job = await bridge.knowledgeImportState(unknownRequest); setUnknownRequest(null); await load(job.documentId); await refreshList() }) }}>检查导入状态</button>}
    {error && <p role="alert" className="error">{error}</p>}<p role="status">{notice}</p>
    {confirmDelete && document && <ConfirmationDialog title="物理删除 Knowledge 文档？" confirmText="确认删除" cancel={() => setConfirmDelete(false)} confirm={() => { setConfirmDelete(false); void mutation('delete') }}><p>将删除此文档的所有源版本、私有源文件、normalized text 和 locator 数据。此操作无法撤销，不承诺取证级擦除。</p></ConfirmationDialog>}
  </section>
}
