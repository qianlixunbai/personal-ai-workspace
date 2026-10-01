# Personal AI Workspace — Current Status

Updated: 2026-10-01 (Asia/Shanghai)

此文件是当前阶段、完成状态、验证证据与遗留项的唯一事实来源。
README 负责启动/API 使用；ADR 负责已采用决策。

## 阶段与结论

Phase 2 — Shared Runtime + Windows Entry。

当前执行范围：**M0 — Shared Runtime Foundation**。

当前结论：**GO**。自动测试、完整构建、真实 Ollama smoke、干净源码构建与 Git 最终审计通过。
未开始 M1 Windows Assistant。

## 已实现

- Java 21 / Spring Boot 4.1.1 单应用、独立进程；Maven 3.9.16 Wrapper 3.3.4。
- 默认 loopback 绑定，并拒绝非 loopback bind 配置。
- 应用自动生成本地 token，私有权限、stateless Bearer authentication；拒绝 Origin / cross-site capability 请求。
- Provider-neutral capability/execution/readiness 边界，仅 Ollama adapter。
- `translate.fast` → `ollama` → `qwen3.5:4b`；配置版本 m0-1、prompt translate-v1。
- LOCAL_ONLY 翻译与最终出站 policy 重验；M0 所有 privacy mode 都禁止 cloud，没有 fallback。
- 异步 Translate API、UUID taskId、有限队列/并发/结果保留。
- queued/running cancellation、HTTP cancellation propagation、不可逆终态与迟到结果丢弃。
- connect/provider/queue/execution timeout 分类、受控错误、输入与输出预算。
- Actuator Runtime health/readiness 与独立 provider/model readiness。
- 最小高价值自动测试、真实本机 smoke 脚本、架构与 ADR 文档。

## 已执行验证

| 验证 | 命令/方法 | 结果 |
| --- | --- | --- |
| Wrapper | `.\mvnw.cmd -version` | PASS，Maven 3.9.16 / Java 21.0.7 |
| 全量测试与打包 | `.\mvnw.cmd -q clean verify` | PASS，14 tests，0 failures/errors/skipped |
| 真实 Ollama | `.\scripts\real-local-smoke.ps1` | REAL PASS，19:21:32 CST |
| 非 loopback 启动配置 | `java -jar target/personal-ai-workspace-0.1.0.jar --server.address=0.0.0.0`（隔离 token 路径） | PASS，启动拒绝 |
| 远程 provider 配置 | `java -jar target/personal-ai-workspace-0.1.0.jar --workspace.ollama.base-url=http://192.168.1.10:11434`（隔离 token 路径） | PASS，启动拒绝 |
| Windows token ACL | 检查生成文件 Get-Acl | PASS，仅文件所有者 FullControl |
| 干净源码构建 | `git write-tree` → `git archive --format=zip` → 解包后 `.\mvnw.cmd -q clean verify` | PASS，14 tests + package；依赖使用本机 Maven cache |
| Git/secret 审计 | `git diff --check` / `git diff --cached --check` / tracked-file 检查 | PASS；无真实 secret、个人绝对路径、IDE cache 或 build output |

自动测试独立于真实 Ollama：

- RuntimeApiTest（1）：真实 Spring HTTP server，在 provider 尚未启动时 Runtime 正常；
  拒绝未认证/Origin、校验输入/未知字段/body 上限、profile 响应脱敏、provider missing/error、日志脱敏。
- ProfilePolicyTest（2）：profile resolution、任意 cloud mode 拒绝、未知 provider、远程 URL/非法配置拒绝。
- OllamaProviderTest（7）：HTTP payload/parsing/budgets、model missing、raw error、invalid/oversize/truncated response、
  provider timeout、in-flight cancellation、connect/request timeout 分类、最终出站策略。
- TaskManagerTest（4）：满载安全拒绝、排队取消及容量回收、迟到成功丢弃、queue/execution timeout、
  结果容量与过期、取消 hook、异常脱敏。

真实 smoke 证据：本机 Ollama installed model `qwen3.5:4b`；经过实际 Runtime API 的翻译任务
`d8d50e5a-db8f-470f-a8b5-b184a3b0e82d` 达到 SUCCEEDED，中文结果长度 6。
检查 Runtime UP、未认证 401、实际 listener `127.0.0.1`，并清理 smoke JVM。
这里只记录非敏感长度与元数据，不保存 prompt/回答全文或 token。
过程证据在本机 `.verification/`，不进入 Git。

## 已知限制

- 取消 HTTP 不保证 GPU 立即停止；不强制 kill 模型或管理 Ollama daemon。
- 单 token 信任域，没有客户端配对、独立 ownership、token rotation、Browser origin allowlist。
- 结果最多 64 条、完成后约 2 分钟保留；重启失效，没有持久化。
- 模型质量只完成短句 smoke；未验证长文翻译质量、硬件吞吐/显存并发或生产可靠性。
- context 输入预算为保守 UTF-8 字节限制，未引入精确 tokenizer。
- provider readiness 是 tags/model presence，不能证明模型加载、GPU 资源和推理质量。
- CONNECT timeout 分类用合成 HTTP 异常验证；真实本地 socket 验证 connection refused，
  未人为操纵系统网络制造 connect timeout。
- 本机验证 Windows/Java 21；Linux/macOS Wrapper 与 POSIX credential 权限实现未做平台实测。
- test JVM 有 Mockito/Byte Buddy 动态 agent 提示，不影响验证结果；Runtime 无此 agent。

## 现有仓库与 Deferred Scope

本轮没有修改、复制或合并两个旧仓库，没有 Finance DB 访问。
以下旧仓库状态来自项目输入，本轮未实时核验：

- Local AI Assistant v0.4.1，PAUSED / MAINTENANCE MODE；B11/B12 仍 DEFERRED。
- Finance TEMPORARILY FROZEN / WAITING FOR REALITY SYNC；学校笔记本最新工作区 **UNVERIFIED**。
  不将 GitHub Remote 当作学校电脑最新事实。

Deferred：Finance integration/Gateway、Memory/Conversation/SQLite、Knowledge/RAG/embedding、
tool/agent framework、Windows WPF/WebView2/React、Browser migration、cloud、streaming、
voice/vision/OCR、backup/migration engine、同步及其他超出 M0 的能力。

下一候选：M1 Windows Entry/Assistant 最小接入（需单独明确产品范围与 pairing），仅建议。
Finance Reality Sync 是未来 Finance 集成的前置条件，不是本轮 M0 的任务。

## Git 交付

本地分支 `main`，提交主题 `feat: bootstrap personal AI workspace runtime`。
未配置 remote，未推送。精确 HEAD 与工作树状态通过 `git rev-parse HEAD` / `git status --short` 获取。
所有 46 个新增文件均属于本仓库；生成的 credential、验证日志及 build outputs 被忽略。
