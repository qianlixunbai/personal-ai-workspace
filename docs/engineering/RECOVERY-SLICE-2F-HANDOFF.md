# Recovery Slice 2f：来源生产链的备份意图预登记

日期：2026-11-07（Asia/Shanghai）。基于 `ff4679382541a79ffb8a9c6032121a398bfb7a21`，本地分支 `codex/recovery-v3-workspace-core`。已核对权威设计 `CURRENT-PROFILE-RECOVERY-DESIGN.md` 第 3–4、7 节与 Slice 2e 交接。**本轮只完成来源登记生产者的准备阶段，没有完成成功来源登记生产者，也没有解除维护阻塞。维护仍为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN。**

## 完成的模块

1. `ProfileRecoveryCoordinator.SourceIntent.cs` 增加原生内部 `ReserveBackupIntent()`。只使用已由 `OpenForPlanning` 校验且持有原有 SingleInstance 的 authority；重新核对 Profile/Native Gate，从 canonical 应用根和 authority ID 推导源 root/data，不接受外部路径、来源 ID、摘要或“已停止”布尔值。分配随机 backupId 与非授权逻辑来源 UUID，沿现有私有 ACL/无 reparse/祖先句柄检查固定两个目录，并从其 OS 句柄读取真实 `FILE_ID_INFO`；24 字节小端编码与 Java `TrustedDatabaseConfiguration.Identity.read` 对齐。目录句柄保留到 coordinator Dispose；只固定目录身份，不代表文件内容或数据库已冻结。
2. 先以 DPAPI CurrentUser、CREATE_NEW、force、同目录无覆盖发布与读回核对，持久写入 `Recovery/source-intents/<backupId>/intent.dpapi`。唯一状态为 `WORKSPACE_BACKUP_INTENT_ONLY / PREPARING`，没有 payload/manifest 摘要、数据库身份、收尾成功或完成状态。它位于独立意图目录，不能被 `PinRegisteredSource` 消费；不会创建 `sources/<id>/source.dpapi`。初始化空 targets 延续已有 Runtime journal 合同，不改变正常 Profile 或候选的启动规则。
3. `InspectBackupIntent` 只读核对指定 backupId；重开后不要求原源目录在线，不回填记录或继续执行。缺失、额外/pending 文件、坏密文、状态/ID 不匹配均拒绝。发布阶段失败的 `BackupIntentIncomplete.Attempt` 保留准确尝试身份；同一 owner 即使失败也不能再次分配，需核对原尝试，不自动重试或覆盖。其他现有登记读取、维护检查、计划和启动边界不变；无 UI/CLI/HTTP 接线。

这一步允许在任何数据库访问前持久记录准备意图，**不允许据此开始备份、创建短期角色、接收管理凭据或恢复**。意图的字段可供未来受信执行链绑定，但意图对象或其磁盘副本均不是授权 capability。未来完整生产者必须使用同一尝试、重新核对仍持有的来源身份，并从真实维护/备份/收尾结果产生完整记录；不能增加可由调用者填字段的 `PublishSource` 或把测试写入器转成产品能力。当前没有生成任何产品成功来源登记。

## 必要检查与证据

只新增一个聚焦 Fact，并只运行一次包含以下两个 Fact 的 `dotnet test`：**2 PASS、0 failed/skipped，297 ms**；Desktop/Profile Debug 与测试工程编译成功，日志无警告/错误。

- `BackupIntentPinsSyntheticSourceAndNeverAuthorizesBackupOrRecovery`：全新私有临时树中的真实 DPAPI/目录句柄；无 authority 的产品入口拒绝且无写入；阻止源 root/data 移动；同 owner 拒绝重分配；意图不能用于恢复计划；源目录移走后仍可只读核对意图；状态/ID 篡改、pending 和发布前失败不被升级，尝试身份与残留文件保留。
- 已有 `RecoveryPlanIsPrivateDurableNonAuthorizingAndInterruptedCandidatesCannotStart`：只回归原独立来源消费者、计划和候选拒绝合同，未改该 Fact。

证据：`.verification/recovery-slice2f/dotnet-test-focused.log`、`test-results/backup-intent.trx`。离线 restore 只使用空本地 feed 和已有缓存；输出写本轮独立 dotnet 目录，`DOTNET_PROCESSOR_COUNT=2`、单 MSBuild worker、禁共享编译/节点复用。没有全套、Java、数据库、模型或前端重建；既有前端资产只验证。`RuntimeLifecycleProbe=true` 只为满足已有测试工程编译，未启动 probe Main/apphost。未运行维护检查中的专用子进程测试，也未启停既有服务。

技术核对先读取 GitHub 的 [.NET GetFileInformationByHandleEx 互操作实现](https://github.com/dotnet/runtime/blob/main/src/libraries/Common/src/Interop/Windows/Kernel32/Interop.GetFileInformationByHandleEx.cs)，并与仓库原有 Win32/JNA 身份代码比对，未引入新的身份/加密协议，未修改 helper 或其 pin。默认沙箱仍因 `helper_sandbox_lock_failed` 无法创建进程，后续限定读取、离线构建和聚焦测试经具体说明执行；未改沙箱 ACL。

## 尚未完成与下一步

优先解决维护 authority 的旧包/跨会话未登记执行、外部 SQL 与模型未知执行证明，再接入无 Runtime 的受信维护 Java 通道：从当前受保护 Workspace 配置导出并固定真实 PG 实例、数据库 OID、端口/角色，在同一 backupId 下完成一致性窗口与加密流，成功回收短期角色后才原子发布 `source.dpapi`。需要把意图与真实源数据库绑定，不能从包内字段、手工 SHA、口令或意图推导成功。现有合成备份 capability 仍不可用作个人来源授权；`ReserveBackupIntent` 的真实 Native Gate/authority 路径本轮未验收。

成功来源登记、完整停写证明、真实恢复、新 DB/角色与目标凭据绑定、prepared-target 核验、READY 发布和显式激活均未完成；本轮没有 Production GO 或端到端验收。没有访问真实用户数据库、Knowledge 源文件、Profile 或 KAG；没有启停现有服务、合并 main、部署或推送。此前公开推送审批拒绝仍待用户具体确认，本轮未重试。

五份原有未跟踪文档保持未修改、未暂存、未提交；修改前后摘要见本轮 `preserved-before.json` / `preserved-after.json`，本地提交与交接状态见 `git-delivery.json`。新增源码和本交接单独纳入本地提交。
