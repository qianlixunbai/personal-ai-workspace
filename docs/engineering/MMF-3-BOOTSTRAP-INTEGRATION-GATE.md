# MMF-3 — Bootstrap Integration Architecture Gate

2026-10-09（Asia/Shanghai）。本轮仅架构决策、源码只读核对、祖先 ACL 元数据与独立合成 fixture；没有正式接入。

**结论：架构建议可提交独立裁定；正式接入与现有 LocalAppData/B1 路径安全证明：STOP。**
七文件 Native Bootstrap 的 **Development Source Review — APPROVED / GO** 保持，但仍是
**DEVELOPMENT PROTOTYPE — NOT PRODUCTION TRUSTED**。该批准不覆盖 Java Windows ACL 部分候选。
本轮另发现：现有 Helper 的目录 metadata handle 未在合成流中阻止 DELETE open，不能把其目录句柄描述为已经证明的 rename/delete pin。
没有为解决该发现改动任何候选；动态验证在实际预期失败后停止。

**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**

本轮 ignored 证据根：`.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/`。
报告与保存结果只用路径角色、CURRENT_SID/FOREIGN_ACCOUNT 别名；不保存 token、用户内容或私人绝对路径。

## 1. Git Reality

| 项目 | 本轮实际核验 |
| --- | --- |
| Workspace 分支 | `codex/mmf3-runtime-private-acl-fix` |
| Workspace HEAD / fresh origin/main | `8dc66d81b78c3efac70feed94aec25ea71dd21a5` |
| Browser sibling | `D:\IDEA\Daima\local-ai-assistant`，`main`，HEAD / fresh origin/main 均为 `267db794691d1a9ab1712b30b07cbeb57241808b` |
| fresh fetch | 两仓库分别执行指定 `127.0.0.1:7890` 命令级代理 fetch 成功；无 fallback/global Git 或系统代理修改 |
| index | 入场与交付无 staged diff；完整 index entries 与入场逐字节一致 |
| Workspace 入场 | 八个 tracked 修改、Java ACL 新类/测试、七个 Bootstrap 源码/测试、四份既有调查/候选报告；全部保留 |
| Browser 入场/交付 | clean，index empty，无 diff |
| 本轮非 ignored 新增 | 仅本报告；无生产、长期测试、配置、Desktop 或既有文档修改 |
| 交付工作树 | dirty：保留用户所有未提交候选与文档，加本报告；没有 stage/commit/push/merge |

磁盘祖先和相关仓库没有发现额外 AGENTS.md；遵循用户提供的 AGENTS.md 与本轮更窄授权。
读取 current architecture、STATUS 当前 MMF-3 边界、ADR-001/002/015、四份既有报告及实际 owning code。
正式 Java main、四个指定 scripts、B1 Desktop 的 HEAD 对照 diff 均为空。
没有 reset/clean/amend/rebase/tag/release，也没有覆盖、格式化、重新生成或混合两个 patch。

| 保留补丁 | 本轮重新计算 SHA-256 |
| --- | --- |
| Java Windows ACL | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06` |
| .NET Native Bootstrap | `279bc299f1bbe72723e649fa1ae34bb778457cb0962f0f194754a3f26265a549` |

两个原始 `source-review.patch` 的 `git apply --reverse --check` 实际成功，仅检查一致性，没有应用/撤销。
证据：[入场逐文件基线](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/candidate-baseline.json)、
[入场 Git 状态](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/git-entry-state.txt)、
[交付保留检查](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/preservation-check.json)。

## 2. 已批准开发 Helper 身份与实际能力

批准范围严格为 `bootstrap/PersonalAiWorkspace.NativeBootstrap/` 的 csproj、Program.cs、TrustedNative.cs、Win32.cs，
以及 `bootstrap/tests/` 的 verify-trusted-native.ps1、TrustedNativeProbe.java、verify-jvm-loader.py。
本轮重新核对七文件与既有 source-review identity，未修改；没有 build/publish。

Helper 是 Windows x64、.NET 10、framework-dependent apphost；初始信任必须涵盖 EXE、managed DLL、deps/runtimeconfig 和实际 .NET runtime，不能只 hash apphost。
本轮重新计算已有构建所有身份，均与原 build-identity 一致。EXE SHA-256 为
`90375a30e0287b6af254c25306af35a3fd28f41cd886eeb0e89422bafc73ed38`，managed DLL 为
`5308285afc913e4e30cf044479a102b5091575205d51ec202d7ee32e2eeadb5b`。
这是开发构建身份，不是签名认证或生产发布批准。

| 固定 native 工件 | 身份 |
| --- | --- |
| JNA | `5.17.0`，Windows x64 |
| JAR SHA-256 | `b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0` |
| entry | `com/sun/jna/win32-x86-64/jnidispatch.dll` |
| DLL SHA-256 | `5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023` |
| DLL 结构 | 273408 bytes；AMD64 `0x8664`、PE32+ `0x20b`、DLL characteristic |

实际 CLI 仅 ProvisionTrustedNative/VerifyTrustedNative；目标固定为 token profile 下 fixture-id 派生的测试根。
不能选择生产路径，不能验证任意正式 package 安装根，没有 Launcher、module inspection、IPC 或生产路径策略。
Provision 原子创建 Protected owner-only descriptor、核验 JAR 内存快照、同对象 staging/rename；existing root 拒绝。
Verify 只读核验 owner/Protected/精确 ACE、祖先、路径/reparse、volume+file ID、single link、hash/PE；失败不修复、不清理。
文件 Verify 句柄在该分支内结束，目录句柄在 Run 返回前释放，随后才由 Program 输出 JSON。
因此收到 PASS 时，既有 Helper 已不持有此次验证的对象句柄。

本轮实际对既有 positive 独立 fixture 执行 Verify：exit 0、PASS、non-elevated、固定 hash/length/identity。
没有重新 Provision 该 fixture、读取生产状态或加载 JNA。
证据：[构建身份](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/binary-identity.json)、
[只读 Verify](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/helper-readonly-verify.json)。

## 3. 当前已发布启动入口与缺口

| 实际 owner | 已发布行为 / 本轮核对 |
| --- | --- |
| PersonalAiWorkspaceApplication.java | main 直接 SpringApplication.run；没有 pre-JNA/bootstrap gate |
| scripts/start-workspace.ps1 | 从 PATH 执行 Java 查询，解析 java.home；可 build、修 Memory ACL、启动/复用 Runtime、启动 Ollama/Desktop；Java 参数没有五项 JNA gate |
| scripts/release/start-release.ps1 | bundle hash 检查后执行 Java 查询；可创建/修状态 ACL、复用 8765 Runtime、启动 Ollama/Desktop；没有可信 native Verify |
| scripts/release/release-functions.ps1 | 本地 manifest/hash 完整性、Java major/path 与 private-state 函数；没有独立发布认证、bootstrap 授权或加载对象绑定 |
| scripts/package-windows.ps1 | Runtime JAR + self-contained Desktop + scripts；无 Bootstrap payload；manifest/SHA256SUMS 与包同目录，无外部认证锚 |
| B1 Debug Desktop | MMF3_ACCEPTANCE && DEBUG 才编译；固定 LocalAppData/PersonalAiWorkspace-Mmf3/run-id、18765、run-derived credential/UDF/mutex；验证私有根，不启动 Java |
| 既有 ignored B1 Runtime harness | `.verification/mmf3-no-inference-gui/start-runtime.ps1` 直接启动固定 Java/JAR 与 18765/test roots；未使用正式 launcher，无 JNA bootstrap gate |

本地 manifest 能检查意外损坏，但攻击者若能替换包与 manifest，可以同时更新它；它不是初始 trust anchor。
现有 Java discovery 在鉴别 Java 前已执行 PATH 上的程序，不能纳入“执行前已可信”的生产证明。
未来 gate 必须位于任何 build 后 artifact 使用、state ACL mutation、服务/模型动作之前；正式 launcher 的既有副作用不能作为本轮可调用验证手段。
本轮未执行这些 scripts 或 B1 Runtime harness。

## 4. 完整可信启动序列建议（尚未授权实施）

推荐最小链为 **外部认证发布来源 → 可信安装内容与稳定对象链 → 用户明确 Provision → Launcher Verify → Java 独立 Verify → 固定 dispatch 加载 → 实际 module 核对 → Runtime 初始化**。

1. **先固定可信来源。** Guard 决定发布者/渠道、独立固定公钥或可信安装机制，并认证完整 release identity。
   包含 Launcher/scripts、Helper 全 payload、Java Runtime JAR/内嵌 JNA 与 jna-platform、JVM/.NET 来源和精确获准版本。
   同目录 checksum、仓库本地 build、Helper 自报 hash 均不能自证初始信任。仅拥有可写来源上的“正确 hash 字符串”不够。
2. **可信安装/展开。** 用户从认证入口明确建立固定安装根，完整祖先与各可执行内容无非可信修改/替换权，拒绝链接、UNC、任意目标和不确定身份。
   不引入通用 Installer；认证下载/发布及窄 Provision 可以分开交付。首次执行 Helper 之前必须已信任其 code、.NET host/runtime 和安装根。
3. **用户明确 Provision。** 单独命令从固定 authenticated JAR 取唯一 entry，创建新的版本/hash native 目录与 DLL。
   Protected current-owner-only descriptor 在创建时生效；核验实际 descriptor、同 handle bytes/ID/hash/PE 后发布。
   不接触 credentials/data/model-state，不覆盖旧 DLL，不在日常启动自动安装、修 ACL、下载或迁移。
4. **Launcher 每次 Verify。** 从可信固定根执行固定 Helper；复核本次实际 JVM/JAR/Helper/native 版本、路径及祖先条件，冲突 STOP。
   Java options 环境覆盖与 agent/替代 classloader 注入不被当合法入口；清除/拒绝 JAVA_TOOL_OPTIONS、_JAVA_OPTIONS、JDK_JAVA_OPTIONS，约束 .NET host 覆盖，禁止通过 PATH/cwd 搜索 Helper。
   只检查 Java major=21 不足以证明 JVM 来源。`-NoProfile`/ExecutionPolicy Bypass 也不是发布认证。
5. **启动确切 JVM/JAR。** 绝对路径、固定 argument vector，五项 JNA properties 在 JVM 启动前配置；不允许任意透传 JVM options。
   启动前检查必须对应实际执行对象。此对应关系依赖安全稳定安装链，不能仅靠先 Get-FileHash 后 Start-Process。
6. **Java 自身执行入口 gate。** main 第一步调用窄 JDK-only admission code，禁止先创建 Spring context、访问 WindowsPrivateAcl/JNA static initializer 或初始化领域状态。
   在可信 package identity 与稳定安装根假设下，Java 自己用固定绝对 Helper 路径重新启动一次只读 Verify；不消费 launcher 留下的 PASS 文件、环境标记或 system-property ticket。
   只接受本次直接子进程的有界输出与正常 exit，检查固定 artifact tuple、派生路径和 identity；再用 JDK 读取预期绝对 DLL 的 bytes/hash 对照。
   JSON 的字段不授予权限；真正证据是已认证且不可被威胁主体替换的 verifier 对本次对象所做检查。
7. **绑定实际加载。** Java 比较 native tuple 与唯一 boot path，拒绝预加载/冲突状态，立即触发固定 JNA Native 初始化；LinkageError/异常即结束进程。
   通过可信 native code 从 OS 读取本 JVM 实际 dispatch module 路径，并重新打开路径对照 volume/file ID/hash/type 与 Verify 对象；错误或不唯一 STOP。
   该加载后核对只能发现错误，不能撤回已执行的恶意 DLL；安全性首先依赖第 1–6 步和第 8 节的稳定性前提。
8. **Runtime admission。** native gate 成功后才允许 Windows 状态对象权限验证及 Spring/domain 初始化；状态路径祖先失败仍独立 STOP。
   不因 native 成功跳过 B1、认证、旧状态、SQLite 或模型 admission。

这里“pre-Spring”指应用 Spring context 初始化前；`java -jar` 的 JVM、Spring Boot JarLauncher/classloader 已先执行，所以 JVM与整个 executable JAR 必须先属于可信发布。
不能声称 Java main 可以补救被替换的 JVM/JAR、启动前 agent 或先执行的恶意 classloader。
JDK-only gate 不调用 JNA 来证明 JNA 尚未可信；安装根的初始 OS 权限证明必须来自已可信的外部入口，不能循环依赖 Java self-hash。

## 5. Provision / Verify / Java 信任边界

| 边界 | 权威及禁止跨越 |
| --- | --- |
| 发布来源 → 首个 Helper | 独立认证来源和受审安装条件；开发 patch/binary hash 只标识候选，不能代替来源信任 |
| Provision → installed native | 唯一改变 native 安装内容的显式动作；仅新版本根，不创建用户数据/凭据或持久授权票据 |
| Verify → 调用进程 | 只读现场检查；PASS 描述本次观测，不是 bearer credential，不承诺退出后的句柄保持 |
| Launcher → Java | 参数只提供固定配置；Java 必须独立复验，不能用 parent PID、可伪造 nonce、环境变量或 PASS JSON 授权 |
| Java gate → Runtime | 成功状态仅进程内控制流；任何别的入口调用 Runtime 初始化也必须受同一 admission 合同约束 |

直接 trusted child stdout 比任意保存 JSON 有明确来源，但 verifier executable 被换时其 stdout 同样不可信。
没有要求 secret-on-command-line、持久 PASS marker、可移植 ticket 或从网页传入证明。
本威胁模型沿用 ADR-002：不防御恶意同账号、管理员、SYSTEM/TrustedInstaller、内核或被替换的可信 runtime。
若要求隔离恶意同账号，仅凭同 SID owner-only ACL、Java property 或 parent-process 检查无法做到；需要另立威胁模型和 OS 边界。

## 6. JNA 不可回退的执行条件

```text
-Djna.boot.library.path=<唯一获准绝对 native 目录>
-Djna.boot.library.name=jnidispatch
-Djna.nosys=true
-Djna.noclasspath=true
-Djna.nounpack=true
```

Java gate 对这五项进行精确检查：拒绝缺失/冲突、相对或多目录 path、任意版本、链接/不同 identity。
`jna.tmpdir` 不是信任目录，`jna.library.path` 不是 dispatch boot gate；不能用私有 temp 代替验证。
`jna.loaded`/`jnidispatch.path` 可伪造，只可诊断。固定 JAR hash 还必须覆盖实际内嵌 JNA class 来源，不能只检查另一个 Maven cache JAR。
加载后 ABI compatibility 不等于精确 artifact/version 认证。

JNA 5.17.0 boot load 失败会继续后续分支；上述配置与可信唯一 path 共同构成 fail-closed 条件。
`nounpack` 还禁止 dispatch 的临时清理/解压分支，`noclasspath` 关闭 file-resource 分支。
这一结论限定 dispatch；其余 `Native.load("kernel32"/"advapi32")` 和 DLL imports 另有 OS module 搜索边界，不能宣称五属性封死所有 native 搜索。
窄 Windows interop 应固定 OS System32/受信系统模块来源并核对必要 imports，不能接受 cwd/PATH decoy。
依据：[固定版本 JNA Native.java](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/Native.java)。

既有 VALID/MISSING/INVALID boot 的独立 JVM/module evidence 是上一轮历史证据，本轮没有重跑或加载 DLL。
未来集成必须在正式 gate 控制流中验证无回退，不能拿历史 probe 代替 main 的 admission 验收。

## 7. raw java -jar 支持决策建议

建议 Windows 产品支持入口仅为受信 Launcher；裸 `java -jar` 缺少必要配置时在 Java gate STOP，不能退回 JNA 默认加载。
不另建设一条 raw bootstrap/provision 产品入口；IDE、mvn spring-boot:run 与测试 main 也不能默认绕过正式 admission。

但 **launcher-only 是支持政策，不是同账号进程认证**：复制全部固定参数的人仍可调用同一个 main。
只要同一可信 main 独立完成全部检查，这种调用不能绕过安全 gate；不能声称 Java 可辨认用户究竟双击了 Launcher 还是手输等价命令。
若 Guard 要求“即使全部资源合格也必须证明由指定父进程启动”，这是新增授权机制，当前小链不满足，须 STOP 另案评估。
推荐批准“所有受支持 Windows Runtime 初始化不可绕过 Java gate”，不批准伪造 PASS 属性作为 launcher 身份。

## 8. 跨进程 TOCTOU、最小防护与新 pin 阻塞

### 8.1 条件式无长期进程方案

对不可信外部 SID，若整个代码/native 对象链在 Verify 期间和之后均无其 write/rename/delete、WRITE_DAC/WRITE_OWNER、可改变解析的 reparse/mount authority，
且 native root/version/DLL 已 Protected current-owner-only、没有先前获得的危险 handle/mapping、没有正在并发 Provision/update/repair，
则 Helper 退出后外部主体仍不能换对象。跨进程重新 Verify + 固定 path/hash/ID + 加载后的 module 核对足以形成这个限定模型下的最小链。
无需为单纯“跨进程”引入 daemon/IPC；残余风险是明确排除的同账号/管理员/系统行为、既有危险 handles、可信更新竞态及未覆盖的 filesystem 语义。

Protected leaf alone 不满足这些条件：每条 parent→child 边都必须阻止替换；祖先 owner 也必须可信。
generic rights 必须按实际目录类型展开，未知 ACE/重定向/身份不可确认即 STOP。
静态 DACL 不会撤销历史已授予的 handle；不能把“当前 ACL 合格”说成证明不存在先前 writer/delete handle。
DLL Verify 的 read-data/share-read open 对普通 writer/delete open 有约束，但不能将此推广成已证明封锁所有映射/目录操作。
这些前提需要 Guard 批准并用最小 fixture 验证，目前没有生产安装链证明。

### 8.2 本轮实际发现

独立 fixture 使用同一 non-elevated current token；新 parent/child 在 token profile 下，不接触 B1或系统目录。
child DACL 故意为 Protected、当前 SID DELETE Deny + 当前 SID Allow（去掉 DELETE）；这是合成访问控制条件，不是 owner-only B1 admission fixture。

| 合成操作 | 本轮实际结果 |
| --- | --- |
| parent 允许自身 DELETE，去掉 DELETE_CHILD；child 请求 DELETE | error 5 / ACCESS_DENIED |
| parent 加入 DELETE_CHILD，child 保留 Protected 与 DELETE Deny；再次请求 DELETE | 成功 0 |
| 持有既有 Helper `Win32.Open(child, true)`，该 handle 不 share-delete；另请求 DELETE | **成功 0，预期 error 32 未出现** |
| 释放上述 handle 后，rename 非空 child | 成功 0；前后 volume/file ID 与 descriptor hash 一致，synthetic marker 仍在 |

因此 synthetic harness 最终因 **SYNTHETIC_CONTRACT_UNEXPECTED** 失败，不能记为完整 PASS。
前三种实际返回值及 rename identity 在保存结果中可复核；没有执行 pin 持有期间的 rename，不能声称已动态复现该步骤。
没有其他主体 token、capability token、管理员 privilege 或实际 LocalAppData 替换攻击。

源码定位：Helper directory Open 使用 READ_CONTROL|READ_ATTRIBUTES（`0x00020080`）、share READ|WRITE，无 share DELETE。
本机这个具体 open 没有取得所预期的 DELETE-open 排他性；可能涉及 metadata-only desired access 的 sharing 语义，但尚未完成更底层机制证明。
不能据通用 API 文档或参数看起来正确就把祖先 handle 当有效 pin，也不能本轮直接改 desired access 并宣称修好。
Java ACL 候选 `NativeAcl.open` 同样是 READ_ATTRIBUTES（security=true 时加 READ_CONTROL）、share=3；源码具有相同待复审条件，
但本轮没有执行其 native operation，不能自动套用 Helper 的全部动态结果。
参考：[CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)、
[IoCheckShareAccess](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/wdm/nf-wdm-iocheckshareaccess)。

证据：[合成实际结果](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/synthetic-fixture.json)、
[ignored harness](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/inspect.ps1)、
[失败记录](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/harness-notes.txt)。
前置脚本问题修正仅限 ignored：PSObject reflection unwrapping、PowerShell 7 ACL extension dispatch、void Invoke 输出 null。
早期中断 fixture 全保留；修正后遇到上述真实预期失败即停止，没有重试把安全失败变成 PASS。

### 8.3 如果必须保留 unsafe 祖先路径

若要求在有非可信 replacement authority 的路径上仍安全运行，可能需要**经证明有效**的祖先/目录 pin，从验证一直覆盖 DLL load，
且对 credentials/data/model-state 覆盖全部后续路径使用；单次启动 pin 不能保护 Runtime 之后的 SQLite/凭据路径 reopen。
替代方向是持有可靠句柄并进行相对/身份绑定操作，但会改变现有状态 I/O 合同，不能当成 mask 小修。
长期 Helper/IPC 的必要性来自这类持续 authority，成本包括 handoff身份、生命周期、崩溃即 STOP、权限与句柄兼容、更新退出、B1/Release同步。
当前 pin 保证尚未成立，IPC 也不能替代基本访问控制证明；不实施、不推荐据此立即引入 daemon。
不能防恶意同账号或管理员的结论不因增加 IPC 而改变。

## 9. LocalAppData 真实权限阻塞与 ancestorAcl 判定

本轮只读真实 descriptor、identity、reparse/type，且直接调用已批准 Helper 的 AncestorAcl predicate。
六个系统/profile 祖先 descriptor 的交付重读 hash 与入场一致；未读取任何正式 credentials/data/model-state。

| 路径角色 | 当前真实权限 / predicate |
| --- | --- |
| 系统盘根 / Users / token profile | 现有 trusted owner 与 ACE；Helper predicate ACCEPT |
| AppData | current owner、unprotected；capability SID 显式 Allow `0x001f01ff`，flags None；ANCESTOR_REPLACEMENT |
| LocalAppData | current owner、unprotected；同 capability FullControl，Inherited；ANCESTOR_REPLACEMENT |
| Temp | 上述 capability FullControl；另有两个 foreign account Modify `0x001301bf`；ANCESTOR_REPLACEMENT |

实际 capability SID：`S-1-15-3-2968813833-811790644-2202111208-3784096404-1081847329-2708967783-1438471679`。
它不在当前 token user/groups 中；不能由此推断不存在携带该 capability 的其他 token。
capability 是 AppContainer 授权维度，实际访问还取决于 user/package/capability、限制 token、mandatory policy 等；没有识别其持有 app或模拟其有效权限。
不新增 trusted SID，不把它等同任意用户可得或默认可信。
参考：[AppContainer token 与 capability](https://learn.microsoft.com/en-us/windows/win32/secauthz/implementing-an-appcontainer)。

两候选 ancestor mask 均为 `0x500d0040`：GENERIC_ALL/WRITE、DELETE、WRITE_DAC/OWNER、DELETE_CHILD。
跳过 ordinary Deny/inherit-only，遇到未知 Allow 类型拒绝；这是按 foreign Allow authority 的保守审查，不是 AccessCheck 对完整 token 的有效权限计算。

| 权限 | 正确对象边界与保守性 |
| --- | --- |
| DELETE `0x10000` | 删除/重命名该祖先对象本身；不等于对子对象 DELETE_CHILD。保护 child 内容不保护祖先路径。应结合该祖先的 parent 与真实 identity，不应简单全删此 bit |
| DELETE_CHILD `0x40` | 对直接 child 的删除/rename 入口；child 的 Protected/DELETE Deny 不能单独否定 parent authority；非空只限制某些删除操作，不能排除 rename |
| WRITE_DAC/WRITE_OWNER | 能改变 ancestor authority/所有者，间接取得 replacement rights；Protected child 不阻止 ancestor 自身被改权限 |
| GENERIC_WRITE | 按目录类型展开为 add-file/add-subdirectory/EA/attributes 等，通常不含 DELETE/DC/WRITE_DAC/OWNER；仅在既有命名空间可创建无关 sibling时，统一拒绝可能过宽 |
| GENERIC_ALL | 展开仍包含危险 authority，不能作为“只写无关 sibling”处理 |

DELETE 与 parent DELETE_CHILD 的替代授权及 Protection 仅控制继承的边界，有 Microsoft 合同与本轮 synthetic evidence 支持。
依据：[DeleteFileW 删除/rename权限](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-deletefilew)、
[文件权限常量](https://learn.microsoft.com/en-us/windows/win32/fileio/file-access-rights-constants)、
[SE_DACL_PROTECTED](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-control)。

**本机阻塞不是仅由过宽 DELETE 位造成。** capability FullControl 的命中为 `0x000d0040`，同时有 DELETE_CHILD、WRITE_DAC、WRITE_OWNER。
即使将 GENERIC_WRITE 与某些 DELETE-only 拒绝按 parent/child 边细化，AppData/LocalAppData 上这些独立危险权仍存在。
Temp foreign Modify 仅命中 DELETE，可进一步区分其自身 rename 与 protected child 直接访问；但 capability 阻塞不会因此消失。

已证明：真实 ACE存在、候选实际拒绝、当前 token 合成父子权限结果、Protected child 非空 rename 保留 identity。
仍是推断：携带该 capability 的特定主体实际能否沿真实链执行替换、其 token 的完整限制，以及真实攻击是否发生。
没有动态证据证明任意外部用户都能替换 LocalAppData；也没有足够证明按现合同排除其 authority，因此 fail-closed STOP。
不使用 Deny 抵消危险 Allow 来为本机放行，不删除祖先检查，不修改 AppData/Temp ACL。
证据：[本机 ACL 与实际 predicate](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/ancestor-acl.json)、
[祖先原样检查](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/scope-check.json)。

## 10. B1 固定路径兼容性与 Debug / Release

B1 固定 LocalAppData 路径、current SID、Protected DACL、仅 current Allow/FullControl、no-reparse 及目录存在检查全部保留。
其 gate 检查 run 根/required child，不能独自证明全部祖先 replacement-authority 安全；通过 B1 descriptor gate 不等于通过新 Java ancestor gate。
无 GUI、B1 build/test 或原失败 Runtime 重启。

Native binary storage 可以独立于 B1 state；例如另案批准 token-profile 派生 native 专用根，并不等于迁移 credentials/data/model-state到 home。
现有 Helper 的 home fixture 不能直接用作生产安装，也不能解除 B1 LocalAppData 的状态路径阻塞。
在不新增信任主体、不修改系统 ACL、不持续可靠绑定 unsafe路径的条件下，本轮**未能证明固定 B1 路径满足完整安全合同**，必须 STOP。

未来 Debug acceptance Runtime 使用同一 Java native gate，加其既有 run-id/18765/credentials/model-state/data隔离。
Debug opt-in只限定测试状态，不减弱 native/ancestor条件；Release不能携带测试工厂/run入口或接受 fixture-id。
仅修改 B1 Runtime harness可能足够接入native参数；Desktop只有实际需改变 admission合同才另案修改，不能为通过 LocalAppData测试放宽 Protected检查。
正式 8765/WinCred target/UDF/mutex 与 B1保持隔离，不能复用正式 token或状态。

## 11. 旧状态、版本更新与迁移影响

Native版本独立 tuple：JNA JAR + jna-platform匹配版本 + entry/DLL hash/length/PE + Helper策略版本 + JVM/OS架构。
未来更新使用新版本/hash目录，显式 Provision，不原地覆盖、自动选择 latest或回退到旧DLL。
Java发布只绑定一个获准tuple；保留旧版本供旧获准Runtime使用，未知/撤销版本STOP。
旧版本清理/回滚另需授权；不能在进程仍使用时删除或把“保留旧版本”解释为可自动downgrade。
分发DLL须随附JNA适用license/notice；本轮不分发或打包。

native成功不说明旧 credentials/data/model-state合格。既有 Java ACL候选严格验证可能拒绝已失去Protection的旧目录/文件，不能自动repair。
当前Launcher已有权限修复与known SQLite sidecar处置，和“启动Verify只读”存在需要另审的边界；未来须先明确哪些动作属于显式Provision/迁移，不能继续隐含在启动里。
不读取旧token/DB/lock/registry/sidecar，不自动迁移到home，不改schema/API/持久格式。
SQLite WAL/SHM/journal新建权限、原子发布/锁、备份与恢复仍是独立未验合同；本轮不关闭。

## 12. 执行依赖与最小实施文件清单（仅建议）

| 工作 | 先决条件 / 可能文件 / 影响 |
| --- | --- |
| A1 发布身份与固定来源 | Guard先批准来源、Helper/.NET/JVM完整身份、安装根和许可；现同目录manifest不能胜任，无生产状态影响 |
| A2 窄生产Provision/Verify | A1和祖先/pin合同裁定；未来单独修订Bootstrap Program/TrustedNative/Win32/csproj中的实际必要项，另出patch，不改本轮批准候选身份 |
| A3 Java不可绕过gate | A1/A2与无长期IPC条件获批；PersonalAiWorkspaceApplication.java首条调用 + 一个窄WindowsBootstrapGate.java，JDK-only preflight与延迟native/module核对；改变启动合同，须独立批准 |
| A4 Windows Launcher | scripts/start-workspace.ps1、scripts/release/start-release.ps1、release-functions.ps1；统一固定artifact/Verify/参数，移到副作用之前；不创建通用进程框架 |
| A5 Package交付 | scripts/package-windows.ps1，按实际选择更新release README；绑定Helper完整payload/native/许可证与认证manifest，生产发布来源先到位 |
| B1 祖先语义与pin复审 | WindowsPrivateAcl.java/Test与Helper Win32 owning逻辑另案；generic mapping、parent/child identity、当前pin失败需先证明，不可覆盖原ACL patch |
| B2 固定LocalAppData合同 | B1后仍有capability DC/DAC/OWNER；决定STOP或另案持续identity/handle方案，非mask缩窄即可完成 |
| B3 Debug acceptance | A3及B2通过后更新独立Runtime launch harness；B1 Desktop Program/Mmf3AcceptanceLaunch仅必要合同变更另获授权，固定根和Protected不放宽 |
| 旧状态兼容/IOException | 独立调查/迁移授权；不依赖native成功即可关闭，也不授权恢复原失败进程 |

可以先在全新独立根完成来源绑定设计、Java pre-Spring admission和无回退验证，以及parent/child fixture证明；不得启动正式Runtime来证明它们。
生产native根、源码中的固定Helper期望hash与发布build身份必须同步，源码patch hash不等于目标EXEhash。
若无持续IPC条件被批准，A无需等待B完成设计，但正常Runtime/B1实际接入仍必须同时通过状态路径gate。
任何`WindowsPrivateAcl`改动、Helper生产路径扩展、Java main、Launcher/package或B1合同变更都需要独立Architecture Guard实施范围批准。
本轮实际生产实施文件：**0**；长期测试文件新增：**0**。

## 13. 必要聚焦测试与本轮实际验证

未来获授权后只需少量distinct invariant证据，不把覆盖点变成大矩阵：

- 一条全新private根集成流：认证fixture artifact → 显式Provision → Launcher Verify → Java自己Verify → 固定dispatch加载/OS module身份 → 停在领域初始化之前；缺DLL/篡改tuple/伪造PASS/冲突配置在同流验证STOP。
- 复用既有JVM missing/invalid + decoy harness验证正式gate的无回退；没有相关代码变化就不重复独立已绿probe。
- 一个parent/child权限流保护DELETE/DC、Protection、非空rename与**真实有效**pin的不同信息；当前失败不写入“已通过”清单。新危险ACL/owner/reparse时STOP而非repair。
- 若Guard选持续handle/IPC，只测关键handoff/collapse/lifetime一个集成流；不先建立通用矩阵。
- B1改动时一个新run的fixed-path/18765/no-production-fallback admission检查；旧状态/SQLite仅在另获授权后做必要owning-layer验证。

**本轮实际执行**：两仓库fetch/Git/index/diff/AGENTS核验；两个patch重新hash及reverse check；七源码与已有binary身份核对；
系统/profile祖先只读ACL/identity与Helper predicate；既有独立native fixture只读Verify；合成current-token权限流（sharing预期失败，保存实际结果后STOP）；
六祖先descriptor交付重读原样；候选逐文件保留检查；`git diff --check`。
脚本兼容修正期间有重复只读核验，不把它们计为新的测试case或生产通过。

**历史证据**：旧Bootstrap Provision/negative flow、独立JVM boot/module流、JavaACL focused tests、B1 source/build审批。
本轮未重新执行，不升级为正式集成证据。

**未执行**：Maven/.NET production build、Java/Desktop/Frontend full suites、JVM加载重跑、B1 GUI/Chrome、完整Runtime、IME/stress/package/release矩阵、
模型probe/load/inference/switch/Release、共享Ollama管理、正式用户状态读取或迁移。没有为了报告重跑绿色flow。

## 14. 仍未知的 IOException 与待裁定架构选择

原LocalClientToken IOException准确阶段、底层cause/Windows category仍UNKNOWN；Protected丢失根因已有历史复现，不能据Bootstrap成功把二者合并或宣布修复。
旧失败现场没有重启/修改；后续需独立授权的阶段化synthetic I/O诊断与必要故障注入，不恢复旧token来“跑通”。

Guard需要独立裁定：

1. 初始发布认证方式、完整Helper/.NET/JVM/Runtime bundle trust anchor及native专用生产根；本轮没有可用生产发布证明。
2. 接受“稳定完整ACL对象链 + Java直接只读复验”的限定模型，还是需要长期pin；必须明确不防恶意同账号/管理员。
3. 当前directory metadata handle的预期排他性失败；是否授权窄API合同证明/修订，再确定Provision/Verify的identity保证。
4. 保留LocalAppData/B1路径时如何处理真实capability DC/DAC/OWNER authority；当前只能STOP，不能通过白名单或删除检查放行。
5. raw java产品不支持与Java gate不可绕过的准确合同；不要求无法靠当前模型证明的父Launcher身份。
6. main/Launcher/package的最小文件范围、Debug Runtime harness接入、existing Runtime reuse判定；不能仅凭health/token就认定旧进程曾通过新gate。
7. 版本更新/保留/撤销策略、旧状态与SQLite兼容/迁移边界、原IOException后续诊断权限。

已有Runtime复用是额外缺口：新启动时Verify本地文件不能证明已运行JVM先前加载了相同对象。
最小建议新gate的受支持入口遇到来源/版本不可证明的既有Runtime时STOP并要求用户另行处理，不自动kill/restart或扩展通用IPC。
若保留复用，需独立定义进程模块/启动身份与认证对应证明；bootstrap PASS不能替代该证据。

## 15. GO / STOP 与交付边界

| Gate | 裁定建议 |
| --- | --- |
| 已批准七文件development source | APPROVED / GO保持；非production trusted |
| 本轮架构报告 | 完成，供独立Architecture Guard复审 |
| A最小链 | 条件式建议；来源、稳定安装链、Java入口与module合同尚待批准/实施，不能标生产GO |
| Helper目录pin保证 | 当前synthetic预期失败，**STOP待合同复审** |
| B现有LocalAppData/B1安全路径 | capability replacement/ACL authority真实存在；未证明排除，**STOP** |
| 原JavaACL候选 | 身份原样保留；未获生产Source Review，不随Helper审批自动通过 |
| 旧状态/SQLite/token IOException | 独立未解决，不关闭 |
| 正式接入/GUI/模型/发布 | **STOP** |

只新增本报告与ignored证据；所有既有候选/报告保留，系统祖先ACL原样，合成现场保留且无清理。
报告交付后停止，等待Architecture Guard裁定具体接入实施范围。

**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**
