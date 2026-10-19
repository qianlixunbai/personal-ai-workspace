# PERSONAL AI WORKSPACE GITHUB OPEN SOURCE EVALUATION

2026-10-19（Asia/Shanghai）。**研究覆盖用户给定 11 个仓库及 4 个额外候选；本轮直接集成 1 个小组件。**

## 结论与证据范围

优先完成原生配置产品路径，再补上 React 根部的渲染故障恢复。没有理由让第三方框架接管现有 Gate、进程所有权、
数据库、Memory/Conversation 真相或 Finance 事实。A 类仅 `react-error-boundary`；其余以设计借鉴或未来候选为主。
以下分类与优先级是基于当前源码的工程判断，不是上游性能/安全保证；未以 Stars 排名。

实际读取官方 GitHub 仓库页、release/tag、官方文档与部分关键源码；采用 Firecrawl 搜索/抓取，fresh capture 使用 maxAge 0。
公共 GitHub REST 元数据返回 **403 rate limit exceeded**，因此未取得全量 issue/PR 统计或完整维护响应时长。
以下维护证据为可见提交、发布及关联 PR 抽样，**不把页面抓取成功当成发布日期，也不把版本标签当成本机兼容验证**。
仓库不同 SDK 的版本分别注明，预发布不作为稳定版推荐。日期未完整显示的保留缺口，不根据相对时间补造年份。

## 候选、许可证和维护快照

| 候选 / 能力 | 许可证 | 本轮可见版本与维护证据 | 类别 / 优先级 |
| --- | --- | --- | --- |
| [react-error-boundary](https://github.com/bvaughn/react-error-boundary)：React 渲染故障恢复 | MIT，已读实际安装包 LICENSE，随包分发 | npm stable **6.1.6**，2026-09-20；仓库 10-19 有提交、[#251](https://github.com/bvaughn/react-error-boundary/pull/251) 近期维护。版本以 [npm registry](https://registry.npmjs.org/react-error-boundary/6.1.6) 与锁文件为准 | **A / 当前已集成** |
| [CliWrap](https://github.com/Tyrrrz/CliWrap)：子进程、pipes、取消 | MIT | [3.10.5](https://github.com/Tyrrrz/CliWrap/releases/tag/3.10.5)，2026-08-19；可见 10-09 提交及 [#350](https://github.com/Tyrrrz/CliWrap/pull/350) / [#343](https://github.com/Tyrrrz/CliWrap/pull/343) | **B / 当前借鉴** |
| [Ollama](https://github.com/ollama/ollama)：本机模型/API | MIT；模型权重各自许可另查 | [v0.40.6](https://github.com/ollama/ollama/releases/tag/v0.40.6)，2026-10-16；当前 Workspace 已有 Ollama Provider | **B / 保留既有集成** |
| [mem0](https://github.com/mem0ai/mem0)：推断记忆、检索与历史 | Apache-2.0 | [ts-v3.3.1](https://github.com/mem0ai/mem0/releases/tag/ts-v3.3.1)，2026-09-25；README 可见 Python 2.2.1，非同一 SDK；10-14 有提交，[#7350](https://github.com/mem0ai/mem0/pull/7350) | **B / 仅审计与溯源设计** |
| [Testcontainers Java](https://github.com/testcontainers/testcontainers-java)：隔离数据库测试 | MIT | [2.0.5](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.5)，2026-04-20；release 可见 [#11712](https://github.com/testcontainers/testcontainers-java/pull/11712) / [#11574](https://github.com/testcontainers/testcontainers-java/pull/11574) | **C / 有稳定容器环境后** |
| [Spring AI](https://github.com/spring-projects/spring-ai)：Java Provider / Tools / RAG | Apache-2.0 | [v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)，08-21；根页可见 2026-10-16 提交，[#6776](https://github.com/spring-projects/spring-ai/pull/6776) / [#6772](https://github.com/spring-projects/spring-ai/pull/6772) | **C / 多 Provider 或 Tools 独立需求时优先评估** |
| [LangChain4j](https://github.com/langchain4j/langchain4j)：Java AI 编排 / 结构化输出 | Apache-2.0 | [1.22.0 / 1.22.0-beta32](https://github.com/langchain4j/langchain4j/releases/latest)，10-08；稳定与 beta 模块分别识别，[#6611](https://github.com/langchain4j/langchain4j/pull/6611) | **C / Spring AI 的备选，二选一** |
| [Windows App SDK](https://github.com/microsoft/WindowsAppSDK)：桌面 lifecycle / 现代 API | MIT | [v2.5.1](https://github.com/microsoft/WindowsAppSDK/releases/tag/v2.5.1)，09-16；页面另显示 2026-09-12 的签名提交日期，两者不混用；10-16 根页提交 | **C / 可信发布设计阶段** |
| [Velopack](https://github.com/velopack/velopack)：安装与更新 | MIT（开源项目）；托管服务另有条款 | [1.2.161](https://github.com/velopack/velopack/releases/tag/1.2.161)，09-29，页面年份未独立确认；[#1072](https://github.com/velopack/velopack/pull/1072) / [#1067](https://github.com/velopack/velopack/pull/1067) | **C / 与签名和来源合同一起评估** |
| [WPF UI](https://github.com/lepoco/wpfui)：WPF Fluent 组件 | MIT；字体/图标分发另查 | [4.3.0](https://github.com/lepoco/wpfui/releases/tag/4.3.0)，2026-05-04；根页最后可见提交 06-27；未取得当前 issue 响应速度 | **C / 原生窗口确有扩展需求时** |
| [Apache Tika](https://github.com/apache/tika)：多格式文档解析 | Apache-2.0；解析器传递依赖逐项核对 | [tags](https://github.com/apache/tika/tags) 可见 4.1.0（2026-09-29）、3.3.3-rc1（10-09）；RC 非稳定版；[#3359](https://github.com/apache/tika/pull/3359) | **C / 明确 PDF/Office 导入需求后** |
| [Qdrant](https://github.com/qdrant/qdrant)：向量/混合检索 | Apache-2.0；Edge 等分发分别核对 | 根页可见 1.19.2、2026-10-05 提交及 [#10946](https://github.com/qdrant/qdrant/pull/10946)；未将该提交断言为最新 stable release | **C / 先明确 embedding 与增量索引合同** |
| [GraphRAG](https://github.com/microsoft/graphrag)：图索引与跨文档归纳 | MIT | [v3.3.0](https://github.com/microsoft/graphrag/releases/tag/v3.3.0)，2026-10-08；release/提交关联 [#2592](https://github.com/microsoft/graphrag/pull/2592) | **C / 大规模关联探索需求后** |
| [Semantic Kernel](https://github.com/microsoft/semantic-kernel)：Agent / Tools / Provider | MIT | [dotnet-1.81.0](https://github.com/microsoft/semantic-kernel/releases/tag/dotnet-1.81.0)，2026-10-06；该版本是 .NET，不冒充 Java SDK；[#14553](https://github.com/microsoft/semantic-kernel/pull/14553) | **D / 当前不引入** |
| [Spectre.Console](https://github.com/spectreconsole/spectre.console)：终端 UI | MIT | [releases](https://github.com/spectreconsole/spectre.console/releases) 可见 0.57.2 等标为 prerelease；稳定版未确认；10-05 有提交及 [#2158](https://github.com/spectreconsole/spectre.console/pull/2158) | **D / 当前不引入** |

除 A 类实际安装的精确版本外，许可证列是本轮官方仓库声明；没有宣称已审计每个候选所有传递依赖。
MIT 分发须保留版权及许可；Apache-2.0 须保留许可/适用 NOTICE、遵守修改与专利相关条款。未复制候选源码进业务实现。

## 与当前实现的适配比较

| 候选 | 兼容、离线与 Windows 适配 | 当前收益、侵入程度与成本判断 |
| --- | --- | --- |
| react-error-boundary | 已安装包 peer React/类型 `^18 || ^19`；ESM，适配现有 Vite/React 19.3；运行无网络、无新增 native 依赖 | 根部原来只有 App，没有渲染错误边界。新增一个 wrapper 解决白屏恢复，未改变 Bridge 或数据所有权；成本小 |
| CliWrap | 上游声明 .NET Standard 2.0+ / Core 3+ / Framework 4.6.2+；可离线、支持 Windows；未本轮安装或编译验证 | 现有 ProcessStartInfo.ArgumentList、Job、直接 child、固定诊断与哈希 pins 更贴合边界。替换不能省去 Gate/Job/pins；只借鉴取消后等待真实退出和并发处理 pipes |
| Ollama | 官方本机 Windows 路径；已下载模型可离线。当前代码强制 local 模型身份、禁止远程字段并持有任务取消/模型准入 | 当前 Provider、active-model 管理和 cancellation 已集成；不能用“升级最新版”替代精确 source/model trust 验收。本轮未升级 exe/权重或调用推理 |
| mem0 | Python/TS；本机部署可选，但自动记忆涉及 LLM、embedding、向量存储与 SQLite 历史；上游有 telemetry 调用 | 当前 Memory 手动、exact revision、逐次授权；直接接入会新增持久化真相和自动提取边界。仅借鉴变更历史/溯源思想；不启用遥测、不引入服务 |
| Testcontainers | Java/JUnit；需要 Docker-compatible runtime。Windows 通常需容器环境/镜像预取；预取后可离线，不能消除 Native Gate 冲突 | 未来可替代手工创建的合成 PostgreSQL 基础设施；不能替代真实 Windows ACL/DPAPI 验收。当前不安装 Docker、不拉镜像、不改既有库或角色；只在明确独立容器测试任务中考虑 |
| Spring AI | [官方 getting-started](https://docs.spring.io/spring-ai/reference/getting-started.html) 明确 2.0.x 支持 Boot 4.0.x / 4.1.x；Ollama 本地选项，Java 栈适配；本轮未编译集成 | 与现有 Profile/Policy/TaskManager、durable Conversation、context assembly 重复。未来只在现有 Provider 后接薄适配，不让框架接管 Memory 或 Finance；依赖/调试成本中等 |
| LangChain4j | 仓库 Java 17 source compatibility，CI 包含 Java 21；Ollama 模块可本机运行，但不同模块 stable/beta 需分别确认 | 工具/结构化输出有价值；与 Spring AI 和当前 Runtime 重复。若未来选择，按功能模块接入，避免同时引入两个编排框架；维护成本中高 |
| Windows App SDK | 上游支持 WPF/Win32，Win10 1809+；部署方式影响 runtime/bootstrap 依赖，离线分发需预置 | 不能给 unsigned build 提供来源认证，也不能解决私有 ACL 外部改写。当前引入扩大部署合同；发布签名/安装设计完成后再验证 .NET10 与具体 API |
| Velopack | 语言无关，支持本机或远程 update source；普通运行可离线，更新需要来源可达/本地介质 | 更新会改变原 pins 与应用二进制，必须同时设计签名、来源、安装后权限与回退。此轮仅候选，不自动下载/安装/更新或解锁 launcher |
| WPF UI | WPF/C#，适合原生窗口；旧 Windows 的字体/图标需安排；本轮未验证 .NET10 包变体 | 主界面是现有 React，原生设置窗口规模小。现在换主题/组件库扩大视觉与依赖范围；未明显优于当前原生控件 |
| Tika | Java；上游当前构建声明至少 Java17，但 4.1.0 具体依赖/Windows 解析器组合未本轮构建验证；核心解析可离线 | 当前 Knowledge 只接受严格 UTF-8 TXT/Markdown，解析/定位/引用合同清晰。引入 PDF/Office 增加复杂解析器、资源与攻击面；需限制类型、大小、时间及隔离后再做 |
| Qdrant | Rust 服务或特定 Edge 形态；Docker 示例不能直接当成 Windows 原生生产方案；需离线部署和模型资源 | 当前 SQLite FTS5 / PostgreSQL lexical index 是可重建派生索引，已有 revision 指纹与引用证据。还没有 embedding 生命周期，直接加向量服务会增加索引一致性、备份和常驻资源成本 |
| GraphRAG | Python 管线，embedding/LLM 的本机配置需单独核实，不能默认离线 | 官方明确索引可能昂贵；当前 K3 引用与 lexical 检索范围不需要图构建。额外 CPU/RAM、模型调用与增量一致性成本高；未进行性能实验 |
| Semantic Kernel | .NET/Python/Java 多 SDK，版本独立；本地 Provider 可选，但具体 Java SDK 与本项目组合未验证 | AI/Tools 若进入 WPF 会越过 Java Runtime owner；若放 Java 则与现有 Runtime/Spring AI 候选重叠。当前收益不足，不引入第二编排层 |
| Spectre.Console | .NET 终端库，可离线；与 WPF/React 产品交互无直接关系 | 新增更漂亮的 console 仍不能消除 receipt/Compile，反而维护另一套交互；日常产品不采用，必要维护工具将来也无需此时加依赖 |

后端可靠性继续使用现有 Spring Boot Actuator、TaskManager、固定错误码、只读数据库检查及取消合同。
没有为框架流行度加入通用重试、调度或日志设施；含副作用的业务操作不应因 Provider 包装层而被自动重放。
数据/备份维持 PostgreSQL/Flyway、原 B3F OpenPGP 管线与恢复验证边界；未改变备份格式或引入另一存储真相。

## 实际源码对照与本轮集成

读取 [CliWrap Command.Execution.cs](https://github.com/Tyrrrz/CliWrap/blob/master/CliWrap/Command.Execution.cs)：
其并发 pipes、取消终止和等待进程退出的设计支持此轮 native 配置进程的清理方式；没有照搬其封装或增加库依赖。
现有 `RuntimeProcessJob` 的 KILL_ON_JOB_CLOSE 和真实 direct child 仍是 Workspace 所需边界。

读取 [mem0 memory/main.py](https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py)：
自动 add 涉及 LLM/embedding/向量去重；显式 update/delete/history 维护历史，且可见 capture_event 调用。
这些职责不能直接替换当前手动 Memory；本轮只分析，不调用或复制。
读取 Spring AI 官方兼容文档、仓库与 release，确认 Boot4.1 已有对应候选，避免依赖旧版本印象作判断。

唯一直接依赖为 **react-error-boundary 6.1.6**，固定版本与 registry integrity 写入 `package-lock.json`，
安装使用 `--ignore-scripts --no-audit --no-fund`，未执行第三方安装脚本；检查实际 LICENSE、package.json 与发布 ESM 源码。
该包没有新增运行时传递依赖，也没有网络、文件、进程或凭据权限。
本轮 `npm audit --omit=dev --audit-level=high` 实际 PASS，生产依赖报告已知漏洞为 0，
证据 `.verification/desktop-simplification-production-audit.json`；这不是完整供应链或所有候选的安全审计。

具体位置：

- `desktop/frontend/src/app/WorkspaceErrorBoundary.tsx`：固定无异常原文 fallback，主动重新打开，不自动 reset/resend。
- `desktop/frontend/src/main.tsx`：包裹 App；通过 React root `onCaughtError` 避免私密异常原文被默认 console 输出。
- `desktop/frontend/THIRD-PARTY-NOTICES.txt` 和 Desktop csproj：MIT 许可单独随 Desktop 分发，不修改 WebView 资产白名单。
- `WorkspaceErrorBoundary.test.tsx`：正常渲染后注入合成私密异常，验证固定恢复提示、按钮及正文不回显异常内容。该用例已实际 PASS。

错误边界仅处理 React 捕获的渲染故障；不声称覆盖所有 async/event/native 故障。
用户重新打开会清除草稿并触发原 WebView session 生命周期；durable Conversation 仍由 Runtime 重读，不自动重试原任务。
该 session 行为沿既有实现保留，本轮没有在真实 WebView2 中重新验收。

前后 production bundle（均含 Phase A）：JS **340.44 → 342.29 kB**，gzip **102.22 → 102.86 kB**。
这是本次构建输出差异，包含 wrapper；不是独立库基准，也不是运行时 CPU/RAM 或吞吐性能结论。
前端最终构建与 Profile Desktop 编译 PASS；真实 WPF/WebView2 故障恢复 E2E 仍 PENDING。

## 后续 Roadmap

1. **完成一次真实开发配置综合流**：在符合原 ACL 合同的独立普通用户环境，通过原生入口验证 SQLite、配置检查、DPAPI、显式重启及进程失效；schema 写入另行授权，随后验证合成 PostgreSQL 的 Memory/Conversation/Knowledge。
2. **可信分发与恢复边界**：先明确签名、来源、安装权限、升级后 pins 与回退，再在 Velopack/Windows App SDK 等候选中做最小原型。保持 Production GO PENDING，Finance 身份绑定独立完成。
3. **按实际需求扩展 AI/Knowledge**：先选多 Provider/Tools 或 PDF/Office 导入其中一个真实痛点；分别评估 Spring AI 薄适配或受限 Tika。embedding/revision/删除/重建合同先于 Qdrant/GraphRAG；不同时引入多套框架或索引服务。

此轮没有自动执行上列后续高风险工作；没有生产数据库切换、Finance 自动绑定、云端替换、Ollama 升级或第三方 telemetry。
