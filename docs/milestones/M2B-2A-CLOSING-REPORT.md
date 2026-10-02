# M2B-2A CLOSING REPORT

Date: 2026-10-02 (Asia/Shanghai)

## 1. Result

**CLOSED — GO / Runtime Batch Translation Contract Ready.**
Shared Runtime 的 Browser batch request/result、一次 task/inference、bounded mapping、安全与 Native compatibility 已验证。
真实证据来自 actual Runtime + 现有 Ollama + synthetic extension HTTP clients；不代表 Chrome PASS、Extension migrated 或 Browser convergence complete。
M2B-2B 未开始。当前状态来源为 [STATUS](../STATUS.md)。

## 2. Git

Repository：`qianlixunbai/personal-ai-workspace`；本地 `D:\IDEA\Daima\personal-ai-workspace`。
Branch：`m2b2a-runtime-batch-translate`，由已 fetch、一致且干净的 main 创建。
本地 implementation commit：`feat: add batch translate runtime contract`，最终 SHA 以 `git rev-parse HEAD` 为准，避免文档自身 SHA 递归。
未 merge main、push、force push、reset 或覆盖用户工作。main / origin/main 保持 `d606472`。
仅修正 README / STATUS / current architecture 中 M2B-1 已发布事实，历史 Closing Reports 与 ADR 原文未改。
Credentials、registry、过程日志、evidence、target、Desktop bin/obj/TestResults 与 Python bytecode 均 ignored。

## 3. Baseline

执行 git status / branch / HEAD / remote / fetch / origin-main / log -12 reality check。
开始时 `main == origin/main == d60647273a8dcf63b71e985bd8ba4e63ad5d64a9`，工作树 clean。
阅读 README、STATUS、current architecture、ADR-001/002/003、M1.5 / M2A / M2B-1 Closing Reports，复核真实 Runtime/security/tests。

只读核对 `D:\IDEA\Daima\local-ai-assistant`：main、HEAD 与 `git ls-remote origin refs/heads/main` 均为
`75bede161e7e81d2e7c0fa8e62ac2d05a7248c83`；工作树 clean，manifest v0.4.1，hotfix report v0.4.1 GO / RELEASED。
读取 content.js / background.js / config.js / manifest.json；没有 fetch/write/commit 该仓库。
确认 DOM records → viewport-first batching → TRANSLATE_BATCH → 一次 Ollama chat → id mapping → DOM 回填。
首批 1000、normal 2800 chars；missing/duplicate/unexpected 的 partial 语义、maxRetries=1、settings-bound cache 与 B11/B12 DEFERRED 均核实。

## 4. Problem Being Solved

原 Runtime 只接收单文本且 TaskView result 为 String，不能忠实承接 Browser 的 record batch。
现有 Translate capability 新增 Batch 模式，一个 batch 仅创建一个 task/owner，执行一次 provider generation，返回按 id 映射的结构化结果。
没有把 batch 展开成 N 个 single tasks/inferences，也没有 Browser 专用 AI backend。

## 5. Batch API Contract

继续使用 POST `/api/v1/translate/tasks`。Single 与 Batch 的 `text` / `items` exactly one；显式 null 输入拒绝。
sourceLanguage 可省略，targetLanguage 必须是合法语言标签；profile 默认/唯一允许 `translate.fast`。

```json
{
  "items": [
    { "id": 1, "text": "Hello" },
    { "id": 2, "text": "Settings" },
    { "id": 3, "text": "Load more" }
  ],
  "sourceLanguage": "en",
  "targetLanguage": "zh-CN",
  "profile": "translate.fast"
}
```

返回 202、一个 UUID taskId 和相对 Location `/api/v1/tasks/{id}`。
GET 返回原 task envelope，Batch result 为 `{"items":[{"id":1,"translation":"你好"}]}`，不是 JSON-inside-string。
API 拒绝未知字段、客户端 model/systemPrompt/translationPrompt/instruction 与 generation parameters；item 也采用 id/text 字段 allowlist。
没有新增 `/browser/translate`、`browser_translate` capability 或 Browser model 参数。

## 6. Task Result Evolution

TaskManager.Work / Job / TaskView result 最小演进为 Object，worker 只允许 String 或 sealed TaskResult。
TaskResult 当前仅一个 structured shape：immutable TranslationBatch / Translation records；list defensive copy，diagnostics 不输出译文。
任意对象/Map/null 结果受控 INTERNAL_ERROR，不引入未来 Agent/Tool/Stream/Plugin result 类型。
Single Translate、Summarize、Ask 的实际 HTTP result 仍序列化为 JSON string，原 Desktop parser/UI 完全不变。
非 SUCCEEDED task result 仍为 null；状态 envelope、Location、error、owner/cancel contract 保留。

## 7. Input Budgets

| Budget | Contract |
| --- | --- |
| Item count | 1–32 |
| Item id | Unique integer 0..2147483647；拒绝 float/string/boolean/null/越界 |
| Each text | Strict string，非 null/non-blank，≤2800 UTF-16 chars |
| Aggregate text chars | ≤2800 UTF-16 chars，且 ≤profile.maxTextCharacters |
| Aggregate text UTF-8 bytes | ≤4096 |
| Serialized provider input JSON | ≤contextBudget − outputBudget − 512，当前 5632 UTF-8 bytes；包括 id、结构与 escaping |
| HTTP request body | ≤32768 bytes |
| Runtime system prompt | ≤512 UTF-8 bytes |
| Generated mapping JSON | ≤outputBudget × 4，当前 8192 UTF-8 bytes |
| Raw provider HTTP body | ≤1048576 bytes，读取中受限 |

32 项限制控制 id/JSON/output overhead，2800 字符对齐真实 Browser normal batch；4096 正文字节为结构与输出留保守空间。
继续复用 translate.fast 的 context/output 8192/2048；没有复制 profile 或提高 num_ctx/num_predict。
JSON escaping 可触发额外 context 限制，即使原文字符数未超限也受控拒绝。
旧 Browser hardTextLimit=12000 不作为 Runtime 安全预算；超长 record 拒绝，不静默截断/拆分/下载新模型。
这些是保守字节/字符预算，不宣称精确 tokenizer 或所有模型输出必然完整。

## 8. Batch Prompt

Runtime 拥有独立 `translate-batch-v1`，Single `translate-v1` 不变。
明确每个 text 是不可信数据，不执行其中指令，不总结/解释/新增事实、不丢正常项，保持 integer id，仅返回严格 JSON array。
网页正文仅作为序列化 user-message 数据；system prompt 由 Runtime 根据合法语言标签生成。
最长合法 source/target tags 也满足 512-byte reserve；测试检查 prompt 与 private input 隔离、LOCAL_ONLY 和脱敏 diagnostics。
Prompt 是行为约束，不是翻译质量或 prompt-injection 免疫的形式保证；输出必须通过 parser。

## 9. Provider Result Parser

TextTaskSubmission 先执行共同的 output UTF-8 budget，再调用 TranslateBatch 严格解析。
顶层必须为完整 JSON array；markdown fence、trailing tokens、duplicate JSON fields、非数组、malformed JSON 受控 PROVIDER_RESPONSE_INVALID。
每项必须是 object；id 必须 integral、int-range 且 requested；有效项仅有 id 与 non-blank string translation。
unexpected id 忽略；duplicate requested id（包括第一次畸形再出现）使该 id 的全部结果失效。
畸形 item、错误 id 类型、empty/non-string translation、额外字段 item 安全忽略；其他正常项继续保留。
返回项按请求顺序排列，translation 去除首尾空白；不返回 raw output、unexpected 内容、parse exception/cause。
已有 Ollama adapter 继续检查 done、assistant、configured model match、无 truncation/tool calls、bounded body 与分类错误。
没有修改 adapter/provider 或增加 retry。

## 10. Partial Semantics

合法数组的有效 subset 可 Task SUCCEEDED，result.items 只包含成功项；missing 不编造 translation。
合法空数组或所有项无效也可 SUCCEEDED + empty items，是明确 partial 结果。
完全 malformed 顶层/超预算/provider transport failure 为 FAILED 或既有 timeout/cancel，而不是 partial success。
Runtime 不自动逐条 retry。M2B-2B 由 Browser 回填有效项、展示 partial、处理用户显式 retry，避免 Browser retry × Runtime retry。

## 11. Task Lifecycle

Batch 继续使用同一 TaskManager、queue、worker 和 policy：concurrency 1、queue 4、maxRetained 64、queue 30s、execution 150s、retention 约 2m。
运行前 worker 再验 LOCAL_ONLY，最终 Ollama egress 仍重验；一个 Work 中仅一个 provider.execute，没有 records execution loop。
QUEUED / RUNNING、SUCCEEDED、CANCELLED、TIMED_OUT、queue full、late-result rejection、immutable terminal 与 retention 全部回归。
DELETE 取消整个 task/provider future；没有 item-level cancellation。真实 smoke 得到 CANCELLED。
受控 slow HTTP integration 额外证明 RUNNING cancellation 与迟到响应无法覆盖终态；TaskManager tests 同时验证 String/structured results。
Cancel 不保证 GPU 立即停止；已成功 task 的 DELETE 保持 SUCCEEDED。

## 12. Browser Authorization

Browser allowedCapabilities 仍固定 `{translate}`；Batch/Single 都通过共享 TextTaskSubmission capability check。
只增加精确 GET Translate readiness route/preflight；没有开放 native provider readiness、Ask、Summarize 或 security administration。
exact registered Origin、独立 credential、loopback、none/cors/empty Fetch Metadata、无 wildcard CORS 全部保持。
wrong/web/unknown/missing Origin、wrong/missing credential 和不合法 preflight 受控拒绝。
没有 translate capability 的 in-process principal 无法提交 batch 或访问 readiness；测试也证明 cloud profile admission/readiness 均拒绝。

## 13. Ownership

一个 batch 的全部 records 只属于 admission clientId。A GET/DELETE A；B/native GET/DELETE A 与不存在任务完全相同 404 TASK_NOT_FOUND。
Native token 持有人继续共享 native-local owner，native/browser 双向隔离，Windows ownership semantics 保持 M2A。
Revoke 阻止后续请求，不取消已接受 batch；slow integration 证明 revoke 后 accepted task 仍成功，但 HTTP credential 已不能查询。
Credential/revoke 跨 restart，task/session 不持久化；没有 owner bypass、task listing 或第二套 registry。

## 14. Readiness Contract

GET `/api/v1/capabilities/translate/readiness` 需要认证且有 translate capability。
仅 `{"available":true}` 或 `{"available":false,"error":{"code":"PROVIDER_UNAVAILABLE"}}`。
复用 ProfileResolver/ProviderPolicy/Provider.readiness；available 同时要求 provider/model usable 且无 error。
provider 离线与模型缺失均折叠到不可用，不返回 provider/model name/list/URL/GPU/system prompt/raw reason；无 task/inference。
原 native `/api/v1/providers/readiness` 与 Windows 使用方式保持。
M2B-2B 将 CHECK_CONNECTION 替换为此 capability-level readiness，并分别呈现 Runtime unreachable 与 Translate unavailable；不能重新直连 Ollama。
本轮没有修改 popup 或实现该接入。

## 15. Native Compatibility

Desktop 全部 source/tests 未修改；token、WinCred target、WPF 三 Action、Pairing/Revoke UI、hotkey/selection/tray 路径保持。
原 Single Translate request 与 string response 通过 HTTP regression，Summarize/Ask 同样 string；native auth/ownership 回归 PASS。
Desktop restore/build/test PASS，67 tests 包括既有 Pairing UI regression；没有重新导入 credential 或改 UI。
actual Runtime/Ollama native Translate / Summarize / Ask smoke 全部 SUCCEEDED。
本轮未重做人工 WPF GUI acceptance；历史 M1/M1.5/M2B-1 GUI PASS 保留为历史证据，不声称新增 Chrome/Windows 全场景验收。

## 16. Tests

| Command | Final result |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS，29 tests，0 failures/errors/skipped；22:56 +08:00 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS，67 tests，0 failed/skipped |
| `.\scripts\real-local-smoke.ps1` | REAL PASS；22:51 +08:00 |
| `.\scripts\browser-security-smoke.ps1` | REAL PASS；23:00 +08:00 |
| `.\scripts\browser-security-smoke.ps1 -Batch` | REAL PASS；22:58 +08:00 |
| `git diff --check` / staged check | PASS |

RuntimeApiTest 扩展同一个现有 API test：2/32 项 accepted、char/byte 边界、JSON escaping budget、empty/duplicate/negative/invalid id、
null/empty/non-string text、count/aggregate/body limits、text+items/neither、非法 language/profile、客户端 model/prompt/generation fields。
结果测试覆盖 all、missing、unexpected、duplicate（三次与先无效再有效）、empty/non-string/malformed item、empty array、extra fields、
malformed/非数组/fences/trailing/duplicate JSON fields、8192-byte output 与 1 MiB provider body 超限，以及 raw errors 不泄漏。
API 计数证明每批只有一次 chat；structured metadata、owner GET/DELETE、cross-owner indistinguishable 404、real HTTP cancellation/revoke、
readiness available/offline/missing model、exact CORS/preflight、credential/Origin/Fetch Metadata/Translate-only 回归。
TaskManagerTest 在原生命周期 case 同时执行 String/structured output，新增 immutability/redacted diagnostics/禁止任意 Object。
TextCapabilitiesTest 增加 Batch/Readiness capability + LOCAL_ONLY admission 拒绝；已有 Provider、registry、native、三能力测试全部保留。
CapturedOutput 与后续 archive/log/evidence audit 验证 prompt、输入/输出/provider markers 与实际 secrets 不进入日志。
普通 tests 使用 fake work / loopback mock，不依赖 Ollama；没有另建 BatchTaskManager universe。

## 17. Real Local Batch Smoke

Synthetic extension Origin，经 native explicit pairing → one-time exchange → 独立 browser credential → actual Runtime → actual existing Ollama。
仅验证的 Python 标准库 loopback relay 转发到 `127.0.0.1:11434`，计数实际 `/api/chat`；无正文日志/持久化，不进入产品 Provider。
Runtime 的 smoke Ollama URL 指向该 loopback relay；模型/provider settings 仍原配置，没有 Ollama 配置修改或模型下载。

| Evidence | Result |
| --- | --- |
| Timestamp | 2026-10-02 22:58 +08:00 |
| Native Single Translate | SUCCEEDED，JSON string |
| Batch records / POST / task / actual chat calls | 3 / 1 / 1 / 1 |
| Batch taskId | c1f61b94-7aaf-434e-8f1c-2fa3cf817b82 |
| Batch status / valid mappings | SUCCEEDED / 3 |
| Safe metadata | translate.fast / m0-1 / LOCAL / translate-batch-v1 |
| Translate sanitized readiness | PASS |
| Wrong Origin / ordinary webpage | 401 / 401 |
| B/native GET+DELETE A | 404 TASK_NOT_FOUND |
| Browser Ask / Summarize | 403 / 403 |
| Separate whole-batch DELETE | CANCELLED，无 result |
| Runtime restart then Batch | SUCCEEDED，同一 credential |
| Revoke before/after restart | 后续请求 401 |
| Listener / process cleanup | 127.0.0.1，仅停止自己启动的 JVM/relay |

一批一次 inference 的计数区间不包含 native smoke、单独 cancel task 或 restart 后验证任务；这些是另外的明确测试请求。
稳定 RUNNING + late-result cancellation 用 slow loopback integration 单独验证，不把真实快速模型 DELETE 的结果当成 GPU 停止证明。
Metadata evidence 位于 ignored `.verification/browser-batch-smoke-evidence.json`；没有 input/result/credential/proof 内容。
原 Single/security smoke 23:00 完整回归并 audit PASS；native 三能力 smoke 22:51 PASS。
早期 smoke 尾部 audit 曾遇到 relay log sharing、Unicode surrogate 和测试名短词匹配；修正后完整重跑 exit 0，未将早期运行记录为 PASS。

## 18. Privacy / Secret Audit

Runtime source 无正文 logger、prompt/output logging 或 credential diagnostics；新 DTO、TaskResult、ProviderExecution toString 脱敏。
实际 native token、2 browser credentials、2 pairing proofs 只在 smoke 内存和 audit stdin 传递；未保存秘密 manifest/命令行值。
Audit 检查 UTF-8/UTF-16/escaped JSON、当前 source、build binaries、嵌套 archive、日志、captured test output 和 metadata evidence。
测试源码包含显式合成 fixtures；测试名称不作为正文日志。全 XML 仍扫描实际 secrets，stdout/stderr/failure/error 节点额外检查正文。

最终完整 Batch audit：107 source files / 1099 files / 33568 byte+archive checks / 127 archives，5 actual native credentials + 5 当前 ephemeral secrets，
0 匹配、0 tracked build artifacts，ignore PASS。Single security smoke audit：1101 files / 33570 checks，其余同上。
最终文档交付后再次执行 native/token/tracked secret/build/archive/evidence scan；无匹配，最终数量以 ignored m2b2a-final-audit-evidence.json 为准。
Auth files、logs/evidence、target、Desktop bin/obj/test outputs、Python bytecode 均 ignored。
Scope 仅本仓库及本地验证产物，不宣称全系统磁盘/剪贴板/内存审计。

## 19. Known Limitations

- 实际 Chrome Origin/Fetch Metadata/host permissions/storage/DOM/cache 未验收；synthetic HTTP 不代表 Chrome acceptance。
- 保守 context/output 预算不保证所有网页语言/记录组合均可完整生成；超预算/truncation 安全失败，长 record controlled rejection。
- Item count/id 范围比旧 Browser 的无限 record array/JS safe-integer 范围更保守，M2B-2B 需明确按 Runtime 上限切批与 oversized UX。
- 模型可能 partial/malformed；Runtime 不编造或自动 retry，Browser 显式 retry/partial UX 待实现。
- Readiness 是 bounded metadata availability，不是 GPU/model-quality/inference 成功承诺。
- Cancel 是尽力传播，无 GPU 即停保证；POST 通信失败可能已接受，无 idempotency/durable task replay。
- Native shared trust domain、同 OS 用户风险、short retention、registry 平台/断电 durability 限制沿用 M2A。
- 只验证当前 Windows 环境；无新增 POSIX/不同模型/吞吐或长文 benchmark。

## 20. Deferred Scope

不修改 local-ai-assistant；Extension pairing exchange、chrome.storage/credential protection、manifest permissions、background RuntimeClient、
DOM/Dynamic Content/Selection/Restore、Browser cache migration、popup redesign、真实 Chrome acceptance 全部 M2B-2B deferred。
B11/B12 继续 DEFERRED。Finance、Memory/conversation persistence、RAG、Agent/tools、Cloud、Streaming、React/WebView2 Workspace、installer、sync 未实现。
没有新增 Runtime retry framework、item-level cancellation、模型下载或 Ollama 服务管理。

## 21. Files Changed

22 files：

- `.gitignore`、`README.md`、`docs/STATUS.md`、`docs/architecture/current-architecture.md`、本 Closing Report。
- `scripts/browser-security-smoke.ps1`、`scripts/ollama-smoke-relay.py`、`scripts/privacy-audit.py`。
- Runtime `api/TranslateController.java`、`capability/TextTaskSubmission.java`。
- `capability/translate/TranslateRequest.java`、`TranslateService.java`、`TranslateBatch.java`、`TranslateBatchPrompt.java`、`TranslateReadiness.java`。
- `security/LocalClientFilter.java`、`task/TaskManager.java`、`TaskView.java`、`TaskResult.java`。
- Tests `api/RuntimeApiTest.java`、`capability/TextCapabilitiesTest.java`、`task/TaskManagerTest.java`。

Java paths 均在 `src/main/java/io/github/qianlixunbai/workspace/` 或对应 `src/test/java/`。
无 Desktop、provider/model/policy implementation、YAML、pom/dependency、旧 Closing Report/ADR 或旧 Browser 仓库变更。

## 22. Architecture Compliance

Translate 仍为一个 capability；共享 TaskManager/TextTaskSubmission/ProfileResolver/ProviderPolicy/ProviderRegistry/Ollama。
LOCAL_ONLY、loopback、最终 egress check、单 worker/queue、client owner、credential/origin 与 Native 兼容边界保持。
只有真实第二种 result shape 的最小 Object + sealed structured record 演进；没有 generic future framework、第二 scheduler/provider 或 Browser-only AI backend。
Readiness 仅 capability 安全投影；verification relay/audit 不进入产品执行链。没有新长期不可逆决策，未新增 ADR-004。

## 23. M2B-2B Readiness

Runtime contract 已为真实 Browser pipeline 提供 batch request、structured partial mapping、cache identity metadata、owner cancellation 与脱敏 readiness。
下一阶段应按已授权 milestone 范围实现可信 extension exchange/storage/RuntimeClient、normal batch bounds、显式 partial/retry、cache identity 与 CHECK_CONNECTION 替代，
并实际验证 Chrome headers/host permissions、credential boundaries 和用户 UX。
**本轮不开始 M2B-2B，不 merge/push。**
