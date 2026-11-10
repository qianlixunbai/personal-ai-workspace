# 写入者覆盖与外部执行结束边界

2026-11-10；基于本仓库实际构造、写入、关闭和 native handoff 路径核对。此清单不是宿主进程普查，也不把当前受限会话的通过结果称为完整维护验收。**BLOCKED / productionGo=false 保持不变。**

## 本轮已闭合的三个缺口

1. `1388fbd`：缺少原外部执行结束证明时，Runtime、Store、原生桥接及 UI 均不能通过人工 RECOVER 清除未知 guard。[实现与证据](EXTERNAL-EXECUTION-RECOVERY-BOUNDARY.md)。
2. `6f83cd7`：受限数据会话不打开/修复独立问答历史，不修复待处理对话/知识任务，不启动知识索引重建。[实际重开验收](RESTRICTED-DATA-WRITERS-ACCEPTANCE.md)。
3. 浏览器注册表在受限会话中不可用，保留正式与 pending 文件，不认证旧浏览器凭据。[实现与证据](RESTRICTED-BROWSER-REGISTRY-ACCEPTANCE.md)。

这三项直接修改现有产品路径，没有新增仅用于显示历史完成状态的产品层。

## 从实际代码确定的范围

| 写入域 / 主要入口 | 受限会话及已有证明 | 尚不能据此证明 |
| --- | --- | --- |
| Runtime 准入：`WindowsNativeGate` → `NativeProfileRecovery.RuntimeLease`；`MaintenanceExclusion` | 协作版本持有同一 Profile 的 `runtime-admission.lock`；原生排斥与实际句柄传递覆盖该产品 Runtime。普通 lease 保留到 JVM 退出。 | 不证明旧二进制、任意程序或外部 SQL 客户端遵守协议。 |
| Memory：`MemoryStore`、`MemoryBackupService`、Workspace backup | 本轮受限 HTTP 仅开放存储状态及记忆读取/新增；真实 store 连接纳入 `MaintenanceRuntime` 的 BoundDataSource。备份恢复路由关闭。 | 不能把允许记忆写入理解为全数据只读，或证明所有备份目标/外部写入者都被接管。 |
| Conversation：`ConversationStore`、`TaskManager` completion callback | 受限路由关闭；跳过构造期 `reconcilePending`；保留同数据库连接。合成后续 PENDING 状态/时间戳实测不变。 | 未开放普通对话任务和迁移维护并发，未证明任意旧 callback 持有者已结束。 |
| Knowledge 真值与文件：`KnowledgeStore`、`KnowledgeIngestion`、`KnowledgeBackup` | 受限使用 metadata-only PostgreSQL store，不执行文件发布、删除或任务 reconcile；ingestion 不可用。合成 PENDING knowledge job 不变。 | shared advisory lock 不是全程阻断外部 SQL 的证明；普通导入/删除/备份发布工作仍需要自己的任务结束和资源归属。 |
| 派生知识索引：`KnowledgeLexicalIndex` | 受限实例不启动索引 worker 或回调。迁移本身可合法生成目标索引；实测迁移后清空的合成索引在日常重开后仍为空。 | 不宣称迁移过程无索引写入，也不覆盖普通索引 worker 的所有取消/异常场景。 |
| 独立问答历史：`KnowledgeAnswerHistory` | SQLite `data/knowledge-answer-history/history.db` 与主 Workspace/Knowledge 存储独立。受限实例无连接；含 QUEUED 行的全部文件实测逐字节不变。 | 没有迁移此域，也不能把主数据库备份视为包含这份独立历史。 |
| 原生会话凭据/浏览器注册：`LocalClientToken`、`BrowserClients` | 本次原生 token 正常轮换属于允许启动写入；浏览器 registry/pending/lock 不打开或修复。实际原生凭据成功、原有效合成 browser credential 被拒绝。 | token 更新不证明历史浏览器任务结束；浏览器注册表不在业务数据库迁移中。 |
| 模型状态：`ModelStateStore`、`MaintenanceModelState` | 文件身份/快照和实际模型状态写锁交接；guard v2 在实际外发前绑定当前 writer、状态目录、request/reservation 与模型/端点；未知/pending guard 拒绝维护，RECOVER 不再清除。 | writer 会话不是原生维护会话或远端执行 ID；这些文件锁不拥有权重、Ollama 服务、runner、远端请求。 |
| 本地任务和 transport：`TaskManager`、`OllamaProvider`、Web workers | 受限 HTTP 不提交任务，模型 manager 拒绝执行。TaskManager 已等待实际 worker、timer、lease 和并行取消回调；成功关闭先于 Conversation／模型资源销毁，维护 FINISH 核对该活体结果。[本地退出验证](TASK-SHUTDOWN-HANDOFF.md)。 | cancel、shutdownNow、本地 drain 或连接关闭不是外部执行完成。本轮只执行受控工作/Spring 生命周期验证，未重跑完整维护；知识 worker 的 bounded await 和其他 Web worker 不能由此获得全局完成证明。 |
| 外部模型执行：普通 Ollama HTTP、隔离 vision transport | 普通推理/卸载接入单次 socket 写入，禁用自动重试；所有完成/退出回调绑定活体请求能力；合成服务证明取消/超时/关闭及迟到回复不清除未知 guard，并发一个成功不能清掉另一个。隔离 vision 原生检查保留自己的限定范围。 | 普通外部服务不是由本次 native owner 创建/持有的专用执行资源；未实现重启后认领、远端取消终态证明或原生 owner 接管。未实测真实模型，也未验收真实云提供商；源码接入不等于视觉路径完成验收。 |
| Finance/行情：`FinanceModuleConfiguration`、`FinanceInfrastructureConfiguration`、`TrustedMarketDataFile` | 默认配置 Finance 和其 Flyway 都关闭，受限 HTTP 不开放；本轮未启用或访问。 | 独立 Finance 数据库、身份文件及行情配置不因此纳入 Workspace 三个 store 的关闭证明。未来启用前必须单独纳入范围。 |
| 迁移、备份、配置、原生证据与临时资源 | `SqliteSourceLease` 固定本次来源/备份；`MaintenanceTarget` 在关键阶段持有实际表锁及绑定；append-only 会话绑定本次 operation/session/challenge 与实际 native Process 退出。 | 三个 store 的连接清空不等于所有域/外部 SQL 清空。已释放的锁和旧 native-exit 不能替代下一会话的实时准入。 |

## 完整闭环的最小完成判据

后续三个模块的真实调用路径、单次传输、活体请求能力及持久化绑定证据见 [外部请求所有权](EXTERNAL-REQUEST-OWNERSHIP.md)。该验证不提升任何共享服务的归属或远端结束权限，完整维护继续 BLOCKED。

1. **范围先确定。** 对计划开放的每个域，列出全部实际写路径、后台 callback、独立文件/数据库和启动修复行为。未纳入协议的写入者不得默认为不存在。受限模式的禁止初始化必须与路由限制同时成立。
2. **本地写入确实终止。** 停止新准入，保留当前会话资源并结束实际任务/事务；关闭和核对全部本域连接、文件 writer。随后由持有原始进程句柄的 native owner 观察 JVM 退出，核对自己的 Job；错误、强制终止、部分状态均不能生成正常完成凭证。
3. **模型状态与执行分别证明。** `.lock`、selection、validation、guard 的所有权交接只能完成“本地状态”一项。每个外部请求必须在发送前登记不可混用的 Profile/会话/执行 identity 和实际 owner；只接受同一执行的可信终态，或从创建前就由 owner 持有的专用执行资源已实际终止的证明。人工勾选、旧成功记录、请求取消、空 reservation、服务驻留列表/进程数量、文件未变均不满足。
4. **外部证明必须接入产品。** 下一实质工作应从普通 `OllamaProvider` 的请求发送与 `ActiveModelManager.Lease` 的 guard 写入/结束回调接通。先定义可支持的 owner 合同和请求绑定，再以新建、完全自有的合成进程验证；不引入能把任意旧服务“认领成功”的接口。没有合同的 provider、旧 guard 或未知持有者继续阻断。合成验证只能说明该合同路径，不能替代真实提供商验收。
5. **交接不可重放。** 本次活 owner、来源/目标、配置、全部独立域以及 operation/session/nonce 持续绑定；一次 successor intent 先消费前次正常退出，再启动后继。断连/未知结果先只读确认；不能回退旧凭证或猜测完成。
6. **最后才扩大交付范围。** 完成上述证据后再考虑开放更多业务路由、普通维护入口和完整桌面视觉/用户路径验收。现阶段不生成新版发布 ZIP，不将旧 ZIP 声称包含新功能。

本轮未修改系统安全、网络或权限设置；未使用 `user.name` 覆盖、旧 Profile/端口查询或未知进程终止。沙箱本地 Git 暂存因 `.git` 只读未执行，经正式执行审批后完成既有授权范围的提交；没有未解决的自动审批拒绝。六份用户文档、main 和旧包按证据基线保留。
