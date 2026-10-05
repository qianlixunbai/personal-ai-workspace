# K2 — Deterministic Lexical Retrieval — Closing Report

当前最终状态：**K2 — CLOSED — GO**。Architecture Guard 独立 source-level Closing Review 已完成，
决策为 **K2 CLOSING REVIEW — APPROVED — GO**；正式结论见文末。

## Historical Local Closing Candidate

日期：2026-10-06（Asia/Shanghai）。

当时为 **K2 — IMPLEMENTED / LOCAL ACCEPTANCE PASS；K2 CLOSING CANDIDATE — GO**。
第四轮一次真实 Windows integrated flow、Pinyin、fresh-canary privacy 和最小 actual launcher PASS。
见文末「Blocker Recovery Attempt 4」。以下前三次历史失败和正确执行 STOP 的事实保留。
**不是 K2 CLOSED — GO；正式 closing authority 仍属于 Architecture Guard。**

## Historical Attempt 1 — Result / Exact Blocker

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

## Blocker Recovery Attempt 2 — 2026-10-06

### Reality Gate / Remediation

起始 feature HEAD `82439a8c71d49eaaa309c19713c0c7abc65da3e6`，working tree clean。
command-local proxy fresh fetch 成功；main / origin/main 仍为
`2323124f76ce34522c78016f2540dccc5e9ed983`，baseline → candidate → docs ancestry 正常。
无 material conflict、main rewrite 或不明改动。

恢复提交：`800a8dc094091188c1aa112e24d6e4d423b0a71f`
（`fix: separate acceptance request and response modes`）。
`scripts/knowledge-search-smoke.py` 分开 request_mode（json/bytes/none）与
response_mode（json/bytes/empty）。Source import / restore 为 bytes request + JSON response；
普通 API 为 JSON + JSON；backup download 为 bytes response；client DELETE 204 显式 empty。
无 `job['state']` special case。真实 source imports 本轮成功，WPF driver 实际启动。

生产 Java/Desktop/Frontend code、schema、Backup、安全/生命周期语义均未修改，无新增依赖。
只新增 **1 条长期 regression**：复用已有 Mockito/source-read seam，SQL 命中及旧 source 已读后
执行真实 archive，在 final authoritative fingerprint check 前改变 corpus；旧结果受控
INDEX_NOT_READY 拒绝，重建 READY 后无旧命中。无需生产 test hook。

Acceptance driver 增加真实 composition Enter guard 的验证步骤（本轮未执行到），以及
IndexedDB/Cache 检查。Fresh-canary scanner 收窄到 K2 owning sources、相关 build/assemblies、
Runtime application archive entries、当前 candidate package 和 K2 evidence（本轮未执行到）。

### 实际执行 / 新阻塞 / STOP

| 本轮验证 | 实际结果 |
| --- | --- |
| Python syntax | PASS（最终 harness 修改后也作静态 parse） |
| 最小 deterministic helper smoke | 1 次 PASS；bytes→JSON、JSON→JSON、bytes download、204 empty |
| queryDiscardsHitsWhenCorpusMutatesBeforeFinalFingerprint | 1 条 / 1 次 PASS，0 failure/error/skipped |
| acceptance executable Release build | 1 次 PASS；FrontendSkipBuild=true，未重建 frontend/runtime package |
| 真实 Windows integrated attempt | 1 次，FAIL；production WPF/WebView2/React/Runtime 已启动 |
| Real Windows Pinyin / composition Enter | NOT EXECUTED |
| Windows lifecycle / recovery / backup 后续阶段 | NOT EXECUTED |
| 完整 fresh-canary logs/UDF/source/build/package privacy | NOT EXECUTED |
| Minimal actual launcher sanity | NOT EXECUTED，遵守新 gate failure 后 STOP |

精确失败：driver stage `no-indexeddb-or-cache-query-storage`，`InvalidOperationException`；
Python wrapper stage `real-acceptance-no-indexeddb-or-cache-query-storage`，`RuntimeError`。
失败位于本轮新增 harness assertion：async JS 返回 Promise，而 `Js()` 直接使用
`CoreWebView2.ExecuteScriptAsync` 的 JSON 返回字符串并与 `"true"` 比较，没有在页面中捕获
resolved Boolean。记录未包含 resolved storage Boolean；这是 harness 结果读取问题，
**不能据此认定生产 IndexedDB/Cache 泄漏，也不能宣称这项 privacy gate PASS**。
没有继续修复、重跑或执行剩余 gates，没有发现足以授权 production 修复的证据。

失败前实际通过：production keyword input、Latin fresh-canary search、title/heading/body 排名、
exact preview offset、deterministic logical ordering、query absent URL/localStorage/sessionStorage。
安全 evidence `.verification/k2-attempt.json` 记录 19 个 sequential check labels；其中含等待和
重复操作，**不是 19 次测试、19 个独立 gates 或成功 integrated flow**。
累计两个 harness attempts：首次 0 WPF executions；本轮 1 WPF execution，无成功 integrated flow。
清理后未发现此 fixture 的 Runtime/acceptance 进程，8765/18768 无监听。

### 未重跑 / Package / Docs / Final State

Java full 134、Desktop full 273、Frontend full 119、production build 和 self-contained publish
继承 implementation candidate `3a5efc1d8e1fe2060c7b7a648b4339b21bb1e790` 的既有 PASS，
本轮明确未重跑：只改 harness/test/docs，生产 bytes 未变。
未重跑历史 browser/IME/stress/package matrix，无真实 Ollama inference。
现有 ignored package manifest commitSha 仍为该 candidate，sourceDirty=false；没有重复 publish。
实际 launcher 和 package 运行级 inclusion gate 尚未完成，不能用 manifest/publish 替代。

已加入 `docs/roadmap/V1-ROADMAP.md`，同步 README、STATUS、current architecture 入口及当前阻塞。
保留首次失败、未启动 WPF/Pinyin 和正确执行 STOP 的历史事实；未追改 K1/ADR-001..011。
V1 roadmap 是未来规划，未启动 K3/W1/Vision/Finance；K2 不是 CLOSED — GO。

最终停在 `k2-deterministic-lexical-retrieval`，仅新增 remediation 与 docs commits；
final docs HEAD 由交付 `git rev-parse HEAD` 输出确定，交付树应 clean。
无 push、merge main、tag、release 或历史 rewrite。**K2 PARTIAL / BLOCKED；不形成 CLOSING CANDIDATE。**
下一步需窄授权修复 async JS Boolean 读取，再完成尚缺 Windows/Pinyin/privacy/launcher gates；
继续继承固定 production candidate 的全量回归，不机械重跑。全部 gates PASS 后交 Architecture Guard
独立 Closing Review。

## Blocker Recovery Attempt 3 — 2026-10-06

起始 HEAD `d07ae1be1ee81dad01e410b1d7f7cc30b60fd0b0`，feature branch/tree/ancestry 正常且 clean。
command-local proxy fresh fetch 成功，main / origin/main 仍为指定 K1 baseline
`2323124f76ce34522c78016f2540dccc5e9ed983`。

### Exact Async Root Cause / Minimal Remediation

历史 attempt 2 只捕获异常类型。原 `Require()` 使用 `new InvalidOperationException()`，
没有自定义 message；stack 和 resolved JS Boolean 未保留，不能补造历史 message/stack。
本轮一次嵌入真实 flow 的 focused smoke 实测 `(async()=>true)()` 经 `ExecuteScriptAsync`
返回 JSON `"{}"`，不是 `"true"`；await C# API completion 不等于取得 Promise resolved Boolean。
失败确实发生于 completed storage assertion 结果读取之前，不是生产泄漏证据。

修复提交 `061f9c8481efd358cf9296fbfaf28224b829f85c`：仅 acceptance `Program.cs`。
`AsyncBoolean()` 在页面内 await 表达式，将 done/ok/value 保存在临时变量，复用现有
10 秒有界 `WaitJs` 取得完成后的 Boolean，最后删除临时变量；不使用额外自动化框架。
IndexedDB databases 和 Cache namespaces 为空的原严格条件保留，实际正结果证明当前 fresh
query 不在两种存储内，没有降级为 API-exists/no-exception，也没有输出私有 browser data。
失败诊断只输出固定 assertion label、exception type、本 driver 方法名与行号；不输出 raw
HTTP/credential/source/query exception text、文件路径或其他 private data。

### 本轮实际执行 / New Blocker / STOP

| 验证 | 实际结果 |
| --- | --- |
| acceptance driver Release compile | 1 次 PASS；FrontendSkipBuild=true |
| async focused smoke | 1 次 PASS，嵌入同一 WPF execution：legacy Promise object / completed Boolean |
| Windows integrated flow | 1 次 FAIL，production WPF/WebView2/React/Runtime 真正启动 |
| IndexedDB / Cache query privacy | PASS，completed positive Boolean；当前两个 namespace 均为空 |
| Bridge metadata / literal snippet / 125% overflow | PASS |
| Pinyin owning gate | FAIL 前置焦点检查；真实 composition / Enter guard / Chinese search NOT EXECUTED |
| 后续 lifecycle / stale / rebuild / recovery / backup | NOT EXECUTED |
| 完整 fresh-canary logs/UDF/build/package privacy | NOT EXECUTED |
| Minimal actual launcher sanity | NOT EXECUTED |

精确新失败：driver gate/message `physical-keyboard-workspace-focus`，
`InvalidOperationException`；wrapper gate `real-acceptance-physical-keyboard-workspace-focus`，
`RuntimeError`。安全 stack：`Program.Require:47` → `<Pinyin>d__37.MoveNext:116`
→ `<Drive>d__34.MoveNext:89` → `<<Main>b__18_2>d.MoveNext:34`。
`pinyin-installed` 已通过，但第一轮 `yusuan` 的首个字符前
`Native.GetForegroundWindow()==hwnd` 为 false，`Key()` 尚未调用。
这是 harness/environment 前台窗口前置条件失败；未证明 production bug 或真实 IME 行为失败。
没有进一步诊断/修复/重跑，也没有执行其余 gates。清理后 fixture Runtime/acceptance 进程
不存在，8765/18768 无监听。安全 evidence 为 ignored `.verification/k2-attempt.json`。

### Inherited Evidence / Final State

没有新增 production test，没有重跑已通过 query-race regression 或 Java 134 / Desktop 273 /
Frontend 119 full suites；没有 production build/publish、历史 IME/browser/stress/package matrix
或真实 Ollama inference。生产候选及 package identity 继续是
`3a5efc1d8e1fe2060c7b7a648b4339b21bb1e790`，生产代码/packaging inputs 未变。
V1 Roadmap 未改写或扩展；只同步 current docs 的准确 blocker，保留 attempt 1/2 历史。

本轮一条 WPF execution 内的 smoke/assertion labels 不算独立执行或成功 integrated acceptance。
最终分支 `k2-deterministic-lexical-retrieval`；final docs HEAD 以交付 Git 输出为准，树 clean。
无 merge main/push/tag/release。**K2 PARTIAL / BLOCKED，尚不构成 CLOSING CANDIDATE。**
下一步需窄授权核对并恢复 Pinyin 前台窗口前置条件，再完成缺失 gates；全部通过后交由
Architecture Guard 独立 Closing Review。

## Blocker Recovery Attempt 4 — Final Local Acceptance — 2026-10-06

### Reality Gate / Foreground Root Cause / Remediation

起始 feature HEAD `579a4e122ce6c67ff982545159ee568c075e2fdb`，tree clean，ancestry 正常。
command-local proxy fresh fetch 成功；main / origin/main 仍为
`2323124f76ce34522c78016f2540dccc5e9ed983`。无未知改动或 material Git conflict。

历史 attempt 3 的具体失败仍是首个 `Key()` 之前 foreground HWND 不匹配；不是实际 Pinyin、
composition、Enter guard 或 production focus bug。确认的 harness 缺口是一次激活请求后未建立
有界 foreground/focus precondition；历史没有保存 activation 返回值/当时 foreground owner，
无法断言 Windows 拒绝激活的具体原因。[Win32 activation 约束](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setforegroundwindow)
也不保证每次请求都成为前台。

修复提交 `e789b38af0f233f186851ff1e6b41cdd8fc3994f`：仅 acceptance `Program.cs`。
先确认 HWND 存在且属于当前 acceptance process，在必要时显示/恢复窗口，调用既有
WPF ReturnFocus / SetForegroundWindow，按条件有界等待。只有普通激活失败才允许复用既有
Product/MainWorkspace acceptance 的 AttachThreadInput fallback，同步块内使用并在 finally detach；
没有 Topmost、全局 hook、生产 foreground-stealing 或无限重试。
验证 top-level foreground、WebView native child focus 和 document.hasFocus/active search input；
切换 IME 后再次建立目标，每一个 physical key（含 Enter/Space）发送前严格核对 foreground。
本次正常激活通过，**fallback 未执行**；不声称 AttachThreadInput 分支已经实测。

生产代码、packaging inputs、schema、Backup、auth、权限与生命周期均未修改。
新增 production/普通 automated tests **0**，无新依赖，无专门 foreground/IME suite。

### 本轮实际 Commands / One Integrated Flow / Privacy

实际执行 `python scripts/knowledge-search-smoke.py` **一次**；内部仅编译改动的 acceptance
project：`dotnet build desktop/acceptance/PersonalAiWorkspace.KnowledgeSearchAcceptance/PersonalAiWorkspace.KnowledgeSearchAcceptance.csproj -c Release -p:FrontendSkipBuild=true`。
compile PASS；同一次 production WPF/WebView2/React/Runtime integrated flow **PASS**。

| 本轮 gate / evidence | 实际结果 |
| --- | --- |
| Foreground + native/DOM target | PASS；有界准备后逐 key 验证，没有向其他 foreground 发送键 |
| Real Pinyin / composition Enter | PASS；physical yusuan 产生真实 composition，composition Enter 未提交；再次选词预算，Enter 搜索 |
| Chinese / Latin search / deterministic ranking | PASS；expected hits、title/heading/body 排名、稳定逻辑顺序 |
| Exact preview / accessibility / 125% | PASS；命中 exact offset 打开，真实键盘提交，125% 无横向 overflow |
| ACTIVE archive / restore | PASS；archive 消失，restore 返回 |
| Current READY / failed revision | PASS；revision 2 替换旧检索内容；invalid UTF-8 FAILED 保留 current READY 2 |
| Rebuild / missing / corrupt index | PASS；explicit rebuild 返回 READY；两种派生文件故障重启后检索恢复 |
| Backup export / new-empty restore / rebuild parity | PASS；target 不含 index；truth/revisions/source hashes 相同；重建后完整 hit DTO（含 offsets/lines/snippet）相同 |
| Browser denial | PASS；Browser credential search/status/rebuild 均 403 |
| No Ollama search dependency | PASS；集成 Runtime 全程配置不可用 Ollama endpoint，未执行 inference |
| Query URL/storage privacy | PASS；Latin fresh canary 和实际 Pinyin query 的 URL/localStorage/sessionStorage 检查；IndexedDB/Cache namespaces 为空 |
| Bridge metadata / literal snippet | PASS；无 digest/fingerprint/token/BM25/path/source bytes；无 HTML execution |
| Complete fresh-canary privacy | PASS，0 matches；4 Runtime logs、375 UDF files、54 owning source/build/package/evidence files、350 Runtime archive entries |
| Cleanup | PASS；test-owned Runtime stopped，verified temporary directory removed |

Stale fail-closed/query-mid-mutation 与 wrong-version recovery 的已有 focused evidence 继承，未再造
一次相同 race 或版本故障；Runtime post-query fingerprint、locator/preview 的生产语义没有变化。
Backup 的本轮 parity 是完整 hits/locators/snippet 与 authoritative truth/source parity；恢复后未另跑
一次相同 preview UI 点击。safe evidence：ignored `.verification/k2-knowledge-search-evidence.json`。
其中 sequential check labels（含重复等待/操作）不是独立 tests、gates 或 executions。
历史共四次 harness attempts：首次未启动 WPF，随后两次 WPF 失败，本次唯一成功 integrated flow。

### Minimal Actual Launcher / Fixed Package

随后执行 `python .verification/k2-launcher-check.py` **一次**，复用既有 K1 实际启动模式，
去掉重复 CheckOnly、full hash/package matrix 和广域 privacy scan。
实际调用现有 candidate 的 `release/start-release.ps1 -StateDirectory <private-temp>/state -DataDirectory <private-temp>/data`
（不是 CheckOnly），launcher exit 0 / ready marker、真实 Desktop main window、packaged Runtime readiness
与新空 data directory 的 K2 search status READY 均 **PASS**。
Runtime lexical/controller classes、frontend search/rebuild/input assets 已确认存在。
package manifest 仍指向 `3a5efc1d8e1fe2060c7b7a648b4339b21bb1e790`，sourceDirty=false。
没有 production build/publish，没有请求 Ollama inference；原已运行的 Ollama 保留。
Parent PID + exact executable/explicit fixture paths 确认 ownership，仅停止本次 launcher 的
Desktop/Runtime；新 token 未进入 launcher Runtime/Desktop logs，temporary directory removed。
safe evidence：ignored `.verification/k2-launcher-evidence.json`；辅助脚本/fixtures 未 commit。

### Inherited Regression / Current Docs / Final Git / Authority

Java 134、Desktop 273、Frontend 119、production build/runtime package/self-contained Release publish
继续继承固定 implementation candidate 的既有 PASS；本轮明确**未重跑**，无 production 变化。
已通过的 query-race regression 也未重跑；无历史 Browser/IME/stress/M5 package matrix、真实 inference。
本轮只有一次 acceptance compile、一次 integrated Windows execution（包含 real Pinyin/privacy）
和一次 minimal actual launcher sanity，不把覆盖点当成执行次数。

README、STATUS、current architecture 同步 LOCAL ACCEPTANCE PASS / CLOSING CANDIDATE — GO；
V1 Roadmap 仅更新 K2 objective status，未扩展。ADR-001..012 与历史 K1 report 未修改。
当前仍有既有明确限制：account-private plaintext、词法 AND/CJK bigram 非 semantic/fuzzy search、
未知 ownership 的 crash orphan staging 保留；无更广的 Windows/IME/environment 兼容性认证。

最终停在 clean `k2-deterministic-lexical-retrieval`，final docs HEAD 见交付 Git 输出；
implementation/package candidate 与 final docs HEAD 分开。没有 rewrite/merge main/push/tag/release。
**K2 — IMPLEMENTED / LOCAL ACCEPTANCE PASS；K2 CLOSING CANDIDATE — GO。**
K2 尚未 CLOSED；不启动 K3。下一步只交 Architecture Guard 独立代码级 Closing Review。

## Architecture Guard Independent Closing Review

日期：2026-10-06（Asia/Shanghai）。本节记录用户提供的 Architecture Guard 独立 source-level
Closing Review 正式决策，不代表本轮重新执行测试或验收。

**K2 CLOSING REVIEW — APPROVED — GO**。
**K2 — CLOSED — GO**。

Review 覆盖实际远端 K1 baseline `2323124f76ce34522c78016f2540dccc5e9ed983`
→ implementation candidate `3a5efc1d8e1fe2060c7b7a648b4339b21bb1e790`
→ 已发布 Closing Candidate `7d17a1bbb6b484ec8007bc411c989f651eec407e` 的完整历史。
implementation candidate 保持不变；历史失败 Attempts 1–3 和成功 Attempt 4 本地验收证据保留。
正式 Review 是独立于本地验收的最终 closing decision。

Independent source-level review verified:

- K1 `knowledge.db` schema v1 remained authoritative and unchanged；`lexical.db` is separate derived/disposable/rebuildable state。
- ADR-012 analyzer/chunker/FTS5/BM25 semantics match implementation；raw user query never becomes FTS MATCH syntax。
- Authoritative fingerprint freshness is fail-closed before/after retrieval；rebuild scheduling is bounded/coalesced；staging publication is validated and atomic。
- ACTIVE/current READY corpus semantics are preserved；failed revisions do not replace current READY。
- Browser remains Translate-only；Knowledge routes are denied before controller body handling。
- Bridge/session authority and response metadata remain bounded；query stays ephemeral in React state。
- No K3/RAG/model orchestration was introduced；Finance Freeze remains intact。

Blocking findings: **0**。Required production fixes: **0**。
Required new tests: **0**。Required reruns: **0**。
Tests/builds executed in this closing publication: **NONE**。

K3 — NOT STARTED。Finance Integration — BLOCKED，pending authoritative Finance Reality Sync。
下一步：T0 — Aggressive Test Suite Consolidation / Slimming → detailed V1 Roadmap expansion → K3。
T0 是 engineering cleanup gate，不是新的 top-level product milestone；本轮不启动 T0。
