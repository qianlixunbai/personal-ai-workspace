# 跨会话原文查找：隔离真实桌面与 PostgreSQL 验证

2026-11-09。基线 `ffa3a12`，分支 `codex/conversation-search-desktop`，回滚标签 `workspace-before-conversation-search-desktop-20261109`；验收工具提交 `150f0a7`。仅本地提交，未推送、合并、部署或发布；仍为 Development-only。

## 结论与改动范围

实际 WPF／WebView2／React／Native Bridge／HTTP／SQLite 已通过原文查找、分页后打开准确轮次、归档筛选、同会话草稿保留、跨会话取消与确认，以及窗口重开后的再次查找。独立临时 PostgreSQL 17.11 的真实应用角色也通过搜索 Store 场景。本轮未发现需要修改产品逻辑的问题，只扩展既有验收工具。

这是[原文查找实现报告](CONVERSATION-MESSAGE-SEARCH-REPORT.md)的后续验证，补齐其真实 WebView 和 PostgreSQL Store 空白；不是完整 AssistantApp／正式安装包验收，也不是 PG 桌面组合验收。此前对个人恢复、正式分发和最大容量性能的限制仍然有效。

直接复用 `MemoryDesktopAcceptanceProbe`、`MemoryDesktopIntegrationProbe` 和 `Run-MemoryPostgresqlProbe.ps1`。桌面增加一个显式 `conversation-search` 模式及一份 partial 文件；Java API 测试增加一个 opt-in 场景；PG 在已有综合场景中增加搜索断言。未建立新的服务、通用测试框架、模型实例或搜索索引。成熟项目的取舍沿用上一轮 Open WebUI／LibreChat 官方来源评估，本轮不引入第三方代码或依赖。

## 实际桌面流程

使用全新合成 Profile 的 evidence 数据库；准备 23 轮活动会话及 1 轮已归档、已取消的历史，其中 13 条活动消息和 1 条归档消息含 `检索目标😀%_`。普通 Profile/data 目录保持为空。没有启动合成提供器或真实模型；使用真实生产窗口和查询链路，只有测试传输层将固定 Runtime 地址映射到本轮自有随机端口。

| 核心操作 | 实际断言与结果 |
| --- | --- |
| 全部与使用中筛选 | 全部第一页 10 条；使用中 10＋3 条两页，无归档标题。查询中的 emoji、`%_` 和消息里的 HTML 均按原文显示，高亮准确，无 script 元素执行。 |
| 同会话跳转 | 从第二页命中打开第 11 轮；实际焦点落在该轮，正文与真实 HTTP 读取结果完全一致，未发送草稿完整保留。 |
| 归档筛选与取消 | 仅返回 1 条归档命中；点击后出现清空草稿确认，默认焦点在取消。取消后仍在原活动会话，草稿不变。 |
| 确认打开归档历史 | 确认清空后精确打开归档第 1 轮；输入框为空且只读、发送禁用、恢复按钮可见。只读查询再次确认记录仍为 ARCHIVED，没有自动恢复。 |
| 窗口重开 | 首个窗口完成清理后创建第二个真实窗口，初始搜索词、结果和草稿为空；重新查找、翻页并打开第 11 轮通过。两个窗口复用同一个 JVM，未宣称完整进程重启。 |
| 无写入和无模型 | 6 次只读搜索 POST；其它非 GET 请求由测试传输层拒绝，实际 mutationAttempts=0。前后所有会话详情深比较相同，仍为 1 活动＋1 归档；模型未就绪，chat／probe 调用均为 0。 |

四张实际 WebView 截图均已目视核对：

- [同会话定位并保留草稿](../../.verification/conversation-search-desktop-20261109/run-1/same-conversation-jump.png)
- [跨会话草稿确认](../../.verification/conversation-search-desktop-20261109/run-1/draft-confirmation.png)
- [打开只读归档历史](../../.verification/conversation-search-desktop-20261109/run-1/archived-result-opened.png)
- [窗口重开后再次定位](../../.verification/conversation-search-desktop-20261109/run-1/reopened-search.png)

截图由真实 WebView CapturePreview 生成；操作通过实际页面 DOM 触发输入与点击，未覆盖人工键鼠、IME 或屏幕阅读器体验。截图不包含个人记录。

## PostgreSQL 补验

为了核实与 SQLite 不同的 `strpos` 查询分支，复用已有专用夹具创建全新、私有 ACL 的 PostgreSQL 17.11 集群，仅监听本轮随机 loopback 端口。沿用 SCRAM、原生准入、程序身份核对、Flyway 迁移及非管理员应用角色；没有连接既有数据库、使用既有凭据或修改服务配置。

在原 `MemoryPostgresqlLifecycleTest` 综合场景内新增：13 条活动终态匹配＋1 条归档终态匹配＋1 条 PENDING 匹配；实际使用低权限应用连接执行生产 ConversationStore。

- 活动分页 10＋3，message ID 不重复，PENDING 被排除；归档筛选仅返回正确会话。
- 中文、emoji、字面 `%_`、大小写及 SQL 形态文本正确；摘要前后截断、UTF-16 匹配偏移与原文一致，HTML 保留为文本。
- 查询前后活动记录一致；关闭重开 Store 后结果相同；删除夹具自己的归档会话后不再命中。
- 既有 Memory 生命周期、SQLite 排名对照、只读事务及 sentinel 检查继续通过。这里只计为 **1 个综合测试**，不把每个断言计作独立测试。

夹具收尾确认自有 PG 实例停止、端口关闭，初始化密码文件和临时连接凭据文件移除；保留合成集群与诊断证据，没有递归清理或覆盖用户目录。

## 实测证据与复现

证据根目录：`.verification/conversation-search-desktop-20261109/`，由 Git 忽略，仅保留本地。源码中的 opt-in 夹具已提交。

| 文件 | 实际结果 |
| --- | --- |
| `java-build-1.log` | 离线 Maven package、测试编译、既有 Runtime JAR 审计成功；构建跳过测试，不计为动态执行。 |
| `dotnet-build-1.log` | 实际验收宿主及 Desktop 编译通过，0 warnings／0 errors；复用上一轮已构建且经过资产检查的前端，未修改前端代码。 |
| `run-1/integration.log` | 原生准入、真实 HTTP／SQLite，1 found／1 passed，0 failed／skipped；`DESKTOP_CONVERSATION_SEARCH_UNCHANGED_HISTORY_NO_MODEL_PASS`。 |
| `run-1/host.log`、`workflow.json`、`result.json` | 四段真实 UI 流程通过，6 次搜索、0 次变更请求、0 模型调用；宿主与 JVM 退出 0。 |
| `postgresql-compile.log` | PG 断言加入后的离线测试编译成功。 |
| `pg-run-1/native/integration.log` | PostgreSQL 17.11，1 found／1 passed，0 failed／aborted／skipped；`POSTGRESQL_CONVERSATION_LITERAL_SEARCH_PAGING_SCOPE_REOPEN_PASS`。 |
| `pg-run-1/database-evidence.json`、`fixture-result.json` | postgresqlConversationSearch=true，testExitCode=0，ownedInstanceStopped=true，temporarySecretsRemoved=true。 |
| `final-isolation-check.json` | 三个自有 JVM／PG PID 均退出、两个端口均关闭，无匹配自有 Profile／PG 路径的剩余进程；六份用户文档逐项 SHA-256 不变。 |

桌面与 PG 动态执行均首次通过。初次捕获中文文件名的哈希时遇到 shell 编码解码错误，已用 UTF-8 与前轮基线逐项重核；原始错误记录保留，没有改动用户文件。终端部分构建日志存在本机编码显示问题，构建退出码及成功信息另有核对。

桌面：Profile `79927a1f51d74d028cde65a803924983`，JVM PID `51904`，HTTP `51829`。PG：专用根 `paw-memory-postgresql-1f4953619fa24230871763c7ab9a57e3`，PG PID `34824`／端口 `62860`，原生测试 Profile `49c1131b4245478ca497d61ea0040841`，JVM PID `48172`。本轮仅针对这些归属已核对的进程做退出检查；没有对既有服务执行启停。个人最近工作区选择文件仅作哈希比对，未解析内容或据此启动；选择未变，未读取真实数据库。

构建 Runtime JAR SHA-256 为 `E7DE8550811A492AE14F14C9D237021536903120F4F6CAFAB5B65B449BE9E8F2`，未部署；原生测试使用本轮 classes／test-classes。生产源文件没有变更。

复现沿用现有非管理员 Windows 桌面宿主准入：离线 Maven 编译，以 `Mmf3ProfileAcceptanceBuild=true` 和 `FrontendSkipBuild=true` 构建既有桌面探针；编译两个既有 Java launcher，设置本轮 classes／test-classes 和依赖 classpath。桌面探针第五参数为 `conversation-search`，PG 调用既有 `Run-MemoryPostgresqlProbe.ps1`。本轮确切命令与入口分别保留为 `run-1.ps1`、`pg-run-1.ps1`、`classpath.txt`。从实际 Explorer 桌面启动以满足既有 KnownFolder／原生身份规则；没有放宽验证器或安全设置。

## 仍未覆盖及下一步

本轮没有完整 AssistantApp 的托盘／快捷键／DevelopmentRuntimeOwner 启动、独立 JVM 重启后的此搜索 UI、正式安装包、PG HTTP／PG 桌面组合、最大容量扫描性能或并发变化下跨页一致性验收。不会把同 JVM 的窗口重开称为完整产品重启，也不会把 PG Store 通过称为 PG 整包通过。

没有读取真实个人数据、备份或恢复个人资料、启动模型、调用 KAG 端点或占用 GPU。个人备份／恢复入口与安全前置保持不变。上一轮 Java 3/3、.NET 31/31、前端 16/16 是历史分层证据，本轮未重复运行或混入本轮计数。

本功能的指定隔离核心验收已完成。后续围绕实际使用反馈和最终产品验收补缺，不自动扩展测试基础设施或恢复框架；若安排完整应用或 PG 组合验收，应单独说明覆盖范围。回退可使用本轮提交或上述基线标签，保留用户未跟踪文档和此前功能成果。
