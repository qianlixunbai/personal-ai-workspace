# M2B-2B-R1 CLOSING REPORT

Date: 2026-10-03 (Asia/Shanghai). Formal scope: **M2B-2B-R1 — Real Chrome GET Security Compatibility**.

## 1. Result

**CLOSED — GO — Runtime security compatibility patch only.**
真实 Chrome 154 使用未修改的 Extension candidate 完成 exchange → readiness → Batch POST → owned task polling → structured result。
Native regression、安全拒绝矩阵、cross-owner isolation 与 privacy/secret audits 均 PASS。
**M2B-2B 仍 PARTIAL / AWAITING REAL CHROME ACCEPTANCE，M2 未关闭。** 本轮不吞并完整 Extension Closing。

## 2. Git

Repository: `qianlixunbai/personal-ai-workspace`。
Branch: `m2b2b-r1-chrome-get-security`，从指定 clean baseline 创建。
Local commit subject: `fix: support authenticated Chrome originless GET`。交付 SHA 见最终 Git 输出，避免在本 commit 文档递归写入其自身 hash。
不 merge main、push、force push 或修改 Extension；main/origin-main 留在 baseline。
Extension `qianlixunbai/local-ai-assistant` branch `m2b2b-runtime-migration`，HEAD `ddfa4a02cace8480bdef78862a907e48a65c6b7c`，开始与验收结束工作树 clean。

## 3. Baseline

Reality check: git status、branch、HEAD、fetch origin、origin/main、log -12 全部执行。
开始时 `main == origin/main == 25dc1dfc9a103b030267f18d93059316f0ce008d`，工作树 clean。
已读取 README/STATUS/current architecture/ADR-003、M2A/M2B-1/M2B-2A closing reports、实际 security/controller/TranslateService/TaskManager 及 browser tests。
Extension migration report、runtime-client/storage/router/manifest 与现有 dev-only Chrome harness 只读核对。

## 4. Confirmed Chrome Behavior

Chrome/154.0.8037.59，本轮新的 isolated headless real Chrome profile，加载原 candidate 的 unpacked extension。
实际 Origin：`chrome-extension://ondbfogghjoeaibikagokmddmhcbijgg`。
以下 headers 由 Chrome 自然生成，生产 Extension 没有伪造或覆盖它们：

| Request | Origin | Site / Mode / Dest | Authorization | Result / readable |
| --- | --- | --- | --- | --- |
| POST pairing exchange | exact extension Origin | none / cors / empty | absent | 200，issued/stored credential |
| GET Translate readiness | ABSENT | none / cors / empty | Browser bearer present | 200，Extension 读取 available=true |
| POST Batch Translate | exact extension Origin | none / cors / empty | Browser bearer present | 202，合法 task + Location |
| GET owned task | ABSENT | none / cors / empty | Browser bearer present | 200，SUCCEEDED structured 2-item result 可读 |

Authorization 只投影成 presence boolean，不保存值。Actual GET response 没有 Access-Control-Allow-Origin。
本轮未观察到上述请求的 OPTIONS，未人为制造 preflight。

## 5. Previous Contract Failure

之前 `Origin == null` 直接进入 native token comparison。真实独立 br1 bearer 无法等于 native token，readiness 因此 401。
Extension 已成功 pairing/storage/proof cleanup；这个失败不是 credential 错误、revocation 或 CORS fallback。
显式 mode=cors 不会制造 Chrome 本来没有发送的 GET Origin；Runtime 必须根据真实 credential/request type admission。

## 6. New Authentication Classification

Origin-absent 的 `Authorization: Bearer br1...` 先选择 BrowserClients.authenticateCredential，再进行 request policy。
Prefix 不授予信任：严格 credential syntax、registered clientId lookup、SHA-256 stored verifier、MessageDigest.isEqual constant-time compare、revocation state 均必须通过。
未识别或畸形 credential 不匹配任何 native token，拒绝。Native 原格式 token 的路径保持。
Origin-present authenticate(authorization, origin) 复用 credential verifier，并额外严格校验 origin。
没有 security package 大重构或新增 auth framework。

## 7. Origin-present Policy

Browser Origin 必须符合 chrome-extension://[a-p]{32}，并 exact match 已认证 client.origin。
none/cors/empty、explicit browser route allowlist、Translate capability 继续要求。
wrong Origin、其他已配对 Origin、unknown extension、普通 https、localhost、null/wildcard、缺失/无效 credential 均拒绝。
Native token + Browser Origin 不能 bypass。Exchange 仍只接收 approved Origin + one-time proof，并拒绝 Authorization。

## 8. Origin-less GET Policy

只允许 GET + 真正有效且未 revoke 的 Browser credential + 完整精确 metadata + 两个 GET allowlist routes + Translate authorization。
不存在的 Origin 无法验证，不从 registry 推导/合成 request Origin。
Authenticated registered Browser principal 进入既有 SecurityContext/ClientIdentity.current()，绝不替换为 native-local。

## 9. Fetch Metadata

必须同时为 Sec-Fetch-Site=none、Sec-Fetch-Mode=cors、Sec-Fetch-Dest=empty。
任意缺失、cross-site、same-site、same-origin、no-cors、navigate、document/script dest 都 401。
Metadata 是浏览器边界附加约束，不能单独认证 bearer 或代替 pairing。

## 10. Route Allowlist

Originless Browser v1 仅 GET `/api/v1/capabilities/translate/readiness` 与 GET `/api/v1/tasks/{uuid}`。
Task path 严格要求 UUID 的 8-4-4-4-12 hex segments；尾部额外 path/畸形 UUID 不允许。
其他未显式登记 GET 包括 Ask/Summarize/admin/provider readiness/future route 一律 403 POLICY_DENIED。
没有根据 GET method 自动允许未来 route。现有 public actuator health 保持原公开健康检查契约。

## 11. Capability Authorization

Filter Browser paths 显式要求 authenticated identity.allowedCapabilities 包含 translate；registry v1 仍只注册 `{translate}`。
Translate readiness/submission 的既有 TextTaskSubmission capability checks 保持。
Ask/Summarize/管理 APIs 在 route gate 即拒绝，browser bearer 从无 native privilege。

## 12. Task Ownership

TaskController 使用 ClientIdentity.current().clientId()；TaskManager admission/get/cancel 的 ownerClientId 规则不变。
Originless Browser A 可读取自身任务；A→B、A→native、native→A 均 404 TASK_NOT_FOUND，与不存在任务完全相同 response body。
除 synthetic regression，本轮真实 Chrome task 也由第二 synthetic browser/native client 发起 GET，均 404 TASK_NOT_FOUND。
不能靠 taskId 获取 native 或其他 browser 结果/存在性。

## 13. Mutation Policy

Browser bearer + Origin absent 的 POST Translate/Ask/Summarize/pair create/exchange、DELETE task/client 均拒绝。
HEAD/OPTIONS 也不进入 Originless compatibility path。
Exact-Origin POST Translate 和 DELETE owned task 继续 PASS；exchange 继续原 proof/Origin rules，无 proof bypass。
没有新的伪 Origin header 或修改 Origin 的 webRequest 要求。

## 14. Native Compatibility

Origin absent + valid native token 保持 Translate/Summarize/Ask、owned task GET/DELETE、pair create/list/revoke。
RuntimeApiTest 已覆盖管理与 ownership；真实 native local smoke 三能力全部 SUCCEEDED。
Desktop restore/build/test 67 PASS，0 warnings/errors；Desktop/WPF/token/WinCred/selection/tray/UX 未修改。
本轮未重新进行 WPF GUI 操作，不把历史 GUI PASS 作为新证据。

## 15. CORS Behavior

Originless GET response 不设置 Access-Control-Allow-Origin，没有 `*` 或 allow-credentials，没有 Origin synthesis。
让 Chrome manifest host_permission 的 privileged fetch 决定读取；真实 candidate 已读取 readiness 与 task result。
Origin-present 响应继续 echo exact authenticated Origin；existing exact-Origin preflight method/header/session/registration policy 保持。
Synthetic smoke 每次检查 CORS 不得出现 wildcard/不匹配 Origin；真实 network projection 同时检查 GET allowOrigin=ABSENT。

## 16. Threat Model

Browser credential 是高熵 bearer secret，才是最终认证材料。此前 explicit user-approved pairing 绑定 registered client identity。
有 Origin 时执行 additional exact-origin binding；真实 Chrome Originless GET 无法验证不存在的 Origin。
此时安全组合为 approved pairing、registered identity、bearer credential、Fetch Metadata、exact GET allowlist、capability authorization、per-client ownership。
普通网页仍拒绝，content script 不可读取 credential；禁止把 bearer 传到 webpage/DOM/message bridge/log。
持有 bearer 的同 OS 用户恶意 native process 能伪造 HTTP metadata，仍在已声明的 Browser-origin isolation 保证之外。
没有新增同 OS 用户隔离、OS vault、GPU cancel 或 credential-compromise 防护保证。

## 17. ADR-003 Amendment

ADR-003 新增 `Amendment — M2B-2B-R1 current behavior (2026-10-03)`，保留 M2A 历史上下文。
记录 Chrome evidence、Origin-present/pairing binding、Originless GET gates、mutation boundary、CORS/no synthesis 和 bearer threat model。
未新增 ADR-004。README/STATUS/current architecture 同步当前事实，同时保留历史阶段验证记录。

## 18. Tests

`./mvnw.cmd clean verify` PASS：**30 tests，0 failures/errors/skipped**，2026-10-03 00:45 +08:00。
Desktop restore、build --no-restore、test --no-build --no-restore PASS：**67 tests，0 failed/skipped，0 warnings/errors**。
Originless regression 覆盖 accepted readiness/owned task、cross-owner/native 404、CORS no-store/no allow-origin、missing/malformed/forged/revoked credential、
missing/wrong metadata、forbidden/unknown GET、POST/DELETE/HEAD/OPTIONS、Origin-present mismatches、native token Origin bypass 拒绝。
BrowserClients 单独验证 credential-only identity、Origin validation 分离、错误 secret/未知 clientId/revoke；原 restart/registry/proof/rate/private ACL tests 保持。
已有 batch、Single、Ask/Summarize、queue/cancel/timeout/provider/privacy tests 全部通过。

## 19. Synthetic Security Smoke

`scripts/real-local-smoke.ps1` REAL PASS（00:47:20 +08:00），native 三能力真实现有 Ollama 推理。
`scripts/browser-security-smoke.ps1` PASS（00:47:41），`-Batch` PASS（00:51:54）。
两者新增 Originless readiness/owned polling/cross-owner/mutation/admin/revoke checks；仍保留 exact-Origin、native、restart 和 revoked-after-restart checks。
Batch：3 records / 1 POST / 1 task / 1 real Ollama inference / 3 valid mappings；独立 cancellation task CANCELLED。
Real-Chrome runner 在启动 Chrome 前另完成 synthetic Originless security chain，明确只证明 server contract，不等于 Chrome PASS。

## 20. Real Chrome Readiness

**PASS**。实际 Chrome/154.0.8037.59、同一 unchanged candidate、实际 action popup pairing 与 production worker fetch。
成功 exchange 后 readiness 自然 Origin ABSENT，none/cors/empty，Browser bearer present，HTTP 200；popup 读取 available=true，Translate 可用。
Credential 保存/Origin/clientId 绑定、proof inputs 清除、content-script storage denied 同时确认。
Native authority 使用本轮自己的 private bootstrap，未读取既有用户 token，也未传给 Browser。

## 21. Real Chrome Task Polling

**PASS**。Readiness PASS 后，通过实际 popup runtime message 调用 candidate 生产 TRANSLATE_BATCH handler，提交一批两个 records。
一次 exact-Origin POST 202，生产 RuntimeClient 使用返回 taskId/Location 执行真实 GET polling。
GET 自然 Origin ABSENT + Browser bearer + none/cors/empty，200 且 response 可读。实际 GET 观察到 SUCCEEDED。
本轮没有刻意延长模型任务以观察每个 QUEUED/RUNNING 中间状态；原 production polling loop 与自动状态回归保持。
Task：`4ce73424-4b8c-414d-8b57-42cc6f075d19`，完成证据 2026-10-03 **00:51:29 +08:00**。

## 22. Real Chrome Translate Result

**PASS**。真实 GET body 在 dev debugger 内存中确认 result 为原生 structured items array，2 valid Chinese mappings。
生产 RuntimeClient 自己读取 JSON、校验 task/profile/Location/prompt identity 并返回 ok + 两项映射；没有测试替换 fetch/Origin 或跳过 parser。
安全 metadata：`translate.fast / m0-1 / LOCAL / translate-batch-v1`。
正文/translation/raw response 没有写入 evidence；只存 structuredResult boolean/count/status/metadata。
仅证明 security transport chain；没有调用全页/Dynamic/Selection/cache Closing 或声称 DOM 验收。

## 23. Privacy / Secret Audit

PASS。Native/browser/proof 的实际临时值只在内存/stdin 比较，不写入命令行、stdout、evidence 或 secret manifest。
Chrome CDP raw packets 不落盘，只投影 request method/path、Origin、metadata、credential presence、HTTP/task status、result count。
扫描 source、build binaries、嵌套 jars/zips、logs、captured test output 与 metadata evidence；检查 actual token/browser/proof 及 input/result markers。
Chrome runner audit：109 source files / 1116 files / 33585 byte+archive checks / 127 archives，6 native credentials + 7 current ephemeral secrets，0 matches、0 tracked build artifacts、ignore PASS。
这个数量是验收时快照；最终文档后再执行 audit，最终数量以 ignored `.verification/m2b2b-r1-final-audit-evidence.json` 为准。
所有 test client 已 native revoke，自有 Runtime/relay/Chrome/site 停止；仅清理新建且路径核验在 `.verification` 内的本轮 Chrome profile。
Private native bootstrap 为预期认证存储，ignored/owner-only；Chrome profile 在审计前清理，避免将预期 browser secret 存储误报为交付泄漏。
范围是本仓库及验证产物，不宣称全系统/剪贴板/进程内存审计。`git diff --check` PASS。

## 24. Known Limitations

真实 Chrome 为 headless + worker debugger attached，不能由此推断 MV3 lifetime、用户 action interaction、WPF GUI 或全部网页兼容。
Readiness 只是 metadata availability，不能保证所有翻译质量/输入组合；本轮两个 records 的实际结构化 inference 另有 PASS。
现有 shared native domain、same OS user、short task retention、registry platform/durability、bearer secret compromise、best-effort cancellation 边界保持。
没有安装 Chrome/SDK/model、修改 Provider/Ollama、扩大 budgets 或引入新 API/header。

## 25. Deferred Extension Acceptance

MDN、Dynamic、Selection、Cache、Runtime offline、Provider offline、Revoke/re-pair UX、MV3 30–45 sec 均仍属于 Extension M2B-2B Closing Acceptance。
本轮没有执行整个既有 Chrome acceptance harness；新增仅针对安全链路的 opt-in runner。
M2B-2B 继续 PARTIAL，不能宣称 Browser Convergence/M2 CLOSED。
Memory/Finance/RAG/Cloud/Agent/tool calling 未开始。

## 26. Files Changed

11 files，产品 Java 仅两个 security files：

- `src/main/java/io/github/qianlixunbai/workspace/security/LocalClientFilter.java`
- `src/main/java/io/github/qianlixunbai/workspace/security/BrowserClients.java`
- `src/test/java/io/github/qianlixunbai/workspace/api/RuntimeApiTest.java`
- `src/test/java/io/github/qianlixunbai/workspace/security/BrowserClientsTest.java`
- `scripts/browser-security-smoke.ps1`
- `scripts/chrome-get-security-smoke.js`
- `README.md`
- `docs/STATUS.md`
- `docs/architecture/current-architecture.md`
- `docs/ADR/ADR-003-browser-client-security.md`
- `docs/milestones/M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md`

## 27. Architecture Compliance

唯一产品变化是 Browser authentication/admission compatibility。ClientIdentity/TaskController/TaskManager ownership 不变。
Translate Single/Batch、Summarize/Ask、task result/parser/prompt/profile/provider/queue/timeout/concurrency/Ollama/Desktop 均未修改。
没有第二 task engine/provider、unauthenticated API、wildcard CORS、伪 Origin/master-token Browser access、direct Ollama fallback 或新 security framework。
验证脚本在本 Runtime repo，仅只读加载 unchanged Extension；其 Java/Chrome/native authority/site/relay 不进入产品链路。

## 28. M2B-2B Resume Readiness

**READY TO RESUME**。已确认的 Runtime Chrome GET security blocker 解除；真实 candidate 可读取 authenticated readiness 和 owned structured Translate result。
回到 Extension candidate 的 M2B-2B Closing Acceptance，继续剩余 deferred gates；保持 Extension 原 candidate 与 Runtime patch 的独立 Git 范围。
Runtime patch 可本地提交；本轮不 merge/push、不关闭完整 M2B-2B/M2、不开始其他阶段。
