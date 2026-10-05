# Personal AI Workspace

Personal AI Workspace 是 local-first Windows AI 工作区：Assistant 单轮问答/摘要、持久多轮 Conversations、用户手动管理的 Memory、Translate 和 Settings。所有推理由本机 Ollama 执行；没有云端 fallback、自动 Memory 或同步。

**M5 — Unified Main Workspace UI：CLOSED — GO。M5A / M5B / M5C / M5D / M5E — CLOSED — GO。**
**M5 FINAL CLOSING — APPROVED — GO。** Architecture / Final Closing Review 已正式批准；Main Workspace 为主产品入口，Portable Windows Release Bundle — validated。Published test baseline：Java **105 PASS** / Desktop **258 PASS** / Frontend **106 PASS**。最终 full automated regression 在 Architecture / Final Closing Review 前只执行一次。范围、包身份和真实 Windows 证据见 [M5E Closing Report](docs/milestones/M5E-CLOSING-REPORT.md)，当前正式状态见 [STATUS](docs/STATUS.md)。ADR-001..010 — Accepted。

No full test suite or Windows acceptance was rerun during Final Formal Delivery.
The published evidence is inherited from the approved M5E / M5 Final Closing Candidate.

## 产品与架构

正常启动、第二次启动、launcher、托盘双击与 Open Personal AI Workspace 都打开或激活 Main Workspace；默认 Assistant 页面。缺少凭据仍打开工作区，Settings 明确显示 Missing，用户通过原生凭据管理显式导入。

React → 固定可信 WebView2 origin → typed allowlisted WPF bridge → application-owned RuntimeClient → Java Runtime / SQLite / Ollama。
Runtime 是 Memory 与 Conversation 的唯一持久化真相；React 没有直接 Runtime HTTP、凭据或第二数据库。Memory 由用户逐次明确选择；下一次 Ask / Turn 不自动继承。

React Main Workspace 包含 Assistant、Conversations、Memory、Translate、Settings。WPF 拥有应用生命周期、single instance、tray、WebView2 host/security、快捷键、UIA/controlled clipboard、helper、credential flow、MemorySelectionWindow、Browser Pairing、Memory Backup、Workspace Backup 与原生 dialogs/confirmations。Runtime 拥有 durable Memory / Conversation、TaskManager、context assembly、provider policy、Ollama execution、backup semantics 与 SQLite。

Legacy Assistant 的正式角色为 Quick Assistant / fallback，保留原生快捷工作流，不是主产品窗口。Native maintenance allowlist 恰好为 `native.openCredentialFlow`、`native.openBrowserPairing`、`native.openMemoryBackup`、`native.openWorkspaceBackup`。`native.openLegacyAssistant`、`native.openConversations`、`native.openMemory` 已退休，不再是 WebMessage authority；WPF fallback implementation 保留。

普通 Assistant Ask = stateless；Conversation = durable multi-turn；Memory = manual/user-controlled；explicit Memory = exact revision；Browser = Translate-only。

## 开发运行

构建需要 Java 21、Maven Wrapper、.NET 10 SDK、Node/npm；推理需要 Ollama 和已安装的 `qwen3.5:4b`。React 页面需要 Microsoft Edge WebView2 Evergreen Runtime。

```powershell
.\start-workspace.cmd
# 或指定一个私有 Workspace 数据目录：
.\scripts\start-workspace.ps1 -DataDirectory 'C:\your-private-workspace'
```

Repository developer launcher 可构建源码；产品 release launcher 单独维护。

## 构建与运行 portable 包

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-windows.ps1
.\artifacts\PersonalAiWorkspace-win-x64\start-workspace.cmd
```

包包含 self-contained win-x64 Release WPF、production React、应用 Runtime JAR、launcher、用户 README、manifest 与 SHA-256。普通运行不需要 Node/npm、Maven、Git、Visual Studio、.NET SDK/runtime 或源码仓库。Java 21、Ollama/configured model、WebView2 是用户前提，不自动下载。

已验收固定包的 payload identity 为 implementation commit `bcf7d5f8e20c05e516ae22cbff40d17a18567222`。后续 historical closing commit `82fb7d4cf7ae2f7b518a79b64c5c4c828f030e3b` 与独立 architecture approval commit 只修改 documentation；package payload identity ≠ final docs-only repository HEAD，不因文档批准重建包。本次 Formal Delivery 仅 merge/push source main；无 tag、GitHub Release 或 ignored local package 上传。Package artifact publication 留待单独决定。

输出位于 ignored `artifacts/`；存在的候选不会被脚本覆盖，可用 `-OutputName` 另建。已有 verified frontend/JAR 的开发流水线可传 `-FrontendPrebuilt -RuntimePrebuilt`；Desktop 仍须 self-contained publish。

包目录可移动；状态不写入包。首次在 Settings 点击凭据管理，在原生窗口明确选择 `%LOCALAPPDATA%\PersonalAiWorkspace\RuntimeState\Auth\client-token`。launcher 只以该私有文件认证 Runtime，不导入 Windows Credential Manager。健康现有 Runtime 必须同时通过 readiness 与认证契约才能复用；未知端口占用 fail closed。

## 存储、隐私与限制

Workspace 默认 `%USERPROFILE%\.personal-ai-workspace\data`；release Runtime auth/browser registry/logs 默认 `%LOCALAPPDATA%\PersonalAiWorkspace\RuntimeState`，与数据目录、包目录分离并设置 owner-only ACL。WebView 使用独立 account-local InPrivate profile。

数据库与备份都是明文；OS 账户与权限提供边界，不承诺加密或取证级擦除。Workspace Backup 包含 Memory + terminal Conversation，恢复到新/空目录；用户明确以该目录重启 Runtime 后使用恢复数据。关闭 Main Workspace 保留托盘，tray Exit 退出 Desktop；Runtime/Ollama 继续由外部管理。

当前交付是 unsigned portable folder，提供损坏检测而非签名/真实性保证；无 installer/MSI/MSIX、code signing、updater、bundled JRE/Ollama、云同步。Java 21、Ollama、configured model、WebView2 Evergreen required；plaintext local DB / plaintext backups 是 accepted limitations，不是 unfinished M5 work。Knowledge、semantic retrieval/RAG、Finance、Agent/Tools、streaming、edit/regenerate/branching、automatic Memory 不在产品范围内。

## 已批准 milestone 历史

以下保留 M5A/B/C/D 和更早阶段的历史事实；其当时入口与权限由上方 M5E 当前行为取代，不追改 historical closing reports。Published M5D baseline：Java 105 / Desktop 251 / Frontend 103 PASS，ADR-001..010 Accepted。
[M5A](docs/milestones/M5A-CLOSING-REPORT.md) · [M5B](docs/milestones/M5B-CLOSING-REPORT.md) · [M5C](docs/milestones/M5C-CLOSING-REPORT.md) · [M5D](docs/milestones/M5D-CLOSING-REPORT.md)。

## M5C approved baseline history

已批准的 React Main Workspace Conversations 支持 ACTIVE / ARCHIVED、paged list、create、manual rename、paged durable history、
per-turn explicit Memory、send、PENDING 观察与 cancel、archive / unarchive / delete、durable failure states 和 reload / reopen / restart recovery。
Runtime = sole durable Conversation truth；React 只负责 presentation、bounded current pages、draft、selection、focus/loading/error。
列表每页 10 Conversations，历史每页 10 Turns；默认读取 page0，必要时再读取 latest page，不全量预加载 1000 Conversations / Turns。
WebView reload 时 session rotates，重新读取 durable detail、发现 PENDING 并观察 terminal，无 replay。
Main Workspace reopen 不自动 cancel、不 resend，从 Runtime 重读；Runtime restart 将 stale PENDING 置为 FAILED / EXECUTION_INTERRUPTED，无 provider replay。

生产链保持 bundled React → typed allowlisted WPF bridge → application-owned RuntimeClient → Runtime / SQLite / Ollama。
M5C 新增 11 个 explicit `conversations.*` 方法，完整名单见 [current architecture](docs/architecture/current-architecture.md)。
Bridge 保持 typed、versioned、allowlisted、origin/session checked、strict payload schemas 与 session-authorized IDs；无 generic CRUD / HTTP / SQL / task-by-ID proxy。
raw Runtime taskId 不暴露给 React；React cancel 只传 conversationId + turnId，WPF Host 通过真实 Runtime detail 验证并绑定内部 taskId 后调用 existing cancel API。
普通 bridge response 仍为 64 KiB；只有 `conversations.get` 的 Conversation detail 为 1 MiB hard ceiling，基于 legal page worst-case，automated + real serialization/render 验证，无 silent truncation。
Memory 是 explicit per-turn，经 native MemorySelectionWindow 显式预览/选择，max 4、exact revision 以 decimal string 跨 bridge。
Accepted admission 消耗 selection，下一 Turn 无 automatic carry-over；明确 stale 要 Review / Change 或 Clear，未知结果保留草稿、清除授权、刷新 durable history，不自动重发。
Historical refs 可以 dangling；UI 只显示历史 reference metadata，不查询当前 Memory 伪造历史 title/body。

Published M5C test baseline（Architecture Review 已批准）：Java **105 PASS** / Desktop **233 PASS** / Frontend **66 PASS**，0 failure/error/skip。
Approval sync / formal delivery 记录既有验收结果，本轮不重跑测试或真实 Windows gates。
**REAL WINDOWS PINYIN — PASS**：production Conversation textarea 中 Pinyin → committed Chinese → conversations.send → exact durable USER → exact provider USER。
**REAL MULTI-TURN OLLAMA — PASS；DURABLE RELOAD / REOPEN / RESTART RECOVERY — PASS。**
真实 Release WPF / WebView2 / bundled React / Runtime / SQLite / Ollama，多轮上下文、逐轮 Memory、取消、failure、生命周期、恢复与隐私均 PASS。
M5A shell/security、M5B React Assistant/Summarize/Memory Ask/Translate及真实 Assistant Pinyin、native hotkey/UIA/clipboard、
Memory CRUD、Workspace / Memory-only recovery 和 Browser Translate-only 回归 PASS。Browser 回归为 synthetic HTTP + real Ollama，不声称新 Chrome GUI 验收。
范围、175 项验收检查记录、性能及限制见 [M5C Closing Report](docs/milestones/M5C-CLOSING-REPORT.md)。该报告保留形成时 IMPLEMENTED / LOCAL ACCEPTANCE PASS、CLOSING CANDIDATE — GO、M5 — OPEN 的历史快照；review 后当前正式状态见 [STATUS](docs/STATUS.md)。

Assistant 继续 ordinary Ask / Summarize / explicit Memory Ask，保持 single-turn/stateless；Desktop Translate 支持 Submit / Cancel / Result / native Copy。
Conversations 为 durable multi-turn domain，独立于 M5B transient operation registry。
会话编辑器支持 Enter 换行、composition 完成后 Ctrl+Enter 或 Send；切换会话丢弃草稿并清除本轮 Memory，不取消已接受执行。
Archive：ACTIVE → ARCHIVED，阻止新 Turn，但不 cancel accepted PENDING execution；Unarchive 使用同一 Conversation ID 回到 ACTIVE。
Delete 是 physical irreversible delete，Runtime conflict 在 PENDING 时阻止删除；无 cancel-then-delete / force delete。删除 dialog 默认聚焦 Cancel。
全部 legacy native windows 保留，包括 `native.openConversations` fallback；M5C 当时 Memory management / Settings maintenance 仍为原生入口。
Conversation content 仅作 in-memory presentation；React 不拥有 durable transcript、second database、IndexedDB Conversation truth 或 localStorage Conversation history。
React 不直接请求 Runtime；localStorage 仅 theme，无 IndexedDB / sessionStorage / service worker domain state，无 content in URL；plain text rendering，UDF privacy scan PASS。
Browser companion 继续 Translate-only；ADR-001..010 **Accepted**，Java production / schema / backup format 无改动。

M5C Release acceptance 复现：先构建 Runtime 与 frontend，确保 8765/18766/11435 空闲和本机 Ollama 模型可用：

```powershell
.\mvnw.cmd clean verify
npm --prefix desktop/frontend run build
python -X utf8 scripts/conversations-workspace-smoke.py
```

脚本使用任务专用临时 Runtime/data、临时 WinCred target、内存中的计数 relay；不停止既有 listener，不修改用户凭据。
新 Conversation editor 必须通过真实 Windows 拼音。`--manual-ime` 提供同一 production 页面中的真实输入步骤，未成立时只报告 PARTIAL。
只保留检查/计数/性能，正文与凭据不进入 Git/evidence；InPrivate cleanup 与 marker scan 不承诺 forensic erase。
M5B 回归：`python -X utf8 scripts/assistant-translate-smoke.py`。

已批准历史：[M5A Closing Report](docs/milestones/M5A-CLOSING-REPORT.md)、[M5B Closing Report](docs/milestones/M5B-CLOSING-REPORT.md) 保留原快照。
M5A shell 的真实 IME 当时 deferred，M5B 的生产 Assistant 编辑器已正式通过；M5C implementation acceptance 对新 Conversation 编辑器单独完成真实验收。
M4 — User-Controlled Conversation Foundation **CLOSED — GO**，包含 durable Conversation、multi-turn execution、explicit per-turn Memory 与 logical Workspace recovery。
[M4C Closing Report](docs/milestones/M4C-CLOSING-REPORT.md) 与 [ADR-007](docs/ADR/ADR-007-logical-workspace-backup-restore.md) 保持原样。
旧窗口退役、installer/updater/Java bundling、Finance/Knowledge/RAG/Agent、Browser Conversation、
streaming、edit/regenerate/branching、automatic Memory、Markdown、attachments 与跨设备同步均继续 deferred。

独立、local-first 的共享 AI Runtime。正式发布须成功实时 fetch、核验远端基线、fast-forward-only merge、push main 与 post-push fetch。
M5A 证据与限制见 [M5A Closing Report](docs/milestones/M5A-CLOSING-REPORT.md)；M4 恢复历史见 [M4C Closing Report](docs/milestones/M4C-CLOSING-REPORT.md)；当前阶段唯一事实来源为 [STATUS](docs/STATUS.md)。

**M2 — Browser Convergence：CLOSED — GO**。
**M3 — User-Controlled Memory Foundation：CLOSED — GO；M3A / M3B / M3C-1：CLOSED — GO**。
**M3C-2 — Versioned Logical Export / Restore：CLOSED — GO**。
当前阶段、验证证据与遗留项的唯一事实来源：[docs/STATUS.md](docs/STATUS.md)。

Published M3 main: `dd069ec5ec4e053a85e8f2f6de6940940cf9f83e`（status sync）；M3 closing commit `c4e6c669088bed437e10af3db4c11e508714a911`。
Implementation: `9d04b4a0c139ff2ccb9126f89ff8e96061eb46ad`。
main 已 fast-forward / pushed，feature branch 已 pushed；publication 完成时 working tree clean；no tag/release。
Java **63 PASS** / Desktop **106 PASS**；Real Recovery **PASS**；M3 Integrated Acceptance **PASS**。
No GitHub Actions CI configured. workflows = 0；published main SHA runs = 0。
Finance Reality Sync remains a separate prerequisite.

Windows Assistant 与 Browser Extension 已收敛到同一 authenticated Personal AI Runtime。
Windows Native 拥有 Translate / Summarize / Ask；Browser Translator v0.5.0 仅拥有 Translate（含 Batch Translate）。
Provider Policy、prompt、model profile、generation config 与 AI execution 均由 Runtime 管理，最终使用本机 Ollama。

M2 closing 历史发布基线（当时 docs-only closing 前）：

- Personal AI Workspace：`ad6e8995cf482517be11602c795b1d6331b68e4b`。
- Local AI Assistant：`b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0`；**Browser Translator v0.5.0 — GO / M2B-2B — CLOSED — GO**。

收口范围、架构、安全与既有验收证据见 [M2 Closing Report](docs/milestones/M2-CLOSING-REPORT.md)。
M2 closing 当时只同步文档，没有重新执行历史 Java/Desktop/Chrome acceptance；B11 inline BR layout / B12 mutation debounce starvation 继续 **DEFERRED**。
Post-M2 Test Suite Simplification 与 M3 已 CLOSED — GO；[M3 Closing Report](docs/milestones/M3-CLOSING-REPORT.md) 保留形成当时的综合验收、边界与 Git 状态；当前 M3 publication 已完成。

## M4C — Workspace Logical Backup / Restore

Assistant 的 **Workspace Backup…** 打开最小原生 WPF 窗口：Export、Choose / Validate、单独 Restore。
备份包含 ACTIVE/ARCHIVED Memory 和 terminal Conversation history，属于**明文个人数据**；没有加密或密码。
SHA-256 用于检测损坏，不提供真实性证明。预览只显示版本、时间和计数。

| Native-only API | Contract |
| --- | --- |
| GET `/api/v1/workspace/backup` | 流式 UTF-8 Workspace JSON；不接受 export path |
| POST `/api/v1/workspace/backup/validate` | 原始 backup body；完整验证并返回安全 metadata |
| POST `/api/v1/workspace/backup/restore` | 原始 backup body；`X-Workspace-Restore-Target` 为 absolute target 的 UTF-8/unpadded base64url |

新格式 `personal-ai-workspace.workspace-backup` format1；Memory section1 / Conversation section1；SQLite仍v3。
使用同一 SQLite read snapshot，拒绝 PENDING；不导出/恢复 taskId，恢复启动不重放任务。
Historical Memory ID/revision/position 保留，包括已删除 Memory 的引用；新 Send 仍须显式选择 Memory。
只恢复到新/空目录，通过 private staging、单事务重建、FTS/read-back/digest/integrity 检查后 no-replace 发布。
成功后须显式使用 restored directory 启动 Runtime/Desktop；不合并、不切换当前数据。

Desktop64KiB缓冲、Runtime逐条解析；文件/body上限 **101,393,896,192 bytes**，根据现有1000x1000 Turns容量推导。
完整验证需要临时磁盘及额外流式上传，backup请求最长2小时；大导出read transaction可能短暂阻塞SQLite writes。
完整101GB理论数据集未实测；单Conversation1000Turns最坏转义约94MiB已验证。
Memory-only `/api/v1/memory/backup` contract 与fresh-v1 restore独立保留，不能用于恢复Conversation。
详细字段/canonical规则/路径策略见 [ADR-007](docs/ADR/ADR-007-logical-workspace-backup-restore.md)。

```powershell
.\mvnw.cmd clean verify
dotnet test desktop/PersonalAiWorkspace.Desktop.slnx
python -X utf8 scripts/workspace-backup-smoke.py
```

真实恢复脚本只使用隔离合成数据，删除original Workspace后，以真实WPF/HTTP/SQLite/Ollama验证恢复与续聊。
Final closing baseline：Java105/Desktop136 PASS；Browser继续Translate-only，普通Ask继续stateless。
M4 — CLOSED — GO；当前 Main Workspace shell 状态见页首 M5A 与 STATUS。

## M4B — Multi-turn Conversation

Assistant 的 **Conversation…** 打开最小原生 WPF modal：New Conversation、选择 ACTIVE Conversation、
分页纯文本 USER/ASSISTANT/执行状态、Send、Use Memory… / Clear Memory、Cancel、Refresh / Reopen、Archive。
Runtime 持久化所有 Turn；Desktop 窗口关闭清空正文与 selection。普通 Ask 继续 single-turn/stateless，Translate/Summarize/Memory 原契约保留。

新增 native-only `POST /api/v1/conversations/{id}/turns`：

```json
{"message":"Current user instruction","memories":[{"id":"b58ab357-456f-4c44-950b-a9c083e8ba8a","revision":1}]}
```

无 selection 时省略 `memories` 或传 `[]`。示例 ID 必须替换为当前实际 ACTIVE Memory/exact revision；不接收模型、profile、system、role、assistant 或 arbitrary history。
202 返回 `conversationId/turnId/taskId/status`、只含计数/sequence/长度的 context admission metadata，Location 指向既有 Task API。
GET/DELETE `/api/v1/tasks/{taskId}` 用于 polling/cancel，task capability=`conversation`、promptVersion=`conversation-v1`、profile=`chat.balanced`。
Conversation detail 承载 durable history，不依赖 Task retention；Turn 新增 `taskId/failureCode/memories`（仅 Memory ID/revision/position）。
Memory reference 不设到 Memory source 的 FK，不阻止 Memory edit/archive/physical delete，不复制正文。

验证与 exact-revision snapshot → 事务保存 PENDING + USER + selection metadata → commit → shared TaskManager。
同一 Conversation 只允许一个 PENDING execution，第二次 Send 返回409；保持线性执行与稳定 sequence。
成功在 TaskManager 终态锁内以一个 DB transaction 插入 ASSISTANT、SUCCEEDED、parent updatedAt。
提交失败/queue full/policy denial 保存 FAILED 和 USER；cancel/timeout 保存对应状态，无虚构 Assistant 错误消息。
完全 storage outage 时 Task 返回受控 storage failure，durable PENDING 留待重启 fail closed，不声称成功。
启动将残留 PENDING → FAILED / EXECUTION_INTERRUPTED，不重新调用模型、不自动 retry。
Archive 不取消已有任务；PENDING 阻止 physical DELETE，须先 Cancel/等待终态。Polling 不更新 updatedAt。

Runtime-owned system → 当前显式 Memory reference data → 最近完整 SUCCEEDED exchanges → 当前 USER 一次。
FAILED/CANCELLED/TIMED_OUT/PENDING 历史、title/lifecycle/error metadata 均不进入 inference。
预算延续 `chat.balanced` 8192 context /2048 output /3000 serialized UTF-16 units；UTF-8 input 上限最多5632，
另为 escaped system/model 和 wire envelope 计算保守 reserve。必需 current/Memory 超限400，不截断或偷偷丢掉 Memory；
history 只整轮纳入，从最旧 successful Turn 丢弃。Persisted history 和 inference window 是不同范围。
选中的 Memory snapshot 仍沿用 M3 exact-revision validation；执行期间编辑或删除不改变已接收 snapshot。每次新 Send 默认无 Memory。

Workspace SQLite additive v2 → v3 migration 保留 M4A/M3 source；Memory logical backup/restore 仍 format1/schema1，恢复构造 fresh v1 后由启动升级。
**Memory export 不包含 Conversation，不能用于 Conversation recovery。** Dedicated Retry = **NOT IMPLEMENTED BY DESIGN**；用户再次尝试须明确发送新 Turn。
Browser 仍 Translate-only；无 edit/regenerate/branching、自动 Memory/retrieval、streaming、Knowledge/RAG、Finance、Agent、React/WebView2 Main Workspace。

```powershell
.\mvnw.cmd clean verify
python -X utf8 scripts/conversation-execution-smoke.py
```

真实 smoke 使用隔离临时数据和凭据，驱动生产 WPF entry/controls、HTTP、SQLite、Ollama，验证多轮、逐轮 Memory、cancel、reopen/continue、失败与启动无重放。
真实 timeout 未稳定制造；自动 queue/execution timeout 测试是主证据。完整结果见 [M4B Closing Report](docs/milestones/M4B-CLOSING-REPORT.md)。
M4B历史closing时尚无Conversation portable backup；当前恢复能力与证据见上方M4C。M4现已通过Architecture/Closing Review，CLOSED — GO。

## M4A — Conversation Domain & Persistence（历史 closing 基线）

Conversation 是独立的 Workspace-owned durable domain，**Conversation ≠ Memory ≠ transient Task history**。
新增 SQLite Conversation lifecycle 与 Turn/Message persistence；不自动提取、写入、搜索或选择 Memory。
普通 `POST /api/v1/ask/tasks` 仍为 single-turn / stateless，未接入 Conversation。

同一私有 data directory 的 `memory.db` 使用事务式 `PRAGMA user_version` v1 → v2 migration；
Memory source、revision、FTS/search 与 backup contract 不变。Memory export 仍 format1/schema1/source-only，
restore 仍生成 fresh M3 schema v1；下一次 Runtime 启动升级至 Workspace v2，Conversation tables 为空。
**Memory export 不包含 Conversation，不能用它恢复 Conversation。** SQLite 是受 OS 账户权限保护的本地明文，非加密。

Conversation：UUID、trimmed title（默认 `New conversation`，非空，输入最多160 code points）、
ACTIVE/ARCHIVED、createdAt/updatedAt。Turn：UUID、conversationId、sequence（1起）、
PENDING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT、timestamps、1个USER message与0..1个ASSISTANT message。
成功才有 Assistant message；失败/取消/超时只保存状态，错误文字不写成 Assistant message。
Message 只有 USER/ASSISTANT；正文保留原文，非空，最多8192 UTF-16 units和8192 UTF-8 bytes。
没有SYSTEM/TOOL/Memory message；已保存Message没有edit API，数据库trigger拒绝UPDATE。
历史严格线性，唯一 `(conversation_id,sequence)`；没有parent/branch/variant字段或regenerate。

| Native-only API | Contract |
| --- | --- |
| POST `/api/v1/conversations` | `{}` 默认标题，或 `{ "title": "Synthetic conversation" }`；201 + metadata/Location |
| GET `/api/v1/conversations` | `status=ACTIVE` 默认，或ARCHIVED；`page=0&limit=10`；metadata items/total/page/limit |
| GET `/api/v1/conversations/{id}` | `page=0&limit=10`；conversation metadata + turns/totalTurns/page/limit；sequence ASC |
| PATCH `/api/v1/conversations/{id}` | `{ "title": "Renamed conversation" }`；只rename |
| POST `/api/v1/conversations/{id}/archive` | ACTIVE → ARCHIVED；重复操作幂等于状态 |
| POST `/api/v1/conversations/{id}/unarchive` | ARCHIVED → ACTIVE |
| DELETE `/api/v1/conversations/{id}` | 204；事务/FK cascade物理删除Conversation、Turns、Messages |

list按updatedAt DESC/id ASC；分页limit 1–10，总Conversation<=1000，每Conversation<=1000 Turns。
每页最多10 Turns，含最坏JSON escaping的预算仍低于Desktop既有1MiB响应上限；不静默截断正文。
分页不是跨请求snapshot；每次详情/列表读取使用单个数据库事务。
Internal Java domain operations创建USER turn、完成ASSISTANT response或终止Turn；没有这些操作的HTTP写入接口。
同一Conversation并发创建通过BEGIN IMMEDIATE + UNIQUE sequence保护，终态不可覆盖；归档阻止新增Turn。
修改无revision framework，native并发rename/lifecycle按SQLite事务提交顺序生效；M4B retry/execution未实现。
400 CONVERSATION_INVALID，404 CONVERSATION_NOT_FOUND，409 CONVERSATION_CONFLICT/CONVERSATION_LIMIT_EXCEEDED，
503 CONVERSATION_STORAGE_UNAVAILABLE；沿用code/message/phase，错误不含正文、路径、SQL或cause。

Desktop只增加Core DTO/RuntimeClient支持，复用native bearer、loopback HTTP、安全解析与脱敏诊断；WPF不新增Conversation UI。
Browser仍Translate-only，Conversation所有route/method/preflight/originless访问拒绝。
没有multi-turn AI execution、context assembly、automatic Memory/retrieval、RAG、Knowledge、Agent、Finance、
React/WebView2 Main Workspace、Browser Conversation access、edit/regenerate/branching、Conversation logical backup/restore/portable recovery。

```powershell
.\mvnw.cmd clean verify
python scripts/conversation-storage-smoke.py
```

该smoke使用isolated synthetic data、test-only internal fixture和四个独立Runtime进程；
不调用模型，不接触用户Memory/WinCred；证据只含IDs/status/counts/PASS-FAIL。
Restart durability不是backup/recovery验收。**M4A CLOSED — GO ≠ M4 CLOSED — GO**；
M4 Final Closing仍被 **M4C Conversation Backup / Restore Gate** 阻塞。
完整范围、验证与遗留项见 [M4A Closing Report](docs/milestones/M4A-CLOSING-REPORT.md)。

## M3A — Native Memory API

Runtime 已有独立 SQLite Memory persistence；Desktop 支持显式 Memory 管理、逐次选择 Memory Ask、versioned logical Export / Restore。
普通 Translate / Summarize / Ask 保持原契约，Ask 仍 single-turn / no history / no memory / no tools。
验收：[M3A Report](docs/milestones/M3A-MEMORY-STORAGE-REPORT.md)；决策：[ADR-004](docs/ADR/ADR-004-user-controlled-memory-storage.md)。

配置 `workspace.data-directory`（环境变量 `WORKSPACE_DATA_DIRECTORY`），默认 `${user.home}/.personal-ai-workspace/data`。
目录必须专用于个人数据，位于项目、build/logs、token/Browser credential registry 之外；Runtime 启动时收紧并验证账户私有权限。
`memory.db` 和 SQLite 的任何 WAL/SHM/journal 均为敏感个人数据。
**SQLite currently stores local plaintext data protected by OS account/filesystem boundary.** Owner-only ACL 不是加密。

所有 Memory 路由只接受 native bearer，无 web Origin；Browser credential/Memory preflight 一律拒绝。
普通 body cap 32KiB 保护 POST/PUT/PATCH/DELETE；仅 native backup restore 有独立预算，见下文。显式保存只接受 MANUAL source、PREFERENCE / PROJECT_NOTE type。

| API | JSON body / query |
| --- | --- |
| POST `/api/v1/memory/items` | `{ "type": "PROJECT_NOTE", "title": "Synthetic note", "content": "Synthetic text" }`；201 + item/Location |
| GET `/api/v1/memory/items/{id}` | 单条读取 |
| GET `/api/v1/memory/items` | `status=ACTIVE`（默认）、`type`、`query`、`page=0`、`limit=20`（max100）；items/total/page/limit |
| PUT `/api/v1/memory/items/{id}` | `{ "expectedRevision": 1, "type": "PROJECT_NOTE", "title": "Updated note", "content": "Updated text" }` |
| POST `/api/v1/memory/items/{id}/archive` | `{ "expectedRevision": 2 }` |
| POST `/api/v1/memory/items/{id}/restore` | `{ "expectedRevision": 3 }` |
| DELETE `/api/v1/memory/items/{id}` | `{ "expectedRevision": 4 }`；204 |
| POST `/api/v1/memory/index/rebuild` | 无需正文；204，source records/revisions 不变 |

修改/归档/恢复每次 revision+1（重复 lifecycle command 也增加），过期 revision →409 MEMORY_REVISION_CONFLICT；
不存在→404 MEMORY_NOT_FOUND；容量/大小超限→409 MEMORY_LIMIT_EXCEEDED；非法值→400 MEMORY_INVALID；
storage/schema不可用→503受控 Memory code。JSON/binding错误保留 INVALID_REQUEST。
总 ACTIVE+ARCHIVED1000，title160 code points，content2000 UTF-16 units AND8KiB UTF-8 bytes；不截断/淘汰。
title/content 去空白检查非空，但保存原始正文/换行。搜索 query<=160 code points，literal case-sensitive substring；
>=3 code points 用FTS5 trigram，短查询instr。归档仅通过 `status=ARCHIVED` 返回；稳定排序 updatedAt DESC/id ASC。
删除后新请求不可查询/搜索到；不承诺 forensic erasure 或 revision history。

真实 packaged restart smoke 不需要 Ollama，自动使用隔离临时 Memory/auth 目录和合成文本：

```powershell
.\mvnw.cmd clean test package
python scripts/memory-storage-smoke.py
```

M3历史 closing Java **63** / Desktop **106** PASS（保留原 Java54 / Desktop94）；M3 最终真实 WPF/HTTP/SQLite/Ollama / logical recovery 综合验收 PASS。

## M3B — Desktop Memory Management

M3B 管理窗口的行为和独立验收如下；M3C-1 的 Ask selector 与管理窗口职责分离。

Assistant 的 **Memory…** 按钮打开独立单实例 WPF modal。默认 Active，支持 Archived/type filters、显式 Search/Enter、
每页20条和 Previous/Next；列表只显示 title/type/status/updatedAt/revision，选择后 GET 完整条目。
New 与编辑输入不会写入 Runtime；只有 **Save** 才创建/更新。Archive/Restore 是显式按钮，保留未保存编辑；Delete 需确认，
删除不保证磁盘取证级擦除。切换条目、New、Reload、关闭时保护未保存修改。
revision conflict 保留本地文本并禁止继续修改服务器；须显式 Reload，有未保存编辑时先确认丢弃，不自动重试或覆盖。
关闭取消 HTTP、清除 title/content/query/list；不建立 Desktop history/cache。管理窗口不向 Ask 传递 Memory；逐次选择使用独立只读 selector。

报告：[M3B Desktop Memory Management Report](docs/milestones/M3B-DESKTOP-MEMORY-MANAGEMENT-REPORT.md)。
真实验收使用临时数据与测试凭据，不访问 Windows Credential Manager 或用户 Memory；不需要 Ollama。
运行前 `127.0.0.1:8765` 必须空闲，脚本不会停止现有 Runtime。

```powershell
.\mvnw.cmd package -DskipTests
python scripts/desktop-memory-smoke.py
```

验收 harness 位于 `desktop/acceptance/`，不属于产品入口或默认 solution tests；脚本自动 build，再驱动真实 WPF controls。

## M3C-1 — Explicit Memory Ask

仅在 **Ask AI** 显示 **Use Memory…**。只读 selector 默认 Active，支持显式 Search/type filter/Previous/Next。
逐条查看完整 title/content 后 Add（最多4条），再 **Use selected**；Ask 显示本次 selected count、title/type/revision，支持 **Review / Change Memory…** 和 **Clear Memory**。
Review / Change 从新选择开始；Cancel 保留 Ask 原选择。Translate/Summarize 不显示该入口。

有 selection 调用独立 native-only `POST /api/v1/memory/ask/tasks`，正文示例：

```json
{
  "question": "What is the synthetic project codename?",
  "memories": [{ "id": "b58ab357-456f-4c44-950b-a9c083e8ba8a", "revision": 1 }],
  "profile": "chat.balanced"
}
```

ID 为示例，必须是实际已保存的1–4条唯一ACTIVE Memory，revision须>0；不能附带title/content/status/source。
profile省略/null使用chat.balanced，其他值拒绝。202返回现有Task envelope与Location，capability仍ask，promptVersion为memory-ask-v1；GET/DELETE沿用`/api/v1/tasks/{id}`。
0条请求拒绝；Desktop 0 selection继续原`/api/v1/ask/tasks`，body仅question/profile，prompt仍ask-v1。

Runtime在admission前一个SQLite读事务中逐条核对exact revision；任一编辑/归档/删除→409 `MEMORY_SELECTION_STALE`，整体不接收任务。
提示 **Selected Memory changed. Review and select Memory again.**；不会偷偷替代、partial使用或retry，须显式reselect或Clear。
accepted task uses admission-time Memory snapshot；后续Memory改变不修改或cancel已接收任务。

question+Memory+JSON wrapper/escaping共同进入原chat.balanced预算：3000 UTF-16单位及5632 UTF-8字节保守输入上限，system<=512字节。
即使1条也可能超限；4条短内容可以通过。超限400 INVALID_REQUEST，须减少selection或缩短内容，不truncate/drop/summarize/扩大预算。
Memory作为untrusted user-authored reference data放在JSON user input，不进入system。结构和policy边界降低instruction confusion，不保证prompt injection免疫或事实正确。

每轮terminal、Clear、Action切换、窗口close/cleanup/exit清除selection；admission失败可保留，stale锁定submit至重新选择。
已接收任务的通信失败也清除selection。问题/回答/selection/snapshot/prompt/provider payload不持久化，无正文日志，Task短期存内存且重启消失。
Browser仍Translate-only，不能调用Memory Ask。没有自动Memory检索/选择/注入/保存/提取；普通Ask不接入Conversation，没有RAG。

报告：[M3C-1 Report](docs/milestones/M3C-1-EXPLICIT-MEMORY-ASK-REPORT.md)；决策：[ADR-005](docs/ADR/ADR-005-explicit-memory-context.md)。
真实验收要求既有本机Ollama与配置模型可用、8765空闲；使用隔离synthetic Memory与临时凭据，不访问用户Memory/WinCred：

```powershell
.\mvnw.cmd package -DskipTests
python scripts/desktop-memory-ask-smoke.py
```

## M3C-2 — Logical Memory Export / Restore

Memory… 管理窗口的 **Export / Restore…** 打开独立小型 maintenance modal。
**Export Memory…** 显式打开 SaveFileDialog；它导出全部已保存 ACTIVE + ARCHIVED source records，
不包含未保存编辑、索引、credentials、tasks、问题/回答/selection/prompt 或历史。
覆盖文件由 OS overwrite prompt 确认；cancel 不导出，后台不自动备份。

**Export contains your Memory text in plaintext. Protect this file like other personal documents.**
本轮没有加密、密码或 secure archive；checksum 不是签名，修改者仍可重算 digest。

**Restore Memory Backup…** 显式选择 backup 文件和新的/空的 data directory。
Restore creates a NEW data directory. It does not merge or overwrite current Memory.
目标须位于已有 parent 中、项目/build/logs/auth/current data 之外，不得含文件或 links/reparse points。
验证整个 logical document 后，Runtime 在 task-owned sibling staging 中创建 fresh schema v1、事务插入
原 source fields、重建 FTS、核对数据/索引/search/schema/quick_check，再发布完整 closed DB。
新目标采用 no-replace directory rename；已有空目标保留目录，仅 no-replace 发布完整 DB。
当前运行的数据库、IDs/revisions/status/timestamps 不被恢复操作修改；恢复不自动切换 Runtime。
成功提示 **Start Runtime with the restored data directory to use it.**

使用恢复后的目录时，先停止当前 Runtime，然后以实际选择的目录启动：

```powershell
java -jar target/personal-ai-workspace-0.1.0.jar "--workspace.data-directory=D:\PersonalData\restored-memory"
```

| Native-only API | Contract |
| --- | --- |
| GET `/api/v1/memory/backup` | source-only UTF-8 JSON；format `personal-ai-workspace.memory-backup`；formatVersion 1 / schemaVersion 1；required itemCount / contentDigest |
| POST `/api/v1/memory/backup/restore` | 严格 envelope：`backup` logical document + `targetDirectory` native 选择的 absolute path；返回 versions/count/digest |

每条保持 `id,type,title,content,status,revision,source,createdAt,updatedAt`，以 lowercase UUID 排序。
时间须是 canonical UTC、精确毫秒；unknown fields / duplicate JSON keys / IDs / incompatible versions /
invalid enums/revisions/times/source/text/Unicode/NUL / corrupt JSON / digest mismatch 整体拒绝。
SHA-256 使用明确的 length-prefixed UTF-8 canonical binary serialization，不依赖 JSON escaping/order/whitespace。
文档/文件上限 **14,948,096 bytes**；restore envelope **15,013,632 bytes**，且嵌套 backup 仍受文档上限约束。
预算由 1000 items × 最坏 JSON escaped record bytes 推导。仅 export success response 扩大 Desktop cap；
普通/error response 保留1MiB，普通API body保留32KiB。Browser / web Origin / missing auth / preflight 拒绝。

稳定错误：400 `MEMORY_BACKUP_INVALID` / `MEMORY_BACKUP_UNSUPPORTED`；413 `MEMORY_BACKUP_TOO_LARGE`；
409 `MEMORY_RESTORE_TARGET_NOT_EMPTY`；500 `MEMORY_EXPORT_FAILED` / `MEMORY_RESTORE_FAILED`。
消息不含正文、文件路径、SQL 或 raw exception。关闭取消 local IO/HTTP并忽略late results；
server 已接收的 restore 不能被撤回，通信结果不明确时检查所选目标后再显式重试。

真实 Windows synthetic-only integrated acceptance（要求8765空闲、本机Ollama/configured model可用）：

```powershell
.\mvnw.cmd package -DskipTests
python scripts/desktop-memory-backup-smoke.py
```

自动验收注入 native picker choices，并使用真实 WPF controls、file IO、HTTP、SQLite 和 Ollama。
不访问用户 Memory/WinCred，不停止用户 Runtime；test-owned source/auth/backup/targets 全部清理。
完整报告：[M3C-2 Report](docs/milestones/M3C-2-MEMORY-EXPORT-RESTORE-REPORT.md)；
长期 contract / publication limitations：[ADR-006](docs/ADR/ADR-006-logical-memory-backup-restore.md)。
Finance 保持冻结；Finance Reality Sync 仍是独立前置条件。

## Milestone 状态（历史记录）

M0 — Shared Runtime Foundation：**CLOSED — GO**。

M1 Windows Assistant Entry 已实现 .NET 10 LTS / 原生 WPF 客户端，代码位于 `desktop/`。
M1 — Windows Assistant Entry：**CLOSED — GO**。2026-10-02 全量回归通过，用户确认剩余真实 Windows 验收全部 PASS。
Java 与 Desktop 分别使用 Maven Wrapper / dotnet CLI 验证；Desktop 只调用 Runtime，不直接访问 Ollama。
M1 closing commit 已 fast-forward merge 到 main 并 push 到 origin/main；发布基线为 `6d17ad7137665bbe6105c868db41edfbd9cf46be`。
M1.5 — Assistant Core Capabilities：**CLOSED — GO**，新增 Summarize 与 single-turn stateless Ask AI。
M1.5 已 fast-forward merge 到 main 并 push 到 origin/main，发布基线 `22c45de4ff2ff2996960ca93817914af1da73baa`；完整证据见 STATUS 与 [M1.5 Closing Report](docs/milestones/M1.5-CLOSING-REPORT.md)。
M2A — Browser Runtime Access Foundation：**CLOSED — GO**，已 fast-forward merge 到 main 并 push 到 origin/main，发布基线 `9d20a9a4a138b9df3583e54eea8c3a1c78a8785e`。
完整历史证据见 [M2A Closing Report](docs/milestones/M2A-CLOSING-REPORT.md)；完整 Chrome acceptance 属于 M2B-2B。
M2B-1 — Browser Pairing UX：**CLOSED — GO**，已 push feature branch、fast-forward merge main 并 push origin/main，发布基线 `d60647273a8dcf63b71e985bd8ba4e63ad5d64a9`。
验收与边界见 [M2B-1 Closing Report](docs/milestones/M2B-1-CLOSING-REPORT.md)，历史报告保留当时未 merge/push 的事实。
M2B-2A — Runtime Browser Batch Translation Contract：**CLOSED — GO / Runtime Batch Translation Contract Ready**。
同一 Translate API 支持一批 records → 一个共享任务 → 一次 provider inference → structured result。
已 merge/push 的稳定基线为 `25dc1dfc9a103b030267f18d93059316f0ce008d`；[M2B-2A Closing Report](docs/milestones/M2B-2A-CLOSING-REPORT.md) 保留当时仅本地提交的事实。
M2B-2B-R1 — Real Chrome GET Security Compatibility：**CLOSED — GO**（2026-10-03），已 merge/push，Workspace 稳定基线为 `ad6e8995cf482517be11602c795b1d6331b68e4b`。
真实 Chrome 154 已验证无 Origin readiness/task GET 可读、exact-Origin Batch POST 与 structured result。
证据与边界见 [M2B-2B-R1 Closing Report](docs/milestones/M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md)。
M2B-2B — Chrome Extension → Shared Runtime Migration：**CLOSED — GO**；最终真实 Chrome 证据见
[Browser Closing Report §41](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/docs/M2B-2B-RUNTIME-MIGRATION-REPORT.md#41-final-closing--real-chrome-acceptance--2026-10-03)。
M2 — Browser Convergence：**CLOSED — GO**；历史报告保留各阶段当时的 Git / 验收状态，当前基线以上述最终 SHA 为准。

## 启动

### Main Workspace 开发与构建（M5A）

开发机需要 Node20.19+/22.12+、npm 与现有 .NET/Java。用户运行发布目录无需 Node/npm/Vite。
React 使用本地 bundled assets，不直接访问 Runtime；凭据、文件与备份操作在原生窗口完成。

```powershell
cd desktop/frontend
npm ci
npm test
npm run build
cd ../..
dotnet build desktop/PersonalAiWorkspace.Desktop.slnx -c Release
dotnet publish desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj -c Release
python -X utf8 scripts/main-workspace-smoke.py
```

普通 build/publish 会自动执行 npm ci/build，并验证 production assets。已构建的流水线可使用
`-p:FrontendSkipBuild=true`，但仍须有 Node 和有效 manifest；不允许缺失 assets 静默发布。
Main Workspace 需要已安装的 Microsoft Edge WebView2 Runtime；初始化失败会显示原生 fallback，Assistant 继续可用。
Debug 开发须显式 `npm run dev` 和 `dotnet run --project desktop/src/PersonalAiWorkspace.Desktop -c Debug -p:MainWorkspaceDev=true`。
仅固定 loopback5173；Release 拒绝 dev property，没有自动 dev-server fallback。
主题设置仅在私有 WebView 会话内保留；关闭时清理浏览数据。没有浏览器个人数据存储。

Windows可双击仓库根目录的`start-workspace.cmd`：按需构建、启动/复用Ollama与Runtime并唤出Assistant。
启动脚本构建跳过测试，不下载模型、不自动配对、不重置Memory；关闭启动窗口不会停止后台应用。

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
M2A 将现有 native token 持有人映射为 `native-local` owner，不改变 Windows 凭据。
Browser 使用独立 credential / Origin / owner，详见 [ADR-003](docs/ADR/ADR-003-browser-client-security.md)。

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

## Windows Browser Pairing（M2B-1）

在 Assistant 点击 **Pair Browser…**，输入 `chrome-extension://` 加 32 个 a-p 小写字符（无尾斜线）。
核对扩展身份后，明确点击 **创建一次性配对**，Desktop 使用已有 Windows Credential Manager native 凭据调用
`POST /api/v1/security/pairings`，提交 `origin`、固定 displayName `Chrome Extension`、`userApproved: true`。
打开窗口、修改输入或启动应用不会创建配对；Desktop validation 只用于 UX，Runtime 是最终 authority。

成功后临时显示 Pairing ID、One-time pairing secret、Expires at（本机时区），可显式 **Copy Pairing ID / Copy Secret**。
Secret 不写文件、日志、telemetry、Credential Manager 或历史；重新创建前清除旧值，窗口关闭或过期时清除显示引用，
关闭时取消 HTTP 等待并拒绝迟到响应回填。托管内存释放引用不等于强制擦除所有内存副本。
复制会进入系统剪贴板；其历史/同步由 Windows 设置控制。窗口关闭不撤销服务器 session，它将按 Runtime 3 分钟 TTL 过期。
网络失败时服务端可能已经创建 session；不要把 UI 失败当成服务端未接受。

**Paired Browsers → 刷新列表** 只显示 displayName、origin、createdAt、allowedCapabilities。
选择后点击 **Revoke selected**，成功 204 后移除条目；失败保留条目并提示，通信失败后刷新确认实际状态。
未 exchange 的 session 不在列表中。Revoke 阻止后续 browser credential 请求，不取消已接受任务。

Desktop 只创建 pairing，不调用 exchange、不生成或保存 browser credential、不修改 registry。
Browser v0.5.0 自行 exchange，并在 trusted-only extension storage 保存独立 credential；不接收 master/native token。
真实 Windows GUI pairing / revoke / re-pair 与 Chrome readiness / Translate 已在 Browser 最终 Closing Report §41 验收 PASS。

## API

Native `/api/v1/**` 请求继续使用 `Authorization: Bearer <local-token>`。
M2A browser client 使用独立 Bearer credential；pairing exchange 使用短时一次性 proof。
公开健康接口只返回 Runtime 状态；provider readiness 需要认证。

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/actuator/health` | Runtime health |
| GET | `/actuator/health/liveness` | 进程 liveness |
| GET | `/actuator/health/readiness` | Runtime readiness，不依赖 Ollama |
| GET | `/api/v1/providers/readiness` | 本地 provider / 配置模型可用性 |
| GET | `/api/v1/capabilities/translate/readiness` | 认证且 Translate-authorized；仅安全 available/error.code |
| POST | `/api/v1/translate/tasks` | Single 或 Batch Translate，202 + taskId + Location |
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

Batch 使用同一路径与 `translate.fast`，`text` / `items` 必须恰好提供一个，显式 null 输入也拒绝：

```json
{"items":[{"id":1,"text":"Hello"},{"id":2,"text":"Settings"},{"id":3,"text":"Load more"}],"sourceLanguage":"en","targetLanguage":"zh-CN","profile":"translate.fast"}
```

每批 1–32 项，id 为唯一整数 `0..2147483647`（拒绝 string / float / null）。每项 text 为非空字符串，最多 2800 字符；
所有 text 合计最多 2800 UTF-16 字符 / 4096 UTF-8 字节。JSON 编码后的整批 input（含 id / escaping）还必须 ≤5632 UTF-8 字节，
并遵守 profile 的字符/context 预算。HTTP body 仍 ≤32 KiB；超长 record 明确拒绝，不截断、拆分或提高模型预算。

Batch 成功的任务返回原生 JSON object，例如 `"result":{"items":[{"id":1,"translation":"你好"}]}`，
promptVersion 为 `translate-batch-v1`；Single Translate / Summarize / Ask 的 result 仍是 JSON string。
只保留 requested、唯一且有效的 id；unexpected id 忽略，duplicate requested id 的所有结果失效，空/畸形项保持 missing。
有效数组的 subset（包括空数组）可 SUCCEEDED；malformed top-level / 超预算输出为 PROVIDER_RESPONSE_INVALID。
没有自动 item retry；后续 Browser 显式处理 partial/retry。DELETE 取消整个 Batch task。

Batch 的安全 profile id/version/locality + promptVersion 已用于 Browser cache identity，不返回 resolved model 或 generation settings。
Runtime 拥有 batch prompt，客户端不能提交 prompt/model/generation 参数。
Translate readiness 只返回 `{"available":true}` 或 `{"available":false,"error":{"code":"PROVIDER_UNAVAILABLE"}}`，
不创建 task、不做 inference；模型缺失也折叠为不可用，不暴露模型/provider。Browser v0.5.0 的 CHECK_CONNECTION 已使用此路径。

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

loopback 不代替认证。Native 拒绝 Origin / cross-site 请求；Browser 使用独立 credential 与 Fetch Metadata，
Origin-present 请求必须匹配已注册精确扩展 Origin。Chrome 无 Origin 的 GET 仅允许下述两个显式路径。
CORS 只响应请求实际携带且精确允许的 Origin；无 Origin 不生成 allow-origin，不设 wildcard，也不代替认证。
Native 持有人共用 `native-local` owner；每个 Browser client 独立 owner，跨 owner GET/DELETE 返回 TASK_NOT_FOUND。
同一 OS 用户能读取 token 是本地信任假设；不隔离已攻陷的同用户进程。
Runtime 默认不记录正文、模型回答、token、provider body。

## Browser Runtime Access（M2）

M2A 的 Runtime foundation、M2B-1 的 Windows pairing UI、M2B-2A 的 Batch contract、R1 的 Chrome GET 兼容和 M2B-2B 的真实 Chrome migration 均已 CLOSED — GO。
Pairing 由现有已认证本机操作显式批准；master token 不传给 Browser。

| 方法 | 路径 | 权限 / 语义 |
| --- | --- | --- |
| POST | `/api/v1/security/pairings` | Native；`origin`、ASCII `displayName`、`userApproved: true`；返回 pairingId/secret/expiresAt |
| POST | `/api/v1/security/pairings/exchange` | 匹配扩展 Origin + pairingId/pairingSecret；只返回本客户端 metadata 和 credential |
| GET | `/api/v1/security/clients` | Native；安全 metadata，无 credential/verifier |
| DELETE | `/api/v1/security/clients/{clientId}` | Native；204 幂等 revoke/server forget，持久化删除 verifier |

Origin 必须为 `chrome-extension://<32 lowercase a-p characters>`，无尾斜线。
Browser 请求要求 `Sec-Fetch-Site: none`、`Sec-Fetch-Mode: cors`、`Sec-Fetch-Dest: empty`；
未知 extension、普通网页、localhost Origin、缺失/不匹配 credential、cross-site/navigation 均拒绝。
Browser 仅可 POST `/api/v1/translate/tasks`、GET Translate capability readiness 和 GET/DELETE 自己的 `/api/v1/tasks/{id}`。
Ask/Summarize/管理 API 不授权 Browser。所有 capability 仍 LOCAL_ONLY，共用同一个 TaskManager。

真实 Chrome privileged GET 可以不发送 Origin。Runtime 首先按 Authorization 类型选择 credential 验证路径，
`br1` prefix 不授予权限；必须 lookup 已注册 clientId 并 constant-time 比较 credential 的 SHA-256 verifier，revoke 后拒绝。
无 Origin Browser 请求只允许 GET `/api/v1/capabilities/translate/readiness` 与 GET `/api/v1/tasks/{uuid}`，
同时要求完整精确 Fetch Metadata、Translate capability 和 task ownership。没有 Origin 时无法验证 exact Origin；
Bearer secret 是认证材料，注册身份来自此前 explicit user-approved pairing。无 Origin 的 mutation/其他 route 一律拒绝。
Pairing exchange 及所有 Origin-present 请求继续要求 exact Origin；POST/DELETE 必须带它。
没有伪 Origin header、Origin synthesis 或 wildcard CORS；现有 exact-Origin OPTIONS policy 保持。

Pairing 3 分钟、一次性；最多 8 sessions、每 session 5 次错误 proof、总 exchange 60 次/分钟。
最多 32 个注册。已配对 credential 跨 Runtime restart 有效；未完成 pairing 在 restart 后失效。
`browser-clients.json` 与 token 同属专用私有目录；只存安全 metadata 和 SHA-256 verifier，64 KiB 上限，
private permissions、exclusive writer lock、atomic replacement、corruption fail closed。
不删除损坏 registry 来静默重置；该 registry 不存 Memory/正文，M3A Memory 在独立私有 data directory 中持久化。
Revoke 不取消先前已接受的 task。

Browser v0.5.0 从可信 worker context 访问 Runtime；storage 在任何读写前限制为 `TRUSTED_CONTEXTS`，
credential 不进入 content script / webpage / DOM / log。Extension local forget 仅删除副本；server revoke 由 Windows trusted native action 执行。
Chrome → authenticated Shared Runtime → Translate / Batch Translate → Shared TaskManager → `translate.fast` → local Provider。
Browser 已移除 direct Ollama endpoint、model/system prompt/generation config ownership、provider parser、direct provider retry/fallback。
Browser 保留 DOM extraction、Viewport First、Dynamic Content、Restore、Selection、frame/document boundaries、sidebar/nested scroll、page-lifetime cache 与 UX。
完整决策、真实证据 amendment 与同 OS 用户进程信任边界见 ADR-003。

## 验证

以下命令和结果为开发验证入口及既有历史证据；本次 M2 Final Cross-Repo Closing 只执行 Git 文档检查。
Browser 最终 §41 记录 pairing/readiness、Batch/task polling、full page、Dynamic、Restore、Selection/frame/privacy、cache、
Runtime/Provider offline recovery、Windows GUI revoke/re-pair 与 MV3 全部 PASS。
MV3 为 **38.231 秒**：35 秒 verification relay delay 后真实 inference，popup 已关闭，worker debugger 已断开，最终成功。
复杂页面验收使用等价 guide + sidebar/nested scroll，未使用真实 MDN 网站；不据此扩大版本/平台或翻译质量保证。
详细历史来源见 [M2 Closing Report](docs/milestones/M2-CLOSING-REPORT.md)。

```powershell
.\mvnw.cmd clean verify
.\scripts\real-local-smoke.ps1
.\scripts\browser-security-smoke.ps1
.\scripts\browser-security-smoke.ps1 -Batch
git diff --check
```

自动测试使用 fake work 和 loopback HTTP mock server，不依赖本机 Ollama。
M2B-2A：Java 29 / Desktop 67 PASS，native 三能力与 synthetic browser Single/Batch 安全 smoke REAL PASS。
Batch smoke 使用 Python 3 标准库的仅验证 loopback relay 计数实际 Ollama `/api/chat`，证明 3 records / 1 POST / 1 task / 1 inference / 3 valid mappings。
Relay 只在验证中转发到现有本机 Ollama，不是产品 Provider；不记录正文，不修改 Ollama。
M2B-2B-R1：Java 30 / Desktop 67 PASS；native、synthetic Single/Batch 与真实 Chrome 154 限定安全链路 PASS。
真实 Chrome 验证脚本 `scripts/chrome-get-security-smoke.js` 使用 Node 24、现有 Java/Chrome、未修改的 sibling Extension candidate。
参数为实际 `java.exe`、`chrome.exe`、可选 Extension repo 路径；拒绝占用端口，使用隔离 dev authority/profile，验证后 revoke/停止自有进程并清理含 credential 的临时 profile。
该脚本只验收 exchange/readiness/Batch/polling/result/security，不执行完整 Extension Closing 或 WPF GUI 验收。
`scripts/privacy-audit.py` 从 stdin 接收内存中的临时凭据，扫描 source/build/archive/log/evidence，不保存或输出秘密。
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
