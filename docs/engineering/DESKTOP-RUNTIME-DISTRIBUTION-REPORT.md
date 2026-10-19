# Windows Desktop Runtime Validation & Distribution Foundation

2026-10-19（Asia/Shanghai）。**开发目录与 ZIP 已真实生成，聚焦打包合同通过；真实 Windows E2E、可信安装、Production GO 均 PENDING。**

## 真实状态与 Git 基线

开始时为 `codex/desktop-setup-simplification`，HEAD `7793dc84119e65cb4b3e9452790cfbf0a3f5d9fb`，
tracked 工作树干净，仅有原 8 个 B4A6 未追踪文件。创建 `codex/desktop-runtime-distribution`，未覆盖这些文件。
实现提交为 `dfcd72564848561d71ce4d74d9ed5198fe208ab1`；报告和状态说明另作本地文档提交。
本地提交范围为 `7793dc8..HEAD`，最终交付 HEAD 为本报告所在提交，以 `git rev-parse HEAD` 为准。
本地 main 仍为 `b35a1ac7d71a5628e1055b6bfd04776d574f85af`。未 fetch，未声称核验远端最新状态。

未 push、merge、tag、release、reset、stash、clean；未修改 Git 全局配置或系统代理。
冻结 Finance 源仓库仅只读 status，tracked/untracked 均干净；没有数据库连接、初始化、角色操作、迁移或正式数据访问。
原 8 文件及 Native Gate、WindowsPrivateAcl、NativeBootstrap 实现、生产启动脚本首尾哈希一致。
原 `.verification/f2.3-b4a6-configuration.local.json` SHA-256 仍为
`FCCD77E62717BCDE2831586FFDABE4496A34AC06B320BA1259E7D936DCE5D9E7`；未操作 receipt、旧 Profile 或备份。
交付时 tracked 工作树干净，保留原 8 个未追踪文件，故整体工作树并非完全 clean。

## PRIVATE_ACE 与独立验收

此前原 helper 的 `PRIVATE_ACE` 是已有真实阻断证据。本轮只读检查当前用户
`.personal-ai-workspace-mmf3-tests` 根目录，仍存在显式 `CodexSandboxUsers` ReadAndExecute ACE。
未再次调用旧 Profile helper，未创建新 Profile 或启动真实 Java/WPF 综合流；不能将此前结论重新记作本轮 E2E。
原 PrivateAcl 要求受信私有目录满足原 ACL 合同，额外 ACE 仍不能被忽略。

Profile 根由有效 Windows token 的用户主目录确定，不接受任意目录替代。
所以仅换一个解压目录、创建新 run-id 或关闭 Codex，都不能保证同一受干扰用户环境合格。
维护路径是：**开发工具构建包；环境管理方提供未受 Codex 沙箱目录权限干扰、满足原祖先/私有 ACL 合同的独立普通 Windows 用户环境；用户在该环境创建全新合成 Profile。**
不复制旧 Profile/DPAPI 配置，不删除 ACE，不自动修复 ACL，不改变 Codex 服务，不提权，不签发设备信任身份。
独立环境仍被拒绝时立即停止。当前真实 Windows 验收保持 PENDING。

## 本轮代码

| 文件 | 实际行为 |
| --- | --- |
| `desktop/.../TrustedDevelopmentRuntimeSources.cs` | 仅原 Profile Debug 分支增加内嵌 manifest v3：固定 `runtime/java`、Runtime JAR 和 JNA 路径，相对应用目录解析；拒绝绝对路径/路径段穿越/非法字符；原 v2 绝对来源兼容 |
| 同一来源持有者 | 继续使用内嵌预期 SHA-256、祖先无 reparse 检查和 deny-write/delete 读取句柄；没有从侧边清单/命令行接收预期哈希，没有改变 Gate 或固定 JVM 参数 |
| `desktop/Directory.Build.props` | 原固定 NativeBootstrap 四文件明确随 publish 复制 |
| `scripts/verification/build-trusted-development-runtime.ps1` | 扩展原入口的 `-DistributionName` 模式；复用 Maven default Runtime audit、生产前端构建和原 Desktop；拒绝覆盖已有输出和链接输入/输出；生成完整包清单与 ZIP |
| `scripts/distribution/development-bundle.ps1` | 校验完整文件集合/大小/哈希、固定 helper 来源、前端清单/CSP、自包含 Desktop、用户状态隔离及开发版标识；只是构建/检查工具，不是启动器或发布认证系统 |
| `scripts/distribution/README-development.txt` | 随包给出直接打开 EXE 的验收路径、外部依赖和停止条件 |
| 原 `RuntimeLifecycleProbe.cs` 与 `test-development-bundle.ps1` | 增加测试专用 source-only 分支；使用实际 ZIP 与实际包内 Desktop 内嵌资源验证搬迁、锁定和篡改拒绝；没有新增生产测试入口或新 xUnit 用例类 |

没有 Java 业务代码、数据库格式、权限、身份、DPAPI、Runtime owner 生命周期或生产启动行为的修改。
没有第二套 launcher、Receipt 状态机、Provider 框架、安装服务或更新进程。

## 已实现的分发基础

构建机入口（开发者执行，普通验收用户无需执行）：

```powershell
pwsh -NoProfile -File scripts/verification/build-trusted-development-runtime.ps1 `
  -JavaHome 'D:\MC\JAVA\JAVA21' -DistributionName 'PersonalAiWorkspace-development-win-x64'
```

构建仍需要现有 Maven/npm/.NET SDK、已恢复的前端依赖和 Gate 所 pin 的原 NativeBootstrap 四文件。
脚本在构建前核对 helper 固定哈希；不自动重编后更新 pin。**这是给定已审查本机工具链/原 helper 输入的可重复构建入口，尚非全新 checkout 无外部输入的可重现发布。**
构建会使用既有 SDK 的常规依赖恢复，不自行下载执行 Java/WebView2/第三方安装器。

```text
PersonalAiWorkspace-development-win-x64/
  PersonalAiWorkspace.Desktop.exe / dll / runtimeconfig / .NET runtime files
  MainWorkspace/                 # 生产 React 资产与原 CSP
  NativeBootstrap/               # 原四个固定文件
  runtime/
    personal-ai-workspace.jar    # 经原 default JAR audit
    jna-5.17.0.jar
    java/                       # 本机 JDK 21、LICENSE/legal/src.zip 等
  ThirdPartyNotices/
  README.txt
  development-bundle.json        # 完整性/构建来源说明；不是发布者签名
```

已生成 `artifacts/PersonalAiWorkspace-development-win-x64` 及同名 `.zip`。
ZIP 为 **375,644,292 bytes**，SHA-256：
`7bee321193b7ab056fd20081e02d63baab6c9bf0c8bcdad1e5203867bad0c858`。
包清单绑定实现 HEAD `dfcd72564848561d71ce4d74d9ed5198fe208ab1`，`sourceDirty=true` 如实包含原未追踪成果的工作树状态。
最终文档提交后未因文档变化重建这个已验证包。

用户不需 JAVA_HOME、查找 JAR 或 Node；但有以下准确限制：

- 主 WPF 程序自包含 .NET；**原 NativeBootstrap 仍是 framework-dependent，必须已安装 Microsoft.NETCore.App 10.0.x x64**。不能通过重编 helper/更改 Gate pin 假装消除这个依赖。
- `WebView2Loader.dll` 是 SDK loader；**React 仍需已安装 WebView2 Evergreen Runtime**。本轮没有安装它；离线新 Windows 机器并非零前置依赖。Microsoft 提供 Evergreen 离线安装与 Fixed Version 两种路线，Fixed Version 尚未接入。[官方部署说明](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution)
- Java 是本机已存在的 Oracle 21.0.7；实际 LICENSE 为 NFTC，不能按 GPL OpenJDK 包处理。这里只作本地开发验收原型，未授权对外分发；厂商许可、版本安全维护、完整依赖许可证/SBOM 仍待正式发布审查。
- Ollama/模型不随包提供；没有下载、加载模型或执行推理，不声明完整 AI 使用已验收。

用户状态继续位于 token 对应用户的 `.personal-ai-workspace-mmf3-tests/<run-id>`，与包目录分离。
构建从新 staging 及明确 JDK/JAR 输入装配，不扫描复制个人 Profile；校验拒绝包内 data/credentials/model-state/WebView profile/备份/日志/DPAPI 文件。
不存在安装/更新/卸载逻辑，因而没有覆盖或清理这些用户状态的代码。未来更新必须在退出原 owner/JVM、释放来源 pin 后替换应用文件；数据库回滚不能靠替换旧 EXE 完成。

## 方案比较与选择

结论：保留 WPF/React/Java 架构，当前先交付开发 payload。**Velopack 是后续普通桌面安装器的优先调查方向，尚未集成；Windows App SDK 不作为本轮新增依赖。**
后者是 Windows API/生命周期库集合，支持 WPF 和原有安装器，并不等于 MSIX 或一套完整可信发布系统。[Windows App SDK 项目](https://github.com/microsoft/WindowsAppSDK)

| 维度 | Velopack + 现有 WPF | Windows App SDK / MSIX 路线 | 当前开发 ZIP |
| --- | --- | --- | --- |
| 安装与权限 | `vpk pack` 可产 Setup/完整更新包；普通 Setup 为每用户 LocalAppData 安装，无需提升 | SDK 本身不替代安装器；MSIX 提供 Windows 包身份/部署模型 | 只有解压，没有安装权限操作 |
| 目录布局 | 安装根下 `current` 存应用，更新整体替换，卸载可删除安装根；状态必须外置 | MSIX 有包目录与可能的文件/注册表重定向，必须实际验证现有 token/Profile 路径合同 | 应用与固定用户 Profile 分离，不执行替换 |
| .NET/Java | 可包入 `dotnet publish` 和 Java payload；不会自动解决 Java 分发许可 | SDK/打包同样不提供 Java；额外 SDK Runtime 会增加部署面 | Desktop 自包含，Java 随包，helper .NET 10 外部依赖明确 |
| WebView2 | 有 bootstrap 选项；会下载/安装依赖，须独立授权和校验 | 同样须决定 Evergreen/Fixed Version，不等于系统一定具备 | Evergreen 已安装为前置，本轮无 bootstrap |
| 首次启动 | 必须在 Main 尽早处理 SDK hooks；不能在安装/卸载 hook 中弹 Profile 窗口或启动 Java | MSIX 激活/身份/重定向要与现有 Main/Gate 验证 | 直接使用现有 EXE、原生 Profile 选择/创建 |
| 签名与来源 | 需签应用及 Setup/Update；签名不自动建立 Workspace Gate 的受信发布者合同 | MSIX 发布身份/签名也需明确，不能用自签名冒充正式信任 | 未签名，仅本机构建信任；哈希不证明发布者 |
| 依赖完整性 | 仍需锁定工具/SDK/JDK/JAR、来源审查和最终文件清单 | 包格式有完整性机制，仍需依赖来源与 signer 策略 | 清单检测缺失/修改；Runtime 预期哈希另外内嵌在未改的 Desktop 中 |
| 更新可信性 | 官方 SDK 会检查下载包大小/哈希；feed/索引真实性、signer 约束、版本/通道/回退策略仍需产品合同 | 依赖 Windows 包部署及签名/更新配置；SDK 本身不是更新来源认证 | 未接 SDK、feed 或自动更新 |
| 更新失败恢复 | delta 失败可回退下载 full 包；这不等于应用/数据库回滚；持有 `current` 文件时 updater 可能终止进程，与当前 owner/pin 需协调 | 包部署与应用数据恢复是两个问题，仍需生命周期和兼容性验收 | 更新/回滚尚未实现和测试 |
| 卸载与数据 | 状态不能放安装根，不能让卸载 hook 删除 Profile/备份 | 要验证包数据删除/重定向对保留策略的影响 | 无卸载动作，保留原用户状态 |
| 离线 | 完整包可包含应用；在线 prerequisite bootstrap 不能当作离线安装 | 需准备所有签名包/Runtime/依赖，不能假设设备可联网 | ZIP 可离线传递；依赖预置，缺少时停止 |
| 许可证 | Velopack MIT，须保留版权/许可证；Java、WebView2、所有依赖另审 | Windows App SDK 项目 MIT；SDK/Runtime/部署组件的实际条款与 notices 另审 | 保留 JDK LICENSE/legal 和现有 notices，尚未声称完成发布许可审计 |

依据：[Velopack 项目](https://github.com/velopack/velopack)、[打包输出](https://docs.velopack.io/packaging/overview)、
[每用户安装与布局](https://docs.velopack.io/packaging/operating-systems/windows)、[安装器](https://docs.velopack.io/packaging/installer)、
[先决组件](https://docs.velopack.io/packaging/bootstrapping)、[签名](https://docs.velopack.io/packaging/signing)、
[hooks/更新/校验](https://docs.velopack.io/integrating/overview)、[MIT 原文](https://raw.githubusercontent.com/velopack/velopack/develop/LICENSE)、
[Windows App SDK 部署依赖](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/deploy-unpackaged-apps)、[MSIX](https://learn.microsoft.com/en-us/windows/msix/overview)。
上述适配/选择是基于官方资料和本仓库启动链的工程判断，不是 Velopack/MSIX 集成成功的证据。

**Setup/MSI/MSIX 安装器原型未生成。** 本轮未发现 PATH 上现成的 vpk/MakeAppx/WiX，未为生成安装器下载执行未经验证的第三方工具。
更关键的是：现有生产 launcher 仍主动拒绝来源，Velopack hooks 与固定 helper/Runtime owner 的安全装配、可信 signer/来源合同尚未成立。
没有集成这些合同便生成一个会启动应用的 Setup，不能满足本轮边界。实际交付仅上述可校验开发 payload/ZIP，不将其标记为 installer 或正式 Release。

## 实际执行的聚焦验证

| 本轮实际执行 | 结果与证据 |
| --- | --- |
| 最终原构建入口，绑定实现提交，`-DistributionName PersonalAiWorkspace-development-win-x64` | PASS；Maven `-pl workspace-application -am -DskipTests package` 是打包而非 Java 测试；原 default JAR audit、TypeScript/Vite/资产验证、Profile Debug `dotnet publish`；`.verification/desktop-distribution-final-build.log` |
| `pwsh -NoProfile -File scripts/distribution/test-development-bundle.ps1 -PackageDirectory artifacts/PersonalAiWorkspace-development-win-x64` | PASS；一个集成合同流程，从真实 ZIP 解压到中文/空格目录，检查完整集合/用户 data 隔离/清单路径穿越/开发版标记/JAR 篡改；实际包内 Desktop 的 `Open()` 验证相对 pin、拒绝在持有期间写入 JAR；修改侧边哈希仍拒绝。`.verification/desktop-distribution-final-contract.log` |
| 普通 Release Desktop build，`--no-restore -p:FrontendSkipBuild=true` 且两 acceptance 属性 false | PASS，零警告/错误；`.verification/desktop-distribution-release-build.log`。这是编译，不是 GUI/E2E |
| Release + Profile acceptance 的 `ValidateMmf3AcceptanceBuild` 目标 | 预期拒绝 `Mmf3ProfileAcceptanceBuild is Debug-only.`；`.verification/desktop-distribution-release-negative.log`。最初检查日志时匹配文字写错，已按实际固定错误核对，未改产品 Gate |
| `pwsh -NoProfile -File scripts/release/start-release.ps1 -CheckOnly` | 预期非零拒绝 `production launch is blocked pending trusted release integration`；`.verification/desktop-distribution-launcher-rejection.log`。没有启动 Java 或创建状态 |
| `git diff --check`、原文件哈希、receipt 哈希、冻结 Finance status | PASS；静态/只读证据，不计作测试套件 |

开发期间的失败已保留并修正：初次打包检查脚本换行解析错误；helper 重编字节变化被 pin 正确拒绝，最终复用现有四个逐一哈希一致的原 payload；
框架依赖测试 apphost 被相邻自包含 hostfxr 影响，改用明确的开发机 `dotnet exec` 执行 source-only 测试 DLL。
后者不清理/伪造环境、不涉及 Gate/真实 Runtime，不改变用户程序或 helper 的启动方式。
早期失败/通过日志为 `.verification/desktop-distribution-build*.log` 与 `desktop-distribution-contract*.log`；最终证据以上表 final 日志为准。
合同的多个覆盖点不是多轮 Windows E2E，没有新增测试数量目标。
收尾时自动审批审查拒绝清理本轮两个失败的 `.development-package-*` 暂存目录，原因仅返回 `blocked by policy`。
未重试删除，目录保留在忽略的 `artifacts/` 中，不影响最终包或 Git 提交。

**未执行**：新版 WPF 配置窗口完整操作、真实 Profile 准入、Java 启动/退出/重启、OS DPAPI 保存、真实 PostgreSQL 综合流、
Memory/Conversation/Knowledge GUI 综合流、Finance Binding、安装/卸载/升级/回滚、推理、GPU/Vision 或任何全量测试套件。
既有 Desktop Simplification 和 B4A5 运行结果只作历史背景，本轮没有重新授予其结论。

## 下一步最少操作与 GO

**唯一建议的用户验收方法**：在环境管理方已提供的、满足原 Gate 合同的独立普通 Windows 用户环境
（预置 .NET 10 x64 Runtime 与 WebView2 Evergreen）完整解压最终 ZIP，双击 `PersonalAiWorkspace.Desktop.exe`，选择“是”创建全新合成 Workspace。
随后用一次流程检查 SQLite StorageStatus、React 设置页、原生数据库设置窗口，再退出确认本次 Java 子进程已退出。
不执行旧 PowerShell/receipt/Compile Recovery；准入失败即停，不修 ACL。

PostgreSQL 只在另行授权、已准备 schema 的独立合成环境中检查/保存/明确重启；schema 初始化和凭据实际写入继续按原合同确认。
没有要求或允许用户在聊天中发送凭据。

**真实 Windows E2E：PENDING。原生数据库窗口/OS DPAPI/PostgreSQL 综合链：PENDING。
Finance Windows Binding：PENDING。可信发布与安装：PENDING。Production GO：PENDING。**
后续安装器工作应从受控 Velopack hooks、经过验证的构建工具/发布签名输入、外部依赖预置和更新/退出合同开始，不解锁当前生产入口。
本轮只本地提交，等待用户确认后才可另行 push。
