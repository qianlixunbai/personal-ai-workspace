# 迁移后从日常桌面入口打开受限数据会话

2026-11-10。承接产品 `fa9a594` 和交付补记 `3a2c041`。第三个实质模块把已有受限重开协议接入 `DevelopmentRuntimeOwner` 的日常启动路径：原生确认后可打开已经迁移的 PostgreSQL 工作区，正常退出保留新停机证明，中断后拒绝自动重开。

**当前可用范围只有存储状态、记忆读取和新增。** 现有 HTTP 路由限制保持原样；对话、知识操作、Finance、模型执行仍关闭。原始来源、配置、目标身份或历史不满足核对条件时停止，不回退到普通 Java 启动。完整维护仍为 **BLOCKED / productionGo=false**，没有授予不受限普通启动或真实外部模型执行权限。

## 完成判据与实现

- 日常桌面入口只用迁移目录名选择检查对象，不把目录、历史 PID 或停机文件当作启动许可。真正准入仍由原生 parent、实际子进程句柄、运行时排他锁、源及备份固定句柄、受保护配置、当前 PostgreSQL 连接和完整会话链共同完成。
- 新 `REVIEW_RESTART` 在消耗旧停机凭证前准备当前核对。当前目标连接及其表锁、来源／模型状态／历史固定句柄一直持有；原生窗口展示当前 Profile、数据库、角色和本次随机摘要，必须勾选后确认。摘要绑定 operation、前次会话及实际退出记录、配置、模型状态、目标身份和随机 nonce，最长 120 秒。
- 取消只结束本次检查，不写 `next.dpapi`，不启动数据 Runtime、不改业务数据。确认后重核当前资源，然后由原有 append-only 协议先写 successor intent，再启动新 Runtime。旧摘要或缺失／中断的 successor 不能回退到更早的成功停机记录。
- 新会话仍以原有 `MaintenanceRuntime` 持有源、配置、模型状态和真实数据库连接；不放宽 `MaintenanceStartupFence`。目标检查只核对当前绑定和迁移凭证，不拿旧导入内容覆盖后来数据。
- 原生桌面绑定实际动态端口与本次进程。此类会话通过受保护的存储接口验证凭据，避免把模型就绪检查当作登录副作用；端口和凭据检查方式一起绑定到同一代 endpoint，替换后旧检查失效。
- 正常退出调用原有 FINISH、验证实际 JVM 退出、再次检查停机现场，并确认本次 Job 中无活动进程才设置 `LastStopConfirmed`。取消、EOF、Dispose 和 Job 中断不制造停机证明。没有全机端口／进程扫描、旧 PID 接管或未知进程终止。

## 实际验收

权威证据目录：`.verification/migrated-workspace-startup-20261110-r3/`。全新合成 Profile `a3cf260cb3764706b5d40dc4b04a6850`，operation `964b966fdb7948488e4c5c5116f8373e`。使用现有受限角色和临时 PostgreSQL；先通过现有真实迁移路径准备这个新场景，未复用旧迁移令牌、数据库、凭据或停机记录。

| 场景 | 结果 |
| --- | --- |
| 日常入口取消 | UI Automation 仅操作当前测试进程的真实原生窗口。确认按钮初始不可用；实际取消后历史文件集合不变，未出现 `next.dpapi`。 |
| 日常入口确认 | 实际勾选并确认，摘要与取消那次不同；真实 `DevelopmentRuntimeOwner` 到达 READY，存储状态为 POSTGRESQL。 |
| 模型阻断 | `/api/v1/models/status` 经现有服务器限制返回 MODEL_UNAVAILABLE，未进入 provider。未运行真实模型，也未发送用户数据。 |
| 正常退出和后来数据 | 通过真实 RuntimeClient 创建记忆 `9c35278b-41e2-4800-96e5-25163f066e8e`，正常退出得到该次 native-exit 记录；再经新原生确认打开后读到相同内容。 |
| 实际中断 | 第二次打开后调用原生 owner 的紧急 Dispose，实际关闭自有 Job，`LastStopConfirmed=false`；第二次会话 intent 保留，没有 native-exit。 |
| 中断后再次启动 | 日常入口拒绝，未生成第三次 successor，整个历史文件集合不变。数据库仍保留新记忆；来源 SQLite 和发布配置的字节摘要未变。 |

`startup-result.json` 所有上述断言通过。这里只编译变更的 1 个 Java 文件（7 个 class）及所需原生客户端；最终 .NET 构建 0 警告、0 错误。验收 Runtime SHA-256：`54AB15C11075334C74D05A520ACB6533B822896EB3E09E34EF4E6DF3EDE2D325`；Desktop DLL SHA-256：`D206C2DAB8C6D3BBAB6180FAAF759C670586CAD626FDE60AA405B0BB53F0B4BF`。`accepted-sources.json` 保存验收时产品源码摘要。

首轮及 r2 在新原生确认窗口之前停止，没有新增会话或修改数据。r2 的有界诊断定位到 PostgreSQL `inet_server_addr()::text` 含掩码，严格地址比较拒绝；改用已有迁移实现采用的 `host(inet_server_addr())` 后通过。两轮失败证据原样保留，各自临时数据库、凭据和 Job 已清理。没有重跑上一模块已通过的两次普通源启动和一次中断实验。

最终 `native-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json` 均成功：临时凭据／配置已清理，数据库正常停止，外层 `forcedOwnedExit=0`，未碰既有服务，`systemPortQueries=0`。guardian 45012 于 `2026-11-10T16:52:34.32983Z` 记录 Job active 0、Job 与共享锁释放、failure=null。共享锁始终为 `Local\Dot_KAG_Workspace_HeavyRun`。

本次是原生日常启动 owner 与确认窗口的真实 UIA／Runtime 验收，**不是完整桌面 WebView、视觉排版或真实用户交互验收**；`visualAcceptance=false`。没有重新打包或修改旧包；本模块交付源码及隔离验收候选物，不能把先前发布 ZIP 当作包含本模块的新包。

## 下一会话接续

本会话已有三个实质模块：普通源工作区原生生命周期、迁移前当前来源／目标最终确认、迁移后日常桌面入口的受限重开。前两项验收范围分别见 `ORDINARY-NATIVE-LIFECYCLE-ACCEPTANCE.md`、`MAINTENANCE-CONFIRMATION-ACCEPTANCE.md`，本次不扩张其证明。

下一个核心条件是外部模型执行的真实所有权和结束证明，并明确全部写入者覆盖范围。现有执行 guard、模型状态固定句柄和模型禁用，仅证明当前工作区状态／本会话不执行模型；不能证明远程请求、手动模型进程、未登记写入者或旧版本进程已经结束。这些未知条件继续阻断，不得扫描全机、杀未知进程或用普通用户确认替代停止证据。

还需在真实前提有证据后，开放所需业务路由、将通用维护入口的来源／目标选择接入已有最终确认，并补完整用户路径验收。不能凭本次模型禁用会话启动成功宣称完整迁移维护可用。

先前 ZIP `artifacts/PersonalAiWorkspace-maintenance-confirmation-20261110.zip` 仍为 `D8A8FF61EF20245485400CAED48364517C9337AD312A6EC8E88AFC155A6FB43F`；main 仍为 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。六份用户文档、旧包及其目录 Runtime 已在 `final-baseline.json` 核对未改。真实数据、默认配置、凭据及其他仓库未触碰，不推送。没有待审批、拒绝待重试或未知运行结果。
