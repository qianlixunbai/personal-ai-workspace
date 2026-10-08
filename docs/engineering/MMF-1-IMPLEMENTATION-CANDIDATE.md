# MMF-1 Runtime Foundation — Implementation Candidate

Date: 2026-10-08 (Asia/Shanghai). Authority: user's separate **MMF-1 ONLY** implementation authorization.
**Architecture Guard prior source decision: STOP / CHANGES REQUIRED. Findings A/B remediated below; candidate GO for re-review only, source approval PENDING. MMF Overall NOT CLOSED.**

## 1. Git Reality Gate

- Workspace: `D:/IDEA/Daima/personal-ai-workspace`, remote `qianlixunbai/personal-ai-workspace`.
- Initial `main / HEAD / fresh origin/main`: `7e97a95dbdd85d4c055fb5aa8128132d1d340a21`; clean working tree.
- Executed `git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 fetch --prune origin`: PASS. No fallback or global configuration changes.
- No additional AGENTS.md found in repository/ancestor instructions; followed the user's conservative implementation/testing/Git rules.
- ADR-015 is Accepted. Read its owning contracts and current STATUS, roadmap, architecture and owning code; no new architecture review.
- Created `codex/mmf-1-runtime-foundation` from the verified baseline. No reset, existing-history change, commit, push or merge.
- W1 remains PAUSED; Finance Integration remains BLOCKED. No sibling/Finance modification or sibling reality audit.

## 2. Implementation Scope

Runtime foundation only: Active Model owner, strict private selection store, positive local admission, immutable reservations,
execution uncertainty marker and package-private internal switch validation/commit/publication.
No user-facing switch/Release/recovery mutation, native confirmation UI, effective Browser epoch, Vision, downloads, tools,
process control, external model cleanup, new provider framework or domain persistence changes.

## 3. Runtime Ownership Changes

`ActiveModelManager` is the sole lifecycle/switch/selection authority. Its short gate protects selection/switch ownership and
bounded outstanding reservations; guard IO uses a separate serialized operation boundary. Network and file IO run outside the model gate.
`ProviderRegistry`, capability prompts, temperature, budgets, locality policy and business output validation remain in their existing owners.

Each reservation freezes model identity/digest/revision, capability parameters and prompt version. Worker execution uses that record,
not a current-selection lookup. YAML bootstrap uses actual bound configuration, normalizes the supported local/default-library forms,
and rejects inconsistent models. Revision 0 is never written; bootstrap never changes YAML or creates selection.
The first AI admission performs bounded, non-private text validation for the configured selection. Readiness itself only reads metadata.

Internal status distinguishes configured, installed (True/False/Unknown), Active, ready and reserved/queued/running/draining.
`loaded` remains **Unknown**: no residency observation was implemented or fabricated. Ready is false during switch, storage failure or uncertainty.
Legacy metadata availability can be true before the initial text validation; it is distinct from internal execution-ready status.

## 4. Local-only Admission Evidence

Fixed supported API release: **Ollama v0.40.0**, exact version match. Primary source inspected:

- [API types](https://github.com/ollama/ollama/blob/v0.40.0/api/types.go): tags/show/chat fields and completion protocol.
- [Model listing](https://github.com/ollama/ollama/blob/v0.40.0/server/model_list.go): manifest digest, local format, runner/context and remote fields.
- [Routes](https://github.com/ollama/ollama/blob/v0.40.0/server/routes.go): show generation, chat source routing and completion responses.
- [Model source parsing](https://github.com/ollama/ollama/blob/v0.40.0/internal/modelref/modelref.go): explicit `:local`, `:cloud` and `-cloud` semantics.
- [Manifest metadata](https://github.com/ollama/ollama/blob/v0.40.0/manifest/manifest.go),
  [model Modelfile generation](https://github.com/ollama/ollama/blob/v0.40.0/server/images.go),
  [Modelfile rendering](https://github.com/ollama/ollama/blob/v0.40.0/parser/parser.go): reviewed local runner/blob source forms.

The implemented subset requires unique exact installed identity; valid normalized manifest SHA-256; tags → show → tags agreement;
explicit completion; supported GGUF architecture (`llama`, `qwen2`, `qwen3`, `qwen35`, `qwen35moe`);
context sufficient for every enabled profile; positive artifact size; local absolute `blobs/sha256-…` FROM; and no remote/cloud routing.
Only absent/empty, `ggml` or `llamacpp` runner metadata is admitted. Unknown versions, architectures, source shapes,
multi-manifest/projector/adapter/draft forms, ambiguous identities and remote indicators fail closed.
Manifest digest and blob digest are different bindings; returned paths are parsed as metadata and never opened.

Both show and inference requests use the reviewed `:local` source directive. Canonical execution identity remains the installed name;
chat completion must match the exact original transport model reference. Name, localhost and tags presence alone never establish local source.
Loopback/no proxy/no redirects, existing LOCAL_ONLY policy and tool-call rejection remain.

Evidence: deterministic `OllamaFixtures`, `ModelFoundationTest`, real loopback HttpClient parsing in `OllamaProviderTest`.
Fixtures contain synthetic names/digests/paths, not downloaded weights or a real user's metadata/prompts.
**Actual installed default-model metadata compatibility: PASS after targeted remediation (§15).** Only version/tags/show were queried; no shared inference, load, unload or switch. The original implementation rejected the real minimum-version field; that rejection is now fixed against pinned official source.

## 5. Selection Store / Recovery Evidence

`workspace.model-state-directory` defaults to `${user.home}/.personal-ai-workspace/model-state`; only Runtime startup configuration selects it.
It cannot overlap credentials/data or reside in the project. JSON, pending, guard and stable `.lock` are private and independently owned.
Selection schema is exact v1, ≤4096 bounded bytes, strict UTF-8, no BOM/unknown or duplicate fields/trailing tokens/null/type coercion;
revision is integer 1..9007199254740991. Guard schema is exact v1 `unresolved=true`, ≤128 bytes, no identity/prompt/token/result.

OS lock excludes another writer. Publication uses CREATE_NEW private pending → write/force → revalidate → ATOMIC_MOVE replacement → readback;
there is no truncate/delete-then-move fallback. Revision CAS precedes commit; in-memory selection is published afterward.
Valid committed selection is authoritative; pending is never promoted. Missing selection plus pending blocks bootstrap.
Malformed state, ownership/ACL/type/link errors and external changes close AI management without failing healthy domain beans.

Windows Java 21 reports null fileKey. The Windows implementation therefore pins lock/selection/live guard with
`NOSHARE_DELETE`/`NOSHARE_WRITE` handles, checks private ACL/owner, resolved path and creation identity, and only releases selection pin
for its own atomic replacement. POSIX uses private modes and file keys. Neither promises protection against administrators or malicious same-account races.

Actual Windows filesystem fixtures PASS: private creation, denied unsafe ACL, stable single writer, root rename denial,
external selection-write denial, strict encoding/schema/bounds, CAS, atomic replacement, restart, pending non-promotion,
regular-file type rejection and deterministic pre-rename failure retaining the old committed selection.
Malformed/crash guard artifacts remain unresolved. POSIX execution and native junction/symlink fault fixtures were **not run**.
Abrupt power-loss and post-rename crash durability on target filesystems remain **PENDING**; normal restart PASS is not that evidence.

## 6. Task Reservation / Concurrency Evidence

Ordinary text, Memory Ask, Knowledge Answer and Conversation use the same model reservation. Knowledge packing checks one frozen profile.
Conversation reserves before profile/context validation and durable USER Turn creation; switching/uncertainty rejection creates zero new USER Turns.
TaskManager retains its executor, bounded queue, budgets, deadlines, completion persistence and retention behavior.
It only gains lease lifecycle callbacks outside its monitor. Queued cancellation decides ownership under the existing monitor;
running cancellation marks drain and retains the lease until worker/provider/guard cleanup exits. Terminal retained results do not block a switch.

One controlled integrated fixture exercises reservation blocking switch; stale revision/digest refusal with zero load;
one switch owner while a concurrent Conversation is rejected; immutable task identity; queued removal;
cancelled running drain after trusted completion; no late result revival; commit/restart; and precommit publication failure.
Existing focused task/conversation/capability tests were also executed. Lock ordering was inspected in source;
no broad stress/race matrix is claimed.

## 7. Execution Uncertainty Guard Evidence

Before inference transport send, the owner atomically writes and forces `execution-guard.json`. Failed guard publication produces zero inference send.
An aggregate marker covers concurrent outbound leases; guard create/clear is serialized independently of gate/task/domain monitors.
Provider protocol completion is recorded before business content/budget validation. Only trusted completion plus local operation exit clears a safe lease.
Untrusted/truncated/error/cancelled outbound operations report uncertainty before terminal task publication and retain the private marker.
Lease removal occurs after guard IO, so switch cannot observe an exit gap. Another lease's success cannot clear an unresolved operation.

The candidate switch lease/marker spans load, durable commit and Active publication. Precommit failure after candidate load retains the marker
and requires confirmation before old-selection recovery load; restart cannot silently retry that interrupted lifecycle.
No automatic replay, timer-based recovery, ps-based proof, restart-based clearing, external kill/restart or public reset is implemented.
Internal status provides opaque recovery generation and local lease counts for MMF-2 arbitration; native recovery consumption remains future work.

Actual fixtures PASS: no-send marker failure; trusted completion with invalid business output; outbound malformed completion STOP;
real HttpClient cancellation/late-response suppression; all-new-AI/switch refusal; independent healthy domain operations;
guard and guard-pending preservation across restart. There is no remote-stop claim.

## 8. Browser Compatibility Preservation

No sibling files, permissions, pairing or credential storage changed. Browser remains Translate-only.
Legacy readiness shape and `profile.version` stay unchanged; readiness performs metadata only, then rechecks the gate/snapshot.
No effective epoch or identity-v1 feature is enabled. No native/local HTTP/React bridge exposes cross-model switch/Release/recovery.
Actual HTTP fixtures verify management route denial/unavailability, stable public profile version and no real model in public task/readiness output.
These are preservation checks, **not PASS for ADR §7's future cross-repository cache-switch acceptance**.

## 9. Files Changed

Java paths below share `src/main/java/io/github/qianlixunbai/workspace/`:

- New: `model/ActiveModelManager.java`, `model/ModelStateStore.java`, `model/ModelConfiguration.java`, `provider/ollama/OllamaModelAdmission.java`.
- Changed: `capability/TextTaskSubmission.java`, `capability/ask/MemoryAskService.java`, `capability/knowledge/KnowledgeAnswerService.java`,
  `conversation/ConversationExecution.java`, `task/TaskManager.java`, `provider/Provider.java`, `provider/ollama/OllamaProvider.java`,
  `health/ProviderHealthController.java`, `common/ErrorCode.java`, `common/ApiError.java`, `api/ApiExceptionHandler.java`.
- Configuration: `src/main/resources/application.yml` (one private state-root property).

Test paths share `src/test/java/io/github/qianlixunbai/workspace/`:

- New: `model/ModelFoundationTest.java`, `provider/ollama/OllamaFixtures.java`.
- Changed: `TestSettings.java`, `provider/ollama/OllamaProviderTest.java`, `api/RuntimeApiTest.java`,
  `capability/TextCapabilitiesTest.java`, `capability/MemoryAskTest.java`, `knowledge/KnowledgeAnswerTest.java`, `conversation/ConversationExecutionTest.java`.
- Existing fake-provider tests isolate the new owner with mocks. Real admission/store/guard use the deterministic integration fixtures.
- API fixtures now explicitly isolate credentials/data/model-state. Obsolete continuation after lost outbound completion was consolidated
  into the new uncertainty flow; batch business-output/revoke behavior remains covered by its existing integrated flow.

Docs: this candidate report, `docs/STATUS.md`, `docs/architecture/current-architecture.md`, `docs/roadmap/V1-ROADMAP.md`.
ADR-015's Accepted approval record is unchanged. No domain schemas, backup formats, Desktop, Frontend, sibling, Finance or dependency file changed.

## 10. Focused Tests — Historical Initial Implementation Execution

All commands used the Maven Wrapper and Java 21. These are executed results, not planned coverage points:

| Command / selection | Actual result |
| --- | --- |
| `mvnw.cmd -q -DskipTests compile` and subsequent focused test compilation | PASS |
| `-Dtest=ModelFoundationTest` (three integrated foundation flows) | PASS after fixes; later extended flows run as below |
| `-Dtest=OllamaProviderTest,TaskManagerTest,ConversationExecutionTest,TextCapabilitiesTest,MemoryAskTest,KnowledgeAnswerTest` | PASS during implementation |
| `-Dtest=ModelFoundationTest,OllamaProviderTest,RuntimeApiTest#modelFoundationBrowserCompatibilityAndUnavailableDomainOperations+runtimeHealthRemainsIndependentOfOfflineProvider+nativeTranslateContract+batchTranslateContract` | PASS after pinned runner/source and metadata-only readiness changes |
| `-Dtest=TaskManagerTest`, then `-Dtest=ModelFoundationTest#oneSwitchOwnerFrozenTasksQueuedCancellationDrainAndZeroRejectedUserTurn` | PASS after final worker cleanup ordering change |
| `-Dtest=RuntimeApiTest#modelFoundationBrowserCompatibilityAndUnavailableDomainOperations+runtimeHealthRemainsIndependentOfOfflineProvider` | PASS after final readiness/status error handling |
| `-Dtest=ModelFoundationTest#localAdmissionDigestDriftTrustedCompletionAndUncertaintySurviveRestart` | PASS after adding no-send guard-publication/pending-restart evidence |
| `git diff --check` | PASS |

Initial FAILs were corrected: test cleanup incorrectly used TaskManager as AutoCloseable; Windows null fileKey was rejected;
lease counts reached zero before guard cleanup ended. Final focused reruns above are green. Mockito emitted existing JDK dynamic-agent warnings.
The earlier green mock-owner suites were not mechanically rerun after Ollama/readiness-only fixes or docs edits.
No Java full suite, Desktop, Frontend, Browser full regression, packaging or real Windows GUI/Chrome/shared Ollama acceptance was run.

## 11. Remaining MMF-2 Dependencies

- WPF frozen exact-intent confirmation: precise candidate/old selection/action/revision/session, automatic eviction impact,
  default Cancel, lifetime/session invalidation, one-shot consumption, late-response suppression and native authority validation.
- Native Settings typed private status/catalog/selection integration; authenticated server operation identity and status reconciliation.
  This candidate's package-private switch must remain unreachable until those gates are implemented and reviewed.
- Explicit single-model Release and separately confirmed old-selection/recovery load, preserving uncertainty/local-drain checks.
- Native recovery challenge consumption with current generation, zero local leases, exact revision/digest and fresh metadata;
  user confirmation of the external service boundary. No public bypass may be added.
- ADR §7 readiness `cacheIdentityVersion=1`: strict bounded opt-in, no-store identity for Single/Batch, opaque per-start/publication/config epoch,
  unavailable/switch/uncertainty suppression and gate-coherent snapshot. TaskView effective version must freeze with the same reservation.
- Sibling Browser rollout before switch exposure: readiness-before-cache lookup, both-mode invalidation, selection/mixed-hit freshness,
  identity-stable polling/render, legacy Runtime cache bypass and legacy Browser unavailable guard. Pairing/scopes remain unchanged.
- Explicit isolated model-state startup configuration in every future Windows/Browser acceptance harness before it runs.
- Independently authorized real Windows + Chrome A-cache → native B-switch acceptance, including cancellation/recovery/shared-client effects.

## 12. Risks / Deferred Work

Fixed-version narrow admission deliberately rejects unsupported local formats/architectures/runners/projectors and unverified versions;
the installed default `qwen3.5:4b` passed actual read-only metadata admission in §15. Text execution and Vision compatibility on the shared server are not claimed. No model, default prompt or capability budget was changed to compensate.
Metadata is a trusted external-service policy observation, not signed artifact authentication or OS network isolation.
Explicit local source improves cloud-routing refusal; final name-based invocation still cannot atomically pin a digest against concurrent local replacement.
Failed candidate loading can affect shared residency through Ollama automatic eviction; old durable selection is not a promise of old residency.
`loaded=Unknown`, native recovery UI, full residency/status UX and broader platform/version acceptance remain deferred.
Uncertainty intentionally suspends AI until a future approved recovery path; restarting the Runtime does not bypass it.
Abrupt power-loss, post-rename crash injection, POSIX execution and native link/junction fault acceptance remain PENDING.

## 13. Final MMF-1 Implementation Candidate GO / STOP

**Initial implementation candidate decision (historical): GO for source review.** Architecture Guard subsequently returned STOP / CHANGES REQUIRED; the targeted remediation and current re-review handoff are recorded in §15.
This is an implementation candidate, not source approval, a release or MMF closing. MMF-2/MMF-3 are NOT STARTED.
Stop here for the independent review; do not publish or enable user-facing cross-model operations.

## 14. Feature Branch / HEAD / Diff / Working Tree

- Branch: `codex/mmf-1-runtime-foundation`.
- HEAD, local main and fetched origin/main ref: `7e97a95dbdd85d4c055fb5aa8128132d1d340a21` (no new commit).
- Working tree: only this task's production/test/docs candidate changes; unstaged, including new untracked source/report files.
  Intentionally retained for review because no commit/push/merge authorization was given.
- Complete review patch (tracked changes plus new files): `.verification/mmf-1-review-1l0_ctyf/mmf-1-runtime-foundation.patch`.
  The ignored review artifact does not enter production or change staging.
- Initial implementation made no real Active selection/token/data access, shared Ollama calls, model downloads or external service lifecycle actions. The later remediation used only explicitly authorized read-only metadata APIs (§15).

## 15. Architecture Guard Targeted Source Remediation — 2026-10-08

### 15.1 Git Reality Gate

Incoming and final branch: `codex/mmf-1-runtime-foundation`; HEAD remains
`7e97a95dbdd85d4c055fb5aa8128132d1d340a21`, exactly the supplied baseline.
Incoming candidate was already dirty (tracked MMF-1 edits plus untracked owners/tests/report); all inputs were preserved.
Repository/ancestor instruction scan found no additional AGENTS.md. ADR-015 Accepted remains the sole architecture contract and is unchanged.
No fetch was needed for this local source repair; no fresh remote-truth claim is made. No reset/clean/checkout, real-index staging,
commit, push or merge. Final tree deliberately remains unstaged for review; no clean-tree claim.

### 15.2 Finding A — Root Cause and Exact Fix

`switchInternal` set `recoveryLoadRequiresConfirmation=true` and discarded Ready before read-only candidate validation.
Its runtime-error branch had no path to restore the old selection's usable state. The old integrated test also asserted that bug.

Switch begin now captures the old Ready/installed/loaded/error state and closes admission through the existing `switching` gate.
The immutable candidate-operation reservation marks possible load/eviction only at `beforeSend`, after durable guard arming,
immediately before transport send. A metadata/digest rejection with no candidate send restores the captured state only while
there is no prior recovery requirement, uncertainty, storage failure or owner closure. CAS refusal precedes state mutation.
No selection/revision/snapshot is changed by rejection and no old-model reload is issued.

Candidate send followed by trusted completion but failed text validation still leaves old residency Unknown, Ready false,
confirmation required and the lifecycle guard intact. Unknown outbound completion still closes AI/switch gates and survives restart.
Successful commit/publication and immutable task/profile/revision semantics are unchanged. No public mutation API was added.

One deterministic regression now proves durable A revision 1 Ready -> B metadata and digest refusal -> zero B chat/load,
unchanged selection bytes/status -> A accepts and executes a normal request without a new probe.
The same flow then proves a B probe with trusted completion but unusable output retains strict old-selection recovery and restart STOP.
The existing loaded-candidate precommit failure, outbound uncertainty/restart and cancelled-but-draining switch refusals remain green.

### 15.3 Finding B — Actual Installed Metadata Result

Shared endpoint calls were limited to GET `/api/version`, GET `/api/tags`, POST `/api/show` with
`model=qwen3.5:4b:local`, `verbose=false`. The revised production `OllamaProvider.admitLocal` was exercised directly,
without constructing an ActiveModelManager, selection store or token owner. No chat/generate/preload/warm/unload/pull/config/process operation.
Raw responses stayed in memory; only redacted shapes/controlled validation results were retained. No full Modelfile or real blob path recorded.

| Checked fact | Observed safe shape / result |
| --- | --- |
| Supported version | `0.40.0`, exact version gate PASS |
| Canonical installed identity | Unique `name=model=qwen3.5:4b`, PASS |
| Manifest binding | Lower-case 64-hex digest; tags before/after agree, PASS; actual digest redacted |
| Details/families | GGUF, family `qwen35`, singleton families; empty parent, PASS |
| Context | `qwen35.context_length=262144`; tag context agrees and exceeds unchanged 8192 budgets, PASS |
| Projector/manifests | `projector_info` and `manifests` absent; no new projector shape enabled |
| Capabilities | `completion`, `vision`, `tools`, `thinking`; completion PASS; Workspace Vision remains unauthorized |
| Positive local source / runner | One absolute local blob FROM with valid blob hash shape; tag runner `ggml`, show runner absent; remote/source/routing absent, PASS |
| Minimum version | `requires` string `0.17.1`; original validator FAIL (`POLICY_DENIED`), revised production validator PASS |

Pinned [api/types.go](https://github.com/ollama/ollama/blob/v0.40.0/api/types.go#L723) defines Requires as the minimum Ollama version;
[server/routes.go](https://github.com/ollama/ollama/blob/v0.40.0/server/routes.go#L1861) returns `m.Config.Requires`;
[server/images.go](https://github.com/ollama/ollama/blob/v0.40.0/server/images.go#L1368) compares that minimum against the client release.
The narrow repair accepts only absent/empty or canonical stable numeric triples <= the exact supported release.
Null/wrong types, malformed/prerelease/unknown/future values still fail closed; all existing local-source/digest/remote/runner checks remain.
No general semver dependency or broader source/architecture/projector compatibility was introduced.

The redacted `src/test/resources/ollama/qwen35-local-v0.40.0.json` preserves the relevant real structural fields,
context, runner, capabilities and requires; hashes/path are synthetic and content/tokenizer data are omitted.
One provider integration regression validates it through real loopback HTTP parsing, with future/malformed version and remote/unknown denial,
and zero fixture chat calls. Actual metadata admission is not an inference-quality, residency or Vision acceptance claim.

### 15.4 Source Diff Summary

Relative to the incoming candidate, only these files changed:

- Production: `model/ActiveModelManager.java`, `provider/ollama/OllamaModelAdmission.java`.
- Tests: `model/ModelFoundationTest.java` (one consolidated new regression plus corrected old expectation),
  `provider/ollama/OllamaProviderTest.java` (one real-shape admission regression), one new redacted JSON fixture.
- Documentation: this candidate report, including correction of stale pending/default-compatibility statements.

No TaskManager/Conversation/API production edits this round. No ADR, Browser, Desktop, Finance, dependency, profile/version,
public readiness shape, persistent schema, real Active selection/token/Memory/Knowledge changes.

### 15.5 Focused Tests Actually Executed After Final Production Revision

Java 21 / Maven Wrapper; actual commands (all PASS, no failures/skips):

```text
mvnw.cmd --batch-mode --no-transfer-progress -Dtest=ModelFoundationTest,OllamaProviderTest,ConversationExecutionTest,RuntimeApiTest#modelFoundationBrowserCompatibilityAndUnavailableDomainOperations+runtimeHealthRemainsIndependentOfOfflineProvider+nativeTranslateContract+batchTranslateContract test
mvnw.cmd --batch-mode --no-transfer-progress -Dtest=TaskManagerTest test
```

The focused foundation evidence covers YAML bootstrap without selection write, metadata-only old Active preservation,
loaded-candidate recovery/guard retention, outbound-unknown restart STOP and cancelled drain blocking switch.
The selected HTTP evidence confirms legacy profile.version/readiness, Translate contracts, Browser authorization and unavailable model-management routes.
`git diff --check` PASS. No Java full suite, Desktop/Frontend/Browser matrix, packaging or shared-server execution acceptance was run.
Initial implementation results in §10 are historical, not counted as this round's execution.

Local evidence: `.verification/mmf-1-remediation/focused-tests.log`, `task-manager-tests.log`,
`actual-before-admission.log` (controlled FAIL) and `actual-after-admission.log` (PASS).

### 15.6 Remaining Security and Recovery Limitations

Local-only admission trusts supported external Ollama metadata, not signed weights or OS network isolation.
Final name-based egress still cannot atomically pin a digest against concurrent external replacement.
Loaded remains Unknown; candidate loading can evict shared models. Failure after send retains recovery confirmation/guard;
unknown outbound or surviving guard remains STOP across restart. No automatic old-model reload or guard-clear bypass was added.
Native exact-intent/recovery and Browser freshness gates remain deferred and internal switching remains package-private/unexposed.
Real shared text inference, Vision, Windows GUI recovery and broad version/platform acceptance were not executed.

### 15.7 New Review Patch Paths

Complete baseline-to-candidate patch (including all incoming candidate edits and untracked files):
`.verification/mmf-1-remediation/mmf-1-runtime-foundation-review.patch`.
Incoming-candidate-to-remediated-candidate delta:
`.verification/mmf-1-remediation/mmf-1-targeted-remediation.patch`.
Patch generation/checking uses temporary alternate Git indices and leaves the real staging area unchanged.

### 15.8 Final MMF-1 Source Candidate GO / STOP

**GO for Architecture Guard re-review of the MMF-1 source candidate.** Findings A/B are resolved with focused and actual read-only evidence.
This is not Architecture Guard source approval, publication, MMF closing or authorization to begin MMF-2/MMF-3/Vision/W1.
Stop here and wait for independent re-review.
