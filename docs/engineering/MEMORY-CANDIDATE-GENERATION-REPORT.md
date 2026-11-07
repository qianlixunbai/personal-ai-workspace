# 对话草稿的本地记忆候选生成

最终状态：后续已接入固定原生 JSON Schema，并完成完整应用／真实模型及独立质量小样本验收，见[整个显式记忆系统完成报告](MEMORY-SYSTEM-COMPLETION-REPORT.md)。下文保留原增量及早期失败的实际边界；模型候选仍须人工审阅、明确保存。

2026-11-07。基线 `d489de3b0a0bd916384629f918ad59c10c21df8e`，分支 `codex/recovery-v3-workspace-core`。本阶段推进记忆形成，不访问真实个人数据，不操作 KAG、数据库、服务或安全设置。

后续增量：[主动重复检查](MEMORY-CANDIDATE-DUPLICATE-REVIEW-REPORT.md)为候选增加有界、可选的当前正文查重和显式继续确认，仍不自动合并／写入。真实模型质量没有因这一步被标为通过；以下记录保留本阶段验证范围。

2026-11-07 后续：[真实失败后的约束修正](MEMORY-CANDIDATE-GROUNDING-REPORT.md)加强来源语言、适用范围与连续字面摘录要求，并拒绝有限范围内的明显文字体系变化；23 项离线用例和构建通过。随后[真实模型复验](MEMORY-MODEL-RECHECK-REPORT.md)改善了语言／范围表现，但仍出现缺少 evidence 的拒绝结果，生成质量尚不稳定；原始失败结果全部保留，不宣称整体通过。

## 链路盘点与交付

| 环节 | 入场状态 | 本次变化 |
| --- | --- | --- |
| 从对话取得原文 | F5.1 支持单条持久 USER／ASSISTANT 消息转编辑草稿，不提取、不改写 | 保留原路径，同时传递消息角色作为候选生成时的来源说明 |
| 记忆提取 | 没有模型生成候选入口 | 用户主动调用当前本机模型，从当前草稿正文提出最多一条候选，也可返回无候选 |
| 候选审核 | 编辑器支持用户手动审阅原文 | 新增原文摘录、类型／标题／正文编辑、丢弃和明确采用；不能自动覆盖已变更草稿 |
| 持久化 | `memory.create`／update、revision 与未知结果保护已存在 | 继续只通过原编辑器的“保存”写入，生成和采用都不写库；保存来源仍为 MANUAL |
| 检索与使用 | 字面查询、按需预览、每次显式选取记忆已有实现 | 保持现状，没有向量检索、自动召回、隐式注入或全历史提取 |

使用路径：对话消息 →“保存为记忆草稿”→“生成记忆候选”→ 对照摘录审阅并修改 →“采用候选到编辑器”→ 最后点击“保存”。现有原文手动保存路径继续可用。生成只读取当前编辑器正文，不查询其他历史、记忆或 Knowledge；原始消息不会因生成被更改。

## 实现与控制边界

- `MemoryCandidatePanel` 提交的是单次候选请求；`memoryCandidate.ts` 生成有界的 JSON 源文本提示并解析结果。完整提示（含转义和指令）必须同时满足既有 Ask 的 3000 UTF-16／5632 UTF-8 限制，不能满足时提示用户精简，不自动截断或扩大配置。
- 模型应返回 `{"candidate":null}`，或只含 type/title/content/evidence 的单条候选。结构、字段、Memory 输入限制及 evidence 为当前提交正文的非空字面摘录均需通过。无候选、格式错误、超限、伪造摘录不采用、不保存、不自动修复／重试；不合格输出仅以文本供用户查看。摘录匹配不能证明候选语义正确，仍需人工审阅。
- 对 USER 和 ASSISTANT 来源分别标识；提示要求助手内容保留“未经核实的建议”归属，采用助手候选后仍显示来源警告。提示不保证模型遵从或事实准确；没有持久 provenance 外键、证据或模型版本历史。
- 新 Native 方法 `assistant.proposeMemory` 只接收 `text`，不接收模型名、URL、凭据、Memory 引用或写入参数。复用现有 `WorkspaceOperations`、`AssistantOperation`、`RuntimeClient` 和普通 `Ask/chat.balanced` 路径；保留既有模型准入、任务所有权、容量、取消和超时。没有新 Java API、模型客户端、provider 配置或 schema。
- 新路径不消费普通助手的 Native Memory selection；普通 Ask／Summarize／Translate 的现有消费行为不变。候选结果仍是短期操作文本，进入编辑器前经前端格式检查，不构成 Native 写库权限。
- 原草稿保持原样直到用户明确采用；生成后的类型／标题／正文若已被用户修改，不能被旧候选覆盖。重新接收草稿、离开并丢弃、session 重置或组件卸载隔离迟到响应。原有 dirty、revision、重复保存和 OutcomeUnknown 保护继续使用；保存结果未知时禁止生成／采用候选，不能借此清除待确认状态。
- 可请求取消并核对已知 operation 的状态。取消意图提出后，即使最终返回成功也丢弃候选；通信结果未知时禁止再次提交，不自动重发。离开或丢弃候选界面不等于取消任务，UI 明确提示已接纳任务可能继续运行；没有清除模型 guard 或推断模型进程已停止。

## 成熟项目参考与复用选择

本次通过 Firecrawl 查阅 [Mem0 Add Memory 官方文档摘录](https://docs.mem0.ai/core-concepts/memory-operations/add) 和 [开源版自定义提取指令文档摘录](https://docs.mem0.ai/open-source/features/custom-instructions)：区分原消息与提取事实，限定提取范围，允许无相关事实时不产生记忆。Mem0 的 `add` 包含持久化语义，因此这里借鉴提取设计，不把它直接接入候选审核入口。此前参考的 [Open WebUI 记忆管理](https://docs.openwebui.com/features/chat-conversations/memory/) 也强调用户查看与管理。

已有 Workspace Store、revision、Bridge、模型任务和手动保存能覆盖本次需求。复用这些实现，只补候选提示／解析、审阅状态和隔离提交；没有新增模型／向量库依赖、复制上游源码或另建记忆引擎。没有实现后台自动记忆、自动去重合并、跨会话画像或持续学习。

## 验证记录

- 前端仅运行 `MemoryPage.test.tsx` 中 2 个新候选场景及 2 个直接受影响的既有原文草稿／未保存保护场景：首次及补充新草稿隔离／保存未知状态保护后的同组复验均为 4 passed、11 因过滤未运行。包括 QUEUED→读取结果、编辑／采用／明确保存、无候选、错误摘录、格式／预算拒绝、草稿变化、取消与未知结果、原始回复来源、迟到读取／session 和零自动写入。
- Native 只运行 `MemoryCandidateUsesLocalAskWithoutConsumingSelectionOrWritingMemory`：首次测试断言误把非法载荷的既有静默丢弃当成错误响应，修正测试后通过（1 passed、172 ms）。验证严格载荷、字节预算、可信 origin、请求防重放、普通助手选择保留、既有取消／session 所有权及 POST 未知结果不重试。使用合成 HttpMessageHandler，无真实网络、凭据或数据库。
- TypeScript、Vite 和资产 manifest 通过；保留已有主 chunk 超过 500 kB 提示。Native 使用本机已缓存 NuGet 包和空本地源还原，编译与测试输出单独放在 `.verification/memory-candidate-generation/dotnet/`，不覆盖既有受信开发包。未做全套回归或 Java／模型／数据库测试。证据与首次失败记录均在 `.verification/memory-candidate-generation/`。

这些验证证明合成输入下的控制流程，**不证明实际本地模型能稳定生成合格候选或提取准确**。尚未启动真实 Windows WPF／WebView2、验收实际模型质量或真实保存／检索链路，也没有发布受信开发包。保持 Development-only、X1 PARTIAL、Vision BLOCKED；真实个人备份／恢复仍关闭。

## 保留与回退

回退标签 `workspace-before-memory-candidates-d489de3` 指向修改前提交。本阶段独立本地提交，不合并 main、不推送或部署。五份原有未跟踪文档保持未跟踪，已复制到忽略目录并记录、复核 SHA-256。

Git 只回退代码与已跟踪文档，不包含真实数据库、Knowledge 源文、模型、凭据、任务／服务状态、五份未跟踪文档或忽略目录证据。保留文档副本也不是个人数据备份；本地提交不是异机备份。
