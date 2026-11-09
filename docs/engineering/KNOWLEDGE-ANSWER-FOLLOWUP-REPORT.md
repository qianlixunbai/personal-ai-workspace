# 回答版本中断复核与未见题验证

2026-11-09，基线 `20a9e74`，分支 `codex/knowledge-answer-followup`，回滚标签 `workspace-before-knowledge-answer-followup-20261109`。只做必要复核、扩展既有手工探针和文档校正，**没有再次修改产品逻辑、提示词、Schema 或模型参数**。仅本地开发，仍为 Development-only。

## 根因核实与修复状态

核对了[上一轮报告](KNOWLEDGE-ANSWER-FIDELITY-REPORT.md)、基线源码与本地原始收据，证据保存在 `.verification/knowledge-answer-followup-20261109/root-cause-evidence.json`。

1. 兼容中断：旧 Desktop `ParseKnowledgeAnswerTask` 只允许 `knowledge-answer-v2`。新 Runtime 返回 v3 后，客户端以 `InvalidResponse` 拒绝已受理 POST 的响应，`SubmitKnowledgeAnswerAsync` 将它保守映射为 `OutcomeUnknown`，没有自动重发。本轮手工探针捕获异常后在 `finally` 执行 `app.ExitAsync()`；模型关闭后读取的任务为 `FAILED / KnowledgeInterrupted`。这是**客户端版本不配套及探针退出造成的中断**，不是 JSON Schema 生成失败，也不是“产品遇到未知结果就自动退出”的证据。
2. 必要修复已在 `2dc240f`：Desktop 与合成快照校验只接受已知 v2／v3，未知版本继续拒绝。上轮 22 个 Desktop 测试及混合版本快照验证通过，本轮源码核对无遗漏，不重复添加同类单元测试或放宽版本白名单。
3. 原 RFC 问题原文为“根据所选 RFC 2606 资料，哪个顶级域名用于明确无效的域名？请用中文简短回答。”答案为 `invalid`，引用中包含 `.invalid`。问题明确要求中文，未明说保留前导点；前导点来自源文和预设字面检查。答案指向相关实体，但不满足该精确检查，也未按明确要求使用中文，不能把它夸大为引用越界或选错了来源。
4. `OllamaProvider` 和 `KnowledgeAnswerParser` 原样传递答案字符串，未去点或翻译。旧提示的语言／逐字保留要求不足是可改善点，但不是已经证明的单一因果解释。原题没有重跑，原失败记录继续保留，不能宣称原题已实测修复。

同时修正上一轮报告两处表述：退出由手工探针触发；冻结题集哈希属于源码文件，由 preregistration JSON 记录，并非两个文件同哈希。未覆盖任何原始失败日志。

## 三道冻结新题

继续采用已审查的 [Ollama 原生结构化输出](https://docs.ollama.com/capabilities/structured-outputs)、LlamaIndex 编号来源和 Haystack 引用映射原则，许可、维护、安全边界及不用新框架的取舍见[既有评估](KNOWLEDGE-ANSWER-FIDELITY-REPORT.md#成熟方案与安全取舍)。本轮不下载新模型、升级依赖或引入通用评测框架。

复用 `RuntimeLifecycleProbe.KnowledgeQuality.cs`，只增加独立三题集及显式 `gui-knowledge-quality-unseen`／`gui-knowledge-quality-unseen-inspect` 模式，保留原 A/B 五题不变。推理前冻结 `frozen-quality-source.cs.txt`，SHA-256 `C4D97D683F11D1ABF91534534EE43E5B1766326E006088884D21E1B2F7FD5206`，记录在 `preregistered-cases.json`；期望值不发送给模型。每题只提交一次，无重试、无答案后处理。

使用全新合成 Profile `1c911ca52f3a4e908726f8d2c41222ad`，固定 Ollama 0.40.1／Qwen3.5 4B／llamacpp，模型 digest 沿用上轮。Runtime JAR 原封复用，SHA-256 `48EBC068E2FF87DF391F676BCDF37956F56CE9B732F198A08ABF9AFEEDF33959`。完整 AssistantApp／WPF／WebView2 启动后，探针经真实 Native RuntimeClient／HTTP／SQLite 导入、问答、来源预览及历史读取；未通过 DOM 点击，不能计作新 UI 交互验收。

| 新题与预期 | 原始答案 | 核对 |
| --- | --- | --- |
| Rook 隔离主机后缀与生产流量限制；中文 | 合成 Rook 资料中，隔离主机使用后缀.quartz-lab。该后缀的主机不可承载生产流量，因为资料明确指出隔离主机不得携带生产流量。 | `.quartz-lab` 完整，未混用 `.amber-live`，否定条件正确，中文完整句。 |
| Nacre 只检查不写文件的完整参数；中文问题明确要求英文句 | The complete parameter that only checks without writing files is --preview=on. | `--preview=on` 完整，没有把默认 off 当作只读模式；英文完整句。 |
| Linden 沙箱金额、模式与未给出的实时上限；中文且保留金额写法 | 合成 Linden 的沙箱转账上限为 EUR 2,406.50，仅适用于审计模式（audit-only）。该上限不适用于实时转账。未给出实时转账上限。 | 保留 `EUR 2,406.50` 与 `audit-only`，限制和缺失事实说明正确，没有编造实时金额。 |

三条均引用选定文档源版本 1，偏移映射到对应原文。自动字面／有无汉字检查 3/3，本轮另逐条复核句子语言、相关性、否定和条件；不是独立人工评分。**只有当前版本的三个短样本，未构成与旧版可比的质量评估，不宣称新方案更优。**

## 最小验证、隔离与边界

证据根 `.verification/knowledge-answer-followup-20261109/`，由 Git 忽略，仅保留本地：

- `dotnet-build.log`：只重新编译已变更的既有探针，0 warning／0 error；原 Runtime 与前端无变化，未重跑 Java、前端或无关测试矩阵。
- `run-1/quality-unseen-receipts.json`：三条首次响应均正常解析为 QUEUED；随后三条 SUCCEEDED，无 OutcomeUnknown、重复 POST 或重放。`quality-unseen-result.json` 为 submitted=3、succeeded=3、retries=0。
- `run-1/ollama.log`：4 次 `/api/chat`，一次准入与三次回答。`run-1/completion.json` 退出通过，所有自有工作进程结束、11435／18765 端口关闭，最低可用内存 17,282,136 KiB；保留 4 GiB 资源截止。
- `run-1/history-inspect`：模型关闭后独立 AssistantApp／JVM 重开，读取三条已存记录，原问题、关键词、任务、答案、范围逐项一致，0 模型调用。实际恢复内容保存在 `quality-unseen-inspect-restored.json`。
- 六份既有未跟踪文档哈希及既有 PostgreSQL PID 集合保持不变；未访问个人数据或更改服务。启动前未发现 Ollama／llama-server／Python／Java 计算进程，使用专用端口和自有 Job；没有跨线程锁，不声称获得 KAG 显式资源握手。最终检查见 `final-isolation-check.json`。
- 仅清理本轮创建、精确路径与哈希核对后的模型副本，释放 6,787,837,108 bytes；原缓存、合成数据库、原始回答与历史证据保留，见 `run-1/cleanup.json`。外层命令误用 `$LASTEXITCODE` 判断纯 PowerShell 脚本而报错，随后只读核实完成记录及目标目录确实不存在，没有重试删除；保留 `cleanup-wrapper-check.json`，不把外层报错隐去。

三题通过证明当前已配套版本能正常受理、生成和恢复历史；不保证任意语言、长资料、其他模型或更多攻击样本的语义保真。Schema 只约束结构，合法引用不等于事实支持。原 RFC 单题未重测、PG 完整桌面与正式发布未验收。历史 schema 2 和 v3 记录的既有回滚边界不变：代码回滚不等于数据回滚；没有新增个人备份／恢复能力。
