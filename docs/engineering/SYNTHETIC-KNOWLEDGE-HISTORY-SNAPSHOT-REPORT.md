# 合成资料中的 Knowledge 来源与问答历史联合快照

2026-11-09。基线 `1b772536a63371eaa571075a5d778dff3d0e04fa`，分支 `codex/knowledge-history-snapshot`，回滚标签 `workspace-before-knowledge-history-snapshot-20261109`。实现阶段提交 `3a742a1`。仅本地提交，未推送／合并／发布，仍为 Development-only。

## 已完成的最小闭环

实现了 **SQLite 合成来源＋终态问答的一致逻辑快照 → 私有暂存验证 → 新候选数据目录 → 关闭／重开读取**。通过真实 SQLite、现有 Knowledge 解析／源文件／locator 和历史存储验证，没有模拟持久层。恢复后可读取原问题、检索词、终态、时间、回答及精确旧版本引用，不创建运行任务，不调用模型。

这是包内 `SyntheticKnowledgeSnapshot` 原型。唯一工厂创建全新、随机命名的合成来源；Windows 下还要求原有 Native Gate 已准入，父目录处于该 Profile 的 evidence 域。它没有接受既有源数据库的入口，没有 Spring bean、CLI、HTTP、Bridge 或 UI 接口。恢复目标只能是本轮自建根目录下不存在的候选目录。**不是面向用户的备份功能，不能用它对当前个人数据库备份或恢复。**

## 实现与兼容边界

| 部分 | 本轮行为 |
| --- | --- |
| 一致快照 | 固定锁顺序 `KnowledgeStore → KnowledgeAnswerHistory`，同时持有两个 Store monitor 与两个 SQLite `BEGIN IMMEDIATE` 事务。导出期间既有方法和其他 SQL 连接均不能写入两库。导出不打开第二个源 History 构造器，不触发中断收敛。 |
| 未完成任务 | 历史 QUEUED、Knowledge PENDING／PARSING 或未完成删除均拒绝快照；不取消任务、不修改状态后冒充完整快照。 |
| 内容格式 | 新命名格式 `personal-ai-workspace.synthetic-knowledge-history-snapshot` v1，明确 `UNENCRYPTED_SYNTHETIC_ONLY`。固定三文件：原有 v1 `knowledge.backup`、有界逻辑终态记录 `answers.bin`、包含数量／长度／SHA-256 的 `manifest.json`。不收纳任意路径、SQLite 页、外来 schema、SQL、凭据或模型状态。 |
| 旧备份 | 仅提取 `KnowledgeBackup` 原有 v1 写入逻辑，便于在联合事务中调用；原魔数、字节布局、版本和公开入口保持不变。Memory／Workspace／PG／OpenPGP 格式均未修改。旧备份仍不含问答历史。 |
| 历史记录 | 最多 1000 条，单条最多 128 KiB；固定 `knowledge-answer-v2`、当前 LOCAL 公共 profile 合同和终态错误白名单。保存原 task ID／问题／检索词／时间／答案／引用，task ID 只作历史标识。严格重复字段、未知字段、类型、Unicode、状态／结果、数量与长度检查，不静默转换或丢弃。 |
| 引用 | 每条引用独立记录 AVAILABLE／UNAVAILABLE_AT_SNAPSHOT。有源时核对原 revision、source／representation／摘录摘要、UTF-16 边界、行号和结构 locator；不改绑当前版本。整份文档原已缺失时保留历史与不可用状态；已有文档缺 revision、应有源缺件或来源与声明不符会拒绝。不可用不等于已证明曾被删除。 |
| 恢复与发布 | 固定文件白名单与摘要预检，复用 v1 源重解析，在全新私有 staging 内建立可信历史 schema，再校验所有记录及引用。关闭两个库后，复用 `MemoryBackupService` 的同父目录、禁止覆盖 rename 方式发布整个候选目录。初始或发布前出现目标均拒绝；没有 merge、覆盖或跨卷复制回退。 |
| 失败处理 | 未完成快照和恢复 staging 留在本轮合成根中作为诊断证据，绝不当成完成快照或激活 Profile；不递归删除未知文件。 |

实现文件：`KnowledgeHistorySnapshotRecord.java`（严格逻辑记录与引用绑定）、`SyntheticKnowledgeSnapshot.java`（合成限定编排）、两个 Store 的包内冻结／重建方法，以及 `KnowledgeBackup.writeSnapshot`。未增加第三方依赖。

复用依据沿用[上一轮官方项目及许可评估](KNOWLEDGE-ANSWER-HISTORY-BACKUP-ASSESSMENT.md#开源复用与许可核对)：已有 Xerial SQLite JDBC `3.53.2.0`（Apache-2.0）、Jackson、JDK 文件与摘要、Knowledge v1 解析重建及 Memory 发布模式。本轮通过逻辑事务快照实现，不复制活动 SQLite 文件；现有 Bouncy Castle OpenPGP 保持原协议，未另造加密方案，也未把新的明文合成格式开放给个人资料。

## 实测与失败记录

证据根 `.verification/knowledge-history-snapshot-20261109/`，使用已有受信 Windows 宿主、全新合成 Profile、原生 Native Gate、JDK 21、单 JVM 384 MiB／2 CPU；未启动 PostgreSQL、Ollama、桌面应用或任何既有服务。

| 证据 | 真实结果 |
| --- | --- |
| `build-final.log`、`implementation.json` | 离线 Maven 构建及既有 Runtime JAR 审计成功。JAR SHA-256 `69521F3B7470B7A9741170F9F30E4DBBDB70F07F629F5522BBA6852687AFAA00`。 |
| `run-3/integration.log` | **10/10，零跳过**：4 项新联合快照场景＋6 项原有 Knowledge v1 备份测试。含 5 条终态记录、归档文档的两个 revision、已删除来源、原源关闭并改名后复制快照至另一全新夹具恢复、目标关闭重开两次；核对原记录及预览完全一致。 |
| 同组并发与失败断言 | 两个独立 SQL 连接的写锁被拒绝，两个并发 Store 写操作在快照期间阻塞，释放后完成；恢复仍得到快照时的源状态和历史。QUEUED 不被收敛；注入导出失败后锁释放。 |
| 同组恢复边界 | 重算摘要的错误 revision／引用／类型／重复字段与记录仍拒绝；应有源被换成空来源、超大记录、未知文件／版本、损坏摘要、非空或越界目标均拒绝。发布前插入用户文件及注入发布失败时，既有文件、当前来源与历史不被修改。 |
| `run-2/integration.log` | 当轮 **13/14**：新闭环 4/4 与历史存储 4/4 已通过，旧备份 5/6。随后仅复测受影响的 10 项；不把两轮重复部分相加成 24 项。 |
| `run-1/integration.log` | **6/14**。深层合成路径上 SQLite 初始化失败，现场存在 257 字符的零字节 DB，journal 路径还会更长；原生临时目录及本原型随机子目录缩短后相关失败消失。此结果与 Windows SQLite 路径预算一致；未改变系统长路径、ACL 或 Native Gate 设置。 |

剩余旧测试失败来自测试自身用 `Files.createDirectory` 创建待恢复目录，未按 Native Gate 创建私有目录；改为已有 `PrivateKnowledgeDirectory.directory` 后通过，生产门禁未放宽。首次编译使用了当前 Jackson 3 不提供的旧枚举开关；移除该调用，以严格 JSON 往返对比拒绝类型强制转换，数值伪装状态／revision 的测试通过。首次探针适配编译也因主构建失败、类尚未生成而失败；这些日志保留，不记为通过。

原生 JVM `28380`、`19988`、`60088` 均由宿主报告退出；最终成功 Profile 为 `953db57ed0014f3494b434e33a45516b`。失败夹具和最终快照仅含合成数据，保留用于诊断。未重跑无关前端／桌面／模型质量测试。六份既有未跟踪文档有原始副本与最终哈希比对，未编辑或暂存。

## 尚未解决的限制与下一步

目前只支持新建合成 SQLite 数据目录，且历史解析合同限定当前 v2。没有接入用户按钮、真实个人恢复、PG 源、一致的跨 Profile／跨库身份绑定、原生候选注册或显式激活；不因此改变现有 UI“备份不包含历史”的提示。

新容器是**明文合成测试格式，摘要不是认证**；不承诺抵御攻击者重写整套自洽文件。可移植或个人备份须先接入现有认证加密容器，并正式版本化维护／恢复合同。也未验收断电／磁盘满／突然杀进程、跨机、任意长路径或恢复目标的完整 AssistantApp 启动。本轮关闭重开 Store 不等于新 JVM 恢复验收。

建议下一步先在合成环境补“候选数据目录经受信原生注册后，由全新 JVM／桌面只读历史及精确引用”的闭环，并继续保持模型关闭、任务不重放；之后再扩展既有加密容器及 PG 身份和停写覆盖。不要先增加个人导出按钮或放宽当前维护准入。
