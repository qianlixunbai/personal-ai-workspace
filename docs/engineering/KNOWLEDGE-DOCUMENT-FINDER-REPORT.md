# 按名称找回资料

2026-11-12，基于 `0226d675a67227ab7e8640d32f4b91e693a20995`，分支 `codex/knowledge-version-compare`。本模块沿原路线的“资料持续使用”推进，未重试桌面或身份诊断。

## 已交付源码

此前文档管理只有状态和每页 20 条分页；正文词法检索只读 ACTIVE 的当前 READY 版本。因此知道文件名称的用户仍需逐页寻找，失败、处理中或已归档资料也不能通过正文检索找回。

现在文档列表提供“按文档名称查找”和清除筛选：用户提交后，在所选 ACTIVE／ARCHIVED 状态的完整元数据集合内先筛选再分页。使用连续子串、Runtime `Locale.ROOT` 小写比较；百分号、下划线、方括号等均按普通字符处理，不是 SQL 通配符或正则。最多 128 个 Unicode code point，拒绝控制字符及不完整代理字符；页面去除输入首尾空白。这里的名称是资料列表当前标题，Word／PDF 转为文字后的标题仍遵循既有导入规则；不搜索每个旧版本的原文件名。

- 新查询复用 `GET /api/v1/knowledge/documents` 的可选 `query`；空值／不传保持原列表含义和响应字段。Native-only 访问、已返回资料的会话授权及后续详情入口不变。
- 单次查询读取文档元数据与导入状态，不读取 revision 正文、原文件或词法索引。最多 500 份，读取第 501 份即拒绝；统一在 Java 中匹配，避免 SQLite／PostgreSQL 大小写规则差异。PostgreSQL 沿用既有表名映射，无迁移、新索引、依赖或后台任务。
- 切换状态及名称回到第一页；翻页、导入轮询和刷新保留已提交筛选。名称改变不改变已打开的资料／原文位置，也不使正文查询或问答自动发起。接受新导入后清除名称筛选并回到使用中列表，以便查看导入回执对应资料。
- 空匹配明确显示筛选无结果，不误报空资料库。迟到列表响应不能覆盖新筛选；切页保留名称条件，连接会话更换清除条件。名称只驻留当前页面内存，不写浏览器存储。

## 必要验证与限制

本地证据：`artifacts/knowledge-document-finder-20261112/`。

| 检查 | 结果与范围 |
| --- | --- |
| TypeScript | `attempt1/typecheck.log`，退出 0。 |
| 前端定向测试 | `attempt1/frontend.log`，4 项通过：名称跨页／状态、原文保持、无匹配、迟到结果／切页／会话，以及原有导入列表回执／分页的两项回归。其余 10 项未执行。 |
| 真实产品查询方法＋合成 SQLite | `attempt1/java-scenarios.log`，3 组通过，使用产品 schema 和 `KnowledgeDocumentListing.read`，仅 `jdbc:sqlite::memory:`；覆盖全列表筛选前分页、非 READY 状态、Unicode、字面符号及输入边界。没有构造 Store／Profile，没有真实文件或 HTTP。运行的是本次编译器已经输出的类，见下述编译异常限定。 |
| 受影响 Java 编译 | **不能记为干净通过**。javac 退出 0，但 `attempt1/java-compile.log` 记录 ZIP 文件系统关闭时 `WindowsPath.toRealPath` 的 `AccessDeniedException`。该次调用包含新增查询、Store、Controller 和合成场景。原 wrapper 只检查退出码，已继续执行下一项；检查日志后停止这项编译，不重放。 |
| C# 构建与 Native Bridge | Core／Desktop／Tests 普通构建完成；`native-attempt2/document-name.trx` 两项通过，覆盖 Bridge 到 RuntimeClient 的查询编码／资料授权和非法输入在 HTTP 前被拒绝。首次测试 1 通过／1 失败：夹具误期待非法 Bridge 请求返回错误消息，实际合同是直接丢弃；仅修正测试断言并独立重跑这两项，未修改产品拒绝规则。 |

Java 本次新增的拒绝对象是 `C:\Users\28421\.m2\repository\org\apache\tomcat\embed\tomcat-embed-core\11.0.24\tomcat-embed-core-11.0.24.jar`，发生在编译器关闭 ZIP 文件系统的 `toRealPath`，不是“进程没有创建”。日志不能确定更深原因，也没有自动审核拒绝文案，不能把它改写成身份差异。已核对最新主会话历史，没有针对该动作的新增相关授权。**不得重跑 `run.ps1`、换 JAR／工具／API／权限，或复制依赖补这项验证。** 原始失败日志、原始退出码回执及补充 `REVIEW.md` 一并保留。

未运行完整 Maven／JUnit、PostgreSQL、真实桌面／WebView、模型、真实 Profile／数据库或真实导入；没有用新夹具替代此前受拒的 opentest4j 目标。普通 C# 输出复用此前前端资产，未生成包含本模块的新整合包，不能把编译和合成场景提升为完整产品验收。

## 备份、回滚与资源

改前备份为 `artifacts/knowledge-document-finder-20261112/before-0226d67.zip`，SHA-256 `8A7148E34A9AFFA8FFE5A89B540F3ECB9C44D5E8A4EB1A927298EE10368A09F2`。只提交本模块源码、必要测试和状态说明，不推送；可按本模块提交 revert。六份用户原始未跟踪文档、真实数据、旧证据与备份保持原样。

首次检查 06:40:51–06:41:12 UTC，原生独立复验 06:42:58–06:43:00 UTC，均获得并释放 `Local\Dot_KAG_Workspace_HeavyRun`。每项检查有时限，Java 128 MiB／2 processor、Node 主进程 512 MiB、Vitest 单 worker 并发、.NET 单构建节点及禁用共享编译／常驻构建服务器。自有检查进程均已退出，临时文件与失败证据保留于本模块目录，没有清理旧锁或其他项目资源。

真实产品累计启动仍 **1/2**，`productionGo=false`。诊断候选仍停在 `d27ce7a`，未包含本模块或后续源码；`EFFECTIVE_IDENTITY_CHANGED`、后续主体比较拒绝、首轮正常退出与原 Round=2 的缺口均未解除。没有访问另一 KAG 或 personal-finance-OS 仓库。
