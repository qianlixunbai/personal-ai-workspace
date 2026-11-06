# Recovery 来源生产者：合成跨进程备份与原生持久登记

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
