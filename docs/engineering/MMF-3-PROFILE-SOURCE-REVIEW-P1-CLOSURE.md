# MMF-3 Profile Source Review — P1 Closure

2026-10-09（Asia/Shanghai）。**Source Review Candidate：GO；Architecture Guard 最终 Source Approval：PENDING。**
**MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**

## 修正与范围

本轮仅修改 `scripts/verification/mmf3-profile-state.ps1`：读取合同源码前扫描当前 AppDomain 的已加载程序集；任何程序集含同名 `PersonalAiWorkspace.Desktop.Mmf3ProfileState` 即 fail closed，包括其他 runspace、Desktop DLL 或本脚本先前加载的正确版本。拒绝发生在合同调用和私有目录操作之前，不尝试认证或复用缓存。

无同名类型时，继续逐字提取 owning C# 的标记合同；直接使用本次 `Add-Type -PassThru` 返回的类型，删除 `-as [type]` 缓存复用分支。每次调用使用全新独立进程：

```powershell
pwsh -NoProfile -File scripts/verification/mmf3-profile-state.ps1 -Mode Inspect -RunId <id>
pwsh -NoProfile -File scripts/verification/mmf3-profile-state.ps1 -Mode Verify -RunId <id>
pwsh -NoProfile -File scripts/verification/mmf3-profile-state.ps1 -Mode Prepare -RunId <fresh-id> -OptIn
```

Desktop C#、MSBuild、owning tests、Java、Native Bootstrap、正式 Runtime、Browser 和 ADR-015 均未修改。未新增依赖、缓存管理器或通用动态编译框架。交付新增本文；聚焦 harness、日志及构建产物仅写入本轮全新 ignored [evidence 目录](../../.verification/mmf3-profile-p1-closure-15bb0ad051c248959b54b4ef9e1b2838/)。

## 实际执行的聚焦验证

| 检查 | 本轮实际结果 |
| --- | --- |
| 全新独立 pwsh Prepare + 明确 `-OptIn` | PASS；创建全新空 fixture `ea0b0f5d6c46459491369ce4aaa0a71e`，合同完成 owner / Protected owner-only DACL 验证 |
| 分别在全新 pwsh 执行 Inspect / Verify | PASS；均返回同一正确私有根 |
| Prepare 无 `-OptIn` | 预期 exit 1；未创建拒绝 ID 的根 |
| 未知同名 stub 已加载 | Inspect / Verify / Prepare 均拒绝；stub 调用次数为 0 |
| 其他 runspace 已加载同名 stub | 三模式均拒绝；stub 调用次数为 0 |
| 同进程先成功 Inspect，再调用脚本 | 三模式均拒绝；正确旧类型也不复用 |
| 只读及拒绝后的私有状态 | PASS；固定 Profile parent 与本轮九个目录的属性、creation/last-write 时间、SDDL、目录 entry 清单前后相同；拒绝 ID 根不存在 |
| 单次 Profile Debug 构建 | PASS；0 warning / 0 error；独立 artifacts 路径，`FrontendSkipBuild=true`，只执行既有 frontend 资产校验 |
| 新构建实际 Desktop DLL | PASS；反射仅调用 `Mmf3ProfileState.Root/Verify`，验证同一 fixture；随后脚本三模式拒绝 DLL 内已加载的同名类型，私有状态未变化 |

脚本验证命令为本轮 `verify-closure.ps1`；构建为 `dotnet build desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj -c Debug -p:Mmf3ProfileAcceptanceBuild=true -p:Mmf3AcceptanceBuild=false -p:FrontendSkipBuild=true --artifacts-path <本轮 evidence>/build`；产物检查使用该 harness 的 `-DesktopOnly -DesktopDll <新 DLL>`。

证据：[脚本结果](../../.verification/mmf3-profile-p1-closure-15bb0ad051c248959b54b4ef9e1b2838/script-results.json)、[构建日志](../../.verification/mmf3-profile-p1-closure-15bb0ad051c248959b54b4ef9e1b2838/profile-build.log)、[实际 DLL 验证](../../.verification/mmf3-profile-p1-closure-15bb0ad051c248959b54b4ef9e1b2838/desktop-result.json)、[源码/补丁检查](../../.verification/mmf3-profile-p1-closure-15bb0ad051c248959b54b4ef9e1b2838/source-review-check.json)。新 DLL SHA-256 为 `9530c9af52298fc4a4aa4a119efaef4a3ec935696431cac0be0b627afb094ef9`；三个 Desktop owning 文件 SHA 与原候选逐字节相同。上述为有限聚焦证据，不计为 GUI/Runtime 验收。

## 原失败与修正后行为

在独立 pwsh 预加载同名 stub，运行入场脚本的证据副本（仅调整副本读取 owning source 的绝对路径）：Inspect / Verify / Prepare 都静默成功，返回 `UNVERIFIED-CONTRACT`，未知合同被调用 3 次。stub 仅返回 sentinel，没有私有写入；未访问原失败 run。

修正后相同预加载条件均在合同调用前抛出 `MMF3 Profile contract already loaded; use a fresh pwsh -NoProfile -File process. Nothing created or modified.`。因此已加载旧合同不能绕过当前源码 ACL 规则；不能证明身份时直接拒绝。

未重跑四种完整构建身份矩阵、Desktop/Java/Frontend 全套或历史 ACL/Bootstrap 测试；其既有 PASS 仍属于历史证据。本轮无 Runtime/GUI/Chrome/模型操作，无 Credential Manager 或 token 文件操作，无原失败 run 访问。

## 更新的四文件完整补丁与保留身份

[更新的完整 source-review.patch](../../.verification/mmf3-profile-p1-closure-15bb0ad051c248959b54b4ef9e1b2838/source-review.patch) 基于 HEAD `8dc66d81b78c3efac70feed94aec25ea71dd21a5`，严格包含以下四文件；前三项保留原候选内容，仅第四项修正：

1. `desktop/Directory.Build.props`
2. `desktop/src/PersonalAiWorkspace.Desktop/Mmf3AcceptanceLaunch.cs`
3. `desktop/tests/PersonalAiWorkspace.Desktop.Tests/Mmf3AcceptanceTests.cs`
4. `scripts/verification/mmf3-profile-state.ps1`

新补丁 SHA-256：`2e0c54e9fde79e6e4e17164c424f7a850d25b74e2ba6b793833abb5a82384618`。

原 Profile 补丁 `f452d1714a20bb28005fd1e59b5240ad1ed818f4fab41427f9375731eb2bac0a` 原样保留，由新补丁替代送审。两个旧候选实际复算身份不变：

| 保留补丁 | SHA-256 |
| --- | --- |
| Java ACL | `8073f5ad612dee751e01daee897d76f9c6879d523ff4f6393eaa9cbe7ca64e06` |
| Native Bootstrap | `279bc299f1bbe72723e649fa1ae34bb778457cb0962f0f194754a3f26265a549` |

更新补丁 `git apply --reverse --check --whitespace=error-all`、两个旧补丁 reverse check、`git diff --check` 均通过；仅检查，未应用/撤销补丁。入场 tracked/nonignored untracked 文件逐 SHA 保留检查仅发现准备脚本变更；index 与入场一致。

Branch：`codex/mmf3-runtime-private-acl-fix`；HEAD 保持上述值。工作树保留入场未提交候选修改，增加脚本修正及本文；未 stage、commit、push、merge 或发布。

**P1 修复候选已完成，停止并等待 Architecture Guard 最终 Source Approval；此 Candidate GO 不授予执行许可。MMF-3 Execution：STOP。Model Management Overall：NOT CLOSED。**
