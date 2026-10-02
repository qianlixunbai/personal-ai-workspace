# Current Architecture — M0 Runtime + M1 Windows Entry + M1.5 Capabilities

M0 — Shared Runtime Foundation：**CLOSED — GO**，已发布 M0 基线。
M1 — Windows Assistant Entry：**CLOSED — GO**，使用 .NET 10 LTS / 原生 WPF，以独立 dotnet CLI 构建。
2026-10-02 final closing 全量回归通过（Java 14 / Desktop 31 tests，Desktop build 0 warnings/errors），
真实 Windows 验收全部 PASS，剩余场景由用户确认；证据来源与已知限制详见 [STATUS](../STATUS.md)。
M1 closing 已 fast-forward merge 到 main 并 push 到 origin/main，基线 `6d17ad7`；ADR-002 为 Accepted，与实现一致。

M1.5 — Assistant Core Capabilities：**CLOSED — GO**；当前实现分支 `m1.5-assistant-capabilities`，未 merge/push。
Windows 主动 Translate hotkey 或手动 Translate / Summarize / Ask → WPF → authenticated localhost Runtime →
`translate.fast` / `summarize.fast` / `chat.balanced` → Ollama → 单个纯文本 result card。Desktop 不直连 Ollama。
没有新增 pairing endpoint 或 per-client task ownership；仍为 single trust domain。
显式本机 token bootstrap + Windows Credential Manager 决策见 [ADR-002](../ADR/ADR-002-windows-client-credential.md)。
本次复核确认上述调用链、loopback-only、LOCAL_ONLY、用户主动采集与无正文持久化边界保持不变；
M1.5 仅增加 Summarize / Ask 两个受控 capability 与最小 Action 选择；没有修改旧仓库，
没有启动 Browser migration、Memory/RAG、多轮会话或工具框架。

## Windows Desktop 边界

| 目录 / 类 | 职责 |
| --- | --- |
| desktop/src/PersonalAiWorkspace.Core | 固定 loopback Runtime client、脱敏 DTO/错误、状态轮询/取消、selection/copy 安全决策 |
| Desktop / AssistantApp、AssistantWindow | WPF result card、托盘、应用 lifetime、显式凭据导入；纯文本、无历史 |
| Desktop / SingleInstance、HotkeyRegistration | 当前用户会话内 named mutex + activation event；RegisterHotKey + WM_HOTKEY；无 keyboard hook |
| Desktop / SelectionWorker、HelperProcess | 同一可执行文件的隐藏 helper，匿名 pipe；UIA MTA、有界焦点/祖先、2s 硬 deadline |
| Desktop / NativeCopyPort | 已验证原生编辑控件的一次 Ctrl+C；snapshot/read/restore STA helper 各 1.5s deadline |
| Desktop / CredentialStore | 本机 owner-only token 文件显式导入、handle ACL 校验、Windows Credential Manager generic credential |
| desktop/tests | mock HTTP、真实隐藏 UIA fixture、Win32 hotkey/credential、instance、copy 边界及 helper deadline 测试 |

UIA 编辑/自定义/未知控件无法确认保护属性时拒绝；已知 Document/Text 及结构祖先允许属性不适用，仍检查祖先保护。
这种属性不适用不能授权 Copy fallback。没有 ambient capture、clipboard subscription、OCR 或 accessibility tree 全量采集。
Copy fallback 只在 UIA Unsupported 且原生编辑焦点已验证时启用，其他控件走手动输入。
只保存空/Unicode 纯文本 snapshot；拒绝丰富格式，sequence/owner/焦点重验并条件恢复，外部更新不覆盖。
超时/无法归属/恢复失败均向用户提示；不保证异步来源应用迟到 Copy 时的 clipboard ownership。
正文只在 UI、HTTP 和 helper pipe 的有界内存中，不持久化 selection/result/clipboard。
Action 切换清空上一项输入/结果；执行期间禁用切换。原 Ctrl+Alt+Shift+T 在 capture 前强制选择 Translate。
Ask 只接收 question，没有 context、messages/history/systemPrompt；prompt 属于 Runtime。
不引入 conversation/history/DB、Markdown renderer 或新的 global hotkeys。

RuntimeClient 禁用 proxy/redirect；响应最大 1 MiB，严格校验状态、UUID、LOCAL profile、terminal/result/error 一致性、
重复 JSON 字段、Location、错误分类；拒绝未知或不完整响应，不显示 raw body/stack trace。
取消在 POST 完成取得 taskId 后发送 DELETE；取消已成功任务按 M0 返回 SUCCEEDED。
190s 轮询总上限、8s HTTP/body 上限，异常/退出时对已知 taskId 尽力取消。
POST 通信失败时可能无法知道是否已接受任务，不能宣称已取消；Runtime 自身仍有有界 deadline。
退出注销热键、终止自己的 helper、取消工作、释放 tray/HWND/activation/HTTP/CTS；不终止 Ollama 或其他应用。

## Java Runtime 边界

单 Spring Boot application，Java 21，独立进程与 Maven artifact。
现有实现没有 Maven 子模块、微服务、数据库或 UI。

```mermaid
flowchart LR
    Client[Authenticated local client] --> API[Translate / Summarize / Ask API]
    API --> App[Capability services + TextTaskSubmission]
    App --> Profiles[Model Profile resolution]
    App --> Admission[Provider Policy]
    Admission --> Tasks[Bounded TaskManager]
    Tasks --> Policy[Execution policy]
    Policy --> Provider[Provider abstraction]
    Provider --> Egress[Ollama final egress policy]
    Egress --> Ollama[Loopback Ollama]
    Ollama --> Result[Controlled task result]
```

## 真实 package 边界

| Package | 职责 |
| --- | --- |
| api | DTO 接入、HTTP 状态和安全错误映射、task GET/DELETE |
| capability.translate / summarize / ask | 独立请求 DTO、版本化 prompt、具体应用编排 |
| capability.TextTaskSubmission | 共享 profile/输入与 prompt 预算、LOCAL_ONLY admission/execution、输出校验 |
| model | 配置 ModelProfile 和安全 PublicProfile |
| policy | LOCAL/CLOUD 分类校验与无 fallback 策略 |
| provider | Provider、capability、execution、registry、readiness 契约 |
| provider.ollama | 固定本地 HTTP、metadata/model 检查、JSON 校验、响应上限 |
| task | UUID、有限执行/队列/保留容量、取消、deadline、终态提交 |
| security | 自动本地 token、私有文件权限、stateless authentication |
| health | 认证后的 provider/model readiness，与 Actuator 隔离 |
| config | 配置校验、loopback 启动约束 |
| common | 脱敏 ApiError / WorkspaceException |

Provider 错误复用 common 的稳定分类；Ollama 异常 cause / body 不跨适配边界。
三个 Capability Service 通过 TextTaskSubmission 依赖 Provider，不依赖 Ollama 类型。Profile 来自 YAML，无数据库。

## 任务生命周期

所有状态提交、查询、取消和 deadline 操作由单进程 monitor 串行化。
Worker 执行不占用 monitor；submit 和 completion 不会把用户正文放入日志。
UUID 在 admission 创建，HTTP 202 后成为任务稳定身份；QUEUE_FULL 不返回未接受的任务。
running/queued 可以转为 CANCELLED 或 TIMED_OUT；只有仍为 RUNNING 的任务可以提交结果。
终态不可逆，已成功结果的后续 DELETE 是 no-op。

队列由 ArrayBlockingQueue 实现，默认 4；ThreadPoolExecutor 默认单 worker。
取消/queue timeout 删除 queued Runnable，及时释放队列容量。
运行取消通过 Cancellation hook 取消 HTTP future，尽力传播。
Worker 结束前不会让新任务获得它的执行槽位，避免与仍在本地执行的代码并发。
Ollama/GPU 的最终停止时间不受 Runtime 保证。

取消/超时的 timer 会从 ScheduledThreadPoolExecutor 删除。
任务记录最多 64；保留容量也参与 admission，满载返回 429，避免无限积累结果。
终态约 2 分钟后过期，查不到返回 TASK_NOT_FOUND；仍在 worker 中的记录保持有界并暂不回收。
无任务清单 API、无任务持久化、无 streaming。

## 出站与安全

Profile `translate.fast` 默认映射 `ollama / qwen3.5:4b / LOCAL / m0-1`。
context 8192、output 2048、temperature 0.1；prompt 版本独立为 `translate-v1`。
新增 `summarize.fast` / `chat.balanced` 同样解析到本机 Ollama / `qwen3.5:4b`，version `m1.5-1`；
context 均为 8192，output 分别为 1024 / 2048，temperature 为 0.1 / 0.4；prompt 为 `summarize-v1` / `ask-v1`。
Summarize 输入 6000 字符 / 6656 UTF-8 字节，Ask 3000 / 5632，Translate 保留 4000 / 5632；
prompt 最多 512 字节，result 最多 outputBudget × 4 字节，HTTP body 32 KiB，provider response 1 MiB。
这是保守字符/字节预算，不承诺精确 tokenizer 上限。
Policy 在 admission、worker 执行及最终 Ollama model egress 重验。
三个 capability 固定 LOCAL_ONLY；当前任何 PrivacyMode 都拒绝 CLOUD；没有 cloud adapter 或 fallback 路径。

HTTP 访问只到字面量 127.0.0.1，不使用系统 proxy、不跟随 redirect。
tags 用于 provider/model availability；不自动安装模型。
非 streaming chat 必须完整结束、模型身份匹配、assistant 文本非空。
长度截断或 tool_calls 拒绝为 PROVIDER_RESPONSE_INVALID，最多接收 1 MiB。
Provider request deadline 覆盖读取响应正文；connect/request/queue/execution timeout 有独立 phase。

API loopback-only，Bearer token 由专用私有目录持有；CORS 不承担认证职责。
公开 Actuator health 仅包含进程状态。Provider readiness 不影响 Spring readiness。
拒绝 Origin/cross-site capability 请求，未来 pairing 需显式设计客户端 origin/权限规则。
全局只有一个本地客户端信任域，不做 per-client task ownership。

## 其他仓库与长期边界

Workspace 完全不引用、复制或修改 Finance / Local AI Assistant 代码。
没有 Finance DB credential、DB dependency、Tool Gateway 或 `/ai/ask` 改动。
Finance PostgreSQL 长期仍由 Finance 独占；未来仅能通过 authenticated Gateway 访问业务查询服务。
Finance Reality Sync 尚未完成；本机没有验证学校笔记本工作区，不据此进行集成。
Browser 现有路径不变，B11/B12 保持 DEFERRED；本轮不处理扩展迁移。
未建立 Memory、RAG、tool calling、完整 React/WebView2 Workspace 或其他未来空框架。
