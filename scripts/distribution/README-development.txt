Personal AI Workspace - Windows 原生安全开发包

DEVELOPMENT ONLY / UNSIGNED / Production GO PENDING
此 ZIP 使用经本机审查的构建和工具链，不是安装器或正式发布。

本包包含：持续对话、显式记忆、资料检索与知识问答历史，以及以下完整资料流程：
知识库“导入文档” -> 选择 TXT / Markdown / DOCX / PDF -> Word / PDF 摘录预览并明确确认 ->
检索与回看原文 -> “导入新源版本” -> “对比源版本” -> 打开增删对应的旧版／新版原文。
已有成功知识问答可从历史打开，再“导出问答笔记…”保存为新的 Markdown 文件。
Word 保存经确认的文字摘录，不保存原文件、图片或版式；公式等不支持内容会拒绝。
原 Word 文件应另行保管。对比超限时请分别预览；旧问答引用不会自动换成新版。
PDF 只保存经确认的文字层摘录，附原文件 SHA-256 和文件内页序号；请另行保管原 PDF。
不做 OCR，不保存图片、批注、附件或版式；表格、多栏与文字顺序须对照原件核对。
加密、表单、任一页没有可提取文字（包括空白页）的 PDF 整份拒绝，不保存部分结果。
上限 8 MiB、200 页、24 万字符、解析 12 秒；可先拆分或另存为文本，核对后导入。
上述导入、阅读、对比和已有历史导出不需要模型运行。生成新回答仍需单独通过本机模型准入。

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

Java 21/JAR/JNA、.NET 10.0.14 Desktop 和 React 资产随包提供。
NativeBootstrap 仍要求本机 Microsoft.NETCore.App 10.0.x x64；界面要求 Microsoft Edge WebView2 Evergreen。
此包不下载或安装先决组件，不提供更新、卸载、发布者签名或正式来源授信。
仅替换应用文件不能恢复用户数据。个人 Profile 恢复尚未开放，现有问答笔记也不是完整备份。
先在合成资料中确认所需流程；不要把旧包的模型 Ready 或验收结论当成本次状态。

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
