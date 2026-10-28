# V1 Vision — Minimal Positive Policy Implementation Candidate

2026-10-28，Asia/Shanghai。**Development-only 候选；不是最终 Source Approval、Vision Ready 或 Production GO。**

入场：`codex/v1-vision-gate-remediation @ ddb0fc976c34c87073ea783b613362c3cc673f92`。
入场仅有前轮未提交的 [Native provenance / policy 审查报告](V1-VISION-NATIVE-PROVENANCE-POLICY-REVIEW.md)，本轮一并交付。
本轮没有重新研究供应链、OBS、WIC 或 token 数学证明。

## 变更

生产代码仅修改 `OllamaVisionEnvironment.REVIEWED_TOKEN_POLICIES` 的数据和对应注释，增加唯一 entry：

```text
identity      qwen3.5:4b/llamacpp/96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7
payload       a96cfee97137af1a40095ce2cb588b7a7ba768cf00851680b0991fcb65004fc8
preprocessing 5864d22b4d6489f43edcbe4542c02a35e0588c5dd41977f659506b50563517af
template      2d965ad8913863ddd9a9e334f881c70f326ac79368b1b129076e71c1f65f4267
POLICY        v1-png-rgb-white-square-1024-context-8192
Evidence      textBound=4096, templateBound=256, imageBound=1024, safetyMargin=512
```

沿用现有 output=1024 和 context=8192，批准预算合计 6912。没有新增其他 entry、公开 API、配置项或抽象。
模型/child、模块、环境、PATH、generation、Native Gate、storage identity、token gate 和失败关闭代码保持原样；
旧隔离 0.40.1 及不匹配的 tuple 不能取得该 entry。Finance、Memory、Knowledge、Browser 权限没有改动。

测试代码只在 `VisionIdentityTest` 新增一个精确绑定回归，并删除原生 held-object fixture 中已过时的
“map 必为空所以不会进入 NativeGate/network”断言；其余 held-object 断言及 fixture 没有改变。
新回归直接读取真实私有 policy map，要求整个 map 恰好等于获准的一个 Evidence，验证正确身份通过，
错误 child、任一错误 payload/preprocessing/template 分量均拒绝。没有为测试增加生产入口。

## 实际验证

实际执行并 PASS：

```powershell
$env:JAVA_HOME='D:\MC\JAVA\JAVA21'
.\mvnw.cmd -o -B -ntp -pl workspace-application -am `
  '-Dtest=VisionIdentityTest#reviewedVisionPolicyBindsOnlyApprovedChildAndTuple+sharedIdentityBindsServerModulesAndEffectiveEnvironment' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

只执行上述新回归和既有共享身份/环境回归，失败、错误、跳过均为零；这两个方法不启动原生 fixture、不调用 Provider，也不发送图片。
未重跑 held-object/WIC、VisionInput/Flow、纯文本 VALIDATE、Java/Desktop/Frontend/Browser 全套或真实 Provider 测试。
后续 package 使用 `-DskipTests`，不把编译或 artifact audit 计为测试执行。

## 可信 Development-only 构建候选

只读检查发现旧 Desktop/Java 仍在运行且持有原 Runtime JAR 的只读锁。因此使用临时 detached worktree 构建 Runtime，
将 JAR 复制到独立候选目录，以原有 Format 2 信任清单生成规则绑定本机 JDK 21、JNA 和新 JAR；
Desktop 使用 Debug / `Mmf3ProfileAcceptanceBuild=true` / `Mmf3AcceptanceBuild=false`，嵌入该清单。
前端资产复用且通过现有 assets verify，没有执行 npm build、安装器或完整分发矩阵。

Runtime 实际执行离线 Maven package；Maven actual-JAR audit 及复制后 actual-JAR audit 均 PASS。
Desktop 实际编译成功，零警告、零错误。只读 PE metadata 提取证明其嵌入清单与生成清单逐字节相同，未加载或运行 Desktop 程序集。
JAR 内 Environment class hash 与本轮聚焦测试使用的编译 class 完全相同；JAR sourceSha 与保存的完整构建输入清单相同。
所有该清单输入均与主工作区构建时原始文件字节一致。原运行中 JAR 和 Desktop EXE/DLL 的 hash 保持不变。

| 最终候选产物 | SHA-256 |
| --- | --- |
| Runtime JAR | `46e5cd92f7fa6a91b391b6c129d7131081bfc94fc89e0321481a3cd2c01379a5` |
| Desktop EXE | `88407ecf17eddbd3953dcbeb1f124522a00605ea75e8f55340e69c85e730ea92` |
| Desktop DLL | `6af784bdeed95eb8e14d808d507034764503a20a67101300a55851006a95e1f4` |
| 嵌入信任清单 | `8cface31f57bae7d4576c354c9c488d6feca2219a1ae571362330c11ea8e2d2c` |
| Runtime sourceSha | `a93d42c6c8d47fb507e2f335e1da70ed7b87c20dcd546ad4430d16ddc0de0867` |
| Environment class | `0ec7febc05f364432d3e840933f50223389955b61a6353a5f9692192af69f23c` |

实际候选位于 ignored [`.verification/v1-vision-minimal-policy-20261028/`](../../.verification/v1-vision-minimal-policy-20261028/)：
`runtime/personal-ai-workspace-0.1.0.jar`、`desktop/PersonalAiWorkspace.Desktop.exe`，以及
`build-identity.json`、`runtime-source-manifest.json`、`runtime-sources.local.json`、`embedded-runtime-sources.json`、
`java-focused.log`、`VisionIdentityTest.xml`、`runtime-build.log`、`candidate-jar-audit.log`、`desktop-build.log`。
该目录不随 Git push 发布；它是本机工具链和绝对路径绑定的开发候选，尚未启动或部署。

首次隔离 checkout 的源指纹检查发现 CRLF/LF 差异；确认只有换行差异后，已仅对齐隔离构建输入并重新生成候选。
初始和最终构建日志均保留，测试没有重复运行。采证包装中的 strict-mode 状态变量和 JAR marker 路径错误在采证层纠正，未改生产行为。
本实施报告在最终候选生成后编写，因此未计入该 JAR 的 sourceSha；生产/测试和前置审查报告已包含在构建输入中。

## 首次 paired synthetic 的准备状态和剩余条件

**代码准备条件已满足。** 本候选已经具备唯一正向 Evidence，且错误 child/tuple 的离线拒绝证据通过；
真实共享 `currentContext` 正向采样、Native Gate 准入、首次两图执行和 Vision validated publication 本轮均未实际运行。

实际运行前仍须：

1. 对本候选完成独立源码/产物复核，并通过原有受信 Development-only 启动链使用该候选；本轮没有停止或替换旧实例。
2. 现时精确 child 为 Active、text Ready、revision 匹配，无 error/uncertainty/guard/switch，所有工作计数为零。
   Runner 若已卸载或退出，先取得独立 lifecycle 授权；不因本次交付自动启动、加载或切换模型。
3. 当时的 Server/Runner 模块、环境和有效预处理重新采样后精确命中唯一 tuple。
   PATH、环境、generation 或其他身份不符即 STOP，不补 entry、不归一化额外字段，也不复用历史驻留假设。
4. **另行明确授权两次图片请求，并完成既有 native `VISION_VALIDATE` exact-intent 的 one-shot 确认**。
   核对完整 child/projector、revision、generation/policy、同一 session 和 60 秒时限；仅使用现有内存合成红/绿规范图。
   第一张得到 `red` 才进入第二张，第二张得到 `green`，且最终身份稳定后才能发布 Vision validated。
   失败、超时、未知完成或身份漂移沿用 owner guard/uncertainty 处理，不自动重试。

本轮图片请求数为零，没有 `VISION_VALIDATE`、模型启动/加载/切换、Ollama inference、全局配置变更或 Firecrawl。
Git 交付范围为一个生产文件、一个测试文件、前置审查报告及本报告；仅提交/推送当前开发分支。
最终本地/远端 commit 核对与工作树状态以交付回复和本地 `delivery-state.json` 为准，不授予最终 Source Approval 或 Production GO。
