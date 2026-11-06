# Recovery Slice 2a：恢复计划登记与候选拒绝

日期：2026-11-07（Asia/Shanghai）。起点为 `codex/recovery-v3-workspace-core @ eb823448464b59e6d2e9cef2852f109da0f09e42`，沿同一开发分支继续。**这是第二切片的准备子模块，不是完整 Slice 2，更不是个人恢复完成或 Production GO。** 没有合并 main、部署或构建受信分发包。

## 已实现的范围

- `ProfileRecoveryCoordinator` 的原生内部 `OpenForPlanning` 复核既有 Profile / helper 准入，持有源 Profile 的 `SingleInstance` 及目录句柄。路径由 OS Known Folder 的既有 `Parent()` 推导，固定为应用目录下 `Recovery/targets/<targetProfileId>`，独立于任何 Profile/data。没有命令行、网页或 UI 调用入口。
- `ReservePlan` 内部分配 operation、target Profile 及目标 DB/owner/app 名称。先独占保留登记目录，再用 DPAPI CurrentUser 写 `plan.pending`、force、同目录无覆盖 rename 为 `plan.dpapi` 并读回。它只记录 `RECOVERY_PLAN_ONLY / PREPARING` 意图；不创建真正的 Profile、数据库或角色，不读取源数据或备份包。
- `Inspect` 只读取有界密文并严格核对固定版本、字段、重复键、目标绑定及派生名称。目录身份/私有 ACL、无链接、单硬链接、读句柄和目标相关 DPAPI entropy 均参与检查。空目录、pending、额外文件、坏记录或未来状态保留现场并拒绝；没有删除、修复、覆盖、续写或自动重放。
- 发布异常携带本次 `Attempt` 身份，供调用方对同一目标进行检查。异常本身不能推断“没有写入”，也不能授权重试。
- `Mmf3AcceptanceLaunch.Validate` 和 Java `WindowsNativeGate` 增加独立拒绝条件：只要目标在登记目录中存在，即拒绝正常启动。登记初始化不完整、目录类型不符或读取失败也拒绝；只有明确不存在才按普通 Profile 继续既有准入。手写 `ACTIVE` / READY 文件不会放行。Java 在原 helper 核验后、JNA 加载前检查，在绑定后及读取 Profile root 时复核；没有降低原 native gate。

## 这份记录不授予什么

计划里的 backupId / payload / manifest digest 仅为意图字段，**不是独立受信来源登记，也不是 SQL 来源能力**。它没有连接 Slice 1 的合成能力对象或任何恢复执行入口。DPAPI 保护仍服从既有同 SID / 管理员威胁边界，不是发布者签名。

取得当前会话 mutex 也不等于已安全停写。现有 `DevelopmentRuntimeOwner` 的直属进程句柄只在内存中，跨启动停止证据、其他会话/手动 Java 以及模型 guard 归属尚未形成完整维护准入。这是继续实现 provision 前必须完成的前置条件；本轮没有使用端口关闭或 HTTP 不通代替它。

所有恢复候选当前都被拒绝，尚无 `RECOVERY_READY`、`ACTIVATION_AUTHORIZED` 或 `ACTIVE` 发布路径。不能删除登记、提前复制 JNA 或将计划字段当成权限来开启候选。未来激活切片需在两层明确替换这条全拒绝合同，绑定受保护完整 receipt，并保留现有安全门槛。旧包仍不理解新登记；未来真正准备目标时必须继续延迟发布 JNA。

## 验证与证据

本轮只新增两个 owning-layer 用例，均追加到现有测试文件。没有删改历史安全测试或旧证据。

| 验证 | 本轮结果与边界 |
| --- | --- |
| C# `Mmf3AcceptanceTests.RecoveryPlanIsPrivateDurableNonAuthorizingAndInterruptedCandidatesCannotStart` | 合成临时应用目录、真实 DPAPI/Windows 文件句柄/ACL；验证关闭后读回、目录 pin、无覆盖、篡改及重复字段拒绝、pending/空候选拒绝、普通身份继续、偏好不变及异常携带 attempt。没有调用 OS `Parent/Root/Prepare`、`OpenForPlanning`、helper、Runtime 或数据库。 |
| Java `TrustedDatabaseConfigurationTest.recoveryReservationsRejectRuntimeAdmissionWithoutReadingCredentialsOrTrustingMarkers` | 一个 JUnit 用例，1 PASS、0 failed/skipped；135 ms。使用本轮 `.verification` 下的 `@TempDir`，验证不完整登记、候选/文件/伪造标记拒绝及普通身份继续。仅执行无 native 的附加目录拒绝逻辑，不代表完整 Windows Gate 实机通过。 |
| 编译 | Desktop Profile Debug 与新生产代码成功编译；Java 仅编译本次两个生产类和 owning 测试，5 条既有 `ExtendedOpenOption` 警告，无错误。没有重新打包 Runtime/helper 或更新 pin。 |

首次 Desktop 测试构建漏传既有 `RuntimeLifecycleProbe=true`，触发旧 `PostgresqlConversation` probe 条件编译引用缺失，**零测试执行**。补齐仓库已有模式后单用例通过（161 ms）；随后补充未知结果身份及有界枚举，再对同一用例聚焦复验通过（141 ms，1 PASS、0 failed/skipped）。本轮 C# 实际运行同一用例两次，Java 用例一次，均通过；没有其他测试执行。最终记录见 `.verification/recovery-slice2a/test-results/recovery-plan-final.trx`。测试宿主只执行过滤后的 Fact，未执行 probe Main。依赖从本机缓存离线还原；编译输出均置于本轮隔离目录。

证据目录：`.verification/recovery-slice2a/`，含失败/成功日志、TRX、Java 单用例日志、编译输出和保留的合成 Java 临时树。C# 合成临时目录路径记在 TRX 标准输出中。未执行全套测试、PG 备份/恢复、模型请求、真实 Profile 准入/停止验收、受保护卷验收或断电试验。未进行本轮独立代理 Review，不沿用 Slice 1 的代码级 GO 作为本轮审查结论。

## 下一步交接

按保留的本地 `CURRENT-PROFILE-RECOVERY-DESIGN.md` 第 3–4 / 8 节，继续 Slice 2：先实现可核对的直属 Runtime / 持久 guard 停止证据与原生维护分派，再把成功备份和短期角色收尾绑定到独立受信来源登记。之后才能落实新 DB/角色 provision 与 prepared-target 原生核验；缺少证据或权限时停止该操作。

后续 Slice 3 仍需目标凭据绑定、真实 CRUD grant、数据与文件读回、清理与 READY 发布；Slice 4 才实现用户显式激活和成功后保存选择。Slice 5 再按设计收一次隔离端到端证据。真实个人数据库、Knowledge 源文件和 Profile 本轮均未访问；现有 PostgreSQL/Ollama/Desktop/Runtime 未启停或修改。KAG 未触及。

入场五份未跟踪设计/报告保持原样且不加入提交；首尾 SHA-256 核对见本轮证据。最终提交和远端 SHA 由交付回复及 `git-delivery.json` 记录，不能由本文件自引用推断。

实现前参考了 [.NET DPAPI](https://github.com/dotnet/runtime/blob/main/src/libraries/System.Security.Cryptography.ProtectedData/src/PACKAGE.md) 和 [ASP.NET Core 文件存储原子发布](https://github.com/dotnet/aspnetcore/blob/main/src/DataProtection/DataProtection/src/Repositories/FileSystemXmlRepository.cs)。采用现有系统能力；本合同不采用非原子 copy 回退，发布异常保留现场。
