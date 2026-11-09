# 文档版本对比与 Word 更新闭环

2026-11-09。基于 Word 导入及内容保真修复 `2bf5f1c`，开发分支为 `codex/knowledge-version-compare`，回滚标签 `workspace-before-version-compare-20261109` 指向该基线。继承此前全部功能提交，没有平行重建产品或更改本地 `main @ f5266bf`，没有推送。仍为 Development-only。

## 交付与使用

已有文档支持不可变源版本，但更新资料后只能分别打开原文。现在选择拥有两个以上已发布版本的文档，展开“对比源版本”，选定基准／目标版本并点击“开始对比”，可看到完整保存文本的逐行增删、未变内容及对应版本原文入口。默认选择最近两个版本；选择相同版本时不发起比较。

新增、删除用文字和加减号区分，未变段落折叠；空格、末尾换行、中文及 emoji 均参与精确比较。替换与移动可能表现为删除加新增，不推断语义。原文按钮使用该段在对应不可变版本中的 UTF-16 偏移，旧版预览继续提示当前版本不同；打开原文不会清除对比结果。新版本不重写旧问答、引用或源文件。

TXT、Markdown 与已确认的 Word 正文摘录共用这一入口。**Word 比较对象仍是保存的摘录，包括文件名、SHA-256 和省略说明，不是 Word 原文件的图片、版式、批注或修订视图。** 原文件仅有元数据变化也可能造成摘录头部变化，不应把所有增删都解释为正文改动。

## 组件复用评估

选择 [jsdiff](https://github.com/kpdecker/jsdiff) 的 npm 包 `diff@9.0.0`，固定版本并保留 lockfile 完整性摘要，直接使用 `diffLines`，不自建差异算法。官方项目页面约 9.2k Stars，BSD-3-Clause；npm 官方元数据记录该版本发布于 2026-04-13，上游 [release-notes](https://github.com/kpdecker/jsdiff/blob/master/release-notes.md)包含对应版本变更。GitHub Releases 页面没有发布条目，不能将其描述为 GitHub Release 已核验。包自带 TypeScript 类型，没有新增传递运行依赖，ESM 可由现有 Vite 打包到 WebView2。

也检查了 [Google diff-match-patch](https://github.com/google/diff-match-patch)，约 8.1k Stars、Apache-2.0，但原仓库在 2024-08-05 归档。其算法仍有价值，本轮选择有近期包维护记录、类型声明和有界异步逐行接口的 jsdiff。没有引入独立编辑器、服务或模型。

安全检查没有假定“无公告”：上游 [GHSA-73rr-hh4g-fpgx / CVE-2026-24001](https://github.com/kpdecker/jsdiff/security/advisories/GHSA-73rr-hh4g-fpgx)记录 `parsePatch`／`applyPatch` 的拒绝服务问题，8.0.3 已修复，9.0.0 不在受影响范围；本功能只调用 `diffLines`，不解析或应用补丁。`npm audit --omit=dev` 返回 0 个已知运行依赖漏洞；这不等于完整安全审计或开发依赖审计。完整 BSD 版权及许可放在 `ThirdPartyNotices/jsdiff.txt`，随桌面构建和发布复制。

比较文本仅由现有授权 Bridge／Runtime 原文接口读回，在 React 中作为纯文本显示；不执行 HTML、不请求文档链接、不写浏览器存储、不添加网络权限。Runtime、数据库 schema、个人恢复边界和模型授权均未改变。

## 资源与失败语义

- 每版上限为 100,000 UTF-16 单位、10,000 行、128 次分页读取；各页必须匹配文档、版本和连续偏移，到达明确的文本结尾后才允许比较。不同结构的分页大小可能不同，因此不是 Word 页数限制。
- 两版串行读取，30 秒为页面边界检查的协作式期限；正在等待的单个 Bridge 请求仍使用原有超时。停止操作等待该次只读请求返回后退出，不再读取下一页或显示迟到结果；不是强行中断 Runtime 请求。
- jsdiff 使用异步回调、500 ms 协作式算法期限和 2,000 次行编辑上限；结果最多 1,000 段。期限不是独立线程或进程的硬终止。超限、缺失版本、读取失败或过大差异明确失败，不显示前缀冒充完整结果。
- 切换文档、会话或离开知识库时失效旧读取；对比内容只在当前组件内暂存。比较没有持久结果缓存，重开后按需重新读取两版。

## 必要验证与证据

证据根：`.verification/knowledge-version-compare-20261109/`。只增加 4 个聚焦前端场景并复用既有 Word 完整应用探针，没有新建测试服务或大矩阵。

| 证据 | 结果及实际范围 |
| --- | --- |
| `npm-metadata.json`、`npm-install.log`、`npm-audit.json` | 固定 9.0.0，只新增一个包，安装禁用脚本；运行依赖审计零已知漏洞。 |
| `frontend-test-1.log` | 首轮 8 通过、1 失败：多个原文按钮使用同名，测试定位歧义。改为“版本＋行号”的可辨识按钮，未改写旧失败记录。 |
| `frontend-test-2.log` | 9/9：4 个新场景及原有 5 个 Knowledge 页面场景；覆盖跨页、中文／emoji 偏移、HTML 纯文本显示、旧新原文定位、停止／卸载迟到结果、错误版本／偏移、读取大小／页数、空格／末尾换行与过大差异。 |
| `frontend-build-final.log` | 最终 TypeScript、Vite 和资产清单构建通过。保留单包超过 500 kB 的 Vite 提示，没有提高阈值掩盖；本轮不是前端分包优化。最后提示文案调整由该构建和随后桌面流程覆盖，未重跑无关测试。 |
| `probe-build-1.log`、`probe-build-2.log` | 首次探针构建因数组误用 `Count` 失败，改成 `Length` 后最终 .NET 构建 0 warning／0 error。普通 Runtime JAR 与 Java 源码不变。 |
| `run-1/first-result.json`、`first-comparison.json` | 完整 AssistantApp／WPF／WebView2／Bridge／HTTP／SQLite：合成 Word 预览取消、确认初版、确认导入同文档第二版；限定检索、完整版本对比及打开旧版原文通过。文本分别 6,852／6,864 UTF-16 单位，均超过单页 4,096，实际读取跨页。 |
| `run-1/reopen-result.json`、`reopen-comparison.json` | 第一轮 App／JVM 退出后独立启动，再读取两版并比较，摘要一致，旧版原文跳转通过；随后只删除本轮记录的合成文档，原三份文档 ID 集合恢复，原三条问答历史数量不变。 |
| `run-1/first-comparison.png`、`reopen-comparison.png` | 实际页面截图保留；已目视检查首轮截图：来源头部和 `--inspect=on` → `--inspect=review` 增删清晰，行号／版本及原文按钮可见，未变长段折叠。 |
| `run-1/completion.json` | 两轮应用退出码均为 0，0 模型调用，自有进程退出，11435／18765 测试端口关闭，最近工作区选择内容保持一致。 |

旧版规范化文本 SHA-256：`45E945D16214DC63E0D6E0CBEF7D53281A1C9EBB86B1FC0D93430DFFF9819473`。

新版规范化文本 SHA-256：`86BF5DEBBCBDA98870D8806B22F844FE5954397F6A55EE4ADC7EB469B4E4FE34`。

## 交付边界

同一分支包含 Word 导入、内容保真拒绝与版本对比，已走通“确认导入 → 更新同一文档 → 对比 → 回看旧版 → 重开再读”。它不是任意规模文档比较、Word 修订合并、语义变更解释或新模型质量验证。PG 完整桌面、个人数据恢复、物理键盘／IME、正式安装包及跨机器兼容性未随本轮验收。

六份用户未跟踪文档继续原样保留；只使用已有归属证明的合成 Profile，未读取真实个人资料。开始前检查计算进程、空闲内存与测试端口，退出由自有 Job 约束；没有启动模型或修改 KAG／既有服务，没有跨项目资源锁或直接调度 KAG。最终隔离检查及 Git 提交信息分别记录在 `final-isolation.json` 与 `final-git.json`。Git 回滚点只覆盖代码及跟踪文档，不等于数据备份。
