# Markdown 资料章节目录

2026-11-11，接续 `866fd6f`。这是执行边界收紧后的独立源码开发，最初只完成静态审查并保留待验；后续收到针对章节目录这一不同目标的明确验证授权，现已完成类型检查和两项新的合成 UI 场景。没有将 `f6d6a84` 的旧测试输出算作本功能证据。原待验报告 `KNOWLEDGE-OUTLINE-PENDING.md` 随本次交付移至此路径，六份用户文档未修改。

## 已实现行为

现有资料阅读支持分页、原文查找与行号定位，但用户不知道章节行号时没有标题目录。新增一个从目录打开原文的完整阅读入口，仅对所选源版本类型为 MARKDOWN 时显示。

- `desktop/frontend/src/components/knowledgeOutline.ts`：沿既有 preview API 读取 Runtime 已解析的 locator，复用 heading、section、行号与 UTF-16 原文位置；不建立第二个 Markdown 解析器，不重新解释代码围栏或标题语法。
- `desktop/frontend/src/components/KnowledgeSourceOutline.tsx`：用户主动读取目录，支持标题字面筛选、每页 20 项、停止读取以及空目录／失败说明。标题作为纯文本显示；目录仅属于当前资料版本和 Bridge session，离页／切换版本后清除。
- `desktop/frontend/src/pages/KnowledgePage.tsx`：接入上述组件；点击标题复用原版本原文读取与既有阅读位置返回流程。读取失败不替换当前原文或消耗返回记录。

扫描按 locator.endOffset 进入下一原文分段，不在 React 保留全文；长章节跨分段时只列出标题起始行，不将每个分段当作新章节。最多 128 次原文预览、30 秒；读到明确结束才显示目录，超限／失败不显示部分目录。运行时只识别既有 text-1 解析器支持的 # 标题，长标题沿用原接口的缩略，TXT／Word／PDF 不生成推测目录。

## 本轮静态审查

已逐项阅读 `KnowledgeParser.represent`、`KnowledgeStore.preview`、Core 的 `KPreview`／`PreviewKnowledgeAsync`、Native `PreviewAsync`、前端 preview 校验与父页面 `openComparedSource`。源码合同支持 locator 边界读取及文件末尾空预览；单个长分段可跳过剩余正文，只保留标题位置。点击使用固定 revision，不回退到当前新版；原生权限仍由既有 session/document 授权约束。

人工检查了 imports、状态代次、异步检查点、分页／筛选边界和事件接线；静态审查期间调整了 utility 文件名，避免 Windows 大小写不敏感下与 TSX 组件同名解析冲突。`git diff --check` 仅用于补丁空白审查，不是类型或行为验证。

此前待验的长章节去重、同名标题分别定位、长末段 EOF、旧版本导航／失败保留原文、停止／离页／session 迟到回执现由下列合成场景覆盖。128 次上限及 30 秒边界只做静态检查，未扩充边界测试或运行容量／性能矩阵。

## 独立验证记录

新增 `KnowledgeSourceOutline.test.tsx`，复用现有内存 KnowledgeService／WorkspaceClient，在实际 KnowledgePage 上验证；另一个场景检查目录组件的停止与代次隔离。没有运行 Java、Native、数据库、Profile、JNA、模型或真实浏览器。

执行使用仓库现有 package.json 的 `check`／`test` 入口，测试目标只包含 `src/components/KnowledgeSourceOutline.test.tsx`；没有调用、复制或加载原被拒脚本，没有重跑旧文本操作恢复套件。新证据仅写入 `.verification/knowledge-outline-20261111/`。

- `initial-result.json`：类型检查退出 0，UI 一项通过、一项失败。测试错误地把最后一条 Bridge 请求当作原文读取，实际末条是相邻检索组件的索引状态查询；原文位置和显示断言此前已通过。保留原输出，断言改为精确筛选 knowledge.preview。
- `preview-assertion-result.json`：类型检查退出 0，目录两项场景全部通过。覆盖长章节多分段只出现一次、同名标题分别定位、标题纯文本显示、长末段结束、旧版到原文及返回、读取失败不消耗阅读位置、切换新版清除旧目录、停止／离页／session 迟到回执。
- 两次均为 Windows Session 1、Node 堆上限 384 MiB、Vitest 单 worker，并持有共享 `Local\Dot_KAG_Workspace_HeavyRun` 全生命周期互斥。两份回执均已释放锁；最后进程退出 0、`failure=null`，没有已知在途检查。

这些结果不等于真实 EXE／WebView、实际大型资料性能或模型验收。目录与标题语义沿用现有解析器；不承诺识别 Setext 标题、TXT 或 Word／PDF 目录。

## 交接与后续价值判断

本会话实际产品模块是 `f6d6a84` 的文本任务核对和本次章节目录，`866fd6f` 事实更正不计产品模块。当前最优先的完整产品关卡仍是正常 WebView／同包启动与退出、未知宿主操作的真实恢复、个人数据恢复，而非继续加目录／筛选／参考的同类入口。它们依赖仍冻结的原生边界或未明确的执行事实；应取得对应范围的明确决定后推进，不把本次合成检查当作解除依据。新的独立核心缺口可另行按实际价值选择，不为数量补微小规则。

原 `.verification/text-operation-recovery-20261111/Run.ps1` 的拒绝及后续两次 PowerShell 7 执行事实，见[准确执行记录](TEXT-OPERATION-RECONCILIATION-REPORT.md)。该脚本及等价流程继续永久停止，不以这次不同目标的授权解除，也不为补证再执行；旧运行缓存未单独清点的边界保留。

`f6d6a84` 和 `866fd6f` 均保留，目录模块只在必要检查后本地提交。main、六份用户文档、ModelStateStore／WindowsPrivateAcl／WindowsNativeGate、安全候选与旧工件保持不变。七个拒绝目录、Profile/model-state、helper／NativeGate／armGuard／模型、系统进程／端口查询等原有边界全部保持；不推送、不打包、不部署。旧 ZIP 不含本功能，完整验收仍 BLOCKED / productionGo=false。
