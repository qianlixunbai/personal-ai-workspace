# M5A CLOSING REPORT

Date: 2026-10-04 (Asia/Shanghai)

## 1. Result

**M5A — IMPLEMENTED / PARTIAL — AWAITING REAL WINDOWS IME ACCEPTANCE.**
**M5 — Unified Main Workspace UI: OPEN.**

The safe packaged shell, bridge/security/privacy boundaries and existing regressions pass.
Real Windows Pinyin composition was attempted but not established by automation. Synthetic
composition events are not counted as actual IME acceptance. M5A is therefore not claimed as
Closing Candidate or CLOSED — GO. Implementation stops for Architecture / Closing Review;
M5B–M5E have not started.

## 2. Delivery / Reality Gate

PASS. Started on `main`, clean working tree, with HEAD/main/origin/main all equal to
`d1d7be9e904387862b663a8bab7c72bb89d392c5`. GitHub was refreshed through command-local
HTTP/HTTPS proxy `http://127.0.0.1:7890`; global Git configuration was not changed.
End-of-work fetch and remote branch listing confirmed the same published SHA and `main` only.
README, STATUS, current architecture, ADR-001..007, actual Desktop/Core, tests, helpers and
acceptance flows were inspected. No material ownership conflict was found.

## 3. Branch Hygiene

Local `git branch` and `--merged main` showed `main` only before implementation. No historical
branch existed to delete. No unknown/unmerged branch was merged, rebased or cherry-picked.
The requested feature branch was created from the exact baseline.

## 4. Git

Branch: `m5a-main-workspace-shell`. Implementation commit:
`233244475078a33f85c89edf801b2f3effa0d524` — `feat: add main workspace shell foundation`.
Accepted ADRs/current docs/this report are a separate local documentation commit.
No merge, push, tag, release or post-implementation branch deletion was performed.

## 5. Baseline

Published M0/M1/M1.5/M2/M3/M4A/M4B/M4C/M4 remain CLOSED — GO; ADR-001..007 remain Accepted.
Historical final M4 suites: Java105/Desktop136. This run: Java105/Desktop168/Frontend14,
all final suite results zero failures/errors/skips. No Java production/test or Core file changed.

## 6. Files Changed

Implementation comprises39 files: frontend package/lockfile/config/build manifest script,
React shell/bridge/status/routes/theme/CSS/fixtures/tests; WPF MainWorkspaceWindow;
Desktop Hosting and Bridge types; AssistantApp/AssistantWindow/native entry integration;
Desktop WebView2 package/build target/friend assembly;32 new bridge/status/security tests;
Release-equivalent MainWorkspace acceptance; build and Windows smoke scripts; privacy audit;
and safe failure diagnostics in the existing Workspace recovery harness/script.
Documentation adds ADR-008/009/010 and this report, and updates ADR index, README, STATUS
and current architecture. Generated assets, dependencies, binaries, DBs and evidence stay ignored.

## 7. ADR-008

[Accepted](../ADR/ADR-008-hybrid-main-workspace-ownership.md): Runtime/WPF/React ownership,
single product, incremental management migration, enduring quick native surface, and explicit
security/privacy/functionality/Windows/regression/review retirement gates. No domain page or
legacy retirement is claimed implemented.

## 8. ADR-009

[Accepted](../ADR/ADR-009-webview2-trusted-content-bridge.md): fixed bundled trusted origin,
least-privilege v1 bridge, credential isolation, navigation/resource/frame/popup/download/
permission policies, dedicated InPrivate profile, controlled cleanup, no generic native proxy
and no frontend direct Runtime access. Normal privacy behavior has real UDF evidence.

## 9. ADR-010

[Accepted](../ADR/ADR-010-frontend-build-desktop-distribution.md): one npm/lockfile frontend,
production assets in build/publish, Debug-only fixed-loopback development, user distribution
without Node/Vite, and WebView2 readiness/native fallback. Java/.NET provisioning/installer/
update/signing decisions remain deferred.

## 10. Frontend Stack

React/React DOM19.3.0, strict TypeScript7.0.2, Vite8.3.2, React plugin6.1.1.
Dev-only Vitest5.0.3, jsdom30.1.2 and Testing Library16.3.3. Live official npm metadata verified
compatible stable versions; local Node24.16.0/npm11.13.0. Only React/React DOM are production
dependencies; production npm audit found0 vulnerabilities. No framework expansion/CDN/analytics.

## 11. Repository Layout

```text
desktop/frontend/{src/app,src/bridge,src/components,src/pages,src/styles,src/test,scripts}
desktop/src/PersonalAiWorkspace.Desktop/{Hosting,Bridge,MainWorkspaceWindow.xaml[.cs]}
desktop/tests/PersonalAiWorkspace.Desktop.Tests/WorkspaceBridgeTests.cs
desktop/acceptance/PersonalAiWorkspace.MainWorkspaceAcceptance/
scripts/main-workspace-{smoke,build-check}.py
docs/ADR/ADR-008..010
```

## 12. WPF Main Workspace Host

Dedicated WPF window with native Assistant/focus/zoom controls, WebView2, loading/failure UI
and bounded controlled close. Content/profile/assets policy and bridge are focused separate
types. Normal application exit awaits browsing-data cleanup; emergency cleanup immediately
disposes the controller. Existing .NET10/WPF/WinForms lifetime remains application-owned.
SDK: official Microsoft.Web.WebView2 **1.0.4258.31**, pinned only in Desktop.

## 13. Production Trusted Origin

`https://workspace.personal-ai.invalid/index.html`, virtual mapping with resource access
`Deny`, only verified `MainWorkspace` frontend output. No DNS/server/Node/file-origin dependency.
Manifest paths and SHA-256 are validated; mapping excludes repository, auth, Workspace DB,
logs and backups. Resource access additionally rejects unlisted files.

## 14. CSP

Production has self-only scripts/styles/fonts, self/data images, no connections, frames,
objects, base URI, form submissions or workers. No unsafe-eval/inline production adjustment
was required. The published HTML contains the same strict CSP as the source and host check.

## 15. Navigation / Permission Policy

Only exact trusted origin and the two entry paths/five known hash routes, without queries,
are admitted. External navigation/frame/popup/download and every permission are blocked.
Password/autofill/host objects/context menus/browser accelerators/default script dialogs/
error pages/production DevTools/extensions are disabled. Explicit toolbar controls provide
75–200% zoom. M5A contains no external-link bridge operation.

## 16. Bridge Contract

Request exactly `version,sessionId,requestId,method,payload`; v1, canonical non-nil UUIDs,
and exactly empty payload. Response includes v1/session/request/ok plus typed result or fixed
controlled error. Exact fields, duplicate keys, field bounds, UTF-8 size, ready document,
source/current top document, session and method are checked before dispatch.
32KiB request/response cap, eight pending requests,4096 consumed IDs per document.

## 17. Bridge Allowlist

Only `shell.bootstrap`, `shell.refreshStatus`, and seven fixed commands:
`native.openLegacyAssistant`, `native.openConversations`, `native.openMemory`,
`native.openBrowserPairing`, `native.openMemoryBackup`, `native.openWorkspaceBackup`,
`native.openCredentialFlow`. There is no domain CRUD/submit/cancel, generic window/path/URL/
process/HTTP proxy, reflection dispatch or exposed AssistantApp/RuntimeClient host object.

## 18. Session / Correlation Semantics

Successful current navigation issues a host session message; reload invalidates/cancels the
old session and rotates its identifier. Duplicate IDs cannot replay native entries. TS
correlates responses, rejects invalid results, invalidates pending requests on session change
and never retries/replays automatically. Late responses were deliberately held through a real
WebView reload and suppressed from the new document. Native modal acknowledgement returns
after the native flow returns; status timeout15s and native timeout5minutes are bounded.

## 19. Credential Isolation

Bootstrap has only versions, reachability, credential enum, WebView capability and fixed
entry availability. No bearer/Authorization/Browser credential/pairing proof/WinCred/path/
personal content is present. Credential management opens the existing Assistant and focuses
its native Import button. Acceptance uses a separate temporary WinCred target and forgets it;
the user's production target is not modified.

## 20. Runtime Interaction

Application-owned RuntimeClient/CredentialStore are reused. Health probes reachability;
authenticated translate.fast readiness establishes credential validity only. UI makes no
all-model readiness claim. External Runtime mode, CORS, Browser permissions, provider policy,
TaskManager and all durable/execution semantics remain unchanged. No supervisor or port-owner
inference/kill was added.

## 21. UDF / Privacy Policy

Fixed account-private LocalAppData `PersonalAiWorkspace/MainWorkspaceWebView2`, dedicated
MainWorkspace InPrivate profile, owner-only ACL/reparse validation, disabled autofill/password
save/extensions/default crash uploads, AllProfile cleanup at start/normal close. Unexpected
ambient WebView runtime/profile/debug overrides fail closed. Failure cannot promise successful
cleanup, and no forensic erasure or hostile same-account/admin isolation is claimed.
Final scan: **282 UDF files**, random synthetic USER/Memory/title markers and actual temporary
bearer searched in UTF-8/UTF-16 after close/crash/reopen; **0 matches**, all files readable.

## 22. React Shell

Assistant, Conversations, Memory, Translate, Settings only. Sidebar/header/cards/status/buttons,
light/dark tokens, responsive layout, sticky sidebar, visible focus, loading and controlled
error text. Business pages explicitly open real native functionality; no fake form/data.
Theme is the only localStorage value (`workspace.theme`, light/dark). InPrivate cleanup limits
its persistence to the open profile session; no IndexedDB/domain cache/service worker.

## 23. Native Entry Integration

One real tray menu entry opens/reuses the main window. Seven bridge entries reach existing
Assistant, Conversation, Memory, Pairing and backup surfaces. Native dialogs/file contents/
backup bytes/paths stay native. Credential entry focuses the actual import action. Dialog
return focuses the workspace; quick native Assistant stays available independently.

## 24. Legacy Fallback

All seven required legacy WPF window classes remain. Initialization/page/renderer failures
show native fallback with Assistant access; no remote content fallback. Safe nonexistent-asset
injection exercised the initialization failure handler. Actual test-renderer crash exercised
ProcessFailed and preserved tray usability. WebView2 was not uninstalled from the machine;
the missing-runtime path shares the tested controlled initialization handler.

## 25. Development Mode

Only explicit Debug `MainWorkspaceDev=true` compiles fixed `http://127.0.0.1:5173` policy.
Vite strict loopback port/HMR uses its separate development CSP. Bridge isolation/allowlist
remain. Debug otherwise bundles production assets. Release rejects the property and has no
development-origin string in the assembly. Debug mode compiled successfully; live HMR was
not separately driven in this run.

## 26. Build Integration

Default Desktop build/publish runs npm ci, strict tsc, Vite build, manifest generation/verification
and content copy. `FrontendSkipBuild=true` verifies existing output rather than bypassing it.
Negative build checks proved missing/incompatible assets and Release development loading fail.
Ordinary users need no Node/npm/Vite. Build checks and final fresh Release publish passed.

## 27. Packaging Assets

Fresh final publish: `.verification/m5a-desktop-final`, with Desktop executable/DLL, WebView2
SDK/native loader assets and `MainWorkspace/index.html`, hashed JS/CSS and versioned manifest.
Release-equivalent acceptance executable independently published and loaded these same
production frontend assets. Manifest format1/bridge1 and all listed hashes verify.
Outputs/evidence/screenshots are ignored and not committed; no installer/release was created.

## 28. Frontend Tests

**14 PASS**: all five routes/nav state, empty fixed requests, native entries, safe available/
unavailable status, response correlations/out-of-order results, invalid schemas, stale session,
pending bounds/timeout/no replay, no browser fallback/generic method, hostile text escaping,
theme-only persistence and missing Desktop instruction. Strict TypeScript build passes.

## 29. Desktop / Bridge Tests

**168 PASS**, preserving historical136 and adding32. Covers exact/wrong origin, current/wrong/
unready document/session, versions, malformed/missing/unknown/duplicate fields, empty payload,
unknown/future/generic methods, path/URL/executable/token payload denial, invalid/duplicate IDs,
size/pending/request bounds, reload/late suppression, native enum coverage, redacted status,
credential states, navigation/resource defaults and Core assembly browser independence.
One intermediate existing UIA test had a COM transient; final whole suite passes unchanged.

## 30. Security Tests

Real production CSP blocks Runtime fetch and iframe. Harness-only SDK CSP bypass/reload then
tests the second native boundary: frame, popup, blob download, notification permission and
external navigation handlers all block. Fake sessions/generic/path/bearer payloads receive no
response. Release DevTools/host objects/autofill/password saving are disabled. Request/response
schema attacks and XSS-like error rendering pass; browser storage has only theme, no IDB/SW.
No bypass or generic scripting endpoint is available through the production bridge.

## 31. Accessibility

Semantic nav/main/headings, skip link, keyboard Enter/Tab routing, visible focus, WPF→WebView
focus and native credential focus/dialog return are verified. Actual display DPI scale1 (100%);
native125% zoom control and no horizontal overflow pass. Chinese UI rendering and injected
composition event/value baseline pass. No animation framework or unnecessary motion exists.
**Actual native Pinyin composition was not established after controlled attempts.** This remains
the explicit closing gate. Mixed-DPI multi-monitor hardware and a screen reader were not tested.
M5A has no production domain text editor; none of the synthetic test textarea is shipped.

## 32. Performance Baseline

Final Release-equivalent measured run, WebView2 Runtime154.0.4258.53:

| Metric | Actual |
| --- | ---: |
| WebView2 initialization | 297.63ms |
| Navigation/session ready | 340.38ms |
| Shell authenticated-status ready from tray open | 625.53ms |
| Harness + actual WPF app working set | 153,407,488 bytes |
| WebView2 browser process working set | 139,259,904 bytes |
| Production JS | 231,254 bytes |
| Production CSS | 5,442 bytes |
| Production HTML | 669 bytes |
| Manifest | 351 bytes |

The first cold initialization observed during development was5340.18ms; subsequent measurements
were warm. These are observations, not an SLA. Working sets exclude other WebView child processes
and include harness overhead; they are not total product memory. No personal datasets preload.

## 33. Real Windows Acceptance

**Automated shell/security/privacy foundation PASS**, full M5A accessibility acceptance partial.
Real production AssistantApp/tray with test-owned credential + Release WPF/WebView2 + bundled
React + isolated real Java Runtime/Ollama verified start, trusted page, five routes, safe status,
all seven native entries, security blocks, production settings, new reload session/stale response,
close/reopen, native initialization/renderer failure fallback and tray survival.
Physical Ctrl+Alt+Shift+T on a synthetic selected WPF textbox drove production helper/UIA/Ollama
Translate; exact selected input and unchanged clipboard sequence passed. Foreground ownership
is checked before sending keys; harness-only thread attachment stabilizes fixture activation.
No unexecuted manual behavior, full OS IME, live Chrome or mixed-monitor test is claimed.

## 34. Existing Regression

Java `mvnw.cmd clean verify`: **105 PASS**. Desktop final default Release build/test: **168 PASS**.
Real native Translate/Summarize/stateless Ask: **PASS**. Workspace WPF export/restore/full logical
field equality/SQLite integrity/search/continued Conversation/explicit Memory/no replay: final
**PASS**. Original first two recovery attempts failed the model marker answer assertion despite
exact restored fields passing; safety diagnostics were added, original assertions stayed intact,
and the third complete run passed. Root cause of answer variability is not established.
Memory-only integrated WPF/HTTP/Ollama logical recovery: **PASS**, including English/Chinese
search, revisions/archive/exact preview/selection/negative restore/Browser denial/statelessness.
Browser Translate-only batch/security/restart/revoke/ownership: **PASS** with synthetic HTTP
clients and real Ollama relay; this is not new real-Chrome acceptance. WinCred/hotkey/UIA/
selection/controlled clipboard/helper/lifecycle suites remain covered and pass.

## 35. Privacy / Security Audit

PASS: source/build/nested archives/evidence/logs/generated and packaged frontend, actual secrets,
ignored-output/backup checks, and explicit frontend no-network/no-domain-storage checks.
Final synthetic WinCred/bearer never enters retained JS/profile/evidence. UDF audit is separate
and scans actual files after normal close and controlled crash. No production payload/content/
title/query/credential/Authorization/WebView/backup logging or console capture was introduced.
The existing Browser fixture's public word `Settings` coincides with approved static IA: only
static frontend body scans exempt that exact public label; log/evidence/UDF/actual-secret checks
stay complete, and random personal markers remain checked. No telemetry SaaS/CDN/fonts/upload.

## 36. Known Limitations

Actual Windows IME is the outstanding closing gate. DPI100% and zoom125% are measured, without
mixed-monitor hardware coverage. Live Debug HMR, physical removal of WebView2 and browser/OS
crash cleanup guarantees are unverified. Existing model-answer/UIA/foreground automation can
vary across runs; failures are recorded rather than softened. Private browser metadata/cache
can remain despite cleanup; no forensic erasure. Runtime/.NET/WebView2 provisioning stays
external. Theme does not survive private-profile cleanup. Native modal acknowledgements can
wait until a dialog closes. M5A intentionally has no React business controls.

## 37. Deferred to M5B–M5E

Assistant/Translate execution, Conversation CRUD/send/task control, Memory CRUD/explicit
selection and richer Settings remain later approved migration. Knowledge/RAG/Finance/Agents/
Tools/Browser Conversation/streaming/edit-regenerate-branching/automatic Memory, supervisor,
installer/updates and backup redesign remain absent. No old WPF surface has been retired.

## 38. Architecture Compliance

Approved production chain and ownership hold. Runtime is the single durable/execution truth;
WPF is the native/security/lifetime authority; React is presentation. No bearer/browser secret/
arbitrary capability is exposed, no direct Runtime access/CORS widening, no second product
stack/store/supervisor, no new domain semantics, no future milestone implementation. Accepted
ADRs describe locked implemented boundaries and explicitly distinguish future retirement/work.

## 39. Git Status

Implementation and documentation are local feature-branch commits; `main`/`origin/main` remain
the exact published baseline. Final documentation commit includes this report; its exact SHA
is available from `git log -2` rather than a self-referential report hash. Final status is checked
after that commit. No generated frontend/dependency/binary/DB/backup/log/evidence artifact is
tracked. There is no merge/push/tag/release/branch deletion and no configured CI claim.

## 40. Recommended Next Step

Architecture / Closing Review of this implementation, plus genuine Windows IME accessibility
acceptance in an interactive desktop session. Keep M5A partial until that evidence is established.
Only after its acceptance/review may the next separately authorized M5B scope begin.
**STOP here: M5 remains OPEN; no M5B work begins.**
