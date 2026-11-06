# 当前状态

核对日期：2026-10-28（Asia/Shanghai）。**Development-only；尚未 Production GO。**

2026-10-30 补充：[GitHub 展示与 Runtime 核对](engineering/GITHUB-SHOWCASE-RUNTIME-READINESS.md) 对应 `main @ 8df87ab`。UI Polish / P0 / P1 / P2 已合并；历史 uncertainty 已解除并有一次文本问答成功记录。最新开发包重启后的历史状态为 NotValidated；本次只读检查时客户端与 Runtime 未运行，不能声明当前 Ready。下文未另行更新的核验与发布描述仍属 2026-10-28 历史记录。

2026-11-07 状态提示候选补充：本轮从 `main @ fe2c34c89703408b06bd3f4d8ca83c2ca6ded5b9` 开发，Settings 复用现有 Desktop owner、Bridge 与 Runtime 状态入口，区分构建身份、可访问性、存储 backend、模型安装／驻留／验证 Ready，并提供下一步。查询失败不保留旧的可用展示；未改变启动、认证、Profile、模型 guard 或外部服务所有权。当前源码候选不等于已构建并验收的分发包，也不等于历史实机证据包。实际仅执行 Settings / StorageSettings / Bridge 前端聚焦测试、TypeScript 检查及 Desktop 状态探针／Bridge 聚焦测试（含相关编译）；未启动真实客户端、模型或用户数据库，未重做受阻 Profile／Java 实机路径，未打包。当前实际 Runtime、数据库与模型状态未在本轮查询，目标 1 验收仍未完成。详见 [开发指南的状态说明](DEVELOPMENT.md#在-settings-核对本次可用性)。

2026-11-07 Recovery Slice 1：从 `352f01f901fc939bbb73dcef3ac34f0a2850fb98` 在 `codex/recovery-v3-workspace-core` 实施 Workspace-only v3 加密备份／恢复内核，固定 17 表结构、12 表数据、5 表 data 排除及受审查 catalog／归档目录；保留旧 v1/v2 合成格式。v3 仅供内部合成装配，本进程成功备份产生的能力对象与认证密文绑定，不能从口令或 manifest 自报身份获得 SQL 恢复权限；未实现正式来源登记或个人恢复入口。见[当前备份边界](architecture/current-architecture.md#本地安全profile-与备份)。

必要编译及单个真实 PG17.11 综合用例已通过（专属临时实例、非默认端口、合成明文卷例外）。同一用例累计执行 5 次：3 次失败、修正后 2 次通过、0 跳过；前三次失败分别涉及断言、fixture 缺失与索引查询上限，另在 fixture 准备时修正了 catalog 的 OID 派生名称。失败目标保留，未原地重放。最后一次为独立 Review 修复后的聚焦复验，1 用例、0 failures/errors/skips、12.57 秒。另一位 `gpt-6-astra`／xHigh 全程只读审查及复审，4 项 finding（失败空目标重放、遗漏 public 类型、两种合法 Knowledge 终态误拒绝）均已关闭，**本切片代码级 GO**。只扩展现有综合用例并去掉一处重复校验断言，未新增测试矩阵或删减已有安全回归。

未重跑全套测试；旧包仅复用认证拒绝证据，没有重新验收完整 v1/v2 成功恢复。第 2–5 切片、受保护卷、断电故障及真实 Profile／Desktop 恢复激活验收均未完成；仍为 Development-only、X1 PARTIAL、Vision BLOCKED、无已核验 CI PASS、尚未 Production GO。

2026-11-07 Recovery Slice 2a：在同一开发分支新增 DPAPI 恢复意图登记与 Desktop／Java 的候选启动拒绝。计划仅为 `RECOVERY_PLAN_ONLY / PREPARING`，不授予 SQL 来源、停写、目标 provision 或激活权限；没有产品维护入口。只运行两个新增的合成文件安全用例及必要编译，未访问真实 Profile／数据库或启动服务。完整第二切片及后续恢复、发布、激活仍未完成；详见[本轮交接与验证边界](engineering/RECOVERY-SLICE-2A-HANDOFF.md)。

历史源码核验基线：`12dce0765dcdb89b75fd605c2b4fc7bf5db00617`；文档现代化候选：`95ba76a0f244037fd01a38e366da8f524908a418`。两者均为固定历史记录，不代表最新 main HEAD。
当前发布主线为 [main](https://github.com/qianlixunbai/personal-ai-workspace/commits/main/)：新版 README、文档体系与 Closing 修订通过 fast-forward 纳入主线；最新提交以该记录为准。原 54 个开发提交完整保留，原 12 个开发分支已清理，文档开发分支继续保留；本次仅完成文档发布，不升级产品验收。

[文档索引](README.md) · [开发指南](DEVELOPMENT.md) · [架构](architecture/current-architecture.md) · [路线图](roadmap/V1-ROADMAP.md)

## 必须保留的关卡

| 项目 | 当前结论 | 证据与限制 |
| --- | --- | --- |
| 产品定位 | **Development-only** | 受审查的本机开发路径；没有正式发布信任链或安装器 |
| X1 Windows Acceptance | **PARTIAL** | [X1 报告](engineering/X1-CROSS-APP-REPORT.md) 的完整跨应用流程仍未通过；后续单次文本成功不替代物理验收 |
| 模型执行 | **未确认当前 Ready** | [只读核对](engineering/GITHUB-SHOWCASE-RUNTIME-READINESS.md) 中的 uncertainty 解除、重启 NotValidated 与 Runtime 未运行均为历史记录；本轮未查询实机当前状态 |
| Vision | **BLOCKED** | [最新修复报告](engineering/V1-VISION-GATE-REMEDIATION-REPORT.md)：policy 仍为空，真实 runner / payload / 环境与完整 token 合同证据不足 |
| GitHub CI | **尚无已核验的 GitHub CI PASS** | 本轮只读 Actions 列表为空，基线没有跟踪的 workflow；不以本机历史 PASS 替代 |
| 正式产品验收 | **尚未 Production GO** | Git 整合、分支清理、文档更新都不升级验收或构成正式 Release |

## 已实现能力与真实边界

“已实现”表示源码链存在，不代表在当前环境或当前提交重新跑过完整验收。

| 范围 | 已有实现 | 边界与来源 |
| --- | --- | --- |
| Windows Workspace | WPF 生命周期、托盘、受信 WebView2、中文导航、浅／深色、原生快速助手 | [UI 成熟度](engineering/UI-MATURITY-REPORT.md)；混合 DPI、物理跨应用流程等仍待验收 |
| Ask / Summarize / Translate | Runtime-owned 本地文本任务、取消、失败／超时状态、Browser 单条／批量翻译 | [M1.5](milestones/M1.5-CLOSING-REPORT.md)、[M2](milestones/M2-CLOSING-REPORT.md)；Browser 仍 Translate-only |
| Conversations | durable 多轮、逐轮显式 Memory、归档／删除、重开与终态恢复 | [M5C](milestones/M5C-CLOSING-REPORT.md)、[PostgreSQL 收尾](engineering/POSTGRESQL-DESKTOP-CLOSING.md) |
| Memory | 用户手动 CRUD、revision、显式 context、独立逻辑备份 | [ADR-004](ADR/ADR-004-user-controlled-memory-storage.md) 至 [ADR-007](ADR/ADR-007-logical-workspace-backup-restore.md)；无自动记忆 |
| Knowledge | TXT / Markdown 导入、独立源文件与 schema、预览、词法检索、问答与 Runtime-owned citations | [K1](milestones/K1-CLOSING-REPORT.md)、[K2](milestones/K2-CLOSING-REPORT.md)、[K3](milestones/K3-CLOSING-REPORT.md)；不是向量检索或自动 RAG |
| Model Management | 单一 Active owner、原生 exact-intent 确认、lease / switch / uncertainty guard | [MMF-3 closing](engineering/MMF-3-CLOSING-RECORD.md)；其历史 Development-only GO 不解除当前 X1 阻塞 |
| Workspace 存储 | 默认 SQLite；显式 PostgreSQL、Profile 选择与成功工作区记忆、原生配置和只读状态探针 | [PostgreSQL 收尾](engineering/POSTGRESQL-DESKTOP-CLOSING.md)；不自动迁移或静默回退 |
| Finance | 绑定身份后的账户、流水、资产、持仓、Portfolio、投资历史、导入回执、Dashboard 与统计只读查询 | [F-next-1–9 盘点](engineering/FINANCE-REMAINING-CAPABILITIES.md)、[F-next-10](engineering/FINANCE-F-NEXT-10-REPORT.md)、[用户确认归属](engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md) |
| 行情 | 显式 Massive 日线显示、来源／参考日期／获取时间、受控 Key 保存 | [M1.2 收尾](engineering/FINANCE-MARKET-DATA-M12-CLOSING.md)；用户报告 AAPL、SPY、USD/CNY 成功，不能外推全部持仓或本轮新包 |
| Web | W1A 原生确认的有界 HTTPS Fetch、SSRF / DNS pinning、临时结果 | [W1A closing](milestones/W1A-CLOSING-REPORT.md)；W1 整体未关闭，Search / Web Answer 不能宣传为已交付 |

Finance Budgets 明确为 **UNSUPPORTED**；不提供预算统计或写入。财务账本写服务、导入创建／确认、工具调用、独立 Finance AI Analyst 均未迁入。统一 AI 的未来金融能力仍须走已绑定的服务端领域边界。

## 验证证据如何理解

- **历史实际执行**：各工程／里程碑报告中的聚焦测试、构建、Windows fixture、数据库／模型结果，仅对应其记录的提交、环境与包。
- **用户人工报告**：M1.2 AAPL / SPY / USD/CNY，以及 F-next-10 的验收确认，按原报告归属保留；不改称本轮由代理实际运行。
- **UI 截图**：真实 Windows WPF / WebView2 渲染，采用隔离合成 UI fixture，不证明当前真实模型成功或用户数据流程通过。
- **文档现代化历史核验（`95ba76a`）**：文档与 owning code 静态交叉核对、链接／锚点／截图隐私审查、Git 与远端状态核验。详见 [原始文档整理报告](engineering/DOCUMENTATION-MODERNIZATION-REPORT.md)，其开发分支交付状态仅记录当时语境。
- **历史文档 Closing** 同步了当前文档的主线发布状态，检查了 Markdown 链接／图片路径、关键状态与 Git 差异，并发布到 main。历史快照与原始交付报告保留当时的记录。
- **历史文档 Closing 未执行**生产测试、完整 Windows 验收、GPU / 模型 / Provider / 数据库操作、构建和打包。开发指南中的命令来自源码核对。

## 仍需完成的验收

1. 用户启动最新开发包，在原生界面重新验证加载并确认 Ready，再恢复真实 X1 选区到结果流程；若 uncertainty 再现则停止，不重置 guard 代替证据。
2. 解决跨应用前台／物理输入阻塞，完成 Q/T 热键、操作切换、受控复制、Esc／关闭和混合 DPI 等真实流程。
3. Vision 的 WIC / held-handle 独立复审、实际 native payload / 有效环境与完整 tokenizer / template 上界合同，之后才讨论最小正向 policy 与真实单图验收。
4. PostgreSQL 同一 final 版本的物理托盘退出／重开关卡、D2 原生验收及新包验收，按各自报告逐项关闭，不用旧版本结果替代。
5. 独立完成最终产品、发布可信链、安装／迁移／恢复等关卡；当前不授予正式 Release 或 Production GO。

USD/CNY 参考日期落后的具体原因仍未知；获取成功不等于最新行情。Massive 显示能力不允许持久缓存、离线回放或派生估值。

## 历史记录与本地资源

现代化前的 1,280 行状态正文保留为 [历史快照](history/2026-10-28/STATUS.md)，其中 Current / PASS / PENDING 与分支名称仅描述当时阶段。历史工程报告、ADR、设计证据和里程碑原路径保留。

`.runtime/`、`.verification/`、`artifacts/`、TestResults、数据库、Token、DPAPI 密文及其他 ignored 私有资源不属于文档发布内容，不随源码推送。使用者不应把本机证据中的个人路径或凭据当成可分享材料。
