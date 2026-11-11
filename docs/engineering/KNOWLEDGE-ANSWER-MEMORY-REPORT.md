# 知识回答摘录与出处回查

2026-11-11，基于 `2cc67eb`，本轮第一项完整模块。原先知识回答只能复制裸文本到记忆，之后无法从记忆回查原问题和引用；现可选取回答片段或整条回答，预览带问答 ID／偏移的正文，重新核对已保存的回答与引用后送入记忆编辑器，由用户明确保存。

保存后可从记忆编辑、只读预览及既有会话记忆入口回查原问答：对比指定范围、阅读原问题／关键词／资料范围／完整回答，并逐项读取当时版本的引用原文。引用跨原文分页时完整读取后才展示，不退回当前版本。记忆正文修改只报告差异，不覆盖正文；原问答或资料删除时保留记忆并报告无法回查。

出处仍是可编辑的定位文字，不是受信来源证明、不可变关系或事实校验。回答保留 ASSISTANT 来源提示；旧的裸文本复制仍明确告知不携带出处。含出处超过既有 2000 UTF-16 上限时要求缩短选段，不截断。没有新增 API、schema、存储、模型调用或自动写入。

## 必要验证

`.verification/knowledge-answer-workflows-20261111/memory-initial-result.json`：TypeScript 类型检查退出 0；新增 `KnowledgeAnswerMemory.test.tsx` 两个合成流程全部通过。第一项使用实际 App／WorkspaceClient 与内存 Bridge，覆盖选段、编辑器保存一次、重读记忆、原问答和旧版本跨页引用、修改差异、来源失效；第二项覆盖超限、半个代理对、回答变化、关闭／编辑／会话重建后的迟到回执。模型提交为零。未重跑已经通过的旧套件。

验证持有 `Local\Dot_KAG_Workspace_HeavyRun`，Node 384 MiB、单 worker；精确完成回执 `failure=null`、`lockReleased=true`。这些是合成 DOM 与源码证据，不代表真实选区、EXE／WebView、数据重启或模型质量验收。

安全候选保持冻结，不读原或替代 Profile／model-state，不执行 helper、NativeGate、模型、部署或打包，不采样系统进程／端口，不触及 KAG、personal-finance-OS、main 或六份未跟踪用户文档。完整验收继续 **BLOCKED / productionGo=false**。
