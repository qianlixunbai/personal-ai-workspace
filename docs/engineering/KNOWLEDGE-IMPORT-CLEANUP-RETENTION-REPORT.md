# Knowledge 取消导入后的清理归属

2026-11-12，基线 `3a7f84d3f86a7d68485476cd3917b413af8a3460`。

`cancel()` 会立即将任务设为 CANCELLED，但上传调用可能仍阻塞在输入流中，或后台任务还未执行 finally 清理。重试 B 将文档的 last_job 改为 B 后，下一次 `admit()` 的旧 SQL 会删除已经终态、又不是 last_job 的旧任务 A。A 随后进入 `cleanupCandidate()` 时查不到自己的记录，无法再定位临时文件；重启 reconcile 也只遍历留存任务。另一个同源问题是取消后的文档可以被删除，外键级联同样会提前抹掉仍在使用的任务记录。

现在上传受理和内存中的活动任务登记在同一 Store 锁内完成。上传调用失败或后台任务结束时，只有在既有候选清理成功后才释放登记；失败清理保留可供后续恢复的任务。旧任务回收跳过这些活动请求，资料归档／删除也会在其仍拥有写入或清理责任时返回原有冲突码。取消状态仍立即可见，原重试入口、请求 ID 和 API 格式不变；紧接终态的管理操作可能短暂收到 `KNOWLEDGE_REVISION_CONFLICT`，应等待清理结束再发起。既有任务数量上限不扩大。

没有 schema 迁移、权限或 NativeGate 改动，也没有新后台服务。活动登记是当前进程内的所有权记录；异常退出后仍由原数据库任务及 reconcile 恢复，不把内存登记称为跨进程完成证明。

## 必要验证

证据目录：`artifacts/knowledge-import-retention-20261112/`。只编译实际修改的 KnowledgeStore、KnowledgeIngestion 和一个独立合成检查类，编译退出 0。测试 JVM 限制 128 MiB／2 个处理器，数据库仅为 `jdbc:sqlite::memory:`，使用产品 SCHEMA_DDL 和实际 `pruneFinishedJobs`；不构造 KnowledgeStore 的 Profile 入口，不启动服务或产品 Runtime。

- 原 DELETE SQL 在“取消 A → 重试 B → 后续导入回收”交错下实际丢失 A，合成用例确认了这一缺口。修复后的实际回收方法保留活动 A，释放其所有权后可回收，B 不受影响。
- 未完成清理的 FAILED 任务、最新终态记录、PENDING／PARSING 记录均保留；已清理的旧 READY 记录可回收，外层事务回滚恢复被回收记录。

两个场景通过，测试退出 0；只有 SLF4J 缺少 provider 的预期提示。`result.json` 记录本次共享互斥取得与释放，工具及测试已结束。`git diff --check` 通过。未读取或依赖受拒的 opentest4j JAR；这不是此前 JUnit 包装或真实数据库验收的补证。

验证范围是实际 SQL 与绑定、事务回滚和受影响源码编译。真实上传流竞争、文件删除失败／重启、PostgreSQL 和 Desktop 集成未运行；活动登记从受理到 finally 的交接及管理操作阻挡由源码核对，未宣称这些实机路径已验收。

## 保留与回滚

改前两份产品文件备份为 `artifacts/knowledge-import-retention-20261112/before-3a7f84d.zip`；本模块独立本地提交，可 revert 回滚，未推送。六份用户原文、原 Profile、候选、旧监督／首轮回执、主体拒绝回执及一次性预留位未修改。

累计产品启动仍 **1/2**；第二次 Desktop／helper／Profile 数据检查器没有启动。`productionGo=false`，首轮正常退出、原 Round=2 和保存重开验收仍未通过。已编译诊断候选仍对应 `d27ce7a`，不包含本修复或此前分页、前端修改。
