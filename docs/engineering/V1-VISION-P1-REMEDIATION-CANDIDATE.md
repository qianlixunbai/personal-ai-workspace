# V1 P1 Remediation Candidate

2026-10-12（Asia/Shanghai）。**V1 P1 REMEDIATION — INCOMPLETE / STOP**。

本轮承接聊天中的独立 `V1 SOURCE REVIEW — STOP`，只修复其 P1-A、P1-B 和 P2。没有重新审查 MMF-3、补充 token allowlist、真实模型请求、Runtime/Desktop 正式启动、Ollama 管理、Credential/ACL/selection 修改或 Git publication。

## 入场与源码身份

- Branch：`codex/mmf3-runtime-private-acl-fix`。
- HEAD：`cb36879d93aa9a23c8b3d94e9fa26f5166a3c659`。
- 原清单：`.verification/v1-vision-implementation-20261012-02beafe4/source-manifest.json`。
- 原 SHA-256：`1879b9dc08f69b584db68ccbc8f443056898e4695d722640b773a459fbd13181`。
- 修改前实际逐项复算：395 项全部匹配。保留所有既有未提交 MMF-3/V1 内容。

## P1-A：实现事实与证据

`OllamaVisionEnvironment` 增加持有 service/runner 原生 handle 的采集：OpenProcess query/read/synchronize 权限，GetProcessTimes 创建时间与原 ProcessHandle startInstant 对照，GetProcessId、WaitForSingleObject 和创建时间复核。参数从同一 handle 的只读 PBI/PEB/RTL 路径取得；保留 Win64 长度和 partial-read 拒绝，并拒绝 embedded NUL。

采集记录 generation、完整 service/runner argv、module base address→path 集合。module 枚举前后重读参数及进程状态；hash 后再次采集完整集合并比较。安装目录模块流式 hash 期间排除 write/delete sharing，检查文件身份、长度及修改时间。新增/删除、base/path 替换、argv/generation 变化、未知模块或失败全部不产生正向 context。

Admission cache 与 `VisionTrust.liveContext` 分开。图片最终发送先准备 durable guard，再在 model gate/operations 锁外独立采集实时 context，与冻结 context 比较，最后才记为 sent。拒绝撤销 Vision proof；尚未发送的请求不制造远端 uncertainty。pending send 计数防止清除其他正在准备的 guard；文本原有 beforeSend 路径保持。

实际执行的无模型证据：`VisionIdentityTest` 验证 generation、argv、module 增加及 base 替换在 hash 期间漂移会拒绝，稳定采样接受，过期 deadline 拒绝。增强既有 `VisionFlowTest`：Fake Provider 最终身份变化使任务失败、proof 失效、零新增图片请求、无虚假 uncertainty；稳定 paired proof/user execution 仍可通过。这里的“图片请求”均为随机 loopback port 的 Fake HTTP。

限制：新增 GetProcessTimes/held-handle Windows interop 本身没有执行实际 runner fixture。单独 sampler 的各漂移变体与最终 Provider 拒绝分别获得 fixture 证据，没有声称每个变体均独立执行了一遍端到端流程。

## P1-B：源码改动及当前阻塞

移除 `PngBitmapDecoder`/`JpegBitmapDecoder` wrapper 和 `BitmapImage.StreamSource` 自动选择。新增窄 `VisionWicCodec`：CoCreateInstance 明确 CLSID，查询实际 GetDecoderInfo→GetCLSID 后才取得像素；同一 frame 经 WIC scaler/default numeric format converter 转为有界 BGRA，不创建 color transformer，不读取 ICC/EXIF，不进行 decoder discovery 或 fallback。

固定 CLSID：

- PNG：`e018945b-aa86-4008-9bd4-6777a1e40c11`，Windows SDK 的明确 Windows 8+ `CLSID_WICPngDecoder2`。
- JPEG：`9456a480-e88b-43ea-9e73-0b2d9b71b1ca`。

**阻塞：本机显式 PNG 实例的 GetDecoderInfo 返回 `0x88982F50`（WINCODEC_ERR_COMPONENTNOTFOUND）。** 旧 Decoder1 和明确 Decoder2 的调查尝试均不能取得组件信息；Initialize 前后均未解决。只读注册表检查确认 CLSID/InProcServer 项存在，但无法证明完整 WIC component metadata 可用。没有修改注册表或降低身份验证。

独立无模型 `RawWicIdentityProbe.cs` 直接通过 COM vtable slot 调用 GetDecoderInfo，绕开 managed ComImport/RCW 声明，复现同一 HRESULT。因此不是仅由本轮 managed GetDecoderInfo 声明产生的失败；注册/系统组件信息为何缺失尚未定根因。该 probe 只实例化系统 codec，未打开 UI、图片、模型或产品进程。

当前正向实际 WIC preview 未通过；缩放、实际像素链的成功路径和 COM 释放不能据此声称完整验证。代码保持拒绝，不是可批准的完成实现。

依据：[Microsoft SDK wincodec.h](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/wincodec.h)、[GetDecoderInfo](https://learn.microsoft.com/en-us/windows/win32/api/wincodec/nf-wincodec-iwicbitmapdecoder-getdecoderinfo)。

## P2 与 preview 生命周期

在 codec CopyPixels 后、解码返回后、detached frozen raster 创建后和成功返回前检查 cancellation/5s deadline。ReadSelectedAsync 只处理已经明确选择的 bounded stream，生产 picker 仍打开原有受检 regular file handle；无 bridge/path/bytes 权限扩大。

实际 worker 返回前保持占槽，未使用 WaitAsync 提前释放。Fake codec fixture 已验证错误身份、缺失、异常没有 fallback；失败/取消/超时 Dispose；取消后 worker 未退出时下一次读取 QueueFull，实际退出后槽可复用；超过 deadline 不返回成功。该证据不等于实际 WIC 生命周期验收。

## 实际执行

- Java：`mvnw.cmd -o -Dtest=VisionIdentityTest,VisionFlowTest,OllamaProviderTest#realHttpParsingAndGenerationSettings test` — PASS；production/test compile PASS。所有 Provider 为 Fake，state store mocked。
- .NET 首次 fixture 编译发生 missing using/using 位置错误，已修正。随后 Core/Desktop/test build PASS。
- `WorkspaceVisionTests` 在首次组合 fixture 执行中 PASS；同次实际 WIC fixture FAIL。后续未机械重跑该已绿测试。
- 实际 WIC 正向 fixture — FAIL，最终调查运行仍为 GetDecoderInfo HRESULT `0x88982F50`；未记录为 PASS。
- `dotnet test ... --no-restore -p:FrontendSkipBuild=true --filter FullyQualifiedName~FailClosedPreviewLifecycleKeepsWorkerUntilExit --logger "trx;LogFileName=v1-p1-lifecycle.trx"` — PASS，同时最终 .NET 构建 PASS。
- 无模型直接 COM probe — 实际执行，复现 `Raw GetDecoderInfo HRESULT=88982F50`。
- `git diff --check` — PASS。

React、Browser、完整 Java/Desktop suite、packaging、GPU、真实 Ollama/digest 文本/VISION_VALIDATE/Windows Vision acceptance 均未执行。Frontend 未修改，使用已有 bundled assets，没有重建 frontend。

执行证据副本：[evidence directory](../../.verification/v1-vision-p1-remediation-20261012-cb36879d/identity.json)。保留失败 TRX 和最终 lifecycle PASS TRX，不把历史失败改写成通过。

## 差异与新候选身份

原395项中只修改5项：

1. `desktop/src/PersonalAiWorkspace.Desktop/VisionImages.cs`
2. `src/main/java/io/github/qianlixunbai/workspace/model/ActiveModelManager.java`
3. `src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaProvider.java`
4. `src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaVisionEnvironment.java`
5. `src/test/java/io/github/qianlixunbai/workspace/capability/vision/VisionFlowTest.java`

新增3项源码/测试：`VisionWicCodec.cs`、`VisionPreviewTests.cs`、`VisionIdentityTest.java`；另外新增本交接报告。没有改动其他原清单项。

新完整源码清单：[source-manifest.json](../../.verification/v1-vision-p1-remediation-20261012-cb36879d/source-manifest.json)，399项，SHA-256 **`e15a409822bfe2cb3b819149e7914ce8cd7d3be9fa249ee19b8c454ffb0ddf43`**。

清单覆盖当时所有399项非ignored Git文件，包含原Implementation报告及3项新源码/测试；不含随后创建的本交接报告及ignored verification/build输出，避免自引用。精确差异见 [source-differences.json](../../.verification/v1-vision-p1-remediation-20261012-cb36879d/source-differences.json)。原清单文件未修改。

Git：Branch/HEAD不变；46个tracked修改、52个untracked文件（包括全部原候选），无staged修改，无commit/push/merge/tag。工作树有意保留候选，未reset/clean/stash。

## 边界、限制与停止点

REVIEWED_TOKEN_POLICIES 仍为空，生产真实图片包括 VISION_VALIDATE 均不可出站。ONE Active、native-only管理、ggml、Browser Translate-only、opaque cache、TaskManager completion/uncertainty、Credential/ACL/selection/CSP保持；未访问Finance或Browser正式仓库。

Native采样只是一个受检时间窗口，不与外部Ollama形成原子事务。外部进程可在最后采样之后继续变化；5s是协作检查，系统调用/IO不能据此承诺硬终止。当前空policy确保这些尚未独立验证的原生路径不开放真实图片执行；不声称已消除全部TOCTOU或取得Source Approval。

**必要解决方案：**先在独立、无模型诊断中查明系统WIC component information缺失原因，并建立明确codec正常返回真实CLSID的证据；任何环境修复须另行授权，不能通过默认decoder/vendor/container检查绕过。随后完成同一明确codec的正向缩略图和资源释放验证，再补齐必要held-handle原生fixture及定点复审。本轮不自行修复环境或开始下一阶段。

**V1 P1 REMEDIATION — INCOMPLETE / STOP**。
