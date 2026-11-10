# Memory desktop WebView2 failure investigation

## 2026-11-10: corrected authorization boundary; current listener read denied

The parent corrected the preceding handoff interpretation after `605ad838ae86a35047c3d9fa402528ab2128a5e3`: the old unrecorded query error did **not** establish a permission denial or prohibit an independent current-state check. The extra requirement that the user personally confirm exclusive port use was agent-added and is withdrawn. Existing continuous-development authorization covers an ordinary read-only check and, once isolation is established, the new synthetic full-EXE/Runtime run with normal precise tool approval. The previous section is retained as historical evidence, not the current authorization rule.

One normal-permission `Get-NetTCPConnection -LocalPort 18765 -State Listen -ErrorAction Stop` call was then actually made. It returned `Microsoft.Management.Infrastructure.CimException`, message **拒绝访问**, category **PermissionDenied**, reason `CimException`, fully qualified error ID **HRESULT 0x80041003,Get-NetTCPConnection**, exception HResult `-2146233088`. This is a concrete access denial, not a no-matching-listener result. The outer shell caught the exception and exited 0; its exit code does not convert the failed observation into an empty port. This new error also does not retroactively identify the older error's cause.

Exact returned fields are preserved at `.verification/normal-entry-isolation-preflight-20261110/port-check-denied.json`, recorded from the existing response without a repeated query. The actual denial concerns the local CIM-backed listener inspection, not automatic approval review, Docker access or an attempted application launch. Port 18765 therefore remains **UNKNOWN**.

As explicitly required for a real denial, the action stopped immediately. No alternate API, elevated listener query, process-ownership enumeration, old refused metadata, Crashpad or private-directory read was attempted. The required user-history read found no new relevant evidence to justify a retry. The known guardian scripts and prior EXE report were reviewed independently, but no guardian, Profile preparation or product process was started. No Job or heavy mutex was acquired; no owned service needs cleanup. No database, default selection, model, security setting or persistent configuration was touched.

The remaining concrete blocker is **obtaining a permitted current listener observation before this fixed-endpoint full-EXE run**, not missing user permission to develop. The present delegation explicitly says to stop rather than switch routes after an access denial, so no further observation or launch is pending. Report this exact failure to the parent for a materially new access-resolution decision; do not request generic permission, require the user to change security settings, or interpret the broad development instruction as permission to bypass the denial. Full normal-web/Runtime startup and webpage save/reopen remain unverified. This correction changes documentation only; the verified bundle and product source are unchanged, and no extra build/test/package was run.

## 2026-11-10: full-EXE isolation preflight blocked before launch

Continuation baseline: `92288b7745b3737fa24bd4562f317413bc327b0b`, branch `codex/knowledge-version-compare`. Repository and checked ancestor locations contain no AGENTS.md; the repository has no .agents directory. Tracked files were clean and the same six user documents were untracked. This section supersedes the earlier next-launch recommendation under the new handoff restriction: keep 18765 UNKNOWN, do not probe it again or enumerate old processes, and use a supported fresh owned endpoint if possible.

Source inspection found **no supported alternate endpoint for the delivered full application**. A new Profile isolates storage but does not select a new Runtime listener:

| Binding | Source evidence |
| --- | --- |
| One-time entry | `Mmf3AcceptanceLaunch.Parse` accepts exactly the entry flag and a 32-character Profile ID. It accepts no endpoint or temporary-configuration argument. `AssistantApp` uses `RuntimeClient.CreateMmf3Acceptance` for that Profile. |
| HTTP client | `desktop/src/PersonalAiWorkspace.Core/RuntimeClient.cs` fixes that client's base address to `http://127.0.0.1:18765`. The separate non-acceptance 8765 path is not an isolated replacement for this entry. |
| Runtime ownership | `DevelopmentRuntimeOwner.RunAsync` checks active listeners for 18765 before starting the child, then checks health and credentials through the fixed client. Restart checks the same port. This is source evidence only; neither path ran in this continuation. |
| Runtime command and data | `TrustedDevelopmentRuntimeSources.StartInfo` sets `--server.address=127.0.0.1`, `--server.port=18765`, and token/data/model-state paths beneath the admitted Profile root. It rejects server, Spring, Workspace and JVM override environment variables. |
| Native admission | `WindowsNativeGate.requireProfileArguments` requires the exact fixed-port argument list before Spring; `rejectOverrides` also rejects alternate server/Workspace system properties. Replacing an argument or injecting configuration is not an existing supported option. |
| Default selection | `AssistantApp.ObserveDevelopmentStartup` returns `READY_OPEN_ONCE` before `DevelopmentWorkspaceSelection.Save` for this flag. The source supports leaving the default selection unchanged, but no new live acceptance is claimed. |

Consequently the requested action—launch the existing delivered EXE with a new synthetic Profile and an independently isolated Runtime endpoint—was **not started**. No Profile was prepared, no EXE/browser/Java/service/model was launched, no real database or previous Profile was opened, no port was queried and no process inventory was taken. There is no new launch/exit/Job evidence and no normal-web Memory save/reopen result. The shared heavy mutex was not acquired because this continuation did only static inspection, hashing and documentation. No safety setting or application admission code changed.

This is a source-level capability blocker combined with the explicit stopped-port boundary, **not a new permission denial or failed Docker/startup attempt**. The previous 18765 query still has an unrecorded error cause and remains UNKNOWN. A new Profile alone does not resolve it. Changing the port contract would require coordinated desktop-client, owner, Java-admission and package changes; silently patching the delivered bundle or weakening admission is outside this verification scope.

Minimum unblock: the user must establish that any known old Workspace development instance has exited normally and that 18765 can be used exclusively, then explicitly allow one new synthetic full-EXE run on that fixed endpoint, including the application's existing listener-admission check. This does not authorize a separate diagnostic retry, enumeration/termination of unknown processes, use of a real Profile or any persistent/security-setting change. If that cannot be established, remain stopped; an alternate-port implementation needs a separately scoped decision rather than an improvised configuration override. No user action or approval is currently pending in the tools.

The delivered ZIP, desktop DLL and Runtime JAR were rehashed read-only and still match the previous delivery receipt: ZIP `BD29D41EED4F83C8D14FF5C539C0F64323918D536F7BAC05CE109D4A057AFEA9` (384,688,736 bytes), desktop `FCD718C1BA2B213752211D7F578A83908C0EBB788E22D774C51B9818FA873B02`, Runtime `BC8F0CC35D2F9321AF848F7FFDFE271DB07B770CF1878454F07A8FD0C0D12625`. Packaging's 933-file/extraction/source result is inherited evidence, not a repeated packaging test. Static baseline and protected-document hashes are recorded at `.verification/normal-entry-isolation-preflight-20261110/baseline.json`; final preservation/working-tree evidence is recorded beside it. No new tests, build or package were needed for this documentation-only finding. Earlier browser-only readiness, native-fallback persistence and whole-database-write limitations remain unchanged. Normal full-EXE/Runtime startup, normal-web save/reopen, no-argument entry, maintenance/migration and formal release remain unaccepted.

## 2026-11-10: delivery decision and default-profile startup correction

Baseline `1aee88122c5465ccd6885bc75fac336269c8de26`. The [explicit-memory completion scope](MEMORY-SYSTEM-COMPLETION-REPORT.md) already includes candidate review, explicit saves, retrieval/preview, versioned conversation references, archive/restore/delete and persistence. Subsequent [draft recovery](MEMORY-DRAFT-RECOVERY-FLOWS.md) and [management recall](MEMORY-MANAGEMENT-RECALL-FLOWS.md) complete the identified editing/search gaps. No further missing mandatory memory capability was established. Automatic memory, vector retrieval and persistent document associations are optional extensions, not reasons to postpone usable delivery. The current blocking user experience is normal WebView startup; personal recovery, physical input/IME, default-entry and release acceptance remain separate gates.

New upstream evidence justified a different comparison. [WebView2 issue 5557](https://github.com/MicrosoftEdge/WebView2Feedback/issues/5557) reports named-profile creation slower than default-profile creation; [issue 5796](https://github.com/MicrosoftEdge/WebView2Feedback/issues/5796) reports startup delays with a named InPrivate profile. Neither establishes this machine's cause or a confirmed fix; their runtimes/timings differ. We did **not** use the latter reporter's component-update disabling switch. The official [MultiProfile contract](https://github.com/MicrosoftEdge/WebView2Feedback/blob/main/specs/MultiProfile.md) separates the default empty ProfileName from InPrivate. A default profile is inside the supplied application UDF; it does not select the user's Edge profile.

Two new, single-use synthetic observations ran with runtime `157.0.4341.42`, existing native admission/private-directory creation, InPrivate enabled, unchanged sandbox/system policy and the shared Session 1 mutex:

| Observation | New synthetic ID / evidence | Actual result |
| --- | --- | --- |
| Native controller, omit only the `MainWorkspace` name | `98556f953bd74fb08c9c348a88f89c02`; `.verification/webview-profile-name-20261110/` | Request 08:58:44.1175794 UTC, creation 08:58:44.3531946; about 236 ms. InPrivate verified; no navigation. Earlier named native comparison timed out at 25 s. |
| Actual WPF host, startup clear, bundled page/Bridge and close; no Runtime owner | `6e48a39705874cc19c4769d56c0b5026`; `.verification/webview-default-wpf-20261110/` | Controller about 227 ms; startup clear about 12,065 ms; React/Bridge ready at 12,478 ms. Local main/navigation DOM present, InPrivate verified, close clear about 13 ms; exit 0. |

The second observation uses a tiny `NoRuntime` fixture hosting the real `MainWorkspaceWindow`/`WorkspaceWebViewHost` and bundled frontend. It never constructs `AssistantApp`, `DevelopmentRuntimeOwner` or RuntimeClient; shell status is explicitly synthetic/unavailable. It therefore demonstrates local WPF/page startup and cleanup, **not full application/Java readiness or memory operations**. No save/reopen tests or model calls were repeated. The first observation did use `AssistantApp`: its normal startup schedules the Runtime owner, so the earlier intention to run no service cannot be promoted into a verified zero-Runtime-start claim. No Runtime readiness or port state was measured in that observation. The second fixture avoids that path entirely. Preparation uses the existing native provisioning helper; it is not a Runtime service launch.

Both guardians returned successful receipts and released their Jobs/mutexes. There were respectively **2 and 5 active Job processes before Job disposal**; kill-on-close performed owned-descendant cleanup. Do not describe the whole process trees as naturally exited. Guardian port observations were removed; **18765 remains UNKNOWN**, and the prior failed query was not retried or replaced with another diagnostic query. The first full-App path includes its ordinary internal listener admission check, which must not be described as a port-state result. No denied directory or extra process metadata was read. Temporary source was restored in `finally`; ordinary outputs were restored before each launch. The first fixture compilation's unused missing assembly-reference warning was removed and only that fixture rebuilt before its sole launch; original log retained.

Product correction: omit the unnecessary custom `ProfileName`, retaining the admitted UDF, explicit InPrivate verification, startup/close clear, 30 s/3 s bounds and fallback. There is only one browser profile required per application UDF. No existing cache is moved/decrypted/deleted and no ACL, registry, sandbox or network policy is altered. The WPF experiment changed this same controller option; its only source difference from the correction is the explanatory comment. No new unit test mirrors this one-line change; the bounded WPF observation supplies affected-behavior evidence.

This is evidence for a practical mitigation, not proof that ProfileName uniquely caused every earlier failure: the comparisons ran at different times on new profiles, and the successful WPF case excludes Runtime startup. EFS/ACL remain unproven causes. Microsoft's [UDF ACL guidance](https://learn.microsoft.com/en-us/deployedge/webview2-security-tools#preserve-default-access-control-lists-acls-on-the-apps-user-data-folder-udf) still describes a possible compatibility concern, but the successful unchanged-security experiment removes the present need to request security changes. No decrypt/permission-relaxation/system-tracing experiment is pending or proposed for execution.

Next acceptance should use the corrected full development bundle, one **new** synthetic Profile, `--open-development-workspace-once`, the existing guardian/mutex and unchanged native admission. Observe normal local page/Bridge plus owned Runtime startup and normal Exit; do not repeat saved-memory writes, models or a standalone port query. This is a different integration boundary from the completed browser-only fixture. It requires the usual exact execution approval for creating/opening that synthetic Profile outside the workspace, **no user change to EFS/ACL/sandbox/settings**. If approved execution is refused, stop that action and retain the useful source/package delivery. The no-argument/default path, maintenance/migration, personal-data recovery and formal release are still unaccepted.

Correction commit: `64b33b2991acb407d6887040ec1fe7ae902b0afb`. Delivered `artifacts/PersonalAiWorkspace-webview-default-profile-20261110.zip`: 933 payload files, 384,688,736 bytes; ZIP SHA256 `BD29D41EED4F83C8D14FF5C539C0F64323918D536F7BAC05CE109D4A057AFEA9`. Desktop DLL SHA256 `FCD718C1BA2B213752211D7F578A83908C0EBB788E22D774C51B9818FA873B02`. Desktop publish, actual ZIP relocation, manifest hashes and embedded-source check passed. Runtime JAR remains `BC8F0CC35D2F9321AF848F7FFDFE271DB07B770CF1878454F07A8FD0C0D12625`; frontend bytes are also unchanged. The package contains no experiment host/method. Packaging evidence: `.verification/webview-default-profile-delivery-20261110/{Build.ps1,result.json,publish.log,embedded-sources.log,lock.json}`. Its guardian PID 35032 released the mutex at 09:12:13 UTC. Packaging launched no browser/Runtime/model and did not repeat persistence tests; this is not new full-EXE acceptance.

The older recall ZIP SHA256 remains `1227D4A789741CF61DB03A87E53D3D4A0E7463F542F65D4432BC47293CFFEED4`; six user-document hashes and main `f5266bf4383f52a2293b0fe4bfe1964af958e64a` remain unchanged. No push, pending action, new read denial or uncertain tool execution remains. Port state remains UNKNOWN as described above. The previously prohibited directory/process reads remain prohibited. This turn supplied a concrete source/package correction without requesting a user security-setting change; the next conversation can perform the separately scoped full-App integration check instead of adding more memory features.

## 2026-11-10: close after startup disposal

Follow-up baseline `eb9ff6d`: the two actual EXE logs in `.verification/memory-exe-entry-20261110/{save,reopen}-stdout.log` both contain `CONTROL_DISPOSE_COMPLETED` after `STARTUP_TIMEOUT / CONTROLLER_START`. Later close emits `CLOSE_CLEAR_FAILED` with `0x80131622` before any `CLOSE_CLEAR_START`. The source reads the disposed WPF control's `CoreWebView2` getter in that interval. This is a secondary application lifetime error, not evidence that a clear request caused the earlier controller timeout.

`WorkspaceWebViewHost` now owns an idempotent control-disposal path and skips the disposed getter on close. It records `CLOSE_CLEAR_UNAVAILABLE_DISPOSED` and leaves `CleanupPassed=false`; no browser-data cleanup is claimed. The normal live-control clear, 30-second startup bound, 3-second close bound, InPrivate requirement and security checks are unchanged.

One focused STA check triggers missing-asset failure **before creating a Profile or browser**, then closes and disposes the real WPF control. It verifies one disposal, no extra clear error, no false cleanup success and no Profile creation. Incremental build passed with zero warnings/errors; the single check passed. Logs/TRX and the released Session 1 heavy-lock receipt are in `.verification/webview-lifecycle-20261110/`. This is lifecycle validation, not a new WebView startup or a normal-page acceptance. Existing packages remain unchanged.

Official [EnsureCoreWebView2Async documentation](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.wpf.webview2.ensurecorewebview2async?view=webview2-dotnet-1.0.4258.31) and upstream [issue 2506](https://github.com/MicrosoftEdge/WebView2Feedback/issues/2506) were reviewed. The latter reports a superficially similar hang and multiple hypotheses; it does not establish this machine's cause or justify reinstalling/altering system configuration. The earlier approved-context trace already shows a visible, loaded WPF control, matching STA HWND thread and continuing heartbeats. There is no evidence here for blindly moving the call to another dispatcher or increasing the timeout.

Read limitation: an exact-path listing of the previous synthetic Profile's `webview2-profile/EBWebView/Crashpad/reports` returned OS access denied. No dump contents were obtained and that range was not retried or bypassed. The initial filename discovery also encountered previously restricted unrelated `.verification` subdirectories; those were not inspected or retried. These are filesystem refusals, not an automatic approval-review rejection. Work continued from already saved workspace evidence.

## 2026-11-10: one native controller boundary comparison

Lifecycle fix: `50a4614924c1e2b3e5bf8b42f30faccbac6d069d`. To distinguish the WPF adapter from the common native initialization path, one temporary experimental build replaced the WPF `EnsureCoreWebView2Async` call with `CoreWebView2Environment.CreateCoreWebView2ControllerAsync`, using the loaded main window's HWND. The same environment options, `MainWorkspace` profile name, InPrivate requirement, native Profile admission and full `--open-development-workspace-once` entry remained in use. No page navigation or browser-data clear was attempted. This is an alternative API experiment, **not a proposed production implementation or normal-page acceptance**.

Evidence and reviewed scripts are in `.verification/webview-native-boundary-20261110/`: `Build.ps1`, `Probe.cs.txt`, `WorkspaceWebViewHost.probe.cs`, `Run.ps1`, and `Restore-ProductBuild.ps1`. Temporary source was restored byte-for-byte in the build's `finally` before launch. The experiment bundle is separately labelled `NATIVE_CONTROLLER_BOUNDARY_ONLY_NOT_PRODUCT_ACCEPTANCE`, `sourceDirty=true`; it must not be distributed as the normal application. Its Desktop DLL SHA256 is `EA8632F531B8519B7B14C60FC8C397AEF2F403D95652A5152C3E6737E4C7844D`; probe-source SHA256 is `2C1E03E1B0FDAA02B20FF72330B374F15321C0C57B55623861E47DB10F5620CC`. Runtime and frontend bytes were checked against the previous accepted bundle and remained unchanged.

A single formally requested sandbox-outside execution actually ran; it was not denied or left pending. Existing preparation tooling created new synthetic Profile `48975fb19a1a44a5872bd3bcdb6ccc95` (prepare PID 22544, exit 0). The experimental full EXE PID 11588 then used only that Profile and the one-time entry. Its default `last-workspace` was absent before and after. No existing memory/database was opened and no model was requested.

| UTC | Observation |
| --- | --- |
| 07:09:33.2886981 | Environment returned. |
| 07:09:33.3038007 | Environment version `157.0.4341.42`; `GetProcessInfos()` returned an empty list. |
| 07:09:33.3060496 | Native controller request on STA with dispatcher access and a loaded, visible WPF view. |
| 07:09:58.3250813 | Native task had not completed at the experiment's 25-second limit. |
| 07:09:58.3253140 | Second environment process snapshot was also empty; no environment process/exit event was recorded. |
| 07:09:58.4180227 | EXE exited 0 after the experiment called existing `AssistantApp.ExitAsync`; no tray acceptance is claimed. |

This demonstrates that bypassing the WPF initialization adapter does **not** produce a controller within this bound in the same environment/Profile context. It does not prove which native operation is waiting, rule out a completion after 25 seconds, or identify EFS, ACLs, a runtime defect, package identity, GPU drivers or security software as the unique cause. The 25-second experiment deadline differs from the normal product's unchanged 30 seconds. Empty `GetProcessInfos()` snapshots are not proof that no OS child processes existed.

In fact the guardian's owned Job still contained **3 active processes** after desktop exit. Closing the Job handle invoked the existing kill-on-close cleanup; this run cannot be described as all descendants exiting normally. The held desktop process had exited, port 18765 was closed after cleanup, and the heavy mutex was released. No process metadata scan, unknown-process termination or KAG port operation was used. No per-PID post-close absence claim is made for the three unnamed descendants.

### New Profile metadata and remaining decision

Two separately requested, read-only inspections targeted only this newly created Profile. `new-browser-directory.json` and `new-browser-metadata.json` record a 215-character UDF path under the launching context's MSIX LocalCache. Both its root and `EBWebView` carry the `Encrypted` file attribute; the protected root DACL grants FullControl to CurrentUser, LocalSystem and Administrators. The known `Crashpad/reports` directory exists but contains no report files. No raw browser contents, credentials or memory rows were read, and the earlier denied Profile was not revisited.

Microsoft's [WebView2 security guidance](https://learn.microsoft.com/en-us/deployedge/webview2-security-tools#preserve-default-access-control-lists-acls-on-the-apps-user-data-folder-udf) explains that sandboxed child processes still require UDF access and that tightening those permissions can prevent initialization. The product's `WindowsProfileSecurity.CreateDirectory/ValidatePrivate` deliberately establishes and validates the restricted root contract. This makes compatibility between native browser storage and the current private storage contract a concrete investigation target, **not a demonstrated ACL failure**. EFS is an observed attribute, not a diagnosed cause. No ACL, encryption, Chromium sandbox, registry, antivirus, network or system policy was changed.

Further live comparisons should change one explicitly chosen condition or obtain a narrowly scoped native failure trace. Repeating the same WPF/native startup is not informative. A cache-location/security experiment would need an explicit scope decision because this task forbids changing ACLs/security settings or bypassing Profile admission; no such experiment is approved, pending, or silently prepared to run. A native trace would first need an exact tool/owned-process/data-scope plan; no debugger, system trace, symbol download or runtime reinstall was performed. The specific denied/waiting native operation remains unknown.

### Product restoration and handoff

The temporary experiment also changed ordinary `win-x64` build outputs. These were restored by one necessary no-restore publish of clean product source `50a4614`, under the shared heavy mutex. `normal-product-result.json` confirms matching build/publish DLL hashes and absence of the experimental method marker. Normal Desktop DLL SHA256: `21BAC1425A23C0D39394E746F24B06A0530DD7D5F0BBE4E0B3FF05FF166274EA`. The normal output was **not launched**; `normal-product-publish` is a build output, not a new complete delivery bundle.

Build, experiment and restoration each held Session 1 `Local\\Dot_KAG_Workspace_HeavyRun` only for their complete heavy lifecycle and released it in `finally`; receipts are `build-cleanup.json`, `cleanup.json`, `normal-product-build-cleanup.json`. Original packages, six user documents and main `f5266bf4383f52a2293b0fe4bfe1964af958e64a` remain preserved; no push. The prior complete-EXE native-memory save/reopen pass remains valid; normal WebView2 pages, no-argument default entry, maintenance/migration and formal release remain unaccepted. The delivered bundle still contains product `cff8015`; the new lifecycle correction exists in source and verified normal build output only.

## 2026-11-10: third module — safe zoom actions on the fallback page

Continuing from `67c9cda`, the existing WPF fallback still left the zoom controls enabled after the failed startup had disposed the browser. A single extension of the existing STA lifecycle check reproduced a real `ObjectDisposedException`, object `Browser`: the actual ZoomIn routed handler reached `WebView2Base.CoerceZoomFactorPropertyChanged`, whose `CoreWebView2` getter rejected the disposed control. The stack is retained in `.verification/webview-fallback-interaction-20261110/before-test.log` and `before-lifecycle.trx`. The production `AssistantApp.DispatcherUnhandledException` handler displays an application-error message and calls `Shutdown(1)`; that full-EXE shutdown consequence is established by source inspection, not a new live crash run.

The three zoom buttons now start disabled, become available after browser readiness, and are disabled on failure and shutdown. Their routed handlers check readiness and window shutdown/closed state before reading or writing browser zoom, so an already queued click is harmless. The normal 75–200% zoom range and reset to 100% are unchanged. Native Memory and Assistant fallback controls remain available.

The same single STA check now covers forced zoom-in, zoom-out and reset events after the actual control has been disposed, plus queued events after window close. It verifies unchanged failure text/zoom label, disabled zoom buttons, available native Memory entry, and the earlier cleanup guarantees. Before the product fix it failed with the SDK exception; after the fix it passed, 1/1, 219 ms. Incremental build passed with zero warnings/errors. No extra suite, Profile, browser process, HTTP/SQLite store, JVM or model was started. Both focused check lifecycles used the existing Session 1 heavy mutex and released it; the final receipt is `lock.json` in that evidence folder. No denied read path was revisited and no safety/configuration setting changed.

### Next concrete task after this three-module handoff

The remaining independent user-facing gap is **delivery**: the retained `cff8015` development bundle still has both reproduced lifecycle/fallback defects, while the corrections are currently source/build results. The next session should create a newly named complete development bundle from the committed corrected source, retaining the accepted old bundle, its ZIP and six user documents. Reuse the reviewed SQLite-long-path Runtime/frontend payload and `.verification/PersonalAiWorkspace-sqlite-long-path-20261110-sources.local.json`, checking exact hashes; adapt the previously reviewed `.verification/memory-exe-entry-20261110/Build-DesktopDelivery.ps1` to the new baseline/output instead of running its old hard-coded `cff8015` guard/output names. No Runtime or frontend rebuild is needed if those inputs remain identical.

Only if an actual EXE check is needed for the newly changed toolbar, use one new synthetic Profile and `--open-development-workspace-once` under the shared lifecycle lock: verify the natural failure message, all three disabled zoom actions, the native-memory entry's availability, and normal exit/Job receipt. Do not repeat the already passed Memory save/reopen writes, run models, touch the default selection, or report this as successful normal WebView2 navigation. Package notes must retain the current browser startup limitation and distinguish the new UI check from the older persistence acceptance. The native controller investigation remains at the evidence boundary above; another identical startup or security-setting change is not the next task.

2026-11-09. Source baseline `d772c9a65d60d98458c9c1e3fef40d4de90ba197`, branch `codex/knowledge-version-compare`. This stage reads existing synthetic evidence and adds bounded application diagnostics. It neither fixes the native browser failure nor passes Memory desktop acceptance.

## Finding

Controller initialization succeeded. Process-failure notifications were delivered before the startup profile-clear call, and the owned browser itself subsequently produced a crash dump. The 30-second timeout occurred much later. These observations rule out describing this run as a controller-creation timeout or attributing every failure notification to timeout disposal. They do not identify the initial cause, establish a GPU-driver defect, or prove ClearBrowsingDataAsync caused the failure.

The existing ProcessFailed handler calls the application's fail-closed fallback for every failure kind. That hides the WebView; it does not explicitly close or dispose it. Whether this response influences subsequent browser behavior remains unknown. Its behavior is unchanged in this stage.

## Original delivery order and two clocks

Source: `.verification/memory-profile-clear-observation-20261109/startup.log`. The production host stopwatch starts later than the fixture stopwatch; their millisecond values must not be compared as one clock. Rows below preserve the original JSON-line delivery order. Notifications can be queued: delivery time is not necessarily the instant a native process failed.

| Delivered observation | Host clock, ms | Fixture clock, ms | Meaning |
| --- | ---: | ---: | --- |
| ENVIRONMENT_CREATED | 22 | — | Environment returned successfully. |
| CONTROLLER_CALLBACK | — | 639 | Success; browser PID 27448, runtime 157.0.4341.42. |
| CORE_INITIALIZED | 442 | — | Production initialization callback succeeded. |
| GpuProcessExited, RenderProcessExited, two more GpuProcessExited | — | 641 | Delivered while stage was CORE_INITIALIZED, before the clear-start log/call. |
| CONTROLLER_CREATED | 444 | — | EnsureCoreWebView2Async continuation returned. |
| PROFILE_CLEAR_START | 444 | — | AllProfile clear is about to be called. |
| GpuProcessExited | — | 643 | Another notification; still no explicit disposal. |
| BROWSER_PROCESS_FAILED | 446 | — | Production handler calls Fail; fallback collapses the WebView. |
| GpuProcessExited twice | — | 647, 650 | WebView visibility is now false. Causality is not established. |
| BrowserProcessExited, through **CoreWebView2.ProcessFailed** | — | 968 | Unexpected browser-exit notification; not the environment's BrowserProcessExited event. |
| STARTUP_TIMEOUT | 30043 | — | Profile clear never reached the completion stage; timeout catch explicitly disposes the control. |
| HOST_TEARDOWN | — | 30318 | Fixture is about to request window shutdown. |

Thirty dispatcher heartbeats were delivered. The recorded UI thread and HWND thread were both 28320, in STA with DispatcherSynchronizationContext. This supports a functioning UI message pump, not proof that every native asynchronous operation can complete. The thread desktop was a CodexSandboxDesktop and the input desktop was Default; this difference is contextual evidence only, not proof of a launch/desktop restriction. No desktop or execution context was changed.

Original result: exit 10, no READY, no JVM PID, zero model calls. The previous exact-PID checks found host 21740 and browser 27448 gone, with port 18765 closed. This stage starts neither process again.

## Existing owned crash evidence

Only the known synthetic Profile `39d1b9009361437a865463b06c0cc647` was inspected for browser diagnostics. Its root is under `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles`. Under `webview2-profile/EBWebView/Crashpad` there are metadata/settings files, a one-byte shutdown marker, and this report:

`reports/cf0a4fd1-cec2-4a38-9c97-9ade7f015a33.dmp`

- Size: 8,503,619 bytes. SHA256: `84389BDAEFF729CEA361558D2BC87D0050E78A3437031D266FE285BE15A4F5A3`.
- Header timestamp: `2026-11-09T20:52:18Z` (one-second resolution); file last-write UTC: `20:52:18.255891Z`.
- Misc-info stream has a valid process ID: **27448**, matching the live browser PID already recorded by the fixture. It is not merely an unidentified GPU-child dump.
- Exception stream: thread 11500, code `0x80000003`, flags 0; containing module `msedge.dll`, version `157.0.4341.42`, module-relative address `0xB69FD6D`.

PowerShell BinaryReader read only the minidump header/directory and exception, containing-module and misc-info records using the documented [MINIDUMP_EXCEPTION_STREAM](https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_exception_stream), [MINIDUMP_EXCEPTION](https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_exception), and [MINIDUMP_MISC_INFO](https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_misc_info) layouts. No debugger, symbol download, binary installation, raw memory/string extraction or upload was performed. A first attempt to parse `watson_metadata` as JSON failed; its contents were not interpreted or included in this report. An exception address without a symbolized stack cannot explain the initiating failure.

Reviewable, content-free derived evidence is in `.verification/memory-webview2-failure-investigation-20261109/previous-startup-timeline.json` and `existing-dump-summary.json`. The raw dump remains in the synthetic Profile.

## Official API contracts and missing evidence

| Evidence | Available from original run | Missing / interpretation |
| --- | --- | --- |
| SDK / Runtime | Project and resolved SDK package 1.0.4258.31; live runtime 157.0.4341.42 | Loaded SDK assembly version was not logged. Runtime version is also corroborated by the dump module. |
| ProcessFailed | Kind, delivery order, stage, browser PID | Reason, ExitCode, ProcessDescription and FailureSourceModulePath were not captured. No per-failed-child PID is supplied by these recorded fields. |
| Browser exit | ProcessFailedKind.BrowserProcessExited and a matching browser dump | Environment BrowserProcessExitKind and its PID were not subscribed/logged. |
| Application lifetime | Fallback code and later timeout/fixture teardown | Exact fallback entry/return and every application disposal request lacked markers. |
| Crash reporting | Existing local dump and metadata | FailureReportFolderPath API result was not logged; the Crashpad location above was inspected directly. No symbolized stack or initiating-child exit code. |

Microsoft documents [ProcessFailed arguments](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2processfailedeventargs?view=webview2-dotnet-1.0.4258.31) as distinct kind/reason/exit-code information. Reasons can distinguish launch failure, resource exhaustion, crash and other failure categories; they must be observed, not inferred from the process name. FailureSourceModulePath can identify a failed code-integrity load; this investigation does not disable or bypass that check.

The [environment BrowserProcessExited event](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2environment.browserprocessexited?view=webview2-dotnet-1.0.4022.49) also fires for normal shutdown and is distinct from ProcessFailed. Their order on a crash is not guaranteed. Its [event arguments](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2browserprocessexitedeventargs) provide exit kind and browser PID. Consequently the old log's string BrowserProcessExited does not establish environment resource-release completion or normal shutdown.

[Microsoft's process-event guidance](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/process-related-events) describes GPU failures as potentially auto-recoverable and recommends collecting detailed failure information and local crash reports. The generic existing fallback may therefore be a separate recovery-policy question; this stage does not change it or treat an unrecovered renderer/browser as usable.

The [threading contract](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model) requires STA, a UI message pump and callbacks on that thread. No synchronous wait or nested modal loop was found on the inspected startup/clear path. [ClearBrowsingDataAsync](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2profile.clearbrowsingdataasync?view=webview2-dotnet-1.0.4258.31) supplies asynchronous clear operations, not a promised completion deadline or proof that this browser crash originated in clearing. Both AllProfile calls remain unchanged.

The [FailureReportFolderPath property](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2environment.failurereportfolderpath?view=webview2-dotnet-1.0.4258.31) identifies the environment's diagnostic folder. Existing [IsCustomCrashReportingEnabled=true](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2environmentoptions.iscustomcrashreportingenabled?view=webview2-dotnet-1.0.4258.31) remains set; Microsoft documents that this prevents Windows sending crash data to its endpoint. No log/dump was uploaded by this stage.

## Minimal diagnostic change and future decision

The existing DEBUG host records now include sequence, closing/failed state and fixed markers around fallback, timeout/control disposal, close-time clearing and immediate disposal. The guarded test fixture adds UTC/sequence, loaded SDK assembly version, ProcessFailed reason/exit code/hex code, bounded runtime description and module basename, and environment browser-exit kind/PID. Its failure-report path is emitted only relative to its owned user-data folder; outside paths are replaced with a fixed label. It stays subscribed through the existing host shutdown, then detaches with disposed-COM guards. There is no added wait, retry, navigation, recovery attempt or filesystem scan in these handlers. Both logs retain their 64-record limit; absence of a late event, including after detachment or budget exhaustion, is not proof it never occurred.

After separate KAG resource coordination, at most one new default-permission, synthetic-Profile startup could use these changed observations, the same runtime, a fresh owned user-data folder, no JVM/model and the existing 30-second boundary. This run is **not performed here**. It would distinguish:

1. Failure reason/exit code delivered before any app fallback/close/dispose marker: inspect that actual failure category; preserve any owned dump. LaunchFailed/IntegrityFailure would require a separately scoped investigation, not an exception to sandbox or integrity policy.
2. A later environment exit correlated to the same PID: separate a Failed exit from Normal exit after explicit app disposal. Earlier ProcessFailed notifications still require explanation.
3. Continued profile-clear wait without a process failure after valid subscriptions: consider the native clear/completion path separately. No such healthy-browser observation exists yet.

Stop on the first refusal, timeout or unrecovered failure. Do not repeat the same run, extend timeouts, disable GPU/sandbox, skip AllProfile, alter the runtime/system/ACL, use real Profiles, or automatically escalate the desktop launch. If the additional fields still do not discriminate, report the exact remaining need (for example, reviewed symbol-based analysis of this owned dump) before any new tool/network/execution request. No different execution mode is justified by the current evidence alone.

## Verification, rollback and same-version delivery

`baseline-d772c9a.bundle` in `.verification/memory-mainline-readiness-20261109` was reverified before edits: complete history, exact HEAD d772c9a; SHA256 `2A5799576876B738463BA9E822A2F3931330F0C95CE47CBFC04BFEA2E0A59212`. The six protected user documents matched their preserved SHA256 values before edits.

One no-restore incremental managed build of the existing test host passed in 5.99 seconds, zero warnings/errors, with Mmf3ProfileAcceptanceBuild, RuntimeLifecycleProbe and FrontendSkipBuild enabled and the existing trusted-development manifest. Existing dependencies/assets were reused/verified. Log: `.verification/memory-webview2-failure-investigation-20261109/focused-build.log`. This validates SDK API wiring at compile time, not live event delivery, browser recovery, cleanup success or Memory desktop acceptance. No frontend rebuild, package, browser, JVM, model, database or test suite was run.

A future distribution stage should start from a clean final committed source, build frontend and host from that one commit, record it in the bundle manifest, verify copied asset hashes, and perform the cancel/explicit-save/independent-reopen flow against that exact bundle with synthetic data. The existing preview manifest still records `c3a181a17db9f9ff814c60392cd04fe128a4173b` with sourceDirty=true and productionGo=false; it cannot certify d772c9a or these diagnostics. No package was regenerated here.

During build-input discovery, an overly broad `rg --files --hidden --no-ignore .verification` filename search encountered Access denied at other verification directories. That enumeration was stopped and not retried or bypassed. It was a filesystem read refusal, not a visible auto-review decision; no protected file contents were obtained. Subsequent build input used the already identified exact manifest path. No permission or security setting was changed.

## 2026-11-10: stale SOURCES build input identified and repaired

The next coordinated observation stopped before creating a synthetic Profile: the existing probe's `--prepare-profile` returned `SYNTHETIC_PROFILE_STOP stage=SOURCES hresult=0x80131620`, exit 10. No WPF, WebView2, JVM or model started. Log: `.verification/memory-webview2-startup-details-20261110/profile-prepare.log`. Port 18765 remains unknown because its follow-up query failed; it was not queried through another route.

Read-only PE resource inspection of the actual fixture's `PersonalAiWorkspace.Desktop.dll` found the exact first admission failure. This is evidence from the embedded data and file hashes, not an inference from the HRESULT:

| Item | Observed value |
| --- | --- |
| Old Desktop DLL SHA256 | `6FA529B3FB69E04D73AE57275D4AA367A8D99CAAF328900D335A1562DA10CCDD` |
| Resource | `TrustedDevelopmentRuntimeSources`, Format 2, 27,201 bytes, 163 exact files |
| Old resource SHA256 | `728F87CD2B3778116003A22AA8905E1E8E2C646E5C3DF6CDAF04D1786F67B558` |
| First entry | Pinned JNA JAR passed, SHA256 `b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0` |
| First failure | `D:\IDEA\Daima\personal-ai-workspace\target\personal-ai-workspace-0.1.0.jar`, readable; SHA256 mismatch |
| Expected | `7565540d65b9d666a95cef0475d44d2b79656d80f736e681ca0ced6f719dfb7a` |
| Actual | `71f181bf8d991aa5484c9efb83d548ec275644407b388cd68be220421df5dffd` |
| Throw site | `TrustedDevelopmentRuntimeSources.Open`, `TrustedDevelopmentRuntimeSources.cs:67`, source-hash comparison throws an unmessaged IOException |

The earlier incremental diagnostic build selected the wrong input: `.verification/trusted-development-runtime-sources.local.json`, dated October 28, whose SHA256 is exactly the old embedded resource's SHA256. Its JAR pin is stale. The current JAR's hash instead matches the already reviewed [Memory desktop delivery report](MEMORY-DESKTOP-DELIVERY-REPORT.md) and `runtime/personal-ai-workspace.jar` in the existing final preview's `development-bundle.json`. Nothing was missing or unreadable at the first failing path, and no access refusal occurred during these exact-path checks. Later old-manifest entries were not needed to establish its first failure.

The existing acceptance-specific input `.verification/memory-desktop-delivery-20261109/test-sources.json` already pins that reviewed Runtime/JNA and its bundled Java 21 under `artifacts/PersonalAiWorkspace-memory-preview-20261109-215302/runtime`. It is Format 2, 111,048 bytes, 420 exact paths; SHA256 `160EB79B6AB43780B963B9187FFEDAE2DAADD38D9B4EC96674C2DEEED3634C0E`. All 420 paths were checked for the existing path/reparse rules, readable file streams and exact SHA256 equality. The Java release also identifies version 21. No directory enumeration, real Profile inspection, generic new trust entry or relaxed validation was needed.

The minimal repair was to reuse this existing reviewed acceptance input in one incremental managed build, rather than edit the old manifest's expected hash or change application security code. Neither manifest, the Runtime JAR nor any product/test C# source was modified. Reproducible build command from the repository root:

```powershell
dotnet build desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore --configuration Debug --artifacts-path D:\IDEA\Daima\personal-ai-workspace\.verification\memory-desktop-delivery-20261109\dotnet -p:Mmf3ProfileAcceptanceBuild=true -p:RuntimeLifecycleProbe=true -p:FrontendSkipBuild=true -p:TrustedDevelopmentManifest=D:\IDEA\Daima\personal-ai-workspace\.verification\memory-desktop-delivery-20261109\test-sources.json -nologo
```

The build passed in 5.87 seconds, zero warnings/errors. Direct inspection of its resulting embedded resource confirms SHA256 `160EB79B6AB43780B963B9187FFEDAE2DAADD38D9B4EC96674C2DEEED3634C0E`, matching the reviewed acceptance input byte-for-byte; all 420 newly embedded pins also match their exact files. New copied Desktop DLL SHA256: `4152E4E43E5972348623E6FB906BFC32FC1EBA06733FCC1B25B9DC4F22D8B16D`; test DLL: `B449029BB9980DB4DC450DCC9D22257B799A1DC641E497C7E8CB77D8825E03AF`. Both DLL/PDB CodeView identities match, and all three diagnostic source-document checksums match the unchanged 2f453ad source. This establishes build-input consistency, not runtime admission or WebView2 success.

Before repair, `.verification/memory-source-admission-repair-20261110/baseline-2f453ad.bundle` was verified to contain exact HEAD `2f453adeeb29b6d1381617c5812bd9f441b725f0` and complete history; SHA256 `3C2B3737D714376D25B7045834FA56B4F2676C9F8F2FEAFE1E2C983C067195B2`. The previous Desktop DLL was retained as `PersonalAiWorkspace.Desktop.before.dll` in the same evidence directory. The six protected user documents matched their preserved hashes.

That evidence directory also retains `first-failure.json`, `reviewed-manifest-validation.json`, `focused-build.log`, `embedded-after.json`, `binaries-after.json` and a read-only exact-resource/pin inspection script. No Profile preparation or desktop launch was repeated for this repair. No frontend/runtime/native-helper rebuild, package generation, model/JVM, port-query retry, ACL change or trust-check bypass occurred. Only this report is a tracked-file change.

A future preparation/30-second startup requires a newly coordinated window and must use these repaired binaries. The changed embedded manifest is the material new condition; the previous attempt did not reach Profile creation. Stop again if admission fails. If admitted, collect the already specified failure details and lifetime markers once; do not automatically run extra desktop attempts. The old WebView2 crash, real Memory save/reopen and final same-version bundle acceptance remain unresolved.

## 2026-11-10: corrected-input startup result and preserved first failure

One coordinated run from source baseline `94627d7857c2e62f57f0bbcd52264a77dffe21bd` used the repaired fixture. Preparation succeeded once, producing synthetic Profile `41b9c947f7e3402cac5c4105edd2b3f3`. A post-check initially derived LocalAppData from the PowerShell environment (`C:\Users\28421\AppData\Local`) rather than the product's token/handle binding; it incorrectly reported the new folder missing. Preparation was not repeated. The exact new Profile under `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles` was then confirmed, with an empty `webview2-profile` before startup. This correction did not alter product binding, ACLs or any existing Profile.

The single `--memory-draft-host 41b9c947f7e3402cac5c4105edd2b3f3 startup` ran with default permissions and SDK 1.0.4258.31 / runtime 157.0.4341.42. Unified UTC timestamps establish:

| UTC on 2026-11-10 | Observation |
| --- | --- |
| 01:47:20.9168613 | Controller callback succeeded, owned browser PID 444. |
| 01:47:20.9189934 | GPU failure: Reason=Crashed, ExitCode=-1073741790 / `0xC0000022`. |
| 01:47:20.9200113 | Main renderer failure: Reason=LaunchFailed, ExitCode=49 / `0x00000031`. |
| 01:47:20.9206115 | PROFILE_CLEAR_START, after those notifications. |
| 01:47:20.9219850 | FALLBACK_START; later GPU notifications follow. |
| 01:47:21.2843359 | Browser ProcessFailed: Reason=Unexpected, ExitCode=-2147483645 / `0x80000003`. |
| 01:47:21.3015649 | Environment BrowserProcessExited: PID 444, exit kind Failed. |
| 01:47:50.5029794 | STARTUP_TIMEOUT at host 30049 ms. |
| 01:47:50.5030688 | First explicit CONTROL_DISPOSE_START, after the failed browser exit. |
| 01:47:50.6141810 | Fixture HOST_SHUTDOWN_START; close-time clear subsequently failed, then disposal/teardown completed. |

Descriptions and FailureSourceModulePath basenames were empty; there is no identified denied file/module/object. Thirty dispatcher heartbeats continued on matching STA UI/HWND thread 14872. The thread desktop remained CodexSandboxDesktop and input desktop Default. The fixture returned exit 10, no READY, no Java PID, zero model calls, selectionWriterUsed=false and no Memory bridge writes. The Memory save/close/reopen flow was not reached.

The [Microsoft status-code reference](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-samr/7440cfac-6052-4925-84e4-c32e417de300) identifies `0xC0000022` as STATUS_ACCESS_DENIED. [Chromium's current upstream sandbox ResultCode enum](https://github.com/chromium/chromium/blob/main/sandbox/win/src/sandbox_types.h) defines 49 as SBOX_ERROR_CANNOT_CREATE_RESTRICTED_TOKEN. That is a relevant process-launch hypothesis, not proof of the exact Edge 157 implementation or which access check failed. The renderer's API-provided LaunchFailed reason is directly observed. These data do not identify a defective GPU driver, damaged repository ACL, or a needed antivirus/AppLocker change. No such settings were inspected or changed.

The environment exit was explicitly Failed before application disposal, so normal timeout shutdown does not explain it. [Microsoft's event guidance](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/process-related-events) distinguishes environment exit from per-control ProcessFailed and warns that some GPU failures can recover automatically. The existing fallback's strict handling remains unchanged; a renderer failed to launch and the browser also failed in this run. AllProfile did not reach its completion stage, so no cleanup success is claimed.

Cleanup read only the two IDs owned by this run: host 25536 and browser root 444 were both absent after exit. A complete descendant list was not enumerated. No unrelated process was stopped; no JVM/model/service was started. Port 18765 remains UNKNOWN from the earlier failed query and was not re-queried by another mechanism. The exclusive runtime window was released to KAG. The synthetic Profile and diagnostics are retained for forensics, with no real Profile or selected-profile preference write.

Evidence: `.verification/memory-webview2-corrected-startup-20261110/` contains `profile-prepare.log`, `owned-profile.json`, `startup.log`, `startup-summary.json`, and `command-result.json`. The synthetic Profile's `evidence/memory-draft/startup-result.json` and `startup-failure.json` record the actual failed outcome. No raw dump or log was uploaded.

### Small application fix supported by this run

The production handler had already diagnosed BROWSER_PROCESS_FAILED at host 467 ms, but left StartupFailureCode unset. The outer wait later assigned STARTUP_TIMEOUT, so consumers only received the later wait failure. During pending initial readiness, the handler now records BROWSER_PROCESS_FAILED with null-coalescing assignment; the outer exception path also uses null-coalescing assignment. A subsequent timeout still emits its own timed diagnostic. Failures after initial readiness do not become startup codes, and the existing fallback still fails closed.

This change does not cancel a browser launch, navigate, retry/recover a process, bypass profile clearing or declare readiness. The 30-second host limit, 3-second close limit, AllProfile calls and late-completion guards remain intact. An observer can now stop waiting when an already-known failure is published and request the existing bounded close path. The change corrects reporting; it does not remedy the native process-access/launch failure.

One incremental no-restore build with the reviewed `test-sources.json` and FrontendSkipBuild passed in 5.77 seconds, zero warnings/errors. It completed before the next KAG-only build/test window instruction. Log: `.verification/memory-startup-first-failure-20261110/focused-build.log`. No further startup, browser, test suite, frontend rebuild or package was run to validate this change; live behavior remains unverified. The pre-change rollback is `.verification/memory-startup-first-failure-20261110/baseline-94627d7.bundle`, exact HEAD and complete history verified, SHA256 `F7B20CE34D157D1F5F7F2D4D46E7F168BE93CF3755FCFC9746E3AFABDA0E8B69`. All six protected user documents matched their preserved hashes before the edit.

### Different next action requiring explicit execution-mode coordination

Do not repeat the same default-sandbox startup. A bounded comparison through the supported single-call approval mechanism is now a concrete candidate: use the same reviewed fixture executable, first `--prepare-profile`, then read the returned new Profile's native binding, then `--memory-draft-host <that-new-ID> startup`, with separate narrow approvals and the same 30-second limit. Paths must come from product/native binding, not PowerShell environment assumptions. No JVM or Memory write is needed for this comparison. The hypothesis is that the current caller token/desktop context prevents Chromium child startup; a different context is not assumed to succeed and is not a product fix by itself.

This would request sandbox-outside execution, not Windows administrator elevation. It changes the caller's file/desktop access and therefore requires explicit authorization for these exact operations plus a resource window; earlier Git approvals do not authorize it. The new Profile must belong to that execution identity, remain isolated, and retain normal private creation/binding checks. Do not reuse the sandbox account's Profile, weaken Chromium sandbox/GPU/integrity policies, switch the session to Full access, change ACLs/settings, or run the full application with real selection preferences. No such execution-mode request or operation has been made in this stage.

If that comparison is not authorized, the existing owned crash evidence can instead be reviewed with an explicitly scoped symbol/debugger plan; no debugger or symbol download is currently authorized or installed by this task. The exact denied operation/object and a verified runtime-specific mapping of renderer exit 49 remain unknown. Real Memory persistence and same-final-commit distribution acceptance remain blocked on a working supported desktop context.

## 2026-11-10: one formally requested context comparison

After explicit coordination, a single `require_escalated` request ran the reviewed comparison script `.verification/memory-startup-context-compare-20261110.ps1` (SHA256 `71CAAAB31B9B0BA7E61BB451060B92E90C615F84E6C7247F98BE4C089E7DDADE`). There was no prior denied/pending request for this action. The tool actually executed it; no independent approval-event/reason was visible. The tool invocation ultimately reported exit 1, while the separately recorded startup child exited 10; these are distinct results, not an approval rejection.

Exact binary hashes were checked before execution. Only `--prepare-profile`, the new ID's `VerifyProfileNative`, and that ID's startup were run, once each, with bounded waits. Preparation PID 11548 and native binding PID 7616 both exited 0. Synthetic Profile `9d0dfa367b6d4608903829826ae56ca8` had an initially empty UserDataFolder. Its handle-bound root was in the Codex MSIX LocalCache under `D:\WpSystem` (owner SID omitted here), rather than the sandbox account's root. No selected-profile preference or real Profile was used. This was sandbox-outside execution, not a Windows administrator-elevation request, and no existing ACL/security policy was changed.

| Observation | Previous default-sandbox run | Approved-request comparison |
| --- | --- | --- |
| Thread/input desktops | CodexSandboxDesktop / Default | Default / Default |
| Environment | Created | Created |
| Controller | Success; browser PID 444 | No successful callback; CONTROLLER_START timeout at about 30 seconds |
| Process failures | GPU Crashed/C0000022, renderer LaunchFailed/49, browser Failed | No CoreWebView2 existed to subscribe to ProcessFailed; absence of events proves nothing about child health |
| Callback after disposal | Not the initiating failure | IsSuccess=false, HResult=-2147467260 (`0x80004004`), delivered after CONTROL_DISPOSE_COMPLETED |
| UI pump | 30 heartbeats, matching STA UI/HWND thread | 30 heartbeats, matching STA UI/HWND thread 2732 |
| Result | Startup child exit 10 | Startup child exit 10; outer 55-second watchdog did not fire |

Comparison host PID 26864 exited normally from the wrapper's perspective (no forced kill); controller creation did not yield a browser PID. Browser descendants and port 18765 remain UNKNOWN, not assumed absent. No JVM/model/service or Memory flow started. The active window was released to KAG, with these unknowns stated. Only existing workspace logs were read afterward; no global process scan, alternative port query, unknown-process termination or additional startup was attempted. Raw stdout/stderr, owned binding, individual process outcomes and `result.json` are retained in `.verification/memory-startup-context-compare-20261110/`.

This comparison did not produce READY. It changes caller desktop/account/profile binding; the selected runtime version was not captured before controller completion. It therefore neither proves that the Codex sandbox is the sole cause nor identifies a safe application/runtime workaround. The same default and sandbox-outside startup paths are not repeated. Full Memory save/close/reopen and final-version package acceptance remain unverified.

### Bounded ownership observation before controller creation

The comparison exposed a cleanup-evidence gap: the fixture observed environment exit/PID/version only after a successful controller callback. The acceptance-only DEBUG host now publishes its environment immediately after validating the synthetic UserDataFolder binding. The fixture subscribes to BrowserProcessExited and ProcessInfosChanged there, reads version/report-folder metadata, and remembers at most eight browser root IDs. The old controller-success path reuses that subscription rather than adding duplicates; teardown removes it with disposed-COM guards.

[GetProcessInfos](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2environment.getprocessinfos?view=webview2-dotnet-1.0.4258.31) returns processes using the same UserDataFolder, excluding crashpad. Here that folder is unique and synthetic. The fixture records only browser root IDs from this API or this environment's exit event, never system-wide process data, command lines, frame content or other Profile directories. It does not kill anything. [ProcessInfosChanged](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2environment.processinfoschanged) supplies updates without waiting for a controller. The existing 64-record bound remains. Missing observations still cannot establish absence of all descendants or crashpad.

One explicitly coordinated, no-restore incremental build passed in 5.85 seconds, zero warnings/errors, using the same reviewed acceptance sources and FrontendSkipBuild. Log: `.verification/memory-environment-ownership-20261110/focused-build.log`. No new startup, model, JVM, service, frontend rebuild or test suite was run. Live early-event delivery remains unverified, and this does not retroactively identify the last run's browser processes. The build window was released immediately afterward.

Before edits, `.verification/memory-environment-ownership-20261110/baseline-e210c1d.bundle` was verified to contain exact HEAD e210c1d and complete history; SHA256 `1666F3EB34857345E9B86E141A39DE5E36AB3B9C4C20E6FF293835CD8F650F73`. No Memory feature/security contract, ordinary release startup, timeout, cleanup scope or permission policy changed. Subsequent work should address real independent Memory/delivery gaps rather than expand startup experiments.
