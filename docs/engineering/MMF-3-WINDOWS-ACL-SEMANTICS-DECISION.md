# MMF-3 — Windows ACL & Handle Semantics Decision Gate

2026-10-09（Asia/Shanghai）。仅定向安全语义核验与架构建议；没有生产实施授权。

**决定性差异：现有 metadata-only 目录 handle 在本机 NTFS 上不能 pin 路径；本轮在持有 Helper 原句柄时实际完成非空目录 rename 和原路径重新绑定。** 加 `FILE_LIST_DIRECTORY` 的独立实验句柄阻止了 DELETE open / rename，但仅证明持有期间的这项约束。LocalAppData capability authority 仍真实存在，Protected run 根没有继承该 ACE，也不能消除父目录的删除子项权。

**推荐：保持现有 B1 / LocalAppData STOP；另案审查“仅未来 MMF-3 测试状态根使用安全 current-token profile 私有新根”。** 不迁移任何已有状态或凭据，不实施长期 handle/IPC。

Native Bootstrap Development Source Approval、Trusted JNA Loader 方案 A 方向保持 GO。Production Integration / MMF-3 Execution：**STOP**。Model Management Overall：**NOT CLOSED**。

本轮证据根：[独立 ignored evidence](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/)。

## 1. Git Reality 与保护

| 项目 | 本轮实际事实 |
| --- | --- |
| Workspace branch / HEAD / fresh origin/main | `codex/mmf3-runtime-private-acl-fix` / `8dc66d81b78c3efac70feed94aec25ea71dd21a5` / 同 HEAD |
| Browser branch / HEAD / fresh origin/main | `main` / `267db794691d1a9ab1712b30b07cbeb57241808b` / 同 HEAD；clean |
| fetch | 两仓库指定命令级 `127.0.0.1:7890` proxy fetch 均成功，无 fallback / global 配置修改 |
| index | 入场无 staged diff；交付 entries 与入场一致 |
| 入场工作树 | 八个 tracked 候选修改、Java ACL 新类/测试、七个 Bootstrap 文件、五份既有报告；全部保留 |
| 本轮新增 | 本文及独立 ignored 临时探针/证据；生产代码、长期测试均零修改 |
| 交付 | Workspace 保留原 dirty 工作树并新增本文；未 stage / commit / push / merge / reset / clean / rebase / amend |

检查磁盘祖先及仓库 AGENTS，未发现额外 AGENTS.md；读取 current architecture、STATUS 当前边界、ADR-001/002/015、Integration Gate 与 owning code。遵循用户本轮更窄授权。

| 保留身份 | 本轮重新计算 SHA-256 |
| --- | --- |
| Java ACL `source-review.patch` | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06` |
| .NET Bootstrap `source-review.patch` | `279bc299f1bbe72723e649fa1ae34bb778457cb0962f0f194754a3f26265a549` |

两个 patch 的 `git apply --reverse --check` 实际成功，没有应用或撤销。探针使用的已有 Helper payload hashes 与原 build-identity 一致，没有 build/publish。保留基线涵盖全部 tracked / nonignored untracked 文件、指定既有 MMF 调查证据及旧 GUI harness 顶层记录；没有遍历私人浏览器状态或读取 token/数据库内容。

证据：[逐文件基线](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/preserved-baseline.json)、[入场状态](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/status-baseline.txt)、[交付检查](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/final-check.json)。原失败 run 只读目录元数据，无修复、重启或清理。

## 2. 已保存合成失败证据复核

[旧 synthetic-fixture.json](../../.verification/mmf3-bootstrap-integration-a785e08709aa4bedbd0ca56c64fe3625/synthetic-fixture.json) 与旧 inspect.ps1 相符：parent 无 DELETE_CHILD 时 DELETE open=5；加入 DELETE_CHILD 后=0；Helper metadata handle 持有时仍=0；释放后非空 rename=0，原对象 ID / descriptor / marker 保留。

旧 harness 因预期 sharing error 32 未出现而失败，不能记为 PASS。它没有执行持句柄 rename。本轮补充这个缺口，没有重新运行旧 fixture 或覆盖原失败证据。

## 3. CreateFileW / share / delete / rename 的准确区别

| 事件 | 含义与本轮可用证据 |
| --- | --- |
| DELETE open 被允许 | `CreateFileW` 返回有效 handle，说明这次 open 没有被访问或共享检查拒绝；不等于对象已删除 |
| DELETE 权实际授予 | `NtQueryInformationFile(FileAccessInformation=8)` 读回 `0x00110080`，其中含 `DELETE=0x10000`；额外 SYNCHRONIZE/READ_ATTRIBUTES 不是请求了文件数据读权 |
| 真正删除 | 需设置 delete disposition / 调用删除 API 并满足对象状态；非空目录 disposition=145，空目录=0，最后关闭后按路径 open=2 |
| rename / 移动路径 | 独立的 namespace 操作；本轮同 parent / 同 NTFS volume rename=0，未做跨卷 copy/delete |
| 原路径解析目标改变 | rename 后新建原路径目录；重新按原路径打开得到不同 file ID |
| 已打开对象身份改变 | 本轮没有发生；旧 handle 的 volume/file ID 始终相同，最终路径指向 renamed 对象 |

`FILE_SHARE_DELETE=4` 是共享许可，`DELETE=0x10000` 是访问权限；互不授予。Parent `FILE_DELETE_CHILD=0x40` 是另一授权来源，不是 child 的 DELETE ACE。Rename 还要求目的 parent 的创建入口权限，成功打开 DELETE handle 不能保证任意目的路径 rename 成功。[CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)、[rename 合同](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/ns-ntifs-_file_rename_information)。

`FILE_FLAG_BACKUP_SEMANTICS=0x02000000` 允许目录 open，并不自动授予 DELETE 或 pin；本轮未启用 backup/restore privilege。`FILE_FLAG_OPEN_REPARSE_POINT=0x00200000` 控制最终分量的链接打开语义，不能冻结全部中间路径、禁止以后修改 reparse，或替代祖先验证。

Microsoft 文档区分 data access 与 attributes/EA access，共享检查由 filesystem 的 open 状态决定。[CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)、[IoCheckShareAccess](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/wdm/nf-wdm-iocheckshareaccess)、[FILE_OBJECT](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/wdm/ns-wdm-_file_object)。本轮实际结果证明：这里仅有 READ_CONTROL / READ_ATTRIBUTES 的目录 open 没有产生预期的 no-delete-share 排他性；加入目录数据权限后才出现 error 32。没有读取 kernel share counters，不能把这一机制解释升级为全部 Windows filesystem / filter driver 的实现证明。

## 4. 实际 pin 能力与失败范围

仅一个全新随机、non-elevated current-token profile fixture；根原子创建为 Protected/current-owner-only，先验证 profile 祖先。局部 current-SID DELETE Deny / Allow 是合成条件，不是产品 admission descriptor。不存在其他主体 token。

| 定向条件 | 实际结果 |
| --- | --- |
| 普通文件 READ_CONTROL\|READ_ATTRIBUTES\|READ_DATA；share READ=1 | WRITE_DATA open=32，DELETE open=32，MoveFile rename=32；held / path ID 不变 |
| protected child DELETE Deny；parent FullControl 去掉 DELETE_CHILD | DELETE open=5，rename=5 |
| 同一非空 child；parent 加回 DELETE_CHILD；持有原 Helper directory Open | DELETE granted，非空 disposition=145；**同 handle rename=0** |
| 空 child；Java 参数等价的 metadata directory open，share=3 | DELETE open=0；**MoveFile rename=0**；held / moved-path ID 相同 |
| 独立 directory 加 `FILE_LIST_DIRECTORY=1`，share=3 | DELETE open=32；MoveFile rename=32；held / original-path ID 不变 |
| 独立空 child；持有 metadata handle；另取得 DELETE handle | disposition=0；关闭 DELETE handle后原路径 open=5；最后 metadata handle关闭后 open=2、目录不存在 |

空与非空差异阻止了非空目录的 disposition，**没有阻止非空 rename**。拒绝删除共享的目录句柄不能因此被宣布阻止整个 subtree 的子项写入、ACL 改动、reparse 或其他 namespace 操作；pin parent 本身也不等于 pin 每个 child。

本轮成功记录了失败语义，不代表现有 Helper pin gate 通过。最小修订候选是目录 open 加 `FILE_LIST_DIRECTORY`，保持 share READ|WRITE、禁止 share DELETE；需另案审查权限兼容性及覆盖每个必要对象的保持范围。不能改为申请 DELETE 来“修复”pin，也不能只改 share=0 而仍保留 metadata-only access 后宣称安全。

## 5. Helper 与 Java ACL 适用性

| Owning code | 实际参数 / 生命周期 | 结论 |
| --- | --- | --- |
| Helper `Win32.Open(directory=true)` | desired `0x00020080`；share=3；OPEN_EXISTING；flags=`0x02200000` | 本轮通过原已核验 assembly 的真实方法持有，rename失败防护已被反证 |
| Helper `Win32.Open(file)` | desired `0x00020081`；通常 share=1；发布重开例外 share=7 | 普通文件定向 sharing 约束成立；不覆盖所有 mapping / 既有危险 handle / 发布例外 |
| Java `NativeAcl.open` | security=true 时 `0x00020080`，false 时 `0x80`；目录及文件 share=3、同 flags | 目录实际 Win32 参数等价实验可适用；没有运行 Java/JNA 候选，不宣称其完整流程通过。文件也没有请求 READ_DATA，不能借用 Helper 文件 pin 结论 |
| Java `createFile` metadata handle | desired READ_CONTROL\|READ_ATTRIBUTES、share=3 | 私有原子创建与有效共享 pin 是不同合同；文件共享能力未在本轮另外动态验证 |

Helper Verify 返回前已释放目录/文件句柄；Java Pins 只覆盖一次 operate 调用，并不覆盖之后的 Java/SQLite I/O。即使未来加一个 access bit，Helper 退出至 JVM load、Java verify 返回至再次 path open 的窗口仍需稳定 ACL 链，不会自动成为跨进程持续保护。

## 6. 文件身份与路径替换证明

本轮统一使用 `FILE_ID_INFO` 的 64-bit volume serial + 原始 128-bit file-ID bytes；旧报告用整数字段展示，比较时不能直接把不同格式字符串当成 ID 变化。

| 非空 Helper 实验观察点 | volume : file ID |
| --- | --- |
| rename 前 / 旧 handle rename 后 / moved-path 重新打开 | `aa2e58ed2e58b453:b1d6070000000f000000000000000000` |
| rename 后新建的原路径 | `aa2e58ed2e58b453:cdd60700000016000000000000000000` |

旧 handle final path 不再匹配原路径，匹配 moved 路径；descriptor hash 前后相同，合成 marker directory 仍存在。这直接证明 **保留已打开对象身份不等于保留原路径绑定**。LIST_DIRECTORY pin 的拒绝操作前后 ID 及原路径 ID 相同。空目录 delete-pending 时旧 handle ID 仍可读，最终关闭后路径不存在；不能称为“handle被重新指向替换对象”。

证据：[完整操作、权限、ID 与 descriptor](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/synthetic-results.json)。

## 7. LocalAppData capability authority

只读 `GetSecurityInfo` 同对象 owner / DACL / control、ACE SID/rights，以及 volume/file ID / final path / attributes。当前 token 不含下述 capability；没有为它取得 token或运行 AppContainer。

SID：`S-1-15-3-2968813833-811790644-2202111208-3784096404-1081847329-2708967783-1438471679`。

| 对象角色 | capability Allow ACE 实际作用 / control |
| --- | --- |
| AppData | 显式 `0x001f01ff`，flags=None，直接作用本对象；另一个 `0x10000000`（GENERIC_ALL）带 OI|CI|IO，仅用于继承；control=`0x8404`，非 Protected |
| LocalAppData | `0x001f01ff` / Inherited 对本对象有效；另有 OI|CI|IO|Inherited GENERIC_ALL；control=`0x8404` |
| B1 parent `PersonalAiWorkspace-Mmf3` | 同 capability FullControl / Inherited 及继承模板，直接 child run 的父对象仍有 authority；control=`0x8404` |
| 旧 B1 run 根 | current owner，control=`0x9404` / Protected，仅 current OI|CI FullControl；**没有 capability ACE** |
| 旧 run 的 credentials 目录 | current owner，control=`0x8004`，非 Protected，仅 current inherited FullControl；没有 capability ACE；这是原失败现场的只读观察，未修复 |

因此不能说“capability直接继承到了所有 protected目标”。它在目标父目录生效，已足以产生独立 parent→child 风险。B1根阻断的继承不能反向移除父目录 ACE。

WRITE_DAC / WRITE_OWNER 只授权修改**该 ancestor**的 DACL / owner，不直接赋予修改 protected child descriptor 的权利。它们可以改变 ancestor authority，建立删除子项或替换祖先路径的条件。DELETE_CHILD 作用于直接 child；更高 ancestor 的 DELETE_CHILD 作用于中间目录，不能笼统说它直接授予所有后代内容访问。Protected/owner-only child 可排除该对象上未获授权 SID 的直接读写及 ACE 继承，不能排除 parent DELETE_CHILD、ancestor namespace 替换、既有危险 handles、同账号或管理员行为。[文件安全与继承](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights)、[Protected control](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptor-control)、[DELETE 授权](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-deletefilew)。

## 8. 真实路径安全性与 generic mapping

两候选 ancestor mask=`0x500d0040`，是 foreign Allow authority 的保守检查；跳过普通 Deny / inherit-only，并非某个外部 token 的完整 AccessCheck。

GENERIC_WRITE 映射为 `0x00120116`（目录 add-file/add-subdir、EA/attributes、READ_CONTROL、SYNCHRONIZE），不含 DELETE / DELETE_CHILD / WRITE_DAC / WRITE_OWNER。仅允许在已安全且存在的命名空间创建无关 sibling 时，统一拒绝 GENERIC_WRITE 可能过宽；仍须独立评估属性/reparse/新建窗口。GENERIC_ALL 映射 `0x001f01ff`，危险 authority 保留。ancestor 自身 DELETE 不等于其 child DELETE_CHILD，volume root 也不能用普通目录 rename推理。[generic mapping](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights)。

**本机 blocker不因缩窄 generic mask 消失：** capability 的实际 FullControl ACE 已经是具体 rights，命中 `0x000d0040`，包含 DELETE_CHILD、WRITE_DAC、WRITE_OWNER。未新增 trusted SID、删除祖先检查或修改 B1 Protected gate。

证据分类：

- **实际观察：**上述 ACE、control、owner、ID；AppData / LocalAppData / B1 parent 的实际 Helper ancestor predicate 为 ANCESTOR_REPLACEMENT；当前 token 不含 capability；八对象的描述符及 ID 入场/交付一致。
- **Windows API 合同：**generic mapping、继承保护的范围、parent delete authority及 rename / disposition要求。
- **合成验证：**当前 token 的 parent DELETE_CHILD 可克服 child DELETE Deny，并在原 metadata handle持有时 rename；不等于对真实 AppData实施攻击。
- **未验证推论：**哪一应用持有该 capability、其 user/package/restricted SID / mandatory policy 的完整有效权限、是否能在真实链完成攻击。不证明任意用户都能拿到 capability，也不证明该 authority 已被 token其他限制排除。[AppContainer token / capability](https://learn.microsoft.com/en-us/windows/win32/secauthz/implementing-an-appcontainer)。

另有真实身份拒绝：现有 Helper 对 B1 parent / run / credentials返回 `PATH_IDENTITY`。本轮仅用独立 metadata open读回该对象信息，保留候选拒绝：三者 final path与输入不相等，最终 leaf符合角色，tag=0 / 无最终 reparse标志；不能据此推断中间解析链或 filesystem/filter行为已安全。具体 canonical path差异根因未继续扩展调查。该结果不是 ACL 放行，亦未修改 candidate path比较。

当前账号有另一可验证准备路径：系统盘根 / Users / current-token profile ancestor predicate ACCEPT、final path一致、NTFS；profile下全新原子 Protected私有 fixture创建成功，无既有 ACL修改。profile另一个 capability ACE仅 `0x00100020`（traverse/synchronize），无本次 replacement mask命中。不能推广到所有账号或将整个 profile当应用私有根。

**保留本机现有 B1 / LocalAppData 的安全运行条件没有被证明，STOP。** 即使另行解决 PATH_IDENTITY及旧 credentials Protection，capability authority仍独立阻塞；反之也一样。

## 9. 三条路线：工作量与风险

| 路线 | 必要前提与对象 / 生命周期 | I/O影响与成本 | 裁定建议 |
| --- | --- | --- | --- |
| 1 保留 LocalAppData | 全链证明外部 replacement/ACL authority无法生效，或在每次 namespace使用期间连续可靠保护 AppData→Local→应用parent→run→required children及相关文件 | 仅增加 LIST_DIRECTORY只解决目录本身的共享约束；不能修改危险真实 ACL或信任capability来放行。需处理按路径Java I/O、token读写、原子发布、SQLite sidecar、Desktop导入、WebView2 / Chrome多进程访问与重开；成本高、跨生命周期证明未成形 | 本机 STOP；其他机器只有全链独立通过且无既有危险 handles / 未覆盖更新竞态时才有条件讨论，无本轮生产GO |
| 2 仅未来测试根放 current-token profile 私有新路径 | 每次从有效token profile派生固定命名新根；全祖先authority/owner/identity/reparse验证；原子Protected/current-only创建；失败STOP无LocalAppData回退 | 新Debug测试路径合同、run identity、启动harness及验收调整；普通Java文件I/O / SQLite / Browser API可继续沿固定新路径使用，避免unsafe祖先的持续跨进程绑定。成本中低；持久格式/生产数据不改，但旧状态/sidecar问题仍独立 | **推荐独立提案**；本轮profile fixture只证明准备可行，不证明完整MMF-3启动 |
| 3 长期handle或IPC | 必須覆盖全部可换祖先及必要文件、Runtime / Helper / Desktop / WebView2 / Chrome生命周期；谁持有、handoff、故障退出、重启复验均需明确 | Helper退出不能留下pin；Java本地长期handle也涉及各API重开和重新解析。IPC再新增端点认证、handle传递与持有者崩溃边界；不能以heartbeat/PASS文件维持授权。成本最高，仍非ACL/reparse万能防护 | 路线2尚有可行准备证据，未证明前两条均不足；**不选、不实现** |

路线1若靠handle弥补unsafe祖先，其保持时间必须从第一次命名空间验证一直覆盖最后一次相关资源使用；仅Bootstrap→load短窗口不覆盖Runtime状态全生命周期。更新、repair、崩溃重启及已有危险handle均需另立合同。

路线3失去任一必要pin、持有者崩溃或identity无法复验时应停止依赖访问；没有可靠的方法承诺崩溃后继续沿unsafe路径运行。它不能提高同SID隔离能力，也不能把拥有WRITE_DAC的主体自动排除。

## 10. 推荐最小路线与测试身份影响

将**路线2的未来独立Debug测试变体**交给 Guard；旧B1编译入口/参数/LocalAppData派生规则/Protected gate继续原样。新变体固定从effective-token profile派生，如 `<token-profile>/.personal-ai-workspace-mmf3-tests/<new-run-id>`，不接受调用方任意根、环境覆写或自动回退。此为新路径与admission合同，必须重审，不是配置小改动。

| 合同 | 后续提案应采用的边界 |
| --- | --- |
| 固定run-id | 仍仅接受32位lowercase hex；每次使用全新ID，两个模式互斥，旧run不复制/复用；一个run的root在Desktop/Runtime/Chrome一致派生 |
| Desktop Credential target | 继续 `PersonalAiWorkspace/Desktop/MMF3/{RunId}/Runtime/127.0.0.1:18765`；使用新ID，无旧credential读取/迁移；明确新模式不会与旧run同ID启动 |
| Desktop导入 | 新变体仅允许其新根 `credentials/client-token`，保持native picker / private file约束；正式target与8765不变 |
| WebView2 | 新根 `webview2-profile`，现有实际UDF相等检查继续适用；新profile不复制cookies/storage，需重新验收多进程目录创建权限 |
| Chrome | 独立新 `chrome-profile`及测试extension副本；未来显式user-data-dir，不读取个人Chrome数据；extension ID、pairing/storage重新验收，Browser生产源码预计无需改 |
| Runtime | 仍固定loopback:18765；harness明确token-file/model-state/data/log目录；不复用原Runtime，不调用正式launcher，不改生产默认配置；未来接trusted native gate另审 |

只准备未来空测试状态；不迁移生产数据/凭据/旧run。LocalClientToken IOException、SQLite WAL/SHM/journal权限与历史兼容仍需独立解决，迁移测试根不能关闭它们。

## 11. 需要修改的准确文件（本轮与未来分开）

**本轮：**仅 `docs/engineering/MMF-3-WINDOWS-ACL-SEMANTICS-DECISION.md`；ignored `probe.ps1`及其记录不进入长期测试suite。

**未来获独立授权后最小profile-test候选范围：**

| 准确文件 | 必要工作；本轮均未修改 |
| --- | --- |
| `desktop/Directory.Build.props` | 独立Debug-only profile-test opt-in、Release禁止、与旧B1变体互斥；保留旧B1 build行为 |
| `desktop/src/PersonalAiWorkspace.Desktop/Mmf3AcceptanceLaunch.cs` | 新变体仅固定profile根派生/admission；旧变体仍原LocalAppData路径与参数。有效token profile权威不能仅依赖USERPROFILE字符串 |
| `desktop/tests/PersonalAiWorkspace.Desktop.Tests/Mmf3AcceptanceTests.cs` | 复用已有集成测试承载新根身份/错误root拒绝/旧变体保持，避免新增重复层测试 |
| 新 `scripts/verification/mmf3-profile-state.ps1` | 独立、显式、默认STOP的未来test harness；prepare/runtime/browser参数共享固定run派生，先审源码再允许运行；不覆盖既有ignored start/prepare/finish或失败current-run记录 |

未来测试变体仍由现有 `Program.cs` 调用 Parse，在构造任何client/mutex/WebView前完成admission，预计无需改其dispatcher；如果实际实现要求扩大范围，先交Guard裁定。不新增通用配置/权限框架。

**另案pin修订的最小owner：**`bootstrap/PersonalAiWorkspace.NativeBootstrap/Win32.cs`及 `src/main/java/io/github/qianlixunbai/workspace/security/WindowsPrivateAcl.java`，目录 desired access 增加 LIST_DIRECTORY；Java文件handle要求需单独确定。优先复用 `bootstrap/tests/verify-trusted-native.ps1` / `WindowsPrivateAclTest.java` 中已有高价值流，只补重要不同合同。这是未来新patch，不能覆盖本轮保留的两个身份；暂不授权。

## 12. 不需要修改的文件 / 身份

本轮 Native Bootstrap全部源码、WindowsPrivateAcl类/测试、`PersonalAiWorkspaceApplication.java`、Java main、四个Windows启动/打包scripts、B1 Desktop全部源码、Runtime security/config、pom与ADR-015均原样。

路线2本身不需要修改生产 `LocalClientToken`、Memory/Knowledge/ModelState、RuntimeClient HTTP合同、CredentialStore、SingleInstance、WebView host、Browser生产源码、持久schema、正式launcher或package。现有Desktop资源使用已从acceptance.Root/Profile/RunId派生，不能为新根重构它们。

JNA保持 `5.17.0`；DLL SHA-256保持 `5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023`，未重新加载或下载该DLL。方案A完整生产入口整合仍属于前一Gate另案，不混入测试根变更。

## 13. 实际执行的定向实验 / 验证

本轮实际执行两仓库fetch、HEAD/index/worktree/AGENTS核验；两patch hash与reverse-check；已有Helper payload hash核对；八个真实目录owner/DACL/control/SID/rights/ID只读前后复核；一个新NTFS私有fixture中的read-data共享、parent权限、持metadata-handle rename、原路径替换、LIST_DIRECTORY候选拒绝、空/非空 disposition流；保存证据校验及Git diff检查。

初次只读B1 metadata读取遇到Helper PATH_IDENTITY，在创建fixture前停止；后续观察open仅记录metadata并保持该拒绝，未绕过产品gate。一次临时诊断因缺少PSScriptRoot失败，无有效结果或fixture。最终合成fixture只执行一次，不重跑已绿流；没有增加长期测试。脚本exit 0表示记录完成，具体安全判断依据保存返回值/ID与独立结果断言，不把七段观测计成七个生产验收PASS。

证据：[本轮临时探针](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/probe.ps1)、[synthetic results](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/synthetic-results.json)、[真实metadata](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/real-acl-before.json)、[真实路径原样](../../.verification/mmf3-acl-semantics-c18269b237d7450d98b6ab27ccc96104/scope-check.json)。没有正式Helper CLI Provision/Verify重跑；只调用已核验开发assembly的窄方法。

## 14. 未执行与剩余不确定性

未运行Java/JNA候选、Spring context、完整Runtime、Windows Assistant、Chrome、模型操作、Ollama管理、Maven/.NET production build、package、Java/Desktop/Frontend全suite、完整浏览器/IME/stress矩阵。旧focused/bootstrap/JVM/B1证据仅为历史，不算本轮执行。

未获取外部/capability token、操作AppContainer、修改真实系统/AppData ACL、对既有目录rename/delete、读取私人内容、迁移或修复旧状态。合成fixture保留，无清理。

尚未知：真实B1 canonical path差异根因、capability完整有效token authority、其他filesystem/filter组合的sharing、以前授予的危险handle/mapping、LIST_DIRECTORY新增权限在全部目标机器的可得性、新测试变体的完整多进程运行证据。原IOException阶段/cause、SQLite sidecar、历史兼容仍独立未知。本轮已有决定性证据，停止扩展扫描/实验。

## 15. Architecture Guard 待批准事项

1. 接受本机现有metadata-only directory pin已被反证；另案是否批准小范围LIST_DIRECTORY access修订及其严格保持范围，不宣称解决ACL/reparse或退出后窗口。
2. 保持本机LocalAppData/B1 STOP；不新增trusted capability、不删ancestor检查、不放宽Protected gate。
3. 是否批准路线2独立Debug测试变体的固定profile根、全新run-ID及上述准确文件范围；旧B1/生产路径不变，新合同必须独立源码审查。
4. profile来源、原子私有创建、完整祖先身份/authority、失败无回退、Desktop credential/WebView2/Chrome/Runtime派生一致性与最小集成验证的合同。
5. 来源可信的方案A生产集成、旧IOException/sidecar/兼容性各自另案；本轮不将其批准与路径提案合并，不启用路线3。

## 16. GO / STOP

| Gate | 本轮建议 |
| --- | --- |
| Native Bootstrap Development Source Approval | 保持 GO；未修改，不升级production trust |
| Trusted JNA Loader 方案 A 方向 | 保持 GO；生产来源/入口/生命周期仍待独立批准 |
| 现有Helper metadata目录pin | **DISPROVEN on this NTFS fixture / STOP** |
| LIST_DIRECTORY pin修订 | 合成持有期间DELETE-open/rename拒绝已验证；生产修订 **NOT AUTHORIZED** |
| LocalAppData capability compatibility / 真实B1路径 | **BLOCKED / STOP** |
| 未来profile测试根 | 准备可行证据；仅推荐独立提案，实施未授权 |
| Production Integration / MMF-3 Execution | **STOP** |
| Model Management Overall | **NOT CLOSED** |

只交付本文和独立证据，停止本轮工作。没有提交或发布任何既有候选。
