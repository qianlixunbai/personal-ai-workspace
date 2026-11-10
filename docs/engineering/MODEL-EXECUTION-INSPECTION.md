# 模型阻断请求的用户检查路径

2026-11-10，接续 `7541038`。完整维护仍 **BLOCKED / productionGo=false**。

## 已核实的契约与本轮目标

当前生产 `OllamaProvider` 使用 `/api/version`、`/api/tags`、`/api/show`、`/api/ps` 做来源/版本/驻留核对，`/api/chat` 做非流式推理，`/api/generate` 做单模型卸载。文本、管理探针和视觉入口使用同次请求绑定；没有原推理请求终态查询、远端取消确认或重启后认领 API。证据及官方资料见 [本地退出交接](TASK-SHUTDOWN-HANDOFF.md) 和 `.verification/task-shutdown-20261110/provider-contract.json`。本轮复用已读取的官方契约，不再调用模型或重复旧验证。

用户真实缺口：现有“核对外部恢复（只读）”只刷新模型状态和目录，仍不知道哪些已登记请求缺少结束证据；Ollama 不在线时目录查询也不能帮助解释旧阻断。

模块二完成判据：提供 native-only 的本地记录读取 API，返回有限的请求编号、操作、模型身份和结束证据分类；读取不调用 provider、不修改 guard、不授予恢复能力。本进程已观察到的协议完成仅在本进程内展示；重启后历史记录一律不能据此认定完成。旧格式、pending、损坏、缺失或不可读不能成为“没有记录”。同一读取不能将另一个未知请求误标完成。

模块三完成判据：将同一 API 接到现有原生 Bridge 和设置页恢复入口，用户可显式查看记录及当前不支持恢复的原因，Ollama 离线也能读取；严格校验响应并拒绝过期会话结果。只读动作不弹确认、不发送修改请求，不产生人工解锁。完整桌面视觉/真实 provider 验收不在本轮内。

## 实现、必要验证与边界

模块二已完成：`GET /api/v1/models/executions` 经原有 native-only Controller 检查，调用 `ActiveModelManager.inspectExecutions()` 和实际 `ModelStateStore`，不刷新 provider。响应只包含记录分类、恒为 false 的 `recoverySupported` 及最多 128 条请求元数据；不暴露 endpoint、ownerSession、stateBinding、reservation、提示词、回复或凭据。

本进程仅在实际发送、可信完成和 transport 退出路径更新只供展示的有界观察。它不落盘，不参与恢复准入；当前 guard 正常清除时一起释放。另一请求未知时，已经完成的记录仍可展示完成，不能清除其他未知。重开只解析历史身份，全部展示 UNKNOWN，不从 UUID 重建权限。旧版、pending、损坏/缺失及不可读分别返回 LEGACY、INCOMPLETE、UNREADABLE 或 UNAVAILABLE；没有请求行不代表无阻断。NONE 也只说明没有模型执行记录，不是完整维护许可。

必要验证 `.verification/execution-inspection-20261110/`：原包装和共享锁、450 秒外层期限、Java 256 MiB／2 CPU，新建合成 Profile，经真实 Native Gate、真实私有 ModelStateStore、无模型 loopback HTTP 服务及实际 Controller 的 MockMvc 分派。一个聚焦场景验证了本次完成与正在发送/未知的不同请求、provider 已关闭后仍可读取、重开后的逐项未知、旧/损坏/pending 说明；每次读前后 guard 字节不变；浏览器身份和查询参数分别拒绝为 403/400。没有启动实际 API HTTP listener，未宣称全安全过滤器、桌面或真实提供商验收。

`result.json` 为 passed，`token-cleanup.json` 确认临时 token 删除；build/run cleanup 均为 Job 0、锁释放、failure=null。只编译三个产品源文件与新聚焦验证及既有支持类，5 条既有 `ExtendedOpenOption` 警告，无编译错误；未重跑已通过的关闭或旧外部请求测试。模块三的原生/界面接线仍在开发，未随本次模块二提交宣称完成。

记录列表是本地发送登记及活体观察，不是服务端任务清单。历史格式没有持久化用户任务 ID、提问或精确中断原因；不能凭模型名把请求归到某次用户对话，也不能把所有历史行都断言为仍在执行。
