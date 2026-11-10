# 累积功能整合开发包

2026-11-10。最终可交付文件为 **`artifacts/PersonalAiWorkspace-integrated-development-20261110-r2.zip`**，产品源码 `75b9614d012c4cd6ee3b597da7f4074cdf59240b`。这是独立开发版，不替换现有安装或默认工作区；完整维护仍 **BLOCKED / productionGo=false**。

**不要使用没有 `-r2` 后缀的同日 integrated-development 候选。** 该候选的 JAR 逐条检查发现本地文件头损坏，已撤销其交付结论并保留原文件和失败回执。`maintenance-confirmation` 及更早用户包不受影响，字节保持不变。

## 为什么本次选择整合交付

原目标是一个可实际使用的 Windows 本地 AI 工作区，包含持续对话、显式记忆、资料和独立财务查询。近期开发已补齐多项生命周期保护和只读模型请求检查，但最近交付的 maintenance-confirmation 包仍停在 `fa9a594`。继续增加同类保护不能解决“源码有了，包里没有”的问题。

本次完成一个整合交付任务，没有为三模块数量追加小功能：将当前完整 Java 产品、Desktop 和 React 资源重新构建到独立新包；校验全部归档与内嵌来源；以最终包实际 EXE 验收隔离的原生只读入口。没有新增单元测试或重跑既有业务场景矩阵。

## 包里增加了什么

相对于旧 maintenance-confirmation 包，本版包括：迁移后受限数据会话的原生重开链；未开放领域的构造期写入禁止；浏览器客户端注册的禁用初始化；模型请求独立身份、完成通知防重放和持久所有权；设置页本地阻断请求列表；任务、资料上传/解析/清理、知识索引和读取的退出等待；原生退出/维护 FINISH 对实际 drain 结果的核对。

用户可在 **设置 → 本机模型 → 高级模型操作与恢复 → 查看阻断请求（只读）** 读取本地登记。读取不调用提供商，不清除 guard，不赋予恢复权限。本版已打入这项 React/Bridge/API 接线；本轮没有运行它的完整 WebView/Runtime 流程，先前分层验证见 [模型请求检查](MODEL-EXECUTION-INSPECTION.md)。

迁移后受限会话仍只开放声明的存储状态、记忆读取及新增。知识、模型和其他写入不能因重开而解除限制。普通工作区的既有能力保留，但其当前实际模型状态不由新包或历史成功记录推导。

## 构建与完整性

[离线整合脚本](../../scripts/verification/package-integrated-development.ps1)首先检查基线清单、Bootstrap 固定 pin，以及依赖、资源和工具链合同未变。只复用这些未变文件；从 Git 跟踪源编译全部 194 个 Java 产品文件，生成 567 个 class，重新构建 React 和 Desktop。所有应用 class 都来自本次完整编译，没有拼接此前多个验证目录的 class，也没有保留旧的多余内部类。

JAR 使用全新归档创建，Boot 内嵌依赖以 STORED 保存。封装前重新读取每个条目：567 个应用 class 与编译输出相同，248 个其他条目与旧包资源/依赖逐字节相同，仅构建来源标记另行更新。没有类路径覆盖、依赖下载、程序安装或模型操作。

最终 933 个 payload、934 个 ZIP 条目和 420 条 Desktop 内嵌 Runtime pin 全部核对。源文件在构建前后摘要相同。清单 `sourceDirty=true` 如实保留六份未跟踪用户文档及本轮交付脚本/说明的状态；产品源无未提交改动，产品提交与后续交付说明提交分别记录。

| 文件 | SHA-256 |
| --- | --- |
| 最终 ZIP（带 `-r2`） | `221B62CE2EC0972B88B83F8176544B61B4BEB8703FE4C7CC57049CB6EEF3F9BE` |
| 最终 Runtime JAR | `7A51CA97EA2F5F5C3AC43453FDF46FFFBD3135AC1616172CE729E759727E3C9A` |
| 最终 Desktop DLL | `07FDDD16EE97F6F1C1E03104BF5B5EC8746384FCC0F40EDFDC8D5E56C3235AFF` |

构建证据：`.verification/integrated-development-20261110-r3/` 的 `delivery-result.json`、`runtime-content-audit.json`、`product-sources.json`、`build-cleanup.json`。Java 有 75 条既有 `ExtendedOpenOption` 警告及弃用提示；前端有大于 500 kB 的 bundle 提示，无构建错误。Desktop 构建成功。构建 guardian 于 `19:08:12Z` 记录 Job 0、锁释放、failure=null。

## 最终包真实入口检查

`.verification/integrated-entry-20261110-final/`；全新合成 Profile `a6c4fc1abb154d508b242a9586321875`。使用最终包的实际 EXE、原生组件与固定 Bootstrap，没有替换产品 DLL 或使用测试版入口。

1. 用产品 `--choose-development-workspace` 打开原生选择器。实际 UIA 读取开发版说明和三个入口，Invoke“关闭”，退出码 0。此参数不读取默认选择记录。
2. 通过既有受控工具准备全新合成 Profile，再用产品 `--recover-development-workspace-v1` 打开维护页。页面显示 BLOCKED、历史/排他访问尚未证明；备份、恢复、来源连接均未开放。新 Profile 没有普通 Runtime 生命周期登记，未取得 exclusion 是本场景的预期拒绝，不能记为来源准备完成。
3. Invoke“审阅维护迁移范围与条件”。实际页面说明资料范围和模型任务边界，执行迁移按钮禁用；关闭审阅及维护页，退出码 3，表示只读检查结束且维护仍 BLOCKED。

三个实际窗口均保存 PNG 和 UIA 快照并检查显示。最终实际 EXE PID 为选择器 40872、维护页 39352；没有按旧 PID 查找或控制进程。`entry-result.json` passed，`entry-cleanup.json` forcedOwnedExit=0、temporaryTokenRemoved=true；guardian 于 `19:08:48Z` 记录 Job 0、锁释放、failure=null。

本轮没有启动 Java Runtime、WebView、PostgreSQL 或模型，没有点击来源连接授权，没有读取真实凭据、接触真实数据库或更改默认工作区。这三页的局部视觉/语义验收 **不等于完整桌面视觉、网页/Bridge/Runtime 联动或成功维护迁移验收**。

使用原 `Local\Dot_KAG_Workspace_HeavyRun`、拥有的 Job 和 450 秒外层期限；构建 Java 384 MiB/2 CPU、Node 384 MiB、.NET 限制堆和并行度。最终原生入口使用 Job 每进程 768 MiB/整 Job 2 GiB，避免向受信原生 helper 注入其禁止的 DOTNET_* 环境变量。没有修改产品准入、系统权限、安全设置或网络。

## 保留的失败与回退

- 首轮 `.verification/integrated-development-20261110/` 把 `bootstrap/tests` 的夹具变化误计为复用依赖变化，在编译前停止；收窄到真正的 Bootstrap 产品源后继续。无安装或数据操作。
- 第二轮 build 产出无后缀候选，但额外 JAR 全量读取在 `BOOT-INF/classes/backup/workspace-v3-pg17.json` 发现本地文件头损坏。它的 `delivery-result.json` 由同目录 `delivery-invalidated.json` 明确撤销；最终脚本改为创建全新归档，并把全部条目实读核对置于发包前。原失败文件未覆盖。
- 首个原生检查把 UIA 的 Button 与同名 Text 同时计数，导致验收断言失败；该自有 EXE 被收尾，forcedOwnedExit=1。修正测试筛选后，没有修改产品代码。
- `.verification/integrated-entry-20261110-r2/` 的准备失败是验收包装添加 DOTNET_* 变量触发 `ValidateHelper`。该 helper 尚未启动原生 provision，部分新 Profile `7bc232ba448c46ff8cc7410cdf6353a3` 保留，未重试或修复它。以 Job 承担内存限制、另一全新 Profile 继续，未放宽产品检查。此前无效包上的成功入口结果也没有替代最终包的单独检查。
- 每个失败包装均已释放 Job/共享锁；没有待执行的动作或不确定启动。本轮没有自动审批拒绝；继承的旧端口、旧 Profile/进程和外部反馈拒绝均未重试。

回退无需安装/迁移操作：旧包仍在原位置，本版是独立目录和 ZIP。本轮 Git 只提交已完成的构建脚本、开发版说明和交付报告；不移动 main、不推送、不提交六份用户文档或运行证据。

## 未完成边界与下一步

真实模型恢复、旧未知请求终态证明、完整写入者覆盖、完整维护入口、默认启动和最终 WebView/Runtime 视觉流程仍未完成。当前提供商缺请求级终态查询/取消凭证；本地取消、进程退出、人工勾选或此包的通过记录都不能解除旧 guard。

普通完整入口仍使用既有固定端点，继承的旧端口状态保持未核实；本轮没有查询/占用它，也没有调整端点或门禁。下一步应在具备已授权且可证明隔离的完整启动条件后验收本包实际网页/Runtime 流程，或推进独占模型服务的明确所有权设计；不要继续堆叠旧提供商不支持的恢复接口。任何真实模型/付费服务/真实数据迁移都需另行明确目标、数据、费用、风险及回退后再确认。

`protected-inputs.json` 确认 main 仍为 `f5266bf`，六份用户文档和旧 maintenance-confirmation ZIP/JAR 哈希均与入场基线相同。请求的模型/思考/服务档位原样记为 gpt-6-astra / xhigh / fast，不推断实际路由。
