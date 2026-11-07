# 显式记忆系统完成报告

2026-11-07。基线 `a50a16d905c1dc2e187e844bf08c2034bb45ce07`；本轮完成当前 Development-only 记忆系统的必要验收。用户要求验收完成后推送现有仓库、审慎整理分支、总结并暂停；不启动下一阶段。

## 已完成的使用闭环

- 从单条用户／助手消息主动生成最多一条候选，使用 Ollama 原生固定 JSON Schema；展示原文与逐字摘录，可编辑、丢弃或明确采用。生成和采用均不写入记忆，只有明确保存才持久化。
- 主动核对相同正文的已有记忆，可对照并明确修订同一 ID／版本；并发修改、过期、归档或缺失目标拒绝保存，保留草稿。没有自动合并或后台提取。
- 从最多 1,000 条 ACTIVE 记录词法检索前 20 条相关结果，逐条预览、明确选择，原生边界最多使用 4 条精确 ID／revision 引用。没有自动选用或向量服务。
- 默认关闭的会话连续使用可显式开启，每轮重新核对版本。版本失效拒绝发送且不新增轮次；清除停止后续显式引用，已有会话历史及其事实继续保留。
- 会话历史保存引用版本；完整进程重启后记录、修订和历史仍在。归档移出召回，恢复重新可查，删除不改写已有会话历史。

早期失败暴露证据改写、语言偏离及缺少 evidence。现在仍严格拒绝格式错误、非原文摘录和有限范围内明显文字体系偏离；固定 schema 解决结构约束缺口，不能证明所有语义正确。未伪造证据、静默修复、自动重试或放宽预算。

## 实际验证及证据

所有实例均使用合成数据和自有独占配置，未操作真实个人数据库、KAG 或既有服务。`.verification/` 为忽略的本地证据，不随 Git 推送；可复用测试源码和本报告进入 Git。失败原始结果继续保留，单独人工语义复核绑定原结果 SHA，不改写原结果。

| 验证 | 结果与实际覆盖 | 本地证据 |
| --- | --- | --- |
| 完整真实应用与模型 | 通过；实际 AssistantApp、WPF、WebView、DevelopmentRuntimeOwner、JVM、Native Gate、SQLite、Ollama 0.40.1、Qwen3.5 4B。候选审阅／保存、选择／连续两轮引用、过期拒绝、清除；整应用及 JVM 退出后重开，修订／历史保留，归档／恢复／删除通过 | `.verification/memory-full-app/run-29/`；两次应用 exit 0，四个 PASS 标记，截图已查看 |
| 同版本独立真实质量 | 8 个候选＋3 次会话，11 次单并发调用约 48 秒，全部机器断言与逐项语义复核通过；中文范围、英文事实、否定修正、暂时内容、注入、未确认建议、虚构秘密和不支持场景 | `.verification/memory-full-app/run-28-quality/`；这是直接提供器质量 fixture，不冒充完整桌面 |
| 最终合成桌面 | 1/1；真实 WPF／React／Bridge／HTTP／SQLite，合成提供器；候选查重、同 ID 修订、显式检索、连续引用、过期／清除、重开和生命周期；实际提供器 payload 断言 | `.verification/memory-desktop-acceptance/run-9/`；host exit 0，Java 已退出；此前 run-8 也通过 |
| PostgreSQL 17.11 | 1/1 综合场景；独立 SCRAM 实例、非超级应用角色、真实迁移／Store，Unicode 召回、版本、只读事务快照、会话引用、重开、1,000 条上限及异常 1,001 条拒绝 | `.verification/memory-postgresql/run-3/`；自有实例停止，临时凭据删除；不代表完整 PostgreSQL GUI |
| 原生结构化 HTTP | 4/4；固定 schema、普通 Ask 不变、非法契约／身份拒绝、含 schema 超预算无调用、无静默修复／重试 | `.verification/memory-structured-output/api-4/` |
| Windows 身份与兼容性 | 6/6；持有对象、固定 runner 参数、PID 绑定 OpenMP 状态、console 父子／参数／环境、catalog 身份范围、精确平台文件 pin | `.verification/memory-structured-output/windows-platform-tests.log` |
| .NET 候选／记忆选择 | 38/38；候选任务使用固定契约且不消费已有选择，既有选择／异常语义 | `.verification/memory-structured-output/dotnet-tests-final.log` |
| 前端与会话预算 | 前端 23 项、TypeScript／Vite 构建通过；会话原生预算 4/4，系统提示 494 UTF-8 字节，原 512 上限不变 | `.verification/memory-context-regression/run-2/`；前端记录见历史验收报告 |

Java／.NET 编译与最后实际桌面探针重建均通过。另有 Ollama 0.40.6 的 11 次独立小样本通过记录；最终验收采用与完整应用相同的 0.40.1。没有把合成提供器结果、预热、只读诊断成功当作真实模型质量通过。

关键结果摘要：

```text
MEMORY_FULL_APP_OWNED_MODEL_READY_PASS
MEMORY_FULL_APP_REAL_CANDIDATE_REVIEW_EXPLICIT_SAVE_PASS
MEMORY_FULL_APP_REAL_RECALL_RETAIN_STALE_CLEAR_PASS
MEMORY_FULL_APP_RESTART_PERSISTENCE_ARCHIVE_RESTORE_DELETE_PASS
workflow exitCode=0; reopen exitCode=0
ownedServerExited=true; ownedClientExited=true; portsClosed=true
```

真实应用 `memory-full-workflow.json` SHA-256：`489bd45bff074bd44e4d6c431df01ca0076acff3e47d357a49404d43078ad7af`。

同版本独立质量 `quality-results.json` SHA-256：`07953e91d9bfa8fbc1c8fb9d5745b25569ad7949475845a5be75cd13643ddafa`。

## 复用与 Windows 修复

复用 Ollama 原生结构化输出、现有任务／租约／取消／预算、原生文件身份检查、生产迁移与 Store、真实桌面组件及测试宿主。评估 Instructor、Testcontainers、Mem0／LangExtract 的适配性、许可证、活跃度和成本后，采用原生能力与验证／来源定位实践，没有增加 Python、容器、向量服务或第二条模型调用链。具体开源链接、观察日期和取舍见[结构化输出报告](MEMORY-STRUCTURED-OUTPUT-REPORT.md)、[来源保护报告](MEMORY-CANDIDATE-GROUNDING-REPORT.md)和[历史验收记录](MEMORY-SYSTEM-ACCEPTANCE-WIP.md)。

Windows 上 JDK 的可空 fileKey 改为复用已有卷＋FILE_ID_128 身份检查，仍持有禁止写入／删除的 JAR 句柄并验证完整来源。隔离模型使用准入 Profile 内固定目录。按 Ollama／llama.cpp、LLVM OpenMP 和 Microsoft Terminal 的实际实现，仅识别固定 runner 的启动参数、PID 绑定初始化字段及直属 console，原始状态仍参加稳定快照，所有模块继续验证。

签名 catalog 只在固定系统 catalog 范围识别 CryptSvc 服务 SID；模块本身权限边界未扩大。隔离开发构建为实际加载的 7 个额外 GPU DLL 和 3 个 MSVC DLL 固定精确 Windows 相对路径、大小与完整 SHA，并继续检查受保护文件、锁和 WinTrust。原 82 个 Ollama 分发文件及 nvcuda anchor 不变；没有安装、更新或重分发系统驱动，没有修改 ACL、系统安全设置或现有服务。临时异常位置诊断已从产品源码和最终桌面程序集移除。

## 限制与暂停边界

- 这是当前显式记忆功能的开发验收，不是正式安装包发布、跨机器兼容性、CI 全量认证或 Production GO。该隔离开发平台清单绑定当前 Windows／驱动／C++ 运行库；升级后应重新审查，不能自动放行。
- 真实模型只是小样本。含有效事实又混入恶意内容的场景保守弃答，不能证明召回率；一次清除后的回答用了较宽泛的 “future references”，仍有措辞局限。人工审阅不可移除，语言保护不等于全面语言或语义判断。
- 桌面由实际控件与 WebView 自动化操作，未覆盖物理键盘、中文 IME、辅助功能或所有分辨率。PostgreSQL Store 已验证，未单独运行全套 PostgreSQL GUI。
- 真实个人数据／日常 Profile 未参与；没有证明长期使用质量、性能或迁移所有历史数据。Vision、个人备份／恢复和原有 Production GO 关卡保持各自状态。
- 自有模型／客户端／JVM 已退出，测试端口关闭；只清理有归属记录且确认退出的临时模型副本，保留合成数据库、截图、成功及失败日志。当前没有运行中的 Ready 声明。

本轮清理记录 `.verification/memory-full-app/owned-model-cleanup.json`：17 个新建合成 Profile 的模型副本全部删除，共回收 115,393,168,094 字节；删除前已逐项核对归属、完成状态、解析后的绝对范围及无重解析点，来源模型和所有验收结果保留。原有五份用户未跟踪文档经 SHA 核对均未变化。

## Git 与下一阶段建议

功能提交 [`794c7609857011481588d16caca11f8d2fe01676`](https://github.com/qianlixunbai/personal-ai-workspace/commit/794c7609857011481588d16caca11f8d2fe01676) 已成功推送到既有 `origin/codex/recovery-v3-workspace-core`。只提交明确选择的 41 个代码、测试和报告文件；五份原有用户未跟踪文档内容不变且未提交。远程仍为 `https://github.com/qianlixunbai/personal-ai-workspace.git`，没有创建新仓库或推送验证数据。

fetch 后核对所有本地／远程分支：`codex/runtime-probe-failure-isolation`、`codex/startup-status-clarity` 及原 `main` 均为验收提交祖先，没有独立待合并提交。`main` 已从 `352f01f901fc939bbb73dcef3ac34f0a2850fb98` 通过 `--ff-only` 前进到 `794c760` 并成功推送；合并后的代码树与实际验收树完全一致，没有冲突或重新拼接。这个快进也纳入该开发分支此前已经提交并推送的 Recovery 防护工作；个人备份／恢复仍按原关卡禁用，不能据此宣称其整体完成。三个开发分支均保留，不删除、不强推。后续仅提交本文的收尾记录，不变更验收代码。

已成功推送的回滚／验收标签：

| 标签 | 指向 | 用途 |
| --- | --- | --- |
| `workspace-before-memory-postgresql-a50a16d` | `a50a16d905c1dc2e187e844bf08c2034bb45ce07` | 本轮记忆收尾前基线 |
| `workspace-main-before-memory-20261107` | `352f01f901fc939bbb73dcef3ac34f0a2850fb98` | main 快进前状态 |
| `workspace-memory-accepted-20261107` | `794c7609857011481588d16caca11f8d2fe01676` | 完成验证的代码树 |

需检查旧版时可从对应标签新建独立分支／worktree；对已共享主线撤回功能应另做审阅后的 revert，避免重写远程历史。标签只保护代码，不备份数据库；本轮没有修改真实个人数据，不能把 Git 回滚误称为数据恢复。

后续可优先建立较大的合成质量语料与错误分类，再安排经授权的日常小规模试用、PostgreSQL GUI／IME 验收；正式发布前完善可移植受信打包与平台版本审查。自动提取、语义检索或记忆调度应另行评估，不能由当前成功样本直接推导可靠性。

**这些只是建议，本轮不启动。按用户要求完成 Git 收尾和总结后暂停 Workspace 开发。**
