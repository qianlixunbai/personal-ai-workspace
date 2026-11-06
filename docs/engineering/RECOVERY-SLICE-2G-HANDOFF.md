# Recovery Slice 2g：真实源角色—加密备份—回收闭环

日期：2026-11-07（Asia/Shanghai）。基于 `3b4cd3be0d7daf10ae7f26413f5d394edb732cb7`，分支 `codex/recovery-v3-workspace-core`。继续执行权威恢复设计第 3–4 节：本轮从意图记录推进到**实际创建短期源角色、生成 Workspace-only v3 密文、回收角色后才返回成功结果**。该执行核心已在本轮全新独立 PostgreSQL 17 实例中验证；**个人来源登记生产者仍未完成，原生维护仍为 BLOCKED / UNREGISTERED_WRITERS_UNPROVEN。**

## 已完成的实际执行阶段

- 新增包内 `WorkspaceSourceBackup`，由同一个 owner 管理本次角色与备份执行。`backupSynthetic` 仍严格要求显式合成停写前提、B3 合成库名/ownership receipt、非 5432 端口；没有 CLI、HTTP、原生产品接线或个人数据入口。它先检查真实源 PG17 system identifier、database OID、端口和连接角色，校验受审查 catalog、无其他源 session，然后用本次 backupId 派生的全新角色名 CREATE_NEW。role OID 与本次 COMMENT receipt 一起用于精确收尾。
- 短期角色无高权限或 membership，只授予本源 CONNECT、workspace USAGE、固定 17 表 SELECT/UPDATE；UPDATE 仅供现有 SHARE 锁，备份引擎不发源 DML。检测到继承的其他 DB CONNECT、源 CREATE/TEMP、其他业务 schema 权限或 workspace CREATE 就拒绝并回滚，**不会修改 PUBLIC/日常应用权限来取得成功**。本轮合成夹具只在已确认全新 initdb inventory 的独立集群中设置测试权限。随机临时密码不写备份/文件/日志，登录到期 30 分钟；管理连接限制语句 15 秒、锁等待 3 秒。
- 复用现有 v3 真实快照、17 表锁、pg_dump、OpenPGP 流和当前状态核对。`UnifiedPostgresqlBackup.Streamed` 直接返回写入 ZIP 的 manifest 原始字节摘要和同一源连接读取的 database OID；`WorkspaceBackup` 保留这两项结果，不从重新序列化的 manifest 或外部 SHA 推断。既有 v1/v2 格式和 v3 合成来源限制未删除。
- 无论成功、普通异常或 Error，都走本次角色的收尾；再次连接必须匹配原真实实例/database/receipt。先独立提交 NOLOGIN/PASSWORD NULL 并读回，确认没有该角色的 session/membership，再仅撤销本次固定授权并 DROP ROLE，最后确认不存在且连接关闭成功。没有 DROP OWNED/CASCADE、未知连接终止、源数据修复或自动 retry。收尾失败返回 `CREDENTIAL_CLEANUP_REQUIRED`，不以密文完成标记冒充产品成功。
- 不可由元数据构造的包内 `Completed` 仅在真实备份与角色回收均成功后产生，包含实际来源和实际加密结果。该对象目前只属于合成执行链，**不是个人 SQL 来源授权，也不会写 `Recovery/sources/.../source.dpapi`**。意图、普通 SHA 或调用者提供 `true` 不能绕过个人维护阻塞。

## 必要聚焦验证

新增一个 `WorkspaceSourceBackupIntegrationTest.sourceRoleIsReclaimedBeforeCompletedBackupCanBeReturned`，一次执行覆盖成功和失败路径：

1. 测试只读取本轮新建私有目录内的新 fixture；先核对 postmaster PID、data directory、端口和全新 initdb 的 3 库/1 非系统角色 inventory，再创建全新 B3 源库，迁移受信 Workspace V1/V2、植入一条合成 Memory 和 Finance sentinel。
2. 使用生产执行核心创建/授权角色，真实 pg_dump → v3 加密流 → 源身份核对 → 回收。核对角色不存在、源 snapshot 未变、Finance sentinel 保留；真实认证解密并通过 pg_restore TOC 检查，比较解密 manifest 文件与实际密文的摘要/字节数。临时明文只由现有 staging cleanup 清理。
3. 在同一测试专属源库植入 PENDING，再调用执行核心。实际快照校验拒绝，无密文完成标记，短期角色仍回收；PENDING 原样保留，没有为通过而修改源记录。两个调用的传入口令数组均被清零。

仅定向 `javac --release 21` 编译 3 个改动核心与该测试，使用已有缓存依赖；通过本地 JUnit Launcher 只运行这一项，不启动 Runtime/Spring。首次 **1 PASS / 0 failed/skipped，3641 ms**；补充管理超时并保留显式合成停写入参后，针对修改后的同一项再运行一次，最终 **1 PASS / 0 failed/skipped，3456 ms**。没有失败重试、全套测试、打包、前端或 Native Gate 实例验收。

仓库在 D: 的祖先 ACL 不满足既有维护 staging 规则，未修改或绕过规则。经限定执行审批，每次新建一个 `C:\PAW-SourceBackup-<随机ID>` 当前用户私有目录，核对祖先 ACL/无链接后运行独立 PG17：随机 loopback 端口、16 MB shared_buffers、12 max_connections；JVM 限两核/512 MB。每次结束均核对该实例的数据路径、端口、PID、进程映像和启动时间后仅停止该自建集群。两次均已停止，保留私有合成证据；没有启停既有 PostgreSQL/Ollama/KAG 或访问个人 Profile/Knowledge/数据库。

证据集中于 `.verification/recovery-slice2g/`：`source-backup-integration.log`、`javac-final.log`、`owned-fixture.json`、`fixture-result.json`；首次结果以 `first-*` 保留。`run-isolated-source-backup.ps1` 和 `RunSourceBackupTest.java` 为本地验证启动器，不是产品入口。真实执行摘要在各自新建根的 `source-backup-evidence.json`，明确 `personalSourceRegistration=false`。技术难点先查阅 GitHub 的 [PG17 DROP ROLE 依赖处理](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/commands/user.c) 和 [pg_dump](https://github.com/postgres/postgres/blob/master/src/bin/pg_dump/pg_dump.c)；未引入新的加密或签名协议。

## 下一步与明确限制

现在已有真正来自成功执行及角色回收的包内结果。下一步应把它接入受信原生维护子进程：当前受保护 Workspace 配置绑定实际源、与 2f 同 backupId/逻辑 ID/固定 root/data 身份一致、原生 owner 验证直属子进程的完整退出及有界结果后，才 force/无覆盖发布独立 DPAPI 来源记录。**不能加一个接收调用者 `SourceRegistration` 的通用写入 API，也不能把本合成方法的 `writersStopped` 用于个人授权或仅删 B3 限制。**

当前正常 Java Gate 的 runtime-admission 共享租约与维护 owner 的排他句柄存在真实接线问题，专门维护通道尚未实现；旧包/跨会话、外部 SQL、未知模型执行的完整停写证明仍缺失。不能靠普通 NativeDatabaseSetup 或启动 Runtime 绕过这一阻塞。成功来源登记、真实目标恢复、prepared-target、目标角色/凭据、READY、显式激活仍未完成；本轮没有个人备份可用、完整恢复、独立安全评审或 Production GO 结论。角色回收故障/断电中断的真实现场验收也未执行。

五份原有未跟踪文档摘要保持一致，未暂存/提交；记录见本轮 `preserved-before.json` / `preserved-after.json`。本轮新增执行核心仅本地提交，未合并 main 或部署。随后用户对先前指定推送给出新授权：仅对历史提交 `abe6530acc43a4027dfd5cace20e8c991fc3599b` 及公开仓库 `qianlixunbai/personal-ai-workspace` 的 `codex/recovery-v3-workspace-core` 分支重试一次。完整审查其 5 个代码/合成测试/交接文件，凭据模式扫描无匹配；远端原为其父提交 `1273610737aa121ea916cb308a1765d38b195139`。精确 SHA refspec、无强推/标签的单次推送成功，随后只读核验远端为 `abe6530`。比它新的开发提交均未推送；最终本地提交及清洁工作树状态见 `git-delivery.json`。
