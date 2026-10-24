# Workspace UI 开源研究

2026-10-24，Asia/Shanghai。研究与设计提案；不是已发布 UI，也不改变 Development-only / Production GO 状态。

## 结论

推荐 **AI Productivity Workspace** 为主方向，采用 Windows 熟悉的导航与窗口秩序，以统一本地 CSS Token 串联现有 React 页面。Finance 在自身工作区使用分组导航和高密度表格，不占据整个应用首页。前五个最有价值的参考为 **Cherry Studio、WPF UI、Actual Budget、AnythingLLM、Radix Primitives**；选择的是各自适合的模式，不拼装五套皮肤。

本轮实质考察 **16 个项目：Windows 3、AI 3、Finance 3、React 组件/工具 7**。每个项目核对仓库、有关实现、LICENSE、维护元数据及官方界面资料。直接组件建议为按需 Radix、Lucide，复杂表格候选 TanStack Table；第一阶段保留既有 HTML 控件和 CSS，不必新增依赖。Recharts 暂缓，原因是现有金融精确字符串合同与数值绘图之间尚无获准的展示投影。

关联交付：[设计方向](WORKSPACE-UI-DESIGN-DIRECTION.md)、[实施计划](WORKSPACE-UI-IMPLEMENTATION-PLAN.md)、[第一阶段 Prompt](WORKSPACE-UI-PHASE-1-PROMPT.md)。

## 研究基线与证据边界

- 入场分支 `codex/workspace-post-fnext10-cleanup`，HEAD `28ac441a096af736a5fbc91bb67913e0552c868c`；工作树干净。
- 按指定代理执行 `fetch --prune origin` 成功，远端清理基线相同；创建独立 `codex/workspace-ui-research`。Finance 参考基线为 `f09d1bd1f883db8740cef8336a78e9a3a4123270`，没有修改该分支。
- 已读 [清理报告](../engineering/WORKSPACE-POST-FNEXT10-CLEANUP-REPORT.md)、[剩余能力](../engineering/FINANCE-REMAINING-CAPABILITIES.md)、[F-next-10](../engineering/FINANCE-F-NEXT-10-REPORT.md)、[当前架构](../architecture/current-architecture.md)、ADR-008/009/010，以及真实 React/WPF/Bridge owning code。旧架构文档有历史快照，以当前源码及新阶段报告为准。
- 发现使用 GitHub 搜索、Firecrawl 和内置网页搜索；源码/许可证由 GitHub API 与文件读取核对。筛选依据是职责、维护与适配成本，不以 Star 排名。
- 截图证据主要是官方 README 中的真实资源、官方带标注文档、内置图像搜索返回的官方图像资料及布局源码；没有运行上述应用。部分图片二进制查看不可用，无头 Edge 截图启动被自动审批以 `blocked by policy` 拒绝；没有绕过审批或下载图片来规避显示限制。**不宣称已逐像素审查全部 16 个项目，也不把静态图当动态交互验收。** Cherry 当前截图资源另经公开读取返回 HTTP 200；图片/文档可能早于当前源码。
- Ghostfolio demo 仅取得 JavaScript 提示；Fluent Storybook 文本有 No Preview；TanStack 旧 pagination 文档 URL 返回 404。改用已取得的实现与官方 README，相关动态行为保持未验证。Fluent 全树一次传输失败后改用精确目录/文件，未以失败读取作结论。
- 没有上传本地源码/用户内容到设计服务，没有真实 Provider 调用、数据库创建、运行应用、组件安装、生产改码、构建或测试执行。原 F-next-10 测试仅为历史证据，不计本轮执行。

## 总览与评分

分数为本项目上下文下的设计参考价值 / 直接代码或依赖复用价值，各 1–5；不是产品质量排行。高设计分可以与低复用分同时成立。兼容性考虑 React/CSS、严格 CSP、Native ownership、金融语义、许可及维护负担；安全/许可未解决时不能凭高分自动引入。所有下列仓库查询时均未 archived。

| # | 项目 | 类别 | 正文核对后的许可证 | 最近推送 UTC | 设计 / 复用 |
| --- | --- | --- | --- | --- | --- |
| 01 | [WPF UI](https://github.com/lepoco/wpfui) | Windows | MIT | 2026-06-27 13:58:28 | 5 / 2 |
| 02 | [Files](https://github.com/files-community/Files) | Windows | MIT + 文件级 MPL-2.0 | 2026-10-22 22:35:34 | 4 / 1 |
| 03 | [Microsoft PowerToys](https://github.com/microsoft/PowerToys) | Windows | MIT | 2026-10-24 12:47:28 | 4 / 1 |
| 04 | [Cherry Studio](https://github.com/CherryHQ/cherry-studio) | AI | AGPL-3.0 | 2026-10-24 15:01:08 | 5 / 1 |
| 05 | [Chatbox Community Edition](https://github.com/chatboxai/chatbox) | AI | GPL-3.0 | 2026-09-24 14:09:13 | 4 / 1 |
| 06 | [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) | AI | MIT | 2026-10-24 01:34:32 | 5 / 2 |
| 07 | [Actual Budget](https://github.com/actualbudget/actual) | Finance | MIT | 2026-10-23 12:53:21 | 5 / 2 |
| 08 | [Ghostfolio](https://github.com/ghostfolio/ghostfolio) | Finance | AGPL-3.0 | 2026-10-24 11:10:28 | 4 / 1 |
| 09 | [Firefly III](https://github.com/firefly-iii/firefly-iii) | Finance | AGPL-3.0 | 2026-10-24 15:12:56 | 3 / 1 |
| 10 | [Fluent UI React v9](https://github.com/microsoft/fluentui) | Components | MIT；fonts/icons 另有条款 | 2026-10-23 17:11:45 | 4 / 2 |
| 11 | [shadcn/ui](https://github.com/shadcn-ui/ui) | Components | MIT | 2026-10-24 13:43:50 | 4 / 3 |
| 12 | [Radix Primitives](https://github.com/radix-ui/primitives) | Components | MIT | 2026-10-14 21:52:47 | 5 / 4 |
| 13 | [TanStack Table](https://github.com/TanStack/table) | Components | MIT | 2026-10-21 14:02:33 | 4 / 3 |
| 14 | [Recharts](https://github.com/recharts/recharts) | Components | MIT | 2026-10-24 13:31:05 | 4 / 2 |
| 15 | [Lucide](https://github.com/lucide-icons/lucide) | Components | ISC；部分 Feather 派生图标为 MIT | 2026-10-23 16:52:19 | 5 / 4 |
| 16 | [cmdk](https://github.com/dip/cmdk) | Components | MIT | 2025-10-29 04:13:58 | 3 / 3 |

推送日期取 [GitHub repository API](https://docs.github.com/en/rest/repos/repos#get-a-repository) 的 `pushed_at`，不是页面 `updated_at`；可能包含非默认分支或自动化更新。下面固定默认分支快照供复查，因此推送日期可以晚于快照提交日期。

| 项目 | 查询时默认分支 | 读取快照 |
| --- | --- | --- |
| WPF UI | `main` | [`ffebacd61058`](https://github.com/lepoco/wpfui/commit/ffebacd61058170cf63864b7d5aa730cffff848a) |
| Files | `main` | [`ee399b0d3e09`](https://github.com/files-community/Files/commit/ee399b0d3e09084aa24d7261c9342d92bc89a5da) |
| Microsoft PowerToys | `main` | [`a5e83e5e2472`](https://github.com/microsoft/PowerToys/commit/a5e83e5e2472647395c4f1fa455433a31cf0d4a1) |
| Cherry Studio | `main` | [`8c6f9045f427`](https://github.com/CherryHQ/cherry-studio/commit/8c6f9045f4276caf36a2f82d182871df11acecd9) |
| Chatbox Community Edition | `main` | [`0ac6385ae1ff`](https://github.com/chatboxai/chatbox/commit/0ac6385ae1ff5bf778777826da3c5edcd55b61b8) |
| AnythingLLM | `master` | [`a5bb8b9c7b12`](https://github.com/Mintplex-Labs/anything-llm/commit/a5bb8b9c7b1262635ea58fc17d4899619cbf58e9) |
| Actual Budget | `master` | [`97a7ca50f310`](https://github.com/actualbudget/actual/commit/97a7ca50f3109b37e5294abfcec8f763df397085) |
| Ghostfolio | `main` | [`7429176b44d6`](https://github.com/ghostfolio/ghostfolio/commit/7429176b44d6bde31baf3b02acecef1a9cbaaf06) |
| Firefly III | `main` | [`b9c31006e8d3`](https://github.com/firefly-iii/firefly-iii/commit/b9c31006e8d3008df3d12e23de86b66088c9480e) |
| Fluent UI React v9 | `master` | [`6d3e9d686434`](https://github.com/microsoft/fluentui/commit/6d3e9d686434d69ac8fc9ca4c4a93f3afaa3d676) |
| shadcn/ui | `main` | [`ffd225775bbb`](https://github.com/shadcn-ui/ui/commit/ffd225775bbb7955a673f08bb9be4a63600d9dfb) |
| Radix Primitives | `main` | [`eb3a2b709129`](https://github.com/radix-ui/primitives/commit/eb3a2b7091293e27df8f9ba906632d8daa3d81a3) |
| TanStack Table | `main` | [`c14e0221b1e6`](https://github.com/TanStack/table/commit/c14e0221b1e656c7bb35e7b3d4cdf188719a3599) |
| Recharts | `main` | [`491ab237c63d`](https://github.com/recharts/recharts/commit/491ab237c63dbab6a7588bd6270ce16167c828bf) |
| Lucide | `main` | [`7faf87628a1a`](https://github.com/lucide-icons/lucide/commit/7faf87628a1ad6175975bc595344337ce14a7db4) |
| cmdk | `main` | [`dd2250ed6084`](https://github.com/dip/cmdk/commit/dd2250ed608443e8f32bafc5fa2d1d07a3746aa3) |

## 01. WPF UI

- **用途与技术栈：** Windows 桌面参考；C# / WPF / XAML。仓库：[lepoco/wpfui](https://github.com/lepoco/wpfui)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/lepoco/wpfui/blob/ffebacd61058170cf63864b7d5aa730cffff848a/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-06-27T13:58:28Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://user-images.githubusercontent.com/13592821/166259110-0fb98120-fe34-4e6d-ab92-9f72ad7113c3.png)。官方 Gallery 截图和 NavigationView 文档；源码含可折叠 pane、header、footer、TitleBar 与 Mica。
- **实际源码：** [Windows/MainWindow.xaml](https://github.com/lepoco/wpfui/blob/ffebacd61058170cf63864b7d5aa730cffff848a/src/Wpf.Ui.Gallery/Views/Windows/MainWindow.xaml)；[documentation/navigation-view.md](https://github.com/lepoco/wpfui/blob/ffebacd61058170cf63864b7d5aa730cffff848a/docs/documentation/navigation-view.md)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴主导航与维护入口分组、页面标题和原生窗口层次；不要把 Gallery 首页的大横幅带入日常工作区。
- **兼容性：** 宿主技术最接近；React 内容仍由 WebView2 承载，不能把 XAML NavigationView 直接当 React 组件。
- **依赖/复用成本：** 只借交互成本低；新增 NuGet、换 FluentWindow、自绘标题栏和主题联动成本高，须独立宿主阶段。
- **不适用部分：** 不替换当前 Window、Snap/窗口命中测试和 Native Gate；Segoe 字体/图标资产并非全部随 MIT 自动授权。
- **评分与处置：** 设计适用 / 直接复用 = **5 / 2**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 02. Files

- **用途与技术栈：** Windows 桌面参考；C# / WinUI XAML；不是 WPF。仓库：[files-community/Files](https://github.com/files-community/Files)。
- **许可证：** MIT + 文件级 MPL-2.0，已读 [许可证正文](https://github.com/files-community/Files/blob/ee399b0d3e09084aa24d7261c9342d92bc89a5da/LICENSE-MIT)。[MPL 正文](https://github.com/files-community/Files/blob/ee399b0d3e09084aa24d7261c9342d92bc89a5da/LICENSE-MPL) 与所读 SettingsPage 文件头需一起看。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-22T22:35:34Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://files.community/)。官网有 tabs、双栏、预览窗格和设置截图；SettingsPage 的 SidebarView 使用独立导航与可滚动内容。
- **实际源码：** [Files.App/MainWindow.xaml](https://github.com/files-community/Files/blob/ee399b0d3e09084aa24d7261c9342d92bc89a5da/src/Files.App/MainWindow.xaml)；[Settings/SettingsPage.xaml](https://github.com/files-community/Files/blob/ee399b0d3e09084aa24d7261c9342d92bc89a5da/src/Files.App/Views/Settings/SettingsPage.xaml)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴“稳定主导航＋局部详情/预览”、设置分组及窄窗折叠；多栏各有职责。
- **兼容性：** 布局思路可迁入 WebView；WinUI 控件和文件管理生命周期不能搬入当前 WPF。
- **依赖/复用成本：** 视觉/交互参考低；实际代码迁移高且不必要。API 显示 MIT，但 SettingsPage 文件头明确 MPL-2.0，必须逐文件看授权。
- **不适用部分：** 不引入文件系统浏览器、云盘、标签页池或第二套窗口生命周期；不把多标签误当已实现多工作区。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 1**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 03. Microsoft PowerToys

- **用途与技术栈：** Windows 桌面参考；多工具 Windows 仓库；所查 Settings 为 C# / WinUI XAML。仓库：[microsoft/PowerToys](https://github.com/microsoft/PowerToys)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/microsoft/PowerToys/blob/a5e83e5e2472647395c4f1fa455433a31cf0d4a1/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T12:47:28Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://learn.microsoft.com/en-us/windows/powertoys/general)。官方 General 文档有设置截图、搜索、主题和托盘说明；ShellPage 使用导航和搜索建议模板。
- **实际源码：** [Views/ShellPage.xaml](https://github.com/microsoft/PowerToys/blob/a5e83e5e2472647395c4f1fa455433a31cf0d4a1/src/settings-ui/Settings.UI/SettingsXAML/Views/ShellPage.xaml)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 设置页按任务分区、状态与动作就近、原生维护动作有明确名称；Runtime 设置可借鉴这种信息层级。
- **兼容性：** Windows 习惯吻合，但其 WinUI 设置宿主不等于 WPF/WebView2 混合方案。
- **依赖/复用成本：** 模式参考低；移植设置框架、原生搜索和工具发现机制高。
- **不适用部分：** 不照搬管理员提升、自动更新、诊断上传或全部工具菜单；本产品没有这些授权。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 1**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 04. Cherry Studio

- **用途与技术栈：** AI 客户端/工作区参考；Electron / React / TypeScript；所查 AppShell、PageSidebar。仓库：[CherryHQ/cherry-studio](https://github.com/CherryHQ/cherry-studio)。
- **许可证：** AGPL-3.0，已读 [许可证正文](https://github.com/CherryHQ/cherry-studio/blob/8c6f9045f4276caf36a2f82d182871df11acecd9/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T15:01:08Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/CherryHQ/cherry-studio/blob/8c6f9045f4276caf36a2f82d182871df11acecd9/.github/images/readme/chat-light-en.webp)。README 分列 Agent、Chat、Provider、Knowledge 官方截图；PageSidebar 有宽度边界、隐藏时 inert 和 splitter 可访问性。
- **实际源码：** [layout/AppShell.tsx](https://github.com/CherryHQ/cherry-studio/blob/8c6f9045f4276caf36a2f82d182871df11acecd9/src/renderer/components/layout/AppShell.tsx)；[shell/PageSidebar.tsx](https://github.com/CherryHQ/cherry-studio/blob/8c6f9045f4276caf36a2f82d182871df11acecd9/src/renderer/components/chat/shell/PageSidebar.tsx)；[package.json](https://github.com/CherryHQ/cherry-studio/blob/8c6f9045f4276caf36a2f82d182871df11acecd9/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴“全局功能导航—会话/资料列表—主内容”分工、Provider 与聊天分离、来源区按需展开。
- **兼容性：** React 呈现思路适合；Electron IPC、tabs 池、持久偏好和 AI 引擎不能替换当前 typed Bridge / Shared Runtime。
- **依赖/复用成本：** 交互参考低到中；复制 AGPL 组件、动画、路由与状态体系成本高。本轮不复制代码。
- **不适用部分：** 不显示未实现的 Agent/Tools，不开放 API Key 输入、不自动载入全部 Memory/Knowledge；不复用其 tab/IPC/数据持久化。
- **评分与处置：** 设计适用 / 直接复用 = **5 / 1**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 05. Chatbox Community Edition

- **用途与技术栈：** AI 客户端/工作区参考；Electron / React / TypeScript；当前侧栏混用 Mantine、MUI、Radix。仓库：[chatboxai/chatbox](https://github.com/chatboxai/chatbox)。
- **许可证：** GPL-3.0，已读 [许可证正文](https://github.com/chatboxai/chatbox/blob/0ac6385ae1ff5bf778777826da3c5edcd55b61b8/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-09-24T14:09:13Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/chatboxai/chatbox/blob/0ac6385ae1ff5bf778777826da3c5edcd55b61b8/doc/statics/snapshot_light.png)。README 有浅/深主题截图；Sidebar 实现会话列表、小屏 drawer、宽度 200–500 的拖动边界。
- **实际源码：** [renderer/Sidebar.tsx](https://github.com/chatboxai/chatbox/blob/0ac6385ae1ff5bf778777826da3c5edcd55b61b8/src/renderer/Sidebar.tsx)；[package.json](https://github.com/chatboxai/chatbox/blob/0ac6385ae1ff5bf778777826da3c5edcd55b61b8/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴会话列表的稳定入口、历史与输入区的层次、小窗隐藏列表后的恢复入口。
- **兼容性：** 对 Conversations 参考价值高；其 React 18 相关配置和多 UI 库并存不应成为本项目模板。
- **依赖/复用成本：** 布局参考低；复制 GPL 代码及依赖树成本高。当前 Workspace 不需要可拖动侧栏。
- **不适用部分：** 不照搬 tracking、移动端分支、云服务订阅或图片生成入口；不要同时引入 Mantine + MUI + Radix。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 1**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 06. AnythingLLM

- **用途与技术栈：** AI 客户端/工作区参考；React JSX / Tailwind 前端、Node 服务；README 同时描述 Desktop 与部署方式。仓库：[Mintplex-Labs/anything-llm](https://github.com/Mintplex-Labs/anything-llm)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/Mintplex-Labs/anything-llm/blob/a5bb8b9c7b1262635ea58fc17d4899619cbf58e9/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T01:34:32Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/Mintplex-Labs/anything-llm#readme)。README 链接官方演示 GIF；Sidebar 为可折叠工作区导航，Citation 将来源切换到 SourcesSidebar。
- **实际源码：** [Sidebar/index.jsx](https://github.com/Mintplex-Labs/anything-llm/blob/a5bb8b9c7b1262635ea58fc17d4899619cbf58e9/frontend/src/components/Sidebar/index.jsx)；[Citation/index.jsx](https://github.com/Mintplex-Labs/anything-llm/blob/a5bb8b9c7b1262635ea58fc17d4899619cbf58e9/frontend/src/components/WorkspaceChat/ChatContainer/ChatHistory/Citation/index.jsx)；[frontend/package.json](https://github.com/Mintplex-Labs/anything-llm/blob/a5bb8b9c7b1262635ea58fc17d4899619cbf58e9/frontend/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 最值得借鉴 Knowledge 的“回答—引用—源预览”连贯流程，以及来源按需展示，而非把完整原文堆在聊天中。
- **兼容性：** 呈现可参考；其向量/RAG、Agent、自动记忆、浏览器存储与 Workspace 的词法检索和逐次明确 Memory 权限不同。
- **依赖/复用成本：** MIT 允许按条件复用，仍需拆解 hooks、API、路由、Tailwind 与 React 18 耦合；重写少量呈现通常比搬整页低。
- **不适用部分：** 不迁入 embedding/向量数据库、自动 Memory 提取、后台 Agent 或第二套 AI Runtime；文档正文不进入全局搜索索引。
- **评分与处置：** 设计适用 / 直接复用 = **5 / 2**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 07. Actual Budget

- **用途与技术栈：** 金融业务界面参考；TypeScript / React；desktop-client 与 Electron、loot-core 分包。仓库：[actualbudget/actual](https://github.com/actualbudget/actual)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/actualbudget/actual/blob/97a7ca50f3109b37e5294abfcec8f763df397085/LICENSE.txt)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-23T12:53:21Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://actualbudget.org/docs/tour/accounts/)。官方 Account Register 文档有带标注的账户标题、筛选、表格截图；仓库另有 desktop-electron/screenshots 的浅/深流水页。
- **实际源码：** [sidebar/Sidebar.tsx](https://github.com/actualbudget/actual/blob/97a7ca50f3109b37e5294abfcec8f763df397085/packages/desktop-client/src/components/sidebar/Sidebar.tsx)；[transactions/TransactionsTable.tsx](https://github.com/actualbudget/actual/blob/97a7ca50f3109b37e5294abfcec8f763df397085/packages/desktop-client/src/components/transactions/TransactionsTable.tsx)；[desktop-client/package.json](https://github.com/actualbudget/actual/blob/97a7ca50f3109b37e5294abfcec8f763df397085/packages/desktop-client/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴标题/余额/筛选/流水的纵向顺序、右对齐金额、筛选条件可见、列表密度；不把每个数值都做成大卡片。
- **兼容性：** Finance 列表的最佳模式参考；其预算与可编辑交易不是 Workspace 现有只读能力。
- **依赖/复用成本：** 模式参考低；TransactionsTable 耦合核心实体、整数金额工具、批量写操作和表格导航，直接移植高。
- **不适用部分：** 不采用其整数金额换算、账户计算、预算或同步方案；不复制 Import/Add New/批量修改按钮；不能推导本项目已有预算。
- **评分与处置：** 设计适用 / 直接复用 = **5 / 2**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 08. Ghostfolio

- **用途与技术栈：** 金融业务界面参考；TypeScript / Angular Material / NestJS / PostgreSQL。仓库：[ghostfolio/ghostfolio](https://github.com/ghostfolio/ghostfolio)。
- **许可证：** AGPL-3.0，已读 [许可证正文](https://github.com/ghostfolio/ghostfolio/blob/7429176b44d6bde31baf3b02acecef1a9cbaaf06/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T11:10:28Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/ghostfolio/ghostfolio/blob/7429176b44d6bde31baf3b02acecef1a9cbaaf06/apps/client/src/assets/images/screenshot.png)。README 提供截图与 live demo；demo 文本抓取仅返回需 JavaScript。已查 performance 模板含 skeleton、币种和值及数据 Provider 异常提示。
- **实际源码：** [home/home-page.html](https://github.com/ghostfolio/ghostfolio/blob/7429176b44d6bde31baf3b02acecef1a9cbaaf06/apps/client/src/app/pages/home/home-page.html)；[portfolio-performance/portfolio-performance.component.html](https://github.com/ghostfolio/ghostfolio/blob/7429176b44d6bde31baf3b02acecef1a9cbaaf06/apps/client/src/app/components/portfolio-performance/portfolio-performance.component.html)；[package.json](https://github.com/ghostfolio/ghostfolio/blob/7429176b44d6bde31baf3b02acecef1a9cbaaf06/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴投资视图的期间/币种上下文、估值与收益分层、数据源故障显式展示。
- **兼容性：** Portfolio Summary 的视觉参考；Angular 组件不兼容 React，金融估值口径也不相同。
- **依赖/复用成本：** 交互参考低；AGPL + Angular + 估值服务的实际代码复用成本很高。
- **不适用部分：** 不显示全局净资产、收益曲线或行情实时性；不把数据源失败仅画成图表空白，不抄其百分比/估值算法。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 1**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 09. Firefly III

- **用途与技术栈：** 金融业务界面参考；PHP / Laravel / Blade；不是 React UI 库。仓库：[firefly-iii/firefly-iii](https://github.com/firefly-iii/firefly-iii)。
- **许可证：** AGPL-3.0，已读 [许可证正文](https://github.com/firefly-iii/firefly-iii/blob/b9c31006e8d3008df3d12e23de86b66088c9480e/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T15:12:56Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/firefly-iii/firefly-iii/blob/b9c31006e8d3008df3d12e23de86b66088c9480e/.github/assets/img/imac-complete.png)。README 有桌面截图和官方 demo；查阅分组交易列表 Blade，包含金额/交易组/详情链接，而非只有统计卡片。
- **实际源码：** [lists/groups-large.blade.php](https://github.com/firefly-iii/firefly-iii/blob/b9c31006e8d3008df3d12e23de86b66088c9480e/resources/views/components/lists/groups-large.blade.php)；[composer.json](https://github.com/firefly-iii/firefly-iii/blob/b9c31006e8d3008df3d12e23de86b66088c9480e/composer.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴 Finance 导航的领域分组、期间上下文、从摘要进入交易明细的路径。
- **兼容性：** 功能分区适用；其预算、净资产、银行同步等功能不能作为 Workspace 当前功能清单。
- **依赖/复用成本：** 视觉参考低；模板/后端/金融领域迁移极高，AGPL 代码不纳入候选直接复用。
- **不适用部分：** 不移植全彩 Dashboard 卡片、金融写入表单和规则引擎；避免用其净资产概念替换当前账户余额。
- **评分与处置：** 设计适用 / 直接复用 = **3 / 1**（满分各 5，主观工程判断）。本轮只作视觉/交互参考，不迁入应用代码。

## 10. Fluent UI React v9

- **用途与技术栈：** 组件/工具候选；React / TypeScript / Griffel；v8/v9 共仓需区分。仓库：[microsoft/fluentui](https://github.com/microsoft/fluentui)。
- **许可证：** MIT；fonts/icons 另有条款，已读 [许可证正文](https://github.com/microsoft/fluentui/blob/6d3e9d686434d69ac8fc9ca4c4a93f3afaa3d676/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-23T17:11:45Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://react.fluentui.dev/?path=/docs/concepts-introduction--docs)。官方 Storybook 提供 v9 组件/主题说明；本次页面文本有 No Preview，不能宣称实际操作成功。源码核对 Button styles 与 Provider theme style tag。
- **实际源码：** [react-components/package.json](https://github.com/microsoft/fluentui/blob/6d3e9d686434d69ac8fc9ca4c4a93f3afaa3d676/packages/react-components/react-components/package.json)；[FluentProvider/useFluentProviderThemeStyleTag.ts](https://github.com/microsoft/fluentui/blob/6d3e9d686434d69ac8fc9ca4c4a93f3afaa3d676/packages/react-components/react-provider/library/src/components/FluentProvider/useFluentProviderThemeStyleTag.ts)；[Button/useButtonStyles.styles.ts](https://github.com/microsoft/fluentui/blob/6d3e9d686434d69ac8fc9ca4c4a93f3afaa3d676/packages/react-components/react-button/library/src/components/Button/useButtonStyles.styles.ts)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** Token 的语义命名、状态设计、键盘/屏幕阅读器规范及 Windows 信息层级。
- **兼容性：** React peer 范围覆盖 19；但 Provider 创建 style 标签、Button 使用 Griffel，与当前 style-src self 的兼容性需验证。
- **依赖/复用成本：** 完整采用会带来样式构建、Provider、主题与组件迁移成本；本轮不建议为外观调整 CSP 或新增 nonce 合同。
- **不适用部分：** 不同时装 v8/v9，不宣称 WPF 采用 Fluent 就能共享 React 组件；字体/图标授权独立核查。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 2**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 11. shadcn/ui

- **用途与技术栈：** 组件/工具候选；React / TypeScript / Tailwind；当前提供 Radix / Base UI / React Aria 多种基座。仓库：[shadcn-ui/ui](https://github.com/shadcn-ui/ui)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/shadcn-ui/ui/blob/ffd225775bbb7955a673f08bb9be4a63600d9dfb/LICENSE.md)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T13:43:50Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://ui.shadcn.com/docs/components/radix/sidebar)。官方文档有 Sidebar 浅/深截图、结构和折叠说明；实际读 Radix base sidebar.tsx，不把它等同于一个黑盒 npm UI 库。
- **实际源码：** [ui/sidebar.tsx](https://github.com/shadcn-ui/ui/blob/ffd225775bbb7955a673f08bb9be4a63600d9dfb/apps/v4/registry/bases/radix/ui/sidebar.tsx)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 借鉴 Sidebar Header/Content/Footer、分组和 Sheet 的组成方式；清晰的组件边界可作为复杂控件包装参考。
- **兼容性：** 源代码可改，但当前项目没有 Tailwind；源码默认写 sidebar_state Cookie，并注册全局 Ctrl+B，不能直接套用。
- **依赖/复用成本：** 整套导入会新增 Tailwind/工具类约定及源代码维护责任。按需独立评估单个组件可行，Shell 不需要 CLI 生成整套模板。
- **不适用部分：** 不运行自动 add-all，不复制 Cookie、持久化或全局快捷键；不混用三个 base，不照搬 SaaS/team/billing 导航。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 3**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 12. Radix Primitives

- **用途与技术栈：** 组件/工具候选；无预设皮肤的 React / TypeScript primitives。仓库：[radix-ui/primitives](https://github.com/radix-ui/primitives)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/radix-ui/primitives/blob/eb3a2b7091293e27df8f9ba906632d8daa3d81a3/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-14T21:52:47Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://www.radix-ui.com/primitives/docs/components/dialog)。官方 Dialog 示例和键盘表说明 focus trap、Esc、触发器焦点返回；源码组合 FocusScope、DismissableLayer、Portal、RemoveScroll。
- **实际源码：** [src/dialog.tsx](https://github.com/radix-ui/primitives/blob/eb3a2b7091293e27df8f9ba906632d8daa3d81a3/packages/react/dialog/src/dialog.tsx)；[dialog/package.json](https://github.com/radix-ui/primitives/blob/eb3a2b7091293e27df8f9ba906632d8daa3d81a3/packages/react/dialog/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 复杂弹层/菜单优先复用成熟焦点和可访问性原语，以同一组本地 CSS Token 着色。
- **兼容性：** 不要求 Tailwind，适合当前 CSS；但 Portal、滚动锁、动态 style/CSP 仍须用选定发布版验证。
- **依赖/复用成本：** 单控件依赖成本中，低于整套 UI 框架；原 ConfirmationDialog 已可工作，不能仅为了统一而立即替换。
- **不适用部分：** 不把 headless 理解为零风险或自动无障碍合规；不将 React Dialog 用于替代 WPF 凭据/金融绑定/模型确认。
- **评分与处置：** 设计适用 / 直接复用 = **5 / 4**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 13. TanStack Table

- **用途与技术栈：** 组件/工具候选；TypeScript headless table core + React adapter。仓库：[TanStack/table](https://github.com/TanStack/table)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/TanStack/table/blob/c14e0221b1e656c7bb35e7b3d4cdf188719a3599/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-21T14:02:33Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/TanStack/table/blob/c14e0221b1e656c7bb35e7b3d4cdf188719a3599/examples/react/basic-use-table/src/main.tsx)。实际读 basic-use-table 例子：columns、row model 和语义 table 由应用组合；所查主干 manifest 是 9.3.1，不套用旧 v8 示例。
- **实际源码：** [src/main.tsx](https://github.com/TanStack/table/blob/c14e0221b1e656c7bb35e7b3d4cdf188719a3599/examples/react/basic-use-table/src/main.tsx)；[react-table/package.json](https://github.com/TanStack/table/blob/c14e0221b1e656c7bb35e7b3d4cdf188719a3599/packages/react-table/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 将列定义/可见性/分页状态与视觉皮肤分离，可用于后续确有需要的复杂流水表。
- **兼容性：** 金额单元格必须只返回原字符串；排序、筛选、cursor 保持现有服务端合同，不启用客户端金融聚合。
- **依赖/复用成本：** 现有每页有界表格不足以证明需要新增它。若采用需锁发布版、适配该版 API，并补齐应用键盘行为。
- **不适用部分：** 不照抄示例 devtools/debug 日志、不加全量数据拉取、虚拟化或金额 Number 转换；headless 不自带完整 a11y 表格。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 3**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 14. Recharts

- **用途与技术栈：** 组件/工具候选；React / TypeScript / SVG / D3 相关工具。仓库：[recharts/recharts](https://github.com/recharts/recharts)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/recharts/recharts/blob/491ab237c63dbab6a7588bd6270ce16167c828bf/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-24T13:31:05Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://recharts.github.io/en-US/examples/)。官方示例库涵盖 Bar/Line/Tooltip/ResponsiveContainer；实际读 ResponsiveContainer 及其显式尺寸属性。主干为 3.11.0-canary.7。
- **实际源码：** [component/ResponsiveContainer.tsx](https://github.com/recharts/recharts/blob/491ab237c63dbab6a7588bd6270ce16167c828bf/src/component/ResponsiveContainer.tsx)；[package.json](https://github.com/recharts/recharts/blob/491ab237c63dbab6a7588bd6270ce16167c828bf/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 未来月收支的配色、图例、响应式尺寸和 tooltip 信息结构可参考；图表应辅以精确表格。
- **兼容性：** 图表坐标最终需要有限数值，当前金额合同明确不得转 Number；没有已批准的显示投影前，本轮不能推荐直接画金额图。
- **依赖/复用成本：** 库本身可复用，金融字符串投影和严格 CSP 的验证成本中到高；目前表格完全保留。
- **不适用部分：** 不安装 canary、不将 null 填 0、不把部分资产列表重新归一化成 100% 饼图，不用曲线暗示实时/预测能力。
- **评分与处置：** 设计适用 / 直接复用 = **4 / 2**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 15. Lucide

- **用途与技术栈：** 组件/工具候选；SVG 图标集及 React adapter。仓库：[lucide-icons/lucide](https://github.com/lucide-icons/lucide)。
- **许可证：** ISC；部分 Feather 派生图标为 MIT，已读 [许可证正文](https://github.com/lucide-icons/lucide/blob/7faf87628a1ad6175975bc595344337ce14a7db4/LICENSE)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2026-10-23T16:52:19Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://lucide.dev/icons/)。官方图标目录可查统一线条；React Icon 实现以 currentColor、SVG 属性和 aria 属性判断输出。
- **实际源码：** [src/Icon.ts](https://github.com/lucide-icons/lucide/blob/7faf87628a1ad6175975bc595344337ce14a7db4/packages/lucide-react/src/Icon.ts)；[lucide-react/package.json](https://github.com/lucide-icons/lucide/blob/7faf87628a1ad6175975bc595344337ce14a7db4/packages/lucide-react/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 统一导航、动作、状态的 16/20px 线性图标；与文字同用，不再给主导航编号。
- **兼容性：** 可本地打包按需导入，无需字体/CDN；用图标加标签，装饰性图标隐藏给读屏。
- **依赖/复用成本：** 低；仍须锁定正式包并保留完整 LICENSE。主干包 version 为生成占位 0.0.1，不能当待安装版本。
- **不适用部分：** 不把 API NOASSERTION 当许可证未知或把全部图标统称 MIT；不引入另一整套图标字体。
- **评分与处置：** 设计适用 / 直接复用 = **5 / 4**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 16. cmdk

- **用途与技术栈：** 组件/工具候选；React / TypeScript；Command.Dialog 组合 Radix Dialog。仓库：[dip/cmdk](https://github.com/dip/cmdk)。
- **许可证：** MIT，已读 [许可证正文](https://github.com/dip/cmdk/blob/dd2250ed608443e8f32bafc5fa2d1d07a3746aa3/LICENSE.md)。
- **维护：** API `archived=false`；最近推送 UTC 为 `2025-10-29T04:13:58Z`。这仅是仓库活动信号，不证明维护 SLA、稳定发行或所有 UI 刚更新。
- **可查界面证据：** [官方截图/演示资料](https://github.com/dip/cmdk#readme)。旧 pacocoursey/cmdk API 返回迁移；canonical 为 dip/cmdk。README 有分组、搜索、空/加载状态及键盘示例；源码含稳定 value 和关键词合同。
- **实际源码：** [src/index.tsx](https://github.com/dip/cmdk/blob/dd2250ed608443e8f32bafc5fa2d1d07a3746aa3/cmdk/src/index.tsx)；[cmdk/package.json](https://github.com/dip/cmdk/blob/dd2250ed608443e8f32bafc5fa2d1d07a3746aa3/cmdk/package.json)。读取的是有关布局/组件的局部实现，不是全仓安全审计。
- **最值得借鉴：** 未来可为“跳转到页面”提供命令菜单；只搜索固定导航项，避免用户内容进入全局索引。
- **兼容性：** 有 React 19 peer 声明；默认不自动绑定 Ctrl+K，需应用处理 IME、输入焦点和 Memory leave guard。
- **依赖/复用成本：** 中；距查询时最近推送约一年，维护信号弱于其他候选。现有菜单很少，暂不引入。
- **不适用部分：** 不构建跨 Memory/Knowledge/Finance 的全文搜索、不持久化搜索词、不把菜单动作绕过业务/原生确认。
- **评分与处置：** 设计适用 / 直接复用 = **3 / 3**（满分各 5，主观工程判断）。允许作为按需组件候选；本轮零引入。

## 复用类别与许可处理

| 类别 | 本轮决定 | 后续引入条件 |
| --- | --- | --- |
| 视觉参考 | WPF UI、Files、Cherry、Chatbox、Actual、Ghostfolio、Firefly；只保留链接/分析 | 自己实现页面布局，不复制品牌标识、专有插画或整页源码 |
| 交互模式 | 折叠导航、列表—详情、Sources 预览、设置分区 | 保持现有离页保护、会话准入、用户明确选择与受控错误 |
| 可复用组件 | Radix、Lucide；TanStack Table 按实际表格需要 | 正式发布版、peer 核对、完整许可证/传递依赖清单、当前 CSP 下的最小离线验证 |
| 可直接引入代码 | 本轮 **零** | 必须单独指出所复制文件、原 SHA/许可证、必要裁剪、归属和维护责任；MIT 不代表适配成本为零 |

GPL 的分发可能涉及对应源码与许可证义务；AGPL 修改版还涉及符合其条款的网络交互源码提供义务。并非“看过 GPL 页面整个项目就必须开源”，也不能因为只取前端文件就假设豁免。实际组合/分发方式在复制前确认；本轮 Cherry、Chatbox、Ghostfolio、Firefly **均不复制代码**。依据为已读取的对应 LICENSE 正文及 [GNU 许可 FAQ](https://www.gnu.org/licenses/gpl-faq.en.html)。Files 的 MPL 文件不能按仓库 API 的 MIT 总标签处理；Lucide 完整 LICENSE 中的 ISC 与 Feather MIT 都需保留；Fluent 字体/图标有独立资产条款。

## 组件体系选择及未选方案

| 需求 | 建议 | 为什么没有立即引入其他候选 |
| --- | --- | --- |
| Shell、Sidebar、页面标题、卡片 | 现有语义 HTML + 本地 CSS Token；复用既有 navigation | 7 个顶级目的地无须安装完整 Shell 框架；shadcn Sidebar Cookie/快捷键有本项目冲突 |
| 基础按钮/输入/表格 | 先统一现有 native HTML 样式 | 不重写表单状态和验证，不制造另一个组件平台 |
| 复杂弹层/菜单 | Radix primitives 候选；同一套 Token | 现有 ConfirmationDialog 有焦点/安全取消，不主动重做；滚动锁和 CSP 需选版验证 |
| 图标 | Lucide React 本地按需打包 | 不混搭 Fluent icons / Tabler / 图标字体；不从 CDN 加载 |
| 大型复杂表格 | TanStack Table 候选，保留服务端分页/筛选 | 目前有界分页能工作，不为选择依赖而迁表；不启用浮点金额排序/汇总 |
| 金额图表 | 现有精确表格；Recharts 为后续条件候选 | 当前金额禁止 Number/parseFloat/toFixed；禁止为了图形改 Bridge/算法 |
| Command Palette | 暂不引入 cmdk | 菜单规模小、维护更新偏慢，且路由跳转必须复用 Memory leave guard |
| Toast / Status | 现有 role=status/alert 统一 Token | 持久故障不能被短 Toast 隐藏，无须另加通知库 |
| 全套 Fluent UI React | 暂不引入 | Windows 视觉并不要求 Griffel；动态 style 与严格 CSP 的成本尚未证明合理 |
| Tailwind | 不作为本次前提 | 当前 CSS 足以承载 Token，整套迁移增加无关 diff；未来是否采用另行评估 |

截至本次读取，候选主干出现 Recharts canary、Lucide 生成占位版本，Radix 文档/主干版本也不同。**研究快照不是安装版本建议。** 真正采用时锁定正式 npm 发布版并检查 React 19.3 / TS 7 / Vite 8 的 peer/构建结果，本轮不安装、不改 lockfile。

## 对 Workspace 的实际映射

- Cherry 的多栏只用于 Conversations/Knowledge 的列表和上下文，不把 Assistant 单次 Ask 伪装为持续会话；Chatbox 的侧栏恢复入口只借交互，不引入可拖动/持久化体系。
- WPF UI/PowerToys 的导航层次变成 React Shell 的分组与状态入口；原生标题栏、缩放、Quick Assistant 和 fallback 保持 WPF 所有。
- Actual 的表格结构用于 Accounts/Transactions；Ghostfolio 的指标分层用于 Portfolio；Firefly 的领域分组用于 Finance 二级导航。均不迁入外部金融算法。
- AnythingLLM 的来源抽屉对应现有 Knowledge citation locator；不建立新的 RAG、Memory 或检索 authority。
- Radix/Lucide 是可选基础工具，与现有 CSS 共用一个视觉系统；“融合参考”不等于多 UI 库并行。

下一步以 [实施计划](WORKSPACE-UI-IMPLEMENTATION-PLAN.md) 的 Phase 1 为上限；不因本研究提前实现 Agent、金融写入、预算或自由 Provider 编辑。
