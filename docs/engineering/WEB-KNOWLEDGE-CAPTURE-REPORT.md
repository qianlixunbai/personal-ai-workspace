# 网页摘录进入 Knowledge：显式保存与本地复用

> 同日后续：[真实网页与桌面验证](WEB-KNOWLEDGE-LIVE-ACCEPTANCE.md)已通过两个公开样本的实际 WPF／WebView／原生确认／生产 Fetch／SQLite 获取、保存、检索与窗口重开。下文“真实网站／实际 WPF／WebView 尚未验证”保留本功能首次提交时点；真实模型、完整应用启动、正式包与 PostgreSQL 的未验收边界继续保留。

2026-11-09。基线 `fda9562fcd83f346a100d8b079695c8c13d84333`，分支 `codex/web-knowledge-capture`。本轮按“完整核心闭环”推进资料获取与使用，仅本地提交；仍为 Development-only。

## 交付的用户流程

此前 W1A 已能逐次确认并获取公共网页，但结果是短期内存预览，不能直接进入已有知识库。现在 Knowledge 页面提供“从网页添加资料”：

1. 用户输入公共 HTTPS 网址，在现有 WPF 原生窗口确认本次访问。
2. 预览有界纯文本，核对请求／最终网址、获取时间及标题／正文截断情况。
3. 用户再次明确选择保存；Runtime 把已完成获取的文本和来源说明制成 UTF-8 TXT，交给现有 `KnowledgeIngestion`。
4. 保存完成后可打开资料，在现有 Knowledge 中检索、显式提问和打开准确版本的引用。源文件可以重开读取，并参与现有独立 Knowledge 备份／恢复。

保存不会再次访问网址。单纯获取、预览或取消保存不会写入 Knowledge。网页保存没有调用模型；只有后续用户明确发起的知识问答进入已有模型路径。不是网页搜索、自动工具调用、同步更新或完整 HTML 归档，也不改变 Memory／Conversation／Finance 的领域边界。

## 实现与权限

- 新增 Native-only `POST /api/v1/web/fetches/{id}/knowledge`，正文严格仅有 `requestId`，重复／额外 JSON 字段拒绝；Browser 在读取请求体之前拒绝。源文本、网址、时间和截断标记全部取自 Runtime 的成功结果，不接受客户端提交这些内容。
- WPF 只允许当前 Bridge 会话已拥有、已核对成功结果的 operation ID。原生端为每个保存动作分配一个稳定 request ID，React 只能传 operation ID；并发保存拒绝，已尝试的保存以后只能查询同一请求，不会自动重发 POST。超时／回应丢失先只读对账，未知保持未知；会话轮换后的迟到结果不能给新会话授信。
- `web.saveKnowledge`／`web.knowledgeState` 复用既有严格 Knowledge job 回应核验、文档授权和导入状态接口。没有新增文件路径／任意上传字节的 Bridge 权限。前端保存确认默认聚焦取消，支持中文输入法；离页／换会话清除临时预览，迟到结果不回填。
- 快照头写入请求及最终 URL、UTC 获取时间、标题、两个截断标记、提取版本和内容类型，正文标明“网页文本摘录（非完整网页归档）”。全部作为不可信参考文本保存，沿用版本、索引、引用与备份合同；没有新 schema 或专用网页数据库。
- HTML 提取与预览只输出／渲染纯文本，不执行网页脚本、链接或嵌入资源。现有目标验证、DNS pinning、TLS、重定向与 SSRF 约束保留。普通 Ask、Browser Translate-only、模型工具拒绝和服务设置不变。

## 成熟项目复用与安全适配

本轮直接复用仓库已使用的 [jsoup](https://github.com/jhy/jsoup) 及其既有 `WebContentExtractor`，没有自建 HTML 解析器或引入网页抓取服务。2026-11-09 GitHub 查询快照：11,413 Star、未归档，最后 push 为 2026-11-04 23:23:50 UTC。固定安装版本为 **1.23.2**；读取该版本提交 `fbc7775c462f5b023d8db54c9a73d7ec17d53300` 的 [MIT 许可证](https://github.com/jhy/jsoup/blob/fbc7775c462f5b023d8db54c9a73d7ec17d53300/LICENSE)、[安全政策](https://github.com/jhy/jsoup/blob/fbc7775c462f5b023d8db54c9a73d7ec17d53300/SECURITY.md)，并核对发布信息。未复制上游源码、增加依赖或改变许可范围。

安全政策建议使用最新版且不承诺旧版回补。查询时最新版是 [1.24.1](https://github.com/jhy/jsoup/releases/tag/jsoup-1.24.1)，与安装版不同；其解析／选择器行为变化需要单独评估，本增量未升级。核对官方 [GHSA-pmhh-3w7g-xqp8](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8)：自定义 raw-text Safelist 清洗问题影响 1.14.3–1.22.2，1.23.1 修复；现有 1.23.2 已越过该修复版本。本路径使用有界 HTML 解析和文本提取，不使用该 Cleaner／自定义 Safelist 场景。这是已查看公告与具体调用面的评估，**不是完整漏洞或供应链审计**，Star 也不构成安全认证。

适配复用已有 `Parser.htmlParser().setMaxDepth(128)`、输入上限、主动元素移除和输出截断。jsoup 不执行网络请求，不加载子资源；网络仍归已有安全 Fetch。保留最大 4,096 UTF-16 单位／8,192 UTF-8 字节正文及 160 个 Unicode 标量标题，因此只把已有有界结果转换成 TXT，是足够且可核对的领域接线。导入、存储、检索、问答和备份均复用原实现，没有另造平台。

## 必要检查及证据

本地证据在 `.verification/web-knowledge-20261109/`。新增 5 个综合测试场景，复用既有测试设施，未跑全量矩阵。

| 检查 | 结果与实际覆盖 |
| --- | --- |
| Java 编译，`java-compile.log` | Maven 离线 `-pl workspace-application -am -DskipTests test-compile` 成功，包含新增生产类与测试。 |
| 原生 Java 集成，`run-3/integration.log` | **2/2 通过，0 失败／中止／跳过**。新 `WebKnowledgeCaptureTest` 覆盖真实 jsoup／SQLite Store／索引、中文及 emoji、截断与来源保存、幂等、空文本／过期拒绝、关闭重开、搜索和独立备份恢复；网络返回为合成 HTML，没有访问外网。扩展既有 `RuntimeApiTest` 覆盖真实 Spring HTTP、Browser 请求体前拒绝、严格字段、保存和知识问答引用精确版本／原文；模型提供器为本地合成 fixture。 |
| .NET，`dotnet-test.log`、`results/web-knowledge.trx` | **4/4 通过**（新增 2 项，保留 2 项既有 Web 核心场景）。覆盖真实 Bridge／RuntimeClient 与假 HTTP handler：未授权／未观察成功结果拒绝、单次保存、未知只读对账、会话失效、迟到结果及原有精确网址原生批准合同。Core／Desktop 编译成功。 |
| 前端，`frontend-test.log` | **11/11 通过**，3 个文件（新增 2 项），使用真实 WorkspaceClient 与假 Bridge。覆盖获取、纯文本预览、输入法、默认取消、保存、打开资料、改输入不改原操作绑定、未知结果不重发、离页迟到结果和既有引用／阅读器交互。 |
| 构建，`frontend-build.log` | TypeScript、Vite 和前端资产清单生成通过。保留现有 bundle 超过 500 kB 的提示；本次生成 JS 560.91 kB，不把提示当作发布验收。 |
| 退出 | `run-3/host-result.json`、`child-result.json` 均记录退出码 0；Java 自有 PID 11244 与随机 HTTP 端口 52074 已确认不再运行／监听。 |

首次指定的常规 PowerShell 7 安装路径不存在，未启动测试。随后直接 `Shell.Application` 启动的 `run-1`／`run-2` 均在 `PREPARE / WindowsProfileSecurity.OpenDirectory` 被既有目录身份门禁拒绝，尚未进入 Java；失败证据保留，没有复用失败 Profile。查阅既有 [Memory Recall 宿主说明](MEMORY-RECALL-REPORT.md)及微软[实际 Explorer 启动方式](https://devblogs.microsoft.com/oldnewthing/20131118-00/?p=2643/)，改为 `ShellWindows.FindWindowSW` 返回的实际桌面 `Document.Application` 隐藏启动 `run-3`；包装器验证同一用户、非管理员，并生成全新合成 Profile 后成功。未修改执行策略、ACL 校验、Bootstrap hash、Java Native Gate 或任何既有服务。

## 尚未验证及保留限制

- 未运行真实公共网站抓取、真实模型回答质量、实际 WPF／WebView 点击链、完整桌面分发包或 PostgreSQL 保存／恢复。本次通过的是分层测试加真实 SQLite／HTTP 综合场景，不能称为完整应用端到端验收或 Production GO。
- 继续保留 W1A 临时结果最多 16 项／120 秒以及桌面会话最多 16 个获取操作的既有限制；本次未做授权回收。结果到期后不能保存，需用户重新确认获取。保存后的资料独立持久化，不依赖该临时结果。
- 重复获取同一 URL 再明确保存会形成新的 Knowledge 文档；没有 URL 自动去重、文档刷新或旧版本更新策略。存下来的只是本次有界文本，网页后续变化不会自动反映。
- 保存状态 READY 后索引仍沿用异步派生的既有生命周期；真实问答须模型已通过现有准入。未知保存不自动补发，必要时用户从文档列表核对结果。
- Knowledge 独立备份／恢复通过不等于个人 Profile 恢复开放。W1 Search／Web Answer、个人恢复、Vision 和正式打包的原有关卡继续保留；Workspace 与 KAG 严格分开。

## 文件保护与本地回滚

开工核对只有一个工作树，tracked 文件干净；前面三项 Knowledge 增量均已提交，未覆盖并行工作。原有 6 份未跟踪用户文档逐一备份至证据目录 `originals/`，哈希在 `preserved-documents.json`，收尾再次逐项比较；不暂存它们。未修改真实个人资料、现有服务或安全设置，未推送、合并或部署。

功能前回滚标签 `workspace-before-web-knowledge-20261109` 指向 `fda9562fcd83f346a100d8b079695c8c13d84333`；当前增量在独立分支本地提交。证据目录保留 tracked 补丁、新文件副本及最终哈希。需要撤销时优先对本次提交执行单独 revert，保留用户文件；不要使用 `git clean` 或强制 reset。
