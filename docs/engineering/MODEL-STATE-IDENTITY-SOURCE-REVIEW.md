# model-state 两种身份来源的只读核对

2026-11-10，从 `6d4bb7b` 接续。**新增证据解释了两条身份链为何可能不同，但仍未证明原失败的实际原因；没有修改校验或重试原操作。** 本文不是新的执行许可。现有完整维护状态仍为 **BLOCKED / productionGo=false**。

## 原动作及已知结果

原准确目标为 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2\model-state`。停止的动作是在这个合成 Profile 中构造 `ModelStateStore`，准备本地阻断登记。`.verification/knowledge-reading-desktop-20261110/seed-run-errors.log` 明确记录构造函数第 93 行的根目录验证，在第 496 行抛出 `Invalid model state ownership or type`。

状态为**产品校验拒绝，结果已知**，不是自动审批拒绝，也不是未知执行。`.lock` 后续准备与 `armGuard` 尚未执行；EXE/WebView 未启动。本轮只读取仓库内保存的 `Seed.ps1`、`SeedReading.java`、该错误日志、`prepare/owned-profile.json`、产品源码及包内 Java `release` 文件，没有访问原 Profile 的属性、账户、ACL 或文件内容。

## 新核实的事实

| 位置 | 可核实的事实 | 不能由此推出的结论 |
| --- | --- | --- |
| `ModelStateStore.java:88–93` | `account` 来自 `lookupPrincipalByName(System.getProperty("user.name"))`；Windows 分支由“没有 POSIX view、存在 DOS view”判断，然后创建目录并验证。 | 没有原失败时属性视图的采样，不能只用目录名字证明具体分支和身份。 |
| `ModelStateStore.java:491–496` | Windows 原生校验先返回，随后执行 `noLinks`、Java Basic 属性及 owner 比较。类型错误与 owner 不匹配共享一个异常。上述两个产品文件从原 `a9cbc5d` 到本轮保持相同。 | 不能把第 496 行直接等同于 owner 不匹配，也不能把它归因于 WebView。 |
| 随包 `runtime/java/release` | Oracle Java `21.0.7+8-LTS-245`、Windows x86_64。原 Seed 脚本使用同包 Java，代码中没有显式 `-Duser.name` 或 `USERNAME` 改写。 | 没有记录继承的环境／启动属性或实际有效令牌，不能据此断言它们一致或不同。 |
| OpenJDK `jdk-21.0.7-ga` 的 `java_props_md.c:585–603` | Windows 默认 `user.name` 优先取非空 `USERNAME` 环境变量，缺失时才调用 `GetUserNameW`。这是与随包版本对应的公开上游源码证据。 | 它说明可能的来源差异，不能替代 Oracle 二进制的运行时取值证明，更不能证明原进程继承了错误值。 |
| OpenJDK 同版本 `WindowsUserPrincipals.java` | 命名查找调用 `LookupAccountName` 取得 SID；`User.equals` 比较 SID 字符串，不是显示账户名。 | 不能仅以显示名带域前缀或格式不同解释失败；仍缺双方实际 SID。 |
| `WindowsPrivateAcl.currentSid/privateAcl` | 先取当前线程令牌；只有 `ERROR_NO_TOKEN` 才取进程令牌，读取 `TokenUser` 并用 `EqualSid` 对照 owner。`OpenAsSelf=true` 决定打开令牌时的访问检查上下文，不把返回的线程令牌替换成进程令牌。 | 尚无证据表明原线程存在 impersonation 或令牌与环境变量不同。 |
| `NativeAcl.operate` | `Pins` 只覆盖本次原生操作，返回时关闭；Java 属性读取发生在其后。 | 两次检查并非同一持有句柄上的单次原子观测。不能排除时序差异，也不能把这种可能性声称为已发生的竞态。 |

公开一手资料：[OpenJDK 21.0.7 Windows 默认属性](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.7-ga/src/java.base/windows/native/libjava/java_props_md.c)、[Windows principal 查找与相等性](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.7-ga/src/java.base/windows/classes/sun/nio/fs/WindowsUserPrincipals.java)、[Microsoft OpenThreadToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openthreadtoken)、[Microsoft GetUserNameW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getusernamew)。外部查阅仅含公开 API／源码名称，没有发送私有路径、任务或数据。

## 结论与剩余边界

现在能明确区分：Java 这一检查使用“可来自环境变量的用户名 → 命名查找的 SID”，原生检查使用“当前有效令牌 → TokenUser SID”。它们采用不同来源；**来源不同并不证明本次值不同**。原有错误也仍合并了类型与所有权两个条件。

因此没有准备或提交身份检查修复，没有将额外的 Java owner 检查删除、改为放行或用环境变量覆盖令牌身份。此处直接影响模型选择／执行阻断状态的完整性，不是无关测试噪声。原目标、Profile、启动器、身份、ACL 和系统安全设置保持原样；没有换样本重试。

下一步若获准对原样本和正常执行上下文进行精确只读核对，应区分根目录真实类型、Java 命名 principal 对应的 SID 与原生有效令牌 SID，并说明读取时点。现有历史缺少这些值，当前进程的值也不能证明原进程当时的值。只有明确差异和修复边界后，才能单独讨论准备动作与桌面验收是否可恢复；本轮没有要求休息中的用户操作或扩大执行范围。
