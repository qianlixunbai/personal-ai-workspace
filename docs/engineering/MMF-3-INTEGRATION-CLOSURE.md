# MMF-3 Integration Closure — 集中集成候选

2026-10-09（Asia/Shanghai）。依据本轮 **Architecture Guard: LIMITED INTEGRATION GO**。

**集成候选完成，构建、聚焦回归及一次全新隔离预检通过；最终集成审查 PENDING。**
**Development-only — NOT PRODUCTION TRUSTED。Runtime/GUI Execution 尚未批准；Model Management Overall: NOT CLOSED。**

本轮没有启动 Workspace Java Runtime/Spring、GUI 或 Chrome，没有 HTTP/模型请求、共享 Ollama 操作、真实用户数据读取/迁移或生产状态修复。独立验证 JVM、编译器和测试宿主确实执行过，不能把它们称为真实 Runtime 启动验收。

## 1. Git 与批准基线

| 项目 | 实际结果 |
| --- | --- |
| 分支 | `codex/mmf3-runtime-private-acl-fix` |
| HEAD / fresh origin/main | `cb36879d93aa9a23c8b3d94e9fa26f5166a3c659` |
| fresh fetch | 指定命令级 `127.0.0.1:7890` 代理 fetch 成功，无 fallback/global Git 或系统代理修改 |
| Java ACL 批准候选 | 原始 patch SHA-256 实际复核为 `945fc7a9d5d216f5e6ee658fffa96ca5aff36769262bf43948ae5403e7a6f2d7`；该值是补丁身份，不是 commit ID |
| 入场 | 已有未提交 ACL、Bootstrap 和历史报告，均保留 |
| 交付 | 工作树有候选修改和 untracked 文件；index 空；未 stage/commit/push/merge/tag/rebase/reset |

已检查用户 AGENTS、适用磁盘路径、current architecture、ADR-002/015、Profile/Bootstrap/ACL 报告和实际 owning code；没有发现额外磁盘 AGENTS。
本轮仅新增这一份综合报告，不改写历史报告、ADR 或 STATUS。

入场候选中只有 `Program.cs`、`TrustedNative.cs`、`WindowsPrivateAcl.java`、`WindowsPrivateAclTest.java` 改变。八个既有 tracked ACL 候选文件和其余 Bootstrap 文件逐 SHA 保持；Profile 的标记状态合同与 HEAD 相同。
证据：[入场状态](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/git-entry.json)、[保留检查](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/preservation.json)。

## 2. 实际源码修改

| Owning 文件 | 修改与最终行为 |
| --- | --- |
| Bootstrap `Program.cs` / `TrustedNative.cs` | 增加窄 `ProvisionProfileNative` / `VerifyProfileNative`。从当前有效 token 的 Profile API 派生已准备的 `.personal-ai-workspace-mmf3-tests/<run-id>`；验证 private parent/root/八个目录，只显式创建新的 native 子目录。拒绝 elevated Profile 调用，不创建/修复领域目录、凭据或既有 native。旧两个命令及其 JSON 字段保持。 |
| `WindowsNativeGate.java` | JDK-only admission 先检查开发 opt-in、固定 Helper 完整 payload、环境/agent/配置覆盖，再直接调用本次子进程 Verify。验证固定 DLL resource/hash、精确 JNA properties；持有 READ_DATA/no-write/no-delete 句柄后再次 Verify、初始化 JNA、枚举本 JVM 实际 module 并比较路径、file ID/hash，再次 Verify 后才发布 admission。没有 PASS 文件、持久票据、默认解压或系统搜索回退。 |
| Java application main / `WindowsPrivateAcl.java` | main 第一步 gate，随后只接受同一 Profile 的固定 18765/address/token/data/model-state 参数，然后才有 Spring 调用。ACL 实际操作在 `NativeAcl` static 初始化前也执行 gate，直接调用不能绕开可信加载。Windows 判断使用实际 JDK filesystem，覆盖 `os.name` 不能避开 gate。 |
| Profile Debug `Mmf3ProfileNative.cs` / `Mmf3AcceptanceLaunch.cs` / `Directory.Build.props` | 仅 `MMF3_PROFILE_ACCEPTANCE && DEBUG` 编译；Parse 在任何凭据/client/GUI 使用前通过同一 Helper 的现场 Verify。固定四文件 payload 只复制到显式 Profile Debug 产物。普通 Release 中新 gate 和两个 acceptance 类型实际不存在。 |
| `scripts/verification/mmf3-profile-preflight.ps1` | 独立无请求预检入口：只读验证已准备的新 Profile，使用显式本地 Java/JNA 输入，编译并执行独立 gate probe；不调用应用 main，不 Provision、启动 Runtime/GUI/Chrome 或复用服务。18765 被占用则拒绝，不停止其他进程。 |
| 正式 `start-workspace.ps1` / `start-release.ps1` | 在 Java discovery、状态/ACL 修改、服务/Ollama/Desktop 动作之前直接拒绝。**这意味着当前候选的正式 Windows launcher（含 CheckOnly）被阻断**，直到具备可信发布集成；不允许它们先产生副作用再遭 Java gate 拒绝。原参数和后续实现保留，没有生产启动 bypass。 |

Bootstrap 的目录 metadata handle 注释据实修订：不宣称 rename pin；native 安全仍依赖完整祖先权限与 protected private objects。没有新 daemon、IPC、安全框架、依赖、持久 schema 或可信 SID。
旧 B1 LocalAppData 合同、Desktop Program、正式 8765 client、模型管理 API 与 Ollama 配置源码保持。

测试/harness 修改仅为：复用 `WindowsPrivateAclTest` 的综合流程增加实际 SQLite module 来源核对；复用 Desktop Profile 综合流程增加 native 缺失拒绝与真实 Provision/Verify；新增独立 `ProfileNativeGateProbe.java` 和单个 `verify-profile-native.py` 集成 harness。没有新增细碎 JUnit/xUnit 用例或完整矩阵。

## 3. 固定工件与信任边界

| 工件 | 固定身份 |
| --- | --- |
| JNA / jna-platform | `5.17.0`，Windows x64 |
| JNA JAR | `b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0` |
| jna-platform JAR（预检输入） | `b7e3d46c87bad2eb409b0e704916bcd81206168e357312dfddd0e253679cd9e0` |
| dispatch entry | `com/sun/jna/win32-x86-64/jnidispatch.dll` |
| dispatch DLL | 273408 bytes、AMD64/PE32+；`5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023` |
| SQLite JDBC / 测试 DLL | `3.53.2.0`；DLL `7d3281c7adbf3d2fe6b07462dee9cb310194c302154175f172ced3aac05c91c3` |

JVM 必须为 Java 21/x64。五项 JNA 控制固定：`jna.boot.library.name=jnidispatch`、唯一 Profile native `jna.boot.library.path`、`jna.nosys=true`、`jna.noclasspath=true`、`jna.nounpack=true`。
缺少/冲突设置、已加载标记、外部 JVM/.NET/Spring/Workspace 覆盖、子进程失败、无法确定 module 来源均拒绝，无修复/降级路径。

Java 和 Profile Debug 均固定本次 Release Helper 的 EXE、managed DLL、deps、runtimeconfig 哈希；见[完整构建身份](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/build-identity.json)。
这些常量绑定本次开发构建；Helper 重新构建后若身份变化，必须审查并同步固定值，不自动接受新 manifest。

**初始信任仍是已审查的本地源码/构建以及本地 JVM、.NET runtime 与加载环境。没有发布者签名、外部认证信任锚或生产安装链证明。**
本地哈希与子进程协议不能自行认证发布来源，也不能补救先前已执行的恶意 JVM/.NET/classloader。当前实现仅支持 Profile Development，不提供 Production Trusted 声明。
威胁边界继续排除恶意同 SID、管理员/SYSTEM/TrustedInstaller、内核及先前危险句柄；没有因此放宽 current-only private ACL。

## 4. 实际构建和聚焦验证

| 实际执行 | 结果与证据 |
| --- | --- |
| `dotnet build bootstrap/PersonalAiWorkspace.NativeBootstrap -c Release --nologo` | PASS，零 warning/error。[日志](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/bootstrap-build.log) |
| `mvnw.cmd -q -DskipTests compile`，最终 `-DskipTests package` | PASS；最终 JAR 包含最终 gate 字节码，独立 probe 未进入 Runtime JAR。JAR SHA-256：`adc3899a553601d8f32a45494d7b232db3dec9318c1fb9786fc89d3fe0d238a0`。[最终日志](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/java-package-final.log)、[artifact 检查](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/preservation.json) |
| Maven `-Dtest=WindowsPrivateAclTest#protectedBoundarySurvivesOwningFlowsAndRejectsUnsafeInput`，显式 Profile/JNA/SQLite/私有 temp argLine | PASS。执行了 token 初始化/再初始化、Browser registry 生命周期、ModelState guard 重启、Memory backup，以及 Memory/Knowledge 实际 PERSIST journal 再 admission。当前 owner-only/Protection、foreign/inherited 普通对象拒绝、目录 handle 生命周期保持；实际 SQLite module 路径和 hash 匹配专用私有目录。[最终 XML](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/java-acl-final.xml) |
| `python bootstrap/tests/verify-profile-native.py <本轮evidence>` | 单个集成 harness PASS。外部 OS module 枚举确认加载前无 dispatch，成功后唯一 dispatch 位于本轮 Profile 且 hash 匹配。回退开启、Profile 缺失、DLL 损坏、Helper 替换、外部 JVM options、预加载标记、os.name 覆盖均拒绝且未加载 dispatch；valid cwd/system decoy 未被采用。这些是同一 harness 的控制点，不冒充多个端到端验收。[结果](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/native-gate-results.json) |
| Desktop Debug `dotnet test ... -p:Mmf3ProfileAcceptanceBuild=true -p:Mmf3AcceptanceBuild=false -p:FrontendSkipBuild=true --filter FullyQualifiedName~Mmf3AcceptanceTests` | 最终两个既有 owning flows PASS；实际编译身份、跨模式拒绝、Profile/token/UDF 派生、18765 client 与正式 8765 隔离，以及 native 缺失拒绝/同源 Verify。没有 valid Program/GUI 启动。[TRX](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/profile-native-closure.trx) |
| 普通 Desktop Release build，两个 opt-in=false、FrontendSkipBuild=true | PASS，零 warning/error；反射只读检查三种 Profile/acceptance 类型不存在。[构建日志](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/desktop-release-build.log)、[类型证据](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/ordinary-release-types.json) |
| PowerShell AST / Java main javap / Git patch 检查 | scripts 语法 PASS；两个正式 launcher 的早期 throw 经 AST 核对，**没有执行 launcher**。main 字节码先 admission/参数 gate，再 Spring。`git diff --check` 和 full patch reverse check PASS。[脚本](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/script-syntax.json)、[main 字节码](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/main-bytecode.txt) |

早期 fixture 调用曾因正斜杠 JAR 路径被 Bootstrap 的 canonical DOS gate 拒绝；路径规范化后另建新 run。测试 fixture 准备另遇 PowerShell 反射 PSObject 类型错误，随后在新 run 使用显式类型。
Desktop 首次聚焦执行因 dotnet test 注入的 `DOTNET_ROOT_X64`/`DOTNET_HOST_PATH`/telemetry 环境变量被严格 gate 拒绝；修正仅限测试宿主内临时清除并 finally 恢复这三个变量，生产 gate 未放宽。失败现场保留，没有修复旧失败 run。
源码变化后只重跑相应聚焦流程；没有执行全 Java/Desktop/Frontend/browser/IME/stress/packaging 矩阵。此前 WAL/SHM、token fault injection、完整 Backup 证据属于历史批准候选，本轮未重新执行，不能记作本轮 PASS。

## 5. 一次全新隔离、无请求预检

在上述可信加载与 ACL 聚焦测试通过后，另建全新 run `1fd5b43075534bc9966ab5977feb4539`，使用已发布 Profile Prepare，再显式 ProvisionProfileNative。
执行 **一次** `mmf3-profile-preflight.ps1`，结果 PASS / Development-only。

预检只运行独立 probe，并未调用 `PersonalAiWorkspaceApplication.main` 或 Spring。它通过同一 Java gate 核对实际 dispatch，验证固定 runtime 参数，并确认 credentials/model-state/data/WebView2/Chrome/extension/logs 目录为空。生产端口参数被拒绝。
独立读取 Profile run、credentials、native directory 和 DLL 的 descriptor：current owner、Protected=true、唯一 current FullControl，均匹配。

18765 没有 listener；8765/18765/11434 listener 与 Java/Desktop/Chrome/Ollama 进程元数据快照前后相同。未创建 token、读取 Credential Manager、启动/停止服务或发送 HTTP/模型请求。
这是加载和空状态预检，**不是实际 HTTP/认证/GUI/Chrome/Ollama 集成验收**；有限快照也不替代完整系统审计。

证据：[预检结果](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/preflight-result.json)、[实际 descriptor](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/preflight-security.json)、[隔离检查](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/preflight-isolation.json)。
本轮所有 fixture 从当前有效 token Profile 派生；没有 caller root、LocalAppData/B1 回退、生产状态复制或旧 run 重启。私有绝对路径只在 ignored local manifests 中保存，报告不发布 token、用户内容或私人路径。

## 6. 真正剩余阻塞与未执行操作

1. **最终集成源码审查尚未完成。** 本轮新增 gate、Profile Bootstrap 模式、Debug admission 与 launcher 阻断需要审查，不能继承为已批准生产集成。
2. **Production Trusted 来源仍缺失。** 需要独立确定发布认证/可信安装、完整 JVM/.NET/Launcher/JAR/Helper 来源与稳定性。当前正式 Windows launcher 被显式阻断，普通 Windows Java main 也不能使用默认生产参数启动。此限制是当前候选的实际行为。
3. **真实 Runtime/GUI/Chrome 验收未批准、未执行。** 18765 实际 HTTP 认证、真实 GUI 和 Chrome 对齐仍没有本轮动态证据。

最终审查通过后，建议另行授权首次真实开发 Profile 启动：重新准备全新 private run，以本报告固定配置启动 18765 Java Runtime，再按需启动 Profile Debug GUI/Chrome，仅执行明确的无推理认证/状态流程。**本轮不执行这一步**；共享 Ollama 模型管理及任何真实推理仍需独立授权。

没有遗留必须放宽 ACL、读取真实数据、操作共享模型、新增长期服务/复杂 IPC 或无法确认本轮实际 dispatch 来源的问题。Model Management Overall 继续 NOT CLOSED。

## 7. 完整源码补丁与 SHA-256

交付[完整源码补丁](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/source-review.patch)，基于 HEAD `cb36879d93aa9a23c8b3d94e9fa26f5166a3c659`，包含全部未提交源码/构建配置/harness：批准 ACL 候选、Bootstrap 候选与本轮集成；不包含二进制、历史报告、私有 fixture 或本文。

```text
SHA-256: 326814b64dcfd81bf56e5e0d99f168ab1fe6f7ff58ed4dd39f43a4986a212da5
Bytes:   149217
Files:   28
```

[逐源码文件 SHA-256 manifest](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/source-identity.json) 与 [build identities](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/build-identity.json) 可供独立核验。`git apply --reverse --check` 实际通过，仅检查，没有应用/撤销补丁。

**工作在此停止，等待最终集成审查。未提交或推送本轮变更。**
