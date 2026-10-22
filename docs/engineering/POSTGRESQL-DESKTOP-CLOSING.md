# PostgreSQL Desktop Integration — Development Closing

2026-10-22（Asia/Shanghai）。**开发源码收尾；当前 final 的完整物理托盘退出/重启人工关卡 PENDING；Production GO 未授予。**

## 交付范围与提交来源

GitHub 目标：[codex/postgresql-desktop-integration](https://github.com/qianlixunbai/personal-ai-workspace/tree/codex/postgresql-desktop-integration)。只正常推送开发分支，不 merge main、force push、tag 或 Release。最终提交/远端 SHA 以本轮交付时的 Git 核验输出为准，本文不自称包含自身提交 SHA。

- 起始 HEAD：`38c84484f0dfb9490dbe8a4a4edeef37a91a8661`，执行前已核对实际工作树。
- 已有 `e2a64d9026c90427a88f57117326b763d30e9726`：PostgreSQL 维护入口/探针、ModelStateStore ACL 条件修复、限定 Ollama 0.40.6 文本准入及 Conversation admission 超时；已有正确提交不重做。
- 本轮 `6857ca8e0f4da1fa773e68047bab803baaaad6f4`：原选择器初始目录、托盘切换、仅 ID 的成功工作区记忆、失败恢复入口、Runtime 重启代次隔离及使用说明。此前未追踪的 `DevelopmentWorkspaceSelection.cs` 已纳入源码。
- Closing 文档、STATUS、完整历史报告及 [Finance 交接](FINANCE-INTEGRATION-HANDOFF.md) 单独提交；原八个 B4A6 文件保持本地未追踪。

## 已验证范围与证据层级

| 范围 | 证据及限制 |
| --- | --- |
| PostgreSQL 17 / 独立数据库 / Workspace V1、V2 | 既有真实准备与迁移证据；本轮不重新建库、跑 Flyway 或登录数据库 |
| Memory / Knowledge / Conversation | 既有真实持久化、revision、检索、成功/失败/取消终态、重启/重开、旧 Bearer 拒绝证据 |
| Ollama 文本 | 既有 0.40.6 + 已 pin 4B/llamacpp 真实推理；不扩展到 9B、任意模型/服务器、Vision 图片或新 GPU 合同 |
| Windows 自动化 | 既有 WPF/WebView2/React/DPAPI/Native Bearer、真实 Explorer 无参数启动、托盘菜单自动化、SQLite 隔离和 Finance disabled；final 另有显式 Runtime 重启与进程清理证据 |
| 用户人工确认 | 用户已双击 **final** EXE，无手动选 Profile 自动进入 PostgreSQL；确认连接、V1/V2 Ready 与历史 Conversations 可见 |
| 尚未独立明确人工确认 | **同一 final 版本**物理托盘退出 → 再次双击 → 同一 Profile/历史数据。不能以自动化结果或旧版本人工操作替代 |

完整日志索引、失败记录及版本复用边界见 [最新集成报告](POSTGRESQL-DESKTOP-INTEGRATION-REPORT.md)。该报告 2026-10-21 及更早段落作为历史保留，不将旧 PENDING、自动化结果或前次包人工证据改写为本轮完整人工 PASS。

本轮实际新增验证只有：Git diff/staged 检查、下面的 DLL/ZIP 来源核对和只读调用链审查。复用已有聚焦测试 `CompiledVariantIdentityAndCrossModeArgumentsAreIsolated`、`StartupDiagnosticsUseOnlyExactBoundedProtocol` 及结构化 Windows PASS；本轮没有重新运行这些测试、真实 AI/数据库流、安装包矩阵或全量套件。没有新增测试或生产修复。

## 已验证 final 包与新 Git HEAD 的关系

保留目录 `artifacts/PersonalAiWorkspace-remember-workspace-development-20261021-final` 及同名 ZIP；375656904 bytes。
ZIP SHA-256：`13a1df658814018a1314cf80db54056478284900d191941c8a618232cce6ae7f`。

包 manifest 仍如实是 `38c84484f0dfb9490dbe8a4a4edeef37a91a8661` / `sourceDirty=true`，对应构建时尚未提交的选择/记忆源码。它不是从本轮新的 Git HEAD 重新构建的包，不改写清单或重命名冒充新构建。

本轮在提交源码前，以原 final 的内嵌来源 manifest、Debug/win-x64/self-contained/无符号参数做一次 Desktop 聚焦构建：**Desktop.dll 与 Core.dll 的 SHA-256 都与 final 包对应文件一致**；没有重建 Java/React、publish、生成新 ZIP 或启动产品。工作区到代码提交只发生 Git 收录，随后仅文档变化。Java/React 无本轮源码差异；包内资产与源码提交的关系无需通过再次运行推理或重打 375 MB 包证明。

本地证据：`.verification/postgresql-closing-package-parity-build.log`；该日志与私有验证目录不提交。包仍 DEVELOPMENT_ONLY、unsigned、Production GO=false，不随源码 Git 上传。

## B4A6 Retirement Readiness

**技术上具备旧执行工具退役条件；本轮仅准备，不删除、不正式执行退役。** 当前 Desktop、Java 生产代码、NativeBootstrap 和开发构建/分发路径未引用旧 B4A6 runner、Java preparation、旧 run receipt；分发说明中的历史名称不构成执行依赖。原生 Gate 的保留也不等于依赖旧 B4A6 receipt。

| 资产 | 本轮处置 / 未来动作 |
| --- | --- |
| `B4A6-Offline.cmd`、`start-b4a6-offline.ps1` | 保留未追踪；未来可整体停用旧离线菜单，不能作为新版启动入口 |
| `inspect-b4a6-offline.ps1`、`prepare-b4a6-postgresql.ps1`、`configure-b4a6-postgresql.ps1`、`B4A6PostgresqlPreparation.java` | 保留未追踪；只供历史维护，未来经独立批准整体归档/退役，不拆散脚本相互引用 |
| 两份 B4A6 未追踪报告 | 永久保留历史内容；未来归档应保留相对路径、原 SHA-256、失败/PENDING 及目标说明 |
| 旧 receipt、run/Profile 映射、必要 ACL/Compile/恢复证据 | 保留原件；历史报告记录旧 run `f02e090db2cc4e0fba0ce60aa724b495` 为 `NATIVE_VERIFIED`，不是新版运行证明。本轮不重新签发、修复或运行旧 receipt |
| 旧数据库、角色、Profile、备份、ZIP | 完全保留；没有连接或查询其当前状态，不执行 DROP、NOLOGIN、迁移、口令变更、删除或覆盖 |

旧对象名称与八文件逐项清单见 [集成报告的 B4A6 Retirement Readiness](POSTGRESQL-DESKTOP-INTEGRATION-REPORT.md#b4a6-retirement-readiness)。退役后续步骤：先补齐 final 物理生命周期人工确认；再在本机私有位置生成经检查的归档清单并验证副本；保留报告/receipt/必要证据；只有另获明确清理授权才处理旧执行工具或数据库。不得将私有归档、密文或 receipt 直接上传 GitHub。

## 资产保护与下一步

本轮保留 `paw_pgdesktop_5e3ccc3d5b17`、PostgreSQL Profile `55be9b5e4c414064bdfa817f5e4d8b8a`、所有 SQLite Profiles、既有 DPAPI、Ollama 模型/设置、Finance 数据、历史备份/包。操作仅本地源码/Git/文档、聚焦编译及远端分支交付；没有数据库、模型或用户数据操作。

原八文件前后哈希、本轮 Finance 仓库 HEAD/工作树记录保留在忽略目录。本轮提交文件清单采用明确 allowlist，不收录 Profile、Bearer、database.dpapi、私有日志、临时验证材料、ZIP 或原八个 B4A6 文件。

下一轮先做 [Finance 身份与只读账户纵向切片](FINANCE-INTEGRATION-HANDOFF.md)，不把本阶段成功推导为 Finance 绑定、数据库合并或金融写入 GO。人工只需在现有 final 托盘退出后重新双击，确认仍回到 PostgreSQL 和原历史，再反馈该物理流程结果。
