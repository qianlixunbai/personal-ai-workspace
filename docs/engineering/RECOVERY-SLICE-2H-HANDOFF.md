# Recovery Slice 2h：原生维护排他句柄交接

日期：2026-11-07（Asia/Shanghai）。基于 `a07e748b0e2fc0325b6bf91fdfd3cf96d40ad8f6`，分支 `codex/recovery-v3-workspace-core`。本模块解决 2g 明确留下的前置问题：原生维护持有排他写句柄时，Java 不能再走普通 Runtime 的共享租约入口。现在可以把**同一个 Windows 内核文件对象的句柄直接转交给直属 Java 子进程**，并保持原生父端的原句柄。该原语已完成真实 C# → Java 合成验证；产品维护调度/协议尚未接线，维护仍为 **BLOCKED / UNREGISTERED_WRITERS_UNPROVEN**。

## 实现范围

- `ProfileRecoveryCoordinator.Runtime.cs` 保留已核验的现存 `runtime-admission.lock` 写句柄；新增 `ChildLease.cs` 在原生 authority 重核后仅允许一次 `DuplicateHandle`。目标必须是调用方持有的活进程对象，同会话且不是自身；不按外部 PID 重新打开。保留原句柄，复制句柄不可继承，不写文件/环境变量/命令行。未来传输层必须从自身受信且固定 JAR/JVM 的直接启动取得该 Process，等待真实 Gate 完成后经私有 stdin 传递，并核对接收端进程身份。此方法本身尚不证明子进程来自受信启动器。
- `WindowsNativeGate` 增加独立的维护资源路径：先执行原有 Native Gate，再接收排他句柄。选择维护模式之后，即使接收失败或主动关闭，也禁止回退普通 Runtime；已经持有 Runtime 租约的进程不能切换。维护路径只接受 Gate 自身保存的同一 `MaintenanceExclusion` 对象，参数仍严格匹配既有固定 Profile 参数。
- `MaintenanceExclusion` 核对真实磁盘文件类型、规范终路径、FILE_ID_INFO、单链接、零字节、无 reparse/待删除状态。`FlushFileBuffers` 校验实际写访问，拒绝只读句柄冒充；额外固定运行锁文件名、model-state 目录以及现有模型锁。原有 Gate 固定 Profile/evidence 祖先链，资源对象持续复核身份和私有 ACL。没有重新创建或修补锁文件，没有用声明字段代替内核占用。
- 无 Native helper 字节或 pin 变更，无凭据读取/传输、SQL 操作、来源记录发布或新的成功维护凭证。该对象仅表示合作入口的资源排他性，不能证明旧包、外部 SQL 或未知模型执行已经停止。

## 必要关键验证

新增显式手工探针 `bootstrap/tests/RecoveryMaintenanceExclusionProbe.cs/.java`，不自动加入整套测试。C# 在全新随机临时目录创建私有合成树及两个空锁文件，通过反射调用私有 OS 原语；直接启动一个 Java 21 子进程，核对实际 JVM PID 后只在 stdin 发送本次句柄。探针不调用真实 Native Gate，不访问 Known Folder 中的个人 Profile、任何数据库、Knowledge 源文件、凭据或模型服务。JVM 限两核、128 MB；没有启动 PostgreSQL/Ollama/KAG。

最终同一用例 **PASS**，实际覆盖：

1. 原生父端向直属 JVM 完成真实 `DuplicateHandle`，Java 校验并持有同一文件对象。
2. 父端关闭原句柄后，普通 Runtime 的 read/no-share-write 打开仍失败；模型写入、运行锁删除和模型目录改名均失败。
3. 子进程持续复核通过；释放后以普通只读句柄再次申请被拒绝。
4. 该子进程正常退出，运行读租约与模型写句柄均可重新打开；进程清单核对没有遗留探针。

验证只运行这一项，因明确失败作必要诊断：初次 JNA `LARGE_INTEGER` 方法名编译错误已修正；一次诊断构建使用错误条件属性，在启动子进程前失败；Oracle `javapath` 包装器使目标句柄不属于实际 JVM，改为直接 JDK 可执行文件并加入 PID 握手；随后发现 JNA 5.17 的 `FILE_STANDARD_INFO` Java boolean 映射与 Windows 的单字节 BOOLEAN 不符，现按官方头文件读取精确 24 字节布局，未删除校验。四次未成功的探针调用及最终一次成功日志均保留，不把失败记作 PASS。

技术问题先核对 GitHub：[JNA 5.17 WinBase.java](https://github.com/java-native-access/jna/blob/5.17.0/contrib/platform/src/com/sun/jna/platform/win32/WinBase.java)、[WinNT HANDLE 映射](https://github.com/java-native-access/jna/blob/5.17.0/contrib/platform/src/com/sun/jna/platform/win32/WinNT.java) 与 [微软 WinBase.h](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/WinBase.h)。定向 Java 编译成功，既有 `ExtendedOpenOption` 内部 API 警告保留；启用真实 `Mmf3ProfileAcceptanceBuild=true` 的 Desktop/探针构建为 0 warning / 0 error。未跑全套测试、产品打包或真实 Native Gate 验收。

本地证据：`.verification/recovery-slice2h/transfer-integration-corrected.log`、`javac-corrected-layout.log`、`dotnet-build-final.log`、前置失败诊断日志、`preserved-before.json` / `preserved-after.json`。成功的合成空文件夹具保留在该日志记录的随机临时根；没有清理用户目录。五份原有未跟踪文档逐份大小/SHA-256 不变，未暂存或提交。

## 后续核心工作与限制

当前已验证的是 OS 排他资源的跨语言交接，**不是受信来源生产者的端到端成功**。尚须实现版本化、有界的直属维护子进程协议与受信入口分派，核对实际启动/退出和失败收尾；在原生 authority、完整停写证明及受保护 Workspace 来源配置均成立后，才允许 2g 的真实执行核心产生个人来源结果。不能以句柄、调用者布尔值、临时文件或探针结果补齐这些授权。

然后需要将同一次执行的 backupId/逻辑 ID、固定 root/data 身份、实际 PG 实例/OID、包/manifest 摘要与短期角色清理结果绑定；由原生 owner 从已验证的子进程结果 force/无覆盖发布独立 `Recovery/sources/.../source.dpapi`。通用 `PublishSource(外部元数据)` 仍不成立。旧包/跨会话、外部 SQL、未知模型执行的停写证明仍缺失；真实目标恢复、prepared-target、READY 与显式激活均未完成。

本模块仅本地提交；没有推送较新提交、合并 main、部署或操作用户现有服务。此前获单独授权的历史 `abe6530` 推送结果见 2g 交接，本模块没有重复执行。最终本地提交信息见忽略的 `git-delivery.json`。
