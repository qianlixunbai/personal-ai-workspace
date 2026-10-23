# Finance F-next-6 — Investment Positions 只读集成

2026-10-24，Asia/Shanghai。**Development-only；列表、详情、真实 Windows 综合流、双向用户隔离和独立客户端验收通过。不是 Finance Freeze、Production GO 或正式 Release。**

## 实际交付与范围

基于 `codex/finance-f-next-5 @ 5f4ac444173deb91e09e8b995283e1d4c02525b4`，开始时已按指定代理 Fetch 成功，确认本地与远端基线一致。新分支 `codex/finance-f-next-6`，原八个 B4A6 未追踪文件保持原状态，不加入提交。

Finance 内新增 `Investment Positions · 投资持仓`，沿用当前导航、主题、布局和原生窗口：

- 默认 OPEN，支持 CLOSED / ALL、Account ID / Instrument ID、每页数量、手动刷新、空状态、错误、重试。
- 使用原服务返回的不透明 Cursor 前进；上一页只保存服务端给过的返回位置并重新查询，不保存旧页快照，不转换成 offset。筛选、每页数量及刷新从首页开始。内存最多保留 100 页返回位置。
- 点击行重新请求详情；展示 Position、账户、Instrument、状态、精确数量、平均成本、总成本、累计已实现盈亏，以及分开的人工参考和缓存市场参考估值。
- 查询失败、身份或 Runtime 变化清除显示快照；session 变化丢弃迟到结果。Runtime 恢复后重新验证身份及绑定，不自动重绑。

不增加金融写操作、实时行情调用、行情刷新、交易重放或组合计算。Portfolio 统计留待下一阶段：原聚合以 CNY 汇总 transaction-driven 投影，另有 OPEN 估值覆盖数、PARTIAL 与 warning 聚合；本轮没有必要扩展其 DTO、桥接及页面，也没有用当前页数据制造替代统计。

## 来源领域审查与 provenance

冻结来源 `qianlixunbai/Personal-Finance-OS @ c6c87a0627b746e70a45b1c7896f63defd76a810`，本轮前后 HEAD 相同、工作树干净。实际阅读：

- `InvestmentPositionReadQueryService`、`InvestmentPositionDetailQueryService`、`InvestmentPortfolioQueryService`、`InvestmentReadController`。
- `InvestmentReadMapper` 的持仓、ownership 和 portfolio SQL；`InvestmentReadRow`、查询条件、DTO、`InvestmentReadSupport`、`CursorCodec` 及 Cursor records。
- `InvestmentPositionBindingService`、Instrument 定义、`InvestmentCommandTransactionalService`、`InvestmentLedgerCalculator`、`InvestmentReplayEngine`。
- V4/V5/V6/V7/V8/V9/V10/V12/V13 等持仓、精度、绑定、不可变事实与 receipt 约束；原 `InvestmentReadQueryPostgresIntegrationTest` 的分页、LEGACY 排除、详情、跨用户与缓存估值断言。
- F-next-5 已迁入的 Asset、Quote / FX 查询及 `ReferenceValuationService`，确认只装配缓存读取，不装配 Provider/refresh 服务。

本轮复制 17 个实际编译所需的原文件，逐文件源路径、目标路径及 SHA-256 在 [investment-source-provenance.json](../../finance-domain/investment-source-provenance.json)。包括原列表/详情服务、DTO、Cursor、Mapper 及其直接依赖；原 Mapper/Codec 的传递类型也保留原字节，不对它们开放额外路由。Git 属性禁止换行转换。既有估值来源清单继续使用 [asset-source-provenance.json](../../finance-domain/asset-source-provenance.json)。

原 CursorCodec 使用 Jackson 2。为保持原编码实现，增加 BOM 管理的 `com.fasterxml.jackson.core:jackson-databind`，最终 JAR 为 2.21.5；Workspace HTTP 继续使用既有 Jackson 3。没有新框架、Provider SDK、第二个 Finance 进程或构建对冻结仓库的路径依赖。

关键来源规则：

| 事项 | 已核对结论 |
| --- | --- |
| 持仓身份 | `assets.id` 是 Position ID；TRANSACTION_DRIVEN 对 `(user_id, account_id, instrument_id)` 有部分唯一索引。同一 Instrument 可以在不同账户形成不同 Position。 |
| 所属关系 | Account / Instrument 通过包含 user_id 的外键及查询 JOIN 绑定。读取显式限制当前绑定用户；不采用客户端 userId。 |
| 模式 | LEGACY 是历史人工资产，不能当作账本投影；投资列表/详情只查询 TRANSACTION_DRIVEN。ALL 指 OPEN 和 CLOSED，仍排除 LEGACY。 |
| 状态 | 读取存储的 position_status，不按价格或前端数量推断。来源命令投影在零数量/零剩余成本时 CLOSED；正数量持仓 OPEN。账户或 Instrument 停用不删除历史持仓，也不阻止只读展示。 |
| 币种 | 当前来源写入、opening migration 和账本事实采用 CNY；Instrument.quoteCurrency 是报价元数据，不是账务成本的币种。页面明确写出当前来源 CNY 口径，不进行跨币种成本合计。 |

## 金额、成本、盈亏及估值

所有账务字段直接使用原查询服务对投影的读取结果，没有从市价反推成本或已实现盈亏。金额、数量跨 Java → HTTP → C# Native Bridge → React 均是精确十进制字符串；没有 JS Number 金融计算。Number 仅用于每页条数等界面参数。

静态核对的来源计算口径如下；本轮未迁入或改写这些写入/重放算法：

- BUY 的数量 × 单价先按 CNY 2 位 HALF_UP，费用和税加入成本；平均成本为总成本 / 数量，8 位 HALF_UP。
- SELL 部分卖出按当前总成本 × 卖出数量 / 当前数量释放成本，2 位 HALF_UP；全卖出释放全部剩余成本。已实现盈亏是净卖出金额减释放成本，累计值来自投影。来源是加权平均成本，并非本轮自行引入 FIFO。
- OPENING_POSITION 的数量 × 单价按 2 位 HALF_UP 形成初始成本；DIVIDEND 不改变来源持仓的累计已实现交易盈亏。修正与重放由既有不可变事实、reversal / replacement 与原引擎负责，本轮不执行或替换。
- 原只读服务以 UNNECESSARY 格式化数量和平均成本 8 位、成本及累计已实现盈亏 2 位；不会在读取时悄悄重新舍入不合规格的值。

三类信息明确分开：

1. **投资账务事实**：quantity / averageCost / totalCost / cumulativeRealizedProfitLoss，来自持仓投影。
2. **人工参考信息**：原持仓详情返回 assets.current_price 和持久化 market_value。不同于 F-next-5 Assets 的人工价格 × 数量展示，这里保留原详情服务返回的存储值，并标明可能陈旧、没有专属报价时间，不自行重新计算。
3. **缓存市场参考估值**：由原 Quote / FX / ReferenceValuation 计算。保留行情币种、单价、报价/获取时间、Provider、FX 和 CNY 参考值、FRESH / STALE / PARTIAL / UNAVAILABLE 及 code/component warnings；`accountingTruth=false`。没有价格或 FX 时保留 null，未知不是零。原币参考市值及计算时间不在该持仓 DTO 中，页面明确未提供，不自行补算。CNY 参考值遵循原服务 2 位 HALF_UP，CNY 行情使用 SYSTEM_IDENTITY。

合成精确持仓实际读取：数量 `9007199254740993.12345678`、总成本 `9007199254740993.12`、累计已实现盈亏 `1.00`；人工存储市值 `123.45` 与缓存 USD→CNY 参考值 `6305039478318695.19` 分开。已平仓的另一账户同一 Instrument 数量和总成本为零，累计已实现盈亏 `-3.00`。小额样本平均成本 `2.34900019`、总成本 `0.29`；缺 FX 的 CNY 估值 null。CNY 行情样本参考值 `0.02`，不等同于账务成本 `0.01`。

## API、Cursor 与权限

```text
GET /api/v1/finance/investment/positions?status=OPEN&size=10
    [&accountId=<positive int64>][&instrumentId=<positive int64>][&cursor=<source cursor>]
GET /api/v1/finance/investment/positions/{id}
Native: finance.positions / finance.position
```

Workspace 固定 API 的 status 为 OPEN/CLOSED/ALL，默认 OPEN；size 1–20，默认 10，以适配已有 64 KiB Bridge 限制。来源自身 size 1–100，不改源服务。非法/未知/重复/空参数、客户端 userId、非法 ID、非法 Cursor 均拒绝；详情不接受 query 参数。POST/PUT/PATCH/DELETE 及隐式 HEAD/OPTIONS 均拒绝为 405；未认证 401，响应 no-store。

原 Cursor 为 URL-safe Base64 无 padding 的 JSON：version=1、cursorKind=POSITION_LIST、filterFingerprint 和 instrumentId/accountId/positionId。seek 排序与 SQL 完整保留。原 SHA-256 筛选指纹绑定 status/accountId/instrumentId，不绑定 size 或用户，不含 HMAC、过期时间或跨请求数据库快照承诺；Cursor 不是授权凭据。筛选指纹不一致拒绝。Workspace 在原解码之前额外拒绝重复 JSON 字段、尾随 JSON、小数、溢出整数、错误字段集及格式，避免原 Number→long/int 的宽松转换；原 Codec 和来源筛选校验仍负责实际 Cursor 语义。

所有读取经过 Native Bearer → 私有已确认 Finance 绑定 → ACTIVE 用户与用户/数据集指纹 → 独立 Finance DataSource。原服务作为显式构造的查询依赖，在 `FinanceAccountQueries.query` 的 read-only / REPEATABLE_READ / REQUIRES_NEW 快照内执行，避免默认事务管理器歧义。来源 Mapper 仅接入 Finance SqlSessionTemplate。查询无需第二个业务服务、写服务或 AI 工具入口。

他人 Position、Account、Instrument 与不存在资源统一 404 / FINANCE_POSITION_NOT_FOUND。C# 和 React 检查字段集、类型、ID、金融字符串、分页数量/hasMore/nextCursor 及当前请求筛选匹配；详情 ID 必须匹配请求。Native 复用 Runtime 生命周期前后检查；无 URL、SQL、路径或命令代理入口。

## 合成资源与实际执行证据

旧 F-next-5 fixture 不包含投资持仓。只通过 `prepare-finance-next-test.ps1 -Port 55426 -Investments` 初始化 NEW 集群：`D:\PersonalAiWorkspace-Fnext1\2b95bb9a6363`。独立 Finance/Workspace 数据库、V1–V21/V1–V2，Finance reader 只获得所需 SELECT、并保持 default_transaction_read_only。包含既有账户/交易/资产样本及新的不可变 opening/sell 事实与显式投影；未通过生产写 API 生成数据，未把此准备过程声称为交易命令/重放验收。

两个 NEW Profile 分别通过真实 Native 窗口审查及显式确认绑定 Synthetic Alice / Bob：`ebf5cec17b6d496f9dace5400d7447a3`、`7e74a5320c624544af8b65fa1b55a4cb`。身份变化、数据库 OID 替换测试仅操作本轮集群；finally 恢复原身份。替换库保留为本轮测试资源，恢复时使用新归档名，避免重跑覆盖既有快照，不删除旧库。

以下为本轮**实际执行**，不是计划覆盖点或继承的历史 PASS。日志均在仓库忽略目录 `.verification`：

| 执行 | 结果 / 证据 |
| --- | --- |
| Maven package 与 Runtime JAR audit | PASS；`fnext6-maven-build-final.log`、`fnext6-runtime-audit.log`。 |
| 既有 FinanceAccountQueriesTest 的装配与可信身份读取两个方法 | PASS；显式独立装配、默认关闭及身份失配前置拒绝，`fnext6-java-focused.log`。不是 Java 全套，也不冒称这些 JDBC substitutes 是真实 SQL。 |
| 既有 FinancePage 综合回归 | PASS；扩展 Cursor 原样传递、筛选清除返回历史、session 后迟到详情丢弃、精确字符串、未知值、身份失配清除、非法 Number 响应拒绝和重试，`fnext6-frontend-focused-final.log`。 |
| 既有 Desktop 非法 payload 聚焦用例 | PASS；扩展持仓 userId 注入、非法 ID/size/status/cursor 拒绝，`fnext6-native-focused.log`。 |
| Frontend typecheck / production build、Desktop Profile Debug build / publish | PASS；`fnext6-frontend-build.log`、`fnext6-desktop-build-final.log`、`fnext6-package.log`。 |
| 最终 Runtime 的真实 Windows 综合流 | PASS，exit 0；`fnext6-windows-e2e.log`。WPF/WebView2/React/Typed Bridge/Java/PostgreSQL：首/次/末页、OPEN/CLOSED/ALL、账户/Instrument 筛选、空结果、安全返回、详情重读、刷新、失败及重试、金融字符串与估值边界。 |
| 同流权限、身份与恢复 | PASS；外部/不存在资源、LEGACY 排除、无 Bearer、非法方法/参数/筛选 Cursor、重复/小数/溢出 Cursor、no-store；用户身份和数据集 OID 变化时列表/详情拒绝且 UI 清除；恢复后不重绑；Runtime 原进程退出、新进程恢复读取，绑定哈希不变。 |
| 第二个实际 Native 绑定用户 | PASS，exit 0；`fnext6-bob-windows-e2e.log`。Alice 可读 11 / 拒绝 16；Bob 可读 16 / 拒绝 11，并拒绝 Alice 的 Account/Instrument 筛选。 |
| 1280 / 800 与原功能回归 | PASS；`fnext6-1280.png`、`fnext6-800.png` 已实际查看，document 无横向溢出，窄屏纵向可读；同流 Accounts、Transactions、Assets 详情和 Budgets 明确不支持语义通过。 |
| 独立包的实际 CMD / EXE / 独立 JAR | PASS，exit 0；`fnext6-product.log`。CheckOnly、Native Gate、持仓精确详情与旧资产/交易读取、正常托盘退出释放 Runtime；正常停止本轮 PostgreSQL 后由实际 CMD 安全恢复，重开再次读取，最后正常退出，无 18765 listener 残留。 |
| 文件保护、来源和独立包 hash | PASS；`fnext6-protection-audit.log`、`fnext6-package-hashes.log`、`fnext6-previous-package-audit.log`。已有 Profile/DPAPI/绑定、migrations、B4A6、F-next-3/4/5 payload 与本轮新 Profile 身份文件保持；原来源干净。 |

复现核心入口：构建既有 Desktop 测试项目时使用 `Mmf3ProfileAcceptanceBuild=true`、`Mmf3AcceptanceBuild=false`、`RuntimeLifecycleProbe=true`、`FrontendSkipBuild=true` 和本机受审 manifest。用 apphost 的 `<AliceProfile> gui-finance-next6` / `<BobProfile> gui-finance-next6-bob` 执行，不能用测试宿主的环境覆盖 Native Gate。实际桌面脚本从当前用户 Explorer 启动，未放宽 ACL、pin 或准入。

开发中未成功的尝试不计 PASS：原 Codec 缺少 Jackson 2 编译依赖；新增拒绝方法 handler 初次使用了当前 Spring 不公开的 array 构造器，改用公开 Collection；xUnit analyzer 要求 Assert.Single 使用 predicate；均在对应层修复后构建通过。独立包首次脚本在 Bridge bootstrap 前点击导航，停在账户元素查找；实际 UI 与手动再次导航证明 Finance 正常，脚本改为等待“工作区已连接”后重试成功。首次日志 `fnext6-product-first.log` / exit 10 保留；这次仅修改验收脚本，没有重建产品字节或降低断言。

## 独立开发客户端与字节身份

```text
D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-fnext6-development-20261024\Open-Synthetic-Finance.cmd
```

新目录不覆盖 F-next-5。同一 Windows 用户、当前隔离 Profile/路径、已有 JDK 21、.NET 10、WebView2 与 PowerShell 7.4+ 是前提。launcher 延续文件/目录身份、ACL、依赖哈希、system identifier、数据库 OID/marker、进程/端口归属及 DPAPI/绑定校验；只对确认停止的本轮 PostgreSQL 正常 start，不初始化、重建、删 PID、改密码或回退 SQLite。托盘退出释放本客户端 Runtime，PostgreSQL 保留运行。

BUILD-INFO format=`FINANCE_F_NEXT_6_DEVELOPMENT`；记录实际构建时 `sourceCommit=5f4ac444173deb91e09e8b995283e1d4c02525b4 / sourceDirty=true`，原 F-next-2 基础包仅提供已核对的启动依赖。**以实际 payload 与以下哈希标识本轮构建字节，不将基线 sourceCommit 冒称为最终实现提交。验证后没有为美化 BUILD-INFO 重新打包。** 完整文件哈希在包内 BUILD-INFO，主要文件：

| 文件 | SHA-256 |
| --- | --- |
| Open-Synthetic-Finance.cmd | `02cdf5908c6835202ffd58bf2961cf67f8072f047fb719b219c47a80d6fad752` |
| Open-Synthetic-Finance.ps1 | `870d874c927c9607ba0fc7b1d28b5e0c15c316cd38dca2896f76032ec0610302` |
| PersonalAiWorkspace.Desktop.exe | `4c8a35d030d4172ceca6642994be595a382a75bff35ae2d888d50bbd7573108c` |
| PersonalAiWorkspace.Desktop.dll | `834caab738ac6747cea4eb80572621a43a859034956c9e2f530de3416fa8b046` |
| PersonalAiWorkspace.Core.dll | `5bbf53eb9e93fcd39563550dd3dc75e38154523830cf81e6dcc0051197b8a38a` |
| runtime/personal-ai-workspace.jar | `63224485b46213629387ba5f992a203634a16940a53779bc7b1b7b1b6beb1146` |
| MainWorkspace/workspace-assets.json | `56c51e39f00c3a83f416520d4bda3473d09667466a153f81d9969c0b54cff8ea` |
| BUILD-INFO.json | `004718b073899f840a746912bc5b5780854bd37d9a4a04757266515c9b06a341` |

历史 F-next-2 recovery 的 `Open-Synthetic-Finance.ps1` 仍与其历史 BUILD-INFO 不一致：expected `b7d3d76d9cd99ed384a8777e64f402125f174ec1e410d0aaecc9a50a89a102b9`，actual `beb8ff0a7c55b6c72b47ba672adf9260d9fdd26c2af92cce64de5bed41a4b393`，修改时间 `2026-10-23 18:40:08`，早于本轮。本轮没有修复或写入该包；打包使用原始哈希匹配的 F-next-2 基础包。

## 未运行、风险与下一阶段

- 未运行 Java/Desktop/Frontend 全套、完整浏览器/IME/压力/历史安全矩阵、真实 Provider 或付费 API、真实 Windows 重启；没有把历史测试作为本轮新执行。
- 未重新执行投资写命令、FIFO/平均成本写入或修正重放测试；本轮没有修改相应生产代码。合成账本事实/投影是隔离夹具，不等同于完整写命令验收。
- Cursor 无原生 TTL 或跨请求一致性快照；数据变化后上一页重新读取，未承诺永久位置。人工参考值可能陈旧，缓存行情可能过期；缺失数据保留未知。
- 当前 CNY 账务约束来自冻结来源。未来若拓展非 CNY 投资账本，应先修改并验证领域合同，不能在此页面猜测币种或转换盈亏。
- 独立客户端是同机 Development-only，未签名、未发布安装包，不是 Production GO。真实 Windows 重启后的恢复仍待人工执行。
- 下一阶段优先审查并迁入原 Portfolio 的完整统计范围、覆盖状态和 warnings；金融写入、正式冻结与发布分别授权。

最少人工验收：双击上述 CMD → Finance → Investment Positions，打开“合成精确持仓 · #11”，核对精确数量、CNY 成本/已实现盈亏与独立的 USD 市场参考；切换 CLOSED 查看另一账户 `-3.00 CNY`，查看缺 FX 的未知值；从托盘正常退出后重开。

## Git 交付记录

基线和来源 SHA 如上。最终正常 Commit / Push 到 `origin/codex/finance-f-next-6`；没有 merge main、tag、Release、force push、历史改写或自动清理未追踪文件。报告随实现提交，无法在正文自包含自身提交 SHA；提交后的实际 local HEAD、origin tracking HEAD、远端 `ls-remote` HEAD 与一致性结果写入本机 `.verification/fnext6-git-delivery.json`，GitHub 最终提交链接及核对结论见交付回复。打包后的文档与 Git 交付不触发重新构建或重跑已通过的验收。
