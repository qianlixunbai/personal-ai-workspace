# MMF-3 — 有限授权模型身份修复候选

2026-10-10（Asia/Shanghai）。未 commit/push；未构建、启动或执行 Windows 应用。
本报告仅记录本轮模型身份、准入与操作确认补丁，不重开架构审查或宣布 MMF-3 集成关闭。

## 实现与持久化决定

可执行身份为 `logical model name + runner + child Manifest digest`。

- Admission 允许同名不同 runner 的 tags 条目，分别检查身份；重复 runner 或同名共享 digest 的映射拒绝。
- 第一阶段仅准入严格本地 GGUF 审核通过的 `ggml`。`llamacpp` 不自动选择；含 projector、未知来源、remote、架构/context 不一致的 metadata 拒绝。
- 显式 runner show 的 selected child 必须匹配 tags runner/digest，前后 tags 必须保留同一候选条目。双 runner tags 缺少 show child summaries 时拒绝。
- Execution snapshot、catalog handle、VALIDATE、SWITCH、release、普通推理与 probe 共用候选身份。`/api/show`、`/api/chat`、`/api/generate` 明确传 `runner`，同时保留 `model=<logical-name>:local`；不回退默认 runner。推理 profile 的 logical name 也必须等于 reservation 的 logical name。
- Native 确认意图及窗口包含候选和旧 Active 的 runner、digest。保留默认 Cancel、60 秒、session invalidation、一次消费和未知结果只读核对逻辑。
- `/api/ps` 只有确切 name/runner/digest 命中才确认驻留；同名未匹配且无确切命中时返回 Unknown，不推导父子 digest 映射。成功查询无同名条目才返回 False。

**保留原磁盘格式。** 从已持久化的 `model + digest` 找到唯一 tags runner 后，仍须通过完整 admission 与 selected-child 核对。
同名重复 digest、重复 runner、旧父 digest、缺失身份或安全证明均拒绝，不能据默认 runner 重解释旧记录。
`ModelStateStore` 本轮未修改；selection/guard/validation-required schema、旧 digest 和真实 Profile 原样保留。
fixture 验证了旧父 digest + unresolved guard + validation-required 的状态在只读检查/拒绝恢复后没有任何状态写入。
这不是实际旧 Profile 的恢复或迁移验收。

## 变更边界

生产文件：

- Java：`provider/ollama/OllamaModelAdmission.java`、`OllamaProvider.java`、`model/ActiveModelManager.java`、`api/ModelController.java`。
- Desktop：Core `Models.cs`、`RuntimeClient.Models.cs`；Desktop `Bridge/WorkspaceModels.cs`、`ModelConfirmationWindow.cs`。
- Settings 必要合同与候选区分：Frontend `bridge/models.ts`、`pages/SettingsPage.tsx`。

Native-only catalog 新增 `runner`；status 新增 nullable `configuredRunner` / `activeRunner`；mutation 必须显式携带 `candidateRunner` / `expectedActiveRunner`。
这是本轮明确授权的模型管理合同变化。Runtime、Desktop、Frontend 必须使用匹配合同；旧 mutation 缺 runner 返回受控 INVALID_REQUEST，旧严格客户端拒绝新 shape。
Browser public profile/cache identity、领域 API、DB、备份格式、默认配置和权限边界未改。

测试仅新增一个 Provider/manager 综合身份回归和一个 API exact-intent 合同回归；其余既有 Java/Desktop/Frontend fixture 与断言只做必要合同同步。
入场已有 Admission 的 0.40.1 白名单和 Provider 的 cached metric 候选保留。
入场其他候选叶文件逐文件 SHA-256 核对未变，包含持久化、ACL、Native、凭据与启动器候选；没有修改 Bootstrap。

## 固定源码与 fixture 来源

官方 `v0.40.1` tag 本轮只读解析为 `cf2a313a298066d572c36812e5ad30a21c0db13b`，按该 commit 获取并核对：

- [api/types.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/api/types.go)：Show/Chat/Generate 的 runner 与 ManifestSummary 合同。
- [server/model_list.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/server/model_list.go)：tags 对 Manifest List 输出 child runner/digest 条目。
- [manifest/manifest.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/manifest/manifest.go)：指定 runner 的单一偏好、runner 错配拒绝、SelectedDigest；旧 GGUF config 的 ggml 派生规则。
- [server/routes.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/server/routes.go)、[server/images.go](https://github.com/ollama/ollama/blob/cf2a313a298066d572c36812e5ad30a21c0db13b/server/images.go)：show、chat、release 的 runner 选择及 ps 的 ManifestDigest/Runner 输出。

本轮实际只读 metadata：GET `/api/version`、GET `/api/tags`；POST `/api/show` 使用 `qwen3.5:4b:local` 与显式 ggml/llamacpp。
实际版本 0.40.1；4b 同名两个不同 child digest，ggml show selected=ggml 且无 projector，llamacpp show 有 `projector_info` 和 `qwen35,clip` families。
前后 tags 比较一致；未调用真实 chat/generate/load/switch/release。

新增 `src/test/resources/ollama/qwen35-manifest-list-v0.40.1.json` 为上述只读 metadata 的脱敏最小投影：
保留 details、capabilities、requires、architecture/context、child summary 与 projector 的关键形状；
模型名改为 `test-model:latest`，digest 改为固定 a/b/c，FROM 改为隔离路径；删去 timestamps、模板和无关 model_info。
llamacpp 的 projector map 仅保留 architecture。它是策略 fixture，不是完整 API 原文或模型二进制。
原始本机路径/metadata、固定源码及日志仅保存在 ignored `.verification/mmf3-model-identity/`。

## 本轮实际验证

| 执行 | 结论与边界 |
| --- | --- |
| `mvnw.cmd -Dtest=OllamaProviderTest test` | PASS；既有单 Manifest、两个已支持版本、来源/context/response 合同及新的跨层 fixture。首次编译断言访问包私有方法失败，改为检查 mock 调用；随后发现“完全缺失模型”应保留 MODEL_UNAVAILABLE，修复分类后通过。 |
| `mvnw.cmd -Dtest=OllamaProviderTest#manifestListIdentityRemainsBoundAcrossAdmissionOperationsAndRestart+finalEgressPolicyRejectsCloudBeforeHttp,ModelControllerTest test` | PASS；补充同名 digest 显式重选、错误 runner、API 必需 runner、reservation logical name、旧状态无写入等边界。 |
| 最后补丁后：`mvnw.cmd -Dtest=OllamaProviderTest#manifestListIdentityRemainsBoundAcrossAdmissionOperationsAndRestart test` | PASS；补充双 runner 缺 child summaries 的拒绝。只重跑受影响方法。 |
| Frontend `npm run check`；`npm test -- src/pages/SettingsPage.test.tsx` | PASS；无输出构建的类型检查与既有 typed Settings flow。 |
| 独立 `ReadonlyIdentityProbe` 使用编译后的 Provider 查询真实 metadata | PASS；两个 ggml 候选 identity 精确对应 tags，现有 model/digest 可唯一重新验证 runner；没有 Spring、凭据、state manager 或推理初始化。 |
| `git diff --check`；入场受保护候选叶文件 SHA-256 | PASS。 |

Provider/manager 测试中的 SWITCH、VALIDATE、release 与推理只访问随机端口的 fixture HTTP 服务；持久化用 mock，未创建凭据或修改 ACL。
API 回归直接验证 Controller 的模型请求解析，没有启动 Runtime；不作为 HTTP authentication 集成验收。
Desktop 确认断言已同步但**未执行**；未编译 Desktop/Windows 应用，未运行 GUI/完整 Windows 验收。
`ModelFoundationTest` / `RuntimeApiTest` 仅同步 fixture 并通过 Java test compilation，未执行其持久化/认证集成测试。
没有运行完整 Java、Desktop、Frontend 或 Browser suite，没有 package/release。

## 剩余边界与交付状态

真实推理和 Native 确认执行链尚未做 Windows 集成验收；本轮证据不能宣布该 gate PASS。
保留 ADR-015 §6 已有的按名调用边界：metadata 复验与 HTTP 执行之间，外部客户端仍可能改变 tag；这不是原子 digest-CAS 或服务监督器。
本补丁证明显式 runner 定址与候选 digest 复验，未扩大到外部进程控制或新的持久化格式。

Git branch：`codex/mmf3-runtime-private-acl-fix`；HEAD：`cb36879d93aa9a23c8b3d94e9fa26f5166a3c659`。
工作树保留入场未提交候选和本轮补丁，未 commit/push；真实模型、旧 Profile guard/validation、ACL、Bootstrap、凭据均未修改。
