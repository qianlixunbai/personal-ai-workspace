import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig(({ command }) => ({
  plugins: [react(), {
    name: 'debug-only-csp',
    transformIndexHtml(html) {
      // Only Vite's explicit loopback server needs HMR connections and injected styles.
      return command === 'serve' ? html.replace("connect-src 'none'", "connect-src 'self' ws://127.0.0.1:5173")
        .replace("script-src 'self'", "script-src 'self' 'unsafe-inline'")
        .replace("style-src 'self'", "style-src 'self' 'unsafe-inline'") : html
    },
  }],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false },
  build: { sourcemap: false, assetsInlineLimit: 0 },
  test: { environment: 'jsdom', restoreMocks: true, clearMocks: true },
}))
