# Finance F-next-10 — 可直接复制的实施 Prompt

以下代码块可整体复制。来源口径、差异与依赖见 [剩余能力交接](FINANCE-REMAINING-CAPABILITIES.md)。本文件是下一轮实施指令，不是已完成的功能或验收记录。

```text
继续 Personal AI Workspace，直接实施 Finance F-next-10 — Dashboard & Cashflow Read-only Integration。

先读仓库规则、docs/engineering/FINANCE-REMAINING-CAPABILITIES.md、F-next-9 报告和实际 owning code。
Workspace：D:\IDEA\Daima\personal-ai-workspace。
规划分支：codex/finance-remaining-capabilities；其功能父基线为
codex/finance-f-next-9 @ aac471d8fc6a70697c4c1111658bb615ab5c69e4。
先核对实际 HEAD/工作树/最新报告，按现有规则 fetch；保护已有修改。
从已提交的规划分支创建 codex/finance-f-next-10，不覆盖同名已有分支。
冻结来源：D:\IDEA\Daima\personal-finance-OS @ c6c87a0627b746e70a45b1c7896f63defd76a810，只读参考。

目标：沿用 WPF/WebView2、React/TypeScript、Workspace 侧栏和 Finance 页面组件，增加一个 Dashboard 视图：
- CNY 账户总余额（含各状态，准确命名）；当月 INCOME/EXPENSE/net。
- 当前月及前五个月的普通收支趋势，服务端补齐六项。
- 来源支持的每条资产人工参考分布；最近五条 CNY 普通表流水。
- 手动刷新、错误重试、空状态、绑定异常处理与迟到响应丢弃。
F-next-1–9 已有读取不重做；Budgets 仍为 UNSUPPORTED，不实现预算。

关键口径已经确定：
1. 不原样复制原 Dashboard totalAssets/netWorth：它们把账户余额与人工当前价估值相加，且没有负债领域。
   本阶段不展示该混合总额，也不把 CNY 账户余额重命名为总资产或净资产，不用 0 冒充未提供指标。
2. Asia/Shanghai 业务 Clock，一次请求固定同一个 now。
   月收支：[本月1日00:00, now)；趋势：[五个月前月初, now)，按月升序补零。
   SQL 仅当前用户、CNY、INCOME/EXPENSE；net 用 BigDecimal 在服务端计算。
   V19 transfers、投资事实、REFUND、ADJUSTMENT 不进入普通收支。
   不直接使用原 MonthlyCashFlowTool 的完整月份范围替代该截至 now 的合同。
3. 分布只取 CNY assets 的非空 current_price/quantity、quantity>0、乘积>0；
   value=current_price*quantity；percentage=value*100/全部符合条件的乘积之和，4 位 HALF_UP。
   不按 type 分组、不合并同名、不含账户余额、不读取/刷新 Quote/FX、不并入 Portfolio 或净资产。
   独立标注人工参考、价格时间/新鲜度未知；缺价不冒充已估零，显示覆盖/排除信息。
   保留全量分母，稳定 ID_ASC 最多展示 20 条；带 total/complete，明确部分视图并可进入已有 Assets。
   不静默截断、不按本页归一化、不画冒充全体的部分饼图。
4. 最近五条来自当前用户 CNY transactions，不筛类型或未来日期；
   transacted_at DESC,id DESC（第二排序键是明确适配）；不混入 transfers/investment_transactions。
   账户仅当前用户；分类仅当前用户或 user_id IS NULL AND is_system=true；名称缺失沿来源未知名称。
5. 所有金融金额、百分比与 ID 端到端为十进制/ID 字符串；React 不求和、不减 net，不转换金融金额为 Number。

推荐最小实现：
- 新增窄 FinanceDashboardQueries/Controller，固定 GET /api/v1/finance/dashboard、空 payload finance.dashboard。
- 沿现有 FinanceTransactionQueries/FinanceImportQueries 的固定 SQL 适配模式；
  复用已迁入的 AccountQueryService 和 FinanceAccountQueries.query，缺少聚合按冻结来源 SQL 最小适配。
- 全部身份验证、统计、名称查询在一个 Finance 专用只读 REQUIRES_NEW/REPEATABLE_READ 快照内。
  不直接注册未指定 Workspace Finance 事务管理器的来源 DashboardService 注解来假定正确，不扩大包扫描。
- 衔接既有 RuntimeClient、typed allowlisted Bridge、React 类型与 Finance 页面；不新增通用代理/查询框架。
- 现有 /overview 与 Portfolio 合同不变；拒绝额外 query/身份字段，用户/数据集来自服务端受信绑定。
- 保持 Native Bearer、origin/session、严格 DTO、Browser Translate-only 和 Finance 默认关闭。
- 响应维持 HTTP 60 KiB/Bridge 64 KiB，核对转义最坏预算；超界安全拒绝，不抬全局上限。
- session/Runtime 切换、不可用、绑定失效与导航卸载清掉敏感快照；错误不展示旧身份结果。

架构决定无需重议：唯一 Unified AI Assistant + Shared AI Runtime。
不迁独立 Finance AI Analyst/CloudAiProvider/Orchestrator/会话/记忆系统；本阶段不加 AI 或 Tools。
The server owns financial truth。未来工具只经受控 Finance 应用服务取事实，LLM 不读库、不重算余额/成本/盈亏。

禁止：金融写入；上传/Preview/Mapping/Confirm/文件 cleanup；行情/FX 网络刷新；新迁移或数据库授权修改；
修改冻结来源、Finance V1–V21、Workspace V1/V2、历史数据库、Native 绑定、Profile/DPAPI/凭据、
B4A6 八个历史未追踪文件、任何旧 Development-only 包（含原 F-next-2 recovery 及历史哈希问题）。
无 UI redesign、新组件库、新测试框架或泛化架构。真实写入仍需独立设计、授权、验收。

验证保持最小：先检查复用已有测试/合成隔离环境。
新增统计的金额/月份/身份/快照若有重要覆盖缺口，只扩展少量 owning-layer 高价值集成证据，优先一个真实 SQL 流；
覆盖 CNY/类型/月界/补零/精确大额、双用户/指纹改变拒绝、刷新恢复，不把覆盖点变成多轮完整 E2E。
跨层合同变化只跑必要的 Native DTO/Bridge、前端聚焦与类型/构建检查，不机械跑三层全套或 Windows/IME/包装矩阵。
不以 mock 金融输出冒充真实 SQL 验收，不调用真实付费 AI Provider；相关检查通过即止。
复用可用夹具/入口，确有缺失时只作本切片所必需的补充，不另建测试基础设施。
遇到架构冲突、未知工作树阻塞或环境无法验证时报告精确阻碍，不改正确生产行为或放宽门禁。
本次不要求新 Windows 包或 EXE；代码验证与历史客户端/整机重启人工待办分开报告。

交付：小范围生产改动及必要的最少测试/夹具修改；docs/engineering/FINANCE-F-NEXT-10-REPORT.md；
明确来源口径、适配差异、实际执行/历史证据/未验证、Git 分支/HEAD/工作树和剩余风险。
授权正常 Commit/Push 本功能分支；提交前检查 status、实际 diff、staged diff、git diff --check，只纳入本任务文件。
禁止 force push、merge main、改历史、tag/release；仍为 Development-only，不授予 Production GO。
完成后给出简洁功能结果、实际验证、GitHub 链接和下一步，不停留在计划阶段。
```
