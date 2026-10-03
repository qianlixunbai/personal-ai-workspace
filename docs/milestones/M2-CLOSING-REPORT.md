# M2 FINAL CROSS-REPO CLOSING REPORT

Date: 2026-10-03 (Asia/Shanghai)

## 1. Result

**M2 — Browser Convergence：CLOSED — GO**。
**Browser Translator v0.5.0 — GO / M2B-2B — CLOSED — GO**。

Windows Assistant 与 Browser Extension 已收敛到同一 authenticated Personal AI Runtime。
本轮是 docs-only Final Cross-Repo Closing & Status Sync，不增加功能、不修改实现、不重新执行历史 acceptance。
分支 `m2-final-cross-repo-sync`；提交主题 `docs: close M2 browser convergence`。
本轮只做本地提交，未 merge/push，停在 Closing Review。

## 2. Cross-Repo Baselines

| Repository | 最终发布基线（本次 docs-only closing 前） | 核验 |
| --- | --- | --- |
| qianlixunbai/personal-ai-workspace | `ad6e8995cf482517be11602c795b1d6331b68e4b` | 起始 main == HEAD == origin/main；fetch 后仍一致，工作树 clean |
| qianlixunbai/local-ai-assistant | `b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0` | 本地 main/HEAD 与 GitHub main 的只读 ls-remote 核验一致；工作树 clean；manifest 0.5.0 |

Workspace 执行 git status、branch --show-current、rev-parse HEAD、fetch origin、rev-parse origin/main、log -12 --oneline --decorate。
Browser 没有 fetch、写文件或提交；通过 GitHub 只读 connector 读取上述精确 commit 的最终报告 §41。

证据来源：

- [Browser M2B-2B Closing Report §41（固定最终 commit）](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/docs/M2B-2B-RUNTIME-MIGRATION-REPORT.md#41-final-closing--real-chrome-acceptance--2026-10-03)。
- [M2B-2B-R1 Closing Report](M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md)。
- [M2B-2A Closing Report](M2B-2A-CLOSING-REPORT.md)、[M2B-1 Closing Report](M2B-1-CLOSING-REPORT.md)、[M2A Closing Report](M2A-CLOSING-REPORT.md)。
- [M1.5 Closing Report](M1.5-CLOSING-REPORT.md) 与 [STATUS 的 M0/M1 历史 Closing / Windows acceptance](../STATUS.md)。

Browser report §1–40 保留原 PARTIAL 记录，§41 为最终验收结论。§41 的 candidate-only、当时未 merge/push、等待 Cross-Repo Sync 叙述属于历史时点；
本次 GitHub main 核验确认 closing commit 已成为上述发布基线。本次用户明确授权 M2 Final Sync。
Browser README/DEVELOPMENT_STATUS 的旧 PARTIAL 摘要不覆盖最终 §41；本轮不修改该仓库。
Workspace 历史 Closing Reports 同样保留当时 Git 与验收范围，不重写历史或 ADR。

## 3. M2 Scope

关闭 Browser Convergence：Runtime browser access foundation、Windows pairing UX、Runtime Batch contract、
真实 Chrome GET security compatibility，以及 Extension Runtime migration / 最终真实 Chrome acceptance。

Windows Native 拥有 **Translate / Summarize / Ask**；Browser 仅拥有 **Translate**，包含 Single / Batch。
Browser 没有 Ask/Summarize、native provider readiness 或 security administration 权限。
本次只更新 README、STATUS、current architecture，并新增本 Closing Report。

## 4. Milestone Sequence

| Stage | Result | 交付 |
| --- | --- | --- |
| M0 | CLOSED — GO | Shared Runtime Foundation |
| M1 | CLOSED — GO | Windows Assistant Entry |
| M1.5 | CLOSED — GO | Assistant Core Capabilities |
| M2A | CLOSED — GO | Browser Runtime Access Foundation |
| M2B-1 | CLOSED — GO | Browser Pairing UX |
| M2B-2A | CLOSED — GO | Runtime Browser Batch Translation Contract |
| M2B-2B-R1 | CLOSED — GO | Real Chrome GET Security Compatibility |
| M2B-2B | CLOSED — GO | Chrome Extension → Shared Runtime Migration / Real Chrome Acceptance |
| M2 — Browser Convergence | CLOSED — GO | Final Cross-Repo Closing & Status Sync |

R1 解除真实 Chrome authenticated GET 自然不带 Origin 而被误归 native 的 401 blocker；
后续 Browser §41 完成剩余 acceptance，没有新增 Extension/Runtime 产品代码修复。

## 5. Final Architecture

```text
Windows Assistant ─┐
                   ├─→ Personal AI Runtime
Browser Extension ─┘
                         ↓
                   Provider Policy
                         ↓
                   translate.fast /
                   summarize.fast /
                   chat.balanced
                         ↓
                       Ollama
```

三个 profile 是 Runtime 整体能力集合；Browser authorization 仍为 Translate only。
实际共享执行链为 capability services → TextTaskSubmission → bounded TaskManager → Provider Policy / Provider → loopback Ollama。
Runtime 拥有 prompt、model profile、generation config、provider parser 与 execution；两个客户端只访问 Runtime。
详见 [Current Architecture](../architecture/current-architecture.md)。

## 6. Windows Client

.NET 10 LTS / native WPF Assistant；Translate hotkey / selection 或手动 Translate / Summarize / single-turn stateless Ask。
共用 authenticated loopback RuntimeClient、task polling/cancel、纯文本 result card、tray/lifecycle 与 Windows Credential Manager native 凭据。
主动采集、有界 UIA helper、保守 Copy fallback、protected/password fail closed 与无后台选区监控边界保持。

Pair Browser 仅在用户显式批准 exact extension Origin 后创建短时 proof；Desktop 不执行 exchange、不保存 Browser credential。
Paired Browsers 由 native authority 列出安全 metadata / revoke；最终 Browser §41 已通过真实 WPF pairing/revoke/re-pair E2E。
原 Windows 三能力证据来自 STATUS / M1.5 / M2B-1 / R1；本次没有重跑 GUI、Desktop tests 或 native smoke。

## 7. Browser Client

Browser Translator v0.5.0：

```text
Chrome Extension → authenticated Shared Runtime :8765
→ Translate / Batch Translate → Shared TaskManager → translate.fast → local Provider
```

已移除 direct Ollama endpoint、model ownership、system prompt ownership、generation config ownership、provider parser、
direct provider retry、direct provider fallback。当前已不再是 Chrome → Ollama。

保留 DOM extraction、Viewport First、Dynamic Content、Restore、Selection、frame/document boundaries、sidebar/nested scroll、
page-lifetime cache 与 Browser UX。Host permission 仅 `http://127.0.0.1:8765/*`。
Runtime/Translation unavailable 或凭据失效均受控提示；无 model/provider/raw error 展示或 direct fallback。

Cache key 为 normalized text + targetLanguage + profile.id + profile.version + promptVersion；不包含 resolved model/generation settings。
Batch/Single identity 分开学习；未学习 identity 时 lookup 只允许 miss，观察到 identity 改变清理旧 cache。
cache hit 仍检查认证/readiness，不绕过 Runtime offline、Provider unavailable 或 revoke。

## 8. Shared Runtime

Java 21 / 单 Spring Boot application、独立进程；Windows 与 Browser 共用 API、capability、profile、Provider Policy、TaskManager 和 Ollama adapter。
所有 capability 固定 LOCAL_ONLY，只有 loopback local Provider；无隐式 cloud/provider fallback、模型下载或服务管理。

共享有界任务生命周期保持：concurrency 1、queue 4、queue wait 30s、execution 150s、最多 64 条 retained task，终态约 2 分钟过期。
共享 owner、cancel、timeout、不可逆终态与 late-result rejection；Single result 为 JSON string，Batch result 为 structured object。
Cancel 尽力传播，不保证 GPU 即停；revoke 阻止后续请求，不取消先前已接受任务。重启丢失 task/session，Browser registry/credential/revoke 可跨重启。
Browser Translate readiness 只返回 available / 受控 code，不返回 model/provider/raw diagnostics，也不做 inference。

## 9. Browser Security

当前安全决策来源为 [ADR-003（含 R1 amendment）](../ADR/ADR-003-browser-client-security.md)，本轮不重设计或修改 ADR。

- **Explicit Windows pairing approval**：native authority 显式批准 exact extension Origin，短时 single-use proof。
- **Independent Browser credential**：exchange 签发独立 credential；**no master/native token in Extension**。
- **Trusted-only extension storage**：worker 在任何访问前设置 `TRUSTED_CONTEXTS`；content script/page/DOM 不接收 credential。
- **Exact Origin for pairing / mutations**：exchange 和 POST/DELETE 要求 exact approved/registered Origin；其他 Origin-present 请求同样验证 exact registered Origin。
- **Authenticated Origin-less Chrome GET compatibility**：仍需 registered、未 revoke 的 Browser bearer；不把缺失 Origin 当成已验证 exact Origin，不合成 header。
- **Strict Fetch Metadata**：Browser 请求要求完整精确 `none/cors/empty`。
- **Explicit GET allowlist**：Origin-less 分支仅 GET `/api/v1/capabilities/translate/readiness` / GET `/api/v1/tasks/{uuid}`；其他 GET、HEAD/OPTIONS、Origin-less mutations 拒绝。
- **Capability authorization**：Browser 仅 Translate；Ask/Summarize/native readiness/admin 拒绝。
- **Per-client task ownership**：shared TaskManager 绑定 owner；cross-client/native GET/DELETE 与不存在任务同为 404 TASK_NOT_FOUND。
- **Revoke / re-pair**：Windows server revoke 删除注册/verifier；Forget local pairing 仅删除副本，重新授权需新 proof/exchange。

CORS 仅 exact allowed Origin；无 Origin 响应不生成 allow-origin，无 wildcard，不用 CORS 替代认证。
Origin/Fetch Metadata 不隔离持有 bearer 的恶意同 OS 用户 native process；trusted-only chrome.storage.local 也不是 OS credential vault。

## 10. Batch Translation

同一 POST `/api/v1/translate/tasks` 接收 Single `text` 或 Batch `items`，恰好一个。
**一个 normal Browser batch → 一个 shared task → 一次 provider inference**；不展开为逐条 task/inference。
Runtime 拥有 `translate-batch-v1` prompt 和严格 mapping parser，只返回 unique valid requested ids。
duplicate requested id 全部失效，unexpected/empty/malformed item 保持 missing；有效 subset / empty array 可 SUCCEEDED partial。
malformed top-level 或超输出预算受控失败；Runtime 没有自动 item retry。

Batch 每批最多 32 items / 合计 2800 UTF-16 chars / 4096 UTF-8 text bytes；serialized input JSON 同时满足当前 5632-byte profile input budget，HTTP body ≤32 KiB。
Browser 保留 Viewport First 首批约 1000 chars；超 Batch 而在 Single 4000 chars / 5632 UTF-8 bytes 内的完整 record 用 Single。
再超限明确 partial/失败，不截断、拆句或增加 generation/model budget；Runtime 是最终 admission authority。
Translate POST/exchange 不自动重试；只有已知 task GET 的临时网络错误最多两次重试。partial retry 由用户显式发起且只重试失败 records。
真实计数证据：M2B-2A 的 3 records / 1 task / 1 inference / 3 mappings；Browser §41 普通页面 4 records / 1 Batch generation。

## 11. Real Chrome Acceptance

全部来自 **既有 Browser Closing Report §41**（第 2 节固定 commit 链接），不是本轮重新执行。
环境为 Windows、real headful Chrome **154.0.8037.59**、isolated profile、生产 unpacked Extension。

| Gate | Final evidence |
| --- | --- |
| Pairing / readiness | PASS；真实 Windows GUI proof → popup exchange；trusted storage/content access denial，Origin-less authenticated GET 200，popup reopen/Chrome restart 保留 pairing |
| Batch Translate / task polling | PASS；Batch POST 202、task GET 200、structured result；普通页面 4 records / 1 real generation |
| Full page / viewport / Footer / sidebar | PASS；18 段等价复杂 guide，当前可见优先，nested scroll catch-up，不重复译文、保留原文/href/target；真实 MDN 网站未用于这次 migration acceptance |
| Dynamic | PASS；initial catch-up、Load More、partial/retry；受控失败后保留九条成功 records，只显式重试失败项 |
| Restore | PASS；停止 watcher，再 Translate re-arm，在途旧结果丢弃；Restore 后滚动不再翻译 |
| Selection / frame / privacy | PASS；真实原生右键/card、旧响应隔离、same-origin frame 内回填、cross-origin 安全拒绝且不回落 top frame；password 无提交/card，navigation 无旧插入 |
| Cache | PASS；Translate → Restore → Translate 第二轮四条译文零新增 generation；Selection 同文命中；cache 不绕过认证/readiness |
| Runtime offline / recovery | PASS；自有 Runtime 停止时明确 offline/禁用，cached Selection 受控失败；restart 后沿用原 credential |
| Provider offline / recovery | PASS；Runtime 保持运行，停止 relay route 模拟 Provider unavailable，恢复 route 后新 Translate 成功；实际 Provider 进程保持运行 |
| Windows GUI revoke / re-pair | PASS；GUI revoke → cached Selection readiness 401 / 零 inference → invalid pairing → Forget → 新 GUI proof/exchange/readiness → 新四条 Translate 成功 |
| Real MV3 long task | PASS；详见第 12 节 |
| Runtime-only network | PASS；仅访问 127.0.0.1:8765，没有 11434 或 direct Provider fallback |

Browser §41 历史回归记录：npm ci PASS、npm test **104 PASS**（29 content / 70 Runtime-security / 5 popup），static audit **13 PASS**。
Runtime R1 历史记录：Java **30 PASS**、Desktop **67 PASS**、native 与 synthetic Single/Batch、限定 real Chrome security chain PASS。
不将 synthetic HTTP / mock tests 当成真实 Chrome GUI acceptance，也不复用旧 v0.4.1 的 MDN/long-request PASS 替代此次 migration evidence。

## 12. MV3 Long Task

既有真实实测 **38.231 秒（38,231 ms）— PASS**。
verification-only loopback relay 延迟 **35 秒**后转发真实 inference；一次实际 Provider generation、四条正确 DOM translation。
popup 已关闭；worker debugger 在提交前断开，整个检查没有附加 worker debugger；完成后 popup reconnect。

这是受控 relay delay + 真实 inference 的 long Runtime-task 证据，不声称模型天然推理 38 秒或所有 worker lifetime 都有保证。
没有引入 offscreen、keepalive ping、alarms、daemon、WebSocket 或 native messaging 产品 workaround。
relay 和验收 runner 不属于产品执行架构；本次不重跑长任务。

## 13. Privacy

网页/selection text、translation、prompt、raw Runtime/provider body、Authorization、credential/proof 不进入生产日志或正文持久化。
Runtime task/result 为有界短期内存；Browser translation cache 仅页面生命周期内内存，不跨页/重启持久化。
Windows 只主动 capture；Browser Selection 不读写剪贴板。用户显式 Copy Origin/Secret/result 的系统剪贴板行为仍受 Windows history/sync 设置影响。
完整页面跳过 hidden/collapsed/aria-hidden/editable drafts 与 iframe；显式 Selection 允许普通 editable/designMode 文本，但 password/protected fail closed。

Browser §41 历史 secret/body audit 为 42 file checks / 0 matches，Runtime source/build/archive audit 同样 0 matches、0 tracked build artifacts、ignore PASS。
证据只保存 metadata/header-presence 投影，不保存 secret/header values 或原始 task bodies；验证产物不提交。
这是已有 Closing Reports 的限定审计，不宣称本轮重新扫描实际 secrets，也不扩展为全系统磁盘、剪贴板或物理内存审计。

## 14. Known Limitations

- 当前 Windows / Chrome 154 环境验收不保证其他 OS/version、任意网页布局或通用模型质量；复杂页面验收使用等价 guide，未实际重测 MDN 网站。
- 模型可产生 partial/malformed 或超预算结果；Runtime 安全失败，Browser 显式处理 partial。保守字符/字节预算不是精确 tokenizer。
- Cache identity 来自成功 task metadata；全 cache-hit 页面没有持续发现 profile/prompt hot reload 的新契约。cache 不跨页/重启。
- POST 通信失败可能已经接受任务，不能保证未提交或已取消；无持久 replay/idempotency，Cancel 不保证 GPU 即停。
- Revoke 不取消已接受任务；task/结果有界短期保留、restart 失效。Native 仍共享 native-local owner，没有自动 credential rotation。
- chrome.storage.local 不是 OS vault；同用户恶意进程/credential compromise 不在 Browser Origin isolation 保证范围内。
- Windows Copy fallback 仅保守原生编辑控件、Unicode 纯文本条件恢复；Windows clipboard history/sync、任意文件系统断电 durability 与未实测平台边界仍保留。

## 15. B11/B12

**B11 inline BR layout — DEFERRED**。

**B12 mutation debounce starvation — DEFERRED**。

两项都没有在 migration、最终 Chrome Closing 或本次文档同步中修复。M2 CLOSED 不表示它们被修复或移出已知限制。

## 16. Deferred Scope

Browser Ask/Summarize、M3、Finance integration/Gateway、Memory/conversation persistence、Knowledge/RAG/embedding、Agent/tools、Cloud、Streaming、
Voice/Vision/OCR/screenshot、完整 React/WebView2 Workspace、installer/auto-update/Windows Service、backup/sync 均未在本轮实现。
Finance Reality Sync 是未来 Finance integration 前置条件，本次没有核验学校笔记本或访问 Finance DB。
测试精简仅作为下一维护步骤记录，本轮没有删改 tests/scripts/config、没有开始 M3。

## 17. Repository Boundaries

Personal AI Workspace 拥有 Runtime、Windows native client、policy/model/prompt/provider/task/security contracts。
Local AI Assistant 拥有 Browser Extension、DOM/viewport/dynamic/Restore/Selection/frame/cache/UX。
仓库通过 authenticated loopback Runtime API 连接，没有复制/合并 Browser code、第二 AI backend 或 Finance DB dependency。

本次只修改四个文档：`README.md`、`docs/STATUS.md`、`docs/architecture/current-architecture.md`、`docs/milestones/M2-CLOSING-REPORT.md`。
未修改 src、Desktop source、pom.xml、tests、scripts、config 或 security implementation；local-ai-assistant 全程只读。
历史 Closing Reports 和 ADR 原文保留；本分支未 merge/push。

## 18. Architecture Compliance

两个客户端共用单 Runtime、单 TaskManager/queue、Provider Policy 与 local Provider；没有 Browser-owned model/prompt/parser/settings、direct Provider retry/fallback。
Batch 保持一个 task/inference，LOCAL_ONLY/loopback、capability authorization、per-client ownership、取消/deadline/终态边界保持。
exact-Origin pairing/mutations 与受认证的 Origin-less GET allowlist 遵循 ADR-003/R1，未弱化认证或重设计 ADR。
verification relay/CDP/UI Automation 不进入产品路径，没有新增保活或未来空框架。

本次 `git diff --check` **PASS**；已检查 `git diff --stat`、完整 `git diff` 与 `git status`，确认只有上述四个文档。
Java/Desktop/Chrome tests、native/security smoke 和 privacy audits 没有在本次重跑；其 PASS 全部明确引用已有 Closing Reports。
精确本地 docs commit 与最终工作树状态由本轮 Git handoff 提供，不替换第 2 节的已发布产品基线。

## 19. Next Maintenance Step

下一步：**Post-M2 Test Suite Simplification**。

目标：**Minimal High-Value Testing**。

本次仅记录该步骤，不删测试、不继续开发 M3。停在本地 docs closing commit，等待 Closing Review。
