# 记忆使用记录：单条原生数据库用例执行方案（未执行）

2026-11-11。清理修复与诊断已保存于 `5f3f8e61584423abdc0259b715a9e7ea9860ef45`。本方案只完成源码入口收窄和静态审查，尚未编译/启动测试宿主，也没有得到本入口的执行授权。数据库断言仍未运行；[旧失败诊断](MEMORY-USAGE-DATABASE-BLOCKER.md)的未知根因保持。

## 选定入口与最小源码变更

复用已有 `bootstrap/tests/MemoryRecallIntegrationProbe.csproj` 与同名 Java launcher，不使用桌面 UI、PG、通用测试框架或新的产品服务。默认入口原来会执行四个场景，不能直接拿来当本次单条验证。

本次仅给这两个 launcher 增加显式 `--scenario memory-usage` 选择：C# 将 `memory-usage` 作为 Java main 参数；Java 只选择 `ConversationStoreTest#memoryUsageFindsExactVersionsPagesAndArchivedTurnsWithoutChangingHistory`，要求 found=1、passed=1、failed=0、aborted=0、skipped=0。错误参数及与 PG 模式组合先拒绝。原 NativeGate、Profile 准入、私有临时目录工厂、执行期限和进程所有权全部保留。

`PrivateTemp` 原本就先调用 `WindowsNativeGate.profileRoot()`，再通过 `PrivateMemoryDirectory.directory()` 创建 `evidence/memory-test-<UUID>`；这与直接 Surefire 的普通 @TempDir 是不同的、明确的原生夹具合同。这里不修改旧失败路径或用它冒充旧现场修复。**只有新入口的目标和副作用被明确授权后才可运行，不能用换 Profile 绕过旧拒绝。**

## 输入与执行顺序

1. 只准备必要编译输入：核对 `0a96dda` 生产源码与旧编译清单一致后复用相应 classes；重新编译修复后的 ConversationStoreTest 和 launcher。依赖保持原 `C:/Users/28421/.m2/repository`，JUnit Platform launcher 应与保存的 JUnit 6.0.3 匹配，精确文件需在准备时核对；不复制依赖以绕过原路径权限。若不能证明 classes 对应关系，报告缺口，不盲目复用或整包重建。
2. 仅编译现有 C# 测试宿主及其必要依赖，Debug、`Mmf3ProfileAcceptanceBuild=true`、`FrontendSkipBuild=true`，独立输出到 `artifacts/memory-usage-native-20261111/`。使用既有已核验前端供构建校验，不生成分发包；此入口没有 UI，产物不能宣称是最新集成桌面包。原 NativeBootstrap 四文件以原固定 hash 核对后随宿主使用，不更改 pin。
3. 将 launcher classes、产品 classes、单条测试 classes 及同版本依赖的精确绝对路径写入新证据目录的 `classpath.txt`。不修改 `artifacts/memory-usage-20261111/` 的旧编译/日志。准备完成后以实际解析到的宿主绝对路径替换下面的 `$hostExe`；它不是当前已存在的产物声明。
4. 获得下述具体执行授权后，仅从获准的同用户、非提升执行上下文启动一次。不能以切换 Explorer、shell、身份或删除环境变量绕过拒绝。重型编译/运行遵守 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun` 互斥，静态审查不占用它。

启动参数合同（**说明用途，本轮未运行**）：

```powershell
$javaExe = 'D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-knowledge-conversation-workflows-20261110\runtime\java\bin\java.exe'
$jnaJar = 'C:\Users\28421\.m2\repository\net\java\dev\jna\jna\5.17.0\jna-5.17.0.jar'
$evidence = 'D:\IDEA\Daima\personal-ai-workspace\artifacts\memory-usage-native-20261111\run-1'
$classpathFile = 'D:\IDEA\Daima\personal-ai-workspace\artifacts\memory-usage-native-20261111\classpath.txt'
& $hostExe $javaExe $jnaJar $classpathFile $evidence --scenario memory-usage
```

`$hostExe`、classpath 和全新 run-1 目录须在完成必要纯编译后具体落盘并审阅，不能直接运行这个模板。当前已纯字节核对：上述 Java/Javac 与 JNA 文件存在；`bootstrap/PersonalAiWorkspace.NativeBootstrap/bin/Release/net10.0-windows/` 的 .exe/.dll/.deps.json/.runtimeconfig.json 四文件 hash 全部匹配当前 Java/C# pin。这不证明 host 身份、准入或运行成功。

## 精确目标、副作用与退出

| 动作 | 目标与边界 |
| --- | --- |
| 新建 Profile | 已有宿主生成一个随机 ID，先将意图写入新 run-1/owned-profile.json，再经 Windows KnownFolder 和当前有效用户绑定实际 `PersonalAIWorkspace.Development/Profiles/<id>`。真实绝对路径由正常 API 确定；不借原 `b204c7e3cc33425e841eb1aa32d2caa2` 的许可。 |
| 创建私有目录 | 原有 Prepare 创建 credentials、model-state、data、webview2-profile、chrome-profile、browser-test-extension、evidence、logs；缺失的应用/Profiles 父目录也可能创建。仅新对象使用产品规定的私有描述符，已有对象校验失败即停，不改其 ACL/owner。 |
| 原生 provision/admission | 原固定 helper 的 ProvisionProfileNative 从指定 JNA jar 提取并发布固定 jnidispatch.dll；Java 多次调用 VerifyProfileNative、加载原生库、持有 Profile/evidence 的句柄。会读取有关 Recovery 注册路径的元数据及新 Profile 的维护目录，不调用恢复/激活。 |
| 租约及合成数据库 | 创建/验证 `evidence/runtime-admission.lock` 并保持句柄至 JVM 退出；工厂创建私有 `evidence/memory-test-<UUID>/data/memory.db`，执行正常 Memory/Workspace schema 初始化、索引与 SQLite sidecar 写入。用例建立 1 条记忆、2 个会话和 14 轮；核对版本、分页、归档/取消/PENDING 排除、不改历史、关闭重开后，删除本用例的合成归档会话。 |
| 不发生的产品操作 | 单条 Store 用例不启动 Spring/HTTP、WebView、AssistantApp、模型或 PG；不产生 client-token、不清理现有浏览数据、不执行 ModelStateStore writer，不读取用户数据。空目录创建不等同于这些子系统运行。 |
| 正常结束与失败 | JVM 测试正常完成后自行 System.exit，宿主读取退出码并释放句柄。现有宿主期限为 120 秒，超时或异常可 Kill 自己刚创建的 JVM 进程树，Job 使用 KILL_ON_JOB_CLOSE；helper 自有调用也有超时终止。这是测试资源清理，不能称为产品托盘正常退出验收。 |
| 保留证据 | JUnit 工厂 cleanup=NEVER，保留新 Profile、合成 SQLite 与日志；不递归删除。失败后不重用证据目录、不重新换 ID 尝试，不重放未知执行；先报告具体阶段和已有副作用。 |

## 现有授权覆盖与未覆盖项

已覆盖：源码修复、静态检查、正常本地 Git 回滚保护；本轮只做这些。未覆盖且需父任务判断/取得具体许可：创建上述新的合成 Profile（可能位于当前可写根之外）、运行新测试宿主/固定 helper/JNA、读取相关恢复登记元数据、创建私有测试数据库及租约、保留合成证据，以及超时终止本次自有测试子进程。原 Profile 两次产品启动许可不自动覆盖此路径，也不能解除历史 Profile 或操作拒绝。

任何同机身份/路径映射或准入失败仍会 STOP；当前没有新证据证明这些问题已解除。父任务若不能确认同一获准执行上下文，维持此步骤未执行；不能用既有历史 PASS 代替当前准入。无新自动审批拒绝，新增入口仅静态检查、`git diff --check` 通过，未编译/运行。下一次只执行这一条 Store 用例，不追加桌面、HTTP、PG、全测试或重复前端场景。
