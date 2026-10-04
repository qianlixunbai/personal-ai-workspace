import { useEffect, useRef } from 'react'

export function ConfirmationDialog({ title, children, confirmText, confirm, cancel }: {
  title: string; children: React.ReactNode; confirmText: string; confirm: () => void; cancel: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const safe = useRef<HTMLButtonElement>(null)
  const cancelRef = useRef(cancel); cancelRef.current = cancel
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    dialog.current?.showModal(); safe.current?.focus()
    return () => { dialog.current?.close(); if (previous?.isConnected) previous.focus() }
  }, [])
  return <dialog ref={dialog} className="memory-confirmation" aria-labelledby="memory-confirm-title"
    onCancel={event => { event.preventDefault(); cancelRef.current() }} onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); cancelRef.current() }
      if (event.key === 'Tab') {
        const buttons = dialog.current?.querySelectorAll<HTMLButtonElement>('button')
        if (!buttons?.length) return
        if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[buttons.length - 1]!.focus() }
        else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) { event.preventDefault(); buttons[0]!.focus() }
      }
    }}>
    <h2 id="memory-confirm-title">{title}</h2>{children}<div className="operation-actions">
      <button ref={safe} onClick={cancel}>取消</button><button className="destructive" onClick={confirm}>{confirmText}</button>
    </div>
  </dialog>
}
