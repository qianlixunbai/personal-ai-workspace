# M3C-2 — MEMORY EXPORT / RESTORE REPORT

Date: 2026-10-03 (Asia/Shanghai)

## 1. Result

**M3C-2 — GO. M3 — User-Controlled Memory Foundation — CLOSED — GO.**
Versioned logical export and new/empty-directory restore are implemented and accepted. Real
synthetic Windows recovery and M3 integrated acceptance passed. This is local delivery awaiting
Closing Review, not publication.

## 2. Git

Repository: `qianlixunbai/personal-ai-workspace`. Initial clean `main`, HEAD and fetched
`origin/main` were all `ef4d6e8e17d045883a4be58d45dc2e963749042f` (`feat: add explicit memory ask`).
An initial network failure caused STOP without changes; the user requested a retry, fetch
succeeded, and the exact baseline was rechecked before any implementation.
Branch: `m3c2-memory-export-restore`. Implementation commit:
`9d04b4a0c139ff2ccb9126f89ff8e96061eb46ad` — `feat: add memory export and restore`.
Separate documentation commit: `docs: close M3 user-controlled memory foundation`.
Local commits only; no merge, push, tag or release. Exact final HEAD is available from `git log`.

## 3. Baseline

Before branch creation: `mvnw.cmd clean test` **54 PASS**, Desktop solution test **94 PASS**;
zero failures/skips. M3A, M3B and M3C-1 are the supplied CLOSED — GO official baseline.

## 4. Scope

Only logical backup format/validation, source preservation, FTS reconstruction, native explicit
Desktop UX, failure safety, recovery/regression and M3 closing documentation. No new AI
capability, changes to AI prompts/profiles/Ask behavior, Browser repository, Finance, Conversation,
RAG, embeddings/vector DB, Agent, automatic extraction, sync/cloud/encryption or scheduler.

## 5. Backup Architecture

Desktop owns native file choices and export-file IO. Runtime owns authoritative SQLite
serialization/validation and target construction. `memory_items` alone is authoritative.
GET `/api/v1/memory/backup` returns logical JSON; POST `/api/v1/memory/backup/restore` accepts
strict `{backup,targetDirectory}` and returns versions/count/digest only. Both are native-only.
Desktop never accesses SQLite. Runtime receives no export-file path and exposes no generic
file-write/delete operation. The trusted native restore capability is limited to a fresh directory.

## 6. Format Contract

Exactly `format,formatVersion,schemaVersion,createdAt,itemCount,contentDigest,items`;
format `personal-ai-workspace.memory-backup`, formatVersion 1, schemaVersion 1.
Every item has exactly the existing nine source fields. SHA-256 is required and covers all source
fields and metadata through explicitly specified, length-prefixed UTF-8 canonical binary
serialization, sorted by lowercase UUID string. JSON field order/whitespace/escaping and item
array order do not affect the digest. Full specification: [ADR-006](../ADR/ADR-006-logical-memory-backup-restore.md).

Unknown/missing fields, duplicate keys/IDs, nil/malformed UUID, bad enums/source/revision/times,
reverse timestamps, limits/Unicode/NUL, truncated JSON, wrong format, incompatible versions,
oversized files/documents/envelopes and digest mismatch fail closed before target construction.
Canonical UTC timestamps must preserve SQLite's millisecond precision exactly. No best-effort,
skip-row import, partial acceptance or future-schema guessing.

## 7. Export

One SQLite read transaction reads all ACTIVE and ARCHIVED source rows, ordered by `id ASC`.
There is no paginated mixed-time export. Existing fields remain unchanged. Serialization is
deterministic for a given snapshot/createdAt; each new export has its own creation metadata.
Export contains no FTS/SQLite sidecars/triggers/raw indexes, auth/task/Browser/log/AI/Ask state.
Native SaveFileDialog supplies the destination and overwrite intent. Desktop writes a sibling
temporary file, flushes it, and publishes with overwrite only if approved.

## 8. Restore

Require an absolute, new/empty target with an existing parent; reject current data and its
ancestors/descendants, nonempty or unknown files, credentials/project/build/log paths and
links/reparse points. A private sibling staging directory receives fresh schema v1 and original
source values in one write transaction. No existing target DB is opened or merged.
Validation/commit/connection close happen before publication. The running store is never switched.

## 9. Preservation Semantics

Exact `id,type,title,content,status,revision,source,createdAt,updatedAt` preservation, including
original whitespace/line endings, Unicode, archived lifecycle and nontrivial revisions/timestamps.
No generated UUID, revision reset, timestamp replacement or archived-to-active conversion.
Roundtrip equality is checked inside Runtime and independently against real source/restored
SQLite rows and restored Runtime API responses.

## 10. FTS Rebuild

Rebuild FTS solely from the inserted source. Verify source count/every field, schema v1,
source/FTS count/text/row linkage, FTS integrity-check and actual title search for each record,
plus SQLite `quick_check`. English/Chinese search and ACTIVE/ARCHIVED filtering pass after
starting a brand-new Runtime. No index bytes are imported or compared as backup truth.

## 11. Atomicity / Failure Safety

New target: no-replace same-parent rename of the completed staging directory. Existing empty
target: retain that directory, check FileStore and publish the complete closed `memory.db` with
a no-replace rename. This is a safe single-database publication strategy on the verified Windows
local filesystem; it does not claim portable atomic directory replacement or power-loss durability.
No copy/replace fallback, user-directory deletion or current DB hot replacement.

Recheck target/link/identity state after staging; Windows JDK's absent fileKey is handled with
creation-time checks. Target races and injected pre-publication failure refuse finalization and
clean staging. A mid-insert SQL constraint failure rolls back all rows. Active source rows,
revisions and DB bytes stay unchanged after successful and rejected restores. Cleanup addresses
only task-owned staging and known SQLite filenames; locked filesystem cleanup is best effort.

## 12. Size Bounds

Logical document/file limit **14,948,096 bytes**; restore envelope **15,013,632 bytes**.
Derived from `1000 * (2000 * 6 + 160 * 12 + 1024) + 4096`, plus 64 KiB envelope/path reserve.
Worst-case valid escaping for content/title is budgeted, not an arbitrary copied MiB constant.
Runtime also checks the nested backup's raw UTF-8 token span separately, so envelope reserve
cannot enlarge the backup budget. Oversized bounded/chunked reads reject; no truncation.
Existing ordinary API bodies remain 32 KiB and ordinary/error Desktop responses remain 1 MiB.
The 1000-record escaped fixture roundtrips; a >1 MiB Desktop export succeeds while ordinary
response limits still reject the same oversized response.

## 13. Desktop UX

MemoryWindow **Export / Restore…** opens a small independent MemoryBackupWindow.
**Export Memory…** invokes SaveFileDialog; **Restore Memory Backup…** invokes OpenFileDialog
then OpenFolderDialog. Cancelling any picker produces no export/restore/file read.
The window states exactly:

> Export contains your Memory text in plaintext.
>
> Protect this file like other personal documents.

It also explains NEW data directory, no merge/overwrite and manual Runtime restart after restore.
Success displays versions/count only. Errors use controlled text; server messages/paths/content
are suppressed. Closing cancels local IO/HTTP and late completions cannot write/repopulate UI.
There is no workspace switcher or background export. Exports include saved source records only.

## 14. Security

Native succeeds. Paired Browser, originless Browser, web Origin, missing auth and preflight are
denied for both new endpoints. Existing Browser allowlists/capabilities/CORS are unchanged;
only the exact authenticated native POST restore body gets the larger budget. No remote/web
route, generic filesystem API or Desktop SQLite access. Native bearer retains the existing
same-account trust domain; it is not cryptographic proof of a physical file-picker interaction.

## 15. Privacy

No backup payload, title/content, selected file text, question/answer, token/private path or raw
SQL/exception is written to logs, status, evidence or Git artifacts. Controlled errors have no
driver/parser cause; backup/path diagnostic wrappers are redacted. Real acceptance is synthetic-only.
Windows Credential Manager and user data are untouched. Evidence contains safe check names,
PASS/FAIL, versions, count, digest and file size. Default backup/staging names and `.verification/`
are ignored. Users must protect custom-named plaintext exports themselves.

## 16. Runtime Tests

Final **63 PASS = 54 existing + 9 new**, zero failures/errors/skips.
Eight grouped MemoryBackup tests cover empty/deterministic export, exact new/empty recovery,
strict malformed/version/digest rejection, count/byte bounds, unsafe targets, mid-transaction and
pre-publication failures/races, maximum escaped records and cross-connection snapshot consistency.
One Runtime HTTP contract test covers native success, security, controlled errors/privacy,
endpoint-specific limits and nested-document-size rejection.

## 17. Desktop Tests

Final **106 PASS = 94 existing + 12 new cases**, zero failures/skips.
High-value coverage includes explicit-only behavior, three picker cancellation paths, warnings,
native export/restore bodies, metadata-only status, stable target/invalid/version/restore errors,
local validation before HTTP, close during local read or HTTP, late-result suppression, export
response allowance without changing ordinary limits, strict digest/format validation and bounded
native file IO with no unconfirmed overwrite. Existing management/Memory Ask regressions pass.

## 18. Real Recovery Acceptance

**PASS**, synthetic Windows WPF → packaged Runtime HTTP → SQLite → real local Ollama.
Three records: one ACTIVE PREFERENCE, one ACTIVE PROJECT_NOTE, one ARCHIVED PROJECT_NOTE;
revisions/timestamps are nontrivial. Restart source Runtime before management/Ask/export.
Stop source Runtime, start a separate maintenance Runtime, restore to a new target, then stop
maintenance and start a brand-new Runtime on the target. An additional existing-empty target
is independently restored and compared against the exact original rows.

All nine source fields, ACTIVE/ARCHIVED views, English/Chinese searches, real WPF preview/select,
real explicit Memory Ask and archived Ask rejection pass. Restored synthetic records are deleted;
source/backup/targets/auth/logs and owned Runtime processes are cleaned. No user Runtime is stopped.
The tested three-record plaintext document is 1158 bytes; metadata versions 1/1/count 3.
Safe evidence: ignored `.verification/m3c2-memory-backup-evidence.json`.

Native picker choices are injected for automated acceptance; production uses real OS dialogs.
The harness drives production WPF buttons, native file IO, HTTP and real inference; it does not
claim unattended clicking of OS picker/overwrite dialogs or a second physical machine test.

## 19. Negative Recovery Acceptance

**PASS**: changed backup content with stale digest →400 MEMORY_BACKUP_INVALID and no finalized
target; unsupported formatVersion →400 MEMORY_BACKUP_UNSUPPORTED and no target; nonempty target
→409 MEMORY_RESTORE_TARGET_NOT_EMPTY with its sentinel/contents unchanged. Current maintenance
and source DB bytes remain unchanged. These are real packaged Runtime checks, not only mocks.

## 20. M3 Integrated Acceptance

**PASS**: WPF Save → source Runtime restart → WPF Manage → explicit per-turn selection → real
Memory Ask → WPF Export → new-directory restore → new Runtime on restored target → Manage /
search/filter → explicit Memory Ask on restored ACTIVE context → cleanup and privacy audit.
Source records survive; questions/answers/selections/tasks do not become persisted tables or backups.

## 21. Existing Regression

All original Java54/Desktop94 retained. Runtime API, Memory/Ask, ordinary text capabilities,
provider/profile policy, TaskManager and Browser security tests pass. Desktop management,
Memory Ask, native credential and existing product tests pass. Browser repository unchanged;
no mechanical full Chrome rerun. Finance code, DB, Tool Gateway and `/ai/ask` untouched.

## 22. Files Changed

Runtime: new `memory/MemoryBackup.java`, `memory/MemoryBackupService.java`,
`api/MemoryBackupController.java`; `MemoryStore`, `PrivateMemoryDirectory`, `MemoryConfiguration`,
`LocalClientFilter`, `ErrorCode`, `ApiError`, `ApiExceptionHandler`; new `MemoryBackupTest` and
one added `RuntimeApiTest` contract case.

Desktop: new Core `RuntimeClient.MemoryBackup.cs`, WPF `MemoryBackupWindow.xaml/.cs`,
`MemoryBackupTests.cs`, `MemoryBackupAcceptance/Program.cs/.csproj`; existing `Contracts.cs`,
`RuntimeClient.cs`, `MemoryWindow.xaml/.cs` and `AssemblyInfo.cs`.
Other: `.gitignore`, `scripts/desktop-memory-backup-smoke.py`, README, STATUS, current architecture,
ADR index/new ADR-006, this report and the M3 closing report. No unrelated repository files.
Total: 25 implementation/test/acceptance files and 7 documentation files.

## 23. Documentation / ADR

Updated README startup/backup workflow, STATUS as current truth, architecture and ADR index.
New [ADR-006](../ADR/ADR-006-logical-memory-backup-restore.md) defines lasting format/digest,
ownership, plaintext, reconstruction and maintenance publication boundaries.
New [M3 Closing Report](M3-CLOSING-REPORT.md) states final scope and integrated evidence.

## 24. Known Limitations

Only format v1/source schema v1; canonical millisecond UTC timestamps and lowercase UUIDs.
Plaintext SHA-256 detects damage/edits without digest recomputation, not malicious authorship.
No encryption, cloud/sync/scheduler, merge, automatic switch or migration framework. Local
Windows filesystem recovery was tested; network filesystems, second physical machines, power-loss
directory durability and hostile same-account/admin races are not claimed. Empty target ACL can
be hardened before a failing publication; locked staging cleanup is best effort. Local cancel
cannot undo server restore already admitted; after ambiguous timeout, inspect the chosen target.
These limits are product contract boundaries, not unfinished in-scope acceptance gates.

## 25. M3 Final Status

**M3 CLOSED — GO**. M3A/M3B/M3C-1 are CLOSED — GO; M3C-2 implementation/recovery acceptance GO.
Final capability is explicit save, durable SQLite, management/lifecycle/search, revision concurrency,
explicit per-turn Memory Ask, portable versioned logical export and new/empty-directory restore.
No automatic retrieval. Finance remains frozen pending separate Finance Reality Sync.

## 26. Recommendation

**GO for Closing Review.** Keep the branch local; publication requires the subsequent review.

Reproduce:

```powershell
.\mvnw.cmd clean test
dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-restore --logger "console;verbosity=minimal"
.\mvnw.cmd package -DskipTests
python scripts/desktop-memory-backup-smoke.py
```

The script requires Windows, existing local Ollama/configured model and a free `127.0.0.1:8765`.
It builds its WPF harness, uses temporary source/auth/backup/targets, and emits metadata-only evidence.
