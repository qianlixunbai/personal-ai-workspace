# 记忆使用记录：单场景原生测试执行与宿主修复

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
