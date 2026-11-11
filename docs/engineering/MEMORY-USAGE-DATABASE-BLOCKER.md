# 记忆使用记录：数据库初始化失败诊断

2026-11-11，基线 `0a96dda9f0e93ca3a8cb813cade685ca372e5623`，分支 `codex/knowledge-version-compare`。

## 已完成修复与证据边界

修正 `ConversationStoreTest.close()`：仅关闭成功构造的对象，用 finally 保证 ConversationStore 关闭发生异常时仍尝试释放 MemoryStore。这消除初始化失败后的附加空指针，不改变产品、安全检查或数据库查询。

上轮唯一数据库用例仍为 **BLOCKED / 查询断言未运行**。本轮仅静态检查清理代码和差异，不为此低风险修复重复 Maven、前端、C# 或旧数据库测试；不能把清理修复称为原初始化问题已解决。

## 精确诊断

读取范围限定为[上轮报告](MEMORY-CONVERSATION-USAGE-REPORT.md)指出的 `artifacts/memory-usage-20261111/maven-test.log`、`maven-exit.json`、`source/target/surefire-reports/TEST-io.github.qianlixunbai.workspace.conversation.ConversationStoreTest.xml`、同名文本报告及 `2026-11-11T19-22-22_322.dumpstream`，未泛遍历验证目录。

- Maven 退出 1。`memoryUsageFindsExactVersionsPagesAndArchivedTurnsWithoutChangingHistory` 在 `@BeforeEach open()` 的 MemoryStore 构造阶段报 `MEMORY_STORAGE_UNAVAILABLE`，方法正文未执行；清理时另报 store 为 null。
- XML 保存 Windows 11 / Java 21.0.7、普通 Surefire 启动及 `argLine=-Xmx384m`；没有 `workspace.native.development-profile`、`workspace.native.bootstrap` 或 `jna.*` 属性。这是旧 JVM 证据，不是当前账户/目录采样。
- 当前源码链为 `MemoryStore` → `PrivateMemoryDirectory.prepare` → `directory` → `WindowsPrivateAcl.directory` → `WindowsNativeGate.admit`。Gate 要求真实的受信 Bootstrap、32 位十六进制 Profile ID，并验证实际 Profile、固定字节和 JNA/原生所有权；普通 Surefire 参数缺少必要条件。
- **缺参数不是已验证的旧根因。** 路径检查位于 Gate 前，旧异常被安全包装而没有阶段/cause；无法判断当时最先失败的是路径、NativeGate、ACL 或其他步骤。classpath 的 dumpstream 提示也不等于根因，测试类本身已经开始执行。

## 后续实际验收约束

保留 NativeGate、owner/ACL、路径和 Profile 全部检查。不通过伪造系统属性、换身份/目录/数据库、改 ACL、反射设置已准入状态或替代入口绕过失败。不能把跳过测试计为通过，也不重建整包替代诊断。

先审查已有正常测试启动入口，明确它的输入、目标 Profile/数据目录、正常退出方式及副作用，再决定是否覆盖现有授权。原 Profile 两次正常产品启动/退出的许可不自动授权新的原生测试路径。当前尚未启动 NativeGate、helper、数据库、产品或模型；SQLite 查询和 PostgreSQL 行为/性能仍未验收，最终集成桌面包未生成，`productionGo=false`。

已有入口已完成源码审查并收窄为单条显式场景；具体输入、准备顺序、Profile 创建、原生加载、SQLite 写入和自有子进程超时清理见[最小原生测试方案](MEMORY-USAGE-NATIVE-TEST-PLAN.md)。此方案未编译/执行，未将原启动许可外推到新入口。

后续父任务明确授权该独立测试路径。[一次执行记录](MEMORY-USAGE-NATIVE-TEST-REPORT.md)确认必要编译及 Profile 准备完成，但宿主反射查找重载出现歧义，在创建 Java 前退出 10。该普通代码缺陷已修复并编译，未再运行；原数据库初始化根因与查询验证缺口仍保留。

再后续：父任务明确授权同一合成 Profile 续跑一次。新增显式续跑入口并仅编译宿主后，run-2 在 INPUT 阶段退出，Java 仍未启动；已保存回执复核符合，根绑定字符串比对仍待定位，未再次运行或放松校验。具体事实以[执行报告末节](MEMORY-USAGE-NATIVE-TEST-REPORT.md#同一-profile-获准续跑run-2)为准。

## 断线与恢复

前两次补丁在读取首文件时断连，无成功回执。恢复后的只读核对确认 HEAD 未变、工作树/索引无差异、两份已有文件与 HEAD 相同、新报告不存在，才应用本次修复。无未知动作重放，无新的自动审批拒绝。六份入场未跟踪文档、main、用户数据、旧失败证据和候选不改动；仅本地 Git 保存，不推送。
