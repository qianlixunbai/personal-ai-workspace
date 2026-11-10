# 桌面退出后的 Job 成员收尾观察

2026-11-10。对先前三次实际托盘退出后仍有 5 个成员的疑点，只增加一次有界观察。结果：**桌面退出码 0 后，持有的 guardian Job 从 5 个成员降为 2 个，在 3,049 ms 样本中归零；观察总长 3,050 ms，随后才关闭空 Job。此次无需 guardian 强制收尾。** 没有据此改产品退出逻辑。

## 为什么需要新证据

[正常网页验收](MEMORY-NORMAL-WEB-EXE-ACCEPTANCE.md) 的三次 guardian 在桌面退出后直接进入 finally，关闭仍有 5 个成员的 kill-on-close Job。该事实仍成立，不改写为自然退出。

源码显示 `AssistantApp.ExitAsync` 等待 Runtime Owner 清理、WebView Profile 清理及 controller Dispose；guardian 没有等待退出后 Job 排空。Microsoft 的 [BrowserProcessExited 说明](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2environment.browserprocessexited) 区分 controller 关闭与浏览器进程组全部退出、资源释放。结合先前时序，合理的下一步是观察自有 Job，不能直接判定泄漏，更不能枚举旧 PID 或放宽安全设置。

## 实际执行

- 使用未修改的 `artifacts/PersonalAiWorkspace-normal-web-verified-20261110`：产品来源仍为 `64b33b2991acb407d6887040ec1fe7ae902b0afb`，Desktop DLL SHA-256 `FCD718C1BA2B213752211D7F578A83908C0EBB788E22D774C51B9818FA873B02`。本轮迁移修复尚未进入这个包。
- Profile 是本轮新建的 `2d0e6baed0a14bf9a1da7b9bf4495182`，仅有本轮合成资源；用 `--open-development-workspace-once`，没有切换默认配置。`last-workspace` 前后均不存在。
- guardian PID `33660`，Desktop PID `28828`，启动 `10:14:35.1047304 UTC`。正常 React/Bridge 在 12,672 ms 就绪；托盘显示同一 Profile 和既有运行 JAR SHA。没有保存记忆、调用模型或重做保存/重开全套。
- `agent-desktop` 技能文件可读，PATH 中仍没有 CLI。复用已审阅的 Windows UI Automation 脚本，按本次持有的 PID/启动时间/EXE 路径验证身份，向唯一自有 NotifyIcon 句柄请求菜单，再对唯一“退出”控件调用一次 InvokePattern。无全局鼠标/键盘输入。
- `10:15:31.8627462 UTC` 产品开始关闭；13 ms 左右完成 Profile 清理，`10:15:31.897211 UTC` 完成 controller Dispose。Desktop 实际退出码 0。

| 距桌面退出观察开始 | guardian Job 活动成员 |
| --- | ---: |
| 1 ms | 5 |
| 108 ms | 2 |
| 2,940 ms | 2 |
| 3,049 ms | 0（成员列表为空） |

这里只通过持有 Job 的 QueryInformationJobObject 读取成员与计数，没有读取这些 PID 的命令行、路径或其他系统元数据，因此不把成员逐一归类为浏览器/Java。结论限于本次 Job 中剩余成员在 guardian 干预前全部消失。Runtime Owner 本身仍使用原有 Job/kill 清理机制，**不宣称 Java graceful shutdown，也不宣称所有子进程从未受产品终止机制影响**。本次没有出现需要修改产品的持续残留证据。

## 收尾与可追溯性

本地 `.verification/desktop-exit-drain-20261110/` 保留 `Run.ps1`、`Ui.ps1`、`startup-exe.json`、`startup-exit-menu-ready.json`、`startup-stdout.log`、`post-desktop-exit-drain.json` 与 `startup-cleanup.json`。guardian 最大排空观察预算为 15 秒，实际约 3 秒结束。Job 关闭前活动成员 0，`guardianForced=false`；`10:15:35.0503867 UTC` Job 和 Session 1 共享锁均释放。

没有重新查询 18765、旧进程元数据或被拒 Crashpad/私有目录，没有操作 KAG、真实 Profile、数据库配置、权限或系统策略。无本轮拒绝、待审批、结果不明或仍运行的自有 guardian。无参数默认入口、完整维护迁移、个人恢复和正式发布仍未验收。一次排空观察不保证全部机器/浏览器版本的退出时序。
