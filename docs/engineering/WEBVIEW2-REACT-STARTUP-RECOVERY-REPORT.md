# WebView2 / React Startup Recovery

2026-10-20（Asia/Shanghai）。**当前账户普通交互式桌面上下文：最终开发 EXE 的 React、Native Bridge、Java/SQLite、托盘退出和同一合成 Profile 重开已实际通过。Codex 打包宿主内直接启动仍有环境限制；Explorer 无参数双击与 Profile 选择器的人工综合验收 PENDING。**

## 交付与结论

最终包：`artifacts/PersonalAiWorkspace-webview2-react-recovery-development-20261020.zip`。

- 大小：375652048 bytes。
- SHA-256：`99b2f1d4e9e85da57a69866f53a523a50e215f0ff92bf05f6839d9b35d9bef51`。
- 实现提交：`74737ac9049d3446a7f6bef7037aa71cb666a6f1`。
- 分支：`codex/webview2-react-startup-recovery`，从预期基线 `a41e1391c23b276ca70fb6195c1986f101d090a5` 创建。
- 新 ZIP 仅生成一次。旧 ZIP、旧 Profile、receipt、备份及 Windows Native Security Modernization 成果保留。

不能声称修好了 Codex 打包宿主自身的 WebView2 环境。实际恢复途径是使用已验证的普通 Windows 桌面上下文运行同一个产品 EXE；没有增加产品启动器、修改系统配置或绕过准入。源码修复了应用自身的无限等待、误判 Ready 和错误归因问题，并交付了可在普通上下文操作的开发客户端。

## 故障定位与上下文对照

首先核对了现代化报告、当前架构、ADR-009/010、WPF/WebView2/Bridge/React 源码和指定日志。
原 `windows-modernization-real-gui.log` 仅有探针 STOP；`windows-modernization-client-ui.json` 仅显示加载界面；`windows-modernization-real-owner.log` 已证明 Runtime/认证/SQLite。上述历史结果不等于本轮 GUI 通过。

本轮先保留原启动行为，仅增加阶段诊断，再进行对照：

| 实际上下文 | Environment | Controller / 初始化事件 | 页面 / React |
| --- | --- | --- | --- |
| Codex 派生进程，全新合成 Profile | 18 ms 创建成功 | `CONTROLLER_START` 后约 60 秒仍未完成；退出处分解控件时才收到 `0x80004004` | 未执行 Navigate，尚未加载 HTML/JS/CSS |
| `Shell.Application.ShellExecute` 派生的对照 | 16 ms 创建成功 | 同样未完成；实际 UDF 仍位于打包映射路径 | 未导航；该调用不能当作普通 Explorer 上下文的证明 |
| 当前账户 Interactive / Limited 的一次性计划任务启动，独立新合成 Profile | 11 ms 创建成功 | 首次约 5.3 秒完成，产生 `CoreWebView2InitializationCompleted` | 原页面约 5.8 秒导航完成，真实 Bridge/Native Bearer/SQLite 显示通过 |
| 最终自包含 EXE，普通上下文 | 成功 | 初始化事件和 Controller 均成功 | 首次约 4401 ms、重开约 4431 ms 达到 `REACT_BRIDGE_READY` |

证据分别为 `.verification/startup-recovery-baseline-gui.log`、`startup-recovery-explorer-baseline.log`、`startup-recovery-unpackaged-baseline.log`、`startup-recovery-final-client-stage.log` 和 `startup-recovery-final-restart-stage.log`。

本机对照使用同一 WebView2 Runtime `155.0.4283.62`、同一安全策略和前端资源。Codex 派生浏览器实际 UDF 长度为 225 字符，包含 `WpSystem/.../LocalCache/Local`；普通上下文为 129 字符，不含该映射段。**这是宿主/实际目录差异的证据，不足以单独证明 MAX_PATH 是根因。** 没有据此重装 Runtime、缩短受信目录、增加别名、复制 Profile 或放宽文件身份检查。

`0x80004004` 出现在探针主动退出并 Dispose 之后，表示取消，不能当作最初初始化失败的 HRESULT。等待期间探针的 UI dispatcher 仍可推进；源码使用 STA、WPF Loaded 异步初始化，没有发现 GUI 分支对 WebView2 Task 使用同步 Wait/Result 的证据。

普通上下文创建 Profile 后，Codex 对早期合成 run 的后续访问在 `PROFILE_PARSE` 得到固定 `DIRECTORY_IDENTITY` 拒绝；另一次新合成准备返回 `0x80131620`，未进入 GUI。证据：`startup-recovery-codex-profile-identity.log`、`startup-recovery-codex-fresh-profile.log`。保留现场，未调整 ACL、Known Folder 或 Native Gate。不同宿主不承诺自动共用 Profile。

## 初始化链逐项核验

| 阶段 / 问题 | 当前真实证据 |
| --- | --- |
| WPF Window | 最终 EXE 创建真实窗口，UI Automation 能访问 React 导航和设置控件 |
| Environment / User Data Folder | 上述两类上下文都创建 Environment；继续核对 Environment/Controller 的 UDF 与准入 Profile 一致 |
| Controller / InitializationCompleted | 普通上下文成功；Codex 最初卡在此处，早于资源或 React 执行 |
| 导航地址与完成事件 | 最终固定地址 `https://workspace.personal-ai.invalid/index.html`；探针核对实际 Source，宿主记录 `NAVIGATION_COMPLETED` |
| HTML / JS / CSS | 最终 EXE 两次均记录 `HTML_LOADED`、`SCRIPT_LOADED`、`STYLESHEET_LOADED`；探针同时检查 root 有 DOM 子节点和 stylesheet 存在 |
| CSP / 资源来源 | 两次最终 EXE 日志未观察到 `RESOURCE_BLOCKED`、`ASSET_RESPONSE_FAILED` 或 `BROWSER_SECURITY_ERROR`；原严格 CSP、manifest 和 Deny 虚拟源映射保留 |
| JavaScript / Error Boundary | Development-only Runtime/Log 观察器未记录 `SCRIPT_UNCAUGHT`；真实探针检查未出现 Error Boundary 降级标题，并取得 React Settings 的 Core CapturePreview |
| Bridge Session / Origin | 沿原版本、来源、当前 document、session、payload 验证；原聚焦合同检查及旧 session 晚到响应检查通过 |
| React Ready 到 WPF | 原协议是 WPF 在导航完成后发送 `shell.session`，React 挂载后请求 `shell.bootstrap`；现在仅在该请求通过原验证并成功响应后标记 Ready，不新增 wire method 或绕过验证 |
| UI 等待 / 超时 | 首次初始化至有效 bootstrap 共用 30 秒期限；关闭取消等待，失败释放 WebView，晚到初始化不能恢复失败窗口；无自动重试 |

实际像素证据：`.verification/startup-recovery-react-settings.png`，来自真实 WebView2 Core CapturePreview。最终 EXE 的控件操作证据见 `startup-recovery-final-client-ui.json`、`startup-recovery-final-client-settings.json`、`startup-recovery-final-restart-settings.json`。当前会话的物理屏幕拷贝取得锁屏壁纸，未将该文件用作 GUI 成功证据。

最终 EXE 首次和重开后都通过真实 UI Automation 进入 Settings、调用“重新检查存储”，显示 `SQLite`、`Ready` 和“已响应并通过原生认证”，加载提示消失。这些证据超出了“WPF 窗口已打开”。没有调用模型、真实 Provider 或业务写入功能。

## 最小源码修改与安全边界

生产文件：

- `desktop/src/PersonalAiWorkspace.Desktop/Hosting/WorkspaceWebViewHost.cs`：有界启动、关闭取消、固定阶段/错误诊断、资源/脚本观察；等待真实 bootstrap，替换原暗示 Runtime 未安装的笼统提示。
- `desktop/src/PersonalAiWorkspace.Desktop/Bridge/WorkspaceBridge.cs`：内部 `HasBootstrapped` 仅由当前会话成功的 `shell.bootstrap` 设置，会话失效时清零；原入站和出站准入条件保持。
- `desktop/src/PersonalAiWorkspace.Desktop/MainWorkspaceWindow.xaml.cs`：通过 `Host.IsReady` 隐藏加载层，移除仅因导航完成便隐藏加载层的逻辑。

测试/探针文件：

- `desktop/tests/PersonalAiWorkspace.Desktop.Tests/RuntimeLifecycleProbe.cs`：复用原 AssistantApp 入口，增加合成 Profile 准备、固定错误分类、Ready/失败 UI 检查及正常退出观测；截图采用新文件名以支持同一 Profile 重开。保留原 `gui` 生命周期推送检查，增加 `gui-close` 和两个负向模式。
- `desktop/tests/PersonalAiWorkspace.Desktop.Tests/WorkspaceBridgeTests.cs`：在既有来源/会话测试中补充 bootstrap 标记及晚到响应不能标记新会话 Ready 的断言；没有新增独立 unit test。

本报告为第六个变更文件。未修改 Java、Native Helper、WindowsProfileSecurity、Runtime Owner、Native Bearer、数据库配置、Finance/Memory/Knowledge 业务或 React Error Boundary。

诊断仅在 Debug 开发构建记录，每个 WebView 实例最多 64 条；仅含固定 contract/stage/code、HRESULT 数值、UTC 和耗时。Profile 日志使用新的独占创建文件。不输出 Bearer、密码、DPAPI、页面内容、WebMessage、任意 URL 或原始异常消息。DevTools UI、远程调试端口及浏览器保护没有开启或放宽。

## 本轮实际执行的聚焦验证

| 本轮执行 | 结果与证据 |
| --- | --- |
| 原 Bridge 聚焦类 | PASS：`startup-recovery-bridge-tests.log`；之后仅复验扩展的来源/晚到响应两条既有测试，`startup-recovery-bridge-ready-tests.log` |
| Debug 真实 GUI 探针构建 | PASS，零警告/错误：`startup-recovery-probe-build.log` |
| 普通 Release 编译，acceptance 属性关闭 | PASS，零警告/错误：`startup-recovery-release-build.log`；仅编译，不是正式启动准入通过 |
| 普通上下文真实 GUI，同一新合成 Profile 重开 | PASS：`startup-recovery-unpackaged-fixed-repeat.log`；React/Bridge、Native Bearer、SQLite、从 Ready 正常 ExitAsync 后所持 Java 退出 |
| 不可信 WebView 环境覆盖的负向启动 | PASS：`startup-recovery-negative-environment.log`；固定 `INITIALIZATION_FAILED / PROFILE_VALIDATED` 和可见原生 fallback，未接受覆盖 |
| 页面完成但 React 不发送 Ready 的负向启动 | PASS：`startup-recovery-negative-ready-timeout.log`；真实 Controller/HTML/JS/CSS/导航成功，约 30030 ms 后固定 `STARTUP_TIMEOUT / NAVIGATION_COMPLETED`，加载层没有被提前隐藏，随后正常退出并清理 Java |
| 最终 EXE 首次启动、设置按钮、托盘退出 | PASS：`startup-recovery-final-client-settings.json`、`startup-recovery-final-client-exit.log` |
| 最终 EXE 同一合成 Profile 再次启动、设置按钮、托盘退出 | PASS：`startup-recovery-final-client-restart.json`、`startup-recovery-final-restart-settings.json`、`startup-recovery-final-restart-exit.log`；两次退出均确认 Desktop/所持 Java 结束，18765 无监听 |
| 最终目录/ZIP | PASS：`startup-recovery-package.log`、`startup-recovery-final-zip-verification.log`；完整目录 manifest/hash/assets 检查，ZIP 条目完整性及主程序集/前端资源摘要检查 |

负向 Ready fixture 只将独立 `.verification/...-probe/MainWorkspace` 输出中的 JS 临时改为 no-op，并更新该 fixture 的资源摘要；仍使用原 CSP、资源准入和 Bridge。执行后按原字节恢复 JS/manifest，恢复标记在 `startup-recovery-negative-ready-timeout-restored.txt`。没有改源码前端、最终包或任何 Profile 的用户数据。

最新 Codex 上下文的 Controller 超时分支未能动态重跑，因为 Profile 身份检查先行拒绝；它不能标为已通过。共享 30 秒期限已在上述真实“导航成功但没有 React Ready”情形动态通过。

未运行 Java/Desktop/Frontend 全量、Browser/IME、PostgreSQL、Finance Binding、模型/Provider、备份恢复或 SQLite 长路径综合回归。前端未改，复用并校验既有产物；没有重新执行 TypeScript/Vite 构建。B4A5 的历史 PASS 只用于对照，不计入本轮成功执行。

真实 GUI 使用当前账户的 Interactive/Limited 一次性计划任务进行上下文对照，没有提权、密码、系统代理或全局环境修改；任务已注销。托盘 Exit 通过该 EXE 所属 NotifyIcon 的右键回调打开菜单，再用 UI Automation 调用原“退出”，最终走原 ExitAsync；未强杀本次 EXE/Java 或其他应用。

## 打包、历史资产与剩余事项

早期探针使用的旧本地绝对构建清单有 hash 不匹配；随后复用经原 `Assert-DevelopmentBundle` 校验的上一轮 `-final` 包输入。最终仅发布新的 WPF/Desktop，自包含 .NET；复用原 Java/JDK/JNA、Native Helper 和嵌入式来源 pins，无 Maven/JDK/Helper 重编译。打包步骤保留于忽略的 `.verification/startup-recovery-package.ps1`。ZIP 的来源绑定实现提交；`sourceDirty=true` 对应原八个 B4A6 未追踪文件，不表示把它们放进包。

上一轮最终 ZIP 的 SHA-256 仍为 `0aad2ff2ec13a17f3aee0f2ff64fbe6a91fbb17da363b759a050bc1d17fe1404`。原八个 B4A6 文件继续未追踪；历史 Profile/receipt/备份/其他 ZIP 未写入或删除。无 reset、clean、stash、覆盖 checkout、push、merge、tag 或 release。实现和报告本地提交；最终报告提交 HEAD 以交付回复及 Git 为准。

**SQLite 约 318 字符路径下的 `SQLITE_CANTOPEN / MEMORY_RESTORE_FAILED` 保持独立 PENDING，本轮未修复或重跑；Production GO 仍 PENDING。** 本轮不扩大到 Codex/Windows 安全架构或正式发布。

## 一次人工双击验收

1. 在普通 Windows Explorer 中将新 ZIP 完整解压到新目录，双击 `PersonalAiWorkspace.Desktop.exe`，选择“是”创建新的合成开发工作区。
2. 确认 React 导航出现，在 Settings 点击“重新检查存储”，看到 SQLite、Credential Ready 和原生认证成功。
3. 托盘选择“退出”，再次双击 EXE，选择“否”并选择刚创建的同一 Profile，确认界面再次 Ready。只关闭主窗口会保留托盘。

普通 Explorer 上下文的 Profile 根可在地址栏打开 `%LOCALAPPDATA%\PersonalAIWorkspace.Development\Profiles`，按本次创建时间确认新的 32 位目录；这只是人工定位提示，产品仍通过系统 Known Folder 和原生身份检查决定目录。

此项用于补齐无参数 Explorer 入口及选择器的人工路径；本轮自动化已验证同一产品 EXE 的显式 Profile 入口、真实 React 操作和正常重开。不要从 Codex 打包宿主共享/复制旧 Profile，也不要选择 B4A6 历史目录。若普通 Explorer 入口仍失败，下一项必要信息是该次固定阶段/错误码；保留该新 Profile 的 `logs/workspace-ui-*.jsonl`，不运行 ACL 修复脚本。

本轮到此停止，等待用户确认。

## 官方实现参考

- [WebView2 threading model](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model)：UI STA/message pump、异步等待与回调约束；本轮经 `read-docs` 工作流核对。
- [.NET WinForms 10 NotifyIcon](https://github.com/dotnet/winforms/blob/v10.0.0/src/System.Windows.Forms/System/Windows/Forms/NotifyIcon.cs)：限定本次 EXE 的正常托盘右键/退出验证依据。
