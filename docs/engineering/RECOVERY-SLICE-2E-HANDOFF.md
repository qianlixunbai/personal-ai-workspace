# Recovery Slice 2e：来源登记消费侧与计划准入

日期：2026-11-07（Asia/Shanghai）。起点 `28b7dde951ed008ef2b8938f94e6da4f0bbed84f`，继续本地分支 `codex/recovery-v3-workspace-core`。按本机保留的 `CURRENT-PROFILE-RECOVERY-DESIGN.md` 第 3–4 节推进。**本轮完成独立来源登记的消费检查，未实现成功备份登记的生产者；维护仍为 BLOCKED，未开放真实恢复，不是 Production GO。**

## 完成的三个任务

1. 在同一 `ProfileRecoveryCoordinator` 的 `Source` partial 中实现只读来源合同。固定读取受信应用根下 `Recovery/sources/<backupId>/source.dpapi`，不接收登记路径。复用既有祖先句柄、私有 ACL、无 reparse/单硬链接、DPAPI CurrentUser、16 KiB 上限和严格 JSON 检查；backupId 参与 entropy。缺失、不可读、空目录、pending、额外文件、坏密文、重复或未知字段都拒绝，既不修复也不创建登记。
2. `ReservePlan` 删除调用者提供的 sourceProfileId，先核对本机来源记录及 payload/manifest 两个摘要，再从记录取得来源身份；检查失败发生在分配 operation/target 和写候选登记之前。记录及祖先句柄保持到计划发布和读回结束。新计划版本 2 绑定来源登记**密文字节**的 SHA-256；不能以重新加密语义相同的登记替代原绑定。历史 v1 计划仍可只读查看，且与 v2 一样只代表 `RECOVERY_PLAN_ONLY / PREPARING`，所有登记候选仍拒绝启动。
3. 扩展现有一条恢复计划 Fact 并做两条聚焦验证，补本交接。没有新增测试文件/Fact、公开命令、UI 动作、Java/helper 协议或受信 pin 更新。

## 消费合同与信任边界

本版 `SourceRegistration` 固定 `Version=1`、`Kind=WORKSPACE_BACKUP_SOURCE`、`State=BACKUP_REGISTERED`、`CredentialCleanup=RECLAIMED`，只接受 Workspace-only v3 / PG17。字段包含 backupId、源 Profile、规范 UUID 数据集溯源 ID、root/data 的现有 48 位十六进制 Windows FILE_ID_INFO、源 PG system identifier / database OID / loopback port / 库名 / 角色、payload 字节数与 payload/manifest 摘要。源连接名称仅作为私有溯源数据，不拼 SQL、不用于连接；对象字符串输出固定为 `SourceRegistration[private]`。

**没有把收到这些字符串当作成功备份。产品没有签发/导入来源登记的方法。** 测试直接在全新临时替身目录构造合成 DPAPI 记录，只验证消费拒绝、绑定与文件共享合同，不代表真实登记生成链已通过。后续生产者必须由受信原生维护 owner 冻结实际来源，分配 backupId，并在实际密文完成、源一致性核对和短期角色成功回收后才 force/无覆盖原子发布；未知收尾不得写 `RECLAIMED`。不能把测试的 `WriteSource` 搬成产品功能，不能通过包旁完成文件、口令、手工 SHA 或布尔标志补信任。

本轮消费检查不打开源 Profile/DB，保留“maintenance authority 可与备份来源不同、源 DB 可离线”的设计。真实 authority 仍须走既有 `OpenForPlanning` / Native Verify；本轮测试没有调用这些真实入口，不能据此声称该集成已验收。

计划仍不是 SQL 来源能力，当前方法也不读取或认证包。将来执行者必须重新 pin 同一来源登记、匹配计划中的记录摘要、完整认证所选包并核对摘要/字节数/manifest，再检查维护与新目标前提；历史 v1 计划不能被静默升级为可执行计划。无记录或记录变化不能靠重算计划 SHA 放行。DPAPI 继续服从现有同 SID/管理员威胁边界，不是发行者签名。

## 最小验证

只运行一次 `dotnet test`，过滤两条既有 Fact：**2 PASS、0 failed/skipped**，汇总 449 ms。Desktop/Profile Debug 与测试工程成功编译，日志无警告/错误。

- `RecoveryPlanIsPrivateDurableNonAuthorizingAndInterruptedCandidatesCannotStart`：使用全新私有临时应用树和真实 DPAPI，覆盖来源缺失时无登记副作用、收尾/范围/版本/身份/端口/重复/未知字段/坏密文/摘要不符拒绝、pending 拒绝、来源句柄阻止写删及目录改名、v2 记录绑定、v1 可读但不可启动、偏好不变和计划发布异常保留 attempt。
- `RuntimeExitEvidenceNeverAuthorizesMaintenanceOrHidesInterruptedOwners`：由于复用了 `ReadRecord` 的句柄读取重载，对既有维护拒绝合同做一次回归；用合成目录及一个测试专属隐藏 `cmd.exe /d /q`，验证退出记录、锁、guard 和始终 `UNREGISTERED_WRITERS_UNPROVEN`。没有接触任何既有进程或服务。

证据位于 `.verification/recovery-slice2e/dotnet-test-focused.log`、`test-results/recovery-source.trx`。本机缓存加空本地 feed 离线 restore；产物独立放在本轮 `dotnet` 目录，单 MSBuild worker、`DOTNET_PROCESSOR_COUNT=2`、无共享编译服务。本轮没有失败构建/失败测试重试，没有全套、Java、前端重建、数据库或模型测试。使用既有 `RuntimeLifecycleProbe=true` 仅解决条件编译；经测试适配器执行上述 Fact，未运行 probe Main/apphost。

Windows 默认沙箱初始化仍报 `helper_sandbox_lock_failed`；只对已授权的精确工作区读写/聚焦验证使用经审核的执行权限，未修改沙箱 ACL。一次并行只读文档读取因 exec-server transport disconnected 未执行，之后单独读回成功；没有不明状态的数据动作。

实现前查看了 GitHub 的 [.NET ProtectedData](https://github.com/dotnet/runtime/blob/main/src/libraries/System.Security.Cryptography.ProtectedData/src/System/Security/Cryptography/ProtectedData.cs)、[restic repository](https://github.com/restic/restic/blob/master/internal/repository/repository.go)；沿用平台保护与既有原子计划发布，不引入密码算法、签名方案或通用仓库。现有发布方式也参考了 [ASP.NET Core 文件仓库](https://github.com/dotnet/aspnetcore/blob/main/src/DataProtection/DataProtection/src/Repositories/FileSystemXmlRepository.cs)，本项目继续拒绝 copy 回退。

## 尚未完成与下一步

旧包、跨会话未登记执行、外部 SQL 及模型未知执行的完整停写证明仍缺失，维护结果没有放行值。下一步仍应优先解决这条前提和源数据库一致性窗口，再把真实成功备份/短期角色收尾接到独立登记生产者。本轮未创建任何生产来源登记，不能宣称“受信备份链已可用”。

无 Runtime 的维护 Java/provision 接口、真实来源登记发布、新 DB/角色、prepared-target 核验、目标凭据/CRUD grant、完整读回/清理、READY 与显式激活均未完成。真实 Native Gate 到 UI、真实 Profile 生命周期、来源登记生产链及恢复端到端尚未验收；保留 BLOCKED，不用合成记录补 PASS。

五份原有未跟踪文档不修改、不入提交，首尾摘要见本轮 `preserved-before.json` / `preserved-after.json`。未触及真实数据库、Knowledge 源、用户 Profile 或 KAG，未启停现有服务，未合并 main、部署或构建受信分发包。既有公开推送审批拒绝仍待用户明确确认；本轮未推送、重试或绕过。最终本地提交信息保存在 `.verification/recovery-slice2e/git-delivery.json`。
