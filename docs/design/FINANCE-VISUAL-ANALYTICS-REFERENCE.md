# Finance Visual Analytics D2 · 图表源码参考

2026-10-25，Asia/Shanghai。读取以下固定提交源码；没有运行第三方产品，没有复制第三方实现。四张用户图片已实际查看：浅色 Dashboard 参考信息层次；三张深色参考分别用于圆环与图例、多线趋势与选择、滚动台账。图片中的预测、金额、标的及目标比例不进入产品。

| 项目 / 检查提交 | 实际检查的源码 | 本轮借鉴与边界 |
| --- | --- | --- |
| [Actual Budget](https://github.com/actualbudget/actual) · `9231ec8f3ab31a6a835d9af3aedad53dd5321809` · MIT | [CashFlowGraph.tsx](https://github.com/actualbudget/actual/blob/9231ec8f3ab31a6a835d9af3aedad53dd5321809/packages/desktop-client/src/components/reports/graphs/CashFlowGraph.tsx) | ComposedChart 的柱与线、ReferenceLine 零基线、主题色、Tooltip 的月份与多项数据、容器自适应。Workspace 独立使用分组柱与净额线；不采用其前端金额加法、整数金额模型、余额曲线或转账口径。年份由 Java 持久交易查询产生。 |
| [Ghostfolio](https://github.com/ghostfolio/ghostfolio) · `4f17f0ecd4a357b860f835ab11c4d0034c20d2d0` · AGPL-3.0 | [allocations-page.component.ts](https://github.com/ghostfolio/ghostfolio/blob/4f17f0ecd4a357b860f835ab11c4d0034c20d2d0/apps/client/src/app/pages/portfolio/allocations/allocations-page.component.ts) | 多个比例视图、UNKNOWN 分组、持仓复合标识的点击、加载前 initialize 清空旧图、destroy 结束订阅。其价值字段回退为零及局部重新归一化不适用于 Workspace；本轮用完整数量分母，将覆盖与报价过期分别表达。 |
| [Firefly III](https://github.com/firefly-iii/firefly-iii) · `b9c31006e8d3008df3d12e23de86b66088c9480e` · AGPL-3.0 | [ExpenseReportController.php](https://github.com/firefly-iii/firefly-iii/blob/b9c31006e8d3008df3d12e23de86b66088c9480e/app/Http/Controllers/Chart/ExpenseReportController.php) | 服务端按时间段产生 bar/line 数据集，bcmath 合计与空集合状态。源码自身注明该图非多币种感知；本轮严格限定普通 CNY，并复用 D1 独占上界，不照搬其 inclusive 日期或账户组合模型。 |

许可实际读取：[Actual](https://github.com/actualbudget/actual/blob/9231ec8f3ab31a6a835d9af3aedad53dd5321809/LICENSE.txt)、[Ghostfolio](https://github.com/ghostfolio/ghostfolio/blob/4f17f0ecd4a357b860f835ab11c4d0034c20d2d0/LICENSE)、[Firefly](https://github.com/firefly-iii/firefly-iii/blob/b9c31006e8d3008df3d12e23de86b66088c9480e/LICENSE)。仅借鉴交互模式，不引入 AGPL 源码或第三方图表依赖。

## 实际适配评估与选择

现有 React 19.3、WebView2 Chromium、`style-src 'self'; script-src 'self'; connect-src 'none'`，每图最多十二个月、十三个分组；浏览器不接收原始历史账本。

| 方案 | 适配评估 | 结论 |
| --- | --- | --- |
| Apache ECharts | 官方支持 Canvas / SVG，提供圆环、柱、折线及事件；[官方渲染文档](https://echarts.apache.org/handbook/en/best-practices/canvas-vs-svg/) 建议按点量选择。静态检查 `34f4d927eafe251f26f63afd3aeefa30f2feba30` 的 [TooltipHTMLContent.ts](https://github.com/apache/echarts/blob/34f4d927eafe251f26f63afd3aeefa30f2feba30/src/component/tooltip/TooltipHTMLContent.ts) 发现默认 HTML Tooltip 写入 style.cssText，不能未经实际验证就宣称与当前 CSP 相容；需定制 Tooltip、主题、容器 resize/dispose、React 生命周期和等价键盘入口。npm 元数据：6.1.0、Apache-2.0、unpackedSize 60,297,703 bytes，非传输体积。 | 能实现，但当前有界点量无需额外渲染器及包装层。未安装或冒称完成 ECharts 原生 CSP 验收。 |
| Recharts | npm 3.10.1、MIT、React peer 包括 19；unpackedSize 7,452,998 bytes，非 bundle/gzip 体积。Actual 已用其 ComposedChart。检查 `9b4e20f1e3246753d2a3cea2756140f03a934347` 的 [TooltipBoundingBox.tsx](https://github.com/recharts/recharts/blob/9b4e20f1e3246753d2a3cea2756140f03a934347/src/component/TooltipBoundingBox.tsx) 发现 HTML 定位 style，且附 Escape 处理；仍需自定义 Tooltip/响应容器/CSP 评估，数值 dataKey 必须只用服务端坐标。 | React 集成便利，但不能解决金融精度和来源语义，维护成本高于本轮局部 SVG。未安装或做实际 WebView2 库验收。 |
| **现有 SVG + 局部 React 组件** | SVG 属性绘制圆环、双柱与折线，CSS 文件使用原主题 Tokens，无动态 style、eval、Canvas 或联网。坐标及占比来自 Java；title、固定详情区、语义月份按钮/图例及精确台账提供等价操作。点量固定，无大数据前端聚合。新增依赖 0；包体以实际生产构建记录为准。 | **唯一主要方案。** 与既有 D1 结构和安全策略吻合，实际浏览器及 Windows 验收另见工程报告。 |

静态源码推断与实际运行证据分别记录。两个库的包元数据由本轮 `npm view` 只读取得；没有将 unpackedSize 当成 WebView 下载成本。没有让任何图表组件计算金额、汇率、收益率或权威占比。
