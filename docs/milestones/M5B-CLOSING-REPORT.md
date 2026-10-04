# M5B CLOSING REPORT

Date: 2026-10-04 (Asia/Shanghai)

## 1. Result

**M5B — IMPLEMENTED / LOCAL ACCEPTANCE PASS.**
**M5B CLOSING CANDIDATE — GO.**
**M5 — OPEN.**

Assistant and Desktop Single Translate are real production React pages. All required local gates,
including actual Windows Pinyin and exact Runtime input, pass. This is a candidate for Architecture /
Closing Review; M5B is not self-approved CLOSED — GO. M5C–M5E have not started.

## 2. Delivery / Reality Gate

PASS. Started on clean `main`, with HEAD/main/origin/main equal to
`8bf5aac70630fae730ea5ba1d101b42a5e277af5`. Successful live fetch used command-local HTTP/HTTPS
proxy `http://127.0.0.1:7890`, without global Git proxy changes. Remote had main only.
README, STATUS, current architecture, ADR-001..010, M5A report and actual Core/Desktop/frontend/
hosting/bridge/native selector/acceptance/privacy contracts were inspected. No material architecture
conflict required a stop. A final live fetch reconfirmed the published main baseline.

## 3. Git

Local branch: `m5b-assistant-translate-migration`, created directly from the required baseline.
Implementation: `8bfc362196233297302997bf23cf9052147ef2f9` —
`feat: migrate assistant and translate to main workspace`. Current docs and this report are a
separate documentation commit. No main development, merge, push, tag/release, branch deletion or M5C branch.

## 4. Baseline

M0/M1/M1.5/M2/M3/M4 and M5A remain CLOSED — GO; ADR-001..010 remain Accepted.
M5A acceptance: Java105 / Desktop168 / Frontend14. M5B final: **Java105 / Desktop197 / Frontend33**,
zero failures/errors/skips. No Java production/test or Browser Extension file changed.

## 5. Files Changed

20 implementation files plus README, STATUS, current architecture and this report (24 total).
Frontend: App integration/shell regression, bridge contracts/client and operations tests,
OperationPage and business tests, narrowed native WorkspacePage and editor styles.
Core: AssistantOperation admission-uncertainty flag and safe OutcomeUnknown text.
Desktop: AssistantApp integration, WorkspaceBridge expansion, WorkspaceOperations registry and
acceptance friend assembly. Tests: WorkspaceOperationsTests. Acceptance: the separate
AssistantTranslateAcceptance project/program, assistant-translate-smoke.py and expanded privacy scanner.
Generated assets/binaries/dependencies/data/evidence remain ignored. M5A Closing Report is unchanged.

## 6. Architecture Boundaries

Bundled React → typed WPF bridge → existing application-owned RuntimeClient → external Runtime →
LOCAL_ONLY Ollama. Runtime retains domain/execution/storage/context/provider ownership; WPF retains
credentials/dialogs/clipboard/lifetime/security. React holds presentation state only.
No new framework, provider/model settings, Runtime supervisor, durable store or domain semantics.

## 7. Bridge Expansion

Exactly six additive v1 methods: `assistant.selectMemories`, `assistant.submit`, `translate.submit`,
`operations.get`, `operations.cancel`, `operations.copyResult`. Existing M5A methods remain.
Schema/duplicate/unknown fields, source/current document/session/version/UUID/requestId and bounds
are checked before dispatch. Eight pending requests and4096 consumed IDs per document remain.
Requests retain32KiB. Replies expand to64KiB: a verified8192-byte control-character result can escape
to49152 JSON bytes; a maximum-output test proves no silent truncation. No generic proxy/CRUD is added.

## 8. Operation Registry

Application-owned transient registry admits only successfully validated Runtime admissions.
Opaque operation UUID binds originating session, capability, Runtime task ID/prompt version and safe
transient state/result. Reservations protect concurrent admission; accepted/reserved total ≤16.
Live runners never evict. Terminal or ended communication-error entries expire after2minutes,
pruned on access and every30seconds. App restart loses registry. Diagnostic DTO strings redact content.
The shared AssistantOperation is the sole Runtime poller; operations.get serves its strictly validated
bound snapshots, avoiding a second polling/cancel/error implementation. Unknown/foreign IDs share safe denial.

## 9. Assistant UI

Production controlled textarea, Ask/Summarize selector, native Memory action, Submit, queued/running/
cancelling/terminal status, final plain result and Copy result. All specified presentation states exist:
Idle, Validating, Submitting, Queued, Running, Cancelling, Succeeded, Failed, Cancelled, TimedOut, OutcomeUnknown.
One bounded current presentation per page; no transcript/history.

## 10. Ask Semantics

Ordinary Ask submits only question and the host-owned existing profile through RuntimeClient.
Single-turn/stateless/no Conversation persistence. Live relay asserts ordinary admission memoryCount0;
Runtime-to-provider user input remains exact. Isolated SQLite has zero Conversation rows after all business flows.

## 11. Summarize Semantics

Existing SummarizeInput/RuntimeClient/profile/prompt. No Memory references, history, Conversation or
automatic save. Mode change clears selection and previous result presentation. Real React and native WPF
Summarize complete successfully through real Runtime/Ollama.

## 12. Explicit Memory Ask

Use Memory opens actual native MemorySelectionWindow with full native preview/Add/confirmation.
React receives selected title, memoryId, positive decimal-string Int64 revision and position only.
No collection/content/hidden revisions. Host rejects references not authorized by this session's picker.
Selected Ask uses existing Memory Ask with immutable exact-revision references and memory-ask-v1.
Real synthetic ACTIVE context answer contains the correct generated marker; next ordinary admission has zero references.

## 13. Memory Selection Lifecycle

Default no Memory. Picker Cancel preserves current UI/host selection; Review/Change starts fresh.
Accepted admission immediately consumes host and React selection, including terminal failure/cancel later.
Pre-admission stale retains title/reference state, blocks submit and requires explicit reselection or Clear.
No latest-revision substitution. Real edit/stale rejection and unit forged/overflow/order/duplicate tests pass.
Switching to Summarize clears React selection; empty-reference admission never injects host selection.

## 14. Translate UI

Manual text input/paste, native-equivalent target choices zh-CN/en/ja, Submit, queued/running/cancel,
terminal result/error and explicit Copy result. No batch/history/favorites/file/provider UI.

## 15. Translate Semantics

Existing TranslateInput and RuntimeClient SubmitTranslateAsync create Desktop Single Translate.
Frontend payload is text/targetLanguage only; host selects fixed existing profile. Browser DOM/Dynamic/
Restore/frame behavior and Browser batch UI remain independent and unchanged.

## 16. Cancel

Cancel accepts only the current session's registered nonterminal operation. Shared runner requests
existing RuntimeClient cancellation after accepted identity is known. Acknowledgement means requested;
GPU stop is not promised. Terminal Runtime truth wins, including success racing cancellation.
UI reconciles a rejected/racing cancel by reading the owned snapshot. Route/reload/workspace close
never auto-cancel; application exit uses bounded existing best-effort semantics.

## 17. Polling / Terminal States

Existing strict task identity/capability/profile/prompt-version/terminal/result/error checks stay intact.
Shared300ms Runtime polling; React reads registry snapshots350ms without overlapping requests.
Terminal states stop polling. Failed/Cancelled/TimedOut render status/error with no fabricated answer.
Communication failure stops automatic presentation polling and reports unconfirmed outcome.

## 18. Unknown Outcome

Shared AssistantOperation marks POST transport/deadline/unverifiable-response uncertainty while preserving
underlying RuntimeClient error classification and original strict regression assertions.
Native and React presentation show safe Outcome unknown when admission cannot be proven.
Known credential/validation/stale/capacity rejections stay distinguishable. No automatic retry/resend/replay.
Lost reply, submit timeout and session rotation coverage pass; accepted operations survive document replacement.

## 19. Copy Result

operations.copyResult takes only opaque owned operationId. Only verified SUCCEEDED result is copied by
native Clipboard.SetText, following explicit UI click. Nonterminal/failed/foreign/unknown IDs and arbitrary
clipboard payloads reject. Live production copy passes; fixture restores the previous clipboard if it still
owns the test result, and never logs clipboard contents.

## 20. Credential Isolation

Same application RuntimeClient and existing WinCred credential callback. React never receives bearer,
Authorization, token file path, Browser credential/proof or ownership secrets. Real acceptance uses a
unique temporary WinCred target and forgets it; the user's production credential target is not changed.

## 21. Browser Boundary

Main Workspace retains native identity. No React Runtime CORS permission or direct HTTP transport.
Browser remains Translate-only; Runtime/Extension allowlists and permission model are unchanged.
Cross-owner tasks, unauthorized capabilities, originless mutations/admin and revoked credentials still deny.

## 22. Conversation Boundary

No Conversation endpoint/CRUD/send/transcript storage in the M5B business bridge or UI.
Conversations still opens the existing native window. Live isolated DB assertions prove no Conversation
persistence after Assistant/Translate/native stateless flows. Durable React migration remains M5C.

## 23. Legacy Native Compatibility

AssistantWindow and AssistantOperation remain production quick/fallback/regression surfaces.
Real WPF native Ask/Summarize and real hotkey/UIA Translate pass. Native Memory/Conversation/pairing/
backup/credential flows and toolbar fallback remain. No global hotkey moved to React or selection Ask hotkey added.

## 24. React Data Persistence

Personal input/result/selection live in memory only. Theme remains the only localStorage value.
No sessionStorage/IndexedDB/Cache API/service worker/console/URL/query/hash personal content, analytics,
browser clipboard or Markdown library. Hashes contain only fixed page routes. Reload clears presentation,
rotates session and does not replay; route switch retains limited page state without submitting/cancelling.

## 25. UDF Privacy

Dedicated private profile/ACL/environment override policy, autofill/password restrictions and bounded
AllProfile cleanup remain. Final business acceptance scans **303 UDF files,0 matches**, all readable.
Input/summary/translate/AI result/Memory title/context marker/Chinese marker/temporary bearer are generated
or captured only in the fixture. Normal submit/result/route/reload/close/reopen and real renderer crash
with business input are exercised. UTF-8/UTF-16/JSON-escaped scans and repository/evidence/log audit pass.
Unique result marker and qualifying full real answers are scanned. No forensic erase or OS-crash cleanup guarantee.

## 26. Rendering / XSS Safety

React text children/textarea/pre and bdi metadata only; no dangerouslySetInnerHTML, HTML/Markdown or
syntax highlighting. Tests cover script/img-onerror/entity/RTL strings; live hostile editor/result flow
shows plain text and creates no script/image node. Unallowlisted/raw errors and task/result schema attacks reject.

## 27. IME-safe Input Logic

Controlled onChange preserves original value without trimming/truncation. Composition events only track
state; no compositionupdate submit. Enter/Shift+Enter newline, Ctrl+Enter submit guarded by React composing,
native isComposing and keyCode229. Explicit button is available after committed valid input.
Synthetic composition tests are unit coverage only and not counted as native acceptance.

## 28. Real Windows Pinyin Acceptance

**REAL IME PASS. exact-value assertion PASS.** Actual Windows Chinese/Pinyin input in production
Assistant textarea, using physical keybd_event keyboard input and native Space candidate selection.
Candidate Enter produces no submission. Fresh full synthetic Chinese string is entered through native
Pinyin/selection plus digits; observed actual compositionstart/update/end and committed exact value.
One explicit production submit, exact React/bridge input, isolated Runtime admission and exact provider
user-message input pass. Real Ollama completes and UI shows success. No phrase/body is in retained evidence.
No dispatchEvent composition, programmatic phrase setting or clipboard substitution forms this evidence.
An earlier probe observed composition without full phrase; it was not counted as PASS.

Reproduction: `python -X utf8 scripts/assistant-translate-smoke.py`. Other machines without reliable native
automation receive PARTIAL. `--manual-ime` displays the runtime-generated phrase and Pinyin steps in a
native dialog, waits for actual composition/commit/Submit, then asserts exact input in the in-memory relay.

## 29. Accessibility

Labels, keyboard-accessible controls, live status/alerts, focus outlines, Chinese rendering, multiline,
native picker return, focus after submit/cancel and route navigation are covered. Production business editor
visible focus and125% zoom/no horizontal overflow pass; observed DPI scale1. Actual Pinyin gate passes.
Screen-reader hardware and mixed-DPI multi-monitor testing remain unverified, with no discovered blocking defect.

## 30. Frontend Tests

**33 PASS** (14 baseline +19). Ask/Summarize/Translate payloads, explicit metadata/decimal precision,
selection consumption/stale/clear/native Cancel, no automatic Memory/Conversation, queued/running/cancel/
failure/timeout/result/copy, composition guards, input budgets, hostile output, route/session/no replay,
operation correlations and strict safe response/error schemas. Strict TypeScript and Vite build pass.

## 31. Desktop / Bridge Tests

**197 PASS** (168 baseline +29). Exact six-method allowlist, origin/session/request replay/unknown fields,
input UTF-16/UTF-8 bounds, decimal revisions/overflow, forged refs, arbitrary task ID denial, owned/unknown/
foreign operations, copy/cancel terminal guards, accepted reload survival/stale reply suppression,
capacity/expiry, unknown admission, strict shared polling, DTO redaction and maximum escaped response.
Original RuntimeClient/legacy/Memory/Conversation/credential/selection/helper/security suites remain passing.

## 32. Runtime Regression

`mvnw.cmd clean verify`: **105 PASS**, no failures/errors/skips, packaged jar rebuilt. Java code is unchanged.
Real stateless native three-capability HTTP smoke, WPF legacy paths, Workspace logical recovery/continue,
Memory-only logical recovery/search/explicit Ask and Browser Translate-only security/real inference pass.

## 33. Real Ask Smoke

Production React editor → assistant.submit → host registry/shared runner → isolated Runtime → actual
Ollama → plain verified result. Exact synthetic input/provider message, correct result marker, owned copy,
ordinary memoryCount0 and no DB persistence all pass.

## 34. Real Summarize Smoke

Production Summarize mode and synthetic paragraph through existing Runtime/Ollama pass. No references/
automatic saves. Legacy WPF Summarize also passes.

## 35. Real Memory Ask Smoke

Fixture creates a synthetic ACTIVE entry, opens the real native selector through React, loads full
preview, Add/Confirm, displays safe metadata and submits exact references. Correct real model marker,
immediate selection clear and next ordinary memoryCount0 pass. Real changed-revision rejection blocks
until explicit Clear; no replacement. Fixture deletes its synthetic Memory before DB/stateless assertions.

## 36. Real Translate Smoke

Production Translate text/target UI through host/Core/Runtime/local Ollama completes with plain result.
Accepted cancellation reports actual CANCELLED and no answer. Existing native single and Browser Single/
Batch Translate regressions pass independently.

## 37. Real Windows Acceptance

Release WPF + installed WebView2 **154.0.4258.53** + bundled production React, actual app/tray/runtime,
temporary WinCred, native selector/clipboard, real Ollama, Pinyin/exact relay, focus/zoom, routes/reload/
close/reopen/crash fallback pass. Acceptance adds no production scripting/relay/IME endpoint.
Default build/publish and damaged/missing asset/Release-dev rejection checks pass.
Final reviewable Desktop publish: ignored `.verification/m5b-desktop-final`.

## 38. Hotkey / Selection Regression

Real foreground-owned synthetic WPF selection, physical Ctrl+Alt+Shift+T, production UIA/helper → native
Translate → Ollama passes. Exact selected input and unchanged clipboard sequence pass in MainWorkspace
foundation regression. Existing protected/unsupported/copy/timeout/helper/lifecycle suites pass.
No new full real-browser or protected-app manual matrix is claimed.

## 39. Browser Regression

Synthetic HTTP Browser clients with real Ollama PASS: pairing/readiness/native auth, Single/Batch,
one Batch POST/task/inference for3 mappings, origin checks, ownership404, originless GET allowlist,
denied mutation/admin, restart credentials, revoke/restart and safe audit. Re-run native/browser smoke
with a temporary WORKSPACE_DATA_DIRECTORY rather than using personal domain data.
This is not a rerun of historical Chrome GUI/MV3/DOM/Dynamic acceptance; Extension code was unchanged.

## 40. Privacy / Security Audit

PASS: source/build/nested archives/evidence/logs/production assets, actual temporary secrets/random
business markers, Git ignored-output/DB/backup controls, no frontend network/domain storage and no
bridge content diagnostics. Final business audit0 matches,0 tracked generated/data artifacts; UDF303/0.
Post-documentation repository audit also PASS:249 source files,2027 files,128 archives,34784 byte/archive
checks and0 matches; no tracked build/backup artifacts. This static audit complements the fixture's live-marker scan.
Fixtures emit only controlled checks/counts/metrics, discard raw inputs/outputs/credentials after shutdown,
and clean their own temporary Runtime/data. No provider dump/prompt/context/body/path logging is added.

## 41. Performance

Final measured Release-equivalent warm run; observations, not an SLA:

| Metric | Observed |
| --- | ---: |
| Assistant route to editor interaction | 31.15ms |
| Translate route to editor interaction | 46.12ms |
| Runtime admission HTTP round trip through relay | 3.13–47.38ms |
| React Ask completion | 1233.76ms |
| React Summarize completion | 542.30ms |
| React explicit Memory Ask completion | 789.77ms |
| React Single Translate completion | 754.27ms |
| Native Pinyin operation completion | 1129.46ms |
| Desktop + acceptance working set | 197,586,944 bytes |
| WebView browser process working set | 160,423,936 bytes |
| Production JS | 242,201 bytes (+10,947 vs M5A) |
| Production CSS | 6,301 bytes (+859 vs M5A) |

Route timings measure already-mounted bounded page presentation, including harness calls; admission
timings include Runtime/relay and are not pure bridge overhead. Working sets include acceptance overhead,
exclude other WebView children and are not total product memory. No Conversation/Memory list preload.

## 42. Known Limitations

No screen-reader hardware/mixed-DPI matrix, new live Chrome acceptance or universal OS-crash cleanup.
Runtime/model/foreground/IME automation remains environment-dependent. Registry/results are transient:
reload loses presentation/ownership access; expired operation cannot be copied through the registry.
Core and bridge enforce byte/character/transport budgets; no silent truncation. POST uncertainty can
leave a Runtime-owned task running until its bounded truth/deadline; no automatic resend.
InPrivate metadata/cache may remain; account/admin compromise and forensic erase are outside claims.

## 43. Deferred to M5C–M5E

React durable Conversations, Memory CRUD and full Settings; product consolidation/installer/update/
provisioning/retirement. No Knowledge/RAG/Finance/Agent/Tools/Browser Conversation/streaming/edit/
regenerate/branching/auto-title/automatic Memory/backup redesign/Markdown. All legacy surfaces remain.

## 44. Architecture Compliance

ADR-001..010 remain Accepted. Ownership/credential/least-privilege/trusted content/privacy/build split
preserved; no new ADR needed. Host owns operations and native dialogs/clipboard; Runtime owns execution
and durable domains; React is in-memory presentation. Legacy/React share AssistantOperation semantics.
Required real IME gate is independently proven with native input and exact Runtime evidence.

## 45. Git Status

Final local delivery consists of the implementation commit and a separate documentation commit on
the requested branch. Main/origin/main remain the exact published baseline; remote main only.
Working tree is checked clean after documentation commit. No generated/evidence/data/secret file tracked;
no merge/push/tag/release/branch deletion. No GitHub Actions workflow exists, so no CI result is claimed.

## 46. Recommended Next Step

Architecture / Closing Review of this M5B candidate, using implementation, final suites, real production
business/IME evidence, security/privacy and preserved native paths. Keep M5 OPEN; no self-approval or
formal main delivery. **STOP here. Do not start M5C.**
