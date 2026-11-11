# 当前源码整合与最小纯编译

最新更新：当前源码的 [Java 完整 reactor 离线编译](JAVA-OFFLINE-COMPILE-REPORT.md)已通过正常具体命令审批完成，Finance／workspace-application 均 SUCCESS，退出 0，75 条内部 API 警告；原依赖路径不变，前轮失败输出保留。前端、普通 C# 与 Java 均有实际编译证据，真实服务、桌面及新包仍未验收。下述 Java 失败／未执行说明是历史状态。

后续更新：Java 正式构建的输入与独立输出已闭合，并执行了一次离线 Maven compile；Finance 生成 class 后在 JDK 关闭 JAR 时的 `toRealPath` 操作遭访问拒绝，应用模块跳过。详见 [Java 实际构建记录](JAVA-OFFLINE-COMPILE-REPORT.md)。下文“Java 未执行”保留为此前整合阶段的历史状态。

日期：2026-11-11。首次实际编译源码：`27db39f1636ab63795e716bcd3fe228e3ac603b3`，分支 `codex/knowledge-version-compare`。未发现仓库或上级目录的 AGENTS.md；保留 main 和六份用户原有未跟踪文档。

## 本轮已取得的证据

此前仅有前端类型／合成场景证据的批量导入、检索工作集文件、逐份问答、问答恢复与历史查找，现已一起通过正式桌面项目的普通 Debug 编译。Core、Desktop 均 **0 警告、0 错误**，退出 0，用时 4.19 秒。没有为通过编译修改产品源码、依赖版本或安全门禁。前端 `tsc --noEmit`、Vite production bundle、资源清单生成及正式 csproj 内 `assets.mjs --verify` 均通过。Vite 报告单个 JS chunk 大于 500 kB；独立输出目录在前端根外，没有清空任何旧目录。

所有新输出位于 `artifacts/integration-compile-20261111-27db39f`（下称 R）。原 target/bin/obj/dist、旧包、真实资料及缓存未被构建覆盖。旧活动标记的独立归档见 [中断记录](CANDIDATE-LOCK-INTERRUPTION-20261111.md)，不能视为候选构建或原生准入获准。

## 正式入口、执行面与输入

- 本机 `C:\Program Files\dotnet\dotnet.exe`，SDK 10.0.401，满足根 global.json 的 10.0.100 / latestFeature。当前两个 csproj、desktop/Directory.Build.props、已有 NuGet g.props/g.targets 和 WebView2 targets 已读取核对。
- 当前 Core 无外部包；Desktop 的 PdfPig 0.1.16、WebView2 1.0.4258.31、OpenXml 3.5.1 与已有 project.assets.json 一致，包含 net10.0-windows target。缓存和 SDK 是已有本机输入，不是新下载或候选包的来源认证。
- 将两个原 obj 中各自的 `project.assets.json`、`.csproj.nuget.g.props`、`.csproj.nuget.g.targets` 逐字节复制到 `R/dotnet/obj/<项目名>/`，复制前后 SHA256 相等。没有修改解析结果或伪造 restore/lock。MSBuild 只求值的输出保存为 `R/msbuild-properties.json`，确认包根、独立中间／最终目录、原 csproj 及两项验收标志 false。
- 初次尝试只求值的相对 `MSBuildProjectExtensionsPath=obj\` 没有导入 NuGet props（NuGetPackageRoot 为空），因此没有用它编译；最终使用 SDK 原生 artifacts 布局和上述原字节解析输入。没有改写项目入口或替换 targets。
- 前端复用当前 node_modules，执行 package.json 中相同的类型检查、Vite 和资产脚本。Vite 只把输出参数指向 `R/frontend/dist`；资产脚本原字节复制到 `R/frontend/scripts/assets.mjs`。通过 csproj 的 `FrontendRoot` / `FrontendSkipBuild=true` 使用该独立输出，仍执行原清单校验；未执行 npm ci。
- 进程环境仅本次 shell：`DOTNET_CLI_HOME=R/dotnet-home`、`DOTNET_GENERATE_ASPNET_CERTIFICATE=false`、`DOTNET_ADD_GLOBAL_TOOLS_TO_PATH=0`、`DOTNET_CLI_TELEMETRY_OPTOUT=1`、`DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1`、`DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE=1`、`MSBUILDDISABLENODEREUSE=1`。第一次只求值时 CLI 提示未能添加全局 tools PATH，之后明确禁用该尝试；没有修改系统 PATH、证书或 ACL。
- 编译在 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun` 独占窗口内执行，结束后释放。只启动构建工具，不运行产出的程序集、产品、helper、模型或原生准入。没有调用旧候选 runner/Build/restore。

实际构建命令（R 为上述唯一绝对目录；前端前两条在 desktop/frontend 执行，资产脚本在 R/frontend 执行，dotnet 在仓库根执行）：

```powershell
npm.cmd run check
node.exe node_modules/vite/bin/vite.js build --outDir "$R/frontend/dist"
node.exe scripts/assets.mjs
dotnet.exe build desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj --no-restore -c Debug --artifacts-path "$R/dotnet" --disable-build-servers "-p:FrontendRoot=$R/frontend" -p:FrontendSkipBuild=true -p:Mmf3AcceptanceBuild=false -p:Mmf3ProfileAcceptanceBuild=false -p:UseSharedCompilation=false -m:1 -nodeReuse:false
```

初始回执：`R/frontend-check.log`、`R/frontend-build.log`、`R/dotnet-build-initial.log`、`R/dotnet-build-initial.exit`。初始 Desktop DLL SHA256 `c194c716ff0197425121d8dd7b301fb67db66c197f36177aacfcf205168064cf`；Core DLL `c34577decac4cdcec3679b176518fc62d7ec29bb4f231ec3628be182cce82624`；前端清单 `0870702cac9089414043e38a829963a4c65ab5f5a9ee979dce6707f760a964db`。这些都是普通编译输出，未发布、未安装。

## Java 的具体缺口

静态确认 `GET /api/v1/knowledge/answer/tasks/search` → KnowledgeAnswerHistory.search → C# SearchKnowledgeAnswersAsync → Native `knowledge.answerHistorySearch` → TypeScript 方法／校验器有对应接线，保留 nativeOnly、准确 query、20 条分页及原历史页授权。单资料问答后端合同没有被前端多资料串行功能扩大。

正式 Java 入口是根 Maven reactor（finance-domain → workspace-application），不是旧候选工具。最短普通命令为 `./mvnw.cmd -o -pl workspace-application -am -Dmaven.test.skip=true compile`，**本轮未执行**。本机存在 Maven 3.9.16 wrapper 分发和 Spring Boot 4.1.1 父 POM；不存在本地仓库的 `io.github.qianlixunbai/finance-domain/0.1.0` JAR，不能单独编译应用而假定 Finance 已安装。

仍需满足的条件明确限定为：

1. 当前正式 Finance 编译会执行父依赖管理指定的 Lombok 1.18.46（maven-compiler-plugin 3.15.0）；按既有边界，需闭合这次处理器、配置、源码与依赖输入。未应用的 finance-source-selection.patch 和旧 candidate 输入清单不能作为此次已获准执行的证明。
2. 正式 POM 把应用输出固定到根 target、Finance 默认写自身 target。要保留旧输出并做到独立目录，需先确认／提供正式 Maven 支持的目录配置，不能假设 `-Dproject.build.directory` 能覆盖固定 XML，也不调用旧 wildcard javac 命令或用来源未证明的旧 Finance class 填充。

这是 **未执行、待输入与输出条件闭合**，不是“所有 Java 编译被禁止”，也不是本轮新的审批拒绝。没有 Java 编译结果或实际 HTTP/数据库结果；后续只围绕这两个条件推进，不再扩大验证设施。

## 验收边界

普通 Debug C# 编译未启用 MMF3/Profile 条件源码；不证明这些配置已编译，也不证明原生文件选择、真实 HTTP、重开持久化、WPF/WebView 交互或模型链路通过。Java、真实后端、实际桌面、打包与候选部署仍未完成；`productionGo=false`、`nativeValidation=NOT_RUN`。图形主机身份未知及既有具体拒绝保持原边界。

## 资料出处修复后的最终整合

产品提交 `7e7332e` 修复资料出处读取资格后，类型检查和三个直接相关场景通过，详见 [出处接线记录](SOURCE-REOPEN-AUTHORIZATION-REPORT.md)。没有继续追加未编译功能。基于这个已提交且产品源码无未提交差异的版本，在全新 `artifacts/integration-compile-20261111-7e7332e` 目录复用上文相同正式构建命令，完成一次必要的最终前端 bundle、资源清单及 Core／Desktop 整合构建：退出 0，**0 警告、0 错误，4.08 秒**。未重复已通过的类型／测试场景。

最终目录保留 `source-head.txt`、`frontend-build.log`、`frontend/dist/workspace-assets.json`、`dotnet-build.log`、`dotnet-build.exit` 和普通 Debug 输出。最终前端清单 SHA256 `dcd0c7bd67232aa268315276b9fbd2d0b7fd027ca902a0d884bc2685d3872071`；C# 日志 SHA256 `1726b9d188b8611816255e260de79fc459e15f6380e051b2f9f93b9fe99adf79`。先前 `27db39f` 输出及原有 target/bin/obj/dist 保留，最终普通构建没有启动产品或替换旧包。

当前剩余接线缺口仍包括 `ConversationSourceReview` 经 `readConversationSource` 直接读取正文 ID，未先从会话列表取得重开后的临时资格；本轮没有修改这条链路。下一步优先闭合上述 Java 正式纯编译条件和实际可运行关键链路，避免把未原生验收的源码积累写成桌面交付。本轮没有新的自动审批拒绝、未知执行结果或在途构建进程；不以提交数冒充三个新产品模块。
