# MMF-2B Native Model Management — SOURCE REVIEW APPROVED — GO

2026-10-08 (Asia/Shanghai). **Architecture Guard Final Decision: SOURCE REVIEW — APPROVED — GO; Source blockers: 0.** Runtime/native/Desktop/Settings source and the narrow validation-required implementation supplement are approved. ADR-015 Accepted text is unchanged. Source publication is authorized; real integrated acceptance is NOT PERFORMED; MMF-3 NOT STARTED; Model Management Overall NOT CLOSED. Section 17 records formal approval/publication; earlier review-stage decisions are historical.

The initial source review reproduced a recovery/restart confirmation bypass in `ModelFoundationTest.confirmedGuardRecoveryFollowedByRestartStillRequiresNativeValidation`. The authorized targeted remediation preserved that assertion and passed it using a separate durable validation requirement. Trusted Release/candidate completion and execution uncertainty are distinct; capability-specific Desktop error mappings are repaired. Architecture Guard independently closed the P0 and both P1 findings. Section 16 retains the remediation evidence and historical decisions; section 17 supersedes their pending-approval status without erasing previous failures.

## 1. Historical initial implementation Git Reality Gate

| Repository | Entry branch | HEAD / freshly fetched origin/main | Entry tree |
| --- | --- | --- | --- |
| Workspace | main | `16ee6eb50d982b474f020b33acfd252268d13144` | clean |
| Browser | main | `267db794691d1a9ab1712b30b07cbeb57241808b` | clean |

Both exact proxy fetch commands succeeded: `git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 fetch --prune origin`. No fallback/global configuration change. No ancestor or repository AGENTS.md found; user-provided instructions apply. Workspace alone created `codex/mmf-2b-native-model-management`. Browser remains clean on its approved baseline. No Finance access, reset, history rewrite or publication.

Authority/owners inspected: Accepted ADR-015 §3–8.1, current architecture/status and MMF-1 implementation record; ActiveModelManager, ModelStateStore, OllamaProvider/Admission, Runtime API/security, native WebFetch confirmation/WorkspaceBridge, application RuntimeClient and React Settings. MMF-1 reservation/Conversation admission and MMF-2A opaque epoch architecture are retained.

## 2. Frozen native API contract (recorded before implementation)

All routes below are native-credential-only, `Cache-Control: no-store`. The existing security filter rejects Browser before reading mutation bodies. No Browser scope changes.

| Method / route | Exact request | Success |
| --- | --- | --- |
| GET `/api/v1/models/catalog` | no body/query | 200 `{selectionRevision, models}`; at most 64 verified candidates |
| GET `/api/v1/models/status` | no body/query | 200 model status |
| GET `/api/v1/models/recovery` | no body/query; metadata inspection only | 200 model status |
| POST `/api/v1/models/selection` | frozen intent; action `SWITCH` or `RELEASE_OLD_THEN_SWITCH` | 200 model status |
| POST `/api/v1/models/release` | frozen intent; action `RELEASE` | 200 model status; selection unchanged |
| POST `/api/v1/models/validation` | frozen intent; action `VALIDATE` (same configured selection) | 200 model status; selection unchanged |
| POST `/api/v1/models/recovery` | frozen intent; action `RECOVER` | 200 model status; no load/replay |

Frozen intent exact fields: `action`, `catalogHandle`, `candidateModel`, `candidateDigest`, `expectedSelectionRevision`, `expectedActiveModel`, `expectedActiveDigest`, `recoveryGeneration`, `externalConfirmed`. Active fields are both null for no Active. Generation is null except RECOVER; externalConfirmed is true only after native shared-client/external-fact confirmation for RELEASE, RELEASE_OLD_THEN_SWITCH or RECOVER. No endpoint, path, provider override or configuration fields are accepted. Native owns the frozen intent; React sends only action, handle, revision and generation to request a native dialog.

Catalog entry exact fields: `handle`, `model`, `digest`, `contextLimit`, `completion`, `localSourceVerified`, `providerDeclaredVision`. Handles are opaque UUIDs, bound to the exact verified identity/digest and catalog selection revision; refresh replaces the bounded handle set. Unsupported candidates are excluded under the existing strict local admission policy; malformed global catalog shape, duplicate identity and overflow are controlled failures. Catalog/status never warm.

Status exact fields: `configuredModel`, `configuredDigest`, `activeModel`, `activeDigest`, `selectionRevision`, `installed`, `loaded`, `ready`, `reserved`, `queued`, `executing`, `draining`, `switching`, `uncertain`, `recoveryGeneration`, `validationRequired`, `error`. Facts are TRUE/FALSE/UNKNOWN; error is a safe Runtime error code or null. Configured and Active identity/digest are separate. Read-only observation rechecks metadata and residency; failure makes residency UNKNOWN.

Errors: 400 INVALID_REQUEST; 401 UNAUTHORIZED; 403 POLICY_DENIED; 409 MODEL_SWITCH_CONFLICT / MODEL_SELECTION_REVISION_CONFLICT / MODEL_EXECUTION_UNCERTAIN / MODEL_CATALOG_STALE / MODEL_CATALOG_LIMIT_EXCEEDED; 503 MODEL_STATE_UNAVAILABLE / MODEL_CONFIGURATION_INVALID / MODEL_IDENTITY_CHANGED / MODEL_UNAVAILABLE / PROVIDER_UNAVAILABLE; 504 TASK_TIMEOUT; 500 PROVIDER_RESPONSE_INVALID / INTERNAL_ERROR. Duplicate/unknown keys, trailing tokens and nonintegral/out-of-range revision fail closed. No automatic POST retry; lost mutation response permits status GET reconciliation only and remains UNKNOWN.

Catalog, status and each provider mutation use a total cancellation budget capped at the configured task execution budget and 150 seconds. Mutation success returns a local status snapshot instead of starting additional provider IO after commit. Desktop model-only HTTP deadlines are 155 seconds; React allows the native dialog plus mutation/reconciliation to finish. Existing non-model deadlines remain unchanged.

## 3. Runtime Switch / Release / Recovery Evidence

Implemented source: a narrow ModelController delegates to the sole ActiveModelManager. Strict bounded JSON and exact action/route binding; no arbitrary URL/path/model override; native route checks complement the existing pre-body Browser filter denial. Catalog handles bind verified identity/digest and observed revision, with a replace-only map of at most 64 entries. Read-only catalog uses the existing supported-version local admission; it never sends inference/load/unload. Overflow is rejected rather than truncated.

Selection uses the same gate as reservations: expected revision, exact Active and catalog binding, idle/uncertainty admission, then one switch owner. Candidate metadata/local/context verification precedes guarded bounded text validation. Commit precedes Active and fresh opaque epoch publication. Metadata rejection retains the previous ready Active. Candidate send/failure retains durable selection and closes readiness without automatic old reload or candidate cleanup.

Normal switch sends no Release. RELEASE_OLD_THEN_SWITCH authorizes the exact old Active, checks both candidate and old metadata, then performs one guarded Release before validation/commit. Standalone Release performs one guarded `/api/generate` request with a fixed local identity, `stream=false`, `keep_alive=0`, no prompt. Its trusted completion parser accepts the fixed v0.40.0 unload response. Selection bytes/revision remain unchanged on standalone Release and VALIDATE. Protocol basis: [Ollama v0.40.0 routes](https://github.com/ollama/ollama/blob/v0.40.0/server/routes.go) and [API types](https://github.com/ollama/ollama/blob/v0.40.0/api/types.go); verification used fixture responses only.

RECOVER checks the catalog/configured digest, exact revision/Active, user external-fact confirmation, zero local leases and current recovery generation. The logical gate remains closed during metadata validation and private guard clearing. Valid startup guards can be explicitly recovered; malformed/pending/unsafe state remains STOP. Recovery changes generation/epoch, leaves readiness false and requires a separate confirmed validation across Runtime restarts; no load/replay/Release is performed by recovery. The targeted remediation publishes the exact validation marker before clearing the guard.

**Historical blocker, now remediated:** the initial implementation kept that requirement only in memory. The retained recovery/restart regression previously failed because ordinary reservation succeeded. It now proves zero automatic probe, followed by native VALIDATE, reliable marker removal and usable admission. Review the candidate persistence contract in section 16 before approval.

The original MMF-1 failure, reservation/switch races, Conversation zero USER Turn, draining/guard and atomic publication tests still pass in the final owning-layer execution. Their package-private fixture switch shares the same selection-operation implementation as the new native entry. The new native lifecycle flow covers stale handle/revision, reserved switch rejection, separate Release authorization, unchanged durable selection on Release/VALIDATE, release-old switch, restart guard retention, wrong recovery generation, digest drift, generation replacement, no recovery inference and catalog malformed/overflow rejection.

## 4. WPF Exact-Intent Confirmation Evidence

NativeModelConfirmation/ModelConfirmationWindow show the frozen canonical target, SHA-256 digest, old Active or explicit absence, exact action and expected revision; recovery also shows generation. All loading actions show the required automatic-eviction warning verbatim. Release-old explicitly identifies its additional single target.

Cancel is default and initially focused; Escape/close reject. Release/recovery confirmation has an initially unchecked external-coordination/fact checkbox, and Allow stays disabled until checked. The narrow native owner applies a real 60-second lifetime, current-session authority, document/session invalidation, one-shot consumption and late-response suppression.

Actual automated WPF tests passed for default/focused Cancel, read-only captured fields, action-specific warning/consent, and close refusal. Actual expiry test waited 60 seconds and verified zero POST after late Allow. This is automated WPF fixture evidence, not product Windows/Chrome model acceptance.

## 5. Desktop Bridge / RuntimeClient Evidence

RuntimeClient.Models.cs uses the application-owned RuntimeClient and fixed Runtime endpoint. Catalog/status have bounded response sizes, exact fields, safe revisions/UUIDs/identity/digests and controlled error/status parsing. Runtime credentials never enter model DTOs or React.

WorkspaceModels holds one session-owned snapshot and one pending model intent. React requests only action/opaque handle/revision/generation; it cannot supply an externalConfirmed flag, arbitrary candidate identity/path/endpoint, or an already-approved authority. The native owner freezes the trusted catalog item plus status, awaits WPF, rechecks expiry/session under the same lock and starts exactly one POST. After consumption it discards the snapshot so a new operation must refresh and obtain a new dialog.

WorkspaceBridge adds only models.inspect, models.inspectRecovery and models.mutate; strict payload/method admission, trusted document checks, request deduplication, bounded envelopes and session late-response suppression remain owned by the existing bridge. Rejected/stale/foreign document/session requests cannot submit a mutation.

Mutation response loss performs one status GET and returns UNKNOWN even if a revision is observed. No automatic POST retry, rollback, new candidate or Release follows reconciliation. The focused native flow actually proved denial, stale revision, immutable exact payload, Release vs Switch authority, replacement cancellation, one-shot consumption, late mutation response suppression and GET-only unknown reconciliation.

**Targeted integration completed:** MemoryAskError, ConversationError and KnowledgeAnswerError now accept the strict admission status/code pairs documented in section 16. Conversation CRUD retains its existing narrower error contract. Unknown/management-only codes and wrong statuses remain invalid; Knowledge invalid/lost POST retains OutcomeUnknown without replay. No domain production owner was changed to mask an error.

## 6. React Settings Implementation

Settings layout/maintenance entries remain; a minimal model card adds explicit read-only refresh, installed candidate selection, configured/Active identities, separate installed/loaded/ready facts, local reservation/queued/executing/draining counts, uncertainty and generation. It offers Switch, explicitly separate release-old switch, single Release, same-selection validation, read-only recovery inspection and confirmed recovery.

No model is auto-selected or warmed. Unreachable residency remains UNKNOWN; provider-declared Vision is labelled separately from Workspace Vision Not Validated. Buttons require appropriate catalog/idle state; recovery requires uncertainty and the exact configured candidate. Mutation and session generation checks suppress stale results. Unknown outcome clears usable catalog/operation authority until an explicit read-only refresh. React performs no direct Ollama fetch and has no credentials or filesystem paths.

The integrated Settings/real typed bridge test actually passed for distinct state display, finite native intent payload, busy-state suppression, unknown result without replay, stale session suppression and strict rejection of extra metadata. TypeScript static checking passed after the new test.

## 7. Browser Isolation & Cache Compatibility

Browser sibling is unchanged. Read-only inspection confirmed published runtime-client.js opts into cacheIdentityVersion=1; content.js calls readiness before cache lookup for batch and selection, with identity/generation checks on mixed outputs. No Browser test was executed here.

Actual Runtime HTTP tests passed for Browser catalog/status/recovery denial with and without Origin, over-budget malformed Browser mutation denial before parsing, native authentication/exact request handling, no model identity disclosure to Browser, legacy readiness denial after switching and identity-v1 availability. Successful selection/validation gets a new random opaque epoch; existing task snapshots retain their epoch. No Browser route/scope or MMF-2A identity schema changed. Real A-cache → B-switch compatibility remains MMF-3, NOT PASS in this report.

## 8. Local-only / Residency / Vision Boundary

Existing fixed loopback/no-proxy/no-redirect transport, fixed v0.40.0 source subset, tags→show→tags identity/digest checks, completion/context checks and final local egress revalidation remain. Show-derived local blob paths are parsed as evidence and never opened or returned. Only bounded display fields leave Runtime for native Settings; no model catalog reaches Browser.

`/api/ps` is a residency observation only; empty ps never authorizes Release or clears uncertainty. Shared external idle/completion facts come from the explicitly accepted user assumption, not Runtime verification. There is no process control, download/install, multi-provider framework, YAML default change, Vision/OCR, prompt/budget change, domain schema/backup change, automatic replay or unload cleanup.

## 9. Files Changed

Existing production:

```text
src/main/java/io/github/qianlixunbai/workspace/api/ApiExceptionHandler.java
src/main/java/io/github/qianlixunbai/workspace/common/ApiError.java
src/main/java/io/github/qianlixunbai/workspace/common/ErrorCode.java
src/main/java/io/github/qianlixunbai/workspace/model/ActiveModelManager.java
src/main/java/io/github/qianlixunbai/workspace/model/ModelStateStore.java
src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaProvider.java
desktop/src/PersonalAiWorkspace.Core/Contracts.cs
desktop/src/PersonalAiWorkspace.Core/RuntimeClient.cs
desktop/src/PersonalAiWorkspace.Core/RuntimeClient.MemoryAsk.cs
desktop/src/PersonalAiWorkspace.Core/RuntimeClient.Conversation.cs
desktop/src/PersonalAiWorkspace.Core/RuntimeClient.KnowledgeAnswer.cs
desktop/src/PersonalAiWorkspace.Desktop/AssistantApp.cs
desktop/src/PersonalAiWorkspace.Desktop/Bridge/WorkspaceBridge.cs
desktop/frontend/src/app/App.tsx
desktop/frontend/src/bridge/client.ts
desktop/frontend/src/bridge/contracts.ts
desktop/frontend/src/pages/SettingsPage.tsx
```

New production:

```text
src/main/java/io/github/qianlixunbai/workspace/api/ModelController.java
desktop/src/PersonalAiWorkspace.Core/Models.cs
desktop/src/PersonalAiWorkspace.Core/RuntimeClient.Models.cs
desktop/src/PersonalAiWorkspace.Desktop/Bridge/WorkspaceModels.cs
desktop/src/PersonalAiWorkspace.Desktop/ModelConfirmationWindow.cs
desktop/frontend/src/bridge/models.ts
```

Tests/documentation:

```text
src/test/java/io/github/qianlixunbai/workspace/model/ModelFoundationTest.java
src/test/java/io/github/qianlixunbai/workspace/api/RuntimeApiTest.java
desktop/tests/PersonalAiWorkspace.Desktop.Tests/WorkspaceModelsTests.cs
desktop/tests/PersonalAiWorkspace.Desktop.Tests/ConversationExecutionClientTests.cs
desktop/frontend/src/pages/SettingsPage.test.tsx
docs/engineering/MMF-2B-IMPLEMENTATION-CANDIDATE.md
```

No acceptance harness was added. ADR-015 Accepted text, YAML, domain schemas/backups, Browser and Finance are untouched.

## 10. Historical initial implementation Test Commands & Actual Results

Commands below ran in the previous implementation session and were NOT rerun as a matrix in this targeted remediation. Current actual executions are in section 16.

| Executed command | Actual result |
| --- | --- |
| `mvnw.cmd -q -DskipTests compile` | Initial compile failed on missing ModelStateStore import; corrected; later focused Java commands compiled successfully |
| `mvnw.cmd -q -Dtest=ModelFoundationTest test` | PASS before added restart regression |
| `mvnw.cmd -q "-Dtest=ModelFoundationTest,RuntimeApiTest#nativeModelApiCatalogExactIntentRevisionAndBrowserPreBodyIsolation+modelFoundationBrowserCompatibilityAndUnavailableDomainOperations+readinessV1FirstUseAndStrictQuery" test` | PASS before final response-snapshot adjustment and restart regression |
| `mvnw.cmd -q "-Dtest=ModelFoundationTest,RuntimeApiTest#nativeModelApiCatalogExactIntentRevisionAndBrowserPreBodyIsolation" test` | **Historical FAIL on pre-remediation source**: confirmedGuardRecoveryFollowedByRestartStillRequiresNativeValidation failed; native HTTP test and other owning-layer flows passed |
| `dotnet build desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj --no-restore -v quiet` | PASS, no warnings/errors, before frontend source completion |
| Same desktop build with `-p:FrontendSkipBuild=true` | PASS for updated native source; bundling/assets were not rebuilt |
| `dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore --filter FullyQualifiedName~WorkspaceModelsTests -p:FrontendSkipBuild=true -v minimal --logger "trx;LogFileName=mmf-2b-models.trx"` | PASS including real 60-second expiry and automated WPF dialog checks |
| In desktop/frontend: `npm test -- src/pages/SettingsPage.test.tsx src/bridge/client.test.ts` | PASS |
| In desktop/frontend: `npm run check` | PASS on final frontend/test source |
| `git diff --check` | PASS on tracked changes |

The current focused Java run has replaced the generated Surefire reports; they now record PASS, not the historical failure. Historical Desktop model evidence remains `desktop/tests/PersonalAiWorkspace.Desktop.Tests/TestResults/mmf-2b-models.trx`. Generated outputs are ignored, not source changes.

## 11. Tests NOT PERFORMED

No full Java/Desktop/Frontend/Browser suite, full browser acceptance, IME/stress/historical regression or installation/package matrix. No shared Ollama switch, warm/load, Release, inference or external kill/restart. No real two-model Windows/Chrome product acceptance, GPU/resource measurement or external-client coordination/recovery proof. Fixtures used private temporary credentials/model-state/domain roots and provider endpoints; no production token/selection was read or copied.

## 12. Historical pre-approval MMF-3 Dependency Handoff

First close MMF-2B source blockers; MMF-3 is not authorized or started. Then separately authorize isolated Runtime endpoint/token/private state/data plus actual two-model/shared-client impact, Windows native interaction, Browser A-cache→B-switch including Single/Batch/mixed/revoke/legacy guard, GPU residency effects, unknown outcome and operator recovery acceptance. Source fixtures do not replace these checks.

## 13. Historical Full Review Patch Handoff

Full tracked + untracked source/documentation patch: `.verification/mmf-2b-full-review.patch`, relative to HEAD `16ee6eb50d982b474f020b33acfd252268d13144`. No staging or commits are needed to include new files. The updated patch includes all prior candidate files plus this remediation; it is a review artifact, not an approved/deployable implementation. SHA-256 and application checks are recorded alongside the patch at handoff; section 16 also identifies the actual incremental patch.

## 14. Historical pre-approval MMF-2B Candidate GO / STOP

**Targeted remediation Candidate GO for independent Architecture Guard re-review. Approval/publication STOP pending that review.** The original regression is retained and green; normal legacy bootstrap is preserved, and guard/selection v1 schemas are unchanged.

Architecture Guard must independently review the candidate validation marker contract, its exact binding, filesystem guarantees and crash ordering while preserving §3B/§3F. The requested Desktop mappings and focused verification are complete. Previously deferred timeout/residency DTO edge review and integrated acceptance are not promoted to PASS by this remediation.

Work stops awaiting **Architecture Guard Independent Source Re-review**. No automatic publication or MMF-3 transition. Model Management overall is NOT CLOSED.

## 15. Historical prior handoff Branch / HEAD / Working Tree State

Workspace: `codex/mmf-2b-native-model-management`; HEAD and fetched origin/main remain `16ee6eb50d982b474f020b33acfd252268d13144`. Working tree contains only the authorized candidate production/test/doc changes listed above; nothing staged or committed. Dirty source is deliberately retained for review under the no-commit instruction. Generated patch/build/test outputs are ignored.

Browser: main, HEAD/origin/main `267db794691d1a9ab1712b30b07cbeb57241808b`, clean at final read-only check. No commit, push, merge, tag, release, package publication, reset, rebase or amendment performed.

## 16. Historical Architecture Guard Targeted Remediation Handoff

### 16.1 Current Git Reality Gate

Entry/final branch: `codex/mmf-2b-native-model-management`; HEAD: `16ee6eb50d982b474f020b33acfd252268d13144`, matching the approved baseline. Entry tree contained the existing unstaged/untracked candidate. It was copied into ignored `.verification/mmf-2b-targeted-remediation/before/` before editing, alongside the initial status/file list. No ancestor/repository AGENTS.md found; user-provided conservative/minimal-testing instructions applied. Accepted ADR-015 and current architecture/owning code were inspected. Remote fetch was NOT PERFORMED: this gate checks the explicitly approved local baseline. Browser/Finance were neither accessed nor modified in this remediation.

Existing candidate production/tests remain present. The original failing test remains with its rejection/zero-probe assertion. Nothing is staged/committed; the deliberately dirty feature tree is retained as instructed. No reset, clean, overwrite of unrelated candidates, history rewrite or publication was performed.

### 16.2 P0 root cause and fix

RECOVER previously deleted the execution guard and relied on `recoveryLoadRequiresConfirmation` in RAM. Restart discarded that flag and ordinary `ensureActive()` bootstrapped. Now RECOVER publishes/read-verifies a private durable validation requirement before guard deletion. Startup strictly reads it, checks its selected identity/revision, pins its digest even for legacy revision zero, and restores the confirmation gate. Ordinary reservation/readiness cannot probe/load while it remains. Authorized native VALIDATE or explicit selection actions use the existing exact-intent gate; after successful protocol/text/digest validation (and selection commit when applicable), marker removal must succeed before Ready/publication. Store failures cannot fall back to YAML or memory-only readiness.

### 16.3 Candidate durable state contract (NOT Accepted)

`validation-required.json` means the selected model requires fresh native-authorized loading/text validation. It is independent of remote execution uncertainty, Ready/residency and durable selection existence. Its exact v1 schema is:

```json
{"version":1,"validationRequired":true,"selectionRevision":0,"provider":"ollama","model":"example-local:tag","digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
```

Revision is integral `0..9007199254740991`: zero binds a real legacy effective selection, rather than implying validation is required just because no selection exists. `provider` is exactly `ollama`, `model` obeys the existing canonical local-reference/256-byte rule, and `digest` is exactly 64 lowercase hex. All six fields are mandatory; unknown/duplicate fields, null/wrong types, false, unknown version, trailing JSON, invalid UTF-8/BOM and size over 4096 bytes reject. Digest/revision/model must match the current selected identity; a well-formed mismatched marker closes AI with MODEL_STATE_UNAVAILABLE. For revision-zero recovery the marker supplies the digest pin, and native VALIDATE must independently revalidate that digest. No selection revision/presence heuristic is used.

Same single-writer private model-state root/lock; marker is excluded from domain backups. Ancestors/file/pending are NOFOLLOW and owner/type/ACL-or-POSIX checked; Windows pins deny external write/delete. Publication uses same-directory owner-only CREATE_NEW `validation-required.pending`, bounded bytes, `force(true)`, identity recheck, ATOMIC_MOVE and exact bounded readback. Pending artifacts are never promoted/ignored/deleted by startup; their presence closes model state. Marker contents/key are rechecked during store verification, and unexpected appearance/disappearance/replacement fails closed. No public schema/config/path input changed; `active-model.json` and `execution-guard.json` remain their existing v1 contracts.

Ordering: (a) RECOVER metadata/exact generation/zero leases → marker commit/readback → guard clear → not-ready snapshot; (b) confirmed load/selected Release final send boundary → marker commit/readback → guard arm → send; (c) successful confirmed validation → optional selection commit → marker removal/absence check → Ready/epoch publication → completed lease guard cleanup. IO stays outside the short gate monitor, under the logical owner/operations serialization. Marker write/read/commit/remove failures close admission and leave uncertain artifacts; persistence failure is recorded before operation close decides on guard removal. Crash after marker publication but before guard clear retains both protections. Crash after selection commit but before marker removal may leave a revision/digest mismatch: strict state STOP, never implicit repair or fallback. Crash after marker removal but before completed guard cleanup leaves execution uncertainty. Successful selected Release clears the trusted completed guard but retains the marker through restart.

Windows evidence: final ModelFoundationTest actually ran on this Windows host/NTFS workspace with private OS temp state roots; marker publication/removal, ACL rejection, pinned selection and restart fixtures passed. The marker reuses the owning store's atomic-write/ACL/NOFOLLOW primitive. Physical power-loss, filesystem fault injection, directory-entry durability and a filesystem-wide atomic replacement/permission matrix were NOT PERFORMED. Existing force/atomic-rename guarantees apply; no absolute cross-filesystem or hostile same-account/administrator guarantee is claimed. These limits require Architecture Guard assessment; ADR-015 Accepted text is unchanged.

### 16.4 Release / candidate guard lifecycle

`Reservation.close()` now clears a guard based on all outbound operations being safely completed/local leases closing, independent of future native validation. It still serializes guard IO with send, keeps the logical lease in the set through guard cleanup, and preserves uncertainty or storage failure. Standalone selected Release keeps selection bytes/revision, discards readiness, retains the exact validation marker, performs no reload, and leaves no unjustified unresolved guard after a trusted response/local exit. Partial/malformed Release leaves guard uncertainty across restart. Trusted completed candidate load with invalid business output also leaves the old selection plus validation requirement while safely clearing its execution guard; the prior assertions were corrected to the authorized distinction, rather than deleting that regression. Metadata-only candidate rejection preserves the old Ready Active and creates neither marker nor guard.

### 16.5 Desktop compatibility

MemoryAsk, Conversation turn admission and KnowledgeAnswer admission strictly map 409 MODEL_SWITCH_CONFLICT/MODEL_SELECTION_REVISION_CONFLICT/MODEL_EXECUTION_UNCERTAIN and 503 MODEL_STATE_UNAVAILABLE/MODEL_CONFIGURATION_INVALID/MODEL_IDENTITY_CHANGED. Conversation admits these/provider bootstrap errors only for turn submission; its CRUD parser stays narrow. Knowledge task polling also accepts the actually possible guarded-worker errors MODEL_STATE_UNAVAILABLE/MODEL_IDENTITY_CHANGED/MODEL_EXECUTION_UNCERTAIN. Wrong status, unknown MODEL codes and catalog-only codes reject. Existing Memory/domain meanings and Knowledge lost/invalid POST OutcomeUnknown/no replay remain. No WPF/React implementation changed in this remediation.

### 16.6 Crash / restart evidence (coverage points, not separate executions)

| Point | Actual fixture evidence |
| --- | --- |
| A: clean YAML | Existing localAdmission flow bootstraps revision zero without selection write |
| B: normal durable restart | Existing concurrency flow and extended recovery flow bootstrap the unchanged validated selection |
| C: outbound unknown | Existing malformed inference retains guard across restart with zero new probe/user send |
| D: RECOVER then restart | Retained confirmedGuardRecoveryFollowedByRestartStillRequiresNativeValidation rejects ordinary admission and asserts unchanged probe count |
| E: native VALIDATE | Same flow clears marker/guard, admits tasks, and retains normal durable restart; marker fixture also validates revision-zero digest pin |
| F: trusted selected Release | Integrated Release flow preserves selection, clears guard, restarts with marker and zero automatic probe |
| G: partial Release | Same Release flow leaves uncertainty/guard across restart and zero automatic probe |
| H: publication/guard-clear failure | Store fixture publishes marker, inserts guard pending, rejects clear; restart retains uncertainty. Existing precommit fault still retains guard |
| I: invalid/unsafe marker | Consolidated store fixture rejects pending, malformed/version/encoding/size/type/ACL and mismatched identities; no fallback; marker removal precondition failure preserves it |
| J: metadata-only rejection | Existing rejection flow retains Ready A and asserts no marker/guard, with no extra probe |

These are deterministic isolated fixtures/process reconstruction, not real abrupt process termination or power-cut evidence. Local reservation/concurrency/MMF-2A epochs and native HTTP pre-body denial remain covered by the focused owning commands.

### 16.7 Actually executed focused verification

Final commands, after the production revisions:

```text
mvnw.cmd -q "-Dtest=ModelFoundationTest,RuntimeApiTest#nativeModelApiCatalogExactIntentRevisionAndBrowserPreBodyIsolation+modelFoundationBrowserCompatibilityAndUnavailableDomainOperations+readinessV1FirstUseAndStrictQuery" test
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-build --no-restore --filter "FullyQualifiedName~ConversationExecutionClientTests|FullyQualifiedName~MemoryAskTests.WrongPromptVersionFailsClosedAtAdmissionPollAndCancel|FullyQualifiedName~KnowledgeTests.AnswerStrictProjectionBoundsAndUnknownPostNeverReplays" -p:FrontendSkipBuild=true -v minimal --logger "trx;LogFileName=mmf-2b-errors-final.trx"
git diff --check
```

All PASS. Maven performed directly affected Java compilation plus the owning model fixture/native API/readiness tests. The same dotnet filter earlier rebuilt affected Core/Desktop/test projects after their final code revision and passed; the final gate used those unchanged assemblies with --no-build after the last Java adjustment. Frontend bundling was skipped. Evidence: `.verification/mmf-2b-targeted-remediation/java-focused-final.log`, `desktop-errors-final.log`, current Surefire reports and `desktop/tests/PersonalAiWorkspace.Desktop.Tests/TestResults/mmf-2b-errors-final.trx`. No planned coverage is counted as an execution.

Earlier in this remediation, the first Java command failed the existing precommit assertion: trusted guard cleanup ran before the catch recorded persistence failure. Production ordering was corrected, and the final owning command passed. One intermediate Desktop run failed because the new CRUD rejection assertion used invalid page limit 20 (existing maximum 10), before HTTP; the fixture input was corrected, and the final command passed. No production contract was relaxed to resolve either failure.

Final source review also kept runtime marker verification on store-owned immutable bytes/file identity rather than comparing an IO snapshot against a subsequently published in-memory selection. Exact binding is checked at startup and marker publication; this avoids falsely closing the store when read-only inspection overlaps an authorized switch. The final owning Java command includes this adjustment.

NOT PERFORMED: full Java/Desktop/Frontend/Browser suites, repeated WPF confirmation/expiry tests, frontend tests/check/build (unchanged source), real Windows/Chrome/shared Ollama/two-model/GPU acceptance, stress/IME/packaging matrix and physical power-loss validation. Prior WPF/Settings/native fixture tests remain preserved; their earlier PASS is historical only. All new execution used isolated tokens, private temp model-state/data and deterministic loopback providers; shared Ollama was not operated.

### 16.8 Actual incremental diff

Only these owners changed relative to the incoming WIP: ActiveModelManager.java, ModelStateStore.java; RuntimeClient.MemoryAsk.cs, RuntimeClient.Conversation.cs, RuntimeClient.KnowledgeAnswer.cs; ModelFoundationTest.java, ConversationExecutionClientTests.cs; this candidate report. Two consolidated model fixture tests and one cross-capability Desktop contract test were added; original tests were retained/extended. No harness, dependencies, defaults, prompt/budgets, domain persistence/backup, Browser/Finance, WPF/React or Accepted ADR changes. `.verification/mmf-2b-targeted-remediation/incremental-review.patch` records exact content changes from the saved incoming WIP; its manifest records incremental line statistics.

### 16.9 Updated complete review patch

`.verification/mmf-2b-full-review.patch` contains every tracked/untracked candidate source/test/report against the approved HEAD, including preserved original native/WPF/frontend candidates. SHA-256: `.verification/mmf-2b-full-review.sha256`. Original review artifact is preserved under `.verification/mmf-2b-targeted-remediation/prior-full-review.patch`. Complete/incremental patch checks and file statistics are saved in `patch-manifest.json`; no real Git index staging or commits were used.

### 16.10 New risks / deferred acceptance

The exact marker schema and lifecycle above are a narrow new candidate architecture contract, requiring independent review. Mismatched marker after an interrupted selection commit deliberately closes AI; no automatic repair path is introduced. Revision-zero operations requiring an old selection marker must be able to establish a trustworthy selected digest before send. Filesystem power-loss durability remains bounded by existing platform primitives/evidence; GUI/shared-client external completion facts remain explicit trust assumptions. Prior timeout/residency DTO edge review and MMF-3 acceptance remain deferred, not waived.

### 16.11 Final decision

**Targeted remediation Candidate GO for Architecture Guard re-review; NOT APPROVED, publication STOP.** All requested deterministic source blockers are remediated on the retained feature WIP with final focused PASS. Stop here awaiting Architecture Guard; no commit/push/merge, model process management, shared model operation or MMF-3 activity follows automatically.

## 17. Formal Source Approval and Git Publication

2026-10-08 (Asia/Shanghai). Architecture Guard independently reviewed the complete candidate and targeted remediation.
**Final Decision: SOURCE REVIEW — APPROVED — GO; Source blockers: 0.**
**P0 Recovery/Restart: CLOSED; P1 Release Guard Lifecycle: CLOSED; P1 Desktop Error Compatibility: CLOSED.**
Approval covers the existing Runtime/native/Desktop/Settings source, not real Windows/Chrome/Ollama acceptance or MMF-3.

### Approved patch and implementation commit

Approved baseline: `16ee6eb50d982b474f020b33acfd252268d13144`.
Approved complete review patch: `.verification/mmf-2b-full-review.patch`.
Approved SHA-256: `758ae6f2fa358bd1a57e0b5befe7b696890772ecadb14ff7e558bb35c3b7cc2c`.
Exactly 29 approved files, as listed in section 9; implementation commit:
`3e4b5d15872ba00cd96b3c1e98a6a5d38302cd63` (`feat(models): implement approved MMF-2B native model management`).
Its tree is `1ab06525fc3347ad9b5e6dd5b34281c1500288a7`, equal to the approved patch applied to the baseline.

Publication reality gate verified expected feature branch/HEAD, empty initial index, consistent author/committer,
fresh origin/main and local main at the baseline, exactly 29 tracked/untracked candidate changes and byte-identical complete source patch.
No unknown unignored changes or additional AGENTS.md were found. Exact staging was checked by tree identity and
`git diff --cached --check`; ignored review patches, Surefire/TRX, build outputs and private/temporary data were excluded.
Approved production/test content was not modified during publication. ADR-015, Browser and Finance are untouched.

The first publication attempt stopped before staging/committing because proxy fetch failed with TLS handshake error
and direct fetch failed with connection reset. This resumed attempt successfully refreshed origin and repeated all identity gates;
the network stop does not erase or alter the historical source/test failures in sections 10 and 16.7.

### Validation-required implementation supplement approval

Architecture Guard approved Runtime-private `validation-required.json` as a narrow implementation supplement.
Section 16.3's exact six-field v1 schema, 4096-byte strict UTF-8/field/type/version bounds, owner-only/NOFOLLOW rules,
model/digest/revision binding, private atomic publication/readback, crash ordering and fail-closed handling remain the approved contract.
The marker stays independent of selection and execution guard: requiring future native validation does not imply remote execution is unknown.
RECOVER reliably establishes it before clearing a guard; ordinary admission after restart cannot automatically load/probe.
Successful confirmed validation safely removes it before opening admission. Trusted selected Release retains it and selection/revision,
while safely completed guard cleanup remains distinct from unknown outbound completion.
Existing `active-model.json`/`execution-guard.json` v1 schemas and ADR-015 Accepted body remain unchanged.
Filesystem limits and unperformed power-loss/real external-service acceptance described in section 16.3 remain explicit.

### Separate docs-only approval commit and publication boundary

After the implementation commit, a separate docs-only commit updates exactly:
`docs/STATUS.md`, `docs/architecture/current-architecture.md`, `docs/roadmap/V1-ROADMAP.md`,
and this `docs/engineering/MMF-2B-IMPLEMENTATION-CANDIDATE.md`.
It records source approval, approved marker supplement, prior test evidence and unperformed integrated acceptance.
No production/test changes, tags, releases or installation packages are authorized in this step.

The implementation → docs-only approval chain is authorized for fast-forward-only publication to main after another fresh remote/ancestry check.
Actual publication completion and the docs-only commit/remote SHA must be reported from independent post-push remote verification;
this document does not infer remote completion from a local commit or push initiation. The approved review patch remains unchanged.

### Evidence and remaining acceptance gates

Focused tests were **previously reported PASS** in the implementation/remediation; their actual commands, earlier failures and corrections
remain in sections 10 and 16.7. No tests/builds/packaging/inference are rerun in this publication task.
Real Windows/Chrome/Ollama integrated acceptance: **NOT PERFORMED**.
**MMF-3 NOT STARTED; Model Management Overall NOT CLOSED.**
Remaining separately authorized acceptance includes isolated Runtime/token/private roots, native Windows interaction,
two-model/shared-client eviction/Release impact, Browser cache A→B Single/Batch/mixed/revoke/legacy paths,
GPU/residency effects, cancellation/draining/unknown outcomes, operator recovery and restart/failure behavior.
No shared Ollama load/unload/inference or process management, automatic replay, Vision/OCR, W1 Search or Finance activity follows source publication.
After verified publication, stop and await Architecture Guard's independent MMF-3 authorization.
