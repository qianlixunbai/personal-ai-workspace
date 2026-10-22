Personal AI Workspace - Windows 原生安全开发包

DEVELOPMENT ONLY / UNSIGNED / Production GO PENDING
此 ZIP 使用经本机审查的构建和工具链，不是安装器或正式发布。

完整解压到当前 Windows 用户自己的应用目录，双击 PersonalAiWorkspace.Desktop.exe。
首次无参数启动显示选择器；此后默认打开上次成功通过原生认证与存储检查的 Profile。
选择“否”打开已有开发工作区；文件夹选择器默认打开当前 Windows 上下文的 Profiles 目录，选择原 Profile 文件夹。
选择“是”才会创建全新的合成 Workspace（默认 SQLite）；不会自动配置 PostgreSQL。
不要复制旧 Profile、DPAPI 密文或真实数据库。
创建只为新目录设置权限，不修复或迁移旧目录。无需第二账户、旧 B4A6 receipt 或 ACL 恢复脚本。

数据使用系统 Known Folder 的 LocalAppData/PersonalAIWorkspace.Development/Profiles/<id>。
Windows 打包宿主可能将 AppData 写入重定向到宿主包的实际数据目录；应用绑定 OS 句柄返回的实际位置。
从不同 Windows 打包上下文启动时数据位置可能不同；请始终使用同一 Windows 用户与启动上下文。
上次工作区只在该私有应用目录的 last-workspace 文件中保存 32 字符 Profile ID，不保存数据库密码、Bearer 或路径。
记录不会替代 Profile 路径/权限校验与 Native Gate。记录或工作区无效时显示错误并提供重新选择入口，不自动修复或回退到 SQLite。
私有目录允许当前用户、SYSTEM、Administrators；其他主体的敏感读取或写入/替换权限被拒绝。
不授予 CodexSandboxUsers 私人数据读取权限。ACL/DPAPI 不能隔离同用户身份下的恶意进程。
准入失败时保留现场并停止；不要提权、修改旧 ACL 或反复运行历史恢复工具。

Java 21/JAR/JNA、.NET Desktop 和 React 资产随包提供。
NativeBootstrap 仍要求本机 Microsoft.NETCore.App 10.0.x x64；界面要求 Microsoft Edge WebView2 Evergreen。
此包不下载或安装先决组件，不提供更新、卸载、发布者签名或正式来源授信。

用一次合成流程验收：创建 Profile -> 设置页“重新检查存储”显示 SQLite/原生认证 ->
关闭并退出客户端 -> 确认该次 Java 子进程退出 -> 无参数重启，确认自动打开原 Profile，核对状态保留。
只关闭工作区窗口可能保留托盘；请从托盘选择“退出”。
如果已进入其他 Profile，可在托盘菜单查看当前开发 Profile，点击“切换开发工作区…”：
客户端正常退出、释放本次 Runtime 后强制显示同一选择器；选择“否”及已有 Profile。
新 Profile 成功通过原生认证和存储检查后才更新记录；取消选择或启动失败不覆盖上次成功记录。
也可用 PersonalAiWorkspace.Desktop.exe --choose-development-workspace 主动打开选择器。
如有未保存的 Memory 修改，仍须先确认丢弃；取消退出不会切换。此入口不创建或改写数据库配置。
不启用 Finance、不自动执行 Flyway、不初始化 PostgreSQL。原生数据库设置仅用于另行准备并授权的合成环境。
包中没有用户凭据或数据库；旧开发包、旧 Profile 和备份原样保留。

development-bundle.json/SHA-256 检查完整性；Runtime/helper 预期 pin 嵌入代码，侧边清单不能签发信任。
随包 Java LICENSE/legal 和现有 notices 保留。正式再分发许可、签名、安装与更新仍待独立验收。
