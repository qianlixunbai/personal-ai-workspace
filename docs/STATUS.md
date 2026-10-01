# Personal AI Workspace — Current Status

Updated: 2026-10-01 (Asia/Shanghai)

此文件是当前阶段、完成状态、验证证据与遗留项的唯一事实来源。
README 负责启动/API 使用；ADR 负责已采用决策。

## 阶段与结论

Phase 2 — Shared Runtime + Windows Entry。

当前执行范围：**M1 — Windows Assistant Entry Vertical Slice**。

M0 — Shared Runtime Foundation：**CLOSED — GO**；以下 M0 验证记录保留为历史事实。
M1 当前结论：**NO-GO — BLOCKED / environment prerequisite**。
已核对 Git、源码、测试及环境，创建 `m1-windows-entry` 分支；未创建 Desktop 项目，未实现 Windows Entry。
原因是本机没有可用 .NET SDK。按本轮要求，不自行安装 SDK，不将缺少 SDK 的状态写成已实现或已验收。

## M1 本轮真实核对

- 开始时 `main` 工作树干净；HEAD / 本地 `origin/main` / `git ls-remote origin refs/heads/main`
  均为 `5d71d11144fd6e066638f29ea2464fdc16ea332a`。
- origin：`https://github.com/qianlixunbai/personal-ai-workspace.git`；M0 已成功发布到远端。
  这修正此前 Current Status 中“未配置 remote，未推送”的过时描述，不改变 M0 历史验收结果。
- 环境：Windows 11 amd64；Java 21.0.7；Spring Boot 4.1.1（pom）；Maven Wrapper 3.9.16。
- `dotnet --info`：Host 8.0.31 x64，`No SDKs were found`；`dotnet --list-sdks` 无输出。
  PATH 仅找到系统 dotnet；系统 x64、系统 x86、当前用户 `.dotnet` 的常用 SDK 目录均不存在，
  没有 DOTNET / MSBuildSDK 环境配置。已装 3.1/6/8 Runtime，不满足 .NET 10 WPF 构建前置条件。
- 本轮重新执行 `.\mvnw.cmd clean verify`：**PASS**，14 tests，0 failures/errors/skipped，
  BUILD SUCCESS，2026-10-01 19:41:49 +08:00。M0 源码与 HTTP 契约未修改。
- `dotnet restore` / `dotnet build` / `dotnet test`：**NOT RUN — SDK missing / Desktop project absent**。
- 文档 `git diff --check`：PASS；tracked build/cache/credential/log 文件 0，literal secret-pattern 文件 0。
  本机 2 个私有 token 文件的值与 tracked 文件比对，泄漏匹配 0；检查时不输出 credential 值。
  `git check-ignore` 确认 Runtime credential、Java build output 与验证日志被忽略。
- M1 真实 Windows smoke：**UNVERIFIED**。本轮未重新执行 M0 真实 Ollama smoke；旧记录仅是 M0 历史证据。

## M1 恢复条件与实施计划

先由用户安装 [正式 .NET 10 SDK（Windows x64）](https://dotnet.microsoft.com/en-us/download/dotnet/10.0)，
在新的终端确认 `dotnet --info` / `dotnet --list-sdks` 可识别 10.x SDK 后继续本分支。
目标为 `net10.0-windows` / WPF；尚无实际 project target。

1. 在 `desktop/` 建立独立 solution 与 WPF 项目，保持 Maven / dotnet 两套构建。
2. 建立 Runtime HTTP client，覆盖 202、状态轮询、DELETE cancel 与受控错误；代理和 redirect 禁用。
3. 建立单实例、无主窗口托盘生命周期、RegisterHotKey 快捷键及资源释放。
4. UIA first，仅用户主动触发读取 selection；隔离阻塞 provider，检查前台变化和 protected/password 控件。
5. 实现保守 controlled-copy fallback：拒绝旧 clipboard、有限等待、安全恢复或明确保守限制；支持手动输入。
6. 优先评估方案 B：用户首次显式选择 Runtime 私有 token 文件，验证本机权限后导入 Windows protected storage。
   不新增网页可获取 credential 的 pairing endpoint；这只是候选方案，尚未实现，后续采用时新增 ADR-002。
7. 建立纯文本 input/result card、translate/cancel/copy result 及分类错误 UX，不保存正文或历史。
8. 补齐最小高价值 Desktop 契约/selection/lifecycle/privacy 测试，并验证 Java 回归。
9. 完成下述真实人工 smoke，取得用户结果后才能评定 M1 GO。

当前仍是 **single trust domain remains**：没有 per-client credential 或 task ownership isolation。
Desktop credential missing/invalid UX、DPAPI/Credential Manager、hotkey、selection 与 clipboard 恢复均未实现。
不能把本计划当作已采用的安全能力或已通过的测试。

## M1 真实 Windows acceptance checklist（待实现后执行）

先启动 Ollama、确认已有配置模型，再启动 Runtime 和后续 Desktop。
每项返回 PASS/FAIL、应用版本/分支及受控错误分类，不提供选区、译文或 token 原文。

| 场景 | 人工操作与预期 | 当前结果 |
| --- | --- | --- |
| 生命周期 | 启动两次仍只有一个实例；托盘打开/关闭窗口；托盘退出后热键和进程释放 | UNVERIFIED |
| Notepad | 选中非敏感测试短句 → hotkey → input 填入 → Runtime Translate → result | UNVERIFIED |
| Chrome/Chromium | 普通网页选中短句 → hotkey → UIA 或受控 fallback → result；不修改扩展 | UNVERIFIED |
| 无选区 | 不选中文字触发，明确提示；不能将旧 clipboard 当成当前选区 | UNVERIFIED |
| Protected/password | 聚焦受保护输入触发，拒绝读取与复制 fallback | UNVERIFIED |
| Clipboard | 先放入非敏感旧值，尝试 fallback，检查没有误用旧值且按所选保守策略恢复/提示 | UNVERIFIED |
| Runtime offline | 停止 Runtime 后翻译，明确 Runtime unavailable；无 Ollama/cloud fallback | UNVERIFIED |
| Provider offline | Runtime 保持运行、由用户停止 Ollama后翻译，明确 Provider unavailable | UNVERIFIED |
| Cancel | 较长请求点击 Cancel，展示 Runtime 返回的终态；不宣称 GPU 立即停止 | UNVERIFIED |
| Privacy | 检查 Desktop logs、工作目录和 temp，没有选区、译文或明文 token；仅允许受保护 credential storage | UNVERIFIED |

## M0 已实现（历史 Closing 事实）

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

## M0 已执行验证（历史 Closing 事实）

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

本轮仅授权 M1 Translate Windows Entry；当前因 SDK 缺失未实施。
Deferred：Finance integration/Gateway、Memory/Conversation/SQLite、Knowledge/RAG/embedding、
tool/agent framework、完整 WebView2/React Workspace、Browser migration、cloud、streaming、Summarize/Chat、
voice/vision/OCR、installer/auto-update/Windows Service、clipboard history/continuous monitoring、
backup/migration engine、同步及其他超出 M1 的能力。

下一步：满足 .NET 10 SDK 前置条件后继续 M1，不开始下一 milestone。
Finance Reality Sync 是未来 Finance 集成的前置条件，不是本轮任务。

## Git 交付

M0 远端基线：`main` / `origin/main` = `5d71d11144fd6e066638f29ea2464fdc16ea332a`，
提交主题 `feat: bootstrap personal AI workspace runtime`，已推送到上述 origin。
当前 M1 工作分支为 `m1-windows-entry`，本轮仅修正文档并记录环境阻塞；没有 merge 或 push。
精确当前 HEAD 与工作树状态通过 `git rev-parse HEAD` / `git status --short` 获取。
生成的 credential、验证日志及 build outputs 被忽略，不进入 Git。
