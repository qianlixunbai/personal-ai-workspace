# 本地任务退出与维护交接

2026-11-10，接续 `305a01b`。整体仍为 **BLOCKED / productionGo=false**。本轮不修改六份用户文档、main、旧包或真实 Profile，不调用真实模型。

## 先确认的提供商边界

仓库当前唯一模型实现为 `OllamaProvider`，限定本机 loopback、已核验的本地模型，没有云模型自动回退。[官方 chat 文档](https://docs.ollama.com/api/chat)定义同一响应的 `done` 与 `done_reason`；[v0.40.1 routes.go](https://github.com/ollama/ollama/blob/v0.40.1/server/routes.go)中 chat 使用请求 context，注册的 `/api/ps` 是模型驻留查询。所核对的路由没有按原推理请求 ID 查询完成或确认取消的接口。此结论限定于当前实现与该版本源码，不扩展为所有未来提供商的结论。

取消 context 或断开 socket 不提供客户端可在重启后验证的原请求终态。发送 `keep_alive:0` 是另一条模型卸载请求，也不能补证先前未知请求。当前不支持远端查询、旧请求认领或人工解锁；保留未知 guard。真实模型调用也不能凭空增加这个协议能力，因此本轮没有必要请求真实模型执行授权。

## 本轮选择的真实阻塞与完成判据

当前 `TaskManager.close()` 发出取消及 `shutdownNow()` 后立即返回。仍运行的工作、结果映射、模型租约退出可能与后续存储关闭重叠；任务已标记 CANCELLED 不能证明这些本地工作已结束。Spring 仅显式保留独立知识问答历史依赖，尚未声明异步工作所用的其他资源关闭顺序。

本轮只闭合这条本地生命周期：

1. TaskManager 关闭后拒绝新任务；排队任务不执行；等待已运行工作、租约关闭以及并行取消回调实际退出。等待超时、线程中断或清理异常不返回正常关闭，也不因稍后退出自动改写为成功。
2. 声明异步 Conversation 完成回调和模型租约实际使用的资源依赖；Conversation 已依赖 Memory，独立知识问答历史已通过构造器声明依赖。成功关闭任务应先于这些资源销毁。Knowledge 问答的结果映射只使用已捕获的不可变证据，不新增无关 Store 依赖；独立 ingestion/index 的原有依赖和受限会话行为保持不变。
3. 维护 FINISH 在关闭前取得本次 TaskManager 实例；只有该实例正常完成本地 drain，才可继续核对连接、模型状态文件和生成 VERIFIED_STOPPED。Spring 的销毁异常可能被框架记录后继续，因此不能只信 `context.close()` 返回。

必要验证采用少量受控本地工作和 Spring 生命周期检查：延迟退出/延迟 lease 清理时不能提早宣称完成，排队工作不运行，取消结果不被迟到成功覆盖，异常/中断不能生成正常 drain。无真实 provider、用户数据或桌面操作。等待只涉及本地资源，不证明外部 Ollama/runner 已结束。

## 验证与交付

已实现上述本地生命周期。`TaskManager.close()` 先停止准入和执行器，发布取消结果，再等待 worker、timer 及监视器外正在执行的取消/lease 回调；`shutdownComplete()` 只反映当前对象。5 秒是发出取消及同步回调返回后的 drain 等待预算，不是任意持久化/第三方回调的硬中断期限；同步回调卡住时不会生成完成结果，仍需原生 owner 的既有期限/强制退出路径。发生超时、中断或清理异常后，本对象永久不能提供成功 drain。再次 close 不会清除此失败。

只有成功 drain 才清空内存任务集合。取消结果已提交后，迟到工作输出不能覆写。transport 取消及 lease 回调移至 TaskManager 监视器外，防止等待工作线程时互相持锁。`DependsOn` 为异步任务补齐 Conversation 与 ActiveModelManager 的资源存活顺序；已有知识问答历史构造依赖继续保留。

`MaintenanceRuntime.finish()` 在销毁 context 前显式关闭并核对本次 TaskManager，销毁后再次核对；失败不会进入 `VERIFIED_STOPPED`。这没有新增普通维护许可，也不改变外部模型 guard。普通 Spring 销毁在 close 异常后仍可能继续销毁其他 Bean；因此未完成 drain 不能作为优雅关闭或停写证明。本轮没有改变 Desktop 原生强制清理行为。

证据：`.verification/task-shutdown-20261110/`。复用原 `Run.ps1`、`Local\Dot_KAG_Workspace_HeavyRun`、自有 Job、450 秒外层期限；仅编译两个修改产品文件及一个新测试类，javac 192 MiB、Java 256 MiB／2 CPU。构建无错误或警告。`TaskShutdownTest` 三项通过：

- 实际 TaskManager 执行器和 Spring 容器：运行工作及 lease 分别受 latch 阻塞时，close 不能返回，依赖资源不得关闭；排队工作不执行；两个取消结果各发布一次；迟到结果不能覆写；释放后实际资源关闭且 drain=true。依赖资源是合成生命周期对象，不冒充真实数据库或模型。
- 本地工作已退出，但另一个调用线程仍处于取消 hook 时，close 继续等待；该 hook 退出后才成功。
- 实际 5 秒期限、关闭线程中断、合成取消 hook 异常均失败；工作稍后退出及再次 close 也不能把状态变成成功。中断标志保留。

`build-result.json`、`result.json` 为 passed；`build-cleanup.json`、`run-cleanup.json` 均为 Job active 0、Job 关闭、共享锁释放、failure=null。没有新 Profile、token、服务、真实 provider 或数据库；没有重跑旧模型请求场景。`MaintenanceRuntime` 接线已编译与源码核对，未重新执行完整原生维护 FINISH／PostgreSQL 场景或桌面视觉流程。

读取历史验证脚本列表时，一次过宽目录枚举遇到既有私有目录的操作系统拒绝访问；未读取其内容、未重试、未提权访问，随后只读取交接明确指定的脚本。它不是本轮实现阻塞。源码构建和测试无自动审批拒绝或未知结果。

## 后续边界

本轮交付一个完整的本地退出模块，不用额外小项凑数量。真实外部执行所有权/终态仍缺失；当前 Ollama 协议不支持给旧未知请求补证。若继续做可恢复执行，需要明确选择并实现从创建前持有的专用执行资源（包含真实 runner/权重身份、Profile/请求绑定和原生退出交接），或更换到原生支持可认证请求终态的提供商；只发起一次真实调用无法补齐这些设计。

维护还需 ingestion/index、独立业务域与全部写入者的各自关闭证据；本轮未扩大它们的准入。真实服务调用、完整维护、默认桌面路径及视觉验收均未完成。main、六份用户文档、旧 ZIP/JAR 保持原基线，不推送、不重新打包，旧包不含本轮改动。
