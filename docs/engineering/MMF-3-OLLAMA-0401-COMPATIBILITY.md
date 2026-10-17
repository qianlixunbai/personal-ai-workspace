# MMF-3 — Ollama 0.40.1 compatibility

2026-10-09（Asia/Shanghai）。Development-only 候选；未 commit/push。

## 修复

- `OllamaModelAdmission.java`：明确允许 `0.40.0` 和 `0.40.1`；`requires` 与本次实际已准入的服务版本比较。未知版本、预发布/非规范版本及额外 version 字段仍拒绝。
- `OllamaProvider.java`：使用上述白名单，向 metadata 验证传递本次服务版本。
- `OllamaProviderTest.java`：扩展一个既有只读准入回归，验证两个版本、最低版本边界、0.40.1 catalog、未知版本拒绝，以及 identity/context/local-source/remote 字段拒绝。没有增加测试方法或重复其他层测试。

local-only、identity、digest、source、context、安全字段、loopback/no proxy/no redirects 等验证全部保留。没有新增架构、API、依赖或持久化格式变化；既有未提交候选逐文件 SHA-256 与入场快照相同。

## 实际证据

共享 Ollama `/api/version` 实测 `0.40.1`。只调用 GET `/api/version`、GET `/api/tags`、POST `/api/show`（`model=<name>:local, verbose=false`）。没有推理、加载、卸载、释放、切换或共享服务管理。

`qwen3.5:4b`、`qwen3.5:9b` 的真实 metadata：`gguf/qwen35`、单 family、`runner=ggml`、`requires=0.17.1`、context `262144`、绝对本地 blob FROM；无非空 remote、manifest-list 或 projector metadata。tags 前后相同。未发现额外字段、来源或 context 兼容问题，故未放宽任何对应规则。

已核对固定版本官方 [ShowResponse / Requires 定义](https://github.com/ollama/ollama/blob/v0.40.1/api/types.go#L704-L725)、[只读 ShowHandler](https://github.com/ollama/ollama/blob/v0.40.1/server/routes.go#L1573) 和 [model/source 处理](https://github.com/ollama/ollama/blob/v0.40.1/server/images.go)。

| 本轮执行 | 结果 |
| --- | --- |
| `mvnw.cmd -Dtest=OllamaProviderTest,ModelFoundationTest#localAdmissionDigestDriftTrustedCompletionAndUncertaintySurviveRestart test` | Provider 文件全部 PASS；ModelFoundation 方法在 fixture 凭据初始化阶段 ERROR：`Cannot initialize private local client credentials: DIRECTORY_PREPARE/IO_FAILURE`。整体命令失败，未进入模型行为断言；未修复 ACL/Native 或重跑该方法。 |
| `mvnw.cmd -Dtest=OllamaProviderTest#installedDefaultMetadataRequiresStableSupportedVersionAndPositiveLocalSource package` | 在补充 0.40.1 identity/context/source 拒绝断言后，该方法 PASS；Java 编译及 Spring Boot JAR package PASS。 |
| 独立 Java `ReadonlyCatalogProbe` 调用实际 `OllamaProvider.catalog(8192, ...)` | 真实 `0.40.1` catalog PASS；两个本地模型均识别，digest/context 与真实 tags 对应一致。仅 Provider 调用，没有 Spring/凭据/state manager/Native 初始化。 |
| 构建 JAR 中两个 owning class 与 probe 使用的编译 class 逐字节比较 | 一致。 |
| 入场候选文件 SHA-256 比较、`git diff --check` | PASS。 |

没有运行全量 Java、Desktop、Frontend 或完整 Windows 验收。Desktop 实际点击“只读刷新模型”尚未在新 JAR 上执行。Provider 真实通过与 GUI 验收是不同证据。

日志和原始 metadata 在 ignored `.verification/mmf3-ollama-0401/`；原始 metadata 含本机路径，不进入源码 fixture。

## Runtime JAR 身份

- 文件：`target/personal-ai-workspace-0.1.0.jar`
- 大小：`46319267` bytes
- SHA-256：`d4db066fc18f19fc7771c5eb79883d8cc634fbd2b033fd68c642827eb6e55cd5`
- Git：`codex/mmf3-runtime-private-acl-fix` / `cb36879d93aa9a23c8b3d94e9fa26f5166a3c659` + 既有候选 + 本轮未提交改动。JAR 包含当前工作树候选，不能仅用 HEAD 标识。

## 当前 Development-only Profile 的手动重启

1. 记录当前已经验收通过的 Profile RunId。若该 Profile Runtime 仍运行，只在它自己的控制台按 Ctrl+C 正常结束，并确认 `18765` 已空闲。不要结束未知进程或 Ollama。本轮不自动重启。
2. 在 PowerShell 7.4+ 中执行以下命令，将 `$runId` 替换为该已验收 RunId；复用同一 Profile、token 文件和 Credential Manager target，不 Prepare/Provision/导入新凭据。Verify 或 Native admission 失败即停止。

```powershell
Set-Location 'D:\IDEA\Daima\personal-ai-workspace'
$ErrorActionPreference = 'Stop'
$runId = '<当前已验收的32位lowercase hex RunId>'
$repo = (Get-Location).Path
$jar = Join-Path $repo 'target\personal-ai-workspace-0.1.0.jar'
if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    'd4db066fc18f19fc7771c5eb79883d8cc634fbd2b033fd68c642827eb6e55cd5') { throw 'JAR identity mismatch' }
if (@(Get-NetTCPConnection -State Listen -LocalPort 18765 -ErrorAction SilentlyContinue).Count) {
    throw '18765 occupied; stop only the known Profile Runtime manually'
}
$state = & '.\scripts\verification\mmf3-profile-state.ps1' -Mode Verify -RunId $runId | ConvertFrom-Json
$root = $state.Root
$helper = Join-Path $repo 'bootstrap\PersonalAiWorkspace.NativeBootstrap\bin\Release\net10.0-windows\PersonalAiWorkspace.NativeBootstrap.exe'
$native = Join-Path $root 'jna-5.17.0-win32-x86-64'
$evidence = Join-Path $root 'evidence'
if (!(Test-Path -LiteralPath (Join-Path $evidence 'sqlitejdbc.dll') -PathType Leaf)) { throw 'Existing Profile SQLite native DLL missing' }
$java = (Get-Command java.exe -ErrorAction Stop).Source # 使用已验收的 Java 21
$javaArgs = @(
    "-Dworkspace.native.development-profile=$runId", "-Dworkspace.native.bootstrap=$helper",
    "-Djna.boot.library.path=$native", '-Djna.boot.library.name=jnidispatch',
    '-Djna.nosys=true', '-Djna.noclasspath=true', '-Djna.nounpack=true',
    "-Djava.io.tmpdir=$evidence", "-Djna.tmpdir=$evidence",
    "-Dorg.sqlite.lib.path=$evidence", '-Dorg.sqlite.lib.name=sqlitejdbc.dll',
    '-jar', $jar, '--server.address=127.0.0.1', '--server.port=18765',
    "--workspace.security.token-file=$(Join-Path $root 'credentials\client-token')",
    "--workspace.data-directory=$(Join-Path $root 'data')",
    "--workspace.model-state-directory=$(Join-Path $root 'model-state')"
)
& $java @javaArgs
if ($LASTEXITCODE -ne 0) { throw 'Development Profile Runtime startup failed' }
```

3. 保持 Runtime 控制台运行。当前 Profile Desktop 恢复连接后，点击“只读刷新模型”；必要时正常退出并以原 Profile Debug 程序和 `--mmf3-profile-acceptance <同一RunId>` 重开 Desktop。不要使用正式 `start-workspace.ps1` / release launcher，也不要点击加载、验证推理、切换或释放。此步骤为待执行的手动确认。
