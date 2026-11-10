# 模型阻断请求的用户检查路径

2026-11-10，接续 `7541038`。完整维护仍 **BLOCKED / productionGo=false**。

## 已核实的契约与本轮目标

当前生产 `OllamaProvider` 使用 `/api/version`、`/api/tags`、`/api/show`、`/api/ps` 做来源/版本/驻留核对，`/api/chat` 做非流式推理，`/api/generate` 做单模型卸载。文本、管理探针和视觉入口使用同次请求绑定；没有原推理请求终态查询、远端取消确认或重启后认领 API。证据及官方资料见 [本地退出交接](TASK-SHUTDOWN-HANDOFF.md) 和 `.verification/task-shutdown-20261110/provider-contract.json`。本轮复用已读取的官方契约，不再调用模型或重复旧验证。

补充精确版本核对：`OllamaModelAdmission.SUPPORTED_VERSIONS` 为 0.40.0、0.40.1、0.40.6；0.40.6 的准入还被 `OllamaProvider` 限定在固定多模态子模型，并非通用兼容。除已读取的 0.40.1 外，又读取官方 [0.40.0 routes.go](https://github.com/ollama/ollama/blob/v0.40.0/server/routes.go) 和 [0.40.6 routes.go](https://github.com/ollama/ollama/blob/v0.40.6/server/routes.go)。三版所核对的路由均没有按原推理请求 ID 查询终态/确认取消的恢复接口；`/api/status` 也不是本项目使用的原推理请求查询入口。这是源码范围内的结论，未访问当前安装服务。

| 实际请求 | 当前接受的合同 | 不能证明 |
| --- | --- | --- |
| GET version/tags/ps、POST show | 限定版本、模型/runner/digest、明确本地来源及驻留 metadata；目录/来源查询不加载模型 | 请求是否结束、其他客户端空闲、服务归属 |
| POST chat | 显式本地模型引用、runner、`stream=false`、`think=false`；结构化任务附既定 schema。当前同次响应必须满足精确模型、assistant 角色、合法字段、`done=true`、`done_reason=stop/length` | 回答业务有效、旧请求结束、整个 runner 或服务退出。length 的协议完成不等于回答成功 |
| POST generate | 单模型卸载，`keep_alive=0`；接受同次 `done=true/done_reason=unload` | 先前未知推理完成、原执行终态查询、共享服务空闲 |

请求/响应检查、单次 socket 写入及当前活体请求能力均沿用既有实现；没有凭这些资料扩大真实提供商、视觉或维护验收。

用户真实缺口：现有“核对外部恢复（只读）”只刷新模型状态和目录，仍不知道哪些已登记请求缺少结束证据；Ollama 不在线时目录查询也不能帮助解释旧阻断。

模块二完成判据：提供 native-only 的本地记录读取 API，返回有限的请求编号、操作、模型身份和结束证据分类；读取不调用 provider、不修改 guard、不授予恢复能力。本进程已观察到的协议完成仅在本进程内展示；重启后历史记录一律不能据此认定完成。旧格式、pending、损坏、缺失或不可读不能成为“没有记录”。同一读取不能将另一个未知请求误标完成。

模块三完成判据：将同一 API 接到现有原生 Bridge 和设置页恢复入口，用户可显式查看记录及当前不支持恢复的原因，Ollama 离线也能读取；严格校验响应并拒绝过期会话结果。只读动作不弹确认、不发送修改请求，不产生人工解锁。完整桌面视觉/真实 provider 验收不在本轮内。

## 实现、必要验证与边界

模块二已完成：`GET /api/v1/models/executions` 经原有 native-only Controller 检查，调用 `ActiveModelManager.inspectExecutions()` 和实际 `ModelStateStore`，不刷新 provider。响应只包含记录分类、恒为 false 的 `recoverySupported` 及最多 128 条请求元数据；不暴露 endpoint、ownerSession、stateBinding、reservation、提示词、回复或凭据。

本进程仅在实际发送、可信完成和 transport 退出路径更新只供展示的有界观察。它不落盘，不参与恢复准入；当前 guard 正常清除时一起释放。另一请求未知时，已经完成的记录仍可展示完成，不能清除其他未知。重开只解析历史身份，全部展示 UNKNOWN，不从 UUID 重建权限。旧版、pending、损坏/缺失及不可读分别返回 LEGACY、INCOMPLETE、UNREADABLE 或 UNAVAILABLE；没有请求行不代表无阻断。NONE 也只说明没有模型执行记录，不是完整维护许可。

必要验证 `.verification/execution-inspection-20261110/`：原包装和共享锁、450 秒外层期限、Java 256 MiB／2 CPU，新建合成 Profile，经真实 Native Gate、真实私有 ModelStateStore、无模型 loopback HTTP 服务及实际 Controller 的 MockMvc 分派。一个聚焦场景验证了本次完成与正在发送/未知的不同请求、provider 已关闭后仍可读取、重开后的逐项未知、旧/损坏/pending 说明；每次读前后 guard 字节不变；浏览器身份和查询参数分别拒绝为 403/400。没有启动实际 API HTTP listener，未宣称全安全过滤器、桌面或真实提供商验收。

`result.json` 为 passed，`token-cleanup.json` 确认临时 token 删除；build/run cleanup 均为 Job 0、锁释放、failure=null。只编译三个产品源文件与新聚焦验证及既有支持类，5 条既有 `ExtendedOpenOption` 警告，无编译错误；未重跑已通过的关闭或旧外部请求测试。这些模块二验证不替代下述模块三或完整桌面验收。

记录列表是本地发送登记及活体观察，不是服务端任务清单。历史格式没有持久化用户任务 ID、提问或精确中断原因；不能凭模型名把请求归到某次用户对话，也不能把所有历史行都断言为仍在执行。

## 模块三：现有恢复入口接线

设置 → 本机模型 → 高级模型操作与恢复 → **查看阻断请求（只读）**，现在经 `WorkspaceClient` → 既有 `models.inspectRecovery` Bridge → `WorkspaceModels.InspectExecutionsAsync` → `RuntimeClient.InspectModelExecutionsAsync` → 上述 `/executions` API。它不再调用模型状态/目录接口；普通模型刷新路径保持原合同，旧 GET `/recovery` 兼容入口也未删除。

用户看到本地执行编号、推理/验证或卸载、模型及摘要，以及本次完成、等待或未知原因；旧版、未完成写入和不可读状态有不同说明。读取会清除当前 UI 的旧模型选择及原生缓存选择授权；检查结果不能用于发起修改。开始新读取、Runtime 未确认、会话/存储生命周期改变时清除旧观察；迟到响应不能回显。恢复按钮仍禁用，没有人工勾选或清除 guard 的动作。当前接口不提供“是哪条用户提问”的映射，界面也不冒称该映射已存在。

Core 与前端均限制响应字段、最多 128 个唯一请求编号、合法模型身份/选择版本和状态组合；拒绝 `recoverySupported=true`，也拒绝此前运行的 COMPLETED 声明。列表限制高度并可键盘聚焦滚动，长编号/摘要按原样换行；未放宽页面 CSP。

必要验证 `.verification/execution-inspection-desktop-20261110/`：

- .NET 构建通过，1 项新测试通过。真实 RuntimeClient、WorkspaceModels 和 WorkspaceBridge 使用合成 HTTP handler；恢复读取只产生一个 GET `/executions`、0 POST、0 确认窗口；旧模型授权失效，非法响应拒绝，会话切换后迟到数据不发布。
- 前端完整类型检查通过，1 项新 SettingsPage 测试通过，其余 2 项既有测试跳过。实际 React 设置页与 WorkspaceClient 使用合成 Bridge 回复，验证逐请求呈现、完成/未知区别、pending 不冒充空记录、拒绝伪造恢复及旧会话响应；仅产生只读 `models.inspectRecovery`。
- 原共享锁/Job 包装，450 秒外层期限，.NET 2 CPU、Node 384 MiB／单 worker。native/build cleanup 均为 Job 0、锁释放、failure=null；未使用真实模型、用户数据或真实凭据，未创建桌面验收 Profile。

以上是分层的实际产品接线验证，未启动完整 WPF/WebView 应用或重新跑完整原生维护；.NET 测试构建复用既有 frontend dist，React 检查针对新源码，不冒称新界面已经打入交付包。完整桌面视觉、真实服务与旧未知请求恢复仍未验收。没有重新打包/推送，main、六份用户文档及旧包保持原基线。

补充官方源码读取已成功。随后两次可选 Firecrawl 质量反馈被自动审批拒绝（任务标识/实现上下文外传风险），未执行、未重试，已停止；具体目标和原因记在 `feedback-denial.json`。该反馈不是交付依赖，没有待执行的模型调用或不确定结果。
