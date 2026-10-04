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
| [ADR-007](ADR-007-logical-workspace-backup-restore.md) | Implemented locally; pending Review | 独立Workspace format1、Memory+terminal Conversation、bounded streaming、strict digest、new/empty v3 reconstruction与recovery |
