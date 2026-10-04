import type { ShellStatus } from '../bridge/contracts'
const credentialLabels = { Ready: '有效', Missing: '未导入', Invalid: '需要重新导入', Unavailable: '暂时无法确认' }
export function StatusPanel({ status }: { status: ShellStatus | null }) {
  return <dl className="status-list">
    <div><dt>Runtime</dt><dd><span className={status?.runtime === 'Available' ? 'dot online' : 'dot'} />{status ? status.runtime === 'Available' ? '可连接' : '不可连接' : '等待检查'}</dd></div>
    <div><dt>凭据</dt><dd>{status ? credentialLabels[status.credential] : '等待检查'}</dd></div>
    <div><dt>工作区</dt><dd>{status ? '已连接' : '等待连接'}</dd></div>
  </dl>
}
