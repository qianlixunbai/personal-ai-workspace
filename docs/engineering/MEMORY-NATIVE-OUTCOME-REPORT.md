# 原生 Memory 写入结果不明保护

2026-11-10；基线 `fbfc70e9300dbc990ce5d2e73c75ef354b72a4bd`，分支 `codex/knowledge-version-compare`。

原生 Memory 的 `RunAsync` 原先在通信异常后恢复所有操作。若新建已经提交、但响应丢失，编辑器仍是未保存状态，用户再次点击 Save 会重发新建。现有 WebView Memory 页面已对这种结果不明状态禁用写入，本次将相同边界补到原生窗口。

`MemoryWindow.xaml.cs` 将写操作的 `OutcomeUnknown`、`ClientTimeout`、`RuntimeUnavailable`、`InvalidResponse` 标记为结果待确认；保持草稿，禁用保存、归档／恢复和删除，事件入口也阻止调用，不自动重试。元数据和状态提示说明如何刷新检查、明确重新加载；列表刷新不会解除保护。成功读取并载入记录，或经原有放弃确认开始新草稿，才清除该编辑器状态。未改变原有删除、关闭、重新加载确认，未增加协议、存储、权限或幂等性假设。

已知输入错误、修订冲突和不存在仍沿用原有处理；只读请求失败不产生写入结果不明状态。已确认成功的写入后，列表刷新异常仍由既有 `RefreshAfterMutationAsync` 单独处理，不将保存成功改为失败。

仅新增一条 STA 回归，复用现有合成 HTTP Store，在提交新建／更新后抛出通信异常。旧实现实际失败于 `Assert.False(window.SaveButton.IsEnabled)`；修复后同一用例 1/1 通过（约 306 ms）。用例还核对无重复提交、列表刷新不解锁、明确加载与放弃确认、输入错误和只读失败仍可恢复。实际注入的是响应丢失；其他错误枚举沿用前端分类，未分别扩展故障矩阵。归档和删除的阻断包含在此用例，未单独运行归档／删除响应丢失场景。

必要增量编译通过，0 警告、0 错误；沿用现有 Profile Debug 夹具、已审核清单（420 个精确哈希匹配）、`FrontendSkipBuild=true` 和 `--no-restore`。没有重跑前一入口用例、MemoryPage 24/24、类型检查或全套测试，没有显示窗口或启动 WebView2、JVM、模型、临时服务，未重建前端与分发包。

证据位于 `.verification/memory-native-outcome-20261110/`：`build-before.log`、`test-before.log`、`build-after.log`、`test-after.log`、`test-results/before.trx`、`test-results/after.trx`。

修改前完整回滚备份 `baseline-fbfc70e.bundle` 已验证包含基线 HEAD 和完整历史；SHA-256 为 `186B41B282B931365C63489958CDFF3E7BD4B2C46DEDF2CD0E30434C75A28873`。

复用 `.verification/memory-native-fallback-20261110/Invoke-WorkspaceHeavyRun.ps1`，Windows SessionId `1`，锁名 `Local\Dot_KAG_Workspace_HeavyRun`。旧实现复现持锁 PID `6456`，UTC `04:42:51.5189106` 至 `04:43:01.0670531`；修复验证 PID `7980`，UTC `04:43:44.6350499` 至 `04:43:52.3802609`。两次均在 `finally` 释放，包装 exit 0，两个自有进程随后确认退出。没有持锁编辑、写报告或 Git 操作，未遇锁忙、遗弃或权限拒绝；此前未知端口／浏览器状态未扩大核查。

本地提交范围仅为 `MemoryWindow.xaml.cs`、`MemoryWindowTests.cs`、本报告及 `docs/STATUS.md`。保护六份用户文档、真实数据与 main，不推送。当前仍是 Development-only；真实原生窗口交互、同版本分发及原 WebView2 Memory 保存／关闭／重开闭环仍未验收，合成回归不能替代这些交付关卡。
