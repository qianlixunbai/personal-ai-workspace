# Recovery Slice 2b：直属 Runtime 退出观察与维护前置核查

日期：2026-11-07（Asia/Shanghai）。起点 `1273610737aa121ea916cb308a1765d38b195139`，继续 `codex/recovery-v3-workspace-core`。本轮完成 Runtime 持久证据子模块，**不是完整 Slice 2，不是维护准入或个人恢复完成，不构成 Production GO**。没有合并 main、部署、重打包受信 Runtime/helper、更新 pin 或构建受信分发包。

## 已完成

1. `ProfileRecoveryCoordinator.Runtime.cs` 在 OS 派生的应用私有目录 `Recovery/runtimes/<profileId>/<attemptId>` 保存不可覆盖的 `intent.dpapi`、`started.dpapi`、`exited.dpapi`。开始启动前先分配 attempt 并发布意图；启动后绑定 Desktop/Runtime PID、启动时间、会话和已核对的 JAR 摘要；退出事实只从 `Process.Start` 返回且仍持有的实际进程句柄观察，不从磁盘 PID 重新查进程，不用端口/HTTP 状态推断退出。
2. `DevelopmentRuntimeOwner` 在现有启动/清理边界接入记录。退出观察严格位于现有 Job dispose/Kill 之前；当时进程仍活着就不写退出记录。取消路径即使已观察到退出也标记 `OWNER_STOP_REQUESTED`，不作为停止前提。Dispose 不补写、不杀进程、不覆盖或重放；新增记录/通知异常不能跳过原有清理。没有把维护操作接到 `StopAsync`，没有改变其原本取消/清理策略。
3. `InspectMaintenancePrerequisites` 是原生内部、只读的拒绝式核查，复用已持有 Profile mutex/目录的 coordinator。它要求历史存在、每个 attempt 都有完整且一致的三份记录、退出码为零且无 owner stop 请求；缺失、额外文件、pending、坏密文、未知/重复字段、错误身份、未来版本均拒绝。最多核对 4096 次尝试，超限拒绝；不只挑最新一份。它固定并验证 model-state 私有目录，以禁止共享写/删除的现有 `.lock` 读句柄排斥既有 Java writer，拒绝 execution guard、任何 pending 与未知目录条目，并把读锁持有到 coordinator 结束。

记录复用 Slice 2a 的 DPAPI CurrentUser、私有 ACL、无链接/单硬链接、有界读取及 CREATE_NEW → force → 同目录无覆盖 rename → 读回逻辑；没有新增密码算法、依赖或通用存储平台。`RuntimeRegistrationIncomplete` 保留本次 Profile/attempt 身份以供后续检查，不授予重试权限。正常 Profile 选择偏好及所有恢复候选拒绝规则保持原状；新日志初始化同时建立 `Recovery/targets`，遵守旧的“不完整登记拒绝启动”规则。

## 必须保留的停止边界

**有完整退出观察仍只返回 `UNREGISTERED_WRITERS_UNPROVEN`，没有成功准入值或维护执行能力。** 它只证明被登记的直属子进程退出被观察到，不能证明所有 writer 或 Ollama 已停止，退出码零也不是业务完整性证明。

静态检查发现 `PersonalAiWorkspaceApplication.main` 在创建 `ModelStateStore` 前已经加载受信数据库配置并进入 Spring 初始化；因此只取得 model-state `.lock` 不能阻止手动 Java 的更早数据库访问，也不能覆盖旧包、其他未登记会话和外部 SQL 客户端。现有 `SingleInstance` 又只覆盖 Local 登录会话。不能用本轮观察记录或缺少 guard 推导这些主体不存在。

另一个实际限制是原 owner 的普通退出最终可能走 Job/Kill；本轮不会把此路径补记为干净退出。历史无记录、启动中断或清理前未观察到退出会持续使维护核查拒绝，即使以后另开新 Runtime 也不会隐藏历史缺口。没有自动 GC、补写旧证据、读取 PID 后推断退出或解除未知状态的工具。常规 Runtime 启动并不要求历史全已结束，但新意图发布失败会阻止该次新子进程启动；`Recovery` 初始化中断仍按 Slice 2a 拒绝启动。此可用性代价需要在产品维护入口完成前解决，不能靠删登记绕过。

DPAPI/ACL 沿用同 SID/管理员威胁边界，不提供发布者签名或抵御同用户恶意回滚的承诺。目录枚举及 guard 检查也不构成全局停写快照。本轮没有尝试扩大它们的权限含义。

## 验证

仅新增一个 owning-layer Fact，放入现有 `Mmf3AcceptanceTests`；因共享计划记录读写被提取，连同原 Slice 2a 计划 Fact 一起执行一次：**2 PASS，0 failed/skipped**，测试日志汇总耗时 336 ms。新增用例 226.9425 ms，计划回归 101.4979 ms。证据为 `.verification/recovery-slice2b/test-results/recovery-runtime.trx` 和 `dotnet-test.log`。

新用例只创建 `paw-recovery-runtime-<uuid>` 私有临时替身目录，使用真实 Windows DPAPI/ACL/文件句柄，并启动一个隐藏、标准输入控制、工作目录固定到该替身的 `cmd.exe /d /q` 子进程；它由测试发送 `exit 0` 正常结束。未启动 Java/Runtime、helper、模型或服务，没有调用产品 `Parent/Root/Prepare/Begin/OpenForPlanning`。覆盖缺失/部分历史、活进程不产出退出记录、重开读回、不覆盖、篡改/重复字段/pending 拒绝、writer 共享冲突、目录 pin、guard 保留及始终不授予维护权限。合成路径记在 TRX 标准输出，保留现场。

测试通过后的最终代码检查补了清理层嵌套 finally，确保新增记录或通知异常不能跳过原有 owner 清理；其后只做最终 Debug 编译，未重复测试。最终编译零警告、零错误，见 `dotnet-build-final.log`。测试及编译使用本机缓存离线 restore、单构建 worker、隔离 artifacts；没有执行 probe Main、全套测试、Java 测试、数据库/模型请求或前端重建。真实生命周期集成、跨会话/旧包兼容、断电持久性均未验收；没有本轮独立代理评审，不能借用 Slice 1 的独立评审结论。

## 后续顺序

下一模块先补齐数据库访问之前的 Runtime/原生维护互斥及未登记/旧客户端的保守处置，结合既有 guard 合同解决可核对的停止归属；再实现 `Program` 正常 Runtime 创建之前的原生维护分派。不能简单把本轮 blocker 改成成功，也不能让 `.lock` 的当前排斥效果替代这份证明。

维护窗口完整后，才能把成功的 v3 密文发布、payload/manifest 摘要、源身份及短期源角色收尾绑定到独立受信来源登记。本轮**未实现**来源登记、短期角色授权/回收、数据库 provision、prepared-target 原生核验、目标凭据、restore/READY、显式激活或 UI。Slice 2a 的计划字段仍不是 SQL 来源能力；目标准备仍不得提前发布 JNA。

本轮未访问真实个人数据库、Knowledge 源或 Profile，未启停既有 PostgreSQL/Ollama/Desktop/Runtime，未接触 KAG。五份入场未跟踪文档首尾 SHA-256 一致且不加入提交，见 `preserved-before.json` / `preserved-after.json`。最终提交/远端 SHA 由 `.verification/recovery-slice2b/git-delivery.json` 和交付回复记录。

实现前参考 GitHub 上 [.NET Windows Process 句柄实现](https://github.com/dotnet/runtime/blob/main/src/libraries/System.Diagnostics.Process/src/System/Diagnostics/Process.Windows.cs) 与 [ASP.NET Core 文件发布](https://github.com/dotnet/aspnetcore/blob/main/src/DataProtection/DataProtection/src/Repositories/FileSystemXmlRepository.cs)。沿用系统能力；本合同不采用 copy 回退，也不将进程退出等同于业务停止。
