# Finance F-next-3 — 只读交易流水

2026-10-23，Asia/Shanghai。**Development 验证通过；不是 Production GO。**

从已推送的 `codex/finance-f-next-2-startup-recovery @ 01609f579ff20a74b446f19eefc4c8f8fd1574ed` 建立 `codex/finance-f-next-3`，已按指定临时代理 fetch 核对基线。

## 产品与边界

- Finance 保留原账户总览、分页、筛选和详情，增加 `Transactions · 交易流水` 视图。
- 服务端分页，每页 1–20 条，page 0–100000；按交易日期降序、ID 降序、来源升序稳定排序。支持账户 ID、开始/结束日期和交易类型组合筛选；结束日期包含当天，账户 ID 可在 Accounts 查看。
- 列表及点击后重新读取的详情展示精确金额字符串、原币、原始本地交易时间、关联账户、类型及备注。没有可用的通用交易状态字段，明确说明未提供，不生成状态。
- 原 `transactions` 和 V19 `transfers` 分别保留 `TRANSACTION` / `TRANSFER` 来源与 ID，避免两表同 ID 混淆。转账展示转出、转入账户，账户筛选匹配任意一端；历史单账户 TRANSFER 不推断另一端，不归类收入/支出。历史 REFUND、负 ADJUSTMENT 按原字段展示，不推算余额或折算币种。
- 加载、空数据、错误、重试、清除筛选齐备。切换视图/离开页面、Bridge session 或 Runtime 生命周期变化会清除快照并拒绝迟到响应；交易筛选也在身份会话变化时清空。

新增只读接口：

```text
GET /api/v1/finance/transactions?page=0&size=10&accountId=&startDate=&endDate=&type=ALL
GET /api/v1/finance/transactions/{TRANSACTION|TRANSFER}/{id}
```

类型支持 ALL / INCOME / EXPENSE / ADJUSTMENT / TRANSFER / REFUND。日期严格采用 YYYY-MM-DD；未知、重复参数、客户端 userId、越界分页或无效日期均拒绝。不存在和其他用户详情统一 404，其他用户账户筛选返回空集。

查询参考冻结 `personal-finance-OS @ c6c87a0627b746e70a45b1c7896f63defd76a810` 的 `TransactionService.pageByUser` 过滤/排序与 `TransactionResponse` / V19 转账字段。原 Service 同时包含金融写操作，因此只适配必要查询 SQL，复用 Workspace 既有 JdbcTemplate、Finance DataSource 和 `FinanceAccountQueries.query`，没有整体装配原写服务或引入新依赖。

每次列表/详情都经过现有 Native 身份、已确认用户与数据集指纹校验，在同一 Finance read-only、REPEATABLE_READ、REQUIRES_NEW 事务内查询。账户关联也带服务端用户 scope。金额使用 BigDecimal.toPlainString，ID/total 使用字符串；Native/React 不以浮点数计算金额。新增 Bridge 仅有严格白名单 `finance.transactions` / `finance.transaction`，沿用 Runtime 生命周期检查、Bearer、loopback、Browser 拒绝及 no-store。

没有金融写 API、AI 金融工具、历史 migration、数据源隔离或原绑定格式变更。

## 本轮实际验证

原始日志和截图位于忽略目录 `.verification`，不提交凭据、数据库或私有 Profile。

| 实际执行 | 结果 / 边界 |
| --- | --- |
| Java package，跳过测试 | PASS，包含可独立运行 Runtime JAR 及既有 JAR 审计；不代表完整测试套件 |
| Java 现有 `FinanceAccountQueriesTest#trustedReadPreservesAmountsUserScopeAndTransactionThenRejectsChangedIdentity` | PASS；补入交易列表/详情对改变数据集身份的拒绝 |
| 现有 `FinancePage.test.tsx` 综合回归 | PASS；扩展交易精确字符串、session 变化与迟到详情丢弃、显式刷新恢复 |
| Desktop 现有 `PayloadMustBeExactlyEmptyAndNoArbitraryUrlPathOrExecutableIsAccepted` | PASS；扩展交易额外 userId、非法来源/ID、账户路径及日期拒绝 |
| Frontend typecheck/production build、Desktop Profile Debug build/publish | PASS |
| `gui-finance-next3` / `fnext3-windows-e2e.log`，退出码 0 | PASS；真实 WPF/WebView2/React/Bridge/Java/PostgreSQL，分页同时间排序、组合筛选、转账两端、负调整、历史退款/转账、空列表、无效筛选后的恢复；账户及 Memory/Conversation/Knowledge 开关隔离、Runtime 重启和持久化读取通过 |
| 同一综合流的真实 HTTP / Native 读取 | PASS；Bob 普通流水、Bob 转账均 404，Bob 账户筛选为空，userId/重复参数/非法日期等 400，无 Bearer 401，响应 no-store；`9007199254740993.10`、`0.20`、`-0.10` 和 `12.34 USD` 字符串通过 |
| 同一综合流 1280 / 800 宽度 | document 无横向溢出；实际查看 WebView CapturePreview 截图 |
| `fnext3-product.log`，退出码 0 | PASS；新目录实际 CMD/EXE/独立 JAR，UIA 核对交易列表、详情和精确金额，正常托盘退出释放 Runtime、重开后绑定/DPAPI 与三个 Workspace Store 快照保持 |
| 新开发启动器 / payload 审计 | PASS；CheckOnly 通过；新夹具停止后经实际 CMD 正常恢复并成功进入上述真实产品页面；payload 与受信来源哈希逐项一致，无共享 target JAR 依赖 |

测试代码只扩展上述现有聚焦回归，并在原 Windows 综合工具中增加一条交易流程；没有新测试框架、独立组件测试或全套矩阵。未执行 Java/Desktop/Frontend 全套、压力、IME、完整打包矩阵或真实 Windows 重启。历史 F-next-2 recovery 安全拒绝矩阵未重跑；核心 recovery 校验代码保持原样。本轮正常停库恢复不代表掉电或物理重启验收。

未成功的尝试没有计为 PASS：首次新夹具缺少分类外键，修正在测试资源层，失败夹具正常停止并保留；Codex packaged AppData 重定向导致新 Profile 身份拒绝，通过正常 Explorer 上下文完成创建/运行，未放宽门禁或修改 ACL；新夹具初始参数顺序被 recovery 拒绝后按原严格顺序正常重启；临时 PowerShell 等待方式及 UIA 把 React 分段文本当成完整节点的假设已在本地 harness 修正。最终通过证据以上表为准。

## 数据保护与开发客户端

原合成 Finance 角色只授权旧账户读取。为避免更改已验收数据或权限，本轮使用 NEW 隔离合成集群 `127.0.0.1:55424`、独立 Finance/Workspace 数据库与新 Profile `471c7d7fa11b451f9dc46ca65e30ac78`。通过既有准备工具的显式 `-Transactions` 选项，在创建新库时放入最小样本；不提供对已准备数据库的补写入口。当前 Alice 可见六笔，Bob 的两种记录仅用于拒绝验证。原 `fnext1-target.json` 未覆盖。

旧八个 B4A6 未追踪文件、原 Profile 的绑定及两份 DPAPI 哈希逐项核对未变；未暂存 B4A6。没有改动旧数据库/角色、历史备份或冻结 Finance 工作树。产品检查观察到旧 55423 集群已停止，未尝试启动或修复它；新包只管理新隔离夹具。冻结 Finance 仍为上述 SHA，工作树干净。

开发目录：

```text
D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-fnext3-development-20261023
```

双击 `Open-Synthetic-Finance.cmd`，从侧栏 Finance 选择 Transactions。新包使用独立合成 Profile，不显示旧 Profile 的用户资料；旧包、旧 Profile 与已验收数据保留。恢复启动器继续执行原目录/ACL、文件与配置哈希、PostgreSQL 来源、进程/端口归属、system identifier、数据库 OID/marker 及 binding/DPAPI pin，确认停止态后才正常 start；不初始化、重建、重绑、改密码、删除 PID 或回退 SQLite。

这是同机 Development-only 客户端，依赖已有 JDK 21、.NET 10、WebView2、PowerShell 7.4+；不移动目录或替换 pin 的依赖。BUILD-INFO 保留实际构建时 `sourceCommit=01609f5... / sourceDirty=true`，独立 JAR 和 payload 哈希标识实际字节；提交报告不重复打包。真实 Windows 重启与用户物理托盘验收仍 PENDING，不授予 Production GO。

正常 Commit / Push 至 `codex/finance-f-next-3`，远端最终 SHA 以交付回复和 ls-remote 核验为准；不 force push、merge main、amend、tag 或 Release。交付时跟踪文件应干净，仅保留原八个 B4A6 未追踪文件。

下一阶段建议：继续预算/资产的只读总览，复用同一绑定与精确金额合同；金融写入继续保持独立授权边界。
