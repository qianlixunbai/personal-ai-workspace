# 受限数据会话：禁止未启用域的启动写入

2026-11-10；承接 `0708f85` 的迁移后受限数据会话及 `1388fbd` 的未知模型 guard 修复。

HTTP 路由关闭并不能阻止 Spring eager bean 构造时写入。原实现会打开独立知识问答历史、把历史中的未完成问答修复为失败、修复待处理对话和知识导入任务，并启动知识索引重建。本模块把这些构造期副作用从受限数据会话中排除。

## 实现范围

- `ConversationConfiguration`：受限会话保留实际 PostgreSQL store 连接，跳过 `reconcilePending`。
- `KnowledgeConfiguration`：受限会话使用现有 metadata-only PostgreSQL store；它仍核对 schema 并持有 advisory lock，但不准备知识文件目录、不执行任务修复。
- `KnowledgeAnswerHistory`：使用无连接的不可用实例，不创建、打开、升级或修复独立的 `data/knowledge-answer-history/history.db`；数据操作返回存储不可用。该 SQLite 域没有因此被迁移到 PostgreSQL。
- `KnowledgeIngestion` 和 `KnowledgeLexicalIndex`：受限实例不启动后台任务、不准备索引文件、不注册重建回调；导入、搜索及重建入口拒绝操作。
- 模式来自已通过原生准入的 `WindowsNativeGate.maintenanceRuntime()`，没有新增调用参数、环境开关或普通 Runtime 绕过路径。普通会话继续使用原构造和修复行为。

本次没有开放新的业务路由。仍只允许存储状态、记忆读取及新增；完整维护仍为 **BLOCKED / productionGo=false**。

## 实际验收

最终证据：`.verification/restricted-data-writers-20261110-r3/`。

全新合成 Profile `ac7a1b068558481ab11ef132307c3bbe`、operation `8d0887cb8d61427f838d548ef5457ad5`。使用原生准备工具和真实普通非管理员宿主所有者、独立 PostgreSQL 17.11、受限应用角色；沿用原生迁移确认和日常重开确认窗口及真实 Runtime owner。

1. 原生迁移前，在独立问答历史中创建合成 QUEUED 行，不提交模型任务；记录全部历史文件名称及 SHA-256。迁移结束后这些文件逐字节相同。
2. 迁移按现有设计生成派生索引。仅在本次合成目标中清空派生索引，另写入后续 PENDING 对话及知识导入任务，记录状态、时间戳、错误字段。
3. 经真实原生确认重开，存储为 POSTGRESQL，模型状态接口返回 MODEL_UNAVAILABLE。历史文件不变、两个 PENDING 状态及时间戳不变、索引表仍为空。
4. 正常关闭并获得该次退出确认后，再核对同样数据和索引；原 SQLite source 的 SHA-256 不变。`startup-result.json` 所有验收字段为 true，`visualAcceptance=false`、`productionGo=false`。

只首次编译五个改动的产品 Java 文件，复用已通过的模型 class；原生验收程序编译零警告、零错误。不重跑已通过的 guard、桥接和前端类型检查，也不重复旧取消/中断场景矩阵。

最终候选 Runtime SHA-256：`416439ABD06347D99A6944961486A3EA3F20D7A338C67E62A01CCEEB1BC6190C`；Desktop DLL：`0C11EABF797E30705BFE753892D4DD0188968056685443D8271BD9B16AE19114`。候选仅供本次隔离验收，不是发布包。

`fixture-result.json`、`native-parent-cleanup.json`、`run-cleanup.json` 确认：新建数据库停止，临时配置/token 删除，forcedOwnedExit=0，Job active 0，Job/共享锁释放，failure=null。沿用 `Local\Dot_KAG_Workspace_HeavyRun`、既有 Job、450 秒 deadline、Java 256 MiB/2 CPU 边界。未调用真实模型、未传输用户数据、未操作已有服务，systemPortQueries=0。

## 失败尝试的准确边界

- 首个构建在五个 Java 文件编译后，被既有 Runtime identity 输出目录校验拒绝；无数据会话启动，Job/锁清理完成。随后依照既有 `target/classes` 约定生成 identity，未弱化校验，复用产品编译结果。
- r2 实际迁移后历史文件检查通过，但测试误把合法迁移生成的索引预期为空而失败（实际 1 个 chunk、8 个 metadata 项）。已完成正常退出及临时数据库/配置/token/Job 清理。修正合成场景为迁移后清空派生索引，再用全新 Profile/数据库验证受限重开；产品实现未因此调整。
- 中间一次补丁工具读取文件时连接中断；只读确认补丁未落盘且 r3 不存在后才继续。没有重复结果不明的操作。

## 下一处已确认写路径

`BrowserClients` 的 eager 构造仍可能清理 `credentials/browser-clients.pending`，即使浏览器路由在受限会话中关闭。这属于独立安全注册域，需要单独的禁用初始化修复和证据，不能将本模块验收扩大到所有写入者。

实际外部模型 owner/结束证明仍未实现；模型状态文件锁、Runtime 连接关闭和 Job 结束的证明范围不同。原 ZIP、真实配置/凭据/数据库、六份用户文档、main 均不在修改范围；不推送、不生成发布 ZIP。