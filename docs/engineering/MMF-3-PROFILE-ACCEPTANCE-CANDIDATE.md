# MMF-3 — Profile-Root Acceptance Candidate

2026-10-09（Asia/Shanghai）。本轮仅交付 Debug-only 独立开发候选、空私有 fixture 和聚焦证据。

**Profile Candidate：开发候选可提交 Architecture Guard 独立 Source Review；等待 Source Review，未获得执行批准。**
**现有 B1：STOP。Production Integration：STOP。MMF-3 GUI / Model Execution：STOP。Model Management Overall：NOT CLOSED。**

本轮 evidence：[独立目录](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/)。全部编译中间文件、产物、TRX 和脚本记录均写入这个全新 ignored 目录，没有复用旧验收产物或失败现场。

## 1. Git Reality

| 项目 | 实际事实 |
| --- | --- |
| Workspace branch | `codex/mmf3-runtime-private-acl-fix` |
| Workspace HEAD / fresh origin/main | `8dc66d81b78c3efac70feed94aec25ea71dd21a5` |
| Browser HEAD / fresh origin/main | `267db794691d1a9ab1712b30b07cbeb57241808b`；入场 clean |
| fetch | 两仓库指定命令级 `127.0.0.1:7890` proxy fetch 成功；没有 fallback 或全局设置修改 |
| index | 入场无 staged changes；逐 entry 与入场一致 |
| worktree | 保留八个既有 Java tracked 修改及全部既有 nonignored untracked 源码、测试、报告；本轮新增三个 Desktop tracked 修改、一个脚本和本文 |
| 操作边界 | 没有 stage / commit / push / merge / rebase / amend / reset / clean / tag / release |

检查磁盘祖先和仓库适用 AGENTS，未发现额外文件；遵守用户提供的 AGENTS 和本轮窄授权。读取 current architecture、STATUS、ADR-001/002/015、Windows ACL Semantics Decision 及 Desktop/Bootstrap owning code。未修改 ADR、STATUS 或既有报告。

[入场状态](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/status-baseline.txt)、[入场 source baseline](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/preserved-baseline.json)、[源码保留及 patch 检查](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/source-review-check.json)。除本轮允许修改的三个 tracked 文件外，入场 tracked/nonignored untracked 文件逐 SHA-256 不变。既有 ignored evidence 没有写入或清理；该逐文件 baseline 不宣称覆盖所有 ignored 内容。

## 2. 两个独立候选身份保留

| 候选 | 本轮实际重新计算的 SHA-256 |
| --- | --- |
| Java Windows ACL patch | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06` |
| .NET Bootstrap patch | `279bc299f1bbe72723e649fa1ae34bb778457cb0962f0f194754a3f26265a549` |

两份 patch 入场 `git apply --reverse --check` 成功；没有应用或撤销。对应源码和既有报告包含在本轮保留 baseline 中。本轮不修订 Java ACL、Bootstrap、Helper pin 或 Trusted JNA Loader，也不提升其生产批准状态。

## 3. 独立构建合同

`desktop/Directory.Build.props` 新增 `Mmf3ProfileAcceptanceBuild`，默认 `false`。Profile Debug 定义 `MMF3_ACCEPTANCE` 以复用现有真实链路，并额外定义唯一 `MMF3_PROFILE_ACCEPTANCE`。统一 props 同时适用于 Core、Desktop、测试工程；Core 原有 friend assembly 规则覆盖两个独立 opt-in。

Release + Profile、Legacy + Profile、Profile Debug + MainWorkspaceDev 都在 PrepareForBuild 前拒绝。旧 B1 的 Debug-only、MainWorkspaceDev 禁止规则保留。没有修改 Program.cs。

| 实际编译身份 | 产物验证 |
| --- | --- |
| Ordinary Debug | Desktop 无 AcceptanceLaunch / ProfileState；Core 无 acceptance factory；两种测试参数均退出 2 |
| Ordinary Release | 同上，确认实际 Release DLL 中入口不存在 |
| Legacy B1 Acceptance Debug | LocalAppData 派生、旧参数/label/target/UDF、Core 18765 factory；拒绝 Profile 参数 |
| Profile Acceptance Debug | 新 Profile 类型、固定 Profile 派生、新参数/label；Core 18765 factory；拒绝旧参数 |

普通身份编译显式传入两个 opt-in=false；另用无 opt-in 的 MSBuild 查询确认两个默认值为 false，见 [默认属性](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/default-properties.json)。入口、factory、参数行为由实际编译 DLL 的反射和 owning-layer tests 验证，未以属性文本代替产物证明。

## 4. Current-token Profile 来源与 SID

`WindowsIdentity.GetCurrent(TokenAccessLevels.Query)` 获取实际有效 Windows token；同一 identity 的 User SID 用于权限检查，AccessToken 交给 `GetUserProfileDirectoryW`。该 [Windows API](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-getuserprofiledirectoryw) 返回指定 token 所属用户的 Profile 根。没有用 USERPROFILE/HOME 等字符串派生 Profile，也没有接受 caller root。

实际 token SID：`S-1-5-21-1077478615-2643175411-222072981-1001`；API 派生为 `C:\Users\28421`。只读独立观察的 KnownFolder 和 HKLM ProfileList/该 SID 的 ProfileImagePath 同为这个路径；ProfileList 观察是本轮证据，不是新增运行配置来源。

Profile 所属账号与 NTFS owner 是不同事实。本机 Profile 的实际 NTFS owner 是 Administrators（`S-1-5-32-544`）。祖先继续采用已审查的 current SID / SYSTEM / Administrators / 固定 TrustedInstaller SID 边界；新测试 parent/root/八个子目录则只接受当前 SID owner。没有新增 trusted capability。

[当前 SID/Profile 实际 descriptor](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/profile-readonly.json)。非法/匿名 token、Profile API 失败、非 canonical local DOS path 均停止，不回退到 LocalAppData。重定向 UNC、带异常组件或 canonical identity 无法匹配的 Profile 不受此候选支持。

## 5. 根权限与完整祖先链

固定根：`<token-profile>/.personal-ai-workspace-mmf3-tests/<run-id>`；run ID 严格为 32 位 lowercase hex。

每次 admission/resource-use Validate，从 drive root 向 Profile 逐层 open：OPEN_EXISTING、READ_CONTROL|READ_ATTRIBUTES、OPEN_REPARSE_POINT|BACKUP_SEMANTICS，读同一 handle 的 attribute/tag、final DOS path 和 security descriptor。要求真实目录、非 reparse、final path 与固定输入相等。不存在、访问失败、未知 descriptor/ACE 形式均拒绝。

祖先 owner 必须在既有可信边界内，DACL 必须存在。普通 Deny / inherit-only 不用于抵消危险 Allow；foreign Allow 命中已审查 `0x500d0040`（GENERIC_ALL/WRITE、DELETE、WRITE_DAC/OWNER、DELETE_CHILD）即拒绝。未因 capability 不在当前 token 中而忽略其权限。

固定测试 parent、run root、八个必需目录要求 current owner、Protected DACL，以及唯一 current SID OI|CI FullControl Allow；继承/foreign/额外 ACE 均拒绝。Profile gate 比旧 B1 更严格，旧 B1 gate 本体没有放宽。

**这些 metadata handles 不宣称 pin rename，也没有依赖现有被反证的 Helper pin。** 路径使用安全依据是已审查的完整祖先 owner/authority 和 private descriptor；仍不承诺隔离恶意同 SID、管理员/SYSTEM/TrustedInstaller、内核或之前已授予的危险 handle。没有新增 IPC/长期 handle 生命周期。

[本轮独立读取的实际祖先/私有目录 descriptor](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/actual-directory-descriptors.json)。本机 Profile capability ACE 仅 traverse/synchronize，不命中 replacement mask；这不是对其他机器/账号的通用放行。未修改系统盘、Users、Profile 或 AppData 既有 ACL。

## 6. 创建与脚本合同

新增 `scripts/verification/mmf3-profile-state.ps1`。默认 Inspect 为完整只读 Verify；缺失 RunId/根均停止。只有 `-Mode Prepare -RunId <fresh-id> -OptIn` 可以创建，未知 root/endpoint 等参数由严格参数绑定拒绝。Verify 不创建、不修复。

脚本从 owning `Mmf3AcceptanceLaunch.cs` 的标记区段编译同一窄合同，不加载 Desktop、调用 Bootstrap 或复制整个 Bootstrap Framework。窄合同沿用已审查 Win32 descriptor/path/ancestor 规则；P/Invoke 限制 System32 DLL 搜索，没有新 NuGet dependency 或生产加载策略变更。

创建使用 `CreateDirectoryW` + 创建期 SECURITY_ATTRIBUTES，SDDL 为 `O:<sid>D:P(A;OICI;FA;;;<sid>)`；不是先公开创建再 Set-Acl。每个对象创建后重新 open/读实际 descriptor 验证。Windows [文件安全合同](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights) 支持创建时指定 descriptor。

已存在 parent 必须完全通过 private gate；缺失 parent 才原子创建。任何已存在 run 或异常对象拒绝，不修复、不删除。部分创建失败保留现场，再次 Prepare 拒绝该 ID。创建前对同 ID 旧 B1 路径只做 GetFileAttributes 元数据碰撞检查；不枚举/读取/迁移旧 run 内容。

脚本实际创建空 fixture `7c668cc4b70d4ea2960aacafa2769109`；初次独立测试 fixture 为 `772a67250c6245e69eea85099a6006de`，补强有效根跨模式断言后的最终 flow fixture 为 `4264ed6a43194e99bb6933026a91d764`。三者保留；没有 client-token 或模型/浏览器用户数据。fixture 测试只在自己新创建的 data 目录进行 ACL/junction 变异并复原，未操作真实 LocalAppData 目录。

[Prepare 结果](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/script-prepare.json)、[脚本聚焦结果](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/script-results.json)。已对旧 B1 ID `4a5ae09cbaa24be0af840b1c7efda64c` 实际执行元数据碰撞拒绝；未创建同 ID Profile run，未读取旧凭据，见 [碰撞记录](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/legacy-collision-rejected.log)。无法在禁止 Credential Manager 查询的范围内证明一个从未留存目录的历史 ID 是否曾使用；合同要求 caller 使用全新随机 ID，目录存在性检查提供额外拒绝。

## 7. Runtime/Desktop/Chrome 一致派生

| 资源 | 同一新 root/run ID 下固定值 |
| --- | --- |
| Runtime | `http://127.0.0.1:18765`；真实 acceptance RuntimeClient factory，无请求执行 |
| Credential target | `PersonalAiWorkspace/Desktop/MMF3/<run-id>/Runtime/127.0.0.1:18765` |
| token import | 仅 `<root>/credentials/client-token`；本轮没有创建或读取 token |
| model state / data | `<root>/model-state` / `<root>/data` |
| WebView2 UDF | `<root>/webview2-profile`，现有 WorkspaceProfile revalidation 复用 |
| Chrome / extension | `<root>/chrome-profile` / `<root>/browser-test-extension` |
| evidence / logs | `<root>/evidence` / `<root>/logs` |
| SingleInstance | 既有 `.MMF3.<run-id>` suffix；新随机 ID 隔离 |
| Desktop 参数/标识 | `--mmf3-profile-acceptance <run-id>` / `MMF3 PROFILE TEST / 18765` |

脚本输出上述资源映射，没有启动命令执行权限或任意路径输入。Runtime Java 接入未实施；Chrome 参数/UDF 多进程实际使用仍待另案验收，不能把映射输出当真实集成成功。

Program 在任何 app/mutex 构造之前调用 Parse；失败直接退出 2。现有 AssistantApp 继续注入 run credential target、18765 factory、原生 WPF model confirmation、Bridge，并跳过正式 hotkey。正式 RuntimeClient.Endpoint 和 CredentialStore.Target 仍保留 8765，Profile admission 没有 fallback。测试只构造 CredentialStore 并检查其 target 字段，未调用 Load/Import/Delete 或 CredRead/CredWrite。

## 8. 旧 B1 保持证据

旧构建仍是 `Mmf3AcceptanceBuild=true`、`--mmf3-acceptance`、LocalAppData/PersonalAiWorkspace-Mmf3、旧 label/target/UDF。旧 ValidatePrivateDirectory 和 token import gate 原体保留在非 Profile 编译分支；没有将新祖先规则或宽松 ACL 合并进旧 B1。

实际 Legacy DLL 的只读 focused test 检查派生值、18765 transport、缺失根/跨模式拒绝。旧 B1 的攻击性 LocalAppData fixture 测试源码保持，**本轮没有执行**；它的历史 PASS 不记为本轮结果。Program、RuntimeClient、CredentialStore、SingleInstance、AssistantApp、WorkspaceProfile/WorkspaceWebViewHost、NativeModelConfirmation 和 Bridge owning source 均在 baseline 中逐字节保持。没有实际原生 confirmation/Bridge GUI 执行声明。

## 9. Release 隔离

实际 Ordinary Release DLL 无 AcceptanceLaunch/ProfileState；Core 无 CreateMmf3Acceptance；Profile/Legacy 参数退出 2。Profile opt-in Release 实际 build exit 1，错误为 `Mmf3ProfileAcceptanceBuild is Debug-only.`。

双 opt-in 和 Profile+MainWorkspaceDev 实际 build exit 1，分别为互斥与 bundled production content 错误。不是只检查 MSBuild 文本；保留真实 build 输出，[负向构建结果](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/negative-builds.json)。没有 publish/package。

## 10. 本轮实际 focused verification

复用既有 owning-layer integrated test 添加 Profile 分支；新增一个只读编译身份/跨模式测试，以避免在 B1 STOP 时运行旧 LocalAppData 变异流。没有逐覆盖点拆分测试或新增其他层重复 tests。

实际执行 `dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj`，所有命令均 `-p:FrontendSkipBuild=true --artifacts-path <本轮 evidence>/build/<identity>`，只复用现有 frontend dist 并由既有 target 校验资产，不做 npm rebuild：

| 配置及 opt-in | 实际 filter | 结果证据 |
| --- | --- | --- |
| Debug，两个 opt-in=false | `FullyQualifiedName~CompiledVariantIdentityAndCrossModeArgumentsAreIsolated` | [Ordinary Debug TRX](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/results/ordinary-debug.trx)：PASS |
| Release，两个 opt-in=false | 同上 | [Ordinary Release TRX](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/results/ordinary-release.trx)：PASS |
| Debug，Legacy=true、Profile=false | 同上 | [Legacy Debug TRX](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/results/legacy-debug.trx)：PASS |
| Debug，Profile=true | `FullyQualifiedName~Mmf3AcceptanceTests` | [Profile Debug TRX](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/results/profile-debug.trx)：PASS |

Profile integrated flow：真实私有创建/独立 ACL readback、拒绝重复 prepare、非法/额外/跨模式参数、缺失根、环境变量不能改变根、限定 target/UDF/import、非 Protected/foreign ACL、foreign ancestor replacement predicate、真实 junction、18765 transport、最终 revalidation。正确参数只调用 Parse，**不调用有效 Program.Main**，没有 GUI。交付前发现跨模式断言还应在有效新根存在时执行，以排除“仅因根缺失才失败”的误证；补充该断言后只重跑受影响的 integrated flow，见 [最终 Profile flow TRX](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/results/profile-final-flow.trx)：PASS。其他已绿身份验证没有重跑。

owner mismatch 的证据是从实际 fixture descriptor 取值后仅替换 owner 字段，调用同一校验 predicate；未在 OS 上创建 foreign-owned 目录。foreign ancestor predicate 使用实际新 fixture 添加 Everyone DELETE_CHILD 后读回的 descriptor；没有获取 foreign token或对真实 Profile 祖先实施攻击。[fixture 记录](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/focused-fixture.json)。覆盖点不是独立测试执行数。

另实际执行三个必要负向 build、一次空 fixture Prepare、脚本默认只读/无 opt-in/已有 run/非法 ID/额外 root/Verify focused probes、旧 run metadata collision 拒绝、SHA 保留、index 比较、Git diff/patch reverse/whitespace 检查。重跑仅限上述新断言对应的 flow。

## 11. 初次失败与修正

首次脚本 Prepare 在任何创建之前失败：`MMF3 token/profile owner mismatch.`。原因是初版额外把 Profile 的文件系统 owner 强制等于 current SID，误把账号关联与 NTFS owner 混为一谈。本机实际 owner 为 Administrators。

只读核对 token SID、真实 owner/DACL 与系统 ProfileList 后，将 Profile owner 验证恢复为已经审查的祖先可信边界；账号归属仍由同一有效 token 的 Profile API 保证，新 parent/root/子目录 owner 必须 current SID。没有改任何现有 ACL、增加 trusted SID 或放宽 private DACL。本次失败未计为 PASS，没有留下部分创建目录。

此后脚本 Prepare 和初次 Profile focused tests 通过；新增有效根跨模式断言后的最终 flow 也通过。没有生产/test 编译失败或测试失败；必要的负向拒绝是预期结果，单独列明。两个 test fixture 记录均保留在 [all-focused-fixtures.json](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/all-focused-fixtures.json)。

## 12. 完整独立源码补丁

[source-review.patch](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/source-review.patch) 只包含本轮四个源码/测试/脚本文件：

1. `desktop/Directory.Build.props`
2. `desktop/src/PersonalAiWorkspace.Desktop/Mmf3AcceptanceLaunch.cs`
3. `desktop/tests/PersonalAiWorkspace.Desktop.Tests/Mmf3AcceptanceTests.cs`
4. `scripts/verification/mmf3-profile-state.ps1`

基于指定 HEAD；前三个是 diff，新脚本是完整新增文件。没有混入两个既有候选、报告、ignored evidence 或 binaries。实际 `git apply --reverse --check` 和 reverse whitespace error check 成功，仅检查，未应用补丁。[四文件 SHA](../../.verification/mmf3-profile-acceptance-3024df82c9b74dacb5288dd582f922bd/source-identity.json)。本文不进入源码 patch，避免 hash 自引用。

## 13. Patch SHA-256

```text
f452d1714a20bb28005fd1e59b5240ad1ed818f4fab41427f9375731eb2bac0a
```

原 Java / Bootstrap patch 身份仍为 §2 两个独立 hash。

## 14. 未执行项目

没有真实 Runtime/Spring/JNA 启动、Desktop GUI、Chrome/WebView2 环境、Credential Manager、验收 token、模型推理/VALIDATE/SWITCH/Release、Ollama 管理、正式 Launcher/Package、Browser 源码修改或任何 Git publication。

没有 Java/Desktop/Frontend full suite、旧 B1 LocalAppData 攻击性 fixture、完整 browser/IME/stress/packaging matrix。没有重新执行 Java ACL / Native Bootstrap / JVM loader 的历史测试；既有报告结果属于历史证据。没有迁移、读取、修复或删除旧 run 的状态/credential，也没有更新旧 current-run 指针。

## 15. Remaining blockers

Profile Candidate 尚需独立 Source Review；本轮空 fixture/编译证明不批准多进程真实验收。Runtime 测试入口、Trusted JNA Loader 的生产来源/统一入口/生命周期、原 LocalClientToken IOException、SQLite WAL/SHM/journal 权限、既有状态兼容性均未关闭。

LocalAppData ancestor authority、旧 Helper metadata pin 被反证、旧 B1 PATH_IDENTITY/非 Protected 现场继续 STOP。本轮不修复它们。没有证明外部 capability 的完整 token 有效权限、所有 Windows/filesystem/filter 组合、恶意同 SID 或历史危险 handle。

本机 Profile 路径和权限合同已通过上述有限实际验证；没有发现要求扩大四文件源码范围的 blocker。将来若真实多进程路径兼容无法通过，必须 STOP，不回退旧 LocalAppData 或放松 gate。

## 16. Candidate GO / STOP

**Profile 开发候选：可交独立 Source Review（development candidate GO）；Profile Source Approval：PENDING。** 本轮按授权完成后停止，不执行真实集成。

**现有 B1：STOP。Production Integration：STOP。MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**
