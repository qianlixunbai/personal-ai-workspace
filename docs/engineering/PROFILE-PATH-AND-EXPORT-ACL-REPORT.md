# Profile 路径绑定与导出私有权限修复

日期：2026-11-09。范围仅为两项既有阻塞及直接受影响的路径调用者；不增加产品功能，不发布新安装包。

## 已证实的原因

1. 原 `PROFILE_PREPARE` 失败不是访问拒绝。系统将 Known Folder 下的逻辑 Profile 路径解析到 Codex 包缓存的物理目录。逻辑与物理句柄的卷序列号和完整 128 位文件 ID 相同，API 成功、无重解析点，但原严格字符串比较拒绝了不同路径。物理应用目录、Profiles 和叶目录均具有当前用户所有、受保护且规范的私有 ACL。
2. 原导出失败独立发生于 `SetSecurityInfo`。原保护函数同时设置 Owner 与 DACL。在仓库目录中新建的合成文件上，当前进程的 WRITE_OWNER 打开请求被拒绝（Win32 5），仅设置 DACL 的新实现成功，所有者不变。原历史暂存文件已经清理，本轮没有假称重新检查了那个文件。

这说明存在系统路径映射，但不确认实施映射的具体 Windows 组件；当前探针进程并无包身份。

## 实现与边界

- 新增 `BindRoot`：仅从 Known Folder、固定应用目录及合法 Profile ID 派生逻辑根；保留逻辑祖先和原叶句柄，严格固定实际祖先链，检查实际应用目录、Profiles、叶目录 ACL，比较完整文件身份，并在两侧保持固定期间重新打开逻辑名称核验。
- 普通 `OpenDirectory` 和 `HoldDirectories` 仍严格比较路径并拒绝重解析点。没有对任意调用者路径放开重定向。
- 新建后使用实际绑定根。桌面生命周期、原生辅助程序及恢复目标保持绑定；原有字符串 API 只用于路径查询，不成为永久身份凭证。
- 恢复登记、工作区选择仍独立使用原有应用位置，不能由重定向 Profile 的祖父目录推导。备份意图的生产分支保持实际 Profile 绑定，合成测试分支仍留在独立测试目录。
- 导出保护通过现有写入句柄的 `ReOpenFile` 获取同一文件对象，只申请 READ_CONTROL / WRITE_DAC，先核对所有者，再设置受保护的仅当前用户 DACL，最后严格核对实际所有者和 DACL。保护成功前不写正文，不覆盖已有目标。
- NativeBootstrap 重建后将精确 EXE/DLL 哈希同步到桌面、Java gate 和打包检查三处；未删除或降低哈希验证。

## 验证

- Release NativeBootstrap 构建成功，两次重建的 EXE/DLL 哈希一致；打包脚本的 `Assert-DevelopmentBootstrap` 通过。
- 39 项聚焦桌面检查通过：路径形状与完整身份差异拒绝、私有 ACL 策略、全新真实重定向 Profile 创建/重开/子目录、固定期间改名拒绝、原生辅助程序返回相同实际根、恢复登记与备份意图回归，以及导出的私有权限、失败清理、取消和不覆盖。
- 另 1 项既有原生准入集成通过：新 Profile、真实 native provision / launch admission、环境变量伪造拒绝、合成子目录不安全 ACL 拒绝、junction 拒绝。
- Java `TrustedDatabaseConfigurationTest`：7 项通过；仅一个限定 JVM，无模型调用。
- 6 份既有用户文档 SHA-256 前后一致；main 未变；未推送。结束检查无本轮测试辅助程序、Java 或 Ollama 进程遗留。

证据目录：`.verification/path-binding-export-acl-20261109/`。

- `focused-final.trx`：39 项通过。
- `native-admission-final.trx`：1 项通过。
- `profile-fixture-final.json`：全新合成 Profile 的逻辑/实际路径与成功状态。
- `java-gate-regression.log` 及复制的 Java 测试报告：7 项通过。
- `baseline.json`：基线提交、main 和用户文档摘要。

保留早期失败：测试构建最初缺少仓库既有的 RuntimeLifecycleProbe 编译开关；继承 ACE 的测试样本构造错误已修正；备份意图的旧路径假设由关联回归发现并修正；新 helper 的旧哈希校验曾正确拒绝启动，更新为实际审核构建哈希后准入通过。没有为失败测试放宽权限或绕过检查。

## 未验收与回滚

- 没有重新打包或发布预览 ZIP，已有预览包未更新；没有重复完整 WPF/WebView 产品启动、模型、跨机器或 PostgreSQL 恢复全流程。
- 未对所有竞态调度进行故障注入。已验证持有绑定时改名失败；完整文件身份复核和失败关闭保留。原有安全模型仍不隔离以同一 Windows 用户运行的恶意进程。
- 没有更改系统设置、既有 Windows/仓库目录 ACL、用户数据或既有服务。权限负例只操作本轮新建的合成对象，失败现场与合成 Profile 保留。
- 回滚基线标签：`workspace-before-path-binding-export-acl-20261109`，指向 `bdbd9fae791991d0d42b2e0397303c6f1df4f951`。修复单独提交，可用逆向提交撤销代码；不要用清理或硬重置删除用户未跟踪文档，也不要将 Profile 数据当作代码回滚对象。

精确 helper SHA-256：EXE `d1166043c27929957e2c8597c6fd6bbe8a369ad1cfa45791d7dd665506983166`；DLL `9dafed0b1379e9f013d6eb83c9ac5518e611c0e15faa3557991a0c4341029698`。
