# Personal Finance OS → Workspace 技术交接

2026-10-22（Asia/Shanghai）。**仅源码只读梳理与下一轮建议；没有开始 Finance 功能实施、数据库迁移或真实数据修改。** PostgreSQL Desktop 开发交付见 [Closing](POSTGRESQL-DESKTOP-CLOSING.md)；完整物理生命周期人工关卡仍单独保留。

## 权威来源与实际核对

- 冻结来源仓库 `D:\IDEA\Daima\personal-finance-OS`：本轮实际为 `zh-cn @ c6c87a0627b746e70a45b1c7896f63defd76a810`，工作树干净，未 fetch/build/写入该仓库。
- 读取其 `backend/pom.xml`、`docs/STATUS.md`、`docs/architecture/{current-architecture,consistency,security}.md`、`frontend/src/App.tsx`。原仓库当前文档仍将正式 Freeze 记为 CONDITIONAL GO；本轮依用户指令执行冻结只读保护，不擅自改写其里程碑状态。
- Workspace 现有 [Finance 模块与来源](../../finance-domain/README.md)、[source-provenance](../../finance-domain/source-provenance.json)、[migration-provenance](../../finance-domain/migration-provenance.json)、[F2.3-A2 报告](F2.3-A2-FINANCE-POSTGRESQL-INTEGRATION-REPORT.md)；实际读取 `FinanceModuleConfiguration`、`FinanceAccountQueries`、`LocalFinanceProfile`。不是从零复制整个 Finance 应用。

| 项目 | Finance 来源 | Workspace 既有边界 |
| --- | --- | --- |
| Java / Spring | Java 21、Boot 3.3.5、MyBatis-Plus、PostgreSQL 17 | Java 21、Boot 4.1.1；`workspace-application → finance-domain` 单向依赖，禁止扩大 `com.financeos` 全包扫描 |
| 认证 | 现有 Web JWT principal，服务端确定 userId | Native Bearer 只证明 Native 调用者，不自动证明 Finance 身份；不能以 Windows SID、Profile ID 或客户端提交 userId 认领数据 |
| 数据库 | Finance V1–V21、独立用户和金融事实 | `financeDataSource` / `financeTransactionManager`，`workspace.finance.enabled` 默认关闭；Workspace V1/V2 不能复用 Finance history 或迁移器 |
| Web UI | React 19/TS/Vite、Axios/JWT、账户/流水/投资/导入/AI 页面 | 可信 WebView2、typed allowlisted Bridge、Runtime 为真相；不搬入 JWT localStorage 或前端直连数据库/任意 HTTP |
| 金融权威 | 服务端精确金额、事务、锁、事实、receipt、replay | 现有只读 `FinanceAccountQueries` 是可复用入口，不由 React 或 LLM 重算权威余额 |

Boot 3→4 兼容改动应仅在 Workspace 的必要适配处做；原来源文件/SQL 的 byte provenance 与版本含义保留，不为兼容修改冻结原仓库。

## 建议第一项：身份绑定与只读账户纵向切片

历史 F2.3-A2 的真实 SQL/金额/用户隔离/事务证据已具备，但独立 JVM 的真实 `LocalFinanceProfile` 文件发布曾返回 `FINANCE_BINDING_INVALID`。该 Windows 绑定缺口本轮没有重新运行；Workspace Profile 现代化或 PostgreSQL 启动成功不能自动把它改成 PASS。

下一轮先核对现有合同与实际文件调用链，完成一个最小且可审查的 Native 身份确认路径：

1. 受信原生 UI 显示经服务端读取的目标数据库与历史身份摘要，用户明确确认；不索取聊天 secret，不接受 WebView 自报的可信用户 ID。
2. 复用 `FinanceAccountQueries.confirmHistoricalBinding` 与 `LocalFinanceProfile.createConfirmed` 的 CREATE_NEW/不重绑语义。该确认当前没有 HTTP/Bridge/AI 入口，不能假称已有可用产品 UI；显式加入口须先明确权限与确认合同。
3. 复用 `financeDataSource`、`financeTransactionManager`，在 read-only REPEATABLE_READ 事务内重读数据库身份、ACTIVE 用户与绑定指纹，再查询当前绑定用户账户；缺失/改变/不可访问一律 fail closed，不选第一条用户自动绑定。
4. 通过窄 DTO / typed bridge 只展示一个 CNY 余额与账户详情切片；暂不迁移 Finance 写路径、导入流程、整套 Web Router 或 AI Tools。

起始验收以一条合成 integrated flow 为主：显式绑定 → 自有账户只读查询 → 他人账户拒绝 → 身份/数据集改变拒绝 → 重开保留。保留原有高价值事务测试，不把每个检查点拆成多套矩阵。真实绑定发布与首次启用 Finance 仍需独立目标和安全确认；本交接不授权对真实 Finance 库建表/迁移/写入。

## 必须保持的金融不变量

- 账务写入依旧由服务端 Service 与单个 Finance PostgreSQL 事务拥有；不得让 Workspace task/LLM 调用替代交易提交或把两个 DataSource 拼成伪原子事务。
- `AccountBalanceService` 为生产余额写入原语；普通流水的事实与 delta 同事务回滚。投资保留不可变事实、固定锁顺序、candidate/canonical replay、投影、审计与 receipt 一致提交；V13 等 deferred integrity 约束不能删改来迎合新入口。
- 幂等键必须绑定服务端用户与 canonical request hash；同 key/同意图返回既有 receipt，同 key/不同意图冲突；unknown commit 只按同一冻结意图查询/恢复，不生成新 key 重发。
- 金额保持精确 BigDecimal/货币边界；审计、trace/digest 和确定性重放不由前端/AI 重建。Import 保留 Session 锁、账户升序锁、frozen plan、CONSUMED/receipt 事务与提交后清理分离。
- Finance V1–V21 与 Workspace V1/V2 分开 DataSource、角色、Flyway location/history 与显式启用开关；不因同为 PostgreSQL 而合库、自动迁移或复用 Workspace 数据集身份。

这些是后续实现的保留约束，本轮未重新执行金融事务、并发、重放或 Import 验收，不把原 Finance G4-3 的历史 GO 扩大为 Workspace Finance GO。

## UI、AI 与回退顺序

| 后续阶段 | 最小交付与退出条件 |
| --- | --- |
| F-next-1 身份/只读 | 先关闭真实 Windows 绑定缺口与账户读隔离；Finance 仍有独立开关，关闭后 Workspace 不要求 Finance 数据库可用 |
| F-next-2 UI 逐步接入 | 从账户只读展示复用组件/格式化开始，适配 typed native transport 与服务器身份；独立 Finance Web/JWT 产品仍可运行，不直接复制整个 App/登录状态 |
| F-next-3 必要金融写切片 | 逐个迁移原 Service/receipt/replay 合同与回滚证据；需独立授权与设计，不在 UI 层实现余额 effect 或跨库事务 |
| F-next-4 AI 协作 | 在已绑定的只读应用服务前定义受限 Tool；统一 Runtime 只负责执行/策略，Finance 仍拥有事实、精确计算和审计。保留现有 `FinanceAiOrchestrator` 的只读、有限轮次语义，不把 Cloud Provider 默认改成共享本地模型 |

Memory/Knowledge 是用户逐次授权的上下文，不自动吸收金融账本、身份或完整流水；AI 回答不能成为 receipt、投影或账务真相。后续必须定义输入预算、用户/数据集 scope、取消/过期/错误与审计脱敏，再接 Tools；不开放任意 Mapper/SQL/写操作给 AI。

回退以关闭 Workspace Finance 入口和独立部署切换为先；原 Finance 应用、数据库与原仓库保留。Finance 备份/绑定指纹/跨集群恢复另立合同，现有 Workspace Backup 不覆盖 Finance。不能用旧数字 userId 或旧指纹自动接管恢复后的数据，不以删除 binding.json 或回滚 SQL 修复身份冲突。

## 已审查开源资料如何使用

当前能核对的归档是 [GITHUB-OPEN-SOURCE-EVALUATION.md](GITHUB-OPEN-SOURCE-EVALUATION.md)，2026-10-19 的历史审查；两仓库中未定位到另一个题为 Global Open-Source Benchmark 的独立原件，不能虚构其选型结论。下一轮如有该原件，应先补齐对应路径/版本与已审查范围。

沿已有审查结果：复用已集成的 react-error-boundary；借鉴 CliWrap 的取消/真实退出及 mem0 的审计溯源思想；只有出现独立需求时才评估 Testcontainers 的隔离测试环境，或在现有 Provider 后二选一评估 Spring AI / LangChain4j 薄适配。不引入自动记忆、遥测、第二套持久化真相或新的金融服务来替代现有合同。

这不是对这些上游的当前版本、维护状态或 Finance 安全性的重新认证。本轮没有外部大调研、安装依赖、拉取镜像、修改 Provider 或启动任何上述下一阶段功能。
