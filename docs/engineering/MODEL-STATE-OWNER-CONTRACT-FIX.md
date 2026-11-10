# Windows 模型状态的有效账户绑定修复候选

2026-11-10，接续 `34accfc`。**已完成两个生产文件的最小补丁、194 个 Java 产品源编译和一个独立纯 SID 契约检查；没有运行任何 Profile 的产品构造／准入／登记，也没有部署、打包或启动 EXE。** 这是经过有限独立验证的源码候选，完整 EXE／WebView、真实模型恢复与维护继续 **BLOCKED / productionGo=false**。

## 身份契约依据

契约是“已准入 Profile 中，当前 OS 执行账户拥有自己的私有状态”，没有发现允许另一指定账户代管该状态的配置合同：

- `ADR-015 §F` 要求 Runtime-owned、owner-only 的目录／状态／pending／lock／guard，且路径由 Runtime 启动配置决定；配置路径不是授权另一个 owner。
- `NativeBootstrap/Win32.cs:31` 通过 `WindowsIdentity.GetCurrent(TokenAccessLevels.Query)` 获取主体，`:43` 将令牌传给 `GetUserProfileDirectoryW`；不是从 `USERPROFILE`、交互登录用户名或 Java 属性推导账户。
- `TrustedNative.cs:33–50` 拒绝提升的 Profile 运行，以该主体确定根路径和 ACL；`WindowsProfileSecurity.cs:164` 要求 owner 精确等于此 SID。
- Java `WindowsPrivateAcl.currentSid/privateAcl` 原本已经采用当前有效 TokenUser 和 `EqualSid`。SYSTEM／Administrators 可以出现在既有可信 DACL 项中，但**不能代替当前账户成为私有对象 owner**。祖先目录的 OS 身份规则是另一条既有规则。
- `WindowsNativeGate` 的 Bootstrap 固定来源、Profile／路径校验与私有边界检查保留。子进程不被假定继承父线程的模拟令牌；若两者与 Profile 不一致，原有准入仍须拒绝。

因此 Windows Store 额外依赖 `System.getProperty("user.name") → 名称解析` 并不表达一个独立、受信的期望主体，反而可能与上面的规范主体冲突。修复没有将“当前采样 owner 恰好等于 token”本身作为允许条件，也没有新增用户指定 SID／用户名或可配置 owner。

## 具体补丁与权限边界

| 文件 | 改动 |
| --- | --- |
| `model/ModelStateStore.java` | Windows 分支在原生准入后捕获不可变 `OwnerIdentity`，不再建立用户名 principal；目录／文件创建及每次完整校验传入同一个绑定。Java 类型检查后仍做第二次 owner 检查，改用原生句柄读取与 SID 比较。非 Windows principal／POSIX／ACL 分支保持。 |
| `security/WindowsPrivateAcl.java` | 增加只读获取当前主体的公共工厂、不可变 SID 快照及绑定重载。每次绑定操作开始与结束都要求实际有效主体仍等于初始 SID；末尾 owner 检查仍使用 `GetSecurityInfo(OWNER)`、`IsValidSid` 和 `EqualSid`。原有 `privateAcl` 的 owner 谓词提取复用，DACL 条件未改变；其他调用方的未绑定接口沿用原行为。 |

规范身份取得仍复用现有、未修改的 API 路径：[OpenThreadToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openthreadtoken) 请求 `TOKEN_QUERY`，有模拟令牌时取线程主体，只有 `ERROR_NO_TOKEN` 才调用自身 [OpenProcessToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openprocesstoken)。`OpenAsSelf=true` 控制打开令牌的访问检查，不把线程主体替换成进程主体。权限错误、匿名令牌、读取／验证失败均拒绝，不按用户名、管理员组或其他令牌放行。

[GetTokenInformation(TokenUser)](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-gettokeninformation) 返回的 SID 先经 `IsValidSid`；冻结的是完整 SID 字节副本，比较后续有效主体是否仍精确相等。对象 owner 的比较使用 [EqualSid](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-equalsid)，不按域前缀、显示名、登录会话名或组成员资格判断。相同 SID 的不同令牌／登录会话仍属于原有 OS 账户边界，未引入令牌实例级授权。

**接受条件有明确变化**：如果 JVM 用户名与真实主体不同，旧 Windows 分支会额外拒绝；候选只在完整原生 Profile／类型／所有权／DACL 条件满足、且主体仍等于固定 SID 时继续。它不是 owner 等于“任一账户或管理员”的 OR 放行，也没有省去末尾 owner 检查。固定 SID 防止 Store 随后跟着另一个线程主体重新绑定；获取失败不退回旧用户名或允许访问。

路径／reparse／磁盘类型／文件身份、私有 DACL、单 writer 锁、原子发布、guard、请求身份、防重放与后来数据保护均保留。没有 `SetSecurityInfo`、令牌修改或 ACL 修复。各次系统调用仍是顺序观察，不承诺抵御恶意同账户／管理员的任意竞态，也没有把末尾 owner 查询称作全程原子快照。新绑定操作若中途失败，已有的“可能已创建但不得假定事务回滚”语义仍适用。

本次**仅修 Windows**。非 Windows 的原用户名 principal／POSIX 模式行为未重写，也未验收，不能宣传为已经解决跨平台有效 UID 身份问题。产品文件格式、HTTP／Bridge 合同、Bootstrap、配置和安装均未改变。

## 独立验证与未执行范围

证据目录：`.verification/model-state-owner-contract-20261110/`。

| 证据 | 本次实际结果 |
| --- | --- |
| `compile.log`、`compile-errors.log` | 194 个实际 Java 产品源和一个纯逻辑检查编译成功；沿用 75 项已有内部 API／弃用警告，无产品启动。 |
| `OwnerIdentityCheck.java`、`identity-contract.log` | 一个综合契约检查通过：原始数组被改变后绑定不变；同 SID 的新副本接受；后来不同主体、管理员 SID、缺失／无效 SID 拒绝；拒绝不重新绑定，诊断不输出 SID。全部为合成字节，无令牌或文件系统采样。 |
| `class-load.log`、`result.json` | 检查执行 classpath 仅新编译 class，不带 Runtime 依赖；没有加载 `WindowsNativeGate`、`NativeAcl`、`ModelStateStore` 或 JNA。源码前后哈希稳定。 |
| `cleanup.json` | 22:44:19Z 自有 Job 0，Job／原共享锁已释放，failure=null。沿用 kill-on-close、768 MiB 单进程／2 GiB Job；编译限 60 秒、纯检查限 10 秒。 |

**未验证**：新增绑定 API 的真实原生调用、文件 owner／DACL 故障路径、模拟令牌切换、任何 ModelStateStore 构造／登记、EXE／WebView 或模型恢复。没有通过替代 Profile、身份、工具或夹具试跑原受拒动作；没有重跑旧测试组／包，旧包仍不含本补丁。这些缺口因本轮执行边界保留，不以纯逻辑 PASS 冒充完成。

## 原事实与后续动作

[22:33 的只读采样](MODEL-STATE-READONLY-OBSERVATION.md)表明当前值与 owner 拒绝分支相符；旧时刻身份、类型和 FileID 缺失，仍不能证明旧根因。原准备已经执行至目录准备返回，随后根目录校验拒绝；后续 `.lock`、`armGuard`、EXE 未执行。新源码没有改变这些历史事实。

下一步需先审阅候选的身份契约与权限等价性，再确定具体原生验证／运行范围；本消息及源码提交都不构成原流程重试许可。将来若获准，仍须核明当前目标、未执行步骤及正常审批，不能重放已完成的合成导入或把启动当成无副作用读取。此次不向休息中的用户发问。

main、六份用户文档、真实数据／凭据、默认配置、旧包和其他拒绝边界保持；只做本地可回滚提交，未推送。请求的模型／thinking／service tier 无法通过当前执行接口切换或核验，不声称已满足该运行档位。
