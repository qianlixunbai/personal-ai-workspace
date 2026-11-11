# 候选构建真实输入只读审阅 — 2026-11-11

状态：INPUT PREPARATION ONLY。真实 Guard / Build / restore / publish、产品、helper、原生准入均未运行；新增测试为 0。没有候选包，也没有批准任何旧 finance 字节复用。

本轮冻结构建工具 `f6bdb9100226961c89e583527ef8a49f4e57cfa9`，产品 `2ca50415cc6752288110fb31aa0abff6af7d4ed9`。实际输入暴露了一个先前纯源码审阅未发现的 NuGet 哈希口径冲突，详见下文。未修改冻结源码，Build.ps1 SHA256 仍为 `6704344aa129cde9b01c70c3d290d486d466efb4081662490085da6386ea9ea3`。

所有逐文件路径、大小、SHA256、NuGet 包内条目和源头对应保存在仓库内精确证据目录：

`.verification/candidate-input-audit-7b04d09125794129a0f634bf7a6fd23d/`

其 `continued/` 是主证据；`pwsh-tree.json` 位于上一级。完整证据文件的路径/大小/SHA256 索引随本报告提交为 `ISOLATED-CANDIDATE-INPUT-AUDIT-CATALOG.json`。这两份报告可本地回滚；原始大清单保留在上述本地目录，不把工具二进制或缓存加入 Git。

## 九个输入树

| ID | 实际来源 | 版本、文件数和字节数 | 当前结论 |
|---|---|---|---|
| pwsh | `C:\Users\28421\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell` | 7.6.9；658 文件；256,666,658 B | 完整本地树已哈希；版本来自文件元数据，未运行版本命令 |
| git | `C:\Program Files\Git` 的 cmd/bin/mingw64/usr 和 LICENSE.txt/ReleaseNotes.html | 2.54.0.windows.1；9,305 文件；415,322,610 B | 所选工具范围完整。未读取系统/用户 gitconfig、凭据；6 个 etc/Etc 子树在下钻前排除；根 etc/dev/tmp/卸载器未选入 |
| java | `D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-knowledge-conversation-workflows-20261110\runtime\java` | Oracle JDK 21.0.7+8-LTS-245 x64；418 文件；331,142,144 B | 全部精确文件与固定 ZIP 对应并实读核对；包含 javac。没有使用 PATH 中的 Oracle javapath 启动器 |
| node | `C:\Program Files\nodejs` | 24.16.0；1,940 文件；104,706,123 B | 完整本地树已哈希；满足 package.json 引擎声明；未运行 Node/npm |
| dotnet | `C:\Program Files\dotnet` | SDK 10.0.401；6,778 文件；1,206,200,841 B | 完整树已哈希。host/fxr 与 net10 shared/ref/host packs 为 10.0.12；发布所需 10.0.14 包另外在离线 NuGet 中找到 |
| frontend | `D:\IDEA\Daima\personal-ai-workspace\desktop\frontend\node_modules` | 5,214 文件；109,306,697 B；194 个已安装包 | 全树哈希、各 package.json 版本已对应源锁；未 npm ci/install/build |
| nuget | `C:\Users\28421\.nuget\packages` 中下表八个精确 nupkg | 8 包；所有包与条目已哈希 | 只选择这些相对路径作为离线来源，不扫描用户缓存根。不代表已批准 restore |
| locks | 两个项目各自的 `packages.lock.json` | **均不存在** | 缺少真实且可审阅的 Desktop/Core 锁。旧 assets 不能代替锁 |
| contracts | `C:\Users\28421\.m2\repository\org\springframework\boot\spring-boot-starter-parent\4.1.1` | parent POM 14,470 B | SHA256 `04cc8a51d17b04d518c232ab5423fb3b9ca6c9eb95d2562800267a6bf991f953` 与固定约束一致 |

Git 排除清单记录于 `git-tree.json`，包括 4 个名称为 Etc 的时区数据目录；这是审阅选集中的明确排除，不声称整个 Git 安装树均被读取。选入工具没有跟随 reparse point；除 Git 这些明确排除项外，其他工具树 skipped=0。现有安装路径和版本元数据不是发行者身份、签名有效性或供应链认证的证明。

Java 的 java.exe 为 54,376 B / `d82117d83c6a27cda42126bca1bf9a2dea4f92195ba9e72ced0352fb52d5be2f`，javac.exe 为 23,656 B / `6fe0b34e9b47d255351040c9ad5920cad9c37d5a10a60916cd2c84299d0c40af`。完整 JDK 源树由固定 ZIP 绑定，不是仅固定这两个启动文件。

## .NET、前端与离线 NuGet

SDK `.version` 声明 10.0.401 / commit `e34a38d2ae1fc26406a317517196e55c68ff83ab`，BundledVersions.props 声明 net10 的默认配套最新运行包、ref pack 和 apphost pack 为 10.0.12；本机相应 packs 确实是 10.0.12。较旧 shared runtimes 3.1.32、6.0.36、8.0.31 也在完整安装清单中；只存在一个 SDK 目录。SDK 不是缺失，但最终输入可进一步选择实际需要的树范围，不能把安装树完整哈希冒称全部构建代码已获语义审查。

当前 Desktop `obj/project.assets.json`（47,000 B，`c722b07d4377e1d8b331cea442f2cea49c3a1913cbf23982353036e03e27e8aa`）记录四个普通包及四个 10.0.14 downloadDependencies。Core assets（21,632 B，`b0b91623c2c02448695e1674595f65181532d9e0e718e069b9c79f57d93fe332`）无普通包，并记录三个 10.0.14 运行包。这些是历史本地线索，不是本轮 restore 结果，也不证明将来冻结源码的最终解析图。

所有 nupkg 路径均为上述 NuGet 根下的 `<小写ID>/<版本>/<小写ID>.<版本>.nupkg`：

| 包 / 版本 | 字节数 | 压缩包 SHA256 |
|---|---:|---|
| DocumentFormat.OpenXml / 3.5.1 | 15,375,355 | `71375a11a53eeb554005477ce6ca127909aebed474900e92298360b49a68307f` |
| DocumentFormat.OpenXml.Framework / 3.5.1 | 1,683,829 | `d317d974986a90d8f6de1832a48fb1052d771ea475a37a2f4e3be85c497fa9c5` |
| Microsoft.AspNetCore.App.Runtime.win-x64 / 10.0.14 | 12,784,020 | `91638ac8382c437e71c0739a0e3123290f9bf48bcd01e616352a86add5571089` |
| Microsoft.NETCore.App.Host.win-x64 / 10.0.14 | 5,791,948 | `2a4547b0e6cf0dd15e723531daa5450d99d4e151105aec0d31f2b0c7cfb286db` |
| Microsoft.NETCore.App.Runtime.win-x64 / 10.0.14 | 39,979,900 | `a3ea61b4a2da6d28e562dd1cdacf80eb4dd6b9727b0a2b47f9d6aba90dbbcb1f` |
| Microsoft.Web.WebView2 / 1.0.4258.31 | 9,801,922 | `56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9` |
| Microsoft.WindowsDesktop.App.Runtime.win-x64 / 10.0.14 | 39,195,036 | `a6dba3ca8b8b5b7945399545d881e0ac294746a7571f2209981934d49ecfe8cc` |
| PdfPig / 0.1.16 | 16,710,625 | `d67171846ea8c28f50359137065fec4514266d7a32b23eae6c5f2ebed8ffcfc4` |

缓存 metadata 的 source 均声明 `https://api.nuget.org/v3/index.json`；这只是本地 metadata 的来源声明，未通过网络重新获取或验证包。八个包均有 `.signature.p7s` 条目，已记录大小和哈希，**没有验证签名**。

### 新发现的实际阻断：两种 SHA512 不能共用一个字段

`Build.ps1` 的 Assert-NuGetInputs 对 nupkg 完整字节求 SHA512，并与 `nugetPackages[].sha512` 比较；Assert-RestoredDependencies 又拿同一字段与 assets 中的包内容 hash 比较。当前四个普通包的这两种值全部不同。例如 OpenXml：

- 完整 nupkg SHA512 与 `.nupkg.sha512`：`KujkUMySBytPfAHwwXnU0z8LvCLWUjG3kGqeO8xMb2fSmVaOcF7IAnUNXMrjvxiAO5DvCEpxAUUEVzJxBiYG9g==`
- assets 与 `.nupkg.metadata.contentHash`：`zxdOf5VVCe/uNklbRhj8dVBzQGj3DoqkUuqOp9cAZVuN8mNYDjof1lvSQA2OQNr8Ptc9d7pbA7Azq/ReaI3FpA==`

NuGet 的 contentHash 排除签名元数据，区别于原始 archive hash；官方设计说明见 [Nupkg Metadata File](https://github.com/NuGet/Home/wiki/Nupkg-Metadata-File)。此处使用两套本地独立字段对应的事实确认口径差异，没有把差异误判为包损坏。也没有独立重算 NuGet 的签名无关 contentHash。

最小后续修正应明确区分 archive 身份与 NuGet contentHash：保留已经存在的树内原始 SHA256 绑定，用内容 hash 核对锁/restore；如仍保留 archive SHA512，使用独立字段。不可把 contentHash 改成原始 hash 来伪造锁，也不可删除 archive 字节绑定。完整八包四列对照见 `nuget-hash-comparison.json`。**本轮只报告，未修改 f6bdb91。**

### 构建代码审阅范围

完整 .NET 树中有 5,355 个命中构建代码规则的文件；八个 NuGet 包有 755 个命中的条目（包括普通 DLL，不只是 targets）。逐项路径/字节/hash 已保存。NuGet 八包内 analyzer 条目为 0；SDK 自带 analyzers 在 .NET 清单中，不能因此声称“没有 analyzer”。WebView2 有 20 个 build/buildTransitive 等条目和 38 个 tools 条目；NETCore runtime 有 1 个 tools 条目。

实际 XML 解析禁用 DTD/外部解析器，记录了 imports、Exec、Delete 等动作及父 target 条件。WebView2 入口导入 Common.targets，后者导入 wv2winrt.targets；含两个 wv2winrt Exec 和 CleanDownloadContentFiles 中的 Delete。wv2winrt 接入 BeforeMidlCompileTargets / BeforeClCompileTargets 受 WebView2UseDispatchAdapter 条件控制，当前 WPF/WinForms managed 项目未启用该 C++ 路线；Clean 未被请求。这是当前显式源码的静态路径分析，未做 MSBuild 求值，不保证任意属性覆盖后的执行图。

`dotnet-build-code-inventory.json`、`nuget-build-code.json` 与 `nuget-targets-action-surface.json` 是可逐项审阅的材料，不是已填写理由并批准的 `buildCodeReview`。完整 SDK 信任范围、每项适用理由和最终输入仍需父任务确认。没有用 post-restore 文件检查替代上述审阅。

前端源锁为 v3，239 个 package 条目包含根；installed lock 有 194 个包。读取了 194 个精确 package.json，版本全部与两份锁相符，没有 metadata 读取失败。缺少的 44 项全部 optional 且 OS/CPU 不适用当前 Windows x64。主版本包括 React 19.3.0、TypeScript 7.0.2、Vite 8.3.2、plugin-react 6.1.1；这些声明及全部 package.json/scripts 已记录。没有重算 npm tarball integrity，也没有把解包后文件 SHA256 当作 registry tarball SRI 验证。

## 基线、Java/JAR 与 finance 事实

固定 ZIP：`artifacts/PersonalAiWorkspace-knowledge-conversation-workflows-20261110.zip`，384,773,584 B，SHA256 `428d89e397dd5e156e6edfc9519a4e28c7b698bf8ce61c35b06e1b1684223c56`。哈希一致后才读取 ZIP 内容。

ZIP 有 934 个文件；development-bundle.json 列出 933 个文件，逐项大小/哈希全部一致，唯一额外条目就是 manifest 本身（165,336 B；`fe9e5441a38ece06d96441802fb701ddd9bbfb06f0e03c3f8bffcc9ea121c2fe`）。manifest commit 为 b4ae52e，**sourceDirty=true** 原样保存。

随后仅按这个固定 ZIP 的名字对旧解包目录作 425 次精确文件读取：418 个 Java 文件、3 个 manifest/JNA/主 JAR、4 个固定 Bootstrap。全部大小/hash 与 ZIP 相符；未枚举旧目录、未哈希未知文件、未下钻未知目录。这个步骤与最初主 inventory 回执的 `oldUnpackedTreeRead=false` 有明确时间界限：该字段只描述前一次 inventory；后来的白名单读取另见 `baseline-selected-physical-files.json`。

- 主 JAR：64,148,868 B；`47619ea1c1a7ce488894579f8de95725aac15cf27a01f9c257cd107f265d68fb`。
- JNA 5.17.0：2,002,589 B；`b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0`。
- 主 JAR 中有 78 个 `BOOT-INF/lib/*.jar`，包括 finance。各条目及 boot loader/service 均已列 hash，尚未作为复用白名单批准。
- `META-INF/BOOT.SF`：0 B；`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。零字节标记不提供签名/来源证明。最小决定是明确接受保持该空标记的旧字节，或审阅删除标记的工具修订；本轮未选择。
- `BOOT-INF/lib/finance-domain-0.1.0.jar`：194,972 B；`f3ed62f7b5b493dc9ba5b3d644d3b21441ea98bbf3ce973c1dea1e315f5603e5`。109 条目=85 个 class、21 个资源、3 个 manifest/Maven metadata。21 个资源与精确当前源码资源逐字节一致；POM/metadata 表明模块和版本。**这些不证明 85 个历史 class 来自固定 Git 源码。**

finance 当前本地源码有 54 个 Java 文件，其中 10 个引入 Lombok；全部已记录 hash，未编译。父依赖 POM 声明 Lombok 1.18.46，本地精确 archive 为 `C:\Users\28421\.m2\repository\org\projectlombok\lombok\1.18.46\lombok-1.18.46.jar`，2,045,364 B，SHA256 `01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126`。未加载/运行 processor。

父任务的最小 finance 决定：明确接受上述固定历史字节及 sourceDirty=true 的来源局限；或另审一个从固定 Git 54 源文件、21 资源、精确依赖 classpath 和明确 Lombok processor 输入重建模块的独立方案。后者需要新增受审构建步骤；现有 Workspace 的 `-proc:none` 不会自动重建 finance。不能改用未知历史字节，也不能称“哈希一致就来自 Git”。本轮 resource/source hash 是工作树事实，固定 Git 一致性采用前轮独立源码审阅记录，未伪称本轮新做 Git 源码追溯。

## publish/runtime 白名单草案与实际缺口

`publish-origin-proposal.json` 从旧包分出 508 个非 MainWorkspace/runtime 条目：490 项已找到 NuGet 或完整 .NET 输入中的同 hash 源，4 个 Bootstrap 与固定 pins 一致，5 个受保护产品文件必须重建，9 项声明文件随后对应到精确源码或 frontend/LICENSE，9 项全部字节一致。这只是字节源对应，不是最终 TFM/RID 解析或许可批准。

`publish-scope-proposal.json` 进一步区分：

- 501 项 publish 草案（含 5 个新生成产品文件、4 个固定 Bootstrap、项目声明文件和依赖）；每个静态项均有来源候选。MainWorkspace 文件名由新的前端输出 manifest 追加。
- 7 项额外打包声明：dotnet-desktop-LICENSE、dotnet-runtime-LICENSE、dotnet-runtime-NOTICES、WebView2-LICENSE、WebView2-NOTICE、react-LICENSE、react-dom-LICENSE。历史脚本通过 Copy-DevelopmentNotices 加入，它们不是当前 csproj publish 内容；冻结候选 Build.ps1 也没有调用或实现这一步。原始字节来源已定位，但必须审阅如何纳入新打包流程。直接把旧包 508 项都放进当前 publishFiles 会把声明文件缺失误当成 publish 故障。不得从旧 publish 目录补拷产品文件。
- `runtime-proposal.json` 有 419 个静态运行文件（418 Java + JNA）；新主 JAR 另行生成。所需 java.exe/server JVM/modules/release/LICENSE 均在固定树中。

这些 JSON 明确标为 PROPOSAL，不符合可执行 inputs 文件的承诺，不传给 Guard。还需选择存在多个同 hash 来源时的确切 NuGet 条目/TFM/RID，以及源码声明映射；不能以历史文件名推定新 publish 一定产出相同布局。

## 运行记录与下一步

只读命令包括 Get-Command/FileVersionInfo、精确 Get-Content/Get-FileHash/ZipArchive、有限已知工具/源码根清单，以及 Git 状态读取；没有运行任何版本查询二进制、产品/helper、旧测试入口。对已冻结工具仅复核一个文件 hash 和 Git 清洁状态。

本轮审阅辅助脚本不是测试：主 inventory 初次已完成 pwsh 哈希后在 Measure-Object 汇总处失败，锁已释放；保留原脚本/回执，续作复用 pwsh 清单，未重复哈希它。摘要脚本第一次仅语法解析失败；修正后已保存映射与前端审阅结果，解析带 BOM 的 XML 字符串时失败；后续仅对已保存文本去除字符串起始 BOM 并继续分析，未改原包或重新跑 inventory。最早工具路径查询的两次 PowerShell 语法失败也未执行操作。失败不是拒绝，没有换 shell、身份或执行策略绕过。原始脚本版本与已产生回执均保留。

主 inventory 与后续固定 425 文件核对分别取得并释放 `Local\Dot_KAG_Workspace_HeavyRun`；主 inventory 约 19.4 秒。额外元数据/清单分析无产品进程。访问拒绝=0、未知动作结果=0、下载/安装=0、restore/build=0、新增测试=0。原 56 断言/4 Job/6 补充函数检查一概未重跑；不能把它们归于本轮或声称最终字节曾全套运行。

可执行下一步按依赖关系：

1. 父任务审阅 NuGet archive/contentHash 字段分离的最小工具修订；同步决定 7 份声明的显式受审组装方式。不改产品行为、原生 pins 或固定安全源码。
2. 明确 BOOT.SF 与 finance 复用决定，或单独审阅 finance 重建步骤及 Lombok 输入。
3. 审阅本地 SDK/工具/依赖信任范围及构建代码清单；取得真实 Desktop/Core locks。若需要首次离线 restore 生成锁，必须作为新的明确范围单独审阅；本轮没有授权也没有执行它。
4. 完成唯一的源条目选择、最终 publish/runtime 白名单及真实 inputs。再由父任务评估是否允许真实 Guard/Build。当前报告、候选标签或已有静态审阅均不授予该权限。

main、六份用户文档、旧包、源数据、既有缓存及所有历史回执均保持原样。没有触碰 KAG/personal-finance-OS、原/替代 Profile、被拒目录、系统端口或任何原生准入，也没有推送。
