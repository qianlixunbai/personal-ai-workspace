# 维护模型状态移交、停机证据复核与迁移审阅

2026-11-10，承接 `98f0c01016c2b5d502a541d8d8de9c9410c2c79a`／产品 `1c37625595aff3dddd3544be850239da329c72e1`。本轮完成三个有限层级：真实模型状态存储的所有权移交；安全停止后的当前证据复核；原生只读迁移审阅入口。**没有完成普通重启授权、外部模型执行所有权或可执行的个人迁移确认链。完整维护仍为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN。**

前置：[Runtime 会话交接](MAINTENANCE-RUNTIME-HANDOFF-ACCEPTANCE.md)、[发布所有权](MAINTENANCE-PUBLICATION-OWNERSHIP-ACCEPTANCE.md)、[配置与目标绑定](MAINTENANCE-BINDING-CONFIGURATION-ACCEPTANCE.md)。未改变 `MaintenanceStartupFence`、未注册来源准入或通用维护阻断；未删除历史证据，也未把进程退出／完成文件当成普通启动许可。

## 实际资源与生命周期

`ModelStateStore` 拥有本 Profile 的 `.lock`、选择和重新验证状态；`ActiveModelManager` 拥有本 JVM 的模型选择、预约、切换及执行 guard 生命周期。`OllamaProvider` 持有 HTTP 客户端／定时器，关闭这些客户端不能证明外部 Ollama 服务、runner、已发送任务或模型驻留结束。`RecoveryOwnedModelExecution` 的已有证明只适用于它自己新建并持有的专属进程树，不能根据旧 PID 或模型文件接管外部进程。本轮不使用它伪造外部模型证明。

上一轮原生父进程与维护 child 全程保持 model `.lock` 的读句柄并拒绝写共享，实际 `ModelStateStore` 无法创建 writer，Spring 会降级为 `store=null`。本轮消除这个具体冲突：

1. source child 在任何 `SqliteMaintenanceOwner.begin`／备份／导入前核对 `MaintenanceModelState`。只接受 `.lock`、`active-model.json`、`validation-required.json`；guard、pending、未知条目均保留并拒绝。模型目录和已有状态文件被 pin，摘要进入 DPAPI handoff intent。
2. 新 Runtime 在旧 owner 退出前接住状态文件 pins 并核对摘要。原生确认真实 source Process 退出后，关闭自己的模型 reader；新 Runtime 此时仍持有 reader。原 Runtime admission 排他写句柄全程保持。
3. 新 Runtime 在自己的明确初始化边界关闭最后的模型 reader，立即使用原 `ModelStateStore` 构造函数取得真实 writer／文件锁；不改 sharing mode、不替换锁文件、不重试竞争。已有文件 pins 持续保留，目录／锁身份复核；若其他 writer 抢先，启动失败并保留 fence。这里是全局 Runtime 排他保护下的 reader→writer 转换，**不声称模型锁本身没有短暂转换间隔，也不声称阻止任意非协作外部进程**。
4. 真正的 Spring `ModelConfiguration` 只能消费该实际 Store 一次。实际 `ActiveModelManager` 在开始服务和安全结束前核对同一实例、无预约／切换／发送／未解决状态，且没有 active/ready 模型。`context.close()` 后核对 writer channel 和 FileLock 均已关闭，再恢复维护 reader 并写终态。

维护会话不运行模型。模型执行 gate 保持关闭，HTTP 只开放健康检查、存储状态与限定记忆数据路由；其他 API 返回 503。原普通 Runtime 的 API 行为不受此条件影响。已有选择和 validation 文件只读保留；没有选择切换、重新验证清理、权重下载、模型 preload／推理、收费 API 或持久模型设置变更。合成选择 `fixture-no-execution:latest` 只是测试文件，不代表真实模型安装或能力验收。

新 intent 必须包含绑定的 modelState 摘要。旧会话记录缺少此字段会安全拒绝，不自动升级、补写或清除；旧包保留可回滚，但不能以回滚绕过维护记录。

## 停止后复核与普通重启的准确差距

独立 inspector 仍由原生 owner 新建并持有真实 Process／管道／Job。它在原 Gate 与 live exclusion 下重新核对完整 DPAPI 阶段链、唯一 operation 注册表、受保护配置 intent／publication attempt／实际配置、来源 SQLite、知识原文、备份、导入阶段链和 owner.lock。目标连接取得当前绑定和表锁，保留 Runtime 后续新增数据，不能使用导入时内容摘要恢复或清理当前数据。模型状态重新 pin 并比较会话摘要。

原生只接收本次精确 operation、challenge、配置、目标、模型摘要及实际 inspector PID，观察此进程退出 0 后才能接受复核。结果始终 `ordinaryStartupAuthorized=false`，并返回四个具体缺口：`NATIVE_RESTART_LIFECYCLE_REQUIRED`、`EXTERNAL_MODEL_EXECUTION_UNPROVEN`、`UNREGISTERED_WRITERS_UNPROVEN`、`USER_CONFIRMATION_REQUIRED`。这是更强的**当前证据审阅**，不是普通重启授权实现。

下一轮普通重启不能简单读取本轮终态后放行：第一次普通会话启动后，这份旧终态就不能证明该新会话安全退出。需要每次原生启动的一次性意图／消费记录、与实际 child 及配置／目标／模型 owner 的绑定、正常关闭与真实进程退出后的更新，以及中断／未知结果不自动续发的协议。未实现前继续使用原 fence，不应增加“存在 VERIFIED_STOPPED 就允许”的分支。

## 用户入口

原“备份与恢复维护”检查完成后可打开“审阅维护迁移范围与条件”。展示当前 `SourceReadiness`，可查看 SQLite→PostgreSQL 范围或选择保留现有存储，说明 Memory／Conversations／Knowledge、原数据和备份保留、Finance 排除、目标必须重新核对、模型资源边界及普通重启缺口。长内容可滚动；关闭默认安全。

该窗口没有迁移回调、密码输入或能产生执行能力的勾选；执行按钮保持禁用。**真实来源／目标选择、当次最终确认和完整执行接线仍未完成**，不能把此审阅页称为可用迁移向导。已编译进入最终原生候选；本轮未做 WPF 可视／交互验收，不虚报浏览器或默认 EXE 验收。

## 聚焦的真实合成验证

Java 最终候选 SHA-256：`C620B68FB1138266E982AB054120A2FFFD76E15A21ABAF7DCB4D2AA4F915714B`。9 个源文件编译生成 39 个 class，15 条既有 `ExtendedOpenOption` 内部 API 警告；原生构建 0 警告、0 错误。r3 复用 r2 相同 Java 字节，只重新编译最终原生审阅展示及中断探针。初版 build/native 均成功，因补上导入前模型 preflight 与非空选择 fixture 而产生 r2；没有将初版编译当作运行验收。

| 场景 | 新合成 Profile／operation | 实际结果 |
| --- | --- | --- |
| 正常结束，`.verification/maintenance-model-review-20261110-r2/` | `77a4fd418dd543f2ad88c4d33e96ade8`／`7442dfc3858640ddb781f4b36ecb6900` | 合成 pending guard 在来源维护目录或配置生成前拒绝；fixture 只删除自己刚创建且字节匹配的标记。真实 Runtime PID 36072、HTTP 49266，实际三后端 27528／24408／35400。非空模型选择完整保留；模型 HTTP 路由 503；正常停止、当前来源／备份／配置／目标／模型独立复核通过。新增记忆 `46211d09-3447-42fb-9995-3c398475948a` 在退出后 SQL 读回，普通新 JVM 仍被 fence 拒绝。 |
| 模型 writer 中断，`.verification/maintenance-model-review-20261110-r3/` | `33733688e67b45f893c0bc85a95a344c`／`8d6e620561d8416f81af3a5fc3626823` | 同一 Java 候选，Runtime PID 36768、HTTP 50135。真实 owner 关闭本次管道／Job；只保留 intent、PINNED、INITIALIZING、VERIFIED，没有 STOPPING／VERIFIED_STOPPED。新独占 ReadWrite 句柄实际打开同一 `.lock`，证明 writer 释放，未写文件。模型文件与来源字节不变；新增记忆 `24ee0c99-32d4-4005-88fa-c04e42d27dcb` 保留，普通启动拒绝。 |

主要回执为两目录的 `handoff-result.json`、`handoff-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json`。正常 run guardian 26672 于 `2026-11-10T14:23:43.3862519Z`，中断 guardian 36572 于 `2026-11-10T14:26:26.4273607Z` 均记录 Job active 0、Job 关闭、共享锁释放。两个独立 PostgreSQL 正常停止；初始化密码、暂存／实际合成 DPAPI 配置和 token 已清理；外层 forcedOwnedExit=0。中断内部仍是已拥有 Job 的真实中断，不能声称其 Runtime 自然退出。

没有重跑完整事务矩阵、记忆保存重开或旧 UI 矩阵。未覆盖已有 validation 文件的非空案例、未知 writer 抢占时序、所有消息丢失组合、真实断电、模型 runner／权重／推理、外部服务停止、普通重启和后续重启崩溃恢复。新证据不扩展前轮已验收范围。下一阶段若要验证真实模型执行，需要明确：独立授权的 runner 可执行文件及权重来源／许可与哈希、专用实例与可控生命周期、资源预算、输入及外部费用范围；现有模型状态证明不替代这些条件。

## 边界与本地交付

仅本仓库。main、六份用户未跟踪文档、旧包、真实数据／默认 Profile／数据库／持久凭据保持不变；无推送。没有触碰 Get-NetTCPConnection/18765、Docker、旧 Crashpad／私有目录／进程元数据、前轮被拒合成 Profile、原生诊断或 KAG 执行策略。未修改 EFS、ACL、沙箱、注册表、网络／系统策略或账户组；沿用产品原生创建新合成 Profile 的既有受保护文件机制。

本轮没有新的权限拒绝、待审批或未知执行结果。最初基线文档哈希读取因 Git 非 ASCII 文件名输出编码导致路径解析失败，随后仅使用公开仓库六份文档的实际文件路径完成哈希；不是权限拒绝，也未读取任何被拒 Profile。

产品提交及新包 SHA 在交付后补入下节。完整维护迁移与普通重启仍是产品缺口，不是待用户批准某个系统策略更改。
