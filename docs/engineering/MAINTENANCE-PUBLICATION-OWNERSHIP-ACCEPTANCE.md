# 配置发布后的活所有权与接管冲突

2026-11-10，承接 `f9d06a504ecb2bf04c3177ea92e76a9aa4242a74`。本轮完成一个可独立交付的真实模块：**直接 owner 发布配置后，继续保持并核对来源、备份、配置及目标事务**。没有把原生移交、Runtime 后端验收、安全完成协议拆成三个空实现；这些仍未完成，完整维护迁移保持 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN`。

前置证据：[目标绑定与配置恢复](MAINTENANCE-BINDING-CONFIGURATION-ACCEPTANCE.md)、[来源 owner](SQLITE-MAINTENANCE-OWNER-ACCEPTANCE.md)、[共同事务](COMBINED-MIGRATION-TRANSACTION-ACCEPTANCE.md)、[原生入口设计](MAINTENANCE-CUTOVER-ENTRY-REVIEW.md)。本轮没有更改普通 NativeGate、原生入口、Spring 启动、用户确认或默认 Profile 行为。

## 修复的具体断点

原 `SqliteMaintenanceOwner.publishConfiguration()` 调用完成后，发布方法会关闭目标 SHARE 锁连接和配置 pin；source 对象仍要求 `database.dpapi` 不存在。因此保留 owner 对象只能保留部分来源句柄，不能再通过 `requireHeld()`，也不能成为下一步无空档移交的有效前置条件。前轮主要验证配置恢复路径，没有覆盖这个直接 owner 发布路径。

现在包内 `MaintenanceConfiguration.Publication` 持有发布锁、受保护意图、暂存配置、实际发布配置及原目标连接。owner 在任何发布写入前接住该对象；只有本次对象实际完成发布、配置字节匹配、原连接完整核对通过，source 才从“配置缺席”转到“本次发布配置仍被 pin”。已有相同配置、旧意图、回执或调用者给定摘要都不能制造该活状态。

发布仍先持久写 `attempt.dpapi`，再 `CREATE_NEW database.dpapi`；没有替换、删除或恢复后来配置。后续 `requireHeld()` 同时检查来源和备份句柄／摘要、配置身份／密文字节摘要、发布锁及原目标连接中的绑定、两域数据和回执。发布失败会使 owner 后续使用永久拒绝，已经取得的资源保持到显式 `close()`。重复发布拒绝但不解除已成功取得的保护。

临时解密配置和密文工作缓冲在发布方法结束时即清零；后续核对依赖固定文件、摘要和真实连接，不把临时明文随着 owner 长期保留。没有新公共 activation API，也没有改变原配置恢复的观察／不重放结果。恢复使用相同发布实现并在返回时关闭局部对象，仍不提供后续 Runtime 接管能力。

## 所有权的实际边界

| 阶段 | 当前真实持有／失效行为 |
| --- | --- |
| `begin` 到完整备份、共同提交 | source 数据库、引用原文、相关目录与 owner 锁保持；备份和阶段记录逐步被 pin |
| 共同提交到配置发布前 | target importer 的事务已结束；后续准备／发布重新完整核对目标。不能声称整个间隔持续冻结目标 |
| 本次直接发布成功 | 来源、备份、记录、实际配置和目标 SHARE 锁事务继续保持；`requireHeld()` 仍有意义 |
| exclusion 失效 | owner 核对拒绝；维护 JVM 不会退回普通 Runtime。已有来源／配置／目标资源不会因核对失败自动关闭 |
| owner 显式关闭 | 释放本次内存／内核资源；不删除来源、备份、配置、目标行或阶段记录。持久启动 fence 保留。这是挂起，不是激活成功或安全完成 |
| 进程死亡 | 内核资源会释放，持久 fence 仍在；不能声称继续阻止外部 SQL／旧二进制写入，也不能凭旧记录恢复活所有权 |

阶段日志仍止于 `TARGET_VERIFIED`，另有配置 intent／attempt。没有新增 `ACTIVE`、`COMPLETE` 或 `SUSPENDED` 成功标志；不能从磁盘文件推断进程仍活着。用户后来数据不作为 close 的清理对象。

## 最小真实验证

最终证据目录 `.verification/maintenance-publication-owner-20261110-r2/`。三个生产类族限定编译，14 个生成 class，11 条沿用的 JDK `ExtendedOpenOption` 内部 API 警告；不是全量 Maven 构建。候选 Runtime SHA-256 `ABB9C5E0BD6E5B6764D5CFC6A3C64D640972E4084FEBB132CF803CFBF069FD7E`，JAR 审计通过，验收产品类从候选提取，没有 classpath 产品覆盖、Gate 反射替代或 mock 所有权移交。

新增 opt-in `bootstrap/tests/MaintenancePublicationOwnerProbe.java`，复用原生准备、独立 PostgreSQL 17.11、真实 DuplicateHandle／NativeGate／DPAPI／SQLite、既有共同 importer，以及旧探针仅有的 startup 模式。共同导入只是新合成环境的必要前置，没有重跑其旧失败矩阵或网页保存重开。

本轮实际执行一个完整流程，覆盖以下不同边界：

- 直接 owner 发布配置后，连续 `requireHeld()` 通过，重复发布拒绝后仍能核对。
- 父进程打开两份源数据库、知识原文、已发布配置的写句柄均得到 Windows sharing violation；原目标连接仍持锁，独立 PostgreSQL 连接的 UPDATE 得到 `55P03`。该 UPDATE 的条件为 false，不修改目标行。
- 显式关闭本次 child 的 exclusion 后，owner 核对和普通 Runtime 准入拒绝；父进程再次打开上述文件写句柄仍被拒绝，目标 SQL 写入仍被锁阻止。
- 显式关闭 owner 后，文件写句柄控制检查恢复可用但不写字节；源文件及配置 SHA 不变。验收程序在自己新建的目标中添加一行后续数据，读回原样；产品没有删除它。
- 新的普通启动 JVM 通过原 Gate 后被维护 fence 阻断，未加载配置或启动 Spring。来源、目标、配置和操作记录没有被 close 清空。

首轮相同闭环已通过；随后审查发现保留 Publication 会延长临时明文缓冲寿命，补上立即清零，因此仅对最终候选再执行上述一个必要流程。两轮均正常清理，没有历史失败伪装为成功。

最终合成 Profile `88a65dcd2d284ae584db31a26e83a106`，operation `3a86c6b9fc074fe8bb82e3f4e023e67e`，独立 PostgreSQL loopback 62029／system identifier `7706527206968606536`。fixture JVM 与两个 owner／startup 子进程正常退出，强制退出 0；临时 PostgreSQL 正常停止，初始化密码、实际／暂存的两份临时 DPAPI 配置清理。run guardian `7396` 于 `2026-11-10T13:01:41.6977104Z` 关闭空 Job 并释放 Session 1 `Local\Dot_KAG_Workspace_HeavyRun`。对应证据为上述目录的 `prepare/owned-profile.json`、`owned-cluster.json`、`publication-result.json`、`publication-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json`。凭据不进入报告或 Git。

未覆盖：发布中断电／进程崩溃、原目标连接中断注入、发布失败后持有资源的完整负面矩阵、新进程配置恢复全矩阵、原生父子接管、实际 Spring／HTTP Runtime 启动及安全结束。这些不因本流程通过而获得验收结论。

## 精确阻塞与下一步设计约束

**尚未执行的动作：把本次维护 owner 移交给正常 Runtime，并以其真实后端完成核对，再安全结束维护。** 这是现有启动架构冲突，非权限拒绝／待审批／结果不明：

1. `WindowsNativeGate.profileRoot()` 的普通 lease 不能与原生维护的写排他并存；`maintenanceAttempted` 永久拒绝同 JVM 转为普通 Runtime。本轮保持这两条保护，不能通过先关 exclusion 或重置标志“激活”。
2. `KnowledgeStore(DataSource, Path, Path)` 构造时执行 `reconcile()`，其中 `recoverInterruptedJobs()` 无条件进入写事务并发出 UPDATE。维护 Publication 持有目标业务表的 SHARE 锁；即便没有待恢复行，正常初始化仍会请求冲突的写锁。`PrivateKnowledgeDirectory.prepare` 和恢复清理也是启动期行为。仅在 `app.run()` 返回后检查后端，不能保护此前已经发生的恢复写入／文件操作。
3. 当前原生 `ProfileRecoveryCoordinator.TransferRuntimeExclusion` 只支持向自己启动的一个维护 child 传递资源，没有管理“仍持有来源的维护 child + 待接管 Runtime child”的完整协议；普通启动 fence 也没有可消费的活移交能力。

因此停止将这些边界直接接成普通启动。下一轮必须先明确并实现分阶段启动：原生父进程保留原排他与两个自己启动的 Process 对象；新 child 经原 Gate 接收真实 duplicate，并在旧 source owner 释放前取得并核对来源、备份、配置和目标的重叠保护；初始化阶段不得先执行恢复写入、开放请求或启动模型。配置版本和目标绑定须来自新 Runtime 的实际 DataSource／存储连接，新进程随机挑战与父进程持有的管道／Process 对象相互绑定；不使用 PID 重开、旧回执或固定 READY。

目标锁从维护连接转为 Runtime 可写行为的确切时点必须显式设计，不能一边保持 SHARE 锁一边期待恢复写入通过。只有来自真正接管后端的核对、来源保护接续及父进程确认齐备，才可写本次操作的受保护终态并允许相应正常启动；未知／失联保留 fence。关闭、取消和之后重开需要区分已提交／已发布／启动中／已核对阶段，不能删整个 evidence 目录、重放导入、恢复旧配置或清理后来数据。

这是接续实现要求，**不是已经实现或测试通过的协议**。本轮没有开通按钮或新增会被误认为可执行迁移的入口。真正需要的用户选择和确认继续保留。

## 本地交付记录

产品、限定探针和报告基础版本提交：`6b133f078bda9051ce98d521005d90a5a22637ea`，分支 `codex/knowledge-version-compare`。main 仍为 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。没有推送。

新开发包 `artifacts/PersonalAiWorkspace-maintenance-publication-owner-20261110.zip` 及同名解压目录已完成，`productionGo=false`。只更新最终验收候选的构建来源标记，再用本地缓存 `--no-restore` 重建 Desktop 内嵌 Runtime pin；前端、JDK、NativeBootstrap 不重建，没有再运行 EXE 或数据库验收。

| 交付核对 | 值 |
| --- | --- |
| ZIP SHA-256 | `6DB6D33DBBF5BE20D630D723AA94A8BBFF20530EB1CEDE4F258BA1CE972EBD5D` |
| Runtime SHA-256 | `A8376A5BD71735019F2DEDC8A0BA862C7BD0E6E0AE76735313A8A8D2CDDBDB51` |
| Desktop DLL SHA-256 | `0E766BF61C4F6A3D8BE8B98E14D1CDD3CD0E43A318D4277D3587BADDAF0F0A08` |
| payload／ZIP 条目／内嵌 pins | 933／934／420，全部实际字节核对通过 |
| 与验收候选比较 | 只有 META-INF/workspace-runtime.properties 构建来源标记变化，其余 JAR 条目实际字节相同 |

publish guardian `35408` 于 `2026-11-10T13:05:52.8107977Z` 关闭空 Job、释放共享锁，记录在最终证据目录的 `delivery-result.json`／`publish-cleanup.json`。所有已执行动作都有完成回执；没有自动审核拒绝、待审批或结果不明动作。六份既有未跟踪用户文档 SHA 和上一份 binding/config ZIP SHA 与基线一致；真实数据／默认 Profile／配置、既有数据库和其他旧包没有操作。不调用模型，不触碰独立 KAG 或 personal-finance-OS。

本轮未重试 Get-NetTCPConnection／18765、Docker、旧 Crashpad／私有目录／旧进程元数据、原生诊断或 KAG 脚本执行拒绝；未改 EFS、ACL、沙箱、注册表、网络／系统策略或账户组。
