# model-state 拒绝分支与历史证据审计

2026-11-10，从 `9022f82` 接续；只读源码／历史证据研究。**已逐项对应错误的全部布尔分支，补齐 Windows 文件系统准入这一约束；仍不能从旧回执确定类型或所有者哪项失败。没有修改安全检查、重试登记或恢复桌面验收。** 完整维护继续 **BLOCKED / productionGo=false**。

## 原动作、执行事实与来源对应

目标仅为原合成 Profile `b204c7e3cc33425e841eb1aa32d2caa2` 的 `model-state`：`C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2\model-state`。

原动作是在 `SeedReading.main:43` 构造 `ModelStateStore`，下一行准备调用 `armGuard` 登记合成阻断请求。保存的 `seed-run-errors.log:1–4` 记录 `ModelStateStore.verify:496` → 构造函数 `:93` → `SeedReading:43`。这是**产品校验拒绝，已知失败**，不是本次自动审批拒绝，也不是未知执行。不能说“原动作完全没执行”：构造函数的账户查找、平台判断、路径检查、`createDirectories` 已返回；之前的合成 Knowledge 导入、历史写入和删除也已按脚本执行。是否在这次构造中刚创建根目录，日志没有记录。

`rootKey` 赋值尚未完成，`:98–110` 的 `.lock` 创建／验证／排他锁、后续状态读取及 `SeedReading:44` 的 `armGuard` 没有到达。`SeedReading:46` 的最终 fixture 记录也未执行。既有 `startup-cleanup.json` 的 `desktopExited=null`、`desktopForced=false`、Job 0、`failure=Owned preparation tool failed` 对应在准备阶段停止，不能解释为 EXE／WebView 已启动后崩溃。

证据均精确读取自仓库，不访问原 Profile、旧端口或旧进程：

| 证据 | 本次读取／核对范围 |
| --- | --- |
| `.verification/knowledge-reading-desktop-20261110/Seed.ps1` | 使用 source-reading 包内 Java，classpath 是 reader-context 的 `compiled` 与 `libraries/*`；显式参数没有 `-Duser.name` 或用户名改写。不能将此进程直接称为从打包 Runtime JAR 启动。 |
| 同目录 `SeedReading.java`、`seed-run-errors.log`、`prepare/owned-profile.json`、`startup-cleanup.json` | 路径、调用顺序、异常位置、创建回执与退出结果；`owned-profile.json` 的 hostPid 是旧登记宿主编号，不是 Java token／owner 证据，本次没有查询该 PID。 |
| `.verification/knowledge-reader-context-20261110/product-sources.json`、`delivery-result.json` | 原构建来源为 `a9cbc5d`，记录源码稳定。三个安全源码的当前 SHA-256 与保存清单完全一致；`git diff a9cbc5d -- <三个文件>` 为空。未声称本次重编译或再次执行了原 class。 |
| 随包 `runtime/java/release` | Oracle `21.0.7+8-LTS-245`、Windows x86_64；这是发行描述，不是失败时 `user.name`、令牌或属性快照。 |

本次来源核对保存于 `.verification/knowledge-excerpt-memory-20261110/identity-source-provenance.json`。三个文件为 `ModelStateStore.java`、`WindowsPrivateAcl.java`、`WindowsNativeGate.java`。

## 错误条件逐项拆解

`ModelStateStore.java:491–496` 顺序为：Windows 原生验证（若 `windows`）→ `noLinks` → Java Basic 属性读取 → 下表从左至右短路求值。异常文本仅说明至少一个条件为真，**不保证 owner 查询曾执行**。

| 分支 | 预期来自哪里 | 本次可证实的历史事实 | 仍未知／不能声称 |
| --- | --- | --- | --- |
| `directory ? !attr.isDirectory() : !attr.isRegularFile()` | 构造函数 `:93` 明确调用 `verify(root, true)`，预期根对象是目录。Basic 属性在 `:494` 以 `NOFOLLOW_LINKS` 读取。 | `directory=true`；因此这一次不是 regular-file 校验。属性读取返回后才可能抛出此处的错误。 | 没有 `attr.isDirectory()` 的当时值。若它为 false，后续 fileKey／owner 条件不会求值；不能凭日志断言 owner 不同。 |
| `!windows && attr.fileKey() == null` | `:89–90` 通过“POSIX view 为空且 DOS view 非空”确定 Windows；非 Windows 要求可用 fileKey。 | 原调用先成功取得 `WindowsNativeGate.profileRoot()`。在保存源码与官方 Windows provider 的通常实现下，走 Windows 专用准入并得到默认 WindowsPath，POSIX 不支持、DOS 支持，故 `windows=true`，此分支短路为 false。 | 原日志没有直接打印布尔值或 provider 返回值。上游源码证据不能替代 Oracle 二进制逐指令证明；若拒绝这项实现对应假设，只能将此分支保留为未采样，不能擅自修成放行。 |
| `!Files.getOwner(path, NOFOLLOW_LINKS).equals(account)` | `account` 在 `:88` 由 `lookupPrincipalByName(System.getProperty("user.name"))` 得到；owner 则在前两条件均 false 时，单独按路径读取。 | 账户查找已返回，否则不会到达 `:496`。上游 Windows principal 相等比较 SID，不比较显示名格式。 | 没有 `user.name`、解析 SID、当时 owner SID 或比较结果；类型失败可让这一分支根本不执行。不能用 `CodexSandboxOffline` 路径名、当前工具账户、域前缀或管理员组身份替代实际 SID。 |

### 为什么 Windows 分支不再只依据路径猜测

`SeedReading:29` 首先调用 `WindowsNativeGate.profileRoot()`。`ordinaryProfileRoot:104–108` 要求已准入的根目录非空；`admit:54–90` 才能赋值。`isWindowsPlatform:292–296` 拒绝自定义 DefaultFileSystemProvider 并检查实际类名 `sun.nio.fs.WindowsFileSystem`，`localPath:375–379` 使用 `Path.of` 构造根路径。ModelStateStore 使用该路径继续 `resolve`，没有换 provider。

OpenJDK 21.0.7 的 [WindowsFileSystemProvider:149–170](https://github.com/openjdk/jdk21u/blob/jdk-21.0.7-ga/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java#L149) 对 DOS view 返回实现，对 POSIX view 返回 null，这个选择是 provider 能力，不是按目标目录的 DACL 动态推断。该版本 [WindowsFileAttributes:378–416](https://github.com/openjdk/jdk21u/blob/jdk-21.0.7-ga/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java#L378) 的 `fileKey()` 正常返回 null；目录判断取目录位并排除符号链接。**不能把“Windows 的 fileKey 是 null”当作原失败原因。** 这是源码调用链推断，明确区别于旧进程现场采样。

### 原生验证已说明什么

基于上述 Windows 路径对应，`:492` 的原生验证必须先正常返回，Java 才到 `:496`。`WindowsPrivateAcl.NativeAcl.operate:169–224` 取得 `currentSid`，逐级打开并暂时 pin 路径；`open/identity:228–252` 校验非 reparse、目录位、磁盘类型、最终路径和文件 ID；`privateAcl:272–309` 校验所有者等于有效 TokenUser、私有 DACL 和访问规则。

因此可作**控制流推断**：在原生操作自己的观测时点，类型／owner／DACL 校验没有拒绝。不能把它提升为 Java 后一次观测中的具体属性值，也不能把后一个通用错误解释为原生 `Windows private owner rejected`。这两个异常文本、位置与判断边界都不同。

`currentSid:255–269` 首选线程令牌，仅 `ERROR_NO_TOKEN` 才取进程令牌，读取 TokenUser；[Microsoft OpenThreadToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openthreadtoken) 说明 `OpenAsSelf=true` 决定访问检查的安全上下文，并不把线程令牌换成进程令牌。Java 的 `user.name` 则可能由 [Windows 默认属性初始化](https://github.com/openjdk/jdk21u/blob/jdk-21.0.7-ga/src/java.base/windows/native/libjava/java_props_md.c#L585) 从非空 `USERNAME` 取得，缺失时才使用 `GetUserNameW`；[WindowsUserPrincipals](https://github.com/openjdk/jdk21u/blob/jdk-21.0.7-ga/src/java.base/windows/classes/sun/nio/fs/WindowsUserPrincipals.java#L72) 通过名称解析获得 SID 并比较 SID。

这些来源机制允许值不同，**仍未证明本例值不同**。`rejectOverrides:299–315` 拒绝 JAVA_TOOL_OPTIONS 等覆盖和 agent／patch-module，但不把 `USERNAME` 与 TokenUser 做一致性绑定。当前进程环境也无法重建原进程继承的环境。

`Pins` 在本次原生 `operate` 返回前关闭（`:181`、`:354–367`）。原生验证、`noLinks:455–461`、Java Basic 与 owner 读取并非同一持有句柄上的原子快照。替换或身份变化是逻辑上尚未排除的可能性；**没有任何已保存证据证明发生过竞态、冒充、目录替换或攻击**，本次不制造这类场景。

## 不能混入这个错误的其他分支

| 位置 | 自己的错误／行为 | 与现有栈的关系 |
| --- | --- | --- |
| `ModelStateStore:81–86` | state 与 credentials/data 重叠、位于项目内 | 到 `:93` 前已经通过；不能将当前错误归于项目目录限制。 |
| `:88` | 用户名查找失败 | 应在查找处抛错，不是现有栈。 |
| `:91–92`、`:492–494` | links/reparse、原生准入、类型／owner／DACL、属性读取异常 | 有各自的异常路径；既有栈说明已返回，没有失败详情被合并为 `:496`。 |
| `:497–511` | POSIX 权限、非 Windows ACL、owner access | 位于 `:496` 后，本次未执行；不应以改 ACL 作为这个回执已证实的修复。 |
| `:98` 之后及 Seed 的 `armGuard` | 锁、状态文件、合成请求登记 | 未到达。未知外部模型请求与这一合成登记失败是独立状态，不因此清除。 |

## 最小后续证据和权限边界

历史资料缺少的值不能靠重跑推导回来。本次没有访问原 Profile 的文件／元数据，没有查询旧 PID／端口，没有构造 ModelStateStore、换 Profile／身份／启动器或改变 ACL。没有确认安全校验本身的实现错误，因此没有提交删减校验或身份替换修复。

若之后获准**单独采集当前状态**，最小只读动作应限定上述准确的 `model-state` 对象，以不跟随 reparse 的元数据句柄读取对象类型、文件 ID、owner SID；不遍历目录，不读取 state、guard、token、数据库正文或凭据。同时在明确获准的同一诊断进程记录时间、默认 provider/view 能力、Java 命名账户是否与 owner 相等、有效 TokenUser 是否与 owner 相等；只保留比较结果及必要受限证据，避免把真实账户名／SID 写入公共日志。不得调用会创建目录、开 writer 锁或登记 guard 的构造函数，也不得使用 `profileRoot()` 的完整准入／租约动作冒充纯元数据采样。

这项读取涉及原 Profile 所有权与身份信息，超出本轮“仓库内精确已保存证据”的范围，**尚未请求／取得确认，保持待确认**。它可能因访问权限或 no-share 句柄失败，失败即停，不升级权限或改 ACL。没有写操作，因此无业务数据回滚；诊断输出只留本地受限证据，不外传。当前快照也仅证明采集时状态，不证明旧进程失败原因。

若必须在未来一次正常、另行获准的启动中区分具体分支，可以先审查保留原短路顺序和所有安全谓词的无敏感值原因码方案；它只改进诊断，不能作为重试或放行的理由。本次没有实现、运行或用替代样本验证这条受阻路径。

只有出现能定位分支的实质新证据后，才讨论对应修复，以及原登记动作是否可单独重新获准。**目前重试仍停止；EXE／WebView、真实模型恢复与完整维护均未验收。**

公开资料检索仅发送官方 URL／通用 API 名。Firecrawl 的可选 ZDR 模式返回未开通，因此没有重复该调用，改由公开网页工具读取官方资料；未调用任何反馈接口或 Tavily，也未联系第三方。
