# 当前状态

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
