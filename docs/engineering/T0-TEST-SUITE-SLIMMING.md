# T0 — Test Suite Slimming

Status: **IMPLEMENTED / LOCAL VERIFICATION PASS**; **T0 CLOSING CANDIDATE — GO**.
K2 remains **CLOSED — GO**; K3 remains **NOT STARTED**.
Finance Integration remains BLOCKED pending authoritative Finance Reality Sync.
T0 is an engineering cleanup gate, with no product capability or roadmap expansion.

## Baseline and inherited work

Baseline main/origin/main/HEAD: `07fee3f456e6e6e809afe3798f28813b01a97625`.
Branch: `codex/t0-test-suite-slimming`.
The first review found 75 dirty files (+17/-7,478) and stopped under the original clean-tree gate.
The subsequent read-only review classified the tree **B — MIXED / NEEDS SURGICAL RECOVERY**:
two Java tests lost necessary support, a nonexistent sanity assembly had been granted internal
access, and all historical Windows acceptance sources had been removed without a replacement.
User-supplied historical UI evidence establishes provenance as **KNOWN INHERITED T0 WIP**
from an interrupted Codex implementation, not unrelated user work. Explicit surgical authorization
superseded the clean-tree requirement; the existing slimming was adopted without modification loss.

Repairs restore only `MemoryAskTest`'s shared `@TempDir Path temporary`, and
`TextCapabilitiesTest`'s still-used baseline `submission(...)` helper and class closing brace.
No deleted Java test was restored. One `WorkspaceSanity` project now justifies the existing friend
assembly entry: it drives the real internal `AssistantApp`, window/host/session, isolated credential
store and native focus helpers. Only it and `PersonalAiWorkspace.Desktop.Tests` retain friend access.

## Inventory

Static inventory uses tracked baseline source and surviving/new source, excluding generated
bin/obj/dist/node_modules and historical evidence. Declarations are not expanded execution cases.

| Family | Baseline files | Final files | Baseline declarations | Final declarations | Source LOC before → after |
|---|---:|---:|---:|---:|---:|
| Java tests/support | 21 | 20 | 130 | 127 | 3,463 → 3,379 |
| Desktop tests | 21 | 21 | 170 | 134 | 3,240 → 2,701 |
| Frontend test files | 12 | 5 | 82 | 33 | 727 → 358 |
| Windows acceptance source/project files | 24 | 2 | 12 projects | 1 project | 2,523 → 203 |
| scripts/ files, including release tools/README | 27 | 9 | — | — | 4,886 → 1,062 |

Java has 19 surviving test classes plus `TestSettings`, one Maven test scope; Desktop retains
one xUnit project; frontend retains one Vitest configuration and four page files plus bridge client.
Script total includes five production launch/package files, `release/README.txt`, package safety
checks, privacy audit, and the new sanity runner. Top-level executable scripts: 23 → 5.
Tests/acceptance/support lose approximately **3,312 net source lines**; with script sources,
the net reduction is **7,136 lines**, including the consolidated replacement.
There was no minimum test count, target percentage, or framework symmetry requirement.

## Invariant ownership and removal decisions

| Important invariant | Surviving owner | Retired overlapping protection / decision |
|---|---|---|
| Runtime/provider/task execution, local policy, bounded queue, cancellation and ownership | Java `RuntimeApiTest`, `OllamaProviderTest`, `ProfilePolicyTest`, `TaskManagerTest` | Real-local/Ollama smoke and relay retired; no inference in routine Windows sanity. Prompt output duplication removed from `TextCapabilitiesTest`; API/provider tests retain execution contracts. |
| Local authentication, Browser Translate-only, origin/task-owner isolation, private registry durability | Java API/filter integration and `BrowserClientsTest`; Desktop credentials/pairing fail-closed tests | Browser/Chrome smoke retired; HTTP boundary owns policy. No Browser matrix is repeated in sanity. |
| Memory durability, exact revision selection, stateless Ask versus explicit Memory | Java `MemoryStoreTest`, `MemoryAskTest`, API integration; one real React save in sanity | Memory storage/native/Ask projects and scripts retired; repetitive client CRUD/error matrices removed. Prompt escaping/privacy still exercised by API integration. |
| Conversation durability, ordered context, execution failure, no replay, migrations | Java Conversation tests, API integration, migration tests | Conversation/ConversationsWorkspace projects and scripts retired; redundant client mappings/permutations removed. |
| Memory/Workspace backup truth, atomic publication, corruption rejection, pending-turn exclusion | Java Memory/Workspace backup/publication tests; native file/unknown-outcome Desktop tests | MemoryBackup/WorkspaceBackup projects and runners retired. |
| Knowledge source truth, current READY revision, bounded imports, recovery, independent backup | Java Knowledge foundation/backup; Desktop source-handle/streaming contracts | Full Knowledge import/lifecycle/backup Windows certification retired; sanity imports one source through test-owned RuntimeClient. |
| Lexical deterministic truth, active/current revision, stale/corrupt fail-closed, rebuild/restore parity | Java `KnowledgeLexicalIndexTest`, API integration | Full K2 certification retired. Trivial engine-presence test removed; real lexical queries still exercise bundled FTS5. |
| Bridge/session rotation, late-response authority, strict DTOs, bounded registries, no automatic replay | Desktop WorkspaceBridge/Operations/Memory/Conversations/Knowledge tests | Frontend domain DTO/bridge helper test files retired; generic frontend bridge correlation/session test retained. |
| Destructive lifecycle, native file/dialog ownership and confirmation | Java domain/publication tests; Desktop backup/Knowledge/selection tests; React destructive confirmation tests | Historical native maintenance matrices retired; meaningful confirmation/uncertain outcome protections retained. |
| Privacy-sensitive state clearing, exact input/selection, React composition guard | Four surviving page test files; frontend bridge session tests | App/Settings rendering and low-risk page states, duplicate validators and server-error presentation rows removed. |
| Real WPF/WebView2/bundled React, typed Runtime bridge, physical Pinyin composition Enter | **One WorkspaceSanity flow** | All 12 milestone acceptance projects replaced rather than archived. |
| Package manifest/path/ACL safety; source/content resource admission | Existing `package-tests.ps1`, frontend asset verification, Desktop content-policy tests | Build-check/packaged-product matrices retired. Actual packaged launcher certification is a **release/P1 gate**, not routine T0 coverage; T0 changes no packaging logic. |
| Memory ≠ Knowledge ≠ Finance | Independent Runtime stores/API native-only boundaries and bridge allowlists | No Finance changes, implicit Knowledge/Memory prompt injection, or new product scope. |

Expensive regressions remain: health JSON negotiation, query-race final freshness check,
backup single-generation snapshots, rollback during schema migration/publication, locked/native
source ownership, revoked selection during in-flight admission, late session responses and physical
composition Enter. Removing historical milestone harnesses does not remove these owning tests.

All removed acceptance projects (prefix `PersonalAiWorkspace.`): AssistantTranslateAcceptance,
ConversationAcceptance, ConversationsWorkspaceAcceptance, KnowledgeAcceptance,
KnowledgeSearchAcceptance, MainWorkspaceAcceptance, MemoryAcceptance, MemoryAskAcceptance,
MemoryBackupAcceptance, MemorySettingsAcceptance, ProductAcceptance, WorkspaceBackupAcceptance.

Removed scripts: assistant-translate-smoke.py, browser-security-smoke.ps1,
chrome-get-security-smoke.js, conversation-execution-smoke.py, conversation-storage-smoke.py,
conversations-workspace-smoke.py, desktop-memory-ask-smoke.py, desktop-memory-backup-smoke.py,
desktop-memory-smoke.py, knowledge-search-smoke.py, knowledge-workspace-smoke.py,
main-workspace-build-check.py, main-workspace-smoke.py, memory-settings-workspace-smoke.py,
memory-storage-smoke.py, ollama-smoke-relay.py, packaged-product-smoke.py,
real-local-smoke.ps1, workspace-backup-smoke.py.

Dead support removed: `ConversationSmokeFixture.java`, obsolete acceptance csproj/Program sources,
12 stale friend-assembly entries and helper code used only by removed tests, including unused
Memory/Conversation fake error fields and their branches. Frontend fixtures remain
referenced by surviving page/bridge tests. No solution/package.json/pom/Vitest/CI wiring was changed;
historical acceptance projects were already outside the default solution. Git history is the archive.

## Permanent Windows flow

`WorkspaceSanity` starts the actual AssistantApp/Main Workspace with a unique test credential
target/single-instance suffix, uses bundled production React, saves one synthetic Memory through
React/bridge/Runtime and verifies its persisted snapshot through RuntimeClient. It imports one tiny
Knowledge source via test-owned native RuntimeClient, waits for lexical READY, then physically types
Pinyin. Composition Enter must not submit; committed Chinese plus ordinary Enter must return the
expected source, which is previewed. Reload must rotate the host session and clear query/hits/source.
Normal Desktop shutdown must complete private-profile cleanup and tray cleanup.

The single Python runner uses an isolated verified temp root for Runtime data/auth/logs, a reserved
unlistening loopback Ollama endpoint, the actual JVM executable, and bounded waits. It refuses an
occupied production Runtime port, never reuses/kills unrelated Runtime processes, and cleans only
PIDs it started and its verified temp directory. Progress/evidence contains fixed labels, not secrets
or source/query content; temporary logs are deleted. Java 21/.NET 10, WebView2, an interactive
Windows desktop and installed zh-CN Pinyin are required. No release bundle or model is required.

## Verification and current commands

Focused development checks actually executed: WorkspaceSanity Release build (including normal
frontend TypeScript/build/asset verification), PASS with zero warnings/errors; runner Python AST
syntax check, PASS. No historical acceptance or test matrix was executed.

Final verification actually executed on 2026-10-06 (+08:00):

| Execution | Result |
|---|---|
| Java surviving suite, once | **132 PASS**, no failures/errors/skips; Maven BUILD SUCCESS. Runtime classpath generated in the same invocation. |
| Frontend surviving suite, once | **38 PASS**, no failures/skips, 5 test files. |
| Desktop initial attempt | Compilation stopped before discovery/execution: CS0649 for two fake fields only assigned by deleted tests. |
| Desktop focused repair check | **42 PASS**, the two affected WorkspaceMemory/WorkspaceConversations classes only, after removing dead fields/branches. |
| Desktop surviving full suite, once actually executed | **194 PASS**, no failures/skips; reused the compiled test assembly with `--no-build`. |
| WorkspaceSanity, exactly one integrated execution | **PASS**: production startup/connected bridge, real Memory save, source import/index READY, genuine physical Pinyin composition Enter denial, committed Chinese lexical hit/preview, replacement session/query/source clearing, normal Desktop/profile/tray cleanup. Owned Runtime stopped and temp files removed. |

The focused checks are separate executions and are not added to the final suite counts. Baseline
published full-suite results were Java 134, Desktop 273 and Frontend 119: historical K2 evidence,
not rerun during T0 inventory. The final baseline source includes the additional query-race test
introduced by `800a8dc` after that Java full run (and tested separately during K2 recovery).
Baseline Java source therefore represents 135 expanded cases by static parameter inspection;
T0 removes three declarations, giving 132 actual final cases. No historical result is rewritten.

```powershell
.\mvnw.cmd test dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/workspace-sanity-classpath.txt'
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj -c Release -p:FrontendSkipBuild=true
npm --prefix desktop/frontend test
python scripts/workspace-sanity.py --no-build
```

`--no-build` reuses the compiled sanity project/current assets and Maven Runtime classpath.
On a fresh checkout, `python scripts/workspace-sanity.py` builds its prerequisites without running
automated suites or producing a release package. It is one flow, not a coverage-point matrix.
Package checks remain optional release work and are not part of these routine commands.

No executable production behavior, schema, API, backup format, Browser/security rule, lexical
semantics, bridge contract or Finance file changed. The sole production-source diff is the required
friend-assembly metadata. No full production publish/package/launcher matrix is warranted.
No extra standalone production build was executed: frontend/.NET compilation was part of the
sanity/test workflows, and Java compilation/classpath resolution part of the single Maven invocation.
No old acceptance matrix, real inference, stress suite, release publish or package test was rerun.
Limits: this flow is representative integration, not full release certification, and requires a usable
interactive desktop/Pinyin. Deferred historical certification is not claimed as T0 execution evidence.
T0 closing belongs to Architecture Guard's independent review; the implementer must not declare CLOSED.
