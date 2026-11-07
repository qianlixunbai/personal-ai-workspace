# Recovery 来源生产者：受保护配置绑定与合成跨进程备份

## 最新阶段：prepared 配置持久写入、真实连接与日常 Store 权限核验

基于本地 `16a9c8e`，本轮选择设计 §4 的 prepared 配置 owning 接口，完成“独立来源 → 新 canonical 目标恢复 → 固定 `data/database.dpapi` → 磁盘重读后的真实 app 连接 → Store/权限核验”的合成阶段。**没有发布 READY 或激活；合成收尾仍撤销两角色登录并清密，留下的配置不能当作可用的日常配置，更不能继续激活此候选。** 完整停写中旧包、跨会话/手动 Java、未知模型执行的归属仍无可信历史可补证；本轮未改写这些条件或修改真实环境来制造证据。

`TrustedDatabaseConfiguration` 新增 package-private `createPreparedRecovery/readPreparedRecovery`，由 `NativeProfileRecovery` 的受限 owning 方法调用。它们只能使用已真实 Gate/原生身份/独立登记核验的 `PreparedRecoveryTarget`，且必须经过本次进程的 restored 状态；磁盘 marker 不能重建这个能力。只写固定 `data/database.dpapi`，复用原来的严格七字段解析与 DPAPI CurrentUser 格式，要求数据库、app 角色、端口与候选/来源绑定一致，不能写 owner/Finance 配置、任意路径或源配置。CREATE_NEW、force、私有 ACL、原生文件身份和前后目录复核均保留；普通 public load/create 的当前 Profile Gate 语义不变。

新的 `PreparedDatabase` 是持有配置只读 pin 的连接提供者，不返回口令或 Spring PropertySource，不接受凭据覆盖或 unwrap。每次连接前核对配置字节摘要/身份和候选能力；真实连接验证 PG17、loopback/端口、DB/角色 OID、session/current user、角色能力/成员关系和权限合同。管理员连接再通过 `pg_stat_activity` 的 backend PID、client port、datid、usesysid 关联实际 app 连接，并核对 system identifier、DB owner、角色 operation receipt 和已消费的数据库 receipt。结果只记录身份、配置摘要及 `WORKSPACE_APP_V1`，不是授权 receipt。关闭配置句柄后再次独立读盘并连接，核对同一 observation，避免只验证内存 JSON。

日常 app 授权只作用于本次已恢复目标：先核对 owner/目标/operation/端口/receipt，事务内设置固定 17 表合同。业务表获所需 CRUD；不可变消息/选择/revision 不授 UPDATE；`write_lock`、`knowledge_control` 仅 SELECT/UPDATE；metadata、Flyway history、两张迁移 receipt 仅 SELECT。随后逐表核对所有 PG17 table privileges 和 grant option，拒绝 schema/database CREATE、TEMP、转授权、owner membership、高权限角色、其他可连接数据库及 public schema 权限。原合成只读验证角色入口未改成通用写角色。

仍只扩展已有 Java `RecoverySourceProducerProbe`，没有新增测试套件。为定位实际问题，本阶段共有两次保留的失败尝试，最后一次新夹具成功：

- 第一尝试 `.verification/recovery-prepared-configuration/` 错把 `pg_control_system()` 当作默认禁止 app 执行的函数，真实连接被新增条件拒绝。查阅 [PG17 系统函数 ACL 初始化](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/catalog/system_functions.sql)、[只读实现](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/utils/misc/pg_controldata.c) 及 [GRANT 默认语义](https://github.com/postgres/postgres/blob/REL_17_STABLE/doc/src/sgml/ref/grant.sgml) 后，移除这条错误的新条件；没有额外 GRANT，也没有修改 pg_catalog 或既有服务权限。实例核验仍由真实后台关联完成。
- 第二尝试 `.verification/recovery-prepared-configuration-fixed/` 已完成配置/权限连接、Memory 写入及 Conversation turn 写入，探针却传入分页 limit=20，超过既有 `MAX_LIMIT=10`，被领域 API 正确拒绝。仅改探针使用现有常量，产品代码/JAR 不变。两次失败目标、配置及记录均保留，不覆盖、不原地重放；各自专属 PG 已停止。
- 最后一次 `.verification/recovery-prepared-configuration-verified/` 完整通过。验证配置 pin 阻止写/删、CREATE_NEW 拒绝重复绑定、错误 DB OID 拒绝、workspace/public DDL、TEMP、metadata/history/receipt 和不可变消息 UPDATE 均以 `42501` 拒绝。沿真实重读凭据执行 Memory 创建/修改/归档/恢复/删除，Conversation 创建/Memory selection/终态消息/改名/归档/恢复/删除，删除本次临时记录后原 Memory snapshot 不变；随后沿同一凭据执行现有 Knowledge 读回、精确 preview 和索引重建。未调用 TaskManager 或模型；探针中的消息均明确为合成数据。

成功目标 `8343ff3cc2b04114a09f230090cce0b9`，operation `ac59da64019a4f159f1b5ab9f45e6743`，数据库 `personal_ai_workspace_b3_ac59da64019a`（OID 16592，owner/app OID 16590/16591）。配置 SHA-256 `dd73198dd0a91032be32a97048977b6cc23ab0d87d01b9e05f72643867779ef3`。来源 producer/consumer PID `29196/26272`；专属 PG PID `44848`、端口 `54080`、夹具 `C:\PAW-SourceProducer-64568636c7c042cb95654aee2f42859b`。PG 已按进程身份核验并停止，postmaster.pid 不再存在。源数据库恢复时不可连接、原合成 Profile 路径不存在，恢复后原 source snapshot/Finance sentinel 不变；双目标角色最终 NOLOGIN/PASSWORD NULL、临时明文 backup 清理、目标无 JNA、普通启动与重放拒绝，均沿原探针继续核验。

真实产品 JAR 的只读维护入口本轮仍返回 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN`、`ObservedExitedChildren=null`；新 authority 没有数据库配置，`SourceState=CONFIGURATION_UNAVAILABLE`。配置恢复仍由同一真实 authority Gate 下的专用合成探针调用，不是已开放的产品恢复命令。合成来源/目标登记没有进入个人命名空间。

必要构建为 Java 离线打包/实际 JAR 审计、原有 Java 探针编译及精确 manifest 的 .NET 探针构建（0 warning/0 error），未跑全套测试或模型。最终产品 JAR SHA-256 `8a9e87f75d819dc22f3a1df67fc2cf0fff27cd5d5eff7c3fe3edf21936cc76b2`；最后修复仅修改探针分页参数，复用该 JAR。helper 字节及 pins 未变。每次顺序运行，Java 384 MiB/2 核，PG shared_buffers 16 MiB/12 connections，最终运行前约 15.6 GiB 空闲内存；未启停 KAG、共享 PG 或任何既有服务。

成功证据目录含独占 runner、manifest、构建日志、`product-maintenance-observation.json`、`owned-prepared-target.json`、`synthetic-recovery-evidence.json`、`fixture-result.json`、`final-boundary-evidence.json`。未验证断电、网络中断、部分写入/CREATE 未知结果；没有将正常 finally 清理当作崩溃保证。下一关键阶段仍需可信个人来源/完整停写，及在全部持久化与关闭/明文清理完成后保留新 app 凭据、独立受保护 READY、显式激活与目标完整 Gate/Store/偏好保存的 owning 流程。不能拿本轮已撤销的合成凭据或 observation 冒充这些前置条件。五份未跟踪设计文档原字节未变；未推送、合并 main 或部署。

## 最新阶段：真实维护 Gate 下的 prepared target 固定 data 恢复

基于本地 `622b5e4`，本轮把独立来源消费接到全新 canonical Profile 的固定 `data`，不再把临时 `staging/candidate` 当作 Profile 恢复结果。已实际完成合成来源到未发布候选的闭环；**个人恢复仍 BLOCKED，没有 RECOVERY_READY、目标登录配置或显式激活**。合成登记始终留在独立命名空间，不能用于个人 `ReservePlan`。

实现集中在已有恢复链路：

- NativeBootstrap 增加只读 `VerifyPreparedRecoveryTarget`：由 targetId 重算 Known Folder 根，核对标准八目录、私有 ACL、canonical root/data 原生身份，并拒绝已发布 JNA 的布局。只返回目录证据，不成为 Runtime Gate。helper 重新构建，并同步 Desktop/Java 的精确 EXE/DLL pin；deps/runtimeconfig pin 保持原值。
- 原生 coordinator 的私有合成入口只在真实 authority 已核验、消费者完成 AEAD/manifest/source 验证后运行。先持有目标 SingleInstance，再于 `Recovery/synthetic-targets/<targetId>` 发布 DPAPI `plan`，记录 operation、新 Profile、来源登记/payload/manifest 摘要、计划 DB/角色/receipt；之后才创建 Profile，记录 root/data 身份并发布 `prepared`。不调用目标 Native Provision，不产生用户授权。
- `PreparedRecoveryTarget.openSynthetic` 要求实际维护排他句柄、真实 Gate 和上述 helper，严格核对 DPAPI 记录、来源绑定、计划命名、root/data 原生身份及空目录。持续固定祖先/目标目录和记录。`claim()` 通过 CREATE_NEW、force 和不覆盖发布生成独立 `attempt.dpapi`，先于目标 SQL/数据写入；领取后、未知结果或出现额外文件均不能重开重放。
- 跨 Profile 的私有 ACL 操作仅接受该不可直接构造的候选能力：先重核 helper 身份、维护排他和持续固定的 Known Folder 祖先句柄，只作用于该候选及其登记目录。普通 `WindowsPrivateAcl` 的原有祖先 ACL 规则未放宽。恢复在固定 `data` 创建私有 Knowledge 子树，沿既有 v3 恢复、Store/源文/索引读回和双角色禁登清密流程运行；临时 backup 清理后只保留候选与有限验证记录。
- C# 与 Java 普通启动额外拒绝 `synthetic-targets` 内已登记目标；目标 JNA 持续缺失，旧 helper 的普通 `VerifyProfileNative` 同样拒绝。没有把 `RESTORE_VERIFIED.json` 当成 READY。

首次实际联调在领取前拒绝：`attempt.dpapi`、目标恢复文件和目标 SQL 意图均未产生；失败候选保留。只读 ACL 检查确认此系统的 AppData/Local 祖先包含通用路径规则拒绝的替换权限，原 Runtime 的 Known Folder 固定句柄上下文原本只覆盖 authority Profile。修复为上述候选专属能力，未更改任何现有目录 ACL，未覆盖或重放失败目标。首次证据位于 `.verification/recovery-prepared-target/`，自建 PG 已停止。此前还有一次 PowerShell 版本不符合预检要求的启动失败，未运行夹具。

修复后使用全新夹具 **1 次**执行已有 `RecoverySourceProducerProbe.cs/.java` 的 `prepared` 模式（原四参数模式保留；第五参数为 `prepared`）：

- 同用户 WMI 启动 PowerShell 7，经 canonical Known Folder/非重解析路径预检。原生探针新建专属 authority，使用本轮精确 manifest 的真实应用 JAR 调用产品 `InspectSourceReadinessAsync`。返回 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN`，`ObservedExitedChildren=null`；新 authority 无数据库配置，因此 `SourceState=CONFIGURATION_UNAVAILABLE`。这不是完整停写证明。
- 独立生产者退出后，新消费者经真实 Native Gate 和维护句柄交接读取 DPAPI 合成来源。原合成 Profile 移到保留目录，源合成数据库 `ALLOW_CONNECTIONS false`；恢复不访问原业务库。验证缺登记、篡改密文并重算 completion、写/删除已 pin 文件、错误口令不创建 DB/角色等拒绝条件。
- 全新 canonical 目标 `0928ebcc1ecf469889875e44c92b773b`，operation `c1e6380e09814f87926bed20bc38e781`，新库 `personal_ai_workspace_b3_c1e6380e0981`。固定 `data/knowledge` 恢复非空 Memory、终态 Conversation、指定 Knowledge source 并重建索引；无 `staging/candidate`，Finance 未进入目标。原 source snapshot/Finance sentinel 不变，两个目标角色均 NOLOGIN/PASSWORD NULL，临时明文 backup 已清理。
- 重复 staging、已领取 prepared target、普通 Java/C# 启动均被拒绝；目标无 JNA，普通 NativeBootstrap 返回 STOP/10。`activation=false`。恢复消费者使用本轮编译类的专用探针，**不是已开放的产品恢复命令**；产品 JAR 真实走过的是维护只读入口。

成功夹具 `C:\PAW-SourceProducer-25f505c9ff2f457fba97a08d9994456e`，PG17 端口 `56278`，自建 PG PID `2644`，生产者/消费者 PID `21252/36228`。自建 PG 按 PID、启动时间、data directory、端口及可执行路径核对后停止。JVM 顺序执行、384 MiB/2 核，PG shared_buffers 16 MiB/12 connections；预检约 15.7 GiB 空闲内存。未启停 KAG、共享 PG、模型或其他既有服务，未访问真实用户 Profile/数据库。

必要验证仅为 helper/桌面探针构建、Java 离线构建及实际 JAR 审计、现有 Java probe 编译、上述失败定位与修复后的单次闭环、`git diff --check`。没有新增测试套件、跑全量测试或模型任务。实际受信应用 JAR SHA-256 为 `4a27153bc5a901e0a9f4c4fe66b0bd67b7084443edc47e05c5392c35efbb9866`；helper EXE `5e0fb764990537a9d131cd3b3140834fa2462205d74f3e5ef4219abde1a6c99d`，DLL `58877d638691495b46cdb35a5bf8030b653660da76b3cc836ef321ba8e58b256`。实现前参考 [.NET 私有目录创建实现](https://github.com/dotnet/runtime/blob/main/src/libraries/System.IO.FileSystem.AccessControl/src/System/IO/FileSystemAclExtensions.cs) 与 [PostgreSQL 17 pg_restore 文档](https://github.com/postgres/postgres/blob/REL_17_STABLE/doc/src/sgml/ref/pg_restore.sgml)，没有以目录存在或普通 rename 推导恢复已发布。

可复查的本地执行材料在 `.verification/recovery-prepared-target-fixed/`：`run-isolated-recovery.ps1`、`invoke-prepared.ps1`、`context-preflight.ps1`、`SourceProducerProbe.csproj`、精确 `runtime-sources.local.json`、构建日志、`product-maintenance-observation.json`、`owned-prepared-target.json`、`synthetic-recovery-evidence.json`、`native-source-evidence.json`、`fixture-result.json`、`final-boundary-evidence.json`。脚本只接受新的独占夹具；不能重放已有 runner/候选。脚本包含此机器的工具位置，迁移执行环境须重新建立可信 manifest/目录预检，不能直接复用本机哈希结论。

完整停写仍缺覆盖旧包、跨会话/手动 Java、未知远程模型执行的可信历史和执行归属；mutex、没有监听端口、没有 guard 文件或本次子进程退出都无法补齐。原生维护继续拒绝个人变更，不能清 guard、取消/kill 原执行或伪造“零历史”来放行。下一关键合同是可信来源生产授权与完整停写覆盖；另需 prepared 配置 owning 接口、日常 app CRUD 权限/真实新凭据读回连接、清理完成后 DPAPI READY，以及用户显式激活后的完整目标 Gate/Store/选择保存。未验证断电、网络中断或部分 CREATE/发布未知结果；失败/中断仍留候选且禁止自动重放。五份未跟踪设计文件保持原字节；未合并 main、部署或推送本轮提交。

## 最新阶段：来源登记跨进程消费，源库不可连接时恢复新合成目标

基于本地 `8429134`，仍在 `codex/recovery-v3-workspace-core`。核对权威设计后发现，旧恢复依赖同一 Java 进程中的 `Completed`，即使原生 DPAPI 来源登记已经发布，生产进程结束后也不能恢复；旧目标准备还必须连接原业务库。这不符合“有完整维护 authority 和独立来源登记即可恢复，不要求原业务库在线”的合同。本轮把这段实际闭环接通，**个人维护仍为 BLOCKED，未发布 READY 或激活**。

新增 `persistence/RegisteredRecoverySource`：正常读取入口先经过原有 `WindowsNativeGate.maintenanceProfileRoot(exclusion)`，仅从 authority 推导的固定 `Recovery/sources/<backupId>/source.dpapi` 读取；不访问登记中的原 Profile、配置或源数据库。它固定祖先目录、登记和密文句柄，核对 canonical 文件身份、无链接、DPAPI CurrentUser/独立 entropy、严格字段/类型/版本、来源种类、角色收尾及 payload 实际字节数/SHA-256，并保留登记密文摘要。原生登记文件允许继承其已核验私有父目录的 ACL，新增的限定检查复用原有 ACL 实现，不重写权限。关闭、缺失、pending/额外文件、绑定不匹配均拒绝；无“由口令或包旁 SHA 重建登记”的路径。

合成消费者只能通过私有 OS 探针缝读取独立的 `synthetic-sources`、`synthetic-source/v1` entropy 和 v2 包装记录，要求原合成 kind/state。其权能不能进入个人来源或 `ReservePlan`。正常受信消费者入口代码已加入，但**本轮未通过真正维护入口验证个人登记读取；没有个人来源生产者，也没有从合成结果晋升的登记接口**。

`PortableEncryptedBackup.decryptRegisteredSynthetic` 在登记和密文持续 pin 的条件下完成原有两遍 AEAD、v3/TOC/catalog/Knowledge 验证，另核对独立登记的原始 manifest 摘要与 logical ID。之后才生成恢复所需的内存权能。`WorkspaceRecovery.restoreRegisteredSyntheticTarget` 才连接登记端口的 `postgres` 管理库，核对 PG17 和 system identifier，创建全新 B3 隔离目标；不连接原业务库，也不复制原配置。新 DB OID/名称不得等于登记中的来源。CREATE 前持久计划额外绑定 `sourceRegistrationSha256`；恢复、Store 读回、索引重建、凭据禁用/清密、仅清理临时 backup 和未知结果不重放沿用原内核。最终仍只得到 `RESTORE_VERIFIED / IDENTITY_REBIND_REQUIRED / WINDOWS_ADMISSION_PENDING`，两个目标登录均禁用。

只扩展既有 `RecoverySourceProducerProbe.cs/.java`，实际执行 **1 次**组合流程：

- 隔离源绑定、备份/源短期角色回收、原生独立核对及 DPAPI 发布后，备份 JVM 和原生 owner 均结束；新消费者 JVM 重新读取真正落盘的原生登记，未手写成功记录或恢复 `Completed`。
- 将本次自建合成 Profile 移到同一隔离应用目录的 `retained-source-*` 保留原字节，原 Profile 路径不存在。只对本次新合成源数据库设置 `ALLOW_CONNECTIONS false`，实测连接以 SQLSTATE `55000` 拒绝；整个恢复期间不停止 PG 服务、不访问原业务库。验证后恢复该合成库的连接设置并核对原 Workspace snapshot 和 Finance sentinel 不变。
- 缺来源登记拒绝；复制登记并改变另一份合成密文、重算其包旁 completion SHA，仍因独立 payload 绑定拒绝；实际登记/密文写句柄被拒绝；错误口令不创建 DB/角色/目标计划。
- 新库恢复非空 Memory、终态 Conversation、Knowledge source 与索引；Finance 不进入目标；两目标角色 NOLOGIN/PASSWORD NULL；登记摘要进入持久计划；同 staging 不重放，临时 backup 已清理，关闭来源权能不可复用。合成记录仍不能预约产品目标。

本次夹具 `C:\PAW-SourceProducer-1821be38815446dc9a1bcccf175edc5d`，PG17 端口 `55817`，生产 JVM PID `20620`、消费 JVM PID `48228`。自建 PG PID `42616` 经 PID/启动时间/data directory/端口/程序路径核对后停止，最终该进程及 postmaster.pid 均不存在。PG 使用 16 MiB shared_buffers/12 max_connections，JVM 两核/384 MiB，顺序执行；未操作 KAG、共享服务或真实用户数据库/Profile。

必要验证仅为本次四个生产 Java 类和既有 Java probe 的定向 javac、原生手工 probe 构建（0 warning / 0 error）、上述一个集成流程及 `git diff --check`。Java 有既有同类 `ExtendedOpenOption` 内部 API 警告；未重建完整应用 JAR、Desktop 产品、前端或运行全套测试。实际加载的 Desktop 复用此前产物，未修改其本轮源码；实际消费/恢复运行的是本轮编译类。新消费路径没有再次实测真实 Native Gate，不挪用上一阶段证据。本轮不宣称已做断电、网络中断、部分 CREATE 未知结果验证，也不宣称 finally 覆盖崩溃。

证据均在 `.verification/recovery-registered-source/`：`javac.log`、`dotnet-build.log`、`native-source-integration.log`、`native-source-evidence.json`、`synthetic-recovery-evidence.json`、`source-binding-evidence.json`、`owned-fixture.json`、`fixture-result.json`、`final-boundary-evidence.json`、`protected-design-hashes.json` 及 runner/csproj。五份未跟踪设计文档 SHA-256 首尾相同。实现前参考 [PostgreSQL CREATE DATABASE 源文档](https://github.com/postgres/postgres/blob/REL_17_STABLE/doc/src/sgml/ref/create_database.sgml) 和 [OpenJDK 21 WindowsChannelFactory](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsChannelFactory.java) 的现有事务/文件共享语义；未修改第三方实现。

**仍缺的产品闭环：** 完整停写覆盖（已登记 Runtime 的自然退出、旧包/跨会话/未登记 writer 与模型执行状态不能仅由“未观察到”推定）、个人来源生产及其实际原生登记消费、prepared Profile/日常 CRUD 凭据绑定、受保护卷上的个人恢复、RECOVERY_READY、显式激活和正常 Desktop 读回。现有缺历史的 `ObservedExitedChildren=null`、`UNREGISTERED_WRITERS_UNPROVEN` 均保持原义；本轮来源读取不能给这些条件补证。后续应在同一 owning 生命周期中解决完整停写/来源生产，再推进目标发布与用户显式激活，不应把本轮合成权能或单独密码当作个人 SQL 准入。本轮仅本地提交；未推送、合并 main 或部署，远端授权仍仅到 `abe6530`。

## 前一阶段：独立同用户上下文打通真实 Native Gate 与来源只读检查

基于 `07328b8`。先核对工作树、权威设计和既有失败证据，五份未跟踪设计文档保持原字节。本次找到并实测了**合成联调的非虚拟化执行环境**：经 Windows WMI `Win32_Process.Create` 启动同一 Windows 用户的隐藏 PowerShell，先创建一个随机、空的私有 `PAW-SyntheticContext-*` 目录，按产品相同的目录句柄访问/共享/无跟随标志核对最终路径。用户 SID 相同、无 reparse、最终路径与 Known Folder 下请求的路径一致。之后在同种上下文运行已有 `RecoveryTrustedInspectionProbe`，仍由原生 owner 直接启动 Java 子进程并完成原有 Gate；WMI 只用于启动外层手工验证执行器，没有成为产品启动器或来源信任根。

线索先查 GitHub：[openai/codex#52698](https://github.com/openai/codex/issues/52698) 报告了 WMI 进程与 Codex 内部进程的 AppData 视图差异；旧 [#41665](https://github.com/openai/codex/issues/41665) 的 breakaway 失败不能推断 WMI 也失败。这些报告不是本机证明，本次结论来自新建空目录预检及下述真实 Gate 执行。没有修改包清单、系统安全配置、Known Folder、目录重定向、Profile ACL 合同、helper pin 或 Java Gate，也没有使用包缓存物理路径冒充受信根。**Codex 普通直接子进程的虚拟化没有被修复；验证成功限于本次独立执行上下文。**

沿用同一手工流程，共执行 3 次（2 次失败、修正后 1 次通过），未扩展自动测试套件：

1. 第一次已完成真实 Profile Prepare/Provision、Java Native Gate 与 `NativeDatabaseSetup` 保存合成配置；随后探针的 `CreateFixtureFile` 使用普通 ReadWrite 句柄调用 `SetAccessControl`，因没有 WRITE_DAC 失败。按 [.NET 原有问题说明](https://github.com/dotnet/runtime/issues/108658) 及 [FileSystemAclExtensions.Create 合同](https://learn.microsoft.com/en-us/dotnet/api/system.io.filesystemaclextensions.create)，改为 CREATE_NEW 时直接传入私有安全描述符，并补准确的夹具阶段名。只改变全新合成锁/guard 的创建，没有修补既有文件权限。
2. 第二次已完成真实维护检查和 guard 拒绝，最后源快照核验因 Jackson JSON 节点的整数宽度差异失败（序列化的 long 行数与解析后的 int 节点比较）。改为反序列化到已有 `Snapshot` 后按原记录类型比较；保留全部表摘要、catalog、sequence、Finance sentinel 和无临时来源角色断言，没有改变 expected 数据或放宽判定。
3. 第三次完整通过：固定 JAR/JVM/JNA → canonical 新合成 Profile → 真正 Native Provision/Gate → 合成受保护配置保存 → 原生 owner/直属维护 Java 句柄交接 → 应用只读连接及配置身份/摘要双端核对 → 未知退出历史保持 null → 未解决 guard 保留并拒绝读取来源 → 源 Workspace 快照和 Finance sentinel 不变。产品入口返回 BLOCKED，未启动 Spring/HTTP、模型或普通工作区，也未更改选择偏好。

成功夹具为 `C:\PAW-SourceProducer-c4ef8eff27724dc3a764cdf7dd68732b`，随机 loopback 端口 `52055`，Profile ID `2cf471476f434b129b4b4df53135330d`。三次各用全新夹具与 Profile，失败目录不重用；三份独立 PG17 实例都经 PID/启动时间/可执行路径/data directory/端口核对后停止，postmaster.pid 均已不存在。每份 PG 为 16 MiB shared_buffers / 12 max_connections，Java 夹具及维护 JVM 受限；没有启停 KAG、共享 PostgreSQL、Ollama 或其他既有用户服务，没有访问真实 Profile/数据库。

只重建修正后的 .NET 手工探针及 Desktop 必要依赖（0 warning / 0 error）并单独编译 Java 探针；复用前阶段已固定的应用 JAR/JVM/JNA（JAR SHA-256 `d00f51cd66eeecedc9fc6086303563f526e7d7a3b55bed48251c95e64a783b75`，其维护入口源码自原构建后未变）。没有重建完整当前应用 JAR、前端、分发包，未重跑备份恢复或全套测试。本次不新增产品恢复功能，仅修复被真实联调揭示的两个夹具错误，并解除这条受信来源检查的执行环境阻塞。

本地证据在 `.verification/recovery-native-context/`：`context-preflight.ps1/json`、`preflight-launch.json`、`run-context-inspection.ps1` 与 `invoke-inspection.ps1`；根目录和 `fixed/` 保留前两次失败、所属夹具及关闭记录；`fixed/dotnet-build.log`、`verified/javac.log`、`verified/native-source-integration.log`、`verified/native-source-evidence.json`、`verified/fixture-result.json` 和 `final-boundary-evidence.json` 保存最终证据。复现应在同一正常用户的独立非虚拟化上下文中沿现有 runner 新建随机夹具；先核验 SID/最终路径，再执行真实 Gate；不可重跑已留下状态的同一 Profile、删除 guard 或改用真实资料。这里保存的是开发环境手工验证路径，不是部署脚本。

**剩余产品阻塞仍明确保留：** `Admission=BLOCKED`、`WriterCoverage=UNREGISTERED_WRITERS_UNPROVEN`、`ObservedExitedChildren=null`。来源只读检查不能授权个人备份，不能构造完整停写证明；个人受信来源生产/持久登记消费、prepared target 与日常 CRUD 凭据绑定、READY、显式激活仍未完成。断电/网络中断、部分 CREATE 未知结果也未验证。下一步应利用已验证的执行上下文推进完整停写与个人来源 authority 的 owning 合同，不能直接把合成 Completed 或这次只读结果升级为个人恢复授权。未合并 main、部署或推送。

## 前一阶段：认证后新建合成目标并恢复，真实入口仍阻塞

基于 `5dc3fe8` 继续。先核对既有 NativeBootstrap 四份构建 pin，以 `VerifyProfileNative` 只读检查此前已记录的合成 ID；helper 返回 STOP/10，未 Provision，也没有证明从工作区可执行文件位置启动就获得非虚拟化目录视图。证据保存在 `.verification/recovery-profile-identity/pinned-native-readonly.json`。没有修改系统/包安全设置、替换 Known Folder、绕行 Gate、操作真实资料或启停既有服务。此前的最终路径分歧仍是实际产品入口的联调阻塞。

随后实现 `backup/WorkspaceRecovery.restoreNewSyntheticTarget`，把已经完成加密及来源角色回收的 `WorkspaceSourceBackup.Completed` 接到实际新目标恢复：

- 先完成 v3 AEAD 两遍认证、清单/TOC/Knowledge 验证及保存的 manifest digest 核对，之后才生成目标凭据、打开管理员连接或创建目标；只有该进程内真实执行产生的 Completed 可进入，不从口令或 JSON 重建来源权限。
- 管理连接重新核对 source server system identifier、数据库 OID/receipt、端口和身份。全新随机数据库与 owner/app 角色仍强制 B3 合成命名、同一非 5432 实例；在任何 CREATE 前 force 保存不含密码的私有合成操作意图，不复用既有目标。
- 两份角色以 NOLOGIN 创建并绑定操作 receipt；核对新 DB OID/owner、两份角色 OID、跨库 CONNECT/成员资格及 app 最小权限后才启用短期登录。调用既有恢复引擎完成新库恢复、Memory/Conversation/Knowledge 读回、源文件摘要与索引重建核对；目标原始 ownership receipt 被原引擎持久消费，未知结果不能重放。
- 成功或普通异常结束时，都按精确 role OID/name/receipt 尝试禁用两份登录并清除密码；清理未知报告 `TARGET_CREDENTIAL_CLEANUP_REQUIRED`。仅删除本次解密临时 backup 子树，保留 candidate、数据库和操作证据；最终合成验证记录在凭据收尾及明文清理完成后写入。没有 DROP、覆盖修复或自动重试。

沿用并扩展唯一 `RecoverySourceProducerProbe`，没有增加测试套件。对新建私有夹具 `C:\PAW-SourceProducer-b015697df8bd4f1f91cf8dee4e8f2b96`、PG17 随机端口 54964 实际执行 **1 次**：受保护配置绑定 → v3 备份/来源角色回收 → 错误口令不创建 DB/角色 → 新目标恢复 → 非空 Memory、Conversation、Knowledge 与索引核验 → 目标两角色 NOLOGIN/PASSWORD NULL → 原生来源记录发布及 DPAPI 重读。还核对 Finance 不进入目标、源快照不变、既有 staging 拒绝重放、操作意图不变及临时 backup 已清理。全部通过；自建 PG 实例经精确 PID/启动时间/路径/端口检查后停止，postmaster.pid 已不存在。Java 只编译本次两个类，.NET 只构建手工 probe（0 warning/0 error），未重建完整应用 JAR、前端或重跑其他测试。

**这仍是合成引擎闭环，不是产品恢复发布。** 合成操作意图不是原生 DPAPI 恢复登记，当前进程内 Completed 不是可重启的可信来源消费者；本次没有验证断电/kill、网络中断或部分 CREATE 的未知结果处置。突然终止可能留下目标和短期凭据，必须保留证据、禁止自动重放；没有宣称 finally 能覆盖崩溃。目标 app 仍使用既有维护读回 grant，未提供日常 CRUD 配置/权限，两个登录最终都禁用。真实 Native Gate、完整停写、个人来源生产/登记消费、prepared Profile、READY、正式发布与显式激活仍缺失，维护保持 **BLOCKED**。目标结果准确保留 `RESTORE_VERIFIED / IDENTITY_REBIND_REQUIRED / WINDOWS_ADMISSION_PENDING`。

证据：`.verification/recovery-fresh-target/` 中 `javac.log`、`dotnet-build.log`、`native-source-integration.log`、`synthetic-recovery-evidence.json`、`native-source-evidence.json`、`fixture-result.json`、`final-boundary-evidence.json` 以及限定 runner。实现参考 [PostgreSQL 17 CREATE DATABASE 原始文档](https://github.com/postgres/postgres/blob/REL_17_STABLE/doc/src/sgml/ref/create_database.sgml) 的事务限制，故 intent 在 CREATE DATABASE 之前持久化，不能把该 SQL 与角色创建包装成一个虚构原子事务。五份未跟踪设计文档哈希未变；未合并 main、部署或推送。

## 前次定位：合成 Profile 最终路径分歧，未修复、未补记 PASS

按后续明确授权重新定位 `09978c3` 的 `PROFILE_PREPARATION` 失败；本次只检查上次记录的两份自建合成目录及其路径祖先元数据，没有新建 Profile、运行数据库、读取业务内容、修改 ACL 或重放 Provision。原失败日志和两个未完成目录均保留。

- 使用与产品相同的 `CreateFileW` 访问、共享及目录/无跟随标志，重开逻辑路径和 OS 返回的物理路径。两者的 `FILE_ID_INFO` 相同，物理路径本身 canonical；目录无 reparse point，当前用户所有、DACL protected。失败点是最终路径字符串与固定 Profile 根不相等，不能将其解释为 ACL 不合格。
- Known Folder 返回 `C:\Users\<user>\AppData\Local`；新建叶目录的最终路径落在 `D:\WpSystem\<SID>\AppData\Local\Packages\OpenAI.Codex_<family>\LocalCache\Local\PersonalAIWorkspace.Development\Profiles\<synthetic-id>`。应用/Profiles 父目录通过两种路径打开均报告相同文件身份，但各自最终路径仍保留各自名称；这不证明目录祖先和叶目录已经具有产品需要的一致 canonical 路径链。不能仅凭文件 ID 相同接受逐个子根重定向。
- `GetCurrentPackageFullName` 返回 `15700 / NO_PACKAGE`。显式当前用户 token 和 NULL token 下的 `KF_FLAG_RETURN_FILTER_REDIRECTION_TARGET` 都未返回物理包缓存位置；NULL token 下单独的 FORCE_APP_DATA_REDIRECTION、FORCE_PACKAGE_REDIRECTION 也未改变返回值。包身份查询或 Known Folder 标志不能在本环境提供修复依据。

微软的 [MSIX AppData 操作说明](https://github.com/MicrosoftDocs/msix-docs/blob/main/msix-src/desktop/desktop-to-uwp-behind-the-scenes.md#appdata-operations-on-windows-10-version-1903-and-later) 说明新建项的私有重定向及已有路径的合并行为，与本机观测相符。GitHub [openai/codex#41665](https://github.com/openai/codex/issues/41665) 有同类“子进程无 package identity、句柄仍指向 LocalCache”的报告；其中 [进程树 breakaway 实验](https://github.com/openai/codex/issues/41665#issuecomment-5476012474) 也未改变报告者的路径视图。后两者只是外部线索，不是本机验证；本次没有复制其进程启动绕行实验、改 Codex 包配置或删除任何 shadow。

**本次没有找到符合原固定根/祖先 pin 合同的产品修复，完整真实 Native Gate 链路仍 BLOCKED。** 不修改 `OpenDirectory`、helper pin 或 Java Gate 来接受该分歧；也不把物理路径写入参数冒充当前受信 Profile。后续需要在独立、正常用户的非虚拟化执行环境中运行已有 `RecoveryTrustedInspectionProbe`，先确认新建合成根的最终路径一致，再进行原生配置与只读来源端到端验证。当前工具进程的所有普通子进程是否可获得这种视图尚未证明，不能承诺只换 shell 或加一个 Known Folder 标志就能解决。该环境前提满足后，仍不得将只读来源验证提升为完整停写或个人备份准入。

本地证据：`.verification/recovery-profile-identity/inspect-context.ps1`、`context-readonly.json`、`known-folder-null-token.json`、`handle-lineage-readonly.json`、`final-boundary-evidence.json`。本次未重跑相同失败的端到端流程或新增测试模块；仅保存诊断与交接结论。五份未跟踪设计文档保持原字节，既有服务未操作，未合并、部署或推送。

## 前一阶段：产品受信维护入口已接线，实机集成阻塞

基于本地 `2b1899f` 继续实现。`Program --inspect-profile-recovery-v1` 现在调用原生 owner 的 `InspectSourceReadinessAsync`：持有既有实例互斥量及 Profile/运行锁/模型锁，使用 `TrustedDevelopmentRuntimeSources` 的嵌入清单固定实际 JAR、JVM 与 JNA 来源，直接启动有资源上限的维护 Java 子进程。Java 经正常 `PersonalAiWorkspaceApplication` 分派到 `NativeProfileRecovery.runInspection`，完成原有 Native Gate 后才发 PID/Profile 握手；原生端随后只向自己持有的 Process 句柄复制排他句柄。

新协议仅接受有界、严格字段的 `INSPECT_SOURCE`，不接受备份动作、管理员材料或调用者路径。Java 重核固定 Profile 参数与已交接资源后，检查模型文件状态；存在 guard、pending 或未知条目时跳过配置读取。其余情况下复用上阶段的配置 pin/DPAPI/应用只读连接核对，返回配置文件身份/密文摘要及数据库、应用角色 OID，原生端独立重核配置文件。这里没有管理连接、PG system identifier 证明、短期角色、加密备份或来源发布，也没有启动 Spring/HTTP/模型。来源只读观察不能构造上阶段要求完整管理连接绑定的备份结果。

**返回的准入始终为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN。** 原生退出记录缺失、损坏或不完整以 nullable unknown 表示，不能改成“0 次运行”或安全退出。没有 guard 文件也只报告 `NO_GUARD_FILE_OBSERVED`，不证明旧包、未登记 Java、外部 SQL 或模型执行已停止。原入口在报告后终止，不进入工作区选择、Runtime 启动或偏好保存。已有严格 `InspectMaintenancePrerequisites` 的拒绝合同保留。

本轮实际验证与未覆盖项：

- 离线构建实际应用 JAR，既有 JAR 审计通过；嵌入精确来源清单的 Desktop 和新手工探针构建通过，.NET 为 0 warning / 0 error。Java 保留既有内部 API 警告，未跑全套测试、前端构建或分发打包。
- 新增一个聚焦协议拒绝测试，实际 1 test / 0 failure / 0 error：Gate 拒绝时输入不被读取；备份动作、额外凭据字段、重复键和超长帧不能进入句柄接收。该测试 mock Gate，**不是实际 Native Gate PASS**。
- 手工集成 `RecoveryTrustedInspectionProbe.cs` 使用原有 Prepare/Provision 和真正 `NativeDatabaseSetup`，计划只对全新随机合成 Profile 执行“受信配置保存 → 原生维护调用 → 来源只读核对 → guard 拒绝”。实际第一次在 **PROFILE_PREPARATION** 阶段被原有 `WindowsProfileSecurity.State → HoldDirectories → OpenDirectory` 的目录身份检查拒绝，尚未调用 Provision、Java Native Gate 或数据库配置写入。独立 PG17 实例已核对 PID、启动时间、data directory、端口与程序路径后停止。
- 为定位同一拒绝，仅补一次不重启 PG 的脱敏函数栈诊断；使用另一个全新随机 Profile ID，同一阶段拒绝。两个新建合成根存在并保留为未完成证据；没有对它们重放 Provision、修补 ACL、换根或覆盖。只读取了这些本轮拥有的目标和工程 helper；没有访问真实用户 Profile 内容或启停既有服务。

因此本轮**真实受信维护/来源集成未通过**。完成的是产品调用链代码、实际产物构建和必要拒绝验证，不能把它写成完整停写证明或实际来源生产者已可用。原生 Profile 目录身份环境阻塞应沿原有 owning 边界单独解决；本轮没有重开已结束的子根重定向排查或放松 Gate/helper pin。完整停写覆盖、个人来源生产与未知结果核对、真实恢复、prepared-target、READY/显式激活仍未完成。

证据在 `.verification/recovery-trusted-inspection/`：`runtime-build.log`、`runtime-sources.local.json`、`dotnet-build*.log`、`protocol-denial-test.log`、`native-source-integration.log`、`preparation-diagnostic.log`、`fixture-result.json`、`first-owned-synthetic-profile.json`、`diagnostic-owned-synthetic-profile.json`。runner 和独立 csproj 同目录。实现前参考 [.NET 10 Process](https://github.com/dotnet/runtime/blob/v10.0.0/src/libraries/System.Diagnostics.Process/src/System/Diagnostics/Process.cs) 的直属子进程退出/EOF 行为。五份未跟踪文档保持不变；仅本地提交，未合并、部署或推送，远端授权仍仅到 `abe6530`。

## 本轮阶段：从 DPAPI 来源配置到备份结果登记

本轮基于本地 `a9903a5`，继续在 `codex/recovery-v3-workspace-core` 开发。已读取权威设计；五份未跟踪设计文档保持字节不变。**本轮完成来源配置绑定内核及其合成端到端执行，个人恢复仍不可用，维护仍为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN。** 下方原有来源生产者记录是此前阶段背景，本节描述最新实现。

`NativeProfileRecovery.freezeSource` 通过已有 `WindowsNativeGate.maintenanceProfileRoot(exclusion)` 取得当前 authority，只读取它固定的 `data/database.dpapi`；沿用 Profile ACL、无链接、配置解析与 DPAPI，不接收任意来源路径、不读取 Finance 配置，也不授予备份准入。`TrustedDatabaseConfiguration.RecoverySource` 在本次执行期间持有 root/data 禁删句柄及配置禁写/禁删句柄，固定原生文件身份和密文字节摘要，关闭后不能重新使用。普通配置加载和正常 Runtime Gate 的合同保持不变。

备份使用该配置中的数据库、端口和 Profile Knowledge 路径；管理员材料只提供用户名/密码，不能另选来源。创建短期角色前，使用受保护应用凭据实际建立只读连接，核对低权限应用角色、Workspace V1/V2 与元数据合同。管理连接必须在 `pg_stat_activity` 中看到该尚存活的应用 backend PID、数据库 OID、角色 OID、客户端端口及 loopback 地址，随后固定 `pg_control_system()` 的实例标识。这样无需向日常应用角色授予控制文件读取权限，也不把 URL 相同当作实例身份。短期角色收尾完成后再次核对来源；若后置核对失败，不发布登记。回收本次已知角色依赖已有实例/数据库身份和角色 receipt，不依赖应用凭据仍可登录，避免配置异常阻止已知角色的安全收尾。

原生合成结果帧升为 v2，增加配置文件身份、配置密文 SHA-256 和应用角色 OID；备份包仍是既有 v3。原生 owner 等待子进程正常退出后，独立 pin 同一固定配置文件并核对身份/摘要，再沿既有密文核验、角色收尾结果和 DPAPI 发布流程落盘。合成登记使用 v2 包装记录，包含原来源结果及配置绑定，仍只写 `Recovery/synthetic-sources`。原产品 `PinRegisteredSource`、来源种类和 `Recovery/sources` 消费合同未放宽；无绑定的旧合成引擎结果不能发送新版原生成功帧。

验证只扩展原有 `RecoverySourceProducerProbe.cs/.java` 一条手工集成。新随机 PG17 实例内创建合成源、低权限应用角色及真正 DPAPI 配置；配置/ACL/句柄使用私有 OS 测试缝，**没有设置虚假 Native Gate 状态，也未运行真实 Profile 或个人凭据**。同次执行验证：

- 固定配置 → 实际应用/管理连接绑定 → 临时角色 → v3 加密备份 → 角色回收 → 原生配置及密文核验 → 独立 DPAPI 登记和重读。
- 配置缺失、配置写入/改名、data 目录改名、错误管理数据库、关闭后复用均拒绝；源 Workspace snapshot 和配置密文不变，Finance sentinel 保留，临时角色已不存在。
- 完整认证解密与 manifest 摘要核对继续通过；合成登记仍不能预约产品目标，同一执行不得重复，子进程结束后排他锁释放。

本轮这一个流程共执行两次，均通过：首次验证实现；收口审查调整“先完成角色回收、再作最终来源复核”的顺序后，仅重跑同一流程。两次独立 PG 实例均按 PID、启动时间、data directory、端口与程序路径核对后停止。使用两核/384 MiB JVM、随机 loopback 端口和 16 MiB shared_buffers；没有访问真实用户数据/Profile、启停既有服务、改动 KAG、合并 main 或部署。

定向 javac 编译通过，沿用 `ExtendedOpenOption` 内部 API 警告；Desktop/探针构建为 0 warning / 0 error。未跑 Maven、前端构建或全套测试。最终证据在忽略目录 `.verification/recovery-source-binding/`：`native-source-integration.log`、`source-binding-evidence.json`、`native-source-evidence.json`、`fixture-result.json`、`owned-fixture.json`、`javac*.log`、`dotnet-build.log`；首轮证据以 `first-` 前缀保留，runner 也在该目录。

实现前先参考 GitHub 的 [OpenJDK 21 WindowsChannelFactory](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsChannelFactory.java) 和 [PostgreSQL 17 pg_controldata](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/utils/misc/pg_controldata.c)，核对 Windows 文件共享选项及实例标识来源；未修改第三方代码。

仍缺完整停写准入、受审查且 pin 的产品直属 JVM 分派及真实 Native Gate 验证、真实 Profile 与维护文件 ACL 合同衔接、实际个人来源生产和未知结果核对。新目标 DB/Profile、prepared-target、凭据绑定、离线恢复/索引验证、RECOVERY_READY 与显式激活也尚未完成。本轮配置绑定是其中一段可复用内核，不能据此把维护改成 GO。后续应把已验证的来源绑定接入有完整停写证据的产品 owner；不能仅把 synthetic 改名或增加可填写的成功 receipt。

本轮仅本地提交，未经另行授权不得推送。公开远端明确授权仍只到 `abe6530`；未 fetch 或宣称重新核验远端。

## 前一阶段背景（截至 a9903a5）

日期：2026-11-07（Asia/Shanghai）。基于本地 `6e8c4ae`，开发分支 `codex/recovery-v3-workspace-core`。已读取并遵循未跟踪的 `CURRENT-PROFILE-RECOVERY-DESIGN.md` 与 `RECOVERY-SLICE-2H-HANDOFF.md`。本阶段把 2g 的实际备份/角色收尾和 2h 的句柄交接连到原生结果核验与 DPAPI 发布；**通过的是明确隔离的合成执行链，个人受信来源登记仍未交付，维护仍为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN。**

## 已实现的连续执行路径

`ProfileRecoveryCoordinator.SourceProducer.cs` 的私有合成执行入口仅接受无真实 authority 的显式合成应用根，不在 CLI/UI/HTTP 分派中暴露。它复用原生 owner 分配的 backupId、逻辑 ID 和真实 root/data 文件身份；重新核对意图，持有空 runtime/model 锁，启动一个直属 Java 进程并保留实际 Process 句柄。子进程 PID 握手后才复制排他句柄，随后通过私有 stdin 发送本次 ID、原生选定输出路径和句柄。协议使用 4 字节大端长度、最大 16 KiB 单帧；拒绝重复/未知字段、额外输出、非零退出及超时，不从外部 PID 打开进程。独立 Job 仅包含本次子进程及其 pg_dump 后代。

Java `WorkspaceSourceBackup.Completed.writeNativeResult` 只能由真实备份且角色回收成功产生的 Completed 调用。帧包含同次 manifest 的逻辑 ID/原始字节摘要、密文字节数/摘要、实际 PG system identifier/database OID/端口、短期角色名与回收结果。没有口令、管理员密码或业务正文；固定标记 `SYNTHETIC_WORKSPACE_BACKUP_RESULT`，不提供根据外部元数据构造成功对象的接口。

原生端等待唯一结果、stdout EOF、正常退出及 stderr 有界排空后，再按自己的路径打开并 pin 密文和 completion，核对真实字节数及 SHA-256、严格 completion 合同和目录精确文件清单，重核源目录身份。最后用已有 DPAPI CurrentUser、CREATE_NEW pending、Flush(true)、同目录不覆盖发布和解密重读，写入独立的 `Recovery/synthetic-sources/<backupId>/source.dpapi`。

**合成记录具有独立目录、DPAPI entropy、Kind 和 State。** 原有 `PinRegisteredSource` 仍只接受 `Recovery/sources` 的 `WORKSPACE_BACKUP_SOURCE / BACKUP_REGISTERED`；共用的是 provenance 字段验证，产品种类验证未放宽。合成记录不能预约产品目标，复制文件或改名也不会补齐产品授权。没有新增通用 `PublishSource(外部元数据)`。

失败保留本次意图、密文和任何发布残留，不重放、不修复状态、不根据包旁标记推断完成。重复同一执行在重新启动子进程前即拒绝。若子进程异常结束/超时，可能留下短期角色，记录不发布；当前阶段不实现未知结果重试或回收协调器，必须保留证据另行核对。

## 实际验证及边界

一个显式手工探针 `bootstrap/tests/RecoverySourceProducerProbe.cs/.java`，未加入自动全套。原生 probe 在本轮全新随机私有目录内创建合成树；Java probe 核对新 initdb 的 PID/data directory/端口及 3 库/1 非系统角色 inventory 后才建合成库，加入受信 Workspace V1/V2、一条 Memory 和 public Finance sentinel。没有真实 Profile、原生身份伪造或 Native Gate 替代；probe 通过明确私有测试缝验证 OS 原语，不能据此声明真实 Native Gate PASS。

最终同一用例 PASS，涵盖：

- 原生 intent → 直属 JVM 的真实 DuplicateHandle → 实际短期角色 → pg_dump/OpenPGP v3 → 角色回收 → 结果帧 → 原生密文核验 → 独立 DPAPI 发布/解密重读。
- Java 额外完整认证解密、核对 exact manifest 摘要与归档内容合同；源 Workspace snapshot 未变化、Finance sentinel 保留、短期角色不存在；维护 staging 的临时 backup 子树按既有规则清理。
- 合成记录不能通过产品种类验证或 ReservePlan，目标登记仍为空；重复同次执行被拒绝且原 DPAPI 字节不变；子进程退出后 runtime/model 锁可以重新打开。

共执行该流程四次：第一次在备份前失败；第二次仅为同一失败补充脱敏阶段诊断；第三次完成实际备份/回收，但在原生密文目录 ACL 检查失败；第四次通过。前三次无来源记录发布，每次独立实例均已按其 PID、启动时间、data directory、端口、可执行路径核对后停止。失败保留，不记作 PASS；没有扩成四套测试。

失败原因是两个既有 ACL 合同不同：合成维护引擎要求 current-user-only，Profile 创建默认带 user/System/Admin 且受保护；维护引擎的输出目录则按父目录继承。修正只作用于新合成夹具及新密文输出边界：前者从创建起使用更窄 ACL，后者按已 pin 的固定 synthetic-payloads 父目录和真实目录句柄核验全部 current-user ALLOW，再在内存中按既有继承文件读法验证。没有修改真实 Profile ACL、写回修补输出 ACL、放宽 `MaintenanceFiles.privatePath` 或原生 `ValidatePrivate`。该适配只用于合成产物，个人路径仍须设计明确的文件域适配。

技术核对先参考 GitHub 的 [.NET 10 Process](https://github.com/dotnet/runtime/blob/v10.0.0/src/libraries/System.Diagnostics.Process/src/System/Diagnostics/Process.cs) 与 [OpenJDK 21 Windows ACL](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsAclFileAttributeView.java)，再结合本机实际句柄/ACL 定位；没有修改第三方源码。

定向 javac 编译和 Desktop/探针构建成功，最终 .NET 为 0 warning / 0 error。使用缓存依赖，未跑 Maven/前端/全套测试或产品打包。JVM 限两核、384 MiB；独立 PG17 为随机 loopback 端口、16 MiB shared_buffers、12 max_connections；没有启动或停止 KAG、共享 PostgreSQL、Ollama 或既有用户应用。

本地证据均在忽略目录 `.verification/recovery-source-producer/`：`native-source-integration.log`、`native-source-evidence.json`、`fixture-result.json`、`owned-fixture.json`、`javac*.log`、`dotnet-build*.log`，以及 `first-*` / `diagnostic-*` / `acl-output-*` 失败日志和 `diagnostic-probe-failure.json`。runner 与 probe csproj 也在该目录；成功合成数据和密文保留于 evidence 记录的随机根，不包含用户来源。

## 剩余真正的产品阻塞

不能通过把 synthetic 改名、把布尔停写声明改成 receipt、或添加可填写元数据的发布 API 来上线个人来源。当前尚缺：

1. 能覆盖旧包/跨会话、不受控 SQL writer、未知模型执行和持久 guard 的完整停写准入；既有 inspection 仍只返回阻塞，没有可授予成功的 capability。
2. 受审查且 pin 的产品直属 Java 启动/Native Gate 分派，以及从当前受保护 Workspace 配置冻结并复核真实来源；合成 fixture 管理员材料不替代此链。真实 Profile 文件 ACL 与维护文件合同也需明确衔接。
3. 将上述真实来源与同次执行结果绑定，发布实际 `Recovery/sources/.../source.dpapi`，并处理角色回收/发布结果未知的只读核对。**本阶段没有这种个人记录生产者。**
4. 全新目标 DB/Profile、prepared-target、凭据绑定、离线数据/索引验证、RECOVERY_READY、用户显式激活和受信 Desktop 读回。

下一阶段应围绕前两项和实际产品来源生产一起推进，不能继续把探针成功当作可用恢复交付。五份原有未跟踪设计文档保持逐字节不变；本阶段不推送、不合并 main、不部署。原远端已知仅至 `abe6530`，未 fetch 或声称重新核验远端。
