# 跨会话原文查找：从历史消息回到具体轮次

2026-11-09。开发基线 `971a9c0`，分支 `codex/conversation-search`，回滚标签 `workspace-before-conversation-search-20261109`，实现与测试提交 `c964d03`。仅本地提交，未推送、合并、部署或发布；仍为 Development-only。

## 优先级判断与本轮交付

总体路线缺少跨会话查找，用户目前只能逐条打开会话、逐页阅读；已经保存的回答难以再次找到。此前 Knowledge 的长证据、持续提问容量、历史持久化和合成加密恢复已有对应交付，不继续重复建设。

恢复入口经复核仍缺个人来源授权、完整停写证明及目标登记／显式激活链；SQLite 来源＋问答归档也不能直接接到现有 PG 恢复合同。为了接一个按钮而新增这些基础设施，不符合本轮优先交付核心功能的目标。因此保留此前隔离恢复成果及关闭的个人入口，转向**当前工作区跨会话原文查找**。这不代表个人恢复已经解决，也不建议因此扩大真实唯一副本资料的使用。

| 候选 | 本轮判断 |
| --- | --- |
| 正式恢复入口 | 仍需独立的来源与激活合同，不是安全的最小 UI 接线；本轮不继续扩张。 |
| 跨会话历史查找 | 直接改善已有 Conversations 的使用，能复用现有存储、原生权限、分页与轮次定位；本轮完整交付源码和必要验证。 |
| PDF／OCR、向量库、自动记忆或通用 Agent | 会增加解析、模型或服务边界；本轮不并行铺开。 |

在“对话”页左侧展开 **查找历史消息**，输入原文关键词，选择全部／使用中／已归档，再点击查找。结果显示会话标题、角色、轮次及匹配上下文；点击结果打开原会话，滚动并聚焦对应轮次，之后可使用既有的“保存为记忆草稿”流程。查找本身不生成回答、选择记忆或保存记忆。

同一会话内定位保留未发送消息及显式记忆选择；跳到其他会话时，如果存在草稿，先由界面确认清空，取消则保留。切换会话沿用原有本轮记忆选择清理。搜索词和结果只留在当前页面内存，离页或会话重置清除，迟到结果不恢复旧内容或定位。结果来源已删除或读取失败时显示错误，不重建消息或重新提问。

## 范围与实现

- 查找当前 Workspace **已结束轮次中的用户消息和助手回复**，包括归档会话；PENDING 排除。不是标题搜索、语义搜索、模糊搜索或 Knowledge／Memory 全域搜索。
- 按连续原文、区分大小写匹配；最多 100 个 Unicode scalar，空白输入、NUL、孤立代理项和非法范围／页码拒绝。`%`、`_` 和引号均按普通文本处理，不作为通配符或 SQL。
- 每页 10 条，最多读取第 11 条判断 hasMore；按会话更新时间倒序、ID、轮次及角色稳定排序。只返回第一处匹配附近最多各 60 个 Unicode scalar，上下文最多 220 个 scalar；UTF-16 偏移与原文一致，不切断 emoji。正文用 React 文本节点和 mark 显示，HTML 不执行。
- `POST /api/v1/conversations/search` 是只读操作，请求 `{query, scope: ALL|ACTIVE|ARCHIVED, page}`，固定 limit=10。搜索词放在 body，避免进入 URL 查询字符串；结果为 `{query, scope, items, hasMore, page, limit}`。page 范围 0–199999 对应既有最多 1000 会话 × 1000 轮 × 两条消息的容量。
- `ConversationStore` 在既有只读事务内参数化查询原表，SQLite 使用 instr，PostgreSQL 分支使用 strpos；不增加 schema、索引、持久副本或备份格式。SQL 内分页限制返回量，**不承诺扫描工作量恒定或最大容量响应时间**。页间反映当前数据，未建立跨页冻结快照。
- HTTP 沿用 native-only，浏览器客户端拒绝。Core 与前端分别验证结构、范围、Unicode、引用身份、匹配偏移及 query／scope／page 一致性；Bridge 只授权当前 session 中实际返回的会话 ID，迟到 session 不授予新授权。搜索不授予取消任务权限，不增加模型能力。
- 打开命中复用 `getConversation`、原有串行分页和轮次焦点；跨会话先发现原有最新持久状态，再读取命中页。整个功能不缓存全部会话正文，不调用模型或重放任务。

## 成熟项目参考与复用选择

2026-11-09 通过 GitHub 官方仓库 API 及项目官方文档核对；Star 数仅为本次观察值，不作为安全或质量证明。

| 项目 | 核对结果与采纳 |
| --- | --- |
| [Open WebUI](https://github.com/open-webui/open-webui) | API 观察 158,160 stars，最近 pushed_at 为 2026-11-08。查看 [chats.py](https://github.com/open-webui/open-webui/blob/main/backend/open_webui/models/chats.py) 的消息摘要和 SQL 分页搜索，参考“结果有界、从原记录读取”的做法。[当前许可](https://github.com/open-webui/open-webui/blob/main/LICENSE)为自定义 Open WebUI License，包含品牌条款；不将其泛称 MIT，也不复制其实现。 |
| [LibreChat](https://github.com/LibreChat-AI/LibreChat) | 官方 API 新仓库位置为 LibreChat-AI/LibreChat，观察 46,872 stars，最近 pushed_at 为 2026-11-08，[许可 MIT](https://github.com/LibreChat-AI/LibreChat/blob/main/LICENSE)。参考[跨会话消息搜索交互](https://www.librechat.ai/docs/features/search)：按会话展示消息并打开原对话。其搜索依赖 Meilisearch；本地已有单用户 SQLite／PG，额外服务、索引副本和维护成本不适合本次窄范围增量。 |

直接复用的是本项目成熟的 Store 事务／Native Bridge／RuntimeClient／轮次分页／确认对话框及文本渲染，没有安装新依赖、引入外部搜索服务或复制第三方代码。Open WebUI 当前 SQLite／PG 查询和 LibreChat 的搜索能力只作设计参考，未据此宣称本项目有纠错、语义或大小写折叠能力。旧 LibreChat API 地址曾返回重定向、旧 raw 路径未返回内容，最终以实际新仓库和官方文档为依据。

## 必要验证与实际结果

证据目录 `.verification/conversation-search-20261109/`。所有数据为新建合成内容，未对个人资料执行查找或备份恢复。无模型、GPU、KAG 端点或共享服务需求；不申请共享模型窗口，不启停 KAG／既有 Ollama／PostgreSQL。原生 Java 验收限制 384 MiB／2 CPU，使用既有宿主及全新 Profile。

| 证据 | 实际结果 |
| --- | --- |
| `java-build-1.log` | 离线 Maven package、测试编译及既有 Runtime JAR 审计成功。构建阶段 skipTests，不算作动态测试；保留既有内部 API 等编译提示。 |
| `run-1/integration.log` | 原生宿主 3/3，0 failed／aborted／skipped：新增 Store 场景、新增真实 HTTP／权限场景及原有会话生命周期回归。 |
| Store 场景 | 26 条活动消息跨三页不重复，归档的取消轮次可查，PENDING 排除；中文／emoji／HTML 字面文本、通配符／引号、非法输入、大小写、前后摘要边界通过。查找不改变原记录，关闭重开结果一致，删除会话后命中消失。 |
| HTTP 场景 | 原生身份取得真实 SQLite 命中及精确偏移；无凭据 401，已配对浏览器 403，非法 scope／page 400；模型未就绪且 chat／probe 调用 0，私有搜索词与标题未进入 Runtime 日志。 |
| `dotnet-test-3.log`、`test-results/conversation-search-3.trx` | 会话所属测试 31/31，0 failed／skipped；含新增结果授权、未返回 ID 拒绝、非法载荷、错误高亮响应及旧 session 不授予权限。没有发出模型提交或取消。 |
| `frontend-3.log` | 现有会话页面 16/16；含新增查找摘要文本安全、准确打开第 11 轮、同会话草稿保留、跨会话确认／取消、归档阅读、响应不匹配拒绝和离页迟到结果清理。 |
| `frontend-build-3.log`、`desktop-final-build.log` | TypeScript／Vite／资产清单及最终 Desktop 编译成功；保留既有主 JS chunk 超过 500 kB 的提示，不顺带扩展拆包。 |

首次前端构建因测试使用 findLastIndex 超出现有 ES lib 目标失败，改用现有兼容方法后通过，未修改编译目标。首次 .NET 测试构建未带既有 RuntimeLifecycleProbe 条件，导致历史探针辅助类型缺失；补齐原有构建组合后进入测试。首次执行为 30 通过／1 失败，新测试错误期待非法 Bridge 消息返回错误对象；原产品合同是直接丢弃。修正测试为无响应且无 HTTP 后，最终 31/31。未为了通过测试修改拒绝规则，失败日志和首次 TRX 均保留。

原生实测 Profile `4297031fb8174993a00f906bb37b521b`，JVM PID `24900`，HTTP `63923`；childExited=true、exitCode=0，宿主退出 0。收尾只读核对自有 JVM 和端口已退出。六份原有未跟踪文档逐项 SHA-256 不变，未暂存、修改或删除。

候选 Runtime JAR SHA-256：`02278AAE39C00AB6B8BEB15D2236F45A540F6A54B20ED663F8C8C245942A163D`。原生验证使用本轮 classes／test-classes；未部署 JAR。功能需使用匹配的 Runtime 与 Desktop，旧 Runtime 不提供新增查询路由。

## 复现与限制

前端运行 `npm run test -- src/pages/ConversationsPage.test.tsx` 与 `npm run build`。桌面沿用现有测试工程、`Mmf3ProfileAcceptanceBuild=true`、`RuntimeLifecycleProbe=true`、`FrontendSkipBuild=true`，按 WorkspaceConversationsTests 过滤；此处运行 xUnit，不执行探针 Main。Java 离线编译后，使用既有 MemoryRecallIntegrationProbe 原生宿主及专用 classpath，选择报告列出的三项 Store／API 方法；本次短适配器和命令保存在忽略证据目录，不另建产品测试框架。

本轮没有完整 AssistantApp／WPF／WebView2 的实际 UI 点击、人工 IME、PostgreSQL 实例或最大容量性能验收；前端证据是 JSDOM＋真实 Bridge 客户端，桌面证据是原生 C# Bridge／Core 配合合成 HTTP，Java 证据才是真实 HTTP／SQLite。不能把分层通过改写为整包真实桌面 PASS。当前只提供精确原文查找，扫描性能随数据量增加，后续需要实际使用证据再决定是否增加全文索引。

个人恢复、正式分发及全产品 Production GO 不因本功能解除。后续优先围绕实际使用反馈或同包产品验收收口，不自动开启更多恢复框架。回退使用本轮提交或基线标签，不覆盖用户文件、运行数据或此前恢复成果。

2026-11-09 后续验证：[隔离真实桌面与 PostgreSQL 报告](CONVERSATION-SEARCH-DESKTOP-REPORT.md)补齐实际 WPF／WebView／Bridge／HTTP／SQLite 的定位、归档筛选、草稿保护及窗口重开，并通过专用 PostgreSQL Store 查询综合场景；两项各 1/1，无模型调用或个人资料操作。上文保留原实现阶段的证据边界，完整 AssistantApp、PG 桌面组合及最大容量仍未验收。
