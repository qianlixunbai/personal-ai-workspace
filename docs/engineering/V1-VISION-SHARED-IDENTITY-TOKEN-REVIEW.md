# V1 Vision — Shared Identity and Token Budget Review

2026-10-27，Asia/Shanghai。**最小源码修复候选；等待独立 Source Review。真实 Vision 保持 fail closed。**

基线：`codex/v1-vision-gate-remediation @ b05fb00db253d6c5e0c6550ddb32661a6855aede`，入场工作树干净。本轮继承本地 `runner-evidence-report.md` 中已经完成的手动文本 VALIDATE 和 runner 证据；不把它们列为本轮重跑。此前的自动审批拒绝和历史失败记录保留。

## 1. 阻塞裁定

| 阻塞 | 本轮结论 | 尚需独立裁定 |
| --- | --- | --- |
| OBS Hook 来源与加载 | 签名、固定哈希、Vulkan manifest/注册表及官方机制已经核对，加载有可解释来源 | 精确安装包/安装主体未证实；受 Hook 影响的 server 不取得 Vision 准入。不得仅凭签名扩大允许目录 |
| 共享路径漏绑 server 模块/环境 | 已完成最小修复并通过 owner 聚焦验证，可关闭实施缺口候选 | Source Approval；补丁未部署，正向政策仍为空 |
| 8192 下 token 上界 | 固定模型词表与固定源码下的条件性上界已可复算，合计 6912 | 实际 native 构建来源、有效预处理条件及新指纹 tuple 的独立批准；不能将条件性证明写成当前环境已获准 |

下一步进入一次独立的源码/环境/预算合并审查；不重复 WIC、文本验证或无关架构研究。

## 2. OBS 只读调查

本机 `C:\ProgramData\obs-studio-hook\graphics-hook64.dll` 为 291656 bytes，版本 `1.8.3.0`，产品 OBS Graphics Hook、公司 OBS Project。SHA-256 与既有 runner 证据一致：

`30ad28bd1ebf039c0264da0945e2f66236952a99a2842b1c6f9af8dba978e0a1`

Windows Authenticode 返回 Valid，签名者 `CN=Hugh Bailey, O=Hugh Bailey`，证书 thumbprint `4C400F6B3A5E3C72C3F80D3EF296C26713E14303`；另有 DigiCert timestamp 证书。文件时间不等同于已验证的安装日期或签名时间。

64/32 位 HKLM `SOFTWARE\Khronos\Vulkan\ImplicitLayers` 分别将 ProgramData 下的 `obs-vulkan64.json` / `obs-vulkan32.json` 注册为 DWORD 0。本机 64 位 manifest 指向 `.\graphics-hook64.dll`，GLOBAL 层名 `VK_LAYER_OBS_HOOK`，入口 `OBS_Negotiate`，声明 `DISABLE_VULKAN_OBS_CAPTURE=1` 可禁用该层。旧采样同时列出 server 中的 `vulkan-1.dll` 和 OBS DLL，runner 中没有两者。

官方 [updater](https://github.com/obsproject/obs-studio/blob/31.0.3/UI/win-update/updater/init-hook-files.c) 会把 hook DLL/manifest 复制到 ProgramData 并注册全局 Vulkan 层；官方 [manifest](https://github.com/obsproject/obs-studio/blob/31.0.3/plugins/win-capture/graphics-hook/obs-vulkan64.json) 与本机语义一致；[Vulkan 实现](https://github.com/obsproject/obs-studio/blob/31.0.3/plugins/win-capture/graphics-hook/vulkan-capture.c) 提供协商和 CreateInstance 拦截。因此 Vulkan 层加载是与观测一致的解释，不要求 OBS GUI 当时运行。没有历史加载调用栈，不能证明这是该次 DLL 加载的唯一机制。

本机目录由 Administrators 所有，但现有 ACL 授予 BUILTIN\Users 写权限；官方 updater 也包含相应目录授权代码。这说明它不是 Workspace 的私有受控发行目录。签名当前有效不证明文件永远不被替换，也不表示其进入 server 地址空间后只具有读取 GPU 帧的权限。

有界检查未发现 OBS 卸载注册记录、Appx 包、当前 OBS 进程、默认 Program Files 安装或 Start Menu 入口。未搜索全部磁盘。不能据此认定它从未安装，或已卸载；也未取得与这个完整 DLL SHA 对应的官方发行包。所查 OBS 31.0.3 [hook 版本头](https://github.com/obsproject/obs-studio/blob/31.0.3/shared/obs-hook-config/graphics-hook-ver.h) 为 1.8.6，不能冒充本机 1.8.3 的精确构建来源。

**关闭的是机制不明这一调查缺口；完整供应链来源及执行信任决策仍未关闭。** 本轮没有删除/卸载 hook、改注册表/ACL、设置环境变量或停止外部进程。

## 3. 最小共享身份修复

生产变更只在 `OllamaVisionEnvironment` 的共享路径，复用既有 `ProcessState`、`Snapshot.roles/roleModules`、`HeldProcess`、`HeldFile` 和 stable sampler：

- 同一 held server/runner 读取完整进程状态与各自模块集合，核对 parent、listener、argv 与两次完整状态；环境或模块变化会使采样拒绝。
- 安装目录 payload hash 区分 server/runner 两个角色，覆盖二者实际加载的安装模块；两边均只保留原有安装目录、Windows System32/WinSxS 目录规则。额外 ProgramData OBS DLL 会被拒绝，没有增加签名例外或允许目录。
- preprocessing hash 绑定去端口 argv、两端 executable/cwd，以及 Ollama、LLAMA、GGML、MTMD、CLIP、GPU/backend/loader 等相关环境、PATH、SYSTEMROOT/WINDIR、兼容层和 Vulkan disable 参数。参数 namespace 内的未知选项也参与指纹，不能复用已批准 tuple。
- 正确形状的 runner `LLAMA_MEDIA_MARKER` 仅在跨 generation 的政策指纹中归一化；server 不能提供它，非法/缺失 marker 拒绝。原始精确值仍参与同一采样的完整前后比较。端口原有归一化规则保留。
- 安装模块禁止 write/delete 的句柄继续保留至最终快照和政策查找结束。保持原有外部服务采样与 HTTP send 非原子这一限制。

此修复改变共享 payload/preprocessing 指纹定义，不复用旧候选指纹。没有更改政策 API、持久格式、原生确认、WIC、context/output、隔离 0.40.1 合同或任何 domain。`REVIEWED_TOKEN_POLICIES` 继续为空；早期空政策拒绝仍有效，因此本轮没有触发真实共享正向执行。

## 4. Token 审查：证明范围与计算

固定来源沿用 Ollama `2476d5ead197abe04629651028f83d48dc75d2b2` 和其指定的 llama.cpp `620d2225cc436ab0b001e3200e590e3b286ef9ab`。已缓存文件与既有 source-index 中的 Git blob SHA 核对；不是对浮动 main 的推测。实际 DLL SHA 到这些源码/构建参数的供应链批准仍需独立完成。

**文本上界。** 只离线读取固定 language GGUF 的 metadata，未运行模型或加载 tokenizer DLL。模型 tokenizer 为 gpt2 / qwen35；256 个 GPT-2 byte 字符全部在词表中，247587 条 merge 的两个操作数和拼接结果均在固定词表中。结合固定 [byte 编码实现](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/src/unicode.cpp) 和 [BPE 实现](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/src/llama-vocab.cpp)：初始一个 UTF-8 byte 对应一个已存在符号，merge 只减少符号数且不会落入缺失 token 的再分解。因此普通文本 token 数不超过 UTF-8 byte 数；非空 special 字面量被识别为单个 token 也不会增加此上界。应用问题限制为最多 4096 UTF-8 bytes，所以 textBound=4096 是条件下的上界，不是平均 token/字符估计。

**模板及特殊 token。** 固定执行仅有一个 system 和一个 user，无 tools/history，think=false。固定 [qwen3.5 renderer](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/model/renderers/qwen35.go) 仍会产生 empty-think prefill。逐项拼接固定 system、角色分隔、换行、prefill 和最多一个图片前空格，固定文本共 171 bytes。服务端使用 [图片 tag](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/model/renderers/image_tags.go)；[wrapper](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/llm/llama_server.go) 替换为进程 marker 并以 multimodal_data 交付，Base64 不是文本 token。保守以两个 vision 边界字面量的 30 bytes 计数，并为 [mtmd](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/mtmd.cpp) 最多一次 BOS/EOS 额外保留 2 tokens，得 203；选取 templateBound=256。没有把 runner 的 chatml flag 当作独立模板证明。

**图像上界。** 应用始终输出一张 1024×1024 RGB 规范 PNG。固定 projector 为 qwen3vl_merger、patch=16、merge=2，未包含 image min/max pixels metadata。在固定 [clip](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/clip.cpp) 的 8..4096 默认图像 token 范围且没有有效 override 时，像素范围为 8192..4194304；1024² 位于其中，并已按 32 对齐。固定 [resize](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/mtmd-image.cpp) 保持该尺寸，embedding 序列为 `(1024/(16×2))² = 1024`。这来自尺寸、预处理与 patch 公式，不来自 embedding_length。vision 边界已在上项另计。

固定 native [参数解析](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/common/arg.cpp) 支持 `--image-min-tokens` / `--image-max-tokens` 及 `LLAMA_ARG_IMAGE_MIN_TOKENS` / `LLAMA_ARG_IMAGE_MAX_TOKENS`；旧证据未见这些覆盖，但它不是已批准的最终 native 参数读数。没有据此宣称当前 DLL 的编译默认值已经独立证明。

| 预算部分 | 条件性上界/保留量 |
| --- | ---: |
| 用户文本 | 4096 |
| 固定 system/template/special（203 向上留至 256） | 256 |
| 单张规范图的 image embeddings | 1024 |
| Vision num_predict/output | 1024 |
| 显式保留 safety margin | 512 |
| 合计 | **6912** |
| 相对 8192 的余量 | **1280** |

512 是明确扣留的余量，不是未知行为的估计值或免责兜底；全部前提仍必须成立。原文本 `translate.fast` 的 output=2048 不变。这里没有增加正向 `Evidence`、政策 entry 或改变准入常量。

**可关闭固定源码/静态词表下的数学缺口；实际环境的 token 准入缺口仍不能关闭。** 缺的不是再举几个图片或文本例子，而是批准实际 native 来源、有效环境/预处理与新身份 tuple 的对应关系。

## 5. 实际验证、证据与下一步

实际执行并通过 owner 聚焦验证：

```powershell
$env:JAVA_HOME='D:\MC\JAVA\JAVA21'
.\mvnw.cmd -o -B -ntp -pl workspace-application -am '-Dtest=VisionIdentityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

在收窄与计算无关的 DISABLE 环境 namespace 后，仅重跑受影响的 `VisionIdentityTest#sharedIdentityBindsServerModulesAndEffectiveEnvironment`，PASS。新增一个长期安全回归，覆盖 server 注入模块拒绝、两端有效环境改变无法复用指纹，以及合法生成 marker 归一化/非法 marker 拒绝。既有稳定采样和无模型 Windows held-object fixture 也在首次 owner 运行中实际通过。没有跑 Java 全量、Desktop、Frontend、Browser、WIC 或真实 Provider 测试。

本地 ignored 证据：`.verification/v1-vision-policy-review-20261027/` 中的 `obs-origin.json`、`token-audit.py/.json`、`upstream-source-index.json`、`java-focused.log`、`java-final-focused.log`；runner 证据继续指向原目录，没有重采或覆盖。脚本只读 GGUF metadata，不读取权重进行执行；其输出是静态条件审计，不冒充 native tokenizer 实测。

独立审查最小清单：

1. 批准本次两端身份绑定修复，以及固定来源下 6912 的条件性预算证明；不要求重复 WIC 或已完成文本验证。
2. 裁定受 OBS Hook 影响的共享服务应如何取得干净 generation。可供另行审批的最小操作方案是利用官方 manifest 的进程级 `DISABLE_VULKAN_OBS_CAPTURE=1`，在用户协调共享客户端后启动新 server；本轮未执行，也未修改全局配置、注册表或 ACL。不尝试从当前进程动态卸载 DLL，不增加 OBS 允许项。
3. 环境操作获批后，只有确需新的有效 generation 时才安排一次完整只读采样。旧证据的相关环境投影未保存 SYSTEMROOT/WINDIR 的值，且旧指纹没有 server 模块/环境；不能猜默认值或用旧 tuple 填新政策。当前不重新请求 VALIDATE 或任何模型执行。
4. 上述实际 tuple 和有效预处理获批后，才提交最小正向政策的独立审查及首次受控 paired synthetic 验证授权；paired 验证是两个图片请求，本轮为零。

没有重新 VALIDATE、图片请求、VISION_VALIDATE、模型切换/下载/更新、Release/Recover 或新 runner 采证。没有使用 Firecrawl，没有更改 Finance、Memory、Knowledge、WIC 或任何系统设置。生产补丁尚未部署；无 commit、push、tag、release，保留可审查工作树差异。
