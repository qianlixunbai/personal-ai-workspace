# PostgreSQL Desktop Integration & Real Windows E2E

## 开发收尾与 GitHub 交付（2026-10-22）

**开发源码进入 Closing；不是 Production GO，也不是完整物理生命周期人工 PASS。** 用户已明确确认：在 Explorer 双击当前 **final** EXE，无手动选择 Profile 即进入 PostgreSQL；Settings 连接正常、V1/V2 Ready、历史 Conversations 可见。当前 final 的完整物理托盘退出 → 重新启动 → 同一 Profile/历史数据仍缺独立明确人工确认，保留 PENDING，不把自动化或旧版本人工操作改写为该项 PASS。

本轮实际起始分支/HEAD 为 `codex/postgresql-desktop-integration` / `38c84484f0dfb9490dbe8a4a4edeef37a91a8661`。Fetch 成功，起始远端无同名开发分支。`e2a64d9` 等已包含 PostgreSQL/Ollama、ModelStateStore ACL、Conversation admission 超时和维护入口，本轮不重做。已将此前选择/托盘/工作区记忆与 Runtime 重启代次代码（含原未追踪生产类）整理为 `6857ca8e0f4da1fa773e68047bab803baaaad6f4`；Closing/STATUS/交接文档另作提交。用户本轮明确授权 Commit 与开发分支 Push，取代下方历史章节“等待人工后才 Commit/Push”的限制；人工缺口本身不被清除。

沿原 final 的源 manifest/构建参数做一次聚焦 Desktop 编译，Desktop/Core DLL 均与 final 包 SHA-256 相同。无必要生产修复、无新长期测试；复用此前实际测试与 Windows 证据，不重跑数据库/Flyway、Ollama推理或全量矩阵，不重打 ZIP。final 包继续原 `38c8448 / sourceDirty=true` 来源，不声称直接构建于本轮新提交。本轮不启动/退出用户产品进程，不做数据、密码、Profile 或备份操作。

GitHub 目标为 [同名开发分支](https://github.com/qianlixunbai/personal-ai-workspace/tree/codex/postgresql-desktop-integration)，只普通 Push；实际成功结果和远端 SHA 在本轮最终交付时另行核验，不提前声明。详细保留范围、B4A6 Retirement Readiness、包一致性与最小人工步骤见 [Closing](POSTGRESQL-DESKTOP-CLOSING.md)；下一阶段只读梳理见 [Finance 交接](FINANCE-INTEGRATION-HANDOFF.md)。以下历史事实、失败记录与证据均保留。

## 记住上次成功工作区（2026-10-21）

**本轮新版真实 Explorer 自动恢复/切换/安全恢复验证 PASS；本轮人工验收 PENDING。** 用户已人工确认原 PostgreSQL Profile 的 V1/V2、Memory、Knowledge、Conversation 数据正常，上一轮托盘切换有效；这些确认不替代本轮记忆功能的人工验收。以下为最新行为，后续历史章节的“每次显示选择器”不再适用于本轮新包。

### 已定位的启动来源与实现

起始运行的是上一轮 `PersonalAiWorkspace-profile-switch-development-20261021` EXE，无启动参数，Desktop DLL 与包清单一致。所持 Runtime 实际使用新建 SQLite Profile `1b37edb646284ed1bbdc8f81c2ecc8aa`：进程于本机 22:43:14 启动，目录于 22:43:16 创建；不是恢复原 SQLite Profile `07d7769638964926a12d1d1cb182d904`。原代码没有上次选择记录，无参数必进选择器，仅“是/创建”分支调用新 Profile Prepare。由当前源码与目录/进程证据可确认走了新建路径，但没有记录用户当时的实际点击，不猜测具体鼠标操作。正常托盘退出后 Desktop/所持 Java/18765 清理成功，不是通过杀进程掩盖残留问题。证据：`.verification/workspace-memory-initial.json`。

- 新 `DevelopmentWorkspaceSelection.cs` 只在现有系统 Known Folder 解析的私有应用目录保存 `last-workspace`，内容为严格 32 字符小写十六进制 Profile ID；无路径、密码、Bearer、数据库字段或第二套 Profile 配置。
- 目录链 pin 与 ACL 策略复用 `WindowsProfileSecurity`；记录文件拒绝目录、reparse、错误所有者、外部敏感授权与无效/超长内容。导航记录允许继承其已 pin/验证的私有父目录权限，验证所有实际授权，不修改磁盘 ACL，也不放宽原 Profile、Native Gate、DPAPI 或凭据文件策略。同账户恶意进程仍不在隔离承诺内。
- `Program` 无参数读取 ID 后仍执行原 `Mmf3AcceptanceLaunch.Parse` / Native Verify / Profile pin；首次没有记录才显示原选择器。记录损坏、指向已删除/移动/不可用 Profile 时提示原因并提供明确重新选择/取消，不调用 Prepare 修复已有目录或自动回退 SQLite。
- `AssistantApp` 在所持 Runtime 完成原生认证后再做只读存储检查，成功才 flush 临时文件并在同一目录原子替换记录。取消选择、退出前未就绪或启动/存储失败均不覆盖上次成功 ID。写记录失败单独提示，不破坏已打开工作区或数据库配置。
- 最终复核补齐已有原生 Settings 显式 Runtime 重启：每个新启动代次可重新记录成功，旧代次的晚到存储检查不覆盖新状态；没有修改 Runtime owner 或配置保存行为。
- 托盘切换复用正常退出、草稿确认与原选择器，以开发版专用 `--choose-development-workspace` 显式重新选择；此参数不传入 Java，也不携带 Profile ID。普通 Release 不开放此入口。新建 Profile 仍按原产品策略默认 SQLite；Java/React 生产代码、数据库格式与迁移逻辑未改。

### 本轮实际验证与证据

| 实际执行 | 结果 |
| --- | --- |
| 首次真实 Explorer 双击无参数新 EXE，原生选择 PostgreSQL，认证/存储就绪后记录 ID | PASS；父进程确为 `explorer.exe`，命令行仅 EXE；记录恰为 32 bytes，无秘密/路径 |
| 真实托盘正常退出，再次 Explorer 无参数双击 | PASS；直接回到 PostgreSQL，不弹选择器，原成功 AI 回复逐字一致 |
| 托盘切至原 SQLite，退出后再次 Explorer 无参数双击 | PASS；记录更新为 SQLite 并自动恢复，PostgreSQL Conversation ID 隔离、Finance disabled |
| 托盘再切回原 PostgreSQL | PASS；原 Memory revision、Knowledge ready revision、原成功回复保持 |
| 记录指向不存在的合成 ID，真实无参数启动并重新选择 | PASS；没有 Runtime 监听、没有自动创建目录、没有数据库回退；显式选择成功后才更新记录 |
| 记录含非法路径内容，取消错误恢复；随后重新选择原 Profile | PASS；不把记录解释为路径，取消正常退出且保留错误记录，显式选择成功后恢复 |
| 仅对本轮新增非秘密记录注入外部读取 ACE，真实无参数启动 | PASS；拒绝且不自动修复 ACL、不启动 Runtime。测试仅恢复自己注入的记录 ACE，未改任何 Profile/凭据 ACL |
| 最后自动恢复 PostgreSQL，实际 React Settings 与 Conversation 页面 | PASS；PostgreSQL、原 `SUCCEEDED` 回复可见，两张截图已实际查看；正常退出后仍记住 PostgreSQL，所持 Java 与 18765 清理 |

上述完整流程在 v2 实际执行，结构化结果为 `.verification/workspace-memory-product.result.json`（PASS）。真实流程日志：`workspace-memory-product-v2.log`、`workspace-memory-product-resume.log`、`workspace-memory-ui.log`；截图：`workspace-memory-settings.png` / `workspace-memory-conversation.png`。没有用传入 Profile ID 的测试 apphost 代替这些 Explorer 流程。

最终交付的 **final** 包仅再补充上述启动代次处理，选择/读取记录/Native Gate/错误恢复代码与 v2 一致，因此复用 v2 的 SQLite 往返与无效记录证据，不机械重跑完整流程。final 实际另外执行：Explorer 无参数自动恢复 PostgreSQL → 原生数据库设置仅执行 STATE / 明确确认重启 Runtime（无 SAVE）→ 旧 Java 退出、新 Runtime 成功后重新记录当前 Profile → 托盘正常退出 → 再次真实 Explorer 自动恢复 PostgreSQL → 原 Memory/Knowledge revision、实际 React Settings 和成功回复核对 → 正常退出。该代次检查仅临时修改本轮非秘密选择记录来区分新旧成功，不改任何数据库配置/凭据。**final 聚焦产品流程 PASS**：`.verification/workspace-memory-final.result.json`；`workspace-memory-final.log` / `workspace-memory-final-resume.log` / `workspace-memory-final-ui.log`，截图为 `workspace-memory-final-settings.png` / `workspace-memory-final-conversation.png`，已实际查看。

聚焦执行原有 `CompiledVariantIdentityAndCrossModeArgumentsAreIsolated` 与 `StartupDiagnosticsUseOnlyExactBoundedProtocol`，PASS；Profile Debug、普通 Release 编译与 Development-only publish/完整性验证实际成功。未新增或修改长期测试，临时验证脚本仅在忽略目录复用原 Explorer/托盘自动化。没有全量 Java/Desktop/Frontend、Browser/IME、压力或完整打包矩阵。Runtime/React 为打包需要实际构建，不冒充测试执行。认证失败的原生诊断与新恢复分支完成源码核对；没有篡改原数据库口令、DPAPI 或 Bearer 做真实认证故障注入，不声称执行了该故障场景。

失败证据保留：第一份 `remember-workspace-development-20261021` 包未通过记录落盘检查，不是交付候选。隔离检查定位 FileStream 句柄设置 DACL 的权限失败，并实际复现本环境 Windows rename 重新应用父目录继承；改用已有私有父目录边界、校验完整有效授权，UI 异步回调显式封装 `Action`。修复后隔离记录创建/替换/读取及真实产品恢复均通过。v2 损坏记录阶段因 Explorer 未取得前台焦点安全停止，未发送输入；仅修正临时 harness 选择当前 Explorer 窗口并续跑未完成部分，没有重跑已绿流程或修改生产行为迎合 harness。日志分别保留于 `workspace-memory-product.log`、`workspace-memory-product-v2.log` 与 resume 日志。

final 聚焦 harness 亦保留了原生 Settings 为 UIA 嵌套窗口、Explorer 重复窗口抢焦点、刷新按钮尚未启用而未执行查询的失败/暂停。仅修正对应窗口定位、复用已有 Explorer 窗口以及等待按钮启用，随后继续未完成的检查；没有修改生产 UI/安全逻辑、降低断言或重新跑完整业务流。

### 数据保留、交付与人工步骤

继续原 PostgreSQL Profile `55be9b5e4c414064bdfa817f5e4d8b8a` / 数据库 `paw_pgdesktop_5e3ccc3d5b17`，未建库、迁移、配置 SAVE、覆盖 `database.dpapi` 或提交新推理。原 SQLite Profile `07d7769638964926a12d1d1cb182d904` 和用户本次新建的 `1b37edb646284ed1bbdc8f81c2ecc8aa`，前后只读业务表/Knowledge 逻辑摘要相同且完整性 ok；SQLite 启动的派生 FTS 重建可能改变文件字节，不声称字节不变。原八个未追踪 B4A6 文件、原 PostgreSQL `database.dpapi` 与上一轮 ZIP 的 SHA-256 均一致；未操作 Finance、旧 Profile 迁移或历史备份。

交付 **`artifacts/PersonalAiWorkspace-remember-workspace-development-20261021-final.zip`**（375656904 bytes），同名目录已可运行。SHA-256：`13a1df658814018a1314cf80db54056478284900d191941c8a618232cce6ae7f`。实际 ZIP 的 EXE/DLL/README/manifest 与已运行目录一致，不包含 last-workspace、database.dpapi 或 client-token。DEVELOPMENT_ONLY、Production GO=false；manifest 为基线 `38c84484f0dfb9490dbe8a4a4edeef37a91a8661` / sourceDirty=true。各包均未覆盖旧包；首个失败候选及已验证 v2 保留，只交付 final。

人工只需：打开 **final** 目录双击 EXE，确认自动进入 PostgreSQL 及原成功回复；物理托盘“退出”后再次双击，应仍直接进入 PostgreSQL。托盘切换仍可主动选择已有 SQLite/PostgreSQL。当前记录已通过成功产品启动保存为 PostgreSQL；不同 Windows 启动上下文仍分别使用其系统解析的私有目录，不自动扫描/迁移其他上下文。

分支 `codex/postgresql-desktop-integration`，HEAD 仍为 `38c84484f0dfb9490dbe8a4a4edeef37a91a8661`；本轮与上一轮修复均未提交，新选择记录类仍是本轮待提交文件，原 B4A6 未追踪文件不变，无 staged diff。**等待本轮人工验收后，再整理最终 Commit 与 GitHub 开发分支 Push；未 Commit/Push/Merge/Tag/Release。**

## Explorer 无参数选择与托盘切换修复（2026-10-21）

**新版实际产品自动化流程 PASS；用户最终人工验收 PENDING。** 以下为本轮结果，旧包及旧验收记录保留，不宣称正式发布或最终验收完成。

### 排查结论与最小修改

- 原 `Program.Main` 在无参数时先调用 `ChooseDevelopmentProfile`，选择并校验 Profile 后才检查单实例；没有“最近 Profile”恢复配置，也没有在该入口自动选择 SQLite。每个 Profile 使用自己的 instance suffix；重复选择相同 Profile 才会激活已有实例。
- 起始确有一个旧包无参数 Desktop 进程及其 Java 子进程。通过真实托盘菜单自动化正常退出后，旧包以无参数 Shell 启动显示了选择器；经原生文件夹窗口选择原 PostgreSQL Profile，实际读到 PostgreSQL 和原成功回复，再次正常退出、清理 Runtime。旧包 Desktop DLL 哈希与原清单一致，包含当前选择器代码。
- **未复现或确定用户此前那次未观察到选择器的原因**；不能将其归因于自动恢复、旧包缺代码或残留进程。已确认的操作问题是文件夹选择器会停在无关历史目录，进入 Workspace 后也没有 Profile 切换入口。
- `Program.cs` 复用原选择器，仅增加 Profiles 父目录作为初始目录，原路径、身份、Native Gate、ACL 与 DPAPI 校验不变。
- `AssistantApp.cs` 在 Development-only 托盘显示当前 Profile，并增加“切换开发工作区…”；复用正常退出及未保存 Memory 确认，完成 Runtime/窗口清理、释放 mutex/目录句柄后，由 `Program` 无参数重开同一 EXE。拒绝丢弃时不请求切换；重开失败会明确提示用户再次双击。未新增最近 Profile 状态或重写存储配置。
- 普通 Release 不显示切换入口；Java、React、数据库格式与配置、安全策略、Finance 和旧 B4A6 未修改。更新了包内操作说明，没有新增长期测试。

### 本轮实际验证

| 实际执行 | 结果 |
| --- | --- |
| 原有 `SingleInstanceSignalsPrimaryAndReleasesMutexOnShutdown` 聚焦测试 | PASS；使用已有 `RuntimeLifecycleProbe=true` Profile 构建模式 |
| 普通 Release Desktop 编译；新版 Development-only 构建、publish、包完整性 | PASS；Release 只编译，未做正式发布 |
| **真实 Explorer 窗口双击新 EXE，无任何启动参数** | PASS；记录父进程为 `explorer.exe`、命令行仅含 EXE；显示选择器，原生文件夹窗口默认列出两个原 Profile |
| 原生选择 PostgreSQL → 托盘切换到原 SQLite → 托盘切回 PostgreSQL | PASS；每次 Runtime 在选择前未启动，切换先正常退出旧 Runtime，新进程仍无参数 |
| 正常退出后再从真实 Explorer 双击、再次选择同一 PostgreSQL Profile | PASS；原成功 AI 回复逐字一致，无新推理/重发 |
| 实际 React Settings 与 Conversation 页面 | PASS；显示 PostgreSQL，原 `SUCCEEDED` AI 回复可见；已查看两张窗口截图 |
| SQLite backend、PostgreSQL Conversation ID 隔离、Finance disabled | PASS；SQLite 没有新增 `database.dpapi`；只读 `integrity_check=ok`、schema=3 |
| 最后真实托盘菜单自动化退出 | PASS；Desktop 与所持 Java 已退出，18765 无监听；不冒充用户物理托盘操作 |

证据：`.verification/profile-selection-product.result.json`（最终 PASS）、`profile-selection-product-ui.log`、`profile-selection-settings.png`、`profile-selection-conversation.png`、`profile-selection-focused-test-probe-mode.log`、`profile-selection-release-build.log`、`profile-selection-bundle-build.log`。

保留失败证据：初次 Profile 测试未加 `RuntimeLifecycleProbe=true`，既有 Conversation probe 条件编译引用缺失导致编译失败；使用正确现有模式后聚焦测试通过，没有改测试代码。临时 UI harness 先后遇到 COM 启动父级仍为 PowerShell、Explorer 列表虚拟化、文件夹列表与按钮重用 AutomationId、未保留进程句柄等问题；均仅在忽略目录的验证脚本修正，没有据此修改正确的生产行为。完整流程成功后的外层壳因未设置的 `$LASTEXITCODE` 报错，不能称该外壳退出成功；最终独立 UI/正常退出检查实际成功，并写出上述最终 PASS。没有全量 Java/Desktop/Frontend、IME、Browser、压力或完整打包矩阵。

### 保留与交付

继续使用原库 `paw_pgdesktop_5e3ccc3d5b17`、PostgreSQL Profile `55be9b5e4c414064bdfa817f5e4d8b8a` 和 SQLite Profile `07d7769638964926a12d1d1cb182d904`。未执行建库、迁移、配置 SAVE 或凭据覆盖。PostgreSQL `database.dpapi`、两个 Profile 的 Knowledge 文件、原八个未追踪 B4A6 文件的前后 SHA-256 均一致；旧 ZIP 哈希也一致。SQLite `memory.db` 字节哈希发生变化：原 `MemoryStore` 每次打开即重建派生 FTS/trigger，不能宣称数据库字节未改变；本次只读完整性为 ok，四张权威业务表均为空，没有通过测试提交写入或删除业务内容。未操作 Finance 或历史备份。

新包：`artifacts/PersonalAiWorkspace-profile-switch-development-20261021.zip`；同名目录可直接运行。SHA-256：`63c29d69de63b54d20672296efbd7f4f8b18ce847fb49e7e147a1afe34ff7623`。DEVELOPMENT_ONLY、Production GO=false；manifest 如实记录基线 `38c84484f0dfb9490dbe8a4a4edeef37a91a8661`、sourceDirty=true，修复未提交，不伪装为干净正式包。

分支仍为 `codex/postgresql-desktop-integration`，HEAD 未变。工作区有本轮两处生产文件、包说明及本报告的修改，另有原八个未追踪 B4A6 文件；无 staged diff。未 Commit、Push、Merge、Tag 或 Release。

用户操作：完整解压新包，双击 `PersonalAiWorkspace.Desktop.exe` → **否** → 选择 `55be9b5e4c414064bdfa817f5e4d8b8a` 文件夹 → Settings 核对 PostgreSQL → Conversations 打开 `Synthetic PostgreSQL inference`，查看成功回复。如果已在 SQLite，直接从托盘“切换开发工作区…”返回同一选择器。最后物理托盘退出，再次双击并选择同一 Profile；此人工关卡仍待用户确认。

## Ollama 兼容与 PostgreSQL 消息级验收收尾（2026-10-21）

**开发版自动验收 PASS；Explorer 无参数双击及物理托盘人工验收 PENDING。** 本节取代下方历史快照的当前状态。已续用原库 `paw_pgdesktop_5e3ccc3d5b17`、原 PostgreSQL Profile `55be9b5e4c414064bdfa817f5e4d8b8a` 和 SQLite Profile `07d7769638964926a12d1d1cb182d904`，没有再次准备数据库、索取管理员密码、迁移或删除旧数据。

### Ollama 0.40.6 / llamacpp 的限定结论

- 本机 `/api/version` 实际为 `0.40.6`。4b child 为 `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`，9b child 为 `c97eb11d70b1acdc88af01eef566c1fe4f7fbe93eb1afc06871132f293ff425a`；二者都是 `qwen35 + clip`、`llamacpp`、completion/vision/tools/thinking、requires `0.17.1`，不是可按单 language blob 合同准入的普通文本模型。
- 官方比较基线为 [v0.40.1 `cf2a313`](https://github.com/ollama/ollama/tree/cf2a313a298066d572c36812e5ad30a21c0db13b) → [v0.40.6 `2476d5e`](https://github.com/ollama/ollama/tree/2476d5ead197abe04629651028f83d48dc75d2b2)。实际读取了两版 `api/types.go`、`server/routes.go`、`server/images.go`、`server/model_list.go`、`manifest/manifest.go` 及官方比较记录；本地副本/差异保留在 `.verification/ollama-0406-review/`。发行内容另见 [0.40.6 release](https://github.com/ollama/ollama/releases/tag/v0.40.6)。没有把第三方 issue 或 tag 名称当成二进制身份。
- **保持的语义：** Show 的 model/projector/capabilities/manifests/details/requires 合同保持；manifest child digest 仍是 runnable identity，parent list digest 不可替代；明确 `runner` 选择仍绑定 child；`:local` 在远程转发之前拒绝 cloud-backed 模型；完整 Chat `done` / `done_reason` / response model 仍可验证。依据 [types](https://github.com/ollama/ollama/blob/v0.40.6/api/types.go)、[routes](https://github.com/ollama/ollama/blob/v0.40.6/server/routes.go)、[manifest](https://github.com/ollama/ollama/blob/v0.40.6/manifest/manifest.go)，并由本次真实任务验证本机组合。
- **实际变化：** [model_list](https://github.com/ollama/ollama/blob/v0.40.6/server/model_list.go) 从逐 child 返回改为只返回首选 child，并隐藏 downgrade guard tag；原 bootstrap 固定寻找 ggml，实际 tags 仅含 llamacpp，因此只追加版本号仍不能工作。manifest 读取/Windows 路径与 digest 校验、已加载模型缓存和请求消息复制亦有变化；Windows regular-file manifest 不走仅适用于 symlink 的快捷缓存验证。chat 使用已解析模型调度，仍保留显式 local/runner 边界。llama.cpp 依赖和 runner 生命周期也发生变化，不能据此推导图片预处理、token 或 GPU payload 与旧版等价。
- **本轮只准入固定 4b/llamacpp 的文本执行。** 复用原完整组件闭包：parent list、child、config、language、projector、license、params 的 SHA-256/长度，以及真实监听进程、`serve` 参数、本地 Modelfile 和前后 tags 一致性。共享服务器增加精确 SHA-256 `71d2002d3ae572d5fa8c1d368ce9d1b9a39c7187cf169e8b330c55410bf6c58e`；本机 Authenticode 实际为 Valid / Ollama Inc.，记录在 `server-signature.json`。这是经检查的本机签名二进制 pin，不声称重新构建或校验了整个官方发布 ZIP。
- 0.40.6 的其他 ggml/llamacpp/model 组合继续 fail-closed；实际 catalog 仅含上述 4b，9b 仍拒绝。未跳过 digest/组件检查，没有改写 Ollama 返回值、下载/删除模型、停止共享 Ollama或写入新的 Active selection。共享监听 PID 前后均为 `3792`；收尾 metadata 见 `actual-after.json`。
- Vision 图片执行仍关闭：`REVIEWED_TOKEN_POLICIES` 仍为空，独立 payload/preprocessing/template/live proof 不变；未扩大隔离 0.40.1 的二进制或 GPU 合同。文本可用与图片可用是两个结论。新硬件/更大模型/云 Provider 没有新增架构或推测性兼容，未来组合仍须独立审查；本次文本扩展没有增加 GPU 型号或设备 ID 限制。

### 生产修复与真实 Conversation

除上一轮已验证的 `ModelStateStore` Windows ACL 条件修复，本轮仅修改 Ollama 的限定版本/首选 child/签名二进制 pin，以及 Desktop `RuntimeClient.Conversation` 的 AI admission 超时：从通用 8 秒改为与模型管理一致的 155 秒。普通 Conversation 存储请求仍沿用原预算，Java 推理/任务超时、安全策略和公开数据格式未改。

首次真实续跑已经越过 PolicyDenied 并加载模型，但 8 秒客户端超时中断冷启动；日志保留为 `ollama-0406-conversation-attempt1.log`，不算成功。共享 Ollama 记录该次连接关闭、load aborted，未遗留 llama-server。随后显式使用既有 RECOVER → VALIDATE 处理本次合成 Profile 的 guard；没有直接删除/改写 guard、自动卸载共享模型或修改 selection revision。成功验收与收尾均没有 unresolved guard。

`RuntimeLifecycleProbe.PostgresqlConversation` 直接续用现有配置/Memory/Knowledge 数据，通过 Native Bearer → HTTP → 正常 Conversation/TaskManager/Provider → PostgreSQL，未直接插表：

| 本次实际执行 | 结果与边界 |
| --- | --- |
| 新 Conversation、USER、真实 AI 回复、Task/Turn 一致 `SUCCEEDED`、显式 Memory ID/revision 关联 | PASS；严格要求成功及非空 assistant，并校验真实 task result 相同 |
| 归档会话提交 | PASS；受控冲突，无新增 USER/Turn |
| 输出预算边界的真实模型任务 | PASS；`FAILED`、受控 failure code、无伪造 assistant |
| 正常任务取消 | PASS；Task/Turn `CANCELLED`，无 assistant/failure code |
| Runtime 显式重启、三种终态及消息/Memory 关联重读、旧 Bearer HTTP 401 | PASS |
| 同 Profile 正常退出/重开 | PASS；成功回复、失败、取消、原 Memory、Knowledge revision/preview 和原元数据 Conversation 隔离保持 |
| Bridge 旧 session / reload / late-response 拒绝 | 现有聚焦合同测试本次 PASS；这是合同执行证据，不冒充物理桌面操作 |

证据：`.verification/ollama-0406-conversation.log`、`-conversation-reopen.log`、`-conversation.result`（PASS）、`-stale-session-test.log`，以及原 Profile 的私有 `evidence/postgresql-conversation.json` / 两张 PNG。已实际查看综合截图：React Settings 显示 PostgreSQL、V1/V2 READY、Knowledge READY、有效原生凭据及 Finance disabled。

### 新 Development-only ZIP 与实际产品 EXE

新包：`artifacts/PersonalAiWorkspace-postgresql-ollama0406-development-20261021.zip`，375652462 bytes；SHA-256 **`46a85ba0e4ce3d73bd74ef4781d4c4560a6345e5c686544f8c75977279f429c7`**。同名目录是解包后的可运行内容。只生成这一次新独立 ZIP，未覆盖旧包；DEVELOPMENT_ONLY、Production GO=false、无 installer/update。

打包使用既有 `build-trusted-development-runtime.ps1`。代码/包基线为本地提交 `e2a64d9026c90427a88f57117326b763d30e9726`；manifest 如实记录 sourceDirty=true（当时有待提交报告及原八个 B4A6 文件），没有将本机包伪装成干净的正式发行。

- Maven Runtime、React production build、Development-only self-contained WPF publish 实际成功；没有全量测试或打包矩阵。
- 原 `test-development-bundle.ps1` 实际完成 ZIP 解压/带空格中文目录搬迁、完整性、路径穿越/假冒 Production GO 拒绝、Runtime 篡改及 sidecar 重 hash 后仍被内嵌 pin 拒绝。结构化结果 PASS。外层调用壳继承最后一次**预期拒绝**子探针的 exit 10；这是调用壳状态，不是包损坏；保留原日志，没有修改工具/断言或重复跑整套合同。
- **实际新包 `PersonalAiWorkspace.Desktop.exe`**（不是 test apphost）依次启动原 PostgreSQL Profile、退出、相同 Profile 再开，再启动原 SQLite Profile：WPF 窗口、WebView2/React/Bridge READY、PostgreSQL 三 Store 读取、已成功回复及 revision 重读、旧 Bearer 401 全部 PASS。SQLite 无 database.dpapi，三个 PostgreSQL Store IDs 均 404，Finance disabled。
- 三次实际 EXE 都通过现有托盘菜单的自动化调用正常退出，18765 监听/所持 Java 清理 PASS。自动化使用 WinForms 的真实菜单和 UI Automation，不是物理鼠标托盘验收；也没有把带显式 Profile 参数的进程启动当作 Explorer 无参数双击。
- 实际 EXE 证据：`.verification/ollama-0406-product-exe.log` / `.result`（PASS）；包构建与合同日志：`-development-bundle-build.log` / `-development-bundle-contract.log`。

### 凭据、验证复用与交付边界

本次使用原隐私扫描器逻辑的临时限定范围副本，检查 tracked/untracked 源码、当前 target/Desktop 构建、新包目录/ZIP/内嵌归档和本次日志；合成数据库真实密码及 Bearer 只经内存/stdin进入扫描，不回显、不落新文件。**实际凭据匹配为 0**，新包不含 Profile、client-token、database.dpapi 或测试私钥。

原始 pattern audit 为 FAIL，唯一命中 `src/test/resources/web/fixture-key.pem`，SHA-256 `4adb3376263ad17f485e3d35dc339c8580296f01061ca5a2e09de51fd4a57200`。这是原提交 `c90b963` 的公开自签 TLS 测试材料，旧 [W1A-1 报告](W1A-1-IMPLEMENTATION-CANDIDATE.md) 已明确说明仅供 `fetch-fixture.example.com` 本地 test seam；实际引用在 `WebTransportTest`，不在产品包。未删除 fixture、修改扫描器或隐藏原 FAIL；分类复核另记 `.verification/ollama-0406-privacy-reviewed.json`，不把 pattern FAIL 谎报成原脚本全绿。

本次 Java 仅运行 `OllamaProviderTest,VisionFlowTest`；Desktop 仅运行 `ConversationExecutionClientTests` 及既有 stale-session 单项；均 PASS。复用下节上一轮实际 PostgreSQL 准备、ACL、原生 CHECK/SAVE/DPAPI、防覆盖、Memory/Knowledge 写入/revision/检索/来源定位证据，未重跑已绿阶段。两个维护 PowerShell 文件仅本次 parser PASS，没有再次执行建库。没有 Java/Desktop/Frontend 全量、Browser/IME、压力、Finance 或完整 packaging matrix。

两项一次性任务已注销，18765 无监听；共享 Ollama、数据库/角色、Profile、合成验收数据、旧 ZIP、旧八文件均保留。tracked 代码已提交，报告另作 docs-only 本地提交；原八个 B4A6 文件继续未追踪。fresh origin fetch 成功，目前同名远端开发分支尚不存在，**未 Push**，未 Force Push/Merge/Tag/Release。

**B4A6：自动验收已验证新运行路径不依赖旧 runner，技术退役准备具备；人工 Explorer/物理托盘关卡完成前不宣布正式退役，也不自动删除旧文件或 DROP 数据库。** 普通 Push 同样留待这些必要人工关卡通过后，沿用本轮既有授权，无需重新请求普通工程步骤许可。

用户仅需一次集中人工流程：在 Explorer 打开新包目录，双击 `PersonalAiWorkspace.Desktop.exe`，选“否”并选择现有 PostgreSQL Profile `55be9b5e4c414064bdfa817f5e4d8b8a`；确认 Settings 为 PostgreSQL、Conversation 有成功回复，然后物理托盘“退出”。再次同样双击选择相同 Profile，确认数据仍在并物理托盘退出。无需密码、建库或重做导入；默认 SQLite 的实际新 EXE 隔离已自动验证。

---

## 高自主授权续行：真实 PostgreSQL 存储通过，模型准入阻断完整验收（2026-10-20）

**当前为 PARTIAL。** 本轮已实际完成独立数据库、Workspace V1/V2、原生 PostgreSQL 配置与 Memory/Knowledge 持久化；Conversation 元数据通过，消息/终态仍未通过。下方暂停、认证失败及旧 PENDING 均保留为历史证据，不覆盖本节最新事实。

### 实际数据库与凭据

- 分支 `codex/postgresql-desktop-integration`；起始及当前 HEAD `ecb0655934776c65a68658b05cb805e6b1a54e5e`。代理 fetch 成功；没有 Commit、Push、Merge、Tag 或 Release。
- 独立合成库 **`paw_pgdesktop_5e3ccc3d5b17`**；owner/migrator **`paw_pgd_owner_5e3ccc3d5b17`**；受限 app **`paw_pgd_app_5e3ccc3d5b17`**。均为本轮全新对象，没有复用旧 RESERVED 或 B4A6 对象。
- 用户在本机官方 psql 原生提示中输入一次管理员密码。实际只读硬校验确认 `postgres / postgres`、`127.0.0.1:5432`、17.11、`D:/PostgreSQL/data`、管理员超级用户及新名称未占用，随后才执行 DDL。
- 两个专用角色均无超级用户、建库、建角色、复制和 BYPASSRLS 权限。新库从 template0 创建，先关闭连接，撤销 PUBLIC 数据库权限、写入唯一标记并限定 CONNECT 后才开库；app 无角色成员资格或 schema CREATE 权限。owner/migrator 与 app 使用不同随机凭据。
- 复用 Runtime JAR 内的 pgjdbc `PasswordUtil`、Flyway 及原 Workspace V1/V2；未运行 Finance migrations，未新增 schema、数据库格式或产品配置体系。app 实际连接与 `PostgresqlWorkspace.verify` 通过。
- 管理员口令没有进入 PowerShell/Java、DPAPI、参数、环境变量或日志。合成角色明文只经私有进程管道交付；SQL 管道使用 SCRAM verifier。官方 Windows [simple_prompt 源码](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/common/sprompt.c) 的 CONIN$/CONOUT$ 路径用于确认密码提示与重定向 SQL stdin 分离；实际登录成功是本轮执行证据。
- 维护代码与依赖在既有当前用户私有目录中准备、编译和 pin 后再输入密码；没有执行历史不受信任的临时 helper。`ready.json` 位于 `.runtime/workspace/pgdesktop-5e3ccc3d5b17/`，只记录非秘密身份及数据库准备结果，不代表完整桌面验收。

### 生产修复与验收代码

本轮仅修改一处生产条件：`ModelStateStore.verify` 已先执行统一 `WindowsPrivateAcl.verify`，却仍通过旧 NIO ACL 分支拒绝 SYSTEM/Administrators，导致 WPF 合法新 Profile 返回 `MODEL_STATE_UNAVAILABLE`。现在 Windows 复用既有 Native 权限校验；保留 owner/type、路径、writer lock、selection、guard、POSIX 和其他平台检查。没有改 ACL、放宽 Native Gate、迁移或修复已有 Profile。

既有 `WindowsPrivateAclTest` 综合流程增加 WPF 权限形态与 descriptor 不被重写的断言，沿用原 writer 排他和 guard 重启检查；没有新增独立测试用例。既有 RuntimeLifecycleProbe 增加 PostgreSQL 综合流程及明确限定的 storage-only 流程。storage-only **不声称消息或终态通过**，完整流程的断言仍保留。

新增 `bootstrap/tests/PostgresqlDesktopMigration.java` 与 `scripts/verification/prepare-postgresql-desktop.ps1` 为显式合成环境维护入口，不进入日常 Runtime。入口分阶段准备、原生登录、只读身份检查、创建、Flyway 和 app 预检；`attempt.json` 阻止盲目重跑，失败保留部分对象而不自动 DROP。执行副本只写私有目录；源脚本后续补充显式 Runtime manifest 参数和会话级 SQL 日志抑制，未再次运行 DDL。

### 本轮实际执行

| 执行 | 结果与证据边界 |
| --- | --- |
| 准备脚本 parser、迁移 helper 编译、无 SQL 本地预检、SCRAM 管道及无效输入拒绝 | PASS；不计作数据库或产品验收 |
| psql 创建独立目标、Flyway V1/V2、app 权限/空库/结构预检 | PASS；私有 `created.json` / `ready.json` |
| Java `WindowsPrivateAclTest` 聚焦综合流程 | PASS；`.verification/postgresql-desktop-full-acl.log`，无 failure/error/skip |
| Maven `-pl workspace-application -am -DskipTests package` | BUILD SUCCESS；`.verification/postgresql-desktop-full-package.log`，这是构建，不是测试 |
| Profile Debug 原 WPF 验收 apphost 构建 | PASS，零警告/错误；`.verification/postgresql-desktop-full-probe-build.log` |
| 真实原生 CHECK、WPF PasswordBox、安全保存确认、DPAPI 重读、防覆盖、显式重启、旧 Bearer HTTP 401 | PASS；`.verification/postgresql-desktop-storage-integrated.log` |
| 真实 WPF / WebView2 / React / Bridge / PostgreSQL 状态 / Finance disabled | PASS；同上。已查看实际 CapturePreview 图像，Settings 显示 PostgreSQL、受信配置及 V1/V2 READY |
| Memory 创建、读取、更新/revision、陈旧 revision 删除拒绝、重启持久化 | PASS；经真实 Native Bearer RuntimeClient / HTTP / Store，不是独立 SQL |
| Conversation 创建、重命名、归档、恢复、重启后元数据读取 | PASS；消息创建、模型执行与终态不在此结论中 |
| Knowledge TXT/Markdown 导入、双 revision、归档/恢复、临时文档删除、索引重建、检索及 source revision/offset/line 对应 preview | PASS；文件与 PostgreSQL Store 真实路径。原 SQLite 文件保留，操作与重启前后 SHA-256 不变；未验证模型生成的 RAG 答案 |
| 同一 Profile 重开后的 Memory / Knowledge / Conversation 元数据与来源定位 | PASS；`.verification/postgresql-desktop-storage-reopen.log` |
| 新独立默认 SQLite Profile / React / Native Bearer / 正常退出与所持 Java 清理 | PASS；`.verification/postgresql-desktop-full-sqlite.log` |

成功 PostgreSQL Profile：`55be9b5e4c414064bdfa817f5e4d8b8a`；独立 SQLite Profile：`07d7769638964926a12d1d1cb182d904`。前者位于 Windows LocalAppData 的 `PersonalAIWorkspace.Development/Profiles/`。截图与非秘密 Store IDs 在各 Profile 的 evidence 中保留。

上述 GUI 使用原测试 apphost 承载真实 AssistantApp / DevelopmentRuntimeOwner / bundled React 与 Java/Spring/Store，**不是新分发包产品 EXE、Explorer 无参数双击或物理托盘点击验收**。正常 ExitAsync 清理通过不冒充托盘 UI 通过。

### 保留失败与当前安全阻碍

1. 工具宿主直接准备 Profile 在 `PRIVATE_PROFILE / 0x80131620` 失败。沿用历史已验证的当前用户 Interactive/Limited 一次性计划任务后，新 Profile 创建成功。未移除注入设置、提权、改 ACL 或降低原门禁。
2. 首次综合探针在保存后过早观察异步重启状态。修复 harness 为等待新 PID、READY 和原生按钮恢复，再验证旧 Bearer；不是修改生产预期。首次日志保留为 `postgresql-desktop-full-integrated-attempt1.log`。
3. 完整 Conversation 流程先实际返回 `ModelStateUnavailable`，修复上面的单处生产条件后，实际返回 **`PolicyDenied`**。分别保留 `-attempt2.log` 与 `-attempt3.log`；没有把失败改成 PASS。
4. 本机只读 `/api/version` 为 **Ollama 0.40.6**，`/api/tags` 中安装的 qwen3.5:4b/9b 默认 runner 为 **llamacpp**。当前普通文本准入仅支持 `OllamaModelAdmission.SUPPORTED_VERSIONS` 的 0.40.0/0.40.1 与经过审查的 ggml 路径；Vision 的固定 llamacpp 特例也有独立来源/组件身份合同。版本和 runner 存在明确兼容缺口，不能只追加白名单或修改版本响应来获得验收 PASS。

因此消息/终态、三个 Store 的完整消息级 PostgreSQL 桌面 E2E、新产品 EXE/Explorer/托盘与新 Development-only ZIP **仍 PENDING**。本轮没有更改共享 Ollama、下载/替换模型、修改 Provider 白名单或借 mock/SQL 伪造终态。下一步应独立审查当前 Ollama/runner 的安全兼容性，或准备现有合同支持的独立模型环境，再续跑完整流程。按照“全部通过后 Push”的授权条件，本轮不 Push，也不将 PARTIAL 宣称为交付完成。

### 清理、保护与复现

- 已删除并实际复核本轮 `maintenance.dpapi` / `application.dpapi` 不存在；管理员临时文件也不存在。Profile 内产品 `data/database.dpapi` 与合成数据保留。没有复用或恢复旧管理员密文。
- 一次性任务已注销，18765 无监听；没有按不明 PID 清理进程。见 `.verification/postgresql-desktop-full-cleanup.json`。
- Finance 始终 disabled，冻结 Finance 仓库、历史数据库/角色、原用户 Profile、真实 SQLite、receipt、备份与旧 ZIP 未操作。八个 B4A6 未追踪文件相对原哈希清单全部一致，继续未追踪。
- B4A6 退役准备：当前构建、原生配置与新准备链路未发现对旧 runner 的依赖；八文件、旧库/角色及历史证据仍保留。完整产品 E2E 尚未达成，不宣布正式退役，不自动 DROP。
- 没有执行全量 Java/Desktop/Frontend、Finance、Browser/IME、压力或完整打包矩阵；没有重复运行已绿的 ACL 流程。没有生成新 ZIP；未 Commit/Push，工作树保留原修改及本轮候选。
- 新目标复现入口：先用当前构建产生并审查的绝对路径 manifest 执行 `prepare-postgresql-desktop.ps1 -PrepareOnly -RuntimeManifest <manifest>`，再在普通 PowerShell 7.4+ 中运行返回私有目录里的 `prepare.ps1 -CheckOnly` / `prepare.ps1`。成功过的 session 禁止重跑；此操作会创建另一个新合成库，不用于覆盖本轮目标。
- Probe 复现：按本轮 build 日志构建原 Desktop test apphost，参数为 `<new-profile-id> gui-postgresql <session-suffix>`，或明确限于非推理存储的 `gui-postgresql-storage` / `gui-postgresql-storage-reopen`。fixture 已在收尾清理，现有已配置 Profile 可重开；不得绕过原生窗口向历史 Profile 注入配置。

---

## 用户要求暂停后的复盘报告（2026-10-20）

**状态：按用户要求暂停。PostgreSQL 桌面集成目标未完成。** 收到停止指令后，只读取 Git/报告及暂存文件是否存在，并整理本节；不再启动窗口、登录数据库、构建、测试或修改生产代码。最后一次“重新打开安全输入窗口”的请求尚未实际执行，新管理员暂存文件也未创建。

### 我的执行问题

1. **安全输入窗口的启动准备不足。** 仓库已有通过 `$PSHOME` 显式加载 Windows PowerShell 模块的做法，我最初没有复用，导致可见窗口在 `Get-Acl` 处失败。首个窗口也没有保留错误，用户需要再次反馈并提供截图。这是我的启动和诊断准备问题。后来只增加两条模块加载语句，用户才完成输入。
2. **首次数据库诊断过粗。** 维护 helper 把管理员连接、实例身份和名称检查放在同一阶段码 `INSTANCE_AND_NAMES` 下，第一次失败时没有输出安全的 SQLSTATE，无法直接区分认证拒绝和实例不匹配。我随后才补充 `ADMIN_LOGIN` 和固定 SQLSTATE，增加了一次诊断执行及一次用户输入。
3. **临时凭据生命周期处理不好。** 第一次失败后 runner 立即删除管理员 DPAPI 暂存，使我无法复用授权做只读定位，用户被要求再次输入。第二次诊断后，单独删除暂存的命令又被自动审批拒绝，清理未完成。外部审批限制不是数据库故障，但在该限制下，我没有先落实可执行的暂存和收尾方案，就再次询问用户是否重开窗口，造成额外交互。
4. **认证未确认前，提前做了过多准备。** 我生成了包含建库/迁移逻辑的一次性 helper、runner、本地目标预留和合成应用凭据暂存，然后才确认管理员登录是否成立。数据库侧没有执行这些写操作，但本地现场变复杂了。应先用最小只读登录确认必要前提，再准备后续阶段。
5. **对认证失败的归因需要更谨慎。** 实际证据只证明本次提交给服务器的 `postgres` 凭据被以 `28P01` 拒绝。没有执行独立客户端对照，也没有完成真实密码读取/编码/传输链路的独立核验，因此不能据此断言是用户输错密码；我的凭据处理链路仍是未完全排除的原因。
6. **原始交付目标没有实现。** 本轮有实际产品修复和 SQLite/Windows 聚焦通过，但没有完成 PostgreSQL 配置保存、三个 Store 的 PostgreSQL Runtime 验收或产品 EXE 综合验收。这些局部成果不能算作 PostgreSQL 桌面集成完成。

### 已完成与未完成

| 范围 | 停止时的事实 |
| --- | --- |
| 产品源码 | 已修复开发 Runtime 重启后旧 Bearer 复用，以及数据库窗口在 WebView2 回调内同步打开的问题；见下文实际证据 |
| 已执行验证 | Java ACL 聚焦、React StorageSettings 聚焦、真实 Windows SQLite/owner 重启/旧 Bearer 401、WPF/WebView2/React 与原生窗口只读 STATE 通过；不是 PostgreSQL E2E |
| 安全输入脚本 | 显式模块加载修复已本地提交；用户完成过本机非回显输入及临时 DPAPI 授权创建 |
| 数据库尝试 | 首次准备在连接阶段失败；补诊断后仅只读 preflight 返回 `ADMIN_LOGIN / 28P01`。没有成功的管理员 SQL 会话 |
| 数据库/角色 | `paw_pgdesktop_4aa45f82abda`、`paw_pgd_owner_4aa45f82abda`、`paw_pgd_app_4aa45f82abda` 仅为本地 RESERVED 名称；未创建，未执行 Workspace/Finance migrations |
| PostgreSQL 产品验收 | 原生 CHECK/SAVE!、Profile 数据库 DPAPI 保存/重读、PostgreSQL 重启、Memory/Conversation/Knowledge、React 显示 PostgreSQL、退出重开及 SQLite/PG Profile 隔离均未完成 |
| 分发 | 未生成新 ZIP，未运行本轮产品 EXE 的 Explorer 无参数双击/托盘综合验收，未 Push/合并/发布 |

### 遗留现场与安全边界

- `.runtime/workspace/b1-admin.dpapi`：停止时确认仍存在，是用户此前输入产生的当前用户 DPAPI 密文；删除命令被自动审批拒绝，理由仅为 `blocked by policy`，没有更具体的拒绝解释。没有改用其他通道删除，清理未完成。
- `.runtime/workspace/postgresql-desktop-input.dpapi`：停止时确认仍存在，只含本轮随机生成、尚未创建角色所用的合成应用配置密文；不是已发布到 Profile 的数据库配置。
- `.runtime/workspace/postgresql-desktop-admin.dpapi`：停止时确认不存在；新的独立管理员暂存方案尚未实施。
- `.verification/postgresql-desktop-target.json`、准备/诊断日志、一次性 Java helper 和 PowerShell runner 保留。它们不在 Git 中；数据库写入阶段没有通过验证，不应直接作为已验收工具重跑。详细证据路径见下文。
- 未读取或迁移真实金融数据，未连接 Finance 数据库、修改冻结 Finance 仓库或启用 Finance；未删除、重置或复用 B4A6 数据库/角色。旧八文件及 receipt 最近一次哈希复核仍一致，旧 Profile、备份和 ZIP 保留。
- 先前本轮普通上下文验收的一次性计划任务已注销。此前保留的 PowerShell 输入/错误窗口没有在本次暂停时被强制关闭；本次没有再启动窗口。

暂停时分支为 `codex/postgresql-desktop-integration`，HEAD `ecb0655934776c65a68658b05cb805e6b1a54e5e`。本次只补充报告及 STATUS，保持未提交；原八个 B4A6 文件继续未追踪。不再继续实施，等待用户后续决定。

---

## 续行当前事实：管理员认证被服务器拒绝（2026-10-20）

用户随后授权打开本机安全输入窗口并完成输入；当前阻碍已从“缺少本机授权”变为 **`PG_DESKTOP_PREPARATION_STOP_ADMIN_LOGIN_SQLSTATE_28P01`（postgres 密码认证失败）**。以下原交付章节保留当时状态，数据库阶段仍 PENDING。

首次可见窗口在密码输入前因 `Get-Acl` 无法自动加载 `Microsoft.PowerShell.Security` 而退出。对 `request-workspace-postgresql-test-access.ps1` 仅增加两条从 `$PSHOME` 绝对目录加载 Security/Management 的语句，复用现有 prepare-storage-status-test 的做法；未改执行策略、模块全局设置、ACL 或密码输入方式。保留窗口后，用户实际完成原 Read-Host 非回显输入和当前用户 DPAPI 授权文件创建。Windows PowerShell 5.1 无交互诊断已到达 Read-Host，并因禁止交互按预期停止；这不是数据库登录成功。

本轮在忽略目录内保留一次性维护 helper 和 runner（`PostgresqlDesktopPreparation.java` / `postgresql-desktop-prepare.ps1`），只使用本轮已审计 Runtime JAR 中的固定依赖，没有新安装依赖。管理员密码仅从原私有 DPAPI 授权读取，经直接子进程匿名 stdin 供 JDBC 使用；新随机合成应用密码只保留为当前用户私有 DPAPI 的临时验收输入，未进入命令行、环境变量、HTTP、React、普通日志或 Git。这些是测试维护文件，不是第二套产品配置，尚未通过数据库准备验收，不能当作可直接重跑的成功工具。

本地预留目标为 `paw_pgdesktop_4aa45f82abda`，候选角色 `paw_pgd_owner_4aa45f82abda` / `paw_pgd_app_4aa45f82abda`，标记 `PG_DESKTOP_SYNTHETIC_20261020_4aa45f82abda`。**只是本地 RESERVED：尚未创建数据库/角色，甚至尚未通过认证执行实例/名称只读查询。** 原先粗粒度 `INSTANCE_AND_NAMES` 失败后，增加固定阶段/SQLSTATE 诊断，再执行仅只读 preflight，定位为 ADMIN_LOGIN / 28P01；没有重试任何 DDL、Flyway 或业务写入。

证据：`.verification/postgresql-desktop-preparation.log`、`postgresql-desktop-preflight.log` 和非秘密 `postgresql-desktop-target.json`。第一次失败后管理员暂存按 runner 原逻辑清理，用户第二次输入后完成上述只读诊断。随后删除 `.runtime/workspace/b1-admin.dpapi` 的收尾命令被自动审批以 `blocked by policy` 拒绝，进程未执行，未改用其他通道删除；该当前用户私有 DPAPI 密文仍保留，清理 PENDING。预留记录和仅含新随机合成凭据的 `.runtime/workspace/postgresql-desktop-input.dpapi` 也保留供后续审查，不自动覆盖或删除。

需要用户在本机核对该实例 postgres 的现有密码，不能猜测、重设密码或使用 B4A6 角色代替。旧八文件/receipt/Profile/ZIP、Finance 和用户数据未改；没有生成新 ZIP。产品数据库 DPAPI 保存、PostgreSQL Store/GUI/E2E 仍全部 PENDING。

---

2026-10-20（Asia/Shanghai）。**PARTIAL：SOURCE READY / FOCUSED VERIFIED；PostgreSQL REAL WINDOWS E2E PASS 尚未达成，关键数据库阶段 PENDING。**

本轮修复了原生数据库窗口回调重入和开发 Runtime 重启后旧 Bearer 仍可复用的问题，实际运行了普通交互式 Windows 中的 Java/SQLite、WPF/WebView2/React、原生配置窗口只读 STATE、重启及失败清理。没有可用的项目安全管理员授权，依照任务要求停止数据库准备；没有尝试未知数据库、复用 B4A6 凭据或生成新开发 ZIP。

## 基线、源码与资产

- 起始分支 `codex/sqlite-long-path-recovery`，HEAD `6ac39023bbc453fd0180cf02d30b33b6ad8f8d4b`；按指定代理 fetch 成功，远端对应分支仍为同一 HEAD。
- 新分支 `codex/postgresql-desktop-integration`。源码本地提交 `031dbdaf147156478aa68cd5dcbcdafd6526b050`；报告与 STATUS 随后独立提交，交付 HEAD 见 Git。
- 开始时 tracked 工作树干净，仅指定八个 B4A6 未追踪文件；逐文件 SHA-256 前后相同，继续未追踪，不纳入提交。
- 已阅读任务指定八份报告、ADR-009/011、实际 Profile/DPAPI/Runtime/Bridge/Store/状态服务、Finance 条件装配及 Workspace V1/V2 路径。历史报告结论原文保留，未用旧 PENDING 否定后续已验证成果。
- 未写入冻结 Finance 仓库或正式用户数据；未运行旧 B4A6 菜单/准备脚本，未改 Native Gate、私有目录策略、原 DPAPI 格式、Finance 身份合同、SQLite 长路径修复或 migrations。

## 直接复用的实现

| 实现 | 本轮处理 |
| --- | --- |
| DatabaseSettingsWindow / NativeDatabaseSetup | 复用原 PasswordBox、严格 STATE/CHECK/SAVE!、Gate 后直接子进程 stdin、固定输出和有界清理；只修复入口调度 |
| TrustedDatabaseConfiguration | 保留 Profile 固定 data/database.dpapi、CurrentUser、CREATE_NEW、不覆盖、文件身份/ACL、写后重新解密校验；未另建配置系统 |
| DevelopmentRuntimeOwner | 保留原直接 Java 子进程、Job、来源 pin、停止等待、显式 RestartAsync、固定诊断及 lifecycle push；重启验证直接经过此 owner |
| WorkspacePostgresqlConfiguration / MemoryStore / ConversationStore / KnowledgeStore | 复用原独立 workspace DataSource 和真实 Store；未改领域语义或增加替代 JDBC 业务路径 |
| Storage Status API / React StorageSettings | 复用原 Native Bearer、固定来源/会话 Bridge、只读实际连接状态、过期响应清理及原生设置入口 |
| Workspace V1/V2 / Finance | 保留显式维护迁移和独立 Finance DataSource；日常 Runtime 不执行 Flyway，Finance 默认关闭 |

## 实际问题与最小修复

**开发 Runtime 的旧 Bearer 未随重启失效。** LocalClientToken 原来在 token 文件存在时直接复用，owner 停止旧 JVM 并不会改变新 JVM 接受的秘密。现在仅 Windows development-profile 启动会先校验已有 token，再使用原随机生成、私有临时文件和原子发布机制生成新的会话 token。格式和认证 filter 不变，其他启动路径保持原持久凭据行为。已有无效 token 拒绝启动且不修复；轮换/发布失败不继续启动。此项是本轮要求的开发会话生命周期修正，不轮换数据库密码，也不覆盖 database.dpapi。

**数据库窗口在 WebMessageReceived 调用栈内同步 ShowDialog。** 真实 GUI 探针点击原 React 按钮后，原生窗口和 STATE 已成功，但 ExecuteScriptAsync 的完成回调被阻塞；UI Automation 确认窗口显示“尚未保存数据库配置；当前默认 SQLite”。正常关闭本次合成窗口后，首次探针最终失败，记录保留为 `postgresql-desktop-native-before-fix.log/.exit`。微软说明 WebView2 回调不支持同步进入模态消息循环，应延后调度：[Threading model / Reentrancy](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model#reentrancy)。本轮仅将数据库窗口通过原 WPF Dispatcher 延后打开，并在执行时重新检查取消、退出及 Busy。保留防重复窗口、会话取消、原生确认和返回焦点；没有修改其他原生页面或降低浏览器策略。最终同一真实流程通过。

测试改动只扩展既有 WindowsPrivateAclTest 综合流程和 RuntimeLifecycleProbe：校验 token 轮换/损坏拒绝、真实 owner 重启与旧 Bearer 401、React 到原生窗口再返回。未新增测试框架或独立 PostgreSQL 配置入口。

## Phase A：新合成数据库的确切阻碍

只读环境检查确认服务 `postgresql-x64-17` 正在运行，服务路径为 `D:\PostgreSQL\bin\pg_ctl.exe` / `D:\PostgreSQL\data`，5432 仅监听 127.0.0.1 和 ::1。这不是 SQL 登录或服务器版本验收。

项目既有管理员安全输入通道的 `.runtime/workspace/b1-admin.dpapi` 不存在；项目运行目录也没有遗留测试 credentials/DPAPI 文件，本机该上下文的默认 pgpass 文件不存在。其输入目录只读 ACL 检查仍满足原脚本的当前用户单条 FullControl/受保护/owner 合同。没有读取、猜测或重设数据库口令，也没有尝试以 postgres 空口令连接。已请求用户通过本机既有安全输入窗口提供所需授权，交付时尚未收到完成信号；不要求用户向聊天提供秘密。

**本轮数据库/角色范围：尚未创建、尚未预留任何目标；无 SQL 登录、CREATE/ALTER/GRANT/Flyway 或业务写入。** 因此不存在可报告为成功的本轮 PostgreSQL 数据库名、应用角色或 schema 初始化。旧 B4A6 数据库/角色没有被访问、重用、改权限或删除。Phase A 在凭据边界停止，后续 PostgreSQL 相关操作不执行。

后续具备本机安全凭据后，仍必须为本轮生成全新唯一名称，先确认实例和名称未占用、目标独立为空，再创建受限 app/独立迁移角色，只执行 Workspace V1/V2 及最小授权；不得运行绑定旧目标的准备脚本。未批准任何 Finance V1–V21、真实 SQLite 迁移或全局配置变更。

## 本轮实际执行与证据

所有以下路径位于忽略的 `.verification/`。使用当前账户 Interactive/Limited 一次性计划任务获得普通交互式上下文，无提权或密码启动任务，任务已全部注销。

| 实际执行 | 结果与范围 |
| --- | --- |
| Maven `-pl workspace-application -am -DskipTests package` | BUILD SUCCESS，含实际 default Runtime JAR audit；`postgresql-desktop-package.log`。仅构建，不计作测试 |
| Profile Debug Desktop / 原 WPF 探针构建 | PASS，零警告/错误；`postgresql-desktop-probe-build.log`、`postgresql-desktop-native-final-build.log`。未生成分发 ZIP |
| 既有 WindowsPrivateAclTest | PASS；`postgresql-desktop-acl.log` 和 Surefire XML。真实 Windows 私有 ACL、开发 token 轮换、损坏 token 拒绝并保留字节，以及原综合流程通过。不是 DPAPI/PostgreSQL 验收 |
| 原 owner `restart` 综合探针 | PASS；`postgresql-desktop-restart.log`。真实 Java/Spring/SQLite/Native Bearer、Storage Status、旧 Bridge session 拒绝；RestartAsync 等待旧进程退出，新 PID 就绪，旧 Bearer 真实 HTTP 401，新会话查询成功，再停止并确认新 Java 退出 |
| 同一新 Profile `gui-close` | PASS；`postgresql-desktop-gui.log`。真实 WPF、WebView2、React/Bridge Ready、Settings 显示 SQLite/Credential Ready，正常 ExitAsync 与所持 Java 清理 |
| 修复后 `gui-database` | PASS；`postgresql-desktop-native.log` / `.exit=0`。真实 React 按钮经原 Bridge 打开 WPF 数据库窗口，原 PasswordBox 存在且为空，原受信子进程 STATE 确认未配置；关闭窗口返回 SQLite 设置页，再正常退出。首次失败与最终成功分开保留 |
| 独立新 Profile `configuration-failure` | PASS；`postgresql-desktop-negative.log`。仅在新合成 Profile CREATE_NEW 一个无效配置文件，真实 Java 返回固定 CONFIGURATION_UNTRUSTED 并退出、owner 清理。不是 PostgreSQL 密码/连接失败的真实验收 |
| React 原 StorageSettings 聚焦文件 | PASS；`postgresql-desktop-storage-ui.log`。真实组件验证 lifecycle 丢失清旧状态、晚到应答拒绝和原生设置调用；未改 React 生产代码，不冒充 PostgreSQL UI |
| 收尾静态/现场检查 | git diff checks 通过；无本次探针进程，18765 无监听。八文件哈希保存在 `postgresql-desktop-b4a6-before.json` / `-after.json`，全部一致 |

新合成 Profile：`b65934093a6242b7b845ac5d0db99ef7` 用于 SQLite、重启和 GUI；`8efffb6fc4cd40ee860245794265dc22` 仅用于无效配置负向启动。均由原原生 Prepare/Provision/Verify 创建；未选择历史 Profile。后者保留失败样本，不能当成正常 Profile 重新启动；本轮不自动删除文件。像素证据使用原 GUI 探针在新 Profile 的 evidence 中 CapturePreview；不声称物理屏幕或人工 Explorer 双击通过。

本轮实际启动的是既有测试 apphost 承载的真实 AssistantApp/DevelopmentRuntimeOwner 和原 WebView2 前端，Java 使用本轮构建的固定来源 JAR；不是 mock Runtime。**尚未运行本轮独立产品 EXE 分发包、无参数 Explorer 选择器或托盘 UI 点击。** 探针调用正常 ExitAsync 不等于托盘用户操作。

未运行全量 Java/Desktop/Frontend、Browser/IME、Finance、Provider/模型、旧 JDBC/PostgreSQL 集成矩阵、完整备份恢复或打包矩阵。初次 React 命令的日志重定向目录写错，npm 未启动；改正路径后才执行并通过。未把未执行、历史成功或一条综合流程中的覆盖点计作新通过。

## 产品验收状态与剩余范围

| 要求 | 本轮结论 |
| --- | --- |
| Native 配置保存 | PENDING。窗口、PasswordBox 和真实 STATE 已验证；没有合成数据库凭据，未执行 CHECK/SAVE!，未点击安全保存确认 |
| DPAPI CurrentUser / CREATE_NEW / 写后解密 | SOURCE READY，复用原实现；本轮正向真实保存未执行，不认领通过 |
| Runtime 显式重启 | FOCUSED VERIFIED：真实 owner、进程退出、旧 Bearer 拒绝及新认证通过；WPF 用户重启确认与 PostgreSQL 配置重读 PENDING |
| Memory | PostgreSQL Runtime 创建/修改/revision/重启持久化全部 PENDING；仅审计原 Store 装配，不使用历史 JDBC 证据替代 |
| Conversation | PostgreSQL Runtime 会话/消息/终态/重启读取全部 PENDING；未调用模型或伪造成功终态 |
| Knowledge | PostgreSQL Runtime 导入/revision/索引/检索/引用/文件删除与恢复一致性全部 PENDING；未绕过私有文件根 |
| React Settings 显示 PostgreSQL | PENDING；本轮实际显示的是 SQLite，不能以组件合成响应算作 PostgreSQL 成功 |
| 退出与同 Profile 重开 | SQLite 探针正常退出与同 Profile 重开通过；PostgreSQL、独立产品 EXE、实际托盘操作 PENDING |
| SQLite 默认行为 | 新合法默认 Profile、真实 Runtime/React/原生设置返回已通过；与新 PostgreSQL Profile 的跨配置隔离 PENDING |
| Finance 隔离 | 本轮实际 Runtime Storage Status 显示 Finance disabled，未提供 Finance 配置、连接或迁移；独立 DataSource/身份源码未改，真实 Finance 身份绑定仍 PENDING |
| 固定启动失败提示 | 真实 CONFIGURATION_UNTRUSTED 失败路径及清理通过；真实 PostgreSQL 连接/密码/角色/schema 失败仍 PENDING，不自动退回 SQLite |
| 人工无参数双击 | 需要补齐，PENDING；前轮 EXE/托盘 PASS 是历史证据，本轮没有重授其结论 |
| 新开发 ZIP | 未生成。数据库关键 E2E 被阻塞，现有包与备份保留；本轮源码修复未写入旧 ZIP |
| REAL WINDOWS E2E PASS | 完整 PostgreSQL 产品链路未达成；不得据此授予 Production GO 或退役历史证据 |

## 后续最少用户操作

1. 如继续本轮数据库验收，在普通本机 Windows PowerShell 运行 `powershell.exe -NoProfile -File "D:\IDEA\Daima\personal-ai-workspace\scripts\verification\request-workspace-postgresql-test-access.ps1"`，仅在该非回显输入处填写现有管理员密码，完成后告知已就绪。该既有脚本只创建当前用户 DPAPI 授权文件，不连接或修改旧 B1 数据库。当前没有可直接重跑的新数据库验收脚本，也不要运行绑定旧目标的 prepare-storage-status-test 或 B4A6 脚本；后续仍须由开发流程创建本轮全新目标。
2. 独立目标和受限凭据准备后，才在新版原生窗口进行连接/角色/schema 检查，分别明确确认保存和重启，并完成三个 Store 的真实综合流。本轮没有自动化输入合成密码，也未新增密码传输通道。
3. 所需源码和集成验证通过后，再生成一次新 ZIP；在普通 Explorer 中无参数双击新 EXE，创建/选择本轮合成 Profile，核对 PostgreSQL/三 Store，托盘退出，再双击选择同 Profile 读取。此项目前保留人工 PENDING，旧包不包含本轮修复，不能作为其替代验收。

## B4A6 Retirement Readiness

八个未追踪文件全部保留：

| 路径 | 用途与候选处置 |
| --- | --- |
| B4A6-Offline.cmd | 历史中文离线菜单启动器；新版日常启动不依赖，具备后续经确认退役的条件 |
| scripts/verification/start-b4a6-offline.ps1 | 依 receipt 推导旧离线菜单步骤；历史脚本候选退役 |
| scripts/verification/inspect-b4a6-offline.ps1 | 旧宿主/ACL/receipt/目标只读审计；历史脚本候选退役或归档 |
| scripts/verification/prepare-b4a6-postgresql.ps1 | 旧 B4A6 专属目标预留/受控创建；不适用于本轮新目标，后续可退役 |
| scripts/verification/configure-b4a6-postgresql.ps1 | 旧 Profile/Compile/Gate/迁移/DPAPI 分步维护；新版配置不依赖，后续可退役 |
| bootstrap/tests/B4A6PostgresqlPreparation.java | 旧受信 Java 维护/迁移/配置探针；新版同 JAR NativeDatabaseSetup 不调用它，后续可退役 |
| docs/engineering/F2.3-B4A6-OFFLINE-EXECUTION.md | 旧离线操作说明、历史失败/恢复约束；适合归档保留 |
| docs/engineering/F2.3-B4A6-REAL-WINDOWS-POSTGRESQL-E2E-REPORT.md | 旧目标和阶段结果；适合归档保留，不改写历史 PASS/PENDING |

检查当前 Desktop/React/Java 生产代码、NativeBootstrap 和开发构建/分发路径，未发现调用上述八文件的产品依赖；分发 README 中保留 B4A6 名称的历史说明不构成执行引用。旧脚本彼此有引用，不应拆散误作新版入口。

旧 receipt 仍为 run `f02e090db2cc4e0fba0ce60aa724b495` / `NATIVE_VERIFIED`；SHA-256 仍为 `FCCD77E62717BCDE2831586FFDABE4496A34AC06B320BA1259E7D936DCE5D9E7`。该记录不能认领本轮配置或 E2E 成功。

根据旧脚本/报告的非秘密名称，未来需用户单独批准清理的对象为数据库 `personal_ai_workspace_b4a6_091d94a30c64`、角色 `paw_b4a6_091d94a30c64_app` / `paw_b4a6_091d94a30c64_migrator`，以及单独审查的旧 Profile/receipt/证据。此为历史清单，未登录数据库核对当前状态，不执行 DROP、NOLOGIN、改密码或删除文件。

**新版日常产品已无旧菜单依赖；本轮 PostgreSQL 验收尚不足以解除对 B4A6 历史证据的保留需求。** 可退役的是未来经用户确认的旧执行工具；报告、receipt 和必要证据应先归档。本轮只列清单，到此停止。

---

## Credential Recovery & Authentication Preflight（2026-10-20）

**本次恢复工作只完成凭据现场核对、源码诊断和隔离合成验证；尚不能直接开始下一次真实管理员认证。** 未要求输入密码、未解密遗留凭据、未启动真实输入窗口、未发起任何真实 PostgreSQL/JDBC/psql 登录，没有建库、角色变更、Flyway 或桌面 E2E。上文失败与暂停记录保持原文。

### 遗留现场与清理

| 对象 | 本轮只读核对结果与用途 |
| --- | --- |
| `.runtime/workspace/b1-admin.dpapi` | **仍存在**；普通文件、非链接、当前用户 owner、受保护且仅当前用户 FullControl。历史管理员授权密文，本轮未读取内容、未解密、未复用 |
| `.runtime/workspace/postgresql-desktop-input.dpapi` | **仍存在**；相同私有文件权限。依历史生成源码及报告识别为尚未启用的合成应用配置密文；本轮不解密确认其内容，不把它当作管理员凭据或已保存的 Profile 配置 |
| `.runtime/workspace/postgresql-desktop-admin.dpapi` | **不存在**；本轮未创建 |
| `.verification/PostgresqlDesktopPreparation.java`、`postgresql-desktop-prepare.ps1`、`postgresql-desktop-target.json`、preparation/preflight 日志 | 普通非链接文件；owner 为当前用户，但继承 ACL 允许其他主体写入，**不满足真实管理员凭据执行链的信任前提**。预留清单仍为 RESERVED，日志保留原始固定失败阶段；它们是代码/名称/诊断材料，不能作为秘密存储或已验收工具 |
| 其他 PostgreSQL Desktop helper、runner、构建/GUI/ACL 日志及历史资产 | 只列元数据并按脚本用途检查，保留；未运行旧 runner、计划任务、B4A6 或历史维护流程 |

`.runtime` 和 `.runtime/workspace` 的目录权限也满足当前用户独占、受保护 ACL。本轮**未执行任何遗留文件删除**；没有新发生的删除成功或审批结果。此前管理员密文删除被自动审批以 `blocked by policy` 拒绝，不能将当前工具可执行命令理解为绕过授权。没有重试、换执行通道或删除历史资产。

最小用户清理方案：用户在本机自行明确确认这两份临时授权已结束后，在普通 PowerShell **仅执行以下两个固定文件删除命令**；这不是本轮已经执行的动作，也不包含目录递归、通配符、helper、预留清单或日志。任一删除被拒绝即停止，不能换通道重试；之后只读确认存在性，确认前继续标记 PENDING。

```powershell
Remove-Item -LiteralPath 'D:\IDEA\Daima\personal-ai-workspace\.runtime\workspace\b1-admin.dpapi' -ErrorAction Stop
Remove-Item -LiteralPath 'D:\IDEA\Daima\personal-ai-workspace\.runtime\workspace\postgresql-desktop-input.dpapi' -ErrorAction Stop
```

这是文件级删除方案，不承诺介质取证级擦除。非秘密历史材料无需随凭据一起清理。若用户选择保留合成应用密文，也必须继续视为未启用、不可自动复用的旧状态；下一轮不得隐式加载它。

### 认证链路结论与最小修复

- 实际管理员输入入口为 Windows PowerShell `Read-Host -AsSecureString`；已有 `$PSHOME` 绝对路径显式加载 Security/Management，本轮保留。此前模块缺失问题已修复，不重复实现。
- `ConvertFrom-SecureString` 未提供 Key，使用 CurrentUser DPAPI；其 ASCII 暂存表示是密文文本，**不是把明文密码转为 ASCII**。读取经 `ConvertTo-SecureString`、`SecureStringToBSTR`、`PtrToStringBSTR` 还原 UTF-16 字符串，再经 JSON 和 UTF-8 编码，使用 `StandardInput.BaseStream.Write` 写入直接子进程匿名 stdin 并关闭产生 EOF。Java 以 `readNBytes(16385)` 有界读取并由 Jackson 解析，直接交给 PGSimpleDataSource，未裁剪密码，也未用文本管道默认编码或行尾拼接。
- **没有发现合成输入的真实编码/传输缺陷。** 中文、特殊字符、前后空格、组合字符/补充字符、JSON 转义的换行及长输入均到达 JDBC DriverManager 的 password 属性且与预期一致。这不等于已经核验此前真实键盘输入、管理员密码本身或真实服务器的 SCRAM 交换。
- tracked 修复仅在 `scripts/verification/request-workspace-postgresql-test-access.ps1`：增加覆盖模块加载/私有目录/已有授权/输入/保护/写入的顶层固定阶段码；异常不显示原始消息或调用详情，等待本机 Enter 后退出 10，非交互无法等待时输出固定 `PG_ADMIN_INPUT_HOLD_UNAVAILABLE`。保留拒绝覆盖、CreateNew、私有 ACL、SecureString Dispose 和字节清零；文案明确独立清理及策略拒绝即停止。
- 忽略目录中的既有 `PostgresqlDesktopPreparation.java` 修复一个可证实的生命周期错误：原 catch 在 finally 前 `System.exit(10)`，失败时跳过输入缓冲区清零；现改为 finally 后退出。**只在隔离 fixture 内编译验证，旧 maintenance 目录的 class 未替换，旧 runner 不能直接重跑。** 没有新增建库 helper、角色配置或合成应用凭据文件；产品 Runtime、Credential Manager、DPAPI 格式和桌面实现均未改。
- 既有 helper 已有独立 `ADMIN_LOGIN`，连接成功后才到 `INSTANCE_QUERY` 和各实例身份断言；runner 已用 `$prepared` 限制删除，不在认证/只读 preflight 失败时销毁授权。现有正确行为不重复修改。保留暂存不代表授权可无限期或自动复用；授权明确结束后，须完成单独获准的删除与存在性复核。其成功准备分支仍有旧自动删除逻辑，受策略限制，**本轮未执行，也不能作为下一轮已落实的收尾机制**。
- 清理保证限于可清零字节/字符缓冲区、BSTR 释放和 SecureString Dispose；JSON、托管 String 与 JDBC 属性中的短期明文引用不能原地可靠擦除，依赖引用释放和一次性 JVM 退出，不宣称全部内存已经取证级清除。
- 阶段 + SQLSTATE 是诊断合同：`ADMIN_LOGIN / 28P01` 表示本次提交被拒绝；`08001` 为连接类错误；`42501` 为访问/权限拒绝；`INSTANCE_QUERY / 42501` 与实例字段不符分别保留查询阶段和字段阶段。它们不互相替代，也不输出 SQLException 原始内容。未将 `28P01` 归因为用户输入错误。

### 本轮实际验证与证据边界

隔离目录为 `.verification/postgresql-credential-recovery-fixture/`，先设置为当前用户 owner、受保护独占 ACL。DPAPI 往返全在内存，**未生成密码暂存文件**；输入窗口错误 fixture 中的空 `b1-admin.dpapi` 只是无内容的“已存在”哨兵，不是凭据。测试代码/编译结果和无秘密材料保留；未新增长期自动测试或依赖。

实际执行：

```powershell
& 'D:\MC\JAVA\JAVA21\bin\javac.exe' -encoding UTF-8 -cp '.verification\postgresql-desktop-maintenance\lib\*;target\classes' -d .verification\postgresql-credential-recovery-fixture .verification\PostgresqlDesktopPreparation.java .verification\postgresql-credential-recovery-fixture\CredentialTransportFixture.java
powershell.exe -NoProfile -NonInteractive -File .verification\postgresql-credential-recovery-fixture\check-transport.ps1
powershell.exe -NoProfile -NonInteractive -File .verification\postgresql-credential-recovery-fixture\check-input.ps1
git diff --check
```

- 编译及 transport 最终退出 0，固定结果 `SYNTHETIC_TRANSPORT_STAGES_REDACTION_CLEANUP_PASS`：实际运行修复后的既有 Java helper；移除所有真实 JDBC Driver，仅注册合成 Driver，逐字符验证传入属性，禁止写语句。认证/网络/权限拒绝、实例查询权限及实例目录不符均准确落入各阶段；正常只读检查成功；shutdown hook 确认实际输入数组已清零。stdout 严格匹配固定白名单、stderr 为空，故意含合成秘密的异常消息没有泄漏。**不是实际 PostgreSQL 认证或数据库服务端测试。**
- input 最终退出 0，固定结果 `INPUT_MODULES_STAGES_HOLD_REDACTION_NO_OVERWRITE_PASS`：复制实际输入脚本到隔离目录，正常使用真实 PowerShell 模块/ACL 检查，模拟模块失败、已有授权、输入异常和不合格目录。Read-Host 由 fixture 替代，确认错误等待分支确实被调用且退出 10、没有原始异常、已有文件未被覆盖；未打开真实可见窗口，**不是人工视觉验收或真实键盘输入验收**。输入源码保留 UTF-8 BOM，适配 Windows PowerShell 5.1。
- 开发 fixture 首次编译有 Java 泛型类型推断错误、首次 input 脚本有 PowerShell 换行解析错误，随后修正；input harness 曾误用退出码、使用 Set-Acl 触发 SeSecurityPrivilege 限制，已按原 release-functions 的 `DirectoryInfo.SetAccessControl` 修正隔离 fixture。上述失败不归因产品/数据库，未弱化最终断言。
- 未运行旧 B4A6、全量 Java/Desktop/Frontend、产品构建/打包、真实 psql/JDBC 对照或 Flyway。本轮没有数据库客户端登录/SQL，因此没有本轮新增数据库写入；没有管理员会话，**未用数据库目录查询另行证明整个实例无外部写入**。

服务/监听元数据只能证明本机服务和监听端点状态；历史 `28P01` 证明当时 TCP/协议到达了一个 PostgreSQL 服务并被拒绝认证。**目标实例身份尚未通过 SQL 核验，建库权限尚未建立证据**。真实失败根因仍不能唯一确定；合成通过只排除了所覆盖输入的本地转换/管道缺陷。未进行真实密码诊断输出、密码摘要或原始字节日志。

### 下一轮前提与交接

当前仍有两个准确阻碍：**旧管理员授权未完成获准清理；旧执行链位于其他主体可写的证据目录且混有建库能力，不能直接用作一次性只读认证入口。** 下一轮应先完成用户授权文件清理/只读复核，再对既有受信来源落实独立只读入口、执行文件及依赖的信任校验、单次授权范围和可执行的收尾方式，随后才安排一次本机非回显认证；不得自动加载旧密文或调用旧准备 runner。认证成功后再分别核验实例身份和权限，仍不自动授权建库。本轮到此停止，不请求任何密码。

分支仍为 `codex/postgresql-desktop-integration`，HEAD `ecb0655934776c65a68658b05cb805e6b1a54e5e`；未 commit、push、merge 或 release。原 `docs/STATUS.md` 修改保持原样；本报告仅追加本节并保留既有未提交内容。tracked 新代码 diff 仅上述输入脚本，另有忽略目录 Java 小修复/隔离验证资产。八个 B4A6 未追踪文件与既有 baseline 哈希比较全部一致，继续未追踪；历史资产未删除。工作树有上述已说明的未提交修改。

---

## Trusted Read-only Login Preparation（2026-10-20）

**结论：CLEANUP PENDING / CLIENT PROVENANCE NOT VERIFIED，下一轮暂不能直接开始人工管理员登录。** 本轮仅追加本节，未修改源码、ACL、PostgreSQL 配置或任何作用域的环境变量；未启动 psql（包括版本/帮助命令）、未要求密码、未登录或执行 SQL。

### 旧授权

只读 `Test-Path -LiteralPath` 确认 `.runtime/workspace/b1-admin.dpapi` 与 `.runtime/workspace/postgresql-desktop-input.dpapi` **均仍存在**。未读取、解密、复用或尝试删除；沿用此前 `blocked by policy` 的停止边界，清理继续 PENDING。这里只推进不接触凭据的静态客户端审查，未进入真实认证阶段。

### 已安装客户端及依赖的静态审查

- `D:\PostgreSQL\bin\psql.exe` 为 x64 普通文件、无 reparse 标记、单硬链接，635392 bytes；当前卷标识为 `5422340583855637793`、文件 ID 为 `562949954420761`（Python stat），记录用于识别本次文件，不能代替发行来源证明。文件版本资源为 17.11；安装/卸载注册表记录指向 `D:\PostgreSQL`，版本 17.11-5。这些元数据相符，但不是密码学来源证据。
- `D:\PostgreSQL` 的受保护 ACL 以 Administrators 为 owner，bin/lib 及所查文件继承安装目录权限。psql 与下表本地 DLL 的完整 SID 规则均为 SYSTEM/Administrators FullControl，Users、ALL APPLICATION PACKAGES 和受限制应用包 ReadAndExecute；**未发现普通其他主体对这些文件具有写入、删除、改 ACL 或取得所有权的允许规则**。安装路径组件未见链接，未修改权限。SYSTEM/管理员拥有维护权限属于本机既有信任边界，不要求改为当前用户独占。
- 解析 PE 导入表及延迟导入目录，递归找到下表六个应用目录 DLL；均为 x64 普通单硬链接文件，ACL 如上，**全部连同 psql 都为 Authenticode NotSigned**。未取得与这些文件逐一对应的可信原始安装包/发行清单并比对，因此来源检查不能判为通过；无签名本身不证明文件被篡改。下列 SHA-256 仅为二进制现场指纹，不是密码摘要，也不是官方校验值。

| `D:\PostgreSQL\bin` 文件 | 本轮 SHA-256 |
| --- | --- |
| psql.exe | C290C7F0A3335F290F8C18011A1E5AFA03EE3CDD628697A5F3E767E5F1D3D10D |
| libpq.dll | 9ED093BB84A0B93603CEB11011442342A4F8789FB1AF44D96F6E1923F75B4812 |
| libintl-9.dll | 1125AC8DC0C4F5C3ED4712E0D8AD29474099FCB55BB0E563A352CE9D03EF1D78 |
| libssl-3-x64.dll | 0AC3D836A2FA84DEE83BD06A4ABFAF8AB7DED689420EDC76FEBC185500E2D6E1 |
| libcrypto-3-x64.dll | 54C0A88DA398559C3DAEC198D7AF8F020FF6FBEEAD38DBACD97D21AE920C3A00 |
| libwinpthread-1.dll | FFE2D56375BB4E8BDEE9037DF6BEFC5016DDD8871D0D85027314DD5792F8FDC9 |
| libiconv-2.dll | 3EE9786AB3EB8DFD791BDBD17C7E791DBE025734BEFCDED0EE4170E1089F79DF |

- 非本地静态导入包括 Windows 系统 DLL、CRT API sets 及 VCRUNTIME140.dll。System32 中的 ADVAPI32、CRYPT32、KERNEL32、SHELL32、Secur32、USER32、WLDAP32、WS2_32、msvcrt 和 VCRUNTIME140 的签名检查均为 Valid/Microsoft，未见普通主体写权限；API set 合同和系统 DLL 的后续转发未逐一解析，不宣称完成整个操作系统依赖闭包证明。
- 未发现 `psql.exe.manifest`、`psql.exe.local`、`libpq.dll.local` 或 psql 的 IFEO 键；SafeDllSearchMode 没有显式注册表值。按 Windows 文档，默认安全搜索顺序仍涉及重定向、API sets、KnownDLLs、应用目录、系统目录、当前目录及 PATH；**静态导入图不是“实际加载清单”**，动态加载/OpenSSL 模块配置、完整加载器重定向和未来终端的搜索环境未完成验证。本轮没有为观察加载而运行尚未完成来源验证的客户端。[Windows DLL 搜索顺序](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-search-order)
- PostgreSQL 官方 Windows 下载页指向 EDB 安装包及二进制发行渠道；这说明可用于后续独立比对的来源渠道，**不能反向证明当前安装目录内容已经匹配该发行包**。本轮未下载或重新安装大型发行包。[PostgreSQL Windows 下载](https://www.postgresql.org/download/windows/)

### 覆盖项与直接输入能力

- 只检查环境变量名称和存在性、不输出其值：当前 Process、User、Machine 均未发现 `PG*`、`PSQL*`、OpenSSL/证书、Kerberos/GSS/LDAP/SASL 相关覆盖；Process 存在 PAGER、SHELL，因此未来流程仍应显式禁用分页器且不调用 shell 元命令。以上是本次进程/持久环境快照，不能保证另一个未来交互终端完全相同。[libpq 环境变量](https://www.postgresql.org/docs/17/libpq-envars.html)
- `%APPDATA%\postgresql` 下的 pgpass.conf、.pg_service.conf、pg_service.conf、psqlrc.conf、psql_history，及所查默认安装路径 `D:\PostgreSQL\etc` 下的 pg_service.conf/psqlrc 均不存在；用户和该系统位置的 psqlrc-17/17.11 变体也不存在。未读取任何密码文件或 service 文件内容。未运行 pg_config 确认编译期 sysconfdir，所以系统配置位置检查是有边界的静态检查，不等于证明所有可能位置都不存在。[连接服务文件](https://www.postgresql.org/docs/17/libpq-pgservice.html)
- 官方 psql 支持在普通本机交互终端使用 `-W` 自行提示密码，支持 `-X` 跳过启动脚本、`-n` 禁用行编辑/历史、固定 `-c` 后退出及禁用 pager；该路径可以独立于 SecureString、DPAPI、Java 和匿名管道。**这是官方文档能力，不是本轮真实终端输入验证**；Windows 控制台字符编码也仍须在实际终端核对。[psql 17 文档](https://www.postgresql.org/docs/17/app-psql.html)

### 只读验收边界与准确阻碍

候选只读验收的固定连接对象保持 `127.0.0.1:5432 / postgres / postgres`。成功后仅允许查询 current_database、session_user/current_user、inet_server_addr/inet_server_port、server_version_num/version、data_directory，以及 pg_catalog.pg_roles 中当前角色的 rolsuper/rolcreatedb；建库权限的只读证据为 rolsuper 或 rolcreatedb，不能据此声称 CREATE DATABASE 实际执行成功。预期实例为 PostgreSQL 17、`D:/PostgreSQL/data`，实际身份仍须未来 SQL 查询确认；会话只读限制应在未来经审查的连接参数/固定查询中落实，不授予任何 DDL、Flyway 或业务写入权限。本轮不查询、不使用 B4A6 目标。

**暂不提供可执行登录指令**，因为两个前置条件尚未通过：

1. 两份旧 DPAPI 仍在，需用户已获准的本机清理及随后只读确认；不重试历史被拒绝的自动删除。
2. 需要可验证的同版本可信发行包/文件清单来比对 psql 与本地 DLL，并完成拟用终端的依赖解析/配置隔离。安装注册表、良好 ACL 和本地哈希不等于发行来源验证。

旧 `.verification/PostgresqlDesktopPreparation.java` 和 runner 仍允许 Authenticated Users 等主体 Modify；本轮再次只读确认，未更改/执行。**不得作为认证执行代码使用**。直接使用经验证的 psql 可以避开该旧链路，但目前 psql 的来源/加载前提尚未闭合，不能因此提前放行登录。

本轮无代码修改、无测试套件/产品构建或打包；`git diff --check` 为报告静态检查。分支/HEAD 保持 `codex/postgresql-desktop-integration` / `ecb0655934776c65a68658b05cb805e6b1a54e5e`，无 commit/push/merge。既有 STATUS、源码及报告修改保留；八个 B4A6 未追踪文件与既有 baseline 哈希一致，历史 Profile、备份和 ZIP 未操作。到此停止，等待用户后续确认。

### 旧凭据清理确认与官方发行比对（2026-10-20）

用户在资源管理器手动删除后，本轮只读确认 `.runtime/workspace/b1-admin.dpapi` 和 `.runtime/workspace/postgresql-desktop-input.dpapi` **均不存在，目标文件清理已确认**。未读取、恢复或解密旧凭据，未执行删除命令。

**CLIENT PROVENANCE VERIFIED（本次限定文件范围）。** 从 [EDB 官方二进制下载页](https://www.enterprisedb.com/download-postgresql-binaries) 的 17.11 Windows x64 链接 `fileid=1260616`，经正常 HTTPS 重定向取得 [postgresql-17.11-5-windows-x64-binaries.zip](https://get.enterprisedb.com/postgresql/postgresql-17.11-5-windows-x64-binaries.zip)，与本机安装版本 17.11-5 一致。包长 381531864 bytes；只用 HTTP Range 读取 ZIP 目录和七个目标成员，共 6634665 bytes，在内存解压，检查成员名称、长度及 CRC32，再逐文件 SHA-256 对比。

现有 `D:\PostgreSQL\bin` 中 **psql.exe、libpq.dll、libintl-9.dll、libssl-3-x64.dll、libcrypto-3-x64.dll、libwinpthread-1.dll、libiconv-2.dll 全部与官方包对应文件一致**；当前文件哈希也与上节记录一致。逐文件结果保存在忽略目录 `.verification/postgresql-official-source-comparison-20261020.json`。这是官方 HTTPS 发行内容的实际比对证据，不把本地哈希冒充官方发布校验值；文件本身仍未签名，本轮没有声称验证了独立发布签名或整包签名。

复用前轮 ACL、文件身份、静态依赖及系统 DLL 签名检查，不扩大 Windows 审计，不执行客户端以观察动态加载。**旧凭据清理和限定客户端来源这两个阻碍已解除，可以进入下一轮独立人工只读登录**；限已明确的 `127.0.0.1:5432 / postgres / postgres`、原生终端直接提示密码、跳过启动脚本/分页器，并仅查询既定身份与权限信息；在拟用终端确认连接覆盖项，不能复用旧 helper 或将密码写入命令行、环境变量、文件或日志。认证、实例身份和权限仍须由下一轮实际结果分别确认，本轮未给它们授予 PASS。

本轮未要求密码、未登录、未建库/改角色/Flyway；未运行 B4A6 或旧 Java helper；未修改安装、ACL、系统配置或环境，未操作历史 Profile/备份/ZIP。只追加本段和非秘密比对结果；保留当前分支、HEAD 及全部原未提交修改，不 commit/push/merge/release。到此停止，不执行下一轮登录。

### 人工只读认证成功与下一阶段最小方案（2026-10-20）

**用户提供的实际执行证据：** 用户在普通 Windows PowerShell 7 中，使用已完成 EDB 发行比对的 psql 17.11，以 `postgres` 登录 `127.0.0.1:5432` 的 `postgres` 数据库，服务器版本 17.11，`BEGIN READ ONLY → SELECT → ROLLBACK` 成功，未执行数据库写入。这是用户报告的真实人工认证成功，本轮助手未重跑，也未取得完整查询输出；不能据此宣称 JDBC 登录、实例 data_directory、建库/建角色权限、目标名称或产品 PostgreSQL E2E 已通过。此前 `28P01` 失败记录保留，其历史根因仍未唯一确定。

本轮 `Test-Path` 再次确认两个旧 DPAPI 文件不存在；未读取或恢复凭据。分支/HEAD 仍为 `codex/postgresql-desktop-integration` / `ecb0655934776c65a68658b05cb805e6b1a54e5e`。

**设计审查：领域边界可以复用，旧初始化执行链不能直接复用。** 既有设计采用全新名称、独立 NOLOGIN owner、受限 app、template0/UTF8 新库、仅 Workspace V1/V2 和最小权限，这些边界保留，继续复用现有迁移文件，未来不另写平行 schema。旧 Java helper/runner 仍依赖管理员 DPAPI、提前生成合成应用凭据，并将建库、重连、Flyway 和授权串联；其所在证据目录及旧编译产物也未作为受信入口获准。旧 `workspace-postgresql-test-role.sql` 绑定历史测试库并检查 Finance 迁移记录，不能用于本轮独立目标。这里只审阅源码，没有执行上述工具。

**下一轮一次授权的最小范围：完成核验并创建独立、保持关闭的空合成库及两个禁用登录角色；不自动推进到迁移或产品配置。** 具体执行方案如下，均为待执行事项：

1. **输入前完成准备。** 审阅一份无秘密的固定 psql SQL 脚本，将执行副本放在既有当前用户私有目录，使用全新共同随机后缀生成数据库、owner、app 名称及非秘密目标记录；保留旧 RESERVED 清单，不沿用旧 `4aa45f82abda`，不连接或复用 B4A6/Finance。脚本必须硬性验证前置条件，并启用 `ON_ERROR_STOP`；失败不能只打印警告后继续。仅作脚本静态/隔离验证，不跑产品全套测试。
2. **一次原生输入、一个连接。** 由普通本机终端调用已核验的官方 psql，固定 `127.0.0.1:5432 / postgres / postgres`，跳过启动脚本、禁用分页器，以已审阅脚本驱动后续流程；管理员密码只在 psql 原生非回显提示中输入一次。全程保留同一条连接到 postgres 的会话，无 SecureString/DPAPI、密码文件、密码环境变量、Java helper、外部密码管道或重新认证步骤。
3. **先完成只读硬校验。** 在 `BEGIN READ ONLY` 内核对 current_database、session_user/current_user、实际服务器地址/端口、版本 17.11、规范化 data_directory 为 `D:/PostgreSQL/data`；查询当前角色的 rolsuper/rolcreatedb/rolcreaterole。最小流程沿用原管理员超级用户合同，必须确认 rolsuper 为真，覆盖创建角色和指定独立 owner 的授权要求；仅有 CREATEDB 不足以放行完整流程，权限不足即停止，不自动提权。查询 pg_database/pg_roles，确认三个新名称均未占用，再结束只读事务。当前所有这些结果仍 PENDING。
4. **同会话内执行限定创建。** 只读事务 ROLLBACK 后，事务内创建 owner/app，均显式 NOLOGIN、无密码、无超级用户/建库/建角色/复制/BYPASSRLS 权限；提交后，单独以 autocommit 创建 `TEMPLATE template0 / ENCODING UTF8 / OWNER 新owner / ALLOW_CONNECTIONS false` 的新库，仅对该新库撤销 PUBLIC 数据库权限并写入合成目标标记。此阶段没有应用密码或临时凭据需要清理。CREATE DATABASE 不能放入事务块，也不能把整批 SQL 当成单个 `-c` 请求或全程 single-transaction；仅有 CREATEDB、却不能 SET ROLE 为新 owner 也不满足指定 owner 的条件。[CREATE DATABASE 17](https://www.postgresql.org/docs/17/sql-createdatabase.html)
5. **复核后退出。** 同一 postgres 会话只读复核新库名称/OID、owner、UTF8、datallowconn=false、权限及两个 NOLOGIN 角色，保存不含秘密的阶段结果并退出 psql。此成功态仅为“独立合成目标已创建且关闭连接”；未连接新库检查空库、未迁移 Workspace V1/V2、未启用 app 登录，不能标为 Runtime READY。之后的开库、应用凭据安全保存和迁移须另有明确执行范围。

名称只读检查与实际创建之间仍可能出现并发冲突；以实际 CREATE 的唯一性检查为最终约束，不用 IF NOT EXISTS 或覆盖旧对象来掩盖冲突。角色事务与 CREATE DATABASE 不具有整体原子性：任一步失败或连接断开，记录确切阶段和已经创建的对象，停止，保留现场；不自动重登、重试、DROP 或回收同名对象。正常路径只需要一次管理员输入，不承诺故障后可以无授权续跑。

本轮只追加报告，并执行 `git diff --check`；未修改生产/测试代码、未进行任何认证尝试、建库、角色修改、Flyway、窗口启动或额外安全审计。现有源码、STATUS、报告未提交修改及八个 B4A6 文件保留；Finance、历史数据库、Profile、备份和 ZIP 未操作；不 commit/push/merge/打包。方案待下一轮明确授权后实施，本轮到此停止。
