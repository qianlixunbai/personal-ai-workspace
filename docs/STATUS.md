# Personal AI Workspace — Current Status

Updated: 2026-10-03 (Asia/Shanghai)

此文件是当前阶段、完成状态、验证证据与遗留项的唯一事实来源。
README 负责启动/API 使用；ADR 负责已采用决策。

## 阶段与结论

Phase 2 — Shared Runtime + Windows Entry / M2 Browser Convergence。

当前交付：**M2B-2B-R1 — Real Chrome GET Security Compatibility：CLOSED — GO**，仅 Runtime security compatibility patch。
**M2B-2B：PARTIAL / AWAITING REAL CHROME ACCEPTANCE**。完整 Extension Closing / M2 未关闭，本轮不执行其余 Browser QA。
Runtime 稳定基线 `main == origin/main == 25dc1dfc9a103b030267f18d93059316f0ce008d`。
Patch 位于本地分支 `m2b2b-r1-chrome-get-security`，不 merge/push；Extension branch `m2b2b-runtime-migration` / `ddfa4a0` 未修改。

M1.5：**CLOSED — GO**（2026-10-02），实现与本轮验证完成；已 fast-forward merge 到 main 并 push 到 origin/main，发布基线 `22c45de4ff2ff2996960ca93817914af1da73baa`。

M0 — Shared Runtime Foundation：**CLOSED — GO**；以下 M0 验证记录保留为历史事实。
M1 — Windows Assistant Entry：**CLOSED — GO**。
SDK 环境阻塞已在用户明确授权后解除，Windows Entry 实现与自动验证已完成。
用户授权 Codex 操作电脑后，真实 Notepad / Chrome selection → Runtime → Ollama → result、
tray/lifecycle、无选区、password、Runtime offline 与 Cancel 已通过 Windows UI Automation 自动操作验收。
2026-10-02 用户明确确认剩余真实 Windows 验收没有问题，包括 controlled-copy、stale clipboard protection、
Provider unavailable / restart recovery、credential persistence 与 privacy/log inspection。
本次 M1 FINAL CLOSING REVIEW 重新执行全部回归并复核架构、安全与 Git 交付，正式收口为 CLOSED — GO。
M1 closing 后已 fast-forward merge 到 main 并 push 到 origin/main；M1 发布基线为 `6d17ad7137665bbe6105c868db41edfbd9cf46be`。历史 Closing Review 记录保留当时事实。

## M2B-2B-R1 实现与限定真实 Chrome 验证（2026-10-03）

**CLOSED — GO**：修复已由 Chrome 154 确认的 Origin-absent GET 被误归 native → 401 问题。
Browser bearer 验证与 Origin 校验最小分离；`br1` prefix 仅选择验证，仍需 registered clientId + constant-time SHA-256 verifier + 未 revoke。
无 Origin Browser 只允许 GET Translate readiness / GET task UUID，要求完整精确 none/cors/empty、Translate capability 和 existing owner checks。
Origin-present / pairing / mutating paths 保持 exact Origin，Originless POST/DELETE/未列入 GET 拒绝；Browser 不获得 native/Ask/Summarize/admin 权限。
无 wildcard、伪 Origin header 或 Origin synthesis；无 Origin response 不返回 Access-Control-Allow-Origin，exact-Origin OPTIONS 保持。
任务/AI/Prompt/Profile/Provider/Queue/Timeout/Concurrency/Ollama/Desktop 契约未修改。ADR-003 增加 evidence-based amendment，保留历史。

| 验证 | 结果 |
| --- | --- |
| Maven clean verify | PASS：30 tests，0 failure/error/skipped |
| Desktop restore/build/test | PASS：67 tests，0 failed/skipped；0 warnings/errors |
| Native real-local smoke | REAL PASS：Translate / Summarize / Ask SUCCEEDED；native management 与 GET/DELETE 回归 PASS |
| Synthetic Single / Batch security smoke | PASS：Originless readiness/owned polling、cross-owner 404、mutation 401、admin 403、revoke 401、restart；Batch 3 items/1 task/1 inference/3 mappings |
| Exact Origin / metadata / deny matrix | PASS：wrong/unknown/web Origins；metadata missing/wrong；malformed/forged/revoked credential；Ask/Summarize/admin/unknown GET；Originless mutations |
| Real Chrome 154.0.8037.59 exchange/storage | PASS：自然 exact Origin、none/cors/empty、无 Authorization，exchange 200；credential 保存、proof 清除、content script 读取拒绝 |
| Real Chrome readiness GET | PASS：自然 Origin ABSENT + Browser credential + none/cors/empty；200，Extension 可读 |
| Real Chrome Batch POST/task GET/result | PASS：POST 自然 exact Origin，202；polling GET 自然 Origin ABSENT，200；SUCCEEDED structured 2-item result 可读 |
| Real Chrome task cross-client isolation | PASS：第二 synthetic browser/native 读取 Chrome task 均 404 TASK_NOT_FOUND |
| CORS / secret / privacy / artifact audit | PASS：Originless GET 无 allow-origin；实际 token/browser/proof/content/result/source/build/archive/log/evidence 0 matches |
| git diff --check | PASS |

真实 Chrome 时间 00:51:29 +08:00，task `4ce73424-4b8c-414d-8b57-42cc6f075d19`，
`translate.fast / m0-1 / LOCAL / translate-batch-v1`。生产 candidate 未修改，也没有 security-header override。
真实 headless Chrome 使用实际 action popup pairing 与生产 worker Runtime client；由显式 isolated native dev authority 创建 proof，未重新验收 WPF GUI。
Worker debugger 仅用于自然网络 evidence 的 metadata 投影；不宣称 MV3 idle/lifetime PASS。
任务最后一次实际 GET 读到 SUCCEEDED；本轮没有刻意延长模型任务以观察每一个中间状态。
只保存 header-presence/status/identity/count 等 metadata；不保存正文/结果/credential/proof，临时 profile 在关闭自有 Chrome 后清理。
完整 28 项交付与 reproducible script 见 [R1 Closing Report](milestones/M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md)。

Resume readiness：Runtime compatibility blocker 已解除，可以回到 Extension M2B-2B Closing Acceptance。
MDN、Dynamic、Selection、Cache、Runtime/Provider offline、revoke/re-pair UX、MV3 30–45 秒仍未由本轮完成，M2B-2B 继续 PARTIAL。

## M2B-2A 实现与验证（2026-10-02；历史 closing 记录）

**CLOSED — GO / Runtime Batch Translation Contract Ready**。当时范围仅 Shared Runtime；M2B-2B / Extension migration 尚未开始。
后续已 merge/push 至稳定基线 `25dc1df`，以下保留当时 closing 证据。
开始时 main clean；fetch 后 `main == origin/main == d60647273a8dcf63b71e985bd8ba4e63ad5d64a9`。
分支 `m2b2a-runtime-batch-translate`，本地提交 `feat: add batch translate runtime contract`，不 merge/push。
最小同步 M2B-1 当前已发布事实；M1.5 / M2A / M2B-1 Closing Reports 与 ADR-001/002/003 保留历史原文。

- 只读核对 Local AI Assistant main / remote main `75bede161e7e81d2e7c0fa8e62ac2d05a7248c83`，v0.4.1 GO / RELEASED；工作树 clean，未修改。
  确认 viewport-first 1000 / normal 2800 chars、一次 inference、id mapping、partial/retry、model/prompt/settings cache；B11/B12 DEFERRED。
- 同一路径 POST Translate，`text/items` exactly one；Batch 每批 1–32 项，id 唯一非负 int，正文合计 ≤2800 chars / 4096 UTF-8 bytes。
  序列化 JSON（含 escaping/id）仍需满足 profile 的当前 5632-byte context 输入预算，body ≤32 KiB。
- 一个 Batch → 一个 shared TaskManager task → 一次 provider.execute / Ollama chat；仍 translate / translate.fast / LOCAL_ONLY。
  String 或唯一当前 sealed structured result shape；Single 三能力 result 仍为 JSON string，Batch 为 object/items array。
- Runtime `translate-batch-v1` + 严格 JSON parser；只返回 unique valid requested ids；duplicate id 全失效，unexpected/empty/malformed item 保持 missing。
  有效 subset / empty array 可 SUCCEEDED partial；malformed top-level / 8192-byte output 超限受控失败，无自动 item retry。
- Translate-only sanitized readiness；精确 GET/preflight 路由扩展，无 provider/model/raw diagnostics。原 auth/origin/ownership/revoke/cancel 保持。
  Desktop source/UI/token/WinCred 不变，无需重新导入凭据；没有扩展 storage/cache/DOM 修改或新 ADR。

| 验证 | 本轮结果 |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS：29 tests，0 failures/errors/skipped；22:56 +08:00 |
| Desktop restore / build / test | PASS：67 tests，0 failed/skipped，0 warnings/errors |
| `.\scripts\real-local-smoke.ps1` | REAL PASS：native Translate / Summarize / Ask 全部 SUCCEEDED；22:51 +08:00 |
| `.\scripts\browser-security-smoke.ps1 -Batch` | REAL PASS：22:58 +08:00，3 records / 1 POST / 1 task / 1 actual Ollama chat / 3 valid mappings |
| Synthetic browser security smoke | PASS：pairing/exchange、single、Translate readiness、wrong Origin/web 401、cross-owner 404、Ask/Summarize 403、restart/revoke |
| Whole batch DELETE | 真实 smoke CANCELLED；受控 slow HTTP integration 另验证 RUNNING cancellation 与 late output rejection |
| Secret/body/build/archive/evidence audit | PASS：实际 native/browser/proof 仅 stdin/内存比较，0 匹配、0 tracked build artifacts；最终数量见 Closing Report |
| `git diff --check` | PASS |

RuntimeApiTest 扩展现有 loopback HTTP mock：严格 types/limits、structured result、partial mapping、一次 chat 计数、ownership、readiness、
whole batch cancel、revoke 不取消 accepted batch、脱敏错误与 CapturedOutput 隐私检查。
TaskManagerTest 在原队列/deadline/cancel/retention 测试同时运行 String 和 immutable batch result，未建立第二套 Batch task test universe。
真实 smoke 的 verification-only relay 仅转发已有本机 Ollama 并计数，不保存正文、不进入产品 Provider，不停止用户 Ollama。
首次 smoke 的尾部审计先后遇到 Windows relay log file sharing、PowerShell UTF-16 surrogate 输入，以及测试名 GenerationSettings 的短词匹配；
已修正 cleanup/encoding/实际 captured-output 扫描，以上只记录最终完整成功退出证据。
真实 WPF 手动 GUI 未重新验收；本轮证据为 67 Desktop tests + native actual Runtime/Ollama smoke，历史 GUI PASS 不改写为本轮新证据。
实际 Chrome headers/host permissions/exchange/storage/DOM/cache **UNVERIFIED / M2B-2B DEFERRED**。
完整 23 项交付见 [M2B-2A Closing Report](milestones/M2B-2A-CLOSING-REPORT.md)。

## M2B-1 实现与验证（2026-10-02）

M2B-1 — Browser Pairing UX：**CLOSED — GO**。已 push feature branch、fast-forward merge main 并 push origin/main，发布基线 `d60647273a8dcf63b71e985bd8ba4e63ad5d64a9`。历史 Closing Report 保留当时未 merge/push 的事实。
基于干净且 fetch 后一致的 `main == origin/main == 9d20a9a4a138b9df3583e54eea8c3a1c78a8785e`。

- Assistant 最小 Pair Browser 入口；只在显式点击创建后 POST `/api/v1/security/pairings`，沿用 native WinCred。
- Origin 格式检查仅为 UX；Runtime 为最终 authority。显示 ID / secret / 本机时区 expiry，提供显式 Copy。
- Secret 不持久化、不记录日志、不进入 diagnostic DTO/异常；禁用 undo，新建前/过期/关闭时清除显示引用。
  关闭取消等待，迟到响应不能回填。显式剪贴板复制受 Windows history/sync 设置影响，托管引用清理不保证物理擦除。
- 手动刷新 Paired Browsers 安全 metadata；native DELETE 成功 204 后移除所选客户端，失败不伪装成功。
- 原 RuntimeClient HTTP/凭据/限制共用；Runtime Java、AI capabilities、Provider、TaskManager、profile/prompt/timeout 未改。
- M2A 当前发布状态已修正；M2A 历史 Closing Report 与 ADR-003 不改，无新长期安全决策/ADR-004。

| 验证 | 本轮结果 |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS：Java 23，0 failures/errors/skipped；20:49 +08:00 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS：67，0 failed/skipped；既有 40 tests 源码未改 |
| 真实 WPF Pair Browser | PASS：invalid Origin 提示、显式创建两次、ID/43-character secret/expiry、metadata refresh、关闭再打开无历史 |
| 真实 WPF Native 三能力 | PASS：Translate / Summarize / Ask，经真实 Runtime + 已有 Ollama，输出长度分别 6 / 76 / 1 |
| 实际 pairing proof / native token 泄漏检查 | PASS：两份当前 proof 与 native token 对比 source/log/build outputs，404 文件，无匹配 |
| 仓库 secret/token/log/build/archive/ignore 审计与 `git diff --check` | PASS：1075 文件 / 33522 byte+archive checks / 127 archives / 5 个 actual native tokens，0 匹配、0 tracked build artifacts |

真实 Windows 自动操作在 20:54 +08:00，通过 UI Automation 调用真实 WPF controls；不绕过 UI 直接创建 pairing 或提交 AI task。
未执行 exchange，随后关闭配对 UI；本轮启动的 Desktop/Runtime 已清理。截图仅在 secret 区域已清除后生成。
自动验收脚本曾因 UTF-8/BOM、owned-window automation tree、collapsed controls 调整后重跑；以上为最终完整成功记录。
Revoke 的 HTTP/错误/列表行为由 mock + WPF tests 验证；本轮没有 exchange 或真实已注册客户端 revoke 的新增端到端证据。
测试覆盖错误分类、response validation、capacity/security error、replacement、close/in-flight cancellation 与迟到响应。
真实 Chrome pairing / host permissions / Fetch Metadata / storage / Browser Translate **UNVERIFIED / DEFERRED**。
完整交付见 [M2B-1 Closing Report](milestones/M2B-1-CLOSING-REPORT.md)。

## M2A 实现与验证（2026-10-02）

M2A：**CLOSED — GO**（Runtime Browser Access Foundation）。不代表 Chrome Extension acceptance 或 M2 convergence complete。
分支 `m2a-browser-runtime-access`；基于干净且 fetch 后一致的 `main == origin/main == 22c45de4ff2ff2996960ca93817914af1da73baa`。
M2A 已 push feature branch、fast-forward merge main 并 push origin/main；M2B-1 开始时 fetch 确认
`main == origin/main == 9d20a9a4a138b9df3583e54eea8c3a1c78a8785e`。历史 M2A Closing Report 保留当时未 merge/push 的事实。

- Native bootstrap authority、token 格式、Credential Manager target 与所有 Desktop 文件不变，无需重新导入。
- Native 显式批准精确 `chrome-extension://<id>`，3 分钟 single-use proof，restart 丢弃 outstanding pairing。
- Browser 随机 clientId、独立 256-bit credential、SHA-256 verifier / constant-time comparison；默认 Translate only。
- Loopback + exact Origin + none/cors/empty Fetch Metadata + proof/credential；普通 webpage、未知扩展、错 origin/credential 拒绝。
  精确受控 preflight；无 wildcard CORS，不用 CORS 代替认证。
- 共享 TaskManager 绑定 owner；跨 browser/native GET/DELETE 与不存在任务等同 404 TASK_NOT_FOUND。
  Cancel、queue、deadline、late-result protection、retention 不重写。
- Token private directory 中的小型 security registry：64 KiB / 32 clients / 8 sessions，上限 5 proof failures/session、60 exchanges/minute。
  Owner-only permissions、exclusive writer lock、atomic replace、严格 schema、corruption fail closed。
  Credential/revoke 跨 restart，session/task 不跨 restart；只保存 auth metadata/verifier。
- Native list/revoke protected APIs；revoke 原子删除注册/verifier，后续请求拒绝；不自动取消已接受任务。
- Provider/profile/prompt/concurrency/timeouts 未改，三种 capability 均 LOCAL_ONLY，无 remote/cloud fallback。

| 验证 | 本轮结果 |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS：Java 23，0 failures/errors/skipped；最终 17:13 +08:00 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS，SDK 10.0.401 |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS：40，0 failed/skipped |
| `.\scripts\real-local-smoke.ps1` | REAL PASS：Translate/Summarize/Ask 均 SUCCEEDED，未认证 401，loopback listener |
| `.\scripts\browser-security-smoke.ps1` | REAL PASS：native、synthetic pairing、Translate、wrong-origin/web-origin 401、cross-owner 404、restart persistence/revoke |
| Secret/log/build/evidence audit、`git diff --check` | PASS；实际 token/ephemeral credentials 无泄漏，private auth/build/evidence ignored |

普通 API 测试覆盖 exact/missing/unknown/web/wildcard Origin、错/畸形/缺失 credential、Fetch Metadata、single-use/replay、
Translate allowed、Ask/Summarize/管理 APIs denied、受控 preflight、安全 client metadata、无 token/credential/Origin 日志。
Registry 测试覆盖 TTL、失败/总量 budgets、bounded registry、reload/revoke、corrupt/oversized/duplicate/unknown format、
private ACL、exclusive lock、atomic-write failure 与 stale-pending recovery。任务测试覆盖 owner 隔离、running/queued cancel、late-result 拒绝，
既有 queue/full/timeout/retention 回归通过。

本轮没有重新做 WPF 人工 GUI 验收；沿用 M1/M1.5 已确认的 GUI 证据，Desktop source 无变化，40 自动测试和 native 三能力真实 smoke 回归通过。
真实 Chrome extension headers/host permissions/storage/DOM/UI **UNVERIFIED / M2B DEFERRED**；不将 synthetic HTTP client 当作 Chrome 验收。
POSIX 平台、unsupported permission filesystem 和任意断电 durability 未做实机验证；当前 Windows ACL/restart/atomic failure 已验证。
完整决策见 [ADR-003](ADR/ADR-003-browser-client-security.md)，完整交付见 [M2A Closing Report](milestones/M2A-CLOSING-REPORT.md)。

## M1.5 本轮实现与验证（2026-10-02）

- Reality check：main clean，fetch 后 `main == origin/main == 6d17ad7137665bbe6105c868db41edfbd9cf46be`。
  从该 main 创建 `m1.5-assistant-capabilities`。最小同步 M1 已 fast-forward merge/push 的当前事实；历史 Closing Review 不改写。
- Runtime 增加 Summarize / Ask HTTP task API、`summarize.fast` / `chat.balanced` profile 与 `summarize-v1` / `ask-v1` prompt。
  共用原 TaskManager、线程池、queue/cancel/deadlines/短期保留及 error contract；所有 capability 固定 LOCAL_ONLY。
  Desktop 共用同一 RuntimeClient / AssistantOperation，只访问 authenticated `127.0.0.1:8765`。
- Summarize：6000 字符 / 6656 UTF-8 字节，context/output 8192/1024；Ask：3000 / 5632，8192/2048。
  Translate 保留 4000 / 5632，8192/2048。prompt ≤512 字节，HTTP body ≤32 KiB，provider body ≤1 MiB，output ≤outputBudget×4 字节。
  三个 profile 均使用已有 `qwen3.5:4b`；没有下载模型、cloud/fallback、任意 systemPrompt 或 context 接入。
- WPF 最小 Action selector 默认 Translate。切换清空输入/结果，执行时禁用切换；所有 Action 共用单个纯文本结果卡与 Cancel。
  原热键始终回到 Translate，selection/copy/credential/tray/hotkey 注册实现未重写。无 history、conversation、DB 或正文日志。

| 验证 | 本轮结果 / 证据来源 |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS，Java 16，0 failures/errors/skipped；最终运行 16:34–16:35 +08:00 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS，正式 SDK 10.0.401 |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS，Desktop 40，0 failed/skipped；包含现有 selection/copy/UIA/credential/lifecycle 回归 |
| `.\scripts\real-local-smoke.ps1` | REAL PASS，三种 capability 均 SUCCEEDED；实际 Runtime + 已有 Ollama，未认证 401，listener loopback，smoke JVM 已清理 |
| 真实 WPF 启动 / 手动三种 Action / 切换 | 自动 GUI PASS：由 UIA 操作真实 ComboBox、输入框、提交按钮并读取实际结果长度；不直接调用 controller |
| 原热键 selection Translate | 自动 GUI PASS：外部合成 WinForms 原生 Edit 选区，发送实际 Ctrl+Alt+Shift+T，从 Ask 状态自动回到 Translate 并成功；本轮未声称重跑 Notepad/Chrome |
| Cancel | 自动 GUI PASS：真实运行中的 Ask 长回答请求，经 WPF Cancel 得到 CANCELLED，结果为空 |
| Runtime offline / recovery | 自动 GUI PASS：停止仅本轮启动的 Runtime，Summarize 显示 Runtime unavailable；恢复后 Ask 成功 |
| Provider unreachable | 自动 GUI PASS：真实 Runtime 临时配置不可达 loopback Ollama endpoint，Ask 显示 Provider unavailable，恢复默认配置后成功；没有停止用户 Ollama |
| 实际 Ollama 停止 / 恢复 | 用户本轮明确人工确认“已人工验证，全部 PASS”；独立于上述隔离端点自动证据 |
| 重启 / 无历史 / credential | 自动 GUI PASS：终止仅本轮 Desktop 并启动新进程，input/result 为空，Action 默认 Translate，已保存 credential 仍支持成功翻译；不是 graceful tray exit 的新增证据 |
| Security / privacy scan | PASS：tracked+新增 source secret patterns、4 个实际 token、验证日志正文/token、build token 与 tracked 产物扫描均 0 匹配；ignore checks PASS，非全系统磁盘审计 |
| `git diff --check` | PASS |

Real local smoke（仅保留元数据）：

| Capability | taskId | Status | inputLength | outputLength | Profile / prompt |
| --- | --- | --- | ---: | ---: | --- |
| Translate | 58db4389-6201-42b1-a7bb-b273dd62f134 | SUCCEEDED | 13 | 6 | translate.fast / translate-v1 |
| Summarize | 7ab88ca4-2106-49b9-8673-2e505da84369 | SUCCEEDED | 94 | 94 | summarize.fast / summarize-v1 |
| Ask | 464350cd-2512-4e50-8eb8-4d8f45371d28 | SUCCEEDED | 49 | 1 | chat.balanced / ask-v1 |

Private evidence：忽略的 `.verification/m1.5-*.log`、`real-smoke-evidence.json`、
`m1.5-windows-evidence.jsonl`、`m1.5-scan-evidence.json`；不保存 prompt/answer 正文到日志或报告。
本轮启动的 Desktop / Runtime / selection fixture 已清理，没有停止用户 Ollama。
完整 22 项交付报告：[M1.5 Closing Report](milestones/M1.5-CLOSING-REPORT.md)。

## M1 Final Closing Review（2026-10-02）

- 收口前执行 `git status`、`git branch --show-current`、`git rev-parse HEAD`、`git log -5 --oneline`：
  工作树干净，分支 `m1-windows-entry`，reviewed implementation HEAD `d8e8281040578311c0a77978d0e2aaaeaacf9a70`。
  提交序列为 `d8e8281`（JSON health negotiation）、`c98704a`（Windows entry）、`80ccb53`（SDK prerequisite）、`5d71d11`（M0）。
- 2026-10-02 15:45–15:47 +08:00 重新执行 Maven clean verify、Desktop restore/build/test 与 `git diff --check`，全部 PASS；
  精确命令与数量见下表。此次没有重跑 M0 real-local-smoke；其既有 REAL PASS 保留为历史证据。
- 收口扫描覆盖全部 tracked files 的 secret patterns、实际本机 token 值泄漏、tracked build/cache/log/credential 输出，
  并检查 `.verification/` 与本次 Maven test logs 中的实际 token 泄漏；均无匹配，不输出 secret/token 原值。
  2026-10-02 15:50:54 +08:00：76 个 tracked files，3 个实际 token、12 个验证/测试日志文件，
  secret/token/tracked artifact 匹配均 0，7 项产物/凭据 ignore 规则检查全部通过；
  脱敏报告位于忽略的 `.verification/m1-final-closing-scan.json`。
  这是仓库和验证输出范围的检查，不宣称全系统磁盘审计；真实 privacy/log 验收由用户确认。
- 架构复核：Desktop 固定调用 authenticated `127.0.0.1:8765` Runtime，Runtime 统一调用本机 Ollama；
  UIA first / user-triggered only / conservative copy / protected fail closed / manual input、无正文日志或历史保持不变。
  相对 M0 基线，`src/`、`pom.xml` 与 `scripts/` 无变更；本次 closing 仅三个 Markdown 文档变化。
- ADR-002 `Accepted` 与实际 Windows Credential Manager / 显式文件 bootstrap / single trust domain 一致，无需同步修改。
- Closing commit：`docs: close M1 Windows assistant entry`。提交的最终 SHA 以 `git rev-parse HEAD` 与 closing report 为准，
  不将文档自身的 commit SHA 写回造成递归提交。工作树应干净；具备 merge to main 的条件，但本次不执行 merge/push。

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

以上为 M1 历史边界。M2A 已按 ADR-003 演进为独立 browser credential / owner；native 持有人仍共用 native owner。

## M1 已执行验证

命令均从仓库根目录执行。普通 Desktop 测试不启动真实 Ollama，也不读取用户当前 selection/clipboard。

| 命令 / 方法 | 结果 |
| --- | --- |
| `dotnet --info` / `dotnet --list-sdks` | PASS，正式 SDK 10.0.401 x64 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | Final closing PASS，2026-10-02，exit 0 |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | Final closing PASS，2026-10-02，0 warnings/errors，exit 0 |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | Final closing PASS，2026-10-02，31 tests，0 failed/skipped，exit 0 |
| `.\mvnw.cmd clean verify` | Final closing PASS，14 tests，0 failures/errors/skipped + package，2026-10-02 15:45:11 +08:00，exit 0 |
| `.\scripts\real-local-smoke.ps1` | 既有 M0 REAL PASS（2026-10-01 21:04:44 +08:00），task SUCCEEDED，resultLength 6；本次未重跑 |
| `git diff --check` / `git diff --cached --check` | PASS |
| tracked build/cache/log/credential 与 secret-pattern scan | Final closing PASS，76 tracked files，匹配 0；bin/obj/.vs/TestResults/Runtime credential/verification log 已忽略 |
| 3 个实际本机 token 值比对（不输出值） | Final closing PASS，76 tracked files / 12 verification 与 Maven test logs 泄漏匹配均 0；此前 2-token 扫描也通过 |
| Desktop source privacy / boundary scan | 无正文 logger/persistence、Ollama endpoint、keyboard hook 或 clipboard subscription |

Desktop 高价值覆盖：mock HTTP 提交/QUEUED→RUNNING→SUCCEEDED、提交期间取消、HTTP 401/404/429/403/503、
200 failed envelope 与 timeout/cancel、offline/missing credential、重复字段/未知状态/错误 UUID/oversize/Location/redirect fail closed、
脱敏 DTO/异常；copy 旧值/owner/丰富格式/恢复失败/取消恢复；native hotkey conflict + release；
单实例激活/释放；WPF XAML/manual/cancel controls；隔离 WinCred roundtrip/forget、bootstrap 私有 ACL/malformed file；
真实隐藏 UIA fixture 的 selected text/empty/PasswordBox/不支持保护属性拒绝；helper 进程启动与挂起 timeout/kill/reap。
fixture 的测试内容为合成文本，只读取该隐藏 fixture；copy 测试用 fake port，不改动用户剪贴板。

M0 新 smoke taskId：`bd37d699-bdba-45cf-9aae-9a760d63bfea`，loopback bind 与未认证 401 通过。
这是 Runtime/Ollama 回归，不是 M1 Notepad/Chrome acceptance；过程日志/脱敏 evidence 在忽略的 `.verification/`。

2026-10-02 实际 GUI 操作发现并修复健康检查兼容问题：Actuator 在没有 Accept 时返回
`application/vnd.spring-boot.actuator.v3+json`，Desktop 原先严格要求 `application/json`，误报 Malformed Runtime response。
客户端现在显式请求 `Accept: application/json`；新增 Actuator content negotiation 回归测试，保持响应校验严格。
真实窗口已显示 Runtime UP，真实手动输入翻译也通过。没有修改 M0 Runtime API 或配置文件。

## M1 已知限制

- UIA 随目标应用/provider/权限变化。本机 Notepad 11.2504.62.0、Chrome 154.0.8037.59 与 tray 已自动操作通过；
  Chrome 使用独立测试 profile、合成网页，未强制开启 accessibility。其他应用与版本尚未验证。
- Copy fallback 拒绝无法证明安全的 hosted/custom controls（包括浏览器 DOM fallback）、图片/富文本/大 clipboard；手动输入始终可用。
- 只恢复 Unicode 纯文本，不保存原格式/ownership；来源应用迟到 Copy 或焦点/owner 变化时可能无法安全恢复，UI 明确提示。
- 不控制 Windows 自身 clipboard history/同步；显式 Copy result 会进入系统剪贴板。
- Cancel 不保证 GPU 立即停止。POST 通信失败可能已接受但尚未知 taskId；不能声称已取消，Runtime 自身 deadline 有界。
- 凭据保护不隔离已攻陷的同用户进程；forget 不撤销 Runtime token；没有 per-client ownership 或自动 rotation。
- 没有 installer、auto-start/update、Windows Service 或 Runtime/Ollama lifecycle manager。

## M1 真实 Windows acceptance（Final：全部 PASS）

先确认 Ollama 与已有配置模型可用。在仓库根目录：

```powershell
java -jar target/personal-ai-workspace-0.1.0.jar
# 另一个终端：
dotnet run --project desktop/src/PersonalAiWorkspace.Desktop --no-build
```

首次点击“导入 Runtime 凭据…”选择 `.runtime/client-token`，确认 credential valid。
默认热键 Ctrl+Alt+Shift+T；在源应用触发前不要先激活 Assistant 窗口。
2026-10-02 用户在本次 closing 指令中明确确认剩余真实 Windows 验收没有问题。
下表记录最终 PASS 与证据来源；用户确认的场景不伪装为 Codex 在本次 review 中重新自动执行。
此前 Provider 地址覆盖测试曾被自动审批拒绝并未执行，Copy fallback 当时未完成真实验收；
这两个历史事实保持不变，本次通过用户真实验收确认补齐，不是重新解释此前未执行的测试。
不记录用户选区、译文、clipboard 正文或 token 原文；用户没有提供逐项时间/应用版本，不补造这些信息。

| 真实 Windows 验收 | 最终结果 | 验收证据来源 / 预期边界 |
| --- | --- | --- |
| Notepad selection | PASS | Codex 真实自动操作 + 用户确认；正式 hotkey → input 匹配 → Runtime/Ollama → resultLength 6 |
| Chrome selection | PASS | Codex 真实自动操作 + 用户确认；独立 profile 合成网页 DOM 选区 → 正式 hotkey → resultLength 6，未修改扩展 |
| password/protected text rejection | PASS | Codex 真实自动操作 + 用户确认；Chrome password IsPassword=true，拒绝 capture/copy，input/result 为空 |
| controlled-copy fallback | PASS | 用户真实 Windows 验收确认；一次受控 Copy 获取新文本，按原生控件/纯文本保守策略恢复或提示 |
| stale clipboard protection | PASS | 用户真实 Windows 验收确认；不将旧剪贴板当作本次选区 |
| manual input | PASS | Codex 真实自动操作 + 用户确认；手动输入 → Translate → 真实结果 |
| cancel | PASS | Codex 真实自动操作 + 用户确认；QUEUED → RUNNING 后 Cancel 返回 Cancelled、result 为空，不承诺 GPU 立即停止 |
| Runtime unavailable | PASS | Codex 真实自动操作 + 用户确认；Runtime 停止时明确 Runtime unavailable，无直接 Ollama/cloud fallback |
| Provider unavailable | PASS | 用户真实 Windows 验收确认；Runtime 正常而 Ollama offline 时明确 Provider unavailable |
| Provider restart recovery | PASS | 用户真实 Windows 验收确认；Provider 恢复后可重新 Translate |
| tray exit | PASS | Codex 真实自动操作 + 用户确认；托盘退出后应用进程退出 |
| global hotkey release | PASS | Codex 真实自动操作 + 用户确认；退出后同一 hotkey 可重新注册 |
| credential persistence | PASS | 用户真实 Windows 验收确认；重启后沿用 Windows Credential Manager 凭据，无需日常复制 token |
| privacy/log inspection | PASS | 用户真实 Windows 验收确认 + Codex 仓库/验证日志限定扫描；无正文或明文 token 泄漏 |
| 无选区 | PASS | Codex 真实自动操作；Notepad selection 收为 caret 后提示无选区、清空旧 input/result，未改动 clipboard |
| 单实例 / 窗口生命周期 | PASS | Codex 真实自动操作；重复启动一个实例，托盘打开/关闭窗口，关闭清空文本 |

此前由 Codex 自动操作的 GUI 验收在 2026-10-02 13:37–13:57 +08:00 实际操作已运行的 Windows 程序，
使用 Windows UI Automation 控件调用、合成页面/文件选区与 SendInput 正式热键；没有绕过客户端入口调用翻译 API。
自动操作场景的脱敏 evidence JSON 位于忽略的 `.verification/`，只记录状态、匹配布尔值、长度与版本，无捕获正文、译文或 token。
合成输入 fixture 与测试 Chrome profile 也在该忽略目录中；它们是测试素材，不是 Desktop 保存的用户历史。
测试 Chrome 窗口与 Notepad 合成标签页已关闭，Assistant 已重新启动并显示 Ready，input/result 为空；
默认 Runtime 在 127.0.0.1:8765 运行（该段为当时操作记录，不是本次 closing 的进程状态检查）。
上述自动操作保持原证据来源，剩余真实验收以本次用户确认补齐；全部 required acceptance 已 PASS。

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
- Native token 持有人仍共享 native owner；Browser 有独立 pairing/credential/Origin/owner。没有自动轮换。
- 结果最多 64 条、完成后约 2 分钟保留；重启失效，没有持久化。
- 模型质量仅有合成短文本 Translate/Summarize/Ask smoke；不代表长文忠实度、通用回答正确性、硬件吞吐/显存并发或生产可靠性。
- context 输入预算为保守 UTF-8 字节限制，未引入精确 tokenizer。
- provider readiness 是 tags/model presence，不能证明模型加载、GPU 资源和推理质量。
- CONNECT timeout 分类用合成 HTTP 异常验证；真实本地 socket 验证 connection refused，
  未人为操纵系统网络制造 connect timeout。
- 本机验证 Windows/Java 21；Linux/macOS Wrapper 与 POSIX credential 权限实现未做平台实测。
- test JVM 有 Mockito/Byte Buddy 动态 agent 提示，不影响验证结果；Runtime 无此 agent。

## 现有仓库与 Deferred Scope

本轮没有修改、复制或合并两个旧仓库，没有 Finance DB 访问。
Local AI Assistant 本轮仅只读核验；Finance 状态仍来自项目输入，未实时核验：

- Local AI Assistant released main `75bede1` 为历史 v0.4.1；当前验证 candidate `m2b2b-runtime-migration` / `ddfa4a0`，M2B-2B PARTIAL。B11/B12 仍 DEFERRED。
- Finance TEMPORARILY FROZEN / WAITING FOR REALITY SYNC；学校笔记本最新工作区 **UNVERIFIED**。
  不将 GitHub Remote 当作学校电脑最新事实。

M1 Translate Windows Entry 已 CLOSED — GO；真实 Windows acceptance 全部 PASS，自动操作与用户确认来源见上表。
Deferred：Finance integration/Gateway、Memory/Conversation/SQLite、Knowledge/RAG/embedding、
tool/agent framework、完整 WebView2/React Workspace、完整 Browser Closing Acceptance、cloud、streaming、多轮 Chat、
voice/vision/OCR、installer/auto-update/Windows Service、clipboard history/continuous monitoring、
backup/migration engine、同步及其他超出 M1 的能力。

M1 FINAL CLOSING REVIEW 已完成，closing commit 已 merge/push；M1 历史验收证据保持不变。
M1.5 / M2A / M2B-1 / M2B-2A 已 CLOSED — GO 且已 merge/push；本轮完成 M2B-2B-R1 Runtime security compatibility，完整 M2B-2B 仍 PARTIAL。
Finance Reality Sync 是未来 Finance 集成的前置条件，不是本轮任务。

## Git 交付

M0 远端基线：`main` / `origin/main` = `5d71d11144fd6e066638f29ea2464fdc16ea332a`，
提交主题 `feat: bootstrap personal AI workspace runtime`，已推送到上述 origin。
M1 closing commit `6d17ad7` 已 fast-forward merge 到 `main` 并成功 push；2026-10-02 fetch 确认本地 main 与 origin/main 一致。
M1.5 implementation commit `22c45de` 已发布；M2A implementation commit `9d20a9a` 已发布，2026-10-02 fetch 确认 main / origin/main 一致。
M2B-1 implementation commit `d606472` 已发布。
M2B-2A implementation commit `25dc1df` 已 merge/push；2026-10-03 本轮 fetch 确认 `main == origin/main == 25dc1dfc9a103b030267f18d93059316f0ce008d`。
M2B-2B-R1 在 `m2b2b-r1-chrome-get-security` 仅本地提交，不 merge/push。
精确当前 HEAD 与工作树状态通过 `git rev-parse HEAD` / `git status --short` 获取。
生成的 credential、验证日志及 build outputs 被忽略，不进入 Git。
