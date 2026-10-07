# Architecture Decision Records

记录已经采用的架构决策，不作为执行 roadmap；Accepted 不等于已实施，实施状态见 STATUS。

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
| [ADR-011](ADR-011-knowledge-domain-storage-ingestion-recovery.md) | Accepted | Knowledge 独立 ownership、private sources/schema、deterministic ingestion/revision/locator、publication/reconciliation、独立 backup/restore；既有 Architecture Guard K0 approval |
| [ADR-012](ADR-012-deterministic-lexical-retrieval.md) | Accepted | K2 deterministic lexical retrieval、独立 derived FTS5/BM25 index、fingerprint freshness、bounded rebuild、native-only/privacy；Architecture Guard K2 approval |
| [ADR-013](ADR-013-grounded-knowledge-answer-citations.md) | Accepted | K3 CLOSED — GO；explicit native-only knowledge-answer、question + lexical query、atomic immutable evidence admission、ranked full-chunk prefix、strict JSON / Runtime-owned answer-level citations；Architecture Guard IMPLEMENTATION / SOURCE CLOSING REVIEW APPROVED — GO |
| [ADR-014](ADR-014-controlled-web-access.md) | Accepted | W1 Architecture APPROVED — GO；implementation NOT STARTED；explicit native-only Search/Fetch、WPF approval、Runtime PublicWebTransport / DNS pinning、ephemeral frozen Web evidence、local synthesis / Runtime citations；next W1A，publication closing review APPROVED — GO；formal publication COMPLETE（本 closing commit 发布到 main 时生效） |
