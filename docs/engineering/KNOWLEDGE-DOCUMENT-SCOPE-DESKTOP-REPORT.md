# 指定文档问答：完整桌面、真实模型与历史重启

2026-11-09。基线 `9de7829`，分支 `codex/knowledge-document-scope-desktop`，回滚标签 `workspace-before-knowledge-document-scope-desktop-20261109`；验收工具提交 `f2b4df9`。仅本地提交，未推送、合并或发布，仍为 Development-only。

## 实际结论

完整 AssistantApp／WPF／WebView2／Native Bridge／DevelopmentRuntimeOwner／HTTP／SQLite 已完成指定文档检索、两个真实模型问答、来源核对，以及模型关闭后的独立进程历史恢复。**范围与持久历史闭环可用；真实模型质量没有全部通过。** 第一题中文回答正确，第二题只返回 `invalid`，未满足预先要求的 `.invalid` 精确写法和中文表达。引用范围均属于所选文档，但不能据此认定回答质量合格。

本轮不修改产品逻辑、存储格式、模型提示词、服务或权限，只扩展现有 `RuntimeLifecycleProbe`，增加三个显式手工模式：`gui-document-scope`、`gui-document-scope-reopen`、`gui-document-scope-history`。继续使用已构建的真实生产前端与原有应用启动、原生确认、来源及模型准入；没有新增自动化框架或第三方依赖。

## 桌面闭环与证据

证据根目录 `.verification/knowledge-document-scope-desktop-20261109/`，由 Git 忽略，仅留本地。全新合成 Profile 为 `76a8b8050b214c4db342465846c3641d`。资料通过原生确认从公开的 [Example Domain](https://example.com/) 和 [RFC 2606 HTML](https://www.rfc-editor.org/rfc/rfc2606.html) 获取，预览后明确保存；没有访问个人资料。RFC 正文仍是标明截断的摘录，不是完整 RFC 归档。

| 阶段 | 实际结果 |
| --- | --- |
| 完整采集启动 | App `34692`／Java `27796`；2 次网页获取、2 次明确保存、0 次知识问答；`run-2/capture.log` 退出 0。 |
| 完整重启并限定资料 | App `7408`／Java `21916`；两份来源的 metadata、版本、全文 SHA-256 和旧检索位置保持一致。Example 文档范围内搜 `invalid` 得到 0 条；切换阅读的文档并刷新详情后，检索范围和草稿仍保留；明确选择全部资料后才找到 RFC 命中。 |
| 真实问答 | 分别明确限定 Example／RFC 后，再切换到另一份正在阅读的文档并刷新；问题和原范围保持。两条任务均生成成功、引用都属于各自所选文档。第二题在精确文本质量断言处失败，`run-2/completion.json` 仍为 `passed=false`，不改写为 PASS。 |
| 模型关闭后独立重启 | App `24512`／Java `46988`；`run-2/history-only/host.log` 退出 0。通过实际产品历史列表发现并读取两条记录，原问题、关键词、答案、范围和引用全部一致；界面恢复文档标题及源版本 1，原文高亮可打开。0 次获取、保存或问答提交，2 次历史读取。 |
| 删除一条历史 | 使用真实界面确认删除第二题，剩 1 条历史；两份源资料保留，已删 task 的历史读取返回 TaskNotFound。 |

所有界面操作通过真实 WebView DOM 触发，未伪造 Bridge 响应。原生客户端额外读取实际 Runtime 结果作断言。三次启动各有独立 AssistantApp 和 JVM；不是同 JVM 的窗口重开。以下截图已目视核对：

- [限定 Example 后无匹配](../../.verification/knowledge-document-scope-desktop-20261109/run-2/web-knowledge-scoped-no-match.png)
- [重启后的中文答案与引用原文](../../.verification/knowledge-document-scope-desktop-20261109/run-2/history-only/web-knowledge-history-permission.png)
- [重启后的原始第二题答案及准确引用](../../.verification/knowledge-document-scope-desktop-20261109/run-2/history-only/web-knowledge-history-invalid.png)

截图只包含本轮合成资料。范围标签的恢复另外由实际 DOM 断言确认；后两张截图定位在引用原文，不以截图代替未显示区域的断言。

## 模型质量与失败保全

沿用固定 Ollama 0.40.1、Qwen3.5 4B、llamacpp，digest `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。`run-2/ollama.log` 实际记录 **3 次 `/api/chat`**：一次模型准入、两次知识回答。没有重问、改变检索词或重复生成来取得更好结果。

| 问题与关键词 | 原始回答 | 核对 |
| --- | --- | --- |
| 用中文简述 Example Domain 用途；`permission` | `该域名可用于文档示例。` | 满足问题；引用 Example 源版本 1，偏移 `[0,372)`，行 1–13，原文支持。 |
| 用中文指出 RFC 2606 中用于明确无效域名的顶级域；`invalid` | `invalid` | 指向相关名称，但缺少前导点，也没有中文说明；预设 `.invalid` 断言失败，质量按部分满足记录。引用 RFC 源版本 1，偏移 `[2299,4346)`，行 13–13，包含正确名称及适用说明。 |

这是两条小样本的本轮人工式 AI 核对和预定文本检查，不是独立人工评分或通用质量评估。不能把它描述成两个中文问题全部通过；也不能把第二题夸大为引用越界或选错了另一份资料。

第二题的语义断言发生在汇总 oracle 写出前。为继续独立验证已落库的真实记录，`history-only.ps1` 将两个已保存的原始 answer JSON 及该阶段进程记录组合成**预期值 oracle**，保留出处于 `history-only/oracle-provenance.json`。没有向产品数据库插入答案、恢复数据库、伪造历史 API 或重新调用模型；探针仍通过真实 API／界面读取并逐项比较持久历史。原失败日志、原答案及 `passed=false` 完整保留。

验收工具随后增加“每题语义断言前保存当前汇总 oracle”的检查点，便于以后在质量失败后直接检查持久记录；没有放宽语义断言。该小改动重新编译通过，独立历史验收使用最终构建；本轮模型执行发生在检查点增加前，未重复模型样本。

## 构建、资源与保留

- `java-package-final.log`：当前源码的离线 v1-isolated-development package 及 Runtime JAR 审计通过；skipTests 构建不计作动态测试。JAR SHA-256 为 `461615E2250AF9C94C8763CEC288813543BF70912BD2E69399533A6B9C4816D0`，未部署。
- `dotnet-build-final.log` 与 `dotnet-build-evidence-checkpoint.log`：初次完整前端／桌面构建和最终探针编译通过，.NET 0 warning／0 error；保留既有 Vite 主 chunk 超过 500 kB 提示。没有重复无关测试矩阵。
- 第一轮普通 Shell COM 启动在 PRIVATE_PROFILE 阶段被已有目录身份校验拒绝，未启动模型或业务流程；原始日志在 `run-1/`。改用现有 Explorer 窗口的 `Document.Application.ShellExecute` 后正常准入。没有修改目录身份算法、ACL 规则、原生 pins 或用户系统设置；未把启动失败隐去。
- 启动前检查没有 Ollama／llama-server／Python worker，11435／18765 端口空闲，可用内存约 19 GiB；只启动本轮独立模型和 Runtime。保留 4 GiB 内存截止与阶段超时，实测最低可用内存 17,556,676 KiB。没有启动 KAG 或接触其模型／数据库；本轮可用工具未提供跨线程资源握手，采用可观测资源空闲检查，不声称取得 KAG 的显式锁。
- `final-isolation-check.json` 核对全部自有进程退出、两个端口关闭；既有 PostgreSQL PID 集合未变。6 份原有未跟踪用户文档 SHA-256 均保持不变，未暂存、移动或清理这些文件。
- `run-2/cleanup.json` 确认只删除本轮新复制且经路径、归属和哈希核对的模型目录，释放 6,787,837,108 bytes；原模型缓存未动，合成 Profile／数据库、原始失败记录和截图保留。清理脚本首次因仍要求质量 PASS 而停止，修正为“运行已结束＋独立历史恢复通过＋进程已退出”后执行；未覆盖质量结果。

## 成熟方案取舍

继续复用现有 Ollama 及 Windows WebView2／WPF 工具链，不为本次验证引入新框架。重新检查 Ollama 固定版本的 [MIT 许可](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/LICENSE)、[持续发布记录](https://github.com/ollama/ollama/releases)与[安全页](https://github.com/ollama/ollama/security)。页面显示持续发布、暂无已发布安全公告；这不是无漏洞结论。验证保持原有固定 0.40.1、loopback 监听、私有测试模型、82 个发行文件／9 个模型 blob 与驱动闭包准入，不自动升级依赖、安装服务或重新分发模型。

范围实现的 LlamaIndex／Haystack 参考和不引入通用过滤框架的取舍沿用[实现报告](KNOWLEDGE-DOCUMENT-SCOPE-REPORT.md)。本轮没有改动该设计。

## 剩余范围与回滚

本轮只覆盖 SQLite、两份英文公开网页和两个中文问题。第二题质量仍不满足全部要求；未覆盖长中文资料、多文档冲突、恶意内容、无答案问答、PG 完整桌面组合、最大容量、正式安装包或人工键鼠／IME。上一轮隔离层的更新／归档范围拒绝与 PG Store 证据继续有效，但没有把它们计成本轮完整桌面测试。

历史库 schema 2 的兼容约束不变：代码回滚标签不能用于给真实升级后的数据库降级；本轮没有打开或升级个人数据，没有增加个人备份恢复承诺。后续应根据实际用户需求补回答格式遵循、证据支持度和必要产品缺口，不自动扩展底层验收框架。正式发布及 Production GO 未解除。
