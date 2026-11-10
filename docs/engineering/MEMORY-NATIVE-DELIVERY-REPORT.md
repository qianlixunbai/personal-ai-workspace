# 原生记忆本地交付与编辑提示

2026-11-10。接续 `c0a8a09bff7871eebc3b940bed5f5686174ce84e`，仅在 `codex/knowledge-version-compare` 开发；Development-only / unsigned。本轮没有启动 WebView2、完整 AssistantApp、JVM、模型或临时服务。

## 已完成

已将原生记忆备用入口、写入结果不明保护，以及最近前端记忆草稿／查重／通知修复纳入独立 Windows 开发包。沿用现有本地分发脚本和已审查 Runtime，不更换 helper、JDK、JNA 或任何预期信任哈希。包内说明列出备用入口、明确保存、未知结果的核对步骤及未通过的桌面验收。

原生记忆编辑器原先允许空白／超限草稿点击 Save，之后才显示笼统输入错误。现在显示标题 Unicode 字符数、正文 UTF-16 长度和 UTF-8 字节数；无效草稿禁用 Save，并给出修改提示。内容保留，不自动截断。有效性直接复用 Core 的 `MemoryValidation.ValidText`，通过 `MemoryCreateInput.IsValid()` 暴露，不新增序列化字段、协议或另一套校验规则。现有修订冲突、结果不明、放弃确认与原生关闭行为保留，关闭时清除计数提示。

## 构建与必要验证

- 初版包绑定 `de5c1cec538006edd255af404a5e2c7ab24d22d7`，已包含此前原生入口和结果不明保护。前端构建与自包含 .NET 10.0.14 发布完成；沿用的 420 个受信输入哈希全部匹配。
- 现有分发合同 **7/7 通过**：实际 ZIP 解压与换路径全文件核对、私有状态拒绝、清单越界／生产身份冒充拒绝、JAR 修改拒绝、实际内嵌相对来源读取、重算不可信侧边清单仍不能绕过内嵌 pin。没有扩大测试矩阵。
- 编辑提示增量：必要无恢复构建 **0 警告／0 错误**；仅重跑受到 Save 可用性变化影响的一条既有 STA 用例，**1/1 通过，约 336 ms**。该用例只将无效草稿的 Save 预期改为禁用，仍核对有效输入恢复和未知写入禁止重放。不新增测试，也不重跑前端／入口或其他已通过检查。
- 本轮前端构建保留已有大于 500 kB chunk 提示；没有依赖漏洞审计或性能提升结论。

第一次发布在 NuGet 恢复阶段失败：沙箱进程选择 `CodexSandboxOffline` 的空缓存，访问 nuget.org 被套接字权限拒绝（NU1301／NU1900）。原失败日志保留。随后一次精确授权的同流程构建使用当前用户已有缓存，恢复与发布成功；不是自动审批拒绝，没有修改机器网络／沙箱策略或降低校验。原失败临时目录保留，不覆盖它继续发布。

初版产物：`artifacts/PersonalAiWorkspace-memory-native-20261110.zip`，384,670,089 字节，933 个产品文件。SHA-256：`4B400770D6A5AE8CC7619C46AEEC79D3D36B1E4B4502DB2C5E8BF5D79EB33971`。该初版尚不包含本报告中的编辑提示，最终同版本产物另在下方记录，旧包保留。

证据目录：`.verification/memory-native-delivery-20261110/`。`build.log` 为首轮失败，`build-restored.log` 为成功构建；`contract.log`、`package-result.json` 为初版合同与收据；`editor-build.log`、`editor-test.log`、`test-results/editor.trx` 为编辑提示验证。

## 回滚、资源与验收边界

完整 Git 回滚：`.verification/memory-native-delivery-20261110/baseline-c0a8a09.bundle`，已验证精确基线及完整历史；SHA-256 `F09D20E96AC59EB548DF3E3F33292B38AE8F17C011BB50AB5FC7A3BDB64A99FD`。旧 ZIP 与解压目录保留；回滚应用不能覆盖或回退用户数据库。

复用 `.verification/memory-native-fallback-20261110/Invoke-WorkspaceHeavyRun.ps1` 的 Session 1 会话锁 `Local\Dot_KAG_Workspace_HeavyRun`。构建 PID 11708（04:52:22–04:53:09 UTC，失败）和 7252（04:54:12–04:54:31，成功）、初版合同 PID 12184（04:55:18–04:55:32）、编辑验证 PID 29656（04:57:22–04:57:31）均已在 finally 释放。编辑、Git、报告不持锁。未遇锁忙、遗弃或权限拒绝，不查询或终止未知浏览器／18765，也不处理 KAG 的 18895。

**仍未验收**：原 WebView2 保存／关闭／独立重开；真实失败页点击后原生窗口的交互、保存与独立重开；最终包的完整 EXE 启动、跨机器、PG 桌面、个人恢复、签名／安装／更新。合成 STA 和包合同不能替代上述验收。没有根因结论或重复浏览器启动实验。

六份用户文档、真实 Profile／数据和 `main @ f5266bf4383f52a2293b0fe4bfe1964af958e64a` 保持；没有推送、远程发布、账户／凭据或许可条款变更。manifest 的 `sourceDirty=true` 记录六份未跟踪用户文档，产品构建以已提交源码为准，这些文档不进分发包。
