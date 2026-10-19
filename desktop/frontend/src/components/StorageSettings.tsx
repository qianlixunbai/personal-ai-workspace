import { useEffect, useRef, useState } from 'react'
import type { WorkspaceClient } from '../bridge/client'
import type { StorageStatus, StorageStartupStatus } from '../bridge/storage'

const labels: Record<string, string> = {
  NOT_CONFIGURED: '未配置', CONFIGURATION_INVALID: '配置无效', CONNECTED: '可连接', CONNECTION_FAILED: '连接失败', AUTHENTICATION_FAILED: '认证失败',
  ROLE_REJECTED: '应用角色不符合安全要求', ACCESS_DENIED: '权限不足', NOT_CHECKED: '尚未检查', INITIALIZATION_REQUIRED: '需要显式初始化', INCOMPATIBLE: '版本或结构不兼容',
  V1_READY: 'V1 已准备；V2 尚未准备', V1_V2_READY: 'V1 / V2 已准备', READY: '数据库结构已准备', DISABLED: '默认关闭',
  NONE: '检查完成', CONFIGURATION_INCOMPLETE: '配置字段不完整', PERMISSION_DENIED: '数据库检查权限不足', SCHEMA_INCOMPATIBLE: '数据库版本或结构不兼容',
  EXPLICIT_APPROVAL_REQUIRED: '需要用户独立确认', MAINTENANCE_ONLY: '仅受控维护可用；正式产品入口未开放', BLOCKED_SECURITY_REQUIREMENTS: '当前安全条件不允许正式恢复',
  WINDOWS_PROFILE_APPROVAL_REQUIRED: 'Windows / MMF-3 受信 Profile', VOLUME_PROTECTION_REQUIRED: '明文暂存卷保护',
  FINANCE_IDENTITY_CONFIRMATION_REQUIRED: 'Finance 历史身份确认与重新绑定', CROSS_DEVICE_VERIFICATION_REQUIRED: '真实跨设备恢复',
  TRUSTED_LOCAL: '本机私有配置与凭据已校验',
  STARTUP_CAUSE_UNAVAILABLE: '无法确认 Runtime 已启动；具体原因尚无受信证据',
  NATIVE_AUTHENTICATION_UNAVAILABLE: 'Runtime 健康检查可达，原生认证尚未通过',
  STARTING: '正在启动受控开发 Runtime', ADMISSION_DENIED: '受信开发准入或构建来源检查未通过',
  CONFIGURATION_UNTRUSTED: '本机配置未通过可信校验', CREDENTIAL_UNAVAILABLE: '所需本机凭据不可用',
  DATABASE_CONNECTION_FAILED: '数据库连接失败', DATABASE_AUTHENTICATION_FAILED: '数据库认证失败',
  SCHEMA_NOT_READY: '数据库结构或应用角色未满足准入条件', STARTUP_TIMEOUT: 'Runtime 启动超时',
  PROCESS_EXITED: '本次受控 Runtime 已退出', STARTUP_CANCELLED: 'Runtime 启动已取消',
}
export function StorageSettings({ bridge, enabled }: { bridge: WorkspaceClient; enabled: boolean }) {
  const [snapshot, setSnapshot] = useState<StorageStatus | null>(null)
  const [startup, setStartup] = useState<StorageStartupStatus | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const generation = useRef(0), inFlight = useRef(false)
  useEffect(() => {
    const reset = () => { generation.current++; inFlight.current = false; setSnapshot(null); setStartup(null); setWorking(false); setError('') }
    reset()
    const unsubscribe = bridge.onSession(reset)
    return () => { generation.current++; unsubscribe() }
  }, [bridge])
  useEffect(() => { if (!enabled) { generation.current++; inFlight.current = false; setWorking(false); setSnapshot(null); setStartup(null); setError('') } }, [enabled])
  useEffect(() => {
    if (!enabled) return
    return bridge.onStorageStartup(host => {
      setStartup(host)
      if (!host.canQueryStorage) {
        generation.current++; inFlight.current = false; setWorking(false); setSnapshot(null); setError('')
      }
    })
  }, [bridge, enabled])
  const inspect = async () => {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setWorking(true); setSnapshot(null); setStartup(null); setError('')
    try {
      const host = await bridge.storageStartupStatus()
      if (current !== generation.current) return
      setStartup(host)
      if (host.canQueryStorage) { const next = await bridge.storageStatus(); if (current === generation.current) setSnapshot(next) }
    }
    catch (problem) { if (current === generation.current) setError(problem instanceof Error ? problem.message : '存储状态暂时不可用。') }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const configure = async () => {
    if (!enabled || inFlight.current) return
    inFlight.current = true; const current = ++generation.current; setWorking(true); setError('')
    try {
      await bridge.open('native.openDatabaseSettings')
      if (current === generation.current) { inFlight.current = false; await inspect() }
    }
    catch (problem) { if (current === generation.current) setError(problem instanceof Error ? problem.message : '原生数据库设置暂时不可用。') }
    finally { if (current === generation.current) { inFlight.current = false; setWorking(false) } }
  }
  const value = (field: keyof StorageStatus) => snapshot ? labels[String(snapshot[field])] ?? '尚未检查' : '尚未检查'
  return <section className="card" aria-labelledby="storage-title" aria-busy={working}>
    <div className="card-heading"><h2 id="storage-title">本地数据与存储</h2><button className="secondary" disabled={!enabled || working} onClick={() => { void inspect() }}>重新检查存储</button></div>
    <dl className="status-list">
      <div><dt>Runtime 启动观察（WPF）</dt><dd>{startup ? startup.failure === 'NONE' ? '已响应并通过原生认证' : labels[startup.failure] : '尚未检查'}</dd></div>
      <div><dt>当前持久化引擎</dt><dd>{snapshot ? snapshot.backend === 'SQLITE' ? 'SQLite' : snapshot.backend === 'POSTGRESQL' ? 'PostgreSQL' : '配置不受支持' : '等待受控查询'}</dd></div>
      <div><dt>配置可信状态（Runtime）</dt><dd>{snapshot ? labels[snapshot.configurationTrust] : '未由 Runtime 验证'}</dd></div>
      <div><dt>PostgreSQL 配置</dt><dd>{snapshot ? snapshot.postgresqlConfigured ? '连接字段已提供' : '尚未完整配置' : '尚未检查'}</dd></div>
      <div><dt>本机 PostgreSQL 连接</dt><dd>{value('connection')}</dd></div>
      <div><dt>Workspace migration</dt><dd>{value('workspaceSchema')}</dd></div>
      <div><dt>Knowledge PostgreSQL 持久化</dt><dd>{value('knowledgeSchema')}{snapshot?.knowledgeSchema === 'READY' && snapshot.backend === 'SQLITE' ? '；当前尚未启用' : ''}</dd></div>
      <div><dt>Finance 模块 / 数据库</dt><dd>{snapshot ? `${snapshot.financeEnabled ? '已显式启用' : '关闭'} / ${value('financeSchema')}` : '尚未检查'}</dd></div>
      <div><dt>迁移准入</dt><dd>{value('migration')}</dd></div>
      <div><dt>加密备份能力</dt><dd>{value('encryptedBackup')}</dd></div>
      <div><dt>正式恢复准入</dt><dd>{value('restore')}</dd></div>
      <div><dt>最近一次检查</dt><dd>{snapshot ? new Date(snapshot.checkedAt).toLocaleString('zh-CN') : '尚未检查'}</dd></div>
      <div><dt>检查结果</dt><dd>{working ? '正在只读检查…' : snapshot ? value('errorCode') : '等待检查'}</dd></div>
    </dl>
    {error && <p role="alert" className="error">{error}</p>}
    <p className="hint" role="status">{working ? '先检查 WPF 启动观察；仅在原生认证通过后查询数据库。' : 'WPF 启动观察不执行 PostgreSQL SQL。数据库结果仅来自已运行 Runtime 的受控查询；检查不会初始化、迁移或切换存储。'}</p>
    <div className="maintenance-list">
      <article className="maintenance-row"><div><h3>本机 PostgreSQL 配置</h3><p>开发版可在 Windows 原生窗口检查连接、安全保存凭据并明确重启。密码不进入网页；不会自动初始化数据库或迁移 SQLite。新设备须重新配置凭据并独立确认 Finance 身份。</p></div><button className="secondary" disabled={!enabled || working || startup?.configurationAction !== 'NATIVE_SETUP_AVAILABLE'} onClick={() => { void configure() }}>{startup?.configurationAction === 'NATIVE_SETUP_AVAILABLE' ? '打开原生数据库设置' : '原生配置尚未开放'}</button></article>
      <article className="maintenance-row"><div><h3>数据迁移准备</h3><p>需要用户独立确认源数据、备份与回退方案；本页不检查或迁移正式个人数据。</p></div><button className="secondary" disabled>迁移与切换未开放</button></article>
      <article className="maintenance-row"><div><h3>加密备份与恢复</h3><p>直接加密与认证恢复已具备合成维护能力。正式备份与恢复尚未开放，不能以现有明文导出替代。</p></div><button className="secondary" disabled>正式备份与恢复未开放</button></article>
    </div>
    <p className="hint">{snapshot ? `尚需完成：${snapshot.securityRequirements.map(item => labels[item]).join('；')}。` : '安全条件尚未查询；正式操作保持禁用。'}</p>
    <p className="hint">受控开发进程仅报告固定安全启动码；其他启动路径或未知输出保持原因未知。连接检查不证明 Knowledge 私有文件生命周期或 Finance 身份绑定通过。</p>
  </section>
}
