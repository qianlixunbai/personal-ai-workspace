Personal AI Workspace - 本机构建的开发验收包

DEVELOPMENT ONLY / UNSIGNED / Production GO PENDING
此 ZIP 是离线开发目录原型，不是安装器，不提供发布者身份或正式来源信任。
仅供本地审查后在独立普通 Windows x64 用户验收环境中使用，不对外分发。

首次使用：完整解压到验收用户自己的应用目录，打开 PersonalAiWorkspace.Desktop.exe，
选择“是”创建全新的合成 Workspace；SQLite 为默认存储。不要复制旧 Profile。
首次创建只沿原有 Prepare / Provision 合同设置新目录私有权限，不修复已有目录。
若原生准入失败，停止验收；不要删 CodexSandboxUsers ACE、提权或修改 ACL。
关闭 Codex 不保证已有用户目录恢复合格，须使用环境管理方提供的独立普通用户环境。

Java 21、JAR、JNA、.NET Desktop 和 React 资产已打包；无需设置 JAVA_HOME、寻找 JAR
或安装 Node。但原 Native Gate pin 的 NativeBootstrap 仍要求已安装 .NET 10 x64 Runtime
（Microsoft.NETCore.App 10.0.x）；React 界面还要求已安装 Microsoft Edge WebView2
Evergreen Runtime。WebView2Loader.dll 是 SDK loader，不是浏览器 Runtime。
此原型不下载、不安装这些先决组件；缺少组件时停止验收。

验收用一个合成流程：创建/选择 Profile -> SQLite StorageStatus -> React 设置页 ->
打开“原生数据库设置” -> 退出并确认本次 Java 子进程退出。
PostgreSQL 检查/DPAPI 保存/显式重启只用于另行授权且 schema 已准备的独立合成数据库；
此包不创建数据库、角色或 schema，不自动初始化、不接触 Finance 或个人数据。

程序与依赖放在解压目录。用户数据仍由原合同放在当前用户 Profile 的
.personal-ai-workspace-mmf3-tests/<新 run-id>，不在包内。
没有安装、更新、卸载逻辑。更换开发包使用新的目录，保留用户 Profile、DPAPI 配置与备份。
不得将开发包的原地文件替换当作已验证的更新/回滚能力。

来源错误：保留整个包并核对构建日志；损坏或缺失依赖必须重新取得完整本机构建包。
包内 development-bundle.json/SHA-256 仅检查完整性，不能证明可信发布者。
随包保留 Java LICENSE/legal 和其他依赖 notices；Java 厂商许可、版本安全审查、完整 SBOM
与再分发权仍须在正式分发前完成。
