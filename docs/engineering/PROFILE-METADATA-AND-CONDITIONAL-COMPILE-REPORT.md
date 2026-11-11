# 原 Profile 元数据与条件源码编译复核

后续更新：已在新增明确授权内完成 [同源候选包和精确启动范围](SAME-SOURCE-CANDIDATE-AND-STARTUP-SCOPE.md)，包括当前 Runtime JAR 和 420 项内嵌 pins；未运行产品。下文缺清单／未打包描述本报告阶段的历史状态。一次临时空白窗口的 CUA 标题比对无匹配，主机关联仍未确认。

2026-11-11；输入 HEAD `7ef181f1519829953fd638bf62c20bbafac57639`，分支 `codex/knowledge-version-compare`；产品代码仍与 `7e7332e` 相同。仓库及已核对上级未发现 AGENTS.md。本次只完成准确目标的限定只读检查、Profile 条件源码纯编译和下一步静态审阅，没有新增产品功能、重跑普通构建或启动产品。

## 准确目标与历史动作

原 Profile P 为 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2`，M 为 `P\model-state`。来源是 [原始阅读验收报告](KNOWLEDGE-READING-CONTINUITY-REPORT.md)、[原对象只读观察](MODEL-STATE-READONLY-OBSERVATION.md) 和 [原生验证计划](MODEL-STATE-NATIVE-VALIDATION-PLAN.md)。没有重新访问它们引用的旧验证目录或原 Profile 内容。

原 `SeedReading.java` 已写合成资料／历史并删除一份合成来源；随后 `new ModelStateStore(...)` 的根目录 `verify(root,true)` 报 `Invalid model state ownership or type`。这是已知产品校验拒绝。目录准备此前已返回，不能说整段 Seed 未执行；之后的 writer `.lock`、状态内容读取、armGuard、最终回执及 EXE 未到达。原 Seed 不可重放。

已有独立只读观察在 2026-11-10 22:33 成功，已读历史没有这两个准确对象的独立普通元数据操作被拒的记录。该结论只覆盖已有记录，不排除未保存历史。本次父任务明确允许在此条件下读取根和 M 的必要元数据；没有把产品构造拒绝解释为不存在，也没有取得构造／登记重试授权。

前一计划将当前环境的 LocalAppData 推导为 28421 路径；它与原目标不符，已纠正，未访问那个推导位置。当前自身 `Environment.GetFolderPath(LocalApplicationData)` 的确仍返回 28421 路径，但 Profile 源码 `WindowsProfileSecurity.Parent()` 使用有效令牌的 `SHGetKnownFolderPath` 并进行 OS 句柄绑定。这两者不能混同；本次未调用产品 Parent/Root/Pin、helper 或 KnownFolder 原生准入，不能据此断言未来正常准入将成功或失败。

## 本次实际元数据结果

证据目录为 `artifacts/profile-metadata-review-20261111-f3bd63caf10145d2979783626690aa3d`。一次普通 `use_default` 调用在 UTC `08:52:38.4279716Z` 开始，只对 P、M 依次执行 `Get-Item -LiteralPath -Force` 和 `Get-Acl -LiteralPath`，读取 owner 与普通 DACL，不读 SACL。另读自身 WindowsIdentity 的 SID／模拟级别、当前解释器和 LocalAppData 值。未枚举子项、读取内容、打开 Java、申请产品锁、写 Profile 或改权限。

| 对象 | 类型／属性 | owner 比较 | DACL |
| --- | --- | --- | --- |
| P | DirectoryInfo；仅 Directory，无叶节点 ReparsePoint | 等于本次有效主体 SID | Protected=true；3 条显式 Allow |
| M | DirectoryInfo；仅 Directory，无叶节点 ReparsePoint | 等于本次有效主体 SID | Protected=true；3 条显式 Allow |

两者的三条 ACE 均为有效主体、SYSTEM、Administrators 的 FullControl，ContainerInherit/ObjectInherit，Propagation=None；没有继承 ACE。自身 ImpersonationLevel=None。工具实际退出 0，failure=null。原始 SID 和必要账户数据仅在本地 JSON，不写入 Git 报告。`metadata-result.json` SHA256：`7b025496ba29913328280eb0429a35c79fd16542845634e71272a56a05aee292`。

这是顺序、按路径的当前观察，没有检查所有祖先、读取 FileID 或持有产品级绑定。它不证明与旧失败时刻为同一文件对象，不重现旧 Java 用户名映射，也不证明完整 Token、原生 Gate、状态文件或运行租约有效。不能把它提升为原失败根因已证实或安全候选通过原生验收。

## Profile 条件源码的实际纯编译

普通 Debug 未编入 Profile 分支，因此本次只补这一配置。先读取正式根／应用 POM、两个 csproj、`desktop/Directory.Build.props`、已有 NuGet g.props/g.targets、WebView2 managed/Common targets；MSBuild 只求值确认了两个条件标志、实际宏、独立输出／中间路径以及空 TrustedDevelopmentManifest。Profile 增加两份 bootstrap/Shared 源码、条件代码和四个 helper 文件的内容复制；不执行 helper。四文件实际 SHA256 均与当前 C#／Java 固定 pins 对应；未改 pins、关闭检查或生成替代 manifest。

新输出 R 为 `artifacts/profile-compile-20261111-7ef181f`。仅从已有成功编译目录逐字节复制两个项目各三份 NuGet 解析输入；六项复制前后哈希一致。135 份已跟踪源码／配置记录于 `source-inputs.json`，编译后哈希仍相等。使用已有 dotnet SDK 10.0.401、已解析包和已有前端 `artifacts/integration-compile-20261111-7e7332e/frontend`，不 restore、下载、重新编译前端或 Java。

```powershell
dotnet.exe build desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj --no-restore -c Debug --artifacts-path <R>/dotnet --disable-build-servers -p:FrontendRoot=<已有成功前端目录> -p:FrontendSkipBuild=true -p:Mmf3AcceptanceBuild=false -p:Mmf3ProfileAcceptanceBuild=true -p:UseSharedCompilation=false -m:1 -nodeReuse:false
```

此命令实际执行一次；工具会话 92103 退出 0，Core／Desktop **0 警告、0 错误，4.33 秒**。正式项目仍运行 `assets.mjs --verify`。Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun` 在构建期间持有，`08:55:29.4977840Z` 释放并 Dispose；无在途构建。环境变量仅限定本次 CLI_HOME／首次运行／证书／遥测／构建服务器行为，与普通纯编译一致，不改持久配置或系统权限。

回执为 R 下 `preflight.json`、`msbuild-evaluation.txt`、`resolved-inputs.json`、`helper-copy-inputs.json`、`build-start.json`、`build-exit.json`、`build-cleanup.json`、`compile-summary.json`。主要 SHA256：

| 文件 | SHA256 |
| --- | --- |
| Desktop DLL | `3f559792acaaeda1c663048231b212e3f4436f352bb725ca8e4d03b3abecec48` |
| Core DLL | `17a0d946d472243dfa1d0395a82e94fd9558d720332dea286ecec0b5c959c633` |
| 编译日志 | `172ab6155b4c434feb9545d2bc0791813598a9d52fe291de54c3cc918c5b3f1d` |

输出是 framework-dependent Profile Debug，SelfContained=false、无 RID、无受信 Runtime 内嵌清单；没有装配当前正式可执行 Runtime JAR。它是条件源码编译证据，**不是可运行候选包**。未加载或运行产品程序集、helper、Java 服务、WebView、模型；测试次数为 0。原审阅 ZIP 和普通编译输出未改。

## 下一步的确切执行面（尚未执行）

本次材料足以让父任务决定下一阶段范围，但不能自行解除原生准入、原 M 构造或候选 EXE 的停止状态。先完成同源离线运行包，再单独决定准入和一次合成联动；不要启动本次缺清单的纯编译 EXE。

| 阶段 | 确切目标／程序 | 读取、写入和限制 |
| --- | --- | --- |
| 同源离线包 | 产品源码与 `7ef181f` 相同；正式 Maven 3.9.16／原 JDK 21.0.7／原依赖；正式 Runtime JAR、Profile Debug win-x64 与 Format=3 内嵌 pins；拟输出到全新 `artifacts/PersonalAiWorkspace-knowledge-source-live-candidate-20261111` | 只写新的源码快照、构建／打包输出；无 Profile。正式 POM 的 prepare-package 会执行 `scripts/package-windows.ps1 -WriteRuntimeIdentity`，package 会 repackage 并调用 `-AuditRuntimeJar`；其中身份 writer 要求自身仓库与固定 classes 路径、通过 Git 获取源码清单。现有编译快照不能直接冒充满足该打包合同。需先闭合完整命令及这些脚本的输入／输出，不跳过 identity 或 JAR audit。旧开发打包器还会重建、写旧 target 并 Assembly.Load；本轮未执行，也不删保护分支另起替代入口。 |
| 正常准入 | 上述冻结新包的四文件固定 NativeBootstrap，命令 `VerifyProfileNative b204c7e3cc33425e841eb1aa32d2caa2`；Java 固定 `WindowsNativeGate.admit`／bound owner；正常解析必须为原 P | 需确认 P 祖先、credentials/model-state/data/webview2-profile/chrome-profile/browser-test-extension/evidence/logs 元数据；加载 P 下固定 jnidispatch.dll；检查 Recovery 登记及 `P/evidence/sqlite-activation`。如取得 ordinary runtime root，还可能创建 runtime-admission.lock 并持有租约。不是本次两个对象的只读范围；helper 失败、目标不同或有恢复登记即停止。 |
| 原 M 构造边界 | 与首次正常应用初始化合并，不另加独立测试探针；准入批准必须覆盖 ModelStateStore 根绑定／构造／关闭，不重放 Seed、不 armGuard | 先单独决定 `.lock`、active-model.json/pending、execution-guard.json/pending、validation-required.json/pending 的准确状态核对与允许读取；构造可能创建锁、读取已有状态。未知 guard、活动 writer 或对象变化须停止，不清状态、不修 ACL。当前内容未知，未探查。 |
| 一次资料出处联动 | 新包 `PersonalAiWorkspace.Desktop.exe --open-development-workspace-once b204c7e3cc33425e841eb1aa32d2caa2`，普通 owned lifecycle 的动态端口；只导入本轮合成文本、保存一条引用记忆，再独立重开原记忆回查准确 ID/revision | 读合成 Profile token／配置和已有必要应用状态，写本轮知识／记忆及 SQLite journal、运行回执、WebView 状态；无模型调用、无默认工作区选择、无迁移分支。真实列表授予读取资格后才能取详情／原版本；仅 HTTP 或 mock 不能算完成桌面联动。 |

合成输入已准备为 R 下 `synthetic-source.txt`，仅两句自有测试文字，没有导入或获得任何 documentId／revision。验收必须使用应用实际返回的 ID／revision，不填造回执。关闭页面不足以清空 Native session 的 Documents 集合，因此必要验收应包含一次实际退出与独立重开，再从正常记忆列表进入来源；同进程切页只能算页面回查。

预计不加载模型；正式 owned Java 已限定 `-Xmx384m -XX:ActiveProcessorCount=2`、启动期限 60 秒，正常 CLOSE 期限 30 秒、兜底自有子进程等待 10 秒。未来整轮建议总限 180 秒、Session 1 同一重型互斥，只用已归属 Job／句柄管理本次子树；当前没有运行测量，不能把预算写成峰值内存结果。缺图形主机身份关联时，不使用 CUA 点击；需要先获得正常工具关联证据。

运行不需要预先修改 ACL、EFS、注册表、系统策略、账户或 user.name。若正常路径要求任何此类变更，应停止并提交具体动作另行确认。允许的回滚是保留旧包、停止已确认归属的本次进程；合成数据仅可按实际 ID 经正常产品操作删除，不能删除整个 P、清未知状态或以 Git revert 代替数据库回滚。失败／未知结果先读本次准确回执，不重复执行。

历史 Crashpad 拒绝的绝对 Profile ID 在本次已读报告中仍未明确绑定。后续完整 WebView 访问范围需由父任务从已有原始历史核实，不能到磁盘扫描这些目录补证；已知 `bf786634eabb4e5b80edeadf25cecab8/evidence/sqlite-activation` 的旧拒绝也保持停止。其他七个历史验证目录、旧 Get-NetTCPConnection、旧被拒脚本及等价运行、restricted URL、KAG 均未触碰。

## 本次结束状态

限定元数据与条件纯编译均成功；无本轮自动审批拒绝、待审批请求、未知执行结果或在途产品／构建。当前仍为 `productionGo=false`、`nativeValidation=NOT_RUN`。前端、普通 C#、Profile 条件 C#、Java 编译与 REVIEW_ONLY 组装分别有证据；真实服务、桌面独立重开、可运行同源包与部署尚未完成。对话出处读取资格的既有源码缺口未在本轮扩展修复。

main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，六份原有未跟踪用户文档、旧包／回执和旧失败输出保留；无远程推送。本轮只提交本报告与计划中的准确路径更正，工件留在 artifacts，原始身份值不进 Git。
