# 记忆使用记录：会话更新期间的分页稳定性

2026-11-11。起点 `5ae939a0d99bbb3f563398a39185b2c258f13f49`。

## 缺口与修复

原查询按会话的 `updated_at DESC,id ASC` 和轮次序号排序，再按页号使用 `OFFSET`。翻页之间如果一个尚未展示的会话被重命名、归档或更新，它会移动到第一页，第二页可能重复旧结果并漏掉该会话。

记忆使用记录现按会话的 `created_at DESC,id ASC` 分组，再按轮次 `sequence ASC` 排列。同一会话在产品中重命名、归档、取消归档或增加轮次不会改变其创建时间；相同创建时间以会话 ID 和同一会话内唯一轮次序号确定顺序。这保留了按会话分组和页号协议，但用户看到的是**最近创建的会话优先**，不再是最近更新的会话优先。页面已说明新顺序及刷新条件。

这仍是实时 `LIMIT/OFFSET` 视图，不是跨页快照。新增或删除匹配记录、原 PENDING 轮次进入终态，以及删除会话，都可能改变页边界；应重新点击“查询使用记录”从第一页查看。新增记录可能排在已读边界之前，因此不能保证一次翻页覆盖并发新增。未引入持久游标、服务端会话、数据库迁移或额外索引。

Java 请求/响应的 `memoryId`、可空 `revision`、`page`、每页 10 条及 `hasMore` 不变。查询仍以参数绑定过滤准确记忆、可选版本和非 PENDING 轮次，并保留活动及归档会话。Native 当前 session 只授权当次返回的会话，旧有失效 session 拒绝行为未改；读取原轮次仍重新核对会话、轮次和记忆引用。没有降低 NativeGate 或扩展权限。

## 有界合成检查

回执保存在 `artifacts/memory-usage-pagination-unit-20261111/`。SQLite 数据库均为进程内 `:memory:`，通过既有 schema 定义创建合成表，未访问任何 Profile 或持久数据库。

| 检查 | 实际结果 |
| --- | --- |
| 原排序重现 | JUnit 启动并执行 4 个合成场景；11 条记录翻页期间更新原本未显示的会话时，期望剩余会话 `...0001`，实际得到已出现的 `...0002`。3 项通过、该项失败，测试进程退出 1，见 `baseline/test.log`。 |
| 修复后的 Java/SQL | 只编译受影响的 `ConversationStore`、查询合同和合成场景；编译退出 0。独立 Java 测试进程使用实际查询和参数绑定，4 个场景通过、退出 0，见 `fixed-plain/result.json` 与 `test.log`。覆盖 11 条跨页更新、同创建时间和轮次分界、记忆/版本/终态过滤及空页、增加记录后刷新。 |
| 前端 | 原 `MemoryUsageReview.test.tsx` 定向执行 2/2 通过，见 `frontend/result.json` 与 `test.log`。它使用合成 Bridge，不是实际 WebView。 |

新增记录场景也明确验证实时分页的边界：在第一页读取后插入排序更靠前的匹配记录，下一页可以重复旧项；从第一页重新查询能看到新增项。旧 Native `MemoryUsageProjectsExactRevisionAuthorizesResultsAndRejectsWrongOrLateScope` 已有通过记录，本轮未重复执行；合成 SQL 过滤检查不能替代 Native session 授权验收。

上述各测试均先取得并释放 `Local\Dot_KAG_Workspace_HeavyRun`；两次 Java 测试各将堆限制为 128 MiB，前端 Node 限制为 256 MiB。没有运行完整 Maven reactor、产品 Runtime、Desktop、WebView、模型或真实服务。

## 原始拒绝与未验收边界

原排序重现时，`javac` 输出 `java.nio.file.AccessDeniedException: C:\Users\28421\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar`，堆栈位于关闭 ZIP 文件系统时的 `WindowsPath.toRealPath`；`javac` 最终退出码为 0，原脚本随即继续使用该 JAR 执行 JUnit 测试，返回后才看到这项错误。完整输出保存在 `baseline/compile.log`。这是该依赖路径的系统拒绝，不能据此推断整个测试或其他路径被拒；查询最新消息后没有针对该拒绝的新事实，不重复受拒文件操作。`Run-ScopedUnit.ps1` 保留为原命令证据，其中仍包含受拒目标，不得再次执行。

修复后的独立场景直接使用 Java 断言，不加载任何 JUnit 或 `opentest4j` JAR；可供常规 Maven 测试发现的 JUnit 包装类保留在源码中，本轮修复后没有重新编译或运行该包装类。此 SQL 单元检查不调用 `MemoryStore`、NativeGate 或原被拒合成 Profile，不能把它称为先前失败的原生数据库集成用例通过，也不证明 PostgreSQL 实际行为或性能。

本轮无真实 Profile、旧监督或旧回执操作；原首次产品启动的正常退出仍未证实，启动计数为 1/2，`productionGo=false`。未推送，回滚通过本模块本地提交的 revert 完成。
