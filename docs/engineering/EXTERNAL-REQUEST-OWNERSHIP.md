# 外部请求所有权：真实调用路径与完成边界

2026-11-10。整体仍为 **BLOCKED / productionGo=false**；不扩大普通维护或桌面交付范围。

## 生产路径与最小合同

| 阶段 | 实际入口 | 必须保留的边界 |
| --- | --- | --- |
| 准入与任务 | `TextTaskSubmission.submitReserved/prepareConversation`、视觉服务 → `ActiveModelManager.reserve/reserveVision` → `TaskManager.submit` | 先提交本域 pending 记录，再排队；reservation 绑定选定模型与 revision，不等于外部服务所有权。 |
| 管理请求 | `ActiveModelManager.ensureActive/probe/selectionOperation/validateVision`、`OllamaProvider.release` | 验证探针、切换验证、视觉验证与卸载都能实际执行模型；同样需要逐次请求协议。 |
| 外发 | `OllamaProvider.executeChatInSession/releaseInSession` → `exchange` → socket write | 每次推理/卸载仅一条 socket；禁用自动重试、重定向、认证重发；先持久化 guard，再委托实际写入。普通 loopback 服务本身仍不属于 Runtime。 |
| 重试 | 当前任务工作只调用一次 provider；失败后新提交重新准入 | 网络不确定后不能重试。未来增加应用重试，必须创建新的请求身份且前一次已可信结束；不得复用旧完成通知。 |
| 取消、超时、关闭 | `TaskManager.cancel/timeout/close` → `Cancellation` → socket abort；provider close 关闭其活动 socket | 本地任务可先成为终态，服务端仍可继续执行。只有取消前无实际写入，或同次请求的可信协议终态，才可能正常清除 guard。 |
| 回复与落盘 | provider 验证协议终态 → 内容/预算检查 → 工作结果映射 → `TaskManager.finish` completion callback → lease close | 外部协议结束、用户结果有效、业务落盘是三个不同事实。取消结果会阻止迟到回复覆写；成功落盘异常可能转 FAILED。guard 清空不能证明独立历史库或全部 writer 已关闭。 |
| 进程终止与维护 | `WindowsNativeGate` Runtime lease、`MaintenanceModelState`、原生 owner/Job | JVM 或自有 Job 退出只证明其拥有的本地进程结束；不能证明共享 Ollama、runner 或远端请求结束。未知/旧 guard 仍阻断维护，人工 RECOVER 不可用。 |

完整最小闭环：发送前绑定活体 owner、Profile/state writer、reservation、不可重用的 request identity、endpoint/operation/model；真实 transport 只接受该身份；完成回调只结束同次执行；崩溃/不确定时保留持久化阻断；维护还需每个独立 writer 的关闭与原生交接。没有远端终态或从创建前持有专属进程树的结束证明，就不能恢复未知执行。

## 已完成：单次传输与本地退出

普通推理和卸载现在直接复用现有 Apache socket-output 边界；metadata 请求保持原路径。每次 inference/unload 创建一条新 IPv4 socket，关闭自动重试、重定向和认证；guard 在实际写入前建立。provider 关闭会中止自身活动 transport，且禁止关闭竞态继续写入。没有认领任意既有服务，也没有新增接受 PID 或人工结束证明的接口。

依据：[Apache HttpClient 的禁用重试合同](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.html)、[Ollama chat 协议](https://docs.ollama.com/api/chat)。协议中的 `done` 只能按该次响应语义使用；官方文档不能替本地 Runtime 证明某共享服务的进程归属。

必要验证：`ExternalRequestLifecycleTest.singleSendAndUncertainLocalExit`，普通非管理员宿主、新建合成 Profile、真实 Native Gate/私有 ModelStateStore、真实产品 transport 对接模型为空的 loopback HTTP 服务。

- 四种新场景：服务收完请求后断连、客户端取消、请求超时、provider 关闭。每个服务端只收到一次推理；bootstrap 的正常完成先通过同一 transport。
- 取消/超时/关闭返回后，服务 handler 仍未结束；guard 保留。随后释放 handler、尝试发送迟到正常回复，guard 字节不变。重开实际 store 仍 unresolved，不能清除。
- `.verification/external-request-transport-20261110/result.json` 为 passed；`token-cleanup.json` 确认临时 token 已删除；`build-cleanup.json`、`run-cleanup.json` 为 Job active 0、共享锁释放、failure=null。
- 复用原 Job 与 `Local\Dot_KAG_Workspace_HeavyRun` 包装，450 秒外层期限，Java 256 MiB/2 CPU。没有调用真实模型、读取真实凭据、查询旧端口、改变系统策略或重打包。

## 已完成：同次请求的完成能力

`ActiveModelManager.Reservation.bindRequest` 为同一个实际 provider、reservation 和不可变 HTTP request 创建一次性内存对象。普通推理、管理探针、视觉推理和卸载的产品入口都捕获这个对象，并将它传至 socket guard、实际 write、协议完成和 finally 退出。旧的无参数发送/完成/退出入口已移除。

未写入、重复完成、已经退出、另一 reservation、前一次请求的能力均不能完成当前请求；先前已完成且退出后才能创建下一次请求。重建 manager/进程无法从 UUID 或磁盘文件重新构造此能力。它证明本地 transport 的关联，不证明共享 Ollama/远端服务归属。

针对性验证 `ExternalRequestLifecycleTest.requestCompletionCannotCrossAttempts`：经实际 Native Gate 新建合成 Profile；真实 provider 在同一 lease 连续完成两次合成 HTTP 请求；随后用受控 write delegation 检查前述拒绝分支，第二次未完成的尝试退出后 guard 保留，实际 store 重开仍 unresolved。受控回调检查没有被记作真实远端执行。既有 socket-boundary 测试按新签名同步并编译，没有重跑旧场景或完整测试集。

证据 `.verification/external-request-binding-20261110/{result.json,token-cleanup.json,build-cleanup.json,run-cleanup.json}`：passed、临时 token 清理、Job 0、锁释放、failure=null。没有外部模型/付费 API、系统设置改动或重新打包。

## 已完成：发送前的持久化绑定

实际 `prepareCheckedSend` 现在调用 `ModelStateStore.armGuard(RequestBinding)`。guard v2 包含本次持锁 writer 的随机 `ownerSession`、实际状态目录路径与文件身份的 SHA-256 `stateBinding`，以及 request/reservation UUID、loopback endpoint、chat/unload、模型、runner、digest、selection revision。writer 会话不冒充原生维护 operation/session，也不冒充远端执行 ID。提示词、回复和凭据不写入 guard。

并发/连续尝试先将新的绑定原子发布、强制写入并回读，再允许 socket 写入；保留先前绑定直至所有相关 lease 安全退出。当前 writer 每次操作都核对固定文件身份和精确字节，pending、替换、写入失败、超预算均 fail-closed。单个 guard 上限 128 次尝试/64 KiB；达到上限后不发送新请求，也不裁剪历史来腾空间。

重开只解析，不认领；旧 v1、v2、损坏或 pending guard 都继续 unresolved。目录哈希、UUID、旧回执或人工确认本身不能重建内存能力，也不能让维护准入。原来的 `MaintenanceModelState` 只接受其已知正常文件集合，仍拒绝包含 guard 的目录。

最终针对性验证 `ExternalRequestLifecycleTest.durableRequestsAreBoundBeforeEgress`：两个真实产品 HTTP 请求同时在合成服务端等待；服务读取到的 guard 已有两个不同 request/reservation、同一 writer/状态目录绑定与正确模型/端点。一个请求成功并释放 lease 后，另一个未结束请求的 guard 保留；第二个断连后 guard 不变，重开拒绝清除及维护所有权。没有强杀 JVM 或假称此测试证明远端进程退出。

证据 `.verification/external-request-durable-20261110/{result.json,token-cleanup.json,build-cleanup.json,run-cleanup.json}` 全部成功，Job active 0、锁释放、临时 token 删除、failure=null。只编译三个改动产品源文件及相关测试支持；5 条既有 `ExtendedOpenOption` 警告，无编译错误。未重跑前两组已通过场景。

## 接续边界

- 受支持：当前可信进程内，已准入 Ollama 协议的单次 HTTP transport 与当前 writer/请求绑定；同次有效协议终态可结束该次请求。合成验收覆盖这些实际调用路径。
- 不受支持：认领共享/任意既有服务，远端取消或终态查询，重启后恢复旧能力，拿本地取消/JVM 退出/服务列表当远端结束证据。未知执行仍永久阻断，直至未来实现针对原执行的可信结束合同；本轮没有提供恢复后门。
- 接续已提供 [本地阻断请求检查及设置页入口](MODEL-EXECUTION-INSPECTION.md)：实际发送身份与本进程完成观察可逐项展示，历史记录仍按未知，读取不依赖 provider、不修改 guard。原生 Bridge/React 分层验证通过；不代表真实服务恢复或完整桌面验收。
- 下一核心缺口：专属执行资源在创建前由原生 owner 持有，或 provider 原生的请求绑定、可认证终态合同；必须与当前原生维护会话/关闭回执接通并防重放。当前本地 writer 会话不能代替这些证明。可继续用新建自有进程做合成开发；本轮未启动真实模型，没有需要额外批准的待执行真实请求。
- 维护还需全部独立 writer 的关闭、外部模型边界和完整桌面流程/视觉验收。接续模块已补齐 [TaskManager 本地 drain 与维护检查](TASK-SHUTDOWN-HANDOFF.md)，以受控工作验证真实执行器及 Spring 关闭顺序；不证明远端执行结束，也不替回复持久化异常、独立数据域或完整维护授予许可。
- 无待审批、拒绝或结果不明动作。main、六份用户文档与旧维护确认 ZIP/JAR 保持原基线；只保留本地回滚提交，不推送、不重新打包。旧包不包含本轮模块。
