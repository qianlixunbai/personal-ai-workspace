# Windows Native Security Modernization

2026-10-19（Asia/Shanghai）。**Development-only 源码重构、开发 ZIP、真实 Java/SQLite/Native Bearer 与退出清理已验证；完整 React/WebView2 GUI 和普通 Explorer 双击综合验收 PENDING。Production GO PENDING。**

## 结果与交付

新开发入口已摆脱旧 `.personal-ai-workspace-mmf3-tests` 及“私有目录恰好一条当前用户 FullControl ACE”的日常启动前提。
权限检查仍然存在；新设计使用系统 Known Folder、.NET Windows 身份/访问控制对象、Windows 目录句柄及既有 JNA 的类型化安全结构。
未增加 NuGet、Maven 或 npm 依赖，没有引入第二套启动器或状态机。

当前账户已实际运行最终包的 EXE/WPF 外壳、Java、Spring、SQLite 和原生认证链；完整界面尚未通过：
GUI 探针未在期限内等到 WebView2/React Ready，实际 EXE 窗口也停留在“正在打开工作区…”。
因此不能回答“新版客户端完整可用”。这是 WebView2 初始化阶段的未完成事项，不能把它改称 ACL、DPAPI 或 PostgreSQL 故障。
未得到浏览器初始化异常码；没有据此修改浏览器安全策略、Sandbox 或权限。

- 分支：`codex/windows-native-security-modernization`。
- 起始 HEAD：`e6a41b323fcc2366db79ab9d0858ccd584767c18`，与任务基线一致。
- 实现提交：`bc976a708bb768b25712ad8b12d6e57cf08dd929`；本报告随后以独立文档提交交付，最终 HEAD 以 Git/交付回复为准。
- 最终包：`artifacts/PersonalAiWorkspace-windows-native-security-development-20261019-final.zip`，375647963 bytes。
- SHA-256：`0aad2ff2ec13a17f3aee0f2ff64fbe6a91fbb17da363b759a050bc1d17fe1404`。
- 最终包绑定上述实现提交；`sourceDirty=true` 反映保留的八个未追踪 B4A6 文件，不表示它们被打包。包内文件清单另外绑定实际字节。
- 先前生成的本轮中间候选无 `-final` 后缀，包含早期实现，不作为本次交付包。历史 ZIP 未覆盖。

## 六类职责与真正替换的实现

| 职责 | 本轮处理 | 保留的边界 |
| --- | --- | --- |
| 数据目录 / Profile 生命周期 | `Mmf3ProfileState` 原重复 P/Invoke、描述符解析和精确 ACE 判断被移除，改为调用共享 `WindowsProfileSecurity`；WPF 与 helper 编译同一源码 | 固定应用根、严格 32 位 run identity、新目录独占创建、现有目录只验证不修复、无旧数据导入 |
| Windows 身份 / ACL | Helper 的手工 TOKEN_USER/SID 缓冲区解析被 `WindowsIdentity` / `SafeAccessTokenHandle` 替换；权限策略使用 .NET `DirectorySecurity`、`FileSystemAccessRule` 和句柄上的 `FileStream.GetAccessControl` | 当前 SID owner、受保护且规范的私有 DACL、必要的当前用户权限、未知/遗漏 ACE 表示拒绝；不信任其他普通用户 |
| 凭据保护 / 秘密读取 | 开发 owner 的 token 读取复用共享新策略；普通构建的导入合同不变 | 已有 Windows Credential Manager 和 CurrentUser DPAPI 保留；密码只经原生 PasswordBox/匿名管道，不经 React 或 HTTP 配置接口 |
| EXE / DLL / JAR 来源 | 保留原来源校验，显式更新仅 Development-only helper 四文件 pin；协议升级为 `WINDOWS_PROFILE_V2` | 嵌入式预期 hash、完整 JDK/JAR pin、拒绝重解析路径、DLL hash/PE/硬链接/file identity、加载前后复验、拒绝环境注入；侧边清单不能签发信任 |
| Java 所有权 / 启动认证 | Java `WindowsPrivateAcl` 手工读取 ACE 字节偏移被既有 JNA `ACL.getACEs()` / `ACCESS_ALLOWED_ACE` 等结构替换；Gate 后持有目录句柄并在使用时复验私有边界 | 原 `DevelopmentRuntimeOwner`、Job、直接子进程所有权、精确 loopback/状态目录参数、Profile token / Native Bearer 和退出清理；没有改业务服务 |
| 开发验收 / 正式信任 | 原 opt-in Debug 构建和 WPF 入口继续使用；历史 `mmf3-profile-state.ps1` 对新构建明确停止，引导原生入口，不再动态编译过期合同 | 普通 Release 不编入共享开发 Profile 组件；原正式 launcher 仍拒绝启动，未授予发布者/安装/生产来源信任 |

共享组件仍有少量直接 Win32 调用：Known Folder 查询、独占新建目录、目录句柄打开/属性/最终路径。
这些是仍需保留的 OS 原语；没有再实现一套安全描述符二进制解析器。
`RawSecurityDescriptor` 仅使用 .NET 自带解析结果核对规则投影是否遗漏 ACE，不要求固定 ACE 数量或排列。
Java 文件创建的 `CREATE_NEW`/Windows descriptor API 以及必要的 JNA bootstrap 仍保留，没有声称已移除全部 native 代码。

`NativeDatabaseSetup` 和 `TrustedDevelopmentRuntimeSources` 沿用已有调用链；没有重写正确的 DPAPI、数据库前置检查、来源持有者或 Runtime owner。
Finance、Memory、Conversation、Knowledge 的业务实现、API、schema、备份格式和持久配置格式均未修改。

## 新目录与安全合同

```text
Windows FOLDERID_LocalAppData / PersonalAIWorkspace.Development / Profiles / <32-hex-id>
  credentials/  data/  model-state/  webview2-profile/
  chrome-profile/  browser-test-extension/  evidence/  logs/
  jna-5.17.0-win32-x86-64/
```

1. 根由 `SHGetKnownFolderPath(FOLDERID_LocalAppData, effective-token)` 推导，不采信 LOCALAPPDATA、USERPROFILE、ZIP 位置或调用者提供的任意根。
2. 新应用目录、Profiles、run 和必要子目录在创建时即设置受保护权限，当前用户为 owner；当前用户、SYSTEM、Administrators 各有 FullControl。既有对象不重写 ACL。
3. 不再要求单 ACE。允许明确的系统管理主体；其他主体只有无数据内容的属性/权限信息读取、同步，以及目录遍历权限可以被接受。
   对敏感文件不把 ExecuteFile 当作目录遍历放行。其他主体的 ReadData、写入、删除、替换或权限修改会被拒绝；继承专用危险 Allow 也被检查。
   不按名称自动信任 `CodexSandboxUsers`；其 ReadAndExecute 包含 ReadData，仍不合格。
4. 上层 Known Folder 路径不再套用旧逐 ACE 替换权限谓词。Desktop 与 Java 在整个生命周期持有带 LIST_DIRECTORY、禁止 SHARE_DELETE 的目录链句柄；操作期间继续验证最终路径/对象类型/无 reparse，私有应用边界在使用时复验。
   Helper 也在自身检查/发布期间持有同类句柄。真实重命名拒绝已在 WPF 聚焦流程执行。
5. Java 私有文件仍须受保护 DACL；只有已列名 SQLite sidecar 可在合格私有父目录下使用继承 ACL。
   Sidecar 不再依赖精确单条 inherited ACE，但依然要求当前 owner、必要权限且没有外部数据访问授权。
6. 保留 JNA DLL 的逐对象发布、不覆盖、文件身份与来源核验；不提供旧版本 helper/协议 fallback。

这是基于 Windows 账户的数据保护合同，**不等价于应用沙箱**。
SYSTEM/Administrators 的正常操作被明确允许；原模型本来也没有提供对管理员/kernel 的隔离，但其私有 ACL 的外观现在确实改变。
同 SID 恶意进程仍能读取该用户有权读取的数据、使用其 DPAPI 或修改 ACL，不能承诺防护。
目录生命周期句柄保障运行中的替换边界，不能承诺离线位置永不改变或抵抗拒绝服务；重新启动时仍校验私有对象。
未把 Known Folder、ProtectedData 或 CsWin32 宣称为自动授信/隔离机制。

### Windows 打包宿主的实际差异

本机 Codex 的打包上下文会把新 AppData 内容实际放到 `WpSystem/.../OpenAI.Codex_.../LocalCache/Local`。
本轮初次新目录校验因逻辑路径与句柄最终路径不同而拒绝；这是 OS 文件系统虚拟化，不是额外 ACE。
实现现在先从 Known Folder 推导唯一应用目录，再绑定该目录的实际 OS 句柄路径，验证私有权限，并对实际路径逐段持有/检查。
不接受调用者传入映射，不修改 Codex、系统代理、环境安全配置或旧目录。

来自不同 Windows 打包上下文的运行，可能得到不同的实际 AppData 位置。
本轮 Codex 上下文中的实际合成运行，不证明 Explorer/IDEA 入口下的同一实际目录已经验收。
普通用户应在同一启动上下文中创建并重新选择 Profile；跨上下文的数据迁移未实现，也没有自动搜索/复制旧数据。

## 微软与成熟组件审查

以下版本/发布状态于本轮在线读取官方 release/NuGet 索引并检查相关源码；没有下载执行新的第三方二进制。

| 项目 | 核实的版本 / 许可证 | 源码结论与选择 |
| --- | --- | --- |
| [dotnet/runtime](https://github.com/dotnet/runtime/releases/tag/v10.0.13) | 最新稳定 10.0.13；本机及本包沿用 10.0.12，SDK 10.0.401；MIT | 实际使用 WindowsIdentity、SafeAccessTokenHandle、DirectorySecurity、FileSystemAclExtensions；兼容现有 net10.0-windows/WPF，已编译并执行。未安装或替换本机 Runtime；最新补丁升级尚未做 |
| [CsWin32](https://github.com/microsoft/CsWin32/releases/tag/v0.3.363) | 稳定 0.3.363；[MIT](https://github.com/microsoft/CsWin32/blob/main/LICENSE) | 审查 SourceGenerator；它生成互操作绑定，不制定策略。现有少数所需 API 已有准确签名，本轮不引入额外生成器 |
| [win32metadata](https://github.com/microsoft/win32metadata/releases/tag/v71.0.30-preview) | 71.0.30-preview；[MIT，SDK headers 条款另计](https://github.com/microsoft/win32metadata/blob/main/LICENSE) | 官方 [NuGet 索引](https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.win32metadata/index.json) 未列出无 preview 后缀版本；不因 GitHub prerelease=false 就称其稳定。审查 Security partition headers，未添加依赖 |
| [Windows App SDK](https://github.com/microsoft/WindowsAppSDK/releases/tag/v2.5.1) | 稳定发布 2.5.1；[MIT](https://github.com/microsoft/WindowsAppSDK/blob/main/LICENSE) | 审查 UnpackagedApplicationData 的 Known Folder 路径实现及不支持的方法。ApplicationData 不是数据沙箱；本轮直接使用 OS/.NET API，不引入 SDK、部署 Runtime 或实验 API |
| [dotnet/extensions](https://github.com/dotnet/extensions/releases/tag/v10.11.0) | 稳定 10.11.0；[MIT](https://github.com/dotnet/extensions/blob/main/LICENSE) | 审查 ResourceMonitorService 的 hosted lifetime；与本轮权限/独占子进程所有权不匹配，不为替换原有正确 owner 引入 Generic Host/Service |
| 既有 JNA / jna-platform | 5.17.0；Apache-2.0 或 LGPL-2.1-or-later 双许可，本轮按现有许可保留 | 复用 typed ACL/ACE wrapper、Kernel32/Advapi32/Crypt32；源码确认未知 ACE 仍返回通用 header，因此本策略可以拒绝；没有升级 native DLL |

上述五个上游均有近期官方发布/维护证据；没有以流行度作为集成理由。
本轮新增外部依赖为零。仍随包/使用的直接相关既有依赖：Oracle JDK 21.0.7+8-LTS-245（本地 LICENSE 为 Oracle NFTC）、sqlite-jdbc 3.53.2.0（Apache-2.0，SQLite core public domain）、
Microsoft.Web.WebView2 SDK 1.0.4258.31（Microsoft 分发许可，非 MIT）；本机 WebView2 Runtime 目录版本 155.0.4283.62。
保留原 Java LICENSE/legal、JAR 内 licenses/notices 及既有第三方 notices；不是完整 SBOM、再分发授权或全部依赖安全补丁审计完成。
正式发布前仍须独立核对 vendor 条款、补丁、签名和安装来源。

相关源码与官方 API：

- [.NET FileSystemAclExtensions](https://github.com/dotnet/runtime/blob/v10.0.0/src/libraries/System.IO.FileSystem.AccessControl/src/System/IO/FileSystemAclExtensions.cs)：handle GetAccessControl、descriptor-at-create API；DirectoryInfo.Create 接受已存在目录，因此新建独占语义继续由 CreateDirectoryW 保证。
- [.NET Environment.Win32](https://github.com/dotnet/runtime/blob/v10.0.0/src/libraries/System.Private.CoreLib/src/System/Environment.Win32.cs)：Known Folder 调用；本轮显式使用有效 token。
- [.NET ProtectedData](https://github.com/dotnet/runtime/blob/v10.0.0/src/libraries/System.Security.Cryptography.ProtectedData/src/System/Security/Cryptography/ProtectedData.cs)：CurrentUser/LocalMachine 与 CryptProtectData 映射；不因包装变化改现有 Java DPAPI 格式。
- [SHGetKnownFolderPath](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath)、[Known Folder flags](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/ne-shlobj_core-known_folder_flag)：系统目录及 packaged redirection 的能力范围。
- [CsWin32 SourceGenerator](https://github.com/microsoft/CsWin32/blob/main/src/Microsoft.Windows.CsWin32/SourceGenerator.cs)、[metadata Security partition](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/Partitions/Security/main.cpp)。
- [Windows App SDK UnpackagedApplicationData](https://github.com/microsoft/WindowsAppSDK/blob/main/dev/ApplicationData/UnpackagedApplicationData.cpp)、[extensions ResourceMonitorService](https://github.com/dotnet/extensions/blob/main/src/Libraries/Microsoft.Extensions.Diagnostics.ResourceMonitoring/ResourceMonitorService.cs)。
- [JNA 5.17.0 WinNT / ACL.getACEs](https://github.com/java-native-access/jna/blob/5.17.0/contrib/platform/src/com/sun/jna/platform/win32/WinNT.java)。

## 本轮实际验证与失败

| 实际执行 | 结果 / 证据 |
| --- | --- |
| NativeBootstrap Release build | PASS；net10.0-windows，零警告/错误。审查源码后更新 Development-only 四文件 pin |
| WPF `Mmf3AcceptanceTests` + `CredentialTests` 聚焦命令 | PASS；`.verification/windows-modernization-desktop-final.log`。真实新 Profile/Native Provision/Verify、系统 ACE 准入、外部数据权限拒绝、无环境变量根注入、目录重命名 pin、junction 拒绝；隔离 WinCred roundtrip/forget、格式/权限导入 |
| 普通 Release build，两 acceptance 属性 false | PASS，零警告/错误；`.verification/windows-modernization-release-build.log`。仅编译，不是正式启动通过 |
| 原正式 launcher `-CheckOnly` | 预期非零拒绝并核对固定错误，PASS；`.verification/windows-modernization-production-gate.log`；未创建正式状态 |
| Maven package / Runtime JAR audit / TypeScript/Vite/assets / 自包含 Desktop publish | PASS；`.verification/windows-modernization-bundle-final.log`。Maven 使用 `-DskipTests`，不可称为 Java 测试通过 |
| 最终真实 ZIP 独立解压、完整清单、实际嵌入式相对来源 Open/pin | PASS；原打包校验函数与实际包内 Desktop assembly；测试工具仅加入 `.verification/windows-modernization-e2e-package`，未改最终包 |
| 篡改解压副本 JAR 并重算侧边清单、篡改 helper DLL | 均按预期拒绝；`.verification/windows-modernization-package-negative-and-cleanup.log`。副本恢复，最终 ZIP hash 仍一致 |
| 真实 owner / Spring / Native Bearer / SQLite / session Bridge / close | PASS；`.verification/windows-modernization-real-owner.log`。使用最终 ZIP 的 Desktop assembly、helper、Java/JAR；实际 HTTP/SQLite，没有 mock。认证状态 Ready，Finance disabled；旧 session 拒绝，正常 cleanup 后所持 Java 退出；预取消不创建 child |
| 真实 GUI 探针 | **未通过**；`.verification/windows-modernization-real-gui.log`，`WEBVIEW2_GUI_PROBE_STOP`，未达到 React Ready。没有把 owner 或合同结果算作完整 GUI E2E |
| 直接最终包 Desktop EXE，同一新 Profile | WPF 外壳打开，实际 Java 在 127.0.0.1:18765 监听；`.verification/windows-modernization-client-ui.json` 显示“正在打开工作区…”。完整 React UI **PENDING** |
| 实际 EXE 的本轮清理 | 对核对了可执行路径的本次 EXE 做受控进程终止，验证 Job 随之清理持有的 Java；不是托盘正常退出验收。没有终止其他应用/Runtime；18765 最终无 listener |
| Git / receipt / Finance 只读状态 | diff checks PASS；B4A6 receipt hash 与历史报告 `FCCD77...D9E7` 一致；Finance sibling tracked working tree 干净 |

WPF 最终命令为：

```powershell
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj `
  -p:Mmf3ProfileAcceptanceBuild=true -p:FrontendSkipBuild=true `
  --filter 'FullyQualifiedName~Mmf3AcceptanceTests|FullyQualifiedName~CredentialTests'
```

显式提供本地固定 JNA artifact；测试进程仅在自己作用域内恢复 SDK 注入变量，不修改用户/系统环境。
变更了既有测试以覆盖新的合同，删除了不再成立的精确单 ACE 断言；没有为测试数量增加冗余用例。

### Java 综合回归未绿的精确范围

复用了 `WindowsPrivateAclTest.protectedBoundarySurvivesOwningFlowsAndRejectsUnsafeInput`，没有运行 Java 全套。
第一次失败是 fixture 自己的 Win32 metadata helper 未用扩展路径；修正为与 production 一致的 `\\?\` 路径后，运行到逻辑备份恢复，得到 `MEMORY_RESTORE_FAILED`，整项仍是 **FAIL**。
未删除备份断言、放宽 expected、禁用测试或修改 Memory 的正确安全合同。

单独的新合成路径诊断实际证明：318 字符数据库路径直接 JDBC open/write 返回 SQLite code 14 / `SQLITE_CANTOPEN`；同卷检查为 true，目录 no-replace publish 成功。
证据：`.verification/windows-modernization-java-acl.log`、`windows-modernization-java-acl-final.log`、`windows-modernization-storage-diagnostic.log`。
因此不能把该失败归因于新 ACL 读取算法；也不能把前半流程已经到达的安全断言算作整项测试 PASS。
本轮日常 data 路径的真实 SQLite 启动已由 owner 流程另行通过，但深层 staging/备份长路径兼容仍待专门处理。
最后的使用时边界复验调整后，没有再次运行这条已知受阻的长路径综合测试；最终真实 Runtime 包含该调整并通过 owner 流程。

未运行：全量 Java/Desktop/Frontend、完整 Browser/IME/MMF 矩阵、GPU/Vision/模型推理、真实 Provider smoke、PostgreSQL 综合流、Flyway、正式数据迁移/恢复、安装/升级/回滚。
未复用旧 B4A5、MMF-3 或 B4A6 的历史 PASS 冒充本轮 E2E。

## 历史资产、后续定位与最少操作

原八个 B4A6 未追踪文件继续未追踪；receipt、旧 Profile、ACL、DPAPI 密文、数据库、备份、旧开发 ZIP 保留。
没有 reset、clean、stash、覆盖 checkout、push、merge、tag 或 release；没有自动初始化数据库、读取真实数据库密码或修改 Finance 数据。
旧 MMF-3/B4A6 代码和报告用于历史追溯/离线维护，不再是新版客户端日常准备路径。
保留的 `Mmf3*` 类型名、opt-in Debug 构建属性、18765、CredentialTarget/单实例隔离是内部兼容名称；不再携带原精确 ACL 根合同。
正式发布仍需独立的签名、安装来源、完整性与发布机制设计，当前 launcher 没有解锁。

最少用户操作：

1. 在当前普通 Windows 账户中完整解压 **`-final.zip`** 到新的应用目录，双击 EXE，选择“是”创建新的合成 Workspace；不要选旧 B4A6 Profile。
2. 若 React 正常显示，在设置页检查 SQLite/原生认证；退出时使用托盘“退出”，再启动并选择刚创建的同一 Profile，验证合成状态保留。
3. 若仍停在“正在打开工作区”或有准入错误，停止本次验收并保留现场；下一步只定位 WebView2 初始化/宿主上下文，不运行 ACL 恢复脚本。

当前结论：**开发日常启动已解除旧 MMF-3 精确 ACL 依赖；当前用户的 WPF/Java/SQLite 启动有真实证据，完整客户端（React、普通双击入口和状态重启综合流）仍为 PENDING。**
提交后 tracked working tree 干净，仅保留原八个未追踪文件；精确最终 HEAD 见本次交付回复。
