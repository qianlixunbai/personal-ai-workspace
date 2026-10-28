# V1 Roadmap

2026-10-28，源码基线 `main @ 12dce0765dcdb89b75fd605c2b4fc7bf5db00617`。

[当前状态](../STATUS.md) 定义已核对的产品事实；本页区分已有源码、待验收事项与未来规划。**Development-only，尚未 Production GO。** 规划不构成实施、外部调用、真实数据迁移或发布授权。

[文档索引](../README.md) · [架构](../architecture/current-architecture.md) · [完整历史路线图](../history/2026-10-28/V1-ROADMAP.md)

## 已交付源码与历史阶段

| 阶段 | 已交付范围 | 当前解释与主要证据 |
| --- | --- | --- |
| M0–M2 | 本地任务 Runtime、Windows 文本操作、Browser 配对与受限翻译 | [M2 closing](../milestones/M2-CLOSING-REPORT.md)；Browser Translate-only |
| M3–M4 | 手动 Memory、显式 context、持久多轮 Conversation、逻辑备份／恢复 | [M3](../milestones/M3-CLOSING-REPORT.md)、[ADR-007](../ADR/ADR-007-logical-workspace-backup-restore.md) |
| M5 | React / WebView2 Main Workspace、WPF 原生边界与资产构建 | [M5E closing](../milestones/M5E-CLOSING-REPORT.md)；历史阶段通过不等于当前正式发布 |
| K1–K3 | Knowledge 导入／管理、词法检索、显式问答和引用 | [K1](../milestones/K1-CLOSING-REPORT.md)、[K2](../milestones/K2-CLOSING-REPORT.md)、[K3](../milestones/K3-CLOSING-REPORT.md)；不再标记 NOT STARTED |
| T0 | 测试体系收敛、既有 WorkspaceSanity 验收入口 | [T0 报告](../engineering/T0-TEST-SUITE-SLIMMING.md)；不因文档更新重跑历史全套 |
| W1A | 原生逐次确认、有界公共 HTTPS Fetch | [W1A](../milestones/W1A-CLOSING-REPORT.md)；W1 整体未关闭 |
| MMF | 单一 Active Model、来源／能力准入与 uncertainty 保护 | [MMF-3](../engineering/MMF-3-CLOSING-RECORD.md)；仅历史 Development-only closing |
| PostgreSQL Desktop | 原生配置、Profile 记忆／切换、Workspace 三 Store 与独立离线备份链 | [Desktop 收尾](../engineering/POSTGRESQL-DESKTOP-CLOSING.md)；剩余人工关卡保留 |
| Finance F-next-1–10 | 从冻结来源迁入受控只读切片、总览与统计 | [能力盘点](../engineering/FINANCE-REMAINING-CAPABILITIES.md)、[F-next-10](../engineering/FINANCE-F-NEXT-10-REPORT.md)、[人工确认归属](../engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md) |
| D2 / M1–M1.2 | 财务图表、行情显示边界、Massive 参考日线 | [D2](../engineering/FINANCE-VISUAL-ANALYTICS-D2-REPORT.md)、[M1.2 closing](../engineering/FINANCE-MARKET-DATA-M12-CLOSING.md)；D2 PARTIAL 仍保留 |
| X1 / UI maturity | 跨应用快速助手源码、中文导航与资料／设置层级 | [X1](../engineering/X1-CROSS-APP-REPORT.md)、[UI maturity](../engineering/UI-MATURITY-REPORT.md)；X1 Windows Acceptance PARTIAL |
| Git 整合与文档 | 54 个提交进入 main、旧开发分支清理；本轮整理文档入口 | 源码基线保持 `12dce07`；本轮文档仅交付独立开发分支 |

## 当前未关闭的工作

这些是已有实现的剩余审查／验收，不是本轮正在执行的新功能。

| 工作 | 当前状态 | 下一步所需证据 |
| --- | --- | --- |
| X1 真实 Windows 流程 | **PARTIAL** | 模型完成事实、前台／物理输入、Q/T 选区到结果和清理流程 |
| 模型执行恢复 | **MODEL_EXECUTION_UNCERTAIN** | 外部实际完成事实与原生授权；不自动清 guard |
| V1 Vision Foundation | **BLOCKED** | WIC / held-handle 独立审查、实际 runner 闭包／环境、完整 token 合同；policy 仍为空 |
| PostgreSQL / D2 / 新开发包 | 部分人工关卡仍待确认 | 按同一版本与同一包取证，不跨版本继承完整 PASS |
| W1 整体关闭 | **NOT CLOSED** | 已交付 W1A 之外的能力与独立 Windows／公网验收，按后续授权推进 |

Vision 的最新技术边界见 [修复报告](../engineering/V1-VISION-GATE-REMEDIATION-REPORT.md)。源码候选、安装 metadata、磁盘哈希或无模型 fixture 都不替代真实图片推理准入。

## 未来规划

| 方向 | 当前范围界定 |
| --- | --- |
| W1 后续 | Search / Web Answer、引用整合及 W1 最终验收；当前不能宣传为可用 |
| Finance 后续 | 账本写入、导入创建到确认、预算等需独立领域设计／来源核验；Budgets 当前 UNSUPPORTED |
| 统一金融 AI | 只沿用 Workspace Unified AI Assistant + Shared Runtime；不迁入独立 Finance AI Analyst，不开启自主金融工具 |
| Resource Monitor / System Status | 统一资源／子系统状态属于未来完整能力；现有模型／存储状态不等于完整资源监控 |
| P1 产品整合 | 启动／退出与 degraded UX、隐私与恢复、安装／迁移、发布可信链、最终 Windows 验收 |
| 分发与维护 | 代码签名、安装器、更新、明确许可证与 CI 方案分别处理；当前无正式 Release、CI PASS 或 Production GO |

没有新增时间承诺，也不自动启动这些阶段。自动记忆、云同步、通用 Agent / Tool Calling、向量检索等不因架构图或产品展示而变成已交付范围。

## 更新规则

- 已实现能力先核对 owning code，再链接实际报告；避免用规划批准代替源码或验收。
- 标明源码交付、历史验证、本轮执行、用户人工确认、PENDING / BLOCKED 的区别。
- 当新报告与旧阶段冲突时，更新当前入口，保留旧报告的日期、基线和结果。
- 每次推进分别维护 STATUS、路线图与架构；Git fast-forward、Commit 或 Push 不是验收关闭。
