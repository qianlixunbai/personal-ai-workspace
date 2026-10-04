import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import { resolve, relative } from 'node:path'
const folder = resolve('dist')
const manifestPath = resolve(folder, 'workspace-assets.json')
const hash = (file) => createHash('sha256').update(readFileSync(file)).digest('hex')
const list = (dir) => readdirSync(dir).flatMap(name => {
  const path = resolve(dir, name)
  return statSync(path).isDirectory() ? list(path) : [path]
})
const files = Object.fromEntries(list(folder).filter(p => p !== manifestPath).sort().map(p => [relative(folder, p).replaceAll('\\', '/'), hash(p)]))
if (!files['index.html'] || Object.keys(files).some(p => p !== 'index.html' && !/^assets\/[\w.-]+\.(js|css|svg|png|woff2)$/.test(p)))
  throw new Error('Unexpected or missing Main Workspace build assets')
if (!readFileSync(resolve(folder, 'index.html'), 'utf8').includes("connect-src 'none'; frame-src 'none'"))
  throw new Error('Production CSP is missing')
const manifest = { formatVersion: 1, bridgeVersion: 1, files }
if (process.argv.includes('--verify')) {
  if (JSON.stringify(JSON.parse(readFileSync(manifestPath, 'utf8'))) !== JSON.stringify(manifest))
    throw new Error('Main Workspace assets missing, modified or incompatible; rebuild frontend')
} else writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n')
