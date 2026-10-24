# Workspace UI 实施计划

2026-10-24，Asia/Shanghai。**下一阶段候选开发计划；以下验收全部是待执行项，不是本轮测试结果。** 当前提交只包含本文件及另三份研究/设计文档。

设计依据：[研究](WORKSPACE-UI-OPEN-SOURCE-RESEARCH.md)、[设计方向](WORKSPACE-UI-DESIGN-DIRECTION.md)。推荐方向为 AI Productivity Workspace，以 Windows 导航习惯和 Finance 数据密度补充；不替换 .NET 10/WPF/WebView2、React/TypeScript、Java Shared AI Runtime 或现有数据库方案。

## 已确认的真实结构

| Owner | 当前职责 | 计划如何使用 |
| --- | --- | --- |
| `desktop/frontend/src/app/App.tsx` | 7 页导航、bootstrap、状态、Memory 离页保护；多数页 hidden 保留挂载；Finance/Settings 条件挂载 | Shell 和导航外观更新，保留原有 React identity、enabled/visible 和生命周期 |
| `desktop/frontend/src/app/navigation.ts` | route ID/name/subtitle；pageFromHash 的未知路由回到 assistant | 仅必要标签/分组元数据，不加 Router，不生成子 hash |
| `desktop/frontend/src/app/theme.ts` | light/dark，现有 workspace.theme，系统初始偏好 | 原样复用，不新建 preference store |
| `desktop/frontend/src/styles/workspace.css`、`src/main.tsx` | 全局及页面样式；一次性本地样式导入 | 新 Token 通过原变量映射；可增加单独 tokens.css，按一个入口导入 |
| `desktop/frontend/src/pages/FinancePage.tsx` | 9 个 view 的唯一组合点、accounts 初始、historyPosition | 第一阶段只改 nav 与容器，复用全部渲染分支/回调 |
| `desktop/frontend/src/pages/OperationPage.tsx` | Assistant 与 Translate 共用任务/输入状态 | 第二阶段局部布局，第三阶段 Translate 验证共享影响 |
| `desktop/frontend/src/pages/{Conversations,Memory,Knowledge,Settings}Page.tsx` | 对应页面的持久/编辑/权限/维护逻辑 | 按阶段改呈现，不迁出业务状态来建立全局缓存 |
| `desktop/frontend/src/components/Finance*.tsx` | 各金融只读页与证据 | 第四阶段分别调整，不以“一张 Dashboard”替代模块 |
| `desktop/frontend/src/bridge/*` | DTO、校验、typed 方法和会话相关性 | 受保护，不为视觉改合同；金融字符串和来源状态不变 |
| `desktop/src/PersonalAiWorkspace.Desktop/MainWorkspaceWindow.xaml(.cs)` | 原生窗口/缩放/焦点/fallback | 前四阶段不改；第五阶段仅必要本地资源外观可单独评估 |
| `desktop/src/PersonalAiWorkspace.Desktop/Hosting/*` / `Bridge/*` | 内容准入/CSP、manifest、敏感数据隔离、原生权限 | 不改、不放宽、无新跨层主题/路由方法 |
| `src/main/java/.../workspace/finance/*` 与 `finance-domain` | 绑定/只读事务、金融事实/计算 | 全部阶段保持不变，不因表格或图表而调整 Java/SQL/schema |

ADR-008/009 是 ownership 与可信内容依据；其中早期页数/DTO 限制属于历史快照，当前 7-route allowlist 和现有金融合同以 owning code 为准。STATUS 中更早的 G3/Finance 快照不得覆盖 F-next-10 源码事实。

## 分阶段交付

每一阶段单独审查、验证和交付；本计划不授权自动连续执行 Phase 2–5。不预先创建规划目录、基础框架或全部页面骨架。

### Phase 1：Shell、导航与设计 Token

**可见结果：** 同一套明暗 Token、清晰工作区导航与可到达的状态/设置入口；宽窄窗口均可导航；Finance 9 个平铺按钮改成分组局部导航/窄窗 select。全部业务页面继续使用现有实现。

文件范围：

- 必改：`desktop/frontend/src/app/App.tsx`、`desktop/frontend/src/styles/workspace.css`、`desktop/frontend/src/pages/FinancePage.tsx`。
- 必要时改：`desktop/frontend/src/app/navigation.ts`；新增 `desktop/frontend/src/styles/tokens.css`，且仅在新增时调整 `desktop/frontend/src/main.tsx` 的 CSS 导入。
- 小组件仅在确能降低 App/FinancePage 重复时增加，例如 `components/WorkspaceNavigation.tsx` 或 `components/FinanceNavigation.tsx`；不要求两者都建，不创建通用可插拔导航系统。
- 按实际需要复用/扩展既有 `pages/MemoryPage.test.tsx` 中的 App 离页用例，以及 `pages/FinancePage.test.tsx` 的 Dashboard 综合流。不为颜色、每个菜单或每个断点新增测试。

实施顺序：

1. 记录当前挂载、hidden、visible/enabled、navigate、leave guard 与 Finance view 切换边界。不要先拆业务组件。
2. 引入设计 Token 和旧变量映射，改 Shell padding/宽度/字体；页面选择器按需限定。避免全局 `section/button/pre` 大范围重置影响领域组件。
3. 全局导航按工作/资料/业务/底部组织，所有入口调用同一 navigate。保留 7 个 hash、默认 assistant、内存偏好。删除装饰性 01–07，不增加假工作区选择器。
4. 增加紧凑/窄 viewport 退化布局，优先原生 select/语义 HTML；如果没有图标库，短文字/完整标签即可，不手绘整套 SVG。
5. Shell 状态仅使用已取得的 ShellStatus；等待/不可用不显示在线绿点，模型未核对不显示 Ready。状态入口转 Settings，不自动检查或加载模型。
6. Finance nav 分组并用原生 select 处理小窗。保留初始 accounts、9 个 view（Budgets 标“来源不支持”）、Dashboard→Assets 与 Position→History 及显式 History 清筛选。
7. 复核旧主题/高对比/局部滚动/错误容器，然后执行最少验证。没有新增依赖，不安装 Tailwind/Radix/Lucide，不修改 package.json/lockfile。

第一阶段不移动 Assistant/Memory/Knowledge 内部表单，不加图表、Markdown、Command Palette、模型切换器或新路由。不做 WPF 标题栏/主题联动，不改变 Finance 的服务端默认、首次读入、卸载或读取次数。

### Phase 2：Assistant 与 Conversations

调整 `pages/OperationPage.tsx`、`pages/ConversationsPage.tsx` 及其局部 CSS，必要时小型呈现组件。Assistant 保留单次 Ask/Summarize；Conversations 保留持久会话与 latest/history 语义。把任务状态、逐轮 Memory、结果与输入分层，列表宽度/窄窗恢复统一。

优先复用既有输入、取消、受控复制与轮询。图标确有需要时可引入 Lucide 正式版并记录完整 LICENSE；复杂弹层确有缺口才评估 Radix。保留现有 ConfirmationDialog，不只为“统一”替换。安全 Markdown 是独立候选，不在本阶段默认引入。

最少证据：按修改范围选择既有 `OperationPage.test.tsx` 的执行/IME/unknown 综合行为与 `ConversationsPage.test.tsx` 的 send/Memory 或 switch 行为；仅选覆盖改变风险的用例，不跑整个 UI suite。一个合成离线页面流检查键盘焦点、长文本和小窗，不发真实模型请求。

### Phase 3：Memory、Knowledge、Translate

调整 `pages/MemoryPage.tsx`、`pages/KnowledgePage.tsx`、`components/KnowledgeSearch.tsx`、`components/KnowledgeAnswer.tsx`、`pages/OperationPage.tsx` 的 Translate 布局及局部 CSS。形成一致列表/详情/编辑区；来源定位沿用已有 revision/locator，不建立新的上下文合并服务。

一个综合流验证 Memory 编辑后导航取消/确认、Knowledge 检索→引用源版本预览、Translate 输入/结果布局。根据实际修改复用既有 Memory/Knowledge/KnowledgeAnswer/Operation 聚焦测试；原生文件选择与备份保持既有流程。没有新语言、格式、检索方式或 Provider 调用。

### Phase 4：Finance 页面呈现

在 Phase 1 的导航上，按真实页面依次改：

1. Accounts + Transactions：表格/筛选/标题/详情统一，保留 kind/id、分页/日期/账户/类型合同。
2. Assets + Positions + Portfolio：账务与参考估值分层，保留来源/缺失/新鲜度/完整性。
3. Dashboard：摘要、六个月精确表、完整分母和 recent 的布局；不增加净资产或金额图表。
4. History + Imports：列表→详情→audit/receipt，复用原分段读取；重要证据不截断。
5. Budgets 只统一能力说明样式。

文件为 `components/FinanceDashboard.tsx`、`FinanceTransactions.tsx`、`FinanceAssets.tsx`、`FinanceInvestments.tsx`、`FinancePortfolio.tsx`、`FinanceHistory.tsx`、`FinanceImports.tsx`、`FinanceBudgets.tsx` 与仍在 FinancePage 内的 AccountPage。不要为排版把 AccountPage 拆成新服务；`components/FinanceAccounts.tsx` 是 Settings 中配置/绑定区域，不能误当主账户页重写。

现有表格足够时不用 TanStack。只有列控制等真实需求出现才锁定正式版按需用；绝不客户端数值聚合。Recharts 暂缓，直到另行批准有界、非权威绘图投影和精确回看方案；当前禁止金额 Number 转换，不能以依赖需求反推修改 Bridge/Java。

复用 `pages/FinancePage.test.tsx` 中已存在的精确字符串/部分覆盖、Receipt/审计、失效快照/迟到响应行为；只执行本切片涉及的流。可将合成大额、null、长 ID、部分分布放进一次表格→详情→来源检查；不把每个字段变成独立执行。金融读取合同没改时不重跑 Java SQL、Desktop 全套或历史 Finance 矩阵。

### Phase 5：Settings、Runtime 与细节收敛

调整 `pages/SettingsPage.tsx`、`components/StatusPanel.tsx`、`StorageSettings.tsx`、`FinanceAccounts.tsx` 及 CSS，分区整理当前真实维护能力。复用 `SettingsPage.test.tsx / StorageSettings.test.tsx` 中实际受影响行为；仍不新增自由 Provider 管理。

原生暗色接缝为单独事项：先检查既有系统资源/主题支持，仅在不改窗口/安全/生命周期时可做局部 XAML 颜色资源。若要 React 主题同步到 WPF，当前无现成合同，应停止该子项并报告需要另行架构决定；不加通用 theme Bridge。宿主文件实际变化才需要聚焦 Desktop 构建/相关测试与一次原生窗口检查。

Command Palette 仍非必需。未来如引入 cmdk，只处理固定导航，复用 navigate/dirty guard，不建立跨域全文搜索、存储或后台索引。

## 替换策略与依赖准入

| 对象 | Phase 1 | 后续策略 |
| --- | --- | --- |
| 全局 Shell | 保留 App 状态，只换 DOM/CSS 的必要外层 | 不建立 page registry/plugin engine |
| Finance nav | 替换顶部 nav；view/render 分支原样 | 局部层次统一，无 route/schema 变化 |
| 按钮、select、textarea | 现有 HTML + Token | 业务验证和事件处理不抽走；不重复造表单框架 |
| Dialog/Drawer | 现有 ConfirmationDialog/HTML dialog | 有真实缺口再用 Radix，先证实 CSP、焦点、滚动锁 |
| Table | 原 table/th/有界滚动 | 真实复杂需求才选 TanStack；服务端分页/精度不变 |
| 图标 | 不强制新依赖 | Lucide 单一图标体系，本地按需包，完整许可声明 |
| 图表 | 精确表保留 | 数值投影未获准前不引入 Recharts |
| 状态提示 | role=status/alert 和已有错误文案 | 不用 toast 替代持久错误/Unknown/Partial |

若未来引入依赖，先读正式 npm 发布的 peer/engine/license，固定确切版本与 lockfile，并查看传递依赖。React 19.3、TS 7.0.2、Vite 8.3.2 是当前本仓库实际值；上游 main 分支/文档版本不是安装证明。Fluent 的 style tag、Radix 的 RemoveScroll/Portal、图表动态样式需在当前 CSP 下验证。验证失败时保留既有控件，报告具体问题；不加 unsafe-inline、远程字体、CDN、任意 HTTP 或传递用户数据到第三方服务。

## 第一阶段最少验证与交付门槛

先复用现有证据和脚本，无最低测试数。以下是建议的最小路径；实现时以实际 diff 决定，不为文档计划机械执行。

| 证据 | 最少做法 | 明确不能替代的证据 |
| --- | --- | --- |
| 差异审查 | 确认只有授权前端呈现文件；`git diff --check` | 不证明运行行为 |
| 构建 | 在 `desktop/frontend` 执行一次 `npm run build`，已含 tsc 和 assets manifest | 不称为 WPF/真实 Windows 验收；无须再单独 npm run check |
| 两项关键回归 | 一次 Vitest 调用选择现有 App Memory leave guard 与 Finance Dashboard 精确/失效快照综合流 | 是替身/DOM 合同证据，不是真实 SQL 或 Provider |
| 布局与键盘 | 一个离线合成数据综合浏览流，在各断点就近取样：7 页可达、Finance 9 入口、主题、长值、离页取消/确认 | 不是 7+9 个独立测试；不用真实金融数据或真实模型 |
| 原生视觉（有可用合成宿主时） | 用既有安全 Development 宿主做一次不发 AI 请求的缩放/焦点/窗口控件检查；记录实际 viewport/DPI/Zoom | 普通浏览器 mock 不能宣称 WebView2/CSP/DPI 通过 |

可复用的聚焦命令（仅在确实改 App/Finance nav 时）：

```powershell
# cwd: D:\IDEA\Daima\personal-ai-workspace\desktop\frontend
npm test -- src/pages/MemoryPage.test.tsx src/pages/FinancePage.test.tsx -t "guards actual App sidebar|keeps dashboard strings"
npm run build
```

这不是要求新增两个测试。若现有综合流已充分覆盖，不新增；若确有导航 guard 绕过等长期风险，只扩展一个最强集成用例。标签文案变化可让定位更语义化，但不能弱化关键断言、接受错误状态或跳过失败。

布局检查聚焦 1920×1080、1440×900、1280×800、1024 宽与 780×540 DIP 下的代表点。Windows DPI 和 WebView Zoom 不做全排列：普通窗口 100%、一个 150% DPI 边界、一个 200% WebView zoom 小 viewport 已能先定位大多数导航问题；如果触发失败，再扩展受影响边界。记录实际值，不能把 CSS 模拟当物理 DPI。

页面 document/body 无横向溢出；长数据只在有标签的表格/代码/值容器横滚，必要时键盘可达。窄窗允许纵向滚动，不隐藏提交/取消/错误或 Settings。light/dark/forced-colors 和键盘 Tab/Esc/焦点回到原控件在同一综合流内检查。

不新建数据库/Profile、不调用真实付费 Provider、不生成正式 Windows 客户端。若当前可用宿主/合成测试环境不足，完成能做的静态/离线验证并精确报告哪项原生验收未执行；不假造 PASS，也不为过关放宽环境或 Native Gate。构建/测试出现环境失败先定位 root cause；真正阻断任务时停止并报告。

## 风险、兼容与回退

| 风险 | 防护与停止条件 |
| --- | --- |
| 新导航旁路 Memory guard | 所有入口复用 navigate；现有 App 综合用例与人工取消/确认；发现旁路先修导航，不改 Memory 保存合同 |
| responsive 组件 remount 导致草稿或请求重建 | 同一元素/组件 identity，用 CSS 和已有 visible；不把同一页面放在两套条件树中 |
| Finance hidden 后留旧数据 | 保留原条件挂载与 generation；不缓存快照、不改为始终挂载 |
| 样式覆盖泄漏 | 使用 Token alias 和局部 class；尤其检查 shared OperationPage、finance-toolbar 和 hidden；不要用 overflow-x:hidden 掩盖问题 |
| 双语/长金额破坏宽度 | flex/grid 子项 min-width:0、ID 换行、金额局部滚动；没有省略证据/转浮点 |
| CSP/第三方组件不兼容 | Phase 1 零依赖；后续单控件门禁失败就保留已有控件，不改 CSP |
| 主题与原生白顶栏不一致 | 记录为宿主阶段待解决，首期不改桥接/标题栏；不声称已完成全宿主暗色 |
| 需求蔓延到深链接、默认页或新能力 | 超出只做文档说明，不顺带实施；未知 Provider/预算/Tools 不展示可用入口 |
| 已有用户修改或远端分叉 | 不覆盖、不 stash、不 clean；任务确实受阻时报告具体文件/refs |

每个阶段的代码可以通过后续明确提交恢复旧呈现，但不自动 reset/rebase/revert，不用全局 feature flag、双套 Shell 或隐藏业务逻辑当回退架构。首期代码与旧页面继续兼容现有 Bridge v1/资源 manifest，没有数据迁移。

## Git 与本轮实际证据

本轮研究分支来自清理基线，预期只提交四个 `docs/design/*.md`。正常 commit/push 已由本轮任务授权；不改 main/Finance 里程碑，不 force push、不 merge/tag/release。最终 SHA、推送与工作树状态以最后实际命令和交付回复为准，本文不写自指的提交 SHA。

本轮已做源码/文档/公开来源读取，以及四份 UTF-8 文档、内部/本地链接、代码围栏和固定上游源码路径的静态核对。设计中的正文/辅助文字/主按钮配色样本静态对比度均高于 4.5:1；这不等于浏览器或完整无障碍验收。

未执行 Java/Desktop/Frontend 测试、编译、打包、真实应用验收、Provider 请求或 SQL。F-next-10 历史 PASS 与本轮研究分别记录；本计划的覆盖点均为待验证。第一阶段可直接使用 [独立 Prompt](WORKSPACE-UI-PHASE-1-PROMPT.md)。
