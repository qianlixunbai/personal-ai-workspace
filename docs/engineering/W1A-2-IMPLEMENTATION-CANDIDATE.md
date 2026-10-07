# W1A-2 Native Approval + Typed Bridge implementation candidate

Date: 2026-10-08 (Asia/Shanghai). Implementation authorized by the explicit W1A-2 request.
Complete W1A Architecture Guard review remains required; publication to main is NOT authorized.

Reality gate: command-local proxy fetch succeeded. `main / origin/main` were
`2a0c4c322ea18c8c2dac478c80f929137c45942b`; `origin/codex/w1a1-secure-fetch` was
`c90b9639ba31421b0f601a40c1ca3659368704b0`, strictly ahead by one commit, no divergence.
The clean branch `codex/w1a2-native-approval` started exactly at that approved W1A-1 candidate.
Candidate/remote W1A-2 SHAs are recorded in the delivery handoff (not self-referential here).
ADR-014, status, current architecture, roadmap and W1A-1 candidate were inspected.
Their historical authorization statements are unchanged; this request separately authorizes W1A-2.

## Changed owners

Under `desktop/src/PersonalAiWorkspace.Core/`:

- `WebFetch.cs`: independent raw ASCII canonical target and separate redacted Web DTOs.
- `RuntimeClient.WebFetch.cs`: typed submit/get/cancel using the existing fixed-loopback,
  Bearer, no-proxy/no-redirect HTTP stack, eight-second call deadline and strict duplicate JSON rejection.
- `Contracts.cs`: twelve controlled Web error mappings and fixed safe messages.

Under `desktop/src/PersonalAiWorkspace.Desktop/`:

- `WebFetchConfirmationWindow.cs`: owned native WPF exact-intent dialog.
- `Bridge/WorkspaceWebFetch.cs`: application-owned transient Web session authority.
- `Bridge/WorkspaceBridge.cs`: three exact-field methods, session begin/end and existing stale-response suppression.
- `AssistantApp.cs`: application-owned RuntimeClient/confirmation wiring.

Under `desktop/frontend/src/bridge/`: `webFetch.ts`, `contracts.ts`, `client.ts`.
Only typed client/validators/error codes are added. No page, navigation, approval authority,
browser canonicalization, persistence or frontend history is added.

Tests: added `WebFetchTargetTests.cs`, `RuntimeClientWebFetchTests.cs`, `WorkspaceWebFetchTests.cs`
under `desktop/tests/PersonalAiWorkspace.Desktop.Tests/`; extended `WorkspaceBridgeTests.cs`
and `desktop/frontend/src/bridge/client.test.ts`. No existing tests deleted or full suites added.

## Authority and contracts

`web.fetchSubmit` accepts only `url` (2048 UTF-16 admission ceiling); GET/CANCEL accept
only a canonical operation UUID. Native canonicalization matches Runtime's ASCII hierarchical
HTTPS/443, lowercase LDH/two-label host, single terminal root-dot removal, empty-path `/`,
and conservative suffix/numeric authority/dot-segment/leading-double-slash rejection.
No System.Uri/browser normalization changes the raw escaped path/query: duplicate parameters,
query order, plus and empty query survive. Runtime independently validates again.

The dialog shows the captured full URL and hostname in read-only wrapping/scrollable text boxes,
with the required public Internet / at-most-two same-host redirects / no cross-host statement.
Cancel is focused/default and Escape cancels; closing cancels. Allow once is explicit and disables
its button on consumption. No token, automatic/remembered approval or clickable URL is present.

One pending confirmation globally per application Web owner; cancellation timer and monotonic
post-dialog check enforce a 60-second lifetime. The exact request captures one immutable target.
Session end invalidates authority under the same lock used for approval consumption and direct
Runtime submission initiation. Invalidation winning that lock prevents POST; approval winning may
admit one operation. A replaced session cannot revive an approval or receive a late result.
Existing host failure/shutdown invalidation cancels the dialog/task through that same lifecycle.

Only after Allow and the final current-session/cancellation check does Desktop generate a
non-empty UUID, bind it to that session/target and initiate POST with exactly operationId/url.
Bridge request-ID deduplication and the single pending owner prevent duplicate request/click replay.
No approval means no Runtime POST and consequently no Runtime DNS/public egress.

Uncertain POST outcome triggers at most one read-only GET with the SAME UUID. POST is never
replayed and no replacement UUID is allocated. Failed reconciliation exposes UNKNOWN with that
session-owned ID for subsequent explicit GET/CANCEL. Definite rejection releases ID authority.
Each session retains at most 16 known operation targets; reaching that limit fails closed until
session replacement. No Runtime execution state is cached/duplicated. Replacement clears all IDs,
without transfer, replay or synchronous cancellation of already admitted Runtime work.

DTO parsing validates exact operation/result fields and state/result/error combinations, canonical
requested/final URLs and same hostname, supported MIME and extraction version, UTC acquisition time,
title <=160 scalars AND <=640 UTF-8 bytes, text <=4096 UTF-16 AND <=8192 UTF-8 bytes,
valid Unicode and boolean truncation flags. Owner additionally binds requested URL/hostname to the
approved target on every response. Unknown/duplicate/trailing/malformed/oversized data fails closed.
Sensitive ToString methods are redacted; server messages/phases, DNS/TLS/headers/exceptions and
credentials are never reflected. OutcomeUnknown is Desktop communication state, not Runtime error.

## Actual verification

Executed from repository root unless otherwise indicated:

```powershell
dotnet build desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj --no-restore -v minimal
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter 'FullyQualifiedName~WebFetch|FullyQualifiedName~WorkspaceBridgeTests|FullyQualifiedName~WorkspaceMemoryTests|FullyQualifiedName~WorkspaceConversationsTests|FullyQualifiedName~KnowledgeTests' -v minimal
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter 'FullyQualifiedName~WebFetch|FullyQualifiedName~WorkspaceOperationsTests' -v minimal
# From desktop/frontend:
npm exec vitest run src/bridge/client.test.ts
npm run check
```

Build PASS (includes frontend TypeScript/build via the existing Desktop build target).
Both targeted Desktop commands PASS; second includes the real 60-second expiry, strict malformed
Unicode/time checks and existing WorkspaceOperations regressions. Actual STA WPF dialog verification
checked ownership/full text/default Cancel/cancellation closure/window close. Bridge flow used a
deterministic fake Runtime HTTP handler: trusted intent -> native confirmation interface -> exact
canonical POST -> admission -> typed polling/result -> current session response, plus cancellation,
duplicate denial, replacement races, unknown reconciliation and late-result suppression.
Frontend targeted client tests and TypeScript check PASS. Not every coverage point is an independent execution.

Initial Desktop test compilation failed only for two intentionally unawaited Dispatcher operations;
explicit discard fixed them. Initial frontend Web test emitted a malformed error envelope with an extra
result field; correcting the fixture resolved it. No unresolved failures. No unrelated green owners were
rerun after the final Web-only DTO refinement; no production suites were rerun after this documentation.

Full Java/Desktop/frontend suites, W1A-1 transport matrix, WorkspaceSanity, packaging, public smoke,
model inference, Finance and Browser repository tests were intentionally not run. W1D public-network
and integrated Windows product acceptance remain future gates; STA/mock evidence is not W1D acceptance.

Java W1A-1 production changes: 0. W1A-1 security contract changed: NO.
Search implemented: NO. WebAnswer/model synthesis implemented: NO.
Public Web requests executed: NONE. Finance changes: 0. Browser repository changes: 0.
Main merged: NO. Deviations: NONE. Complete W1A Architecture Guard source review remains required.

W1A-2 IMPLEMENTATION CANDIDATE — READY
W1A PUBLICATION — NOT AUTHORIZED
ARCHITECTURE GUARD COMPLETE W1A REVIEW — REQUIRED
