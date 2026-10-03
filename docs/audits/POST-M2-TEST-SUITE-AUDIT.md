# POST-M2 TEST SUITE AUDIT REPORT

Date: **2026-10-03 (Asia/Shanghai)**. Scope: **TEST AUDIT ONLY**.

**RECOMMENDED PLAN: Balanced.** 当前主要成本是重复覆盖、长场景的故障定位、验收脚本的历史耦合；并非自动测试总耗时过长。保留 security / lifecycle / DOM / credential 的独立保护层，先整合共享 capability 错误与取消场景、固定迁移扫描和文案断言。没有足够证据建议整文件删除任何当前正式自动回归 suite。

本轮仅新增本报告；未删除、合并、重命名或修改测试，未修改生产代码、Runtime contract、Browser behavior、依赖或配置，未创建实现 commit。local-ai-assistant 只有 fetch、读取和现有自动测试执行，没有源文件修改。B11 / B12 仍 **DEFERRED**；没有开始 Memory、Finance、RAG、Agent 或 M3。以下 Keep / Merge / Remove Candidate 全部是未来建议。

## 1. Reality Check 与证据范围

两个仓库都依次执行了 `git status`、`git branch --show-current`、`git rev-parse HEAD`、`git fetch origin`、`git rev-parse origin/main`、`git log -10 --oneline --decorate`。fetch 均成功，没有 checkout / pull / reset / merge。

| Repository | Branch | Local HEAD = fetched origin/main | Initial working tree | Gate |
| --- | --- | --- | --- | --- |
| qianlixunbai/personal-ai-workspace | main | `6aa76b4f9c58bcbcec3c89d42f95aa595cff62f2` | clean | PASS；M2 CLOSED — GO 基线 |
| qianlixunbai/local-ai-assistant | main | `b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0` | clean | PASS；v0.5.0 / M2B-2B CLOSED — GO 基线 |

未发现适用于这两个仓库的 AGENTS.md。memory registry 搜索没有相关命中，本报告不依赖历史 memory。

以 `git ls-files` 建立正式 inventory，以 ignored 目录中的脚本清单补充历史工具；不把 node_modules、Chrome profile 内第三方扩展、build outputs、截图、日志、credential registry 或 clean-source 快照算作当前 suite。未读取 credential 内容来做 inventory，未将 secret 或原始任务正文写入报告。

证据顺序：当前 source/tests → 实际 Closing evidence → 历史 bug reports → 当前 architecture → 本轮要求 → README/status。Unknown 标为 **UNVERIFIED**。Closing 文档中的 PASS 是历史验收，不能冒充本轮重跑。

### 本轮实际执行

| Command / repository | Result | Wall time | 限定 |
| --- | --- | --- | --- |
| Workspace `mvnw.cmd test -q` | **30 PASS，0 failures/errors/skipped** | **14.35 s** | 使用当前 source，Spring + loopback fake Provider；未执行 clean verify / 打包 / real Ollama |
| Workspace `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-restore --logger 'console;verbosity=minimal'` | **67 PASS，0 failed/skipped** | **4.66 s**；runner 显示测试约 1 s | 包含本次 build；WinCred 使用隔离 test target，UIA 使用自己的 fixture；未操作用户真实选区 |
| Browser `npm test` | **104 PASS = 29 + 70 + 5** | **25.86 s** | 当前 production JS + jsdom / VM；没有 Chrome 或 real Runtime inference |
| Browser `node test/static-audit.js` | **13 PASS** | **0.14 s** | 独立命令；不在 npm test 中 |

Java Surefire 本轮各 suite 时间：RuntimeApi **5.156 s**、TaskManager **1.316 s**、OllamaProvider **0.369 s**、BrowserClients **0.190 s**、ProfilePolicy **0.007 s**、TextCapabilities **0.005 s**。这些不含 Maven 启动等开销，不能与 wall time直接相加作为 benchmark。

未重跑：native real-local smoke、synthetic real-Provider security Single/Batch smoke、real-runtime-smoke、Chrome/CDP/headful Windows acceptance、MV3 long task、实际 secret/body archive audit。它们当前可运行性和耗时为 **UNVERIFIED**；下面成本表是依赖和代码结构判断。测试运行只生成 ignored build/test 产物。

## 2. 分类与计数口径

| Category | 含义 | 决策原则 |
| --- | --- | --- |
| A — MUST KEEP | 安全、数据边界、高风险行为或真实 bug 回归 | 行为不能消失；可以保留在更清晰的矩阵中 |
| B — KEEP BUT MERGE | 高价值但碎片/重复 | 先完成覆盖迁移并验证，再考虑去掉重复部分 |
| C — REDUNDANT | 同层、同路径、同失败机制已有充分保护 | 精确到重复场景/断言；不以标题相似推断重复 |
| D — IMPLEMENTATION-COUPLED | 无独立业务/安全价值的表示或内部结构要求 | 放宽/移除表示断言，保留行为断言 |
| E — MIGRATION-TEMPORARY | 固定 migration/header/debug 时点 | 从日常 suite 移出或历史归档候选 |
| F — MANUAL / ACCEPTANCE TOOLING | real Runtime / Chrome / Windows / MV3 验收 | 从自动数量分离；保留有用入口 |
| G — FIXTURE / SUPPORT | mock、fixture、helper、harness | 不按 test case 价值计数；跟随依赖路径保留 |

每行给一个主分类；一个文件包含多种价值时，文件采用 B，下面按主要场景进一步分类。C/D/E 只表示候选，不意味着本轮执行删除。

Java **30 是展开后的 JUnit invocation**，不是 30 个文件，也不是全部安全矩阵行：6 个执行文件、26 个测试方法声明；其中 TaskManager 的 4 个 boolean 参数化方法各运行两次。RuntimeApiTest **仅一个 @Test**，内部串行执行几百行 request / error / security / batch 断言。Desktop **7 文件 / 40 方法声明 / 67 展开 cases**，并没有大批 ViewModel getter 测试。Browser **29 scenario + 70 check 调用 + 5 popup scenario**：70 包含循环中的数据行，不能与 Java 方法数直接比较。static 13 是 architecture/privacy/release guard 条目。

## 3. Workspace：完整文件 inventory 与决策表

Java 路径根：`src/test/java/io/github/qianlixunbai/workspace/`。Desktop 路径根：`desktop/tests/PersonalAiWorkspace.Desktop.Tests/`。下表的短名仅用于阅读，真实文件路径见各 subsection 链接。

| Test file / logical suite | Current purpose / count | Category | Keep / Merge / Remove Candidate | Why / protected behavior | Duplicate with | Suggested future action | Risk if removed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| J1 api/RuntimeApiTest.java | Spring real HTTP → mock Provider；1 invocation | B | Merge / 重组，保留核心 | Native/Browser auth、ownership、capabilities、Single/Batch、redacted logs | J3 provider modes、J4 policy、J5/J6；security smoke | 分开 native capability、Browser security matrix、batch contract；一次 Spring context，各场景独立状态 | HIGH：会同时丢多个 server contract |
| J2 task/TaskManagerTest.java | 6 methods / 10 invocations | B | Keep + Merge 部分 result-shape 复制 | 共享 owner、queue、cancel、timeout、retention、late output、sanitization | J1 batch cancel；J6 两能力 cancel | 核心 scheduler 测一次；String/structured 保留代表性等价/不可变证明 | HIGH：不同 capability 的 happy path不能替代 |
| J3 provider/ollama/OllamaProviderTest.java | 7 methods / cases | B | Keep；merge error-classification setup | 真 HTTP parser、bounded body、redirect、timeout、cancel、egress policy | J1 provider modes；J4 locality | adapter 实测保留；纯 classify 方法并入控制错误矩阵 | HIGH：mock capability happy path不测真实 HTTP adapter |
| J4 model/ProfilePolicyTest.java | 2 methods / cases | A | Keep | profile ownership、LOCAL_ONLY/cloud deny、loopback endpoint 与 unsafe budget | J3 final-egress、J6 capability policy | 小型 policy/config 矩阵；保留各层 admission / execution / egress | HIGH：安全配置边界不可由 Chrome 推导 |
| J5 security/BrowserClientsTest.java | 7 methods / cases | A | Keep | registry verifier/ACL/atomicity、TTL/replay/budgets、restart/revoke、credential identity | J1 exchange/auth；security smoke restart | 维持 registry 专属边界；少量格式案例table化 | HIGH：HTTP happy path不能证明磁盘/状态故障 |
| J6 capability/TextCapabilitiesTest.java | 3 methods / cases | B | Merge | capability auth/LOCAL_ONLY、Runtime-owned prompt、redaction、batch reserve | J2 cancel/late；J4 policy | 每能力保留 wiring/prompt/output/policy，取消 lifecycle 不再每能力完整复制 | HIGH 若整文件移除；重复 cancel部分可转移 |
| JG TestSettings.java | config/profile/task fixtures | G | Keep | 安全 fake settings；并非 test case | J2 ManagerScope 等 | 共享 fixture 命名/配置跟随保留 suite | 所有依赖测试无法运行 |
| D1 RuntimeClientTests.cs | 9 methods / 18 cases | B | Keep + Merge shared transport | auth、strict envelope、cancel accepted POST、health、controlled errors、redirect | D2 两能力错误/cancel；D3 shared transport | 一份通用 RuntimeClient contract；保留 health 历史 bug | HIGH：server tests不验证客户端 admission |
| D2 AssistantCapabilitiesTests.cs | 5 methods / 9 cases | B | Merge | Summarize/Ask body/profile/prompt、wrong capability、input budgets | D1 shared errors/cancel/poll | generic 部分合入 D1；每能力保留字段/路由/结果身份 | MEDIUM/HIGH：整删丢 capability-specific contract |
| D3 BrowserPairingTests.cs | 13 methods / 27 cases | B | Keep + Merge parser/transport | Native approval、Origin/no-network、safe list/revoke、WPF secret lifecycle | D1 transport、D3 自身 malformed 与 replacement场景 | invalid response table、pairing UX state scenario；保留 immediate-clear 与 late response 观察点 | HIGH：secret/UI时序是独立风险 |
| D4 CredentialTests.cs | 2 methods / cases | A | Keep | 实际 WinCred隔离目标 roundtrip/forget、owner-only import ACL | J5 server ACL作用对象不同 | 不用 fake credential store 替代原生 boundary | HIGH：真实 Windows存储不能由API矩阵替代 |
| D5 HelperProcessTests.cs | 2 methods / cases | A | Keep | actual selection helper拒绝零前台；hung child终止/回收 | J2 timeout语义不同 | 保留有界进程验收，独立于 scheduler | HIGH：失控 helper/误读 ambient selection |
| D6 SelectionAndLifecycleTests.cs | 8 methods / cases | B | Keep；小范围 Merge | selection/copy、clipboard所有权/恢复、hotkey/single-instance、WPF action state | D7 UIA、D5 helper；D6 hotkey fake/native互补 | clipboard/privacy保持分层；移除局部文案/label断言候选 | HIGH：OS数据边界不等于普通 property |
| D7 UiaTests.cs | 1 method / case | A | Keep | actual UIA仅 selected fixture text、password/untrusted/empty fail closed | D6 SelectionRules为另一层 | 保留实际 UIA，不能只留下分类 helper | HIGH：native automation语义丢失 |
| scripts/real-local-smoke.ps1 | opt-in native三能力 real Ollama | F | Keep | packaged Runtime绑定loopback、401、真实三能力推理 | J1 fake HTTP；Browser synthetic smoke native Translate | release acceptance每能力一条；不做单元边界全排列 | MEDIUM/HIGH：失去真实打包/provider接线证明 |
| scripts/browser-security-smoke.ps1 | opt-in Single + `-Batch`，real Runtime/Provider、synthetic headers | F | Keep；未来减重复matrix | pairing两client、Originless GET、ownership、restart/revoke、batch生成计数 | J1安全矩阵、J5重启、Browser real-runtime | 一份共享安全短路径；Single/Batch各证明输出形状，完整拒绝矩阵归J1 | HIGH 若连真实restart/registry/one inference证明也删 |
| scripts/chrome-get-security-smoke.js | R1 headless Chrome自然header诊断 | E | Remove Candidate / 历史归档 | 确认真实 Origin-less GET bug；固定旧commit+branch+Chrome154 | Browser chrome-runtime-smoke及最终§41 | 保留历史证据；将自然header gate交给可复现release acceptance后可退出日常入口 | HIGH 若未来没有自然GET验收；LOW 若完整替代已存在 |
| scripts/ollama-smoke-relay.py | `-Batch`真实forward计数fixture | G | Keep | 转发真实Provider/count，不能按额外case计数 | Browser providerRelay用途相似、消费者不同 | 只随 security Batch tooling保留；不进入产品架构 | 删除会让one batch/one inference验收不可运行 |
| scripts/privacy-audit.py | source/build/archive/evidence secret/body audit | F | Keep | stdin临时秘密、日志正文、archive、ignored/tracked审计 | static audit只扫source；历史m1.5/m2b1 scanners | 保留通用audit；历史复制scanner可归档 | HIGH：static字符串guard不能替代实际secret审计 |
| Maven / xUnit project wiring | pom、Desktop test csproj、Directory.Build.props、global.json | G | Keep | 执行入口、Java21/.NET10/Windows依赖 | 无独立测试 | 不算case，不改变本轮依赖 | 删除会破坏套件执行 |

Java 内嵌支持项全部归 **G**：J1 的 `MOCK` HttpServer、MODE/CHAT_CALLS/BATCH_OUTPUT/LAST_CHAT、slow latches、`send/browser/browserRequest/createPairing/exchangeBody/submitAndPoll/pollBrowser/batch/token/tree/valid`；J2 的 `ManagerScope/output/terminal/await`；J3 的 loopback server/executor、payload/call counters、`execution/assertCode/respond`；J5 的 `MutableClock/file/pair`；J6 的 `FakeProvider/submission`。CapturedOutput 是运行日志断言的支持，不能当额外 test case。保留使用者时保留其 helper；删 fixture 名字不增加精简价值。

### 3.1 Java主要场景逐项分类

[RuntimeApiTest.java](../../src/test/java/io/github/qianlixunbai/workspace/api/RuntimeApiTest.java)；以下是一个长 @Test 内的 logical suites，不假称当前已有独立 BrowserSecurityMatrixTest。

| 当前位置 / 主要场景 | Category / future decision | 保护内容；重复与删减条件 |
| --- | --- | --- |
| `runtimeSecurityProviderSeparationAndTranslateContract` health/native admission | A / Keep | Runtime health独立于Provider；protected route无凭据401；native token不能跨Browser Origin；保留每类route接线 |
| 同方法 Translate success / validation / 413 / model/profile exposure | A / Keep | 路由、202/Location、Single string、profile/prompt归Runtime；unknown/model/language/char/UTF8/body预算均有业务价值 |
| 同方法 Summarize/Ask各跑 Provider mode 1/2/3/4/0 | B / Merge | 每能力valid output/profile/prompt保留；相同 provider error permutations与J3重叠，可共享一次transport/output错误matrix并保留每capability admission失败代表 |
| 同方法 Summarize/Ask request字段与forbidden keys | B / Merge | 每能力text vs question、profile ownership、stateless/no-history边界保留；unknown-field公共规则可共享，不删特定能力的schema证明 |
| CapturedOutput / forbidden private values | A / Keep | 输入/输出/raw body/token/proof不得入日志；不能只扫source断言无logger |
| `browserSecurity` explicit approval / exact exchange / no-store / replay | A / Keep | approved proof与credential发放；exchange拒绝Authorization/native、错误Origin；CORS exact而非wildcard |
| `browserSecurity` Origin-present credential / metadata / capability / routes | A / Keep | wrong/missing/malformed/native/revoked凭据、wrong Origin、Fetch Metadata、Translate-only；不被happy Chrome取代 |
| `browserSecurity` list privacy / cross-owner GET+DELETE / native-browser isolation | A / Keep | 无credential/verifier metadata；wrong owner与missing同404，涵盖双向隔离 |
| `chromeOriginlessGetCompatibility` two allowed GET / no synthesized CORS | A / Keep | registered未撤销browser bearer + none/cors/empty；readiness与owned task成功；历史真实bug |
| 同helper invalid credential / each metadata dimension / route+method denial | A / Keep | 非GET/HEAD/OPTIONS、Originless mutation、extra路径/UUID、admin/provider/unknown route；维度逐项有名称和expected status |
| 同helper cross-owner / Origin-present regression / revoke tail | A / Keep | 新GET路径不能变native owner或放松已有Origin约束 |
| `batchContract` available projection / preflight | A / Keep | readiness仅safe availability；provider/model不可暴露；route allowlist仍自动保护 |
| `batchContract` valid reorder / multi-item / 32 items / one task & generation | A / Keep | mapping按请求顺序、public identity、Runtime-ownedprompt与once execution；新增高风险contract |
| `batchContract` input admission矩阵 | B / Merge | text/items互斥、empty/null/wrong type/duplicate request IDs、count/char/UTF8/serialized escaping/HTTP budget、forbidden ownership；table化而不是丢不同约束 |
| `batchContract` partial mapping 9 outputs | B / Merge | valid、missing、unexpected、duplicate全部失效、blank/wrong-id类型、empty/extra item；保留各故障类及有效subset；不能合成全FAILED |
| `batchContract` malformed top-level 8 outputs | B / Merge | 非array/null/multiple JSON/code fence/duplicate key、semantic output budget与HTTP body bound；有名table行，不能把两层budget当等价 |
| `batchContract` repeated generic Provider modes | C / Remove Candidate部分 | 与同方法三能力及J3重复的unavailable/missing/malformed包裹；先保留一次batch-specific parse failure和shared输出budget证明 |
| `batchContract` actual RUNNING cancel / late provider HTTP | A / Keep | 真实adapter cancellation接到任务终态；与J2 fake work不同层，不能整删 |
| `batchContract` revoke accepted task does not cancel it | A / Keep | 撤销阻止后续HTTP，但已接受任务可完成；J5持久撤销不证明这个语义 |
| readiness JSON字符串完整相等 / concrete版本常量 | D / Remove Candidate断言部分 | 保留safe fields/absence和profile/prompt身份；去掉JSON属性顺序要求。版本若是对外contract则继续保留，不以“常量”自动删除 |

[TaskManagerTest.java](../../src/test/java/io/github/qianlixunbai/workspace/task/TaskManagerTest.java)：

| Method / current invocations | Category / decision | 未来最低覆盖；重复与风险 |
| --- | --- | --- |
| `ownersIsolateRunningQueuedAndRetainedTasksAndOwnerCancellationWinsLateResult` ×2 | B / Merge | wrong browser/native owner GET+cancel、QUEUED/RUNNING/retained404、owner cancel；String/structured至少各一条代表路径 |
| `boundedQueueCancellationAndLateSuccess` ×2 | B / Merge | queue full、不执行已cancel queued work、capacity重新可用、terminal cancel幂等、late result discard；boolean output shape不是两套scheduler |
| `queueAndExecutionTimeoutsRemainDistinctAndLateResultsAreDiscarded` ×2 | B / Merge | QUEUE/EXECUTION不同phase、有界timeout、late result不可覆盖；生命周期集中一份，structured保留一次终态兼容 |
| `resultRetentionIsBoundedAndExpires` ×2 | B / Merge | retained capacity、TTL、404、容量恢复；两种result无需完整复制TTL等待 |
| `cancelledHookPropagatesAndUnexpectedFailuresAreSanitized` ×1 | A / Keep | attach-after-cancel调用hook、内部失败不泄secret；不是private getter噪声 |
| `structuredResultIsImmutableAndDiagnosticsAreRedactedAndArbitraryObjectsAreRejected` ×1 | A / Keep | structured允许形状、immutable、redaction、arbitrary object拒绝；属于安全/数据contract |

[OllamaProviderTest.java](../../src/test/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaProviderTest.java)：

| Method | Category / decision | 保护内容；未来动作 |
| --- | --- | --- |
| `realHttpParsingAndGenerationSettings` | A / Keep | real adapter wire payload与nonstream/Runtime-owned settings；不能让Browser拥有model。具体值是配置接线，可集中fixture，不删ownership证明 |
| `unavailableMissingAndRawErrorAreControlled` | A / Keep | tags unavailable/missing model/raw chat failure；未ready不执行chat、sanitized cause |
| `malformedOversizeIncompleteAndRedirectsAreRejected` | B / Merge table | malformed/null/trailing/oversize/incomplete/truncated/redirect；独立协议类保留 |
| `providerTimeout` | A / Keep | 实际HTTP deadline，不等于J2 scheduler timeout |
| `inFlightCancellation` | A / Keep | cancellation传播到正在执行HTTP；独立于UI发DELETE |
| `connectionAndRequestTimeoutClassification` | B / Merge | 当前直接调用内部classify，但CONNECT/PROVIDER错误phase有诊断contract价值；并入error matrix，不能归为纯D整删 |
| `finalEgressPolicyRejectsCloudBeforeHttp` | A / Keep | 最后一层policy、zero chat；service policy与adapter egress是不同绕过入口 |

[ProfilePolicyTest.java](../../src/test/java/io/github/qianlixunbai/workspace/model/ProfilePolicyTest.java)：`profileResolvesAndCloudIsDeniedInEveryMode` 与 `arbitraryEndpointsAndUnsafeBudgetsAreRejected` 均 **A / Keep**。前者profile resolver/所有privacy modes/cloud deny；后者remote/path/userinfo/query endpoint拒绝与invalid config；security smoke没有这些配置故障。

[BrowserClientsTest.java](../../src/test/java/io/github/qianlixunbai/workspace/security/BrowserClientsTest.java)：

| Method | Category / decision | 保护内容；为何不是高层重复 |
| --- | --- | --- |
| `credentialAuthenticationDoesNotInventOriginBinding` | A / Keep | credential-only identity与exact Origin validation分离；wrong secret/unknown/revoked；R1行为根基 |
| `credentialAndRevocationSurviveRestartButPairingDoesNot` | A / Keep | verifier持久化、proof仅内存、secret不落registry、revoke跨restart |
| `expiryReplayFailedAttemptAndGlobalBudgets` | A / Keep | TTL、single-use、per-proof/global throttling、window恢复；mutable clock有快速确定性价值 |
| `boundedSessionsRegistryAndExactOrigin` | A / Keep | 8-session/32-client bound、revoke释放capacity；Origin规范，不能以UI拒绝输入代替 |
| `malformedAndAmbiguousRegistryFailClosed` | A / Keep | unknown/duplicate/extra/null/oversize registry与pending ambiguity；故障注入非普通HTTP happy path |
| `atomicFailureDoesNotPublishAndStalePendingNeverResurrectsRevocation` | A / Keep | commit失败不发布、stale pending不复活撤销；安全持久状态边界 |
| `privatePermissionsAndExclusiveRegistryLock` | A / Keep | actual owner-only ACL/POSIX与exclusive writer lock；与Windows WinCred是不同对象 |

[TextCapabilitiesTest.java](../../src/test/java/io/github/qianlixunbai/workspace/capability/TextCapabilitiesTest.java)：

| Method | Category / decision | 必保留与可移交部分 |
| --- | --- | --- |
| `batchAdmissionAndReadinessRequireTranslateAuthorizationAndLocalPolicy` | A / Keep | forbidden principal、batch submit/readiness LOCAL_ONLY-before-execution、DTO redaction、prompt reserve |
| `bothCapabilitiesEnforceLocalPolicyBeforeExecution` | B / Merge | Summarize/Ask各一条policy wiring，provider zero execution；J4全mode matrix不必再次复制 |
| `promptsStayOwnedByRuntimeAndRunningCancellationDiscardsLateOutputForBoth` | B / Merge | 每能力promptVersion/system/input分离、privacyMode、redaction必须保留；共同cancel/late状态移交J2，J1保留真实HTTPcancel |

## 4. Desktop：方法与低价值断言

本轮没有找到“getter/property passthrough套件”。高数量主要来自 InlineData、真实HTTP envelope与pairing安全/时序；不能按67→20处理。下表覆盖全部40个测试方法声明；括号×n为当前expanded cases。

### 4.1 RuntimeClientTests.cs（18）

[Source](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/RuntimeClientTests.cs)。

| Method | Category / decision | Protected behavior / duplicate / removal risk |
| --- | --- | --- |
| `SubmitPollAndSuccessFollowM0ContractWithoutModelOrBodyInDiagnostics` | A / Keep | Translate body/endpoint/auth、progress、result与redaction；可做generic运行场景并配capability-specific请求表 |
| `CancelWhilePostPendingWaitsForIdentityThenDeletesAcceptedTask` | A / Keep | 未拿到taskId的cancel不能丢已接受任务；不等于server已知ID cancellation |
| `HttpErrorsAreCategorizedAndRawBodyTokenNeverEnterException` ×6 | A / Keep | 每种对外DesktopError和secret redaction；matrix早已存在，没有必要为减数字砍行 |
| `AcceptedTaskFailureIsReadFrom200Envelope` ×5 | A / Keep | HTTP200仍可能FAILED/CANCELLED/TIMED_OUT；与HTTP非2xx不同输入路径 |
| `OfflineIsControlledAndCredentialMissingNeverSendsHttp` | A / Keep | offline安全提示、missing secret零请求；可以供capability共享 |
| `MalformedUnknownDuplicateMismatchedAndOversizeResponsesFailClosed` | B / Merge named table | parser/schema、taskId、status/error一致性、1MiB bound；不同失败维度保留，改善定位 |
| `HealthDoesNotUseCredentialAndReadinessProvesAuthWhenProviderOffline` | B / Merge health scenario | unauth health vs authenticated readiness；不能删除credential隔离断言 |
| `HealthNegotiatesJsonWithActuatorVendorDefault` | A / Keep | **真实M1 bug**：Accept缺失导致vendor MIME而Desktop误报malformed；与健康成功不能等价 |
| `RedirectAndForeignLocationNeverRedirectOrAcceptTask` | A / Keep | 外域Location/redirect不可接受或泄bearer；server routing不能代测client |

### 4.2 AssistantCapabilitiesTests.cs（9）

[Source](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/AssistantCapabilitiesTests.cs)。

| Method | Category / decision | Protected behavior / duplicate / removal risk |
| --- | --- | --- |
| `SubmitPollSuccessUsesOneClientAndOnlyCapabilityFields` ×2 | B / Merge | 保留Summarize text vs Ask question、profile/endpoint和output；generic完整Q/R/S progress归D1 |
| `CancelPendingSubmissionObtainsIdentityAndDeletesSameCapability` ×2 | B / Merge | 与D1同AssistantOperation；将action维度放共享cancel scenario，不能无replacement删除 |
| `SharedErrorsAndMalformedResponsesRemainControlled` ×2 | C / Remove Candidate重复部分 | 内部每action重复401/provider/model/queue/offline/common malformed；D1已同层同client覆盖。保留此处独有promptVersion为空、8193结果budget等证明再迁移 |
| `PollCannotSwitchCapabilityOrDisplayAnotherActionsResult` ×2 | A / Keep | polling不得switch Ask/Summarize，旧capability output不显示；generic auth不能覆盖 |
| `CapabilityInputBudgetsRejectEmptyCharactersAndUtf8Overflow` | A / Keep | Ask/Summarize独立字符/UTF8预算及legal boundary；不是字符串格式噪声 |

### 4.3 BrowserPairingTests.cs（27）

[Source](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/BrowserPairingTests.cs)。

| Method | Category / decision | Protected behavior / duplicate / removal risk |
| --- | --- | --- |
| `PairingUsesNativeStackApprovalContractAndParsesProofMetadata` | A / Keep | native authenticated POST、无Origin、userApproved、safe proof + redaction；字段值与外部contract绑定 |
| `InvalidOriginNeverSendsARequest` ×6 | B / Merge | UI/client boundary各非法类可以named table；server exact Origin仍独立保留；建议保留wrong scheme、bad extension syntax、suffix/newline代表 |
| `SecurityErrorsAreControlledAndNeverIncludeRawBody` ×6 | B / Merge | SECURITY_STATE/PAIRING-specific capacity/origin不是generic错误；401/fallback/重复redaction setup可与D1共享 |
| `OfflineMissingCredentialAndCancellationRemainControlled` | B / Merge | shared Request transport部分重复D1，但取消与敏感proof边界仍保留一次pairing wiring |
| `InvalidPairingResponseFailsClosed` ×4 | B / Merge | 与下一方法合named malformed-proof table；保留duplicate-key、null/type、nonJSON代表 |
| `ProofShapeExpiryExtraFieldsAndResponseLimitsAreValidated` | B / Merge | proof shape/date/secret-bearing extra/size；不是前表所有行的等价复制；expired visibility另由WPF测 |
| `ListingOnlyAcceptsSafeMetadataAndRevokeUsesNativeDelete204` | A / Keep | no secret/verifier metadata、nativeGET/DELETE204；反射property形状可D，但wire/body absence不可删 |
| `ListingRejectsSecretBearingResponses` ×2 | A / Keep | credential/verifier都不得进入DTO/UI；不同字段来源保留matrix行 |
| `WpfOnlyClickCreatesPairingThenReplacementAndCloseClearSensitiveDisplay` | A / Keep | explicit click、无自动创建、undo禁用、close清secret、closed window不重建 |
| `CloseDuringCreationCancelsAndNeverRepopulatesEvenWithLateResponse` | A / Keep | close发cancel，late response不重显secret；必须控制response时序 |
| `FailedReplacementClearsPreviousProofAndRevokeOnlyRemovesConfirmedClient` | A / Keep | replacement failure清proof；revoke失败保留list、成功移除；跨组件状态 |
| `InvalidOriginAndExpiredProofNeverBecomeVisibleInWpf` | A / Keep | invalid无network、expired无secret/copy；保留WPF实际行为 |
| `BeginningAReplacementImmediatelyClearsPriorVisibleSecret` | B / Merge | 可整合进replacement状态scenario，但必须在pending response期间观察immediate-clear，不能只检查最后为空 |

局部 **D** 候选：`PairingStatusText` 对英文完整片段的 `Contains`、metadata反射property列表、JSON/string表示顺序。局部 **A**：`IsUndoEnabled=false`、copy disabled、text清空和可见性——这是secret lifecycle，不能误称CSS/UI实现细节。

### 4.4 Windows selection / credential / lifecycle（13）

| File / method | Category / decision | Protected behavior / duplicate / removal risk |
| --- | --- | --- |
| [CredentialTests](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/CredentialTests.cs) `WindowsCredentialManagerRoundtripAndForgetUseIsolatedTestTarget` | A / Keep | actual WinCred save/load/forget，test GUID target；不可换getter测试 |
| 同文件 `ExplicitBootstrapRequiresOwnerOnlyFilePermissionsAndRejectsMalformedFile` | A / Keep | import ACL owner-only / world-readable拒绝 / malformed拒绝；外部文件安全边界 |
| [HelperProcessTests](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/HelperProcessTests.cs) `RealSelectionHelperRejectsZeroForegroundWithoutReadingUserSelection` | A / Keep | actual helper零前台安全拒绝 |
| 同文件 `HungHelperIsTerminatedAndReapedAtDeadline` | A / Keep | 子进程deadline及PID回收，不是J2 timeout复制 |
| [SelectionAndLifecycleTests](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/SelectionAndLifecycleTests.cs) `SelectionSuccessEmptyProtectedChangedAndTimeoutNeverSendCopy` | A / Keep | protected/changed/untrusted/timeout不会Copy；分类与副作用边界 |
| 同文件 `FreshControlledCopyRestoresClipboardAndRefusesOldText` | A / Keep | 新clipboard sequence、旧内容拒绝、恢复；用户数据不能被覆盖 |
| 同文件 `ClipboardOwnerChangesAndRichFormatsFailClosedWithoutOverwriting` | A / Keep | 外部owner/格式/restore失败/oversize fail closed |
| 同文件 `ClipboardReadCancellationStillAttemptsRestore` | A / Keep | 异常/取消路径仍恢复；happy copy不会必然抓到 |
| 同文件 `HotkeyConflictIsExplicitAndDisposeReleasesExactlyOnce` | B / Keep/可merge | stub证明失败不release与Dispose幂等；native下一条不测wrapper Dispose，不能直接当dead duplicate |
| 同文件 `NativeHotkeyConflictThenShutdownMakesRegistrationAvailableAgain` | A / Keep | actual test-only key conflict/re-register；环境风险中等 |
| 同文件 `SingleInstanceSignalsPrimaryAndReleasesMutexOnShutdown` | A / Keep | actual mutex/event跨thread、shutdown恢复，GUID隔离 |
| 同文件 `WpfResultCardLoadsAndRemainsUsableForManualInput` | B / Keep/merge | action switching清旧输入/输出、各预算、busy/cancel/read-only；button label文字与initialization getter断言为D候选 |
| [UiaTests](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/UiaTests.cs) `ActualUiaReadsOnlySelectedFixtureTextAndRefusesFixturePassword` | A / Keep | hidden自有fixture、selected-only/password/untrusted/empty；非ambient capture |

支持项 **G**：D1 `Handler/Response/Envelope`（D2复用）；D2 capability envelope/Response；D3 Handler/Pairing/Clients/StaAsync；D6 CopyPort/HotkeyStub/Controller/Sta；D7 hidden HwndSource/TextBox/PasswordBox/Button fixture。它们是所有mock/STA/nativefixture基础设施，不是另一些“低价值case”。没有独立fixture源文件遗漏；均嵌在上述测试文件中。

## 5. Workspace：ignored历史工具inventory

这些是当前磁盘存在、Git明确忽略的local历史文件，**不属于30/67或可从clean checkout获得的release tooling**。未来归档建议不授权本轮清理磁盘或删除证据。

| File（均在 `.verification/`） | Purpose | Category | Future action / duplicate / risk |
| --- | --- | --- | --- |
| desktop-automation.ps1 | M1真实UIA/foreground/tray/hotkey驱动 | F | 保留可参考acceptance；与D6/D7层不同，clean-checkout复现UNVERIFIED |
| m1-closing-scan.ps1 | 固定M1 source/closing扫描 | E | 归档候选；现有privacy audit更通用；历史证据不删 |
| m1.5-gui-functions.ps1 | GUI控件/操作/脱敏evidence helper | G | 被历史GUI脚本依赖；不算case |
| m1.5-gui-probe.ps1 | GUI控件探测/诊断 | E | 历史诊断；不是发布gate；再运行价值UNVERIFIED |
| m1.5-gui-manual.ps1 | 实际GUI手动三能力流程 | F | 保留历史acceptance参考；与real-local API smoke不等价 |
| m1.5-gui-hotkey.ps1 | foreground受控hotkey selection | F | 真实交互路径；不得以helper单测替代 |
| m1.5-gui-cancel.ps1 | GUI长任务取消 | F | actualCancel控件接线；与server scheduler不同 |
| m1.5-gui-offline.ps1 | GUI Runtime/Provider故障提示 | F | 真UI/进程断连；重复控件helper可未来共享 |
| m1.5-gui-restart.ps1 | Desktop重启 / input清空 / credential | F | realprocess lifecycle；不能与task retention混为一谈 |
| m1.5-tray-probe.ps1 | tray控件/菜单诊断 | E | 探测脚本非完整日常regression，归档候选 |
| m1.5-selection-fixture.ps1 | 自有合成编辑窗口fixture | G | 跟随hotkey acceptance，不能按scenario删 |
| m1.5-scan.py | 当时source/artifact privacy扫描 | E | 先比较通用privacy-audit范围再归档；不重复维护 |
| m2b-1-windows-acceptance.ps1 | actualPairBrowser GUI proof/list/no exchange | F | 保存proof lifecycle参考；完整revoke/re-pair证据来自Browser§41 |
| m2b-1-audit.py | 当时pairing proof/build审计 | E | 通用privacy audit候选替代；不清历史证据 |
| clean-source/（包括旧scripts/real-local-smoke.ps1） | 历史源码checkout快照 | G | 不算当前测试文件，不对快照中的case再次计数 |
| 历史Chrome profile / logs / JSON / screenshots | 运行产物/evidence | G | 第三方扩展不是项目test suite；不读取secret、不清理、不作为test count |

## 6. Browser：完整文件inventory与决策表

本节文件均相对于 local-ai-assistant。正式tracked `test/` **10 文件**：3 automated、1 static、2 opt-in smoke、2 JS support、2 HTML fixtures。`package.json`只有3个 test子入口，static/real/Chrome不在npm test。

| Test file / logical suite | Current purpose / count | Category | Keep / Merge / Remove Candidate | Why / protected behavior | Duplicate with | Suggested future action | Risk if removed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| test/behavior-test.js | jsdom实际config/content；29 scenarios | B | Keep + targeted Merge | 完整DOM/Restore/Dynamic/Selection/cache/viewport/privacy | 自身cache/dynamic变体；background mapping为另一层 | 保留真实bug与时序独立case；合并同机制恢复/缓存片段，不做巨型场景 | HIGH：server不保护DOM与session |
| test/background-runtime-test.js | VM真实worker/client/storage/router；70 expanded checks | B | Keep + named matrices | pairing/trusted storage、Runtime-only、admission/poll/POST不重试/GET有界重试、frame | server安全与mapping层不同；popup UX层不同 | security rows保留；error-family/setup合并；去menu文案断言 | HIGH：不能依赖server单方面保护client |
| test/popup-behavior-test.js | 实际popup JS + HTML；5 scenarios | B | Keep 5 state paths | Restore preflight/session isolation、reopen、proof清除、storage/Forget/revoke | background仅控制状态返回；不测popup接线 | state transition为主，减少exact文案，无需压缩5→1 | HIGH：真实hotfix与secretUX |
| test/static-audit.js | 13 standalone checks | B | Keep architecture guards；E/D部分候选 | provider/runtime/credential/storage/logging boundaries | runtime fake/network checks；真实audit不同范围 | Architecture Boundary Audit +独立release hygiene；去固定0.5.0/migration旧文件扫描 | HIGH 若弱化provider/secret边界；LOW移交release格式检查 |
| test/runtime-harness.js | VM chrome mocks、random proof/credential、task/json fixtures | G | Keep | 运行真实production worker；no Runtime/Ollama/Chrome deps | embedded jsdom harness用途不同 | fixture defaults严格；给matrix行名称；不按case算 |
| test/real-runtime-smoke.js | real Runtime + real Provider + synthetic headers；可导入helper | F | Keep | 生产worker协议接到real Runtime；native isolated authority、one batch POST、中文mapping | Workspace security smoke更完整server边界；本文件client真实接线 | 保留一个跨仓库contract正路径；共享NativeSmokeRuntime/relay | MEDIUM/HIGH：唯一tracked跨repo生产client synthetic smoke |
| test/chrome-runtime-smoke.js | opt-in real headlessChrome；自然headers/storage/offline/revoke/cache/MV3 | F | Keep | Chrome自然GET、trusted storage、popup关闭长任务、restart；非headful完整证据 | Workspace R1旧脚本；ignored final§41 runner | 稳定release entry候选；解除历史标签/runner脆弱性需未来独立任务 | HIGH：jsdom/VM不代表MV3/真实Chrome |
| test/cdp-client.js | Chrome protocol连接/command/eval | G | Keep | smoke及ignored headful runner共同依赖；隐藏privatepayload日志 | Workspace R1内嵌CDP是历史复制 | 保留单个Browser CDP support；无业务case计数 |
| test/dynamic-test-page.html | Load More/dynamic/long text/footer fixture | G | Keep | 历史§41真实initial catch-up/partial/Restore使用 | embedded contentfixtures只在jsdom | 保留真实浏览器fixture，不算automated case |
| test/privacy-hotfix-page.html | nestedscroll/hidden/editable/source-replace/iframe fixture | G | Keep | B01/B03/B04/B05/B06/B10/B13真实回归载体 | jsdom privacy不能验证Chrome几何/frame | 保留generic fixture，B11/B12未修复 |
| package.json / lock | automated入口、dev-only jsdom | G | Keep | 可复现test wiring；生产extension不需build | 无 | 不为简化测试修改依赖或生产permissions | 删除破坏入口 |

Browser source/evidence链接使用稳定commit，未用远端搜索替代本地审计：[test目录](https://github.com/qianlixunbai/local-ai-assistant/tree/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/test)。

Browser 内嵌支持项归 **G**：content 的 `makeEnv/pageWithTexts/pollUntil`、geometry/checkVisibility/Range shims、message responder、pending requests/progress/log collectors；popup 的 `makePopup/deferred/waitFor/emitProgress`、真实 popup.html fixture；background 的 `check` 计数器以及 runtime-harness 的 `makeBackground/task/json`、random synthetic proof/credential 和 Chrome API mocks；real-runtime 的 `NativeSmokeRuntime/providerRelay/until/wait`；Chrome smoke 内的 fixture HTTP server、target/popup connection helpers、network-presence projection。它们不单独增加104个检查的统计。真实 Chrome 长任务前必须解除 worker debugger，普通诊断 connection 不能充作该门槛。

### 6.1 content behavior全部29 scenarios

Source: [behavior-test.js](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/test/behavior-test.js)。行号为当前基线scenario开始行；标题缩写不改变原始场景。

| # / source line / current purpose | Category / decision | Protected behavior；duplicate / future action / removal risk |
| --- | --- | --- |
| 1 L250 B13 sticky sidebar + non-bubbling scroll | A / Keep | 真实B13 bug、clip→scroll catch-up→Restore→rearm；不是只测MutationObserver helper |
| 2 L297 clipping client boxes by axis/viewport | A / Keep | x/y/ancestor clientbox/edge/display:contents；几何mock具有局限，Chrome补充而非代替 |
| 3 L321 fixed/sidebar/nav without overflow | B / Merge with #2 | 非overflow nav仍需viewport intersection，hidden也排除；合table需保留这一路径 |
| 4 L332 BR range geometry / clipped inline / dynamic | A / Keep | Range而非anchor rect，BR/aggregate source刷新；#2普通box不必然测Range |
| 5 L367 nested privacy native/fallback/selection | A / Keep | visibility fallback、opacity/aria/editable/designMode/collapsed；全文排除与explicit selection不同权限 |
| 6 L395 multi-record anchor partial / source order | A / Keep | **B01**；共享anchor成功不能隐藏失败sibling；#14不同anchor不替代 |
| 7 L425 source replacement in-flight/completed text/BR | A / Keep | **B03** childList/characterData/source identity；stale session没有source mutation不能替代 |
| 8 L460 hidden reveal / editable boundary | B / Merge targeted with #5/#7 | **B05/B06/B10**，每次rescan清visibility、aggregate text更新；不能只留静态hidden fixture |
| 9 L485 BR additions invalidate output | A / Keep | **B03** range内新增node、nested text变化；#7已有node内容变化不能完全替代 |
| 10 L510 malformed IDs cannot poison cache | A / Keep | **B09** content对message层独立validation、partial retry/cache；server/background正确不保证content正确 |
| 11 L532 full-page/main/footer/DOM/dedupe/Restore | A / Keep | 用户主路径、href/target/rel、source不变、完整清marker；可承接#18同批dedupe片段 |
| 12 L578 viewport priority vs DOM order | A / Keep | first batch可见优先、组内稳定；#1 clipping不是priority排序 |
| 13 L607 initial catch-up/cache/watch stop/rearm | A / Keep | initial mutation、singleflight、own-node无feedback、Restore后忽略，再Translate重启 |
| 14 L664 partial explicit retry / new content | A / Keep | 自动catch-up不得retry failed，新增正常翻译，用户仅重发失败records |
| 15 L710 initial stale response vs new DOM/cache/progress | A / Keep | **Restore/stale**；旧generation不能覆盖DOM/cache/currentoperation |
| 16 L742 dynamic stale after Restore vs new run | A / Keep | dynamic cleanup不得破坏新pending；可同fixture表运行initial/dynamic，保留两个时序 |
| 17 L777 content reinjection rearm | A / Keep | 新JS上下文+orphan markers；与普通Restore同context不同，不能只按rearm关键词删 |
| 18 L802 same-batch fan-out / cross-batch/cache counts | B / Merge | 同批dedupe与#11重叠；跨批、original-record count、LAT_RESET保留cache专属路径 |
| 19 L829 transport failures uncached / explicit retry | B / Merge with #14/cache matrix | reject Promise而非partial empty result，raw errors不显示/不logging仍必保留 |
| 20 L850 bounded LRU cache | A / Keep | cache容量+eviction从用户请求观测；不调用private helper，不能误标D |
| 21 L872 selection/page shared cache / card excluded | A / Keep | 双向second hit、singlecard、source不变、card不触发Observer；独立Selection scope |
| 22 L916 new selection / close wins late response | A / Keep | selection generation/card关闭后不缓存；页面session race不等价 |
| 23 L950 overlong selection / safe Runtime error | A / Keep | budget零请求、raw error/text不泄UI/log；提示具体字样可D，不删安全断言 |
| 24 L968 BR segmentation/adjacency/dynamic/Restore | A / Keep | 实际DOMsource/order/BR路径；不会证明浏览器inline BR视觉布局B11已修复 |
| 25 L1020 item/char/exact UTF8 batching | A / Keep | client partition不split/truncate；server只reject不能代替客户端计划正确 |
| 26 L1036 oversized partial / failed-only retry | B / Merge with #14/#25 | oversize记录保留完整text、局部失败，不重发成功项；保留不同failure mechanism |
| 27 L1050 public identity / Single-vs-Batch cache | A / Keep | mixed old hit/new identity失效、profile version改变、Single metadata不污染Batch |
| 28 L1072 cache cannot bypass offline/revoke | A / Keep | cached full-page与Selection仍需readiness/auth；与background状态只返回不同 |
| 29 L1087 Restore during cache readiness | A / Keep | cache-hit+miss preflight await后不submit旧miss；与已提交后stale output是两个时间窗口 |

建议仍按 `dynamic initial/catch-up`、`partial retry`、`generation races`、`source identity`、`cache`、`privacy/geometry` 保留命名子场景，不把29合成一个几十步全链路。可用共用fixture/table减少代码；能单独报错的时序应独立。#13已经接近用户建议的Dynamic lifecycle，不需要从零再写相同megascenario。

### 6.2 background-runtime全部70 checks的logical分类

Source: [background-runtime-test.js](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/test/background-runtime-test.js)。下表计数按实际循环展开，合计 **70**；每个主要check均归入一行，不把repeat label误当相同输入。

| Lines / current check purpose | Expanded count | Category / decision | Protected behavior / duplicate / future action / risk |
| --- | --- | --- | --- |
| L19 missing frame ID | 1 | A / Keep | fail closed，不回落top frame；B04 |
| L29 missing probe document ID | 1 | A / Keep | exactdocument injection；独立于frame存在 |
| L38 selection mismatch current target | 1 | A / Keep | stale context-menu text不能发给新document |
| L49 injectable tab/menu/selectedtext routing | 1 | A / Keep | 禁chrome://、仅选中text；菜单中文title精确相等为D候选 |
| L60 probe/script target same frame+document | 1 | A / Keep | CSS/JS injection锁定document；actualChrome fixture另测 |
| L82 ordinary input selection | 1 | A / Keep | explicit普通editable输入可翻译；password实测主要来自§41 |
| L89 exchange stores onlycredential/metadata | 1 | A / Keep | 不存proof、不向message返回credential |
| L91 trusted storage before first read/exchange | 1 | A / Keep | TRUSTED_CONTEXTS时序；static只看字样不能代替 |
| L92 exchange no Authorization/synthesizedheaders | 1 | A / Keep | proof交换不能借native/browser bearer；Chrome自然headers必须独立验收 |
| L94 worker restart storage reuse | 1 | A / Keep | 持久pairing survivesworker；VM模拟不宣称真实Chrome restart |
| L97 content cannot manage/read pairing | 1 | A / Keep | sender边界；static absence+Chrome storage denial互补 |
| L100 malformed proof ID / secret | 2 | B / Merge matrix | 两种格式各一代表，before network；不是server malformed credential的同对象 |
| L105 storage missing boundary / rejected boundary / write failure | 3 | A / Keep | 前两种零network，写失败已exchange→revoke guidance；不可合成一个通用storage failure |
| L109 expired/replay controlled401 | 1 | A / Keep | client safe mapping；serverTTL/replay另层 |
| L112 ambiguous exchange no replay | 1 | A / Keep | 可能已issue，不自动重复；指导检查serverclients |
| L114 local Forget no management calls | 1 | A / Keep | local forget != revoke；popup只验证button接线 |
| L115 unpaired translation no network | 1 | A / Keep | credential缺失不能降级provider |
| L116 authenticated sanitized readiness | 1 | A / Keep | 必须带browserbearer、safe projection |
| L118 offline / L121 provider unavailable / L123 revoked | 3 | A / Keep | 三种state不可互换；sameUI label不代表相同action |
| L129 onebatch task/Q-R-S；L130 redirect/cookies；L131 contract/identity | 3 | B / Merge scenario | 同happyworker可一次验证全部；auth/redirection/onePOST/assertions不能消失 |
| L145 malformed submission envelope/Location | 9 | B / Merge named table | invalidID/foreignLocation/capability/status/cloud/profileversion/prompt/result/date；全部有不同validation维度，不能只保留badJSON |
| L151 controlled task error codes | 11 | B / Merge error-family matrix | provider/model→unavailable等同展示可共fixture；每distinct result.kind及secret absence保护。表行保留时count可以仍11 |
| L155 terminal CANCELLED / TIMED_OUT | 2 | A / Keep | terminal停止poll，不能只测SUCCEEDED |
| L158 queue admission failure no resubmit | 1 | A / Keep | POST429无重试；与已接受task envelopeQUEUE_FULL不同输入路径 |
| L161 transport failure / stalledbody POSTdeadline | 2 | A / Keep | **B07迁移后等价**：body read仍有deadline、ambiguousPOST绝不重试 |
| L164 oversizedstreamedresponse no retry | 1 | A / Keep | bounded body；HTTP正常status不能绕过 |
| L171 knownGET boundedretries / L173 retryexhaustion | 2 | A / Keep | GET允许2次、POST不得重发；成功/耗尽均保留 |
| L175 overallqueued task deadline | 1 | A / Keep | 每request有deadline不等于overall截止；servertimeout不能保证client退出 |
| L178 poll ID/capability/identity/result mismatch | 4 | B / Merge named table | submissionvalidation不证明后续pollidentity保持；保留4个维度 |
| L185 duplicate/unexpected/missing/blank mapping | 1 | A / Keep | message仅valid subset；contentcache再防守属另一层 |
| L188 count/aggregatechar/UTF8/singlechar/singleUTF8/duplicate-ID | 6 | B / Merge named table | zero network/no truncation；不同admission预算不当作近似case砍 |
| L197 oversized-but-safe Single path | 2 | B / Merge representative matrix | ASCII/Unicode两预算、onePOST/noitems/id保留；不能只看字符串length |
| L199 productionlog secret/proof/input absence | 1 | A / Keep | 当前执行实际productionlog；静态regex不替代 |

这种suite已使用很多循环，重复主要在命名、fixture/setup与同error-family展示；**不是70个完全独立单测文件**。尤其9个malformed envelope和6个budget行，不建议为了数字减少删不同边界。

### 6.3 popup全部5 scenarios

Source: [popup-behavior-test.js](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/test/popup-behavior-test.js)。

| Scenario | Category / decision | Protected behavior / future action / risk |
| --- | --- | --- |
| Restore invalidates preflight + scoped progress | A / Keep | **B02/B08**；tab/topframe/operation/session隔离；background不会验证popup旧finally |
| reopened popup follows GET_STATUS operation | A / Keep | 真popup reopen时续接当前任务；exact“正在翻译...”为D候选，身份与正确progress必留 |
| exactOrigin / unpaired / proofclear / ambiguousexchange | A / Keep | proof在await前清除、一条PAIR、zeroWARMUP；exactOrigin是contract而非DOM文案 |
| storagefail + localForget guidance | B / Merge state table | storage failure不能enableTranslate；Forget不发REVOKE；具体中文片段D候选，语义“local不等于serverrevoke”应保留 |
| revoked disablesTranslate whileRuntimeonline | A / Keep | credentialinvalid != Runtimeoffline；保留action state，文字可宽化 |

未发现大量HTML顺序/CSS class断言；DOM元素ID是操作接口。安全相关disabled/clearing不可按“UI细节”删除。当前自动popup scenarios没有独立完整pair-success→ready、offline→recovery矩阵；这些由background和historical§41补充，不能声称5条已穷尽全部popup状态。

### 6.4 Static audit逐项13条

Source: [static-audit.js](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/test/static-audit.js)。

| # / current check | Category | Future action / protection / removal risk |
| --- | --- | --- |
| 1 zero legacyendpoint/provider settings | A | Keep no11434、directProvider API、concretemodel/prompt/generationownership。部分legacy identifier scan可E归档；不得删核心禁止架构 |
| 2 soleRuntimehost + exactpermissionsarray | A | Keep唯一Runtimehost和最小permission set；array顺序相等部分D，不把ordering当security |
| 3 fetchonlyruntime-client.js | B | Keep trustednetworkboundary；文件名/唯一fetch语法形状D倾向，未来assertboundary而非grep固定位置 |
| 4 contentnocredential/storage/authbridge | A | Keep；content禁unsafe storage和credential；与runtime sender/Chromeaccess互补 |
| 5 no native token / WinCred / localStorage/sessionStorage | A | Keep；native credential必须留Windows；不允许unsafe browser storage |
| 6 config no credential/provider/model ownership | A | Keep；配置层不能重新引入model/prompt/generation |
| 7 Origin/FetchMetadata never synthesized | A | Keep；自然headercontract，避免以伪造值通过测试 |
| 8 clipboardnoRead / explicitCopyOrigin only | A | Keep读取边界；现有regex对write授权只做有限扫描，不能声称穷尽所有write路径 |
| 9 controlledcounts/status logging | A | Keep禁止secret/input/result/raw error logs；regex是快速guard，非完整数据流证明 |
| 10 no trackedbuild/privateartifacts | A | Keep releasehygiene；不在runtimebehavior数量内，独立gitguard |
| 11 no personalpaths / literalbrowsercredentials | A | Keep secret/pathhygiene；pattern不能替代actualsecretstdin audit |
| 12 hardcoded version0.5.0 synchronized | E | 移交releaseversionconsistency：各来源相等，版本由release目标决定；不把永远0.5.0当长期测试 |
| 13 legacy modeltest absent + exactruntime testscript | E | 固定M2迁移撤除断言归档候选；Runtime-onlyarchitecture仍由#1/2/3/6及生产client测试保留 |

未来可形成 **Architecture Boundary Audit**（建议8–10个命名guard组）+ releaseversion/hygiene。合并guard名字不等于减少安全维度。no11434、directendpoint、concretemodel、onlyRuntimehost、secretliteral、unsafe storage、contentlogging均有持续价值。

### 6.5 ignored final acceptance / debug工具

`.verification/m2b2b-final/`当前有以下7个脚本，均没有tracked；正式§41证据使用它们，不能因为“ignored”就认定无价值。按作用分类，不复制正文或secret。

| File | Category | Purpose / proposedfuture action / risk |
| --- | --- | --- |
| acceptance.js | F | headfulWindowsGUI pairing/revoke+coreChrome/cache/offline/MV3；保留historicalsource参考，未来稳定release入口候选 |
| supplement.js | F | supplementarycomplex/Dynamic/Selection/frame/privacy/cache/re-pairhardening；历史完整门槛的主要runner之一，不能只留headlesssmoke就声称取代 |
| runtime-helper.js | G | isolated Runtime/privateauthority/providerrelay/outage/delay；跟随验收路径，不算case |
| gui.ps1 | G | actualWPF proof/list/revoke与nativeinteraction驱动；F路径依赖 |
| audit.js | F | 当前ephemeralsecret/body跨repo审计入口；与source-onlystaticguard不同 |
| extra.js | E | intermediate injected verification interactions/extendedsections；与supplement有重叠，历史片段归档候选，依赖关系需移交前核对 |
| final-extras.js | E | intermediatecache/outage/documenthardening片段；后来supplement含对应逻辑，整合后归档候选 |
| JSON/log/PNG/Chromeprofile等evidence | G | 不算测试；不删除、不读取credential或原始body来完成audit |

**可复现性限制**：clean checkout拿不到这些ignoredrunner，且当前是否能独立启动未重跑。§41历史38.231秒PASS可靠地说明当次验收成功，但不等于tracked `chrome-runtime-smoke.js`已经完整覆盖headful/native右键/跨frame/Windowsrevoke。未来发布仍必须保留这些gate的真实执行方式；本轮不把ignored脚本搬入Git。

## 7. 历史真实bug → 当前回归保护

| Historical evidence / confirmedfailure | Current protectivepaths | Verdict |
| --- | --- | --- |
| [Workspace STATUS：M1 health bug](../STATUS.md)：2026-10-02 Actuator vendorMIME造成Desktop误报malformed | D1 `HealthNegotiatesJsonWithActuatorVendorDefault` +健康/realnativeacceptance | A；不可当冗余JSON格式测试删 |
| [R1 Closing §§4–10](../milestones/M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md)：realChromeGET不带Origin，被误归native→401 | J1 OriginlessGET+failclosedmatrix、J5 credentialidentity；securitysmoke；realChrome自然headergate | A矩阵 + F自然Chrome；旧pin脚本E不意味着contractE |
| [v0.4.1 Hotfix](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/docs/V0.4.1_HOTFIX_REPORT.md) B01：同anchor成功掩盖失败/译文逆序 | content#6、#14、#24；真实privacyfixture | A；跨anchorpartialhappy路径不替代 |
| 同报告B02/B08：popup旧await/finally/progress破坏新操作 | popup#1/#2、content#15/#29、§41popupreconnect | A；session/preflight/submittedtask三种时序不同 |
| 同报告B03/B05/B06：source身份/BR范围/visibilitycache/hiddeninline | content#7/#8/#9/#5/#4 | A；源变化与Restore换代不同，不可只保留一条stale |
| 同报告B04：frame/document未锁定 | backgroundL19–82 + privacyHTML + §41真实same-origin/cross-origin/navigation/password | A + F；VM不证明真实注入权限 |
| 同报告B07/R02：完整bodydeadline与rawerror泄露 | currentbackgroundPOSTstalledbody/overalldeadline/sanitization、content#19/#23、J3adapterboundedbody | A；旧directProviderhealthdeadline路径迁移后不再恢复，仅保留当前Runtime等价边界 |
| 同报告B09：Number(id)/duplicate/unexpected宽松mapping | J1batchmapping、backgroundL185、content#10 | 三层A；Provider→Runtime、HTTP→client、message→DOM不同信任入口，不能按同bug删两层 |
| 同报告B10/B13：editable/privacy与sidebar/nestedscroll遗漏 | content#1–5/#8/#12、Selectionrouting、realprivacyfixture | A + F；包括privacy拒绝与正确可读内容 |
| [M2B-2A](../milestones/M2B-2A-CLOSING-REPORT.md)：新增structuredmapping/onebatch-oneinference/partial高风险contract | J1batchinput/output/onegeneration + J2structured + client/DOMmapping | A；新增contract已证实验收，未逐项声称全部输入曾真实出bug |
| [Browser§41](https://github.com/qianlixunbai/local-ai-assistant/blob/b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0/docs/M2B-2B-RUNTIME-MIGRATION-REPORT.md#41-final-closing--real-chrome-acceptance--2026-10-03)：actualGUIproof/revoke/re-pair、partialretry、MV3longtask | trackedChromesmoke部分 + ignoredfinalrunner全门槛；fake套件补充 | F必须保留；35srelaydelay+realinference，**38,231ms**，popup关闭，workerdebugger提交前detach |

§41明确最终没有新的productcodefix，曾修runner encoding、modal lookup、fixture anchors、retrybatchcount、clickcoordinates、stalepopup/targetlifetime；不能把这些runner失败写成产品bug。它还明确使用等价complexguide而非本轮真实MDN网站；v0.4.1用户确认的MDN历史PASS是另一次证据。

[M2 Final Closing](../milestones/M2-CLOSING-REPORT.md)最终状态优先于Browser§1–40旧PARTIAL；M2A、M2B-1、M2B-2A、R1历史report仅在当时范围内引用。B11 inlineBRlayout / B12 debouncestarvation仍 **DEFERRED**，任何currentgreen不能被解释为它们已修复。

## 8. 重复层分析与建议覆盖归属

| Behavior / repeatedlayers | 确認重复或互补 | Futureminimum / simplificationrule |
| --- | --- | --- |
| credential格式：J5→J1→securitysmoke→Chrome；Desktopproof另对象 | J5 verifier/registry、J1 HTTPclassification、Chrome自然headers是互补。J1/browser-security-smoke中大量同拒绝组合才是候选重复 | 少量credentialunitmalformed代表 +完整serversecuritymatrix + Chrome关键链路；syntheticsmoke不必复制全排列 |
| scheduler：J2 String/structured×4→J6每能力cancel→J1batchrealHTTPcancel | J2两输出形状完整TTL/timeout重复较明显；J6prompt测试混scheduler；J1真实adaptercancel为独立接线 | 共享TaskManagerlifecycle一次 +structuredcompatible代表 +一条HTTPcancel。各capability不重跑整套scheduler |
| genericerrors：J3→J1每capability多个mode→D1→D2两能力→Browserclient | 不同语言/客户端mapper独立；J1内每能力复制provider模式、D2同client复制错误最值得减 | adaptererror矩阵一份；serverAPI通用失败projection一份；每客户端自己的mapper一份；每capability请求/profile/output/policy |
| Nativehealth：APIpublichealth→D1healthauthseparation→D1vendorMIME→realnative | credentialfreehealth与vendormedia有不同根因 | 保留M1vendorregression，能共setup但不能去关键Accept检查 |
| Batchmapping：Runtimeproviderparser→backgroundHTTPmapping→contentmessage/cache | **不确认可整层删除**：不同信任入口/副作用 | Runtime完整strictmappingtable；client少量malicioussubset代表；contentcachepoison+explicitretry完整scenario |
| Batchbudgets：serveradmission→backgroundzero-network→contentpartition | serverreject、clientvalidation、recordpacking是不同外部行为 | server边界表 + clientzero-network表 +contentno-split/no-truncate partition，不在每层复制全部JSON字面变体 |
| Restore/stale：contentinitial→dynamic→Selection→popuppreflight→cachereadiness→Chrome | 不同generation/cancellation窗口；不是六次同一helper | tablefixture共用，保留各race名字；Chrome验收关键路径，不替代自动时序控制 |
| WinCred/serverACL/UIsecret/prooftrustedstorage | OSvault、fileACL、WPFdisplay、extensionstorage四个不同边界 | 全部至少一条actual或behavior路径，不能以server安全为由删除Desktop/Browser |
| static sourcegrep→executedclientlogs→actualsecretartifactaudit | lexicalguard、运行输出、实际secret扫描互补 | 保留architectureguard与运行redaction，actualaudit作为F而非automated数量 |

### BrowserSecurityMatrixTest未来设计（本轮未实现）

每行独立记录：`caseName / credentialType / credentialState / Origin presence+value / FetchMetadata / method / route / capability / owner / expectedStatus / expectedCode / expectedCORS / cacheControl`。不要一次做所有维度全笛卡尔积；用每个独立deny分支+已知可绕过组合。至少覆盖：

1. native无Origin成功；native+extensionOrigin拒绝；browserbearer不能冒充native；missing/malformed/wrongsecret/unknown/revoked拒绝。
2. exactregisteredOrigin成功；otherpaired/unknown/web/wildcard/null/emptyOrigin拒绝；exchange只approvedproof、noAuthorization、no-store/single-use。
3. Origin-less只允许registeredbrowser+GETreadiness或ownedUUIDtask；自然无Origin响应不合成CORS；POST/DELETE/HEAD/OPTIONS拒绝。
4. Site / Mode / Dest逐项missing/invalid，另有Origin-present路径代表；none/cors/empty全齐才可admit。
5. capabilityTranslate-only、admin/provider/Ask/Summarize/unknown/trailingslash/extraUUIDpathdeny。
6. GET/DELETE wrongbrowserowner/nativeowner双方均404，body与不存在一致；QUEUE/RUNNING/retained在manager；HTTP接线至少一个代表。
7. exactpreflight/safeheaders、allowOrigin无wildcard；revoke后Originpresent/absent两分支均拒绝；revoke不取消alreadyacceptedtask。

每security维度单独命名，table失败输出caseName而不输出credential/body。不能用一条realChromehappypath替代自动failclosedmatrix。

## 9. 测试成本（LOW / MEDIUM / HIGH）

Runtime列为预计执行成本等级，括号仅在本轮实测时列出；flake/maintenance是结构风险判断，无重复run统计，所以不是已测概率。Diagnostic HIGH表示定位质量好，其他HIGH表示成本/风险/价值高。

| Suite | Runtime | Externaldependencies | Flakinessrisk | Maintenancecost | Diagnosticquality | Behaviorcoverage | Securityvalue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RuntimeApi J1 | MEDIUM (5.156s) | Spring/JVM/loopbackHTTP，fakeProvider，无Ollama | MEDIUM：串行共享state、临时端口、poll/latch | HIGH：大单方法多领域 | LOW：前部failure截断后续，loop行名少 | HIGH | HIGH |
| TaskManager J2 | LOW (1.316s) | JVMthreads/timers | MEDIUM：60/150ms、TTLsleep、release同步 | MEDIUM | MEDIUM | HIGH(sharedlifecycle) | HIGH(owner/terminal/redaction) |
| OllamaProvider J3 | LOW (0.369s) | real loopbackHttpServer，无模型 | MEDIUM：100/300msdeadline | LOW/MEDIUM | HIGH(method分明；malformedloop定位一般) | HIGH(adapter) | HIGH(egress/redirect/bound) |
| BrowserClients J5 | LOW (0.190s) | OSfilesystem/ACL/lock | LOW/MEDIUM：平台ACL，mutableclock无需sleep | MEDIUM | HIGH | HIGH(registry) | HIGH |
| ProfilePolicy/TextCapabilities | LOW (0.012s combinedsuiteelapsed) | fakeProvider/JVM；J6有latch | LOW/MEDIUM | LOW | HIGH/MEDIUM | MEDIUM(capabilitypolicy) | HIGH |
| DesktopRuntimeClient/AssistantCapabilities | LOW | .NET10/Windows+mockHttp，无模型 | LOW | MEDIUM(共享error重复) | HIGH；embeddedtables部分MEDIUM | HIGH(clientcontract) | HIGH |
| DesktopBrowserPairing | LOW/MEDIUM | Windows/WPF/STA+mockHttp | MEDIUM(staticexpires/time/dispatcher) | MEDIUM/HIGH | HIGH(namedmethod)，malformedloopsMEDIUM | HIGH(secretUX) | HIGH |
| WinCred/UIA/helper/nativehotkey/clipboard/singleinstance | LOW/MEDIUM | actualWindowsAPIs、WPF/helper/PowerShell、隔离targets | MEDIUM(OS+nativewindows+deadlines) | MEDIUM | HIGH | HIGH(nativeboundary) | HIGH |
| Browsercontentbehavior | MEDIUM（占npm总25.86s主要等待；独立耗时UNVERIFIED） | Node/jsdom，geometryshim，无Chrome | MEDIUM(750msdebounce/3spoll) | HIGH(1109行fixture+scenario) | MEDIUM(命名scenario明确、内部步骤较长) | HIGH | HIGH(privacy/cache/stale) |
| Browserbackground | LOW（独立耗时UNVERIFIED） | Node/VM/WebResponse/ChromeAPIstub | LOW/MEDIUM(8/10/50msdeadline) | MEDIUM | MEDIUM：重复checklabel、failurecatch隐藏细节 | HIGH(protocol/frame/storage) | HIGH |
| Browserpopup | LOW（独立耗时UNVERIFIED） | Node/jsdom+mockChrome | LOW/MEDIUM(初始化poll) | MEDIUM(文案耦合) | HIGH(two namedraces)+MEDIUM(pairingpieces) | MEDIUM(5状态路径) | HIGH |
| Browserstatic | LOW (0.14s) | Node/Git/sourcefiles | LOW | MEDIUM(regex/hardcodedrelease) | HIGH(13namedguard) | LOW(runtimebehavior) | HIGH(boundaryguard，非完整证明) |
| real-local/securitySingle+Batch/syntheticreal-runtime | HIGH；实际秒数UNVERIFIED | packagedRuntime/Java/realOllama/model/ports；Python或Node | HIGH(modeloutput/time/port/processcleanup) | MEDIUM | MEDIUM(controlledmessages+metadata) | HIGH(real接线)，非Chrome | HIGH(restart/secret/ownership) |
| Chrome/headfulGUI/MV3acceptance | HIGH；本轮UNVERIFIED，历史longtask38.231s | realChrome/CDP/WindowsGUI/Runtime/Provider/relay/fixtures | HIGH(targetlifetime/version/coordinates/model/30–45s窗口) | HIGH | MEDIUM(phaseevidence)，debuggers影响需区分 | HIGH(realbrowser) | HIGH |
| privacyartifactaudit | MEDIUM/HIGH；本轮UNVERIFIED | Python/Git/filesystem/archive+stdinsecret | MEDIUM(filesharing/staleoutputs) | MEDIUM | HIGH(counts/matchedfilelabels，无secret) | MEDIUM(releaseprivacy) | HIGH |

当前三套自动回归总共只需几十秒量级；Balanced的第一收益应是可读性、定位质量和维护量。将矩阵table行合入一个scenario会降低“打印PASS条数”，未必降低计算时间或实际输入数量。无实测flake率、无前后benchmark，不能承诺提速百分比。

## 10. Audit发现：仅记录，不修复

| Finding / source | Classification | Observation / limitation | Futureaction |
| --- | --- | --- | --- |
| R1chrome-get脚本L16/L257–270 | E；确认工具staleness | 强制ExtensionHEAD=`ddfa4a02cace8480bdef78862a907e48a65c6b7c`且branch=`m2b2b-runtime-migration`；当前main/b4c3a71不满足。另锁Chrome154 | 归档迁移入口或未来另任务提取naturalGETgate；本轮不改pin、不checkout旧branch |
| RuntimeApi单@Test | B；结构诊断缺陷 | 一个failure会跳过后续security/batchchecks；count=1掩盖大量matrix | futureisolation分suite/parameterrows；允许报告testcount增加 |
| RuntimeApi Origin-presentmetadata loop | B；测试精度限制 | site变化时request缺Mode/Dest，因此不能单独证明wrongsite是拒绝原因；Origin-lessGET已有逐维度测试，同productionhelper | futurematrix每次只变一个header；不能声称当前Origin-present全独立matrix |
| RuntimeApi temporaryport | G；flake风险 | 先开ServerSocket取port再close，之后mockbind；窗口存在port竞争 | futurefixture直接绑定port0可考虑；本轮不修 |
| TaskManager timeout/retention参数化 | B；flake/重复 | 60/150ms和sleep450ms靠wallclock；late-result断言部分在release后立即读取，没有统一“latework完成后”barrier | 将explicitcompletionbarrier/controlledclock列未来测试质量建议；本轮PASS不证明所有race排列 |
| Desktop BrowserPairing staticExpires | G；时间fixture风险 | type初始化后固定UtcNow+3min；长时间暂停/慢runner可能过期 | future每casefreshclock/fixture；当前runPASS，未复现failure |
| RuntimeApi readinessJSON相等 / staticpermissionsJSON相等 | D；表示耦合 | JSON属性/array顺序变化会失败，未必改变合同/权限 | futureassertsafeproperties/set；secretabsence和permission集合仍A |
| Browserbackground重复label + catchdetailsuppressed | B；定位不足 | malformed9、budget6等行failure打印同label；安全地隐藏privatebody也隐藏case定位 | futurelabelcase维度，不能输出secret/rawresponse |
| staticauditregex | B；证明范围有限 | 固定词和`fetch(`形状可被格式/别名/新API绕过；permissionarray顺序、硬版本增加falsepositive | 保留快速guard，未来从architectureboundary判断；不把13PASS当完整数据流安全审计 |
| chrome-runtime-smoke result/limits | F；验收范围标签 | successfulrun仍写PARTIAL；headless/nativeauthority不测完整WindowsGUI/native右键；严格30–45s窗口还包含真实modeltime | 列releasegate的限定；不能用script名称冒充§41完整headfulPASS |
| security-smoke `-Batch`取消 | F；有意弱断言 | accepted immediatecancel允许CANCELLED或SUCCEEDED；不是确定RUNNINGcancel证明 | 保留J1slowHTTP/J2确定cancel，不移除后说smoke已覆盖 |
| real-runtime relay/helpers | G；tooling范围 | real-runtime-smoke同时export执行helpers；Chromesmoke依赖它；relay采用realProvider并非product | 不能整文件标temporary删除；future可整理职责但本轮不改 |
| ignoredfinalrunner / intermediateextra | F/E；可复现性 | source依赖localpaths/environment/UI工具，cleancheckout不存在；历史runner误判已见§41 | 优先未来稳定验收入口，保留历史evidence；可运行性UNVERIFIED |

没有在本轮自动运行中观察到失败。以上“风险/限制”不是新确认的生产bug，也不授权修测试；确认为stale的工具与结构问题照实记录。

## 11. 三档未来方案（仅规划）

### Plan 1 — Conservative

最稳。保留所有正式executables与独立安全/behavior场景，先处理明确E/D：R1固定commit/branch入口历史归档、static#12版本移交releaseconsistency、#13旧modeltest撤除扫描归档、Desktop/Popup局部文案/JSONordering断言。历史scanner/extra片段只登记归档，不清除证据；被消费的Ghelper不删。

真实回归数量大体保持Java30、Desktop约63–67、Browser约101–104；static长期guard可由13变11（release一致性另入口）。这些是规划估算，不是强制目标。收益主要减少发布时固定版本/文案维护；速度几乎不变。没有整文件formaltests的无条件删除名单。

### Plan 2 — Balanced（推荐）

1. **先同层generic重复**：D2 `SharedErrorsAndMalformedResponsesRemainControlled`将共用错误移交D1，保留capability-specificprompt/outputlimit；D2pendingPOSTcancel与D1统一action表；J6只保留prompt/profile/policy/redaction，不重复两能力scheduler全语义。
2. **共享scheduler集中**：J2核心queue/owner/cancel/timeout/retention各一次，保留String/structured代表终态+immutable/redaction，不完整双跑四组。J1realHTTPbatchcancel与revokeacceptedtask仍保留。
3. **serverAPI重组**：明确nativecapability、Browsersecuritymatrix、batchmapping。J1每capability重复provider模式减为sharedprojectionmatrix +各能力valid/schema/policy；J3protocolparser/realHTTPdeadline及egress不删除。J5全部高风险registryfailure保留。
4. **Desktoppairing整理**：malformedproof响应两个方法合namedtable；SecurityError专属phase保留，generictransport共享；replacement成功/失败/close可共fixture，但pendingimmediate-clear和lateclose时序继续单独可定位。
5. **Browsercontent小范围整合**：#3并入geometrymatrix；#8复用privacy/sourceidentityfixture；#18同批dedupe部分由#11承接但crossbatch/LAT_RESET/recordcount留cache场景；#19/#26与partial/budget使用共同fixture、独立failure名称。#7/#9/#15/#16/#22/#29等真实race不压成单场景。
6. **background保留安全维度**：namedsubmission/poll/budget/errorfamilytables、减少重复happysetup/check包装与menu文案；不同storagefailure/POSTambiguity/GETretry/frame路径不删。Popup5个statepath保留，减少exact文字。
7. **static/acceptance分流**：长期ArchitectureBoundaryAudit与releasehygiene；F退出日常test数字而保留发布入口。先稳定realChrome/headfulgate复现，才考虑归档旧R1/中间extra工具。

风险 **LOW/MEDIUM（条件成立时）**：替代suite尚未存在，所有C/merge删除须等新路径运行且故障类别逐项对照。最大风险是merge时漏掉时序观察点或把input/output预算当近似样例；gate是下节CoreRegressionSet，不是目标casecount。

### Plan 3 — Aggressive（不推荐）

进一步依赖J1integration与FrealChromeacceptance，移除较多service-levelwiring、client输入变体、独立geometry/DOM片段，Desktop减少mockcontract变体而依赖GUIgate。即使如此，native/browserseparation、serverfailclosedmatrix、owner、batchstrictmapping、共享scheduler、clientPOSTambiguity、privacy/Restore/generation、Windowscredential仍不能删。

风险 **HIGH**：真实Chrome只测happy/关键路径，无法覆盖所有failclosed；ignoredheadfulrunner还不能从cleancheckout复现；longtask/model/OSflakiness会降低日常定位，单大integrationfailure遮蔽多个gate。模型“当前正确输出”也不能替代maliciousmapping/secretfailure注入。预期正式case包装可能降到Java约20–26、Desktop约35–45、Browser约65–80，但**当前无证据证明这样保留同等故障检测**；不是approved安全目标，不默认实施。

## 12. Balanced预计结果与统计陷阱

| Repository / metric | Currentverified | Balancedestimate | 含义与限制 |
| --- | --- | --- | --- |
| WorkspaceJava expandedinvocations（沿用当前单API@Test包装） | 30 | **约24–30** | 核心减少J2outputshape完整复制/J6重复scheduler/J3classification碎片；API内部矩阵变少并不反映invocation数 |
| WorkspaceJava若把API命名拆分改善diagnostics | 30 | **约28–40** | 可能增加测试数，同时减少重复请求/维护代码；不能为了24这个数字拒绝更好的failure定位 |
| WorkspaceDesktopexpandedcases | 67 | **约50–58** | D1约16–18、D2约5–7、D3约16–20、Windowsboundary其余约13；粗略范围，具体matrix行保留会影响数字 |
| WorkspaceSmoke/tooling | 5 trackedscripts +ignoredhelpers | **核心4个tracked脚本保留，1个R1入口E候选** | real-local/security/privacy/relay继续；不按case缩为零，先保留Chrome自然headergatereplacement |
| Browserprintedautomatedcheck/scenario count | 104 =29+70+5 | **约88–97** =content23–26 +background60–66 +popup5 | 以减少重复check包装/合并scenario计；独立输入矩阵维度保持，非砍malformed安全行quota |
| Browser若table每个输入行继续打印PASS | 104 | **更可能约98–101** | content少量合并，70个backgrounddistinctrows大多保留；这是更可比较的故障输入数口径 |
| Browserstaticnamedguards | 13 | **约8–10长期guard组 +releaseconsistency/hygiene** | 多个predicate仍在，#12/#13固定migration移交；不是把securitydimensions从13砍到8 |
| BrowsertrackedF/Gfiles | 2smokes +2JSsupport +2HTMLfixtures | **全部保留** | futureacceptance可整理，但不能把工具/fixture计入104再声称缩减 |

范围是审计判断，不是benchmark或已实现结果；没有机械“Java30必须压到20”或“70securitychecks砍一半”的依据。50–58 /88–97若保留原每行报告方式达不到，接受较高数字。正式追踪应同时记录 **namedscenarios、matrixinputrows、真实testexecutions、失败诊断、维护文件/代码量**。

## 13. CORE REGRESSION SET — 未来最低安全线

以下按logicalsuite定义“少而强”，不是每suite只剩一个happycase。A行为可以merge，但至少一个高质量自动路径必须存在；F真实验收不能替代failclosed自动矩阵。

### Workspace

| Corelogicalsuite | Minimumcontract | Currentanchors |
| --- | --- | --- |
| W-Core1 TaskManagerlifecycle / ownership | QUEUED/RUNNING/所有terminal、queuefull、queued/runningcancel、distincttimeouts、lateoutput、retention/expiry、ownerGET/cancel404、sanitizedfailure、structuredcompatibility/immutability | J2；一条J1realHTTPcancel接线 |
| W-Core2 Nativecapabilitycontracts | nativeauth、Translate/Summarize/Ask各validresult、requestschema/budget、profile/prompt/outputownership、LOCAL_ONLY、health与Provideravailability分离 | J1/J6/J4；Freal-local三能力 |
| W-Core3 Browsersecuritymatrix | exactOrigin / registeredcredential / native-browserisolation / OriginlessChromeGETallowlist / FetchMetadatafailclosed / capability/routes / CORS/no-store / revokedstate | J1/J5；FChrome自然headers |
| W-Core4 Browserregistry / pairingrevoke | explicitapproval/single-use/TTL/replay/budgets、listnoconfidentialfields、restartpersistence/sessionloss、atomicfailure/noreviver、ACL/lock、revoke不抢占已接受task | J5/J1；Fsecurityrestart/realGUIre-pair |
| W-Core5 Batchcontract | multi-item/reorder、duplicate/unexpected/missingpartial、malformedtop-level/item、raw/serialized/UTF8/outputbudgets、onebatch=onetask=oneproviderexecution；noautomaticitemretry | J1batchcontract；Frelay真实count |
| W-Core6 Providerboundary | loopback/noarbitraryegress、finalLOCAL_ONLY、adapterparser/redirect/bodylimit、realHTTPtimeout/cancel、safeerrors | J3/J4 |
| W-Core7 Windowscredential / inputboundary | isolatedactualWinCred/importACL、selected-onlyUIA/password/untrusted、boundedhelpercleanup、clipboardfreshness/owner/restoration、hotkey/singleinstance | D4–D7；fake与native组合 |
| W-Core8 Desktopclient / pairingUX | sharedRuntimeClientenvelope/HTTPvsacceptederror、pendingPOSTcancel、capabilityidentity、healthvendorMIME、nativeapproval/list/revoke、proofclearbeforeawait/replace/close、lateproofdiscard、failedrevoke不消list | D1–D3/D6；FGUIreleasegate |

### Browser

| Corelogicalsuite | Minimumcontract | Currentanchors |
| --- | --- | --- |
| B-Core1 Runtime/security/pairing | TRUSTED_CONTEXTSbeforeaccess、contentnomanage/readcredential、explicitproof/noautorreplay、Forget!=revoke、missing/revoked/offline/unavailable区别、Runtime-only/noProviderfallback/noheadermanufacture | background+static；FrealChromeaccessdenial |
| B-Core2 Runtimeprotocol / batch | onebatchPOST/task、strictLocation/envelope/identity、boundedbody/request/overalldeadline、terminalstop、POSTambiguous不重试、knownGETboundedretry、mappingpartial、clientbudgets/Single完整record | background；real-runtime/Chrome正路径 |
| B-Core3 FullDOM/Viewport/Restore | main/footer/links/sourceidentity、viewportfirst、sidebar/nestedscroll/clipping、BRRange、cleanup/watchstop/rearm、source替换invalidate | content#1–12/#24；privacyfixture+realChrome |
| B-Core4 Dynamic/partial/generation | initialcatch-up、singleflight、ownmutations无loop、partial仅显式retryfailed、sourceaddition、initial/dynamicstale、reinjection、新generation不被oldfinally干扰 | content#6–9/#13–17/#29 |
| B-Core5 Selection/frame/privacy | exacttab/frame/document/nofallback、ordinaryeditableexplicitselection、password/protecteddeny、全文hidden/editable/designMode排除、selectioncardindependentgeneration/close、nobody/logleak | backgroundrouting、content#5/#8/#21–23、F真实frame/password/navigation |
| B-Core6 Cacheidentity/secondhit | normalizedtext+targetLanguage+publicprofile/version+prompt、Single/Batchisolation、boundedLRU、fullpage/Selectionsecondhit、malformed/staleresult不poison、offline/revoke不bypass | content#10/#18–22/#27–29 |
| B-Core7 Popupstate/securityactions | Restorepreflightcancel、scopedprogress/reopen、proofimmediateclear、pairingfailure/Forget/revoked按钮state、Runtimeonline但credentialinvalid | popup5；F真实popupreopen/re-pair |
| B-Core8 Architecture/releaseprivacy | no11434/directProvider/concretemodel、soleRuntimepermission、nocredentialliteral/unsafecontentstorage/nativecredential、controlledlogging、noartifactsecrets | staticlongtermguards + actualstdinartifactauditF |
| B-Core9 RealChrome/MV3acceptance **F** | 自然OriginlessGET、actualstorageboundary、headfulproof/revoke/re-pair、真实右键/frame/privacy、cache/offline、popup关闭且workerdebuggerdetach的30–45srealinference、restart | trackedChromesmoke部分 + §41ignoredrunner完整；cleancheckout全gate复现仍UNVERIFIED |

B11/B12仍是deferredlimitations，不新增“已修复”的corecase承诺；保留现有BR/Dynamic回归，不能用它们的green作为deferredbug已解决的证明。

## 14. 第一轮实际删减的建议顺序与退出条件

**RECOMMENDED PLAN: Balanced.** 下一轮若单独授权实施，第一批从低风险/同层重复开始：

1. `test/static-audit.js` #12/#13固定版本/migration扫描；把长期architectureguards与releaseconsistency分开。WorkspaceR1旧pinChrome工具只登记归档候选，**先确保未来naturalGETreleasegate可复现**。
2. Desktop `AssistantCapabilitiesTests.cs` sharederrors / pendingcancel，与 `RuntimeClientTests.cs`共享；独有capability/prompt/outputbudget/wrongaction必须先迁入可review测试。
3. Desktop `BrowserPairingTests.cs` malformedproof重复setup、genericerror重复、局部exactstatuslabel；绝不先删immediate-clear/close-late/revoke-confirmation。
4. Java `TextCapabilitiesTest`混入的共享cancel与 `TaskManagerTest`四组boolean整套复制；保留J2全部lifecycle维度与structured代表，再审J1genericProvider模式。
5. Browsercontent#3/#8/#18/#19/#26的小范围fixture/scenario整合，backgroundnamedtable与安全casefailurelabel；Popup以stateaction为主。源身份、race、privacy矩阵最后审，默认保留。

在任何future删除前，提交应给出 **oldcase→replacementpath→同一fault能被捕获的理由**，并重跑受影响自动suite；涉及securityheaders/storage/MV3/真实frame/Windowscredential的release变更仍需对应Fgate。建议一批一个领域，以可独立定位的case/report为review单位，不以“目标数字达标”为完成条件。

本轮停在报告。Java30 / Desktop67 / Browser104 / static13当前源文件全部保留，历史Closing与evidence不改，不实施任何删减，不创建测试/产品实现commit。

## 15. 最终交付完整性核验

- Workspace 仍在 `main`，HEAD 仍为 `6aa76b4f9c58bcbcec3c89d42f95aa595cff62f2`；唯一新增文件是本报告（未提交）。已有 tracked 文件无 diff。
- Browser 仍在 `main`，HEAD 仍为 `b4c3a71ea7e85b8aee9fa779ad38bf448d5d47a0`，working tree clean，tracked 文件无 diff。
- 本报告的相对本地文件链接全部存在；Java6套件 invocation 总数30、Desktop7文件 expanded cases总数67、Browser29/70/5与独立13 static口径已核对。
- Git tracked diff whitespace check 和本报告单文件 whitespace check 通过。没有创建 branch / commit / push，没有删除 ignored 历史工具或验收产物。

## Implementation Batch 1 Result

本节追加实现记录；以上原审计结论保持不变。删除前已核对下表的共享调用路径；没有替代证明的边界保留。

| Removed / merged old case | Replacement path | Why same fault is still caught |
| --- | --- | --- |
| D2 pending POST cancel：Summarize / Ask ×2 | D1 `CancelWhilePostPendingWaitsForIdentityThenDeletesAcceptedTask`；D2 submit / polling identity | `AssistantOperation` 在取得 ID 后的 cancel 分支没有 action 分叉；同一 capability 变量同时用于 GET / DELETE，二者共用 `TaskRequest` / `ParseTask`。保留每能力请求与结果身份，完整 pending lifecycle 只证明一次。 |
| D2 shared HTTP errors：401 / provider / model / queue，每能力各一组 | D1 `HttpErrorsAreCategorizedAndRawBodyTokenNeverEnterException`，改由 `AssistantOperation.RunAsync` 触发 POST | 与旧 case 同经 `RunAsync` → capability submit → `SubmitTaskAsync` → `SendAsync`；继续逐 code 验证 DesktopError、body/token redaction 与无 inner exception。 |
| D2 offline ×2 | D1 `OfflineIsControlledAndCredentialMissingNeverSendsHttp`，offline 经 `RunAsync` | 同一个 `SendAsync` 的 HttpRequestException mapper；RunAsync 不得吞掉或改写错误。 |
| D2 generic malformed JSON / CLOUD locality ×2 | D1 `MalformedUnknownDuplicateMismatchedAndOversizeResponsesFailClosed` | 同一 JSON / duplicate parser 与 `ParseTask` locality guard，无 action-specific 分支；D2 保留独有 identity / prompt / output 校验。 |
| D2 empty prompt / 8193-byte result ×2 | D2 `CapabilityResponsesRejectInvalidIdentityPromptAndOutput` named rows | 原输入继续存在；另区分 Summarize 4096-byte 与 Ask 8192-byte 上限，覆盖 UTF8 预算及 wrong submit capability / profile。 |
| D2 repeated QUEUED → RUNNING → SUCCEEDED progress ×2 | D1 `SubmitPollAndSuccessFollowM0ContractWithoutModelOrBodyInDiagnostics`；D2 `SubmissionAndPollingUseCapabilityRouteBodyAndResultIdentity` 与 wrong-capability polling | 共享 loop/progress 仍精确断言状态序列；每能力仍经过 RunAsync，验证 route/body/profile、成功轮询的 ID/status/result，polling 错身份不得展示。 |
| D3 unauthorized HTTP row | D1 HTTP 401 row；D3 `FailedReplacementClearsPreviousProofAndRevokeOnlyRemovesConfirmedClient` | 401 在共享 `SendAsync` 中先于 security mapper 处理；pairing 实际 POST 401 仍验证旧 proof 清空、敏感值不进状态、控件恢复。其他 pairing-specific HTTP rows 全部保留。 |
| D3 missing credential subcase | D1 credential-missing no-send；D3 native authenticated pairing contract | 同一 `authenticate=true` credential gate；缺失 credential 必须在进入 handler 前失败。D3 仍保留 pairing offline redaction 与 caller cancellation wiring。 |
| D3 `InvalidPairingResponseFailsClosed` ×4 + `ProofShapeExpiryExtraFieldsAndResponseLimitsAreValidated` 的4个内部输入 | D3 `MalformedPairingResponsesFailClosed` named table | 原8个输入逐一保留并标名；补独立 malformed JSON、null root、wrong-type ID、invalid ID / duplicate key。invalid expiry 保留；expired proof 的显示边界仍由原 WPF expiry test 独立保护。 |
| D3 English status fragments | 原 WPF tests 中的 hidden-proof、copy / create / revoke 状态、client retention/removal、safe nonempty status | 不绑定英语句子；失败不得残留 proof，敏感值不得进状态，failed revoke 保留 client，confirmed revoke 删除。 |

**Result: GO.** 基线经 fetch 确认为 `main = origin/main = 6aa76b4f9c58bcbcec3c89d42f95aa595cff62f2`；起始唯一未跟踪文件是本报告。分支 `post-m2-test-simplification-1`，先独立提交原审计，再提交测试简化；不 merge / push，不启动 Batch 2 / M3。

| Desktop suite | Methods before → after | Expanded cases before → after |
| --- | --- | --- |
| RuntimeClientTests | 9 → 10 | 18 → 20 |
| AssistantCapabilitiesTests | 5 → 4 | 9 → 7 |
| BrowserPairingTests | 13 → 12 | 27 → 22 |
| Windows boundary (4 files) | 13 → 13 | 13 → 13 |
| Total (7 files) | **40 → 39** | **67 → 62** |

- Baseline **67 PASS / 0 failed / 0 skipped**，runner **941 ms**；after **62 PASS / 0 failed / 0 skipped**，runner **954 ms**。基线命令含 build，工具 wall time 2.776 s；after `--no-build --no-restore` 命令 wall time 2.224 s，含额外 TRX logger。执行方式不同且只有单次采样，不据此宣称提速。
- 命名 pairing malformed table 将原5个 runner cases 合并为1个方法，实际输入 **8 → 14**，原8个不删；capability identity/prompt/output named table 每能力6行。收益是减少 generic matrix / cancel setup 重复、明确 caseName 与解除英语文案耦合，不是减少安全输入。
- W-Core8 全保留：HTTP/accepted envelope errors 与 redaction；pending POST cancel；capability identity / budgets；health vendor MIME；native approval / safe list / DELETE 204；proof clear-before-await / replacement / close / late discard；failed revoke retention。五个指定 WPF UX 方法均在 TRX 中 PASS。新增确定性 client-timeout / IO mapper rows；未声称测试真实8秒 deadline。
- W-Core7 的 `CredentialTests`、`HelperProcessTests`、`SelectionAndLifecycleTests`、`UiaTests` 与基线完全无 diff，13 cases 全 PASS；production / Core / Java / Browser 均未修改。反射属性安全断言及 pairing 专用403/500分支保留。
- `dotnet restore`、`dotnet build --no-restore`（0 warning/error）、`dotnet test --no-build --no-restore --logger "console;verbosity=minimal"`（额外 TRX logger）、`git diff --check` 均 PASS。实现只改3个授权测试文件与本追加节；其他审计建议 DEFERRED，须另行授权。

## Implementation Batch 2 Result

**POST-M2 TEST SIMPLIFICATION — BATCH 2 REPORT**

Date: **2026-10-03 (Asia/Shanghai)**. Repository: **qianlixunbai/personal-ai-workspace**. 本节追加 Batch 2 实测结果，以上原审计与 Batch 1 历史证据保持不变。

### 1. Result

**GO — Balanced Batch 2 完成。** 只重组 Java tests 并追加本报告。Java **37 PASS**，Desktop **62 PASS**；production / Desktop / Browser 零修改；W-Core1–W-Core6 保留；所有删除的重复执行都有下方 replacement；diff whitespace check PASS。

### 2. Git

Reality check 依次执行 `git status`、`git branch --show-current`、`git rev-parse HEAD`、`git fetch origin`、`git rev-parse origin/main`、`git log -10 --oneline --decorate`。起始 working tree clean，`main = HEAD = fetched origin/main = 61c2936c71c7408eccb9a6e29fc28db869572680`。

工作分支：`post-m2-test-simplification-2`。本地提交标题：`test: simplify runtime regression suite`；交付 SHA 见最终回复。`main` / `origin/main` 保持基线；不 merge / push。未发现适用的 AGENTS.md；memory registry 无相关命中。

### 3. Baseline

本轮实际执行 `.\mvnw.cmd test -q`：**30 invocations，0 failures，0 errors，0 skipped，wall 12.18 s**。以下是 Surefire suite time，均不含完整 Maven 启动时间。

| Suite | Baseline invocations | Baseline time | After clean verify invocations | After time |
| --- | ---: | ---: | ---: | ---: |
| RuntimeApiTest | 1 | 5.282 s | 12 | 5.914 s |
| TaskManagerTest | 10 | 1.342 s | 6 | 2.056 s |
| TextCapabilitiesTest | 3 | 0.006 s | 3 | 0.023 s |
| OllamaProviderTest | 7 | 0.373 s | 7 | 0.387 s |
| BrowserClientsTest | 7 | 0.228 s | 7 | 0.220 s |
| ProfilePolicyTest | 2 | 0.007 s | 2 | 0.009 s |
| Total | **30** | — | **37** | — |

Desktop 起始代码是 Batch 1 的 62-case baseline；本轮最终实际回归为 62 PASS，未把原审计中的历史 67 当成当前基线。

### 4. Scope

修改 `TaskManagerTest.java`、`TextCapabilitiesTest.java`、`RuntimeApiTest.java` 与本审计追加节。`src/main/**`、`desktop/**`、`pom.xml` / runtime config 零 diff。未访问或修改 Browser repo，未开始 M3、Memory、Finance、RAG，未修 B11 / B12。

### 5. Before

6 个 Java suites、**26 个 test method declarations / 30 expanded invocations**。TaskManager 的 4 个 boolean 参数化 lifecycle 各执行 String / structured 两次；TextCapabilities 将两个能力的 prompt 与取消混合；RuntimeApi 一个大 @Test 串行阻断多个后续 contract。

RuntimeApi generic failure-mode task submissions 是 **14**：Translate 2 + Summarize 4 + Ask 4 + Batch 4。该口径排除独立 offline connection-refusal 场景、readiness GET 和 capability success。

### 6. TaskManager Changes

4 个 scheduler 场景各保留一次 canonical String 路径，6 个方法 / 10 invocations → 6 个方法 / 6 invocations。完整保留 owner isolation、cross-owner GET/cancel not found、QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT、queue full、queued/running cancel、cancel idempotence、queue release、distinct queue/execution timeout、late-result discard、retention capacity/expiry 和 sanitized failure。

原 `structuredResultIsImmutableAndDiagnosticsAreRedactedAndArbitraryObjectsAreRejected` 保留，并增加实际 structured SUCCEEDED + result equality。不可变、redacted diagnostics、arbitrary object rejection 原断言保留。RuntimeApi 仍证明真实 HTTP structured cancel/revoke。

取消和 timeout 后用同一个单 worker 的后续成功任务作为 barrier，确认旧 work 的返回值已经由 scheduler 处理后再读结果；不再仅依赖 work 返回前的 exited latch 或 release 后立即 get。补 retained success/cancel 的跨 owner GET/cancel 检查及明确 FAILED/null result 断言。Queue/execution timeout 从 60/150 ms 调整为 250/1000 ms，retention 从 400 ms 调整为 1000 ms，并将固定 sleep 450 ms 改为有截止时间的 expiry polling。仍使用真实 timer，未引入 fake clock 或修改 TaskManager。

### 7. Capability Changes

`promptsStayOwnedByRuntimeAndRunningCancellationDiscardsLateOutputForBoth` → `promptsProfilesAndOutputsStayOwnedByRuntimeForBoth`。Summarize/Ask 都经过真实 service → shared submission → TaskManager 成功路径；保留并加强 Runtime-owned exact prompt/profile、promptVersion、capability identity、system/input separation、LOCAL_ONLY、DTO/execution redaction、string output。仅去掉两次完整 scheduler cancel/late scenario，替代路径见第 13 节。

Translate batch admission/readiness 的 translate authorization、cloud rejection、provider zero execution、DTO redaction、language-tag template reserve 均保留。Summarize/Ask cloud policy 在 provider execute 前被拒绝，zero execution 断言保留。

### 8. RuntimeApi Changes

拆为 **7 个方法 / 12 invocations**：

| Method | Expanded invocations | Purpose |
| --- | ---: | --- |
| runtimeHealthRemainsIndependentOfOfflineProvider | 1 | 真 connection refusal、Runtime offline startup/health、native admission |
| nativeTranslateContract | 1 | Translate schema/validation/budgets、public identity、text result、exact prompt |
| nativeAssistantCapabilitiesContract | 2 | Summarize/Ask 各自 endpoint/schema/forbidden fields/input budgets/prompt/profile/output |
| sharedApiErrorProjectionMatrix | 5 | named unavailable/model missing/malformed/output budget/internal sanitized projection |
| browserSecurityContract | 1 | approval/exchange、Origin/credential/routes/ownership/CORS/revoke/privacy |
| chromeOriginlessGetContract | 1 | 历史 Chrome GET regression 独立运行 |
| batchTranslateContract | 1 | 全部 Batch input/output/execution/identity/cancel/revoke contract |

一次 Spring context；每个 invocation 自己创建/启动/停止 loopback fake Provider，重置 mode/counters/output/latches，HTTP 配对使用独立 clients，finally 撤销已 exchange 的 fixture clients。每 JVM 使用 target 下唯一 token/registry 目录，避免上次非 clean 执行的 registry 残留。固定 SAME_THREAD 避免静态 mock state 被并发调用覆盖，不指定测试执行顺序。Offline 场景保持 Provider 不绑定、不启动。

每次 invocation 的 AfterEach 检查 private markers、token、记录的完整 prompt/input/pairing proof/credential/Origin 不进日志；browser/batch 专有诊断断言保留。该日志检查不依赖旧长方法前半段先 PASS。

Readiness 改为验证 available/error/code、禁止 model/provider/profile、无 concrete model；不再绑定完整 JSON 字符串与属性顺序。公开 profile version 仍验证 Translate `m0-1`、Summarize/Ask `m1.5-1`；promptVersion 仍精确验证。

Generic mode submissions **14 → 4**（四个真实 adapter failure rows），额外一个 INTERNAL_ERROR row 在 test-only scheduler work 注入 raw exception，随后实际 HTTP GET 验证 TaskController 的 sanitized projection；没有声称这条是新的真实 adapter fault simulation。Summarize 的独有 4096/4097-byte output 边界另行保留，不并入 Translate/Ask 共用预算。

### 9. Provider Test Changes

**OllamaProviderTest 零 diff，7 PASS。** 不实施 J3 classification merge；real HTTP parsing、generation settings、unavailable/model/raw status、malformed/oversize/incomplete、redirect rejection、真实 timeout、in-flight cancel、connection/request classification、final LOCAL_ONLY egress 全部原样保留。

### 10. Batch Coverage Retained

原输入拒绝 **26 rows**、forbidden ownership/config **10 fields**、body 413 **1 row** 全保留；覆盖 exactly-one text/items、null/type/ID validation、max 32/33、aggregate characters、UTF-8、serialized/context budget、unsafe language/profile。所有这些拒绝合并校验 provider zero generation。合法 32 items、2800 characters、1365 Chinese characters、2800 quotes 的成功输入保留。

输出映射 **9 rows** 全保留并有名称：完整 reorder、missing partial、unexpected ignored、duplicate output ID invalid/omitted、blank omitted、malformed items ignored、null/repeated ID omitted、empty partial、extra item field omitted。用每行 expected IDs 替换 counts/index 条件，继续检验安全 omission，并加强返回 ID 顺序。

输出拒绝 **8 rows** 全保留并有名称：malformed JSON、object/null top-level、trailing JSON、markdown fence、duplicate JSON key、output budget、adapter bounded-body budget。没有用一个 malformed case 取代其他协议故障。

Execution 保留一个 accepted batch 的一个 taskId/Location、exactly one provider generation（成功主场景及 9 映射行）；public profile/version/locality、translate-batch-v1、无 concrete model、Runtime-owned system/user 分离；cross-owner GET/DELETE 404、真实 HTTP RUNNING cancel/late response、revoke 后 accepted batch 仍成功，原样保留。

### 11. Browser Security Coverage Retained

Origin-present approval/exchange matrix、single-use/replay、exact registered Origin、wrong extension/web/null/wildcard Origin、missing/malformed/wrong-secret/native credential、Translate-only capabilities、route allowlist、Fetch Metadata、native/browser 双向 task ownership isolation、cross-owner GET/cancel 404、CORS exact Origin/no wildcard、no-store、list privacy、native revoke 与 revoked credential rejection 均保留。

历史 Originless Chrome GET 单独成为 @Test：valid browser bearer + no Origin + none/cors/empty → readiness/owned task GET 200；无 synthesized CORS，no-store；other browser/native owner 404；missing/wrong credential 和 Fetch Site/Mode/Dest 每个维度拒绝；route allowlist、POST/DELETE/OPTIONS/HEAD mutation/read restrictions、exchange Authorization/no-Origin 拒绝；Origin-present allowlist仍绑定原 Origin。原边界断言全部搬迁保留，不以历史 Chrome acceptance 替代。

### 12. Registry / Policy Tests

**BrowserClientsTest 7 methods / 7 PASS，ProfilePolicyTest 2 methods / 2 PASS，均零 diff。** Registry identity/persistence/restart/revoke、TTL/replay/budgets/capacity/Origin、malformed registry、atomic failure、ACL/lock；profile ownership、LOCAL_ONLY、endpoint validation、unsafe configuration/final privacy boundary 均保留。

### 13. Replacement Mapping

删除前依据当前 shared execution/DTO 路径和审计核对 replacement；没有 replacement 的边界不删除。

| Removed / merged old case | Replacement path | Same fault remains detectable because |
| --- | --- | --- |
| J2 ownersIsolate… 的 structured 重复 invocation | 同名 canonical String test + structuredResultIsImmutable… success + RuntimeApi batch ownership/cancel | TaskManager.submit/Job.run/find/cancel 的 scheduler/owner 路径不按 result shape 分叉；canonical 逐 owner/status 断言仍在，structured admission/terminal 类型独立证明；HTTP structured ownership/cancel 同样实测。 |
| J2 boundedQueueCancellationAndLateSuccess 的 structured 重复 invocation | 同名 canonical test + structuredResultIsImmutable… + batchTranslateContract | 同一 workers queue、cancel/remove/finish 路径仍检查 QUEUE_FULL、queued no-execute、running cancel、replacement success、capacity release/idempotence；structured success 与真实 HTTP cancel 保留。 |
| J2 queueAndExecutionTimeouts… 的 structured 重复 invocation | 同名 canonical test + structured success | QUEUED/RUNNING timer 和 status guard 不读取结果类型；distinct phases、TIMED_OUT/null result、处理旧 work 之后的 late discard 继续实测。 |
| J2 resultRetentionIsBoundedAndExpires 的 structured 重复 invocation | 同名 canonical test + structured success | maxRetained/expire 基于任务数/status/finishedAt/inWorker，与 result shape 无关；capacity rejection、expiry not found、新 admission 继续实测。 |
| J6 Summarize/Ask running-cancel/late-output 两次复制 | TaskManager ownersIsolate…、boundedQueue…、queueAndExecutionTimeouts…；RuntimeApi batchTranslateContract 的真实 adapter cancel | 两能力共用 TextTaskSubmission → TaskManager，没有 capability-specific cancellation branch；canonical scheduler barrier 捕捉 late finish/status overwrite，HTTP cancel 保留真实 future hook；两能力 service prompt/profile/privacy/output 接线仍各执行。 |
| J1 Summarize provider modes 1/2/3/4 | sharedApiErrorProjectionMatrix 四个 adapter rows + nativeAssistantCapabilitiesContract("summarize") 的 4096/4097 输出边界 | Summarize/Translate 共用 OllamaProvider.execute → TextTaskSubmission → TaskManager/TaskView → TaskController GET；相同 controlled error/status/null result/raw redaction 仍检查。Summarize 独有 schema/prompt/profile/4096 budget 单独保留。 |
| J1 Ask provider modes 1/2/3/4 | sharedApiErrorProjectionMatrix + nativeAssistantCapabilitiesContract("ask") | 同一 adapter/submission/scheduler/HTTP projection，无 Ask-specific generic error branch；Ask question schema、forbidden fields、input character/UTF8 budget、profile/prompt/result 身份仍独立检验。 |
| J1 Batch generic provider modes 1/2/3/4 | sharedApiErrorProjectionMatrix + batchTranslateContract 8 output rejection / 9 mapping rows | Batch 共用 submitMapped 的 adapter/output-budget/TaskView 错误路径；只有 structured mapping 是独有分叉，全部 top-level/key/item/duplicate/budget 故障保留，因此共用错误与 Batch 专属 mapping 错误仍可被捕捉。 |
| J1 Translate unavailable/model mode setup | sharedApiErrorProjectionMatrix 的 UNAVAILABLE/MODEL_MISSING + standalone offline health test | 真 503/empty model tags 继续执行；native readiness available vs model readiness 差异及 connection-refusal path 都保留。 |
| J1 readiness 完整 JSON 字符串相等 | batchContract available/error/code + forbidden public fields checks | 不绑定 property order；availability、错误 code、model/provider/profile 泄露等实际 contract 故障仍使断言失败。 |
| J1 原长 @Test 的 Browser / Chrome / Batch 串行 helper calls | browserSecurityContract、chromeOriginlessGetContract、batchTranslateContract 独立 @Test | 原安全/Batch断言逐行搬迁，自己的配对/任务 fixture；前置 native test 失败不会阻断这些 runner invocations。 |
| J3 classification merge | **未修改、未删除**；原 7 methods | 真实协议和分类故障仍由原 adapter authority 检测，无需 replacement。 |

### 14. Core Regression Gate

| Gate | Retained executable authority | Result |
| --- | --- | --- |
| W-Core1 TaskManager lifecycle / ownership | 6 TaskManager tests + real HTTP structured cancellation | PASS |
| W-Core2 Native capability contracts | native Translate / Summarize / Ask API rows、offline health、TextCapabilities / ProfilePolicy | PASS |
| W-Core3 Browser security matrix | independent browserSecurityContract + chromeOriginlessGetContract + readiness/batch ownership | PASS；原边界无删减 |
| W-Core4 Browser registry / pairing revoke | BrowserClients 原 7 tests + HTTP approval/exchange/revoke + accepted-batch lifecycle | PASS；原边界无删减 |
| W-Core5 Batch contract | 原 26 + 10 + 1 input rejection、9 mapping、8 output rejection、legal boundary/execution/identity/cancel/revoke | PASS；原边界无删减 |
| W-Core6 Provider boundary | OllamaProvider 原 7 tests + ProfilePolicy 原 2 tests | PASS |

### 15. After

| Suite | Methods before → after | Expanded invocations before → after | Source LOC before → after |
| --- | ---: | ---: | ---: |
| RuntimeApiTest | 1 → 7 | 1 → 12 | 540 → 660 |
| TaskManagerTest | 6 → 6 | 10 → 6 | 163 → 189 |
| TextCapabilitiesTest | 3 → 3 | 3 → 3 | 114 → 118 |
| OllamaProviderTest | 7 → 7 | 7 → 7 | 123 → 123 |
| BrowserClientsTest | 7 → 7 | 7 → 7 | 143 → 143 |
| ProfilePolicyTest | 2 → 2 | 2 → 2 | 49 → 49 |
| Total | **26 → 32** | **30 → 37** | **1132 → 1282** |

3 changed test files **817 → 967 LOC**；test diff **+365 / -215**，净 +150。表/fixture/失败隔离增加了代码行，执行重复减少；未宣称 source LOC 或 invocation 总数下降。审计报告只追加 187 行；总 diff **+552 / -215，4 files**。

### 16. Runtime / Maintenance Impact

production runtime 无变化。Scheduler 完整 result-shape 复制 8 → 4 invocations；service scheduler cancel 2 → 0；generic API adapter mode submissions 14 → 4，另新增独立 internal projection 与 Summarize 4096/4097 特有边界。维护共享错误时只改一个具名矩阵；能力身份/schema/prompt 特有断言各保留。

diagnostics：API 失败隔离从一个 @Test 提升到 12 runner invocations，两个能力与五个错误 modes 使用具名 JUnit 参数化显示名（Surefire XML 仍以 method[index] 记录）；Batch 26/9/8 行有明确名称，输出期望由 parallel arrays/index 条件改成 named row + expected IDs。单个 Batch 场景内部仍串行，不声称每个 matrix row 都是独立 runner invocation。

wall time：baseline warm test 12.18 s、clean test 17.31 s、clean verify 17.20 s（含编译/打包）；命令成本不同，且只作本轮单次测量，**不宣称提速**。TaskManager suite 1.342 → 2.056 s，主要为 timeout/retention 安全裕量；依然需要真实 clock/timer，随机顺序单个 seed PASS 不等于穷尽所有并发 interleavings。

### 17. Tests

| Command | Result | Wall time |
| --- | --- | ---: |
| `.\mvnw.cmd test -q` (before) | 30 PASS；0 failures/errors/skipped | 12.18 s |
| `.\mvnw.cmd clean test` (after) | 37 PASS；0 failures/errors/skipped；BUILD SUCCESS | 17.31 s |
| `.\mvnw.cmd clean verify '-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random' '-Djunit.jupiter.execution.order.random.seed=20261003'` | 37 PASS；0 failures/errors/skipped；jar/repackage PASS | 17.20 s |

README 的标准 gate 为 clean verify，已执行。Surefire XML 确认随机配置实际生效；API 执行顺序包含 assistant → batch → Chrome → Browser → errors → offline health → native Translate，证明这些场景无需旧长方法的先后状态。原 Mockito dynamic-agent warning 不改变 gate；无 dependency/runtime config 修改。

### 18. Desktop Regression

执行 `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-restore --logger "console;verbosity=minimal"`：**62 PASS，0 failed，0 skipped，wall 6.23 s**（含 build；runner 约 1 s）。desktop tracked diff 为空，Batch 1 保持不变。

### 19. Diff Check

`git diff --check` PASS；reviewed `git status`、`git diff --stat`、完整 `git diff`。`git diff --name-only 61c2936 -- src/main desktop pom.xml` 为空；只有以下 4 个授权 test/report 文件变化。Browser repo 没有操作或修改，因此未重跑 Chrome/MV3，也没有把它们列为本轮实际 PASS。

### 20. Files Changed

- `src/test/java/io/github/qianlixunbai/workspace/task/TaskManagerTest.java`
- `src/test/java/io/github/qianlixunbai/workspace/capability/TextCapabilitiesTest.java`
- `src/test/java/io/github/qianlixunbai/workspace/api/RuntimeApiTest.java`
- `docs/audits/POST-M2-TEST-SUITE-AUDIT.md`（只追加本节）

### 21. Deferred Batch 3

本轮不实施下一批。后续审计中的 Browser scenario/fixture、historical tooling 归档或 J3 named classification table 仍 DEFERRED，须单独确定范围；Browser registry/policy/adapter authority 不列为待删除目标。B11/B12、M3、Memory/Finance/RAG 仍未启动。

### 22. Recommendation

**GO：接受 Balanced Batch 2 的本地 test-only 提交。** 收益是同层重复执行减少、公开 contract 保留、故障定位与完成同步更明确；无需以 30 → 37 或 source +150 LOC 判定失败。保留现有 Chrome/real Provider/Windows acceptance 入口；本轮无对应 production 变更，不据此扩展验收范围。不 merge / push。
