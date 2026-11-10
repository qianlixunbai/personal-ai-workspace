import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeDetail, KnowledgeImport, KnowledgeJob, KnowledgeList, KnowledgePreview } from '../bridge/knowledge'
import { ConfirmationDialog } from '../components/ConfirmationDialog'
import { KnowledgeSearch } from '../components/KnowledgeSearch'
import { KnowledgeAnswer } from '../components/KnowledgeAnswer'
import type { KnowledgeSearchHit } from '../bridge/knowledgeSearch'
import { KnowledgeSourcePreview, type KnowledgeSourceTarget } from '../components/KnowledgeSourcePreview'
import { KnowledgeWebCapture } from '../components/KnowledgeWebCapture'
import { KnowledgeVersionComparison } from '../components/KnowledgeVersionComparison'
import { KnowledgeSourceFind } from '../components/KnowledgeSourceFind'
import type { SourceMatch } from '../components/knowledgeSourceMatches'
import { KnowledgeSourceLine } from '../components/KnowledgeSourceLine'
import { readSourceText } from '../components/knowledgeSourceText'
import { unicode } from '../bridge/memory'
import { formatMemorySource } from '../components/memoryKnowledgeSource'

type ReadingPosition = { documentId: string; revision: string; offset: number; target: KnowledgeSourceTarget | null; offsets: number[] }

const labels = { PENDING: '等待处理', PARSING: '正在解析', READY: '可用', FAILED: '导入失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
export function KnowledgePage({ bridge, enabled, visible, onMemoryDraft }: { bridge: WorkspaceClient; enabled: boolean; visible: boolean; onMemoryDraft?: (content: string, role: 'ASSISTANT' | 'KNOWLEDGE') => void }) {
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED'>('ACTIVE'), [page, setPage] = useState(0)
  const [list, setList] = useState<KnowledgeList | null>(null), [detail, setDetail] = useState<KnowledgeDetail | null>(null)
  const [preview, setPreview] = useState<KnowledgePreview | null>(null), [revision, setRevision] = useState('')
  const [sourceTarget, setSourceTarget] = useState<KnowledgeSourceTarget | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false), [stale, setStale] = useState(false), [importReceipt, setImportReceipt] = useState<KnowledgeImport | null>(null)
  const [listLoading, setListLoading] = useState(false), [listError, setListError] = useState('')
  const listView = useRef({ status, page }), listRequest = useRef(0)
  const listActive = useRef(enabled && visible); listActive.current = enabled && visible
  const generation = useRef(0), selection = useRef(0), offsets = useRef<number[]>([]), selected = useRef<string | null>(null)
  const trail = useRef<{ back: ReadingPosition[]; forward: ReadingPosition[] }>({ back: [], forward: [] })
  const filter = useRef<HTMLSelectElement>(null)
  const [corpusEpoch, setCorpusEpoch] = useState(0)
  const document = detail?.document
  const processing = document?.processingState === 'PENDING' || document?.processingState === 'PARSING'
  const draftExcerpt = (start: number, end: number) => {
    if (!enabled || !visible || busy || stale || !preview || !document || preview.documentId !== document.documentId
      || preview.sourceRevision !== revision || !Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > preview.text.length) return
    const content = preview.text.slice(start, end)
    if (!content.trim() || !unicode(content)) return
    onMemoryDraft?.(formatMemorySource({ excerpt: content, title: document.title, documentId: preview.documentId,
      sourceRevision: preview.sourceRevision, startOffset: preview.offset + start, endOffset: preview.offset + end }), 'KNOWLEDGE')
  }
  const position = (): ReadingPosition | null => preview ? { documentId: preview.documentId, revision: preview.sourceRevision, offset: preview.offset, target: sourceTarget, offsets: [...offsets.current] } : null
  const remember = () => {
    const current = position()
    if (current) trail.current.back = [...trail.current.back.slice(-31), current]
    trail.current.forward = []
  }
  const report = (e: unknown) => { setError(e instanceof Error ? e.message : 'Knowledge 操作暂时不可用。'); if (e instanceof BridgeError && ['KnowledgeRevisionConflict', 'OutcomeUnknown', 'KnowledgeDeleteIncomplete'].includes(e.code)) setStale(true) }
  const changeListView = (nextStatus: typeof status, nextPage: number) => {
    if (listView.current.status === nextStatus && listView.current.page === nextPage) return
    listRequest.current++; listView.current = { status: nextStatus, page: nextPage }
    setStatus(nextStatus); setPage(nextPage); setList(null); setListError(''); setListLoading(false)
  }
  const refreshList = async (corpusChanged = true) => {
    if (!listActive.current) return
    const epoch = generation.current, request = ++listRequest.current, view = listView.current
    const current = () => epoch === generation.current && request === listRequest.current && listActive.current
    setListLoading(true); setListError('')
    try {
      const result = await bridge.listKnowledge(view.status, view.page)
      if (!current()) return
      if (corpusChanged) setCorpusEpoch(n => n + 1)
      // Deleting/archiving the final item of a page must not look like an empty library.
      if (view.page > 0 && view.page * 20 >= result.total) {
        changeListView(view.status, Math.max(0, Math.ceil(result.total / 20) - 1)); return
      }
      setList(result)
    } catch (e) {
      if (current()) { setList(null); setListError(e instanceof Error ? e.message : '文档列表读取失败，请刷新列表。') }
    } finally { if (current()) setListLoading(false) }
  }
  const load = async (id: string, reset = true) => {
    const epoch = generation.current, pick = ++selection.current
    const result = await bridge.getKnowledge(id)
    if (epoch !== generation.current || pick !== selection.current) return false
    const keepReader = !reset && document?.documentId === id && !!revision
    if (keepReader && !result.revisions.some(r => r.sourceRevision === revision)) throw new BridgeError('KnowledgeNotFound', '所选源版本已不可用。保留此前阅读内容，未切换到其他版本。')
    if (!keepReader) { remember(); setPreview(null); setSourceTarget(null); offsets.current = []; setRevision(result.document.currentReadyRevision ?? '') }
    selected.current = id; setDetail(result); setStale(false)
    return true
  }
  const run = async (work: () => Promise<void>) => {
    if (busy) return; const epoch = generation.current; setBusy(true); setError(''); setNotice('')
    try { await work() } catch (e) { if (epoch === generation.current) report(e) }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  useEffect(() => {
    const clear = () => { generation.current++; selection.current++; listRequest.current++; selected.current = null; offsets.current = []; trail.current = { back: [], forward: [] }; setList(null); setListLoading(false); setListError(''); setDetail(null); setPreview(null); setSourceTarget(null); setRevision(''); setError(''); setNotice(''); setBusy(false); setStale(false); setImportReceipt(null); setConfirmDelete(false) }
    const unsubscribe = bridge.onSession(clear); return () => { clear(); unsubscribe() }
  }, [bridge])
  useEffect(() => {
    if (!visible) {
      selection.current++; offsets.current = []; trail.current = { back: [], forward: [] }
      setPreview(null); setSourceTarget(null)
    }
  }, [visible])
  useEffect(() => {
    if (!enabled || !visible) { setListLoading(false); return }
    void refreshList(false)
    return () => { listRequest.current++ }
  }, [bridge, enabled, visible, status, page])
  useEffect(() => {
    if (!enabled || !visible || !processing || !document) return
    const epoch = generation.current, pick = selection.current, id = document.documentId
    const timer = setTimeout(() => { void bridge.getKnowledge(id).then(result => { if (epoch === generation.current && pick === selection.current) { setDetail(result); setRevision(current => current || result.document.currentReadyRevision || ''); void refreshList() } }).catch(e => { if (epoch === generation.current && pick === selection.current) report(e) }) }, 1000)
    return () => clearTimeout(timer)
  }, [bridge, enabled, visible, document, processing, revision])
  const readAcceptedImport = async (job: KnowledgeJob, epoch: number) => {
    if (epoch !== generation.current || !listActive.current) return
    changeListView('ACTIVE', 0)
    const loaded = await load(job.documentId)
    if (epoch !== generation.current || !loaded) return
    setImportReceipt(null); setNotice('导入已接收，资料详情已读取。处理状态见文档详情。')
    await refreshList()
  }
  const importSource = (update: boolean) => run(async () => {
    if (importReceipt) return
    const epoch = generation.current
    const result = await bridge.importKnowledge(update && document ? document.documentId : null, update && document ? document.metadataVersion : null)
    if (epoch !== generation.current) return
    if (result.outcome === 'CANCELLED') { setNotice('已取消导入。'); return }
    setImportReceipt(result)
    if (result.outcome === 'UNKNOWN') { setNotice('导入结果暂无法确认；请检查导入状态，文件不会自动重发。'); return }
    setNotice('导入已接收，正在读取资料详情。读取失败不会重新导入。')
    await readAcceptedImport(result.job!, epoch)
  })
  const checkImport = () => run(async () => {
    if (!importReceipt?.requestId) return
    const epoch = generation.current, requestId = importReceipt.requestId
    const job = await bridge.knowledgeImportState(requestId)
    if (epoch !== generation.current) return
    setImportReceipt({ outcome: 'ACCEPTED', requestId, job })
    setNotice('已查到原导入请求，正在读取资料详情。')
    await readAcceptedImport(job, epoch)
  })
  const showPreview = (offset: number, history: 'push' | 'back' | 'reset' | 'before' = 'push') => run(async () => {
    if (!enabled || !visible || !document || !revision) return
    const destination = history === 'back' ? offsets.current.at(-1) : offset
    if (destination === undefined) return
    const epoch = generation.current, pick = selection.current
    const result = await (history === 'before' ? bridge.previewKnowledgeBefore(document.documentId, revision, destination) : bridge.previewKnowledge(document.documentId, revision, destination))
    if (epoch !== generation.current || pick !== selection.current) return
    // A failed read must leave both the displayed source and its back stack intact.
    if (history === 'reset') offsets.current = []
    else if (history === 'back') offsets.current = offsets.current.slice(0, -1)
    else if (preview && preview.offset !== result.offset) offsets.current = [...offsets.current.slice(-255), preview.offset]
    setPreview(result)
  })
  const mutation = (action: 'archive' | 'restore' | 'delete') => run(async () => {
    if (!document) return
    const epoch = generation.current, id = document.documentId
    if (action === 'delete') { await bridge.deleteKnowledge(id, document.metadataVersion); if (epoch !== generation.current) return; selection.current++; selected.current = null; offsets.current = []; trail.current = { back: trail.current.back.filter(p => p.documentId !== id), forward: trail.current.forward.filter(p => p.documentId !== id) }; setDetail(null); setPreview(null); setSourceTarget(null); filter.current?.focus(); setNotice('文档及保留的源版本已删除。') }
    else { await (action === 'archive' ? bridge.archiveKnowledge(id, document.metadataVersion) : bridge.restoreKnowledge(id, document.metadataVersion)); if (epoch !== generation.current) return; const loaded = await load(id); if (epoch !== generation.current || !loaded) return; setNotice(action === 'archive' ? '文档已归档。' : '文档已恢复。') }
    await refreshList()
  })
  const openComparedSource = (sourceRevision: string, offset: number) => run(async () => {
    if (!enabled || !visible || !document) return
    const epoch = generation.current, pick = selection.current
    const result = await bridge.previewKnowledge(document.documentId, sourceRevision, offset)
    if (epoch !== generation.current || pick !== selection.current) return
    remember(); setRevision(sourceRevision); setSourceTarget(null); offsets.current = []; setPreview(result)
  })
  const openFoundSource = (match: SourceMatch) => run(async () => {
    if (!enabled || !visible || !document || !revision) return
    const epoch = generation.current, pick = selection.current
    const result = await bridge.previewKnowledge(document.documentId, revision, match.startOffset)
    if (epoch !== generation.current || pick !== selection.current) return
    remember(); offsets.current = []; setPreview(result)
    setSourceTarget({ documentId: document.documentId, sourceRevision: revision, startOffset: match.startOffset, endOffset: match.endOffset, startLine: match.startLine, endLine: match.endLine, kind: 'find' })
  })
  const openHit = (hit: Pick<KnowledgeSearchHit, 'documentId' | 'sourceRevision' | 'startOffset' | 'endOffset' | 'startLine' | 'endLine'>, kind: KnowledgeSourceTarget['kind'] = 'search') => run(async () => {
    if (!enabled || !visible) return
    const epoch = generation.current, pick = ++selection.current
    const nextDetail = await bridge.getKnowledge(hit.documentId)
    if (epoch !== generation.current || pick !== selection.current) return
    const result = await bridge.previewKnowledge(hit.documentId, hit.sourceRevision, hit.startOffset)
    if (epoch !== generation.current || pick !== selection.current) return
    // Commit the complete destination together. A missing source or failed read
    // must not replace the previous document/version, highlight or return stack.
    remember(); selected.current = hit.documentId; offsets.current = []; setStale(false)
    setDetail(nextDetail); setRevision(hit.sourceRevision); setPreview(result)
    setSourceTarget({ documentId: hit.documentId, sourceRevision: hit.sourceRevision, startOffset: hit.startOffset, endOffset: hit.endOffset, startLine: hit.startLine, endLine: hit.endLine, kind })
  })
  const revisit = (direction: 'back' | 'forward') => run(async () => {
    if (!enabled || !visible) return
    const destination = trail.current[direction].at(-1)
    if (!destination) return
    const epoch = generation.current, pick = ++selection.current
    const nextDetail = await bridge.getKnowledge(destination.documentId)
    if (epoch !== generation.current || pick !== selection.current) return
    const result = await bridge.previewKnowledge(destination.documentId, destination.revision, destination.offset)
    if (epoch !== generation.current || pick !== selection.current) return
    const current = position(), opposite = direction === 'back' ? 'forward' : 'back'
    // Re-read the exact immutable source. Failed navigation never consumes history
    // or presents cached text for a source that may no longer be available.
    trail.current[direction] = trail.current[direction].slice(0, -1)
    if (current) trail.current[opposite] = [...trail.current[opposite].slice(-31), current]
    selected.current = destination.documentId; offsets.current = [...destination.offsets]
    setDetail(nextDetail); setRevision(destination.revision); setPreview(result); setSourceTarget(destination.target); setStale(false)
  })
  const openLine = (line: number) => run(async () => {
    if (!enabled || !visible || !document || !revision) return
    const epoch = generation.current, pick = selection.current, deadline = Date.now() + 30_000
    const check = () => {
      if (epoch !== generation.current || pick !== selection.current) throw new Error('阅读位置已变更。')
      if (Date.now() > deadline) throw new Error('原文读取超时，保留当前阅读位置。')
    }
    let text: string
    try { text = await readSourceText(bridge, document.documentId, revision, check, '此版本超出行号定位的读取范围，请使用原文分页阅读。') }
    catch (failure) { if (epoch !== generation.current || pick !== selection.current) return; throw failure }
    let startOffset = 0
    for (let current = 1; current < line; current++) {
      const next = text.indexOf('\n', startOffset)
      if (next < 0) throw new Error('所选版本没有此行，保留当前阅读位置。')
      startOffset = next + 1
    }
    if (startOffset >= text.length) throw new Error('所选行位于文本末尾，没有可读取的正文。')
    const newline = text.indexOf('\n', startOffset), endOffset = newline < 0 ? text.length : Math.max(startOffset + 1, newline)
    const result = await bridge.previewKnowledge(document.documentId, revision, startOffset)
    if (epoch !== generation.current || pick !== selection.current) return
    check(); remember(); offsets.current = []; setPreview(result)
    setSourceTarget({ documentId: document.documentId, sourceRevision: revision, startOffset, endOffset, startLine: line, endLine: line, kind: 'line' })
  })
  return <section className="knowledge-page" aria-label="Knowledge 管理">
    <div className="card-heading memory-toolbar knowledge-toolbar"><div><h2>我的文档</h2><p className="hint">仅使用你主动导入的内容</p></div><div className="conversation-actions">
      <button className="primary" disabled={!enabled || busy || !!importReceipt} onClick={() => { void importSource(false) }}>导入文档</button>
      <button disabled={!enabled || busy} onClick={() => { void run(async () => { await bridge.open('native.openKnowledgeBackup') }) }}>知识库备份…</button></div></div>
    <p className="hint backup-warning">知识库需单独备份；源文件、数据库与备份均含明文内容，请妥善保管。</p>
    <p className="hint">支持 UTF-8 TXT、Markdown、Word（.docx）正文和 PDF 文字层摘录。Word / PDF 须先预览确认，不保存原文件、图片与版式；PDF 不进行 OCR，扫描件须先转为文字并核对。</p>
    <details className="advanced-section"><summary>从网页添加资料</summary><KnowledgeWebCapture bridge={bridge} enabled={enabled && !busy} visible={visible} saved={refreshList} open={id => run(async () => { await load(id) })} /></details>
    <KnowledgeSearch bridge={bridge} enabled={enabled && !busy} visible={visible} corpusEpoch={corpusEpoch} open={openHit} currentDocument={document} />
    <details className="advanced-section"><summary>向知识库提问</summary><KnowledgeAnswer bridge={bridge} enabled={enabled && !busy} visible={visible} open={hit => openHit(hit, 'citation')} currentDocument={document} onMemoryDraft={onMemoryDraft} /></details>
    <div className="knowledge-layout">
      <section className="card knowledge-list" aria-labelledby="knowledge-list-title"><h2 id="knowledge-list-title">文档列表</h2>
        <div className="knowledge-list-tools"><label className="sr-only" htmlFor="knowledge-status">文档状态</label><select id="knowledge-status" ref={filter} value={status} disabled={busy} onChange={e => changeListView(e.target.value as typeof status, 0)}><option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option></select>
          <button disabled={!enabled || busy || listLoading} onClick={() => { void run(refreshList) }}>刷新列表</button></div>
        {listLoading && <p role="status">正在读取文档列表…</p>}
        {listError && <p role="alert" className="error">{listError}</p>}
        <ul tabIndex={0} aria-label="文档列表" aria-busy={listLoading}>{list?.items.map(d => <li key={d.documentId}><button className="memory-select" disabled={busy || listLoading} aria-pressed={document?.documentId === d.documentId} onClick={() => { void run(async () => { await load(d.documentId) }) }}>{d.title}<span>{labels[d.processingState]} · 源版本 {d.currentReadyRevision ?? '无'}</span></button></li>)}</ul>
        {list?.items.length === 0 && <p className="empty-state">{status === 'ACTIVE' ? '还没有文档。点击上方「导入文档」，添加第一份参考资料。' : '没有已归档的文档。'}</p>}
        <div className="pagination"><button disabled={!enabled || busy || listLoading || page === 0} onClick={() => changeListView(status, page - 1)}>上一页文档</button><span>{page + 1} / {Math.max(page + 1, Math.ceil((list?.total ?? 0) / 20))}</span><button disabled={!enabled || busy || listLoading || !list || (page + 1) * 20 >= list.total} onClick={() => changeListView(status, page + 1)}>下一页文档</button></div>
      </section>
      <section className="card knowledge-detail" aria-labelledby="knowledge-detail-title"><h2 id="knowledge-detail-title">{document?.title ?? '选择一个文档'}</h2>
        {(trail.current.back.length > 0 || trail.current.forward.length > 0) && <nav className="pagination" aria-label="来源阅读记录">
          <button disabled={!enabled || !visible || busy || !trail.current.back.length} onClick={() => { void revisit('back') }}>返回上一处来源</button>
          <button disabled={!enabled || !visible || busy || !trail.current.forward.length} onClick={() => { void revisit('forward') }}>前进到下一处来源</button>
          <span className="hint">仅保留本页最近 32 处阅读位置，返回时重新核对原版本。</span>
        </nav>}
        {!document && <p className="empty-state">从左侧选择文档，查看版本与原文。</p>}
        {document && <><p role="status" aria-live="polite">{document.status === 'ACTIVE' ? '使用中' : '已归档'} · {labels[document.processingState]}</p>
          {processing && <p role="progressbar" aria-label="Knowledge 导入处理" aria-valuetext={labels[document.processingState]}>等待完整 READY 发布；旧 READY 版本仍可预览。</p>}
          {detail.job?.errorCode && <p className="error" role="alert">本次处理未完成：{detail.job.errorCode}。已存在的 READY 版本保持可用；可显式重新选择文件导入。</p>}
          {stale && <p role="alert">请刷新详情确认当前状态后再修改；不会自动重试。</p>}
          <div className="knowledge-reader-tools"><label htmlFor="knowledge-revision">已发布源版本<select id="knowledge-revision" value={revision} disabled={busy || !detail.revisions.length} onChange={e => { remember(); selection.current++; setRevision(e.target.value); setPreview(null); setSourceTarget(null); offsets.current = [] }}>{!detail.revisions.length && <option value="">尚无 READY 版本</option>}{detail.revisions.map(r => <option key={r.sourceRevision} value={r.sourceRevision}>版本 {r.sourceRevision} · {r.sourceType} · {r.byteLength} bytes</option>)}</select></label>
            <button disabled={!enabled || busy || !revision} onClick={() => { void showPreview(0, 'reset') }}>预览源文本</button></div>
          {preview && <KnowledgeSourcePreview preview={preview} target={sourceTarget} currentRevision={document.currentReadyRevision} disabled={!enabled || busy || !visible || stale} hasPrevious={offsets.current.length > 0} navigate={showPreview} saveExcerpt={onMemoryDraft ? draftExcerpt : undefined} />}
          {revision && visible && <KnowledgeSourceLine key={`line/${document.documentId}/${revision}`} disabled={!enabled || busy} open={openLine} />}
          {revision && <KnowledgeSourceFind key={`${document.documentId}/${revision}`} bridge={bridge} documentId={document.documentId} revision={revision} enabled={enabled && visible} disabled={busy} open={openFoundSource} />}
          <KnowledgeVersionComparison key={`${document.documentId}/${document.metadataVersion}/${detail.revisions.length}`} bridge={bridge} detail={detail} enabled={enabled && visible} disabled={busy} open={openComparedSource} />
          <div className="conversation-actions"><button disabled={!enabled || busy} onClick={() => { void run(async () => { await load(document.documentId, false) }) }}>刷新详情</button>
            <button disabled={!enabled || busy || processing || stale || !!importReceipt || document.status !== 'ACTIVE'} onClick={() => { void importSource(true) }}>导入新源版本</button>
            <button disabled={!enabled || busy || processing || stale} onClick={() => { void mutation(document.status === 'ACTIVE' ? 'archive' : 'restore') }}>{document.status === 'ACTIVE' ? '归档' : '恢复'}</button>
            <button className="destructive" disabled={!enabled || busy || processing || stale} onClick={() => setConfirmDelete(true)}>物理删除…</button>
            {processing && document.requestId && <button disabled={!enabled || busy} onClick={() => { void run(async () => { await bridge.cancelKnowledgeImport(document.documentId, document.requestId!); await load(document.documentId) }) }}>取消导入</button>}</div>
        </>}
      </section>
    </div>
    {importReceipt && <section className="card" aria-label="本次文档导入">
      <p>{importReceipt.outcome === 'UNKNOWN' ? '导入结果待确认。只检查原请求，不会重复发送文件。' : `文件已接收，最近确认状态：${labels[importReceipt.job!.state]}。可重新读取资料，无需再次导入。`}</p>
      <button disabled={busy || !enabled || !visible} onClick={() => { void checkImport() }}>{importReceipt.outcome === 'UNKNOWN' ? '检查导入状态' : '重新读取已接收资料'}</button>
    </section>}
    {error && <p role="alert" className="error">{error}</p>}<p role="status">{notice}</p>
    {confirmDelete && document && <ConfirmationDialog title="物理删除 Knowledge 文档？" confirmText="确认删除" cancel={() => setConfirmDelete(false)} confirm={() => { setConfirmDelete(false); void mutation('delete') }}><p>将删除此文档的所有源版本、私有源文件、normalized text 和 locator 数据。此操作无法撤销，不承诺取证级擦除。</p></ConfirmationDialog>}
  </section>
}
