# ADR-012 — Deterministic Lexical Retrieval and Rebuildable Index

Status: Accepted

Date: 2026-10-06 (Asia/Shanghai)

Approval provenance: Architecture Guard's K2 Architecture Review — APPROVED — GO,
supplied with this implementation authorization. The implementation engineer does
not independently approve architecture or close K2; independent closing review is required.

## Truth, storage and scope

`knowledge/knowledge.db` schema v1 remains authoritative and is not migrated.
Knowledge Backup v1 remains unchanged and excludes all index state. The separate
account-private plaintext `knowledge/index/lexical.db` (index schema v1) is
derived, disposable and rebuildable. Its task-owned candidates live in
`knowledge/index/staging/`. Existing Knowledge owner-only ACL/no-links policy
applies before content is written. This is filesystem protection, not encryption.

Only ACTIVE Documents with a current READY revision are searched. Retained old,
failed, cancelled and interrupted revisions do not replace that corpus. Search
reads immutable normalized text and typed K1 locators; it never reads original
external files, changes normalization or becomes truth. Memory, Knowledge and
Finance remain independent. Finance integration deferred pending authoritative
Finance Reality Sync; no Finance dependency is introduced.

## Application-owned semantics

`lexical-chunk-1` preserves structural locator/section boundaries and exact UTF-16
offsets in authoritative normalized text. Large locators split at newline, then
whitespace, then Unicode code-point boundaries. Each chunk has at most 2048 code
points (at most 8192 UTF-8 bytes); the complete index has at most 100000 chunks.
Chunk ordinals start at zero within each document revision. Line numbers are
one-based, inclusive; offsets are zero-based, end-exclusive. No surrogate pair
is split. No page or citation identity is invented.

`lexical-1` uses NFKC and Locale.ROOT lowercase only for derived tokens. General
letter/digit runs produce word tokens; contiguous Han, Hiragana, Katakana and
Hangul produce unigrams and adjacent bigrams. Tokens use type prefixes and
hexadecimal Unicode code points, so FTS sees safe ASCII identifiers. No stemming,
fuzzy matching, transliteration, synonyms, segmentation downloads or AI tokenizer
is used. All unique query tokens must match a single chunk across title/heading/body.

Queries must be nonblank, well-formed Unicode, at most 128 code points and 32
unique analyzed tokens. Invalid/control-only/tokenless input is rejected;
overcomplex input is never truncated. User text is never FTS MATCH syntax:
only application-produced quoted identifiers joined with AND reach MATCH.

SQLite FTS5 owns postings and BM25 only. `lexical-rank-1` fixes title/heading/body
weights at 4.0/2.0/1.0. Ascending BM25, canonical documentId (binary order), numeric
sourceRevision, then chunkOrdinal determines order. The actual bundled engine
must pass create/insert/MATCH/BM25 capability proof; no LIKE or alternative-engine
fallback exists. SQLite runtime version is part of index identity.

## Freshness, executor and publication

A SHA-256 length-framed fingerprint includes index/chunker/analyzer/ranking and
SQLite versions, followed by canonical documentId/currentReadyRevision/
representationDigest tuples in sorted order. Private index metadata records
these identities and document/chunk counts; none reaches JS. Search checks the
authoritative fingerprint before and after querying and discards results on change.
Stale indexes never serve results.

Successful current READY publication, archive, restore and delete invalidate
derived state and schedule full rebuild. Failed ingestion preserving the current
pointer does not invalidate the corpus. Index errors never roll back Knowledge
truth. A separate `knowledge-index` executor has one running build and one
coalesced rerun flag, without an unbounded queue or AI/ingestion worker reuse.

Build a private candidate, create schema, chunk/analyze/insert, check versions,
counts, FTS integrity, quick_check and the 1 GiB hard file/page cap, close it,
verify the current fingerprint and publish with same-volume atomic replacement.
Changes during build prevent READY publication and coalesce another build.
Filesystem publication and Knowledge DB mutation are not a cross-DB transaction.
Freshness gates and rebuild provide correctness.

Missing/corrupt/unsupported/wrong-version/wrong-fingerprint indexes leave Knowledge
startup available and schedule rebuild or report controlled unavailability. Only
provably task-owned index candidates are cleaned; unknown objects are preserved.
Restore first reconstructs unchanged Knowledge Backup v1 truth/sources in an
isolated target; the restarted Runtime rebuilds the absent index.

## API, bridge, UX and privacy

Native-only POST `/api/v1/knowledge/search` accepts query and limit (default/max 10).
GET `/search/status` and typed POST `/search/rebuild` return bounded safe status.
Authentication and Browser denial precede body processing; Browser stays
Translate-only with unchanged origins/CORS/capabilities.

Typed `knowledge.search`, `knowledge.searchStatus`, `knowledge.rebuildSearchIndex`
retain the 32 KiB request/64 KiB ordinary response budgets. Hits expose only
documentId, bounded title, decimal sourceRevision, sourceType, exact offset/line
locator, nullable bounded heading, plain-text snippet (at most 384 UTF-16 units)
and snippet-relative Unicode-safe highlight ranges. Search title is bounded to
160 UTF-16 units, heading to 96 units, and at most 16 ranges per hit;
worst-case JSON escaping stays below 64 KiB. Digests, fingerprints, index tokens,
BM25, rowids, SQL, absolute paths and source/backup bytes are never exposed.
Returned document IDs authorize get/preview in the captured current session;
rotation clears them and late responses cannot authorize a replacement session.

Search stays inside `#/knowledge`; query lives only in React state, never URL,
storage, history, diagnostics, logs or evidence. Snippets use text nodes and mark,
never HTML execution. Explicit result preview uses the exact revision/offset.
Status, empty/unavailable/building state, keyboard submit/focus and 125% layout
must be verified; one genuine Windows Pinyin search flow is mandatory.

Knowledge Search is explicit deterministic lexical retrieval only. It does not
call Ollama/providers/TaskManager or feed Ask, Conversation, Memory, Translate or
Summarize. Embeddings, vectors, semantic/hybrid retrieval, RAG, prompt assembly,
model citations, PDF/DOCX/OCR, collections/watchers/sync and K3/K4 are deferred.
Crash-interrupted candidates without a live task ownership reference are preserved;
startup does not infer deletion authority from a filename. This can leave private
orphan staging files for explicit maintenance; no unknown object is recursively removed.
