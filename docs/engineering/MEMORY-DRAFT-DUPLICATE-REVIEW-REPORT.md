# Explicit duplicate review for Memory editor drafts

2026-11-09. Based on `de4e51d2fcc1755a5f59bbba089c8d51f71fedbd`, branch `codex/knowledge-version-compare`. A bounded user-facing addition to manual and Knowledge-answer-to-Memory review; not a desktop acceptance or production-release claim.

## Gap and resulting behavior

Existing duplicate review was reachable only inside a generated model candidate. A manually entered draft or an unmodified Knowledge answer could not use that review without first generating a different candidate. The Memory editor now offers an optional “检查草稿是否重复” action for a valid unsaved body, including while its title is still blank. It requires storage access, not a model call.

The new panel calls the existing checkCandidateDuplicates helper, rather than creating another duplicate algorithm. It searches the literal first 160 Unicode characters across active memories, reads at most the first 20 matching records, verifies their authoritative ID/revision/status and compares the complete exact body. Archived records and semantic similarity are outside this check. Results state checked/total counts and explicitly warn on incomplete reads, changed records or failure. Editing the body, leaving/disablement during a check, session replacement, editor replacement and unmount invalidate old results and stop further reads. The source text is not rewritten or truncated for saving.

Each exact match can be opened through the parent's existing discard-confirmation/get path. Cancelling preserves the current draft; confirming reads the existing authoritative record. No automatic save, merge, update, deletion, model generation or Memory-context selection occurs. Checking is optional and does not impose uniqueness: users may still explicitly save a separate item, and concurrent records can change after the check. Existing Save locks and unknown-outcome protections remain in force.

Knowledge answers are checked as their validated original text, including literal markup. Their assistant/unverified review warning remains, and an explicitly saved record remains MANUAL without invented persistent answer/citation provenance. Existing model-candidate review remains independent because its proposed body may differ from the editor draft.

## Implementation and evidence

- `MemoryDraftDuplicateCheck.tsx`: optional read-only panel, bounded existing helper, stale-result invalidation, and explicit existing-record action.
- `MemoryPage.tsx`: mounts the panel only for a new valid draft outside revision-target review; passes existing requestDiscard/get behavior and availability/uncertain-outcome guards.
- Existing MemoryPage integrated tests add two scenarios covering manual/no-title checking, title-only/archived exclusions, double-click bounds, safe rendered text, cancelled/confirmed navigation, failures, 20-of-21 partial review, and late replies after editing/leaving.
- The existing KnowledgeAnswer-to-real-Memory-editor test now checks exact answer text against an existing record before the same explicit single Save; no model proposal or update is introduced.

First focused run: 33 passed, 2 failed, with React duplicate-key warnings. The new sibling panel initially reused the candidate panel's numeric epoch key, which could retain/duplicate candidate controls. It now uses a distinct draft-duplicates key namespace. The original failed log was retained; the final run passed 35/35 across the two owning files, with the model-candidate flows preserved. TypeScript check also passed.

Commands: `npm --prefix desktop/frontend test -- src/pages/MemoryPage.test.tsx src/components/KnowledgeAnswer.test.tsx`; `npm --prefix desktop/frontend run check`. Evidence: `.verification/memory-draft-duplicate-review-20261109/{focused-first.log,focused-final.log,typescript-final.log}`. No full frontend suite, frontend production build, package update, Java/model or extra desktop test was run for this feature.

Before source edits, `.verification/memory-draft-duplicate-review-20261109/baseline-de4e51d.bundle` was created; git bundle verify returned 0, confirmed exact baseline HEAD and complete history. SHA256: `6b75e5b3df924767e7b2753c4119db52a759bad3f70f724118fb5cbc5e5b3c7d`. The six user-owned untracked documents, main, personal skills and all existing user data remain outside this change; no remote/push/publication or security-setting change was made.

## Separate desktop result and remaining boundary

During a separately coordinated window, exactly one startup observation of the already-built de4e51d diagnostic DLL ran against a different fresh synthetic Profile. It captured real GPU/renderer/browser process exits and still failed to reach READY at the existing timeout. That DLL used the existing production assets; it was not a desktop test of this new draft feature. The exact observed host and browser PIDs exited and port 18765 was closed; the window was released. See MEMORY-PROFILE-CLEAR-ANALYSIS.md for the events and independent limits.

This feature is validated through the owning UI/Bridge tests. Its production frontend bundle was deliberately not rebuilt, so existing ZIPs do not yet include it. A full actual Memory discard/save/reopen flow remains blocked by browser startup stability and has not been declared passed. Do not add automatic Memory or relax clearing/security to promote this result.
