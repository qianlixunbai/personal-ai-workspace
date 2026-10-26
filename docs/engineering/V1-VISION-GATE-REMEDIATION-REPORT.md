# V1 Vision Gate Remediation

2026-10-27（Asia/Shanghai）。**SOURCE / MODEL-FREE FIXTURE READY FOR INDEPENDENT REVIEW；LIVE VISION BLOCKED。**

本轮完成获准的定点修复、官方源码研究和无模型 Windows 验证准备，不代表 Architecture Guard 批准、Vision Ready 或 Production GO。没有加载、切换、下载或更新模型；没有向 Ollama 发送真实或合成图片。原报告及历史失败证据保留。

## 1. 基线与实际修改

入场工作树干净，基线 `codex/finance-market-data-m12 @ c6b193e95c32e6c5d8c77e90165425c3aaaa79a0`；本轮新建 `codex/v1-vision-gate-remediation`。交付 commit 由该分支 Git history 标识，报告不自引用最终 commit。

生产修改仅两处：

- `OllamaVisionEnvironment.currentContext`：共享服务的最终采样原来仍只接受 0.40.1 hash，模板指纹也写死旧 hash，与已允许的 0.40.6 metadata/text identity 不一致。现在从 held file 得到完整 SHA，再与原有 `SHARED_SERVER_HASHES` 比较，模板指纹绑定此次实际匹配的 hash；不新增白名单来源，不重定向隔离 0.40.1 合同。
- 同一路径原来 hash 完每个安装模块即释放文件锁；现在复用现有 `HeldFile`，将 server image 和安装模块的禁止 write/delete 句柄保留到第二次 snapshot、文件身份复核和 policy lookup 结束，并在 finally 释放。它缩小采样窗口内文件替换风险，不宣称外部服务与最终 HTTP send 构成原子事务。
- `VisionTokenBudget.verify`：拒绝问题中的 `[img]`、`[img-`、`<__ollama_media_` 保留控制语法，返回既有 `INVALID_REQUEST / VISION_QUESTION`。官方 renderer 会把这些内容解释为媒体占位符；尤其 `[img-` 会抑制自动插入真正图片标记。此输入收紧保护单图绑定，不修改普通文本问答。

测试只扩展现有 owner：`VisionInputTest` 增加保留标记拒绝断言；`VisionIdentityTest` 增加一个实际 Windows 无模型子进程/文件句柄综合 fixture。没有改变生产可见性或为测试新增生产抽象。

`REVIEWED_TOKEN_POLICIES` 保持 `Map.of()`。context 8192、output 1024、图像与上传大小、Native Gate、唯一 ActiveModelManager、child digest、取消/uncertainty、Browser Translate-only 均保持。没有访问或修改 Finance、Memory、Knowledge 权威数据；没有更改持久格式或增加依赖。

## 2. WIC 与 held-handle 定点复审结论

重新读取 [P1-B 修复报告](V1-WIC-P1-B-FIX-CANDIDATE.md)、[原 remediation](V1-VISION-P1-REMEDIATION-CANDIDATE.md)、实际 codec/preview/bridge 及现有测试。

WIC codec SHA-256 `d72e5937e19afd0e8f7f2172e078610ec20121fceb1bd98b7f147e377db9bbf5` 与原 P1-B 清单一致。当前 `VisionImages.cs` 和 `VisionPreviewTests.cs` 已包含之后的 cleanup 后取消/deadline 复核，不能将旧 P1-B 三文件清单冒充当前全量身份。本轮未修改三个 WIC 文件；它们与本轮 Git 基线一致。

源码链仍为固定 Factory2 → 明确 PNG2/JPEG component → QI DecoderInfo → component CLSID → CreateInstance → 实际 decoder CLSID → Initialize → 同一 frame/scaler/numeric converter → detached frozen thumbnail。COM 使用和释放在同一 worker；取消/超时不提前归还仍在运行的 worker 槽。当前聚焦测试实际执行 PNG/JPEG、512×384 输出、CopyPixels 后取消、Initialize 失败和清理结束前禁止返回成功。

**WIC：具备独立定点复审条件，本轮没有取得独立批准。** 未发现需要改变该生产代码的新证据，因此保留实现。

新增原生 fixture 通过产品现有私有 interop 读取自己创建的 Java 子进程：中文/空格/emoji argv、PEB/RTL cwd/environment、父 PID、实际模块枚举、稳定 generation、文件 hash 后至第二次 snapshot 的写打开拒绝、释放后可写，以及进程退出后原 held handle 检查拒绝。它没有接触 Ollama 进程，也没有授予 Native Gate。

既有纯 sampler fixture 同时验证 generation、argv、module base/path、环境与角色模块集合漂移拒绝。**held-handle 源码和无模型 Windows 证据具备独立复审条件；真实 Ollama runner 的完整准入仍未验证。** 新 fixture 验证原生设施与受控采样，不冒充共享服务 `currentContext` 的正向生产运行。旧原生诊断里的权限缺失、partial read 等更广覆盖仅为历史证据，本轮未重复执行。

## 3. Ollama 0.40.6 的可靠证据

只使用官方 GitHub，未使用 Firecrawl。tag `v0.40.6` 实际解析至 `2476d5ead197abe04629651028f83d48dc75d2b2`；以下引用固定在该 commit。其 [LLAMA_CPP_VERSION](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/LLAMA_CPP_VERSION) 指定 `b11669`，官方 tag 解析至 llama.cpp `620d2225cc436ab0b001e3200e590e3b286ef9ab`。[构建配置](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/llama/server/CMakeLists.txt) 还会链接 Ollama compat，不能只凭 llama.cpp tag 认定某个已安装 DLL 的来源。

### Payload 与模板

Java 现有 `/api/chat` 使用完整 child wire reference、显式 `llamacpp`、单张规范 PNG、system/user messages、`think=false`、`num_ctx=8192`、`num_predict=1024`。本轮只静态核对和 fake HTTP 验证，没有向真实服务发送此 payload。

固定 config 的 renderer/parser 是 `qwen3.5`。官方 [chat routing](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/server/routes.go) 对显式 renderer 走 Go render/Completion；不是任意 GGUF Jinja 模板路径。[renderer registry](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/model/renderers/renderer.go) 与 [Qwen renderer](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/model/renderers/qwen35.go) 表明 `think=false` 仍有空 think prefill，角色分隔、换行及固定 system 也必须计入预算。

[图片占位符处理](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/model/renderers/image_tags.go) 生成稳定图片标记；[llama-server wrapper](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/llm/llama_server.go) 将其替换为进程随机 marker，并将 Base64 图像放入 `/completion` 的 `prompt` 对象，字段为 `prompt_string` 和 `multimodal_data`。PNG 数据在该 wrapper 中不再经 WebP 转换。`num_predict` 被传为有限生成预算。

### 预处理与图像 token

本机固定 projector 的完整 hash 本轮重新匹配；静态 GGUF metadata 为 `qwen3vl_merger`、patch size 16、spatial merge 2、RGB、mean/std 各 0.5。`embedding_length=1024` 是特征维度，**不是图像 token 数证明**；metadata 的 image_size 768 也不能直接当作输入尺寸合同。

官方 [clip.cpp](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/clip.cpp) 为该 projector 使用动态尺寸及默认 8..4096 图像 tokens，允许显式参数/metadata 覆盖；[clip-model.h](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/clip-model.h) 将该范围换算为像素界限。[预处理](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/mtmd-image.cpp) 按 patch×merge 对齐、在像素界限内 bicubic resize；[mtmd](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/mtmd.cpp) 添加 vision begin/end 文本 tokens。

**有条件的源码推导：**对本应用固定 1024×1024 规范 PNG，在上述 metadata/defaults 且没有有效预处理覆盖时，对齐尺寸仍为 1024×1024，图像 embedding 数为 `(1024 / (16×2))² = 1024`。此结论来自尺寸与 patch 公式，不来自 embedding_length。vision 边界、system、角色分隔、think prefill、BOS/EOS 等仍须另计；尚未用真实 runner 验证此条件集合。

[服务端 prompt.go](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/server/prompt.go) 的 768 image tokens 明确仅作历史消息截断启发式，不是 projector-aware token 上界。Qwen 最少 1024 tokens 的 grounding 提示也不是通用最大值；不能拿它直接填 policy。

### 本机只读观测

本轮只 GET `/api/version` 和 `/api/ps`：0.40.6，驻留模型为空；进程枚举只有 Ollama service，没有 llama-server。没有为采证自动加载模型。已安装 server 为 27,944,328 bytes、SHA-256 `71d2002d3ae572d5fa8c1d368ce9d1b9a39c7187cf169e8b330c55410bf6c58e`，本轮 Windows Authenticode 检查为 Valid。

完整 manifest list、llamacpp child、config、language、projector、license、params 的大小及 SHA 全部重新匹配既有固定值。child 为 `96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7`；projector 为 `db3830bf825a26fcfed35416378696a47e0cbcf49c5b652a6fe9db90541f86d9`。这是磁盘静态证据，未将权重交给模型 runtime；安装目录磁盘 inventory 也不等于已加载 DLL inventory。

## 4. 最小可信准入合同与剩余 STOP

保留现有 `VisionTokenBudget.Evidence` 和三类 fingerprint，不引入新 policy API 或外部配置入口。最少仍须同时具备：

1. 固定 server/child/config/模型组件来源与当前 held process generation；Native Gate 和唯一 Active owner 有效。
2. 当前实际运行的 native payload 完整闭包，以及与审查来源对应的 DLL 全 SHA；不能把安装目录扫描或 fake hashes 当作加载证明。
3. 有效 argv、环境及其他预处理 override 的审查合同。尤其共享路径当前只把 argv 纳入 preprocessing fingerprint，未像隔离合同一样校验环境；继承的图片处理设置不能被忽略。隔离 0.40.1 的 role validator 不能直接视作共享 0.40.6 的证据。
4. 固定 renderer、实际 tokenizer、system/user 内容上界、vision/role/think/BOS/EOS 计数及输出保留空间的完整可复算依据，满足 `text + template/special + image + 1024 output + margin <= 8192`。单个 1024 图像推导不满足全部合同。
5. 独立审查确认同一个真实 payload/preprocessing/template tuple 后，才可新增最小正向 policy。真实 paired execution proof 随后独立获得，不能由 metadata、fixture 或磁盘哈希替代。

当前阻止填写 policy 的直接事实是**无已加载 runner，无法取得其实际 payload/有效预处理身份**；此外共享环境覆盖与完整模板/tokenizer 上界合同仍需定点审查。不会靠添加猜测常量、复用 0.40.1 指纹、取消身份检查或扩大预算解锁。

因此：**现有 Java Runtime → VisionController → ActiveModelManager → Provider，以及 WPF/React 候选链已具备继续定点复审和无模型准备的条件；尚不具备执行真实单图验收的准入条件。** 本报告不授予 Source Approval、Vision Ready 或发布批准。

## 5. 本轮实际执行与未执行

实际 PASS：

```powershell
dotnet test desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj --no-restore -p:FrontendSkipBuild=true --filter 'FullyQualifiedName~VisionPreviewTests|FullyQualifiedName~WorkspaceVisionTests' --logger 'trx;LogFileName=vision-desktop.trx' --results-directory .verification/v1-vision-gate-remediation-20261027

$env:JAVA_HOME='D:\MC\JAVA\JAVA21'
.\mvnw.cmd -o -pl workspace-application -am '-Dtest=VisionIdentityTest,VisionInputTest,VisionFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Desktop 的实际 WIC/fixture 和 Java 的输入、token gate、身份漂移、fake HTTP integrated flow 均通过；无失败/跳过。Java native fixture 在本机 Windows 实际运行，未被条件跳过。Maven reactor 只构建依赖，没有执行 Finance tests。复用既有 frontend assets，没有重建或测试未修改的 React。

证据位于 ignored `.verification/v1-vision-gate-remediation-20261027/`：`vision-desktop.trx`、`java-focused.log`、三个聚焦 XML、`wic-source-comparison.json`、本轮静态组件/metadata/签名观测和固定版本 `upstream-source-index.json`。最终文件 hashes 和差异记录见同目录 `delivery-evidence.json`、`candidate.patch`。审查远端时以此分支实际源码、上述命令和官方固定链接为准；ignored 本机证据不随 Push 发布。

开发执行器早期出现 Windows sandbox ACL 启动错误；切换完全访问后已恢复。本轮一次证据采集 PowerShell 命令有解析错误，未执行任何采集；纠正后静态采集成功。没有把这些失败当作产品缺陷或测试 PASS。

未执行：真实 digest 文本请求、模型管理/加载/切换、合成图 VISION_VALIDATE、用户图片、正式 WPF picker/窗口/session 端到端、内存/GPU 峰值、全量 Java/Desktop/Frontend/Browser suite、发布打包或正式发布。

## 6. 下一轮最小操作边界

先独立定点复审本分支 WIC/原生身份补丁、上述静态推导及共享环境覆盖缺口。当前没有驻留 runner，下一项需要单独明确授权的模型操作为：**仅通过既有 ActiveModelManager/native 管理链，对同一固定 4b child 做一次文本验证以加载其 runner，再只读采集实际 argv/环境/loaded modules 与 generation；不发送图片、不更换来源、不允许并存第二 Active。** 若需要切换 Active，须把当前选择和目标完整 digest 纳入明确授权；不能为了采证暗中切换或使用旁路 `ollama run`。

取得上述证据并完成完整 token 合同的独立审查后，才补最小 policy entry，再单独授权现有 paired synthetic proof 和一次真实 Windows 单图→纯文本→既有文本兼容→取消/失败综合验收。paired proof 本身有两个合成图片请求，不能把“单图验收一次”误解成总共只发一个图片请求。

本轮 Commit/正常 Push 仅交付源码候选，不 Merge main、不改历史、不建 Tag/Release。**通过的是无模型准备；真实 Vision、Windows UI、GPU 和 Production 仍 BLOCKED / NOT RUN。**
