# 完整会话导出开发增量

2026-11-09。沿总体路线补齐持续对话的数据可携带能力：从对话页导出**全部持久轮次**，不受当前历史页影响。旧有 Word／PDF、版本对比、限定检索、问答历史与阅读能力保留在同一代码链。仍为 Development-only，无推送或正式发布。

## 使用和实现

打开一个会话，点击“导出完整会话…”，在 Windows 保存对话框选择本地新的 `.md` 文件。笔记包括标题、会话／轮次 ID、UTC 时间、所有消息原文、成功／失败／取消／超时状态，以及持久记录的记忆选择 ID／版本／位置。空会话与归档会话也可导出。未发送草稿、当前选中的记忆正文和模型当前上下文不会被拼入笔记；历史回答自身可能含有个人信息。

Native 只接受本会话已授权的 conversation ID，逐页读取现有 Runtime API，检查顺序、唯一性、总数及元数据，结束后再核对一次。Store 的终态轮次不可变；遇到生成中、并发改名／归档／新增、超限或错误就停止，不保存部分内容。读取限时 30 秒，成品上限 4 MiB UTF-8；没有新 Runtime API、数据库迁移、模型或网络服务。

原文用长度超过内容中连续反引号的纯文本代码围栏包裹，保留 Unicode、HTML 和链接字面内容。复用原知识笔记的原生安全写入器：同目录私有暂存、严格 UTF-8、落盘、无替换移动；拒绝已有目标、网络路径及重解析目录。会话结束取消在途读取／保存；超时或响应丢失不自动重发。明文笔记不是可恢复备份，已有文件不会覆盖。

## 开源方案、许可和适配

核查了 [LibreChat](https://github.com/LibreChat-AI/LibreChat) 与 [Open WebUI](https://github.com/open-webui/open-webui) 的成熟聊天产品路径。观察时约 46.9k／158k stars，仅作维护规模参考。LibreChat 为 MIT，[发布页](https://github.com/LibreChat-AI/LibreChat/releases)有近期稳定及预发行记录；[安全公告](https://github.com/LibreChat-AI/LibreChat/security/advisories)包括 Markdown／artifact 相关 XSS，不能把高 Star 当安全保证。Open WebUI 的[当前许可](https://github.com/open-webui/open-webui/blob/main/LICENSE)含品牌保留条件，不能简单视为 MIT。

本项目已经具备持久历史、Native 会话授权和文件写入边界，因此复用自身现有能力及 [.NET 无覆盖文件移动](https://learn.microsoft.com/en-us/dotnet/api/system.io.file.move?view=net-10.0)，不引入另一聊天服务、浏览器下载通道或 Markdown 转换器。未复制上述项目代码，无新增依赖或第三方许可负担；原依赖声明随包保留。

## 必要验证与失败记录

- 桌面聚焦检查 **38/38**，包含全分页、原文、全部终态、长整数记忆版本、未授权拒绝、记录变化／生成中／超限拒绝、会话取消及并发导出拒绝；共享写入器实际 UTF-8／已有文件保护另项 **1/1**。证据：[`conversation-export.trx`](../../.verification/conversation-export-20261109/conversation-export.trx)、[`export-writer.trx`](../../.verification/conversation-export-20261109/export-writer.trx)。前端 Conversations 页 **19/19**，包括仅发送 ID、草稿／分页保持、保存／取消提示与生成中禁用；TypeScript、生产资源构建及 .NET 构建通过。既有大 JS chunk 提示保留。
- 真实 WPF／WebView／Bridge／Native 保存对话框／HTTP／SQLite 流程通过：停留第 2 页，导出全部 **23 轮／3 页**，逐条核对原文和 UTF-8；保存与取消都保留草稿和当前页，草稿不入文件。真实 Java fixture 确认持久历史不变，模型调用 **0**，桌面写请求 **0**，端口和自有 JVM 正常退出。[结果](../../.verification/conversation-export-20261109/run-5/result.json)、[文件核对](../../.verification/conversation-export-20261109/run-5/export-result.json)、[截图](../../.verification/conversation-export-20261109/run-5/conversation-export.png)。导出文件 SHA-256：`4C361057DA1E8B5FB0F7EAECD29167D67FD3E628CB4CC1B7C58703FF9DCEF1C3`。
- 首两次夹具在全新合成 Profile 准备阶段停止：当前宿主返回的目录句柄路径未通过既有身份核对，尚未进入应用；没有调整 ACL、EFS 或路径校验。随后使用此前有创建证明的合成 Profile，只在其独立 `evidence/api-memory-*` 数据库运行；普通 `data` 文件及最近工作区记录通过前后核对。新 Profile 的两个空目录保留现场，此创建问题未修复。[定位](../../.verification/conversation-export-20261109/run-2/failure.json)。
- 第一次实际保存选在仓库证据目录，返回固定安全错误；定向写入诊断定位到 `SetSecurityInfo` 的 Access Denied。未覆盖文件，也未修改目录权限。最终改用新的临时目录，通过原生保存后只复制合成导出结果供复核。[诊断](../../.verification/conversation-export-20261109/run-4/export-exceptions.jsonl)。这说明保存目标必须支持设置私有文件权限，不能宣称任意本地目录都可写。

桌面流程复用既有手工探针和合成会话夹具，使用真实产品组件；它不是完整 AssistantApp／托盘进程入口验收，也不是新分发 EXE 的人工操作认证。上一 PDF 包的完整应用启动与重开已由[前轮报告](KNOWLEDGE-PDF-IMPORT-REPORT.md)完成，本轮未重复。没有重跑全部 Java、PG、模型、恢复或包篡改矩阵。

## 交付、回滚和边界

实现提交 `380de4bf8fcf754ef59ab72c5c76e675d9e7c1d6`。回滚标签 `workspace-before-conversation-export-20261109` 指向 `c3517eea89f65c061d0fe097005bdb64227db8a1`。成果继续在 `codex/knowledge-version-compare`，`main` 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，不推送。回滚应用不应覆盖 Profile 或回退用户数据库。

新统一开发包：[Workspace-conversation-export-development-20261109.zip](../../artifacts/Workspace-conversation-export-development-20261109.zip)，384,661,743 字节；SHA-256：`0DAA5B066AB7308655FBBB9086A425EBF60B1FBF5935317FFAC7309E3EEA88AA`。933 个产品文件及清单共 934 个 ZIP 条目逐项哈希通过，发行目录严格检查通过；产品来源绑定上述提交，没有未提交产品源码变化。manifest 的 `sourceDirty=true` 保留用户未跟踪文档和文档编辑的事实，不表示这些文件被打包。[包收据](../../.verification/conversation-export-20261109/package-result.json)。Maven package 使用 skipTests，不计为 Java 测试通过；前端与自包含 Desktop 发布通过。

旧包保留；六份用户未跟踪文档哈希不变，不纳入提交或分发。最近工作区记录和既有七个 PostgreSQL 进程未变，测试自有端口／JVM 已退出。[最终隔离收据](../../.verification/conversation-export-20261109/final-isolation.json)。没有读取真实个人资料、改动现有服务、安全设置或 KAG 工程；使用无模型、限额单 JVM 顺序验证，没有建立跨项目资源锁。

未验收：新宿主上下文下全新 Profile 创建、不同机器、完整 PG 桌面、个人恢复、最大容量性能、通用模型质量及签名／安装／更新。导出不重建历史记忆正文、不证明实际模型上下文，也不提供会话导入。分发继续含 .NET Desktop 10.0.14 和 Oracle JDK 21.0.7（保留 Oracle NFTC／legal），NativeBootstrap 仍要求本机 .NET 10 x64，WebView2 Evergreen 为外部先决条件。
