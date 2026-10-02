# M2B-1 CLOSING REPORT

Date: 2026-10-02 (Asia/Shanghai)

## 1. Result

**CLOSED — GO**：Windows Assistant Browser Pairing UX 已实现、自动测试及真实 Windows 验收通过。
此结论仅适用于 trusted native 创建配对 UI，不代表 Chrome pairing PASS 或完整 Browser convergence。

## 2. Git

Branch：`m2b-browser-pairing-ui`。本地提交主题：`feat: add browser pairing UI`。
不 merge/push；main / origin/main 保持稳定基线。最终 commit SHA 通过 `git rev-parse HEAD` 获取，避免文档自身 SHA 递归。
生成凭据、日志、截图、验收脚本和 build outputs 只在 ignored `.runtime/` / `.verification/` / target / bin / obj 中。

## 3. Baseline

开始时 `git status` clean，branch main；HEAD / fetch 后 origin/main 均为
`9d20a9a4a138b9df3583e54eea8c3a1c78a8785e`，主题 `feat: add browser runtime access foundation`。
已执行 status / branch / HEAD / fetch / origin/main / log -10 reality check，读取 README、STATUS、current architecture、ADR-003 和 M2A Closing Report。
审查 BrowserAccessController / BrowserClients / LocalClientFilter、Desktop RuntimeClient / WinCred / WPF / tests。
修正 README / STATUS 的 M2A 当前未发布描述；M2A 历史 Closing Report 未改写。

## 4. Pairing UX

现有 Assistant 增加一个 Pair Browser 按钮，打开小型 WPF modal，延续现有颜色、控件和布局风格。
输入精确 `chrome-extension://[a-p]{32}`，无尾斜线；只有显式点击“创建一次性配对”才调用 endpoint。
启动、打开窗口、Origin 输入变化都不自动创建。格式检查属于 UX，Runtime 是最终 authority。
成功显示 Pairing ID、One-time pairing secret、Expires at（本机时区）；显式 Copy Pairing ID / Copy Secret。
不生成 package，因此没有混入 native credential 的 package 风险。
受控提示区分 Runtime unavailable、Unauthorized、Invalid extension origin、Pairing capacity full、Security state error、Pairing creation failed。
拒绝 malformed/extra/duplicate/oversized response，不显示 raw JSON、provider body 或 stack trace。

## 5. Runtime Contract

同一 RuntimeClient / native credential callback / fixed `http://127.0.0.1:8765`：

| Desktop method | API / result |
| --- | --- |
| CreateBrowserPairingAsync | POST `/api/v1/security/pairings`，origin、displayName=`Chrome Extension`、userApproved=true；200 pairingId/secret/expiresAt |
| ListBrowserClientsAsync | GET `/api/v1/security/clients`；最多 32 个安全 metadata DTO |
| RevokeBrowserClientAsync | DELETE `/api/v1/security/clients/{id}`；无 body 204 |

复用原 HTTP stack、8s HTTP/body deadline、1 MiB response cap、proxy/redirect/cookie 禁用、Accept JSON、auth、duplicates 和受控错误。
Shared SendAsync 仅增加 security error mapper 与 bounded empty-204 处理；原 AI task contract 与错误行为保留。
不调用 `/pairings/exchange`，不传 Origin header、不扫描/发现扩展、不向 Browser 自动发送 proof。
Runtime Java security / auth / registry / pairing TTL / TaskManager 全部不改。

## 6. Secret Lifecycle

Proof 只在当前窗口及请求处理的短期内存中，无 Credential Manager/file/log/telemetry/history 写入。
BrowserPairing diagnostic ToString 脱敏；受控异常不附 raw body 或 inner cause。Secret TextBox 禁用 undo。
第二次创建开始时即清除旧显示，失败也不恢复旧 secret；关闭清除 ID/secret/expiry/Origin/list 引用并停止 timer。
关闭取消 HTTP 等待，迟到响应无法恢复敏感 UI。TTL 到期自动清除显示，不自动重新创建。
Owner close / tray exit / cleanup 同样关闭配对窗口。释放托管引用不保证所有内存副本立即擦除。
显式复制写入系统剪贴板，其 history/sync 由 Windows 设置控制；本轮真实验收未复制实际 proof。
关闭不取消 Runtime session，服务器 3 分钟 TTL / restart 负责释放；网络失败可能已接受，不能保证“未创建”。

## 7. Revoke UX

手动刷新 Paired Browsers，只呈现 displayName、origin、createdAt、allowedCapabilities。
ClientId 仅用于路由；DTO 无 credential/verifier，解析采用字段 allowlist，含秘密/未知字段即拒绝。
显式选择并点击 Revoke selected；确认 204 后移除条目，失败保留并显示受控错误。通信失败需刷新确认。
未 exchange 的 session 不在列表；Revoke 不取消先前已接受任务。
HTTP 路径、native auth、empty-204、拒绝秘密字段、WPF 成功移除/失败保留均由自动测试覆盖。
真实验收只刷新列表，没有创建真实 Browser credential 或执行真实已注册客户端 revoke；不补造这项端到端证据。

## 8. Existing Desktop Regression

既有 Translate/Summarize/Ask、selection、controlled-copy、hotkey、tray、WinCred 和 helper 测试源码全部未改。
原有 40 tests 与新增 27 cases 共 67 PASS。没有修改 AssistantOperation、AI submit methods、Provider、模型、profile、prompt、queue 或 timeout。
真实 WPF 通过原 Action selector/input/submit/result：Translate / Summarize / Ask 全部 SUCCEEDED。
真实输出仅记录长度 6 / 76 / 1，无输入、输出正文或 token evidence。

## 9. Tests

| Command | Result |
| --- | --- |
| `.\mvnw.cmd clean verify` | PASS，Java 23，0 failures/errors/skipped，20:49 +08:00 |
| `dotnet restore desktop/PersonalAiWorkspace.Desktop.slnx` | PASS |
| `dotnet build desktop/PersonalAiWorkspace.Desktop.slnx --no-restore` | PASS，0 warnings/errors |
| `dotnet test desktop/PersonalAiWorkspace.Desktop.slnx --no-build --no-restore` | PASS，67，0 failed/skipped |
| `git diff --check` / staged whitespace check | PASS |

新增 contract + real WPF tests：valid/invalid origin、native approval JSON、offline/missing credential/401/cancel、
pairingId/secret/expiry parsing、malformed/duplicate/extra/oversized responses、capacity/security-state/failure categories、redacted diagnostics、
explicit button gate、immediate previous-secret clearing、replacement failure、close clearing/in-flight cancel/late result rejection、
expired proof hidden、safe metadata only、correct revoke API、failure retains / success removes client。
开发中修正 xUnit collection assertion analyzer 与无效日期 fixture；最终 tests 全部通过，不把早期 stale test binary 结果作为交付验证。

## 10. Real Windows Acceptance

2026-10-02 **20:54 +08:00**，真实 Windows WPF + Runtime + 已有 Ollama，UI Automation 操作实际控件：

| Check | Result |
| --- | --- |
| Runtime running，Desktop using existing WinCred | PASS |
| Synthetic valid extension Origin，明确点击创建 | PASS，两次真实 API response |
| Invalid origin 提示 | PASS |
| Pairing ID / 43-character one-time secret / expiresAt | PASS，值只在验收进程内存比较 |
| Second pairing replaces prior display | PASS，新 proof 不同；更完整的 immediate-clear 行为由 WPF tests 验证 |
| Close and reopen | PASS，无 pairing history/secret region |
| Safe client listing | PASS，未 exchange session 不成为 registered client |
| Native Translate / Summarize / Ask | PASS，真实 Runtime/Ollama，output lengths 6 / 76 / 1 |
| Actual token/proofs absent from logs/source/build | PASS，404 files checked |

**No exchange performed. No Chrome pairing acceptance claim.**
验收关闭 UI 后停止仅本轮启动的 Desktop/Runtime；未停止 Ollama。
早期脚本处理了 Windows PowerShell UTF-8/BOM、owned window automation tree 与 collapsed-control 查找问题；最终完整重跑 exit 0。
曾发现 Oracle javapath 启动 shim 的 child JVM 未被 launcher cleanup 收回，核对 PID/完整 jar 路径后清理，最终改为直接启动实际 JVM并确认无遗留进程。
非敏感 evidence 位于 ignored `.verification/m2b-1-windows-evidence.json`；截图仅在 proof 已清除的窗口生成，不保存 secret screenshot。

## 11. Privacy / Secret Audit

实际 native token 与两份当前 pairing proof 在验收进程内存中对比 source/log/build outputs，404 文件，0 匹配。
另做仓库 secret patterns、全部已有 private native tokens、verification/test logs、build/archive contents 与 Git ignore/tracked-artifact 审计。
最终扩展审计：101 source files、1075 files、33522 byte/archive checks、127 archives、5 个实际 native tokens，0 匹配、0 tracked build artifacts，ignore checks PASS。
Source 无 logger/telemetry、WinCred 写入、文件持久化、exchange 路径或 browser credential 生成逻辑。
保存的 evidence 仅有 PASS/status、counts、lengths 和时间，不保存 proof/native token/browser credential/verifier。
扫描范围为本仓库与本地验证产物，非全系统/剪贴板/内存 dump 审计。

## 12. Known Limitations

- 没有真实 Extension；Chrome Origin/Fetch Metadata/host permissions/storage/exchange 尚未验收。
- Proof 释放引用不保证 managed heap 立即物理擦除；用户复制后 Windows clipboard history/sync 不由应用控制。
- UI 关闭不撤销 outstanding session；通信失败可能已创建，需等待 TTL 或显式重试，最多 8 个 session。
- 固定 displayName `Chrome Extension`，同 Origin 多 registration 可能显示相同名称；没有 pairing history 或 auto-discovery。
- List parser 固定当前 Translate-only contract；将来 capability/schema 演进需同步调整 DTO。
- Revoke 本轮没有新增真实 registered-client 的端到端验收；Runtime 真实 revoke contract 已在 M2A 验证，Desktop 新路径用 contract/WPF tests 覆盖。
- Native shared owner / 同 OS 用户信任假设与既有取消、短 retention 等限制保持。

## 13. Deferred Scope

不修改 `qianlixunbai/local-ai-assistant`；Extension exchange、chrome.storage、Browser Runtime client、Browser Translate migration、
DOM/Dynamic Content/Restore/Selection Translation 全部 deferred。
Finance、Memory、RAG、Agent、Cloud、Streaming、installer/auto-update/完整 Workspace 均未开始。

## 14. Files Changed

- README.md、docs/STATUS.md、docs/architecture/current-architecture.md、本 Closing Report。
- Core：Contracts.cs、RuntimeClient.cs、BrowserSecurity.cs、RuntimeClient.BrowserSecurity.cs。
- Desktop：AssistantApp.cs、AssistantWindow.xaml、AssistantWindow.xaml.cs、BrowserPairingWindow.xaml、BrowserPairingWindow.xaml.cs。
- Tests：BrowserPairingTests.cs。

Desktop 路径分别位于 `desktop/src/PersonalAiWorkspace.Core/`、`desktop/src/PersonalAiWorkspace.Desktop/`、
`desktop/tests/PersonalAiWorkspace.Desktop.Tests/`。无 Java、旧测试、Provider/YAML/prompt/model/dependency 变更。

## 15. Architecture Compliance

Desktop 仍为 trusted native UI，唯一权威为 Runtime；master token 只用于 loopback authenticated native API，不对用户展示或给 Browser。
共享 HTTP client 和原 WinCred，无第二套 credential store/HTTP stack，无 browser registry direct writes，无自动批准/扫描/传 proof/exchange。
Single Runtime、ProviderPolicy、TaskManager 与 LOCAL_ONLY 不变。沿用 ADR-003，无新长期安全决策，不新增 ADR-004。

## 16. M2B-2 Readiness

Windows 端已经有可 review 的显式创建、短期 proof 生命周期与 native Revoke 入口。
下一阶段需由真实 Chrome Extension 展示自己的 Origin，并在可信 extension context 执行 exchange、storage 与 Runtime client 验证。
应验证 host permissions、实际 Origin/Fetch Metadata、trusted storage/message boundaries 与 Translate migration，不能复制 master token。
**M2B-2 未开始。本轮不 merge/push。**
