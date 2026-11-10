# Knowledge 导入后的读取与列表连续性

日期：2026-11-10。接续基线 `66c4a57`；产品提交 `9daf2c11b0020fb2e65469b25242d51f758378fe`。本项是本会话第三个实质模块，前两项见[会话草稿与上下文保护](CONVERSATION-DRAFT-TRANSITIONS-REPORT.md)。工作限于 `personal-ai-workspace`，与 KAG 仓库分开。

## 已确认的问题与完成行为

旧 `KnowledgePage` 的导入流程先改变筛选／页码，再调用仍捕获旧筛选的 `refreshList`；导入处理轮询也持有旧闭包。用户从“已归档”第二页导入资料，或在处理期间切换筛选后，迟到刷新可能把其他视图的列表写回当前界面。初始列表 effect 有失效保护，但这些直接刷新没有同等保护。

导入已受理后，旧流程直到详情和列表读取都成功才提示；详情失败没有保留可继续查询的已受理回执。未知导入的状态读取返回后也没有再次核对会话代次，存在把旧请求的后续详情读取发入新会话的窗口。页尾记录被并发移走后，旧列表留在超出总页数的页码，显示为“还没有文档”。这些都是原管理／导入流程中的实际缺口，未追加来源阅读小控件。

现在文件受理后立即保留原请求回执。详情读取失败会明确区分“文件已接收”和读取错误，可点击“重新读取已接收资料”，通过原 `requestId` 查询状态、读取详情；UNKNOWN 继续显示“检查导入状态”。回执解决前不开放另一份导入，防止覆盖待查请求；没有重发文件或自动解除未知结果。详情读取成功才移除回执；单独的列表失败不抹掉已受理／详情成功提示。

列表刷新在发出时读取当前筛选／页码，并按会话代次、请求顺序和页面可用状态核对回执。切换视图立即使旧请求失效，迟到成功或错误不能覆盖当前列表；处理轮询也按当前视图刷新。加载／失败单独显示，加载时不开放过期列表操作；末页超出总数时回到最后一个有效页。原导入状态返回后，会话若已轮换，不继续读取旧文档。

改动仅在 React 页面及两项针对性场景。复用现有 Bridge／Runtime API，无新增依赖、存储 schema、模型执行、自动写入、凭据访问或安全规则调整。回执属于当前页面内存，不宣称应用关闭或会话轮换后持久恢复。

## 最少必要验证

证据目录：`.verification/knowledge-import-continuity-20261110/`，原始失败与修正后结果均保留。

| 证据 | 结果与范围 |
| --- | --- |
| `frontend-test.log`、`frontend-test2.log` | 两次均只选择本项 2 个场景，1 通过、1 失败，10 个旧场景未选中。失败夹具调用默认带 `result` 的成功响应帮助函数构造错误响应，违反现有 Bridge 错误信封合同；第二次只改错误码仍未解决。产品正确拒绝，未放宽验证器。 |
| `frontend-test3.log` | 修正合成错误信封后，同一选择集 2/2 通过，10 个旧场景跳过。覆盖已受理后详情失败、仅查原请求、不重复上传、列表失败仍保留成功事实、末页回退、处理轮询跨筛选、迟到列表错误、状态查询已返回但 continuation 前会话轮换。 |
| `frontend-test3-check.log` | `tsc --noEmit` 通过。 |
| `test3-cleanup.json` | 21:07:32Z，关闭前自有 Job 0，Job／共享锁释放，failure=null。前两次失败的收尾也保留。 |
| `product.patch` | 提交前两文件完整补丁；`git diff --check` 通过。 |

没有重跑前两项会话修复的前端 6 项／Native 1 项、旧阅读组或全量测试。测试中的 UI／Bridge 为合成场景，不等同于真实 EXE／WebView 操作。重资源继续走原包装、`Local\Dot_KAG_Workspace_HeavyRun`、自有 kill-on-close Job、450 秒总预算、768 MiB 单进程／2 GiB Job，Node 384 MiB／单 worker。

## 独立交付

使用既有离线脚本生成独立[新开发 ZIP](../../artifacts/PersonalAiWorkspace-import-continuity-20261110.zip)和[解压 EXE](../../artifacts/PersonalAiWorkspace-import-continuity-20261110/PersonalAiWorkspace.Desktop.exe)，没有替换现有安装。最新包包含本会话三项产品修复。

| 交付证据 | 结果 |
| --- | --- |
| `delivery-result.json` 产品来源 | `9daf2c11b0020fb2e65469b25242d51f758378fe`；构建期间产品源码稳定 |
| ZIP SHA-256 | `5F67938BFF378B2993E19D22D4D8542F946EA0EDF7C627D09E7F51E336F06548` |
| Runtime JAR SHA-256 | `28972C5BB099D0F13E7AAE73644FD0131C91DEF18DDF2119AFFB700524FC2361` |
| Desktop DLL SHA-256 | `1389ED1BC9727A9C2BF146AE5CAC1C4393944BCD91F58A0023860475204D476F` |
| 构建／归档校验 | TypeScript／Vite、194 个 Java 产品源、567 个 class、Desktop publish、933 载荷／934 ZIP 条目／420 项内嵌来源 pin 均通过；既有 Java 警告和 Vite 大 chunk 提示保留 |
| `build-cleanup.json` | 21:09:49Z，关闭前自有 Job 0，Job／共享锁释放，failure=null |
| `protected-result.json` | main、六份用户文档、r2／source-find／source-reading／reading-navigation／conversation-drafts 五个旧 ZIP 的哈希不变；本项所有 worker 均完成，收尾 Job 0 |

只有已完成且必要检查通过的产品改动提交到当前开发分支，文档另行提交，可分别回滚；未推送。无需重跑前两项已通过验证，包构建／来源校验也不代替完整产品验收。

## 未解除的边界与接续

原动作仍停止：为原合成 Profile `b204c7e3cc33425e841eb1aa32d2caa2` 登记 `model-state`，目标为 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2\model-state`。`ModelStateStore` 构造时在 `verify(root, true)` 抛出 `Invalid model state ownership or type`；这是已知产品拒绝，不是本轮自动审批拒绝或结果未知的启动。拒绝后 `.lock`、armGuard 和 EXE／WebView 验收未运行。本轮没有重试、另建同类 Profile／换身份、改 ACL／安全配置或削弱检查。

[身份来源核对](MODEL-STATE-IDENTITY-SOURCE-REVIEW.md)区分了 Java 的环境用户名来源和原生有效令牌 SID，但缺原失败时的对应值，根因未证实，不能据此解除上述边界。旧端口／进程／Profile 等拒绝及未知远端请求保持原状态；本地退出不证明远端结束。

**新包完整 EXE／WebView、真实模型恢复与整体维护仍未验收；BLOCKED / productionGo=false。** 本轮未调用真实／付费模型、传输用户数据、读取真实凭据、迁移真实数据库或改变默认安装。没有调用被拒外部反馈或许可未明确的 Tavily。

三个实质模块现已完成：网页会话草稿保护 `bcd9f87`、原生会话上下文保护 `f061213`、本项 Knowledge 导入连续性 `9daf2c1`。下一会话先读本报告和前两项报告，按明确用户流程／确切新相关证据推进；不为计数添加小项，不重跑已通过组，不把包校验作为桌面验收。所请求模型、thinking 与 service tier 仍无法从执行接口切换或核验。
