# 隔离候选构建工具：源码独立审阅材料

日期：2026-11-11。状态：SOURCE PREPARATION ONLY / NOT EXECUTION AUTHORIZATION。

产品输入固定为 `2ca50415cc6752288110fb31aa0abff6af7d4ed9`。本修订只新增构建工具、候选警示模板和本材料；没有修改产品、安全候选、旧包或原构建脚本。工具自己的 Git revision 由后续真实输入清单的 `buildToolsRevision` 固定，和产品 revision 分开审阅。

## 交付源码与入口

- `scripts/verification/candidate-build/Guard.ps1`：独立 guardian、共享锁、全新目录、工具拷贝、Job 启动与回执。
- `Common.ps1`：路径/输入 schema/哈希/私有环境/归档清单工具。
- `BuildSupport.cs`：纯 PE 资源解析、二进制子工具输出、受控 Job 与暂停后入 Job 的 worker。
- `Build.ps1`：固定 Git 快照、Java 与前端编译、离线 .NET restore/publish、空目录逐文件组装、双层归档。
- `inputs.schema.json`：真实审阅输入格式，不是可运行的示例输入。
- `scripts/distribution/README-candidate.txt`：内外层候选警示。

没有提供伪造的 inputs.json、NuGet 锁文件、工具版本哈希或载荷白名单。当前入口不能在缺少这些真实已审阅材料时工作。代码没有 approval flag；源码评审通过、输入评审通过及执行范围许可是三个不同事实，调用前须由操作者核对。

## 已从精确源码闭合的约定

1. Maven 父 POM `spring-boot-starter-parent:4.1.1` 的 SHA256 为 `04cc8a51d17b04d518c232ab5423fb3b9ca6c9eb95d2562800267a6bf991f953`，资源过滤 delimiter 为 `@`，`useDefaultDelimiters=false`。固定源码的 application.yml 没有成对 `@...@`，`${user.home}` 保留为运行时表达式。其余当前六个资源原样取自固定 Git 快照。以后遇到需要替换的 `@...@` 即停止，不假装已做 Maven 插值。
2. Workspace Java 只编译 `src/main/java/**/*.java`，显式 `-proc:none -implicit:none`。不运行 Maven/Gradle、Lombok、Java 主类或 jpackage。默认 Runtime 身份沿用六个键：format、kind、sourceSha、providerSha、environmentSha、gateSha；不引入 isolated-development class 或十键约定。sourceSha 绑定固定快照的完整 Git 文件清单，和旧脚本“本地 tracked + untracked”清单的来源语义不同，明示记录，不冒称相同工作树。
3. 旧 JAR 的 `BOOT-INF/classes/**` 全部丢弃；当前 class 和全部当前资源从空目录重建。Manifest、索引、Maven metadata、身份标记重新生成。第三方依赖、Boot loader、服务声明与内部 finance JAR 逐条白名单并绑定哈希/大小/复用理由。
4. `META-INF/BOOT.SF` 在旧 JAR 中为零字节，没有查明上游生成用途。仅当真实审阅清单显式将其列为 `empty-legacy-marker` 且哈希、大小、理由吻合时允许原样保留；不视为签名或真实性证明。未分类、非零字节及其他未知根条目都停止。这项兼容保留仍需输入审阅者明确接受。
5. `finance-domain-0.1.0.jar` 的历史构建依赖 Lombok，故不声称它经过本轮 `-proc:none` 重编译。工具要求相关 POM、finance 源码与资源在 b4ae52e..2ca5041 间不变，且旧 JAR 的全部非 metadata 资源逐字节等于固定源码（当前 21 个资源）。旧包 manifest 的 `sourceDirty=true` 会原样记入证据和新候选 manifest。以上并不证明历史 finance class 来自该 Git 提交；复用的是显式审阅的旧字节。
6. Java 工具链要求完整等于旧包 manifest 的 `runtime/java/**` 清单，候选运行时也须携带完整相同集合；不只核 java.exe 或抽取少量文件。
7. `Mmf3ProfileAcceptanceBuild=true`、Debug 不变。即使 `Mmf3AcceptanceBuild=false`，现有 Directory.Build.props 仍定义 **MMF3_ACCEPTANCE 和 MMF3_PROFILE_ACCEPTANCE**。其四个 Bootstrap 文件继续使用产品固定 pins。`TrustedDevelopmentRuntimeSources` 从 PE metadata/资源区读取，绝不 `Assembly.Load` 产品 DLL。

该 Profile 构建启用的产品入口包括无参数/保存的工作区、`--choose-development-workspace`、`--mmf3-profile-acceptance <ID>`、`--open-development-workspace-once <ID>`、`--recover-development-workspace-v1 <ID>`、`--inspect-profile-recovery-v1 <ID>`。启动这些入口会进入实际产品准入；构建工具不调用它们，也不将 EXE 的 `--help`/`--version` 当作无副作用检查。

## 拟用命令链（本轮未执行）

下面的 R 是全新 `.verification/candidate-2ca5041-YYYYMMDD-<32位hex>`，S 是 `R/work/source`，P 是 S 下 Desktop csproj。不存在自动重试、换 shell、下载缺失依赖或产品 smoke。完整实参以源码和每一步 `*-command.json` 为准；此处路径符号用于审阅，不能原样复制为真实已审批命令。

入口要求操作者已经处于真实清单指定的 PowerShell 7.4+ 主机、Windows Session 1：

```text
<reviewed-pwsh.exe> -NoLogo -NoProfile -NonInteractive -File <repo>/scripts/verification/candidate-build/Guard.ps1 -Inputs <reviewed-inputs.json> -Root <fresh-R>
```

没有 `ExecutionPolicy Bypass` 或替代主机回退。该正常入口自身若遭拒即停止，不换路径执行。

Guardian 的受控 worker 命令：

```text
R/tools/pwsh/pwsh.exe -NoLogo -NoProfile -NonInteractive -File R/control/Build.ps1 -Root R
```

Guardian 和 worker 都将用 PowerShell `Add-Type -Path R/control/BuildSupport.cs` 编译独立支持代码到内存；这不是产品加载，但会执行新工具代码，故本轮没有做。编译环境的 TEMP/缓存已改到 R。

Worker 按以下顺序调用受清单固定的工具：

```text
R/tools/git/cmd/git.exe -C <repo> rev-parse HEAD
R/tools/git/cmd/git.exe -C <repo> diff --quiet HEAD -- scripts/verification/candidate-build scripts/distribution/README-candidate.txt
R/tools/git/cmd/git.exe -C <repo> show <tools-revision>:<each-control-file>
R/tools/git/cmd/git.exe -C <repo> diff --quiet <b4ae52e-full> <2ca5041-full> -- <explicit unchanged-contract paths>
R/tools/git/cmd/git.exe -C <repo> ls-tree -r -z --full-tree <2ca5041-full>
R/tools/git/cmd/git.exe -C <repo> archive --format=zip --output=R/tmp/source.zip <2ca5041-full>
R/tools/java/bin/javac.exe -J-Xmx384m -J-XX:ActiveProcessorCount=2 -J-Djava.io.tmpdir=R/tmp/general --release 21 -parameters -encoding UTF-8 -proc:none -implicit:none -sourcepath "" -cp R/work/java-libraries/* -d R/work/java-classes @R/work/java-sources.txt
R/tools/node/node.exe S/desktop/frontend/node_modules/typescript/bin/tsc --noEmit
R/tools/node/node.exe S/desktop/frontend/node_modules/vite/bin/vite.js build
R/tools/node/node.exe S/desktop/frontend/scripts/assets.mjs
R/tools/dotnet/dotnet.exe restore P -r win-x64 --locked-mode --configfile R/control/NuGet.Config -p:NuGetAudit=false -p:Configuration=Debug <shared-properties>
R/tools/dotnet/dotnet.exe publish P --no-restore -c Debug -r win-x64 --self-contained true -p:TrustedDevelopmentManifest=R/work/trusted-runtime-sources.json -p:PublishSingleFile=false -p:PublishTrimmed=false -p:DebugType=None -p:DebugSymbols=false -o R/work/desktop-publish -v minimal <shared-properties>
```

Git 的 unchanged-contract paths 在 Export-SourceSnapshot 中逐字列出，覆盖 finance-domain、两级 POM、前端 package/lock、Bootstrap/Shared、Bootstrap 工程、Directory.Build.props、Desktop/Core csproj。Git archive 每个文件与 ls-tree blob ID 比较，export-ignore/subst 导致缺失或改字节即拒绝。工具六文件也必须等于独立工具提交；不依据未跟踪用户文档构造源码身份。

共享属性完整集合：

```text
-p:Mmf3ProfileAcceptanceBuild=true
-p:Mmf3AcceptanceBuild=false
-p:MainWorkspaceDev=false
-p:FrontendSkipBuild=true
-p:RuntimeFrameworkVersion=10.0.14
-p:SelfContained=true
-p:UseSharedCompilation=false
-p:MSBuildEnableWorkloadResolver=false
-p:DirectoryBuildPropsPath=S/desktop/Directory.Build.props
-p:DirectoryBuildTargetsPath=R/control/empty.targets
-p:CustomBeforeMicrosoftCommonTargets=R/control/empty.targets
-p:CustomAfterMicrosoftCommonTargets=R/control/empty.targets
-m:1
-nodeReuse:false
```

这不是“没有构建 hooks”的承诺：

- Vite 会加载固定源码的配置、React 插件及其依赖；必要原生构建依赖也属于完整 node_modules 白名单。
- Desktop 的 `BuildMainWorkspaceFrontend` 即使 FrontendSkipBuild=true，仍通过 MSBuild Exec 执行 `node scripts/assets.mjs --verify`，工作目录是 S/desktop/frontend。PATH 第一项为 R/tools/node；不执行 npm ci 或 npm scripts。
- .NET SDK/WPF 编译任务、资源工具、SDK analyzers、NuGet 包的 build/buildTransitive targets 仍会运行，属于需要审阅的固定工具输入。UseSharedCompilation=false 禁止常驻编译服务器，不等于禁用分析器。
- 已读当前 WebView2 1.0.4258.31 targets：Common.targets/wv2winrt.targets 被导入；C++ dispatch adapter 条件不适用于当前 WPF 项目，不调用 Clean。实际包仍需逐文件哈希审阅。禁止把未来其他包的未知 targets 当作已审阅。
- 不调用测试项目、旧验证脚本、安装器、jpackage、签名、发布、postbuild smoke、helper、产品 EXE 或 NativeGate。NuGetAudit=false 只明确关闭本次联网漏洞审计；不代表依赖安全评估通过。

## 路径与真实输入清单

九棵输入树分别映射到 R：PowerShell/git/java/node/dotnet → tools；前端依赖 → S/desktop/frontend/node_modules；NuGet 包档案 → inputs/nuget-feed；实际 Desktop/Core locks → inputs/locks；已固定父 POM → inputs/contracts。每个文件提供来源树、相对路径、SHA256、大小，拒绝相对路径逃逸、大小写冲突、reparse point、未知/重复条目与覆盖。

NuGet 先校验 `.nupkg` 的 SHA512 和实际 nuspec 的 ID/version，feed 必须恰好等于已审阅包清单，再 locked restore。还原只读本地 feed，包/HTTP/plugin/scratch 缓存在 R；不复制旧 obj 或其指向全局 NuGet 缓存的绝对路径。还原后核 packageFolders、已解析包内容哈希及生成 props/targets/json 的外部绝对路径。需要完整 SDK/runtime pack/依赖档案和真实 lock；缺失就停止。

Git 的全局/系统配置被定向到 R 的空配置，禁用 optional locks 和 replace objects；读取原仓库的指定 Git 对象。源码及 Desktop/Core 默认 bin/obj 位于新快照内部。dist、java-classes、runtime-assembly、desktop-publish、payload、review-envelope 都只在 R；publish 从不存在的空路径开始。所有模板/日志/归档均拒绝覆盖。

进程环境使用 R 下 USERPROFILE/APPDATA/LOCALAPPDATA、TEMP/TMP、DOTNET_CLI_HOME、MSBuildUserExtensionsPath、PSModulePath 和 NuGet 缓存，关闭相关遥测/工作负载通知/共享编译服务器。保留 Windows SystemRoot/System32 以运行系统工具与加载系统库。初始 guardian 本身由已审阅原 PowerShell 主机启动，不把已经加载的主机库说成来自 R。

**这是可信固定工具、明确路径/缓存与前后哈希校验，不是系统级文件或网络沙箱。** 无 ACL/身份/网络设置变更；不能保证系统库、恶意插件或未考虑的工具行为绝对零外部写入。路径校验和哈希也不是覆盖全流程的原子文件系统隔离。先前方案中的“执行器必须强制仅写 R”不再作为自设阻塞；这些限制必须如实报告。

最终 publish 清单不是“接受目录内所有文件”：每条只有三种来源：本轮六个允许产品输出名之一、固定输入文件、固定 NuGet 包内的精确 entry。前端清单单独取自新 dist 并比对。需要完整最终输出集合，未知 DLL/资源/多余旧文件即失败，不从旧 Desktop 填缺口。四个 Bootstrap 文件按现有 pins 复用；旧 baseline Desktop DLL/EXE 不允许覆盖新产物。

旧包固定路径为 `artifacts/PersonalAiWorkspace-knowledge-conversation-workflows-20261110.zip`，已保存 SHA256 `428d89e397dd5e156e6edfc9519a4e28c7b698bf8ce61c35b06e1b1684223c56`；执行前后计划核对该哈希及旧解包目录完整清单。本轮没有重新运行大包哈希检查。成功 worker、清理确认后才做后置复核；失败/拒绝/未知后不在 finally 盲目重读可能被拒目标，记为 NOT_RUN_AFTER_FAILURE_OR_UNKNOWN_CLEANUP。

## Guardian、输出与回滚

Guardian 在 Session 1 获取 `Local\Dot_KAG_Workspace_HeavyRun`（等待最多 60 秒），持锁涵盖输入准备、worker、清理与后置检查。遗弃锁视为历史结果未核清，停止。新 worker 先 CREATE_SUSPENDED，分配到全新 unnamed Job 后才恢复，只继承三个显式标准流句柄；不改身份、不 breakaway、不提权。Job kill-on-close、12 进程、单进程 768 MiB、总 2 GiB；worker 660 秒、单工具 300 秒、输出 drain 10 秒、清理等待 10 秒。

Guardian 的启动前拷贝/哈希和结束后哈希没有硬内核 I/O 超时；不能宣称整个入口绝对 660 秒完成。worker deadline 从受控进程创建后计时。新 Job 的 Windows P/Invoke/结构体布局和嵌套 Job 兼容性尚未执行检查。分配失败只终止那个尚未恢复的确切子进程并记录确认状态，不退回裸进程执行。已分配子树只由该 Job 清理；不扫描系统进程或端口。清理无法确认时失败，不报 clean。

每步先写 command，再写 started PID，进程退出先保存 exit，随后等待输出 drain。子任务结束不等于产品/远端模型结束；本工具不启动模型。guardian-result 与 guardian-released 分开保存；回执缺失或传输中断属于待核对，不凭缺失重跑。源码没有自动重试或自删缓存。

输出：`R/review/PersonalAiWorkspace-CANDIDATE-NOT_FOR_USE-2ca5041.review.zip`。外层只含警示 README、candidate-manifest、`payload-CANDIDATE-NOT_FOR_USE.zip`。内层含 `PersonalAiWorkspace.Desktop.exe` 等实际载荷及同样警示。内外 manifest 均声明 CANDIDATE、NOT_FOR_USE、productionGo=false、nativeValidation=NOT_RUN、fullDesktopAcceptance=false、systemSandbox=false，分别记录产品/工具 revision、输入哈希和旧包 sourceDirty。

标签及嵌套压缩包降低误点风险，但不是执行控制；解压后的 EXE 仍可运行。没有 latest/current 别名、快捷方式、安装、自启、签名或发布。旧可用包和快捷方式不覆盖。保留 R 供审阅，不在脚本里递归删除；以后若明确允许清理，仅在确认所有自有进程已结束、核对 R 的解析绝对路径/ownership 和证据保存后删除该唯一目录。源码修订可用 Git revert 回滚；不替用户处理真实数据。

## 当前验证与必须保留的未闭合项

本轮仅允许 PowerShell AST ParseFile、JSON 语法解析、Roslyn syntax tree 与 GetDiagnostics（不 Emit、不 Add-Type 新支持代码、不启动子进程、不加载产品）。这些检查不证明脚本可执行、MSBuild 命令可成功、归档可用或 Job 监护已验收。

2026-11-11 实际静态结果：Common/Guard/Build 三个 PowerShell 文件均 0 个解析错误；schema JSON 解析成功（13 个必填顶层字段）；BuildSupport.cs 的 Roslyn 语义诊断 0 errors / 0 warnings，未调用 Emit。检查用系统 PowerShell 随附的 Roslyn/reference assemblies，没有执行本次新增脚本/支持类。最终 Git diff/源码哈希也会单独记录，工具提交仅是待独立审阅的源码检查点。

独立审阅仍需：完整新增 diff；真实九树/工具版本清单；真实 NuGet locks 与包 targets/分析器/工具输入；最终 publishFiles 来源清单；零字节 BOOT.SF 的兼容保留决定；旧 finance class 来源无法证明的明确接受或后续独立重建方案。只读材料不足时保守停止，不能捏造清单或 clean 状态。

如需检查新工具执行能力，须另列并许可：①仅合成数据的路径/schema/归档检查；②用非产品合成 PE 测资源解析；③新 guardian 对一个无产品行为的独立子进程验证启动/退出/超时/自有清理；④真实已审阅输入的纯构建。上述本轮均未执行，不能未经许可把 helper 或旧受拒文本恢复流程改作 fixture。

实际产品 helper/Profile/NativeGate 准入、ACL fixture、原被拒对象登记、完整 EXE/WebView、真实模型及打包交付另有范围许可；此次工具源代码准备不解除其中任何阻断。bindCurrentOwner 含 helper/Profile/DLL/pins，不是纯 token 查询。原/替代 Profile、七个被拒目录、18765 系统查询、text-operation-recovery/Run.ps1 及等价流程继续停止。
