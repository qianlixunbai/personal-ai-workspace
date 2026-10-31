# X1 Execution Lifecycle Reliability 验收

2026-10-31，Asia/Shanghai。入场 `main / origin/main @ 8a0ac0fae6f9d5d119a65490602922da8a2abdc7`，工作树干净；按要求经本机代理 fetch 成功。工作分支 `codex/x1-execution-lifecycle-20261031`。

**X1 继续 PARTIAL。Phase 0 PASS；Phase 1 发现原生取消确认缺陷，最小源码修复后隔离验证 PASS；Phase 2 / 3 未执行。** 当前受信客户端仍为 `8df87ab`，未包含本次修复，生命周期一致性问题尚不能在运行客户端上视为解决，因此停止真实模型流程。没有用更换客户端或额外请求取得 PASS。

**Vision BLOCKED、Development-only、无已核验 CI PASS、尚未 Production GO** 保持。此前 Q/T E2E、X/Esc 关闭、记事本来源焦点和微软拼音基础操作的历史 PASS 保留，参见 [真实 Q/T E2E](X1-REAL-E2E-ACCEPTANCE-20261031.md) 与 [P2 / IME 收口](X1-WINDOWS-INTERACTION-P2-20261031.md)；本轮没有重复这些基本操作。

## Phase 0：实时准入及客户端版本

本机 ignored 证据目录：`.verification/x1-execution-lifecycle-20261031/`。下文证据文件均相对此目录；不提交真实 Profile 路径、身份、凭据或用户内容。

`phase0-runtime.json`（16:50:11 +08:00）实际确认：

| 准入项 | 结果 |
| --- | --- |
| Desktop / Java | 原 Desktop PID `17960`，Java PID `24012`，Java 的父进程、JAR 路径、Profile 参数及 loopback `18765` listener 匹配原进程；代次与原验收记录一致。 |
| Development Build | `ui-latest-development-8df87ab-20261029-01`；EXE、Desktop/Core DLL、Java EXE、Runtime JAR 的 SHA-256 均匹配既有包清单，见 `phase0-package-pins.json`。NativeBootstrap 固定字节核对及 `VerifyProfileNative` 通过。 |
| Profile / Native | last-workspace 为原 Profile；Native 目录身份核验通过；既有私有 ACL 凭据仅在内存使用；受认证 GET 成功。 |
| Runtime / PostgreSQL | health `UP`；`POSTGRESQL / TRUSTED_LOCAL / CONNECTED`；Workspace `V1_V2_READY`，Knowledge / Finance schema `READY`，`errorCode=NONE`。 |
| Active Model | Configured / Active 均为 `qwen3.5:4b`，runner `llamacpp`，digest `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。 |
| 准入状态 | `ready=true`、`uncertain=false`、`validationRequired=false`、`switching=false`；reserved / queued / executing / draining 均为 `0`。 |
| 远端可观测状态 | Ollama PID `15236` 保持；`GET /api/ps` 返回 `models=[]`；该次连接快照未观察到其他 Ollama 客户端。不是对整个操作时段的连续排他证明。 |

`loaded=FALSE` 是实际返回值，不能改写为已加载。Ready 与驻留状态分别记录。main 中“正在提交请求…”的提示修复也未进入该运行客户端；`8df87ab..8a0ac0f` 的 `AssistantApp.cs` 差异仅该提示，取消 / 版本判断链相同。

## Phase 1：既有覆盖、确定性测试与修复

先审阅 `TaskManager`、`AssistantOperation`、`AssistantApp`、`AssistantWindow`、`WorkspaceOperations` 及既有 Desktop / mock Bridge 测试。Java 两种终态顺序与 Core 的 POST pending cancel 已有覆盖，没有增加重复测试，也没有修改 Java、Runtime HTTP 合同或前端生产代码。

| 场景 | 本轮实际结果 | 证据及限制 |
| --- | --- | --- |
| 取消先于迟到成功 | 隔离 PASS | 既有 `ownersIsolateRunningQueuedAndRetainedTasksAndOwnerCancellationWinsLateResult`：latch 确认 RUNNING 后取消，再放行迟到结果；单 worker 后续任务屏障确认旧结果已处理，仍为 CANCELLED、无结果。 |
| 成功先于取消 | 隔离 PASS | 既有 `boundedQueueCancellationAndLateSuccess`：确定 SUCCEEDED 后取消返回同一终态 view，结果及完成时间不变；同一用例亦覆盖取消后迟到结果。 |
| POST pending cancel | 修复前 UI FAIL；修复后隔离 PASS | 既有 Core 回归确认不取消 POST，先取得 UUID 再 DELETE；新增原生 fixture 进一步断言 UI 显示已确认取消、结果为空、按钮恢复。修复前已得到 CANCELLED，但 UI 仍显示“正在请求取消，等待确认…”。 |
| UUID 返回后取消 / 完成竞争 | 隔离 PASS | 原生 fixture 先返回 RUNNING、挂起 GET，再取消；GET 仍为 RUNNING，随后 DELETE 返回已确定的 SUCCEEDED。UI 如实显示完成及该任务结果，没有伪造取消。 |
| 忙时 Q/T / 重复提交 | 隔离 PASS | POST pending 及 RUNNING / 关闭后 draining 期间，直接调用真实 `WindowMessage` 的 Q/T 分支及 `SubmitAsync`；无新增 capture、无版本改变、无重复请求。没有注册或注入真实快捷键。 |
| 执行中关闭、重开、迟到进度 / 成功 | 隔离 PASS | 挂起 RUNNING 的 GET 后走真实关闭入口；重开为空，旧 RUNNING 及 DELETE 的 SUCCEEDED 均不能回填；旧 operation 结束后 busy=false，新的捕获与提交显示新结果。 |
| 执行中关闭、重开、迟到错误 | 隔离 PASS | 独立受控响应返回迟到 transport error；原有 best-effort DELETE 完成后，重开的窗口仍无旧原文、问题、结果或错误；新的捕获与提交正常。 |
| UNKNOWN 不重试 / 重放 | 隔离 PASS | 既有 Desktop 测试区分未知 POST 与本地缺凭据；mock Bridge 的 POST timeout、session rotation / reload 断言不重发，并丢弃旧回复。 |

关闭后旧 operation 尚未结束时，真实 owning code 继续 Busy，禁止 Q/T 捕获和新提交；fixture 明确验证此阶段没有并行新任务。旧 operation 结束后才重新捕获、提交，并断言按钮恢复；不把这一合同写成关闭后立即可以并发提交。

### 产品根因与最小修改

`CancelOperation → ClearText` 递增 `ContentVersion`；原 `RunOperationAsync` 固定捕获旧版本，因而将当前任务的取消确认、成功终态或错误也当作旧回调丢弃。按钮最终恢复，但状态可能永远停在等待取消。

仅修改 `AssistantApp.cs`：保存当前 operation 对应的内容版本，用户取消清空内容后重新关联该版本，使同一任务的真实确认可展示。关闭入口随后再次 `ClearText` 递增版本，仍屏蔽旧进度、结果和错误。保留原单 operation / Busy 门禁及取消时清空原文、问题、结果、Memory 选择的行为；未修改生命周期中的 Runtime 终态、认证、模型、数据库和持久化合同。

回归复用既有 `RuntimeLifecycleProbe`，增加 opt-in `--x1-execution-lifecycle-fixture`。独立进程实例化真实 `AssistantApp` / `AssistantWindow`，测试代码通过反射绑定现有 fake HTTP handler；以 `TaskCompletionSource` 控制 POST / GET / DELETE 时序，等待均有界。无需生产测试注入 API，没有新增依赖或长期重复单元测试。四条 PASS 标记为同一 integrated fixture 的覆盖点，不能计作四次独立验收执行。

fixture 初次调试时发现 WPF 构造器即排程 OnStartup，首次尝试进入本地 WebView 启动并超时，退出 1；这是 harness 问题，未作为产品缺陷或 PASS。fixture 随后仅中止构造器排程的启动 operation，断言无 Workspace / Tray，避免启动 WebView、真实快捷键和真实服务，再取得 `desktop-fixture-before.log` 中上述产品断言 FAIL。生产启动代码未因 harness 调整。所有 fixture Runtime 请求均绑定内存 handler。

### 实际执行的聚焦验证

| 执行 | 结果 | 本机证据 |
| --- | --- | --- |
| Java 既有 TaskManager 两项 | 2 executed / 2 passed / 0 skipped | `java-task-races.log`、`java-task-races.xml`。无模型 / 数据库连接的进程内测试。 |
| Desktop fixture 修复前 | 退出 1，取消确认断言 FAIL | `desktop-fixture-before.log`。 |
| Desktop fixture 修复后 | 退出 0，四个覆盖点全部完成 | `desktop-fixture-after.log`。这是一次隔离集成流程，不是物理验收。 |
| Desktop 既有聚焦测试 | 5 executed / 5 passed / 0 skipped | `desktop-focused.log`、`desktop-focused.trx`，核对 TRX executed / passed 均为 5。 |
| Frontend 既有 mock Bridge 聚焦测试 | 3 passed / 15 filtered skipped | `frontend-lifecycle.log`；15 项没有执行，不计 PASS。 |
| Desktop 增量 build | PASS，0 warning / 0 error | `desktop-fixture-after-build.log`；`FrontendSkipBuild=true`，两种 MMF3 acceptance build 均 false。未发布或替换受信包。 |

可复现入口（在仓库根目录，复用现有依赖；Java 使用本机已审查 JDK 21）：

```powershell
.\mvnw.cmd -pl workspace-application -am '-Dtest=TaskManagerTest#boundedQueueCancellationAndLateSuccess+ownersIsolateRunningQueuedAndRetainedTasksAndOwnerCancellationWinsLateResult' '-Dsurefire.failIfNoSpecifiedTests=false' test

dotnet build desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:RuntimeLifecycleProbe=true -p:FrontendSkipBuild=true -p:Mmf3ProfileAcceptanceBuild=false -p:Mmf3AcceptanceBuild=false
& .\desktop\tests\PersonalAiWorkspace.Desktop.Tests\bin\Debug\net10.0-windows\PersonalAiWorkspace.Desktop.Tests.exe --x1-execution-lifecycle-fixture

dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-build --no-restore -p:RuntimeLifecycleProbe=true --filter 'FullyQualifiedName~CancelWhilePostPendingWaitsForIdentityThenDeletesAcceptedTask|FullyQualifiedName~CancelDoesNotCopyNonterminalOrOverrideTerminalRuntimeTruth|FullyQualifiedName~ReloadSuppressesPendingSubmitReplyWithoutCancellingOrReplayingAdmission|FullyQualifiedName~SharedRunnerDistinguishesUnknownTransportAdmissionFromKnownCredentialFailure'

npm --prefix desktop/frontend test -- src/pages/OperationPage.test.tsx src/bridge/client.test.ts -t 'POST timeout displays Outcome unknown|session rotation clears state and never replays|invalidates pending requests on reload without replay'
```

未执行 Java / Desktop / Frontend 全套、完整浏览器 / IME 矩阵、stress、打包矩阵；文档整理后不重复已通过测试。

## Phase 2 / 3：真实物理验收尚缺

| 项目 | 本轮状态 |
| --- | --- |
| 用户物理 Q → 单次提交 → RUNNING → 点击取消 | **BLOCKED / 未执行**：隔离测试发现的取消状态缺陷仍在原运行客户端中。没有安排用户重试，没有自动代操作。 |
| 真实取消 UUID、终态、完成时间 | **无本轮任务，均不适用**。fixture 生成的合成 UUID 不能充当真实任务证据。 |
| 执行中物理 X/Esc → 手动重开 → 旧任务终态 / 迟到隔离 | **BLOCKED / 未执行**：同一原客户端限制；没有新增第二条模型任务。历史空闲关闭 PASS 不替代执行中关闭。 |
| 完成早于物理取消 / 关闭的时序 | **未测试**，无物理操作时间线，不声称覆盖。 |

代理本轮真实推理提交数为 0；没有 VALIDATE / RECOVER / SWITCH / RELEASE、服务重启、guard 清除或原生确认代操作。CANCELLED 仍不代表远端计算一定停止；本轮只有 fixture 的取消终态，没有真实取消后远端执行证据。

## 最终真实状态与交接

`final-runtime.json`（16:55:38 +08:00）与 `final-host.json`（16:55:39 +08:00）实际复核：

- 原 Desktop / Java / Ollama PID 及代次保持，Runtime health UP。原 PostgreSQL connected；Workspace / Knowledge / Finance schema 状态不变，`errorCode=NONE`；PostgreSQL listener 仍在 `5432` / `55432`。
- Active / Configured identity、digest、runner、selection revision `0` 及 recovery generation 未变；Ready、`uncertain=false`、`validationRequired=false`、`switching=false`，reserved / queued / executing / draining 全为 0。`loaded=FALSE` 原样记录。
- Ollama `/api/ps` 仍 `models=[]`；连接快照未观察到其他客户端。首尾快照未发生新的 uncertainty，不能扩充为无间隙监控证明。
- 受信包五项字节与入场一致；原 Workspace / Finance DPAPI 配置 hash 匹配原始记录。未替换客户端、修改真实 Profile / Finance 数据 / 凭据或改动安全门禁。
- 隔离流程未观察到重复提交、异常回填；本轮真实取消及执行中关闭未测，不能对此给出 Windows 真实 PASS。

交付仅包含上述最小 Desktop 修复、既有 probe 的隔离 fixture 及本报告；在本开发分支 Commit / Push，不合并 main，不创建 Tag / Release。最终提交 SHA 与工作树状态以交接消息及 Git 实际状态为准。

后续仍需在明确允许使用包含修复的受信客户端后，由用户本人完成最多两条真实模型流程，记录实际 UUID、终态、完成时间、取消 / 关闭时序及远端可观测状态。本轮在此停止，**X1 PARTIAL** 不变。
