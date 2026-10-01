# Personal AI Workspace — Current Status

Updated: 2026-10-01 (Asia/Shanghai)

此文件是当前阶段、完成状态、验证证据与遗留项的唯一事实来源。
README 负责启动/API 使用；ADR 负责已采用决策。

## 阶段与结论

Phase 2 — Shared Runtime + Windows Entry。

当前执行范围：**M1 — Windows Assistant Entry Vertical Slice**。

M0 — Shared Runtime Foundation：**CLOSED — GO**；以下 M0 验证记录保留为历史事实。
M1 当前结论：**PARTIAL / AWAITING REAL WINDOWS ACCEPTANCE**。
SDK 环境阻塞已在用户明确授权后解除，Windows Entry 实现与自动验证已完成。
仍等待用户真实 Notepad / Chrome selection → Runtime → Ollama → result、tray/lifecycle 与错误路径验收；
未把代码、fixture 测试或 M0 Ollama smoke 当作 M1 的真实人工 PASS。

## M1 本轮真实核对

- 开始时 `main` 工作树干净；HEAD / 本地 `origin/main` / `git ls-remote origin refs/heads/main`
  均为 `5d71d11144fd6e066638f29ea2464fdc16ea332a`。
- origin：`https://github.com/qianlixunbai/personal-ai-workspace.git`；M0 已成功发布到远端。
  这修正此前 Current Status 中“未配置 remote，未推送”的过时描述，不改变 M0 历史验收结果。
- 前置环境核对确实发现 SDK 缺失，并在 `80ccb53` 记录阻塞，没有自行安装。
  用户随后明确授权安装正式 .NET 10 SDK 并继续同一分支；未 reset、重建分支或重启 milestone。
- 安装前执行 `winget --version`（v1.29.380）与 `dotnet --list-sdks`（空）。
  仅执行指定 `winget install --id Microsoft.DotNet.SDK.10 -e --source winget --accept-package-agreements --accept-source-agreements`。
  下载官方 Microsoft installer，通过 WinGet hash 校验，安装成功；没有绕过 UAC。
- 安装后 `dotnet --info` / `dotnet --list-sdks` / WinGet package list 确认正式 SDK **10.0.401 x64**，
  Host 10.0.12，当前终端可用，无 PATH 刷新阻塞。SDK 包自带运行组件；没有单独安装 Desktop Runtime，
  没有安装 Preview/RC、.NET 11、Visual Studio 或无关工具。
- 环境：Windows 11 x64（10.0.26100）；Java 21.0.7；Spring Boot 4.1.1；Maven Wrapper 3.9.16。
  没有修改 Java、Maven 或 Ollama 环境。Desktop 三个 project 均为 `net10.0-windows`。
- M0 源码、pom、API 与 token/filter/task semantics 未修改。

## M1 已实现

- `desktop/PersonalAiWorkspace.Desktop.slnx`：Core / WPF Desktop / Windows tests；与 Maven 独立。
- 当前用户会话单实例（named mutex + activation event）、无主窗口运行、system tray 打开/检查/退出。
- Ctrl+Alt+Shift+T：RegisterHotKey + MOD_NOREPEAT；明确冲突错误，退出注销；无 keyboard hook/logger。
- 仅主动热键触发 UIA：当前 focused element、有界祖先保护检查、TextPattern.GetSelection；
  MTA helper 进程，2s 超时可终止，捕获前后重验前台、native focus 和 UIA focused element。
- password/protected、编辑/自定义控件保护属性无法确认、UIA exception/timeout、无 selection、超预算与前台变化 fail closed；
  已知 Document/Text 与结构祖先允许属性不适用并检查祖先；这种路径不能授权 Copy fallback。
  不把旧 input 留作新 capture 成功，不做后台 selection/clipboard monitoring。
- Controlled-copy：仅验证过的原生 Edit/RichEdit 焦点；仅空/纯文本 clipboard snapshot；
  等待快捷键释放最多 700ms，一次 Ctrl+C、新 sequence + 来源进程校验、600ms 新内容等待。
  snapshot/read/restore 各在独立 STA helper，1.5s deadline；正文只经内存 pipe。
  条件恢复 Unicode 纯文本；外部变化不覆盖，restore failure/late copy/ownership 不明有明确提示。
- 纯 WPF input、目标语言、Translate、Cancel、queued/running、纯文本 result、Copy result 与 close-to-tray。
  捕获成功自动 Translate，失败可手动输入；无 Markdown/chat/history/conversation。
- 固定 Runtime HTTP client：POST 202 + Location、GET polling、DELETE cancel；所有终态与错误分类。
  固定 127.0.0.1:8765、proxy/redirect 禁用、1 MiB response cap、严格 JSON/UUID/status/profile/result/error 校验。
  Runtime health；offline、401、404、429、provider/model/policy、malformed response 分类 UX。
- 用户显式本机私有 token file bootstrap；本机路径、owner、handle ACL、格式与读预算校验；
  Windows Credential Manager 保存，仅当前用户本机后续登录可用。missing/invalid/unauthorized/forget 明确。
  已采用方案 B，见 ADR-002；无 Runtime pairing endpoint 或 M0 breaking change。
- 没有 Desktop 正文日志、selection/result/clipboard history、plaintext credential file；
  DTO/exception 诊断不包含正文/token；窗口关闭/应用退出释放当前内容，cleanup 释放资源。

当前仍是 **single trust domain remains**：没有 per-client credential 或 task ownership isolation。

## M1 已执行验证

命令均从仓库根目录执行。普通 Desktop 测试不启动真实 Ollama，也不读取用户当前 selection/clipboard。

| 命令 / 方法 | 结果 |
| --- | --- |
| `dotnet --info` / `dotnet --list-sdks` | PASS，正式 SDK 10.0.401 x64 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS，30 tests，0 failed/skipped |
| `.\mvnw.cmd clean verify` | PASS，14 tests，0 failures/errors/skipped + package，21:03:15 +08:00 |
| `.\scripts\real-local-smoke.ps1` | M0 REAL PASS，21:04:44 +08:00，task SUCCEEDED，resultLength 6 |
| `git diff --check` / `git diff --cached --check` | PASS |
| tracked build/cache/log/credential 与 secret-pattern scan | PASS，匹配 0；bin/obj/.vs/TestResults/Runtime credential/verification log 已忽略 |
| 2 个本机 token 值比对（不输出值） | tracked files / private verification logs 泄漏匹配均 0 |
| Desktop source privacy / boundary scan | 无正文 logger/persistence、Ollama endpoint、keyboard hook 或 clipboard subscription |

Desktop 高价值覆盖：mock HTTP 提交/QUEUED→RUNNING→SUCCEEDED、提交期间取消、HTTP 401/404/429/403/503、
200 failed envelope 与 timeout/cancel、offline/missing credential、重复字段/未知状态/错误 UUID/oversize/Location/redirect fail closed、
脱敏 DTO/异常；copy 旧值/owner/丰富格式/恢复失败/取消恢复；native hotkey conflict + release；
单实例激活/释放；WPF XAML/manual/cancel controls；隔离 WinCred roundtrip/forget、bootstrap 私有 ACL/malformed file；
真实隐藏 UIA fixture 的 selected text/empty/PasswordBox/不支持保护属性拒绝；helper 进程启动与挂起 timeout/kill/reap。
fixture 的测试内容为合成文本，只读取该隐藏 fixture；copy 测试用 fake port，不改动用户剪贴板。

M0 新 smoke taskId：`bd37d699-bdba-45cf-9aae-9a760d63bfea`，loopback bind 与未认证 401 通过。
这是 Runtime/Ollama 回归，不是 M1 Notepad/Chrome acceptance；过程日志/脱敏 evidence 在忽略的 `.verification/`。

## M1 已知限制

- UIA 随目标应用/provider/权限变化，真实 Notepad、Chrome、tray 和完整退出 UX **UNVERIFIED**。
- Copy fallback 拒绝无法证明安全的 hosted/custom controls（包括浏览器 DOM fallback）、图片/富文本/大 clipboard；手动输入始终可用。
- 只恢复 Unicode 纯文本，不保存原格式/ownership；来源应用迟到 Copy 或焦点/owner 变化时可能无法安全恢复，UI 明确提示。
- 不控制 Windows 自身 clipboard history/同步；显式 Copy result 会进入系统剪贴板。
- Cancel 不保证 GPU 立即停止。POST 通信失败可能已接受但尚未知 taskId；不能声称已取消，Runtime 自身 deadline 有界。
- 凭据保护不隔离已攻陷的同用户进程；forget 不撤销 Runtime token；没有 per-client ownership 或自动 rotation。
- 没有 installer、auto-start/update、Windows Service 或 Runtime/Ollama lifecycle manager。

## M1 真实 Windows acceptance checklist（待用户返回结果）

先确认 Ollama 与已有配置模型可用。在仓库根目录：

```powershell
java -jar target/personal-ai-workspace-0.1.0.jar
# 另一个终端：
dotnet run --project desktop/src/PersonalAiWorkspace.Desktop --no-build
```

首次点击“导入 Runtime 凭据…”选择 `.runtime/client-token`，确认 credential valid。
默认热键 Ctrl+Alt+Shift+T；在源应用触发前不要先激活 Assistant 窗口。
每项返回 PASS/FAIL、应用版本/分支及受控错误分类，不提供选区、译文或 token 原文。
Notepad 与 Chrome 的真实选区链路均需成功才能评定 M1 GO；Chrome UIA 不可用时的手动输入提示
是保守错误行为，不能替代该场景的 selection acceptance PASS。

| 场景 | 人工操作与预期 | 当前结果 |
| --- | --- | --- |
| 生命周期 | 启动两次仍只有一个实例；托盘打开/关闭窗口；托盘退出后热键和进程释放 | UNVERIFIED |
| Notepad | 选中非敏感测试短句 → hotkey → input 填入 → Runtime Translate → result | UNVERIFIED |
| Chrome/Chromium | 普通网页选中短句 → hotkey → UIA → result；UIA 不可用则明确手动输入，不强行 DOM copy；不修改扩展 | UNVERIFIED |
| 无选区 | 不选中文字触发，明确提示；不能将旧 clipboard 当成当前选区 | UNVERIFIED |
| Protected/password | 聚焦受保护输入触发，拒绝读取与复制 fallback | UNVERIFIED |
| Clipboard | 先放入非敏感旧值，尝试 fallback，检查没有误用旧值且按所选保守策略恢复/提示 | UNVERIFIED |
| Runtime offline | 停止 Runtime 后翻译，明确 Runtime unavailable；无 Ollama/cloud fallback | UNVERIFIED |
| Provider offline | Runtime 保持运行、由用户停止 Ollama 后翻译，明确 Provider unavailable | UNVERIFIED |
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

本轮仅实现 M1 Translate Windows Entry；等待真实 Windows acceptance。
Deferred：Finance integration/Gateway、Memory/Conversation/SQLite、Knowledge/RAG/embedding、
tool/agent framework、完整 WebView2/React Workspace、Browser migration、cloud、streaming、Summarize/Chat、
voice/vision/OCR、installer/auto-update/Windows Service、clipboard history/continuous monitoring、
backup/migration engine、同步及其他超出 M1 的能力。

下一步：用户完成 M1 checklist 并返回结果，必要时修复后 Closing Review；不开始下一 milestone。
Finance Reality Sync 是未来 Finance 集成的前置条件，不是本轮任务。

## Git 交付

M0 远端基线：`main` / `origin/main` = `5d71d11144fd6e066638f29ea2464fdc16ea332a`，
提交主题 `feat: bootstrap personal AI workspace runtime`，已推送到上述 origin。
当前 M1 工作分支为 `m1-windows-entry`，从 `80ccb53` 继续实现；没有 merge 或 push。
精确当前 HEAD 与工作树状态通过 `git rev-parse HEAD` / `git status --short` 获取。
生成的 credential、验证日志及 build outputs 被忽略，不进入 Git。
