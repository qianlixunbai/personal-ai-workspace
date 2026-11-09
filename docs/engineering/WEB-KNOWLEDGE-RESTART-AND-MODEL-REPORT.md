# 网页知识：完整应用重启与真实问答验收

2026-11-09，基线 `cf945b4`，分支 `codex/web-knowledge-restart`。**完整 AssistantApp／WPF／WebView2／DevelopmentRuntimeOwner／实际 JVM／SQLite 的网页保存、进程退出、重启恢复，以及隔离 Qwen3.5 4B 的两个问答与引用样本均通过。** 第三次完整启动确认引用对应的源版本与文本范围不变。仍为 Development-only，不是正式安装包或模型质量总验收。

## 完成的工作

扩展已有 `RuntimeLifecycleProbe`，增加三个显式手工模式，复用生产应用、原生确认、Bridge、RuntimeClient、进程所有权与 Profile 准入。没有引入新的 UI 自动化框架、模型客户端、数据格式或产品接口。

- `gui-web-knowledge`：新合成 Profile，从 [Example Domain](https://example.com/) 和 [RFC 2606 HTML](https://www.rfc-editor.org/rfc/rfc2606.html) 各获取一次，经真实原生确认、预览和明确保存进入 Knowledge。
- `gui-web-knowledge-reopen`：上个 AssistantApp 和 JVM 都退出后，重新启动并核对持久数据，进行两个真实模型问答，打开引用来源。
- `gui-web-knowledge-citations`：再次退出应用和 JVM 后重开，核对两份资料与旧引用目标，明确验证旧问答任务不可恢复。

本轮还发现并修复了一个实际构建缺陷：`scripts/package-windows.ps1` 用面向人的 Git 输出逐行读取来源文件，中文未跟踪文件名被 Git 转义，构建把引号和八进制转义当成文件路径而失败。现在通过 `ProcessStartInfo.ArgumentList` 调用 `git ls-files -z`，明确以 UTF-8 读取并按 NUL 分隔。遵循 [Git 官方路径输出协议](https://git-scm.com/docs/git-ls-files)，未改变 Git 配置、忽略规则、文件名或用户文件。构建清单与独立 Git 路径集合完全一致，六份原有未跟踪文件都被正确计算哈希且内容未改。

## 实际证据

本地证据目录：`.verification/web-knowledge-restart-20261109/`；唯一应用运行轮次 `run-1/`，三个阶段均退出码 0，没有重试模型或改写问题。

| 阶段 | App PID / Java PID | 实际结果 |
| --- | --- | --- |
| 首次采集 | 51564 / 28116 | 2 次获取、2 次保存、0 次知识回答；保存前文档数不增加，保存后恰好两份可用资料 |
| 完整重启并问答 | 8056 / 43792 | 文档 metadata、revision、完整归一化文本 SHA-256、旧检索命中范围全部相同；重新检索与源文高亮可用；2 次真实知识回答 |
| 再次完整重启 | 61376 / 31976 | 两份资料仍一致，两个旧引用的精确文本仍能读取；0 次重新获取、保存或知识回答 |

资料内容为公开低敏测试材料。Example Domain 保存为 560 bytes／372 UTF-16 单元；RFC 摘录为 4,550 bytes／4,350 UTF-16 单元，明确标记正文已截断，不表示完整 RFC 归档。所有请求都经过现有公开 HTTPS、DNS／SSRF 与限额合同，没有下载真实个人资料或重新访问已保存网页来冒充恢复。

真实模型沿用已固定的 Ollama 0.40.1、`qwen3.5:4b`、`llamacpp`，模型 digest `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`。使用新复制的私有模型目录，分发 82 文件、9 个模型 blob、既有驱动闭包及准入哈希均保留；没有更新 Native pins。Ollama 日志实际记录 3 次 `/api/chat`：一次模型准入验证，两次知识回答；不能把总模型调用说成只有两次。

| 问题要点／检索词 | 实际回答 | 精确引用 |
| --- | --- | --- |
| Example Domain 可作何用途／`permission` | `It may be used in documentation examples.` | 对应 Example Domain 的源版本 1、范围 `[0, 372)`、行 1–13 |
| RFC 2606 哪个顶级域用于明确无效的域名／`invalid` | `The .invalid top-level domain` | 对应 RFC 摘录的源版本 1、范围 `[2299, 4346)`、行 13–13 |

两份原始结果在 `web-knowledge-answers.json`，包含实际问题、任务、回答、引用和引用原文。人工复核两条答案都能由各自引用支持，没有新增无关事实。RFC 样本也实际经过较长首命中的有界摘录；仅为英文样本，不能作为长中文资料质量通过的证据。

`web-knowledge-real-answer-0.png`、`web-knowledge-real-answer-1.png` 和 `web-knowledge-third-start-source.png` 已人工查看，界面中的源版本、引用范围和高亮与结果一致。截图只采集自有 WebView，未截取其他桌面内容。

## 恢复边界

**持久恢复的是文档及其源版本；知识回答任务仍是内存状态。** 第三次启动调用两个旧 task ID 均得到 `TaskNotFound`。验收保存的引用 JSON 只是外部测试证据：它用于再次读取相同文档、版本与偏移，不能当成产品恢复了历史问答、恢复了旧任务或自动重新打开旧引用。

界面在新进程中重新检索并打开原文；旧引用目标则通过实际 Runtime 预览接口核对。没有向产品注入历史问答、伪造 Bridge 回复或绕过原生访问控制。第三次启动未重新验证模型 Ready，资料恢复不依赖模型保持可用。

## 构建、资源与清理

- 初次 Maven 构建因中文路径错误失败，保留 `java-package-before-path-fix.log`。修复后离线 Maven package 成功，隔离 JAR 审计通过；既有 Java 内部 API／弃用警告仍在，不能称为 Java 零警告。
- 当前 JAR SHA-256：`1e0904d2018084372c54b54937cdd949e24426047389f76deeeb149c2aa0bf43`。独立 Format 2 清单绑定当前 JAR、JDK 和 JNA 的 163 个文件，随后构建实际探针；没有使用旧业务 JAR 代替本次代码。
- .NET Debug 构建 0 warning／0 error；前端未修改，复用已构建的生产资源。没有为本轮重复运行无关全套测试。
- 全新合成 Profile：`3a004d4343544a1caff8f38afe1fed95`；模型监听 `127.0.0.1:11435`，Runtime `18765`。启动前核对无已有模型进程和端口冲突，不访问默认个人 Profile、服务设置或 KAG。
- 首阶段／第二阶段／第三阶段外部截止分别 240／360／180 秒。外部资源检查每约 5 秒一次，可用内存低于 4 GiB 时只终止本轮拥有的进程；实测最低约 17.3 GiB。本轮完整应用使用正常 DevelopmentRuntimeOwner 的 JVM 参数，**没有套用上轮 adapter 的 384 MB 堆上限**。
- `cleanup-verified.json` 独立核对三个 App、三个 Java、自有模型服务与该 Profile 的 WebView 全部退出，11435／18765／runner 58954 端口关闭。最终可用内存约 19.2 GiB、显存空闲 6,614 MiB。
- 仅删除本轮新复制且核实所有权／无链接的模型目录，释放 6,787,837,108 bytes；源模型未动，合成数据库、源文件和日志／截图保留。见 `temporary-model-cleanup.json`。

## 开源复用与安全判断

本轮继续复用 [Ollama](https://github.com/ollama/ollama)，2026-11-09 页面约 186.4k Star，仓库未归档，[MIT 许可](https://github.com/ollama/ollama/blob/main/LICENSE)，官方[最新发布页](https://github.com/ollama/ollama/releases/tag/v0.40.15)可见持续发布。验证使用既有固定 0.40.1，未自动升级到最新版本，避免在验收过程中改变已审查执行闭包。

检查了官方[安全公告页](https://github.com/ollama/ollama/security/advisories)，当时页面没有已发布公告；这不是无漏洞证明，也不替代固定版本的完整供应链审计。本轮保持回环监听、私有测试模型、原有哈希／驱动准入和进程 Job，不安装服务、不更改系统驱动或安全设置。模型许可证没有因本次验证而变成应用依赖许可；没有重新分发权重。现有 jsoup、安全 Fetch、SQLite 和 WebView 自动化的复用判断延续[功能报告](WEB-KNOWLEDGE-CAPTURE-REPORT.md)及[上轮桌面报告](WEB-KNOWLEDGE-LIVE-ACCEPTANCE.md)。

## 剩余限制与回滚

仅覆盖两份公开 HTML、两个英文问题、一个固定模型和 SQLite。未覆盖中文长资料质量、多文档冲突／无答案、恶意页面指令、批量与长期稳定性、PostgreSQL 完整桌面流程、正式签名安装包、人工键鼠／IME／高 DPI／辅助功能。没有因此关闭 W1 Search／Web Answer、个人恢复或生产发布门禁。

基线回滚标签 `workspace-before-web-knowledge-restart-20261109` 指向 `cf945b4`。六份原有未跟踪用户文件的备份与哈希在 `originals/` 和 `preserved-documents.json`；只提交本轮明确列出的源码与报告，不暂存这些用户文件。修复、验收工具和说明保留本地 Git 提交；不推送、不合并。最终提交及文件核对记录在证据根目录 `completion.json`。
