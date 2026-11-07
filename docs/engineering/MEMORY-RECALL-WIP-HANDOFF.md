# 相关记忆检索：历史 WIP 交接

> 2026-11-07 更新：后续已通过普通桌面 Explorer 宿主完成原生准入及 3 项真实 Store／HTTP 集成测试。功能、复用评估、限制和回退见 [完成与验证报告](MEMORY-RECALL-REPORT.md)。下文保留开发过程证据，未提交／阻塞描述是当时状态。

2026-11-07。基线 `32819ceb468c8bc95b02b9142d3c4d744030f530`，分支 `codex/recovery-v3-workspace-core`。

**WIP，尚未提交，不是已交付功能。** 开发途中收到用户最新指示：“能提交的先提交了，开发的先不提交”。基线上的既有功能已经提交；本轮涉及 Runtime、客户端、原生窗口及测试的改动是同一未完成增量，没有拆成看似完成的提交，没有推送。

后续用户已授权：**记忆模块开发完成并通过必要验证后，推送到现有 GitHub 仓库**。该授权不包含提前推送当前 WIP；达到条件后先核对远程、分支和公开提交范围，保护未跟踪文档、凭据和用户数据。本阶段仍未达到完整交付条件，没有提交或推送。

## 选定功能与参考

已有链路具备候选生成、审阅、新增或修订、精确版本引用和会话内连续使用，但原生选择器只能字面短语查找。此次开发“按问题查找相关记忆 → 预览 → 明确添加 → 确认选用 → 现有 Ask／Conversation 发送”的完整链路。

参考 [Mem0 Search Memory 官方文档](https://docs.mem0.ai/core-concepts/memory-operations/search) 中按查询召回、限定范围、返回有限排序结果及解释匹配依据的实践。此处使用本项目既有 `LexicalAnalyzer` 的确定性词法分析，并不实现 Mem0 的向量检索、语义理解或重排服务，也没有引入其 SDK。Firecrawl 读取了该公开来源，没有上传本地代码或数据。

## 当前代码

- `MemoryStore.recall` / `MemoryRecall`：当前工作区 ACTIVE 记录、可选类型过滤、同一只读事务；源行扫描受现有 1,000 条容量上限约束，最多读取 1,001 行以拒绝异常超限。对 SQLite／PostgreSQL 使用同一 Java 排序，不建新索引、schema 或缓存。
- NFKC／小写词语和 CJK 相邻字对匹配；存在 CJK 字对时去掉单字查询词，仅单字问题仍可使用单字匹配。每个不同查询词在标题计 3、正文计 1，同一字段重复不加分；分数、更新时间降序及 ID 升序确定顺序。返回前 20 条、有匹配的总数和命中词，不把分数称为概率或相关性保证。
- 新增 native-only `POST /api/v1/memory/recall`。查询放请求体，不放 URL；原有认证、请求体大小限制和错误分类继续适用。没有新增 WebView Bridge 方法或 Browser 授权。160 字符上限沿用 MemoryQuery，空问题拒绝，纯符号查询返回空结果。
- 原生 `MemorySelectionWindow` 新增“查找相关记忆”，原文查找保留；输入本身不发送请求。相关结果仍需单条 get 校验当前 ACTIVE／revision，用户预览、Add、Use 后才返回既有 ID／long revision 选择。新查询失败清除旧结果／预览，保留已明确选中的条目；窗口关闭取消请求，迟到结果被丢弃。
- `RuntimeClient` 校验精确字段、条数、唯一 ID、类型／状态、排序、分数和命中词边界；响应记录的诊断字符串不打印正文、查询或命中词。
- `AssistantApp`、`AssistantWindow` 和 `ConversationWindow` 都调用同一原生选择器。选择授权、逐次 Store 快照、预算和过期拒绝沿用原流程。本轮未改 React、会话发送、模型执行或选择授权规则。

此实现可能命中常见词，也可能漏掉同义词及隐含语义。用户需要在选择器中输入或粘贴问题，没有自动复制会话草稿、后台召回、自动选用、自动写入或模型质量承诺。

## 已执行验证与真实阻塞

证据目录：忽略的 `.verification/memory-recall/`。

1. 首次 Maven 离线必要编译通过；`MemoryStoreTest#recallRanksCurrentSourcesAndPreservesExplicitExactAdmission` 和两个已有 HTTP 合同用例共 **3 errors / 0 failures / 0 skips**，失败在临时存储／Browser Registry 初始化，尚未到检索断言。普通 Surefire 进程没有 `WindowsNativeGate.admit` 所需的受信 Development Profile／Bootstrap／固定原生 dispatch。没有绕过 guard 或把它报成测试通过。
2. 新增纯检索 `MemoryRecallTest` **1 passed，94 ms**：问题与保存正文不必是完整相同短语；全角／大小写、中文相邻字、单字、词边界、不因重复涨分、有限且稳定排序、精确大整数版本、不可变返回值及安全诊断。它不打开数据库或 HTTP，不能替代上述受阻用例。
3. 隔离 Core／Desktop／Desktop.Tests 编译和 **4 个 Native 用例首次通过，473 ms**：客户端合同、相关候选到显式 Ask 引用、过期拒绝及失败保留已选、窗口关闭丢弃迟到响应、原有最多四条选择／取消行为。HTTP 完全合成，不读取凭据或监听真实 Runtime。之后为支持 Unicode 规范化可能扩展的匹配词，将客户端标签上限从 2,048 调整至 4,096，并仅复验客户端用例，**1 passed，52 ms**。
4. Desktop 构建依赖自动执行前端 TypeScript／Vite／资产生成，均通过；保留原有主 chunk >500 kB 提示。没有额外重复运行前端测试。NuGet 使用现有缓存及本轮空来源目录，.NET 输出独立于现有桌面包。

命令：

```powershell
.\mvnw.cmd -o -pl workspace-application -am '-Dtest=MemoryStoreTest#recallRanksCurrentSourcesAndPreservesExplicitExactAdmission,RuntimeApiTest#nativeMemoryCrudContractAndPrivateErrors+memoryAuthorizationAndEveryMutationBodyAreBounded' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o -pl workspace-application -am '-Dtest=MemoryRecallTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore --artifacts-path .verification/memory-recall/dotnet --filter 'FullyQualifiedName~RelatedRecall|FullyQualifiedName~RecallValidatesRankedActiveResults|FullyQualifiedName~SelectorIsReadOnlyActiveExplicitSearchPreviewMaxFourAndCancelLeavesOwnerUntouched'
```

下一步须在符合现有准入的全新合成环境中完成 Store／HTTP 检索、归档／删除／更新和授权边界验证，随后审阅整个增量并明确选择文件提交。已查询 WSL 分发版状态，均为 Stopped，没有启动新 VM、安装工具或改变现有安全设置来绕过阻塞。未访问真实个人数据、配置或凭据，未启停现有服务，未调用真实模型或操作 KAG。

真实模型候选质量、回答质量、Windows 桌面人工流程和 PostgreSQL 新路径均未验收，没有发布受信包。个人备份／恢复继续关闭。

## 保存状态与回退

修改前创建标签 `workspace-before-memory-recall-32819ce`。5 份原有未跟踪文档均保持原样和未跟踪状态，在证据目录复制并记录 SHA-256；本轮新增检索类、纯检索测试及本交接也是未跟踪 WIP，需要一起保留。不能 `git clean`、`reset --hard` 或覆盖这些文件。

由于最新用户指示，本轮不提交开发代码。Git 标签只保护已提交基线；未提交差异另存于本轮忽略证据目录，便于检查，不构成正式提交或异机备份。Git 及这些代码副本都不保护真实数据库、模型、凭据或服务状态。

## 接续排查：已定位宿主路径重定向，仍未提交

本次接续前，Git HEAD 仍是 `32819ce`，原有 WIP 和全部未跟踪文件先复制到 `.verification/memory-recall-integration/preserved/`，已跟踪差异保存为 `before.patch`。未重置、清理或覆盖原有文档。

调查确认普通 Surefire 缺少原生准入；历史 `mmf3-profile-state.ps1` 已明确停用，不能调用它或去掉其拒绝语句。新增三个未提交测试工具文件 `bootstrap/tests/MemoryRecallIntegrationProbe.{cs,csproj,java}`：通过当前产品的 `Mmf3ProfileState.Prepare` / `Mmf3ProfileNative.Provision` 创建全新随机 ID Profile，由固定 Bootstrap 准入，并计划在所属 Job 的 Java 子进程中只运行既有三个 Store／HTTP 场景。Java 计划限制为 `-Xmx384m`、2 个处理器；不接触真实模型或既有 Runtime。它拒绝复用已有 fixture，失败目录和证据保留。

`RuntimeApiTest` 的 Windows 临时目录入口也改为在已准入的新 Profile 的 evidence 下用现有私有目录创建方法建立新目录，非 Windows 行为不变；JUnit Store 临时目录由启动器的 `TempDirFactory` 使用相同方法创建。没有修补默认临时目录的 ACL，也没有修改任何产品准入代码。

当前固定 Bootstrap 四份文件逐项匹配 `Mmf3ProfileNative` 和 `WindowsNativeGate` 中已有 hash，没有重编译 Bootstrap 或改 pin。离线 Java 必要编译及纯检索测试再次通过（1 passed，总构建 6.25 秒），新增 Java 启动器编译通过；隔离 .NET Profile 测试启动器构建 0 warnings / 0 errors。原生四场景与客户端复验仍引用上一阶段证据，本阶段没有重复运行。

然而，**新 Profile 在 Prepare 阶段、Java 启动前就被拒绝**：

- 精确调用链为 `WindowsProfileSecurity.OpenDirectory → HoldDirectories → State → Mmf3ProfileState.Prepare`，异常为目录身份拒绝。
- 第一次新建的 Profile 根目录已经存在，尚未创建任何必需子目录。只读元数据确认它、Profiles 和应用父目录均由当前用户拥有、DACL 受保护且规范，仅用户／SYSTEM／Administrators 的既有权限。没有修改这些父目录或权限。
- 与产品相同的目录句柄只读 API 显示：父目录最终路径匹配，但新 Profile 根目录的最终路径不匹配。预绑定路径在逻辑 LocalAppData，实际句柄路径在 Codex 包的 `LocalCache/Local/PersonalAIWorkspace.Development/Profiles/<本次新 ID>`。确切私有路径只保存在忽略证据中。
- 分别用直接 apphost、标准 ShellExecute 隐藏启动、已安装的标准 dotnet 宿主尝试；每次新随机 Profile，三次均在同一 Prepare 阶段拒绝。未调用 Java、数据库或模型，也没有挂起的测试服务。三个失败的空合成根目录保留，不迁移、不复用。
- 微软 [KNOWN_FOLDER_FLAG 文档](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/ne-shlobj_core-known_folder_flag) 说明打包进程可存在文件系统重定向，`KF_FLAG_RETURN_FILTER_REDIRECTION_TARGET` 用于读取相关目标。仅以该 flag 做了只读查询，本宿主下它与默认 LocalAppData 返回相同结果，因此也不能宣称加一个 flag 已解决问题。

**目前阻塞的是测试宿主的逻辑路径／实际写入路径不一致，原生 guard 正确拒绝了它。** 没有解除校验、改变安全设置、改固定 hash、移动现有 Profile 或从真实个人工作区借用配置。

后续需要在路径绑定一致的受信 Windows 测试宿主运行新 fixture，或先单独审查产品 Known Folder／目录身份解析对这种打包宿主的适配，再以全新合成 Profile 验证；不得把允许重定向、修改 pin 或复用失败目录当作快捷测试开关。完整 Store／HTTP 验证仍未通过，因此整个相关记忆功能和本次测试工具继续未提交。安全初始化问题已从泛化的“缺少 Profile”缩小为有句柄证据的具体路径身份不一致，但尚未修复。

## 接续验证：不依赖 Profile 的真实 SQL 与认证过滤器

按后续指示，本阶段保留宿主阻塞事实，完成独立验证，不再重试创建 Profile 或更换启动方式。开工前再次保存全部 WIP 和未跟踪文件，证据在 `.verification/memory-recall-query/`。

参考仓库 `SqliteKnowledgeMigrationTest`、`FinanceInfrastructureTest` 的内存 SQLite 做法，将现有召回 SELECT／行映射／排序提取为包内只读 `MemoryRecall.query`。生产调用仍只有 `MemoryStore.recall → transaction(false, ...) → query`；连接由原 Store 构造与准入取得，事务所有权、异常脱敏及回滚保持不变。没有新增替代 Store 构造函数、假 Profile、允许任意路径的参数、身份状态反射修改、认证开关或数据库配置。查询辅助函数本身不创建连接、文件、schema 或任何准入能力。输入验证移入同一包内的 `MemoryRecall.terms`，仍在事务前完成；只将原有行解析器设为包内可见供同一查询路径复用。

新增 `MemoryRecallQueryTest` 在全新 `jdbc:sqlite::memory:` 数据库中建立合成源行，执行的正是生产 SELECT 和行映射。测试期间 `PRAGMA query_only=ON`，并核对 `total_changes()` 没有变化。没有打开任何持久化用户数据库。

- 类型／ACTIVE 筛选、标题和正文排序、Unicode 全角归一化和中文命中词通过。
- SQL 更新后的内容和 `9007199254740994` 版本能够重新检索；归档与删除后消失，恢复后重新出现，先前返回的结果保持不可变。这里的更改由测试直接写入合成内存行，**不代表 Store 的 CRUD／快照事务已验收**。
- 1,000 条源行只返回前 20 条、总数正确且同分排序稳定；第 1,001 条触发受控拒绝。空、超长、NUL 和坏 Unicode 查询保留明确错误，符号／无匹配查询返回空。

另参考现有 `FinanceRouteSecurityTest` / `VisionSecurityTest`，新增 `MemoryRecallSecurityTest`，以合成凭据提供器执行真实 `LocalClientFilter`：浏览器 credential、无凭据／错误凭据、非 loopback、网页 Origin 和预检均拒绝，拒绝前不消费查询正文；明确 Native fixture 才进入后续链，并保留原正文。未知长度的 32,769 字节请求仍被 413 拒绝；响应保留 no-store，错误不回显查询。它没有打开凭据文件、监听 HTTP 或初始化安全登记，不冒充完整 Spring HTTP 联调。

实际执行 **4 个场景全部首次通过、0 failures / errors / skips**：

```powershell
.\mvnw.cmd -o -pl workspace-application -am '-Dtest=MemoryRecallTest,MemoryRecallQueryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 3 passed：2 个真实内存 SQL 场景 0.399 s；既有纯排序场景 0.007 s。
.\mvnw.cmd -o -pl workspace-application -am '-Dtest=MemoryRecallSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 1 passed：真实认证过滤器／合成凭据场景 1.288 s。
```

必要 Java 编译和 `git diff --check` 通过；没有重跑前端／Native／完整套件，没有为压下 Mockito 的既有动态 agent 提示而修改 JVM 安全设置。本阶段没有创建新的 Profile、启停服务、调用真实模型、访问个人数据或操作 KAG。

尚未验证的仍是：受信 Windows 文件存储与 Store 事务整体、完整 Spring HTTP／桌面联调、PostgreSQL 新查询路径、真实模型候选和回答质量。上述独立结果缩小了查询和过滤器的不确定性，**没有解除宿主准入阻塞**。整个功能继续未提交；原有 5 份文档保持原样，其余 WIP 和新增测试另存副本，待完整交付条件满足后再提交并按授权核对推送范围。
