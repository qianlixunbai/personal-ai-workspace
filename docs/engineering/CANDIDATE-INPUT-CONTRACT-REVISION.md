# 真实输入发现后的最小修订

2026-11-11；基于输入审阅 checkpoint `16762762860b487b9df07d0908bf0d6338fa4b8c`。只修改 Build.ps1 与输入 schema，并记录本页评估。未运行 Guard、Build、restore、产品/helper、原生入口、Lombok 或旧检查套件；没有形成锁文件、候选包或新的 finance class。

## NuGet 两种哈希

输入格式升级为 `WORKSPACE_CANDIDATE_INPUTS_V2`。每包必须分别提供 `archiveSha512` 与 `contentHash`；不再接受旧的模糊 `sha512` 字段。

- `archiveSha512` 是整个原始 nupkg 的 Base64 SHA512，包含签名元数据。restore 前对私有 feed 的实际 archive 求值比较，已有输入树 SHA256 绑定继续保留。
- `contentHash` 是 NuGet 排除签名元数据后的内容身份。restore 前逐项核对两份真实锁文件中的非 Project 依赖；restore 后核对 assets 的 package sha512，并读取此次空私有缓存中 NuGet 新生成的 `.nupkg.metadata.contentHash`。后者覆盖全部审阅包，包括不在 assets.libraries 中的 runtime download 包；缺失 metadata 也拒绝，不默许未使用包。
- restore 前拒绝非空包缓存；restore 后再次确认每个缓存 nupkg 的原始 SHA256 与已固定 feed archive 相同。旧用户缓存的 metadata 不会被复制为验证结果。只有所有比较成功才继续 publish，并生成 `evidence/nuget-hashes.json`。

这不是把内容 hash 重命名成 archive hash：两值在不同阶段与不同对象比较，互换会在原始 SHA512 或内容检查失败。对签名包，本轮使用前次保存的真实证据：八个原始 archive SHA512 均等于其 `.nupkg.sha512`；四个普通包的 assets 值均等于 `.nupkg.metadata.contentHash`，而不等于原始 digest。四个 runtime 包的历史 contentHash 只有缓存 metadata 线索，未来必须由新私有提取结果验证；没有在本轮声称重算过 NuGet 归一化算法或验证过包签名。

原始证据位于 `.verification/candidate-input-audit-7b04d09125794129a0f634bf7a6fd23d/continued/nuget-hash-comparison.json` 与 `nuget-packages.json`，哈希索引在 `ISOLATED-CANDIDATE-INPUT-AUDIT-CATALOG.json`。语义来源是 [NuGet 官方 metadata 设计](https://github.com/NuGet/Home/wiki/Nupkg-Metadata-File)。

## 七份完整第三方声明

V2 必须显式提供七项 `noticeFiles`，每项包含规范目标路径、原始字节数和 origin（含 SHA256）。Build.ps1 还固定以下来源、版本、条目、长度与实际审阅 hash；仅有同 hash 的其他来源不被接受。

| ThirdPartyNotices 下目标 | 唯一来源 | 原始字节数 |
|---|---|---:|
| dotnet-desktop-LICENSE.txt | Microsoft.WindowsDesktop.App.Runtime.win-x64 10.0.14 / LICENSE | 1,137 |
| dotnet-runtime-LICENSE.txt | Microsoft.NETCore.App.Runtime.win-x64 10.0.14 / LICENSE.TXT | 1,139 |
| dotnet-runtime-NOTICES.txt | 同包 / THIRD-PARTY-NOTICES.TXT | 78,041 |
| WebView2-LICENSE.txt | Microsoft.Web.WebView2 1.0.4258.31 / LICENSE.txt | 1,487 |
| WebView2-NOTICE.txt | 同包 / NOTICE.txt | 3,894 |
| react-LICENSE.txt | 已固定 frontend 树 react/LICENSE | 1,088 |
| react-dom-LICENSE.txt | 已固定 frontend 树 react-dom/LICENSE | 1,088 |

restore 前核对七项来源及完整字节，拒绝省略、增加、重复、大小写别名、来源替换、hash/长度不符以及与 publish 白名单相冲突的路径。publish 本身仍先完整核对；七份声明随后在新的 payload 中用 CreateNew 写入，重新读取固定来源、验证写出字节，并记录独立 provenance。没有文本解码/重编码、拼接或许可证生成，不覆盖已存在输出。最终 payload inventory 与内外 ZIP 校验包含这些文件。项目原有的另外七份声明仍由 csproj publish。

来源证据为前次 `publish-scope-proposal.json`、`notice-source-facts.json` 和对应 nupkg entry 清单；这些证明字节/来源对应，不授予 finance 或 BOOT.SF 复用许可。

## 两份锁文件的最小形成方案（仅评估）

不用旧 assets 改写锁，不需要运行产品。先独立审阅一个仅 restore 的新步骤：把固定 Git 的 Desktop/Core 项目及适用 props、固定 SDK/运行包和八包离线 feed 放到全新工作目录，使用新的空包缓存、清空外部源的 NuGet.Config、受审输入代码、共享重负载锁、自有 Job 与超时回执。它不是复用被拒脚本，也不调用有真实锁门槛的 Guard/Build 入口绕过门槛。

首次只请求 `dotnet restore <Desktop.csproj> -r win-x64 --use-lock-file --configfile <private-config> -p:NuGetAudit=false -p:Configuration=Debug`，加上与候选 Build-Desktop 完全相同的 Mmf3、SelfContained、RuntimeFrameworkVersion=10.0.14、固定 props/targets、关闭共享编译/节点复用等属性；**不使用 --locked-mode**，不调用 build/publish/产品。项目引用应一并产生 Core 锁；若缺任一锁则停止，不由 assets 补造。

输出后只读复核两份原生生成锁的 TFM/RID、完整依赖图、contentHash、新缓存身份与工具/源码 hash，再将两份文件作为待审阅输入固定。当前范围不授权这次 restore，未创建或运行此方案的脚本。restore 会求值 MSBuild/包构建逻辑，因此仍须预先审阅 imports/targets/analyzers；没有以“仅 restore”承诺无代码执行。启用锁与 locked-mode 的区别见 [NuGet 官方文档](https://learn.microsoft.com/en-us/nuget/consume-packages/package-references-in-project-files#locking-dependencies)。

## finance 重建与 Lombok（仅评估）

现有固定模块原样重建需要 Lombok 注解处理：54 个 Java 源中 10 个使用 @Data 或 @Getter/@Setter（含嵌套配置类）。例如 Account.java 只有私有字段；其他服务调用 Asset.getSymbol/getId、MarketQuote.getSymbol 及 Asset.setId/setSymbol 等生成的方法。只把 lombok.jar 放到 classpath 后继续使用 `-proc:none` 无法产生这些方法。手工补方法或 delombok 都是另一项源码/处理步骤变更，不能假装零行为变化。

模块 POM 明确配置 Lombok annotationProcessorPaths；Spring Boot dependency POM 4.1.1 固定 Lombok 1.18.46。本地精确来源：`C:\Users\28421\.m2\repository\org\projectlombok\lombok\1.18.46\lombok-1.18.46.jar`，2,045,364 B，SHA256 `01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126`。本轮只读 ZIP 中 manifest、LICENSE 和处理器 service 声明；manifest 声明 1.18.46，service 列出 `lombok.launch.AnnotationProcessorHider$AnnotationProcessor` 与 `$ClaimingProcessor`。未运行 jar/main/javaagent/processor。

包内 LICENSE 是 5,378 B，SHA256 `76479448741d7a7a3a97b6afd9ab9699d95621faafc65a1b8e9149342ea00feb`：Lombok 主体 MIT，文件还包含 bundled ASM 的 BSD 条款及 cmdreader/lombok.patcher 的许可声明；评估与审阅材料保留整个 LICENSE，不能只摘 MIT 首段。许可名称及编译期用法也见 [Lombok 官方 javac 文档](https://projectlombok.org/setup/javac)。本地包/版本证据不等于发行者签名认证。

最小独立方案是单独的 finance 编译阶段：固定 Git 的 54 源文件与 21 资源、同一 JDK 21.0.7、精确依赖 JAR 顺序、仅这个 Lombok JAR 的 processorpath、显式列出的上述处理器，以及新的输出/临时目录；不让其他依赖隐式参与 processor discovery。其后 Workspace 主源码仍使用原来的 `-proc:none`，只引用新生成的 finance JAR。Lombok 会在 javac 进程内执行并改变编译树，生成访问器/对象方法，因此它是新增受审构建代码，不是纯数据。还须固定/审阅适用 lombok.config（包含向父目录查找的影响），再允许处理器运行。

重建不会执行应用/helper/native，但需要另行授权新的编译动作及新输入规范，产生新的 finance hash 和明确来源回执；不要求或暗示与 sourceDirty=true 的旧 85 个 class 字节完全相同。若不重建，旧 finance JAR 的显式复用决定仍由父任务作出；BOOT.SF 的空标记决定也保持未定。

本轮行为测试为 0；仅 PowerShell AST 解析、JSON 读取、现有证据逐项核对和 diff 源码审阅。旧回执不变，不能声称此修订已经真实 restore/组装通过。
