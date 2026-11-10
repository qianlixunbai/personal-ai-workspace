# 所选资料版本内的原文字面查找

2026-11-10，接续 `6cd368d`，沿用 `codex/knowledge-version-compare`。本轮选择并完成一项真实产品增量：**在所选资料版本内查找精确短语，查看命中摘要，再打开对应原文**。没有把源码、测试和打包分别计作三个功能。

## 用户价值与完成判据

原目标是可日常使用的 Windows 工作区，资料及旧回答应能追溯到具体来源。原有词法检索面向当前可用版本；原文阅读器能分页和返回引用，但无法在旧版本中查找精确编号、标点或短语。版本对比也不能替代这一阅读操作。

完成判据：按用户选择的版本完整查找；跨预览页的短语仍能匹配；摘要、行号和原文高亮指向相同 UTF-16 范围；读取失败或超过预算不把局部结果当完整结果；取消、版本／文档变化、会话轮换和离页隔离迟到回复；不需要模型、索引重建或新的数据写入。

## 交付行为

入口：**我的文档 → 选择文档及已发布源版本 → 在此版本中查找**。输入后点击“查找此版本”，不会随输入或展开自动读取。

- 区分大小写、按字面匹配空格、换行和符号，无正则表达式、分词或隐式归一化。短语上限 256 UTF-16 单位，拒绝空白及不完整 Unicode。
- 沿用已有版本对比的完整原文读取：固定 documentId／revision、每页现有 `knowledge.preview`、4,096 单位响应和实际 `nextOffset`。共享读取函数仍保持 100,000 UTF-16 单位、10,000 行、128 页上限；查找总时间预算 30 秒。不增加桥接方法、Runtime API、存储 schema 或权限。
- 只有完整版本读完才匹配，支持跨页和重叠命中。统计全部命中，最多保留前 200 处摘要，每页显示 20 处；超过时建议使用更具体的短语。约 10 万字的 UI 说明是便于阅读的近似值，表情等补充字符占两个 UTF-16 单位。
- “打开第 N 处原文”重新通过原通道读取该版本和精确起点；仅高亮当前页与命中范围的交集。查找命中、检索命中、模型引用使用不同标签；旧版本不会被替换成最新版本。
- 查询和结果仅保存在页面内存。停止后等当前只读请求结束，禁止再发送下一页；切换版本／文档、会话轮换、离页会清除结果，迟到回复不会恢复结果。无自动重发、模型调用、外部服务、浏览器存储或 URL 写入。
- 所有正文和摘要继续使用 React 文本节点；不执行来源 HTML／Markdown，也不自动加载其中的链接或图片。

方案直接复用项目已有 `readComparisonSource` 的读取合同及 `HighlightedText`，只提取两处共用的完整版本读取函数。字面匹配使用 JavaScript 内建 `indexOf`，没有需要选型的新引擎或依赖，因此没有新增 GitHub／外部研究、下载或服务调用。原版本对比的范围、错误和预算保持不变。

## 必要验证与失败保留

证据目录：`.verification/knowledge-source-find-20261110/`。仅在既有三个前端测试文件范围内执行，新增三个综合场景；不增加测试框架、Java／.NET 全套或真实数据库验收。

| 证据 | 结果 |
| --- | --- |
| `frontend-test.log` | 首次 12/24 通过。新增组件与辅助文件仅靠大小写区分名称，Windows 模块解析选错文件；修正为 `KnowledgeSourceFind.tsx` / `knowledgeSourceMatches.ts`，未放宽测试或产品校验。 |
| `frontend-test2.log` | 同一组 **24/24 通过**：跨页精确查找、旧版本定位／高亮、失败不展示部分结果、停止／离页／会话清空、Unicode／重叠／多行命中及 200 条展示上限；原引用阅读与版本对比回归通过。 |
| `frontend-check.log` | 首次类型检查发现严格索引下可能为 undefined；生产代码改用已界定范围的 `charAt`，夹具标明已存在元素。 |
| `frontend-check-final.log` | 最终 TypeScript 检查通过。只修正类型问题后未重复全组测试。 |
| `test-cleanup.json` / `test2-cleanup.json` / `check-cleanup.json` | 各次自有 Job 在清理前均为 0；锁和 Job 已释放。失败过程原样保留。 |

重资源复用原包装和 `Local\Dot_KAG_Workspace_HeavyRun`，自有 kill-on-close Job、每进程 768 MiB／整 Job 2 GiB、Node 384 MiB、单测试 worker、450 秒外层总时限。没有查询旧进程、端口或 Profile，没有重试任何继承的拒绝操作。

## 交付与剩余边界

实现及必要验证完成后才本地提交，可单独 revert；不修改 main、不推送。六份原用户文档通过入场哈希逐个保护，真实资料／数据库／凭据／默认配置／已安装应用和旧包不动。

本轮不是通用大文档搜索、语义检索、OCR 或全库历史搜索。超过既有读取上限的资料保留分页阅读；本地 DOM 流程通过不等于完整 EXE／真实 WebView／PostgreSQL 桌面新功能验收。没有重新执行已通过的 r2 生命周期观察或旧模型 guard 检查。

产品实现已提交为 **`1cd8c42b5cab9b2404ce4dddf9d04e78d9850574`**。基线 `6cd368d` 保持在祖先链，回滚可单独 revert 此功能提交。最后的类型修正后，完整构建再次通过 TypeScript、Vite 资产生成和 Desktop publish；未重新运行完整 EXE。

实际产品代码发生变化，因此生成[独立开发 ZIP](../../artifacts/PersonalAiWorkspace-source-find-20261110.zip)及[解压目录 EXE](../../artifacts/PersonalAiWorkspace-source-find-20261110/PersonalAiWorkspace.Desktop.exe)，未替换既有安装、默认入口或旧包。包中的源版本是 `1cd8c42`，后续报告提交不改变产品字节。

| 项目 | 结果 |
| --- | --- |
| ZIP | `PersonalAiWorkspace-source-find-20261110.zip`，384,744,768 bytes |
| ZIP SHA-256 | `1842F37F9FB44D3BB7E376F46F0E09ED2436FD7805D50345BB5F3EAD750A2FD5` |
| Runtime JAR SHA-256 | `CBFA0818F1163BC8C398D9BE63BA99A857F63397D75BF7C62A5124870354FBE4` |
| Desktop DLL SHA-256 | `9D72D8751ED5E82F57D40FF831F8D8AB0BA3DED47B67C33A3BD86EDFD7A77445` |
| 构建／归档 | 194 个 Java 源文件、567 个 class、248 个沿用资源／依赖条目；全部 JAR 条目可读取，无旧应用 class 残留 |
| 分发校验 | 933 个载荷、934 个 ZIP 条目、420 项实际程序集内嵌来源 pin；构建前后产品源码摘要一致 |
| 资源收尾 | `build-cleanup.json`：20:12:08Z 清理前 Job 0，锁与 Job 已释放，failure=null |

复用原 `scripts/verification/package-integrated-development.ps1`，以有效 r2 包为离线依赖／资源基线；产品 Java、React 和 Desktop 全部重新构建。保留既有 Java 75 项 `ExtendedOpenOption` 等警告和 Vite >500 kB chunk 提示，无编译失败。证据为同目录 `delivery-result.json`、`runtime-content-audit.json`、`product-sources.json`、`frontend.log`、`desktop.log`、`build-cleanup.json`。没有修改打包脚本或新建测试框架。

最终复核：main 仍为 `f5266bf`；六份用户文档 SHA-256 和旧 r2 ZIP SHA-256 与入场相同。原有效包保留其已完成的真实 EXE 验收，新包只声明本次源码、合成前端流程与打包验证，不继承新功能的完整桌面验收。

真实模型恢复和完整维护仍 **BLOCKED / productionGo=false**；本地退出或取消不能证明远端执行结束。下一次若需要扩大本功能验收，应只在全新合成资料上核对新包的实际“选择旧版本 → 查找 → 原文高亮”操作，不重复已完成的 r2 生命周期观察，也不把增加同类 guard 当作功能开发。本轮没有被拒或结果不确定的动作；没有后台任务继续运行。
