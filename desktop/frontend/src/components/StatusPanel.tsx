import type { ShellStatus } from '../bridge/contracts'
const credentialLabels = { Ready: '有效', Missing: '未导入', Invalid: '需要重新导入', Unavailable: '暂时无法确认' }
export function StatusPanel({ status }: { status: ShellStatus | null }) {
  return <dl className="status-list">
    <div><dt>Java Runtime 健康检查</dt><dd><span className={status?.runtime === 'Available' ? 'dot online' : 'dot'} />{status ? status.runtime === 'Available' ? '本次检查可访问' : '暂时无法确认' : '尚未检查'}</dd></div>
    <div><dt>原生认证</dt><dd>{status ? credentialLabels[status.credential] : '尚未检查'}</dd></div>
    <div><dt>桌面界面连接</dt><dd>{status ? 'Desktop Bridge 已响应；不代表模型或数据库可用' : '尚未确认；请从托盘重新打开工作区'}</dd></div>
  </dl>
}
