# GitHub 展示与 Runtime 就绪核对

2026-10-30（Asia/Shanghai）。基线为 fetch 后的 `main @ 8df87abf667d158b1edc90c9a343a375f3d06f4a`，展示分支 `codex/github-final-showcase`。**Development-only / X1 PARTIAL / Vision BLOCKED / 尚无已核验 CI PASS / 尚未 Production GO。** 本轮仅更新展示与状态说明，不开始 X1 或 Vision 开发。

## 六页展示与隐私

[项目 README](../../README.md) 保留原有结构，更新 Assistant 浅色主图、Conversations 深色阅读图，以及 Memory、Knowledge、Finance、Settings 四张可点击原图的缩略图。

| 页面 | 原始 PNG / 来源 |
| --- | --- |
| Assistant · 浅色 | [问答与结果](../design/ui-experience-p2/assistant-1280x800-light.png) |
| Conversations · 深色 | [历史与输入](../design/ui-experience-p2/conversations-1280x800-dark.png) |
| Memory · 浅色 | [列表与编辑](../design/ui-experience-p2/memory-1280x800-light.png) |
| Knowledge · 深色 | [资料与原文](../design/ui-experience-p2/knowledge-1280x800-dark.png) |
| Finance · 深色 | [财务仪表盘](../design/ui-experience-p2/finance-1280x800-dark.png) |
| Settings · 深色 | [模型状态与设置](../design/ui-experience-p2/settings-1280x800-dark.png) |

全部复用 2026-10-29 [P2](UI-EXPERIENCE-P2-REPORT.md) 的真实 `MainWorkspaceWindow` / 受信 WebView2 合成 fixture 渲染。窗口 1280×800 DIP、原图 1264×722 px，DPI scale / ZoomFactor 均为 1；不是完整桌面或物理操作截图。新增公开的 Conversations 原图取自同轮最终 `after/conversations-1280x800-dark.png`；其余五张已在仓库，无重复复制。

P2 最终资源目录的入口 HTML、JS、CSS 与最新开发包对应文件 SHA-256 一致。其他五页在 Conversations 最终 CSS 数值调整前已捕获，该改动只调整对话历史高度，未改变这些页面；不将旧捕获冒称本轮在真实用户 Profile 中重新截图。

本轮逐张视觉检查六张图；内容均为合成问题、长历史、记忆、资料及 Finance 身份／金额。没有公开真实用户财务、记忆、凭据、个人文档、Profile ID 或本机用户路径。Knowledge 中的脚本文字是合成纯文本安全样例。Settings 的 `ui-fixture:8b` 与 uncertainty 告警，以及各页连接／完成状态，均不表示当前 Runtime 事实。原图无裁剪、拼接或生成；新增图复制前后 hash 一致，其余五图与 P2 已审查 hash 一致。

## 开发包身份与本次只读观察

进程、端口及驻留状态最后采样于 **2026-10-30 14:14:42 +08:00**；这些是采样时状态。

包：`artifacts/ui-latest-development-8df87ab-20261029-01`。读取清单与版本资源，并核对 Desktop EXE / DLL、Core DLL、Runtime JAR 和全部前端文件的 SHA-256，均与包清单一致；未重建或覆盖该包。

| 身份 | 实际值 |
| --- | --- |
| 清单 commit / sourceDirty | `8df87abf667d158b1edc90c9a343a375f3d06f4a` / `false` |
| EXE ProductVersion / FileVersion | `1.0.0+8df87abf667d158b1edc90c9a343a375f3d06f4a` / `1.0.0.0` |
| 前端 JS | `index-BtHq6tpR.js` · `983a44a015ad928c394e9b4dcfeedaa2744002b8ec65419adae340217b0194fe` |
| 前端 CSS | `index-DuR71ZSw.css` · `08240dcc29b98376db98458d1a72863b48fb9234823869d115e60c88d019b446` |
| Runtime JAR | `0d5c4958c01e5879ff7022945a90b5635c2ebb00f0db70d37c94248096f9ecb0` |

| 检查项 | 本次真实观察 / 限制 |
| --- | --- |
| Workspace / Runtime | 未发现 Desktop 或 Java 进程；`127.0.0.1:18765` 无 listener。未启动客户端，因此没有当前 Runtime API 快照。 |
| Ollama | 服务在 `127.0.0.1:11434` 监听；只读 `GET /api/version` 返回 `0.40.13`。 |
| 模型身份 | `GET /api/tags` 存在 `qwen3.5:4b`，runner `llamacpp`、Q4_K_M，digest `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`，与最近开发包状态记录一致。 |
| 驻留模型 | `GET /api/ps` 返回空列表；这只证明采样时未驻留，不能替代 Runtime Ready 或任务状态。 |
| uncertainty guard | 当前无 Runtime，未读取 Profile 内的 guard 文件；不能给出当前 API 的 `uncertain` 值。最近重启记录为 `uncertain=false`，不再沿用早期“仍未解除”的结论。 |
| Ready / 本地任务 | 当前进程内状态不可读；不把历史零计数写成当前 API 结果。无进程及无驻留模型也不独立证明过去请求的完成事实。 |

## 历史证据与当前 Ready 的区别

- 用户提供的既有事实：原 Workspace 已解除 `MODEL_EXECUTION_UNCERTAIN`，`qwen3.5:4b` 完成过一次文本问答。本轮未重新推理，不将此事实写成新的实际执行。
- 本机 ignored `.verification/ui-latest-development-8df87ab-20261029-01/result.json`、`model-status-after-restart.json` 与 `model-status-final.json` 记录：2026-10-29 最新开发包重启后 `ready=false`、`activeModel=null`、`loaded=FALSE`、`validationRequired=true`，即 **NotValidated**；`uncertain=false`，`reserved / queued / executing / draining` 全为 0，`switching=false`、`error=null`。这些是历史快照，不是今天的实时 API 响应；不上传本机文件。
- owning code `ActiveModelManager` 的 Ready / active 为进程内状态，重启不能继承旧 Ready；`managementStatus()` 仅检查元数据，不能替代原生确认的验证加载。

**结论：可准备下一阶段 X1，尚不具备直接执行真实推理验收的 Ready 证据。** 用户需自行启动上述包，在 Settings 的原生模型确认界面重新执行验证加载，并确认当前模型身份、Ready 和空闲状态。本轮没有代替用户确认、验证加载、切换、释放、RECOVER 或执行模型请求，也未为健康检查重复推理。

## 下一阶段 X1 的最小计划（本轮未执行）

依据 [X1 历史报告](X1-CROSS-APP-REPORT.md) 与 [P2 待人工项目](UI-EXPERIENCE-P2-REPORT.md)：早期 Q / UIA 捕获与操作切换有部分证据；完整流程曾因 uncertainty 停止，无模型流程曾因 `SetForegroundWindow=false` 停止。后续单次文本成功没有关闭这些物理流程缺口。

1. **用户准入与物理输入**：启动同一开发包与已有工作区，通过原生界面验证加载；确认 Ready、身份及无执行／排队／reservation／draining。用物理中文 IME 检查候选词、Enter / Ctrl+Enter 不误发，再使用只含合成文字的外部应用。
2. **一条综合选区到结果流程**：物理 Q 热键捕获 → 切换总结／解释／提问并确认选区保留 → 最小文本推理返回 → 主动复制结果；补一次 T 直接翻译。观察前台激活、WPF / WebView 焦点、回到来源窗口后的焦点及剪贴板恢复。先确认捕获来源，激活失败则停止并记录，不向其他窗口注入。
3. **取消、失败与未知结果**：在同一受控会话检查执行中取消、物理 Esc／关闭、迟到结果隔离与不误重发；失败／未知状态优先复用既有确定性证据，只补必要人工观察。若真实结果未知则保留现场、核对外部完成事实，不为制造案例中断服务或清除 guard。
4. **实体显示环境**：在真实 Windows 125%／150% 缩放和混合 DPI 多显示器间拖窗、最大化／还原，核对可读性、点击、滚动、浮窗边界和 IME 候选位置。没有相应设备时记为待验，不用 CSS / DPR 模拟计作通过。

用户必须亲自完成原生授权、物理选区／热键／IME、焦点感受及实体多屏操作。上述是计划，未计作已通过的测试或覆盖点。

## 本轮验证范围

实际执行：远端 fetch 与基线核对、包关键文件及前端身份 hash 校验、只读进程／端口和 Ollama 元数据检查、六张原图视觉隐私检查、图片 hash／尺寸与文档本地链接／锚点检查、Git diff / staged diff / whitespace 核查。仅提交必要文档和一张新增原图。

没有修改生产代码、长期测试或 harness；没有运行生产测试、全量回归、模型推理、X1 完整流程、Vision、真实财务／文档操作、构建或打包。既有 Profile、数据库、模型、开发包及历史验收证据保留。仅 Commit / Push 展示分支，不合并 main，不创建 Release / Tag；本轮结束后停止。

返回 [文档索引](../README.md) · [当前状态](../STATUS.md)。
