# 整合开发包的网页、Runtime 与退出交接验收

2026-11-10。最终 **`artifacts/PersonalAiWorkspace-integrated-development-20261110-r2.zip`** 的实际 EXE 已通过一次隔离合成场景：普通网页启动、原生认证、SQLite 状态查询、查看两条未知模型请求，以及托盘退出后的原生关闭核对。产品文件没有修改，继续使用原 r2 包，不重新打包。真实模型恢复和完整维护仍 **BLOCKED / productionGo=false**。

## 用户价值与完成判据

此前整合交付只验收了原生只读窗口，尚未证明包内 React、Bridge 和实际 Runtime 可以一起工作。本次以用户实际点击的“查看阻断请求（只读）”为入口，要求显示合成请求的真实持久记录、保持未知状态和恢复禁用，并在正常退出后由独立原生入口确认本次 Runtime 已关闭、guard 未清除。没有增加仅供验证器调用的产品入口。

使用交付提交 `1b5e47a` 的最终包，产品源码 `75b9614`；本次 EXE 验收没有覆盖或替换 DLL/JAR。既有完整归档、文件与 pin 检查不重跑，沿用[整合交付报告](INTEGRATED-DEVELOPMENT-DELIVERY.md)的完整性证据。

## 实际产品链路与结果

证据目录：`.verification/integrated-web-runtime-20261110/`。新建合成 Profile `b291469d704a4ea781901967f2570565`，通过现有准备工具和真实 `ModelStateStore.armGuard` 写入两条请求元数据，然后关闭准备进程。准备没有提供商、HTTP 客户端或推理任务；应用运行时使用包内原始 JAR，没有加载准备用 class。

两条记录分别是推理/验证 `11111111-1111-4111-8111-111111111111` 和卸载 `22222222-2222-4222-8222-222222222222`；模型名 `synthetic-inspection:latest`。这些只是合成登记，不能证明任何真实模型执行已发生或结束。

1. 最终 EXE 以 `--open-development-workspace-once` 打开该 Profile，保留真实默认工作区。实际 WebView2 加载 React，日志在启动后 12,650 ms 记录 `REACT_BRIDGE_READY / OK`。设置页显示 Runtime 可访问、原生认证有效；点击“重新检查存储”后显示当前使用 SQLite。
2. 展开“高级模型操作与恢复”，点击一次“查看阻断请求（只读）”。真实 React → Bridge → Runtime → 本地状态读取返回两条记录，各自显示编号、类型、模型、运行器、选择版本和摘要，并标为“没有可核验的结束证明”。页面说明此前运行的记录仍按未知处理；“恢复暂不可用”保持禁用。
3. 页面显示“本地记录已读取；未联系 Ollama，也未修改执行状态”。未点击“只读刷新模型”、验证、切换或恢复；只读请求检查不调用提供商。没有执行模型、联系付费服务、传输用户数据或读取真实凭据。本次没有做网络抓包，不以页面文案代替独立网络测量。
4. 通过实际托盘菜单“退出”，Desktop 在外层收尾前退出 0，WebView 清理和 Dispose 均完成。随后用同一个最终 EXE 的只读维护入口核对该 Profile：显示 1 次启动登记、1 次在 owner 强制收尾前退出 0、其中 1 次确认本次应用正常关闭；维护继续 BLOCKED，模型仍存在未解决状态。关闭检查窗口退出 3，是维护阻断状态的预期结果。

退出核对使用产品持久生命周期记录。正常关闭证明要求 `CLOSE / CONTEXT_CLOSED`、实际 JVM 退出和内部 Runtime Job 清空；它只覆盖本次拥有的 Runtime，不能证明旧外部模型请求或其他写入者已经停止。

合成 guard 在网页读取、退出和原生核对后保持同一 SHA-256：`5B2FB6AC0635D76693E9D2BDEFF29A9E617F2117C54E27A838B685791223D51E`。未删除、改写或人工解除 guard；Profile 和历史证据保留，只清理本次临时客户端 token。

## 可核实证据与资源收尾

| 证据 | 结论 |
| --- | --- |
| `startup-exe.json`、`startup-stdout.log` | 最终 EXE 路径、仅本次打开、React 就绪、Desktop 退出 0 |
| `startup-advanced-ui.json` | Runtime 认证与 SQLite 状态 |
| `startup-execution-records.json`、`startup-exit-menu.json` | 两条真实页面记录、禁用恢复、当前包 Runtime 来源 |
| [网页实拍](../../.verification/integrated-web-runtime-20261110/startup-execution-records-bottom.png) | 两条记录均可见，页面语义与显示相符 |
| `after-exit-uia.json`、[退出后维护页实拍](../../.verification/integrated-web-runtime-20261110/after-exit-maintenance.png) | 本次原生正常关闭证明与持续阻断同时存在 |
| `shutdown-inspection-result.json`、`inspection-cleanup.json` | guard 未变、检查通过、检查 EXE 无强制退出、临时 token 已清理 |
| `startup-cleanup.json`、`run-cleanup.json` | 两轮拥有的 Job 与共享锁释放，failure=null |

沿用 `Local\Dot_KAG_Workspace_HeavyRun`、拥有的 Job、每进程 768 MiB / 整 Job 2 GiB；产品 Java 使用原有 384 MiB / 2 CPU 限制。普通会话总期限 420 秒，后续原生检查 450 秒。没有修改浏览器 GPU 参数、系统安全、网络或产品准入，也没有借用 KAG 环境。

普通会话的 Desktop 已自然退出，但外层 guardian 收尾前仍有 **5 个本 Job 成员**，随后由 Job 关闭清理。不能把它表述为整个桌面进程树自然退出；本次 Runtime 正常关闭由独立原生记录另行证明。原生检查 guardian 收尾前为 Job 0。没有按旧 PID 查找、控制未知进程或查询旧端口。

首次对 HTML 展开控件请求 InvokePattern 时发现其不支持，动作尚未发送；读取该控件支持的 ExpandCollapsePattern 后正常展开。没有产品故障、权限拒绝或不确定的重复操作。本轮没有自动审批拒绝；继承的拒绝操作均未重试。

## 更正与未完成范围

上份交付报告把普通完整入口描述为固定端口，这一判断有误。现有 [RunWorkspaceAsync](../../desktop/src/PersonalAiWorkspace.Desktop/DevelopmentRuntimeOwner.Migrated.cs) 对普通工作区调用 `RunOwnedAsync`，后者使用 `ownedLifecycle: true`；[StartInfo](../../desktop/src/PersonalAiWorkspace.Desktop/TrustedDevelopmentRuntimeSources.cs) 已设置 `--server.port=0`，通过本次拥有的原生会话绑定返回端口。固定 18765 属于其他分支，不是本次入口。此次只核对并使用已有实现，没有为验收调整端点或放宽所有权保护。

通过范围是这一次新合成工作区的网页/Runtime/只读请求检查与正常退出，不是所有页面视觉验收、默认工作区启动、受限迁移会话全流程、真实模型恢复或完整维护。当前提供商仍缺请求级终态查询/取消凭证；本地关闭、人工勾选或合成记录不能解除真实旧请求阻断。

main 与六份用户文档保持不变，真实数据、既有安装、默认配置和旧包未修改，不推送。本次只提交验收结果及过时状态说明的修正；本会话已完成整合交付和实际网页联动两个实质模块，不为数量追加验证旁路。下一步应选择无需真实模型的新用户能力，或先明确真实恢复所需的提供商所有权合同；不要重复本次已通过的包检查与页面场景。
