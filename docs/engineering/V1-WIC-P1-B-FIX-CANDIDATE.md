# V1 WIC P1-B FIX CANDIDATE

2026-10-12（Asia/Shanghai）。**V1 WIC P1-B FIX — READY FOR INDEPENDENT RE-REVIEW**。

本轮仅修复已确认的 P1-B decoder 创建路径，并执行无模型产品路径 fixture。没有取得 Source Approval，没有关闭 V1，也没有自动开始下一阶段。此前失败报告与 TRX 保留原样；本报告提供后续修复证据。

## 1. 源码身份与差异

- Branch：`codex/mmf3-runtime-private-acl-fix`。
- HEAD：`cb36879d93aa9a23c8b3d94e9fa26f5166a3c659`。
- 入场清单：`.verification/v1-vision-p1-remediation-20261012-cb36879d/source-manifest.json`，SHA-256 `e15a409822bfe2cb3b819149e7914ce8cd7d3be9fa249ee19b8c454ffb0ddf43`；399 项逐项匹配。
- 本次仅修改三项既有源码/测试：`VisionWicCodec.cs`、`VisionImages.cs`、`VisionPreviewTests.cs`。另外新增本报告。原清单其他396项保持原哈希；所有既有 MMF-3/V1 未提交候选保留。
- 精确本轮补丁：[p1b-fix.patch](../../.verification/v1-wic-p1b-fix-20261012-cb36879d/p1b-fix.patch)。原文件备份位于同目录 `before/`；不是对 Git HEAD 的总候选差异。

新完整源码清单：[source-manifest.json](../../.verification/v1-wic-p1b-fix-20261012-cb36879d/source-manifest.json)，400项，SHA-256：

`8b008d27d7cfe743b88d47d12953d8305237f8d428ba263934b280f454ded9d6`

清单覆盖当前所有非ignored Git文件，仅排除本交接报告以避免自引用；verification/build输出不纳入源码清单。相比旧399项清单，新增纳入此前未被其收录的旧 remediation 报告，其内容没有修改。当前报告另记哈希于 [identity.json](../../.verification/v1-wic-p1b-fix-20261012-cb36879d/identity.json)。逐项旧/新哈希见 [source-differences.json](../../.verification/v1-wic-p1b-fix-20261012-cb36879d/source-differences.json)。

## 2. 固定 Factory 与双重实际身份验证

[VisionWicCodec.cs:38](../../desktop/src/PersonalAiWorkspace.Desktop/VisionWicCodec.cs#L38) 固定 CoCreateInstance Factory2 CLSID `317d06e8-5f24-433d-bdf7-79ce68d8abc2`，请求其 IWICImagingFactory 基接口；这里使用的组件信息及 scaler 方法属于该基接口。未使用 Factory 的自动格式选择。

构造链：

1. `:39` CreateComponentInfo 请求明确批准的 PNG2 `e018945b-aa86-4008-9bd4-6777a1e40c11` 或 JPEG `9456a480-e88b-43ea-9e73-0b2d9b71b1ca`。
2. `:42` COM cast 执行 QI 到 IWICBitmapDecoderInfo；`:43–44` 对组件 GetCLSID 并严格匹配。
3. `:45` DecoderInfo.CreateInstance 创建 decoder；不直接 CoCreateInstance decoder。
4. `:46–47` 对得到的实际 decoder 调用 GetDecoderInfo，再调用 GetCLSID 并严格匹配。
5. 两次身份检查完成后，才创建内存 stream 并 Initialize。

接口定义 `:132` 使用 SDK IID `d8cd007f-d08f-4191-9bfc-236ea7f0e4b5`，按8个 ComponentInfo、12个 BitmapCodecInfo、3个 DecoderInfo 方法展开；CreateInstance 为包含 IUnknown 的 slot25。Factory.CreateComponentInfo 为 slot6。未调用的前缀占位声明仅保留 vtable 顺序。既有独立诊断的固定 SDK 定义与 slots 证据保留在 root-cause verification 目录。

任意创建、QI、身份检查或 Initialize 失败均拒绝。没有 PNG1 重试、decoder discovery、容器格式代替身份或 BitmapImage/BitmapDecoder fallback。未推断 Windows DLL 内部实现，也未修改系统环境。

## 3. 同一解码链与 COM 生命周期

[VisionWicCodec.cs:60](../../desktop/src/PersonalAiWorkspace.Desktop/VisionWicCodec.cs#L60) 从上述同一 decoder 获取唯一 frame、核对结构尺寸、用持有的 Factory2 创建有界 scaler，再用固定 numeric BGRA converter 执行 CopyPixels。最长边仍为512；无 color transformer 或 ICC/EXIF 自动转换。受检文件 handle、结构过滤、上传大小限制和 Native bytes/path 所有权未改变。

Factory、Decoder、Stream 由 codec 持有；ComponentInfo/DecoderInfo 是同一 RCW 的接口别名，由 component owner 释放。实际 decoder info 在构造结束时释放；frame/scaler/converter 在 Decode finally 释放。Release 对同一 RCW 去重，并在某个释放失败后继续尝试其他 owner。构造失败清理全部已获得引用，Dispose finally 配对成功的 CoInitializeEx/CoUninitialize；已有 STA 的 RPC_E_CHANGED_MODE 不错误配对 Uninitialize。

`:85` 绑定创建线程，拒绝跨 worker 使用；产品创建、解码、释放之间没有 await。清理失败不覆盖原身份拒绝或取消；成功路径的清理失败则阻止返回成功。[VisionImages.cs:51](../../desktop/src/PersonalAiWorkspace.Desktop/VisionImages.cs#L51) 仅为这一错误优先级规则调整 codec finally。

P2 的 CopyPixels 后、Decode 返回后、detached frozen raster 后及发布前检查均保留。仍 await 实际 worker 返回，并在外层 finally 才释放槽；没有用取消/超时包装提前释放仍运行的 WIC worker。五秒是协作 deadline，不承诺硬中断正在运行的 Windows codec 调用。

## 4. 本次实际执行的最小验证

只执行一次：

```powershell
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter FullyQualifiedName~VisionPreviewTests --logger "trx;LogFileName=v1-wic-p1b-final-fix.trx" --results-directory .verification/v1-wic-p1b-fix-20261012-cb36879d
```

**PASS**。同一命令的 Core、Desktop、Desktop.Tests 构建成功。没有启动正式应用；使用已有 frontend assets，没有重新构建 frontend。

- [ExplicitWindowsCodecsProduceFrozenPreview:25](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/VisionPreviewTests.cs#L25)：复用合成16-bit PNG及合成JPEG，直接执行产品 VisionWicCodec，核对明确 CLSID及实际 decoder Identity；真实 scaler/CopyPixels 输出512×384 BGRA。再经产品 ReadSelectedAsync 返回冻结512×384预览。空编码输入的构造失败及真实 CopyPixels 后取消分支也执行；随后正常解码成功。
- [FailClosedPreviewLifecycleKeepsWorkerUntilExit:48](../../desktop/tests/PersonalAiWorkspace.Desktop.Tests/VisionPreviewTests.cs#L48)：复用注入 codec fixture 验证错误身份、缺失组件、异常均拒绝且单次工厂尝试，无 fallback；取消后未退出 worker 仍 QueueFull，未提前 Dispose，退出后槽可复用；超时不返回成功且调用 Dispose。补充清理异常不覆盖取消、成功路径清理异常不能返回成功的断言。

错误身份/缺失组件证据使用原有注入边界，没有移除实际 Windows 组件或伪造系统注册状态。实际 COM 正常、Initialize失败和CopyPixels后取消路径已经执行；fixture 未逐个测量系统 COM 引用数或 native memory 峰值，不将后续正常调用成功夸大为完整泄漏证明。

结果：[v1-wic-p1b-final-fix.trx](../../.verification/v1-wic-p1b-fix-20261012-cb36879d/v1-wic-p1b-final-fix.trx)。`git diff --check` PASS；本轮三文件相对入场备份的 whitespace 检查亦 PASS。原清单、原失败TRX及RawWic证据共6项复算仍匹配历史哈希，未改写或清理。

## 5. 保留的门禁、历史证据与限制

P1-A 的 held process handle/generation、argv/module前后采样、hash后集合复核、独立liveContext与proof撤销代码和测试均保持入场哈希。此前 Java focused PASS 为历史证据，本轮没有重跑 Java。P2 行为保持，并由上述 preview fixture重新覆盖受影响路径。

REVIEWED_TOKEN_POLICIES 仍为 `Map.of()`；真实图片包括 VISION_VALIDATE 仍关闭。本轮未修改 Runtime API、ONE Active、ggml、Browser Translate-only、opaque cache、TaskManager guard、Credential、ACL、selection、CSP或Bridge合同；没有访问Finance/Browser正式仓库。

未执行真实模型、Ollama请求、digest文本、Vision execution proof、token policy授权、正式WPF picker/窗口/session端到端验收、JVM/WIC/native memory峰值、GPU或Release/Production验收。合成图片的真实产品WIC路径成功不等于正式Windows UI验收。本次不重新验证全部文本/Browser/MMF-3兼容性；相关源码哈希保持，只进行受影响owner的聚焦验证。

Git Branch/HEAD不变；46个tracked修改、53个untracked文件，无staged修改。三项被修复的源码/测试原本即untracked，不能只看 Git tracked diff。verification目录为ignored本地证据，不是必须提交的生产源码。没有reset/clean/stash或commit/push/merge/tag。

## 6. 独立定点复审最少材料与停止点

复审只需新400项清单及identity、三文件入场差异patch、两个preview fixture源码与本次TRX；必要时对照原root-cause诊断的SDK slots与明确component CreateInstance结果。P1-A保留状态可通过旧/新哈希及原focused报告确认，不把历史PASS写成本轮重新执行。

补丁与证据已完成，停止修改，等待独立定点源码复审。没有自动授权token policy、模型操作、环境修复或Windows Vision验收。

**V1 WIC P1-B FIX — READY FOR INDEPENDENT RE-REVIEW**。
