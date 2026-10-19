import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceErrorBoundary } from './WorkspaceErrorBoundary'

afterEach(cleanup)
it('replaces a failed render with recovery controls without displaying private exception details', () => {
  function Broken(): never { throw new Error('synthetic-private-conversation-content') }
  const view = render(<WorkspaceErrorBoundary><p>工作区可用</p></WorkspaceErrorBoundary>, { onCaughtError: () => {} })
  expect(screen.getByText('工作区可用')).toBeTruthy()
  view.rerender(<WorkspaceErrorBoundary><Broken /></WorkspaceErrorBoundary>)
  expect(screen.getByRole('alert').textContent).toContain('已提交的任务不会自动重试')
  expect(screen.getByRole('button', { name: '重新打开工作区' })).toBeTruthy()
  expect(view.container.textContent).not.toContain('synthetic-private-conversation-content')
})
