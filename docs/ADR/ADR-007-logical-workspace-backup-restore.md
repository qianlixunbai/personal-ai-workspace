# ADR-007 — Versioned Logical Workspace Backup / Restore

Status: Accepted

Date: 2026-10-04 (Asia/Shanghai)

## Decision and relationship to ADR-006

Workspace portable source data comprises Memory and durable Conversation history. Add an independent
`personal-ai-workspace.workspace-backup` format v1. ADR-006 remains Accepted and unchanged in meaning:
its Memory-only format1/schema1 export and fresh Memory DBv1 restore continue to work independently.
This ADR does not supersede ADR-006. Workspace DB internal schema remains v3; logical document/section
versions are separate axes. There is no schema migration in M4C.

Only source fields are portable. Credentials, Browser pairing/registry, Tasks, provider/model/settings,
logs, verification evidence, build output and derived SQLite/FTS/index/trigger storage are excluded.
No Finance/Knowledge/RAG/Agent/automatic Memory/cloud/sync/scheduler/main UI is introduced.

## Strict UTF-8 JSON contract

Exactly six top-level fields: `format,formatVersion,createdAt,contentDigest,memory,conversations`.

| Section | Required fields |
| --- | --- |
| memory | `schemaVersion=1,itemCount,items` |
| conversations | `schemaVersion=1,conversationCount,turnCount,messageCount,items,turns` |
| memory.items[] | ADR-006's exact `id,type,title,content,status,revision,source,createdAt,updatedAt` |
| conversations.items[] | `id,title,status,createdAt,updatedAt` |
| conversations.turns[] | `id,conversationId,sequence,status,failureCode,createdAt,updatedAt,messages,memories` |
| turn.messages[] | `id,role,content,createdAt` |
| turn.memories[] | `position,memoryId,revision` |

Counts must equal actual row counts and remain within existing domain limits. IDs are non-nil canonical
lowercase UUIDs; source IDs are unique within their entity tables. Turn sequences are contiguous 1..N
within each Conversation, at most1000. Selection positions are contiguous0..N-1, at most4, with unique
memoryId per Turn and positive signed64 revisions. Historical selections have no Memory FK and need
not point to an existing/current revision; no historical Memory text is reconstructed to satisfy them.

Conversation lifecycle is ACTIVE/ARCHIVED; original title, text, IDs, timestamps and terminal truth are
preserved. Timestamps use exact canonical Java Instant UTC/millisecond strings, round-trip through signed
SQLite epoch milliseconds, and updatedAt>=createdAt. Title follows existing trimmed/nonempty/160-code-point
contract; messages preserve original content and existing8192 UTF-16/8192 UTF-8 byte limits.

Portable Turn statuses are SUCCEEDED, FAILED, CANCELLED, TIMED_OUT. SUCCEEDED has exactly one USER and
one ASSISTANT; every other outcome has exactly one USER and no ASSISTANT. `failureCode` is required,
nullable, and non-null only for FAILED; the existing seven controlled enum values are allowed.
Legacy FAILED/null histories remain valid. SYSTEM/TOOL/unknown roles and unknown codes are rejected.
`taskId` is absent; restored task_id is NULL and no Task is reconstructed. Export rejects any PENDING in
its snapshot with409 WORKSPACE_BACKUP_CONFLICT, before writing response bytes. No cancel/wait/reconcile
is performed by export. Restore/validate reject PENDING before publication.

Missing/unknown fields, duplicate keys/IDs/sequence/positions, invalid types/enums/UUIDs/times/revisions,
malformed UTF-8/unpaired UTF-16/NUL, oversized fields/counts/document, truncation/trailing garbage, invalid
relationships and digest mismatch reject the whole document. Arrays may be reordered without changing
logical meaning; scalar property order/whitespace/escape choices have no semantic effect.

## Canonical SHA-256

Every canonical value is a UTF-8 string preceded by its unsigned four-byte big-endian byte length.
Integers are base10 ASCII without leading zeros; timestamps are canonical UTC Instant strings. Nullable
failureCode is the empty string (no enum has that value). The digest field itself is excluded.

1. `format,formatVersion,createdAt,memory.schemaVersion,memory.itemCount,conversations.schemaVersion,
   conversations.conversationCount,conversations.turnCount,conversations.messageCount`.
2. Memory records sorted by lowercase UUID ordinal: ADR-006's nine source fields in the order above.
3. Conversation items sorted by lowercase UUID ordinal: `id,title,status,createdAt,updatedAt`.
4. Turns sorted by conversationId ordinal, then numeric sequence. For each Turn:
   `id,conversationId,sequence,status,failureCode,createdAt,updatedAt`, then decimal message count and
   selection count; messages USER then ASSISTANT using `id,role,content,createdAt`; selections sorted
   numerically by position using `position,memoryId,revision`.

Hash the concatenation with SHA-256 and encode lowercase64 hexadecimal characters. This detects accidental
damage/edits; it is not encryption, authentication or a signature. Anyone able to modify the file can
recompute it. UI states plaintext/no encryption/password and recommends protecting the file as personal data.

## Consistent snapshot and bounded transport

An independent read-only SQLite connection begins a transaction; its first SELECT checks PENDING and pins
one snapshot. Counts, canonical digest and streamed JSON all read that transaction. New writes after its
snapshot do not enter the backup. No application-wide Runtime freeze/lock is added. Existing DELETE journal
locking can temporarily delay writers during a long export; an export is an explicit maintenance operation.

Existing capacity:1000 Memory;1000 Conversations x1000 Turns x2 messages. Worst-case escaped message text
alone:98,304,000,000 bytes. Complete conservative document/file/body budget:

`14,948,096 + 4096 + 1000 * (160*12 + 1024 + 1000 * (2*8192*6 + 4*256 + 2048))`

= **101,393,896,192 bytes**. This is a derived safety ceiling including overhead/whitespace; domain capacity
is preserved. Per-field/count bounds also apply. The full theoretical101GB dataset was not materialized
in acceptance; maximum single-Conversation worst-escaping export/validate (~94MiB) and20MiB Desktop
chunk transport were tested. Disk/time/available storage remain operational limits; there is no truncation.

Runtime streams arrays record by record. Parsing never builds an aggregate JSON tree: small row trees are
bounded to12 fields/4 nested-array elements/depth4, with global parser string/name/number/depth constraints.
Memory rows are capped1000. Conversation rows go directly into a private staging SQLite transaction;
PK/UNIQUE/FK constraints enforce identities, and read-back canonical ordering avoids an unbounded ID set
or in-memory sort. Duplicate keys and strict total byte limits apply also to chunked requests.

Desktop uses64KiB streaming buffers and a read-only file handle across preview/restore; no DB access or
aggregate backup buffering. Full source/digest validation is requested from Runtime before export file
publication or displaying a restore preview. This deliberately reuses the authoritative validator instead
of duplicating a second large sorting/storage implementation in Desktop. Restore validates again.
Backup requests have a bounded two-hour client deadline including body transfer; existing ordinary/error
requests retain8-second deadlines/1MiB responses and ordinary mutation bodies retain32KiB.

## Native API and ownership

| Endpoint | Contract |
| --- | --- |
| GET `/api/v1/workspace/backup` | streamed JSON, no export destination path |
| POST `/api/v1/workspace/backup/validate` | raw UTF-8 backup body; complete private disposable reconstruction, metadata only |
| POST `/api/v1/workspace/backup/restore` | raw UTF-8 backup body plus `X-Workspace-Restore-Target` header |

Target header is unpadded base64url of strict UTF-8 absolute directory text, at most8192 UTF-16 units.
Header budget64KiB supports this field. Target is outside the URL, avoiding request-URL path diagnostics.
Body limits are the same as the file budget; there is no second JSON envelope/copy. Metadata has exactly
`formatVersion,memorySchemaVersion,conversationSchemaVersion,createdAt,memoryCount,conversationCount,
turnCount,messageCount,contentDigest` and is strictly checked by Desktop.

Existing authentication/Origin/Browser route denial occurs before large body reads/filesystem work.
Browser credentials with/without Origin, preflight, webpages and missing authentication cannot use these
APIs. Browser remains Translate-only; no Browser repository change.

Desktop owns native Save/Open/Folder dialogs and user export file IO. Export downloads to an unpredictable
sibling file, flushes it, fully validates, then renames with no replacement unless SaveFileDialog explicitly
approved overwrite. Close/cancel suppresses late UI results and clears file/metadata references. Selected
backup preview contains only versions/time/counts, and Restore requires a separate explicit click.

## Transactional reconstruction and safe publication

Reuse ADR-006's location validation/publication boundary. Reject active data and its ancestors/descendants,
nonempty/file targets, project/build/target/logs/.runtime/.git/auth locations, links and reparse points.
Require an existing parent. Build an unpredictable task-owned sibling staging directory, harden account
permissions before DB contents, initialize supported Workspacev3, and stream all rows into one transaction.
Deferred parent FKs permit section/array order changes; foreign_key_check validates relationships before
commit. Original source fields are inserted, task_id staysNULL. Read-back canonical digest/counts verifies
all logical rows; reuse Memory reconstruction verification to rebuild FTS, compare every Memory field,
source/FTS counts/linkage/text, search every source title, FTS integrity and quick_check before commit.
Startup preserves terminal histories with zero replay and normal explicit new Send can continue them.

After commit and connection close, recheck target emptiness/identity/creation time, component links and
same FileStore. New target renames the complete staging directory without replacement; existing empty
target retains its identity, hardens ACL and publishes only the complete closed DB without replacement.
No merge, overwrite, cross-volume copy, active hot swap, auto-switch or restart is implemented.

Failures rollback and never modify the active DB; cleanup only touches the task-owned staging directory
and known SQLite files. Workspace cleanup failure reports controlled failure, even if a complete DB was
already published in the existing-empty case; users must inspect explicitly rather than assume success.
Validation-only uses a private temporary DB and removes it without publishing. Logs/errors contain no
content/title/path/SQL/raw causes/credentials. Evidence is synthetic-only; backups/DB/build/logs are ignored.

## Limits and consequences

Local plaintext/account/admin trust boundaries and non-forensic deletion remain. Rechecks do not isolate
hostile same-account/admin filesystem races; guarantees concern the verified Windows local filesystem,
not network filesystems or universal power-loss durability. Validating a large file needs staging disk and
extra transfers; a long read snapshot can delay SQLite writers. A client close cannot recall an already
admitted server restore. No cloud/sync/scheduler/encryption/password/incremental/merge/history/Finance
backup is included. Main Workspace UI and M4 closure remain a separate Architecture/Closing Review decision.
