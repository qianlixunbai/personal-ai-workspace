# 当前来源／目标绑定的原生最终确认

2026-11-10，承接 `304af0a`。本轮完成一个实质模块：**现有维护迁移 transport 的最终确认闸门**。确认前只读取并持有当前来源／模型状态和目标事务；取消不创建迁移登记、不导入、不发布配置。确认必须绑定这次活体资源，随后才进入既有备份、导入、配置发布与受限 Runtime 接管流程。

普通源工作区的已验收生命周期未改、未重跑；完整个人维护仍为 **BLOCKED / UNREGISTERED_WRITERS_UNPROVEN，productionGo=false**。本模块没有开放通用维护按钮，也没有把合成 UI 操作当作真实用户的数据迁移许可。

## 完成判据与实现

- **确认前没有持久化迁移副作用。** `MaintenanceMigrationReview` 先取得真实 `SqliteSourceLease` 和 `MaintenanceModelState`，读取来源内容摘要／数量，并创建 `SqliteCombinedMigration.PreparedTarget`。operation 和随机 nonce 仅存在内存。旧／未完成维护记录会阻止准备。最早的 `SOURCE_FROZEN` 日志在当前确认校验通过后才创建，取消不会使普通启动被一个新的维护记录挡住。
- **同一目标事务贯穿审阅到导入。** 原导入器与新审阅共用 `PreparedTarget`，保留实际连接、目标实例／数据库／角色／schema／凭证表身份和写入锁。`requireCurrent` 在确认前和复制前复核当前身份、空目标和 schema；备份内容必须等于已审阅来源摘要。没有二次按名字寻找目标，也没有重新打开另一个连接来执行导入。
- **原生确认绑定当前内容。** `MaintenanceRuntimeHandoff.StartAsync` 改为 PREPARE → REVIEW_PREPARED → 原生窗口 → CONFIRM/CANCEL → 既有迁移。返回的 JSON 摘要包括 Profile、operation、随机 nonce、来源文件身份／摘要、模型状态摘要、目标实际身份、数据库／角色名、两组内容摘要／行数、限定迁移范围和禁用模型。父进程核对 SHA-256、实际 child PID、Profile 和先前选择的连接名称／端口。窗口只展示非凭据内容，默认取消，必须先勾选核对项才能确认；代码中没有接受 caller boolean 的产品批准入口。
- **确认一次有效。** 原生窗口对象只能请求一次；Java 只接受当前 operation／digest，并重新检查活体源、模型与目标。审阅最多保留 120 秒，超时／关闭管道／取消使本次资源失效。成功确认的摘要进入所有新维护日志事件，读取时检查其格式和链内一致性；旧日志可读，但不能获得新确认能力。
- **确认后失败仍保留证据。** 进入实际操作后继续使用原有 CREATE_NEW、备份复核、一次提交、未知提交保留和配置发布屏障。新的确认不会清除或重试已有未完成操作，不能覆盖后来数据或后来配置。

目标业务表和控制表使用 SHARE ROW EXCLUSIVE；凭证表按既有最小权限保留 ACCESS SHARE，并在最终确认前及导入前检查为空。仅有 INSERT 权限不能取得同样的强表锁，不能为了验收提高权限；PostgreSQL 的锁与权限规则见 [PostgreSQL 17 LOCK 文档](https://www.postgresql.org/docs/17/sql-lock.html)。凭证若被并发插入，确认失效；更晚的竞争由 singleton 约束和原事务回滚路径拒绝。

## 实际验证

只编译本轮 4 个 Java 源文件、21 个 class，基于上一已交付 JAR 更新；5 条既有 ExtendedOpenOption 警告。原生构建 0 警告、0 错误。产品 Runtime SHA-256：`154191B71F5F57D6E697A1ECB2DECE03690F820B27994AFEE8C6FE8E5622DD4C`；Desktop SHA-256：`99BF58A26247C7F2AB7F6388C78A946E8DE0B106E46212E04F948E7CD76DCC0D`。

主流程证据：`.verification/maintenance-confirmation-20261110/`。新合成 Profile `b14747c26ce245588f89fb76ebe8d416`；真实临时 PostgreSQL 17.11、受限应用角色、非空合成 SQLite／知识来源和本地模型选择。没有启动模型、下载权重或调用收费 API。

| 检查 | 结果 |
| --- | --- |
| 原生确认窗口 | Windows UI Automation 只匹配本次验收 Process 的窗口；实际展开摘要、核对 Profile 与数据库，确认按钮初始禁用。第二次实际 Toggle 勾选后按钮才可 Invoke。没有产品内批准回调。 |
| 审阅期间来源写入 | 对真实来源 `memory.db` 请求写句柄失败，原字节摘要未变。 |
| 审阅期间目标写入 | 另一真实 psql 连接请求业务表 ROW EXCLUSIVE，返回 `55P03`；SELECT 仍可见业务行与凭证均为 0。 |
| 第一次取消 | 实际 Invoke 取消按钮，子进程正常退出；三个迁移 registry 均不存在、目标 `0|0`、来源不变，来源独占写句柄重新可取得（未写入）。 |
| 第二次确认 | 新 operation／随机审阅摘要与前次不同；实际原生勾选并确认，进入备份、导入、配置发布、真实 Spring PostgreSQL 接管。operation 为 `a0906b9d30a641468667eb574823568f`。 |
| 结果与收尾 | 真实 HTTP storage/status 返回 POSTGRESQL；目标 1 条来源记忆／1 条导入凭证，原来源字节不变。受限 Runtime 完成原有 FINISH 和退出复核。 |

agent-desktop 的技能说明已读，但 CLI 未安装／不在可调用路径；没有安装工具或修改环境。使用项目现有 WPF 验收技术栈执行了真实 UIA 语义操作。未做截图布局、人工键鼠、完整迁移向导或真实用户点击验收，`visualAcceptance=false`。

随后仅补两个针对性拒绝场景：`.verification/maintenance-confirmation-rejections-20261110/`，新 Profile `1c4b6894cdad4ba4800e7bc1a38712f3`。**完整复用上述 Java／Desktop 产品字节，只编译新增验收客户端**；未重复正常 UI 迁移、普通启动、普通重开或中断流程。

1. 实际 native owner 准备后，将确认摘要改一位再提交；返回 STOP／退出 10，目标仍 `0|0`，没有迁移 journal／配置，来源不变。
2. 另一次实际准备后，独立应用连接成功插入本次合成竞争凭证，再发送正确的当前确认；仍返回 STOP／退出 10。保留竞争凭证，目标为 `0|1`，业务数据未导入；没有迁移 journal／配置，来源不变。

这验证了摘要不匹配及真实目标状态改变时的失败关闭。未测试所有时序组合、真实断电、提交应答丢失、窗口过期矩阵或 privileged DBA 的对抗性修改；未扩展前轮既有验证范围。

主流程 `confirmation-result.json`、`native-parent-cleanup.json`、`fixture-result.json`、`run-cleanup.json` 均成功。guardian 16980 于 `2026-11-10T15:59:22.8187523Z` 记录 Job active 0、锁释放；拒绝流程 guardian 45924 于 `2026-11-10T16:06:37.4005983Z` 同样记录 Job 0、锁释放。两套临时数据库正常停止，初始化密码、合成已发布／暂存配置和客户端 token 已清理，未触碰既有服务。共享锁始终为 `Local\Dot_KAG_Workspace_HeavyRun`，静态编辑／Git／报告不持锁。

## 能覆盖和仍不能覆盖的范围

| 写入者／资源 | 当前覆盖 |
| --- | --- |
| 遵守原 runtime-admission 协议的 Java | 活体排他句柄排除新的参与者；历史退出记录不能替代它。 |
| SQLite 真值文件及已引用知识原文的普通文件写入／替换 | Windows 文件身份与 NOSHARE_WRITE／DELETE 句柄直接冻结，不靠 PID 清单。只覆盖本迁移声明的数据集合。 |
| 目标业务／控制表的普通 PostgreSQL 写入者 | 真实事务表锁约束，包括忽略应用 advisory lock 的客户端。未停止整个数据库实例。 |
| INSERT-only 导入凭证 | 不谎称全排他；当前重验、唯一约束和回滚拒绝竞争，不扩权、不删对方数据。 |
| 本地模型选择／验证状态 | 原模型锁和快照保留；发现未知 guard 或改变则停止。文件静止不是外部模型任务完成证明。 |
| 外部模型服务／远端请求／手动模型进程 | **未覆盖**。没有枚举、接管或终止未知进程，也没有把当前确认变成模型执行授权。 |
| 未登记数据域、旧版本未遵守协议的额外资源、管理员对整个存储的恶意回滚 | **没有全局证明**。仍沿用原信任边界，不从本次合成记录推导完整维护许可。 |

剩余最小条件：把已发布迁移的逐会话协议接入真正的普通原生启动授权，并保持中断失效；把实际模型执行 owner／结束证明及未知状态接入维护准入；在这些准入成立后，将通用维护入口的来源／目标选择连接到本轮已经实现的原生最终确认。迁移后的普通启动仍被原 `MaintenanceStartupFence` 阻断。不存在待批准的真实模型运行或真实数据迁移动作，本轮也没有自动审批拒绝或未知执行结果。

main、六份用户文档、真实数据／凭据／默认配置及旧包保持原状；不推送。没有重试旧端口、被拒 Profile 或未知进程信息读取，没有修改现有权限或系统安全／网络设置。新合成 Profile 仅通过原有受控准备工具创建。
## 本地交付凭证补记

2026-11-10 断连后完成限定只读核查，确认此前打包成功；没有重跑打包、测试、服务或清理，也没有修改包内容。产品与本报告原始提交为 `fa9a5943632d0f1dfe09d904d69b57c087d7fd6e`。

- 开发包：`artifacts/PersonalAiWorkspace-maintenance-confirmation-20261110.zip`，目录副本为同名无扩展名目录。
- ZIP SHA-256：`D8A8FF61EF20245485400CAED48364517C9337AD312A6EC8E88AFC155A6FB43F`。
- 933 个载荷文件，含清单共 934 个 ZIP 条目和 934 个目录文件。逐一核对归档与目录文件的 SHA-256、字节数及清单，全部一致，无缺项或多项。
- 发布 Runtime SHA-256：`50F62968AE2B6615D47F50CE90A6E438CD309BF5440A2E14A3FB1FB178EBE083`；Desktop DLL SHA-256：`A3ED2DD95AF97A937F36BA91A24B6175F665F9BF3861F0A00598C0D29A77A097`。交付凭证中的 `desktopSha256` 指 DLL。Runtime 与已验收运行字节仅构建来源标记不同；420 条内嵌校验值为此前打包验收记录，本次未重执行嵌入资源验证。
- `.verification/maintenance-confirmation-20261110/delivery-result.json` 与清单均绑定上述产品提交，`productionGo=false`。清单的 `sourceDirty=true` 与当时保留六份未跟踪用户文档的现场相符；只读核查时没有已跟踪文件改动。
- 同目录 `publish-cleanup.json`：`2026-11-10T16:13:18.2659818Z`，guardian PID 41896，`jobActiveBeforeCleanup=0`、`jobReleased=true`、`lockReleased=true`、`failure=null`。上次打包和该次清理的未知状态据此解除，没有扫描或终止任何未知进程。

该包仅为开发交付，不是安装器，不开启更新，不授权真实数据迁移或外部模型执行；完整维护仍为 **BLOCKED / productionGo=false**。完整视觉验收及通用维护向导未完成，剩余功能准入条件仍以上文为准。此次补记和文档提交不改变包内容与产品来源提交。
