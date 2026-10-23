# Finance F-next-5 — Assets 只读集成

2026-10-24，Asia/Shanghai。**Development-only：Assets 功能、真实 Windows 链路与独立开发客户端验证通过。不是正式 Release 或 Production GO。**

基线：已 fetch 并核对的 `codex/finance-f-next-4 @ 67d257691c708d5d9cee7a5cc67194a9ce7c438e`。开发分支 `codex/finance-f-next-5`；正常 Commit / Push，最终提交与远端 SHA 见交付回复。原八个 B4A6 未追踪文件不加入提交。

## 实际功能与来源边界

保留原账户总览、Accounts、Transactions 和 Budgets，增加 `Assets · 资产`。支持当前绑定用户的列表、重新读取的详情、服务端分页、类型筛选、稳定排序、加载/空状态/错误/重试及手动刷新。没有导航、主题或组件库重构。

来源只读核对 `personal-finance-OS @ c6c87a0627b746e70a45b1c7896f63defd76a810` 的 AssetController、AssetQueryService、AssetService、AssetResponse、Asset Entity/Mapper、Quote/FX/ReferenceValuation 实现，以及 V1/V2/V3/V4/V6/V8 等相关 migrations：

- 展示名称、代码、类型、市场、资产币种、数量、平均成本单价、人工参考单价、数量模式及记录创建/更新时间。原领域类型为 STOCK / ETF / FUND / BOND / GOLD / CRYPTO / CASH / OTHER。
- 人工价格估值遵循 AssetService 的 `currentPrice × quantity`，以服务端 BigDecimal 计算，保留完整精度；不直接采用可能陈旧的持久化 `market_value`。**缺少人工价格时保留 null**，不用原 AssetResponse 的 null-to-zero 回退，不推造零市值、盈亏或收益率。人工估值不是账户余额或实时净值。
- 原字节迁入 ReferenceValuationService、MarketQuoteQueryService、ExchangeRateQueryService 及必要依赖。来源文件及逐项哈希见 [asset-source-provenance.json](../../finance-domain/asset-source-provenance.json)；Git 属性保留这些原始字节，构建不读取冻结仓库。仅补充原属性类所需的 Jakarta Annotation API 编译依赖，没有新框架或 Provider SDK。
- 市场参考估值仅支持 **US STOCK / ETF**。批量读取已有行情和汇率快照，由原 ReferenceValuationService 计算原币参考市值，以及行情币种至 CNY 的参考值，最终 CNY 保留 2 位、HALF_UP。CNY 行情沿用 SYSTEM_IDENTITY 恒等汇率。没有第二套参考估值算法。
- 展示行情/汇率来源、各自报价与获取时间、服务端计算时间、公式版本和 FRESH / STALE / PARTIAL / UNAVAILABLE 状态；缺行情、缺汇率、无效数量等使用原服务 warning code 的固定中文提示。缓存有效不等于实时。无来源的字段显示“未提供”；不支持的资产明确说明能力边界。
- 使用来源默认 TTL：行情 15 分钟、FX 60 分钟；只读装配不开放 Provider 配置或刷新服务。页面刷新只重读缓存，不联网、不写入行情。资产记录更新时间不是人工价格的报价时间。
- 列表/详情不汇总不同币种，不替代原 Overview 口径，不推算投资收益。没有创建、改价、平仓、删除资产或其他金融写 API。

新增固定路由：

```text
GET /api/v1/finance/assets?page=0&size=10&type=ALL&sort=CREATED_DESC
GET /api/v1/finance/assets/{id}
```

page 0–100000、size 1–20；类型取上述原领域枚举或 ALL。排序为 CREATED_DESC（创建时间降序、ID 降序）、ID_ASC、NAME_ASC（同名按 ID 升序）。拒绝未知/重复参数、客户端 userId、非法排序和越界请求；他人资产与不存在资产统一 404。

## 身份、事务与恢复

列表/详情均经既有 `FinanceAccountQueries.query`：Native 认证、私有已确认绑定、ACTIVE 用户、用户指纹与数据集指纹，在同一 Finance 独立 DataSource 的 read-only / REPEATABLE_READ / REQUIRES_NEW 快照内验证和读取。Quote/FX Mapper 仅绑定既有 Finance SqlSessionTemplate，不装配 AssetService、行情更新或金融写服务。

金额、数量、价格、汇率及估值在服务端使用 BigDecimal，跨 HTTP / Typed Native Bridge / React 全部为精确字符串。Native 复用 Runtime 生命周期前后检查、固定 loopback 路径、Bearer 与严格 payload/响应校验。React 离开视图、session 或 Runtime 变化时清除快照并丢弃迟到响应；Runtime 就绪后重新校验身份并恢复读取。身份失配不自动重绑。

来源允许资产代码为空；适配器向原估值批处理传入支持 null 查询键的 Map，以返回来源定义的无行情状态，不改变原估值算法。原只读事务、数据集绑定、DPAPI、启动恢复和历史 migrations 均未修改。

旧合成库无资产样本，故只创建一套 NEW 合成资源：`127.0.0.1:55425`、独立 Finance/Workspace 数据库及新 Profile `f519f6837d474eb3b6ea67b3a4220bdb`。准备工具新增显式 `-Assets`，仅在新库初始化时放入样本并包含原交易样本，不提供对旧库补写资产的入口。行情统一标记 `SYNTHETIC_FIXED`，不是现实价格或实时 Provider。后续调试复用此环境，没有重复创建数据库/Profile。

## 本轮实际验证

原始日志、截图和临时产品脚本在忽略目录 `.verification`；凭据、数据库和私有 Profile 不提交。

| 实际执行 | 结果及范围 |
| --- | --- |
| Maven package + 现有 FinanceAccountQueriesTest 的装配与受信身份读取两个方法 | PASS；验证显式独立装配及数据集变化后资产列表/详情拒绝；包含 Runtime JAR 审计，不是 Java 全套 |
| 现有 FinancePage 综合回归 | PASS；扩展资产精确字符串、缺价格、session 变化后迟到详情丢弃与身份失配清除。没有新增普通组件测试 |
| 现有 Desktop PayloadMustBeExactlyEmptyAndNoArbitraryUrlPathOrExecutableIsAccepted | PASS；扩展资产 userId 注入、非法 ID、分页和排序拒绝 |
| Frontend typecheck / production build，Desktop Profile Debug build / publish | PASS |
| 既有 Windows 综合工具的 gui-finance-next5，最终日志 fnext5-windows-e2e.log / exit 0 | PASS；真实 WPF/WebView2/React/Native/Java/PostgreSQL，资产分页/同时间稳定排序/类型筛选/空列表/详情/刷新 |
| 同一综合流的估值、权限与身份恢复 | PASS；大额人工估值与 USD→CNY 参考估值保持精度、CNY 舍入至 0.02、缺价格为 null、缺汇率为 PARTIAL、空代码且无行情为 UNAVAILABLE、已过期快照明确标识；他人资产 404，无 Bearer 401，非法参数 400，POST 405，响应 no-store；新合成用户身份变化时列表/详情 403、UI 清除，恢复后重试且不重绑 |
| 同一综合流的 Runtime 重启及原功能回归 | PASS；原进程退出、新 Runtime 读取资产成功、绑定哈希不变；账户总览与详情、原交易列表与详情及跨用户交易拒绝通过 |
| 同一综合流 1280 / 800 宽度 | PASS；document 无横向溢出，实际查看 fnext5-1280.png / fnext5-800.png，精确数值和来源可读 |
| 新包实际 CMD / EXE / 独立 JAR，fnext5-product.log / exit 0 | PASS；CheckOnly、Native Gate、资产详情和精确估值、原账户/交易详情、正常托盘退出释放 Runtime；正常停止本轮新合成 PostgreSQL 后，由实际 CMD 安全恢复并再次读取资产；最终正常退出，无 18765 listener 残留 |
| 文件保护与产物核对 | PASS；新包 payload 哈希全部匹配；原 Profile/绑定/DPAPI、Finance V1–V21、Workspace V1/V2、B4A6 八文件与新 Profile 身份文件哈希保持；冻结 Finance HEAD 不变且工作树干净；旧 F-next-2 基础包、F-next-3/4 payload 匹配各自清单 |

没有运行 Java/Desktop/Frontend 全套、压力、IME、大型历史安全矩阵或完整打包矩阵；没有真实 Provider/API 调用，也没有真实 Windows 重启。旧启动恢复拒绝矩阵属于历史证据，本轮只实际验证新合成库的正常停止/安全启动恢复。未对投资持仓迁移作通过结论。

未成功的尝试不计为 PASS：首次直接取 SqlSession Mapper 早于 Mapper 初始化，改为注入已注册 Mapper；首次 Desktop 聚焦调用缺少既有 RuntimeLifecycleProbe 构建条件，补齐原标志；Profile 首次在应用上下文遭目录身份拒绝，改用既有 Explorer 路径成功，未放宽 Gate/ACL；综合脚本修正了保留筛选后的数量预期和窄窗口隐藏角标的可见性预期；产品脚本停止过全桌面 UIA 查找，随后仅定位本客户端菜单，实际正常退出/恢复验证通过。后述问题均只修正 harness，没有修改正确的金融或生命周期生产行为。

另发现**旧 F-next-2 recovery 包的 Open-Synthetic-Finance.ps1 与其历史 BUILD-INFO 哈希不一致**，文件时间为 2026-10-23 18:40:08，早于本轮。本轮未写入或修复它；新包从哈希匹配的 F-next-2 基础包生成，独立包验证通过。

## 独立开发客户端与最少人工验收

```text
D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-fnext5-development-20261024\Open-Synthetic-Finance.cmd
```

同机 Development-only，使用新合成 Profile，旧包和旧数据保留。独立 JAR SHA-256：`a2fa7c0e08cdf74f70e1de142254aec136542e72f55aaf040306f4e75f25551e`。BUILD-INFO 的 format 为 FINANCE_F_NEXT_5_DEVELOPMENT，记录实际构建时 `sourceCommit=67d2576... / sourceDirty=true`；以独立 JAR 与 payload 哈希标识实际构建字节。报告提交后不为修改该记录重新打包。

沿用原 PostgreSQL 启动恢复校验：私有目录/ACL、文件/配置哈希、来源和进程/端口归属、system identifier、数据库 OID/marker 及绑定/DPAPI pin。只在确认停止态后正常 start；不初始化、重建、改密码、重绑、删除 PID 或回退 SQLite。依赖已有 JDK 21、.NET 10、WebView2、PowerShell 7.4+，不要移动目录或替换 pin 的依赖。

最少人工步骤：

1. 双击 CMD → Finance → Assets，查看五项合成资产；切换类型或每页数量并打开“合成精确资产”。
2. 核对人工估值与市场参考估值分列、SYNTHETIC_FIXED 来源、报价时间及缓存过期说明；打开“合成缺汇率资产”或“合成未知价格基金”，确认未知金额没有显示为零。
3. 点击刷新，切回 Accounts / Transactions 查看原详情；从托盘正常退出后重开。真实 Windows 重启后的体验可在方便时执行，不是本轮已完成的验证。

下一阶段建议迁移来源已经实现的 InvestmentPositionReadQueryService、InvestmentPositionDetailQueryService 与 InvestmentPortfolioQueryService，只读展示账户/Instrument 绑定、持仓投影、total_cost / realized_profit_loss 及原领域统计口径。继续区分人工价格、缓存参考估值和投资账务事实，保留原服务的币种、快照及用户边界；金融写入、预算新领域与统一视觉升级各自另行规划。
