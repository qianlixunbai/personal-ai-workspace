# Memory draft recovery after a missing record

2026-11-10, starting at `aae27759c120da6439b6d5c8b5587afb048f95f2` on `codex/knowledge-version-compare`. No repository/ancestor AGENTS.md or repository .agents skills were present. Main and six untracked user documents are preserved; their baseline hashes are in `.verification/native-memory-recover-draft-20261110/baseline.json`.

## Native editor: explicitly retain a missing record's draft as new

Inspection confirmed the handoff's real workflow gap: `MemoryNotFound` retained the editor text but blocked Save, while New discarded it. The existing synthetic missing-record check demonstrated that old New behavior. The editor now explains that the record is currently absent and exposes **保留草稿，另存为新记忆**. Confirmation detaches only the missing ID/revision, retaining exact type/title/body. It performs no write. The subsequent explicit Save creates a new ACTIVE record with a new ID and revision 1; it neither revives nor overwrites the original record. Invalid draft text remains editable under existing save validation.

The recovery control is unavailable during work, without a loaded missing target, or with an unknown write outcome. A read reporting absence after a lost write response does not acknowledge or retry that write. Comparison and duplicate results are cleared when converting the draft; normal discard/close protection remains.

The two affected existing STA workflows passed, 2/2, with no new test class or full-suite run: missing record -> declined recovery -> confirmed recovery -> explicit new save; and unknown create/update/receipt handling including a later missing read. The synthetic HTTP handler now returns the actual not-found contract for absent GETs. Exact Unicode/whitespace/type, new identity, original absence and write counts were checked. Build had zero warnings/errors. Evidence: `.verification/native-memory-recover-draft-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-recover-draft.trx}`. Session 1 shared heavy wrapper PID 17068 released at 08:18:22 UTC.

No real database, Profile, Runtime service, model, browser or product EXE was used. This is focused synthetic UI evidence, not fresh persistence or WebView2 acceptance. The previous development ZIP remains unchanged pending one combined delivery. Historical real EXE save/exit/independent reopen belongs to `cff8015`; normal WebView2/default entry, maintenance/migration and formal release remain open. No whole-database zero-write claim is made.

## Follow-up selected against the actual memory goal

The completed [explicit memory system](MEMORY-SYSTEM-COMPLETION-REPORT.md) requires user review and explicit saves. Static inspection of `MemoryPage.tsx` found the same missing-record dead end in the normal web editor, whose hint recommended New even though it discarded the draft. Complete that same user operation there next; do not broaden response-validation experiments or repeat failed browser launches. Normal web behavior must still be labelled unaccepted on this machine until WebView2 works.
