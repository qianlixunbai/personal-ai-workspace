# Personal AI Workspace

独立、local-first 的共享 AI Runtime。M0 — Shared Runtime Foundation：**CLOSED — GO**。
当前阶段、验证证据与遗留项的唯一事实来源：[docs/STATUS.md](docs/STATUS.md)。

M1 Windows Assistant Entry 已实现 .NET 10 LTS / 原生 WPF 客户端，代码位于 `desktop/`。
M1 — Windows Assistant Entry：**CLOSED — GO**。2026-10-02 全量回归通过，用户确认剩余真实 Windows 验收全部 PASS。
Java 与 Desktop 分别使用 Maven Wrapper / dotnet CLI 验证；Desktop 只调用 Runtime，不直接访问 Ollama。
M1 closing commit 已 fast-forward merge 到 main 并 push 到 origin/main；发布基线为 `6d17ad7137665bbe6105c868db41edfbd9cf46be`。
M1.5 — Assistant Core Capabilities：**CLOSED — GO**，新增 Summarize 与 single-turn stateless Ask AI。
本轮实现保存在 `m1.5-assistant-capabilities`，未 merge/push；完整证据见 STATUS 与 [M1.5 Closing Report](docs/milestones/M1.5-CLOSING-REPORT.md)。

## 启动

需要 JDK 21；无需安装全局 Maven。首次构建需要网络下载 Maven 与依赖。

```powershell
.\mvnw.cmd clean verify
java -jar target/personal-ai-workspace-0.1.0.jar
```

macOS / Linux 使用 `./mvnw clean verify`。Runtime 默认监听 `127.0.0.1:8765`。
非 loopback 地址在启动时拒绝。Ollama 离线不会阻止 Runtime 启动。
默认 Ollama 地址 `http://127.0.0.1:11434`，默认模型 `qwen3.5:4b`。
Runtime 不下载模型、不修改 Ollama 配置、不连接其他项目。

首次启动自动生成 256-bit 本地客户端 token，保存在 `.runtime/client-token`；
POSIX 权限为目录 0700 / 文件 0600，Windows ACL 仅允许文件所有者访问。
不输出 token、不提交 token。客户端应从本机私有文件读取，避免复制到日志或共享终端。
配置见 `src/main/resources/application.yml`。token-file 指向专用私有目录；
Runtime 会收紧该目录及文件权限。不要将其配置为共享目录。

## Windows Assistant（M1 / M1.5）

需要 Windows 11 x64 和 [正式 .NET 10 SDK](https://dotnet.microsoft.com/en-us/download/dotnet/10.0)。
本机在用户授权后通过 WinGet `Microsoft.DotNet.SDK.10` 安装并验证了 SDK 10.0.401。
没有单独安装 Desktop Runtime、Visual Studio、Preview/RC 或 .NET 11。
SDK 自带的运行组件不属于额外安装包。根目录 `global.json` 仅接受正式 .NET 10 SDK，适用于下述根目录命令；不影响 Maven。

在仓库根目录执行（Runtime 在另一个终端按上节启动）：

```powershell
dotnet --info
dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx
dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore
dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore
dotnet run --project desktop/src/PersonalAiWorkspace.Desktop --no-build
```

首次启动打开最小 Assistant window。点击“导入 Runtime 凭据…”并选择当前用户的
`.runtime/client-token` 文件；也可选择 Runtime 自定义配置的本机私有 token 文件。
客户端校验本机路径、无 reparse point、文件所有者与私有 ACL，并检查打开文件 handle 的权限，
将凭据保存到 Windows Credential Manager。后续启动无需重新读取文件或日常复制 token。
导入后用认证 readiness 确认凭据；Runtime offline 时仍保留已导入凭据并明确提示。
凭据 missing/invalid/unauthorized 可通过显式重新导入修复；“忘记凭据”只删除 Desktop 保存的副本。
M1 保留 single trust domain，没有 per-client task ownership。详见 [ADR-002](docs/ADR/ADR-002-windows-client-credential.md)。

日常使用：

- 在其他应用选中文字，按 **Ctrl+Alt+Shift+T**；成功捕获后填入 input 并自动发起 Translate。
- 没有选区、受保护控件、前台变化或 provider 不支持时，窗口显示分类提示；可手动输入/粘贴再点 Translate。
- Action 默认 Translate，目标语言默认 `zh-CN`，可选 `en` / `ja`。原热键始终切回 Translate 再捕获选区。
- Summarize：手动输入/粘贴文本，按源语言生成简洁摘要。Ask AI：输入单轮问题，得到纯文本回答。
- 切换 Action 清空上一项 input/result；执行时不能切换。所有 Action 共用 Cancel，向 Runtime 发送 DELETE，以实际终态为准。
- 结果按纯文本显示，可显式 Copy result；关闭窗口继续在托盘运行。托盘可打开窗口、检查 Runtime 或退出。
- 热键冲突有明确提示，仍可从托盘手动翻译；再次启动应用激活同一用户会话内的已有实例。

选区只在主动热键后读取。UIA 在 MTA helper 进程读取当前焦点和最多 24 层祖先，2s 超时后终止 helper。
password/protected、编辑/自定义控件保护属性无法确认、UIA 异常/超时以及前台/焦点变化均 fail closed。
已知 Document/Text 和结构祖先允许该属性不适用，仍检查祖先保护状态；这不能授权 Copy fallback。
不做 screenshot/OCR、键盘 hook、剪贴板订阅或后台选区监控。

可关闭的 Copy fallback 采用保守边界：只对已通过保护检查且焦点身份稳定的
`Edit` / `RichEdit20W` / `RICHEDIT50W` 原生控件发送一次 Ctrl+C；浏览器 DOM 等其他控件不强行复制。
仅保存空或纯文本剪贴板的内存 snapshot（最多 65536 字符），拒绝图片/富文本/文件或自定义格式。
读取/恢复分别在 STA helper 中，单次 1.5s 上限；等待新剪贴板最多 600ms。
检查 sequence、来源进程和前台/焦点，拒绝旧文本；恢复仅在本次 sequence/来源仍匹配时进行。
恢复为 Unicode 纯文本，不保证原格式/ownership；外部更新不覆盖，恢复失败明确提示并拒绝自动翻译。
来源应用迟到 Copy、焦点变化或 clipboard ownership 无法确认时，可能无法安全恢复；UI 提示检查剪贴板并手动输入。
这不是通用 clipboard history engine；Windows 自身的剪贴板历史/同步由用户系统设置控制。

Desktop 不写正文日志或历史；helper 正文只通过匿名标准流 pipe 传递到父进程内存，
不经 command line、文件或日志。Copy result 是用户显式向系统剪贴板写入结果。
Runtime 地址固定 `http://127.0.0.1:8765`，禁止 proxy/redirect，无 cloud/Ollama fallback，
不管理 Java、Ollama、模型下载或服务启动。

## API

所有 `/api/v1/**` 请求需要 `Authorization: Bearer <local-token>`。
公开健康接口只返回 Runtime 状态；provider readiness 需要认证。

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/actuator/health` | Runtime health |
| GET | `/actuator/health/liveness` | 进程 liveness |
| GET | `/actuator/health/readiness` | Runtime readiness，不依赖 Ollama |
| GET | `/api/v1/providers/readiness` | 本地 provider / 配置模型可用性 |
| POST | `/api/v1/translate/tasks` | 提交 Translate，202 + taskId + Location |
| POST | `/api/v1/summarize/tasks` | 提交 Summarize，202 + taskId + Location |
| POST | `/api/v1/ask/tasks` | 提交单轮 Ask，202 + taskId + Location |
| GET | `/api/v1/tasks/{taskId}` | 三种 capability 共用状态、成功结果或受控错误 |
| DELETE | `/api/v1/tasks/{taskId}` | 取消 QUEUED / RUNNING；终态幂等返回 |

提交示例：

```json
{"text":"Hello, world!","sourceLanguage":"en","targetLanguage":"zh-CN","profile":"translate.fast"}
```

Translate 的 `sourceLanguage` 可省略，`profile` 可省略并默认为 `translate.fast`。
语言参数是形如 `en`、`zh-CN` 的标签，不接受任意 prompt 指令。

Summarize 请求：`{"text":"Synthetic source text","profile":"summarize.fast"}`。
`profile` 默认 `summarize.fast`；可选 `targetLanguage`，省略时输出源语言摘要。
Desktop 第一版使用源语言摘要，不额外提供样式选项。

Ask 请求：`{"question":"What is 2 plus 3?","profile":"chat.balanced"}`。
`profile` 默认 `chat.balanced`；这是 **single-turn stateless API**。
不接受 context、messages、conversationId、history、memory、tools 或客户端 systemPrompt。
所有 capability 拒绝未知字段（包括 `model`）、空输入、未知/跨 capability profile 与超预算请求。

| Capability | Profile | 字符上限 | UTF-8 输入字节上限 | context / output | 输出字节上限 | Prompt version |
| --- | --- | ---: | ---: | --- | ---: | --- |
| Translate | translate.fast | 4000 | 5632 | 8192 / 2048 | 8192 | translate-v1 |
| Summarize | summarize.fast | 6000 | 6656 | 8192 / 1024 | 4096 | summarize-v1 |
| Ask | chat.balanced | 3000 | 5632 | 8192 / 2048 | 8192 | ask-v1 |

HTTP body 最大 32 KiB，所有 prompt 最大 512 UTF-8 字节。
输入字节上限 = context − output − 512；字符与字节限制同时生效，非精确 tokenizer 计数。
Provider response 最大 1 MiB；实际 result 另按 profile output × 4 字节硬限制，超限安全失败，不静默截断。
三个 profile 第一版均配置为 `ollama / qwen3.5:4b / LOCAL`，不下载新模型。
Translate profile version 为 `m0-1`，新增 profile version 为 `m1.5-1`；temperature 分别为 0.1 / 0.1 / 0.4。
具体模型只由 Runtime 配置解析，public task response 不暴露具体模型名。

PowerShell 客户端示例（Runtime 已启动）：

```powershell
$headers = @{ Authorization = 'Bearer ' + (Get-Content .runtime/client-token -Raw).Trim() }
$body = @{ text = 'Hello, world!'; sourceLanguage = 'en'; targetLanguage = 'zh-CN'; profile = 'translate.fast' } | ConvertTo-Json
$task = Invoke-RestMethod http://127.0.0.1:8765/api/v1/translate/tasks -Method Post -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "http://127.0.0.1:8765/api/v1/tasks/$($task.taskId)" -Headers $headers
# 需要取消时：
# Invoke-RestMethod "http://127.0.0.1:8765/api/v1/tasks/$($task.taskId)" -Method Delete -Headers $headers
```

任务状态：`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`。
只有 `SUCCEEDED` 暴露 `result`。轮询获得终态后读取结果；不要将一次 QUEUED 响应视为完成。
响应包括 UUID taskId、capability、profile 的 id/version/locality、promptVersion、时间戳、result/error。
不暴露具体模型名、prompt、凭据、provider raw body 或异常堆栈。

受控错误包括 `PROVIDER_UNAVAILABLE`、`MODEL_UNAVAILABLE`、`TASK_CANCELLED`、
`TASK_TIMEOUT`、`QUEUE_FULL`、`INVALID_REQUEST`、`POLICY_DENIED`、
`PROVIDER_RESPONSE_INVALID`、`INTERNAL_ERROR`、`UNAUTHORIZED`、`TASK_NOT_FOUND`。
Admission/input/auth 错误直接返回 HTTP 4xx；已接受任务的失败在 GET 的 200 task envelope 内表达。
`TASK_TIMEOUT.phase` 区分 `CONNECT`、`PROVIDER`、`QUEUE`、`EXECUTION`；
取消使用 `TASK_CANCELLED`，不是超时。队列/保留容量满载返回 429。
Readiness 始终返回 200 的状态 envelope；读取 `available`、`modelAvailable` 和 `error`。

## 执行与隐私边界

调用链：API → Translate / Summarize / Ask Service → TextTaskSubmission → 共享 TaskManager → ProviderPolicy → Provider → Ollama。
Capability 持有各自 prompt；TextTaskSubmission 只复用 profile resolution、预算、受控执行与输出校验。
默认并发 1、等待队列 4、队列等待 30s、任务执行 150s、连接 2s、模型请求 120s、metadata 3s。
输出/终态仅保存在有界内存中：最多 64 条，完成后约 2 分钟过期（1s 清理周期或查询时清理）。
执行中的 worker 退出前仍占用执行容量，即使任务已经取消或超时。
取消会移除排队任务并取消下层 HTTP future；迟到结果不能覆盖 CANCELLED / TIMED_OUT。
HTTP cancel 不保证 GPU 立即停止；已提交 SUCCEEDED 的任务不会被事后 DELETE 撤销。
原始输入在取消排队任务或 worker 开始执行时从队列记录释放；运行中可能短暂被调用栈引用。
重启丢失所有任务，不支持会话或长期数据保存。

M0 只允许 LOCAL Ollama。`LOCAL_ONLY`、`LOCAL_PREFERRED`、`CLOUD_OPTIONAL` 是策略概念，
当前任何模式都禁止 cloud；三个 capability 均固定 LOCAL_ONLY。无隐式 fallback。
Ollama URL 只允许显式端口的 `http://127.0.0.1` / `http://localhost`，后者固定为 127.0.0.1。
禁用代理与 HTTP redirect，避免向远程地址发送正文。

loopback 不代替认证。拒绝带 Origin 或 cross-site Fetch Metadata 的 capability 请求，
不启用 CORS。Runtime 仍是同一 token 信任域内的本机 API；M1 Windows 使用显式本机文件 bootstrap。
没有 Browser pairing endpoint、独立客户端权限或令牌轮换；未来独立凭据需要同时演进 ownership。
同一 OS 用户能读取 token 是本地信任假设；不隔离已攻陷的同用户进程。
Runtime 默认不记录正文、模型回答、token、provider body。

## 验证

```powershell
.\mvnw.cmd clean verify
.\scripts\real-local-smoke.ps1
git diff --check
```

自动测试使用 fake work 和 loopback HTTP mock server，不依赖本机 Ollama。
M1.5 验证（2026-10-02）：Java 16 / Desktop 40 tests 全部 PASS，build 0 warnings/errors；
三种 capability 的真实 Runtime + Ollama smoke PASS。真实 WPF 主路径、热键、取消、离线/恢复与重启 PASS；
实际 Ollama 停止/恢复由用户人工确认 PASS。详细证据来源及边界见 STATUS 和 Closing Report。
M1 final closing regression（2026-10-02）：Java 14 tests、Desktop 31 tests 全部通过，0 failed/skipped；
Desktop restore/build 通过，build 0 warnings/errors。真实 Windows 验收包含 Notepad/Chrome 选区、
protected/password 拒绝、controlled-copy 与旧剪贴板保护、手动输入、cancel、Runtime/Provider offline 与 Provider 恢复、
tray exit/热键释放、凭据持久化及隐私日志检查，全部 PASS。自动操作与用户确认的证据来源详见 STATUS。
secret/token/tracked build-output scan 与 Git whitespace 检查通过；这些扫描限于仓库和本地验证输出，不是全系统磁盘审计。
smoke 脚本启动单独 Runtime、调用真实本地模型、检查监听地址和认证，并仅输出脱敏证据；
最后停止自己启动的进程。Ollama / 模型不可用时失败，不会自动 pull 或修改配置。
`.verification/` 中的过程日志与 smoke 私有 token 被 Git 忽略。

架构：[current-architecture](docs/architecture/current-architecture.md)。
决策：[ADR-001](docs/ADR/ADR-001-local-shared-runtime.md)。
