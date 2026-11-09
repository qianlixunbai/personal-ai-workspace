# PDF 文字层导入与完整应用验证

日期：2026-11-09。已补验上一阅读包的实际 EXE 启动，并交付 PDF 选择 → 原生摘录预览 → 确认入库 → 限定检索／原文定位 → 独立应用重开。新的统一预览包包含此前 Word、版本对比、回答排版、历史与导出能力。仍为 **Development-only / unsigned**，不是正式发布；未推送。

## 交付与使用

- [新预览 ZIP](../../artifacts/Workspace-pdf-development-20261109.zip)，384,655,502 字节。完整解压后运行 `PersonalAiWorkspace.Desktop.exe`；本机也可打开[已有目录中的入口](../../artifacts/Workspace-pdf-development-20261109/PersonalAiWorkspace.Desktop.exe)。产物被 Git 忽略，只在本机保存。
- 产品提交 `fc6bac78a58807755f0def7035e5f3ca16e5916e`，分支 `codex/knowledge-version-compare`；包清单绑定该提交。`sourceDirty=true` 对应原有六份未跟踪用户文档，构建时已跟踪文件干净。
- ZIP SHA-256：`A9E62C71D4BE06FD129EABE5E945796D3A8A1BF800B87E6F1199D33AB0BFA0ED`。
- Desktop DLL：`E77D12FC1A8F0570E3D15316C7135F1517EAC1E14023C5B2819A0345E52D016A`；Runtime JAR：`5A4A18BC60DFCD95409E8DC20A792F1EBDBF0FEA5061BA15B460B39EF18A369C`。
- 933 个清单文件通过原包完整性和独立解压核对；真实验收副本中的 933 个产品文件逐一保持相同哈希。[最终收据](../../.verification/workspace-pdf-20261109/final-receipt.json)。

在知识库点击“导入文档”或“导入新源版本”，选择 `.pdf`，核对原生预览后确认。保存为 `.pdf.txt`，包含原文件名、SHA-256、提取格式、文件页数、限制说明和逐页文字。原 PDF 不复制进知识库，应另行保管。取消、解析失败或拒绝时不创建部分摘录。

只提取文字层，不进行 OCR，不保存图片、批注、附件或版式。任一页没有可提取文字（也包括空白页）、表单或加密文件整份拒绝。限制为 8 MiB、200 页、24 万 UTF-16 代码单元（界面简称字符）、最终 UTF-8 摘录 1 MiB、子进程阶段 12 秒。复杂排版、表格、字形映射、数学表达式和多栏阅读顺序须对照原件核对；成功解析不证明内容语义完整。

页标记是文件内页序号，不是印刷页码。既有检索、问答引用、版本和备份继续针对 **TXT 摘录的行／偏移**，没有增加原 PDF 页坐标、PDF 预览或原文件备份合同。新回答仍需模型准入，本轮未验证 PDF 问答质量。

## 方案选择与许可

先核对当前包、路线和工作树，完成真实启动再选择资料入口缺口；不扩张 UI、恢复框架、服务或模型评测。观察时的公开方案如下，stars 仅用于成熟度参考，不是安全保证：

| 方案 | 许可与适配判断 |
| --- | --- |
| [PdfPig](https://github.com/UglyToad/PdfPig) | 约 2.6k stars，Apache-2.0，源自 PDFBox。原生 .NET 字节输入适合现有 Native 文件边界；固定 [0.1.16](https://www.nuget.org/packages/PdfPig/0.1.16)，使用官方推荐的 `ContentOrderTextExtractor`。其 1.0 前 API 有变化风险，因此不使用浮动版本。 |
| [Apache PDFBox](https://github.com/apache/pdfbox) | 约 3.1k stars，Apache-2.0。功能成熟，但本项目若在 JVM 内解析，会扩大原始文件与 Runtime 的边界；本轮不引入第二套解析通路。 |
| [PDF.js](https://github.com/mozilla/pdf.js) | 约 54.1k stars，Apache-2.0。适合浏览器 PDF 阅读，但原文件传入 WebView、worker、CSP 与坐标预览会增加当前只需文字导入的整合范围。 |

实际 NuGet 元数据绑定源提交 `a7bb35662bbbf405efddad50aedc9bcdcf515afc`，.NET 10 选用其 net9.0 资产，无新增传递 NuGet 依赖。保留未修改的包二进制。完整 [LICENSE](../../desktop/src/PersonalAiWorkspace.Desktop/ThirdPartyNotices/PdfPig-LICENSE.txt) 含 Apache-2.0 和 PDFBox／FontBox、Adobe AFM／CMap 附录，[NOTICES](../../desktop/src/PersonalAiWorkspace.Desktop/ThirdPartyNotices/PdfPig-NOTICES.txt) 保留原版权说明；另附[来源记录](../../desktop/src/PersonalAiWorkspace.Desktop/ThirdPartyNotices/PdfPig-PROVENANCE.txt)。三份文件随包逐字节核对。不是对全部上游源码或正式再分发的法律审计。

## 实现与隔离

Native 持有现有受校验的本地只读文件句柄，原 PDF 路径及二进制不交给 React 或 Runtime。父进程将有界字节经 stdin 交给同包 EXE 的终止式 `--pdf-text-worker` 模式，再通过有界 stdout 接收结果。worker 不解析 Profile、不启动 Runtime、不写临时原文、不执行 PDF 动作或打开文档链接。

解析前加入独立 Windows Job：512 MiB 进程提交内存上限、最多一个进程、关闭 Job 即终止；现有 HelperProcess 负责超时、取消和回收。该 Job 是资源限制与生命周期隔离，**不是 AppContainer、降权或网络隔离沙箱**，不宣称可防御任意解析器漏洞。正常 Runtime Job 行为不变，安全设置与 ACL 未调整。

复用 Word 的原生预览与既有 TXT 入库。新增固定错误码贯穿 Native／React，错误不包含文件路径、正文或底层异常。没有新增 Runtime API、schema、持久存储或模型调用；原有版本对比、问答和备份采用既有摘录合同。

## 验证与真实结果

1. 上一阅读包真实无参数 EXE：包内 JVM health `UP`，实际 WebView 已加载且 Runtime 可访问，包内 CoreCLR 10.0.14。使用有来源证明的既有合成 Profile，偏好记录不变。[启动收据](../../.verification/workspace-pdf-20261109/answer-package-startup-2.json)。首轮 UIA 检查误用了简短标签，实际 aria-label 更长；修正断言后通过，原失败保留。
2. PDF／受影响 Word 检查 10/10；中文 ToUnicode、两页顺序、SHA-256、原字节不变、混合有字／无字页与表单拒绝、损坏、201 页和取消。真实 PDF worker 参与检查。首轮 8/10，独立手写合成 PDF 缺少 CID FontDescriptor 导致拒绝；补齐样本后通过，未放宽产品解析设置。[首轮](../../.verification/workspace-pdf-20261109/pdf-word.trx)、[最终](../../.verification/workspace-pdf-20261109/pdf-word-2.trx)。知识库页原有 5 项检查及前端 TypeScript／生产构建通过；保留既有大 JS chunk 提示。
3. 新 PDF 包的真实无参数 EXE 启动通过，包内 JVM／CoreCLR／实际 UI 均通过。[收据](../../.verification/workspace-pdf-20261109/pdf-package-startup.json)。两次实际 EXE 的退出由本轮自有 Job 完成，只证明启动与回收，不冒充托盘正常退出验收。
4. 同包产品文件的完整 AssistantApp／WPF／WebView／Native／HTTP／SQLite 流程，两次独立进程一次通过：原生选择文件；两类不支持样本拒绝且文档不变；预览取消不入库；确认后 READY；限定检索并打开命中原文；重开后全文与哈希一致；移除本轮唯一导入记录。原有三文档及三条问答历史保持。两个应用均经 `ExitAsync` 正常退出，并等到各自 JVM 退出。[首次](../../.verification/workspace-pdf-20261109/run-1/first-result.json)、[重开](../../.verification/workspace-pdf-20261109/run-1/reopen-result.json)、[完成](../../.verification/workspace-pdf-20261109/run-1/completion.json)、[实际界面](../../.verification/workspace-pdf-20261109/run-1/first-source.png)。这是完整应用内的既有测试入口，不是以 mock Bridge 替代产品链路。

测试入口只追加到独立解压副本，未覆盖产品文件。严格发行包检查按设计拒绝追加的测试文件，随后按原 933 文件清单核对产品字节；原 ZIP 和发行目录仍通过严格检查。复用脚本最初保留了 Word 的证据目录标签，已修正证据归集位置，未重跑已经通过的桌面流程。[说明](../../.verification/workspace-pdf-20261109/run-1/evidence-location-correction.json)。

## 保护范围、回滚与剩余边界

全程 0 模型调用；启动前检查没有既有 Java／Ollama／llama-server／Workspace 占用测试端口，空闲内存足够。桌面流程最低记录空闲内存约 12.7 GiB。没有打开或修改 KAG 工程，也没有建立跨项目资源锁；使用顺序和无模型验证避免争用。

六份既有未跟踪用户文档哈希不变，未提交或打包；最近工作区记录不变；原 PostgreSQL 进程集合保持；本轮进程和端口均退出。没有读取真实个人文档、修改个人恢复状态、服务配置、ACL、默认分支或远端。合成样本及证据保留用于复核，导入记录已通过产品入口删除。

本地回滚标签 `workspace-before-pdf-import-20261109` 指向 `7909024598009dde126829791f7bc772e8b9e1b5`；实现提交 `fc6bac7`，文档另行本地提交。`main` 保持 `f5266bf4383f52a2293b0fe4bfe1964af958e64a`。旧包原样保留；不建议通过回退数据库或覆盖 Profile 来回滚应用。

尚未验证：真实复杂 PDF 的通用文字完整性、最大容量／模糊测试／恶意 PDF 对抗、PDF 新源版本与新模型回答的组合、跨机器与不同 Windows 上下文、PG 完整桌面、个人恢复、签名／安装／更新。PDF 不含 OCR。Oracle JDK 21.0.7 与 .NET 10.0.14 Desktop 随包，NativeBootstrap 仍要求本机 .NET 10 x64，WebView2 Evergreen 仍是外部先决条件；本轮没有重新认证这些先决条件或扩大正式发布结论。
