# Finance 剩余能力与统一 AI 架构交接

2026-10-24（Asia/Shanghai）。本轮仅做源码盘点、架构决定与下一轮交接；**没有开发业务、运行测试、连接数据库、调用 Provider 或构建客户端。现有 Finance 交付仍为 Development-only，不是 Production GO。**

## 1. 基线、范围与结论

| 项目 | 本轮实际核对 |
| --- | --- |
| Workspace | `D:\IDEA\Daima\personal-ai-workspace`；起始 `codex/finance-f-next-9 @ aac471d8fc6a70697c4c1111658bb615ab5c69e4` |
| 远端 | 按指定 `127.0.0.1:7890` 代理执行 `fetch --prune origin` 成功；`origin/codex/finance-f-next-9` 与起始 HEAD 相同 |
| 规划分支 | 从上述本地/远端一致基线创建 `codex/finance-remaining-capabilities` |
| 冻结来源 | `D:\IDEA\Daima\personal-finance-OS`；`zh-cn @ c6c87a0627b746e70a45b1c7896f63defd76a810`；起始工作树干净，本轮只读、未 fetch |
| Workspace 起始状态 | 无 tracked/staged 修改；仅 B4A6 原八个未追踪文件，保留且不纳入提交 |
| 证据范围 | 两仓库实际 Java、React/TypeScript、SQL、装配与 Bridge；F-next-1–9 报告、原 Finance STATUS、Dashboard 历史一致性报告。历史测试结果不算本轮执行 |

结论：**F-next-10 适合做 Dashboard & Cashflow 只读集成，但不能原样迁入 Dashboard 的混合估值总额。** 剩余主能力是财务总览/统计、分类查询、导入创建到确认、账户/普通流水/资产/投资写入，以及行情/汇率主动刷新等。已有账户、流水、资产、持仓、Portfolio、投资历史与导入回执读取不重新开发。

**最终只保留 Workspace Unified AI Assistant + Shared AI Runtime；取消独立 Finance AI Analyst 的迁入计划。** 原项目的只读金融工具保留为合同参考，未来接入已绑定的 Finance 应用服务。本轮不开启工具。

本文件的后续顺序和 AI 决定取代 [2026-10-22 旧交接](FINANCE-INTEGRATION-HANDOFF.md) 中尚未执行的阶段建议；不追改其历史记录。README、STATUS、路线图及 architecture 中较早的 F0 BLOCKED/尚未冻结等段落保留历史语境，当前迁移程度以实际源码和 [F-next-9 报告](FINANCE-F-NEXT-9-REPORT.md) 为准。原 Finance STATUS 自身仍记录正式 Freeze 为 CONDITIONAL GO，本轮按用户指定 SHA 执行冻结只读保护，不替原项目授予正式 Freeze。

## 2. 已迁移能力总表

下表“已迁移”只表示所列只读范围已接入 Development-only；不表示整个来源模块或其写入都已迁完。Workspace API 前缀为 `/api/v1/finance`。

| 阶段 | 已有范围与实际入口 | 分类及保留限制 |
| --- | --- | --- |
| F-next-1 | Native 首次明确绑定；CNY 余额、账户切片；`/accounts/balance`、`/accounts/{id}` | 已迁移；CREATE_NEW、不重绑、每次验证数据集/ACTIVE 用户指纹 |
| F-next-2 | Finance 导航；账户总览、分页列表/详情；`/overview`、`/accounts` | 已迁移；现有 overview 是账户概览，**不是原 Dashboard** |
| F-next-3 | 普通流水和 V19 transfer 的只读列表/详情；`/transactions`、`/transactions/{kind}/{id}` | 已迁移；金额与 ID 字符串、类型/账户/日期筛选；独立来源键避免 ID 碰撞 |
| F-next-4 | `/budgets` 与 Budgets 能力状态页 | 来源没有实现预算领域；仅 `UNSUPPORTED`，不是预算管理 |
| F-next-5 | `/assets`、`/assets/{id}`；人工价格与缓存 Quote/FX 参考估值 | 已迁移读取；缺价 null、过期/不可用/部分覆盖有明确含义；无主动刷新/写入 |
| F-next-6 | `/investment/positions`、`/investment/positions/{id}` | 已迁移持仓读取；来源 cursor、OPEN/CLOSED、账户/标的筛选及账务/参考分离 |
| F-next-7 | `/investment/portfolio`，Portfolio Summary | 已迁移统计读取；成本、已实现盈亏与非账务参考估值分开，不是 Dashboard 净资产 |
| F-next-8 | `/investment/transactions`、详情、`/{id}/audit-timeline` | 已迁移逻辑交易/不可变事实/修正关系/历史 Receipt 读取；无冲正、更正或 Replay 命令 |
| F-next-9 | `/imports/transactions/batches`、`/{uuid}` | 已迁移 CONFIRMED 历史及服务端完整验证后分段回执；不是上传/Preview/Confirm |

实际拥有者：[FinanceAccountQueries](../../src/main/java/io/github/qianlixunbai/workspace/finance/FinanceAccountQueries.java)、同目录 `FinanceTransactionQueries` / `FinanceAssetQueries` / `FinanceInvestmentQueries` / `FinanceInvestmentHistoryQueries` / `FinanceImportQueries`、[FinancePage](../../desktop/frontend/src/pages/FinancePage.tsx) 及其组件。原字节迁入清单见 [Finance 模块](../../finance-domain/README.md) 与各 `*-source-provenance.json`。F-next-9 的历史列表是 Workspace 有界适配；来源只有单 Batch 回执 GET，不能倒称来源已有历史列表 API。

## 3. 剩余真实能力、归属和迁入差距

来源证据均固定在 `c6c87a0…`。下表 Java 路径相对于来源 `backend/src/main/java/com/financeos/module/`；迁移路径相对于 `backend/src/main/resources/db/migration/`。`BaseMapper` 表示源码使用 MyBatis-Plus 的实体查询/写入，不存在独立 Mapper XML。复杂度为依赖和风险的相对估计，不是工时承诺。

| 能力 / 分类 | 来源 Controller → Service → DTO / Mapper 与 API | SQL、原前端和 Workspace 差距 | 风险 / 复杂度 / 推荐位置 |
| --- | --- | --- | --- |
| Dashboard / Financial Overview：部分迁移（仅账户概览可复用） | [dashboard](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/dashboard)：`DashboardController → DashboardService → DashboardDto`；账户/资产/分类/流水 QueryService；`GET /api/v1/dashboard` | `accounts/assets/transactions/categories`，V1，资产精度/投影续见 V4/V6；真实 `pages/Dashboard.tsx`。Workspace 没有该聚合入口 | 只读；混合估值口径必须适配；中；F-next-10 |
| 月收支、净现金流、六个月趋势：真实存在但未迁移 | 同 Dashboard；[TransactionQueryService](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ledger/service/TransactionQueryService.java)、`MonthlyCashFlowAggregate`、`TransactionMapper.sumByTypeAndDate/monthlyCashFlowByMonth` | V1 `transactions` 与 user/currency/type/time 索引；Dashboard 月度组件和图表有入口；Workspace 流水分页不是统计查询 | 只读；CNY、月份、精确金额；中；F-next-10 合并交付 |
| Asset Allocation：真实存在但未迁移 | `DashboardService`、`DashboardDto.AssetAllocation`；[AssetQueryService](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/asset/service/AssetQueryService.java)、`AssetMapper/BaseMapper` | V1/V4/V6 `assets`；Dashboard `AssetAllocationChart`；当前 Assets 仅个体估值，没有来源这项分布聚合 | 只读人工参考；非全资产配置、非实时行情；中；F-next-10 |
| Recent Transactions：部分迁移 | `DashboardService`、`DashboardDto.RecentTransaction`；`TransactionQueryService.listRecentByUser`、`AccountQueryService`、`CategoryQueryService` 及对应 Mapper | V1 四表；Dashboard `RecentTransactionsPanel`；Workspace 已有流水读取，但不是来源“CNY 最近五条普通表流水”的合同 | 只读；不能截取当前分页或混入 transfer 表/投资事实；低至中；F-next-10 |
| Categories：真实存在但未迁移独立查询 | [CategoryController / Service / DTO / Mapper](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/category)：`CategoryRequest/Response`、`CategoryMapper`；GET/POST `/api/v1/categories`、POST `/init`；另有 `CategoryQueryService` | V1 `categories`；前端 Transactions 和 Import 的选项使用真实 API，**无独立分类管理页**。Workspace 无分类目录/筛选接口 | 查询只读；创建/init 写库；可见性/父分类归属需审查；低至中；F-next-10 后合并必要分类支持 |
| User Profile：部分迁移身份展示；完整 Profile 来源没有实现 | [UserController → UserService](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/user)；`RegisterRequest/LoginRequest/LoginResponse`、`UserMapper`；仅 POST `/register`、`/login` | V1 `users`；Login/Register 和退出菜单，无 Profile 页或 me/update API。Workspace 已显示绑定 username，Native 审核使用身份摘要 | 不迁入第二套 JWT 注册登录；只有必要展示随分类/设置小步补充，不作为独立大阶段；低 |
| Import Preview / Mapping / 分页 Rows / Cancel：真实存在但未迁移 | [TransactionImportPreviewController](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/importing/controller/TransactionImportPreviewController.java) → `preview.TransactionImportPreviewService` / `TransactionImportSessionService`；`TransactionImportPreviewRequest/Response/Row`、`TransactionImportMapping`、SessionMapper | POST `/api/v1/imports/transactions/preview`；PUT `/{sessionId}/preview`；GET `/{sessionId}/rows`；DELETE `/{sessionId}`。V14–V21；原 `TransactionImport.tsx` 有完整流程；Workspace 仅已确认历史 | **会写 Session/文件/生命周期，即使不写账本也不是只读**；高；先单独安全设计，再实施完整有价值的导入流程 |
| Import Confirm / unknown result recovery：部分迁移（仅历史回执读取） | [TransactionImportConfirmService](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/importing/service/TransactionImportConfirmService.java)；Confirm/BatchController；`TransactionImportConfirmRequest/Response/Receipt`；Batch/Item/BatchAccountImpactMapper | POST `/{sessionId}/confirm` + `Idempotency-Key`；GET `/batches/{batchId}`；V14/V16/V17/V20，`transactions/accounts`。原导入确认、恢复与 Receipt 路由真实存在，Workspace 未装配确认服务 | 金融写入；Session → 升序账户锁、frozen plan、canonical hash、receipt 原子性；高；导入独立准入后 |
| 文件对象与 Session 生命周期：真实存在但未迁移 | [importing](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/importing)：`TransactionImportFileLifecycleService`、`TransactionImportPreviewCleanupService`、`LocalTemporaryImportFileStorage`、`TrustedImportStorageRoot`、`ImportStorageProvisioner`、FileObject/SessionMapper；无通用文件管理 Controller | V14/V15/V21；`transaction_import_storage_bindings/file_objects`、RAW/PLAN 文件；原 Import 流程使用，非独立 UI；Workspace 未装配这些文件服务 | 写库/文件删除与恢复；不可因读取历史回执就启动 cleanup；高；是 Preview 的前置依赖，不另开微小编号阶段 |
| Account 创建、元数据修改、停用：部分迁移（读已完成） | [AccountController → AccountService](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/account)；`AccountRequest/Response`、AccountMapper、AccountBalanceService、InvestmentPositionBindingService；POST `/accounts`、PUT `/{id}`、POST `/{id}/deactivate` | V1/V4 `accounts`；原 Accounts 页真实表单；Workspace 无命令入口。新账户余额为零，元数据更新不是任意设余额；无来源账户硬删 API | 金融领域写入；归属/锁/绑定、CNY 限制；中至高；导入实施所需账户支持可与基础流水写切片协调 |
| 普通交易录入/修改/删除及转账：部分迁移（读已完成） | [ledger](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ledger)：TransactionController/Service、`TransactionRequest/Response`、TransactionMapper、TransactionWriteRules；TransferController/Service、`TransferRequest/Response`、TransferMapper | POST/PUT/DELETE `/transactions[/{id}]`；POST `/transfers`。V1/V18/V19；原 Transactions 有普通 CRUD；转账 backend 真实存在，未见对应专用前端表单。Workspace 均只读 | 余额 delta/回滚；创建幂等不等于修改、删除和转账均幂等；高；独立写权限准入后，复用原余额原语 |
| Legacy Asset 创建/人工价格/关闭/删除：部分迁移（读已完成） | [AssetController → AssetService](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/asset)；`AssetRequest/Response`、AssetMapper；POST `/assets`、PUT `/{id}/price`、PUT `/{id}/close`、DELETE `/{id}` | V1/V4/V6 `assets`；原 Assets 页有入口；Workspace 无写入口 | 手动参考价更新可用于交易驱动资产，但 close/delete 必须拒绝该类投影；关闭 Legacy 不计算 SELL 收益；中至高；按业务需要在写入阶段迁入 |
| Investment 首购/追加 BUY/SELL/DIVIDEND/REVERSAL/REPLACEMENT：部分迁移（读已完成） | [command](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/investment/command)：Command/Reversal/ReplacementController → InvestmentCommandService、各 TransactionalService/ConsistencyChecker；FirstBuy/Trade/Dividend/Reversal/Replacement DTO；InvestmentTransaction/CorrectionMapper | POST `/investment/positions`、`/{id}/buy`、`/sell`、`/dividends`；`/investment/transactions/{id}/reversal`、`/replacement`。V4–V13；原 Investments + CommandDialog/coordinator/RecoveryBanner 真实可用；Workspace 仅读审计与投影 | 锁序/不可变事实/确定性 replay/receipt/unknown commit；高；基础写入之后单独验收，禁止普通 Asset CRUD 改投影 |
| Instrument 目录/创建；Legacy → transaction-driven migration：真实存在但未迁移独立入口 | [instrument](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/investment/instrument)：InstrumentController、Query/CommandService、Request/Response、InstrumentMapper；[migration](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/investment/migration)：LegacyAssetMigrationController、Preview/Confirm/TransactionalService、Preview/Confirm DTO，Asset/InvestmentTransactionMapper | GET/POST `/investment/instruments`；POST `/investment/legacy-assets/{id}/migration-preview`、`/migration-confirm`；V7–V9。原投资命令依赖标的选项，未见 Legacy migration 独立前端入口；Workspace 持仓已带标的信息但无目录或创建/转换 | 目录读可按需合入；创建与转换为写入，opening cost/预览 token/投影绑定风险高；在投资命令阶段处理，不自动迁移历史资产 |
| 主动行情/FX 参考估值刷新：部分迁移（缓存读取已完成） | AssetController → MarketQuoteService / ReferenceValuationRefreshService；后者协调行情、ExchangeRateService 与 ReferenceValuationService；Quote/ReferenceValuation DTO，MarketQuoteMapper/ExchangeRateMapper；POST `/assets/{id}/quote/refresh`、`/reference-valuation/refresh` | V2 `market_quotes`、V3 `exchange_rates`；原 Assets 有按钮；Workspace 只注册 QueryService/参考计算，未注册网络刷新 Provider | 外部网络与缓存写入，不等于金融账本写入，也不是纯读；中；按真实新鲜度需求单独准入，F-next-10 不调用 |
| Finance AI Analyst：暂缓迁移（**独立系统迁入永久取消**） | [ai](https://github.com/qianlixunbai/Personal-Finance-OS/tree/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ai)：AiController → FinanceAiOrchestrator → FinanceToolDispatcher / CloudAiProvider；POST `/api/v1/ai/ask`；三个只读 Fact Tool | 原 `pages/AiAnalyst.tsx`、`/ai` 实现单问题/纯文本回答；未实现独立持久多轮会话或长期记忆。Workspace 不迁入该入口、Provider 或 Orchestrator | 只复用金融合同；Runtime 能力与数据出站授权尚缺；后于只读业务服务成熟和统一工具门禁 |

**来源没有实现，不列为迁移欠账：** 预算管理；完整 User Profile 管理；负债领域及其净资产扣减；FX 折算的全币种账务总资产；独立退款命令；账户硬删除；分类更新/删除的完整管理 API；仅因 V1 有 `asset_prices` 表不能宣称已有历史价格查询/收益曲线（实际 Java/前端未检出使用者）。不把这些表、枚举或规划当成产品能力。

**剩余读取细节：** 原普通流水分页支持 `categoryId` 与 LocalDateTime 边界；Workspace F-next-3 是现有账户/日期/类型合同，没有分类筛选。分类支持阶段可补必要筛选，但这是合同适配，不重做交易模块。标的目录读取已存在于来源，持仓里显示标的信息不等于已迁入独立标的目录。

导入生命周期的实际依赖：CSV/XLSX 原解析器、5 MiB 文件/10,000 行限制、15 分钟 Session；状态为 `MAPPING_REQUIRED/PREVIEW_READY/EXPIRED/CANCELLED/CONSUMED`，映射更新使 revision/冻结计划证据变化，Confirm 复验 preview token、digest、warning acknowledgements 和重复证据。V21 文件对象采用 `RESERVED/LIVE/RETIRED/DELETED`、lease/fence_epoch、RAW/PLAN 受信根与持久化 storage binding；旧引用需先验证才能登记，不能启动即扫描删除未知文件。确认事务原子提交交易、账户影响、Batch/Receipt 与 CONSUMED；物理 cleanup 在提交后执行，清理失败不回滚已提交金融事实。F-next-9 只读取这些结果，不能被当作上述上传/恢复链已经迁完。

### 身份、迁移与写入准入

对以上全部读取，继续使用已认证 Native + 本地已确认绑定 + 数据集/ACTIVE 用户指纹；**不需要第二次登录或新的用户绑定制度**。新对象 ID 必须在当前用户 scope 重查，分类只允许当前用户或真正系统分类。原 `CategoryService.listByUser` 使用 `userId OR isSystem`，而 `CategoryQueryService` / `TransactionWriteRules` 的系统可见性要求 `user_id IS NULL AND is_system=true`；未来独立列表应遵守严格边界，不能盲搬较宽列表谓词或在本轮修改来源。

本轮候选所需表已在 Finance V1–V21 中存在，Workspace 也保留迁移副本；**F-next-10 无需新 schema 或数据迁移**。这不证明任意现场数据库已具备权限/完整 schema。未来写入服务能否原样适配 Boot 3→4、事务管理器/角色权限/依赖尚需按切片核查，不预先承诺零迁移，更不执行 Flyway、补授权或修复旧数据。只读新增查询权限若不足，安全报错并报告，不能提升历史角色。

写入类必须另有明确授权/权限合同、用户可审查意图、失败恢复与最小验收：原服务端锁与事务、AccountBalanceService、canonical hash/幂等键、Investment Ledger/replay/receipt 均保留。普通 create 已有 V18 幂等；TransferService 没有该幂等合同，不能把其事务原子性当成重试安全。未知结果不得换 key 重发或自动重放非幂等命令。Profile/email 修改还会改变现有用户指纹，不得因显示需求顺带开放或重绑。**统一 AI 不授予任何金融写权限。**

## 4. Dashboard / Cashflow 字段级口径

权威源码：[DashboardService](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/dashboard/service/DashboardService.java)、[DashboardDto](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/dashboard/dto/DashboardDto.java)、[TransactionMapper 聚合 SQL](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ledger/mapper/TransactionMapper.java)、[来源 Dashboard UI](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/frontend/src/pages/Dashboard.tsx)。

| 原字段 | 实际来源 / 算法 | F-next-10 处理 |
| --- | --- | --- |
| `totalAssets` | 所有状态 CNY `accounts.balance` 合计，加上 CNY `assets.current_price * quantity` 合计。缺价/数量按零参与该旧总额；不是读取 `assets.market_value`，也不是 Portfolio 成本或缓存 Quote/FX | **不迁入该混合总额**；已有 CNY 账户总余额可复用并准确命名，不能改名为 totalAssets |
| `netWorth` | 直接等于 totalAssets；代码注明 V1 无负债跟踪，没有净资产独立算法 | **暂不提供净资产指标**，不返回伪造的 0、不把账户余额重命名为净资产 |
| `monthIncome` | `transactions` 当前用户、CNY、`type='INCOME'`，`SUM(amount)`；`[本月1日00:00, now)` | 复用服务端 SQL 和 BigDecimal |
| `monthExpense` | 同窗口 `type='EXPENSE'`，正金额求和 | 保留来源口径，不用退款抵扣或计入投资支出 |
| `monthNet` | 服务端 `monthIncome - monthExpense` | 精确字符串；UI 不相减 |
| `monthlyCashFlowTrend` | `date_trunc('month', transacted_at)` 分组，CNY、INCOME/EXPENSE；从当前月往前五个月的月初到同一个 now；按月升序，Java 补齐六个月，net 在服务端计算 | 保留六点和零月；这是普通收支趋势，不是账户余额变化、全量资金流或投资现金流 |
| `assetAllocation` | CNY assets 中 currentPrice/quantity 非空、quantity>0、乘积>0 的**每条资产**；value=乘积，percentage=value×100/全部符合条件乘积之和，scale=4、HALF_UP。不按资产类型分组、不合并同名、不含账户余额 | 独立“人工参考资产分布”，保留筛选/分母/舍入；不是整个净资产配置。缺价资产不视为已估零；显示排除/覆盖信息 |
| `recentTransactions` | `transactions` 当前用户、CNY，按 transacted_at DESC 取五条；不筛类型、不设 now 截止。账户名当前用户；分类名当前用户或 user_id=null 的系统分类；缺失名称为未知 | 可含历史 REFUND/ADJUSTMENT/旧 TRANSFER 及未来日期流水；不混入 V19 transfers 或 investment_transactions。按 `(transacted_at DESC,id DESC)` 做明确的同时间稳定排序适配 |

### 时间、币种、退款与估值边界

- 来源 `FinanceOsApplication.businessClock()` 为 `Asia/Shanghai`。`transacted_at` 是 PostgreSQL `TIMESTAMP`，对应 LocalDateTime；月份边界沿此业务时区，不能误用当前 Workspace 的 UTC Quote 时钟。一次 Dashboard 固定一个 now；当前月上界为排他 now，历史五个月完整，未来流水不进收支聚合。
- 当前账务写规则只支持 CNY、INCOME/EXPENSE/ADJUSTMENT。独立 V19 transfer 只改两个账户余额，不写普通收入/支出；投资 BUY/SELL/DIVIDEND 记录在投资账本，不计入普通收支。REFUND 虽在旧表 CHECK/读取类型中，普通写入口明确不支持；聚合排除 REFUND、ADJUSTMENT 和旧 TRANSFER。若退款实际录为 INCOME，就按其存储类型统计；没有自动退款净额算法。
- 账户/资产总额查询按 `currency='CNY'`；非 CNY 数据忽略，不折算。Quote 的 USD→CNY 参考估值属于另一套参考合同，不能加进账务余额、净资产或普通收支。
- 来源资产分布是持久化人工 current_price，不调用 MarketQuote/FX，不读取市场缓存。没有价格时间/TTL、行情新鲜度或负债覆盖保证；刷新页面只能重读持久化数据，不能让旧人工价格变成实时价格。原总额缺价按零是旧实现行为，不能据此向用户宣称完整估值。
- 原 Dashboard 没有服务端 Dashboard 缓存；每次服务调用重新查询。原 React 在挂载时 GET 后保存在页面 state，没有手动刷新按钮；本轮建议加入显式刷新/错误重试及成功读取时间，禁止持久化金融页面缓存或用旧页面数据覆盖新身份。
- 分布百分比逐条舍入后未必精确合计 100%；不在 React 归一化。源分布查询未指定排序且列表无界；Workspace 应保留每资产语义，增加稳定 ID 排序及响应预算，不能按 type 重分组来假装同一指标。

### 原服务不能直接注册的原因与最小替代

原 `DashboardService` 依赖 AccountQueryService、AssetQueryService、CategoryQueryService、TransactionQueryService 和业务 Clock。当前 Workspace 只已装配 AccountQueryService；Asset/Transaction 的已有 Workspace 适配和投资查询不是这四个完整来源 QueryService。Category/Transaction Mapper 及 Dashboard DTO/服务尚未迁入；不能扩大 `com.financeos` 全包扫描来补依赖，更不能顺带装配写服务。

原服务公共入口采用 `@Transactional(readOnly=true,isolation=REPEATABLE_READ,propagation=REQUIRES_NEW)`；来源只有一个业务数据源，注解未命名 Workspace 的 `financeTransactionManager`。Workspace 现有 [FinanceAccountQueries.query](../../src/main/java/io/github/qianlixunbai/workspace/finance/FinanceAccountQueries.java) 已用 Finance 专用 TransactionTemplate 实现同样独立只读 RR、30 秒 timeout，并在该事务里先验身份再读业务。**不能把原注解直接当成跨双数据源正确装配的证明。**

推荐沿现有 FinanceTransactionQueries/FinanceImportQueries 模式，在 Workspace 增加窄 `FinanceDashboardQueries`：以 `identity.query(userId -> …)` 包住所有统计及名称查询，复用已迁入 AccountQueryService 求余额；缺少的聚合按来源固定 SQL 做最小读取适配，不复制第二套通用查询框架、不调用分页 HTTP 拼总额、不嵌套开启多个统计事务。来源算法/SQL与有意差异记录进 F-next-10 报告；若选择原字节迁入纯查询依赖，则遵循现有 provenance 规则，但不要为复用而迁入整个 Dashboard/AI 子系统。

该替代保留服务端金融权威、同一次快照和已有身份边界，移除与本轮要求冲突的混合总额。原仓库算法不改；以后若要新增严谨账务净资产，应独立定义资产成本/负债/估值范围，不能本轮顺手发明。

## 5. 已确定的统一 AI 架构

目标调用关系：`Personal AI Workspace → Unified AI Assistant → Shared AI Runtime → Provider / Tool Orchestration → Finance / Knowledge / Memory / Translate 等领域能力`。这是唯一产品方向，**不再评估建立独立 Finance AI 的替代方案**。

| Owner | 最终职责 | 当前事实 / 本轮边界 |
| --- | --- | --- |
| Unified Assistant / Runtime | 对话入口和会话、Provider 调度、模型能力/执行策略、Tool 权限与编排、队列/取消/超时/恢复、对话持久化、受控 Memory/Knowledge、工具审计及未来多模型协作 | 已有 Assistant/Conversations、ProviderRegistry/ProviderPolicy、TaskManager、ConversationStore、显式 Memory/Knowledge 查询。当前 ProviderPolicy 只允许本地 Ollama，Ollama 响应校验拒绝非空 tool_calls；云 Provider/通用工具执行/多模型协作并未完成 |
| Finance | 领域模型、账户/交易、精确计算、账本与投影、事务/锁/幂等/恢复/审计、数据集与用户隔离、服务端统计、受限查询服务 | `workspace-application → finance-domain` 单向依赖；独立 Finance PostgreSQL/事务管理器。Finance 不依赖 AI Runtime；不建立第二个可执行 Finance 后端 |
| Provider | 执行被统一策略准入的模型请求 | 未来可同时支持 Ollama、多家云端 API 等受信 Provider；单一 Runtime 不等于单一模型。本轮不取消 LOCAL_ONLY、不加隐式云 fallback、不迁入原 CloudAiProvider |

**The server owns financial truth.** LLM 不重算权威余额、成本或已实现盈亏；不拿聊天记录、Memory 或知识文档代替当前金融事实。统一 Runtime 通过受控 Finance 应用服务读已经验证 scope 的结果，不能将数据库、Mapper、任意 SQL 或写 Service 暴露给模型。

现有实现依据：[ProviderPolicy](../../src/main/java/io/github/qianlixunbai/workspace/policy/ProviderPolicy.java)、[ProviderRegistry](../../src/main/java/io/github/qianlixunbai/workspace/provider/ProviderRegistry.java)、[OllamaProvider](../../src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaProvider.java)、[TaskManager](../../src/main/java/io/github/qianlixunbai/workspace/task/TaskManager.java)、[ADR-001](../ADR/ADR-001-local-shared-runtime.md)、[ADR-005](../ADR/ADR-005-explicit-memory-context.md)。上述最终职责不等于这些未来能力已实现或本轮获得开发授权。

### 原 AI Analyst 的保留与工具复用

冻结来源所有 AI 源码保留不动。`/ai` 页面、AiController、CloudAiProvider、FinanceAiOrchestrator 和独立 Dispatcher 不迁成第二套入口/Provider/编排器；不新建 Finance 专属聊天历史、记忆库或会话系统。原来源本身是单次问答与请求内工具循环，不把“不迁独立持久会话”误写成原来已有这种会话数据库。

| 来源工具 | 实际合同 | 统一 Runtime 未来处理 |
| --- | --- | --- |
| [FinancialOverviewTool](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ai/tool/FinancialOverviewTool.java) | 服务端用户 ID；无模型业务参数；依赖 DashboardService；包含旧 totalAssets/netWorth | 概念可复用，旧混合总额不可原样沿用；优先调用 F-next-10 已验证统计服务，明确 schema/指标差异 |
| [MonthlyCashFlowTool](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ai/tool/MonthlyCashFlowTool.java) | fromMonth/throughMonth，含首尾、最多 12 月；CNY，服务端补零和 net；范围是完整月份，不以当前 now 封顶 | 参考严格范围校验，复用服务端聚合；**不要直接用于 F-next-10 当前月截至 now 的合同**。未来工具必须明示 full-month 与 as-of 语义 |
| [InvestmentPortfolioSummaryTool](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ai/tool/InvestmentPortfolioSummaryTool.java) | 无业务参数；成本/已实现盈亏与缓存参考估值分离，保留覆盖数/新鲜度/warnings | 优先复用 F-next-7 已迁入 InvestmentPortfolioQueryService/Workspace 应用边界；不要另写 Portfolio SQL |
| [FinanceToolDispatcher](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/main/java/com/financeos/module/ai/tool/FinanceToolDispatcher.java) | 工具名 allowlist、严格参数/重复 key/尾随 JSON 检查、额外字段拒绝、安全失败；原 Orchestrator 另限制 3 轮/5 调用 | 只参考合同/预算/失败关闭方法，适配统一工具门禁；不复制为平行 Orchestrator 或新 Registry |

### 未来 Finance 只读 Tool 必需合同（规划，未开放）

1. scope 由 Runtime 受信执行上下文绑定当前用户/数据集，再经 Finance 服务端复验；模型参数不能选择 userId、Profile、指纹、数据源或凭据。不能伪造 HTTP Native 身份来绕过门禁，需未来明确受信内部应用调用边界。
2. 用户授权明确哪些数据用于哪次任务，以及本地/云端出站策略。先取最小必要聚合，默认不返回全账本、完整身份、账户号、任意描述或大段交易明细。
3. 返回精确金额字符串、币种、范围、来源/合同版本、查询时间和参考新鲜度；缓存绑定数据集/用户/参数/版本，不跨身份复用，不把旧结果说成当前事实。查询时间不冒充人工价格更新时间。
4. 工具名/参数/月份/行数/字节/轮次/总调用与 deadline 都有上限；金额由服务端提供。取消/超时不得转为写入或无限重试，未知/不可用/部分估值不伪装零值。
5. 审计记录调用授权、工具版本、受保护的 scope 关联、请求关联、结果状态、时长及必要覆盖信息；不默认记录原始提示、完整金融 payload、凭据或可跨域检索的身份数据。存储位置/保留期/访问/删除在实现前确定，不能宣称现有通用 task 日志已满足金融工具审计。
6. Finance 不自动写 Memory；敏感金融片段的长期保存须用户逐次明确授权。Knowledge RAG 不遍历 Finance DB，Memory/聊天历史不成为金额真相；会话保存金融工具结果的策略也必须明确，不能因为现有 Conversation 持久化就默认保存全部工具 payload。
7. 绑定缺失、scope 改变、权限不足、未知工具/参数、不可用服务或预算溢出均失败关闭；Browser Translate-only、DPAPI/Profile 和现有 Native 安全边界不扩大。所有写工具持续禁止。

## 6. 后续实施顺序

| 顺序 | 可交付范围 | 前置 / 准入与复杂度 |
| --- | --- | --- |
| 1：F-next-10 | Dashboard & Cashflow 只读纵向切片，合并趋势、参考分布、最近交易，不拆四个编号 | 现有 Native/Finance 查询边界；口径适配明确；中 |
| 2：分类与必要身份展示 | 可见分类只读目录、必要类型/分类筛选；复用已显示 username；需要时附带 Instrument 目录读 | 不假设来源有独立管理/Profile API；无写入/init、无第二套登录；低至中。按实际用户流程合并，不各开微小里程碑 |
| 3：导入完整流程的安全设计 | Native 文件选择/受控传输、RAW/PLAN 私有根与 V21 生命周期、Session/Mapping、Preview、授权确认、unknown result/Receipt | Preview 即有持久化/文件副作用；先定义写权限/文件恢复/用户可审查意图/最小验收。若已有账户分类足够，不必等待普通交易编辑全迁；高 |
| 4：按该设计交付导入；再补基础写切片 | 以真实需求选择账户/分类必要写支持、普通交易录入/修改/删除、转账；避免为导入复制另一套 TransactionWriteRules/余额逻辑 | 原事务/锁/幂等合同；转账及非幂等未知结果需先解决；高。未授权不自动开始 |
| 5：投资/Legacy 相关写入 | 标的创建、BUY/SELL/分红/冲正/更正与受控 opening migration；Legacy 维护按需合并 | 投资账本、receipt/replay/恢复与绑定规则必须完整；高；不从 Asset CRUD 绕过 |
| 独立依赖轨：统一 AI 只读 Tools | Runtime 通用受限工具门禁成熟后接总览/现金流/Portfolio | 至少依赖 F-next-10 等受信查询与统一授权/审计/出站能力；**不依赖金融写入迁完**，可以在写功能前独立获准实施；中至高 |
| 按需：行情/FX 主动刷新 | 来源受限 Quote/FX 缓存刷新与明确新鲜度 | 网络/限流/缓存写入独立授权；不作为 Dashboard 前置，不调用真实付费 API 做大规模测试 |
| 主要能力完成后 | Finance 与 Workspace 视觉统一，再研究开源 UI/图表方案 | 保持 WPF/WebView2/React 和现有组件；本轮不调研、不重设计、不换组件库 |

以上是建议顺序，不把后续写入/云出站/AI 工具当成授权。历史 Windows 整机重启/物理生命周期等待办独立保留，不因文档盘点通过而关闭，也不要求在本轮重跑。

## 7. F-next-10 可直接执行的开发范围

**开发目标：** 在现有 Finance 页面增加 Dashboard，只读读取当前绑定身份的 CNY 账户余额、当月收入/支出/net、六个月趋势、人工参考资产分布、最近五条 CNY 普通表流水。不出现旧混合 totalAssets/netWorth 指标，不修改既有 `/overview` 或 Portfolio 合同，不加入 AI。

推荐触点：根 `src/main/java/io/github/qianlixunbai/workspace/finance/` 的窄 Dashboard Query/Controller 与 `FinanceModuleConfiguration`；`desktop/src/PersonalAiWorkspace.Core/RuntimeClient.FinanceDashboard.cs`；既有 `Bridge/WorkspaceBridge.cs`、`MainWorkspaceWindow.xaml.cs`；`desktop/frontend/src/bridge/` 的类型/方法和 `components/FinanceDashboard.tsx`、`pages/FinancePage.tsx`。这些是建议 owning files，正式开发时先核对实际调用链，不能为逐项对齐而机械创建文件。

- 建议新增固定 `GET /api/v1/finance/dashboard` 和空 payload 的 `finance.dashboard` typed bridge；拒绝额外 query/身份字段，保持 Native Bearer/可信 origin/session/schema 验证、Browser 拒绝和 Finance 默认关闭。页面显示已有 DISABLED/NOT_BOUND/BINDING_INVALID/UNAVAILABLE，绑定异常使用原 Settings/Native 入口。
- 服务端单次 `identity.query` 内先验身份再完成全部统计/名称查询；所有查询限定当前 userId，分类严格可见。固定业务 `Asia/Shanghai` Clock 与一个 now；同一 Finance RR 事务，禁止跨 Workspace 数据源读取/事务或扩大包扫描。
- 金额/百分比/ID 端到端字符串，BigDecimal/toPlainString；禁止 Number/parseFloat/toFixed/React reduce 求金融总额或 net。月份等非金额展示可用普通前端格式。不新增图表依赖；优先精确表格/已有样式。若图形需要浮点比例，只可用服务端单独提供的非权威绘图数据，不能再由 UI 汇总金额。
- 资产分布保留来源每条资产算法及全量分母，建议稳定 ID_ASC 最多返回 20 条，并附 total/complete 与缺价排除信息；超过范围明确“部分视图”并可转到已有 Assets，不画冒充完整 100% 的局部饼图、不按本页归一化、不静默截断。分页/覆盖元数据是 Workspace 适配，不能写成来源原 DTO 自带。
- 六个月趋势固定六项、最近流水最多五条；响应继续遵守 HTTP 60 KiB/Bridge 64 KiB，含 JSON 转义最坏情况。超界安全错误，不提高全局预算来容纳无界数据。人工价格无来源时间/TTL时明确未知，不制造 `FRESH`。
- 手动刷新/错误重试；会话轮换、Runtime 切换/不可用、解绑失效、导航卸载时清除敏感快照，迟到响应丢弃；失败后不得保留先前身份的金融内容。正常空数据与 unavailable/缺估值区分。
- 不增加 migration、写 Service、上传/导入/cleanup、Provider、Tool、长期 Memory 或网络刷新；不修改原 Finance、V1–V21、Workspace V1/V2、Profile/DPAPI、旧绑定、原八个 B4A6 文件或任何历史开发包。

**最小验证（下一轮计划，不是本轮已执行）：** 先复用现有 Finance 身份/只读事务/路由聚焦证据和框架；新增金额/月份/双用户/快照聚合确有覆盖缺口时，在现有合成隔离环境与 owning-layer 集成入口扩展一个高价值流，不能靠 mock 聚合替代真实 SQL。覆盖来源 CNY/类型/月界/补零、精确大额、他人数据/指纹改变拒绝与刷新恢复；覆盖点不是各自一轮 E2E。必要的 Native DTO/Bridge 和前端类型/聚焦检查只验证新增跨层合同；不机械跑 Java+Desktop+Frontend 全套、不建立新框架或完整 Windows/IME/包装矩阵。检查通过即止；若环境不足，准确报告缺口，不放宽门禁、不冒称验收完成。

来源 [DashboardApiIntegrationTest](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/test/java/com/financeos/integration/DashboardApiIntegrationTest.java)、[DashboardSnapshotConsistencyPostgresIntegrationTest](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/backend/src/test/java/com/financeos/integration/DashboardSnapshotConsistencyPostgresIntegrationTest.java) 和 [C2 历史一致性报告](https://github.com/qianlixunbai/Personal-Finance-OS/blob/c6c87a0627b746e70a45b1c7896f63defd76a810/docs/archive/review/G2-1-C2-Dashboard-Snapshot-Consistency-Review.md) 可作为合同参考；后者记录 REQUIRES_NEW 已获授权以及混合快照修复。它们并未在 Workspace 对新实现执行，不复制整套来源测试。现有 Workspace `FinanceAccountQueriesTest` / `FinanceRouteSecurityTest` / `FinancePostgresqlIntegrationTest`、`bootstrap/tests` 和 `RuntimeLifecycleProbe.Finance*.cs` 须按适用性复用，不能把只证明旧账户查询的测试算成新 Dashboard 验收。

可直接复制的独立实施 Prompt：[FINANCE-F-NEXT-10-PROMPT.md](FINANCE-F-NEXT-10-PROMPT.md)。下一轮从本规划分支的已提交文档进入功能开发；执行前仍需刷新实际 HEAD/工作树，不把本轮文档分支的 SHA 当成未来运行包身份。

## 8. 本轮验证、保护与尚未验证

本轮实际完成：读取两个指定 HEAD/工作树；按指定代理 fetch Workspace；核对真实 Controllers/Services/DTO/Mappers/SQL/React 入口、当前 Workspace 装配与 Bridge；编写本报告和实施 Prompt；提交前检查实际 diff、staged diff、`git diff --check` 与文档相对链接；仅提交这两份文档并正常 Push 规划分支，随后核对远端分支与本地 HEAD。

保护核验：B4A6 八文件与 Workspace 内已有 Finance/Workspace migrations 在本轮开始记录 SHA-256，文档完成后与原记录比较；冻结来源结束时复核同一 HEAD 与干净工作树。没有运行 Runtime/数据库/构建/打包/清理脚本，没有读取或改写私有凭据、Profile、DPAPI、绑定与历史包。对 ignored 历史包和数据库没有重新全量 hash/内容扫描，不把“未操作”写成新的恢复或二进制验收；原 F-next-2 recovery 包历史哈希问题保留。

**本轮没有执行任何自动化测试、Windows E2E、UI 人工运行、新 PostgreSQL fixture、EXE 构建、Provider/API 调用或金融写入。** F-next-1–9/原 C2 等结果均仅为历史报告；未将其计入本轮 PASS。未验证现场角色新增统计所需 SELECT、Dashboard 新查询的运行性能/最大资产规模/连接池压力、Boot 适配、统一金融 Tools 或未来写入恢复。以上待实际 owning slice 验证，不阻止本轮有限文档交接，也不授予生产就绪。

本轮交付后的预期工作树：无任务 tracked/staged 改动，仅保留原 B4A6 八个未追踪文件。最终提交 SHA/远端核对结果在交付回复列出，不为把自身 SHA 写回文档再制造提交。没有 force push、merge main、rebase/amend、tag 或 release。
