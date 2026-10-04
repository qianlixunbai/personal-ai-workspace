import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { WorkspaceClient } from './bridge/client'
import './styles/workspace.css'
const bridge = new WorkspaceClient(window.chrome?.webview)
window.addEventListener('pagehide', () => bridge.dispose(), { once: true })
createRoot(document.getElementById('root')!).render(<App bridge={bridge} />)
