# 候选构建工具：二审修订与独立合成结果

日期：2026-11-11。起点 `a298f15651b26fb9ea77709ccfa094ea7fb5fec9`。状态：修订源码和独立合成检查已完成，真实候选构建 **NOT_RUN**。

本轮仅涉及 Workspace 新工具及审阅文档。产品固定 `2ca50415cc6752288110fb31aa0abff6af7d4ed9`，不修改 main、六份未跟踪用户文档、产品安全候选、旧 ZIP、现有 target/bin/obj/dist/缓存；不接触 KAG 或 personal-finance-OS。源码可本地 Git revert，不推送。

## 七项修订

1. **固定 ZIP 证明允许树，再读取旧目录。** `Read-PinnedArchiveManifest` 先检查固定 ZIP SHA256，在 ZIP 中读取唯一规范名称 development-bundle.json，再核对 manifest 全部文件与 ZIP entry 的名称、长度和哈希。拒绝重复、大小写冲突、缺失和额外项目。旧目录使用 `Assert-AllowedInventory`，逐层先验证全部子项名字，未知文件不打开/哈希，未知目录不下钻；普通 Get-Inventory 仅用于新自有目录。Java 清单在复制前就与固定 ZIP 的完整 runtime/java 清单对应，其他复用字节也绑定相同 manifest。真实 ZIP 和旧目录本轮均未读取。
2. **Git 改为私有对象读取。** `New-PrivateGitReader` 只复制原 `.git/objects` 下形状合格的普通 loose/pack 文件至 R/work/object-reader.git，逐文件固定复制字节。原 config/config.worktree、include、attributes、index、hooks、refs 不复制、不交给 Git。commondir、shallow、objects/info 内容、未知目录/文件、reparse point 一律拒绝，不跟随引用。私有 bare 配置固定 fsmonitor=false、gc.auto=0，并隔离系统/全局配置。命令只运行 ls-tree/cat-file；没有 worktree diff、textconv、externaldiff、archive 或 attributes 展开。六个控制文件对比指定工具提交；两个固定产品提交的契约使用原始 tree records 比较；batch blob 逐项验证 ID/类型/长度/边界/SHA1。原仓库当前 HEAD 不再作为执行时准入依据。真实对象复制本轮未运行。
3. **统一 Windows 大小写语义。** publish 禁止目录/扩展名判断不区分大小写；受保护产品文件名的任何大小写及子目录变体不能伪装 input-file，必须规范根名称和 generated-product。实际 publish 输出也必须与白名单规范拼写一致。DATA、Credentials、mainworkspace 等变体不能绕过分类。
4. **结构化路径检查与 restore 前审阅。** JSON 按键/字符串、XML 按属性/文本解析，逐项拆分分号路径列表；检查 C:/、反斜杠、UNC、外部后项、目录回退、根相对/盘符相对路径。XML namespace URL 不误当成本地路径。新增必填 buildCodeReview，restore 前按来源、entry、hash、审阅理由覆盖 SDK/source/NuGet 的 props/targets、任务、分析器和工具等代码输入，未知或多余条目均停止。它是字节审阅清单，不是许可开关；实际清单仍未提供，输出检查不能替代 imports/targets 预审。
5. **设备别名。** 相对路径拒绝 COM¹/²/³、LPT¹/²/³、CONIN$、CONOUT$、CLOCK$ 及已有保留名；只用字符串负例，不打开这些别名目标。Job 保留已审阅的 NUL 标准输入实现。
6. **关闭确认与不可自动再入。** CloseHandle 的尝试和成功分别记录；辅助句柄失败同样报告，失败不伪称关闭成功，也不重复关闭。Guard 在共享锁下先创建精确协调文件 `.verification/candidate-build-active.json`，只有自有活动进程归零、未分配子进程清理及所有句柄关闭确认后才删除。未知、崩溃、构造状态不明、abandoned lock 留下标记，下次拿到锁仍拒绝；独立检查另用 candidate-tools-synthetic-active.json，两入口相互检查。结果写入失败也不会进入解除阻断路径。该单文件协调写入是 R 外的明确例外，不扫描 .verification、不改 ACL。其他共享锁使用者未在本轮修改，不能声称它们已实现此持久规则。
7. **准确产物说明。** 移除 legacyAcceptanceBuild 和无依据的 updatesEnabled。manifest 分开记录 Mmf3AcceptanceBuild=false / Mmf3ProfileAcceptanceBuild=true 输入属性及两个编译宏 MMF3_ACCEPTANCE / MMF3_PROFILE_ACCEPTANCE；restore/publish 显式传入 DEBUG、TRACE 和上述宏。仅声明 installedThisRun=false / publishedThisRun=false，不改产品更新行为。最终外层 ZIP 另写 `evidence/final-archive.json` 的 SHA256/大小。Java classpath 根据已审阅 jarEntries 顺序逐个列明，并核对旧 classpath.idx，不用 wildcard。

## 保留的契约与未来真实调用边界

- `ModelStateStore.java` SHA256 仍为 `2E2A097F34A5AAE5381DBA2D125B6F64CDA48BC9747BD2A3C5106453AE6B28AE`。WindowsPrivateAcl、WindowsNativeGate、TokenUser 安全候选不改动、不部署、不原生运行。
- 旧 ZIP 固定 `artifacts/PersonalAiWorkspace-knowledge-conversation-workflows-20261110.zip`，SHA256 `428D89E397DD5E156E6EDFC9519A4E28C7B698BF8CE61C35B06E1B1684223C56`，来源 b4ae52e；没有重新哈希或修改真实旧包。
- 旧 sourceDirty 原样记录，finance class 的来源仍 `NOT_ESTABLISHED`。资源与源码一致、模块 diff 不变、字节 hash 一致都不能证明历史 class 来自指定源码。BOOT.SF 零字节只能是明确接受的兼容 marker，不能冒充签名；实际接受尚未获得。
- PEReader 只解析产品字节，不 Assembly.Load。Java `-proc:none -implicit:none`；空 publish、空资源组装；保留全部固定资源过滤、Runtime 六键、Bootstrap pins 和前端清单契约。Maven parent POM 固定 hash 与旧报告一致。
- 独立 Job 仍是挂起创建→加入自有 Job→恢复；无 breakaway、提权、身份变更、全局进程/端口枚举。生产限额保持 12 进程、单进程 768 MiB、Job 2 GiB；worker 660 秒从恢复后计时，工具 300 秒、drain 10 秒、清理 10 秒。准备/结束 I/O 没有全入口硬超时。
- 路径/缓存隔离、哈希和 Job 不是 OS 文件/网络沙箱；不改 ACL/网络/身份，也不新增“必须 OS 强制只写 R”的自设阻塞。顺序文件检查不是全程原子保护。
- 实际 NuGet/Vite/MSBuild targets、任务、分析器仍会执行，必须由真实输入审阅固定。UseSharedCompilation=false 避免常驻编译服务，NuGetAudit=false 只关闭联网审计。
- 最终仍是外层 README+manifest+嵌套 payload；NOT_FOR_USE、productionGo=false、nativeValidation=NOT_RUN、fullDesktopAcceptance=false、systemSandbox=false。标签不阻止解压后手动运行。无安装、发布、快捷方式、自启、签名、latest/current 别名或旧包覆盖。

未来 Guard/worker 的调用形式不变，但不能执行历史报告里的 Git diff/archive 或 wildcard javac 命令。现有 Build.ps1 的命令回执是实际命令来源。Git 参数包括私有 --git-dir、--no-replace-objects、core.fsmonitor=false、core.attributesFile=、diff.external=、core.hooksPath=、gc.auto=0；cat-file --batch 由 RunTool 二进制 stdin 文件复制，不使用 shell。restore/publish 新增 `-p:DefineConstants=DEBUG%3BTRACE%3BMMF3_ACCEPTANCE%3BMMF3_PROFILE_ACCEPTANCE`。

## 已执行检查

先静态审阅新 harness：只读工具源码和当前 PowerShell 引用库，生成数据仅在全新专用目录；无 baseline/Git/产品读取，无真实 Guard/Build 调用，无完整伪造 inputs。非产品 PE 由 MetadataBuilder 直接写字节，从不加载执行。Job 子进程仅是当前 PowerShell 的 NoProfile 短命合成 Child.ps1；无产品/helper/准入/ACL fixture。

首轮静态检查：5 个 PowerShell 文件 AST 0 errors；BuildSupport.cs 与 SyntheticFixture.cs 使用当前 PowerShell 的 Roslyn/ref assemblies 做语义检查，0 errors / 0 warnings，未 Emit。

一次合并运行，实际命令（当前 PowerShell 7.6.9；没有换 shell 或执行策略）：

```powershell
& .\scripts\verification\candidate-build\checks\SyntheticChecks.ps1 -Root (Join-Path $PWD '.verification/candidate-tools-synthetic-65d0cad107d54e9c9d1a591de3ecff01')
```

退出 0，56 项功能检查、4 个 Job 场景全部通过。合成阶段内存编译的仅是新工具及新 harness。功能范围为路径/设备字符串、大小写来源、JSON 重复键/局部 schema、结构化路径列表、固定 ZIP manifest 对应、重复归档、未知文件不 hash / 未知目录不下钻，以及 PE 正常、缺失、外链、重复、越界 offset、负/过大/截断长度、非法 UTF8、越界资源目录。未生成完整 inputs.json，也没有接受真实候选输入。

Job 每次上限 3 进程、单进程 256 MiB、合计 512 MiB，正常/后代/赋 Job 场景 worker deadline 8 秒，超时场景 1 秒；清理只针对自有 Job/精确挂起子进程。赋 Job 失败由 harness 暂时把其自有 Job 字段设为确定的无效句柄，产生实际 Win32 错误 6，随后恢复原句柄再清理，不指向他人 Job 或进程。

| 场景 | worker PID | 结果 | 清理前/后活动数 | Job 关闭 |
|---|---:|---|---|---|
| 正常 | 10856 | exit 0 | 0 / 0 | confirmed |
| 超时 | 37172 | 预期 deadline exceeded | 2 / 0 | confirmed |
| 自有后代 | 42540 | exit 0，强制清理后代 | 2 / 0 | confirmed |
| 分配失败 | 7736 | Assign build Job failed: 6；从未恢复 | 0 / 0 | confirmed |

所有场景 auxiliaryHandlesClosed / unassignedCleanupConfirmed / cleanupConfirmed=true。正常场景无需强制清理；超时及后代场景按预期强制自有 Job 退出。分配失败子进程在挂起状态清理。回执见精确目录下 `result.json`、`*-job-result.json`、`released.json`；`lockReleased=true`、`jobCleanupConfirmed=true`、`reentryBlocked=false`。未知输入的读取 trace 中 hashPaths=[]，listedPaths 只有合成目录根，没有进入 unknown-directory。没有系统全局采样。结束后未重复整套检查。

上述通过后源码复核再收紧两点：Java 关系校验前移到复制之前；禁止产品 DLL 的子目录变体。仅补纯函数定点检查和最终语法/语义复核，不重复进程场景。OwnedBuildJob 补显式无参构造转发至相同生产限额，Guard 同时检查独立 harness 的未核清标记；没有改变已验证的 Job 启动/清理路径。

定点实际命令：`& .\scripts\verification\candidate-build\checks\BoundaryChecks.ps1 -Receipt (Join-Path $reviewRoot 'boundary-result.json')`，其中 reviewRoot 为全新 `.verification/candidate-tools-review-bb4c442c2d1e4c8cabdd7a1bab5fc447`。退出 0，6 项纯函数检查通过；最终 6 个 PS 文件 AST 0 errors，两个 C# 源文件语义诊断 0 errors / 0 warnings（未 Emit），schema 14 个必填顶层字段。`git diff --check` 通过。Guard 持久标记的异常路径只做源码复核，未运行真实入口、未模拟 CloseHandle 失败，也未执行真实对象导出/restore；不将这些写成已运行验收。

## 二审交付与仍待处理

完整修订 diff、工具文件 hash、实际命令和证据 hash 随本地提交另存于新审阅目录；最终回复提供确切路径及 SHA256。diff 以 a298f15 为基线，包含新增 harness 和文档，不包含用户文件或合成二进制产物。真实工具 inputs 和实际 buildCodeReview 仍缺失，不制造此类材料来通过入口。

仍待：完整修订源码的独立二审；真实九树/版本/hash；NuGet locks/targets/分析器审阅；最终 publish 白名单；BOOT.SF 和历史 finance class 的明确接受或独立重建方案。以上完成前真实构建继续不运行。本轮没有产生候选包，没有运行产品/浏览器/模型，没有完整桌面验收。

原/替代 Profile、helper、NativeGate、armGuard、ACL fixture、18765 查询、七个已拒目录、旧 text-operation-recovery/Run.ps1 及等价流程保持停止。bindCurrentOwner 不是纯 query；历史拒绝根因仍未证明。此次没有收到新的自动审批拒绝，也没有重试任何历史受拒动作。
