# 完整 EXE 的原生记忆备用入口验收

2026-11-10，接续 `2db591c28edbf12d72e1931aa495c7d828550e61`。**实际开发 EXE → WebView2 自然失败页 → 原生 Memory 保存 → 托盘退出 → 独立 EXE 重开读回已通过。** 这是完整产品入口的证据，补齐此前仅测试宿主加载原生窗口的边界；不表示 WebView2 已修复。仅合成数据、Development-only / unsigned，无模型调用。

## 隔离启动的最小产品改动

原有显式 Profile 参数也会在成功启动后更新 `last-workspace`，不适合保留用户默认入口的验收。产品提交 `cff8015ee92edc1ac3a8b13a98afe751247f9410` 增加：

```text
PersonalAiWorkspace.Desktop.exe --open-development-workspace-once <32位小写十六进制Profile-ID>
```

仍走 Program、Profile Pin/Parse/Validate、SingleInstance、AssistantApp、RuntimeOwner 和原生认证的完整路径，只跳过默认工作区记录更新。窗口标题和托盘显示“仅本次打开”；不创建 Profile、不接受任意路径/端口，不降低权限或准入检查。该模式仍使用所选 Profile 的真实数据库，保存会持久化，并非只读或临时数据模式。用户主动选择“切换开发工作区”属于另一项明确操作，继续使用原选择流程。

必要增量编译为 0 警告 / 0 错误；既有 `CompiledVariantIdentityAndCrossModeArgumentsAreIsolated` 扩充非法/缺失参数与不存在的 Profile 校验，单项 1/1 通过。没有增加测试框架或重跑已通过的持久化/长路径矩阵。成功参数与不更新默认记录由下述真实 EXE 流程补证。

## 实际入口、包和操作

执行包为 `artifacts/PersonalAiWorkspace-open-once-20261110`，产品来源 `cff8015`，原 ZIP SHA-256 `5EA7A21FD745FE1006A80FF42AA209F53ED8BE4A3A36EE681D2C6E55872296B5`。没有向该包添加测试宿主或替换产品实现。Desktop DLL SHA-256 `614D32FB55682047742DC9F4E95DD100DA7001681FE07F58D5691DC2F8D4DC46`；Runtime JAR SHA-256 `BC8F0CC35D2F9321AF848F7FFDFE271DB07B770CF1878454F07A8FD0C0D12625`，与前一 SQLite 长路径包一致。

全新合成 Profile 为 `6a4470ce538d4f2b94dae275b3792715`。仅准备阶段复用此前 `0ad9665` 包的原生 Prepare/Provision/Parse 工具；之后运行的是上述完整产品 EXE 和它启动的包内 Java。准确根目录见 `.verification/memory-exe-entry-20261110/prepare/owned-profile.json`，数据库为该新 Profile 下 `data/memory.db`，没有打开既有个人数据库、修改配置或接入真实账户。

本机没有可调用的 agent-desktop CLI。实际交互使用 Windows UI Automation，读取后按唯一窗口/控件身份执行一次按钮或文本操作。托盘菜单通过核对 PID、创建时间和原生窗口句柄后发送菜单消息打开，再调用真实“退出”菜单项；没有调用内部 ExitAsync 或测试构造窗口。截图只捕获本轮产品窗口。Windows PowerShell 的文件执行策略曾阻止辅助脚本，未改变策略；改用现有 PowerShell 运行包内 UI Automation 程序集。普通沙箱看不到产品窗口时，使用明确单次批准的当前用户窗口操作。

| 阶段 | 实际结果 |
| --- | --- |
| 第一次启动 | EXE PID `2868`，Java PID `14528`。窗口标题含“仅本次打开”，WebView2 在约 30 秒自然报 `STARTUP_TIMEOUT / CONTROLLER_START`。没有设置 WebView 环境覆盖或强制失败参数。 |
| 备用入口 | 从实际失败页点击 `NativeMemoryFallbackButton`，打开实际 Memory 窗口，显示列表为空。打开期间入口禁用；返回后恢复。原始超时消息保持。 |
| 明确保存 | 实际 Save 按钮执行一次，保存标题“完整 EXE 合成记忆 20261110”和合成正文。列表为一条，ID `743beba6-f36d-4217-a72d-0568cb3c0e9b`，Revision `1`，界面提示“Memory 已保存”。 |
| 正常退出 | 关闭已保存的 Memory，再通过真实托盘“退出”。桌面退出码 0；guardian 释放 Job 前活跃进程数已为 0，18765 关闭，无 guardian 强制终止。 |
| 独立重开 | 新 EXE PID `18460`、新 Java PID `17096`。再次自然出现同一 WebView2 超时，经实际备用入口选择唯一记忆，标题、正文、ID、状态、Revision 和界面显示的创建/更新时间与保存时一致，提示“Memory 已加载”。没有再次保存。 |
| 第二次退出 | 同样通过托盘退出，桌面退出码 0；Job 清理前为 0，自有资源收尾、18765 关闭。 |

两次启动前后，当前启动上下文中的 `last-workspace` 均不存在，未被创建。这里验证的是保留“不存在”的状态；没有临时写入用户默认记录来构造另一场景。Runtime 退出由产品现有 Owner 生命周期清理，不将正常桌面退出扩大为 Java 自身 graceful shutdown 的证明。

证据根目录：[`.verification/memory-exe-entry-20261110`](../../.verification/memory-exe-entry-20261110)。关键文件为 `save-exe.json`、`reopen-exe.json`、两组 `*-cleanup.json`、`*-stdout.log`、`save-after-save.json`、`reopen-record-loaded.json`、`*-exit-menu-ready.json`。已检查[独立重开后的 Memory 截图](../../.verification/memory-exe-entry-20261110/reopen-native-memory.png)与[真实失败页截图](../../.verification/memory-exe-entry-20261110/reopen-failure-page.png)。

## SQLite 哈希变化的只读调查

保存并退出后 SHA-256 为 `0C27D67C86F100114111DF4EDEE6D68557BD031F31D29FA70B02F249667EAE26`；独立重开并退出后为 `F6DD6B722C9C309F48CA0E2D08F4B8C54E7C8717EADAAF4368D814FE3765AED6`。文件为 98,304 字节，没有 WAL、SHM 或 journal sidecar。**没有保存重开前的数据库字节副本，只有当时的哈希和 UI 快照，因此不能进行两份数据库的页级或全表比较。**

恢复连接后未再次启动产品或构造维护写入。只用普通文件读取将当前合成库复制为 `database-after-reopen.sqlite`，核对已记录的后态哈希；SQLite 仅以 `mode=ro&immutable=1` 打开这个离线副本，并启用 `query_only`。调查前后原库及副本哈希一致。结果见 `database-readonly-inspection.json`：

- `memory_items` 恰好一条，ID、标题、正文、PROJECT_NOTE / ACTIVE、Revision 1 与保存/重开证据一致，来源 MANUAL；当前源记录创建/更新时间相等。历史 UI 时间只有显示精度，不冒充毫秒级前态 SQL 快照。
- FTS 派生内容与当前源记录的 rowid、标题和正文一致；四张会话业务表均为空。
- 当前 `user_version=3`、`schema_version=35`、24 页、4096 字节/页、freelist 为 0。这些是后态元数据，不能推导完整前态。

[MemoryStore.java](../../src/main/java/io/github/qianlixunbai/workspace/memory/MemoryStore.java) 的现有初始化在数据库已有版本时仍进入写事务并调用 `rebuildIndex()`：删除/重建三个搜索触发器和 FTS5 虚表，再从 `memory_items` 填充索引。[WorkspaceSchema.java](../../src/main/java/io/github/qianlixunbai/workspace/persistence/WorkspaceSchema.java) 仅在旧版本上执行对应迁移。上述源码自被验收 Runtime 来源至 `cff8015` 没有变化。

因此，完整 Runtime 的启动路径**明确包含派生索引维护写入**，整库零写入并不成立；这与文件哈希变化相符。现有证据支持合成记忆保留，未发现业务记录变更的迹象，但不能把全部不同字节唯一归因于索引/文件布局，也不宣称所有业务表的前后 SQL 快照完全相同。没有据此修改产品存储或重跑完整流程。

## 最终交付与真实差异

[更新说明后的开发 ZIP](../../artifacts/PersonalAiWorkspace-native-entry-verified-20261110.zip)，或直接使用[完整解压目录入口](../../artifacts/PersonalAiWorkspace-native-entry-verified-20261110/PersonalAiWorkspace.Desktop.exe)。ZIP 为 384,672,748 字节，SHA-256 `1B4D7A1E00B049493F9262903DCB82D53438A62EAB4565C20291E9D30516DF47`。

该包复用实际验收包，933 个清单文件中 **932 个字节不变，唯一 payload 差异为 README.txt**；完整性清单相应更新并标注 `README_ONLY_AFTER_EXE_ACCEPTANCE` 和验收原包哈希。产品来源仍为 `cff8015`，未重编译任何程序。README SHA-256 为 `0DBF34C4420451024D9922B436E0B419A533E9089861725C7CD35AD5994F1AF7`。所有 934 个 ZIP 条目（含清单）均按实际压缩包流核对，结果在 `verified-package-result.json`。原 open-once 包、SQLite 长路径包和所有更早包保留。

首次全量包装在 Maven wrapper 报 `Cannot index into a null array`，尚未执行 Runtime 构建；日志 `delivery-build.log` 保留。随后只重新发布实际变动的 Desktop，沿用已核对且未变化的 Runtime/前端字节，再完成实际 EXE 验收。本次说明更新没有再次触发该构建或任何启动测试。

## 资源、恢复与接续边界

重任务沿用 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun`：保存阶段 guardian PID `14728`，06:07:04–06:10:24 UTC；重开阶段 PID `30728`，06:10:54–06:13:15 UTC。覆盖完整进程生命周期并在 finally 释放。最后说明包装 PID `24404`，06:39:46–06:39:55 UTC，也已释放。静态调查、Git 和报告不持锁。没有处理未知旧 18765 进程、历史浏览器或 KAG 18895。

断连发生在两组正常退出和数据库后态哈希均已落盘之后；当时下一条源码/进程查询未执行。恢复后确认 HEAD 为 `cff8015`、索引和受跟踪文件干净、原包哈希及六份用户文档一致，没有重复保存、启动或提交。

辅助进程枚举存在证据限制：原 guardian 按已记录父 PID 轮询，`reopen-owned-processes.json` 中后出现的 `python.exe` 不能仅凭父 PID 判为自有，可能涉及 PID 复用；没有对它发出终止操作。实际退出结论依据持有的 Job 句柄在释放前为 0 和桌面句柄退出结果。恢复时额外 WMI 查询 19 个记录 PID 被系统拒绝访问，未改路重试；`resumed-process-check-corrected.json` 的 false 值不可当作不存在证明，限制已单独写入 `resumed-process-check-limit.json`。当前可读端口列表中 18765 未监听。该可选复查受限不改变已有退出收据，不据此清理任何进程。

基线回滚为 `.verification/memory-exe-entry-20261110/baseline-2db591c.bundle`，已 `git bundle verify`。开发仍在 `codex/knowledge-version-compare`；main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`，六份用户文档原样保留，不暂存、不推送。

本轮不再增加功能或验收次数。**WebView2 正常页面保存/重开、无参数默认入口、维护迁移、PG 桌面、跨机器与正式发布仍未验收。** 两次 WebView2 均自然超时，关闭还记录 `CLOSE_CLEAR_FAILED`，没有宣称浏览器数据清理成功。下一会话若继续浏览器问题，应先依据这两次固定阶段的诊断选择有新信息的最小步骤；不要重复已经通过的完整 EXE 备用入口保存样本，或把静态索引维护解释扩大为全部数据库差异已证明。
