# Recovery Slice 2d：启动前只读维护检查入口

日期：2026-11-07（Asia/Shanghai）。起点 `ec410324b6c61a7f2f2bebada963978be69384ea`，继续本地分支 `codex/recovery-v3-workspace-core`。本模块把已有拒绝式检查接入原生启动边界，并纠正逻辑 JSON 恢复提示；**维护准入仍为 BLOCKED，不是备份/恢复/激活完成，不是 Production GO**。

## 完成的三个任务

1. `ProfileRecoveryCoordinator.ParseInspectionRequest` 定义唯一只读命令 `--inspect-profile-recovery-v1 <profileId>`，仅接受两个参数及 32 位小写十六进制身份。解析不访问 Known Folder、Profile 或 helper；不接收路径、SQL、凭据、恢复动作或绕过标志。语法通过本身不授予权限。
2. Profile Debug 版 `Program.Main` 在普通 Profile 选择、`AssistantApp` 构造和 Runtime 启动之前分派检查。该命令族包括未知版本都直接返回，不进入选择器或正常启动。有效语法仍须经原 `Mmf3AcceptanceLaunch.Parse`/Native Verify，再由 `OpenForPlanning` 持有既有 Profile mutex、原生目录 pin，并执行 2b/2c 的租约/模型 guard/退出记录检查。全程同步留在 STA 入口线程，结果对话框关闭后才释放 coordinator。没有成功码、维护能力或可复用准入凭据。
3. 原生 Workspace Backup 窗口明确 JSON 仅包含 Memory/Conversations，不包含 Knowledge 文件、PostgreSQL/Profile 配置或 Finance；说明完整 PG Profile 恢复尚未开放，以及运行中工作需在原工作流处理。逻辑恢复说明和成功提示不再让用户把任意恢复目录当成已激活 Profile。后端仍沿原实现拒绝当前 PG 的逻辑 restore，数据操作/API 均未修改。

## 检查结果与边界

| 退出码 | 固定结果 | 含义 |
| --- | --- | --- |
| 2 | `INVALID_REQUEST` | 格式/版本/身份语法无效，在任何 Profile/helper 访问前退出。 |
| 3 | `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN` | 已有前置检查完成，但旧包、未登记进程/会话和外部 SQL 的停止状态仍无法证明。 |
| 4 | `BLOCKED / INSPECTION_UNAVAILABLE` | 受信准入、锁、目录、guard 或历史任一步异常；只显示固定文案，不显示路径、账户、异常原文、helper 输出或记录内容。 |

命令只在 `MMF3_PROFILE_ACCEPTANCE && DEBUG` 编译身份中存在，不是发布版维护接口。这里的只读指不创建/修复 Profile、登记或业务数据；协调器仍取得必要的 OS 互斥/文件句柄，并使用既有只读验证 helper。未运行这个有效命令去读取真实 Profile。报告只反映本次检查，不是未来维护窗口的停写凭据。

未调用 `StopAsync`、`ExitAsync`、Runtime 启动、模型取消、记录清除、权限修复或目标创建。未新增 Java/helper 动作或更新受信 pin。既有 helper 自身的受控调用行为保持原样；本轮没有运行它。没有把 `MaintenanceInspection.Blocker` 改为成功，旧包和外部写入者的证明缺口完整保留。

## 最小验证

成功运行仅一次、两条已有 owning-layer 测试：**2 PASS，0 failed/skipped**；测试日志汇总 375 ms。

- `Mmf3AcceptanceTests.RuntimeExitEvidenceNeverAuthorizesMaintenanceOrHidesInterruptedOwners`：298.8584 ms。扩展原 Fact，检查版本/参数/任意路径拒绝及固定反馈，不创建真实 UI；原场景继续在全新私有临时替身目录使用真实 DPAPI、文件锁和一个测试自建的隐藏 `cmd.exe /d /q` 子进程。覆盖活进程/不完整退出/guard/坏记录拒绝、互斥和始终 BLOCKED。
- `WorkspaceBackupTests.RestoreRequiresSeparateClickAndComparesValidatedMetadata`：372.6425 ms。原 WPF 测试使用内存 HTTP handler、内存流和模拟选择器，无网络或目标目录写入，验证显式恢复动作及“不代表 Profile 激活”的结果提示；未显示实际窗口。

证据位于 `.verification/recovery-slice2d/test-results/recovery-inspection.trx` 和 `dotnet-test-focused.log`。Desktop/Profile Debug 与测试工程编译成功，成功日志无警告/错误。没有新增测试文件或 Fact，也未运行全套、Java、数据库、模型或前端重建。

第一次尝试因缺少现有测试工程的 `RuntimeLifecycleProbe=true` 条件编译标志，`RuntimeLifecycleProbe.PostgresqlConversation.cs` 引用的旧类型未包含，编译失败，**没有运行测试**；失败日志保留为 `dotnet-test.log`。第二次仅补该编译参数，经 `dotnet test`/测试适配器运行上述两条 Fact，**没有执行 probe Main 或 apphost**。本轮未修订这个既有条件编译问题。

依赖通过空本地 feed 加本机缓存离线 restore，产物独立放在 `.verification/recovery-slice2d/dotnet`；单 MSBuild worker、`DOTNET_PROCESSOR_COUNT=2`。无全局环境修改。Windows 默认沙箱初始化出现 `helper_sandbox_lock_failed`，只对已授权工作区的精确命令使用经审核的执行权限；没有修改沙箱 ACL。

并发边界实现前参考了 GitHub 上 [.NET Windows Mutex 实现](https://github.com/dotnet/runtime/blob/main/src/libraries/System.Private.CoreLib/src/System/Threading/Mutex.Windows.cs)，沿用系统 mutex 和当前线程释放方式，未新增后台任务或互斥平台。

## 尚未覆盖与后续

有效命令经过真实 Native Gate 到对话框的集成、真实 Profile/GUI 展示、跨 Windows 会话、旧包、外部 SQL、断电持久性均未验收。现有合成测试不能据此升级维护状态。未知/中断历史仍需保留，不能用最新一份退出记录覆盖缺口。

下一安全前提仍是可核对的旧客户端/未登记执行处置与源数据库一致性窗口；不得因已有检查入口便开放备份/恢复。其后才能绑定 v3 密文完成、源身份、payload/manifest 摘要及短期源角色收尾到独立受信来源登记。来源登记、短期权限授权/回收、数据库 provision、prepared-target、目标凭据、READY、正式激活全部未实现；计划记录仍不是 SQL 来源权限。

五份用户未跟踪文档首尾 SHA-256 一致，见 `preserved-before.json` / `preserved-after.json`，不修改或纳入提交。没有访问真实数据库、Knowledge 源或 Profile，没有启停/修改既有服务，没有触碰 KAG。没有合并 main、部署、构建受信分发包或推送。此前公开推送 `abe6530` 的审批拒绝仍待用户明确同意；本轮未重试或绕过。最终本地提交 SHA 记在 `.verification/recovery-slice2d/git-delivery.json`。
