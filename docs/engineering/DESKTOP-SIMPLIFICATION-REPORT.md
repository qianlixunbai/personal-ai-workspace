# PERSONAL AI WORKSPACE DESKTOP SIMPLIFICATION REPORT

2026-10-19（Asia/Shanghai）。**实现、聚焦合同和构建通过；新版真实 Windows E2E / Production GO PENDING。**

## 保留的基线

从 `codex/f2.3-b4a6-real-postgresql-e2e`、HEAD `2d161b4b1b95cb021385cc3e17d9f52611874f49` 创建
`codex/desktop-setup-simplification`。开始时 tracked 工作树无改动，存在 8 个 B4A6 未追踪文件，全部保留且未纳入本轮提交。
未 stash、clean、reset、覆盖 checkout、push、merge、tag 或修改 Git 配置。

只读核对 `.verification/f2.3-b4a6-configuration.local.json`：Profile `f02e090db2cc4e0fba0ce60aa724b495`，
真实 phase 为 `NATIVE_VERIFIED`，Prepare / Provision / Verify 成功历史保留，另有 Compile precheck 失败恢复审计；**Compile 未通过**。
receipt SHA-256 为 `FCCD77E62717BCDE2831586FFDABE4496A34AC06B320BA1259E7D936DCE5D9E7`。
原始 8 文件、receipt、Native Gate、WindowsPrivateAcl 和正式 launcher 在首尾按哈希核对。

没有连接或修改 PostgreSQL，没有新建数据库/角色、重设密码、Flyway、读取正式 SQLite 或迁移源文件。
B4A6 合成数据库及既有 app/migrator 角色保留；冻结 `personal-finance-OS` 只读 status 干净。
未修改已有 Profile、ACL、备份、恢复脚本或历史报告。没有读取或导出真实数据库密码。

## 新的开发配置路径

1. 使用既有 `build-trusted-development-runtime.ps1 -JavaHome <已审查的本机 JDK21>` 构建一次。
2. 直接打开生成的 Profile Debug Desktop EXE。无参数时原生选择器提供创建独立开发工作区或选择已有 Profile；原显式参数入口仍兼容。
3. 创建前明确确认 Development-only、本机构建信任及仅新目录私有权限；复用原 Prepare / Provision / Verify。失败保留新目录，不重用历史 run、不修复已有 ACL。
4. SQLite 默认行为沿原启动链保留。在 Settings 点击“重新检查存储”，再打开“原生数据库设置”。
5. 在原生窗口输入本机端口、数据库、受限应用角色和 PasswordBox 密码。“检查连接与结构”只读核对连接、角色、Workspace V1/V2 与 Knowledge。
6. “安全保存”单独确认后再次执行真实 preflight；通过才使用原 DPAPI CurrentUser / CREATE_NEW 保存并重新解密校验。结构未准备时拒绝保存，不执行初始化。
7. “重启 Runtime”再单独确认中断风险和目标存储。仅停止原 owner 的直接 Java 子进程，等待退出后启动同一路径；关闭设置窗口后重查存储。

选择已有 Profile 不要求用户输入 run-id、receipt phase、Compile 或 PowerShell 命令。
创建/配置只限 Development-only，不授予正式安装/生产来源信任。当前构建依赖本机 JDK、绝对来源 pin 和已存在的 .NET/WebView2 等开发前提，尚非可移植安装包。
已有 DPAPI 配置只检查是否可解密，**不覆盖、不删除、不编辑**。已配置时的实际连接结果在主设置页重查；凭据轮换不在本轮范围。
Finance 明确关闭；迁移、正式备份恢复按钮继续禁用。

## 代码与安全合同

| 位置 | 实际变化 |
| --- | --- |
| Desktop `Program.cs` / `Mmf3ProfileNative.cs` | 在原 WPF 入口选择/创建开发 Profile，复用固定 helper payload 验证及原 Provision；没有第二个启动器 |
| `DatabaseSettingsWindow.cs` | 原生安全输入、状态、连接检查、保存与重启确认；关闭/会话失效取消操作并清除 PasswordBox |
| Desktop `NativeDatabaseSetup.cs` | 同一已 pin 的 JDK/JAR、原 Gate、直接子进程、匿名管道；准入前不发送秘密；有界输出和 45 秒操作期限，Job 与退出清理 |
| Java `NativeDatabaseSetup.java` / application main | 同一 JAR 的固定原生维护入口；原 admit / requireRuntimeArguments / profileRoot 成功后才读输入；复用 TrustedDatabaseConfiguration.install/create/load |
| `TrustedDevelopmentRuntimeSources.cs` | 将已有固定启动参数集中到原来源持有者；manifest v2 增加被原 Provision 使用的 JNA JAR 哈希 pin，不接受运行时传入的预期哈希 |
| `DevelopmentRuntimeOwner.cs` | 明确重启；旧子进程退出后才清空进程/诊断/凭据状态并重新启动；认证观察绑定当次运行，旧观察不取消新运行 |
| `AssistantApp` / Bridge / React StorageSettings | 新增严格空 payload `native.openDatabaseSettings`；只有开发 owner 报告原生入口可用。普通/Release 宿主拒绝执行 |
| 原开发构建脚本 | 删除重复 JAR 条件检查，复用现有完整 Runtime JAR audit；生成 manifest v2；不是 Compile Recovery 或新 receipt 流程 |

数据库密码不进入 React、WebView2 state、普通 Bridge payload、HTTP、Java 命令行、环境变量、日志或临时文件。
配置只经本次直接子进程 stdin，最多 16 KiB；操作 STATE/CHECK/SAVE! 为固定协议，stdout 仅固定枚举，无异常自由文本。
WPF 发送前以及 Java 读取前均完成各自原准入；运行来源 pin 持有到配置子进程清理结束。
输入 JSON 字节和原提供层加/解密字节清零。JDBC/JSON 所需 .NET/Java 字符串仍在进程内存，未承诺取证级擦除。

原 `TrustedDatabaseConfiguration` 的持久格式不变，仍为 format 1 / 固定 Profile data/database.dpapi；
Owner、protected DACL、Win32 identity、无 reparse/硬链接、禁止写入/删除的读取句柄、CREATE_NEW 和失败拒绝沿原实现保留。
没有修改 Native Gate、WindowsPrivateAcl、Finance 身份、DataSource 工厂、Workspace V1/V2 或业务 Store。
没有新增 HTTP 配置写入接口；Java main 的新分派仅包裹原固定参数校验，没有放宽 Gate 的参数列表。

Phase B 另在 React 根部集成 `react-error-boundary 6.1.6`，固定错误提示和用户主动重新打开，屏蔽 caught exception 原文的 console 输出。
许可证随 Desktop 的 `ThirdPartyNotices` 复制；WebView 资产白名单未扩大。细节见报告 B。

## 本轮实际验证

| 已执行 | 结果与证据 |
| --- | --- |
| `TrustedDatabaseConfigurationTest` 聚焦类 | PASS，`.verification/desktop-simplification-java.log`；复用原格式/ACL/身份/DPAPI/装配测试，新增一个原生管道综合合同 |
| 新增管道合同最终定点重跑 | PASS，`desktop-simplification-native-pipe-final.log`；Gate 拒绝不读取输入、只读检查不保存、结构缺失拒绝保存、成功 CREATE_NEW 重读、已有配置不覆盖、固定无秘密输出 |
| Desktop StorageStatusBridgeTests + 既有两个 Bridge 合同 | PASS，`desktop-simplification-desktop.log`；仅相关 origin/session/空 payload 与状态行为，包括拒绝密码 payload |
| React StorageSettings + SettingsPage | 既有 SettingsPage 首次 PASS；新存储流程夹具初次失败，拆分异步应答后 StorageSettings 聚焦重跑 PASS，日志 `desktop-simplification-react*.log` |
| WorkspaceErrorBoundary 故障用例 | PASS，`desktop-simplification-error-boundary.log`；真实组件捕获渲染异常，固定恢复提示，不显示合成私密异常文本 |
| 原工程构建脚本 | PASS，`desktop-simplification-build.log`；Maven package skipTests、default JAR audit、TypeScript/Vite/assets、带来源 manifest 的 Profile Desktop |
| Phase B 最终前端构建 | PASS，`desktop-simplification-frontend-build-final.log` |
| 最终 Profile Desktop 编译 | PASS，零警告/错误，`desktop-simplification-desktop-handoff-build.log`；包含重启观察防护与有界清理；许可证已复制 |
| 原 helper 只读 VerifyProfileNative | **STOP / PRIVATE_ACE**；当前 B4A6 Profile 准入未成立，未继续访问数据库或启动真实 Java Runtime |

Java 合同中的 Gate、DPAPI、OS ACL/identity、数据库 preflight 使用既有 mock 风格；文件 CREATE_NEW/字节保持为临时文件实际操作。
这不是 OS DPAPI 或 PostgreSQL E2E。Bridge/React 也属于合同/组件验证，不冒充真实网络或 WebView2 验收。
初次第三方许可证放在前端 public 时，被原资产白名单拒绝；改为随 Desktop 单独分发后构建通过，未放宽白名单。

未运行 Finance 全量、备份恢复矩阵、GPU/Vision、Browser、完整 MMF-3、全量 Java/Desktop/Frontend、发布或安装包矩阵。
没有重复已绿的无关验证。B4A5 的真实 SQLite / WPF / Java / React 证据为历史证据，本轮未重授其结论。

## 真实运行阻断与剩余条件

**开发版二进制已构建，新的正向 WPF / Java / DPAPI / PostgreSQL 配置链尚未真实验收。**
原 helper 在当前环境仍拒绝 PRIVATE_ACE。未忽略额外 ACE、删除 Sandbox 权限、改变服务/用户组、提升权限或关闭 Codex 防护。
普通用户独立运行与受信验收环境应与开发工具职责分离；现有不合格父目录不能仅靠退出 Codex 自动变为合格。
需环境管理方提供满足原合同的独立普通用户受信环境，再通过同一原生入口完成一次合成数据综合流。
如果仍有额外 ACE，继续拒绝；本轮没有 ACL 修复授权或自动修复方案。

PENDING：新版真实 Profile 创建/选择、SQLite 启动回归、WPF 原生输入到 Java 的成功 DPAPI 保存、真实重启和取消/会话失效、
PostgreSQL DataSource 与 Memory/Conversation/Knowledge 端到端；Finance Binding、可信发布/安装、跨设备恢复与 Production GO。
真实 schema 初始化仍需另行明确授权；原生设置不会替用户执行 Flyway，不会绕过 B4A6 目标身份/权限合同。

## 旧 B4A6 工具处置与最少操作

`B4A6-Offline.cmd`、start/configure/inspect/prepare 脚本和 `B4A6PostgresqlPreparation.java` 原样保留，
标记为历史工程维护路径，不是日常产品依赖；未移动、修改或删除用户未提交成果。
历史 Compile Recovery 与备份边界保留，不再新增 R1e/R1f/R2 等脚本，不强行重试 Compile。

源码/构建侧无需用户再修 receipt 或编译 Java helper。合法独立运行环境具备后，用户只需打开已生成 Desktop、选择 Profile，
在 Settings 使用原生配置窗口，分别确认保存和重启。尚无 schema 时，先独立批准受控初始化；正式个人数据迁移仍不在本轮授权中。
精确交付 HEAD 以本分支 Git 记录为准；本轮成果可整理为本地提交，8 个原 B4A6 未追踪文件继续保留在工作树。
