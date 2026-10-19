import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { WorkspaceErrorBoundary } from './app/WorkspaceErrorBoundary'
import { WorkspaceClient } from './bridge/client'
import './styles/workspace.css'
const bridge = new WorkspaceClient(window.chrome?.webview)
window.addEventListener('pagehide', () => bridge.dispose(), { once: true })
// Caught exception text may contain private content; show only the fixed local fallback.
createRoot(document.getElementById('root')!, { onCaughtError: () => {} }).render(
  <WorkspaceErrorBoundary><App bridge={bridge} /></WorkspaceErrorBoundary>,
)
