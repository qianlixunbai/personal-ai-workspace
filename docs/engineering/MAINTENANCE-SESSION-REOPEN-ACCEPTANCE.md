# 原生维护会话重开与中断失效

2026-11-10，承接产品 `79f81ff16527ccd15d4ce04c56b5db6b6b781e71`／交接 `d5a57d6b87cb049c68b0c07f0b6c2020d150ea36`。本轮只推进 personal-ai-workspace。完成的范围是**模型关闭的原生维护会话再次打开、每次独立留证，以及中断后拒绝沿用旧停机记录**。这是受限维护 transport，不是普通启动授权，也不是已接通的个人迁移向导。完整维护仍为 **BLOCKED / UNREGISTERED_WRITERS_UNPROVEN**。

前置：[模型状态移交与审阅](MAINTENANCE-MODEL-REVIEW-ACCEPTANCE.md)。原 `MaintenanceStartupFence`、默认普通启动、完整停写证明、外部模型权限和迁移审阅页的禁用执行按钮不变。没有模型执行、权重下载、外部收费 API 或真实用户数据库操作。

## 三个交付层级

### 每次会话都必须独立完成

新增 `MaintenanceSessions`，在原 operation 下保存连续编号的 `sessions/00000001` 等会话。原生父进程确认自己的实际 Runtime Process 完成 FINISH、退出 0 且输出管道 EOF 后，才启动独立 inspector。inspector 在原 Native Gate／live exclusion 下重验完整阶段链、当前来源、备份、受保护配置、当前目标和模型状态，再保存 `native-exit.dpapi`。它绑定 operation、session、实际 child PID、随机 challenge、终态摘要、配置和模型摘要。该记录不是根据 PID 查询、旧完成文件或“进程已经不见了”推断的。

每次重开先由新 owner 核对所有前序会话和退出记录，在前序目录 CREATE_NEW 写入 `next.dpapi`，然后建立新会话目录。**这个意图在下一 Runtime 的 Process.Start 之前落盘**。它绑定下一个连续编号、前序退出摘要、Profile／根目录身份和 `MODEL_DISABLED_MAINTENANCE` 模式。新 child 的实际 PID 与自己生成的 challenge 随后进入该次 `intent.dpapi`。记录使用既有 DPAPI、私有文件创建和持有句柄机制，不修改 ACL／EFS／系统策略。

历史校验逐个核对完整链、前后绑定与目录白名单，最多接受 128 次后继会话；超过上限安全拒绝。缺失目录、缺少阶段、已有未结束 successor、额外条目、不同配置／模型／目标、缺少 native exit witness 均不能退回早期成功会话。失败记录保留，无自动修复、覆盖、跳过或补造终态。旧包的终态缺少新 witness 时不会自动升级成重开能力。

此链不声称抵御同用户或管理员对整个受保护存储的恶意回滚，也不证明任意非协作 writer 已停止；原有隔离和信任边界仍适用。

### 原生重开接线与真实所有权

`MaintenanceRuntimeHandoff.ReopenAsync` 使用已选 operation，重新打开受保护的运行排他句柄、模型 reader 和 pinned 产品来源；通过 `--native-maintenance-restart-v1` 启动自己的 owner。拿到 durable PREPARED 后才创建 Runtime，核对真实 Process／会话、移交句柄、绑定 challenge。新 Runtime 接住当前来源、配置、目标和模型 pins 后，父进程确认 owner 真实退出，随后才切换真实 `ModelStateStore` writer 并初始化 Spring。

正常结束再次走真实 store／manager quiescence、context.close、writer 关闭、独立当前证据复核和 native exit witness。每次重开都是新对象、新 Process／Job 和新的握手；不会把旧 Runtime 实例恢复成运行态。该入口目前仅是开发构建内部 transport，没有新增面向用户的执行按钮或自动启动分支。

### 保留迁移后的数据与会话凭据

首次接管仍严格核对导入内容摘要。重开改用目标当前绑定及原有不可变导入凭证：系统／数据库／角色／schema／receipt 身份、operation 和两个导入摘要必须匹配，业务表保留当前内容。在 bootstrap、实际 DataSource 接管与 Spring 后端核验时都使用这个明确模式；不能重新套用旧导入快照来覆盖或拒绝合法的后来数据。

原开发 Runtime 每次启动都会更新本机客户端令牌。重开调用方须在新 Runtime 就绪后重新读取受保护 token，旧 HTTP 凭据不会续用；未修改这个既有行为。模型 gate 与 HTTP 路由限制持续生效，模型 API 返回 503。模型选择和 validation 状态保持原有只读保留语义。

## 验证记录

Java 候选 SHA-256：`E2C1601293A15E8805E4BA15C8C976DE80A7D0F7B0D6499F892B7C1F215F9D76`。仅编译本轮 6 个 Java 源文件、生成 24 个 class，并基于上一已交付 JAR 更新相应 class；10 条既有 `ExtendedOpenOption` 警告。原生编译零警告、零错误。沿用共享命名互斥 `Local\Dot_KAG_Workspace_HeavyRun` 和已审阅的独立 Job 包装；构建／服务全生命周期持锁，静态工作不占锁。

本轮先保留了三个未完成验收的现场，均没有被当作通过：

- `.verification/maintenance-session-reopen-20261110/`：Java／原生构建成功。受限执行账户启动临时 PostgreSQL 时，`pg_ctl` 创建受限令牌失败（87）；数据库未启动，产品未运行。Job active 0、共享锁释放；初始化密码已删除，客户端令牌尚未生成。
- `...-r2/`：主机账户启动临时 PostgreSQL 成功，但准备阶段的 Profile 属于 sandbox 账户。fixture 在创建业务来源前因 Profile 不匹配停止；没有绕过原 Gate 或修改旧 Profile。临时 PostgreSQL 正常停止，密码清理，Job active 0，客户端令牌未生成。
- `...-r3/`：准备与执行改用同一非管理员账户。产品完成初次接管、正常停机、退出 witness 和第一次重开的 VERIFIED。验收客户端沿用旧令牌，请求失败后关闭自己持有的 Job；这条记录保留为未完成会话。仅修正验收客户端重新读取令牌，并增加旧令牌必须返回 401 的核对；Java 产品候选不变。临时 PostgreSQL 正常停止，暂存／实际合成配置及 token 清理，Job active 0。

最终聚焦流程在 `.verification/maintenance-session-reopen-20261110-r4/` 通过；复用完全相同的 Java 候选和原生产品 DLL（`BC006380AECCA32560DA26BA06D6FCBFB4201F8FDBD3E816587C6ECBAD008D11`），仅重新构建验收客户端。新 Profile 为 `69e3038477644c6f8b27b84cc2301278`，operation 为 `95c36cc9281a4201998e538f2e2aab27`。

| 阶段 | 实际结果 |
| --- | --- |
| 初次会话 | Runtime PID 41428，HTTP 54501，三个实际后端 30776／27056／41564。写入 `d9fca32b-2dfd-497e-9c8d-7d13850f1aed` 后正常结束并记录 native exit。 |
| 第一次重开 `00000001` | 新 Runtime PID 37388，读回初次会话数据；旧令牌 HTTP 401，新令牌可访问；保存 `46060b62-f6ab-4856-9ef4-9563494361a1`。模型路由 503。正常关闭与当前证据复核完成。 |
| 第二次重开 `00000002` | 新 Runtime PID 42156，读回第一次重开新增内容。真实 owner 关闭自己持有的管道／Job；保留 intent、PINNED、INITIALIZING、VERIFIED，没有 STOPPING、VERIFIED_STOPPED 或 native-exit。 |
| 中断后的下一次重开 | 新 owner 拒绝；所有 activation 文件的路径与字节哈希保持不变，未创建第三次会话；实际独占 ReadWrite 打开模型 `.lock` 证明 writer 已释放，未写文件。 |
| 后续检查 | 来源 SQLite／知识原文和非空合成模型选择保持不变。初次会话新增行在全部 Runtime 退出后由 SQL 再读回。新的普通 JVM 仍输出 `PUBLISHED_CONFIG_STARTUP_FENCED`。 |

主要回执：`handoff-result.json`、`handoff-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json`。最后 guardian 41448 于 `2026-11-10T14:59:15.2135867Z` 记录 Job active 0、Job 关闭和共享锁释放。临时 PostgreSQL 正常停止，初始化密码、暂存／实际合成 DPAPI 配置和客户端 token 均清理；外层 `forcedOwnedExit=0`。第二次重开的内部 Job 中断是有意执行的真实中断，不能描述成 Runtime 自然退出。

只运行这一聚焦产品流程及其修正后的验收客户端，没有重跑完整迁移事务、旧 UI 或模型矩阵。未完成普通重启、原生父进程自身重启后的接续、真实断电、所有消息丢失组合、恶意全历史回滚、已有非空 validation 文件、真实模型 runner／权重／推理或 WPF 视觉验收；不扩展前轮已有验收范围。

## 边界和后续

当前 inspector 返回 `ordinaryStartupAuthorized=false`，具体剩余项为 `ORDINARY_NATIVE_RESTART_REQUIRED`、`EXTERNAL_MODEL_EXECUTION_UNPROVEN`、`UNREGISTERED_WRITERS_UNPROVEN`、`USER_CONFIRMATION_REQUIRED`。受限维护重开不能直接作为普通启动的新分支。

下一步先把这个逐会话协议接入真正的普通原生启动／退出生命周期，并保持对中断和未知结果的阻断；再完成外部模型专属资源 owner 与退出证明。真实 runner 的可执行文件及权重来源、许可／哈希、专用实例、资源预算、输入和外部费用范围需要明确授权，不能由模型状态文件代替。最后再接来源／目标选择、当次最终确认和完整执行入口。

main、六份用户未跟踪文档和旧 ZIP 均已核对基线；真实数据／默认 Profile／数据库和持久凭据未触碰。没有触碰 KAG、18765 端口查询、Docker、私有会话存储或前轮被拒 Profile；没有修改系统安全设置。准备／执行均通过原有非管理员检查，主机执行获准后才运行，不使用管理员进程。没有新的自动审批拒绝或待审批动作。一次只读工具传输断连后已重新确认 Git 现场，没有重复不确定写操作；所有构建／运行均有完成和清理回执。当前上下文没有实际模型／推理强度元数据，因此无法核实本轮是否确为 Astra xhigh，也不推断历史 Sol 用量。
