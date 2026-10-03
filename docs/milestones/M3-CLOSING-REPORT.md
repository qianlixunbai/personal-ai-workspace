# M3 — User-Controlled Memory Foundation — Final Closing

Date: 2026-10-03 (Asia/Shanghai)

**M3 CLOSED — GO.** The final implementation gate M3C-2 passes versioned logical recovery,
real restored management/Memory Ask, security/privacy and existing regression. Local branch
`m3c2-memory-export-restore` awaits Closing Review; no merge, push, tag or release.
Implementation: `9d04b4a0c139ff2ccb9126f89ff8e96061eb46ad` (`feat: add memory export and restore`).
Closing documentation is a separate local commit.

## Delivered foundation

| Gate | Result and lasting behavior |
| --- | --- |
| M3A | CLOSED — GO: explicit MANUAL save, private Runtime-owned SQLite schema v1, source/FTS separation, lifecycle/search, exact revision concurrency |
| M3B | CLOSED — GO: native explicit management, no autosave, dirty/stale protections, cancellation/late-result isolation |
| M3C-1 | CLOSED — GO: explicit 1–4 ACTIVE per-turn selections, exact-revision immutable admission snapshot, ordinary Ask isolation, no history |
| M3C-2 | GO: source-only format v1/schema v1 export, strict digest/format validation, exact reconstruction in new/empty data directory, rebuilt FTS |

Memory is portable user-owned source data. IDs, type, original title/content, lifecycle status,
revision, MANUAL source and both timestamps survive recovery exactly. Backup includes neither
derived indexes nor auth/task/AI/Ask data. Files are plaintext and the UI explicitly tells users
to protect them. Restore never changes the active database, merges existing data or switches Runtime.

## Final evidence

Clean official starting baseline `main == origin/main == HEAD == ef4d6e8e17d045883a4be58d45dc2e963749042f`
was confirmed after a successful fetch. Initial Java54/Desktop94 passed before branching.
Final regression: **Java63 PASS / Desktop106 PASS**, no failures/skips, all 54/94 existing cases retained.

The complete synthetic Windows chain passed:

```text
WPF explicit Save
  → restart source Runtime / SQLite persistence
  → WPF Manage, lifecycle/filter/search
  → explicit preview/select / real Ollama Memory Ask
  → WPF logical Export
  → stop source / separate maintenance Runtime
  → restore to new directory
  → brand-new Runtime on restored directory
  → exact source fields / rebuilt FTS / quick_check
  → WPF Manage / explicit real Memory Ask
  → delete synthetic records / privacy audit / owned-process and temporary-data cleanup
```

One ACTIVE PREFERENCE, one ACTIVE PROJECT_NOTE and one ARCHIVED PROJECT_NOTE were restored,
with nontrivial revisions and timestamp differences. An existing empty target also restored exactly.
Archived Memory stays archived, absent from ACTIVE selection and rejected by explicit Memory Ask.
Both English and Chinese search pass. Current maintenance and original source DB bytes do not change
because of restore. Changed content/digest mismatch, unsupported format version and nonempty
target fail closed. Paired/originless Browser, web Origin, missing auth and preflight are denied.
No user Memory or Windows Credential Manager is accessed, and no user's Runtime is stopped.

The automated harness injects synthetic native picker choices while driving production WPF
buttons, native file IO, HTTP/SQLite and actual local inference. It does not claim manual OS-dialog
interaction, cross-platform/network filesystem guarantees or a second physical-machine test.
Evidence retains metadata/check names only at ignored `.verification/m3c2-memory-backup-evidence.json`.
Full results and rerun commands: [M3C-2 Report](M3C-2-MEMORY-EXPORT-RESTORE-REPORT.md).

## Boundary decisions and remaining exclusions

[ADR-004](../ADR/ADR-004-user-controlled-memory-storage.md),
[ADR-005](../ADR/ADR-005-explicit-memory-context.md) and
[ADR-006](../ADR/ADR-006-logical-memory-backup-restore.md) define the adopted foundation.
The checksum is not authentication/encryption. Source/schema versions are distinct; only v1/v1
are supported. Same-volume staging publishes a complete database without replacement, with a
specific empty-directory strategy rather than a universal atomic-replace claim. Local cancellation
does not recall an already-admitted server operation; a locked filesystem can prevent staging cleanup.

M3 does not include Conversation, RAG, embeddings, vector DB, Agent/tools, automatic extraction or
retrieval, Browser Memory, Finance integration, sync/cloud, scheduler, encrypted/password backup,
merge/hot replacement, workspace switcher or a schema migration engine.
Browser repository/capability boundaries and existing AI behavior are unchanged. Finance stays
frozen; **Finance Reality Sync remains a separate prerequisite** before any Finance integration.

**Recommendation: GO for Closing Review.** M3 closure denotes accepted foundation scope; it does
not authorize publication or any excluded next milestone.
