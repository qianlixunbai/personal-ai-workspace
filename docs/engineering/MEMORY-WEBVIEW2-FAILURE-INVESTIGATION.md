# Memory desktop WebView2 failure investigation

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
