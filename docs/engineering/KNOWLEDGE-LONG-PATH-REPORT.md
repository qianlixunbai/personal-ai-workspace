# Knowledge 历史 SQLite 长路径兼容性

2026-11-10，基线 `1a6145efa105909276d7abeb27e97b1646fbe4f9`，分支 `codex/knowledge-version-compare`。本项修复真实持久化验收暴露的独立存储问题；不改变 WebView2 未验收结论。

## 修复与依据

KnowledgeAnswerHistory 原先直接拼接普通 SQLite URL。将 Memory 已有的 Windows URI 构造原样提取为 `SqliteFileUrl.forFile`，历史库与 Memory 的主连接、只读快照共用。Windows 仍采用转义路径、扩展前缀及 `win32-longpath`，其他平台仍保留普通 URL。

[SQLite VFS 官方文档](https://www.sqlite.org/vfs.html)说明 `win32-longpath` 保留普通锁语义，并扩大路径容量；[SQLite Windows 源码](https://github.com/sqlite/sqlite/blob/master/src/os_win.c)的两个 VFS 共用 `winAppData` 和文件操作。当前依赖 [sqlite-jdbc 3.53.2.0 源码](https://github.com/xerial/sqlite-jdbc/blob/3.53.2.0/src/main/java/org/sqlite/SQLiteConnection.java)支持 URI 文件名直接交给原生数据库。没有使用关闭锁的 `-none` 变体，也没有升级依赖。

数据库目录、schema、历史记录、身份绑定、文件锁、native admission、私有目录及 sidecar 校验均保持原样；不迁移、截断或重命名用户目录，不修改系统策略、ACL 或注册表。

## 最小真实验证

复用最终包的 Java、依赖、native bootstrap 和 Profile 准备工具，仅优先加载本次编译的三个产品类。显式手工工具为 `bootstrap/tests/KnowledgeLongPathProbe.java`，无 Runtime 服务、HTTP、浏览器、模型或 UI。新合成 Profile `33e9bcb185d448ef9581e5a6500d59cf` 由原产品 Prepare/Provision/Parse 创建，精确路径仅记录在 `.verification/knowledge-long-path-20261110/prepare/owned-profile.json`。

| 检查 | 结果 |
| --- | --- |
| 旧历史库实现，1,100 UTF-16 字符路径 | 实际 `KNOWLEDGE_STORAGE_UNAVAILABLE`，零字节文件保留；PID 30220 正常退出 0（期望失败）。 |
| 修复后 249 字符普通路径 | 保存成功；普通 SQLite 只读连接 quick_check 通过。 |
| 修复后 1,100 字符路径，含空格、`%23#&`、中文 | 保存成功，原目录内建立真实 SQLite；另一个 JVM 读回同一问题、答案、任务及 scope。 |
| 独立进程重开 | 保存阶段 PID 14384，重开 PID 17520；两者正常退出 0。重开后两份历史库字节未变，无 journal/WAL/SHM 残留。 |
| 第二历史写入者 | 两条路径均继续拒绝第二个 Store，已有记录可读。 |
| Memory 受影响验证 | 同一新 Profile 另一个长路径 Memory 数据库，创建一条合成记忆；独立 JVM 读回，`openReadSnapshot` 的 quick_check 通过。未重跑已通过的原生窗口流程。 |

证据均在 `.verification/knowledge-long-path-20261110/`：`baseline.log`、`complete-save.log`、`reopen.log`、对应 `*-process.json` / `*-cleanup.json`、`short-receipt.json` / `long-receipt.json`。产品与工具编译日志为空。

工具首次包装在已经完成 baseline 后，因读取自身未关闭日志句柄而退出；确认 baseline 正常退出后，只接续未执行阶段。首次 save 已写入普通路径，但工具将 `TaskView.result` 的 JSON 无类型 Map 与实际 record 作 Java equality，断言失败；保留 `save-errors.log` 和收据，修正为 JSON 树比较，`complete-save` 仅核对已有短路径、保存未执行的长路径和 Memory，没有重复保存短路径。产品代码没有为这些工具问题改变。

## 接续模块：Knowledge 源库与派生索引

历史库修复保存于 `9fee49061dbe3cffe76755bea350909a7c17248b` 后，核对并实证两个同类缺口：原 KnowledgeStore 在 1,087 字符路径打开失败；只修复源库而保留旧 KnowledgeLexicalIndex 时，合法空源库可读，但索引构建进入 FAILED。两个 baseline 都使用新的隔离目录，证据为 `catalog-baseline.log`、`index-baseline.log`，未更改历史模块样本。

最小产品改动为源库连接，以及索引的候选构建、现有索引打开、只读搜索三个连接点共用 `SqliteFileUrl`。内存数据库、索引算法、发布流程、重建策略、private-path 检查及 PostgreSQL 均未改。

必要验证在短路径（源库 246 / 索引 250 字符）和长路径（1,087 / 1,091 字符）各导入一份新合成 Markdown，检查原文、单一版本、带明确文档范围的中英检索，并在独立 JVM 中读取相同文档、版本及命中。重开时已有索引立即 READY，未依靠静默重建掩盖打开失败；源库重开前后字节完全一致。实际产品 KnowledgeIngestion、KnowledgeStore、KnowledgeLexicalIndex 和 SQLite 全部参与，无模型或模拟 Store。

保存 PID 32476；最终独立重开 PID 15564；均正常退出 0。`knowledge-save.log`、`corrected-knowledge-reopen.log`、各进程与清理收据、两份 `knowledge-*-receipt.json` 保存结果。初次重开在短路径收据比较处失败：JSON `valueToTree` 的 LongNode 与解析收据的 IntNode 不同；修正工具为双方均经过 JSON 文本解析后，仅读取既存样本重新核对通过，未重放导入。原 `knowledge-reopen-errors.log` 保留。这是验证工具修正，未改变产品数据。

## 接续模块：Conversation 共用数据库

源库/索引修复已保存于 `baeaead`。接着在新合成目录确认：Memory 可初始化 1,074 字符的 `memory.db`，旧 ConversationStore 打开同一文件却返回 `CONVERSATION_STORAGE_UNAVAILABLE`（`conversation-baseline.log`）。只将该连接改为共用 URL；位置仍由已验证 Memory 实例提供，不增加迁移、schema 或准入逻辑。

236 字符普通路径和 1,074 字符长路径各创建一条合成记忆、一段会话与一轮成功的用户/助手消息；从另一个 JVM 读回完整 ID、时间、原文、终态、轮次和 Memory 收据。Conversation 与 Memory 实际访问同一文件，无模型调用。保存完成 PID 23596，独立重开 PID 13196，退出 0；`conversation-complete-save.log`、`conversation-reopen.log` 及对应进程/清理收据保留。

首轮工具误用页面上限 20，Conversation 现有上限为 10；已保存普通路径后在读取详情时被 `CONVERSATION_INVALID` 正确拒绝。修正工具参数后只核对该单条已有记录并补收据，不重复写入，再完成未执行的长路径及独立重开。原 `conversation-save-errors.log`、失败进程收据保留，产品分页限制未更改。

## 边界与接续

原 262 字符失败样本、前轮原生 Memory 成功样本、所有旧包保留。本轮尚未打包或运行完整 EXE；WebView2、失败页入口、PG、恢复/迁移、跨机器和正式发布均不属于本次通过范围。维护用 SQLite→PostgreSQL 迁移连接仍有普通文件 URL，未修改或执行，不能扩展为所有维护路径的长路径验收。

所有构建及验证复用 Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 短运行包装，覆盖同步子进程退出后 finally 释放；未遗弃、未强杀，未访问未知 18765 或 KAG 18895。未启动常驻服务。Git、编辑和报告不持锁。main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`；六份用户文档不修改、不暂存；仅本地可回滚提交，不推送。
