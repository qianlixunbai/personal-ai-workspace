# 独立记忆质量入口：启动记录的静态核清与最小修正

2026-11-12，基线 e096dda0ddc67b489ecb10c3e038b4fffe6cf369。只分析既有证据、固定版本提供器源码及支持说明，并执行最小静态／合成检查。本轮没有再次启动 Ollama、Runtime 或 Desktop，没有发送模型请求。

## 九个 blob 与日志中的零并不矛盾

Ollama v0.40.1 的 [PruneLayers](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/server/images.go) 先排除修改时间不足一小时的文件，再把候选数量写为 “total blobs”。这不是模型目录的完整文件计数，也不是已成功加载的模型数。

本次脚本用 FileMode.CreateNew 和 CopyTo 创建九个文件，没有继承源文件修改时间；服务在复制后数秒启动。它们处于清理宽限期，因此零候选符合实现。已有 memory-model-recheck-6 日志同样先记零，随后从该副本加载并完成四次 /api/chat，提供了独立历史佐证；不把那轮的模型结果计入本轮。

既有 model-copy.json 记录九项大小及哈希；服务日志中的 OLLAMA_MODELS 与脚本复制根一致。提供器的 [Models 配置](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/envconfig/config.go) 和 [BlobsPath／V2Path](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/manifest/paths.go) 也对应 models/blobs、models/manifests-v2，没有静态证据支持子目录或环境传递错误。

因此收窄上一份 REVIEW.md 的判断：不能再把这条零计数当成模型不可见的异常证据。旧报告保持原样，本文件记录修正。模型实际加载仍未验证，因为本次根本未进入推理。

## 端口检查的确定缺陷与仍未知的原因

原 run.ps1 用 Get-NetTCPConnection -State Listen -LocalPort 59881 -ErrorAction SilentlyContinue，但没有 ErrorVariable，也没有保存返回行。非终止错误通常不会进入 catch，随后零行便可能被混同为正常空结果。原 cleanup 中同样的零行判断不能独立证明端口关闭。

目前只有提供器的监听日志、自有进程句柄、最终通用失败文本。缺少每次查询的行数、LocalAddress／LocalPort／OwningProcess，以及错误 ID、类别和异常类型。故不能确定当时是正常无匹配、错误所有者／地址、多行，还是查询错误；更不能断言查询遭到访问拒绝、身份变化或某个命名空间问题。

已新增 scripts/verification/memory-quality-launch.ps1：

- 保留同一个按端口过滤的 Get-NetTCPConnection，不引入另一 API、全局进程查询或连接试探。
- 同时记录返回行和错误；状态为 OWNED、EMPTY、MISMATCH、QUERY_ERROR。即使有正确所有者行，只要查询报错，仍为 QUERY_ERROR。
- 修正版 runner 持久化每次观察；查询错误立即停止，后续清理不再次查询该动作，端口关闭状态为 null。普通查询错误也不擅自归为权限拒绝或加入可重试白名单。
- 所有者条件仍要求恰好一条 IPv4 回环记录、相同端口和自有 PID；没有用服务日志替代归属要求。

旧失败的具体查询结果已经缺失，无法通过静态分析还原。本轮到此停止该根因追查，不用新探针重复失败。

## 正式支持的本地模式

原脚本清除继承的 OLLAMA_* 后未设置 OLLAMA_NO_CLOUD，日志明确为 false。[官方 FAQ](https://docs.ollama.com/faq#how-do-i-disable-ollamas-cloud-features) 支持用 OLLAMA_NO_CLOUD=1 启用本地模式；固定版本 envconfig 也实现该配置。

v0.40.1 的[推荐目录请求](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/server/model_recommendations.go)在 NoCloud 为真时先返回；[云端 show cache](https://raw.githubusercontent.com/ollama/ollama/v0.40.1/server/model_show_cache.go)在 cloud disabled 时跳过 hydration。后者异步运行，不能把其外网失败当作本地监听失败的原因。此前 runner 的 --offline 只属于 llama-server，不能代替外层 Ollama 服务的设置。

修正版在清除继承变量之后、Process.Start 之前，只对该子进程设置 OLLAMA_NO_CLOUD=1。不修改用户／系统环境、真实 server.json、网络策略、代理或地址，不重试两个已拒绝的外网请求。这里只确认配置与控制分支，尚未动态证明本轮二进制的本地运行或完全无外联。

## 交付、验证与未执行候选

备份：artifacts/memory-quality-launch-static-20261112/before-static-review.zip，SHA-256 95EFA0F6A222EDB34B8A4A4BD53C431AC90E472F5605418EDDA41F03644A156A。

该目录的 run.ps1 是保留原流程的修正版，引用上述已跟踪 helper；cases.json、quality.test.mjs、vitest.config.mjs 从上轮未调用的冻结样本复制，只调整本轮证据路径。原脚本、冻结文件及全部旧回执未修改。候选 run.ps1 SHA-256：D4B7DF01E5656A5D9CE43496F2FF969924C8828A4B4B3636463D5CFC87B4F062。它仍未执行，不是可宣布成功的运行包。

verify-static.ps1 只解析候选脚本、导入 helper，并用本地 PowerShell 函数替身提供四种合成查询结果；实际 Get-NetTCPConnection 未调用。最终通过：OWNED、返回正确行但同时报错的 QUERY_ERROR、错误 PID 的 MISMATCH、无错误零行的 EMPTY；子进程环境值为 1，错误停止及未知关闭状态接线检查通过。原 freeze.json 中全部文件哈希仍相同。结果为 static-result.json，2026-11-12T07:22:48.970Z。

初次合成检查也通过，但随后静态复核发现生成候选时的一处文本替换误改了 Job 源码读取参数。该候选从未执行；修正这一生成错误、增加精确路径断言后，仅复跑上述最小检查。初次结果保留为 static-result-initial.json，不能据此声称启动脚本曾动态通过。

后续若安排一次正规有界运行，应使用新候选、继续共享互斥与精确所有权要求。首先需要取得可解释的端口观察；任一查询错误仍停止，不更换 API 或放行。只有 OWNED 才进入版本核对及最多三次本地质量调用。这个安排尚未执行，也不自动解除其他明确拒绝。

本轮无产品源码、Java/JUnit、前端构建、真实模型、Profile／凭据或数据库操作；无新服务、后台任务或互斥锁。先前自有模型副本与自动生成密钥的临时目录已按旧回执清理，没有读取或重建它们。真实产品启动仍 1/2，productionGo=false，诊断候选仍为 d27ce7a。

固定版本资料读取中，internal/cloud/cloud.go 返回 404；随后 GitHub 的 internal/cloud 目录页被 Web 工具以 “restricted URL” 拒绝。未重试、改工具或另找等价目录入口；读取的独立 FAQ 与先前已取得的源码支持上述有限配置结论。该 Web 工具结果不是自动审核回执，也不改变原有身份、桌面、JAR 和外网目录请求拒绝。
