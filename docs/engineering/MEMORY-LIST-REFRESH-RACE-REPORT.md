# Memory list refresh feedback race

2026-11-09. A bounded Memory UI reliability fix, based on branch `codex/knowledge-version-compare` at `2b2064254475586608756317ec05d03d4c5f24e4`. This does not close the separate desktop startup/Memory acceptance gate.

## User-visible problem and change

A user can refresh the Memory list while editing, then save the editor before that read finishes. Saving is intentionally available during the read. The confirmed mutation triggers a newer list read, which can finish first. The older read's data was already rejected by the list generation guard, but its failure still propagated to the page error, and its success still replaced the "Memory saved" notice with an obsolete refresh notice.

`MemoryPage.fetchList` now returns whether its result was applied. Superseded reads return false and own neither list state nor success/error feedback. Current read failures still clear their unavailable list and propagate normally; confirmed mutations whose current follow-up read fails still report that the mutation succeeded. No automatic retry, extra write, storage/Bridge contract change, or draft replacement was added.

Read the existing roadmap, Memory architecture review and Memory UI/Bridge implementation before selecting this owning-layer fix. No automatic Memory extraction, schema migration, source-provenance claim, model change or unrelated feature was introduced.

## Evidence and validation

Before source edits, `.verification/memory-list-refresh-race-20261109/baseline-2b20642.bundle` was created without writing Git metadata. `git bundle verify` returned 0, confirmed exact HEAD `2b2064254475586608756317ec05d03d4c5f24e4`, and reported complete history. SHA256: `90f077283412c173fda31d115010b1e87bd058a086d4184a8674a7eb56c788a6`.

Two added cases use the actual WorkspaceClient/MemoryService response envelopes. They hold a list request, perform one supported editor update, let the post-save read display the new revision, then deliver the old failure or success. Both failed against the original source: the late error appeared as an alert, or the late success replaced the saved notice. Both scenarios also verify exact saved content, the current revision, a clean editor, one update, zero creates, and exactly the three expected list requests.

After the fix, the entire owning MemoryPage file passed 21/21 tests, including existing draft guards, lifecycle changes, explicit unknown-outcome handling, session rotation and current follow-up-list failure coverage. `npm --prefix desktop/frontend run check` also passed. No full frontend suite, frontend production build, product rebuild, JVM/model, packaging, or additional desktop flow was run for this fix.

Commands and original/final output:

- `npm --prefix desktop/frontend test -- src/pages/MemoryPage.test.tsx -t 'keeps a confirmed save'`: two expected regressions failed before the fix, 19 existing cases skipped.
- `npm --prefix desktop/frontend test -- src/pages/MemoryPage.test.tsx`: 21 passed after the fix.
- `npm --prefix desktop/frontend run check`: passed.
- Logs: `.verification/memory-list-refresh-race-20261109/{regression-before.log,memory-page-final.log,typescript-final.log}`.

The two product/test files are `desktop/frontend/src/pages/MemoryPage.tsx` and `MemoryPage.test.tsx`. This report records the bounded fix; the controller diagnosis report separately records the completed asset-fixed startup observation and its remaining PROFILE_CLEAR_START timeout. Personal skill files, verification state, and the six user-owned untracked documents are outside the proposed commit. Nothing was pushed or published; main is unchanged.

## Remaining limit

The fresh desktop run created a controller successfully but never reached READY while clearing profile data. Therefore the UI race is verified through the owning integrated JSDOM/Bridge tests, not through a real WPF Memory cancel/save/reopen flow. The product frontend bundle was deliberately not regenerated, so existing packaged artifacts do not yet contain this UI fix. See MEMORY-CONTROLLER-DIAGNOSTICS-REPORT.md for the exact startup evidence and next bounded acceptance plan.
