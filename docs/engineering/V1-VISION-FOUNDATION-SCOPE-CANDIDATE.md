# V1 — Multimodal / Vision Foundation Scope Candidate

2026-10-11（Asia/Shanghai）。**Proposal / Pending independent Architecture Guard review。**
**SCOPE CANDIDATE READY；IMPLEMENTATION NOT AUTHORIZED；Vision execution NOT PERFORMED。**
进入依据为 [MMF-3 CLOSED — GO（Development-only）](MMF-3-CLOSING-RECORD.md)，不是 Production RELEASE GO。
本文件只核对真实源码并提出最小范围；下述 API、预算、准入与验证设计均未 Accepted。
ADR-015 Accepted 正文不变；任何补充合同须独立审查后才可实施。

## 1. 源码与能力事实

审查身份沿用 Closing Record 的 HEAD、source-baseline.json 与 18 文件审批清单。
本轮入场基线逐文件一致；只读核对以下 owner，不启动 Runtime/Desktop/Ollama，不查询实际模型服务。

| 当前 owner | 已存在的事实 | Vision 缺口 |
| --- | --- | --- |
| [Provider.java](../../src/main/java/io/github/qianlixunbai/workspace/provider/Provider.java) | Capability 只有 TEXT_GENERATION；ChatMessage 只有 role/content | 没有图像输入执行合同 |
| [OllamaProvider](../../src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaProvider.java) | 显式 runner、model:local、执行前身份重验；loopback/no proxy/no redirects；有界响应；可信完成与 provider exit 分开 | 请求仅文本 messages；不能因响应允许 images 字段推定支持输入图片 |
| [OllamaModelAdmission](../../src/main/java/io/github/qianlixunbai/workspace/provider/ollama/OllamaModelAdmission.java) | 白名单 0.40.0/0.40.1；只准入 ggml、严格 GGUF/source/family/context/child identity；拒绝非空 projector_info 和 PROJECTOR 指令 | 不能删去拒绝条件来通用开放 Vision；llamacpp/mlx enum 可解析不代表已准入 |
| [ActiveModelManager](../../src/main/java/io/github/qianlixunbai/workspace/model/ActiveModelManager.java) | 唯一 Active owner，ExecutionModel=model/runner/digest/revision；统一 reservation、switch、guard；catalog 可给 native 端 providerDeclaredVision | 当前 probe 是文本验证；不存在 Workspace image-input 验证或 Vision-ready gate |
| [TextTaskSubmission](../../src/main/java/io/github/qianlixunbai/workspace/capability/TextTaskSubmission.java) / [TaskManager](../../src/main/java/io/github/qianlixunbai/workspace/task/TaskManager.java) | frozen profile/reservation；任务归属、队列、取消/超时/drain、短期内存结果 | 文本 token 预算不能替代图像/vision token 预算；无需第二 scheduler |
| [LocalClientFilter](../../src/main/java/io/github/qianlixunbai/workspace/security/LocalClientFilter.java) / [ClientIdentity](../../src/main/java/io/github/qianlixunbai/workspace/security/ClientIdentity.java) | Browser Translate-only；native capabilities 显式集合；普通 mutation body 上限 32768 bytes，少数 exact native routes 例外 | Vision route、native capability 和图像 body 例外都不存在，须明确审查 |
| [WorkspaceBridge](../../desktop/src/PersonalAiWorkspace.Desktop/Bridge/WorkspaceBridge.cs) / [WorkspaceContentPolicy](../../desktop/src/PersonalAiWorkspace.Desktop/Hosting/WorkspaceContentPolicy.cs) | trusted document/session、exact payload、32 KiB 请求/常规 64 KiB 响应；CSP connect-src none；不开放下载/权限 | 大图不能直接塞进 bridge 或通过放宽 CSP/任意 file URL 读取 |
| [KnowledgeSourceFiles](../../desktop/src/PersonalAiWorkspace.Desktop/KnowledgeSourceFiles.cs) | 已有单文件 native picker、local regular handle/no reparse、有界 stream 模式 | 仅 TXT/Markdown；可参考方式，不能把它改成通用附件/图像导入框架 |
| [Core Models](../../desktop/src/PersonalAiWorkspace.Core/Models.cs)、[Settings](../../desktop/frontend/src/pages/SettingsPage.tsx)、[Frontend model parser](../../desktop/frontend/src/bridge/models.ts) | typed model/runner/digest 与 providerDeclaredVision 合同 | 没有 Vision 选择、preview 或提交入口，不持有 credentials |

[ADR-015 §3.I](../ADR/ADR-015-active-model-management.md#i-vision-preparation) 已要求分开
providerDeclaredVision、runtimeValidatedText、workspaceVisionExecutionValidated；MMF 阶段最后一项固定为未验证的语义，
不是当前 status 已包含一个可启用的 Vision readiness 字段。

历史只读记录 [readonly-version.json](../../.verification/mmf3-model-identity/readonly-version.json) 显示当时 Ollama 0.40.1；
身份修复报告保存同名 ggml/llamacpp child、llamacpp projector metadata 的观察。
这些不是本轮实时服务版本、当前安装模型、可执行 Vision runner 或 GPU 能力证明。

固定上游版本只读合同核对采用 MMF-3 已引用的官方 commit `cf2a313a298066d572c36812e5ad30a21c0db13b`：
[api/types.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/api/types.go)
含 ChatRequest.runner 与 Message.images；[server/routes.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/server/routes.go)
和 [server/images.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/server/images.go)
是后续核对实际 runner/image/projector 执行及来源的固定依据。
协议能携带图片不证明当前模型/runner 真正消费图片，也不认证外部服务的网络隔离。
本轮未作模型推荐、下载或 VRAM 容量保证。

## 2. 最小垂直闭环（Proposal）

```text
用户在 Windows Assistant 明确选择一张本地 PNG/JPEG
→ WPF 绑定实际文件 handle，受控读取、验证与原生预览
→ 用户明确提交图片及有界问题；typed bridge 只传操作元数据
→ application-owned RuntimeClient 发送图像 bytes 到 native-only API
→ Runtime 独立校验图像 + 唯一 Active 的 Vision gate + frozen reservation
→ 已独立验证 image-input 的 local model/runner/child digest
→ 有界文本结果 → Windows Assistant；取消/失败受控显示
```

优先在既有 Assistant/Main Workspace 入口增加一个明确的单图操作。
首次只输出 plain text 描述/问答，无结构化 OCR schema、Conversation 附件或自动领域保存。
图片中包含文字、图表等不可信内容；模型生成文本不是事实认证，更不能授权 tools、Web、文件读取或领域写入。
当前 Active 没有通过 Vision 验证时显示不可用；不隐式切换、自动下载、选另一 runner 或回退云端。

WPF 负责图片选择及预览，WebView2 展示安全 metadata 和文本结果。原生 preview 避免把完整图像跨 bridge 回传，
不增 file://、host mapping 到私人目录或 connect-src。若要求 WebView2 内 preview，必须另审单个规范化缩略图的有界 transport，
不能先扩大通用 bridge 响应上限；该 UX 选择 Pending。

## 3. 模型准入、验证与唯一 Active（Proposal）

保留一个 Active owner、一个已选择身份和现有 selection schema。首版要求候选同时满足文本 completion 与受控 image-input；
Vision-only 第二 Active/Provider 路由不在范围内。文本能力没有合格 Vision 候选时继续使用现有文本模型，图像操作 unavailable。

必须分开三个结论：

| 结论 | 证据与含义 |
| --- | --- |
| providerDeclaredVision | 固定版本 show/capabilities 声明；只读 metadata，无 image egress |
| runtimeValidatedText | 当前准入身份的既有文本 probe 与文本执行合同；不代表视觉功能 |
| workspaceVisionExecutionValidated | 精确版本/身份/图像预处理策略下，明确授权的 synthetic image-input probe 实际通过；不保证所有图片识别质量或 GPU 性能 |

建议 Runtime 保留非持久的 Vision validation 状态，绑定 model + runner + child digest、已支持 Provider 版本和验证/预处理策略版本。
Runtime restart、Active publication、更换 runner/digest、metadata/source 漂移或策略变化时失效；重验须明确授权，不能后台 probe。
不把 metadata 声明直接转为 verified，也不借当前 text VALIDATE 静默增加图像发送。
建议增加独立 native exact-intent Vision 验证操作；精确 action、DTO、expiry/default-Cancel/session/one-shot 与返回形状待审，
不能复用旧 mutation shape 偷改 VALIDATE 的语义。

真实能力验证应使用少量无私密合成图，问题不透露正确答案；例如两张有明显不同色块/形状的配对图，交换图像后答案应随视觉输入改变。
核对一次综合流程中的 image bytes、显式 runner、稳定 selected child，以及有界受控输出，区分 metadata、文本猜测与实际图像理解。
该流程提供能力 smoke evidence，不能证明任意模型不会猜答案，也不能替代人类质量评估。
失败/未验证保持 Vision unavailable；不自动尝试备用 runner。

Vision admission 必须正面审查语言模型与 projector/视觉组件的本地来源、child manifest 绑定、family/architecture/context 和固定版本执行路径。
不跟随 show 返回的文件路径读模型文件。当前 ggml 规则保持到新的具体受支持子集获批；
是否支持某个 ggml embedded-vision 或 llamacpp/projector 子集 **Pending**，不能只放宽 projector_info/families 白名单。
已有磁盘 model+digest 必须仍能唯一恢复 runner；重复 runner/共享 digest 歧义继续 fail closed，不能改变旧持久格式来绕过歧义。
如果 Vision 所需身份不能在现有格式下唯一恢复，先报告架构冲突，停止实施而非自动迁移。

所有跨模型 load 继续使用原 native switch exact-intent 和 automatic eviction 提示；图像 probe 可能触发加载，亦须相应确认。
加载后有可信完成却视觉回答不合格，与远端完成未知分别处理；后者仍关闭整个共享 AI admission/switch/Release，
不能只关 Vision 或借 task terminal/restart/空 ps 清 guard。文本 ready、Vision validated 与 resident 分开报告。

## 4. 推荐 owner 与最小 API（Proposal）

| 位置 | 建议最小改动 |
| --- | --- |
| Runtime capability | 窄 `capability/vision` owner 验证单图、问题、Vision budgets，并提交现有 TaskManager；不把 binary 塞入 TextTaskSubmission |
| Runtime model | ActiveModelManager 继续唯一 reservation/validation owner；明确 Vision gate 和同一 admission snapshot，不创建 VisionModelManager |
| Provider | 在现有 Provider/OllamaProvider 中增加明确单图 execution 输入合同；不污染普通 ChatMessage、文本 API 或自动传历史图片 |
| Native API/security | 窄 Vision controller、exact DTO、native capability 和单一路由 body 上限；保留 Browser deny、task owner、no-store |
| Desktop | Core 窄 RuntimeClient.Vision；WPF 窄 image picker/preview 和 WorkspaceVision session owner；参考 existing file/operation 模式，不重构 Knowledge |
| Frontend | 既有 Assistant 中少量 typed 方法及结果状态；图片 bytes、路径、credentials、模型参数不由 React 控制 |

API 草案：

- `GET /api/v1/capabilities/vision/readiness`：native-only；有界 providerDeclaredVision / textValidated / visionValidated / available 与受控 reason；不触发 load/inference。
- `POST /api/v1/vision/tasks`：native-only exact JSON，候选字段 `question`、`image={mediaType,dataBase64}`、`expectedSelectionRevision`。
  Runtime 依据当前唯一 Active 捕获 model/runner/child digest；客户端不提交 Provider URL/model/runner override、文件路径或任意解码选项。
  stale revision 在有模型 egress 前受控拒绝；其不代替 Vision validation 状态检查。
- 成功沿用 202 + Location + TaskView，capability 为窄 `vision`，结果使用已有 String，保留 task UUID/owner GET/DELETE。
  native capability 集合需显式补充；Browser task ownership 和 Translate-only allowlist 均不扩大。
- 独立 native Vision validate action 绑定完整三项身份及 revision/session，不能由 Browser 或 React 自称 approval。
  使用现有 native management 控制面还是独立小端点，具体 action/shape 由 Guard 裁定。

优先 JSON/base64 是为了避免框架 multipart 自动落盘，并与现有 bounded JSON/policy 方式一致；
代价是约 4/3 编码膨胀与多份内存。只有该 exact native route 才可提高 body limit，不能全局提高 32 KiB。
先验证身份/权限，再按实际读取字节计数；Content-Length、chunked 或字段类型均不能绕过上限。
strict unknown/duplicate fields、base64 长度与解码后上限在 allocation 前后核验；无 URL、file reference、data URI 或 multipart 自动 spool。
认证失败/Browser deny 必须在大 body 消费前发生；413/400/409/503 等具体受控错误枚举待 API review，不复用会误导的领域错误。
这是新的 native capability/API amendment，未批准，不作为现有兼容 API 描述。

Bridge 候选仅 `vision.pick`、`vision.submit(selectionHandle, question)`、已提交任务 get/cancel/clear 等窄方法。
selectionHandle 由 WPF 生成、session-scoped，最多一张图；不是 Runtime 路径或任意文件读取授权。
图片选择取消零推理；替换/关闭/session replacement 清原生图像引用并抑制迟到结果。
已发 POST outcome unknown 不自动重发；如何在 POST 前由 native 确定 task ID 并只读查询需独立裁定，不能声称现有服务生成 UUID 已提供幂等提交。

## 5. 图像格式与资源预算（Proposal / Pending）

以下数值是保守候选，不是实测容量、安全认证或性能承诺。Guard 需确定实际 decoder/模型上下文和最坏内存后批准。

| 资源 | 候选上限/规则 |
| --- | --- |
| 图片数与格式 | 一张；仅静态 PNG/JPEG，按 magic + decoder 识别；拒绝动画/多帧、SVG/GIF/WebP/HEIC/PDF、损坏/不支持结构 |
| 原始 encoded bytes | 4 MiB；native handle 读取与 Runtime base64 解码后分别限额；不无限读再检测 |
| HTTP JSON body | 6 MiB，包含 base64 及 question/envelope；实际流计数，unknown/重复字段拒绝；上传阶段候选 15s deadline，需明确实现而非只设置 provider timeout |
| 原图维度 | 每边 ≤4096、总像素 ≤8,000,000；先 header 检查，long 安全乘法，再解码；32-bit RGB/RGBA 核心像素存储约 ≤32 MB，不等于整个 decoder/process 峰值 |
| 规范化模型输入 | 去 metadata、EXIF/ICC 等非必要内容；方向/色彩处理须确定；有界重编码 RGB，长边 ≤1024、≤1,048,576 pixels、≤4 MiB；明确告知缩放，不截断 bytes 假装合法图片 |
| Preview | 原生仅有界预览（候选长边 ≤512）；不能在 UI thread 直接解码任意原图；decoder 自身风险仍须评审 |
| 问题与结果 | question ≤1000 字符且 UTF-8 ≤4096 bytes；output ≤1024 tokens 且 UTF-8 ≤8192 bytes；plain text，不开放 HTML/tools；prompt/output/context 候选不可照抄文本字节估算 |
| 同时保留 | 每 session 1 张待提交图；Runtime 全局最多 1 个 Vision upload/decode/queued/running/draining 在途操作；与既有总 reservation/TaskManager capacity 一起检查，不新建 scheduler |
| 时间 | decoder 目标预算 5s，Provider request ≤120s、任务 execution ≤150s，继承 queue ≤30s；这些是不同阶段，不宣称 150s 覆盖整个上传/排队链路 |

Runtime 对 native 已检查图像仍独立解码验证，不信 filename/mediaType/dimensions。
先识别格式、有限 metadata、维度/帧数，再有界像素 allocation 和规范化；拒绝 decompression bomb、整数溢出、非预期 reader/特殊结构。
预算须覆盖 raw bytes + base64/string/parser + decoder scratch + raster + normalized bytes + HTTP payload + task captures；
只有像素上限不能证明无 OOM。失败关闭该操作，不试不同 decoder 或 silently enlarge limits。

**关键 Pending：** Java ImageIO 与 WPF/WIC 的 codec CPU/临时内存不能仅由像素上限、线程 future timeout 或 CancellationToken 硬限制。
5s 只能作为目标/协作 deadline，不能写成已证明强制终止 decoder。
Guard 必须裁定是否接受固定 PNG/JPEG readers 的有界输入/像素与剩余 codec 风险，还是要求窄、独立审核的解码隔离。
若要求硬 CPU/memory 隔离，先确定最小可实施 owner/机制；不在本候选中自动引入通用 helper/service 框架。
解码尚未退出不能释放在途资源计数并无限接受下一图；Provider 尚未可信完成更不能解除模型 guard。
decoder 选择、严格帧/metadata 检测、方向/alpha/色彩规则、image token/context 估算及 peak memory 是实施前必须裁定的具体问题。

## 6. 任务、故障与内容生命周期（Proposal）

图像任务与文本任务共用唯一 Active reservation 和 TaskManager。接收/解码需有在途限额；
profile/model/runner/child digest、question、规范化图像在 admission 后冻结，worker 不重查当前 selection。
网络和解码不在 model gate、TaskManager 或领域 DB 锁内执行。
拒绝/stale/invalid image 应为 zero image inference egress；outbound 前沿用 local-source/身份复验和 guard beforeSend。
已排队/运行/draining 的 Vision 同样阻止 switch/Release，不因 UI Cancel/Task TIMED_OUT 推定远端已停。
不自动 reload、卸载残留候选、重放推理或重启外部服务。

取消/超时分层记录：上传停止、未发送任务撤销、解码 cooperative stop、已发 Provider uncertainty 不能混同。
可信终态 + 本地 worker exit 与 malformed/partial response/断线的未知完成沿用 MMF；
纯 Memory/Conversation history/Knowledge CRUD 与词法查询在 Runtime/store 健康时继续可用。
相应受控 errors 与 Desktop strict parser 必须一起审查，不能为新错误放宽全部文本/领域 parser。

默认不持久化图像、问题或结果，不加 DB/schema、Conversation/Knowledge 附件、backup、历史、自动 cache 或磁盘 temp。
WPF/session 和 Runtime worker 内短期持有 bytes；任务终态后释放图像捕获，结果依既有 TaskManager 内存 retention 保留至到期。
当前 default retention 为 2m；UI 可在当前 session 展示结果，clear/close 后清引用，不能承诺 managed memory 物理擦除或外部 Ollama 不留驻留内部状态。
路径、filename、image/base64、question、answer、raw Provider response、凭据不进入 logs/exception diagnostics；只保留受控错误和必要非内容 metadata。
不借图像中的指令打开网络/文件/tools；结果只为用户参考，不写入 Finance 等业务事实。

## 7. GPU 与真实模型待核验项

用户环境 RTX 5060 8GB、RTX 3060 6GB 作为验收规划输入；本轮没有独立核对硬件、驱动、CUDA、当前 VRAM 或 residency。
不按参数规模、文件大小或模型名推定完整入显存。文字模型/MMF 的性能不能继承为 Vision 性能。
用户已安装并另行授权的一个明确候选才进入固定版本 tags/show/selected-child/source/projector 只读检查；
版本/runner 不受支持、来源或绑定不足即 STOP，不自动升级 Ollama/下载模型。
之后独立授权 synthetic image probe 与一次真实单图流程，观察 memory/residency、完成时延、是否 CPU/GPU 分担；Unknown 如实报告。
CPU fallback 若实际发生只记功能路径，不冒充 GPU 性能 PASS；两张卡分别记录，未运行的目标不得写 PASS。
首版究竟验证一台主机、两台都支持还是对另一台明确限制，由 Guard/用户定范围，不预设两套完整矩阵。
VRAM 不足、加载/图片 token 超限为受控 failure；不自动 Release 共享模型以腾挪显存。

## 8. 文本/Browser 兼容及最小验证计划

普通 Translate Single/Batch、Summarize、Ask、Conversation、Memory Ask、Knowledge Answer 的 request/prompt/output 合同保持。
选择合格 Vision Active 是全局模型切换，所有文本路径随 ONE Active 路由，不能声称旧模型仍服务文本。
Active publication/restart 仍更新 opaque cache epoch；图像内容不进入 Translate cache identity 或 Browser TaskView。
新增能力不向 Browser 暴露模型/runner/digest、图像上传、native validate 或新 scopes，不改变 Legacy/revoke guard。

**计划，NOT EXECUTED；没有最低测试数量或新增完整矩阵。**

- 优先扩既有 Provider/Manager 综合 fixture：单图正确 runner/child、Vision proof 不等于 metadata、失效后拒绝、trusted completion 与 cancel/unknown gate；保留一个能证明跨层身份安全的强流程，复用 MMF 历史证据。
- 一条 owning Runtime API/图像入口综合流程覆盖 native auth、Browser pre-body deny、body/像素/格式超限和 zero egress，以及 task owner/文本结果；只对 codec/上传独有的重要边界增加必要验证，不把每个预算参数排列成独立测试。
- 复用 WorkspaceBridge/operation 流程证明 session handle 权限、文件选择取消、替换/late suppression、unknown POST 无重发。Frontend 简单 rendering 无需额外用例；若仅布局变化，静态/手动检查足够。
- 真实 Windows 一个集成 flow：显式私有 Runtime/token/data/model-state 与独立 endpoint → 一次受控 Vision 准入/probe → native 单图选择/preview/提交/文本 → 一次既有文本操作确认路由 → bounded cancel/失败 UI。危险外部恢复与低显存故障可用 fixture 补充并明确等级；真实授权/隔离不足即 STOP。

只运行最终改动 owner 的最小聚焦验证；不重跑 MMF-3 全验收、Browser 全 suite 或 GPU/模型大型矩阵。
具体测试方法和实际 Windows evidence plan 在实施授权时定稿，不把这里的 coverage points 算作成功执行。

## 9. Architecture Guard 最小裁定清单

1. **准入子集及能力验证：** 固定 Ollama 版本、具体 model/runner/child/projector local-source 合同，paired synthetic probe 的通过标准；文本+图像共享 ONE Active 是否满足现有 selection/恢复格式。
2. **独立验证生命周期：** Vision-ready 的内存绑定/失效、独立 native exact-intent action 与安全 failure/uncertainty 语义；不得改变旧 text VALIDATE 及 guard 合同。
3. **图像安全与资源：** PNG/JPEG 严格 decoder、WPF preview 风险、数值预算/peak memory、上传 deadline、codec timeout 的剩余风险或窄隔离必要性、vision token/context 边界。
4. **最小 transport 与 UI：** native-only JSON 图像 route、exact body exception、capability/errors；WPF preview 与 session handle、未知 POST 的可核对 task 身份，不扩大 bridge/CSP/Browser authority。
5. **执行范围：** 一张图/文本结果/无持久化及最小验证流程；确定 RTX 5060/3060 各自支持声明、GPU vs CPU evidence 与实施/模型操作授权的独立门禁。

这些是新阶段候选待裁定事项，不是重开 MMF-3 关闭或新增其验收条件。裁定前不改实现、不操作模型。

## 10. 暂缓与后续交接

DEFERRED：视频、实时摄像头、持续屏幕监控/全系统截图权限、自动 OCR 平台、结构化票据导入、
多模态 RAG、图像向量库、Agent、Finance 图片识别、自动扫描私人目录、Browser 多模态、云 Vision、自动下载安装模型。
手动本地 PNG/JPEG 文件即使来自截图也只是普通显式图像输入，不授权任何 capture/monitoring 权限。
不预定 OCR/model/API 或 Finance tool schema。

Finance 正在独立修 bug，尚未冻结。后续依赖及安全迁移边界见 [Closing Record §4](MMF-3-CLOSING-RECORD.md#4-下一阶段与-finance-交接)：
Finance bug closing → 独立 Freeze → Home PC Migration/可信备份恢复与权限隔离 → F0 authoritative Reality Sync → 再定集成。
Finance 拥有金融事实权威；Workspace 不直连 PostgreSQL，F0 前不建 Gateway/adapter 或开放跨系统 Tool Calling。
本轮未访问 Finance/修改 Browser，也未修改 Java/C#/TypeScript/测试/配置、Runtime/token/ACL/selection/guard 或测试环境。

**STOP：等待 V1 Architecture Guard 独立评审。Scope preparation 不授权 implementation、模型操作或生产发布。**
