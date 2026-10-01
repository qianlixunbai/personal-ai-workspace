# Personal AI Workspace

独立、local-first 的共享 AI Runtime。当前已实现 M0 Translate foundation。
当前阶段、验证证据与遗留项的唯一事实来源：[docs/STATUS.md](docs/STATUS.md)。

M1 Windows Assistant Entry 已完成基线核对，当前 **BLOCKED / environment prerequisite**：
本机没有 .NET SDK，尚未创建 Desktop 项目。计划使用 .NET 10 LTS / 原生 WPF，
代码放在 `desktop/`，通过认证后的 Runtime API 翻译，不直接访问 Ollama。
需要先由用户安装 [正式 .NET 10 SDK（Windows x64）](https://dotnet.microsoft.com/en-us/download/dotnet/10.0)，
随后用 `dotnet --info` / `dotnet --list-sdks` 确认 10.x SDK 可用；仅安装 Runtime 不满足构建要求。
本轮没有安装 SDK。Java 与 Desktop 将分别使用 Maven Wrapper / dotnet CLI 验证。

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
| GET | `/api/v1/tasks/{taskId}` | 查询状态、成功结果或受控错误 |
| DELETE | `/api/v1/tasks/{taskId}` | 取消 QUEUED / RUNNING；终态幂等返回 |

提交示例：

```json
{"text":"Hello, world!","sourceLanguage":"en","targetLanguage":"zh-CN","profile":"translate.fast"}
```

`sourceLanguage` 可省略，`profile` 可省略并默认为 `translate.fast`。
语言参数是形如 `en`、`zh-CN` 的标签，不接受任意 prompt 指令。
拒绝未知字段（包括 `model`）、空文本、未知 profile、超过字符或 UTF-8 输入预算的请求。
HTTP body 最大 32 KiB，默认正文最多 4000 字符；另有 UTF-8 字节预算 5632，
为 8192 context 预留 2048 output 和 512 instruction/template budget。
这是一种保守输入限制，并非精确 tokenizer 计数。

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

调用链：API → TranslateService → TaskManager → ProviderPolicy / ProfileResolver → Provider → Ollama。
默认并发 1、等待队列 4、队列等待 30s、任务执行 150s、连接 2s、模型请求 120s、metadata 3s。
输出/终态仅保存在有界内存中：最多 64 条，完成后约 2 分钟过期（1s 清理周期或查询时清理）。
执行中的 worker 退出前仍占用执行容量，即使任务已经取消或超时。
取消会移除排队任务并取消下层 HTTP future；迟到结果不能覆盖 CANCELLED / TIMED_OUT。
HTTP cancel 不保证 GPU 立即停止；已提交 SUCCEEDED 的任务不会被事后 DELETE 撤销。
原始输入在取消排队任务或 worker 开始执行时从队列记录释放；运行中可能短暂被调用栈引用。
重启丢失所有任务，不支持会话或长期数据保存。

M0 只允许 LOCAL Ollama。`LOCAL_ONLY`、`LOCAL_PREFERRED`、`CLOUD_OPTIONAL` 是策略概念，
当前任何模式都禁止 cloud；Translate 固定 LOCAL_ONLY。无隐式 fallback。
Ollama URL 只允许显式端口的 `http://127.0.0.1` / `http://localhost`，后者固定为 127.0.0.1。
禁用代理与 HTTP redirect，避免向远程地址发送正文。

loopback 不代替认证。拒绝带 Origin 或 cross-site Fetch Metadata 的 capability 请求，
不启用 CORS。M0 是同一 token 信任域内的本机 API，尚未实现 Browser/Windows pairing、
独立客户端权限或令牌轮换；未来客户端接入时需要明确演进此契约。
同一 OS 用户能读取 token 是本地信任假设；不隔离已攻陷的同用户进程。
Runtime 默认不记录正文、模型回答、token、provider body。

## 验证

```powershell
.\mvnw.cmd clean verify
.\scripts\real-local-smoke.ps1
git diff --check
```

自动测试使用 fake work 和 loopback HTTP mock server，不依赖本机 Ollama。
smoke 脚本启动单独 Runtime、调用真实本地模型、检查监听地址和认证，并仅输出脱敏证据；
最后停止自己启动的进程。Ollama / 模型不可用时失败，不会自动 pull 或修改配置。
`.verification/` 中的过程日志与 smoke 私有 token 被 Git 忽略。

架构：[current-architecture](docs/architecture/current-architecture.md)。
决策：[ADR-001](docs/ADR/ADR-001-local-shared-runtime.md)。
