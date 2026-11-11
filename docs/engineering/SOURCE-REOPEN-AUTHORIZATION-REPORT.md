# 资料与对话出处的重开读取资格

日期：2026-11-11。整合基线 `27db39f`；正式普通 Debug 编译证据见 [当前源码整合报告](WORKSPACE-INTEGRATION-COMPILE-REPORT.md)。这是已有出处功能的前端／Native 接线修复，不新增来源存储、自动模型上下文或 Native 授权入口。

## 资料出处

`MemorySourceReview` 和会话参考最终核对原来直接用正文／临时参考中的 ID 调用 `knowledge.get`；Native `WorkspaceKnowledge.GetAsync` 要求当前 session 的 Documents 集合包含该 ID。应用重开后读取资格为空，原有宽松前端夹具却直接返回详情，因此旧合成通过不能证明真实重开可用。

新增共用 `readAuthorizedKnowledge`，先沿现有 ACTIVE／ARCHIVED 列表定位准确文档 ID，每种状态最多 25 页、总期限 30 秒。找到后立即读取该 ID 详情，再由原流程核对原源版本及字符范围。找不到就停止，不用同名资料、相似内容或新版代替。每次 await 前后检查当前 UI/session，迟到列表不继续发详情；失败不自动重试。不缓存资格，不放宽 Native，也不改变原文或当前草稿。

直接验证：`npm run check` 通过；只选择 MemorySourceReview 的两个原场景和 ConversationReferences 的正常参考流程，**3 passed / 1 skipped**，4.06 秒。Memory 夹具增加与 Native 相同的“先列表才能详情”约束，并在 session 更换时清除资格；验证归档回查、已删除 ID 不请求详情、编辑后迟到列表不继续，以及原版本／范围、错误／session／草稿保护。原只读操作断言增加必要的列表调用。没有跑历史全套或新增验证框架。

本节证据是前端合成流程与类型检查，不是实际应用重开、磁盘持久化或原生准入验收。资料底层 C# 在本轮整合基线普通配置已编译；本修复只改前端。

提交 `7e7332e` 已纳入全新独立目录的最终前端及正式 Core／Desktop 构建，退出 0、0 警告／0 错误，4.08 秒。没有 restore 或实际应用运行。对应 [最终整合回执](WORKSPACE-INTEGRATION-COMPILE-REPORT.md) 保留源码版本与日志。

## 对话出处：补齐重开后的列表授权

`92a0764` 基线仍有缺口：`readConversationSource` 直接调用 `getConversation`，而 Native `WorkspaceConversations.GetAsync` 要求当前 session 的 Known 集合已经包含该会话。Memory 中的可编辑会话／轮次／消息定位不具有该资格。

等待原 Profile 启动范围答复期间，现已补齐共用读取函数：先沿现有 ACTIVE／ARCHIVED 列表定位准确会话 ID，每种状态最多 100 页（现有合同每页 10 条、最多 1000 个会话），找到后才读取原轮次页，再执行原有轮次 ID、序号、消息 ID、角色及字符范围核对。每次列表请求前后检查 UI/session 是否仍有效及 30 秒预算；迟到列表不继续发详情，找不到就停止，不改查同名会话或其他消息，不缓存资格，不放宽 Native。

修复共用于记忆对话出处、消息摘录核对、工作区消息阅读和跨会话参考。摘录夹具现在要求先列表授权，并在 session 变化时清除资格；归档回查前清空资格，缺失 ID 不发详情，编辑／session 更换后的迟到列表不继续读取。原有消息身份、Unicode 范围、草稿与只读约束保留。

验证仅为 `npm run check` 和两个既有前端文件的 4 个场景，没有新增测试设施。类型检查通过；首次运行 3 passed / 1 failed（6.03 秒），失败是跨会话参考的旧只读调用断言遗漏新增的两次列表调用；修正该断言后仅重跑该文件，2 passed（4.03 秒）。结合首次通过的摘录文件 2 个场景，4 个场景最终均通过。检查各自持有并释放共享重型 mutex。本轮不执行 Vite build、C#/Java 编译、打包、产品/helper/NativeGate 或真实 Profile 读写。此修复尚未纳入来源 `933bb20` 的既有候选包，不把前端合成场景当作原生退出重开验收。
