# Native Memory daily editing flows

2026-11-10, starting at `a6b14f9` on `codex/knowledge-version-compare`. Repository and parent directories contain no AGENTS.md or .agents skills. The six untracked user documents and main are preserved.

## 1. Compare a saved record without losing a new draft

The native editor already had create/update, search, paging, archive/restore/delete and backup entry points. Unlike the web editor, it could not use a new draft to revise an existing record: selecting a list item discarded that draft after confirmation. The next priority is this actual missing editing operation, rather than the previous handoff's separate Assistant failure-message suggestion.

The new **保留草稿并对照** tab offers the current search page as a record picker and reads the selected record into a read-only title/body view beside the unchanged editor. The user can confirm **保留草稿，修订这条记忆**, then explicitly Save to update that ID and revision. Adopting performs only a fresh read. A changed record is shown for another review; archived and missing records cannot become new revision targets. A cancelled confirmation leaves the draft new. Unknown write outcomes still prohibit adoption/replay. Review text is cleared on window close.

One focused STA workflow passed, 1/1: exact title/type/body preservation, cancelled adoption, change during review, same-ID save, archived/missing targets and close clearing. Build: zero warnings/errors. Evidence: `.verification/native-memory-review-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-review.trx}`. The reused Session 1 heavy wrapper held PID 21356 through build/test completion, releasing at 07:55:51 UTC. No actual HTTP service, database, Profile, browser, model or product EXE was started.

At module 1 this was a source/incremental build change, not yet a new delivery bundle or full EXE acceptance. The prior `eeeaeb4` development ZIP is untouched. Historical actual EXE save/reopen evidence remains at `cff8015`; normal WebView2 pages, default no-argument entry, maintenance/migration and formal release remain unaccepted. Database reopen hashes changed previously; no whole-database zero-write claim is made.

## 2. Resolve an editing conflict while keeping the draft

Module 1 is committed as `febc43e`. For an already loaded record, **对照最新** now reads its current title/body into the same comparison tab. After a revision conflict, Save stays blocked while the user reviews and manually merges useful text into the unchanged draft. **保留草稿，以此版本继续编辑** requires confirmation and a fresh matching read before adopting that revision; the subsequent explicit Save still uses server-side optimistic concurrency. A different record cannot replace the loaded target. Unknown writes remain blocked even when the currently stored content has been previewed.

The existing conflict check now exercises the full compare/merge/save workflow, including declined confirmation, wrong target and a second concurrent edit after adoption. The existing unknown-outcome check covers both new and updated records against this route. These two affected checks passed, 2/2; build zero warnings/errors. Evidence: `.verification/native-memory-conflict-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-conflict.trx}`. The reused Session 1 wrapper released PID 25436 after synchronous build/test completion. No persistence loop or browser was run. Scope and delivery limits above are unchanged.

## 3. Check a draft for exact duplicates and review the result

Module 2 is committed as `f02390b`. **草稿查重** searches the first 160 Unicode code points of the current body in ACTIVE and ARCHIVED records, reads at most 20 full records per status, and compares the complete body exactly. It excludes the currently edited ID. The screen states how many search results were checked and warns when additional records remain. It does not imply semantic matching or a transactionally consistent snapshot across both status reads. A failed read removes results and reports that the check could not be completed; editing the body invalidates old results.

The user can select a result and keep the draft while reviewing the current stored text. Active results can follow the explicit same-ID revision workflow; archived records can be read but are not adopted as new update targets. Two bounded reads perform the check, without a per-record detail loop, model or mutation. The existing server search and storage contracts are unchanged.

One focused workflow passed, 1/1: Unicode query bound, exact versus similar bodies, both statuses, read-only lookup, explicit same-ID save, current-ID exclusion, incomplete results, failed reads, body-edit invalidation and close during a pending check. Layout render inspection then found that long result titles did not wrap and the minimum-size review body was squeezed by the picker. Titles now wrap, the review title scrolls, and the picker collapses after reading. The same affected check was rerun after each of those layout changes, passing; earlier logs/TRX and the squeezed-layout image remain preserved. Final build has zero warnings/errors; final wrapper PID 11236 released at 08:04:13 UTC.

Evidence: `.verification/native-memory-duplicates-20261110/`, including final `run.log`, `build.log`, `test.log`, `test-results/native-memory-duplicates.trx`, and `layout-{review,duplicates}-{804,1024}.png`. The images render WPF content at normal/minimum sizes; they are not product EXE screenshots or physical interaction acceptance. The temporary capture snippet is saved in evidence and removed from the committed test; the workflow assertions remain. No actual service, database, browser, Profile or model was used.

## One delivery for the completed workflow batch

Module 3 is committed as `10a9c8c030293c971b04df59d15624b546399d75`. All three modules are complete. Their single combined [development ZIP](../../artifacts/PersonalAiWorkspace-native-memory-edit-flows-20261110.zip) and [unpacked EXE](../../artifacts/PersonalAiWorkspace-native-memory-edit-flows-20261110/PersonalAiWorkspace.Desktop.exe) include the earlier close/receipt/lifecycle protections. This packaging closure is not an additional feature module. All older bundles remain preserved.

| Evidence | Result |
| --- | --- |
| Product source | `10a9c8c`, tracked source clean throughout packaging |
| ZIP | 384,681,832 bytes; SHA-256 `ED5580341DD772A2570DFE41D147E53ECD1C2B4522801A9588FF5AE8582EC0AA` |
| Desktop DLL | SHA-256 `BDEC8071235023C3CF2859DE3EDED0D29CDCC6B402554D7536183C947A7A0102` |
| Payload and actual assembly pins | 933 files verified after actual ZIP extraction/relocation; `DEVELOPMENT_PACKAGE_SOURCES_PASS` |
| Runtime and frontend | Unchanged source and reused bytes, compared with accepted earlier package; Runtime JAR `BC8F0CC35D2F9321AF848F7FFDFE271DB07B770CF1878454F07A8FD0C0D12625` |
| Build | Self-contained Desktop publish using the existing approved pins/toolchain; no warnings/errors in log |

Evidence: `.verification/native-memory-edit-flows-delivery-20261110/{Build.ps1,README.txt,run.log,publish.log,embedded-sources.log,result.json,lock.json,final-state.json}`. The reused synchronous package lifecycle held the Session 1 heavy mutex through completion, PID 11352, released at 08:07:16 UTC. It started no product EXE, browser, Profile, Runtime service, database or model. Port 18765 was not listening after completion. Main remains `f5266bf4383f52a2293b0fe4bfe1964af958e64a`; all six user documents match the session baseline. No push, other repository/port action, system security change, new denial, pending approval or uncertain operation occurred.

## Handoff after three real editing operations

Stop this conversation here; the parent can continue in a fresh conversation in this same project. Development-only / unsigned remains accurate. The historical actual EXE -> natural WebView2 failure -> native Save -> tray exit -> independent reopen acceptance is for `cff8015`. This new package has focused synthetic UI checks, rendered layout inspection and packaging verification, **not** a new real persistence acceptance. Normal WebView2/default entry, maintenance/migration, PostgreSQL desktop, cross-machine and formal release are still open. Do not rerun the accepted save/reopen loop or the same timed-out native-controller experiment, change security settings, or revisit refused Crashpad/process-metadata reads.

The completed native flow is now: optional bounded duplicate check -> read-only comparison -> explicitly choose the same record -> manually merge if needed -> explicit Save. A concrete remaining daily-use gap to assess next is preserving a draft when its loaded record has been deleted: `missing` blocks Save and New currently clears the draft. An explicit “keep draft as new” operation could recover that work after a confirmed missing record; it must not turn an uncertain write into an automatic retry. This is a static next-operation finding, not an already reproduced or accepted new fix. Prefer a verified user workflow over more generic response validation or another packaging-only round. No immediate browser experiment is proposed.
