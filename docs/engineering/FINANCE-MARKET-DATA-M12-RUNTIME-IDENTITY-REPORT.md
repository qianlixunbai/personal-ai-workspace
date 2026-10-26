# M1.2 — 运行版本排查与启动冲突

2026-10-27，Asia/Shanghai。基线 `fba5d295c3f5145699dff63342d8d87928091e93`，`codex/finance-market-data-m12`。用户复验时的进程已经退出，开始检查时没有 Workspace/Java 进程及 18765 listener，故不能证明当时究竟运行了哪个 JAR，也不能把本次发现的冲突确定为那次失败的根因。

## 证据和修复

- 旧 M1.2 fix 包 JAR SHA-256 `be7557676e549f212ff28cf27e2314d3643cb972d21932daa44d6ca50b244cbc`。反编译的 `MassiveClient` 对 FX 使用 12 位上限，可产生 `FOREX_BARS / PRICE_SCALE`。
- FX Precision 包 JAR SHA-256 `412eab8dbf32af1664e7911d46dd1ed7ab230631255590063e35da54967c2a0f`，与 BUILD-INFO、当前 target JAR 一致；其 `MassiveClient` 字节码在 FX 时跳过 `PRICE_SCALE` 分支，与源码一致。生产代码中唯一生成 `PRICE_SCALE` 的分支要求非 FX，且 stage 在该分支前由同一 FX 判断确定；跨层枚举、测试和 UI 可传递或显示此代码，但不会自行生成它。
- FX Precision 启动器启动本包 Desktop；其内嵌的受信 manifest 指向本包 JAR，Desktop 对该文件及 Java 来源校验并持有 pin 后，以 `-jar` 启动子进程。Runtime owner 在 18765 已被占用时拒绝启动，不主动复用任意监听器。
- 旧/新包对同一 Profile 使用同一单实例 mutex。原 `Program` 在已有实例时发送激活信号并退出，可能使用户从新启动器看到旧窗口及旧 Runtime；这是有代码和旧 JAR 字节码支持的可复现错配机制，不是对已结束的用户复验进程的事实认定。
- Profile Debug 启动现在遇到已有同 Profile 客户端时显示冲突并退出，不静默激活旧实例；保留原锁及 Runtime 准入。托盘菜单仅在所持 Java 已就绪且仍存活时显示经过 pin 校验的 JAR SHA-256，否则显示未就绪。没有修改 Java 行情精度、Finance 账本、许可、凭据格式或历史包。

## 本轮实际验证

- `FinanceMarketDataTest` 聚焦测试通过；`SelectionAndLifecycleTests.SingleInstanceSignalsPrimaryAndReleasesMutexOnShutdown` 通过。第一次 Maven 命令因 PowerShell 拆分属性参数而失败，正确引用后重跑通过。没有新增测试或运行完整套件。
- 新独立包 CheckOnly 通过；旧 fix、FX Precision 和新包的 BUILD-INFO payload 哈希均匹配；FX Precision 与新包记录的相关源文件哈希匹配当前源码。新包实际 JAR 通过现有 `package-windows.ps1 -AuditRuntimeJar` 审计。
- 受控合成 Profile 实际运行：旧 fix Desktop 的 Java 子进程持有 18765；新 Desktop 显示冲突并退出，旧子进程仍持有端口。正常退出旧版后，新 Desktop 启动本包 Java，持有 18765，托盘显示上述 SHA-256；正常托盘退出并释放端口。另通过实际新 CMD 启动器验证 Desktop、子进程、端口、托盘哈希和正常退出。未发起行情请求或读取真实 API Key；这不是用户物理操作或真实 USD/CNY 验证。
- 验证结束无 Workspace/Java 进程、18765 listener；冻结 Finance OS HEAD `c6c87a0627b746e70a45b1c7896f63defd76a810` 且工作树干净。未运行全量套件、真实行情或整机重启。

## 新开发包与复验

启动器：`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-market-data-m12-runtime-identity-development\Open-Workspace-Finance-Market-Data-M12-Runtime-Identity.cmd`。

包为同机 Development-only，不覆盖旧包。BUILD-INFO 真实记录构建时基线 `fba5d295c3f5145699dff63342d8d87928091e93`、`sourceDirty=true` 及实际源码和 payload 哈希；Java JAR 沿用此前已审计的 FX Precision 字节，并非本轮重新编译的干净提交。包依赖既有 JDK21、.NET10、PowerShell7.4+、WebView2、受信 Profile 和数据库。

1. 从旧客户端托盘选择“退出”，再打开新启动器。若提示冲突，先退出已有客户端，重新打开新包。
2. 右键托盘图标，确认“运行中 Java JAR SHA-256”为 `412eab8dbf32af1664e7911d46dd1ed7ab230631255590063e35da54967c2a0f`；未就绪时先等待工作区连接。
3. 在 Finance 中主动刷新 USD/CNY。若仍失败，只记录安全的 Stage / Reason；不要发送 Key、原始响应或真实价格。当前源码及已审计的新 JAR 不应对 FX 产生 `FOREX_BARS / PRICE_SCALE`，但真实 Provider 结果仍待用户复验。
