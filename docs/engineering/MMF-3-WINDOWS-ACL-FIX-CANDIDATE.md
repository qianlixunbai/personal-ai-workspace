# MMF-3 Windows Private ACL Corrective Candidate

2026-10-09（Asia/Shanghai）。**Final: STOP。保留部分候选供 Architecture Guard 独立 Source Review；不是合格生产修复，不可发布。**

Windows 句柄权限实现与隔离集成回归已有实际证据，但依赖加载审查发现新的具体阻塞：本机默认 JNA 解压目录继承 foreign SID 的可写权限，JNA 在权限 helper 能验证对象之前就可能从这里加载 `jnidispatch`。最终通过的 fixture 显式使用另一个全新私有目录作为 JVM/JNA 临时根，不能以此声称默认生产加载路径安全。本轮不改全局目录 ACL、不扩大可信 SID、不改普通凭据路径、不设计额外 bootstrap/lifecycle；按 STOP 规则停止继续实施和测试。

## 1. Git Reality

两仓库实际首先执行指定代理 fresh fetch，均成功；未用 direct fallback，未改全局 Git 或代理设置。

| 仓库 | 分支 | HEAD / 本轮 fresh origin/main |
| --- | --- | --- |
| Workspace | `codex/mmf3-runtime-private-acl-fix` | `8dc66d81b78c3efac70feed94aec25ea71dd21a5` |
| Browser sibling | `main` | `267db794691d1a9ab1712b30b07cbeb57241808b` |

入场 index empty；唯一工作树变更是未提交的 `MMF-3-RUNTIME-ACL-ROOT-CAUSE.md`。原报告未覆盖、删除或改写，交付时 SHA-256 仍为 `135065b21308c8ad4359344838c1ee82559b484195c679b88aa662423639ed52`，原 `.verification/mmf3-runtime-acl/` evidence 未改。未操作原失败 run 或正式用户凭据。

适用仓库及祖先未找到磁盘 AGENTS.md；遵循用户本轮提供的 AGENTS.md 与明确任务授权。已读取相关 current architecture、Accepted ADR-015、原调查报告及实际 owning code。B1 current architecture 记载 SOURCE APPROVED / PUBLISHED，implementation `7405562a673ff0911427adaa0445e39e3d57afea`；这是文档/源码核对，不是本轮 Desktop 动态验收。

交付时 HEAD 不变、index empty；工作树包含本轮未提交源码/测试/报告及原未提交调查报告。Browser clean、无改动。没有 commit、push、merge、tag、release、reset、clean、rebase、amend 或 force push。

## 2. 原始报告事实与未证实事项

[原调查报告](MMF-3-RUNTIME-ACL-ROOT-CAUSE.md) 已复现 Java 21 Windows NIO `setAcl()` 解除 Protected DACL，且独立 token 初始化在 Protection 丢失时仍可成功。

原 LocalClientToken IOException 的准确阶段、cause、Win32 code 仍未知。此次脱敏错误分类只面向未来失败；没有恢复此前未保存的异常，没有把 ACL 故障与原 IOException 合并为一个已证明根因。

本輪测试生成的 token 仅属于独立随机 fixture，无预设 token、无正式 token 导入、无 HTTP 认证请求。

## 3. Win32 / JNA 技术选择及理由

部分候选使用版本锁定 JNA/JNA-platform 5.17.0。Windows-only `WindowsPrivateAcl` 负责创建与验证；非 Windows 门控在 native 实现类初始化之前。

已有对象采用 **verify-only**：读取实际 current effective token SID、owner、DACL/control bit；严格拒绝不符合合同的对象，不调用 Java `setAcl`，不自动迁移。不需要为本来正确的对象重新写 DACL。

新对象采用 `CreateDirectoryW` / `CreateFileW(CREATE_NEW)` 的 `SECURITY_ATTRIBUTES`：current SID owner、`D:P`、唯一 current SID Allow FullControl；目录 OI/CI，文件无继承 flags。随后重新打开/读取实际对象，验证 Protection，不以生成的 SDDL 或 Java ACL equality 当作成功证据。

所有权限读取经 `GetSecurityInfo(SE_FILE_OBJECT, OWNER|DACL)` 与 `GetSecurityDescriptorControl`。打开祖先和目标使用 OPEN_REPARSE_POINT、BACKUP_SEMANTICS、share READ/WRITE、无 share DELETE；权限操作期间固定对象链。核对 native reparse/type、`GetFinalPathNameByHandleW` 实际路径与 FileIdInfo 的可读性；FileIdInfo 查询不替代原 ModelStateStore identity/lock 合同。路径限默认 filesystem 的本地盘符路径；拒绝 UNC、ADS、尾随点/空格、链接和 reparse。native/path 异常不得 fallback 正常启动。

祖先额外拒绝 foreign SID 的删除/ACL 替换权限，避免在可替换祖先下创建私有子对象后继续使用。祖先允许 current SID、SYSTEM、Administrators、固定 TrustedInstaller service SID；这些是既有 privileged threat boundary，不是私有对象访问白名单。**私有对象仍仅接受 current SID。** 未向任何正式或全局目录添加权限。未启用 privilege。

句柄和 `GetSecurityInfo` / SDDL conversion 的 LocalAlloc 返回内存均有 close/LocalFree 路径。只读 owner/DACL，无 SACL 请求；仅新对象创建时指定 current owner，不改已有对象 owner/SACL。没有 NULL DACL、foreign ACE 复制、管理员修复、shell/icacls、JDK internal reflection 或自编译 DLL。

JNA-platform 5.17.0 已核对 Get/SetSecurityInfo 映射；production 不调用 SetSecurityInfo，因为已有对象不应改写。测试用 SetSecurityInfo 的 PROTECTED flag 只用于布置全新负向 fixture。三个窄补充映射是 `GetFinalPathNameByHandleW`、SDDL conversion、raw-pointer GetSecurityDescriptorControl，使用 Win32 stdcall、UTF-16 WString/char[]、32-bit BOOL/DWORD、16-bit control 与 pointer-by-reference。

依据：[Microsoft GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo)、[SetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-setsecurityinfo)、[CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)、[SDDL protected flag](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-string-format)、[SDDL conversion](https://learn.microsoft.com/en-us/windows/win32/api/sddl/nf-sddl-convertstringsecuritydescriptortosecuritydescriptorw)、[descriptor control API](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-getsecuritydescriptorcontrol)。

**没有最终合格的默认 Runtime 加载方案；技术候选尚未 GO。**

## 4. 新增依赖及许可证、来源与加载阻塞

| 坐标 | 版本 | 传递依赖 | 本轮选择许可证 |
| --- | --- | --- | --- |
| `net.java.dev.jna:jna` | 5.17.0 | 无 | Apache-2.0 |
| `net.java.dev.jna:jna-platform` | 5.17.0 | 同版本 jna | Apache-2.0 |

已核对本地 POM、实际 Maven dependency tree、上游固定 tag 源码/[许可证](https://raw.githubusercontent.com/java-native-access/jna/5.17.0/LICENSE)。上游提供 Apache-2.0 / LGPL-2.1-or-later 双许可，不引入第三个外部权限组件。

从 Maven Central 实际下载同版本 JAR，与本地缓存 SHA-256 一致：

- jna：`b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0`
- jna-platform：`b7e3d46c87bad2eb409b0e704916bcd81206168e357312dfddd0e253679cd9e0`

JNA 随 JAR 提供既有 jnidispatch native binary，无本轮编译 DLL。Windows 需要兼容 JVM/native architecture、系统 kernel32/advapi32、可加载的 jnidispatch；JNA 可通过 boot library path、系统查找或 classpath extraction 获取 binary。此候选没有新增启动参数、改变系统属性或修改 launcher 来绕过加载条件。

已审查固定版本 [Native.java](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/Native.java)：默认 Windows 临时根下的 `jna-<user-name-hash>`；文件创建/写入后 `System.load`，发生在 helper 的 native 权限验证之前。源码行为与本机该默认目录实际 ACL 元数据共同构成阻塞证据：Protected=false，foreign SID 有 inherited Modify / FullControl。未做恶意 DLL 替换试验，也未声称复现了 DLL 注入；默认生产加载安全性未获证明。

最终绿色 fixture 显式 `-Djna.tmpdir=<全新私有测试根>`；Surefire 记录的 `jnidispatch.path` 父目录与该隔离配置相同。这只证明该测试加载条件可用；不是生产修复。本候选还没有加载前的可信 bootstrap/加载 gate，不能宣称 target ACL fail-closed 已解决 native 加载前的信任问题。

## 5. 完整生产文件修改范围

| 文件 | 实际修改 |
| --- | --- |
| `pom.xml` | 两个同版本锁定 JNA 坐标 |
| `security/WindowsPrivateAcl.java`（新增） | Windows-only 窄 native 创建/权限/对象身份 helper |
| `security/LocalClientToken.java` | Windows 私有创建/verify-only、reparse 拒绝、脱敏初始化阶段 |
| `security/BrowserClients.java` | 相同 helper 创建目录/lock/pending；existing pending 与 atomic final 权限验证 |
| `memory/PrivateMemoryDirectory.java` | Windows 私有目录/DB 创建和既有对象严格验证；保留 POSIX modes |
| `knowledge/PrivateKnowledgeDirectory.java` | Windows 私有目录/文件创建与验证；保留 POSIX modes |
| `model/ModelStateStore.java` | 仅 Windows 创建/权限验证接入；selection/guard/validation/lock 算法未改 |
| `memory/MemoryBackupService.java` | Windows staging 使用新随机名与私有创建，避免随后 protect 拒绝自己刚建的 unprotected staging |
| `knowledge/KnowledgeBackup.java` | staging 使用既有 owning directory 入口进行私有创建 |

后三个文件由实际 owning 调用链确认必要；没有扩展模型管理或备份格式。没有修改其他 Memory/Knowledge 内容逻辑、AI API、Browser、Desktop、React、Finance、正式路径/配置、认证格式、DB/schema、STATUS/current architecture/roadmap/ADR-015。

## 6. Windows Protected DACL 验证

最终实际执行的综合 Windows regression 使用全新随机 fixture，覆盖：

- 已有 protected credentials 经过 restrict 后 native security descriptor bytes 完全不变。
- 新 token、Browser registry/lock、Memory DB、Knowledge directories/source/lock、ModelState root/lock/selection/guard 的真实 descriptor Protected + DACL_PRESENT 为 true。
- 新 Knowledge staging file 写入非个人 synthetic 内容前验证 Protection；同目录 atomic move 后验证 target。
- unprotected 目录被拒绝，拒绝前后 descriptor bytes 不变。
- Protected=true 但带 foreign Allow ACE 的目录被拒绝且 descriptor 不变。
- foreign parent 的 inheritable read ACE 不进入新私有子目录；其创建与 native Protection readback 成功。

长期 regression 直接读取 GetSecurityInfo 返回的实际 SECURITY_DESCRIPTOR_RELATIVE control bytes，独立于 helper 的 control API 分支；不是 Java getAcl 列表 equality。production helper 仍通过 GetSecurityDescriptorControl 验证。未将一次集成流程的覆盖点计成多次独立执行。

**目录权限创建拒绝故障、junction/reparse 负向动态试验尚未执行；不标 PASS。**

## 7. Current SID / foreign ACE 验证

production SID 来自 thread token（仅 ERROR_NO_TOKEN 才取 process token），不是用文件 owner 作为可信身份。新 owner/Allow 以实际 SID 构造，读取后 EqualSid 核对；其他 principal、非普通 Allow、inherit-only/inherited/未知 flags、非 file FullControl 范围 mask 均拒绝。目录必须有 current SID 可继承 FullControl。

综合 fixture 实际通过 current-account owning flows、foreign Allow 拒绝和 foreign inheritance 隔离。执行前 PowerShell WindowsPrincipal 检查拒绝 elevated 身份；本轮未以管理员运行。

默认 Temp 祖先含 foreign replacement authority 时，候选实际拒绝；随后仅换用允许的新独立 fixture，不修改这些祖先。**Owner mismatch 单独动态 case 未执行**；其 native 检查已实现，不能把源码审查当成该负向测试 PASS。

## 8. 临时文件与原子写入安全

Windows token temporary 是 UUID 同目录名，CREATE_NEW 与 private protected descriptor 在同一个 native 创建调用中生效；尚未写入 token bytes 即进行实际验证。之后写 token、ATOMIC_MOVE、final 文件权限/结构检查。Browser pending、lock 与 ModelState pending/lock 采用同一创建边界；Knowledge source/staging/lock 经 owning 入口创建。Memory/Knowledge backup staging 也改用私有创建。

本轮已验证正常 token 初始化（43-byte 结构，无内容输出）、Browser 保存/撤销/重启、Knowledge synthetic source atomic move、ModelState selection/guard 的正常持久化与 restart guard。没有注入 I/O failure；不声称已经证明所有失败后无不确定 token 的合同。

SQLite 自行产生的短期 journal/WAL/SHM 仍依赖目录的 owner-only inheritance，不承诺它们均带 Protected bit。本轮未启动 JDBC 流程，未动态捕获这些 sidecar；异常遗留的 unprotected 文件会被新 prepare 严格拒绝，不能静默迁移。这是需 Guard 评估的兼容/恢复风险，不是已通过的安全验收。

句柄 pin 仅覆盖 helper 的 native 对象创建/检查；不声称把所有后续 Java I/O 改成 handle-only 操作或防御恶意同账号/admin。祖先 replacement check 与原账户边界必须一并审查。

## 9. LocalClientToken 错误诊断设计

固定阶段：DIRECTORY_PREPARE、ACL_READ_OR_PROTECT、TEMP_FILE_CREATE、TEMP_FILE_PROTECT、TOKEN_WRITE、ATOMIC_MOVE、FINAL_FILE_VERIFY、TOKEN_READ、CLEANUP。

只发布固定阶段与白名单类别：WIN32_<unsigned numeric code>、ACCESS_DENIED、ALREADY_EXISTS、NOT_FOUND、IO_FAILURE、VALIDATION_FAILURE。对 native library/interop RuntimeException/LinkageError 删去原始 message；最终 IllegalStateException 不携带原始 cause。

临时文件 cleanup 若是唯一失败则标 CLEANUP；已有 write/move 等主失败时保留主阶段，初始化仍失败，禁止 cleanup 掩盖原始定位。没有 token、pairing secret、领域内容、账户名或私有路径写入初始化异常。没有改 token 格式或正常路径。

**阶段故障注入尚未执行。** 这些是源码设计，不是已验证阶段定位；原始 IOException 原因依旧未知。

## 10. 实际 focused test 结果

实际必要编译：`mvnw.cmd -q -DskipTests compile` 成功。Maven dependency tree 实际执行成功，仅上述两个锁定 JNA artifacts。

最终 focused 命令：

```text
mvnw.cmd -q -Dtest=WindowsPrivateAclTest,BrowserClientsTest#credentialAndRevocationSurviveRestartButPairingDoesNot+privatePermissionsAndExclusiveRegistryLock "-DargLine=-Djava.io.tmpdir=<全新私有fixture根> -Djna.tmpdir=<同一fixture根>" test
```

最终 Surefire 结果 failure/error/skipped 均为零，实际执行的方法为：

- 新增单一长期 integration regression：`WindowsPrivateAclTest.protectedBoundarySurvivesOwningFlowsAndRejectsUnsafeInput`。
- 复用 `BrowserClientsTest.credentialAndRevocationSurviveRestartButPairingDoesNot`。
- 复用 `BrowserClientsTest.privatePermissionsAndExclusiveRegistryLock`。

没有新增重复 DTO/UI/getter 测试。没有运行整个 Java/Browser/Desktop suite。模型部分仅 ModelStateStore 文件系统操作，无 provider/model load/inference/API/server。

证据：[脱敏 focused 方法结果](../../.verification/mmf3-windows-acl-candidate/focused-results.json)、[最终构建日志](../../.verification/mmf3-windows-acl-candidate/focused-windows.log)、[dependency tree](../../.verification/mmf3-windows-acl-candidate/dependency-tree.txt)。最终日志为空是 Maven quiet 成功退出；具体实际执行证据来自 Surefire 方法结果，不能仅以空日志当作 PASS。

## 11. 初次失败及修复记录

1. Maven Central `.jar.sha256` 请求返回 HTTP 404，首次审计脚本把缺失元数据误报为 mismatch；不存在已确认的 JAR 字节 mismatch。改为实际下载固定版本 JAR，与缓存 SHA-256 比较一致，才加入依赖。
2. 首次 Windows integration 的正向流程已走完，但负向 fixture 用 Java name lookup 查询 SID 文本 `S-1-1-0`，出现 UserPrincipalNotFoundException。仅修测试 fixture，先通过 Win32 解析 Everyone 的账户名称；并令 foreign read ACE 可继承。改后 focused 命令成功。[初次记录](../../.verification/mmf3-windows-acl-candidate/initial-fixture-failure.log)。
3. 新增祖先 replacement-authority 检查后，默认 Temp fixture 实际被拒绝。只读 ACL 证实 foreign 可写授权；不放宽检查、不修全局 ACL。换用用户主目录下全新随机、current SID owner、protected owner-only fixture，显式设测试 JVM/JNA temp，最终 focused 通过。[拒绝记录](../../.verification/mmf3-windows-acl-candidate/unsafe-default-temp-failure.log)。
4. 随后核对依赖 native extraction 条件与默认 JNA 目录实际 ACL，确认 §4 加载阻塞；停止继续源码、故障注入与负向测试。没有为了达到检查清单启动 Runtime 或改生产路径。

原失败 run、原报告 evidence 未删除；没有以旧 PASS 充当本轮结果。成功测试必要重跑的原因分别是 fixture 修复与实际 security predicate 改变；没有在报告修改后重复执行绿色测试。

## 12. Windows / POSIX 兼容性

Windows 实际环境为 JDK 21.0.7、Windows 11，本轮成功 fixture 使用版本锁定 JNA、隔离 private temp 与非 elevated 进程。证明了该 fixture 的 native API 能力；默认 Runtime 加载仍 STOP。

POSIX permissions 分支仍用既有 0700 directory / 0600 file、原账户检查；新 native helper 不在非 Windows gate 后初始化。仅新增 classpath artifacts，不声称它们在所有平台加载 native。

**本机无已安装 WSL distribution；未安装新环境。Linux/macOS 实际启动、POSIX modes 和 non-Windows native-loading smoke 均未执行。** POSIX 保持仅有源码审查证据，不能写成动态 PASS。

旧 credentials/data/Knowledge/ModelState 中的 unprotected、foreign owner/ACE 或不符合目录继承合同的状态会 fail closed。没有自动迁移历史 token/registry/DB/selection/guard/lock，没有自动删除 pending/journal。严格检查可使旧状态拒绝启动；不得当作历史状态坏了或应删除数据。兼容性与受审迁移路径仍待决定。

## 13. Runtime / 桌面隔离回归证据

静态 Git diff：Desktop、Browser sibling、React、Finance、Runtime APIs、正式配置、STATUS/current architecture/roadmap/ADR-015 未修改。B1 `Mmf3AcceptanceLaunch.ValidatePrivateDirectory` 仍要求 current SID owner、Protected DACL、current-only Allow 与 FullControl；reparse/launch gate 未放宽。

ModelStateStore selection/guard/validation-required parse、CAS、pending/recovery、NOSHARE pins、single-writer lock 与 lifecycle 逻辑未改；仅 Windows 创建和权限验证接入。综合 fixture 实际验证 single writer、selection restart、restart unresolved guard 与 clearGuard 拒绝；没有据此宣称全部 marker/recovery contract 动态通过。

没有 Spring context、完整 Runtime、Desktop、Chrome、真实模型、Ollama 服务或管理请求。没有模型 A→B、load/inference/Release/Unload/Recovery。本轮不执行实际 MMF-3 GUI acceptance。

## 14. 未执行测试

未执行：token write/move/cleanup I/O fault injection 与阶段定位；目录权限写入失败负向；owner mismatch 独立负向；junction/reparse 独立动态负向；JDBC/SQLite sidecar；两个 backup owning flow 的集成回归；Linux/macOS/POSIX 与 non-Windows native-loading smoke；完整 ModelState marker/recovery suite；Desktop B1 动态 gate、Runtime/GUI/Chrome/模型操作；full Java/Desktop/Frontend/Browser/IME/stress/packaging/release matrices。

其中关键 security/diagnostic 验证因具体依赖加载阻塞而中止，不视为 optional PASS，不把计划覆盖或源码实现当作执行结果。

## 15. Full Source Review Patch

[完整部分候选 source-review.patch](../../.verification/mmf3-windows-acl-candidate/source-review.patch) 包含 §5 全部生产/配置修改、新 WindowsPrivateAcl 类，以及新增 WindowsPrivateAclTest 的全部内容。旧 BrowserClientsTest 未修改。补丁针对指定 Workspace HEAD，包含 untracked 新类/测试，未 stage。

已实际 `git diff --check` 和 `git apply --reverse --check source-review.patch` 成功；后者仅检查完整补丁与现工作树内容一致，没有实际应用/撤销补丁。报告为独立文档，避免把报告自身的补丁 hash 放进同一补丁造成循环。

原调查报告不属于本轮 source patch；其内容和 hash 完整保留。[证据 SHA 清单](../../.verification/mmf3-windows-acl-candidate/evidence-sha256.json)、[默认 native-loader ACL 元数据](../../.verification/mmf3-windows-acl-candidate/native-loader-boundary.json) 供审查；只含 metadata，不含 token/私有绝对 fixture 根。

本地工具定位文件、Surefire 原始 XML 的 JVM 私有路径属性、下载的 JAR 和上游源码缓存不是分享报告/补丁内容。没有把这些路径属性转存到脱敏结果。

## 16. Patch SHA-256

`source-review.patch`：**`8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06`**。

该 hash 标识实际部分候选源码，不表示安全验收通过、Architecture Guard 批准或可发布。

## 17. Remaining Blockers

1. **默认 JNA native 加载安全未成立。** 本机 extraction 目录 foreign 可写，加载在 helper 验证之前；当前部分源码没有可信 bootstrap/load gate。需 Guard 决定受审、版本锁定、private-before-load 的加载安排及其生命周期，不能自动改全局 ACL、改变普通凭据路径、添加新的信任主体或临时 shell 修复。
2. **默认私有状态位置祖先权限兼容性。** 本机 LocalAppData/Temp 祖先存在 foreign replacement authority，新检查会拒绝。未改变这些历史状态；隔离测试根不构成默认生产路径通过证据。不得为通过测试放宽 B1 或私有对象边界。
3. **关键未执行验证。** §14 的 I/O/诊断、权限失败、owner/reparse、POSIX、SQLite sidecar、backup 流程尚无实际通过证据；加载阻塞解除前不继续完整安全验收。
4. **旧生产状态兼容/恢复。** 严格 protected 检查会拒绝已被 NIO 解除 Protection 的旧目录/文件及某些遗留 SQLite sidecar；无自动迁移。需独立评估、明确授权，不能以本候选修改用户历史状态。
5. **原 token IOException 未定位。** 新分类不能恢复旧 cause；无故障注入或重启原现场来掩盖此未知事项。

## 18. Candidate GO / STOP

**Candidate：STOP。Native 对象 API 已在隔离 fixture 证明部分合同；默认加载信任与必要验收未完成。**

本轮提交原样可复核的部分 patch、绿色 focused evidence、失败记录与具体阻塞，供 Architecture Guard 独立 Source Review / 后续加载方案裁定。不请求发布、不宣称已有合格生产修复。不继续 Runtime/GUI 或模型执行，不 commit/push/merge/tag。

**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**
