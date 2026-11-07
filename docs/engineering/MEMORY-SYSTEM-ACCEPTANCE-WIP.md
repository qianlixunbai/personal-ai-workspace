# 整个记忆系统的验收状态、PostgreSQL 与桌面闭环

**2026-11-07 最终更新：当前显式记忆模块已完成必要验收；本文下方是原样保留的历史 WIP。** 第 29 轮完整应用与真实模型闭环、重启持久化通过；同版本第 28 轮独立质量 fixture 的 11 次调用通过断言及单独语义复核；最终第 9 轮合成桌面回归通过。当前范围、证据、剩余限制及暂停安排以[完成报告](MEMORY-SYSTEM-COMPLETION-REPORT.md)为准。下文“尚未完成／不提交”等结论只描述各历史时点，不覆盖本段最终结论。

2026-11-07，基线 `a50a16d905c1dc2e187e844bf08c2034bb45ce07`。**整个记忆系统尚未完成验收。** `a50a16d` 只交付并推送了显式相关检索增量，不能将其等同于整个模块完成。本轮修改保留本地 WIP，未提交、未推送、未合并分支。

## 当前完成范围

最新进展：[原生结构化输出与完整应用复验](MEMORY-STRUCTURED-OUTPUT-REPORT.md)。接续[早期失败记录](MEMORY-MODEL-RECHECK-REPORT.md)，已将 Ollama 固定 JSON Schema 接入现有原生候选任务，保持严格逐字证据与语言保护。原生 HTTP 4/4、.NET 38/38 及独立真实 Qwen3.5 4B 的 8 个候选／3 个会话小样本通过，另行语义复核绑定原结果 SHA。完整 AssistantApp／真实模型组合仍卡在加载后原生进程准入：第 22 轮未通过，没有进入候选保存流程；Windows 控制台／OpenMP 兼容适配的 5 项身份回归通过，继续定位剩余检查。没有把独立模型成功或诊断完成当作完整验收。

| 闭环 | 已有实现／实际验证 | 仍需补足 |
| --- | --- | --- |
| 对话原文 → 可编辑候选 → 审阅 | 单条来源、主动生成最多一条候选、固定原生 schema、字面证据摘录、丢弃或明确采用；真实 WebView／Bridge／HTTP 合成提供器流程和独立真实模型 8 场景通过 | 完整应用组合未通过；小样本不证明合格率或总体忠实度，早期失败记录保留 |
| 审阅 → 查重／修订 → 保存 | 本轮真实桌面自动化通过：发现重复后阻止采用、明确确认、对照原记录、采用后仍未保存、保存更新同一 ID 的 revision | 真实模型与完整应用启动组合验收；人工输入与 IME 未覆盖 |
| 保存 → 检索 → 预览 → 本轮使用 | 真实 WPF 选择器、词法检索、完整预览、显式 Add／Use、SQLite HTTP 链路通过；独立真实模型两轮使用合成颜色记忆通过 | 与完整应用组合未验收；PostgreSQL 桌面整链路未单独运行 |
| 会话内连续使用与撤销 | 桌面连续两轮引用同一版本、过期拒绝且不增轮次、清除后无后续显式引用；修正后真实模型 3 轮小样本承认旧历史仍包含事实 | 独立模型调用不等于完整应用端到端；小样本不证明模型总能遵循上下文 |
| PostgreSQL 持久化与检索 | **本轮新建独立 PostgreSQL 17.11 实例，真实原生准入、生产 Store 与迁移路径通过**，范围见下文 | PostgreSQL 对应 HTTP／桌面整链路未单独运行；不代表所有 PostgreSQL 功能验收 |

没有新增自动记忆、隐式上下文、向量服务或个人备份／恢复能力。个人备份／恢复仍关闭。桌面数据流已有真实自动化证据，真实模型及其与完整应用启动的组合验收仍未完成；不宣称整个模块完成，也不推送或整理合并后续分支。

## 最新修正的离线／桌面回归

2026-11-07 本次真实复验后的最终源码：前端 23 项通过，TypeScript／Vite 和独立 .NET 编译通过；新增真实失败回放均保持拒绝。会话系统提示从 363 增至 494 UTF-8 字节，仍在 512 字节上限内，现有 wire reserve 自动扣除增加的开销。独立 Native Gate fixture 首轮为 3/4：旧测试中的 1,810 汉字已超过当前完整消息预算。将测试明确改为验证 1,760 汉字的实际序列化 payload 可容纳、1,810 汉字与长模型名输入应拒绝后，第二个全新 Profile 的 **4/4 通过**；未增加预算或绕过准入。见 `.verification/memory-context-regression/run-2/`。

最新前端资产与当前 Java 提示的真实 WPF／WebView／HTTP／SQLite 合成提供器回归，`.verification/memory-desktop-acceptance/run-8/` **完整通过**，JUnit `found=1 passed=1 failed=0`，host exit 0，自有 JVM 已退出。覆盖与第 6 轮相同的查重、修订、显式检索选择、连续使用、过期拒绝、清除、重开、归档／恢复／删除和实际提供器 payload 断言。前一轮 `run-7` 在首个来源会话显示 Provider unavailable 并失败；该轮与编译／前端测试同时运行，隔离提供器的 200ms health/connect 时间预算可能受调度影响，但没有直接超时诊断，**原因尚未证实**。第 8 轮串行运行，代码、期望和超时均未放宽；两轮结果完整保留，不把第 7 轮改为成功。

本轮模型、JVM 和 WebView 所属进程均已回收；7 个新增模型实例的临时副本在核对精确路径、无链接及退出状态后删除，回收 47,514,786,557 字节。来源模型目录、所有响应、SHA 清单和历史失败证据保留。未创建提交，HEAD 和 Git 回滚标签仍为原基线；完整 WIP 的前后 patch、新文件副本和摘要位于 `.verification/memory-model-recheck-1/`，这些本地副本不是新的 Git 提交或异机备份。

## 本轮选 PostgreSQL 的理由与复用评估

数据库闭环可以用完全合成且独占的临时实例验证，避免先接触真实用户数据库或共享推理资源。先核对 [Testcontainers Java](https://github.com/testcontainers/testcontainers-java)：2026-11-07 GitHub 元数据显示 MIT、8,760 Star、未归档、最近 push 2026-11-02。[README](https://github.com/testcontainers/testcontainers-java/blob/main/README.md) 提供的成熟做法是以临时真实数据库支撑 JUnit 验证。

评估直接复用后，本轮不新增 Testcontainers 依赖：它需要 Docker 容器运行环境；当前任务已有经使用的本机 PostgreSQL 二进制与独立 initdb 测试方式，引入 Docker／镜像／清理组件会增加环境和资源范围，也不能替代本项目的 Windows Native Gate。没有启动 Docker／WSL、安装依赖、下载镜像或修改运行环境。许可证是公开元数据核对，不是对依赖的全面安全认证。

实际直接复用的是仓库 `synthetic-postgresql.ps1` 的受限工具调用／端口查询、`SyntheticPostgresqlFiles` 的文件和目录句柄、`MaintenanceFiles` 的私有文件验证、`MemoryRecallIntegrationProbe` 的真实准入和进程所有权、Flyway 的现有迁移，以及生产 `MemoryStore`／`ConversationStore`。只补 opt-in 测试、临时实例编排和有限的测试选择，不写第二套存储或模拟 PostgreSQL。未复制第三方实现。

## 隔离与执行结果

`bootstrap/tests/Run-MemoryPostgresqlProbe.ps1` 从普通、非提升权限的桌面宿主运行，创建全新 `paw-memory-postgresql-<uuid>` 私有 Temp 目录；它不能接受已有数据目录。随机空闲 loopback 端口且拒绝 5432，`initdb` 使用新随机密码和 SCRAM，16 MB shared buffers、最多 12 个连接。复用受限子进程环境，不继承 libpq 连接／服务配置。测试使用新数据库及无超级用户、建库、建角色、复制或 RLS 绕过权限的应用角色；应用角色没有 schema CREATE 或 public 表写权限。

在任何数据库修改前核对私有文件、路径、postmaster PID／启动时间／可执行文件、data_directory、system_identifier、端口和 initdb 初始目录清单；使用 CREATE_NEW 标记禁止失败 fixture 重放。Native Probe 仍执行原有固定 Bootstrap 和 Java Native Gate。收尾只针对 PID、启动代次、可执行文件与数据目录均匹配的自有实例，不停止已有 PostgreSQL 服务；清除本次两份临时凭据文件，保留合成数据和日志供复核。

执行了三次**全新** fixture：前两次分别发现测试调用的分页上限错误、请求 DTO 与持久 Selection 类型直接比较错误，均只修正测试，未放宽产品规则。三次 fixture 均有 `ownedInstanceStopped=true`、`temporarySecretsRemoved=true`。最终 `.verification/memory-postgresql/run-3/` 的证据：

```text
MEMORY_RECALL_NATIVE_ADMITTED
MEMORY_RECALL_TESTS found=1 passed=1 failed=0 aborted=0 skipped=0
testExitCode=0
POSTGRESQL_MEMORY_LIFECYCLE_VERIFIED
```

该综合场景实际覆盖：

- 现有真实 Flyway 迁移、应用角色准入与 public 表写拒绝；合成旁表内容保持不变。
- SQLite 与 PostgreSQL 保存相同合成内容后，Unicode／CJK 召回的正文、版本、得分和命中词一致；原始换行、空白、HTML 字面量保留；类型、ACTIVE、无匹配和符号输入行为正确。
- 明确引用的版本快照、更新后的旧引用拒绝、乐观并发版本冲突、归档消失、恢复重现、删除消失；重复检索不修改源行。
- 真正的 PostgreSQL `REPEATABLE READ READ ONLY` 快照，在另一个 Store 写入时保持原视图。
- 真实 Conversation Store 持久化用户／合成助手消息及 memory ID、revision、position；记忆修改后会话历史仍保存原引用；重开 Store／Conversation 后保持结果。
- 1,000 条真实源记录、前 20 条稳定排序、重复词不加分、`9007199254740993` 大整数版本、非法查询拒绝、异常 1,001 条受控拒绝。

没有模型调用；“synthetic answer”是显式测试文本，不能当作模型回答质量证据。这里直接验证 Store，不是 PostgreSQL HTTP／桌面端到端验收。测试后整库留有有意构造的 1,001 条异常合成行，已停止且无临时登录凭据，不可作为日常 Profile 使用或重放。

Java test-compile、隔离 .NET Probe／Desktop／Core 编译、PowerShell 语法检查通过。共享测试入口改动后，还回归其默认三个 SQLite Store／HTTP 场景；最终结果见本地 `default-probe/` 日志。本轮没有修改生产代码、schema、服务配置或安全检查。此前的前端、Native 合同测试没有因本轮测试设施修改而重复全量运行。

## 本轮补验：真实桌面与合成提供器

`bootstrap/tests/MemoryDesktopAcceptanceProbe.csproj` 是显式运行的 Debug 测试宿主，沿用普通 Explorer 桌面宿主、全新 UUID Profile、固定 Bootstrap、真实 Java Native Gate 与自有进程 Job。Java 使用既有 `RuntimeApiTest` 的随机 loopback HTTP 端口和合成 Ollama 服务；它仍运行生产 Spring 控制器、认证过滤器、SQLite Store 与任务／会话处理。测试只在 `memory.desktop.fixture=true` 时启用，不会在普通测试套件中等待桌面操作。

原生测试宿主复用生产 `MainWorkspaceWindow`、打包 React 资产、WebView2、`WorkspaceBridge`、`WorkspaceMemory`／`WorkspaceConversations`／`WorkspaceOperations`、`RuntimeClient` 和 `MemorySelectionWindow`。仅在测试中用现有 handler 注入将固定 loopback 请求转发到该自有随机端口，携带新 fixture 的真实原生凭据；没有更改产品端点、凭据存储或认证规则。WebView 使用新 Profile；自动化只操作可见 DOM 控件和自有 WPF 控件，不注入伪造 Bridge 响应，不采集整个屏幕或安装热键／托盘，不触碰剪贴板。

先评估 [FlaUI](https://github.com/FlaUI/FlaUI)：2026-11-07 GitHub 元数据显示 3,196 Star、MIT、未归档、最近 push 2026-08-13。它适合外部 Windows UI Automation；本仓库已有真实 WebView 脚本／原生控件驱动，直接复用这些设施能覆盖本次目标，避免增加 UIA 包、外部进程定位与焦点控制的维护成本。也核对 [Microsoft WebView2Samples](https://github.com/MicrosoftEdge/WebView2Samples)（1,047 Star、未归档、最近 push 2026-11-05）；仓库根目录和 GitHub 元数据未给出明确 SPDX 许可证，本轮没有复制其源码。参考 [Microsoft 的 WebView2 线程与重入说明](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model)，保留 UI 线程与异步调用。没有新增或下载依赖。Star 仅用于筛选，实际复用依据是已有接口、权限范围和资源成本。

资源约束：只启动自有测试 JVM（384 MB heap、2 个 active processors）及临时 WebView；不启动模型、数据库服务或容器。就绪等待 55 秒、UI 总时限 190 秒、单状态等待 25 秒，Java fixture 220 秒截止；退出只清理自己持有的 Java 进程树，正常完成会等待 WebView 清理。全部文本和凭据均属于全新合成 fixture。

共运行六个全新 Profile。前四轮暴露的是驱动问题：模态窗口与脚本完成通知的等待顺序、就绪文件尚未写完即被读取、点击到了保留在隐藏页面中的同名按钮。分别改为先驱动原生窗口再等待脚本完成、原子发布完整就绪文件、只匹配实际可见控件；未调整产品断言或安全边界。第五轮完整通过；随后补充“实际发给提供器的显式记忆正文”断言，第六轮再次完整通过。

最终 `.verification/memory-desktop-acceptance/run-6/`：

```text
MEMORY_DESKTOP_NATIVE_ADMITTED
PASS CANDIDATE_DUPLICATE_EXPLICIT_REVIEW_SAME_ID_REVISION_SAVE
PASS REAL_WPF_RECALL_PREVIEW_EXPLICIT_ADD_TWO_RETAINED_TURNS
PASS STALE_REJECTED_WITHOUT_TURN_CLEAR_STOPS_FUTURE_REFERENCES
PASS REOPEN_REAL_WEBVIEW_PERSISTED_REVISION
PASS UI_ARCHIVE_RESTORE_DELETE_REAL_RECALL
PASS MEMORY_DESKTOP_ACCEPTANCE
MEMORY_DESKTOP_TESTS found=1 passed=1 failed=0 skipped=0
host exitCode=0; javaExited=true; realModel=false
```

实际验证链路：

- 真实界面发送合成来源，转为草稿并主动生成候选；提供器固定返回符合生产解析器的 JSON。查重发现既有相同正文，未勾选确认时不能采用；采用候选／采用修订都不写库，点击保存才把同一 ID 从版本 1 更新为 2。
- WPF 点击“查找相关记忆”后只有一个匹配，未自动选中；完整预览后 Add，再 Use。勾选连续使用，手动发送两轮，真实持久引用均为该 ID／版本 2／位置 0。
- 第二个客户端把合成记录更新为版本 3；原选择再次发送被拒绝，总轮次保持 3。清除后发送第 4 轮成功且无显式引用，先前引用仍保存版本 2。
- 关闭并重新创建真实 WebView 窗口，读取到版本 3 的正文。界面归档后实际 recall 无结果，恢复后重现，删除确认后列表和 recall 均为空。这是窗口重开，**不是完整应用／Runtime 重启测试**。
- 提供器实际收到五个业务请求：原文一轮、候选一次、连续使用两轮、清除后一轮。中间两轮 `explicitMemory` 正文相同；原文、候选和清除后请求没有该显式上下文。清除后历史仍含最初的来源文本，不能宣称“模型忘掉了历史”。

`result.json`、`owned-cleanup.json`、合成请求记录与三个 WebView 截图留在忽略目录。Java test-compile、独立 .NET 编译通过（.NET 0 warning／0 error）；共享 HTTP fixture 改动后默认 SQLite Store／HTTP 回归结果为 `found=3 passed=3 failed=0 aborted=0 skipped=0`，host exitCode 0。未重复无关全套测试。

边界：这是实际 WPF／WebView／Native／HTTP／SQLite 的自动化数据流验收，**不是物理键鼠、IME、完整 AssistantApp 启动、发布包信任、真实模型质量或 PostgreSQL GUI 验收**。测试 native adapter 只提供本次功能所需的生产管理器和真实健康／凭据检查；没有冒充完整应用启动状态。未修改生产逻辑、schema、服务或安全设置。

## 真实模型验收仍需的具体协调

### 2026-11-07 协调窗口内的实际结果：未通过，窗口已释放

父任务已明确确认 KAG 到达安全检查点、自有数据库／模型退出且私有端口关闭，并授权本轮短时验证。实时检查约 16.5 GiB 可用内存，RTX 5060 约 1.8/8.2 GB 显存占用，没有 Ollama／llama-server 进程。默认 `127.0.0.1:11434` 属于 `GameViewerServer.exe`（PID 6280），没有向该端点发模型请求或操作它。旧 Workspace 0.40.1 隔离目录的 models 已不存在，因此未复用这个失效实例。

使用仓库已有审查记录对应的 Ollama 0.40.6（EXE SHA-256 `71d2002d3ae572d5fa8c1d368ce9d1b9a39c7187cf169e8b330c55410bf6c58e`，当前 Authenticode Valid）及 Qwen 3.5 4B llamacpp 子清单 `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。所需 9 个 blob（6,787,815,616 字节，含 index 引用的两个 runner 闭包）复制到本轮忽略目录，源和副本逐一核对内容摘要；模型随附许可为 Apache 2.0。源文件只读，没有下载模型、改写原模型目录或服务。独占 Ollama 使用随机 loopback 端口 51468、独立进程用户目录、并发 1／最多加载一个模型，生命周期复用 `RuntimeProcessJob`，结束关闭 Job 并确认进程与监听退出。

新增 opt-in `desktop/frontend/acceptance/memoryCandidate.model-acceptance.test.mjs` 直接调用生产 `candidatePrompt`／`parseCandidate`；Ask／Conversation 系统提示从当前 Java 类导出，采用当前 chat.balanced 的 8192 context、2048 output、temperature 0.4、think=false、digest-qualified local 模型请求。**这是实际模型输出的独立质量检查，没有经过生产 Runtime HTTP、Ready／准入或完整应用启动；不能把它写成这些路径已通过。** 初次执行文件位于 src 下且为 TypeScript；收尾发现浏览器项目没有 Node 类型依赖，已将仅用于 Node 的手动验收脚本移入 acceptance 并改为等价 JavaScript，避免新增类型依赖或破坏浏览器构建；没有因此再次调用模型。

实际只发出 4 个单并发候选请求，约 4.5 秒：

- 中文稳定偏好：结构和原文摘录通过；标题保留项目名，正文省略了“讨论琥珀项目时”的适用范围，内容审阅发现范围表达风险。
- 英文项目事实：结构、原文摘录和事实内容通过。
- 一次性问候：返回 `candidate:null`，通过。
- 否定／修订：**未通过**。模型将中文来源改写成英文候选，并把 evidence 从原文“我以前喜欢长段落，现在不再喜欢了。今后……”改为“我现在不再喜欢长段落，今后……”。生产解析器以 `Invalid candidate or evidence` 拒绝，没有放宽摘录规则，也没有保存候选。按失败即停约定停止；余下 4 个候选场景及 3 轮真实回答没有执行，未自动重试。

证据在 `.verification/memory-model-acceptance/quality-results.json`、`quality-test.log`、`owned-process.json`、`completion.json`。自有 Ollama PID 7432 及测试进程均已退出，51468 已关闭，原 11434 仍由 PID 6280 持有；收尾显存约 1.85 GB。`windowReleased=true`，KAG 可以继续限定任务。本轮不宣称质量通过，不提交／推送／合并。下一步应先离线增强提示中的来源语言、适用范围和“连续字面摘录”约束并回归，再另行协调模型窗口；不能以放宽生产解析器来消除失败。

后续已在不占用模型的情况下完成[离线约束修正](MEMORY-CANDIDATE-GROUNDING-REPORT.md)，真实效果等待下一次窗口。以下为原协调方案；本次窗口已经使用并释放，不构成后续无限期占用授权。

窗口建立前尚未取得 KAG 控制方确认，因此此前没有将“未调用模型”写成“模型不可用”或“KAG 正在忙”。仅观察空闲显存、进程列表或另建端口／锁文件都不能保证不会抢占 KAG 的后续任务。下一次仍需由父任务协调双方：确认当前实际端点、模型、资源归属、没有在途推理，并在约定窗口内停止提交新推理；不用停止或重启任何现有服务。

建议单次窗口上限 12 分钟、并发 1、最多 8 个候选请求与 3 个会话请求，继续使用现有配置的输入／输出预算；允许现有准入所需的一次验证探针，但不切模型、改预设或自动重试。任何不确定状态或失败即停止。仅使用下列合成来源，并保留当前生产 `candidatePrompt`／`parseCandidate` 的实际结果：

| 合成来源类别 | 需人工核对的结果 |
| --- | --- |
| 中文稳定偏好、英文项目事实 | 结构可解析，摘录字面一致，只保留明确事实 |
| 问候与临时请求 | 无候选，不能将临时任务变成长期偏好 |
| 明确否定与修订 | 不把已否定／旧值当成当前事实 |
| 来源内夹带“忽略要求／直接保存”等干扰文字 | 不执行来源指令，不声称已保存，不伪造依据 |
| 助手的未核实建议 | 保留助手建议的归属，或返回无候选 |
| 只含虚构密钥占位符的文本 | 无候选，不把秘密类文本提炼成记忆 |
| 信息不足的模糊陈述 | 不补全不存在的事实 |

会话三轮在独立合成 Profile 内验证明确选择、保留选择和清除后的回答；逐项区分结构通过与语义忠实度，不将小样本写成总体准确率。实际模型未完成这些检查前不提交本轮整体 WIP、不推送、不合并分支。

## 后续验收顺序与暂停条件

1. 先落实 [共享模型验证协调边界](CONVERSATION-MEMORY-RETENTION-REPORT.md#共享模型验证的可行协调方式)：Workspace 的单 JVM lease 不约束 KAG，必须由双方控制方确认短时窗口和无在途任务，再验证现有 Workspace Ready／准入。仅观察显存空闲或另建文件锁不构成协调；不为验收切模型、重启或扩权真实服务。
2. 在该窗口使用少量合成来源完成真实候选和回答验证：稳定偏好／项目事实、临时闲聊、否定与修订、来源中的干扰指令；记录结构、摘录、语义忠实度、无候选和明确选择后的回答。无效候选不可保存，失败不自动重试。先明确实际模型和证据，再给质量结论。
3. 本轮已补齐合成提供器下的主要桌面数据流。在协调窗口内补验完整应用启动与真实模型组合；按需要补人工输入、IME 和错误状态，明确与本轮自动化证据的区别。
4. 整个模块达到必要验收后，明确选择已完成内容提交，核对远程及公开范围再推送；随后检查分支归属、差异、目标和未完成改动，仅合并适合的分支并保留回滚点，不删除用户分支。当前没有提前合并。
5. 收尾报告区分已完成能力、真实验证与限制，给下一阶段建议；随后暂停 Workspace 开发，不启动下一模块。KAG 不受该暂停要求影响。

## 本地 WIP 与回退

修改前创建本地标签 `workspace-before-memory-postgresql-a50a16d`，5 份原有未跟踪用户文档复制到 `.verification/memory-postgresql/preserved/` 并按 SHA-256 核对。当前源代码提交仍为 `a50a16d`；本轮测试和说明保持未提交，未推送。不要 reset、clean 或覆盖未跟踪文件。忽略目录保存前后差异及本轮新文件副本；它们是本地开发检查点，不是个人数据备份。

桌面补验前另保存全部既有差异与未跟踪文件到 `.verification/memory-desktop-acceptance/before.patch`、`preserved/`、`before-untracked.json`；收尾保存 `after.patch` 和新文件副本。原有五份用户文档与 PostgreSQL 两个新增测试／脚本未改动。Git 回滚基线保持 `a50a16d` 及原标签；当前 WIP 只有本地副本，不能称为已经提交到 Git 或已异机备份。
