# 回答排版阅读与原文保真

日期：2026-11-09。已完成助手／摘要、持久会话和知识问答的 Markdown 阅读增量，并生成新的本地 Windows 开发预览包。**Development-only / unsigned**；本轮验证覆盖前端行为、隔离 WPF WebView 阅读及包完整性，不增加完整 AssistantApp／真实模型／个人恢复的验收结论。

## 交付与使用

- [最终 ZIP](../../artifacts/Workspace-answer-reading-development-final-20261109.zip)，381,893,693 字节。完整解压后运行 `PersonalAiWorkspace.Desktop.exe`；本机也可使用[已有解压目录入口](../../artifacts/Workspace-answer-reading-development-final-20261109/PersonalAiWorkspace.Desktop.exe)。包被 Git 忽略，仅存在于本机。
- 产品提交：`927976d451020ddf27e6af9a7864afba117f2b77`；最终正文样式修正：`60f7a0f98275a4919b6ef02a7d0523108ce6158d`。最终包清单绑定后者。
- ZIP SHA-256：`DCCD27F6F6258A27B3427F1548115092E942E81A8EE1D9D92BAE7F9D64A21418`。
- Desktop DLL：`405464C08445882B2B4AFD1A17D846484A242F5D57B8901DDB5B773577443CD3`；Runtime JAR：`A19E785A7EC633F97FA0D5C1900FDDEB9B3008C5E0F9335615526E9D49285B45`。
- [包收据](../../.verification/answer-reading-20261109/package-receipt-final.json)记录完整目录及 ZIP 的 923 个清单文件核对结果。旧整合包、首个阅读候选包与失败记录均保留，新增入口以本报告的 **final** 为准。

打开成功回答或历史回答即可阅读标题、强调、列表、表格、引用段落与代码块；“查看原文／返回排版”在当前回答内切换。代码保持缩进并可横向滚动，表格有独立滚动区域与键盘焦点。助手的“复制结果”、会话的“保存为记忆草稿”和知识问答的“导出问答笔记”继续使用已有原始记录。

用户消息、翻译结果、搜索高亮和引用来源预览仍显示字面原文；知识引用继续通过既有精确版本按钮打开。回答里的网址显示为不可点击的文字，图片仅显示说明；原始 HTML 显示为文本，不执行代码或加载图片。不增加数学公式、语法高亮、图表执行或外部网址打开功能。

## 选择依据与成熟方案

开始时核对了当前包报告、路线图、阶段总结、Git 分支和六份未跟踪用户文档。代码沿 `codex/knowledge-version-compare` 同一链开发，已有可用的 Word／版本／历史链路。回答显示仍是 `pre`／纯文本，表格、列表及代码难以阅读，是跨三个日常页面的直接产品缺口。本轮选择统一阅读，未扩大恢复／模型评测设施。

| 方案 | 来源、许可及适配判断 |
| --- | --- |
| [React Markdown](https://github.com/remarkjs/react-markdown) | 观察时约 15.9k stars，MIT；固定 `10.1.0`，官方 [release](https://github.com/remarkjs/react-markdown/releases)有发布和修复记录。组件直接生成 React 节点，兼容现有 React 19；无需把 HTML 字符串注入 DOM。 |
| [remark-gfm](https://github.com/remarkjs/remark-gfm) | 约 1.3k stars，MIT；固定 `4.0.1`，[release](https://github.com/remarkjs/remark-gfm/releases)列出类型与文档维护。用于表格、删除线和任务列表，与前者属于同一生态。 |
| [Marked](https://github.com/markedjs/marked) | 约 37.3k stars，MIT，成熟且更偏重轻量解析。官方明确说明 HTML 输出不自带清洗；本项目需要 React 节点及严格的图片／导航控制，因此选择 React Markdown，未引入第二套 HTML 清洗与注入路径。 |

Star 与发布记录仅作为参考，不表示安全保证。安装使用固定版本、lockfile 和禁用安装脚本；既有依赖的版本与 integrity 均未改变，见 [依赖差异](../../.verification/answer-reading-20261109/dependency-changes.json)。100 个直接及传递依赖均从实际包收集 MIT／ISC 许可，完整文本随 Desktop 输出／发布，见 [markdown notices](../../desktop/src/PersonalAiWorkspace.Desktop/ThirdPartyNotices/markdown.txt)及[来源哈希](../../.verification/answer-reading-20261109/notices.json)。部分传递类型包不进入浏览器，仍保留其通知。

本轮 `npm audit --omit=dev` 报告生产依赖已知漏洞 **0**，原始结果为 [npm-audit.json](../../.verification/answer-reading-20261109/npm-audit.json)。这不是全应用安全审计。前端压缩后、gzip 前的主 JS 从 588,297 增至 755,143 字节，增加 166,846 字节（约 28.4%）；最终 gzip 约 218 KB。Vite 原有 500 KB 大 chunk 提示保留，没有为消除提示额外重构加载体系。所有解析都在当前进程完成，无新增服务、模型或网络请求。

## 实现与保真

共用 `AnswerContent` 只接收已通过现有 Bridge 校验的回答字符串，以回答 ID 挂载、`memo` 避免无关重渲染时重复解析。白名单限制生成元素，链接／图片／任务勾选转为静态文本节点；未启用原始 HTML 解析插件。脚注正文保留，标记不可导航；各回答脚注项目 ID 隔离，省略解析器固定的脚注标题 ID，避免重复。

“查看原文”显示同一字符串，包括换行与空白。排版不回写存储，不改变提交内容、复制协议、历史导出或记忆候选原文。新回答通过 ID 重置阅读模式；会话未发送草稿保持。复用现有浅深色 token，并隔离正文列表、字号与标题颜色，避免继承知识引用列表的分隔线或会话元数据样式。

Java、Native 授权、CSP、存储 schema 和 API 均未改动。Desktop 工程只增加许可文件随包复制项；没有新增持久恢复入口。

## 本轮验证

证据目录：[`.verification/answer-reading-20261109`](../../.verification/answer-reading-20261109)。

| 范围 | 结果及限制 |
| --- | --- |
| 聚焦前端 | 四个文件 **34/34 PASS**，见 `frontend-tests-3.log`：Markdown 结构、原文逐字保留、危险 HTML／网址／图片无可执行节点、多回答脚注、会话草稿及记忆正文、按原 operation 复制、按历史 task 导出、翻译保持字面显示。新增 4 个用例并扩展已有导出用例，未运行全前端套件。 |
| 生产构建 | TypeScript、Vite／资产清单、普通 Maven package、既有 JAR 审计与自包含 win-x64 Desktop publish 通过，见 `package-build-final.log`。Maven 使用 skipTests，不计为 Java 测试通过。包内 .NET 仍为 10.0.14；既有 Oracle JDK 21.0.7／NFTC、NativeBootstrap 本机 .NET 10 与 WebView2 前提保持。 |
| 实际 WPF WebView | [最终结果](../../.verification/answer-reading-20261109/run-final/result.json)为 PASS。使用最终包前端文件、全新缓存、一次性合成 Bridge；三页面 × 浅深两主题，助手／会话窗口 1100×820 DIP，知识页 1024×680 DIP。原文切换、草稿、表格／长代码滚动、引用原文及复制／导出请求通过；无页面横向溢出，外部资源请求为 0。代码不执行真实原生复制／保存，不启动产品 Native／Runtime／数据库／模型。 |
| 包完整性 | 构建与最终核对调用既有包合同；最终目录与 ZIP 中 923 个 payload 文件逐项核对长度及 SHA-256，ZIP 清单也一致，新增许可与来源相同。首个候选另实际解压到中文／空格路径，完成包核对及 WebView 阅读。未重复旧 7 项负向矩阵。 |
| 保护 | [final-isolation.json](../../.verification/answer-reading-20261109/final-isolation.json)：六份未跟踪用户文档哈希相同，既有 PostgreSQL PID 集相同，无 Java／Ollama 运行进程遗留，预览进程已退出。未访问个人 Profile 或 KAG，0 次模型调用。 |

已查看最终[助手浅色截图](../../.verification/answer-reading-20261109/run-final/assistant-light.png)和[知识页深色截图](../../.verification/answer-reading-20261109/run-final/knowledge-dark.png)。截图中的 Runtime 可用状态和所有内容均为合成展示，不能作为真实 Runtime 就绪证明。

失败记录保留：首轮 33/33 通过后，补充脚注标题 ID 时暴露重复 ID，`frontend-tests-final.log` 为 33 通过／1 失败；修正后上述 34/34 通过。预览 `run-1`／`run-2` 错误等待空输入的发送按钮启用，`run-3` 的合成引用多带字段被严格校验拒绝，并补正折叠入口操作；这些是临时预览工具问题，未放宽产品校验。`run-4` 通过后截图发现列表样式继承，修正产品 CSS、重打包，再由 `run-final` 通过。没有删除或改写失败证据。

## Git 与剩余边界

回滚标签 `workspace-before-answer-reading-20261109` 指向 `d6f9a7b252186465b48cfc3b5d7a4233b54157ec`。产品与样式各自本地提交，文档另作提交。`main` 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，没有推送或新增分支。最终包构建时 tracked 产品文件干净；`sourceDirty=true` 来自原有六份未跟踪用户文档，它们未被提交或打包。

新包尚未重新进行完整 AssistantApp／JVM 重开、真实 Windows 原生保存对话框或 EXE 无参数启动验收；旧包的完整流程证据仅属于[旧整合包](WORKSPACE-CURRENT-BUNDLE-REPORT.md)。本轮不增加一般模型质量、PG 桌面、跨机器安装、个人恢复、签名更新或 Production GO 的结论。X1 仍 PARTIAL，Vision 仍 BLOCKED。上述范围保留为后续独立关卡，不阻塞本轮阅读源码与开发预览交付。
