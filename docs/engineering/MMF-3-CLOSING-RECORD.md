# MMF-3 Closing Record — CLOSED — GO（Development-only）

日期：2026-10-11（Asia/Shanghai）。**MMF-3 CLOSED — GO，仅限 Development-only 集成关闭。**
本记录归档同日独立 Final Closing Review 的裁定，不重新开展架构审批或验收。
**Development-only CLOSED 不等于 Production RELEASE GO。** Production Trusted、正式启动与发布能力未获此裁定批准。

## 1. 关闭身份与审批来源

| 身份 | 值 |
| --- | --- |
| Repository | `D:/IDEA/Daima/personal-ai-workspace` |
| Branch | `codex/mmf3-runtime-private-acl-fix` |
| HEAD | `cb36879d93aa9a23c8b3d94e9fa26f5166a3c659` |
| source-baseline.json SHA-256 | `0cba5da2e51516e595c399f0e974dfc3ca7fb177fc25a5395850b442db8ef5a8` |
| 模型身份修复 18 文件增量清单 SHA-256 | `aa24ff45e86355147399849de0db0712655d13542b54d92afd742e125db78062` |

当前含已批准但尚未提交的源码候选；**HEAD 单独不能标识关闭版本**。
必须同时绑定 HEAD、[源码基线清单](../../.verification/mmf3-identity-integration-e1c471f2aa7649ae9c91b72223f23ad1/source-baseline.json)及实际文件哈希。
本次文档归档入场复算上述基线哈希，清单全部 376 个文件条目与当前文件一致。
18 文件清单哈希来自用户提供的独立审批身份，本轮不宣称重新独立复算该清单。
本轮随后对 STATUS、current-architecture、V1-ROADMAP 的授权文档同步不构成源码漂移；原基线不重生成，生产/测试/配置仍须逐文件保持。

批准链：

1. [ADR-015](../ADR/ADR-015-active-model-management.md) 为 Accepted，保留正文及 §7 Browser freshness、§10 最小验收边界。
2. [MMF-2B §17](MMF-2B-IMPLEMENTATION-CANDIDATE.md#17-formal-source-approval-and-git-publication) 正式 Source Approval — GO；P0 Recovery/Restart、P1 Release Guard Lifecycle、P1 Desktop Error Compatibility 已 CLOSED。
3. 原 MMF-3 Development-only 集成源码候选已获 Architecture Guard APPROVED；2026-10-11 模型身份修复 18 文件增量获 Independent Architecture Guard SOURCE APPROVAL — GO，未发现 P0/P1 阻塞。
4. 2026-10-11 独立 Final Closing Review：**MMF-3 CLOSED — GO**。Legacy 门禁关闭；Revoke 真实认证/UI 阻断加既有 cache guard fixture 满足该阶段关闭要求；未发现尚未满足的已批准硬性门禁。

第 3、4 项及下述后续 Windows/Chrome 最终结果，来源为用户本轮归档请求和前一轮 Final Closing Review。
它们是明确提供的审批/验收输入，不能把早期 candidate 文档、预检或构建日志冒充这些独立结论的原始执行记录。

## 2. 已通过及证据等级

| 项目 | 关闭采用的证据 | 边界 |
| --- | --- | --- |
| Development-only Runtime/Desktop 隔离 | 独立 Profile Prepare / Provision / Verify、Credential / ACL 检查 PASS | 真实 Windows 开发隔离环境；不认证生产安装链 |
| 模型执行 | 4B 真实 VALIDATE、4B → 9B 真实 SWITCH PASS | 已授权的本地模型操作；本轮不重复 |
| 身份与持久化 | Ollama 0.40.1 Manifest List、logical model + runner + child Manifest digest 绑定；迁移后 9B 显式重新绑定 PASS；revision 2 跨 Runtime 重启持久化 PASS；无 unresolved execution guard | 当前验收 run 的结论，不表示旧失败 Profile 已恢复 |
| Browser 连接 | 独立 Chrome Profile、测试扩展连接及配对/Translation 可用状态已观察 | Runtime `127.0.0.1:18765`；扩展 `chrome-extension://eaglajleeaicmpgngdmjckgpfbgimabh` |
| Single / Batch | A=9B：P/Q Batch、L Single 实际翻译成功；B=4B：同一页面 P/Q、L 再次成功，用户未观察到旧缓存立即复用 | 真实用户操作观察，非完整 HTTP 请求取证 |
| Mixed | P/Q/R 操作用户反馈正常 | 不等于真实 Chrome 已注入并观察 mixed epoch mismatch |
| Legacy | 真实扩展专用测试入口 GET 返回 HTTP 200、available=false、cacheIdentityPresent=false、PASS | 关闭未 opt-in readiness 的受控拒绝门禁；入口仅在隔离副本 |
| Revoke | 明确授权撤销测试 Browser 客户端后，真实 Chrome 凭据失效、Translation 不可用、按钮禁用，P/Q 未重新显示缓存译文 | 覆盖认证及 UI 阻断；未触发撤销后的 cache-read |
| 确定性补充 | 既有 freshness、mixed mismatch、revoke、legacy guard fixture PASS | 不写成真实 Chrome 执行；支撑真实观察无法逐请求确认的合同 |

现有本地证据入口：

- [MMF-3 集成候选及构建/ACL/native gate 记录](MMF-3-INTEGRATION-CLOSURE.md)。其形成时 PENDING / NOT PERFORMED 是历史状态，由本记录的后续批准与验收输入补齐，不改写历史正文。
- [模型身份修复候选](MMF-3-MODEL-IDENTITY-REPAIR-CANDIDATE.md)与[跨层集成构建报告](../../.verification/mmf3-identity-integration-e1c471f2aa7649ae9c91b72223f23ad1/REPORT.md)：Provider/Manager、Controller、Settings、Desktop 聚焦验证和产物合同；不是独立 Windows 操作记录。
- [Windows ACL 最终 XML](../../.verification/mmf3-integration-closure-ca0f954666e241aca231a2292d77a385/java-acl-final.xml)与[MMF-2B 聚焦/恢复记录](MMF-2B-IMPLEMENTATION-CANDIDATE.md#167-actually-executed-focused-verification)。均为此前执行，本轮未重跑。
- Browser sibling 的 `.verification/mmf-2a-behavior-final.log`、`.verification/mmf-2a-background-final.log` 及 `.verification/mmf-2a/MMF-2A-CANDIDATE-HANDOFF.md`：此前确定性执行。跨仓库原始日志是本地补充材料，不作为本轮真实 Chrome 操作记录。

早期 Legacy 自动操作 STOP 与专用入口准备阶段 NOT EXECUTED 记录仍保留；不能追写为 PASS。
最终 Legacy PASS 采用用户确认的后续专用入口执行结果；Revoke 截图支持凭据失效与按钮禁用的观察。

## 3. 明确保留项

- Single / Batch / Mixed 未独立记录逐项 POST 数量、Task ID、opaque epoch、实际模型身份。观察与 fixture 分别报告，不能互相提升证据等级。
- Revoke 因按钮已禁用，没有实测撤销后 cache-read 路径；其确定性保证来自既有 fixture。
- Legacy 临时 `legacy-test` 入口仅在隔离扩展副本，不属于正式 Browser 产品源码或未来发行物。
- 真实 Release、部分外部恢复、真实断电、低显存故障以及完整 Java/Desktop/Frontend/Browser suites **NOT PERFORMED**。已有 fixture 的模拟故障与重建进程证据不等于真实共享服务恢复、断电或 GPU 验收。
- 历史 `ModelFoundationTest` 凭据目录初始化错误保留为失败，不记 PASS；后续 ACL owning flow 的 PASS 不追溯改变该失败。
- Production Trusted 来源尚未建立；正式 Windows launcher、Installer、签名与生产发布尚未验收。当前正式 Windows launcher 的早期阻断仍保留；关闭不授权解禁或 bypass。
- 外部 Ollama metadata 信任、按名调用期间的 digest race、automatic eviction、共享客户端协调及 CPU/GPU 驻留限制继续适用；不声称 ONE Active 等于整个 Ollama 只有一个驻留模型。

上述限制已纳入 Development-only 关闭范围，不是自动追加的关闭条件，也不构成生产发布批准。
本轮没有新源码、新测试、模型操作、环境恢复或清理；没有 commit/push/merge/tag/Release。
工作树保留原未提交候选，Git index 保持入场状态；不得为取得 clean 状态撤销或提交这些候选。

## 4. 下一阶段与 Finance 交接

下一独立活动为 [V1 Vision Foundation 范围候选审查](V1-VISION-FOUNDATION-SCOPE-CANDIDATE.md)。
仅准备与 review；Vision implementation、图像推理、模型加载/切换/Release/恢复均未授权。
沿用唯一 Active owner、runner/child digest、native exact-intent、LOCAL_ONLY、reservation/guard 与 Browser privacy/cache 边界。
W1 后续联网开发继续 PAUSED；Finance Integration 继续 BLOCKED pending F0。

Finance 当前仍独立修复 bug，**尚未正式冻结**。用户后续计划：

```text
Finance bug 修复完成 → Finance 独立冻结 → Home PC Migration
→ 与 Workspace 同机独立运行 → F0 Finance Reality Sync → 再确定集成
```

Freeze 后另行核对最终 branch/HEAD/remote、未提交状态、实际聚焦/完整测试结果、PostgreSQL/Flyway schema、
Docker Compose/环境、实际 AI Provider/Tool Calling 入口、Finance Query Services、备份恢复完整性、Bug Fix Closing 与保留项。
不能现在推定这些事实或替 Finance 宣布冻结。

迁移方案在 Freeze 后独立制定：仓库与服务各自独立；Git commit、构建产物、配置、数据库状态分别核验。
不能把运行中的 PostgreSQL 数据目录直接复制作为唯一备份；采用可信数据库备份、校验及目标机器恢复验证。
凭据和私密配置经单独安全流程迁移；同机部署不自动共享数据库凭据或模型执行权限。
Finance OS 始终拥有金融事实的唯一业务权威；Workspace 不直接连接 Finance PostgreSQL，
F0 前不开放跨系统 Finance Tool Calling，不提前创建 Finance Gateway 或 finance-adapter。
本轮未访问 Finance 仓库，也未执行迁移。
