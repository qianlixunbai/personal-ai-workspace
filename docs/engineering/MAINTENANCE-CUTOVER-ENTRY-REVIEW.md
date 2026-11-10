# 维护入口与数据库切换的实际边界

2026-11-10，接续真实离线导入提交 `1787cdca7167cbcf69e8a70f6da1b4fecb83966e`。本页是生产调用链与实现决策，不是完整用户迁移验收；设置切换的实际运行结果另行记录。

## 已有入口没有串成一条用户迁移流程

| 用户动作 | 生产实现 | 实际能力 / 缺口 |
| --- | --- | --- |
| 选择备份并恢复 | `MemoryBackupWindow.xaml.cs`、`WorkspaceBackupWindow.xaml.cs`、`KnowledgeBackupWindow.cs` | 各自的逻辑备份验证与新/空数据目录恢复；不会调用两个 SQLite → PostgreSQL `migrate()`，不提交 PostgreSQL 配置 |
| 选择器 → 备份与恢复维护 | `Program.ShowRecoveryMaintenance` → `ProfileRecoveryCoordinator` → `ProfileRecoveryWindow.ShowInspection` | “创建备份”“从备份恢复”按钮明确禁用；可检查前置条件，不能创建可执行迁移授权 |
| 网页设置 → 打开原生数据库设置 | `WorkspaceBridge` → `AssistantApp.OpenAsync` → `DatabaseSettingsWindow` | 真实生产入口，支持已准备 PostgreSQL 目标；不选择源备份、不建库、不迁移 |
| 检查连接与结构 | C# `NativeDatabaseSetup.ExecuteAsync` → Java `NativeDatabaseSetup.run` 的 CHECK | 固定 Runtime/JDK、原生准入后才通过 stdin 传秘密；只读校验受限角色和 V1/V2 结构，不运行 Flyway |
| 安全保存 | 原生确认 → SAVE! → `TrustedDatabaseConfiguration.create/load` | 只创建本 Profile 的 `data/database.dpapi`，CurrentUser 保护、CREATE_NEW、重新解密核对；不覆盖已有配置 |
| 重启 Runtime | 原生确认 → `DevelopmentRuntimeOwner.RestartAsync` | 停止本客户端拥有的 Runtime，下一代重新读配置；不负责源数据冻结、导入回执或两域协调 |
| 独立重开 | `Mmf3AcceptanceLaunch.OpenOnceArgument` → `AssistantApp` | 已支持的同一 Profile 隔离入口；`READY_OPEN_ONCE` 分支不读写 `last-workspace`，不是无参数默认入口验收 |

`SqliteWorkspaceMigration.migrate` 与 `SqliteKnowledgeMigration.migrate` 的生产调用点搜索仍为零；仅各自内部调用 importSnapshot。它们作为 offline primitive 的真实成功、失败保全已在[前轮报告](OFFLINE-POSTGRESQL-IMPORT-ACCEPTANCE.md)验证。

## 完整入口的准确阻塞

第一处缺口不是“按钮没连上”。`ProfileRecoveryCoordinator.SourceProducer` 在拿到本机锁、核对历史后仍返回 `Admission=BLOCKED / WriterCoverage=UNREGISTERED_WRITERS_UNPROVEN`；单独授权的来源连接核对也只接受该 BLOCKED 合同。Java `MaintenanceExclusion` 注释与实现同样明确：持有 Runtime/model 排他句柄不证明外部 SQL、旧客户端或模型执行全部停下，不授予备份/切换权限。本轮没有改掉这个合同，也没有对个人 Profile 再读历史、凭据或进程。

第二处缺口是跨域与跨介质的一致性。两个 migrate() 各自取得源锁、备份、提交目标事务、释放源锁；没有一个生产 owner 同时覆盖两个源库到配置切换的完整时段。Workspace 成功而 Knowledge 失败时，现有回执只能分别说明各域状态。两个独立提交与本机 DPAPI 文件创建不是一个事务，不能因为它们各自通过就把整体描述成原子迁移。PostgreSQL 的[事务保证](https://www.postgresql.org/docs/17/tutorial-transactions.html)只覆盖相应事务块，这一跨组件结论来自本仓库调用链。

第三处缺口是启动与恢复语义。保存配置后，旧 Runtime 仍使用 SQLite，直到明确重启；这本身是已有设置行为。若把在线窗口直接改成“迁移”，迁移完成后到重启前仍可能产生新的源写入。部分成功或结果未知时，不能用删除旧配置、重试导入、自动切回 SQLite 来猜测权威来源。无参数入口仍绑定真实 `last-workspace`，不能临时改它再回滚完成隔离验收。

## 实现决策

**值得补齐产品维护能力，但不把完整迁移塞进在线数据库设置窗口。** 本轮先验证已存在的配置绑定和重开层；迁移入口应复用选择器的离线维护 owner，并保留明确的来源与新目标。

参考了 GitHub 上的 [pgloader](https://github.com/dimitri/pgloader)：它解决数据加载到 PostgreSQL 的问题。当前已验证的 importer 还承担 Workspace 业务不变量、Knowledge 原文、私有目录与原生身份；新增 pgloader 依赖不能替代这些约束，也不能补齐本机来源授权和 DPAPI 激活。本轮没有下载、复制或运行该项目。

后续实现应集中在以下一条实际产品流程，而非增加通用路径参数或验收专用开关：

1. 在原生维护 owner 中选择并绑定同一受信来源和全新目标；明确 SQLite 来源与 PostgreSQL 来源的不同停写证明。无法证明时显示准确阻塞，不补旧历史、不终止未知服务。
2. owner 持有全部必需来源排他与文件 pin，完成同一冻结时段的两域备份/摘要；使用既有 Native Gate 与实际来源解析，不从外部传入 `approved=true` 或任意目录绕过准入。
3. 为两域导入增加共同目标事务/组合回执的实际实现。现有公开方法自行建连与 COMMIT，不能直接外包一个空事务来假装原子性。必须在同一目标身份上明确哪些数据已提交。
4. 在目标提交可核对后才发布配置，并保留受保护的阶段记录，绑定来源摘要、目标集群/数据库/角色身份与配置摘要。重启失败保持可核查状态，不自动回放写入或改源库。
5. 使用现有 open-once 打开候选并核对实际 Runtime 后端与关键内容，再向用户提供独立的默认工作区选择。保留原来源和备份，不默认清理；真实默认选择另行验收。

这不是要求新增系统服务、HBA/防火墙、账户组或安全策略。若实现最终依赖这些变化，超出本轮隔离范围，必须报告确切动作/目标/原因；不得绕过已有 BLOCKED 合同。

本页没有宣称上述 owner、组合事务或恢复日志已实现。真正缺少的是生产来源授权与跨域切换协调，不能用本轮新合成夹具的“来源已知”替代面向用户工作区的证明。
