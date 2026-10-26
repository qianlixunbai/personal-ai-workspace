# Finance Market Data M1.2 — Massive Basic

核对日期：2026-10-26，Asia/Shanghai。以官方网页及官方 GitHub 源码为依据；未使用 Firecrawl、付费服务或真实凭据。实际账号权限和真实 USD/CNY 返回仍须用户本地最少 smoke；不能用公开套餐描述代替本机成功响应。

## 选型与权限边界

[Stocks Basic](https://massive.com/pricing?product=stocks) 和 [Currencies Basic](https://massive.com/pricing?product=currencies) 当前均为 $0/月、5 REST requests/min、2 年历史、日终数据和 Reference Data。Stocks 包含美国股票与 ETF 的行情，ETF Global 的付费成分股/分析产品不是 ETF 日线报价的前提；见[官方产品权限说明](https://massive.com/knowledge-base/article/why-am-i-not-authorized-when-attempting-to-connect-to-massives-websockets)。免费价格是当前条款，不能保证供应商永不更改。

[个人应用说明](https://massive.com/knowledge-base/article/which-plan-do-i-need-to-show-massive-data-in-my-app)明确：个人、非商业且只有本人能看到数据的应用适用 individual plan；任何其他查看者，包括测试或 staging 用户，均涉及 Business 许可。本实现仅作本人的本地客户端，不提供公开行情服务。

[Market Data Terms §1–3](https://massive.com/legal/market-data-terms-of-service)允许个人非商业使用，并默认限于 display use；其他协议和第三方 entitlement 仍适用。工程判断如下：为完成本人屏幕显示所需的请求解析、身份校验及临时内存表示可用；没有把显示权扩张为持久缓存、离线再次查看或持仓金融派生计算权。账号应由 Massive 按适用条款核定；客户端不通过勾选、自报套餐或配置布尔值签发权限。

| Provider / 用途 | M1.2 决策 | 执行边界 |
| --- | --- | --- |
| Massive / 本人屏幕显示 | 开放，须有 Key 且实际响应可访问并校验成功 | 仅显式手动刷新；没有全局许可锁 |
| Massive / 展示必要的临时内存处理 | 开放 | 本次请求及当前 React 组件状态；不设置服务端报价缓存 |
| Massive / 行情持久缓存 | 未开放 | DPAPI 文件只含 Key、Provider 和限流时间，无价格/代码/响应 |
| Massive / 离线再次查看 | 未开放 | 状态接口不回放报价；页面卸载、会话切换和 Runtime 重启后须重新请求 |
| Massive / 持仓人民币参考估值 | 未开放 | 报价不注入 Quote/FX valuation adapters；原 BigDecimal 算法保持原样 |
| Twelve Data / 旧 M1 数据 | 原阻止原则保留 | 不读取/复用旧凭据文件，不展示或估值其缓存 |
| Finance / 已确认合成旧缓存 | 保留既有行为 | SYNTHETIC_LEGACY；与真实 Massive 区域分开，不宣称真实账户估值 |
| 未核验来源 / 旧 Finance 缓存 | 继续不展示/估值 | 不按 Provider 标签或用户自报推断可信性 |

`FinanceMarketDataStore.permits(provider, use)` 为源码审查后的 Provider/用途策略。原生配置只接收 API Key；没有持久化权限字段或前端授权入口。以后取得额外授权时，应分别审查对应用途与存储/估值边界，不能通过放宽整个 Provider 的总开关实现。

## REST、时间与身份

- 采用固定 `https://api.massive.com`，`Authorization: Bearer`，无重定向、原始错误回显、自动重试或自动分页；不把 Key 放 URL。复用 Java HttpClient、Jackson BigDecimal、DPAPI、Native Gate、当前 Finance 用户绑定。
- 实际股票/ETF接口为 [Custom Bars](https://massive.com/docs/rest/stocks/aggregates/custom-bars)：`/v2/aggs/ticker/{symbol}/range/1/day/{from}/{to}?adjusted=false&sort=desc&limit=14`。一次取 14 天以内的已结束日线，选最新有效日期；不轮询空交易日。
- [Ticker Overview](https://massive.com/docs/rest/stocks/tickers/ticker-overview) `GET /v3/reference/tickers/{symbol}?date={barDate}` 包含在 Stocks Basic 中。逐次校验 exact ticker、market=stocks、locale=us、currency_name=usd、CS/ETF 和允许的美国 MIC。现有持仓先经服务器重新查询归属、US/USD、STOCK/ETF；不上传 Position ID、账户、数量、成本或交易。股票每次最多两个 REST 请求，独立于 FX。
- FX 采用 [Forex Custom Bars](https://massive.com/docs/rest/forex/aggregates/custom-bars)，固定 `C:USDCNY`，显示 USD/CNY 日线 close，不使用实时 conversion endpoint。CNY 与 CNH 不互换，不反转币对。官方 Currencies Basic 支持 forex 日线，但本机具体 USD/CNY entitlement、覆盖及真实响应 **NOT RUN**；403/404/空数组明确返回错误，不伪造汇率。
- 已比较官方 [Stocks Previous Day Bar](https://massive.com/docs/rest/stocks/aggregates/previous-day-bar) 和 [Forex Previous Day Bar](https://massive.com/docs/rest/forex/aggregates/previous-day-bar)：Basic 均列可访问。本轮选择明确截止日期的 range 接口，避免把当前交易日混进免费请求。
- [Stocks Basic 可用时间说明](https://massive.com/knowledge-base/article/on-the-free-basic-plan-when-can-i-pull-the-previous-trading-days-data)：当天数据在纽约午夜之后才成为可请求的过去日期；不是 16:00 收盘后立刻可取，也不是 flat files 的 11:00 ET。使用 `America/New_York` 自动处理夏令时，截止纽约昨天。
- [Forex Overview](https://massive.com/docs/rest/forex/overview)说明 forex 时间标准为 UTC、报价聚合不同于股票成交。FX 按 UTC 日期显示，并保守排除仍为 ET 当天的日期。UTC 刚过午夜时可能再落后一天，优先避免 Basic 当前日拒绝；后续按实际账号 smoke 决定是否可缩短。
- `c` 使用 BigDecimal 原精度；股票最多 8 位、FX 最多 12 位，超出范围拒绝，不静默舍入。`t` 是日线窗口起点的 Unix 毫秒，按相应时区提取实际日期；UI 分别标注日期、时区、窗口起点和获取时间，获取时间不冒充成交时间。过去 14 天无数据明确 NO_DATA，不猜值。

## 官方开源客户端调查

阅读官方 [client-python README](https://github.com/massive-com/client-python)、[aggs.py](https://github.com/massive-com/client-python/blob/master/massive/rest/aggs.py) 和 [base.py](https://github.com/massive-com/client-python/blob/master/massive/rest/base.py)。其 MIT 软件许可不替代数据许可。确认 API 新域名、Bearer header、日线 range/previous-close 路由及响应 results 的取法；不复制 SDK，不新增 Python/Kotlin sidecar。本轮沿用既有有界 Java transport，不启用 SDK 的 trace、自动重试和自动分页，以免额外配额或日志风险。

## 配额、错误与备选

所有股票身份校验和 FX 共用保守的 5/min 滚动窗口；在请求前持久预留，失败也消耗额度。更换 Key、空 Key 保存或 Runtime 重启不重置窗口；429 后冷却至少 60 秒。应用外同一 Key 的额度不可观测，仍服从上游拒绝。401/403、429、404/空数据、网络失败、身份不匹配和格式错误分别报告，不回退到旧 Provider 或合成报价，不自动升级。

Currencies Basic 的官方权限没有显示必须付费的障碍，因此本轮未接入第二个 FX Provider。没有真实 Key 时不能判定 USD/CNY 稳定不可用，也不擅自声称 ECB 交叉率已实现。若实际免费账号明确无法取到 USD/CNY，下一步可独立审查 ECB 来源、再利用许可和同日 EUR/CNY ÷ EUR/USD 的 BigDecimal 交叉计算，并明确来源与参考日期。
