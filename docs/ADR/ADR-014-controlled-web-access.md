# ADR-014 — Controlled Web Access

Status: Accepted

Date: 2026-10-07 (Asia/Shanghai)

**W1 Architecture — APPROVED — GO.**
Architecture Guard: **W1 — Controlled Web Access: ARCHITECTURE GUARD REVIEW — APPROVED — GO**;
architecture blockers: 0. Approval follows the completed read-only W1 Reality Audit.
Decision baseline: authoritative `main / origin/main`
`af3c842906174801b6c434a255e826300e37a72b`.

**W1 implementation — NOT STARTED.** This task authorizes architecture publication only;
production implementation requires separate authorization. Architecture Guard closing review
of this publication candidate remains required.
Next independent implementation activity:
**W1A — Public Web Policy + Secure Fetch Foundation + Native Approval**.

## Product scope and explicit operations

W1 v1 adds a separate explicit **native-only Web** capability. The preferred product
surface is **Web**, with two distinct user-controlled operations: **Search Web** and **Fetch URL**.
A search result URL does not authorize Fetch; fetching it requires a separate explicit
user operation and native approval.

Ordinary Ask remains stateless, single-turn, LOCAL_ONLY, with no Web and no tools.
Conversation remains durable multi-turn with no automatic Web. Knowledge Answer remains
explicit local Knowledge grounding. Browser Extension remains Translate-only.
Finance Integration remains **BLOCKED pending F0 authoritative Finance Reality Sync**.
Memory / Knowledge / Conversation remain independent domains; W1 does not authorize
cross-domain autonomous orchestration or any Finance API/schema/auth/tool assumptions.

W1 v1 selects Reality Audit **Option D: explicit user Search / Fetch followed by local synthesis**.
The model does not select Web tools, derive a public query/URL from private context,
or receive direct network access. Existing `Provider` contracts and `OllamaProvider`
tool-call rejection remain unchanged. Native Ollama tool calling, Provider tool definitions
or tool-call result types, generic Tool Registry, Agent loop, Connector architecture
and planner framework are excluded. Future tool calling requires a separate review.

## Public intent, policy and native approval authority

Public request material must be explicitly user supplied. Search has separate inputs
for the question and the public search query; only the explicit query may be sent
to the Search backend. Fetch uses the explicitly supplied URL.
Memory, Knowledge, Conversation and Finance content must never be implicitly appended
to query, URL, headers, body, Referer, credentials or logs.

W1 v1 supports exactly `DISABLED` and `ASK_EVERY_TIME`, defaulting to `ASK_EVERY_TIME`.
`ALLOW_AUTOMATICALLY` is deferred; automatic Web access is outside W1 v1.
React cannot authorize Internet egress:

```text
React explicit intent
→ typed WPF bridge validation
→ WPF native confirmation of exact outbound public intent
→ user approval
→ WPF submits exact approved operation
→ Runtime Web execution
```

Search confirmation displays the explicit query. Fetch confirmation displays the
canonical URL and destination hostname. Cancel or absent confirmation means **zero
Runtime Web operation and zero public Internet egress**. Human approval precedes
Runtime Web execution admission; do not add `WAITING_APPROVAL` to `TaskManager`.

Session/document replacement invalidates pending native approval UI. A late approval
cannot revive a replaced session. Already admitted Runtime execution may follow existing
explicit operation semantics, but old WebView sessions cannot gain result authority.
Keep fixed typed operations and current-session authority; no generic HTTP bridge.

## Runtime-owned public transport

`PublicWebTransport` is the Runtime-owned public Web boundary, separate from
`OllamaProvider` transport, Desktop `RuntimeClient`, WebView2 and Browser Extension.
The existing Desktop-to-Runtime credential path does not authorize forwarding those
credentials to public destinations. Public Web requests never pass through Ollama.

Minimum W1 v1 transport contract:

- HTTPS only, port 443 only; arbitrary Fetch is GET only.
- No IP literals; reject userinfo, fragments, localhost and `*.localhost`.
  Reject `file:`, `http:`, `ftp:`, `data:`, `ws:` and `wss:`.
- No system proxy, cookies, browser profile/session or ambient Windows credentials.
- No Workspace token, Browser credential, Authorization or Referer forwarding;
  no model-defined arbitrary headers. Use a fixed application-owned User-Agent.
- Normal TLS certificate and hostname verification.
- Bounded DNS/connect/request/body/total deadlines, wire bytes, decoded/extracted
  text, concurrency and queue. Exact numeric budgets require W1A implementation review.
- Cancellation prevents further egress; an uncertain result never causes automatic replay.

### DNS / SSRF and actual connection authority

URI syntax validation alone is insufficient. For every public Fetch target:

```text
canonical hostname → bounded DNS resolution → inspect every resolved address
→ reject if any address is non-public → pin validated address set to actual connection
→ preserve TLS hostname / SNI / certificate validation
```

Deny at minimum loopback, private/LAN, link-local, multicast, reserved/non-routable,
metadata-style targets, IPv6 ULA/link-local and IPv4-mapped private/loopback IPv6 forms.
Mixed public/private DNS answers fail closed. Do not validate once and let the HTTP
stack perform an uncontrolled second DNS resolution.
A narrow HTTP transport dependency may require implementation review; this publication
selects no library.

### Redirects and content scope

Automatic redirects are **OFF**. Runtime may manually process at most **2 hops**, each
requiring full URI/scheme/port validation, DNS resolution, address classification,
pinning and TLS hostname validation. Redirects must retain the **same exact canonical
hostname**; cross-host redirects are denied.

Supported response types are exactly `text/html`, `text/plain` and `application/xhtml+xml`.
PDF, DOCX, archives, executables, images, audio, video and arbitrary binary formats
are outside W1 v1. No JavaScript/CSS execution, subresource fetching, iframe navigation,
recursive crawling, browser automation or download execution.
Prefer `Accept-Encoding: identity`; W1 v1 must not depend on compressed public bodies.
A dedicated HTML parser/extractor may be added during W1A after dependency review;
WebView2 is not the extraction engine.

## Search and Fetch remain separate

```text
Search: explicit query → one reviewed Search backend → bounded metadata/snippets
Fetch:  explicit URL → PublicWebTransport → bounded document evidence
```

Search discovery grants no Fetch permission. Do not introduce a generic arbitrary
HTTP tool or a speculative provider marketplace/connector framework.
**No Search vendor/provider is selected.** W1B must complete a narrow Search Backend
Review before implementation. Backend-specific request details remain review decisions
within the explicit public-intent and transport/privacy boundaries above.

## Frozen public evidence and local synthesis

`WebEvidenceSnapshot` names an immutable, operation-local snapshot. It is public,
ephemeral, bounded, Runtime-owned and frozen before synthesis. Web evidence is not
authoritative personal-domain truth.
Possible provenance includes a Runtime source label, source kind, title, requested URL,
final validated URL, hostname, acquisition time, content type, extraction version
and bounded text. These are conceptual provenance requirements; this ADR does not
lock unnecessary DTO field names.

Permanent boundary: **WebEvidenceSnapshot != KnowledgeEvidenceSnapshot**.
Do not introduce a generic "all evidence" domain abstraction.

After evidence is frozen, final synthesis reuses the existing `TaskManager`,
`ProviderPolicy` and LOCAL_ONLY Ollama path. Do not hold network/security locks during
model inference. Public requests are explicit user operations, not model tool calls.

## Narrow Web Answer and Runtime citation authority

W1C may introduce a narrow `TaskResult.WebAnswer`; no generic `AgentResult` or
`EvidenceResult`. Follow K3's strict structured answer style, with citation labels
assigned by Runtime evidence admission:

```json
{
  "answer": "...",
  "citations": ["W1", "W2"]
}
```

Runtime owns **label → actual Web evidence provenance**, independently of model output.
Require a nonblank answer and nonempty citations where evidence is referenced.
Reject duplicate fields, trailing tokens, unknown fields, incorrect types, duplicate
citation labels and unknown citation labels. Malformed results reject the whole answer;
no tolerant fallback. The answer is plain text only.
Labels identify referenced admitted Web evidence; citations are not formal entailment proof.

## Ephemeral state and untrusted-content boundary

W1 v1 does not automatically persist search queries, URLs, fetched page bodies,
Web evidence, answers, citations, history or cache. Runtime restart may discard
transient state. Do not modify Memory DB, Conversation DB, Knowledge DB, Workspace
Backup, Knowledge Backup or Finance DB. Saving a Web source into Knowledge would
require a separate explicit future operation.

Public Web content is untrusted data. Prompt boundaries distinguish Runtime system
instructions from Web evidence. Fetched/search evidence cannot issue Runtime instructions,
authorize further requests, invoke tools, modify Memory/Knowledge/Finance, bypass
approval or change policy. Prompt instructions do not replace enforced security boundaries.
No private-content logging or provider raw body/error echo.

Browser identity is denied Web Search, Fetch, Answer, approval and evidence authority;
Translate-only route/capability/Origin/task ownership remains intact.
Finance stays frozen pending F0; W1 grants no cross-domain autonomous authority.

## Internal implementation phases and verification ownership

These are internal W1 phases, not new roadmap milestones; none starts in this task.

| Phase | Approved scope / required review |
| --- | --- |
| W1A | Public Web Policy + Secure Fetch Foundation + Native Approval; review numeric budgets and any transport/extractor dependency |
| W1B | Explicit Search Backend + bounded Search Evidence; narrow Search Backend Review before implementation |
| W1C | Web Answer + Frozen Web Evidence + Runtime-owned Citations + Web UI |
| W1D | Integrated Windows/Public-Network Acceptance + Closing |

Future verification follows the existing minimal-testing policy: reuse owning evidence
and prefer a small realistic integrated flow. W1D is future acceptance, not executed
evidence. This architecture publication changes documentation only; **tests/builds
executed: NONE**. No public Web smoke, model inference or WorkspaceSanity is run here.

Deferred capabilities include automatic Web access, model tool calling, additional
content types, durable Web cache/history and explicit save-to-Knowledge. Each requires
separate applicable review; no generic tools, evidence or connector framework is approved.

**W1 Architecture — APPROVED — GO. W1 implementation — NOT STARTED.**
**ARCHITECTURE GUARD CLOSING REVIEW — REQUIRED.**
