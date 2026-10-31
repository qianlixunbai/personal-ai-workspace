# X1 第一轮真实 Windows Q / T 聚焦验收

2026-10-31，Asia/Shanghai。源码基线：`main @ 2b579ae798a5c2ce1739a255536d17afbd46dcb3`；入场 working tree 干净。

**Q 总结 PASS；T 直接翻译 PASS；关闭后的来源窗口独立核验 BLOCKED。** 两条模型路径均取得真实记事本选区、用户物理快捷键、原生浮窗、Java Runtime、Ollama 实际结果与主动复制证据。X1 整体仍为 **WINDOWS ACCEPTANCE PARTIAL**，本记录不关闭其他 Windows 验收项目。Vision **BLOCKED**；**Development-only**；**无已核验 CI PASS**；**尚未 Production GO**。

## 证据范围与准入

本机原始证据保存在 ignored 目录 `.verification/x1-real-e2e-20261031/`，下述 JSON / PNG 文件名相对此目录。合成原文与结果可以进入本记录；认证凭据、原始诊断流及其他用户剪贴板正文未落盘。

历史恢复报告 `.verification/runtime-recovery-verification-20261031/REPORT.md` 仅用于定位受信身份。本轮另行执行实时只读核验，见 `phase0-runtime.json`（02:32:56）、`phase0-processes-ollama.json`（02:33:17）、`phase0-trust.json`（02:34:15）。

| 对象 | 本轮实际身份与准入 |
| --- | --- |
| Desktop | PID `17960`，代次 `17960:134378557087496664`；原包 `artifacts/ui-latest-development-8df87ab-20261029-01`，manifest commit `8df87abf667d158b1edc90c9a343a375f3d06f4a`。EXE、Desktop/Core DLL、Java、JAR 与该包清单 hash 匹配；本轮涉及的捕获、提交和任务 owning code 在该包 commit 与源码基线间无差异。 |
| Java Runtime | PID `24012`，parent `17960`，代次 `24012:134378557095399424`；同包 Java / JAR、原 Profile 参数；`127.0.0.1:18765`，health `UP`。JAR SHA-256 `0d5c4958c01e5879ff7022945a90b5635c2ebb00f0db70d37c94248096f9ecb0`。 |
| PostgreSQL | PID `29956`，代次 `29956:134378547494963778`；`D:\PostgreSQL\bin\postgres.exe`，监听 `127.0.0.1:55432`。当前 Runtime storage API 实际报告 `POSTGRESQL / TRUSTED_LOCAL / CONNECTED`、Workspace `V1_V2_READY`、Knowledge `READY`、`errorCode=NONE`。未重复运行集群恢复或迁移。 |
| Profile / Native 认证 | 原生 helper 来源 pin 和 `VerifyProfileNative` 通过，保存 Profile ID / 偏好 hash 未变；私有凭据文件的 ACL / owner 校验后，仅在内存使用 Native Bearer 读取状态及指定任务。 |
| Ollama | PID `15236`，代次 `15236:134378363303204065`；`D:\Ollama\reviewed-0.40.6\dist\ollama.exe`，API version `0.40.6`，SHA-256 `71d2002d3ae572d5fa8c1d368ce9d1b9a39c7187cf169e8b330c55410bf6c58e`，监听 `127.0.0.1:11434`。 |
| 模型 | Configured / Active 与 Ollama tags 均为 `qwen3.5:4b / llamacpp / 96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。Runtime `ready=true`、`uncertain=false`、`validationRequired=false`，reserved / queued / executing / draining 均为 `0`，`switching=false`。 |
| 共享执行 | 准入、Q 后、T 后及最终采样未观察到其他连接 Ollama 的客户端。该结论限于采样，不宣称连续监测了所有共享客户端。 |

Runtime 的 `loaded=FALSE` 原样保留。02:33 的独立 Ollama `/api/ps` 同时列出相同 digest 的驻留别名；两者未完全一致，不把任一快照改写为另一值，也不以驻留状态替代 Ready。没有因此重复 VALIDATE、加载或发送额外推理。

## 真实操作与 Runtime 终态

用户负责记事本输入、选区、物理快捷键、Q 操作选择 / 提交及复制。只读观察器没有键盘注入、自动点击或抢前台逻辑。实际记事本为 Windows 11 `Microsoft.WindowsNotepad_11.2504.62.0`，正文控件 `RichEditD2DPT`，两条路径的来源窗口为 PID `21200` / HWND `331270`；浮窗 HWND `1707640`。

`ui-observations.jsonl` 记录前台、选区、原生控件状态和剪贴板元数据。任务 UUID 通过现有 SDK 的 `Microsoft.Diagnostics.NETCore.Client` 对 Desktop `System.Net.Http` EventPipe 元数据做一次有界只读观察，在内存仅提取白名单 `/api/v1/tasks/<uuid>`；只保存 UUID，没有保存原始流、认证头或请求正文。随后通过原 Native 认证独立 GET 指定任务，见 `observed-task-ids.jsonl`、`q-runtime.json`、`t-runtime.json`。这不是由脚本直接提交的替代 API 验收。

| 项目 | Q 快捷助手总结 | T 直接翻译 |
| --- | --- | --- |
| 结论 | **PASS** | **PASS** |
| 选区与前台 | 02:36:35.130，记事本主动选区 97 字符；02:36:36.649 浮窗前台且原文逐字相同。02:38:01 再次观察到同一浮窗前台，随后用户选择总结并提交。 | 02:39:12.892，记事本主动选区 86 字符；02:39:14.386 浮窗前台且原文逐字相同。用户明确确认物理按 T 后自动翻译，未点击翻译按钮。 |
| Task UUID | `800fd0d6-d4ab-4c1b-8dce-22907d278363` | `5be579f0-dc75-41a2-9875-f5c1da8d29cf` |
| Runtime 创建时间 | `2026-10-31 02:38:05.3173407 +08:00` | `2026-10-31 02:39:15.8768774 +08:00` |
| Runtime 完成时间 | `2026-10-31 02:38:10.0099719 +08:00` | `2026-10-31 02:39:18.0229584 +08:00` |
| Runtime 终态 | `SUCCEEDED`、capability `summarize`、`error=null` | `SUCCEEDED`、capability `translate`、`error=null` |
| 请求身份 | `summarize.fast / summarize-v1 / LOCAL` | `translate.fast / translate-v1 / LOCAL` |
| UI 状态 | 实际观察到排队 → 处理 → 已完成；结果与独立任务 API 完全一致。 | 实际观察到排队 → 处理 → 已完成；结果与独立任务 API 完全一致。 |
| 主动复制 | 02:38:25.028 首次观察到复制成功，剪贴板文本与 94 字符实际结果完全一致。 | 02:39:28.175 观察到复制成功，剪贴板文本与 29 字符实际结果完全一致。 |
| 截图 | `q-result-023810.png` | `t-result-023918.png` |

两项任务的 profile version 均为 `am1-48d1ea36-4988-423c-85e8-1eba2c507f55`；Q 后空闲核验通过才指导 T。Active 身份、selection revision `0`、recovery generation 和 Runtime 代次始终匹配，构成请求与模型的关联。TaskView 本身不含模型 digest，未声称每个任务响应直接返回了 digest。

本轮观察到一个 summarize UUID 和一个 translate UUID，没有观察到重复任务；用户确认 T 无手动提交。Q 初次截图前后的 UIA 可见性变化不等同于关闭、捕获失败或第二次模型执行，不据此虚构退出原因或新增成功次数。未录制物理按键的硬件事件；物理操作依据为用户执行及确认，应用反应和任务结果为独立观察证据。

### Q 选区原文及实际返回

原文：

> Personal AI Workspace 是一个运行在 Windows 上的本地 AI 工作区。它通过本地模型处理文本，并提供跨应用快捷助手功能。我们正在测试它是否能够正确获取选区并返回总结。

实际 AI 返回：

> Personal AI Workspace 是运行于 Windows 的本地 AI 工作区，支持通过本地模型进行文本处理及提供跨应用快捷助手功能；当前正在测试其能否准确获取选区并返回总结。

### T 选区原文及实际返回

原文：

> Personal AI Workspace helps users translate selected text with a local language model.

实际 AI 返回：

> 个人 AI 工作区帮助用户使用本地语言模型翻译选定的文本。

## 焦点、剪贴板与窗口体验

Q / T 均观察到记事本前台 → 原生浮窗前台；取词前后的 clipboard sequence 分别保持 `1069` / `1117`，T 直到结果完成仍保持 `1117`。结合原生 UIA TextPattern 中的精确选区、浮窗原文和既有调用链，这支持本轮使用直接 UIA 取词；没有取得受控 Copy fallback 的实际执行证据，不将其格式恢复能力标为通过。

复制仅由用户主动点击“复制结果”执行。Q / T 的复制状态与剪贴板逐字比对均通过；没有自动向记事本粘贴。截图操作另有剪贴板 sequence 变化，未将其误判为产品取词污染。用户在体验反馈中回复“没有”，没有报告异常闪烁、遮挡或输入焦点丢失；这不是连续屏幕录像或独立逐项物理断言。

关闭后的来源窗口独立核验 **BLOCKED**：用户最后反馈“窗口状态没有变，我的操作不会影响到开发区”；该答复没有指明关闭瞬间的前台窗口。02:47:38 的最终只读观察对原来源 HWND `331270` 调用 `AutomationElement.FromHandle` 返回 `Unrecognized error.`，原记事本 PID `21200` 此时已不存在，见 `final-window.json`、`user-confirmation.json`。因此未独立确认关闭后的原文 / 选区保留、来源窗口激活或内容清理，不根据早先 UIA 可见性变化推断关闭，也不推断进程退出的触发者或原因。已停止该项观察，没有重开来源应用、注入输入或追加模型请求；先前已闭合的 Q / T 模型路径证据保留。

## 问题与未验证项目

发现一项轻微提示文案问题：T 的 `SetCapturedText → UpdateAction` 会先显示“已取得选区，选择操作后开始”，此时取消按钮已启用，自动提交正在进行；收到任务身份后再映射为排队 / 处理 / 完成。用户确认未手动点击，真实任务成功，因此不判作 T 未自动提交。记录该非阻断问题，本轮没有修改生产代码或覆盖受信包。

以下均为 **NOT TESTED**：IME、取消、运行中关闭、迟到结果、物理 Esc 专项、125% / 150% DPI、混合多屏、受控 Copy fallback / 多格式剪贴板恢复、解释 / 提问、其他真实应用与其他模型。不为这些项目制造额外请求。

## 最终状态与变更范围

最终 Runtime 采样 `2026-10-31 02:47:41.537 +08:00`（`final-after-user-runtime.json`；来源窗口观察失败后的一次针对性复核）：health `UP`；原 PostgreSQL backend 仍 `TRUSTED_LOCAL / CONNECTED`，Workspace `V1_V2_READY`、Knowledge `READY`、`errorCode=NONE`。Active / Configured 身份与准入相同；`ready=true`、`uncertain=false`、`validationRequired=false`、`switching=false`，四项执行计数均为 `0`，`error=null`。此前 02:43:32 的正常快照保留于 `final-runtime.json`。

`final-host.json` 确认四个目标进程代次、路径与三个 listener 未变，保存 Profile 偏好及本轮入场记录的 Workspace / Finance 配置 hash 未变。未启动 / 重启服务，未执行 VALIDATE / RECOVER，未修改模型准入合同、Native Gate、Profile、数据库或真实数据。服务保留运行。

生产代码、长期测试代码均未修改；只增加本验收记录、原 X1 报告的范围更新，以及 ignored 本机观察脚本 / 脱敏证据。实际验证是上述真实用户集成流程、独立状态 / 指定任务 GET、UIA / 截图 / 剪贴板核对；没有新增自动化测试、构建或打包，没有重跑 Java / Desktop / Frontend 全套或其他历史矩阵。

未创建分支、Commit 或 Push。交付仍在 `main @ 2b579ae798a5c2ce1739a255536d17afbd46dcb3`，保留本轮文档修改供审阅；不以未经要求的提交制造干净 working tree。本轮不产生 CI PASS 或 Production GO。


## 后续交接：Windows 交互 P2

本记录中的 Q / T PASS 及当时关闭核验 BLOCKED 均为原时间点事实，不改写。后续 [P2 独立记录](X1-WINDOWS-INTERACTION-P2-20261031.md)在新记事本 PID 44984 / HWND 6228384 上完成 X / 物理 Esc 关闭、来源焦点、原文 / 选区和相应剪贴板检查；真实 IME 因用户主动发送 nihao 产生至少一次额外推理而停止，未取得完整通过证据。本轮未重跑 Q / T 模型验收，未将上述问答算作旧路径成功。T 提示的一行修复只在开发分支，未覆盖原受信包。总体 X1 PARTIAL 及其余发布限制保持。

同日 15:37 的后续聚焦验收已独立取得微软拼音候选 / 中文上屏及普通 Enter 换行 PASS；组合态 Ctrl+Enter 为模拟 PASS、物理 NOT TESTED。本次用户确认没有发送，未观察到新增真实任务；不改写先前额外推理及零推理约束 FAIL，不重跑或增加 Q / T 成功记录。最新证据及未完成项目见 P2 文末；X1 PARTIAL 等限制保持。
