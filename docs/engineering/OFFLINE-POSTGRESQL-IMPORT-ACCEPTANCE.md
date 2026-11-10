# 真实 PostgreSQL 离线导入验收

2026-11-10，接续 `fb9c60227c93bcf2fe618c6e833946b2a76194e9`，分支 `codex/knowledge-version-compare`。只操作 personal-ai-workspace 仓库及本轮新建的合成 Profile；没有连接现有用户数据库、Docker、Finance OS 或独立 KAG。未发现本仓库及已检查祖先中的 AGENTS.md。

## 结果与用户价值

**两条真实文件迁移 API 均完成独立 PostgreSQL 17.11 导入、提交后核对和 Store 重开。** 前轮只证明 SQLite 长路径备份与目标不可用时保全，本轮补齐目标真实可用时的持久化，以及目标中途失败的事务回滚。没有修改生产代码，也没有把模拟 DataSource 当作成功导入。

直接运行已交付 `PersonalAiWorkspace-migration-long-path-20261110` 中的 Runtime JAR，其 SHA-256 为 `6672ca01cfd6cd4926cd0a28374d77cbe4a63b1d0fb06392f1f7ff4e139e6dd5`。仅添加独立 opt-in [验收程序](../../bootstrap/tests/OfflinePostgresqlMigrationProbe.java)；classpath 中没有生产类替换。现有 JDK、SQLite/JDBC/Flyway 依赖、NativeBootstrap 均来自该包。该包的产品修复来源仍为 `5d86d409019f21308714f8854c011c0282428bdc`，打包来源仍为 `31b877616779dfa5c8e2e80c6a319e7b4b29958f`，不会被本轮验收提交替代。

| 路径 | 实际提交并逐列核对的数据 | 源 / 成功备份路径长度 |
| --- | --- | --- |
| Workspace SQLite v3 → PostgreSQL | 2 条 Memory（含 ACTIVE revision 2 和 ARCHIVED）、1 个 Conversation、1 个终结 Turn、2 条 Message、1 个 Memory selection | 472 / 468 |
| Knowledge SQLite v1 → PostgreSQL | 1 个 Document、1 个 Revision、1 个 READY Job、0 个 Delete；重建索引有实际 chunk，原文预览和词法搜索通过 | 485 / 478 |

长度为 UTF-16 单元数；本轮源和备份**同时**超过 MAX_PATH，含中文、空格、`%23`、`#`、`&`，正文含 emoji、中文和换行。来源是现有 SQLite 格式，不是任意更早 schema 的升级验收。

单组集中验证覆盖：

- 新集群中显式执行 Workspace Flyway V1/V2，实际 `migrationsExecuted=2`，validate 成功。导入使用独立非超级用户应用角色，无建库、建角色、复制、RLS 绕过或 schema CREATE 权限；生产 `PostgresqlWorkspace.verify` 实际通过。
- Workspace 在 `conversation_messages` 写入时触发真实 PostgreSQL CHECK 失败；此前的 Memory、Conversation、Turn 写入整体回滚。Knowledge 在重建 `knowledge_chunks` 时触发真实 CHECK 失败；此前的文档、修订、Job、索引和导入回执整体回滚。服务器日志分别记录 `synthetic_failure`，不是仅在连接前失败。
- 两条路径都用新连接检查所有相关目标表及导入回执为零后，显式移除本轮合成约束，使用**新的备份路径**完成导入；没有自动重试未知提交结果。
- 成功后从新 PostgreSQL 连接逐列比较全部源业务行，核对生产返回 digest 与持久回执一致；关闭后重新创建 Memory/Conversation/Knowledge Store 读回。Knowledge 新建索引对象后按词法查询准确文档。
- 同一备份路径再次调用时拒绝且不覆盖；换新备份路径对已导入目标再次调用时拒绝，相关目标表、索引和回执全部保持原值。失败、成功及重复拒绝均保留可读且 integrity_check 通过的完整备份。
- 源 SQLite 文件前后 SHA-256 及所有表/行保持相同，Knowledge 原文与各备份中的对应原文逐文件一致；每次调用后重新取得 `BEGIN IMMEDIATE` 再回滚，证明源写锁释放。此处是本轮合成源的字节比较，不推广为历史用户数据库“零写入”。

未执行模型请求、HTTP/网页/完整 EXE 流程、用户数据迁移、默认后端切换、恢复激活、跨机器或正式发布。没有重跑旧的完整桌面验收或退出观察。生产包未变，无需制造新包。

## 隔离与依赖

复用此前 `.verification/memory-postgresql/run-3/tool-identities.json` 中实际使用的本机 PostgreSQL 17.11 四个工具；本轮 SHA-256 全部相同。没有下载、安装或执行新来源程序。文件未签名，历史哈希一致是本次复用证据，不扩写成整个发行包重新完成了来源认证。

遵循 PostgreSQL 官方 [initdb](https://www.postgresql.org/docs/17/app-initdb.html)、[独立数据目录与端口](https://www.postgresql.org/docs/17/app-postgres.html)、[pg_ctl 启停](https://www.postgresql.org/docs/17/app-pg-ctl.html) 的既有方式。全新 `pgdata` 位于本轮 `.verification` 目录，随机 SCRAM 密码只用于本轮集群；initdb 临时密码文件已删除，Java 所需秘密通过 stdin 传递，不写入 Git、报告或命令行。新建 Profile 通过已有 Native Prepare，未改真实默认 Profile/配置或真实记忆。

服务仅监听 `127.0.0.1:60271`。先以本机临时 listener 申请系统分配端口，再由本轮 `pg_ctl` 启动；没有查询系统已有端口/服务。连接前核对本轮 postmaster PID、启动代次、程序路径、数据目录和监听配置；执行任何数据库写入前再核对 PostgreSQL 返回的 data_directory、system_identifier、版本、地址/端口及全新集群目录数量。未读取既有凭据或依赖用户的 PostgreSQL 服务。

## 两次宿主尝试与报告导出

完整保留失败记录，不覆盖为成功：

1. `.verification/offline-postgresql-migration-20261110/`：沙盒宿主以 `CodexSandboxOffline` 初始化成功，但 `pg_ctl start` 返回非零，未生成 postgres.log 或 postmaster.pid。该轮没有 JDBC 连接或迁移。旧 helper 丢弃了 start 输出，因此无法断言其精确根因是权限问题；仅确认沙盒宿主与此前普通用户宿主不同。临时密码已删除，guardian 关闭前 Job 成员为 0。
2. `.verification/offline-postgresql-migration-normalhost-20261110/`：经精确正常审批，普通非管理员宿主新建另一份合成 Profile/集群。JVM 退出 **0**，两条路径均输出 PASS；PostgreSQL 正常停库，Job 成员为 0。随后 `Copy-Item` 导出合成 JSON 时因“无法加密指定的文件”失败，故 **worker/guardian 的总体退出码为 1**。这是报告复制失败，不改写成整条脚本成功。
3. 数据库已停止后，仅经正常审批读取既有合成报告的文本，写入工作区新文件 `result-export.json`，没有重跑数据库、迁移或修改 EFS/ACL。源与导出 SHA-256 均为 `6EBCCF5DB798C52AFAEA5CF4A0D83485DFD3278FA2E36B66B79D516473FC0088`。历史失败脚本保留；后续报告转存应写文本内容而非复制加密属性。

关键本地证据：

| 文件（普通宿主证据目录内） | 内容 |
| --- | --- |
| `prepare/owned-profile.json`、`intent.json` | 新合成 Profile `8b0aa49e1ca944bd8d5dab70e9c4bd9f` 的来源 |
| `owned-cluster.json` | 本轮 PID 34740、端口 60271、system identifier `7706489417030284108` |
| `probe.log`、`result-export.json` | 两条真实导入 PASS、数量、哈希与边界 |
| `cluster/postgres.log` | 实际两次约束失败、ready 和正常 shutdown |
| `fixture-result.json` | `probeExit=0`、`ownedInstanceStopped=true`、`temporarySecretsRemoved=true`，报告复制失败原值保留 |
| `run-worker.json`、`run-cleanup.json` | worker exit 1；Job 清理前 0，finally 释放 Job 和共享锁 |
| `export-receipt.json` | 仅文本导出成功、前后哈希相同、数据库没有重跑 |

Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 覆盖各准备/编译及完整自有服务生命周期。普通宿主 PostgreSQL 于 `2026-11-10T10:34:57.309Z` 记录正常关闭，guardian 于 `2026-11-10T10:34:57.5155958Z` 关闭空 Job 并释放锁；不是 Job 强杀数据库。无仍运行的自有服务、待审批或结果不明动作。未变更系统服务、防火墙、账户组、安全设置、注册表、EFS、既有 ACL 或网络策略。

## 剩余交付边界

本轮完成了**真实离线导入 primitive**，还不是用户可操作的完整维护迁移：静态检索生产源码没有发现调用这两个 `migrate()` 的产品入口；受控停写、迁移确认、回执检查、两域部分成功的协调、后端切换和恢复激活仍需单独设计与验收。不能因两条 API 成功就把完整维护工具标为已交付。

默认入口仍绑定 OS Known Folder 下的 `last-workspace`，现有 open-once 不读写默认记录，也没有已支持的独立配置根。本轮没有先修改真实默认设置再回滚，或为了补验收而增加任意目录/复杂开发参数。下一步应围绕真实用户维护流程与配置隔离的产品需求做决策；不凑三项小功能，不再次观察已解释的同一退出，也不擅自执行正式发布。

本轮仅做必要的 opt-in Java 编译和上述集中运行，未执行全套测试。六份用户文档、main 和所有旧包保留；本地提交、不推送。完整 Git SHA、最终保护核对见本次项目回复和本地 `final-state.json`。
