# 记忆使用记录：单场景原生测试执行与宿主修复

最新：`run-2` 在 Java 启动前以 INPUT / IOException 退出。其后检查准备回执与实际源码输入一致，但对同一 Profile 根的精确只读元数据调用遭到操作系统拒绝访问，已停止该诊断和数据库续跑。根路径比较仍只是疑点，没有证实启动器契约缺陷；详见末节。

2026-11-11。运行来源 `a8b68a123da9de8210a09e0a305d5fd14e24dfcb`，分支 `codex/knowledge-version-compare`。[审阅方案](MEMORY-USAGE-NATIVE-TEST-PLAN.md)随后得到父任务明确授权：新建专用合成 Profile、私有 SQLite、固定 helper/JNA，以及正常自有测试进程与限定超时清理；不外推原产品启动许可。

## 结果

**唯一一次宿主启动退出 10，Java 子进程尚未创建，数据库初始化及查询断言仍未运行。** 本轮发现并修复了普通宿主代码缺陷：`RuntimeProcessJob` 现有 `Create()` 与 `Create(bool)` 重载，启动器原 `GetMethod("Create", Methods)` 抛 `AmbiguousMatchException`。现改为显式 `Type.EmptyTypes` 选中原无参方法；不修改 Job、生命周期或安全边界。

这不是操作系统、NativeGate、owner/ACL 或自动审批拒绝，也不证明上轮 Surefire 初始化错误的根因。失败后没有另换 Profile/身份/路径重试，没有进行第二次测试。修复后仅重编译测试宿主，0 警告、0 错误；数据库验证继续待执行。

## 最少编译与运行证据

证据根：`artifacts/memory-usage-native-20261111/`，旧 `memory-usage-20261111/` 未改写。

| 文件 | 实际事实 |
| --- | --- |
| `inputs.json` | 281 个生产源/资源/POM 等输入与上轮保存清单逐个 SHA-256 相同；复用上轮成功编译的生产 classes，没有重编 Java reactor。 |
| `compile-classpath.txt`、`classpath.txt` | 原 JDK、原 `.m2/repository`；JUnit launcher 6.0.3。旧 test-classes 从运行 classpath 排除，新编测试类与 launcher 优先，未搬依赖规避访问限制。 |
| `javac.log`、`javac-exit.json` | 只编译 ConversationStoreTest 与 MemoryRecallIntegrationProbe 两个 Java 源文件，exit 0。没有执行 JUnit。 |
| `dotnet-restore.log`、`dotnet-build.log` 及各自 exit JSON | 仅本机原 NuGet 缓存解析；宿主与必要 Core/Desktop 依赖编译成功，0 警告、0 错误，Profile Debug 条件开启。复用上一流水模块前端，不生成新前端或分发包。 |
| `run-inputs.json` | 固定 Bootstrap 四文件哈希匹配，实际宿主/测试 classes/classpath 哈希已记录；没有 Gate 禁止的环境变量名，未清除或改写环境来通过检查。Session 1。 |
| `run-1/run-intent.json`、`host-start.json`、`host-result.json` | 一次 `--scenario memory-usage`，宿主 PID 15600，2026-11-11 12:49:33 UTC 观察退出 10，等待约 0.289 秒，外部 runner 未强制结束它。 |
| `run-1/host.log`、`failure.json` | `stage=JAVA`、`AmbiguousMatchException`；栈在 RuntimeType.GetMethodImplCommon → Type.GetMethod → MemoryRecallIntegrationProbe.Main。阶段名 JAVA 是启动意图，不代表 JVM 已启动。 |
| `run-1/post-run-observation.json` | 精确检查本轮 Profile 和固定回执路径；无 integration.log、integration-errors.log、child-result.json。结合异常位置，`Process.Start(start)` 尚未执行。 |
| `run-1/host-before-fix/` | 首次运行的宿主 exe/dll/pdb/deps.json/runtimeconfig.json 在修复重编前逐个复制并比对 SHA-256，保存 hashes.json；原失败宿主字节与日志保留。 |
| `host-fix-build.log`、`host-fix-build-exit.json` | 一行修复后仅编译宿主，`BuildProjectReferences=false`，exit 0、0 警告/错误。没有重复前端、Native 或全套检查，也没有第二次执行。 |

全部重型编译及这一次启动均使用 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun` 短互斥并 finally 释放。正式具体命令审批获准，没有新拒绝；未读取可用内存或旧拒绝目标。

## 本次已发生的副作用与清理

新合成 Profile 为 `2fd8ddc494cb45b5a7c8db284fbe386c`，实际回执根：

`C:\Users\28421\AppData\Local\PersonalAIWorkspace.Development\Profiles\2fd8ddc494cb45b5a7c8db284fbe386c`

Prepare、Provision 和 Profile pin 已返回后才进入 JAVA 阶段，因此新私有目录及固定 JNA DLL 已建立。精确只读检查确认 `jna-5.17.0-win32-x86-64/jnidispatch.dll` 存在，`evidence/runtime-admission.lock`、`data/memory.db`、`credentials/client-token` 不存在。测试临时目录工厂未运行，不存在本次 Java 初始化/SQL 行为。

宿主异常被自身 catch 处理并返回 10，主进程自然退出；外部未 kill。Profile pin 按 using 作用域释放。原 Provision helper 在继续 JAVA 阶段前已按现有代码成功退出；没有进入 Java 子进程创建/Job 分配或子进程强制清理分支，也没有取得 Java runtime-admission 租约。新 Profile/合成准备结果与回执保留，没有删除、恢复或再利用。

这次 C# Profile 准备及固定原生库 provision 成功，不等于 Java NativeGate、ModelStateStore、完整 Runtime 或真实桌面验收成功。不创建/轮换认证凭据，不修改既有对象 ACL、身份或系统设置；没有操作原 Profile `b204c7e3cc33425e841eb1aa32d2caa2`、真实数据、产品、WebView、模型或 PG。

## 交接边界

清理修复 `5f3f8e6`、单条选择 `a8b68a1` 保留。当前宿主重载修复已具备纯编译结果；再次动态执行仍须遵守既定“一次运行、失败保留、不自行轮换 Profile”边界，由父任务明确后续方案，不能悄悄重用失败目录或创建另一 ID 碰运气。没有未退出的本次宿主或在途工具任务，查询仍为未验收，`productionGo=false`。

所有修改只在功能分支本地 Git 保存，不推送；main、六份原始未跟踪文档和旧证据不改动。源码回滚不删除已保留的新合成 Profile。

## 同一 Profile 获准续跑：run-2

父任务在 `d26d2da` 后明确指示只使用已准备 Profile `2fd8ddc494cb45b5a7c8db284fbe386c` 再运行一次，不新建其他 Profile。旧宿主固定创建新 ID，无法原样满足这个指令；因此只给同一测试宿主加显式 `--scenario memory-usage --resume-prepared <run-1>`，读取首次 synthetic/owned、JAVA/AmbiguousMatchException、自然退出回执及无 Java 回执，要求新旧证据目录为同级不同目录，并核对原记录根路径与当前 Root 返回值。通过后只会执行 State.Verify/Pin，跳过 Prepare/Provision；全部 NativeGate/owner 检查仍在。还增加自有 child-start/cleanup 回执用于区分自然退出和强制清理；本轮没有到达这些写入。

这是一处测试入口调整，不是修改产品的 Profile、ACL 或身份规则。`resume-host-build.log` / `resume-host-build-exit.json` 记录只编译宿主、0 警告/错误、退出 0；没有重编依赖或 Java/前端。此前修复版的宿主五文件保存在 `host-after-overload-fix/` 并核对 hash，首次失败字节与回执仍在 run-1 原处。

运行参数是同一 Java、JNA、classpath，输出新 `run-2`，附 `--resume-prepared` 指向原 `run-1`。`run-2/inputs.json` 记录精确目标、宿主/源码 SHA-256，确认固定 helper、Java launcher/test classes、Desktop 与 classpath 保持前轮字节。没有改环境变量、另换身份或通过其他 Shell 尝试准入。

- `run-2/host-result.json`：PID 32608，2026-11-11 12:59:14 UTC 自行退出 10，约 0.118 秒，外部未强制结束；`failure.json` 仅记录 INPUT / IOException / MemoryRecallIntegrationProbe.Main。
- `run-2/receipt-recheck.json`：只读重核首次已保存回执；同级不同目录、synthetic、原异常类型、退出状态、无 Java 回执等条件均符合。新 owned-profile、child-start、integration 和 child-result 回执均不存在。
- 根据源码顺序，停止在新 owned-profile 写入之前，尚未到 VERIFY_PREPARED、helper 获取、Job 创建或 Java Process.Start。没有启动 JVM，NativeGate/MemoryStore/数据库查询均未进入。
- 当前余下疑点为 `root != preparedRoot` 字符串比对。首次 Root 在新 ID 尚不存在时返回创建意图；现在 Root 对既有目录会通过 BindRoot 取 OS 绑定结果，两次语义不同。**这是结合代码和事后回执排除后的推断；当次实际 root/比较结果未保存，不能报告某个具体重定向路径或断言原因已完全证实。** 没有通过执行 helper/产品或改用其他路径获取新结果，也没有降低此检查。
- `run-2/post-run-observation.json`：只检查本次已记录根下固定位置；runtime-admission.lock、data/memory.db、credentials/client-token 仍不存在。本次未创建另一 Profile、未重复 provision，无 Java 子进程/租约需要清理；宿主退出已确认，无在途动作。

本轮无自动审批拒绝。INPUT 检查未通过是已知失败，不能误报为 Java NativeGate/OS 拒绝，也不能说通过了原生数据库验证。单次续跑已停止；如继续，需先明确当前根绑定与原准备回执之间的实际对应，保持同一 Profile 及全部原生验证，不以第三次碰运气执行或放宽路径条件替代诊断。旧 Surefire 根因、SQLite 查询断言和完整桌面闭环继续未解决。

## 根绑定诊断停止：精确元数据读取被拒绝

父任务随后允许先查现有日志、源码和正常精确只读元数据。实际源码 `MemoryRecallIntegrationProbe.cs` 的 SHA-256 为 `26a9e5ed425d875218a15e80e5c3d9cb6fc6b3b0f81f94dca2ea674098770c3d`，宿主 DLL 为 `0cbce4a739013391c03ce7bc382064f4200c2a2ea71c71e9ab964535cc20c89c`，均与 run-2 输入回执一致。原准备根字符串长度 102，等于 `Path.GetFullPath` 的结果，无末尾或重复分隔符；这没有证明实际绑定根相同或不同。

源码中的 INPUT / IOException 可由证据目录已含 owned-profile、续跑目录不满足同级不同目录、前次 synthetic/失败/退出/无 Java 回执契约不符、或当前 Root 与 preparedRoot 不等触发。现存回执排除了前面的文件条件；Root 对已存在目录走 BindRoot，首次创建前则返回 LogicalRoot。没有当次 Root 值，不能把最后一个条件报告为已直接观察的失败。

随后仅针对原回执根，调用独立的纯元数据读取代码：CreateFileW 请求 FILE_READ_ATTRIBUTES，READ/WRITE/DELETE 共享，OPEN_EXISTING、BACKUP_SEMANTICS 与 OPEN_REPARSE_POINT；然后准备读取 FileAttributeTagInfo 和 GetFinalPathNameByHandleW。未执行产品、helper、NativeGate，未写目标或改权限。工具回执 `e97370` 退出 1，`WorkspaceProfileMetadataRead.Read(owned.root)` 抛出 `Exception calling "Read" with "1" argument(s): "拒绝访问。"`。未返回内部阶段或原生错误码，不能断言具体哪个原生调用失败。计划中的 `profile-root-metadata.json` 写入位于调用之后，未执行。

这是本次新增的 **OS 访问拒绝**，不是自动审批拒绝，也不能倒推 run-2 或旧 Surefire 的根因。拒绝后读取父线程最近消息，未发现对该拒绝操作的新相关授权证据；没有提权、替换 API/路径/身份或执行 helper 来重试。`artifacts/memory-usage-native-20261111/profile-root-metadata-denial.json` 只依据已返回工具错误补记，不再次访问目标。

当前未决边界是：无法在允许的读取范围内确认当前绑定根与原准备回执的对应，也未证明可修复的启动器契约缺陷。因此不再 provision、不改路径比较、不创建新 Profile、不第三次启动宿主。Java、NativeGate、MemoryStore 初始化及数据库查询断言仍未运行；没有新增产品/helper/Java 进程或租约需要清理。停止该验证路径，转做独立的资料逐份问答源码改进。
