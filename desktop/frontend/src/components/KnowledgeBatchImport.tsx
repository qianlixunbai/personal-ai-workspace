import { useEffect, useRef, useState } from 'react'
import { BridgeError, type WorkspaceClient } from '../bridge/client'
import type { KnowledgeJob, KnowledgeSelectedFile } from '../bridge/knowledge'

type Row = KnowledgeSelectedFile & { state: 'WAITING' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED' | 'FAILED'; job?: KnowledgeJob; problem?: string | undefined }
const processing = (job?: KnowledgeJob) => job?.state === 'PENDING' || job?.state === 'PARSING'
const labels = { PENDING: '等待解析', PARSING: '正在解析', READY: '已导入，可检索', FAILED: '解析失败', CANCELLED: '已取消', INTERRUPTED: '处理已中断' }
const problemText = (e: unknown) => e instanceof Error ? e.message : '导入状态暂时无法确认。'
const uncertain = (e: unknown) => !(e instanceof BridgeError) || ['OutcomeUnknown', 'ClientTimeout', 'RuntimeUnavailable', 'InvalidResponse'].includes(e.code)

export function KnowledgeBatchImport({ bridge, enabled, visible, onActive, changed, open }: {
  bridge: WorkspaceClient; enabled: boolean; visible: boolean; onActive: (active: boolean) => void; changed: () => void; open: (id: string) => void
}) {
  const [rows, setRows] = useState<Row[]>([]), [busy, setBusy] = useState(false)
  const [selectionUnknown, setSelectionUnknown] = useState(false), [notice, setNotice] = useState('')
  const entries = useRef<Row[]>([]), generation = useRef(0), locked = useRef(false), keepGoing = useRef(false)
  const available = useRef(enabled && visible); available.current = enabled && visible
  const publish = (next: Row[]) => { entries.current = next; setRows(next) }
  const replace = (row: Row) => publish(entries.current.map(item => item.requestId === row.requestId ? row : item))
  useEffect(() => { onActive(rows.length > 0 || selectionUnknown || busy) }, [rows.length, selectionUnknown, busy, onActive])
  useEffect(() => { if (!enabled || !visible) keepGoing.current = false }, [enabled, visible])
  useEffect(() => {
    const clear = () => { generation.current++; keepGoing.current = false; locked.current = false; publish([]); setBusy(false); setSelectionUnknown(false); setNotice('') }
    const unsubscribe = bridge.onSession(clear)
    return () => { generation.current++; keepGoing.current = false; unsubscribe() }
  }, [bridge])
  const choose = async () => {
    if (locked.current || !available.current || entries.current.length || selectionUnknown) return
    locked.current = true; setBusy(true); setNotice('')
    const epoch = generation.current
    try {
      const selection = await bridge.selectKnowledgeFiles()
      if (epoch !== generation.current) return
      publish(selection.files.map(file => ({ ...file, state: 'WAITING' })))
      setNotice(selection.files.length ? '核对清单后点击开始导入。所有文件都将作为新文档，不会替换已有版本。' : '已取消选择，没有导入文件。')
    } catch (e) {
      if (epoch === generation.current) { setSelectionUnknown(true); setNotice(problemText(e) + ' 尚未发起上传；请释放本次文件选择后再选择。') }
    } finally { if (epoch === generation.current) { locked.current = false; setBusy(false) } }
  }
  const inspect = async (row: Row): Promise<Row> => {
    const job = await bridge.knowledgeImportState(row.requestId)
    if (row.job && job.documentId !== row.job.documentId) throw new BridgeError('InvalidResponse', '原导入请求的资料身份不一致。')
    return { ...row, state: 'ACCEPTED', job, problem: undefined }
  }
  const check = async (row: Row) => {
    if (locked.current || !available.current) return
    const epoch = generation.current; locked.current = true; setBusy(true); setNotice('')
    try {
      const next = await inspect(row)
      if (epoch !== generation.current) return
      replace(next); changed(); setNotice('已核对原请求。继续导入剩余文件需要再次点击继续。')
    } catch (e) {
      if (epoch === generation.current) { replace({ ...row, state: 'UNKNOWN', problem: problemText(e) }); setNotice('仍无法确认原请求；保留此项，不重新上传。') }
    } finally { if (epoch === generation.current) { locked.current = false; setBusy(false) } }
  }
  const start = async () => {
    if (locked.current || !available.current || selectionUnknown || entries.current.some(row => row.state === 'UNKNOWN')) return
    const epoch = generation.current; locked.current = true; keepGoing.current = true; setBusy(true); setNotice('')
    const current = () => epoch === generation.current
    const proceed = () => current() && keepGoing.current && available.current
    try {
      for (const entry of entries.current) {
        if (!proceed()) break
        let row = entry
        if (row.state !== 'WAITING' && !processing(row.job)) continue
        if (row.state === 'WAITING') {
          row = { ...row, state: 'SENDING' }; replace(row)
          try {
            const result = await bridge.importSelectedKnowledge(row.requestId)
            if (!current()) return
            if (result.outcome === 'UNKNOWN') {
              replace({ ...row, state: 'UNKNOWN' }); setNotice('本项结果未知，已暂停后续上传。请检查原请求。'); return
            }
            row = { ...row, state: 'ACCEPTED', job: result.job! }; replace(row)
          } catch (e) {
            if (!current()) return
            replace({ ...row, state: uncertain(e) ? 'UNKNOWN' : 'FAILED', problem: problemText(e) })
            setNotice('本项未能确认完成，已暂停后续上传。已提交的文件不会重发。'); return
          }
        }
        // Wait for each accepted job before admitting another file into the bounded Runtime queue.
        for (let poll = 0; processing(row.job) && poll < 120 && proceed(); poll++) {
          await new Promise(resolve => setTimeout(resolve, 1000))
          if (!proceed()) break
          try {
            row = await inspect(row)
            if (!current()) return
            replace(row)
          } catch (e) {
            if (!current()) return
            replace({ ...row, state: 'UNKNOWN', problem: problemText(e) }); setNotice('状态读取失败，已暂停后续上传。只核对原请求。'); return
          }
        }
        if (!proceed()) break
        changed()
        if (row.job?.state !== 'READY') {
          setNotice(processing(row.job) ? '本项仍在处理，已暂停。稍后检查原请求或继续等待。' : '本项处理未完成，已暂停。可查看原因，再决定是否继续剩余文件。'); return
        }
      }
      if (current()) setNotice(keepGoing.current && available.current ? '本批导入已结束。各项结果保留在清单中。' : '已暂停后续上传，当前请求的回执已保留。')
    } finally { if (current()) { locked.current = false; setBusy(false); keepGoing.current = false } }
  }
  const discard = async () => {
    if (locked.current || !available.current) return
    const epoch = generation.current; locked.current = true; setBusy(true)
    try {
      await bridge.discardSelectedKnowledgeFiles()
      if (epoch !== generation.current) return
      const unresolved = entries.current.some(row => row.state === 'UNKNOWN' || processing(row.job))
      publish(unresolved ? entries.current.filter(row => row.state !== 'WAITING') : [])
      setSelectionUnknown(false)
      setNotice(unresolved ? '未提交文件已释放；待确认的请求保留，已提交内容没有删除。' : '本批已结束，已导入资料保持原样。')
    } catch (e) { if (epoch === generation.current) setNotice(problemText(e)) }
    finally { if (epoch === generation.current) { locked.current = false; setBusy(false) } }
  }
  const unresolved = rows.some(row => row.state === 'UNKNOWN' || processing(row.job))
  return <section className="card" aria-label="批量导入资料">
    <h3>批量导入资料</h3>
    <p className="hint">一次最多 10 份，正文合计不超过 32 MiB。Word / PDF 先逐份确认摘录。清单只保留在当前窗口；每份处理完成后再导入下一份。</p>
    <button disabled={!enabled || !visible || busy || !!rows.length || selectionUnknown} onClick={() => { void choose() }}>选择多份文件…</button>
    {rows.length > 0 && <>
      <ul aria-label="待导入清单">{rows.map(row => <li key={row.requestId}>
        <strong>{row.filename}</strong> · {row.byteLength.toLocaleString()} 字节 · {row.state === 'WAITING' ? '待确认导入' : row.state === 'SENDING' ? '正在上传' : row.state === 'UNKNOWN' ? '结果待确认' : row.state === 'FAILED' ? '提交失败' : labels[row.job!.state]}
        {(row.problem || row.job?.errorCode) && <p className="error">{row.problem ?? row.job!.errorCode}</p>}
        {(row.state === 'UNKNOWN' || processing(row.job)) && <button disabled={!enabled || !visible || busy} onClick={() => { void check(row) }}>检查 {row.filename} 的原请求</button>}
        {row.state === 'ACCEPTED' && row.job && <button disabled={!enabled || !visible || busy} onClick={() => open(row.job!.documentId)}>查看 {row.filename}</button>}
      </li>)}</ul>
      <button className="primary" disabled={!enabled || !visible || busy || rows.some(row => row.state === 'UNKNOWN') || !rows.some(row => row.state === 'WAITING' || processing(row.job))} onClick={() => { void start() }}>{rows.every(row => row.state === 'WAITING') ? '确认并开始导入' : '继续导入剩余文件'}</button>
      {busy && <button onClick={() => { keepGoing.current = false; setNotice('正在暂停；等待当前请求返回后保留回执，不会重发。') }}>暂停后续上传</button>}
    </>}
    {(rows.length > 0 || selectionUnknown) && <button disabled={!enabled || !visible || busy} onClick={() => { void discard() }}>{unresolved ? '放弃未提交的文件' : selectionUnknown ? '释放本次文件选择' : '结束本批'}</button>}
    {notice && <p role="status">{notice}</p>}
  </section>
}
