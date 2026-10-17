# MMF-3 — Trusted Native Bootstrap Candidate

2026-10-09（Asia/Shanghai）。方案 A 与独立 .NET 10 Helper 的本轮实施授权已执行。
**DEVELOPMENT PROTOTYPE — NOT PRODUCTION TRUSTED。**

新增独立 Helper、一个隔离 Windows 文件系统验收流及独立 JVM 加载探针。未接入正式 Runtime/Launcher。
**Candidate：GO，限本轮开发 fixture 的独立源码复核；生产可信部署/正式入口接入：STOP。**
**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**

本轮证据根：`.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/`。
报告以角色表示 profile、目录、current SID；不记录账户名、私人 profile 绝对路径、token 或用户内容。
测试现场全部保留，不自动清理，包括早期失败的全新 fixture；没有重复操作旧失败 run。

## 1. Git Reality

| 项目 | 本轮实际结果 |
| --- | --- |
| branch | `codex/mmf3-runtime-private-acl-fix` |
| HEAD / fresh origin/main | `8dc66d81b78c3efac70feed94aec25ea71dd21a5` |
| fetch | 指定 `127.0.0.1:7890` 单次 Git 代理 fetch 成功，无 fallback/global config 修改 |
| index | 入场及交付 empty |
| 原候选 | 八个 tracked 修改、WindowsPrivateAcl.java/Test.java、三份既有报告，逐文件 SHA 保留 |
| 原 ACL full patch | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06`，实际复算一致 |
| 本轮新非 ignored 文件 | 四个 Helper 文件、三个 focused harness 文件、本报告；均 untracked |
| 交付 working tree | dirty：按授权保留原候选和本轮新增；未 stage/commit/push/merge |

适用祖先/仓库没有磁盘 AGENTS.md；遵循用户提供的 AGENTS.md 和限定授权。
已读取 current architecture、ADR-015、已有 ACL/loader 决策报告、WindowsPrivateAcl owning code 及相关测试。
没有改写既有 ACL 补丁、正式 Java main、启动/打包脚本、B1 固定根、Browser、Finance 或 ADR-015。
没有 reset、clean、amend、rebase、tag、release 或任何 Git publication。

证据：[入场逐文件基线](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/candidate-baseline.json)、
[交付逐文件保留检查](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/preservation-check.json)。

## 2. Native Bootstrap 设计与实际源码

独立 Windows x64 Console 项目，无 Spring/JNA 自举、安装器框架或进程管理职责。
只有两个命令；参数数量/命令名不合法时 exit 10，输出脱敏 STOP JSON：

```text
PersonalAiWorkspace.NativeBootstrap.exe ProvisionTrustedNative <32位小写hex fixture-id> <固定JAR绝对路径>
PersonalAiWorkspace.NativeBootstrap.exe VerifyTrustedNative <同一fixture-id>
```

| 源码 | 职责 |
| --- | --- |
| [项目](../../bootstrap/PersonalAiWorkspace.NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.csproj) | net10.0-windows、x64、warnings-as-errors、无 PackageReference |
| [Program.cs](../../bootstrap/PersonalAiWorkspace.NativeBootstrap/Program.cs) | Windows/x64 gate、精确命令解析、PASS/STOP 脱敏；没有 Launcher/握手 |
| [TrustedNative.cs](../../bootstrap/PersonalAiWorkspace.NativeBootstrap/TrustedNative.cs) | 固定工件、fixture-only 路径、祖先 pin、Provision/Verify、hash/PE |
| [Win32.cs](../../bootstrap/PersonalAiWorkspace.NativeBootstrap/Win32.cs) | effective token、profile API、SECURITY_ATTRIBUTES、descriptor、file identity、句柄发布 |

Native 根固定为：`<TOKEN_PROFILE>/.personal-ai-workspace-native-bootstrap-fixture-<id>/jna-5.17.0-win32-x86-64/`。
这是本轮开发测试根，不是生产安装路径决定。命令不能指定任意目标路径，不能指向源码、Temp、LocalAppData/B1、
credentials/data/model-state 或旧 run；不会把 USERPROFILE 字符串用于信任路径。
Profile 来自同一 effective token 的 [GetUserProfileDirectoryW](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-getuserprofiledirectoryw)。
先尝试 thread token，仅 ERROR_NO_TOKEN 才取 process token；验证 SID 后，以该 SID 检查完整 home 祖先链。
本机系统盘根 → Users → TOKEN_PROFILE 的当前严格祖先 predicate 实际通过；没有放宽 LocalAppData 判定。

## 3. Trust anchor 与限制

独立批准的输入是本轮用户指定的 JNA 坐标、JAR/DLL hash、entry、长度、machine；这些值编译进 Helper。
使用本轮前已存在的 Maven cache JAR，单句柄读取有界完整 JAR、核验固定 hash 后在内存提取唯一获准 entry。
没有网络下载或加载临时网络 DLL，没有接受同目录 hash manifest 作为独立信任来源。

| 固定身份 | 值 |
| --- | --- |
| JNA | `5.17.0` |
| JAR SHA-256 | `b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0` |
| 唯一 native entry | `com/sun/jna/win32-x86-64/jnidispatch.dll` |
| DLL SHA-256 | `5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023` |
| DLL 长度 / PE | `273408`；MZ/PE、AMD64 `0x8664`、PE32+ `0x20b`、DLL characteristic |

**没有生产级独立发布/安装信任根、Helper 签名、发布者认证或受信 Launcher。**
可写仓库中编译的 Helper 与本轮 binary hash 只能标识待审开发候选；攻击者可改 Helper 时，常量本身也可被改。
本轮 hash 核验不被描述成代码签名/供应链认证。生产准备还需受审发布来源、可信 Helper/.NET/JVM、固定 Java/JAR 及许可证交付。
本轮没有发布 JNA binary；未来独立分发 DLL 须携带 JNA 适用 license/notice，不得遗漏拆包后的许可责任。

## 4. 依赖与构建身份

实际 SDK `10.0.401`，MSBuild `18.9.11`；.NET runtime `10.0.12`；Windows `10.0.26100`、x64。
沿用仓库 global.json；没有修改 SDK/依赖配置或系统安装。
Release build 为 framework-dependent apphost，需 .NET 10；没有 self-contained publish、打包或 runtime 下载。
OS P/Invoke 使用 assembly-level `DefaultDllImportSearchPaths(System32)`；仅 kernel32/advapi32/userenv。
Helper 不加载 jnidispatch，不依赖 JNA/管理员权限，不启用 token privilege。

执行 `dotnet build bootstrap/PersonalAiWorkspace.NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.csproj -c Release`，
必要修正后最终 `--no-restore` build 成功：0 warnings / 0 errors。
最终加入明确 ProcessArchitecture.X64 gate 后只重建 Helper 并执行一次 existing-valid fixture Verify smoke；
Windows/JVM 已绿验收流没有机械重跑；其 owning Provision/ACL/hash/loader 代码没有因该 gate 改动。
[最终 build log](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/build.log)、
[构建输出逐文件 SHA](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/build-identity.json)、
[最终 x64 smoke](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/final-x64-smoke.json)。

## 5. Provision 实际行为

1. 验证平台、命令和 fixture-id；从 effective token 得到 SID/profile，检查所有真实祖先。
2. 祖先用 OPEN_REPARSE_POINT/BACKUP_SEMANTICS 打开，核验 disk/type、normalized final path、file ID；持有句柄禁止 delete sharing。
3. 完整 JAR hash/唯一 entry/长度/DLL hash/PE 在创建目标之前核验；从已核验内存快照获取 DLL。
4. 使用 CreateDirectoryW + SECURITY_ATTRIBUTES 原子新建 fixture root/version 目录；existing root 一律拒绝，不复用或修复。
5. 目录创建 descriptor：current SID owner、Protected DACL、唯一 current SID FullControl，OI/CI。
6. CreateFileW(CREATE_NEW) 创建随机 staging DLL，创建时 owner=current SID、Protected、唯一 owner FullControl、无继承 flags。
7. 写入前从真实句柄读取 descriptor/identity；写入、FlushToDisk 后，按同一 handle 核对长度/hash/PE。
8. SetFileInformationByHandle(FileRenameInfo) 发布同一对象到 final DLL；ReplaceIfExists=false，绝不覆盖已有 final。
9. 发布后核对同一 handle file ID/path、owner/DACL/hash/PE，再通过 final path 独立 reopen 比较对象 ID 并重复验证。
10. 结束前复核 held ancestor/private directory descriptors。所有不确定结果 STOP；没有自动 ACL 修复、重试安装或现场清理。

发布按 [FILE_RENAME_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_rename_info) 的 x64 布局；
source handle 保持 READ/WRITE/DELETE，其他打开仅 share-read；发布后的只读 reopen 为与该持有 handle 兼容而 share read/write/delete，
**仍由 source handle 的 share-read 限制新的写入/删除**。日常 Verify 不使用该发布专用 sharing。
没有“公开创建 → 写 DLL → Set-Acl”的窗口；没有修复既有身份异常 DLL 的路径。

## 6. Verify 实际行为

Verify 仅使用 open/read-security/read-data/read-attributes。不会 mkdir、写入、SetSecurityInfo、提取、下载、rename 或删除。
完整检查：当前 SID、root/version 目录 owner-only Protected DACL、祖先 owner/替换权、reparse/type、canonical handle path、
volume + 128-bit file ID、DLL single link、长度、hash、固定 PE；结束前复核 descriptor/identity。
文件 share-read，拒绝已有兼容性不足的 writer/delete handle；目录持有期间不 share-delete。
读取到的 owner/DACL 来自 [GetSecurityInfo(handle)](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo)，
复制真实 Windows descriptor 后解析 ControlFlags/RawAcl，不比较 Get-Acl 展示字符串或自生成 SDDL。
私有对象只接受一个普通 current SID Allow FullControl；目录精确 OI/CI，文件无 flags，拒绝 inherited、foreign、未知 ACE 或 null DACL。
不是 File.Exists 或 hash 单项成功即 PASS。

## 7. Windows ACL 与文件身份结果

独立 Get-Acl binary descriptor/RawSecurityDescriptor 检查了本轮新 root、version 目录及 final DLL：
owner=current SID、Protected=true、唯一显式 owner FullControl、foreign Allow=0、inherited=0、reparse=false。
Provision 与二次 Verify 返回同一 volume/file ID；真实 descriptor/hash/LastWrite 快照在 Verify 前后相同。
负向 fixture 的 mutation 由 harness 显式完成；Helper 的失败不修权限、不恢复原 DLL、不清理 fixture。
没有管理员运行；最终 PASS JSON 的 token elevation=false，harness 也拒绝 elevated 流程。

Owner mismatch 使用**真实系统卷祖先 descriptor 的只读 owning PrivateAcl predicate**，实际 OWNER 拒绝。
没有启用 restore/take-ownership privilege，也没有声称做了 foreign-owned DLL 的 CLI end-to-end mutation。
Unsafe ancestor 使用**本轮全新 fixture 根上的真实 foreign DeleteChild ACE**直接执行 owning AncestorAcl，
实际 ANCESTOR_REPLACEMENT；同一 unsafe root 的 CLI Verify 因 PRIVATE_ACE STOP。
没有为测试给系统盘根/Users/home/LocalAppData 添加权限。两项 predicate evidence 与其他 CLI evidence 明确区分。

## 8. 原生加载器失效回退测试

独立 JVM 使用固定五属性，不修改 Java main/正式启动；拒绝存在 JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS 的 harness 环境。
本机这些来源没有附加覆盖。使用解析后的真实 `java.home/bin/java.exe`，Java `21.0.7`。

```text
-Djna.boot.library.path=<唯一fixture native directory>
-Djna.boot.library.name=jnidispatch
-Djna.nosys=true
-Djna.noclasspath=true
-Djna.nounpack=true
```

| 独立 JVM case | 实际结果 |
| --- | --- |
| VALID_BOOT | exit 0；OS module enumeration 找到唯一 helper provision 的 private jnidispatch，DLL hash 一致 |
| MISSING_BOOT | exit 10；LinkageError 受控 STOP；OS 未发现 dispatch module |
| INVALID_BOOT | exit 10；无效非 PE fixture DLL STOP；OS 未发现 dispatch module |

每个 case 均保留有效 `java.library.path`/cwd dispatch decoy、完整 JAR native entry、classpath file native resource。
失败仍无系统/classpath 回退；隔离 temp 始终 empty。未依赖 jnidispatch.path/jna.loaded 字符串来证明模块来源。
这里只验证固定参数下的 loader 合同；Helper hash-tamper STOP 与 JVM invalid-PE STOP 是不同检查，
没有声称 JNA 本身会拒绝任意 hash 不匹配但仍能加载的 PE。
[JVM 实际结果](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/jvm-results.json)。

## 9. TOCTOU 与外部 SID 权限边界

祖先白名单保持既有候选：current SID、SYSTEM、Administrators、固定 TrustedInstaller SID。
私有对象只允许 current SID；没有加入 capability/其他账户 SID。
祖先拒绝 foreign GENERIC_ALL/WRITE、DELETE、WRITE_DAC/OWNER、DELETE_CHILD；不以 Deny ACE 抵消危险 Allow。
未知 Allow 类型 fail closed；仅跳过不会扩大当前对象授权的 inherit-only/ordinary Deny。

每个祖先 open 不 share-delete，目标文件 hash/identity/ACL 通过同一 handle 检查；发布也是同一 handle。
创建 descriptor 防止 public-write 窗口，复查 descriptor 防止只用创建期声明当证据。
边界仍不承诺防恶意同账号、管理员、SYSTEM/TrustedInstaller、内核或已被篡改的 Helper/runtime。
同 SID 可改 ACL、替换其文件或伪造输出，现合同不把同账号视为隔离主体。

**Helper 退出会释放 pins；没有实现贯穿 Java System.load 的连续 handle pin/受控握手。**
账号外防替换依赖通过检查的完整祖先权限与 owner-only descriptor；hash 不是单独的 TOCTOU 保证。
如果未来要求跨进程连续 pin/loaded-file attestation，应独立审查 Launcher/IPC/lifecycle 成本；本轮没有默默新增这些职责。

## 10. DLL dependencies 搜索验证

本轮重新从固定 DLL 的 PE import directory 解析：`PSAPI.DLL`、`KERNEL32.dll`；delay imports absent。
在独立 JVM 初始化 JNA **前后**读取 OS modules：KERNEL32 在初始化前已由 JVM 加载；PSAPI 在 dispatch 加载后出现。
成功 case 两者均在 GetSystemDirectoryW 返回的 System32；本机 KnownDLLs registry 同时列出这两个名字。
缺失/无效 dispatch case PSAPI 未出现；这与无 dispatch 加载一致，不把依赖未加载误报成路径劫持。

[Windows DLL search order](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-search-order) 的 loaded-module/KnownDLLs
语义与本机实际路径共同提供这些固定 imports 的有限证据。本轮没有 SetDefaultDllDirectories/SetDllDirectory，
**没有把 Helper/父进程 DLL search policy 宣称为 Java 子进程的策略**。
证据只覆盖本机、固定 DLL 和本轮新 JVM；没有证明全部 Windows 版本、后续 Native.load、JVM 第三方模块/注入来源、
生产 Java/Helper/.NET 安装路径或所有启动入口安全。生产统一 Launcher、受信发布源及跨入口 enforcement 未完成，仍 STOP。

## 11. 实际 focused tests 与失败记录

实际执行并最终成功：

- Helper Release build；最终 x64 gate build + 一次只读 Verify smoke。
- [verify-trusted-native.ps1](../../bootstrap/tests/verify-trusted-native.ps1)：一个 integrated Windows flow；Provision、二次只读 Verify、
  缺 DLL、改 hash、非 Protected、foreign Allow、owner predicate、unsafe ancestor predicate/CLI、junction、非法输入、
  non-admin、失败快照不变、拒绝 reprovision、忽略伪造 USERPROFILE；独立读取真实 Windows descriptor。
- [TrustedNativeProbe.java](../../bootstrap/tests/TrustedNativeProbe.java) 编译及
  [verify-jvm-loader.py](../../bootstrap/tests/verify-jvm-loader.py) 的上述有限 JVM probes/import/module checks。
- Git diff --check、原 ACL patch SHA/反向一致性检查、新 source patch 反向一致性/whitespace 检查、入场文件逐 SHA 保留检查。

Coverage points 不是独立 test executions；不使用点数声称测试规模/高覆盖。
[Windows 实际结果](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/filesystem-results.json)。

早期失败均未计为 PASS，现场保留：TOKEN_ELEVATION 传入过大的固定结构长度导致 Win32 24，改为准确 4 bytes；
Set-Acl fixture mutation 请求 SeSecurityPrivilege，改为仅 DACL 的 SetNamedSecurityInfoW；PowerShell reflection 包装/字符串转换
修正仅影响 harness；JVM harness 初期错误要求失败无 dispatch 的 case 也加载 PSAPI，改为仅 successful load 必须出现完整 imports。
已有记录 dependency-observation-blocker.json 的 MISSING_BOOT/PSAPI count=0 是这项 harness 错误，不是确认的系统依赖劫持。
前向 git apply --check 新增补丁因文件已存在而拒绝；实际采用反向 check 验证源码一致性，没有应用/撤销任何 patch。
[失败与修正记录](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/harness-notes.json)。

重跑只用于尚未完成的 focused flow/harness 修正；最终绿色流未因报告或 patch packaging 再跑。
最后 ProcessArchitecture gate 未改变已测 Win32/Provision/Verify/loader 代码，仅执行最小 final Verify smoke。

## 12. 未执行测试与保留阻塞

未启动真实 Workspace Runtime、Spring context、Desktop、Chrome、模型服务或模型管理操作。
未运行 Java/Desktop/frontend 全套、Maven production build/tests、B1 GUI、完整 IME/browser/stress、packaging/release matrix。
未重跑原 ACL candidate suites；旧报告里的绿色结果仅为历史证据。
没有 foreign-owned DLL CLI mutation，没有模拟恶意同 SID 或管理员攻击；不把 real-descriptor predicate evidence 扩大成这些执行。

继续保留：**LocalAppData ancestor blocker 未修复；旧 Runtime 状态兼容未迁移；LocalClientToken 原 IOException UNKNOWN；
SQLite sidecar/POSIX 回归待完整 ACL 候选后续验收；WindowsPrivateAcl 未获生产 Source Approval。**
新增 Native Helper 的有限证据不关闭上述项目。
生产 trust anchor、统一启动入口/不可绕过的早期 Java gate、必要 IPC/pin 合同尚未实现和批准；普通 java -jar 没有获得保护声明。

## 13. 全部新源码 Full Source Review Patch

[source-review.patch](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/source-review.patch)
完整包含 §2 四个 Helper 文件与 §11 三个 harness 文件，共七个新源码/工程文件，基于指定 HEAD，未 stage。
可单独复核；不混入原 ACL candidate、报告自身或 ignored binary/evidence，避免报告 hash 自引用。
[每个源文件 SHA 清单](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/source-review-identity.json)。
已实际 `git apply --reverse --check` 通过；这里只检查，没有应用/撤销补丁。

## 14. Patch SHA-256 与原候选关系

本轮新源码完整 patch SHA-256：

```text
279bc299f1bbe72723e649fa1ae34bb778457cb0962f0f194754a3f26265a549
```

旧 Windows ACL full patch SHA-256 保持：

```text
8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06
```

原八个 tracked 文件和全部入场 untracked 源码/报告逐字节未改。
本轮 Helper 不调用 WindowsPrivateAcl，不把其部分候选接入生产，不改变现有 token/data/model-state 路径、格式或生命周期。
两个 source patch 可以分别审查；新增 Helper 的 GO 不等于原 ACL 候选 Source Approval。

## 15. 后续 Java/Launcher 接入文件与早期 gate 合同

以下全部是待独立 Guard 批准的未来修改清单，**本轮未修改**：

| 准确文件 | 未来责任/兼容性成本 |
| --- | --- |
| `src/main/java/io/github/qianlixunbai/workspace/PersonalAiWorkspaceApplication.java` | Spring context/JNA 前 gate；拒绝缺受信入口的 raw Java 启动 |
| `src/main/java/io/github/qianlixunbai/workspace/security/WindowsPrivateAcl.java` | 如 Guard 要求，native impl 初始化前要求同一 gate；保持 ACL/domain 合同 |
| `scripts/start-workspace.ps1` | dev 入口固定 Helper/native/JVM 来源与属性；日常 verify-only |
| `scripts/release/start-release.ps1` | release preflight 调用同一受审 bootstrap，在 credential/model/service 行为之前拒绝失败 |
| `scripts/release/release-functions.ps1` | 仅确有公共 preflight/manifest 责任时接入；不扩大路径/repair 权限 |
| `scripts/package-windows.ps1` | 可信 Helper/native/JAR/hash/arch/license 分发，独立签名/发布信任来源 |
| `scripts/release/README.txt` | 固定来源、显式准备/更新、raw Java 支持限制、日常失败不修复 |
| `desktop/src/PersonalAiWorkspace.Desktop/Program.cs` 与 `Mmf3AcceptanceLaunch.cs` | 仅 B1 owner 另案授权时接入；固定 LocalAppData fixture 根不随本轮更改 |
| `bootstrap/PersonalAiWorkspace.NativeBootstrap/*` | 将 fixture-only 合同转成批准的生产根，签名/部署/连续 pin 或握手另案设计 |

未来 gate 必须在 JNA 首次 class initialization 和 Spring context 之前完成；固定唯一 verified directory 与五个 JVM 属性，
拒绝外部 boot path/冲突属性/入口绕过。不能以可伪造的 JSON PASS、环境变量、jna.loaded 或验证成功字符串作为唯一安全证明。
若采用持续 Helper pin + 受控 IPC/进程创建，将新增 trusted launcher/进程与 handle lifetime 管理；
若仅支持该统一 launcher，将改变 raw java -jar 支持范围。两项都须独立 Architecture Guard，不在本轮默默实施。
不预设新 Java gate 源文件名字或握手方案；其实际最小 owner 在该独立决定后确定。

## 16. Candidate GO / STOP

本轮已交付独立 Helper 源码与可复核 patch；新 fixture 的安全创建、只读拒绝、固定参数无 dispatch fallback，
以及本机固定 OS imports 路径已有有限实际证据。**开发 fixture 候选可提交独立 Source Review（GO）。**

**生产信任与正式部署：STOP。DEVELOPMENT PROTOTYPE — NOT PRODUCTION TRUSTED。**
没有签名/可信安装来源、正式 Java gate、统一 Launcher、跨进程持有合同或真实产品验收。
不宣布原 ACL、LocalAppData、旧状态、SQLite/POSIX、原 IOException 或模型集成已通过。
没有 commit/publication；按本轮授权完成后停止。

**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**
