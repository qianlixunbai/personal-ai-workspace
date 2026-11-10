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

这不是云提供商验收、专属模型进程树验收或桌面视觉验收。下一项是将本次请求身份绑定到实际持久化 guard。
