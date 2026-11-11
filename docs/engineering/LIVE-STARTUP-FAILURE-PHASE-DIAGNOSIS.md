# 首次启动失败的阶段定位

2026-11-11；输入 HEAD `b9a621817423c64c6cead0f81c869b08cba42896`。本轮只读源码、既有工作区回执及原 b204 范围内的必要准确日志／元数据，不启动产品、JVM、浏览器或模型，不构建／测试。启动计数 **1/2**，`productionGo=false`。六份原始文档未改，不推送。

**已收窄到两个失败阶段，仍未取得首次 Runtime 的原始失败码，不能宣称根因已查明。** 当前证据不支持把问题归因为账户切换、缺少 PostgreSQL 配置、UIA 点击或执行通道断线。

## Runtime：已成功登记子进程，未留下完整 READY 回执

沿 `DevelopmentRuntimeOwner.Lifecycle.cs` 对照已解读的同一 attempt `93d926922afe416bbf5cc780e25959f1`，可以确认：

1. `TrustedDevelopmentRuntimeSources.Open()`、`StartInfo()`、Process.Start、Job.Assign 和 RecordStarted 已走过。cleanup-intent 的发布要求内存 observation 已进入 STARTED；该状态仅在 started 记录写入并回读成功后设置。故不是尚未创建 Java、前置参数环境拒绝或 Assign Job 失败。StartInfo 中原 Profile 的正常校验和固定 helper 也必须先完成；这不代表 **Java 自己的** NativeGate 已通过。
2. 本轮只读准确文件元数据：`ready.dpapi`、`ready.pending`、`stop-request.dpapi`、`stop-request.pending`、`exited.dpapi` 均不存在；intent、started、cleanup-intent、cleanup 保留。没有读取 ready／stop-request 的 Challenge。
3. 最窄的有据范围是 **NativeRuntimeSession.OpenAsync／其后 RecordReady 尚未成功形成完整持久回执的阶段**。OpenAsync 包括协议头、Profile、PID、challenge 往返和 READY／端口校验。之后才会绑定端口、进行 HTTP health／认证检查。现存证据没有支持进入后续检查或正常 CLOSE。
4. Java 启动 UTC 14:28:27.4277615、记录退出 UTC 14:28:27.6712429，约 244 ms；回执为 OWNER_CLEANUP／退出 1。这个退出码不是可靠的 Java业务失败码：Desktop 清理路径会关闭 Job，必要时终止仍存活的自有子进程。它也不能证明一定由哪个底层终止调用造成。

NativeRuntimeLifecycle 可以在 Java 原生准入、参数检查、握手或 Spring 初始化失败时，通过私有 stdout 管道发送固定 `STOP`／原因码。C# 将安全原因码映射为 RuntimeStartupDiagnostic 状态，但当前 RunOwnedAsync 仅调用内存 `State(failure.Code)`；UI 随后把多种原因归为“启动或存储校验未通过”。现有 attempt 回执没有失败码或协议阶段字段，也未保存本次私有管道内容。普通 owned-lifecycle 分支不使用另一个 synthetic 分支的 stderr Pump；Java 进入该分支后还会抑制普通 stdout／stderr。因此不能从构建日志、helper stdout、退出码或 UI 泛化提示重建当时的精确原因。

本轮还仅检查已归属 PID 的准确默认 JVM 致命错误日志 `P/hs_err_pid18668.log`，结果不存在，未读内容，也未搜索其他目录。这是 [Java 21 ErrorFile 默认命名](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html) 所规定的工作目录位置；该位置不存在日志不能排除未产生或写到其他位置的错误，更不能据此证明 JVM 启动正常。

## WebView：控制器完成，清理 Profile 时收到未分类的进程事件

只在原 P/logs 一层按源码规定的 `workspace-ui-*.jsonl` 和本次启动时间窗口定位，找到唯一日志：`workspace-ui-861d39f55ebe423e9d587d074b060d29.jsonl`。其 SHA256 为 `5fd4b53d4917e9fab588046eb153b645a20bfee21e08a168ef40e4c4f32579ea`。只读 10 条固定枚举／数字诊断，没有 URL、页面内容或凭据。日志本身没有 Desktop PID，归属依据是原 Profile、唯一创建时间窗口及与已保存 UI 症状一致，不能把它说成含 PID 的强绑定记录。

| UTC | 阶段／事件 |
| --- | --- |
| 14:28:27.4319965 | PROFILE_VALIDATED |
| 14:28:27.4407823 | ENVIRONMENT_CREATED |
| 14:28:27.9285332 | CORE_INITIALIZED |
| 14:28:27.9303903 | CONTROLLER_CREATED |
| 14:28:27.9312736 | PROFILE_CLEAR_START |
| 14:28:27.9324751 | BROWSER_PROCESS_FAILED |
| 14:28:27.9333748 | FALLBACK_COMPLETED |

没有 PROFILE_CLEAR_COMPLETED、NAVIGATE、REACT_BRIDGE_READY 或 HOST_CLOSE_START 记录。日志支持控制器确已创建、清理阶段收到事件，**不证明 AllProfile 清理已完成，也不证明清理 API 本身造成了该事件**。Runtime 已记录的退出时间早于此 WebView 事件；不能用这条较晚事件解释更早的 Java 失败。它们都早于 UIA“否”调用及执行通道断线。

发现明确的源码诊断缺口：`WorkspaceWebViewHost.cs:169` 使用 `core.ProcessFailed += (_, _)`，丢弃事件参数，对所有类型写同一个 BROWSER_PROCESS_FAILED 并进入 fallback。日志 hresult=0 是 Diagnose 的默认值，不是“底层没有错误”。Microsoft [ProcessFailed 文档](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/process-related-events) 明确区分浏览器、主渲染器、GPU、utility 等事件，其中一些可自动恢复；**因此当前中文提示和固定日志名不能证明主浏览器进程真的退出，也无法证明本次属于可恢复事件**。没有依据关闭 GPU、安全沙箱、跳过 AllProfile 清理、换 WebView Profile 或修改系统设置。

按上述官方分类对照源码，当前统一 fallback 还会对可自动恢复的事件中断工作区；这是静态可确认的处理范围问题，但不能据此断言本次收到的就是这类事件。仓库已有测试 trace 源码会记录 ProcessFailedKind、Reason、ExitCode，但它不属于这次运行，不能借用其旧结果。本轮只读该源码，没有执行测试宿主、读取旧受拒目录或 Crashpad。

## 已排除的错误推断

- `database.dpapi` 在启动前不存在，**不是已证实的配置缺失根因**。TrustedDatabaseConfiguration.load 在文件不存在时返回空配置来源；正式 application.yml 默认 backend=sqlite。只有选择 PostgreSQL 且缺可信配置等具体条件才映射 POSTGRESQL_NOT_CONFIGURED。本轮不读取或补写凭据、迁移／重建数据库。
- 没有新身份差异证据，也没有新的 OS／自动审批拒绝。不存在新的 token 或环境调查。
- UIA“否”异常仍是结果未知；没有内层阶段或 HRESULT，不能把它改写成权限拒绝、明确未执行，或用来解释更早的 Runtime 失败。本轮无 UIA 调用。
- 监督会话没有新终态回执，desktop-1.json 仍是启动时记录。没有监督 PID 可供精确查询，未遍历未知进程、试占共享互斥或重试旧会话。监督终态及互斥释放仍未知。

## 下一步可安全实施的最小改动

继续重复扫描现有日志无法恢复已经丢失的协议状态。本轮未修改产品或重新消费启动次数。若继续源码工作，优先以下小范围产品诊断改动，再由父任务决定是否值得使用剩余一次实际启动：

1. 复用产品已有的受限原生诊断格式，为正常 Runtime owner 保留**首个固定失败码与协议阶段**（例如首帧／身份往返／等待 READY／记录 READY）。只输出固定枚举、受控异常类型／数值，不输出原始管道字节、challenge、token、路径或业务数据；不将新文件塞入当前严格定义的 attempt 目录，不变更既有 journal 兼容合同、NativeGate 或关闭逻辑。不增加远程接口、备用运行入口或独立验证框架。
2. 复用现有受限 UI jsonl 格式记录 ProcessFailedKind、Reason、ExitCode，准确描述失败类型；保留首个失败。是否调整 fallback 行为应按官方分类另作最小审查，不能把本次未知事件自动当作 GPU 故障而放行。禁止日志采集 ProcessDescription、URL、frame 信息或原始异常文本。
3. 若后续确需 UIA 操作，在实际动作前区分是否支持 InvokePattern，并把阶段／异常类型／HRESULT 限定记录；已有“否”动作不重发。这个改动本身不创建窗口或操作任何控件。现有 Desktop 已不存在，不能为补日志而重开。

当前 KAG 仍在工作，本轮不运行上述改动的构建或测试。将来只检查实际改动的编译／纯逻辑分支，必要实测必须单独明确候选身份与剩余启动范围；不能拿日志改进代替业务闭环。

## 本轮证据

仍使用 `artifacts/live-exit-run-20261111-422bcdf/`：`startup-phase-metadata.json`、`startup-ui-log-observation.json`、`startup-exact-jvm-log-metadata.json`。此前 Runtime／Desktop 归属和终态证据不改写。仅本报告与状态入口进入本地 Git；没有产品、Profile、原始文档或 KAG 修改。
