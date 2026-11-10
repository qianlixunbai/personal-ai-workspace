# Memory editor-state notification race

2026-11-10. Baseline `047d11aeb11a4a673bb35e918ce7824395cdda90`, branch `codex/knowledge-version-compare`.

## User-visible problem

A native dirty-state notification can still be pending when an explicit Memory Save succeeds. The editor then queues a clean-state notification. Previously, if the earlier dirty notification failed later, its catch checked only the bridge session generation and displayed “原生未保存状态保护未能确认” on the now-saved editor. A later successful clean notification did not remove that stale warning. The successful Save and draft were not undone, but the feedback contradicted the current operation.

The synthetic frontend regression holds the first dirty notification, performs an ordinary Save through the existing Memory service fixture, then rejects the held notification. Before the fix, it fails specifically because an alert remains after Save. It is not a syntax/setup failure and is not evidence of real desktop persistence or native acknowledgement behavior.

## Minimal fix

Each desired editor-state notification receives a local sequence number. A failed notification may update the error only if both its existing session generation and its notification sequence are current. Native notifications remain in their original serialized queue; none is skipped, reordered, retried, or reported as acknowledged by this change.

Current notification failures still display the existing warning. Session/unmount isolation, Save/create/update handling, dirty/leave confirmations, native close protection, unknown-write-outcome handling and MANUAL provenance are unchanged. This is a feedback ordering fix, not a guarantee that native protection has acknowledged a notification.

## Focused verification

- Before the production change: the one new regression failed at the intended no-stale-alert assertion (1 failed, 23 skipped). Original log retained.
- After the fix: MemoryPage 24/24 passed. The same new case then fails a current dirty notification and confirms its warning remains, the unsaved body is retained, the saved body stays unchanged in the synthetic service, and only one memory.create was issued.
- Notification calls in that case remain `[true, false, true]`, preserving the existing queue behavior.
- TypeScript `tsc --noEmit` and diff checks passed. No other test file or suite was run.

Evidence: `.verification/memory-editor-state-race-20261110/regression-before.log`, `regression-before.json`, `focused-after.log`, and `typescript-after.log`. The rejected-before test is an asserted application regression, not a tool/permission rejection. The post-fix MemoryPage run took 7.25 seconds. The coordinated frontend window was released when tests/type checking finished.

No browser, WPF, JVM, model, database, frontend production bundle or distribution package was launched/built for this change. No real Profile or user data was used. Memory's real desktop save/close/reopen and the existing WebView2 startup failures remain unresolved; this source fix has not been incorporated into a new distribution bundle.

## Rollback and scope

`.verification/memory-editor-state-race-20261110/baseline-047d11a.bundle` was verified with exact HEAD and complete history before edits. SHA256: `EF8E197B1671E7FB6D1F174E15F238A7226EA810E6CF0C8FF8B06669D3CCFA63`. Scope is MemoryPage, its existing test file, this report and the status note. Six protected user documents matched their preserved hashes; evidence and user documents are excluded from the local commit. Main, remotes, ACLs and security settings are unchanged; nothing is pushed.
