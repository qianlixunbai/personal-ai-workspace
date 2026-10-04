# M5D CLOSING REPORT

Acceptance date: 2026-10-05, Asia/Shanghai. Scope: Memory + Settings Migration only.
This records a local closing candidate; Architecture / Closing Review remains required.

## 1. Result

**M5D — IMPLEMENTED / LOCAL ACCEPTANCE PASS.**
**M5D CLOSING CANDIDATE — GO. M5 — OPEN. M5E — NOT STARTED.**

All M5D hard gates passed: Runtime-owned durable CRUD and explicit Save; literal search, filters,
20-item metadata list; exact revisions, real conflict and missing protection; dirty navigation,
Reload and native close/reload protection; lifecycle without autosave; physical deletion confirmation;
browser persistence/UDF privacy; actual Settings; real Windows Pinyin and native backup recovery.
The final full M5D Windows run contains233 check records, including repeated fixture steps, rather
than233 independent gates. Java105 / Desktop251 / Frontend103 passed with no failures/errors/skips.

## 2. Reality Gate

Before development, branch was `main`, working tree clean, and the live command-local proxy fetch
succeeded. HEAD, main and origin/main all equalled `4f97a03f4317f02370e2a5bb10f80eb7ce6ad61d`.
Remote branches were main only. A second live fetch during final regression confirmed that same
published main. No global proxy setting was changed.

```powershell
git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 fetch origin
```

README, STATUS, current architecture, Accepted ADR-001..010, M3/M4/M5A/M5B/M5C reports and the
existing RuntimeClient/Memory/native maintenance/bridge/frontend implementation were inspected.
No material architecture conflict was found.

## 3. Git

Branch: `m5d-memory-settings-migration`, created from the verified published baseline.
Implementation: `6e6423dc552e2578e22753c16101e55240cc099e`,
`feat: migrate memory and settings to main workspace`.
Closing documentation is a separate subsequent commit, `docs: record M5D memory settings closing candidate`.
There was no merge, push, tag, release, branch deletion or history rewrite.

## 4. Baseline

| Suite | Published M5C baseline | Final M5D | Increase |
| --- | ---: | ---: | ---: |
| Java | 105 | 105 | 0 |
| Desktop | 233 | 251 | 18 |
| Frontend | 66 | 103 | 37 |

No Java/Core production change was needed. Existing accepted tests remain, including UIA/password,
clipboard, Memory selection and Conversation recovery assertions.

## 5. Files Changed

The implementation commit changes28 files. Frontend: App integration, typed bridge/contracts,
new Memory DTO validators, MemoryPage, SettingsPage, ConfirmationDialog, styles, tests and fixtures;
the replaced native-entry placeholder WorkspacePage is removed. Desktop: new WorkspaceMemory,
WorkspaceBridge, AssistantApp, MainWorkspaceWindow, WebViewHost, friend assembly and bridge tests.
Acceptance: new MemorySettingsAcceptance project and isolated Windows runner. Existing privacy
scanner accepts fresh Memory markers; Browser scanner handles the removed tracked placeholder.
The existing UIA fixture waits for WPF ApplicationIdle before querying its hidden tree.
Documentation: README, STATUS, current architecture and this report only. Existing closing reports
and ADRs were not edited. Generated assets, databases, backups and evidence remain ignored.

## 6. Architecture Boundaries

Production flow is bundled React → typed allowlisted WPF bridge → application-owned RuntimeClient
→ existing native-only Runtime API → SQLite. Runtime is the sole durable Memory/Conversation truth.
React owns presentation, one current metadata page, one loaded Memory snapshot and its local draft.
There is no second database, generic CRUD/HTTP/SQL proxy, direct Runtime fetch or new authority in React.

## 7. Runtime Contract Reuse

Existing list/get/create/update/archive/restore/delete RuntimeClient calls and Runtime validators
are reused. API/schema/search implementation, Memory types, MANUAL source, capacity1000,
revision semantics, backup versions and provider policy remain unchanged.
Archived Memory remains editable through explicit Save, matching the native MemoryWindow.

## 8. Bridge Expansion

The exact additions are `memory.list`, `memory.get`, `memory.create`, `memory.update`,
`memory.archive`, `memory.restore`, `memory.delete` and `memory.editorState`.
Payloads have exact fields, strict enums/canonical UUIDs, Unicode validation and bounded pages.
Unknown methods/fields and duplicate JSON fields fail closed. Existing version/origin/document/
session/requestId validation remains. `memory.editorState` carries only `{dirty:boolean}` and
returns `{acknowledged:true}`; it is a transient native UI protection signal without text or persistence.

## 9. Session Authorization

Only IDs returned by actual list/create in the current live session become authorized.
get/update/lifecycle/delete require that authority; delete revokes it. The set is capped at1000.
Document rotation ends the old set, and a late old-session list/create cannot authorize a new one.
Bridge and frontend generation checks suppress old responses/errors. Authorization stores IDs only,
without titles, content or a Memory cache. Real reload rotates the session and requires a fresh list.

## 10. Memory DTO Projection

List projection has exactly eight fields: `id,type,title,status,revision,source,createdAt,updatedAt`.
Full projection adds only `content`. No path, SQL, credential, backup bytes, internal execution
identity or raw exception reaches JavaScript. Full objects cross only explicit get and successful
create/update/archive/restore. DTO diagnostics redact title/content. Frontend rejects extra fields,
invalid calendar timestamps/enums, inconsistent identity/revision/status and overexposed list items.

## 11. Metadata-only List

Runtime's existing full list contract is projected by the Host before posting to WebView.
React cannot preview list content without explicit get. Automated schema tests and the real
Windows bridge interception both verified metadata-only output. No initial selection, full-body
preload, all-page preload, background search or automatic Memory selection is added.

## 12. Response Budget

The existing32KiB request and64KiB ordinary response ceilings are unchanged. Only the pre-existing
`conversations.get` detail retains its1MiB exception. Tests serialize a legal maximally escaped full
Memory and20 maximally escaped metadata titles inside real bridge envelopes under64KiB.
Title≤160 scalars; content≤2000 scalars AND2000 UTF-16 units AND8192 UTF-8 bytes; query≤160 scalars.
Invalid Unicode/NUL is rejected. No silent truncation or global budget increase is used.

## 13. Memory Page

Memory is a real React management page with list/search/filters/paging, explicit editor, metadata,
Save/Archive/Restore/Delete/Reload/New, dirty/stale/missing/unknown state and native fallback/backup
entries. First visible route loads one list page. The mounted hidden page preserves its bounded UI
state; confirmed dirty route departure discards the editor. All authoritative reads come from Runtime.

## 14. Search Semantics

Search is the existing literal, case-sensitive substring match across title/content. Runtime keeps
its FTS5 trigram path for queries of at least three Unicode scalars and instr fallback for shorter
queries. `%`, `_`, quotes, spaces, punctuation and FTS-like syntax are text, without interpolation.
Input typing does not search; Search or Enter submits, while IME candidate Enter is suppressed.
Search/Refresh preserve the loaded snapshot and exact dirty draft.

## 15. Filters

ACTIVE/ARCHIVED and ALL/PREFERENCE/PROJECT_NOTE map to existing Runtime query enums.
Changing filter resets the list to page0 and retains the editor. Frontend validates returned items
against requested status/type. Real status/type combinations matched direct Runtime results.

## 16. Pagination

Fixed page size20; pages0..49; maximum domain capacity1000. Next/Previous reflect total and
loading state; only the requested page is read. Real21-item fixture displayed20 then1 then20.
Deleting the last item of a tail page explicitly refreshes and corrects to the valid previous page.
Search/filter/page changes never silently clear dirty content.

## 17. Get / Editor

Explicit row selection calls get; no body is inferred from list metadata. The editor retains the
loaded exact Runtime snapshot, exact editable type/title/content and canonical revision string.
Status/source/ID/timestamps remain read-only. No trimming, Unicode normalization or maxLength
truncation changes text. Selection respects dirty discard confirmation and returns focus to title.

## 18. Create

New initializes a local empty PROJECT_NOTE draft after dirty confirmation. Only explicit valid
Save invokes create. Successful response authorizes/adopts the new ID and snapshot, clears dirty,
then refreshes the list. Typing and composition never create or autosave. Real Chinese title/body
creation persisted exact committed values; unknown create outcome freezes Save without replay.

## 19. Update

Explicit Save sends the selected ID, exact loaded expectedRevision and exact draft. Successful
response becomes the canonical snapshot and clears dirty. Tests and real Runtime checks verify
preserved whitespace/newlines and content equality. A failed follow-up list refresh retains the
confirmed successful Save and reports the refresh problem separately.

## 20. Revision Handling

Positive Int64 revisions cross as canonical decimal strings, never JS Number. Host validates digits
and invariant long parsing; frontend uses BigInt only for validation/comparison. Tests include
`9007199254740993` and `9223372036854775807` and reject zero/signs/leading zeros/exponents/overflow.
The expected revision always comes from the loaded full snapshot, rather than a list row or guess.

## 21. Real Revision Conflict

An independent authenticated fixture client updated the same selected item after React loaded it.
React's stale Save received real MEMORY_REVISION_CONFLICT; exact draft remained visible and
Save/Archive/Restore/Delete were blocked. Reload Cancel retained draft; confirmed Reload fetched
the actual newer revision/content. No overwrite, force-save, automatic merge or retry was used.

## 22. Dirty State

Dirty is exact type/title/content inequality against the loaded snapshot, or the empty New baseline.
Reverting all fields clears it. The UI shows an unsaved indicator. Only boolean transitions reach
the native Host, serialized to preserve ordering; there is no body-bearing synchronization or log.
An unconfirmed protection signal displays a controlled error instead of claiming protection succeeded.

## 23. Dirty Navigation Protection

Selection, New, route departure and explicit Reload use labelled default-Cancel dialogs before
discard. Real selection/New/route Cancel preserved exact values. Sidebar and direct hash routing
both pass through the guard. Native main-window close/application exit and trusted document reload
check the Host signal before disposal/session rotation and show native Yes/No with defaultNo.
Real Cancel retained the same window/document/session/draft; confirmation allowed close/reload.
Renderer invalidation preserves native dirty state for later close protection.

## 24. Reload

Reload is explicit get-latest for the loaded identity. Dirty Reload asks before discarding; Cancel
performs no get and keeps draft/revision. Confirmed Reload adopts actual Runtime truth and clears
stale/unknown state. Document reload is separately protected natively and rotates authority after
confirmation. Reopen does not restore browser drafts or replay mutations.

## 25. Missing / Concurrent Delete

A second client physically deleted an item while React held a dirty snapshot. Real Save returned
MEMORY_NOT_FOUND; React retained the draft, marked missing and blocked mutations against that ID.
It did not silently create a replacement. Missing Reload is disabled; explicit New/other selection
is available with normal discard protection. List/Refresh reflects Runtime deletion.

## 26. Archive

Archive sends ID and expectedRevision only. Runtime advances revision/status to ARCHIVED;
there is no draft in the request. A clean editor adopts the response. A dirty editor adopts only
the saved baseline/status/revision while retaining exact draft. Archive is independent of Save.

## 27. Restore

Restore uses the existing same-ID ARCHIVED→ACTIVE lifecycle call and revision safety.
Real Runtime status/revision transition passed. Restore retains dirty fields without committing
them; the next explicit Save uses the advanced revision. Nothing is recreated or automatically selected.

## 28. Dirty Lifecycle Semantics

Real archive/restore with locally edited body left Runtime content unchanged and draft intact.
The subsequent explicit Save persisted the draft with the lifecycle's new revision, including a
Save while ARCHIVED. Frontend and Host tests cover this separation and exact revision handling.
Lifecycle conflict/missing/uncertain outcomes receive the same fail-closed editor protection.

## 29. Delete

Delete requires an explicit default-Cancel dialog explaining physical irreversible deletion inside
the Workspace, disappearance from future search/selection, dirty discard and no forensic erasure
guarantee. Cancel does not call delete. Confirmed delete uses expectedRevision, clears editor,
revokes session authority and refreshes/corrects paging. Real physical deletion and tail correction passed.

## 30. Mutation Outcome Handling

Timeout, transport failure and unverifiable mutation responses are Outcome unknown, rather than
known failure. Exact draft stays; mutations freeze; no automatic retry/replay occurs. Existing-ID
recovery uses explicit Refresh/Reload; an uncertain create requires explicit list inspection and
selection/New. Tests cover all five mutation timeouts and ambiguous creation. Confirmed business
errors retain their safe codes; successful mutation with list-refresh failure remains successful.

## 31. Plain-text Rendering

Titles/content/query are controlled text/value nodes. No Markdown/HTML parser, innerHTML,
dangerouslySetInnerHTML, eval or content-bearing URL is added. Hostile markup/entities/bidi/control
fixture text remained literal in tests; React interpolation escapes list titles. Editor/list wrapping
and bidi isolation preserve usability without changing stored text.

## 32. Real Windows Pinyin

Final full Release WPF/WebView2/bundled React acceptance used the installed native Windows
Pinyin input method and physical key events on both production Memory title and textarea.
Native/DOM composition was observed, committed Chinese was saved explicitly, direct Runtime
values matched exactly and Reload preserved them. Chinese title and body searches also passed.
The driver checks its own foreground window before typing and restores the original input language.
Earlier foreground failures and a `--skip-ime` PARTIAL run do not substitute for this final PASS.
The skip path always reports realWindowsPinyin=false and a PARTIAL closing gate.

## 33. Real Search Acceptance

Real title/body Chinese queries and one/two/three-character, literal punctuation/quote/space queries
matched the existing Runtime list contract. Status/type filters and explicit Search/Enter paths are
tested. Every search retained a dirty editor. Search typing has no network side effect; composition
candidate acceptance does not submit a search.

## 34. Real CRUD Acceptance

The real production page created/get/updated, archived/restored with dirty preservation, saved
ARCHIVED content, physically deleted and reopened. Direct Runtime/SQLite comparisons verified
exact content and durable metadata. Concurrent revision conflict and deletion were induced through
the fixture's second client, without mocked production Runtime or a new API.

## 35. Real Pagination

The real page exercised first20, next1, previous20, both boundary button states and deletion of
the tail item followed by page correction. Lists stayed metadata-only. The real driver and frontend
tests separately assert page counts; Host tests cap authorization and request bounds at1000/49.

## 36. Memory Backup Boundary

Memory page's backup button and Settings entries invoke fixed existing native methods.
Production MemoryBackupWindow/WorkspaceBackupWindow own paths, file I/O and Runtime validation;
React receives only entry acknowledgement. No export/import bytes, path string, merge, hot replace
or active-workspace switch bridge is added. Native plaintext/new-or-empty-target warnings remain.
Acceptance injects file-picker choices; it exercises real native controls, file I/O and Runtime calls,
without claiming automated Windows common-file-dialog interaction.

## 37. Memory Backup Recovery

Actual React-created Memory was exported through the native Memory-only workflow. The source
Runtime/data directory was made unavailable during restore; maintenance restored to an isolated new
target. Existing v1 Memory data upgraded through normal Runtime startup, then React list/get/search
read the restored values. All nine Memory fields matched exactly, including IDs/revisions/timestamps.
The existing integrated Memory backup regression also passed positive/negative/FTS/explicit Ask checks.

## 38. Workspace Backup Regression

M5D exported React-created Memory and terminal Conversation through native Workspace backup;
source-unavailable restore into an isolated new target preserved every logical Memory/Conversation/
Turn/Message/MemorySelection field. React Memory and Conversation history read the recovered data.
No restored executable task/replay was introduced. The independent M4C regression passed40
top-level checks plus real WPF phases, exact logical recovery, invalid-target/input rejection, no replay
and real Ollama continuation/explicit Memory. Its synthetic backup contained2 Memory,2 Conversations,
6 Turns,9 Messages and2 selections,4380 bytes. No user workspace was used.

## 39. Settings Page

Settings displays actual application version, Runtime reachability, credential enum and WebView
state, explicit Refresh and existing native maintenance buttons. It explains local-first SQLite,
plaintext backups, account/process boundaries, externally managed Runtime/Java/Ollama and
Translate-only Browser scope. There are no speculative disabled configuration controls.

## 40. Runtime Status

The existing safe shell status contract reports actual Runtime connectivity. Real fixture stop
changed Settings to Unavailable; restart plus explicit Refresh restored real status. Reachability and
credential availability are explicitly distinguished from model/action readiness. Desktop has no
reliable current Runtime data-directory contract, so Settings states that boundary instead of inventing a path.

## 41. Credential Status

Ready/Missing/Invalid/Unavailable are existing safe status enums; no bearer or token file contents
are exposed. Real task-owned WinCred removal produced Missing and restoration produced Ready.
All four display states are tested. The credential entry opens the existing native Assistant import/
forget flow. Acceptance used a unique credential target and left user credentials unchanged.

## 42. Native Maintenance Entries

Credential management, Browser Pairing, Memory Backup, Workspace Backup and legacy Assistant
are fixed existing allowlisted native entries. Real production windows opened and returned; credential
management focused its native import control. Maintenance availability comes from actual shell status.
No renderer-owned picker, filesystem service, credential service or backup parser was introduced.

## 43. Provider/Model Boundary

Runtime remains responsible for provider/model profiles, prompt/generation policy and execution.
Settings offers no invented model picker, provider configuration, readiness assertion or process start/
stop/download/install API. Existing Ollama was used by acceptance; installer/updater/Java bundling
and process supervision remain outside M5D.

## 44. Browser Pairing Boundary

Browser pairing still requires the native explicit consent flow. Browser companion is Translate-only;
Memory, Assistant, Conversation, backup and native administration scopes are not exposed.
No CORS/origin relaxation, shared bearer, expanded permission or React pairing secret is added.
Pairing/revoke/restart/readiness/ownership/origin policies were exercised again.

## 45. React Persistence

Only existing theme preference uses localStorage. Domain state stays in RAM; no IndexedDB,
sessionStorage, service worker, Cache API, browser Memory/Conversation database or content-bearing
URL is added. Storage spies, static audit and actual WebView storage probes passed. List/editor/
search do not write storage. Close/reload/reopen/session changes do not resurrect a browser draft.

## 46. UDF Privacy

Final M5D scanned374 actual private-profile files for fresh synthetic Memory title/content/query,
dirty/stale/missing/Pinyin/restored values and temporary bearer:0 unexpected matches.
Runtime logs also passed. Native private profile settings and existing cleanup remain enabled;
fixture cleanup was confirmed. This is a marker scan/cleanup observation, not forensic erasure or
isolation from same-account/admin processes. Acceptance secrets/markers were never committed.

## 47. Accessibility

Semantic list and selected-row state, labelled filters/search/editor/pagination, read-only metadata,
live dirty/stale/missing notices and accessible operation buttons are present. Native HTML dialog
has an accessible name, initial Cancel focus, Escape cancellation, Tab containment and invoker focus
return. Save/get/Reload focus title; delete focuses New; route confirmation focuses the page heading.
Real Cancel/defaultNo checks passed. Memory at100% uses two columns;125% stacks and scrolls
vertically with no horizontal overflow, including the editor screenshot. Settings status/maintenance
labels and explicit Refresh are tested. No screen-reader or mixed-DPI hardware acceptance is claimed.

## 48. Frontend Tests

**103 PASS**,9 test files,0 failures/skips; strict TypeScript check and production build PASS.
New37 cases comprise18 DTO/client cases,14 Memory-page cases and5 Settings cases.
They cover exact schemas/revisions/Unicode/budgets, metadata-only behavior, all mutation timeouts,
list/filter/search/paging, dirty and no-autosave, conflict/missing/unknown, lifecycle, deletion correction,
route/dialog/focus, old-session error suppression, plaintext and no storage. Existing suites remain.

```powershell
npm --prefix desktop/frontend test
npm --prefix desktop/frontend run build
```

## 49. Desktop / Bridge Tests

**251 PASS**,0 failures/skips;18 added Memory bridge cases. Coverage includes exact allowlist,
origin/session checks, list/create authority/delete revocation/bound1000/late old list, strict payloads,
canonical Int64 including maximum, Unicode/page constraints, lifecycle, safe errors, redacted diagnostics,
metadata projection and legal worst-case serialization. The existing hidden UIA fixture initially
failed COM provider lookup under the full parallel suite while its isolated run passed. Waiting for
WPF ApplicationIdle before the first query produced a complete251 PASS; no assertions were removed,
no ambient text/window was read, and no catch/retry/skip converted a failure into success.

```powershell
dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --configuration Release
python -X utf8 scripts/main-workspace-build-check.py
```

Default npm-ci/frontend-build/Desktop-publish and seven Release/Debug asset safeguards passed:
missing/incompatible assets fail build; Release refuses development loading and lacks dev origin;
explicit Debug development loading compiles; published HTML/JS/CSS/manifest are complete.

## 50. Java Regression

**105 PASS**,0 failures/errors/skips, from `mvnw.cmd --batch-mode --no-transfer-progress clean verify`.
The packaged Runtime was used for fresh Windows regression. Java production/schema/API/backup
format/security code is unchanged, so no additional Java semantics or duplicate contract tests were added.

## 51. Legacy MemoryWindow

The original MemoryWindow remains usable as a fixed native fallback. Fresh native Memory CRUD/
search/archive/restore/dirty/stale/delete/reopen, explicit Memory Ask and integrated Memory backup
scripts passed. No native window is retired. React lifecycle/edit semantics reuse that accepted behavior;
MemorySelectionWindow remains the only explicit M5B/M5C picker and is unchanged.

## 52. M5B Explicit Memory Regression

Fresh real Assistant acceptance passed ordinary Ask, Summarize, explicit exact-revision Memory Ask,
stale selection protection, next ordinary Ask with zero Memory, single-turn/stateless behavior and
no automatic Memory/Conversation save. The native Memory Ask regression also passed real Ollama
selection/preview/revision/stale/no-carry-over checks. React CRUD does not change admission semantics.

## 53. M5C Per-turn Memory Regression

Fresh real Conversation acceptance passed multi-turn context, per-turn explicit exact revision,
next Turn with zero Memory, stale handling and historical dangling references. React Memory edits/
archive/delete do not rewrite historical references. Reload/reopen/restart, pending discovery/cancel,
archive during accepted execution, fail-closed interrupted execution, no replay and restored real
Ollama continuation passed. The legal large ten-Turn detail serialized988644 bytes under1MiB.

## 54. Browser Regression

Fresh Batch/security regression passed with synthetic HTTP clients and real Ollama, not new
Chrome GUI acceptance. Three items used one POST/one task/one inference and three valid mappings;
whole-batch delete reported CANCELLED. Wrong/ordinary web origin401, cross-owner404, originless
owned reads allowed under existing policy, originless mutations401/admin403/revoked401, no originless
Access-Control-Allow-Origin, readiness, native credential, pairing, revoke and restart passed.
The run supplied a fresh task-owned WORKSPACE_DATA_DIRECTORY; it did not use the user's database.
The audit now skips a no-longer-existing tracked placeholder while including untracked new sources.

## 55. M5A/B/C Regression

Fresh M5A shell/security/native fallback/navigation/frame/popup/download/permission/direct-fetch/
reload/stale/renderer-failure gates and real hotkey/UIA Translate/unchanged clipboard passed,
79 check records. Fresh M5B Assistant/Translate/native Copy/cancel/plaintext/routes/reload/reopen/
failure/native quick operations passed,79 records, realWindowsPinyin=true. Fresh M5C passed175
records, realWindowsPinyin=true, including multi-turn and durable recovery.
M5A's historical synthetic diagnostic-textarea probe still honestly reports windowsImeVerified=false
and its legacy PARTIAL IME closing field; Architecture Review had deferred IME to production editors.
It is not reclassified as real Pinyin. Actual production Assistant, Conversation and Memory editors
each passed fresh native Pinyin in their own final regression, satisfying the applicable current gates.

## 56. Privacy / Security Audit

Final full M5D runner supplied14 fresh Memory marker kinds and one ephemeral bearer only via
stdin/in-memory fixture state. Source/build/log/evidence/ignored output/archive audit PASS:
sourceFiles273 in that pre-commit snapshot, files2236, byte/archive checks34992, archives128,
native credential files6, unexpected matches0, tracked build artifacts0, tracked backup artifacts0.
Frontend direct-network/domain-storage and bridge content-diagnostic checks passed. Evidence stores
only safe check names, counts, timings and states, without the marker values, bodies, query or bearer.
Scoped temporary data removal, runtime stop and profile cleanup were confirmed. Final documentation
and staged Git files are additionally checked with the same scanner and git diff --check.

```powershell
'{}' | python -X utf8 scripts/privacy-audit.py
```

Empty-input standalone audit still scans actual native credential files and static privacy patterns;
fresh marker coverage comes from the full runner, not that empty-input invocation.

## 57. Performance

Single final local run, warm machine; observations only, without an SLA or population estimate.
Bridge timings include request/Host/Runtime/response; DOM readiness is recorded separately.

| Observation | Value | Measurement scope |
| --- | ---: | --- |
| Memory route | 36.69 ms | Heading visible |
| Memory initial route ready | 62.90 ms | Initial list interactive |
| First20-item list | 34.60 ms | Initial bridge round-trip |
| memory.get / editor ready | 46.64 ms | Explicit Reload through editor readiness |
| memory.get | 6.50 ms | Bridge round-trip |
| Search response | 4.30 ms | Last submitted query, empty-query reset |
| Page switch | 3.80 ms | Last Next/Previous bridge round-trip |
| Save | 19.42 ms | Last explicit Save through confirmation |
| Revision conflict | 23.89 ms | Stale Save through visible conflict |
| Settings route | 46.08 ms | Heading visible; actual status verified separately |
| Desktop working set | 206684160 bytes | Sampled process working set |
| WebView working set | 433094656 bytes | Sum of reported WebView process working sets |

The WebView sum can count shared pages across processes; it is not private committed memory.
JS grew from261353 to278804 bytes: +17451 (+6.68%). CSS grew from7988 to9599: +1611 (+20.17%).
Combined JS/CSS delta19062 bytes (+7.08%); final gzip JS85.53 kB/CSS2.61 kB. Existing dependencies
are unchanged; no UI framework was added. WebView runtime observed154.0.4258.53, DPI scale1.

## 58. Known Limitations

No new Chrome GUI, screen-reader, mixed-DPI multi-monitor, system-wide privacy or forensic erasure
acceptance is claimed. OS file dialogs use injected picker choices in backup acceptance. Real IME
requires an interactive unlocked desktop, Pinyin and stable fixture foreground; the runner refuses
to type into another foreground process. Earlier foreground/driver failures were corrected and the
final complete run passed; `--skip-ime` remains explicitly PARTIAL. The final UIA fixture readiness
repair is described in section49. Measurements are one-machine observations, and empty-query
search timing does not stand for every nonempty query. Runtime unavailability/ambiguous mutation
requires explicit recovery; drafts are in-memory and cannot survive a renderer/process failure.
The older Workspace-regression wrapper had a pretty-printed JSON parsing error after the underlying
script completed; its fresh PASS evidence and all40 checks were verified directly without weakening them.

## 59. Deferred to M5E

M5E product consolidation/packaging/final acceptance has not started. Installer/updater/Java bundling,
legacy window retirement and broader deployment qualification require that separate scope/review.
Automatic Memory/extraction, semantic search/RAG, model configuration UI, encrypted database,
browser Memory/Conversation access, streaming, Markdown, attachments, cross-device sync and
Finance/Knowledge/Agent features remain deferred; none were smuggled into M5D.

## 60. Architecture Compliance

ADR-001..010 remain Accepted with no new ADR. Runtime sole truth, explicit Memory choice, native
credentials/maintenance, exact optimistic concurrency, logical recovery/new-or-empty target,
Translate-only Browser and typed bounded origin/session bridge boundaries hold. No Java/Core
production, schema/API/backup-format/CORS/Browser permission change was required. M5A/B/C
closing reports and their approved statuses remain unchanged. M5D is not declared CLOSED.

## 61. Git Status

Implementation and closing documentation are separate commits on the requested local branch.
main/origin/main remain the published baseline; no merge/push/tag/release/branch deletion occurred.
Only intended source/tests/scripts/docs enter Git; database/WAL/SHM/backup/token/build/profile/
logs/screenshots/evidence stay ignored. The final commit/status/ancestry/privacy checks accompany
this report's delivery. The documentation commit is intentionally identified by its subject in
section3, avoiding a self-referential hash; the final response provides both actual commit IDs.

## 62. Recommended Next Step

Submit this concrete implementation and closing candidate for Architecture / Closing Review.
Keep **M5 — OPEN** and **M5E — NOT STARTED** until authorized. Do not merge/push or self-close M5D.

Primary real acceptance reproduction, sequentially with interactive Windows/Pinyin, existing Ollama,
and8765/18767 free (the runner refuses existing listeners and uses task-owned data/credentials):

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
npm --prefix desktop/frontend run build
python -X utf8 scripts/memory-settings-workspace-smoke.py
```

Existing regressions were run sequentially: `main-workspace-smoke.py`, `assistant-translate-smoke.py`,
`conversations-workspace-smoke.py`, `desktop-memory-smoke.py`, `desktop-memory-ask-smoke.py`,
`desktop-memory-backup-smoke.py`, `workspace-backup-smoke.py`, and
`browser-security-smoke.ps1 -Batch` with a fresh isolated WORKSPACE_DATA_DIRECTORY.
Local ignored evidence: `.verification/m5d-memory-settings-evidence.json`, M5A/B/C evidence,
M4C recovery evidence and Browser Batch evidence. Safe screenshot inspection covered
`.verification/m5d-memory.png`, `m5d-memory-125.png`, `m5d-memory-editor-125.png`, `m5d-settings.png`.
These files are local observations, not published assets or committed personal content.
