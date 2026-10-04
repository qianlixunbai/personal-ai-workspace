# M4B CLOSING REPORT

Date: 2026-10-04 (Asia/Shanghai)

## 1. Result

**M4B — IMPLEMENTED / LOCAL ACCEPTANCE PASS / AWAITING ARCHITECTURE AND CLOSING REVIEW**.
Java **94 PASS**, Desktop **123 PASS**; real Windows WPF/HTTP/SQLite/Ollama Conversation acceptance PASS,
M3/M4A/native AI/Browser regressions PASS, privacy/security audit PASS.
No formal M4B publication or final CLOSED — GO declaration is made before the requested review.
**M4 — OPEN**. Only M4B was implemented; M4C and Main Workspace were not started.

## 2. Delivery Gate

**PASS WITH REMOTE FRESHNESS DEFERRED**, using the user's explicit one-time exception.
Working tree was clean on main; local main, cached origin/main and M4A branch all equaled
`126061bc116c8d7f2215446eac8151149ae07f1a`. No unknown local commits; launcher, implementation,
closing and the tracked M4A report were present. The preceding formal delivery in this chat successfully
performed ff-only merge, push and post-push fetch verification.

Remote freshness at M4B start: **UNVERIFIED due to GitHub connectivity outage**.
Last verified published baseline: **126061bc116c8d7f2215446eac8151149ae07f1a**.
Two start fetches failed with github.com:443 connection timeout; a later retry failed with connection reset.
Cached origin/main is not evidence of current real-time remote state.

## 3. Reality Check

Confirmed source at the known baseline: independent Conversation/Turn/Message, Workspace DBv2,
unchanged Memory v1 logical backup/source, ordinary stateless Ask, native-only Conversation CRUD,
existing shared TaskManager/policy/profile/Ollama, Desktop/Core and WPF, acceptance/privacy scripts.
Reviewed all six Accepted ADRs; no material conflict. M3's no-persistence Ask rule remains on its original
paths; M4A's separately authorized durable Conversation domain is extended independently.
Historical closing counts were Java75/Desktop111; they were not presented as new test results.

## 4. Git

Branch created from formal main: `m4b-conversation-execution`.
Implementation commit: `7bd2261f71131dc6be397954c89a45f78799062b`,
`feat: add multi-turn conversation execution`.
Acceptance evidence correction: `348c2f1b918bb5cb775c74e54fafecbfe9b17093`,
`test: distinguish simulated provider failure evidence`; smoke rerun PASS, failure phase correctly reports realOllama=false.
README/STATUS/architecture/this report follow in a separate local documentation commit.
No merge/push/rebase/reset/cherry-pick/force/tag/release/branch deletion; other repositories untouched.

## 5. Baseline

Formal M4A baseline: `126061bc116c8d7f2215446eac8151149ae07f1a`.
Implementation: `03591f9fe74f3a3db18ca062ae168f21cb668a49`.
Approved launcher: `c90359f425c13cc272c80dd57442ce5cb95ee101`.
M0/M1/M1.5/M2/M3/M4A remain CLOSED — GO; M4 remains OPEN.
No user Memory or production Windows Credential Manager entry was used by acceptance.
Installed Ollama was initially stopped; a hidden local service was started with existing qwen3.5:4b.
No model installation or download was needed. That Ollama service is retained; test Runtime/WPF processes exited.

## 6. Files Changed

36 implementation/test/acceptance files plus four documentation files, 40 total.

| Group | Files |
| --- | --- |
| Runtime execution | ConversationContext, ConversationExecution, ConversationController, TextTaskSubmission, Provider, OllamaProvider, ClientIdentity, TaskManager |
| Domain/schema | Conversation, ConversationLimits, ConversationStore, ConversationConfiguration, WorkspaceSchema |
| Java tests | new ConversationContextTest/ConversationExecutionTest/ConversationV3MigrationTest; existing RuntimeApiTest/ConversationStoreTest/MemoryStoreTest |
| Desktop/Core | Conversation.cs, RuntimeClient.Conversation.cs, RuntimeClient.cs |
| WPF | ConversationWindow.cs; AssistantWindow.xaml/.xaml.cs; AssemblyInfo.cs |
| Desktop tests | new ConversationExecutionClientTests/ConversationWindowTests; existing ConversationClientTests |
| WPF acceptance | PersonalAiWorkspace.ConversationAcceptance.csproj / Program.cs |
| Scripts | new conversation-execution-smoke.py; existing conversation-storage-smoke.py, memory-storage-smoke.py, desktop-memory-backup-smoke.py, privacy-audit.py |
| Documentation | README.md, docs/STATUS.md, docs/architecture/current-architecture.md, this report |

Names without paths refer to existing corresponding Java/Core/Desktop packages. No external repository edits.

## 7. Conversation Execution Architecture

Native Desktop → ConversationExecution → validation/exact Memory snapshot → durable USER/PENDING →
Runtime context → existing TextTaskSubmission profile/policy/provider stack → shared TaskManager →
LOCAL_ONLY Ollama → serialized durable completion. Conversation is neither Memory nor transient Task retention.
One PENDING execution per Conversation ensures linear inference. Other Conversations share the original queue/concurrency.

## 8. Send / Persistence Ordering

Validate current text and complete mandatory Memory context without truncation. Obtain the existing exact-revision
admission snapshot. BEGIN IMMEDIATE verifies ACTIVE and absence of PENDING, allocates stable sequence,
saves PENDING/USER/preallocated task ID/ordered selection metadata, updates parent time and COMMITs.
Only afterward can TaskManager accept and execute work. Independent DB-connection tests observe the USER
and PENDING before provider execution. Rejection never deletes the USER.

## 9. Task Integration

Existing bounded TaskManager only; no new pool/queue/scheduler. A minimal Completion callback is serialized
under its existing terminal-state lock and bound to preallocated taskId/conversationId/turnId.
Polling/cancel use existing owned Task API. Completed callback references are released. Queue full and
closed-manager admission record FAILED; policy and unexpected submission failure are sanitized and tested.

## 10. Success Finalization

The winning success callback inserts ASSISTANT, marks SUCCEEDED and updates Conversation.updatedAt in
one SQLite transaction before Task SUCCEEDED is published. Atomicity/rollback/fault-injection tests PASS.
A storage failure converts Task to FAILED/CONVERSATION_STORAGE_UNAVAILABLE with no result and attempts
a controlled durable FAILED outcome. If all storage is unavailable, durable PENDING remains until startup
fail-closed reconciliation; neither Task nor Turn falsely claims success.

## 11. Failed / Cancelled / Timeout Semantics

Submission/provider/policy/model failure → FAILED with controlled failure enum. QUEUE_FULL → FAILED.
Task cancellation → CANCELLED; queue/execution/provider timeout → TIMED_OUT. USER stays; no ASSISTANT
is created for any unsuccessful outcome. UI shows execution state, never fabricated assistant error text.
Failure enum: EXECUTION_INTERRUPTED, PROVIDER_UNAVAILABLE, MODEL_UNAVAILABLE, QUEUE_FULL,
POLICY_DENIED, EXECUTION_FAILED, STORAGE_UNAVAILABLE. No raw errors are persisted.

## 12. Startup PENDING Reconciliation

Conversation bean initialization, before HTTP admission, transactionally marks all leftover PENDING
FAILED/EXECUTION_INTERRUPTED and touches affected parent timestamps. No task reconstruction or provider call.
Real packaged restart with synthetic PENDING proves no Assistant and zero tags/chat calls on startup.
Repeated reconciliation is idempotent; no automatic resend or retry.

## 13. Retry Semantics

**Dedicated Retry = NOT IMPLEMENTED BY DESIGN**.
No retry endpoint, in-place replay, automatic retry, regenerate, alternate answer, attempt graph or retryOfTurnId.
Terminal service guards and DB trigger reject terminal → PENDING and duplicate completion.
Manual new Send creates a new Turn; failed history is kept and excluded from inference.

## 14. Context Assembly

Runtime-only, deterministic and local. Order: Runtime system → current explicit Memory reference-data JSON
as a user message, when selected → bounded successful USER/ASSISTANT pairs → current USER exactly once.
Provider serializes supplied roles without choosing business history. Desktop sends references and input only.
No title/lifecycle/error text, cross-Conversation context, automatic retrieval or compression.

## 15. Context Budget

Reuse chat.balanced: context8192/output2048/maxTextCharacters3000.
Measure actual serialized messages including JSON escaping, role/content wrappers and Memory JSON.
UTF-8 input budget = context-output-templateReserve;
templateReserve=max(512,escaped system bytes+escaped model bytes+256 conservative wire-envelope bytes).
System remains <=512 bytes; no profile expansion or client model/context setting.
Tests explicitly verify provider JSON plus output reserve fits the profile context, including Unicode and long model config.
Mandatory current/Memory overflow is controlled INVALID_REQUEST; neither is silently truncated/dropped.

## 16. History Admission Rules

Only complete prior SUCCEEDED USER+ASSISTANT pairs. FAILED/CANCELLED/TIMED_OUT/PENDING excluded.
Newest successful exchanges admitted first; output order is sequence ASC. Admission stops when the next
older whole Turn cannot fit. No partial Turn/message or mid-message truncation. Durable history keeps all
outcomes within existing1000-Conversation/1000-Turn limits; inference has a smaller independent window.

## 17. Explicit Memory Selection

Existing 1–4 unique ACTIVE ID/revision selector reused; no selection means empty Memory context.
WPF uses the existing complete-preview MemorySelectionWindow. Selection clears after accepted execution,
explicit Clear, change of Conversation and close. Stale selection blocks sending until explicit reselect/clear.
No automatic search/selection/injection/extraction/save. Memory IDs/revision/order alone are durable Turn metadata.

## 18. Memory Revision Consistency

Reuse MemoryStore.snapshotForAsk: requested order, one SQLite read transaction, exact revision/ACTIVE check.
Any invalid/missing/edited/archived reference rejects the entire selection; no substitution or partial use.
The immutable admitted snapshot is unchanged by later edit/archive/delete, tested with actual store mutations.
Metadata has no FK to Memory source and retains no Memory body; physical Memory deletion remains available.
Future Turns only use their own new selection. Prior assistant replies can naturally contain Memory-derived facts.

## 19. Provider / Policy

Separate conversation capability and conversation-v1 prompt, same chat.balanced/Ollama abstraction and policy.
Native identity adds conversation; Browser remains translate only. Admission/work/final egress verify LOCAL_ONLY;
no direct Conversation HTTP to Ollama, cloud fallback, retry or provider stack duplication.
ProviderExecution adds immutable user/assistant messages with redacted diagnostics. Existing single-turn constructor
and Translate/Summarize/Ask contracts remain. Output is validated before durable completion.

## 20. Cancel / Timeout / Stale Result

Shared lock selects one terminal winner. Cancel/timeout persists first; late worker success cannot invoke another
durable completion. Store verifies conversation/turn/task association and PENDING status. Tests cover wrong task,
wrong Conversation, duplicate/terminal completion, queued/running timeout and late success rejection.
Cancel propagates existing HTTP cancellation and does not promise immediate GPU termination.

## 21. Archive / Delete Race Semantics

Archive rejects new sends and leaves existing execution running. Existing pending Turn may succeed or terminate.
Rename does not alter admitted context; title is never input. DELETE checks PENDING in the same transaction
and returns409 CONVERSATION_CONFLICT until terminal. No delete-induced cancel or background orphan work.
Terminal deletion cascades Turn/Message/selection metadata. Read/poll never changes updatedAt.

## 22. Runtime API

Native-only POST `/api/v1/conversations/{id}/turns`: message plus optional existing memories selector.
202 conversationId/turnId/taskId/status, Location `/api/v1/tasks/{taskId}`, count/sequence/size context evidence.
Only the Task owner may GET/DELETE that Task. Durable detail exposes safe failure enum, taskId and selection metadata.
Unknown fields/roles/system/model/provider/history/assistant rejected. No retry/regenerate/edit/branch endpoints.
Existing controlled error envelope/status codes preserved; Browser/web Origin/auth denial remains enforced.

## 23. Desktop Core

Single existing RuntimeClient: SubmitConversationTurnAsync, GetConversationTaskAsync, CancelConversationTaskAsync.
Native bearer, fixed loopback, no proxy/redirect/cookies, deadline/1MiB/duplicate/strict field and association checks.
Strict conversation-v1/chat.balanced task parsing and controlled storage/selection/queue errors.
Read-only DTO lists and redacted diagnostics. No prompt assembly, provider config, DB read or Desktop history store.

## 24. WPF Conversation UX

Production Assistant Conversation… button opens a single minimal native modal. Create/select ACTIVE,
ordered plain text paged Turns, send, existing explicit Memory picker, task status/cancel, refresh/reopen and archive.
Failed/Cancelled/TimedOut are execution labels, not Assistant messages. Close cancels HTTP, clears content/selection,
disables undo and guards late UI updates. Reopened PENDING retains taskId for explicit cancellation/refresh.
No React/WebView2, redesign, Markdown/code renderer, avatars, auto-title, token counter or search UI.

## 25. Browser Boundary

Browser remains Translate-only. Route/CORS/credential allowlists unchanged.
Current HTTP tests deny Conversation send/detail/preflight, Ask/Memory and native task ownership access.
Existing real local Browser Batch/security/revoke/restart regression PASS with synthetic clients.
Historical Chrome GUI/MV3 acceptance is not relabeled as current M4B GUI evidence; Browser repository untouched.

## 26. Privacy

No USER/ASSISTANT/Memory/title/prompt/context/provider response/credential logs. Only IDs/status/counts/sequences/sizes
in evidence. Source and DTO diagnostics redacted. Context privacy markers extend the existing scanner.
Acceptance uses isolated temporary data/auth; M4B WPF never reads user Memory or production WinCred.
M4B smoke's temporary data was removed and own logs were scanned with actual temporary token and synthetic markers.
Existing critical WinCred tests use their own test credential target; no user credential mutation.

## 27. Security

Final audit PASS: actual local credentials6, content/secret matches0, tracked build/DB artifacts0, ignore checks PASS.
Source/build/archive/log/verification scans and Browser secret audit PASS. No body evidence or DB committed.
Private SQLite/account permission boundary unchanged; plaintext and same-account/admin threat limitations remain.
Two test-only native/Browser regression data directories remain after automatic approval rejected their cleanup
(`blocked by policy`, no specific reason provided). No workaround or permission bypass was attempted.
They are named `workspace-m4b-native-f0332511f1fe426f9a4db8463dca81c0` and
`workspace-m4b-browser-c0225bd38fe042b4bbcba1588d9cc59a` in the OS temporary directory and contain synthetic test data only.

## 28. Schema Migration

Workspace DBv2→v3 adds nullable task_id/failure_code, unique task ID index, ordered immutable selection metadata
and terminal immutability trigger. v0/v1 still upgrade transactionally via existing infrastructure.
Explicit v2 preservation and failed-v2 rollback tests PASS; M3 source/revisions/FTS and M4A histories unchanged.
Unknown newer version fails closed. Memory logical backup remains format1/schema1; fresh restore remainsv1
and next Runtime startup upgrades to Workspacev3 with empty Conversation tables.

## 29. Tests

| Command / evidence | Current result |
| --- | --- |
| `.\mvnw.cmd clean verify` | **94 PASS**, 0fail/error/skip; BUILD SUCCESS |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS, 0warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | **123 PASS**, 0fail/skip |
| `git diff --check` / staged check | PASS |

Existing75/111 retained and adapted only for authorized v3/send/delete contracts; new19Java/12Desktop.
Grouped tests cover context ordering/exclusion/budget/whole-window/revision, execution atomicity/failure/ownership,
cancel/timeouts/retention/startup/lifecycle/retry, migration and UI/client validation/stale/close/late results.
Initial transitional failures were corrected; final counts above are actual successful results.

## 30. Real Multi-turn Ollama Smoke

`python -X utf8 scripts/conversation-execution-smoke.py` PASS against the final packaged Runtime.
Real production WPF entry and controls, first/second successful Turn, expected synthetic marker recalled from
Conversation history with no Memory. A separate Conversation's first admission has no history and no cross-Conversation marker.
Assertions use marker containment plus admission evidence, not exact free-form output equality.

## 31. Real Explicit Memory Smoke

Real WPF preview/Add/Use selected → real Ollama answers synthetic Memory marker; selection clears.
Separate API confirmation: current selected Memory count1; next new Turn's admission Memory count0.
Evidence includes only memoryCount/admittedSequences/input sizes. No requirement that the model forget
facts already present in successful assistant history, and no automatic Memory save to satisfy smoke.

## 32. Failure / Restart Smoke

Real controlled cancel → CANCELLED/no Assistant. Isolated packaged Runtime restarted on a synthetically
seeded PENDING → FAILED/EXECUTION_INTERRUPTED; counting local HTTP endpoint records0tags/0chat on startup.
Explicit new send against a controlled empty-model-list endpoint → MODEL_UNAVAILABLE/FAILED/no Assistant;
1tags/0chat confirms no automatic retry. This is real Runtime/Ollama-adapter HTTP integration with a synthetic
failure endpoint, not a claim that the running real Ollama daemon was broken or stopped.
Real timeout **UNVERIFIED**; automated queue/execution timeout and stale-result tests are primary evidence.

## 33. Real Windows Acceptance

PASS: actual production Assistant Conversation button/modal, create, first/second local model turns, history use,
full Memory preview/selection, cancel/no fake Assistant, archive-disabled Send, process exit/restart, durable reopen
and continue, controlled FAILED execution labels and close cleanup. Three WPF acceptance processes ran
initial/reopen/failure phases against real packaged Runtime/SQLite; initial/reopen use real installed Ollama.
Existing Translate/Summarize/ordinary Ask/Memory real-local regressions and integrated real WPF Memory recovery PASS.
Current123 Desktop tests include actual isolated WinCred roundtrip/import, native hotkey registration/conflict/release,
hidden UIA synthetic fixture and selection/lifecycle regressions. They are current test evidence, not a new manual
Notepad/Chrome user-session acceptance claim. No new unverified Conversation GUI gate is hidden.

## 34. Regression

- `python -X utf8 scripts/memory-storage-smoke.py`: PASS, real packaged restart/CRUD/search/lifecycle.
- `python -X utf8 scripts/conversation-storage-smoke.py`: PASS, M4A restart/domain regression with authorized v3 upgrade.
- `python -X utf8 scripts/desktop-memory-backup-smoke.py`: PASS, real Windows WPF/Ollama logical Memory recovery, negative restore and privacy.
- `scripts/real-local-smoke.ps1`: REAL PASS, Translate/Summarize/stateless Ask, isolated synthetic data.
- `scripts/browser-security-smoke.ps1 -Batch`: REAL PASS, actual local Batch inference, ownership/Origin/capability/restart/revoke/secret checks.
- Existing privacy-audit.py: PASS, including M4B context markers and actual credential/source/build/archive/log/evidence/DB tracking checks.

The Memory recovery script's old global no-selection-table assertion was scoped to permit only the new independent
Conversation selection table; it still asserts all Conversation/selection rows are absent after Memory-only restore.

## 35. Known Limitations

Remote freshness UNVERIFIED; no formal publication. Two regression temporary directories remain because
cleanup was automatically rejected. Real timeout smoke not manufactured; controlled tests provide evidence.
No Conversation logical export/restore/portability, streaming, edit/regenerate/branch/retry framework.
Full storage outage can leave durable PENDING until restart; task reports storage failure, never success.
SQLite remains local plaintext/OS permissions; deletion is not forensic erasure; old Runtime cannot openv3.
Cross-request pages lack shared snapshots. One pending Turn per Conversation; shared completion DB work may
briefly delay other task transitions within existing3s SQLite busy timeout. No Linux/macOS or new Chrome GUI acceptance claim.

## 36. Deferred to M4C

- Conversation logical export.
- Conversation restore.
- Logical backup envelope/version.
- Recovery.
- Portability.
- Final full lifecycle acceptance.
- Final M4 Windows/Ollama acceptance.
- Final M4 privacy/restore closing.

No M4C implementation started and Memory backup was not widened to include Conversation.

## 37. M4 Overall Closing Gate

**M4 remains OPEN** behind M4C Conversation Backup / Restore Gate.
**M4B CLOSED — GO ≠ M4 CLOSED — GO**; an eventual M4B review GO cannot close M4 or claim portable recovery.
Restart durability/reconciliation is not logical backup or recovery completion.

## 38. Architecture Compliance

Runtime context/LOCAL_ONLY/shared execution, Conversation ≠ Memory, USER persist-before-execute,
successful-only complete history, exact per-turn selection, immutable terminal/Message, no retry/replay,
Browser Translate-only and unchanged ordinary Ask verified. All Accepted ADRs retained.
Current architecture carries new long-term rules; no unnecessary ADR/framework.
No Finance/Knowledge/RAG/Agent/TOOL role/semantic retrieval/auto Memory/React/WebView2/M4C scope drift.

## 39. Git Status

Local implementation, acceptance evidence correction and documentation commits on `m4b-conversation-execution`; final clean working tree
verified after documentation commit. main and cached origin/main remain the formal M4A baseline.
No merge or push, no tag/release, M4A branch retained. Exact documentation HEAD is provided by git log and delivery response.
Credentials/data/build/log/evidence remain ignored; temporary audit evidence is not committed.

## 40. Recommended Next Step

STOP implementation and request the already planned Architecture / Closing Review of this concrete local result.
Remote freshness remains deferred. Before any M4B merge/push/formal delivery successfully run:

```powershell
git fetch origin
git rev-parse main
git rev-parse origin/main
git log --oneline --decorate --graph --all
```

If origin/main is still `126061bc116c8d7f2215446eac8151149ae07f1a`, proceed only under the separately authorized
Closing/Delivery process. Unknown new remote commit → **STOP / REMOTE DIVERGENCE REVIEW**;
no automatic merge/rebase/cherry-pick/reset/force push. Keep M4 OPEN and do not start M4C or Main Workspace.
