# 同源候选包与最小启动范围

## 后续现场核对：许可已收到，helper 验证通过

用户已于 2026-11-11 09:55:12 UTC 明确同意原开发 Profile 的两次启动／退出、token 更新及 WebView 浏览数据清除；原始问答经 `user_message_read.read_messages` 核实。该许可不解除历史拒绝，不允许更换身份、目录或入口绕过。本节是后续结果，下文“未运行 helper”等描述保留为组装阶段历史。

独立源码修复已提交 `0335ff49278bedf7b747486bc584e5240ce85ba7`：补齐对话出处的列表授权，类型检查和 4 个前端场景通过，见 SOURCE-REOPEN-AUTHORIZATION-REPORT。候选包仍为 `933bb20` 来源，未重新构建／打包，未纳入该新增前端修复。本地提交首次被自动审批以“与开题报告任务无关、缺少可采信授权”拒绝，命令未执行；读取用户原始 Git 回滚授权及上下文后，仅同范围重试一次并成功，没有换通道提交或改 ACL。

现场证据目录为 `artifacts/live-link-preflight-20261111-4461263e58b54c4a8b9b0b0f282c659a`。只读元数据检查 P、data、credentials、evidence、model-state：均为非重解析目录，owner 匹配原有效主体且 DACL protected。准确检查的 database.dpapi、finance.dpapi、三个 sqlite 维护登记、六个模型状态／pending 文件、model-state/.lock 和 credentials/client-token **全部不存在**。未读内容、枚举目录、创建 Profile 锁或修改权限。第一次启动将生成 token，独立重开才会轮换；这是现场观察，不再仅是假设。

候选 EXE/DLL/JAR 当前哈希与下表相同。正规 `require_escalated` 调用的有效主体与原 Profile 不同，前置主体比较在 helper 启动前停止，该次没有产品操作。随后普通执行通道再次确认原有效主体，固定四项 helper pins 和环境前提核对通过，实际执行包内 `NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.exe VerifyProfileNative b204c7e3cc33425e841eb1aa32d2caa2`。UTC 10:03:16.3056367Z 开始，PID 3500，退出 0，无超时；返回根与原 P 完全相同，JNA hash 与固定值相同。没有 Provision、Java、WebView、token 或数据库操作；自有 helper 退出，共享 mutex 已释放。此结果仅证明 helper 原生验证，不证明 Java NativeGate、ModelStateStore、服务或桌面已通过。

证据 SHA256：metadata.json `e0d856a74248d38e31acad53dbb09c763aa4f7367ff6bfe3ffee12b64c9e2a84d`；helper-result.json `04f41e7d8d813cf84f45db15d47d5d6e63a61280da3564c6f1f088bfe7412552`；helper-stdout.txt `4082ce68d6864fc2a87c9d9cfeb123d9b9c727ae25982e66edd2ae457d463a9b`。身份前置停止另存 identity-precheck-stop.json，不输出秘密。

完整闭环当前缺少已确认的正常界面操作／退出控制。候选 OnStartup 自动打开 Workspace；托盘“退出”才调用 AssistantApp.ExitAsync，依次清理 WebView、发送 owned Runtime CLOSE 并等待退出。关闭主窗口只关闭 WebView，Assistant 使用 OnExplicitShutdown，不能视为整个应用退出。现有候选无普通外部退出命令；Recovery 专用 callback 不是替代入口。CUA 主机关联仍未确认且未重试，因此没有先启动桌面再以强制结束冒充正常闭环，也未使用反射或自造消息触发退出。后续需要用户在已确认的 Windows 命令主机上执行正常界面操作，或父任务提供已确认的同主机操作途径；不是再索要已收到的 token／浏览清理许可。资料保存、真正退出、独立重开回查均尚未执行。

2026-11-11。**离线候选包已实际组装并审计通过；产品、helper、NativeGate、真实服务和桌面联动均未运行。** 来源提交 `933bb20d5dad2e04c6e0a13cc243cba7fc07124a`，产品代码与 `7e7332e` 相同。`productionGo=false`，`nativeValidation=NOT_RUN`。本报告后的文档提交不改变这份包的来源提交或字节。

## 交付物与准确身份

候选目录 B：`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-knowledge-source-live-candidate-20261111`，ZIP 为同目录名加 `.zip`。证据目录 E：`artifacts/candidate-package-20261111-933bb20`。包内 README 明确候选未验收、未安装、未部署、未设置默认；不能直接把它交付为已通过运行的应用。

| 对象 | SHA256／结果 |
| --- | --- |
| ZIP，384,847,318 字节 | `d95e30f50e3cf0493145e5d9ed94e4c16157a62889d377b752c8ac8fbe5caca0` |
| B/PersonalAiWorkspace.Desktop.exe | `9d8e3cdff3ffcf6fbfcb6ed1099e5b419c68791eb619f659c61ab8bbd7bc0e6c` |
| B/PersonalAiWorkspace.Desktop.dll | `3fa3588d6ee9717bbd02aa3b9d3bc6b41cc3643bbd167d3f19337618f352ca0f` |
| B/runtime/personal-ai-workspace.jar | `907f1867d7f54445d14ecf0ad643a1b2592cee2a6f22e953139c5c38dbbd045b` |
| JAR 内 finance-domain-0.1.0.jar | `52c9e4d31109927753c74f5ebaf71a902db944b4bf8e84d16fc135eb2ed15a5c` |
| 内嵌 TrustedDevelopmentRuntimeSources | `92d4288aab0a2b391f446790b015b2517cd6b385b2d6dffbe7fae5ae4d0777bc`；66,011 字节、420 项 pins |
| 一次性组装脚本 E/Assemble.ps1 | `b72738fefeeaaa46676dd5100da80719f10c4662d0e41231aca8f6cc3eee4ce8` |

933 个载荷文件，加 `development-bundle.json` 后 ZIP 共 934 项。正式 `Assert-DevelopmentBundle` 校验通过，ZIP 每项解压流 SHA256 与对应输出文件一致。桌面是 self-contained win-x64/Profile Debug，Microsoft.NETCore.App 和 Microsoft.WindowsDesktop.App 均为 10.0.14。固定 Bootstrap 仍是既有 framework-dependent helper，保留其 .NET 10.x 前置条件；本轮没有安装运行时。

E/file-origins.json 为全部 933 个载荷逐项记录来源：直接复制的运行时、前端、helper 和可对应的正式 publish 中间输出均再次核对源文件字节；生成的 JAR、README、正式 publish 生成项和许可证则明确绑定对应配方／命令，未把配方条目冒充已找到逐文件原始缓存路径。

## 为什么这次是同源组装

复用成功 Java 编译 `artifacts/java-compile-20261111-0b7d969-scoped` 的完整输出。先将 280 项源码／配置哈希与当前仓库对照，再逐项核验并复制 682 项编译输出，另外核对准确输出树没有未列文件。应用 **569 class／7 资源**、Finance **85 class／21 资源**，没有重跑 javac/Maven、注解处理器或测试。

旧包 `PersonalAiWorkspace-knowledge-conversation-workflows-20261110` 先通过原 `Assert-DevelopmentBundle` 全清单校验；Git 确认 POM、Finance、应用资源、Bootstrap、NuGet/前端依赖合同等不变。旧包只提供未变的 Boot loader、第三方嵌套依赖、JDK/JNA 及 Maven 元数据。组装彻底移除旧 JAR 的 `BOOT-INF/classes/`、原 Runtime identity 和原 Finance JAR；使用当前成功输出新建全部应用条目及 Finance JAR。Finance 三份 META-INF 元数据保留，嵌入 POM 与当前 POM 字节一致。每个 Runtime 条目都有预期哈希，重开检查后全部相等，嵌套依赖保持 STORED。没有用旧应用 JAR 或失败中间输出充当当前 Runtime。

正式 [package-windows.ps1](../../scripts/package-windows.ps1) 仅增加 `RuntimeBuildRoot`，只允许与 WriteRuntimeIdentity 一起使用，限定到仓库 artifacts 的直接子目录，要求准确 classes 位置并拒绝重解析路径；源码枚举和三个 owner class 哈希规则不变。默认输出合同保留。此次身份写入 E/target/classes，不写原 target；随后仍执行正式 `-AuditRuntimeJar`，不是手写身份或绕过 audit。

Runtime identity 的 `sourceSha=e5e37f47a9af5ea5464592af3fc5c5907216b98db4ede00b886981afc48b70fc`，对应 E/target/source-manifest.json；正式 writer 按其现有仓库源码枚举合同计算，包含原有未跟踪文档的哈希而不复制其内容进包。包清单准确保留 `sourceDirty=true`（六份用户文档），另记 `productSourceDirty=false`。

前端直接复用 `integration-compile-20261111-7e7332e/frontend`，仅正式项目的 `assets.mjs --verify`，无 npm build/ci。上一轮 Profile 条件编译的 135 项源码／配置及六份 NuGet 解析输入经过核验。上一轮 DLL 没有 Runtime 清单，且不是 RID/self-contained 输出；因此必须再做一次正式 `dotnet publish --no-restore -c Debug -r win-x64 --self-contained true`，以编入这次准确的 Format=3 pins 和发布必需的 RID 产物。具体完整参数在 E/publish-command.json；使用已有 SDK／缓存、不 restore 或下载、独立 artifacts 输出。该必要 publish PID 26628 退出 0，未运行产出的 EXE。

内嵌清单通过 `PEReader` 的元数据表／资源节读取，未 `Assembly.Load` 产品。资源的完整字节哈希与外部准确清单相同，420 个 pin 又逐项与包内实际文件哈希相同；不存在空清单、宽泛信任规则或旧 JAR pin。正式 Runtime audit 只读取新 JAR 字节及文件身份，不调用产品 Gate。E/runtime-audit.log 记录 PASS。

整个一次性组装从 UTC `09:18:33.3055481Z` 到 `09:18:50.4944474Z`；工具会话 6446 退出 0，`09:18:50.4994357Z` 释放并 Dispose Session 1 共享重型 mutex。唯一构建子进程有 45 秒期限，超时才会结束其自有子树，本次没有超时或强制清理。E/result.json、cleanup.json、publish-exit.json、runtime-entry-hashes.json、finance-entry-hashes.json、embedded-pins-audit.json 保存结果。没有新的测试、产品启动或模型调用。

## 精确启动命令与顺序（尚未执行）

原 Profile P 必须是 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2`。上轮仅 P 和 P/model-state 的普通元数据已成功；没有据此读取其他内容。本轮没有再访问 P。

未来仅考虑 B 中上述精确 EXE，cwd=B，参数 `--open-development-workspace-once b204c7e3cc33425e841eb1aa32d2caa2`。正常解析必须返回 P，不接受外部 root、改 user.name/LocalAppData、换身份、另建 Profile 或跳过检查。此命令不是本报告的执行授权。

1. `Program.ResolveDevelopmentProfile -> Mmf3AcceptanceLaunch.Parse -> Mmf3ProfileState.Root/Verify/Pin` 使用有效 token 的 KnownFolder 和 OS 句柄绑定；校验祖先、根、规定子目录及 Recovery 登记。必须是这个原 P。OpenOnce 不经过默认工作区选择器，READY 后也不保存 `last-workspace`。
2. `Mmf3ProfileNative.Verify` 固定校验 B/NativeBootstrap 下 exe/dll/deps.json/runtimeconfig.json 四文件，执行 `VerifyProfileNative <原ID>`，核对返回的根和原生 DLL 身份；不执行 Provision。该验证会在多个资源使用点重复，不能承诺 helper 只启动一次。
3. `DevelopmentRuntimeOwner.FindMigratedOperation` 检查 `P/evidence/sqlite-activation`，存在时会枚举至多两项并进入迁移分支。因此未来启动前必须已获准核实该准确登记不存在；有登记或未知即停，不借启动隐式进入迁移或旧 18765 查询。
4. `TrustedDevelopmentRuntimeSources.Open` 验证 420 项准确 pins；`RuntimeAttempt.Begin` 在当前有效 KnownFolder 对应应用根 A 下生成本次运行回执。A 必须由正常准入确认，不能仅由 P 文本推定，因为源码保留 OS 路径重定向支持。
5. 使用 B/runtime/java/bin/java.exe 与上述新 JAR，`-Xmx384m -XX:ActiveProcessorCount=2`，固定原 ID/helper/JNA 参数，`--native-runtime-lifecycle-v1 --server.address=127.0.0.1 --server.port=0`，token/data/model-state 精确绑定 P。Java 在 Spring 前调用 `WindowsNativeGate.admit()`，通过 helper 校验、JNA 固定哈希与加载、Profile pin、Recovery 检查，再取得 ordinary runtime lease 和维护 fence。不能单独调 Spring 或更换入口。
6. 正常加载配置并初始化 Spring stores，owned lifecycle 把动态端口及会话握手返回其桌面所有者，客户端再查 health/auth。健康返回不证明 ModelStateStore 已通过：ModelConfiguration 会把该构造失败转换为 AI 不可用状态，而资料 API 可能仍可用。原 owner 候选的原生结论必须单独记录，不能被 health=OK 掩盖。
7. 正常 WebView 初始化和 Bridge 完成后，才进行一个合成资料／记忆的导入、保存、正常退出和一次独立重开。两次启动必然产生不同 Runtime 会话、token 和运行回执；不做问答生成或其他模块验收。

固定 helper pins 与当前 C#／Java 相同：exe `d1166043c27929957e2c8597c6fd6bbe8a369ad1cfa45791d7dd665506983166`，dll `9dafed0b1379e9f013d6eb83c9ac5518e611c0e15faa3557991a0c4341029698`，deps `27af3a8da9f3b6155d86236c59339053f077e3ad33724e8f5e40281fb0375885`，runtimeconfig `3e965cd7cfd553c2ff4e842d8d8019aaae2da0a21d92ff85a87573dd8d1b95c6`；jnidispatch `5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023`。包内 JDK/JNA 的逐文件身份以已审计的 420 项内嵌清单为准。

## 必须涵盖的实际读写与安全边界

| 目标 | 正常路径会做什么；本轮均未做 |
| --- | --- |
| P 祖先、P 及 credentials/model-state/data/webview2-profile/chrome-profile/browser-test-extension/evidence/logs | 读取类型、owner/DACL、文件身份，持有绑定句柄；加载 P/jna-5.17.0-win32-x86-64/jnidispatch.dll。除原 P/M 元数据外，当前状态未知。 |
| A/Recovery、targets、synthetic-targets 与原 ID 登记 | 准入读取元数据，存在恢复目标则按合同拒绝。普通启动还可能新建缺失的 Recovery、targets、runtimes/原ID/随机attempt；不删除旧登记。 |
| A/Recovery/runtimes/原ID/随机attempt | 新建 intent、started、ready、stop-request、exited，异常时 cleanup-intent/cleanup 的 `.pending`→`.dpapi` 回执，DPAPI CurrentUser；不覆盖旧 attempt。 |
| P/evidence/runtime-admission.lock 与维护 fence | 缺失时创建私有空锁，读取既有锁身份、大小并持有租约；不是零写入。与维护相关的准确检查仍需包含在准入许可内。 |
| P/credentials/client-token | **每次开发 JVM 启动都会验证并轮换 token**：创建 `.token-<UUID>.tmp`，写入随机值，再原子替换 client-token、清理自有临时文件；桌面读取新 token。不是只读凭据，也不是修改 Windows Credential Manager；不记录 token 内容或恢复旧 token 作为回滚。 |
| P/data/database.dpapi、finance.dpapi | 正常启动会检查存在性，存在则读取并 DPAPI 解密配置，可能开启相应数据库连接。最小本地合成验收应先获准仅核实两项不存在；存在时停止并重新界定数据范围，不解密、不触发真实数据库连接。当前未查询。 |
| P/model-state | `.lock` 创建／持有；读取 active-model.json/pending、execution-guard.json/pending、validation-required.json/pending。未知 guard、pending、writer 或对象变化须停并保留；不 armGuard、删除状态或修权限。 |
| P/data/memory.db 及 sidecars | 正常 Memory/Conversation schema 检查／初始化、事务；Conversation 启动会将已有 PENDING 轮次标为 FAILED，而非只改本轮记忆。只允许原自有合成数据库，不能声称不会改变其他合成行。 |
| P/data/knowledge、knowledge-answer-history | knowledge.db、knowledge.lock、sources、staging、history.db、history.lock 及 SQLite sidecars。Knowledge 启动会核对既有源文件、处理已登记的中断 job／待删除项及其 staging；History 可迁移 schema 并将 QUEUED 标为 FAILED。未知 staging 不自动清除。 |
| P/data/knowledge/index | 启动检查 lexical.db；索引不新鲜时会读取当前 ACTIVE 合成资料并后台重建，创建 staging 中的候选和索引文件。这是正常启动的额外读写，不是模型推理。 |
| P/webview2-profile、P/logs/workspace-ui-随机ID.jsonl | WebView 会创建／使用内部浏览器目录和进程，启用 InPrivate；启动与退出均调用 AllProfile 浏览数据清理，退出清理限 3 秒。不能保证只写一个固定文件，也不能称缓存零删除。日志仅产品固定诊断字段。 |

新私有目录／文件在创建时使用现有产品合同的保护 DACL（有效主体、SYSTEM、Administrators）；这是明确的新对象权限设置。已有对象走验证，不自动重写 ACL；没有要求改变系统权限、EFS、注册表、安全策略、账户或检查规则。不得把“持续开发”当成额外安全敏感变更的笼统确认；如需改已有权限或改运行身份，须提交具体动作另行判断。

数据范围还存在必要前提：原 P 曾由准备工具写入自有合成样本，但本次元数据不能证明其当前内容、配置及未完成状态。未来许可必须覆盖上述正常启动副作用；不能承诺“只读两条合成文本”。已准备输入为 `artifacts/profile-compile-20261111-7ef181f/synthetic-source.txt`，SHA256 `9a46340083f48a32711f4a7c9418b2dd8e838ca065791d0ee9ed7f9cb1b5bdc6`。实际 documentId、revision、memoryId 只能由应用返回，尚未生成。

旧 Crashpad 拒绝是某历史 Profile 的 `webview2-profile/EBWebView/Crashpad/reports` 的直接目录列举；已读报告未给出其绝对 Profile ID，故仍不能排除与 P 重叠。本次没有尝试该操作，也未读 crash/GPU 内容。未来浏览器启动可能自行创建／写内部 Crashpad/GPU 状态，与诊断列目录不是同一个操作，但不据此推断旧边界已解除；须先从已有历史明确映射和允许范围。既有 bf786634eabb4e5b80edeadf25cecab8 的 sqlite-activation 拒绝、七个验证目录、旧 Get-NetTCPConnection、被拒脚本及等价运行、restricted URL 均保持停止。

## 资源、退出重开及清理

只使用 Session 1 的共享重型 mutex 和本次持有句柄／Job；正式 Java 堆 384 MiB、2 个逻辑处理器，启动限 60 秒，WebView 初始化限 30 秒，Runtime CLOSE 限 30 秒、自有进程收尾等待 10 秒。未来整个“启动→保存→退出→重开回查→退出”建议限 180 秒；WebView/桌面实际总内存尚未测量，不把 JVM 堆上限当整树内存上限。不得加载模型或发送生成请求；发现旧模型状态不确定即停。

首次正常退出必须确认 Desktop、owned Java 会话 CLOSE、退出句柄信号与锁释放；记录 Job 自然退出和强制清理的区别。只有状态明确结束，才以同一 B/P OpenOnce 独立重开并回查原资料版本。缺少退出证据不能重放。回滚是保留旧包、停止明确归属的本次子树，按实际 ID 经正常产品操作移除本轮合成文档／记忆（如获准）；不删除 P、旧 Recovery 回执、未知状态或整个数据库。token 轮换、正常初始化／reconcile 和索引变化不由 Git 回滚，报告中必须保留这个限制。

## 一次临时空白窗口关联：未确认

按父任务新增的明确授权，只创建了一个无内容、无控件、TopMost=false、最小化的自有 WinForms 窗口；没有使用共享 Notepad 或用户既有应用。命令侧 Windows 机器名 `千里寻白`、Session 1、STA，PID 4268、HWND 6230824，随机标题 `Workspace-Association-96672926471246c9847fc2f736f587e9`。UTC `09:13:24.9747931Z` 显示，60 秒 timer 于 `09:14:24.9987708Z` 关闭并 Dispose；后续只核对该自有 PID，结果 EXITED_PID_ABSENT。

使用 computer-use 文档限定观察范围；实际可用工具为 `mcp__cua_repl.js`。首次 `cua.getState()` 初始化并返回文档／清单；随后只调用一次 `cua.listWindows({emit:false})`，仅输出精确随机标题匹配结果。结果 target=windows、matches=[]，没有匹配 HWND，也没有 PID／主机 ID 可比对。未截图、点击、激活窗口、读取其他应用正文或安装工具；随后结束 CUA 操作，没有换工具重试。

证据为 `artifacts/desktop-association-3b67a7eb3b964cd9907dcc31b22e136c` 下 BlankWindow.ps1、launch.json、shown.json、closed.json、cua-match.json、exit-observation.json；stderr 为空。无匹配不能证明两端不同主机，也不能证明相同桌面；最小化是否影响列表未知，不能据此自动换条件重试。图形关联保持 UNCONFIRMED，不解除 Profile/Crashpad/GPU/原生准入停止状态。

## 可交父任务的下一决定

包、精确身份和源码执行面已准备完成；当前没有运行中的构建、临时窗口或产品进程，没有自动审批拒绝或待审批请求。真正启动前仍需界定原 P 的配置／状态前提、上述凭据轮换与应用/Recovery/WebView 写入，以及旧拒绝目标映射；图形操作还需要可成立的主机关联。不能用包审计或 owner 元数据匹配代替这些条件。

main 与六份用户文档不变，不推送、不混 KAG。正式脚本改动已由 `933bb20` 本地提交，可单独 revert；本次工件独立保留，不覆盖或删除旧包、旧 target、旧失败输出。本轮完成的是候选打包；真实服务、桌面退出重开、原生验收和部署仍未完成。
