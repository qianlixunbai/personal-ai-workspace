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

### 2026-11-11 后续精确诊断：未重跑构建

诊断仅读取原执行记录、原 Maven 日志、本地同版本 JDK 的 `lib/src.zip`，并查询精确目标及其祖先的普通元数据。没有重新调用目标的 `toRealPath`、重读 JAR 内容、遍历 `.m2`、改 ACL、复制／改名依赖、换仓库或执行 Maven。以下事实补充前述失败记录，不将失败中间产物提升为成功构建。

**原执行面及审批证据。** 本线程 `6af41af1-0f14-8191-ad41-5e56c4f4b01c` 的 `read_thread` 原命令记录 `exec-aa71843b-7e98-4fc5-a57a-5b657d5ba0b5` 确认：外层解释器为 `C:\Users\28421\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe -Command`；cwd 为上述 `R/source`，不是原仓库根；Maven 可执行文件、全部参数、JAVA_HOME 与开始／退出回执一致。前一条 `exec-2d3650fe-1473-4b80-b589-6455c918d0df` 是解析期失败，不是第二次构建。

实际 Maven 命令完整展开为：

```powershell
& 'C:\Users\28421\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd' -o -B -ntp -X -s 'D:\IDEA\Daima\personal-ai-workspace\artifacts\java-compile-20261111-fec9222/settings.xml' -gs 'D:\IDEA\Daima\personal-ai-workspace\artifacts\java-compile-20261111-fec9222/settings.xml' -l 'D:\IDEA\Daima\personal-ai-workspace\artifacts\java-compile-20261111-fec9222/maven-compile.log' -Dmaven.test.skip=true compile
```

Java 可执行文件为 `D:\IDEA\Daima\personal-ai-workspace\artifacts\PersonalAiWorkspace-knowledge-conversation-workflows-20261110\runtime\java\bin\java.exe`。日志第 3／514 行确认该运行时，第 852 行 `fork = false`；javac 是该 JVM 内的编译器，未启动单独 javac.exe。日志第 378 行记录 `COMSPEC=C:\windows\system32\cmd.exe`。正常 `mvn.cmd` 第 180 行先调用同一 java.exe 的 `--enable-native-access=ALL-UNNAMED -version` 探测，第 185–195 行启动 Classworlds；日志第 399 行确认本次 INTERNAL_MAVEN_OPTS 包含该选项，第 670 行确认 launcher 参数与上述 Maven 参数一致。按已读取的 launcher 源码及回执展开，主 Java 调用结构如下（用于说明执行面，未在诊断中执行；没有单独保存 OS 进程命令行采样）：

```text
<上述 java.exe> --enable-native-access=ALL-UNNAMED
  -Xmx512m -XX:ActiveProcessorCount=2
  -Djava.io.tmpdir=<R>/tmp -Duser.home=<R>/home -Djava.awt.headless=true
  -classpath <Maven根>/boot/plexus-classworlds-2.11.0.jar
  -Dclassworlds.conf=<Maven根>/bin/m2.conf -Dmaven.home=<Maven根>
  -Dlibrary.jansi.path=<Maven根>/lib/jansi-native
  -Dmaven.multiModuleProjectDirectory=<R>/source
  org.codehaus.plexus.classworlds.launcher.Launcher <上述全部 Maven 参数>
```

**工具权限模式仍有一项记录缺口，不能猜测。** 原 `maven-compile-start.json` 不含 sandbox 权限字段；`read_thread` 返回该条记录的字段仅为 type/id/command/cwd/status/exitCode/durationMs/output，没有原 `exec_command` 的 `sandbox_permissions`、justification 或审批决定。针对本线程 ID 的本地当天 rollout 文件名精确查找也未命中，未扩大搜索。因此当前证据不能确认该次 Maven 是 `use_default` 还是 `require_escalated`，也不能确认 Maven 的正式审批是否已经使用或覆盖范围。此前正常 Git 暂存／提交使用过正式审批，不等于审批覆盖 Maven。已确定的是 Maven 实际执行后发生 Windows 文件访问异常；现有回执没有 auto-review 拒绝 Maven 的消息。本轮元数据查询明确使用默认执行模式且全部成功。

**文件及目录。** `2026-11-11T08:19:40.8813467Z` 查询结果保存在 `R/jar-metadata-diagnostic.json`：目标是普通 `System.IO.FileInfo`，594187 字节，属性 Archive，创建和最后写入时间均为 `2026-10-23T18:38:12.8363140Z`，LinkType/Target 为空。目标及路径上逐个精确查询到 `C:\` 的所有祖先均无 ReparsePoint 属性；没有枚举这些目录的子项。`C:\Users` 为 ReadOnly/Directory，`C:\` 为 Hidden/System/Directory，其余祖先为 Directory；这些属性不能独立推导有效访问权。

目标、`2.21.5` 及 `jackson-core` 两级目录的 owner 均为 `千里寻白\28421`，ACL 继承开启，普通 DACL 读取均成功；读到的 5 条 ACE 全为继承的 Allow，没有 Deny：`SYSTEM`、`Administrators`、`千里寻白\28421` 为 FullControl；`千里寻白\CodexSandboxUsers` 和 `S-1-5-21-2401927692-4182424708-3186836709-906716766` 为 Modify/Synchronize/DeleteSubdirectoriesAndFiles。完整 SDDL 留在上述 JSON。没有读取 SACL／有效令牌，也没有对更上级目录读取 ACL；这些结果不能证明原 Java 进程拥有相同的有效权限。

**读取与关闭顺序。** 这是由本地同一 JDK 源码及堆栈共同支持的结论：`JavacFileManager.ArchiveContainer` 第 559–580 行先成功创建 ZIP 文件系统并建立包索引，之后才会出现在 file manager 的容器关闭循环；`ZipFileSystem` 第 170–179 行依次做读取检查、以 READ 打开字节通道并读取 central directory。此次堆栈已经进入该容器的 close，故支持编译器曾成功打开该 JAR 并读取 ZIP 目录；不能据此声称每个 class 条目都读过，也没有逐条加载日志。已有依赖回执另记录此前只读哈希成功，SHA256 为 `b64b5874162b503a0e58a8f7758266e8dd9f91bf49e3a59ae0b5f47589a231b7`；本轮没有重读目标内容。

`ZipFileSystem.close` 第 467–526 行先关闭剩余流、完成 sync／`ch.close()` 及压缩器清理，然后调用 `provider.removeFileSystem`；后者第 324–330 行先 `toRealPath`，成功才移除内部映射。本次失败发生在该映射移除之前，不是初始 JAR 打开，也不是删除依赖文件。`WindowsLinkSupport.getRealPath` 第 255–288 行对各路径段调用 `FindFirstFile`，捕获异常后使用完整原路径重新抛出。因此日志里的完整 JAR 路径不证明被拒的是末尾文件这一段；确切失败的路径段、文件占用、受限令牌、安全软件及有效权限均仍未知。没有安装诊断软件、扫描未知进程或重现该操作。

完整异常链保存在 `R/access-denied-full-stack.txt`，逐行来自原日志 1040–1166 行，包含 Maven／Plexus 包装异常。根因 AccessDeniedException 的全部堆栈如下，无省略：

```text
Caused by: java.nio.file.AccessDeniedException: C:\Users\28421\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.21.5\jackson-core-2.21.5.jar
    at sun.nio.fs.WindowsException.translateToIOException (WindowsException.java:89)
    at sun.nio.fs.WindowsException.rethrowAsIOException (WindowsException.java:96)
    at sun.nio.fs.WindowsLinkSupport.getRealPath (WindowsLinkSupport.java:288)
    at sun.nio.fs.WindowsPath.toRealPath (WindowsPath.java:947)
    at sun.nio.fs.WindowsPath.toRealPath (WindowsPath.java:42)
    at jdk.nio.zipfs.ZipFileSystemProvider.lambda$removeFileSystem$0 (ZipFileSystemProvider.java:324)
    at java.security.AccessController.doPrivileged (AccessController.java:571)
    at jdk.nio.zipfs.ZipFileSystemProvider.removeFileSystem (ZipFileSystemProvider.java:326)
    at jdk.nio.zipfs.ZipFileSystem.close (ZipFileSystem.java:526)
    at com.sun.tools.javac.file.JavacFileManager$ArchiveContainer.close (JavacFileManager.java:657)
    at com.sun.tools.javac.file.JavacFileManager.close (JavacFileManager.java:735)
    at org.codehaus.plexus.compiler.javac.JavaxToolsCompiler.compileInProcess (JavaxToolsCompiler.java:177)
    at org.codehaus.plexus.compiler.javac.JavacCompiler.performCompile (JavacCompiler.java:337)
    at org.apache.maven.plugin.compiler.AbstractCompilerMojo.executeReal (AbstractCompilerMojo.java:1236)
    at org.apache.maven.plugin.compiler.AbstractCompilerMojo.execute (AbstractCompilerMojo.java:708)
    at org.apache.maven.plugin.compiler.CompilerMojo.execute (CompilerMojo.java:235)
    at org.apache.maven.plugin.DefaultBuildPluginManager.executeMojo (DefaultBuildPluginManager.java:126)
    at org.apache.maven.lifecycle.internal.MojoExecutor.doExecute2 (MojoExecutor.java:328)
    at org.apache.maven.lifecycle.internal.MojoExecutor.doExecute (MojoExecutor.java:316)
    at org.apache.maven.lifecycle.internal.MojoExecutor.execute (MojoExecutor.java:212)
    at org.apache.maven.lifecycle.internal.MojoExecutor.execute (MojoExecutor.java:174)
    at org.apache.maven.lifecycle.internal.MojoExecutor.access$000 (MojoExecutor.java:75)
    at org.apache.maven.lifecycle.internal.MojoExecutor$1.run (MojoExecutor.java:162)
    at org.apache.maven.plugin.DefaultMojosExecutionStrategy.execute (DefaultMojosExecutionStrategy.java:39)
    at org.apache.maven.lifecycle.internal.MojoExecutor.execute (MojoExecutor.java:159)
    at org.apache.maven.lifecycle.internal.LifecycleModuleBuilder.buildProject (LifecycleModuleBuilder.java:105)
    at org.apache.maven.lifecycle.internal.LifecycleModuleBuilder.buildProject (LifecycleModuleBuilder.java:73)
    at org.apache.maven.lifecycle.internal.builder.singlethreaded.SingleThreadedBuilder.build (SingleThreadedBuilder.java:53)
    at org.apache.maven.lifecycle.internal.LifecycleStarter.execute (LifecycleStarter.java:118)
    at org.apache.maven.DefaultMaven.doExecute (DefaultMaven.java:261)
    at org.apache.maven.DefaultMaven.doExecute (DefaultMaven.java:173)
    at org.apache.maven.DefaultMaven.execute (DefaultMaven.java:101)
    at org.apache.maven.cli.MavenCli.execute (MavenCli.java:919)
    at org.apache.maven.cli.MavenCli.doMain (MavenCli.java:285)
    at org.apache.maven.cli.MavenCli.main (MavenCli.java:207)
    at jdk.internal.reflect.DirectMethodHandleAccessor.invoke (DirectMethodHandleAccessor.java:103)
    at java.lang.reflect.Method.invoke (Method.java:580)
    at org.codehaus.plexus.classworlds.launcher.Launcher.launchEnhanced (Launcher.java:255)
    at org.codehaus.plexus.classworlds.launcher.Launcher.launch (Launcher.java:201)
    at org.codehaus.plexus.classworlds.launcher.Launcher.mainWithExitCode (Launcher.java:362)
    at org.codehaus.plexus.classworlds.launcher.Launcher.main (Launcher.java:314)
```

**最小后续方案，尚未申请／执行。** 本线程的 `exec_command` 提供正常 `sandbox_permissions="require_escalated"` 和 justification 审批入口，无需改 ACL／全局配置。该接口审批的是所提交命令，不提供一个只为该 JAR 增加 Read 权限的独立参数，不能宣称技术上仅放开单文件。先补齐上述原调用的权限模式与审批记录：若确认原来仅为默认沙盒执行，且有针对本次失败的新相关授权／条件证据，才可把同一绝对 Maven／JDK、同一原 JAR／仓库、同一离线 compile 范围交正常审批评估；不得申请宽泛 Maven 永久前缀。输入／源代码／插件不变，新执行输出须使用新的独立根以保全本次失败证据，不能覆盖 R，也不能单独复用这 85 个 class 跳过 Finance。若查明此前已正式批准仍然失败，或原模式仍不明，则保留阻塞，不换入口重复。这里仅提出审批边界，并不把可用通道、DACL 的 Allow 或诊断任务授权当成已批准重试。

R 中保留配置、源文件清单、依赖清单、完整 Maven 日志、开始／退出回执和全部失败中间输出。`maven-compile.log` SHA256：`1176422d63800e0741d47caa552917dd4be1169bcc7648ef322d15bb9cc0883e`；`maven-compile-exit.json`：`46aea82f172ff6bc8d6e40ac30f355b1df26f7ae68a0f5a2453057da9008ac22`。源清单 SHA256 `c84e71454a58c35cc5a51f21dfda026906e93ba3a04eed818888e3a918be7d44`，依赖清单 `a1933c1463dc83be59397ab8ea30f56ecdfc0851040130988714df880c81931b`。

没有应用 class 输出、Finance JAR、测试执行、Java 产品启动、模型/helper/原生准入、Profile 构建、候选 EXE、部署或新包。此前通过的前端／普通 C# 编译证据保持，不扩大为真实后端或桌面验收。main、六份用户文档、真实资料、旧包和旧中断记录保持；`productionGo=false`、`nativeValidation=NOT_RUN`。
