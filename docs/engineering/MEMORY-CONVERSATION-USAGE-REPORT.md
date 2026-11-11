# 记忆到历史会话的使用记录

2026-11-11 接续：[初始化失败诊断与清理修复](MEMORY-USAGE-DATABASE-BLOCKER.md)修正了失败后的附加空指针。旧 Surefire 参数缺少 Windows 原生准入必要条件，但不能据此判定旧异常的首个根因；数据库断言仍未运行，未重跑或降低校验。

日期：2026-11-11。基线：`170652613e6c7ecca2f3c17b4ea81ab1da8b1912`，分支 `codex/knowledge-version-compare`。这是本对话继研究笔记、流水搜索后的第三个业务模块，回到 Memory／Conversations 核心链路。

## 使用缺口与交付

已有会话能够展开本轮记忆引用，但从一条记忆出发无法找回选择过它的历史会话。现在“我的记忆”加载已存记录后，可以主动查询“记忆使用记录”，跨页查看全部已记录版本，或仅查当前已存版本。结果包含使用中及已归档会话、轮次、终态和引用版本；点击后在原编辑页读取准确轮次的用户消息与助手回复，未保存的记忆修改保持原样。

这只查询 Conversations 的显式选择记录，不覆盖复制进消息的文字参考、独立问答或其他隐含信息。记录不证明模型实际采纳；失败、取消和超时会明确显示，没有助手回复时不补造回答。历史未保存当时的记忆正文，不能将当前正文当作旧版本快照。修订或删除当前记忆不撤回已经保存的会话文字。

## 实现范围

- Java 新增 Native-only 只读 `POST /api/v1/conversations/memory-usage`，输入 `memoryId`、可空正整数 `revision`、`page`。使用既有 `conversation_memory_selections` 联结轮次和会话；绑定参数、排除 PENDING、每页读取至多 11 行并返回 10 行及 hasMore，页码上限 99999。沿既有只读事务执行，不读当前 Memory 正文，不创建新表或索引，不改变备份格式。
- Core 严格检查范围回显、页码、条数、终态、版本及重复轮次。Native 的 `conversations.memoryUsage` 只给当前 session 中实际返回的会话授予读取资格；失效 session 的迟到结果不能授予资格。版本在 WebView 合同中保留十进制字符串，避免 64 位精度损失。
- React 对响应范围再次核对。预览使用原会话 ID 和准确轮次页，重新确认 turnId、sequence、终态、memoryId 与 revision；引用不匹配则拒绝显示。更换记忆／已存版本、离开页面、重连或收起时使进行中的结果失效。不更新记忆、不选择下轮上下文、不发送消息或调用模型。
- 列表是当前历史的分页视图，不承诺并发重命名／新增／删除期间的跨页快照。既有表未新增反向索引，大数据量性能尚未测量。

## 实际验证与输出边界

回执：`artifacts/memory-usage-20261111/`。只执行下列本次直接相关检查，没有重跑研究笔记、流水搜索或旧套件，也没有生成新的 Vite production 资产。

| 检查 | 结果 |
| --- | --- |
| TypeScript | `typescript.log`，通过。 |
| 前端 | `frontend-tests.log`，2/2 通过，1.71 秒。真实 WorkspaceClient 合同配合合成服务，覆盖跨页准确轮次、64 位版本、草稿保留、错配拒绝、离开后迟到响应，不代表真实 WebView。 |
| Native | `dotnet-tests.log` 与 `test-results/memory-usage.trx`，单个新场景通过，103 ms。覆盖返回项授权、未返回项拒绝、版本精度、错误版本、无效 payload 和失效 session。Core／Desktop／Tests 普通 Debug 编译通过。 |
| Java | 正规离线 Maven 编译完成：Finance 54、应用 195、应用测试 66 个源文件。实际只选择一个新的 ConversationStoreTest 方法；它在 `open()` 构造临时 MemoryStore 时以 `MEMORY_STORAGE_UNAVAILABLE` 失败，测试方法断言尚未执行。Maven 退出 1，不能记作 SQLite 查询通过。日志保留于 `maven-test.log`、`maven-exit.json` 和 `source/target/surefire-reports/`。 |

Java 使用原 Maven、JDK 和 `.m2/repository`，正常精确审批；351 个源／资源／POM 输入复制到独立快照并记录 `source-inputs.json`。未尝试修 ACL、换身份、改变临时数据库路径或重跑初始化，错误被原构造器包装，尚不能确定底层原因。没有绕过该失败另做替代数据库验收。后续需先明确这一次初始化失败的原因；SQLite 实际查询与 PostgreSQL 行为／性能均未验收。

C# 采用既有 NuGet 解析、`--no-restore`、独立 `dotnet/` 输出和单场景筛选；MMF3 与 RuntimeLifecycleProbe 均关闭。为避免重复前端打包，构建复用 `finance-transaction-search-20261111/frontend`，所以该普通 Desktop 输出嵌入的是上一模块的前端，**不含本次记忆使用记录界面**。本轮 UI 交付为源码及类型／合成检查；不能把这个 C# 输出当最终整合桌面包。Java 仅 classes，无新 Runtime JAR。

前端和后端检查均在 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun` 互斥内串行运行并 finally 释放。Maven 堆上限 512 MiB，测试 JVM 384 MiB。没有启动产品、模型、Java 服务或个人 Profile。

## 中断、拒绝与交接

一次 `apply_patch` 写界面文件时出现 `exec-server transport closed`。最小只读命令随后成功，核对确认新组件不存在、MemoryPage 无新增入口，才补写这同一批改动；结果已读回，未重复提交未知写入。

只读 `Get-CimInstance Win32_OperatingSystem` 的空闲内存查询被系统拒绝访问，所在命令在任何快照写入前停止。已查询最新用户消息，未得到针对该操作的新证据，故不重试或改用别的查询通道；后续仅执行独立的文件准备与已授权检查。没有自动审批拒绝，也没有更改系统权限。当前可用内存数值未取得；成功取得的共享互斥不代表内存容量证明。

本轮按精确路径本地提交，不推送；main、六份原始文档、既有包及旧失败证据保留。源码可用独立提交的 revert 回滚，无迁移或数据恢复动作。研究笔记 `e14e934`、流水搜索 `1706526` 及本模块三项到此交接，不继续添加外围小项。原同机界面／正常退出控制仍未解决，产品未启动、未打包、未做真实桌面或正常退出验收，`productionGo=false`。
