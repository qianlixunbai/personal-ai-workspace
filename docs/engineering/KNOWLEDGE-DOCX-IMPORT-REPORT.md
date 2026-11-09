# Word 正文摘录导入与隔离桌面验收

2026-11-09。基线为已获用户授权快进的本地 `main @ f5266bf`，实现位于 `codex/knowledge-docx-import`，回滚标签 `workspace-before-docx-import-20261109` 指向基线。没有推送或进一步修改 main。仍为 Development-only。

## 交付的使用流程

知识库的“导入文档”现在接受 `.docx`。用户通过原生文件选择器选择本机普通文件，后台读取正文段落与简单表格，再在原生只读窗口中预览全文；点击“确认导入这份摘录”后，才把 UTF-8 文本交给既有 Knowledge 导入流程。取消文件选择或预览都不上传、不入库。

保存名称为 `原文件名.docx.txt`，内容包含来源文件名、原文件 SHA-256、提取格式 `docx-body-v1`、省略范围说明及正文。表格按行排列，单元格用制表符分隔并加双引号，单元格内双引号按 TSV 方式转义。Runtime 仍将它视为普通 TXT，复用既有版本、检索、原文预览、问答引用与备份机制，不新增来源类型、数据库迁移、Runtime 接口或模型服务。

**保存的是经确认的文字摘录，不是 Word 原文件或 Word 页面副本。** 图片／对象以省略提示表示；页眉页脚、脚注／尾注正文、批注、自动编号、样式和版式不导入。原文件不改写，不在库中保留；知识库的引用行号及偏移只指向已保存摘录。原 Word 文件仍需用户另行保管。

修订、域、内容控件、直接隐藏文字属性、合并单元格、嵌套表格、替代内容及未知正文元素会保守拒绝；可先在 Word 中另存为纯文本并核对。此范围不是任意 DOCX 的完整渲染或可见性判断，也未求值继承样式与自动编号。

## 成熟组件评估与边界

采用固定版本 [Open XML SDK 3.5.1](https://www.nuget.org/packages/DocumentFormat.OpenXml/3.5.1) 及其同版本 Framework，直接使用类型化 `OpenXmlReader` 流式遍历，未自行编写 OOXML 解析器。[上游项目](https://github.com/dotnet/Open-XML-SDK)属于 .NET Foundation，页面显示约 4.6k Stars；[3.5.1 发布页](https://github.com/dotnet/Open-XML-SDK/releases/tag/v3.5.1)可见发布及后续维护记录，当前包提供 net10.0 目标，适配本项目。MIT 许可全文已加入 `desktop/src/PersonalAiWorkspace.Desktop/ThirdPartyNotices/Open-XML-SDK.txt` 并随构建复制。

也检查了 [Mammoth.js](https://github.com/mwilliamson/mammoth.js)：约 6.3k Stars、BSD-2-Clause，支持 DOCX 到语义 HTML及原始文本，也有 .NET 移植；它是合理候选。此处选择 Open XML SDK，是因为要在原生持有文件流的边界内限定正文结构、拒绝修订／域并逐步检查读取上界，无需 HTML 显示链或另接其移植版本。没有据此判断 Mammoth 不安全或不可用。

上游[安全公告页面](https://github.com/dotnet/Open-XML-SDK/security/advisories)当时未列出已发布公告；启用 `NuGetAuditMode=all` 的恢复没有告警。固定版本[读取器源码](https://raw.githubusercontent.com/dotnet/Open-XML-SDK/v3.5.1/src/DocumentFormat.OpenXml.Framework/OpenXmlPartReader.cs)禁止 DTD，并使用部件字符上界。包 SHA-256 记录在 `dependency-hashes.json`。GitHub REST 查询遭到公共 IP 限流，`openxml-upstream.json` 中空值是该失败，不作为许可证、更新时间或精确 Stars 的证据；上述判断来自官方页面和实际恢复结果，不是完整安全审计。

文件沿用已有 held-handle 只读校验，不重新打开原路径、不执行 Office、不访问文档超链接、不解包到磁盘。一次只允许一个原生选择／提取操作；后台工作实际结束后才释放并发占用。压缩源沿用 8 MiB 上限，另限制最多 256 个 ZIP 项、单项 4 MiB、实际解压合计 16 MiB、正文 480,000 UTF-16 单位、输出 2 MiB、200,000 个读取节点、64 层深度和 90,000 个段落。10 秒为循环检查的协作式截止，不是独立进程的硬终止保证。文件过大、损坏、加密或不支持结构会失败，不截断后冒充完整成功。

## 实际验证与原始失败

证据根为 Git 忽略目录 `.verification/knowledge-docx-import-20261109/`。

| 验证 | 结果与范围 |
| --- | --- |
| `dotnet-test-1.log`、`test-results/knowledge-docx.trx` | 29/29，零跳过：4 个新的聚焦提取场景及原有 25 个 Knowledge 场景。覆盖中文／emoji／表格引号／链接显示文字，修订／域／合并单元格拒绝，解压上界、DTD、损坏输入、取消和纯图片拒绝。 |
| `frontend-test.log` | Knowledge 页面与问答组件共 14/14；随后“已取消导入”文案调整由最终构建及真实取消流程覆盖，未重复整套前端测试。 |
| `frontend-final-build-2.log`、`probe-build-4.log` | 最终前端与完整桌面探针构建通过，.NET 0 warning／0 error；沿用既有默认 Runtime JAR，Java 源码未改。 |
| `run-1` | 自动操作停在文件选择窗口，210 秒自有 Job 截止退出；没有取得导入成功收据，不重放该操作。 |
| `run-2`、`run-3` | 定位到验收工具硬编码的控件编号 1001 不适用于本机 OpenFileDialog；先出现不支持 ValuePattern，再出现找不到编辑控件。均退出且未新增资料。 |
| `run-4/first-file-controls.json` | 本机文件名控件编号为 1148；探针改为按明确的“文件名”标签和 ValuePattern 选择控件，记录不包含文件列表名称或输入值。 |
| `run-4/first-result.json` | 真实 AssistantApp／WPF／WebView／Bridge／HTTP／SQLite：先取消预览，资料数仍为 3；再次选择并明确确认后仅新增本次合成摘录，检索与精确原文预览通过。 |
| `run-4/reopen-result.json` | 第一轮应用及 JVM 退出后，第二次独立启动读回同一摘录，全文和源摘要一致；核对本轮创建的 ID、标题及全文后，仅删除自己的测试资料，原三份文档 ID 集合恢复，三条历史数量保持。 |
| `run-4/first-source.png`、`reopen-source.png` | 已目视核对实际页面：中文、emoji、表格文字、来源说明及命中高亮可见。原生预览通过 UI Automation 比较完整文字；不是物理键盘／IME 验收。 |
| `run-4/completion.json` | 两轮退出码均为 0，0 模型调用，最近工作区选择内容未变，全部自有进程和端口退出。 |
| `reconnected-isolation-check.json` | 断线重连后的只读核对：六份用户文档哈希不变，既有 PostgreSQL PID 集合不变，默认选择哈希一致，11435／18765 无监听，也无遗留 Java／Ollama／llama-server／Desktop。 |

成功轮次合成 Word 原文件 SHA-256：`0AB1167BB3CFDEBB5258C5F37469910FD8415EE668606A3B470FCCC66DE4D7DB`，导入、重开和清理后均不变；保存摘录 SHA-256：`9763F74F7686B43566A5AEE698B14A7B01922940ED9A26CADF675882C0D03516`。成功前的失败日志全部保留。连接中断发生在成功验收之后、报告和 Git 提交之前；重连没有重跑导入、删除或模型操作。

## 可用范围与下一步

当前交付可用于限定规模的基础 DOCX 正文摘录，经预览确认后进入既有知识库。真实端到端证据限于本轮生成的合成 DOCX 和 SQLite 完整应用；未运行 Word／LibreOffice 的完整排版或任意 Office 文档兼容性验收，未验证新模型回答质量、PG 完整桌面组合、正式安装包或个人备份恢复。已有原文引用能力不等于 Word 格式保真或语义正确。

使用的合成 Profile 有此前明确归属记录，测试资料均可辨识并由当前轮次收据约束；没有读取真实个人文档，没有启动模型或操作 KAG／既有服务。仅进行资源前置检查与自有 Job 隔离，没有跨项目资源锁。接续工作优先完善实际文档提取保真与用户预览语义，避免把格式扩展变成新的服务平台。
