# 当前编译产物审阅与最小真实联动计划

最新交付：基于来源提交 `933bb20` 的同源候选包已离线生成并通过 Runtime／内嵌 pins／ZIP 校验，见 [候选包与准确启动范围](SAME-SOURCE-CANDIDATE-AND-STARTUP-SCOPE.md)。它与下文 REVIEW_ONLY ZIP 是不同工件，仍未运行、安装或部署。

后续复核：原 Profile 的准确目标在 `C:\Users\CodexSandboxOffline\AppData\Local` 下，早期按当前环境推导的 `C:\Users\28421\AppData\Local` 不是已证实的原目标。2026-11-11 已在普通权限下完成原根目录和 model-state 的限定元数据读取，并通过一次独立 Profile 条件源码编译（0 警告／0 错误）；未启动产品或 helper。见 [准确目标、编译回执与准入边界](PROFILE-METADATA-AND-CONDITIONAL-COMPILE-REPORT.md)。下文“本轮未读取／未编译”描述前一轮审阅组装，不覆盖该后续结果。

日期：2026-11-11。产品源码基线 `5a57ff76a133a4e42e90a4582c76df64af2ad6a1`；产品代码与 `7e7332e` 相同，此后仅更新构建与诊断文档。本轮不重建前端／C#／Java，不运行产品、helper 或旧拒绝入口，不读真实用户数据。

## 选定的一次真实联动

验证 `7e7332e` 的资料出处重新授权链：准备一份只有两句文字的合成 UTF-8 文档，通过正常资料导入创建 v1；从原文选区建立带 documentId／revision／范围的记忆草稿并按正常流程保存。正常退出工作区客户端并独立重开，从正常记忆列表重新进入该记忆并回查原文，观察新会话的受控资料列表先找到准确 ID，再读取详情与原版本片段。仅一个文档／记忆、一次退出重开；不生成问答、不调用模型、不测试批量导入或其他历史场景。仅关闭来源页不足以证明 Native session 的读取资格已清空，不以同进程切页代替这次重开。

实际链路为 `MemorySourceReview -> readAuthorizedKnowledge -> Bridge listKnowledge/getKnowledge -> WorkspaceKnowledge.ListAsync/GetAsync -> RuntimeClient -> KnowledgeController -> KnowledgeStore`。前端会先查询 ACTIVE，未找到才查 ARCHIVED；Native 列表响应授予当前会话 Documents 集合的资格，GetAsync 先 Require(session,id)；后端的 `/api/v1/knowledge/documents`、`/documents/{id}`、`/documents/{id}/preview?revision=...` 仍要求 native 客户端身份。验收证据应包含真实 Desktop／Runtime 的 PID 和版本、请求方法与脱敏 ID／revision、页面实际显示内容、退出与锁释放；不记录 token 或数据库凭据。仅用 HTTP 调用或 Mock 不算上述三层联动已通过。

## 为什么当前不能直接执行

1. `PersonalAiWorkspaceApplication.main/startRuntime` 在 Spring 启动前必经 `WindowsNativeGate.admit()` 和 `requireRuntimeArguments()`。Windows 下必须使用已核实 helper 的完整 pin、Profile ID、原生库及 Profile 所有权；参数精确绑定该 Profile 的 credentials/data/model-state。改 user.home、端口、系统属性、启动类或调用 Spring 子入口都不能替代该准入。
2. 当前编译通过的 `artifacts/integration-compile-20261111-7e7332e/dotnet/bin/PersonalAiWorkspace.Desktop/debug/PersonalAiWorkspace.Desktop.exe` 是普通 Debug，实际构建调用明确 `Mmf3AcceptanceBuild=false`、`Mmf3ProfileAcceptanceBuild=false`。`Program` 普通分支不接受隔离 Profile 参数，`AssistantApp` 使用默认 CredentialStore／RuntimeClient；启动它可能接触默认用户上下文，不能当成合成隔离联动。
3. 正常隔离桌面入口是 **另一个明确启用 Profile 的构建** 的 `--mmf3-profile-acceptance <id>`（或既有 open-once 入口），会验证 helper、Profile 和受信 Runtime，再由 `DevelopmentRuntimeOwner` 管理子进程。它尚未为当前源码生成，不能给普通 Debug 填入参数冒充该入口。
4. 已有 Profile `b204c7e3cc33425e841eb1aa32d2caa2` 的 model-state 登记曾报 `Invalid model state ownership or type`，后续 armGuard／EXE 未执行。其准入限制没有因纯编译成功解除；所有权安全候选 `d7b4786` 仍未部署／原生验收。图形工具也尚未确认用户主机身份，不能用当前 shell 的 cwd 推定可点击的桌面。

因此本轮没有启动 Java、Desktop、helper，没有创建新 Profile、读取旧 Profile 或重演登记，没有声称真实联动成功。

## 交父任务判断的最小下一动作

**当时提出的动作是原失败对象的限定只读诊断，而非重试登记。** 使用当前已确认解释器 `C:\Users\28421\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe`（工具 shell 选择 `powershell`），只对原 Profile 根和其 `model-state` 做 `Get-Item -LiteralPath ... -Force`、`Get-Acl -LiteralPath ...`；不枚举子文件、不读内容、不调用 Java／helper、toRealPath、注册、锁或 armGuard，不改 ACL。早期推导的 28421 用户目录未作为目标访问；后续按既有原始报告核实的原目标为 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2\model-state`，限定读取的实际结果见上方复核报告。今后准入仍须正常解析至这个原目标；不能替换目录、账户或 Profile 来迁就启动。元数据查询若拒绝，停止该目标。

该动作与旧拒绝直接相关，仅用于区分文件类型、reparse/link、owner／DACL 等最基本条件，不预判根因。诊断输出只写新的仓库内 `artifacts/profile-metadata-review-<本次唯一标识>`，不处理历史目录。无产品进程或目标写入，清理仅关闭自身查询句柄；保留回执，不删除原 Profile，因此无数据库回滚需求。本轮**未提交或执行**这个诊断请求，等待父任务对原受限对象的边界作判断。

**真实联动的后续条件也需单独闭合。** 只有原拒绝得到相关证据支持、正常审批明确允许后，才考虑：以本报告对应最新源码生成一次 Profile Debug 候选（`Mmf3ProfileAcceptanceBuild=true`、`Mmf3AcceptanceBuild=false`、self-contained win-x64），携带当前正式 Runtime JAR 和对应内嵌 pins，输出到全新 `artifacts/PersonalAiWorkspace-knowledge-source-live-candidate-20261111`；沿既有核实的 Bootstrap／JDK 正常入口，针对同一原合成 Profile 使用 `--mmf3-profile-acceptance b204c7e3cc33425e841eb1aa32d2caa2`。该目录目前未生成；这不是批准使用旧冻结 `2ca5041` 或安全候选，也不是要求现在更换 Profile。若原目标已不是可用的自有合成环境，交父任务重新判断，不能自动新建一个来规避旧拒绝。

此阶段会执行产品／helper、读取合成 Profile 的 token 和配置、写合成知识／记忆数据及 WebView 状态，并启动真实后端；它超出本轮纯编译和审阅组装范围。Profile 构建还会纳入普通 Debug 未编入的条件代码，涉及尚未原生验收的安全候选时必须先单独审阅并决定，不能凭普通编译成功推定可部署或运行。源数据应仅为固定合成文本，文件输入放在新的仓库内验证目录；不得复制真实数据库／用户文档。回滚是保留旧包、停止这次拥有的子进程并沿正常产品 API 删除本次合成文档／记忆；不改原登记或清理未知会话。清理需记录 Desktop 的正常退出、其 Runtime 所有权／Job 收尾和锁释放，结果不明先核回执；不按名称终止其他进程。完整 UI 操作还需先确认图形工具与该 Windows 主机的关联，未确认时不能点击。

## 审阅产物的允许范围

本轮可组装的是 **REVIEW_ONLY 编译输出资料包**，不是 `WORKSPACE_DEVELOPMENT_BUNDLE_V1` 或可安装／可运行发行候选。正规发行脚本 `scripts/package-windows.ps1` 即使传 Prebuilt 仍会 `dotnet publish` 并要求当前可执行 Runtime JAR；开发打包脚本 `scripts/verification/package-integrated-development.ps1` 还会 javac、重建前端、写原 target、Profile publish 并 `Assembly.Load` 产品程序集。两者不满足本轮“复用已有输出、不重建、不加载产品程序集”的条件，均未执行；不会删去它们的保护分支后换名运行。

只复用其安全数据组装方式：新目录、明确文件列表复制、逐项 SHA256、生产前端资产清单核对、`ZipFile.CreateFromDirectory`、重新打开 ZIP 比对各条目哈希。没有生成新的打包系统或伪造正式 bundle manifest。载荷为当前普通 Debug 桌面输出（不带 PDB 和 SDK 文档 XML）、其中真实打包的前端资源、两个 Java 模块的本次 classes／资源、脱敏构建回执与依赖清单。Java 依赖仅列坐标／原路径／哈希，不复制被拒过的依赖或借此改变后续运行路径；不混入旧 JAR、JDK、helper、启动脚本或开发 Profile。

Desktop 源码／输出来自 `7e7332e`，Java 源码／输出来自 `0b7d969`；静态 Git 差异核对确认到 `5a57ff7` 没有 desktop/src、前端、Java、Finance、POM 或 Bootstrap 产品改动。桌面 runtimeconfig 是 framework-dependent net10.0，不能冒充 self-contained Release；Java 尚无带正式 Runtime identity 的 Boot 可执行 JAR。原 C# 命令记录保留实际两个 MMF3=false 开关，前端 asset 清单绑定复制字节，Java 输出逐项对照成功 compile 的 682 项清单。构建检查不重复执行。

## 实际审阅组装结果

已生成 [REVIEW_ONLY ZIP](../../artifacts/PersonalAiWorkspace-review-only-20261111-5a57ff7.zip) 和同名解压目录。ZIP 大小 9,564,325 字节，SHA256 为 `e7656fed78aa422bed59cc69db160f2d7f535a8f9fddfa9b852fb7df48bc6152`。31 个桌面输出文件、682 个 Java 输出文件，加 README／来源资料共 735 个载荷文件，另有 review-manifest.json 与 SHA256SUMS.txt；ZIP 共 737 个条目，拷贝前后与 ZIP 读取后的哈希全部一致。

回执在 `artifacts/review-assembly-20261111-5a57ff7/assembly-result.json` 和 `assembly-cleanup.json`，实际组装工具会话 57377 退出 0；UTC `08:44:54.5783913Z` 完成，`08:44:54.5938378Z` 释放并 Dispose 本轮共享 mutex。`desktop-command.json` 保存真实构建命令与两个 MMF3=false 开关的原执行记录。本轮仅一个 PowerShell 组装命令，没有子产品进程、程序集加载、重新编译、测试、依赖下载、helper 或原生调用；没有执行两份旧打包脚本、正式 package 审计或 Runtime identity writer。副本只供审阅，不提供运行指令，不能称为已完成可运行候选打包。

main、六份用户文档、真实资料、旧编译和旧包保持；`productionGo=false`，`nativeValidation=NOT_RUN`，真实服务与桌面验收未完成。待父任务判断的是上述原受限 Profile 的限定诊断与后续准入范围，而不是要求重新批准已完成的编译或本次审阅组装。
