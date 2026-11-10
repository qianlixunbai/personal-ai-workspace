# 原生 Memory 真实持久化闭环

2026-11-10，接续 `33c39aa5ce191ac7a3a0570ac99e893c0bf8a7c4`。**原生 Memory 的明确保存、正常关闭、独立进程重开读取已通过**。仅合成数据、Development-only；不代表 WebView2 或完整 AssistantApp 启动通过。本轮只增加已有手工验收入口的原生模式，没有修改产品存储、UI、协议或交付包。

## 实际路径与隔离范围

使用[最终开发包](../../artifacts/PersonalAiWorkspace-memory-native-final-20261110.zip)，产品来源 `0ad966521fed75701ab29dbef6395f325e032a1d`，ZIP SHA-256 `32518BBC44E9F2BB9E5ED063D62CC50ECAF4E6BFE2DD3D2429593CB55B3C1B2D`。没有重新构建或重跑已经通过的分发合同。

在 `.verification/memory-native-persistence-20261110/bundle-under-test/` 复制最终包，仅加入手工宿主工具；实际加载包内 Desktop / Core DLL。Desktop SHA-256 `B18017560C853A6196979965F0A87A6D3351ACD49949C343E22C95CBCFF2E7EE`，Core `F6D75462CE5BD7C0E7E43A277687C1B63286F5BF0F6EC4FA6C7567E019457373`。Runtime 来自同包 JAR（SHA-256 `71F181BF8D991AA5484C9EFB83D548EC275644407B388CD68BE220421DF5DFFD`）解出的产品 classes 和依赖；它们在测试支持类之前加载。没有替换产品实现或使用模拟 Memory Store。

复用 `MemoryDesktopAcceptanceProbe` / `MemoryDesktopIntegrationProbe` 的显式手工流程：包内 `Mmf3ProfileState.Prepare`、native helper Provision、Pin、Parse、`TrustedDevelopmentRuntimeSources.Open()` 与 Java `WindowsNativeGate.admit()` 均实际执行。新 Profile ID 为 `fe63cd55282f4f21bfb50abfa7b593c8`，由系统按正常产品合同解析至当前用户私有 Profile 根目录。精确位置保存在 `prepare/owned-profile.json`，不读写已有用户 Profile。

实际合成数据库为该 Profile 下 `evidence/m/data/memory.db`，独立凭据位于同一合成工作区的 `credentials/client-token`；凭据不复制到报告。普通 `Profile/data` 仍为空，`last-workspace` 元数据哈希未变。只用新建合成工作区的 token，不接入真实账户。整个流程未启动模型或合成提供器。

宿主加载**实际 `MemoryWindow`**，驱动其按钮、列表、文本框和真实 Yes/No 放弃确认；没有注入确认回调。既有 `RuntimeClient` 的 HTTP handler 只将 Memory 请求转到本次已核对 PID / ready receipt 的随机本机端口。服务端是完整 Spring 应用上下文中的实际 MemoryController / MemoryStore / SQLite；不是内存模拟服务。保存进程仅允许一次 POST，重开进程拒绝所有写请求。未使用固定 8765、18765 或 18895。

这是一条测试宿主装载产品原生窗口的路径，**没有执行完整 `AssistantApp` EXE，也没有点击真实 WebView2 失败页入口**；它不替代标准启动、固定端口生命周期、失败页路由和完整应用验收。

## 保存、关闭与独立重开结果

| 阶段 | 实际结果 |
| --- | --- |
| 新建 | SQLite 列表为空；取消放弃草稿保留内容，确认放弃后清空；两步均无写请求。 |
| 明确保存 | 宿主 PID `21824`、Java PID `30304`、端口 `59150`。实际 Save 保存含中文、换行与 emoji 的合成内容；HTTP 读回同一记录，Revision `1`，恰好一次写入。 |
| 正常关闭 | 在已保存内容后加入未保存后缀；先取消关闭，窗口和草稿保留；再确认放弃，窗口正常关闭并清除编辑文本。Java 收到完成信号后正常退出 `0`，日志包含 graceful shutdown complete；宿主退出 `0`，端口关闭。 |
| 独立重开 | 新宿主 PID `12216`、新 Java PID `34684`、新端口 `56643`。重新打开同一个 SQLite 文件，原生列表仅一条；选择后标题、正文、ID、时间及 Revision 与保存收据完全一致，不含未保存后缀。写请求 `0`。 |
| 收尾 | 第二窗口正常关闭，Java 和宿主退出 `0`，端口关闭。两个阶段均未强制终止，模型调用 `0`。 |

只有这两个进程阶段各执行一条显式手工场景，Java 记录分别为 found=1 / passed=1 / failed=0。没有扩展回归矩阵、重跑旧 UI 测试或启动 WebView2。

主要证据位于 `.verification/memory-native-persistence-20261110/short-path/`：`save-result.json`、`reopen-result.json`、两组 `*-owned-java.json` / `*-host.json` / `*-cleanup.json` / `*-host-cleanup.json`、`*-java.log`、`saved-record.json`，以及 `saved-native.png` / `reopened-native.png`。重开截图已检查，与原生编辑区和 Revision 1 一致。`preserved-sample.json` 记录关闭后的实际数据库：98,304 字节，SHA-256 `FE2613D4A32122B7FF7546A49579A861E32FD146C29F32F4B95AAD16FEFB270D`，无 journal / WAL / SHM。样本保留在新 Profile，不清理或覆盖它。

## 首次失败与修正边界

首次使用较长合成子目录 `evidence/native-memory`，Runtime 在 `KnowledgeAnswerHistory` 初始化时返回 `KNOWLEDGE_STORAGE_UNAVAILABLE`；尚未创建 MemoryWindow，没有保存、没有启动重开。Java PID `11896`、宿主 `15200` 均退出，原日志及零字节 `history.db` 保留。

该 `history.db` 的路径长度是 262。对同一新建空文件的单次只读对照确认：现有普通 SQLite URL 返回 `SQLiteException` 错误码 `14`；使用 Memory 已有形式的 `win32-longpath` URL 可以打开并完成 quick_check，文件仍为零字节。证据 `path-readonly-check.log`。这是 Knowledge 历史库的具体长路径限制，**不是 WebView2 根因结论**。

本轮仅缩短验收工作区为全新 `evidence/m`，保留首次失败目录，确保历史库及 journal 路径低于现有限制后执行上述一次闭环。没有降低 native / 私有目录校验、修改 ACL、系统长路径策略或产品连接 URL。Knowledge 等仍采用普通 SQLite URL 的存储在长路径下的兼容性保留为独立产品缺口；本次短路径成功不能宣称该问题已修复。

工具准备时的首次 Java 编译曾在关闭重复缓存 JAR 时报告 `AccessDeniedException`，即使进程返回 0 也未将其当作成功。随后消除与包依赖重复的缓存项，将仅测试依赖原样复制到仓库，核对哈希，用新的输出目录编译成功且日志为空。C# 手工宿主增量编译 0 警告 / 0 错误。没有新增依赖、变更机器策略或重建产品。读取合成 Profile 私有文件需要当前用户权限，已通过精确单次授权只读检查；无自动审批拒绝、待审批或结果不明的保存。

## 资源、回滚与交接

沿用 Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 与既有 `Invoke-WorkspaceHeavyRun.ps1`。成功闭环从 05:24:20.046 至 05:24:33.377 UTC 全程持锁（包装 PID `4408`），覆盖两组子进程、窗口及服务正常退出，finally 释放。首次失败包装 `26712`、只读对照 `6120`、短路径工具构建 `21492` 也正常释放。未知 18765、历史浏览器后代及 KAG 18895 未联系或处理。

收尾按进程创建时间和句柄确认归属，不能仅凭 PID：成功重开 Java 的 PID `34684` 后来已被一个创建于 05:24:50 UTC 的 `conhost` 复用，该新进程不是本轮 Java，未终止它。原 Java 创建时间为 05:24:27.475，父宿主已等待并确认其退出码 0。

完整 Git 回滚为 `.verification/memory-native-persistence-20261110/baseline-33c39aa.bundle`，SHA-256 `4CF293D7D60915D413728C7632D2E6578E445F5BFB50F1AF154CFB086D2AF42A`，已核对。六份用户文档、main、旧包和最终开发包保留，不推送。

本项原生持久化关卡已完成，不再重复运行保存样本。下一步按真实产品缺口选择；可处理刚证实的 Knowledge 长路径兼容性，但应复用现有 Memory 做法并做最小受影响验证。原 WebView2 保存 / 关闭 / 重开、完整 EXE 及失败页到原生入口、PG 桌面、个人恢复、跨机器和正式发布仍未通过本轮验收。测试工具修改不进入现有开发包，不要求为这次证据收尾重新打包。
