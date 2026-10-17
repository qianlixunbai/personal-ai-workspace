import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { WorkspaceClient } from '../bridge/client'
import { Port } from '../test/fixtures'
import { VisionPanel } from './VisionPanel'

afterEach(cleanup)
it('keeps image transport native, consumes a selected handle once, and displays the result as plain text', async () => {
  const port = new Port(); const bridge = new WorkspaceClient(port); port.session()
  try {
    render(<VisionPanel bridge={bridge} enabled />)
    const ready = { providerDeclaredVision: true, runtimeValidatedText: true, workspaceVisionExecutionValidated: true, available: true, selectionRevision: 1, reason: null }
    await act(async () => { port.reply(0, ready) })
    fireEvent.click(screen.getByRole('button', { name: '选择 PNG / JPEG…' }))
    const handle = '11111111-1111-4111-8111-111111111111'
    await act(async () => { port.reply(port.sent.length - 1, { changed: true, handle, mediaType: 'image/png', width: 16, height: 16 }) })
    fireEvent.change(screen.getByRole('textbox', { name: '图片问题' }), { target: { value: 'synthetic question' } })
    fireEvent.click(screen.getByRole('button', { name: '提交单图' }))
    expect(port.sent.at(-1)?.payload).toEqual({ handle, question: 'synthetic question' })
    const answer = '<script>alert(1)</script><img onerror=x> synthetic answer'
    await act(async () => { port.reply(port.sent.length - 1, { taskId: '22222222-2222-4222-8222-222222222222', status: 'SUCCEEDED', result: answer, error: null }) })
    expect(screen.getByText(answer).tagName).toBe('PRE')
    expect(document.querySelector('script,img')).toBeNull()
    expect(screen.getByRole('button', { name: '提交单图' }).hasAttribute('disabled')).toBe(true)
    expect(port.sent.filter(x => x.method === 'vision.submit')).toHaveLength(1)
    await act(async () => { port.emit({ type: 'vision.cleared', version: 1, sessionId: port.sent.at(-1)!.sessionId, handle }) })
    expect(screen.queryByText(answer)).toBeNull()
  } finally { cleanup(); bridge.dispose() }
})
