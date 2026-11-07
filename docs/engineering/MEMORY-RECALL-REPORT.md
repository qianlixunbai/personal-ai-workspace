# 显式相关记忆检索：完成与验证

2026-11-07。基线 `32819ceb468c8bc95b02b9142d3c4d744030f530`，分支 `codex/recovery-v3-workspace-core`。本增量完成必要验证；仍为 Development-only。此前的排查过程保留在 [WIP 交接](MEMORY-RECALL-WIP-HANDOFF.md)，其中“尚未通过完整联调”的结论已由本文更新。

## 使用与实现

在原生记忆选择器输入问题或关键词，点击“查找相关记忆”，按词语匹配查看当前 ACTIVE 记忆，再预览、明确添加并确认 1–4 条。原文查找继续可用。查询、预览、添加都不会发送模型任务或修改记忆；只有原有明确发送流程使用选中的 ID 与精确 revision。版本过期、归档或删除沿用原有拒绝规则。

复用现有 `LexicalAnalyzer`、MemoryStore 事务、Native 认证、RuntimeClient 和原生选择器。新增 native-only `POST /api/v1/memory/recall`，查询放请求体；不增加 Bridge 权限、索引、schema、配置或服务。读当前源行并按标题／正文的不同词语匹配计分，重复词不增加权重，更新时间和 ID 决定同分顺序。扫描有 1,000 条上限，展示前 20 条，类型过滤、命中词和总数可见。失败清空旧结果，保留用户已明确选中的条目；关闭窗口取消请求并丢弃迟到结果。

这是词法检索，可能遗漏同义表达，不能代表语义理解。查询需要用户输入，未增加自动提取问题或自动选用记忆。Assistant、Conversation 和 WebView 所调用的同一个原生选择器均复用此路径。

## 先评估成熟项目，再决定复用范围

2026-11-07 通过 GitHub 仓库元数据核对以下项目；Star 数仅作生态线索，`pushed_at` 仅作维护活动信号，不等同安全审计或质量证明。未下载、安装或执行这些项目，也未复制第三方实现。

| 项目 | 当日状态 | 适配、安全和资源成本；实际决定 |
| --- | --- | --- |
| [Mem0](https://github.com/mem0ai/mem0) | Apache-2.0，68,545 Star，未归档；最近 push 2026-11-06 | 参考其按查询召回、范围过滤与结果限制的 [Search Memory](https://docs.mem0.ai/core-concepts/memory-operations/search) 流程。完整 SDK 会引入另一套记忆／向量／模型集成，其写入和版本语义不能直接替换本项目显式审阅、原 ID/revision 和本地准入合同。本轮无需模型或向量服务；直接复用仓库已有分词、存储、认证和选择器，仅补有界排序与接口。没有实现或声称实现 Mem0 的语义召回。 |
| [Windows App SDK](https://github.com/microsoft/WindowsAppSDK) | MIT，4,721 Star，未归档；最近 push 2026-11-07 | 评估其 Windows 应用／存储能力作为宿主适配候选。这里是测试进程继承目录映射却没有包身份，未找到引入整个 SDK 就能可靠解决的证据；额外运行时／打包依赖会扩大改动范围。最终使用已安装 Windows 的标准桌面 Shell，不添加 SDK。 |
| [Microsoft Package Support Framework](https://github.com/microsoft/MSIX-PackageSupportFramework) | MIT，132 Star，未归档；最近 push 2025-03-26，近期维护信号较弱 | 其用途是为打包应用加载兼容性 fixup，涉及启动／重定向行为及额外原生组件；与当前固定 Bootstrap 身份和无需改宿主包的测试目标不匹配。未安装 fixup、修改包配置或加载注入组件。 |

后续较大功能仍应先重新核对许可证、维护、安全边界、适配与资源成本，优先复用；本表不预先批准后续引入任何依赖。

## Windows 宿主阻塞的实际解决

Microsoft 的 [MSIX 文件系统说明](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-behind-the-scenes) 解释了 AppData 新建目录的私有映射与合并读取。原宿主中新 Profile 的实际句柄路径与 Known Folder 逻辑路径不同，现有 `WindowsProfileSecurity` 正确拒绝。只读探针比较默认与 [Known Folder 重定向 flags](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/ne-shlobj_core-known_folder_flag)，包含当前进程及显式用户 token，仍得到同一逻辑目录；`GetCurrentPackageFullName` 返回 `APPMODEL_ERROR_NO_PACKAGE (15700)`。因此没有用猜测的包名、环境变量、硬编码物理路径或放宽目录比较来“适配”。

普通 `ProcessStartInfo.UseShellExecute` 和直接创建 `Shell.Application` 仍继承映射。使用 Microsoft [通过实际桌面 Explorer 启动程序](https://devblogs.microsoft.com/oldnewthing/20131118-00/?p=2643/) 的标准方式，取得 `ShellWindows.FindWindowSW(..., SWC_DESKTOP, ..., SWFO_NEEDDISPATCH)` 返回的桌面 `Document.Application`，再调用其 `ShellExecute`，得到路径一致的普通桌面测试宿主。只读探针先确认差异；测试包装器再验证同一 Windows 用户且未提升权限。没有改 UAC、执行策略、系统安全设置、产品 ACL／目录检查、Bootstrap hash 或 Java Native Gate。

这解决的是**隔离联调宿主的启动方式**，并未宣称产品已支持任意 MSIX／第三方包装宿主的目录映射。此类宿主仍按原规则失败关闭。普通桌面运行通过，不需要扩大真实服务权限。

## 验证证据与边界

全部使用合成数据。证据保留在被 Git 忽略的 `.verification/memory-recall/`、`memory-recall-query/` 和 `memory-recall-host/`。

| 范围 | 结果与实际覆盖 |
| --- | --- |
| 独立 Java | `MemoryRecallTest` 1、`MemoryRecallQueryTest` 2、`MemoryRecallSecurityTest` 1，共 4 passed。覆盖 Unicode／CJK 分词、排名、真实内存 SQLite SELECT、只读性、更新／归档／删除后当前结果、有界扫描、真实认证过滤器与 body 限制。 |
| Windows 完整准入和持久化／HTTP | `MemoryRecallIntegrationProbe` 输出 `MEMORY_RECALL_NATIVE_ADMITTED`，随后 **found=3 passed=3 failed=0 aborted=0 skipped=0**；宿主及 Java 子进程均正常退出。运行真实 `MemoryStoreTest#recallRanksCurrentSourcesAndPreservesExplicitExactAdmission` 和 `RuntimeApiTest` 的 `nativeMemoryCrudContractAndPrivateErrors`、`memoryAuthorizationAndEveryMutationBodyAreBounded`，包括真实 SQLite Store 事务、版本快照及本地随机端口的 Spring HTTP。 |
| Native 自动化 | 4 个聚焦场景通过；客户端边界调整后其中 1 项复跑通过。覆盖响应合同、查询不进 URL、long revision、明确预览／选择／Ask、旧版本拒绝、失败保留选择、关闭取消及迟到结果。HTTP 为合成客户端 fixture。 |
| 构建 | Java、Core、Desktop、Desktop.Tests、TypeScript／Vite／资源 manifest 通过；保留已有前端 chunk 提示及 Mockito agent 提示，没有为压制警告改安全设置。 |

Windows 成功证据：`.verification/memory-recall-host/explorer-integration/{host-result.json,child-result.json,integration.log}`。每次 fixture 都是新 UUID Profile，原失败 fixture 未重用，合成数据和日志保留；只终止测试自己拥有的进程。未访问真实个人 Memory、数据库、凭据或 KAG，未启停已有服务，未调用真实模型。

尚未覆盖 PostgreSQL 实例上的此查询路径、真实模型候选／回答质量，以及真实 WPF／WebView 手工交互验收；自动化分别覆盖 Native 和真实 Runtime 合同，不能声称完整真实桌面端到端验收。个人备份／恢复仍关闭。此确定性检索功能本身不依赖模型调用。

## 重现 Windows 集成验证

使用已经审核的本机 Java 21、JNA 5.17.0 和与代码固定摘要一致的 NativeBootstrap 四文件；不要改 hash 来接受任意二进制。先按 WIP 文档执行必要 Maven 编译，再由测试报告 `java.class.path` 取得同版本测试依赖，加入对应 JUnit Platform launcher 和编译后的 `MemoryRecallIntegrationProbe.java` 类目录，写入忽略目录下的 classpath 文件。启动类会执行原有 Native Gate，再只选择上述三个集成测试。

使用独立 `--artifacts-path` 构建 `bootstrap/tests/MemoryRecallIntegrationProbe.csproj`，传入 `Mmf3ProfileAcceptanceBuild=true`；已验证前端资源时可用 `FrontendSkipBuild=true`。将上述固定 Bootstrap 放入该探针输出的 `NativeBootstrap` 子目录。普通桌面终端中运行该探针，依次传入 Java 可执行文件、固定 JNA jar、classpath 文件、全新证据目录四个绝对路径。不要从继承有不一致映射的宿主启动；自动化入口可用上面的真实桌面 Shell 方式，保持同用户、非提升权限。探针拒绝已有 `owned-profile.json` 的目录，超时停止自身拥有的 Java 进程树，输出仅保留在本地证据目录。

## Git 与回退

仅提交本增量源代码、测试、探针和说明；原有 5 个未跟踪用户文档仍保持原内容与未跟踪状态。基线标签为 `workspace-before-memory-recall-32819ce`。可用 `git revert <本增量提交>` 撤销此确定性检索，不需数据库迁移；不要 reset、clean 或覆盖用户文档。Git 不备份真实数据库、凭据、模型、未跟踪文档及忽略的本地验证证据。

用户已授权完成后推送现有仓库。提交前刷新并核对的目标为 `origin` 的 `https://github.com/qianlixunbai/personal-ai-workspace.git`，同名分支 `codex/recovery-v3-workspace-core`；已有 33 个本地 Workspace 提交在此前远程 tip 之后。正常推送将同步这些既有开发提交及本增量，不改 main，不创建仓库，不强制推送。最终执行结果以 Git 回执和远程 tip 核对为准。
