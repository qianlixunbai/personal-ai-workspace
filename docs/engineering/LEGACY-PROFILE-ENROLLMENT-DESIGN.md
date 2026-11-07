# 旧工作区受控登记：建立当前边界，保留未知历史

2026-11-07；基线 `d788361`，在 `2e23419` 后补充原生规则收尾与专属模型执行树核对。这是 [主设计](CURRENT-PROFILE-RECOVERY-DESIGN.md) 的实施补充提案，**不是已开放的个人迁移入口**。原设计文件保持原字节。实现均只在新建隔离环境验证；真实 Profile、实例配置、服务、凭据和业务数据本轮未访问或修改。

## 结论与范围

旧工作区不必永久证明所有过去的进程都如何退出。可行目标是：在一次明确授权的维护窗口内，**主动建立并持续持有当前的新连接／文件写入边界，等待已有工作结束，再从这个起点登记**。此前历史明确记为 UNKNOWN，永不生成假 `exited` 事件。缺失的 `runtime-admission.lock` 只能在边界成立后以 CREATE_NEW 建立；它本身不是边界成立的证据。

该路径不能只凭管理员口令、数据库表锁或 HBA 规则放行：原生 Profile 身份、已有会话自然退出、模型执行状态、持久 guard、终态数据与源文件一致性、来源登记、隔离目标和显式激活仍分别检查。个人现有路径继续 BLOCKED。

主设计第 3 节现有维护流程依赖已登记执行；本提案增加一个独立的“旧工作区首次登记”操作，需要实现并审查新的当前边界能力，**不能修改原检查为缺历史即成功，也不能把本实验接到个人备份按钮**。业务范围仍为 Workspace/Knowledge，不增加 Finance、原地恢复或跨机迁移。

## 成熟方案的依据

- PostgreSQL `pg_upgrade` 在升级前要求停止相关服务器：其安全起点是可核实的当前离线状态，而非补造每次历史退出。本项目不能照搬为停止同机共享实例。[PostgreSQL 17 pg_upgrade](https://www.postgresql.org/docs/17/pgupgrade.html)
- pgBackRest 区分禁止新操作和处理已有操作，默认 stop 允许现有任务完成；`--no-online` 备份也要求 PostgreSQL 已关闭。借鉴“阻止新增 → 等待存量结束 → 验证状态”的顺序，不使用 force 来制造成功。[pgBackRest command reference](https://pgbackrest.org/command.html)
- Borg 明确文件备份本身不提供应用一致性，需要应用停写、快照或数据库专用导出。本项目继续要求 SQL 快照与 revision 文件句柄共同绑定。[Borg consistency guidance](https://borgbackup.readthedocs.io/en/latest/quickstart.html#important-note-about-files-changing-during-the-backup-process)
- PostgreSQL HBA 按第一条匹配规则决策；Windows 后续连接立即读取更新后的规则。`pg_dump` 支持一致性快照，不能单独证明外部文件或模型已停止。本提案据此推导出窄的每库连接限制，并用本地实验证明其能力和不足。[HBA](https://www.postgresql.org/docs/17/auth-pg-hba-conf.html)、[pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html)

## 拟实施顺序

1. **只读登记前检查。** 沿原 NativeBootstrap/WindowsNativeGate 核对当前 Profile。新增专用于登记前元数据的受限上下文，允许在缺早期 Runtime 锁时核对固定受保护应用配置、真实 DB/角色身份；只返回元数据，无 SQL 写接口、无任意路径、无备份能力，不调用会创建锁的普通 Runtime 启动入口。原失败分支不得直接跳到解密器。此前用户对这些只读检查的批准继续有效；本轮尚未实现这条新上下文。
2. **准备具体计划再确认变更。** 用实例标识／DB OID／Profile 根和 data 原生身份绑定 operation；列出唯一目标库、短期维护角色、HBA 文件身份／原始摘要／拟新增规则、保存位置、最长等待时间和恢复步骤。新 DPAPI intent 位于独立 Recovery 登记域。这里开始涉及真实写入，必须先提供具体对象与影响；不能把用户对只读检查或“Git 能回滚”的同意转换为已批准的数据库维护。
3. **仅限制该库的新连接。** 在经验证的 HBA 文件前安装精确数据库名规则：仅本次受限维护角色从 loopback 通过，其余 IPv4/IPv6 来源拒绝；其他数据库沿原规则。临时角色使用原设计的最小权限，既有 app 角色权限和密码不改。持续 pin 规则文件、验证解析结果并实际验证新旧身份连接。不要使用 `CONNECTION LIMIT 0` 代替完整隔离；也不把 `ALLOW_CONNECTIONS false` 直接套给需要新连接的 pg_dump。
4. **等已有会话自然退出。** HBA 不终止已有连接。只要目标库还有不属于本次持有连接的 backend，状态就是 WAITING/BLOCKED；有超时便停在此处，不调用 `pg_terminate_backend`、不取消事务、不停共享 PG。核对 prepared transactions、恢复/订阅/后台写入来源；未知扩展、后台任务或实例身份不明均拒绝。仅“client backend 数为零”不覆盖所有服务器侧写入来源。
5. **独立建立模型和文件边界。** 持有原 Desktop mutex 和已有 model-state 排他句柄；检查原 guard，不删除、不 RECOVER。已知请求需原 owner 的完成证据；未知请求需提供方可验证的 drain/结束事实，或在单独批准的维护窗口内核实其精确服务及直属 runner 均结束。端口不监听、`/api/ps`、无 guard 或数据库围栏都不是模型结束证明。若该服务与 KAG 共用，必须列清影响后另行确认；本提案不把停止共享 Ollama/重启电脑作为自动步骤。远端提供方不能用本机进程退出替代证明。
6. **形成当前的一致性起点。** 从已建立边界的连接复用主设计的 17 表锁、V1/V2 catalog、safeJobs、终态关系和 revision 清单；逐个 pin 对应源文件，核对字节、解析表示和 locators。PENDING/PARSING/delete intent 仍拒绝，不在维护里修复。这个阶段会读取业务数据／源文件，须纳入后续具体维护／备份许可，本轮未读取。
7. **首次登记并继续原流程。** 仅在 3–6 全部成功且相关句柄持续持有时，用 CREATE_NEW 建立缺失的早期 Runtime 锁和新 epoch 记录。记录明确 `historyBeforeEpoch=UNKNOWN`，绑定当前 SQL／文件／模型证据及 owner 生命周期；没有“过去全部正常退出”的字段。新的活体能力只能在同一 owner 内消费一次。磁盘 epoch 不是可重建权限的成功标记。随后才可沿既有原生安全输入、加密 v3、短期角色收尾与独立来源登记进入新目标恢复链；恢复、READY、显式激活仍是各自阶段。
8. **释放与后续备份。** 结果核对完成后撤销本次短期材料，再核对并恢复本次确切访问规则。释放 SQL/文件/模型句柄后，当前边界能力立即失效。旧客户端可能再次运行，因此不能宣称 epoch 建立后永久禁止所有旧写入者；后续每次备份仍需重新建立当前边界，不能只看最近一次成功登记。

上述步骤中的“当前边界”取代的是无从追溯的历史要求，并不取代原设计要求的当前未知执行拒绝、停写、一致性和来源授权。若第 5 步无法核实，允许第 3 步的变更按受控撤销流程退出，但绝不发布登记成功。这是需要继续实现和验证的明确条件，而非要求用户补交不存在的历史日志。

## 回退与风险

| 情况 | 可执行的回退／保留 |
| --- | --- |
| HBA 安装前失败 | 无访问规则变化；保留 intent，不创建锁或来源登记。 |
| HBA 安装成功，但等待会话／模型超时 | 核对仍是本次 owner、实例、文件身份和已安装字节后，仅恢复本次 HBA 改动并核对连接；不杀会话、不把超时改为成功。 |
| HBA 内容被其他管理员改变，或发布结果未知 | 不用原副本覆盖新配置；保留原／拟写字节和操作记录，独立只读核对。当前连接限制可能继续存在，明确报告可用性影响。 |
| owner 崩溃 | 新连接可能持续被限制。需要独立 `InspectEnrollment`，绑定实例／operation／原与当前配置；不重放安装、备份或激活。该恢复消费者尚未实现。 |
| 登记／备份部分成功 | 不删历史、intent、未发布目标或密文；不将部分记录用于普通启动。按原设计的 UNKNOWN 规则处理。 |
| 代码需回退 | Git 恢复点为 `recovery-before-legacy-enrollment-d788361`。优先 revert 本轮提交；不 reset/clean 用户未跟踪文件。五份文档另有本地校验副本。 |

恢复 HBA 只恢复访问策略，不会复活已经退出的连接、撤销业务期间已提交的事务、恢复被取消的模型请求或回滚外部副作用。Git 不备份数据库、Profile 内容、凭据、模型、未跟踪数据或同机服务状态。不能保证用 Git 完成这些对象的回滚。

HBA 是实例级配置文件，即使规则只匹配一个库，语法错误、文件权限、包含文件、配置并发修改和服务账户差异仍可能影响新连接；真实实现必须使用原身份/ACL合同、正确转义数据库名、受控原子更新、确切规则解析和实际连接核验。实验只覆盖 Windows PG17/TCP，未覆盖 Unix socket、IPv6 实际连接、include 并发、复制/后台扩展、集群切换或恶意管理员。共享实例若不能安全实现这份局部计划，应选择另行批准的离线维护窗口；不扩大停止范围。

## 本轮已实施和实际验证

`bootstrap/tests/LegacySourceFenceProbe.java` 与 `scripts/verification/test-legacy-source-fence.ps1` 是**手动隔离实验**，不是产品工厂。runner 只创建新的私有临时根、自生成凭据、随机 loopback 端口；在创建数据库前核对 PID/data/端口、实际连接的 data/HBA 路径、PG17，以及全新实例的 3 数据库/1 非系统角色 inventory。没有真实 Profile 参数、已有数据库选择或个人 source 发布入口。

仅执行一次，首次通过。实际验证：

- 针对新建源库，HBA 拒绝旧 app 和管理员的新连接；另一个新建数据库仍可连接，sentinel 不变。
- 规则生效前的旧 app 连接仍能执行一次合成写入，证明“安装围栏即停写”是错误的。随后只由该 probe 自愿关闭其连接，确认当前源库其他会话已结束，无 prepared transaction。
- 保持 SHARE 锁与 exported snapshot，由新建受限角色启动真正的 pg_dump 并导入该快照；同时 Windows 源文件禁写句柄拒绝写入。期间旧 app 仍无法建立新连接。
- 核对当前 HBA 正是本次安装字节后恢复原始字节；原 app 可重新连接，业务角色权限未因围栏而改变。没有创建 Runtime 历史、Profile 锁或个人登记；模型仍为 UNPROVEN。

专属实例为端口 `62705`／PID `16144`，经启动时间、data directory、端口及可执行路径再次核对后已停止。所有合成数据、dump 和非敏感证据保留；自产初始化密码文件、pgpass 和 fixture 凭据文件已删除。未启停既有 PG/Ollama/KAG。JVM 为 256 MiB/2 核，PG 为 16 MiB shared_buffers/12 connections。只编译这个手动 probe，未跑 Maven、Desktop、前端或全套测试；javac 有 5 条既有 Windows `ExtendedOpenOption` 内部 API 警告。

结果为 `SYNTHETIC_DATABASE_FENCE_VERIFIED_MODEL_UNPROVEN`，不是 v3 完整备份／恢复验证（实验表是单独的 synthetic_source），也不是受保护卷、故障恢复或完整 Native Gate 实机验收。证据在 `.verification/recovery-legacy-enrollment/` 的 `probe-result.json`、`fixture-result.json`、`owned-fixture.json`、编译／执行日志和文档摘要。

## 新增：规则中断核对和模型结束证据

原生 `LegacyRuleChange` 为后续 owner 提供实际文件操作原语，目前唯一私有工厂明确限定合成临时根。DPAPI 意图只记录操作、路径、文件身份与摘要，不记录凭据，不是 SQL 准入。持有私有父目录和操作锁后，按实际四处文件（当前 HBA、原始保留、候选、已撤销规则）的身份／摘要组合识别 Prepared、InstallInterrupted、Installed、RestoreInterrupted、Restored；其他组合或访问失败均为冲突／未知。重新打开和 Dispose 不执行修复，也不把磁盘意图转换成新安装许可。

安装先用原始文件的排他 DELETE 句柄移动到保留名，再用候选的排他句柄移动到 HBA 名；每次移动的目标必须为空。撤销对称执行，保留原始文件身份和 ACL。这样避免“先按路径核对、随后覆盖另一对象”的竞争；第三方同内容替换也不会被覆盖。原始／候选字节、记录未完成发布、被占用、异常链接或并发插入对象都不能靠自动重试绕过。[Windows FILE_RENAME_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_rename_info)

**代价与适用限制：** 两次移动间 HBA 名称会暂缺，可能影响共享实例所有新连接，owner 中断会延长此状态。故不是原子配置热更新，不得承诺仅源库短暂不可用；正式使用必须批准该维护窗口，或改用经独立验证的等价方案。合成根只允许私有 ACL，尚不支持任意 PG 服务账户的真实 ACL。跨进程的 Java 验收桥只是夹具，未建立个人 Profile／PG system ID／DB OID 与整个活体句柄生命周期绑定。掉电时文件系统持久化顺序、异常布局及管理员并发变化仍应保留证据人工核对，不能保证自动恢复服务，更不能保证业务数据回滚。

`RecoveryOwnedModelExecution` 仅为新的、专属的本地执行树实现结束核对。CreateProcess 挂起创建→加入匿名 Job→恢复主线程，关闭先启动后加入的逃逸窗口；Job 不允许 breakaway，没有添加第二个根的入口。根进程的真实句柄退出、整个 Job ActiveProcesses 为零且根正常退出后，同一活体 owner／operation 仅可消费一次结果。清理／Dispose 先失效，再关闭 Job；外部 PID、历史 JSON、监听缺失、空模型列表均不能生成结果。[CREATE_SUSPENDED](https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags)、[Job accounting](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_accounting_information)

Job 不是未知模型的通用隔离器：WMI 创建的进程不自动加入父 Job，服务代理／远端工作也不属于该树。将来只可接入已审查且全部执行都归属于该 Job 的提供方，并绑定完整受信产物、Profile、请求及持久 guard；本轮没有此真实提供方适配。根退出与 Job 清空不证明业务成功，不授权清除失败／未知 guard。[Windows Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)

新增的一个原生聚焦用例验证：安装中断后只读重开；显式撤销；相同内容但不同文件身份拒绝覆盖；撤销中断后恢复；主进程退出时 runner 仍存活而拒绝结束；runner 自愿结束后才单次通过。原生首次因目标字符串 NUL 遗漏失败，修复后通过。PG 集成首次被新 data 目录继承 ACL 拒绝；改为在下一新建空目录创建时设置保护后通过。没有放宽产品 ACL、修复旧目录或扩大测试集。

通过的专属 PG17 端口 `60062`／PID `17040` 已停止；首次失败的 `59974`／`36624` 也已停止。真实 pg_dump 仍导入持有的 snapshot；原生规则 owner 在撤销中途退出，独立进程检查不改变 HBA，明确撤销后原文件身份／字节恢复，app 重连成功。独立模型树使用两个轻量合成程序，不加载模型、不访问 Ollama/KAG；它不为该 PG 探针或个人工作区提供模型准入。证据及失败记录在 `.verification/recovery-legacy-boundary-v2/`，旧阶段证据保留。

## 后续需要的最小具体授权

已有“当前 Profile 身份、Runtime 历史、应用配置连接元数据”的只读许可不重问。完成新的只读登记前上下文后，可据该许可确认源身份。以下操作仍需先给出具体目标和回退方案，再获得相应批准：

1. 对确认的单个数据库创建／回收本次维护角色，并在确认的 HBA 文件安装／撤销精确源库规则；涉及管理员权限及临时连接不可用。材料只进入原生安全输入，不进聊天或 Git。
2. 读取该 Profile 的模型执行归属／guard 等必要元数据；若需结束某个已确认服务或 runner，另列 PID／创建时间／服务名／共享消费者影响。不能用宽泛授权停止 KAG 或身份未知进程。
3. 在当前边界全部成立后，写入该 Profile 缺失的锁与独立 epoch；以及读取业务数据／源文件、创建加密备份和新的恢复目标。实际备份、恢复和激活仍分别绑定具体对象，尚未执行。

近期实现重点是安全的登记前只读上下文、原生规则 owner 与真实数据库／服务身份和批准计划绑定、实际模型提供方与 guard 的结束证据适配，然后把持续持有的活体边界接入既有来源生产链。本轮原语和合成验证不替代这些绑定，不能直接把字符串规则、意图 JSON 或合成模型结果当作个人准入能力。
