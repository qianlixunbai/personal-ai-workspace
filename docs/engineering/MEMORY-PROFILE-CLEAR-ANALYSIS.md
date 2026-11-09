# Profile clearing: static analysis and early failure observation

2026-11-09. Based on committed Memory refresh fix `075a59bb6e6dc738eaa586b0f0f4ca3b54e2de5e`, parent `2b2064254475586608756317ec05d03d4c5f24e4`. This stage does not claim a fix for the browser clearing stall or successful Memory desktop acceptance.

## Fresh-profile observation with de4e51d

After a newly coordinated window, one default-sandbox startup used the existing diagnostic DLL and new synthetic Profile `39d1b9009361437a865463b06c0cc647` with an empty independent UserDataFolder. No rebuild, JVM, model, true user Profile, environment override or security change was involved.

The live controller callback recorded runtime `157.0.4341.42`, host PID 21740 and owned browser PID 27448. Environment creation completed at 22 ms and controller creation at 444 ms (host clock). The fixture captured GpuProcessExited and RenderProcessExited around 641 ms on its own clock, while the recorded stage was still CORE_INITIALIZED; the first failures therefore preceded the production clearing call. More GPU exits occurred at PROFILE_CLEAR_START, the production early failure handler emitted BROWSER_PROCESS_FAILED, and BrowserProcessExited followed around 968 ms on the fixture clock. UI heartbeats continued to 30. The original startup bound expired at 30043 ms, READY was never reached, and the fixture returned 10.

This distinguishes actual browser/GPU/renderer failures from a wait with no observed process failure. It does not identify why those processes exited or prove that the clear API caused their failures; the earliest failures preceded it. Do not reinterpret this as permission to disable GPU/sandbox features, bypass clearing or move the test to a wider execution context. The desktop loop stopped after this single outcome; subsequent work returned to the user-visible Memory draft review feature.

Direct checks of the two exact observed PIDs found both exited; 18765 was closed, no force-stop was used, and complete descendant enumeration was not claimed. The resource window was released. Evidence: `.verification/memory-profile-clear-observation-20261109/{profile-prepare.log,startup.log,startup-summary.json,startup-failure.json,startup-result.json}`. The live runtime agrees with the earlier on-disk version metadata. No further startup observation is pending without a materially new condition; resolve browser stability from specific evidence before arranging the separate Memory desktop acceptance.

## Existing evidence and ordering

The one asset-fixed startup run created the controller at 536 ms and received a successful initialization callback. Its UI thread/HWND thread matched and it recorded 30 Dispatcher heartbeats while awaiting PROFILE_CLEAR_START. It timed out at 30055 ms, before navigation, Bridge activation, or READY. The owned synthetic Profile's `webview2-profile/EBWebView/Last Version` file contains `157.0.4341.42`; this is on-disk runtime metadata, not a captured live BrowserVersionString. The next trace can distinguish them directly.

The production path in WorkspaceWebViewHost is:

1. Validate assets and private Profile, reject unexpected WebView environment overrides, and create the environment.
2. Create the MainWorkspace InPrivate controller on the WPF UI thread; verify InPrivate and disable password/autofill persistence.
3. Await ClearBrowsingDataAsync(AllProfile).
4. Only after successful clearing, apply the remaining settings/content-policy handlers, map the trusted production assets, create the Bridge, navigate and await the React handshake.

There is no Task.Result, synchronous Wait, nested modal message loop, page NavigationCompleted dependency, or Bridge callback awaited before the clear operation. Its continuation captures the live WPF dispatcher; the measured heartbeats rule out a stopped UI message pump in this run. This does not prove that every browser/utility process or native completion callback stayed healthy.

## Timeout and exit guarantees

InitializeAsync bounds the whole startup task to 30 seconds with WaitAsync and the host lifetime token. The WebView clear API has no cancellation-token parameter. On failure the host records a bounded startup code/stage, invalidates the Bridge, shows fallback and disposes the WebView control. The suspended initialization path rechecks closing/failed after clearing, so a late completion cannot proceed to navigation or READY.

CloseAsync sets closing, cancels the host lifetime, cancels readiness, disposes the Bridge, attempts AllProfile clearing with its existing three-second bound, and disposes the control in finally. It reports CleanupPassed only if that clear completes. Disposing a timed-out control is not proof of a successful data-clear result. These guarantees, the AllProfile scope, InPrivate check, environment checks and both deadlines remain unchanged.

## Upstream evidence and uncertainty

[Microsoft's ClearBrowsingDataAsync API reference](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2profile.clearbrowsingdataasync?view=webview2-dotnet-1.0.4258.31) documents whole-profile and data-kind overloads, plus a separate time-range overload. It does not promise a maximum completion time or require a prior website navigation. The project references WebView2 SDK 1.0.4258.31.

[Upstream issue 3417](https://github.com/MicrosoftEdge/WebView2Feedback/issues/3417) describes an older WPF AllProfile/DiskCache wait before navigation. Microsoft maintainers and reporters discussed differing reproduction results and [workarounds in 2023](https://github.com/MicrosoftEdge/WebView2Feedback/issues/3417#issuecomment-1670431258); later reports also disagreed about versions and workaround effectiveness. This is relevant prior evidence, not confirmation that runtime 157 here has the same underlying defect.

Do not omit DiskCache, skip clearing, raise the timeout, switch desktop/security context, or use a real Profile to make the check pass. A MinValue-to-now time-range call does not inherently prove the same timestamp-independent coverage as AllProfile; a navigation warm-up would change the pre-clear sequencing and needs its own constrained policy design and evidence. No such workaround was implemented or executed.

## Completed bounded observation change

Before this change, the production ProcessFailed handler was registered only after profile clearing and later setup. Thus absence of that diagnostic during a clear wait could not establish absence of a browser failure. The existing fallback handler is now subscribed immediately before clearing, with a fixed BROWSER_PROCESS_FAILED diagnostic. It retains the existing fail-closed state and does not unblock navigation or treat clearing as successful.

The test-only MemoryDraftStartupTrace now captures BrowserProcessId and Environment.BrowserVersionString at successful controller initialization, then observes ProcessFailedKind through the same owned CoreWebView2 object. This gives a future resource-window run an explicit owned browser PID without retrying the denied CIM enumeration. It does not prove that every possible descendant has been enumerated. Disposal ignores a late observation and tolerates the already-disposed COM/control state when removing its event handler.

No store, Memory source/provenance, credentials, model, profile directory/ACL, timeout, or cleanup scope was changed. No additional desktop/browser/JVM process was started for this stage.

## Rollback, verification, and remaining work

Before these source edits, `.verification/memory-profile-clear-observation-20261109/baseline-075a59b.bundle` was created and git bundle verify returned 0 with exact HEAD `075a59bb6e6dc738eaa586b0f0f4ca3b54e2de5e` and complete history. SHA256: `b82cf8f396351e488801f5324b42d57930c7e44ecc56c58907676e6b257c0be7`.

A managed incremental Desktop/test-host build passed with zero warnings/errors in about six seconds, using no restore and FrontendSkipBuild=true. Existing secured production assets were verified/copied by the existing target; no frontend production rebuild, package generation, native-helper rebuild, desktop run, JVM/model, or full test suite was performed. The already-passing 21 MemoryPage tests and TypeScript check were not rerun for the prior commit. This build validates the event/API wiring at compile time; it does not validate live ProcessFailed delivery or clear completion. Log: `.verification/memory-profile-clear-observation-20261109/focused-build.log`.

The single coordinated follow-up above collected the added observations and ended with process failures. Do not repeat it without a materially new diagnostic condition and resource coordination. A successful clear and READY remain prerequisites for the separately planned manual Memory discard/save/independent-reopen flow. Knowledge-answer-to-Memory acceptance additionally retains its deterministic history preparation requirement. None of those desktop outcomes is claimed here.
