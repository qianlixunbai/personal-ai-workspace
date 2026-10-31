# X1 Windows 交互验收 P2

2026-10-31，Asia/Shanghai。基线 `main @ 2b579ae798a5c2ce1739a255536d17afbd46dcb3`；工作分支 `codex/x1-windows-interaction-p2-20261031`。以下原 P2 执行记录中的 HEAD / 未提交状态截至 15:24；后续收口另列。

**15:37 最新聚焦结果：微软拼音候选 / 中文上屏 PASS；普通 Enter 换行 PASS；组合态 Ctrl+Enter 隔离模拟 PASS、物理 NOT TESTED。** 本次收口用户确认没有发送，未观察到新增真实任务。原 P2 主动发送及零新增推理约束 FAIL 的历史事实保留；这不是全部 IME / X1 验收通过，详见文末。

**X 关闭、物理 Esc、来源焦点、原文 / 选区保留和对应剪贴板检查 PASS；真实 IME 验收 BLOCKED。** IME 过程中用户确认主动发送了 `nihao`，得到真实回复，因此本轮“零新增真实推理”约束未满足，不能宣称整个 P2 通过。未再要求重试。

上一轮 [真实 Q/T E2E](X1-REAL-E2E-ACCEPTANCE-20261031.md) 的 Q / T PASS 保留；本轮没有重跑总结或翻译。X1 总体仍 **PARTIAL**；Vision **BLOCKED**；**Development-only**；**无已核验 CI PASS**；**尚未 Production GO**。

## 证据与来源窗口

本机 ignored 证据目录：`.verification/x1-windows-interaction-p2-20261031-144103/`。下述文件名均相对此目录；旧证据目录未覆盖。入场两份未提交的 X1 文档已保存原样副本及 SHA-256，见 `before-X1-*.md`、`preserved-documents.json`；本轮只在原文后追加交接说明。

启动前无现存 Notepad 进程。本轮打开独立 Windows 11 记事本文件 `X1-P2-Source.txt`，实际来源 PID `44984`、精确代次 `44984:134379024671538849`、HWND `6228384`，路径来自 `Microsoft.WindowsNotepad_11.2504.62.0` 包。原生正文控件为 `RichEditD2DPT`。Launcher PID `16720` 不冒充实际窗口进程。见 `source.json`。

37 字符合成原文：

> X1 P2：关闭与焦点验收。原文和选区应保持不变。仅捕获，不提交模型请求。

用户本人按 Q、点击 X 及物理按 Esc；没有由脚本代按。`observations.jsonl` 读取来源正文 / TextPattern 选区、浮窗控件及剪贴板序号；`foreground-events.txt` 通过只读 WinEvent foreground hook 记录 HWND / PID 切换，无键盘 hook 或输入注入。观察有分段起止，未将未采样时间宣称为连续证明。两个实际操作区间均取得事件及前后状态。

## 实际结果

| 项目 | 结论 | 实际证据与边界 |
| --- | --- | --- |
| Q 选区准确 | PASS | 两次均取得完整 37 字符；浮窗原文逐字一致，result 为空、busy=false。只捕获，没有要求提交。 |
| 鼠标 X 关闭 | PASS | `15:00:18.3192724` 浮窗 HWND `1707640` 成为前台；`15:00:26.7561814` 前台回到来源 HWND `6228384`。用户回复“X 已关闭，前台是  记事本”。`x-close-result.json`。 |
| 物理 Esc 关闭 | PASS | `15:11:06.1240547` 浮窗前台；`15:11:12.5481795` 回到同一来源窗口。用户回复“Esc 已关闭，前台是  记事本”。`esc-close-result.json`。 |
| 来源焦点恢复 | PASS（本场景） | 两条关闭路径均由浮窗直接回到原记事本；该切换中未观察到其他窗口激活。不是对任意应用 / 窗口栈的通用承诺。 |
| 来源正文及选区保留 | PASS | X / Esc 后 UIA 均确认正文与完整选区一致；最终 `15:20:26` 仍为同一进程代次、HWND，原文与选区保持。`final-state.json`。 |
| 剪贴板未被取词 / 关闭修改 | PASS（两条关闭路径） | X 区间序号保持 `1142`，Esc 区间保持 `1158`，owner 同样未变；后续用户复制 / 截图的序号变化另行记录，没有归因于产品。未执行 Copy fallback，未验证多格式恢复。 |
| 空输入本地拒绝 | PASS（观察事实） | Workspace `assistant-input` 在 `15:12:40` 为空；`15:15:14.287` 仍为空时出现“请输入内容，或缩短文字后重试。”本地校验。没有录制硬件按键事件，不将此扩充为全部 Ctrl+Enter 场景已通过。 |
| 真实微软拼音候选 / 中文组合 | BLOCKED | 未取得真实候选窗口及中文上屏的完整操作确认；UIA 只观察到 `nihao` / `gongzuoqu` 等拉丁文字。键盘 layout `0x8040804` 本身不能证明微软拼音中文组合成功。流程因真实发送而停止。 |
| Enter 候选处理 / 普通换行 | NOT TESTED（未闭合） | 未取得两次 Enter 各自行为的用户确认，未观察到计划中的完整多行中文输入。不将拉丁文字上屏自动判作 IME 产品缺陷。 |
| 非空组合态 Ctrl+Enter（物理） | NOT TESTED | 提交合同可能发送真实请求，本轮没有要求执行；模拟端口测试另列，不能替代物理验收。 |
| 本轮零新增真实推理约束 | FAIL | 用户明确报告输入 `nihao` 后主动发送并收到回复，至少一次额外真实推理已确认；不是由脚本发起，不判作自动误发送缺陷。 |

现有 `HideOnClose` 通过取消 / 清空浮窗内容及 `Hide()` 实现关闭，Esc 调用同一入口；它没有显式保存和激活外部来源 HWND。本轮实测确认了记事本场景中 Windows 返回来源前台的实际结果，没有增加抢焦点 workaround 或修改生命周期合同。

## IME 停止现场与证据限制

用户提供截图显示输入 `nihao`、“已完成”和实际结果：

> Hello! How can I help you?

随后用户明确回复：“对的，我输入nihao，然后发送，这是他回复我的”。原图保存为 `ime-user-submitted-result.png`，来源说明 / hash 见 `ime-stop-result.json`；输入时间线见 `ime-observations.jsonl`。

这证明至少一次新增真实问答，不能继续声称本轮零推理。该请求未取得独立 Runtime task UUID 或完成时间，未查得完整提交次数；也未单独确认发送按钮 / 键盘手势。不把它计入 Q / T 验收或拿来验证 IME 自动发送保护。脚本未发起模型请求，未以第二次真实请求核对该现场。

最初操作指引把候选态 Enter 的结果预写为“你好”，过于具体；随后已纠正为保留微软拼音实际上屏结果、不重试追求预期文本。指引中的预期不是实际通过证据。收到真实发送确认后停止新增操作，两个只读观察进程于 `15:19:24` 退出；没有重复 Q/T、VALIDATE 或 RECOVER。

## T 临时文案：定位及最小修复候选

调用链：`CaptureAsync → SetCapturedText → UpdateAction` 写入“已取得选区，选择操作后开始”；T 随后直接进入 `SubmitAsync`。原 `SubmitAsync` 设置 busy，但直到 `AssistantOperation.RunAsync` 的 POST 返回任务身份后，progress 回调才映射排队 / 处理 / 完成，因此 POST 等待期间仍显示等待用户选择的提示。

在 `AssistantApp.SubmitAsync` 的 `window.SetBusy(true)` 后仅增加一行：

```csharp
window.StatusText.Text = "正在提交请求…";
```

该行覆盖提交等待期，之后仍由原任务状态映射接管。未修改请求、取消、Native Gate、模型准入或业务合同；不引入新抽象 / 依赖。修复只存在于独立分支源码，未覆盖受信包；本轮真实关闭操作运行的仍是原 `8df87ab` 开发包，不能冒称新代码已完成真实 T 复验。

## 实际执行的聚焦验证

- Desktop 增量编译 PASS：`dotnet build .../PersonalAiWorkspace.Desktop.csproj --no-restore -p:FrontendSkipBuild=true -p:Mmf3ProfileAcceptanceBuild=false -p:Mmf3AcceptanceBuild=false`，见 `status-copy-build.log`。没有重建前端或发布包。
- 既有 Desktop 回归 `SelectionSurvivesActionSwitchesButCloseAndNewCaptureDiscardItsContext` PASS，见 `close-regression.log`、`x1-p2-close-regression.trx`。它的 routed Esc / 窗口清理断言不替代上述用户物理 Esc 证据，也不单独证明新增提示的真实显示。
- 既有前端回归 `Enter and Ctrl+Enter during composition never submit; committed composition supports Ctrl+Enter and normal multiline paste` PASS，使用模拟 bridge，见 `ime-guard-regression-focused.log`。初次筛选包含未转义的 `+`，全部跳过，保留于 `ime-guard-regression.log`；初次退出码 0 不算 PASS。改用匹配文本 `during composition never submit` 后实际执行通过。

没有新增长期测试，没有执行 Java、Desktop、Frontend 全套、完整浏览器 / IME / DPI 矩阵。除上述两项既有聚焦回归外没有扩展测试执行。

## 最终状态与交接

`final-runtime.json` 于 `15:20:25.907 +08:00` 实际返回：health `UP`，原数据库 `POSTGRESQL / TRUSTED_LOCAL / CONNECTED`、Workspace `V1_V2_READY`、Knowledge `READY`、`errorCode=NONE`。Configured / Active 均为 `qwen3.5:4b / llamacpp / 96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`；Ready，`uncertain=false`、`validationRequired=false`、`switching=false`，reserved / queued / executing / draining 均为 `0`。

Desktop / Runtime / Ollama 代次及原 Profile 校验沿用原受信读取机制，未修改配置。`final-state.json` 还确认受信 Desktop / Core DLL 和 JAR 与包清单 hash 匹配。没有服务重启、模型管理、数据库 / Profile / Native Gate / 安全合同修改。来源记事本保留运行，没有为了收口关闭它。

生产 diff 只有上述一行提示；另新增本记录，并在两份原 X1 文档末尾追加范围说明，历史 Q/T PASS 和当时关闭核验 BLOCKED 均保留。分支 `codex/x1-windows-interaction-p2-20261031`，HEAD 仍为 `2b579ae798a5c2ce1739a255536d17afbd46dcb3`；本轮文件未提交，未 Commit / Push / 合并 main。

IME 本轮未取得完整通过证据，零新增推理目标未满足；X1 PARTIAL、Vision BLOCKED、Development-only、无已核验 CI PASS、尚未 Production GO 均保持。本轮已停止。

## 15:30 代码与文档收口复核

审查范围为当前分支全部四个未提交文件：`AssistantApp.cs` 的一行提示、两份新验收记录及原 X1 报告补充，没有发现范围外改动。独立核对原 Q / T task JSON 的 UUID、`SUCCEEDED` 和时间，以及 X / Esc 的用户确认、前台切换和正文 / 选区证据；保留已有 PASS，没有重跑这些真实流程。历史主动发送 `nihao` 导致的至少一次新增真实推理及零推理约束 FAIL 保留。

源码调用链复核：新增提示在 Busy / exit / Memory 检查和 `CreateInput` 校验通过后、创建原 `AssistantOperation` 并设置 busy 后写入，随后仍由原 `RunOperationAsync` 的任务进度 / 错误 / finally 接管。没有改变请求参数或次数、POST 先取得任务身份再取消、Outcome unknown 不重发、内容版本守卫、关闭清理、重试或模型生命周期。`AssistantOperation`、RuntimeClient、Bridge、前端和 Java 均无生产改动。该结论属于静态审查；未冒称受信包已显示新提示。

原聚焦验证证据实际核验：Desktop TRX 的 `executed=1 / passed=1 / notExecuted=0`，对应既有关闭综合回归；首次前端筛选的 `5 skipped` 不算 PASS，修正筛选后的日志确为目标用例实际执行通过。复用该 Desktop 历史证据，没有重跑已通过的关闭回归。

本次实际执行与新证据保存在 ignored `.verification/x1-p2-closing-20261031/`：

- `closing-build.log`：同一 Desktop 增量编译命令通过，0 warnings / 0 errors；跳过前端重建，仅运行既有 assets 完整性检查，没有 publish 或覆盖受信包。
- `closing-ime-mock.log`：`npm --prefix desktop/frontend test -- src/pages/OperationPage.test.tsx -t 'during composition never submit'` 实际执行目标用例通过，其余 4 项因筛选跳过。模拟 Bridge 验证组合期间 Enter / Ctrl+Enter 及 keyCode 229 不提交，组合结束后 Ctrl+Enter 按合同提交。证据层级为隔离模拟，物理 Ctrl+Enter 仍 NOT TESTED；未在真实 Ready 工作区按该组合键。
- `closing-before-runtime.json`（15:30:16）：沿用原受信 Desktop PID `17960` / Runtime PID `24012` 及原 Profile，health UP；PostgreSQL `TRUSTED_LOCAL / CONNECTED`；Configured / Active 身份、selection revision `0` 和 recovery generation 未变，Ready、`uncertain=false`、`validationRequired=false`、`switching=false`，四项执行计数均为 0。只读 GET，未重复 VALIDATE。

本次没有新增长期测试、修改测试 / harness 源码或执行全套回归。Git 只执行当前开发分支的普通 Commit / Push，不合并 main，不创建 Tag / Release，不改写历史。物理 IME 验收尚待下阶段独立操作证据，不能由本节编译或模拟结果升级为 PASS。

## 15:37 真实微软拼音聚焦验收与最终收口

第一阶段提交 `01ddefbbbdf7badfce25b2188275287a383cca98` 已普通 Push；`git ls-remote` 独立确认远端开发分支指向该提交，main 仍为原基线。本节是其后的文档补充，不新增生产改动。

继续沿用同一受信 Desktop / Runtime 和原 Profile，由用户本人在真实 Workspace `assistant-input` 操作；没有代按、设置文本、点击发送或在 Ready 工作区试按 Ctrl+Enter。用户中途询问“要发送吗”，已立即明确“不发送”；此提问不等于发生了提交。

| 项目 | 本次结论 | 取得的证据及范围 |
| --- | --- | --- |
| 微软拼音真实候选窗口 | PASS（本场景） | `15:34:53.009` 截图 `ime-screen-153452993-2.png` 显示输入中的 `ni`、组合下划线及含“你”等候选的窗口；窗口位于输入光标下方、输入框内，未见错位或裁切。用户随后明确确认使用微软拼音候选选择。不是仅凭 keyboard layout 判定。 |
| 中文候选选择及上屏 | PASS（本场景） | `15:34:53.650` 截图 `ime-screen-153453631-3.png` 和 UIA 均记录实际上屏“你好”，输入仍可编辑。用户答复“是，用空格、数字或鼠标选候选”；没有进一步区分该选项内的具体手势，不声称执行了候选态 Enter。实际上屏结果不是预写的通过条件。 |
| 普通 Enter 换行 | PASS（本场景） | `15:37:09.863` 截图 `ime-screen-153709843-2.png` 显示光标进入下一行；UIA 从实际 ` zhongwen` 变为 ` zhongwen\n`。用户确认“已换到下一行，可以继续输入，没有发送”。未取得第二行继续输入中文的独立记录，不扩充为完整连续多行中文编辑验收。 |
| 组合态 Ctrl+Enter | 模拟 PASS；物理 NOT TESTED | 复用本次 15:30 的隔离 mock Bridge 用例；未冒险触发真实请求，也不将此证据冒称物理键盘通过。 |
| 异常提交 / 组合文字丢失 / 焦点异常 | 本场景未观察到 | 候选态和上屏截图正常；输入时 UIA 记录输入框持有焦点，用户离开 Workspace 回复聊天的前台切换单独记录。未观察到新 task UUID、重复提交或新结果；原有“已完成”及结果在输入前已显示，不能当作本次新任务。观察有间隙，不承诺任意输入流程无异常。 |
| 本次收口新增真实推理 | 未报告或观察到 | 用户确认没有发送，代理提交数为 0；有界 HTTP 元数据观察仅在内存提取白名单任务 UUID，本次没有命中，不保存原始流 / 凭据 / 请求正文。此结论限于本次观察与用户确认；原 P2 至少一次新增推理及零推理约束 FAIL 不改写。 |

本节原始证据沿用 `.verification/x1-p2-closing-20261031/`：`ime-observations.jsonl`、`screen-captures.jsonl`、上述截图、`user-confirmation.json` 和带 SHA-256 的 `evidence-manifest.json`。只读观察器已停止，未关闭受信客户端、Runtime、Ollama 或用户记事本。

最终只读状态 `closing-final-runtime.json`（15:37:43.743 +08:00）：health UP；原 PostgreSQL `TRUSTED_LOCAL / CONNECTED`、Workspace `V1_V2_READY`、Knowledge `READY`、`errorCode=NONE`。Configured / Active 仍为 `qwen3.5:4b / llamacpp / 96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`，Ready、`uncertain=false`、`validationRequired=false`、`switching=false`，reserved / queued / executing / draining 全为 0；selection revision / recovery generation / 进程代次均未变。`loaded=FALSE` 原样保留，不以 Ready 改写它。

`closing-trusted-package.json` 重新核对当前包 EXE、Desktop/Core DLL、Java EXE、JAR 均匹配原 `8df87ab` 清单；`closing-configuration-pins.json` 确认原 Workspace / Finance 配置 hash 未变。没有 VALIDATE、模型管理、服务重启或数据库 / Profile / Native Gate 修改，没有覆盖受信包。文档补充后不重跑已通过的测试、编译或打包。

仍未完成：物理组合态 Ctrl+Enter、候选态 Enter 专项、连续多行中文编辑、真实取消 / 运行中关闭与迟到响应、受控 Copy fallback / 多格式恢复、其他应用及 DPI / 混合多屏等。建议本分支仅作为 Development-only 小修复与准确验收记录合并，不能据此关闭 X1 或宣称 Production GO；本次没有执行合并。**X1 PARTIAL、Vision BLOCKED、Development-only、无已核验 CI PASS、尚未 Production GO。**
