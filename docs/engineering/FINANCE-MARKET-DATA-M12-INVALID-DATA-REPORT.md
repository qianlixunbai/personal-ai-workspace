# M1.2 — INVALID_DATA 最小修复与安全诊断

2026-10-26 至 2026-10-27，Asia/Shanghai。继续 `codex/finance-market-data-m12`，基线 `1a70ecf792f097184e116d22c8d090434fe68684`，起始工作区干净；指定代理 fetch 成功。实现提交 **`e236c50dd4c2fced6df1b6642dab55c07789844e`**，交付报告之后的提交仅含文档与证据。

**用户报告：原 M1.2 Windows 人工验收中 AAPL 曾显示 Massive 真实日终价，后续发生 INVALID_DATA。此为用户提供的历史证据，不是本轮 Provider 验证。** 截图无法判定失败的是 SPY 还是 USD/CNY，也没有保留实际失败字段。按用户要求，本轮没有读取/使用真实 Key、抓取真实行情或访问行情账户；**LIVE PROVIDER NOT RUN / 修复效果待人工复验**，不宣称真实问题已全面解决。

## 已确认的缺陷与最小修复

旧实现直接将 `BigDecimal.scale()` 当作有效小数位数。确定性响应中的 SPY `100.12345678000000000000` 与 `100.12345678` 数值完全相同，旧客户端却抛出 INVALID_DATA。先用原生产代码运行定向回归，实际失败；修复后，同一用例的 SPY 与 USD/CNY 等值表示均通过。

只增加 `stripTrailingZeros()` 的无损规范化，再执行原有股票 8 位 / FX 12 位有效小数上限、20 位整数范围、正数和长度限制；没有浮点转换、舍入或抹掉非零小数。真正超过上限的非零小数仍拒绝。价格在请求股票 Reference 身份前验证，避免无效价格再消耗一次额度。所有示例数字均为确定性夹具，未采集用户行情内容。

这证明代码存在数值表示误拒，**不能据此确定截图中的真实失败一定由尾随零引起**。因此同时加入安全诊断，供下次本地人工验收确认具体分支。

## 官方规范与 SPY / USD/CNY 审查

只使用内置搜索、官方文档和官方 GitHub 工具；没有 Firecrawl。

| 核对项 | 证据与处理 |
| --- | --- |
| 股票 / SPY 响应 | [Stocks Custom Bars](https://massive.com/docs/rest/stocks/aggregates/custom-bars) 的 `c` 为 JSON number，`t` 为窗口起点的 Unix 毫秒整数。未承诺固定 JSON 小数位格式，因此无损去尾零；仍拒绝字符串价格、错误身份、非正值、越界日期及真正超精度值。 |
| SPY 身份 | [Ticker Overview](https://massive.com/docs/rest/stocks/tickers/ticker-overview) 提供对应日期的 ticker、market、locale、currency_name、type、primary_exchange。保留 exact ticker / stocks / us / usd / ETF / 美国 MIC；没有将 CS 当 ETF，也没有跳过第二次身份请求。 |
| Forex 格式 / 时间 | [Forex Custom Bars](https://massive.com/docs/rest/forex/aggregates/custom-bars) 说明报价聚合、UTC、数字 close 和毫秒整数 `t`。保留 `C:USDCNY`、UTC 日期及排除未结束日期；不混用 CNH、不反转币对，不因时间失败猜测偏移。 |
| adjusted | 官方 Stocks/Forex 均列 boolean；Forex 官方样例请求 `adjusted=true` 并返回 true，而当前客户端明确请求 false。该样例不能证明当前账号对 false 的请求会返回 true。没有盲目接受 true、缺失或错误类型；若发生，安全诊断为 `FOREX_BARS / ADJUSTMENT_FLAG`，后续可据新证据单独处理。 |
| 精度上限 | 文档没有承诺 `c` 不超过原应用的 8/12 位边界。此次只修等值表示；超过边界的有效非零精度仍拒绝并显示 PRICE_SCALE，不截断，也不擅自扩大跨层金融合同。 |
| 开源客户端 | 核对官方 [Agg 模型](https://github.com/massive-com/client-python/blob/master/massive/rest/models/aggs.py) 与 [现有 aggregate 测试](https://github.com/massive-com/client-python/blob/master/test_rest/test_aggs.py)。其通用模型不替代本应用的身份/日期边界；没有复制上游 float 价格处理，也不把上游夹具当真实 USD/CNY/SPY 成功证据。 |

## 诊断边界

`MassiveClient.Failure` 只携带源码固定的 Stage / Reason 枚举，状态响应新增可空 `diagnostic`。C# 与 React 都严格白名单校验，仅允许 `stage`、`reason` 两个字段；未知码或额外 message 等字段直接拒绝，不显示原文。

| 安全诊断示例 | 含义 |
| --- | --- |
| STOCK_BARS / PRICE_SCALE | 股票有效小数仍超过 8 位 |
| FOREX_BARS / ADJUSTMENT_FLAG | FX adjusted 与请求的 false 不符或类型无效 |
| FOREX_BARS / TIMESTAMP_RANGE | FX 日线日期越过原有边界 |
| STOCK_REFERENCE / ASSET_TYPE | 股票 Reference 类型与预期 STOCK / ETF 不一致 |
| STOCK_REFERENCE / CURRENCY、MARKET、EXCHANGE、TICKER | 分别识别身份不符的位置 |
| STOCK_BARS 或 FOREX_BARS / JSON_FORMAT、RESULTS_SHAPE、TIMESTAMP_TYPE、PRICE_TYPE | 对应解析/字段结构问题 |

另区分限流准入、HTTP 状态、传输失败等固定类别。不增加请求日志，不包含 Key、URL、ticker 值、价格、交易日期、请求 ID、响应片段、数量、账户或异常消息，不保存诊断历史。Provider 失败状态改为 `NOT_VALIDATED`，界面显示“本次未取得有效报价”，避免把已有失败响应显示为 NOT RUN；这不是成功或真实 Provider PASS。

## 本轮实际验证与交付

[命令与边界](evidence/finance-market-data-m12-invalid-data/verification.txt) · [包哈希](evidence/finance-market-data-m12-invalid-data/package-hashes.json)。

- **PASS：确定性 Java**，仅 `FinanceMarketDataTest`。复用原类和重要集成行为，增加一条等值价格表示回归；同时证明 SPY/FX 非零超精度仍拒绝、Reference ETF 类型和当前日仍拒绝、FX 失败不锁死股票，以及旧合成估值/配额/无行情持久化边界不变。
- **PASS：React 聚焦**，仅 `FinanceMarketData.test.tsx`。验证安全原因码显示、失败不再显示 NOT RUN、恶意诊断值不进入界面，保留会话迟到响应与用途守卫。
- **PASS：C# 聚焦**，仅 `RuntimeClientTests.MarketData`。验证安全诊断穿过原合同，未知原因和额外字段拒绝且错误不回显合成敏感 canary。
- **PASS：构建与包检查**，TypeScript/Vite production build、Maven `-DskipTests package` 与实际 JAR identity audit、Profile Debug win-x64 publish、独立启动器 CheckOnly。构建不计为测试；保留原 Vite >500 kB 提示。修复包源为实现提交，`sourceDirty=false`，来源与 payload 哈希已校验。
- **PASS：保护检查**，原 M1 / M1.1 / M1.2 与新修复包 payload 均符合各自清单。冻结 Finance OS 仍为 `c6c87a0627b746e70a45b1c7896f63defd76a810` 且干净；finance-domain、账本、Receipt、迁移、DPAPI 配置/存储和许可策略未修改。
- **NOT RUN：真实 Provider / 实际 Key / 新包原生界面人工验收**。没有重跑 Java/Desktop/Frontend 全套、D2 原生矩阵、整机重启或历史验收。D2 历史 **PARTIAL** 和 Development-only 不变。

独立启动器：

`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-market-data-m12-fix-development\Open-Workspace-Finance-Market-Data-M12-Fix.cmd`

该目录不覆盖旧包，沿用原本机运行前提及已绑定 Profile；不会重新输入或替换已有 Key。检查只执行 CheckOnly，没有通过客户端主动刷新。最终 Git 交付 HEAD 是此后 docs-only 提交；无需因报告变更重新构建或重跑通过的测试。

最少人工复验：正常退出旧客户端后打开新启动器 → Finance → 投资组合统计或持仓 → 分别手动查看 SPY 与 USD/CNY。若仍失败，记录界面上的结果、Stage / Reason 即可，不提供 Key、原始响应或真实价格。股票每次最多 2 次请求，FX 1 次；遇 RATE_LIMITED 等待至少 60 秒再试。AAPL 既有成功只保留为用户历史证据，本轮不替代复验结论。
