# MMF-3 Java Windows Private ACL Closure Candidate

2026-10-09（Asia/Shanghai）。**Candidate GO：交付最小 Java ACL 修订及隔离验证证据，供独立 Source Review。Java ACL Source Approval：PENDING。**

Native Bootstrap Production Integration：STOP；MMF-3 Execution / Runtime / GUI：STOP；Model Management Overall：NOT CLOSED。本报告的 GO 仅适用于 Java 候选交付，不是生产源码批准、可信加载集成批准或执行授权。

## 1. Git Reality

实际首先执行指定代理 fresh fetch，成功，无 direct fallback、全局 Git/proxy 修改。

| 项目 | 本轮实际结果 |
| --- | --- |
| Workspace | `D:/IDEA/Daima/personal-ai-workspace` |
| Branch | `codex/mmf3-runtime-private-acl-fix` |
| HEAD / fresh origin/main | `cb36879d93aa9a23c8b3d94e9fa26f5166a3c659` |
| Published Profile | 上述 commit；四文件源码保留 |
| Index | 入场及交付均 empty |
| Working tree | 入场已有未提交 Java ACL、Bootstrap、报告；交付保留，另有本轮四个 owning 文件修订和本文 |

`8dc66d81` 仅属于历史候选形成时的基线，绝非当前 HEAD。未 reset、clean、rebase、commit、push、merge、tag、release 或修改历史。未检查或修改 sibling 仓库。

适用祖先及仓库文件清单未发现磁盘 AGENTS.md；遵循用户提供的 AGENTS.md。已读取 current architecture、ADR-015、Profile contract、既有 ACL/semantics 报告及实际 owning code。历史文档状态不冒充本轮动态验收。

## 2. 现有 Java ACL 候选身份

以下历史补丁实际复核 SHA-256，一字节未改：

| 候选 | SHA-256 |
| --- | --- |
| [Java ACL 历史补丁](../../.verification/mmf3-windows-acl-candidate/source-review.patch) | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06` |
| [Bootstrap 历史补丁](../../.verification/mmf3-trusted-bootstrap-d07782a5ce0949ebba13528376a158b8/source-review.patch) | `279bc299f1bbe72723e649fa1ae34bb778457cb0962f0f194754a3f26265a549` |

入场源码逐文件 hash 与交付比较，本轮仅 §11 的四个既有 owning 文件改变；其余入场源码、Bootstrap 候选、已发布 Profile 四文件及旧报告全部保持。见 [preservation-check.json](../../.verification/mmf3-java-acl-closure/preservation-check.json)。

## 3. 本轮实际新增证据与测试加载条件

通过已发布脚本 `Prepare -OptIn` 建立全新 Profile run `374ae68c38aa4a33acf050a1e12c9ab9`。仅使用其随机合成目录，没有读取真实 token/个人内容、重启原失败 run、启动 Spring Boot、HTTP、GUI、Chrome、模型或 Ollama。

非管理员进程；Profile 根及专用 `java-test-load` 均 current SID owner、Protected=true、唯一 current SID FullControl/OI/CI。测试加载根通过 .NET 创建时 descriptor 原子建立，加载前核验实际 ACL。DLL 文件继承唯一 current SID FullControl，Protected=false；其安全边界是已核验 Protected owner-only 父目录，而非文件 Protected bit。

从本机固定版本 JAR 提取 native entry，记录 JAR/native SHA；显式指定 JVM/JNA temp、JNA boot library path、SQLite lib path，使用 `jna.nosys=true`、`jna.nounpack=true` 禁止系统搜索/默认解压 fallback。实际 `GetModuleHandleW` / `GetModuleFileNameW` 读取表明 jnidispatch.dll 与 sqlitejdbc.dll 均来自该专用目录；`jnidispatch.path` 也匹配。不是仅检查启动参数。

| Native library | 实际 SHA-256 |
| --- | --- |
| jnidispatch.dll（JNA 5.17.0） | `5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023` |
| sqlitejdbc.dll（SQLite JDBC 3.53.2.0） | `7d3281c7adbf3d2fe6b07462dee9cb310194c302154175f172ced3aac05c91c3` |

见 [fixture-security.json](../../.verification/mmf3-java-acl-closure/fixture-security.json)、[native-inputs.json](../../.verification/mmf3-java-acl-closure/native-inputs.json)、[实际 module 来源](../../.verification/mmf3-java-acl-closure/final-native-false-PERSIST.log)。绝对 fixture 路径仅保存在 ignored local manifest；报告与 token 诊断不发布私有绝对路径或 token。**这些条件只属于隔离测试，不提升为 Production Trusted。**

## 4. Handle / Path Identity

| 实际实验 | 结果 |
| --- | --- |
| 原 Java helper `NativeAcl.open` 目录句柄：READ_CONTROL/READ_ATTRIBUTES，share READ/WRITE | DELETE open=0、rename=0；metadata-only pin 被实际反证 |
| 相同 fixture 增加 FILE_LIST_DIRECTORY | DELETE open=32、rename=32（sharing violation）；关闭后 rename 成功 |
| 修订后 regression 调用实际 helper 的 directory open | DELETE open/rename 均为 32；CloseHandle 后 move 成功 |
| metadata 文件句柄持有期间 rename，原路径建立新文件 | rename 成功；原句柄对原路径的 `identity()` 检查拒绝 |

本轮将目录 desired access 从 `0x20080` 增为 `0x20081`，保留 share READ/WRITE、无 share DELETE、OPEN_REPARSE_POINT/BACKUP_SEMANTICS。LIST_DIRECTORY 需要实际列目录权限；本机完整 fixture 祖先链可用。其他机器若拒绝此权限则 fail closed，无 metadata-only fallback；未启用 privilege，也未列举祖先内容。

目录句柄仅在一次 helper 创建/验证内、由 Pins 反向关闭。文件创建/验证句柄仍只读取 metadata，**不能声称阻止文件 rename，也不能声称 helper 返回后的 Java 路径 I/O 仍持续 pinned**。源码注释已据实修订。

`identity()` 校验 type/reparse、handle final path，并要求 FileIdInfo 可查询；未保存跨操作 FileId 作连续身份比较。ModelStateStore 原有 READ/NOSHARE_DELETE/NOSHARE_WRITE 文件 pin、single-writer lock、identity/recovery 逻辑保持，未把短期 helper directory handle 当作长期替代。

当前边界仍是 OS account 与受核验 ancestor authority；不承诺抵御恶意同账号/admin 或已持有危险句柄。没有发现完成该既有边界需要持续跨进程 pin/IPC 的证据，未引入此类架构。

依据：[Microsoft CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)。sharing/rename 结论以本机动态实验为准，未泛化到所有 filesystem/filter。见 [原句柄实验](../../.verification/mmf3-java-acl-closure/baseline-pin-false-PERSIST.log)、[文件身份变更实验](../../.verification/mmf3-java-acl-closure/final-identity-false-PERSIST.log)。

## 5. Token / Browser / Memory / Knowledge / ModelState 权限与生命周期

| Owner | 本轮实际执行与结果 |
| --- | --- |
| LocalClientToken | 私有创建、初始化、既有 token 再初始化；final 为 Protected owner-only；阶段故障另见 §8 |
| BrowserClients | 创建/交换/撤销、持有 lock 时第二实例拒绝、关闭后重新打开；registry/lock Protected；pending 经实际保存/atomic move 流程 |
| PrivateMemoryDirectory / MemoryStore | DB 创建在 JDBC 写入前；真实 JDBC 初始化、synthetic row、关闭/重新打开；data 与 memory.db Protected |
| PrivateKnowledgeDirectory / KnowledgeStore | 目录/source/staging/lock 私有创建；synthetic source 写入/atomic move/再验证；真实 ingestion、DB/backup/重启流程 |
| ModelStateStore | selection commit、guard、并发第二 owner 拒绝、close/reinitialize 后 unresolved guard 保留；root/lock/selection/guard Protected |

综合回归通过 `GetSecurityInfo` 实际 descriptor 独立读取 Protection，并执行真实 owning admission；已有 protected directory verify 前后 descriptor bytes 相同。DB/staging 独立 native readback 还记录了 current owner、ACE type/flags/mask。不得将最终 descriptor 读取说成对每次瞬间创建/写入都做了独立抓拍。

**静态源码合同**：新 token/pending/DB/备份临时文件使用创建时 SECURITY_ATTRIBUTES；内容写入前再验证；atomic move 后保留/复核 ACL。已有不合格普通对象只拒绝、不调用 NIO setAcl 修复。所有未抓拍的瞬时阶段属于源码判断；本轮没有建立完整并发攻击矩阵。

## 6. SQLite sidecar 验证与最小合同修订

MemoryStore 与 KnowledgeStore 正式 owning code 均设 journal_mode=DELETE。应用预先私有创建主 DB；SQLite 自行创建 `*-journal`，在 WAL 模式下自行创建 `*-wal`、`*-shm`。

实验通过实际 owning constructor 获取 JDBC connection，仅在合成 fixture 内切换 PERSIST/WAL，以保留可观察 sidecar；没有改变生产 journal 配置。WAL 实验在提交合成事务后 halt 独立实验 JVM，随后以真实 owning constructor 重启。这个 halt 不涉及真实 Runtime 或原失败 run。

| 对象 | 创建者 | 实际 native descriptor |
| --- | --- | --- |
| memory.db / knowledge.db | 应用 helper，SQLite 写内容 | current owner；Protected=true；普通 Allow、flags=0、FullControl `0x1f01ff` |
| 两组 db-journal | SQLite | current owner；Protected=false；唯一 current SID Allow、INHERITED_ACE=16、FullControl |
| 两组 db-wal / db-shm | SQLite | 与 journal 相同：owner-only inherited、Protected=false |

**原候选实际失败**：两组 PERSIST journal 关闭后 admission 拒绝；两组异常退出遗留 WAL/SHM 的重启 admission 拒绝，均为 WorkspaceException。直接 ACL 读取证明这些文件并无 foreign Allow，而是严格 Protected 要求与 SQLite 继承行为冲突。

**修订后实际 PASS**：上述合成状态分别用真实 MemoryStore/KnowledgeStore 重新打开成功；综合 regression 对真实 PERSIST journal 重新 admission 成功，并断言 journal admission 前后 descriptor bytes 不变。

新增 `verifySqliteSidecar` 是明确的候选合同修订，只接受六个精确 basename。直接父目录必须在同一 helper 句柄链持有期间通过严格 current-only Protected OI/CI FullControl gate；文件必须普通磁盘文件、current owner、DACL present。若 sidecar Unprotected，只允许**唯一普通 current SID inherited FullControl ACE**；额外显式/foreign/未知 ACE 不接受。已 Protected 的 sidecar 仍须原普通私有文件严格 gate。

主 DB、token、registry、lock、source、staging、model state 没有获得这一例外。测试实际拒绝 unknown basename、额外显式 ACE、带 foreign read 的不合格 parent。没有放宽全部文件，没有修改 ACL、迁移历史对象或替 SQLite 创建这些 sidecar。

目录保护与严格检查过的 owner-only inheritance 支持本账号访问边界；不能仅因 child Protected=false 判为不安全，也不能只因名字像 sidecar 就给予 admission。[Microsoft ACE inheritance](https://learn.microsoft.com/en-us/windows/win32/secauthz/automatic-propagation-of-inheritable-aces)、[SQLite temporary files](https://www.sqlite.org/tempfiles.html)。

见本轮 [Memory journal 原拒绝及 descriptor](../../.verification/mmf3-java-acl-closure/baseline-sqlite-false-PERSIST.log)、[Knowledge journal 原拒绝](../../.verification/mmf3-java-acl-closure/baseline-sqlite-true-PERSIST.log)、[Memory WAL/SHM](../../.verification/mmf3-java-acl-closure/baseline-crash-false-WAL.log)、[Knowledge WAL/SHM](../../.verification/mmf3-java-acl-closure/baseline-crash-true-WAL.log)、[Memory WAL 修订后](../../.verification/mmf3-java-acl-closure/revised-restart-false-WAL.log)、[Knowledge WAL 修订后](../../.verification/mmf3-java-acl-closure/revised-restart-true-WAL.log)。PERSIST 修订后结果为同目录 `revised-restart-*-PERSIST.log`。热 rollback journal 的断电恢复、其他 SQLite 临时文件类型未执行。

## 7. Backup staging

MemoryBackupService 实际 restore 到全新目标及已合格私有空目标，重新打开后 logical rows 与原 backup 相同，恢复目录/main DB Protected。另通过既有 `restoreDatabase` builder 入口现场读取 staging directory、内容写入前的 DB descriptor，再执行发布 rename/重新打开，均 PASS。

KnowledgeBackup 复用既有非空 streaming export/validate/restore 集成测试，source/revisions 重新打开后保持。独立观察 probe 在 validation 输入读取时，只遍历自己全新 staging：实际 directory/file 均 current owner、Protected=true、唯一 current Allow FullControl；随后 restore 及真实 KnowledgeStore 重启通过，restored tree 逐对象 admission 成功，active staging 清空。

本轮未修改 MemoryBackupService/KnowledgeBackup 的已有候选源码；它们此前的私有创建修订保持。未发现本轮流程产生无法重新验证的 staging。没有据此声称任意中断、断电或恶意目标替换下的备份恢复均已验证。见 [live staging readback](../../.verification/mmf3-java-acl-closure/backup-staging.log)。

## 8. LocalClientToken fault injection

独立 ignored `TokenFaultProbe` 调用实际 constructor；Mockito 在测试 JVM 对指定调用点注入失败，保留真实私有文件创建/写入/cleanup。没有新增 production injection seam。

| 本轮实际注入 | 诊断及最终状态 |
| --- | --- |
| TEMP_FILE_CREATE / TEMP_FILE_PROTECT | 对应阶段 IO_FAILURE；final 不存在 |
| TOKEN_WRITE（先写 synthetic partial 再抛错） | TOKEN_WRITE/IO_FAILURE；无 final，正常 cleanup 删除 temp |
| ATOMIC_MOVE 前抛错 | ATOMIC_MOVE/IO_FAILURE；无 final |
| FINAL_FILE_VERIFY / TOKEN_READ | 对应阶段 IO_FAILURE；constructor 失败，final 仅可能是完整 private 43-byte 文件 |
| CLEANUP 唯一失败 | CLEANUP/IO_FAILURE；constructor 失败 |
| write 主异常 + cleanup 异常 | 保留 TOKEN_WRITE/IO_FAILURE；无 final；残留仅限私有 synthetic temp |
| move 实际完成后注入抛错 | ATOMIC_MOVE/IO_FAILURE；完整 private final 可能存在，但 constructor 必须失败，未认作成功 |
| 错误 token 读取内容 | TOKEN_READ/VALIDATION_FAILURE；无内容泄露 |
| 实际无 share DELETE 的 READ_DATA temp handle | 真实 Files.move 与 cleanup 因 sharing 拒绝；保留 ATOMIC_MOVE/IO_FAILURE，final 不存在，temp 私有 |
| NativeFailure(5) 注入 | TEMP_FILE_CREATE/WIN32_5；无 final；这是分类注入，不是实际权限设置失败 |

每个失败均核对固定完整错误字符串、cause=null、无 suppressed 原异常；原注入 message 含敏感 sentinel/路径/token 参数，也未进入最终异常。所有可能已提交的 final 均再次核验私有 ACL 和完整长度；未发布半成品 token，未把不确定提交视为本次成功。

TEMP_FILE_CREATE 包含 helper 的原子创建与内部验证；不能把此接口内部各 Win32 子调用均声称为独立 TEMP_FILE_PROTECT 定位。上述大部分为**确定性调用点故障注入**；实际 OS sharing 失败单独标明。ACCESS_DENIED/ALREADY_EXISTS/NOT_FOUND 的每个类别未另建矩阵，保持源码判断。

见 [token-fault-final.log](../../.verification/mmf3-java-acl-closure/token-fault-final.log)。**原历史 IOException 阶段/cause 仍 UNKNOWN；这些合成失败不是历史原因证据。**

## 9. Negative security tests

| 项目 | 本轮状态 |
| --- | --- |
| foreign Allow ACE | 实际 PASS：拒绝 protected foreign directory 且 descriptor bytes 不变；sidecar 不合格 parent 也拒绝 |
| Unprotected 普通 DACL | 实际 PASS：目录/普通文件 gate 拒绝，不修复；SQLite exact inheritance 例外另有独立限制 |
| Reparse / junction | 实际 PASS：全新 fixture junction 标记实际确认；helper 拒绝 |
| Unsafe ancestor | 实际 PASS：仅合成 ancestor 添加 Everyone DELETE；其下 protected child 验证拒绝 |
| 临时创建失败 | 调用点故障注入 PASS；Win32 5 分类注入 PASS；实际 OS 创建拒绝未单独执行 |
| Atomic move 失败 | 实际 sharing 失败与 move 前/后调用点注入均 PASS |
| 路径身份变化 | 实际 PASS：metadata 文件 rename/replacement 后旧 handle 与原路径不匹配，被拒绝 |
| 错误 owner | NOT PERFORMED：未启用 privilege/冒用其他账号/变更真实状态；EqualSid owner predicate 仅源码判断 |
| 生产权限设置失败 | NOT PERFORMED：production 不修复已有 ACL；未实际注入 SECURITY_ATTRIBUTES/native descriptor 创建失败 |

布置 unsafe ancestor 时 PowerShell Set-Acl 因 SeSecurityPrivilege 不可用失败；改用仅保存 Access 的既有 .NET API 后成功，没有启用 privilege。此 fixture 布置失败**不计为 production 权限设置负向测试 PASS**。见 [negative-fixtures.json](../../.verification/mmf3-java-acl-closure/negative-fixtures.json) 与 `negative-verify-*.log`。

## 10. POSIX 状态

0700 directory / 0600 file 既有分支保持，本轮新增 sidecar exception 只在 Windows gate 下调用；非 Windows 不初始化 NativeAcl。此为静态源码判断。

本机 `wsl --list --quiet` 返回不可用（exit 1），未使用 Linux 环境，未安装系统环境或改写非 Windows 实现。**POSIX smoke：NOT PERFORMED。**

## 11. 准确修改范围

以下路径相对 §1 Workspace 根。本轮 production diff 不为零，只有三个既有候选 owning 文件需要修订：

| 本轮改变的文件 | 准确范围 |
| --- | --- |
| `src/main/java/io/github/qianlixunbai/workspace/security/WindowsPrivateAcl.java` | directory LIST_DIRECTORY；据实标注 handle lifetime；六名 sidecar scoped read-only admission |
| `src/main/java/io/github/qianlixunbai/workspace/memory/PrivateMemoryDirectory.java` | 仅 prepare 的三个 SQLite sidecar 改调 scoped verifier；主 DB strict gate 不变 |
| `src/main/java/io/github/qianlixunbai/workspace/knowledge/PrivateKnowledgeDirectory.java` | 仅 prepare 的三个 SQLite sidecar 改调 scoped verifier；DB/lock strict gate 不变 |
| `src/test/java/io/github/qianlixunbai/workspace/security/WindowsPrivateAclTest.java` | 扩充原单一 integrated regression：SQLite journal/restart/scoped negatives、真实 helper sharing、token/browser reopen、Memory restore |
| `docs/engineering/MMF-3-JAVA-ACL-CLOSURE-CANDIDATE.md` | 新增本文 |

新增 ignored evidence/probes 全部在 `.verification/mmf3-java-acl-closure/`；不加入长期测试数量、patch 或生产入口。

完整候选 patch 除以上四个 source/test 文件，还包含原候选已有但**本轮字节未改**的 `pom.xml`、`security/LocalClientToken.java`、`security/BrowserClients.java`、`model/ModelStateStore.java`、`memory/MemoryBackupService.java`、`knowledge/KnowledgeBackup.java`（Java 短路径均在上述 package 根）。

已发布 Profile 四文件、Bootstrap、Java main、Launcher、Packaging、Browser、Finance、ADR-015、生产数据路径、schema/格式/auth/生命周期保持。未做无关重构、依赖升级或新增 production dependency。

## 12. 原始失败与修复结果

1. **历史事实**：NIO setAcl 解除 Protected DACL；既有候选使用创建时 native descriptor/verify-only。本轮回归再次验证 Protected 保持，不重新启动历史失败 run。
2. **本轮直接失败**：实际 metadata directory handle 无 rename pin。最小 LIST_DIRECTORY 修订后实际 helper regression PASS；关闭后的窗口未承诺解决。
3. **本轮直接失败**：真实 SQLite owner-only inherited sidecars 被严格 admission 错误拒绝。仅 sidecar scoped verifier 修订后真实 restart PASS；普通对象要求未放宽。
4. **本轮 harness 失败**：新负向用 NIO 写入相同 FullControl ACE，Windows 将其并入 inherited ACE，未建立预期异常输入，首次 focused 中该断言失败。改为额外显式 READ_DATA ACE 后 focused PASS；Knowledge backup 当次已 PASS，未因该 fixture 修复重跑。
5. **其他 harness 布置问题**：.NET private directory API 参数适配、probe token 查询方法、日志文件名中的分隔符均只修改 ignored probe；Set-Acl 问题见 §9。没有因此修改 production 边界。
6. **仍 UNKNOWN**：历史 LocalClientToken IOException 的具体阶段/cause/Win32 code。没有宣称本轮定位到历史根因。

原失败日志保留：[initial-focused-fixture-failure.log](../../.verification/mmf3-java-acl-closure/initial-focused-fixture-failure.log)。

## 13. Build / focused tests

**实际执行**：入场 `mvnw.cmd -q -DskipTests compile` exit 0；后续 focused Maven test 生命周期完成最终候选 production/test 编译；最后 `git diff --check` 与完整 patch 的 `git apply --reverse --check` 均 exit 0（只检查，未应用）。

使用 §3 私有 JVM/JNA/SQLite 参数，实际 focused 方法为：

- `WindowsPrivateAclTest.protectedBoundarySurvivesOwningFlowsAndRejectsUnsafeInput`：最终 PASS。
- `KnowledgeBackupTest.independentStreamingContainerRecoversExactSourcesAndRevisionsWithoutOriginalWorkspace`：本轮 PASS，来自最初 owning focused invocation；后续只重跑实际改变的 Windows regression。

首次组合命令中 Windows fixture 断言失败；修 fixture 后 owning regression PASS；最后因 source 加入 root-parent sidecar gate、补充 reopen/私有空目标 coverage，再执行该单一回归 PASS。未在报告修改后重复跑绿色 tests。

独立 javac/java probes 实际完成 baseline pin、真实 SQLite sidecar/PERSIST/WAL halt/restart、identity、junction/ancestor、module origin、token faults、live backup staging；它们是 disposable 验证，不伪装为长期 suite 或逐 coverage point 独立 acceptance executions。

见 [focused-results.json](../../.verification/mmf3-java-acl-closure/focused-results.json)、[最终 focused log](../../.verification/mmf3-java-acl-closure/focused-final.log)。Maven quiet log 为空不能单独作为测试 PASS；方法结果取自本轮实际 Surefire XML。

**有意未执行**：Java 全 suite、Desktop/Frontend/browser/IME/stress、包装矩阵、Bootstrap build/正式 helper CLI、真实 Runtime/GUI/Chrome/模型操作。历史 PASS 不计本轮执行。

## 14. 新完整候选 patch

[source-review.patch](../../.verification/mmf3-java-acl-closure/source-review.patch) 是以当前 `cb36879d93aa9a23c8b3d94e9fa26f5166a3c659` 为 base 的完整 Java ACL source/test 候选，56,179 bytes；包含全部八个 tracked 候选文件与两个新增候选文件，不包含本文、ignored evidence 或 Bootstrap。

[candidate-identity.json](../../.verification/mmf3-java-acl-closure/candidate-identity.json) 列出十个精确文件及逐文件 SHA-256。read-only reverse apply check 成功。历史两个补丁保持原样。

## 15. 新 SHA-256

```text
945fc7a9d5d216f5e6ee658fffa96ca5aff36769262bf43948ae5403e7a6f2d7
```

## 16. Remaining blockers / limits

- Java ACL Source Approval **PENDING**：须独立审查 LIST_DIRECTORY 权限需求与 scoped SQLite admission 合同修订。
- Trusted native production loading/launcher/lifecycle integration **STOP**：本轮隔离 loading 不能解决或证明产品入口 trust。
- 本机 NTFS fixture 的 sharing/权限证据未覆盖其他机器、filesystem/filter、历史危险 handles 或恶意同账号/admin。
- wrong-owner、实际 native SECURITY_ATTRIBUTES 设置失败、POSIX smoke、hot rollback journal 断电恢复 **NOT PERFORMED**；不伪造 PASS。
- 不合格旧普通状态仍 fail closed，无自动修复/迁移；未执行真实旧状态兼容测试。严格 sidecar gate 也拒绝任何不合格继承/parent。
- 原历史 IOException **UNKNOWN**；真实 Runtime/GUI/MMF-3 acceptance 尚未获授权。

## 17. Candidate GO / STOP

**Java Windows Private ACL Closure Candidate：GO for independent source review。** 实际证据支持两项最小修订；未超出既有 Java owning 范围。尚未执行的项目及边界如上，不以候选 GO 替代其证据。

**Java ACL Source Approval：PENDING。Native Bootstrap Production Integration：STOP。MMF-3 Runtime/GUI：STOP。Model Management Overall：NOT CLOSED。**

交付保留未提交候选与新 synthetic fixture；HEAD 不变、index empty。没有 Git publication、真实 Runtime 或模型操作。到此停止。
