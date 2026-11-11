# 主窗口正常退出入口与本机 UI Automation 能力调查

2026-11-11；基线 `04187db8363ad50ebdb26e3bd099da8172a9fa96`，分支 `codex/knowledge-version-compare`。本轮按明确指示转向正常使用／退出缺口，不继续增加未运行的业务功能。没有启动产品、测试窗口、原生准入或 UI Automation。

## 结论及本次源码改动

可以把托盘“退出”的同一关闭逻辑提供为主窗口可见入口。已在 WPF 原生顶部工具栏增加“退出工作区”，不依赖 React、WebView 是否初始化成功或托盘是否可操作。按钮调用注入的 `() => AssistantApp.ExitAsync()`；托盘仍直接调用同一个 `ExitAsync()`，其方法正文未改。没有新的 Bridge 方法、HTTP／管道退出接口、隐藏开关、进程终止命令或 NativeGate 例外。

点击期间按钮禁用并显示“正在退出…”，避免重复动作；既有未保存编辑确认被取消时恢复按钮。关闭窗口与应用退出仍不同：窗口 X 继续按既有逻辑关闭网页／保留托盘，点击“退出工作区”才请求整个应用正常结束。退出请求在途时不让窗口 X 另开一条并发关闭流程。按钮及窗口的 AutomationId 分别为 `ExitWorkspaceButton`、`MainWorkspaceWindow`，用于标准无障碍识别；不是额外控制协议。

最小改动只涉及 `AssistantApp.cs`、`MainWorkspaceWindow.xaml` 和其 code-behind。普通测试构造器没有应用退出回调时按钮禁用，正式 `AssistantApp.ShowWorkspace` 始终提供真实回调。没有为了自动化而建立假窗口、UIA 执行器或测试后门。

## 现有退出链及其实际边界

源码 `AssistantApp.ExitAsync` 依次保留：WebView 已报告的 Memory 未保存修改确认、原生 Memory 窗口 `TryClose`、设置全局退出标记、关闭选择器和工作区、等待 `WorkspaceOperations.ShutdownAsync`、关闭辅助窗口／热键、取消捕获和快速助手任务、等待自有 Runtime Stop、最后 `Application.Shutdown` 与幂等 `Cleanup`。原生记忆写入中的关闭保护仍由原窗口执行。它不是所有临时问题草稿的持久保存承诺。

- `WorkspaceWebViewHost.CloseAsync` 仍使 Bridge 会话失效，并按原 3 秒界限尝试 AllProfile 清理后释放 controller；`CleanupPassed=false` 不能写成清理成功。
- `WorkspaceOperations.ShutdownAsync` 保留请求取消和 5 秒／2.5 秒等待；快速助手保留原等待。取消请求或等待结束都不是外部模型实际结束的证明。
- 普通 owned-lifecycle 开发 Runtime 的 `StopAsync` 保留 graceful intent，`NativeRuntimeSession.CloseAsync` 经既有私有通道验证 CLOSE／CONTEXT_CLOSED、attempt／challenge 和自有 JVM 退出。迁移工作区则经 `MaintenanceRuntimeHandoff.FinishAsync`。SQLite／Spring 关闭仍由这些既有 Runtime 路径负责，按钮不直接关数据库。
- 原有失败清理仍可能关闭 Job 或结束自有子进程；RecoveryActivation 分支也不能泛化成正常协议退出。因此后续必须区分“用户请求退出”“Desktop 退出”“Runtime 正常关闭已证实”“兜底清理”，不能只因窗口消失或退出码 0 就宣称全部自然结束。

WPF 的 [OnExplicitShutdown](https://learn.microsoft.com/en-us/dotnet/api/system.windows.application.shutdownmode?view=windowsdesktop-10.0) 正是本应用关闭窗口后仍存活的机制；[直接 Shutdown 不受 Closing 取消约束](https://learn.microsoft.com/en-us/dotnet/api/system.windows.application.shutdown?view=windowsdesktop-10.0)，所以新按钮必须经过现有 `ExitAsync`，不能直接调用 Shutdown。新入口也不能解决主窗口出现前的启动／NativeGate 拒绝。

## 官方本机自动化能力：存在，实际窗口控制尚未证实

本次仅查询执行进程自身和已安装运行时：PowerShell 7.6.9、Session 1、`Environment.UserInteractive=true`；`dotnet --list-runtimes` 显示 Microsoft.WindowsDesktop.App 10.0.12。对该已确认路径下 UIAutomationClient.dll、UIAutomationTypes.dll、PresentationFramework.dll 做普通文件元数据读取，三者存在。没有加载产品／helper 程序集，没有调用 UIA、获取窗口句柄、列举桌面或控制任何窗口。这些事实支持具备本机实现条件，但不证明当前输入桌面、窗口站、完整性级别及 provider 可用。

官方支持的最小路线是：

1. 保留未来本次正常启动返回的 `Process` 对象与身份，不按名称接管旧进程。在该对象上 [Refresh／MainWindowHandle](https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.process.mainwindowhandle?view=net-10.0) 取得其本机主窗句柄；无主窗、隐藏到托盘或当前为别的对话框时可能为 0／不是目标，不能据标题猜另一个进程。
2. 对这个句柄调用 [GetWindowThreadProcessId](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindowthreadprocessid) 核对仍属于自有进程，再用 [AutomationElement.FromHandle](https://learn.microsoft.com/en-us/dotnet/api/system.windows.automation.automationelement.fromhandle?view=windowsdesktop-10.0) 从该窗口起步。只在其子树定位唯一的 `MainWorkspaceWindow`／`ExitWorkspaceButton`，核对 ProcessId、Button 类型、启用且可见；不从桌面 RootElement 搜索、不列举其他进程或读取网页／用户窗口内容。
3. 标准 [WPF ButtonAutomationPeer 实现 IInvokeProvider](https://learn.microsoft.com/en-us/dotnet/api/system.windows.automation.peers.buttonautomationpeer?view=windowsdesktop-10.0)，可用 [InvokePattern.Invoke](https://learn.microsoft.com/en-us/dotnet/api/system.windows.automation.invokepattern.invoke?view=windowsdesktop-10.0) 操作可见按钮，走同一 Click 事件。使用独立无窗口的 MTA 调用线程，遵循 [Microsoft 线程指南](https://learn.microsoft.com/en-us/windows/win32/winauto/uiauto-threading)，不在产品 UI 线程同步等待自身。
4. UIA 有 provider、UIPI、桌面及完整性级别限制；保留现有 manifest 的 `asInvoker / uiAccess=false`。依照 [安全边界](https://learn.microsoft.com/en-us/windows/win32/winauto/uiauto-securityoverview)，拒绝时停止，不提权、不修改 uiAccess、不换工具／身份／桌面或尝试窗口消息、反射退出、模拟其他点击来替代。

Invoke 返回不证明应用已经退出；provider 还可能因模态对话框阻塞。未来若发生超时，调用结果按未知处理，不把包装超时当作取消，不再次 Invoke，也不并行补发别的退出操作。未保存修改提示交给用户决定，不能自动确认丢弃。

这是与未知 CUA 后端分开的官方本机候选能力，不是已经完成同机 GUI 验收。本轮没有重做临时窗口／唯一标题关联实验，也没有新建自动化设施。

## 最小后续验证方案（本轮未执行）

先把本次三处 WPF 改动整合进准确源码的正常候选；本次普通 Debug 输出仅为编译证明，携带复用的旧前端资产，不是最终整合分发包。保持既有 NativeGate、原 Profile 和 normal startup 契约，不新建 Profile 或复用受拒验证脚本。当前指示不允许启动，因此本轮到源码与编译结束。

在后续允许实际运行且用户能作人工退出兜底的时段，最小一次流程为：正常启动得到自有进程 → 等主窗口出现 → 只读核对上述自有句柄／控件 → 对唯一启用的“退出工作区”调用一次 → 等待原应用关闭链和自有进程事实。先验证这个入口，不同步做业务／数据库全套测试，不一开始就自动跑两次重启。

必须单独记录：按钮调用结果、未保存确认是否阻止退出、Desktop 终态、既有 Runtime 正常关闭记录和是否触发兜底清理。仅观察本次持有的自有句柄／Job，不查旧受拒端口、全机资源或用户进程。若控件缺失、句柄不符、权限拒绝或 Invoke 结果未知，停止自动操作，保留现场说明；不以 Kill 冒充成功。

需要用户操作时，准确步骤为：保留可见主窗口 → 先保存希望保留的记忆及临时草稿 → 点击顶部“退出工作区”（不是标题栏 X）→ 若出现未保存确认，按自己的意图取消退出或确认丢弃 → 等待应用和托盘图标正常消失。旧包没有这个按钮时仍使用原托盘“退出”；不能把安装／重打包尚未完成的源码入口描述为用户已可点击。用户确认取消应保留应用，之后再明确发起退出。

## 拒绝范围的精确区分

| 目标／事实 | 当前边界 |
| --- | --- |
| 合成 Profile `C:\Users\28421\AppData\Local\PersonalAIWorkspace.Development\Profiles\2fd8ddc494cb45b5a7c8db284fbe386c` | 已明确发生 OS 拒绝：该根的精确只读元数据查询。该动作及替代入口继续停止，不换工具／身份／路径、不 provision 或新建 Profile 绕过。根路径比较缺陷仍未证实；Java／NativeGate／MemoryStore／SQL 断言未运行。 |
| 原产品 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2` | 历史固定 VerifyProfileNative helper 曾退出 0、路径匹配。不能推成所有读取被拒，也不能推成 Java／WebView／当前启动通过。本轮不访问、不验证、不启动该 Profile。 |
| 旧 Crashpad reports 列举 | 曾被拒，绝对 Profile 映射未知。不归因到原产品或合成 Profile，不重列举、不递归父目录定位。 |
| 当前 Windows 执行通道的系统组件元数据 | 本轮成功读取，与上述私有 Profile 目标不同；不解除任何既有拒绝，也不证明能控制用户桌面。 |

原用户对原产品两次正常启动／退出导致的 token 更新与 WebView AllProfile 清理已有具体同意，但本轮明确限制仍是“不执行”。新 UI 入口不修改这些影响，不把 Git 当作数据库、凭据或浏览数据回滚手段。

## 实际检查与交付范围

证据在 `artifacts/visible-workspace-exit-20261111/`：`capability-inventory.json` 仅记录系统组件元数据及执行进程自身；`nuget-inputs.json` 记录两项目已有 NuGet 解析文件原字节复制与哈希一致。`dotnet-build.log`／`dotnet-build-exit.json`：正式 Core／Desktop 普通 Debug 编译一次退出 0、0 警告、0 错误，4.12 秒。使用 SDK 独立 artifacts 输出、`--no-restore`、`FrontendSkipBuild=true`、两个 MMF3 开关 false、共享编译与构建服务器禁用；原前端仅校验既有资产，不重新构建。Session 1 的短互斥在 finally 释放。

XAML XML 解析及 Git 差异空白检查通过；静态核对实际 Click 接线、取消确认后按钮恢复及原 ExitAsync 正文未变。没有运行会创建窗口的现有 WPF 测试，也没有新增测试窗口。没有加载／执行生成的程序集、产品、helper、UIA、Java、真实数据库或模型，没有新分发包、安装或部署；Profile 条件构建及真实按钮／布局／退出尚未验收，`productionGo=false`。

只精确本地提交源码和本报告／状态入口，不推送、不改 main、六份原始未跟踪文档、旧包、原数据或 KAG。下一步是准确候选与一次可人工兜底的正常退出验证条件，不能继续用更多业务功能替代这个缺口。
