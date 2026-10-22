# Finance F-next-1 — 原生身份绑定与只读账户

2026-10-22（Asia/Shanghai）。**功能已实现；真实 WPF/WebView2 集成流、重开与 SQLite Profile 隔离 PASS。独立产品 EXE 的最终人工显示/物理退出验收 PENDING；不是 Production GO。**

## 后续人工验收反馈（F-next-2 同步）

用户已在本机打开 F-next-1 合成开发客户端，在 Settings 刷新并确认账户，从物理系统托盘退出后重新打开，确认账户数据仍正常。记录为 **F-next-1 Development Human Acceptance PASS（用户人工反馈）**。这是本轮收到的人工反馈，以下原执行记录中的 PENDING 保留其历史含义；不代表 Production GO，也不替代未执行的完整测试矩阵。

## 范围与基线

- 从 `codex/postgresql-desktop-integration @ 24fa3817caeb93430c14fdab091f71528eb1b63b` 新建 `codex/finance-f-next-1`；没有 merge main。
- 冻结 Finance 来源保持 `zh-cn @ c6c87a0627b746e70a45b1c7896f63defd76a810`、工作树干净，未在该仓库构建或写入。
- 起始八个未跟踪 B4A6 文件保留，不加入本轮提交。历史数据库、Profile、DPAPI、备份及真实财务数据未迁移、覆盖或删除。
- 沿用 2026-10-19 开源评估中的既有错误边界、进程所有权与隔离思路；没有引入依赖、完整 Finance Web App、Agent、MCP、Vision 或 AI 金融工具。

## 实现

`Settings → Finance · 只读账户` 经 typed bridge `finance.overview`、Native RuntimeClient、Java `FinanceAccountQueries`、独立 Finance DataSource 读取首个 CNY 账户及 CNY 总余额。账户编号、名称、类型、状态、金额均来自服务端 DTO。BigDecimal 转十进制字符串，React 不求和、不转浮点数；无 CNY 账户、未绑定、身份失效、不可用和关闭状态分别显示。

`native.openFinanceBinding` 只打开 WPF 窗口，payload 必须为空。端口、数据库、只读角色和 PasswordBox 凭据只在原生窗口输入。固定来源 Java 直接子进程先通过原 Native Gate，再通过有界 stdin/stdout 会话读取数据库、服务器、数据集指纹及 ACTIVE 用户的姓名、邮箱、创建时间、历史指纹。候选无默认选择；用户选定后仍须在原生 MessageBox 明确确认。

确认仅提交该子进程所持服务端快照的索引。`confirmHistoricalBinding` 在只读 REPEATABLE_READ 事务中重新读取并比较历史身份，再调用 `createConfirmed`。没有 HTTP/AI 确认路由，不从 Windows SID、Workspace Profile ID、React userId 或 Native Bearer 推断 Finance 用户。

绑定仍使用 `finance-identity/binding.json` 的既有 format 1、CREATE_NEW、不覆盖及失败后不自动重绑语义。修复了历史 Windows 发布缺口：Windows JDK 的 `BasicFileAttributes.fileKey()` 为空，现复用已有 Win32 volume/file identity 校验，并在读取期间禁止共享写入/删除；没有放宽 ACL、链接或 Native Gate 校验。

Finance 连接单独保存为当前 Profile 的 `data/finance.dpapi`，复用可信配置的 DPAPI CurrentUser、CREATE_NEW 首次创建、私有 ACL 及句柄复验。原 Workspace `database.dpapi` 格式与行为保留。没有 Finance 配置时，受控 Windows 启动将 `workspace.finance.enabled` 保持关闭；存在经原生确认创建的配置时显式启用，`workspace.finance.flyway.enabled` 始终关闭。禁止与 Workspace PostgreSQL 使用同一个 URL；核对流程也拒绝含 Workspace schema 的目标。

每次账户查询先要求合法绑定，然后在同一个 Finance 只读 REPEATABLE_READ 事务中重读集群/数据库身份、ACTIVE 用户及历史指纹，再按服务端用户 ID 读取账户和余额。保留 `financeDataSource`、`financeTransactionManager` 和既有查询服务；没有跨数据源伪原子事务或金融写入。

确认后的显式 Runtime 重启曾遭遇 Windows 在旧进程退出后短暂仍报告监听端口的竞态。修复只在显式重启时短暂等待端口释放；超时仍由原准入拒绝，不连接、复用或结束其他监听进程。

## 合成环境

最终验收使用本轮新建独立 PostgreSQL 17 集群：

| 项目 | 最终合成目标 |
| --- | --- |
| 监听 | `127.0.0.1:55423` |
| 私有集群目录 | `D:\PersonalAiWorkspace-Fnext1\939355624cdf` |
| Finance 数据库 | `fnext1_finance_939355624cdf`，V1–V21 |
| Workspace 数据库 | `fnext1_workspace_939355624cdf`，独立 schema `workspace`，V1/V2 |
| Finance 应用角色 | `fnext1_reader_939355624cdf`，无超级用户/建库/建角色权限，必要 SELECT 与数据库身份函数权限，默认只读 |
| Workspace 应用角色 | `fnext1_writer_939355624cdf`，独立授权 |
| 最终绑定 Profile | `86705e85cb5b4a22869dd34849e0c07b` |
| 合成用户 | Synthetic Alice / Synthetic Bob，`example.invalid` 邮箱 |

`prepare-finance-next-test.ps1` 与 `FinanceNextPreparation.java` 只接受新集群的限定端口、管理员名称、实际 data_directory 和新名称；迁移前验证目标不存在。故障注入只操作带本次合成标记的对象。凭据随机生成，仅留在本机私有目录的 DPAPI 文件；没有上传凭据或 DPAPI。

此前 `55422` 合成集群及失败准备目录属于本轮测试资源，保留数据；不删除旧数据库来伪装干净状态。LocalAppData 的 EFS 环境导致首次 initdb WAL 文件操作失败，随后采用未加密的专用 D 盘目录与私有 ACL；没有修改已有目录权限或读取历史管理员凭据。现有 PostgreSQL 服务与 B4A6 数据库未参与本轮迁移。

## 实际执行证据

下表中的 PASS 是本轮执行结果，不是计划覆盖点计数。日志与图片位于忽略的本机 `.verification` 目录，不上传私有材料。

| 执行 | 结果与边界 |
| --- | --- |
| `FinanceAccountQueriesTest,FinanceRouteSecurityTest,TrustedDatabaseConfigurationTest` 聚焦 Maven 命令 | PASS；既有身份、事务、查询和可信配置检查。随后 Native 协议诊断改动由编译与真实集成流验证 |
| frontend `client.test.ts` | PASS；复用既有 typed client 测试 |
| Desktop `PayloadMustBeExactlyEmptyAndNoArbitraryUrlPathOrExecutableIsAccepted` | PASS；扩展已有测试，拒绝 Finance 的 userId、confirmed、路径、凭据等 payload |
| Java package / frontend typecheck + build / Profile Debug Desktop build | PASS；没有运行全仓库套件 |
| `fnext1-e2e-final.log`，`gui-finance` | PASS，正常退出。真实 AssistantApp、WPF、WebView2、React、Native Gate、HTTP Runtime 与 PostgreSQL，不是 Mapper-only 验收 |
| `fnext1-e2e-reopen-visible.log`，`gui-finance-reopen` | PASS；独立进程重开，绑定文件哈希、账户显示、Memory、Conversation、Knowledge 持久化和检索保持 |
| `fnext1-e2e-isolation-final.log`，新 SQLite Profile | PASS；Finance disabled，无绑定、无 PostgreSQL Profile 的三类数据 |
| `fnext1-account-evidence.png` | 已人工检查截图：首个 CNY 账户、类型、ACTIVE、账户余额与服务端总余额可见 |
| 独立发布的实际 `PersonalAiWorkspace.Desktop.exe` 自动化 smoke | **PENDING**。已启动至设置页；自动化未在等待期内识别金额，失败后已清理本次进程，不能宣称 PASS。后续调整/重启操作被自动审批审查以 `blocked by policy` 拒绝，未换通道重试 |

完整集成流实际验证了：原生服务端候选核对与确认；账户 `9007199254740993.10 CNY`、总余额 `9007199254740993.30 CNY`；Alice 访问 Bob 账户返回 404；HTTP 绑定/确认路径不存在，userId 查询参数被拒绝；缺失及无效绑定拒绝读取；历史邮箱改变拒绝；使用另一个数据库 OID 的合成克隆替换同名目标时拒绝；空 CNY 账户状态；只读角色暂时禁止登录后的不可用及恢复；再次绑定被拒绝且原文件不变。

Finance 开关隔离在自有合成 Profile 中暂存 Finance 配置、重启至 disabled 后验证。绑定文件保留；恢复配置重启后恢复合法读取。Memory 创建与正文持久化、Conversation 创建/重命名/归档/恢复及持久化、Knowledge 上传/解析/检索/持久化均实际执行。没有据此声称重新验收所有三类模块的 React 编辑流程、AI 推理、IME、完整备份恢复或所有金融业务。

失败尝试保留在本机日志：原生协议 CRLF/LF、重启端口释放、合成隔离测试分页参数超出既有上限等均已定位并修复；没有 Disabled、削弱业务断言或修改生产行为迎合错误 fixture。未重跑原 Finance 的交易、投资、并发、replay、导入全套及完整打包矩阵，这些领域代码与迁移没有改变。

来源核验还发现既有 provenance 的 Java `workspaceSha256` 是原 CRLF 采集值，而当前 Git baseline/工作树为 LF：三个文件与基线相同，且与冻结来源按换行归一后完全一致。本轮不重写该历史记录或格式化文件。V1–V21 SQL 实际字节与记录的 SHA-256 全部一致。

## 本机开发版运行

现成目录：`artifacts/PersonalAiWorkspace-finance-fnext1-development-20261022`。双击其中 `Open-Synthetic-Finance.cmd`，等待 Runtime 就绪后进入 Settings，点击“只读刷新账户”。该入口明确打开上表合成 Profile。绑定已完成，无需再次录入密码或重新绑定。

这是依赖本机 .NET 10、WebView2、已 pin JDK/Runtime JAR 的开发目录，manifest v2 保留绝对来源；不是跨机器分发包、签名安装器或 Production Trusted。不要复制 DPAPI、token 或私有 Profile 到 GitHub。修改 Runtime 后按原脚本重建来源 manifest 和 Desktop：

```powershell
pwsh -File scripts/verification/build-trusted-development-runtime.ps1 -JavaHome D:\MC\JAVA\JAVA21
```

新工作区使用原开发版选择器 `--choose-development-workspace`，首次 Finance 接入须在原生窗口填独立数据库凭据、核对服务端候选并明确确认；不要把密码发到聊天。不要为解决错误删除绑定或覆盖现有配置。

最少剩余人工步骤：打开上述合成入口 → 等待就绪 → Settings / 只读刷新账户，核对金额及 ACTIVE → 从物理托盘退出并重开。**这一最终产品 EXE 人工关卡仍 PENDING。** 真实 Finance 数据的首次用户确认、跨设备绑定恢复与 Production GO 均未执行或授予。

下一阶段先逐步加入 Finance 独立导航、账户列表/选择和服务端分页，继续使用 typed native transport、服务端用户 scope 与精确金额。账户只读范围验收稳定后再单独设计必要金融写切片；写入继续复用原 Service、receipt、幂等、重放和回滚合同，不在 UI/LLM 层计算金融 effect。

Git 交付只包含本轮源码、聚焦验收工具及本报告；最终提交和正常 Push 的 SHA 以交付回复和实际远端核验为准。未 merge main、force push、tag 或 Release。
