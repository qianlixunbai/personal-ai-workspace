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
