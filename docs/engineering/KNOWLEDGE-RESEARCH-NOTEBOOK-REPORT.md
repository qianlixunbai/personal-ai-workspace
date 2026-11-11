# 多问答研究笔记

日期：2026-11-11。基线 `5ffdff9aabb517b2aad87d210ec7a8c99c0743d7`，开发分支 `codex/knowledge-version-compare`。本轮交付一个业务模块：把多份资料研究产生的已保存问答整理成一份带准确出处的 Markdown 笔记。

## 用户流程

在知识库的“基于文档回答”中打开问答历史或搜索历史，在“整理研究笔记”中加入最多 10 条成功完成的回答。可以跨页保留清单，展开原问题、关键词、范围、回答及引用版本，再调整顺序或移除。选择过程不替换正在编辑的问题草稿，也不调用模型。

“核对并导出研究笔记…”先重新定位准确历史记录，核对它仍与选择时相同，再由原生层读取全部已保存问答及其准确源版本摘录。全部成功后才显示一次保存窗口，写入一个新的 `.md` 文件。各题按所选顺序保留原问题、回答、关键词、问答 ID、资料范围、文档 ID、源版本、行号、UTF-16 范围和引用原文，延续既有纯文本围栏处理。

这是所选历史结果的汇编，不生成跨资料综合结论，也不声称覆盖全部资料。明文笔记不是完整资料备份。清单只在当前连接内保留；关闭应用或重新连接后需要重新选择。各题取证时间可能不同，引用版本不会自动换成新版。

## 接线与边界

- `KnowledgeResearchNotebook` 复用 `readAuthorizedAnswerHistory` 从受控列表定位准确 ID，保留预览与顺序。导出前整体核对限时 30 秒；离页／服务不可用会暂停后续读取，即使很快返回也不会续跑。会话更换清空清单并忽略旧保存回执。
- 新 `knowledge.researchExport` 只接受 1–10 个互不重复的规范问答 ID，不接受路径、正文或引用替换。TypeScript 与 Native 严格校验；每条还必须持有既有 `AnswerTasks` 会话资格，列表本身不直接授予导出权限。
- `WorkspaceKnowledge` 共用单题导出的读取和文件保存链。开始前捕获全部授权对象，每次读取后及保存前重新检查原会话／授权仍有效。所有历史和引用读取共用 30 秒截止时间；每份摘录最多 16 页、4096 UTF-16 单位，保持原版本。
- 任一记录未完成、删除、范围错误、原文缺失、授权失效或超时，整份停止，尚不打开保存窗口；不导出部分内容。单题和研究笔记共享导出互斥。
- 文件写入复用 `NativeKnowledgeAnswerExport.SaveNewAsync` 的同目录暂存、文件保护和无覆盖发布，没有修改其路径、链接、网络盘与覆盖检查。默认新文件名为 `knowledge-research-<随机 ID>.md`。取消保留清单；保存结果未知时禁用重试，须先由用户明确检查原生窗口及保存位置。
- 无新 Runtime API、持久化结构、备份格式、模型调用或服务生命周期行为。Java、Profile 与 NativeGate 未改。

## 验证与产物

证据目录：`artifacts/research-notebook-20261111/`。使用既有依赖和合成夹具，没有真实业务资料、产品/helper/NativeGate、真实数据库或模型调用。

| 层 | 实际结果 |
| --- | --- |
| TypeScript | `npm --prefix desktop/frontend run check` 通过。 |
| 前端流程 | `KnowledgeResearchNotebook.test.tsx` 3/3 通过，最终 2.74 秒：跨页／排序／草稿与仅 ID 提交；缺失历史／取消／未知保存不重试；离页暂停／重连清空／迟到回执忽略，并检查空／重复选择拒绝。 |
| Native | `KnowledgeTests.ResearchExport*` 两项新场景及 `KnowledgeTests.AnswerExport*` 两项原场景，4/4 通过，154 ms：额外字段／重复 ID／缺资格拒绝、顺序／文本／旧版／Unicode 保真、后续来源缺失不部分保存、取消、互斥及会话更换。保存窗口为合成实现。 |
| 编译 | Vite production 与资产清单成功；普通 Debug Core／Desktop／Tests 编译通过。Vite 保留大于 500 kB 的块体积告警。无下载、restore 或 Java 重编译。 |
| 包及真实运行 | 未打包、未运行 EXE／WebView／真实保存窗口或退出重开；`productionGo=false`。现有 `933bb20` 来源候选没有本次增量，也没有 `0335ff4` 前端修复。 |

首次类型检查和三个前端场景均通过。静态复核随后补上快速离页返回仍暂停的处理，纳入既有第三个场景，只重跑对应前端文件与类型检查。初始前端及 C# 编译嵌入资产在 `frontend/`；最终前端单独保存在 `frontend-final/`。C# 源码未再变，不重复编译；普通 Desktop 输出仍携带初始前端，不能称为最终整合桌面包。

回执：`frontend-final-check.log`、`frontend-final-tests.log`、`frontend-final-build.log`、`frontend-final/dist/workspace-assets.json`、`dotnet-tests.log`、`test-results/research-notebook.trx`。原工程 `dotnet test --no-restore` 精确筛选上述四项；通过 SDK `--artifacts-path` 写入新 `dotnet/`。三个项目的 NuGet 解析文件原字节复制并核对一致，`FrontendSkipBuild=true` 指向初始资产，两个 MMF3 开关及 RuntimeLifecycleProbe 均为 false。编译／测试取得并于 finally 释放 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun`。

## 中断恢复与回滚

一次编辑尝试调用到 WindowsApps 的 `python.exe` 启动别名，工具报告无法启动，脚本未执行；正常工作区文件编辑随后完成，没有更换身份或权限。首次写本报告及四份文档入口时执行环境断开；接续后只读核实 HEAD 仍为 `5ffdff9`、索引为空、九份源码改动在、本报告不存在且四份入口均未更新。因此本次只补缺失文档，不重复任何已通过的测试或构建。

该模块以精确本地 Git 提交交付，可 revert 回滚代码和文档；不推送，不改 main、六份原始用户材料、旧包或旧证据。Git 回滚不删除用户主动保存的笔记，本轮没有实际导出用户文件。

原开发 Profile 两次正常启动／退出的授权仍保留，但同机界面及正常退出控制仍未确认，真实桌面闭环继续暂停。没有重试 CUA 关联实验、启动后强杀、换 Profile、改身份／ACL 或增加隐藏退出开关。后续真实验收必须另外整合最终前端与准确 C#/Java 来源，不能用本报告的合成结果宣称桌面通过。
