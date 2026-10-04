# Architecture Decision Records

记录已经采用且影响真实实现的架构决策，不作为执行 roadmap。

| ADR | 状态 | 决策 |
| --- | --- | --- |
| [ADR-001](ADR-001-local-shared-runtime.md) | Accepted | 独立 Java Runtime、local-first、profiles、本机 token |
| [ADR-002](ADR-002-windows-client-credential.md) | Accepted | Windows 显式本机 token bootstrap + Credential Manager；保留 single trust domain |
| [ADR-003](ADR-003-browser-client-security.md) | Accepted | Browser explicit pairing、独立凭据、精确 Origin、Translate-only 与 task ownership；演进 single trust domain |
| [ADR-004](ADR-004-user-controlled-memory-storage.md) | Accepted | Runtime-owned explicit Memory、SQLite truth、derived FTS、revision、native-only、plaintext + OS boundary |
| [ADR-005](ADR-005-explicit-memory-context.md) | Accepted | Per-turn explicit context、ACTIVE exact-revision admission snapshot、combined budget、ordinary Ask isolation |
| [ADR-006](ADR-006-logical-memory-backup-restore.md) | Accepted | Versioned logical source backup、canonical digest、plaintext、new/empty target、fresh schema/FTS rebuild、no active DB replacement |
| [ADR-007](ADR-007-logical-workspace-backup-restore.md) | Accepted | 独立Workspace format1、Memory+terminal Conversation、bounded streaming、strict digest、new/empty v3 reconstruction与recovery |
| [ADR-008](ADR-008-hybrid-main-workspace-ownership.md) | Accepted | Runtime/WPF/React ownership、增量迁移、原生快速入口与退役门槛 |
| [ADR-009](ADR-009-webview2-trusted-content-bridge.md) | Accepted | 固定可信内容、最小 typed bridge、凭据隔离、会话与 WebView2 privacy boundary |
| [ADR-010](ADR-010-frontend-build-desktop-distribution.md) | Accepted | 单一 npm/React/TS/Vite package、bundled build/publish、Debug dev-only、原生 fallback |
