# 未知外部模型执行：禁止用人工确认清除 guard

2026-11-10，承接 `0708f85`。本模块修复现有模型恢复入口：`RECOVER` 原先仅凭目录元数据、空的本地 reservation 集合和用户勾选，就能删除未知执行 guard。这些条件不能证明原 HTTP 请求、外部服务或 runner 已结束。

## 当前行为

- `ActiveModelManager.manage(RECOVER)` 在任何 provider 查询、状态变更或 guard IO 前返回 `MODEL_EXECUTION_UNCERTAIN / EXTERNAL_EXECUTION_PROOF_REQUIRED`。正常请求的可信协议完成路径不变。
- `ModelStateStore.recoverGuard()` 拒绝重新认领历史 guard；原文件保留，不补写结束记录、不提升旧记录的权限。
- 原生桥接拒绝 RECOVER，不显示可批准的恢复窗口、不发送恢复 POST；原生窗口自身也拒绝此操作。设置页显示恢复暂不可用及原因。
- 这使未知外部执行保持阻断，没有实现真实外部模型 owner、结束证明或完整维护许可。整体仍为 **BLOCKED / productionGo=false**。

## 必要验证与证据

证据：`.verification/external-execution-boundary-20261110/` 与 `-host/`。

1. 前一阶段只编译两个模型 Java 源文件（20 个 class）。原生桥接针对性测试 `UnknownExecutionRecoveryCannotAskForConsentOrPost` 通过，0 POST、无确认窗口；前端类型检查通过。恢复连接后复核回执，未重复这些已通过检查。
2. 普通非管理员宿主账户下，使用既有原生准备工具新建合成 Profile，完整保留 Native Gate、私有文件、真实 `ModelStateStore` 所有者／锁校验。复用已编译产品字节，调用已更新的 `manualRecoveryCannotClearUnknownExternalExecutionAcrossRestart`：实际本地合成 HTTP 响应先完成正常选择，再使一次执行返回无法信任的响应；重建 manager 后，即使带人工确认的恢复 intent 也被拒绝，guard 字节保持不变，再次重建仍拒绝 AI。随后实际 Store 的历史恢复方法也拒绝，`unresolved=true`。
3. 此项使用真实产品 Ollama transport 对接本次测试进程内的合成 HTTP 服务。没有真实模型、权重下载、收费 API、外部数据发送或共享 Ollama 调用。未证明真实服务／runner 所有权或任何远端任务结束。

主回执 `-host/guard-result.json` 为 passed；`guard-cleanup.json` 确认临时 token 删除；`run-cleanup.json` 确认 Job active 0、Job 关闭、共享锁释放且 failure=null。共享互斥为 `Local\Dot_KAG_Workspace_HeavyRun`，沿用原 Job 包装、450 秒外层期限、Java 256 MiB 堆及 2 CPU 上限。

此前失败记录保留：直接 JUnit 测试未具备原生准入；第一次原生尝试的路径字符串格式不符合精确绑定；修正格式后，沙箱账户准备的目录被 Java 所有者校验拒绝，均未进入业务断言。未改 ACL、`user.name`、产品所有者校验或旧 Profile。宿主连接断开时最后一条命令未创建进程；恢复后只读确认宿主尝试目录不存在，再经正式执行审批创建全新宿主场景。各失败尝试均有 Job 0／锁释放及 token 清理回执，不将它们计为通过。

未运行全部模型测试或完整桌面视觉验收。另一组“受限会话隐藏写入”改动仍独立开发，本模块不宣称它已验证。main、六份用户文档、旧 ZIP 和原目录 Runtime 在恢复后已按基线复核不变；不推送、不重新打包。

## 外部执行恢复的最低后续条件

实际 provider 必须在发送前绑定本次 Profile、会话、请求与其活体 owner；完成只能来自同一次请求的可信终态，或确由该 owner 从创建前就持有的专属进程树结束证明。父进程持有的真实句柄、所属 Job 和一次性消费必须一起成立。旧 PID、进程数量、驻留列表、状态文件无变化、人工勾选或前次成功结束均不能替代本次证明。共享／远程 provider、未登记请求及旧版无绑定 guard 保持阻断，不能借合成进程验证升级为所有真实提供商已验收。
