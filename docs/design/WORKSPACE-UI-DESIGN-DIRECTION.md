# Workspace 统一 UI 设计方向

2026-10-24，Asia/Shanghai。**设计提案，尚未实现或进行 UI 验收。** 源码基线为清理分支 `28ac441a096af736a5fbc91bb67913e0552c868c`。研究证据、16 个上游项目及限制见 [开源研究](WORKSPACE-UI-OPEN-SOURCE-RESEARCH.md)；实施边界见 [实施计划](WORKSPACE-UI-IMPLEMENTATION-PLAN.md)。

## 推荐选择

采用 **B：AI Productivity Workspace**，使用 Windows 熟悉的侧栏与标题层次，Finance 页面采用专业数据密度。统一视觉由一组 Token 和组件规则管理，不混用 Fluent、Material 与 shadcn 的默认皮肤。产品面向长期在 Windows 使用的个人用户；首要任务是清楚地进入 AI、对话、记忆、知识或金融工作，而非展示一个巨型首页。

视觉特征：冷灰蓝底、清晰细边界、深蓝当前项、自然中文排版、收敛的圆角。识别点放在 **当前导航的细指示线与主内容顶部的来源/状态行**：用户随时知道正在何处、数据来自何种范围、是否已核对。界面不使用营销式大渐变、装饰性排名数字、满屏统计卡或虚假在线绿点。

### 三个可区分的方向

| 方向 | A：Modern Windows Native | B：AI Productivity（推荐） | C：Professional Analytics |
| --- | --- | --- | --- |
| 核心视觉 | Fluent 式控件、宽导航、原生设置分组、可选 Mica | 安静的统一侧栏、列表/主内容、按需来源区 | 紧凑工具栏、表格/筛选/详情、少量摘要指标 |
| 典型用途 | 设置、原生维护、多窗口一致性 | 日常 Assistant、Conversations、Knowledge；兼容所有模块 | 金融流水、持仓、Audit/Receipt 深入核查 |
| AI 表现 | 表单式输入和输出，系统工具感强 | Assistant 为默认入口，持续对话与一次性操作分清 | 容易让 AI 退化成数据工具中的附属面板 |
| Finance 表现 | 清楚但空间利用偏低 | 二级分组导航，内容按业务密度调整 | 金融体验最强，但可能占据产品中心 |
| Knowledge 表现 | 设置/列表式管理 | 列表—预览—引用定位最自然 | 表格化元数据强，阅读体验需要补偿 |
| WPF/WebView2 | 原生外观易协调；XAML 控件不能直接迁入 React | 最贴合现有 React 容器与独立 WPF 生命周期 | React 数据页适合，原生融合仍需专门处理 |
| 成本 | 全套 Fluent/CSP、宿主标题栏改造为中高成本 | Shell/CSS 渐进更新为低到中；复杂原语按需引入 | 页面改造与精度/密度验证中高 |
| 优势 | Windows 熟悉、设置与维护清楚 | 覆盖长期产品范围，能逐页交付 | 复杂数据高效、信息层次明确 |
| 缺点 | 可用空间下降，容易为像原生而重做宿主 | 必须严格控制第三栏与未实现能力入口 | Finance 中心化风险，非数据页面压迫感强 |

以总体适配/安全边界成本/长期维护/金融密度四项权重 35/30/20/15 作主观 1–5 评估：A=4/3/3/3，B=5/5/4/4，C=3/4/3/5，综合约 A=3.35、B=4.65、C=3.60。评分不是测试结果；B 的优势来自保留现有 ownership 和增量空间，而非某个库的流行程度。

三个方向的结构草图（概念线框，不是现有截图）：

```text
A: 原生标题栏
   [较宽 Fluent 导航] [页面标题 / 大间距系统控件]
B: 原生标题栏
   [工作区导航] [局部列表或模块导航] [主要工作内容] [按需来源]
C: 原生标题栏
   [紧凑导航] [期间 / 筛选 / 状态]
              [少量摘要] [高密度表格] [详情 / 证据]
```

借鉴 A 的窗口秩序、C 的 Finance 密度，全部使用 B 的同一套字体、颜色、间距与焦点规则。参考细节来自 [WPF UI Gallery](https://github.com/lepoco/wpfui)、[Cherry AppShell](https://github.com/CherryHQ/cherry-studio)、[Actual Account Register](https://actualbudget.org/docs/tour/accounts/) 与 [Radix Dialog](https://www.radix-ui.com/primitives/docs/components/dialog)；各自具体源码见研究文档。

## 当前界面审查：源码事实与设计判断

本节为静态代码审查，没有启动当前客户端测量像素。前端缩写 `src/...` 相对 `desktop/frontend`；原生窗口/Hosting 文件位于 `desktop/src/PersonalAiWorkspace.Desktop`。有关行为由实现和已有测试源码支持，不等于本轮测试已运行。

| 核对对象 | 当前事实 | 影响与建议 | 应保留的优点 |
| --- | --- | --- | --- |
| `desktop/frontend/src/app/App.tsx` | 全局 sidebar；每页大标题；顶部固定“原生功能可用”；底部固定 Local only 绿点 | 状态文案不与真实状态一致；用已存在 ShellStatus 展示“可连接/不可用/未核对”，不宣称模型 Ready | 真实 typed bridge、skip link、焦点入口、bootstrap generation 保护 |
| `src/styles/workspace.css` | 主网格 240px 侧栏；main 左右 48px；page-content max-width 960px；850px 时变 190px/24px | 宽屏数据区利用不足；窄窗又长期保留大侧栏。内容宽度应按阅读/数据页区分，采用折叠与可恢复入口 | 已有 light/dark variables、forced-colors、minmax(0,1fr)、表格局部滚动 |
| `src/pages/FinancePage.tsx` | 9 个顶部按钮；本地 view union；初始 accounts；切离 Finance 会卸载 | 改为分组模块导航；不要新增 hash 或持久化状态，不改变初始视图和卸载清除 | 已有完整只读入口、Dashboard→Assets、Position→History 的来源路径 |
| `src/pages/OperationPage.tsx` | Assistant/Translate 同组件，Ask/Summarize，任务轮询/取消、Memory 逐次明确选择，结果 pre | 视觉区分输入、上下文、执行状态、结果；当前不是持续 chat，不可悄悄合并为 Conversations | IME 保护、双字节预算、未知结果不重发、受控复制 |
| `src/pages/ConversationsPage.tsx` | 持久对话列表/详情、ACTIVE/ARCHIVED、分页、latest/history、逐轮 Memory | 可用列表—会话布局，但不假设已有全文搜索、文件夹或流式 token 输出 | 历史有界、归档禁发、取消/恢复按 Runtime 真值、草稿和选择边界明确 |
| `src/pages/MemoryPage.tsx` | 列表/编辑器、revision、dirty guard、native dirty 信号；1050px 单栏 | 统一列表行与编辑区；离页、reload、删除确认不可被新导航绕过 | 用户明确 Save；revision 冲突/unknown 锁定，不自动重试写入 |
| `src/pages/KnowledgePage.tsx` + Search/Answer | 文档/版本/预览、确定性词法检索、问题与检索关键词独立、回答引用定位 | 搜索、导入状态和阅读分层；按需预览源，不将 Memory 与 Knowledge 混成同一列表 | 原生文件选择、双 revision、引用的行/偏移/版本、独立恢复 |
| `src/pages/SettingsPage.tsx` | 运行状态、Storage、Finance 绑定、ModelManagement 和原生维护都堆在同页 | 按运行与模型/存储与身份/备份与权限分区；Phase 1 仅提供状态入口，详细重排后置 | 区分 reachability 与 ready；模型操作使用原生精确确认；敏感信息留在 native |
| `MainWorkspaceWindow.xaml(.cs)` | 默认 1160×790 DIP、最小 780×540；原生标题栏+顶栏；WebView ZoomFactor 0.75–2，步进 0.25；白色顶栏/浅色 fallback | React 的 CSS viewport 才是断点依据；原生深浅衔接存在差异，单独处理；不在网页画第二套窗口按钮 | 原生缩放、聚焦、Quick Assistant fallback；不依赖前端存活 |
| `WorkspaceWebViewHost.cs / WorkspaceContentPolicy.cs` | InPrivate、manifest 资源、固定 origin、7 个 hash、connect-src none / style-src self | 禁止为了新导航/库放宽 CSP；不新建 /runtime 或 finance 子 hash。CSS 提取为本地文件 | 凭据隔离、无任意网络/URL/文件能力、会话轮换清除 |

中英文混排当前重复完整双语长按钮较多；主导航采用短中文＋稳定英文识别名，页面标题保留 `Finance / 财务` 等双语，不把每一行重复双语。金融状态保留来源枚举与解释，因为它们确有核查价值。

## 产品能力与未来边界

| 区域 | 当前可设计的真实范围 | 仅作为明确标识的未来规划 |
| --- | --- | --- |
| Assistant | 一次性 Ask、Summarize、显式 Memory、状态/取消/复制；存在受 readiness 门禁限制的 VisionPanel | 自由 Agent、Finance Tools、自动注入 Knowledge、全局会话合并；Vision 不因有面板就算已验收 |
| Conversations | 持久对话、分段历史、逐轮 Memory、重命名/归档/删除、受控执行 | 全文检索/文件夹/标签、任意附件、token streaming |
| Memory | 用户明确 CRUD/归档/恢复、revision、安全离页 | 自动抓取/自动保存、后台“学习”、跨域默认注入 |
| Knowledge | 原生导入、源版本预览、词法查询、带引用回答、独立备份 | embedding/向量检索、与 Memory 共库、任意 URL 自动抓取 |
| Translate | 已有 zh-CN/en/ja 目标及状态/结果；Browser 仍 Translate-only | 通用浏览器权限或网页抓取入口 |
| Finance | Dashboard、账户/流水/资产/持仓/组合/投资历史/确认导入历史只读 | 写入、上传 Preview/Confirm、主动行情刷新、独立 Finance AI Analyst |
| Budgets | 来源 `UNSUPPORTED` 能力说明 | 不画预算余额、进度条、创建按钮或演示假数据 |
| Runtime/Provider | Shell 状态；Settings 的 Active/Configured/Installed/Loaded/Ready、模型目录与原生确认 | 自由多 Provider/API Key 编辑、自动切换、第二套运行时 |

Shared AI Runtime 是统一模型与执行 authority；Finance 专用应用服务维持金融事实与计算。React 不访问数据库或共享用户身份绑定信息，也不从 ID 推断权限。

## 全局导航与当前路由映射

```text
Personal AI Workspace
  工作
    Assistant      助手（默认）
    Conversations  对话
    Translate      翻译
  资料
    Memory         记忆
    Knowledge      知识
  业务
    Finance        财务
  底部
    运行状态 → Settings 现有“本机运行状态/模型”区域
    Settings       设置
    浅色 / 深色
```

这里只是 **7 个现有 route + 一个 Settings 状态快捷入口**，不是新增 8 个 hash。Assistant 是统一 AI 的产品入口，但它与当前 Conversations 的业务语义继续区分。标签顺序可调整，route ID 保持：`#/assistant`、`#/conversations`、`#/translate`、`#/memory`、`#/knowledge`、`#/finance`、`#/settings`。

所有导航方式（鼠标、键盘、紧凑侧栏、未来命令菜单、状态入口）必须调用 App 原有 navigate / MemoryLeaveGuard；不得用直接 setPage、window.location 或新 Router 旁路。Runtime 状态快捷入口只在页面切到 Settings 后把焦点移至现有区域，不改 hash 合同。若离页取消，焦点和路由保持原页。

本期没有工作区 tabs 或全局 Profile 切换器。已有 Profile 切换仍由原生托盘/选择器处理；React 不保存 Profile ID、数据库地址或“最近工作区”。可为未来新增模块留导航组位置，但不提前显示大量灰色菜单。

### Finance 二级导航

```text
Finance（初始仍为 Accounts）
  总览
    Dashboard              财务总览
  日常账务
    Accounts               账户
    Transactions           交易流水
    Assets                 资产
  投资
    Investment Positions   投资持仓
    Portfolio Summary      投资组合统计
    Investment History     投资交易历史
  历史记录
    Import History         导入历史（仅 CONFIRMED 历史/回执）
  能力说明
    Budgets                预算 · 来源不支持
```

宽窗用 176px 左侧局部模块导航，避免在全局侧栏重复展开所有模块；窄窗用带当前值的 `选择 Finance 页面` 原生 select。两者只驱动既有 view，互斥显示，可键盘访问；group 标题不承担操作。保留 9 个目的地，包括明确标记的 Budgets 说明页，不能把它当成功能表单。

第一阶段只替换 Finance 导航外观；view 初始值、切换卸载、historyPosition 传递和显式点击 History 时清空该筛选的行为完全保留。顺序靠前不代表改变默认页。后续若要 Dashboard 默认或深链接，作为独立明确行为变更处理。

## 关键页面布局

### Shell 与 Assistant

```text
[Windows 原生标题栏：应用名 / 最小化 / 最大化 / 关闭]
[既有原生缩放 / 聚焦 / fallback 入口]
[工作区导航 216] [Assistant / 助手                     状态入口]
                 [单次问答 / 摘要] [按既有数据可证明的模型/状态]
                 [逐次明确选择的 Memory / 无 Memory]
                 [输入区，明确提交与取消，预算提示]
                 [任务状态；失败/结果未知不消失]
                 [结果，受控复制；长代码局部滚动]
```

阅读内容最大 880px、左右居中，表单与结果不必四周再套巨大卡片。Ask 不改成自动创建持久会话；需要持续上下文时用户进入 Conversations。当前没有逐 token stream，则不画模拟流式动画或伪造 Tools 过程。

模型入口先只跳转 Settings；ShellStatus 只有连接/凭据，不提供模型名，就不能凭它显示 Ready。可用模型名只有在已有授权模型 snapshot 可核对时显示；不为装饰状态额外轮询或自动切换。Vision 保留原条件与“未验证”状态，不能因为新附件图标而越过门禁。

Markdown/Code 的长期方向是本地、安全、无 raw HTML 的只读渲染与局部滚动；当前 plain text/pre 是安全可用基线。引入解析器、链接策略、渲染预算和脚本安全需后续单独评估，不在 Shell 阶段添加 Markdown、Mermaid 或远程图片执行。

### Conversations

宽窗：局部列表 260–280px + 会话正文（正文 max 880px）；顶端保留 ACTIVE/ARCHIVED 和已有翻页，新建入口常驻。主区依次显示标题与归档状态、latest/history、消息、逐轮 Memory、输入与执行状态。较窄时列表在主区上方，最大高约 240px；不要为左侧再加全局历史索引。

保留当前分页与只渲染有界 turns 的策略。切会话时丢弃 draft/逐轮 Memory 的现有语义不变，不在视觉迭代自动保存草稿。不得以“新对话”之名重发未知执行结果。模型/Tools/文件上下文若没有实际 DTO，界面不显示可操作控件。

### Memory

工具栏（查询、状态、类型、新建）→列表 280px / 编辑区。保存、重载、归档/恢复与删除放在编辑器就近；dirty/revision conflict/结果未知在编辑器上部固定可见。列表字段展示标题、类型、状态、revision，不预先抓取每篇正文。

页面宽度不足时列表与编辑器上下排列，保留当前位置和未保存内容。切走、刷新与窗口关闭沿用现有保护，原生备份入口保持独立。

### Knowledge

主区工具栏（导入/检索）→文档列表 280px / 文档预览。检索结果有标题、词法命中片段、版本/行定位；“基于知识回答”继续区分问题与检索关键词，不悄悄改成统一语义搜索。

回答引用点击后进入现有 sourceRevision/offset 的真实预览。足够宽时可在 320–400px 侧面显示来源，普通窗口采用同页预览/返回路径，避免 216+280+正文+来源同时挤压。READY/PARSING/FAILED/ARCHIVED 等来自真实状态，失败导入不能显示为可用来源。

### Translate

宽窗输入/结果双栏，窄窗纵向；语言选择、提交、取消和复制沿用 OperationPage。文本方向按内容处理，中文/英文/日文长文不溢出。不能修改共享组件导致 Assistant 模式、输入预算或轮询行为变化。

### Finance

```text
[全局导航] [Finance 局部导航] [页面名 / 已验证身份范围 / 刷新]
                               [当前筛选 / 截至时刻 / 币种与来源说明]
                               [小型摘要（确有服务端指标才显示）]
                               [主表格 / 选中项详情 / Receipt 分段]
                               [不完整估值 / 部分覆盖 / 错误：持久可见]
```

| 页面 | 主内容 | 详情层级和必须保留的信息 |
| --- | --- | --- |
| Dashboard | 账户余额/当月普通收支四项；六个月精确表；人工参考分布；最近五条 | Asia/Shanghai 与 asOf；余额非净资产；普通表收支非投资现金流；分布完整分母及排除数量 |
| Accounts | 账户总览、状态/排序/分页、列表 | 完整 ID、类型/币种/状态、服务端余额，不增加编辑入口 |
| Transactions | 类型/账户/日期筛选、原来源键、分页 | 普通交易与 V19 transfer 的明细按既有 kind/id，不能仅用裸 ID 合并 |
| Assets | 人工价格与缓存 Quote/FX 参考估值 | null/缺价/过期/不可用/部分覆盖；不提供主动行情刷新 |
| Investment Positions | OPEN/CLOSED、账户/标的、cursor | 账务成本与参考估值分区；点击历史继续携带原 positionId |
| Portfolio Summary | 服务端成本/已实现盈亏与非账务估值 | 完整性/覆盖、币种、来源；不与账户余额叠加成总资产 |
| Investment History | 逻辑交易列表→详情→audit timeline | 不可变事实、冲正/更正关系、历史 Receipt；无 Replay/修正操作 |
| Import History | CONFIRMED 历史→已验证回执的分段展示 | batch UUID、统计/账户影响/行分段及完整验证状态；无上传/Preview/Confirm |
| Budgets | 来源 UNSUPPORTED 说明 | 说明不可用原因，返回 Finance 的安全入口；没有预算配置 |

Receipt 分为“摘要/账户影响/行证据”，Audit 分为“逻辑交易/不可变事实/关系/回执”。使用已有分段读取，不能预取全量或只留漂亮摘要。若采用 details/折叠，identity/完整性/缺失/损坏提示必须在折叠外，完整 ID、revision、hash 等证据可以展开/换行全文选取，不以 hover tooltip 作为唯一访问方式。不要新增未经授权的导出/剪贴板能力。

### Runtime / Provider 与 Settings

Runtime 是信息架构中的明确区域，当前落在 Settings，不新增路由：连接状态→Active/Configured/Installed/Loaded/Ready→队列/unknown guard→现有原生确认操作。不能用“Runtime 可连接”概括“模型就绪”。Provider 管理仅呈现现有本地模型能力，不画自由 API Key、远端列表或自动路由设置。

Settings 分为“运行与模型”“存储与 Finance 身份”“凭据与 Browser 权限”“备份与恢复”“界面与隐私说明”。现有维护入口对应真正原生窗口；危险动作继续原生审核，不能转换成 React 假确认。备份范围必须如实区分 Workspace v1（Memory + Conversation）与 Knowledge 独立备份。

## 统一设计系统

### 字体、密度和空间

| 项目 | 统一建议 |
| --- | --- |
| UI 字体 | `"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif`；使用已安装系统字体，无下载/打包 Windows 字体 |
| 页面标题 | Segoe UI Variable Display（fallback 同上），24px/32px，600；不用当前 32px 大标题占据每页首屏 |
| 分区/正文/辅助 | 分区 16/24px 600；UI 14/20px；AI/知识正文 15/26px；辅助 12/18px。关键证据不低于 12px |
| 数据/ID/代码 | 金额沿用正文家族并用 tabular-nums / lining-nums；ID/代码 `Cascadia Mono, Consolas, monospace`，必要时 12/18px |
| 间距 | 4、8、12、16、24、32、48px；常规页面边距 24px，小窗 16px；卡片 20–24px |
| 行/控件 | 导航行 36–40px、按钮/输入最小高 36px；Finance 常规行约 40px，允许内容自然增高，密集可选 32px 但不压字 |
| 圆角/边界 | 控件 6px、卡片 10px、弹层 12px；1px 边界；仅浮层用轻阴影 |
| 动效 | 状态/折叠 100–150ms 内，prefers-reduced-motion 下关闭；不做自动滚动抢焦点 |

中文和英文自然 fallback，不强制 uppercase 辅助行；布局用逻辑属性，输入和结果可 `dir=auto`。金融值和 ID 以独立方向隔离，防止双向文字影响关键字符串。

### 颜色 Token（设计目标，待实际渲染验证）

| 语义 Token | Light | Dark |
| --- | --- | --- |
| canvas | #F4F6FA | #141A23 |
| surface | #FFFFFF | #1C2431 |
| rail | #EEF2F7 | #171F2C |
| text | #1B2638 | #E6ECF4 |
| text-muted | #52627A | #ADBCD0 |
| border | #D7DEE8 | #344154 |
| accent / focus | #315EA8 | #95B9F2 |
| accent-soft | #E8EFFB | #233B61 |
| primary-fill / primary-text | #315EA8 / #FFFFFF | #A7C7FA / #12223C |
| success | #1F6A4B | #8BD5B0 |
| warning | #8A5300 | #F1CA7C |
| danger | #AE2A32 | #FFA5AA |
| unknown | #5B6473 | #B9C2CF |

兼容映射：现有 `--bg/--surface/--text/--muted/--border/--accent/--active/--focus` 指向新语义，不要求一次改完全部选择器。可增加尺寸 Token，但不要创建通用 theme engine。主题沿用 `readTheme/saveTheme` 的 light/dark 与系统初始偏好；InPrivate 清理下不承诺跨窗口永久保存。首期不新增浏览器存储键、Cookie 或跨 Profile 偏好。

文本目标普通字对比度至少 4.5:1，大字 3:1；颜色不作为唯一状态信息。上表边界色用于结构分隔，不承担唯一焦点/交互识别；交互焦点使用高对比 accent 2–3px 轮廓。forced-colors 使用系统颜色与真实边界，不能用 fixed hex 覆盖高对比需求。对比度计算不等于完整 a11y 验收。

### 组件规则

| 组件 | 规则 |
| --- | --- |
| Navigation | 同一层级同高；当前项有细指示线/背景/aria-current；分组标题不交互；底部状态与设置可滚动到达 |
| Button | 一个工作区局部主动作；secondary 用边框；destructive 有文字与确认；pending 禁重复但不丢状态 |
| Input/Select | 标签可见，帮助/错误由 aria-describedby 关联；保持原受控值、IME、输入预算和提交键 |
| PageHeader | 标题、短说明、真实来源/状态、局部动作；不重复品牌横幅 |
| Card | 为真正独立任务分组，避免卡片里套卡片；数据大表可直接占内容面 |
| Table | 语义 table/th、明确标题、表头/金额对齐；滚动容器具可访问名且可聚焦；小窗只在容器内横滚 |
| Dialog/Drawer | 优先保留已验证原生 HTML dialog/ConfirmationDialog；新复杂控件可选 Radix；默认安全取消、Esc/Tab/焦点返回；长证据通常同页或大面板 |
| Status/Toast | Loading/Empty/Error/Unknown/Partial 分开。持久错误就地显示；toast 仅补充非关键确认，不替代结果/故障 |
| Loading | 仅该区域 loading；aria-busy；不拿旧 Finance 快照当 skeleton 背景 |
| Empty | 区分“没有数据”“没有匹配”“尚未绑定”“来源不支持”；动作只链接实际已有入口 |
| Error | 受控可解释原因与现有重试；不显示 raw exception、token、DSN、文件路径或私有请求 |

### 金融数字与证据硬规则

金额、百分比、金融 ID 原样传递展示。禁止为格式化、排序、图形使用 `Number / parseFloat / toFixed`；不自动四舍五入、缩写 K/M、科学记数或强制两位小数。Dashboard 合同可接受最多 60 位整数和 16 位小数，设计必须按真实上界容纳，其他页面按各自合同而非统一猜测。

金额列右对齐且用等宽数字；通常不拆小数，但极长值允许局部水平滚动。详情里完整值可换行或在有标签的局部滚动区读取，不能通过缩成不可读字体、ellipsis 或 tooltip 隐藏。超长 ID/来源名称可 `overflow-wrap:anywhere`；null 显示“未提供/未定价”等有原因的标签，不能显示 0。

估值不完整、Quote/FX 过期、价格时间未知、部分资产分布保持常驻说明。Dashboard 的人工分布最多 20 项且分母覆盖全部合格资产；不得将当前页归一化为 100%。六个月数据以服务端补零为准；不在前端重新合计或把普通收支命名为投资收益。当前不引入金额图表，图表候选仅保留在研究中。

## 响应式、DPI 与 Windows 宿主

以 **实际 WebView2 的 CSS viewport width** 决定布局，不能直接按显示器物理分辨率。原生窗口标题栏、顶栏、任务栏、边框、Windows 缩放和 WebView ZoomFactor 都会减少有效内容。WPF 使用 DIP，CSS 使用 CSS px；不承诺二者在所有缩放下 1:1。依据：[Microsoft breakpoints / effective pixels](https://learn.microsoft.com/en-us/windows/apps/design/layout/screen-sizes-and-breakpoints-for-responsive-design)。

| CSS viewport 宽度 | 全局导航 | 局部导航/列表 | 主内容 |
| --- | --- | --- | --- |
| ≥1440 | 216px 展开 | Finance 176px；Conversation/Knowledge 260–280px | 24px 边距；数据 max 1680px；阅读 max 880px；来源区仅剩余空间充分才并列 |
| 1280–1439 | 216px 展开 | Finance 176px；内容列表 240px | 24px 边距；不默认展开第三个来源栏 |
| 1024–1279 | 64px 紧凑栏，真实文字可访问名；可展开带文字导航 | Finance 使用 select；阅读列表可上下排列 | 16–24px 边距；不隐藏唯一操作 |
| 760–1023 | 64px 紧凑栏；显式“展开导航” | Finance select；列表—详情单栏 | 16px 边距；表格仅局部横滚 |
| <760（缩放可能触发） | 顶部“工作区页面”原生 select 或可关闭菜单 | 全部单栏 | 仍可到达每页；这是桌面缩放退化路径，不另建手机版 |

不以图标-only rail 作为唯一可发现入口；Phase 1 无图标库时可用短文字并提供完整 aria-label，或直接使用明确的页面 select。展开导航只在组件内存中；点击后复用 navigate，不能持久化域内容。侧栏主体设置可滚动并有 min-height:0，底部 Settings/状态不能被 sticky 100vh 的固定内容挤出。只保留必要滚动层：页面滚动 + 有界数据/代码容器，避免每张卡都独立滚动。

尺寸检查目标（将来执行，不是本轮 PASS）：

| 目标窗口/屏幕 | 预期 |
| --- | --- |
| 1920×1080 | 展开导航、数据页充分利用宽度；知识/AI正文仍保持阅读行长 |
| 1440×900 | 展开导航和局部列表；来源默认折叠 |
| 1280×800 | 若实际 CSS 宽已小于 1280，按紧凑规则；所有 Finance 目的地可到达 |
| 1024 宽 | 紧凑导航、Finance select、表格局部横滚 |
| 现有最小 780×540 DIP / WebView 200% | 不改变宿主最小尺寸；允许降到单栏/select；提交、取消、错误、导航可见或可滚到 |
| Windows 100/125/150/200% | 记录实际 viewport、devicePixelRatio、ZoomFactor；检查文本、命中与窗口控件，不机械做全部排列组合 |

保持真实原生标题栏和系统最小化/最大化/关闭；React 不进入标题栏、不覆盖窗口控件、不实现拖拽命中或 Mica。既有缩放按钮、聚焦、fallback 留在 WPF。第一阶段只改善 WebView 内部，因此原生白顶栏与 React dark 的视觉接缝仍是已知未解决项；Phase 5 可在不改窗口语义下评估系统主题刷。若要求 WPF 跟随 React 手动切换，当前没有现成主题同步合同，必须另行设计授权，不能为“视觉一致”偷偷新增 Bridge 方法。

本方案不包含云同步、Finance 写入、DB/schema、Native Gate、身份重绑、全局 Profile 切换、第二套 Runtime 或权限变更。下一轮只按 [Phase 1 Prompt](WORKSPACE-UI-PHASE-1-PROMPT.md) 开发，所有本页布局/色值均为待实施目标。
