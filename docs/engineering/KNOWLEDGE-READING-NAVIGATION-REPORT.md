# 来源回查、行号定位与问答草稿保护

2026-11-10，接续 `b5f0f64`，沿 `codex/knowledge-version-compare` 完成三个实际使用流程。产品提交 **`370c313`**。只改前端，不新增 Runtime API、数据 schema、权限、模型调用或依赖。

## 已完成的产品行为

1. **跨来源返回／前进。** 原阅读器的“上一页文本”只保存当前来源内的分页位置；打开另一份引用、查找命中或切换版本后，无法回到先前资料。现在“返回上一处来源／前进到下一处来源”保留最近 32 个位置的文档 ID、不可变版本、准确偏移、原范围和分页返回栈。返回时重新读取详情及准确版本原文，全部成功才移动记录；来源缺失、错误响应或读取失败保留当前正文和记录，不用缓存正文冒充仍可访问的来源。新来源跳转清除前进分支；删除当前文档后移除其阅读位置；离页／会话轮换清空记录，迟到回复不能恢复。
2. **按所选版本的原文行号定位。** 引用和笔记已有行号，但原界面只能找词或逐页阅读。新入口明确定位已保存纯文本的行号，不是 Word／PDF 页码。复用已有 `readSourceText` 完整读取合同：100,000 UTF-16 单位、10,000 行、128 页及 30 秒预算，确认完整后计算行首偏移，再经原 Preview 读取并高亮该行。原始换行和 emoji 坐标保留；没有此行、文本末尾无正文、失败或超限时保留阅读位置。没有预取、持久书签或行号索引，长资料仍用原分页阅读。
3. **保护尚未提交的知识问题。** 原历史按钮会覆盖当前问题／关键词／资料范围，删除正在查看的历史也会清空其后输入的新草稿。现在对比上一次成功受理或历史读回的表单内容；有修改时复用既有确认对话框，默认焦点为取消。只有确认且历史成功读回才替换，失败仍保留草稿。未修改的已保存内容无需重复确认；删除历史保留后续修改但移除已删除答案。会话变化清理确认框与草稿；不触发模型重放。

实现直接复用现有 Preview、完整版本读取、Highlight、ConfirmationDialog 和历史 API，无引擎或第三方库选型争议，未新增外部研究／下载。原模型引用和检索命中仍与行号定位分开标识；位置高亮不等于回答事实正确。阅读记录只存在当前页面内存，不保存正文副本，不写浏览器存储或 URL。

## 必要验证与保留的失败

证据目录：`.verification/knowledge-reading-navigation-20261110/`。只新增并选择三个综合场景，不重跑上一轮 24 项组，也没有 Java、Native、数据库或模型验收。

| 证据 | 结果 |
| --- | --- |
| `frontend-test.log` | 首次 2/3 通过；新行号组件与原查找组件的 React sibling key 相同，切版本后出现重复控件。失败保留，产品改为专属 `line/` 前缀，没有修改断言去接受重复控件。 |
| `frontend-test2.log` | 3/3 通过，另 22 项未选中。跨文档旧版本回退／前进、范围和分页栈、失败不消耗位置、离页迟到隔离；跨页／emoji 后行号、HTML 纯文本、缺行／失败保持；历史读取取消／失败／成功、仅范围改变、删除后草稿及会话清理。 |
| `frontend-test2-check.log` | TypeScript 通过。 |
| `test-cleanup.json` / `test2-cleanup.json` | 两轮清理前自有 Job 均为 0，锁／Job 已释放；失败原件仍在。 |

沿用已有 `Local\Dot_KAG_Workspace_HeavyRun` 包装、自有 kill-on-close Job、450 秒总时限，每进程 768 MiB／整 Job 2 GiB，Node 384 MiB、单 worker 串行文件。静态读写和 Git 不占重资源锁。没有查询旧端口或进程。

## 原桌面阻塞的只读核对

保留上一轮准确目标：`C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2\model-state`。被拒动作是在该新合成 Profile 下用 `new ModelStateStore(...)` 建立验收用的本地模型阻断登记。**被产品校验拒绝，结果已知；不是自动审批拒绝、不是未知执行。** EXE 依赖准备成功，故此前未启动，本轮也未重试。

本轮仅查看仓库中的 `Seed.ps1`、`SeedReading.java`、失败日志／已有回执及产品源码。得到以下更精确的检查顺序：

- 堆栈落在构造函数第 93 行的 `verify(root, true)`，即根目录校验；尚未执行后续 `.lock` 创建和 `armGuard`。
- `account` 由 `lookupPrincipalByName(System.getProperty("user.name"))` 建立。Windows 分支的目录创建／验证另走 `WindowsPrivateAcl`：取线程有效令牌 SID，无线程令牌才取进程 SID；按句柄检查 owner、DACL、真实目录类型和 reparse 边界。
- `verify` 的第 496 行将 Java 目录类型与 `Files.getOwner(...).equals(account)` 合并为同一个错误。若当时确实进入 Windows 分支，原生 owner/type 检查在这行之前已返回；这是源码控制流的条件推断，不能替代运行时身份采样。
- 现有回执没有记录失败时的 Java `user.name`、解析后的 principal、有效令牌 SID 和目录属性。因此“两种身份来源不一致”是可解释该结果的排查方向，不是已经证实的根因；也不能由 Profile 路径中的账户名推断实际执行身份。

没有改 owner／ACL、Java 身份属性、检查规则或安全设置；没有重新访问该目录属性、重新触发拒绝、换 Profile／身份／启动器或复制样本绕过。若要继续这一关卡，应先获得对原样本及同一正常执行上下文进行精确只读身份／类型对照的明确可执行范围；本轮未请求夜间操作，也没有足够证据据此修改产品所有权检查。任何恢复重试仍须遵守原拒绝边界，不能仅以“合成数据”作为豁免。

## Git、文件保护与交付边界

入场 HEAD 为 `b5f0f64d80fbc8d03fb6232487d0a762d3af0967`，main 为 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。六份用户文档保持未跟踪，入场哈希与前轮相同。保留 `product-before-commit.patch` 及新增文件副本后，仅暂存六个完成验证的产品／测试文件。

首次普通沙箱 `git add` 因不能创建 `.git/index.lock` 返回 Permission denied；确认暂存区为空、HEAD 未变、锁文件不存在。按规定读取用户历史，核实已有 Git 回滚授权，再经工具审批完成限定的本地暂存／提交；没有修改文件 ACL 或安全设置。提交 `370c313` 成功，没有自动审批拒绝或未确认提交结果。可单独 revert 本产品提交；不改 main、不推送，Git 回滚不代表数据恢复。

完整 EXE／真实 WebView 的新增流程尚未验收；源码检查和构建不能代替。真实模型恢复、旧未知请求、完整维护继续 **BLOCKED / productionGo=false**。不读取真实凭据、不迁移真实数据库、不调用真实模型／付费服务、不改默认配置或现有安装。本轮没有触碰 KAG 或其他仓库。

执行接口未提供切换／核验当前模型、thinking 或 service tier 的能力，故不声称已验证请求的运行参数。产品工作按已授权范围完成。

## 配套开发包

已用既有离线整合脚本生成[独立开发 ZIP](../../artifacts/PersonalAiWorkspace-reading-navigation-20261110.zip)和[解压 EXE](../../artifacts/PersonalAiWorkspace-reading-navigation-20261110/PersonalAiWorkspace.Desktop.exe)，产品来源为 `370c313ec209a9eaa5388c6f974adb7b539fcad3`。后续文档提交不改变包中的产品字节。旧 r2、source-find、source-reading 包保留，不覆盖安装或默认入口。

| 交付证据 | 结果 |
| --- | --- |
| ZIP SHA-256 | `7929FDDBEACA575EDDEF6057D8A8BE141FF06E6FB551285C04959856F40A6A35` |
| Runtime JAR SHA-256 | `A91755813AB37779A8B0F59A4CDDF4660F5CE63DA9AFF293110ACBEC5CFFB618` |
| Desktop DLL SHA-256 | `3C232E6B2F525310725E670107AA1577CB084D661908C6E7D7E0989F0F5ECC3E` |
| 完整离线构建 | TypeScript、Vite、Java 全部产品源、Desktop publish 通过；保留既有 Java 警告和 Vite 大 chunk 提示。 |
| 来源／归档 | 194 个 Java 源文件、567 个 class、933 个载荷、934 个 ZIP 条目及 420 项内嵌来源 pin 核对通过，构建前后产品源码稳定。 |
| `build-cleanup.json` | 20:40:53Z：自有 Job 清理前为 0，锁／Job 释放，failure=null。 |
| `protected-final.json` | main、六份用户文档及上述三个旧包哈希均与入场相同。 |

最终没有后台开发或验收任务。已完成三个实质流程，下一会话从本报告和 Git 状态接续；不要重跑本批已通过组或旧 model-state 准备。真实桌面关卡与真实模型恢复仍独立未完成。
