import { useEffect, useState, useSyncExternalStore } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { ShellStatus } from '../bridge/contracts'
import type { Observation } from '../bridge/statusObservations'
import type { Page } from '../app/navigation'

// A presentation age limit, never an admission rule. No timer below issues bridge requests.
export const observationFreshMs = 60_000
type Summary = { label: string; detail: string; tone: 'unknown' | 'attention' | 'observed' }
const unknown = (label: string, detail: string): Summary => ({ label, detail, tone: 'unknown' })
const attention = (label: string, detail: string): Summary => ({ label, detail, tone: 'attention' })
const observed = (label: string, detail: string): Summary => ({ label, detail, tone: 'observed' })
function unavailable<T>(observation: Observation<T>, now: number, sourceTime?: string): Summary | null {
  if (observation.phase === 'checking') return unknown('正在检查', '等待本次回执；旧结果不作为当前状态。')
  if (observation.phase === 'failed') return attention('检查失败', observation.reason ?? '请到对应设置区显式重新检查。')
  if (observation.phase === 'invalidated') return unknown('需要重新检查', observation.reason ?? '旧结果已失效。')
  if (!observation.value || observation.receivedAt === null) return unknown('尚未检查', '进入对应设置区，按需显式检查。')
  const source = sourceTime ? Date.parse(sourceTime) : observation.receivedAt
  if (now < observation.receivedAt || source > now) return unknown('检查时间待核对', '本机或来源时间发生偏差，请核对时间后重新检查。')
  if (now - Math.min(source, observation.receivedAt) >= observationFreshMs)
    return unknown('结果已过期', '已超过一分钟，当前状态未知；需要时请显式重新检查。')
  return null
}
function jumpTo(id: string) {
  const target = document.getElementById(id)
  if (!target) return
  for (let parent = target.parentElement; parent; parent = parent.parentElement)
    if (parent instanceof HTMLDetailsElement) parent.open = true
  target.focus()
  target.scrollIntoView?.({ block: 'nearest' })
}
function ObservationCard({ title, summary, observation, sourceTime, target, action }: {
  title: string; summary: Summary; observation: Observation<unknown>; sourceTime?: string
  target: string; action: string
}) {
  return <article className="subsystem-card" aria-label={title}>
    <h3>{title}</h3><p className={`subsystem-state state-${summary.tone}`}>{summary.label}</p>
    <p className="hint">{summary.detail}</p>
    <p className="observation-time">{observation.receivedAt === null ? '尚无检查回执' : <>
      {observation.phase === 'failed' ? '失败回执：' : observation.phase === 'invalidated' ? '上次回执（已失效）：' : '检查回执：'}
      <time dateTime={new Date(observation.receivedAt).toISOString()}>{new Date(observation.receivedAt).toLocaleString('zh-CN')}</time>
    </>}{sourceTime && <><br />来源检查：<time dateTime={sourceTime}>{new Date(sourceTime).toLocaleString('zh-CN')}</time></>}</p>
    <button className="secondary" onClick={() => jumpTo(target)}>{action}</button>
  </article>
}
export function StatusPanel({ bridge, status, navigate }: {
  bridge: WorkspaceClient; status: ShellStatus | null; navigate?: (page: Page) => void
}) {
  const observations = useSyncExternalStore(bridge.statusObservations.subscribe, bridge.statusObservations.getSnapshot)
  const [, tick] = useState(0)
  useEffect(() => {
    const update = () => tick(value => value + 1)
    const timer = setInterval(update, 5_000)
    window.addEventListener('focus', update)
    document.addEventListener('visibilitychange', update)
    return () => { clearInterval(timer); window.removeEventListener('focus', update); document.removeEventListener('visibilitychange', update) }
  }, [])
  const clock = Date.now()
  const { shell, startup, storage, model } = observations
  const shellUnavailable = unavailable(shell, clock)
  const runtime = shellUnavailable ?? (status?.runtime === 'Available' && shell.value?.runtime === 'Available'
    ? observed('本次检查可访问', '仅确认 Runtime 健康响应；存储、模型和具体功能需分别核对。')
    : attention('Runtime 待确认', '请刷新连接状态，或查看 Runtime 启动观察。'))
  const credential = shellUnavailable ?? (status?.credential === 'Ready' && shell.value?.credential === 'Ready'
    ? observed('本次认证通过', '认证结果不代表数据库读写、模型执行或恢复准入已通过。')
    : attention(status?.credential === 'Missing' ? '凭据未配置' : status?.credential === 'Invalid' ? '凭据需要更新' : '认证待确认', '到连接设置核对原生凭据。'))
  const connectionConfirmed = !shellUnavailable && status?.runtime === 'Available' && status.credential === 'Ready'
    && shell.value?.runtime === 'Available' && shell.value.credential === 'Ready'
  const host = startup.value
  const hostUnavailable = unavailable(startup, clock)
  const hostSummary = hostUnavailable ?? (host?.canQueryStorage
    ? observed('启动观察通过', '本次允许继续查询存储；数据库结果见下方独立检查。')
    : attention(host?.failure === 'ADMISSION_DENIED' ? '启动准入未通过' : host?.runtime === 'STARTING' ? 'Runtime 正在启动' : '启动或认证待处理',
      `观察码：${host?.failure}。请查看启动观察中的处理说明，原有准入限制继续保留。`))
  const data = storage.value
  const storageUnavailable = unavailable(storage, clock, data?.checkedAt)
  const storageSummary = storageUnavailable ?? (!connectionConfirmed ? unknown('连接待重新确认', '当前存储可用性未知，请先核对连接。')
    : data?.backend === 'SQLITE' ? unknown('当前使用 SQLite', '该快照未验证 SQLite 读写；PostgreSQL 的检查结果不用于推断 SQLite 可用。')
    : data?.backend !== 'POSTGRESQL' ? attention('存储配置不受支持', '请到存储设置核对配置。')
    : data.errorCode !== 'NONE' || data.configurationTrust !== 'TRUSTED_LOCAL' || data.connection !== 'CONNECTED'
      ? attention('PostgreSQL 需要处理', `检查码：${data.errorCode}；连接：${data.connection}。请核对配置、权限与结构。`)
      : data.workspaceSchema !== 'V1_V2_READY' || data.knowledgeSchema !== 'READY'
        ? attention('PostgreSQL 结构待准备', `Workspace：${data.workspaceSchema}；Knowledge：${data.knowledgeSchema}。请核对功能所需结构，初始化与迁移仍需独立确认。`)
      : observed('PostgreSQL 本次可连接', `Workspace：${data.workspaceSchema}；Knowledge：${data.knowledgeSchema}。连接成功不证明所有功能读写正常。`))
  const modelUnavailable = unavailable(model, clock)
  const m = model.value
  const modelSummary = modelUnavailable ?? (!connectionConfirmed ? unknown('连接待重新确认', '请先核对 Runtime 与原生认证，再只读检查模型。')
    : m?.status.uncertain ? attention('模型执行仍受阻断', '原执行是否结束未知。AI 请求、切换、释放与恢复继续受原有保护。')
    : m?.status.switching ? unknown('模型正在切换', '等待原操作回执，再显式检查；不要重复提交。')
    : m?.error || m?.status.error ? attention('模型检查部分失败', m?.error?.message ?? `模型状态码：${m?.status.error}`)
    : !m?.catalog ? unknown('模型目录未确认', '历史验证记录不足以证明当前 Ollama 或模型可用。')
    : m.status.installed !== 'TRUE' ? unknown('模型安装待确认', '目录可访问不代表所选模型已安装，请在模型设置中核对。')
    : !m.status.ready ? unknown('模型尚未就绪', '到模型设置核对安装、选择与验证要求。')
    : observed('检查时通过模型验证', `${m.status.activeModel}；本地执行 ${m.status.executing}、排队 ${m.status.queued}。不证明远端执行结束或其他客户端空闲。`))
  const modelConfirmed = modelSummary.tone === 'observed'
  const dataFresh = !storageUnavailable && connectionConfirmed
  const pgSchemasReady = dataFresh && data?.backend === 'POSTGRESQL' && data.configurationTrust === 'TRUSTED_LOCAL'
    && data.connection === 'CONNECTED' && data.errorCode === 'NONE' && data.workspaceSchema === 'V1_V2_READY' && data.knowledgeSchema === 'READY'
  return <section className="card workspace-status" aria-labelledby="workspace-status-title">
    <h2 id="workspace-status-title" tabIndex={-1}>工作区状态总览</h2>
    <p className="hint">汇总本次窗口中的检查结果。一分钟后标记过期，不自动查询；离开设置页仍保留时间，会话重开后清空。</p>
    <div className="subsystem-grid">
      <ObservationCard title="Java Runtime" summary={runtime} observation={shell} target="settings-status-title" action="查看连接检查" />
      <ObservationCard title="原生认证" summary={credential} observation={shell} target="settings-status-title" action="查看凭据设置" />
      <ObservationCard title="启动观察" summary={hostSummary} observation={startup} target="storage-title" action="查看启动说明" />
      <ObservationCard title="数据存储" summary={storageSummary} observation={storage} {...(data ? { sourceTime: data.checkedAt } : {})} target="storage-title" action="查看存储检查" />
      <ObservationCard title="模型与 Ollama" summary={modelSummary} observation={model} target="models-title" action="查看模型检查" />
    </div>
    <h3 className="availability-title">按要做的事继续</h3>
    <ul className="availability-list">
      <li><div><strong>阅读资料、管理记忆与对话记录</strong><p>{!connectionConfirmed ? '先核对连接，再到功能页面继续。' : pgSchemasReady
        ? '本次 PostgreSQL 结构检查通过；具体记录、私有源文件与读写仍由功能页面确认。'
        : '记录与原文读写尚未在总览确认；存储检查不会读取你的资料。'}</p></div>
        <div className="availability-actions"><button className="secondary" disabled={!navigate} onClick={() => navigate?.('knowledge')}>打开知识库</button>
          <button className="secondary" disabled={!navigate} onClick={() => navigate?.('memory')}>打开记忆</button>
          <button className="secondary" disabled={!navigate} onClick={() => navigate?.('conversations')}>打开对话</button></div></li>
      <li><div><strong>AI 问答与翻译</strong><p>{modelConfirmed ? '检查时模型通过验证；具体请求仍由 Runtime 实时准入，不保证后续执行成功。'
        : '先查看模型检查，处理未确认或阻断状态。'}</p></div><button className="secondary" onClick={() => jumpTo('models-title')}>核对 AI 使用条件</button></li>
      <li><div><strong>财务功能</strong><p>{dataFresh && data && !data.financeEnabled ? '当前配置关闭 Finance；本页不会启用。' : '独立核对 Finance 数据库与身份绑定；存储检查不证明身份绑定完成。'}</p></div>
        <button className="secondary" onClick={() => jumpTo('settings-finance-title')}>查看财务连接</button></li>
      <li><div><strong>迁移、加密备份与正式恢复</strong><p>仍需独立确认和安全条件验证；本页不会开放受保护操作。现有手动导出入口见备份说明。</p></div>
        <button className="secondary" onClick={() => jumpTo('maintenance-title')}>查看备份说明</button></li>
    </ul>
  </section>
}
