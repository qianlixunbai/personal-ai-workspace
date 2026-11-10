# Desktop fallback fixes and native editor continuation

## 2026-11-10: corrected development delivery

Module 1 starts from clean tracked source `2bface5c0451cc8c38e169bd10bbc5fe4c9355b9` on `codex/knowledge-version-compare`. No AGENTS.md or .agents directory is present in this repository, and no AGENTS.md was found in its parent directories. Six untracked user documents were preserved and compared with their existing SHA-256 baseline.

The new [development ZIP](../../artifacts/PersonalAiWorkspace-webview-lifecycle-fixed-20261110.zip) contains the close-after-disposal correction `50a4614` and fallback zoom correction `2bface5`. The [unpacked EXE](../../artifacts/PersonalAiWorkspace-webview-lifecycle-fixed-20261110/PersonalAiWorkspace.Desktop.exe) belongs to the same complete bundle. The original `PersonalAiWorkspace-native-entry-verified-20261110` directory and ZIP remain untouched.

Only Desktop was republished, from committed product source, with existing reviewed relative Runtime pins, self-contained .NET 10.0.14 and bundled frontend. The build completed without warnings/errors in its log. Runtime and frontend source have no changes since the prior actual-EXE acceptance source `cff8015`, and every reused Runtime/frontend file matched its old manifest hash. The published Desktop DLL equals the actual build output; the temporary native-controller experiment marker is absent. Source remained clean during the build.

| Delivery evidence | Result |
| --- | --- |
| ZIP bytes | 384,673,297 |
| ZIP SHA-256 | `9619442AC0A8A77F5B874C51334191AE1A97CECC02FAF53657EAFD898E5049A2` |
| Desktop DLL SHA-256 | `6BC74B8EAA58EB70D0A79A5019BA2F981FB0AB0B33A9C8A2B6B407D56E8CA68B` |
| Runtime JAR SHA-256 | `BC8F0CC35D2F9321AF848F7FFDFE271DB07B770CF1878454F07A8FD0C0D12625` |
| Full payload | 933 manifest files verified before and after actual ZIP extraction/relocation |
| Embedded trust inputs | Existing `--verify-package-sources` probe passed against the extracted product assembly; tooling was added only to the disposable extracted copy |
| Differences from accepted old payload | Desktop DLL, Desktop EXE, Core DLL and README; all other payload bytes unchanged |
| Old ZIP | Still `1B4D7A1E00B049493F9262903DCB82D53438A62EAB4565C20291E9D30516DF47` |

The changed Core DLL comes from republishing the project dependency at the new source revision; no Core source changes were introduced in this module. The unsigned completeness manifest records clean tracked product source and packaging-only scope, and does not claim new EXE acceptance. It is not publisher identity or Production GO.

Evidence: `.verification/webview-fixed-delivery-20261110/{Build.ps1,publish.log,embedded-sources.log,result.json,lock.json}`. Build and packaging used the Session 1 `Local\Dot_KAG_Workspace_HeavyRun` mutex, PID 1984, released at 07:37:51 UTC. No product EXE, Profile, browser, Java Runtime, database, model or temporary service was started. No system/security setting or denied read range was touched.

The earlier [full EXE native Memory save/reopen result](MEMORY-EXE-ENTRY-REPORT.md) remains historical evidence for `cff8015`, not a fresh end-to-end acceptance of this build. The two corrected behaviors already have the focused [STA lifecycle evidence](MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md); that check and the real persistence loop were not repeated for packaging. Normal WebView2 initialization remains unresolved. Normal pages, default no-argument entry, maintenance/migration, PG desktop, cross-machine behavior and formal release remain unaccepted. Database file hashes changed on the earlier reopen; no whole-database zero-write claim is made.

## 2026-11-10: retain a pending native Memory write

Module 1 delivery documentation is committed as `547e1d4`. Module 2 follows the confirmed close-during-write gap: `MemoryWindow.WindowClosing` allowed close after draft-discard confirmation, and `WindowClosed` cancelled the same token passed to the dispatched mutation. A status change/delete has no necessary dirty draft, so it could close with no warning at all. The old focused check explicitly expected this cancellation behavior.

The existing check now requires the result window to remain open during Save and a clean-editor lifecycle write. It failed against the old product at the first `TryClose()` assertion (window actually closed). The fix marks the entire mutation operation pending and refuses close with a wait-for-result message until it finishes. This also covers archive, restore and delete through their existing shared `RunAsync(mutation: true)` path. `AssistantApp.ExitAsync` and profile switching already stop if `CloseMemory()` returns false; no new exit path was introduced. RuntimeClient's existing eight-second request timeout remains intact. Successful writes, bounded failures and uncertain outcomes release the close guard; uncertain writes keep the existing replay protection. Ordinary read cancellation and draft-discard confirmation remain available.

After the fix, the two affected checks passed, 2/2: pending Save followed by a confirmed response, pending lifecycle followed by a lost response, duplicate-action exclusion and close after completion; plus the existing independent list-read close/cancellation check. The failed baseline and final TRX/logs are retained under `.verification/memory-write-close-20261110/`. Incremental build reports zero warnings/errors. No new test suite, Profile, browser, actual HTTP/database, JVM or model ran. The shared Session 1 heavy mutex was released after each build/check; final PID 17388, 07:40:51 UTC. No real EXE/tray interaction is claimed by these STA checks.

At this point the module 1 ZIP still contains product `2bface5`; the new write-close correction is a source/build change, not yet a newly packaged EXE acceptance.

## 2026-11-10: match the write receipt to the submitted draft

Module 2 is committed as `6e77ea8`. Module 3 found that `CreateMemoryAsync` checked response shape, Location and ACTIVE status but did not bind type/title/content or initial revision to the submitted draft. Update likewise accepted unrelated valid text and any later revision. `MemoryWindow.SaveAsync` would then load that unrelated item, clear the draft and show success. This is a reproduced client-validation gap; there is no claim that a real user database or the current Java server returned such a response.

The existing client check and native editor unknown-outcome check were extended with inconsistent synthetic replies. Before the fix, the client accepted a different valid title, and the editor lost its dirty state after a successful fake-store write whose response substituted unrelated content. Those two assertions failed; the previous write-close check passed. The baseline runner additionally emitted a `PortableSymbolReader.Dispose` NullReferenceException while cleaning up source information; that separate tooling failure is preserved in `before-test.log` and was not described as a product failure.

The client now requires exact submitted type/title/content in create/update acknowledgements. Create requires revision 1; update and lifecycle responses require exactly one revision step, matching the existing `MemoryStore` transaction contract. ID, source, Location, status, Unicode and timestamp checks remain. No input is trimmed or rewritten. A mismatch is `InvalidResponse`, so the native editor's existing unknown-outcome guard keeps the submitted draft and blocks replay until explicit reload/reconciliation. The shared client also serves the bridge; no frontend schema, Runtime, database or model change was needed.

After this correction the three focused checks passed, 3/3, with zero build warnings/errors. They cover valid create/update, mismatch of each submitted field/revision, a lifecycle revision jump, native draft preservation/no replay/explicit reload, and the pending-write close behavior. No larger suite or actual persistence loop was run. Logs/TRX are in `.verification/memory-write-receipt-20261110/`. One intervening attempt reported `HEAVY_LOCK_BUSY` and ran no build/check; static review continued before retry. Final held lifecycle PID 28160 released the Session 1 mutex at 07:44:43 UTC. No Profile, browser, actual service/database, JVM or model was started.

## Final delivery and handoff

Module 3 product commit is `eeeaeb440bcf11a54d59fd86715d81c97420f125`. All three tasks are complete. Final packaging closure rebuilt this clean committed product into [PersonalAiWorkspace-native-memory-guarded-20261110.zip](../../artifacts/PersonalAiWorkspace-native-memory-guarded-20261110.zip), also available as a [complete unpacked EXE](../../artifacts/PersonalAiWorkspace-native-memory-guarded-20261110/PersonalAiWorkspace.Desktop.exe). Both the earlier accepted bundle and the new module 1 bundle remain preserved.

| Final delivery evidence | Result |
| --- | --- |
| ZIP bytes | 384,673,905 |
| ZIP SHA-256 | `F5634EAA06CB4E6D33369EA99C29B475BA492B4D4594836C7F38971E12B6AE9C` |
| Desktop DLL SHA-256 | `601F10C7C1D622056F0B2D2F69FE0E2CAAD6EB7EC28B4AE9A204BD566EB12082` |
| Actual source | `eeeaeb4`; tracked product source clean throughout packaging |
| Complete payload and actual embedded sources | 933 files verified after ZIP extraction/relocation; actual product assembly source probe PASS |
| Reused Runtime/frontend | Every byte matches the prior accepted package; Runtime JAR hash unchanged |
| Changed payload from original accepted package | Core DLL, Desktop DLL, Desktop EXE, README only |

Final evidence is `.verification/native-memory-guarded-delivery-20261110/{Build.ps1,publish.log,embedded-sources.log,result.json,lock.json,final-state-before-docs.json}`. The publish has no warnings/errors in its log. The shared mutex was held only for the full build/package/probe lifecycle, PID 34000, and released at 07:46:19 UTC. No product EXE, Profile, browser, Runtime service, database or model was started in this round. Port 18765 was not listening at final inspection; no unknown process was terminated or inspected through a previously refused route. No KAG port or other repository was touched. Six user documents still match their baseline; main stays `f5266bf4383f52a2293b0fe4bfe1964af958e64a`; no push.

This is **Development-only / unsigned, packaging verification only**, with the precise new close/receipt checks above. The earlier full EXE save/reopen acceptance remains evidence for `cff8015`. Normal WebView2 pages, no-argument default entry, maintenance/migration, PG desktop, cross-machine and formal release remain unaccepted. WebView2 root cause is still unknown; EFS/private ACL observations are not a diagnosis, and the earlier native experiment's three Job-cleaned descendants must not be reported as natural exits. The historical forbidden Crashpad path and denied extra process-metadata reads were not revisited. No new action was denied or left pending in this round.

Stop this round here. A concrete next independent source finding is `MainWorkspaceWindow.OpenAssistant`: its catch overwrites the original browser failure text, unlike the Memory fallback's separate status; it also lacks that route's pending/closed guards. This is a static finding awaiting one focused reproduction, not a newly accepted runtime fault. Resolve it only if the next session confirms the user impact; do not repeat the same controller timeout, real Memory persistence loop, or broaden a security-condition experiment. Any browser security/location comparison still needs an exact independently authorized action/target/risk, and must preserve the stated restrictions.
