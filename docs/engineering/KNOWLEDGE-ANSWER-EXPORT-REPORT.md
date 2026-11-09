# 知识问答笔记导出

2026-11-09，基线 `82a2e66`，分支 `codex/knowledge-answer-export`；回滚标签 `workspace-before-knowledge-answer-export-20261109`。仅本地开发，Development-only，未推送或发布。

## 交付和使用

Knowledge 的成功回答下新增“导出问答笔记…”；新完成的回答和从历史页打开的成功回答都可使用。用户在原生窗口选择新的 `.md` 文件，保存原问题、原检索词、原资料范围、回答，以及每条引用的文档 ID、标题、源版本、行号、UTF-16 范围和准确原文。笔记另记导出时间，不把它当成回答生成时间。

这补齐了“提问 → 查看引用 → 带着可核对的原文离开应用”的日常使用流程。编辑框内未提交的新草稿不影响导出，历史中的回答也不会重新生成。现有长资料摘录、历史和指定文档范围已具备，本轮不继续扩大同类模型样本或恢复基础设施。

导出是**明文的单条问答副本**，不是完整文档或可恢复的工作区备份。原文任一片段缺失、版本不符、截断或读取失败时停止，不拿新版替代，也不生成只有部分引用的成功文件。需要先恢复可读取的来源，才能导出完整笔记。引用范围准确不保证模型回答语义正确。

## 成熟方案评估

本轮通过 GitHub 连接在线读取元数据、README 和许可证。2026-11-09 查询值：

| 方案 | 维护、许可和适配 | 选择 |
| --- | --- | --- |
| [Markdig](https://github.com/xoofx/markdig) | 5,355 Star，未归档，最近 push 2026-11-08 07:12:05 UTC。[BSD-2-Clause](https://github.com/xoofx/markdig/blob/main/license.txt)；.NET Markdown 解析、AST 和 HTML 渲染，兼容当前技术栈。README 说明 HTML、自动链接等功能及关闭 HTML 解析的选项。 | 适合未来渲染／编辑，但本功能只写固定笔记结构，不需要解析或渲染用户 Markdown。加入解析器不能解决版本授权、来源读取或原生保存。 |
| [Pandoc](https://github.com/jgm/pandoc) | 46,863 Star，未归档，最近 push 2026-11-09 06:44:56 UTC。[README](https://github.com/jgm/pandoc/blob/main/README.md)标示 GPLv2+，[许可文本](https://github.com/jgm/pandoc/blob/main/COPYING.md)已读。支持多种格式，需引入额外可执行文件／转换流程。 | 单一 Markdown 输出不需要通用格式转换；新增分发、进程及许可维护成本，没有解决本功能额外缺口。 |

采用已有 `RuntimeClient` 历史／版本预览、WPF `SaveFileDialog`、.NET UTF-8／文件流和 `KnowledgeBackupWindow.ProtectFile`。新增代码只负责领域编排和固定模板，不自建 Markdown 引擎，不复制上游代码或新增依赖。所有动态标题、问题、回答和原文都在纯文本代码块内，围栏长度超过正文中的最长反引号串，不把模型 HTML／远程图片变成活动内容。应用不打开输出、不调用转换器、不访问其中的链接。

以上是维护、许可和适配检查及本功能安全取舍，不是完整漏洞审计；Star 不能证明安全。Markdig 的小写 `security.md` 查询返回 404，未据此推断整个项目没有安全政策。

## 实现边界

- Bridge `knowledge.answerExport` 只接受当前会话已经打开／提交并获授权的 `taskId`。只有历史列表授权还不够；客户端不能传正文、源版本或目标路径。
- Native 重新读取持久历史，核对成功状态与原范围，按不可变版本逐页取回。沿用单页最多 4,096 UTF-16 单位，每条最多 16 次读取，最多 10 条引用；非连续范围、错误版本或半个代理字符拒绝，不把额外上下文写进摘录。
- 一次仅允许一个导出；会话撤销取消读取／保存，页面忽略迟到结果。原生窗口取消返回 CANCELLED；超时或无效成功回执显示“结果无法确认”，先检查保存位置，不自动重放。
- 保存位置只由原生选择器取得，拒绝网络路径、重解析路径和非 `.md` 目标。同目录创建新暂存文件，复用既有权限保护，完成 UTF-8 写入和刷盘后以不覆盖模式发布。已有文件保留；失败／取消清理自有暂存文件。没有更改既有文件权限或系统安全设置。
- Java、数据库 schema、历史协议、模型参数和个人恢复准入不变；没有自动建立 Memory 或 Conversation 关联。

## 必要验证

证据根：`.verification/knowledge-answer-export-20261109/`，Git 忽略。只扩展现有两个测试文件，新增桌面三个综合场景、前端一个综合场景，没有新测试框架或无关全量矩阵。

| 检查 | 结果与证据 |
| --- | --- |
| 前端 KnowledgeAnswer／KnowledgePage | **14/14**，`frontend-test.log`。历史打开 → 修改草稿 → 仅传 ID 导出 → 成功／取消／未知结果 → session 迟到结果；无新问答请求。额外带路径的成功回执拒绝。 |
| TypeScript／Vite／资产清单 | 通过，`frontend-build.log`；保留既有单 JS chunk 大于 500 kB 的提示。 |
| .NET Knowledge owning tests | **25/25**，`dotnet-test.log`、`test-results/knowledge-export.trx`。Native Bridge／真实 RuntimeClient 解析＋合成 HTTP；原问题、旧版摘录保留；无授权、未完成、缺失来源、错误版本、并行导出及 session 变更受控。 |
| 加强 Unicode 边界后的复验 | **2/2**，`dotnet-unicode-test.log`、`test-results/knowledge-export-unicode.trx`。emoji 跨分页完整恢复；引用终点切断代理对拒绝；额外路径请求在 Bridge 阶段拒绝。生产代码未因加强断言修改。 |
| 实际原生文件写入 | .NET 场景调用 `SaveNewAsync` 在唯一临时目录写文件，UTF-8 字节一致，已有文件不覆盖，预取消无目标／残留文件；只删除自身临时文件和空目录。 |

```text
npm.cmd --prefix desktop/frontend test -- --run src/components/KnowledgeAnswer.test.tsx src/pages/KnowledgePage.test.tsx
npm.cmd --prefix desktop/frontend run build
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter FullyQualifiedName~KnowledgeTests
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter FullyQualifiedName~KnowledgeTests.AnswerExport
git diff --check
```

记录保留首次结果；本轮没有失败后修改断言掩盖产品失败。未启动完整 AssistantApp／WebView2 或操作系统保存对话框，未做新 JVM、SQLite／PG 实库组合、真实模型、文件系统攻击／断电、DPI／IME 或发布包验收。真实磁盘写入和合成 HTTP 流程不等于完整桌面实机通过。原有三题小样本质量结论不扩大。

## 隔离、回滚和后续

起始分支 `codex/knowledge-answer-followup`、HEAD `82a2e66`，已跟踪文件干净；六份既有未跟踪文档逐个记录 SHA-256，原样保留、不加入提交。核对既有七个 PostgreSQL 进程，没有启动／停止它们。没有读取真实 Profile、个人资料、凭据或访问 KAG 服务；无模型／GPU／Docker 工作。本轮没有 KAG 显式资源握手，采用不需要其资源的开发范围。

普通沙箱进程初始化报 `helper_sandbox_lock_failed`，未执行命令；后续工具审批允许的执行完成工作，没有修改沙箱目录或安全配置。不存在仍被自动审批拒绝的操作。

代码可单独 revert，没有数据库迁移。Git 回滚不删除已由用户另存的笔记，也不构成个人数据恢复。完整 Windows 保存对话框和同包应用验收是下一实际缺口；继续使用新合成 Profile，无需加载模型。个人恢复、PG 完整桌面和正式分发维持原边界。
