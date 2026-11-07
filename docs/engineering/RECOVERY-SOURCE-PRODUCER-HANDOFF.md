# Recovery 来源生产者：受保护配置绑定与合成跨进程备份

## 后续定位：合成 Profile 最终路径分歧，未修复、未补记 PASS

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
