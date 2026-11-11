# 原产品退出验证：身份已对照，当前实际根尚未查询

2026-11-11；本轮输入 `639867053b775828837bd07f4d3d8500cd3b8a17`，分支 `codex/knowledge-version-compare`。本轮仅限同一普通执行入口的公开身份／目录元数据、既有回执和源码；不启动产品、helper、新候选，不查询任何 Profile。

**更正前置判断：当前进程与有效身份的 SID，与旧原 Profile 元数据记录的有效 SID 相同，解释器绝对路径和 .NET LocalAppData 也完全相同。没有证据要求切换账户或恢复登录。** `6398670` 当时只读到未保存 SID 的较晚回执，故把原主体对应关系列为未知；本轮找到更早的准确记录后，这项未知已消除。此前停止未产生产品操作，其回执保留，不改写历史。

尚未核实的是**当前正常准入的实际目录绑定**。逻辑 LocalAppData 与原绝对目标的字符串差异，既不是主体变化的证明，也不是产品最终绑定不一致的证明；不能将其独立用作拒绝或通过条件。产品最终根仍须精确等于已批准原目标，所有 NativeGate、owner／ACL、文件身份及绑定复查必须保留。

## 当前公开元数据与旧记录

当前一次只读快照于 UTC `2026-11-11T14:10:05.4650754Z` 完成，普通 `use_default`，Session 1。仅查询本次解释器自己的进程／线程身份和两个公开环境变量；没有全量环境、凭据内容或访问令牌句柄／原始字节输出。

| 层次 | 当前结果 | 与旧记录的关系 |
| --- | --- | --- |
| 进程身份 | OpenProcessToken 仅请求 TOKEN_QUERY；其 User SID 与旧有效 SID 相同 | 不是从 USERPROFILE、用户名或目录名推断 |
| 线程／有效身份 | OpenThreadToken 返回 ERROR_NO_TOKEN（1008），无模拟线程令牌；WindowsIdentity.GetCurrent(Query) SID 与进程相同 | 旧元数据 impersonationLevel=None，旧有效 SID 与当前相同；不据此声称全部权限／登录会话一致 |
| 正式解释器 | `C:\Users\28421\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe` | 与旧元数据逐字相同；本轮没有另换入口或用户 |
| 公开环境变量 | USERPROFILE=`C:\Users\28421`；LOCALAPPDATA=`C:\Users\28421\AppData\Local` | 仅是进程环境值，不承担身份或授权判断 |
| 普通 .NET API | LocalApplicationData=`C:\Users\28421\AppData\Local` | 与旧元数据相同 |
| 产品所用 OS API 的第一阶段 | 同一有效 identity 的 AccessToken、FOLDERID_LocalAppData、flags=0 调用 SHGetKnownFolderPath，HRESULT=0，返回 `C:\Users\28421\AppData\Local` | 当前与 .NET 值相同；没有继续访问应用目录或调用产品 Parent／Root／BindRoot |
| 当前 canonical Profile 根 | 未查询 | 不能从上述逻辑目录拼接得到，更不能视作准入已通过 |

身份 API 的语义依据 Microsoft [WindowsIdentity.GetCurrent](https://learn.microsoft.com/en-us/dotnet/api/system.security.principal.windowsidentity.getcurrent?view=net-10.0)、[OpenProcessToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openprocesstoken) 和 [OpenThreadToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openthreadtoken)。OpenAsSelf 只决定打开线程令牌时的访问检查上下文，不把返回身份替换为进程身份。本轮没有模拟、复制用于切换身份的令牌或调整权限。[SHGetKnownFolderPath](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath) 查询已知文件夹，本轮 flags=0，没有请求创建目录或加载其他用户配置。

## 旧原产品证据和执行方式

原目标 P 仍严格为 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2`。

1. `artifacts/profile-metadata-review-20261111-f3bd63caf10145d2979783626690aa3d/metadata-result.json`：UTC 08:52:38.4279716，普通 use_default，同一解释器；保存原有效 SID、无模拟级别、LocalAppData，以及 P／model-state owner 等于该 SID 的实际结果。本轮只读取此工作区 JSON，SHA256 与原报告完全一致：`7b025496ba29913328280eb0429a35c79fd16542845634e71272a56a05aee292`。
2. `artifacts/live-link-preflight-20261111-4461263e58b54c4a8b9b0b0f282c659a/identity-precheck-stop.json`：历史 require_escalated 调用的有效主体不匹配，在 helper 启动前停止；后续普通入口确认原主体后成功。该历史差异不能套用到本轮普通入口，本轮未改用 require_escalated 做身份探测。
3. 同目录 `helper-result.json`：UTC 10:03:16.3056367，固定包内 `NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.exe VerifyProfileNative b204c7e3cc33425e841eb1aa32d2caa2`，PID 3500，退出 0，无超时，返回 root 与 P 相同。SHA256 `04f41e7d8d813cf84f45db15d47d5d6e63a61280da3564c6f1f088bfe7412552` 仍相等。**helper 回执本身没有 SID／解释器字段**；普通入口及主体比较的联系来自当时身份停止回执和[启动范围报告](SAME-SOURCE-CANDIDATE-AND-STARTUP-SCOPE.md)，不能伪称保存了 helper 完整令牌快照。

以上排除了“当前有效 SID 与已存原主体不同”这一具体判断；没有证明原 helper 当时所有环境值、应用目录重定向机制或当前准入结果。没有重演 helper 或读取 P。

## 正常入口的绑定机制与最小修正

源码 WindowsProfileSecurity.Parent 先用当前有效身份查询 OS Known Folder，随后对派生的应用目录（存在时）打开句柄并取得 FinalPath，才拼出 Profiles 父目录。Root／BindRoot 再按严格 ID 取得根、固定逻辑及实际祖先链、检查 owner／私有 ACL、读取文件身份、重开逻辑名称并要求最终根和文件身份不变。Mmf3ProfileState 直接委托这些方法；helper 同样走共享绑定代码，而不是信任 caller 提供的绝对根。

因此，前置检查应区分“逻辑 Known Folder”和“OS 句柄绑定根”。本轮修正的是前置诊断和继续条件，**未发现需修改产品路径实现的证据**。Git 比较确认从旧候选源码 `933bb20` 到本轮 HEAD，WindowsProfileSecurity.cs、TrustedNative.cs、Bootstrap Program.cs 和 Mmf3ProfileNative.cs 没有差异；未改 NativeGate、固定 pins、授权路径或不一致拒绝逻辑。是否实际发生目录重定向尚未观察，不能把源码支持重定向当作本机已发生重定向的证明。

未来允许恢复准入时：仍使用同一普通入口和原 ID，由正常绑定取得最终根，再与 P 精确比较；不同、无法取得或遭拒都停止，不跳过比较、不回退到环境变量路径、不复制 Profile。当前没有依据要求用户切换账户、重登或恢复连接；只有未来得到具体主体／绑定不一致证据时，才说明相应正常恢复步骤。本轮最新指令明确不查询 Profile，故没有执行这一步，也不把之前两次启动许可解释为本轮必须启动。

## 合成目标的拒绝范围保持独立

另一个目标是 `C:\Users\28421\AppData\Local\PersonalAIWorkspace.Development\Profiles\2fd8ddc494cb45b5a7c8db284fbe386c`。既有 `artifacts/memory-usage-native-20261111/profile-root-metadata-denial.json` 和[原生测试报告](MEMORY-USAGE-NATIVE-TEST-REPORT.md)记录：工具 e97370 退出 1，对该准确根执行 FILE_READ_ATTRIBUTES 元数据查询时报“拒绝访问”，未返回内部阶段或原生错误码，也未生成计划中的 metadata JSON。该动作未执行 helper／产品；Java、NativeGate 和数据库查询断言没有进入。

本轮只读上述工作区回执，未重查该目标、其父目录或替代路径；未尝试改变身份／ACL／工具来访问它。原 b204 的历史读取和 helper 成功不解除此拒绝；此拒绝也不能泛化成 b204 所有读取被拒。旧 Crashpad 列举的绝对 Profile 未知，仍不查询；其他既定拒绝保持。

## 本轮交付与下一步边界

新证据位于 `artifacts/live-exit-identity-diagnosis-20261111/`：一次性公开元数据脚本、current-identity.json 和 comparison.json。后者保存历史哈希、逐项比较及未执行边界，SHA256 `a38eb750d9050cd10d666d277bb1858d56d4a6129725ca1424280d5a956b4428`。全部七项身份／解释器／旧 owner／目录值比较为 true；这不是产品运行测试。

没有产品／helper／NativeGate／UIA／Java／数据库运行，没有 Profile 或应用父目录查询，没有客户端 token 读取／更新、WebView 清理、新候选、编译或测试。没有新的 OS／自动审批拒绝，无未知在途操作。**产品启动 0 次，两次额度未消耗，productionGo=false。** 可见退出功能源码仍为 `42c7358`，旧候选未包含它。

剩余实质步骤是准确同源候选及原范围正常准入，再验证自有窗口的“退出工作区”确实完成原 ExitAsync／Runtime 清理；必要时人工操作见[可见退出报告](VISIBLE-WORKSPACE-EXIT-REPORT.md)。本轮按只读限制停止在实际绑定之前。六份原始文档、旧包、用户数据、main 与 KAG 未改，只有本报告和状态／路线图更正进入本地 Git。
