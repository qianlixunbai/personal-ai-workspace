# 资料前文阅读与引用跳转连续性

2026-11-10，接续 `fb19ea1`（版本内查找产品提交 `1cd8c42`），沿原功能分支开发。本次完成两个相互关联的用户流程，与上一项版本内查找组成三个实质功能；构建和验收不另计功能数量。

## 1. 从中段命中读取尚未访问的前文

原阅读器的“上一页文本”是已访问位置的返回栈。直接打开引用／查找命中时返回栈为空，不能读取前一段；“从本节开头阅读”也只能到当前结构块，无法越过前一结构边界。这会阻碍用户核对引用之前的适用条件。

新增“阅读前文”：Runtime 从同一不可变版本返回紧邻当前偏移之前的一页，最多 4,096 UTF-16 单位，限定在实际结构块内，完整保留代理字符。每次显式点击只发一次只读请求，不从文档开头扫描、不扩大全文下载预算。连续向前可跨结构块；到偏移 0 禁用；原引用范围和“返回引用位置”仍保留。

实现沿现有 Preview DTO 增加 `GET /documents/{id}/preview-before?revision=...&before=...`、Core/Bridge `knowledge.previewBefore` 和前端只读接线。旧 `knowledge.preview` 合同不变；Native-only、会话文档授权、版本绑定、响应字段白名单、大小和 Unicode 检查继续生效。新响应还须非空、恰好结束于请求边界且包含一个 locator；不使用负偏移重试或猜测替代边界。

Store 复用既有 normalized text、结构解释和同步读取，没有 schema、迁移、源文件写入、模型、索引或退出生命周期变化。旧包不具备此 API，需使用本次完整配套包，不混用旧 Runtime 与新前端。

## 2. 刷新及失败跳转保持正在阅读的准确版本

原 `openHit` 先调用 `load`，在目标引用读成功前便清除现有正文、范围和返回栈，并切到目标文档的当前版本。随后旧来源丢失或读取失败时，用户失去原阅读位置。手工“刷新详情”也会把明确选择的旧版本改为最新版本。

现在先读取目标详情和准确版本原文，全部成功且会话／选择仍有效后一次切换。失败、错误版本回应、离页或会话轮换不会把不完整目标放进阅读器。正常选择文档也在详情读成功后才替换旧阅读状态。

“刷新详情”只更新详情，保留所选版本、当前原文、引用范围、版本内查找结果和返回栈。若返回详情不再包含所选版本，显示该版本已不可用并保留此前阅读内容，不静默回退到最新版；显式版本选择仍由用户决定。保存的引用历史可以在模型不可用时继续走同一准确来源读取流程。

## 必要验证

证据目录 `.verification/knowledge-reader-context-20261110/`。没有重跑上一项的 24 项例行测试，也未扩充测试框架。

| 层级与证据 | 本次结果 |
| --- | --- |
| `frontend-test.log` | 仅选择 3 项：新增前文导航、已保存引用失败／刷新保持，以及直接受影响的旧会话／错误版本隔离检查；3/3 通过，11 项未选中。 |
| `frontend-build.log` | TypeScript、Vite 和资产清单生成通过；保留既有 >500 kB chunk 提示。 |
| `native-test.log` | 原正向 Preview 合同检查通过；新增检查在非法参数处失败，因为夹具错误期待回复，而既有 Bridge 对非法 payload 直接丢弃。未修改产品规则。 |
| `native2-test.log` | 修正夹具后只重跑新增检查，1/1 通过：未授权文档、非法边界、错版本／偏移／多字段、会话轮换及迟到回应。两次编译均 0 warnings / 0 errors。 |
| `java-test.log` / `java-result.json` | 独立合成 SQLite 中新增旧版本前文／跨结构／代理字符／首尾边界场景及受影响的原正向 Preview 合同，2/2 通过。明确核对连续前文无空隙、未切到新版本、未修改文档 metadata。 |
| 各 `*-cleanup.json` | 清理前自有 Job 均为 0，锁／Job 已释放。Java 合成临时 token 已移除，数据证据保留。 |

Java 使用本轮新合成 Profile `7407ca0684814aca9481732080d94970`，数据位于其 evidence 下；从未接触用户现有资料／配置／凭据。依赖从既有已校验包离线复用，未下载新依赖或调用提供商。

重资源使用原 `Local\Dot_KAG_Workspace_HeavyRun`、自有 kill-on-close Job、450 秒总预算、每进程 768 MiB／整 Job 2 GiB；Java 256 MiB／2 CPU，Node 384 MiB／单测试 worker，.NET 受限堆／并行度。旧拒绝操作没有重试，也不检查旧端口或未知进程。

## 配套交付及验收边界

以上源码及必要检查完成后本地提交，随后生成一个包含三项阅读增量的独立开发包，旧 r2 与 source-find 包保留。实际 EXE／WebView 合并验收结果将在下方据实追加；当前记录的 DOM、Store 和 Bridge 检查不替代真实桌面操作。

本机未在 PATH 找到 `agent-desktop` CLI（只有已读取的技能说明位置已知）；不为此安装工具或修改系统设置。真实交互计划复用项目已经使用过的、只定位本次自有 EXE 的 Windows UI Automation 工具，遵守技能的语义操作和逐次核实原则。

main、六份用户文档、真实数据和旧包保护不变；不推送。真实模型恢复与完整维护仍 **BLOCKED / productionGo=false**。所有合成记录仅说明本地阅读行为，不证明真实模型执行或远端结束。

## 最终配套包与实际桌面验收阻塞

产品提交为 **`a9cbc5d5f577bd07c9bfc6fb02915fc7b6386f11`**，包含本次两项功能及 `1cd8c42` 的版本内查找。[新的独立开发 ZIP](../../artifacts/PersonalAiWorkspace-source-reading-20261110.zip)和[解压 EXE](../../artifacts/PersonalAiWorkspace-source-reading-20261110/PersonalAiWorkspace.Desktop.exe)已生成，未替换安装或默认入口。报告补充提交不改变包的产品字节。

| 项目 | 构建证据 |
| --- | --- |
| ZIP SHA-256 | `B3CCE4E682507B9AFA6F53FFA0528C75E69F30F178D5FF7607C9A0E6CE81C281` |
| Runtime JAR SHA-256 | `8147D6FF25CE681867FB4F9D4CF6C445CC10EA96F36947B84DABFE098DE0DFD6` |
| Desktop DLL SHA-256 | `378E3F004EE2959AEE154FA06EE17E2B4E1E65D8DFCBFC2E252A7507F98F1757` |
| 来源／归档校验 | 194 个 Java 源文件、567 个 class、248 个沿用资源／依赖条目、933 个载荷、934 个 ZIP 条目、420 项实际内嵌来源 pin；全部读取核对通过，构建前后产品源码稳定 |
| 资源收尾 | `build-cleanup.json`：20:26:37Z，自有 Job 0、锁与 Job 释放、failure=null |

复用既有离线整合脚本，仅生成一次本批产品配套包；构建中保留已有 Java 75 项警告及 Vite 大 chunk 提示。没有重新运行上一项 24 项测试或旧 r2 启动／退出场景。

合并桌面验收证据保存在 `.verification/knowledge-reading-desktop-20261110/`。准备工具新建了合成 Profile `b204c7e3cc33425e841eb1aa32d2caa2`，其实际根路径由工具返回为 `C:\Users\CodexSandboxOffline\AppData\Local\PersonalAIWorkspace.Development\Profiles\b204c7e3cc33425e841eb1aa32d2caa2`。这是本轮独立样本，不是用户原工作区，也未据此切换执行身份。

准备程序通过产品 Store 创建两版合成资料、两条明确标记为夹具的本地历史，并删除其中一份合成来源，以供准确旧版及缺失来源流程使用。之后创建本地模型阻断状态的 `new ModelStateStore(...)` 在 `ModelStateStore.verify` 的第 496 行抛出 **`Invalid model state ownership or type`**。它的目录类型／账户所有权检查未通过；尚未核实是哪一个具体条件，不推断为已有浏览器故障。新 guard 的 `armGuard` 尚未执行，没有模型或 HTTP 客户端调用。

**停止的准确动作：在上述新合成 Profile 的 `model-state` 下建立用于隔离验收的本地模型阻断登记。状态：被产品校验拒绝，结果已知；不是自动审批拒绝，也不是未知执行。** 桌面 EXE 启动依赖此准备成功，因此本次 EXE／WebView **未启动、未验收**，无 UIA 动作或截图；不能将前面的存储／Bridge 检查或打包成功改称真实桌面通过。

失败原件 `seed-run-errors.log`、`prepare/owned-profile.json` 和 `startup-cleanup.json` 保留。包装在 20:27:39Z 确认 Job 0、锁和 Job 释放、临时合成 token 移除；合成资料和失败目录保留，没有修复 ACL、改 Java 身份属性、换工具／身份重试、复制其他 Profile 或移除旧 guard。随后读取了可见用户历史，没有获得改变这条边界的新授权，未重试被拒操作。

最小后续阻塞是：先在正常授权的 Workspace 执行环境中明确这一新样本的模型状态类型／所有权不一致原因和可接受的原生准备路径。不能凭更多同类 guard、重打相同包或改变安全检查恢复此验收。当前三个资料阅读功能的源码／必要检查／配套包已交付；实际新包交互仍待这一步，真实模型恢复与完整维护仍 BLOCKED / productionGo=false。本轮没有后台开发或验收进程继续运行。
