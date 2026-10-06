# Personal AI Workspace — V1 Roadmap

## 1. 文档定位与使用方式

本文件是 Personal AI Workspace 长期 V1 产品演进与开发路线图的 source of truth。
它解释已经交付的历史、当前能力、需要持续保留的架构边界，以及未来阶段的目标和顺序。
阅读时先看阶段总览和当前能力快照，再查阶段详情；未来目标不能作为当前功能说明。

完成事实以 Accepted ADR、正式批准记录和 Closing Reports 为依据，不从旧聊天补造实现细节。
[STATUS](../STATUS.md) 负责当前运行与交付状态；[current architecture](../architecture/current-architecture.md)
解释实际架构；[ADR 索引](../ADR/README.md) 指向架构决策；Closing Reports 保留阶段证据、限制和形成时状态。
历史报告中的 OPEN / candidate 或旧能力范围，应结合后续正式批准记录理解，不追改历史报告。
正式 closing authority 属于 Architecture Guard。

**列入路线图只定义预期范围与顺序，不授权实施。** 每个未来阶段或主要跨阶段平台任务，
均须独立完成适用的 architecture review、范围确认和实施授权。本文件本身不批准 K3。
当前事实锚定 T0 正式关闭后的 main：`b59da429711ca23e77e7772c5005b29c8dab12b2`。
后续更新应保持事实、规划和批准来源可区分，不把计划追写成早期成果。

## 2. V1 最终产品目标

V1 的目标是一个以 Windows Desktop 为主入口、local-first 的 AI 工作区。
共享 Java Runtime 负责 AI 与领域编排，本地 Ollama 提供推理；用户通过统一界面使用
Assistant、Conversation、Memory、Knowledge，并在未来获得受控 Web、Vision 和 Finance 能力。
模型与资源管理、明确的进程所有权、可移植的本地数据备份和子系统降级，属于最终产品体验。

演进路线先建立共用 Runtime，再接入 Windows 与浏览器，随后建立显式 Memory、
持久 Conversation 和统一 Workspace，最后引入独立 Knowledge 与确定性词法检索。
后续才计划把检索证据用于带引用的回答，并增加联网、多模态和财务领域编排。

**当前已交付到 K2 + T0。** 本地三种文本操作、持久对话、显式 Memory、Knowledge 管理与词法搜索已存在。
受控 Web、Vision、Finance integration、通用 Active Model 管理、完整 Resource Monitor 和 P1 最终整合仍是未来目标。
最终 V1 不应把所有个人数据混成一个通用记忆库，也不应因一个子系统不可用而让整个工作区失效。

## 3. 永久架构原则

### 3.1 一个 Windows 产品，清晰的组件所有权

主产品持续收敛为一个 Windows Workspace，而非多个独立 AI 平台。核心调用链为：

```text
React → trusted WebView2 → typed allowlisted WPF bridge
      → application-owned RuntimeClient → Java Runtime → local provider / domain stores
```

- React 负责布局、交互与有界展示状态；不持有 Runtime credentials，不直接 HTTP 调用 Runtime，不成为第二个 durable store。
- WPF 负责 native/security/lifecycle：应用和单实例、托盘、WebView 安全、快捷键/UIA、受控剪贴板、凭据、原生文件与确认操作。
- Runtime 负责持久 AI/领域编排：TaskManager、provider/model policy、上下文、Memory/Conversation/Knowledge truth、SQLite 和逻辑备份语义。
- Browser Extension = **Translate-only** companion。Workspace 扩展领域不自动扩展 Browser 权限。
- Local-first / private-by-default；当前模型执行为 LOCAL_ONLY，无隐式 cloud fallback。

### 3.2 领域含义不能混淆

| 概念 | 永久含义 | 不应被解释为 |
| --- | --- | --- |
| 普通 Ask | stateless single-turn | 自动保存历史、自动获取个人上下文 |
| Conversation | durable multi-turn | Memory 或短期 Task 记录 |
| Memory | explicit user-controlled reusable context | 自动长期记忆、聊天历史、导入文档库 |
| Knowledge | imported reference material | 自动进入每次 prompt 的上下文或 Finance truth |
| Finance | authoritative financial truth | 检索文本、模型回答、Vision 候选 |

**Memory ≠ Knowledge ≠ Finance。** Conversation 历史也不会自行变成 Memory。
财务文档可以作为 Knowledge 参考材料，但不能代表真实余额、交易或 PnL；财务真相只属于 Finance。

### 3.3 隐私、派生状态与恢复

私人 Memory、Knowledge、Conversation 和 Finance 数据不得被隐式送到 Web、model 或 tool surfaces。
已批准的显式上下文路径按用户选择和预算使用数据，不授权跨领域自动注入。
凭据、原生路径、source/backup bytes 不交给通用 JS bridge；typed allowlist 与 session authority 继续约束每次调用。

派生索引是可丢弃、可重建的辅助状态，不是领域真相。`knowledge.db` 是 Knowledge authoritative truth；
lexical index 是 derived / disposable / rebuildable。索引失败不能回滚权威文档变更，也不能继续提供过期结果。
Portable logical backup 是产品原则；当前各备份格式的范围独立，不能用“Workspace”名称暗示已备份所有领域。

当前数据库、私有源副本与备份是明文；owner-only OS 权限不等于加密，也不隔离恶意同账户进程或管理员。
删除不等于取证级擦除，digest 不等于签名。未来工作不得扩大这些既有保证。

## 4. 完整阶段总览

| 顺序 | Milestone | 主要目的 | 状态 | 主要依赖 |
| --- | --- | --- | --- | --- |
| 01 | M0 | Shared Runtime Foundation | CLOSED — GO | 本地 Java / Ollama 基础 |
| 02 | M1 | Windows Assistant Entry | CLOSED — GO | M0 |
| 03 | M1.5 | Ask / Summarize / Translate | CLOSED — GO | M0 + M1 |
| 04 | M2 | Browser Convergence | CLOSED — GO | Shared Runtime / 显式配对 |
| 05 | M3 | User-Controlled Memory | CLOSED — GO | Runtime / native security |
| 06 | M4 | User-Controlled Conversation | CLOSED — GO | M3 / TaskManager |
| 07 | M5 | Unified Main Workspace UI | CLOSED — GO | M0–M4 / React-WPF ownership |
| 08 | K0 | Knowledge Architecture Review | APPROVED — GO | M5 / 领域边界 |
| 09 | K1 | Deterministic Knowledge Foundation & Recovery | CLOSED — GO | K0 |
| 10 | K2 | Deterministic Lexical Retrieval | CLOSED — GO | K1 truth / locators |
| — | T0 工程门禁 | Test Suite Consolidation / Slimming | CLOSED — GO | K2 closing；位于 K3 前 |
| 11 | K3 | Grounded Knowledge Answer + Citations | NOT STARTED | K2 / 独立架构审核 |
| 12 | W1 | Controlled Web Access | NOT STARTED | K3 后的工具/策略审核 |
| 13 | V1 | Multimodal / Vision Foundation | NOT STARTED | Model Management Foundation |
| 14 | F0 | Finance Reality Sync | NOT STARTED | authoritative Finance worktree |
| 15 | F1 | Finance Integration Foundation | BLOCKED pending F0 | F0 事实 / 独立集成审核 |
| 16 | F2 | Bill / Transaction Import | 未来；受 Finance Freeze 约束 | F1 / Finance 写入权限；Vision 可提供候选 |
| 17 | F3 | Unified Finance Assistant | 未来；受 Finance Freeze 约束 | F1/F2 / authoritative Finance |
| 18 | F4 | Knowledge + Vision + Web + Finance Orchestration | 未来 | 各领域能力及独立审核 |
| 19 | P1 | V1 Product Consolidation / Final Closing | 未来 | 前述 V1 能力与最终产品门禁 |

T0 位于 K2 关闭之后、路线图扩展与 K3 之前，是工程清理门禁，不占产品 milestone 编号。
M5A–M5E 是 M5 内部阶段；M3/M4 的子阶段同样不提升为正式主线。
K4 当前不是必需的 V1 prerequisite。
Model Management Foundation、Resource Monitor / System Status 是跨阶段平台任务，不新增顶层 milestone。

正式 Vision milestone 名称仍为 **V1 — Multimodal / Vision Foundation**。
它与产品版本 V1 的名称有歧义；尚无 Architecture Guard 的重命名批准，因此本文保留原名。

## 5. 已完成的产品与架构演进

以下“用户得到的能力”描述该阶段交付后的增量，不能把更晚的功能归入更早的历史。
各阶段的 CLOSED / APPROVED 以当前正式状态为准，历史 candidate 报告保留原语境。

### M0 — Shared Runtime Foundation

**阶段定位 / 进入前状态：** 各客户端不应分别拥有模型调用与执行策略；当时还没有可共用的本地 AI 后端。
M0 建立独立 Shared Runtime，把 capability、模型配置、任务控制与出站权限集中到同一执行边界。

**核心工作：** Java 21 + Spring Boot 单应用、独立进程；默认且受校验的 loopback 服务；
私有本地 Bearer token；Provider abstraction 与 Ollama adapter；Model Profile 和 LOCAL_ONLY policy。
首条 Translate vertical slice 使用异步提交、轮询与取消，共用有界 TaskManager。
并发、队列、结果保留、输入/输出预算均有上限；取消、连接/provider/排队/执行超时与受控错误分别表达。
终态不可被迟到结果覆盖；Runtime health/readiness 与认证后的 provider/model readiness 分离。

**用户最终得到的能力：** 本地客户端能够提交真实 Ollama 翻译并查询或取消任务，获得可复用 AI 后端。
此时尚没有最终 Windows Workspace。后续客户端共用模型策略和任务语义，不必各自直连模型。

**核心技术与组件：** Java Runtime、Spring Boot、Model Profile、ProviderPolicy、TaskManager、Ollama。
**数据与持久化：** 私有认证材料持久存在；Task/input/result 只在有界短期内存中，Runtime 重启后 taskId 失效。
没有 durable personal domain、Conversation 或 replay。

**安全与权限边界：** loopback 不代替认证；M0 拒绝带 Origin / cross-site 的 capability 请求。
模型出站再次检查本地策略，禁用 proxy/redirect，无 cloud fallback。
当时 token 持有人共用本地信任域；M2 才引入独立 Browser identity/owner。

**与其他领域的关系 / 明确不做：** 不含 Memory、Conversation、Knowledge、Finance、Agent、工具、备份引擎或服务进程管理。
取消 HTTP 不保证 GPU 立即停止；readiness 不等于推理质量或模型已经 warm。
**阶段结果 / 后续依赖：** CLOSED — GO；M1 接入第一个 Windows client，其后能力继续复用 M0。

证据：[ADR-001](../ADR/ADR-001-local-shared-runtime.md)、[STATUS：M0 历史交付](../STATUS.md#m0-已实现历史-closing-事实)。

### M1 — Windows Assistant Entry

**阶段定位 / 进入前状态：** M0 只有后端能力，尚无日常使用的 Windows 原生入口。
M1 用 .NET / WPF 建立第一个 Assistant，把 Translate 接入用户桌面工作流。

**核心工作：** WPF 输入/结果/取消界面，应用拥有的 RuntimeClient，托盘与当前用户会话单实例，
显式热键触发 UIA selection，保护检查失败时拒绝捕获，有界 helper 与保守 controlled-copy fallback。
首次凭据通过用户主动选择私有 token 文件导入 Windows Credential Manager；日常复用 OS 凭据。

**用户最终得到的能力：** 用户可手动输入文本，或主动触发热键获取支持应用的选区并翻译；
查看结果、取消、明确 Copy result，通过托盘管理 Assistant。AI Runtime 首次具有 Windows 客户端。

**核心技术与组件：** .NET、WPF、RuntimeClient、Windows Credential Manager、UIA、native helpers、single-instance/tray。
M1 为原生 WPF 界面；React / WebView2 Main Workspace foundation 属于后来的 M5A。
当时没有 Runtime/Ollama lifecycle manager；已有 one-click developer launcher 的后续历史不能归入 M1。

**数据与持久化：** OS 保存 Desktop 凭据副本；选区、输入、结果仅在有界内存中，不保存正文历史。
Runtime Task 沿用 M0 短期内存；窗口关闭/退出清理当前内容。
**安全与权限边界：** Desktop 固定调用认证 loopback Runtime，禁用 proxy/redirect，不直接调用 Ollama。
WPF 拥有热键、捕获、原生文件和凭据边界；无后台选区监控、clipboard subscription 或 keyboard hook。
密码/保护属性、焦点或 clipboard ownership 不确定时 fail closed；无法安全捕获可手动输入。

**与其他领域的关系 / 明确不做：** Translate-only Assistant；不含 Ask/Summarize、持久对话、Memory、Knowledge、Finance、OCR、统一 Workspace。
UIA 兼容性和纯文本 clipboard 恢复有明确限制，不宣称通用应用捕获或完整剪贴板还原。
**阶段结果 / 后续依赖：** CLOSED — GO；M1.5 在这个入口中扩展共享 Runtime capability。

证据：[ADR-002](../ADR/ADR-002-windows-client-credential.md)、[STATUS：M1 已实现](../STATUS.md#m1-已实现)。

### M1.5 — Ask / Summarize / Translate

**阶段定位 / 进入前状态：** Windows 已能翻译，但还不能进行普通单轮问答或文本摘要。
M1.5 扩展文本操作，保持 M0/M1 的本地执行与 native ownership。

**核心工作：** Runtime 新增 Ask 与 Summarize capability，各自拥有 prompt/profile；
三个操作共用 provider/policy、TaskManager、预算、轮询、取消、deadline 和受控错误。
WPF 按操作显示输入与结果；selection hotkey 继续走 Translate。

**用户最终得到的能力：** 显式 Translate，文本 Summarize，以及 **Ask = stateless single-turn**。
摘要属于文本操作；问答没有 durable 多轮上下文。模型输出不构成正确性或忠实度保证。

**核心技术与组件：** WPF、RuntimeClient、Runtime capability services、TextTaskSubmission、Model Profiles、TaskManager、Ollama。
**数据与持久化：** 沿用短期 Task retention；不保存问题、答案或会话历史，没有新的个人数据领域。
**安全与权限边界：** 客户端不提交具体 model、system prompt、tools 或任意 messages；Runtime 拥有策略与预算。
执行保持 LOCAL_ONLY、无 cloud fallback；正文不进入 Desktop 日志。

**与其他领域的关系 / 明确不做：** 当时没有 durable Conversation、Memory、Knowledge、RAG 或 Finance integration。
三种操作不自动读取个人上下文，不联网、不调用工具。
**阶段结果 / 后续依赖：** CLOSED — GO；M2 让 Browser 收敛到相同 Runtime，M3/M4 独立引入 durable domain。

证据：[M1.5 Closing Report](../milestones/M1.5-CLOSING-REPORT.md)。

### M2 — Browser Convergence

**阶段定位 / 进入前状态：** Browser Extension 的直接模型接入不应继续成为独立 AI 执行平台。
M2 让 companion 使用 Shared Runtime，保留浏览器自己的 DOM 与翻译交互职责。

```text
Browser Extension → authenticated local Runtime → Translate / Batch Translate
                  → shared TaskManager → Runtime profile / provider → Ollama
```

**核心工作：** 显式批准精确 extension Origin，一次性 pairing proof，独立 Browser credential、
持久 verifier registry、server revoke 和任务 owner 隔离；Windows 增加配对管理入口。
Browser Single/Batch Translate、readiness、任务轮询/取消接入 Runtime；移除 direct Ollama、
模型/system prompt/generation settings、provider parser 和 direct provider retry/fallback ownership。
真实 Chrome Origin-absent privileged GET 的兼容只覆盖认证后的明确 GET allowlist。

**用户最终得到的能力：** 用户配对后可在 Browser 使用页面/选区翻译、Dynamic Content、Restore 等 companion 工作流，
模型执行由 Runtime 统一控制。Browser 不成为新的 Assistant 或个人数据管理端。

**核心技术与组件：** Chrome Extension worker/content scripts、WPF pairing UI、Runtime authentication/registry、TaskManager。
**数据与持久化：** Browser 可信 extension storage 保存独立 credential；Runtime 只保存安全 metadata/verifier。
页面翻译 cache 限页面/content-script 生命周期内存；pairing secret 短期存在，不保存正文历史。

**安全与权限边界：** **Browser Extension = Translate-only**；不能访问 Ask/Summarize、Memory、Conversation、Knowledge、Finance。
任务查询/取消绑定 owner；native 持有人仍共用 native owner，并非逐个 native app 隔离。
credential 不进入网页/DOM/content script；Origin/CORS/Fetch Metadata 不代替 Bearer 认证。
本地 forget 不等于 server revoke；撤销不取消此前已接受任务。

**与其他领域的关系 / 明确不做：** 后续 Workspace 领域能力不自动授权 Browser；无 Browser Memory、对话、知识、Finance 或通用工具权限。
不把限定真实 Chrome 验收扩大为所有浏览器、页面或翻译质量保证。
**阶段结果 / 后续依赖：** CLOSED — GO；此权限边界约束所有后续阶段。

证据：[ADR-003](../ADR/ADR-003-browser-client-security.md)、[M2 Closing Report](../milestones/M2-CLOSING-REPORT.md)。

### M3 — User-Controlled Memory

**阶段定位 / 进入前状态：** 单次 AI 操作无法保存可复用个人上下文，Runtime 还没有持久个人数据领域。
M3 建立用户主动编写、主动保存、主动选择的 Memory，避免自动采集或隐式 retrieval。

**核心工作：** Runtime-owned SQLite Memory records；MANUAL 的 PREFERENCE / PROJECT_NOTE；
显式 CRUD、字面搜索、ACTIVE/ARCHIVED、archive/restore/delete 和 revision 并发保护；WPF 管理入口。
单次 Memory Ask 通过原生预览与确认选择 ACTIVE Memory，Runtime 按 ID + exact revision 解析整个选择。
过期选择整体拒绝，不换成最新版本；已接受任务使用 admission-time immutable snapshot。
Memory-only logical backup v1 支持严格验证与新/空目录恢复，索引从 source records 重建。

**用户最终得到的能力：** 主动保存偏好/项目上下文、管理与搜索记录、备份恢复，并明确将选定上下文附到一次 Ask。
下一次操作不自动继承。显式选择让用户知道本次模型看到什么，并在内容发生变化时重新确认。

**核心技术与组件：** Runtime Memory store、SQLite、derived Memory FTS、WPF management/selector、共享 AI task stack、logical backup。
**数据与持久化：** `memory_items` 是 Memory truth；revision 表达当前记录版本，不是完整 revision history。
源文本跨重启存在；普通 Ask/Memory Ask 仍无持久问答历史；备份包括 ACTIVE 和 ARCHIVED source records。
恢复不合并、不覆盖当前 DB、不自动切换 Runtime。

**安全与权限边界：** native-only；Browser denied；用户正文与查询不写日志。
Memory 是不可信用户上下文，不放进 system message，不等于验证过的事实或 prompt-injection 免疫。
exact revision 与 combined input budget 同时约束选择，不截断或自动丢弃条目。

**与其他领域的关系 / 明确不做：** Memory 不是 chat history、Knowledge documents 或 Finance truth。
不含自动提取/保存/检索、Conversation、RAG、embeddings、cloud sync 或 Finance integration。
**阶段结果 / 后续依赖：** CLOSED — GO；M4 可以在每个 Turn 显式复用 Memory，但不把历史转换为 Memory。

证据：[M3 Closing Report](../milestones/M3-CLOSING-REPORT.md)、[ADR-004](../ADR/ADR-004-user-controlled-memory-storage.md)、
[ADR-005](../ADR/ADR-005-explicit-memory-context.md)、[ADR-006](../ADR/ADR-006-logical-memory-backup-restore.md)。

### M4 — User-Controlled Conversation

**阶段定位 / 进入前状态：** Ask 和 Memory Ask 都是一次性操作，用户无法跨窗口或重启继续已保存的多轮对话。
M4 建立独立 Conversation / Turn / Message 领域：**Ask ≠ Conversation ≠ Memory**。

**核心工作：** durable Conversation 与严格有序 Turns；USER 先持久化，再进入共享 TaskManager。
Runtime 按预算组装 system、当前 Turn 显式 Memory、最近完整 SUCCEEDED Turns、当前 USER。
不拆分旧 Turn、不偷偷总结，失败/取消/超时/PENDING 不作为成功历史上下文；每个 Conversation 最多一个 PENDING execution。
终态持久化与执行结果一致，迟到成功不能覆盖取消/超时；重启将残留 PENDING 标为 FAILED / EXECUTION_INTERRUPTED，不 replay。
生命周期支持创建、手动改名、archive/unarchive/delete；归档阻止新 Turn，不自动取消已接受工作。
有 PENDING 时删除拒绝；关闭界面不等于取消服务器工作，未知提交结果不自动重发。

**用户最终得到的能力：** 查看、重开和继续持久多轮对话，逐 Turn 选择 Memory，观察取消/失败状态，
管理归档与删除，通过 Workspace Backup 恢复后继续历史。Conversation 历史本身不成为 Memory。

**核心技术与组件：** Runtime Conversation store/context/execution、共享 TaskManager/Ollama、SQLite、WPF Conversation UI、Workspace Backup。
内部 M4A 建立领域与持久化，M4B 接入执行与多轮上下文，M4C 加入逻辑 Workspace recovery；不是顶层 milestones。
**数据与持久化：** Memory 与 Conversation 同处既有私有 `memory.db`，Workspace 内部 schema 演进到 v3；领域含义仍独立。
保存有序 USER/ASSISTANT 与终态，历史 Memory 只保存 ID/revision references，不复制选择正文或重建已删除 Memory。
Workspace Backup v1 = Memory + terminal Conversation；不导出 live Task、taskId 或 PENDING。
Memory-only Backup 继续独立兼容；恢复到新/空目标，不 hot-swap、不自动切换。

**安全与权限边界：** Runtime 拥有上下文，客户端不能提供 system/context/model；native-only，Browser 不扩权。
只有 SUCCEEDED 保存真实 ASSISTANT；失败不伪造回答，controlled failure 不暴露原始异常。
**与其他领域的关系 / 明确不做：** 普通 Ask 仍 stateless；Memory 仍每 Turn 显式选择。
不含 Knowledge、Web、Finance、自动 Memory、streaming、edit/regenerate/branching 或 dedicated retry。
**阶段结果 / 后续依赖：** CLOSED — GO；M5 迁移展示入口，保持已建立的 durable semantics。

证据：[current architecture：M4](../architecture/current-architecture.md#m4-final-architecture)、
[M4A](../milestones/M4A-CLOSING-REPORT.md)、[M4B](../milestones/M4B-CLOSING-REPORT.md)、
[M4C](../milestones/M4C-CLOSING-REPORT.md)、[ADR-007](../ADR/ADR-007-logical-workspace-backup-restore.md)。

### M5 — Unified Main Workspace UI

**阶段定位 / 进入前状态：** 已有 Assistant、Memory、Conversation 和原生 maintenance，但入口分散。
M5 将其收敛为一个主要 Windows Workspace，不重建 Runtime 领域或第二份持久数据。

**核心工作：** bundled React / TypeScript 通过 trusted WebView2 呈现主界面，
仅经 typed allowlisted WPF bridge 和 application-owned RuntimeClient 访问 Runtime。
固定可信内容、asset verification、严格导航/资源/权限策略、专用 InPrivate profile、session rotation 与 late-response rejection
构成基础；不开放 generic HTTP/native/path proxy，也不在 React 保存个人正文或凭据。

M5 交付五个主要页面：Assistant、Conversations、Memory、Translate、Settings。
Assistant 提供 Ask / Summarize 与显式 Memory Ask；Conversations 提供 durable history 与逐 Turn 操作；
Memory 提供显式保存、管理和 dirty/stale protection；Translate 是 Desktop 显式文本操作；Settings 展示安全状态和原生维护入口。
Knowledge 页面由后来的 K1 新增，检索由 K2 新增，不能计入 M5 原始范围。

**内部演进：** 下列 stages 都属于 M5，迁移展示不改变既有领域真相。

| 内部阶段 | 已交付增量 | 历史证据 |
| --- | --- | --- |
| M5A | Main Workspace shell、trusted WebView/security、bridge/build foundation；仍通过原生入口使用领域能力 | [M5A Report](../milestones/M5A-CLOSING-REPORT.md) |
| M5B | Assistant / Desktop Single Translate 进入 React；显式 Memory selector 仍归 WPF；未知 outcome 不 replay | [M5B Report](../milestones/M5B-CLOSING-REPORT.md) |
| M5C | Conversations 管理、历史分页、发送/取消和逐 Turn Memory 迁移；重载后读取 durable truth | [M5C Report](../milestones/M5C-CLOSING-REPORT.md) |
| M5D | Memory / Settings 迁移；显式 Save、dirty/stale protection、安全状态和 native maintenance | [M5D Report](../milestones/M5D-CLOSING-REPORT.md) |
| M5E | 默认产品入口、tray/second-instance 收敛、旧 JS 入口退休、portable packaging 与最终验收 | [M5E Report](../milestones/M5E-CLOSING-REPORT.md) |

**用户最终得到的能力：** 一个 Main Workspace 成为正常启动、second-instance activation、launcher 和 tray Open/double-click 的目的地。
缺少凭据也先显示 Workspace，用户从 Settings 明确进入原生凭据流程。
旧 Assistant 保留 Quick Assistant / Quick Translate 与 fallback 职责，不再是默认主产品窗口。
MemoryWindow/ConversationWindow 实现保留供 native fallback，健康 React 不再广告旧领域窗口。

**核心技术与组件：** React、TypeScript、bundled assets、WebView2、WPF、RuntimeClient、Runtime、SQLite、portable release launcher。
WPF 拥有 app lifecycle、single instance、tray、WebView host/security、credentials、native dialogs/file access、
privileged maintenance、Memory selection、hotkey/UIA/controlled clipboard/helpers 和 fallback。
Runtime 拥有 durable Memory/Conversation、TaskManager、上下文、provider policy/model execution、backup semantics 和 SQLite。

**数据与持久化：** UI migration 不改变领域 schema 或备份格式；React 只有有界展示状态，不是 durable domain cache。
portable 包包含 self-contained .NET Desktop、production React、Runtime application JAR、launcher、README 与 manifest/checksums。
数据、Runtime auth/log state 和 WebView profile 分离于包目录；包可移动不等于所有状态随包移动。

**安全与权限边界：** JS 不接收 token/路径/backup bytes；session-bound 方法和 domain IDs 限制读取与 mutation。
M5E 退休三个旧 native-open JS 权限，保留凭据、Browser pairing、Memory backup、Workspace backup 四个 maintenance entries。
K1 后续才另加 Knowledge methods/backup entry。签名、安装器、自动更新并未交付。

**与其他领域的关系 / 明确不做：** Ask stateless、Conversation durable、Memory explicit、Browser Translate-only 保持。
M5 不含 Knowledge/RAG、Web、Vision、Finance、通用模型管理或跨领域 Agent。
Java 21、Ollama/configured model、WebView2 仍是外部前提；现有 launcher 不建立 exit-time Runtime/Ollama supervisor ownership。
Unsigned portable folder 与 plaintext DB/backup 是接受的限制，不是未完成的 M5。
**阶段结果 / 后续依赖：** CLOSED — GO；M5A–M5E 全部 CLOSED — GO；独立 Final Closing 已批准。
K0/K1 在这个主界面与 ownership 基础上扩展独立 Knowledge。

证据：[ADR-008](../ADR/ADR-008-hybrid-main-workspace-ownership.md)、[ADR-009](../ADR/ADR-009-webview2-trusted-content-bridge.md)、
[ADR-010](../ADR/ADR-010-frontend-build-desktop-distribution.md)、[STATUS：M5 正式批准](../STATUS.md#current-m5-approved-final-closing)。

### K0 — Knowledge Architecture Review

**阶段定位 / 进入前状态：** 产品已有 Memory 与 Conversation，但导入参考材料的独立领域与恢复边界尚未落实。
K0 是架构决策阶段，避免把文档混进 Memory 或提前引入 embedding-first 基础设施。

**核心工作：** 确认 Knowledge 为独立 durable domain；用户显式导入；先建立 deterministic representation 与 recovery，
再推进 lexical retrieval，之后才规划 grounded answers。未来索引必须 derived/rebuildable。
Memory ≠ Knowledge ≠ Finance；财务文档只作为参考，不成为 Finance truth。
不自动把 Knowledge 注入 Ask/Conversation，也不以 vector DB / embeddings 作为起点。

**用户最终得到的能力：** 主要产出是获批准的边界与后续实施顺序，几乎没有直接新增用户功能。
**核心技术与组件：** Runtime / WPF / React ownership、独立 Knowledge storage、未来 parser/locator/index 边界。
**数据与持久化：** K0 定义未来 truth ownership，不声称已经创建 Knowledge DB、导入文档或检索数据。
**安全与权限边界：** 显式 native import，Browser Translate-only，私有材料不隐式进入模型/外部工具；Finance Freeze 保持。

**与其他领域的关系 / 明确不做：** 不扩展 Memory 类型为文档库，不建通用 personal-item/vector store，
不实现导入/search/RAG、PDF/DOCX/OCR 或 Finance contract。
**阶段结果 / 后续依赖：** APPROVED — GO；K1 承接存储/导入/恢复，K2 承接确定性词法检索。

证据：[ADR-011](../ADR/ADR-011-knowledge-domain-storage-ingestion-recovery.md) 与
[K1 Report](../milestones/K1-CLOSING-REPORT.md) 明确记录既有 Architecture Guard K0 approval 的来源。
仓库没有独立 K0 Closing Report；这里不补造新的批准 artifact 或历史执行证据。

### K1 — Deterministic Knowledge Foundation & Recovery

**阶段定位 / 进入前状态：** K0 已批准领域边界，但用户还不能持久管理自己的参考文档。
K1 首次交付不依赖 AI 模型的本地 Knowledge foundation。

**核心工作：** Windows native picker 显式导入 TXT / Markdown，实际 opened handle 验证并有界流式上传；
strict UTF-8，私有源副本，稳定 Document identity，不可变 READY revisions，source/representation digests 与版本化表示。
TXT 行范围、Markdown section/heading 构成 typed locators；只确定性规范 BOM/newlines，不执行 HTML、链接或图片。
单 worker + bounded queue、配额和 durable request identity；processing 为
PENDING / PARSING / READY / FAILED / CANCELLED / INTERRUPTED，独立于 ACTIVE / ARCHIVED lifecycle。

启动 reconciliation 将未完成 import 转 INTERRUPTED，不自动 replay；失败更新保留旧 READY pointer。
source rename 与 SQLite commit 不宣称是同一事务，以 journal/identity 对账，只清理证明拥有的对象。
archive/restore 与 physical delete 带 metadata version 检查；delete 使用 journal，锁定源时返回可控 incomplete，不能假报成功。
React Knowledge 页面提供 metadata list/detail、revision selector、bounded plain-text preview 和原生备份入口。

**用户最终得到的能力：** 导入、查看、管理、更新、归档/恢复、明确删除本地 Knowledge 文档，
在原文件不可用时仍使用私有副本，并通过独立备份恢复。**K1 阶段不能搜索文档。**

**核心技术与组件：** Runtime Knowledge store/parser/ingestion/reconciliation、SQLite、private sources、native streaming、typed session bridge、React Knowledge page。
**数据与持久化：** `knowledge/knowledge.db` schema v1 是 authoritative truth，持有 documents/revisions、
exact normalized text、typed locators 与最小 journals；`knowledge/sources/` 保存私有原始字节。
sourceRevision、metadataVersion、parser/normalization version 语义独立；改变源才产生新 source revision。
独立 Knowledge Backup v1 包含文档、retained READY revisions、源字节、normalized text、locators、digests 与 current pointers。
恢复仅到新/空 Workspace data target，发布 inactive Knowledge；不 merge/hot-swap/自动切换。
Workspace Backup v1 仍只含 Memory + Conversation；Knowledge backup 不恢复它们或 credentials。

**安全与权限边界：** native-only，Browser denied；React 不收 source paths、upload/backup bytes、credentials。
session rotation 清除 ID authority，旧响应不能授权新 session；unknown upload outcome 只查状态，不自动重传。
预览受 locator/range/bridge budget 约束，源与 DB 受 owner-only ACL，而非加密。

**与其他领域的关系 / 明确不做：** 不修改 Memory/Conversation truth 或备份格式。
无 lexical/semantic search、RAG、模型 Knowledge answers、自动 prompt injection、PDF/DOCX/OCR、Web 或 Finance integration。
**阶段结果 / 后续依赖：** CLOSED — GO；K2 使用 K1 immutable representation/locators 建 derived index。

证据：[ADR-011](../ADR/ADR-011-knowledge-domain-storage-ingestion-recovery.md)、
[K1 Closing Report](../milestones/K1-CLOSING-REPORT.md)、[STATUS：K1 正式批准](../STATUS.md#current-k1-approved-final-closing)。

### K2 — Deterministic Lexical Retrieval

**阶段定位 / 进入前状态：** 文档可管理与预览，但无法通过内容关键词定位证据。
K2 首次在 Knowledge 页面交付显式词法搜索，仍不调用模型生成回答。

```text
authoritative knowledge/knowledge.db → versioned chunks / tokens
                                    → derived knowledge/index/lexical.db → explicit search / preview
```

**核心工作：** deterministic chunking 保留 K1 locator/section 与 exact UTF-16 offsets，
大范围按 newline、whitespace、Unicode code-point boundary 有界拆分，不切 surrogate pair。
deterministic analyzer 对派生 tokens 做 Unicode NFKC + Locale.ROOT lowercase；
Latin/general letter-digit runs 产生 word tokens，CJK 产生 unigram 与 adjacent bigram，不改变 normalized truth。
应用生成安全 quoted token identifiers 与 AND MATCH syntax；用户输入不直接成为 FTS syntax。
查询有长度/token 数上限，全部 tokens 必须在单 chunk 的 title/heading/body 中满足，不静默截断。

SQLite FTS5 负责 postings / BM25；title / heading / body 权重固定为 4.0 / 2.0 / 1.0。
排序按 ascending BM25、canonical documentId、numeric sourceRevision、chunkOrdinal 确定 tie-break。
检索 corpus 只包含 ACTIVE/current READY，retained old 或失败 revision 不替换当前有效版本。

corpus fingerprint 覆盖版本/SQLite identity 与权威 corpus；查询前后核对 freshness，变化即丢弃结果，stale fail closed。
mutation invalidates/schedules rebuild；专用单 worker 与一个 coalesced rerun，避免无限排队或占用 AI TaskManager。
候选索引经过 schema/count/integrity/budget/fingerprint 校验后 atomic publication。
缺失、损坏、版本/指纹不符的派生 index 可重建，权威 Knowledge 不被回滚；未知 ownership 的 crash orphan 不推定可删除。

**用户最终得到的能力：** 在 Knowledge 页面显式输入关键词，查看 bounded snippets/highlights、行/section locator，
打开 exact revision/range preview，查看 index status 并请求 rebuild。搜索不要求 Ollama 可用。
这不是 semantic/vector/fuzzy search，也不把拼音输入自动 transliterate 成汉字查询。

**核心技术与组件：** Knowledge immutable representation、application-owned chunker/analyzer/ranking、SQLite FTS5/BM25、derived index worker、React search/typed bridge。
**数据与持久化：** `knowledge.db` schema v1 与 Knowledge Backup v1 不变；`knowledge/index/lexical.db` 独立 derived、明文、可丢弃。
Backup 不含 index；恢复 truth 后重启 Runtime 重建。搜索 query/hits 为 React ephemeral state，不进入 URL/storage/history/logs。
**安全与权限边界：** native-only POST search；Browser 在 body handling 前 denied，CORS/capabilities 不扩权。
JS 只接收安全 metadata、plain snippets/highlight ranges 与 locators，不接收 digest/fingerprint/SQL/score/path/source bytes。
session rotation 清空查询与 ID authority；late response 不授权替代 session，结果不自动进入 AI/Memory。

**与其他领域的关系 / 明确不做：** 与 Memory 搜索是不同领域与语义；无 Ask/Conversation prompt assembly、
RAG、AI answers、model citations、embeddings、Web、PDF/DOCX/OCR 或 Finance。
**阶段结果 / 后续依赖：** CLOSED — GO，独立 Closing Review 已批准；K3 未来才把这些证据用于 grounded answer + citations。

证据：[ADR-012](../ADR/ADR-012-deterministic-lexical-retrieval.md)、[K2 Closing Report](../milestones/K2-CLOSING-REPORT.md)。

## 6. T0 — Test Suite Consolidation / Slimming（工程历史）

**阶段定位 / 进入前状态：** K2 之后积累了重复的跨层测试、milestone acceptance projects 与 smoke scripts。
T0 清理验证维护成本，不是第 11 个产品 milestone，也没有引入新用户功能。

**核心工作与技术归属：** Java 保留 Runtime/domain/security/recovery invariants；Desktop 保留 bridge/native/session boundaries；
Frontend 保留有长期价值的 React 行为。12 个历史 Windows acceptance projects 收敛为一个 WorkspaceSanity。
routine flow 覆盖真实 WPF + WebView2 + React + Runtime、Memory、Knowledge 与 physical Pinyin；不要求真实 Ollama inference。
历史 certification 保存在 Git / Closing Reports；完整 package/launcher certification 属于 release/P1 gate。

**用户结果 / 数据与权限边界：** 产品行为、API/schema/备份、词法语义和领域 authority 未变。
测试使用隔离自有 state，清理只针对已知自有进程/路径；production-source-path 仅有必要的 friend-assembly metadata 调整。
Finance Freeze 和 Browser Translate-only 保持，未启动 K3，不新增领域持久数据。
**明确不做：** 不重跑所有历史认证，不以 test count 或 coverage points 代表产品功能或独立执行次数。

**历史执行证据：** T0 工程记录记载 Java 132 / Desktop 194 / Frontend 38 PASS、一次真实 WorkspaceSanity PASS，
tests/acceptance/scripts 合计净减少约 7,136 source LOC。它们是此前实际执行的证据；路线图编辑不重跑任何测试/构建。
**阶段结果 / 后续依赖：** CLOSED — GO；工程清理门禁已关闭，未来 K3 仍须独立 Architecture / Planning。

证据：[T0 Test Suite Slimming](../engineering/T0-TEST-SUITE-SLIMMING.md)，含独立正式 Closing Review。

## 7. 当前产品能力快照（K2 + T0 基线）

### Desktop Product

主产品是统一 Main Workspace。当前页面为 Assistant、Conversations、Memory、Knowledge、Translate、Settings；
其中五个基础页面来自 M5，Knowledge 来自 K1，Knowledge search 来自 K2。
正常启动/second instance/tray/launcher 进入主窗；Quick Assistant、hotkey/UIA 与 native fallback 保留。
WPF 管理凭据、文件、maintenance 与安全边界；React 不直连 Runtime、不保存第二份领域 truth。

### AI 与 Durable Conversations

本地 Ollama 支持 Ask、Summarize、Translate；普通 Ask stateless，显式 Memory Ask 只用于当前操作。
Conversation 有 durable ordered Turns、bounded multi-turn context、逐 Turn Memory、取消/失败/重启状态与生命周期管理。
没有 automatic replay，历史不会自动提取成 Memory；模型回答正确性仍需要用户判断。

### Explicit Memory

用户手动保存和管理 PREFERENCE / PROJECT_NOTE，字面搜索、edit/archive/restore/delete、revision conflict protection 已存在。
附加上下文须主动 preview/select；Runtime 校验 exact revision，每次 Ask/Turn 默认无继承选择。

### Knowledge

支持 native TXT/Markdown strict UTF-8 import、private sources、immutable revisions、deterministic representation/locators、
bounded ingestion/restart reconciliation、ACTIVE/ARCHIVED、physical delete、plain-text bounded preview 与独立 backup。
K2 在 ACTIVE/current READY corpus 上提供 deterministic lexical search、snippet/highlight/locator preview、freshness 和 rebuild。
不依赖模型，不生成 Knowledge answer/citations，不提供 semantic search。

### Browser Companion

用户明确配对后，Extension 使用 Shared Runtime 的 Translate/Batch Translate；保留 Browser DOM/Dynamic/Selection/Restore UX。
**Translate-only**；无法访问 personal domains，也不直连模型。

### Backup / Recovery

| 当前已交付格式 | 包含的 truth | 不包含 | 恢复方式 |
| --- | --- | --- | --- |
| Memory Backup v1 | ACTIVE/ARCHIVED Memory source records | Conversation、Knowledge、Tasks、credentials、derived indexes | 新/空目标，重建 Memory 派生状态；不合并或自动切换 |
| Workspace Backup v1 | Memory + terminal Conversation，含历史选择 references | PENDING/live Tasks/taskId、Knowledge、settings/model/auth/Browser state | 新/空目标，用户明确重启到恢复目录 |
| Knowledge Backup v1 | Documents/retained READY revisions、private source bytes、normalized text、locators/digests/pointers | lexical index、temporary jobs/journals、Memory/Conversation、credentials | 新/空 Workspace data target，验证发布 inactive Knowledge；重启重建 index |

这些格式是便携逻辑数据备份，并非整个应用/凭据/配置镜像。备份明文，无密码加密或云同步；恢复不覆盖 active domain、不 hot-swap。

### 当前运行与发布限制

已有 unsigned portable Windows bundle 路径与 launcher；Java 21、Ollama/configured model、WebView2 是外部前提。
Runtime/Ollama 当前不由 Desktop exit-time supervisor 管理；固定模型配置/profile 不等于通用 Active Model 管理。
已有安全状态/readiness 与 Knowledge index status，不等于完整 Resource Monitor / 最终 System Status。
历史打包验收只代表对应 candidate，不能由源码更新推定某个旧包包含后续 K1/K2。

### 尚未实现

- K3 grounded Knowledge answers / model citations；semantic/vector retrieval 也不是当前能力。
- W1 controlled Web、Vision、Finance integration、F4 unified cross-domain orchestration。
- 通用 Model Management Foundation、完整 Resource Monitor / System Status、最终 startup/process ownership/degraded UX。
- P1 final V1 product consolidation、最终 clean-install/migration/Windows closing gates。

## 8. 未来领域阶段（规划，不构成实施授权）

本节全部为 planned / target / future architecture，须阶段独立审核。
明确的长期边界是设计约束，不代表相关 API/schema/UI 已落地。

### K3 — Grounded Knowledge Answer + Citations

**阶段定位 / 进入前状态：** NOT STARTED。K2 可以找到并预览证据，但不会生成回答或模型引用。
K3 计划把 deterministic retrieval 转为可核对来源的本地模型辅助回答。

**计划核心工作：** 用户问题 → bounded Knowledge retrieval → selected evidence → local model
→ grounded answer → citations back to actual source / revision / locator。
检索继续作为确定性 evidence source；回答应区分 source evidence 与 model synthesis，引用须可回到实际来源。
不足的证据不能被表述成已验证事实。最终 evidence selection、prompt、citation DTO/renderer/API 由 K3 review 决定。

**目标用户能力：** 未来能够基于已导入材料提问，看到有来源支撑的回答并核对原文。
**核心技术与组件：** 计划复用 K2 retrieval、K1 locators、Runtime orchestration、local model、typed WPF bridge 与 Workspace UI。
**数据与持久化：** Knowledge truth 继续属于 `knowledge.db`，index 仍 derived；是否保存回答/引用及如何与 Conversation 关联待 review。
不据此预定新 schema 或让模型回答回写 source truth。

**安全与权限边界：** 计划只在批准的本地 evidence budget 下使用材料；无 hidden automatic Memory mutation、Finance truth creation 或 unrestricted Web。
**与其他领域的关系：** 基于 Knowledge 回答与普通 Ask/Conversation 语义须明确区分；不能自动把每次 Ask 变成 Knowledge 查询。
**明确不做：** 不预定模型名、embedding model、vector DB、最终 prompt/API/schema/citation DTO；无 semantic/vector prerequisite，除非另行审核。
**阶段结果 / 依赖：** NOT STARTED；依赖 K2，下一步只可进入独立 K3 Architecture / Planning，随后才可能授权实施。

### W1 — Controlled Web Access

**阶段定位 / 进入前状态：** NOT STARTED。当前 AI 不联网获取实时公共信息。
W1 计划通过 Runtime-controlled tools 获得有界 public evidence，默认让用户确认访问。

**计划核心工作 / 架构链：**

```text
Local Model → structured tool call → Runtime Policy → controlled Web Search / Fetch
            → Public Internet → bounded evidence → Local Model
```

计划 policy 选项：Disabled、**Ask before accessing web（默认）**、Allow automatically for public-information queries。
模型不能任意访问 Internet；Runtime 对目标与返回证据执行权限和预算检查。

**目标用户能力：** 未来可以批准 AI 查询当前公共信息，获得有范围限制的 evidence。
**核心技术与组件：** 计划使用 Runtime Policy、reviewed Search/Fetch tools、bounded transport 和 evidence handling；不预定服务商/API。
**数据与持久化：** Web evidence 不自动成为 Memory、Knowledge 或 Finance；cache/history 保存范围须在 W1 明确，本文不定义新持久格式。

**安全与权限边界：** 规划 HTTPS、SSRF defense、loopback/private/LAN denial、metadata endpoint denial、DNS validation、
redirect limit、timeout、body limit、MIME validation、bounded concurrency。
默认不使用 cookies、不复用 browser login session、不转发 Workspace credentials、不暴露 Runtime token。
私人 Memory/Knowledge/Conversation/Finance 数据不得自动发送到 Web，包括不得隐式拼入 public query。

**与其他领域的关系：** 与 K3 本地 Knowledge grounding 独立；将 public evidence 与 private source 组合需要明确批准的 policy。
Browser Extension 继续 Translate-only，Workspace startup 不启动浏览器。
**明确不做：** 无 unrestricted Internet、browser automation/session takeover 或自动私有数据外传；本路线图不授权工具实施。
**阶段结果 / 依赖：** NOT STARTED；正式顺序在 K3 后，随后规划 Model Management Foundation，再进入 Vision。

### V1 — Multimodal / Vision Foundation

**阶段定位 / 进入前状态：** NOT STARTED。当前只处理文本与 TXT/Markdown Knowledge，不能理解图像。
这里的 V1 指保留原名的 Vision milestone，不代表整个产品版本已经完成。

**计划核心工作：** image、screenshot、document image、chart、receipt understanding 与 OCR-like structured extraction。
能力选择、native input ownership、预算与失败表达须独立 review，不预定最终 OCR engine/model/API。

**目标用户能力：** 未来可以明确提供图像，获得解释或结构化提取候选；不会据此宣称识别内容绝对正确。
**核心技术与组件：** 计划依赖 Runtime multimodal execution、已审核的模型 capability/profile、Windows 输入边界与 Workspace 展示。
**数据与持久化：** 图像、提取结果与候选的保留策略待审核；Vision output 是 candidate information，不是 authoritative Finance truth。
不得因 receipt 被识别就写入交易。

**安全与权限边界：** 输入须显式，不能引入后台 screen capture 或自动送图到 Web/cloud；source/候选与 commit 权限分离。
**与其他领域的关系：** future receipt path 为：

```text
Vision → structured candidate → later F2 validation → mapping → dedup
       → explicit user confirmation → Finance commit
```

只有 Finance commit 才创建 authoritative transaction truth；文档图像理解也不等于已批准 Knowledge PDF/DOCX ingestion。
**明确不做：** Vision 不直接记账、不绕过 Finance validation、不预定 OCR/model/API，也不授权 Knowledge 新格式。
**阶段结果 / 依赖：** NOT STARTED；计划在 Model Management Foundation 后，receipt commit 另依赖 F0/F1/F2 授权。

### F0 — Finance Reality Sync

**阶段定位 / 进入前状态：** NOT STARTED；**Finance Integration — BLOCKED**。
现有 Workspace 仓库不能替代 authoritative Finance implementation 的真实状态。
F0 的目的不是构建集成，而是在任何契约假设之前建立核验过的 Finance reality。

**计划核心工作：** 从 authoritative Finance worktree 核对真实 repository/branch/HEAD 与数据模型、schemas、auth、API、tools、
validation、transaction ownership、write semantics、当前 AI integration、backup/recovery reality 和 integration constraints。
输出事实、缺口和可审查的集成限制，再交由独立架构审核；不能用旧聊天或 Workspace 中的参考文档推定接口。

**目标用户能力：** 预计很少或没有直接新增产品功能，主要产出是 verified integration truth。
**核心技术与组件：** authoritative Finance repository/worktree 及其实际组件；具体协议与实现此时不预定。
**数据与持久化：** 不创建 Workspace 财务副本、不改 Finance schema、不迁移 transaction truth；核验真实 owner。
**安全与权限边界：** 核验实际 authentication、read/write/commit authority 与 recovery；不能把 review 视为开放写权限。

**与其他领域的关系：** Finance 是独立 durable domain；Knowledge 财务文档、Memory 或 Vision 候选不能替代 Finance state。
**明确不做：** F0 前 ZERO integration contract assumptions；不发明 FinanceAdapter、generic Connector、Finance tool schema、
auth contract、transaction API 或 future write API。
**阶段结果 / 依赖：** NOT STARTED；Finance Freeze 保持。F0 核验完成并经适用 review 后，才可能为 F1 定义事实基础。

### F1 — Finance Integration Foundation

**阶段定位 / 进入前状态：** BLOCKED pending F0。当前 Workspace 没有获核验和授权的 Finance integration boundary。
计划连接 authoritative Finance truth，同时不在 Knowledge/Memory 内复制或重新定义财务领域。

**计划核心工作：** F0 之后按真实契约定义并审核 Workspace → Finance boundary；访问和失败语义、read/write authority 须明确。
这里仅记录原则，不选择实现 adapter、协议、tool schema 或认证方案。
**目标用户能力：** 未来在统一 Workspace 中使用经过批准的 Finance 接入能力；可提供的具体操作须以 F0 和 F1 review 为准。
**核心技术与组件：** Workspace Runtime/WPF/UI 与 authoritative Finance 的 reviewed boundary；实际 Finance components 待核验。
**数据与持久化：** Finance 保持独立 durable domain 与真相 owner；Workspace 不把财务状态塞入 Knowledge/Memory。

**安全与权限边界：** read/write 权限受控，模型不得直接写 financial records；credential/security boundary 由真实 Finance auth 决定。
**与其他领域的关系：** Knowledge 可提供参考，不替代 balances/transactions/PnL；Memory 可表达偏好，不是财务账本。
**明确不做：** 不在 F0 前定义 API/schema/auth；不直接绕过 Finance owner 改库，不预授权财务 mutation。
**阶段结果 / 依赖：** BLOCKED pending F0；F2/F3 依赖通过独立 review 的 F1 foundation。

### F2 — Bill / Transaction Import

**阶段定位 / 进入前状态：** 未来阶段，受 Finance Freeze 约束；F1 即使建立连接，也不等于票据可安全提交。
F2 计划把候选提取与 authoritative transaction commit 分开。

**计划核心工作 / 用户路径：** user bill/receipt/source → extraction candidate → validation → mapping → dedup
→ explicit confirmation → authoritative Finance commit。
规划 validation、confirmation、idempotency/dedup 和 uncertain-result handling；结果未知时不能自动重复提交或假报失败未写入。
**目标用户能力：** 未来可检查和确认票据/交易导入，而不是由模型静默记账。
**核心技术与组件：** approved candidate extraction（可含 Vision）、Finance validation/mapping/dedup、native/Workspace confirmation、reviewed commit boundary。
**数据与持久化：** 候选不是 transaction truth；只有 authoritative Finance commit 创建正式记录；候选保留格式待 review。

**安全与权限边界：** 明确用户确认和写权限，no silent model-driven financial mutation；commit 后状态依据 Finance，而非模型判断。
**与其他领域的关系：** Vision 可以提供 receipt candidate；Knowledge 文档可以是参考 source，但二者不能越过 Finance validation。
**明确不做：** 不预定 transaction API、dedup key 或写入 schema，不把提取成功当成记账成功。
**阶段结果 / 依赖：** 未来；依赖 F0/F1，使用图像源时另依赖 Vision，实施需单独审核与授权。

### F3 — Unified Finance Assistant

**阶段定位 / 进入前状态：** 未来阶段，受 Finance Freeze 约束；文本模型与 Knowledge retrieval 不能准确代表当前财务状态。
F3 计划提供由 authoritative Finance 支撑的自然语言财务问答及经批准的操作。

**计划核心工作：** 以真实 Finance data/tooling 回答问题，把 model synthesis/presentation 与 authoritative result 区分；
任何 action 都遵循已审核的权限、验证、确认与 uncertain-outcome 规则。
**目标用户能力：** 未来在统一界面询问实际余额、支出或 portfolio state，并按批准范围执行财务动作。
**核心技术与组件：** local model orchestration/presentation、reviewed Finance access、Workspace UI；具体 tools/API 待 F0/F1/F3。
**数据与持久化：** authoritative financial truth 仍只属于 Finance，模型输出不成为新账本，回答/history 保留策略待 review。

**安全与权限边界：** 模型没有直接财务写权限；涉及 mutation 的交互不能退化成纯文本承诺。
**与其他领域的关系：** Knowledge 回答“上传的银行文档说了什么”；Finance 才回答“我的实际当前余额/支出/持仓是什么”。
Memory 中的偏好可辅助呈现，但不能改变实际 financial state。
**明确不做：** 不用近似 Knowledge search 回答实际账务，不把 LLM output 或过时文档视作 authoritative result。
**阶段结果 / 依赖：** 未来；依赖 F0/F1/F2 的已核验边界与适用授权，之后才规划跨域 F4。

### F4 — Knowledge + Vision + Web + Finance Orchestration

**阶段定位 / 进入前状态：** 未来高级阶段；独立领域能力存在并不意味着可以无限制组合或自治行动。
F4 计划让一个用户请求有意组合多个已授权领域，同时保留各自的 authority。

**计划核心工作：** 明确范围与权限 → 选择所需 Memory/Knowledge/Vision/Web/Finance → bounded evidence/tool orchestration
→ 区分候选、参考、公共信息与财务真相 → 用户可核对的结果/确认。
例如未来可结合用户主动选择的背景、导入参考、公开信息和真实财务状态解释一个问题；这只是场景方向，不是已有 agent behavior。
**目标用户能力：** 未来减少在领域页面间手工搬运上下文，并能看清每份信息的来源和权限。
**核心技术与组件：** Runtime controlled orchestration、各领域 reviewed boundaries、typed Workspace presentation；不预定新 Agent framework。
**数据与持久化：** 每个领域保留自己的 truth；Memory、Knowledge、Finance 不折叠为 generic vector/memory store。
跨域 provenance/operation history 是否持久化须专门审核。

**安全与权限边界：** 每个步骤保留 data-use/egress/write policy；组合本地私有数据不自动授权 Web 外传或 Finance commit。
**与其他领域的关系：** Memory 是显式上下文、Knowledge 是参考、Vision 是候选、Web 是公共证据、Finance 是财务真相。
**明确不做：** 无 unrestricted autonomous Agent、通用任意 tool 权限或“所有数据都是 Memory”的设计。
**阶段结果 / 依赖：** 未来；依赖 K3/W1/Vision/F1–F3 及显式 Memory 边界，再由 P1 完成产品整合。

## 9. Model Management Foundation（跨阶段平台任务）

**阶段定位 / 进入前状态：** 未来、NOT STARTED。当前 Runtime 已有固定 Model Profiles 与 provider readiness，
尚无通用用户 Active Model 选择、安全切换与统一 loading lifecycle。这不是新增编号的产品 milestone。

计划顺序：**K3 → W1 → Model Management Foundation → Vision**。
目标是 **ONE Active Model**；默认不同时加载多个模型，Settings 未来允许选择当前 Active Model。
profile 计划描述 provider、model id、capabilities、text support、vision support、context limit、
installed state、loading state、startup behavior；不把具体模型或厂商名称写成永久要求。

**计划核心工作 / 用户结果：** 显示 Loading、Ready、Busy、Unloaded、Failed；
明确模型是否支持请求能力，并安全切换。未来切换流程为：

```text
select model → verify installed → verify capability → load/warm → health check → activate
```

只有新模型验证成功才更新 Active Model；失败保留旧 active configuration/model，不先破坏旧模型再尝试新模型。
startup 只 warm 用户选中的 Active Model。模型 presence/readiness 与加载状态/推理质量须区分。

**核心组件 / 数据与持久化：** 计划由 Runtime profile/provider/lifecycle policy 和 Settings 协作；
Active Model 配置与持久格式待 review，不放入 Memory/Knowledge truth，也不暗改现有 backup contract。
**安全与权限边界：** React 仍不直连 Ollama、不获得进程或任意 provider URL authority；资源与取消语义须保留。
**与其他领域的关系：** 支持 text / future Vision capability checks；模型不可用不能阻止纯数据管理与 lexical search。
**明确不做 / 阶段结果与依赖：** 未来、NOT STARTED；不默认 multi-model startup、不预定 download/model catalog/自动安装行为；
独立审核后实施，P1 负责最终模型设置与切换体验产品化。

## 10. Resource Monitor / System Status（跨阶段平台任务）

**阶段定位 / 进入前状态：** 未来、NOT STARTED（完整平台能力）。本地 AI 的 GPU/内存负载需要低干扰的状态可见性；
已有 readiness/index status 不能代表完整监控。可在 P1 前逐步引入，P1 负责最终产品化，不新增 milestone 编号。

**计划核心工作与目标用户能力：** 让用户区分资源压力、服务失效、模型加载和队列繁忙，
知道哪些功能仍可使用；避免把每个 warning 变成整个产品不可用。

| 范围 | Planned V1 scope |
| --- | --- |
| Hardware | CPU utilization；RAM used/total/%；GPU utilization；VRAM used/total/%；GPU temperature；disk free |
| AI Runtime | Active Model；Model State；TTFT / first-token latency；tokens/sec；context usage；running tasks；queued tasks |
| Services | Runtime；Ollama；Knowledge Index；Vision；Web；Finance，各自状态 |
| Storage | Workspace；Knowledge；lexical index；Finance；bounded logs / auxiliary data |
| Warnings | RAM/VRAM pressure；GPU temp；low disk；context near max；index stale/rebuilding；model unavailable；queue busy |

System Status 目标为低噪声概览，详细页再展示 TTFT、吞吐、context、queue/storage；
最终 telemetry 来源、可用性及指标口径须按平台 review 确认，不伪造硬件不支持的读数。
**核心技术与组件：** 计划使用 OS/native resource boundary、Runtime/task/model status、domain-specific service status 与 React 展示。
**数据与持久化：** storage 统计不是新的 domain truth；history/log retention 是否存在与上限待审核，不能把正文纳入 telemetry。
**安全与权限边界：** native 采集范围明确，React 只消费 typed bounded status，不获得任意系统探测能力或 credentials。
**与其他领域的关系：** 配合 Active Model 与 degraded mode，Knowledge rebuilding、Finance unavailable 等保持独立表达。
**明确不做 / 阶段结果与依赖：** 未来、NOT STARTED；V1 排除 network traffic monitor、GPU power、fan、clock、voltage 和 general-purpose hardware telemetry dashboard。
未来确有需要再审核 Advanced Diagnostics；P1 完成最终 UX，不假定上述能力现在存在。

## 11. Startup Experience 与 Process Ownership（未来最终 V1 体验）

这是一条 **FUTURE FINAL V1 EXPERIENCE**，不是当前 launcher 行为的逐项描述。
目标是 UI 尽早可见，模型 warmup 异步进行，允许适用的部分功能先可用。

```text
Personal AI Workspace.exe → Single Instance Guard → Main Window appears early
→ Runtime health/start → Ollama health → reuse existing Ollama if already running
→ start Ollama if required → load configured Active Model → warm only Active Model
→ capability check → subsystem status → READY
```

计划 startup 不等待模型完全 warm 才显示主窗；不自动启动浏览器、不默认加载多个模型、
不每次启动重建 Knowledge index、不启动无关程序。READY 与子系统可用性须区分，warmup/failure 不锁死可用的数据页。

已有 Ollama 应复用，Workspace exit 不终止 externally owned Ollama。
由 Workspace 启动的 Runtime/Ollama 需要显式记录与审核 lifecycle/process ownership；
不能仅凭端口监听推定拥有进程，也不能 kill unknown processes。
当前 launcher 的 external-service mode 与现有退出语义保持历史事实；未来 supervisor/ownership 变更需独立授权。
Model Management 提供 selected Active Model 语义，P1 负责 one-click startup 与 shutdown 最终体验。

## 12. Degraded Mode（永久产品原则与未来 UX 目标）

领域与服务应各自表达可用性，避免单点部分失败扩大为整体失效。
当前 Knowledge lexical search 不依赖 Ollama；下面的完整用户体验是未来产品整合目标，不宣称所有 UI 降级路径已完成。

| Future degraded condition | 应继续可用的能力 / 目标行为 |
| --- | --- |
| Model failed / unavailable | lexical Knowledge、Memory management、history、Settings；模型操作明确不可用 |
| Web unavailable | local AI 与本地领域仍可用，不隐式改变联网权限或转 cloud |
| Finance unavailable | Knowledge/Memory/Conversation 保持；不能用文档/模型猜测替代 Finance truth |
| Knowledge index rebuilding/stale | Memory/Conversation 保持；词法搜索显示暂不可用，不返回旧结果 |
| Vision unavailable | text AI 保持；图像操作明确 unavailable，不静默走未经授权的外部服务 |

Resource Monitor / System Status 计划显示 subsystem state 与可用操作，P1 完成低干扰 warning/recovery UX。
恢复时只恢复该子系统，不自动 replay 未知结果的请求、重发金融 mutation 或重新授权失效 session。

## 13. P1 — V1 Product Consolidation / Final Closing

**阶段定位 / 进入前状态：** 未来最终整合阶段。多个领域与平台任务完成后，仍需将其变成统一、可安装使用、可恢复的 Windows 产品。
P1 负责 final V1 productization，不再引入另一个大型业务领域。

**计划核心工作：** unified UX、one-click startup、Active Model settings/switching、Runtime/Ollama lifecycle、
Resource Monitor、System Status、degraded UX、backup/recovery UX、startup/shutdown polish。
在批准范围内恢复 previous page；完善 app icon、tray icon、launcher icon 与 product identity、accessibility。
完成 privacy audit、packaging、clean install、migration compatibility、final regression、final Windows acceptance、
final architecture review 与 V1 closing review。最终发布形态、安装/签名/更新范围须单独确认，不能从这些目标推定已承诺具体方案。

**目标用户能力：** 未来从一个清晰入口启动并管理工作区，理解模型/服务状态，处理部分失效，
明确选择备份/恢复范围，在干净环境和支持的迁移路径中使用完整 V1。
**核心技术与组件：** WPF lifecycle/native/security、React UX、Runtime domains/policy、Model Management、System Status、release packaging/launcher。
**数据与持久化：** 保留各 domain truth 和 portable backup；任何迁移、格式扩展、配置持久化必须经过 review。
backup UX 应准确说明当前格式的领域范围，不用“统一”掩盖 credentials/settings/Knowledge 的排除项。

**安全与权限边界：** 最终 privacy/security review、明确 process ownership、controlled egress、Finance commit authority、Browser Translate-only 不得弱化。
**与其他领域的关系：** 整合已有业务和平台能力及 degraded boundaries，而不是重定义 Memory/Knowledge/Finance。
**明确不做：** 不在 P1 新增大型业务领域，不把最终 regression 变成日常 full-suite/matrix；
release gate 的实际执行与历史证据继承须分别记录。
**阶段结果 / 依赖：** 未来；以完成的 V1 scope、批准的最终 gates 和独立 Closing Review 为前提，不提前宣称版本关闭。

## 14. V1 完成时的目标能力（全部按未来最终范围理解）

以下是 intended V1 final behavior；不是当前功能清单。当前已存在的子集见第 7 节，
新增范围仍由各阶段审核，具体 persistence/API/schema 不由本节确定。

### Local Assistant

目标为本地 Ask / Summarize / Translate 与明确的模型状态/能力提示；普通 Ask 保持 stateless single-turn。
需要额外 context 或工具时应有明确入口与授权，不悄悄改变普通请求语义。

### Durable Conversations

目标为持久 multi-turn、可继续历史、逐 Turn 显式 context，保留 ordered/terminal/no-replay 规则。
与未来 grounded/tool 操作的关系须 review，不能把 stored history 自动提升为 Memory。

### Explicit Memory

目标为用户可保存、管理、搜索、选择与恢复的 reusable personal context；用户明确决定每次使用。
不自动提取个人资料或自动长期记忆。

### Knowledge

目标在已有 import/revision/lifecycle/backup/lexical retrieval 上增加 K3 grounded answers 与可核对 citations。
Knowledge 继续是 reference material，derived indexes 可重建；不把 semantic/vector 或更多文件格式当作已批准 V1 必需项。

### Controlled Web

目标通过 Runtime-reviewed tools 获得当前 public evidence，默认 Ask-before-access，并明确 egress/privacy policy。
不复用浏览器登录或隐式发送私人领域内容。

### Vision

目标显式理解 image/screenshot/document image/chart/receipt 并产生候选结构化信息。
识别结果与 Finance commit 分离，不能直接形成 transaction truth。

### Finance

目标在 F0 事实核验后，逐步提供 reviewed integration、confirmed bill/transaction import、authoritative finance assistant 与受控跨域编排。
财务答案与 mutation 以 Finance truth/validation/commit 为准；在 F0 前保持 BLOCKED。

### Model Management

目标 ONE Active Model、Settings selection、Loading/Ready/Busy/Unloaded/Failed、能力检查与安全切换。
失败保留旧 active model，startup 只 warm 选中的模型。

### System / Resource Status

目标以低干扰方式显示 CPU/RAM/GPU/VRAM/temp/disk、模型/任务、服务与 storage，给出可操作的 warnings。
一个 subsystem 不可用不应把整个 Workspace 标为不可用；排除通用硬件 telemetry dashboard。

### Backup / Recovery

目标为 portable local-first domain data、准确范围说明、严格验证和明确恢复操作。
沿用 current truth/derived separation 与安全 publication；若扩展备份范围或迁移格式，须独立审核，不能从目标倒推已实现。

### Windows Product Experience

目标 one-click startup、主窗早出现、single instance/tray/native fallback、清晰 process ownership、degraded UX、
accessibility、统一 identity、经过 clean-install/migration/privacy/final Windows acceptance 的交付体验。
不 auto-launch browser、不 kill external/unknown processes，最终 V1 closing 由独立 review 决定。

## 15. 当前所在位置与下一步

已完成：**M0 / M1 / M1.5 / M2 / M3 / M4 / M5 / K1 / K2 / T0 — CLOSED — GO**；
**K0 — APPROVED — GO**。M5A–M5E 均为已关闭的内部阶段。
本路线图事实基线为 T0 正式关闭后的 authoritative main：
`b59da429711ca23e77e7772c5005b29c8dab12b2`；文档候选分支的 commit 不代表 main 已更新。

**K3 — NOT STARTED。Finance Integration — BLOCKED pending F0 authoritative Finance Reality Sync。**
W1、Vision 与跨阶段平台目标没有因路线图记录而启动；Finance contracts 尚未核验。

此扩展文档先交 **Architecture Guard documentation review**。
文档任务正式审核后，下一项独立架构活动可从 **K3 Architecture / Planning** 开始；
实施仍需阶段范围确认、架构审核与明确授权。路线图扩展本身既不启动 K3，也不批准任何未来 milestone。
