# 原生分阶段接管、真实后端核对与本次会话结束

2026-11-10，承接 `9867af9fea05178fec9cc1d4139d50ba59ce513b`。在已交付的发布后所有权模块之上，完成两项相连模块：原生分阶段移交及真实 Runtime 核对；本次维护会话的安全结束和新进程终态观察。**完整个人维护迁移仍为 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN`**，没有接通迁移按钮，没有自动迁移或自动清除 fence。

前置证据：[发布所有权](MAINTENANCE-PUBLICATION-OWNERSHIP-ACCEPTANCE.md)、[绑定与配置](MAINTENANCE-BINDING-CONFIGURATION-ACCEPTANCE.md)、[来源 owner](SQLITE-MAINTENANCE-OWNER-ACCEPTANCE.md)、[共同事务](COMBINED-MIGRATION-TRANSACTION-ACCEPTANCE.md)。复用原 Gate、真实 DuplicateHandle、来源 lease、两域共同事务和配置保护；没有以 mock、旧回执或进程创建成功代替接管。

## 阶段、实际持有人和失效边界

原生 `MaintenanceRuntimeHandoff` 自己启动、保留两个实际 Process 和自有 Job，从嵌入 pins 校验过的产品 JAR 启动 source child 与新 Runtime；不重新打开任意 PID。原生持有 Runtime admission 写句柄、model exclusion 读句柄和来源包 pins，直到会话退出。新 Runtime 在原 Gate 准入后自行生成随机 challenge；source owner 的 DPAPI intent 同时绑定本次 operation、实际 Runtime PID、challenge、Profile 目录身份、配置密文摘要及目标绑定。

| 阶段 | 来源／配置保护 | 目标保护与允许的行为 |
| --- | --- | --- |
| 停写、备份、共同提交、配置发布 | 原 owner 保持来源数据库、知识原文、备份、阶段文件及 owner.lock；发布后继续 pin 实际配置 | 复用前层共同事务和发布 SHARE 事务；不是声称导入提交之后始终冻结一切外部写入 |
| 新 Runtime `PINNED` | 在旧 owner 释放前，新 child 获取重叠的来源、备份、日志、配置及 exclusion 保护；核对完整原始链和摘要 | 新 child 同时建立自己的真实目标 SHARE 事务，重读绑定和导入内容 |
| `INITIALIZING` | 原生确认旧 child 实际退出 0；新 child 必须取得原 owner.lock；来源和配置 pins 持续存在 | 真正 Spring DataSource bean 先建立另一条重叠 SHARE 核对连接，再释放 bootstrap 事务；bean 返回前最后核对连接也关闭，明确进入 Runtime 可写初始化阶段 |
| `VERIFIED`／`SERVING` | 本次新 Runtime 保持所有权；原生仍保持实际 Process、管道、Job 和外部 exclusion | 正常 Spring／Memory／Conversation／Knowledge 初始化全部执行，包括 reconcile 写入。完成后从实际 Store 连接重读身份，并再核对完整导入内容；原生收到匹配证明后才能发 SERVE |
| `STOPPING` | 停止接受新的 HTTP 请求，仍持有本次所有权 | 正常关闭真实 Spring context；确认实际跟踪的所有 DataSource 连接已关闭。使用新目标连接核对当前绑定和配置，不要求恢复成导入时内容 |
| `VERIFIED_STOPPED` | 保留全部持久记录；当前进程退出后释放内核 pins | 原生收到同会话回执并观察此实际 Process 退出 0，再启动独立 inspector，核对完整 DPAPI 链和当前配置／目标身份。它观察历史，不授权普通重启 |

原 `KnowledgeStore` 初始化 UPDATE 与维护 SHARE 锁的冲突由明确的 bean 移交边界解决；没有去掉维护锁、跳过初始化或给所有普通启动增加例外。只读 `StorageStatusService` 使用原有 PGSimpleDataSource 工厂；它不会消耗真实 Runtime bean 的一次性移交。整个初始化期间 HTTP 为 503，只有实际后端核对与原生 SERVE 完成后才接受请求；每次准入仍核对本次所有权。

目标进入 Runtime 可写阶段后不再声称受维护表级 SHARE 锁持续冻结。初始化结束的完整内容校验会拒绝这一区间造成的目标漂移；运行后只允许正常业务演进，结束时不能以旧导入摘要清理后来数据。既有未注册写入者保护继续是通用维护入口的阻断条件。

任何失败、管道中断或进程死亡都不生成成功终态、不重放共同导入、不清除操作记录或配置。旧 owner 在 PINNED 前仍持有资源；PINNED 后新 child 已有重叠保护；新 child 死亡后内核锁会释放，但持久 fence 阻止普通应用继续，不能声称此时还阻止任意外部 SQL 或非协作文件写入。旧维护 JVM 的 `maintenanceAttempted` 仍不可转成普通 Runtime。

## 实际证明与最小验收

新增 opt-in 原生验收 `MaintenanceRuntimeProbe.cs`；使用完整产品启动方法、真实 Spring beans、实际 HTTP、本地 token、真实 PostgreSQL 17.11。实际数据源记录它创建的 Connection 对象；三个 Store 在各自 monitor 内交出自己真正初始化的连接供核对，验证连接归属、目标持久绑定、schema 和不同的 `pg_backend_pid()`。请求方提供的 PID、固定 READY、历史 backend PID 均不能代替这些检查。

新维护会话使用原生支持的 loopback 动态端点 `server.port=0`，返回实际 WebServer 端口；原普通启动端点仍为 18765。本轮没有查询、占用或绕行被拒的 18765 系统诊断。原生管道只接受限定帧和本次 operation／challenge／配置／绑定；失败输出只含阶段、异常类型和代码位置，不含异常消息、SQL、配置值或凭据。

最终 Java 候选 SHA-256：`D4F92FDC932F244DA59642B60BA61CA8373739A683BB0341AE43EC4DE531350C`。12 个 Java 源文件联合编译生成 44 个 class，18 条既有 `ExtendedOpenOption` 内部 API 警告；原生构建 0 警告、0 错误。验收从完整候选 JAR 启动，没有 classpath 产品覆盖或缩减 Spring context。没有重跑已有事务矩阵、网页保存重开或模型调用。

| 最终场景 | 合成 Profile／operation | 结果 |
| --- | --- | --- |
| 正常结束，`.verification/maintenance-runtime-handoff-20261110-r4/` | `c462b8e36a6e4912a9083211519faea7`／`49d6b89415f34a1296ce3ce06cf02b02` | Runtime PID 18480、HTTP 50882、Store backends 24224／2568／33000。实际 storage 状态为 PostgreSQL／TRUSTED_LOCAL／CONNECTED／V1_V2_READY／知识 READY；HTTP 创建并读回记忆 `20f1d444-1d2b-455a-8639-fc5c31eba3ea`。正常结束及新进程终态观察通过；结束后独立 SQL 读回同一新增内容；普通新 JVM 被 fence 阻断 |
| 中断，`.verification/maintenance-runtime-handoff-20261110-r6/` | `9d8e09bfe12c48e0a87daf999ad43246`／`89d94d53bc06475eae415786283e2bb1` | 同一候选实际接管和 HTTP 写入后关闭自有管道／Job。保留 intent、PINNED、INITIALIZING、VERIFIED，未写 STOPPING／VERIFIED_STOPPED。来源与配置摘要不变；新增记忆 `9b6bfa33-ebba-4d8c-a888-06880f98a22c` 在退出后保留；新 JVM 普通启动 fence 通过 |

两次正常执行的产品路径均核对来源文件 SHA 不变。正常结束调用是一次性的：发送 FINISH 后即置 attempted，未知响应不得再次发送。结束只关闭这次 Runtime 与本次维护句柄，没有删除／覆盖来源、备份、目标行、后来配置或日志。独立 inspector 仅承认精确完整链，并重新连接当前目标；它明确返回 `ordinaryStartupAuthorized=false`，不把历史 PID 当作仍活着的后端。

中断实测使用真实拥有的 Job 退出，未区分最终由 EOF 正常失败还是 Job 终止竞争完成，因此不称中断 Runtime 自然退出。正常结束产品则由源 child、新 Runtime、inspector 的实际退出 0 支撑。外层 fixture／probe／普通启动检查均结束，两个临时 PostgreSQL 正常停止，初始化密码、合成实际／暂存 DPAPI 配置和合成 token 按原方式清理。未删除合成来源、备份、受保护日志或目标行。

正常结束 guardian 31628 在 `2026-11-10T13:59:13.2218195Z`、中断 guardian 13284 在 `2026-11-10T14:01:54.2833942Z` 均记录 Job active 0、Job 关闭、共享锁释放。对应 `handoff-result.json`、`handoff-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json` 为主要凭证。凭据未入报告或 Git。

## 保留的失败与未覆盖边界

初版验收宿主的 RID assets 缺失导致一次构建失败；随后两次宿主在进入产品前因 framework-dependent 启动方式／10.0.14 与已装 10.0.12 不匹配而失败。修正仅使用已有 `dotnet exec` 和项目默认 .NET 10.0 框架配置，没有安装运行时或改系统策略。r3 实际进入正常 Runtime 初始化后，旧只读状态调用遇到工厂返回类型变化的 `NoSuchMethodError`；已保留原工厂签名并分离真实 bean 移交，r4 正常完整验证通过。r5 中断产品和后来数据保全通过，但末尾普通启动检查脚本的 helper 路径含 `..`，不符合原 Gate 规范路径合同；仅规范化脚本路径，r6 复用相同候选补齐通过。所有失败包装已收尾，证据未覆盖成成功。

本会话一次沙箱只读 `Test-Path` 检查 r3 合成 Profile `bf786634eabb4e5b80edeadf25cecab8` 的 `evidence/sqlite-activation` 目录收到 Access denied；该动作未重试、未提升权限或换路线读取同一目录。目标绝对根由 r3 的 `prepare/owned-profile.json` 记录。已查用户消息，没有相关新授权，其他项目尚待确认的 RemoteSigned 请求不适用。本轮无自动审批拒绝、待审批或未知执行结果；此目录检查不再需要，产品验证依靠新合成会话自身授权流程的回执完成。既有 Get-NetTCPConnection／Docker／Crashpad／原生诊断及 KAG 脚本拒绝均未触碰。

本次完成的是受保护的新 Runtime **会话接管与安全停止**，不是持续可重开的个人迁移。普通重启仍被维护记录阻断；还需设计并验证完成迁移后可以消费的原生普通重启权威，而非删除 evidence、根据 VERIFIED_STOPPED 自动放行或复用旧后端证明。模型 exclusion 全程保持，未验证模型配置所有权移交／模型调用；完整 Spring 启动不代表 AI 功能已接管。通用未注册写入者证明、真实用户明确选择／确认、完整 UI 接线、正式发布仍未完成。没有验证任意阶段断电、数据库中途崩溃、全部消息丢失组合、跨机、后来配置替换或并发外部 SQL 注入；既有配置 pin 和未知结果不重放保护保留，但不虚报新矩阵覆盖。

## 本地交付

产品提交：`1c37625595aff3dddd3544be850239da329c72e1`，分支 `codex/knowledge-version-compare`。

新包：`artifacts/PersonalAiWorkspace-maintenance-runtime-handoff-20261110.zip`；933 payload 文件、934 ZIP 条目逐一核对；420 条内嵌 Runtime pins 与实际文件一致。

- ZIP SHA-256：`CF7567E167BAD79CD7AF65A883035BB7B08AB3BD11AC20E63884E86FE657CB8C`
- Runtime SHA-256：`1DFB28CD9A7018758679D929DF0DE1011F1C9BA94CA7667C04C1C484A02F66A1`
- Desktop DLL SHA-256：`703A335C9251C0E489BCBA5A4F7BF9E5CA6A12626B78CBBD92C8C98BFF398209`

Runtime 交付只更新 `META-INF/workspace-runtime.properties`，其余 JAR 条目与最终验收候选逐字节一致；Desktop 从已验收源重新构建并嵌入交付 pins。没有额外重跑默认 EXE／网页／模型矩阵，也不把此打包当成正式发布验收。打包 guardian 17664 在 `2026-11-10T14:06:38.9228883Z` 记录 Job active 0、锁释放，详见 r4 的 `delivery-result.json`／`publish-cleanup.json`。

前一所有权包与前一绑定配置包 ZIP SHA 分别仍为 `6DB6D33DBBF5BE20D630D723AA94A8BBFF20530EB1CEDE4F258BA1CE972EBD5D`、`0EAC0591CB9751D756638BF97E373C41B31948B9258550E2EB27B0127B259827`；旧包未覆盖。main 仍为 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，六份用户文档与本会话前层 baseline SHA 一致且保持未跟踪。没有推送、真实数据修改、待审批或未知执行结果。

下一阶段应基于这里的终态边界解决安全普通重启与明确用户入口，不重新实施已验收的共同事务或发布 owner。与前一已交付的发布所有权合计，本会话完成约三项真实模块；继续集成时仍需保留 BLOCKED，不能仅因正常停止记录存在就开放普通启动或迁移按钮。
