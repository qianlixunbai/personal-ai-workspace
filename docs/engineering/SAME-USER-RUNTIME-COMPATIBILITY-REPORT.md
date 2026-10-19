# Same-User Windows Runtime Compatibility

2026-10-19（Asia/Shanghai）。**诊断 SOURCE IMPLEMENTED / 聚焦合同 TEST EVIDENCED；同账户正常启动与真实 Desktop E2E PENDING。**

本轮没有发现可在原固定根合同内、同时不修复已有 ACL 的可靠启动修复。当前必须校验的共享父目录不合格，
换解压位置或 run-id 无效。新目录合同需要用户按任务 §六另行授权；本报告 §五给出可审查的最小变更方案，尚未实施。
不建议第二账户、虚拟机、额外设备或反复 ACL 恢复作为产品解决办法。

## 一、源码级根因与实际证据

| 所有者 | 当前实际合同及影响 |
| --- | --- |
| `desktop/src/PersonalAiWorkspace.Desktop/Mmf3AcceptanceLaunch.cs` / `Mmf3ProfileState` | 用有效 Windows token 的 `GetUserProfileDirectoryW` 计算用户主目录；固定 `ParentName=.personal-ai-workspace-mmf3-tests`；根为 `<home>/<ParentName>/<32位run-id>`。不由 ZIP、USERPROFILE 或用户输入任意路径决定 |
| 同一 `State/ValidateDescriptor` | 先验证 home 的所有祖先，再验证共享 parent、run 根和 8 个必要子目录。私有目录必须当前 SID owner、protected DACL、恰好一条当前 SID 的 OI/CI FullControl Allow。Prepare 在校验既有 parent 失败时不会创建新 run，也不修复 parent |
| `bootstrap/PersonalAiWorkspace.NativeBootstrap/TrustedNative.cs` / `Win32.cs` | 独立重新从有效 token 推导相同路径。`PrivateAcl` 同样要求精确单 ACE；多出的只读 ACE 也返回 `PRIVATE_ACE`。祖先规则不同：受信 owner + 原 mask `0x500d0040` 排除非受信 SID 的替换/权限修改能力，允许普通读取/遍历 |
| `src/main/java/io/github/qianlixunbai/workspace/security/WindowsNativeGate.java` | 验证固定 helper 四文件 pin，直接子进程 VerifyProfileNative；只接受固定协议、run-id、根父目录名、DLL 路径/hash/文件身份；加载前后重验。失败不加载 JNA、不启动 Spring；不能只改 WPF 路径 |

本轮实际只读结果：

- token 推导 home 为当前账户主目录。原共享 parent 为当前用户 owner、protected DACL，但有两条显式 OI/CI Allow：当前用户 FullControl，以及 `CodexSandboxUsers` ReadAndExecute/Synchronize。
- 用新随机 run-id 执行原 `mmf3-profile-state.ps1 -Mode Inspect`，退出 1，`MMF3 private ACE mismatch.`；新 run 目录不存在。没有选择、读取或修改旧 B4A6 Profile 内容。
- 原 helper 的 apphost 哈希核对与既有 pin 相同后，仅调用 `VerifyProfileNative <该随机id>`；退出 10，`STOP / PRIVATE_ACE / win32=0`。它先拒绝共享 parent，因此并不需要新 run 存在。
- `Get-Acl` 快照及原 `ValidateDescriptor` 的实际只读求值显示：home 满足 ancestor descriptor 合同；AppData 与 AppData/Local 均被判定 `MMF3 foreign ancestor replacement authority.`。其中有非受信 capability SID 的有效 FullControl ACE。
- 已存在的 `Local/PersonalAIWorkspace` 自身 ancestor descriptor 合格，但未保护继承，不能作为私有 parent；而其上游 AppData/Local 已不合格。候选 `Profiles` 目录不存在。

这说明冲突发生在**必须保持精确私有 ACL 的共享父目录**，并非 JDK 所在解压目录，也不是 DPAPI 解密或 PostgreSQL 错误。
对已创建的新 run 而言，之后任一必要私有目录被增加 ACE，同样会在复验时失效。

事实与推断边界：本轮确认额外 ACE 存在及其触发拒绝；未追踪到具体哪个进程、服务、时间或权限刷新操作写入了它。
“开发工具访问/刷新权限导致 ACE 增加”与历史现象一致，属于合理假设，不能据此认定某个服务恶意或按进程名称授信。
尚不知道 Codex 会否主动遍历并修改任何新应用根，也未证明退出 Codex 会恢复 ACL。没有修改服务、用户组、系统代理或权限。

## 二、完整启动、持久化与路径边界

WPF `Program` 无参数进入原生 Profile 选择/创建；创建沿 `TrustedDevelopmentRuntimeSources.Open → Prepare → Provision`，
随后 `Mmf3AcceptanceLaunch.Parse → State.Verify → Native.Verify`。上述步骤成功后才构造单实例、Assistant、凭据和 WebView。
本轮只在这个启动前 catch 增加固定提示，原 return 2 保留。

`DevelopmentRuntimeOwner` 沿原 `StartInfo` 再验 Profile/Gate，启动同一 pinned Java，使用固定 loopback 18765 和 Profile 派生的
token/data/model-state 参数。Java 再执行 Gate 与精确参数验证，随后才启动应用。owner/job 持有直接子进程、退出/取消清理；本轮未改变生命周期。

`TrustedDevelopmentRuntimeSources` 内嵌 v2/v3 预期 hash；v3 只解析固定包内 runtime 相对路径，检查祖先非 reparse 并持有拒绝写/删的文件读取句柄。
侧边 manifest 不能签发信任。本轮没有放宽来源、改 JDK/JAR 或重编 helper。

Native `Win32.Identity` 验证句柄最终路径、对象类型、reparse、volume/file-id，普通文件硬链接数必须为 1；
DLL 从固定 JNA JAR 提取，以 CREATE_NEW 私有 staging、同对象不替换发布、重开身份与 hash 校验。
目录元数据句柄并非完整 rename pin，现有源码也明确依赖 ancestor ACL 排除外部替换；不能扩张为同 SID 攻击防护承诺。

`TrustedDatabaseConfiguration` 仅在 Gate 后读取 `<admitted-root>/data/database.dpapi`，使用 CurrentUser DPAPI（UI_FORBIDDEN，未设 LOCAL_MACHINE），
无调用者目录，无自动迁移；CREATE_NEW、不覆盖、owner/ACL、实际 Win32 identity、硬链接及读写时复验保留。
SQLite 与 Knowledge/模型状态继续由该 Profile 的既有数据配置派生；用户状态不进入安装/ZIP 目录。

DPAPI 通常约束登录凭据及机器，不能区分同用户的任意普通进程；源码也没有提供按应用 SID 的额外秘密。
[Microsoft CryptProtectData](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata)。

逻辑备份恢复仍沿 `MemoryBackupService`、`WorkspaceBackupService`、`KnowledgeBackup` 的离线/新目标、拒绝覆盖与位置校验边界；
统一 PostgreSQL/加密维护恢复不等于 Runtime 准入，恢复后仍需身份重绑。数据库 DPAPI 是设备本地配置，不是可移植备份的一部分。
本轮未调用恢复、未复制旧密文、未变更备份格式或目标规则；新候选目录不能自动使旧备份目标成为受信 Profile。

## 三、同账户可行性与威胁模型

**同账户共存技术上可行，但当前工程/环境尚未验证可靠共存。** 当前 SID 可拥有新的受保护私有目录，且无需换 Windows 身份；
目录选择能减少开发路径与受信数据路径重叠，不能阻止同 SID 进程再次修改 ACL 或读取该 SID 的数据。

| 目标 | 结论 |
| --- | --- |
| 检测意外增加的权限、拒绝不受信 Profile | 继续严格拒绝；不能自动修复来掩盖失败 |
| 防止其他普通账户通过本地文件权限读取私有数据 | 精确私有 ACL + 合格祖先提供既有保护；不涵盖管理员/kernel 或已取得该用户 authority 的进程 |
| 防路径替换、重解析点、硬链接、来源篡改 | 继续使用原 identity/ACL/hash/句柄复验；不增加新的受信身份 |
| 减少与开发工具的不必要目录冲突 | 新固定位置有验证价值；是否持续被增加 ACE 仍 PENDING |
| 仅靠普通 ACL + CurrentUser DPAPI 隔离同 SID 任意恶意进程 | **NOT FEASIBLE**。不能把不同文件夹称为完整应用安全边界 |
| 在已有不合格 parent 上不改 ACL、不改固定根合同而正常准入 | 当前条件下不可实现；继续 fail-closed |

## 四、目录候选比较

| 位置 | 当前判断 |
| --- | --- |
| `<token-home>/.personal-ai-workspace-mmf3-tests/<id>` | 现行唯一合同，shared parent 已不合格，所有新 id 同受影响 |
| `LocalAppData/PersonalAIWorkspace/Profiles/<id>` | 常见应用数据布局，但本机上游 AppData/Local 的替换权限被原合同拒绝；不推荐本轮通过放宽 ancestor policy 使用 |
| Windows Known Folder API | 若将来采用 LocalAppData，应以有效 token 的 `SHGetKnownFolderPath(FOLDERID_LocalAppData)` 查询，不采信 LOCALAPPDATA 字符串，并单独验本地规范路径/ACL。API 返回路径并不证明该路径安全，不能解决当前祖先失败 |
| `<token-home>/.personal-ai-workspace-development-profiles-v1/<id>` | **推荐申请授权的最小 Development-only 候选**。本轮只查不存在，home 祖先已由原 helper 走查；不创建目录，不证明新根持续可用 |

Known Folder 对有效用户 token 的要求见 [Microsoft SHGetKnownFolderPath](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath)。
没有把该 API 当作新安全 authority；本轮推荐仍沿原 GetUserProfileDirectoryW，以避免引入 AppData 的已知祖先失败和新的定位机制。

## 五、待授权的最小源码方案（未实施）

### 新旧合同差异

新开发版本将唯一固定根从旧 ParentName **替换**为 `.personal-ai-workspace-development-profiles-v1`，仍从同一有效 token home 推导。
不接受任意目录、环境变量或可配置根；不添加自动搜索、旧根失败 fallback 或多候选轮转。
只为全新目录设置精确私有 descriptor；候选根若已存在不合格，立即停止，既不删除 ACE 也不另找根。

```text
<effective-token home>/
  .personal-ai-workspace-mmf3-tests/              # 保留旧版本/旧数据，不访问其内容
  .personal-ai-workspace-development-profiles-v1/ # 待批准的新唯一固定 parent
    <fresh 32-hex id>/
      credentials/  model-state/  data/          # database.dpapi 仍仅 data 下 CREATE_NEW
      webview2-profile/  chrome-profile/  browser-test-extension/  evidence/  logs/
      jna-5.17.0-win32-x86-64/
```

改变的是**根位置及对应 helper 二进制信任 pin**，不是私有 ACE/ancestor 身份集合、DPAPI 保护范围、DB 格式或 Runtime 权限。
这些位置与 pin 本身就是安全合同的一部分，所以即使没有放宽 ACL，也必须获得授权后再改。

### 精确代码范围

1. `Mmf3AcceptanceLaunch.cs/Mmf3ProfileState.ParentName` 与 `TrustedNative.Run` 的固定 parent 同步更改。
   原 `mmf3-profile-state.ps1` 已从 owning source 提取合同，无需另起路径实现。全新 GUID 必须不与旧固定根及历史 LocalAppData legacy run 冲突，只查元数据，拒绝复用。
2. `WindowsNativeGate.verify` 的固定父名检查同步；保留有效 token helper 原验证、协议、加载前后身份/hash 复验及精确 Runtime 参数。
   WPF 原选择器仍只接受新版本派生的唯一精确根；选旧根时仅给兼容提示，不读取旧 credentials/数据。
3. 在审查新 helper 源码和构建身份后，显式更新 `WindowsNativeGate.BOOTSTRAP`、`Mmf3ProfileNative.ValidateHelper`、
   `scripts/distribution/development-bundle.ps1` 的四文件 pin；不能让构建脚本自动接受任意本机重编 hash。
4. 更新原 GUI 固定位置提示、包说明以及原 Profile 合同 fixture 的位置断言；不改 `WindowsPrivateAcl`、DPAPI、Store 或 owner/job。
   重新构建 JAR/Desktop/新命名开发 ZIP，不覆盖当前包。生产 launcher 继续阻断。

### 旧 Profile、恢复、升级和回滚

旧 Profile、receipt、备份和旧 ZIP 原样保留；新版本不自动迁移或降级回退、不双写、不导入 DPAPI 文件。
这是明确的开发版本路径不兼容：旧 Profile 不会在新包选择器中直接打开，仍属于旧包及旧 Gate；旧包也仍会拒绝其不合格 ACL。
需要旧数据时必须另行设计/批准逻辑恢复和设备配置重绑，本次授权建议仅限全新合成 Workspace。

应用包搬迁不改变 token-home 数据位置。未来安装/卸载/升级必须保持此数据根外置且不清理；本轮不新增安装器或更新协议。
回滚通过退出 owner/Java 后使用保留的旧包；新 Profile 留在原处不搬回、不改 ACL。旧包不能读取新根，回滚不保证旧环境恢复可启动。
schema 没有变化，不能把二进制回滚当作数据库回滚。

### 失败拒绝与验收设计（全部尚未执行）

复用现有 owning-layer Profile/native 合同和 `RuntimeLifecycleProbe`，只补根变更带来的实际缺口：

- 同一聚焦 Profile 合同：环境变量覆盖/任意根被拒绝、旧根不能冒充新根、跨旧根 run-id 冲突拒绝；extra read ACE、继承、foreign owner、危险 ancestor 继续拒绝；合格新建只发生 CREATE_NEW。
- 原 native 负向合同继续验证 reparse/junction、硬链接、路径/文件身份及 DLL/JAR 篡改；helper pin 漂移拒绝，不另造一套 ACL policy。
- 当前 Windows 账户保持 IDEA/Codex 可用，由 WPF 自己创建合成 Profile 并自行验证，避免为取证让工具直接扫描新私有根；
  一次综合流覆盖 Java → React/WebView2 → SQLite StorageStatus → 退出且直接 Java 子进程终止，再从同一 Profile 重开检查持久可用性。
  无 PostgreSQL/Finance/模型推理；不读旧 DPAPI。该流不是本轮已经通过的测试。
- 若环境仍向新根添加 ACE，立即停止，报告不兼容；不得换另一个根继续碰运气。此时仅靠目录布局不能交付可靠共存，须另行确定环境支持能力，不能承诺 ACL 能阻止该操作。

## 六、本轮实际修改与执行

| 变化/检查 | 结果与证据边界 |
| --- | --- |
| `Program.cs` | Profile Debug 启动失败区分精确私有 ACL 与危险 ancestor，固定中文提示原因、范围及停止条件；未转发异常自由文本/账户/路径/helper 输出，不改变返回值或准入 |
| `Mmf3AcceptanceTests.cs` | 一个纯 descriptor 综合合同，确认 foreign read ACE 私有目录拒绝而 ancestor 允许；加 delete 则 ancestor 拒绝；未知异常只显示固定消息。无真实目录创建、ACL 修改、GUI 或 helper |
| `README-development.txt` | 去除第二账户作为最终方案的建议；明确旧固定 parent 冲突及停止条件 |
| 聚焦 `dotnet test ... --filter FullyQualifiedName~ReadOnlyForeignAceRemainsRejectedWithSafeStartupDiagnostic` | PASS，`.verification/same-user-runtime-focused-final.log`；同时编译 Profile Debug Desktop，复用现成前端资产验证，无 Java 测试 |
| 普通 Release `dotnet build ... --no-restore -p:FrontendSkipBuild=true`，两 acceptance 属性 false | PASS，0 警告/错误；`.verification/same-user-runtime-release-build.log`；仅编译，不是启动证据 |
| 原只读 State Inspect 与原 helper Verify | 如 §一，均正确拒绝；Inspect 日志 `.verification/same-user-runtime-inspect.log`。helper 输出来自本轮实际调用，不是历史结果 |
| 实际 ACL descriptor 求值 | `.verification/same-user-runtime-acl-descriptors-final.json`。只证明 descriptor 谓词，不冒充全路径 identity/Gate 正向通过 |

开发中测试初次编译因新增用例误用 RawSecurityDescriptor 构造参数失败；修正测试后聚焦运行通过。
一次临时 PowerShell descriptor 检查的 typed catch 未捕获 Invoke 包装异常，导致旧 outcome 值不可采信；已修正捕获和断言，以上只采用 final JSON。
没有为这两个验证问题修改正确的生产安全行为。

没有重新运行 Java/Desktop/Frontend 全套、Finance、备份矩阵、GPU/Vision 或真实付费服务。
没有重新打 350+ MiB ZIP：当前仍被已确认的 Gate 阻断，新根实施还需授权，重新打包不能证明兼容。
原 ZIP 保留且**不包含本轮新提示/说明**；本轮编译的 Desktop 只作源码验证，不冒充新的可用开发交付包。

## 七、状态、Git 与唯一下一步

| 分类 | 本轮结论 |
| --- | --- |
| SOURCE IMPLEMENTED | 固定中文启动诊断与开发包说明；根/ACL/DPAPI/来源/生产 launcher 合同保持原样 |
| TEST EVIDENCED | 上述聚焦 descriptor/消息合同、Profile Debug 编译、普通 Release 编译 |
| REAL WINDOWS VERIFIED | 仅当前 ACL 只读检查及原 Gate 负向拒绝；没有真实启动 Desktop 主窗口、Java 或 WebView2 |
| PENDING | 新根授权/实现/新包；同账户真实合成 SQLite 综合流与长期共存；真实 Windows E2E、Finance Binding、可信安装与 Production GO |
| NOT FEASIBLE | 用普通同 SID ACL + CurrentUser DPAPI 保证隔离同 SID 任意恶意进程；在现有不合格 parent 上不改任何合同/ACL而准入 |

从实际基线 `03295dabd6dcbaf9bd7d95f27c6db0d23bd0607c` 创建 `codex/same-user-runtime-compatibility`。
本轮本地提交范围为 `03295da..HEAD`，精确最终 HEAD 以 Git 交付为准；没有 fetch，未声称核验远端。
仅提交本轮 owning code、聚焦测试、README、本报告与 STATUS；原 8 个 B4A6 未追踪文件保留在工作树中，不纳入本轮提交。
原 receipt 与原安全实现首尾复算哈希保持一致；未修改旧 Profile/ACL、备份、数据库/角色/schema、冻结 Finance 仓库或真实用户数据。
没有 push、merge、reset、stash、clean、amend、tag 或 release。交付时 tracked clean，整体仍有原 8 个未追踪文件。

**唯一建议的下一步：用户明确授权 §五的固定新根替换及经审查 helper pin 更新，仅用于当前账户全新合成 Development Profile。**
授权后由代理沿现有入口实现、构建及验证，用户无需逐条 PowerShell、修 ACL 或操作 B4A6 receipt。
最终 GUI 验收仍是解压新包 → 双击 EXE → 创建合成 Workspace → 查看 SQLite StorageStatus → 退出；
不可避免的外部前提仍为原 helper 所需 .NET 10 x64 Runtime、WebView2 Evergreen，以及合格且未被额外修改的目录权限。
此授权不包括旧数据迁移、DPAPI 归属变化、ancestor/私有 ACL 放宽或 Production Launcher 解锁；这些若成为必要条件，必须另外申请。
