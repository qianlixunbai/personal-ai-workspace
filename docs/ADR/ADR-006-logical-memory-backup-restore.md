# ADR-006 — Versioned logical Memory backup / restore

Status: Accepted

Date: 2026-10-03 (Asia/Shanghai)

## Context and decision

Memory is user-owned portable source data. `memory_items` remains the sole authoritative table;
FTS tables/shadow tables, triggers, indexes and SQLite sidecars are derived/storage details.
An explicit export contains only source records and versioned backup metadata. It contains no
credential, task, Browser state, logs, AI prompt, question, answer, selection or provider state.
Restore reconstructs fresh schema v1 in a new or empty directory. It never opens, replaces,
deletes, merges into or switches the active Runtime's database. Export / Restore are native-only.

## Document contract

The UTF-8 JSON document has exactly seven required fields:

| Field | v1 value |
| --- | --- |
| format | `personal-ai-workspace.memory-backup` |
| formatVersion | Integer `1`, document format |
| schemaVersion | Integer `1`, Memory source schema |
| createdAt | Canonical UTC `Instant.toString()`, exact millisecond precision |
| itemCount | Integer 0–1000, equal to array length |
| contentDigest | Required lowercase 64-character SHA-256 hex |
| items | All ACTIVE and ARCHIVED source records |

Each item has exactly `id,type,title,content,status,revision,source,createdAt,updatedAt`.
IDs use canonical lowercase UUID strings, excluding the nil UUID. Revision is a positive signed
64-bit integer. Source is MANUAL; enums and text limits are the existing Memory contract.
Timestamps must round-trip exactly through SQLite's signed epoch-millisecond representation;
`updatedAt >= createdAt`. Surrounding whitespace, line endings and Unicode are preserved.

Unknown/missing fields, duplicate JSON keys/IDs, wrong types, malformed UTF-8/UTF-16, NUL,
unsupported versions, invalid enums/times/revisions/source, oversized text/count/document,
truncated/corrupt JSON and digest mismatch reject the entire document. No guessing, skip-row
import, coercion, migration framework or partial restore. Format and schema versions are distinct.

## Canonical digest serialization

The digest uses **canonical binary serialization**, independently of JSON property order,
whitespace and escaping. Sort items by their lowercase UUID string using ordinal/ASCII ascending
order; item array order has no semantics.

Every value below is a UTF-8 string, prefixed by its byte length as an unsigned four-byte
big-endian integer. Concatenate the length/value pairs with no separator or BOM:

1. `format`, decimal `formatVersion`, decimal `schemaVersion`, canonical `createdAt`, decimal `itemCount`.
2. For each sorted item: `id`, `type`, original `title`, original `content`, `status`, decimal `revision`,
   `source`, canonical `createdAt`, canonical `updatedAt`.

Integers use base-10 ASCII without leading zeros/sign on positive values. SHA-256 hashes those
bytes; encode the result as lowercase hex. `contentDigest` itself is excluded. The digest detects
accidental corruption or edits without a recomputed digest; it is **not a signature or authenticity
proof**. A party able to edit the plaintext file can recompute it. There is no encryption/password.

## Ownership, transport and size bounds

Desktop owns SaveFileDialog/OpenFileDialog/OpenFolderDialog and export-file IO; it never reads
SQLite. Runtime owns the single SQLite read-snapshot serialization, full validation and fresh
database construction. Desktop validates the export contract/digest before saving, and validates
a selected backup before sending it. Export-file replacement uses OS SaveFileDialog's overwrite
prompt; a racing file is not replaced when overwrite was not approved. Export writes a sibling
temporary file, flushes it, then renames it to the user-selected filename.

| Native endpoint | Bound |
| --- | --- |
| GET `/api/v1/memory/backup` | Success JSON at most 14,948,096 bytes |
| POST `/api/v1/memory/backup/restore` | Strict `{backup,targetDirectory}` JSON, at most 15,013,632 bytes |

Document budget = `1000 * (2000 * 6 + 160 * 12 + 1024) + 4096`: six JSON escaped bytes
per content UTF-16 unit, twelve per possible astral title code point, conservative source-field
overhead, document overhead. Restore reserves a further 64 KiB for the envelope and an absolute
target path of at most 8192 UTF-16 units. Whitespace also counts toward the transport/file budget.
Both server and streamed Desktop reads enforce hard bounds. Ordinary mutation bodies retain
32 KiB and ordinary/error Desktop responses retain 1 MiB; only this exact authenticated native
restore route / successful export response receive their larger budgets.

Target paths come from the explicit native folder choice. Runtime accepts no export path, no
arbitrary filename write or deletion endpoint. Restore requires an existing parent, a new/empty
absolute target, separate from current data (including descendants/ancestors), credentials,
projects, build/target/logs/.runtime/.git locations, and rejects links/reparse points. Native bearer
authenticates the existing same-account Desktop trust domain; it does not cryptographically prove
a human used a picker. Browser credentials/web Origins/missing auth are denied before parsing or
filesystem work; Browser routes/capabilities/CORS remain unchanged.

## Reconstruction and publication

Create an unpredictable task-owned sibling staging directory, apply account-private permissions,
initialize fresh schema v1, and insert original source fields in one SQLite write transaction.
Rebuild FTS from source, compare every stored field against the logical rows, verify source/FTS
count and text/row linkage, run FTS integrity-check, exercise search on every record's title,
verify schema v1 and `quick_check`, then commit and close the SQLite connection.

Recheck target state and links after staging construction. For an existing empty directory,
recheck fileKey where available and creation time; JDK Windows may return no fileKey. Then use
the following safe publication strategy on the same local filesystem:

- New target: rename the complete staging directory to the final name with no replacement.
- Existing empty target: retain the user's directory, verify the same FileStore, apply private
  ACL/permissions, and rename the complete closed `memory.db` into it with no replacement.

No cross-volume copy, replace-existing, hot swap or deletion of a user's directory is used. The
empty-directory case publishes one already-complete database rather than claiming atomic
directory replacement. This is the verified Windows local-filesystem maintenance strategy,
not a portable guarantee for network filesystems or power-loss durability of directory metadata.
The selected empty directory's ACL may be hardened even if a later publication fails. Rechecking
is not an isolation guarantee against hostile same-account processes/admins racing filesystem
operations; the existing OS account trust boundary continues to apply.

Failure leaves the active database unchanged and attempts cleanup only of known SQLite files in
the task-owned staging directory, followed by the staging directory itself. A locked filesystem
can prevent cleanup; such residue is not a finalized target and is never considered success.
Insert failure rolls back all source rows. A target that becomes nonempty is refused without
overwriting it. Errors carry stable codes only, never raw paths, SQL, content or exception causes.

## User-visible semantics and consequences

MemoryWindow opens a small separate MemoryBackupWindow. Both operations are explicit; export
includes saved source records, including archived items. UI states:

> Export contains your Memory text in plaintext.
>
> Protect this file like other personal documents.

> Restore creates a NEW data directory.
>
> It does not merge or overwrite current Memory.

Success shows versions/count only and instructs the user to start Runtime with the restored
directory. Closing cancels local reads/HTTP and ignores late results; it cannot recall a server
restore already admitted. There is no automatic workspace switch, scheduler, sync/cloud backup,
encryption, history, Conversation/RAG/embeddings/vector DB/Agent, automatic extraction or Finance.

Privacy evidence contains only check names, PASS/FAIL, versions, count, digest and file size.
Real acceptance is synthetic-only; no user's Memory or Windows Credential Manager is accessed.
