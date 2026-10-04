# ADR-008 — Hybrid Main Workspace Ownership and Incremental Migration

Status: Accepted

Date: 2026-10-04 (Asia/Shanghai)

## Decision implemented in M5A

M5 — Unified Main Workspace UI uses one WPF Desktop product with a bundled React/TypeScript
presentation shell hosted by Microsoft WebView2. M5A establishes the shell and explicit native
entries only. It does not implement React domain pages or change Runtime domain semantics.

Runtime remains the owner of durable Memory/Conversation, SQLite, execution truth, TaskManager,
context assembly, provider policy, Ollama interaction and logical backup validation. React is
never a second durable store. WPF remains the owner of application lifetime, single instance,
tray, hotkey, UIA/selection, controlled clipboard/copy, helper processes, Credential Manager,
native dialogs/confirmations, WebView lifecycle/focus and bridge admission. The existing
application-owned RuntimeClient and CredentialStore are reused.

React owns shell layout, five navigation destinations, presentation, loading/errors, focus,
and non-sensitive presentation state. M5A has no domain forms, AI execution, domain cache,
Memory selection or Conversation CRUD. Its only stored preference is `workspace.theme` with
`light`/`dark`, within the private WebView session; profile cleanup means this is not promised
to survive closing the workspace. No personal text, queries, paths, results, secrets or backup
metadata are stored in browser storage.

The tray offers Main Workspace explicitly. Legacy Assistant remains the quick native surface,
including the original hotkey/selection workflow. Main Workspace is intended to become the
primary management surface through separately reviewed M5B–M5E work. Opening it does not
replace the tray double-click, single-instance activation or hotkey Assistant behavior in M5A.

## Incremental migration and retirement gates

AssistantWindow, ConversationWindow, MemoryWindow, MemorySelectionWindow, MemoryBackupWindow,
WorkspaceBackupWindow and BrowserPairingWindow remain production functionality and fallback.
Shell buttons use fixed native targets and real existing windows. Credential management opens
Assistant and focuses its Import button; import/forget remain explicit native actions.

Retirement requires a later approved milestone with equivalent domain functionality, security,
privacy, accessibility, real Windows acceptance and regression evidence, plus an explicit
Architecture/Closing Review decision. No surface is retired by this ADR or M5A. The quick
native surface remains part of the approved ownership split even as management pages migrate.

External Runtime mode is unchanged. M5A does not supervise Runtime/Ollama, infer ownership from
a listening port, kill unrelated processes, install software, or add another tray/hotkey owner.

## Consequences

M5A's pages honestly route users to native functionality while the management layout becomes
usable. Business migration, model/provider editing, installers, auto-update and final packaging
are still deferred. ADR-001..007 and Browser Translate-only permissions remain in force.
