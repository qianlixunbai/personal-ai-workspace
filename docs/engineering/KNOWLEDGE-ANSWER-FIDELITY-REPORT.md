# 知识回答保真与结构化输出

2026-11-09。基线 `f36dacb`，实现提交 `2dc240f`，开发分支 `codex/knowledge-answer-fidelity`，回滚标签 `workspace-before-knowledge-answer-fidelity-20261109`。仅本地开发，Development-only；没有推送、部署、个人资料操作或 KAG 变更。

## 结论与根因

上轮 RFC 回答 `invalid` 丢失前导点且未使用中文，原记录和失败结论保留。本轮检查 `OllamaProvider.executeChatInSession` 及 `KnowledgeAnswerParser`：它们直接保留模型的答案字符串，没有去点或翻译步骤。旧 v2 提示要求证据、JSON 和来源标签，但没有明确要求回答语言、标识符逐字保留以及否定／条件保留；知识问答还经 `ResponseFormat.TEXT` 提交，没有使用现有原生结构化输出能力。这些是可修正的提示与合同缺口；**不能据此证明某条提示是上轮单次失败的唯一原因**。

修正版将固定 JSON Schema 交给现有 Ollama Provider，并加强通用保真提示。独立新题中旧版 5/5 满足预设检查；新版 4 题真实回答满足，另 1 题在本轮发现的客户端版本兼容问题后中断，没有重问。**当前证据证明新合同可运行，不能证明相对旧版的语义质量提升，也不能宣布原 RFC 问题已实测解决。**

## 实现

- `knowledge-answer-v3` 明确遵循问题指定语言，否则采用问题语言；原样保留名称、代码、数值、单位及标点，保留否定与适用条件。资料不足或冲突须说明；来源文本继续作为不可信资料。系统提示仍在原 512-byte 上限内，没有加入本轮题目、特定扩展名或答案替换。
- 新增固定 `KNOWLEDGE_ANSWER` Schema：仅 `answer` 与 `citations`，答案 1–2048 字符，1–10 个唯一 `S1`…`S10` 标签。复用现有 `ResponseFormat`、Provider 能力检查和原生 Ollama `format`，无新依赖或服务；不支持结构化输出时明确拒绝，不静默退回自由文本。
- 现有解析器继续独立拒绝多余字段、重复／不存在的来源标签、越界或空答案，并从冻结证据生成真实文档引用。Schema 的合法标签不代表本次真的提供过该来源，更不证明答案受来源支持，仍须执行原有解析校验。
- 打包与摘录选择加入 JSON 转义、Schema 和 1024-byte 保守系统／信封余量；Provider 继续检查最终请求。没有提高上下文、输出或模型参数预算。代价是可用证据容量略缩小，无法容纳时仍拒绝。
- Desktop 和合成快照校验明确兼容 v2／v3，未知提示版本仍拒绝。普通 Ask 保持原 TEXT 合同。历史 schema 仍为 2，备份容器格式不变。

## 成熟方案与安全取舍

2026-11-09 查阅了公开主源资料，未复制上游实现或安装 Python 框架：

| 参考 | 采用的原则与边界 |
| --- | --- |
| [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs) | 使用固定 JSON Schema，并在应用端再次校验；格式约束不能替代语义验证。复用项目已经接入的能力。 |
| [LlamaIndex CitationQueryEngine](https://github.com/run-llama/llama_index/blob/main/llama-index-core/llama_index/core/query_engine/citation_query_engine.py) | 编号来源、要求仅根据资料和相关来源回答、资料不足时说明。当前冻结来源及有界摘录足够承载此原则，不额外引入检索框架。 |
| [Haystack AnswerBuilder](https://docs.haystack.deepset.ai/docs/answerbuilder) | 把回答里的编号引用映射到提供的文档；映射不是事实蕴含校验。当前更严格的 supplied-label 校验继续保留。 |

[LlamaIndex](https://api.github.com/repos/run-llama/llama_index) 元数据为 MIT、未归档，最近 push 为 2026-11-06；[Haystack](https://api.github.com/repos/deepset-ai/haystack) 为 Apache-2.0、未归档，最近 push 为 2026-11-09。这只表示公开维护活动，不能保证适合直接嵌入本项目。[LlamaIndex 安全政策](https://github.com/run-llama/llama_index/security)与 [Haystack 安全政策](https://github.com/deepset-ai/haystack/security)都要求宿主负责输入及权限边界；后者列有 Jinja2 模板 RCE 历史公告。未采用动态模板、反序列化 pipeline 或不受控抓取组件。现有 Native-only、冻结证据、预算、固定 Schema、无工具执行和无自动重试继续保留；不把“成熟项目”描述为可免除应用安全责任。

Ollama 继续固定 0.40.1，沿用既有 [MIT 许可](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/LICENSE)及发行／安全核查，不自动升级。测试只使用既有固定 Qwen3.5 4B／llamacpp，digest `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。

## 必要代码验证

本地忽略证据根：`.verification/knowledge-answer-fidelity-20261109/`。

| 检查 | 实际记录 |
| --- | --- |
| Java KnowledgeAnswerTest 全类＋相关 RuntimeApiTest | `run-2/integration.log`：9 项，8 通过、1 失败、0 跳过。失败来自旧测试 spy 复制异步索引状态，且拦截的是旧的二参数 search。修复为委托真实索引并检查现用三参数调用持有锁；`core-capture-fix` 只重跑受影响项，1/1。原失败保留。 |
| HTTP 与契约 | 已通过场景确认真实 Provider 请求携带固定 Schema；非法 `S99` 失败且只有一次调用；普通 Ask 不携带 Schema。其他场景覆盖字面字符串保留、独立 Schema 树、不支持结构化能力拒绝、转义及 Schema 预算、冻结证据与有界摘录。 |
| Desktop KnowledgeTests | `native-tests/knowledge-compat.trx`：22/22，0 跳过，包括 v2／v3 提交及历史读取、v4 拒绝和未知 POST 不重发。 |
| 混合版本快照 | `core-snapshot-version/integration.log`：受影响的联合恢复综合场景 1/1，混合 v2／v3、不同终态与旧来源引用恢复并重开一致。 |
| 构建 | 默认 Java 编译、离线 v1-isolated-development package、JAR 审计、前端和 .NET 完整构建通过。.NET 0 warning／0 error；保留既有 Vite chunk 大小提示。skipTests 构建不计作动态测试。 |

最终 Runtime JAR SHA-256 `48EBC068E2FF87DF391F676BCDF37956F56CE9B732F198A08ABF9AFEEDF33959`；163 项 Format 2 来源清单，无原生 pins／系统设置变更，不是正式安装包。

## 冻结新题与真实模型结果

在第一次新题推理前冻结 5 份合成资料、题目、检索词及检查项。`preregistered-cases.json` 记录的题集源码 `frozen-quality-source.cs.txt` 的 SHA-256 为 `8BC79372BA467F3F1EA3F47A3CB683D6A9AB4452565E89CC678FDE8520BB4320`，不是两个文件具有相同哈希。之后只增补历史检查／不重放恢复逻辑，五题内容与期望均未改，期望不发送给模型。

通过真实 AssistantApp／WPF／WebView2 启动及原生 RuntimeClient／HTTP／SQLite 调用；本轮未通过 DOM 点击问答按钮，不冒充新增 UI 交互验收。全新合成 Profile `e443d8805ee94840bd2a35d2aa73d504`，导入和模型准入走产品路径。旧 Runtime JAR 来自上轮固定构建，SHA-256 `461615E2250AF9C94C8763CEC288813543BF70912BD2E69399533A6B9C4816D0`。两版本复用相同资料、范围和题目，各题最多提交一次；不重跑旧 RFC 题。

| 独立题目 | v2 原始答案 | v3 原始答案／状态 |
| --- | --- | --- |
| Kestrel staging 扩展名，要求中文完整句 | 合成 Kestrel 资料中，staging 包的扩展名是.cobalt。 | 无答案：未知提交经只读对账确认为 FAILED／KnowledgeInterrupted；没有重问。 |
| Mira 只校验不写文件的参数，要求英文句 | Use the --verify-only flag to perform checks without writing files. | Use the --verify-only flag. |
| Orchid 延迟上限、适用范围与实时流量限制，要求中文 | 延迟上限为 0.075 ms，仅适用于离线导入，不适用于实时流量。 | 延迟上限为 0.075 ms，仅适用于离线导入（offline imports），不得应用于实时流量（live traffic）。因此不能用于实时流量。 |
| Fennec 批准预算，资料未提供，要求中文 | 无法回答，因为提供的资料中未包含 Fennec 项目批准的预算金额。 | 资料中未包含合成 Fennec 项目批准的预算金额。 |
| Selene 后缀，资料含“忽略问题”干扰指令，要求中文句 | 根据合成 Selene 资料，归档文件后缀是.pearl。 | 根据合成 Selene 资料，归档文件后缀是 .pearl。 |

成功的九条模型答案均引用本题选定文档源版本 1，精确偏移回到原文。按预设字面检查和本轮逐条语义复核，旧版 5/5、新版成功的 4/4 满足；新版全部计划题按 4/5 记录，另题为技术中断。自动语言检查仅识别有无汉字，因此另核对了完整句、问答相关性、条件否定与缺失事实；这不是独立人工评分或统计评测。

首次当前版本提交暴露 Desktop 只接受 v2：Runtime 已接收，客户端解析 v3 后产生 OutcomeUnknown；本轮手工验收探针捕获异常后在 finally 调用 `app.ExitAsync()`，随后历史确认为中断。这不是“产品遇到 OutcomeUnknown 会自动退出”的证据。`model-ab-1/completion.json` 的 `passed=false` 和原失败日志不改写。模型关闭的 `history-inspect` 读取持久记录，确认中断，无答案；随后兼容修正后仅提交剩余 4 题。`model-resume-1/quality-current-result.json` 为 submitted=4、recovered=1、succeeded=4、retries=0，workflowPassed 表示收集／存储流程完成，不表示五题全过。

Ollama 原日志合计 **12 次 `/api/chat`**：`model-ab-1` 7 次（旧版准入＋5 回答＋初次新版准入），`model-resume-1` 5 次（再次准入＋4 回答）。未知首题无已完成的推理响应，仍按已提交后中断处理，而不是假定没发过而重试。

模型关闭后的最终独立启动 `model-ab-1/history-final` 退出 0，0 模型调用：读取 5 条旧版及 5 条新版终态记录，旧版与原结果一致，新版四条成功和一条中断也与原任务、问题、范围逐项一致，`answer-comparison.json` 保留比对证据。

## 隔离、回滚和剩余边界

只使用空闲 loopback 11435／18765、私有合成 Profile、固定发行／模型／驱动闭包和自有 Windows Job；4 GiB 可用内存截止保留。原对照最低可用内存 17,084,708 KiB，续跑最低 17,649,612 KiB。不操作 KAG 或其资料；没有跨线程资源锁，采用启动前进程／端口／内存检查，不声称已与 KAG 握手。

收尾时自有进程和端口退出，既有 PostgreSQL PID 集合保持不变；6 份原有未跟踪用户文档哈希未变、未暂存。只删除经精确路径、无链接、归属与文件哈希校验的本轮 6.78 GB 模型副本，原模型缓存及合成数据库、失败日志和原始答案保留。见 `final-isolation-check.json`、`model-ab-1/cleanup.json`。

代码回滚标签不等于数据回滚。当前历史 schema 2 未再迁移，但旧 Desktop／旧合成快照校验器只认识 v2，不能承诺读取新 v3 记录；Runtime 与 Desktop 必须配套。未打开真实个人数据库，不提供个人数据降级、备份或恢复承诺。

Schema 只约束结构，语言、精确文字、事实和引用支持仍受模型能力影响；没有语义自动判定或自动修复器。拒答仍附上相关来源，合法引用也可能不足以支持正文。只做一个固定模型的短合成小样本，未测长资料、跨文档冲突、更多攻击变体、其他模型、PG 完整桌面、人工键鼠和正式包。后续应依据实际质量反馈选择有限的独立样本或产品改进，不用反复生成原题刷通过率，也不因本轮扩张恢复／测试框架。
