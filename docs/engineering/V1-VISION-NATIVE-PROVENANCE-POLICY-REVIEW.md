# V1 Vision — Native Provenance and Minimal Policy Review

2026-10-28，Asia/Shanghai。独立证据审查，限定本机 Development-only。

基线：`codex/v1-vision-gate-remediation @ ddb0fc976c34c87073ea783b613362c3cc673f92`；入场工作树干净。
本轮没有修改生产代码或正向 policy，没有执行模型请求、模型操作或进程启动。

## 裁定

| 项目 | 结论 | 准确范围 |
| --- | --- | --- |
| Native provenance | **GO** | 实际 held sample 的全部 15 个安装文件与官方固定发行包逐文件 SHA-256 相同；接受官方发布者及其构建链作为信任基础 |
| Effective preprocessing | **GO** | 固定二进制、模型组件和已捕获 argv/environment 下，源码可确定有效默认值；不是 live image/token 实测 |
| Actual identity tuple | **GO** | 独立离线复算三项 hash 与真实采样一致，相关环境值无缺失；批准该记录中的精确 tuple |
| Minimal positive Vision policy | **APPROVABLE** | 可以另轮实施一条固定 child/tuple 的预算证据；本轮 policy 仍为空 |
| 进入下一轮最小 policy 实施 | **具备条件** | 无剩余供应链或预处理 STOP；实施交付和首次图片执行仍是后续步骤 |

以上批准不等于 Vision 执行已验证。Clean Generation 和纯文本 VALIDATE 沿用已有真实证据；本轮未重跑。

## 1. Native provenance：从实际文件到官方构建

依据 [Clean Generation 报告](../../.verification/v1-vision-clean-generation-20261028/clean-generation-report.md)、
[完整 held identity](../../.verification/v1-vision-clean-generation-20261028/full-runner-identity.json) 和
[采样审计](../../.verification/v1-vision-clean-generation-20261028/identity-binding-audit.json)。
原样保留 Server/Runner generation、两端完整模块清单和 88 个唯一文件的 SHA，不重新采样。

本轮从官方 [v0.40.6 release](https://github.com/ollama/ollama/releases/tag/v0.40.6) 取得资产元数据。
`ollama-windows-amd64.zip`：asset ID `649113621`，大小 `1471164488` bytes。
发布的整包 SHA-256 为 `e359637575302f288d76c0007c408a4be9c7376d59c4f28684d63b232a44b427`，
官方 `sha256sum.txt` 与 API 所列值相同。**该整包 SHA 仅作为发布方声明保存，本轮没有下载整包或独立验算整包 SHA。**

实际核查方法为：官方资产 URL → HTTPS redirect 到 `release-assets.githubusercontent.com` →
校验 `206 Content-Range` 的 ZIP 目录及选定 member → 解压并通过 ZIP CRC → 对完整解压文件计算 SHA-256 →
与原 held-process sample 对比。读取约 552 MB 的 Range 数据，未落地或执行发行包中的 EXE/DLL。
以下 15 个安装文件全部相同；这次是与官方发布内容比对，而非重复与历史安装文件比对。

| 相对安装目录路径 | 实际采样及官方 member 的相同 SHA-256 |
| --- | --- |
| `ollama.exe` | `71d2002d3ae572d5fa8c1d368ce9d1b9a39c7187cf169e8b330c55410bf6c58e` |
| `lib/ollama/llama-server.exe` | `bb3d2f86e3d8907c4febc3d7a299a8d13d95ddc281c5f81fe496e0d32b12dca3` |
| `lib/ollama/libllama.dll` | `18aa7d8ffb2f84fb1453595fea31d9b848adeb77292180e46df30393ea9ba44f` |
| `lib/ollama/libllama-common.dll` | `73cee75ae67f3876548551e377109506f86ea9b912dc14faa8d5166a72e9594e` |
| `lib/ollama/libllama-server-impl.dll` | `175498a864ea95d8d72ffbb018127b411549622c9c27c836c2c67a381bc823e2` |
| `lib/ollama/libmtmd.dll` | `5f5ae1a2c9137ab3dc227abf6b9df5726a2266bc6f6d86cb9fe9f419917c1628` |
| `lib/ollama/ggml.dll` | `1855a5bb33cc4a42e3fe5b9e6a9eac7c5e4b969366abe472337750e31b5d16cd` |
| `lib/ollama/ggml-base.dll` | `da9913762648d3d97829245096c9388a229e5b8e9a40d49513a56d69803a2a2c` |
| `lib/ollama/ggml-cpu-zen4.dll` | `ec9beeac17d98f3de9cd115800a40057c3f8e334c59c35de39d6a9d3943c7e25` |
| `lib/ollama/libc++.dll` | `2ca4c00c8b4f7daf8965d93feb92ec275c3355286b679cc67687c0ac5b7da81d` |
| `lib/ollama/libomp.dll` | `132279c0558a11ed8e686af29576af9fc25bba759dac20410102f94c5e45a14a` |
| `lib/ollama/libunwind.dll` | `9b3a107b54885224edeeba178e23ac2c441d1b24a44fb9d9b8c5b8c9ea073c71` |
| `lib/ollama/cuda_v13/ggml-cuda.dll` | `3a9ccab9ae606b34aa89ca732545aa45dd3a38e403b6e31d9cb58977c0eef198` |
| `lib/ollama/cuda_v13/cublas64_13.dll` | `dadfa5a56d50a25909ffd5950695aacaa3413dc2bf5dac4fb632eba9c1d59768` |
| `lib/ollama/cuda_v13/cublasLt64_13.dll` | `ef07c759475d69a7802112d3151c1fb1d87fe7479920db32097cf580c4b1489f` |

官方 tag refs 本轮再次核对：Ollama `v0.40.6` → `2476d5ead197abe04629651028f83d48dc75d2b2`；
其 [LLAMA_CPP_VERSION](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/LLAMA_CPP_VERSION)
为 `b11669` → llama.cpp `620d2225cc436ab0b001e3200e590e3b286ef9ab`。
只读解析本机 Server 的 Go build 信息，得到精确 Ollama revision、`vcs.modified=false`、Windows/amd64；
`libllama-common.dll` 中含 `620d2225c`。这些是旁证，未单独拿版本字符串代替文件比对。

同一 Ollama 提交的官方 [release workflow run 38986144988](https://github.com/ollama/ollama/actions/runs/38986144988)
为 success，head SHA 与固定提交相同；Windows CPU、CUDA 13、CLI、组包和 release jobs 均有成功记录。
固定 [workflow](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/.github/workflows/release.yaml)、
[Windows build script](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/scripts/build_windows.ps1) 和
[CMake presets](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/llama/server/CMakePresets.json)
对应 Release、shared libs、动态 backend、CPU variants（Windows OpenMP）和 CUDA 13.0 / `cuda_v13`。
实际 loaded `ggml-cpu-zen4`、CUDA backend、cuBLAS 与这一分发结构吻合。

[Server CMake](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/llama/server/CMakeLists.txt)
读取固定 llama.cpp tag，经 [compat.cmake](https://github.com/ollama/ollama/blob/2476d5ead197abe04629651028f83d48dc75d2b2/llama/compat/compat.cmake)
应用 `001-llama-cpp-hooks.patch`，并把 Ollama compat 源码链接入 llama，mtmd loader 调用其 metadata/tensor hooks。
本轮已检查 patch、compat 实现及其实际分支，未将安装包当作无补丁的原生 llama.cpp。

**合理供应链验收标准：**本机开发准入接受“可信官方发布通道的精确文件相同 + 固定源码/构建配置 +
同提交成功构建记录 + 版本/签名旁证”，以及原有 Windows/System32/WinSxS 平台信任边界。
本轮不要求可重复构建逐 bit 一致、全部工具链/驱动源码证明、历史安装主体证明或不存在任何同 SID 干预的绝对保证；
也不声称取得了逐 DLL 的独立 SLSA attestation。这些更强事实不是当前 MVP 的必要门槛。
对 OS/driver 文件继续使用原有目录信任合同，88 个文件并非都应映射到 Ollama 发行包。

## 2. Effective preprocessing：批准实际条件，不重做旧数学证明

源码入口为固定 llama.cpp 的 [common defaults](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/common/common.h)、
[arg parser](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/common/arg.cpp)、
[server context](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/server/server-context.cpp)、
[clip](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/clip.cpp) 和
[image preprocessing](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/mtmd/mtmd-image.cpp)。

- 实际 argv/environment 无 image min/max overrides；common/mtmd 未指定值均为 `-1`，进入模型默认分支。
- 固定外置 projector SHA `db3830bf825a26fcfed35416378696a47e0cbcf49c5b652a6fe9db90541f86d9`：
  `general.architecture=clip`，`qwen3vl_merger`，patch=16、merge=2，无 image min/max pixels metadata。
  Compat 的 monolithic `qwen35`/`qwen3vl` detect 不匹配该 `clip` 文件；不会将其他模型分支的像素默认值注入这里。
  Compat 的 `maybe_clip_mmproj_embd` 特例仅针对 `gemma4a`，不改变这个 projector。
- 有效 image tokens 范围 **8..4096**；对应像素 **8192..4194304**。
  动态 resize 保持比例、按 **32 pixels** 对齐，算法 bicubic；metadata 中 `image_size=768` 不强制将本图缩到 768。
- 已检查 server 对 mixed/non-causal 图像的 `n_ubatch` cap 分支。此 Qwen35 为 causal、有 memory object，
  QWEN3VL 的 `mtmd_decode_use_non_causal` 为 false，该 cap 不触发。
  实际 `-b 1024 -ub 1024`、`LLAMA_ARG_FIT_TARGET=1669` 和 CUDA backend 已绑定；不能仅由 ubatch 数猜测 image max。
- 单张规范 1024×1024 RGB PNG 在该范围内且已对齐，输出仍为 1024×1024，既有 imageBound=1024 的条件成立。
  已审查 compat Qwen35 text fixes：它们不替换这份词表/BPE；Go renderer 路径仍由精确 Server hash 和 config 绑定。

因此沿用 [已批准条件性预算](V1-VISION-SHARED-IDENTITY-TOKEN-REVIEW.md)：
`4096 text + 256 template/special + 1024 image + 1024 output + 512 margin = 6912 ≤ 8192`，余量 1280。
本轮只关闭其实际构建和有效预处理前提，没有重新运行词表审计、token 数学脚本或图片处理。
这是由精确官方二进制对应源码、固定 metadata 和实际控制输入推得的有效配置；没有冒充读取了 live 内部 hparams 或实测 image tokens。

## 3. Actual identity tuple：独立复算 GO

独立使用已序列化模块路径/SHA、角色、argv、cwd 和安全环境投影，按实际产品公式离线重建：
payload 为角色区分的安装模块有序 map；preprocessing 为固定 policy + payload + 去端口 argv + 两端 image/cwd/相关环境；
template 为实际 Server SHA + 固定 config 的 Go-renderer 公式。
两端相关环境名称都有对应值；Runner 的合法生成 marker 采用产品同样的 `<generated-per-runner>` 归一化。
两端 bound-environment hash 及最终三项 hash 均与原生采样记录相同。

```text
identity      qwen3.5:4b/llamacpp/96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7
payload       a96cfee97137af1a40095ce2cb588b7a7ba768cf00851680b0991fcb65004fc8
preprocessing 5864d22b4d6489f43edcbe4542c02a35e0588c5dd41977f659506b50563517af
template      2d965ad8913863ddd9a9e334f881c70f326ac79368b1b129076e71c1f65f4267
policy        v1-png-rgb-white-square-1024-context-8192
```

固定 child → config/语言/projector 的 closure、完整组件 hash 和文本 owner 完成状态复用既有证据，未重读全部权重。
本轮核对了 Clean Generation manifest 的 22 个证据文件，并以官方固定 commit tree 的 Git blob IDs 核对 26 个相关缓存/新增源码。
这不是再次运行 native sampler 或 `currentContext` 正向分支；原诊断未执行 policy lookup。
新 generation 只有在重新通过产品实际采样并匹配该 tuple 时才能使用此 entry，不能把历史进程存活当作当前事实。

## 4. 下一轮最小实施与首次 paired synthetic 的边界

最小实施只需在 `OllamaVisionEnvironment.REVIEWED_TOKEN_POLICIES` 加入上述一个精确 key 和一个
`VisionTokenBudget.Evidence(identity, payload, preprocessing, template, POLICY, 4096, 256, 1024, 512)`，
并更新该处过时的“尚无证据”注释。保留现有 `SHARED_SERVER_HASHES`、token gate、原生采样、目录和 Native Gate 合同；
无需修改公开 API、context/output、持久格式或引入新策略框架。
已有独立 0.40.1 分发及其他 tuple 不取得这条 entry 的授权。

实施验证优先复用现有 token-boundary、共享身份与 fake HTTP 集成证据；
对新增真实 entry 的绑定如有覆盖缺口，只补一个必要的数据准入回归，并校正测试中专属空 policy 的假设/注释。
不扩张测试矩阵或重跑全套。部署时按既有可信 Runtime/embedded source manifest 构建链交付并核对实际产物。
本报告不是这一步已实施或已测试的声明。

首次 paired synthetic 必须另有明确图片执行授权，并满足：

1. 正向 entry 的实际源码/构建交付完成；原有 Development Native Gate 正常通过。
   当前 owner 为精确 child，text Ready，selection revision 匹配；无 uncertainty/error/guard/switch，reserved/queued/executing/draining 均为零。
2. 现有干净 Server/Runner 在使用时通过真实身份采样和精确 tuple 查找；只以现时检查判断驻留与有效性。
   若已卸载或退出，STOP 并单独处理 lifecycle 授权，不为验收自动加载、启动或切换模型。
   PATH 含实际开发工具临时目录，下一次继承环境改变可能不再匹配；不得为复用旧 entry 擅自忽略这些值。
3. 使用既有 native `VISION_VALIDATE` exact-intent：核对 model/runner/完整 child/projector、revision、generation/policy，
   同一 session、60 秒有效期、one-shot 确认；沿用唯一 Active owner 与既有 reservation/guard/send chain。
4. 一次 paired flow 最多两次图片请求：现有内存合成红图和绿图，各一张 1024×1024 规范 PNG；
   使用现有固定 system/问题、local-only、无 history/tools、think=false、num_ctx=8192、num_predict=1024。
   第一张严格得到 `red` 才进入第二张，第二张严格得到 `green`；两张都成功且最终身份未漂移才发布 Vision validated。
5. 超时、未知完成、颜色失败或身份/政策漂移立即保留未验证状态，沿用 owner 的 guard/uncertainty 处理，不自动重发。
   记录实际 intent、请求数、结果与最终 owner 状态；不把预算批准或纯文本成功写成图片验收 PASS。

当前没有必须继续追查的供应链 STOP。后续出现 member SHA 不同、未知相关控制值、不同 child/tuple、额外模块或身份采样失败，
即为具体 STOP；应核对该项差异，不通过扩允许目录、接受未知默认值或自动加入多个候选 entry 绕过。

## 5. 本轮证据与未执行项

本轮实际执行的是官方 release/member SHA 比对、官方 tag/构建记录查询、只读 binary build-marker 检查、
源码分支静态审查、证据文件完整性检查及独立 tuple 离线复算；没有执行生产测试套件。
原 WIC、纯文本 VALIDATE、Clean Generation 和 token 证明为继承证据，未重跑；没有 OBS 调查、VISION_VALIDATE、图片请求、模型操作、
Server/Runner/Desktop/Runtime 启动、全局配置变更或 Firecrawl。生产 policy 保持空；没有 commit、branch 创建、push、tag 或 release。

新增本地审查附件在 ignored [`.verification/v1-vision-native-policy-review-20261028/`](../../.verification/v1-vision-native-policy-review-20261028/)：
`official-release.json`、`official-loaded-file-comparison.json`、`official-entry-index.json`、`official-sha256sum.txt`、
`official-source-pins.json`、`official-build-run.json`、`binary-build-markers.json`、`build-source-index.json`、
`source-tree-pin-audit.json`、`independent-tuple-audit.json`、`effective-preprocessing-audit.json` 和只读采证脚本/固定源码缓存。
这些本地附件不会随普通 Git 发布；本报告保留关键身份、hash、官方链接与验收口径。

离场分支/HEAD 不变；唯一非 ignored 工作树新增为本审查报告。Vision runtime 功能验收仍待下一轮最小实施及后续单独授权的 paired flow。
