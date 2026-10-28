# Documentation

历史源码核验基线：`12dce0765dcdb89b75fd605c2b4fc7bf5db00617`（2026-10-28）。文档现代化与 Closing 随 main 发布，最新主线提交见 [当前状态](STATUS.md)。**Development-only，尚未 Production GO。**

## 从这里开始

| 读者／问题 | 入口 |
| --- | --- |
| 第一次了解产品 | [GitHub README](../README.md) |
| 功能到底实现到哪里、还阻塞什么 | [STATUS](STATUS.md) |
| 环境、构建、启动与凭据边界 | [Development Guide](DEVELOPMENT.md) |
| 模块、调用链与数据 ownership | [当前架构](architecture/current-architecture.md) |
| 已交付、待验收、未来规划 | [Roadmap](roadmap/V1-ROADMAP.md) |
| 采用了哪些架构合同 | [ADR index](ADR/README.md) |
| Finance 的来源与只读范围 | [Finance module](../finance-domain/README.md) |
| 为什么旧文档还有 Current / NOT STARTED | [历史归档说明](history/README.md) |
| 本轮改动与验证证据 | [文档现代化报告](engineering/DOCUMENTATION-MODERNIZATION-REPORT.md) |

## 如何判断证据

当前事实先核对源码，再结合 STATUS 和最近的专属报告。ADR 的 Accepted 是决定状态，报告的 GO 只在其记录范围内有效；历史本机 PASS、用户人工报告、无模型 fixture 与当前 CI／真实 Windows 验收是不同证据。

**X1 Windows Acceptance PARTIAL、MODEL_EXECUTION_UNCERTAIN、Vision BLOCKED、尚无已核验的 GitHub CI PASS** 仍保留。源码整合与文档更新不关闭这些关卡。

下列为完整 Markdown 导航。工程／里程碑／设计／审计正文按原日期与基线保留，旧分支名称仅作历史定位。完整本地 ignored 日志、包、Token 和数据库不随此导航发布。

<a id="adr"></a>

## ADR：决定与合同

| 文档 | 原始标题 |
| --- | --- |
| [ADR-001-local-shared-runtime.md](ADR/ADR-001-local-shared-runtime.md) | ADR-001 — Independent local shared runtime |
| [ADR-002-windows-client-credential.md](ADR/ADR-002-windows-client-credential.md) | ADR-002 — Windows client credential bootstrap |
| [ADR-003-browser-client-security.md](ADR/ADR-003-browser-client-security.md) | ADR-003 — Browser client security and task ownership |
| [ADR-004-user-controlled-memory-storage.md](ADR/ADR-004-user-controlled-memory-storage.md) | ADR-004 — User-Controlled Memory Storage |
| [ADR-005-explicit-memory-context.md](ADR/ADR-005-explicit-memory-context.md) | ADR-005 — Explicit Memory context for one Ask |
| [ADR-006-logical-memory-backup-restore.md](ADR/ADR-006-logical-memory-backup-restore.md) | ADR-006 — Versioned logical Memory backup / restore |
| [ADR-007-logical-workspace-backup-restore.md](ADR/ADR-007-logical-workspace-backup-restore.md) | ADR-007 — Versioned Logical Workspace Backup / Restore |
| [ADR-008-hybrid-main-workspace-ownership.md](ADR/ADR-008-hybrid-main-workspace-ownership.md) | ADR-008 — Hybrid Main Workspace Ownership and Incremental Migration |
| [ADR-009-webview2-trusted-content-bridge.md](ADR/ADR-009-webview2-trusted-content-bridge.md) | ADR-009 — WebView2 Trusted Content and Least-Privilege Bridge |
| [ADR-010-frontend-build-desktop-distribution.md](ADR/ADR-010-frontend-build-desktop-distribution.md) | ADR-010 — Frontend Build and Desktop Distribution |
| [ADR-011-knowledge-domain-storage-ingestion-recovery.md](ADR/ADR-011-knowledge-domain-storage-ingestion-recovery.md) | ADR-011 — Knowledge Domain, Storage, Ingestion and Recovery |
| [ADR-012-deterministic-lexical-retrieval.md](ADR/ADR-012-deterministic-lexical-retrieval.md) | ADR-012 — Deterministic Lexical Retrieval and Rebuildable Index |
| [ADR-013-grounded-knowledge-answer-citations.md](ADR/ADR-013-grounded-knowledge-answer-citations.md) | ADR-013 — Grounded Knowledge Answer and Runtime-Owned Citations |
| [ADR-014-controlled-web-access.md](ADR/ADR-014-controlled-web-access.md) | ADR-014 — Controlled Web Access |
| [ADR-015-active-model-management.md](ADR/ADR-015-active-model-management.md) | ADR-015 — Active Model Management Foundation |

<a id="finance"></a>

## 工程报告：Finance 与行情

| 文档 | 原始标题 |
| --- | --- |
| [F2.3-A2-FINANCE-POSTGRESQL-INTEGRATION-REPORT.md](engineering/F2.3-A2-FINANCE-POSTGRESQL-INTEGRATION-REPORT.md) | F2.3-A2 FINANCE POSTGRESQL INTEGRATION REPORT |
| [FINANCE-DASHBOARD-DRILLDOWN-REPORT.md](engineering/FINANCE-DASHBOARD-DRILLDOWN-REPORT.md) | Finance UX D1 — Dashboard Drill-down 交付 |
| [FINANCE-F-NEXT-1-REPORT.md](engineering/FINANCE-F-NEXT-1-REPORT.md) | Finance F-next-1 — 原生身份绑定与只读账户 |
| [FINANCE-F-NEXT-10-PROMPT.md](engineering/FINANCE-F-NEXT-10-PROMPT.md) | Finance F-next-10 — 可直接复制的实施 Prompt |
| [FINANCE-F-NEXT-10-REPORT.md](engineering/FINANCE-F-NEXT-10-REPORT.md) | Finance F-next-10 — Dashboard & Cashflow Read-only Integration |
| [FINANCE-F-NEXT-2-REPORT.md](engineering/FINANCE-F-NEXT-2-REPORT.md) | Finance F-next-2 — 独立导航与只读账户管理 |
| [FINANCE-F-NEXT-2-STARTUP-RECOVERY-REPORT.md](engineering/FINANCE-F-NEXT-2-STARTUP-RECOVERY-REPORT.md) | F-next-2 Development-only PostgreSQL 启动恢复 |
| [FINANCE-F-NEXT-3-REPORT.md](engineering/FINANCE-F-NEXT-3-REPORT.md) | Finance F-next-3 — 只读交易流水 |
| [FINANCE-F-NEXT-4-REPORT.md](engineering/FINANCE-F-NEXT-4-REPORT.md) | Finance F-next-4 — Budgets 只读入口与来源能力边界 |
| [FINANCE-F-NEXT-5-REPORT.md](engineering/FINANCE-F-NEXT-5-REPORT.md) | Finance F-next-5 — Assets 只读集成 |
| [FINANCE-F-NEXT-6-REPORT.md](engineering/FINANCE-F-NEXT-6-REPORT.md) | Finance F-next-6 — Investment Positions 只读集成 |
| [FINANCE-F-NEXT-7-REPORT.md](engineering/FINANCE-F-NEXT-7-REPORT.md) | Finance F-next-7 — Portfolio Summary 投资组合统计 |
| [FINANCE-F-NEXT-8-REPORT.md](engineering/FINANCE-F-NEXT-8-REPORT.md) | Finance F-next-8 — Investment History & Immutable Audit Timeline |
| [FINANCE-F-NEXT-9-REPORT.md](engineering/FINANCE-F-NEXT-9-REPORT.md) | Finance F-next-9 — Import History & Confirmation Receipts |
| [FINANCE-INTEGRATION-HANDOFF.md](engineering/FINANCE-INTEGRATION-HANDOFF.md) | Personal Finance OS → Workspace 技术交接 |
| [FINANCE-MARKET-DATA-M1-REPORT.md](engineering/FINANCE-MARKET-DATA-M1-REPORT.md) | Finance Market Data M1 |
| [FINANCE-MARKET-DATA-M11-REPORT.md](engineering/FINANCE-MARKET-DATA-M11-REPORT.md) | Finance Market Data M1.1 — 可用性与信息清晰度 |
| [FINANCE-MARKET-DATA-M12-CLOSING.md](engineering/FINANCE-MARKET-DATA-M12-CLOSING.md) | Market Data M1.2 开发收尾 |
| [FINANCE-MARKET-DATA-M12-FX-PRECISION-REPORT.md](engineering/FINANCE-MARKET-DATA-M12-FX-PRECISION-REPORT.md) | M1.2 — FX 高精度显示兼容修复 |
| [FINANCE-MARKET-DATA-M12-INVALID-DATA-REPORT.md](engineering/FINANCE-MARKET-DATA-M12-INVALID-DATA-REPORT.md) | M1.2 — INVALID_DATA 最小修复与安全诊断 |
| [FINANCE-MARKET-DATA-M12-REPORT.md](engineering/FINANCE-MARKET-DATA-M12-REPORT.md) | Finance Market Data M1.2 — 免费个人日终行情 |
| [FINANCE-MARKET-DATA-M12-RUNTIME-IDENTITY-REPORT.md](engineering/FINANCE-MARKET-DATA-M12-RUNTIME-IDENTITY-REPORT.md) | M1.2 — 运行版本排查与启动冲突 |
| [FINANCE-REMAINING-CAPABILITIES.md](engineering/FINANCE-REMAINING-CAPABILITIES.md) | Finance 剩余能力与统一 AI 架构交接 |
| [FINANCE-VISUAL-ANALYTICS-D2-REPORT.md](engineering/FINANCE-VISUAL-ANALYTICS-D2-REPORT.md) | Finance Visual Analytics D2 — Interactive Charts & Annual Financial Review |

<a id="windows"></a>

## 工程报告：Workspace 存储与 Windows

| 文档 | 原始标题 |
| --- | --- |
| [DESKTOP-RUNTIME-DISTRIBUTION-REPORT.md](engineering/DESKTOP-RUNTIME-DISTRIBUTION-REPORT.md) | Windows Desktop Runtime Validation & Distribution Foundation |
| [DESKTOP-SIMPLIFICATION-REPORT.md](engineering/DESKTOP-SIMPLIFICATION-REPORT.md) | PERSONAL AI WORKSPACE DESKTOP SIMPLIFICATION REPORT |
| [F2.3-B1-UNIFIED-POSTGRESQL-MIGRATION-REPORT.md](engineering/F2.3-B1-UNIFIED-POSTGRESQL-MIGRATION-REPORT.md) | F2.3-B1 UNIFIED POSTGRESQL MIGRATION REPORT |
| [F2.3-B2-KNOWLEDGE-POSTGRESQL-MIGRATION-REPORT.md](engineering/F2.3-B2-KNOWLEDGE-POSTGRESQL-MIGRATION-REPORT.md) | F2.3-B2 KNOWLEDGE POSTGRESQL MIGRATION REPORT |
| [F2.3-B3-UNIFIED-BACKUP-RESTORE-REPORT.md](engineering/F2.3-B3-UNIFIED-BACKUP-RESTORE-REPORT.md) | F2.3-B3 UNIFIED BACKUP & RESTORE REPORT |
| [F2.3-B3E-PORTABLE-ENCRYPTED-BACKUP-REPORT.md](engineering/F2.3-B3E-PORTABLE-ENCRYPTED-BACKUP-REPORT.md) | F2.3-B3E PORTABLE ENCRYPTED BACKUP REPORT |
| [F2.3-B3F-SECURE-BACKUP-PIPELINE-REPORT.md](engineering/F2.3-B3F-SECURE-BACKUP-PIPELINE-REPORT.md) | F2.3-B3F SECURE BACKUP PIPELINE REPORT |
| [F2.3-B4A-DESKTOP-POSTGRESQL-INTEGRATION-REPORT.md](engineering/F2.3-B4A-DESKTOP-POSTGRESQL-INTEGRATION-REPORT.md) | F2.3-B4A DESKTOP POSTGRESQL INTEGRATION REPORT |
| [F2.3-B4A2-TRUSTED-WINDOWS-CONFIGURATION-REPORT.md](engineering/F2.3-B4A2-TRUSTED-WINDOWS-CONFIGURATION-REPORT.md) | F2.3-B4A2 TRUSTED WINDOWS CONFIGURATION REPORT |
| [F2.3-B4A4-REAL-WINDOWS-CONFIGURATION-REPORT.md](engineering/F2.3-B4A4-REAL-WINDOWS-CONFIGURATION-REPORT.md) | F2.3-B4A4 REAL WINDOWS CONFIGURATION REPORT |
| [F2.3-B4A5-TRUSTED-DEVELOPMENT-RUNTIME-REPORT.md](engineering/F2.3-B4A5-TRUSTED-DEVELOPMENT-RUNTIME-REPORT.md) | F2.3-B4A5 TRUSTED DEVELOPMENT RUNTIME REPORT |
| [POSTGRESQL-DESKTOP-CLOSING.md](engineering/POSTGRESQL-DESKTOP-CLOSING.md) | PostgreSQL Desktop Integration — Development Closing |
| [POSTGRESQL-DESKTOP-INTEGRATION-REPORT.md](engineering/POSTGRESQL-DESKTOP-INTEGRATION-REPORT.md) | PostgreSQL Desktop Integration & Real Windows E2E |
| [SAME-USER-RUNTIME-COMPATIBILITY-REPORT.md](engineering/SAME-USER-RUNTIME-COMPATIBILITY-REPORT.md) | Same-User Windows Runtime Compatibility |
| [SQLITE-LONG-PATH-RECOVERY-REPORT.md](engineering/SQLITE-LONG-PATH-RECOVERY-REPORT.md) | SQLite Long-Path Recovery Reliability |
| [WEBVIEW2-REACT-STARTUP-RECOVERY-REPORT.md](engineering/WEBVIEW2-REACT-STARTUP-RECOVERY-REPORT.md) | WebView2 / React Startup Recovery |
| [WINDOWS-NATIVE-SECURITY-MODERNIZATION-REPORT.md](engineering/WINDOWS-NATIVE-SECURITY-MODERNIZATION-REPORT.md) | Windows Native Security Modernization |

<a id="models"></a>

## 工程报告：模型、Native Gate 与 Vision

| 文档 | 原始标题 |
| --- | --- |
| [MMF-1-IMPLEMENTATION-CANDIDATE.md](engineering/MMF-1-IMPLEMENTATION-CANDIDATE.md) | MMF-1 Runtime Foundation — Implementation Candidate |
| [MMF-2B-IMPLEMENTATION-CANDIDATE.md](engineering/MMF-2B-IMPLEMENTATION-CANDIDATE.md) | MMF-2B Native Model Management — SOURCE REVIEW APPROVED — GO |
| [MMF-3-BOOTSTRAP-INTEGRATION-GATE.md](engineering/MMF-3-BOOTSTRAP-INTEGRATION-GATE.md) | MMF-3 — Bootstrap Integration Architecture Gate |
| [MMF-3-CLOSING-RECORD.md](engineering/MMF-3-CLOSING-RECORD.md) | MMF-3 Closing Record — CLOSED — GO（Development-only） |
| [MMF-3-INTEGRATION-CLOSURE.md](engineering/MMF-3-INTEGRATION-CLOSURE.md) | MMF-3 Integration Closure — 集中集成候选 |
| [MMF-3-JAVA-ACL-CLOSURE-CANDIDATE.md](engineering/MMF-3-JAVA-ACL-CLOSURE-CANDIDATE.md) | MMF-3 Java Windows Private ACL Closure Candidate |
| [MMF-3-MODEL-IDENTITY-REPAIR-CANDIDATE.md](engineering/MMF-3-MODEL-IDENTITY-REPAIR-CANDIDATE.md) | MMF-3 — 有限授权模型身份修复候选 |
| [MMF-3-OLLAMA-0401-COMPATIBILITY.md](engineering/MMF-3-OLLAMA-0401-COMPATIBILITY.md) | MMF-3 — Ollama 0.40.1 compatibility |
| [MMF-3-PROFILE-ACCEPTANCE-CANDIDATE.md](engineering/MMF-3-PROFILE-ACCEPTANCE-CANDIDATE.md) | MMF-3 — Profile-Root Acceptance Candidate |
| [MMF-3-PROFILE-SOURCE-REVIEW-P1-CLOSURE.md](engineering/MMF-3-PROFILE-SOURCE-REVIEW-P1-CLOSURE.md) | MMF-3 Profile Source Review — P1 Closure |
| [MMF-3-RUNTIME-ACL-ROOT-CAUSE.md](engineering/MMF-3-RUNTIME-ACL-ROOT-CAUSE.md) | MMF-3 Runtime Private ACL Root Cause Investigation |
| [MMF-3-TRUSTED-BOOTSTRAP-CANDIDATE.md](engineering/MMF-3-TRUSTED-BOOTSTRAP-CANDIDATE.md) | MMF-3 — Trusted Native Bootstrap Candidate |
| [MMF-3-TRUSTED-NATIVE-LOADER-DECISION.md](engineering/MMF-3-TRUSTED-NATIVE-LOADER-DECISION.md) | MMF-3 — Trusted JNA Native Loading Architecture Decision |
| [MMF-3-WINDOWS-ACL-FIX-CANDIDATE.md](engineering/MMF-3-WINDOWS-ACL-FIX-CANDIDATE.md) | MMF-3 Windows Private ACL Corrective Candidate |
| [MMF-3-WINDOWS-ACL-SEMANTICS-DECISION.md](engineering/MMF-3-WINDOWS-ACL-SEMANTICS-DECISION.md) | MMF-3 — Windows ACL & Handle Semantics Decision Gate |
| [V1-VISION-FOUNDATION-SCOPE-CANDIDATE.md](engineering/V1-VISION-FOUNDATION-SCOPE-CANDIDATE.md) | V1 — Multimodal / Vision Foundation Scope Candidate |
| [V1-VISION-GATE-REMEDIATION-REPORT.md](engineering/V1-VISION-GATE-REMEDIATION-REPORT.md) | V1 Vision Gate Remediation |
| [V1-VISION-IMPLEMENTATION-CANDIDATE.md](engineering/V1-VISION-IMPLEMENTATION-CANDIDATE.md) | V1 Vision Implementation Candidate |
| [V1-VISION-MINIMAL-POSITIVE-POLICY-IMPLEMENTATION.md](engineering/V1-VISION-MINIMAL-POSITIVE-POLICY-IMPLEMENTATION.md) | V1 Vision — Minimal Positive Policy Implementation Candidate |
| [V1-VISION-NATIVE-PROVENANCE-POLICY-REVIEW.md](engineering/V1-VISION-NATIVE-PROVENANCE-POLICY-REVIEW.md) | V1 Vision — Native Provenance and Minimal Policy Review |
| [V1-VISION-P1-REMEDIATION-CANDIDATE.md](engineering/V1-VISION-P1-REMEDIATION-CANDIDATE.md) | V1 P1 Remediation Candidate |
| [V1-VISION-SHARED-IDENTITY-TOKEN-REVIEW.md](engineering/V1-VISION-SHARED-IDENTITY-TOKEN-REVIEW.md) | V1 Vision — Shared Identity and Token Budget Review |
| [V1-WIC-P1-B-FIX-CANDIDATE.md](engineering/V1-WIC-P1-B-FIX-CANDIDATE.md) | V1 WIC P1-B FIX CANDIDATE |

<a id="engineering"></a>

## 工程报告：其他交付与维护

| 文档 | 原始标题 |
| --- | --- |
| [DOCUMENTATION-MODERNIZATION-REPORT.md](engineering/DOCUMENTATION-MODERNIZATION-REPORT.md) | 文档与 GitHub 首页现代化报告 |
| [GITHUB-OPEN-SOURCE-EVALUATION.md](engineering/GITHUB-OPEN-SOURCE-EVALUATION.md) | PERSONAL AI WORKSPACE GITHUB OPEN SOURCE EVALUATION |
| [T0-TEST-SUITE-SLIMMING.md](engineering/T0-TEST-SUITE-SLIMMING.md) | T0 — Test Suite Slimming |
| [UI-MATURITY-REPORT.md](engineering/UI-MATURITY-REPORT.md) | Workspace 产品成熟度 / UI-UX 升级 |
| [W1A-1-IMPLEMENTATION-CANDIDATE.md](engineering/W1A-1-IMPLEMENTATION-CANDIDATE.md) | W1A-1 Runtime Secure Fetch implementation candidate |
| [W1A-2-IMPLEMENTATION-CANDIDATE.md](engineering/W1A-2-IMPLEMENTATION-CANDIDATE.md) | W1A-2 Native Approval + Typed Bridge implementation candidate |
| [WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md](engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md) | Workspace Post F-next-10 Engineering Cleanup |
| [X1-CROSS-APP-REFERENCE.md](engineering/X1-CROSS-APP-REFERENCE.md) | X1 跨应用助手：GitHub 定点借鉴记录 |
| [X1-CROSS-APP-REPORT.md](engineering/X1-CROSS-APP-REPORT.md) | X1 Cross-App Assistant |

<a id="milestones"></a>

## 里程碑 Closing

| 文档 | 原始标题 |
| --- | --- |
| [K1-CLOSING-REPORT.md](milestones/K1-CLOSING-REPORT.md) | K1 — Deterministic Knowledge Foundation Closing Candidate |
| [K2-CLOSING-REPORT.md](milestones/K2-CLOSING-REPORT.md) | K2 — Deterministic Lexical Retrieval — Closing Report |
| [K3-CLOSING-REPORT.md](milestones/K3-CLOSING-REPORT.md) | K3 — Grounded Knowledge Answer + Citations — Closing Report |
| [M1.5-CLOSING-REPORT.md](milestones/M1.5-CLOSING-REPORT.md) | M1.5 CLOSING REPORT |
| [M2-CLOSING-REPORT.md](milestones/M2-CLOSING-REPORT.md) | M2 FINAL CROSS-REPO CLOSING REPORT |
| [M2A-CLOSING-REPORT.md](milestones/M2A-CLOSING-REPORT.md) | M2A CLOSING REPORT — Browser Runtime Access Foundation |
| [M2B-1-CLOSING-REPORT.md](milestones/M2B-1-CLOSING-REPORT.md) | M2B-1 CLOSING REPORT |
| [M2B-2A-CLOSING-REPORT.md](milestones/M2B-2A-CLOSING-REPORT.md) | M2B-2A CLOSING REPORT |
| [M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md](milestones/M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md) | M2B-2B-R1 CLOSING REPORT |
| [M3-CLOSING-REPORT.md](milestones/M3-CLOSING-REPORT.md) | M3 — User-Controlled Memory Foundation — Final Closing |
| [M3A-MEMORY-STORAGE-REPORT.md](milestones/M3A-MEMORY-STORAGE-REPORT.md) | M3A — MEMORY STORAGE FOUNDATION REPORT |
| [M3B-DESKTOP-MEMORY-MANAGEMENT-REPORT.md](milestones/M3B-DESKTOP-MEMORY-MANAGEMENT-REPORT.md) | M3B — DESKTOP MEMORY MANAGEMENT REPORT |
| [M3C-1-EXPLICIT-MEMORY-ASK-REPORT.md](milestones/M3C-1-EXPLICIT-MEMORY-ASK-REPORT.md) | M3C-1 — EXPLICIT MEMORY ASK REPORT |
| [M3C-2-MEMORY-EXPORT-RESTORE-REPORT.md](milestones/M3C-2-MEMORY-EXPORT-RESTORE-REPORT.md) | M3C-2 — MEMORY EXPORT / RESTORE REPORT |
| [M4A-CLOSING-REPORT.md](milestones/M4A-CLOSING-REPORT.md) | M4A CLOSING REPORT |
| [M4B-CLOSING-REPORT.md](milestones/M4B-CLOSING-REPORT.md) | M4B CLOSING REPORT |
| [M4C-CLOSING-REPORT.md](milestones/M4C-CLOSING-REPORT.md) | M4C CLOSING REPORT |
| [M5A-CLOSING-REPORT.md](milestones/M5A-CLOSING-REPORT.md) | M5A CLOSING REPORT |
| [M5B-CLOSING-REPORT.md](milestones/M5B-CLOSING-REPORT.md) | M5B CLOSING REPORT |
| [M5C-CLOSING-REPORT.md](milestones/M5C-CLOSING-REPORT.md) | M5C CLOSING REPORT |
| [M5D-CLOSING-REPORT.md](milestones/M5D-CLOSING-REPORT.md) | M5D CLOSING REPORT |
| [M5E-CLOSING-REPORT.md](milestones/M5E-CLOSING-REPORT.md) | M5E CLOSING REPORT |
| [W1A-CLOSING-REPORT.md](milestones/W1A-CLOSING-REPORT.md) | W1A — Secure Fetch Foundation + Native Approval — Closing Report |

<a id="design"></a>

## 设计与调研

| 文档 | 原始标题 |
| --- | --- |
| [FINANCE-INTERACTION-REFERENCE.md](design/FINANCE-INTERACTION-REFERENCE.md) | Finance D1 交互源码参考 |
| [FINANCE-MARKET-DATA-M12-REFERENCE.md](design/FINANCE-MARKET-DATA-M12-REFERENCE.md) | Finance Market Data M1.2 — Massive Basic |
| [FINANCE-MARKET-DATA-REFERENCE.md](design/FINANCE-MARKET-DATA-REFERENCE.md) | Finance Market Data M1 — 选型与来源 |
| [FINANCE-VISUAL-ANALYTICS-REFERENCE.md](design/FINANCE-VISUAL-ANALYTICS-REFERENCE.md) | Finance Visual Analytics D2 · 图表源码参考 |
| [WORKSPACE-UI-DESIGN-DIRECTION.md](design/WORKSPACE-UI-DESIGN-DIRECTION.md) | Workspace 统一 UI 设计方向 |
| [WORKSPACE-UI-IMPLEMENTATION-PLAN.md](design/WORKSPACE-UI-IMPLEMENTATION-PLAN.md) | Workspace UI 实施计划 |
| [WORKSPACE-UI-OPEN-SOURCE-RESEARCH.md](design/WORKSPACE-UI-OPEN-SOURCE-RESEARCH.md) | Workspace UI 开源研究 |
| [WORKSPACE-UI-PHASE-1-PROMPT.md](design/WORKSPACE-UI-PHASE-1-PROMPT.md) | Workspace UI Phase 1 — 可直接执行的开发 Prompt |
| [WORKSPACE-UI-PHASE-1-REPORT.md](design/WORKSPACE-UI-PHASE-1-REPORT.md) | Workspace UI Phase 1 — Shell & Navigation Foundation |
| [WORKSPACE-UI-VISUAL-REDESIGN-REPORT.md](design/WORKSPACE-UI-VISUAL-REDESIGN-REPORT.md) | Workspace UI Visual Redesign |

<a id="audits"></a>

## 审计与测试体系历史

| 文档 | 原始标题 |
| --- | --- |
| [POST-M2-TEST-SIMPLIFICATION-CLOSING.md](audits/POST-M2-TEST-SIMPLIFICATION-CLOSING.md) | POST-M2 TEST SUITE SIMPLIFICATION — FINAL CLOSING REPORT |
| [POST-M2-TEST-SUITE-AUDIT.md](audits/POST-M2-TEST-SUITE-AUDIT.md) | POST-M2 TEST SUITE AUDIT REPORT |

<a id="history"></a>

## 历史入口快照

| 文档 | 原始标题 |
| --- | --- |
| [README.md](history/2026-10-28/README.md) | Personal AI Workspace |
| [STATUS.md](history/2026-10-28/STATUS.md) | Personal AI Workspace — Current Status |
| [V1-ROADMAP.md](history/2026-10-28/V1-ROADMAP.md) | Personal AI Workspace — V1 Roadmap |
| [architecture.md](history/2026-10-28/architecture.md) | Personal AI Workspace — Current Architecture |
