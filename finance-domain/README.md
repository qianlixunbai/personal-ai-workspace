# Finance Module — Read-only Integration

2026-10-24：F-next-6 增加 Investment Positions 只读列表与重新查询的详情，保留来源 OPEN/CLOSED/ALL、账户/Instrument 筛选和不透明 seek Cursor。原字节迁入投资查询、DTO、Mapper 与 Cursor 依赖，逐文件哈希见 [investment-source-provenance.json](investment-source-provenance.json)。继续复用已绑定 Native 身份、独立 Finance 只读快照和 F-next-5 Quote/FX/ReferenceValuation；账务投影、持久化人工参考与缓存市场估值分别展示，不迁入写服务、重放算法或 Portfolio 聚合。实际 Windows、双用户隔离及独立客户端证据见 [F-next-6 报告](../docs/engineering/FINANCE-F-NEXT-6-REPORT.md)。仅 Development-only。

2026-10-24：F-next-5 增加 Assets 只读列表、详情、服务端分页/类型筛选/稳定排序，以及人工价格估值和来源缓存参考估值。原字节迁入 `ReferenceValuationService`、Quote/FX 查询及依赖，来源与哈希见 [asset-source-provenance.json](asset-source-provenance.json)。每次查询复用 Native 绑定、数据集与用户指纹检查和 Finance 独立只读快照；未装配金融写服务或 Provider。人工价格缺失保留 null；参考估值仅支持 US STOCK/ETF、原币行情与至 CNY 汇率，未知/部分/过期状态不视为零或实时行情。详见 [F-next-5 报告](../docs/engineering/FINANCE-F-NEXT-5-REPORT.md)。仅 Development-only。

2026-10-24：F-next-4 增加 Budgets 只读入口与服务端能力状态。冻结 Finance 来源无预算模型、预算表或统计规则，因此经原绑定与数据集校验后明确显示 UNSUPPORTED，不生成预算列表、详情、金额或超支判定。账户总览、Accounts 与 Transactions 保留。见 [F-next-4 报告](../docs/engineering/FINANCE-F-NEXT-4-REPORT.md)。仅 Development-only。

2026-10-22：已实现 WPF 原生服务端身份核对/首次绑定，以及 typed bridge → Runtime → 独立 PostgreSQL 的 CNY 账户只读切片。
合成环境中的真实 WPF/WebView2 集成流、进程重开与 SQLite Profile 隔离已通过；独立产品 EXE 最终人工关卡 PENDING，非 Production GO。
Windows 绑定发布改为复用 Win32 文件身份，保留 CREATE_NEW / 不静默重绑；Finance 默认关闭，普通启动不迁移。
详见 [F-next-1 实现与验收报告](../docs/engineering/FINANCE-F-NEXT-1-REPORT.md)。以下 F2.3-A2 等内容保留为历史证据，不代表本轮重新执行。

## F2.3-A2 当前结果（2026-10-17）

真实 PostgreSQL 17.11 测试库 `personal_ai_workspace_test` 已从空库用 Flyway 12.4.0
完整初始化 V1–V21。原始 SQL 的一次性副本与 SHA-256 见
[migration-provenance.json](migration-provenance.json)；Git 对这些 SQL 禁用换行转换，保留原始字节。
Workspace 独立维护副本，运行和构建均不读取冻结 Finance 仓库。

真实 Account SQL、MyBatis/Plus、精确 BigDecimal/CNY、双向用户隔离、数据库身份指纹／ACTIVE
校验和 Finance 只读 REPEATABLE_READ／并发快照／失败回滚／连接释放验证通过。
仅执行一次聚焦 Maven 集成测试，BUILD SUCCESS；没有业务代码改动或新依赖。

**整体仍为 PARTIAL**：独立 JVM 中真实 `LocalFinanceProfile` 文件发布返回
`FINANCE_BINDING_INVALID`；此路径依赖 Windows 私有 ACL／Native Gate，未放宽准入。
数据库绑定校验使用真实 PostgreSQL 和真实绑定解析器，但文件读写端采用替代夹具。
不得将该结果计为真实绑定文件验收、首次日常绑定交付或完整 Runtime GO。
默认 Finance／Flyway 关闭、普通启动不迁移、MMF-3 PENDING 均保留。

本地复用：先在 Windows PowerShell 运行
`scripts/verification/prepare-finance-postgresql-test.ps1 -CredentialsOnly`，验证两个已有账号并私有暂存凭据，
再运行 `scripts/verification/run-finance-postgresql-test.ps1`。
runner 在成功或失败后删除临时凭据；不会保留管理员密码。
首次空库的管理员准备脚本仅在确认目标／空状态后创建专用迁移角色；已有角色时停止，不重复初始化。
复跑测试仅验证已完整迁移的 V1–V21 history，不对未知／部分 schema 再次 migrate。
测试只删除本次生成的合成用户／账户，不执行 clean、repair、baseline 或删除数据库。

完整权限、实际证据与剩余条件见
[F2.3-A2 Report](../docs/engineering/F2.3-A2-FINANCE-POSTGRESQL-INTEGRATION-REPORT.md)。
以下 F2.2-B/A 内容为历史阶段记录。

普通 Maven JAR，接入 Workspace 正式 reactor；只有 `workspace-application` 使用 Spring Boot
可执行打包。应用源码仍位于根 `src/`，默认输出仍为根 `target/`，JAR 名称及既有启动入口保留。
根 POM 聚合两个模块并继承原 Java 21 / Spring Boot 4.1.1 基线；原应用依赖、打包门禁和
`v1-isolated-development` profile 迁至 `workspace-application/pom.xml`，路径显式指回根目录。

依赖方向：`workspace-application → finance-domain`。Finance 不依赖 Shared AI Runtime。
后续 AI Tools 只能经受控应用服务读取事实，不能直接访问 Mapper 或金融写事务。

## 冻结来源

Personal Finance OS：`zh-cn`，提交 `c6c87a0627b746e70a45b1c7896f63defd76a810`。
本轮实际读取时工作树干净；F2.1 已复制 `AccountQueryService`、`AccountMapper`、`Account`，
本轮三个业务文件字节不变。源路径及复制前后 SHA-256 见 [source-provenance.json](source-provenance.json)。
保留 BigDecimal、CNY 过滤、userId 隔离、SQL 和 Mapper 所有方法，包括内部写方法。
不建立同步机制，构建不读取原 Finance 仓库，也不依赖其绝对路径或 JAR。

## F2.2-B 历史状态：SOURCE READY / DATABASE PENDING

已建立正式 Native 账户查询链；用户明确确认没有授权隔离测试数据库，因此本轮未连接 PostgreSQL，
没有真实 SQL／数据库事务／金额与用户隔离执行证据，也未在本机创建真实绑定。Finance 仍默认关闭。

### 本地档案与绑定

`LocalFinanceProfile` 在已有 `workspace.data-directory` 下使用独立的
`finance-identity/binding.json`，不修改 Workspace SQLite 或 Finance schema。
首次确认成功时服务端生成随机 UUID 档案标识，保存格式版本、Finance userId 和两个 SHA-256 指纹：

- 数据集：PostgreSQL 集群 system_identifier、数据库 OID、数据库名称。
- 用户：users.id、username、email、created_at。各字段按 UTF-8 字节长度编码后计算摘要；不读取密码哈希。

[PostgreSQL 控制信息](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-CONTROLDATA)
提供集群标识；每次账户读取都通过同一 Finance 事务重读数据库身份和 ACTIVE 用户，再核对指纹。
缺失绑定、用户消失／失效、数据集或用户指纹变化均拒绝查询，不按数字 ID、首条用户或 Windows SID 接管。
为避免历史账号被自动认领，本轮不新建 users；原 username／email 唯一约束、password_hash 非空和 status 保持。

首次历史绑定仅保留包内 `FinanceAccountQueries.confirmHistoricalBinding`：要求已认证 Native，
重新读取已人工审核的用户／数据库证明并精确匹配后，以 CREATE_NEW 创建绑定；已有文件一律拒绝覆盖。
它没有 HTTP、Bridge、AI 或普通用户确认界面入口，也不会在启动或读取时自动调用。
当前无法完成可信首次确认，故保持未绑定；后续受保护 Native 确认流程必须接入此边界，不能直接提交任意 userId。

档案文件复用 PrivateMemoryDirectory／WindowsPrivateAcl：校验私有位置与权限、拒绝链接、
限制文件大小、严格检查 JSON／版本／ID／指纹，并核对读取前后文件身份。错误与 toString 不包含私密内容。
中断写入或未知发布结果不自动修复／重绑。未运行受 MMF-3 限制的真实 Windows ACL／文件发布验收。

档案 UUID 不依赖计算机或 SID，可随未来备份保留。未来恢复须同时考虑此文件与 Finance 数据集；
逻辑恢复到新 PostgreSQL 集群／数据库后必须重新确认指纹，不能凭旧 userId 自动关联。
现有 Workspace 逻辑备份不等于 Finance 备份，本轮没有修改备份格式或实现统一恢复。

### 查询入口与事务

只有 `workspace.finance.enabled=true` 才装配应用服务／Controller：

- `GET /api/v1/finance/accounts/balance`：原 AccountQueryService 的当前绑定用户 CNY 余额合计。
- `GET /api/v1/finance/accounts/{accountId}`：原 findAccessibleAccount，只读取绑定用户拥有的指定账户。

路由拒绝全部 query 参数，不接受客户端 Finance userId、profileId 或数据集标识。
原 LocalClientFilter／SecurityFilterChain 不变：Native Bearer、loopback、Origin 与 Browser allowlist 继续生效。
FinanceAccountQueries 额外要求认证上下文中的真实 Native principal 与 ROLE_LOCAL_CLIENT，
不使用 ClientIdentity.current() 的无认证 Native 回退。AI Runtime 没有注册金融 Tool 或 Mapper 入口。

调用链：Native 授权 → 私有绑定 → FinanceIdentityRepository 的用户与来源校验 → 原 AccountQueryService。
JdbcTemplate 和 MyBatis 都使用 financeDataSource；其连接显式指定 public，避免用户同名 schema 遮蔽原账户表。
TransactionTemplate 显式使用 financeTransactionManager，建立 30 秒、readOnly、REPEATABLE_READ 的独立新事务，
在同一 Finance 快照中核验身份与查询账户。没有全局事务代理或跨库事务，不修改其他业务事务。
三个迁入业务文件保持原字节与 SQL／过滤逻辑。金额始终以 BigDecimal 计算，DTO 金额与账户 ID 输出字符串，
不暴露用户标识；只读账户入口没有写操作，SQL／连接失败返回脱敏 FINANCE_UNAVAILABLE。

### 本轮实际验证（2026-10-17）

仅执行一次 Maven 调用，test 生命周期包含受影响模块的编译：

```powershell
.\mvnw.cmd -B -ntp -pl workspace-application -am '-Dtest=FinanceAccountQueriesTest,FinanceRouteSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

BUILD SUCCESS，只执行上述两个新增聚焦测试类。最小 Context 验证新增组件默认缺席／显式装配；
确定性 JDBC／Mapper 替代夹具验证 Native／未绑定拒绝、严格绑定解析、用户和 CNY 查询参数、
超出 JavaScript 精确整数范围的金额字符串、同 ID 换集群／失效／缺失用户拒绝，以及 Finance JDBC 只读快照的提交／回滚。
原认证过滤器的聚焦新路由验证确认无效 Bearer／Browser 被拒绝、Native Bearer 可进入只读 Controller。
这些是替代验证，不是 PostgreSQL 真实 SQL、实际用户隔离、金额或数据库事务验证。
日志：`.verification/f2.2-b-finance-account-20261017/maven-focused.log`（本地忽略文件）。
未重跑 F2.2-A、旧 Finance、V1、MMF-3、Browser 全量、GPU／Vision 测试或打包；没有新增 Maven 依赖。
Windows Gate、配置白名单和 ACL 不变，MMF-3 仍 PENDING；未授予 Runtime、Database 或 Production GO。
进入完整业务迁移前先解决受控 PostgreSQL／原 schema／最小权限、可信首次绑定，以及真实 Account SQL／金额／隔离／事务验证。
本轮到此停止，不进入 F3。

## 显式装配与默认关闭

启动层导入 `FinanceInfrastructureConfiguration`，由它复用 F2.1 的
`FinanceAccountQueryConfiguration`，不扩展全局 `com.financeos` 组件扫描。
沿用唯一开关 `workspace.finance.enabled`，缺省／false 不创建 Finance DataSource、SessionFactory、
SessionTemplate、TransactionManager、Flyway、Service 或 Mapper。正常 Workspace 无需 PostgreSQL 配置。
`application.yml` 显式保持 Finance 和 Finance Flyway 两个开关为 false。

开启后必须提供以下三个属性，缺失或空白时报出属性名称，不回显值：

| 属性 | 合同 |
|---|---|
| `workspace.finance.datasource.url` | 显式 `jdbc:postgresql://本机主机:端口/数据库名`；主机仅 `127.0.0.1`、`localhost` 或 `[::1]`，端口 1–65535；数据库名限字母、数字、下划线、点、短横线，首字符限字母、数字、下划线 |
| `workspace.finance.datasource.username` | 必填，无默认值 |
| `workspace.finance.datasource.password` | 必填，无默认值；仅放本地私有配置，禁止 Git、命令行参数或日志 |

拒绝远程主机、SQLite URL、多主机、URL 查询参数和 URL 内凭据；不推断数据库、不自动建库或回退。
使用 PostgreSQL `PGSimpleDataSource`，不引入连接池；连接超时 5 秒、socket 超时 30 秒。
装配不建立连接，因此装配成功仅代表配置与依赖关系有效，不代表数据库已就绪。
后续连接／SQL 失败会传播异常，不提供替代数据或宣称启用成功。
仅导入真实 `AccountQueryService`，Mapper 扫描限于账户 mapper 包，且要求 `@Mapper`。

| Bean | 显式绑定 |
|---|---|
| `financeDataSource` | 上述 Finance 专有配置；PostgreSQL 驱动，无共享数据源回退 |
| `financeSqlSessionFactory` | `financeDataSource`；MyBatis-Plus factory、下划线映射、禁用 SQL 日志 |
| `financeSqlSessionTemplate` | `financeSqlSessionFactory`；账户 Mapper 唯一绑定 |
| `financeTransactionManager` | 同一个 `financeDataSource`；后续金融事务必须显式选择此管理器 |
| `financeFlyway` | 同一个 `financeDataSource`；额外要求 `workspace.finance.flyway.enabled=true` |

基础设施 Bean 均为 `defaultCandidate=false`，只通过限定名装配，不标记 `@Primary`。
不启用全局事务代理、不配置通用 `transactionManager`，也不引入跨库事务。
现有 SQLite 仍由原代码通过 DriverManager 管理，未修改文件、schema 或事务语义。
未改变 SecurityFilterChain、Native Bearer、Browser／Bridge 或身份边界。
F2.2-A 当时没有 Finance HTTP API；F2.2-B 仅新增上述 Native 账户只读入口，没有登录、金融写接口或 AI Tools。

MyBatis-Plus 3.5.17、MyBatis 3.5.19、MyBatis-Spring 4.0.0 保持不变。
新增 PostgreSQL JDBC 42.7.13、Flyway core／PostgreSQL 12.4.0，均使用现有 Boot 4.1.1 BOM。
未增加 JDBC／MyBatis／Flyway Starter 或 Boot JDBC／Flyway 自动配置模块；没有全局自动配置排除。

## Flyway 边界

Finance 开启时仍默认不创建 Flyway；只有两个开关都为 true 才建立 `financeFlyway` 定义。
没有 `initMethod=migrate`、启动回调或迁移执行器；**即使显式装配 Flyway，Context 启动也不执行 migration**。
唯一位置固定为 `classpath:db/finance/migration`，不使用通用 `db/migration`，本轮未复制任何迁移文件。
冻结来源共有 V1–V21，默认 PostgreSQL schema 为 public，部分历史 SQL 显式依赖 public。
本定义固定 public 并沿用默认 `flyway_schema_history`；禁止自动创建 schema、baselineOnMigrate、clean，
启用缺少位置失败和迁移命名检查。不调用 migrate、repair、clean 或 baseline，不改旧 SQL／checksum。

这里只完成依赖及迁移定义隔离，尚未完成迁移执行能力、历史库确认或 Flyway 10.20.0 → 12.4.0 数据库兼容验证。
后续受控迁移必须先确认数据集／schema／history、迁移文件和备份恢复方案。

## 开发配置与 Windows 产品入口

开发期只允许在独立最小 Context／IDE 的受控本地配置源中提供上述 `workspace.finance.*` 属性。
若保存为文件，放入已有 Git 忽略的 `.runtime/finance/finance.properties`，限制为当前开发者可访问，
不要使用可能进入进程列表或日志的命令行传递密码，也不要覆盖 Workspace Security 设置。
本轮测试直接提供无真实凭据的内存属性源；没有新增通用外部配置加载器或另一个 Boot 启动入口。

这不是 Windows 产品启用方法。`WindowsNativeGate.requireRuntimeArguments()` 仍要求原固定五项参数；
`rejectOverrides()` 仍拒绝 WORKSPACE_*／SPRING_*、相关 JVM 属性等覆盖，普通启动脚本仍 Development-only STOP。
不能据此推荐环境变量、任意参数或 Spring 配置导入绕开 Gate。最终须由受保护 Windows 启动链／私有配置文件
提供 Finance 配置，该入口尚未集成；本轮未修改 Gate、ACL 或 MMF-3。

## 后续 Finance 事务适配要求（本轮不迁入）

原仓库以下声明／注入依赖默认管理器，迁入时必须明确选择 `financeTransactionManager`：

- `AccountService:62/76/93`、`TransactionService:79/157/180`、`TransferService:34`、
  `CategoryService:34/47`、`AssetService:77/95/112/124`、投资 command／migration Service 等裸 `@Transactional`。
- `AccountBalanceService:31/57/63`、`InvestmentPositionBindingService:42` 的 MANDATORY 必须留在同一 Finance 事务；
  保留账户排序加锁、FOR UPDATE、lock_timeout、ownership 与幂等／唯一约束。
- `DashboardService:47` 的 REQUIRES_NEW + REPEATABLE_READ，以及
  `InvestmentPositionReadQueryService:43`、`InvestmentPositionDetailQueryService:35`、
  `InvestmentPortfolioQueryService:37`、`InvestmentLogicalTransactionReadQueryService:29`、
  `InvestmentLogicalTransactionDetailQueryService:33/46` 的 REPEATABLE_READ 不得弱化。
- `TransactionImportConfirmService:80/84` 的未限定 PlatformTransactionManager／TransactionTemplate（60 秒），
  `TransactionImportFileLifecycleService:27–32` 的未限定 manager（REQUIRES_NEW、5 秒），以及
  `TransactionImportSessionService:75` 的事务（60 秒）须显式绑定。

以上路径均在冻结仓库 `backend/src/main/java/com/financeos/module/` 下。
注解事务原本使用 Spring 默认回滚规则；Import 确认在 TransactionTemplate 外捕获数据库异常，
文件生命周期使用独立新事务。后续保留这些回滚／传播语义，不能吞异常、取消事务或放宽隔离解决适配。
F2.1 的 AccountQueryService 目前没有事务注解；本轮没有为它创建新的事务语义。

## F2.2-A 实际验证（2026-10-17）

Workspace 根目录只执行一次聚焦 Maven 调用；`test` 生命周期包含两个受影响模块的 `compile`：

```powershell
.\mvnw.cmd -B -ntp -pl workspace-application -am '-Dtest=FinanceInfrastructureTest' '-Dsurefire.failIfNoSpecifiedTests=false' dependency:tree test
```

结果 `BUILD SUCCESS`。只运行 `FinanceInfrastructureTest`，无失败或跳过；没有运行既有其他测试类。
最小 Context 已验证默认隔离（包括单独打开 Flyway 开关也不能启用 Finance）、内存 SQLite 数据保留、
缺 URL／密码与远程 URL 拒绝、Mapper／SessionFactory／事务管理器到 PostgreSQL 的实际 Bean 绑定、
非 Finance 普通注入仍选择原数据源／管理器，以及 Flyway 默认不装配／显式装配不执行。
测试用 Finance 配置使用未连接的 loopback 端口与虚拟凭据，不打开 PostgreSQL 连接；SQLite 仅为内存测试夹具。
原始日志：`.verification/f2.2-a-finance-infrastructure-20261017/maven-focused.log`（本地忽略文件）。
编译仍有原源码的内部 JDK API／deprecated API 警告，未扩大范围修改。

依赖树及自动配置元数据仅静态核对。未启动完整 Workspace／Windows／Native Context，未连接真实 PostgreSQL、
执行 Account SQL、验证真实事务／金额／ownership、执行 migration 或验证旧 history 兼容；未运行 Docker、
Ollama、GPU、Vision、Browser、历史全量回归或打包。F2.2-A READY 仅指源码基础设施边界。
MMF-3 Profile Gate 保持 PENDING、REVIEWED_TOKEN_POLICIES 保持空；没有 Runtime／Database／Production GO。
下一步仅 F2.2-B 的可信 Finance 用户绑定、受控真实连接、Account SQL 和金额／隔离／事务验证；本轮未开始。

## F2.1 历史验证（2026-10-17，本轮未重跑）

在 Workspace 根目录使用原 Maven Wrapper 执行一次：

```powershell
.\mvnw.cmd -B -ntp -pl workspace-application -am -Dmaven.test.skip=true dependency:tree compile
```

结果 `BUILD SUCCESS`，正式 reactor 包含根聚合 POM、`finance-domain` 和 `workspace-application`。
Finance 的四个源文件及应用源码均以 `javac release 21` 编译；未新增、编译或执行测试。
原始日志：`.verification/f2.1-finance-module-20261017/maven-compile.log`（本地忽略文件）。
应用编译保留既有内部 JDK API／deprecated API 警告，未扩大范围修改。
依赖树及实际 JAR 自动配置元数据仅作静态核对；启动脚本仅增加新模块的构建输入检查。
没有重新运行 F0/F2 候选、打包、启动 Context、数据库、Docker、Ollama、E2E 或历史 Guard。

当时编译与静态检查不等于运行兼容性验收。F2.2 首先需要专用 PostgreSQL 基础设施及显式生命周期，
验证默认关闭／开启缺依赖时的 Context 行为、真实用户身份绑定、事务与 SQL，再处理 JSON 精度、
严格解析和 Flyway 兼容性。F2.1 当时不进入 F2.2，不授予 Runtime／Database／Security／Production GO。
