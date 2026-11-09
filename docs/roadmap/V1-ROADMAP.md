# V1 Roadmap

2026-11-09 [知识问答笔记导出](../engineering/KNOWLEDGE-ANSWER-EXPORT-REPORT.md)补齐“原问答 → 核对引用 → 保存可携带笔记”。评估 Markdig／Pandoc 后复用现有历史、原文预览及原生文件接口，不增加转换器／模型服务。关键前端、Native 和实际文件写入检查通过；下一缺口是同包 Windows 保存对话框与应用使用验收，单条明文笔记不替代个人恢复或通用质量评估。

2026-11-09 [版本兼容与未见题复核](../engineering/KNOWLEDGE-ANSWER-FOLLOWUP-REPORT.md)确认已配套 v3 可正常受理、生成和在模型关闭后恢复历史，三道新题一次通过；只复用手工探针，不再修改已修复的产品代码或扩充测试框架。原 RFC 失败保留，无新旧版本优劣结论。该范围验证已收口，后续围绕实际用户需求选择产品改进，不反复生成同类样本。

2026-11-09 [知识回答保真与结构化输出](../engineering/KNOWLEDGE-ANSWER-FIDELITY-REPORT.md)已完成固定 Schema、通用语言／字面／条件提示和新旧版本兼容。独立新题旧版 5/5、新版 4 题满足且 1 题技术中断，失败保留、未知提交不重放，停机历史一致；不能据此宣称相对质量提升。下一步按实际反馈选择有限独立样本或产品缺口，引用合法不替代事实支持，结构化输出不替代语义校验；不默认扩大评测／恢复设施。个人恢复、PG 完整组合与正式发布边界不变。

2026-11-09 [指定资料的完整桌面与真实模型验证](../engineering/KNOWLEDGE-DOCUMENT-SCOPE-DESKTOP-REPORT.md)补齐范围选择、无匹配不回退、草稿保持、精确引用及关闭模型后的独立进程历史恢复。两题中一题完整满足，另一题仅答 `invalid`，没有满足精确写法／语言要求；质量失败保留，不宣称全通过。只复用已有验收工具，未改变产品 schema／服务。后续围绕回答遵循、证据支持与实际产品需求排序，不默认扩展底层测试；PG 完整组合、个人恢复与正式发布仍未完成。

2026-11-09 核心能力增量：[指定资料范围检索与问答](../engineering/KNOWLEDGE-DOCUMENT-SCOPE-REPORT.md)完成“选定当前文档版本 → 检索／提问 → 精确引用 → 历史保留范围”。复用原有 Store、索引与授权，不引入服务或模型依赖；只补范围所需的历史元数据及旧格式兼容。SQLite／HTTP、独立 PG、Native 和前端核心验证通过，完整实际桌面及真实模型质量未随本轮验收。后续按使用反馈和产品收益推进，不默认扩张 PDF／OCR、恢复或测试框架；个人恢复仍未开放，仅本地提交。

2026-11-09 [跨会话查找隔离真实桌面与 PG 验证](../engineering/CONVERSATION-SEARCH-DESKTOP-REPORT.md)完成：真实 WebView 的字面查找、分页定位、归档筛选及草稿保护通过，窗口重开后再次查找通过；独立 PostgreSQL Store 综合场景通过。仅复用／扩展现有验收工具，无产品变更、模型或个人资料操作，自有资源已退出。指定核心验证已收口；后续按实际反馈与最终产品收益排序，不继续扩展底层验收框架。完整 AssistantApp、PG 桌面组合和容量性能仍未验收，个人恢复仍未开放。

2026-11-09 优先级调整与交付：[跨会话原文查找](../engineering/CONVERSATION-MESSAGE-SEARCH-REPORT.md)补齐“找到旧消息 → 摘要核对 → 回到原轮次”的日常使用链路。恢复的来源授权／原生登记／激活尚不能通过最小 UI 接线完成，因此本轮保留边界，停止继续铺开隔离备份设施。复用现有存储和权限，无新增服务或模型需求；Java 3/3、桌面 31/31、前端 16/16 及构建通过，完整桌面／PG／最大容量未验收。后续以实际使用与产品闭环收益排序，不默认继续归档框架；个人恢复仍未开放，仅本地提交。

2026-11-09 [合成加密恢复的隔离桌面验证](../engineering/KNOWLEDGE-ENCRYPTED-RECOVERY-DESKTOP-REPORT.md)：实际历史页、旧版本精确引用和缺失来源提示在两个独立 JVM 中均通过；没有模型调用或任务重放。复用现有测试宿主，数据限定在新 Profile 的 evidence，普通 data 为空。正式来源登记／候选激活、完整 AssistantApp 恢复与用户入口仍待完成；下一步围绕该产品链路收敛，不扩张归档框架。六份未跟踪文档未变，仅本地提交；下方较早记录保留时点范围。

2026-11-09 [来源与问答历史的合成加密恢复](../engineering/SYNTHETIC-KNOWLEDGE-HISTORY-ENCRYPTION-REPORT.md)：已有联合快照复用现有 OpenPGP 流完成直接加密、完整认证及新候选恢复，隔离测试 16/16。个人入口、原生来源登记／候选激活、完整新 JVM／桌面历史阅读及 PG 仍未完成；下一步优先连接既有产品恢复流程，不继续扩张归档框架。仅本地开发，六份未跟踪文档保持原样。

2026-11-09 重启闭环：[完整应用与真实知识回答](../engineering/WEB-KNOWLEDGE-RESTART-AND-MODEL-REPORT.md)完成三次独立 AssistantApp／JVM 启动，SQLite 资料、源版本与引用目标稳定；隔离 Qwen3.5 4B 两个英文公开样本回答正确且来源可核对。知识回答历史仍不持久保存，正式分发、PostgreSQL 和一般模型质量未验收，W1 整体未关闭。另修复构建来源清单对中文文件名的处理；保留用户文件和本地 Git 回滚点。

2026-11-09 验证推进：[网页资料真实桌面闭环](../engineering/WEB-KNOWLEDGE-LIVE-ACCEPTANCE.md)已通过两个公开样本的实际 WPF／WebView／原生确认／生产 Fetch／SQLite 获取、保存、检索与窗口重开，模型调用为 0。完整 AssistantApp 启动、JVM 重启、正式桌面包、真实模型质量及 PostgreSQL 仍未随该项验收；W1 整体未关闭。默认无公网核心回归 2/2 通过，用户文件和既有服务未动。

2026-11-09 核心闭环增量：[网页摘录进入 Knowledge](../engineering/WEB-KNOWLEDGE-CAPTURE-REPORT.md)完成源码与必要合成检查。公共网址原生确认 → 有界预览 → 明确保存 → 本地重开／检索／知识回答引用／独立备份恢复；复用现有 jsoup、安全 Fetch 和 Knowledge，未知结果只读对账。不是 Search／Web Answer 或自动联网；真实网站、模型、完整桌面包／WebView、PostgreSQL 待后续验收，既有临时结果期限与会话容量保留。仅本地提交，仍为 Development-only。

2026-11-09 最新增量：[长中文 Knowledge 有界原文摘录](../engineering/KNOWLEDGE-BOUNDED-EXCERPTS-REPORT.md)完成实现与合成流程验证，首个长命中可按句子和相邻上下文适配既有预算，引用保持准确版本与范围。真实模型质量、真实桌面使用及 PostgreSQL 全流程尚未随此增量复验；本地提交未推送，仍为 Development-only。

2026-11-09 接续：用户已恢复开发。11 月 7 日[显式记忆系统](../engineering/MEMORY-SYSTEM-COMPLETION-REPORT.md)完成必要开发验收；本阶段先完成[知识回答授权生命周期修复](../engineering/KNOWLEDGE-ANSWER-LIFECYCLE-REPORT.md)，消除已确认终态任务造成的会话累计上限。日常启动／恢复、长资料 Knowledge、真实个人备份和正式分发仍按各自范围推进；未因这一步增加模型质量、真实桌面或生产发布结论。下方表格保留早期阶段信息，具体能力以较新的专项报告为准。

2026-10-28，历史源码核验基线 `12dce0765dcdb89b75fd605c2b4fc7bf5db00617`。文档现代化与 Closing 随 main 发布；最新主线提交与固定核验基线的区别见 [当前状态](../STATUS.md)。

[当前状态](../STATUS.md) 定义已核对的产品事实；本页区分已有源码、待验收事项与未来规划。**Development-only，尚未 Production GO。** 规划不构成实施、外部调用、真实数据迁移或发布授权。

[文档索引](../README.md) · [架构](../architecture/current-architecture.md) · [完整历史路线图](../history/2026-10-28/V1-ROADMAP.md)

## 已交付源码与历史阶段

| 阶段 | 已交付范围 | 当前解释与主要证据 |
| --- | --- | --- |
| M0–M2 | 本地任务 Runtime、Windows 文本操作、Browser 配对与受限翻译 | [M2 closing](../milestones/M2-CLOSING-REPORT.md)；Browser Translate-only |
| M3–M4 | 手动 Memory、显式 context、持久多轮 Conversation、逻辑备份／恢复 | [M3](../milestones/M3-CLOSING-REPORT.md)、[ADR-007](../ADR/ADR-007-logical-workspace-backup-restore.md) |
| 日常会话定位 | 指定轮次直接跳转、回到最新，保留当前草稿／记忆选择 | [2026-11-07 交付](../engineering/CONVERSATION-TURN-NAVIGATION-REPORT.md)；源码与前端构建完成，真实 Windows 包未验收 |
| 记忆检索预览 | 单条按需只读正文与字面搜索高亮，保留编辑草稿 | [2026-11-07 交付](../engineering/MEMORY-READ-PREVIEW-REPORT.md)；无自动记忆，真实 Windows 包未验收 |
| 会话记忆引用 | 按需查看本轮引用，与当前记录核对版本，不将新版正文当作旧版 | [2026-11-07 交付](../engineering/CONVERSATION-MEMORY-REFERENCE-PREVIEW-REPORT.md)；无历史正文重建，真实 Windows 包未验收 |
| 对话记忆候选 | 用户发起本地提取、摘录对照、可选正文查重、编辑／丢弃／采用后明确保存 | [候选生成](../engineering/MEMORY-CANDIDATE-GENERATION-REPORT.md)、[查重范围](../engineering/MEMORY-CANDIDATE-DUPLICATE-REVIEW-REPORT.md)；合成流程验证，真实模型质量和 Windows 流程未验收，无自动写入 |
| M5 | React / WebView2 Main Workspace、WPF 原生边界与资产构建 | [M5E closing](../milestones/M5E-CLOSING-REPORT.md)；历史阶段通过不等于当前正式发布 |
| K1–K3 | Knowledge 导入／管理、词法检索、显式问答和引用 | [K1](../milestones/K1-CLOSING-REPORT.md)、[K2](../milestones/K2-CLOSING-REPORT.md)、[K3](../milestones/K3-CLOSING-REPORT.md)；不再标记 NOT STARTED |
| T0 | 测试体系收敛、既有 WorkspaceSanity 验收入口 | [T0 报告](../engineering/T0-TEST-SUITE-SLIMMING.md)；不因文档更新重跑历史全套 |
| W1A | 原生逐次确认、有界公共 HTTPS Fetch | [W1A](../milestones/W1A-CLOSING-REPORT.md)；W1 整体未关闭 |
| MMF | 单一 Active Model、来源／能力准入与 uncertainty 保护 | [MMF-3](../engineering/MMF-3-CLOSING-RECORD.md)；仅历史 Development-only closing |
| PostgreSQL Desktop | 原生配置、Profile 记忆／切换、Workspace 三 Store 与独立离线备份链 | [Desktop 收尾](../engineering/POSTGRESQL-DESKTOP-CLOSING.md)；剩余人工关卡保留 |
| Finance F-next-1–10 | 从冻结来源迁入受控只读切片、总览与统计 | [能力盘点](../engineering/FINANCE-REMAINING-CAPABILITIES.md)、[F-next-10](../engineering/FINANCE-F-NEXT-10-REPORT.md)、[人工确认归属](../engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md) |
| D2 / M1–M1.2 | 财务图表、行情显示边界、Massive 参考日线 | [D2](../engineering/FINANCE-VISUAL-ANALYTICS-D2-REPORT.md)、[M1.2 closing](../engineering/FINANCE-MARKET-DATA-M12-CLOSING.md)；D2 PARTIAL 仍保留 |
| X1 / UI maturity | 跨应用快速助手源码、中文导航与资料／设置层级 | [X1](../engineering/X1-CROSS-APP-REPORT.md)、[UI maturity](../engineering/UI-MATURITY-REPORT.md)；X1 Windows Acceptance PARTIAL |
| Git 整合与文档 | 54 个开发提交进入 main、旧开发分支清理；新版 README、文档体系与 Closing fast-forward 发布 | 历史源码核验基线为 `12dce07`；当前 main 另包含文档提交，开发分支保留 |

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
