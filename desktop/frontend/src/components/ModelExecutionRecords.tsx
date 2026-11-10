import type { ExecutionCompletion, ExecutionRecordState, ModelExecutionInspection } from '../bridge/models'

const summaries: Record<ExecutionRecordState, string> = {
  NONE: '未发现模型执行阻断记录。其他维护条件仍需单独核对。',
  CURRENT_SESSION: '以下是本次运行保留的发送记录。已完成的项目可能与尚未结束的项目一同保留；不能只凭其中一个完成就解除阻断。',
  PREVIOUS_SESSION: '这些记录来自此前运行。当前无法核验各次执行是否结束，所有项目仍按未知处理。',
  LEGACY: '旧版阻断记录没有具体请求编号，无法列出对应执行；原执行是否结束仍未知。',
  INCOMPLETE: '执行记录写入未完成，无法可靠列出全部请求。现有阻断继续保留。',
  UNREADABLE: '执行记录损坏、缺失或读取不一致，无法可靠列出请求；不能将它视为没有阻断。',
  UNAVAILABLE: '当前无法读取模型执行记录。请保留现有工作区，核对 Runtime 状态后再显式读取。',
}
const completionText: Record<ExecutionCompletion, string> = {
  PREPARED: '已登记；尚未观察到发送',
  IN_PROGRESS: '已尝试发送；正在等待结束响应',
  COMPLETED: '本次运行已收到可核验的协议完成响应',
  NOT_SENT: '本次尝试退出前未发生发送',
  UNKNOWN: '没有可核验的结束证明',
}

export function ModelExecutionRecords({ inspection }: { inspection: ModelExecutionInspection }) {
  return <section aria-labelledby="execution-records-title">
    <h3 id="execution-records-title">模型执行记录</h3>
    <p>{summaries[inspection.recordState]}</p>
    {!!inspection.requests.length && <>
      <p className="hint">共 {inspection.requests.length} 条。本地执行编号可用于核对问题，不是服务端任务编号。以下信息仅反映本次读取时的状态，不包含提问或回复。</p>
      <ol className="execution-records" tabIndex={0} aria-label="本地模型执行记录">{inspection.requests.map(request => <li key={request.requestId}>
        <strong>{request.operation === 'chat' ? '模型推理或验证' : '模型卸载'} · {request.model}</strong>
        <p>{completionText[request.completion]}</p>
        <dl className="status-list">
          <div><dt>本地执行编号</dt><dd>{request.requestId}</dd></div>
          <div><dt>模型运行器</dt><dd>{request.runner} · 选择版本 {request.selectionRevision}</dd></div>
          <div><dt>模型摘要</dt><dd>{request.digest}</dd></div>
        </dl>
      </li>)}</ol>
    </>}
    {inspection.recordState !== 'NONE' && <p className="hint">当前提供商不支持按原请求补查结束证明。可以继续核对记录；重启、取消本地任务或人工确认都不能解除未知执行。记录不会因刷新而删除。</p>}
  </section>
}
