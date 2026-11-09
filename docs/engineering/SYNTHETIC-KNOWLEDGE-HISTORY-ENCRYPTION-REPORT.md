# 合成知识来源与问答历史：加密归档及恢复

2026-11-09。基线 `99f54a7`，分支 `codex/knowledge-history-encrypted`，回滚标签 `workspace-before-knowledge-history-encrypted-20261109`。实现及测试提交 `b91594e`。仅本地提交，未推送、合并或发布；仍为 Development-only。Workspace 与 KAG 分开，未操作 KAG。

## 本轮交付

已完成 **新建合成 SQLite 来源／终态历史 → 直接写入认证加密归档 → 完整认证 → 来源及历史重建 → 发布新候选目录 → 关闭／重开读取**。保留原问题、检索词、状态、时间、答案、旧 revision 的精确引用及原本不可用的来源。原源关闭并改名后，从复制到另一全新夹具的密文恢复，结果一致。

本轮先消除[明文快照原型](SYNTHETIC-KNOWLEDGE-HISTORY-SNAPSHOT-REPORT.md)的传输保护缺口：复用现有 OpenPGP 流，不增加依赖、服务、数据库类型或通用恢复框架。它仍是合成验证功能，**不是用户可用的个人备份按钮，也没有完成 Profile 注册／激活和完整桌面恢复验收**。

## 复用方式与合同

| 部分 | 实现及边界 |
| --- | --- |
| 加密实现 | 从已有 `PortableEncryptedBackup` 提取 `OpenPgpStreams`，旧调用点委托给它。继续使用已安装 Bouncy Castle `1.86`、RFC 9580、AES-256-GCM、Argon2id 64 MiB／3 passes／parallelism 4；没有编写密码算法或升级包。 |
| 格式隔离 | 新合成外层格式 `personal-ai-workspace.synthetic-knowledge-history-openpgp` v1，literal 为 `workspace-synthetic-knowledge-history-v1.zip`，外层仅 `backup.pgp`／`complete.json`。旧 PG v1/v2/v3 的版本、literal、文件白名单及准入未扩大。 |
| 一致逻辑内容 | 明文原型与加密路径共用 `writeEntries`，仍按 KnowledgeStore → History 的顺序持有两个 monitor／SQLite 写事务，拒绝 QUEUED 与未完成导入；流式计算实际来源和历史字节的长度及摘要。 |
| 直接加密导出 | 在同一逻辑快照窗口把固定三个条目写入 ZIP／OpenPGP，没有额外落盘的明文快照或 ZIP。关闭加密流并 force 后写外层完成记录，再无覆盖 rename 发布整个密文目录。失败仅留下 `.pending` 密文，不报告完成。 |
| 内外版本 | 内部仍是原合成明文逻辑格式 v1，`UNENCRYPTED_SYNTHETIC_ONLY` 描述解密后的内容；外层单独声明固定密码配置。外层摘要是预检信息，不是认证证据。 |
| 完整认证 | Windows 下持有禁止写入／删除共享的密文及完成文件句柄；先检查固定 KDF／AEAD 参数，再将第一遍明文流全部读到空输出，检查末尾认证标签及无尾随包。通过后才创建解包目录，从同一密文句柄第二遍解密。 |
| 解包与恢复 | 只允许 `knowledge.backup`／`answers.bin`／`manifest.json`，保持逐项限额及总预算，拒绝目录、未知／越界路径、缺件、错误类型或版本。认证不免除原有源重解析、可信 schema、历史记录和精确引用校验。 |
| 清理及发布 | 校验成功后、候选目录发布前删除本轮解包的固定三文件和目录；异常路径也清理这些文件。不递归删除未知内容，不宣称安全擦除磁盘。重建失败的私有 staging 仍按原原型保留诊断，永不激活。 |
| 口令及范围 | 入口成功／失败均清空调用方 `char[]`；测试口令仅随机生成于内存，不写日志、命令行或文件。没有个人来源构造器、Spring bean、CLI、HTTP、Bridge 或 UI 路由；只有新建合成夹具能力可以调用。 |

沿用[此前官方项目及许可核对](KNOWLEDGE-ANSWER-HISTORY-BACKUP-ASSESSMENT.md#开源复用与许可核对)。本轮通过 Firecrawl `read-docs` 检索 [Bouncy Castle 官方 PGPEncryptedData API](https://downloads.bouncycastle.org/java/docs/bcpg-jdk18on-javadoc/org/bouncycastle/openpgp/PGPEncryptedData.html)，核对完整读取后进行完整性验证的顺序。在线页面自报 **1.85**，不能作为本机 1.86 全版本证明；实际编译及所有测试使用现有 **1.86** JAR。没有复制外部实现，许可信息仍见 [Bouncy Castle 官方许可](https://www.bouncycastle.org/about/license/)。

## 实测

证据根 `.verification/knowledge-history-encrypted-20261109/`。使用前轮已有受信 .NET 原生宿主、全新合成 Profile、JDK 21、单 JVM `-Xmx384m`／2 CPU、原 Native Gate。没有启动 PostgreSQL、Ollama、AssistantApp／WebView、HTTP 服务或任何现有服务。

| 检查／文件 | 结果 |
| --- | --- |
| `build-1.log`、`test-compile-1.log`、`build-2.log` | 离线 Maven 编译、测试编译、package 及 Runtime JAR 审计成功。Maven 构建阶段跳过测试，实际测试由下列原生宿主执行，不能混计。保留现有内部 API／弃用／unchecked 编译提示。 |
| `run-2/integration.log` | **16/16 成功，0 失败／中止／跳过**：9 项联合快照测试（原有 4＋新增加密 5）、6 项原 Knowledge v1 备份测试、1 项旧 OpenPGP 流合同测试。 |
| 加密往返 | 5 条记录，覆盖 SUCCEEDED（2 条）、FAILED、CANCELLED、TIMED_OUT；包括已归档来源的两个 revision 和一份原本已删除的来源。接收夹具原有空 Store 保持空，恢复库关闭／重开两次后逐记录及旧版本预览完全一致。没有任务执行队列或模型调用。 |
| 认证拒绝 | 错口令、密文中部及末尾篡改、截断、尾随字节、非法 KDF 内存参数：即使重新生成公开长度／摘要也全部拒绝，没有解包目录或候选目标；口令数组已清空，当前源和历史未变。 |
| 认证后的结构拒绝 | 正确口令生成的越界路径、外来 `history.db`、缺件、4097 字节 manifest、错误 legacy literal、来源／历史摘要不一致，均拒绝且不发布目标。解包运输文件已清理。 |
| 完成／目标边界 | 未完成问答不被自动收敛；注入导出异常及线程中断不发布完成目录，随后仍可操作原 Store。非空目标的用户文件保留；外层版本／类型／重复字段／额外文件拒绝。 |
| 旧格式回归 | 旧 Knowledge v1 6 项通过。旧 `PortableEncryptedBackup` 三种 literal 通过原调用点加密、独立 BC 读取／认证以及原调用点解密；参数保持原合同。这不是完整 PostgreSQL 备份恢复回归，未访问任何 PG 库。 |
| 退出 | `run-2/child-result.json`：Profile `024d4c24a8a14eda965b826fdd221f2b`，自有 JVM PID `32024`，exit 0／childExited=true；`host-result.json` 为 exit 0。 |

候选 JAR SHA-256：`4799081F1EED6A14E935C4262DB2785B27AF25ACB3A09D73B1F775319E526F50`。该 JAR 在实现提交前由相同工作树源码构建；原生测试运行此次编译的 classes／test-classes，未部署 JAR。

首次 `run-1` 在 PREPARE／`WindowsProfileSecurity.OpenDirectory` 阶段失败，未进入 Java。与[已记录的打包宿主 Known Folder 映射问题](MEMORY-RECALL-REPORT.md)一致。改为项目已有的实际 Explorer `ShellWindows.FindWindowSW` → `Document.Application.ShellExecute` 隐藏启动，同用户、非管理员的新夹具通过。未修补 ACL、修改安全设置／Bootstrap pin／Native Gate，未重用失败目录。初次失败记录保留，不算通过。

六份原有未跟踪文档在 `preserved-documents.json` 中记录原始 SHA-256，收尾逐项核对未变，未编辑或暂存。未复制真实资料、读取个人密钥或对个人数据库执行任何备份／恢复。

## 复现与剩余范围

复用现有 `bootstrap/tests/MemoryRecallIntegrationProbe.csproj` 及受信构建宿主；将 `bootstrap/tests/knowledge-snapshot/MemoryRecallIntegrationProbe.java` **单独**编译到专用 classes 目录，放在测试 classpath 首位。不要同时编译同名 Memory 适配器。先离线编译当前 Maven reactor，再从路径身份一致的普通 Windows 桌面宿主运行原 .NET probe，传入 JDK java、固定 JNA、classpath 文件、全新证据目录四个参数；不传 PostgreSQL 参数。实际命令保留于证据目录 `run-2.ps1`，未添加自动执行任务。

目前只有来源与终态问答；不包含 Memory、Conversations、凭据、模型、WebView 或运行任务。恢复目标及失败重建 staging 是私有**明文**数据库／来源，不是数据库静态加密。没有认证加密后的个人来源授权／数据集身份绑定、原生候选注册或 Profile 激活；对称认证也不能证明归档来自某个被授权的原 Profile。

**未验收**：恢复候选的全新 JVM／完整 AssistantApp／历史页、跨 Profile／跨机、PG 源、一致停写的个人生命周期、断电／磁盘满／杀进程、恶意同 SID 并发进程、最大容量及人工 UI。现有用户备份入口仍不包含历史，提示保持原样；不能据此声称个人数据已备份或可以恢复。

下一步应回到产品恢复闭环：在合成命名空间中复用已有原生来源登记／候选机制，将这一固定内容合同连接到新 JVM 和实际历史页，验证无模型阅读及原引用，再考虑受控个人入口。不继续增加归档类型、PG 框架或提前开放用户按钮。
