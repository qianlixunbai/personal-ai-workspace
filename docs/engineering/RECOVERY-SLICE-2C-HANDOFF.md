# Recovery Slice 2c：凭据读取前的 Runtime／维护互斥

日期：2026-11-07（Asia/Shanghai）。起点为本地 `abe6530acc43a4027dfd5cace20e8c991fc3599b`，继续 `codex/recovery-v3-workspace-core`。本轮只在本地开发、验证、提交，**不推送或重试推送**。这是 Slice 2 的早期互斥子模块，不是完整维护准入、可信备份完成或 Production GO。

## 已实现

- `NativeProfileRecovery.RuntimeLease` 固定使用已通过原 Native Gate 的 Profile 下 `evidence/runtime-admission.lock`。正常 Java 路径创建私有空文件时使用既有 CREATE_NEW 原语；文件已存在则只核验、不截断或修复。核对私有 ACL、真实文件身份、单硬链接、零长度，并持有 READ + NOSHARE_WRITE/DELETE 文件句柄。多个正常 Runtime／原生配置子进程可以持有共享读租约，保留既有并存行为。
- `WindowsNativeGate.profileRoot()` 在返回 Profile 前取得并复核租约。正常 Runtime 的 `requireRuntimeArguments` 位于凭据加载及 Spring 初始化之前；数据库和 Finance 原生配置入口也在发送 `ADMITTED`、接收输入之前经过这里。租约由 Gate 的私有静态字段持有到进程终止，不随 `app.run()` 返回或 shutdown hook 提前释放；丢失或关闭后拒绝，不静默重取。原 helper/JNA 准入及恢复候选拒绝没有放宽。
- `WindowsPrivateAcl.holdDevelopmentProfile` 追加固定 `evidence` 子目录句柄与私有 ACL 核验，防止租约期间移动目录改变锁名所指对象。
- C# 维护前置核查先固定同一 evidence 目录，以既有文件的 READ/WRITE、仅 SHARE_READ 句柄取得排他维护权，再核对 model-state `.lock`、guard 和历史退出观察。Windows 的共享规则使此写句柄与 Java 读租约双向冲突。维护端不创建缺失文件、不写文件内容；异常不吞掉、不修复。成功核查后仍保留两个锁直到 coordinator 释放。

这是文件句柄的进程间互斥，不把空文件本身当作可信登记或已停止证明。路径均由已有 Profile authority 推导，没有新增 HTTP、React、任意路径命令或用户可伪造的跳过开关。

## 仍然拒绝实际维护

`MaintenanceInspection.Blocker` 仍固定为 `UNREGISTERED_WRITERS_UNPROVEN`。本轮只覆盖采用新代码的 Java 路径；旧包、未登记会话、外部 SQL 客户端和模型执行归属仍不能靠此文件推断不存在。原 model-state guard、所有 attempt 的完整退出证据和 Local mutex 仍保留；没有删除 guard、补写历史、取消任务、强杀服务或把退出码零等同于业务完整性。

下一步必须在受控条件下补齐这些主体的覆盖和旧包兼容处置，再落实 `Program` 在正常 Runtime 创建前的原生维护分派。不得简单把 blocker 改成成功。受信来源登记仍须绑定 v3 完整密文／manifest 摘要、源身份及短期角色收尾；计划字段和本轮锁文件均不提供 SQL 来源能力。

本轮未实现来源登记、短期源角色授权／回收、数据库 provision、prepared-target 核验、目标凭据、恢复／READY 或显式激活。未更新 Runtime/helper pin、未打包受信分发产物、未合并 main 或部署。完整 Gate／Profile 和旧包共存必须在后续授权的隔离环境验收，不能由本轮单元证据推定通过。

## 断线恢复与聚焦验证

断线前完成了三个 Java 生产类及 owning 测试的聚焦编译、.NET 离线依赖还原；测试命令随后因 exec-server 连接中断没有返回结果。重连时先核对工作树：正好保留七个源码／测试改动；五份未跟踪文档的 SHA-256 与入场记录相同；没有 `java-test.log`，且没有命令行匹配 `RunEarlyLease`／`recovery-slice2c` 的 Java、javac、dotnet 或 testhost 进程。本轮没有停止任何进程，然后才补跑待完成验证。

| 验证 | 结果及边界 |
| --- | --- |
| Java `TrustedDatabaseConfigurationTest.earlyRuntimeLeaseExcludesMaintenanceBeforeCredentialsAndCannotReopenAfterLoss` | 1 PASS，0 failed/skipped，550 ms。仅本轮 `@TempDir` 替身及真实 JDK／Windows 文件共享；ACL 和文件身份为 mock，以反射设置并最终清除测试 JVM 的 synthetic cached admission。验证共享读者并存、与维护写句柄双向冲突、禁止移动锁文件、身份变化／租约丢失／非空文件拒绝；维护锁阻塞 `NativeDatabaseSetup` 时输入字节完全未读取，未创建 data/credentials。不是原 Native Gate 或真实跨会话验收。 |
| C# `Mmf3AcceptanceTests.RuntimeExitEvidenceNeverAuthorizesMaintenanceOrHidesInterruptedOwners` | 扩展原有 Fact，1 PASS，0 failed/skipped，290 ms。真实 DPAPI/私有 ACL/文件句柄的临时替身；覆盖维护端缺失不创建、非空不修复、早期读者冲突、目录 pin 和既有 guard／历史拒绝。只启动测试自己的隐藏 cmd 子进程并正常结束；没有运行产品 Runtime/helper。 |
| 编译 | Java 聚焦编译成功，12 条既有类型的 `ExtendedOpenOption` 内部 API 警告；Desktop Profile Debug 与测试构建成功，日志无编译警告或错误。没有构建 Java 分发 JAR 或 NativeBootstrap。 |

两项测试在确认断线现场后各实际执行一次；没有执行全套、额外回归、数据库、模型、前端重建或 probe Main。Java 验证进程限定 256 MiB 堆与 2 个逻辑处理器，Desktop 单构建 worker；所有产物在 `.verification/recovery-slice2c/`。Java 日志和合成目录保留；C# 路径记于 `test-results/recovery-early-lease.trx`。没有本轮独立代理评审、真实 Profile 验收、跨 Windows 会话试验或断电试验。

五份原有未跟踪文档不修改、不加入提交，首尾哈希见 `preserved-before.json`／`preserved-after.json`。未访问真实个人数据库、Knowledge 源或 Profile，未启停用户既有 PostgreSQL/Ollama/Desktop/Runtime，KAG 未触及。最终本地 SHA 及暂停推送状态记于 `git-delivery.json`，不沿用 Slice 2b 的远端状态推断本轮已发布。

实现前核对了 [OpenJDK 21 WindowsChannelFactory](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsChannelFactory.java) 的共享选项到 Win32 标志的映射。沿用已有 JDK 与 Win32 文件能力，没有新增依赖或互斥平台。
