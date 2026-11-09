# 合成加密恢复：真实桌面阅读与独立 JVM 重开

2026-11-09。基线 `18175d3`，分支 `codex/knowledge-history-desktop`，回滚标签 `workspace-before-knowledge-history-desktop-20261109`。验收工具提交 `ee5219f`。仅本地开发与提交，未推送、合并、部署或发布；仍为 Development-only。Workspace 与 KAG 分开，未操作 KAG。

## 已完成的闭环

在全新合成 Profile 内生成知识来源和五条终态问答，经已有 OpenPGP 合成归档恢复到新候选目录，再由真实 WPF／React／WebView2／Native Bridge／RuntimeClient／HTTP／SQLite 读取。第一轮 JVM 完全退出后，第二轮独立 JVM 打开同一恢复数据，重复通过历史阅读及引用检查。原始来源库在恢复前关闭并改名，第二轮不重新生成、导入或恢复数据。

两轮均保留原问题、检索词、状态、时间、答案和精确引用。旧引用打开第 1 版原文，同时明确提示当前可用版本为第 2 版；已删除来源显示错误，原答案仍可阅读。FAILED、CANCELLED、TIMED_OUT 分别显示原终态，没有重新发出问答或重放原任务。未启动模型或合成提供器。

这是**隔离桌面恢复验收**。正式用户备份按钮、原生来源登记／候选激活、完整 AssistantApp 恢复启动和 PostgreSQL 尚未连接。不能据此认为个人资料已经备份，或当前用户入口已经包含问答历史。

## 原有安全设计与复用取舍

先核对既有 Recovery 设计、`ProfileRecoveryCoordinator`、`NativeProfileRecovery`、`DevelopmentRuntimeOwner` 和 `AssistantApp`：原生恢复拥有来源登记、目标准备、独立消费及激活链，现有内容和数据库身份按 PG 流程处理；普通 AssistantApp 就绪还会保存最近工作区。它的 `syntheticLifecycleProbe` 要求真正的 `RecoveryActivation`，不能拿普通新建 Profile 代替激活能力。

因此采用任务明确允许的隔离验证路径，扩展已有 `MemoryDesktopAcceptanceProbe`，复用已有私有 Profile provisioning、Native Gate、JVM Job、桌面 NativeActions 适配器和实际 MainWorkspaceWindow。没有更改生产启动路径、允许任意恢复目录、伪造注册／READY／ACTIVE 状态，也没有放宽身份、ACL 或 Bootstrap pin。测试创建自己的普通 WPF Application，不运行 AssistantApp／DevelopmentRuntimeOwner；此处的真实 JVM 启动不是正式应用恢复生命周期。

| 复用部分 | 本轮边界 |
| --- | --- |
| 合成加密归档 | 直接调用已验证的 `SyntheticKnowledgeSnapshot`／`SyntheticEncryptedKnowledgeSnapshot`，沿用现有 Bouncy Castle OpenPGP，不新增密码实现、归档类型或依赖。此前认证与非法内容测试见[加密报告](SYNTHETIC-KNOWLEDGE-HISTORY-ENCRYPTION-REPORT.md)。 |
| 合成数据工厂 | 测试类 `KnowledgeRecoveryDesktopFixture` 仅在明确的 `prepare`／`reopen` 属性下运行，固定使用本轮全新 Profile 的 `evidence/kd`。恢复候选相对路径限制在其 `fixtures` 子目录；普通 `Profile/data` 保持空目录。 |
| 跨进程校验 | 第一轮保存预期值 oracle；第二轮要求不同生产者 PID，仅打开既有候选。oracle 用来断言，不写回数据库，不构成生产信任或来源登记。来源、历史完整记录及引用先后由 Java 严格校验。 |
| 实际桌面链路 | 复用现有前端资产、Knowledge Bridge、RuntimeClient 和 Spring API。测试 transport 只把固定 loopback 地址映射到本轮随机端口，拒绝非 GET 请求；不触及既有 Runtime。 |
| 模型与任务 | 不启动 mock provider、Ollama 或模型；模型状态保持未就绪。每条旧 task ID 均在新 TaskManager 中为 TASK_NOT_FOUND，历史读取不会注册任务。 |
| 隔离与退出 | 仅新建自有 Profile 和进程；两轮由现有 Job 管理，窗口正常清理，JVM 退出后检查端口关闭。最近工作区选择仅比较前后 SHA-256，不读取其内容用于启动。 |

所有新 Java 代码位于 `src/test` 或 `bootstrap/tests`，C# 增量位于既有测试工程；生产 Java／C#／前端实现没有变更。没有增加生产 API、Bridge 操作、用户按钮或恢复权限。本轮继续复用项目已采用的开源实现，不引入新包；许可及密码实现评估沿用前轮报告。

## 本轮实测

证据根：`.verification/knowledge-history-desktop-20261109/`；实际运行子目录 `run-1/`。新 Profile ID `d1be24a0d4f8445991ddd05fded53a05`。JDK 21.0.7，JVM 限制为 384 MiB／2 CPU；两轮顺序执行，使用原 Native Gate。

| 检查与证据 | 结果 |
| --- | --- |
| `java-build-1.log` | 首次测试编译失败：新 fixture 的 lambda 及 verify 方法未处理既有 API 的 checked exception。改为普通循环并声明异常后修复；未进入桌面测试，不计为通过。 |
| `java-build-2.log` | 离线 Maven reactor package 与既有 Runtime JAR 审计成功。构建跳过测试，不能计入下列原生运行次数。 |
| `dotnet-restore.log`、`dotnet-build-1.log` | 既有验收工程恢复及编译成功，0 warnings／0 errors；复用已有前端产物并执行资产校验，没有重新改写前端。 |
| `run-1/prepare.log` | 第一轮实际创建密文、恢复新候选并打开桌面；JUnit 1/1，0 failed／skipped。JVM PID `52564`，HTTP `50205`，退出码 0。 |
| `run-1/reopen.log` | 第一轮退出后另启 JVM；从恢复数据重开并再次打开桌面；JUnit 1/1，0 failed／skipped。JVM PID `39052`，HTTP `53353`，退出码 0。 |
| 两份 `*-ui.json` | 每轮五次真实历史点击，0 次 answerSubmit；5 条终态记录、1 份活动文档。成功记录 2 条，另有 FAILED／CANCELLED／TIMED_OUT 各 1 条。 |
| 旧版本引用 | API 的第 1／2 版全文与预期完全一致；桌面选择第 1 版并精确高亮引用偏移，显示当前第 2 版提示。中文、emoji、换行及 `<script>` 字面文本保留，没有生成 script 元素。 |
| 缺失来源引用 | 实际按钮打开已删除来源后，Runtime 返回 KnowledgeNotFound，页面显示来源不存在错误；原答案和引用记录保留。 |
| 两次前后 Store 检查 | Java 将严格验证后的每条原终态记录与实际历史对象逐项相等比较，覆盖问题／检索词／时间／Profile 摘要／prompt／结果／错误；全部旧任务 ID 不存在于执行队列。 |
| `processes.json` | 两轮均 exited=true、exitCode=0、mutationAttempts=0。每轮 historyGets=10，其中 5 次来自真实 UI，另 5 次是测试读取对照。 |
| `result.json`、`host-result.json` | 宿主退出 0；actualWebView／actualHttp／actualSqlite=true，actualAssistantApp／modelStarted／profileActivated=false；personalSelectionUnchanged／ordinaryProfileDataEmpty=true。 |

本轮运行首次即通过。使用项目已有的实际 Explorer 桌面 COM → 隐藏 PowerShell 启动方式；包装脚本核对当前用户且拒绝管理员进程，避免已知的包装宿主 Known Folder 映射差异。没有修改系统安全设置、执行策略或已有服务。

四张截图保留在 `run-1` 的 `prepare-old-reference.png`、`prepare-missing-reference.png`、`reopen-old-reference.png`、`reopen-missing-reference.png`。两阶段均有 DOM 与 API 断言；收尾另目视检查第二轮两张截图，旧版提示／高亮和缺失来源错误显示正确。

候选 JAR SHA-256：`45E7E6C8D9D88DBC465461673DFCE1F5DB799EBE1FC59516F757844BE71DED30`。测试运行新编译的 classes／test-classes，经上述测试宿主启动；没有部署该 JAR，也没有执行完整正式启动器。没有重跑前轮 16 项归档认证测试或无关全套，不能将前轮结果计为本轮结果。

收尾只读检查确认两轮 JVM、自有 Profile 的 WebView 进程和两个端口均不再存在。六份原有未跟踪文档按 `preserved-documents.json` 原始 SHA-256 逐项相等，未编辑或暂存。测试只使用合成内容，没有对真实个人数据库运行备份／恢复，没有操作 KAG、现有模型或数据库服务。

## 复现与剩余范围

先离线构建当前 Maven reactor，再以 `Mmf3ProfileAcceptanceBuild=true` 构建既有 `bootstrap/tests/MemoryDesktopAcceptanceProbe.csproj`；将 `bootstrap/tests/MemoryDesktopIntegrationProbe.java` 编译到独立 classes 并放在测试 classpath 首位。从路径身份一致的普通 Windows 桌面宿主运行测试 EXE，依次传入 java、既有 JNA JAR、classpath 文件、全新证据目录及 `knowledge-recovery`。宿主自行生成新 Profile，并顺序执行 prepare／reopen；不要对已有 Profile 手工设置 fixture 属性或复用已完成证据目录。完整本次命令在忽略目录的 `run-1.ps1`。

未完成的产品工作仍是：以独立的授权来源／目标合同将 SQLite 来源＋问答历史纳入既有原生恢复登记，完成来源消费、候选准备、显式激活及完整 AssistantApp 就绪链，然后提供准确的用户入口。本轮没有为此扩展 PG 框架或提前开放按钮，不能把测试 oracle、数据路径或成功读取替代原生信任证明。

恢复数据库与来源仍是私有明文；该归档不包含 Memory、Conversations、Finance、凭据、模型或运行任务。个人停写证明、跨机／跨 Profile、PostgreSQL、断电／磁盘满／杀进程、恶意同 SID 进程、最大容量与人工 UI 验收均未随本轮完成。当前个人恢复仍未开放。

回退可按需 revert 本轮文档和测试提交；此前加密实现独立保留。基线标签用于定位，不覆盖用户文件，不删除证据或合成 Profile。
