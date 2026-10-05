# ADR-011 — Knowledge Domain, Storage, Ingestion and Recovery

Status: Accepted

Date: 2026-10-05 (Asia/Shanghai)

Approval provenance: the existing Architecture Guard K0 approval supplied with
the K1 implementation authorization. This record does not constitute a new
approval by the Implementation Engineer. K1 closing requires separate review.

## Domain and ownership

Knowledge is independent of Memory. Explicitly imported reference sources are
not `memory_items`, MemoryType values, Conversation messages or AI context.
Runtime owns the durable Knowledge domain. WPF owns native pickers, opened file
handles, bounded authenticated transport and privileged backup file IO. React
owns presentation and bounded session state; it receives no filesystem path,
file handle, source upload bytes, backup bytes, credentials or Runtime location.
The established React → typed WPF bridge → application-owned RuntimeClient →
Runtime chain remains. Browser remains Translate-only with no permission/CORS
expansion. Knowledge performs no model/provider/Ollama/TaskManager execution.

Finance integration deferred pending authoritative Finance Reality Sync.
Knowledge has zero Finance implementation dependency or API/schema/auth/tool
assumptions. Its ownership can remain independent during future integration.

## Durable layout and identity

Below `workspace.data-directory`, `knowledge/knowledge.db` independently owns
its versioned schema, Documents, immutable READY Revisions, normalized text,
typed structural locators and minimum durable ingestion/publication/delete
journals. `knowledge/sources/<random-document-uuid>/<revision>.source` contains
exact imported bytes; `knowledge/staging/` contains bounded task-owned work.
Memory/Conversation `memory.db` and their schemas/backups are not migrated.

Directories/files are current-account private before durable content is written.
Links/reparse points, network authority, repository/build/log/auth locations and
unowned objects fail closed. Protection is account/filesystem access control,
not encryption, administrator isolation or forensic erasure.

Document identity is a canonical random UUID, independent of name/path/digest.
Document lifecycle ACTIVE/ARCHIVED and optimistic `metadataVersion` are separate
from processing state. Restore preserves identity and creates no source revision.
Archive/restore/delete require an expected metadata version. JS-facing positive
Int64 versions/revisions use canonical decimal strings.

Exact imported bytes determine SHA-256 `sourceDigest`. Changed bytes on the same
Document create a new `sourceRevision`; identical bytes return the existing
revision without silent merge. A different Document with the same digest is
rejected with DUPLICATE_SOURCE without disclosing the other source. Software
reprocessing never changes sourceRevision: parserVersion and
normalizationVersion independently identify the immutable representation.

## Admission, representation and publication

Only explicit TXT/Markdown import is admitted. WPF validates the actual opened
local regular file and streams that handle through a dedicated native-only
bounded upload path; Runtime accepts bytes, never a source path authority.
Strict UTF-8, Unicode, meaningful text, NUL/binary rejection and capacity checks
are mandatory. Original display filenames are safe metadata, never extraction
paths. External original files may subsequently move or disappear.

Normalization is deterministic/versioned, preserving textual Markdown without
executing HTML, following links or loading resources. Authoritative normalized
text, representation digest and stable typed line/section locators live in the
Knowledge DB. Preview is plain text and ranged within the ordinary 64 KiB bridge
response budget, including worst-case JSON escaping. No retrieval/chunk/index,
embedding, citation or Knowledge Answer is implemented in K1.

Initial limits: 500 Documents, 10 revisions/Document, 2000 retained revisions,
8 MiB/source, 2 MiB normalized UTF-8 and 500000 code points, 2 GiB source corpus,
256 MiB normalized artifacts, 100000 lines and 10000 structural blocks. Ingestion
has one worker and four queued jobs. Durable request UUIDs support outcome lookup;
unknown transport outcomes are never automatically uploaded again.

PENDING/PARSING/READY/FAILED/CANCELLED/INTERRUPTED are independent of lifecycle.
Private staging and a durable candidate journal precede parsing/publication.
Source bytes are published immutably before one DB transaction publishes the
complete representation/revision and advances currentReadyRevision. Filesystem
rename and SQLite commit are not one atomic transaction. Only complete validated
READY revisions are visible; failed updates preserve the previous READY pointer.
Startup reconciles unfinished jobs to INTERRUPTED, verifies READY sources and
cleans only provably owned/unreferenced candidates. It never replays unknown work
or recursively removes unknown user data. Explicit cancellation is admitted
before the final publication boundary.

Physical delete requires explicit default-safe confirmation and optimistic
versioning. A minimal durable delete journal and same-filesystem staging rename
allow rollback while the Document exists and completion after DB deletion.
Locked sources produce controlled failure/retry, never a false success.

## Knowledge Backup v1 and recovery

Knowledge Backup v1 is independent of Workspace Backup format1 (Memory +
Conversation) and Memory Backup. It is a versioned, bounded streaming container
of Documents, retained READY revisions, exact source bytes, normalized text,
typed locators, digests, versions, lifecycle and current READY pointers. It
excludes temporary staging, running jobs, logs, credentials and absolute paths.
Opaque identities determine internal paths. Strict validation rejects unsupported
versions, unknown fields, duplicate identities/entries, unsafe paths, malformed
metadata, record/decoded-size explosions and digest/size/pointer mismatches.
Bounded buffers and a derived decoded ceiling below approximately 3 GiB prevent
whole-corpus buffering.

Runtime owns logical export/validate/restore; WPF owns dialogs/file streams;
React invokes only `native.openKnowledgeBackup`. Validation publishes nothing.
Restore reconstructs and verifies an inactive complete Knowledge layout in a
private task-owned staging directory, then publishes into a new/empty Workspace
data directory. No merge, overwrite, hot replacement or automatic switch occurs.
Users explicitly restart Runtime/Desktop with the restored data directory.
Memory, Conversation and auth/Browser state are not restored by this format.

Future retrieval indexes are derived and rebuildable from retained private
sources and versioned normalized truth. This ADR does not select an embedding
model, vector database, PDF/DOCX parser, lexical analyzer, model prompt or Finance
integration. K2/K3/K4 remain NOT STARTED.
