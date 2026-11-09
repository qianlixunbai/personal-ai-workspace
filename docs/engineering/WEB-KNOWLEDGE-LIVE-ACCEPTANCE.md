# 网页知识采集：真实网页与桌面验证

2026-11-09。功能基线 `e635675`，验证分支 `codex/web-knowledge-live-check`。**实际 WPF／WebView2／原生确认／生产公共网络 Fetch／HTTP／SQLite 的获取、保存、检索、来源阅读和窗口重开流程通过。** 本轮只扩展既有显式验收工具及更新文档，没有修改产品行为、依赖、服务或安全设置；仍为 Development-only。

## 实际运行范围

复用 `bootstrap/tests/MemoryDesktopAcceptanceProbe` 的新随机 Profile、固定 Native Bootstrap、Java Native Gate、自有进程 Job、真实前端资产和截图接口，增加可选第五参数 `web-knowledge`。默认记忆验收模式保留，没有另建 UI 自动化平台。

- 公共样本固定为 [Example Domain](https://example.com/) 和 [RFC 2606 官方 HTML](https://www.rfc-editor.org/rfc/rfc2606.html)，每次运行最多各提交一次获取。前者验证小网页，后者验证长文截断。请求由实际 Runtime 的 `WebConfiguration`／`PublicWebTransport` 执行，沿用 DNS、TLS、同主机重定向及 SSRF 控制；没有代理抓取、返回网页 fixture 或关闭安全校验。
- 新 opt-in Java 场景明确断言只有生产 `webFetchService` Bean。只有显式 `web.desktop.fixture=true` 才排除原无网络 Web 测试 Bean；普通测试继续使用原 fixture。本轮实际桌面回归中的模型端点是随机 loopback 的健康元数据 stub，生成与能力 probe 调用均断言为 0，没有访问现有 Ollama。
- WPF 测试 adapter 接入生产 `WorkspaceWebFetch`、`NativeWebFetchConfirmation`、`WorkspaceKnowledge`。只操作自有窗口中的真实控件和 WebView DOM，不注入伪造 Bridge 回应，不操作全局键鼠、剪贴板、托盘或热键。文件选择器在本模式拒绝调用，避免访问个人文件。
- 每次是全新合成 Profile；没有读取个人工作区、修改用户选择或借用现有凭据。Java 堆上限 384 MB、active processors 2；UI 总截止 190 秒，Java 场景截止 220 秒，获取单步等待 35 秒覆盖原 Fetch 30 秒上限。退出仅处理自有 Java Job 和自有 WebView。

IANA 的[示例域名说明](https://www.iana.org/help/example-domains)指出其 HTTP 服务仅尽力提供，不适合作为生产应用的运行依赖。本轮是有限人工触发验收，没有建立持续监控或默认联网测试。RFC 样本最终使用 HTML；本轮没有完成原始 TXT 格式的真实抓取验收。

## 成功证据

本地证据目录：`.verification/web-knowledge-live-20261109/`。最终成功轮次为 `run-4/`，宿主退出码 **0**，Java `found=1 passed=1 failed=0 skipped=0`。

| 路径 | 已验证的行为 |
| --- | --- |
| 原生取消 | 实际确认窗口展示准确主机和完整网址；取消为默认按钮，取消后获取／保存 POST 均为 0。 |
| 两个真实页面 | 逐次原生允许后成功获取，预览纯文本，不生成 DOM 子元素；请求 URL、最终 URL、获取时间可见。短网页不截断，RFC 正文明确显示已截断。 |
| 明确保存 | 获取本身不产生文档；保存对话框使用实际 HTML `dialog`，默认焦点为取消。取消保存不写入，再明确确认后各生成一个 READY 文档。 |
| 来源与检索 | 生产 Runtime 保存来源网址、时间、截断说明和原文；UI 可打开源版本 1、检索关键词、从命中打开原文并显示准确范围高亮。RFC 文档为 4,550 bytes 的有界快照，不宣传为完整 RFC。 |
| 窗口重开 | 正常关闭并确认 WebView 清理完成，再创建真实主窗口；两份文档仍可读，重新检索成功。获取计数保持 2，保存计数保持 2。 |
| 模型与调用数 | `result.json`／`web-operation-counts.json`：2 次获取 POST、2 次保存 POST、3 次本地只读检索 POST。`integration.log`：`DESKTOP_REAL_WEB_HTTP_SQLITE_VERIFIED_MODEL_CALLS_ZERO`。 |
| 默认无公网回归 | `regression/integration.log`：原 `WebKnowledgeCaptureTest` 和既有 HTTP 核心场景 **2/2 通过，0 失败／中止／跳过**，确认默认 Web fixture 保留。包括原有 Store 重开、备份恢复和合成提供器引用；这些不是此次真实网页桌面中的模型测试。 |
| 构建 | Java 离线 test-compile 成功；最终 .NET Debug 构建 0 warning／0 error，校验并复制当前前端资产。没有更改前端源码或重复跑其全套测试。 |

保留 5 张成功截图：`public-preview-0.png`、`public-preview-1.png`、`saved-search-source-0.png`、`saved-search-source-1.png`、`reopened-knowledge.png`。已人工查看长文截断预览、源范围高亮及重开检索截图；只截自有 WebView，没有采集整个桌面。

`cleanup-verified.json` 记录四轮自有 Java PID 已退出、各随机 Runtime 监听关闭、与各新 Profile 匹配的 WebView 子进程已退出。默认无公网回归宿主／Java 也正常退出。没有停止其它服务或删除测试 Profile；合成数据和失败证据保留在本地。

## 首次失败与修正

保留第一次构建错误及全部前三轮失败，不将它们改记为通过。均为验收工具适配问题，本轮没有发现需要修改生产实现的缺陷。

1. 初次 .NET 编译：验收脚本辅助方法 `Button` 与 WPF 类型同名，静态 `ClickEvent` 引用冲突；改为完整类型名，后续编译通过。
2. `run-1`：实际已获取 Example Domain，但断言错误地要求正文包含页面标题。产品按合同分开呈现标题／正文；改为分别核对标题和正文关键词。未保存。
3. `run-2`：实际保存确认框已出现；脚本查询显式 `[role=dialog]`，而组件使用有隐含角色的原生 `<dialog>`，等待超时。改为 `dialog[open]`。未保存。
4. `run-3`：实际已保存一个网页并读回全部短文与来源；验收传输白名单把本地只读检索的 POST 当成未知写操作，主动拒绝。补齐两个固定关键词、最多 3 次本地搜索的白名单；没有改变公共目标范围或产品接口。
5. `run-4`：完整通过。前三轮各提交一次公开获取，成功轮次提交两次；整个桌面排查合计 5 次获取操作，未无限重试或扩大网站范围。

每次重试均基于明确的新诊断，使用新的 Profile；未复用失败身份、放宽校验或修改既有数据库。

## 成熟实现与适配判断

2026-11-09 重新核对 [FlaUI](https://github.com/FlaUI/FlaUI)：3,197 Star、未归档，最近 push 为 2026-08-13 21:45:21 UTC；读取 [MIT 许可证](https://github.com/FlaUI/FlaUI/blob/main/LICENSE.txt)。它适合外部 Windows UI Automation；本仓库已有实际 WebView／WPF 控件驱动、合成 Profile 和进程退出设施，能直接覆盖本轮目标，因此复用现有工具，没有引入 UIA 依赖、全局进程定位或焦点控制框架。

参考微软 [WebView2 线程模型](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model)，保持 STA／UI Dispatcher 和异步脚本调用，操作实际模态窗口控件后再等待对应脚本完成。FlaUI 仅作适配比较，未复制其代码或安装其包；没有对它做完整漏洞／供应链审计，Star 与维护日期不构成安全认证。jsoup 及安全 Fetch 的复用和版本评估沿用[功能交付报告](WEB-KNOWLEDGE-CAPTURE-REPORT.md)。

## 验证边界和回滚

- 这是实际桌面数据流验证，但使用验收 native adapter；不是完整 `AssistantApp`／DevelopmentRuntimeOwner 启动、正式受信安装包或签名发布验收。
- 重开了真实窗口和 WebView，**没有在本轮重启 JVM／整个应用后重新获取或复验**。Store 重开和备份恢复的证据来自默认无公网场景，不能改称本轮 GUI 备份恢复。
- 没有调用真实模型、验证网页知识回答质量或真实模型引用忠实度；检索命中高亮不是模型回答。也未覆盖 PostgreSQL GUI、物理键鼠、中文 IME、所有 DPI／分辨率及暗色主题。
- 仅两个公开页面通过，不能推广为所有网站兼容；截断、120 秒结果保留、会话 16 次获取上限和没有 URL 去重／自动更新的既有边界继续保留。W1 Search／Web Answer、个人恢复、Vision 和正式发布关卡未解除。

六份原有未跟踪用户文档在开工备份至 `originals/`，哈希清单为 `preserved-documents.json`，收尾全部未变且不暂存。Workspace 与 KAG 分开，无真实个人数据、既有服务或安全设置改动。回滚标签 `workspace-before-web-knowledge-live-20261109` 指向 `e635675`；当前验证与文档单独本地提交，不推送、合并或部署。完整前后补丁、文件哈希与运行记录保留在证据目录。

复现时：离线编译当前 Java 测试；用独立 `--artifacts-path`、`Mmf3ProfileAcceptanceBuild=true` 和已验证前端资产构建现有 `MemoryDesktopAcceptanceProbe.csproj`。将 `MemoryDesktopIntegrationProbe.java` 编译到 classpath 首位，再从普通、同用户非提升桌面宿主运行探针，传 Java、固定 JNA、classpath、全新证据目录四个绝对路径及第五参数 `web-knowledge`。此模式会真实访问上述两个网址，仅应显式运行；普通套件不启用该模式。
