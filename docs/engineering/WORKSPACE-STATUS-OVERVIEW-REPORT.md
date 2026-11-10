# 工作区状态总览与功能入口

2026-11-10，源码基线 `d7b4786f83a5d0b257cdabf854f6d029a83d9130`，在原 `codex/knowledge-version-compare` 分支完成一个产品模块：把分散的连接、启动观察、存储和模型检查汇总到设置页，并按用户要做的事提供后续入口。未扩展为系统资源监控。

## 实际交付

- `StatusPanel` 展示 Java Runtime、原生认证、启动观察、数据存储、模型与 Ollama 五项独立状态；每项都有检查回执时间和对应检查区入口，存储另显示 Runtime 返回的来源检查时间。没有“全部就绪”总灯。
- “按要做的事继续”区可打开知识库、记忆、对话页面，或定位 AI 使用条件、Finance 连接、备份说明。定位会展开对应折叠区并移动键盘焦点；页面导航沿用 App 原有流程。进入页面不改变既有能力授权。
- 状态明确区分尚未检查、检查中、失败、失效、过期和时间偏差；一分钟是界面展示的新鲜度门槛，不是准入规则。每五秒只刷新本地时钟显示；窗口重新获得焦点或可见性变化时也更新显示，不发请求。
- SQLite 只显示当前引擎并说明未验证其读写；PostgreSQL 分别核对配置可信性、连接和结构。结构检查通过不代表 Knowledge 私有文件、具体记录读写或 Finance 身份绑定通过。
- 模型目录失败保留其他子系统结果。模型已有验证记录、目录可访问、安装、Ready、本地执行数和远端完成仍为不同事实；不根据本地请求数或本地记录检查断言远端结束。
- 迁移、加密备份、正式恢复及模型恢复保留既有保护。总览只提供说明和导航，不直接调用原生维护、切换、加载、恢复或模型任务。

## 模块边界与失效处理

`bridge/statusObservations.ts` 是仅供显示的内存观察缓存。`WorkspaceClient` 在现有 `shell.bootstrap`、`shell.refreshStatus`、`storage.startupStatus`、`storage.status`、`models.inspect` 的已校验 Promise 上记录结果；没有新增 Bridge 方法、后台探针、HTTP 请求或持久化。

每项有独立请求版本，旧检查不会覆盖新检查。Bridge 会话变更或关闭会清空所有观察；Runtime 生命周期事件使依赖结果失效并记录该事件的启动观察。连接重新检查、检查失败、原生凭据／数据库设置、模型操作、模型任务或执行记录检查会保守地使相关旧观察失效。旧会话的原生操作完成回执不会清除新会话结果。观察缓存不被任何产品准入逻辑读取，既有类型校验、原生确认和后端权限保持原样。

观察随 `WorkspaceClient` 存活，离开／重新进入设置页不会把时间改为“刚刚检查”。普通状态使用回执接收时间；存储还取来源时间和回执时间中较早的时间判断过期，未来时间保持待核对。没有通过缓存延长有效期或自动重查。

## 验证证据

本地证据目录：`.verification/workspace-status-overview-20261110/`。均为实际 React 组件和 WorkspaceClient 配合合成 `WebViewPort` 的 jsdom 场景；无真实服务、用户资料、数据库、Profile 或模型访问。

| 证据 | 结果与范围 |
| --- | --- |
| `initial-types.log`、`initial-result.json` | TypeScript `tsc --noEmit` 通过，退出码 0。 |
| `initial-ui.log` | 3 个文件、8 个场景通过：新增总览 3 个，受影响的 SettingsPage 3 个、StorageSettings 2 个。 |
| 新增场景 1 | 独立存储／模型检查、部分目录失败保留存储、SQLite 不误报读写就绪、模型未知执行保持阻断、回执时间、折叠区／键盘焦点、资料导航、受保护按钮保持禁用、没有总览额外请求。 |
| 新增场景 2 | PostgreSQL 检查与具体功能读写分开、模型验证与远端完成分开、离页重进保留原时间、过期不轮询、旧来源时间和未来来源时间分别处理。 |
| 新增场景 3 | Runtime 准入失败、生命周期之后的迟到模型回执丢弃、无效存储响应显示失败、会话清空、旧原生操作完成不污染新会话。 |
| `repair-types.log`、`repair-ui.log`、`repair-result.json` | 源码复核后补充“执行记录检查使旧模型观察失效”；类型检查通过，仅补跑新增主流程及既有阻断记录流程，2 通过、4 跳过。未重跑其他已通过场景或全套测试。 |

两轮命令均在 `Local\Dot_KAG_Workspace_HeavyRun` 内顺序执行；Node 堆上限 384 MiB、Vitest 单 worker、单场景超时 10 秒。同步命令均返回，两个结果回执均 `lockReleased=true`、`failure=null`。未新建系统／进程／端口采样，未启动服务。没有执行生产构建、资源打包、EXE 或 WebView 浏览器验收；DOM 场景不代表实际桌面布局、辅助技术和完整链路验收。

## 本地检查点与保留事项

- 仅提交本模块前端源码、必要合成场景及本报告／状态／路线图；不推送、不改 main，不纳入六份原有未跟踪用户文档。
- main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。原有用户文档保留，旧工件未重建或覆盖。
- `ModelStateStore.java` 源码 SHA256 仍为 `2E2A097F34A5AAE5381DBA2D125B6F64CDA48BC9747BD2A3C5106453AE6B28AE`。该安全候选未改动、未打包、未部署。本轮没有原／替代 Profile 操作、armGuard、helper、NativeGate／产品原生准入或真实模型流程，也没有修改系统安全、ACL 或身份。
- `PersonalAiWorkspace-knowledge-conversation-workflows-20261110.zip` 仍是此前 `74a947f`／源码 `b4ae52e` 的包，不包含本模块及后来的安全候选；不能把源码检查视为旧包已更新或 EXE 已验收。
- 原 model-state 产品校验拒绝、真实模型恢复、完整 EXE／WebView 及整体维护验收仍 **BLOCKED / productionGo=false**；这些既有阻塞没有被 UI 改进解除。

本地暂存首次因沙箱不能创建 `.git/index.lock` 返回 Permission denied；随后只读确认索引未写入、HEAD 未变、没有锁文件，并从用户原始历史核对 Git 备份／可回滚授权。相同明确文件清单经正常 `require_escalated` 审批后暂存成功；未修改 ACL、身份或 Git 配置，没有换工具绕过。本轮无新的产品准入尝试或不明执行结果。

下一步应按真实使用缺口选后续模块；无需为模块数量重复测试、补小规则或重新打包。完整桌面验收须等待原安全边界的独立处置依据，本报告不提供部署授权。
