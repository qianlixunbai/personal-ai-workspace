# Personal AI Workspace — V1 Roadmap

本文件是长期 V1 roadmap source of truth。它记录实施顺序和永久架构边界，不是 ADR、
K2 implementation spec 或未来功能已实现的声明。当前证据见 [Current Status](../STATUS.md)
和各 milestone Closing Report；正式 closing authority 属于 Architecture Guard。

## 正式主线

| 序号 | Milestone | 目标 / 当前状态 |
| --- | --- | --- |
| 01 | M0 | CLOSED — GO |
| 02 | M1 | CLOSED — GO |
| 03 | M1.5 | CLOSED — GO |
| 04 | M2 | CLOSED — GO |
| 05 | M3 | CLOSED — GO |
| 06 | M4 | CLOSED — GO |
| 07 | M5 | CLOSED — GO |
| 08 | K0 | APPROVED — GO |
| 09 | K1 | CLOSED — GO |
| 10 | K2 | Deterministic Lexical Retrieval；CLOSED — GO，Architecture Guard 独立 Closing Review 已批准 |
| 11 | K3 | Grounded Knowledge Answer + Citations；NOT STARTED |
| 12 | W1 | Controlled Web Access；NOT STARTED |
| 13 | V1 | Multimodal / Vision Foundation；NOT STARTED |
| 14 | F0 | Finance Reality Sync；NOT STARTED |
| 15 | F1 | Finance Integration Foundation；BLOCKED，依赖 F0 |
| 16 | F2 | Bill / Transaction Import；未来阶段 |
| 17 | F3 | Unified Finance Assistant；未来阶段 |
| 18 | F4 | Knowledge + Vision + Web + Finance Orchestration；未来阶段 |
| 19 | P1 | V1 Product Consolidation / Final Closing；未来阶段 |

M5A–M5E 是 M5 internal stages，不是单独的 top-level milestones。
K4 当前不是必需的 V1 prerequisite。Model Management Foundation 和 Resource Monitor
是跨阶段平台任务，不新增第 20 个 top-level milestone。

## 永久领域边界

**Memory ≠ Knowledge ≠ Finance。**

- Memory：explicit user-controlled reusable personal context。
- Knowledge：user-imported reference material。
- Finance：independent authoritative financial domain。

Knowledge 中的财务文档永远不构成 balance truth、transaction truth 或 PnL truth。
Finance Integration 保持 BLOCKED，直到 F0 完成 authoritative Finance Reality Sync，
查明真实 Finance repository、数据模型、接口、验证与提交权限，再由独立 review 决定后续集成。

## K3 与 W1

K3 在 K2 确定性检索基础上规划 bounded grounding 与可核对的 citations；其实现和批准
独立进行。本 roadmap 不授权自动把检索结果写入 Memory 或形成 Finance truth。

W1 的受控访问链固定为：

Local Model → structured tool call → Runtime Policy → controlled Web Search / Fetch
→ Public Internet → bounded evidence → Local Model。

模型没有 unrestricted arbitrary Internet access。默认 policy 为 **Ask before accessing web**。
未来可选择 Disabled、Ask before accessing web、Allow automatically for public-information queries。
Web 不得自动发送 Memory、Knowledge 或 Finance private data。

W1 至少规划 HTTPS、SSRF protection、loopback/private/LAN denial、metadata endpoint denial、
DNS validation、redirect limit、timeout、body limit、MIME validation 和 bounded concurrency。
默认不使用 cookies，不复用 browser login session，不转发 Workspace credentials，不暴露 Runtime token。
Browser Extension 继续作为 Translate-only companion；Workspace startup 不启动 browser。

## Model Management Foundation

这是 cross-cutting V1 platform task。实施顺序：

K3 → W1 → Model Management Foundation → Vision。

V1 默认 **ONE Active Model**，不默认同时加载多个模型。Settings 未来允许选择 Active Model。
Model profile 至少规划 provider、model id、capabilities、text support、vision support、context limit、
installed state、loading state 和 startup behavior。
模型状态包括 Loading、Ready、Busy、Unloaded、Failed。
Startup 只启动 / warm 用户当前选择的一个 Active Model。

Safe Model Switching 顺序：

select model → verify installed → verify capabilities → load/warm → health check → activate。

只有新模型验证成功后才 update Active Model；失败 retain old Active Model。
禁止先破坏旧模型再尝试新模型。

## Vision

Vision V1 规划 Image、Screenshot、Document Image、Chart、Receipt 和 OCR-like extraction。
可以输出 structured candidate，但不能直接形成 Finance truth。
Receipt / Image 的后续路径为：

Vision → candidate → later F2 validation / mapping / dedup / confirmation → Finance commit。

只有 Finance commit 后才是 authoritative transaction。

## Resource Monitor 与 System Status

Resource Monitor 是 V1 正式能力，与 Model Management Foundation 一起逐步开发，P1 最终产品化。

| 范围 | V1 规划监控 |
| --- | --- |
| Hardware | CPU utilization；RAM used / total / %；GPU utilization；VRAM used / total / %；GPU temperature；Disk free space |
| AI Runtime | Active Model；Model State；First-token latency；Generation tokens/sec；Context usage；Running tasks；Queued tasks |
| Services | Runtime；Ollama；Knowledge Index；Vision；Web Access；Finance |
| Storage | Workspace total；Knowledge size；Lexical index size；Finance size；bounded logs / auxiliary data |

低干扰 warning 可包括 high RAM / VRAM pressure、abnormal GPU temperature、low disk、
context near max、index stale / rebuilding、model unavailable 和 queue busy。
V1 不做 network traffic monitoring、GPU power、fan speed、GPU clock、GPU voltage 或
general hardware telemetry dashboard。后续确有需要时归入 Advanced Diagnostics。

未来 System Status 至少展示 Runtime、Ollama、Active Model、Model State、CPU、RAM、GPU、
VRAM、GPU Temperature、Knowledge、Vision、Web、Finance；详细页展示 TTFT、tokens/sec、
context、queue 和 storage。

## Startup Experience 与 Process Ownership

V1 最终实现 one-click startup，目标链为：

Personal AI Workspace.exe → Single Instance Guard → Main Window appears early
→ Runtime health/start → Ollama health → reuse existing Ollama if already running
→ start Ollama if required → load configured Active Model → warm only Active Model
→ capability check → subsystem status → READY。

Main window 不等待模型完全 warm 才出现。不自动启动浏览器、不默认同时加载多个模型、
不每次启动重建 Knowledge index、不自动启动无关程序。
原本已运行的 Ollama 被 reuse，Workspace exit 不杀它。由 Workspace 启动的 Ollama
按明确 lifecycle policy 管理。不得 kill unknown process。
本节是未来产品目标，不追改现有 launcher 的历史事实或当前外部进程管理语义。

## Degraded Mode

子系统失败不得拖死整个 Workspace：Model FAILED 时 Knowledge lexical search、Memory、
history、Settings 仍可用；Web unavailable 不影响 Local AI；Finance unavailable 不影响
Knowledge；Index rebuilding 不影响 Memory / Conversation。

## P1 — V1 Product Consolidation / Final Closing

P1 最终负责 Unified UX、one-click startup、model settings、safe model switching、
Runtime / Ollama lifecycle、Resource Monitor、System Status、degraded mode、backup / recovery UX、
startup / shutdown、restore previous page、icon / tray icon / launcher icon、product identity、
accessibility、privacy audit、packaging、clean install、migration compatibility、final regression、
final Windows acceptance、V1 architecture review 和 V1 closing review。
P1 不再引入新的大业务域。

本次仅同步 K2 当前状态为 CLOSED — GO，Architecture Guard 独立 Closing Review 已批准；
K3、W1、Vision、Model Management、Resource Monitor 和 Finance 均未因此启动。
