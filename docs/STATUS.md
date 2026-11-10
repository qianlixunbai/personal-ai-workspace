# 当前状态

2026-11-10 最终 r2 包的退出疑点已完成一次有界确认：Desktop 退出 0 后，自有 guardian Job 从 5 降至 2 个成员，2,941 ms 样本归零，随后关闭空 Job，无外层强制清理。观察最多 15 秒且计入原 420 秒总预算；资源限制和锁保留，实际总耗时约 78.2 秒。前轮包装在 Dispose 后约 170 ms 即收尾，不能由此判泄漏；本次与历史约 3 秒异步结束一致，无需修改产品或重打包。合成 guard 未变，远端终态仍未证明、productionGo=false。新增聚焦观察包装，首个无效长度 ID 的准备失败回执保留，产品未启动；有效新 Profile 完成一次确认。会话三个实质模块已完成，未推送。[时间线、包装与精确边界](engineering/INTEGRATED-EXIT-OBSERVATION.md)。

2026-11-10 最终 r2 整合包已完成一次真实 EXE 的隔离网页/Runtime 联动：React 正常就绪、原生认证有效、当前 SQLite 状态可查；“查看阻断请求（只读）”展示两条合成未知请求，恢复保持禁用。托盘退出后，独立原生维护入口确认本次 Runtime 正常关闭，guard 哈希原样保留，维护继续 BLOCKED / productionGo=false。Desktop 退出 0；外层收尾前 5 个自有 Job 成员由 Job 清理，不能称全树自然退出；后续检查 Job 0，两轮锁均释放。使用既有 port=0 生命周期，没有查询旧端口、调用模型或修改产品代码，原 r2 包继续有效且未重打包。纠正此前“普通入口固定端点”的旧判断；真实恢复、默认启动与完整维护仍未验收。[截图、证据与范围](engineering/INTEGRATED-WEB-RUNTIME-ACCEPTANCE.md)。

2026-11-10 累积功能已整合为独立新开发包 `artifacts/PersonalAiWorkspace-integrated-development-20261110-r2.zip`，产品源 `75b9614`。完整 Java 产品、Desktop 与 React 重建；567 个 class、248 个沿用资源/依赖条目、933 个载荷、934 个 ZIP 条目及 420 条内嵌 pin 核对通过。最终实际 EXE 的选择器取消、合成只读维护页及迁移审阅页检查通过并正常退出，Job 0/锁释放。第一份无 `-r2` 后缀候选已因 JAR 文件头问题作废，不能使用。没有启动 Runtime/模型/数据库；完整网页联动、真实恢复和维护仍 BLOCKED / productionGo=false。旧包、main、六份用户文档和默认配置不变，未推送。[最终包、SHA、失败证据与验收边界](engineering/INTEGRATED-DEVELOPMENT-DELIVERY.md)。

2026-11-10 模型状态真实 writer 移交、停机后的当前来源／备份／配置／目标复核，以及只读迁移审阅页已本地交付，产品提交 `79f81ff16527ccd15d4ce04c56b5db6b6b781e71`。新包 `artifacts/PersonalAiWorkspace-maintenance-model-review-20261110.zip`，SHA-256 `F1056BDDAC78D852B0645AF9E50C6D63E7E85C69DD868AFC9EAB7F68A4C83BFD`；933 payload／934 ZIP 条目／420 内嵌 pins 全部核对。非空模型选择字节保留；pending guard 在导入前拒绝；正常结束与真实 Job 中断均保留后来数据，普通启动仍拒绝。两个临时 PostgreSQL 正常停止、凭据清理，Job 0／共享锁释放。模型权重／runner／推理未执行；审阅页仅编译，未做可视验收。**普通重启授权、外部模型执行所有权、最终来源／目标确认与执行入口仍未完成，完整维护 BLOCKED 保持。** main、六份用户文档、真实数据和旧包不变，无推送。[实际资源、验收与精确后续边界](engineering/MAINTENANCE-MODEL-REVIEW-ACCEPTANCE.md)。

2026-11-10 原生分阶段 Runtime 接管与本次会话安全结束已交付：产品提交 `1c37625595aff3dddd3544be850239da329c72e1`；新包 `artifacts/PersonalAiWorkspace-maintenance-runtime-handoff-20261110.zip`（SHA-256 `CF7567E167BAD79CD7AF65A883035BB7B08AB3BD11AC20E63884E86FE657CB8C`），933 payload／934 ZIP 条目／420 内嵌 pins 核对通过，Runtime 除来源标记外与验收候选一致。来源／备份／配置重叠移交，真正 Spring 数据源完成绑定核对后进入可写初始化，正常 Knowledge 初始化和三个实际 Store 后端核对通过。HTTP 新增数据在安全结束后保留；新进程核对完整终态，真实中断保留未完成记录，普通新 JVM 继续被 fence 阻断。两组临时库正常停止、凭据清理，验收及打包 Job 0／共享锁释放。普通重启授权、模型所有权和完整用户入口仍未完成，BLOCKED／未注册写入者保护保持。旧包、main、六份用户文档和真实数据不变，无推送。[完整协议、SHA、实际证据、历史失败与接续](engineering/MAINTENANCE-RUNTIME-HANDOFF-ACCEPTANCE.md)。

2026-11-10 发布后所有权模块已本地交付：产品提交 `6b133f078bda9051ce98d521005d90a5a22637ea`，新包 `artifacts/PersonalAiWorkspace-maintenance-publication-owner-20261110.zip`（SHA-256 `6DB6D33DBBF5BE20D630D723AA94A8BBFF20530EB1CEDE4F258BA1CE972EBD5D`）。933 payload／934 ZIP 条目／420 内嵌 pins 核对通过，Runtime 除来源标记外与最终验收候选相同；publish Job 0、锁释放。只完成发布后持续持有与失效保全，没有完成原生接管、真实 Runtime 后端核对或安全结束协议，完整维护迁移保持 BLOCKED。旧包、main、六份用户文档和真实数据不变，无推送。[完整交付与精确接续冲突](engineering/MAINTENANCE-PUBLICATION-OWNERSHIP-ACCEPTANCE.md)。

2026-11-10 配置发布后的活所有权模块已通过真实合成验证：直接 owner 发布后继续持有来源、备份、发布配置和原目标 SHARE 锁连接；失效拒绝后保留资源到显式关闭，关闭保留操作记录和普通启动 fence。最终候选验证了跨进程文件／目标 SQL 写入阻断、失效后的保留及关闭后后来数据保全；临时库正常停止、凭据清理、子进程正常退出、Job 0、共享锁释放。正常 KnowledgeStore 初始化会执行恢复写入，与维护 SHARE 锁冲突；原生父子移交、分阶段 Runtime 启动、真实后端验收及安全完成仍未实现，完整维护迁移继续 BLOCKED，没有用旧回执或固定 READY 开放入口。[实际边界、限定验收与接续约束](engineering/MAINTENANCE-PUBLICATION-OWNERSHIP-ACCEPTANCE.md)。

2026-11-10 维护绑定／配置层已统一交付：产品提交 `ccfffa2d463957713fd2bf081f785c4059307038`，新包 `artifacts/PersonalAiWorkspace-maintenance-binding-config-20261110.zip`（SHA-256 `0EAC0591CB9751D756638BF97E373C41B31948B9258550E2EB27B0127B259827`）。933 payload／934 ZIP 条目／420 内嵌 pins 全部通过，交付 Runtime 仅更新最终验收候选的来源标记；publish Job 0／锁释放。原生所有权激活移交及启动后的真实权威核对仍缺失，配置发布与 fence 必须一起保留，不能称完整维护迁移已开通。旧包、用户文档、main 和真实数据不变，无推送。[完整 SHA、资源收尾和接续缺口](engineering/MAINTENANCE-BINDING-CONFIGURATION-ACCEPTANCE.md)。

2026-11-10 已补齐维护目标的持久绑定、受保护配置意图／有限恢复写入，以及未完成维护的普通启动阻断。新合成验收通过 PostgreSQL 重启后身份重读、新进程完成配置发布、后来目标数据拒绝并保全、后来配置保留、已尝试后缺席配置不重建、普通启动仍拒绝；没有重放导入。三个 Java 子进程正常退出，临时库正常停止，凭据清理，Job 0／共享锁释放。原生激活所有权移交仍未实现，BLOCKED／未注册写入者保护不变，完整维护迁移未开通。main、六份用户文档、真实数据和旧包保留，不推送。[最终候选、历史失败、精确验收范围与接续](engineering/MAINTENANCE-BINDING-CONFIGURATION-ACCEPTANCE.md)。

2026-11-10 本轮 owner 已统一交付：产品提交 `3ab41e34256eb3095bb61645ed14db2e904c157f`，新包 `artifacts/PersonalAiWorkspace-sqlite-maintenance-owner-20261110.zip`（SHA-256 `8FA58BC5C65C0FD8691FDDCE66F65EF561AD9C60948DF89A3F315D87A6EF6A3D`）。933 payload／934 ZIP 条目／420 内嵌来源 pin 全部通过；候选与交付 Runtime 除构建来源标记外逐项字节一致。打包 Job 0、锁已释放，旧包、六份用户文档与 main 保持原样，无推送。此组已完成共同事务、来源 lease、owner／阶段观察，下一组转入原生所有权移交与配置恢复设计；完整维护迁移仍未开通。[完整 SHA、验收和接续边界](engineering/SQLITE-MAINTENANCE-OWNER-ACCEPTANCE.md)。

2026-11-10 SQLite 来源停写 owner 与受保护阶段观察已完成合成闭环：真实 NativeGate/exclusion 下，已有写入者阻止获取，两域数据库和知识原文的跨进程写入／可写映射被拒绝；完整备份保持至一次真实共同提交和新连接核对。并发 owner 拒绝，关闭后的新进程能重读 DPAPI 阶段链，持久记录仍阻止重放；未完成记录、后续配置和新增源数据均保全。三个 Java 子进程正常退出，临时 PostgreSQL 正常停止、密码清理、Job 0、共享锁释放。来源 lease 独立提交 `0893f525e8cd3a3cba5eba71448d4d58ffa837b6`。原生 BLOCKED 保持；当前是来源／备份／导入 owner 和只读恢复判定，尚缺激活期间原生所有权移交、稳定目标绑定及配置恢复写入，未开通完整用户迁移。没有重跑既有两域事务、网页或 EXE 矩阵，main、六份用户文档和旧包保留，不推送。[实现、最小验收、失败现场与剩余阻塞](engineering/SQLITE-MAINTENANCE-OWNER-ACCEPTANCE.md)。

2026-11-10 两域共同事务已实现并通过一次真实 PostgreSQL 闭环：记忆／会话与知识在同一目标事务写入，知识 CHECK 失败时全域回滚；实际 commit 后模拟确认丢失保持 COMMIT_UNCONFIRMED，只读核对完整数据，不重放；新数据与重复拒绝保全、Store 重开、第二个新目标普通成功均通过。源与完整合成备份不变，配置未生成；临时 PostgreSQL 正常停止、密码清除、Job 0、共享锁释放。模块提交 `12b2d880fbab3ecfab655671b8d157ab7cc6d485`，新开发包 `artifacts/PersonalAiWorkspace-combined-migration-20261110.zip` 的 933 payload／934 ZIP 条目／420 内嵌来源 pin 全部通过。交付 Runtime 只更新已验收候选的构建来源标记，其余 JAR 字节一致；未重跑完整 EXE。新 API 只接受已隔离快照，原生来源准入仍 BLOCKED，受保护阶段日志和配置恢复未实现，不能称完整用户迁移。main、六份用户文档、旧包与真实数据保留，不推送。[实现、验收、历史失败、完整 SHA 与精确阻塞](engineering/COMBINED-MIGRATION-TRANSACTION-ACCEPTANCE.md)。

2026-11-10 实际数据库设置切换与独立重开已通过：原交付包的 AssistantApp/React/Bridge/原生窗口/Java helper，在新合成 Profile 和独立 PostgreSQL 17.11 中完成检查、取消保存、确认 DPAPI 保存、明确重启与独立宿主重开；两次实际 PostgreSQL Store 读回同一记忆和知识原文，旧令牌失效，切换区间源库字节相同。验收宿主承载真实组件，不是无参数产品 EXE 或物理输入验收。数据库正常停止、临时密码/配置移除，最终 2 个自有子进程由 Job 收尾、锁释放；生产代码/旧包未变，无重打包。完整维护入口仍受来源停写证明、两域事务及配置激活协调缺口阻塞，已给出可维护产品方案，未绕过准入。[完整结果、失败历史、清理及接续](engineering/DATABASE-CUTOVER-ENTRY-ACCEPTANCE.md)；[入口设计审查](engineering/MAINTENANCE-CUTOVER-ENTRY-REVIEW.md)。

2026-11-10 已补齐真实 PostgreSQL 离线导入：使用交付包原始 Runtime、全新合成 Profile 和独立 PostgreSQL 17.11，Workspace v3 与 Knowledge v1 两条文件迁移 API 均通过长源/备份路径、实际目标约束失败回滚、成功提交逐列读回、重复拒绝、备份/源保全及 Store 重开。JVM 退出 0；报告复制因加密属性失败导致包装退出 1，随后仅导出既有合成文本，哈希相同，未重跑迁移或改 EFS。自有数据库正常停止，Job 清理前 0、锁释放。无生产代码或包变更、不推送；完整用户维护入口/后端切换、默认入口与正式发布仍未验收。[证据、历史失败与明确范围](engineering/OFFLINE-POSTGRESQL-IMPORT-ACCEPTANCE.md)。

2026-11-10 迁移修复统一开发包已交付：`artifacts/PersonalAiWorkspace-migration-long-path-20261110.zip`，构建来源 `31b877616779dfa5c8e2e80c6a319e7b4b29958f`，933 payload / 934 ZIP 条目逐项核对，420 内嵌来源 pin 与实际文件一致；Runtime 与 Desktop 重建，前端/Java/helper 不变。旧包全部保留，main 与六份用户文档不变，不推送。新包完整 EXE 未重跑；退出观察来自旧验收程序，默认入口、成功维护迁移和正式发布仍未通过。构建 Job 清理前 2 个成员由 Job 收尾，锁已释放；断连恢复先只读核实，无重复动作或未定结果。[完整 SHA、证据、权限边界和下一会话交接](engineering/MIGRATION-AND-EXIT-DELIVERY-HANDOFF.md)。

2026-11-10 退出剩余成员已完成一次限定观察：同一已验收 EXE 正常托盘退出码 0 后，guardian Job 从 5 降为 2，约 3.05 秒内归零，再关闭空 Job；没有强制收尾，无持续残留证据，因此未改产品关闭逻辑。Runtime Owner 仍用既有终止机制，不称 Java graceful shutdown；前次三次即时 Job 清理的历史结论保持。共享锁已释放，未查询旧进程或被拒范围。详见 [退出观察](engineering/DESKTOP-EXIT-DRAIN-ACCEPTANCE.md)。

2026-11-10 离线迁移长路径源库/备份已修复：Workspace 与 Knowledge 各自的长源路径、长备份路径共 4 个合成组合通过；目标不可用时备份完整、源库哈希及全部表行不变、再次提交不覆盖、写锁释放。原版同 4 个组合在目标前失败。未连接 PostgreSQL、启动模型或修改默认配置；这是备份与失败回滚验收，不能称真实用户迁移或目标导入完成。源码与必要聚焦验证见 [离线迁移报告](engineering/OFFLINE-MIGRATION-LONG-PATH-ACCEPTANCE.md)，统一包尚待更新。

2026-11-10 完整正常网页验收已通过：复用 `64b33b2` 新包及全新合成工作区，实际 EXE／自有 Runtime 三次启动约 12.6 秒完成 React/Bridge 就绪，原生认证与 SQLite 状态通过；正常网页明确保存一次 Memory，独立 EXE 重开读回相同 ID、标题、正文、ACTIVE／Revision 1 和显示更新时间，没有再次保存。三次真实托盘退出码均为 0，浏览器清理通过；每次 Job 关闭前仍有 5 个成员，由 Job 收尾，不能称全部自然退出。共享锁均已释放，默认记录未改，无模型或真实数据操作。用户自身查询时的“无监听”与应用运行证据分别记录，未重试被拒系统查询。验收说明提交 `f0cbfa2`，新包 `artifacts/PersonalAiWorkspace-normal-web-verified-20261110.zip` 已交付：933 个 payload 文件中仅 README 改变，其余 932 个文件字节不变，934 个 ZIP 条目实字节核对通过；旧包保留、无重编译、无推送。默认入口、维护迁移、个人恢复、跨机及正式发布仍待验收。[完整证据、SHA 与边界](engineering/MEMORY-NORMAL-WEB-EXE-ACCEPTANCE.md)。

2026-11-10 已撤销上一条自行增加的“需用户确认端口独占”条件：旧 UNKNOWN 本身不是权限拒绝，持续开发授权已覆盖常规只读检查及安全合成验收。按更正执行了一次正常权限 `Get-NetTCPConnection -LocalPort 18765 -State Listen -ErrorAction Stop`，此次实际返回 `CimException / PermissionDenied / HRESULT 0x80041003`、消息“拒绝访问”，并非无监听项；已保存精确错误。遵照真实拒绝后停止的要求，没有换路线／提升权限重查，没有识别或终止进程，也未启动 EXE、Runtime、浏览器或准备 Profile。18765 仍为 UNKNOWN，完整联动验收仍受阻；此为系统 CIM 访问拒绝，不是自动审批拒绝，没有待审批或结果不明启动，未持重运行锁。[更正、确切错误及后续边界](engineering/MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)。

2026-11-10 完整桌面入口验收在启动前发现隔离阻塞：一次性开发入口的桌面客户端、Runtime 启动器和 Java 原生准入均固定 `127.0.0.1:18765`，没有已支持的新临时端点；新 Profile 只隔离数据。按交接要求保留旧端口 UNKNOWN，不重查、不启动或接触未知服务，也未准备 Profile、运行 EXE／Java／浏览器或操作数据库。现有新包及 Desktop／Runtime 哈希与交付回执一致。要继续，需用户确认旧 Workspace 正常退出且 18765 可独占，并明确允许一次新合成工作区在固定端点运行及应用自身准入检查；否则保持停止，不能靠绕过配置／准入解决。此为静态能力阻塞，不是新审批拒绝；没有待执行或结果不明动作，本轮未持重运行锁。完整网页／Runtime 联动、网页保存重开等仍未验收。[精确证据、边界及最小解阻步骤](engineering/MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)。

2026-11-10 WebView2 默认 InPrivate Profile 修正已提交 `64b33b2` 并交付 `artifacts/PersonalAiWorkspace-webview-default-profile-20261110.zip`：933 文件、实际解压与嵌入来源通过，ZIP SHA256 `BD29D41EED4F83C8D14FF5C539C0F64323918D536F7BAC05CE109D4A057AFEA9`。这是已有原生/WPF新合成对照支持的最小修正，未改安全条件；前端和 Runtime 字节不变，旧包、六份用户文档、main 保留，无推送。构建锁已释放，18765 保持 UNKNOWN。完整应用正常页面＋Runtime 联动、默认入口、维护迁移及发布仍待验收；无需先请求 EFS/ACL/沙箱变更，不继续添加假设记忆功能。[完整证据与接续](engineering/MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)。

2026-11-10 交付判断：显式记忆核心能力及已确认的编辑／查找缺口已实现，当前优先处理正常网页启动。参考 WebView2 上游命名 Profile 延迟报告后，两次全新合成对照取得新证据：省略自定义 ProfileName，原生控制器约 236 ms 创建；实际 WPF／本地页面／Bridge 在约 12.48 s 就绪，启动与退出清理通过。已将同一最小修正应用到源码，保留 InPrivate、原生目录准入及超时，不改 EFS/ACL/沙箱。WPF 为不启动 Runtime 的隔离宿主，不代表完整 EXE/Java 或保存重开验收；首次原生对照走过 AssistantApp 普通启动，不能声称零 Runtime 启动。两次 Job 收尾、共享锁已释放；18765 保持 UNKNOWN，没有替代诊断查询。准备一份新开发包，旧包／main／六份用户文档不变。[证据、边界与下一步](engineering/MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)。

2026-11-10 记忆管理的相关查找已完成两个实际入口并统一交付：原生 `b5a5a9e`、网页 `0d7955a`，可从分散关键词进入保留草稿对照、同 ID 修订、归档／恢复。新包 `artifacts/PersonalAiWorkspace-memory-management-recall-20261110.zip` 含 933 文件，实际解压和嵌入来源通过；Runtime、旧包、main、六份用户文档保持不变，无推送。共享锁已释放；本轮未启动服务，最终 18765 只读查询报错，状态记为 UNKNOWN 并停止，不换路线重试。没有重跑 WebView2 或保存重开；正常浏览器、默认入口、维护迁移及发布仍未验收。两个完整入口和一次统一交付后接续新对话，不凑第三项功能。[交付、验证、限制与接续](engineering/MEMORY-MANAGEMENT-RECALL-FLOWS.md)。

2026-11-10 正常网页记忆管理也已完成相关查找至预览、保留草稿修订、归档／恢复的流程；复用现有 Runtime 词法检索，仅传元数据，保持会话授权、最多 20 条和主动读取正文。Bridge 2/2、TypeScript 及两条受影响组件流程通过；一处测试断言修正后只复跑失败项。原生模块已提交 `b5a5a9e`，统一包准备中。没有启动数据库、模型或浏览器，正常 WebView2 仍未验收。[完整记录](engineering/MEMORY-MANAGEMENT-RECALL-FLOWS.md)。

2026-11-10 原生记忆管理已接入既有相关检索：分散关键词可以找到使用中记录，并保留草稿进入对照、同 ID 修订、归档与恢复；归档查找仍用原文匹配，无模型或自动选用。一个必要合成流程 1/1、编译零警告错误；没有重跑保存重开或 WebView2。正常网页入口的同一流程接续中，统一新包待交付。[问题、实现与证据](engineering/MEMORY-MANAGEMENT-RECALL-FLOWS.md)。

2026-11-10 三项草稿恢复流程已完成并统一交付：`1f9a62b` 原生记录不存在时保留草稿另存、`af1d295` 网页同一流程、`0c9b2fb` 网页版本冲突对照合并。必要检查分别 2/2、2/2、3/3 通过。新包 `artifacts/PersonalAiWorkspace-memory-draft-recovery-20261110.zip` 含 933 文件、实际解压与嵌入来源核对通过；前端已重建，Runtime 未变，旧包保留。main、六份用户文档哈希未变，无推送；18765 未监听、共享锁已释放。没有重跑真实 EXE／数据库保存重开／WebView2，正常浏览器、默认入口、维护迁移与正式发布仍未验收。本会话三项结束，下一会话先读[完整接续、证据与边界](engineering/MEMORY-DRAFT-RECOVERY-FLOWS.md)。

2026-11-10 网页 Memory 版本冲突已可保留草稿、对照当前内容并手工合并，确认后重新读取核对版本，再点保存更新同一记录；并发再改仍冲突，未知写入及归档不能借此解除保护。三项受影响组件检查 3/3、TypeScript 通过；没有重跑实际浏览器或持久化。三个使用流程已完成，准备一份合并开发包。[证据与边界](engineering/MEMORY-DRAFT-RECOVERY-FLOWS.md)。

2026-11-10 网页 Memory 同样支持记录不存在后保留草稿另存，确认转换与实际保存分开；未知写入仍拦截，重置会话清除待确认操作。仅两项组件检查 2/2 和 TypeScript 通过，没有实际 WebView2 验收；原生流程已提交 `1f9a62b`。下一项补齐网页版本冲突后的对照合并，随后统一交付。[报告](engineering/MEMORY-DRAFT-RECOVERY-FLOWS.md)。

2026-11-10 原生 Memory 已补全记录不存在后的草稿另存：保留类型、标题、正文，经明确确认改为新草稿，再点 Save 才创建新 ID；不恢复被删原记录，未知写入后即使读到不存在也不开放重试。两项受影响合成检查 2/2、编译零警告错误；没有运行真实数据库、浏览器或保存重开。旧包保留，准备统一交付；接着补齐正常网页编辑器的同一实际缺口。[流程与证据](engineering/MEMORY-DRAFT-RECOVERY-FLOWS.md)。

2026-11-10 原生 Memory 三条日常编辑流程已完成并一次性交付：`febc43e` 保留新草稿修订已有记录、`f02390b` 版本冲突对照合并、`10a9c8c` 主动查重（使用中／已归档各最多 20 条，明确不完整范围）。必要检查分别 1/1、2/2、1/1 通过，修正最小窗口阅读空间。新包 `artifacts/PersonalAiWorkspace-native-memory-edit-flows-20261110.zip` 来源 `10a9c8c`，933 文件及实际嵌入来源通过；旧包全保留。没有重跑真实保存／重开或 WebView2；18765 未监听、共享锁已释放，main 和六份用户文档不变，无推送。三项完成，下一会话及精确未验收边界见[交接报告](engineering/NATIVE-MEMORY-EDIT-FLOWS.md)。

2026-11-10 原生 Memory 冲突处理可保留草稿继续编辑：点击“对照最新”，合并需要保留的内容，确认采用已核对版本，再明确 Save；并发再次修改仍阻止覆盖。未知写入不会因查看内容而解除保护。两项受影响检查 2/2 通过，编译零警告错误；仍未更新开发包或重跑实际保存重开。[编辑流程](engineering/NATIVE-MEMORY-EDIT-FLOWS.md)。

2026-11-10 原生 Memory 新增“保留草稿并对照”：可读取已有记忆，明确采用为修订目标，再 Save 更新同一条记录，避免为核对旧内容而丢掉草稿。采用前目标若已变化，会展示最新内容要求重新核对；不自动保存。新增一项完整操作检查 1/1 通过，编译零警告错误；没有实际数据库／浏览器／模型运行，旧包未变。[流程与边界](engineering/NATIVE-MEMORY-EDIT-FLOWS.md)。

2026-11-10 本轮三项已完成并交付：`547e1d4` 首个生命周期修复包、`6e77ea8` 原生 Memory 写入中关闭保护、`eeeaeb4` 保存回执与草稿／版本精确匹配。最终新包 `artifacts/PersonalAiWorkspace-native-memory-guarded-20261110.zip` 来源 `eeeaeb4`，933 文件、实际解压／嵌入来源核对通过；两个旧包均保留。必要检查分别 2/2、3/3 通过，编译无警告错误；本轮没有启动产品 EXE／Profile／Runtime／模型，也没有重复保存重开。18765 未监听、自有构建生命周期和共享锁已结束；main、六份用户文档保留，无推送。WebView2 正常启动仍未解决，历史 `cff8015` 实际原生记忆验收与新包包装验证分开。三任务止于此，后续接续及精确边界见[完整交接](engineering/DESKTOP-FALLBACK-DELIVERY-REPORT.md)。

2026-11-10 Memory 保存回执现在绑定本次提交：已复现合法结构但不同标题／正文的响应被接受并清空原生草稿；现校验类型、标题、正文及精确版本增量，异常回执保留草稿并进入结果待确认、防止重复写入。三项聚焦检查 3/3 通过，编译零警告错误，没有真实存储／浏览器／模型运行。三项任务已完成，正在把本轮两项 Memory 修复整理入另一个新包；旧包均保留。[交接报告](engineering/DESKTOP-FALLBACK-DELIVERY-REPORT.md)。

2026-11-10 原生 Memory 写入中关闭保护：旧行为已用现有 STA 检查复现，会关闭窗口并取消保存请求；现保存／归档／恢复／删除操作结束前保留窗口并提示等待结果，托盘退出／切换已有关闭判断自然承接。两项受影响检查 2/2 通过，增量编译零警告错误；列表读取仍可关闭取消，结果不明的防重复写入保留。没有启动真实 EXE、数据库、浏览器或模型；已交付 `2bface5` 包暂未包含本项源码修复。[报告](engineering/DESKTOP-FALLBACK-DELIVERY-REPORT.md)。

2026-11-10 新开发包已交付两项 WebView2 失败后的生命周期／缩放修复：产品源 `2bface5`，`artifacts/PersonalAiWorkspace-webview-lifecycle-fixed-20261110.zip`。仅重编译桌面端，933 个文件、实际 ZIP 解压及嵌入来源核对通过；Runtime／前端字节不变，旧验收包保留。本次没有重跑 EXE、原生记忆保存或浏览器启动；正常 WebView2 根因与未验收边界不变。接着处理已确认的原生记忆保存中关闭会取消请求的问题。[交付与后续报告](engineering/DESKTOP-FALLBACK-DELIVERY-REPORT.md)。

2026-11-10 第三模块修复失败页缩放异常：已复现启动失败后点击“放大”访问已释放 Browser 抛出 `ObjectDisposedException`；现缩放控件仅在浏览器就绪时启用，失败/关闭时禁用，并拦截排队中的点击。原生记忆入口及原始错误提示保留。复用一项 STA 检查，修复前失败、修复后 1/1 通过，编译无警告/错误；没有新增浏览器启动、Profile、Runtime 或模型调用。三模块完成，下一会话优先把两项真实修复交付到新开发包；现有 `cff8015` 包未更新、WebView2 根因仍未知。[证据与明确打包接续](engineering/MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)。

2026-11-10 WebView2 生命周期修复 `50a4614`：避免启动失败释放控件后退出再次访问它；明确记录清理不可用，编译及一项无浏览器/无 Profile 的 STA 检查通过。随后仅一次新合成 Profile 原生控制器对照也在 25 秒界限内未完成（Runtime 157.0.4341.42），单换 WPF 初始化调用不足以解决。新缓存目录 EFS/私有 ACL 是已观察条件，尚非根因；未改安全设置。实验 EXE 退出 0，Job 关闭前仍有 3 个自有进程，由 Job 清理，不能称全树自然退出；端口、共享锁已释放。临时源码和普通构建输出已恢复并核对，旧交付包未更新。正常页面仍未验收，已通过的完整 EXE 原生记忆闭环保留。[证据、精确限制与接续](engineering/MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)。

2026-11-10 [完整 EXE 的原生记忆备用入口](engineering/MEMORY-EXE-ENTRY-REPORT.md)已通过：实际产品从 WebView2 自然超时失败页打开 Memory，明确保存、托盘退出后，由独立 EXE/Java 重开读回同一记录和 Revision 1。`cff8015` 增加“仅本次打开”参数，保留默认工作区记录；编译及单条参数校验通过。重开后的整库哈希变化已做离线只读调查：源码明确每次启动重建派生索引，源记忆及显示元数据一致；缺少前态数据库副本，不能逐字节归因或声称整库零写入。新包 `artifacts/PersonalAiWorkspace-native-entry-verified-20261110.zip` 仅更新验收说明，932 个程序/资产文件与实际验收包字节不变，933 清单文件及 ZIP 934 条目已核对。两次桌面正常退出、Job 释放前为 0、端口关闭，互斥锁已释放；恢复时可选 WMI 复查受限已记录。WebView2 正常页面、无参数默认入口、维护迁移及正式发布仍未验收；main、六份用户文档与旧包保留，无推送。

2026-11-10 [SQLite 长路径最终本地交付](engineering/KNOWLEDGE-LONG-PATH-REPORT.md)：历史、源库/索引、Conversation 三模块分别提交 `9fee490` / `baeaead` / `9498a8d`。新开发包 `artifacts/PersonalAiWorkspace-sqlite-long-path-20261110.zip` 已构建，933 文件、24 个改动类及实际内嵌来源核对通过；旧包、原失败样本、main、六份用户文档保留，无推送。超过 1,000 字符路径及普通路径均通过必要的真实存储/独立重开验证；完整 EXE、WebView2、维护迁移和正式发布未验收。自有进程退出、锁释放，本轮三模块完成，下一会话从明确剩余缺口接续，不重复已通过场景。

2026-11-10 [Conversation 共用数据库长路径](engineering/KNOWLEDGE-LONG-PATH-REPORT.md)：确认 Memory 可开而旧 Conversation 不能开的同库问题后，复用相同 SQLite URL。236 / 1,074 字符路径的会话、轮次、消息及记忆已由独立 JVM 读回；分页与安全限制保留，工具初次页大小错误证据保留，没有重复保存。三个存储模块已完成必要验证；本地交付收尾中，完整 EXE / WebView2 与维护迁移边界不变。

2026-11-10 [Knowledge 源库与索引长路径接续](engineering/KNOWLEDGE-LONG-PATH-REPORT.md)：旧源库和旧索引分别实证失败后，四处文件连接共用已验证 URL。短路径和超过 1,000 字符路径均完成真实导入、范围检索及独立 JVM 重开；原文、版本及命中一致，重开直接加载已有索引，源库字节未变。无模型／浏览器，未更新旧包，Conversation 同类连接待核对。

2026-11-10 [Knowledge 历史长路径修复](engineering/KNOWLEDGE-LONG-PATH-REPORT.md)：复用 Memory 已有 SQLite Windows 长路径连接，保持目录、schema、记录和安全校验。旧代码在新合成 1,100 字符路径失败，修复后实际保存及独立 JVM 重开通过；249 字符普通路径、第二写入者拒绝和 Memory 共用连接最小验证通过。原失败样本保留，无模型或浏览器，锁已释放。源库、索引及 Conversation 的同类连接待逐项核对；旧包未更新，完整 EXE/WebView2 验收边界不变。

2026-11-10 [原生 Memory 真实持久化闭环](engineering/MEMORY-NATIVE-PERSISTENCE-REPORT.md)已通过：最终包 `0ad9665` 的实际原生窗口在新合成 Profile 内明确保存，经正常关闭后由独立桌面／Java 进程读取同一 SQLite 记录，重开写入 0、未保存修改未保留；两组服务正常退出、端口关闭、共享锁释放。使用真实 HTTP / Store，没有模型、WebView2 或既有用户数据。首次长合成路径在 Knowledge 历史库初始化失败，经只读对照证实 SQLite 路径限制；缩短全新验收子目录后闭环通过，产品长路径兼容性仍待处理。完整 AssistantApp／失败页入口与 WebView2 保存重开仍未通过本轮验收，不重复已通过的原生样本或旧测试。

2026-11-10 [原生记忆本地交付](engineering/MEMORY-NATIVE-DELIVERY-REPORT.md)：最终本地 ZIP 已包含备用入口、写入结果不明保护及保存前长度／有效性提示，来源 `0ad9665`，旧包与完整 Git 回滚保留。初版分发合同 7/7、编辑增量必要编译与一条受影响回归通过；最终 ZIP 933 文件及换路径后的实际内嵌来源独立核对通过，未重复篡改矩阵。首轮 NuGet 缓存／网络失败已解决，原日志保留。共享短运行锁已释放，六份用户文档和 main 未变。未启动桌面／JVM／模型，原 WebView2 保存／重开未通过，真实原生记忆保存／独立重开尚未执行，仍为 Development-only。

2026-11-10 [原生 Memory 备用入口](engineering/MEMORY-NATIVE-FALLBACK-REPORT.md)已提供 WebView2 失败页到既有记忆窗口的明确入口；[原生写入结果不明保护](engineering/MEMORY-NATIVE-OUTCOME-REPORT.md)防止已提交但响应丢失后重复保存，保留草稿与原有确认，允许只读核对。两个小阶段分别完成必要增量编译与单条 STA 用例，后一项先复现旧版失败；没有重跑前端回归或启动真实桌面／JVM／模型。共享短运行锁已采用并释放。分发包未更新，原 WebView2 保存／关闭／独立重开闭环仍未通过，继续为 Development-only。

2026-11-10 [Memory 编辑状态通知竞态](engineering/MEMORY-EDITOR-STATE-RACE-REPORT.md)：保存成功后，旧的未保存状态通知失败不再给当前记录留下过期警告；当前通知失败仍明确提示。保留原有 native 通知顺序、确认与未知写入保护，不重试或自动写入。单条回归先复现失败，修复后 MemoryPage 24/24 和 TypeScript 通过；未更新分发包，真实桌面保存／关闭／重开仍未通过。

2026-11-09 [Memory 草稿主动查重](engineering/MEMORY-DRAFT-DUPLICATE-REVIEW-REPORT.md)：手工及知识回答原文草稿可不调用模型、主动核对已有使用中记忆；复用现有有界查重，明确显示重复项、失败及检查不完整，打开已有记录仍需丢弃确认，保存仍由用户明确执行。两个相关前端文件 35/35、TypeScript 通过；首轮 React key 冲突已修正并保留日志。未更新分发包，未通过完整 Memory 桌面保存／重开。另一次唯一隔离 startup 已捕获 GPU／渲染／浏览器进程退出，宿主及已记录 browser PID 均退出，18765 关闭；未放宽清理或安全设置，仍为 Development-only。

2026-11-09 [知识回答转记忆草稿](engineering/KNOWLEDGE-MEMORY-DRAFT-REPORT.md)：当前成功回答及历史回答可明确进入既有 Memory 人工审阅，保留原文及助手未核实归属；不自动调用模型或保存，引用不持久关联。Knowledge 12/12、Memory 19/19、Conversation 19/19 及前端类型／构建通过，首轮夹具失败保留。仅本地源码提交，未重打预览包；完整桌面、个人恢复、PG 与模型质量未随本轮验收，仍为 Development-only。

2026-11-09 [完整会话导出](engineering/CONVERSATION-EXPORT-REPORT.md)补齐全部持久轮次的明文 Markdown 保存，含原文、终态和记忆引用元数据，不含未发送草稿。桌面聚焦 38/38、共享写入器 1/1、前端 19/19；真实 WPF／原生保存／HTTP／SQLite 的 23 轮导出及取消通过，0 模型调用。新建合成 Profile 在当前宿主的身份校验未通过；复用有来源证明的合成工作区完成验证，未放宽安全规则。新包、失败证据和未验收范围见报告，仍为 Development-only。

2026-11-09 [PDF 文字层导入](engineering/KNOWLEDGE-PDF-IMPORT-REPORT.md)已进入新统一预览包：受限子进程提取、原生预览确认、限定检索／原文定位及独立应用重开一次通过，取消与不支持文件不入库。PdfPig 0.1.16／Apache-2.0 及完整附录声明随包；PDF／Word 10/10、前端 5/5、实际新旧预览 EXE 启动和新包 933 文件核对通过。0 模型调用，六份用户文档、最近工作区和原服务保持；本地提交，未推送。无 OCR；复杂 PDF 语义完整性、模型质量、个人恢复、PG 桌面、跨机器与正式发布仍未验收。下方“阅读新包未启动”的记录已由本轮补验，只保留原时点范围。

2026-11-09 [回答排版阅读](engineering/ANSWER-MARKDOWN-READING-REPORT.md)已进入新的本地开发预览包：助手／摘要、会话及知识回答支持 Markdown 与原文切换，原记录复制／记忆草稿／导出保持。复用 MIT React Markdown／GFM；34/34 聚焦检查、三页面浅深色隔离 WPF WebView 及最终 ZIP 923 文件完整性通过。仅本地提交，未触及个人数据或 KAG。新包未复验完整 AssistantApp／Runtime 启动，不能继承下方旧包的完整流程 PASS；跨机器、个人恢复、PG 桌面和模型质量边界保持。

2026-11-09 [统一 Windows 开发包](engineering/WORKSPACE-CURRENT-BUNDLE-REPORT.md)已包含 Word 导入、版本对比、限定检索、历史问答与笔记导出。修正旧打包 helper 常量、固定包内 .NET 10.0.14 并补齐许可材料；最终 ZIP 7/7 合同通过，两条桌面流程共四次独立应用启动及实际 EXE 无参数启动通过，0 模型调用。仅本地提交，`main` 保持 `f5266bf`；六份用户文档、最近工作区记录与既有服务未变。仍为 Development-only；附最终包、精确哈希、失败记录和未验证范围。

2026-11-09 [文档版本对比](engineering/KNOWLEDGE-VERSION-COMPARISON-REPORT.md)接续 Word 导入：选择两个已发布版本，完整读取后显示逐行增删，并跳回对应版本原文。固定复用 BSD jsdiff 9.0.0；前端必要检查 9/9，实际合成 Word 两版本及独立应用重开通过，0 模型调用。成果沿同一链位于 `codex/knowledge-version-compare`，本地 `main` 已按此前明确授权更新到 `f5266bf`，没有进一步更新或推送；下列拒绝记录保留原时点经过。仍为 Development-only。

2026-11-09 [Word 正文摘录导入](engineering/KNOWLEDGE-DOCX-IMPORT-REPORT.md)完成并于重连后提交 `6910ba8`：选择 DOCX → 原生预览确认 → TXT 摘录入库 → 检索／精确原文 → 独立应用重开。随后复现并修正公式／字体符号被跳过却仍成功的问题，改为明确拒绝，普通 Unicode 字符保留。初轮桌面 29/29、前端 14/14；修复后相关 6/6，完整桌面再次两次独立启动通过，失败记录保留。复用 MIT Open XML SDK 3.5.1，不改 Runtime／schema，0 模型调用。六份用户文档与既有服务未动，成果在 `codex/knowledge-docx-import`，基于 `main @ f5266bf`，未推送，仍为 Development-only。

2026-11-09 [分支核对与完整桌面流程](engineering/WORKSPACE-INTEGRATED-DESKTOP-REPORT.md)：全部 22 个本地分支已属于同一提交链，统一版本位于 `codex/knowledge-answer-export`。普通 Runtime／完整 AssistantApp 的限定检索、历史问答、精确引用、原生保存与取消、独立应用重启两轮通过，0 模型调用。自动审批拒绝更新默认分支，`main` 仍为 `184aafd`，待明确授权；没有推送，回滚标签保留。此前特殊 Runtime 因已清理的测试模型目录缺失而启动失败，日志保留，最终改用默认构建并移除临时诊断。六份用户文档、最近工作区选择和既有服务保持不变；新问答、所有页面、PG 桌面、个人恢复和正式发布未随本轮验收，仍为 Development-only。下列记录保留各自时点范围。

2026-11-09 [知识问答笔记导出](engineering/KNOWLEDGE-ANSWER-EXPORT-REPORT.md)新增从成功回答／历史记录导出 Markdown：保留原问题、范围、回答和精确旧版本引用原文，草稿不混入，缺失来源拒绝，原生保存仅创建新文件。复用既有版本读取、原生保存和 .NET 文件接口，无新依赖、schema 或模型调用。前端 14/14、Desktop 25/25、Unicode 边界补验 2/2 及构建通过；完整 Windows 保存对话框、实际数据库组合和发布包未验收。六份用户文档原样保留，仅本地开发；不增加回答质量或个人备份能力结论。

2026-11-09 [版本中断复核与三道未见题](engineering/KNOWLEDGE-ANSWER-FOLLOWUP-REPORT.md)完成：确认旧客户端拒绝 v3 后由手工探针退出，既有 `2dc240f` 兼容修复有效；本轮无需再次修改产品。当前版本新题 3/3 满足字面、语言及条件要求，三次首次 QUEUED 收据正常，关闭模型后独立进程历史一致。仅 4 次模型调用（准入＋三题），没有重试；未与旧版比较，不宣称更优，原 RFC 题未重测。用户文档与服务未动，已清理自有模型副本；仅本地开发，Development-only。

2026-11-09 知识回答已接入固定 JSON Schema，提示 v3 明确回答语言、名称／数值／标点保真及否定条件；Desktop 与合成快照兼容 v2／v3。Java 原 9 项中的 8 项通过、旧测试夹具修正后的受影响项 1/1，混合版本快照 1/1，Desktop 22/22；构建通过。五份冻结新合成资料中旧版 5/5 满足，新版 4 题满足，另 1 题因本轮发现的版本兼容问题中断并保留，未重发；模型关闭后全部历史核对一致。**未证明质量提升，未重测或宣称修好原 RFC 单题。** 六份用户文档及既有服务未动，模型副本清理；仅本地开发，Development-only。[根因、成熟方案、真实结果与回滚边界](engineering/KNOWLEDGE-ANSWER-FIDELITY-REPORT.md)。

2026-11-09 指定文档范围已完成真实完整桌面的检索、引用和模型关闭后历史重启闭环：明确限定资料无匹配时不回退，切换阅读文档与刷新保留范围和草稿；两条实际模型回答在独立 AssistantApp／JVM 重启后恢复原问题、范围和引用，删除历史保留来源。**模型质量未全部通过**：两道中文问题第一题正确，第二题仅返回 `invalid`，未满足 `.invalid` 精确写法和中文表达，原失败记录保留，未重问刷结果。仅扩展既有验收工具；6 份用户文档及既有服务未动，自有进程、端口与模型副本已清理。PG 整包、长资料质量、个人恢复和正式发布仍未完成；仅本地提交，Development-only。[逐轮证据与质量局限](engineering/KNOWLEDGE-DOCUMENT-SCOPE-DESKTOP-REPORT.md)。以下记录保留各自时点结论。

2026-11-09 指定文档检索与问答已交付：Knowledge 可明确限定一份当前源版本，筛选在结果截取前执行，引用与历史保留该范围；更新／归档后拒绝旧选择，不自动扩大到全库，草稿保持。复用现有检索／Native 授权／证据预算；历史 schema 1→2 与旧合成归档兼容已验证。Java 九项通过＋修正断言的单项补验通过，独占 PG 1/1，Native 20/20，前端 13/13 及最终构建通过。未调用真实模型或访问个人资料，自有资源退出，六份用户文档未变。完整桌面、PG 桌面组合和真实质量未随本轮验收；仅本地提交，仍为 Development-only。[交付、逐轮证据与回滚边界](engineering/KNOWLEDGE-DOCUMENT-SCOPE-REPORT.md)。

2026-11-09 跨会话查找隔离桌面验收通过：真实 WPF／WebView／Bridge／HTTP／SQLite 完成分页后第 11 轮定位、归档筛选、同会话草稿保留、跨会话取消／确认与窗口重开；6 次只读搜索、0 变更请求、0 模型调用。专用 PostgreSQL 17.11 应用角色的搜索／分页／Store 重开也通过，两条综合场景各 1/1、零跳过。仅扩展现有验收工具，产品逻辑未改；自有进程及端口退出，临时 PG 凭据清理，六份用户文档哈希未变。完整 AssistantApp、PG 桌面组合、最大容量仍未验收；仅本地提交，仍为 Development-only。[实测、截图与限制](engineering/CONVERSATION-SEARCH-DESKTOP-REPORT.md)。以下记录保留各自时点结论。

2026-11-09 跨会话原文查找已交付：对话页可查找当前工作区的终态消息，按使用中／归档筛选，查看字面高亮并打开对应会话和轮次；同会话保留草稿，跨会话先确认，离页／session 重置清除结果。复用现有 Store／原生授权／分页，不新增搜索服务、schema、模型调用或持久副本。原生 HTTP／SQLite 3/3、桌面 Bridge 31/31、前端 16/16 及构建通过；完整真实桌面、PG、最大容量性能未随本轮验收。已评估 Open WebUI／LibreChat；恢复入口仍缺来源与激活合同，本轮转向直接用户能力，不继续扩张备份基础设施。六份用户文档与既有服务保持不变，仅本地提交，仍为 Development-only。[实现、取舍、逐轮证据及限制](engineering/CONVERSATION-MESSAGE-SEARCH-REPORT.md)。

2026-11-09 合成加密恢复的隔离桌面闭环通过：恢复来源及五条终态问答后，真实 WPF／WebView／Bridge／HTTP／SQLite 可阅读历史、精确打开旧版本引用并提示已删除来源；第一轮 JVM 完全退出后，第二轮独立 JVM 重开同一数据通过，两轮各 1/1、零跳过。未启动模型、未重放任务、桌面写请求为 0；自有进程和端口退出，最近工作区选择与六份未跟踪文档未变。本轮只扩展测试工具，正式用户入口、原生登记／Profile 激活、完整 AssistantApp 恢复及 PG 仍未完成；不能视作个人数据已备份。仅本地提交，仍为 Development-only。[实测、截图与边界](engineering/KNOWLEDGE-ENCRYPTED-RECOVERY-DESKTOP-REPORT.md)。以下记录保留各自时点结论。

2026-11-09 合成联合快照已增加认证加密：复用既有 Bouncy Castle OpenPGP 流，来源和终态历史直接写密文，完整认证后再解包／重建／发布新候选，恢复保留原问题、答案和旧版本引用。原生隔离验证 16/16、零跳过；错口令、重算摘要后的篡改、非法 KDF、越界内容及中断均拒绝，旧来源备份和旧容器流合同通过相关回归。没有个人数据操作、用户按钮、PG 或 Profile 激活；恢复库仍为私有明文，完整新 JVM／桌面恢复尚未验收。六份未跟踪文档未改，仅本地提交，仍为 Development-only。[实现与实测边界](engineering/SYNTHETIC-KNOWLEDGE-HISTORY-ENCRYPTION-REPORT.md)。以下较早记录保留各自时点结论。

2026-11-09 合成联合快照原型：全新 SQLite 合成资料可将 Knowledge 全部保留源与终态问答在双库停写窗口内导出，私有暂存校验后发布新候选数据目录，重开保留原问题、状态、时间、答案、旧版本精确引用及来源不可用状态。新闭环＋旧 v1 备份兼容最终 10/10，历史存储另轮 4/4；并发写排斥、缺件／损坏拒绝、发布目标占用保护通过。旧备份格式及用户入口未改，原型为明文合成限定，不提供个人／PG／跨 Profile 恢复或激活。六份未跟踪文档未改；仅本地提交，仍为 Development-only。[实现、逐轮结果和限制](engineering/SYNTHETIC-KNOWLEDGE-HISTORY-SNAPSHOT-REPORT.md)。

2026-11-09 问答历史完整重启验收：全新合成 Profile 的三次 AssistantApp／WebView／JVM 启动通过，第三次启动前已关闭本轮模型及子进程。产品历史页恢复两条原问题、检索词、答案和精确源引用，0 次重新问答；界面删除一条历史后两份源文档仍保留。隔离 Qwen3.5 4B 本轮实际 3 次调用（准入＋两题），不代表通用质量；自有进程、端口和模型副本已清理，原有六份未跟踪文档未改。[实测与证据](engineering/KNOWLEDGE-ANSWER-HISTORY-RESTART-REPORT.md)及[备份边界评估](engineering/KNOWLEDGE-ANSWER-HISTORY-BACKUP-ASSESSMENT.md)。历史备份尚未实现，现有格式仍不包含历史；个人恢复未开放。仅本地提交，仍为 Development-only。下列结论保留各自时点。

2026-11-09 问答历史增量：新知识问答会先保存当前 Profile 的本地执行记录，完成结果与精确引用提交后才发布成功；新增历史分页、重新打开引用和显式删除。重开时未完成记录标为中断，不自动重新调用模型；保持原生与会话授权。原生隔离测试 17/17，最后边界修正后历史所属 4/4；桌面 25/25，随后历史专项 3/3；前端 7/7。现有备份不含该本地记录，旧版已丢失的任务无法补回；本轮未做完整 AssistantApp 进程重启或真实模型质量验收。仅本地开发，仍为 Development-only；[实现取舍、实测和下一步](engineering/KNOWLEDGE-ANSWER-HISTORY-REPORT.md)。下列报告保留各自时点结论。

2026-11-09 完整重启及真实知识回答验收：完整 AssistantApp／WebView／DevelopmentRuntimeOwner／SQLite 连续三次启动通过，重启后两份公开网页资料的元数据、源版本、全文哈希和检索／引用目标保持一致。隔离 Ollama 0.40.1／Qwen3.5 4B 的两个英文问题与精确引用通过；实际另有一次模型准入调用。旧知识问答任务重启后为 TaskNotFound，历史回答没有持久恢复。修复构建脚本误读 Git 中文文件名的问题，原有六份未跟踪文件保持不变；所有自有进程、端口及临时模型副本已清理。正式安装包、PostgreSQL、中文长资料及一般模型质量仍未验收；[实际证据与限制](engineering/WEB-KNOWLEDGE-RESTART-AND-MODEL-REPORT.md)。仅本地提交，仍为 Development-only。下列较早记录保留各自时点的验证范围。

2026-11-09 网页知识真实桌面验证：实际 WPF／WebView／原生确认／生产公共 Fetch／HTTP／SQLite 对 Example Domain 与 RFC 2606 HTML 的获取、明确保存、检索、原文高亮及窗口重开通过。成功轮次仅 2 次获取、2 次保存、3 次本地搜索，模型生成与 probe 调用为 0；自有进程和端口已退出。扩展既有 opt-in 验收工具，修正三个工具断言／白名单问题，产品实现未变；默认无公网核心回归 2/2 通过。不是完整 AssistantApp／正式桌面包、JVM 重启、真实模型质量或 PostgreSQL 验收；[实际证据、失败记录和边界](engineering/WEB-KNOWLEDGE-LIVE-ACCEPTANCE.md)。仍为 Development-only，仅本地提交。

2026-11-09 网页资料闭环：Knowledge 可输入公共 HTTPS 网址，经既有原生逐次确认获取、预览后明确保存；Runtime 将有界文本、来源网址、时间和截断说明存入既有 Knowledge，复用重开、检索、问答引用与独立备份恢复。原生端绑定一次保存请求，未知只读对账，不重发保存或访问网页。直接复用现有 jsoup 1.23.2／安全 Fetch／Knowledge，无新依赖或 schema。隔离 Java 2/2、.NET 4/4、前端 11/11 及编译构建通过；真实网站、真实模型、完整桌面／WebView 和 PostgreSQL 路径未验收。仅本地提交，W1 整体未关闭，仍为 Development-only；[交付、复用评估、首次失败和限制](engineering/WEB-KNOWLEDGE-CAPTURE-REPORT.md)。

2026-11-09 来源阅读增量：Knowledge 引用和检索命中可在指定源版本中精确高亮，支持从本节开头阅读、上下文翻页及返回引用／命中位置；旧版本有明确提示，读取失败不丢失返回记录，切换版本／会话或离页会清除定位。复用既有文本渲染和有界预览接口，未增加依赖、后端合同或模型调用。两个现有前端测试文件 9/9 通过（新增 2 项），TypeScript／构建通过；真实模型质量、受信桌面包和实际 WebView 交互未随本功能验收。仅本地提交，仍为 Development-only；[复用评估、证据和回滚说明](engineering/KNOWLEDGE-SOURCE-READER-REPORT.md)。

2026-11-09 后续增量完成：Knowledge 首个长中文命中整体超预算时，可从冻结原文选择覆盖检索词的连续句子及相邻上下文，引用按实际摘录更新偏移与行号；完整片段仍按排名前缀打包，不能容纳的窗口继续拒绝。复用 JDK 分句及现有词法／预算／引用组件，参考 LlamaIndex 和 Lucene，未加依赖。Java 原生隔离场景 7/7、.NET Knowledge 16/16、前端 3/3 及构建通过；未验证真实模型摘录质量、完整桌面包或 PostgreSQL 流程。提示契约升级 v2，须配套 Runtime／Desktop。仅本地 Git，不推送、合并或部署；[交付、复用评估、证据与限制](engineering/KNOWLEDGE-BOUNDED-EXCERPTS-REPORT.md)。

2026-11-09 开发已按用户指示恢复，首个增量为 Knowledge 持续使用：修复同一桌面会话累计 64 个知识回答后永久 `QueueFull` 的本地授权限制。容量不足时只回收最早确认终态的授权；已验证 `TaskNotFound` 也撤销对应授权，活动／未知任务不因时间被回收，迟到结果须重新核对会话与授权实例。旧实现聚焦复现失败、修复后 16 项现有 Knowledge 所属测试通过（新增 3 项）；未调用模型、访问真实数据或部署。本轮仅本地 Git 收尾，不推送；[结果、复用取舍与保留限制](engineering/KNOWLEDGE-ANSWER-LIFECYCLE-REPORT.md)。记忆完成及其小样本边界仍有效，个人备份／恢复仍未开放；下列“暂停”等表述是原阶段历史状态。

2026-11-07 记忆系统收尾完成：当前 **Development-only 的显式记忆范围**已完成必要验收。真实 AssistantApp／WPF／WebView／原生 Runtime／SQLite／Ollama 0.40.1／Qwen3.5 4B 的生成、审核、保存、检索、会话引用、过期拒绝、清除、完整进程重启及归档／恢复／删除闭环通过；同版本独立 11 次质量小样本另行断言及语义复核通过。PostgreSQL Store 综合场景、最终合成桌面回归及必要安全／协议回归通过。全部测试使用合成数据，自有实例已退出；这不表示当前正在运行或 Ready，不代表正式发布或 Production GO。按用户要求完成 Git 收尾后暂停，不启动下一阶段。[完成报告、证据与剩余限制](engineering/MEMORY-SYSTEM-COMPLETION-REPORT.md)是当前结论；下列 WIP 和失败条目保留其历史时点。

2026-11-07 结构化候选与完整应用收尾（仍为 WIP）：已复用 Ollama 原生 JSON Schema，固定契约经原生候选任务发出；严格证据／语言校验及人工采用保存保持。原生 HTTP 4/4、.NET 38/38、独立真实模型 11 个小样本及单独语义复核通过。完整 AssistantApp 与真实模型组合仍在加载后准入处失败，未进入记忆保存；正在核对 Windows 控制台／OpenMP 兼容性，相关身份回归 5/5 通过。未提交／推送／合并；[最新进展与准确边界](engineering/MEMORY-STRUCTURED-OUTPUT-REPORT.md)优先于下列历史时点。

2026-11-07 最新真实模型复验（整体仍未通过）：KAG 完成并停止后执行了有界、单并发合成验收及针对性修正。候选的来源语言、摘录和范围约束有所改善，但最后完整尝试仍漏掉 evidence，被生产解析器拒绝；不能将此前成功轮次当作稳定质量。发现并修正模型把“清除显式引用”误说成“历史也已删除／不可用”的问题，当前提示单独 3 轮会话复验通过。全部自有模型实例已退出、端口关闭。未提交／推送／合并；完整应用与真实模型组合、候选生成可靠性仍待完成。见[逐轮结果、修正和限制](engineering/MEMORY-MODEL-RECHECK-REPORT.md)。下列条目保留各自历史时点。

2026-11-07 候选约束离线修正（WIP）：参考 Mem0 与 LangExtract 的来源语言、示例和原文定位实践，强化项目／时间／否定范围及逐字连续摘录；保持严格拒绝改写 evidence，增加有限范围的明显文字体系偏离拦截和清楚的拒绝说明。23 项离线回归、TypeScript／Vite 构建通过，未调用模型、未改服务。真实效果等待新的 KAG 协调窗口；保持未提交／推送／合并。见[修正、成熟实现评估与验证边界](engineering/MEMORY-CANDIDATE-GROUNDING-REPORT.md)。

2026-11-07 真实模型窗口结果（未通过）：KAG 安全检查点确认后，用自有隔离 Ollama 0.40.6／Qwen 3.5 4B 发出 4 个合成候选请求；第 4 项否定修订的 evidence 被模型改写，生产解析器正确拒绝，随后停止，未重试或保存。窗口已释放，自有进程退出、随机端口关闭；未操作现有 11434 端口程序。真实模型质量仍未验收，余下场景及完整应用组合待完成，继续保持 WIP，不提交／推送／合并。详见[模型实际结果与后续边界](engineering/MEMORY-SYSTEM-ACCEPTANCE-WIP.md#2026-11-07-协调窗口内的实际结果未通过窗口已释放)。

2026-11-07 桌面验收补充（仍为本地 WIP）：真实 WPF／WebView2／Native Bridge／HTTP／SQLite 通过候选查重、同 ID 修订保存、相关检索预览、显式选择、两轮连续引用、过期拒绝、清除、窗口重开和归档／恢复／删除。实际提供器请求也确认引用与清除范围；提供器为合成 fixture，没有真实模型调用。未修改生产逻辑或现有服务。真实模型质量及完整应用启动组合仍待协调验收，尚未达到整个记忆系统完成条件，继续不提交／推送／合并；见[验收证据、复用评估和具体协调需求](engineering/MEMORY-SYSTEM-ACCEPTANCE-WIP.md)。

2026-11-07 模块验收继续（本地 WIP）：相关检索增量完成不等于整个记忆系统完成。本轮以全新独占 PostgreSQL 17.11 实例补验真实 Store 的检索、版本失效、重开持久化及会话引用，综合场景通过，实例已停止、临时凭据已移除；未改生产逻辑。真实模型质量、真实桌面完整流程仍未验收，后续内容暂不提交／推送／合并。全模块验证后再推送、核对整理适合的分支，报告完成与限制后暂停 Workspace，不开启下一阶段。见[模块验收矩阵与 PostgreSQL 证据](engineering/MEMORY-SYSTEM-ACCEPTANCE-WIP.md)。

2026-11-07 记忆检索补充：原生选择器可按问题或关键词查找相关 ACTIVE 记忆，逐条预览并明确选择后用于本轮；无自动选用或写入。复用已有分词、事务、认证和选择器，保留精确版本检查。7 项 Java 场景（含正常桌面宿主原生准入、真实 Store 与 HTTP 的 3 项）、4 项 Native 自动化及必要构建通过。包装宿主的路径映射仍按原规则拒绝，未降低安全校验；PostgreSQL 此查询、真实模型质量和真实桌面手工流程未验收。个人备份／恢复仍关闭，见[功能、成熟项目复用与验证报告](engineering/MEMORY-RECALL-REPORT.md)。

核对日期：2026-10-28（Asia/Shanghai）。**Development-only；尚未 Production GO。**

2026-11-07 记忆修订补充：已采用的候选或手写新草稿可明确选择一条使用中的既有记忆，对照旧内容、采用修订后再保存，沿用原 ID 和精确版本，避免只能另建记录。不会自动找目标、合并或写入；变更／归档／缺失目标须重新选择，保存冲突与未知结果继续保留草稿并阻断重试。4 个合成前端场景及 TypeScript／构建通过；未改 Native／Runtime／schema，未访问真实数据或运行模型，也未发布／验收真实桌面。个人备份／恢复仍关闭，见[功能、验证和回退边界](engineering/MEMORY-DRAFT-REVISION-REPORT.md)。

2026-11-07 记忆使用补充：会话新增默认关闭的“在当前会话连续使用这组记忆”。用户明确勾选后，后续手动发送复用所选 ID／版本，每轮仍由 Runtime 校验及生成快照；更换选择、离开页面、切换会话或重载会清除连续使用，过期／未知结果阻断复用。4 个合成前端场景、4 个隔离 Native 场景及构建首次通过。现有模型 lease 只约束 Workspace 内部，尚未建立双方认可的共享资源协调窗口，本轮未调用模型、改动服务或接触真实数据；实际回答质量和真实桌面流程未验收。个人备份／恢复仍关闭，见[功能、协调前置与回退](engineering/CONVERSATION-MEMORY-RETENTION-REPORT.md)。

2026-11-07 候选审核补充：生成记忆候选后可主动检查已有使用中记忆的相同正文，最多核对 20 条匹配记录；重复或不完整结果须用户明确确认才能继续采用，不自动合并／保存。3 个合成前端场景及构建通过。虽有可用内存／显存，但尚未核定共享模型与 KAG 的隔离及 Workspace 准入，本轮未发真实推理请求，也未验收模型质量或真实桌面。个人备份／恢复仍关闭，见[范围、证据与回退](engineering/MEMORY-CANDIDATE-DUPLICATE-REVIEW-REPORT.md)。

2026-11-07 记忆形成补充：对话转草稿后可主动用当前本机模型生成最多一条可编辑候选，对照原文摘录审阅、采用，再明确点击保存；无候选或无效输出不写入。复用现有 Ask／Memory 链路，候选请求不消耗助手已有记忆选择。前端聚焦场景、隔离 Native 桥接及构建通过；实际模型提取质量和真实 Windows 流程未验收。无后台自动记忆，个人备份／恢复仍关闭，见[能力盘点、交付与验证边界](engineering/MEMORY-CANDIDATE-GENERATION-REPORT.md)。

2026-11-07 记忆透明性补充：对话历史可展开本轮记忆引用，并逐条按需读取。仅 ID／版本匹配且仍使用中的当前正文可显示；版本变化、归档和删除不冒充历史正文。查看不改变下一轮记忆选择或草稿，不增加自动记忆。一个合成 Bridge 场景及前端构建通过；尚未打入受信 Windows 包或验收真实读取链路。个人备份／恢复继续 BLOCKED，详见[交付与回退边界](engineering/CONVERSATION-MEMORY-REFERENCE-PREVIEW-REPORT.md)。

2026-11-07 记忆使用补充：“我的记忆”新增按需只读正文预览和已提交搜索词高亮，保留右侧未保存草稿，一次只读取／显示一条，并拒绝列表版本已变化的结果。一个合成 Bridge 聚焦用例及前端构建通过；仍为用户手动维护记忆，没有自动提取或注入。未打入受信 Windows 包或访问真实个人数据，个人恢复继续 BLOCKED。见[交付与使用范围](engineering/MEMORY-READ-PREVIEW-REPORT.md)。

2026-11-07 日常使用补充：会话历史新增按轮次直接定位及“回到最新”，复用原分页读取，保留未发送草稿与已选记忆，方便定位原文后使用既有记忆草稿流程。一个合成 Bridge 聚焦用例及前端构建通过；未打入受信 Windows 包或执行真实工作区验收。个人备份／恢复继续 BLOCKED，本阶段未修改真实数据库、服务或安全设置。见[交付与验证边界](engineering/CONVERSATION-TURN-NAVIGATION-REPORT.md)。

2026-11-07 Recovery 失败反馈阶段：合成生产者新增原生进度及需关闭确认的结果界面，区分授权未发送、执行结果未知、登记待核对和登记后收尾未确认；重复 Loaded 不会再次执行，结果窗口关闭不触发重试或激活。限定的只读个人身份检查已获用户授权并完成：最后保存的选择及私有目录元数据可核对，未返回命名 Desktop 进程，未取得 loopback Runtime 监听记录；这不能证明历史写入者或模型已停止。未读取真实配置、凭据或数据库内容，未启停既有服务。**个人备份／恢复仍 BLOCKED**，无需在聊天中提供密码。验证、检查对象和剩余前置见[本阶段交接](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。

2026-11-07 Recovery 原生授权阶段：一次性原生管理员/备份口令输入已与实际直属子进程、随机挑战、backupId 和受保护来源配置绑定，经私有管道进入合成冻结/加密/回收流程；取消或未知结果不可重放当前意图。一次新隔离流程通过原生按钮授权、真实备份和独立进程恢复，合成来源仍不能用于个人 plan。**个人恢复仍 BLOCKED**：历史未登记写入者和未知模型执行没有完整停写证明；本模块没有开放个人管理员入口。未访问真实数据、启停既有服务或调用模型。详见[交接、验证与最小用户前置](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。

2026-11-07 Recovery Runtime 收尾观察阶段：直属子进程新增受保护收尾意图/实际退出记录，修复 Job 结束时异步退出观察先于内核结束信号的竞态；原生维护界面可区分启动/退出未知、停止请求、失败退出和 owner 收尾。所有收尾记录仍拒绝安全停写准入，旧历史缺口不能由新成功记录覆盖。一个既有聚焦用例经两次失败诊断后最终通过；未访问真实 Profile/数据库、启动 Java/模型或启停共享服务。**个人恢复仍 BLOCKED**，完整历史归属/模型停止证明和个人管理员授权生产链未完成。详见[交接及准确验证范围](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。

2026-11-07 Recovery 来源冻结阶段：绑定来源的合成备份已用实际 OS 排他、17 表 SHARE 锁、原文文件 pin 和单次能力替代调用者的停写布尔声明，贯穿短期角色授予、加密与回收。两套全新合成流程通过（第二次验证最终 endpoint/目录绑定），原数据/Finance sentinel 保留，能力关闭后不能重用，来源仍只能登记到合成命名空间。**完整个人停写证明和个人管理员授权生产链尚未完成；UI 仍仅只读维护，个人恢复保持 BLOCKED。** 未访问真实 Profile、启停既有服务或执行模型请求；详见[本阶段交接](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。

2026-11-07 Recovery 维护 UI / Desktop 生命周期阶段：开发工作区选择器已提供原生只读维护入口，运行中托盘只给退出指引；完整停写、模型状态和来源授权分别显示，个人备份/恢复按钮保持禁用。一次全新合成流程验证取消确认不变、单次按钮授权、保留目标 mutex、真实 AssistantApp 启动回调发布 ACTIVE，以及其 RuntimeClient 的 Store/原文读回；个人选择文件未变，所属 Runtime/专用 PG 已退出。没有测试 WebView/托盘完整交互，也没有开放个人恢复。**个人恢复仍 BLOCKED**：旧包/跨会话/手动进程及外部写入者的完整停写无法补证，个人短期管理员授权到可信来源完成登记的生产链仍缺失。见[本阶段交接与证据](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。

2026-11-07 Recovery 合成激活阶段：已在全新隔离 PG/Profile 中验证来源登记 → 恢复 → READY → 单次显式授权 → 绑定唯一 owner/JVM 的 Native Gate → 实际 Runtime/Store 就绪 → DPAPI ACTIVE。修复恢复文件创建时未保护子目录 DACL 的问题；两个失败目标保留且不重放，owner 退出后残留授权仍被拒绝。未写用户工作区偏好、未启停既有服务。**个人恢复仍 BLOCKED**：完整停写、个人来源授权与正式确认 UI/生命周期尚未闭环。详情及准确验证边界见[来源生产者／激活交接](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。下文各切片记录保留其历史时点。

2026-10-30 补充：[GitHub 展示与 Runtime 核对](engineering/GITHUB-SHOWCASE-RUNTIME-READINESS.md) 对应 `main @ 8df87ab`。UI Polish / P0 / P1 / P2 已合并；历史 uncertainty 已解除并有一次文本问答成功记录。最新开发包重启后的历史状态为 NotValidated；本次只读检查时客户端与 Runtime 未运行，不能声明当前 Ready。下文未另行更新的核验与发布描述仍属 2026-10-28 历史记录。

2026-11-07 状态提示候选补充：本轮从 `main @ fe2c34c89703408b06bd3f4d8ca83c2ca6ded5b9` 开发，Settings 复用现有 Desktop owner、Bridge 与 Runtime 状态入口，区分构建身份、可访问性、存储 backend、模型安装／驻留／验证 Ready，并提供下一步。查询失败不保留旧的可用展示；未改变启动、认证、Profile、模型 guard 或外部服务所有权。当前源码候选不等于已构建并验收的分发包，也不等于历史实机证据包。实际仅执行 Settings / StorageSettings / Bridge 前端聚焦测试、TypeScript 检查及 Desktop 状态探针／Bridge 聚焦测试（含相关编译）；未启动真实客户端、模型或用户数据库，未重做受阻 Profile／Java 实机路径，未打包。当前实际 Runtime、数据库与模型状态未在本轮查询，目标 1 验收仍未完成。详见 [开发指南的状态说明](DEVELOPMENT.md#在-settings-核对本次可用性)。

2026-11-07 Recovery Slice 1：从 `352f01f901fc939bbb73dcef3ac34f0a2850fb98` 在 `codex/recovery-v3-workspace-core` 实施 Workspace-only v3 加密备份／恢复内核，固定 17 表结构、12 表数据、5 表 data 排除及受审查 catalog／归档目录；保留旧 v1/v2 合成格式。v3 仅供内部合成装配，本进程成功备份产生的能力对象与认证密文绑定，不能从口令或 manifest 自报身份获得 SQL 恢复权限；未实现正式来源登记或个人恢复入口。见[当前备份边界](architecture/current-architecture.md#本地安全profile-与备份)。

必要编译及单个真实 PG17.11 综合用例已通过（专属临时实例、非默认端口、合成明文卷例外）。同一用例累计执行 5 次：3 次失败、修正后 2 次通过、0 跳过；前三次失败分别涉及断言、fixture 缺失与索引查询上限，另在 fixture 准备时修正了 catalog 的 OID 派生名称。失败目标保留，未原地重放。最后一次为独立 Review 修复后的聚焦复验，1 用例、0 failures/errors/skips、12.57 秒。另一位 `gpt-6-astra`／xHigh 全程只读审查及复审，4 项 finding（失败空目标重放、遗漏 public 类型、两种合法 Knowledge 终态误拒绝）均已关闭，**本切片代码级 GO**。只扩展现有综合用例并去掉一处重复校验断言，未新增测试矩阵或删减已有安全回归。

未重跑全套测试；旧包仅复用认证拒绝证据，没有重新验收完整 v1/v2 成功恢复。第 2–5 切片、受保护卷、断电故障及真实 Profile／Desktop 恢复激活验收均未完成；仍为 Development-only、X1 PARTIAL、Vision BLOCKED、无已核验 CI PASS、尚未 Production GO。

2026-11-07 Recovery Slice 2a：在同一开发分支新增 DPAPI 恢复意图登记与 Desktop／Java 的候选启动拒绝。计划仅为 `RECOVERY_PLAN_ONLY / PREPARING`，不授予 SQL 来源、停写、目标 provision 或激活权限；没有产品维护入口。只运行两个新增的合成文件安全用例及必要编译，未访问真实 Profile／数据库或启动服务。完整第二切片及后续恢复、发布、激活仍未完成；详见[本轮交接与验证边界](engineering/RECOVERY-SLICE-2A-HANDOFF.md)。

2026-11-07 Recovery 来源生产者阶段：已在新建独立 PG17 合成实例贯通原生分配意图 → 直属 Java 排他句柄交接 → v3 加密备份与短期角色回收 → 原生核验真实密文及正常退出 → 独立 DPAPI 发布/重读。记录固定为 `synthetic-sources` 下的合成种类，不授予个人 SQL 来源信任；真实 `sources/.../source.dpapi` 生产者、完整停写证明、受保护源配置绑定及恢复/激活仍未完成，维护保持 **BLOCKED / UNREGISTERED_WRITERS_UNPROVEN**。同一聚焦流程经失败诊断后通过，未访问真实用户数据或操作既有服务；详见[来源生产者交接](engineering/RECOVERY-SOURCE-PRODUCER-HANDOFF.md)。

历史源码核验基线：`12dce0765dcdb89b75fd605c2b4fc7bf5db00617`；文档现代化候选：`95ba76a0f244037fd01a38e366da8f524908a418`。两者均为固定历史记录，不代表最新 main HEAD。
当前发布主线为 [main](https://github.com/qianlixunbai/personal-ai-workspace/commits/main/)：新版 README、文档体系与 Closing 修订通过 fast-forward 纳入主线；最新提交以该记录为准。原 54 个开发提交完整保留，原 12 个开发分支已清理，文档开发分支继续保留；本次仅完成文档发布，不升级产品验收。

[文档索引](README.md) · [开发指南](DEVELOPMENT.md) · [架构](architecture/current-architecture.md) · [路线图](roadmap/V1-ROADMAP.md)

## 必须保留的关卡

| 项目 | 当前结论 | 证据与限制 |
| --- | --- | --- |
| 产品定位 | **Development-only** | 受审查的本机开发路径；没有正式发布信任链或安装器 |
| X1 Windows Acceptance | **PARTIAL** | [X1 报告](engineering/X1-CROSS-APP-REPORT.md) 的完整跨应用流程仍未通过；后续单次文本成功不替代物理验收 |
| 模型执行 | **未确认当前 Ready** | [只读核对](engineering/GITHUB-SHOWCASE-RUNTIME-READINESS.md) 中的 uncertainty 解除、重启 NotValidated 与 Runtime 未运行均为历史记录；本轮未查询实机当前状态 |
| Vision | **BLOCKED** | [最新修复报告](engineering/V1-VISION-GATE-REMEDIATION-REPORT.md)：policy 仍为空，真实 runner / payload / 环境与完整 token 合同证据不足 |
| GitHub CI | **尚无已核验的 GitHub CI PASS** | 本轮只读 Actions 列表为空，基线没有跟踪的 workflow；不以本机历史 PASS 替代 |
| 正式产品验收 | **尚未 Production GO** | Git 整合、分支清理、文档更新都不升级验收或构成正式 Release |

## 已实现能力与真实边界

“已实现”表示源码链存在，不代表在当前环境或当前提交重新跑过完整验收。

| 范围 | 已有实现 | 边界与来源 |
| --- | --- | --- |
| Windows Workspace | WPF 生命周期、托盘、受信 WebView2、中文导航、浅／深色、原生快速助手 | [UI 成熟度](engineering/UI-MATURITY-REPORT.md)；混合 DPI、物理跨应用流程等仍待验收 |
| Ask / Summarize / Translate | Runtime-owned 本地文本任务、取消、失败／超时状态、Browser 单条／批量翻译 | [M1.5](milestones/M1.5-CLOSING-REPORT.md)、[M2](milestones/M2-CLOSING-REPORT.md)；Browser 仍 Translate-only |
| Conversations | durable 多轮、逐轮显式 Memory、归档／删除、重开与终态恢复 | [M5C](milestones/M5C-CLOSING-REPORT.md)、[PostgreSQL 收尾](engineering/POSTGRESQL-DESKTOP-CLOSING.md) |
| Memory | 用户手动 CRUD、revision、显式 context、独立逻辑备份 | [ADR-004](ADR/ADR-004-user-controlled-memory-storage.md) 至 [ADR-007](ADR/ADR-007-logical-workspace-backup-restore.md)；无自动记忆 |
| Knowledge | TXT / Markdown 导入、独立源文件与 schema、预览、词法检索、问答与 Runtime-owned citations | [K1](milestones/K1-CLOSING-REPORT.md)、[K2](milestones/K2-CLOSING-REPORT.md)、[K3](milestones/K3-CLOSING-REPORT.md)；不是向量检索或自动 RAG |
| Model Management | 单一 Active owner、原生 exact-intent 确认、lease / switch / uncertainty guard | [MMF-3 closing](engineering/MMF-3-CLOSING-RECORD.md)；其历史 Development-only GO 不解除当前 X1 阻塞 |
| Workspace 存储 | 默认 SQLite；显式 PostgreSQL、Profile 选择与成功工作区记忆、原生配置和只读状态探针 | [PostgreSQL 收尾](engineering/POSTGRESQL-DESKTOP-CLOSING.md)；不自动迁移或静默回退 |
| Finance | 绑定身份后的账户、流水、资产、持仓、Portfolio、投资历史、导入回执、Dashboard 与统计只读查询 | [F-next-1–9 盘点](engineering/FINANCE-REMAINING-CAPABILITIES.md)、[F-next-10](engineering/FINANCE-F-NEXT-10-REPORT.md)、[用户确认归属](engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md) |
| 行情 | 显式 Massive 日线显示、来源／参考日期／获取时间、受控 Key 保存 | [M1.2 收尾](engineering/FINANCE-MARKET-DATA-M12-CLOSING.md)；用户报告 AAPL、SPY、USD/CNY 成功，不能外推全部持仓或本轮新包 |
| Web | W1A 原生确认的有界 HTTPS Fetch、SSRF / DNS pinning、临时结果 | [W1A closing](milestones/W1A-CLOSING-REPORT.md)；W1 整体未关闭，Search / Web Answer 不能宣传为已交付 |

Finance Budgets 明确为 **UNSUPPORTED**；不提供预算统计或写入。财务账本写服务、导入创建／确认、工具调用、独立 Finance AI Analyst 均未迁入。统一 AI 的未来金融能力仍须走已绑定的服务端领域边界。

## 验证证据如何理解

- **历史实际执行**：各工程／里程碑报告中的聚焦测试、构建、Windows fixture、数据库／模型结果，仅对应其记录的提交、环境与包。
- **用户人工报告**：M1.2 AAPL / SPY / USD/CNY，以及 F-next-10 的验收确认，按原报告归属保留；不改称本轮由代理实际运行。
- **UI 截图**：真实 Windows WPF / WebView2 渲染，采用隔离合成 UI fixture，不证明当前真实模型成功或用户数据流程通过。
- **文档现代化历史核验（`95ba76a`）**：文档与 owning code 静态交叉核对、链接／锚点／截图隐私审查、Git 与远端状态核验。详见 [原始文档整理报告](engineering/DOCUMENTATION-MODERNIZATION-REPORT.md)，其开发分支交付状态仅记录当时语境。
- **历史文档 Closing** 同步了当前文档的主线发布状态，检查了 Markdown 链接／图片路径、关键状态与 Git 差异，并发布到 main。历史快照与原始交付报告保留当时的记录。
- **历史文档 Closing 未执行**生产测试、完整 Windows 验收、GPU / 模型 / Provider / 数据库操作、构建和打包。开发指南中的命令来自源码核对。

## 仍需完成的验收

1. 用户启动最新开发包，在原生界面重新验证加载并确认 Ready，再恢复真实 X1 选区到结果流程；若 uncertainty 再现则停止，不重置 guard 代替证据。
2. 解决跨应用前台／物理输入阻塞，完成 Q/T 热键、操作切换、受控复制、Esc／关闭和混合 DPI 等真实流程。
3. Vision 的 WIC / held-handle 独立复审、实际 native payload / 有效环境与完整 tokenizer / template 上界合同，之后才讨论最小正向 policy 与真实单图验收。
4. PostgreSQL 同一 final 版本的物理托盘退出／重开关卡、D2 原生验收及新包验收，按各自报告逐项关闭，不用旧版本结果替代。
5. 独立完成最终产品、发布可信链、安装／迁移／恢复等关卡；当前不授予正式 Release 或 Production GO。

USD/CNY 参考日期落后的具体原因仍未知；获取成功不等于最新行情。Massive 显示能力不允许持久缓存、离线回放或派生估值。

## 历史记录与本地资源

现代化前的 1,280 行状态正文保留为 [历史快照](history/2026-10-28/STATUS.md)，其中 Current / PASS / PENDING 与分支名称仅描述当时阶段。历史工程报告、ADR、设计证据和里程碑原路径保留。

`.runtime/`、`.verification/`、`artifacts/`、TestResults、数据库、Token、DPAPI 密文及其他 ignored 私有资源不属于文档发布内容，不随源码推送。使用者不应把本机证据中的个人路径或凭据当成可分享材料。
