# M4A CLOSING REPORT

Date: 2026-10-04 (Asia/Shanghai)

## 1. Result

**M4A — Conversation Domain & Persistence：CLOSED — GO**。
**M4 — User-Controlled Conversation Foundation：OPEN**。
Only M4A implemented; M4B, M4C and Main Workspace UI were not started.

## 2. Reality Check

Repository `qianlixunbai/personal-ai-workspace`; origin URL matches.
Initial main/HEAD/cached origin/main were `dd069ec5ec4e053a85e8f2f6de6940940cf9f83e`.
Two untracked launchers triggered the requested STOP before development.
User identified them as existing one-click startup work and approved proceeding with the proposed separate commit.
Fetch succeeded and remote main still matched the confirmed baseline; no unexplained remote changes/divergence.
After launcher commit, working tree was clean and local main was safely one known commit ahead of origin/main.
README/STATUS/current architecture, all six Accepted ADRs, Runtime packages/Memory/storage/backup/auth/Ask/TaskManager,
Desktop Core/WPF/tests and existing acceptance scripts were inspected. No intended-behavior conflict required superseding an ADR.
Historical M3 closing SHAs in documents were distinguished from current published status-sync SHA.
No AGENTS.md was found in the repository file search.

## 3. Git

- Confirmed published baseline: `dd069ec5ec4e053a85e8f2f6de6940940cf9f83e`.
- User-authorized launcher commit on local main: `c90359f425c13cc272c80dd57442ce5cb95ee101` — `chore: add one-click workspace launcher`.
- New branch from that clean local main: `m4a-conversation-domain`.
- Implementation: `03591f9fe74f3a3db18ca062ae168f21cb668a49` — `feat: add durable conversation domain`.
- This report/README/STATUS/architecture follow in a separate local documentation closing commit.
- No merge, rebase, reset, force push, push, tag or release. Other repositories untouched.

## 4. Baseline

Before M4A source edits, Runtime verify ran **63 PASS**, Desktop existing no-build regression **106 PASS**.
Initial Desktop build was blocked by the already running Assistant; initial clean Runtime build was blocked by its running JAR.
User explicitly authorized closing Assistant as needed; the Assistant and this repository's Runtime were stopped for verification.
Ollama was retained. No acceptance run used the user's Memory or Windows Credential Manager.

## 5. Files Changed

M4A implementation: 25 files; closing documents: 4 files. The separately approved launcher commit adds 2 files.

| Group | Files / purpose |
| --- | --- |
| Java domain | `conversation/Conversation.java`, `ConversationLimits.java`, `ConversationStore.java`, `ConversationConfiguration.java` |
| SQLite versioning | `persistence/WorkspaceSchema.java`; existing `memory/MemoryStore.java`, `MemoryBackupService.java` |
| API/errors | `api/ConversationController.java`, `ApiExceptionHandler.java`; `common/ApiError.java`, `ErrorCode.java` |
| Runtime tests | `conversation/ConversationStoreTest.java`; `memory/ConversationMigrationTest.java`, `ConversationSmokeFixture.java`; `api/RuntimeApiTest.java`; existing `MemoryStoreTest.java`, `MemoryBackupTest.java` |
| Desktop Core | `Conversation.cs`, `RuntimeClient.Conversation.cs`, `Contracts.cs` |
| Desktop tests | `ConversationClientTests.cs` |
| Acceptance/privacy | `scripts/conversation-storage-smoke.py`; existing `memory-storage-smoke.py`, `desktop-memory-backup-smoke.py`, `privacy-audit.py` |
| Docs | `README.md`, `docs/STATUS.md`, `docs/architecture/current-architecture.md`, this report |

Java paths are relative to `src/main/java/io/github/qianlixunbai/workspace/` or matching `src/test/java/`.
Desktop source paths are relative to `desktop/src/PersonalAiWorkspace.Core/`; tests to `desktop/tests/PersonalAiWorkspace.Desktop.Tests/`.

## 6. Conversation Domain

Independent durable domain. A Conversation is user-owned stored history, not Memory and not transient task retention.
No TaskManager/provider/model integration or Memory business dependency in ConversationStore.
Existing validated Workspace data location initializes before the independent Conversation connection opens.

## 7. Data Model

`conversations`: UUID id, title, ACTIVE/ARCHIVED status, created_at/updated_at.
`conversation_turns`: UUID id, conversation_id FK, positive sequence, outcome status, timestamps; UNIQUE(conversation_id,sequence).
`conversation_messages`: UUID id, turn_id FK, USER/ASSISTANT role, original content, timestamp; UNIQUE(turn_id,role).
Each service-created Turn has exactly one USER and optionally one ASSISTANT; only SUCCEEDED has ASSISTANT.
Stable IDs, explicit timestamps/roles/sequence/outcomes support future logical export without implicit row ordering.
No branch/parent/generation/variant graph fields or generic personal-item entity.

## 8. SQLite Migration

Reuse existing Xerial SQLite JDBC and private `memory.db`; no additional database technology, ORM or migration framework.
`PRAGMA user_version` now represents Workspace DB version 2; Memory source/backup version remains 1.
Empty v0 initializes existing Memory v1 then adds Conversation schema in the same BEGIN IMMEDIATE transaction.
Existing M3 v1 upgrades transactionally to v2 without rewriting Memory source/revisions/timestamps.
Migration failure rolls back new schema and preserves Memory and version; unknown newer versions fail closed.
Memory's derived FTS rebuild still uses its existing transaction/trigger mechanism.
ADR-006 restore explicitly constructs fresh Memory-only schema v1, validates/rebuilds/publishes as before.
Starting a new Runtime on restored Memory migrates to v2 and creates empty Conversation tables.
No backup JSON contract/digest/budget/version change. Old v1 Runtime cannot open an upgraded v2 DB; no downgrade is implemented.

## 9. Lifecycle

Create ACTIVE; default title `New conversation`; manual rename only.
Archive/unarchive explicitly switch ACTIVE/ARCHIVED; repeating a lifecycle command keeps the requested state.
Archived Conversation rejects new Turns. Existing PENDING can be completed; execution/cancellation policy is deferred to M4B.
Physical DELETE transactionally cascades through Turns and Messages. Delete failure rolls back the whole aggregate.
No tombstone, recycle bin, remote deletion marker, sync, favorite, pinned, folder or tag.

## 10. Turn / Message Semantics

Internal Java operations: `createTurnWithUserMessage`, `completeTurnWithAssistantMessage`, `markTurnTerminated`.
PENDING → SUCCEEDED/FAILED/CANCELLED/TIMED_OUT; terminal state cannot be overwritten or completed twice.
Assistant insert, outcome update and parent timestamp update commit together; failure rolls back.
Failures/cancellation/timeouts have no fabricated error-text Assistant message.
Only USER/ASSISTANT roles exist; schema CHECK rejects SYSTEM/other roles. Runtime system prompt and Memory are not messages.
Matching conversationId + turnId is required; cross-Conversation completion fails with controlled missing error.
No client HTTP route writes a Turn or Assistant message.

## 11. Ordering

Per-Conversation sequence starts at 1 and is allocated while holding BEGIN IMMEDIATE before reading max(sequence).
SQLite write lock plus UNIQUE(conversation_id,sequence) prevents races across independent connections/processes.
Detail uses sequence ASC; metadata list uses updated_at DESC/id ASC. Repository recreation and process restart preserve order.
Each list/detail response reads one transaction snapshot. Separate page requests do not promise a shared snapshot.

## 12. Immutability

Message content has no update service or HTTP endpoint. SQLite BEFORE UPDATE trigger rejects mutation.
Java records/immutable lists and Core read-only DTO lists avoid mutable returned history.
No edit/regenerate/alternate response/branching endpoints or fields.
Privileged direct external DB manipulation remains outside the existing same-account/admin threat model.

## 13. Runtime API

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/conversations` | `{}` default title or explicit title; 201 metadata + Location |
| GET | `/api/v1/conversations` | ACTIVE default or ARCHIVED; metadata page |
| GET | `/api/v1/conversations/{id}` | metadata + ordered turn page |
| PATCH | `/api/v1/conversations/{id}` | rename only |
| POST | `/api/v1/conversations/{id}/archive` | explicit archive |
| POST | `/api/v1/conversations/{id}/unarchive` | explicit unarchive |
| DELETE | `/api/v1/conversations/{id}` | 204 physical cascade deletion |

Native bearer only, no web Origin; existing filter and controller native identity checks.
Canonical non-nil lowercase UUIDs, enums/unknown fields/duplicate keys and page/limit validated.
Title: nonblank, trim, raw input <=160 Unicode code points. Content: nonblank, valid Unicode/no NUL,
<=8192 UTF-16 units AND <=8192 UTF-8 bytes; preserve originals, reject rather than truncate.
1000 total Conversations, 1000 Turns per Conversation; page default/max10, minimum1.
Worst detail JSON budget: `10 * (2 * 8192 * 6 + 2048) + 4096 = 1,007,616 bytes < 1MiB`.
Actual worst-escaping HTTP response was tested below existing Desktop 1MiB cap.
No credentials, model/provider/task internals in Conversation contract. Ordinary request body cap remains32KiB.

## 14. Desktop Changes

Core DTOs, strict parsing and RuntimeClient create/list/get/rename/archive/unarchive/delete methods only.
Reuse native credentials, fixed loopback/proxy/redirect restrictions, 8s deadline, 1MiB cap,
duplicate rejection, field allowlists and safe error classification. No independent HTTP stack/history cache.
WPF UI unchanged; no Conversation window/debug UI, chat bubbles/sidebar/Markdown or React/WebView2.
Ordinary Translate/Summarize/Ask and Memory DTO/client/selection/UI behavior remain unchanged.

## 15. Memory Boundary

Conversation ≠ Memory. No automatic extraction/save/search/retrieval/selection/injection.
Existing explicit per-turn Memory Ask preserves exact-revision admission snapshot and existing budgets.
Ordinary Ask stays stateless; M4A does not persist its tasks/questions/answers or add conversationId.
Memory CRUD/search/lifecycle/export/restore and real WPF/Ollama regression passed.
Memory export contains only Memory source records, never Conversation. It is not a Conversation backup.

## 16. Browser Capability Boundary

Browser remains Translate-only; auth/filter/credential/route allowlists were not widened.
Paired and originless Browser Conversation access, mutation and preflight denied in HTTP tests.
Browser has no Conversation list/history/archive/delete, Ask or Memory capability.
Real local Batch Translate/security/restart/revoke regression passed using synthetic HTTP clients.
Chrome GUI/MV3 acceptance was not rerun in M4A; historical M2 acceptance is not relabeled as current.
`local-ai-assistant` was not modified.

## 17. Privacy

Domain/request DTO ToString is redacted or metadata-only, including all message content/title-bearing types.
No logger in the new domain/client; error handlers discard causes. Captured HTTP logs and restart/acceptance logs were checked.
Verification evidence contains only metadata/IDs/status/counts/checks; no complete message bodies or credentials.
All new persistence fixtures and real acceptance data are synthetic and isolated; temporary personal-data fixtures cleaned.
No user Memory/WinCred was inspected; logical Memory export remains explicit plaintext, DB protection remains OS permissions.

## 18. Security

Shared existing private data directory/DB/sidecar ACL boundary; no additional credential/capability/cross-Origin grant.
Parameterized SQL handles all user values; FK, role/status CHECKs and unique sequence/role constraints preserve associations.
Tracked-secret, actual-local-credential, source/build/archive/log/verification and DB-tracking scans passed with0matches.
No tracked DB/build/data/log artifact. Ignore checks cover memory.db and WAL/SHM/journal.
Privacy scanner now also inspects `.runtime` logs, Conversation private markers and tracked database extensions.
Evidence: ignored `.verification/m4a-privacy-audit-evidence.json` and existing Browser batch audit evidence.

## 19. Error Contract

Reuse `code/message/phase`, no separate envelope.
400 CONVERSATION_INVALID; 404 CONVERSATION_NOT_FOUND; 409 CONVERSATION_CONFLICT/CONVERSATION_LIMIT_EXCEEDED;
503 CONVERSATION_STORAGE_UNAVAILABLE. Binding/JSON retains INVALID_REQUEST; auth/policy retains401/403.
No SQL/path/constraint name/stacktrace/raw cause/body leaks to clients; Core validates status/code before safe classification.
Shared schema initialization retains existing controlled MEMORY_SCHEMA_UNSUPPORTED/MEMORY_STORAGE_UNAVAILABLE startup categories.

## 20. Tests

| Verification | Final result |
| --- | --- |
| `.\mvnw.cmd clean verify` | **75 PASS**, 0fail/error/skip; packaged build success |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS; 0warning/error |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | **111 PASS**, 0fail/skip |
| `git diff --check` / cached diff check | PASS |

12 added Runtime tests:8 integrated store cases +2 migration cases +2 HTTP cases.
5 added Desktop cases cover CRUD/list/detail, statuses, strict malformed/ownership/role/order checks,
classified/safe errors and pre-HTTP validation/Location rejection.
Existing63 Runtime/106 Desktop regressions retained; old Memory backup tests use explicit v1 maintenance construction,
and Memory restart smoke correctly checks Workspace DBv2. Assertions keep backup format/schema1 and actual fresh restorev1.
Standard existing Java deprecation/Mockito instrumentation diagnostics remain; no product behavior changed for those warnings.

## 21. Real Persistence Smoke

`python scripts/conversation-storage-smoke.py` **PASS** against the packaged Runtime.
Uses actual M3 schema v1 input prepared by a test-only Java fixture, synthetic Memory, private temporary auth/data and ephemeral port.
Creates/renames/lists over HTTP, stops Runtime, persists USER/ASSISTANT/terminal Turn via internal Java domain primitive,
then checks exact title/lifecycle/count/sequence/roles/content after new process startup.
Archive → third Runtime → verify archived; unarchive/delete → fourth Runtime →404 and zero child records.
Memory source fields remain exact; search and logical backup v1 regression pass; quick_check/log privacy/cleanup pass.
Internal fixture is test-classes only and not in product JAR; no testing HTTP endpoint exists.
Evidence: ignored `.verification/m4a-conversation-smoke-evidence.json`; safe IDs/status/counts only.
This is **durability / persistence acceptance, not backup / restore acceptance**.

## 22. Regression

- `python scripts/memory-storage-smoke.py`: **PASS**, real packaged three-process CRUD/search/lifecycle/restart/delete regression.
- `python scripts/desktop-memory-backup-smoke.py`: **PASS**, real Windows WPF/HTTP/SQLite/Ollama integrated M3 export/restore,
  source/revisions/search/exact selection/context response, fresh v1 recovery, Runtime upgrade and cleanup/negative recovery/privacy.
- Existing `scripts/real-local-smoke.ps1`: **REAL PASS** for Translate/Summarize/single-turn Ask, run with isolated temporary WORKSPACE_DATA_DIRECTORY.
- Existing `scripts/browser-security-smoke.ps1 -Batch`: **REAL PASS** for synthetic Browser Batch Translate, one inference,
  readiness/ownership/originless access/capability denial/restart/revoke and secret audit, using isolated temporary data.
- Whole source/build/archive/log/evidence privacy audit **PASS**. No Finance/Browser-repository changes.

## 23. Known Limitations

No Conversation execution/UI/export/restore/portability. Stored PENDING is not automatically reconciled after restart in M4A.
No message edit/regenerate/branch/retry orchestration. Native rename/lifecycle use transaction commit order, not revision conflicts.
Page requests have no cross-request snapshot; fixed capacities reject overflow rather than evict.
Workspace DBv2 is not readable by old M3 Runtime; no down-migration.
SQLite remains local plaintext protected by OS account/filesystem boundary; same-account/admin processes are outside it.
Physical delete is not forensic erasure; process restart durability does not imply arbitrary power-loss/filesystem guarantees.
Windows real acceptance was run; real Linux/macOS acceptance and new Chrome GUI acceptance are **UNVERIFIED**.
Assistant/normal Runtime remain stopped after the authorized build shutdown; user can explicitly restart via launcher.

## 24. Deferred to M4B

- Multi-turn AI execution.
- Conversation context assembly.
- Explicit selected Memory into a Conversation turn.
- Failed/cancelled/timeout execution handling.
- Retry semantics.
- Model execution integration.

## 25. Deferred to M4C

- Conversation logical backup/export.
- Conversation restore.
- Backup schema/version handling.
- Recovery / portability verification.
- Full lifecycle acceptance.
- Final M4 Windows + Ollama acceptance.
- Final M4 privacy/restore closing.

## 26. M4 Overall Closing Gate

**M4A CLOSED — GO ≠ M4 CLOSED — GO**.
M4 Final Closing remains blocked by **M4C Conversation Backup / Restore Gate**.
Must include logical export, restore, schema/version handling, recovery, portability and privacy verification.
M4A restart persistence cannot close recovery/portability or M4 overall.

## 27. Architecture Compliance

Conversation distinct from Memory/tasks; linear immutable history with meaningful Turn boundary.
No automatic Memory, RAG/Knowledge/embeddings/vector DB, Agent/tool framework, Finance/Gateway/PostgreSQL,
React/WebView2 main UI, Browser Conversation permission, edit/regenerate/branching or premature backup claim.
ADR-004 Memory/privacy/transaction principles preserved; ADR-005 ordinary stateless Ask and explicit Memory context preserved;
ADR-006 format/schema1 and fresh v1 maintenance publication preserved. No Accepted ADR edited or silently superseded.
Versioned additive Workspace migration is the explicitly authorized M4A schema extension; architecture document holds the new long-term rules.

## 28. Git Status

Delivery remains local on `m4a-conversation-domain`; implementation and documentation committed separately.
Final working tree is checked clean after the documentation commit. Native data/credentials/build/log/evidence stay ignored.
Local main contains only the separately approved launcher commit beyond origin/main; M4A not merged to main.
No push/tag/release/force operation. Final closing SHA is available from `git log -1` and delivery response.

## 29. Recommended Next Step

Architecture / Closing Review of M4A implementation, migration/version separation and acceptance evidence.
Keep M4 overall OPEN behind M4C backup/restore gate. Await that review and explicit next milestone authorization.
Do not automatically begin M4B, M4C or Main Workspace UI.
