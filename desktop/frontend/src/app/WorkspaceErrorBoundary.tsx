import type { ReactNode } from 'react'
import { ErrorBoundary } from 'react-error-boundary'

export function WorkspaceErrorBoundary({ children }: { children: ReactNode }) {
  return <ErrorBoundary fallback={<main className="page"><section className="card" role="alert">
    <h1>工作区页面暂时无法显示</h1>
    <p>重新打开会清除未提交的草稿。已提交的任务不会自动重试，可重新打开会话查看结果。</p>
    <button className="secondary" onClick={() => window.location.reload()}>重新打开工作区</button>
  </section></main>}>
    {children}
  </ErrorBoundary>
}
