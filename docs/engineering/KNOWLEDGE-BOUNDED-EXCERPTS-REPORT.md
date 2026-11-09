# 长中文资料问答：有界原文摘录与准确引用

2026-11-09。基于 `f6ff1741e41048030ac2498e47f15daf58392aee`，分支 `codex/knowledge-bounded-excerpts`。本阶段完成 Knowledge 首个长命中片段的预算适配与必要合成验证，只作本地 Git 提交；未推送、合并、部署，仍为 Development-only。

## 用户问题与交付范围

原 K3 只接受完整检索片段。K2 合法的约 2,000 字中文片段，其 UTF-8 字节数可能已超过 `chat.balanced` 的 5,632 字节输入预算；加入问题和 JSON 后更无法容纳。首个片段不适配就拒绝，用户即使只询问其中一小段，也不能进入回答流程。这是既有全项目盘点提出的长中文证据问题，本次先完成这一可独立交付的能力。

现在仅在**首个命中片段整体超预算**时，围绕原有检索词选取连续原文句子，并保留两侧各至少一个可用的相邻句子。发送给模型的仍是冻结的原文子串；引用更新到实际摘录的 UTF-16 偏移和行号。用户可沿原有“查看来源”入口查看对应版本，回答期间发生文档更新也不会把新版正文当作旧证据。

正常能容纳的完整片段仍按排名前缀打包；后续片段放不下就停止，不用后续摘录填空、不跳过高排名命中。没有扩大字符、字节、输出或任务资源预算。前端补充了摘录及来源条件核对说明，现有引用协议和来源入口保持兼容。

## 实现与边界

- 沿用 `KnowledgeEvidenceAdmission` 的 Store／索引同步捕获、权威范围核验和不可变证据，不读取搜索摘要代替原文，不重新打开外部原文件。
- `KnowledgeEvidenceExcerpt` 复用 JDK `BreakIterator`（`Locale.ROOT`）获取捕获片段内的句子边界。沿用既有 NFKC／大小写／中日韩字与双字词法分析；从必需词中排除已出现在模型可见标题、heading 的词。
- 选取覆盖其余全部检索词的最短连续句子范围，按 UTF-16 宽度比较，同宽取最早范围；若检索词全在标题／heading 中，则从首句开始。匹配词无法覆盖时拒绝。
- 核心范围两侧各保留至少一个可用相邻句子，再对称扩展至完整序列化输入允许的最大句子窗口。预算检查包含问题、元数据、JSON 转义及 `excerpt` 标识；`TextTaskSubmission` 仍是最终预算权威。
- 只提交一个 S1 摘录，正文严格等于原文 `substring`。保留原 documentId、revision、结构 locator、heading、内部摘要，重新计算实际偏移／行号。模型只见展示字段与 `excerpt:true`，完整片段为 `false`；模型仍只能返回已准入标签，不能提供引用身份。
- 系统提示说明省略内容不能被推断为不存在。提示版本为 `knowledge-answer-v2`，Desktop 严格接受相同版本；新旧 Runtime／Desktop 混搭会拒绝，更新时须使用配套构建。本轮没有安装或替换正在使用的桌面程序。
- 没有数据库迁移、新索引、新模型、新服务、后台任务或额外模型调用。局部计算最多处理一个既有 K2 有界片段和 32 个检索词；临时数据不会新增持久证据副本。

规范变更见 [ADR-013](../ADR/ADR-013-grounded-knowledge-answer-citations.md)。历史 K3 审查和模型结果不自动覆盖此扩展。

## 成熟开源方案与复用取舍

2026-11-09 通过 GitHub 连接读取仓库元数据、许可证及以下固定提交的实现；Star 为当时快照，不作为安全保证。

| 项目与固定源码 | 维护／许可证 | 评估与实际选择 |
| --- | --- | --- |
| [LlamaIndex SentenceWindowNodeParser](https://github.com/run-llama/llama_index/blob/fe107aab65e46d1d07c80663182691970417bbd9/llama-index-core/llama_index/core/node_parser/text/sentence_window.py) | 52,849 Star，未归档；最近 push 2026-11-08 21:41:55 UTC；[MIT](https://github.com/run-llama/llama_index/blob/fe107aab65e46d1d07c80663182691970417bbd9/LICENSE) | 参考命中句携带相邻句子窗口的成熟做法。直接组件依赖 Python／Pydantic／Node 数据链，窗口以空格连接句子，不能直接保留本项目原文偏移和空白。为这一局部适配增加第二套运行时与资料管线成本过高，因此不直接引入。 |
| [Apache Lucene UnifiedHighlighter](https://github.com/apache/lucene/blob/fb76d84770a876284d8b722764e18620a1f01d6d/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/UnifiedHighlighter.java) | 3,608 Star，未归档；最近 push 2026-11-09 01:36:52 UTC；[Apache-2.0](https://github.com/apache/lucene/blob/fb76d84770a876284d8b722764e18620a1f01d6d/LICENSE.txt) | 参考基于分句边界和查询命中的原文 passage。直接高亮器绑定 Lucene Analyzer／IndexReader／索引词偏移；当前项目已有 SQLite／PostgreSQL 存储和自有词法索引，直接替换会扩大规范化与数据权限边界。本次复用同类标准 JDK 分句能力和现有词法组件。 |

没有复制上述项目源码或安装新依赖。自行实现的部分限于既有权威证据上的连续窗口选择、预算回调和引用坐标换算。省略文本的语义风险通过相邻上下文、明确模型提示、来源核对入口和必要拒绝约束；这些措施不能保证没有遗漏全部限定条件。

## 必要验证与证据

本地证据目录：`.verification/knowledge-excerpts-20261109/`。在已有测试类中只新增两个 Java 场景，复用现有原生准入宿主、API、桌面和前端测试，没有增加另一套测试框架。

| 检查 | 实际结果与范围 |
| --- | --- |
| `java-compile.log` | JDK 21，Maven 离线 `test-compile` 成功。 |
| `run-1/integration.log` | 原生准入成功，**7/7** 通过，0 失败／中止／跳过：6 个 `KnowledgeAnswerTest`，另含现有 Runtime API 原生知识问答准入、Browser 请求体前拒绝与普通 Ask 隔离场景。 |
| 新增长中文合成场景 | 真正导入、SQLite Store／词法索引、原子捕获、任务提交和结果解析；完整中文片段单独就超预算，摘录后的实际 JSON 适配。引用等于原文范围，适用／排除条件保留，推理等待期间更新文档后，来源仍为旧 revision 且可准确预览。Provider 返回固定合成答案，**没有真实模型推理**。 |
| 新增窗口边界场景 | 中文、emoji、全角拉丁与组合重音保持原文；无孤立代理字符。无分句超长文本、相邻窗口不能容纳、查询词相隔过远或无法覆盖时拒绝。原有完整片段排名前缀、转义预算、伪造标签及损坏索引拒绝场景仍通过。 |
| `dotnet-test.log`、`results/knowledge-excerpts.trx` | **16/16** `KnowledgeTests` 通过，0 失败／跳过；更新 v2 契约并确认旧 v1 被拒绝，同时保留上一增量的授权回收和迟到结果约束。Core／Desktop 编译及构建资产校验通过。复用上一增量已恢复的独立 artifacts 目录，未重新下载依赖。 |
| `frontend-test.log` | **3/3** 现有 KnowledgeAnswer 交互测试通过，覆盖实际 revision／offset 来源打开、纯文本呈现及原有交互约束。 |
| `frontend-build.log` | TypeScript、Vite 和资产清单生成通过；Vite 仍提示单个压缩 JS chunk 大于 500 kB，未在此功能中改动代码分包。 |
| `run-1/host-result.json`、`child-result.json` | 专用 Windows 宿主及 Java 正常退出，退出码 0；复核该 Java PID 和临时 HTTP 监听已不存在。既有服务未操作。 |

原生 fixture 使用新建随机 Profile 与合成数据库，经现有受信宿主完成 Windows 身份准入；没有绕过身份校验或变更系统安全设置。它由普通桌面 Explorer 隐藏启动，避免已知包宿主路径映射问题；测试入口保持原生门禁，仅缩小运行的测试选择。没有启动 Ollama、调用 KAG 端点或接触真实个人资料。未运行 PostgreSQL、完整 AssistantApp GUI 或真实模型回答质量验收；这些不在上述通过数量中。

## 剩余限制与回滚

1. 分句只在已捕获 K2 片段内进行，K2 片段边界本身可能截在原文句子中。词法覆盖和相邻句子不能证明语义完整；用户仍应核对来源，尤其是条件、否定、日期及例外。重复命中只取一个确定窗口，不汇总其他窗口中的冲突事实。
2. 无分句的超长段落、跨度太大的多词问题、问题／标题开销过大，仍可能返回现有证据预算错误。没有任意截字、拼接离散段落、自动改写查询、自动缩减问题或重试模型。
3. 引用可靠地指向实际输入，不证明模型回答忠于该输入。真实模型质量、真实桌面安装使用及 PostgreSQL 全流程尚未随本功能复验。原记忆模块的小样本限制、真实个人备份／恢复和正式分发门槛继续保留。
4. 基础回滚点为 `workspace-before-knowledge-excerpts-20261109` → `f6ff174`。仅明确提交本功能源码、测试、ADR 和报告入口；原有 6 个未跟踪文档按 `preserved-documents.json` 逐一核对 SHA 并原样保留。提交前另存差异和新增文件副本，不使用 reset／clean。可通过单独 revert 本功能提交回滚源码；回滚和升级都需要配套 Runtime／Desktop，Git 备份不等于数据库恢复。

下一步优先用合成材料做有限真实模型摘录质量验收，覆盖否定条件、重复数值、资料更新及不足证据；随后再按日常可用性路线评估启动／恢复前置工作。本报告不把这些后续工作列为完成。
