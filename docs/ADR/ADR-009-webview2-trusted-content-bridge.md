# ADR-009 — WebView2 Trusted Content and Least-Privilege Bridge

Status: Accepted

Date: 2026-10-04 (Asia/Shanghai)

## Trusted content and browser policy

Production uses `https://workspace.personal-ai.invalid/index.html` through WebView2's virtual
host mapping with `CoreWebView2HostResourceAccessKind.Deny`. It maps only verified bundled
`MainWorkspace` build assets. There is no DNS dependency, `file://` navigation, project/data/auth/
logs/backup mapping, arbitrary frontend URL or Release development-server fallback. Asset
manifest format1 pins bridge version1 and SHA-256 for every admitted HTML/JS/CSS asset.
Filesystem link components and modified/missing/incompatible assets fail to a native fallback.
Hashes detect packaging damage; they do not isolate a hostile same-account code modifier.

Production CSP:

```text
default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none';
base-uri 'none'; form-action 'none'; worker-src 'none'
```

Navigation admits only the fixed origin, `/` or `/index.html`, no query, and the five known
hash routes. Frame navigation, popups and downloads are denied. All permission requests are
denied without profile persistence, including camera, microphone, location and notifications.
Resource admission is GET-only, same-origin and manifest-listed script/style/image/font; Fetch,
XHR, service-worker resources and unrelated files are denied. Host objects, browser accelerator
UI, context menus, built-in error pages, default script dialogs, autofill and password saving
are disabled; Release DevTools are disabled. Browser extensions and default crash reporting
uploads are disabled. No external content is opened in the WebView. M5A has no external-link
operation; a future such operation requires an explicit native allowlist decision.

## Versioned bridge v1

Host sends `{type:"shell.session",version:1,sessionId:<canonical UUID>}` after a successfully
loaded current document. Request fields are exactly `version,sessionId,requestId,method,payload`.
Both identifiers use canonical non-nil lowercase UUIDs. Every M5A payload is exactly `{}`.
Supported methods:

```text
shell.bootstrap
shell.refreshStatus
native.openLegacyAssistant
native.openConversations
native.openMemory
native.openBrowserPairing
native.openMemoryBackup
native.openWorkspaceBackup
native.openCredentialFlow
```

Explicit enum/switch dispatch maps only actual native flows. No reflection dispatch, generic
window/type/method invocation, host object, URL/path/process access or HTTP proxy exists.
Responses have `version,sessionId,requestId,ok` plus exactly `result` or controlled `error`.
The status DTO includes contract/app version, reachability, credential enum, WebView availability
and native-entry names. Native success acknowledges `{opened:true}` after the native entry
returns; modal windows can remain open before that reply. Frontend timeout is15s for status,
5minutes for native flow, with no automatic replay. Credential entry focuses the existing
native import UI; it does not receive a token or filename from JavaScript.

Admission checks message source, current top-level document, active ready session, version,
request ID, method, exact fields including duplicate keys, empty payload, 32KiB UTF-8 request
budget, field bounds, eight pending requests and4096 IDs per document. Invalid requests are
dropped without a reflection/error oracle or raw diagnostic. Responses are bounded to32KiB;
frontend validates exact response/status schemas and correlations. Reload/navigation invalidates
the old session, cancels pending native status work, rotates the session, and suppresses old
results. IDs remain consumed for the document lifetime to prohibit replay; exhaustion requires
an explicit reload. Hash navigation stays within the admitted current document.

## Credential and Runtime isolation

React talks only to the typed WebMessage bridge. WPF calls the existing application-owned
RuntimeClient. Native bearer, Authorization, Browser credential, pairing proof and WinCred
content never cross the bridge. Runtime CORS/permissions and Java security code are unchanged.
Health means reachability; authenticated `translate.fast` readiness establishes credential
validity only, not all-model readiness. Backup files, bytes, paths and previews remain native.

## UDF/privacy boundary

WebView2 has a dedicated `MainWorkspace` InPrivate profile under fixed account-local
`%LOCALAPPDATA%/PersonalAiWorkspace/MainWorkspaceWebView2`. The root is owner-only ACL protected,
validated against other allowed principals and reparse components, outside repository,
Workspace DB, auth and backups. Normal browser profiles are never shared. Required SDK APIs
are real: controller options `ProfileName`/`IsInPrivateModeEnabled`, profile autofill/password
settings and `ClearBrowsingDataAsync(AllProfile)` at initialization and controlled close.
Unsupported privacy capabilities fail initialization instead of weakening the policy.

Initialization rejects ambient WebView environment overrides that could redirect the runtime,
profile or debugging configuration. Cleanup has a3s bound, then disposes the controller; a
browser failure can prevent confirmed cleanup. InPrivate, no remote browsing/service worker/
domain browser storage, and synthetic UDF scans are the practical privacy policy. Local browser
metadata/cache may remain. No forensic erasure, same-account compromise isolation or arbitrary
OS crash cleanup guarantee is claimed. No page text, bridge payload, credential or personal
domain data is logged, and no console/CDP capture is wired into production code.

The separate acceptance executable uses SDK scripting/CDP to exercise hostile requests,
temporarily bypass CSP to verify native interception, and deliberately crash a test renderer.
It adds no callable production bridge capability; retained evidence is safe checks/metrics only.

## Failure behavior

Missing Runtime/SDK features, invalid assets, initialization/page/renderer failures show a
native WPF fallback with an Assistant action. Closing/reopening creates a fresh controller and
bridge. The existing tray, hotkey and native windows remain available. Security or privacy gates
that lack real evidence prevent M5A Closing Candidate status.

## API references

- [Microsoft local-content/virtual-host mapping](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/working-with-local-content)
- [Controller options and InPrivate](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2controlleroptions.isinprivatemodeenabled)
- [WPF initialization with controller options](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.wpf.webview2.ensurecorewebview2async)
- [Profile browsing-data cleanup](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2profile.clearbrowsingdataasync)
