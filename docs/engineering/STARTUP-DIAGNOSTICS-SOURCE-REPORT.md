# 启动诊断源码补齐

2026-11-11；基线 `6f0fbcb57afef0c747f3f1d9aa0985cba4281c5c`，分支 `codex/knowledge-version-compare`。本轮落实[首次失败阶段定位](LIVE-STARTUP-FAILURE-PHASE-DIAGNOSIS.md)提出的两项诊断缺口，仅改 Desktop 诊断源码并做静态检查。没有启动产品、第二轮、helper、浏览器、JVM 或模型，没有构建、UI 操作、进程遍历或强杀。启动计数仍 **1/2**；旧监督终态及共享互斥释放仍未知；`productionGo=false`。

## 已实现的诊断

### Runtime：固定阶段与首次原始失败码

`RuntimeStartupTrace` 仅进入原有 `MMF3_PROFILE_ACCEPTANCE && DEBUG` 构建。正常 owner 在既有源码／Profile／原生启动前校验成功后，尝试向现有 `logs` 目录创建 `runtime-startup-<随机文件名>.jsonl`；不创建目录、不修复权限、不写严格定义的 attempt journal。首次阶段在校验之前也会输出到宿主控制台，但校验前失败不会产生本文件。诊断 I/O／序列化异常被捕获，不参与产品状态、准入或关闭决定。

合同为 `WORKSPACE_RUNTIME_STARTUP_V1`，每次 owner 最多 40 条。字段仅有固定合同、阶段、事件、受控失败分类、白名单原因码、HRESULT、Desktop／自有 Runtime PID、序号和 UTC。没有 Profile 标识／路径、管道原文、challenge、token、凭据、用户内容、异常消息或堆栈。

- 阶段覆盖源码校验、StartInfo、Job／attempt 登记、自有进程创建与登记，以及协议头、Profile 比较、PID 比较、challenge 接收／回复、READY／attempt／challenge／端口核对，随后是 READY 回执、端口绑定、health、认证和进程检查。`NotStarted` 区分开始前就取消的情况。
- `FIRST_FAILURE` 只锁定一次；清理时的泛化状态不能覆盖已捕获的首次诊断。启动成功写 `READY` 并停止记录，后续正常退出不被误记为启动失败。该 READY 只代表此 Runtime owner 已走完既有启动检查，不代表 WebView、数据库业务或完整桌面验收通过。
- 原 `RuntimeStartupDiagnostic.Accept` 状态映射完整保留。私有管道 STOP 的 10 个既有固定原因码在映射前保留，例如 `POSTGRESQL_NOT_CONFIGURED`、`ROLE_REJECTED`；未知字符串只记录 `UNRECOGNIZED`，不保留其文本。`REMOTE_STOP`、`END_OF_STREAM`、取消与本地 I/O 错误可区分；不是每个错误都带远端原因码。
- 文件打开失败时仍尝试原生控制台输出；控制台也不可用时允许诊断缺失。**日志缺失不能证明某阶段未执行**。它不是启动／关闭回执，不用于恢复或进程归属判定，也不追溯补写首次运行已经丢失的信息。

### WebView：保留 ProcessFailed 的枚举与数值

复用 `WORKSPACE_UI_V1` 和现有条数上限，新增可空字段 `processFailedKind`、`processFailedReason`、`processExitCode`、`processDetailsAvailable`。Debug 收到事件时读取 SDK 的 `ProcessFailedKind`、`Reason`、`ExitCode`；字段为数值，不读取描述、模块路径、Frame／URL 或异常文本。若属性读取失败，保留已取得的数值，availability 为 false，并将该读取异常的 HRESULT 写入原 hresult 字段；成功读取时 hresult=0 仍只代表没有记录属性读取错误。

原 `closing || failed` 门控与首次失败状态保留；`Fail`、Profile AllProfile 清理、导航和关闭流程未改。**仍对原先所有 ProcessFailed 类型进入 fallback**；调整可恢复 GPU／utility 事件的产品处理属于行为改动，本轮未做。Release 不新增事件属性读取。SDK 中这些属性的存在已通过本地官方 XML 文档核对；数字含义须按候选实际使用的 WebView2 SDK／Runtime 版本解释，不能单凭 ExitCode 推断根因。

## 后续可证伪的问题

| 新证据 | 能区分什么 | 仍不能证明什么 |
| --- | --- | --- |
| `ProtocolHeader` + `REMOTE_STOP` + 白名单原始码，或 `END_OF_STREAM` | Java 主动发送固定 STOP，还是首帧前管道结束 | EOF 本身不能说明 NativeGate、JVM 或系统具体失败原因 |
| 最后协议阶段与 `RecordReady` 的首次失败 | 握手尚未完成，还是握手后持久回执失败 | 不等于 SQL／资料保存通过 |
| 原始码与既有泛化状态 | 例如原来同为 SCHEMA_NOT_READY 的三个具体原因 | 不能把未知文本还原为可信原因 |
| WebView kind／reason／exitCode，且 detailsAvailable=true | 主浏览器、渲染器、GPU／utility 等实际事件类型 | 不证明 AllProfile 清理导致事件，也不证明清理完成 |
| detailsAvailable=false 或整份日志缺失 | 诊断本身不完整，应保留未知结论 | 不能当作没有发生失败 |

## 已完成的检查与限制

证据保存于 `artifacts/startup-diagnostics-source-20261111/static-review.json`，含基线、5 个源码 SHA256、检查条件与结果。

1. 使用同一 PowerShell 宿主已加载的 Roslyn 5.0，仅 ParseText／读取语法树；5 个文件在 Profile Debug、普通 Debug、Release 三组符号下无语法诊断。没有创建 Compilation／Emit、调用编译器或加载产品程序集。首次尝试加载 SDK 另一份同名 Roslyn 时发生程序集版本冲突 `0x80131040`，未进入源码解析；随后使用宿主现有解析器。这是静态工具准备错误，不是权限拒绝或产品失败。
2. 源码 token 比较通过：去掉新增诊断语句后，owner 业务方法和 Native OpenAsync 与基线一致；owner 完整 finally、Native CloseAsync／ReadValue／Write／Stop、原失败状态映射、WebView Fail／Close／Dispose 方法保持一致；WebView InitializeAsync 仅替换诊断调用及事件参数名。原始码白名单与原映射的 10 个协议原因码一致。共 30 个静态检查项，**不是 30 个运行测试**。
3. 人工审阅输出字段、首次失败锁定、条数上限、Debug 条件及 SDK 属性声明。未更改 NativeGate、Profile 绑定、迁移、认证、数据库、关闭逻辑、协议校验与超时；六份原始文档保留，未读写真实 Profile 数据或受拒目标。

**尚未编译／类型检查或实际触发这些新诊断。** 当前准确候选仍来自 `422bcdf`，不含此补丁，不能用于验证新增字段。后续需在允许构建且资源协调就绪时，仅编译受影响 Desktop 配置，并检查固定码／未知码、首次失败与字段隐私；再更新同源候选、核对 pins。任何下一次实际运行仍须先解决现存监督／互斥状态缺口、确认原 Profile 准入与候选身份，并由父任务明确执行范围；已批准总额度只剩一次，不能为补日志自动重开。旧 UIA“否”结果未知且不得重试。首次启动根因、正常可见退出及保存／独立重开闭环仍未验收。

本轮源码与本报告使用本地精确 Git 提交保存，不推送。Git 只回滚源码／文档，不代表可以回滚 Profile、凭据或数据库。
