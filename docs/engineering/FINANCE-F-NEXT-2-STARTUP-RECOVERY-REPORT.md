# F-next-2 Development-only PostgreSQL 启动恢复

2026-10-23，Asia/Shanghai。开发启动恢复及实际新包 EXE 验证通过；**真实 Windows 重启验收 PENDING，不是 Production GO**。

## 范围与修复

基于已交付 `codex/finance-f-next-2 @ fe331f35ed18766c06468ff01b62252d91e68f31`，新建 `codex/finance-f-next-2-startup-recovery`。原入口只执行 Desktop EXE，数据库停止后没有恢复路径。用户此前自行启动原集群后 Finance 正常，属于用户反馈，不能代替本轮执行证据。

本轮只新增开发脚本、文件安全检查辅助源码、一个聚焦验证 harness 和本报告。未修改 Java/Finance/Desktop/Frontend 业务代码、迁移、公共 API 或持久化格式，未展开 F-next-3。Desktop 重新 publish 仅为内嵌新目录的 Runtime 来源 manifest。

生成的 `Open-Synthetic-Finance.cmd` 调用同目录 PowerShell 启动器，目标固定为原合成集群：

- `127.0.0.1:55423`，PostgreSQL 17；`D:\PersonalAiWorkspace-Fnext1\939355624cdf\pgdata`。
- 原 Profile `86705e85cb5b4a22869dd34849e0c07b`，原 Finance/Workspace 独立数据库和角色。
- 先核对当前 SID、root/pgdata 的 Win32 目录身份、当前用户私有 ACL、数据树权限、无 reparse/文件硬链接和外部 tablespace，再核对固定配置哈希、PostgreSQL bin/lib/share 来源清单和版本、control system identifier。复用 `WindowsProfileSecurity` 目录链 pin；不修改 ACL。
- root 内独占启动锁串行化双击。锁和本次启动日志只在不存在时 CREATE_NEW；日志位于原私有 root。配置、凭据及二进制读取持有防写/删除句柄。
- 端口已监听时，必须匹配 PID 文件、loopback 监听、进程路径/参数、SID 和启动代次；不向非预期监听者尝试登录。
- 未监听时，必须确认 `pg_ctl status` 返回停止态、没有该集群进程。单独通过 Windows SCM 固定服务记录、services.exe 父进程和创建时间核对原系统服务进程树，只读区分，不管理该服务。其他状态不明则拒绝。
- stale PID 仅在目录/端口字段匹配且对应进程确实不存在时，交给正常 PostgreSQL start 自行处理；脚本不写入、删除 PID，也不停止现有 PostgreSQL。PID 被复用等不确定状态直接拒绝。
- 仅正常执行原二进制 `pg_ctl start -w -t 30`，不注册服务、不变更系统启动项。启动后仍重新检查进程归属，用固定只读 SQL 比较 system identifier、实际目录/配置、主版本、地址/端口、两个数据库 OID 与原合成 marker，然后才请求启动 EXE。
- 使用原私有 `fixture.dpapi` 的 CurrentUser 解密，不提示或重设密码；密码仅进入单个 psql 子进程环境。清除继承的 libpq/server 环境，SQL 强制只读并设置连接/语句超时；子进程、CIM 查询和启动等待有界。stdout 只发布固定状态，原始数据库错误不回显。
- 打包时以既有 Finance 数据集指纹算法核对原 binding；启动入口 pin 原 binding、两个 Profile DPAPI 文件。实际 Desktop/Runtime 继续执行原 Native Gate、DPAPI、数据集/用户身份核对。没有自动新建 Profile、重绑或 SQLite 回退。

不执行 initdb、CREATE/DROP DATABASE、数据库密码覆盖或系统服务配置。冻结 Finance 仓库保持 `zh-cn @ c6c87a0627b746e70a45b1c7896f63defd76a810` 且干净。原 8 个未追踪 B4A6 文件保留且未暂存；没有运行它们或访问 B4A6 数据库/角色。

## 本轮实际验证

原始日志位于忽略目录 `.verification`，不把 Profile、凭据或日志提交到 Git。

| 执行 | 实际结果与边界 |
| --- | --- |
| `test-synthetic-finance-recovery.ps1`，最终日志 `fnext2-recovery-tests-final.log` | PASS；对现有 55423 运行态只读核对；对独立、原已停止的 F-next 合成 fixture `56f81d864807:55422` 正常恢复，再次调用复用同一 postmaster，独占锁竞争拒绝。该 fixture 的原 DPAPI、私有目录、control、停库状态及与已审查默认配置相同的哈希先核对；无建库/初始化。只正常停止本 harness 启动并再次验证身份的 fixture；55423 始终保持原 PID |
| 同一 harness 的拒绝分支 | PASS；真实缺失路径、真实 TCP 非 PostgreSQL 监听占用；注入预期目录 identity、配置/二进制 hash、control system ID、数据库 OID 不匹配均拒绝。后几项是期望身份注入，不是改坏当前集群或真实交换数据库；未做物理数据文件损坏、ACL 攻击或 stale PID 故障注入 |
| 最终包 CMD / 实际 EXE，`fnext2-recovery-product.log`，退出码 0 | PASS；真实 CMD、Native Gate、WPF/WebView2/React/Java/PostgreSQL。UIA 核对 Synthetic Alice 和精确余额，HTTP 核对 READY/3 个账户；Runtime 进程确实使用新目录 JAR。原 Memory/Conversation/Knowledge 实际读取快照在正常托盘退出/重开后相同；原 binding 与两份 DPAPI 哈希不变 |
| 同一产品综合流的退出 | PASS；正常托盘退出释放 Runtime 18765；合成 55423 postmaster 与系统 PostgreSQL 服务 PID/状态不变；最终再次通过新 CMD 打开，留给用户查看。不是物理鼠标托盘操作或真实重启 |
| 最终包来源审计 | PASS；核对所有 payload 哈希、实际 Desktop DLL 内嵌 manifest 的 SHA-256、manifest 每项受信依赖及新目录绝对 JAR 路径；JAR/Frontend/NativeBootstrap 沿用原交付字节，无共享 target JAR 依赖 |
| 构建 | 实际执行定点 Desktop Debug Profile publish；原 Java JAR 和前端复用，未重新构建或声称通过 Java/Frontend 全套 |

中间失败未计为 PASS：Add-Type 的 nullable 编译上下文、inet 地址 CIDR 文本、系统服务 SID 不可读、Windows pg_ctl 输出管道 EOF、启动锁诊断，以及本地 UIA harness 的 UTF-8/外部附加进程退出码问题，均已分别定位处理。隔离 fixture 启动后的管道误报没有通过改业务行为或强杀进程掩盖；先重新核对身份，再正常停止该测试进程。最终启动代码经上述最终 harness 和实际产品流执行通过。

未执行 Windows 重启、真实掉电恢复、完整安全/IME/压力矩阵或 Java/Desktop/Frontend 全套；业务代码未改，不重复运行历史 F-next-2 分页等矩阵。

## 交付与重启后验收

新目录：`D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-finance-fnext2-recovery-development-20261023-r1`。原 F-next-2 包未覆盖；中间候选不作为交付入口。

同机 Development-only，依赖该 Windows 用户原私有 Profile/DPAPI、固定本地 PostgreSQL 17、JDK 21、PowerShell 7.4+、.NET 10 和 WebView2。不要移动目录或替换 pin 的配置/二进制。`BUILD-INFO.json` 提供 payload、内嵌 manifest 与来源摘要；它是可检查的构建记录，不充当发布者签名或运行时重新授信入口。

**下次真实 Windows 重启后只需双击新目录的 `Open-Synthetic-Finance.cmd`**，随后在 Finance 核对 Synthetic Alice、3 个账户、CNY `9007199254740993.30`，并查看原 Memory/Knowledge/Conversation。从托盘退出再打开后仍应保持；数据库不会随 Workspace 退出被停止。不需要输入密码。若显示 STOP，保留诊断代码及现场，不删除 PID、不重建或重绑。

以交付回复中的最终 commit 和正常 Push/远端 SHA 核对为准；未 merge main、force push、amend、tag 或创建正式 Release。
