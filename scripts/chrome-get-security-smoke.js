/* Opt-in M2B-2B-R1 security acceptance only. Uses unchanged unpacked Extension candidate.
 * Headless real Chrome + isolated native dev pairing authority; no Windows GUI/MV3/DOM claim.
 * CDP captures metadata projections only, never persists raw protocol, bodies or credentials.
 * Usage: node scripts/chrome-get-security-smoke.js <java.exe> <chrome.exe> [extension-repo]
 */
const fs = require('fs');
const path = require('path');
const net = require('net');
const http = require('http');
const assert = require('assert');
const { spawn, execFileSync } = require('child_process');
const ROOT = path.resolve(__dirname, '..');
const RUN = path.join(ROOT, '.verification', 'm2b2b-r1-chrome');
const EXT = path.resolve(process.argv[4] || path.join(ROOT, '..', 'local-ai-assistant'));
const BASE = 'http://127.0.0.1:8765', DEBUG_PORT = 19223, SITE_PORT = 18801;
const EXPECTED = 'ddfa4a02cace8480bdef78862a907e48a65c6b7c';
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await fn()) return; await wait(100); }
  throw new Error('Acceptance deadline exceeded');
}
class CDP {
  constructor(socket) {
    this.socket = socket; this.id = 0; this.pending = new Map(); this.listeners = new Map();
    socket.addEventListener('message', event => {
      const packet = JSON.parse(event.data);
      if (packet.id) {
        const entry = this.pending.get(packet.id); if (!entry) return;
        this.pending.delete(packet.id); clearTimeout(entry.timer);
        if (packet.error) entry.reject(new Error('Chrome command rejected'));
        else entry.resolve(packet.result);
      } else for (const fn of this.listeners.get(packet.method) || []) fn(packet.params);
    });
  }
  static async connect(url) {
    const socket = new WebSocket(url);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', () => reject(new Error('Chrome connection failed')), { once: true });
    });
    return new CDP(socket);
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('Chrome command deadline')); }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }
  on(method, fn) { this.listeners.set(method, [...(this.listeners.get(method) || []), fn]); }
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) throw new Error('Chrome evaluation failed (private details suppressed)');
    return result.result.value;
  }
  close() { this.socket.close(); }
}
const evidence = { result: 'PARTIAL', scope: 'M2B-2B-R1 security compatibility only',
  pairingAuthority: 'Isolated native dev authority; Windows Assistant GUI not exercised',
  checks: {}, requests: [], limitations: ['Headless Chrome with worker debugger attached; no MV3 lifetime claim',
    'MDN/Dynamic/Selection/cache/offline/revoke UX/MV3 acceptance remains Extension M2B-2B scope'] };
let runtimeProcess, chromeProcess, browser, popup, worker, page, site, nativeToken, extensionId;
const issued = [], secrets = [], bodies = [], connections = [], records = new Map();
const bodyReads = []; let captureFailure = false;
function passed(name) { evidence.checks[name] = 'PASS'; console.log('PASS ' + name); }
function git(...args) { return execFileSync('git', ['-C', EXT, ...args], { encoding: 'utf8' }).trim(); }
async function unused(port) {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', () => reject(new Error('Acceptance port occupied'))); server.listen(port, '127.0.0.1', resolve); });
  await new Promise(resolve => server.close(resolve));
}
async function endpoint(route) { return (await fetch('http://127.0.0.1:' + DEBUG_PORT + route, { signal: AbortSignal.timeout(2000) })).json(); }
async function connectTarget(id) {
  let target;
  await until(async () => { target = (await endpoint('/json/list')).find(t => t.id === id); return !!target?.webSocketDebuggerUrl; });
  const connection = await CDP.connect(target.webSocketDebuggerUrl); connections.push(connection); return connection;
}
async function request(method, route, body, credential, origin, metadata = false) {
  const headers = { 'Content-Type': 'application/json' };
  if (credential) headers.Authorization = 'Bearer ' + credential;
  if (origin) headers.Origin = origin;
  // Synthetic HTTP only. The actual Chrome worker supplies its natural security headers.
  if (metadata) Object.assign(headers, { 'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty' });
  const response = await fetch(BASE + route, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    redirect: 'error', signal: AbortSignal.timeout(8000) });
  return { status: response.status, cors: response.headers.get('Access-Control-Allow-Origin'),
    data: response.status === 204 ? null : await response.json() };
}
async function native(method, route, body) {
  const r = await request(method, route, body, nativeToken);
  assert(r.status >= 200 && r.status < 300); return r.data;
}
async function pair(origin) {
  const proof = await native('POST', '/api/v1/security/pairings', { origin, displayName: 'R1 Security Acceptance', userApproved: true });
  secrets.push(proof.pairingSecret); return { pairingId: proof.pairingId, pairingSecret: proof.pairingSecret };
}
async function synthetic() {
  const origin = 'chrome-extension://' + 'a'.repeat(32), other = 'chrome-extension://' + 'b'.repeat(32);
  const a = (await request('POST', '/api/v1/security/pairings/exchange', await pair(origin), null, origin, true)).data;
  const b = (await request('POST', '/api/v1/security/pairings/exchange', await pair(other), null, other, true)).data;
  for (const client of [a,b]) { assert(client.credential?.startsWith('br1.')); issued.push(client.client.clientId); secrets.push(client.credential); }
  const ready = await request('GET', '/api/v1/capabilities/translate/readiness', undefined, a.credential, null, true);
  assert.strictEqual(ready.status, 200); assert.strictEqual(ready.cors, null); assert(ready.data.available);
  const input = { items: [{ id: 1, text: 'Hello world.' }, { id: 2, text: 'The library opens every morning.' }], sourceLanguage: 'en', targetLanguage: 'zh-CN' };
  bodies.push(...input.items.map(i => i.text));
  const task = await request('POST', '/api/v1/translate/tasks', input, a.credential, origin, true); assert.strictEqual(task.status, 202);
  const route = '/api/v1/tasks/' + task.data.taskId;
  assert.strictEqual((await request('GET', route, undefined, b.credential, null, true)).status, 404);
  assert.strictEqual((await request('GET', route, undefined, nativeToken)).status, 404);
  const nativeTask = await native('POST', '/api/v1/translate/tasks', input);
  const denied = await request('GET', '/api/v1/tasks/' + nativeTask.taskId, undefined, a.credential, null, true);
  assert.strictEqual(denied.status, 404); assert.strictEqual(denied.data.code, 'TASK_NOT_FOUND');
  assert.strictEqual((await request('POST', '/api/v1/translate/tasks', input, a.credential, null, true)).status, 401);
  assert.strictEqual((await request('DELETE', route, undefined, a.credential, null, true)).status, 401);
  let result;
  await until(async () => {
    result = await request('GET', route, undefined, a.credential, null, true);
    assert.strictEqual(result.status, 200); assert.strictEqual(result.cors, null);
    return !['QUEUED','RUNNING'].includes(result.data.status);
  }, 160000);
  assert.strictEqual(result.data.status, 'SUCCEEDED'); assert(Array.isArray(result.data.result.items));
  bodies.push(...result.data.result.items.map(i => i.translation));
  // Complete the separate native fixture before Chrome acceptance to avoid competing inference.
  await until(async () => !['QUEUED','RUNNING'].includes((await native('GET', '/api/v1/tasks/' + nativeTask.taskId)).status), 160000);
  passed('synthetic originless readiness / owned GET / cross-owner / mutations / no synthesized CORS');
}
function record(id) { if (!records.has(id)) records.set(id, { requestId: id }); return records.get(id); }
function getHeader(headers, name) { return Object.entries(headers).find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1]; }
async function chromeAcceptance() {
  chromeProcess = spawn(process.argv[3], ['--headless=new', '--enable-unsafe-extension-debugging', '--no-first-run',
    '--no-default-browser-check', '--user-data-dir=' + path.join(RUN, 'chrome-profile'), '--remote-debugging-port=' + DEBUG_PORT, 'about:blank'],
    { windowsHide: true, stdio: 'ignore' });
  let version;
  await until(async () => { try { version = await endpoint('/json/version'); return !!version.webSocketDebuggerUrl; } catch (_) { return false; } });
  evidence.browserVersion = version.Browser; assert(/^Chrome\/154\./.test(version.Browser));
  browser = await CDP.connect(version.webSocketDebuggerUrl); connections.push(browser);
  extensionId = (await browser.send('Extensions.loadUnpacked', { path: path.join(EXT, 'browser-extension') })).id;
  site = http.createServer((req,res) => { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end('<!doctype html><title>R1 security fixture</title><p>Security acceptance fixture</p>'); });
  await new Promise(resolve => site.listen(SITE_PORT, '127.0.0.1', resolve));
  const url = 'http://127.0.0.1:' + SITE_PORT;
  const pageId = (await browser.send('Target.createTarget', { url })).targetId;
  page = await connectTarget(pageId); await until(() => page.evaluate("document.readyState === 'complete'"));
  const tabs = await browser.send('Target.getTargets', { filter: [{ type: 'tab', exclude: false }, { exclude: true }] });
  const tab = tabs.targetInfos.find(t => t.url === url + '/'); assert(tab);
  await browser.send('Extensions.triggerAction', { id: extensionId, targetId: tab.targetId });
  let popupInfo, workerInfo;
  await until(async () => {
    const targets = await endpoint('/json/list');
    popupInfo = targets.find(t => t.url === 'chrome-extension://' + extensionId + '/popup.html');
    workerInfo = targets.find(t => t.type === 'service_worker' && t.url === 'chrome-extension://' + extensionId + '/background.js');
    return !!popupInfo && !!workerInfo;
  });
  popup = await connectTarget(popupInfo.id); worker = await connectTarget(workerInfo.id);
  worker.on('Network.requestWillBeSent', p => {
    if (p.request.url.startsWith(BASE + '/api/v1/')) Object.assign(record(p.requestId), { method: p.request.method, path: new URL(p.request.url).pathname });
  });
  worker.on('Network.requestWillBeSentExtraInfo', p => {
    const h = p.headers; Object.assign(record(p.requestId), { origin: getHeader(h,'Origin') || 'ABSENT',
      site: getHeader(h,'Sec-Fetch-Site') || 'ABSENT', mode: getHeader(h,'Sec-Fetch-Mode') || 'ABSENT', dest: getHeader(h,'Sec-Fetch-Dest') || 'ABSENT',
      browserCredentialPresent: /^Bearer br1\./.test(getHeader(h,'Authorization') || '') });
  });
  worker.on('Network.responseReceived', p => Object.assign(record(p.requestId), { status: p.response.status,
    allowOrigin: getHeader(p.response.headers, 'Access-Control-Allow-Origin') || 'ABSENT' }));
  worker.on('Network.loadingFinished', p => {
    const r = record(p.requestId);
    if (r.method === 'GET' && r.path?.startsWith('/api/v1/tasks/') && r.status === 200) {
      bodyReads.push(worker.send('Network.getResponseBody', { requestId: p.requestId }).then(response => {
        const data = JSON.parse(response.base64Encoded ? Buffer.from(response.body, 'base64').toString('utf8') : response.body);
        r.taskStatus = data.status; r.structuredResult = Array.isArray(data.result?.items);
        r.resultItemCount = data.result?.items?.length || 0;
        if (data.result?.items) bodies.push(...data.result.items.map(i => i.translation));
      }).catch(() => { captureFailure = true; }));
    }
  });
  await worker.send('Network.enable');
  const origin = 'chrome-extension://' + extensionId;
  await until(() => popup.evaluate("document.getElementById('extensionOrigin')?.value === 'chrome-extension://' + chrome.runtime.id"));
  const proof = await pair(origin);
  await popup.evaluate("(() => {document.getElementById('pairingId').value=" + JSON.stringify(proof.pairingId) +
    ";document.getElementById('pairingSecret').value=" + JSON.stringify(proof.pairingSecret) + ";document.getElementById('pairingForm').requestSubmit();return true;})()");
  await until(() => popup.evaluate("document.getElementById('pairingStatus').textContent === 'Paired' && !document.getElementById('btnTranslate').disabled"));
  assert(await popup.evaluate("!document.getElementById('pairingId').value && !document.getElementById('pairingSecret').value"));
  const own = (await native('GET','/api/v1/security/clients')).find(c => c.origin === origin); assert(own); issued.push(own.clientId);
  // Read the actual stored secret in the trusted dev debugger only for auditing; never send it to the page or evidence.
  secrets.push(await popup.evaluate("chrome.storage.local.get('runtimePairing').then(s=>s.runtimePairing.credential)"));
  assert(await popup.evaluate("chrome.storage.local.get('runtimePairing').then(s=>s.runtimePairing?.origin==='chrome-extension://'+chrome.runtime.id && s.runtimePairing?.clientId==='" + own.clientId + "')"));
  assert(await popup.evaluate("chrome.tabs.query({active:true,currentWindow:true}).then(t=>chrome.scripting.executeScript({target:{tabId:t[0].id},func:async()=>{try{return !(await chrome.storage.local.get('runtimePairing')).runtimePairing?.credential;}catch(_){return true;}}})).then(r=>r[0].result===true)"));
  passed('real exchange / trusted storage / content storage denial / proof clearing / readiness readable');
  const result = await popup.evaluate("chrome.runtime.sendMessage({type:'TRANSLATE_BATCH',items:[{id:1,text:'Hello world.'},{id:2,text:'The library opens every morning.'}]})");
  assert(result.ok); assert.strictEqual(result.results?.length, 2);
  assert(result.results.every(i => /[\u3400-\u9fff]/.test(i.translation)));
  assert.strictEqual(result.identity.promptVersion, 'translate-batch-v1');
  bodies.push(...result.results.map(i => i.translation));
  await Promise.all(bodyReads); assert(!captureFailure);
  const network = [...records.values()].filter(r => r.path?.startsWith('/api/v1/'));
  const exchange = network.find(r => r.path === '/api/v1/security/pairings/exchange' && r.method === 'POST');
  const readiness = network.filter(r => r.path === '/api/v1/capabilities/translate/readiness' && r.method === 'GET');
  const posts = network.filter(r => r.path === '/api/v1/translate/tasks' && r.method === 'POST');
  const polls = network.filter(r => r.path.startsWith('/api/v1/tasks/') && r.method === 'GET');
  assert(exchange && exchange.status === 200 && exchange.origin === origin && !exchange.browserCredentialPresent);
  assert(readiness.length > 0 && polls.length > 0); assert.strictEqual(posts.length, 1);
  for (const r of [exchange, ...readiness, ...posts, ...polls]) {
    assert.strictEqual(r.site, 'none'); assert.strictEqual(r.mode, 'cors'); assert.strictEqual(r.dest, 'empty');
  }
  for (const r of [...readiness, ...polls]) {
    assert.strictEqual(r.origin, 'ABSENT'); assert(r.browserCredentialPresent); assert.strictEqual(r.status, 200); assert.strictEqual(r.allowOrigin, 'ABSENT');
  }
  assert.strictEqual(posts[0].status, 202); assert.strictEqual(posts[0].origin, origin); assert(posts[0].browserCredentialPresent);
  assert(polls.some(r => r.taskStatus === 'SUCCEEDED' && r.structuredResult && r.resultItemCount === 2));
  // Verify a synthetic second browser client and native authority still cannot read the real Chrome task.
  const otherSecret = secrets.find(s => s.startsWith('br1.') && s !== secrets.at(-1));
  for (const credential of [otherSecret,nativeToken]) {
    const denied = await request('GET', polls[0].path, undefined, credential, null, credential !== nativeToken);
    assert.strictEqual(denied.status, 404); assert.strictEqual(denied.data.code,'TASK_NOT_FOUND');
  }
  evidence.requests = network.map(({ requestId, ...safe }) => safe);
  evidence.taskId = polls[0].path.split('/').at(-1); evidence.itemCount = result.results.length;
  evidence.profile = result.identity.profile; evidence.promptVersion = result.identity.promptVersion;
  passed('natural Chrome GET headers / exact-Origin POST 202 / owned polling 200 / structured result readable / cross-client isolation');
}
async function audit() {
  for (const log of ['runtime.log']) {
    const content = fs.readFileSync(path.join(RUN,log),'utf8');
    assert(![...secrets,...bodies].filter(Boolean).some(value => content.includes(value)));
  }
  const scan = spawn('python', [path.join(ROOT,'scripts','privacy-audit.py')], { cwd: ROOT, windowsHide: true, stdio: ['pipe','pipe','pipe'] });
  let out = ''; scan.stdout.on('data', c => out += c); scan.stderr.on('data', () => {});
  scan.stdin.end(JSON.stringify({ secrets, logBodies: bodies }));
  await new Promise((resolve,reject) => { scan.once('error',reject); scan.once('exit',code => code === 0 ? resolve() : reject(new Error('Privacy audit failed'))); });
  fs.writeFileSync(path.join(RUN,'privacy-audit-evidence.json'),out);
  passed('actual secret / proof / content / result / source / build / archive / evidence audit');
}
async function cleanup() {
  let revokeFailed = false;
  if (nativeToken && runtimeProcess?.exitCode === null) for (const id of issued) {
    try { await native('DELETE','/api/v1/security/clients/'+id); } catch (_) { revokeFailed = true; }
  }
  if (browser) await browser.send('Browser.close').catch(() => {});
  for (const connection of connections) connection.close();
  if (chromeProcess) {
    await until(() => chromeProcess.exitCode !== null || chromeProcess.signalCode !== null,8000).catch(() => chromeProcess.kill());
    await until(() => chromeProcess.exitCode !== null || chromeProcess.signalCode !== null,8000);
    // This fresh, owned profile intentionally held the Browser credential. Dispose it before artifact scans.
    const profile = path.resolve(RUN,'chrome-profile');
    assert(profile.startsWith(path.resolve(ROOT,'.verification') + path.sep));
    assert.strictEqual(profile,path.join(RUN,'chrome-profile'));
    fs.rmSync(profile,{recursive:true,force:true,maxRetries:10,retryDelay:200});
  }
  if (site) { site.closeAllConnections(); await new Promise(resolve => site.close(resolve)); }
  if (runtimeProcess) { runtimeProcess.kill(); await until(() => runtimeProcess.exitCode !== null || runtimeProcess.signalCode !== null,8000); }
  assert(!revokeFailed, 'Acceptance clients could not be revoked');
}
(async () => {
  fs.mkdirSync(RUN,{recursive:true});
  evidence.extensionCommit = git('rev-parse','HEAD'); assert.strictEqual(evidence.extensionCommit,EXPECTED);
  assert.strictEqual(git('branch','--show-current'),'m2b2b-runtime-migration'); assert.strictEqual(git('status','--porcelain'),'');
  for (const port of [8765,DEBUG_PORT,SITE_PORT]) await unused(port);
  assert(!fs.existsSync(path.join(RUN,'chrome-profile')), 'Use a fresh ignored acceptance directory');
  try {
    const log = fs.openSync(path.join(RUN,'runtime.log'),'w');
    runtimeProcess = spawn(process.argv[2], ['-jar',path.join(ROOT,'target','personal-ai-workspace-0.1.0.jar'),
      '--workspace.security.token-file='+path.join(RUN,'private','client-token')], { cwd: ROOT, windowsHide:true, stdio:['ignore',log,log] });
    fs.closeSync(log);
    await until(async () => { try { return (await fetch(BASE+'/actuator/health/readiness')).ok; } catch (_) { return false; } });
    nativeToken = fs.readFileSync(path.join(RUN,'private','client-token'),'utf8').trim(); secrets.push(nativeToken);
    await synthetic(); await chromeAcceptance();
  } finally { await cleanup(); }
  assert.strictEqual(git('rev-parse','HEAD'),EXPECTED); assert.strictEqual(git('status','--porcelain'),'');
  evidence.extensionUnchanged = true;
  evidence.result = 'PASS — scoped real Chrome security compatibility'; evidence.timestampUtc = new Date().toISOString();
  fs.writeFileSync(path.join(RUN,'chrome-get-security-evidence.json'),JSON.stringify(evidence,null,2));
  await audit();
  fs.writeFileSync(path.join(RUN,'chrome-get-security-evidence.json'),JSON.stringify(evidence,null,2));
  console.log(JSON.stringify(evidence,null,2));
})().catch(() => {
  evidence.result = 'PARTIAL — acceptance did not complete; private failure details suppressed';
  fs.writeFileSync(path.join(RUN,'chrome-get-security-evidence.json'),JSON.stringify(evidence,null,2));
  console.error(evidence.result); process.exitCode = 1;
});
