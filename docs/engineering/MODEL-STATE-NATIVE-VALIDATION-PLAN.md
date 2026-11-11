# 核心交付与原生候选的最小验证决定

2026-11-11；产品源码基线 `0894e25b0a74d5cce37d3d888de92ffc92518382`，安全候选 `1a50de2` + `d7b4786`。**这是静态准备方案，不是执行授权或运行回执。** 当前所有原生、ACL 夹具、打包、部署仍停止，`productionGo=false`。本轮只读报告、确切源码和旧包清单，未执行以下任何步骤，也未新增产品模块。

最短交付路径是：单独批准离线候选打包 → 明确原对象与准入读取范围 → 原生绑定及原对象构造检查 → 同包 EXE 的核心流程及退出／重开。独立 token 查询和独立 ACL 夹具是可选诊断，不能代替产品准入。真实生成还需要模型及外部任务所有权的独立决定。不要再用外围功能增加来替代这些验收。

## 已有证据与确切目标

- [修复与独立审阅](MODEL-STATE-OWNER-CONTRACT-FIX.md)：独立审阅关闭两项源码问题，仅支持源码 checkpoint，未独立执行／审阅 AST 检查器。执行方已保存 194 个 Java 源码编译、75 条警告、5 处绑定调用的符号检查；未加载产品类或 JNA。不可把这些结果当作原生验证许可。
- [原对象只读观察](MODEL-STATE-READONLY-OBSERVATION.md)：2026-11-10 22:33 的 owner SID 与当时有效 TokenUser 相符，与 Java 用户名解析不同；当时目录类型、无重解析及最终路径检查通过。这是当时的元数据，不证明原失败时刻的对象身份或根因。当前 SID 相同也不证明完整 token、权限、完整性级别或模拟状态相同；顺序检查不等于全程原子性。
- 原 Profile **P**：`C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2`。原被拒对象 **M**：`P\model-state`。P 来自既有 `knowledge-reading-desktop-20261110/prepare/owned-profile.json`，未重新访问。后续调用者若正常准入解析不到这个 P，立即停止，不换账户、不新建或改用另一个 Profile。
- 独立夹具拟定 **F**：`D:\IDEA\Daima\personal-ai-workspace\.verification\model-owner-acl-fixture-20261111-0894e25`。目前未创建或检查；只允许未来全新对象，存在即停止。它不是 Profile，不运行 Store、不包含真实凭据／模型／数据库。
- 拟定新包 **B**：`artifacts/PersonalAiWorkspace-owner-contract-validation-20261111-0894e25` 及同名 `.zip`；打包证据 **E**：`.verification/model-owner-package-20261111-0894e25`。目前均未创建或检查。新运行回执拟用 `.verification/model-owner-admission-20261111-0894e25`，与被拒脚本完全独立。

原 `SeedReading.java` 已完成合成资料／历史导入和一次删除，随后构造 M 时在根目录 `verify` 拒绝，错误 `Invalid model state ownership or type`。原流程的 `.lock`／writer 获取、`armGuard`、最终回执、EXE 未到达；目录准备已返回，是否当时新建了 M 未知。**不得重放整段 SeedReading。** 只重试已知未完成的步骤也仍需具体审批和当前状态核对。

## 分步范围、判定及副作用

| 步骤与必要性 | 精确动作、通过条件及证据边界 | 已执行／拒绝历史 | 副作用、所需决定、清理与回滚 |
| --- | --- | --- | --- |
| A：纯 token 查询；可选，建议不单独重复 | 独立小程序只查询自身当前线程 token；仅 `ERROR_NO_TOKEN` 回落自身进程，`TOKEN_QUERY` 读取 TokenUser／来源／elevation，校验 SID 后关闭句柄。不加载 Workspace/JNA/NativeGate，不打开 P/M 或其他账户 token。已有 22:33 观察足够说明待验证假设；若需当前值，可在获准的 C 进程内记录一次。 | 22:33 类似查询成功，但整份旧 ObserveModelState 还会访问 M，不能作为纯查询复跑。本步骤新程序尚未编写／执行，无本步骤新拒绝。 | 一个短命自有进程及批准证据目录内的结果；不改 token、ACL、权限或系统设置。须确认这个精确只读范围；程序先静态审阅。原始 SID 仅本地，不进 Git。句柄关闭、退出回执；不扫描其他进程。 |
| B0：独立一次性 ACL 夹具；可选 | 在 F 下只创建 `valid-dir`、`valid-file`、`invalid-dacl-file`，描述符在创建时给定；验证类型、句柄 owner／DACL 与有效 SID 比较。无产品 Gate、无 Profile、无 Store。错误 DACL 只用于新建无内容文件，不给既有对象改权限；创建/查询失败即停止。它只能证明 OS 语义和探针，不能验证候选的 Java 接线。 | 未创建／未运行，无该夹具拒绝。已通过的合成 SID 字节测试不包含这些 OS 调用。现有 `WindowsPrivateAclTest` 会进入 Gate 和多个 Store，不能冒充此独立检查。 | 新目录／三个空对象、原生句柄及回执；须单独批准确切创建 ACL。无需管理员、赋予外来 owner、模拟其他账户或修改安全设置。清理只针对本次清单中路径及 FileID 一致的对象，逐个删文件再空目录；不递归扩大，不修 ACL 以完成清理。失败则保留并报告。 |
| B1：离线候选打包；完整同包验收必要 | 现有 `scripts/verification/package-integrated-development.ps1`，BaselineDirectory 为旧包目录，OutputName 为 B 的名称，EvidenceDirectory 为 E。先审阅全脚本及其依赖、冻结产品输入及依赖清单；运行环境必须正常支持该脚本。只构建新 B，核对 JAR、前端、EXE 嵌入资源与清单一致、原生 helper/DLL 固定来源未变。不得把新 class 偷换进旧包。 | 旧包有成功打包记录；当前候选未打包。本脚本与被拒文本操作 Run.ps1 不是同一流程，仍不因旧成功自动获准。新检查若拒绝即停，不换 shell/内联重跑。 | 新编译／暂存／包和证据文件，可能产生编译缓存；无 Profile、安装、默认选择、模型或网络下载。须明确批准“仅离线打包”，全生命周期持有共享锁与自有 Job，记录缓存和输出范围。保留失败输出；回滚只放弃新 B，不覆盖旧包，不自动清理未知路径。 |
| C：真实 NativeGate/helper/Profile 准入及绑定；必要 | 用冻结候选和原 runId，在一个受控探针进程中执行公共 `bindCurrentOwner()`；保留完整 Gate，随后 `verify(M,true,owner)` 和 `verifyOwner(M,true,owner)`。准入要求 exact P、未注册为恢复目标、正常固定 helper/JNA 来源，任何失败即停止。可在额外批准的 F 中用公共 bound `directory/createFile/verify/verifyOwner` 验证一个新目录／文件，并确认错误类型、B0 的不合规 DACL 被拒；不反射绕过 Gate，不构造备用 Store。 | 原准备曾进入产品准入，最后 M 旧 owner/type 校验拒绝；新候选的整个 C 尚未执行。新证据是实际 owner 观察、候选固定 SID 修复、独立审阅及编译／接线，不是重新准入许可。 | helper 子进程、DLL 加载、P/祖先/子目录元数据及固定 payload 读取、进程期句柄保持；如调用 `profileRoot/ordinaryProfileRoot` 还会取得运行 lease，可能创建 `P\evidence\runtime-admission.lock`。须明确批准下文完整读取范围及可选 F 写入。helper 在异常时只终止自身子进程；探针退出后才释放进程期 pins。不能声称纯查询或零文件副作用。 |
| D：原 M 的一次构造；必要 | C 通过且 M 与既有 22:33 本地 FileID／最终路径证据一致后，最小探针只 `new ModelStateStore(M,P\credentials\client-token,P\data)` 并关闭。批准后先检查确切状态名：`.lock`、`active-model.json`、`active-model.pending`、`execution-guard.json`、`execution-guard.pending`、`validation-required.json`、`validation-required.pending`；禁止广泛枚举。若已有活动选择、guard/pending、未知 writer、对象变化或恢复登记，停止并报告，不清除。 | 原构造在 verify(M) 失败；根准备之前部分已执行，后半段未执行。旧只读采样不等于本次构造通过，当前文件状态未知。只允许具备新证据且正常审批通过后的一次最小尝试。 | 可能创建 `.lock`、获取 writer/文件 pins，读取已存在状态；不是只读。不新增选择／guard、不访问数据库内容。须批准原 M 重试及这些副作用；最终关闭 Store 和进程。原对象不删除，不回填旧状态，不用 Git 回滚冒充数据回滚；新锁文件可保留并记录，未知文件绝不清理。 |
| E1：同包 EXE，无模型核心流程；必要 | 对 B 内正常 Desktop EXE 使用 `--open-development-workspace-once b204c7e3cc33425e841eb1aa32d2caa2`，须先确认当前 helper 正常解析至 P，不能注入不同 root。核对同包普通 WebView/Bridge、鉴权、SQLite 与已有合成资料；一个完整“打开历史回答 → 精确旧版原文／目录跳转 → 返回 → 明确保存一条本轮合成记忆 → 托盘退出 → 独立重开读回”流程，并查看状态页。旧文本恢复拒绝套件不复跑；无模型的文本状态显示不等于真实操作恢复验收。 | 当前 B 未生成，当前候选未做 EXE。更早版本的正常 WebView/记忆读回成功，不证明新包；原 Seed 的 EXE 没启动。 | 自有 Desktop/helper/Java/WebView 子树；可能创建／写凭据、运行 lease/回执、SQLite journal及这条明确合成记录、WebView缓存／清理及日志。须批准 P 的正常应用读写和前述记录，不能声称数据库零写入。OpenOnce 不选择新默认工作区。只用自有句柄/Job监督；关闭后记录自然退出和强制清理的区别。保留合成记录ID与回执，不删整个 P。 |
| E2：真实模型核心生成及取消；完整生成验收必要，独立决定 | 只有确认确切本地模型来源、端点、身份/摘要、资源预算及外部任务所有权后，才能执行一条合成输入的真实回答/引用流程；取消/异常恢复另列确切场景。未知外部任务或迁移继续阻断。无第三方模型、Tavily、费用或数据外传授权，不能由 E1 自动进入此步。 | 本候选未执行，模型条件当前未确认。A～E1 均不能证明模型任务终结或恢复安全。 | 模型进程/请求、持久选择/guard与合成回答等真实写入；须单独批准明确对象和生命周期。本地 HTTP 完成／进程结束不证明远端任务完成；只有匹配任务的权威完成证据才能解除 guard，否则保留阻断。不能自动删除状态作为回滚。 |

### C 的准入范围必须整体评估

`WindowsPrivateAcl.bindCurrentOwner()` 先调用 `WindowsNativeGate.admit()`，后查 SID。Gate 会验证 `B\NativeBootstrap\PersonalAiWorkspace.NativeBootstrap` 的 `.exe`、`.dll`、`.deps.json`、`.runtimeconfig.json` 四文件，启动 `VerifyProfileNative`，检查 Profile/恢复登记，校验并加载 `P\jna-5.17.0-win32-x86-64\jnidispatch.dll`，反复 helper 校验和保持 DLL／Profile 句柄；精确固定哈希在冻结的 `WindowsNativeGate.java` 中。helper 检查 P 下 `credentials`、`model-state`、`data`、`webview2-profile`、`chrome-profile`、`browser-test-extension`、`evidence`、`logs` 的元数据／私有权限及根到叶绑定。它不是只读取 M。

Java 还检查 P 所在 Development 根下 `Recovery`、`Recovery\targets`、`Recovery\synthetic-targets` 及原 runId 的登记状态；`ordinaryProfileRoot()` 增加运行 lease 和 maintenance fence。遇到恢复登记，不能临时改走恢复模式。完整 EXE 会进一步使用浏览器目录内容，故 C 的元数据准入不能自动授权 E1。

历史 Crashpad 拒绝报告只写“此前 Profile 的 `webview2-profile/EBWebView/Crashpad/reports`”，本次已读材料没有绑定其绝对 Profile ID；另有已记录旧 `bf786634eabb4e5b80edeadf25cecab8/evidence/sqlite-activation` 拒绝。**不能把另一次成功读取的 `39d1…` Profile 猜成被拒对象，也不能声称已排除所有重叠。** C/E1 放行前由父会话从既有回执／历史补齐拒绝对象映射；不在磁盘探查这些目录来补证。若无法界定合法读取范围，C/E1 保持停止，不换 Profile。

普通 Profile 现有 `DevelopmentRuntimeOwner.RunAsync` 转到 `RunWorkspaceAsync`，先检查 `P\evidence\sqlite-activation`，没有迁移登记才进入 owned lifecycle，采用 `server.port=0` 和自有进程协议回传端点。E1 的批准范围必须包含这一确切登记检查；有登记则停止，不进入迁移流程。恢复激活分支仍含 18765 listener 检查。本计划只允许已确认的普通、无迁移分支，不查询 18765，不改配置绕开它，也不通过启动恢复分支隐式重试该检查。原模型任务状态与迁移所有权未知时仍阻断。

### 原对象登记与错误路径的明确取舍

原 Seed 的 `armGuard` 会把 `synthetic-reading:latest`、loopback `127.0.0.1:1/api/chat` 的合成请求写入 M 的 guard。它尚未执行，且会使模型运行处于未解决状态；**最小验收剔除此步，不执行或“补跑登记”。** 如未来专门验收阻断页面，必须另批原 M 的 guard 写入、确切完成证据与保留策略，不能为了清理测试而清除未知 guard。

上述小规模检查不覆盖真实模拟令牌切换、外来 owner 创建、所有中断/原子写故障、跨平台或同账户恶意竞态。已有合成 SID 拒绝与静态 pending 接线只支持对应结论；不为补齐这些场景索取新权限或修改身份。若要求候选达到更强安全验收标准，先单独决定可用环境和缺失案例，不能据此直接升级为生产可用。

所有获准的重运行都须在 Windows Session 1 持有 `Local\Dot_KAG_Workspace_HeavyRun` 全生命周期互斥，设置超时／自有 Job，至少记录输入哈希、目标身份、阶段结果、子进程退出和清理回执。超时或失联先核对确切自有回执，未知结果不重放；拒绝后按既有边界停止，不换工具、shell、路径或身份。新安全状态变更一律需要新的具体评估。

## 旧包与新源码的差距

现有 `artifacts/PersonalAiWorkspace-knowledge-conversation-workflows-20261110.zip` 的报告提交为 `74a947f`，目录清单实际源码为 `b4ae52e1fe537085427693b891a038969a3d8643`，Development-only、`productionGo=false`。本轮读取清单及 Git 差异，**未重哈希 ZIP 或声称其当前完整性复验**；历史大小／哈希见[交付报告](KNOWLEDGE-CONVERSATION-DELIVERY-REPORT.md)。

旧包已有资料摘录存记忆／来源回查、统一搜索、跨会话参考和手动草稿保存恢复。当前基线比它多出 38 个产品／测试源文件差异（36 个前端、2 个 Java），以下八项功能和两次安全候选提交未进入旧包：

| 后续源码 | 旧包缺少的内容 |
| --- | --- |
| `67b6b85`、`a6e71ba`、`2cc67eb` | 五项状态总览；历史消息摘录存记忆；历史消息参考来源回查 |
| `da6e3a9`、`82b31bf`、`8d991b6` | 知识回答摘录存记忆；旧回答选段加入当前会话参考；知识问题／任务切页连续性 |
| `f6d6a84`、`0894e25` | 文本操作未知状态核对；精确版本 Markdown 章节目录 |
| `1a50de2`、`d7b4786` | TokenUser 固定 owner 及 pending 文件绑定／noLinks 顺序修正 |

`f6d6a84` 的两次 PowerShell 7 实际运行结果仍保留，但其此前 Windows PowerShell 执行策略拒绝不能被后续运行洗成已授权；原 `.verification/text-operation-recovery-20261111/Run.ps1` 及等价流程永久停止，缓存未单独清点。`0894e25` 的独立类型检查和两个合成目录场景通过，不等于完整 EXE。[准确执行记录](TEXT-OPERATION-RECONCILIATION-REPORT.md)、[目录模块报告](KNOWLEDGE-OUTLINE-REPORT.md)保持有效。

## 下一次只需作出的决定

1. **首选先决定 B1：是否允许仅生成上述独立离线候选包。** 它不需要触碰原 Profile，但最新指令明确暂停打包，不能用持续开发授权代替。A/B0 可跳过，不需要用户修改 Windows 执行策略或安全设置。
2. **C/D/E1 暂不申请泛化运行许可。** 先从已有历史明确旧拒绝目标映射，再给出确切探针源码/命令、helper/payload 清单和既有自有进程监督入口供评估；普通非提升执行身份必须自然匹配 P。范围无法合法满足时，只保留源码与离线包，不能让用户手动绕过安全检查或准备替代 Profile。
3. E1 通过也只能宣布“此同包的无模型核心读写／退出重开通过”。默认入口、维护／迁移、真实模型恢复／取消和正式发布分别保留未验收；E2 待明确模型与任务所有权后独立决定。

本方案没有待审批工具请求或在途验证进程。源码回滚可用本地 Git revert 对应产品提交，安全候选若要撤回须先审阅依赖并按 `d7b4786`、`1a50de2` 逆序单独处理；本轮不实施。Git 不回滚 Profile、数据库、凭据、guard 或运行副作用。main、六份用户文档、原安全三文件及旧工件保持不变；本轮复核 ModelStateStore SHA256 仍为 `2E2A097F34A5AAE5381DBA2D125B6F64CDA48BC9747BD2A3C5106453AE6B28AE`。
