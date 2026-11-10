# Native Memory daily editing flows

2026-11-10, starting at `a6b14f9` on `codex/knowledge-version-compare`. Repository and parent directories contain no AGENTS.md or .agents skills. The six untracked user documents and main are preserved.

## 1. Compare a saved record without losing a new draft

The native editor already had create/update, search, paging, archive/restore/delete and backup entry points. Unlike the web editor, it could not use a new draft to revise an existing record: selecting a list item discarded that draft after confirmation. The next priority is this actual missing editing operation, rather than the previous handoff's separate Assistant failure-message suggestion.

The new **保留草稿并对照** tab offers the current search page as a record picker and reads the selected record into a read-only title/body view beside the unchanged editor. The user can confirm **保留草稿，修订这条记忆**, then explicitly Save to update that ID and revision. Adopting performs only a fresh read. A changed record is shown for another review; archived and missing records cannot become new revision targets. A cancelled confirmation leaves the draft new. Unknown write outcomes still prohibit adoption/replay. Review text is cleared on window close.

One focused STA workflow passed, 1/1: exact title/type/body preservation, cancelled adoption, change during review, same-ID save, archived/missing targets and close clearing. Build: zero warnings/errors. Evidence: `.verification/native-memory-review-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-review.trx}`. The reused Session 1 heavy wrapper held PID 21356 through build/test completion, releasing at 07:55:51 UTC. No actual HTTP service, database, Profile, browser, model or product EXE was started.

This is a source/incremental build change, not a new delivery bundle or full EXE acceptance. The prior `eeeaeb4` development ZIP is untouched. Historical actual EXE save/reopen evidence remains at `cff8015`; normal WebView2 pages, default no-argument entry, maintenance/migration and formal release remain unaccepted. Database reopen hashes changed previously; no whole-database zero-write claim is made.

## 2. Resolve an editing conflict while keeping the draft

Module 1 is committed as `febc43e`. For an already loaded record, **对照最新** now reads its current title/body into the same comparison tab. After a revision conflict, Save stays blocked while the user reviews and manually merges useful text into the unchanged draft. **保留草稿，以此版本继续编辑** requires confirmation and a fresh matching read before adopting that revision; the subsequent explicit Save still uses server-side optimistic concurrency. A different record cannot replace the loaded target. Unknown writes remain blocked even when the currently stored content has been previewed.

The existing conflict check now exercises the full compare/merge/save workflow, including declined confirmation, wrong target and a second concurrent edit after adoption. The existing unknown-outcome check covers both new and updated records against this route. These two affected checks passed, 2/2; build zero warnings/errors. Evidence: `.verification/native-memory-conflict-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-conflict.trx}`. The reused Session 1 wrapper released PID 25436 after synchronous build/test completion. No persistence loop or browser was run. Scope and delivery limits above are unchanged.

## 3. Check a draft for exact duplicates and review the result

Module 2 is committed as `f02390b`. **草稿查重** searches the first 160 Unicode code points of the current body in ACTIVE and ARCHIVED records, reads at most 20 full records per status, and compares the complete body exactly. It excludes the currently edited ID. The screen states how many search results were checked and warns when additional records remain. It does not imply semantic matching or a transactionally consistent snapshot across both status reads. A failed read removes results and reports that the check could not be completed; editing the body invalidates old results.

The user can select a result and keep the draft while reviewing the current stored text. Active results can follow the explicit same-ID revision workflow; archived records can be read but are not adopted as new update targets. Two bounded reads perform the check, without a per-record detail loop, model or mutation. The existing server search and storage contracts are unchanged.

One focused workflow passed, 1/1: Unicode query bound, exact versus similar bodies, both statuses, read-only lookup, explicit same-ID save, current-ID exclusion, incomplete results, failed reads, body-edit invalidation and close during a pending check. Layout render inspection then found that long result titles did not wrap and the minimum-size review body was squeezed by the picker. Titles now wrap, the review title scrolls, and the picker collapses after reading. The same affected check was rerun after each of those layout changes, passing; earlier logs/TRX and the squeezed-layout image remain preserved. Final build has zero warnings/errors; final wrapper PID 11236 released at 08:04:13 UTC.

Evidence: `.verification/native-memory-duplicates-20261110/`, including final `run.log`, `build.log`, `test.log`, `test-results/native-memory-duplicates.trx`, and `layout-{review,duplicates}-{804,1024}.png`. The images render WPF content at normal/minimum sizes; they are not product EXE screenshots or physical interaction acceptance. The temporary capture snippet is saved in evidence and removed from the committed test; the workflow assertions remain. No actual service, database, browser, Profile or model was used.
