# 财务流水按备注原文查找

日期：2026-11-11。基线 `e14e93403fd1609693ae6f72ad6c668e269f2a7a`，分支 `codex/knowledge-version-compare`。研究笔记完成精确本地提交后，本轮继续交付一个独立的日常财务能力：用备注片段、订单号等查找全部符合条件的交易和转账。

## 用户行为

交易流水增加“备注原文”输入。关键词按连续原文匹配，区分大小写，最多 128 个 Unicode 字符；空字符串取消该条件，纯空白、控制字符和非法代理项拒绝。`%`、`_`、引号等都是普通字符，不按通配符或正则解释，也不自动去除用户输入的首尾空格。

点击“应用筛选”后从第 1 页查询，账户、日期、交易类型、每页条数及统计明细范围与原来一致。中文输入法组合期间不会提交。界面明确显示已应用的备注条件，搜索结果行直接展示原备注；翻页、刷新和打开详情沿用已应用条件，不把尚未提交的输入当作查询条件。“清除筛选”显式回到默认范围。

从收支统计下钻后再按备注筛选，界面说明当前结果已缩小，不能把筛选后的总笔数或记录当作完整统计范围。金额及总笔数仍由服务端以精确字符串返回，客户端不合计金额或推算财务事实。会话／运行状态变化仍清空原身份的结果及关键词。

## 查询与权限合同

- `FinanceTransactionController` 为既有 GET 列表增加可选 `query` 参数，保持参数白名单和禁止重复参数。未提供时为空；没有新增写接口。
- `FinanceTransactionQueries` 验证关键词后才进入既有 `FinanceAccountQueries.identity.query`。后者仍负责 Native 身份、已绑定用户／数据集检查及 Finance 只读 REPEATABLE_READ 事务。
- 在已有普通交易与独立转账 UNION 的外层追加参数化 `position(? in coalesce(description,''))>0`，两个源的用户过滤和账户归属 JOIN 不变。计数和分页复用同一 WHERE／绑定参数，在同一既有只读事务里执行；过滤先于 LIMIT/OFFSET，不是当前页内搜索。
- Java 结果回显准确 `query`。Native URL 对非空关键词使用 `Uri.EscapeDataString`，空关键词不追加新 URL 参数，保留已有无备注查询合同。Native 和 React 同时核对回显条件，并逐行确认备注确实包含原关键词；缺失回显、返回其他条件、无备注或不匹配记录均拒绝。空查询可接受旧的无 `query` 回显；非空查询不会降级为全部流水。
- Native Bridge 仅在原流水 payload 中允许这个可选字符串，仍拒绝用户 ID、任意字段、类型错误与超长输入。用户、数据集、账户和金额的权威仍在原 Finance 层；没有移动或修改冻结的 finance-domain 源码。
- 查询和分页对象的 `ToString` 改为脱敏固定文本，避免新增的备注搜索被对象输出意外带出。没有增加查询日志、数据库索引、配置、凭据读取或模型调用。

## 最少必要验证

回执目录：`artifacts/finance-transaction-search-20261111/`。

| 层 | 实际结果 |
| --- | --- |
| 前端 | TypeScript 通过；两个新搜索场景与一个既有 Dashboard 下钻场景，3 passed / 5 skipped，2.14 秒。涵盖 IME、特殊字符、跨页／详情／清除、金额精度、不匹配回显／备注拒绝、无效输入及身份切换迟到响应。 |
| Java | 正式离线 Maven reactor `test` 成功，Finance 54、应用 194、应用测试 66 个源文件编译，实际仅运行 `FinanceTransactionSearchTest` 两项，2/2 通过，1.435 秒；总构建 11.516 秒。检查参数绑定、两个账本用户限定、计数与分页共享过滤、拒绝重复／注入参数及绑定错误不读取账本。使用合成 JDBC，不连接 PostgreSQL。 |
| Native | 初次普通 Debug 编译和三个精确筛选场景通过：新搜索解析、既有 Dashboard 纳秒截止范围、既有 Bridge 严格字段拒绝。随后补上空查询不追加 URL 参数的兼容处理，仅复验两个受影响的 RuntimeClient 场景，2/2 通过，48 ms。 |
| 构建 | 新 Vite production 资产及清单生成，C# Core／Desktop／Tests 正规编译；没有 restore、下载、package、install 或服务启动。保留既有 Vite 大块、Java 内部 API／Mockito agent 提示，不称零警告。 |

Maven 使用已核实的原 Maven 3.9.16、原 JDK 21.0.7 和原 `C:\Users\28421\.m2\repository`，通过正常 require_escalated 审批运行；没有复制依赖绕过拒绝或修改权限。350 个当前源／POM／资源／测试输入逐字节复制到新的 `source/` 并记录 SHA256；保持原工程入口和离线 settings，测试筛选为 `FinanceTransactionSearchTest`，不运行其他测试。原 source、target、旧快照和所有旧工件保留。

C# 使用 `--no-restore --artifacts-path`、现有 NuGet 解析文件和本轮 `FrontendRoot`；两个 MMF3 开关及 RuntimeLifecycleProbe 均为 false。初始输出在 `dotnet/`，空查询兼容修正后的输出单独在 `dotnet-final/`，保留前次结果。该最终普通 Desktop 编译嵌入本轮前端，其中已包含研究笔记最终源码；与研究笔记报告所述较早的初始资产输出区分。

主要回执：`frontend-check.log`、`frontend-tests.log`、`frontend-build.log`、`maven-test.log`、`maven-exit.json`、`source-inputs.json`、`dotnet-tests.log`、`dotnet-final-tests.log` 和 `test-results/`。重型操作只在 Session 1 实际持有 `Local\Dot_KAG_Workspace_HeavyRun` 时运行，并在 finally 释放。未重复研究笔记已通过的场景。

## 交付层级与后续

本轮交付源码、针对性合成验证与编译，不生成受信 Runtime JAR、Profile 包或新 ZIP。Java 产物为独立 classes，C# 为普通 Debug；没有部署、启动产品/helper/NativeGate、访问真实财务库、实际搜索用户交易或执行桌面验收。新 SQL 在真实 PostgreSQL 数据集的行为／性能仍未实测，不能把绑定测试写成数据库全流程通过。

改动以独立本地 Git 提交保留，回滚用该提交的 revert，不涉及数据迁移或账本恢复；不推送、不修改 main 或六份原始用户文档。`productionGo=false` 与原 Profile 的界面／正常退出控制阻塞继续保留。本线程已交付研究笔记和流水搜索两个业务模块；后续应选择独立使用缺口，不重复已过检查或为旧桌面验收新增旁路。
