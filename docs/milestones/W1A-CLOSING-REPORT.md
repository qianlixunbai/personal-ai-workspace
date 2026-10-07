# W1A — Secure Fetch Foundation + Native Approval — Closing Report

Date: 2026-10-08 (Asia/Shanghai). Repository: `qianlixunbai/personal-ai-workspace`.

Architecture Guard decision: **W1A-1 SOURCE REVIEW — APPROVED — GO**;
**W1A-2 SOURCE REVIEW — APPROVED — GO**; **COMPLETE W1A SOURCE BLOCKERS — 0**;
**W1A PUBLICATION — AUTHORIZED**. Implementation is complete.
**W1A — CLOSED — GO; FORMAL PUBLICATION — COMPLETE** takes effect only after the entire
W1A chain and this documentation commit are successfully published to main.
**W1 OVERALL — NOT CLOSED**. This report does not close W1 or authorize future implementation.

## Baseline and publication boundary

- Original main / origin-main: `2a0c4c322ea18c8c2dac478c80f929137c45942b`.
- Approved W1A-1: `c90b9639ba31421b0f601a40c1ca3659368704b0`, branch `codex/w1a1-secure-fetch`.
- Approved W1A-2: `8782e7365cbc4e28df849bab3ddc03359ac96a0a`, branch `codex/w1a2-native-approval`.
- Fresh command-local proxy fetch verified all three expected remote SHAs, clean worktree,
  and W1A-2 strictly ahead of main by two commits without divergence.
- Closing adds exactly four documentation files/updates on W1A-2. Publication uses a normal
  main fast-forward after another fresh baseline/divergence check. No merge commit, amend,
  rebase, squash, force push, PR, tag or release; both feature branches remain intact.
- Closing/final local/remote main SHA and completed publication verification are recorded
  in the delivery handoff; this commit cannot contain its own SHA.

## Delivered scope and preserved boundaries

W1A-1 owns Runtime public policy, independent target validation, secure HTTPS GET transport,
bounded DNS public-address classification/connection pinning/TLS, same-host manual <=2 redirects,
bounded HTML/plain/XHTML extraction, transient native-only Fetch operations and controlled errors.
W1A-2 owns independent native canonicalization, owned WPF exact-intent confirmation, one pending
intent with 60-second expiry, one-time approval/current-session recheck, typed RuntimeClient
submission/poll/cancel, session-owned operation IDs, same-ID read-only uncertainty reconciliation,
strict DTO/error validation and typed allowlisted bridge/client support. No final Web page.

React has no approval/credential authority; cancellation means no Runtime admission or public
egress. Session replacement invalidates pending approval and clears read/cancel authority;
late results cannot authorize a replacement session. Runtime remains public execution truth.
No automatic Web, model tool calling, Search implementation, WebAnswer/model synthesis or citations.
No Web history/persistence, domain schema/backup change, Provider/TaskManager change or generic agent framework.
Ask/Conversation/Knowledge boundaries, Browser Translate-only and Finance BLOCKED pending F0 remain.

Accepted [ADR-014](../ADR/ADR-014-controlled-web-access.md) and the historical
[W1A-1 candidate](../engineering/W1A-1-IMPLEMENTATION-CANDIDATE.md) /
[W1A-2 candidate](../engineering/W1A-2-IMPLEMENTATION-CANDIDATE.md) are preserved unchanged.
Their earlier pending-review/publication statements describe their original candidate stage;
the source approval and publication authority above supersede them for W1A.

## Evidence and remaining gates

Inherited evidence only: W1A-1 reports focused Runtime policy/transport/service/API fixtures;
W1A-2 reports Desktop/bridge/client focused verification, deterministic fake local Runtime HTTP
integration, STA native dialog checks and real 60-second intent expiry. Source review approved
both candidates; this closing task does not rerun or claim new execution evidence.

Closing verification: docs-only diff and `git diff --check`; **builds/tests/inference/public Web
requests executed: NONE**. Production, tests, dependencies, scripts, Browser and Finance changes: 0.
**Public Internet smoke / integrated Windows product acceptance — NOT PERFORMED**;
local fixtures/STA evidence do not constitute W1D acceptance.

Next: **W1B — Search Backend Review** (backend unselected; review precedes any implementation authorization).
**W1C — PENDING**: WebAnswer, frozen evidence, Runtime-owned citations and final Web UI.
**W1D — PENDING**: integrated Windows/public-network acceptance and overall W1 closing review.

W1A — CLOSED — GO
FORMAL PUBLICATION — COMPLETE
W1B SEARCH BACKEND REVIEW — NEXT
W1 OVERALL — NOT CLOSED
