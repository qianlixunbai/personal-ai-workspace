# SQLite Long-Path Recovery Reliability

2026-10-20（Asia/Shanghai）。**本机真实 Windows / 合成数据恢复聚焦验证 PASS，历史约 318 字符路径问题已修复。** 不代表完整桌面重新验收或正式发布批准。

## 基线与范围

- 起始分支 `codex/webview2-react-startup-recovery`，HEAD `23657e0776565c4bed7c565f2894dec3676bd3ef`，与要求一致。
- 独立分支 `codex/sqlite-long-path-recovery`；实现和本报告本地提交，最终 HEAD 见 Git / 交付回复。
- 开始时只有指定八个 B4A6 未追踪文件；它们继续未追踪。没有运行 B4A6 脚本，没有 reset、clean、stash、覆盖历史、push、merge、tag 或 release。
- 阅读了 Windows Native Security Modernization、WebView2 Startup Recovery、ADR-006、现有恢复与 ACL 源码、SQLite/JDBC 配置和历史失败日志。仓库中未发现名为 Global Open-Source Benchmark 的独立报告；现有最新开源评估为 `GITHUB-OPEN-SOURCE-EVALUATION.md`（2026-10-19），已阅读其存储及恢复边界结论，未进行新架构审查。
- 没有修改 Native Gate、Profile 根合同、ACL、DPAPI、Runtime Owner、前端、PostgreSQL、Finance 或 Provider；没有新增依赖、备份格式、公开 API 或错误码。

## 真正根因与七类区分

本轮识别并修复两个生产路径问题，不能只归咎于测试夹具。

1. **SQLite 路径形式与 VFS**：原 `MemoryStore` 直接传入普通 DOS 路径。固定 DLL 在深层路径调用 Windows 文件 API 时需要扩展前缀；默认 `win32` VFS 另外还有 1040 UTF-8 bytes 的路径缓冲上限。只加前缀可以通过 318 字符，但不足以通过更深路径；只选择 `win32-longpath` 也不足以通过本机 318 字符路径。两者须结合。
2. **JDK 同卷误判**：数据库构建通过后，原 `Files.getFileStore(...).equals(...)` 仍可能拒绝发布。JDK 21 的 `WindowsFileStore.equals()` 比较卷根字符串；长 staging 返回 `\\?\C:\`，较短父目录返回 `C:\`，同一实际卷却不相等。本机已捕获 `STAGING_STORE=系统盘 (\\?\C:)`、`PARENT_STORE=系统盘 (C:)` 和 `FILESTORE_SAME=false`。

| 待区分项 | 实际结论 |
| --- | --- |
| 测试夹具路径错误 | 历史 Win32 metadata helper 未加前缀是前一个独立 fixture 问题；基线已修正。本轮另修复一个既有恢复测试的空目录夹具，使用现有私有目录创建 API，符合生产已有准入要求 |
| Java 路径规范化 | 原失败形态在绝对、normalize 后仍失败；没有用 realpath 别名、短文件名或目录映射绕过身份验证。另发现上述 JDK FileStore 表示差异 |
| SQLite JDBC/native 限制 | 默认路径与默认 VFS 有实际限制；既有 3.53.2.0 DLL 已包含可用的 `win32-longpath`，无需升级/替换 DLL |
| staging 过深 | 不可预测的 UUID 同级 staging 比用户选择的短目录名更长，会先触及限制；仍保留原位置、命名及独占创建方式 |
| sidecar / journal / WAL | 原 code 14 可在普通数据库 open/write 复现，不是只在 WAL 才发生。修复后深路径 PERSIST journal、WAL、SHM 创建、既有 ACL 准入和重新打开均实际通过 |
| 扩展前缀兼容 | JDBC URI 中把 `?` 编码为 `%3F`，并编码文件名的 URI 保留字符；前缀只用于内部 SQLite 文件打开，不变更对外路径合同 |
| 文件身份与发布 | 既有安全拒绝没有被取消；修复了同卷比较的表示差异，保留真实身份复验与无覆盖发布 |

固定 DLL、JDK 源码及 SQLite 3.53.2 `os_win.c` 与真实行为相符。上游源码及文档是解释依据，测试结果来自本机执行。

## 最小修改

- `MemoryStore.java`：Windows SQLite 连接统一使用带扩展前缀的 file URI 和 `vfs=win32-longpath`；主连接与 `openReadSnapshot()` 同时调整。使用正常锁实现，未使用 `-none` VFS。非 Windows 和 PostgreSQL 分支保持原行为。
- `MemoryBackupService.java`：Windows 同卷判断复用既有 JNA 的 `GetVolumePathNameW` / `GetVolumeNameForVolumeMountPointW`，比较 OS 返回的卷 GUID。查询失败继续安全拒绝；不按卷标签或盘符猜测，不增加跨卷复制回退。其他平台继续使用原 FileStore 检查。
- `MemoryBackupTest.java`：新增一条综合长路径回归，覆盖两个发布分支、重开/快照、完整性、修订、搜索、不覆盖和失败清理；正常路径既有测试的空目录改为调用 `PrivateMemoryDirectory.directory()`。没有删除失败断言、禁用用例或放宽结果。

## 本轮真实执行证据

环境：Windows 11 Pro x64 `10.0.26100`，Oracle JDK `21.0.7+8-LTS-245`，sqlite-jdbc `3.53.2.0`，JNA `5.17.0`。实际加载的 `sqlitejdbc.dll` SHA-256 由既有 ACL 综合测试核对为 `7d3281c7adbf3d2fe6b07462dee9cb310194c302154175f172ced3aac05c91c3`。

本轮 Codex 打包宿主中的新建/既有 Profile 准入分别出现 `Development directory identity rejected` / helper `UNEXPECTED`，未进入 SQLite。没有修改 Gate 或历史 Profile。随后使用前轮已验证的普通交互式 Windows 上下文，以当前账户 Interactive / Limited 一次性计划任务创建全新合成 Profile 并通过原准入，再运行测试。任务均已注销；无密码、提权、系统代理或全局配置修改。

### 诊断对照

下表长度为本轮合成数据库路径的 UTF-16 单元数；PASS 表示真实 create/insert/quick_check，不只是建立连接。

| 路径长度 | 普通路径 / 默认 VFS | 扩展前缀 / 默认 VFS | 普通 URI / longpath | 扩展前缀 URI / longpath |
| --- | --- | --- | --- | --- |
| 240 | PASS | PASS | PASS | PASS |
| 259 / 260 | code 14 | PASS | code 14 | PASS |
| 318 | code 14 | PASS | code 14 | PASS |
| 1100 | code 14 | code 14 | code 14 | PASS |

证据：`.verification/sqlite-long-path-probe.log`。原历史 `.verification/windows-modernization-storage-diagnostic.log` 的 318 字符 code 14 保留，不能把它改写为历史 PASS。

首次修复连接后，新测试仍在发布前失败；独立诊断显示 staging 数据库构建成功，而 FileStore 比较错误。失败记录保留在 `sqlite-long-path-tests-initial-failure.log`、`sqlite-long-path-initial-test.xml` 和 `sqlite-long-path-volume-mismatch.log`，最终修复后才通过全部指定断言。

### 最终聚焦验证

| 实际执行 | 结果 / 证据 |
| --- | --- |
| `test-compile -DskipTests` | PASS，仅编译，不计为测试通过；`sqlite-long-path-compile.log` |
| 既有正常恢复 `newAndEmptyTargetsPreserveEveryFieldRebuildSearchAndLeaveLiveDatabaseBytesUnchanged` | PASS，新目标及已有空私有目录；字段、搜索、修订、schema、quick_check、源数据库字节不变 |
| 既有失败恢复 `injectedFailureAndRacingTargetCannotPublishPartialDataOrChangeSource` | PASS，插入回滚、发布前失败、竞态目标不覆盖及清理 |
| 新综合长路径恢复 | PASS，目标 `memory.db` 长度精确为 240 / 318 / 1100；staging 更长。含空格、`%23`、`#`、`&`、中文的目录名。每种长度验证新目标和已有空目录发布、真实关闭重开及独立只读快照、源字段及修订保持、旧 revision 拒绝、搜索及 quick_check；二次恢复不覆盖；注入失败无发布且父目录无临时内容 |
| 既有 `WindowsPrivateAclTest.protectedBoundarySurvivesOwningFlowsAndRejectsUnsafeInput` | 整项 PASS，未把前半流程算作全项成功；包括实际 DLL 身份检查。以上共同证据：`sqlite-long-path-tests.log`、`sqlite-long-path-final-memory-test.xml`、`sqlite-long-path-final-acl-test.xml` |
| 既有 `WorkspacePublicationTest.stagingFailureRacingNewAndEmptyTargetsAndInsertRollback` | PASS；共享发布函数的 Workspace 分支回归，`sqlite-long-path-workspace-publication.log` / `sqlite-long-path-workspace-test.xml` |
| 临时合成 sidecar / 卷身份探针 | PASS，超过 1100 字符路径的 PERSIST journal、WAL / SHM、既有 sidecar ACL 准入、快照和重开；真实 C: 与 D: 卷 GUID 判为不同，Profile 与父目录判为同卷；`sqlite-long-path-sidecars.log` |
| 残留与静态检查 | PASS，各探针清除自身合成数据库；JUnit 清理临时目录。新 Profile evidence 内数据库/sidecar/恢复 staging 残留均为零，仅保留固定 sqlitejdbc.dll；`sqlite-long-path-cleanup.json`。`git diff --check` PASS |

最终测试选择命令（通过上述普通上下文运行，`argLine` 是新合成 Profile 的原 Gate/JNA/固定 SQLite DLL 参数）：

```powershell
./mvnw.cmd -pl workspace-application -am `
  '-Dtest=MemoryBackupTest#newAndEmptyTargetsPreserveEveryFieldRebuildSearchAndLeaveLiveDatabaseBytesUnchanged+injectedFailureAndRacingTargetCannotPublishPartialDataOrChangeSource+windowsLongPathsRestoreReopenAndFailWithoutPublishingOrResidue,WindowsPrivateAclTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=$fixtureOptions" test

./mvnw.cmd -pl workspace-application -am `
  '-Dtest=WorkspacePublicationTest#stagingFailureRacingNewAndEmptyTargetsAndInsertRollback' `
  '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=$fixtureOptions" test
```

每种长度/每个断言是综合流程覆盖点，不是独立测试执行数。未运行 Java 全套、Desktop/Frontend、旧 B4A6、Browser/IME、Provider、PostgreSQL 或打包矩阵。共享发布回归通过后没有再次机械重跑已绿恢复测试。

## 安全合同、限制与用户操作

**原失败长度已支持**：本机真实 native open/write 的 318 字符对照及最终 318 字符目标恢复/重开均通过；同时通过 1100 字符目标及更深 staging。没有把 staging 移到短的公共目录，没有复制任何真实数据库。

继续保留同卷 staging、私有目录/文件验证、CREATE_NEW、既有文件身份复验、不覆盖已有目标、重建事务及完整性检查、关闭后无覆盖重命名，以及失败时仅清理任务自有 staging 的已知 SQLite 文件。卷身份无法查询时拒绝发布；不引入降级复制。保留原 ACL 策略与同 SID / 管理员信任边界，不宣称防御恶意同账户进程或新增长断电持久性保证。

本轮实测范围是本机 Windows 11 / NTFS / 固定 JDK 与 SQLite DLL。没有测试所有 Windows/JDK 版本、ReFS、网络盘、UNC、设备命名空间或全部极限长度；这些不属于新增支持承诺。原目标路径上限 8192 UTF-16 单元和 Windows 目录名/位置/重解析拒绝合同保持，未把 8192 上限写成全部已实测。原有 OS 锁定导致清理失败的 best-effort 限制仍存在；本轮注入失败及残留检查实际通过。

无需用户修改注册表、长路径策略、ACL、Profile 或手工移动数据库。要使用源码修复，需要后续按既有受信开发构建流程构建；**现有开发 ZIP 没有被重建或替换，因此不包含本次源码修复**。本轮没有必要重新生成 375 MB ZIP。

**正常开发版启动影响**：既有开发包字节未变，其前轮正常交互式 Windows 启动、React/Bridge/SQLite 和退出/重开 PASS 是历史证据。本轮源码的正常 MemoryStore 初始化、Workspace schema 升级、快照、恢复及 ACL 综合流程通过，未观察到 SQLite 启动层回归；没有执行完整 Desktop EXE / WebView2 启动，不能声称新源码的全客户端重新验收通过。Codex 宿主的 Profile 身份限制保持独立事项。

交付时 tracked working tree 干净；仅原八个 B4A6 文件未追踪。历史 Profile、receipt、备份和开发 ZIP 保留。至此结束本轮修复，等待用户确认。

## 官方依据

- [SQLite VFS](https://www.sqlite.org/vfs.html)：Windows VFS 及 URI 选择方式；本轮在线读取。
- [SQLite 3.53.2 Windows VFS 源码](https://github.com/sqlite/sqlite/blob/version-3.53.2/src/os_win.c)：固定版本默认/longpath VFS、宽字符路径转换；本轮读取对应源码。
- sqlite-jdbc `3.53.2.0` 本地 sources JAR：核对 `SQLiteConnection` 的 URI 处理和 `SQLiteConfig` 默认 `OPEN_URI`；未根据最新主线替代固定版本结论。
- JDK `21.0.7` 本机 `lib/src.zip` 的 `WindowsFileStore.java`：核对 `equals()` 按卷根字符串比较的实现。
- [GetVolumePathNameW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getvolumepathnamew)、[GetVolumeNameForVolumeMountPointW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getvolumenameforvolumemountpointw)：本轮经官方文档核对签名、返回值及卷 GUID 行为，调用复用既有 JNA 绑定。
