# Workspace Post F-next-10 Engineering Cleanup

2026-10-24（Asia/Shanghai）。仅工程清理。Finance F-next-10 已验收以用户本轮明确确认为依据；没有重新运行人工验收。当前仍为 **Development-only / Production GO 未授予**。

## 结果与范围

- 远端分支：清理前 **18**，删除旧分支 **16**；发布清理分支后实际核对为 **3**。HEAD 符号引用不计为分支。
- 本地分支：清理前 **32**，新建清理分支 **1**，通过 `git branch -d` 删除旧分支 **30**，剩余 **3**。
- 八个 B4A6 未追踪文件：全部逐文件私有归档、字节哈希验证、从原位置退役；没有提交原始文件。
- 生产代码、测试/验证脚本、配置、Native Gate、React/Bridge、金融计算、持久化格式及 migrations 均无修改。Git 中仅新增本报告和 README 当前入口。
- 没有修改 main、F-next-10 或其他仓库的分支，没有 force push、rebase、amend、tag、release、SQL、业务启动、重打包或全量测试。

## A. 清理前完整分支盘点与逐项判断

仓库：`qianlixunbai/personal-ai-workspace`。唯一 worktree 为本 Workspace；入场分支 `codex/finance-f-next-10`，HEAD `f09d1bd1f883db8740cef8336a78e9a3a4123270`。tracked/staged 修改均为空，只有下列八个未追踪文件。

先把全部本地 refs、完整远端 `ls-remote --symref`、worktree 和 status 写入仓库外私有归档，然后按约定代理执行 `fetch --prune origin` 成功，未走直连 fallback。远端默认分支为 `main`，并非 F-next-10。

下表覆盖全部 32 个原本地分支及全部 18 个原远端分支；同名本地/远端 tip 完全一致，因此合并列示。“本地”表示开始前没有同名远端分支，本轮也没有创建对应远端。

对每个 tip 均实际执行完整提交遍历、`merge-base tip F-next-10`、`merge-base --is-ancestor` 和 `rev-list F-next-10..tip`。**每一行共同祖先均等于该行 tip，独有提交数均为 0**；不是内容相似或 cherry-pick 推断。完整提交图和逐 ref 计算结果保存在私有归档。

| 分支 | 删除前完整 tip SHA | 开始前位置 | 完整历史提交数 | 分类与实际处理 |
| --- | --- | --- | ---: | --- |
| `codex/desktop-runtime-distribution` | `03295dabd6dcbaf9bd7d95f27c6db0d23bd0607c` | 本地及远端 | 116 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/desktop-setup-simplification` | `7793dc84119e65cb4b3e9452790cfbf0a3f5d9fb` | 本地及远端 | 114 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/f2.3-a2-finance-postgresql-integration` | `bd4638a7053d3f43a925ba8270252f9384e07c34` | 本地 | 104 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b1-unified-postgresql-persistence` | `be409b40fda1a73deff970484fff164a7016c246` | 本地 | 105 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b2-knowledge-postgresql` | `2a4a2b7f77364c13dc2ed3582d792d2dcee008c8` | 本地 | 106 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b3-unified-backup-restore` | `5bf4a09361b449efcf6ccdd6c9154b4e0ee462a7` | 本地 | 107 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b3e-portable-encrypted-backup` | `d4e60ec2304973cb364b07ca9c9aad05bd8c70e9` | 本地 | 108 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b3f-secure-backup-pipeline` | `b35a1ac7d71a5628e1055b6bfd04776d574f85af` | 本地 | 109 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b4a-desktop-postgresql-integration` | `8eab41c2e675052a02e2334cb20d9b23467413d6` | 本地 | 110 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b4a2-trusted-postgresql-configuration` | `8bef760e1e41af0b2a59e10f3bba35c70193a0ba` | 本地 | 111 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b4a3-trusted-runtime-activation` | `70697b3fae9923021d8fd769caec3c86b14150d3` | 本地 | 112 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b4a5-trusted-development-runtime` | `2d161b4b1b95cb021385cc3e17d9f52611874f49` | 本地 | 113 | SAFE_TO_DELETE；已删除本地 |
| `codex/f2.3-b4a6-real-postgresql-e2e` | `2d161b4b1b95cb021385cc3e17d9f52611874f49` | 本地 | 113 | SAFE_TO_DELETE；已删除本地 |
| `codex/finance-f-next-1` | `e53d668b9e3caef4cd9e88a222695b75fe3483c3` | 本地及远端 | 130 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-10` | `f09d1bd1f883db8740cef8336a78e9a3a4123270` | 本地及远端 | 141 | KEEP；保留 |
| `codex/finance-f-next-2` | `fe331f35ed18766c06468ff01b62252d91e68f31` | 本地及远端 | 131 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-2-startup-recovery` | `01609f579ff20a74b446f19eefc4c8f8fd1574ed` | 本地及远端 | 132 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-3` | `1da1ccfb39bc9c506afc4ba544f152ff4b2dc8b7` | 本地及远端 | 133 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-4` | `67d257691c708d5d9cee7a5cc67194a9ce7c438e` | 本地及远端 | 134 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-5` | `5f4ac444173deb91e09e8b995283e1d4c02525b4` | 本地及远端 | 135 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-6` | `4a7ae93cfd510eb7dd92546abf1071c4119cc6a9` | 本地及远端 | 136 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-7` | `0495ce13898e8651696dbb916e9436a7af6b7327` | 本地及远端 | 137 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-8` | `01aa1f59b33b884b7acf002c66b43b31bfc7d0d9` | 本地及远端 | 138 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-f-next-9` | `aac471d8fc6a70697c4c1111658bb615ab5c69e4` | 本地及远端 | 139 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/finance-remaining-capabilities` | `d17261729bd5d74a07b7ee933bc751984292e5a4` | 本地及远端 | 140 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/mmf3-runtime-private-acl-fix` | `9ea35c0d6ae196f218921f1a2e4ecdd3d1799a21` | 本地 | 103 | SAFE_TO_DELETE；已删除本地 |
| `codex/postgresql-desktop-integration` | `24fa3817caeb93430c14fdab091f71528eb1b63b` | 本地及远端 | 129 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/same-user-runtime-compatibility` | `e6a41b323fcc2366db79ab9d0858ccd584767c18` | 本地 | 117 | SAFE_TO_DELETE；已删除本地 |
| `codex/sqlite-long-path-recovery` | `6ac39023bbc453fd0180cf02d30b33b6ad8f8d4b` | 本地及远端 | 122 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/webview2-react-startup-recovery` | `23657e0776565c4bed7c565f2894dec3676bd3ef` | 本地及远端 | 121 | SAFE_TO_DELETE；已删除本地及远端 |
| `codex/windows-native-security-modernization` | `a41e1391c23b276ca70fb6195c1986f101d090a5` | 本地 | 119 | SAFE_TO_DELETE；已删除本地 |
| `main` | `b35a1ac7d71a5628e1055b6bfd04776d574f85af` | 本地及远端 | 109 | KEEP；保留 |

新分支 `codex/workspace-post-fnext10-cleanup` 从指定 F-next-10 SHA 创建，为 **ACTIVE**，受当前 worktree 保护；开始前不存在同名本地/远端分支。没有 REVIEW_REQUIRED 或因独有提交被迫保留的旧分支；所有自动生成的 `refs/codex/*` 和其他非分支引用均未清理。

## B. 删除依据、备份和恢复

保留的三个分支及理由：

| 分支 | 保留原因 |
| --- | --- |
| `main` | 远端默认分支，无条件保护；仍为 `b35a1ac7d71a5628e1055b6bfd04776d574f85af`，未包含 F-next-10，不自动合并。 |
| `codex/finance-f-next-10` | 用户已验收 Finance 里程碑，完整保留 `f09d1bd1f883db8740cef8336a78e9a3a4123270`，后续只读参考。 |
| `codex/workspace-post-fnext10-cleanup` | 当前工作分支，下一阶段 UI 调研候选基线。 |

实际依赖审查：GitHub connector 的仓库元数据和开放 PR 查询，与已认证 REST 查询互相核对；开放 PR 为 0。分页完整的 REST 结果显示 Actions workflows、deployments、environments、rulesets、webhooks 均为 0；18 个远端分支无 protected 标记，默认分支仍单独保护。所有原本地分支的 `.github` 树为空。当前脚本、构建/打包配置未引用任何 `codex/` 开发分支作为实际执行依赖；文档中的阶段来源不算执行依赖。

GitHub connector 不提供所需部署/环境审查能力；本机 `gh` 未登录，因此 REST 审查复用 Git 既有 credential helper，仅在内存中使用凭据。没有要求提供新 secret，没有修改 Git 全局配置或系统代理，也未输出/保存凭据。此处结论覆盖仓库及可见 GitHub 配置，不能推断未知外部系统的私有依赖。

私有归档根目录（`A`）：

`%USERPROFILE%\WorkspacePrivateArchives\personal-ai-workspace\post-fnext10-20261024`

该目录位于 Workspace 仓库外，关闭继承，仅当前 Windows 用户和 SYSTEM 具有权限。只修改新归档目录 ACL，没有修改既有 Profile 或数据目录 ACL。

- Git 对象及 refs 备份：`A/workspace-before-cleanup.bundle`（3,363,936 bytes），`git bundle create --all`。
- SHA-256：`f73b0a92768b085b99cb3452c959dafce936bd4dfaed579d160a4d32c5ee1f25`，另存 `A/SHA256SUMS.txt`。
- 已执行原仓库及独立空 bare 仓库 `git bundle verify`，无前置提交依赖；实际从 bundle fetch 到 `A/restore-check.git`，执行 `git fsck --full --no-reflogs` 成功，并逐项核对 **50 个原本地/远端分支 refs** 的 SHA 完全相符。
- `A/branch-classification-before-delete.json`：原 ref、完整 tip、共同祖先、完整历史规模、独有提交数、分类、删除理由。
- `A/refs-before-fetch.txt`、`A/remote-before-fetch.txt`、`A/commit-history.txt`：prune 前原始引用及完整提交图。
- `A/branch-delete-results.json`：每个实际删除动作及退出结果；远端删除前均再次精确 `ls-remote` 核对 SHA，没有发现并发更新。
- 远端逐名使用普通 `git push origin --delete <name>`，成功后再重新检查 worktree，逐名 `git branch -d <name>`；没有 `-D` 或强制更新。

恢复方法详见私有 `A/RESTORE.md`。例如将已退役分支恢复到一个**事先确认不存在**的新本地 ref：

```powershell
$archive = Join-Path $env:USERPROFILE 'WorkspacePrivateArchives/personal-ai-workspace/post-fnext10-20261024'
Get-FileHash -LiteralPath (Join-Path $archive 'workspace-before-cleanup.bundle') -Algorithm SHA256
git bundle verify (Join-Path $archive 'workspace-before-cleanup.bundle')
git fetch (Join-Path $archive 'workspace-before-cleanup.bundle') 'refs/heads/codex/finance-f-next-9:refs/heads/codex/recovered-finance-f-next-9'
git rev-parse refs/heads/codex/recovered-finance-f-next-9
```

原 tip 仍通过 F-next-10/清理分支可达，bundle 则提供独立的完整恢复来源。恢复远端属于未来单独的普通 push 操作，本轮未执行。**不要上传整个私有归档、bundle、原始报告或 receipt。**

## C. B4A6 八文件用途、依赖和实际处置

以下为真实文件系统与 `git ls-files --others --exclude-standard` 的结果，不按历史摘要猜测。脚本仅静态阅读，没有运行任何旧脚本或 Java helper。

A 类为已废弃工具，B 类为历史验证文档；D 表示含本机路径、连接身份或诊断上下文等不适合直接公开的内容。采用保守私有保存，**未将未发现明文凭据等同于已获公开许可**。

| 原始相对路径 | 类型 / bytes | SHA-256 | 实际用途与分类 |
| --- | --- | --- | --- |
| `B4A6-Offline.cmd` | `.cmd` / 467 | `008316582daf42b3e7a3efba94cce426794215f9513b2bada863ac1dabbcb3c2` | 固定本机 PowerShell 的旧菜单入口；A+D |
| `bootstrap/tests/B4A6PostgresqlPreparation.java` | `.java` / 13,146 | `c69dffc6f800537bcb74b234fde64699c349ac7835c63ee8d153504000b006ed` | 旧 Native Gate、Workspace V1/V2、DPAPI 配置及合成数据 SQL 探针；A+D |
| `docs/engineering/F2.3-B4A6-OFFLINE-EXECUTION.md` | `.md` / 13,927 | `dfa65d0767fb5df0b6220d38e6dbce044814870c763c5b047cc8f1e3120931cc` | 旧离线操作顺序、恢复/停止条件和 PRIVATE_ACE 处置边界；B+D |
| `docs/engineering/F2.3-B4A6-REAL-WINDOWS-POSTGRESQL-E2E-REPORT.md` | `.md` / 13,252 | `4fe51e7b60b827d80f7b548d727be469ae4716ab7476639171861c7dd3abe6ae` | 旧建库/Profile 准备结果、故障分析和未完成验收证据；B+D |
| `scripts/verification/configure-b4a6-postgresql.ps1` | `.ps1` / 14,855 | `8f0d4b47ad299093e671bd0093fc6feb98a8c2be6d00958fdad854ab85744d14` | 依旧 receipt 的 Profile/Compile/Gate/迁移/配置分步编排；A+D |
| `scripts/verification/inspect-b4a6-offline.ps1` | `.ps1` / 6,266 | `e89f387515bcabd926ad7838c688ee169043fb119b6d577221dc8f14d39d0f3a` | 旧宿主、ACL、固定目标、来源及端口的只读前置检查；A+D |
| `scripts/verification/prepare-b4a6-postgresql.ps1` | `.ps1` / 5,764 | `5dce6c056558996db20b806339877a66528f67e61d11e4dd3ae173118f4bd766` | 旧专属合成数据库及角色预留/受控创建；A+D |
| `scripts/verification/start-b4a6-offline.ps1` | `.ps1` / 8,622 | `6d40050409946769d208fee7c1d344db6a891c01f20facd39c1b573f89b1d3a2` | 依据 receipt 推导下一阶段的旧离线交互菜单；A+D |

**以上每个文件均已按同一规则处置**：归档副本为 `A/b4a6/<原始相对路径>`，复制后 SHA-256 与原件逐项一致；删除前再次核对源文件和副本哈希，以精确 `Remove-Item -LiteralPath` 删除原文件，再确认原位置不存在。八项全部完成，无 C 类运行依赖阻碍。未执行 `git clean` 或目录递归删除。

逐项创建/修改时间（UTC）、类型、bytes、哈希、原始路径和归档相对路径保存于 `A/b4a6-manifest.json`。内容隐私审查及实际移除结果分别在 `A/b4a6-content-review.json`、`A/b4a6-retirement-results.json`。

依赖检查与替代路径：

- 旧调用链是 CMD → start → configure/inspect → Java preparation；prepare 是独立旧目标准备步骤。该互相引用的六文件整体保留在归档中，没有只删链中一环。
- 对八个文件名、旧 receipt 及 preparation runtime/classes 名称搜索全部 tracked 内容，仅四份历史工程报告引用，未发现当前 Runtime、WPF/WebView2、React、NativeBootstrap、启动器、测试/CI、构建和打包执行入口依赖；另查本机 `.verification` 下脚本未发现精确文件名调用。
- 实际当前调用链为 Desktop `Program` / `TrustedDevelopmentRuntimeSources` / `DevelopmentRuntimeOwner`，数据库设置为 C# `NativeDatabaseSetup` → 既有 Java `NativeDatabaseSetup`；不调用旧 helper 或 receipt。`build-trusted-development-runtime.ps1`、分发脚本和项目编译定义同样不依赖八文件。没有为退役而改动这些生产入口。
- 分发说明中的“无需旧 B4A6 receipt”和历史报告中的保留记录不构成运行依赖；README 增加当前清理入口，历史原文保持原时点语境。
- 原两份报告的数据库恢复限制、环境身份、失败分析、安全结果及 PARTIAL/BLOCKED/PENDING 均逐字保存在私有副本，未改成 PASS。它们不是当前日常启动的唯一有效步骤。
- 另将三个 `.verification/f2.3-b4a6*` 原始证据文件复制到 `A/b4a6-evidence/` 并验证哈希；清单为 `A/b4a6-evidence-manifest.json`。原 receipt、目标预留 SQL、run/Profile 映射及对应 Profile 现场仍留在原处，未删除数据库/配置。

恢复旧文件时，从 `A/b4a6/` 按清单复制至原路径，仅当原位置不存在时进行，核对 SHA-256 后再单独审查是否允许执行；归档本身不授权运行历史建库或配置工具。

## D. 保护与验证边界

| 项目 | 本轮实际证据与边界 |
| --- | --- |
| F-next-10 与 main | 本地保留原 SHA，删除后远端 `ls-remote --symref` 核对相同；候选分支从 F-next-10 直接继承。 |
| Finance、统一 Shared AI Runtime、Memory/Knowledge/Translate、Native Gate、绑定逻辑 | 原 tracked tree 在八文件退役后仍逐字等于 F-next-10；实际提交 diff 仅本报告及 README。没有生产行为变化。 |
| Finance 冻结来源 | 入场/退役后 `git status` 均干净，HEAD 均为 `c6c87a0627b746e70a45b1c7896f63defd76a810`；仅只读检查。 |
| 原 Finance V1–V21 / Workspace V1–V2 migrations | 未修改、未运行；生产树比较覆盖原 SQL 和 provenance。 |
| 历史开发客户端/包、原 F-next-2 startup recovery、F-next-10 独立包及既有保护清单 | 复用既有 F-next-10 保护清单，去掉本轮授权退役八文件，补入当前 F-next-10 包和三个旧证据文件；本轮建立并复核 17,340 个文件 SHA-256，变化为 0。相对于原历史清单也未发现其他缺失/变化；范围见私有清单，不推断未列出的文件。 |
| Profile、DPAPI、身份绑定 | 清单内受保护文件字节未变；未解密、重绑或重建，未触发 Runtime/业务流程。当前认证运行状态 **NOT VERIFIED**。 |
| PostgreSQL 历史/真实用户/合成数据 | 没有执行 SQL、连接数据库、启动/停止集群、迁移、清空或金融更新；数据库业务内容未在本轮查询比对，**NOT VERIFIED**，不能用包哈希替代数据库验收。 |
| Secrets、Runtime Memory、历史保护证据 | 未修改原文件、未修改/创建凭据；仅对授权旧证据做私有副本。 |
| 备份恢复及八文件归档 | 本轮实际执行 bundle 独立恢复、fsck、50 个 refs 比对及八文件复制/删除前哈希验证，全部通过。 |
| 启动/构建入口 | 静态调用链与引用审查；未执行构建或 UI/Windows 运行验收，**NOT VERIFIED（运行时）**。 |

无新增业务测试或测试/harness 修改。没有运行 Maven、Desktop、Frontend 全量或聚焦业务套件、E2E、Finance Windows 矩阵，也没有新数据库、合成环境或客户端包；本轮证据仅为上述 Git/备份/哈希/静态检查。历史报告的测试结果仅作历史证据。

私有 `A/protected-current-before.json` 和 `A/protection-after.json` 保存本轮保护清单与结果；不将这些含私有路径的原始清单上传 GitHub。

## E. 开发基线、交付与下一阶段

- 当前候选分支：`codex/workspace-post-fnext10-cleanup`。
- 继承起点：`f09d1bd1f883db8740cef8336a78e9a3a4123270`，没有重打已验收包；原包内构建来源与 manifest 保持原值。
- 工程清理内容提交及本次补记前实际审计 HEAD：`15051fc6351063ec271114591339c3545c99d03e`，已正常 push，随后精确核对远端同名分支 SHA 一致。该提交相对 F-next-10 只含 README 和本报告；本次另一个提交仅补充最终交付记录，不改写前一提交。
- 已执行实际/staged diff 审查和 `git diff --check`；首次 push 后 `git status --porcelain=v1 --untracked-files=all` 为空，本地/远端均只有上述三个保留分支，默认分支仍为 main，F-next-10 SHA 不变。没有独有历史提交丢失或未清理的八文件。
- 最终报告补记提交的完整 SHA 以此分支 `git rev-parse HEAD`、GitHub 本文件所属最新提交及最终交付回复为准（文档不能预先嵌入其自身提交哈希）；精确值及最终 `ls-remote`/status 另写在私有 `A/final-delivery.json`。本轮没有清理阻碍。

下一阶段为 **Personal AI Workspace — GitHub Open-source UI Research**，推荐从本清理分支新建后续工作。首先阅读本报告、[FINANCE-REMAINING-CAPABILITIES.md](FINANCE-REMAINING-CAPABILITIES.md)、[FINANCE-F-NEXT-10-REPORT.md](FINANCE-F-NEXT-10-REPORT.md) 和现有架构资料，然后另行开展开源 UI 调研。本轮不启动该调研、F-next-11、UI 重设计或金融写入迁移。

Categories、Import Confirm、金融写入与统一 AI Finance Tools 继续保留在 Backlog。Development-only 边界保持。
