# 知识问答历史：完整进程重启验收

2026-11-09。本轮从 `5ae7b005d8700b94d2ddd7ec3176fa0c5a840f51`（历史实现）建立 `codex/knowledge-answer-history-restart`，回滚标签 `workspace-before-answer-history-restart-20261109` 指向该基线。验收代码阶段提交 `f2478dc`。仅本地开发，仍为 Development-only，未推送、合并或发布。

## 结果

**真正退出 AssistantApp 和 JVM 后重新启动，历史页可以恢复本轮新生成的两个答案及原引用，模型关闭时仍可阅读。** 本轮没有更改生产实现；将已有 opt-in `RuntimeLifecycleProbe.WebKnowledge` 的第三阶段从“任务缺失”改为实际产品历史页验收。

测试在新建合成 Profile `622ff7be0f364107980e027be0adaf18` 内进行，使用 Example Domain 和 RFC 2606 的公开文本，无个人资料。现有 Native Gate、原生逐次网页访问确认、受信开发构建清单、Profile 私有文件及模型固定摘要均保留。

| 完整启动 | App PID / JVM PID | 实测 |
| --- | --- | --- |
| 1：采集 | `42416 / 47864` | 实际桌面确认获取、预览并保存两份网页；2 次 fetch、2 次 save、0 次问答。 |
| 2：重开与生成 | `50440 / 59104` | 两份源元数据、revision、全文哈希及定位一致；通过实际界面提交两个问题，获得成功答案和精确引用。 |
| 3：模型关闭后重开 | `52944 / 5648` | 从产品历史列表发现两个任务，再经界面打开原问题、检索词、答案和引用；2 次历史读取、0 次问答提交。删除当前一条历史后列表剩 1 条、表单清空、该历史查询返回 TaskNotFound，源文档仍为 2 份。 |

每一阶段均等待自有 JVM 退出后才结束 App。第二阶段结束后，宿主销毁本轮模型 Job 及其子进程，确认 `11435`／`18765` 均关闭，再启动第三阶段。第三阶段还断言模型 `Ready=false`；未靠在线模型补生成答案。测试 JSON 仅作期望值比对，不回写数据库、不作为历史发现入口。

隔离 Ollama `0.40.1`／Qwen3.5 `4b`，digest `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。两题输出分别为 “It may be used in documentation examples.” 和 “The .invalid top-level domain”。本轮实际 `/api/chat` 共 **3 次**：一次准入、两次问答；不能把它说成只有两次模型调用。引用分别为源 revision `1` 的 UTF-16 范围 `[0,369)` 和 `[2299,4346)`，与重启前及源预览一致。两题是流程验收，不代表一般模型质量通过。

## 证据与验证量

根目录 `.verification/knowledge-answer-history-restart-20261109/`：

- `build.json`、`java-package-final.log`、`jar-audit.log`、`dotnet-build-final.log`：离线 Maven 隔离开发构建、JAR 审计、完整桌面／前端构建成功；.NET 0 警告／0 错误。Vite 仍有既有大于 500 KiB 的 bundle 提示，Java 仍有内部 API／弃用提示。
- Runtime JAR SHA-256：`2736F96B8B632A4254F21D920158201FFC208F6AEB7FD29F57798D8CCCB91727`；受信构建清单 163 个文件，无 native pin 或安全设置变更。
- `run-1/capture.log`、`reopen-and-answer.log`、`history-restart-model-off.log` 以及各 `*-result.json`：三次完整启动均 exit 0；一次完整端到端轮次，不重复跑旧单元测试凑数量。
- `run-1/web-knowledge-process-*.json`、`model-off-before-restart.json`：进程身份与模型停止时间；`web-knowledge-result-*.json`：实际桥接请求计数。
- `run-1/web-knowledge-answers.json`：本轮实际答案、引用与源文本；`web-knowledge-history-permission.png`、`web-knowledge-history-invalid.png`：模型关闭后的实际 WebView 答案与来源阅读画面。列表与表单值另由真实 DOM 和 API 双重断言。
- `run-1/completion.json`、`cleanup.json`：自有进程退出、端口关闭，最小可用内存 `18,214,760 KiB`，未触发 4 GiB 保护线；删除本轮独占模型副本 `6,787,837,108 bytes`，保留合成 Profile／证据与原模型缓存。清理首次因 3 份模型生成 metadata 缓存未在原复制清单而停止，未删除文件；核对路径与限额、另存缓存证据后才完成限定清理，见 `cleanup-initial-stop.json`。
- `preserved-documents.json`、`originals/`、最终 `completion.json`：六份既有未跟踪文档的保护副本、逐项哈希及 Git 收尾证据。

## 备份评估与限制

已完成[历史备份安全边界评估](KNOWLEDGE-ANSWER-HISTORY-BACKUP-ASSESSMENT.md)，核对 Xerial／SQLite、Bouncy Castle 和 PostgreSQL 官方实现及许可，优先复用已有依赖和流程。现有 Knowledge v1、Workspace v1 与统一 PG v3 均不覆盖独立历史库，不能直接塞入旧归档或把 PG 快照当跨库一致性。建议后续先做合成限定的版本化“Knowledge 源＋终态历史”联合逻辑快照，落实共同停写、引用缺失语义及候选原子发布后，再接入统一恢复。

**本轮没有实现或验收历史备份／恢复。** 现有备份不包含历史的 UI 提示保持有效，个人恢复仍未开放。未运行 PostgreSQL、未操作既有服务、KAG、个人资料或安全设置。

仍未覆盖突然杀进程／断电后的历史、源物理删除后的真实桌面提示、跨 Profile／跨库恢复、PostgreSQL 源后端完整桌面流程、中文长资料与正式发布包。上一轮关闭／重开 SQLite 的中断记录测试不能替代真实崩溃验收。本轮 UI 操作为实际 WebView DOM 事件驱动，不等于人工键鼠、IME 或辅助功能验收。六份未跟踪文档保持原样，不纳入提交。
