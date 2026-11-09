# Workspace 分支整合与完整桌面笔记流程

2026-11-09。开始于 `codex/knowledge-answer-export`／`9dda83d`，完成范围是本地分支整合和一条完整的桌面使用流程。没有增加产品功能、第三方依赖、模型调用或数据库格式；没有推送远端，仍为 Development-only。

后续授权补记：用户明确同意后，本地 `main` 已于 2026-11-09 从 `184aafd` 纯快进至 `f5266bf`，全部 22 个分支、回滚标签和六份用户文档保留，没有推送。收据位于 `.verification/workspace-integrated-desktop-20261109/authorized-main-result.json`。下文的审批拒绝描述保留首次尝试时点，不再是当前阻塞；Word 导入开发以这个已整合的 main 为基线。

## 分支实际上是否整合

只读核对了全部 22 个本地分支和唯一工作树：所有分支尖端都已包含在 `9dda83d`，近期功能沿同一条提交链推进，没有待拼接的分叉。开始时 `main` 及本地缓存的 `origin/main` 都停在 `184aafd`，落后当前功能尖端 28 个提交；本轮没有 fetch，不能据此判断远端实时状态。

| 链上的阶段 | 已包含的尖端 |
| --- | --- |
| 生命周期、片段上界、来源阅读 | `f6ff174` → `64a9503` → `fda9562` |
| 网页资料、实际桌面、完整重启 | `e635675` → `cf945b4` → `b460ccb` |
| 问答历史、历史重启 | `5ae7b00` → `1b77253` |
| 合成快照、加密、恢复阅读验证 | `99f54a7` → `18175d3` → `971a9c0` |
| 跨会话搜索与桌面验证 | `ffa3a12` → `bb77fa0` |
| 指定资料问答、桌面、保真、复核、笔记导出 | `9de7829` → `f36dacb` → `20a9e74` → `82a2e66` → `9dda83d` |

因此没有 cherry-pick 或冲突处理。验收工具和报告保存在现有 `codex/knowledge-answer-export` 分支，不另建功能分支；**`main` 仍停在 `184aafd`**。原计划在提交后以 `--ff-only` 快进本地 `main`，但自动审批拒绝了包含这一步的命令，要求针对默认分支变更的明确授权，命令未执行。读取最近用户历史后未找到该明确授权，没有重试或绕过。旧主线回滚标签 `workspace-main-before-integration-20261109` 指向 `184aafd`；整合前功能标签 `workspace-before-integrated-desktop-20261109` 指向 `9dda83d`。全部分支保留。只移动代码分支不回滚数据库；不要把旧代码直接指向新格式资料来冒充数据恢复。

## 同一版本的实际使用流程

复用现有 `RuntimeLifecycleProbe`，增加两个 opt-in 模式 `gui-integrated-note`／`gui-integrated-note-reopen`。宿主运行完整 AssistantApp、WPF、已打包 WebView、正式 Bridge、DevelopmentRuntimeOwner、HTTP 和 SQLite，不用网页 mock。合成 Profile 来自[未见题复核](KNOWLEDGE-ANSWER-FOLLOWUP-REPORT.md)，其中原有三份短资料和三条真实模型历史保持原样。本轮不重新生成回答。

最终以当前源码构建的 **default Runtime** 完成以下连续操作：

1. 打开知识库，选定 Rook 文档，限定当前源版本。搜另一份资料的 `Nacre` 得到零结果，没有扩大范围；搜 `Quarantine` 命中本资料，并打开精确位置。
2. 从历史页重新打开原回答，核对原问题、关键词、范围和完整任务记录。三条历史分别与原始收据逐项相等；点击引用后，界面高亮与存储原文精确相等。
3. 在问题栏输入未提交的新草稿，点击导出。通过实际 Windows 保存对话框写入本次证据目录的新 `.md` 文件，核对原问题、原回答、文档 ID、源版本和引用全文，确认草稿没有混入。
4. 第二次点击导出并取消系统对话框，原文件哈希不变，草稿仍留在界面。
5. 第一轮应用和 JVM 完全退出后，启动第二个独立 AssistantApp／JVM，再走检索、历史和引用读取；笔记哈希与第一轮一致，来源及历史各保持三条。

最终两次应用启动均退出码 0：Desktop PID `52208`／`46652`，JVM PID `7124`／`55388`。第一轮捕获两次 `knowledge.answerExport`（保存、取消），第二轮为零；没有问答提交、来源导入、历史删除、模型变更或网页获取请求。保存和取消使用自有窗口的系统控件，未读取文件选择器中的个人目录内容。导出文件是明文摘录，不是完整备份。

## 构建与失败记录

全部本地证据位于 Git 忽略目录 `.verification/workspace-integrated-desktop-20261109/`。没有重跑无关测试矩阵；本轮的核心证据是一条流程和独立进程重开。

| 证据 | 实际结果 |
| --- | --- |
| `branch-relations-before.json` | 22 个本地分支尖端均为起始 HEAD 的祖先 |
| `run-1`～`run-3` | 使用此前模型验收的特殊 Runtime，均停在启动阶段；自有进程、端口已退出 |
| `diagnostic-cause.txt` | 根因定位到 `OllamaVisionEnvironment.developmentInstance`；特殊构建要求已按旧收尾记录删除的测试模型目录仍存在 |
| `default-runtime-build.log`／`default-runtime-audit.log` | 离线 Maven 默认构建成功，实际 JAR 身份审计通过；没有恢复大型模型副本或放宽安全检查 |
| `default-desktop-build.log` | 普通 Runtime 配套 Desktop／探针构建成功；临时异常类型诊断已从产品源码移除 |
| `run-4/first-failure.json` | Runtime、检索、历史、引用已通过；探针过早读取保存对话框文件名控件，控件尚未出现 |
| `default-desktop-dialog-build.log` | 仅修正探针等待控件就绪，0 warning／0 error |
| `run-5/first-result.json`、`reopen-result.json`、`completion.json` | 最终完整流程和重开通过，两个阶段退出码 0；实际保存、取消和原文核对成功 |
| `run-5/first-answer.png`、`reopen-source.png` | 已目视核对：真实回答、导出取消提示、引用信息和原文高亮正常呈现 |
| `final-isolation-check.json` | 六份用户文档哈希、最近工作区选择内容、既有 PostgreSQL PID 集合未变；本轮端口关闭，无 Java／Ollama／llama-server／Desktop 遗留 |

默认 Runtime JAR SHA-256：`C27A88ED9F64E9FB7CF85CCA3344A30C286BBA840FBDD2A2D8DD63D89684C139`。编译嵌入的来源清单 `default-sources.json` SHA-256：`8884C85A1012FE7047A47F315043FD979434C7D719410CD21001816A4EC82FDE`。沿用经核对的本地 JDK 21、JNA 和原生引导固定信任，不新增或升级依赖。成功轮次笔记 SHA-256：`37322E80168B86DB7EA33F4F1CEEF4AF39631AF404DD5D5ABD0458F8DAB0CAA5`。

特殊 Runtime 的失败说明旧验收构建依赖旧环境，不能作为普通可启动版本使用；不是历史数据损坏。定位时临时记录的只有 Java 异常类型和代码位置，没有记录凭据或资料正文，且最终产品源码与 `9dda83d` 完全一致。全部失败日志保留，没有把首次失败改记为成功。

## 现在能用到哪里

当前 `codex/knowledge-answer-export` 是包含近期功能的统一源码入口，`main` 的更新待明确授权。当前 default Runtime 与同次 Desktop 构建已经实测可用：SQLite 合成资料的限定搜索、读取已保存问答、精确引用、原生另存笔记及完整重启后继续阅读。

验收的 Desktop 在 `.verification/workspace-integrated-desktop-20261109/dotnet/bin/PersonalAiWorkspace.Desktop/debug/`；测试宿主位于同级 `PersonalAiWorkspace.Desktop.Tests/debug/`，运行时绑定 `default-sources.json` 的本地构建输入。它们依赖本机 Development 配置，**不是可搬移的正式安装包**。复验还需已有、明确归属的合成 Profile 及 `quality-unseen.json`；本地 `run.ps1` 在默认工作区选择不一致、端口占用或发现计算进程时会停止，不能为了运行探针而改动真实个人选择。默认开发构建流程继续由 `scripts/verification/build-trusted-development-runtime.ps1` 提供；本轮 Maven 使用既有 `target` 输出，配套 JAR 副本、Desktop 和证据保存在独立目录。

本轮没有覆盖新问答生成、所有其他页面、PG 桌面组合、大容量、正式分发或个人备份恢复；之前三个短问答样本不能证明总体质量提升。没有新增上线／安装／个人数据迁移授权。复用既有 WPF／WebView2／系统 UI Automation 和验收宿主足以验证这条流程，无需引入外部框架；也没有启动模型抢占 GPU。仅完成资源前置检查和自有进程隔离，没有与 KAG 建立跨线程资源锁。

## 尚未执行的默认分支操作

待批准动作是在验收成果提交后，`git switch main`，再 `git merge --ff-only codex/knowledge-answer-export`，不推送远端。它会移动本地默认分支和工作树选择；旧 main 标签已保留。自动审批原因为：`The action commits changes and fast-forwards the local default main branch; touching a default branch is high risk, while the user has not explicitly authorized this exact branch mutation.` 状态是 **denied，未执行**，不是待执行的后台命令，也没有不确定结果。审批只阻止默认分支更新，不改变上述已完成的隔离桌面验证结论。
