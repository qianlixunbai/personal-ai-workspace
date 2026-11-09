# 指定文档范围的检索与问答

2026-11-09。基线 `bb77fa0`，分支 `codex/knowledge-document-scope`，回滚标签 `workspace-before-knowledge-document-scope-20261109`，实现与验证提交 `c1caa85`。仅本地开发，未推送、合并、部署或发布；仍为 Development-only。

## 本轮交付与优先级

Knowledge 现在可以明确限定到一份文档的当前可用源版本，完成检索、问答、准确引用和历史重开。以前只能全库检索，其他资料可能占满前 10 条命中，使用户正在阅读的文档无法进入回答；本次在结果截取之前限定候选，解决这一实际使用缺口。

上一项跨会话历史查找已完成指定隔离验收，不继续扩展它的测试矩阵。PDF／OCR 会新增解析器、资源限制与来源格式合同；个人恢复仍缺其独立授权和激活前置。相比这些较大边界，指定资料问答可直接复用已有来源、索引、预算和引用链路，提供完整日常能力，故优先实施。

使用方法：在知识库文档列表打开一份使用中的资料，在“查找资料”或“基于文档回答”中点击 **限定为当前文档**，再手动检索或提问。两块区域各自保存明确选择的范围，默认都是全部可用资料；**使用全部资料** 是显式解除限定的操作。切换文档或刷新详情不会悄悄改用另一个范围，问题与关键词草稿保持。范围标签显示文档和版本，离页／session 变化清除。

本次只支持一份当前可用文档或全部可用资料。引用预览仍可查看旧版本，但新问答不能通过这个入口选取非当前版本。源更新、归档或删除后，旧选择返回冲突，须明确重新选择；没有命中时返回空结果／拒绝无证据问答，不自动扩大到全库。

## 实现与安全边界

- 搜索和问答请求新增可选 `scope: {documentId, sourceRevision}`。缺省／null 保持原全库语义；旧请求形状继续可用，范围对象拒绝额外字段、非法 ID／版本和数值强转。旧 Runtime 会拒绝带范围的新请求，不会静默忽略限定。
- SQLite 在参数化 SQL 的 LIMIT 前按 document／revision 筛选。PostgreSQL 保持全库 BM25 统计，在候选进入前 10 条队列之前筛选；没有新增索引、查询服务或数据库依赖，也不声称减少了 PG 全库扫描成本。
- 服务端核对 ACTIVE 当前源版本及原有索引指纹。检索与权威证据读取保持原 Store 同步边界，随后释放锁；接受的旧版本证据保持冻结，后续修改不会替换正在生成的回答引用。
- Native 只允许使用当前 session 已获授权的文档 ID，重新核对返回命中／答案引用与请求范围，越界响应不能授权新文档。POST 之后出现无效范围响应按 OutcomeUnknown 处理，不重发；历史读取仍需当前历史页授权。Browser 权限不变。
- UI 显示的范围不由模型推导；资料文本不获得工具、路径、配置或数据库权限。模型仅接收原有有界证据包，引用仍由 Runtime 从已接纳的标签映射，不能将“引用合法”解释为“答案事实一定正确”。

### 历史与兼容

问答历史在原有私有 SQLite 库中新增小型 `answer_scopes` 表，user_version 从 1 事务升级到 2；原 answers 表和原记录保持。范围与接受记录同事务保存，删除历史时一并移除。历史重开恢复原范围，包括文档后来更新或删除的情况；再次提问仍须通过当前版本核对。历史 schema 升级无论来源 backend 是 SQLite 还是 PG 都使用原 Profile 本地历史库。

为避免既有合成快照静默丢掉新字段，联合快照内部 manifest 升至 2，并保留严格读取旧 version 1 的路径；旧记录恢复 scope=null，不补造来源限定。新快照校验成功答案的每条引用均属于保存范围。加密容器、Knowledge 独立来源备份、PG 来源 schema 与用户恢复入口没有改变；这只是现有原型的字段兼容，不是新的恢复功能。

**代码回滚不等于数据降级。** 已升级到历史 schema 2 的库不能交给只支持 schema 1 的旧程序。此次只在全新合成资料上执行升级和恢复，没有打开或升级真实个人历史。回滚标签用于代码；保留测试库和证据，不提供自动数据降级或真实个人恢复承诺。

## 成熟项目参考

本轮核查官方资料与仓库许可，采用显式元数据过滤的思路，未复制代码或安装框架。

| 参考 | 核对与取舍 |
| --- | --- |
| [LlamaIndex](https://github.com/run-llama/llama_index) | 官方 [retriever 类型](https://github.com/run-llama/llama_index/blob/main/llama-index-core/llama_index/core/vector_stores/types.py)提供 document IDs 与严格元数据过滤类型；[根许可为 MIT](https://github.com/run-llama/llama_index/blob/main/LICENSE)。参考“查询范围作为结构化参数”，不引入 Python runtime、向量库或模型自动过滤器。 |
| [Haystack](https://github.com/deepset-ai/haystack) | 官方[元数据过滤文档](https://docs.haystack.deepset.ai/docs/metadata-filtering)说明在检索时限定资料子集；[根许可为 Apache-2.0](https://github.com/deepset-ai/haystack/blob/main/LICENSE)。保留查询时过滤的做法，不引入通用过滤表达式、额外 Document Store 或外部服务。 |

本项目直接复用现有 SQLite FTS5／PG token 排名、Native Bridge、KnowledgeEvidenceAdmission、预算打包、引用校验和历史 Store。新增范围是精确 ID＋版本，不接收 SQL、模型生成条件或用户文件路径；既有安全设置未修改。

## 必要实测

证据目录 `.verification/knowledge-document-scope-20261109/`，由 Git 忽略，仅留本地。所有来源、历史、备份恢复和 PG 集群均为新建合成数据；Provider 为进程内 fixture／自有随机 loopback 模拟 HTTP，没有真实模型、GPU 或 KAG 调用。

| 证据 | 实际结果 |
| --- | --- |
| `java-build-2.log` | 默认 Runtime 离线 package、测试编译、既有 JAR 审计通过；构建 skipTests，不计作动态执行。 |
| `run-2/integration.log` | 10 项中 9 项通过、1 项旧测试版本断言失败，0 aborted／skipped。通过项包括 SQLite 限定在 top 10 前生效、仅所选证据进入提供器、更新后的拒绝、范围历史重开、schema 1 升级、未知 schema 拒绝、新旧快照与加密恢复、真实 HTTP／Browser 权限。 |
| `run-3/integration.log` | 修正版本断言后，只重跑受影响的 1 项，1/1，0 failed／aborted／skipped。覆盖重算摘要后的错误内容、重复记录、篡改范围、超限、非法版本及占用目标拒绝。没有重跑此前已通过的 9 项。 |
| `pg-run-1/native/integration.log` | 新建独占 PostgreSQL 17.11 的既有综合场景 1/1；新增 12 份高排名干扰资料＋1 份目标资料，目标不在全库前 10 条，限定后仍能取得；无匹配不回退、归档后拒绝旧范围。真实低权限应用角色查询。 |
| `dotnet-test-3.log`、`test-results/knowledge-scope-3.trx` | 现有 Knowledge 测试文件 20/20，零跳过；含未授权范围不发 HTTP、返回资料／引用越界拒绝、POST 未知结果只发一次、旧历史响应兼容及 session 保护。 |
| `frontend-test-2.log` | 两个现有知识页面文件 13/13；含范围明确选择、刷新不改范围、问题／关键词草稿保留、响应越界拒绝、历史范围重开不提交模型与 session 清理。 |
| `frontend-build-3.log`、`dotnet-test-3.log` | 最终 TypeScript／Vite 及 Desktop 编译通过；保留既有主 JS chunk 超过 500 kB 的提示。 |
| `pg-run-1/fixture-result.json`、`final-isolation-check.json` | PG testExitCode=0，所属实例停止、临时凭据移除；全部自有 JVM／PG 退出、端口关闭、无自有 Profile 剩余进程；六份用户文件逐项 SHA-256 不变。 |

首次运行情况完整保留：前端初次编译因 exactOptionalPropertyTypes 的 undefined 标注失败，修正后通过。首次新前端测试 11 通过／2 失败，是选择器名称和更新夹具缺少新 revision，修正夹具后 13/13。Java 首轮 3 通过／7 失败：误用限定真实端口的 v1-isolated 构建、未采用既有短临时路径，以及新测试把内存任务时间与持久回执时间视作相同；改回默认 fixture 构建、既有短目录及比较重开前保存记录后，第二轮 9 通过。余下旧测试仍把新支持的版本 2 视为未知版本，改为 3 并定向重验通过。Native 首轮 20/20 后补强 POST 越界响应处理；第二次构建与前端重建资产发生竞争、未进入测试，按依赖顺序重建后最终 20/20。没有降低产品拒绝规则来消除失败。

默认 Runtime JAR SHA-256：`C5EFD32EB048035588CC0C76A76DD66ECDC69E09D482E70D6FDA0437CD6F27A4`，未部署。Java 动态执行使用当前默认 classes／test-classes；适配器、选择清单、classpath 与确切命令保存在证据目录。保留旧失败日志，不用后续 PASS 覆盖。

自有 JVM PID `46964`、`4492`、`54916`、PG 测试 JVM `47572` 均退出；PG PID `33496`、端口 `52315` 已停止，成功 HTTP 轮次端口 `52301` 已关闭。未对既有 PG／Ollama／KAG 服务执行启停，没有读取个人数据库、配置或凭据。

## 可用范围与未完成项

源码、配套 Runtime／Desktop 构建及上述隔离核心流程已交付。本轮前端证据是 JSDOM＋实际 Bridge 客户端，Native 是真实 Core／Bridge 配合模拟 HTTP，Java 是原生宿主下的真实 HTTP／SQLite，PG 是真实 Store；没有把它们相加宣称完整 AssistantApp／实际 WebView 点击验收。

未做本功能的完整 Windows 桌面／安装包、PG HTTP／PG 桌面组合、真实模型回答质量、最大容量性能或人工 IME 验收。仍是词法检索，关键词须匹配；限定资料不保证模型对全文理解或穷尽所有相关段落。个人备份恢复、正式分发及 Production GO 不因本功能解除。后续按实际使用反馈与最终产品闭环排序，不默认继续扩展底层验证框架。
