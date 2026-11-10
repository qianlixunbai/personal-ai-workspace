# 正常网页与完整 EXE / Runtime 验收

2026-11-10，接续 `bef2bf49dc7d6bdcd1287c4c40e05fd2588f1337`。**已通过实际完整 EXE → 正常 React 网页 → 自有 Runtime 认证与 SQLite 状态 → 托盘退出，以及正常网页的一次合成 Memory 保存 → 独立 EXE/Runtime 重开读回。** 产品代码没有修改，没有重跑原生备用入口验收，也没有调用模型。

## 入口、来源与权限边界

实际执行包是 `artifacts/PersonalAiWorkspace-webview-default-profile-20261110`，产品提交 `64b33b2991acb407d6887040ec1fe7ae902b0afb`；原 ZIP SHA-256 `BD29D41EED4F83C8D14FF5C539C0F64323918D536F7BAC05CE109D4A057AFEA9`。每次 guardian 在启动前核对包清单和 Desktop DLL SHA-256 `FCD718C1BA2B213752211D7F578A83908C0EBB788E22D774C51B9818FA873B02`。没有向产品包添加实验宿主、测试入口或覆盖浏览器参数。

用户在自己的 PowerShell 执行 `Get-NetTCPConnection -LocalPort 18765 -State Listen -ErrorAction Stop`，返回“未找到匹配的 MSFT_NetTCPConnection 对象”。该结果证明用户查询时没有匹配监听项，不是访问拒绝，也不保证未来持续空闲。代理先前收到的 `PermissionDenied / 0x80041003` 仍保留，**没有重试该系统查询、换 API 检查端口或扫描旧进程**。本次按后续明确授权进入应用自己的未修改准入/绑定路径；这些成功的应用运行结果与用户的时点查询分别记录，不冒充系统端口关闭检查。

新合成 Profile：`b74a632ed0bc4689ac311d55e1087f02`，通过已有原生 Prepare/Provision/Parse 工具创建，准备进程 PID 29568、退出码 0。工具 DLL 的 SHA-256 为 `62BB9FB11C2CD86C0048DD412FE383813128535E2384CB8935259E5F8F942450`，仅准备阶段使用。实际 OS 绑定根目录记录于 `.verification/webview-full-exe-20261110/prepare/owned-profile.json`。随后三次均运行真正产品 EXE：

```text
PersonalAiWorkspace.Desktop.exe --open-development-workspace-once b74a632ed0bc4689ac311d55e1087f02
```

源码绑定证据见 [WebView2 调查](MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md)：原生准入要求 token、data、model-state 都属于所选 Profile，RuntimeOwner 只管理自己启动的子进程；成功认证状态受该 Owner 的存活/Ready 检查约束。每次实际托盘均显示上述 Profile 及运行中 JAR SHA-256 `bc8f0cc35d2f9321af848f7ffdfe271db07b770cf1878454f07a8fd0c0d12625`，与验收包相同。没有使用未知已有服务或读取真实数据库。

## 实际结果

| 阶段 | Desktop PID / guardian PID | React / Bridge 就绪 | 页面操作与结果 |
| --- | --- | --- | --- |
| 启动与退出 | 304 / 36476 | 12,599 ms | 正常本地网页可见；点击 Runtime 状态和“重新检查存储”，显示原生认证有效、SQLite；真实托盘退出，桌面退出码 0。 |
| 网页保存 | 27540 / 36180 | 12,617 ms | 初始列表为空；通过网页输入合成标题/正文，点击“保存”一次；出现唯一记录，提示 `Memory 已保存。`；真实托盘退出，桌面退出码 0。 |
| 独立重开 | 24424 / 31488 | 12,584 ms | 新 EXE/Runtime 再次进入正常网页，列表读回同一 ID；明确加载后标题、正文、状态、版本和显示更新时间与保存后相同；没有再次保存；真实托盘退出，桌面退出码 0。 |

Memory ID：`549b5951-60ac-436d-9dd2-e631d9cf2c26`；类型 `PROJECT_NOTE`，状态 `ACTIVE`，Revision `1`，显示更新时间 `2026-11-10T09:46:32.981+00:00`。标题为“网页完整入口合成记忆 20261110”，正文只含合成验收说明。保存和重开后的网页列表均恰好显示一条记录；编辑器正文逐字相同，重开提示“已加载 Runtime 保存的 Memory。”。这证明此次业务记录保留，不宣称整库字节不变、启动零写入或全部业务表均无变化。

每次日志都包含 `PROFILE_VALIDATED`、`CONTROLLER_CREATED`、`PROFILE_CLEAR_COMPLETED`、`REACT_BRIDGE_READY`、`CLOSE_CLEAR_COMPLETED` 和 `CONTROL_DISPOSE_COMPLETED`，无本轮启动失败。第一次控制器约 234 ms 完成，启动清理约 12,062 ms，关闭清理约 13 ms。正常页截图和 UI Automation 均确认 InPrivate 页面及本地受信 URL；不据此推导此前失败的唯一根因。

## 交互与证据

本机仍未安装 agent-desktop CLI；按已读桌面操作技能，复用 Windows UI Automation，以本轮 EXE PID、创建时间、映像路径和唯一控件身份进行操作。首个 Chromium UIA 树较薄，等待稳定后由新调用读取完整网页，没有重启或加 accessibility 参数。重开记录按钮支持 TogglePattern 而非 InvokePattern；首次获取 InvokePattern 在发送动作前报 `Unsupported Pattern`，随后只读确认 ToggleState=Off，再执行一次受支持的选择。没有重复保存、绕过权限或变更产品代码。

本轮 evidence 根目录为 `.verification/webview-full-exe-20261110/`：

- `Run.ps1`、`Ui.ps1`、`Capture.ps1`：由原 guardian/UI 辅助脚本复用，去掉额外系统端口查询与进程枚举；操作仅限本轮合成窗口。
- `phase-results.json`、`memory-comparison.json`：三次就绪/退出和网页字段比对结果。
- 三组 `*-exe.json`、`*-cleanup.json`、`*-job-members.jsonl`、`*-stdout.log`、`*-stderr.log`：真实进程句柄、Job 成员、应用日志与收尾证据。
- `startup-storage-ready-ui.json`、`save-save-complete.json`、`reopen-record-loaded.json`、三组 `*-exit-menu-ready.json`：实际认证/存储、保存、独立加载和托盘身份。
- [正常设置页](../../.verification/webview-full-exe-20261110/startup-normal-settings.png)、[网页保存后](../../.verification/webview-full-exe-20261110/save-normal-memory.png)、[独立重开读回](../../.verification/webview-full-exe-20261110/reopen-loaded-memory.png)：已查看实际窗口截图。

最初运行中的 stdout 文件由 guardian 独占写入，一次读取返回共享冲突；未改权限，退出关闭文件后才读取完整日志。保存阶段两个早期 UI 快照使用简写字段 `n/t/e/v`；最终保存、重开和对比证据均为完整字段。辅助脚本显示差异没有导致重复操作或产品重试。

## 退出与剩余边界

三次均实际调用托盘“退出”，没有直接调用内部 ExitAsync、关闭未知窗口或终止未知进程。桌面句柄在 guardian 清理前退出，退出码都是 0；应用完成网页清理和控件释放。**每次 guardian Job 关闭前仍有 5 个成员**，它们的 PID 来自持有 Job 的 `QueryInformationJobObject`，没有按旧父 PID 猜测归属。随后关闭 Job 触发已有 kill-on-close 清理；不能称所有子进程自然退出，也没有对这些 PID 再作系统枚举或逐个缺席证明。Runtime 自身也沿用产品 Owner 的现有清理机制，不宣称 Java graceful shutdown。

共享 Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 覆盖各次准备/启动至退出及 Job 清理，在 finally 释放：09:41:46.046、09:47:54.921、09:51:42.130 UTC。没有遗留运行中的本轮 guardian 或持有的 Job/重运行锁。各次 `last-workspace` 均由不存在保持不存在；不创建或改写默认记录，不触碰真实 Profile、数据库、个人浏览器数据、EFS/ACL、注册表、网络或系统策略。

此次已补齐限定合成环境中完整正常网页/Runtime 和网页 Memory 保存重开证据。无参数默认入口、维护迁移、个人恢复、跨机器、物理输入/IME、最小尺寸及正式发布仍未验收。其他记忆编辑/查找功能的先前聚焦检查不扩大为此次全部实际 UI 验收。没有为凑模块添加功能、跑额外测试或调用模型。当前限定交付目标没有待执行的阻塞动作；系统端口读取的历史拒绝依然保留，不影响已经取得的本轮应用内证据，也不构成未来重查授权。

## 说明更新与最终开发包

验收报告及 README 提交为 `f0cbfa2edc8b2cf728953a874070e6d437e52ff5`。新的 [开发 ZIP](../../artifacts/PersonalAiWorkspace-normal-web-verified-20261110.zip) 和 [解压目录 EXE](../../artifacts/PersonalAiWorkspace-normal-web-verified-20261110/PersonalAiWorkspace.Desktop.exe) 复用全部实际验收程序字节，只更新 README 与对应清单；没有重编译或再启动。

| 交付项 | 结果 |
| --- | --- |
| 产品来源 | `64b33b2991acb407d6887040ec1fe7ae902b0afb` |
| ZIP 字节数 / SHA-256 | 384,687,815 / `ACE6C4BFC53ABB4CE762B5C89882A6F9AAD709D3864A1ADB7D144F0B02F8C11C` |
| 清单 payload | 933 个文件；其中 932 个文件的字节完全不变，唯一 payload 差异为 README.txt |
| README SHA-256 | `7125EEF3DBB6AE1F68E4359226C834AAD93441BA8D72B401ADA168A98DD14DB6` |
| ZIP 核对 | 934 个条目逐项读取压缩流核对长度及 SHA-256，包括清单本身；未再做不必要的启动测试 |
| 原包 | 原 default-profile ZIP 哈希仍为 `BD29D41EED4F83C8D14FF5C539C0F64323918D536F7BAC05CE109D4A057AFEA9`；更早包未改写 |
| 包装收尾 | guardian PID 10824，09:57:04.858 UTC 释放共享锁；无浏览器/Runtime 启动 |

包装证据：同一 evidence 目录下 `Package-Verified.ps1`、`verified-package-result.json`、`package-lock.json`。清单标明 `README_ONLY_AFTER_NORMAL_WEB_EXE_ACCEPTANCE`、原验收包 SHA 和说明提交；产品来源没有被说明提交替代。最终受跟踪工作区、main 与六份用户文档的保留检查写入 `final-state.json`。开发分支仍为 `codex/knowledge-version-compare`，main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，不推送。
