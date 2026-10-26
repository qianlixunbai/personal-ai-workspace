# Finance Market Data M1 — 选型与来源

核对日期：2026-10-26（Asia/Shanghai）。结论：采用 Twelve Data 官方 REST，由 Java 发起；不增加 SDK、Python sidecar、Redis 或金融写事务。服务默认关闭，只做个人本地参考估值。

## GitHub 源码调查

通过 GitHub API 核对仓库元数据，并阅读以下实际源码；没有复制第三方实现。维护日期只是本次快照，不构成服务可用性承诺。

| 项目 | 本次观察 | 采用的设计经验 |
| --- | --- | --- |
| [Portfolio Performance](https://github.com/portfolio-performance/portfolio)；[TwelveDataQuoteFeed.java](https://github.com/portfolio-performance/portfolio/blob/master/name.abuchen.portfolio/src/name/abuchen/portfolio/online/impl/TwelveDataQuoteFeed.java) | EPL-1.0，未归档，最近 push 2026-10-24 | 明确行情来源与交易所 MIC、缓存有效响应、将行情错误与持仓事实分开。没有采用其以 double 转价格或在 URL 放 Key 的做法。 |
| [Ghostfolio](https://github.com/ghostfolio/ghostfolio)；[data-provider.service.ts](https://github.com/ghostfolio/ghostfolio/blob/main/apps/api/src/services/data-provider/data-provider.service.ts) | AGPL-3.0，未归档，最近 push 2026-10-25 | Provider + symbol 身份、市场数据与资产事实分离、缓存和外币估值。M1 不引入其多 Provider/Redis/任务体系。 |
| [官方 twelvedata-python](https://github.com/twelvedata/twelvedata-python)；[http_client.py](https://github.com/twelvedata/twelvedata-python/blob/master/src/twelvedata/http_client.py) | MIT，未归档，最近 push 2026-08-12 | 检查 HTTP 和 JSON 内业务错误，不能把 HTTP 200 当成功。Java 直接实现小型固定客户端，保留错误脱敏与更短超时。 |
| [yfinance](https://github.com/ranaroussi/yfinance/blob/main/README.md) | Apache-2.0，未归档，最近 push 2026-10-25 | 上游明确说明并非 Yahoo 官方授权客户端、实际数据权利须另查且面向个人使用。M1 不选依赖非官方接口的接入，不用软件许可证代替数据许可。 |

## 官方服务资料与取舍

- [Twelve Data API](https://twelvedata.com/docs)：`/quote`、`/exchange_rate`；支持 `Authorization: apikey …`，因此 Key 不放 URL。Quote 读取 symbol、MIC、currency、type、timestamp、close；FX 读取 USD/CNY、rate、timestamp。客户端拒绝错误 JSON、非正金额、未来异常时间和身份不匹配。
- [Individual Pricing](https://twelvedata.com/pricing)：核对到 Basic 为 8 credits/min、800/day。**当前免费档列为内部非展示用途，不能据此承诺本客户端免费展示合法。** 是否可显示、可访问 USD/CNY、所需套餐/交易所权限及可缓存时长，以用户实际账号与合同为准。原生窗口要求明确启用及许可确认，不购买或升级套餐。
- [US equities market data](https://support.twelvedata.com/en/articles/9935903-us-equities-market-data)：默认报价来自部分合规交易场所，交易活动覆盖约 5%，不等于全市场 consolidated/SIP 行情。产品仅显示服务商参考报价与原时间，不承诺完整交易覆盖、交易所实时授权或成交可执行价格。
- [Terms of Use](https://twelvedata.com/terms)：内部处理/存储/展示受套餐、数据附加权利和交易所规则约束；禁止超出允许期限缓存及未经许可分发。代码允许设置 1–168 小时保留期，用户必须按自己的许可选择；24 小时默认值是产品上限策略的初值，**不是服务商统一授予的缓存许可**。过保留期数据下次访问时清理，不建立历史行情库，不上传到 GitHub/备份。
- [Alpha Vantage 官方支持](https://www.alphavantage.co/support/)：免费标准额度 25 requests/day；美股实时和 15 分钟延迟数据需要 premium。未选为 M1 主方案：手动逐标的刷新及 FX 共用额度较紧，不能把免费 EOD 当实时。

Twelve Data 覆盖股票、ETF 和 FX，已有上游客户端及成熟桌面项目参考，且可用固定 HTTPS + header Key 完成小范围接入。选型不以“免费无限、全市场实时”作为前提。没有真实授权 Key，因此本轮不验证账号 entitlement 或真实 Provider，不宣称服务商实际调用通过。

## 实施约束

1. 使用服务器已绑定用户的 Position ID 重新查询目标；只接 US/USD 的 STOCK（Common Stock）与 ETF。HK/CN/JP 等保持原标识，不猜 Yahoo 后缀或复用同名美股。返回 MIC 必须属于允许的美国交易所，币种与类型须一致。现有批量估值入口另补市场匹配守卫，防止同名跨市场误估。
2. Java 直接取十进制字符串/BigDecimal；数字型 FX JSON 使用 BigDecimal 节点，股票最高 8 位、FX 最高 12 位小数，与现有详情精度相容；超范围拒绝，不静默四舍五入价格。最终 CNY 金额沿用既有服务逐持仓 HALF_UP 两位舍入与 Portfolio 汇总。
3. 缓存独立于 Finance PostgreSQL，位于现有受信 Profile 的 `data/market-data.dpapi`；CurrentUser DPAPI、私有 ACL、拒绝链接、文件身份校验、强制刷盘和原子替换。Finance SQL 继续 readOnly/REPEATABLE_READ；不新增表、迁移或账务写入。
4. 股票 15 分钟、FX 60 分钟：报价时间与获取时间都必须新鲜；休市收盘价重新获取仍可为 STALE。超时、429、权限、格式或身份错误保留原缓存；只允许同等或更新报价时间替换。离线是 Provider 网络不可达，持仓事实仍需本机 Finance PostgreSQL 可用。
5. 所有 Quote/FX 共用持久化滚动窗口 8/min、800/24h，失败计费预留，429 冷却 60 秒；不自动重试/批量轮询。配置/重启不重置额度。应用外相同 Key 的用量不在本地可观测范围内，服务商拒绝仍如实报告。
6. Native WPF PasswordBox → 原 Native Bearer HTTP → Java DPAPI；网页只能打开原生窗口、读无密钥状态或请求刷新持仓 ID。无通用 URL、任意 symbol、Key/许可载荷或用户 ID 的 Bridge 方法。外网仅固定 Twelve Data origin，不发送数量、余额、账户信息或交易。
