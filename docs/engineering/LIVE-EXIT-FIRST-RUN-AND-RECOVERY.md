# 首次真实启动与失联后只读核对

2026-11-11。候选源码 `422bcdfdab0dd373803359f0ae6c1e1c01fcb817`，分支 `codex/knowledge-version-compare`。**真实闭环失败，产品启动 1/2；未导入、未独立重开、未验证可见退出。productionGo=false。** 本报告后半段仅恢复后的只读观察，不是第二轮启动或新的正常关闭尝试。

## 当前可确认的进程终态

| 对象 | 准确归属和实际结果 | 不能据此声称 |
| --- | --- | --- |
| Desktop | 保存 PID 38620、启动 UTC 14:28:26.4604105、ticks 639300041064604105、Session 1、下表候选 EXE 路径。14:34:56.9368340 的原普通入口精确查询返回该 PID 不存在，没有按名称或遍历进程接管其他对象。 | 没有 Desktop 退出码／时间／关闭路径回执，不能认定正常 ExitAsync 完成，也不能把断线直接解释为应用退出原因。 |
| 自有 Java Runtime | 新 attempt `93d926922afe416bbf5cc780e25959f1` 的 intent／started 记录准确匹配 Desktop PID、启动 ticks、Profile 和候选 JAR。Runtime PID 18668，启动 UTC 14:28:27.4277615；cleanup 回执记录 UTC 14:28:27.6712429、退出码 **1**、ExitKind **OWNER_CLEANUP**。恢复后只查询这个已保存 PID，结果不存在。 | 这是应用所有者的异常清理回执，不是 CONTEXT_CLOSED_NATIVE_EXIT。不能说正常 CLOSE 握手、Java NativeGate、数据库或模型初始化通过。 |
| 监督会话 | `write_stdin` 对已保存工具会话 27101 做空输入读取，返回旧 exec-server session 不存在。desktop-1.json 仍停在启动时，`exited=false`、无 exitCode／mutexReleased。 | 该旧字段是最后保存状态，不证明 Desktop 目前还活着。没有监督进程 PID，不能猜测 PID／遍历全机；监督进程终态和共享互斥释放情况仍未知。 |

没有声称所有 WebView 后代均已退出；本次没有保存其 PID，也没有据名称或全局遍历补查未知进程。现在没有已确认仍存活、可供本轮正常 UI 收尾的 Desktop／Runtime 目标，**不需要重开应用来“补点退出”**。

## 实际原范围准入与准确候选

原目标始终是 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2`。14:20:47 UTC 在同一普通入口核对原根、data、credentials、evidence、model-state 的类型／owner／DACL，以及原先缺失的配置、迁移、guard、锁和 client-token 路径的存在性；结果未变化，没有读 token 内容。固定四项 pins 的 helper PID 20040 退出 0，返回实际根精确匹配原目标；没有 Provision，helper 自有进程结束，短互斥已释放。前置逻辑目录字符串不再作为最终根判断。

候选目录：`artifacts/PersonalAiWorkspace-live-exit-candidate-20261111-422bcdf/`，933 个载荷文件。没有创建 ZIP、安装、设置默认工作区或部署。

| 载荷／证据 | SHA256／结果 |
| --- | --- |
| PersonalAiWorkspace.Desktop.exe | `868682a5e4a2e633c3424053767056855b3ea1d5047d4f048e1ee5da2c0ecb2b` |
| PersonalAiWorkspace.Desktop.dll | `ac57c8f5e9f6493593deda59b961541489f23ebab314f83af5790bc5b4813f60` |
| runtime/personal-ai-workspace.jar | `ed694d4bdef8972fd6f3bdae47b9b7a3e7f94d9916459a6e326802556e13620e` |
| 内嵌 Runtime 清单 | `76658dd9a82776beacabea16b11a99c827a392782786acd0957ac2fb7e15f66e`；420 项 pins，PEReader 只读字节核对及正式 Runtime JAR audit 通过。 |
| 正式 Profile Debug win-x64 publish | PID 1508，退出 0；包含 `42c7358` 可见退出接线和当前前端。 |

生产 Java 源码／配置与 `memory-usage-20261111` 已完成编译的快照逐项一致；复用 573 个应用 class／7 资源、85 个 Finance class／21 资源，核对编译器 createdFiles 清单后记录当前产物哈希。该原 Maven 调用随后在 MemoryStore 测试初始化失败；本次只复用已完成的生产编译，**不把原 Maven 退出 1 改记为数据库测试通过**。没有重跑 Java 编译／测试。

当前前端只生成了一次 Vite production 资产，保留大块提示；类型检查复用 `04187db` 已通过结果。首次组装的依赖比较因宽泛包含两份已改测试宿主源码而停止，核实只有 bootstrap/tests 改动后，准确排除该未进包目录并复用已生成前端继续；不是权限拒绝。仅发布为嵌入新 pins 和可见退出所需的当前桌面产物，未跑无关全套测试。详细配方、来源、哈希和编译回执均保存在下述证据目录。

## 第一轮真实界面与 UIA 异常

14:28:26 UTC 用候选的正常 `--open-development-workspace-once b204c7e3cc33425e841eb1aa32d2caa2` 入口启动 Desktop，计数从 0/2 变为 1/2。监督代码保留本次 Process 对象；后续 UIA 重新核对保存的启动 ticks／绝对 EXE 路径，取得该进程 MainWindowHandle 并核对 HWND 所属 PID。仅从该根或它的 GetLastActivePopup 进入，没有桌面 RootElement、全桌面搜索、用户其他窗口或 CUA 主机关联实验。

实际观察到 MainWorkspaceWindow 和唯一可见 ExitWorkspaceButton，但同时出现“工作区不可用”提示：启动或存储校验未通过，询问是否重新选择。页面 fallback 随后显示“工作区浏览器进程已停止”。这些界面只证明失败症状；没有导入资料，也不能定位为某一数据库／NativeGate／WebView 根因。

唯一修改性 UIA 尝试是对这个自有提示框的已观察“否(N)”按钮（AutomationId 7）调用 InvokePattern，意图保留原工作区、不进入选择／创建流程，不涉及丢弃未保存编辑。工具 `cbcf48` 退出 1，返回：

```text
Exception calling "Wait" with "1" argument(s):
"One or more errors occurred. (Operation is not valid due to the current state of the object.)"
```

保存的 `ui-1-decline-switch.json` 为 **INVOKE_EXCEPTION_OUTCOME_UNCERTAIN**。当时没有保存内层堆栈、HRESULT 或具体调用阶段；源码中 GetCurrentPattern 与 Invoke 在同一表达式，不能事后断言是哪一步失败、控件动作一定未执行，或凭该错误判定权限拒绝。随后仅做一次只读结果核对，14:29:21 UTC 提示仍在；这不授权盲目重发。**没有重试该按钮，也没有调用 ExitWorkspaceButton、私有反射、替代窗口消息或强杀。**

之后执行通道失联，准备读取 Runtime 归属目录的命令在创建进程前失败，未执行。未把失联当作产品终态。

## 恢复后的只读证据边界

恢复后先读仓库内保存回执，再精确查询原 Desktop PID，并尝试只读取监督会话。Runtime 证据只在原应用根的 `Recovery/runtimes/原b204ID` 一层目录中查找晚于本次 Desktop 启动的新目录；唯一候选先通过 intent 的 Desktop PID／ticks／Session／JAR 身份核对，之后才读该 attempt 的 started、exited、cleanup-intent、cleanup。

只解读 RuntimeObservation 类型记录，没有读取 client-token、生命周期 ready／stop-request 的 Challenge 或任何模型／数据库内容。intent、started、cleanup-intent、cleanup 存在，exited.dpapi 不存在。该目录不是受拒合成 2fd 的 Profile 或其父目录，亦未查询 Crashpad。

第一次观察脚本把“文件 owner 正确”和“文件 DACL 必须 protected”合并检查，因后者停止在内容读取前。精确元数据及源码随后确认：owner 正确，三项 inherited FullControl 均属于原主体／SYSTEM／Administrators；产品 ValidateFileAcl 本来允许固定私有父目录的继承权限。仅修正本次观察脚本，检查父目录保护和这些准确 grants；没有改 ACL、产品验证或权限，也不是 OS 拒绝后换入口。后续准确记录解读成功。

全部本地证据在 `artifacts/live-exit-run-20261111-422bcdf/`：

- `preflight-result.json`、`result.json`、`embedded-pins-audit.json`、`reuse-provenance.json`：前置与候选。
- `desktop-1.json`、`ui-1-startup-dialog.json`、`ui-1-decline-switch.json`、`ui-1-after-invoke-error.json`：第一轮已保存事实。
- `recovery-desktop-observation.json`、`recovery-runtime-directory.json`、`recovery-runtime-observation.json`：恢复后的准确进程／Runtime 归属。
- `recovery-supervisor-observation.json`、`recovery-uia-assessment.json`、`recovery-evidence-hashes.json`：监督缺口、异常分类和证据哈希。

本次恢复阶段没有启动产品／helper／Java、调用 UIA、尝试关闭或强杀、重建候选、访问受拒目标、切换身份／入口、修改系统设置或执行新测试。启动上限仍仅消耗 1 次；闭环仍失败。

## 剩余条件

本轮按指示到进程终态与异常事实为止。若继续，应先以现有源码和该次允许的诊断回执定位 Runtime 启动失败及 WebView 停止原因，并补齐 UIA 内层异常阶段的可观察性；不能靠重复第一轮或自动消耗第二轮来探测。当前已无原 Desktop 目标，人工“点否再退出”也不再适用于这个 PID。监督结束与互斥释放缺证据，应保持未知，不靠启动共享重型任务试占来宣称清理完成。

报告本地精确提交；不推送、不改 main、六份原始未跟踪文档、Profile 数据或 KAG。Git 只备份源码／文档，不提供本次真实启动副作用的数据库或凭据回滚。
