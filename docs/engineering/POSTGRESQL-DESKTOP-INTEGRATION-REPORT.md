# PostgreSQL Desktop Integration & Real Windows E2E

2026-10-20（Asia/Shanghai）。**PARTIAL：SOURCE READY / FOCUSED VERIFIED；PostgreSQL REAL WINDOWS E2E PASS 尚未达成，关键数据库阶段 PENDING。**

本轮修复了原生数据库窗口回调重入和开发 Runtime 重启后旧 Bearer 仍可复用的问题，实际运行了普通交互式 Windows 中的 Java/SQLite、WPF/WebView2/React、原生配置窗口只读 STATE、重启及失败清理。没有可用的项目安全管理员授权，依照任务要求停止数据库准备；没有尝试未知数据库、复用 B4A6 凭据或生成新开发 ZIP。

## 基线、源码与资产

- 起始分支 `codex/sqlite-long-path-recovery`，HEAD `6ac39023bbc453fd0180cf02d30b33b6ad8f8d4b`；按指定代理 fetch 成功，远端对应分支仍为同一 HEAD。
- 新分支 `codex/postgresql-desktop-integration`。源码本地提交 `031dbdaf147156478aa68cd5dcbcdafd6526b050`；报告与 STATUS 随后独立提交，交付 HEAD 见 Git。
- 开始时 tracked 工作树干净，仅指定八个 B4A6 未追踪文件；逐文件 SHA-256 前后相同，继续未追踪，不纳入提交。
- 已阅读任务指定八份报告、ADR-009/011、实际 Profile/DPAPI/Runtime/Bridge/Store/状态服务、Finance 条件装配及 Workspace V1/V2 路径。历史报告结论原文保留，未用旧 PENDING 否定后续已验证成果。
- 未写入冻结 Finance 仓库或正式用户数据；未运行旧 B4A6 菜单/准备脚本，未改 Native Gate、私有目录策略、原 DPAPI 格式、Finance 身份合同、SQLite 长路径修复或 migrations。

## 直接复用的实现

| 实现 | 本轮处理 |
| --- | --- |
| DatabaseSettingsWindow / NativeDatabaseSetup | 复用原 PasswordBox、严格 STATE/CHECK/SAVE!、Gate 后直接子进程 stdin、固定输出和有界清理；只修复入口调度 |
| TrustedDatabaseConfiguration | 保留 Profile 固定 data/database.dpapi、CurrentUser、CREATE_NEW、不覆盖、文件身份/ACL、写后重新解密校验；未另建配置系统 |
| DevelopmentRuntimeOwner | 保留原直接 Java 子进程、Job、来源 pin、停止等待、显式 RestartAsync、固定诊断及 lifecycle push；重启验证直接经过此 owner |
| WorkspacePostgresqlConfiguration / MemoryStore / ConversationStore / KnowledgeStore | 复用原独立 workspace DataSource 和真实 Store；未改领域语义或增加替代 JDBC 业务路径 |
| Storage Status API / React StorageSettings | 复用原 Native Bearer、固定来源/会话 Bridge、只读实际连接状态、过期响应清理及原生设置入口 |
| Workspace V1/V2 / Finance | 保留显式维护迁移和独立 Finance DataSource；日常 Runtime 不执行 Flyway，Finance 默认关闭 |

## 实际问题与最小修复

**开发 Runtime 的旧 Bearer 未随重启失效。** LocalClientToken 原来在 token 文件存在时直接复用，owner 停止旧 JVM 并不会改变新 JVM 接受的秘密。现在仅 Windows development-profile 启动会先校验已有 token，再使用原随机生成、私有临时文件和原子发布机制生成新的会话 token。格式和认证 filter 不变，其他启动路径保持原持久凭据行为。已有无效 token 拒绝启动且不修复；轮换/发布失败不继续启动。此项是本轮要求的开发会话生命周期修正，不轮换数据库密码，也不覆盖 database.dpapi。

**数据库窗口在 WebMessageReceived 调用栈内同步 ShowDialog。** 真实 GUI 探针点击原 React 按钮后，原生窗口和 STATE 已成功，但 ExecuteScriptAsync 的完成回调被阻塞；UI Automation 确认窗口显示“尚未保存数据库配置；当前默认 SQLite”。正常关闭本次合成窗口后，首次探针最终失败，记录保留为 `postgresql-desktop-native-before-fix.log/.exit`。微软说明 WebView2 回调不支持同步进入模态消息循环，应延后调度：[Threading model / Reentrancy](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model#reentrancy)。本轮仅将数据库窗口通过原 WPF Dispatcher 延后打开，并在执行时重新检查取消、退出及 Busy。保留防重复窗口、会话取消、原生确认和返回焦点；没有修改其他原生页面或降低浏览器策略。最终同一真实流程通过。

测试改动只扩展既有 WindowsPrivateAclTest 综合流程和 RuntimeLifecycleProbe：校验 token 轮换/损坏拒绝、真实 owner 重启与旧 Bearer 401、React 到原生窗口再返回。未新增测试框架或独立 PostgreSQL 配置入口。

## Phase A：新合成数据库的确切阻碍

只读环境检查确认服务 `postgresql-x64-17` 正在运行，服务路径为 `D:\PostgreSQL\bin\pg_ctl.exe` / `D:\PostgreSQL\data`，5432 仅监听 127.0.0.1 和 ::1。这不是 SQL 登录或服务器版本验收。

项目既有管理员安全输入通道的 `.runtime/workspace/b1-admin.dpapi` 不存在；项目运行目录也没有遗留测试 credentials/DPAPI 文件，本机该上下文的默认 pgpass 文件不存在。其输入目录只读 ACL 检查仍满足原脚本的当前用户单条 FullControl/受保护/owner 合同。没有读取、猜测或重设数据库口令，也没有尝试以 postgres 空口令连接。已请求用户通过本机既有安全输入窗口提供所需授权，交付时尚未收到完成信号；不要求用户向聊天提供秘密。

**本轮数据库/角色范围：尚未创建、尚未预留任何目标；无 SQL 登录、CREATE/ALTER/GRANT/Flyway 或业务写入。** 因此不存在可报告为成功的本轮 PostgreSQL 数据库名、应用角色或 schema 初始化。旧 B4A6 数据库/角色没有被访问、重用、改权限或删除。Phase A 在凭据边界停止，后续 PostgreSQL 相关操作不执行。

后续具备本机安全凭据后，仍必须为本轮生成全新唯一名称，先确认实例和名称未占用、目标独立为空，再创建受限 app/独立迁移角色，只执行 Workspace V1/V2 及最小授权；不得运行绑定旧目标的准备脚本。未批准任何 Finance V1–V21、真实 SQLite 迁移或全局配置变更。

## 本轮实际执行与证据

所有以下路径位于忽略的 `.verification/`。使用当前账户 Interactive/Limited 一次性计划任务获得普通交互式上下文，无提权或密码启动任务，任务已全部注销。

| 实际执行 | 结果与范围 |
| --- | --- |
| Maven `-pl workspace-application -am -DskipTests package` | BUILD SUCCESS，含实际 default Runtime JAR audit；`postgresql-desktop-package.log`。仅构建，不计作测试 |
| Profile Debug Desktop / 原 WPF 探针构建 | PASS，零警告/错误；`postgresql-desktop-probe-build.log`、`postgresql-desktop-native-final-build.log`。未生成分发 ZIP |
| 既有 WindowsPrivateAclTest | PASS；`postgresql-desktop-acl.log` 和 Surefire XML。真实 Windows 私有 ACL、开发 token 轮换、损坏 token 拒绝并保留字节，以及原综合流程通过。不是 DPAPI/PostgreSQL 验收 |
| 原 owner `restart` 综合探针 | PASS；`postgresql-desktop-restart.log`。真实 Java/Spring/SQLite/Native Bearer、Storage Status、旧 Bridge session 拒绝；RestartAsync 等待旧进程退出，新 PID 就绪，旧 Bearer 真实 HTTP 401，新会话查询成功，再停止并确认新 Java 退出 |
| 同一新 Profile `gui-close` | PASS；`postgresql-desktop-gui.log`。真实 WPF、WebView2、React/Bridge Ready、Settings 显示 SQLite/Credential Ready，正常 ExitAsync 与所持 Java 清理 |
| 修复后 `gui-database` | PASS；`postgresql-desktop-native.log` / `.exit=0`。真实 React 按钮经原 Bridge 打开 WPF 数据库窗口，原 PasswordBox 存在且为空，原受信子进程 STATE 确认未配置；关闭窗口返回 SQLite 设置页，再正常退出。首次失败与最终成功分开保留 |
| 独立新 Profile `configuration-failure` | PASS；`postgresql-desktop-negative.log`。仅在新合成 Profile CREATE_NEW 一个无效配置文件，真实 Java 返回固定 CONFIGURATION_UNTRUSTED 并退出、owner 清理。不是 PostgreSQL 密码/连接失败的真实验收 |
| React 原 StorageSettings 聚焦文件 | PASS；`postgresql-desktop-storage-ui.log`。真实组件验证 lifecycle 丢失清旧状态、晚到应答拒绝和原生设置调用；未改 React 生产代码，不冒充 PostgreSQL UI |
| 收尾静态/现场检查 | git diff checks 通过；无本次探针进程，18765 无监听。八文件哈希保存在 `postgresql-desktop-b4a6-before.json` / `-after.json`，全部一致 |

新合成 Profile：`b65934093a6242b7b845ac5d0db99ef7` 用于 SQLite、重启和 GUI；`8efffb6fc4cd40ee860245794265dc22` 仅用于无效配置负向启动。均由原原生 Prepare/Provision/Verify 创建；未选择历史 Profile。后者保留失败样本，不能当成正常 Profile 重新启动；本轮不自动删除文件。像素证据使用原 GUI 探针在新 Profile 的 evidence 中 CapturePreview；不声称物理屏幕或人工 Explorer 双击通过。

本轮实际启动的是既有测试 apphost 承载的真实 AssistantApp/DevelopmentRuntimeOwner 和原 WebView2 前端，Java 使用本轮构建的固定来源 JAR；不是 mock Runtime。**尚未运行本轮独立产品 EXE 分发包、无参数 Explorer 选择器或托盘 UI 点击。** 探针调用正常 ExitAsync 不等于托盘用户操作。

未运行全量 Java/Desktop/Frontend、Browser/IME、Finance、Provider/模型、旧 JDBC/PostgreSQL 集成矩阵、完整备份恢复或打包矩阵。初次 React 命令的日志重定向目录写错，npm 未启动；改正路径后才执行并通过。未把未执行、历史成功或一条综合流程中的覆盖点计作新通过。

## 产品验收状态与剩余范围

| 要求 | 本轮结论 |
| --- | --- |
| Native 配置保存 | PENDING。窗口、PasswordBox 和真实 STATE 已验证；没有合成数据库凭据，未执行 CHECK/SAVE!，未点击安全保存确认 |
| DPAPI CurrentUser / CREATE_NEW / 写后解密 | SOURCE READY，复用原实现；本轮正向真实保存未执行，不认领通过 |
| Runtime 显式重启 | FOCUSED VERIFIED：真实 owner、进程退出、旧 Bearer 拒绝及新认证通过；WPF 用户重启确认与 PostgreSQL 配置重读 PENDING |
| Memory | PostgreSQL Runtime 创建/修改/revision/重启持久化全部 PENDING；仅审计原 Store 装配，不使用历史 JDBC 证据替代 |
| Conversation | PostgreSQL Runtime 会话/消息/终态/重启读取全部 PENDING；未调用模型或伪造成功终态 |
| Knowledge | PostgreSQL Runtime 导入/revision/索引/检索/引用/文件删除与恢复一致性全部 PENDING；未绕过私有文件根 |
| React Settings 显示 PostgreSQL | PENDING；本轮实际显示的是 SQLite，不能以组件合成响应算作 PostgreSQL 成功 |
| 退出与同 Profile 重开 | SQLite 探针正常退出与同 Profile 重开通过；PostgreSQL、独立产品 EXE、实际托盘操作 PENDING |
| SQLite 默认行为 | 新合法默认 Profile、真实 Runtime/React/原生设置返回已通过；与新 PostgreSQL Profile 的跨配置隔离 PENDING |
| Finance 隔离 | 本轮实际 Runtime Storage Status 显示 Finance disabled，未提供 Finance 配置、连接或迁移；独立 DataSource/身份源码未改，真实 Finance 身份绑定仍 PENDING |
| 固定启动失败提示 | 真实 CONFIGURATION_UNTRUSTED 失败路径及清理通过；真实 PostgreSQL 连接/密码/角色/schema 失败仍 PENDING，不自动退回 SQLite |
| 人工无参数双击 | 需要补齐，PENDING；前轮 EXE/托盘 PASS 是历史证据，本轮没有重授其结论 |
| 新开发 ZIP | 未生成。数据库关键 E2E 被阻塞，现有包与备份保留；本轮源码修复未写入旧 ZIP |
| REAL WINDOWS E2E PASS | 完整 PostgreSQL 产品链路未达成；不得据此授予 Production GO 或退役历史证据 |

## 后续最少用户操作

1. 如继续本轮数据库验收，在普通本机 Windows PowerShell 运行 `powershell.exe -NoProfile -File "D:\IDEA\Daima\personal-ai-workspace\scripts\verification\request-workspace-postgresql-test-access.ps1"`，仅在该非回显输入处填写现有管理员密码，完成后告知已就绪。该既有脚本只创建当前用户 DPAPI 授权文件，不连接或修改旧 B1 数据库。当前没有可直接重跑的新数据库验收脚本，也不要运行绑定旧目标的 prepare-storage-status-test 或 B4A6 脚本；后续仍须由开发流程创建本轮全新目标。
2. 独立目标和受限凭据准备后，才在新版原生窗口进行连接/角色/schema 检查，分别明确确认保存和重启，并完成三个 Store 的真实综合流。本轮没有自动化输入合成密码，也未新增密码传输通道。
3. 所需源码和集成验证通过后，再生成一次新 ZIP；在普通 Explorer 中无参数双击新 EXE，创建/选择本轮合成 Profile，核对 PostgreSQL/三 Store，托盘退出，再双击选择同 Profile 读取。此项目前保留人工 PENDING，旧包不包含本轮修复，不能作为其替代验收。

## B4A6 Retirement Readiness

八个未追踪文件全部保留：

| 路径 | 用途与候选处置 |
| --- | --- |
| B4A6-Offline.cmd | 历史中文离线菜单启动器；新版日常启动不依赖，具备后续经确认退役的条件 |
| scripts/verification/start-b4a6-offline.ps1 | 依 receipt 推导旧离线菜单步骤；历史脚本候选退役 |
| scripts/verification/inspect-b4a6-offline.ps1 | 旧宿主/ACL/receipt/目标只读审计；历史脚本候选退役或归档 |
| scripts/verification/prepare-b4a6-postgresql.ps1 | 旧 B4A6 专属目标预留/受控创建；不适用于本轮新目标，后续可退役 |
| scripts/verification/configure-b4a6-postgresql.ps1 | 旧 Profile/Compile/Gate/迁移/DPAPI 分步维护；新版配置不依赖，后续可退役 |
| bootstrap/tests/B4A6PostgresqlPreparation.java | 旧受信 Java 维护/迁移/配置探针；新版同 JAR NativeDatabaseSetup 不调用它，后续可退役 |
| docs/engineering/F2.3-B4A6-OFFLINE-EXECUTION.md | 旧离线操作说明、历史失败/恢复约束；适合归档保留 |
| docs/engineering/F2.3-B4A6-REAL-WINDOWS-POSTGRESQL-E2E-REPORT.md | 旧目标和阶段结果；适合归档保留，不改写历史 PASS/PENDING |

检查当前 Desktop/React/Java 生产代码、NativeBootstrap 和开发构建/分发路径，未发现调用上述八文件的产品依赖；分发 README 中保留 B4A6 名称的历史说明不构成执行引用。旧脚本彼此有引用，不应拆散误作新版入口。

旧 receipt 仍为 run `f02e090db2cc4e0fba0ce60aa724b495` / `NATIVE_VERIFIED`；SHA-256 仍为 `FCCD77E62717BCDE2831586FFDABE4496A34AC06B320BA1259E7D936DCE5D9E7`。该记录不能认领本轮配置或 E2E 成功。

根据旧脚本/报告的非秘密名称，未来需用户单独批准清理的对象为数据库 `personal_ai_workspace_b4a6_091d94a30c64`、角色 `paw_b4a6_091d94a30c64_app` / `paw_b4a6_091d94a30c64_migrator`，以及单独审查的旧 Profile/receipt/证据。此为历史清单，未登录数据库核对当前状态，不执行 DROP、NOLOGIN、改密码或删除文件。

**新版日常产品已无旧菜单依赖；本轮 PostgreSQL 验收尚不足以解除对 B4A6 历史证据的保留需求。** 可退役的是未来经用户确认的旧执行工具；报告、receipt 和必要证据应先归档。本轮只列清单，到此停止。
