# V1 Vision Implementation Candidate

2026-10-12（Asia/Shanghai）。**V1 IMPLEMENTATION CANDIDATE — READY FOR SOURCE REVIEW**。

本轮仅源码实现、deterministic fixture、聚焦测试和构建。没有真实 Ollama 请求、真实模型加载/切换/推理、真实 VISION_VALIDATE、模型 Release、Runtime/Desktop 启动、生产环境修改或 Git publication。Fixture PASS 不等于真实 Vision PASS；V1 未 CLOSED。

## 1. 入场与审批材料

用户的源码实施授权继续有效。实际材料目录为 `D:/安装包/V1-VISION-IMPLEMENTATION-APPROVAL-EVIDENCE`，不同于请求正文中的简称目录；先读取 README，再完整读取 Architecture Conditional GO、P1 Preimplementation Evidence、Service Model Identity Ready 和 P1 Final Guard CLOSED。Scope Candidate 只作为此前提案。

这些是用户提供的独立审查交接副本，本轮未重新执行 MMF-3/P1 全面审查。采用最新 **V1 P1 GATES — CLOSED / IMPLEMENTATION PREPARATION GO**，没有把历史 P1 EVIDENCE INCOMPLETE 改写成当时 PASS。

入场 branch：`codex/mmf3-runtime-private-acl-fix`；HEAD：`cb36879d93aa9a23c8b3d94e9fa26f5166a3c659`。
原 MMF-3 source-baseline SHA-256：`0cba5da2e51516e595c399f0e974dfc3ca7fb177fc25a5395850b442db8ef5a8`。
376 条入场核验仅三份 Closing Record 允许同步的状态文档不同；生产、测试、配置及脚本无未经批准漂移。
原 33 tracked 修改、30 untracked 文件、空 index 全部保护；本轮不撤销或提交这些候选。

## 2. 实现事实与证据等级

| 层 | IMPLEMENTED | TESTED WITH FIXTURE / 边界 |
| --- | --- | --- |
| Admission | 仅 0.40.1 / qwen3.5:4b / Q4_K_M / llamacpp 的完整固定身份；保留原 ggml 规则 | Fake HTTP 验证精确 metadata、digest wire reference 和错误 projector 拒绝；实际 Windows 服务/组件检查未执行 |
| Active/proof | 唯一 ActiveModelManager；独立内存 Vision proof，绑定 Active epoch/revision、runner、projector、policy、native context；publication/Release/Recovery/漂移失效 | Fake Provider + mock state store 验证文本与图片验证分离、one-shot、失效后零图片出站、取消 uncertainty；不冒充真实 ACL/persistence 验收 |
| 图像 | 固定 JDK PNG/JPEG reader、SPI/module、固定 PNG writer；结构/CRC/metadata、尺寸、帧数；内存 stream；8-bit RGB、白底 1024² | 综合 fixture：PNG、透明合成、16-bit grayscale PNG、progressive JPEG、ICC 过滤、APNG/损坏/尾随数据/尺寸拒绝 |
| HTTP/任务 | native-only；单图 strict streaming Base64 parser；UUID/selection revision；单槽贯穿 upload/decode/queue/run/drain；复用 TaskView 和 owner GET/DELETE | fixture 验证重复 ID、未知/重复 JSON 字段、非规范 Base64、body 限额、Browser/auth 拒绝前不读 body；取消后的共享 guard |
| Native/WPF | 所选 regular handle；有界 off-UI WIC preview；无 EXIF/外部 ICC；512 长边；detached frozen thumbnail；session/image generation owner | native fixture 验证冻结 UUID、未知 POST 只查询、one-shot、session replacement、清理后未退出 picker 的迟到结果抑制；真实 picker/WIC 未运行 |
| Bridge/React | Assistant 单图入口；metadata/status/text only；原生预览关闭通知；不跨 bridge 传 bytes/path/credential/model override | UI fixture 验证只有 handle/question 进入 submit、纯文本结果、one-shot、关闭通知清理；既有 text/bridge 聚焦 tests 保持通过 |

## 3. 精确模型与 token 门禁

固定 identity：Manifest List `430d3fc72add72bb7a641e14ee28e60be312b6c9f93a144a22ec4583bdbdf846`；ggml child `3c3adf637187c95955232acd3ed05b9ea45aeb2a5f953e631c38174a46b5b56a`；llamacpp child `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`；config `57c5e44ea71e1b7ad15c8c081b1ad23c66ac5f995a0883e153de7972100e3676`；语言模型 `d324d8d025284ff9faed6c97b03869f4a928bb14a4d83a6cc2c713039c3b3b4f`；projector `db3830bf825a26fcfed35416378696a47e0cbcf49c5b652a6fe9db90541f86d9`。

源码核对批准 child 的小型 manifest/config 时，还确认其固定 license/params 层。它们受完整 child digest 绑定，并纳入 SHA/size 闭包核验；没有通用开放额外组件。运行时不跟随 show 提供的路径读取文件，只比较已批准本地根下的精确 FROM，核验 named manifest、child、config 和全部指定 blobs。当前监听 PID、exe SHA/版本身份与根关联每次 admission 重验，不能把历史 PID 7832 当作永久信任。

该候选文本验证、图片执行和对应 Release wire reference 使用 `sha256-<完整 child>:local` 和显式 runner；响应比较实际 wire reference。既有 ggml wire/准入不变。Metadata 的 providerDeclaredVision 不能授予图片权限。

**BLOCKED — LIVE VISION EGRESS：** `REVIEWED_TOKEN_POLICIES` 当前明确为空，批准材料没有提供可用于执行的当前 native payload/preprocessing/template token proof。本轮不填入猜测值，也不把 1024 embeddings 当作通用 token 上界。生产路径保持 `TOKEN_EVIDENCE_MISSING`，首次图片 probe 和用户图片均不可出站。

实现的可信边界只接受进程内、经过独立审查的 policy 数据，HTTP/React/metadata 不能填充它。当前身份核验更新缓存 context；budget/proof 检查只读该 context，不在 model gate 内执行网络或 codec。未来有审查数据时，采集当前 selected native runner 的 bounded argv、安装目录 modules 的流式 hashes、预处理/renderer fingerprint 和 service/runner process generation，匹配审查 allowlist；未知 DLL、无法读取、policy/process 漂移均关闭图片出站。

本机 SDK 的一个无模型子进程检查实际确认：Windows JDK 的 foreign command 存在、arguments 不存在。因此源码加入了只读 Win64 PBI/PEB/RTL 参数读取和 CommandLineToArgvW；64 KiB 上限、八字节 SIZE_T 返回计数、无权限提升/内存写入。**这部分编译通过，但原生读取本身及真实服务/modules 尚未运行，须独立源码审查和无模型 fixture 验证。**

Token 校验必须同时绑定当前 identity/payload/preprocessing/template/policy，并满足 `text + template/special + image + 1024 output + margin <= 8192`。真实运行前须提供充分的可信 bounds；不扩大 context、不换 runner、不重试模型。Fixture 的 a/b/c hashes 只存在于测试。

## 4. 安全、资源与 lifecycle

encoded ≤4 MiB；原图每边 ≤4096、总像素 ≤8,000,000；exact Vision JSON ≤6 MiB；question ≤1000 UTF-16 / ≤4096 UTF-8；结果 ≤1024 requested tokens / ≤8192 UTF-8。

上传 15s deadline 关闭输入并拒绝迟到 body；decoder 5s 协作 deadline/abort；queue 至多30s、Provider 至多120s、execution 至多150s分别计时。HTTP body/codec 阻塞无法被协作取消强制终止；实际 worker 未返回时不释放对应槽。150s 不覆盖 upload/decode/queue。取消后不把本地 task terminal 或 HTTP future cancellation 当作远端可信完成。

队列只捕获 normalized image + frozen execution；终态 retention 只保留 task/text。运行 worker finally 关闭模型 lease 与 Vision slot；未知完成进入既有 durable uncertainty guard，阻止新 AI admission/switch/Release。普通图片格式错误或不合格视觉回答不关闭已有文本能力。

97 MiB 保留为批准材料的静态大对象估算，**不是实测或硬上限**。本实现存在受界限约束的 parser/filter/encoder/preview 拷贝与 GC/codec scratch，未测 JVM/native/进程峰值；不声称本机峰值等于97 MiB。WIC worker 不使用 WaitAsync 超时提前归还槽，缩略图复制为 detached frozen raster，避免 thumbnail 保留原 stream。

清理/窗口关闭使旧 handle/generation 失效，阻止迟到 picker/result；已发任务只针对冻结 UUID 作 bounded best-effort GET/DELETE，最多八个 cancellation observers，不重发 POST。404、重启或 retention 到期不表示必然未执行；清理不保证物理擦除或 Provider 停止。

没有图片 DB/cache/history、领域写入、任意路径 Runtime API、普通日志中的用户图片/文件名/路径/问题/结果/base64/原始 Provider response。未增加 dependencies 或修改 credential/ACL/private bootstrap、selection v1、CSP、host mappings、bridge 全局 size limits。

新增 routes：GET `/api/v1/capabilities/vision/readiness`；POST `/api/v1/vision/tasks`；GET `/api/v1/vision/validation/intent?sessionId=...`；POST `/api/v1/vision/validation`。其余 Task GET/DELETE 保持原 owner rules。独立 validation intent frozen、60s/default Cancel、一次消费；expiry 使用毫秒精度与单调 deadline，避免跨 Java/.NET 时间精度漂移。

## 5. 实际 V1 文件增量

共33个源码/测试文件；原未提交 MMF-3 内容不是本轮增量。完整路径见 [v1-files.txt](../../.verification/v1-vision-implementation-20261012-02beafe4/v1-files.txt)。

- Runtime：api/ApiExceptionHandler、VisionController；common/ApiError、ErrorCode；capability/vision 的 VisionImage、VisionRequest、VisionSlot、VisionTokenBudget；model/ActiveModelManager；provider/ollama 的 OllamaModelAdmission、OllamaProvider、OllamaVisionAdmission、OllamaVisionEnvironment；security/ClientIdentity、LocalClientFilter；task/TaskManager。
- Native：Core/RuntimeClient.Vision；Desktop/AssistantApp、KnowledgeSourceFiles、VisionImages、Bridge/WorkspaceBridge、WorkspaceVision。
- React：bridge/client、contracts、vision；components/VisionPanel；pages/OperationPage。
- Tests：VisionInputTest、VisionFlowTest、VisionSecurityTest、WorkspaceVisionTests、VisionPanel.test、OperationPage.test。后者只隔离 Vision component 并明确已有文本输入 selector；没有改变既有文本生产语义。

## 6. 实际执行验证

- Java：`mvnw.cmd -o -Dtest=VisionInputTest,VisionFlowTest,VisionSecurityTest,OllamaProviderTest#realHttpParsingAndGenerationSettings+installedDefaultMetadataRequiresStableSupportedVersionAndPositiveLocalSource test` — PASS。Fake HTTP 是随机 loopback port；state store mocked；无真实 Credential/ACL/model 操作。覆盖 controller validation JSON roundtrip 和真实 owning scheduling/guard 逻辑。
- Native：`dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter FullyQualifiedName~WorkspaceVisionTests` — PASS；同时 Core/Desktop/test project build 完成。使用 injected HttpMessageHandler 和 picker fixture，无应用窗口/模型。
- React：`npm test -- src/components/VisionPanel.test.tsx src/pages/OperationPage.test.tsx src/bridge/client.test.ts` — PASS。
- `npm run build` — typecheck + Vite + assets manifest PASS；最后 Native build 使用这份最新 assets。
- `git diff --check` — PASS。

开发中出现过 ApiError enum mapping、TaskManager fixture resource syntax、Native using/nullable callback 和 frontend ByRoleOptions 编译失败，均已小范围修复，以上为最终成功执行。历史 ModelFoundationTest Credential 初始化失败记录未改写；完整 Java/Desktop/Frontend/Browser、IME、stress、packaging matrix 未运行。

## 7. NOT PERFORMED 与正式验收前最少步骤

真实 digest-addressed 文本执行、真实 paired image proof、Windows picker/WIC/端到端、upload/decode deadline 实测、内存/CPU/GPU峰值、RTX5060/3060、共享服务恢复和 Release 均 NOT PERFORMED。

下一步先独立源码审查，尤其 Win64 readonly parameter/module identity 与 token policy trust boundary。通过后仅在独立授权的无模型 fixture 中验证 interop/preview/资源退出。收集并独立批准当前 native payload、预处理 flags、renderer/template/special token 的可信 bound，再补充最小 allowlist 数据；该源码身份变更须审查，不能直接当作本候选的原 SHA。

随后另获真实模型授权，才进行同一 exact child 的文本验证、配对合成图 Vision proof、一个 Windows 单图→纯文本→既有文本→取消/失败流程，以及实测资源/时限。RTX5060 8GB 是待验收目标；RTX3060 6GB 独立判定；均不声明支持或 GPU PASS。

现阶段没有编码前审批材料缺失。LIVE egress gate 的缺少可信 policy 数据是明确 fail-closed 执行前置条件，不能凭本报告解除。浏览器仍 Translate-only；单独 Vision proof 更新不改变 opaque text cache epoch。Finance/Browser 正式仓库均未访问或修改；MMF-3 关闭记录与保护边界保持。

## 8. 交付身份与 STOP

Branch/HEAD 保持入场值；原 source-baseline 文件本身未改变。离场基线对比没有 V1 文件和三份已允许状态文档之外的漂移。

本轮源码哈希清单：[source-manifest.json](../../.verification/v1-vision-implementation-20261012-02beafe4/source-manifest.json)，395项，SHA-256 `1879b9dc08f69b584db68ccbc8f443056898e4695d722640b773a459fbd13181`。清单绑定原候选和 V1 源码/测试，不含随后创建的本报告；不能仅凭 HEAD 标识当前候选。

工作树：46 tracked 修改；本报告加入后48 untracked文件；index无staged修改。既有候选保持；没有 commit/push/merge/tag/Release。停止代码修改，等待独立源码审查，不进入真实模型/Windows验收或 V1 Closing。

**V1 IMPLEMENTATION CANDIDATE — READY FOR SOURCE REVIEW**
