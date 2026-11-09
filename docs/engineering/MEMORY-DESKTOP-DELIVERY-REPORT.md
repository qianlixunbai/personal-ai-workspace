# Memory desktop delivery preview

2026-11-09. Development-only, unsigned preview. This delivery includes the Profile logical/resolved path binding and DACL-only export fix (`ceeaafa`), Knowledge answers to explicit Memory draft review (`81adac9`), and the provenance wording clarification (`c3a181a`). No production release, model-quality certification, or durable Knowledge-to-Memory lineage is claimed.

## User-visible provenance clarification

Draft review identifies an assistant answer as unverified. That role is transient editor state. The persisted Memory schema has `source=MANUAL`, not an answer ID or role. The UI now explicitly says the record was confirmed for saving by the user, not that its contents were verified, and that source roles, conversations, answers and citation relationships are not retained. This is a wording clarification only: no schema migration or automatic save/model behavior was added.

Focused KnowledgeAnswer and MemoryPage tests: 31/31 pass; TypeScript and frontend production build pass. The first check caught a missing JSX closing brace in the new label before any package or runtime use; it was corrected and the original failed log retained. Build retains the existing >500 kB chunk warning.

## Final artifact

- File: `artifacts/PersonalAiWorkspace-memory-preview-final-20261109-215731.zip`
- Size: 384,666,660 bytes
- Product commit: `c3a181a17db9f9ff814c60392cd04fe128a4173b`
- SHA256: `8e9f17ea4c7ffe17999aab5ab8c76ab8b1d4357f4b21fc47d38b0b9d477b4d99`
- Runtime SHA256: `71f181bf8d991aa5484c9efb83d548ec275644407b388cd68be220421df5dffd`
- The earlier `PersonalAiWorkspace-memory-preview-20261109-215302.zip` is retained but superseded. No existing artifact was overwritten.

Seven extraction/relocation/tamper contract checks passed. `sourceDirty=true` records the six preserved user documents and concurrent test-only fixture edits; product source matched the named commit. Contract expected rejection exit code 10 caused an outer wrapper diagnostic on the first package despite its PASS result; the original log is retained and final integrity independently rechecked.

The package contains the trusted bundled JDK/JNA, refreshed production frontend and self-contained Desktop .NET 10.0.14. NativeBootstrap exact pins remain enforced. The helper still has its documented external .NET 10 prerequisite; WebView2 Evergreen is also required. This is a development ZIP, not an installer.

## Acceptance scope

A test-owned WPF Application hosts the production MainWorkspaceWindow/WebView2, native Memory/Knowledge bridges, DevelopmentRuntimeOwner and Java/SQLite persistence. It does not use AssistantApp's selection writer, tray, or global hotkeys. The complete packaged EXE is not launched: even an explicit Profile argument reaches a delayed selected-profile preference write after ready. A parse-only proposal to launch it was corrected before execution.

Only the freshly prepared synthetic Profile `b418abf985534bec935831ffac92a197` is used. A deterministic successful historical answer is seeded offline into that owned Profile after runtime initialization and shutdown, then read through the real runtime. Its citation ID is synthetic, not a source-ingestion or citation-navigation test. No model call, download, Ollama, training or unrelated PostgreSQL/full-suite run is involved.

The first desktop run failed before editing: WebView2 environment creation succeeded, but controller creation reached `CONTROLLER_START / STARTUP_TIMEOUT` at 30 seconds. Runtime availability and history reading had passed; no save was reached. This is not a successful end-to-end desktop Memory acceptance. The original logs remain intact. One bounded retry changed only test-host window creation order to match the product startup path and assigned MainWindow explicitly. It reproduced the same controller timeout. No WebView or OS security setting changed. No third attempt was made. Actual desktop cancel/save/reopen was therefore not reached, and no Memory was written by this acceptance. The root cause is not established; redirected profile paths are not proven responsible.

Runtime observations, original failure logs and final isolation checks are recorded in the accompanying `.verification/memory-desktop-delivery-20261109` evidence. Unknown-result replay prevention is covered by the focused UI test; desktop transport-loss fault injection is not claimed.

## Rollback and safety

Rollback tag: `workspace-before-memory-desktop-delivery-20261109` at `81adac93900cc110fe1ef9371bbbff51588f6df4`. The six user-owned untracked documents are outside all commits. No push or main update; no existing Profile database, selected Profile preference, external service, Windows/repository ACL, security setting or sandbox change. Private ACL setup applies only to the newly created owned fixture Profile through existing product code.

## Local-only delivery

The user requested local project outputs only. The final ZIP remains in artifacts; no Space/Library upload was initiated. Upload helper preparation did not create or transfer a Library item.

## Final observed result

- Native preparation, real Runtime startup, SQLite access and retrieval of the seeded historical answer passed.
- Desktop draft cancellation, explicit save, repeat-click protection and independent saved-record reopening were never reached because both WebView2 controller attempts timed out. This acceptance remains blocked, not passed.
- Independent read-only inspection after teardown found zero Memory records and one unchanged historical answer.
- The acceptance harness compiles with zero warnings/errors. It is retained for further diagnosis, not marked as successful acceptance.
- Owned application/JVM/conhost exited, no WebView2 process tied to this synthetic Profile remained, runtime port 18765 closed, and no forced termination was needed.
- Final selected-preference and all six protected-document SHA256 checks match the baseline. `main` is unchanged. The synthetic Profile and compact failure evidence are retained; existing Profiles were not modified.

Evidence: `.verification/memory-desktop-delivery-20261109/{desktop-summary.json,offline-after.json,teardown.json,final-isolation.json,attempt-1/,attempt-2/,save-1.log,save-2.log,final-test-build.log}` and `.verification/memory-desktop-delivery-package-20261109/final-{build.log,contract.log,result.json}`. Final ZIP was independently rehashed after tests. No Library upload started, and the upload task was cancelled on the user's local-only instruction.