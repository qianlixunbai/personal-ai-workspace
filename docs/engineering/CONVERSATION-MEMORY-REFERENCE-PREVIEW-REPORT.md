# 对话历史中的记忆引用查看

2026-11-07，基线 `cce51d5837ec30d3d0c00071561fd6313a161c41`，分支 `codex/recovery-v3-workspace-core`。入场核对最新 Recovery 交接、记忆只读预览和轮次定位报告；已跟踪工作树干净，原有五份未跟踪文档保留。仅开发个人 Workspace，与 KAG 无代码或运行环境操作交叉。

## 实际功能

对话历史中有显式记忆引用的轮次新增“查看记忆引用”。打开只展示说明和按引用顺序排列的按钮；点击“查看记忆 1”等按钮，才调用既有 `WorkspaceClient.getMemory` 读取该条记录。一次只打开一个轮次、查看一条记忆，不预取整轮或全会话。

读取成功后同时核对 ID、完整字符串 revision 和 ACTIVE 状态。通过时显示标题、完整正文、记录版本与标识，并标注“当前保存正文（版本与本轮引用一致）”。这不是历史正文快照，也不是模型实际使用或执行成功的证明。历史只持久化 ID/revision/position，不能重建未保存的旧正文。

版本变化或归档时不展示返回的标题／正文，提示到“我的记忆”查看当前记录；已删除／不存在有单独提示。其他读取错误显示既有安全错误，不自动重试。原文通过 React 文本节点呈现，保留换行，不解释 HTML、不总结、不截断。查询后其他客户端仍可能修改记忆，当前预览不构成实时订阅或长期版本证明。

关闭、切换引用／轮次、翻页／定位／显式刷新、切换会话、离页、禁用或 session 重置时清除相应预览；迟到结果不能重新出现。正常后台会话轮询不自动重新读取记忆。查看本身不更改未发送草稿、下一轮显式选择或记忆记录，也不产生模型请求；切换会话仍沿用原有清除下一轮选择的行为。

## 开源参考与复用决策

通过 Firecrawl 检索并阅读了 [Open WebUI 官方 Memory & Personalization 文档的相关摘录](https://docs.openwebui.com/features/chat-conversations/memory/)，其手动管理、查看和删除记忆的用户控制方式作为产品参考。它也描述了模型调用记忆工具的能力；本次没有引入这部分能力，不宣称自动记忆已完成。只使用官方文档中的用户控制做法，未复制上游代码或导入其存储／工具执行引擎。

Workspace 已有可靠的 Memory Store、Native 读取桥、revision 合同与持久引用元数据，因此复用这些能力，并复用现有预览样式。新增 `ConversationMemoryPreview.tsx` 仅承担本轮引用的展示与当前版本核对；没有另建记忆库、检索算法、迁移或新依赖。API、Native Bridge、Java、数据库 schema、恢复准入和安全设置均未修改。

## 必要验证与使用边界

- 在既有 `ConversationsPage.test.tsx` 增加并仅运行一个合成 Bridge 集成场景，首次通过：1 passed / 11 skipped（按名称过滤未执行），1.57 秒。覆盖点击前零正文读取、完整文本／HTML 字面呈现、草稿／选择保留、版本／状态／ID 不符、删除／存储失败、一次一条、关闭／刷新／切换／离页／禁用／session 的响应隔离，以及不产生额外写入或模型请求。
- `npm --prefix desktop/frontend run build` 首次通过，包含 TypeScript、Vite 和资产 manifest；保留已有主 chunk 大于 500 kB 提示，本阶段不扩展拆包工作。`git diff --check` 通过。
- 日志位于忽略目录 `.verification/conversation-memory-preview/`。只运行上述聚焦场景和前端构建，没有全套回归、Java／Desktop 编译、数据库或模型测试。

已交付源码和本地前端产物，**未打入受信 Windows 开发包，未验收真实 WPF／WebView2 的样式、键盘／焦点、Native Bridge 与 Runtime 读取链路**。实际使用需要后续受信打包与桌面验收；本次结果仅证明合成 Bridge 的前端链路。保持 Development-only、X1 PARTIAL、Vision BLOCKED；个人备份／恢复继续 BLOCKED。

## 回退与保留

修改前创建本地 Git 标签 `workspace-before-memory-history-preview-cce51d5`，指向基线；功能与本报告独立提交。本阶段没有合并 main、推送、发布或启动应用，没有访问真实个人数据库、凭据、模型或服务，也没有修改安全设置。

五份原有未跟踪工程文档复制到 `.verification/conversation-memory-preview/preserved-documents/`，记录 SHA-256 并在提交前再次核对原文件及副本。原件继续未跟踪，不纳入提交。Git 回退覆盖本次代码和已跟踪文档，不包含数据库、Knowledge 源文件、模型、凭据、服务状态、未跟踪文档或忽略目录中的证据／副本；本地 Git 提交也不是异机备份。
