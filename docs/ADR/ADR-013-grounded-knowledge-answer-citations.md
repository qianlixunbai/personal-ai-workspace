# ADR-013 — Grounded Knowledge Answer and Runtime-Owned Citations

Status: Accepted

Implementation status: **K3 — CLOSED — GO**.
Implementation candidate: `c938e21539cc001576a243f8cfb2ba6163223d56`.
Architecture Guard: **IMPLEMENTATION / SOURCE CLOSING REVIEW — APPROVED — GO**;
blocking findings / required production fixes / required new tests / required reruns: 0.
Formal publication remains pending final closing-documentation/publication review.
Implementation boundaries and inherited execution evidence are recorded in the
[K3 Closing Report](../milestones/K3-CLOSING-REPORT.md).

Date: 2026-10-06 (Asia/Shanghai)

Approval provenance: Architecture Guard reviewed the K3 Reality Audit and issued
**K3 — Grounded Knowledge Answer + Citations: ARCHITECTURE REVIEW — APPROVED — GO**,
with zero architecture blockers. The Architecture Guard-approved decisions recorded
here are authoritative for K3 architecture.
Decision baseline: authoritative main
`2b6274c9413ffa732ae9d5fbca24d1ad3a29c931`.

## Purpose, explicit inputs and permanent boundaries

K3 adds one separate native-only capability, `knowledge-answer`, because use of
imported reference material must be explicit and distinguishable from ordinary
stateless single-turn Ask and durable multi-turn Conversation. Memory remains
explicit reusable context; Knowledge remains imported reference material.
**Memory ≠ Knowledge ≠ Finance.** No automatic Knowledge injection is permitted.

K3 v1 is an **Ask Knowledge / 基于知识回答** card inside the existing Knowledge page,
with no new top-level route or Ask mode. It requires two explicit user inputs:
`question` and lexical retrieval `query` / 检索关键词. The user controls retrieval
terms under unchanged K2 deterministic lexical AND semantics. There is no model
or semantic query rewrite and no automatic question-to-query transformation.

```text
explicit question + explicit lexical query
→ deterministic K2 retrieval
→ authoritative immutable evidence capture
→ existing local model task
→ strict result validation
→ plain grounded answer + Runtime-owned answer-level citations
```

`knowledge.db` remains authoritative Knowledge truth. The lexical index remains
derived, disposable and rebuildable. Browser Extension remains Translate-only.
Finance Integration remains **BLOCKED pending F0 authoritative Finance Reality Sync**;
W1 remains **NOT STARTED**. Neither domain is authorized by this decision.

## Owning code and one authoritative admission boundary

`KnowledgeStore` owns synchronized `snapshot`, `searchCorpus`
and `searchSource` semantics; `KnowledgeLexicalIndex.search` owns bounded K2
retrieval, and `LexicalChunker` owns deterministic chunk boundaries. K2's returned
snippets are presentation data, not sufficient evidence authority for K3.

`KnowledgeEvidenceAdmission` is the narrow knowledge-package owner
around those existing owners. Search and evidence capture occur in **one
authoritative Knowledge consistency boundary**, not as independently locked calls:

```text
KnowledgeStore snapshot/monitor
  → K2 lexical search
  → verify authoritative ACTIVE/current READY corpus and exact source revision
  → reconstruct and verify exact evidence from normalized Knowledge state
  → freeze immutable EvidenceSnapshot
release Knowledge boundary
  → pack complete evidence and submit model task
```

Preserve the existing nested lock order **KnowledgeStore → lexical index publication**;
never acquire the Store under a held publication lock. No Knowledge lock may be
held across inference. A mutation cannot interleave search and evidence capture.
Unavailable or disagreeing derived state fails closed before creating a model task.

For each hit, recover the authoritative normalized representation from `knowledge.db`,
verify `documentId`, `sourceRevision`, `startOffset` and `endOffset`, and, where practical,
recompute/verify `LexicalChunker` boundaries and associated lines/heading/typed locator.
Use exact authoritative text for that range; never trust React/WPF snippets or
reopen the user's original external file. Preserve K2's UTF-16 zero-based,
end-exclusive offsets and one-based inclusive line ranges, including Unicode safety.

Do not redesign ingestion, schema v1, Knowledge Backup v1, analyzer/chunker behavior,
ranking/index semantics or Memory/Conversation stores. If exposing authoritative
snapshot data safely requires a tiny internal helper/refactor, implementation must
document it explicitly and preserve observable K1/K2 behavior. No schema migration
is required. The implemented admission owner preserves those boundaries.

## Immutable evidence ownership and later mutations

Each operation labels admitted items `S1`, `S2`, … in deterministic ranked order.
The immutable snapshot owns at least:

| Snapshot data | Authority / visibility |
| --- | --- |
| Label, documentId, title, sourceRevision, sourceType | Runtime retains authoritative identity and label mapping |
| Exact startOffset/endOffset, startLine/endLine, heading | Exact evidence range, not snippet-relative coordinates |
| Structural typed locator and exact evidence text | Reconstructed from authoritative normalized Knowledge state |
| Necessary representation provenance | Internal digest/version details stay Runtime-internal unless explicitly needed |

Model-visible evidence contains only necessary presentation data such as label,
title, heading and text. The model receives no authoritative citation IDs.
Runtime retains **label → authoritative citation identity** independently of model output.

After acceptance, update/archive/delete cannot change the frozen model input:

| Later mutation | Accepted task and source preview |
| --- | --- |
| Update | Accepted answer uses the admitted revision; a retained old revision may remain previewable |
| Archive | Accepted answer may finish; new retrieval excludes the archived document |
| Physical delete | Accepted answer may finish from frozen in-memory evidence; later preview may be unavailable |

Never substitute current/latest revision for the admitted revision. Do not recreate
hidden durable evidence or retain a second durable source solely for K3.

## Deterministic full-chunk budget packing

Use unchanged K2 deterministic ranking and request at most its existing candidate
maximum (10 at the baseline); the K3 client cannot choose an arbitrary limit.
Pack a **ranked prefix** of complete K2 chunks. For each next candidate, serialize
the entire proposed model input, including question, evidence presentation fields
and serialization overhead, and check the active profile's actual input limits.
Both character and UTF-8 byte limits apply; system/output reserves also apply.
`TextTaskSubmission` remains the final budget authority.

If S1 and S2 fit but S3 does not, use S1 + S2 and stop. Do not skip S3 to try S4,
truncate, summarize, rewrite or silently drop content inside an admitted item.
If the first candidate cannot fit, return a controlled evidence/context-budget
failure and create no model task. K3 v1 has no excerpt policy: complete chunks
preserve verifiable text/range identity without introducing new selection semantics.

## Existing local execution and narrow task result

Reuse `TextTaskSubmission.submitMapped → TaskManager → ProviderPolicy → LOCAL_ONLY
Provider → Ollama`. Initial profile is existing `chat.balanced`; capability is
`knowledge-answer`, prompt version is `knowledge-answer-v1`. Do not add another
task framework or a new model profile merely for symmetry.

The existing sealed `TaskResult` includes one narrow immutable subtype,
`KnowledgeAnswer(answer, validated Runtime-generated citations)`.
Model labels are intermediate validation input, not final citation authority.
Ordinary string task contracts remain unchanged; TaskManager needs no architectural
redesign. Existing bounded queue, owner, cancellation, timeout and in-memory
retention semantics continue to govern accepted tasks.

## Strict structured JSON and Runtime citation authority

The model must return strict JSON text with exactly this semantic schema:

```json
{
  "answer": "<non-empty plain text>",
  "citations": ["S1", "S2"]
}
```

Final DTO names may follow repository conventions; these semantics are locked.
Parse only an object with exactly `answer` and `citations`; reject duplicate keys,
trailing content, malformed JSON, incorrect types and empty/blank answers.
`citations` must be a non-empty array of unique string labels, each belonging to
the admitted evidence. Any unknown label rejects the whole result. No partial
success, label filtering or fallback to an ungrounded answer is permitted.
The model must not supply documentId, sourceRevision, path, offset, database
identity or a title as citation authority. Runtime alone maps validated labels
to real citation metadata from the immutable snapshot.

K3 v1 uses **answer-level citations**: plain answer text, followed by
**引用来源 / Sources**. There is no inline citation grammar. This keeps model
validation and application-rendered source controls narrow and independently owned.
Each citation exposes bounded safe documentId/title/sourceRevision/sourceType,
exact evidence and line ranges, heading where applicable, and a structural locator
where useful, sufficient to identify/open the admitted source position. Do not
expose digests, fingerprints, SQL IDs, filesystem paths or internal scores.

A citation proves which admitted evidence the model referenced. It does **not**
cryptographically or semantically prove that every answer sentence is entailed.
Users can inspect the exact admitted revision/range; unavailable deleted sources
must be reported without substituting another revision.

## Fail-closed behavior and ephemeral persistence

| Condition | Required behavior |
| --- | --- |
| Zero lexical hits | Controlled no-evidence result/error; no model call/task |
| Index NOT_READY / STALE / BUILDING / FAILED | No stale fallback; no model call/task |
| Derived index/evidence disagreement | Fail closed; no model call/task |
| First evidence cannot fit | Controlled evidence/context-budget failure; no model call/task |
| Provider/model unavailable | Existing controlled local failure; no cloud fallback |
| Queue full | No accepted task |
| Timeout | TIMED_OUT; a late answer cannot win |
| Cancel | CANCELLED; a late answer cannot win |
| Malformed JSON or invalid/unknown/duplicate citation labels | FAILED; no partial result |
| Zero citations | FAILED / controlled grounding failure; never grounded success |
| Runtime restart | Task/result do not survive; no replay or persistent recovery |
| POST outcome unknown | No automatic replay |

Question/query/answer/citations are ephemeral. K3 adds **no new DB, Knowledge
schema migration, answer table, citation history table, Conversation dependency
or backup format change**. Reload, session replacement and Runtime restart do
not restore K3 answer state.

## Typed bridge, current-session authority and React lifecycle

Use fixed typed methods only: `knowledge.answerSubmit`,
`knowledge.answerGet` and `knowledge.answerCancel`. Trusted Workspace/current session only; maintain bounded
task authority. Authorize task IDs only after accepted submission. Get/cancel
require an authorized current-session task. Validated citations may authorize
their real document IDs for preview in that captured session. Late responses cannot
authorize a replacement session. No automatic replay or generic HTTP/task/native
proxy; no token/path/source bytes exposed to JS. Existing bridge byte bounds
(32 KiB request / 64 KiB ordinary response) remain unless proven impossible.

Browser is denied K3 before body processing through existing security boundaries;
route/capability/Origin/CORS authority remains Translate-only, including task access.
Admit `knowledge-answer` for native identity only; existing capability contracts remain unchanged.

React holds question/query/answer/citations in memory only, clearing them on session
replacement and leaving the Knowledge page. No localStorage/sessionStorage/URL or
browser cache/history persistence; no autosubmit. IME composition Enter must not
submit. Render answers as plain text and citations as application-owned controls,
never model-rendered links, HTML or Markdown. Leaving the page may allow an accepted
Runtime task to continue, but a late result cannot repopulate cleared UI. Do not
introduce a complex route-level replay system.

## Untrusted evidence and prompt-injection boundary

Imported Knowledge is untrusted data. Structurally separate Runtime system prompt
and evidence. Evidence cannot grant system/developer/tool authority, Web access,
Finance authority or Memory mutation. Prompt instructions are defense-in-depth,
not a security boundary. Actual boundaries remain LOCAL_ONLY, no tools, no Web,
no Finance, no Browser, Runtime citation mapping, bounded inputs and typed bridge.
Question/query/evidence/answer must not appear in logs or diagnostic `toString` output.

## Permanent T0-compatible verification ownership and acceptance risk

No test-count target or historical acceptance matrix is introduced. Prefer the
smallest high-value owning evidence and reuse existing tests; do not duplicate
K2 ranking/index tests or the same contract across layers.

| Owner | Retained K3 invariants |
| --- | --- |
| Java | Immutable atomic evidence admission; deterministic prefix packing; strict parser and invalid/zero/unknown citation fail-closed; native-only API/Browser denial; mutation after admission |
| Desktop | Typed result projection; bounded task/current-session authority; late-response/session replacement safety |
| Frontend | Explicit question/query submission; plain answer/citation rendering and source open; session/page clearing; IME guard if a new handler owns it |

WorkspaceSanity must not automatically gain real model inference. A focused real
local K3 inference/acceptance gate establishes whether the configured model can
satisfy strict JSON output. Accepted candidate evidence is linked in the closing
report; this ADR is not an execution log. If compliance is unacceptable, **STOP**; do not weaken
validation to pass. Separately review a K3-specific profile or structured-output
support before continuing.

## Explicit exclusions and closing boundary

K3 is implemented within this accepted decision. No automatic Knowledge for Ask/Conversation,
Ask mode, semantic/vector search, embeddings, query rewrite, Web, Finance, Vision,
Browser expansion, persistent answer history or Conversation integration is included.
K1/K2 truth and backup formats stay unchanged. Finance Freeze and W1 NOT STARTED
remain untouched.

**K3 — CLOSED — GO. Architecture Guard IMPLEMENTATION / SOURCE CLOSING REVIEW — APPROVED — GO.**
Formal publication is pending final closing-documentation/publication review.
Next milestone: **W1 — NOT STARTED**; next independent activity is **W1 Architecture / Planning**.
This decision does not authorize W1 implementation.
