# ADR-004 — User-Controlled Memory Storage

Status: Accepted (M3A implementation); 2026-10-03 (Asia/Shanghai)

## Context

M3 — User-Controlled Memory Foundation requires explicit user saves that survive Runtime restart.
M3A establishes independent durable storage; UI, Ask integration and export/restore remain later work.
Memory is user-authored personal text, never Finance structured truth.

## Decision

- Runtime owns Memory. Native clients explicitly create `PREFERENCE` or `PROJECT_NOTE`, source `MANUAL`.
  No automatic extraction, Ask save, background capture, retrieval or prompt injection.
- SQLite `memory_items` is authoritative; `PRAGMA user_version=1` identifies the initial schema.
  Only an empty v0 database can migrate to v1. Initialization/migration runs transactionally.
  Nonempty unversioned, unknown newer and corrupt databases fail closed; never delete/recreate the database.
- Pin Xerial SQLite JDBC 3.53.2.0 without JPA, a connection pool or a migration framework.
  FTS5 with case-sensitive trigram tokenizer is actually created and exercised by startup/tests.
  `memory_fts` is disposable derived data, recreated from source on startup and explicit native rebuild.
  Triggers update the index in the source transaction; no best-effort writes after commit.
- Search is literal, case-sensitive substring matching in title/content, without normalization or raw FTS operators.
  At least three Unicode code points use an escaped FTS phrase; shorter queries use parameterized `instr`.
  Default ACTIVE, optional ARCHIVED and type filters; stable `updated_at DESC, id ASC` ordering;
  zero-based page, default limit 20, max 100, bounded query length 160 code points.
- A store serializes access to its one JDBC connection. `BEGIN IMMEDIATE` acquires the SQLite write lock before
  count/insert and revision compare/update/delete. SQLite also arbitrates other store instances/processes.
  Read count/page uses one snapshot transaction. `busy_timeout=3000`, `foreign_keys=ON`, `journal_mode=DELETE`.
  Busy/I/O/SQL failure maps to a content-free storage error; rollback failure retires the connection.
- Create revision is 1; every successful update/archive/restore advances it, including repeated lifecycle commands.
  Update/archive/restore/delete require `expectedRevision`; stale revisions conflict, missing IDs are not found.
  Delete physically removes source/index entries and retains no revision history.
- Native authentication is mandatory. Existing Browser Translate-only route/method/capability allowlists remain
  unchanged and reject every `/api/v1/memory/**` operation, including preflight and originless Browser access.
  Controller also requires a native identity. POST/PUT/PATCH/DELETE body bytes share the existing 32KiB filter cap.
- `workspace.data-directory` defaults to `${user.home}/.personal-ai-workspace/data`, configurable outside the
  project directory and credential directory (and not the working directory itself). Source text never goes into token or Browser registry files.
  Reject symlink/reparse locations, wrong owners and unavailable private permissions. Restrict/verify POSIX
  directory 0700/file 0600 or Windows current-account-only ACL with directory inheritance.
  Existing DB and WAL/SHM/journal files are checked; any SQLite sidecars are sensitive personal data.
- **SQLite currently stores local plaintext data protected by OS account/filesystem boundary.**
  Owner-only ACL is not database encryption. Same-account processes and administrators are outside this boundary.
- Total ACTIVE + ARCHIVED <=1000; title <=160 code points; content <=2000 UTF-16 units and <=8192 UTF-8 bytes.
  Both lengths are computed on original content. Reject, never truncate or evict. Preserve whitespace/line endings;
  reject blank text, NUL and malformed Unicode. Only parameterized SQL accepts user values.
- No raw titles, contents, bodies, queries, SQL parameters, DB paths/bytes or driver exceptions in logs/errors.
  Diagnostic `toString` for items/save/update is metadata-only. HTTP responses are JSON with `Cache-Control: no-store`.

## Consequences

M3B can add explicit native Memory management UI to this independent contract. Ordinary single-turn Ask,
Translate and Summarize keep their existing prompts/contracts; Memory is not read by their execution pipeline.
M3C can build versioned logical export/restore using authoritative source records and rebuild derived FTS.
No export/restore, merge, sync, conversation, RAG, embeddings, Agent or Finance integration exists in M3A.

Delete guarantees absence from subsequent API reads/search, not forensic erasure of SQLite free pages,
sidecars, backups or OS snapshots. There is no full revision history, stable pagination snapshot across separate
requests or general migration from a preexisting unversioned schema. Windows behavior was tested locally;
the POSIX permission path has not received a real Linux/macOS acceptance run.

## Evidence / references

- [M3A implementation and acceptance report](../milestones/M3A-MEMORY-STORAGE-REPORT.md)
- [Xerial pinned release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.2.0)
- [SQLite FTS5 / trigram contract](https://www.sqlite.org/fts5.html#the_trigram_tokenizer)
