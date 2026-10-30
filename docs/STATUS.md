# 当前状态

核对日期：2026-10-28（Asia/Shanghai）。**Development-only；尚未 Production GO。**

2026-10-30 补充：[GitHub 展示与 Runtime 核对](engineering/GITHUB-SHOWCASE-RUNTIME-READINESS.md) 对应 `main @ 8df87ab`。UI Polish / P0 / P1 / P2 已合并；历史 uncertainty 已解除并有一次文本问答成功记录。最新开发包重启后的历史状态为 NotValidated；本次只读检查时客户端与 Runtime 未运行，不能声明当前 Ready。下文未另行更新的核验与发布描述仍属 2026-10-28 历史记录。

历史源码核验基线：`12dce0765dcdb89b75fd605c2b4fc7bf5db00617`；文档现代化候选：`95ba76a0f244037fd01a38e366da8f524908a418`。两者均为固定历史记录，不代表最新 main HEAD。
当前发布主线为 [main](https://github.com/qianlixunbai/personal-ai-workspace/commits/main/)：新版 README、文档体系与 Closing 修订通过 fast-forward 纳入主线；最新提交以该记录为准。原 54 个开发提交完整保留，原 12 个开发分支已清理，文档开发分支继续保留；本次仅完成文档发布，不升级产品验收。

[文档索引](README.md) · [开发指南](DEVELOPMENT.md) · [架构](architecture/current-architecture.md) · [路线图](roadmap/V1-ROADMAP.md)

## 必须保留的关卡

| 项目 | 当前结论 | 证据与限制 |
| --- | --- | --- |
| 产品定位 | **Development-only** | 受审查的本机开发路径；没有正式发布信任链或安装器 |
| X1 Windows Acceptance | **PARTIAL** | [X1 报告](engineering/X1-CROSS-APP-REPORT.md) 的完整跨应用流程仍未通过；后续单次文本成功不替代物理验收 |
| 模型执行 | **未确认当前 Ready** | [只读核对](engineering/GITHUB-SHOWCASE-RUNTIME-READINESS.md)：历史 uncertainty 已解除，最新重启记录 NotValidated；当前 Runtime 未运行 |
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
- **本次 Closing**：仅同步当前文档的主线发布状态，检查 Markdown 链接／图片路径、关键状态与 Git 差异，并正常发布 main；不重写历史快照或原始交付报告。
- **本轮未执行**：生产测试、完整 Windows 验收、GPU / 模型 / Provider / 数据库操作、构建和打包。开发指南中的命令是经源码核对的操作说明，不是本轮执行记录。

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
