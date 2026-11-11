# 候选输入的执行面审阅与最小后续动作

2026-11-11。基于 `e77780b794e8dec65fc9448c1e4d553b9e8c08b3`，产品仍固定为 `2ca50415cc6752288110fb31aa0abff6af7d4ed9`。本轮没有改动待二审的 Build.ps1、inputs.schema.json 和 CANDIDATE-INPUT-CONTRACT-REVISION.md。本文是计划，不是可供 Guard 接受的 inputs，也不是执行批准。

本地组件足以提出一次离线 restore 和一次独立 finance 编译的具体方案。当前缺的是两份真实锁文件、finance 新生成物和相应组装规则、最终 publish/runtime 文件白名单及执行决定。没有发现必须先安装另一套 SDK、JDK 或 Lombok 的理由；离线依赖是否覆盖最终解析图仍须由获准的单次 restore 确认，不能从历史 assets 推定成功。

本轮新增测试 0，restore/build/处理器/产品/helper/原生运行均 0。做的是保存清单的交叉阅读、固定 XML 和 ZIP/class 字节检查，没有加载产品程序集或 Java class。旧 56 项、4 个 Job 场景、6 项补充检查均未重跑，也不作为本次字节的执行证明。

## 1. 来源与构建代码执行面

完整既有路径、字节数和 SHA256 沿用 `ISOLATED-CANDIDATE-INPUT-AUDIT-CATALOG.json` 的 54 份证据。本轮新证据目录为 `.verification/candidate-action-plan-20261111-e77780b/`，索引见同目录报告对应的 ACTION-PLAN-CATALOG。读取只限保存清单中的精确路径，没有重新遍历旧解包目录或受拒目录。

### SDK 和 MSBuild

固定根 `C:\Program Files\dotnet`，host/shared runtime 10.0.12、SDK 10.0.401、引用与本机 packs 10.0.12。只有一个 SDK 版本；SDK `.version` 记录提交 `e34a38d2ae1fc26406a317517196e55c68ff83ab`。需要完整复制已固定 SDK、host、相应 shared/runtime/packs 文件树，不能只复制 dotnet.exe。编译时的参考程序集 10.0.12 与发布用 RuntimeFrameworkVersion=10.0.14 是不同输入；后者由离线包提供，不要求在机器上安装 10.0.14 shared runtime。

| 关键入口，均在该根下 | SHA256 |
|---|---|
| dotnet.exe | `21a46f1e5235cf4e844b9de5429f0e198b9c97a41f0503a66442f1d639ca3ee6` |
| sdk/10.0.401/MSBuild.dll | `22f7b95c5cc1e7287a9d561a0e88719c4d545c5f3892e3e6501051f5abcbe147` |
| sdk/10.0.401/NuGet.Build.Tasks.dll | `d595c9d8e56bdda9803356980dce715193764dd5029c8c132c47e680237de20f` |
| sdk/10.0.401/NuGet.Commands.dll | `3d1ea1e9fc18469646c2dc6b6639d91e595ce1031ac7ddc6d27cb02d196ea015` |
| sdk/10.0.401/NuGet.Packaging.dll | `634860396d6941beb5b007ff0e467e3817b4582dd8e7a15ab089fa6aec66375c` |
| sdk/10.0.401/Sdks/Microsoft.NET.Sdk/tools/net10.0/Microsoft.NET.Build.Tasks.dll | `ecb636143a6a1681670210b1ea4fd39a37e4a8df9111a96047304e3871b04e12` |
| sdk/10.0.401/Roslyn/bincore/csc.dll | `89c788f615634094cc441593d167d9fba9a9f049a5d1ddea87d4fde222d6f42c` |

`planned-input-pins.json` 附 34 个关键 host/MSBuild/NuGet/compiler 文件的路径、大小和哈希；完整闭包仍以原完整 dotnet 树为准。来源是已安装的本地 Microsoft SDK，以上是字节固定，不是本轮重新验证微软签名或逐条反编译全部 DLL 的结论。

本轮从已保存清单逐个读取并核对 SHA256，解析了 SDK 下全部 254 个 props/targets/tasks/overridetasks XML（DTD 禁用、XmlResolver=null）。`sdk-xml-action-surface.json` 保存 Import、UsingTask、Target、Exec、Delete/DownloadFile 的属性及所在目标/条件。实际执行范围按以下入口判断，导入一个文件不等于执行其全部目标：

- `Microsoft.NET.Sdk/Sdk/Sdk.props` 进入 Microsoft.Common.props 和 SDK props；Sdk.targets 选择 C# targets、SDK targets、ApiCompat、Pack、Containers 等导入。固定 Directory.Build.props/targets 和 Common 前后扩展入口；未指定 PublishProfile，不接受 `.pubxml.user` 等外部配置。工作负载解析设置 false，固定 SDKs 路径，不安装 workload。
- `NuGet.targets` 的 Restore 依赖 `_GenerateRestoreGraph`，递归处理 Desktop→Core，收集 PackageReference/PackageDownload/FrameworkReference，再调用 `NuGet.Build.Tasks.RestoreTask`。图生成也执行 NuGet/MSBuild 任务，不是仅解析 XML。其 UsingTask 包括 GetRestoreProjectReferences、GetRestorePackageReferences、GetRestoreSettings、GetProjectTargetFrameworks、GetRestorePackageDownloads、GetRestoreFrameworkReferences 和去重/诊断任务，来自固定 NuGet.Build.Tasks.dll。
- `Microsoft.NET.Sdk.FrameworkReferenceResolution.targets` 的 ProcessFrameworkReferences 位于 CollectPackageReferences/CollectPackageDownloads 之前，调用固定 Microsoft.NET.Build.Tasks.dll 的框架包/host 解析任务。ResolveTargetingPackAssets 还会在后续编译资产解析中选择引用及分析器。不能把所有 SDK tasks 归为“restore 不执行”。
- `Microsoft.NET.NuGetOfflineCache.targets` 会在相应目录存在时增加 NuGetFallbackFolder/library-packs 源。已保存的完整本机树没有这两个根；计划仍显式关闭隐式源、清空 fallback/additional project sources，避免新环境偶然扩大来源。
- 254 个 XML 中共见 7 个 Exec，属于 Run、PreBuildEvent、PostBuildEvent、旧 CrossGen 目标；固定项目没有 pre/post build 命令，Restore 目标不请求这些入口。SDK 任务本身仍可能执行文件 I/O/子进程，不能只凭 Exec 数量宣布零代码执行。
- 项目自身 BuildMainWorkspaceFrontend 在 PrepareForBuild 前，包含 npm、node assets 校验；仅 Restore 不进入它。FrontendSkipBuild=true 只跳过两个 npm 命令，**并不**跳过 node assets 校验；真实 publish 前仍要先有新生成前端。两个 acceptance 属性及固定 props 保持原语义，不另行覆盖 DefineConstants。

### 分析器与源码生成器

固定完整树含 209 个 `/analyzers/` DLL，其中 27 个非本地化资源 DLL；完整路径/哈希在 `sdk-analyzer-inventory.json`。这不等于一次编译加载 209 个分析器。

- SDK C# 路径：Microsoft.CodeAnalysis.NetAnalyzers 与 CSharp.NetAnalyzers，受固定 props 的 EnableNETAnalyzers=true 控制；VB 对应 DLL 不属于本次 C# 目标。CodeStyle targets 的启用条件仍由固定 SDK 和项目属性决定，不擅自关掉分析器换取通过。
- NETCore.App.Ref：Interop 的 ComInterface、JavaScript.JSImport、LibraryImport、SourceGeneration，System.Text.Json.SourceGeneration、RegularExpressions.Generator；是编译器侧分析/生成入口，Restore 不运行 C# 编译器。
- WindowsDesktop.App.Ref：Windows.Forms 基础和 C# 分析器；VB、CodeFix 程序集是否选入须由框架清单和编译器参数决定，不能把存在当执行。Desktop 的 UseWPF/UseWindowsForms=true；后续 build 还涉及固定 WPF markup/MSBuild 任务。
- AspNetCore.App.Ref 的 ASP.NET/Components/RequestDelegate/Configuration/Logging/Options/Validation 生成器，以及 Web SDK 分析器，已固定在工具树；当前 Desktop/Core 没有 ASP.NET FrameworkReference、没有 Web SDK，不能仅因离线 feed 有 ASP.NET runtime 包就说这些被运行。

上述是输入身份、导入条件、任务入口和阶段的源码审阅，不是全部 SDK 二进制的安全认证，也不替代未来实际编译参数的回执。已存的 5,355 项 SDK 广义代码文件清单仍保留其 HASH_INVENTORY_ONLY 历史标记；不得把它整体改成“逐项运行验证通过”。

### 八个离线 NuGet 包的 targets/tools

唯一离线来源是已审阅本地缓存中以下八个原始 archive，执行前逐字节复制到新 L 的私有 feed。来源路径规则是 `C:\Users\28421\.nuget\packages\<小写ID>\<版本>\<小写ID>.<版本>.nupkg`，不把缓存根当扫描或额外解析源。原始 SHA512、NuGet contentHash 和 SHA256 分开保存于 planned-input-pins；旧 metadata 的来源 URL 不等于本轮验证发布者签名。

| ID / 版本 | 字节数 | SHA256 |
|---|---:|---|
| DocumentFormat.OpenXml 3.5.1 | 15375355 | `71375a11a53eeb554005477ce6ca127909aebed474900e92298360b49a68307f` |
| DocumentFormat.OpenXml.Framework 3.5.1 | 1683829 | `d317d974986a90d8f6de1832a48fb1052d771ea475a37a2f4e3be85c497fa9c5` |
| Microsoft.AspNetCore.App.Runtime.win-x64 10.0.14 | 12784020 | `91638ac8382c437e71c0739a0e3123290f9bf48bcd01e616352a86add5571089` |
| Microsoft.NETCore.App.Host.win-x64 10.0.14 | 5791948 | `2a4547b0e6cf0dd15e723531daa5450d99d4e151105aec0d31f2b0c7cfb286db` |
| Microsoft.NETCore.App.Runtime.win-x64 10.0.14 | 39979900 | `a3ea61b4a2da6d28e562dd1cdacf80eb4dd6b9727b0a2b47f9d6aba90dbbcb1f` |
| Microsoft.Web.WebView2 1.0.4258.31 | 9801922 | `56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9` |
| Microsoft.WindowsDesktop.App.Runtime.win-x64 10.0.14 | 39195036 | `a6dba3ca8b8b5b7945399545d881e0ac294746a7571f2209981934d49ecfe8cc` |
| PdfPig 0.1.16 | 16710625 | `d67171846ea8c28f50359137065fec4514266d7a32b23eae6c5f2ebed8ffcfc4` |

八包均没有 analyzer 条目；只有 WebView2 有 build/buildTransitive targets（既有清单 20 项）及 tools（38 项）。其他包没有自带构建 targets；NETCore runtime 的 `tools/StandardOptimizationData.mibc` 是优化数据，并不是要启动的程序。八包的 `.signature.p7s` 存在，但本轮没有验签。

WebView2 Common.targets 哈希 `075c62422526d77cc5f57972da94a0b9b4c2bd900eb346990d66f860eec387a2`，wv2winrt.targets 哈希 `76b9e12abfff129cc4ecd285f185c1ed78beef4bace88c72f0bc7475c40666ee`。managed 路径选择 .NET 库并把原生 WebView2Loader 作为输出内容；这不是启动 WebView2。两个 Exec 属于 WV2WinRTGenerateDispatchAdapterIdl/WV2WinRTMakeProjections，依赖 WebView2UseDispatchAdapter=true 的 C++/MIDL/Cl 目标链；当前 WPF/Forms 项目未启用。`CleanDownloadContentFiles` 会删除 OutputPath 下 loader，但 Restore 不调用 Clean。`tools/wv2winrt/wv2winrt.exe`（209760 B，`e6e0e496d6d70a625caac7007e5ca3e79812a37e92ce906964e2ca42c3a2fd79`）及其依赖属于固定但本计划不请求执行的工具。没有运行任何一个包的 tools。

历史 Desktop assets 中的 downloadDependencies 正好包含表内四个 10.0.14 包（含 ASP.NET），frameworkReferences 是 NETCore.App/WindowsDesktop.App；这解释了为何离线 feed 包含 ASP.NET runtime，却不等同于 ASP.NET 编译引用。`historical-restore-graph-summary.json` 只作为历史输入线索，绝不改名为 packages.lock.json。

## 2. 两份真实锁：一个获准后的单次动作

建议先批准这个动作，再决定实际候选构建。现在没有创建执行目录、runner 或锁文件。

1. **新目录及冻结输入**：将 L 明确取为 `D:\IDEA\Daima\personal-ai-workspace\artifacts\candidate-lock-inputs-<新GUID>`，必须此前不存在。S=`L/source`，缓存=`L/cache`，临时文件=`L/tmp`，回执=`L/evidence`。采用已审阅的私有 Git 对象读取方式导出固定 2ca5041；不用原工作树、旧 obj/bin/dist 或 worktree diff，不读用户 Git 配置、hooks/attributes。导出后检查每个 blob 与提交对应，并固定 Desktop/Core csproj 和 desktop/Directory.Build.props。源码中的 helper Content 文件名只作为项目元数据，此动作不复制、读取或运行旧 helper。需要的新短 runner 单独供源码复核；不调用 Guard.ps1/Build.ps1，不伪造锁绕过门槛，不复用被拒的 text-operation-recovery 或等价流程。
2. **固定工具**：使用已固定的 PowerShell 7.6.9、Git 2.54.0.windows.1 和完整 dotnet 树的私有副本；无需复制 Node、前端 node_modules、Java 或 Maven 来生成锁。`global.json` 固定 sdk 10.0.401/rollForward=disable，MSBuildSDKsPath 固定私有 `sdk/10.0.401/Sdks`，复制完整树按保存的路径/长度/SHA256 验证。只选择桌面项目作为一个 restore 入口，由项目引用包含 Core。
3. **所有离线源**：仅 L/inputs/nuget-feed 内上述八个 archive；每包原始 SHA256 和 archiveSha512 必须相符。新 NuGet.Config clear packageSources/auditSources/packageSourceMapping/fallbackPackageFolders，只添加该绝对本地 feed；关闭 NuGetAudit，RestoreSources 也固定该 feed，RestoreAdditionalProjectSources、RestoreFallbackFolders、RestoreAdditionalProjectFallbackFolders 为空，DisableImplicitNuGetFallbackFolder/DisableImplicitLibraryPacksFolder=true。无下载补包或 ignore-failed-sources。SDK 自身引用 packs 是已固定输入，不是隐藏网络源。
4. **唯一核心调用**：`L/tools/dotnet/dotnet.exe restore S/desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj -r win-x64 --use-lock-file --disable-parallel --no-http-cache --configfile L/control/NuGet.Config -p:NuGetAudit=false -p:Configuration=Debug`，另附下一段列出的精确属性。两份锁此前必须不存在；不用 --locked-mode，也不先运行一次 restore 探路。本次仅声明动作，命令中的 L/S 必须在批准的 runner 中展开成同一个精确绝对路径，不能直接复制本文占位符执行。这些 CLI 选项及默认 packages.lock.json 位置已核对[官方 restore 文档](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-restore)。

与候选工具一致的属性为 Mmf3ProfileAcceptanceBuild=true、Mmf3AcceptanceBuild=false、MainWorkspaceDev=false、FrontendSkipBuild=true、RuntimeFrameworkVersion=10.0.14、SelfContained=true、UseSharedCompilation=false、MSBuildEnableWorkloadResolver=false；DirectoryBuildPropsPath=S/desktop/Directory.Build.props；DirectoryBuildTargetsPath 和 CustomBefore/AfterMicrosoftCommonTargets 均为 L/control/empty.targets；`-m:1 -nodeReuse:false`。额外关闭隐式源和交互、固定 RestoreSources（见上），RestoreIgnoreFailedSources=false、NuGetInteractive=false。不用自定义全局 DefineConstants；不用改变 SDK framework/analysis 属性来减少依赖。

5. **环境和资源**：沿已审阅环境清空规则，将 USERPROFILE/APPDATA/LOCALAPPDATA、DOTNET_CLI_HOME、NUGET_PACKAGES/HTTP_CACHE_PATH/PLUGINS_CACHE_PATH/SCRATCH、MSBuildUserExtensionsPath、TEMP/TMP 全部映射 L 的专用子目录。禁用遥测、首次体验/工作负载通知、MSBuild server/reuse，清除继承的插件和 MSBuild 扩展变量；PATH 只放私有工具及必要 System32。关闭 NuGet 证书在线吊销检查（offline）。使用共享 `Local\Dot_KAG_Workspace_HeavyRun` 和持久未核清状态；审核既有 candidate-build/synthetic 的精确状态后才能开始。复用已审 OwnedBuildJob，挂起创建、加入自己的 Job 再恢复，最多 12 进程、单进程 768 MiB/Job 2048 MiB，worker 恢复后 660 秒；不将准备复制/哈希及最终回执 I/O 宣称为 660 秒总入口硬限时。
6. **允许执行和写入**：允许固定 dotnet host/MSBuild/NuGet/SDK 图和框架解析任务，读取固定项目和私有包，计算 contentHash、解包和写 restore 资产。允许新 S 内各项目的 obj、两份 packages.lock.json，以及 L 的缓存/日志/临时文件/首次运行标记。无需运行 csc、前端、产品 main、helper、native gate 或测试。L/source 是新导出副本，因此 MSBuild 默认 obj/锁位置也位于 L；绝不将原项目地址传给 restore，不使用原有缓存作为 NUGET_PACKAGES，不向旧生成物写入。
7. **网络与隔离边界**：意图中的包解析只有本地 feed，audit/隐式源/在线吊销/交互关闭。该设置与 Job 不构成 OS 网络或文件系统沙箱；SDK/证书基础设施或编译工具在同一用户权限下仍有能力访问外部。不能保证 OS 级零网络/只写 L，也不为此新设 ACL/身份/防火墙要求。若实际日志显示额外源、插件、目标或外部路径，停止并保留回执，不自动换源/下载/重试。
8. **结果与失败保留**：只有一次调用正常退出、Job 活跃进程归零且句柄关闭得到确认后，才记录成功。保存完整参数、工具/源码/feed 哈希、stdout/stderr、退出码、超时/清理状态、所有新锁/资产及新 `.nupkg.metadata`。失败保留 L 原样；清理未确认留下持久 latch，下次拿到 mutex 不代表旧负载消失。不全局枚举/杀进程，不删除原工作树或旧缓存。只读核对新锁的版本/依赖图/contentHash、新 metadata 与原始 archive 的不同哈希语义、实际包集合及所有路径，再把两份原始锁按精确哈希复制到 L/accepted-locks 作为待审输入。此复制也必须有清楚回执；未审通过不交给真实 Guard。缺包、签名或解析失败直接报告，不造锁、不自动重跑。

既有工具已经提供路径/重解析点检查、固定树复制、私有 Git 对象导出、空缓存要求、严格 JSON、Job 与未核清状态等机制；这些能用于小型一次性准备流程，但当前 Build 入口本身要求先有真实锁，故不能拿它生成第一份锁。新 runner 尚未编写/批准，本文不把它说成现已实现的自动防护。

## 3. finance：独立编译处理器方案

目标是从固定源码产生新 class，避免默认复用来源无法证明的旧 finance-domain-0.1.0.jar。旧包 sourceDirty=true 不改变；其 85 个 class 仍没有 Git 来源证明。54 个 Java 文件、21 个资源和十个 Lombok 使用点的事实沿用既有证据；工作树文件 hash 只作观察值，正式执行时仍必须由固定 Git 导出证明，不能把历史 WORKTREE_BYTES_ONLY 标记改成已证明。

需要的输入都已在本地：

- 固定基线 Java 树：Oracle JDK 21.0.7+8-LTS-245，418 文件；javac.exe 为 23656 B，SHA256 `6fe0b34e9b47d255351040c9ad5920cad9c37d5a10a60916cd2c84299d0c40af`。java.exe 为 54376 B，`d82117d83c6a27cda42126bca1bf9a2dea4f92195ba9e72ced0352fb52d5be2f`；完整树和 LICENSE 继续固定，不仅固定两个 EXE。
- `C:\Users\28421\.m2\repository\org\projectlombok\lombok\1.18.46\lombok-1.18.46.jar`：2045364 B，SHA256 `01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126`。固定 finance POM 声明 Lombok processor，已保存 Spring Boot 4.1.1 dependencies POM 声明 lombok.version=1.18.46。版本来源是 POM/manifest 与字节哈希，不以 Maven 已联网或签名已验证代替证据。
- 完整 LICENSE 为 5378 B，SHA256 `76479448741d7a7a3a97b6afd9ab9699d95621faafc65a1b8e9149342ea00feb`，包含 Lombok MIT 与捆绑 ASM、cmdreader、lombok.patcher 声明。工具复制/保留时保持原 JAR 和全部许可文本，不截成一个自写 MIT 摘要。Lombok 是编译输入，不加入产品运行 classpath。[官方 Maven 说明](https://projectlombok.org/setup/maven)
- 第三方 classpath 是基线 BOOT-INF/classpath.idx 顺序中的 77 个非 finance JAR；每个路径、次序、长度、SHA256 已新存 `ordered-jar-inputs-and-signature-metadata.json`。显式逐项列入，排除旧 finance JAR，禁止 `lib/*` wildcard。这些第三方包的字节来自固定旧 ZIP；这项明确的第三方依赖来源接受决定与拒绝默许旧自研 class 来源分开记录。

单独批准后的 F=`artifacts/candidate-finance-inputs-<新GUID>` 只包含固定源码导出、完整 JDK 私有树、显式第三方 JAR、Lombok 和控制文件。输出目录固定 F/work/finance-classes、F/work/finance-generated、F/work/finance-jar-stage、F/output/finance-domain-0.1.0.jar、F/evidence 和 F/tmp。源文件通过清单生成参数文件，不让 javac 搜索源路径或从旧 target 找 class。独立生成的 lombok.config 仅设置 `config.stopBubbling = true`，阻止向用户父目录找配置；从固定 Git 清单查明本模块是否有更深层配置，发现未审配置或 import 即停止。这是明确的新编译输入，不能声称与历史隐藏配置必然相同。[Lombok 配置说明](https://projectlombok.org/features/configuration)

建议一次 javac，参数为 `-J-Xmx384m -J-XX:ActiveProcessorCount=2 -J-Djava.io.tmpdir=F/tmp --release 21 -parameters -encoding UTF-8 -implicit:none -sourcepath <新空目录> -processorpath F/inputs/lombok-1.18.46.jar -processor lombok.launch.AnnotationProcessorHider$AnnotationProcessor,lombok.launch.AnnotationProcessorHider$ClaimingProcessor -d F/work/finance-classes -s F/work/finance-generated @F/control/finance-sources.args`，classpath 为显式 77 个第三方 JAR 加 Lombok 注解定义。空目录 sourcepath 避免命令行空参数的歧义；54 个源文件全由参数清单明确提供。处理器与源列表参数均需作为字面值传递，不能让 PowerShell 展开 `$AnnotationProcessor`。

执行类范围须如实描述：两个明确处理器入口是 `lombok.launch.AnnotationProcessorHider$AnnotationProcessor` 和 `$ClaimingProcessor`；前者经 `lombok.launch.ShadowClassLoader` 进入 `lombok.core.AnnotationProcessor`、`lombok.javac.apt.LombokProcessor` 和 `lombok.javac.JavacTransformer`，再调度对应注解 handlers。六个入口/委派 class 的实际 JAR 条目、长度、哈希在 `lombok-processor-entry-pins.json`。Shadow loader 加载的是同一个已固定 JAR 内 `.SCL.lombok` 字节；不能宣称只执行两个 Java class，也不允许服务扫描引入其他 processor。[官方执行路径](https://projectlombok.org/contributing/lombok-execution-path)

八个 @Data 类型和两个含 @Getter/@Setter 的配置类型需要 AST 变换产生访问器及相关方法；服务源码确实调用这些方法，所以维持全部源码原样却使用 -proc:none 不能可靠形成 finance 新 class。单独运行该处理器不同于启动产品，但仍是执行第三方编译时代码：会初始化编译器/处理器、访问配置/类路径、变更内存 AST，并可能生成 class/资源/临时文件。采用同样自有 Job、共享锁、受控环境和有限内存/时间；它不等同于 OS 沙箱，不能保证处理器仅有 F 读写能力。只接受固定 JAR 的处理器代码，不使用 Maven lifecycle、Java agent、delombok main、IDE installer 或产品 main。

编译成功后从新 classes 和固定 21 个资源组装新 JAR，新增 manifest/Maven metadata 由已审固定内容生成并分别记录来源，不拷贝旧 class；不运行单元测试、迁移、数据库或应用。classes 数量/字节不要求与旧 85 个完全相同，差异要记录来源，不能拿旧 class 填缺。后续 Workspace 编译保持 -proc:none，并改用这份新 JAR；当前 Build 的“恰好复用一个 finance-module”规则仍须一处明确的源码/输入规则修订，不能偷偷将新 JAR 标成旧复用来源。此轮不改待审文件，也未执行 javac/处理器。

## 4. BOOT.SF 的角色和具体建议

固定旧主 JAR 为 64148868 B、SHA256 `47619ea1c1a7ce488894579f8de95725aac15cf27a01f9c257cd107f265d68fb`；META-INF/BOOT.SF 长度 0、SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。它没有签名摘要或签名块，不证明外层应用签名、发布者或 Git 来源。[JAR 签名格式](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html#signed-jar-file)

本轮精确读取本地 Spring Boot loader-tools 4.1.1 JAR（341567 B，SHA256 `b26911978c785fcb894b398c5a781e409682309bf083b9587083fdd02be06a96`），仅解析 Repackager.class 和 FileUtils.class 的字节/常量池，不通过 Java/类加载执行。保存的 Code 字节显示：Repackager 检查输入扩展名 .jar 或 .war、hasSignedLibrary=true 后，写 META-INF/BOOT.SF；对应写入 lambda 只有 return，即写零字节。hasSignedLibrary 排除 JarModeLibrary，并调用 FileUtils.isSignedJarFile；后者检查 JAR manifest 中 digest 条目，并非做密码学验签。这比将 3.5 源码冒称成 4.1.1 源码更精确；[3.5 官方源码](https://raw.githubusercontent.com/spring-projects/spring-boot/v3.5.0/spring-boot-project/spring-boot-tools/spring-boot-loader-tools/src/main/java/org/springframework/boot/loader/tools/Repackager.java) 仅为已有机制的辅助参照。

固定主 JAR 的 78 个内嵌库中，bcpg/bcprov/bcutil-jdk18on 1.86 三个同时含 manifest digest 和 BCRSA204.SF/.RSA。只读检查保存其条目名及整个 JAR 哈希，没有宣称验签成功。固定 JDK 的 lib/src.zip（44931601 B，`53458a4228000e3ac958c8915f910263e78f7dc41173f406da333cf829e9b7a0`）中 JarFile.maybeInstantiateVerifier 根据是否有 signature-related 文件决定是否进入 manifest/verifier 初始化。

因此，**建议明确保留零字节兼容性标记**，并把 provenance 写成“固定 Spring Boot 打包规则所用的零字节 marker；非外层签名”。保留不授予信任，不把三份依赖签名提升成产品签名。去除会改变 signature-related 元数据及 JDK verifier 的触发条件，可能影响嵌套签名依赖/JCE 的兼容性；本轮没有运行产品，不能断言一定失败或完全不受影响。无需为了去除一个空 marker 新增产品运行测试；父任务可据此作最小的保留决定。当前只提出建议，未改变 jarEntries 接受状态或旧包。

## 5. 交给父任务的最小下一步

优先审核上述单次离线锁动作的短 runner 与具体 L，然后只执行一次，保存真实锁和完整失败/成功回执。不要启动完整候选 build 来发现锁缺失，也不要安装更多组件。锁形成后再独立批准固定 Lombok 的 finance 编译，并明确保留 BOOT.SF；需要的 Build 小修是接受新 finance JAR 的真实来源，不是泛化打开处理器发现。最终 publish/runtime 白名单必须逐路径绑定生成产品、固定 runtime/第三方字节或七份 notice 来源，不能仅从旧 publish 复制填补，也不能把已存“旧载荷清单”冒充新输出的批准清单。

待定事项是动作/输入接受与新输出，没有事实依据要求先扩大测试。前端 Node 24.16.0 与本机 node_modules 已具备，194 个已安装包与锁版本一致，44 个缺项是其他 OS/CPU optional；真实前端编译仍未发生。旧缓存、target/bin/obj/dist、基线 ZIP、六份用户文档、冻结安全文件及 main 均保持原状；候选尚未构建、未安装、未发布，native validation 仍 NOT_RUN。

本轮有一项外部读取拒绝：web 工具打开 `https://github.com/spring-projects/spring-boot/tree/v4.1.1` 返回 `Failed to fetch restricted URL`。已停止该目标；读取可见用户历史后没有发现针对此拒绝的新授权，未改工具、路径或身份重试。此前已有的 3.5 资料与明确允许的本地 4.1.1 字节读取各自标明来源；受拒网页内容未取得。这不是本地文件拒绝，也不是已执行构建的未知结果。
