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

## 边界与接续

原 262 字符失败样本、前轮原生 Memory 成功样本、所有旧包保留。本轮没有打包或运行完整 EXE；WebView2、失败页入口、PG、恢复/迁移、跨机器和正式发布均不属于本次通过范围。Knowledge 源库、派生索引和 Conversation 仍有普通文件连接，下一步仅按实证缺口逐项处理。

所有构建及验证复用 Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 短运行包装，覆盖同步子进程退出后 finally 释放；未遗弃、未强杀，未访问未知 18765 或 KAG 18895。未启动常驻服务。Git、编辑和报告不持锁。main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`；六份用户文档不修改、不暂存；仅本地可回滚提交，不推送。
