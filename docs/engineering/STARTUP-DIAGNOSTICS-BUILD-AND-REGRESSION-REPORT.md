# 启动诊断的受影响编译、候选核对与纯逻辑回归

2026-11-11；产品源码提交 `d27ce7a8f1763448c5eec2561a7634071ed608f2`。本轮接续[诊断源码补齐](STARTUP-DIAGNOSTICS-SOURCE-REPORT.md)，已完成 **Desktop Profile Debug 编译、独立候选身份核对和四项纯逻辑回归**，没有停留在静态语法检查。本轮新增源码修改仅在测试项目，产品源码与该提交保持一致。

没有启动产品或使用第二次额度，没有运行 helper、JVM、模型、浏览器或 UI；未创建 Profile、访问真实 Profile 或受拒目标，未强杀、全局遍历进程、推送或部署。原产品累计启动仍 **1/2**，`productionGo=false`。

## 最小受影响编译

正常入口运行一次 Desktop `publish --no-restore`，Profile Debug／win-x64／self-contained，沿用 .NET 10.0.14、原依赖与准确的 TrustedDevelopmentManifest。使用单 worker、禁用共享编译服务及节点复用；`BuildProjectReferences=false`、`FrontendSkipBuild=true`，没有 Java、前端或 Core 重编译，也没有网络恢复依赖。前端原有资产清单核验照常执行。

构建前核对 281 个 Java 输入、161 个前端输入与旧候选记录完全相同；Core、bootstrap、项目依赖及构建约束无源码变化。复用已验证的 Core DLL／引用程序集和离线 restore 资产；当前 136 个 Desktop／Core／共享 bootstrap 输入均记录哈希，并在编译和回归后复核未变化。

本次 dotnet PID 46836，构建 UTC 16:59:54 至 16:59:59，退出 0，日志没有编译警告或错误。没有重跑三端整套构建。原 Java 生产 classes 的复用来源仍是此前生产编译完成的结果；旧 MemoryStore 测试初始化失败、未进入数据库断言的事实不变。

## 准确候选身份

新候选位于：

`artifacts/PersonalAiWorkspace-startup-diagnostics-candidate-20261111-d27ce7a/`

它包含本次诊断补丁及既有可见退出入口，**从未启动**，不是已验收发行版。旧 `422bcdf` 候选及历史证据保留，未生成 ZIP。`development-bundle.json` 的产品提交保持 `d27ce7a`；后续测试／报告提交不重写这一二进制身份。

| 项目 | SHA256 |
| --- | --- |
| Desktop EXE | `3af2f8e5d26c92c889fd5f4bd8a04edbf0e4d950db2123734cab76d57fc0288d` |
| Desktop DLL | `865545057d7f55becd9ad90e1f869bb62667eb82f7eb65fccad640de6fec0592` |
| Runtime JAR | `ed694d4bdef8972fd6f3bdae47b9b7a3e7f94d9916459a6e326802556e13620e` |
| 嵌入 Runtime manifest | `76658dd9a82776beacabea16b11a99c827a392782786acd0957ac2fb7e15f66e` |

- 933 个载荷文件完整核对；相较旧候选，仅 Desktop DLL、Desktop EXE 和 README 的字节改变，清单自身另按当前身份生成。Core、前端、NativeBootstrap、依赖、许可证及 Runtime 载荷逐字节复用，420 项 Runtime pins 全部匹配；原候选也再次通过完整性核验。
- 首轮审计把预期变化限定为 DLL／README，因 EXE 同时变化而停止，未把失败写成通过。随后只读 PE 证明 EXE 与本次 compiler apphost 输出一致，版本为 `1.0.0+d27ce7a…`；与旧 EXE 相比 `.text`、`.rdata`、`.data`、`.pdata`、`.reloc` 均相同，变化在包含版本号的 `.rsrc`。据此明确接受这三个变化文件，并完成完整核对。读取 PE 时的虚拟长度／实际文件长度及 PowerShell 类型变量冲突也只修正在审计命令中，没有改动产品或绕过校验。
- DLL 与本次编译输出完全一致。PEReader 只读元数据确认 RuntimeStartupTrace／RuntimeStartupPhase／Profile owner 存在、SafeRawCode／RawCode／DiagnoseProcessFailure 已编入，并包含 WebView 三个事件属性 getter 引用；嵌入 manifest 与准确输入文件相同。**身份审计没有加载或执行产品程序集。**

## 继续完成的独立回归

使用现有 Desktop.Tests 项目，增加 `RuntimeStartupTraceTests`，只筛选四个测试。测试宿主加载的是与候选 SHA256 相同的 Desktop／Core DLL，仅调用诊断对象，没有创建窗口、Profile、进程、端点或日志文件；不运行 AssistantApp、NativeGate 或产品入口。

1. 首次协议失败保留原阶段和未泛化的 `ROLE_REJECTED`，后续阶段／清理错误不能覆盖它。
2. 带额外私有文本的伪原因码被替换为 `UNRECOGNIZED`，本地异常及内层异常消息不进入记录。
3. 已记录成功后，后续取消不被误记为启动失败。
4. 控制台写入抛出 I/O 异常时，诊断调用仍返回，不把记录故障抛回 owner。

最后一次实际运行 **4 通过、0 失败、0 跳过，52 ms**，TRX 中仅这四个方法。未追加旧批次或完整套件。

准备过程的两个失败完整保留，均发生在测试执行之前：第一次发现新断言触发 xUnit 分析器，同时已有 PostgreSQL conversation probe 文件缺少 `RUNTIME_LIFECYCLE_PROBE` 条件，引用了仅 probe 构建才有的类型／常量。已调整断言并将该文件与其依赖、调用者采用同一编译条件；开启 probe 符号时的新旧方法 token 比对完全相同。改动仅在测试项目，不启动该 probe，也不影响产品／数据库行为。第二次缺少复用 Desktop 引用所需的 runtimeconfig、deps 和 apphost 三份侧文件；补齐本轮已编译文件的精确副本后，第三次完成四项测试。没有为此重编译产品或修改其准入。

本轮没有实际写入 Runtime 诊断文件、触发真实 STOP／WebView ProcessFailed、验证 UIA、NativeGate 或数据库业务。不能把以上纯逻辑通过写成首次启动根因已修复、WebView 事件已分类或正常退出已验收。

## 资源清理与后续边界

构建和定向回归均在 Session 1 使用既有 `Local\Dot_KAG_Workspace_HeavyRun`；各自拥有的构建／测试进程正常返回，各次当前互斥释放记录已保存。它们证明本轮资源使用的结果，**不能证明旧监督会话正常结束或原来的互斥释放路径**。

收到 KAG 资源协调更新时，本轮构建和测试均已结束：构建 PID 46836 于 UTC 16:59:59 退出 0，最后回归 PID 25616 于 UTC 17:09:39 退出 0，同次互斥释放回执为 17:09:39.2059928。没有本轮仍在运行或结果未知的工具调用。没有新的物理内存读数，不能据互斥成功声称物理内存准入通过；之后仅继续轻量报告／Git 核对。任何新的构建须满足原资源准入，不更改阈值、权限或入口，不重查此前被拒的内存目标。

旧 `desktop-1.json` 仍是 UTC 14:28:26 的启动记录，没有新增退出回执。没有已保存的旧监督 PID 可做准确只读状态检查，因此不遍历进程或重试未知会话；旧监督终态和历史互斥释放仍标记 UNKNOWN。原 UIA“否”结果未知，未重试。

下一项能判定首次失败根因的证据必须来自真实受控运行：原 Profile 正常准入、准确候选产生的首个协议阶段／原始码及 WebView 事件分类，以及正常退出回执。当前指令禁止产品重启，且上述旧监督安全条件尚未闭合，因此本轮止于这个实际运行边界；不以追加外围功能替代它，也不自行消费剩余一次启动。Native／数据库保存和独立重开闭环仍待验证。

证据目录：`artifacts/startup-diagnostics-build-20261111-d27ce7a/`。主要文件为 `source-reuse-check.json`、`build-command.json`、`build-exit.json`、`build-cleanup.json`、`compiled-diagnostics-audit.json`、`apphost-audit.json`、`candidate-audit.json`、`regression-result.json` 和 `test-results/startup-trace-attempt3.trx`；失败回执和日志亦保留。测试修复与文档进入明确范围的本地 Git 提交，六份原始文档不变。Git 回滚只涵盖源码／文档，不涵盖用户数据或凭据。
