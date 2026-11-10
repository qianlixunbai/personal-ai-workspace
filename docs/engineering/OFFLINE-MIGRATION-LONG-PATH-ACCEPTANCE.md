# 离线迁移的长路径源库与备份

2026-11-10，基线 `e62a04015b6c6899a335d7ade25ae90a4f36477b`，分支 `codex/knowledge-version-compare`。本项补齐 Windows 长路径下两条离线迁移路径的**源库冻结、完整备份、备份重读及目标不可用后的保留/回滚**。不是 PostgreSQL 成功导入、真实用户迁移、默认配置切换或正式发布验收。

## 问题与最小实现

日常 Memory、Conversation、Knowledge 已使用 `SqliteFileUrl`，但 `SqliteWorkspaceMigration` 与 `SqliteKnowledgeMigration` 仍直接拼接普通 DOS 路径，原生 backup API 也接收普通文件名。长源路径无法进入迁移，长备份路径不能生成可用快照。

两条路径现在复用既有 Windows 扩展前缀和 `win32-longpath` VFS。`SqliteFileUrl.forNativeFile()` 提供去掉 JDBC 前缀的原生 filename/URI，`forFile()` 由它组成原有 JDBC URL。普通平台仍返回原文件名。原来的私有文件验证、CREATE_NEW、BEGIN IMMEDIATE、只读 snapshot、来源摘要核对、源文件 pin、目标事务和无覆盖保护保持。

固定 [sqlite-jdbc 3.53.2.0 NativeDB.c](https://github.com/xerial/sqlite-jdbc/blob/3.53.2.0/src/main/java/org/sqlite/core/NativeDB.c) 的 backup 实现对 `file:` 目标启用 `SQLITE_OPEN_URI`，并单独打开目标数据库；因此只修 JDBC 源库连接不足以修复长备份路径。未复制上游实现、增加依赖或更换 SQLite DLL。

## 真实合成验证

只创建一个全新原生 Profile，沿用既有 Prepare/Provision/Parse 与 Native Gate。运行接受包中的 JDK、依赖及原始运行时字节；修复阶段只把本次三个生产类置于 classpath 前端。探针是 [SqliteMigrationLongPathProbe.java](../../bootstrap/tests/SqliteMigrationLongPathProbe.java)，不是产品入口。没有启动 HTTP/Runtime 服务、数据库服务器、浏览器或模型。

| 来源 | 源路径长度 | 备份路径长度 | 原版结果 | 修复结果 |
| --- | ---: | ---: | --- | --- |
| Memory + terminal Conversation | 1151 | 226 | 未到目标，备份缺失/空 | 备份完整，目标连接尝试 1 次 |
| Memory + terminal Conversation | 226 | 1150 / 1151 | 未到目标，备份空 | 备份完整，目标连接尝试 1 次 |
| Knowledge catalog + source artifact | 1164 | 238 | 未到目标，备份缺失/空 | 备份完整，目标连接尝试 1 次 |
| Knowledge catalog + source artifact | 239 | 1163 | 未到目标，备份空 | 备份完整，目标连接尝试 1 次 |

长度是 UTF-16 单元数；合成路径含中文、空格、`%23`、`#`、`&`。源是既有 SQLite 格式（Workspace v3、Knowledge v1），没有把本次说成更早 schema 的升级验收。Memory 含 revision 2，Conversation 含已终结用户/助手消息，Knowledge 含实际导入的 Markdown 原文。

4 个组合均核对：源文件前后 SHA-256 相等、源库全部表/行相同、备份 integrity_check 成功且与来源全部表/行相同、知识原文文件字节相同；再次提交同一备份路径在目标前拒绝且不覆盖；失败后源库可重新取得 BEGIN IMMEDIATE 并回滚，源哈希仍相同。目标使用只抛出 `08001` 的 DataSource 桩，不产生网络连接或 SQL 写入。证明的是备份及失败保全，不是目标导入或激活。

首轮探针的只读比较使用 `ORDER BY rowid`，遇到 WITHOUT ROWID 表后在迁移调用前退出 1。已保留该现场，改为按实际列序排序，在不同的新合成目录执行修正版对照；没有把脚本错误计作生产失败或重放已执行迁移。

本地证据位于 `.verification/migration-long-path-20261110/`：`Run.ps1`、`intent.json`、`prepare/owned-profile.json`、各阶段 `*-process.json` / `*-cleanup.json`，以及 `baseline-corrected.log`、`fixed.log`。原版 4 个预期失败及修复后 4 个结果来自两次有界 JVM 运行；未跑全套测试。生产类及探针编译无诊断；`git diff --check` 通过。

每次构建/准备/执行在 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun` 内，子进程归属持有 Job。各阶段 Job 清理前活动成员为 0，锁与 Job 在 finally 释放。最后一次固定路径验证于 `2026-11-10T10:11:51.4327238Z` 收尾。保留合成源、失败目录和备份，无未知服务需要终止，无本轮拒绝、待审批或结果不明动作。

## 默认入口与交付边界

静态核对 `Program.ResolveDevelopmentProfile`、`DevelopmentWorkspaceSelection` 与 `WindowsProfileSecurity.Parent`：无参数流程的 `last-workspace` 绑定 OS Known Folder 下私有应用目录；已有 `--open-development-workspace-once` 仅隔离打开行为，不验证默认记录写入。未发现支持独立配置根的产品入口。没有改真实默认记录后再回滚，没有伪造 LOCALAPPDATA，也没有为测试增加绕开 Native Gate 的任意路径参数。默认入口仍待独立方案与实际验收。

此源码修复不修改现有 ZIP。后续统一开发包应准确记录新 Runtime pin 和来源，保留所有旧包；本报告不是发布批准。真实 PostgreSQL 导入、个人恢复、用户数据迁移和默认配置切换仍不在本次自动执行范围。
