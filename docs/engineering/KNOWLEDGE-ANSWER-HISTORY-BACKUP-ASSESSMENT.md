# 知识问答历史纳入备份：范围与实现边界

后续实现：[合成 SQLite 联合快照原型及实测](SYNTHETIC-KNOWLEDGE-HISTORY-SNAPSHOT-REPORT.md)。它落实限定的逻辑往返与引用校验，尚未接入个人备份、PG 或现有加密容器；本文保留评估阶段的范围决定。

2026-11-09。基于历史实现 `5ae7b00` 和当前备份代码的只读评估。本轮不改变备份格式、不操作个人数据、不扩大恢复准入；六份既有未跟踪设计文档保持原样。

## 结论

可以复用现有组件实现，但不能在旧格式中直接附加 `history.db`。历史库独立于 Knowledge SQLite／PostgreSQL 源库，现有源事务和 PG 停写锁均不覆盖它。最有价值的下一步是一个**合成资料限定、版本化的 Knowledge 源与终态问答联合逻辑快照**，先验证引用与源的一致性，再决定是否接入统一 PG 恢复；本轮仅评估，不宣称已支持备份历史。

独立“导出问答 JSON”可以保存文字，但缺少不可变源版本时无法保证引用恢复，不能作为完整恢复方案。复制整个 Profile 又会夹带凭据、WebView、模型与临时文件，因此也不适合。

## 实际代码边界

| 现有机制 | 实际范围 | 纳入历史需要改变什么 |
| --- | --- | --- |
| `KnowledgeBackup` v1 | Knowledge 源、全部保留 revision、normalized text、locator；有界流、摘要、空目标 staging、重解析与原子发布。 | 新格式及元数据，不修改 v1 含义；增加历史与源的共同停写边界、历史逻辑记录验证和联合发布。旧 v1 恢复应明确“没有问答历史”，不能补造记录。 |
| `WorkspaceBackupService` v1 | Memory、Conversations 的逻辑 JSON；PENDING 拒绝；当前 PG 拒绝逻辑 restore。 | 本身不含 Knowledge 源。把历史加进去会形成无源引用，应继续保持明确范围，不把多个按钮串成一致恢复。 |
| `UnifiedPostgresqlBackup`／`WorkspaceV3Catalog` v3 | 精确 PG17 Workspace V1/V2 对象、数据快照和固定 Knowledge source 文件；派生索引重建。 | PG dump 不会包含本地 SQLite。需新内容版本、独立历史记录项及摘要、源身份绑定、停止所有相关写者、恢复候选核对。只改 PG 表清单无效。 |
| `PortableEncryptedBackup` | 已有 OpenPGP 认证、ZIP 流、限额和固定路径白名单；外层完成凭据与内层版本配对。 | 现有 `allowed()` 只接受 dump、manifest、完成标志和特定 source 路径。新历史项必须显式版本化，并贯穿认证、解包限额、暂存、发布与回滚；不能放宽成任意文件。 |
| `WorkspaceSourceBackup.FrozenSource` | 现有合成源维护能力、PG SHARE 锁、固定源文件句柄及前后快照核对。代码明确不是完整停写证明。 | 新增历史读快照／独占写入排除，且覆盖历史新增、完成回调、删除、源修改与物理删除。不能把当前 PG 锁或 UI busy 当作跨库锁。个人恢复仍不开放。 |

历史生产构造器会把 QUEUED 更新为 FAILED／KNOWLEDGE_INTERRUPTED。**备份检查不得直接实例化 `KnowledgeAnswerHistory` 来“读取”离线源**，否则检查本身会改写源。应提供只读逻辑快照校验入口，在没有未完成任务时导出；有 QUEUED 则整次拒绝，由正常运行生命周期处理后重试。

## 开源复用与许可核对

沿用 Firecrawl 开发者检索和官方资料核对，未复制外部实现或新增依赖。

| 成熟组件 | 依据、许可和适配结论 |
| --- | --- |
| [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc) | 当前项目已用 `3.53.2.0`；[Apache-2.0](https://github.com/xerial/sqlite-jdbc/blob/master/LICENSE)。[官方用法](https://github.com/xerial/sqlite-jdbc/blob/master/USAGE.md#how-to-use-online-backup-and-restore-feature)已有 online backup／restore，不需要再装数据库或备份服务。可供受控私有 staging 快照使用；产品归档仍优先逻辑重建，不直接信任外来 SQLite schema。 |
| [SQLite Online Backup API](https://sqlite.org/backup.html) | 可形成 SQLite 数据库的一致副本。它只解决一个 SQLite 数据库快照；不能据此声称与另一个 SQLite／PG 源事务一致。这是结合项目存储布局的适配判断，仍需应用级停写。 |
| [Bouncy Castle Java](https://github.com/bcgit/bc-java) | 已有 `bcpg-jdk18on 1.86` 及 OpenPGP 流；[许可](https://www.bouncycastle.org/about/license/)为 MIT 风格，仓库注明附带修改版 BZIP2 为 Apache-2.0。继续复用项目已实现的认证与限额，不新建加密协议、不因新版本可用就升级依赖。 |
| [PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html) | 官方支持单库一致快照和指定同步 snapshot。继续复用现有受审查工具；本地历史文件不在数据库 dump 内，不能推断跨存储一致性。 |

## 建议的窄合同

1. 显式导出“全部现存终态问答＋Knowledge 保留源”，最多 1000 条；保留问题、检索词、状态、时间、回答、引用、公共模型描述和 prompt 版本。不复制原生凭据、运行队列、模型 Ready／lease、浏览器会话、日志与证据。失败／取消／超时记录也应保留；缺失记录不可补造。
2. 获取同一维护窗口后禁止源与历史写入，保持快照直到输出摘要完成。固定锁顺序和异常释放，拒绝未完成任务；不在导出中取消任务或修改源。对于统一 PG 版本，还须绑定当前原生 Profile 和该 PG 数据集，避免同一 Profile 换库后把不同来源历史无提示混装。
3. 归档使用固定、有界、可流式验证的逻辑记录，不带可执行 SQL、SQLite 页或用户指定路径。严格检查重复字段／ID、Unicode、数量／字节上限、状态与 result/error 组合、公共模型字段、时间和版本；摘要覆盖实际记录顺序及源清单。当前运行期约束不是不可信导入验证器，不能只复用 `get()` 的 JSON 反序列化。
4. 引用保持原 document ID、revision、UTF-16 文本范围与 locator。恢复目标有该源版本时，核对范围、文本／representation 和该 revision；不得改绑到“最新”或同名文档。源已物理删除是合法历史状态，应保留文字并明确引用不可用。应在导出时记录引用可用性及源摘要，恢复时区分原本缺失与归档意外缺件；不能用“允许缺失引用”掩盖缺包。
5. 仅在全新、私有、未激活的目标 staging 里重建可信 schema，完整认证后再消费。所有 ID 冲突和非空目标拒绝；禁止 merge／覆盖当前库。完成源与历史联合校验后一次发布候选 Profile；持有维护能力直到收尾，不分两步发布成半恢复。历史任务 ID 仅作记录，读取仍经新会话历史页授权，不复活模型调用或执行队列。
6. 版本与覆盖范围必须在原生确认、归档元数据和结果中一致显示。旧格式继续严格读取原范围，不能把“历史缺失”显示成完整恢复。新的可移植备份应纳入已有认证加密容器；本机逻辑验证可先使用合成数据，不因此开放真实明文导出。

## 最小后续验收

先做一个合成联合快照往返场景：成功／失败历史、旧 revision、原本已删除源、精确引用、无模型阅读、零任务重放。一个边界组覆盖 QUEUED／并发写拒绝、损坏／重复／超限、外来 schema 或额外路径拒绝、源缺件与原本缺失的区分、非空目标拒绝及发布失败不改当前 Profile。旧格式兼容只运行受影响测试，不扩成全量平台测试。

本轮未实现或运行这些备份／恢复验收；没有启动 PostgreSQL、创建备份、解密或恢复任何真实资料。下一阶段须先落实联合停写与新版本合同。当前仍可使用本机历史阅读，**现有备份不含问答历史**。
