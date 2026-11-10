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

## Continued work

Static review of `MemoryWindow.WindowClosing` and the existing `BusySerializesOperationsCloseCancelsAndLateResponsesCannotRepopulateUi` check confirms that closing during a dispatched save currently cancels its token and discards the visible outcome. A native write-close guard is the next concrete product task; no browser/security-condition experiment is required.
