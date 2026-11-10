# SQLite 来源 owner 与受保护阶段记录

2026-11-10，承接 `c3a6250e28e1a9789c4a9dbdb251f6f95eb4c24c`。本轮补齐可独立交付的来源文件停写 owner、完整合成备份和受保护阶段观察。**没有开通完整维护迁移、配置发布、配置恢复写入或激活。** 原生 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN` 保持不变。

## 已实现的边界

`SqliteSourceLease` 只接受真实 NativeGate 已接收的 `MaintenanceExclusion`，来源由该对象绑定的规范 Profile 决定，不接受调用者路径、固定真值、进程名单或记录代替授权。只支持配置尚不存在、没有 journal/WAL/SHM、业务快照校验通过的 SQLite 来源。现有来源文件、私有目录和原生身份必须通过检查；不修复原有 ACL。

owner 持有两份数据库和所有引用知识原文的 Windows 文件句柄，拒绝共享写入、删除；持有相关目录身份。已有写入句柄会导致获取失败；获取成功后，其他进程的普通文件写入、可写映射以及替换这些固定文件受到内核约束。每次边界检查重新验证句柄、身份、配置缺席、无旁路日志及文件摘要。关闭或底层 exclusion 失效后不能继续使用。该机制不是 PostgreSQL 来源冻结，也不证明模型已退出或所有历史运行实例均已停止。

规则依据为 Microsoft 的 [CreateFileW 共享访问说明](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)及 [Windows 文件打开文档源码](https://github.com/microsoftdocs/win32/blob/51c9b7cc54ef393b6406c8320a7840fdd88209d5/desktop-src/FileIO/creating-and-opening-files.md)。SQLite 仍使用普通只读连接；没有用 [immutable URI](https://www.sqlite.org/uri.html) 跳过锁或变更检测。

`SqliteMaintenanceOwner` 将来源句柄保持到完整备份、两域共同导入和结果观察结束。完整备份通过 SQLite backup API 及原文复制生成，使用新目录／新文件，源与备份按既有快照规则逐项核对；随后备份同样被 pin。失败产生的部分文件保留，不能原地覆盖重试。

注册目录 `evidence/sqlite-maintenance` 中的 `owner.lock` 提供跨进程排他。即使进程退出释放锁，只要已有任一操作记录，新 owner 也会拒绝开始；目前没有清理旧操作、取消后重新导入或自动接续的 API。不能通过换 operation ID 绕过未知结果。

阶段记录使用 CurrentUser DPAPI、Profile／operation／sequence 上下文、前条密文摘要链，限制大小与顺序。先 `CREATE_NEW` 写入 `.pending`、强制刷新，再无替换移动为 `.dpapi`，重读解密核对后保持文件 pin。共同导入增加包内回调，在目标写入前记录 `TARGET_PREPARED`、COMMIT 调用前记录 `COMMIT_ATTEMPTED`；回调失败会停止导入。阶段如下：

| 阶段 | 含义 |
| --- | --- |
| SOURCE_FROZEN / BACKUP_READY | 已取得本次活的来源句柄／完整备份已核对 |
| TARGET_PREPARED | 已绑定预期目标和两域摘要，尚不能声称提交 |
| COMMIT_ATTEMPTED | 下一步可能提交；中断时必须只读核对 |
| STOPPED_BEFORE_COMMIT | 共同 importer 未调用 COMMIT；不授权重试 |
| COMMIT_UNCONFIRMED | COMMIT 或其后确认失败，不能据 rollback 返回断言未提交 |
| TARGET_VERIFIED | 新连接核对共同数据和回执通过；仍不是配置激活 |

记录只用于观察，不能恢复活的内核证明或授权启动 Runtime。`inspect` 重新获取来源句柄，核对链、源／备份摘要和目标；未完成文件、改变的源或配置均保留，不重放导入。发现配置存在时既不解密，也不删除或覆盖。该模块实现的是恢复前的保全与判定，**没有实现配置恢复写入**。

## 一组必要真实闭环

构建证据在 `.verification/sqlite-maintenance-owner-20261110/`，成功验收在 `.verification/sqlite-maintenance-owner-20261110-r2/`。使用真实 NativeGate、原生准备的新合成 Profile、实际跨进程 DuplicateHandle、DPAPI、SQLite、独立 PostgreSQL 17.11。产品类从已审计候选 JAR 提取后运行，没有产品 classpath 覆盖或反射绕过 Gate。验收宿主及两个 Java 探针仅用于合成验证，不是新增产品启动入口。

| 必要检查 | 实际结果 |
| --- | --- |
| 父进程先持有来源数据库写句柄 | owner 获取拒绝；释放后才获取成功 |
| owner 持有期间，另一个进程打开两域库和知识原文写入 | 三项均被拒绝；可写映射也被拒绝；取得 owner 前及关闭后同一账号可正常打开写句柄 |
| 第二个真实 Gate 子进程并发创建 owner | 拒绝，无第二次导入 |
| 保持来源、备份和阶段 pin 执行一次共同导入 | 成功，实际提交后新连接数据／回执匹配，配置未生成 |
| 关闭 owner 后，新进程读取记录 | DPAPI 链和 TARGET_VERIFIED／目标匹配通过；重新 begin 仍拒绝 |
| 合成未完成下一条记录 | JOURNAL_UNCONFIRMED，原字节保留，无重放；仅验收程序移除自己创建的标记 |
| 后续合成配置 | CONFIGURATION_CHANGED_OR_UNREADABLE，原字节保留、未解密；仅验收程序移除自己的哨兵 |
| owner 关闭后保存一条新来源 Memory | SOURCE_CHANGED，新增数据再次打开仍存在，没有恢复覆盖或激活 |

成功日志包含 `OWNER_BUSY_REFUSED`、`CONCURRENT_OWNER_REFUSED`、`OWNER_IMPORTED`、`FRESH_PROCESS_RECORDS_MATCH_REENTRY_REFUSED` 及三个 `RECOVERY_*_PRESERVED`／拒绝标记。三个 Java 子进程均正常退出，强制退出数 0。没有重新跑既有两域提交／失败回滚／确认丢失完整矩阵、网页切换或正常 EXE 矩阵。本轮只实际覆盖 owner 的成功提交阶段；失败与确认丢失的导入语义承接上轮证据，本轮阶段映射经过代码审查，不能称故障断电验收。

- 成功合成 Profile：`cfbce7c5e65a420c98e61d63c0073984`。
- 操作 ID：`2b25fad5f60545b2a6020eccc7216cbe`。
- 独立 PostgreSQL：loopback `64996`，system identifier `7706515954773375064`；正常停止。
- 候选 Runtime SHA-256：`248E0D46D70F2601D8B65C7DE849E8FDEA769BBEB56C9BCDAC8E6C8CD03B48F9`。
- 使用已交付 JDK 和依赖限定编译 3 个生产类族，共 17 个 class；6 条沿用模式的 `ExtendedOpenOption` 内部 API 警告，无编译错误。不是完整 Maven 构建成功。
- run guardian `11584` 于 `2026-11-10T12:18:02.2413163Z` 收尾：Job 成员 0，共享锁释放，临时初始化密码删除，未留下配置；没有操作既有服务或查询系统端口。

## 中断、失败和保留现场

恢复开发前只读核查 Git、精确源文件及构建回执。HEAD 仍为 `c3a6250e28e1a9789c4a9dbdb251f6f95eb4c24c`；中断前的构建脚本、attempt／result 和 cluster 均不存在，确认那个未获执行回执的 Shell 未落盘后才首次构建。没有把“当前无工具调用”当作先前未执行的证明。

第一次新合成验收通过来源已有写入者拒绝、owner 和备份获取、原始写入拒绝后，因 PowerShell 将内存映射名称的 null 转为空字符串而停在验收参数错误。尚未向产品发送 IMPORT，未调用目标导入。仅自有等待子进程被结束，临时库正常停止、Job 0、锁释放。该现场保留。修正验收脚本为随机唯一映射名，第二份全新 Profile／目标库通过；产品代码没有因此改变或重编译。

来源 lease 的独立本地提交：`0893f525e8cd3a3cba5eba71448d4d58ffa837b6`。后续 owner 模块依赖该提交；回滚按依赖逆序处理。

## 尚未开通的具体动作

完整原生维护入口针对用户 Profile 的来源选择、配置发布／恢复写入与激活仍被设计边界阻塞，并非本轮新权限拒绝。当前 `MaintenanceExclusion` 同时阻止同 Profile Runtime 启动；需要实现可核实的原生所有权移交及启动核对，不能关闭 exclusion 后假称仍持有停写证明。还需稳定目标集群绑定及明确配置比较／恢复规则；现有目标身份只绑定同一服务启动实例，服务重启后保守返回变化。不能用已写入的记录替代这些能力。

原生用户确认仍保留，没有放宽 `UNREGISTERED_WRITERS_UNPROVEN`，没有自动迁移、个人恢复或正式发布。后续应设计上述移交／配置机制，再决定如何合并来源证明链。没有 ACL／EFS／沙箱／系统策略变更，旧拒读和服务访问边界继续保持；真实数据、默认 Profile／配置、持久凭据、main、六份用户文档和旧包均不动。本轮无推送。
