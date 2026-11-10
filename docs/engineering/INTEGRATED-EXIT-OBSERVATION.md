# 最终 r2 包退出后的有界观察

2026-11-10。**本次实际 r2 EXE 正常退出后，自有 guardian Job 从 5 个成员降至 2 个，在 2,941 ms 样本中归零，随后关闭空 Job；没有外层强制清理，也没有持续残留证据。** 产品代码和包没有修改。原未知模型 guard 保留，`productionGo=false`。

## 已有证据与判断

[网页/Runtime 验收](INTEGRATED-WEB-RUNTIME-ACCEPTANCE.md)的原始回执仍保留原结论：Desktop 退出 0 后，外层 Job 中的 5 个成员被包装收尾。该次 WebView `CONTROL_DISPOSE_COMPLETED` 为 `19:18:06.867937Z`，包装收尾回执为 `19:18:07.0383473Z`，只相隔约 170 ms。包装在 Desktop 退出后直接进入 finally，没有观察成员异步结束的窗口。

`AssistantApp.ExitAsync` 已等待工作区关闭和 Runtime Stop；`NativeRuntimeSession.CloseAsync` 会核对关闭协议和实际 JVM 退出。WebView controller Dispose 与外层 Job 所有成员退出并非同一个事件。较早版本的[退出观察](DESKTOP-EXIT-DRAIN-ACCEPTANCE.md)也曾记录约 3.05 秒归零，因此不能直接认定新产品泄漏。本次只确认最终 r2 包的这一时序，不重跑两条阻断记录展示、存储查询或其他业务场景。

## 包装改动与完成判据

新增[聚焦退出观察包装](../../scripts/verification/verify-integrated-development-exit.ps1)，从前次实际 EXE 包装保留原准备、启动及收尾流程，只在 Desktop 正常退出后增加有界的自有 Job 观察：

- 仅使用当前持有的 Job 句柄读取活动成员计数和成员 ID，每约 100 ms 采样，不读取成员的命令行/路径，不扫描全机或旧进程。
- 最多观察 15 秒，且预算取原 420 秒总期限的剩余部分，不增加总运行预算。保留 kill-on-close、每进程 768 MiB / 整 Job 2 GiB、Java 384 MiB / 2 CPU 及原共享锁。
- 同时观察计数为 0 和成员列表为空才记录排空成功；超时保留样本并进入原 Job 收尾，不能把强制清理记作自然排空。
- 分别记录 Desktop 是否被强制结束、Job 是否带活动成员关闭及临时合成 token 清理结果。新 Profile ID 在启动前按产品原有 32 位十六进制规则校验。

包装依赖本地既有准备工具、已验收编译证据，以及原 `.verification/integrated-web-runtime-20261110/Seed.ps1` / `SeedGuard.java` 合成元数据夹具；不是独立安装器或新的产品入口。准备完成后实际 EXE 始终加载原 r2 包的 JAR/DLL，没有测试 class 覆盖或浏览器参数调整。

## 本次实际结果

证据目录 `.verification/integrated-exit-observation-20261110-r2/`，新合成 Profile `4b8d6a66711848329a4699d51840d271`。使用 `--open-development-workspace-once`，默认记录既不读取也不修改。guardian PID 31320，Desktop PID 42076，启动 `19:31:16.6128108Z`；托盘确认原包 JAR `7a51ca97…e3c9a`。React/Bridge 在 12,644 ms 就绪。未调用模型或读取用户数据；合成未知 guard 用于保持外部模型准入关闭，没有重新点击记录查询。

通过本次自有 NotifyIcon 菜单对唯一“退出”控件调用一次 InvokePattern，`19:32:28.4257076Z` 开始关闭，`19:32:28.4538909Z` 完成 WebView Dispose，Desktop 退出 0。包装从 `19:32:28.616584Z` 开始观察：

| 距退出观察开始 | 活动成员数 |
| --- | ---: |
| 0 ms | 5 |
| 120 ms | 2 |
| 2,833 ms | 2 |
| 2,941 ms | 0，成员列表为空 |

观察回执总长 2,944 ms；从准备开始的总耗时约 78.2 秒，未触及 420 秒预算。Job 关闭前为 0，`desktopForced=false`、`guardianClosedActiveJob=false`、`failure=null`；共享锁和 Job 于 `19:32:31.5773942Z` 释放，临时合成 token 已清理。guard SHA-256 前后一致：`96A91B600828D2E5489E1FD4CAE73930C887A88D4F488FF575635499E048938F`。

主要证据为 `startup-exe.json`、`startup-stdout.log`、`startup-exit-menu.json`、`post-desktop-exit-samples.jsonl`、`post-desktop-exit-drain.json`、`guard-after-exit.json` 和 `startup-cleanup.json`。只做一次实际产品启动/退出确认，没有重复包完整性、网页业务场景或增加测试矩阵。

首个准备尝试的包装误用了 28 位 ID，准备工具返回 `NATIVE_PROFILE_INPUT` / exit 10，产品 EXE 尚未启动，Job 0 / 锁释放。修正包装输入规则后使用另一全新合成 ID；原 `.verification/integrated-exit-observation-20261110/` 失败回执保留，未修复或重开该输入对应的 Profile。运行中普通读取日志曾因写入者占用失败，待包装退出后正常读取，没有改共享方式绕过。以上是输入校验/文件占用问题，没有本轮自动审批拒绝；继承的被拒范围均未重试。

## 三个事实的边界

1. **自然收尾观察**：本轮 Desktop 退出后剩余自有成员在外层 guardian 干预前归零。与历史约 3 秒的时序一致，不支持持续泄漏判断；不保证其他机器或版本的时序，也不逐一推断各 PID 的角色或所有产品内部终止行为。
2. **Job 强制清理**：前轮的 5 个成员确实由包装收尾，历史不改写为自然退出；本轮明确在成员为 0 后才关闭 Job。前轮“本次 Runtime 正常关闭”的独立原生证明仍只属于前轮，本轮没有再次运行维护检查，不能用 Job 归零替代一次新的 JVM 协议证明。
3. **远端执行结束**：两轮均未证明旧外部请求终态。自然进程退出、正常 Runtime 关闭、本地取消和人工确认都不能清除未知 guard。真实模型恢复与完整维护继续 BLOCKED / productionGo=false。

结论是修正验收包装的观察时机，无需修改产品退出链，也无需重打包。唯一有效整合包继续为 `artifacts/PersonalAiWorkspace-integrated-development-20261110-r2.zip`。main、六份用户文档、旧包、真实数据和默认配置保持不变；只提交已完成包装与报告，不推送。本会话三个实质结果已完成：整合交付、真实网页联动、最终包退出边界确认；后续接续不再重复这三项。
