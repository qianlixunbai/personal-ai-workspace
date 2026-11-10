# 离线迁移、退出观察与开发包交接

后续更新（2026-11-10）：已用本页交付包的原始 Runtime 完成独立 PostgreSQL 的两条真实离线导入 API 验收，生产文件和旧包未改。成功提交、实际中途失败回滚、源/备份保全、重复拒绝及 Store 重开通过；完整维护产品入口、后端切换和默认入口仍未验收。报告复制失败与仅文本导出恢复分别保留，详见 [后续真实导入报告](OFFLINE-POSTGRESQL-IMPORT-ACCEPTANCE.md)。下文仍保留本页原交接时点的结论。

2026-11-10。仓库仅 `D:\IDEA\Daima\personal-ai-workspace`，开发分支 `codex/knowledge-version-compare`，本轮起点 `e62a04015b6c6899a335d7ade25ae90a4f36477b`。未发现仓库或已检查祖先目录中的 AGENTS.md，仓库也没有 `.agents` 目录。读取 STATUS、正常网页验收、相关存储/维护实现后推进，不接触同项目 Finance OS 或独立 KAG。

## 交付结果

1. **离线迁移长路径修复**：`5d86d409019f21308714f8854c011c0282428bdc`。Workspace 与 Knowledge 的源冻结、只读 snapshot 和 native backup 目标统一使用已有长路径规则。原版 4 个组合均未能完成备份；修复后 4 个组合均保留完整备份并到达刻意不可用的目标边界，源哈希及全部表/行不变、知识原文一致、原备份不覆盖、失败释放写锁。目标是无网络 DataSource 桩，**不代表 PostgreSQL 导入成功**。完整证据见 [迁移报告](OFFLINE-MIGRATION-LONG-PATH-ACCEPTANCE.md)。
2. **退出疑点获得新证据**：`31b877616779dfa5c8e2e80c6a319e7b4b29958f`。只对未修改的已验收程序做一次启动/托盘退出观察，没有保存记忆或重开。桌面退出码 0 后 Job 活动成员由 5 降为 2，约 3.05 秒归零，guardian 随后关闭空 Job。没有持续残留证据，未修改产品关闭逻辑。前次三次即时 Job 强制收尾仍是有效历史事实；Runtime Owner 仍使用自己的终止机制，不称 Java graceful shutdown。见 [退出报告](DESKTOP-EXIT-DRAIN-ACCEPTANCE.md)。
3. **新开发包完整交付**：以下 ZIP 统一包含修复后的 Runtime 和重新嵌入信任 pin 的 Desktop；旧包全部保留。源码生成包后没有重新启动产品、重复保存/重开或追加全套测试。

| 交付项 | 精确信息 |
| --- | --- |
| 新 ZIP | [PersonalAiWorkspace-migration-long-path-20261110.zip](../../artifacts/PersonalAiWorkspace-migration-long-path-20261110.zip) |
| 解压目录入口 | [PersonalAiWorkspace.Desktop.exe](../../artifacts/PersonalAiWorkspace-migration-long-path-20261110/PersonalAiWorkspace.Desktop.exe) |
| 构建源码 | `31b877616779dfa5c8e2e80c6a319e7b4b29958f`（产品修复 `5d86d409019f21308714f8854c011c0282428bdc`） |
| ZIP 字节数 / SHA-256 | 384,687,471 / `95D90714C0AE2208F73FDABD63C2A899F94AC79EC907D2AE8FE5DF631706E70C` |
| 新 Runtime JAR SHA-256 | `6672ca01cfd6cd4926cd0a28374d77cbe4a63b1d0fb06392f1f7ff4e139e6dd5` |
| 新 Desktop DLL SHA-256 | `5DAFB632E86591D1A71E9EE065D9368861432F4FB7FF62A91D0C73CEF8F0D4EE` |
| 文件 / ZIP 条目 | 933 payload / 934，全部流读取核对长度与 SHA-256 |
| 相对上一包变化 | Core.dll、Desktop.dll、Desktop.exe、README.txt、Runtime JAR 共 5 个；另 928 个 payload 字节不变 |
| 固定输入 | 前端、Java/JNA 工具链、NativeBootstrap helper 字节不变，无下载或安装依赖 |
| 实际内嵌来源 | 只读 PE 资源验证 420 个 pin 与包内文件一致；3 个受影响类型及其嵌套类共 9 个 class 与本次 Maven 输出完全一致 |
| 旧包保护 | normal-web-verified ZIP SHA 仍为 `ACE6C4BFC53ABB4CE762B5C89882A6F9AAD709D3864A1ADB7D144F0B02F8C11C` |

`mvn -o package -Dmaven.test.skip=true` 成功、Runtime JAR audit 成功、必要 Desktop publish 无诊断；没有把跳过测试说成测试通过。包内清单明确此包完整 EXE 验收未重跑，历史完整正常网页保存/重开仍属于 `64b33b2991acb407d6887040ec1fe7ae902b0afb`。新包只作 Development-only / unsigned，不是 installer、Release 或 Production GO。

## 资源、权限与断连处理

全部新合成数据在原生 Prepare 新建 Profile `2d0e6baed0a14bf9a1da7b9bf4495182` 的受准入目录中；保留来源、失败样本及备份，无用户数据迁移或配置切换。首个迁移探针在调用迁移前被自身 `ORDER BY rowid` 错误中断，修正后使用不同新目录；原现场保留。

Session 1 `Local\Dot_KAG_Workspace_HeavyRun` 覆盖每次实际构建、准备、JVM 与 EXE 生命周期。迁移验证各阶段清理前 Job 成员为 0；产品退出观察清理前为 0。最终打包 guardian `20104` / worker `11744`，worker 退出码 0，Job 关闭前还有 **2 个构建子进程**，由 kill-on-close 清理；`2026-11-10T10:18:58.6266678Z` 释放 Job 与共享锁。不能把这两个构建进程的清理描述为自然退出，也不能与产品观察混同。

收到包装 worker 完成后，下一次执行通道出现 `exec-server transport disconnected`。立即停止动作，恢复后的首次调用仅只读核对 Git、worker/cleanup/包结果和构建日志，确认已执行成功并收尾；没有重复构建、重放写入或重复提交。没有未确定结果。

本轮无自动审批拒绝、无待审批动作、无仍运行的自有服务/guardian。没有重新查询被拒的 `Get-NetTCPConnection 18765`，没有换 API 查同一被拒状态，没有读取旧进程元数据、旧 Crashpad 或此前被拒私有目录。端口状态仍为 NOT_QUERIED；只让既有产品执行自身准入。未更改 EFS、既有 ACL、沙箱、注册表、系统/网络策略，未操作 KAG 的端口或进程。

## 下一会话的真实边界

- **默认入口仍未实际验收**。源码中的无参数默认选择直接绑定 OS Known Folder 私有应用目录 `last-workspace`；现有 open-once 不读写该记录。未找到受支持的独立配置根。没有改真实默认记录后回滚，也没有覆盖 LOCALAPPDATA 或添加任意路径绕过。若决定做隔离默认配置能力，应先明确产品价值、跨 C#/Native/Java 路径绑定及选择器/重启语义，再做最小实现；不能把内部函数测试当作无参数 EXE 验收。
- **完整维护迁移仍未通过**。本轮只补齐本机 Windows 既有 SQLite 格式（Workspace v3 / Knowledge v1）的来源与备份阶段、失败保全，没验证成功 PostgreSQL import、schema 升级、默认后端切换、恢复激活。下一步如验证成功导入，必须全新合成旧格式来源、独立目标身份和根、明确备份/回滚；真实用户迁移不在自动范围。
- **退出不再是已证实泄漏缺口**。本次 15 秒预算内约 3 秒 Job 排空，无需继续机械重跑启动、读取成员元数据或先改产品；后续只有新证据再处理。所有 Java graceful shutdown 声明仍禁止。
- 个人恢复、跨机器、物理输入/IME、最小窗口与正式发布仍待相应验收。不追加零碎显式记忆功能，不调用模型，不改 main、不推送。

完整本地构建和保护证据：`.verification/migration-long-path-20261110/` 的 `Build-Delivery.ps1`、`Package.ps1`、`package-result.json`、`package-worker.json`、`package-cleanup.json`、`Verify-Payload.ps1`、`embedded-payload-verification.json`、`baseline-git.json`、最终 `final-state.json`。最终文档收尾提交完整 SHA 见本轮项目回复及 Git；构建来源不会被文档提交替代。

六份原未跟踪用户文档逐个 SHA-256 核对不变；main 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。所有开发成果均是本地可回滚提交，受跟踪工作区应干净，六份用户文档继续未跟踪。
