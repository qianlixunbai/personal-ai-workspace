# WebView2 失败页的原生 Memory 入口

日期：2026-11-10。基线：`e1fd98417e985b3119d9e18696ffd51c6d35e35c`，分支 `codex/knowledge-version-compare`。

## 用户收益与范围

工作区 WebView2 进入失败页后，用户可点击“打开原生记忆”，复用当前应用已有的原生 Memory 窗口。加载中的页面不显示该入口，也不自动打开窗口。原生入口不可用时单独显示固定提示，保留上方 WebView2 原始失败信息，不显示异常详情。打开期间按钮禁用，并拒绝重复事件；完成或失败后恢复，后续操作仍由用户明确发起。

没有新增存储、Runtime、Profile、HTTP、桥接方法或权限路径，没有调整 WebView2 安全设置、清理范围与超时。此功能不修复 WebView2 启动，也不代表 WebView2 Memory 保存、关闭、独立重开验收通过。

## 复用的路由与生命周期

`MainWorkspaceWindow` 调用既有 `IWorkspaceNativeActions.OpenAsync(NativeWorkspaceEntry.Memory)`。`AssistantApp` 检查退出/忙状态，经已有 `AssistantWindow.OpenWorkspaceEntry` 路由进入 `OpenMemory`；后者使用该 Assistant 已绑定的 Runtime，检查重复窗口，以 `MemoryWindow.ShowDialog()` 打开并在 `finally` 释放窗口引用。沿用现有临时显示 Assistant、恢复其可见性和返回工作区的行为。

`MemoryWindow` 的草稿放弃、重新加载和删除确认，修订冲突处理及关闭保护均未修改。此入口不直接保存、删除或替用户确认内容。原生 Memory 与 WebView2 Knowledge 草稿流转的能力边界不变。

## 精确修改与验证

- `desktop/src/PersonalAiWorkspace.Desktop/MainWorkspaceWindow.xaml`：失败页原生 Memory 按钮、独立可访问状态文本。
- `desktop/src/PersonalAiWorkspace.Desktop/MainWorkspaceWindow.xaml.cs`：仅失败回调显示入口；调用既有枚举路由，防止重入，显示固定错误提示。
- `desktop/tests/PersonalAiWorkspace.Desktop.Tests/MemoryWindowTests.cs`：复用既有 STA/Dispatcher 辅助函数，增加一条合成用例，覆盖加载中禁止打开、失败后 Memory 路由、等待期间重复点击、成功恢复、失败提示及原始错误保留。
- 本报告。

本次最小验证已通过：

1. XAML XML 静态解析与 `git diff --check`。
2. 一次必要增量编译：沿用 Profile Debug 验收身份与精确清单，`RuntimeLifecycleProbe=true`、`Mmf3ProfileAcceptanceBuild=true`、`FrontendSkipBuild=true`、`--no-restore`；420 个精确输入哈希匹配，编译 0 警告、0 错误。没有重建前端或产品包。
3. 仅运行 `MemoryWindowTests.WebViewFailureOffersExistingMemoryRouteWithoutStartingBrowserOrLosingFailure`，1/1 通过（断言执行约 267 ms，整条 test 命令约 1.7 秒）。没有重跑 MemoryPage 24/24、TypeScript 或其他测试。

新增用例不调用 `Window.Show`、`Host.InitializeAsync` 或真实原生操作，检查窗口未 Loaded、`UserDataFolder` 未创建、阶段仍为 `NOT_STARTED`，且未触发状态引导。它只验证入口与显示行为，未验证真实原生窗口、存储、Profile 选择或桌面持久化。未启动 WebView2、JVM、模型、浏览器或临时服务。

本地证据：`.verification/memory-native-fallback-20261110/incremental-build.log`、`focused-test.log`、`test-results/native-fallback.trx`。

## 共享短运行锁

当前 Windows SessionId 为 `1`；Codex SessionId 为 `01a1c0b7-97d4-73da-ab8c-08a5ac71d00e`。

忽略目录中的包装为 `.verification/memory-native-fallback-20261110/Invoke-WorkspaceHeavyRun.ps1`，任务脚本为同目录 `Verify-NativeFallback.ps1`。包装仅用于同步任务：一次等待最多 8 秒，使用会话级 `Local\Dot_KAG_Workspace_HeavyRun`；检查 SessionId，忙则退出，遗弃锁则不启动任务并要求核实自有状态，创建/打开被拒则停止。仅构建与单条测试持锁，静态编辑、Git、报告不持锁。服务/浏览器任务必须另由实际 guardian 持锁至其全部自有子进程清理结束，不能直接套用启动即返回的脚本。

本次实际持锁 PID `9748`：UTC `2026-11-10T04:30:37.3709914Z` 获得，`04:30:47.2534473Z` 在 `finally` 释放；编译 exit 0、测试 exit 0、包装 exit 0。未遇锁忙、遗弃或权限拒绝。同步任务已结束，没有本次启动的服务或浏览器占用；此前 18765 与未知浏览器后代仍未知，没有扩大进程扫描或终止未知对象。

## 回滚与断连恢复

源码修改前创建并成功执行 `git bundle verify`，包含基线 HEAD 和完整历史：

`.verification/memory-native-fallback-20261110/baseline-e1fd984.bundle`

SHA-256：`D543ADDDAC22B3D84D5024AC4CA839E9113D56AF3063008021D135494B7DB23A`。

第二次恢复只读核对确认 HEAD 未变、索引为空、三处源码修改完整，原报告确实尚未落盘，因此本次新建报告而未重写源码。六份用户未跟踪文档与既有保护清单哈希一致。真实 Profile、数据、main 和远程配置均未修改；不创建远程、不推送。正常逐次 Git 审批仅用于本报告列出的四文件本地检查点。

## 剩余交付边界

当前仍为 Development-only。尚未验收真实失败页点击后原生窗口的交互与持久化，也未更新同版本分发包。已有 WebView2 controller/子进程启动故障及原 WebView2 Memory 保存、关闭、独立重开闭环仍未通过；备用入口源码和这条合成回归不能替代这些验收。
