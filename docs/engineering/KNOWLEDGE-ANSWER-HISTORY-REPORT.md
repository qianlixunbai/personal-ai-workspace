# 知识问答历史：本机持久恢复与显式查看

后续进展：同日的[完整进程重启验收](KNOWLEDGE-ANSWER-HISTORY-RESTART-REPORT.md)已补齐真实 AssistantApp／JVM 重开和模型关闭后的历史阅读；[备份评估](KNOWLEDGE-ANSWER-HISTORY-BACKUP-ASSESSMENT.md)已完成，备份功能仍未扩展。本文保留实现阶段原始验证范围。

2026-11-09。基线 `b460ccbda7bd2e851683b115edecac73e4b205c7`，从 `codex/web-knowledge-restart` 建立 `codex/knowledge-answer-history`。仍为 Development-only，本轮不推送、不合并、不发布。

上一轮[完整重启报告](WEB-KNOWLEDGE-RESTART-AND-MODEL-REPORT.md)证明源文档和旧引用目标能恢复，但问答任务仅保存在 `TaskManager` 内存中。本轮为**升级之后新创建的知识问答**增加本机持久记录及用户可见历史入口。旧版已丢失的问答没有可恢复的记录，不从上轮测试 JSON 导入或伪造产品历史。

## 方案与边界

新增 `KnowledgeAnswerHistory`，在现有 `workspace.data-directory` 下使用独立的 `knowledge-answer-history/history.db`。这是当前 Profile 的本地执行记录，对 SQLite 和 PostgreSQL 源后端都采用同一种本机语义，不是 PostgreSQL 不可用时的降级，也不改变源文档权威位置。复用 `PrivateKnowledgeDirectory`／Windows Native Gate／现有 SQLite JDBC，单写进程文件锁、完整同步事务、schema 校验及内容脱敏异常；不新增依赖、不安装服务。

选择独立记录的原因：现有 Knowledge 源 schema、SQLite→PostgreSQL 迁移、独立知识备份及统一 PostgreSQL 恢复均有严格合同。把任务历史加入源 schema 会同时扩大这些合同和验证范围；复用 Conversation 则会混淆用户显式多轮对话与单次知识回答。本轮只解决同一资料配置的本机持续使用。

**现有 Knowledge、Workspace／PostgreSQL 备份、跨 Profile 恢复及数据库迁移不包含这份本地历史。** 界面明确提示这一限制。没有更改个人恢复门禁，也没有把新增目录写入旧备份格式或宣称完整数据恢复。下一步需要决定历史随备份迁移的产品合同，再更新清单和恢复验收。

## 用户可见行为

- 提交知识问题前，先持久保存任务 ID、原问题、检索词、公开模型配置摘要、提示词版本和创建时间；输入／证据／模型政策校验仍走原入口。
- 复用 `TaskManager.Completion`：回答和 Runtime 生成的精确引用一次提交，之后才发布 SUCCEEDED。取消、超时、失败及关闭也记录终态；落盘失败不得返回成功答案，错误属于 Knowledge。
- 重开时，已保存结果原样读取；遗留未完成记录变为 FAILED／KNOWLEDGE_INTERRUPTED。没有恢复工作队列、重新检索、重新抓取网页或自动模型调用。
- 知识页通过“查看问答历史”分页发现记录，再打开原问题、检索词、回答和引用。每页 20 条，最多 1000 条，满后明确拒绝新增，不静默淘汰历史；终态记录可经界面确认后删除。
- 删除历史不删除源文档；删除源文档也不会把历史引用改绑到别的版本。旧源已经删除时，打开引用会按原接口报不可用。删除当前显示的记录同时清空当前问题与结果；不承诺磁盘安全擦除。
- 历史不会注入普通 Ask、Memory、Conversation 或下一次知识问题，也不存入浏览器 localStorage。界面仍使用纯文本渲染。

新增原生 HTTP 合同：`GET /api/v1/knowledge/answer/tasks?page=0`、`GET /api/v1/knowledge/answer/tasks/{id}`、`DELETE /api/v1/knowledge/answer/tasks/{id}`。已有任务 GET／取消在内存缺失时只允许 Native 读取持久回执；取消历史不会重跑任务。Browser Extension 仍不能访问 Knowledge 历史。

桌面 Bridge 必须先读取当前会话的历史页，再允许打开／删除该页中的任务。会话切换及迟到结果重新核对会话实例和页代次；打开成功后才授权对应任务和引用源。沿用最多 64 个活动／终态任务授权及原回收规则。历史详情包含原问题和较大的引用结果，单独限制为 128 KiB，其余常规 Bridge 响应仍保持 64 KiB；两侧均严格验证字段、ID、状态和范围。

## 成熟项目与许可适配

在实现前检索了两个现有 Java 方案：

| 方案 | 已核实来源及取舍 |
| --- | --- |
| LangChain4j | [官方仓库](https://github.com/langchain4j/langchain4j)及[Apache-2.0 许可](https://github.com/langchain4j/langchain4j/blob/main/LICENSE)。页面可见近期发布；[官方 Chat Memory 文档](https://docs.langchain4j.dev/tutorials/chat-memory/)明确区分完整用户历史与模型窗口记忆，并说明窗口移除消息也会移除 Store 中的消息。参考其区分原则，未引入记忆窗口来保存用户历史。 |
| Spring AI | [官方仓库](https://github.com/spring-projects/spring-ai)标示 Apache-2.0；[官方 JDBC Chat Memory 文档](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)面向模型消息存储，列出 PostgreSQL 等方言，未列出本项目 SQLite 支持，也未提供本项目的取消／终态／精确引用合同。直接采用仍需自建任务层，同时增加框架与 schema，故未引入。 |

两者均只作为设计参考，没有复制实现或引入传递依赖。持久化实际复用项目已有 SQLite JDBC 3.53.2.0 与 Jackson；没有升级它们或重新声称本轮完成依赖漏洞总审计。持久内容只限用户已明确提交的问题、检索词、答案和引用，不保存模型原始输入证据全文、凭据或网页抓取令牌。

## 本轮验证证据

证据目录 `.verification/knowledge-answer-history-20261109/`。全部使用新建合成资料；没有访问默认个人 Profile、真实个人数据、KAG 或已有模型服务。

| 检查 | 实测 |
| --- | --- |
| 原生 Java 集成 `run-1/integration.log` | **17/17，通过且零跳过**：4 项新历史测试、6 项已有知识回答测试、6 项任务生命周期测试、1 项真实 Spring HTTP 场景。SQLite 真正关闭／重开，检查原问题／结果／引用一致、中断收敛、第二写者拒绝、未知 schema 拒绝、容量、取消后迟到结果、存储失败不可发布成功、HTTP 401/403 和普通 Ask 隔离。Provider 为合成 fixture，无真实模型。 |
| 最后历史边界复测 `run-2/integration.log` | **4/4**：列表摘要去除开头空白，保留原问题完整内容；仅重跑受影响历史测试，不把它与 17 项相加成独立场景。 |
| 桌面 `dotnet-tests-final.log`、对应 TRX | **25/25**：真实 RuntimeClient／Bridge 与假 HTTP Handler。覆盖原会话任务权限、历史页授权、迟到回复、删除后授权回收、字段与引用校验及现有模型错误合同。 |
| 桌面最后边界 `dotnet-history-boundaries-final.log` | **3/3**：历史专项，包含新增“3000 字符合法问题经 JSON 转义，加最大引用结果超过 64 KiB 仍能正确读取，且不超过 128 KiB”。与前组有重叠，不宣称 28 个独立测试。 |
| 前端 `frontend-tests-final.log` | **7/7**：真实 WorkspaceClient 与假 Bridge，包含重开保存答案／引用且没有模型提交、纯文本、确认删除仅影响历史、会话切换清除及迟到结果拒绝，以及既有阅读器和未知提交行为。 |
| 构建 | 离线 Maven package 及原有 JAR 审计成功，TypeScript／Vite／桌面 Debug 编译成功。Java 内部 API／弃用和 Mockito 自附加提示、Vite 单包超过 500 kB 提示保留；不是零警告或发布验收。 |

原生宿主复用既有 `MemoryRecallIntegrationProbe`，通过实际 Explorer 的非管理员启动方式创建全新合成 Profile，保持真实 Windows Native Gate、进程 Job、384 MiB Java 堆上限及 120 秒外部截止。`run-1` 的 Java PID 2808、临时 HTTP 端口 58025 均已独立确认退出／关闭；两轮 `child-result.json`／`host-result.json` 均退出码 0，合成数据库与日志保留作为证据。

首次普通 Maven 测试未经过 Native Gate，10 个文件存储场景被拒绝；诊断确认是 Windows native admission STOP，没有关闭门禁。随后改用项目已有宿主，临时诊断异常已经移除。首次前端测试入口缺少 `.bin` 命令，改用本地已装的 `vitest.mjs`；首次 .NET 缺少既有探针编译开关，补齐后构建。另修正旧错误测试夹具的 `knowledge-answer-v1` 为已有生产 `v2`，没有放宽生产解析。最大回包测试最初用超出已有 UTF-8 输入预算的中文夹具，被正确拒绝；改为预算内、转义后同样很大的字符。最后前端复测曾与桌面构建内部的 `npm ci` 争用依赖目录，出现模块暂时缺失；等构建结束后顺序运行。失败日志均保留。

## 未验证、回滚与下一步

本轮没有完整 AssistantApp／WebView／新 JVM 进程重启的历史回看验收。证明的是实际 SQLite 关闭重开、原生 Java／HTTP 集成和桌面／前端分层契约；不能继承上一轮“三次完整重启”作为新功能已验收证据。没有重跑 Qwen 质量测试、实际键鼠／IME／辅助功能、跨 Profile 迁移、PostgreSQL 源后端完整桌面流程或签名发布包。

优先下一步：在全新合成 Profile 里，通过实际界面产生一条知识回答，退出 AssistantApp 和 JVM，重开后从历史页打开它及旧版本引用；关闭模型服务确认历史阅读不要求模型 Ready。再验证进程中断记录、源删除后的提示，并决定是否将本地历史纳入备份／恢复合同。真实模型质量仍需独立题集，前轮两个英文样本不代表一般质量通过。

回滚标签 `workspace-before-answer-history-20261109` 指向基线 `b460ccb`。代码可通过新分支／worktree 回看或显式 revert；不会自动删除本地历史数据库。六份原有未跟踪文档在 `preserved-documents.json` 有哈希及 `originals/` 副本，不暂存、不覆盖。提交清单和最终 SHA 在证据根目录 `completion.json`；只做本地 Git 回滚点，未推送、合并或部署。
