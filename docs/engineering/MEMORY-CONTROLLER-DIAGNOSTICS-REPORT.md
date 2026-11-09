# Memory controller startup diagnosis

2026-11-09. Diagnostic fixture work only; the historical WebView2 controller timeout root cause and Memory desktop save/reopen acceptance remain unresolved. The follow-up below reached controller creation but timed out while clearing profile data.

## Follow-up using committed asset fix 2b20642

One newly coordinated default-sandbox startup-only run used the previously empty UserDataFolder of synthetic Profile `6e6394fc23d54ee6945233f0d3703b3d`, the existing rebuilt test-host DLL, and the verified copied production assets. No rebuild, JVM, model, offline Python seed, security change, or additional desktop retry was used.

The 2026-11-09 20:14 UTC log recorded ASSETS at 1 ms, PROFILE_VALIDATED/ENVIRONMENT_START at 15 ms, ENVIRONMENT_CREATED at 25 ms, and CONTROLLER_START at 33 ms. The initialization callback succeeded; CONTROLLER_CREATED was recorded at 536 ms. STA, DispatcherSynchronizationContext, matching UI/HWND thread 5092 and 30 one-second Dispatcher heartbeats were observed. The thread desktop remained the sandbox desktop and the input desktop remained Default; their difference did not prevent controller creation in this run.

Startup then remained in PROFILE_CLEAR_START and reached STARTUP_TIMEOUT at 30055 ms while awaiting the production `ClearBrowsingDataAsync(AllProfile)`. PROFILE_CLEAR_COMPLETED, navigation and REACT_BRIDGE_READY were not reached. The UI thread continued pumping; a missing message pump is not supported as the explanation for this particular wait. This result does not establish the cause of the older controller timeouts, nor prove that profile clearing is generally broken outside this environment. Its privacy requirement and the 30-second deadline were retained.

The host (5080) exited normally with fixture exit code 10; no Runtime was started, port 18765 remained closed, and no forced termination was used. Browser enumeration was not repeated after the prior CIM permission denial, so complete browser-process cleanup remains independently unverified. Evidence: `.verification/memory-controller-diagnostics-20261109/startup-assets-fixed{.log,-summary.json,-failure.json,-result.json}`. No Memory draft/cancel/save/reopen acceptance passed.

Subsequent [profile-clear static analysis](MEMORY-PROFILE-CLEAR-ANALYSIS.md) records the verified production ordering, current owned-profile runtime metadata, and upstream limitations. A bounded follow-up moves the existing browser-failure subscription before clearing and adds runtime/browser-PID/failed-kind observations to the test fixture. The managed incremental build passes; no new desktop run has validated those observations or resolved the clearing wait.

The later single run of committed `de4e51d` did validate the added observation: new synthetic Profile `39d1b9009361437a865463b06c0cc647`, live runtime `157.0.4341.42`, browser PID 27448. GPU and renderer exits were captured before clearing; subsequent GPU/browser exits occurred while clearing was pending. The host still ended at the original 30-second bound without READY. Exact host/browser PIDs 21740/27448 exited, 18765 was closed, and the window was released. The [full event ordering and limits](MEMORY-PROFILE-CLEAR-ANALYSIS.md#fresh-profile-observation-with-de4e51d) supersede the earlier lack of process-failure evidence; no retry or security workaround followed.

## Verified baseline and rollback

Read the Memory desktop delivery, Profile path/export ACL, and Knowledge-to-Memory draft reports. Repository/ancestor searches found no applicable AGENTS.md. At diagnostic start, the branch was `codex/knowledge-version-compare`, baseline HEAD was `87364638399872a755e3403c28a06b88da74495d`, and main was `f5266bf4383f52a2293b0fe4bfe1964af958e64a`.

Creating `workspace-before-memory-controller-diagnostics-20261109` was denied at `.git/refs/tags/workspace-before-memory-controller-diagnostics-20261109.lock`. The denied tag write was not retried, and no ACL was changed. Before source edits, a read-only Git export created `.verification/memory-controller-diagnostics-20261109/baseline-8736463.bundle`. `git bundle verify` returned 0, confirmed the exact baseline HEAD, and reported a complete history. SHA256: `d37b0f950d4cc0b688f4345e0e1f4cdf666676e7dbcb5e44fd91547ea5d058a5`.

This bundle preserves tracked history; it does not include the six user-owned untracked documents. All six document SHA256 values still match the prior recorded baseline. They were not edited, deleted, or staged. At the initial diagnostic handoff, these changes were left reviewable and uncommitted because normal sandbox Git metadata writes were denied. A local diagnostic checkpoint requires the separately authorized, operation-scoped Git approval flow; it does not mark Memory desktop acceptance as passed. Nothing was pushed.

## Historical evidence

The two readable original logs show environment creation at 35/40 ms, controller start at 52/56 ms, and startup timeout at 30069/30072 ms. No Memory write was reached. The fixture already has an STAThread entry point and the second attempt's synchronous window creation/show/MainWindow ordering. Repeating that ordering change is not a new diagnosis.

Reads of `attempt-1/save-failure.json` and `attempt-2/save-failure.json` were denied; those reads were not retried through another route. The original logs/reports remain intact. Root cause cannot be inferred from their timeout HRESULT alone.

## New fixture and one desktop observation

Added a test-only startup trace and an explicit `startup` mode. The trace records bounded JSON observations of apartment, synchronization context, UI/HWND thread identity, Dispatcher heartbeats, actual desktop names, and controller callbacks. It does not switch desktops, activate windows, change security/browser arguments, or extend the production 30-second timeout. The startup mode does not start a Runtime/JVM or submit model/storage mutations.

Fresh synthetic Profile `6e6394fc23d54ee6945233f0d3703b3d` was provisioned through the existing trusted fixture. Its canonical root is `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\6e6394fc23d54ee6945233f0d3703b3d`. The real Runtime `init` flow passed, checked empty Memory/history, and exited normally (host 4920, Java 4540). This is the sandbox account's new Profile, not an existing user Profile.

Offline history preparation did not run: the default `python` command resolved to `C:\Users\28421\AppData\Local\Microsoft\WindowsApps\python.exe`, whose process launch failed with a file-access error. The script never executed; no other interpreter, process, or elevated seed was substituted.

One subsequent WPF startup-only run (host 5960, no JVM) recorded:

- STA and `DispatcherSynchronizationContext`, with dispatcher access true.
- UI thread and HWND thread both 10740.
- Session 1 and `userInteractive=true`.
- Thread desktop `CodexSandboxDesktop-91a1524d28b85ec092b161e98e1d586b`, while the input desktop was `Default`.
- Failure after about 5 ms at `ASSETS / INITIALIZATION_FAILED`, HRESULT `0x80070002`. The test output contained only a MainWorkspace/assets directory, without the root manifest/index files.

Controller creation was never reached, no controller callback was received, and the short run ended before the first one-second heartbeat. This cannot prove message-pump liveness during controller initialization or explain the previous timeouts. The distinct sandbox desktop is now an observed execution-context difference, not a demonstrated cause.

## Fixture resource fix and validation

The incremental test-only build does not execute Desktop's dynamic frontend-content target. The test project now explicitly carries already-built production frontend assets when RuntimeLifecycleProbe is enabled, and fails its build if their manifest is missing. No frontend or product rebuild was run; product startup/security code is unchanged.

The final focused test-host build passed with zero warnings/errors. All three copied files match the production asset manifest (manifest SHA256 `3e8929020b77c77279e7fb9a3a3017e1ea70453d88f929b00a63173e13a75d11`). An initial XML-comment syntax error in this fixture fix was caught by the build, corrected, and its failed log retained. No second desktop launch followed the asset fix; it requires another coordinated resource window.

Recorded host/Java PIDs 4920, 4540 and 5960 are gone; port 18765 is closed; no forced termination was used. The CIM query for browser processes tied to the synthetic Profile was denied, so independent browser-process enumeration is explicitly unknown, not an empty successful result. The desktop run stopped before WebView environment/controller creation. Reading the real user's `last-workspace` hash was also denied; no preference equality claim is made. The test host excludes AssistantApp's selection writer, and `selectionWriterUsed=false` is recorded.

Evidence is in `.verification/memory-controller-diagnostics-20261109/`: startup/init logs, original/final focused build logs, asset-check.json, cleanup.json, preservation.json, the verified rollback bundle, and the unexecuted offline seed script. Generated probe output was refreshed; prior delivery failure logs were preserved.

## Next bounded step

The asset-fixed startup check above is complete; do not repeat it without a materially new diagnostic condition and another resource window. Profile clearing now blocks READY, so preserve that failure and continue independently useful Memory work instead of extending the desktop loop. Do not switch desktops, skip clearing, relax WebView2 security, or increase the timeout as a diagnostic substitute.

After a supported fix reaches READY, the smallest core Memory acceptance can reuse the production-component host, its existing UI scripting helpers and real RuntimeClient assertions: enter a synthetic manual draft, explicitly discard it and verify zero records, save one draft exactly once despite a repeated click, shut down the owned host/Runtime, then reopen in a new host and verify the original content and MANUAL source. This would require one bounded WPF/WebView2 plus small owned JVM flow, no model and no Python. A manual-only fixture scenario still needs to be added; the current save/reopen modes specifically require a Knowledge history record. That separate Knowledge-answer-to-Memory flow must retain its history-preparation/provenance requirements. Neither plan has been executed here; no model-quality, citation-ingestion, or desktop end-to-end success is claimed.

## Agent desktop skill download

Using the supported skill-installer, installed only the upstream skill documents from `https://github.com/vercel-labs/agent-desktop`, fixed commit `b43ac4f6e892ef608f8851428613c4009f0e84c7`:

- `C:\Users\28421\.codex\skills\agent-desktop` (five Markdown files); SKILL.md SHA256 `941873a93fca670fc16362de192521f2b5ca27675fb55f18c2112cde86ca9089`.
- `C:\Users\28421\.codex\skills\agent-desktop-windows` (five Markdown files); SKILL.md SHA256 `6d1ce79a23caf84a5ef75695a4896a1b8bdde7d2b4ff55a655c20118b38e189c`.

Both skills still require the separate native agent-desktop CLI. Its Windows guide requires a supported signed-in interactive desktop; semantic actions can operate behind another window, while physical input/focus operations require explicit headed-mode authorization. No native CLI was installed or invoked, no upstream build/install script was executed, and no desktop-control capability was demonstrated. Personal skill files are outside the Workspace repository and are not part of its changes. Installation receipt and ten-file inventory are in `skill-install.json`.

Official API reference used for static threading review: [Microsoft WebView2 threading model](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model). It requires an STA UI thread and a running message pump; the present measurements establish the former but do not yet establish the latter during controller creation.
