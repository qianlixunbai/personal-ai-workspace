# Workspace UI Phase 1 — 可直接执行的开发 Prompt

下方完整内容可以复制到下一轮开发任务中。本文件是开发指令草案；生成本文件不代表代码已实施、测试已执行或获得 Production GO。

---

请直接完成 **Personal AI Workspace UI Phase 1：Shell、导航与设计 Token**。默认中文汇报，保守实现、小 diff、最少必要验证。本轮只做第一阶段，不自动连续开发后续阶段。

## 1. 仓库与入场

工作区：`D:\IDEA\Daima\personal-ai-workspace`；GitHub：`qianlixunbai/personal-ai-workspace`。

基础研究分支：`codex/workspace-ui-research`。它应包含以下四份完整研究/设计文档：

- `docs/design/WORKSPACE-UI-OPEN-SOURCE-RESEARCH.md`
- `docs/design/WORKSPACE-UI-DESIGN-DIRECTION.md`
- `docs/design/WORKSPACE-UI-IMPLEMENTATION-PLAN.md`
- `docs/design/WORKSPACE-UI-PHASE-1-PROMPT.md`

研究的生产源码基线是 `codex/workspace-post-fnext10-cleanup @ 28ac441a096af736a5fbc91bb67913e0552c868c`；Finance 历史参考 `f09d1bd1f883db8740cef8336a78e9a3a4123270`。不要把旧 Finance 分支作为本次开发起点。

先读实际 AGENTS.md/仓库约定，核对 HEAD、工作树、已有提交和远端。优先执行：

```powershell
git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 fetch --prune origin
# 仅失败后再用 git fetch --prune origin；不改全局配置
```

确认研究文档实际已在远端/本地一致基线后，从该实际研究 HEAD 创建 `codex/workspace-ui-phase-1`。本 Prompt 明确授权创建该分支、完成下列改动及正常 commit/push；不 force push、amend、rebase、merge main、tag/release。已有同名分支或异常状态先查明；不要覆盖用户修改、stash、clean 或恢复历史资产。真正受阻时停止并报告 refs/文件与原因。

优先读四份文档、`docs/engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md`、`FINANCE-F-NEXT-10-REPORT.md`、`FINANCE-REMAINING-CAPABILITIES.md`、ADR-008/009/010；文档里的历史能力快照以真实 owning code 为准。研究的截图/动态 UI 验证有限，不把上游图片当本应用已验收 UI。

## 2. 目标与设计

采用 **AI Productivity Workspace** 主方向，结合 Windows 的导航秩序。冷灰蓝底、统一语义 Token、克制边界/圆角、中文友好；Assistant 是默认统一 AI 入口，Finance 是重要独立业务区。不要做 ChatGPT clone，也不要把 Finance 塞成整产品首页。

严格以设计文档的字体、Token、尺寸和响应式规则为起点；不是一次重写全部页面。第一阶段不安装组件库，不改 package.json/lockfile，不迁移 Tailwind、不引入 Radix/Lucide/cmdk/Recharts/Markdown 或新的路由器。复杂控件后续优先成熟原语，首期现有 HTML/ConfirmationDialog 继续复用。

## 3. 修改前必须理解

阅读真实文件及有关测试：

- `desktop/frontend/src/app/App.tsx`、`navigation.ts`、`theme.ts`
- `desktop/frontend/src/styles/workspace.css`、`src/main.tsx`
- `desktop/frontend/src/pages/FinancePage.tsx`
- `desktop/frontend/src/pages/MemoryPage.tsx` 与 App 导航离页测试
- `desktop/frontend/src/pages/OperationPage.tsx`、`ConversationsPage.tsx`、`KnowledgePage.tsx`、`SettingsPage.tsx` 的挂载与状态边界
- `desktop/frontend/src/components/FinanceDashboard.tsx` 和 `pages/FinancePage.test.tsx` 的综合流
- `desktop/src/PersonalAiWorkspace.Desktop/MainWorkspaceWindow.xaml(.cs)`
- `desktop/src/PersonalAiWorkspace.Desktop/Hosting/WorkspaceContentPolicy.cs`、`WorkspaceWebViewHost.cs`

特别记录：多数 React 页面以 hidden 保持挂载，Finance/Settings 条件挂载；Memory 有 App 级 leave guard；Finance 初始为 accounts，view 在页内内存中；Position→History 携带 historyPosition，显式点击 History 会清该筛选。不能因响应式树或组件 key 变化破坏这些生命周期。

## 4. 具体实施范围

### A. Shell / Token

- 全局 sidebar、页面标题、边距、按钮/卡片的共同视觉统一；先通过旧变量映射新 Token，避免一次替换全部 CSS。
- 可新增 `src/styles/tokens.css` 并从现有入口导入；若无需新增则留在原 CSS。不要制造通用 theme engine 或工作流目录。
- 展开导航约 216px；大/小窗口边距 24/16px；阅读正文 max 880px，数据页面按可用宽度扩展，目标 max 1680px。不再给全部页面统一 960px 上限。
- 现有主题 readTheme/saveTheme 与 light/dark 行为保留；不新增 localStorage 键、Cookie、个人文本/搜索词缓存或跨 Profile 偏好。
- 顶部状态只显示已取得 ShellStatus 支持的连接/凭据事实；不要固定绿点“在线/已就绪”，不要把 Runtime 可连接当模型 Ready。

### B. 全局导航

- 分组：工作（Assistant / Conversations / Translate）、资料（Memory / Knowledge）、业务（Finance），底部 Settings/状态/主题。
- 保留全部 7 个现有 hash 和默认 Assistant；名称可中文优先并保留英文识别。去掉 01–07 装饰性序号。
- Runtime 状态入口仅跳现有 Settings，不新建 `#/runtime`；不增加任何 host allowlist 或 Bridge 方法。不为状态展示多发模型检查/加载请求。
- 所有入口（包括紧凑/窄窗和状态入口）复用 App navigate / MemoryLeaveGuard；不能直接 setPage、location 或新 Router 绕过。离页取消保留原路由/编辑内容/合理焦点。
- 窄 viewport 可用紧凑短文字栏/明确页面 select；展开/选择入口具有完整可访问名，键盘可达。不要手绘一整套图标或引入图标依赖。
- 侧栏可纵滚，底部入口不被 100vh/sticky 裁掉；同一业务组件保持单一实例，不复制出 desktop/mobile 两套树。

### C. Finance 二级导航（只改导航）

替换当前 9 个平铺按钮，宽窗为局部纵向分组导航，窄窗使用带 label 的原生 select 和 optgroup：

- 总览：Dashboard
- 日常账务：Accounts / Transactions / Assets
- 投资：Investment Positions / Portfolio Summary / Investment History
- 历史记录：Import History
- 能力说明：Budgets · 来源不支持

只驱动原有 view。**初始仍为 accounts**；9 个 view、当前挂载/卸载、原渲染分支、historyPosition 语义与 Dashboard→Assets 全部保留。不得修改 Finance 数据组件、Bridge/DTO/Java/SQL，不新增 hash、持久化 view 或后台预取。

Budgets 保留明确 UNSUPPORTED 说明入口，不做预算表单/进度条/假数据。Import History 只能叫历史/回执，不能误导为可上传/Preview/Confirm。

### D. 响应式与无障碍

断点依据实际 WebView CSS viewport：≥1280 展开全局导航；1024–1279 紧凑；760–1023 简化并单栏；更窄由缩放触发时仍有完整页面 select/菜单。Finance 局部导航在紧凑宽度改 select。具体阈值可为真实文字尺寸小幅调整并说明，不改变宿主最小尺寸。

语义 nav/main/header、skip link、aria-current/label、原焦点保护、forced-colors、prefers-reduced-motion 保留。document/body 不出现横向溢出；允许有标签/可聚焦的表格和代码容器局部横滚，不用全局 overflow-x:hidden 掩盖裁剪。不得通过 ellipsis、缩小到不可读字体或 tooltip 隐藏关键金融证据。

## 5. 允许文件与禁止扩展

核心：`App.tsx`、`workspace.css`、`FinancePage.tsx`；必要时 `navigation.ts`、`main.tsx`、一个 tokens.css 与少量纯呈现导航组件。测试只在已有高价值用例确有缺口时最小扩展，交付报告放 `docs/design/WORKSPACE-UI-PHASE-1-REPORT.md`。

不重构页面业务 hooks/状态、不改其他金融组件内部、不改 Assistant/Conversations/Memory/Knowledge/Translate 功能，不做 WPF 标题栏/深色主题同步或宿主窗口改造。原生白顶栏与网页 dark 的接缝允许作为已知后续项如实报告。

严禁修改 DB/schema/数据迁移、Native Gate、身份绑定、权限、受信 Origin/CSP、资源哈希校验、Runtime 生命周期、Finance 算法、金额字符串、Browser Translate-only 或服务端 truth。React 不访问 Finance 数据库，不新增云端同步或金融写入，不增加第二套 Runtime。

金额/百分比/ID 保留来源字符串，禁止为 UI 使用 Number/parseFloat/toFixed、金额缩写/四舍五入/null→0；不改部分分布完整分母，不把账户余额标为净资产，不掩盖估值不完整、价格时间未知或身份失效。

## 6. 最少必要验证

先看实际 diff，复用现有测试。无最低测试数；不要写颜色快照、每个菜单、每个断点或每个组件的重复测试。

若 App/Finance nav 按上述改变，建议一次聚焦调用复用已有两个关键流：

```powershell
# cwd: D:\IDEA\Daima\personal-ai-workspace\desktop\frontend
npm test -- src/pages/MemoryPage.test.tsx src/pages/FinancePage.test.tsx -t "guards actual App sidebar|keeps dashboard strings"
npm run build
```

build 已包含 tsc 和 assets manifest，不再重复单独 typecheck。依赖不变且环境已有 node_modules 时不机械 npm ci。若旧测试定位受可见文字合理变动影响，可改为语义定位，不能弱化 dirty guard、精确字符串、快照清除或迟到响应断言。

做一个合成离线综合 UI 流：遍历顶级页面与 Finance 入口、主题切换、键盘导航、Memory 离页取消/确认、长中英文/长 ID/精确金额与局部滚动。覆盖 1920×1080、1440×900、1280×800、1024 宽及小 viewport 的代表点；同一流多覆盖点不计作多次独立测试。不调用真实 Provider、不读取/上传真实金融数据，不新建数据库/Profile。

如可使用既有受信合成 Development 宿主，做一次不发 AI 请求的真实 WPF/WebView2 视觉/缩放/焦点检查，记录实际 viewport、DPI 与 ZoomFactor；若无环境则明确记为未执行，不能把浏览器替身称为 Windows 验收。不生成正式 Windows 客户端、installer 或新分发 ZIP，不全量跑 Java/Desktop/Frontend/浏览器/IME 矩阵。

任何失败先定位 root cause；环境/fixture/harness 问题在对应层解决。若安全环境、仓库状态或权限真正阻断，停止并报告，不广泛修生产代码，不放宽 gate/CSP，不 @Disabled 或吞异常。

## 7. Git 与交付

完成后检查 git status、实际 diff、staged diff 与 git diff --check；只 stage 本轮前端呈现/必要聚焦测试/报告，正常 commit/push `codex/workspace-ui-phase-1`。不发布 main，不混入历史备份/截图/金融数据。推送失败报告准确原因，不 force push。

最终简洁说明：可见变化、实际文件、执行过的验证、未执行的原生/DPI 验收及已知接缝、branch/完整 SHA、远端/工作树状态。严格区分源码静态核对、编译、DOM 替身、真实原生、历史证据和未执行项；不把本轮 UI 阶段称为 Release/Production GO。

请直接落实第一阶段并交付；不要只回复计划，也不要扩大为全页面重设计。
