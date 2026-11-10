# Native Memory daily editing flows

2026-11-10, starting at `a6b14f9` on `codex/knowledge-version-compare`. Repository and parent directories contain no AGENTS.md or .agents skills. The six untracked user documents and main are preserved.

## 1. Compare a saved record without losing a new draft

The native editor already had create/update, search, paging, archive/restore/delete and backup entry points. Unlike the web editor, it could not use a new draft to revise an existing record: selecting a list item discarded that draft after confirmation. The next priority is this actual missing editing operation, rather than the previous handoff's separate Assistant failure-message suggestion.

The new **保留草稿并对照** tab offers the current search page as a record picker and reads the selected record into a read-only title/body view beside the unchanged editor. The user can confirm **保留草稿，修订这条记忆**, then explicitly Save to update that ID and revision. Adopting performs only a fresh read. A changed record is shown for another review; archived and missing records cannot become new revision targets. A cancelled confirmation leaves the draft new. Unknown write outcomes still prohibit adoption/replay. Review text is cleared on window close.

One focused STA workflow passed, 1/1: exact title/type/body preservation, cancelled adoption, change during review, same-ID save, archived/missing targets and close clearing. Build: zero warnings/errors. Evidence: `.verification/native-memory-review-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-review.trx}`. The reused Session 1 heavy wrapper held PID 21356 through build/test completion, releasing at 07:55:51 UTC. No actual HTTP service, database, Profile, browser, model or product EXE was started.

This is a source/incremental build change, not a new delivery bundle or full EXE acceptance. The prior `eeeaeb4` development ZIP is untouched. Historical actual EXE save/reopen evidence remains at `cff8015`; normal WebView2 pages, default no-argument entry, maintenance/migration and formal release remain unaccepted. Database reopen hashes changed previously; no whole-database zero-write claim is made.
