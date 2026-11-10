# 维护目标绑定、配置恢复与启动阻断

2026-11-10，承接 `7614c01f3a9fa1a7d89104c766539e5dadd294f4`。仅在 personal-ai-workspace 实现三个层级：持久目标绑定、受保护配置意图及有限恢复写入、普通启动的未完成维护阻断。**没有实现原生激活所有权移交，没有开通完整维护迁移。** 原 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN`、NativeGate、模型锁和 runtime-admission 排他不放宽。

前置设计与证据：[入口边界](MAINTENANCE-CUTOVER-ENTRY-REVIEW.md)、[共同事务](COMBINED-MIGRATION-TRANSACTION-ACCEPTANCE.md)、[来源 owner](SQLITE-MAINTENANCE-OWNER-ACCEPTANCE.md)。本轮不重跑这些层级的完整矩阵或正常 EXE 保存重开。

## 实现与准确边界

`MaintenanceTarget.Binding` 绑定 PostgreSQL system identifier、database/application-role/database-owner/schema/receipt-table OID，以及本次随机 operation ID。独立的 `workspace.maintenance_import` singleton 回执与两域数据在同一连接、同一事务插入。恢复核对身份、操作回执和两域完整数据；数据库服务正常重启不会再因 postmaster 启动时间变化而丢失绑定。原不带绑定的离线 API 保持原同实例观察；旧阶段记录不会被升级为激活许可。

目标扩展是 `src/main/resources/db/workspace/maintenance-target.sql`，需明确为独立新目标准备，不是自动 Flyway 升级，也不由 importer/启动执行 DDL 或 GRANT。应用必须具有只读 system identifier 能力，回执表仅允许 SELECT/INSERT，不能拥有表／schema、成为相关 owner 成员或拥有表／列级 UPDATE、DELETE、TRUNCATE。缺少结构、权限或身份即停止。本轮只在新合成库授予这些权限，没有修改既有数据库。依据 [PostgreSQL 17 控制数据及权限函数](https://www.postgresql.org/docs/17/functions-info.html)。system identifier 不是远程硬件证明；物理克隆可能携带同一身份，不能据此宣称防管理员克隆／篡改。

`MaintenanceConfiguration` 只支持本次已记录的 `ABSENT → 配置密文字节摘要`。owner 保持源／备份／阶段句柄，用标准配置解析器检查凭据并核对绑定目标，再将普通 CurrentUser DPAPI 配置暂存于本次操作专属私有目录。受保护意图绑定 Profile、根目录真实文件身份、operation、前后状态及完整目标期望。不会读取或临时修改真实配置。

发布前保持来源句柄和目标业务表 SHARE 锁；不可变回执表使用 ACCESS SHARE，并由应用无修改权限的约束保护。原连接在意图／写入前后再次完整核对；连接失效或异常不能按成功处理。持久 `attempt.dpapi` 必须先于 `CREATE_NEW database.dpapi`。没有替换、删除旧配置、自动切回 SQLite、回滚用户配置或重放导入的路径。

新进程恢复必须重新经过原 NativeGate、接收实际排他句柄，重读受保护意图，再复核原 owner 阶段、来源、完整备份与目标，且重新取得并保持来源句柄。配置不存在、尚无发布 attempt、阶段确认为 TARGET_VERIFIED 且内容不变时，才可完成那一次发布。COMMIT_ATTEMPTED/COMMIT_UNCONFIRMED 即使只读匹配，也不自动升级为发布权限。存在 attempt 后配置缺席一律 `PUBLICATION_UNCONFIRMED`，不能区分崩溃前未写与用户后来删除，因此不重建。已有配置只比较密文摘要，不解密／覆盖；相同字节只报告“记录中的配置存在，激活仍阻断”，不证明历史写入者。变化或半写配置原样保留。

`MaintenanceStartupFence` 在普通 Runtime 已取得原运行 lease 后、读取凭据／Spring 启动前检查两个维护注册目录。任何操作记录均要求尚未实现的原生移交，不能靠进程退出、关闭 exclusion 或存在配置解除。维护 JVM 的 `maintenanceAttempted` 仍永久禁止转为普通 Runtime。这个模块是中断后的启动保护，**不是成功激活协议**。同一 Profile 在维护记录保留期间不能作为普通工作区重开；本轮只在合成 Profile 使用，未影响用户 Profile。

## 最小真实验收

最终证据目录 `.verification/maintenance-activation-20261110-r3/`。候选 Runtime SHA-256：`AAA8EB8551CAD18CF724C17C36841A18C2566031C34A7B6DD6AF11497406DBC8`。6 个生产类族限定编译成功，13 条沿用的 JDK `ExtendedOpenOption` 内部 API 警告；不是全量 Maven 构建。候选 JAR 审计通过，从候选提取运行产品类，无 classpath 产品覆盖或 Gate 反射替代。

合成 Profile `4a57921eeb3d41ef8dfbf6936b0b3e86`；operation `3a6427e04b394888810271b14fa319ca`。独立 PostgreSQL 17.11：loopback 51421，system identifier `7706521612025580960`。由现有原生 helper 准备，真正 DuplicateHandle、DPAPI、SQLite 与 PostgreSQL；不调用模型，不查询系统端口，不访问旧拒绝范围。

| 新层级检查 | 结果 |
| --- | --- |
| 一次绑定共同导入，错误 operation 只读检查 | 导入成功，错误 operation 返回 TARGET_CHANGED |
| 暂存配置后关闭 owner/JVM，正常停止并重启独立 PostgreSQL | 新进程重读阶段，持久身份／数据／回执一致 |
| 暂存后目标新增一条 Memory | 恢复拒绝发布，配置仍不存在，新增行内容原样读回 |
| 恢复原合成目标后完成配置发布 | 新进程成功，未重放导入，再次观察同一配置字节不变 |
| 验收程序写入自己的后续配置哨兵 | 恢复返回 LATER_OR_PARTIAL_CONFIGURATION_PRESERVED，字节不变 |
| 验收程序删除自己的哨兵，发布 attempt 已存在 | PUBLICATION_UNCONFIRMED，不重建配置 |
| 原维护排他释放后，独立普通启动进程 | 原 Gate 通过后被维护启动保护拒绝，未加载凭据或 Spring |

目标新增数据的拒绝检查后，**仅验收程序**删除自己刚插入、ID／内容精确限定的单行，以继续独立成功路径；产品恢复没有删除目标行。配置哨兵也仅由验收程序创建／清理。没有把这些合成整理动作当作产品恢复行为。

三个验收 Java 子进程正常退出，强制退出 0；临时库正常停止，初始化密码、暂存 DPAPI 凭据和合成配置已清理。run guardian `24568` 于 `2026-11-10T12:40:01.443709Z` 关闭空 Job 并释放 Session 1 `Local\Dot_KAG_Workspace_HeavyRun`。`activation-probe.log`、`activation-result.json`、`activation-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json` 均有对应回执。

未覆盖：真实断电／进程崩溃时序、发布中目标连接断开注入、受限角色权限负面完整矩阵、直接 owner.publishConfiguration 独立路径、用户原生确认／激活 UI。恢复写入调用的是同一内部发布方法，但不能据此声称这些未测场景已经验收。

## 历史尝试

首轮 `.verification/maintenance-activation-20261110/` 已通过重启绑定、配置恢复及启动阻断。随后审查补上列级权限／owner 成员拒绝和发布前后原连接核对，故需要修订候选的一次限定验收。r2 编译因新增方法漏声明原快照校验的 Exception 失败；验收包装在构建失败后误被启动，创建了新合成 Profile／临时库，但因 classpath 不存在而在任何源填充／导入／配置写入前停止。临时库正常停止、凭据清理、Job 0、锁释放。已修正编译声明，并在最终 Accept 包装增加构建完成／成功／classpath 与候选回执前置检查，r2 现场保留，不计成功。

## 仍阻塞的动作与接续

具体未完成动作是：原生维护入口完成本次选择／确认后，把来源停写、目标排他及配置所有权无空档移交给经原始准入的新 Runtime，核对真实后端与数据，并持久记录激活／退出结果。当前没有该 parent/child 协议，也没有成功激活后解除持久 fence 的 API；不能用固定真值、磁盘记录或关闭旧排他代替活证据。安全取消／结束后的记录保留与未来再次迁移策略也未实现。

因此完整用户维护迁移、个人恢复、默认入口和正式发布仍未验收。这里是产品协议缺口，不是本轮新的权限拒绝；不需要放宽 EFS、ACL、沙箱或系统策略。旧 Get-NetTCPConnection/18765、Docker、Crashpad／私有目录、旧进程元数据和原生诊断拒绝均未重试。main、六份未跟踪用户文档、真实记忆／知识、默认配置／Profile／数据库、持久凭据和旧包保持不动；不推送。

三个完成层级以同一产品提交交付，配置发布与启动 fence 必须一起保留；不可只回滚 fence 而保留发布 API。完整 SHA、包哈希和最终资源核对见下方本地交付记录。

## 本地交付记录

产品、限定验收探针及本报告基础版本已提交：`ccfffa2d463957713fd2bf081f785c4059307038`，分支 `codex/knowledge-version-compare`。main 仍为 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，无推送。

新包 `artifacts/PersonalAiWorkspace-maintenance-binding-config-20261110.zip` 及同名解压目录已完成；`productionGo=false`。只对最终验收候选更新构建来源标记，用本地缓存 `--no-restore` 重建 Desktop 内嵌来源 pin；前端、JDK、NativeBootstrap 未重建。没有再跑 EXE 或数据库验收。

| 交付项目 | 核对值 |
| --- | --- |
| ZIP SHA-256 | `0EAC0591CB9751D756638BF97E373C41B31948B9258550E2EB27B0127B259827` |
| Runtime SHA-256 | `402CAD90AC97294D71855C853F139B03BB9CD59B460919F9CDA0FF83FFB785E9` |
| Desktop DLL SHA-256 | `33674A8084436D28753D0D036876F8A7AF3D9A42E2E609F0F47B696E40364FAE` |
| payload／ZIP 条目／内嵌 Runtime pins | 933／934／420，全部实际字节校验通过 |
| 与验收候选比较 | 只有 META-INF/workspace-runtime.properties 的构建来源标记变化，其余所有 JAR 条目字节一致 |

publish guardian `34588` 于 `2026-11-10T12:43:41.6419542Z` 关闭空 Job 并释放共享锁；`delivery-result.json`／`publish-cleanup.json` 在 r3 证据目录。当前所有已执行工具动作都有完成回执，无权限审核拒绝、待审批或结果不明动作。六份用户文档 SHA 和上一份 owner ZIP SHA 与本轮基线一致；其他旧包没有写入。没有读取禁止的原始会话存储。
