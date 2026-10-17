import { useEffect, useRef, useState } from 'react'
import { BridgeError, WorkspaceClient } from '../bridge/client'
import type { VisionReadiness, VisionPick, VisionTask } from '../bridge/vision'

const reasons: Record<string, string> = { TEXT_UNAVAILABLE: 'Active Model 文本能力不可用', MODEL_UNSUPPORTED: '当前模型不支持首期 Vision', TOKEN_EVIDENCE_MISSING: '尚未具备可信 runner / token 预算证据', VISION_UNVERIFIED: '当前 Active Model 尚未完成独立图片验证' }
export function VisionPanel({ bridge, enabled }: { bridge: WorkspaceClient; enabled: boolean }) {
  const [ready, setReady] = useState<VisionReadiness | null>(null)
  const [image, setImage] = useState<VisionPick | null>(null)
  const [question, setQuestion] = useState('')
  const [task, setTask] = useState<VisionTask | null>(null)
  const [busy, setBusy] = useState(false)
  const [consumed, setConsumed] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const epoch = useRef(0)
  const selectedHandle = useRef<string | null>(null)
  const lock = useRef(false)
  const composing = useRef(false)
  const active = task?.status === 'QUEUED' || task?.status === 'RUNNING'
  const message = (e: unknown) => e instanceof BridgeError ? e.message : 'Vision 操作暂时不可用。'
  useEffect(() => {
    const clear = () => { epoch.current++; selectedHandle.current = null; lock.current = false; setImage(null); setQuestion(''); setTask(null); setBusy(false); setConsumed(false); setError(''); setNotice('') }
    const off = bridge.onSession(() => { clear(); setReady(null) })
    const closed = bridge.onVisionClear(handle => { if (selectedHandle.current === handle) clear() })
    return () => { epoch.current++; off(); closed(); if (bridge.ready) void bridge.clearVision().catch(() => {}) }
  }, [bridge])
  useEffect(() => {
    if (!enabled) return
    const current = epoch.current
    void bridge.visionReadiness().then(value => { if (epoch.current === current) setReady(value) }).catch(e => { if (epoch.current === current) setError(message(e)) })
  }, [bridge, enabled])
  useEffect(() => {
    if (!active || !task) return
    const current = epoch.current
    const timer = setTimeout(() => {
      void bridge.visionTask(task.taskId).then(value => { if (epoch.current === current) { setTask(value); if (value.error) setError(value.error.message) } })
        .catch(e => { if (epoch.current === current) { setTask({ ...task, status: 'UNKNOWN', result: null, error: null }); setError(message(e)); setNotice('执行结果未知；不会自动重发。404 或 Runtime 重启不能证明未执行。') } })
    }, 350)
    return () => clearTimeout(timer)
  }, [bridge, task, active])
  const run = async (operation: () => Promise<void>) => {
    if (!enabled || lock.current) return
    lock.current = true; setBusy(true); setError('')
    const current = epoch.current
    try { await operation() } catch (e) { if (current === epoch.current) setError(message(e)) }
    finally { if (current === epoch.current) { lock.current = false; setBusy(false) } }
  }
  const pick = () => {
    if (!enabled || lock.current) return
    epoch.current++; selectedHandle.current = null
    return run(async () => { const current = epoch.current; setTask(null); setImage(null); setQuestion(''); setConsumed(false); setNotice(''); const value = await bridge.pickVision(); if (current === epoch.current && value.changed) { selectedHandle.current = value.handle; setImage(value) } })
  }
  const submit = () => run(async () => {
    if (!image?.handle || consumed || composing.current) return
    const current = epoch.current; setConsumed(true); setTask(null); setNotice('正在提交；取消不保证 Provider 已停止。')
    try { const value = await bridge.submitVision(image.handle, question); if (current === epoch.current) { setTask(value); setNotice(value.status === 'UNKNOWN' ? '执行结果未知；只查询已冻结任务，不自动重发。' : ''); if (value.error) setError(value.error.message) } }
    catch (e) { if (current === epoch.current) { setNotice('未确认提交结果；只可检查已知任务，不会自动重发。'); throw e } }
  })
  const clear = async () => { epoch.current++; selectedHandle.current = null; const current = epoch.current; lock.current = false; setBusy(false); setImage(null); setTask(null); setQuestion(''); setConsumed(false); setNotice(''); setError(''); try { await bridge.clearVision() } catch (e) { if (epoch.current === current) setError(message(e)) } }
  return <section className="card" aria-labelledby="vision-title">
    <h2 id="vision-title">单图 Vision</h2>
    <p role="status">{ready?.available ? '已独立验证图片执行能力' : ready?.reason ? reasons[ready.reason] : '等待本机 Vision 状态'}</p>
    <div className="operation-actions">
      <button className="secondary" disabled={!enabled || busy} onClick={() => { void pick() }}>选择 PNG / JPEG…</button>
      <button className="secondary" disabled={!enabled || busy} onClick={() => { void run(async () => { const current = epoch.current; const value = await bridge.visionReadiness(); if (current === epoch.current) setReady(value) }) }}>刷新状态</button>
      <button className="secondary" disabled={!enabled || busy || ready?.reason !== 'VISION_UNVERIFIED'} onClick={() => { void run(async () => { const current = epoch.current; const value = await bridge.validateVision(); if (current === epoch.current) { if (value.readiness) setReady(value.readiness); setNotice(value.state === 'COMPLETED' ? '图片验证已完成' : value.state === 'CANCELLED' ? '图片验证已取消' : '验证结果未知；不会自动重发') } }) }}>原生确认并验证图片能力…</button>
    </div>
    {image?.changed && <p>{image.mediaType} · {image.width} × {image.height} · 请检查 WPF 原生预览。保持比例并白底补边至 1024 × 1024，不应用 EXIF 旋转。</p>}
    <label htmlFor="vision-question">图片问题</label>
    <textarea id="vision-question" value={question} disabled={busy || consumed} spellCheck={false} autoComplete="off" maxLength={1000}
      onChange={e => setQuestion(e.target.value)} onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }} />
    <div className="operation-actions">
      <button className="primary" disabled={!enabled || busy || consumed || !ready?.available || !image?.handle || !question.trim() || question.length > 1000 || new TextEncoder().encode(question).length > 4096} onClick={() => { void submit() }}>提交单图</button>
      <button className="secondary" disabled={!enabled || busy || !task || (!active && task.status !== 'UNKNOWN')} onClick={() => { void run(async () => { const current = epoch.current; const value = await bridge.visionTask(task!.taskId, true); if (current === epoch.current) { setTask(value); setNotice('已请求取消；这不证明 Provider 已停止。') } }) }}>取消任务</button>
      <button className="secondary" disabled={!enabled || busy || !consumed} onClick={() => { void run(async () => { const current = epoch.current; const value = await bridge.currentVisionTask(); if (current === epoch.current) setTask(value) }) }}>检查已知任务</button>
      <button className="secondary" disabled={!enabled} onClick={() => { void clear() }}>清理图片和结果</button>
    </div>
    {task && <p role="status">{task.status === 'UNKNOWN' ? '执行结果未知' : task.status}</p>}
    {notice && <p role="status">{notice}</p>}{error && <p role="alert" className="error">{error}</p>}
    {task?.status === 'SUCCEEDED' && <pre className="operation-result" dir="auto">{task.result}</pre>}
  </section>
}
