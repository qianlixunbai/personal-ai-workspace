# M1.2 — FX 高精度显示兼容修复

2026-10-27，Asia/Shanghai。分支 `codex/finance-market-data-m12`，基线 `a732aee52c1fc21ecaa5361eb1d1fd662b601036`，起始工作区干净；按指定代理 fetch 成功。实现提交 **`18e967434e784733826300431b96efae6ba24cc3`**，后续交付提交只包含报告与证据。

用户人工复验报告 AAPL / SPY 真实成功，USD/CNY 失败于 `FOREX_BARS / PRICE_SCALE`。这是用户提供的历史证据；本轮不读取或使用真实 Key，不发起真实行情请求。**真实 USD/CNY：NOT VERIFIED**；确定性测试通过不代表真实 Provider 已成功。

## 契约与最小修复

[Massive 官方 Forex Custom Bars](https://massive.com/docs/rest/forex/aggregates/custom-bars) 将 `c` 定义为 JSON number，未列出 12 位小数上限。原 Java 的 FX scale≤12、precision≤32 和 C#/React 的小数≤12 都是应用自行设置的限制。此次仅修复这条显示合同，不改变接口、币对、日期或许可策略。

| 层次 | FX 新边界 |
| --- | --- |
| 数值安全 | 继续用 BigDecimal，严格正数且小于 `10^20`；拒绝 NaN、Infinity 等非有限形式。保留原数值量级上限。 |
| 表示与资源安全 | 无固定小数位/有效数字上限；规范十进制普通文本最多 128 字符。Java 根据 precision / scale 以 long 计算展开后的字符数，在 `toPlainString()` 前拒绝过长指数展开。128 是本应用的有限文本预算，不是 Massive 的精度承诺。 |
| 跨层合同 | Java 输出精确十进制字符串；C#、React 同步接受最多 20 位整数、总长最多 128 字符的正十进制文本，不转换为 CLR decimal、double、float 或 JS Number。合同仍为原 `price: string`，不新增字段或 API。 |
| 屏幕显示 | 沿用现有完整字符串显示和自动换行，不设 12 位显示精度，不截断、不四舍五入，不另建近似价格。原去尾随零逻辑仅规范表示，数值不变。 |
| 股票 / ETF | 原 8 位小数、32 位有效数字及 20 位整数边界保持；AAPL/SPY 身份匹配和请求流程不变。 |

例如确定性 FX 夹具使用 45 位非零小数，同时超过旧 12 位小数和 32 位有效数字；Java → response、C# 解码、React Bridge → 实际组件文本都保留全部字符。128 字符边界可通过；129 字符拒绝。真正超长仍返回原安全诊断 `FOREX_BARS / PRICE_RANGE`，不把数据截成可接受长度。

生产差异仅限 `MassiveClient.java`、C# MarketSample 解码及 React market-data 校验。复用原测试类、原测试方法扩展回归，没有新增测试类或长期测试方法。FinanceDomain / 账本 / Receipt / 历史迁移 / DPAPI 存储 / Native Gate / 许可守卫 / HTTP 配额与日期身份均未改动。Massive FX 不进入持久行情缓存或派生估值。

## 本轮实际验证

[命令记录](evidence/finance-market-data-m12-fx-precision/verification.txt) · [包完整性](evidence/finance-market-data-m12-fx-precision/package-hashes.json)。

- **PASS：Java 聚焦** `FinanceMarketDataTest`，高精度无损、128 字符/短指数边界、超长/数值量级/非正值/非法数字拒绝；原股票、身份、日期、配额、显示用途和无行情持久化集成证据复用。
- **PASS：C# 聚焦** `RuntimeClientTests.MarketData`，高精度与最大长度原样解码、非法表示拒绝；既有 SPY、许可及敏感字段合同保留。
- **PASS：React 聚焦** `FinanceMarketData.test.tsx`，高精度完整出现在屏幕文本，超长等无效输入不能显示，既有 SPY、权限及会话行为保留。
- **PASS：构建/包检查** TypeScript/Vite production build、Maven `-DskipTests package` 与实际 JAR audit、Profile Debug win-x64 publish、启动器 CheckOnly。构建不计作测试；原 Vite >500 kB 提示未扩大处理范围。
- **PASS：保护检查** 新包源为实现提交且 `sourceDirty=false`，源文件及 payload 哈希一致；M1 / M1.1 / M1.2 / 上一轮修复包与原清单一致。冻结 Finance OS 仍为 `c6c87a0627b746e70a45b1c7896f63defd76a810` 且干净；finance-domain 无差异。
- **NOT RUN / NOT VERIFIED**：真实 USD/CNY、本轮真实 AAPL/SPY、真实 Key、新包原生界面人工刷新。未重跑全套 Java/Desktop/Frontend、D2 历史矩阵或整机重启。D2 历史 **PARTIAL** 和 Development-only 不变。

## 新包与人工复验

独立启动器：

`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-market-data-m12-fx-precision-development\Open-Workspace-Finance-Market-Data-M12-FX-Precision.cmd`

新目录不覆盖任何旧包，继续使用本机已绑定开发 Profile；原 JDK21、.NET10、PowerShell7.4+、WebView2 与既有数据库前提不变。包只记录源码与 payload，不包含个人 Key、行情缓存或账户数据。报告提交为 docs-only，无需再次构建或重跑通过的测试。

1. 从托盘正常退出旧客户端，再打开新启动器 → Finance → 投资组合统计或持仓，使用已有原生配置，不重新提交 Key。
2. 主动刷新 USD/CNY，确认显示完整汇率、Massive 来源、UTC 参考日期及独立获取时间；不应因超过 12 位小数再得到 PRICE_SCALE。没有近似/舍入价格。
3. 可各查看一次 AAPL、SPY 确认原功能。FX 1 次 + 两个股票各 2 次 REST 共用每分钟 5 次额度；如触发限流，至少等 60 秒再操作。若 FX 仍失败，只记录安全 Stage / Reason，无需提交 Key、真实价格或原始响应。

只有用户在此新包中再次实际取得 USD/CNY 后，才能更新真实 FX 为成功；本交付继续标记 **NOT VERIFIED**。
