# 首次离线锁生成：源码交付与审阅边界

2026-11-11。起点 `6a5a29a3c1a09cc3385ee8a45e0b86780e631906`，分支 `codex/knowledge-version-compare`。产品仍固定 `2ca50415cc6752288110fb31aa0abff6af7d4ed9`；main 仍为 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。

本次交付是 **269 行、单独可审阅的离线锁 runner 源码**、固定本地输入合同，以及一份**未应用**的 finance 来源选择补丁。没有运行 runner、restore、Guard、Build、编译器/注解处理器、产品/helper 或原生准入。两份真实锁仍缺失，候选包仍未构建。`productionGo=false`、`nativeValidation=NOT_RUN`。

## 文件与固定字节

| 交付 | 字节数 | SHA256 |
|---|---:|---|
| `scripts/verification/candidate-lock/GenerateLocks.ps1` | 22453 | `5b01085c9b5a9cccc431ffafb05b08b9ad92df9a035d683deb2bcb4cb6a3511d` |
| `scripts/verification/candidate-lock/lock-inputs.json` | 6716 | `6b5c069a8f5fe6256f842e2bf65f1bb5dee303a1f487c4fc370dec5de0759827` |
| `docs/engineering/proposals/finance-source-selection.patch` | 14565 | `5da6915404be6264045bb75ae99c21abd6d3e2b68dc4e0a7a1b52ee4b7fe17db` |

runner 内固定合同和 Common 的 SHA256，合同固定 BuildSupport 和三份完整工具文件清单。没有导入 Guard/Build，也没有复制其测试入口。复用的是 `Common.ps1` 中的路径、哈希、环境、Git 对象读取及工具回执函数，以及 `BuildSupport.cs` 中的自有 Job/二进制 stdout 辅助代码。两个现存文件保持不变：

- Common：24538 B，`ad86946901ac238ca8f63b66d712f3ce5bb60603d206a14169eb9df4d03a4e8f`。
- BuildSupport：18750 B，`f285a6d4e39e947af46d01132b94d84599a59a015b5cec26d7fe0eade5485700`。

三份原待审文件也保持原字节：Build.ps1 `62f40c5c41cdbe88c49d4cf996d94003653802220e5977bafecd99d39d4d7911`；inputs.schema.json `a6f48ef1ad85feea7f35c94de98e06e5eca7d0b2c5923b72904377c9422aca22`；CANDIDATE-INPUT-CONTRACT-REVISION.md `dd805c8037ada9b59e5917b633c513c7f57eaa16cd67b5f85f5d499df4533fb7`。finance 补丁改变这些文件中的前两份的建议版本，当前没有应用。

完整 diff 保存为 `.verification/candidate-lock-source-review-ceca0aa8f22c4860a65df39cf343ac56/complete.diff`，基线为本页起点，最终哈希随交付回执提供；不将 diff 的哈希放入其自身内容形成循环。源码、报告和静态回执索引见 `CANDIDATE-LOCK-RUNNER-CATALOG.json`。

## 固定输入，不生成假锁

唯一预定执行根（**本轮未创建**）：

```text
D:\IDEA\Daima\personal-ai-workspace\artifacts\candidate-lock-inputs-06d179db0e54442abf3ebcc5f865808f
```

以下称 L。runner 不接收任意 Root、源目录、包源、参数或重试选项；该根存在就拒绝。改变根/合同须重新审阅源码哈希。只有 guardian/内部 worker 两条路径，内部路径还核对私有脚本位置和本次 ownership token；token 不是 OS 安全边界。

| 输入 | 固定来源 | 完整清单身份 |
|---|---|---|
| 产品源码 | 指定 Git commit 的原始 tree/blob | `2ca50415cc6752288110fb31aa0abff6af7d4ed9`；执行时逐 blob 核对 Git ID，再保存每文件 SHA256 |
| PowerShell 7.6.9 | `C:\Users\28421\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell` | 658 文件；pwsh-tree.json `29c634fa41db939bf66657b1439bda369af905fce0a2835c8034f5f370a6fd2f` |
| Git 2.54.0.windows.1 | `C:\Program Files\Git` | 9305 个已选普通文件；git-tree.json `bb932b0f90181a79f6416f2bda5a726b7d92ae81d5fa9400a169a1f10ce9d459`；此前记录的 6 个跳过项不复制 |
| dotnet host/runtime 10.0.12、SDK 10.0.401、packs | `C:\Program Files\dotnet` | 完整 6778 文件；dotnet-tree.json `de85a10b4d5a4a7b8f0d776a139a1b62db274ba248dab997b92b7cde738c1078` |

三份清单的精确本地路径、原始长度和哈希都在 lock-inputs.json；每条清单包含全部相对路径、长度、SHA256。本轮只核对这些已保存清单的原始字节/条数，**没有重新扫描或哈希 16741 个真实工具文件**。实际复制时才逐条核对来源和新副本，不扫描用户其他目录。现有 host 必须已是指定 PowerShell 路径；不会自动换 shell。

唯一包来源是下面八个 `.nupkg`。来源根为 `C:\Users\28421\.nuget\packages`，每个精确文件路径、字节数、原始 archiveSha512 与 NuGet contentHash 已完整写入合同。没有复制旧 metadata、assets、obj、锁或解包缓存。

| 包 | 版本 | SHA256 |
|---|---|---|
| DocumentFormat.OpenXml | 3.5.1 | `71375a11a53eeb554005477ce6ca127909aebed474900e92298360b49a68307f` |
| DocumentFormat.OpenXml.Framework | 3.5.1 | `d317d974986a90d8f6de1832a48fb1052d771ea475a37a2f4e3be85c497fa9c5` |
| Microsoft.AspNetCore.App.Runtime.win-x64 | 10.0.14 | `91638ac8382c437e71c0739a0e3123290f9bf48bcd01e616352a86add5571089` |
| Microsoft.NETCore.App.Host.win-x64 | 10.0.14 | `2a4547b0e6cf0dd15e723531daa5450d99d4e151105aec0d31f2b0c7cfb286db` |
| Microsoft.NETCore.App.Runtime.win-x64 | 10.0.14 | `a3ea61b4a2da6d28e562dd1cdacf80eb4dd6b9727b0a2b47f9d6aba90dbbcb1f` |
| Microsoft.Web.WebView2 | 1.0.4258.31 | `56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9` |
| Microsoft.WindowsDesktop.App.Runtime.win-x64 | 10.0.14 | `a6dba3ca8b8b5b7945399545d881e0ac294746a7571f2209981934d49ecfe8cc` |
| PdfPig | 0.1.16 | `d67171846ea8c28f50359137065fec4514266d7a32b23eae6c5f2ebed8ffcfc4` |

合同中的 contentHash 来自此前真实缓存证据，仅作为预期值；只有新空缓存运行后生成的 `.nupkg.metadata` 才能用于新结果核对。原始 SHA512 不充当 contentHash，未声称本轮已验证签名。也未把历史四个 runtime 下载包一定全部被选择当作已验证事实：实际缺包/集合不同就保留失败，不能补下载或伪造包使用记录。

源码准备沿用私有 Git 对象读取方式：仅复制普通 loose/pack 对象，不将原 config、hooks、attributes、index、refs 交给 Git；只调用 ls-tree/cat-file。以固定 commit 导出 L/source 全树，拒绝生成物/旧锁/链接/大小写冲突，每份原始 blob 核对后写入。三个适用项目文件是 Desktop.csproj、Core.csproj 和 desktop/Directory.Build.props；完整快照的哈希清单随执行回执保存。本轮只查看了冻结树名字与这三份 XML 源码，未执行导出函数。

## 唯一 restore 与实际执行面

runner 在新 `L/source/desktop/global.json` 写入 sdk=10.0.401、rollForward=disable、allowPrerelease=false。冻结根 global.json 不改；Git 树已静态确认不存在 desktop/global.json 或任何 packages.lock.json。新控制文件的字节/哈希单列，不冒称 Git 原始源码。

唯一 restore 入口为 `L/source/desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj`；Core 通过项目引用参与。实参完整写在 `Invoke-LockRestore`，执行时逐字保存在 command 回执；核心选项是 `-r win-x64 --use-lock-file --disable-parallel --no-http-cache --configfile <private> --verbosity diagnostic`，NuGetAudit=false、Configuration=Debug。不使用 locked-mode，也不调用 build/publish。

Mmf3ProfileAcceptanceBuild=true、Mmf3AcceptanceBuild=false、MainWorkspaceDev=false、FrontendSkipBuild=true、RuntimeFrameworkVersion=10.0.14、SelfContained=true、UseSharedCompilation=false 与候选一致；保留原 props 的宏生成语义，**没有全局 DefineConstants 覆盖**。DirectoryBuildPropsPath 固定快照 props，DirectoryBuildTargetsPath 和 CustomBefore/AfterMicrosoftCommonTargets 固定新 empty.targets，`-m:1 -nodeReuse:false`。关闭 workload resolver；额外关闭中央包 props 搜索，固定 DirectoryPackagesPropsPath 为 empty.targets。这不改变冻结项目的包声明，防止从 L 的父目录引入未审中央包配置。

私有 NuGet.Config clear packageSources/auditSources/packageSourceMapping/fallbackPackageFolders，只添加 L/inputs/nuget-feed。RestoreSources 同样只指向该 feed；清空 additional/fallback sources，关闭隐式 fallback/library-packs、交互和 ignore-failed-sources。所有相关用户目录、临时文件、NuGet 缓存、MSBuild 用户扩展、Git 配置均映射 L；先清空继承环境，再构造最小 PATH、固定 SDK 路径，移除 Node/Java 变量，关闭遥测/首次体验/服务器与节点复用、在线吊销检查。

允许执行的代码必须如实接受：

- 当前受审 PowerShell 主机及私有副本；guardian/worker 会通过 Add-Type 在内存编译固定 BuildSupport，执行其 Windows Job P/Invoke 和进程/文件 I/O。这不是产品程序集加载，也不是“完全没有代码编译”。
- 固定 Git 的 ls-tree/cat-file；固定 dotnet host、MSBuild、NuGet 图收集/RestoreTask、SDK 框架/host 包解析任务。详细 SDK 入口、254 个 XML 导入/条件和 NuGet tasks 已列于 [执行面计划](CANDIDATE-INPUT-ACTION-PLAN.md)。
- WebView2 是八包中唯一带 build/buildTransitive targets 的包；八包无 analyzer。WebView2 的 C++ dispatch adapter/MIDL/Cl 条件未启用，Restore 不请求 Clean。整个 SDK 内的分析器仍属于固定输入，但本步骤不请求 C# 项目编译。
- 项目的前端目标在 PrepareForBuild 前，Restore 不请求该目标；FrontendSkipBuild=true 本身并不会跳过 node assets 校验，不能用这个属性来声称所有构建 hook 都关闭。没有为此复制 Node/npm/前端依赖。

上述是阶段和固定源码分析，不是全部二进制的安全认证。配置只限定预期解析来源，**没有 OS 网络/文件系统沙箱**；同用户 SDK/系统证书组件仍有访问外部的能力。没有改 ACL、身份、防火墙或系统安全。diagnostic 日志会保留实际求值信息；自动检查不能证明所有任务没有其他 I/O，独立审阅必须查看日志中的源、插件、目标和外部路径，发现范围外行为就停止接纳，不自动换源或重跑。

## 进程、结果与失败处理

Session 1 下使用 `Local\Dot_KAG_Workspace_HeavyRun`。直接共用 `.verification/candidate-build-active.json`，并检查 candidate-tools-synthetic-active.json，使已有 Guard/合成入口也能看到本步骤留下的未知状态；没有新增一个旧入口看不见的锁文件。这个精确协调文件是 L 外唯一预期写入例外。拿到 mutex 不等于先前负载已结束；既有 latch 或 abandoned mutex 均停止。

固定工具/包的准备复制在 worker 前；worker 挂起创建、加入自己的 Job 后恢复，最多 12 进程、单进程 768 MiB、Job 2048 MiB，恢复后 660 秒；单个 Git/restore 工具 300 秒、输出 drain 10 秒、Job 清理 10 秒。准备复制/哈希与结束后核对没有整个入口的硬 I/O 时限。不扫描或终止其他进程。

只有 worker exit=0、Job 活动进程归零、未分配子进程及所有句柄关闭确认、未需要强制清理后，才核对结果。失败/拒绝/未知状态不会在 finally 中盲目重读输入，也没有重试或递归删除。结果回执写入失败或清理未知会留下 latch。已确认结束的失败可以释放本次 latch，但执行根仍保留，入口仍拒绝使用同一个根再跑。

核对范围：

- 新缓存必须恰好含八个允许包/版本；新 metadata 的 source 必须为私有 feed、contentHash 与合同一致，缓存 nupkg 必须等于原始 archive SHA256。
- 两个默认位置必须都有真实锁；校验 TFM/RID、依赖类型/版本/contentHash。Desktop 的三个直接依赖、一个包传递依赖和 Core 项目引用必须存在；Core 不引入包依赖。assets 与锁包集合一致，assets 必须有 win-x64、私有缓存和唯一私有源。
- 新 obj 中 JSON/props/targets 做结构化路径核对；拒绝项目 bin 和 obj 中的 DLL/EXE/PDB。逐项复核所有原 Git blob 未改变，保存新 obj/缓存文件清单和两份完整锁的图。
- 原始锁以 CreateNew 原样复制到 L/accepted-locks；两个文件与 `lock-review.json`、guardian-result、guardian-released 一起才构成完整回执。目录名不授予接受：状态始终 `GENERATED_PENDING_INDEPENDENT_REVIEW` / approvedForCandidate=false。部分复制失败、缺回执或日志未复核均不能交给真实 Guard。

没有自动批准新锁的语义图；独立审阅仍需确认完整依赖边/下载图、日志、退出与清理记录及哈希链。缺锁、解析失败、包签名失败或前提与真实 SDK 行为不符就保留原始结果，不能从 assets 写出替代锁。

## finance 最小来源选择补丁（未应用）

[finance-source-selection.patch](proposals/finance-source-selection.patch) 是对当前 Build.ps1 和 schema 的完整建议 diff；本轮仅解析草案和检查补丁可应用性，没有修改实际 builder。没有新增 finance 编译设施，没有运行处理器，也没有生成新 JAR 或回执样本。

建议把候选输入升级 V3，强制 `financeBuild.kind=rebuilt-fixed-source` 和经独立审阅的 receiptSha256；将内部角色改为 rebuilt-finance-module，移除旧 finance-module 的接受方式。利用既有 contracts 输入树容纳父 POM、新 finance-domain-0.1.0.jar 和 finance-build-receipt.json，不增加第十棵树或另一种 Guard。

在读取旧主 JAR 的确切 finance 条目时，只采用其名字和 classpath 位置，改从新合同输入取 finance 字节，**不解出旧 finance JAR/class**。旧主 JAR 的普通第三方、loader/service 和空 marker 规则不放宽。classpath.idx 的顺序保持，Workspace javac 仍 -proc:none；组装来源记录将新 finance 标成 rebuilt-fixed-source，不再标成旧包 reuse。旧 baseline 的 sourceDirty=true 和历史 class 来源未证明的说明仍保留。

未来 finance 回执（本轮不存在）至少使用以下明确字段：

- format=WORKSPACE_FINANCE_BUILD_RECEIPT_V1、productRevision=固定 commit、exitCode=0、jobCleanupConfirmed=true、oldFinanceClassesUsed=false、output 的原始长度/SHA256。
- sourceInputs 为固定 Git 的 54 Java+21 资源完整 path/bytes/sha256；每项与新候选源码比较。
- classpath 为已审 77 个非 finance 第三方 JAR 的完整 path/bytes/sha256，顺序与候选依赖完全一致；不得包含旧 finance、wildcard 或运行时 Lombok。
- javacSha256=`6fe0b34e9b47d255351040c9ad5920cad9c37d5a10a60916cd2c84299d0c40af`，jdkInventorySha256=`c9acc39c25271fae0b3af7b4af7911e9fcd12e505505273d5c1efa73ed36a95f`（既有完整 418 文件清单的原始身份）；lombokSha256=`01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126`。
- processors 顺序严格为 `lombok.launch.AnnotationProcessorHider$AnnotationProcessor`、`lombok.launch.AnnotationProcessorHider$ClaimingProcessor`，以字面值保存。执行会继续进入固定 JAR 内的 shadow loader/handlers，不能声称只运行两个 class。

回执字段和哈希本身不能证明某次编译确实发生；必须结合未来真实 compiler 参数、全套 JDK/Lombok/依赖哈希、源码导出、lombok.config（stopBubbling）、新 class/JAR 组装来源及 Job 结束证据独立审阅后，才能把其哈希填入 V3。新 finance class 数量不必等于历史 85 个，不能用旧 class 填缺。完整 Lombok LICENSE 继续随其工具输入保留。

BOOT.SF 建议的明确来源契约为：唯一 `META-INF/BOOT.SF`，0 B，SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`，角色仍为 empty-legacy-marker；reason 明写“Spring Boot 4.1.1 打包机制的空兼容标记，原样保留，非外层应用签名”。三份 Bouncy Castle JAR 保留整个固定字节与签名相关元数据；本轮没有验签。此契约提出保留，不在当前缺失的真实 inputs 中假填接受记录。

## 实际静态检查与下一步

新 runner AST：0 errors。finance 未应用草案 Build AST：0 errors；固定合同和草案 schema JSON 均解析成功。三份已保存工具清单的字节数/哈希/文件条数一致；固定 Git 树没有旧 obj/bin/dist/node_modules/锁或 desktop/global.json。finance 补丁 `git apply --check --whitespace=error-all` 通过，未 apply。没有 dot-source/执行新 runner，没有 Add-Type 新支持代码、没有 Roslyn Emit，也没有运行历史 56 项/4 Job/6 项检查。AST 和补丁检查不能证明真实 restore 会成功。

本次只创建了独立源码审阅证据目录；不是执行根。最终回执在 `.verification/candidate-lock-source-review-ceca0aa8f22c4860a65df39cf343ac56/final-static-review.json`；static-review.json 保留补充 finance 回执布尔类型/重复路径检查前的阶段结果，不冒称最终草案字节。一次用于汇总哈希的 shell 命令有语法错误、未执行操作；修正括号后完成汇总。没有未知执行结果或历史受拒动作重试。

本轮 Git 暂存曾被普通文件系统沙箱拒绝：精确的五文件 `git add` 无法创建 `D:/IDEA/Daima/personal-ai-workspace/.git/index.lock`（Permission denied）。随后只读确认暂存区仍空，通过 user_message_read 查证用户原话“我允许你做的一切决定，只要你能通过git备份好就行，确保能回滚”（消息 Sentinel_e0ed93fe3a5481919656ee5c748bca31），再经正常 require_escalated 审核重试该暂存一次，退出 0。不是自动审核拒绝，也没有修改 ACL、换仓库、shell 或绕过拒绝；该暂存阻塞已解除。最终五文件 `git diff --cached --check` 通过。

下一步先独立审阅 runner、Common/BuildSupport 的本次调用路径、固定合同和完整 diff，再明确决定是否允许**唯一一次**离线 restore。当前委派只允许源码与静态检查，因此本轮到这里停止执行部分。runner 尚未经过实际运行验证，不能把旧合成结果写成最终源码的逐字节验证。

随后才是单独的 finance 编译/真实回执、finance 补丁评审应用、最终 publish/runtime 唯一来源白名单与 V3 输入闭合。全部闭合之前不构建候选。原旧开发 ZIP 不改名、不覆盖、不宣称包含后续修正；产品所有权候选 d7b4786 未部署/未原生验收。KAG、Computer Use、原/替代 Profile、模型、helper、旧 Crashpad/Profile、受拒网络查询/网页及被拒脚本/目录均未操作。六份原未跟踪用户文档和 main 保持原状；仅本地 Git checkpoint，无推送。
