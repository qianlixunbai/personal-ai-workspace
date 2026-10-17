# MMF-3 — Trusted JNA Native Loading Architecture Decision

2026-10-09（Asia/Shanghai）。**研究交付；推荐方案 A，待 Architecture Guard 批准。生产修复实施 / 发布：STOP。**

**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**

本轮只增加此决策报告及 ignored 的独立研究脚本/证据。没有修改生产代码、长期测试、正式启动入口或 ADR-015；没有启动 Workspace Runtime、Desktop、Chrome、Spring context 或 Ollama，没有初始化真实凭据/数据，没有访问旧失败 run。原 Windows ACL 部分候选继续保持 STOP。

证据根：`.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/`，下文链接指向本轮实际证据。路径以角色表示；current/account-specific SID 使用稳定别名，well-known/capability SID 保留。没有把账号名、私人绝对路径、token 或用户内容写入交付报告与保存的结果 JSON。fixture 不属于生产目录，不自动删除任何旧现场。

## 1. Git Reality

首先实际执行 Git state gate，并以指定单次代理配置执行 `git fetch --prune origin`，成功；未使用 direct fallback，未修改全局 Git / 系统代理。

| 项目 | 本轮实际结果 |
| --- | --- |
| 分支 | `codex/mmf3-runtime-private-acl-fix` |
| HEAD | `8dc66d81b78c3efac70feed94aec25ea71dd21a5` |
| fresh `origin/main` | 同 HEAD |
| index | empty |
| 入场生产/config diff | 保留原 8 个 tracked 修改与 untracked `WindowsPrivateAcl.java` |
| 入场其他候选 | 保留 `WindowsPrivateAclTest.java`、Windows ACL 候选报告、Runtime ACL 根因报告 |
| 指定完整候选 patch SHA-256 | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06`，实际复算一致 |

已有 patch 包含 untracked 源码/测试，不能用单独 `git diff` hash 替代其身份。入场 tracked diff hash 为 `4b91ed865a19378de7457887d827434f99f09cd6063cdb33795e71bfacb8eb0f`。本轮对入场 12 个非 ignored 修改/新增文件保存逐文件 hash；交付检查与基线逐字节一致。实际 `git diff --check` 与 `git apply --reverse --check source-review.patch` 成功；后者只检查，没有应用或撤销补丁。

适用祖先/仓库未发现磁盘 AGENTS.md；遵循用户给出的 AGENTS.md 和本轮限定授权。读取了相关 current architecture、ADR-015、两份已有候选/调查报告及 owning code。本轮只审计本仓库，不重做无关 sibling/Finance 审计。

HEAD / 分支未改变；新增本报告为 untracked。工作树因必须保留的候选与本报告保持 dirty，index empty。没有 reset、clean、覆盖候选、commit、push、merge、tag、release、amend 或 rebase。所有写入限定本轮新 evidence、全新 fixture 与本报告；已有 evidence 和原失败 run 没有写入。

证据：[入场文件基线](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/candidate-baseline.json)、[交付保留检查](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/preservation-check.json)。

## 2. JNA 5.17.0 准确加载顺序

行为依据仅为固定 tag **5.17.0** 的真实 [Native.java](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/Native.java)、[Platform.java](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/Platform.java) 与同版缓存 JAR 的隔离运行。保存源码快照及 SHA-256，不以当前 master、其他版本或类注释代替实际分支。

Windows 的 `Native` 静态初始化首先进入 `loadNativeDispatchLibrary()`，随后才执行 native ABI 兼容检查和其他初始化。精确顺序：

1. **先做临时清理**：`jna.nounpack` 非 true 时，调用 `removeTemporaryFiles()`。该调用先取得/尝试创建 JNA temp root，再处理 `jna*.x` 和对应临时库。IOException 仅警告，继续加载。即使最后从 boot path 成功，默认也可能先触及 temp；方案 A 应同时设置 nounpack。
2. **boot path**：读取 `jna.boot.library.name`，默认 `jnidispatch`；按 `jna.boot.library.path` 的平台分隔符逐目录遍历，映射 Windows 文件名为 `jnidispatch.dll`。存在时设置 `jnidispatch.path`，然后按绝对路径 `System.load`。成功则返回；`UnsatisfiedLinkError` 被吞掉，继续下一目录/后续分支。指定目录不是天然的 fail-closed gate。
3. **system branch（有条件）**：实际实现读取 `jna.nosys` 时默认值为 **`"true"`**。Windows 只有明确设为 false（或其他解析为 false 的值）才尝试 `System.loadLibrary(libName)`；失败继续。Android 有独立例外，与本轮 Windows 无关。类开头概述没有反映这一默认值，不能照概述声称 Windows 默认必经系统搜索。
4. **classpath branch**：`jna.noclasspath` 非 true 才进入；否则直接抛 `UnsatisfiedLinkError`。资源名使用固定 **`jnidispatch`**，不采用自定义 boot library name。Windows x64 首先找 `com/sun/jna/win32-x86-64/jnidispatch.dll`，找不到时还可找 classpath 根的映射库名。
5. **资源为 `file:`**：直接使用已有文件。**nounpack=true 不禁止此分支**。
6. **资源非 `file:`（包括 JAR 资源）**：nounpack 非 true 才用 `File.createTempFile`、普通输出流复制到临时 DLL，然后 `System.load`。nounpack=true 时不解压，最终 native support 加载失败。这里没有本应用 owner/Protected/ancestor/hash 证明，也没有应用可插入的加载前 ACL 回调。
7. **成功后的处置/版本检查**：解压库默认尝试删除；Windows 可能删除失败并生成清理 marker。`jnidispatch.preserve` 可保留。随后 `getNativeVersion()` 的检查只要求 native major 相同、native minor 不低于预期；它不证明 artifact 恰好来自 JNA 5.17.0，更不证明来源/哈希。

源码定位：`Native.java` L220–259、L939–1056、L1091–1192、L1317–1379，均以保存快照的原始行号为准。

| 属性 | 5.17.0 的真实含义 / 限制 |
| --- | --- |
| `jna.boot.library.path` | 优先目录列表；未找到/不能加载会继续；应限制为一个已经验证的绝对目录 |
| `jna.nosys` | Windows 默认 true；仍必须显式锁定 true，拒绝外部覆盖 |
| `jna.noclasspath` | true 禁止整个 dispatch classpath 分支，包括现成 file URL |
| `jna.nounpack` | true 禁止自动解压与加载前 temp cleanup；不是 classpath 禁止开关 |
| `jna.boot.library.name` | 影响 boot/system 名字；不改变 classpath dispatch 资源名；推荐固定默认名字 |
| `jna.tmpdir` | 显式指定时直接 mkdirs，然后检查 exists/canWrite；此分支不另选默认 temp，但没有安全 ACL/身份验证 |
| `jnidispatch.path` | 由 loader 在尝试 boot/classpath 文件前写入的状态值；不是加载配置，失败后也可能残留；system 成功不一定设置 |

未显式指定 jna.tmpdir 的 Windows 分支：先尝试 `java.io.tmpdir/jna-<user.name 的 Java hash>`，若不存在/不可写，**回退 `java.io.tmpdir` 本身**。Linux/BSD/AIX 等走 XDG cache / home cache 分支，macOS 走 home Library cache；默认候选目录不可用也存在 fallback。显式坏 tmp root 可产生清理警告；若仍需 extraction，则失败，不自动改用另一个根。

## 3. 默认加载风险

默认 JAR 提供的 DLL 字节有可信来源，并不能证明写到默认 temp 后仍是同一对象。创建临时文件、打开写流、关闭流、按路径加载之间没有 Protected DACL 创建或本应用的身份/祖先 pin；可写目录/文件允许其他授权 SID 修改字节、修改 ACL 或替换路径。应用第一次调用 ACL helper 时，`Kernel32.INSTANCE` 等已要求 JNA native 初始化，故“加载后再检查 temp”存在循环信任问题。

本机对默认 JNA **目录本身**只读检查：owner=current SID，Protected=false；其他账户继承 Modify，capability SID 继承 FullControl。没有扫描其 DLL、调用默认 extraction、删除旧 marker 或进行攻击替换；风险判断来自真实 ACL + 固定源码，不是注入动态复现。

`jnidispatch.path` 和 `jna.loaded` 都是进程可改的系统属性。实验还显示无效 boot DLL 失败后 path property 仍指向 invalid role；system 成功时 property 可以 unset。它们只能辅助诊断，不能单独作为实际 native 来源证明。

仅设置 tmpdir、boot path、nounpack 或加载后 ABI 检查，任何一个都不足以完成可信加载合同。

## 4. 方案 A / B 比较

| 维度 | A：显式受信任独立 DLL | B：受控 JNA 临时根 |
| --- | --- | --- |
| 加载对象 | 安装/准备阶段从固定 artifact 取出、独立固定 hash 的 DLL | 启动时由 JNA 从 JAR/classpath 复制新 DLL |
| native 之前的信任 | 独立于 JNA 的准备与验证入口 | 同样需要独立 bootstrap，不能由 WindowsPrivateAcl 自举 |
| Protected 合同 | 可在安装创建时给目录/文件显式 Protected DACL，启动 verify-only | 根可 Protected；默认 extraction 文件实测继承 ACL、Protected=false，缺少文件加载前验证点 |
| 防回退 | 单一 boot path + nosys/noclasspath/nounpack=true | 禁 system、限制 classpath/resource provenance、显式 tmp root；仍保留 extraction 生命周期 |
| 文件丢失/不合法 | verifier STOP；JNA 无后备来源 | extraction 可自动创建对象；若要求文件在加载前 Protected/固定身份，现成机制不足 |
| 更新/清理 | 明确版本目录、离线 provision、显式卸载/旧版本清理 | 额外随机 DLL/marker，启动时清理、锁占用、失败残留 |
| 普通用户 | 安全 home 祖先下应用专用根，可无需管理员 | 可建立根，但这只解决根，不证明解压文件与加载窗口合同 |
| 架构成本 | 包装 sidecar、可信 provision/verify 入口、Java 早期 gate | 至少同等 bootstrap，严格文件合同还需 extraction hook、受审 native/loader 改造或重新采用 A |

**B 不是已证实完整方案。** 本轮 .NET Framework 创建独立 private root 成功，证明有不依赖 JNA 的目录准备能力；没有实现生产 helper、installer、native bootstrap 或 JNA fork。JNA 本身不提供让应用在自动 `System.load` 前验证该新 DLL 的回调。若允许 native DLL 只继承 owner-only DACL而不要求 Protected，需 Guard 明确界定 native 对象合同；本轮不自行降低已有合同。即使只对 temp 根要求 Protected，仍欠跨启动入口的验证、目录 pin、classpath provenance 和替换窗口证明。

## 5. 推荐方案与理由

**优先推荐 A；不是批准实施。** 将 `jna-5.17.0.jar` 中 Windows x64 的固定 DLL 在离线准备阶段安装到独立应用目录，例如符号路径 `%USERPROFILE%/<APP_PRIVATE_NATIVE_ROOT>/jna-5.17.0/win32-x86-64/`。该建议与 credentials/data/model-state 分离，实际目录名字/配置需批准，不改现有生产路径。

每次启动在任何 JNA 类初始化之前，由独立可信 verifier 检查 current effective SID、完整祖先链、无 reparse/UNC/ADS、目录/文件 owner 与 Protected DACL、文件身份/架构与固定 hash。仅允许已存在且合法的已安装对象；不存在、身份异常或检查失败直接 STOP，日常启动不修 ACL、不下载、不重新解压安装。

锁定启动属性：

```text
-Djna.boot.library.path=<单一已验证绝对目录>
-Djna.boot.library.name=jnidispatch
-Djna.nosys=true
-Djna.noclasspath=true
-Djna.nounpack=true
```

属性不能代替 verifier。启动入口应拒绝冲突/多目录/相对路径配置，不允许用户可控 Spring 属性或 Java 参数把信任目录切换到任意位置。加载失败终止进程；不能 catch 后继续 Runtime。dispatch 加载成功后可核对实际来源作诊断，但预加载证明必须已经完成。

安全祖先与新对象原子私有创建使 foreign SID 在 hash→load 间没有替换权；如设计还要求句柄连续 pin / loaded-file identity attestation，则需要 verifier 保持句柄或受审握手。威胁边界仍按现候选：不承诺防恶意同账号、SYSTEM/管理员。不可把校验 hash 本身当作 TOCTOU 防护。

选择 A 的原因是：可删除 extraction 生命周期、固定确切对象与版本、缺失明确失败；B 仍要承担同样 bootstrap 成本且无法沿用严格文件创建合同。

## 6. 安全 bootstrap 责任归属

| 责任 | 必须承担的工作 |
| --- | --- |
| Release/build | 固定 JNA artifact/Windows x64 entry，离线提取并核验；生成可信版本/hash 元数据与许可证；禁止启动时网络取 DLL |
| 安装/显式准备入口 | 从已认证 release 来源 provision 到祖先可信的 per-user 根；目录与 staging/final DLL 使用安全新建及 Protected DACL；拒绝既有异常对象，避免先公开写后修 ACL；验证真实 descriptor/identity/hash |
| JNA-independent verifier | 每次启动只读验证 native 根/文件/祖先；必要的 handle pin；固定脱敏错误分类；不能通过 JNA 实现自己首次 native 的信任 |
| Java main / native gate | verifier 完成后才允许 Native/Kernel32/Advapi32 初始化；锁定加载属性，拒绝入口绕过/预加载；启动错误 fail closed |
| Windows launcher | 使用同一已批准 bootstrap，不能只靠“加几个 -D”；保留既有 token/data/domain 隔离 |
| 更新/卸载 owner | 版本目录、运行锁占用、旧版本清理、卸载范围；不得删除用户状态或在运行中替换 DLL |

bootstrap 程序/脚本自身、JAR、hash manifest、Java executable 都必须来自初始受信任发布/安装链。**可改目录中的 DLL 加旁边可改 hash manifest 不能建立来源信任。** 现 portable 包有 SHA 清单/manifest 完整性检查，但不是对任意未知包的发布者认证；本机仓库祖先含广泛 Modify，不能直接宣布仓库/可写包目录为 trust anchor。

可信根不是一份持久化“验证成功”标记。一次安装成功不能豁免以后每次启动验证；日志/属性中的 success 字符串不能授权首次 native 加载。

## 7. 是否需要额外源码、安装或启动入口；供应链

**需要额外准备/验证和启动接入；现候选没有这些能力。** Java 21 公共 `AclFileAttributeView` 只暴露 owner 与 ACL list，不暴露 Windows descriptor 的 Protected bit/完整 native handle identity；不能用 ACL list equality 证明完整合同，也不采用 JDK internal reflection。[Java 21 API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html)、[Windows descriptor control](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-control)。

本轮已使用系统 Windows PowerShell/.NET Framework 创建全新 private fixture。这是隔离实验准备，未加入产品入口。现 release PowerShell/.NET 已具备部分 private ACL API，但不能把其现有 named-path 设置函数当作完整、无竞态、verify-only native bootstrap。

建议 Guard 选择以下成本之一：复用可信 Windows PowerShell/.NET 构成受审 provision/verify 入口，并让直接 Java 的早期 gate 调用同一 verifier；或增加专用 .NET helper（若 self-contained，会增加包体/构建与升级责任）。目录句柄/reparse/identity 证明可能需要受审 Win32 P/Invoke。**新的 helper/installer/native bootstrap/依赖仅列为架构成本，本轮未实现。** 不能只修改 Windows Assistant 而让 raw `java -jar` 绕过；若不引入 Java 可调用的独立 verifier，则必须明确 raw Java Windows 启动暂不支持，属于待批准兼容性改变。

本轮实际供应链核验：加载实验只使用**本轮前已经存在的 Maven 缓存 JAR**中 DLL。为来源核对从 Maven Central 获取同版本 JAR 字节作 hash 比较，网络字节未保存为被加载 DLL，也未动态下载 DLL 后立即加载。

| 对象 | 实际核验 |
| --- | --- |
| 坐标 | `net.java.dev.jna:jna:5.17.0` |
| Maven Central 与缓存 JAR | 字节一致；SHA-256 `b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0` |
| 原始 native entry | `com/sun/jna/win32-x86-64/jnidispatch.dll` |
| DLL SHA-256 | `5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023` |
| DLL bytes / PE | 273408；PE signature 有效；Machine `0x8664`（AMD64）；PE32+ `0x20b` |
| 缓存 JAR manifest | Implementation-Version `5.17.0 (b0)` |
| 隔离实际运行 | JNA `5.17.0`；native ABI version `7.0.2`；pointer size 8 |
| PE version metadata | FileVersion `7.0.2`、ProductVersion `5`，不能误写 DLL FileVersion=5.17.0 |
| PE 静态 imports | `PSAPI.DLL`、`KERNEL32.dll` |
| 本轮源码快照 | Native SHA-256 `3d8a0598206583e90d76f9a3bd0c8709aed03cbc07d8520cdefd81e6535b619e`；Platform SHA-256 `a915bff12f73dc1e924e7792a898f4a8a891f74f555e1ca8ee2936715fddd5f6` |

JNA Java artifact 版本与其 native ABI 版本不是同一编号。确切匹配依据是固定 artifact 的 entry + hash + PE + 运行，而非仅接受 native ABI compatible。来源：[固定 Maven Central artifact](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar)。hash 一致证明取到固定 artifact 的相同字节，不替代未来发布签名/安装信任链。

现有候选固定 `jna-platform:5.17.0`；本轮没有重新下载/测试 platform 或扩大依赖。许可证沿用候选 Apache-2.0 选择，独立打包 DLL 时须随包保留适用 license/notice，不能因为拆出 native binary 而遗漏。

版本更新先在新版本目录离线 provision、核验、发布可信 manifest，再让新启动采用；旧 JVM 仍使用原目录。缺失/异常不得自动采用系统 DLL。卸载/旧版本清理只能在明确应用目录内、无 reparse、已验证身份且不再被运行占用时执行；不能让 JNA 默认 marker 清理承担独立 sidecar 的生命周期。原 SHA 不能跨版本复用。

绝对 dispatch 路径与 JNA 开关只控制 **jnidispatch 的选择**，不等同于限制 Windows 对其 imports 或其他 `Native.load` 的依赖搜索。上述 OS imports 和候选 kernel32/advapi32 仍须验证为预期系统模块；不将任意未知系统 DLL纳入可信范围。[Windows DLL search order](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-search-order)。本轮未实施新的系统 DLL 搜索策略。

证据：[供应链核验](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/supply-chain.json)。

## 8. 生产路径祖先权限分析

只读取相关祖先目录 ACL/owner/reparse metadata。未打开 token、data 文件、旧 run 或旧 DLL；未修改既有 ACL。本轮进程 WindowsPrincipal 检查为 non-elevated。

必须区分实际入口：

| 入口 / 默认配置（源码事实） | 当前严格祖先检查的影响 |
| --- | --- |
| Release launcher：state=`%LOCALAPPDATA%/PersonalAiWorkspace/RuntimeState`，token 在 `Auth/client-token` | 首个被拒绝祖先为 `%USERPROFILE%/AppData`；本机 RuntimeState 尚不存在，未创建 |
| Release / 普通 Java：data、model-state 默认 `%USERPROFILE%/.personal-ai-workspace/...` | 本机从系统盘根、Users、user home 至已有 `.personal-ai-workspace` 的祖先判定通过；不表示目标文件符合 private 合同 |
| 直接 Java / dev launcher：token 默认工作目录下 `.runtime/client-token` | 本机仓库盘根已被候选拒绝；上层开发目录及仓库也存在 foreign Modify，不能靠换 native DLL 解决 token 祖先问题 |
| B1 MMF3 Assistant：固定 LocalAppData 下 `PersonalAiWorkspace-Mmf3/<run-id>` | 路径含相同 AppData 阻塞；本轮只读 runs parent metadata，未访问任何 run |
| JNA 默认 temp | AppData 首先被拒绝；Temp 和默认 JNA 目录另有直接 foreign Modify/FullControl |

关键权限与 SID：

| 祖先角色 | foreign authority（实际原始 mask） | 解释 |
| --- | --- | --- |
| AppData | `S-1-15-3-2968813833-811790644-2202111208-3784096404-1081847329-2708967783-1438471679`，Allow `0x001f01ff`，本对象有效、非 inherit-only | capability SID FullControl，包含 DELETE_CHILD、DELETE、WRITE_DAC、WRITE_OWNER；LocalAppData/Temp 继承此 ACE |
| Temp / 默认 JNA 目录 | `FOREIGN_ACCOUNT_1`、`FOREIGN_ACCOUNT_2`，Allow `0x001301bf` | Modify，含 WriteData / DELETE；本机 current token 不含这两个 SID，未展开账户姓名 |
| 仓库盘根/开发目录链 | Authenticated Users `S-1-5-11`，Allow `0x001301bf` | current token 也属于该组，但组覆盖其他账户，不能据此提升为 current-only 信任 |
| 仓库目录 | 额外 `FOREIGN_ACCOUNT_1/3/4` Modify | 目录继承及新文件来源存在额外 authority |

当前 candidate 的 `ancestorAcl` 判定 mask=`0x500d0040`，检查 DELETE_CHILD / DELETE / WRITE_DAC / WRITE_OWNER / GENERIC_WRITE / GENERIC_ALL；信任集合只有 current SID、SYSTEM、Administrators、既有 TrustedInstaller SID。它不执行按某个 foreign token 的 AccessCheck；跳过 Deny 与 inherit-only ACE，因此是**保守 authority 检查，不是完整有效权限计算**。

在本机 AppData 的 capability FullControl 及可写 Temp DLL 场景，这些不是仅“可创建无关文件”的权限；存在实际安全 authority，不能简单加 capability/Authenticated Users 到 trusted set。尚未识别哪个安装应用持有 capability SID，也没有构造其 token、执行替换攻击或证明任意普通用户都能获得该 capability。当前 token 不含该 capability，不等于其他 token 不可能含有它。

另一方面，ancestor Modify 中的 **DELETE 不等于父目录 DELETE_CHILD**：不能仅凭一个祖先自身 DELETE，就断言其所有 protected 子目录可删除；删除/重命名还受对象非空、父权限、share handle、有效 DACL 等影响。GENERIC_WRITE 与根目录特殊语义也可能使简单位检查过宽。此处只给出候选会拒绝与潜在 authority，未把所有拒绝都称为已动态证明的替换漏洞。若 Guard 要缩窄判定，应独立决定完整 object/parent/token/handle 合同，而不是加 SID 或删检查通过验收。[文件权限定义](https://learn.microsoft.com/en-us/windows/win32/fileio/file-access-rights-constants)、[DACL 检查顺序](https://learn.microsoft.com/en-us/windows/win32/secauthz/access-control-lists)。

普通用户能否使用已有可信目录：**本机 user home 祖先链可以**；current SID 在 home 有 FullControl，祖先无 foreign replacement 位命中。本轮在该链下创建全新 protected current-only fixture 成功。不代表所有 Windows 安装都具备相同 ACL，也不批准移动 Runtime 状态、固定 B1 run 根或把整个 home 当 private 目标。

即使 `PersonalAiWorkspace` 子目录本身判定通过，包含 AppData 的完整链仍会失败；不能以某一级 private 子目录覆盖上级 WRITE_DAC/DELETE_CHILD 风险。A 解决 native 根，不自动解决 LocalAppData credential/run 路径；两项必须分开提交 Guard 决定。

证据：[实际祖先 ACL](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/ancestor-acl.json)。

## 9. 正常 Java 21 与 Spring Boot executable JAR 兼容性

实际独立 JVM 为 Java `21.0.7+8-LTS-245`、Windows AMD64；JNA 5.17.0 + 固定 x64 DLL 可以由 boot path 正常加载。未使用 JDK internal API、`--add-opens` 或 Java native-access 绕过参数。

现 `PersonalAiWorkspaceApplication.main` 直接 `SpringApplication.run`，没有 JNA-independent early gate。未来 gate 必须位于 Spring bean / ACL helper 初始化之前；不能在 bean 初始化失败后补验证。

`-D` JVM 属性必须在 `-jar` 前；Spring `--workspace...` 参数不是 JNA JVM 属性。即使 `java -jar` 能读取 nested JNA Java classes，`System.load` 仍要求文件系统上的 DLL；独立 boot DLL 不依赖 nested native extraction。[Spring Boot nested JAR 布局](https://docs.spring.io/spring-boot/specification/executable-jar/nested-jars.html)。

现 package script 把 executable JAR 放 `runtime/`，Windows Desktop self-contained，release/dev 启动脚本目前都没有 JNA trusted-loader gate。方案 A 须增加 native sidecar、构建提取/hash/许可证、package manifest 和可信准备/验证入口。保留 executable JAR / Spring 业务 API；独立分发不能继续承诺“一个 JAR 在任意 Windows 可写目录直接启动即安全”。

对于正常 Windows 直接 Java，优先要求 main 通过相同可信 verifier 完成加载前证明；若 Guard 只批准 launcher wrapper，则 raw `java -jar` 必须明确 STOP，不得默默回退 default JNA。这个支持范围需批准。本轮没有重新打包、启动 Spring Boot JAR 或动态验收 launcher，因此仅有独立 JVM 实测与现代码/布局分析。

## 10. Windows 普通用户运行条件

日常运行不需要管理员、不启用 privilege、不修改系统目录或现有 user ACL。前提是：本地受支持 filesystem、current effective SID 有能力在已通过完整祖先验证的应用私有根创建/读取文件，已完成受信任离线 provision，DLL/JAR/helper/hash 元数据一致，根与文件保持 Protected/current-only 合同，全部入口受 early gate 约束。

若 home/企业 profile 的祖先 ACL、reparse/网络路径、安全产品或 DLL 加载策略不满足这些条件，结果是受控 STOP；不自动改全局 ACL、不提权修复、不增加 trusted SID、不换 Temp/系统 DLL。

本机只能证明新 home fixture 的条件成立。默认 LocalAppData credential/B1 run 仍阻塞；生产目标历史状态没有验证。没有把非 elevated fixture 成功升级为所有 Windows 用户支持声明。

## 11. Linux / POSIX 影响

现候选 `WindowsPrivateAcl.isWindows()` 在 native 实现类初始化前门控；已有 POSIX 0700/0600 及 domain/persistence 边界保持原样。未来 Windows bootstrap/sidecar 必须同样平台门控，不能对 Linux 全局设置 noclasspath/nounpack 导致无匹配 native 时拒绝正常 POSIX 路径。

若将来需要在 POSIX 使用 JNA，应另行核验对应架构 SO、owner/modes/ancestor/source 和加载 gate；Windows DLL/hash不能复用。JNA 固定源码的 Unix cache/fallback 行为不是可信 POSIX bootstrap 证明。本轮未执行 Linux/POSIX 运行，不安装 WSL、不改变这些路径。

## 12. 旧状态迁移风险

A 的 native 安装与 credentials/data/model-state 不同域，不能安装 DLL 时顺便修复、复制、删除或迁移旧 token/registry/DB/selection/guard。原严格 ACL 候选仍可能拒绝历史 unprotected/foreign-owner/foreign-ACE 对象；SQLite sidecar 等兼容性未知仍保留。

把 release state/B1 run 从 LocalAppData 换到 home 会改变已批准的路径、认证导入、实例/日志/恢复及历史数据发现合同；不能当成 loader 配置小改动。本轮不批准该迁移，也不修改 ADR-015 或 B1 固定入口。

新 DLL 的版本目录/原子 provision 是供应链生命周期，旧用户状态迁移须另案授权、只读 inventory、恢复/回滚与保留策略。缺 native 文件不能触发凭据重建或模型状态清除。旧状态兼容动态验证未完成。

## 13. 隔离实验实际结果

只运行小型 `NativeProbe`，classpath 只有探针与固定 JNA Java classes；没有 Workspace production classes、Spring 或 jna-platform owning flows。bootstrap 使用系统 Windows PowerShell/.NET Framework，在 home 下**全新、随机、一次性** fixture 中原子创建 Protected/current-only 目录，实际读回 owner、Protected、规则与 reparse。实验 DLL 来源为既存已核验缓存 entry，无网络动态 native 加载。

以下为最终完成的一组有限行为探针，不是生产 test suite 或大矩阵。每个 JVM 只验证 distinct loader 分支；预期拒绝是成功观测的 fail-closed 行为，不能写成 DLL 加载成功。

| 隔离分支 | 配置/实际结果 | 实际 module 来源 |
| --- | --- | --- |
| A explicit boot | 三个禁回退属性 true；boot 有固定 DLL；tmp 指向 fixture 中不可用位置；JNA loaded，退出 0 | `boot`；固定 DLL hash |
| A missing | 同禁回退属性；boot DLL 不存在；UnsatisfiedLinkError，退出 7 | 无 jnidispatch module |
| A invalid | 同禁回退属性；boot 为无 executable code 的合成非法 PE；UnsatisfiedLinkError，退出 7；path property 残留 invalid | 无 jnidispatch module |
| B / missing boot fallback | 不设置 nosys（默认 true）；java.library.path 有有效 fixture DLL；自定义 boot name、预置 forged path property；最终解压固定 classpath DLL，退出 0 | `tmp`；没有采用 fixture system DLL或 forged property |
| system opt-in | nosys=false、noclasspath/nounpack=true；boot missing；加载 fixture system DLL，退出 0 | `system`；path property/internal loaded path 均 unset |
| nounpack + file resource | nounpack=true、nosys=true，classpath 未禁止；同版 Java classes 的 fixture JAR 去掉 win64 entry，classpath file resource 提供原 DLL；退出 0 | `classpath-file`；证明 nounpack 不禁止此回退 |
| 显式坏 temp | boot missing、需 extraction；jna.tmpdir 指向 fixture 中一个普通文件之下，无法创建；UnsatisfiedLinkError，退出 7，有被隔离的 stderr | 无 jnidispatch module；没有回退默认 temp |

所有 successful module 用 OS 进程 module enumeration 观察实际 DLL 路径角色，并重新读取 hash；不单凭 `jnidispatch.path`。所有 observed successful DLL hash 均与固定 entry 一致。失败记录只保存白名单类别/角色，未保存 native exception 的路径 message 或原始 stderr。

**证据边界：**加载前验证的是新 fixture 根/预建目录；本轮未实现完整生产 file/ancestor verifier。实验后只读 DLL ACL：各 DLL owner=current、仅 current Allow，但 inherited、**Protected=false**。A 的 positive probe 证明加载选路，不是“已完成 Protected DLL 安装/身份 gate”；生产 A 仍须新建时显式保护 DLL。B 的 extraction 使用普通继承文件，不能声称根 Protected 就保证 DLL Protected。[文件 ACL 继承/新建语义](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights)。

初期探针存在无效观测：ACL 脚本直接格式化 generic mask 为枚举失败、反射读取 inherited Version field 的探针提前退出、一次 fixture 重试目录已存在、Oracle PATH wrapper 与真实 JVM 不同造成 module enumeration 为空。仅修本轮临时 harness；后续改用全新 fixture与解析后的 `java.home/bin/java.exe`。这些尝试不计为有效通过；保留脱敏 harness notes 与 wrapper observation。最终 probe 所有期望结果与实际 module 断言完成；之后未再次运行绿色探针。没有将 harness 故障当作原 IOException 的解释。

实际运行：临时探针 `javac` 编译、独立 JVM loader 分支、只读 ACL/module/PE/hash 检查、Git patch 保留检查。未运行 Maven production compile/test、Java/Desktop/frontend/full suite、打包矩阵、模型/GUI acceptance；原候选绿色 focused tests 仅是历史证据，本轮没有重跑或作为此次 PASS。

证据：[probe results](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/probe-results.json)、[加载前 fixture 目录 ACL](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/fixture-preload-acl.json)、[实验后 native 文件 ACL](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/native-file-acl-after.json)、[harness notes](../../.verification/mmf3-trusted-loader-c87a0203c9e64d6ea76430d905fe204f/harness-notes.json)。

## 14. 仍未知的原 LocalClientToken IOException

原 IOException 的准确阶段、cause、Win32 code 仍未知。加载信任阻塞、已独立复现的 Java NIO Protected DACL 丢失与原 IOException 是不同证据；不能合并成已证实单一根因。

本轮未访问旧失败 run、未重启 Runtime、未读取正式或历史凭据、未做 token I/O fault injection，不能恢复原先没有保存的异常。现候选的未来脱敏阶段诊断不能反推出该异常原因。

## 15. 最小拟实施文件范围（待批准，未实施）

| 最小 owner / 文件 | 若批准 A，所需职责 |
| --- | --- |
| 新的 Windows JNA-independent provision/verify 入口（具体 PS/.NET 文件待选） | 安装原子 private root/DLL、启动 verify-only、ancestor/Protected/identity/hash、必要 pin 与脱敏失败 |
| `PersonalAiWorkspaceApplication.java` + 必要的窄 native gate | 在 Spring/JNA 初始化前完成同一 verifier；固定属性并 fail closed；不在领域 owner 中分散 bootstrap |
| `WindowsPrivateAcl.java`（仅如需防早期 native 初始化绕过） | 在 native impl 初始化前确认同一 gate 已完成，不修改 ACL/domain 算法 |
| `scripts/package-windows.ps1` | 固定缓存/artifact native entry 的离线打包、hash/arch/license、manifest；helper 发布仅在选定方案确实需要时加入 |
| `scripts/release/start-release.ps1` / `release-functions.ps1` | 接同一准备/verify 合同与固定 JVM flags，日常不修 ACL；保证 preflight 在现有模型/服务操作之前 |
| `scripts/start-workspace.ps1` | dev Java 启动采用同一 gate，不让默认 extraction 绕过 |
| 选定 Windows helper 工程文件（仅若 Guard 选专用 helper） | self-contained/runtime/PInvoke 构建和供应链成本，不能提前创建 |
| 精确分发说明与最低限度验收记录 | 用户准备/启动/更新/卸载条件、缺 DLL STOP、raw Java 支持范围 |

不修改 LocalClientToken/Memory/Knowledge/ModelState 格式/API、不移动已有路径、不重构 Desktop/WebView 或增设通用进程管理。B1 隔离验收 launcher 的固定路径与 native 验证兼容性只在其 owner 另案批准后接入；native A 自身不能关闭其 ancestor blocker。本轮不预建长期测试矩阵。

## 16. Architecture Guard 待批准事项

1. A 是否作为下一实施方向；是否要求 native DLL 也显式 Protected（建议保持），仍坚持无系统/classpath 回退、缺失/身份异常 STOP。
2. 初始发布来源与 bootstrap trust anchor；安装根选择、current effective SID/祖先/handle/TOCTOU 合同；不得以可写邻接 manifest 建立来源信任。
3. 复用可信 PowerShell/.NET 与专用 helper 的成本选择；是否允许 P/Invoke、是否 self-contained、helper 初始信任与 Java 入口调用方式。
4. 普通 Java Windows 启动与 executable JAR 的支持合同：强制统一 verifier，或明确不支持 raw bypass；所有入口在 JNA/Spring 前 gate。
5. LocalAppData AppData capability authority 与严格 ancestor predicate 是否需要另案重审；不得加新 trusted SID。默认 credential/B1 路径仍 STOP，不默认批准迁移 home。
6. 固定 artifact/ABI/architecture/hash、OS dependencies、license/notice、版本更新与卸载/旧库清理责任。
7. 后续仅在新 fixture 中验证生产 bootstrap不可绕过、file identity/hash mismatch、unsafe ancestor/reparse 的最小高价值负向行为；旧状态兼容与原 IOException 另案授权。不得把本轮 loader probe 当正式 Runtime/model acceptance。

这些是明确尚未批准的架构/兼容性选择，不是向用户请求实施权限。本轮授权已经完成至研究报告。

## 17. GO / STOP

**研究结论：A 是优先推荐的可审方向；B 未证明符合完整合同。** 固定版本加载选路与拒绝行为已有有限独立 JVM 证据；可信安装、每入口的 JNA-independent pre-load verifier、Protected DLL 与生产路径兼容性尚未实现/批准。

**生产修复 / 发布：STOP。MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。** 原候选 patch/报告/evidence/旧失败 run 保留；原 IOException 与旧状态兼容仍未知。提交本报告后停止，不继续生产实现、真实模型验收、Git 提交或发布。
