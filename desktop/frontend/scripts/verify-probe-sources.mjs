import { createHash } from 'node:crypto'
import { createReadStream, lstatSync, readFileSync, statSync } from 'node:fs'
import { dirname, isAbsolute, normalize, sep } from 'node:path'

// Build-time freshness only. Native admission still validates/pins source files,
// identity, links, permissions and release version in the actual runtime context.
async function verify(file) {
  if (!file) throw new Error('TrustedDevelopmentManifest is required for the Profile runtime probe')
  if (statSync(file).size > 131072) throw new Error('Probe source manifest exceeds 131072 bytes')
  const bytes = readFileSync(file)
  if (bytes.length > 131072) throw new Error('Probe source manifest exceeds 131072 bytes')
  const manifest = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''))
  if (manifest.Format === 3) {
    console.log('Probe source freshness deferred: Format 3 bundled paths are not checked here; publishing/native admission is still required')
    return
  }
  if (manifest.Format !== 2 || !Array.isArray(manifest.Files) || manifest.Files.length < 5 || manifest.Files.length > 2048)
    throw new Error('Expected a bounded Format 2 local probe source manifest')
  const { JavaHome, RuntimeJar, JnaJar } = manifest
  if (![JavaHome, RuntimeJar, JnaJar].every(value => typeof value === 'string' && isAbsolute(value) && !value.startsWith('\\\\')))
    throw new Error('Local probe source roots must be absolute local paths')
  const seen = new Set()
  for (const source of manifest.Files) {
    if (!source || typeof source.Path !== 'string' || !isAbsolute(source.Path) || source.Path.startsWith('\\\\') || normalize(source.Path) !== source.Path ||
        typeof source.Sha256 !== 'string' || !/^[a-fA-F0-9]{64}$/.test(source.Sha256) ||
        source.Path !== RuntimeJar && source.Path !== JnaJar && !source.Path.toLowerCase().startsWith((JavaHome + sep).toLowerCase()))
      throw new Error('Unexpected local probe source path or hash')
    const key = source.Path.toLowerCase()
    if (seen.has(key)) throw new Error(`Duplicate probe source: ${source.Path}`)
    seen.add(key)
    for (let path = source.Path; path; path = dirname(path) === path ? '' : dirname(path))
      if (lstatSync(path).isSymbolicLink()) throw new Error(`Linked probe source or ancestor: ${path}`)
    let actual
    try {
      const hash = createHash('sha256')
      for await (const chunk of createReadStream(source.Path)) hash.update(chunk)
      actual = hash.digest('hex')
    } catch (error) {
      throw new Error(`Cannot read pinned probe source (${error.code ?? 'READ_FAILED'}): ${source.Path}`)
    }
    if (actual !== source.Sha256.toLowerCase())
      throw new Error(`Pinned probe source changed: ${source.Path}; expected ${source.Sha256.toLowerCase()}; actual ${actual}. Use a reviewed current manifest; do not rewrite pins automatically.`)
  }
  console.log(`Probe source freshness: ${seen.size} exact hashes match; native runtime admission remains required`)
}

try { await verify(process.argv[2]) }
catch (error) { console.error(`Probe source preflight failed: ${error.message}`); process.exitCode = 1 }
