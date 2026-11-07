# 当前架构

核对日期：2026-10-28。历史源码核验基线 `12dce0765dcdb89b75fd605c2b4fc7bf5db00617`；当前 main 另包含文档现代化与 Closing 提交，最新主线状态见 [STATUS](../STATUS.md)。本次只同步文档发布状态，模块关系与安全合同未变。

**Development-only；Vision BLOCKED；X1 Windows Acceptance PARTIAL；尚未 Production GO。**

[文档索引](../README.md) · [当前状态](../STATUS.md) · [开发指南](../DEVELOPMENT.md) · [ADR](../ADR/README.md)

## 一个应用 Runtime，明确领域所有权

```mermaid
flowchart TD
    REACT["React Main Workspace"] -->|"受限 typed Bridge"| WPF["WPF / WebView2 / 原生确认"]
    QUICK["原生 Quick Assistant"] --> WPF
    BROWSER["Browser Translate-only"] -->|"配对 / 独立凭据"| RT["Java Shared Runtime"]
    WPF -->|"RuntimeClient / Native Bearer"| RT
    RT --> TASK["TaskManager / ActiveModelManager"]
    TASK --> OLLAMA["本机 Ollama"]
    RT --> WS["Memory / Conversations / Knowledge"]
    WS --> STORAGE["SQLite 默认 / 显式 Workspace PostgreSQL"]
    RT --> FIN["Finance adapter / 绑定身份 / 只读服务"]
    FIN --> FDB["独立 Finance PostgreSQL"]
    RT --> WEB["原生确认的 Web Fetch"]
    RT -.-> VISION["Vision 候选 / BLOCKED"]
```

这不是微服务集群，也不存在第二份 Finance AI Runtime。图中数据连线表示受控调用／持久化关系，不表示所有客户端都有同等权限。Browser 只能执行自己的翻译合同，不能访问 Native-only 个人领域。

## 真实目录与构建关系

| 目录 | 责任 |
| --- | --- |
| [根 POM](../../pom.xml) | Maven reactor：`finance-domain`、`workspace-application` |
| [workspace-application](../../workspace-application/pom.xml) | 应用模块；依赖 finance-domain，编译现有根 `src/main/java`，输出根 `target/` |
| [src/main/java](../../src/main/java/io/github/qianlixunbai/workspace) | Runtime API、任务、模型、Memory、Conversation、Knowledge、Web、持久化、Finance adapter 与安全 |
| [finance-domain](../../finance-domain/README.md) | 从冻结来源迁入的金融查询／DTO／Mapper／SQL；Workspace 独立维护，运行不读取原仓库 |
| [desktop/frontend](../../desktop/frontend/package.json) | 单一 React / TypeScript / Vite package，bundled assets 与界面状态 |
| [desktop/src](../../desktop/src) | WPF 宿主与 Core RuntimeClient；WebView2、原生窗口、快捷键、受控 Copy、Profile / Runtime owner |
| [bootstrap](../../bootstrap) | Native Bootstrap、共享 Windows Profile 安全设施与准入证据 |
| [scripts](../../scripts) | 构建、分发和专项维护／验收入口；历史工具不等于默认启动路径 |

## 展示、原生宿主与服务端

**React** 只持有界面草稿、选择和受限会话状态；不持有 Token、数据库密码、可信文件路径或可自行授信的用户 ID。WebView2 只加载固定可信内容，经 [WorkspaceBridge](../../desktop/src/PersonalAiWorkspace.Desktop/Bridge/WorkspaceBridge.cs) 的白名单方法调用原生宿主。

**WPF** 拥有应用生命周期、托盘、Profile 选择、原生文件选择与确认、选区捕获、复制边界和凭据设置。[RuntimeClient](../../desktop/src/PersonalAiWorkspace.Core/RuntimeClient.cs) 构造受限 loopback 请求；日常开发路径由 [DevelopmentRuntimeOwner](../../desktop/src/PersonalAiWorkspace.Desktop/DevelopmentRuntimeOwner.cs) 管理对应子 Runtime 与显式重启。Ollama 是独立本地服务，不归 React 生命周期管理。

**Runtime** 拥有鉴权／客户端权限、领域校验、revision、任务和模型执行合同。Native Bearer 不授予 Browser 同等权限。浏览器扩展属于独立客户端仓库，本仓库保留其配对／撤销和 Translate-only 服务端边界。

## 任务与模型

[TaskManager](../../src/main/java/io/github/qianlixunbai/workspace/task/TaskManager.java) 统一处理有界并发／排队、状态、取消、超时和结果保留；[ActiveModelManager](../../src/main/java/io/github/qianlixunbai/workspace/model/ActiveModelManager.java) 是唯一 Active owner，管理 capability admission、原生 exact-intent 确认、lease、切换和 uncertainty。

配置选择、安装、加载、Ready、已验证能力是不同事实。默认 profile 中的 `qwen3.5:4b` 不是任意环境的授权；[OllamaModelAdmission](../../src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaModelAdmission.java) 继续核验来源与能力。当前 `MODEL_EXECUTION_UNCERTAIN` 保留，不通过删除状态文件或刷新 UI 自动恢复。

## Workspace 数据领域

| 领域 | 数据真相与边界 |
| --- | --- |
| Memory | 用户主动维护的记录；revision 保护；每次使用显式选取，不自动提取或隐式注入；[只读预览](../engineering/MEMORY-READ-PREVIEW-REPORT.md)复用单条读取并核对列表版本，正文仅留在当前界面内存 |
| Conversations | durable 有序轮次与终态；上下文由 Runtime 组装，UI 不重放未确认提交；[按轮次定位](../engineering/CONVERSATION-TURN-NAVIGATION-REPORT.md)复用分页读取；[记忆引用查看](../engineering/CONVERSATION-MEMORY-REFERENCE-PREVIEW-REPORT.md)只读核对当前记录的精确版本，不重建历史正文或自动选择下一轮上下文 |
| Knowledge | 独立文档 metadata、源文件／内容与版本；TXT / Markdown 确定性导入，支持独立备份／恢复 |
| 检索 | [KnowledgeLexicalIndex](../../src/main/java/io/github/qianlixunbai/workspace/knowledge/KnowledgeLexicalIndex.java) 的派生词法索引；不是向量数据库 |
| 知识问答 | [KnowledgeAnswerService](../../src/main/java/io/github/qianlixunbai/workspace/capability/knowledge/KnowledgeAnswerService.java) 接收显式问题／检索输入，固定 evidence snapshot，由 Runtime 形成引用；不让模型授信来源 ID |

默认配置为 SQLite，源码另有显式 Workspace PostgreSQL backend。PostgreSQL 使用独立 Workspace schema / Store 实现；不能把 SQLite 的 FTS5 描述套用于全部 backend。Knowledge 源文件仍有独立文件生命周期，不能把一个数据库 dump 当成完整 Knowledge 备份。

## Finance：共享进程，独立数据与身份

`workspace-application → finance-domain` 是真实模块依赖。Runtime 的 [FinanceModuleConfiguration](../../src/main/java/io/github/qianlixunbai/workspace/finance/FinanceModuleConfiguration.java) 显式装配专用 DataSource、MyBatis 与事务边界；默认 Finance / Flyway 均关闭，普通启动不跑 Finance migrations。

Native 首次绑定采用 CREATE_NEW，服务端每次核对 dataset / ACTIVE 用户指纹与 ownership；不信任客户端提交的可信 ID。Finance 账本查询使用独立 PostgreSQL 与只读事务，不混入 Workspace Memory / Knowledge。

现有链包括账户、普通流水／transfer、资产、持仓／Portfolio、投资历史、导入回执、Dashboard / Analytics。Budgets 返回 UNSUPPORTED；账本写入、导入创建／确认和金融工具未装配。原 Finance 仓库按固定来源只读保留，不是 Runtime 必须同时运行的第二个应用。

[FinanceMarketDataQueries](../../src/main/java/io/github/qianlixunbai/workspace/finance/FinanceMarketDataQueries.java) 属于 Workspace 应用层。Massive 仅在用户主动请求时获取获准用途的日线参考，原生保存 Key；不持久缓存、不离线回放、不用于派生估值。历史 Quote / FX 缓存与人工参考估值有独立来源／新鲜度／许可边界，不能与 Massive 即时显示混为一谈。

## Web 与 Vision

**W1A Fetch**：原生逐次确认 → Runtime 独立目标验证 → HTTPS / DNS pinning / SSRF 限制 → 有界抽取与临时结果。它不是自动联网 Agent；Search / Web Answer 尚不作为已交付产品能力。见 [ADR-014](../ADR/ADR-014-controlled-web-access.md) 与 [W1A closing](../milestones/W1A-CLOSING-REPORT.md)。

**Vision**：已有 React / WPF 选图预览、WIC 规范化与 held-handle 候选链、Native-only API、ActiveModelManager 和 Provider 校验。`OllamaVisionEnvironment.REVIEWED_TOKEN_POLICIES` 仍为空，实际 native payload / 环境／token 合同证据未闭合，因此 **BLOCKED**。模型 metadata、磁盘哈希、preview 或无模型 fixture 均不代表 Vision Ready。见 [最新修复报告](../engineering/V1-VISION-GATE-REMEDIATION-REPORT.md)。

## 本地安全、Profile 与备份

开发 Profile 基于 Windows Known Folder 的 `LocalAppData/PersonalAIWorkspace.Development/Profiles/<id>`；OS 句柄绑定实际目录，当前用户／SYSTEM／Administrators 的既有 ACL 合同与 Native Gate 保留。DPAPI CurrentUser 保护指定配置／Key；不授信同 SID 恶意进程，也不把“私有 ACL”称为数据库加密。

| 备份类型 | 范围与限制 |
| --- | --- |
| Memory logical backup | Memory source records；独立格式，新／空目标重建派生数据 |
| Workspace logical backup | Memory + terminal Conversations；不自动包含 Knowledge、Finance 或凭据 |
| Knowledge backup | 独立文档／源文件合同；不能用 Workspace logical backup 代替 |
| PostgreSQL unified offline backup v1/v2 | 既有合成整库格式（public + workspace），OpenPGP 加密流与独立维护链；不静默转为 v3 |
| Workspace-only v3 内核 | 仅内部合成装配；workspace 的 17 表结构、12 表数据、全部不可变 Knowledge 源文件；5 表排除 data，恢复后重建索引。不是个人 Profile 恢复入口 |

v3 使用 completion version 3 与 `workspace-v3.zip`，固定 `scope=WORKSPACE_KNOWLEDGE_ONLY`、`schemas=[workspace]`、`financeVersions=[]`、`workspaceVersions=[1,2]`。`WorkspaceV3Catalog` 的[冻结预期](../../src/main/resources/backup/workspace-v3-pg17.json)来自新建合成 PG17.11 库中的受审查 V1/V2 DDL 与 Flyway history，包含迁移文件摘要、对象定义、依赖和归档目录；不从待恢复包建立预期。内部 FK trigger 比较语义，忽略物理 TOAST／内部 trigger 的 OID 派生名字。

导出沿用 PostgreSQL 原生 `--schema=workspace` 与逐表 `--exclude-table-data`，额外禁止大对象、注释与安全标签，不先全库导出再过滤 manifest。选择参考 [PG17 官方合同](https://www.postgresql.org/docs/17/app-pgdump.html)及 [PostgreSQL 上游测试](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/bin/pg_dump/t/002_pg_dump.pl)；schema 过滤并不自动解决外部依赖，故另做固定 catalog／依赖检查。JDBC、pg_dump 和 pg_restore 共用确切端口。

Slice 1 的合成入口只接受本进程成功 v3 备份产生的内部能力对象；公开旧恢复入口拒绝 v3，认证成功不授予 SQL 来源权限。新目标核对空对象集合后，在独立事务及 advisory 锁内将合成 preparation receipt 持久改为 `:restore-attempted:<uuid>`，提交完成才创建 candidate／调用 pg_restore；失败或未知目标即使仍空，换目录或能力对象也不能再次执行恢复，须另建目标。该内核返回 `RESTORE_VERIFIED`，保留原库与失败候选，不复制凭据、启动 Runtime 或构造历史 task/job 队列；独立来源消费、Profile READY 和显式激活由下文的原生生命周期另行拥有。聚焦证据与限制见[当前状态](../STATUS.md)。

恢复准备的 [Slice 2a](../engineering/RECOVERY-SLICE-2A-HANDOFF.md) 新增原生内部计划登记：固定应用目录 `Recovery/targets/<id>`，DPAPI CurrentUser、CREATE_NEW、force 和无覆盖发布，仅记录 `RECOVERY_PLAN_ONLY / PREPARING`。计划不是成功备份来源证明，也不产生 SQL／Profile 操作权限。该个人命名空间的目标仍一律不可启动；登记未知不能当作未登记。

后续[合成闭环](../engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md) 在隔离的 `synthetic-sources/synthetic-targets` 命名空间中实现持久来源消费、固定 Profile/data 恢复、新凭据/权限核验和 READY。显式激活需同一维护 owner 的内存能力；先持久授权，再单次 native 发布尝试，最后把启动领取绑定到实际 JVM Process。helper 从 OS 获取调用父进程，核对 PID/创建时间/session，授权记录本身不能普通启动或中断重放。完整 Native/数据库身份和实际 Runtime/Store 就绪后才写 ACTIVE；普通重开仅接受完整 ACTIVE 链。目标源文件从创建时就使用正常 Runtime 要求的私有受保护 DACL，不通过修补旧目标或放宽 ACL 检查达到激活。个人完整停写、受信来源授权及正式 UI/生命周期仍未闭环，恢复入口保持 BLOCKED；合成例外不能进入个人命名空间。

正式开发版选择器现有只读维护窗口，在正常 AssistantApp 创建之前持有 authority/mutex，关闭即结束维护；运行中入口只提示退出。窗口分别显示停写/模型/配置/来源授权缺口，个人操作仍禁用。私有合成确认窗口与 Program 的生命周期移交已接线：同 STA 线程保留原目标 mutex，进入真实 AssistantApp 启动回调后发布 ACTIVE；合成启动不写个人选择。隔离探针运行该 Application/Dispatcher 与实际 Store，但不安装桌面快捷键、托盘或打开 WebView，未完成完整 UI 交互验收。上述个人阻塞不因这段合成生命周期而解除。

默认逻辑备份及源数据可能为明文；统一加密备份不改变其他格式。恢复、迁移与真实数据操作必须遵守各自独立门禁，文档更新不改变权限或持久格式。

绑定来源的合成生产者现使用 `WorkspaceSourceBackup.FrozenSource`：实际维护排他句柄、17 表 SHARE 事务锁、原文文件 pin 持续至角色回收与关闭，只能消费一次并绑定本次实际短期角色 endpoint/原文目录。该能力仅证明这些被锁对象的冻结，不证明旧进程或未知模型执行全部停止；工厂仍受合成命名空间限制，不能签发个人来源授权。原生完成登记继续要求实际消费者/生产者生命周期和独立密文摘要，不接受局部冻结或口令替代完整个人 admission。

## 架构决策与历史

[ADR 索引](../ADR/README.md) 区分决定与当前实施；报告中的 GO 仅在其记录范围内有效。旧版 1,178 行架构正文保存在 [历史快照](../history/2026-10-28/architecture.md)，其中“没有 Maven 子模块”“Finance 只能经未来 Gateway 连接”等是早期事实，不适用于本基线。
