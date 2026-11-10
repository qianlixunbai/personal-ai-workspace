# 实际数据库设置、后端切换与独立重开验收

2026-11-10，接续 `1787cdca7167cbcf69e8a70f6da1b4fecb83966e` 的真实离线导入验收。仓库仅 `D:\IDEA\Daima\personal-ai-workspace`；开发分支 `codex/knowledge-version-compare`。**实际生产设置页面 → 原生检查/确认保存 → 明确重启 → PostgreSQL Store → 独立应用重开已通过。** 完整离线维护迁移入口尚未实现/验收，不能把本轮配置绑定当作端到端用户迁移。

## 实际组件与范围

复用 `artifacts/PersonalAiWorkspace-migration-long-path-20261110` 的原始产品文件。新增 opt-in 验收宿主引用包内 Desktop/Core/WebView2 程序集，用实际 `AssistantApp`、React、Bridge、`DatabaseSettingsWindow`、Native Gate、Java `NativeDatabaseSetup` 和 Runtime/Store 完成操作；没有 mock 入口、HTTP 或数据库。宿主用 `dotnet exec` 启动，**并非产品 Desktop.exe 的 Program/无参数入口重验**，也不是物理鼠标/键盘输入验收。

使用已有 `--open-development-workspace-once`，核对 `READY_OPEN_ONCE`，不读写真实 `last-workspace`。原生 Prepare 创建新合成 Profile；独立 PostgreSQL 17.11 仅监听 loopback 随机高端口，启动前后绑定自有数据目录、集群 system identifier、PID/启动时间和二进制身份。没有用户数据库、持久凭据、Docker、新下载、系统服务或安全设置变更。

夹具只为本轮切换准备一条 Memory 和一个 Knowledge 文档/版本：用原始 Store 创建隔离 SQLite，再调用此前已验收的两条真实 migrate API 各一次准备 PostgreSQL 目标，初始 `database.dpapi` 必须不存在。没有重跑离线导入的失败/重复矩阵；从 SQLite 到 PostgreSQL 的数据准备不是本轮网页入口完成的。

## 通过的真实流程

1. 实际网页/Runtime 就绪，读回 SQLite 中的 Memory `Synthetic cutover 中文 😀`、Revision 1，和 Knowledge 原文 `# Cutover\nSynthetic preserved source 中文 😀\n`。
2. 设置页点击“重新检查存储”，显示 SQLite 后启用“打开原生数据库设置”；通过真实 Bridge 打开生产 WPF 窗口。填入本轮临时目标并“检查”，成功后仍没有配置文件。
3. 第一次“安全保存”在真实原生确认中取消，文件仍不存在；第二次明确确认，真实 Java helper 通过 CurrentUser DPAPI / CREATE_NEW 创建配置并读回校验，窗口密码清空。当前 Runtime 仍是 SQLite，未提前切换。
4. 在原生窗口明确确认重启：旧自有 Runtime 退出，新 PID 取得连接能力，旧 bearer 返回 Unauthorized。重新读取 Memory 与 Knowledge 内容完全一致；实际状态为 `POSTGRESQL / TRUSTED_LOCAL / CONNECTED / V1_V2_READY / READY`，Finance 关闭。
5. 切换前读取两个合成 SQLite 数据库的字节哈希，与切换后比较相同。读取采用 `FileShare.ReadWrite`，只是不干扰已打开源的观察，不是来源冻结或一致性备份证明；不声称应用整个启动过程整库零写入，也不扩大到所有 sidecar/索引文件。
6. 正常退出第一应用，在独立宿主进程中再次打开同一合成 Profile。新 Runtime 仍为 PostgreSQL，关键内容一致，配置 SHA 相同；没有再次保存配置或写入业务记录。两次真实设置页均截图核对 PostgreSQL、配置可信与 schema 就绪。

| 证据 | 结果 |
| --- | --- |
| 成功目录 | `.verification/database-cutover-run5-20261110/` |
| 合成 Profile | `83278d61996a44b6a29a3c025d721c63` |
| 切换完成宿主 / Runtime PID | `13536 / 20272` |
| 独立重开宿主 / Runtime PID | `36644 / 248` |
| 两次配置 SHA-256 | `15CE434164FE00B2CD12237CCB72C9F6A9BE0ED707E52CD7BE97BEFD285B209E` |
| 程序结果 | Java 准备 exit 0；switch/reopen 各 exit 0；包装 worker exit 0 |
| 页面与收据 | `switch-postgresql.png`、`reopen-postgresql.png`、两份 `*-result.json`、`*-process.json`、`fixture-result.json` |
| 原 Runtime JAR | `6672ca01cfd6cd4926cd0a28374d77cbe4a63b1d0fb06392f1f7ff4e139e6dd5` |
| 原 Desktop DLL | `5DAFB632E86591D1A71E9EE065D9368861432F4FB7FF62A91D0C73CEF8F0D4EE` |

新增宿主源为 `bootstrap/tests/MemoryDesktopAcceptanceProbe.DatabaseCutover.cs`，目标准备为 `PostgresqlCutoverFixture.java`。既有宿主只增加一个显式分支及条件程序集引用；未设置 `PackagedDesktopRoot` 时原项目引用不变。构建仅复用本机输入、空本地 NuGet 源和现有工具链，最终宿主编译 0 警告/0 错误；原包复制件的 933 清单文件逐项哈希仍相同。既有证据目录保存 `Accept.ps1 / Run.ps1 / Worker.ps1` 与编译日志；运行使用 bounded stdin 传秘密，不写入本报告或 Git。

## 历史失败与清理

以下失败保留，不用最终成功覆盖，也没有将它们记为产品缺陷：

- 首次宿主引用旧编译输出导致 OpenOnce 符号缺失，另有 Button 名称冲突；修正为原交付程序集引用及完整类型名，重新离线生成依赖后零诊断。
- `.verification/database-cutover-20261110/` 首轮运行已准备目标，但 PowerShell `$phase` 与 `$Phase` 大小写不敏感碰撞，未启动桌面。数据库停止、密码移除、Job 0。
- `database-cutover-run2-20261110` 中 testhost EXE 在自包含包目录找不到框架，managed Main 前退出；改用本机已有 `dotnet exec DLL`，没有下载安装或更改系统运行时。数据库停止、Job 0。
- `database-cutover-run3-20261110` 实际网页已打开，但驱动漏点“重新检查存储”，等待入口超时；按真实页面规则修正，未放松产品准入。数据库停止，Job 关闭前 2 个自有成员由 Job 清理。
- `database-cutover-run4-20261110` 已通过原生检查、取消及确认保存，随后验收脚本直接 ReadAllBytes 读取仍由 SQLite 打开的文件抛 IOException，尚未重启。改为共享现有读写句柄的只读哈希观察并增加准确阶段；run5 全流程通过。原保存配置经身份核对后移除，数据库停止，Job 收尾 2 个成员。

成功轮 PostgreSQL 使用自身 `pg_ctl stop -m fast` 正常停止，日志明确 `database system is shut down`。临时初始化密码已移除；本轮合成 `database.dpapi` 经解密核对 backend/host/port/database/role/随机密码全部匹配后删除。只保留合成数据、备份和证据，不保留可用的本轮客户端配置或明文密码。

Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 与自有 Job 覆盖实际构建/准备/服务/GUI 生命周期。最终 guardian `34976`、worker `17352`，于 `2026-11-10T11:06:44.4273229Z` 关闭 Job 并释放锁；关闭前仍有 **2 个自有子进程**，由 kill-on-close 收尾，不能称全树自然退出，也不称 Java graceful shutdown。没有重新循环观察旧退出问题；没有查询被拒的 18765 系统状态、Docker 或旧进程元数据。出现锁忙的尝试在准备/启动前结束。

本轮没有审批拒绝、待审批或结果不明动作。agent-desktop 两份技能已读取，但 CLI 不在当前 PATH，技能目录无可执行文件，工具列表也未提供通用 computer-use；没有安装替代程序或用它重试此前拒绝的 Docker 诊断。本页的 GUI 证据来自上述真实应用验收宿主。

## 剩余产品缺口与下一步

[维护入口审查](MAINTENANCE-CUTOVER-ENTRY-REVIEW.md)定位了完整路径的实际缺口：来源授权仍为 `BLOCKED / UNREGISTERED_WRITERS_UNPROVEN`，维护按钮禁用，两个 migrate 没有生产调用点；两域独立事务与 DPAPI 激活缺少共同 owner 和可恢复阶段记录。因此下一项应是受信离线维护产品能力，不能把在线设置窗口拼成“迁移完成”，不能借合成来源绕过真实准入。

继续以已有 SQLite 来源/目标规则设计最小可维护 owner，先解决来源停写证明与两域协调，再接原生入口。无参数默认工作区仍无受支持的独立配置根；不得临时改真实默认记录后回滚。个人恢复、默认入口、完整维护迁移、跨机器、模型及正式发布仍未验收。

本轮新增验收和设计结论，**生产代码、Runtime/前端/native helper 和旧包未改，不重打无变化的包**。main、六份用户文档与两个受保护旧 ZIP 哈希复核见成功目录 `final-state.json`。前轮真实导入提交与本轮配置绑定形成两个实际验收模块；入口设计审查是后续方案，不是已实现的第三功能。
