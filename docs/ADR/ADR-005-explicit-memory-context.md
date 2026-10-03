# ADR-005 — Explicit Memory context for one Ask

Status: Accepted

Date: 2026-10-03 (Asia/Shanghai)

## Context

Durable, manually authored Memory is managed by Runtime. Users need to inspect and explicitly choose which saved context a single local Ask receives. Ordinary Ask remains stateless and must not acquire implicit Memory behavior.

## Decision

Use a separate native-only admission endpoint, `POST /api/v1/memory/ask/tasks`. Desktop sends the question, `chat.balanced`, and 1–4 unique nonempty UUID/revision references. It never resubmits Memory title or content. A separate read-only WPF selector lists ACTIVE entries, fetches full preview content and returns references plus current display metadata only after explicit confirmation.

Runtime resolves the entire selection in requested order in one SQLite read transaction before TaskManager admission. Each item must exist, be ACTIVE and match its exact revision. Missing, archived or edited selections reject the entire admission with HTTP409 `MEMORY_SELECTION_STALE`. There is no substitution, retry, partial use or automatic refresh/resubmit. An accepted task uses its immutable admission-time Memory snapshot; subsequent edits, archive or deletion do not alter or cancel that task.

Execution retains capability `ask`, profile `chat.balanced`, the existing ProviderPolicy/Ollama/TaskManager, task ownership, cancellation and deadlines. The explicit variant is identified by `memory-ask-v1`; ordinary `AskRequest(question, profile)` and `ask-v1` are unchanged.

Serialize the question and user-authored Memory type/title/content as deterministic JSON using a proper serializer. Keep Memory out of the system message. Memory is treated as untrusted user-authored context; structural and policy boundaries reduce instruction confusion. Preferences and project constraints may inform the answer; the current question has precedence over conflicting context, and conflicting Memory calls for uncertainty. These boundaries do not establish immunity to prompt injection or make Memory verified truth. No tools, browsing, history or external actions are introduced.

The actual combined JSON input, including escaping and wrapper, must fit the existing profile's 3000 UTF-16-unit text limit and 5632-byte conservative input budget (8192 context minus 2048 output minus 512 reserve). The system message remains within 512 UTF-8 bytes. Four entries is only a selection guard, never a budget guarantee. Reject overflow; do not truncate, summarize, drop entries, split calls or expand budgets.

Selection is per turn and held only in the current UI. Clear it after accepted-task completion/failure/cancellation, Clear Memory, action change, window close/cleanup and exit. Admission failures can retain selection; stale blocks submission until explicit review/reselection or clearing. Opening Review / Change starts a fresh selector; Cancel preserves the current selection. Accepted-task communication failures also clear selection to prevent accidental reuse.

Do not persist questions, answers, selections, snapshots, prompts or provider payloads. Task state remains short-lived in memory. Browser retains its Translate-only route/capability allowlists with no new CORS route. No automatic selection, injection, extraction, profile generation or retrieval is introduced; selector search is an explicit literal management query.

## Consequences

Users control each turn and can see every context entry before confirmation. Exact revisions can produce intentional stale conflicts requiring reselecting. The Runtime remains final authority. The model may still misunderstand or ignore supplied context; an acceptance example demonstrates transmission/use only. Export/restore, Conversation and RAG require separate scope and decisions.
