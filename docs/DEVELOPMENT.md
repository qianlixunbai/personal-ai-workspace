# Development Guide

适用：当前 main 的 Windows x64 **Development-only** 路径。环境与命令的历史源码核验基线为 `12dce0765dcdb89b75fd605c2b4fc7bf5db00617`；文档现代化与 Closing 随 main 发布，最新主线提交见 [当前状态](STATUS.md)。本文命令仅经脚本／工程配置静态核对，本次 Closing 未重新执行构建、启动或真实验收。

[文档索引](README.md) · [当前阻塞](STATUS.md) · [架构](architecture/current-architecture.md)

## 环境与依赖

| 依赖 | 要求与来源 |
| --- | --- |
| Windows | x64；WPF / WebView2 与 Windows 原生准入不提供跨平台客户端保证 |
| Java | 本机已审查 JDK 21；`JAVA_HOME` 指向该目录；不是仅安装任意 JRE |
| Maven | 仓库 Maven Wrapper；首次依赖解析需要网络或已有本机缓存 |
| .NET | .NET 10 SDK；[global.json](../global.json) 基线 10.0.100，rollForward=latestFeature |
| PowerShell | `pwsh` 7.4+；Runtime 构建的 Windows 审计步骤及开发脚本使用它 |
| Node / npm | [package.json](../desktop/frontend/package.json) 要求 `^20.19.0 || >=22.12.0`；使用 lockfile 和 `npm ci` |
| WebView2 | Microsoft Edge WebView2 Evergreen Runtime |
| Ollama | 真实推理另需本机服务与通过现有来源／能力准入的模型；不能用“已安装”替代 Ready |
| PostgreSQL | 可选；仅显式配置 Workspace backend 或 Finance 时需要。构建不等于创建数据库／角色／迁移 |

Java / .NET / frontend 版本以对应项目文件为准。已记录的 Ollama 0.40.6 文本准入仅限既有受审查身份，不承诺升级、任意模型或 Vision 通用兼容。

## 从源码构建开发客户端

在 PowerShell 7.4+ 执行；事先通过本地环境配置 `JAVA_HOME`。首次依赖下载和构建会写入正常的 ignored 输出，但不会自动配置真实数据库或下载模型。

```powershell
git clone https://github.com/qianlixunbai/personal-ai-workspace.git
cd personal-ai-workspace

java -version
dotnet --version
pwsh --version
node --version
npm --version

npm --prefix desktop/frontend ci
dotnet restore desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj
dotnet build bootstrap/PersonalAiWorkspace.NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.csproj -c Release

pwsh -NoProfile -File scripts/verification/build-trusted-development-runtime.ps1 -JavaHome $env:JAVA_HOME
```

实际 [开发构建脚本](../scripts/verification/build-trusted-development-runtime.ps1)：

1. 要求显式本机 JDK 21，并校验 [development-bundle.ps1](../scripts/distribution/development-bundle.ps1) 中已审查 NativeBootstrap 固定文件 hash。
2. 在 Maven reactor 构建 Runtime（`-DskipTests`），执行现有 Runtime JAR 审计，构建 frontend。
3. 在本机 `.verification/` 写入来源 manifest，绑定工具链／JAR／JNA 等文件身份。
4. 以 `Mmf3ProfileAcceptanceBuild=true` 的 Debug 身份构建 Desktop，嵌入受信 manifest。`--no-restore` 说明此前需完成 .NET restore。

**新机器可能因工具链／产物字节不同而在固定 Bootstrap pin 处拒绝。** 当前脚本没有通用、自助的重新授信流程；应保留诊断并独立审查来源，不修改 pin、复制他人 manifest、放宽 ACL 或伪造 receipt。普通 Debug / Release 编译并不自动获得开发 Profile 准入。

本文未使用 `-DistributionName`；该参数会创建单独开发目录／ZIP，不是日常文档验证所必需，也不构成正式 Release。本轮没有执行打包。

## 启动与原生配置

开发构建成功后：

```powershell
& .\desktop\src\PersonalAiWorkspace.Desktop\bin\Debug\net10.0-windows\PersonalAiWorkspace.Desktop.exe
```

- 首次由原生选择器显式创建全新开发 Profile 或选择已有兼容 Profile；不复制旧 Profile、DPAPI 密文或真实数据库来绕过准入。
- 默认新工作区为 SQLite。后续无参数启动恢复上次通过原生认证与存储检查的工作区；托盘可显式切换。
- 需要 PostgreSQL 时在 Settings 原生数据库窗口中输入配置，执行既有只读检查／安全保存与显式 Runtime 重启；不自动建库、迁移或回退 SQLite。
- Finance 默认关闭；仅对符合来源／数据集／用户指纹合同的已有数据库执行原生首次绑定。打开 Settings 不是授权迁移真实财务数据。
- Ollama、模型加载／切换与真实执行仍须通过既有 Native Gate 和唯一 Active owner。遇到 `MODEL_EXECUTION_UNCERTAIN` 应停止模型 mutation，并核实外部完成事实，不清理 guard 伪造恢复。

[Program.cs](../desktop/src/PersonalAiWorkspace.Desktop/Program.cs) 和 [DevelopmentRuntimeOwner.cs](../desktop/src/PersonalAiWorkspace.Desktop/DevelopmentRuntimeOwner.cs) 是当前启动与 Runtime owner 的实际入口。普通 `start-workspace.cmd` 调用的 [启动脚本](../scripts/start-workspace.ps1) 当前立即拒绝正式启动，不能作为产品安装成功路径。

### 在 Settings 核对本次可用性

- “连接与存储”分别展示 Desktop Bridge、Java Runtime 健康检查与原生认证。下方 Runtime 启动观察复用 Desktop owner 的固定状态；观察失败只能说明暂时无法确认，不推断服务停止或擅自区分通信超时。刷新失败、存储检查未能确认 Runtime 或取得有效响应，或生命周期变化时会撤下先前的可用展示。请显式刷新运行状态，再只读检查模型。
- “连接诊断”的构建身份只读取运行 Desktop 程序集已有的 `AssemblyInformationalVersion`，仅接受有界的版本与源码 hash；字段缺失或不符合合同显示“未取得可靠构建身份”。不读取当前 checkout 的 HEAD 或从安装路径猜测身份。内嵌身份也不证明当前源码、Runtime JAR、候选分发包、历史实机证据包完全一致，未提交源码的差异不由该字段证明。
- “本地数据与存储”的实际 backend、PostgreSQL 连接及结构来自已认证 Runtime 的受控只读查询。连接失败不等于 PostgreSQL 停止；SQLite backend 不等于 SQLite 全部读写已验证。存储查询失败时，同一会话已确认的原生配置入口仍可使用，配置和凭据由原生窗口处理。
- “本机模型”的 Ollama 可访问性只在 Runtime 本次目录查询成功时确认；安装、驻留、验证 Ready 和执行计数分别显示。Runtime 不可访问时保持模型未知；只有历史验证记录、mutation 后无目录快照或查询失败时，不宣称当前可用。只读刷新不执行 VALIDATE / RECOVER、不加载模型、不重放任务。
- Desktop 只管理它直接启动并持有的开发 Runtime。关闭主窗口保留托盘；退出 Desktop 会结束直属 Runtime，必要时终止进程，不承诺所有退出均优雅。外部 Runtime、PostgreSQL 和 Ollama 不被认领、接管或停止，外部模型驻留／执行不保证随 Desktop 退出立即结束。

本项是状态提示改进，不是新的服务管理器或目标 1 实机验收。Development-only、X1 PARTIAL、Vision BLOCKED 与尚未 Production GO 的边界不变。

## 分层开发与最小验证

| 工作 | 可复用入口 | 使用范围 |
| --- | --- | --- |
| Frontend 依赖／类型／资产 | `npm --prefix desktop/frontend ci`、`npm --prefix desktop/frontend run check`、`npm --prefix desktop/frontend run build` | 只改文档时不执行这些生产构建 |
| Java | `.\mvnw.cmd -pl workspace-application -am '-Dtest=OwningTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 将 OwningTest 换成真实 owning-layer 测试；只在相关生产行为变化时运行 |
| Desktop | `dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --filter 'FullyQualifiedName~OwningTest'` | 复用合适的既有 fixture 与构建参数；不默认跑完整 suite |
| Windows 综合验收 | [WorkspaceSanity project](../desktop/acceptance/PersonalAiWorkspace.WorkspaceSanity/PersonalAiWorkspace.WorkspaceSanity.csproj) | 显式需要时使用合成、隔离环境；不可当作无副作用日常 smoke |
| 专项历史证据 | [工程报告索引](README.md#engineering) | 按报告的基线、工具链、来源 pin、命令和包身份理解；不是任意提交可直接复用的 PASS |

生产配置默认 Runtime `127.0.0.1:8765`、Ollama `127.0.0.1:11434`，见 [application.yml](../src/main/resources/application.yml)。不能因 loopback 端口存在就复用未知服务、读取 Token 或假定 Native Bearer 有效。单独 `spring-boot:run` 或 `java -jar` 不等同于受信 Windows 产品启动。

开发模式／历史 receipt／维护脚本有各自明确准入条件；不要组合不同阶段的启动参数、manifest 和包。

## 存储、凭据与本地证据

- 开发 Profile 位于 Windows Known Folder 的 `LocalAppData/PersonalAIWorkspace.Development/Profiles/<id>`。打包宿主可能重定向实际位置，以 OS 句柄绑定结果为准。
- 默认 standalone 配置路径与受控 Profile 是不同上下文；不能把 `application.yml` 的默认数据目录直接当成当前开发 Profile。
- 数据库密码、API Key 和 Token 仅通过项目既有原生窗口、Windows 凭据／DPAPI 或明确支持的本地环境机制输入；不进入 React、源码、文档、日志或 Git。
- `.runtime/`、`.verification/`、`artifacts/`、`**/TestResults/`、构建输出、数据库和私有备份均不得批量添加到提交。
- DPAPI 只保护指定凭据；数据库、Knowledge 源文件与逻辑备份可能为明文。统一 PostgreSQL 加密备份另有独立流程。
- 不使用 `git clean` 或递归清理来“修复环境”，不覆盖未知用户修改或历史证据。

## 故障处理

| 现象 | 应如何判断 |
| --- | --- |
| NativeBootstrap pin mismatch | 工具链／来源身份不一致；保留诊断，独立审查，不自动改 hash |
| PRIVATE_ACL / ANCESTOR_ACL / Native Gate 拒绝 | 属于安全准入事实；不手动放宽权限或复制其他 Profile |
| 正式 launcher 立即拒绝 | 当前设计边界，不是缺少一个启动参数 |
| MODEL_EXECUTION_UNCERTAIN | 模型完成事实未知；刷新不等于恢复 |
| Vision 无法 Ready | 当前 BLOCKED；不要把 preview、metadata 或无模型 fixture 当作解除依据 |
| PostgreSQL／Finance 不可用 | 先核对当前 Profile、明确配置与只读状态；不自动建库／迁移或换用户 |

发布缺陷时只附经过隐私审查的版本、有限状态与复现步骤，不提交凭据、本机绝对用户路径、真实财务资料或完整私有目录。
