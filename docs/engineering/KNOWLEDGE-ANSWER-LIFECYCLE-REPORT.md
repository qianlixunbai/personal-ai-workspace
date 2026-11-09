# 知识回答持续使用：任务授权生命周期修复

2026-11-09。起点 `main @ 184aafdd91251e990518b34682d4d987973443c5`；分支 `codex/knowledge-answer-lifecycle`。用户已明确恢复 Workspace 开发。本阶段只交付这个可独立验证的修复，未推送或部署，仍为 Development-only。

## 问题、选择与实现

`WorkspaceKnowledge.Authority.AnswerTasks` 原来只添加任务 ID，直到整个 WebView session 结束才清空。同一窗口累计 64 个已完成任务后，第 65 次在提交 HTTP 前永久返回 QueueFull；即使 Runtime 的旧结果已经过期，继续等待也不能恢复。既有全项目审查已指出该问题，本轮核实代码并通过合成请求复现。

路线的日常启动／状态隔离已有主线修复，记忆完整应用也已有受控启动／重开证据；真实个人恢复仍涉及来源与停写证明，不能顺手开放。本轮选择不占用模型、不接触数据的 Knowledge 持续使用缺口，作为日常可用性阶段的第一步。未宣称日常可用性整个阶段已完成，长资料证据预算属于下一项独立工作。

生产修改只在 `WorkspaceKnowledge`：

- 保持每会话最多 64 个回答授权及提交中的容量预留。收到 Runtime 严格解析后的 SUCCEEDED／FAILED／CANCELLED／TIMED_OUT 才标记为可回收。
- 新提交容量不足时，按首次观察到终态的顺序撤销最旧授权。再次读取终态不重排，不让旧的在途活动响应撤销已观察到的终态事实。
- GET／cancel 收到经过 RuntimeClient 状态码和结构校验的 TaskNotFound 后，移除对应授权；网络错误、超时、非法响应不释放容量。
- 每个 ID 绑定独立授权对象；响应回来后再次核对原 session 和同一授权对象，之后才投影结果并授权引用文档。旧响应不能复活已回收授权，也不能授信新会话。
- 对已撤销或不属于当前 session 的 ID，GET／cancel 在发出 HTTP 前拒绝。授权回收不取消 Runtime 任务，不删除源文档、结果、对话或数据库记录。

没有新接口、持久表、配置、后台清理、时钟过期、自动轮询、额外模型调用或 POST 重试；没有改变前端交互、K2 排名、K3 引用／预算和严格输出校验。语义已补入 [ADR-013](../ADR/ADR-013-grounded-knowledge-answer-citations.md)。

## 成熟开源实现与直接复用评估

2026-11-09 通过 GitHub 官方连接读取 [dotnet/runtime](https://github.com/dotnet/runtime) 元数据：18,415 Star、未归档，最近 push 2026-11-08 23:48:48 UTC；[许可证](https://github.com/dotnet/runtime/blob/0ca316ae278f39ac0126559fae552120a2900805/LICENSE.TXT)为 MIT。读取固定提交 `0ca316ae278f39ac0126559fae552120a2900805` 的 [MemoryCache 实现](https://github.com/dotnet/runtime/blob/0ca316ae278f39ac0126559fae552120a2900805/src/libraries/Microsoft.Extensions.Caching.Memory/src/MemoryCache.cs)，确认其容量回收先分类过期／优先级，再按访问次序处理，并提供 NeverRemove 类别。

评估直接使用 `Microsoft.Extensions.Caching.Memory` 后没有新增该依赖：这里存的是 task/session 访问授权，活动任务不能被一般容量／时间策略回收；还须在同一会话锁下把提交预留、精确授权对象和迟到引用授信一起核对。通用缓存的回调与过期机制不能替代这些业务约束，引入它仍需另一套状态管理。

实际复用现有 .NET 的 `Dictionary`／`LinkedList`、现有 `WorkspaceKnowledge` 锁和 session、`RuntimeClient` 严格解析、Java TaskManager 的终态合同，以及现有 Knowledge 测试 fixture。参考“受保护活动条目＋有限容量回收”的成熟做法，只补本领域状态规则；没有复制上游实现、安装依赖或增加服务。两份集合均受 64 上限约束，没有定时线程和外部资源开销。Star 和维护活跃度不作为安全保证。

## 必要验证与失败记录

复用 `KnowledgeTests`，只新增 3 个有意义的场景，其余 13 项既有测试一起运行：

1. 保留一个 RUNNING 任务，连续完成 70 个合成回答，涵盖四种终态与取消响应；后续提交继续成功，授权集合不超过 64，旧已回收 ID 的读／取消在 HTTP 前拒绝，活动 ID 仍可读。
2. 63 个活动任务＋一个在途提交占满容量，额外提交在 HTTP 前拒绝；传输失败及伪装成 TaskNotFound 的错误状态码均不释放；真实的严格 TaskNotFound 释放后才能继续提交。
3. 已确认终态授权在容量回收后，一个延迟到达且结构合法的结果不能重新授权任务或其引用文档；既有测试还覆盖会话切换后的迟到提交／读取，以及未知 POST 不重放。

本地证据目录：`.verification/knowledge-answer-lifecycle-20261109/`。

| 记录 | 结果 |
| --- | --- |
| `before-test.log` | 初次测试命令误带仅 Profile 验收宏，触发既有 RuntimeLifecycleProbe 条件编译配对错误；未进入测试。未改动该无关探针来绕过错误 |
| `before-repro.log`、`results/before-repro.trx` | 改为普通测试配置，在生产代码未修改时运行新增连续使用场景，1 项失败，保留旧实现的容量阻塞证据 |
| `after-test.log`、`results/after.trx` | 修复后 `KnowledgeTests` 16/16 通过，0 失败、0 跳过；Core／Desktop／测试程序集编译通过，现有打包前端资产校验通过 |
| `preserved-documents.json`、`originals/` | 开始时 6 份未跟踪文档的副本和 SHA，收尾再次核对 |

实际命令（本地 .NET 10，首次已恢复依赖；没有重跑前端或 Java 全套）：

```powershell
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj `
  -c Debug --no-restore `
  --artifacts-path .verification/knowledge-answer-lifecycle-20261109/dotnet `
  -p:Mmf3ProfileAcceptanceBuild=false -p:FrontendSkipBuild=true `
  --filter 'FullyQualifiedName~KnowledgeTests'
```

这些是实际 Bridge／Core 通过合成 HttpMessageHandler 的所属层验证，不是 70 次真实推理或真实桌面点击。没有运行用户 Runtime、Ollama、数据库或 KAG；没有验证模型回答质量、PG GUI、IME、正式分发或 GitHub CI。

## 限制、回滚与接续

- Java Runtime 自身的队列／结果保留上限仍生效，仍可能合理地短时 QueueFull；修复的是客户端累计使用后的永久限制，不是无限吞吐或无限历史。
- 未观察到终态且未查询到 TaskNotFound 的任务继续保留授权；如果 64 个都处于活动或未知状态，仍受控拒绝。不会根据时间或取消意图猜测任务已经结束；本轮不引入后台查询或放宽权限。
- 回收后即使 Runtime 还保留结果，当前 session 也不再允许用旧 ID 读／取消；当前 UI 没有知识回答历史列表，新提交本来会清除旧回答。此前已合法授权的文档预览不因任务回收而自动删除授权，仍遵守其既有文档规则。
- 显式记忆的小样本、Windows 固定平台合同、真实个人备份／恢复和 Production GO 限制保持原状。没有安装包更新，运行中的旧包不会自动获得本修复。

本地回滚标签 `workspace-before-knowledge-lifecycle-20261109` 指向 `184aafd`。本阶段只明确提交代码、所属测试、ADR、状态／路线入口和本报告；原有 6 份未跟踪文档保持未跟踪、内容不变。不推送、不合并、不改写 main，不把 Git 备份说成数据库恢复。

下一步可独立处理 Knowledge 长中文证据的预算与准确来源范围，或按总体路线继续日常启动／个人恢复前置；必须先核对当时工作区及资源情况，不能把本阶段通过当作这些工作已经完成。
