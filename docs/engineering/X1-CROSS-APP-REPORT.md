# X1 Cross-App Assistant

2026-10-28（Asia/Shanghai）。**IMPLEMENTED / DEVELOPMENT BUILD READY；WINDOWS ACCEPTANCE PARTIAL。**
基线 `ad0913dcf3c6ea918ad83665c2cdf0e7c90995b3`，入场工作区干净；交付分支 `codex/x1-cross-app-assistant`，最终源码身份以该分支 Git HEAD 为准。没有 Production GO。

2026-10-31 聚焦真实验收更新：用户物理 Q → 记事本选区 → 总结，以及物理 T → 直接翻译均 **PASS**；两条路径都有独立 Runtime UUID / `SUCCEEDED` / 完成时间、实际模型结果和主动复制核对，见 [本轮独立记录](X1-REAL-E2E-ACCEPTANCE-20261031.md)。关闭后的来源窗口独立核验 **BLOCKED**：收口时原记事本进程已不存在，不能确认关闭瞬间的焦点与选区保留。下文保留 2026-10-28 的历史执行事实；IME、取消、迟到结果、DPI / 混合多屏等未在新一轮执行，X1 整体仍 **WINDOWS ACCEPTANCE PARTIAL**。Vision BLOCKED、Development-only、无已核验 CI PASS、尚未 Production GO。

## 使用与复用

- 其他应用主动选中文字 → **Ctrl+Alt+Shift+Q** → 在原生浮窗选择翻译、总结或解释/提问，再点击执行。Q 不自动提交模型任务。
- **Ctrl+Alt+Shift+T** 保持选区捕获后直接翻译；两入口使用不同 hotkey ID，独立注册、提示冲突与释放。运行中不接纳另一次 hotkey，不建立队列。
- 切换操作保留原文，清除旧结果及显式 Memory 授权；解释/提问可填问题，捕获场景留空则解释。手动 Ask 未填附加问题时仍发送原单轮问题。
- 新捕获、取消及关闭清除原文/附加问题/结果；内容版本拒绝迟到捕获、进度和结果。Esc 与窗口关闭走同一取消/清理路径；失焦不主动关闭。浮窗靠近触发时光标，按屏幕工作区限制位置。
- 结果只显示在浮窗，仍需主动点击 Copy result；没有自动粘贴。

直接复用 `SelectionWorker`、`ControlledCopy` / `NativeCopyPort`、`AssistantApp` / `AssistantWindow`、`AssistantOperation`、`RuntimeClient`，包括 Runtime Owner、Native Gate、UIA 受保护输入拒绝、helper deadline、Clipboard owner/sequence/format/finally 恢复和先取得任务身份再取消的合同。
GitHub 固定版本、文件/函数、许可证与安全取舍见 [借鉴记录](X1-CROSS-APP-REFERENCE.md)；四个项目仅借鉴思路，无源码复制、GPL 引入、外部安装器/脚本或新依赖。

## 实际代码变化

| 文件 | 修改 |
|---|---|
| `HotkeyRegistration.cs` | 新增 Q ID 与独立注册/释放，保留 T / MOD_NOREPEAT |
| `AssistantApp.cs` | 双入口共享原捕获链；捕获 CTS；内容版本守卫；关闭/取消清空并请求原任务取消；受信 Development Profile 启用快捷键，旧 isolated acceptance 仍禁用 |
| `AssistantWindow.xaml` / `.xaml.cs` | 修复 ActionChanged 无条件 ClearText；附加问题与解释；原生 Esc；收起维护区；浮窗定位 |
| `Native.cs` | 仅增加窗口位置查询/设置 interop，无输入或权限扩展 |
| `NativeCopyPort.cs` | 受控 Copy 同时等待 Q 松开 |
| `ControlledCopy.cs` | 在入口及快照完成后、SendCopy 前检查取消；保留现有恢复与异常语义 |

未修改 `SelectionWorker`、`AssistantOperation`、`RuntimeClient`、Java Runtime、模型管理、队列或其他领域代码。Ask 仍遵守原 3000 字符 / 5632 UTF-8 字节合同，翻译/总结预算不变；超限显式拒绝，不截断选区。没有云端 fallback、全局 Clipboard 监听、通用键盘钩子、跨应用自动控制框架或 Finance / Memory / Knowledge / Vision / Browser 权限变更。

测试仅扩展既有 `SelectionAndLifecycleTests`：一个选区切换/解释问题/超限不截断/Esc/关闭清除/新捕获的综合回归；原取消测试补充快照后取消不得发送 Copy。既有 `RuntimeLifecycleProbe` 增加单条可选跨进程 X1 flow（`gui-x1` / 无模型 `gui-x1-native`），不注册新的测试工程。测试按键注入只存在于显式启用的验收 driver，且先校验自己创建的 fixture 前台窗口。

## 本轮实际执行与限制

| 验证 | 结果 / 边界 |
|---|---|
| Desktop 聚焦自动化 | `SelectionAndLifecycleTests`、`UiaTests`、POST pending cancel、显式 Memory 生命周期：PASS；13 selected cases，非完整 Desktop suite |
| 最后两项聚焦复验 | 最后 UI/清理调整后，仅重跑新综合回归及 Clipboard 取消回归：PASS；与前项重叠，不计作新增覆盖 |
| 真实 Windows 完整 X1 flow | **STOP**。两次运行均完成 Runtime Owner 启动、独立 fixture 聚焦、真实 Q hotkey/UIA 捕获及总结切换保留选区。首次仅输出断言类型，第二次补充状态诊断，Runtime 返回 `MODEL_EXECUTION_UNCERTAIN`。没有成功的模型结果证据，没有继续切换/释放/重置模型或绕过门禁 |
| 真实 Windows 无模型 flow | **STOP**。Runtime 启动和外部 fixture 创建成功；`SetForegroundWindow` 返回 false，在注入按键前停止。没有输入附加到任意用户窗口，也没有添加生产抢焦点 workaround |
| 构建 | 既有受信 Development 构建入口成功；最终增量 Desktop 编译成功，frontend assets 完整性检查通过；最终独立目录 publish 使用已编译输出 |

Q 捕获的真实证据来自本轮较早候选；最后的提示/焦点/清理小改动没有再次取得真实 native flow PASS。自动化中的 Esc 是向真实 WPF Window 发送 routed key event，**不是物理键盘验收**。
最后静态复核补上原 CredentialFlow 入口自动展开维护区，避免收起后无法聚焦导入按钮；该两行 UI 调整仅执行增量编译与 publish，没有重跑已通过的测试。
仍未验证：真实 T 自动翻译结果、总结/解释/提问的模型输出、物理 Esc、运行中关闭后的真实迟到响应、真实应用受控 Copy fallback/恢复及多屏混合 DPI。不得把上表部分覆盖点视为完整 Windows PASS。

本机证据：`desktop/tests/PersonalAiWorkspace.Desktop.Tests/TestResults/x1-focused.trx`、`x1-final-focused.trx`；`.verification/x1-windows-native.log`、`x1-windows-native-diagnostic.log`、`x1-windows-native-only.log`、`x1-development-build.log`。这些是 ignored 本机产物，没有提交日志、用户数据或凭据。
早期构建曾发现旧受信清单的 JAR hash 不匹配，改用现有构建脚本重新生成，不放宽校验；一次 `Profile=true / RuntimeLifecycleProbe=false` 非受支持测试组合编译失败，改为既有 Probe=true 组合后两项复验通过，没有修改无关 PostgreSQL harness。

本轮未运行 Java / Desktop / Frontend 全量测试、WorkspaceSanity 全流程、IME/压力矩阵或正式打包矩阵。原生尝试后，本轮创建的客户端/fixture 与 owned Runtime 已退出，未保留 18765 listener。

## Development 构建

可执行文件：`D:\IDEA\Daima\personal-ai-workspace\artifacts\x1-development-20261028-final\PersonalAiWorkspace.Desktop.exe`。
独立目录、framework-dependent Development-only 构建，依赖本机 .NET 10 Windows Desktop / Native helper runtime、WebView2 和现有受信本地 JDK/JAR；不是可分发的完整 portable bundle。没有替换旧包。
无参数启动沿用现有开发 Profile 选择/记忆及完整原生安全校验；所有默认数据库和模型状态均保留。模型处于上述 uncertain 状态时 AI 请求继续被拒绝。
构建身份、文件 hash 与本机前提另存于该目录 `X1-build.json` / `README-X1.txt`，不包含 token 或数据。后续 docs-only 提交不触发重建。


## 2026-10-31 Windows 交互 P2 补充

[本轮 P2 记录](X1-WINDOWS-INTERACTION-P2-20261031.md)在新的独立记事本进程上取得鼠标 X、物理 Esc、来源焦点、正文 / 选区保留及对应剪贴板检查 PASS。此前来源进程退出导致的 BLOCKED 保留为历史事实。真实 IME 流程因用户确认主动发送 nihao 并收到真实回复而停止：至少一次新增推理，零新增推理约束未满足，IME 仍 BLOCKED / NOT TESTED；未判作自动误发。T 提示仅有独立分支的一行源码修复及聚焦验证，受信包未覆盖，未重复真实 T。X1 PARTIAL、Vision BLOCKED、Development-only、无已核验 CI PASS、尚未 Production GO 保持。
