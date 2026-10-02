# M2A CLOSING REPORT — Browser Runtime Access Foundation

Date: 2026-10-02 (Asia/Shanghai)

## 1. Result

**CLOSED — GO: Runtime Browser Access Foundation PASS.**
Runtime security contract、自动回归、真实本地 synthetic browser security smoke 均完成。
不代表 Chrome Extension PASS、Browser migration PASS 或 Browser Convergence complete；未开始 M2B。

## 2. Git

Repository：`qianlixunbai/personal-ai-workspace`。
本地：`D:\IDEA\Daima\personal-ai-workspace`。
分支：`m2a-browser-runtime-access`，从 clean main 创建；实现仅本地提交，主题 `feat: add browser runtime access foundation`。
未 merge main、push、force push、reset 或覆盖用户工作。精确交付 HEAD 使用 `git rev-parse HEAD` 查询，避免递归回写 commit SHA。
README/STATUS/architecture 最小同步 M1.5 已发布事实；历史 M1.5 Closing Report 未改写。

## 3. Baseline

Reality check 执行 git status/branch/HEAD/remotes/fetch/origin-main/log。
开始时 main clean，fetch 后 `main == origin/main == 22c45de4ff2ff2996960ca93817914af1da73baa`。
M0/M1/M1.5 均 CLOSED — GO，当前 Git/source/tests 优先于旧文档。

## 4. Environment

当前 Windows 本机；Java 21.0.7 LTS、Maven Wrapper、Spring Boot 4.1.1、正式 .NET SDK 10.0.401。
真实 provider 为现有 loopback Ollama，已有模型 `qwen3.5:4b`；未安装 SDK/模型/服务，未修改 Ollama 配置。
所有过程证据在 Git ignored `.verification/` 或构建测试报告目录。

## 5. M0/M1/M1.5 Regression

Java 既有 provider/policy/profile/capability/queue/cancel/deadline/retention 测试通过。
Desktop restore/build/test 通过：40 tests，0 failed/skipped，build 0 warnings/errors。
真实 native Translate、Summarize、Ask 都 SUCCEEDED，公开 readiness UP、未认证 401、listener 127.0.0.1。
Desktop source 无变更；本轮未重新进行人工 WPF GUI 验收，GUI/hotkey/selection/tray/clipboard 的历史 PASS
来源仍是 M1/M1.5 自动真实操作及用户确认，未将其改写为本轮新 GUI 证据。

## 6. Security Problem Addressed

单一 native token 信任域不能安全扩展到 Browser。旧模型只凭 taskId GET/DELETE，无法隔离独立客户端。
M2A 不分发 master token，而建立 explicit pairing、独立 credential、Origin binding、Translate-only 授权和 task ownership。

## 7. Pairing Model

Trusted native action 显式批准精确 extension Origin，提交 `userApproved: true`。
服务器生成随机 UUID pairingId 与 256-bit proof，只在内存保存 SHA-256；TTL 3 分钟、single-use。
Exchange 校验 loopback、活动 session、exact Origin、Fetch Metadata 和 proof；成功前消耗 session，replay/expiry 失败。
重启清除 outstanding pairing。最大 8 sessions，每个 session 5 次错误 proof，exchange 总量每分钟 60 次。
Boolean 是 trusted caller 的用户批准契约，服务器不能证明物理点击；M2A 没有新 pairing GUI。

## 8. Client Identity

服务端生成稳定随机 UUID clientId，类型 `browser-extension`，安全 displayName、exact origin、createdAt、capability set。
仅安全 metadata 可 listing；identity 不包含 credential/verifier，diagnostic toString 不输出 origin。
Native 使用固定 `native-local`；native token 持有人仍属于共享 native owner。

## 9. Credential Model

Browser credential：`br1.<clientId>.<256-bit-base64url-secret>`，独立于 master token 和所有其他 client。
只在 exchange JSON 的 `credential` 字段返回一次，响应 no-store；不放 task metadata、Header 或 Location。
服务端只持久化 SHA-256 verifier，使用 MessageDigest.isEqual；无自制密码学、HMAC key 生命周期或明文 browser secret 文件。
高熵随机值不采用密码 KDF；不存在用户密码/账户。
Future extension 负责自己保存凭据并限制不可信 content script 访问，不向 webpage/DOM/log 暴露；本轮未实现 extension storage。

## 10. Origin Policy

Native：原格式 Bearer、无 Origin、拒绝 cross-site；行为保持兼容。
Browser：精确 `chrome-extension://[a-p]{32}`，必须匹配注册 identity/credential。
Browser contract 要求 none/cors/empty Fetch Metadata，拒绝 missing、navigation 和 cross-site。
普通 https/http webpage、localhost Origin、未知扩展、wildcard、origin mismatch 全部拒绝。
受控 preflight 只允许已批准/注册精确 Origin、指定路由/方法与 Authorization/Content-Type；无 wildcard/cookies。
CORS 不签发身份、不替代认证，实际请求仍需要 proof/credential。
具体规则与官方 Chrome/W3C 参考见 ADR-003；真实 Chrome 发出的 headers 尚未测试。

## 11. Capability Authorization

Browser v1 固定 Translate only：POST 现有 Translate task API，GET/DELETE 自己的 task。
Ask、Summarize、provider readiness、pairing create、client listing/revoke 默认拒绝 POLICY_DENIED。
TextTaskSubmission 同时检查 principal 的 capability set；没有大型 RBAC framework。
Windows 保留三个 capability，均 LOCAL_ONLY。

## 12. Task Ownership

每个 accepted Job 在同一 TaskManager 绑定 ownerClientId。
A 可以 GET/DELETE A 的任务，B 不能；native/browser 双向不能访问彼此任务。
未知、过期、非本人任务完全相同 404 TASK_NOT_FOUND，不返回其他 owner/任务细节。
Owner cancel、QUEUED/RUNNING、不可逆终态、late result、queue/full/deadline/retention 语义保持。
Bootstrap authority 不拥有 browser task 的读取后门。

## 13. Persistence

Token 专用私有目录下 `browser-clients.json`：schema version 1，最多 32 registrations / 64 KiB。
仅 identity/origin/capability/verifier，无 conversation/prompt/input/result/task persistence，无 SQLite/Memory DB。
Windows owner-only ACL / POSIX 0700/0600；拒绝不安全路径/权限失败。
Private pending file → 完整写入 → force → ATOMIC_MOVE replace → publish memory。
Exclusive OS file lock 防止两个 Runtime 共写 registry。Corrupt/unknown/duplicate/oversized state fail closed，不静默清空。
Committed 文件是恢复权威；stale pending 不覆盖 committed，只有 pending 时拒绝启动。
Browser credentials 跨 restart，task 和 pairing session 不跨 restart。

## 14. Revocation

Native-authorized DELETE client API 幂等删除 verifier/注册并持久化，释放 registry slot，无无限 tombstones。
后续 credential 请求 401；重启后继续失效。删除失败不提前更新内存，不宣称 revoke 成功。
Future extension local forget 删除自己副本，不等于 server revoke。
Revoke 不自动取消先前已接受任务；既有 deadline/retention 控制其完成及结果过期。

## 15. Native Compatibility

未改 token 格式/文件或 Windows Credential Manager target，不要求 Windows 重新导入或重新配对。
Desktop 全部 source、hotkey/selection/cancel/tray/UIA/controlled-copy 保持。
Native tasks 继续由所有持有现有 token 的 native callers 查询/取消，HTTP DTO/路径不 breaking。

## 16. APIs

| Method / Path | Access / Result |
| --- | --- |
| POST `/api/v1/security/pairings` | Native + explicit approval；pairingId/pairingSecret/expiresAt |
| POST `/api/v1/security/pairings/exchange` | Exact extension + proof；本 client metadata + credential |
| GET `/api/v1/security/clients` | Native；安全 metadata array，无 secret/verifier |
| DELETE `/api/v1/security/clients/{id}` | Native；204，幂等 revoke/forget |
| POST `/api/v1/translate/tasks` | Native 或 Translate-authorized browser；原 202/Location contract |
| GET/DELETE `/api/v1/tasks/{id}` | 已认证 owner；原 task envelope / 404 non-enumerating semantics |

管理请求 DTO 拒绝未知字段；HTTP body 仍最多 32 KiB。Auth error 401，capability denied 403，pairing capacity 429。
没有 `/browser/translate` 或第二套 TaskManager。

## 17. Tests

| Command | Result |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS，23 tests，0 failures/errors/skipped；最终 17:13 +08:00 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS，40 tests，0 failed/skipped |
| `git diff --check` | PASS |

API 测试覆盖 native、web/unknown/wildcard/mismatch/missing Origin、wrong/malformed/missing credentials、metadata rejection、
exact preflight、pairing replay、Translate success、Ask/Summarize/administration denied、listing privacy、task ownership 和 revoke。
六个 security registry tests 覆盖 reload/revoke、session restart invalidation、TTL、single-use、failure budgets、
32-client/8-session bounds、malformed/oversized/unknown/duplicate format、private ACL、exclusive lock、atomic failure/recovery。
新增 TaskManager test 覆盖 running/queued/retained task isolation、owner cancel 与 late result；既有 full/timeout/expiry 回归不变。
CapturedOutput 验证实际 master/browser/pairing credentials、origin、synthetic input/output/provider marker 不进入日志。
普通 tests 使用 fake work/loopback mock，不依赖 Ollama。

## 18. Real Security Smoke

原 native smoke：**REAL PASS**，2026-10-02 17:17 +08:00，三种 capability SUCCEEDED。
新增 `scripts/browser-security-smoke.ps1`：**REAL PASS**，2026-10-02 17:19 +08:00。
实际 Runtime + 已有 Ollama，synthetic HTTP extension clients，无 browser extension 修改。

| Check | Result |
| --- | --- |
| Native authenticated Translate / readiness | PASS |
| Explicit pairing/exchange A/B | PASS，独立 clientId/credential |
| Browser Translate | SUCCEEDED |
| Pairing replay | 401 |
| Correct credential + wrong Origin / ordinary https Origin | 401 / 401 |
| B GET/DELETE A；native/browser cross-owner | 404 TASK_NOT_FOUND |
| Browser Ask/Summarize | 403 |
| Actual Runtime restart, credential still usable | PASS，新 Translate SUCCEEDED |
| Revoke then restart | PASS，前后请求均 401 |
| Actual listener | 127.0.0.1 |
| Log/source/build/evidence secret audit | PASS |

安全 evidence 仅保存 clientId、status/error category、时间与结果，不保存 secret/input/result。
早期 smoke 尾部日志审计遇到空 stderr/Windows file sharing 错误，未记录 PASS；最终修正为清理 Runtime 后审计所有重启日志，完整成功退出。
脚本仅停止自己启动的 JVM，不停止用户 Ollama/Desktop，不自动 pull 模型。

## 19. Privacy / Secret Audit

实际 smoke 的 native/browser/pairing secrets 仅在进程内存；registry 仅 verifier；diagnostic DTO secret toString 已脱敏。
运行时 stdout/stderr 不包含 credentials 或 extension Origins，metadata evidence 无 secrets。
实际 credentials 对当前源码、build outputs、evidence 检查无匹配；仓库 secret patterns/本机 native tokens/build archives 审计通过。
Auth files、`.verification/`、target 与 Desktop bin/obj/test reports 均 ignored，无 tracked secret 或 build output。
扫描范围限本仓库与本地验证产物，不宣称全系统安全审计。
最终审计覆盖 408 文件、16566 byte/archive checks 和 5 个实际 native token，0 匹配、0 tracked build outputs。
Dependency 中 RSA/OpenSSL parser 的 PEM header 常量是扫描误报，已核对没有 key body，并以完整 PEM content 规则复核 PASS。

## 20. Known Limitations

- 未真实 Chrome acceptance；严格 Origin/Fetch Metadata/host-permission contract 须在 M2B 用实际 Chrome 验证。
- 本轮未新增 pairing approval UI；trusted action 使用 protected API，调用方负责呈现并获得用户批准。
- Native callers 共享 owner；不隔离已攻陷同 OS 用户进程，其可读取 private file/伪造 HTTP headers。
- Credential 是长期 bearer，无自动 rotation；撤销不抢占已接受任务。Task restart/短 retention 不提供 durable replay。
- Registry permission/restart 测试在当前 Windows 执行；POSIX 实机和特殊/不支持权限文件系统未验证。
- File force + atomic replace 不宣称所有硬件/文件系统断电 durability；未做 power-loss fault injection。
- 既有取消不保证 GPU 立即停止，模型质量/吞吐与生产可靠性不由合成 smoke 证明。
- Mockito/Byte Buddy 动态 agent 提示为既有 test JVM 行为，Runtime 无此 agent。

## 21. Deferred Scope

Browser Extension、DOM/UI/cache/dynamic DOM/Restore/selection migration，Finance/Gateway/DB、Memory/conversation/SQLite、
Knowledge/RAG/agent/tools、cloud/remote login/streaming、React/WebView2 Workspace、installer/auto-update/mobile/sync。
Local AI Assistant 与 Finance 仓库未访问/修改；旧项目状态沿用输入，未声称本轮实时验收。

## 22. Files Changed

- README.md、docs/STATUS.md、docs/architecture/current-architecture.md。
- docs/ADR/README.md、docs/ADR/ADR-003-browser-client-security.md、本 Closing Report。
- scripts/browser-security-smoke.ps1。
- security/BrowserClients.java、ClientIdentity.java、LocalClientFilter.java、LocalClientToken.java、SecurityConfiguration.java。
- api/BrowserAccessController.java、ApiExceptionHandler.java、TaskController.java。
- capability/TextTaskSubmission.java、task/TaskManager.java。
- tests/api/RuntimeApiTest.java、security/BrowserClientsTest.java、task/TaskManagerTest.java。

Java 文件以上均在现有 `src/main/java/io/github/qianlixunbai/workspace/` 或 `src/test/java/io/github/qianlixunbai/workspace/`。
无 Desktop、provider、prompt、profile/YAML、pom/dependency 或其他仓库变更。

## 23. ADR

[ADR-003](../ADR/ADR-003-browser-client-security.md)：Accepted，记录 explicit pairing、per-client verifier/origin/owner、
native compatibility、no wildcard/web pairing/OAuth、Translate-only、persistence/restart、revocation、abuse bounds 与真实 Chrome deferred。
ADR-001/002 的历史决策不改写，当前模型由 ADR-003 演进。

## 24. Architecture Compliance

独立 Java Shared Runtime、单 ProviderPolicy、共享三能力 task lifecycle 和 native WPF entry 保持。
Browser 使用同一 Translate API/TaskManager，未建立 browser-special capability/provider pipeline。
新增 persistence 只服务 auth metadata，不提前启动 Memory。Local-only、无 cloud fallback、无 Finance/旧仓库集成。

## 25. M2B Readiness

Runtime foundation 具备可 review 的 explicit pairing、revocable credential、Translate-only 与 owner contract。
下一阶段需要另行授权并在 Chrome 验证 service-worker Origin/Fetch Metadata/host permissions、可信 storage/message 边界与用户配对体验。
本轮不启动 M2B、不修改 Extension、不合并或发布分支。
