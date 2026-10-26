# Market Data M1.2 开发收尾

2026-10-27，Asia/Shanghai。M1.2 在既定个人显示范围内完成；D2 原生验收仍为 **PARTIAL**，整个 Workspace 未获 Production GO。

## 用户提供的真实验收证据

用户报告在真实 Windows 人工验证中，AAPL 美股日终行情、SPY ETF 日终行情和 USD/CNY 均成功；USD/CNY 显示 Massive 来源和参考日期，旧版 Runtime 冲突已修复。此为**用户提供的真实人工验收结果**，不是 Codex 本轮亲自运行的 Provider 验证；本轮未读取或使用个人 API Key、未请求真实行情。

## USD/CNY 参考日期

用户报告 2026-10-26 获取时参考日期为 2026-10-20，相差六个日历日。按 [Massive Forex Custom Bars 官方文档](https://massive.com/docs/rest/forex/aggregates/custom-bars)，免费 Currencies Basic 提供日终数据，外汇 bar 由报价生成，窗口无新报价时可无 bar；`t` 表示窗口起点，`c` 为该窗口收盘值。[官方说明](https://massive.com/knowledge-base/article/how-are-massives-forex-bars-built-and-what-do-v-and-n-mean-on-a-forex-bar)进一步说明 Forex 的 `c` 是窗口内最后报价的 bid，不是中间价或成交价。日终和周末因素本身不能证明此次六天差距的原因。

当前实现查询最近 14 天的已完成日期，并在返回的 bar 中选择时间戳最大的合法项；它只能选择实际返回的数据，不能证明服务商不存在更新数据。没有用户当时的原始响应或完整请求时间，也未进行实时 Provider 验证，具体原因**未知**；不推测 Provider 故障、不修改数据或扩大查询。

行情卡片现在在每个返回样本旁明确显示：日线可能较早、获取成功不代表最新行情，应以参考日期为准；参考日期、日线窗口起点和本次获取时间继续分开显示。它不是自动新鲜度判定，也不保证市场最新值。

## 范围、验证与交付

- 能力：个人非商业屏幕显示 Massive AAPL、SPY、受支持的 US/USD 股票或 ETF 持仓日线，以及 USD/CNY 日线参考值；提供来源、日期、获取时间及安全失败分类。用户真实验收覆盖 AAPL、SPY 和 USD/CNY，其他持仓路径不因此推定已真实验收。
- 边界不变：不持久缓存 Massive 行情、不提供离线回放、不把 Massive 行情用于衍生估值；Finance 账本保持只读，不改变许可、金融数值精度、持久格式或身份准入。
- 本轮实际执行：既有行情组件聚焦测试通过，覆盖合成 10 月 20 日参考日期、10 月 26 日获取时间与新鲜度提示；TypeScript/Vite production build 通过（保留现有大 chunk 提示）；新包 CheckOnly、全部 payload/记录源文件哈希及内置提示检查通过。没有重跑 Java/Desktop 全套测试、真实 Provider 或本轮新包的原生 UI；历史验证不能计作本轮执行。
- 新独立启动器：`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-market-data-m12-closing-development\Open-Workspace-Finance-Market-Data-M12-Closing.cmd`。它不覆盖历史包；BUILD-INFO 如实记录基线 `ccac4d9763169b8970855757b135987d2a7a2868`、`sourceDirty=true` 和构建源/文件哈希。Java 沿用已审计的 FX Precision JAR，SHA-256 `412eab8dbf32af1664e7911d46dd1ed7ab230631255590063e35da54967c2a0f`。用户真实验收针对先前版本，不冒称本次纯提示改动后的新包也已人工复验。
- 遗留：10 月 20 日样本为何较早尚未查明；若将来有必要，可在用户自己的授权环境中核对日期和服务商支持信息，不应分享密钥或原始敏感响应。D2 原生 PARTIAL、正式发布准入与 Workspace Production GO 不在本次收尾范围。

建议暂停市场数据功能开发，下一阶段转向 V1 Vision Foundation；该建议不改变 Vision 现有门禁或预先授予验收结论。
