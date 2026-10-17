# MMF-3 Runtime Private ACL Root Cause Investigation

2026-10-09（Asia/Shanghai）。**诊断交付；没有生产修复候选。Final: STOP。**

已独立复现并定位 Protected DACL 被解除的调用。原始 LocalClientToken IOException 未复现，具体阶段与 Windows error category 仍未知；不能将两个现象合并为一个已证明的根因。普通 Runtime、Windows Assistant、Chrome、模型与共享 Ollama 均未启动或调用。

## 1. Git Reality

两仓库本轮分别实际执行指定代理 fresh fetch，成功，无 direct fallback，无全局配置修改。入场工作树 clean、index empty。

| 仓库 | 入场分支 | HEAD / fresh origin/main |
| --- | --- | --- |
| Workspace | `codex/mmf3-b1-same-account-entry` | `8dc66d81b78c3efac70feed94aec25ea71dd21a5` |
| Browser | `main` | `267db794691d1a9ab1712b30b07cbeb57241808b` |

核对通过后创建 `codex/mmf3-runtime-private-acl-fix`，HEAD 未改变。没有 commit、push、merge、tag、release、amend、rebase、reset 或移动 main。Browser 无修改。

适用工作树及祖先未发现磁盘 AGENTS.md；遵循用户提供指令，读取 current architecture、STATUS 的相关边界、ADR-015 与实际 owning code。ADR-015 保持 Accepted，状态文档未修改。

最终唯一非 ignored 变更为本报告（untracked、未提交）；生产/长期测试/配置/Desktop/Browser diff 为零。诊断代码与证据仅在 ignored `.verification/mmf3-runtime-acl/`。临时 fixture 保留，不删除原现场或测试账号。

## 2. 原始故障证据（只读）

失败 run：`ba5d04c888274e9ba4ba2f016cad0533`。只读此前保存的 failure-outcome、stop-state、post-failure-directories、脱敏 stdout 与旧 preflight 报告；未打开 token、registry、DB、lock 或领域内容。

- 日志：08:33:14.745 启动，08:33:17.310 Application run failed。
- `BeanCreationException: localClientToken`；`IllegalStateException: Cannot initialize private local client credentials`，`LocalClientToken.java:42`。
- 保存记录：credentials/data Protected=true → false；owner 未变，无 reparse；model-state 与其他必需目录保持 true。
- 保存退出证据：launcher PID 24516、Java PID 29868 均退出，18765 无 listener，token 不存在；这是此前保存的证据，本轮未重启原进程。
- 底层 IOException 与 cause 未保存；现有 catch 丢弃 cause，无法从此日志恢复 Windows error code。
- 本轮只读核验旧 Runtime JAR SHA-256：`d6e3aa38bd88735c31bd33564bc9b1bb00fe11ab5f74917363dca9136f1503ff`。
- 当前六个调查 owner/config 源文件与旧构建源码基线 `6d22e327c3ff705a57d33fe4a61ad97cd284b8ba` 的 diff 实际为零。

原现场无 ACL 重设、删除、token 预置、数据库/lock 清理、日志修改或 Runtime 重启。

## 3. 已定位的 ACL root cause 与 owning calls

| Owner | 调用位置 | 实际行为 |
| --- | --- | --- |
| LocalClientToken | constructor:23 → restrict:57 | 对 credentials 调用 `AclFileAttributeView.setAcl`，解除既有 Protection |
| BrowserClients | constructor:46 → LocalClientToken.restrict | 同样操作 credentials；lock/registry/pending 也复用 restrict |
| PrivateMemoryDirectory | protect:85；prepare:17 | 对 data 调用 setAcl，解除既有 Protection |
| PrivateKnowledgeDirectory | protect:58；directory:43 / prepare:12 | 同样操作 data；其新子目录/文件也经过 protect |
| ModelStateStore | constructor:68 / 71；permissions:354；verify:378 | 已有目录仅验证，无 setAcl；新对象使用 `acl:acl` 初始属性 |

本机 Java：Oracle Java 21.0.7+8-LTS-245，Windows 11，默认 Windows NIO provider；fixture 使用同一实际 JDK，不通过管理员身份运行。脚本实际拒绝 elevated token，记录当前 SID。

本机 `src.zip` 的 `WindowsAclFileAttributeView.setAcl`（201–214）创建新 security descriptor，只以 `DACL_SECURITY_INFORMATION` 调用 SetFileSecurity；`WindowsSecurityDescriptor`（164–166）初始化 descriptor 并设置 DACL，没有设置 SE_DACL_PROTECTED。本轮 javap 核对实际 JDK 字节码，参数为 `iconst_4`，与本机源码一致。

OpenJDK 21.0.7 对应 native 实现调用 SetFileSecurityW；该源码是上游对照，未声称对 Oracle native DLL 做反编译。[OpenJDK native source](https://github.com/openjdk/jdk21u/blob/jdk-21.0.7-ga/src/java.base/windows/native/libnio/fs/WindowsNativeDispatcher.c#L680)。

Protection 是 security descriptor 的 control bit；FILE_INHERIT / DIRECTORY_INHERIT 是 ACE flags，两者不同。不能靠保留这两个 flags 保留 Protection。[Microsoft control-bit documentation](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-control)。

本机 getAcl 解码仅保留 Java 的四种 ACE flags，丢弃 Windows INHERITED_ACE 信息，也不返回 Protected bit。因此 Memory/Knowledge 的 `getAcl().equals(expected)` 可以通过，而真实 Protection 已经变为 false。Java 的公开 ACL view 只有 owner/acl，未提供 Protected 属性。[Java 21 ACL API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html)。

## 4. 独立复现及 Protection 变化

受控 PowerShell fixture 在 Windows Temp 下创建全新随机根及独立 case 子目录，创建时设置并读回验证：当前 SID owner、Protected=true、唯一当前 SID explicit Allow FullControl、ContainerInherit/ObjectInherit、无 reparse/junction/symlink，包括祖先检查。此脚本仅作测试布置；Java 运行后未通过脚本修复任何 ACL。

直接 javac 编译当前 owning source；直接 Java fixture 调用 owner 方法或反射调用原 restrict。没有 Spring context、HTTP server、JDBC 数据库操作、provider 或模型调用。Memory prepare 仅创建空 fixture 文件；model-state 仅打开本 fixture 的新 lock。

每步前后保存实际 owner SID、ACL SID/type/rights、explicit/inherited、inheritance/propagation、Protected、reparse、对象存在性及操作结果。异常输出仅允许类型、脱敏 category 与代码 stack frame，不输出 message、token bytes 或私有绝对路径。

| 独立步骤 / owning operation | Protection / ACE 结果 | I/O 结果 |
| --- | --- | --- |
| 已有 credentials: Files.createDirectories | true → true；explicit 保持 | SUCCESS |
| 同一目录: getOwner | true → true | SUCCESS |
| 同一目录: current principal lookup | true → true | SUCCESS |
| 同一目录: 单独 setAcl，仅原 restrict 的 ACL 参数 | **true → false；唯一 ACE explicit → inherited** | SUCCESS |
| 独立 credentials: LocalClientToken.restrict(parent,true) | **true → false；explicit → inherited** | SUCCESS |
| Files.createTempFile + 诊断用固定 basename 原子重命名 | 新临时文件 false；唯一当前 SID inherited FullControl；parent 保持 false | SUCCESS |
| restrict(temporary,false) | 文件保持 false；权限主体未扩大 | SUCCESS |
| 写入非敏感固定 synthetic bytes | ACL 不变 | SUCCESS |
| 原子 move 到 fixture client-token | 文件 false；当前 SID inherited FullControl；原临时文件消失 | SUCCESS |
| restrict(final token,false) | 文件保持 false；主体未扩大 | SUCCESS |
| PrivateMemoryDirectory.protect(data,true) | **true → false；explicit → inherited** | SUCCESS |
| PrivateKnowledgeDirectory.protect(data,true) | **true → false；explicit → inherited** | SUCCESS |
| PrivateKnowledgeDirectory.directory(existing data) | **true → false** | SUCCESS |
| PrivateMemoryDirectory.prepare | data **true → false**；新空 memory.db 为 false/current SID inherited | SUCCESS |
| PrivateKnowledgeDirectory.prepare | data **true → false**；新 knowledge 为 false/current SID inherited | SUCCESS |
| ModelStateStore(existing protected model-state) | 已有 root true → true；新 .lock false/current SID inherited | SUCCESS |
| ModelStateStore 等价 acl:acl 新文件创建 | root 保持 true；新文件 false/current SID inherited | SUCCESS |
| 原 LocalClientToken constructor，fresh 独立目录 | credentials **true → false**；fixture token 创建成功 | SUCCESS |
| 原 BrowserClients constructor，fresh 独立目录 | credentials **true → false**；新 fixture lock 为 false | SUCCESS |
| 同一进程组合：ModelStateStore → Memory prepare → Knowledge prepare → BrowserClients（保持 lock）→ LocalClientToken | credentials/data **true → false**；model-state root 保持 true；fixture token 成功 | SUCCESS |

全部观测中 owner 始终为当前 SID，最终 ACL 未出现 foreign principal；这只证明当前测试父目录为 owner-only 时的结果，**不证明在其他父目录下没有权限扩大**。新文件仍为 unprotected/inherited，不能作为全局安全修复通过证据。

初次 fixture 发现 token 独立成功；随后扩展为单调用归因与同进程组合，以获得新信息。没有执行或重复全测试矩阵。第二轮完整记录为交付 evidence；第一轮的全新临时根也保留。组合顺序依据已保存的初始化副作用及 owning code构造，不声称有原进程精确 bean timing trace。

## 5. LocalClientToken IOException：阶段与原因仍未定位

原错误仅证明 IOException 进入 constructor:41 的 catch。这个 catch 覆盖 createDirectories、restrict、temp creation、write、atomic move、finally delete、size、final restrict 和 read；无法据外层消息确定其中哪个失败。

本轮原 constructor、分步文件操作以及保持 BrowserClients lock 的同进程组合均成功。Protection 丢失后也可以完成凭据初始化，因此 **Protection=false 本身并非此 fixture 中凭据初始化失败的充分条件**。

没有给正式 catch 增加 cause/logger，也没有声称 ACCESS_DENIED、owner lookup、temp create、move 或 cleanup 是原失败原因。没有原始 Windows error code；不能从 token 不存在反推原阶段，也不能排除被 finally cleanup 覆盖的异常。原现场只读约束与禁止重启保持。

分步 fixture 的 synthetic 文件不是正式验收 token。实际 constructor 只在新的一次性 case 中生成测试 token，不读回/输出/导入/用于请求；这是本轮获准的独立初始化验证，不是正式 token 或 GUI 认证。

## 6. 一个根因还是两个

**已证明一个 ACL 根因；另一个初始化故障的根因未知。**

多个 owner 的相同 setAcl 用法与本机 JDK descriptor 行为可解释 Protection 变化。该机制在成功初始化 fixture 中同时出现，故不能把 LocalClientToken 的原 IOException 与它认定为同一根因，也不能断言两者完全独立。

原进程到底先由 BrowserClients 还是 LocalClientToken 解除 credentials Protection，以及 data 上 Memory/Knowledge 的精确首次调用顺序，保存日志没有逐调用证据；本轮证明每个入口独立具有该行为，不伪造原进程 tracing。

## 7. 最小修复范围与 Architecture Guard 决策请求

**实施文件：0。没有候选生产补丁。**

仅凭跳过 setAcl 或接受 getAcl equality，可以避免某些已有目录变动，却不能证明 Protected=true、拒绝 unprotected 输入、避免未来 foreign inheritance 或保证新对象私有；不能作为本任务合格候选。创建时 acl:acl 也未令本 fixture 新文件 Protected=true。

如需读取/设置 Windows descriptor control bit，本次已检查的公开 Java ACL API 没有提供该能力。native interop、新依赖、PowerShell/icacls 子进程、反射内部 JDK native 权限修改器及通用 ACL 框架均未实施。若未来方案需要这些机制，须由 Architecture Guard 单独决定；本轮授权不能延伸为新权限架构。

未来任何已批准窄方案仍优先落在实际 Java owner（LocalClientToken / PrivateMemoryDirectory / PrivateKnowledgeDirectory）；BrowserClients 的共享 restrict 调用必须复核；ModelStateStore 的新对象语义也须验证。这里是待决策范围，不是已批准或已经实施的架构。

## 8. Security invariants

| 必须保护的 invariant | 本轮结论 |
| --- | --- |
| Protected=true 不得解除 | **现有实现实际 FAIL**（credentials/data） |
| 当前 SID owner、owner-only Allow | 当前私有 fixture 中保持；未证明所有输入 |
| 不允许 foreign parent ACE 获得访问 | 未完成负向验证，不标 PASS；已有继承开关失效 |
| 新文件在创建/写入/发布期间私有 | 在已知 owner-only fixture 父目录中观测到当前 SID FullControl；没有合格修复证明 |
| 错误 owner/foreign ACE/reparse/错误路径 fail closed | 未完整动态执行，不标 PASS；需在获准修复方案中验证 |
| token 错误不得发布不确定部分状态 | 正常 atomic move 成功，temp 消失；故障注入未执行，不能证明异常路径 |
| 非 Windows 权限路径保持 | 生产源码零修改；POSIX 执行未进行 |
| B1 非法目录拒绝、不放宽 Protection gate | owning source 保持，见 §9；无 GUI 验收 |
| 不改正式路径/API/schema/模型状态合同 | 生产/配置/ADR/Desktop/Browser 零修改 |
| 无管理员掩盖、外部启动后 ACL 修复、预置正式 token | 本轮遵守 |

源码审查还确认 LocalClientToken.restrict 以文件 owner 建 ACE，不核对 current account；Memory/Knowledge 则先查询 current principal 并比较 owner；ModelStateStore 校验 owner/type/private ACE。LocalClientToken 链检查仅 isSymbolicLink，BrowserClients/Memory/Knowledge/ModelStateStore 还检查 isOther。这些是实际代码差异，未据此猜测原 IOException，也未扩大实施范围。

## 9. 聚焦验证与失败记录

**实际执行：** 全新 Windows fixture 的 ACL 布置/读回、单步原代码调用、原 constructor 独立初始化、同进程资源组合，以及本机 JDK source/javap 核对。完整 ACL记录的 I/O 结果为 SUCCESS，安全验收条件却 FAIL（Protection）；不将 SUCCESS 操作等同安全 PASS。

**B1 手工 Source Review：** Mmf3AcceptanceLaunch.ValidatePrivateDirectory（54–71）仍拒绝非当前 owner、unprotected、foreign/non-Allow ACE、缺少 FullControl；Validate/路径入口仍拒绝 reparse、缺失目录与非法 run/path。Desktop source diff 为零。没有把既有 compiled focused tests 或静态审查记作本轮执行通过。

**未执行：** 修复回归测试（没有修复候选）、错误 owner/foreign ACE/reparse/错误路径负向矩阵、IO 故障注入、非 Windows fixture、Desktop test suite、GUI、完整 Runtime、全部 Java/Frontend/Browser/IME/stress/packaging matrix。原 IOException 未复现且需要 Guard 决策，依用户允许诊断 STOP 分支停止，未为达到测试清单猜测修改代码。

没有新的长期测试，无测试数量/coverage 目标。没有新增模型任务、模型请求或共享 Ollama 访问。未做网络 wire capture，不以此宣称已经测量所有其他进程的流量。

## 10. 构建结果

使用同一 JDK 21 javac 实际编译 AclProbe 与当前六个调查 owner/config 源文件，成功。实际执行独立 Java fixture；ModelStateStore 使用的 ExtendedOpenOption 有既有内部 API 编译警告，无新增依赖或新 native call。

未执行 Maven production build、Desktop/Frontend build 或 packaging：生产代码未修改，fixture 构建足以支撑此次诊断。此前 Runtime/Desktop 构建不是本轮构建证据。

## 11. 完整 Source Review Patch

- [生产 source-review.patch](../../.verification/mmf3-runtime-acl/source-review.patch)：0 bytes，完整表示没有生产/长期测试候选修改。
- [诊断 source patch](../../.verification/mmf3-runtime-acl/diagnostic-source-review.patch)：完整新建 AclProbe.java 与 run-probe.ps1；仅 ignored 调查代码，不是 Runtime 权限修复或发布补丁。
- [实际详细记录](../../.verification/mmf3-runtime-acl/probe-results.json)、[简表](../../.verification/mmf3-runtime-acl/probe-summary.json)、[fixture build/run log](../../.verification/mmf3-runtime-acl/probe-build-and-run.log)、[证据 SHA 清单](../../.verification/mmf3-runtime-acl/evidence-sha256.json)。
- 本报告是未提交诊断文档；没有修改 STATUS/current architecture/ADR 或任何原失败证据。

诊断 harness 的必要校验和仅本 fixture 元数据脱敏调整均限 ignored 文件；证据不保存 token bytes 或私有绝对根。脚本源码中测试路径由 Windows Temp 派生，重放会创建新根，不会重用失败 run。

## 12. Patch SHA-256

| Artifact | SHA-256 |
| --- | --- |
| 空生产 source-review.patch | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| diagnostic-source-review.patch | `83675ac8d6b0c78b968f2ba202dc7dad87363f19c7612e8c3d43828958dcfd56` |
| probe-results.json | `d1bc712b524cf210b70eda276cb801c504c23270fbeebf53f40878beb8880ffe` |
| 本机 Java src.zip | `53458a4228000e3ac958c8915f910263e78f7dc41173f406da333cf829e9b7a0` |

空补丁 hash 不表示修复已批准；没有合格修复可供生产 Source Review。Guard 可独立审查诊断 source patch 和原 owning source。

## 13. Remaining blockers

1. 原 LocalClientToken IOException 的准确阶段、cause 与 Windows category 未恢复/复现。
2. 未取得符合全部 invariants 的 Windows 权限修复；公开 NIO view 不表达 Protected control bit，需要单独评估合格窄方案及是否触发 native/依赖架构决策。
3. 修复后的 foreign inheritance、新文件安全、负向 fail-closed、异常 atomic 状态与非 Windows 路径验证未完成。无候选时不得称这些 gate PASS。
4. Desktop/Chrome GUI 和真实模型 MMF-3 acceptance 仍未经本轮授权或执行。

## 14. GO / STOP

**ACL 调用归因：已复现。完整故障根因与修复候选：未完成。Final: STOP。**

本报告只请求 Architecture Guard 独立审查诊断证据及后续权限方案决策，不请求发布，不授权 GUI 或模型操作。ADR-015 Accepted 不变。

**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**

报告交付后停止，等待 Architecture Guard 独立 Source Review / 决策。
