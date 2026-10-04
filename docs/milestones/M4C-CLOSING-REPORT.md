# M4C CLOSING REPORT

Date: 2026-10-04 (Asia/Shanghai)

## 1. Result

**M4C — IMPLEMENTED / LOCAL ACCEPTANCE PASS.**
**M4 FINAL CLOSING CANDIDATE — GO. M4 remains OPEN.**
Architecture / Closing Review must approve final M4 closure. No Main Workspace UI was started.

## 2. Delivery / Reality Gate

**PASS WITH REMOTE VERIFIED EXTERNALLY**, under the user's explicit start-only exception.

Local git fetch at M4C start: **FAILED due to GitHub connectivity** (connection reset/443 failure).
Remote freshness: **VERIFIED EXTERNALLY by Architecture Review**.
Verified remote main: `bf2297e516456a0c0b49c10aea9ff2e6daf2d74f`.
Verified parent: `348c2f1b918bb5cb775c74e54fafecbfe9b17093`.
Local main/HEAD/cached origin/main matched that baseline; branch main and working tree clean. No unknown
local commit or source/schema difference. The exception does not claim a successful local fetch and does
not waive live remote verification before merge/push/formal delivery.

## 3. Git

Local feature branch: `m4c-workspace-backup-restore`, created from the exact published baseline.
Implementation and this report are committed locally. Exact HEAD is supplied in the delivery response/git log.
main/cached origin/main remain at the baseline. No main merge/push, branch push, tag or release.

## 4. Published Baseline

`126061bc116c8d7f2215446eac8151149ae07f1a` → `7bd2261f71131dc6be397954c89a45f78799062b`
→ `348c2f1b918bb5cb775c74e54fafecbfe9b17093` → `bf2297e516456a0c0b49c10aea9ff2e6daf2d74f`.
Baseline M4B: Java94/Desktop123 PASS. Current source was inspected against that commit and Accepted ADRs.

## 5. Files Changed

| Area | Files |
| --- | --- |
| New Runtime | `backup/WorkspaceBackupService.java`, `api/WorkspaceBackupController.java` |
| Shared storage/publication | `memory/MemoryBackup.java`, `MemoryBackupService.java`, `MemoryStore.java`, `PrivateMemoryDirectory.java`, `MemoryConfiguration.java` |
| HTTP/errors | `security/LocalClientFilter.java`, `common/ErrorCode.java`, `ApiError.java`, `api/ApiExceptionHandler.java`, `application.yml` |
| New Desktop Core | `RuntimeClient.WorkspaceBackup.cs`; existing RuntimeClient deadline configuration and Contracts controlled errors |
| WPF | `WorkspaceBackupWindow.xaml/.cs`, AssistantWindow entry/lifetime/test picker injection, AssistantApp cleanup, AssemblyInfo acceptance access |
| Runtime tests | `backup/WorkspaceBackupTest.java`, `memory/WorkspacePublicationTest.java`, new grouped RuntimeApiTest coverage |
| Desktop tests | `WorkspaceBackupTests.cs` |
| Acceptance | `scripts/workspace-backup-smoke.py`, `desktop/acceptance/PersonalAiWorkspace.WorkspaceBackupAcceptance/` |
| Privacy/docs | `.gitignore`, `scripts/privacy-audit.py`, README, STATUS, current-architecture, ADR index/new ADR-007, this report |

Java filenames refer to existing packages below `src/main/java/io/github/qianlixunbai/workspace`.
M4A/M4B Closing Reports and ADR-001..006 were not rewritten. No external repository edits.

## 6. Existing ADR-006 Compatibility

Memory-only format1/schema1, exact nine source fields, canonical digest and endpoint meaning retained.
Memory-only restore constructs fresh DBv1; subsequent Runtime startup migrates to Workspacev3 with empty
Conversation tables. Existing Memory backup tests retain their assertions; real WPF/Ollama Memory recovery PASS.
Shared row validation, FTS verification and publication were extracted/reused, not given different Memory semantics.

## 7. ADR-007 Decision

[ADR-007](../ADR/ADR-007-logical-workspace-backup-restore.md) records the independent Workspace source contract,
canonical digest, resource calculations, streaming API, filesystem publication and limitations.
Status: implemented locally, pending Architecture / Closing Review. It does not supersede Accepted ADR-006.

## 8. Workspace Backup Contract

`personal-ai-workspace.workspace-backup`. Exactly `format,formatVersion,createdAt,contentDigest,memory,conversations`.
Memory section has version/count/items; Conversation section has version/counts/items/turns. Strict source-only
representation; no credentials/models/settings/logs/FTS/Task state. Exact field lists are in ADR-007.

## 9. Version Model

SQLite internal schema3; Workspace format1; Memory section1; Conversation section1. Existing Memory-only
format1/schema1 remains separate. No v3→v4 migration or coupling of logical format to DB version.

## 10. Memory Section

All ACTIVE/ARCHIVED source rows: original UUID/type/title/content/status/revision/MANUAL source/timestamps.
Uses ADR-006 row representation and validation, including Unicode, text/revision bounds and exact UTC milliseconds.
FTS/index/trigger/shadow/rowid data is rebuilt rather than portable source.

## 11. Conversation Section

Original Conversation ID/title/lifecycle/time; Turn ID/parent/sequence/terminal outcome/controlled failure/time;
original USER/ASSISTANT message IDs/roles/content/time; historical ordered selection IDs/revisions/positions.
SUCCEEDED has exactly1 USER+1 ASSISTANT, other outcomes exactly1 USER and no ASSISTANT.

## 12. Task/PENDING Exclusion

No exported taskId field; restored task_id=NULL. Unknown taskId fields reject restore. Any snapshot PENDING
causes409 WORKSPACE_BACKUP_CONFLICT before response bytes; user guidance says wait or Cancel. PENDING restore
rejects. No export auto-cancel, Task reconstruction, startup replay or terminal reclassification.

## 13. Historical Memory Selection

No Memory FK, no current-revision/existence requirement. Round-trip tests include current revision2 versus
historical revision1 and dangling/deleted-reference identities. Position/revision/ID are preserved; no old body
is recreated to satisfy references. New inference still requires explicit current ACTIVE exact-revision selection.

## 14. Canonical Digest

Length-prefixed UTF-8 strings, SHA-256 lowercase hex. Metadata+versions/counts, sorted Memory/Conversation,
Turns by parent/sequence, messages USER then ASSISTANT, selections by position. Null failureCode hashes as
empty string. Property order/whitespace/escaping/array reordering do not change logical digest.
Tests also recompute digests on invalid domain documents to prove integrity is not authenticity or validation.

## 15. Snapshot Consistency

One SQLite BEGIN read transaction for PENDING check, counts, digest and output. Cross-connection tests commit
whole Memory/Conversation generations during exports and observe no mixed generations. No global application
freeze. DELETE-journal read locking can delay writers during large maintenance exports; this limitation is explicit.

## 16. Export Transport

Synchronous servlet streams JSON from cursors, one small record at a time. Desktop uses ResponseHeadersRead,
64KiB chunks and sibling file staging; complete Runtime validation precedes user-file publication. No whole
Workspace JSON byte array, aggregate DOM or LLM token streaming.

## 17. Restore Transport

Raw backup body, no giant envelope copy. Explicit target is strict UTF-8/unpadded base64url header, bounded
8192 UTF-16 units; request header budget64KiB. POST validate and restore bypass ordinary body buffering only
after authentication. Both paths parse bounded streams; restore validates the same held file again before publish.

## 18. Size / Resource Bounds

Existing1000 Conversations x1000 Turns x2 messages; worst escaped message text alone98,304,000,000 bytes.
Conservative complete ceiling **101,393,896,192 bytes**, derived in ADR-007; existing domain capacity unchanged.
Runtime row trees bounded12 fields/4 array children/depth4; parser name64/string16384/number20/nesting12;
Memory at most1000 rows, Conversation order/identity checks disk-backed in staging SQLite.
Desktop64KiB buffers and bounded2h backup deadline; ordinary8s/1MiB response and32KiB mutation limits remain.
~94MiB maximum single-Conversation worst-escaping export/validate and20MiB Desktop chunk transport PASS.
Complete theoretical101GB dataset not materialized; disk availability/time remain operational limits.

## 19. Validation

Whole-document reject for format/section versions, missing/unknown/duplicate keys, malformed Unicode/UUID/time,
NUL, invalid enums/revision/count/sequence/position, duplicate IDs, wrong roles/outcomes/failures, PENDING,
digest damage, truncation/trailing garbage, oversize. Canonical millisecond metadata validates the full SQLite
epoch range in Desktop, including expanded Java Instant years and both signed-64-bit endpoints;
one-millisecond out-of-range metadata rejects. No skip/coercion/partial import.

## 20. Filesystem Security

Reuse existing location/link/account checks. Reject active directory/ancestor/descendant, nonempty/file,
project/.git/build/target/logs/.runtime/auth paths. Existing parent required. Real Windows junction/reparse-point
test PASS; no destination created through the junction. Source bytes unchanged in negative tests.

## 21. Staging Reconstruction

Task-owned sibling with private permissions; supported Workspacev3 DB. Single transaction inserts all source
rows, using deferred parent FKs for array order independence. PK/UNIQUE/FK and foreign_key_check enforce
relationships. Canonical read-back verifies all fields; shared Memory verification rebuilds/checks/searches FTS
and checks schema/quick_check before commit and connection close.

## 22. Publication Strategy

New target: complete directory no-replace rename. Existing empty: identity/creation-time/FileStore recheck,
private ACL and closed complete memory.db no-replace rename while retaining user's directory. Same FileStore
checked for staging/parent and existing target. No cross-volume copy fallback/overwrite/delete-user-directory.
Cross-volume refusal is enforced structurally by sibling staging and FileStore checks, not a fabricated second-volume test.

## 23. Failure Safety

Transaction rollback verified after an insert uniqueness failure; staging/publication failure and both new/empty
target-becomes-nonempty races refuse publication and preserve sentinel/source. Corrupt digest does not publish.
Workspace cleanup failure reports controlled failure/residue rather than success. Only task-owned known SQLite
files/directories are cleaned. No active DB replacement, close, deletion or automatic switch.

## 24. Runtime API

Native-only GET `/api/v1/workspace/backup`, POST `/validate`, POST `/restore` below that prefix.
Safe metadata contains versions/createdAt/counts/digest. 400invalid/unsupported,413oversize,409PENDING or target,
500controlled export/restore failure. Browser denial precedes body/filesystem work. Legacy APIs unchanged.

## 25. Desktop Core

Same RuntimeClient/native credential/loopback/no proxy/redirect/cookies. Streamed download/upload and strict
small metadata/error parsing; shared Runtime validator handles logical contract and digest. The same read-only
file handle prevents Windows write/delete races across preview/restore. No Desktop SQLite or prompt assembly.

## 26. WPF Workspace Backup UX

Small separate Assistant modal, Export and Choose/Validate plus a distinct Restore button. Native dialogs;
metadata-only preview; plaintext/digest warnings, new/empty/no-merge/no-overwrite instructions, explicit start
guidance. Close cancels local work, releases file, clears metadata and ignores late updates. No redesign/navigation.

## 27. Plaintext Privacy

Memory text, titles and USER/ASSISTANT messages are plaintext personal data. UI explicitly says no encryption
or password; protect like personal documents. Digest is no authenticity guarantee. No content/path/backup-body logging.

## 28. Browser Boundary

Translate-only unchanged. New HTTP tests deny native/Browser missing-auth/web-Origin/exact-Origin/originless/
preflight combinations before huge body work; real existing Browser Batch/ownership/Origin/restart/revoke regression PASS.
No local-ai-assistant change or new Chrome GUI acceptance claim.

## 29. Tests

| Command | Result |
| --- | --- |
| `.\mvnw.cmd clean verify` | **105 PASS**,0fail/error/skip; packaged BUILD SUCCESS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS,0warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | **136 PASS**,0fail/skip |
| `git diff --check` / staged check | PASS |

Grouped high-value tests cover snapshot consistency, exact round-trip/lifecycle/continue/immutable messages,
terminal history/task/PENDING rules, corruption/recomputed digest, ordering, large streams, publication/races,
rollback/junction/project rejection, Desktop strict metadata/controlled errors/files/overwrite/preview/close and legacy regression.

## 30. Real Workspace Export

Final `python -X utf8 scripts/workspace-backup-smoke.py` PASS against packaged Runtime and production WPF controls.
Native picker choices injected only for unattended synthetic acceptance; real stream/file IO/HTTP/validation retained.
Dataset:2 Memory (ACTIVE edited revision2, ARCHIVED),2 Conversations (ACTIVE/ARCHIVED),6 Turns,9 messages,2 selections.
All four terminal outcomes represented. Actual Workspace backup file4380 bytes; no body persisted as evidence.

## 31. Real Workspace Restore

Original synthetic Workspace directory removed before recovery. A separate empty maintenance Runtime/WPF
restored only the backup into a new directory; maintenance active DB bytes unchanged. Restored Runtime/Desktop
then launched using that directory. This proves backup-file-only recovery with original source unavailable.

## 32. Logical Equality

SQLite read-back compares every Memory/Conversation/Turn/Message/selection field before and after, with task_id
excluded by contract. Equality checked again after startup. Schema3, quick_check, NULL task_id and FTS PASS.
Internal rowid/index representation is not asserted as portable data.

## 33. Real Restored Conversation Continue

Actual WPF opens restored ACTIVE history and shows terminal states. Explicit new Send asks for a synthetic code
introduced in prior successful history; real Ollama returns the expected marker. A counting local relay proves
zero provider tags/chat calls during restored startup and only the explicit recovered Sends invoke the model.
Archived Send rejects409; unarchive then real new Send succeeds. Old Task GET returns404.

## 34. Real Restored Memory Selection

Restored WPF Memory search and complete explicit selector preview PASS. A new Conversation Turn selects current
restored Memory and real Ollama returns its synthetic marker. Selection clears afterward; no automatic Memory
creation. Historical revision1 and dangling selections remain unchanged while current Memory stays revision2.

## 35. Negative Recovery

Real corrupt/truncated/digest-damaged backup and active/nonempty target fail clearly; active and previously
restored source bytes/rows untouched. Automated invalid documents/path/races supplement these gates.
No partial target/false success/content or target-path logs. FAILED/CANCELLED/TIMED_OUT source fixtures are
controlled seeded histories; real cancel/failure execution is covered by the separate M4B regression.

## 36. Existing Memory Backup Regression

`python -X utf8 scripts/desktop-memory-backup-smoke.py`: PASS, real WPF/HTTP/SQLite/Ollama Memory-only recovery,
negative restore, archived/search/explicit context and no restored Conversation rows. Fresh DBv1 migration
compatibility also PASS in unit tests. Memory UI and contract retained.

## 37. M4A/M4B Regression

`memory-storage-smoke.py`, `conversation-storage-smoke.py`, `conversation-execution-smoke.py`: PASS.
Real M4B multi-turn/context/per-turn Memory/cancel/archive/restart/reopen/continue and controlled failure/no replay
PASS; storage restart/source preservation PASS. Real timeout remains UNVERIFIED; automated timeout tests are primary evidence.

## 38. Final Windows Acceptance

PASS for start, Memory CRUD/search, Conversation/multi-turn/explicit Memory/cancel/failure/restart/archive,
Workspace file export/recovery/start/search/history/continue/no auto Memory/no replay, old Memory-only backup,
Browser Translate-only and stateless ordinary Ask. `real-local-smoke.ps1` and `browser-security-smoke.ps1 -Batch`
both REAL PASS using isolated synthetic data directories.
Current136 Desktop tests include actual isolated WinCred, native hotkey/conflict/release, hidden UIA selection
and helper/clipboard/lifetime regressions. This is current critical Windows regression evidence, not a new manual
Notepad/Chrome user-session claim. Final new recovery uses real Windows/WPF and installed Ollama.

## 39. Privacy / Security Audit

`privacy-audit.py`: PASS;6 actual local native credential files scanned,0 secret/content matches,0 tracked
build/DB artifacts,0 tracked backup artifacts,ignore checks PASS. Source/build/nested archive/log/evidence scans
include new content markers and backup exclusions. Final real acceptance separately scans its temporary logs
against actual ephemeral credential, source markers and target path; PASS before automatic temporary cleanup.
0 real user data enters Git/evidence; user Memory/production WinCred never accessed by acceptance.

## 40. Known Limitations

Plaintext; no encryption/password/cloud/sync/scheduler/incremental/merge/hot swap/forensic erase; OS account/admin
trust boundary; local-filesystem publication only, no universal network/power-loss guarantee. Large validation
requires disk/extra transfer and read snapshots can delay writers. Full101GB theoretical capacity not materialized.
No Finance/Knowledge backup. Client close cannot recall server publication already admitted.

Initial acceptance used the system Java launcher shim, which left a child Runtime; the fixture now resolves the
real JDK executable and owns its PID. The verified test Runtime was stopped. Automatic approval review rejected
the combined stop/recursive cleanup, then rejected nonrecursive cleanup of two later synthetic regression dirs;
stated reason only `blocked by policy`, no further detail. No alternative deletion workaround was attempted.
The following three synthetic-only directories remain in the OS temporary directory:

- `workspace-m4c-smoke-ws_jy7xi`
- `workspace-m4c-native-f944c2ff26274d6c9a1b9763d4aa367a`
- `workspace-m4c-browser-9f343f71b9aa4002ba8e44e3d4af4218`

Final Workspace/Memory recovery runs cleaned their own newly created temporary data normally. Local start fetch
failure/external verification remains explicitly recorded; no current publication freshness claim is made.

## 41. Deferred Beyond M4

React/WebView2 Main Workspace, Knowledge/RAG, Finance integration/reality sync, Agent/Tools/TOOL role,
Browser Conversation, token streaming, edit/regenerate/branching, automatic Memory, encryption/password,
cloud/scheduled/incremental backup and multi-device sync.

## 42. M4 Final Closing Candidate

| Foundation | Evidence | Candidate |
| --- | --- | --- |
| M4A durable Conversation domain | Published baseline + current storage/lifecycle/immutability regression | PASS |
| M4B execution/context | Current tests + real WPF/Ollama multi-turn/Memory/cancel/restart regression | PASS |
| M4C portability/recovery | Current strict backup/restore tests + original-unavailable real recovery/continue | PASS |

**M4 FINAL CLOSING CANDIDATE — GO. M4 — OPEN.** This report does not close M4.

## 43. Architecture Compliance

Accepted ADR-001..006 retained; new contract separate. Runtime owns DB/context/provider/validation; Desktop
owns dialogs/files; Browser Translate-only; Conversation≠Memory≠Task; explicit per-turn Memory; LOCAL_ONLY;
no replay/transient restore/merge/overwrite/auto-switch. Scope exclusions respected; no schema bump or framework.

## 44. Git Status

Local feature commits only; working tree verified clean after final docs commit. No backup/DB/log/build/evidence
tracked. main/cached origin/main unchanged at `bf2297e516456a0c0b49c10aea9ff2e6daf2d74f`; history reports retained.

## 45. Recommended Next Step

STOP implementation for Architecture / Closing Review of this concrete local candidate, including ADR-007.
Before any main merge/push/formal delivery obtain renewed live remote freshness, preferably successful
`git fetch origin`. If still unavailable, wait for Architecture / Delivery Review. Unknown origin/main commit:
**STOP / REMOTE DIVERGENCE REVIEW**; no automatic merge/rebase/cherry-pick/reset/force push.
Only user/Closing Review may approve M4 CLOSED — GO and separately authorize the next Main Workspace milestone.
