# Knowledge 来源阅读：精确范围与上下文导航

2026-11-09。基线 `64a9503b24475a8c9d153efebf1bf29300c836d3`，分支 `codex/knowledge-source-reader`。本轮在长中文摘录之后补齐来源阅读交互，仅本地提交，未推送、合并或部署，仍为 Development-only。

## 用户问题和交付

原“打开引用来源”能按准确版本和起始偏移读回文本，但预览没有标明引用的结束位置；用户看到引用之后的上下文时，难以区分哪些文本是实际引用。直接从摘录中部打开时，原有返回栈为空，也不能方便地从本节开头核对前文，再回到引用。

现在打开引用后，预览只高亮当前页与实际引用范围重合的原文；其他文字明确标为上下文。预览展示实际源版本，并在已存在更新版本时提示仍在查看所选旧版本。“从本节开头阅读”“上一页／下一页文本”“返回引用位置”组成完整的阅读路径。检索结果也使用同一阅读器，标为“检索命中”，不混称为模型引用。

- 引用／命中的 documentId、revision、起止 UTF-16 偏移及行号只保存在本页内存；导航继续使用同一版本。切换版本、文档、会话或离页清除当前定位，迟到的来源读取不会重新填回。
- 复用既有 `previewKnowledge` 和严格的 documentId／revision／offset 回应核验。每次点击最多一次既有预览读取，正文仍受 4,096 UTF-16 单位和结构边界限制；没有预取、全文下载或额外模型调用。
- 相邻页使用后端返回的 `nextOffset`，本节开头使用后端 locator 起点，返回引用使用原始准入起点；没有用减法猜测可能落在代理字符中间的前页起点。
- 读取成功后才更新上限为 256 的返回栈；读取失败保留原文与返回位置，用户可以主动再读。后台导入状态更新不再自动把已经选定的阅读版本切到新版本。
- 从现有 KnowledgeSearch 抽出并共用 `HighlightedText`。所有原文仍是 React 文本节点，保留空白、换行、中文及 emoji，不解释 HTML／Markdown，不将原文或定位写入浏览器存储／URL。增加键盘焦点和可换行的导航按钮，沿用浅／深色主题变量。

只修改前端及对应文档；Java、Native、权限、数据库和提示契约不变。[ADR-013](../ADR/ADR-013-grounded-knowledge-answer-citations.md)补充了阅读范围语义。前一增量的 `knowledge-answer-v2` 配套构建要求仍生效。

## 成熟项目复用评估

2026-11-09 通过 GitHub 连接核对 [bvaughn/react-highlight-words](https://github.com/bvaughn/react-highlight-words)：2,304 Star、未归档，最后 push 为 2026-10-31 19:34:32 UTC。读取固定提交 `cf34c3900394b260328a3afd1d411fb0a2df4896` 的 [MIT 许可证](https://github.com/bvaughn/react-highlight-words/blob/cf34c3900394b260328a3afd1d411fb0a2df4896/LICENSE)、[包配置](https://github.com/bvaughn/react-highlight-words/blob/cf34c3900394b260328a3afd1d411fb0a2df4896/package.json)、[接口说明](https://github.com/bvaughn/react-highlight-words/blob/cf34c3900394b260328a3afd1d411fb0a2df4896/README.md)和[实际组件源码](https://github.com/bvaughn/react-highlight-words/blob/cf34c3900394b260328a3afd1d411fb0a2df4896/lib/components/Highlighter.tsx)。Firecrawl 开发文档搜索辅助定位接口，固定源码是判断依据。

该项目成熟且可直接适配：支持 React 19、TypeScript，以及通过 `findChunks` 提供精确范围；渲染使用 React 文本子节点。其包版本为 1.0.0，依赖 `highlight-words-core` 2.0.0。默认／自定义词匹配能力仍需调用者配置，不能代替本项目已有的版本授权与坐标核验。这里没有发现必须引入新的匹配引擎的需求；也未做全面供应链漏洞审计，Star 和近期提交不代表安全认证。

实际选择是**直接复用仓库已有的 React／KnowledgeSearch 文本范围渲染**，移出原函数供两处共享，而不是再建高亮引擎或新增第三方依赖。开源组件能够工作，但增加它及其词匹配依赖不会补充本任务缺少的能力；真正需要实现的只有本领域的版本绑定、范围交集和用户阅读导航。没有复制上游代码、安装依赖或增加服务。

## 核心检查与首次失败记录

本地证据：`.verification/knowledge-source-reader-20261109/`。复用两个现有测试文件，只新增两个综合场景，没有添加测试框架或全量矩阵。

| 记录 | 结果 |
| --- | --- |
| `frontend-test.log` | 首次 8/9 通过；新增 fixture 在错误回应中多带 `result` 字段，被现有严格 Bridge 合同拒绝。修正 fixture，不放宽生产合同。 |
| `frontend-test-final.log` | 第二次 8/9 通过；新增断言误把最后一个 Bridge 请求当作来源请求，实际还有既有索引状态查询。按明确方法筛选被检查的请求，保留该次原始日志及其原文件名。 |
| `frontend-test-run3.log` | 最终 **9/9 通过**，2 个文件通过，无失败／跳过。 |
| `frontend-build.log`、`frontend-build-final.log` | 两次 TypeScript、Vite 和既有资产清单生成通过；最终生产代码对应后者。仍有既有的大于 500 kB 单 JS chunk 提示，没有在此增量扩展分包工作。 |

新增场景通过真实 `WorkspaceClient`／合成 Bridge 验证：当前文档已有版本 2 时仍打开版本 1；正文和高亮严格对应引用；前文页在 emoji 前的 UTF-16 边界结束，后页完整保留 emoji；跨页高亮仅取交集；额外上下文不被标为引用；返回读取失败后栈仍可用；明确换版本后清除旧定位；错误版本回复拒绝；离页／换会话后的迟到回复不能恢复正文。既有检索、删除确认、未知导入不重发、IME、回答错误和纯文本渲染场景一起通过。

执行范围：

```text
npm.cmd test -- --run src/components/KnowledgeAnswer.test.tsx src/pages/KnowledgePage.test.tsx
npm.cmd run build
git diff --check
```

没有运行 Java／.NET 全套、真实模型、数据库或完整桌面。仅确认 DOM 交互和代码构建，未完成真实 WebView 渲染、DPI／物理键盘或正式包验收。

## 文件保护、回滚和剩余边界

开工时只有一个 Git worktree，已跟踪文件干净；HEAD 包含 `f6ff174` 和 `64a9503`。六份既有未跟踪文档按原路径备份到本次证据目录 `originals/`，并记录 `preserved-documents.json`；收尾逐个核对 SHA-256 相同。未发现额外已跟踪并行改动，原文档继续保持未跟踪，没有打包进本功能提交。

回滚标签 `workspace-before-knowledge-source-reader-20261109` 指向 `64a9503`，原分支保留。提交前另存补丁及本功能文件副本；只显式暂存本次源码、测试和三份交付文档。需要撤回可单独 revert 本功能提交，不 reset／clean，不改 main。Git 回滚不替代个人数据库备份。

没有读取真实 Profile、凭据或个人资料，没有启动或停止既有服务、改变安全设置或调用 KAG 端点。普通沙箱命令最初因本机沙箱目录锁初始化失败未执行；经过工具审批的命令成功完成后续本地工作，没有解除系统安全设置。

高亮保证的是所选版本与坐标范围的可见对应，不保证回答忠于原文，也不表示上下文已进入模型。阅读起点限于现有结构 locator，跨结构继续用下一页；没有全局来源搜索或持久知识回答历史。真实模型摘录质量、受信桌面包、PostgreSQL 全流程、个人备份／恢复及 Production GO 的既有缺口保留。下一步仍应在明确独立资源范围后，用合成资料补齐有限真实模型与受信桌面验收。
