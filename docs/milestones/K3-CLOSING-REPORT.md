# K3 — Grounded Knowledge Answer + Citations — Closing Report

日期：2026-10-07（Asia/Shanghai）。

当前最终状态：**K3 — CLOSED — GO**。
Architecture Guard：**IMPLEMENTATION / SOURCE CLOSING REVIEW — APPROVED — GO**。
**K3 IMPLEMENTATION CANDIDATE — APPROVED — GO；K3 CLOSING DOCUMENTATION — AUTHORIZED。**
**FORMAL PUBLICATION — COMPLETE**；正式发布记录见下方 Publication Boundary。
下一独立活动为 **W1 Architecture / Planning**；**W1 — NOT STARTED**，不授权 implementation。

## Baseline / Candidate / Architecture Authority

- Repository：`qianlixunbai/personal-ai-workspace`。
- Authoritative main / review baseline：`e9737393916b7b256a31e7727e78be375ed490b1`。
- Approved implementation candidate：`c938e21539cc001576a243f8cfb2ba6163223d56`。
- Feature：`codex/k3-grounded-knowledge-answer`；closing 前 local HEAD = fresh remote feature = candidate，
  ahead 1 / behind 0；merge-base = main baseline，working tree clean。
- 架构权威：[ADR-013](../ADR/ADR-013-grounded-knowledge-answer-citations.md)，Status: Accepted。
  本报告记录已实施契约，不改写既有架构决定；closing docs 是独立 follow-up commit，不改写 implementation candidate。

## Delivered Capability / Compatibility / Boundaries

1. **Product / API：** 现有 Knowledge 页内 **Ask Knowledge / 基于知识回答** card，要求 explicit question
   和 explicit lexical retrieval query。native-only `POST /api/v1/knowledge/answer/tasks`，capability
   `knowledge-answer`；plain answer 后显示 **引用来源 / Sources**，使用现有 plain source preview。
   无新 top-level route/Ask mode，普通 Ask 和 Conversation 不自动检索或注入 Knowledge。
2. **Atomic evidence admission：** `KnowledgeEvidenceAdmission` 在一个 `KnowledgeStore` consistency
   boundary 内完成 K2 candidate retrieval → authoritative current ACTIVE/current READY verification
   → exact normalized chunk reconstruction → immutable evidence snapshot。核对 document/revision、
   chunk offset/line/heading、typed locator 与 representation authority；不信任 UI snippets，不重开外部原文件。
   zero hits、index unavailable 或 derived/evidence disagreement 均在创建模型任务前 fail closed。
3. **Lock boundary：** 既有 lock order **KnowledgeStore → lexical index publication** 保持。
   capture 后释放 Knowledge boundary；packing、TaskManager、provider 和 Ollama 执行不持 Knowledge lock。
4. **K2 compatibility：** 沿用 deterministic lexical AND、现有 analyzer/chunker/ranking/freshness；candidate
   set 上限 10。K2 search 仍独立于 AI/Ollama；K3 不改变搜索结果契约或默认 corpus。
   保持 UTF-16 zero-based/end-exclusive offsets、one-based inclusive line ranges 和 Unicode 安全。
5. **Frozen snapshot / mutations：** accepted task 只使用 admitted immutable snapshot。update 不替换
   admitted revision；archive 后 accepted task 可完成、future retrieval 排除 archive；physical delete 后
   accepted task 可凭 in-memory snapshot 完成，但 later preview 可能不可用。绝不替换为 latest revision，
   不为 K3 增加隐藏 durable evidence copy。
6. **Full-chunk budget packing：** 依 K2 deterministic ranking 装入 complete chunks 的 ranked prefix。
   每次检查 actual serialized input（question、evidence presentation fields、serialization overhead）；
   共用 `TextTaskSubmission` profile/input budget authority，最终 submission 再校验 character/UTF-8 budget
   与 system/output reserves。下一项不适配即停止，不 skip/truncate/summarize/excerpt；首项放不下则无 model task。
7. **AI execution / result contract：** 复用 `TextTaskSubmission.submitMapped → TaskManager → ProviderPolicy
   → LOCAL_ONLY provider → Ollama`，profile `chat.balanced`、prompt `knowledge-answer-v1`。
   当前 configured-local-model evidence 使用 `qwen3.5:4b`，不是永久固定模型要求。
   strict JSON 仅包含 non-empty plain `answer` 和 non-empty unique admitted citation labels；duplicate keys、
   trailing content、extra fields、invalid types、blank answer、zero/duplicate/unknown labels 均 fail closed，
   无 partial success、过滤或 ungrounded fallback。既有 queue/ownership/cancel/timeout/no-replay 保持。
8. **Runtime-owned citations：** model labels `S1/S2/…` 不是 authoritative IDs。
   Runtime 从 frozen snapshot 映射真实 document/title/revision/type、exact evidence range/lines、heading
   和 typed locator，形成 `TaskResult.KnowledgeAnswer`。输出 plain answer + Runtime-generated answer-level
   citations；无 inline citation grammar、model links/HTML/Markdown authority，无 path/digest/SQL/internal score 暴露。
9. **Desktop typed parser / session authority：** RuntimeClient 严格验证 task identity/capability/profile/
   prompt/status/result/citation schema 与 bounds；固定 `knowledge.answerSubmit`、`knowledge.answerGet`、
   `knowledge.answerCancel` methods。
   task 仅 accepted submission 后授权，get/cancel 要求 captured current-session authority；validated citations
   授权真实 document preview。64 个 task/in-flight submission 的 session bound、32 KiB request / 64 KiB
   ordinary response 保持；late response 不授权 replacement session，POST outcome unknown 不自动 replay。
10. **React UX / privacy：** explicit submit、bounded status/poll/cancel、plain answer、application-owned
    source controls；IME composition Enter 不提交。question/query/answer/citations 只存 React memory，
    离页/session replacement 清除，late result 不恢复清空 UI；无 autosubmit/storage/URL/history persistence。
    问题、关键词、证据、回答不进入日志或 diagnostic `toString`。
11. **Untrusted evidence / Browser：** system prompt 与 imported evidence 分离；instruction-like source
    不能取得 system/tool 权限。实际边界是 LOCAL_ONLY、no tools/Web/Finance、typed bridge 与 Runtime citation
    mapping。Browser 仍 **Translate-only**，在 body processing 前拒绝 K3，无 capability/Origin/CORS 扩张。
12. **Persistence / domains：** **Memory ≠ Knowledge ≠ Finance**；`knowledge.db` 与 derived disposable index
    保持原 ownership。NO K3 DB、answer/citation history、schema migration、backup format change 或 Conversation
    dependency；reload/session replacement/Runtime restart 不恢复 K3 answer/task/result。
    Finance Integration 仍 **BLOCKED pending F0 authoritative Finance Reality Sync**。

## Verification Evidence — Inherited Local Execution

以下是 approved implementation candidate 的 **inherited accepted local execution evidence**，
不是本轮执行，也不是 Architecture Guard 独立运行测试/模型/Windows 的结果。

| Gate | Accepted inherited result |
| --- | --- |
| Java | 137 PASS；0 failures/errors/skips |
| Desktop | 196 / 196 PASS |
| Frontend | 40 / 40 PASS；6 files |
| Real-model K3 contract gate | qwen3.5:4b / chat.balanced；3 / 3 representative attempts PASS |
| Final clean WorkspaceSanity | PASS；real WPF/WebView2/React/Runtime |

**Real-model gate：** 三次代表性尝试分别为 Chinese single-source、bounded multi-source、包含
instruction-like prompt injection 的 source。验证 strict JSON、non-empty admitted citations、Runtime citation
mapping、exact revision/range preview、no Web/tools。该 gate **不证明每句生成回答均被证据语义蕴含**。

**WorkspaceSanity：** final clean uninstrumented flow PASS，包含 real physical Pinyin composition、
Chinese lexical search、source preview、reload/session clearing 和 clean shutdown。
首次 genuine-pinyin-composition failure 属于历史执行失败；K3 未修改 KnowledgeSearch/input/composition path。
补充 instrumented real-project diagnostic 以 genuine physical composition PASS，随后 harness byte-for-byte
restored，final clean WorkspaceSanity PASS；无需 permanent harness remediation。

**Formal closing docs tests/builds executed：NONE。** 未重跑 Java/Desktop/Frontend/WorkspaceSanity/
Ollama/real-model gate/build/package；无 production/test/harness/script/config/schema 改动。
验证仅限 documentation diff、`git diff --check`、commit scope 与 fresh Git refs/ancestry/working tree；
不把 acceptance coverage points 或继承结果计为本轮执行。

## Known Limitations / Explicitly Deferred Scope

- Retrieval 仍是 explicit user-controlled lexical AND keywords；无 automatic query rewrite 或 semantic/vector retrieval。
- Citations 显示模型引用的 admitted evidence，不是每句回答被蕴含的正式证明；用户可检查真实来源。
- Answer/citations ephemeral；physical deletion 可使 later citation preview 不可用；无 inline citation grammar。
- 无 Conversation integration、ordinary Ask automatic retrieval、Web、Vision 或 Finance integration。
- 未增加 embeddings/hybrid retrieval、额外 source formats、persistent answer/citation history、cloud 或 autonomous tools。
- **W1 — Controlled Web Access：NOT STARTED**；下一独立活动 **W1 Architecture / Planning**，列出不授权 implementation。
  Finance 保持 BLOCKED pending F0；现有安全、领域与备份边界不扩张。

## Architecture Guard Closing Result / Publication Boundary

Architecture Guard **独立审查 actual remote implementation source**：
`e9737393916b7b256a31e7727e78be375ed490b1` → `c938e21539cc001576a243f8cfb2ba6163223d56`。
reviewed remote diff：**17 production files、4 test files、0 docs（本轮 closing 前）**。

**IMPLEMENTATION / SOURCE CLOSING REVIEW — APPROVED — GO。**
Blocking findings: **0**；required production fixes: **0**；required new tests: **0**；required reruns: **0**。
执行证据按上节继承，不声称 Guard 重跑了这些 gates。

**K3 — CLOSED — GO；FORMAL PUBLICATION — COMPLETE。**
Architecture Guard **FINAL CLOSING-DOCUMENTATION REVIEW — APPROVED — GO**；
**FORMAL PUBLICATION — APPROVED — GO**。Documentation blockers / required documentation remediation: **0**。
Implementation candidate：`c938e21539cc001576a243f8cfb2ba6163223d56`。
Closing docs candidate：`dbbfbd93f87569c6116abe811a9d7422fc2d81ba`。
Published through merge commit：`5f0f94c13d8f2d2695e177c8ce70e94d6cd0e36e`。
正式发布仅指源码发布到 main；显式 merge 后追加一笔 docs-only publication-finalization commit。
本轮 publication tests/builds executed：**NONE**；上方技术内容与 inherited execution evidence 保留。
Publication adds no new product behavior；无额外 production/test/harness/script/config 改动，无 tag/release/package publication。
下一独立活动：**W1 Architecture / Planning**；**W1 — NOT STARTED**。列出 W1 不授权 implementation，不启动 W1。
