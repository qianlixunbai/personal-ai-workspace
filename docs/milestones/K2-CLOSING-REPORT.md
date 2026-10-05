# K2 — Deterministic Lexical Retrieval — 阻塞报告

日期：2026-10-06（Asia/Shanghai）。

## Result / Exact Blocker

**K2 PARTIAL / BLOCKED。不得认定为 LOCAL ACCEPTANCE PASS、CLOSING CANDIDATE — GO 或 CLOSED — GO。**

最终一次全量 Java / Desktop / Frontend 回归和 production build / local Release publish 已通过。
真实 Windows hard gate 第一次 harness 执行在生产 WPF driver 启动前失败，输出：

```json
{"result":"FAIL","check":"controlled-api-status","failureType":"TypeError"}
```

准确阻塞位于 `scripts/knowledge-search-smoke.py`：`api()` 的 `binary=True` 同时控制请求体
和响应解析（line 118）。`upload()` 用它发送 source bytes（line 127），因此导入接口的 JSON
响应作为 Python `bytes` 返回；line 130 的 `job['state']` 触发 TypeError。不是 FTS5 capability
失败，也不能据此推断 production Windows/Pinyin 检索已通过。

遵守授权中的 FINAL STOP RULE：停止实现、修复、重跑和后续 gates，仅记录阻塞与 Git 状态。
Pinyin、真实 Windows search / recovery / backup flow、fresh-canary privacy audit 和 launcher
sanity 尚未完成。该 harness 的 backup restore 同样使用此 response flag，后续修复必须区分
请求内容类型和响应内容类型，而不是仅绕过当前异常。需要下一轮明确授权后恢复工作。

## Reality Gate / Git Baseline / ADR-012

- 起始 local main / HEAD / fresh origin/main 精确为 `2323124f76ce34522c78016f2540dccc5e9ed983`。
- command-local 127.0.0.1:7890 proxy 的 `fetch --prune origin` 成功；远端仅 main；起始树 clean。
- 完整核对 K1 production storage/parser/limits/private directory/ingestion/backup/controllers/security/
  RuntimeClient/WorkspaceKnowledge/WorkspaceBridge/React，以及 ADR-011/current architecture/K1 report/pom/yml。
- actual bundled sqlite-jdbc 3.53.2.0 / SQLite 3.53.2 的 FTS5 create/insert/MATCH/BM25 capability PASS。
  最初 probe 错用 aggregate BM25 context，修正为 per-row 查询后通过；不是引擎缺少 FTS5。
- feature branch：`k2-deterministic-lexical-retrieval`，精确从指定 main baseline 创建。
- 第一笔 `3baf7c2` 为 docs-only ADR-012；Accepted 来源为 Architecture Guard K2 approval。
- 被测试/本地打包的 implementation candidate：`3a5efc1d8e1fe2060c7b7a648b4339b21bb1e790`。
  阻塞时 HEAD 也是此 SHA；之后仅提交本阻塞报告及 current-doc 状态，最终文档 HEAD 见 Git。

## Implementation / Compatibility / Boundaries

1. **K1 Compatibility / Knowledge Truth Boundary：** `KnowledgeStore.SCHEMA_VERSION=1`，权威四表
   schema 未变；仅增 corpus read helpers 和 mutation notification。未给 knowledge.db 增 FTS/chunk/index 表。
   K1 foundation 11、Backup 6 聚焦测试通过；历史 K1 Closing Report / ADR-011 未修改。
2. **Finance Freeze：** 无 Finance 实现依赖。M0–M5 / K1 CLOSED — GO、K0 APPROVED — GO 保持；
   Finance Integration BLOCKED，pending authoritative Finance Reality Sync。
3. **Index Layout / Index Privacy：** 独立 `knowledge/index/lexical.db` / `index/staging/`；现有
   owner-only ACL/no-links policy 在派生写入前使用。是 account-private plaintext，不是 encryption。
4. **Index Schema / FTS5 Capability：** 独立 index schema 1，metadata/chunks/lexical FTS5，应用
   token streams 进入 ascii tokenizer；FTS5 capability 有实际 bundle probe 与 Runtime 测试。
5. **Chunker Version：** lexical-chunk-1；K1 exact normalized text + typed locators；每 locator 分块，
   最大 2048 code points / 8192 UTF-8 bytes，newline→whitespace→code-point split，总 chunks ≤100000。
   UTF-16 offsets end-exclusive、line numbers one-based inclusive；无 surrogate split、虚构 page/citationId。
6. **Analyzer Version / CJK Semantics：** lexical-1；NFKC + Locale.ROOT lowercase；Latin/general
   letter/digit runs；Han/Hiragana/Katakana/Hangul unigram + adjacent bigram；type-prefixed hex identifiers。
   不改变 normalized truth，不作 stemming/synonym/fuzzy/Pinyin transliteration/AI segmentation。
7. **Query Contract / FTS Injection Boundary：** 非空、well-formed Unicode、无 controls、≤128 code
   points、≤32 unique tokens；超限 controlled error，不 truncate。FTS 只接收 quoted internal identifiers
   与 AND；用户 operators/quotes/column selectors 不作为 FTS syntax 执行。所有 token 在一个 chunk 满足。
8. **Ranking Contract：** lexical-rank-1；bm25 title/heading/body 4.0/2.0/1.0；ascending BM25，
   binary canonical documentId，numeric sourceRevision，chunkOrdinal。score 不进入 native/JS hit DTO。
9. **Corpus Fingerprint / Index Freshness：** SHA-256 length-framed versions（含 SQLite engine）+ sorted
   ACTIVE/current READY documentId/revision/representationDigest；metadata 保存 fingerprint/counts。
   query 前后核对 authoritative fingerprint，disk fingerprint 也校验；不匹配丢弃，controlled not-ready。
   stale/coalescing 和 disk fingerprint mismatch 测试通过；没有独立覆盖每一种 query 中途 mutation interleaving。
10. **Rebuild Executor / Coalescing：** 专用 knowledge-index，1 worker，capacity-1 queue、running CAS
    和单 rerun flag；200 repeated requests 的聚焦验证通过，无 ingestion/AI TaskManager 复用。
11. **Publication：** private candidate→DDL/token insertion→counts/version/schema/quick_check/FTS integrity
    /page/file cap→close→latest fingerprint gate→same-volume atomic replacement→READY；mutation 期间
    fingerprint 不同则 coalesce。未声明与 knowledge.db mutation 是跨 DB atomic transaction。
12. **Crash Recovery：** missing/corrupt/wrong metadata/version 派生 DB 重建，Knowledge truth 正常启动；
    不作 LIKE scan fallback。仅清理 live task 明确拥有的 candidate；未知/无 live ownership 的 crash orphan
    保留，可能需要后续显式维护。恢复测试通过，但 real Windows recovery gate 未运行到该阶段。
13. **ACTIVE/current READY Scope / Revision Change：** active current corpus；旧 retained revision 不入默认
    search；新 READY 更换 corpus；failed revision 保持旧 current。Runtime lifecycle/revision 测试通过。
14. **Archive/Restore/Delete：** 成功 authoritative mutation 后 invalidate/schedule，索引失败不回滚真值；
    Runtime archive/restore/delete 验证通过，Windows production flow 尚未执行。
15. **Search API / Search Status：** native POST /api/v1/knowledge/search（query、optional limit，default/max 10）；
    GET /search/status、POST /search/rebuild（exact empty object）；strict scalar/extra-field checks；安全 bounded
    READY/BUILDING/STALE/FAILED/counts，32 KiB body。controlled query/index error vocabulary；无 raw SQLite details。
16. **Bridge Contract / Session Authorization：** typed knowledge.search/searchStatus/rebuildSearchIndex；
    32 KiB request / 64 KiB ordinary response。returned document IDs authorize current captured session；
    rotation 清除，late response 不授权新 session；Desktop tests 验证 get/preview、rotation、late response。
17. **Result DTO / Snippet/Highlight Safety：** documentId/title/sourceRevision/sourceType、exact offset/line、
    nullable heading/plain snippet/highlightRanges；title≤160 UTF-16，heading≤96，snippet≤384，ranges≤16，hits≤10。
    Unicode-safe snippet-relative ordered ranges；无 digest/fingerprint/tokens/score/path/bytes/rowid/SQL。
    Desktop worst-case escaping bridge budget PASS；Frontend 使用 text nodes/mark，无 HTML execution。
18. **Query Privacy：** POST body、React state only；不写 URL/storage/query history/logs；Runtime captured-output
    fresh query canary PASS。Frontend ephemeral/no-storage tests PASS。真实 Windows fresh query/source/UDF/package
    privacy audit 因前置失败未执行，不能宣称该 hard gate PASS。
19. **Browser Boundary：** filter native auth / Browser denial 在 body buffering/业务处理前完成；search/status/
    rebuild malformed Browser requests 拒绝，API test PASS；CORS/origin/capability 未扩大。Windows proof 未执行。
20. **No AI/Ollama Dependency：** index/analyzer/chunker/query 无 Provider/Ollama/TaskManager；API test generation
    calls 0。fixture Runtime 可在配置不可用 Ollama endpoint 下启动；完整 Windows no-dependency flow 尚未完成。
    搜索结果不自动进入 Ask/Conversation/Memory/Summarize/Translate。
21. **Knowledge Backup v1 Compatibility / Restore/Rebuild / Search Parity：** format 未修改；集成 Runtime 测试
    证明 exported backup→empty target restore→index absent→rebuild→same logical hits/locators。
    权威来源/backup 不携带 lexical DB；genuine Windows production restore/parity 尚未执行。
22. **Frontend UX：** 现有 #/knowledge 内 explicit keyword input/button、status/building/rebuild、bounded result
    list/plain snippets/line locators/open exact preview/empty/unavailable states；输入变化、session rotation、离开页面
    清除结果或 query，已知 corpus mutation 刷新清除结果。无 AI search/RAG/Knowledge Answer。
23. **Accessibility：** labels、form/Enter submit、composition Enter guard、result heading/preview focus、live status、
    progressbar、可读 empty state；component tests 通过。真实键盘、125% overflow、Pinyin gate 未执行；无 screen-reader certification 声称。
24. **Deferred K3 Scope：** 仅 stable document/revision/offset/line retrieval reference；无 citation renderer、
    prompt assembly、LLM grounding、embedding/vector/semantic/hybrid/PDF/DOCX/OCR/collections/watch/cloud/Agent。

## Tests / Real Windows Acceptance / Privacy / Production Build

| Gate | 实际结果 |
| --- | --- |
| Actual bundled FTS5 probe | PASS，SQLite 3.53.2 |
| Runtime lexical focused | 最终 10 PASS；foundation/backup/index batch 27 PASS |
| Native HTTP / Browser / query privacy focused | 1 PASS |
| Desktop Knowledge focused | 11 PASS |
| Frontend Knowledge/search focused | 13 PASS；一次 fixture highlight offset 错误在开发阶段修正后通过 |
| Final Java full once | 134 PASS，0 failure/error/skipped |
| Final Desktop full once | 273 PASS，0 failed/skipped |
| Final Frontend full once | 119 PASS / 12 files |
| Frontend production build once | PASS，TypeScript + Vite + assets manifest |
| Runtime package | PASS，skipTests，无第二次 full regression |
| Local self-contained Release publish | PASS，sourceDirty false，candidate 3a5efc1 |
| K2 Windows harness attempt | FAIL，upload response TypeError，driver 未启动 |
| Real Windows Pinyin | NOT EXECUTED |
| Windows integrated search/recovery/backup | NOT EXECUTED |
| Fresh query/source/UDF/build/package privacy audit | NOT EXECUTED |
| Minimal launcher sanity | NOT EXECUTED |

本地 ignored package：`artifacts/K2-lexical-candidate-3a5efc1`；manifest 指向完整 candidate SHA，
frontend CSS `index-BhsRBZtO.css` / JS `index-DjtfgzvZ.js`，self-contained win-x64 Desktop + Runtime JAR。
Publish 成功不等于 launcher/product acceptance PASS；未对 package 做额外运行级 K2 inclusion gate。
未产生 successful Windows evidence 文件，harness 输出仅 failure stage/type；`finally` 执行 fixture server /
owned Runtime cleanup，未把 raw source/query 写入报告或 repository。

**Coverage points ≠ independent executions ≠ independent gates。** Driver 计划 22 coverage points；
本次实际 1 次 harness attempt、0 次 WPF driver executions、0 个完成的 Windows/Pinyin acceptance gates。
不能将计划覆盖点、unit assertions 或成功 publish 数量当作 Windows executions/gates。

## Historical Tests Not Re-run / Known Limitations / Git Status / Next Step

未重跑 M5 完整 package matrix、真实 Ollama inference、1000-turn stress、历史 Chrome suite、完整旧
IME matrix 或 988 KiB historical cases；packaging logic 未改变。Final full regression 各执行一次。

已知限制：Windows gates 被 harness bug 阻塞；缺少独立 query 中途 mutation interleaving test；
crash orphan staging 保留；词法 AND/CJK bigram 不是 semantic/fuzzy/Pinyin search；私有 plaintext 与
既有 K1 capacity/format/permission 保证不扩大。正式 Closing Review 尚未进行。

阻塞时 branch `k2-deterministic-lexical-retrieval` / HEAD `3a5efc1d8e1fe2060c7b7a648b4339b21bb1e790`，
working tree clean；相对 baseline 已修改 36 个文件（1265 insertions / 15 deletions），7 个独立本地 commits。
随后只有本报告与 current-doc 阻塞状态的 docs-only commit。没有 push、main merge、tag 或 release；
远端 main 保持起始 fresh-verified baseline，未重新写远端。最终文档 HEAD 与 clean tree 以交付 Git 输出为准。

推荐下一步：明确授权恢复 K2，先修复 harness 的 request/response type 分离，再完成真实 Windows/
Pinyin/恢复/隐私/launcher gates；按实际 owning-code 变化决定必要重测，避免无理由重复全部回归。
全部 hard gates PASS 后才可形成 CLOSING CANDIDATE，随后提交 Architecture Guard 独立代码级 Closing Review。
