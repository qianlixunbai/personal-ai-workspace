# M3B — DESKTOP MEMORY MANAGEMENT REPORT

Date: 2026-10-03 (Asia/Shanghai)

## 1. Result

**M3B — Desktop Memory Management: CLOSED — GO (implementation acceptance; awaiting Closing Review).**
M3A — Memory Storage Foundation: **CLOSED — GO**. M3 — User-Controlled Memory Foundation: **IN PROGRESS**.
Desktop explicit management works against the existing M3A API; Java production/test code is unchanged.

## 2. Git

Repository: `qianlixunbai/personal-ai-workspace`.
Reality check ran `git status`, branch/HEAD, fetch, origin/main and the last 12 commits.
Starting branch `main`, clean tree, `HEAD == origin/main == a5d442bb9dfaf08117f90baa59dca0e312b1edd3`
(`feat: add durable memory storage foundation`). Implementation branch: `m3b-desktop-memory-management`.
Delivery commit subject: `feat: add desktop memory management`. Local delivery only; no merge/push/tag/release.
The delivery commit is identifiable by this report and subject; its own SHA is not embedded in itself.

## 3. Baseline

| Check | Starting baseline | Final regression |
| --- | --- | --- |
| `mvnw.cmd clean test` | 46 PASS | 46 PASS; 0 failure/error/skip |
| Desktop solution tests, `--no-restore` | 62 PASS | 84 PASS; 0 failure/skip |
| Real WPF + HTTP + SQLite | New M3B acceptance | PASS |

M3A packaged Runtime restart smoke was part of the accepted input baseline; M3B acceptance tests window close/reopen,
and uses the unchanged packaged M3A Runtime with isolated SQLite. It does not claim an additional JVM restart test.

## 4. Scope

Delivered Core contracts, RuntimeClient support, independent WPF management, create/read/edit/archive/restore/delete,
explicit search and filters, pagination, revision conflicts, dirty protection, controlled errors, tests, real acceptance and docs.
M3C/Memory Ask, AI context injection, automatic retrieval/extraction, conversation/RAG, export/restore,
Browser Memory, Finance, agents/cloud sync and React/WebView2 are outside this delivery.

## 5. Desktop Architecture

AssistantWindow adds only a small **Memory…** button. A single owned MemoryWindow uses `ShowDialog()` and the existing RuntimeClient.
Code-behind follows BrowserPairingWindow's lightweight style. An internal confirmation enum/callback makes discard/reload/delete
decisions testable without blocking MessageBox in automated tests. Production uses an owner-bound Yes/No dialog, default No.
No new framework or architecture decision; [ADR-004](../ADR/ADR-004-user-controlled-memory-storage.md) already covers this UI.

## 6. RuntimeClient Memory Contract

| Method | Native request | Valid success |
| --- | --- | --- |
| ListMemoryAsync | GET `/api/v1/memory/items` with escaped query/status/type/page/limit | 200 page |
| GetMemoryAsync | GET `/api/v1/memory/items/{uuid}` | 200 item with matching UUID |
| CreateMemoryAsync | POST collection; type/title/content | 201 item + matching relative Location |
| UpdateMemoryAsync | PUT item; expectedRevision/type/title/content | 200 item with newer revision |
| ArchiveMemoryAsync | POST item/archive; expectedRevision | 200 ARCHIVED item with newer revision |
| RestoreMemoryAsync | POST item/restore; expectedRevision | 200 ACTIVE item with newer revision |
| DeleteMemoryAsync | DELETE item; expectedRevision JSON body | 204, empty body |

Contracts: MemoryType/Status/Source, MemoryItem/Page, MemoryCreateInput/UpdateInput/Query. ToString never contains title/content/query.
Client validates exact enums, nonempty UUID, positive revision, original text/Unicode/NUL/blank/size limits,
ISO timestamp shape/order, page types/bounds/request consistency/item counts/unique IDs/filter agreement.
All Memory object fields are explicitly allowlisted, including errors; duplicate JSON properties fail closed.
Shared stack retains fixed127.0.0.1:8765, no proxy/redirect/cookies, native bearer/no Origin, 8s deadline and 1MiB cap.
Caller cancellation propagates with a safe message and no raw exception cause. No index-rebuild UI is exposed.

Title/query <=160 Unicode code points; content <=2000 UTF-16 units AND <=8192 UTF-8 bytes. No silent truncation.
The UTF-8 guard remains in the contract although it is not independently reachable under the current UTF-16 cap for valid Unicode.
The client mirrors Java strip's blank check while retaining the original text, whitespace and line endings.

## 7. Memory Window

Local Only / User Controlled; Search, Refresh, Active/Archived, All/Preference/Project Note, Previous/Next/page indicator.
The read-only list displays title/type/status/updatedAt/revision only, without content previews. Selecting performs GET.
Editor displays type/title/content plus server ID/revision/status/createdAt/updatedAt. Actions: New/Save/Archive or Restore/Delete/Reload.
Status text contains controlled action/error categories or metadata, never personal text/path/SQL/raw body/stack/token.

## 8. Create / Edit

New clears loaded identity/text and defaults PROJECT_NOTE. Neither New nor editing sends POST/PUT.
Only Save writes. Update uses the loaded expectedRevision; create/update success installs the returned server item and clears dirty.
No client-created authoritative UUID/revision/timestamp, auto increment, auto merge, autosave or automatic mutation retry.
Confirmed saves are retained even when their follow-up list refresh fails; status separates saved result from refresh failure.

## 9. Archive / Restore

Explicit lifecycle button sends expectedRevision. Returned item supplies new status/revision.
Archiving can remove a row from Active without deleting it. Restore is available for Archived.
If local edits exist, lifecycle success updates server metadata while retaining local text/type as unsaved edits.

## 10. Delete

Delete requires an explicit button and affirmative confirmation. Dialog explains future read/search removal,
does not promise forensic disk erase, and states that local edits will also be discarded.
Positive expectedRevision is required; conflicts use the stale UX. Only confirmed 204 clears the editor and refreshes the list.
Refusal sends no DELETE. Failure retains editor state and never force deletes.

## 11. Search / Pagination

Typing query sends nothing; Search/Enter applies it explicitly. Empty query lists normally.
Filters use the last explicitly applied query; changing a filter or submitting new search resets page0.
Refresh/list/filter/page operations preserve the editor and its dirty content.
Desktop uses limit20; Previous/Next use returned total/page/limit. Empty pages beyond the current result range fall back safely.
Delete of the last row on page2 is tested to return to page1. No fetch-all request or use of search results in Ask.

## 12. Dirty State

Dirty compares current type/title/content with the last loaded/returned item, or empty new draft baseline.
Switching entries, New, Reload and closing require a discard decision when dirty.
Refusal keeps input and the loaded selection; successful Save clears dirty.
Reload confirmation explicitly states local edits will be lost. Failed loading retains input.

## 13. Revision Conflict UX

409 MEMORY_REVISION_CONFLICT preserves all local editor text, sets stale, and disables Save/archive/restore/delete.
Direct method calls are guarded as well as buttons. No automatic overwrite/retry or last-write-wins.
Safe Chinese status: Memory changed since loading; reload latest before saving.
Reload requires confirmation when dirty, GETs latest, and clears stale only after a valid response. New/entry switch follow dirty protection.
Both mock STA tests and real Runtime conflict injection prove a newer server version is not overwritten.

## 14. Error Mapping

Memory supplies an explicit endpoint error mapper to the shared transport; the existing AI/security status-code switch is unchanged.

| Status/code | Desktop category |
| --- | --- |
| 400 MEMORY_INVALID | MemoryInvalid |
| 400 / 413 INVALID_REQUEST | InvalidRequest |
| 404 MEMORY_NOT_FOUND | MemoryNotFound |
| 409 MEMORY_REVISION_CONFLICT | MemoryRevisionConflict |
| 409 MEMORY_LIMIT_EXCEEDED | MemoryLimitExceeded |
| 503 MEMORY_STORAGE_UNAVAILABLE | MemoryStorageUnavailable |
| 503 MEMORY_SCHEMA_UNSUPPORTED | MemorySchemaUnsupported |
| 401 | Existing Unauthorized before body parsing |
| 403 POLICY_DENIED / 500 INTERNAL_ERROR | Existing controlled categories |

Unknown/mismatched combinations and unexpected fields are InvalidResponse. Raw error messages are discarded.
Not-found on the loaded item preserves local text while blocking mutations and Reload; user can New or Refresh.
An unsuccessful GET of another entry does not mark the previous loaded entry missing.
Tests prove Memory codes are still rejected on Translate/Summarize/Ask/Browser pairing endpoints.

## 15. Window Lifecycle

One busy operation freezes search/refresh/new/save/lifecycle/delete/reload/filter/page/list and editing; Close remains available.
Window close honors dirty decision, marks closed, cancels HTTP, clears title/content/query/items/metadata/view references, disposes lifetime.
Late read and mutation responses cannot repopulate closed controls. TextBox undo is disabled.
Assistant close-to-tray first closes owned Memory; refusal cancels parent close. ExitAsync similarly honors refusal before shutdown.
Normal exit leaves no owned Memory window or active UI population path. Cancellation cannot undo a request already committed by Runtime.

## 16. Privacy

Memory text is displayed only within the explicitly opened window and sent to the native Memory API.
No logging/telemetry/desktop history/cache/clipboard monitoring, explicit Copy Memory, AI context or provider integration is added.
Tests generate synthetic title/content/query/token and check metadata-only ToString, controlled exceptions/status.
Acceptance emits only fixed check names/booleans; Runtime logs and harness stderr are audited for its unique marker/token.
Temporary auth/data are removed after the owned JVM and SQLite audit connection close. Managed reference clearing is not byte erasure.
SQLite remains local plaintext under OS account/filesystem protections; no new encryption or secure erase claim.

## 17. Tests

**22 new cases**: MemoryClientTests has14 (8 error theory cases + 6 facts); MemoryWindowTests has8 real STA WPF facts.
High-value coverage includes methods/paths/auth/body/revisions, URI encoding, malformed/duplicate/extra fields, size limits,
input Unicode budgets, cancellation/transport privacy, endpoint isolation, explicit Save/search/lifecycle/delete,
dirty decisions, conflict/no retry/reload, not-found, lifecycle preserving edits, paging/filters/empty-page recovery,
busy guarding, late read/mutation close races, and Assistant owned-window close-to-tray behavior.
No automated test opens a blocking real confirmation dialog; the same production decision boundary is injected.

## 18. Real Windows Acceptance

**PASS** via `python scripts/desktop-memory-smoke.py`, using the separate native WPF acceptance executable.
Actual MemoryWindow is shown on an STA dispatcher; button/selection/filter events operate through a real RuntimeClient,
real packaged Runtime HTTP on127.0.0.1:8765, and real M3A SQLite. No Ollama or mocks in this acceptance path.

| Required behavior | Result |
| --- | --- |
| Open isolated empty window | PASS |
| Create synthetic PROJECT_NOTE | PASS |
| Close/reopen retains saved item | PASS |
| Explicit search | PASS |
| Edit/Save advances revision | PASS |
| Archive leaves Active | PASS |
| Archived filter finds item | PASS |
| Restore | PASS |
| External update produces stale conflict | PASS |
| UI retains local text and cannot overwrite newer version | PASS |
| Explicit Reload refusal then approval adopts latest revision/text | PASS |
| Delete confirmation refusal then approval | PASS |
| Delete clears editor/list | PASS |
| Close/reopen and search show absence | PASS |
| SQLite source row count0, log privacy, owned JVM stop and temporary directory removal | PASS |

Only temporary test-owned auth/data directories are used; Windows Credential Manager and normal user Memory are untouched.
Confirmation UI wording is production MessageBox code; automated acceptance drives the injectable decision boundary,
not a manual human click on MessageBox. Actual WPF controls, HTTP and SQLite are exercised.

Reproduce on Windows with free8765, JDK21/.NET10/Python:

```powershell
.\mvnw.cmd clean test
dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-restore --logger "console;verbosity=minimal"
.\mvnw.cmd package -DskipTests
python scripts/desktop-memory-smoke.py
```

The script builds the standalone harness (outside the default solution), refuses an occupied endpoint,
never stops another process and prints no subprocess/raw HTTP diagnostics.

## 19. Existing Regression

Final Java46 PASS with unchanged production/test code. Final Desktop84 PASS includes every original62 case.
Translate/Summarize/Ask, selection/UIA/copy/hotkey/credential/pairing tests continue to pass.
Ordinary Ask remains question-only, chat.balanced, single-turn/no history/no Memory/no tools.
Browser and Finance code are untouched; no new Chrome or Finance acceptance is claimed.

## 20. Files Changed

| File | Purpose |
| --- | --- |
| `desktop/src/PersonalAiWorkspace.Core/Contracts.cs` | Memory error categories and safe Chinese messages |
| `desktop/src/PersonalAiWorkspace.Core/Memory.cs` | Contracts/Unicode and input validation/metadata diagnostics |
| `desktop/src/PersonalAiWorkspace.Core/RuntimeClient.cs` | Optional endpoint-specific error mapping, original default checks retained |
| `desktop/src/PersonalAiWorkspace.Core/RuntimeClient.Memory.cs` | Seven Memory calls and strict parsing |
| `desktop/src/PersonalAiWorkspace.Desktop/MemoryWindow.xaml` | Independent minimal WPF management UI |
| `desktop/src/PersonalAiWorkspace.Desktop/MemoryWindow.xaml.cs` | Explicit operations/dirty/conflict/confirmation/lifecycle |
| `desktop/src/PersonalAiWorkspace.Desktop/AssistantWindow.xaml` | Memory button |
| `desktop/src/PersonalAiWorkspace.Desktop/AssistantWindow.xaml.cs` | Owned single modal and safe close |
| `desktop/src/PersonalAiWorkspace.Desktop/AssistantApp.cs` | Honor Memory dirty decision before exit |
| `desktop/src/PersonalAiWorkspace.Desktop/AssemblyInfo.cs` | Access for standalone acceptance harness |
| `desktop/tests/PersonalAiWorkspace.Desktop.Tests/MemoryClientTests.cs` | Protocol/error/isolation/privacy tests |
| `desktop/tests/PersonalAiWorkspace.Desktop.Tests/MemoryWindowTests.cs` | Real STA WPF behavior and lifecycle tests |
| `desktop/acceptance/PersonalAiWorkspace.MemoryAcceptance/PersonalAiWorkspace.MemoryAcceptance.csproj` | Separate native executable |
| `desktop/acceptance/PersonalAiWorkspace.MemoryAcceptance/Program.cs` | Real UI + HTTP acceptance driver |
| `scripts/desktop-memory-smoke.py` | Isolated Runtime orchestration/log/SQLite/cleanup checks |
| `README.md`, `docs/STATUS.md`, `docs/architecture/current-architecture.md` | Current scope/status/architecture/reproduction |
| This report | M3B evidence and Closing Review handoff |

## 21. Documentation

README/STATUS/current-architecture updated consistently: M3A CLOSED — GO; M3B CLOSED — GO after acceptance;
M3 IN PROGRESS; next step M3B Closing Review. Historical milestone reports remain unchanged.
No ADR-005: existing ADR-004 covers explicit native Memory UI. Ask/export/restore are not reported complete.

## 22. Known Limitations

No forensic erase/managed byte wiping guarantee; OS account plaintext boundary remains.
HTTP cancellation cannot roll back committed mutations; an uncertain network outcome needs explicit read/refresh, not automatic retry.
No optimistic merge, editor backup/history, bulk operations, FTS maintenance button or live query requests.
List/search is explicit; external changes require Refresh/Reload. Acceptance covers this Windows environment and production contracts,
not all desktop DPI/display settings or manual MessageBox interaction. The separate harness is not a product startup path.

## 23. M3C Readiness

Explicit management and server-authoritative revision contracts are ready for separate M3C review.
No memoryIds, Memory selector/prompt/context assembly, retrieval/injection/provider/task changes were introduced.
M3C must be separately authorized; M3 overall stays IN PROGRESS. Export/restore is also unimplemented.

## 24. Recommendation

**GO to M3B Closing Review.** All stated M3B acceptance gates passed; preserve the local feature branch for review.
Do not merge/push/tag/release or begin M3C/Memory Ask/export/restore in this delivery.
