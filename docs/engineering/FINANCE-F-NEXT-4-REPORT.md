# Finance F-next-4 — Budgets 只读入口与来源能力边界

2026-10-24，Asia/Shanghai。**Development-only；预算列表、详情及统计未交付，原因是冻结来源没有预算领域。不是 Production GO。**

从远端已核对的 `codex/finance-f-next-3 @ 1da1ccfb39bc9c506afc4ba544f152ff4b2dc8b7` 建立 `codex/finance-f-next-4`。开始前按指定临时代理 fetch 成功；原八个 B4A6 未追踪文件保留。

## 来源事实与实际交付

只读核对 `personal-finance-OS @ c6c87a0627b746e70a45b1c7896f63defd76a810`：

- `backend/src/main/java/com/financeos/module` 只有 account、ai、asset、auth、category、dashboard、importing、investment、ledger、user；无 Budget 实体、DTO、Mapper 或 Service。
- `backend/src/main/resources/db/migration` 的 V1–V21 无预算表、周期、限额或预算统计定义。普通分类、交易支出与 Dashboard 汇总不能充当预算额度或预算使用规则。
- `frontend/src/App.tsx` 无预算路由；现行 `docs/architecture/current-architecture.md` 和 `api.md` 无预算业务接口。`docs/archive/review/2026-07-06-architecture-review.md` 的旧模块概述提到“预算”，不构成已实现领域证据。

因此本轮实现 **React Budgets 独立视图 → 严格空 payload `finance.budgets` → Native → `GET /api/v1/finance/budgets`** 的真实纵向链路。服务端返回有界的单字段能力状态，不返回虚假的空列表或零金额：

```json
{"state":"UNSUPPORTED"}
```

状态仅有 `UNSUPPORTED / DISABLED / NOT_BOUND / BINDING_INVALID / UNAVAILABLE`。`UNSUPPORTED` 只在已确认 Finance 用户及数据集身份验证成功后返回；没有预算记录、金额、币种、周期、分类、已用、剩余、比例或超支判定，不能将它解释成“没有有效预算”或“未超支”。不存在可查询的预算详情，也不增加无来源支撑的分页、周期或分类筛选。所有 query string（包括客户端 userId）拒绝。

页面复用既有导航按钮、卡片和提示样式，保留 Accounts 中的账户总览与列表及 Transactions。提供加载、手动刷新、数据库不可用提示、错误重试及原生配置入口；Runtime 生命周期变化、Bridge session 变化或离开视图清除状态并拒绝迟到响应。没有重新设计主题、侧栏或组件体系。

## 金融与数据保护

`FinanceAccountQueries.budgetAvailability` 复用原 `query`，每次执行 Native 身份、已确认用户、ACTIVE 状态、历史用户指纹与数据集指纹校验，沿用独立 Finance DataSource 的 read-only、REPEATABLE_READ、REQUIRES_NEW 事务。没有读取其他用户预算的代码路径，也没有金融写 API。

账户与交易的 BigDecimal 金额字符串合同、只读查询和用户过滤原样保留；React、Native 与 AI 均未新增金额计算。Finance V1–V21、Workspace V1/V2 和 provenance 文件未修改；原启动恢复校验、绑定、DPAPI、Memory、Conversation、Knowledge 的生产实现未修改。

直接复用 F-next-3 的独立合成资源 `127.0.0.1:55424` 与 Profile `471c7d7fa11b451f9dc46ca65e30ac78`，只读核验已验收数据；没有准备预算假数据、创建新表、向旧夹具补数据、重绑或覆盖 Profile。没有连接或修改真实 Finance 数据。

## 本轮实际验证

原始日志及截图在忽略目录 `.verification`，不提交数据库、私有 Profile、凭据或本地临时产品检查脚本。

| 实际执行 | 结果及范围 |
| --- | --- |
| Maven package，选择现有 FinanceAccountQueriesTest 的身份前置拒绝与受信读取两个方法 | PASS；覆盖预算状态的 Native/未绑定拒绝、只读事务、改变数据集与停用用户拒绝；原精确账户金额断言保留。不是 Java 全套 |
| 现有 FinancePage 综合回归 | PASS；扩展预算能力缺失、拒绝额外金融字段、错误重试和迟到 session 响应清除。没有新增组件测试文件 |
| 现有 Desktop PayloadMustBeExactlyEmptyAndNoArbitraryUrlPathOrExecutableIsAccepted | PASS；将 finance.budgets 纳入原空 payload 校验，userId/path/token 等额外字段拒绝 |
| Frontend typecheck / production build、Desktop Profile Debug build / publish、Runtime JAR 审计 | PASS |
| 复用原只读 Windows 综合流，模式 gui-finance-next4 | PASS，退出码 0；真实 WPF/WebView2/React/Bridge/Java/PostgreSQL 显示预算能力缺失，userId/无来源筛选参数 400、无 Bearer 401、no-store；Runtime 真实重启后再查成功 |
| 同一 Windows 综合流的原账户与交易回归 | PASS；账户分页/筛选/排序/详情，交易列表/详情，9007199254740993.10 与 9007199254740993.30 精确字符串，其他用户账户及交易拒绝；已有三个 Workspace Store 的保存证据与绑定哈希一致 |
| 同一综合流 1280 / 800 宽度 | PASS，document 无横向溢出；实际查看 WebView 截图 fnext4-1280.png / fnext4-800.png |
| 新开发包实际 CMD / EXE / 独立 JAR，fnext4-product.log | PASS，退出码 0；CheckOnly、Native Gate、预算说明、原账户及交易详情、正常托盘退出释放 Runtime、CMD 重开后再次查询及已有三个 Store 快照一致 |
| 新包 payload 与受保护文件核对 | PASS；新 BUILD-INFO 每项哈希匹配，原绑定/两份 DPAPI、Finance V1–V21、Workspace V1/V2、八个 B4A6 文件哈希一致；原 F-next-2 / F-next-3 开发包 payload 匹配各自交付清单；冻结 Finance HEAD 与工作树不变 |

预算金额及统计口径验证 **不适用／未执行**：来源没有可验证的预算事实，不能计作通过。没有运行 Java/Desktop/Frontend 全套、历史大型安全矩阵、压力测试、IME 或完整打包矩阵，也没有真实 Windows 重启。继承 F-next-3 recovery 的安全逻辑不变，本轮不重复停止数据库或重跑恢复拒绝矩阵。

首次 Java 尝试遇到旧测试的 `NotAMockException`：原 verifyNoInteractions 错误接收新建的普通 listing 对象。只在该测试内改用实际注入的 mock listing，保留原“数据库访问前拒绝”断言，重跑聚焦验证通过；没有改正确的生产行为来迎合测试。临时产品脚本首次在 CheckOnly 成功后读取尚未定义的 LASTEXITCODE，修正本地脚本为检查 PowerShell 调用结果；该次未计为产品通过。

## 独立开发客户端与验收

```text
D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-fnext4-development-20261024\Open-Synthetic-Finance.cmd
```

沿用原受信 Native Gate 和 PostgreSQL 安全恢复启动链，内嵌新独立 JAR 的来源 pin。旧开发目录未覆盖。BUILD-INFO 的 format 为 `FINANCE_F_NEXT_4_DEVELOPMENT`，实际构建记录 `sourceCommit=1da1ccf... / sourceDirty=true`；独立 JAR 与 payload 哈希标识构建字节。报告提交后不为更改该历史记录重复打包。

同机 Development-only，依赖已有 JDK 21、.NET 10、WebView2、PowerShell 7.4+，不要移动目录或替换 pin 的依赖。正常启动不 initdb、不重建、不重绑、不回退 SQLite，不修改系统服务或真实数据。

最少人工步骤：双击 CMD → Finance → Budgets，确认“当前绑定身份已验证”和来源不支持的说明；点击刷新预算；切到 Accounts / Transactions 查看原列表与一条详情；正常托盘退出并重开。预算列表及详情验收须等待权威来源提供实际模型和规则，当前无法完成。

下一轮建议先迁移来源已实现的 Assets 只读列表与详情，再接 investment 的持仓、事实及服务端统计，继续保留币种与估值时间语义。预算领域定义需另行明确授权，不由 Workspace 按普通支出推造预算体系；整体视觉优化仍待主要 Finance 功能迁移完成。

正常 Commit / Push 至 `codex/finance-f-next-4`；最终 SHA 与远端核验见交付回复。不 force push、merge main、amend、tag 或 Release。交付时跟踪文件干净，仅保留原八个 B4A6 未追踪文件；冻结 Finance 仍为上述 SHA 且工作树干净。
