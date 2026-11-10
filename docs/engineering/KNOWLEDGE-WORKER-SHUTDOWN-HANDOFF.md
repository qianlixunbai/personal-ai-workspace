# 知识导入与索引退出

2026-11-10，接续 `8d1bce8`。整体仍 **BLOCKED / productionGo=false**。

## 选择依据

现有 `OllamaProvider` 只连接配置的 loopback 服务；Desktop 的 `RuntimeProcessJob` 拥有 JVM，没有拥有 Ollama。`RecoveryOwnedModelExecution` 仅在 DEBUG 验收配置下编译，而且只启动合成程序。要支持新建的独占模型服务，仍需可信服务与 runner 工件、独立端点和配置、从启动前持有进程树，以及 Profile/逐请求绑定。当前没有这条完整产品路径；不能把合成组件直接用于旧共享服务，不能给旧未知 guard 补结束证明。本轮不启动、接管或停止任何现有模型服务，也不重复研究已核实的提供商限制。

选择当前已经存在的写入缺口：知识文件上传在调用线程中写 staging，原关闭只等待解析执行器；索引关闭忽略等待超时。原生普通退出还只检查 Spring context 关闭，框架可能吞下 Bean 的销毁失败。这些会导致本地工作尚未退出时，资源销毁继续或正常退出回执过早发出。

完成判据：导入覆盖上传、排队解析、失败记录及候选清理；索引覆盖重建及正在使用 Store/独立索引连接的调用；原生普通退出和维护 FINISH 对同一批实际对象显式等待并检查失败。超时、中断和清理失败不能被后续 close 改写为成功。受限会话仍不启动导入/索引，不开放业务写入；这些局部结果不等于全部写入者和完整维护验收。

## 模块一：导入

`KnowledgeIngestion` 在第一次 Store 访问前登记上传调用，关闭与队列提交使用同一准入锁。关闭拒绝新调用，已进入的上传返回后执行取消和清理；排队任务执行取消清理，不悄悄丢失已登记任务。正常 close 等待执行器及全部上传调用退出，才报告 `shutdownComplete()`。解析完成、失败写入和候选删除也处在等待范围内。

5 秒是本地 drain 等待预算，不能强制终止阻塞的输入流或第三方文件/数据库调用。超时后保留失败，尝试中断执行器；未能运行的队列项可保留 pending，由现有正常启动恢复协议处理，不能据此宣称正常关闭。既已进入的业务发布可能完成，关闭必须等待它；没有把设置 closed 当作所有写入瞬间结束。清理失败永久阻止本对象报告成功。

必要验证：`.verification/knowledge-ingestion-shutdown-20261110/`。正常非管理员宿主、原共享锁 `Local\Dot_KAG_Workspace_HeavyRun`、自有 Job、450 秒外层期限，Java 256 MiB／2 CPU。沿用可信准备工具创建全新合成 Profile，实际 Native Gate、私有 KnowledgeStore、SQLite 和 staging 文件，未读取用户数据/真实凭据或运行模型。

两个新测试通过：受控上传未结束时 close 不返回，上传返回后取消并删除临时文件；Store 锁阻塞的实际排队任务全部取消清理；真实 5 秒超时及关闭线程中断后，即使上传后来退出，重复 close 仍失败。仅编译一个修改产品源文件及新测试，不重跑旧场景。`build-result.json`、`result.json` 为 passed；build/run cleanup 均为 Job 0、锁释放、failure=null；临时 token 已删除。

## 模块二：派生索引

`KnowledgeLexicalIndex` 关闭同时停止重建准入和 corpus callback，等待实际重建执行器以及已经进入的搜索/状态/重建调用。搜索使用独立连接且最后还会读取 Store，因此仅等待后台 executor 不够。关闭后状态读取直接返回 UNAVAILABLE，不再访问已关闭 Store；search/rebuild 被拒绝。受限构造仍不创建索引目录、不注册 callback 或启动 worker。

超时、中断和候选文件清理错误会保留失败；后来线程退出或再次 close 不能提供成功证明。PostgreSQL 重建在提交前检查关闭/中断；本轮没有执行 PostgreSQL，不能将 SQLite 结果替代其事务验收。

必要验证 `.verification/knowledge-index-shutdown-20261110/`：沿用相同资源包装，新建另一合成 Profile，实际 Native Gate、KnowledgeStore、SQLite 索引构建与搜索。两个新测试通过：Store 锁延迟重建和已进入搜索时关闭确实等待，结束后 staging 无候选、Store 已关闭仍能读 UNAVAILABLE；真实 5 秒超时/中断不能被迟到搜索退出改判；受限构造没有索引目录。只编译修改索引类及新测试，不重跑导入和旧测试集。build/run cleanup 为 Job 0、锁释放、failure=null；临时 token 已删除。

## 模块三：普通退出和维护接线

`RuntimeWorkShutdown.closeContext()` 先捕获本次实际 TaskManager、KnowledgeIngestion 和 KnowledgeLexicalIndex，按顺序显式关闭，并在 context 销毁前后核对各自的活体 drain 结果。普通 `NativeRuntimeLifecycle` 在发出 CONTEXT_CLOSED 之前调用它；维护 `MaintenanceRuntime.finish()` 在模型状态锁/连接结束核对和 VERIFIED_STOPPED 之前调用同一入口。没有重建历史能力或接受磁盘“成功”文件。

`.verification/runtime-work-shutdown-20261110/`：编译三项新增/修改产品文件及前两项最新依赖，无编译警告。一个新集成测试通过：真实 TaskManager、KnowledgeIngestion、私有 KnowledgeStore 与 Spring context；索引使用实际受限构造。上传仍占用 staging 时，协调器保持 context/Store 存活；释放后等待取消和清理，再正常销毁。关闭线程中断时，协调器失败且 context/Store 仍存活；后来上传结束也不能改判，再调用仍拒绝。测试最后清理 context 时出现一条预期的 Spring `Invocation of close method failed` 警告，复现框架会记录而继续的行为，并非测试失败。

build/run cleanup 均为 Job 0、锁释放、failure=null，临时 token 已删除。编译时包含两个真实入口；测试只执行共享产品协调器，没有启动完整原生管道、WPF/WebView、PostgreSQL 维护会话或真实服务。没有重跑前两组已通过场景和旧测试集。

## 交接边界

本轮共五个聚焦测试，均使用合成资料。普通 HTTP 已在两个原生入口调用协调器前停止新准入；这不证明所有已进入的 HTTP、Web worker、备份发布、Finance 或外部 SQL 写入都已纳入协议。普通 Spring shutdown hook 的异常清理也不能单独提供正常退出证据。本轮没有修改原生 owner 的期限或强制清理行为。同步第三方调用仍可能卡住，由既有外层 owner 期限处理，不能给出正常 drain。

已知提供商仍无法给旧未知请求补可验证终态；旧 guard 与受限路由保持不变。应用自有模型服务尚未实现；完整维护与真实模型、桌面视觉仍待验收，`productionGo=false`。下一步应选择尚未覆盖且计划开放的实际写入域或实现专用模型服务的完整合同，不能把此局部退出闭环扩展为所有写入者已结束。

`protected-inputs.json` 已按上一会话基线复核 main、六份用户文档和旧 ZIP/JAR 均未变。仅完成且验证通过的模块提交到当前本地分支，不推送、不重新打包；旧包不包含本轮改动。最终回执位于 `.verification/runtime-work-shutdown-20261110/final-handoff.json`。
