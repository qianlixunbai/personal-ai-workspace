# 当前功能链的 Windows 开发包整合验收

日期：2026-11-09。结论：近期 Word 导入、源版本对比、限定检索、历史问答和笔记导出已进入同一个本地 Windows x64 开发包，并完成关键桌面流程验证。仍为 **Development-only / unsigned**，不是安装器或 Production GO；没有推送。

## 交付与来源

- [最终 ZIP](../../artifacts/Workspace-integrated-development-final-20261109.zip)：完整解压后运行目录内 `PersonalAiWorkspace.Desktop.exe`。也可使用本地已有的 [最终解压目录入口](../../artifacts/Workspace-integrated-development-final-20261109/PersonalAiWorkspace.Desktop.exe)。这些产物被 Git 忽略，只存在于本机。
- 产品代码：`d465b49bfce270ac5f6d536b32293117e76b3634`；最终许可材料打包逻辑：`b5a6b562a66a3309a9c085f88eb5370e16884a62`。最终清单分别记录 `commit` 和 `packagingCommit`。
- SHA-256：`B9FE6EFF6E0D37BB2832631716D026308FDD0C3CDD8598394908D4226C10AEC3`。
- Desktop DLL：`8864D507E6D469F0A00F2F5BB67016B69658F91AFF3833A7A1ADB85843F62407`；Runtime JAR：`1F80733405A6D09EBB80A546803955568CD492581EE9F3EBCB24438C4761BDF8`。
- 922 个清单内文件，其中 915 个与已验证候选包逐文件一致，另补充 7 个许可／notice 文件；没有为了补许可而重新编译产品。最终包及验收副本均重新核对原 915 个文件的哈希。

当前仍在 `codex/knowledge-version-compare` 上沿同一提交链开发，没有再建立零散功能分支。`main` 保持此前已获授权的 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，没有扩大默认分支更新范围。阶段回滚标签 `workspace-before-current-bundle-20261109` 指向开始时的 `abe503b`；产品及打包修复分三次本地提交保留。

清单的 `sourceDirty=true` 保留了构建时六份既有未跟踪用户文档这一事实；当时已跟踪产品文件无未提交修改。这些文档没有被打包或提交。最终来源、文件数量及哈希见 [final-package.json](../../.verification/workspace-current-bundle-20261109/final-package.json)。

## 为什么优先整合交付

总体路线要求得到一个实际可运行的桌面产品。近期能力已经串在同一代码链，继续添加小功能无法证明用户运行的程序包含这些成果。本轮将重点放在完整包、实际入口、包内依赖和用户能走通的资料流程。

先对成熟方案做了来源核查：

| 方案 | 许可、维护和适配判断 |
| --- | --- |
| [.NET SDK](https://github.com/dotnet/sdk)／[Runtime](https://github.com/dotnet/runtime) | 观察时约 3.2k／18.4k stars，MIT。现有工程已使用官方 SDK；[官方自包含发布](https://learn.microsoft.com/en-us/dotnet/core/deploying/)与现有 ZIP 流程可复用，不必另写安装系统。许可材料以实际 NuGet 包为准。 |
| [Velopack](https://github.com/velopack/velopack) | 观察时约 2.4k stars，MIT，[release 页面](https://github.com/velopack/velopack/releases)有 1.2.161 及预发行维护记录；[项目公开安全公告](https://github.com/velopack/velopack/security/advisories)未发现已发布项目公告，这不等于完成安全审计。它适合后续安装／更新；当前引入会增加签名、更新源、安装 hooks 和进程退出合同，因此本轮不引入。 |

选择复用既有 `dotnet publish`、ZIP 与打包合同，只修正实际阻碍交付的部分。没有新增运行时框架、更新服务或模型依赖。

## 修正的三个问题

1. **打包检查持有旧 helper pin。** 第一次预检直接拒绝，尚未开始构建。只将 `Assert-DevelopmentBootstrap` 的两项旧常量同步为 `WindowsNativeGate` 自 `2d417c6` 起已经固定的值；没有按候选文件自动生成新信任，没有修改 Runtime Gate、helper、入口或 ACL。拒绝和复核结果分别保留在 `packaging-precheck-failure.json`、`packaging-precheck-after.json`。提交 `94aa7ed` 同时补充包内实际使用说明。
2. **自包含发布默认带入旧补丁运行时。** 首个候选使用安装的 SDK 10.0.401 默认配套 .NET 10.0.12。官方 [GHSA-p3jp-9v83-76w9 / CVE-2026-111497](https://github.com/dotnet/runtime/security/advisories/GHSA-p3jp-9v83-76w9)列出 .NET 10.0.14 为修复版本；受影响版本的存在本身不证明本应用可被利用，还取决于是否处理相应不可信 Brotli 数据。提交 `d465b49` 在分发发布时固定 `RuntimeFrameworkVersion=10.0.14`，并让包检查拒绝低于该版本的 .NET 10。旧候选被新规则拒绝的收据保留在 `runtime-floor.json`。
3. **默认发布缺少部分许可材料。** 提交 `b5a6b56` 从发布文件的精确依赖版本定位 NuGet 来源，复制 .NET Runtime／WindowsDesktop／WebView2 与 React／ReactDOM 的许可和 notices；校验目标不存在、来源不是重解析路径，最终完整性清单涵盖这些文件。已有 Open XML SDK、jsdiff、react-error-boundary 及完整 Java legal 目录保留。七份新增文件均与实际来源逐文件哈希相同。

只更新包内自包含 .NET，机器安装的 .NET 10.0.12 保持原样。固定 NativeBootstrap 仍依赖本机 Microsoft.NETCore.App 10.0.x x64；界面仍依赖 WebView2 Evergreen，包不负责安装先决条件。

Java 继续使用本机原已审查的 **Oracle JDK 21.0.7+8-LTS-245**，随包保留 LICENSE、legal 及完整所需目录。其许可证是 [Oracle NFTC](https://www.oracle.com/downloads/licenses/no-fee-license.html)，不能表述为 MIT 或 GPL OpenJDK；本轮没有公开再分发或改变工具链信任。该 Java 版本较旧，当前结果不是全依赖漏洞审计或跨机器发布许可结论。

## 验证证据及实际可用范围

证据根目录：[`.verification/workspace-current-bundle-20261109`](../../.verification/workspace-current-bundle-20261109)。只使用有既有创建证明的合成 Profile，未枚举或打开个人 Profile 内容；全部流程 **0 次模型调用**。

| 路径 | 本轮结果与证据 |
| --- | --- |
| 构建 | 普通 Maven Runtime package、既有 JAR 审计、前端构建、自包含 win-x64 发布完成，见 `build-2.log`。Maven 使用 skipTests，不计为 Java 测试通过；前端大 chunk 提示保留。 |
| 最终 ZIP 合同 | **7/7 PASS**：实际解压后换路径全文件校验、拒绝空用户数据目录、拒绝清单越界、拒绝生产身份冒充、拒绝 JAR 变化、迁移后实际内嵌相对来源可打开、重算不可信侧边清单后仍被内嵌 pin 拒绝。见 `package-contract-final.log`。 |
| Word 完整流程 | `run-2` 的两次独立 AssistantApp／WPF／WebView2／Bridge／HTTP／SQLite／JVM 启动均 exit 0。预览取消、原生确认 v1、导入 v2、限定检索、完整读取跨 4096 字符分页的两个版本、逐行对比、旧版原文跳转、重开后再次核对相同内容哈希均通过；结束时仅删除本轮明确记录的新合成文档，原 3 份来源和 3 条问答历史保留。 |
| 已有历史与笔记 | `run-3` 两次独立完整应用启动均 exit 0。核对原问题／检索词／范围／答案／精确引用；编辑未发送草稿后，通过实际 Windows 保存对话框导出原历史答案，再取消另一次保存，文件保持不变；重开后再次一致。笔记 SHA-256 为 `B213270BD168D763E2167FC29E11798709D29E55EACCD68FB4C459E876C8FA5C`。 |
| 实际 EXE 入口 | 产品 EXE 无参数启动已确认主窗口及其直属 Java `/actuator/health=UP`，实际加载包内 .NET 10.0.14 coreclr。见 `actual-exe-startup.json`。这里只做合成工作区启动 smoke，不是物理人工全流程。 |
| 隔离收尾 | 六份用户文档哈希不变，最近工作区记录不变，既有 PostgreSQL PID 集不变，验收端口与自有计算进程全部退出。最终文件、许可来源与机器运行时核对见 `final-isolation.json`。 |

桌面综合流程使用既有 opt-in 手工验收工具启动完整 AssistantApp，文件来自实际解压包，位置含中文和空格。工具只增加缺失的测试程序集等，不替换任何原产品文件；独立验证了全部 915 个产品文件的哈希。另用真实产品 EXE 入口补充无参数启动证据，两者范围分别记录，不把工具入口冒充手工点击主 EXE 的全部操作。

本轮没有新增永久测试或测试框架。首次 `run-1` 因把 framework-dependent 测试宿主与自包含配置组合，未进入托管代码即报 `Could not resolve CoreCLR path`（exit -2147450745）。修正仅涉及验收宿主发布方式，再执行 `run-2`／`run-3`；没有改变产品实现或删除失败记录。

实际 EXE smoke 在空闲状态由本轮持有的测试 Job 结束，**不构成优雅退出证明**。第一份收据记录端口尚未完全关闭；随后只读观察确认 Desktop、Java 均已退出、端口关闭，见 `actual-exe-exit-observation.json`，没有重复终止操作。上述四次综合流程则通过既有应用退出路径结束。

## 使用和限制

包内 `README.txt` 包含启动、切换开发工作区及资料流程说明。完整解压后运行 EXE；Knowledge 支持 DOCX 正文预览确认、导入新源版本、比较与打开对应原文，已有成功回答可从历史导出 Markdown。导入、阅读、对比与已有历史导出无需模型。Word 仅保存文字摘录，不保存原 Word、图片或版式；不支持的公式等会明确拒绝，原 Word 应另行保管。

本轮未运行新的模型回答，不增加整体质量提升结论。未验收 PG 桌面组合、所有页面、任意 Word 文件、跨机器安装、模型通用兼容、个人 Profile 恢复或正式签名／更新／卸载；X1 仍 PARTIAL，Vision 仍 BLOCKED。旧候选包和失败日志保留，交付入口以本报告的 **final** 包为准。没有启动模型、修改既有服务或 KAG 文件，也没有宣称建立跨项目资源锁。
