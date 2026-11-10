# 两域共同事务与结果核对

2026-11-10，接续 `fcdd646663130a1495da110c4122b113a346172b`，仅操作 personal-ai-workspace。选择维护审查中的“两域协调”作为本轮最小可独立实现模块：新 `SqliteCombinedMigration` 在一个 PostgreSQL 连接、一个事务内写入 Workspace 与 Knowledge，含两条已有导入回执和知识索引。它是生产代码中的离线组合 primitive，**不是完整维护入口，不授予来源停写、配置发布或个人恢复权限**。

## 实现与准确恢复语义

- 两域复用原 importer 的结构、业务行、摘要及文件校验；两个 SQLite 快照的读事务与 Knowledge 原文 pin 持续到目标提交和新连接核对结束。只接受调用者已经隔离的快照；不会自动寻找来源、备份、连接既有用户数据库或启动迁移。
- 目标使用同一 JDBC 事务，取得 Knowledge Store 生命周期对应的 advisory 排他锁、Knowledge 写锁和 Workspace 写锁。任一域失败时对该事务回滚；没有调用两个独立 `migrate()` 后再套一层空事务。
- COMMIT 前错误标记 `NO_COMMIT_ATTEMPTED`，不把它表述为目标空库证明。COMMIT 时及之后的错误标记 `COMMIT_UNCONFIRMED`；即使随后 rollback 返回，也不声称先前提交失败。
- `inspect` 用一个新的只读 REPEATABLE READ 事务核对两域数据与已有回执，区分空目标、匹配、差异／部分数据、目标改变。观察不会重试导入、删除行、恢复旧数据、写配置或选择权威来源。迁移后新增数据会得到差异结果并原样保留。
- 目标绑定包含服务地址／端口、数据库 OID、角色 OID及服务启动时刻，保守限制为同一运行实例；**不是持久集群身份或授权凭证**。两条旧回执匹配也不能单独证明历史上来自同一个事务。尚无受保护的本地阶段日志，不能宣称崩溃后的完整配置恢复已实现。

实现核对过 PostgreSQL 17 的[事务可见性与回滚](https://www.postgresql.org/docs/17/tutorial-transactions.html)及[事务级／会话级锁生命周期](https://www.postgresql.org/docs/17/explicit-locking.html)。单个 PostgreSQL 事务只解决本次目标提交；SQLite 来源与 DPAPI 文件并不因此进入同一个事务。这一范围判断来自仓库调用链。

## 一次真实隔离闭环

证据目录 `.verification/combined-migration-20261110/`，独立 opt-in `bootstrap/tests/CombinedMigrationProbe.java`。使用新 Native Prepare Profile、独立 PostgreSQL 17.11 随机 loopback 高端口、受限应用角色和现有 V1/V2 结构。初始目标、凭据、数据目录都属于本轮；启动和 SQL 写入前复核自有集群身份。没有 Docker、18765 系统查询、模型、真实用户配置／记忆／知识读取。

合成源包括一条 Memory、一个会话／成功 turn／两条消息／一个记忆选择，以及一个 Knowledge 文档、版本和 READY job。夹具关闭源 Store 后，同时持有两个 `BEGIN IMMEDIATE`，生成两个完整 SQLite 备份和知识原文副本，再把**这些隔离备份**传给新生产 API。夹具自身知道所有合成写入者，这不是对用户来源停写合同的替代证明。

| 新行为 | 真实结果 |
| --- | --- |
| Knowledge 索引写入时触发实际 CHECK 约束失败 | 之前写入的 Memory、Conversation、Knowledge 与两域回执均回滚；新连接检查 EMPTY |
| 目标已有真实 KnowledgeStore 会话 | 共同导入在 TARGET_EXCLUSION 拒绝，目标仍空 |
| 实际 JDBC commit 完成后代理抛出“丢失确认” | 返回 COMMIT_UNCONFIRMED；手工显式调用只读 inspect 查到两域数据和回执，不重放写入 |
| Store 重开与新数据保护 | Memory 内容、会话 turn、Knowledge 原文读回一致；之后新增 Memory，inspect 返回差异；重复导入拒绝，新增行保留 |
| 第二个全新目标的普通成功路径 | 单次共同提交成功返回，新连接两域核对匹配；把回执拿到第一个数据库检查返回 TARGET_CHANGED |
| 源／备份／配置 | 源库及两个备份前后 SHA 不变，知识原文与副本逐文件匹配；源写锁再次取得并回滚；database.dpapi 始终不存在 |

`probe.log` 六条 PASS、Java exit 0、worker exit 0。故障注入只模拟**真实提交之后的确认丢失**，不是实际断网／断电／进程崩溃试验。没有重跑前轮独立 importer 全矩阵、网页切换或正常 EXE。

## 构建与历史失败

生产候选采用已交付 Runtime `6672ca01cfd6cd4926cd0a28374d77cbe4a63b1d0fb06392f1f7ff4e139e6dd5` 的原依赖与 JDK，仅重编译三个实际变化的生产类族，共 16 个 class。现有 `ExtendedOpenOption` 有 3 条 JDK 内部 API 警告，无编译错误。重新提取候选 JAR 后编译验收程序；验收 classpath **没有生产类覆盖目录**。候选 `combined-runtime.jar` 的 SHA-256 为 `d1a8aa87d9b43ff9c9f7cd14e229eb0754bc41a4d3dd50e62a12173a3c4c2e32`，现有 Runtime 审计通过。

保留所有历史失败，未把它们计为产品验收成功：

1. WindowsApps 的 Python 别名无法执行，未重试该程序，未产生预期 Java 文件；后续直接编辑文本。
2. Maven wrapper 未启动；本地 Maven 初次找不到离线缓存，指定现有缓存后编译停在本地 tomcat 依赖路径。没有下载、改缓存权限、EFS 或系统设置；最终使用已交付包的依赖限定编译，**不是全量 Maven 构建成功**。
3. 首个 .NET ZIP Update 生成的候选未通过 manifest 审计，已保留为失败产物，不交付。改用现有 JDK jar 工具在另一份新副本更新，审计通过。
4. 初次验收编译引用了不存在的私有文件工具方法，改用现有 WindowsPrivateAcl 创建本轮文件后，仅重编译验收程序通过。
5. 一次共享锁忙在构建前退出，没有 Profile／服务启动；随后锁空闲才继续。

## 收尾与剩余阻塞

普通非管理员宿主的 prepare、run 均成功；PostgreSQL 用自身 pg_ctl 正常停止，临时初始化密码删除，未生成持久数据库配置。run guardian 于 `2026-11-10T11:27:18.026329Z` 关闭空 Job（成员 0）并释放 Session 1 `Local\Dot_KAG_Workspace_HeavyRun`；无强杀数据库、无待审批或结果不明动作。

最初枚举验证目录时，以下旧目录返回访问拒绝：`browser-smoke-auth`、`local-smoke-auth`、`m2b2b-r1-chrome/private`、`mmf-1-review-1l0_ctyf`、`mmf-3-same-account/run-4a5ae09cbaa24be0af840b1c7efda64c`、`postgresql-credential-recovery-fixture`、`v1-ollama-0401-environment/models`。未读取其内容、未重试、未改变权限；随后只读取交接指定脚本与本轮新目录。这是文件访问拒绝，不是自动审批拒绝。

**尚不能执行的具体动作：原生维护入口对真实 Profile 的完整停写迁移、配置发布／恢复。** `ProfileRecoveryCoordinator.SourceProducer` 仍为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN，`MaintenanceExclusion` 仍不授予来源权限；本轮没有改变该合同。组合 API 没有自动生产入口。需继续在受信 native owner 内落实来源冻结／完整备份至激活的整个时段、可恢复的受保护阶段记录及稳定集群绑定，再接用户明确确认。这是产品设计／实现阻塞，不是新的系统权限拒绝，不需要放宽 ACL、沙箱或账户组来“解锁”。

默认入口、个人恢复、跨机器和正式发布保持未验收。本轮只有一个完整的两域协调模块，不把构建或日志观察凑成三项产品功能。main、六份用户文档、旧开发包保留，不推送；本地提交和新开发包的最终证据在后续交付段记录。
