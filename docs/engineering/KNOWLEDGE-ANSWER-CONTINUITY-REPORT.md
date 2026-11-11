# 知识问答草稿与任务连续性

2026-11-11，接续 `82b31bf`，本轮第三项完整模块。此前离开资料页会调用问答组件的 clear，丢失未提交问题、检索范围和处理中任务的页面状态；用户返回时需要重输，也无法在原位置核对已提交任务。

现在同一 Bridge 会话内切页保留问题、关键词、准确源版本范围、问答历史列表、当前回答与任务 ID；隐藏时暂停自动轮询，返回后继续读取原 ID，不重新提交。离开期间已发出的提交回执可以在同一会话接收，旧会话回执仍丢弃。状态读取失败停止轮询并提供主动核对；提交结果未知时保持本页重发阻断，查看相同问题的历史不会清除此状态。新增同步操作锁阻止快速重复操作。

当前回答另列出实际对应的问题、关键词和范围。编辑下一题或改用其他范围不会将旧回答重新解释成新范围的回答；引用仍按原任务读取。明确“开始新问题”经确认后清空页面编辑内容与结果，不删除已保存历史、不提交模型任务。原有历史替换草稿确认继续保留。

保留范围仅为当前挂载页面与连接会话，不做自动磁盘保存。关闭应用或会话重建后未提交草稿仍不能恢复；会话重建不证明外部模型完成，也不解除 Runtime／模型原生准入或 uncertainty guard。已发送任务的取消和完成事实仍沿用原协议，离开页面不是取消成功证明。

## 必要检查

证据目录 `.verification/knowledge-answer-workflows-20261111/`：

- `continuity-initial` 首次类型检查发现新测试误用 Testing Library 的 `exact` 参数，UI 未执行；失败记录保留。
- `continuity-types-repair`：修正测试参数后类型检查退出 0；只执行 6 项相关场景，全部通过，14 项原有场景按名称筛选跳过。
- 三项新场景覆盖实际 App 页间往返、未发送问题与准确范围、回答原输入身份、新问题确认、提交中离开、暂停／恢复状态读取、状态失败后手动核对、双击防重、未知结果不重发、读取历史不误解除阻断、会话切换迟到回执。
- 三项直接受影响场景为历史替换／删除时保护问题、原资料范围恢复，以及本轮问答摘录保存／回查。后一项仅把文字断言限定在当前可见回查区域，以适应后台保留的问答 DOM。

测试全部为内存合成 Bridge／jsdom，不初始化产品、数据库、Profile、模型或 JNA。每次运行 Node 384 MiB、Vitest 单 worker，并持有及释放 `Local\Dot_KAG_Workspace_HeavyRun`；最终 `failure=null`、`lockReleased=true`。没有完整 EXE／WebView、物理输入或模型质量验收。

## 三模块交接

本轮完成的独立使用能力：

1. `da6e3a9`：[回答选段到可回查记忆](KNOWLEDGE-ANSWER-MEMORY-REPORT.md)。
2. `82b31bf`：[已保存问答到当前会话参考](KNOWLEDGE-ANSWER-REFERENCES-REPORT.md)。
3. 本报告所在提交：问答页面的草稿／回答／处理中任务连续性。

按约定到此换同项目新会话。先核对 HEAD 和精确完成回执，不重复已通过检查；这条摘录复用链已补齐，不应为了凑模块继续增加同类入口。下一轮以实际核心缺口或新的可验证阻塞证据排序；若剩余核心工作都需要当前禁止的原生运行，就准确报告阻塞。

- 分支 `codex/knowledge-version-compare` 只保留本地可回滚点，不推送；main `f5266bf4383f52a2293b0fe4bfe1964af958e64a` 不变。六份未跟踪用户文档继续保护；未修改 KAG 或 personal-finance-OS。
- `ModelStateStore.java` SHA256 必须保持 `2E2A097F34A5AAE5381DBA2D125B6F64CDA48BC9747BD2A3C5106453AE6B28AE`。`WindowsPrivateAcl`、`WindowsNativeGate` 和安全候选不修改、不部署、不原生运行。
- 不读／重新登记原或替代 Profile／model-state，不运行 helper、armGuard、NativeGate、模型，不改 ACL／权限／身份，不新增系统进程／端口采样。历史 18765 查询和特定旧 Profile／Crashpad 拒绝不得换工具重试。外部模型恢复／迁移不明确继续阻断，不凭本地请求终态推断远端完成。
- 无第三方模型／Tavily费用或数据外传授权。旧包 `PersonalAiWorkspace-knowledge-conversation-workflows-20261110.zip` 仍对应 `74a947f`／source `b4ae52e`，不含上一轮三模块、本轮三模块及安全候选。本轮没有打包、部署或原生运行。
- **完整验收仍 BLOCKED / productionGo=false。** 本地源码／合成场景通过不得写成产品全链路可用。

## 本轮权限回执

本地 Git 暂存／提交第一次因沙箱 `.git` 只读而未执行（index.lock Permission denied），确认 HEAD／暂存区未变后查阅用户 Git 回滚授权，经正常 require_escalated 审批成功完成；不是绕过目录权限。

收尾寻找既有保护文件哈希清单时，执行 `rg --files .verification -g '*guard*' -g '*initial-state*' -g '*protected*' -g '*baseline*'` 的目录枚举在以下目标被文件系统拒绝（os error 5）：

- `.verification/browser-smoke-auth`
- `.verification/m2b2b-r1-chrome/private`
- `.verification/local-smoke-auth`
- `.verification/postgresql-credential-recovery-fixture`
- `.verification/mmf-3-same-account/run-4a5ae09cbaa24be0af840b1c7efda64c`
- `.verification/mmf-1-review-1l0_ctyf`
- `.verification/v1-ollama-0401-environment/models`

该枚举退出 1，不是成功读取这些目录；没有读取其文件内容、没有再枚举或换手段重试，未请求解除权限。之后仅核对已知源码路径和已返回的普通保护清单 `.verification/task-shutdown-20261110/protected-inputs-after.json`。这项无必要的广域证据搜索已停止；下一轮不要扫描整个 `.verification`。其余开发与精确验证已经完成，无后台检查遗留。
