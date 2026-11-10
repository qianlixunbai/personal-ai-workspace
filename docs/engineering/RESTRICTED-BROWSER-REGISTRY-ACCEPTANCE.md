# 受限会话：保留浏览器安全注册表

2026-11-10；承接 `1388fbd` 和 `6f83cd7`。完整维护仍为 **BLOCKED / productionGo=false**。

## 真实问题与修复

`BrowserClients` 原先在所有 Runtime 启动时打开安全注册表；如果同时存在正式 `browser-clients.json` 和 `browser-clients.pending`，构造函数会删除后者。受限会话虽然关闭浏览器路由，这项启动写入仍会执行。

现在由真实原生维护会话状态选择不可用实例：不访问注册表目录、不创建锁、不读取正式记录、不删除 pending。配对、交换、撤销、列表及内部 save 入口拒绝；凭据认证返回 null、Origin 准入返回 false。普通会话继续走原文件类型、所有者、ACL 和锁校验，没有权限变更或调用者可控开关。原生本地会话 token 的正常更新仍属于允许的启动行为。

## 必要验证

最终证据：`.verification/restricted-browser-registry-20261110-r2/`。

新合成 Profile `c5c4145f9dc440c18b5e496cfaf60ee3`，operation `3fe739a7dc994e0084d4fd20b40a714b`。通过既有原生 helper 创建，使用普通非管理员宿主、全新 PostgreSQL 17.11、受限角色和现有原生迁移/重开确认。

- 先在新 Profile 生成测试专用注册记录，用真实普通 `BrowserClients` 读取并成功认证，证明合成 credential 有效；随后创建应保留的 pending 文件。固定测试 credential 只属于此合成夹具，不包含用户秘密。
- 原生迁移后的受限启动、日常重开及正常退出后，正式注册表、pending 和 lock 的名称及 SHA-256 均相同。
- 对本次 native owner 绑定的实际 loopback endpoint 发请求：原生凭据查询存储返回 POSTGRESQL；同一合成 browser credential 带合法浏览器元数据查询 storage 返回 401。未查询旧端口或其他进程。
- 正常退出确认通过；不重跑前一模块的历史/PENDING/索引场景矩阵，不调用模型、不发送用户数据。

只新编译 `BrowserClients.java` 一个产品源文件，复用前两模块已验收 class；更新现有 bootstrap fixture 和原生 probe。原生编译 0 警告、0 错误，`startup-result.json` 六个验收字段均为 true，`visualAcceptance=false`、`productionGo=false`。

候选 Runtime SHA-256：`12402A470EF4E0DD91235863685AD3C7EA4FB4D7C1E797E4EBB2E5F688418621`；Desktop DLL：`A8FE3789518B07F98D40447C7AD47EE221F2B82E603E1D66E82C10638DA99D7A`。仅为隔离验收候选，没有更新发布 ZIP。

`native-parent-cleanup.json` 确认测试注册凭据、pending/lock、临时配置和 token 均删除，forcedOwnedExit=0；删除前校验仅本次新建 Profile 中的文件哈希。`fixture-result.json` 确认本次数据库停止、无已有服务触碰、systemPortQueries=0。`run-cleanup.json` 于 2026-11-10T17:37:49.4777044Z 记录 Job active 0、Job/共享锁释放、failure=null。共享互斥、Job、450 秒上限及 Java 256 MiB/2 CPU 约束均沿用。

首次编译尝试因 PowerShell 单元素 sources 值成为 scalar、参数展开错误而失败，尚未启动数据验收，Job/锁已清理。改为显式 string[] 后通过；产品校验没有为此调整。所有失败回执保留。

`protected-inputs.json` 已核对 main、六份用户文档、旧 ZIP 和原包 Runtime 哈希未变。本模块单独提交以便回滚。全部实际写入者和外部模型边界见 [覆盖清单](WRITER-COVERAGE-AND-EXTERNAL-EXECUTION-BOUNDARIES.md)。