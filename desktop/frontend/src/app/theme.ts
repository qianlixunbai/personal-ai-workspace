export type Theme = 'light' | 'dark'
const key = 'workspace.theme'
export function readTheme(): Theme {
  try { const stored = localStorage.getItem(key); if (stored === 'light' || stored === 'dark') return stored } catch { /* storage may be unavailable */ }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}
export function saveTheme(theme: Theme) { try { localStorage.setItem(key, theme) } catch { /* theme still works in memory */ } }
