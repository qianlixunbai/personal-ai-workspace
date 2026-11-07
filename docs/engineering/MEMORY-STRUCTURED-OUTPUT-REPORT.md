# 记忆候选结构化输出与完整应用复验

2026-11-07。本文件接续 `MEMORY-MODEL-RECHECK-REPORT.md`，原失败记录不改写。最终第 29 轮完整应用闭环及同版本 11 次质量小样本均通过，准确范围与限制以[完成报告](MEMORY-SYSTEM-COMPLETION-REPORT.md)为准。以下按实际进展保留诊断与失败历史。

## 根因与复用选择

此前候选生成只在提示词中要求 JSON，没有给模型服务发送结构约束。严格解析正确拒绝了缺少 evidence 的结果，但提示词不能可靠保证字段出现。本轮采用 Ollama `/api/chat` 原生 `format` JSON Schema，而不是继续添加格式提示或补造证据。

核对 [Ollama 官方结构化输出文档](https://docs.ollama.com/capabilities/structured-outputs)、[0.40.6 实现](https://github.com/ollama/ollama/blob/v0.40.6/llm/llama_server.go)和 [0.40.1 实现](https://github.com/ollama/ollama/blob/v0.40.1/llm/llama_server.go)：两版均有 schema 转发路径。本机真实验证分别使用已有审查版本，不安装或升级服务。2026-11-07 GitHub 元数据中 Ollama 为 MIT、186,210 Star、未归档，当日仍有 push。

同时评估 [Instructor](https://github.com/567-labs/instructor) 及其 [Ollama 集成](https://python.useinstructor.com/integrations/ollama/)：MIT、14,100 Star、未归档，最近 push 为 2026-11-06。其模型是结构约束后再次验证，可配置失败重试。直接引入会增加 Python/Pydantic/重试依赖和第二条服务调用链，不能替代现有 Java 任务所有权、预算、取消与原生身份边界。因此直接复用 Ollama 的既有能力和项目自己的任务链，不引入 Instructor 依赖或自动重试。Star 与活跃度只是选择依据，不是安全认证。

## 实现边界

- `contracts/memory-candidate.schema.json` 是应用固定契约：candidate 为 null 或包含 type/title/content/evidence 的对象，禁止额外字段；字段长度和类型枚举有上限。
- 原生记忆提取请求使用固定 `outputContract=memory-candidate-v1`。其他客户端不能申请该契约；不接受用户提供的 schema。普通问答、会话和带记忆问答保持原来的文本输出。
- 复用既有任务提交、模型租约、取消、限额、结果来源绑定和审阅页面。发送前计算包含 schema 的实际序列化请求大小；超预算直接拒绝，不提高模型预算。
- 模型结果仍须通过严格前端格式、原文字面证据和有限语言保护，必须由用户审阅、明确采用并保存。schema 不保证内容正确，也不能证明证据支持所有改写；不补写证据、不静默翻译、不失败降级或重试。

## 实际验证

原生 HTTP 隔离 fixture 的最终 `api-3` 为 4/4 通过：固定 schema 确实到达提供器；普通 Ask 没有 schema；候选不写记忆；非法契约、额外 schema、非原生及未认证请求被拒绝；含 schema 的请求超预算时没有推理；非法提供器结果只调用一次并失败。此前无效测试扩展 ID 和在故障隔离后继续调用被拒绝的两次 fixture 结果保留。

.NET 选择器/候选任务相关 38/38 通过。既有 4 条记忆摘要断言原来仍是英文，与实际中文界面不符，已修正测试期望，未改界面文字。Java 编译、独立 .NET 编译通过。

真实模型使用合成数据、独占临时模型副本和 loopback 服务，单并发、无自动重试。`model-1` 在首请求因测试脚本 JSON 序列化深度不足把 schema 嵌套对象转成字符串而返回 HTTP 400；修正脚本深度，并在调用前验证 fixture schema 与仓库契约完全一致，保留原失败。

`model-2` 使用 Qwen3.5 4B / Ollama 0.40.6：8 个候选场景及 3 个会话调用完成，机器断言通过。另存的人工语义复核记录绑定原结果 SHA，不改写原结果的 `semanticReview` 字段。观测包括中英文语言、项目范围、否定修正、ASSISTANT 未确认建议的归属、闲聊/注入/纯秘密/不支持场景弃答，以及两次 teal 记忆回答和清除后仍保留历史的说明。**这只是 11 次小样本，不是总体成功率或忠实度保证，也不是完整应用端到端验收。**

## 完整应用启动暴露的既有兼容问题

完整 AssistantApp 的真实 WebView 加载成功，但隔离运行时在 `WindowsNativeGate.isolatedDevelopmentClass` 的 `BasicFileAttributes.fileKey()!=null` 检查失败。受限诊断保留异常类型与代码位置，不保存异常消息或凭据。两个失败应用 fixture 和两次诊断 fixture 均保留；诊断退出成功仅表示诊断完成，不代表记忆流程通过。

[Java 21 文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html#fileKey()) 允许 fileKey 为空。项目的数据库准入已用 Windows 原生 [FILE_ID_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_id_info) 解决相同问题。本轮直接复用 `TrustedDatabaseConfiguration.Identity.read` 获取卷序列号及 128 位文件标识，并校验磁盘文件、最终路径、非重解析点、单硬链接。保留 JAR 的 NOSHARE_WRITE/NOSHARE_DELETE 生命周期锁，锁定前后及缓存重用均比对身份，原路径检查、类来源、打包契约和 SHA 验证不变。没有修改系统权限或固定原生二进制哈希。

该兼容修复后的完整应用已能正常启动并连接 SQLite。后续模型准入暴露两项隔离 fixture 问题：启动脚本硬编码 Windows 目录大小写，与系统实际返回值不一致；模型副本位于工作区，而工作区祖先目录不满足私有存储的原有权限规则。前者改用系统路径 API；后者没有修改既有祖先目录权限。

隔离开发构建现在复用原生准入 Profile 内固定的 `evidence/isolated-ollama-models`，不接受任意模型路径参数。测试仅在全新合成 Profile 中复制已有审查模型，保护副本目录／文件，继续执行原有 Native Gate、目录权限、完整哈希、进程和套接字身份校验。固定分发位置、82 个二进制文件及驱动哈希不变，默认构建没有该开发类。构建契约随真实源码重新生成并审计，而不是修改系统服务或绕过验证。

断线前构建已核实成功，恢复后自有服务和测试进程均已退出。清除临时诊断后重新构建成功。第 13 轮完整应用在私有 Profile 中启动成功，但模型目录仍返回 ModelUnavailable，尚未到达推理；该失败与此前结果保留。继续定位剩余准入原因，完整应用与真实模型闭环仍未通过。

第 14／15 轮受限诊断进一步确认：存储校验通过，服务的子进程中多出了 Windows 自动创建的 `conhost.exe`，原有角色校验正确拒绝该未知进程。测试脚本原先使用 CreateNoWindow 使服务没有控制台，后续启动行为导致额外控制台宿主。按 [Windows 进程创建标志文档](https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags) 的默认控制台继承机制，改为让服务继承本轮脚本已有的隐藏控制台，明确设置 Hidden 并检查控制台存在且不可见。没有添加 conhost 例外、停止其他控制台或修改系统终端设置。再次移除临时诊断后，第 16 轮开始完整复验。

## Windows 已加载推理进程兼容性

第 16／17 轮仍未通过加载后的准入；第 18 轮诊断定位到进程角色检查。第 17 轮曾试验精确限定的启动参数适配，因不足以解决问题而撤回，失败记录不改写。第 19 轮显式选择 Qwen3.5 的 GGML 变体，目录中没有合格项，尚未推理即停止。

随后评估官方 [Qwen3 4B](https://ollama.com/library/qwen3:4b) 纯文本模型（Apache 2.0、约 2.5 GB），核对官方 manifest SHA `359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7` 并下载到独立验证目录。进一步阅读实现确认：通用模型准入支持 GGML，但当前隔离宿主的进程／模型合同仍只支持固定 Qwen3.5 llamacpp 组合。因此停止下载及该方向，未安装到共享模型目录、未运行该模型，也未把它当作自动降级。

第 20／21 轮使用自有、已核对的同一 Ollama 0.40.1／Qwen3.5 副本各执行一次有界合成预热，随后只读采样实际子进程。它们只是诊断完成，不是原生准入或记忆质量通过。采样确认已加载的 llama-server 创建 `System32/conhost.exe 0x4`，父 PID 为该 runner，cwd 为系统 Windows 目录，环境等于 runner 启动环境；runner 初始化后另有 `KMP_BLOCKTIME=200` 和 `__KMP_REGISTERED_LIB_<自身PID>`，fit 仍为 1669，没有修改其公式或数值。

复用依据：

- [Microsoft Terminal 官方测试](https://github.com/microsoft/terminal/blob/main/src/host/ut_host/ConsoleArgumentsTests.cpp) 明确覆盖系统加载器的 `conhost.exe 0x4` 形式。该仓库 MIT、105,338 Star、未归档、2026-11-07 仍有 push；不复制控制台实现，不安装／更改终端设置。
- Ollama 0.40.1 的 `LLAMA_CPP_VERSION` 固定为 `b11351`；[对应 ggml CPU 源码](https://github.com/ggml-org/llama.cpp/blob/b11351/ggml/src/ggml-cpu/ggml-cpu.c) 在 Windows 初始化时设置缺省 `KMP_BLOCKTIME=200`。[LLVM OpenMP 源码](https://github.com/llvm/llvm-project/blob/main/openmp/runtime/src/kmp_runtime.cpp) 以当前 PID、注册地址、`cafe` 标志和库名生成登记字段。直接复用项目已有固定分发库及身份采样，不新增依赖。

适配只在固定隔离实例生效：已验证 runner 的启动参数限定为 `--load-mode none --context-shift --keep 4`；OpenMP 字段必须属于当前 PID、固定值／格式且已加载固定路径的 libomp，之后仅为父环境比较构造启动视图。原始字段仍完整参加两次稳定快照，所有分发模块仍核对固定 SHA；父服务或未知 OpenMP 覆盖被拒绝。控制台只允许作为该 runner 的直属子进程，每父最多一个，固定系统路径／参数／cwd，精确继承启动环境；其全部模块复用现有 OS 权限、锁定文件身份、SHA 采样与 Microsoft 证书链验证，没有按名称跳过进程或放行任意 conhost。Image token policy、模型／驱动 hash、上下文预算、系统服务与安全设置均未修改。

必要身份回归 5/5 通过，覆盖错误父进程／路径／参数／环境、错误 PID、缺少已加载 libomp、未知 OpenMP 覆盖、非法启动参数及既有持有句柄稳定性。Java 和实际桌面探针重新构建通过；第 22 轮开始完整真实模型桌面验收，结果尚待记录。临时异常位置输出已从产品与桌面探针移除；只读诊断工具只保存在忽略的验证目录。

第 22 轮仍在加载后准入失败，没有开始记忆保存。第 23 轮只读诊断已通过实际 runner／console 的角色检查以及 console／主服务的模块检查，剩余失败位于签名 catalog 的 ACL；第 24／25 轮确认是 Windows 驱动 catalog 的服务身份和硬件兼容证书范围，而非模型正文或 fit 预算。

按 [Microsoft 的 Catalog 说明](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/catalog-files)和 [CryptSvc 职责](https://learn.microsoft.com/en-us/windows-server/security/windows-services/security-guidelines-for-disabling-system-services-in-windows-server)，仅在已定位的系统签名 catalog／其 catalog 父目录内识别固定 CryptSvc 服务 SID。模块本身仍只允许原有 OS 管理身份；catalog 和各级父目录均持有并核对文件身份／权限，最终 WinTrust 校验继续执行。没有写 catalog、调整 ACL 或启停 CryptSvc。

CUDA 实际加载的 6 个辅助 DLL 位于同一 `nv_dispi.inf_amd64_0373d825005116d0` 驱动包。只读核对每个文件的完整 SHA-256、大小、无重解析点和 Valid Authenticode（Microsoft Windows Hardware Compatibility Publisher），清单保存在 `driver-closure.json`。隔离开发合同补充这 6 个**精确相对路径／大小／SHA**，每次还须通过原有受保护存储、文件锁和 WinTrust；未把任意硬件签名或整个 DriverStore 作为允许范围。原 82 个 Ollama 分发文件与 nvcuda anchor 哈希不变，额外驱动文件的固定清单是明确的代码变更；默认非隔离实例不获得该清单。既有驱动仅只读使用，没有复制分发、安装或更新驱动。

最终 Windows 身份回归现为 6/6，通过错误 catalog 权限范围、错误驱动目录／同名文件和上述原有负例。Java、隔离 JAR 审计和实际桌面构建通过；第 26 轮用新合同和全新 Profile 继续完整真实模型桌面验收。仍未提交、推送或整理分支。

第 26 轮仍未通过。实际加载清单进一步发现 System32 的 MSVCP140／VCRUNTIME140／VCRUNTIME140_1 与分发包 Vulkan 子目录同名，原 basename 防替换规则也会拒绝这些合法系统依赖。3 个文件均为版本 14.51.36247.0、Valid Microsoft Windows Software Compatibility Publisher 签名，补入精确路径／大小／SHA 合同；只有匹配固定文件的情况才能通过同名检查。第 27 轮失败后的自动只读诊断发现另有 System32/nvapi64.dll，亦为 Valid Windows Hardware Compatibility Publisher 签名。对全部 64 个实际加载系统文件统一核对：53 个 Microsoft Windows、8 个硬件兼容签名（其中一个是原 nvcuda anchor）、3 个软件兼容签名，没有未解释的签名类别。

最终新增平台文件清单为 **7 个驱动辅助文件 + 3 个 C++ 运行库**，不是放行整个目录或某种发布者。`platform-closure.json` 和 `loaded-system-module-signatures.json` 保存了逐文件证据。只读诊断轮次保留，产品代码无异常堆栈输出；桌面执行文件做强制重建，避免临时诊断撤回时文件旧时间戳影响增量构建。第 29 轮开始全新完整流程；第 28 号预留给后续有限质量复验，尚未启动。前述 6 项身份测试覆盖的逻辑未再放宽，新增项只是完整哈希固定的数据。

## 完整应用与真实模型：第 29 轮通过

`.verification/memory-full-app/run-29/` 使用完整 `AssistantApp`、实际 WPF／WebView2、`DevelopmentRuntimeOwner` 自有 JVM、已审计隔离 JAR、原生进程／连接／存储准入和 SQLite。Ollama 为原固定 0.40.1，Qwen3.5 4B 为既有完整 SHA 固定的 llamacpp 子清单；全新合成 Profile。所有按钮由实际可见 DOM 或原生 WPF 控件触发，没有注入 Bridge 返回值或模拟模型输出。

```text
MEMORY_FULL_APP_OWNED_MODEL_READY_PASS
MEMORY_FULL_APP_REAL_CANDIDATE_REVIEW_EXPLICIT_SAVE_PASS
MEMORY_FULL_APP_REAL_RECALL_RETAIN_STALE_CLEAR_PASS
MEMORY_FULL_APP_RESTART_PERSISTENCE_ARCHIVE_RESTORE_DELETE_PASS
workflow exitCode=0; reopen exitCode=0
ownedServerExited=true; ownedClientExited=true; portsClosed=true
```

实际来源为合成项目 Amber 的 teal 状态指示色。候选正文与 quote 均为 `Project Amber uses teal for status indicators.`，类型 PROJECT_NOTE，原文包含完全一致摘录；采用前、采用后均未落库，明确保存后 revision=1。检索预览后显式 Add／Use，两轮回答都为 teal，并持久化同一 ID／revision=1 引用。另一个原生客户端更新到 revision=2 后，旧引用拒绝发送且轮次不增；清除后第 4 轮成功、没有显式引用，模型明确承认旧会话仍含颜色信息。

第一整个应用及所属 JVM 退出后，另一个新应用进程打开同一合成 Profile：修订、4 轮对话及历史引用仍在；实际界面归档令 recall=0、恢复令 recall=1、永久删除令记忆列表为空，4 轮历史保留。两张实际 WebView 截图已查看，主要控件、持久内容／删除后空状态可见。记录中的截图是该时刻的视图，不代替数据库和消息断言。

这覆盖完整应用重启，补齐此前仅重开窗口的范围。仍不涵盖物理键鼠／IME、真实个人数据库、全套 PostgreSQL GUI、跨模型／驱动版本的普遍兼容性或正式发布信任链。没有新增 image token policy，Vision、个人备份／恢复及 Production GO 的原关卡不随记忆验收变化。最后一组同版本独立质量 fixture 使用另一个全新 Profile，单并发最多 11 次请求，另行记录，不与第 29 轮混称同一端到端测试。

第 28 号质量 fixture 随后完成：Ollama 0.40.1／同一 Qwen3.5 子清单，8 个候选与 3 个会话请求，约 48 秒，断言全部通过，自有进程退出、端口关闭。独立人工语义复核绑定 `quality-results.json` SHA `07953e91d9bfa8fbc1c8fb9d5745b25569ad7949475845a5be75cd13643ddafa`，不改写原始结果。中文偏好保留项目范围，英文项目事实保留技术及用途，否定修正仅保留当前值，摘录均逐字一致；混有恶意引用、未确认助手建议、纯虚构秘密、临时／不支持场景均无候选。对混有有效事实的攻击引用选择弃答是保守行为，不证明提取召回率。两次 teal 回答和清除后仍保留历史的说明通过；第三次回答的 “future references” 比产品的“未来显式引用”用语更宽，记录这一表达限制，不据此声称语言质量完全可靠。

第 29 轮真实桌面结果的单独人工复核绑定 `memory-full-workflow.json` SHA `489bd45bff074bd44e4d6c431df01ca0076acff3e47d357a49404d43078ad7af`。原生 HTTP 最终 `api-4` 再验为 4/4，host／Java 正常退出；没有借合成提供器覆盖或重写真实模型失败。早期所有失败均保留。
