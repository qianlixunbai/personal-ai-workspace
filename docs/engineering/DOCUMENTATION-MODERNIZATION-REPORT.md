# 文档与 GitHub 首页现代化报告

2026-10-28（Asia/Shanghai）。基线 `main @ 12dce0765dcdb89b75fd605c2b4fc7bf5db00617`；交付分支 `codex/docs-github-modernization`。仅整理文档，未改生产源码、测试、配置、持久格式或门禁。最终 commit 由 Git history 和交付回复标识。

[当前状态](../STATUS.md) · [文档索引](../README.md)

## 审查范围与主要发现

入场已重新 fetch，main 与 origin/main 均为指定基线，只有 main 分支、一个 worktree，工作区干净。基线 Git 跟踪的 Markdown 为 128 个 / 25,071 行；此数来自盘点，不是测试数量。

审查采用 Markdown 全量标题／链接盘点，再核读当前入口、ADR、近期相关工程报告和真实 owning code。没有把每份早期报告都当成最新产品说明；历史证据按其日期、提交与包身份归属。

| 冲突／入口问题 | 本轮处理 |
| --- | --- |
| README 852 行、STATUS 1,280 行、Roadmap 1,058 行、Architecture 1,178 行，累积多份 Current | 完整归档四份旧正文，当前入口重新编写；不删除历史报告 |
| “K3 NOT STARTED”“Knowledge / Finance 不在产品范围” | 按实际 API、Store、Bridge、页面与 K1–K3 / F-next 报告改为已实现的有限能力 |
| “没有 Maven 子模块”“Finance 只能经未来 Gateway” | 按 reactor POM、workspace-application → finance-domain、独立 DataSource 和只读服务更正 |
| PostgreSQL / 启动 / Profile 的旧 PRIVATE_ACE 阶段覆盖后续实现 | 区分当前受信 Development 路径与历史故障；保留未完成人工关卡 |
| start-workspace.cmd 被写成普通可用启动入口 | 根据 scripts/start-workspace.ps1 的立即拒绝明确正式启动阻断，单列开发构建前提 |
| ADR 索引将 W1A / MMF 写为 implementation NOT STARTED | 更新索引当前实施说明；原 ADR 批准正文只增加历史提示，合同与证据保留 |
| Finance 首页仍以 Twelve Data / NOT RUN 为最新 | 补 M1.2 Massive 当前显示范围，标明用户人工报告和不保证最新；保留旧阶段正文 |
| 文档缺少统一导航与安全开发说明 | 新增完整文档索引、Development Guide 和归档说明 |

静态核对的代码包括：根／模块 POM、frontend package、.NET props / csproj、正式启动脚本、受信开发构建与 Bootstrap hash 校验、Program / DevelopmentRuntimeOwner / Bridge / RuntimeClient、API controllers、ActiveModelManager / Ollama admission / Vision policy、Finance adapters 与行情查询、Memory / Knowledge / 备份 ownership。

## README 展示选择

首页按产品定位、真实 Windows 渲染图、能力与边界、架构／技术栈、开发 Quick Start、状态限制和文档导航组织。没有下载量、CI／发布成功徽章、安装器或正式发布承诺。

组织方式参考 [Ollama README](https://github.com/ollama/ollama/blob/main/README.md) 的短定位／快速开始，以及 [Cherry Studio README](https://github.com/CherryHQ/cherry-studio/blob/main/README.md) 的文档入口／场景截图。只借鉴信息层级，没有复制其代码、文案、Logo、截图或宣传指标。

复用既有 `docs/design/ui-maturity/` 下 assistant-after、knowledge-after 和 quick-actions-after 三张图；逐张视觉检查未见 Token、个人绝对路径或真实资料，内容为合成／空数据。settings-after 也做了视觉审查，但未加入首页。截图来源是本机 WPF / WebView2 的隔离 UI fixture，不能当成真实模型成功、前台激活或完整验收证据；说明紧邻图片。

后续可在真实关卡完成后更新当前版本的 Conversations / Memory / Finance、深色模式与混合 DPI 截图；届时必须再次审查隐私和实际运行证据，不能直接从 ignored 目录批量发布。

## 历史和私有资源保护

- 四份旧入口正文保存在 [history](../history/README.md)，只增加历史提示和重定位相对链接，并提供不可变基线链接。
- 原工程报告、里程碑、设计文档、审计和已有 PNG 均保留；没有改写其原始执行结果。
- ADR-014 / ADR-015 保留原合同，只增加当前阅读说明；ADR index 与 Finance 模块 README 增加当前导航。
- `.runtime/`、`.verification/`、`artifacts/`、TestResults、数据库、凭据及 ignored 私有资源未复制到文档归档。
- 没有读取凭据内容、启动模型／数据库、调用真实 Provider 或重建包。

## 本轮验证

实际执行：

- 基线 128 个 Markdown / 644 个本地链接／锚点；整理后 136 个 Markdown / 923 个本地链接／锚点，均无缺失目标或缺失锚点。覆盖 Markdown、HTML 图片／链接和 reference definitions；这是本地静态检查，不声称外部链接全部在线可用。
- 四份历史归档与固定基线比较，除相对链接重定位和新增历史提示外正文一致；新改动仅 Markdown，未改历史 PNG 或生产文件。
- 变更文档的常见 Token／secret 形状与绝对用户目录模式检查无命中；首页三张图片逐张视觉审查，PNG metadata 未见个人文本字段。
- Kroki 在线 Mermaid 校验返回 HTTP 403；改用仓库外临时下载的 Mermaid 浏览器运行库与本机 headless Edge，两张图均 parse / SVG render 成功，并检查渲染图。没有新增仓库依赖或提交生成图片。
- 使用 Markdown-It 和本机 headless Edge 检查 README 本地预览，图片加载与表格层级正常；这是本地阅读预览，不是 GitHub 页面实测。
- Git diff / staged diff / whitespace 检查以及提交后的远端核验按本轮实际交付执行；ignored 资源仍在原位，未用于公开备份。

没有运行生产测试、完整验收、正式构建或打包。

本轮截图为既有文件视觉审查；开发命令只静态核对，没有在干净机器上执行 Quick Start。保留 Bootstrap pin、外部依赖和本机安全准入限制。

## GitHub About / Topics

入场只读确认仓库 About / homepage 为空、Topics 为空，默认分支 main，当前账号具有 admin / push 权限。连接器未提供仓库元数据编辑能力；实际通过本机 gh 的标准接口同步并回读核验，不改权限或默认分支。

已同步 About（内容与不变的源码主线能力一致）：

> Development-only Windows AI workspace with local Ollama, explicit memory, cited knowledge answers and read-only finance. Vision blocked; acceptance partial.

已同步 Topics：`local-ai`、`windows`、`wpf`、`webview2`、`react`、`java`、`spring-boot`、`ollama`、`personal-knowledge-management`、`personal-finance`。

homepage 保留空值：当前没有独立官网，不把开发分支 README 伪装成已上线站点。重新 fetch 后 main 仍为原基线；本轮开发分支不自动合并 main，新 README 在合并前仅出现在开发分支。

## 状态不升级

**X1 Windows Acceptance PARTIAL / MODEL_EXECUTION_UNCERTAIN / Vision BLOCKED / Development-only / 尚无已核验的 GitHub CI PASS / 尚未 Production GO** 全部保留。文档 Commit / Push 不构成真实验收或正式 Release。
