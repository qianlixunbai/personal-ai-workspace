# 原生记忆本地交付与编辑提示

2026-11-10。接续 `c0a8a09bff7871eebc3b940bed5f5686174ce84e`，仅在 `codex/knowledge-version-compare` 开发；Development-only / unsigned。本轮没有启动 WebView2、完整 AssistantApp、JVM、模型或临时服务。

后续接续已完成[原生 Memory 真实持久化闭环](MEMORY-NATIVE-PERSISTENCE-REPORT.md)：沿用本报告最终包，在新合成 Profile 中运行实际原生窗口、HTTP 与 SQLite，明确保存、正常关闭及独立宿主／Java 进程重开读取通过。以下构建记录仍是本报告原交付轮的事实；完整 AssistantApp 与 WebView2 验收边界保持。

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

## 最终交付入口

- [最终 ZIP](../../artifacts/PersonalAiWorkspace-memory-native-final-20261110.zip)：384,670,547 字节；完整解压后运行 `PersonalAiWorkspace.Desktop.exe`。本机已有[解压目录入口](../../artifacts/PersonalAiWorkspace-memory-native-final-20261110/PersonalAiWorkspace.Desktop.exe)。不上传或远程发布。
- 产品来源：`0ad966521fed75701ab29dbef6395f325e032a1d`，包含本轮编辑提示；之前包说明提交为 `de5c1cec538006edd255af404a5e2c7ab24d22d7`。最终产品源码构建时无未提交修改，后续收尾只改文档。
- ZIP SHA-256：`32518BBC44E9F2BB9E5ED063D62CC50ECAF4E6BFE2DD3D2429593CB55B3C1B2D`。
- Desktop DLL SHA-256：`B18017560C853A6196979965F0A87A6D3351ACD49949C343E22C95CBCFF2E7EE`；Runtime JAR 仍为 `71F181BF8D991AA5484C9EFB83D548EC275644407B388CD68BE220421DF5DFFD`，Java owning source 自前已审查包以来无变化，无须重复 JVM 构建。
- 最终发布使用已有恢复结果 `--no-restore`，前端重新构建，受信输入再次通过 420 项校验。实际 ZIP 解压到含中文和空格的新目录后，933 个产品文件完整性通过；只添加已有测试工具，不替换产品文件，运行实际包内 `TrustedDevelopmentRuntimeSources.Open()` 成功，并再次确认全部产品文件未变。这是最终包的独立正向验证；初版 7 项篡改矩阵未重复，也未宣称最终包重跑 7/7。
- 最终证据：`build-final.log`、`final-sources.log`、`final-package-result.json`、`final-isolation.json`。验收副本只在忽略目录 `final-relocated/` 中保留，不作为运行入口；它包含测试工具。

最终构建持锁 PID 14728，04:59:41–04:59:58 UTC。第一次最终核对因共享锁忙，8 秒后退出且没有启动任务；期间继续静态交接工作。随后 PID 20396 在 05:01:10–05:01:14 获锁并完成核对，finally 正常释放。没有绕过资源协调或终止锁持有者；以上六个自有包装进程均已确认退出。

## 回滚、资源与验收边界

完整 Git 回滚：`.verification/memory-native-delivery-20261110/baseline-c0a8a09.bundle`，已验证精确基线及完整历史；SHA-256 `F09D20E96AC59EB548DF3E3F33292B38AE8F17C011BB50AB5FC7A3BDB64A99FD`。旧 ZIP 与解压目录保留；回滚应用不能覆盖或回退用户数据库。

复用 `.verification/memory-native-fallback-20261110/Invoke-WorkspaceHeavyRun.ps1` 的 Session 1 会话锁 `Local\Dot_KAG_Workspace_HeavyRun`。构建 PID 11708（04:52:22–04:53:09 UTC，失败）和 7252（04:54:12–04:54:31，成功）、初版合同 PID 12184（04:55:18–04:55:32）、编辑验证 PID 29656（04:57:22–04:57:31）均已在 finally 释放。编辑、Git、报告不持锁。未遇锁忙、遗弃或权限拒绝，不查询或终止未知浏览器／18765，也不处理 KAG 的 18895。

**仍未验收**：原 WebView2 保存／关闭／独立重开；真实失败页点击后原生窗口的交互、保存与独立重开；最终包的完整 EXE 启动、跨机器、PG 桌面、个人恢复、签名／安装／更新。合成 STA 和包合同不能替代上述验收。没有根因结论或重复浏览器启动实验。

六份用户文档、真实 Profile／数据和 `main @ f5266bf4383f52a2293b0fe4bfe1964af958e64a` 保持；没有推送、远程发布、账户／凭据或许可条款变更。manifest 的 `sourceDirty=true` 记录六份未跟踪用户文档，产品构建以已提交源码为准，这些文档不进分发包。

## 下一会话交接

本轮完成本地包补齐、原生编辑反馈及最终同版本交付收尾；没有遗留待执行的构建、测试或提交结果。先核对 HEAD、索引和工作区，不重跑这里已通过的检查，不改六份用户文档、main 或其他仓库。

原交接项**仅原生记忆的明确保存／取消与独立进程重开**已由后续[真实持久化报告](MEMORY-NATIVE-PERSISTENCE-REPORT.md)完成。使用最终包产品文件、正常 Native Gate 与新合成 Profile，实际持久化由不同宿主／Java 进程读回，未写上次工作区偏好。不要重跑该样本或借此重启无新证据的 WebView2 对照。18765 及旧浏览器后代仍未知，不得当作空闲或直接清理。

需要保留的具体阻塞是 WebView2 controller 启动及 GPU／renderer 失败，根因仍未确定；当前新包没有解决它。后续新发现的 Knowledge 历史库长路径限制与原生保存通过范围见真实持久化报告，不混同为浏览器根因。构建首轮 NuGet 阻塞已解决，没有自动审批拒绝、待审批或结果不明的本轮写入。
