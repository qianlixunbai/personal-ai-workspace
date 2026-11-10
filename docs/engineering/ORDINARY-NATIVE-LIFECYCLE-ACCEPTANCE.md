# 普通原生启动、正常关闭与维护历史

2026-11-10，承接 `846cded`／产品 `00cfdf1`。本轮只推进 personal-ai-workspace；根目录及祖先目录未发现 AGENTS.md，已读取前轮维护重开交接并核对现场。完成的是**未迁移开发工作区的普通原生生命周期**：真实桌面 owner 启动、应用关闭握手、实际退出观察及维护历史消费。完整维护迁移仍为 **BLOCKED / UNREGISTERED_WRITERS_UNPROVEN，productionGo=false**。

## 交付内容

1. **普通 Runtime 生命周期。** 新增 `NativeRuntimeLifecycle`，接在普通开发工作区的真实 `DevelopmentRuntimeOwner` 入口。原 Native Gate、Profile、运行租约和 `MaintenanceStartupFence` 均保留。父进程先持久化启动意图，再启动自己持有的 Process；子进程产生随机 challenge，双方绑定本次 attempt、Profile、实际 PID。真实 Spring 上下文就绪后返回本次 loopback 动态端口，桌面健康检查及凭据核验通过后才显示可用。此路径不枚举监听端口、不连接已存在的 18765 服务。既有合成恢复激活分支保持原协议。
2. **正常退出和故障退出分开留证。** 用户正常退出或显式重启先保存 CLOSE 意图，再发送私有管道指令。子进程停止接收新的 HTTP 工作、关闭真实 Spring 上下文并回复 CONTEXT_CLOSED；父进程必须核对本次 attempt/challenge、实际进程内核退出、退出码 0、stdout/stderr EOF 及所属 Job 活跃数 0，才能保存 `CONTEXT_CLOSED_NATIVE_EXIT`。关闭最多等待 30 秒，失败仍走原有自有进程清理并保留不完整证据。异常 Dispose、父管道中断、非零退出以及单独的退出码 0 都不能产生正常关闭证明。WPF 的退出调用顺序已接线，避免先取消全局 lifetime 再请求正常关闭造成竞争。
3. **维护历史与界面消费。** 新启动登记使用 version 2；READY 和 CLOSE 记录使用既有 CREATE_NEW／DPAPI／私有句柄机制，绑定 Profile、attempt、PID、challenge、端口。读取完整历史时校验合法文件集合、事件关系和退出结果；新增正常关闭计数，旧 v1 记录兼容但不会升级为新证明。维护说明区区分本次应用正常关闭和 owner 强制收尾，并明确这些记录不证明外部模型或未登记 writer 已停止。桌面 RuntimeClient 只使用本次 owner 提供的端口，停机清除连接；读取凭据期间若代次变化则拒绝发送。健康提示使用本次地址。

这些内容构成同一个可回滚模块的三个产品层级。没有新建第二套迁移执行器，也没有开放迁移执行按钮。

## 聚焦验证

最终证据目录：`.verification/ordinary-native-lifecycle-20261110-r2/`。首次通过的记录保留在同名无 `-r2` 目录；随后补正动态地址提示和启动失败的固定原因码，因协议读取代码变化，对最终候选重新执行同一聚焦流程。使用现有 `Local\Dot_KAG_Workspace_HeavyRun` 和独立 Job 包装，构建及全部服务生命周期持锁；静态工作不持锁。

Java 仅编译 3 个源文件、生成 7 个 class，以前轮交付 JAR 为底更新；5 条既有 ExtendedOpenOption 警告。原生构建 0 警告、0 错误。最终验收候选 Runtime SHA-256 为 `8CC56C4C2667E46779A5FD5CB70EEF4A576AD62C7DE8B9DB7E3DF9137D43618C`，Desktop 为 `CBCE3A04FA1AD57A8ACDB451F9A286095C3952F6D8200C9069664048E36E8FB5`。

主机同一非管理员账户新建合成 Profile `b99a4c0ce35c4fc89c010484e01711c7`，三个阶段均由独立的原生父进程运行真实 owner、Java 产品入口、Spring 和 SQLite 数据存储。没有模型执行、模型下载、收费 API 或 PostgreSQL 集群。

| 阶段 | 实际结果 |
| --- | --- |
| first | Runtime PID 36340。通过真实客户端创建记忆 `2e02ce63-85a7-4926-8651-8ac98519943c`，正常关闭。历史 1 次启动、1 次本次应用关闭、0 次强制收尾。 |
| reopen | 新原生父进程启动 Runtime PID 11412，真实客户端读回该记忆，正常关闭。历史 2 次启动、2 次本次应用关闭，`EXIT_OBSERVATIONS_COMPLETE`。 |
| interrupt | 新原生父进程启动 Runtime PID 45072，仍能读回该记忆；直接 Dispose 实际 owner 触发所属 Job 清理。历史 3 次启动、仍只有 2 次正常关闭、1 次强制收尾；`RUNTIME_HISTORY_UNRESOLVED / OWNER_CLEANUP_NOT_SAFE_STOP`。没有沿用前次正常记录。 |

每次实际退出后都独占打开模型 `.lock`，证明本次 writer 句柄已释放，不写入该文件。维护审阅实际读取受保护历史；没有按保存的 PID 查询或接管进程。`first-result.json`、`reopen-result.json`、`interrupt-result.json` 为分阶段回执。

`fixture-cleanup.json` 确认临时 token 删除；合成数据和历史留作证据。最终 `run-cleanup.json`：guardian 36496 于 `2026-11-10T15:25:39.865765Z` 记录 Job active 0、Job 关闭、共享锁释放，failure=null。首轮也已清理 token、释放 Job 和锁。没有待审批或未知运行结果。首次构建的工具 session id 未在调用输出保留，但随后只读核对 `build-worker.json`／`build-cleanup.json` 确认退出 0、Job 0，未重放未知操作。

没有进行 WPF 视觉／真实用户点击验收、同一 owner 的显式 RestartAsync 流程、配置失败矩阵、关闭超时或所有消息损失组合；也没有运行真实模型或重新执行前轮维护会话矩阵。不能把本次流程说成完整迁移验收。

## 完整闭环的剩余条件

- **迁移后的普通启动授权尚未接通。** 本轮解决普通源工作区生命周期；有已发布维护配置的 Profile 仍被 `MaintenanceStartupFence` 阻断。不能把维护重开或这次正常退出记录直接用作解除该屏障的依据。
- **外部模型执行与全部 writer 的范围尚未证明。** Spring context 关闭、直属 JVM 和 Job 退出只覆盖本次本地应用。需要把已登记的模型专属资源 owner、退出结果和未知执行状态接入实际准入；无权用关闭本地状态文件代替远端完成，也没有授权运行真实模型。
- **当次来源／目标／范围的最终确认与执行入口尚未接通。** 必须绑定当前来源、目标数据库和受限角色的真实身份及备份，确认后重新核验；发生变化或未知结果保留阻断。审阅按钮继续禁用。

下一步应围绕迁移后普通启动的明确能力及失效规则接线，然后接上述准入和用户确认；无需继续叠加与该路径无关的验收层。

main、原六份未跟踪用户文档、旧交付包均由本轮 baseline 单独留证。未改真实数据、凭据、默认配置、其他仓库或系统安全／网络／权限设置；未重试已拒绝的端口查询、旧 Profile 或进程信息读取。代码完成并通过必要验证后本地提交，不推送。当前上下文没有实际路由元数据，无法把会话请求的模型／强度／快速模式当作已核实结果。
