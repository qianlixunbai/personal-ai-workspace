# 原产品最小退出验证：启动前目录范围未闭合

2026-11-11；源码 `42c7358e1fa6d212984f104b30fedc498a6683b9`，分支 `codex/knowledge-version-compare`。

本轮获准继续准确候选整合和原产品最多两次正常启动／退出，但要求任何身份／凭据范围／目录变化或实际拒绝都停止。实际先执行正常普通通道的身份／目录前置核对，发现当前逻辑用户目录与已批准的原绝对目标不一致，**在任何 Profile 访问、helper、产品启动或新候选构建之前停止**。不是已运行后退出失败，不是新的 OS／自动审批拒绝，也没有未知在途操作。

## 已观察事实

| 项目 | 结果 |
| --- | --- |
| 已批准原目标 | `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2` |
| 旧 helper 回执 | 2026-11-11 10:03:16.3056367 UTC，VerifyProfileNative，退出 0，root 与上述原目标相同。仅重新读取工作区内已存在的 JSON，没有再运行 helper。 |
| 当前普通执行通道 | Session 1，`UserInteractive=true`；`WindowsIdentity.GetCurrent().User` 为 `S-1-5-21-1077478615-2643175411-222072981-1004`。 |
| 当前 .NET LocalApplicationData | `C:\Users\28421\AppData\Local`；没有修改环境或身份。 |
| 旧元数据的身份信息 | 已读 `metadata.json` 仅保留 `ownerMatches=true`，未列原 SID；故本轮不能直接证明 SID 相同或不同。另有历史记录说明一次 require_escalated 通道身份比较失败、后来普通通道验证成功，那也不是当前身份的证明。 |

源码 `bootstrap/Shared/WindowsProfileSecurity.cs` 的 Parent 使用当前有效 token 的 Known Folder，且允许产品按 OS 句柄获得实际绑定根。**所以当前逻辑目录不同，不等于已经证明最终 canonical root 必定不同。** 本轮没有执行该绑定、查询任一 Profile、读取父目录或借替代入口探测重定向；当前无法证明继续启动会使用已批准的原绝对目标，不能直接按相同 ID 发起产品。

## 实际执行与未执行

已执行：Git 只读核对、必要启动／退出源码与旧组装报告阅读、工作区内旧元数据／helper／身份停止 JSON 读取及 SHA256、当前执行进程自身的有效 SID／Session／LocalApplicationData 查询。新回执为 `artifacts/live-exit-scope-preflight-20261111/scope-stop.json`。

未执行：新候选整合／publish／打包、原产品 Profile 元数据或内容访问、helper／NativeGate、产品或测试窗口、UI Automation、全桌面枚举、Java／数据库／模型、保存／重开、token 更新、WebView 清理、部署或推送。**本轮实际启动 0 次，获准两次上限未消耗。** 旧候选不含 `42c7358` 的可见退出入口，上一轮普通 WPF 编译输出不是可直接替代的 Profile 候选。

此前受拒的合成 `2fd8ddc494cb45b5a7c8db284fbe386c` 元数据、旧 Crashpad 列举及其他具体拒绝继续停止，没有触碰。没有提权运行 helper、切账户、修改 LocalAppData／user.home／ACL、复制原 Profile 或创建同 ID 的其他 Profile 来消除本次差异。系统组件存在和 Session 1 仍不等于实际 GUI 控制通过，也不证明 CUA 同机。

## 最小继续条件

需要先由执行环境提供可核实的原授权主体／Known Folder 对应关系，使正常产品准入能够绑定原绝对目标；若涉及恢复连接或账户由环境／用户处理，不由代理模拟、改路径或换身份。仅有旧 helper 成功、当前主机在线、相同 Profile ID 或一般“继续开发”不能填补这项对应证据。

范围闭合后才能继续：针对原目标进行已允许的精确配置／迁移／guard 前置核对；整合准确同源 Profile 候选并核对 pins；第一轮只操作本次自有窗口的“退出工作区”，保留未保存确认，核对原 ExitAsync 和 Runtime 正常关闭事实。第一轮成功后才在总计两次限制内判断最小合成保存／独立重开能否完成。原步骤和人工兜底见 [可见退出报告](VISIBLE-WORKSPACE-EXIT-REPORT.md)。本轮没有把这些待执行步骤记成验收通过，`productionGo=false`。

只本地提交本报告和状态／路线图链接，六份原始未跟踪文档、旧包、用户数据、main 与 KAG 保持原样。
