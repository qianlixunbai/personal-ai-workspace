# M3A — MEMORY STORAGE FOUNDATION REPORT

Date: 2026-10-03 (Asia/Shanghai)

## 1. Result

**M3A — Memory Storage Foundation: CLOSED — GO (implementation acceptance).**
**M3 — User-Controlled Memory Foundation: IN PROGRESS.** Local feature branch awaits Closing Review;
there is no merge, push, tag or release. All M3A GO gates below passed with synthetic data.

## 2. Git

Requested reality check performed before any edit: clean `main`, HEAD and fetched `origin/main` both
`1f987402533bf108aa18f0eed4e90de6973c7060`. Inspected 12 commits. Created
`m3a-memory-storage-foundation`; all implementation is on this branch. Local delivery commit subject:
`feat: add durable memory storage foundation`. Exact delivery commit is reported in the chat/Git log;
the report deliberately avoids a self-referential commit hash.

## 3. Baseline

Executed `.\mvnw.cmd clean test`: **37 PASS**, 0 failed/errors/skipped.
Executed `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-restore --logger "console;verbosity=minimal"`:
**62 PASS**, 0 failed/skipped. Java 21.0.7, existing Spring Boot 4.1.1 retained.

## 4. Scope

Delivered durable SQLite, schema/version foundation, domain model, CRUD/lifecycle, revision concurrency,
list/pagination, substring search, actual FTS5/trigram, derived-index rebuild, native Runtime API,
private storage boundary, automated acceptance and documentation.

No Desktop UI/API calls, Memory Ask/context injection, export/restore, history, Conversation, RAG,
embeddings, vector DB, Browser Memory capability, automatic Memory, Agent, cloud/sync or Finance work.

## 5. Architecture

`MemoryController → MemoryStore → private memory.db`. Source table `memory_items` owns truth;
FTS and its shadow tables are derived. A small synchronized JDBC store owns one connection and closes
with the Spring bean lifecycle. No JPA, datasource pool or heavyweight persistence architecture.
Separate `MemoryConfiguration` binds `workspace.data-directory` without changing existing AI profile contracts.

## 6. SQLite / Schema

Pinned `org.xerial:sqlite-jdbc:3.53.2.0`, bundling SQLite 3.53.2.
Actual Windows driver created and exercised FTS5/trigram, not an assumed compile option.
Each connection sets/verifies `foreign_keys=ON`, sets `busy_timeout=3000` ms, and startup verifies
`journal_mode=DELETE`. Correctness-oriented rollback journal; no WAL performance tuning.

Startup checks `quick_check`, then performs supported initialization/index repair in one transaction:
empty v0 → source/index/triggers/current `user_version=1`. Nonempty v0 is unsupported.
v1 source must exist; rebuild FTS from source. Newer versions return `MEMORY_SCHEMA_UNSUPPORTED`;
corruption or failed storage/index initialization returns `MEMORY_STORAGE_UNAVAILABLE` and refuses startup.
No silent deletion/reset. Tests preserve newer DB bytes, corrupt bytes, source and failed-rebuild sentinel data.
Only empty-v0 initialization is supported now; future source migrations must extend this version gate transactionally.

## 7. Memory Model

`id` UUID; `type` PREFERENCE / PROJECT_NOTE; original `title` and `content`; `status` ACTIVE / ARCHIVED;
positive integer `revision`; `source` MANUAL; `createdAt` / `updatedAt` UTC instants.
SQLite timestamps are integer epoch milliseconds, avoiding variable-width string ordering.
Caller cannot set id/status/source/revision/timestamps on create. No AUTO/MODEL/IMPORT/FINANCE type/source API.

Limits are centralized in `MemoryLimits`: 1000 total ACTIVE + ARCHIVED; title 160 Unicode code points;
content 2000 UTF-16 code units AND 8192 UTF-8 bytes; query 160 code points; page default 20 / max 100.
Both content counts are computed. For valid Unicode at <=2000 UTF-16 units, UTF-8 is <=6000 bytes, so
the 8KiB guard is intentionally redundant today. Tests cover maximum multibyte content and rejection
of UTF-8-over-budget content (which necessarily also exceeds the UTF-16 cap).
Blank after `strip`, NUL and malformed Unicode are rejected. Original whitespace and line endings are preserved.
No truncation, compression or eviction.

## 8. CRUD / Lifecycle

Create → read → update (including type) → archive → restore → delete implemented.
Update is allowed on archived records without implicitly restoring them. Successful repeated archive/restore
commands also increment revision. Default list/search excludes archived; explicit `status=ARCHIVED` includes only archived.
Delete atomically removes source/index and future reads return 404; deleted entries cannot appear in search.

## 9. Revision Contract

Create = 1. Update/archive/restore = previous + 1. All four mutation forms, including DELETE, require
positive `expectedRevision` in JSON body. Compare and mutation occur under the same SQLite write lock;
stale existing item → HTTP 409 `MEMORY_REVISION_CONFLICT`; missing item → 404 `MEMORY_NOT_FOUND`.
DELETE consumes the matching revision and removes the row; no deleted revision/history is retained.
Source timestamp/revision remains unchanged after any failed write or index-trigger failure.

## 10. Search / FTS

Literal case-sensitive substring search in title + content, for English, Chinese and valid Unicode.
Queries >=3 code points use FTS5 `trigram case_sensitive 1` MATCH with a double-quoted escaped literal phrase;
short queries use bound `instr` predicates. No caller FTS operators, SQL interpolation or LIKE wildcard behavior.
Queries are not trimmed or normalized; omitted/empty query lists records.
Status/type filters, count and page apply equally to FTS and short scans.
Order: `updated_at DESC, id ASC`, zero-based page; limit 1–100 (default 20).
Each list count/page uses one read transaction. Separate page requests may shift under intervening edits.

Startup and `POST /api/v1/memory/index/rebuild` atomically recreate FTS/triggers from source. Rebuild does not
change source values, lifecycle, timestamps or revisions. Tests actually drop the index and recover it.
Edit removes old search text, delete removes results, archive filter is explicit. Source/index mutations use triggers
in the same transaction. Unsupported FTS/trigram fails startup instead of silently switching search semantics.

## 11. Data Directory / Permissions

Config: `workspace.data-directory`, also configurable with `WORKSPACE_DATA_DIRECTORY` or command-line option.
Default: `${user.home}/.personal-ai-workspace/data/memory.db` on the local machine, outside source/build/logs/auth files.
Configured directory must be outside project and credential directories (and not the working directory itself); forbidden
build/target/logs/.git/.runtime path segments, symlink/reparse locations, wrong owners and unavailable private
permissions fail closed. A dedicated data directory is required because Runtime restricts its permissions.

Startup creates/verifies current-account ownership and restricts/verifies Windows owner-only ACL with file/subdirectory
inheritance, or POSIX directory 0700/file 0600. The DB is created privately before JDBC writes.
Existing DB and SQLite `-wal`, `-shm`, `-journal` sidecars are validated/restricted; all are sensitive personal data.
Current journal mode is DELETE; sidecars remain protected by the private directory boundary.
Windows owner-only ACL was exercised and inspected by tests. POSIX implementation is unverified on a real POSIX host.

**SQLite currently stores local plaintext data protected by OS account/filesystem boundary.**
This is not encryption; same-account malicious processes/administrators are outside the confidentiality boundary.

## 12. API

All routes require native bearer credential and no web Origin.

| Method / path | Body / behavior |
| --- | --- |
| POST `/api/v1/memory/items` | `{type,title,content}` → 201 item + Location |
| GET `/api/v1/memory/items/{id}` | → 200 item / 404 |
| GET `/api/v1/memory/items` | `status,type,query,page,limit` → `{items,total,page,limit}` |
| PUT `/api/v1/memory/items/{id}` | `{expectedRevision,type,title,content}` → updated item |
| POST `/api/v1/memory/items/{id}/archive` | `{expectedRevision}` → archived item |
| POST `/api/v1/memory/items/{id}/restore` | `{expectedRevision}` → active item |
| DELETE `/api/v1/memory/items/{id}` | `{expectedRevision}` → 204 |
| POST `/api/v1/memory/index/rebuild` | No payload required → 204 |

Responses remain plain JSON with `Cache-Control: no-store`; text is data, never HTML execution.
Invalid Memory values → 400 `MEMORY_INVALID`; size/capacity → 409 `MEMORY_LIMIT_EXCEEDED`;
storage/schema failure → 503 respective Memory code. Malformed JSON, unknown enum/field/UUID and binding failures
retain existing 400 `INVALID_REQUEST` HTTP contract. Oversized raw body → 413 `INVALID_REQUEST` before deserialization.

## 13. Security Boundary

Existing Browser Translate-only allowlists/capabilities unchanged. Actual paired Browser credentials were tested:
GET/create/update/PATCH/archive/restore/delete/rebuild denied with Origin; originless Browser requests denied;
Memory OPTIONS preflight denied without capability/CORS expansion. Web Origin and missing bearer denied.
Controller independently checks native identity. Request-body cap now protects POST/PUT/PATCH/DELETE,
including unknown Content-Length streaming bodies, before any controller/storage mutation.
No Browser repo, Desktop product code, Finance repository/DB/Gateway or Finance `/ai/ask` accessed/modified.

## 14. Privacy

No Memory title/content/body/query logs. Item/save/update diagnostics contain only metadata.
Controlled exceptions drop driver causes and return stable messages/codes: no paths/SQL/content/stack trace/DB bytes.
Runtime HTTP captured-output assertions cover actual synthetic title/content/query and credentials.
Packaged smoke checks all three Runtime logs for its unique synthetic marker, title/content/query and token.
Repository/build/archive/log/evidence audit PASS with zero matches and zero tracked build artifacts.
Scope is Runtime/default diagnostics and repository verification artifacts, not a system-wide disk audit or forensic erasure.

## 15. Transaction / Failure Semantics

Writes use `BEGIN IMMEDIATE` before capacity and revision checks. Bound count/insert cannot race across Runtime
store instances; revision compare/update/delete cannot lose a write. SQLite foreign keys and busy timeout are connection-local.
Triggers write index in the source transaction; injected FTS-shadow insertion failure rolls back even after the old
index row was removed. Failed create leaves no half item. Busy errors are controlled and leave original revision intact.
Count/page is a consistent read snapshot. SQL/I/O errors never leak driver diagnostics; rollback failure closes the store.
Schema/index rebuild failures preserve authoritative source and roll back DDL; source is never reset to recover storage.

## 16. Tests

Minimal High-Value Testing: **9 new tests**, not a mechanical per-field/per-endpoint count expansion.

| Suite | Coverage | Result |
| --- | --- | --- |
| `MemoryStoreTest` (7) | Real file create/close/reopen/read/delete/reopen; CRUD/lifecycle/stale revisions; multilingual/literal search; pagination tie-breaker; drop/rebuild; size/capacity; injected source/index rollback; busy; newer/unversioned/corrupt schema preservation; two-store competing revision/capacity; private paths/Windows ACL | PASS |
| `RuntimeApiTest` additions (2) | Real native HTTP CRUD/search/rebuild/errors/privacy, actual paired Browser denial across methods/origins/preflight, missing/web auth denial, unknown-length body caps for all mutation forms, no AI calls | PASS |

Persistence tests use actual SQLite file DBs in isolated OS temp directories, never mock DAOs or user data directories.
The Runtime API test context is explicitly configured to a unique temp Memory directory.
The initial JSON-order-dependent missing-revision fixture was fixed before the final clean run.

## 17. Real Restart Smoke

Executed `python scripts/memory-storage-smoke.py` against the repackaged local Runtime JAR: **PASS**.
Three distinct actual JVM processes; termination is awaited before replacement, avoiding the Oracle javapath launcher shim.
Temporary credentials and Memory data are separate, synthetic-only and cleaned after the run. Provider points to an
unavailable loopback port; no Ollama/real inference required or attempted.
Create → Runtime exit → new Runtime → exact item read → Chinese/short search → update → stale revision rejection
→ archive/default-hidden/archived-search → rebuild/source-equal → restore → delete/read+search absent
→ second complete restart → still absent. Schema v1, DELETE journal and empty source/index verified.
24 acceptance checks, safe evidence in ignored `.verification/m3a-memory-smoke-evidence.json`, no personal body/credentials.
Two preliminary launcher-managed runs were not accepted as restart evidence; their owned orphan JVMs/temp files were cleaned.

## 18. Existing Regression

Final `.\mvnw.cmd --batch-mode --no-transfer-progress clean test package`: **46 PASS**
(37 existing + 9 new), 0 failures/errors/skipped; packaged JAR build PASS.
Final Desktop command unchanged: **62 PASS**, 0 failures/skipped; no Desktop source modifications.
Browser repo unchanged, so Chrome/MV3 acceptance not rerun. Ordinary Translate/Summarize/Ask tests PASS;
AskPrompt/AskRequest/provider prompt semantics unchanged: single-turn, no history/memory/tools.

## 19. Files Changed

| Files | Purpose |
| --- | --- |
| `pom.xml`, `.gitignore`, `src/main/resources/application.yml` | SQLite dependency, sensitive DB ignores, private directory config |
| `memory/MemoryItem.java`, `MemoryLimits.java` | Domain enums/model, safe diagnostics, centralized limits/validation |
| `memory/PrivateMemoryDirectory.java`, `MemoryConfiguration.java` | Account/filesystem isolation, Spring lifecycle |
| `memory/MemoryStore.java` | SQLite/schema/transactions/revisions/FTS/search/rebuild |
| `api/MemoryController.java` | Native Memory HTTP contract |
| `common/ErrorCode.java`, `ApiError.java`, `api/ApiExceptionHandler.java` | Controlled Memory error mapping |
| `security/LocalClientFilter.java` | Existing body protection extended to PUT/PATCH/DELETE |
| `memory/MemoryStoreTest.java`, `api/RuntimeApiTest.java` (test source root) | Real SQLite and HTTP acceptance; isolated temp data |
| `scripts/memory-storage-smoke.py`, `scripts/privacy-audit.py` | Real packaged restart acceptance; Memory privacy markers |
| `README.md`, `docs/STATUS.md`, `docs/architecture/current-architecture.md` | Current use/status/architecture |
| `docs/ADR/ADR-004-user-controlled-memory-storage.md`, `docs/ADR/README.md`, this report | Durable adopted decisions and evidence |

Java paths in this table are beneath `src/main/java/io/github/qianlixunbai/workspace/` unless noted as test files.
No credential, Memory DB, synthetic smoke body, log or build artifact enters Git.

## 20. Documentation

Added this report and Accepted ADR-004; updated STATUS, current architecture, README usage and ADR index.
Current docs distinguish M3A complete from overall M3 IN PROGRESS, and keep historical M1/M2 acceptance as history.
Dependency/search references: [Xerial pinned release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.2.0),
[SQLite FTS5/trigram](https://www.sqlite.org/fts5.html#the_trigram_tokenizer).

## 21. Known Limitations

- Local plaintext, no encryption/forensic erase/revision history. Deleted source/index is inaccessible through future API queries,
  but old bytes can survive in SQLite free pages, backup copies or OS snapshots.
- Windows acceptance only; real Linux/macOS ACL/permission and packaged restart smoke not run.
- Current source schema is v1; only empty-v0 initialization is supported. No prior persisted Memory format exists to migrate.
- Search is literal and case-sensitive, without linguistic normalization, ranking, stemming or semantic retrieval.
- Offset pages have stable ordering per request but may shift between requests with concurrent edits.
- A single store serializes requests; cross-process SQLite contention can return controlled unavailable after 3 seconds.
- Storage/schema startup failure intentionally refuses Runtime startup; no silent replacement or UI recovery workflow.
- No Desktop Memory UI, Memory Ask, export/restore or automatic capture.

## 22. M3B Readiness

Native API now supports explicit save/read/search/edit/archive/restore/delete with private durable storage,
bounded pages and revision conflicts. M3B can build user-controlled Desktop management UI after separate authorization.
No Desktop code has been preemptively added. M3C export/restore must use versioned logical source records and rebuilt FTS.

## 23. Recommendation

**GO for M3A Closing Review.** Production foundation, real SQLite restart persistence, CRUD/lifecycle/revision,
search/rebuild, Browser denial, privacy checks, all Java regression and Desktop baseline passed.
No Finance/Browser/product scope expansion. Overall M3 remains IN PROGRESS.
Do not merge/push/tag/release or start M3B/Memory Ask/export/restore before the requested next-stage review.
