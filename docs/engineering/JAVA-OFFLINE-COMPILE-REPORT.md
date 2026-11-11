# 正式 Maven 离线编译：输入闭合与实际失败

日期：2026-11-11。产品源码 `fec92229a6b2b62a1bb19a5425ca2361243d33c0`（产品行为与 `7e7332e` 相同）。本轮没有产品源码或 POM 改动，没有重跑前端、C# 或历史测试。

**结果：一次正式 reactor `compile` 已执行，退出 1。** Finance 处理了 54 个 Java 源文件，留下 85 个 class 和 21 份资源；随后 JDK 关闭 JAR 的 ZIP 文件系统时，Windows 真实路径解析返回 `AccessDeniedException`。Maven 将 Finance 判为 FAILURE，workspace-application 为 SKIPPED。这些 class 是失败构建的中间输出，未作为成功 Finance 产物接纳或继续用于其他构建。

## 正常构建输入与独立输出

采用已有 Maven 3.9.16 正常 reactor，不使用旧候选 Build／Guard／GenerateLocks，也未修改冻结候选提案。已有三个 POM 与旧依赖基线相比没有差异。

唯一输出根 R：`D:\IDEA\Daima\personal-ai-workspace\artifacts\java-compile-20261111-fec9222`。将当前 Git 已跟踪的根／模块 POM、`.mvn/wrapper/maven-wrapper.properties`、Finance 源码资源、应用 src/main 共 280 个文件，按原相对目录逐字节复制到 `R/source`；其中 Finance Java 54 个、应用 Java 194 个。每个文件记录相对路径、大小及 SHA256，复制前后相等；没有复制旧 class、target、用户资料或原生配置。编译前再次核对快照哈希。

使用原 POM 的默认目录规则，不传假定有效的 `-Dproject.build.directory`，不改 POM／构建入口。实际 `help:effective-pom` 确认：

- Finance 输出为 `R/source/finance-domain/target/classes`。
- 应用输出为 `R/source/target/classes`。
- 两个源目录和资源目录均位于快照内；原仓库 target/classes、旧包与旧证据不作为输出。
- compile 阶段只绑定 `maven-resources-plugin:3.5.0:resources` 和 `maven-compiler-plugin:3.15.0:compile`。exec 插件的身份／审计钩子在 prepare-package／package，Spring Boot repackage 在打包阶段，均未进入。

构建工具为现有 Maven 分发 `C:\Users\28421\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd`。该版本与项目 wrapper 的 Apache Central 分发 URL 一致；launch/config、boot 与 lib JAR、正式插件的本地哈希记录在 `R/build-tool-inputs.json`。Maven ext 根及其 hazelcast/redisson 目录没有扩展 JAR。Help 3.5.1 与 Dependency 3.10.0 仅用于本次实际配置／依赖收集；均离线成功，没有运行构建生命周期。

JDK 复用已经核实的 `artifacts/PersonalAiWorkspace-knowledge-conversation-workflows-20261110/runtime/java`，Oracle JDK 21.0.7+8-LTS-245；未调用 PATH 的 Oracle javapath。此次重新确认 java.exe SHA256 `d82117d83c6a27cda42126bca1bf9a2dea4f92195ba9e72ced0352fb52d5be2f`、javac.exe `6fe0b34e9b47d255351040c9ad5920cad9c37d5a10a60916cd2c84299d0c40af`。仅作为正常编译工具读取，没有启动旧包的应用。

## 处理器、依赖与官方来源

Finance 原 POM 的 annotationProcessorPaths 只有 `org.projectlombok:lombok:1.18.46`，无传递依赖。本地 JAR SHA256 仍为 `01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126`。实际服务描述文件只有以下两个入口，编译日志中的 processorpath 也仅包含该 JAR：

```text
lombok.launch.AnnotationProcessorHider$AnnotationProcessor
lombok.launch.AnnotationProcessorHider$ClaimingProcessor
```

执行会进入同一 JAR 内的 shadow loader／AST handlers，不能将其描述为只执行两个 class。原源码树和仓库上级未发现 lombok.config；快照根明确写入 `config.stopBubbling = true`，防止构建目录向上寻找非本次输入。它是单独记录的编译配置，不是产品源码改写。没有使用 Java agent、delombok 或 Lombok installer。

正常 Maven 当前 compile 图为 Finance 30 个第三方 JAR、应用 84 个第三方 JAR加当前 reactor Finance，合并 85 个不同第三方 JAR。应用图中 74 个 JAR 与既有 77 项运行依赖清单逐项 SHA256 相同，无不匹配；另 10 个是本地已存在的 Spring Boot 4.1.1 starter。另一个独立输入是 Finance 的 Lombok。没有把旧运行依赖清单强行当作完整编译图。

所有已列第三方 JAR 均实际存在，已读取哈希和 `META-INF/services/javax.annotation.processing.Processor`，应用编译 classpath 中未发现处理器，Finance 只有上述 Lombok。全部本地 `_remote.repositories` 记录为 Central。正式资源／编译插件也是现有 Central 缓存；其 POM 标识 Apache Maven 项目及官方源码位置。Lombok 的 POM 标识 Project Lombok。来源说明使用本地 POM／缓存记录与此前核实哈希，不把缓存标记描述成发行者签名认证；没有联网或下载。

相关官方入口由本地元数据及既有说明给出：[Apache Maven Compiler Plugin](https://maven.apache.org/plugins/maven-compiler-plugin/)、[Apache Maven Resources Plugin](https://maven.apache.org/plugins/maven-resources-plugin/)、[Lombok Maven 配置](https://projectlombok.org/setup/maven)。本轮未重新联网读取这些页面。

## 实际执行与精确阻塞

cwd 为 `R/source`。显式 settings 将 localRepository 指向已有 `C:/Users/28421/.m2/repository`，offline=true；命令同时使用 `-o`，无下载／install／deploy。MAVEN_SKIP_RC=true；本次进程 JAVA_HOME 指向上述 JDK；MAVEN_OPTS 为 `-Xmx512m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=R/tmp -Duser.home=R/home -Djava.awt.headless=true`。未注入外部 Maven 参数、Java agent 或扩展 classpath；用户和系统持久配置未修改。

```powershell
& '<已有 Maven 3.9.16>/bin/mvn.cmd' -o -B -ntp -X -s "$R/settings.xml" -gs "$R/settings.xml" -l "$R/maven-compile.log" -Dmaven.test.skip=true compile
```

仅实际 compile 占用 Session 1 的 `Local\Dot_KAG_Workspace_HeavyRun`，命令结束后 finally 释放。唯一真实 compile 开始于 `2026-11-11T08:08:16.9666410Z`，结束于 `08:08:20.7353638Z`，Maven 报告 2.481 秒。此前一次 PowerShell 命令有 `catch` 空格语法错误，在解析期停止、未执行 Maven；没有隐藏第二次 compile。

拒绝的确切目标：

```text
C:\Users\28421\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.21.5\jackson-core-2.21.5.jar
```

关键调用链（原日志 1125 行开始）：

```text
java.nio.file.AccessDeniedException
sun.nio.fs.WindowsLinkSupport.getRealPath:288
sun.nio.fs.WindowsPath.toRealPath:947
jdk.nio.zipfs.ZipFileSystemProvider.removeFileSystem:326
jdk.nio.zipfs.ZipFileSystem.close:526
com.sun.tools.javac.file.JavacFileManager$ArchiveContainer.close:657
com.sun.tools.javac.file.JavacFileManager.close:735
org.codehaus.plexus.compiler.javac.JavaxToolsCompiler.compileInProcess:177
```

因此已证实的是 **编译器关闭该 JAR 容器时的真实路径解析被拒绝**；不能把它泛化为该文件从未能读取，也没有证据确认是 ACL、占用、安全策略或执行沙盒中的哪一种原因。该文件此前只读哈希核对成功，但这不等于此次 Windows 路径操作获得了相同访问条件。没有 Java 源码诊断可以据此修复；85 个 class 的存在也不能将失败改成 BUILD SUCCESS。

状态为 **实际执行后失败／受文件系统访问拒绝阻塞**，不是审批工具的 auto-review rejection，也不是未知执行结果。已查看 user_message_read，未取得针对本次拒绝的新相关授权／条件证据。按边界停止，没有换 JDK／shell／路径、复制依赖逃避原拒绝、修改 ACL、改变身份或尝试 fork 编译；没有在失败输出上单独推进应用模块。下一步只需围绕该精确 `toRealPath` 操作核实访问条件，不能把它扩展为所有 Java 构建永久禁止。

## 留存与验收边界

R 中保留配置、源文件清单、依赖清单、完整 Maven 日志、开始／退出回执和全部失败中间输出。`maven-compile.log` SHA256：`1176422d63800e0741d47caa552917dd4be1169bcc7648ef322d15bb9cc0883e`；`maven-compile-exit.json`：`46aea82f172ff6bc8d6e40ac30f355b1df26f7ae68a0f5a2453057da9008ac22`。源清单 SHA256 `c84e71454a58c35cc5a51f21dfda026906e93ba3a04eed818888e3a918be7d44`，依赖清单 `a1933c1463dc83be59397ab8ea30f56ecdfc0851040130988714df880c81931b`。

没有应用 class 输出、Finance JAR、测试执行、Java 产品启动、模型/helper/原生准入、Profile 构建、候选 EXE、部署或新包。此前通过的前端／普通 C# 编译证据保持，不扩大为真实后端或桌面验收。main、六份用户文档、真实资料、旧包和旧中断记录保持；`productionGo=false`、`nativeValidation=NOT_RUN`。
