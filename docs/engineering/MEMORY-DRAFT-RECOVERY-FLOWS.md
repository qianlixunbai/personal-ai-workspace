# Memory draft recovery after a missing record

2026-11-10, starting at `aae27759c120da6439b6d5c8b5587afb048f95f2` on `codex/knowledge-version-compare`. No repository/ancestor AGENTS.md or repository .agents skills were present. Main and six untracked user documents are preserved; their baseline hashes are in `.verification/native-memory-recover-draft-20261110/baseline.json`.

## Native editor: explicitly retain a missing record's draft as new

Inspection confirmed the handoff's real workflow gap: `MemoryNotFound` retained the editor text but blocked Save, while New discarded it. The existing synthetic missing-record check demonstrated that old New behavior. The editor now explains that the record is currently absent and exposes **保留草稿，另存为新记忆**. Confirmation detaches only the missing ID/revision, retaining exact type/title/body. It performs no write. The subsequent explicit Save creates a new ACTIVE record with a new ID and revision 1; it neither revives nor overwrites the original record. Invalid draft text remains editable under existing save validation.

The recovery control is unavailable during work, without a loaded missing target, or with an unknown write outcome. A read reporting absence after a lost write response does not acknowledge or retry that write. Comparison and duplicate results are cleared when converting the draft; normal discard/close protection remains.

The two affected existing STA workflows passed, 2/2, with no new test class or full-suite run: missing record -> declined recovery -> confirmed recovery -> explicit new save; and unknown create/update/receipt handling including a later missing read. The synthetic HTTP handler now returns the actual not-found contract for absent GETs. Exact Unicode/whitespace/type, new identity, original absence and write counts were checked. Build had zero warnings/errors. Evidence: `.verification/native-memory-recover-draft-20261110/{Check.ps1,run.log,build.log,test.log,test-results/native-memory-recover-draft.trx}`. Session 1 shared heavy wrapper PID 17068 released at 08:18:22 UTC.

No real database, Profile, Runtime service, model, browser or product EXE was used. This is focused synthetic UI evidence, not fresh persistence or WebView2 acceptance. The previous development ZIP remains unchanged pending one combined delivery. Historical real EXE save/exit/independent reopen belongs to `cff8015`; normal WebView2/default entry, maintenance/migration and formal release remain open. No whole-database zero-write claim is made.

## Follow-up selected against the actual memory goal

The completed [explicit memory system](MEMORY-SYSTEM-COMPLETION-REPORT.md) requires user review and explicit saves. Static inspection of `MemoryPage.tsx` found the same missing-record dead end in the normal web editor, whose hint recommended New even though it discarded the draft. Complete that same user operation there next; do not broaden response-validation experiments or repeat failed browser launches. Normal web behavior must still be labelled unaccepted on this machine until WebView2 works.

## Web editor: complete the same recoverable user operation

Native recovery is committed as `1f9a62b`. The web editor now has the same explicit two-step recovery: confirm **保留为新草稿**, retaining type/title/body, then separately Save to create a new ID. The missing-record hint describes this operation and does not recommend the destructive New route. Unknown writes continue to block this action, including when a later GET reports absence. A session reset clears the pending confirmation and draft as before.

Two focused browser-component checks passed, 2/2 (24 unrelated cases intentionally filtered out), plus TypeScript: cancellation, exact draft preservation, zero write during conversion, explicit create/new ID, unknown update followed by absent read, and session reset during confirmation. Evidence: `.verification/web-memory-recover-draft-20261110/{Check.ps1,run.log,typecheck.log,test.log}`. Shared wrapper PID 34072 released at 08:20:32 UTC. These are simulated Bridge/component checks, not an actual WebView2 run. No store/schema/Native contract was changed; the single combined package remains pending.

A second concrete editor gap remains: on version conflict, the normal web editor only offers destructive Reload. The native editor already supports compare/merge while retaining the draft. Bring that complete recovery operation to the normal web page next, retaining exact revision checks and explicit Save, rather than adding generic response checks or another startup experiment.

## Web editor: compare and merge a conflicted draft

Web missing-record recovery is committed as `af1d295`. The loaded editor now offers **对照最新**, showing the current title/type/status/revision/body without replacing the editable draft. After manual merge, **保留草稿，以此版本继续编辑** requires confirmation and another read. If the reviewed record changed, the fresh contents are shown for renewed review; no version is adopted. A matching ACTIVE record becomes the editing baseline only. A subsequent explicit Save still sends the same ID and exact expected revision. Another concurrent update remains a conflict.

Unknown writes are never acknowledged by comparison, and archived records remain read-only in this review route. Missing reads lead to the explicit recovery from module 2. Navigation/session/editor changes clear comparisons and suppress late reads; no persistent copy, schema, model request or auto-merge was introduced. Existing reload/discard remains available.

Three affected component checks passed, 3/3 (24 unrelated cases filtered out), plus TypeScript: conflict/declined confirmation/change during review/manual merge/second conflict/same-ID Save; archived and missing targets plus late hidden reads; and ambiguous create/update with read-only comparison. The existing synthetic MemoryService now enforces expectedRevision so concurrent updates exercise actual conflict semantics. Evidence: `.verification/web-memory-conflict-20261110/{Check.ps1,run.log,typecheck.log,test.log}`. Wrapper PID 24484 released at 08:23:24 UTC. No actual browser, model, database or persistence retest was started. This completes three user workflows; package them together and write the next handoff before changing conversations.
