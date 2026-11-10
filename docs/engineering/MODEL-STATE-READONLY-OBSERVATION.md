# model-state 原操作边界与一次只读观察

2026-11-10，输入基线 `74a947f`。**普通权限只读观察成功：当前目标是普通目录，owner SID 等于本次诊断的有效 TokenUser SID，不等于同进程 Java `user.name` 解析出的 SID。原失败原因仍不能据此追认；没有重试产品构造、登记或 EXE。** 完整 EXE／WebView、真实模型恢复和维护继续 **BLOCKED / productionGo=false**。

## 旧动作：具体被拒的是什么

精确路径：`C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2\model-state`。

原 `SeedReading.java:43` 的 `new ModelStateStore(...)` 在构造函数 `:93` 调用 `verify(root,true)`，于 `:496` 抛出 `Invalid model state ownership or type`。这是**产品准入校验拒绝，结果已知**，不是 OS 访问拒绝或工具／自动审批拒绝。之前的账户查找、平台判断、路径检查、`createDirectories` 已返回；不能说整个操作未执行，也不知道此次是否新建了目录。之前的合成资料／历史写入已执行。`rootKey` 尚未完成赋值，后续 `.lock` 创建／排他锁、状态文件读取、`:44` 的 `armGuard`、最终 fixture 回执和 EXE 均未到达。

核对了原失败目录中的 Seed 源码、错误日志、prepare/startup 回执，以及阅读连续性、导航、身份来源、拒绝分支、导入连续性、参考准备、草稿文件和整合交付记录。这条已保存历史链记录的是原构造失败与其后未访问原对象；**没有该精确对象独立类型／FileID／owner 读取被拒的记录，后续记录明确未做此采样。** 可见房间历史对该 Profile ID 和 `model-state` 的检索也无命中；这不代表能证明未保存历史不存在。其他 Profile、端口、进程和历史目录的拒绝未混入此对象。仓库和上级候选路径未找到适用 `AGENTS.md`。

本次依据接续指令补充的明确范围，在上述历史核对完成后才执行普通权限最小观察；不是将此前产品拒绝当作不存在，也不授予原动作重试许可。

## 本次实际调用面与结果

一次性诊断没有导入产品类。复用包内 Java 21 与 JNA 5.17.0，从 JAR 精确提取既有 DLL 到本轮证据目录，校验固定 DLL 哈希；没有访问 Profile 内的 JNA、credentials、data 或 evidence。没有改 Java 用户名、执行身份或权限。

| 动作 | 实际范围 |
| --- | --- |
| 自身上下文 | `System.getProperty("user.name")`；本进程 `LookupAccountNameW` 解析 SID；`OpenThreadToken(TOKEN_QUERY)`，仅无线程令牌时取自身进程令牌；`GetTokenInformation(TokenUser/TokenElevation)`。无提权、令牌调整或其他进程查询。 |
| 目标打开 | 一次 `NtCreateFile`，完整精确路径、`FILE_OPEN`、`READ_CONTROL\|FILE_READ_ATTRIBUTES`、共享读／写／删除、`OBJ_CASE_INSENSITIVE\|OBJ_DONT_REPARSE`。不申请内容读写／列目录／删除权限，不使用 backup intent，不打开其他目录作替代。 |
| 持有句柄读取 | `GetFileInformationByHandleEx(FileAttributeTagInfo/FileIdInfo)`、`GetFileType`、`GetFinalPathNameByHandleW`；仅 `GetSecurityInfo(OWNER_SECURITY_INFORMATION)`，不读取 DACL／SACL。SID 转换、`LocalFree`、`CloseHandle` 收尾。 |
| 停止规则 | 任何目标调用失败立即停止，无备用 API、重试或提权；重解析、非磁盘、路径不符也停止。本次全部成功，没有触发该停止分支。 |

`OBJ_DONT_REPARSE` 的依据为 [Microsoft OBJECT_ATTRIBUTES 定义](https://learn.microsoft.com/en-us/windows/win32/api/ntdef/ns-ntdef-_object_attributes)；[NtCreateFile](https://learn.microsoft.com/en-us/windows/win32/api/winternl/nf-winternl-ntcreatefile) 说明 `FILE_OPEN` 不创建缺失对象，其函数页的 Attributes 列举比结构页窄。本次记录实际参数与返回值，不把一次成功当作完整平台兼容性证明。[GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo) 支持按句柄只取 owner。只查询公开 API 名／官方资料，未发私有路径或任务数据，未调用反馈或 Tavily。

采样发生于 **22:33:13Z**，`NTSTATUS=0x00000000`；非提升上下文；默认 `sun.nio.fs.WindowsFileSystem` 支持 DOS、不支持 POSIX。当前 `directory=true`、`reparse=false`、磁盘类型为 1、最终路径相符。卷序号／128 位 FileID 和三个 SID 已保存在本地原始回执，未写进跟踪文档。

| 对照 | 当前观察值／源码含义 |
| --- | --- |
| 目录类型 | 是目录，与 `verify(root,true)` 的期望相符；未重新执行 Java Basic 属性读取。 |
| `!windows && fileKey==null` | 已观察 provider 与能力；按已审计 Windows provider 实现，`windows=true`，此条件短路为 false。原生 FileID 不等于 Java fileKey，未将二者混同。 |
| owner 与 Java 命名账户 | SID 不等。根据已审计 Windows principal 的 SID 相等性契约，若将这批当前值用于原表达式，最后一个拒绝条件为真；没有实际调用 `Files.getOwner(...).equals(account)` 或产品 `verify`。 |
| owner 与有效令牌 | SID 相等；Java 命名账户 SID 与有效令牌 SID 不等。这只对照原生 owner 子条件，不代表私有 DACL／祖先／完整准入已通过。 |

只读操作持有短暂 OS 句柄并生成本地诊断文件；没有目标业务内容／ACL／guard 写入，没有产品 writer 锁或租约。未运行 `profileRoot`、`WindowsNativeGate.admit`、`WindowsPrivateAcl.verify`、Store 构造器、`armGuard` 或应用入口。

## 证据、限制与剩余动作

证据目录：`.verification/model-state-readonly-scope-20261110/`。`ObserveModelState.java`、`Run.ps1` 为一次性诊断；`attempt.json` 绑定源码 SHA-256；`observation.json` 含本地身份原值，不应外传或加入 Git；`compile-errors.log`、`observation-errors.log` 为空。`cleanup.json` 记录 Job 0、Job／`Local\Dot_KAG_Workspace_HeavyRun` 已释放、failure=null。沿用 kill-on-close、自有 Job、768 MiB 单进程／2 GiB Job；编译和诊断分别限 25 秒，诊断须收到已归属 Job 的握手才开始。没有后台验收继续运行。

所有类型、身份、FileID 和 provider 值都属于这次新诊断。旧失败没有这些快照，也没有历史 FileID 可证明当前路径仍是当时同一对象；新进程不能重建旧进程的环境、有效令牌或两次检查间的变化。因此已经从“机制可能不同”推进到“本次当前上下文确实不同”，**尚未从当前观察推进到原失败根因证明**。

下一具体工作是审查这条身份差异是否符合产品账户契约，并确定保留所有权、私有目录和请求保护的修复／可区分原因方案。此观察不允许自动替换账户、改 `user.name`、删减 owner 校验、清 guard 或重试原登记；原登记与完整桌面启动仍是未执行的后续动作。

对照最新交付和路线图后，没有新确认且适合本轮独立完成的产品模块；没有重复已有资料／记忆／会话流程或为计数加规则。未运行旧测试、未重新打包、未访问真实数据／凭据／模型，main、六份用户文档、旧包保持原样。此次仅新增调查证据和本报告，不是产品修复或验收通过。
