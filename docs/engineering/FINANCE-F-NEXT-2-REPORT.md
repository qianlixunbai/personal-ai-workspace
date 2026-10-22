# Finance F-next-2 — 独立导航与只读账户管理

2026-10-23（Asia/Shanghai）。**Development Acceptance PASS：真实 WPF/WebView2/React/Java/PostgreSQL 综合流、独立产品 EXE 与 SQLite 隔离已执行通过。F-next-2 用户物理托盘退出/重开验收 PENDING；不是 Production GO。**

## 基线与保护

- 从已推送的 `codex/finance-f-next-1 @ e53d668b9e3caef4cd9e88a222695b75fe3483c3` 建立 `codex/finance-f-next-2`。开始时实际核对工作树和远端，按指定临时代理成功 fetch；未更改全局 Git 配置。
- 用户反馈的 F-next-1 打开、账户刷新、物理托盘退出、重开及数据保持，记录为 **F-next-1 Development Human Acceptance PASS（用户人工反馈）**，已补入上一阶段报告。不是本轮重新执行，也不是 Production GO。
- 起始 8 个 B4A6 未跟踪文件原样保留、不暂存。冻结 Finance 仓库保持 `zh-cn @ c6c87a0627b746e70a45b1c7896f63defd76a810` 且工作树干净；仅只读参考模型与查询服务。
- 没有修改 Finance V1–V21、Workspace V1/V2、数据源或迁移历史，没有金融写入、真实数据迁移、跨库事务、JWT 登录、AI 金融操作或新依赖。

## 产品行为

- 左侧一级 `Finance` 与原页面并列，沿用现有主题、字体、卡片和路由。原会话、Memory 草稿和 Knowledge 页面生命周期保持。WebView 只新增精确 `#/finance` 白名单，不接受账户子路径或任意 URL。
- 总览展示服务端验证后的用户名、Finance 连接状态、全部币种/状态的账户数量、CNY 总余额和本次成功刷新时间。CNY 总余额继续调用原 `AccountQueryService.sumBalanceByUser`，包含使用中及停用 CNY 账户，不折算其他币种。
- 账户列表提供手动刷新、状态筛选、稳定排序、每页条数、上一页/下一页、空状态和重试。显示名称、类型、状态、币种、精确原币余额及系统内账户 ID；没有银行卡号字段。USD 合成账户明确显示 USD，不虚构 CNY 折算值。
- 点击账户后再次从服务端读取详情，展示名称、类型、系统内账户 ID、币种、状态及精确余额。无法访问、已删除或不存在的账户使用同一安全反馈，不泄露他人账户是否存在。
- Settings 保留 Finance 连接检查和原生身份绑定入口，主要账户浏览移到 Finance。关闭、未绑定、身份改变、不可用均有明确状态和安全配置入口，不自动选用户或重新绑定。
- 页面离开、Bridge session 变化、Runtime 生命周期变化及刷新开始时清除旧金融快照。只发布本次全部读取成功的结果；迟到响应不能恢复旧余额。Native 返回前还检查当前 Runtime 进程和就绪状态。
- 宽表格使用局部滚动，窄窗口的总览改为单列；没有引入第二套状态管理或重写 Workspace。

## 查询与金额合同

新增 `GET /api/v1/finance/accounts?page=0&size=10&status=ALL&sort=ID_ASC`，以及 typed bridge `finance.list`、`finance.get`。详情继续复用既有 `GET /api/v1/finance/accounts/{accountId}`。

| 参数 | 合同 |
| --- | --- |
| page | 从 0 开始，0–100000；服务端校验 |
| size | 1–20；UI 提供 2、10、20 |
| status | ALL / ACTIVE / INACTIVE |
| sort | ID_ASC / ID_DESC / NAME_ASC；名称排序以 ID 升序打破并列 |
| total / accountCount / id | 十进制字符串，避免 JavaScript 整数精度损失 |
| balance | BigDecimal.toPlainString；Native 与 React 只验证和展示字符串 |

`FinanceAccountListing` 只做带用户 scope 的 COUNT 和 LIMIT/OFFSET 查询。SQL 排序片段来自服务端固定 switch，其他值均为绑定参数。未知/重复查询参数及客户端 userId 被拒绝；未一次性把所有账户交给前端伪装分页。

所有列表、详情和总览仍经 `FinanceAccountQueries.query`：要求 Native 身份和已确认绑定，在 `financeTransactionManager` 的 read-only、REPEATABLE_READ、REQUIRES_NEW 事务内重读数据库/用户历史指纹，再使用服务端绑定用户 ID。新增读取没有接触其他用户的账户。

总览 JSON 在原 state/account/cnyBalance 上增加 username/accountCount；这是本轮明确的 typed DTO 扩展，Runtime、Native 和 React 同步发布并严格校验字段。旧严格客户端不能假定与新 Runtime 混用。没有修改持久化格式、绑定 format 1 或 Bridge 会话协议版本。

## 本轮实际验证

下列 PASS 均有本轮执行证据；原始日志、截图留在忽略目录 `.verification`，不上传私有 Profile、凭据或日志。

| 验证 | 结果及范围 |
| --- | --- |
| Java 聚焦 `FinanceAccountQueriesTest#trustedReadPreservesAmountsUserScopeAndTransactionThenRejectsChangedIdentity` | PASS；复用既有测试并扩展新列表的身份变化拒绝。保留金额、用户 scope、只读事务和同库连接断言 |
| Frontend `FinancePage.test.tsx` | PASS；一个综合回归验证精确字符串、刷新清空、Runtime 变化和迟到详情拒绝、显式重试及无效绑定反馈 |
| Desktop `PayloadMustBeExactlyEmptyAndNoArbitraryUrlPathOrExecutableIsAccepted` | PASS；扩展现有测试拒绝 Finance get/list 的 userId、路径、溢出 ID、越界分页和任意排序 |
| Desktop `ProductionNavigationResourcesFramesPopupsPermissionsAndDownloadsFailClosed` | PASS；原有安全测试补入精确 Finance 路由及非法账户子路径 |
| Java package、frontend typecheck/build、Profile Debug Desktop build/publish | PASS；Java package 跳过测试，不能当成全套测试通过 |
| `fnext2-e2e-final.log` / `gui-finance-next2` | PASS，退出码 0；真实 AssistantApp/WPF/WebView2/React/Native Gate/HTTP/Java/PostgreSQL |
| `fnext2-sqlite-isolation.log` / `gui-finance-isolation` | PASS，退出码 0；独立 SQLite Profile，Finance disabled、无绑定、三类业务数据隔离，真实页面显示关闭 |
| `fnext2-product-acceptance.log` | PASS，退出码 0；从新发布目录启动实际产品 EXE，通过 UI Automation 打开 Finance、确认总余额、打开详情，再走自动化托盘菜单退出，Runtime 监听已释放 |
| `fnext2-1280.png`、`fnext2-800.png` | 实际检查 WebView 总览截图；两种窗口宽度的 document 横向无溢出断言通过。表格使用局部滚动 |

综合流实际检查了 Alice 账户 ID 1/2/4 的服务端分页、ACTIVE/INACTIVE 筛选、ID 升降序和名称排序；详情金额 `9007199254740993.10 CNY`、CNY 总额 `9007199254740993.30`、零钱 `0.20`、USD `999.99`；Bob ID 3 返回 404，非法/溢出 ID、分页、筛选、排序、userId 和重复参数返回 400，超出数据范围的合法页为空；响应维持 no-store。Runtime 真正退出/重启后恢复查询，原绑定文件哈希以及 F-next-1 Memory、Conversation、Knowledge 内容和检索均保持。

全部复用 `127.0.0.1:55423` 的已有合成数据库与已确认 Profile `86705e85cb5b4a22869dd34849e0c07b`。Alice 原有 3 个账户足够验证分页，所以没有新增 fixture、修改金额、停用数据库角色、更改历史用户或重做绑定。SQLite 使用既有独立合成 Profile `018c1b6c3d7c4c57b0e4f8bd4cee24dd`。

未重新执行 F-next-1 数据库创建、真实历史身份/数据库替换/断连故障注入及完整安全矩阵；这些沿用上一阶段证据。新列表指纹拒绝用聚焦回归验证；本轮不能把历史断连或缺失绑定证据写成重新执行的真实故障注入。没有运行 Java/Desktop/Frontend 全套、IME、压力、交易写入或完整打包矩阵。

失败尝试未记为 PASS：新页面最初遗漏 WebView 精确路由，已补齐；页面移除了对旧 ShellStatus 的不必要阻塞，实际读取仍由 Native Gate/Runtime 判定。综合测试名称排序比较曾因测试端 JSON Unicode 转义方式错误而失败，已修复比较方式。独立 EXE 自动化最初把金额/币种当成同一辅助文本节点，并把 aria-pressed 账户按钮当成 Invoke 控件；按真实文本节点和 TogglePattern 修正后通过，未修改产品金额或削弱断言。外部屏幕抓图捕获了锁屏背景，未作为 UI 截图证据；上述两张有效图片来自真实 WebView CapturePreview。

## 开发客户端

目录：`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-fnext2-development-20261023`。

双击 `Open-Synthetic-Finance.cmd`，等待工作区启动后从侧栏打开 Finance。已使用原合成 Profile 和绑定，不需要重新输入密码。目录内提供 README、BUILD-INFO.json、独立 Runtime JAR 和受信来源清单的构建摘要。

这是本机 Development-only 目录，依赖既有 .NET 10、WebView2、`D:\MC\JAVA\JAVA21` 和合成 PostgreSQL。manifest v2 的 Runtime JAR 固定到新目录的 `runtime/personal-ai-workspace.jar`，不再依赖后续可被构建覆盖的 target JAR；JDK 和 JNA 仍是已 pin 的本机受信依赖。不要移动该目录或替换依赖，不是跨设备分发包。

没有覆盖 F-next-1 开发目录，也没有把 Profile、DPAPI、Bearer、明文数据库密码放入新目录或 Git。F-next-1 原 manifest 使用共享 target JAR，后续重新构建会使其旧 pin 失效；保留目录不等于旧版本可以继续混用新 Runtime。新交付避免了这一共享 JAR 依赖。

最少人工步骤：打开新入口 → Finance 核对 Synthetic Alice/总余额 → 每页 2 条并翻页 → 点击 CNY 账户检查详情 → 从**物理托盘**退出并重开后再查看。此最终用户人工确认仍 PENDING；自动化托盘检查不代替用户反馈。

## Git 与下一阶段

本轮仅提交必要源码、聚焦测试/验收入口和报告，正常 Push 至 `codex/finance-f-next-2`，以交付回复及 `git ls-remote` 的最终 SHA 为准。未 merge main、force push、amend、tag 或正式 Release。交付时跟踪文件应干净；保留原 8 个 B4A6 未跟踪资产。

F-next-3 建议先实现交易流水只读列表及明细，继续使用当前身份/数据集 scope、精确金额和有界分页，再评估预算/资产只读总览及受限只读 AI Tools。金融写入、转账、导入和投资执行须独立设计及验收，不由本轮开放。
