# M5C CLOSING REPORT

2026-10-04 (Asia/Shanghai). Local implementation and acceptance candidate; Architecture / Closing Review pending.
仅记录合成场景的检查、计数与性能；不保留正文、输入法测试词、provider captures、bearer或backup bytes。

## 1. Result

**M5C — IMPLEMENTED / LOCAL ACCEPTANCE PASS。M5C CLOSING CANDIDATE — GO。M5 — OPEN。**
Durable truth、CRUD/lifecycle、bounded history、send/Memory/cancel、reload/reopen/restart recovery、strict bridge/privacy、real Windows gates均PASS。
最终Java105 / Desktop233 / Frontend66，0failure/error/skip。M5A/M5B已批准CLOSED — GO；M5D/M5E未开始。

## 2. Reality Gate

PASS。从clean `main`开工，HEAD/main/origin/main均为`7b6dbdfeece1d4ab8179ec6cd5ce7f730e6aee14`。
按指定command-local127.0.0.1:7890代理成功fetch；未写global Git proxy。remote只有main。
重新阅读README、STATUS/current architecture、ADR001..010、M4A/B/C与M5A/B Closing Reports，并检查实际Core/Runtime/native/bridge/frontend/acceptance/scanner。
现有合同足够，无material architecture conflict。

## 3. Git

Branch：`m5c-conversations-migration`，直接从指定baseline创建。
Implementation：`084171306874f36166077b44dcd0143502ed66c5`，`feat: migrate conversations to main workspace`。
本报告及三个current docs使用独立`docs: record M5C conversations closing candidate` commit。没有merge/push/tag/release或删除feature branch。

## 4. Baseline

| Suite | Published M5B | M5C final | Added |
| --- | ---: | ---: | ---: |
| Java | 105 | 105 PASS | 0 |
| Desktop | 197 | 233 PASS | 36 |
| Frontend | 33 | 66 PASS | 33 |

完整Java clean verify、Desktop suite、Frontend suite均实际重跑。Java/Core production与Browser Extension文件未改变。

## 5. Files Changed

20个implementation files，另4个documentation files。

| Group | Files |
| --- | --- |
| Frontend | App.tsx; contracts.ts; client.ts; conversations.ts/test; ConversationsPage.tsx/test; WorkspacePage.tsx; workspace.css; conversationFixtures.ts |
| Desktop production | AssistantApp.cs; AssemblyInfo.cs; Bridge/WorkspaceBridge.cs; Bridge/WorkspaceConversations.cs |
| Desktop tests | WorkspaceConversationsTests.cs |
| Real acceptance | ConversationsWorkspaceAcceptance.csproj; Program.cs |
| Scripts | conversations-workspace-smoke.py; privacy-audit.py; desktop-memory-ask-smoke.py |
| Docs | README.md; docs/STATUS.md; docs/architecture/current-architecture.md; this report |

Generated assets/bin/obj/evidence/DB/credentials均ignored且不进入commit。

## 6. Architecture Boundaries

React负责presentation/current bounded pages/draft/focus；WPF负责bridge/session/picker/credentials/lifetime；Runtime负责durable history/context/TaskManager/provider/SQLite。
Conversation与stateless Assistant独立。WorkspaceOperations保留原6方法及所有权，不承担Conversation truth或task recovery。

## 7. Runtime Contract Reuse

直接复用既有Create/Rename/List/Get/Archive/Unarchive/Delete/SubmitConversationTurn/CancelConversationTask APIs。
复用Core Conversation/Detail/Turn/Page、AskInput/MemoryAskInput/MemoryReference验证与原生MemorySelectionWindow。
Java production、Core production、DB migrations、Runtime limits及backup formats无修改。

## 8. Bridge Expansion

只新增11个explicit方法：list/get/create/rename/archive/unarchive/delete/selectMemories/clearMemories/send/cancelPending，均以`conversations.`为前缀。
各payload exact，固定limit10；无generic CRUD/HTTP/SQL/task-by-ID proxy。version1、32KiB request、8pending、4096consumed request IDs与origin/document/session checks保留。

## 9. Session Authorization

只有当前session的真实list/create返回ID进入授权集合，最多1000；未知UUID不能读写。delete撤销ID与pending/selection；reload重建全部授权。
取消绑定仅由真实validated get获得conversation+turn→native task+page，取消前重新读取并比对。覆盖unlisted IDs、wrong session/origin、forged turn和changed task。

## 10. Conversation DTO Projection

List仅id/title/status/createdAt/updatedAt。Detail为conversation/turns/totalTurns/page/limit；Turn含turnId、sequence/status/time、USER/nullable ASSISTANT、controlled failure、历史decimal refs与canCancel。
无raw taskId、Memory body、provider payload、prompt/context/system prompt。TypeScript exact字段、canonical IDs、UTC/calendar、pages/count/order/roles/invariants及byte bounds严格检查。

## 11. Bridge Response Budget

普通response保持65536bytes；仅`conversations.get`上限1048576bytes。
最坏消息内容：20×8192UTF-8bytes×6JSONescaping=983040；title最多1920；DTO及envelope保守≤40960，总计≤1025920，低于1MiB。
最大合法native fixture含10双消息Turn、8192控制字符正文、160字符title、40历史refs/Int64max；Core parse→projection→serialization→budget通过。
超预算返回受控InvalidResponse，不截断/drop。真实WebMessage再JSON序列化测得988644bytes，TS和React全量render通过。

## 12. Conversation List

只展示当前10项metadata，selected状态可见；初次ACTIVE page0，必要时选择首个真实Conversation以恢复pending。
New、tabs、paged controls与native fallback均提供；不会默认枚举所有页。

## 13. ACTIVE / ARCHIVED

两种status exact enum，独立in-memory页码；切tab读取对应页并清除选择/draft/Memory。
ARCHIVED可读history，编辑器readOnly，Send/Use Memory禁用。归档后保留当前detail以继续观察已接受pending。

## 14. List Pagination

固定limit10，page0..99、total≤1000，Previous/Next按页获取。
多页真实fixture含25个预建Conversation；初始10项、Next/Previous通过。Frontend接近上限fixture证明bounded page读取，无全页loop。

## 15. Create

empty payload→existing CreateConversationAsync(null)，Runtime默认`New conversation`。
Host授权真实returned ID，React选择空history并refresh list，焦点回editor；无auto-title或LLM调用。

## 16. Rename

manual rename，通过existing Core160 Unicode code-point final validation；160 astral符号通过，161拒绝。
React当前title展示与Runtime真实metadata一致；历史与context不改写，rename后焦点回editor。

## 17. Archive

ACTIVE→ARCHIVED阻止new turn、清除Memory授权，无自动cancel。
真实held-provider fixture在PENDING时Archive后仍PENDING，随后release并观察到SUCCEEDED；no cancel/no resend。

## 18. Unarchive

existing API将同一ID恢复ACTIVE，history保留，之后真实Send成功。
不创建替代Conversation，ARCHIVED tab能找到已归档title。

## 19. Delete

键盘accessible HTML dialog显示真实title及无法从当前Workspace恢复说明，默认聚焦Cancel；Escape取消，独立destructive确认。
Runtime live/PENDING409处理为ConversationConflict，无cancel-then-delete、retry或force。
成功删除撤销host ID/pending/selection并清空UI，焦点New；详细real gate见39。

## 20. History Pagination

打开先page0，按total算latest，必要时仅再取最后页；Older/Newer读取当前页。
React不永久积累历史，仅一页加pending identity；观察latest时可保留当前older visible page而不附加数据。
1000-turn真实fixture默认显示991..1000，Older到981..990、Newer回最新。

## 21. Turn Rendering

PENDING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT全部有测试，plain USER/ASSISTANT role labels。
仅SUCCEEDED渲染真实ASSISTANT；失败/取消/timeout不制造assistant文本。七个failureCode映射safe text，无SQL/stack/provider body。

## 22. Historical Memory References

仅显示Historical Memory×N，投影保存的id/revision/position。无需查询当前Memory或重建旧正文/title。
真实带Memory的Turn在原Memory删除后仍可load。历史dangling refs及Int64 decimal边界有契约测试。

## 23. Editor

Production controlled multiline textarea保留exact输入，无trim/maxLength截断；现有AskInput3000UTF-16与5632UTF-8仍final。
Enter换行；Ctrl+Enter同时检查React composition/native isComposing/keyCode229；Send按钮为显式路径。
PENDING阻止Send与Use Memory；3001字符、多字节超额与未配对surrogate均拒绝。

## 24. Real Pinyin

**PASS：真实Windows Microsoft Pinyin→Conversation textarea→候选上屏/commit→conversations.send→exact durable USER→exact provider current USER。**
Physical keys与真实composition start/update/end被观察；候选Enter没有提交，完成后仅一次admission，真实Ollama SUCCEEDED。
不以CDP insertText、clipboard或synthetic composition代替此gate；phrase只在测试内存中，未保留。

## 25. Per-turn Memory

Use Memory调用native MemorySelectionWindow，完整preview与explicit selection；React只得metadata，max4、exact ordered decimal revision。
Host authority绑定session+conversation，禁止任意/重复/改revision/order/overflow/cross-conversation refs。
Clear为session授权变更，不修改Runtime Memory。

## 26. Memory Lifecycle

switch清旧/新host scope并清UI；archive/clear/reload/delete失效。picker Cancel保留原选择。
accepted立即consume，即使之后失败/取消/timeout。POST不安全错误也consume，只有确定pre-admission stale保留Needs review。
preflight await后重新检查epoch/owner，in-flight clear不能继续POST撤销的refs；stale不得自动升级revision。

## 27. Send Admission

payload仅authorized conversationId、exact message和selected refs。Host验证input、scope与ACTIVE，再调用现有SubmitConversationTurnAsync。
每conversation在host同session限制并发提交；Runtime仍负责one-pending conflict。无optimistic fake message、无provider直接访问。

## 28. Persist-Before-Execute

现有Runtime流程不改：durable USER+PENDING→TaskManager/provider→terminal persistence。
held真实provider时React已从Runtime读取到USER/PENDING，可检查delete conflict、reload、archive与restart。History不来自JS拼接。

## 29. PENDING Handling

serial detail queue防overlap，completion后550ms再poll；terminal/error/hidden停止。visible older history与latest pending identity分别管理。
明确Cancel发送authorized turnId；无需M5B operationId。Route switch不cancel或resend，重新可见时读取durable最新状态。

## 30. Cancel

真实get绑定task留native，取消前re-read原页确认turn仍PENDING且task一致，再existingCancelConversationTaskAsync。
真实held-provider gate产生CANCELLED、USER保留、ASSISTANT不存在。Unit覆盖forged turn/changed task与所有terminal race，Runtime wins。
cancel requested不保证GPU立即停止，UI只按之后durable状态展示。

## 31. Unknown Outcome

POST transport/timeout/invalid reply及unexpected posting exception→OutcomeUnknown，host消耗Memory；TS丢失reply/reload同样处理，无automatic retry。
React保留draft、安全warning、清selection并refresh Runtime；若发现新Turn以durable truth为准。测试断言仅一次submit、old-session reply被忽略。

## 32. Durable Failure after Submit Error

QueueFull/ConversationStorageUnavailable/InternalError乃至post-persistence policy rejection可能已生成FAILED USER。
React所有提交结局都重新读取detail，不把error当作零持久化；除明确stale外host保守清POST selection，UI无重发。
Desktop/Frontend定向测试证明controlled codes、preserved draft、consumed authority和refresh。Java原流程不改。

## 33. WebView Reload Recovery

真实PENDING时reload，session ID变化；ACTIVE list/detail重新授权，真实pending与canCancel恢复；release后SUCCEEDED显示。
admission/provider capture断言无resubmit/replay。Unit证明旧session取消绑定失效，仅新真实detail能重建；old reply被抑制。

## 34. Window Reopen Recovery

PENDING时关闭Main Workspace而保留Runtime，profile cleanup PASS；重开从list/detail找到同一pending，release后terminal可见。
没有自动cancel、resend或USER丢失，不使用旧JS registry。renderer crash fallback与再开也通过。

## 35. Runtime Restart Recovery

真实durable PENDING被确认后终止仅test-owned JVM并重启同一isolated data。
Startup将Turn置FAILED/EXECUTION_INTERRUPTED，React重开准确显示；provider invocation count启动时不增加，旧请求不重放。

## 36. Real Multi-turn Ollama

Turn1建立fresh synthetic code；Turn2不重复code，真实provider使用Runtime assembled context正确回答。
捕获actual provider current USER与durable admission exact匹配，React不提交history。恢复后再次不重复code询问并正确回答。
中间曾因模型echo/过长合成marker而失败；夹具改为短unique code，最终仍严格要求Turn2与恢复回答包含exact code，无production重试。

## 37. Real Explicit Memory

ordinary Turn memoryCount0；native picker选择ACTIVE revision1 Memory后下一Turn memoryCount1且exactref；真实provider上下文包含marker、实际回答使用它。
接受后selection清除；再下一Turn未重新选择，memoryCount0。真实Memory改revision触发stale，需要明确Clear/Review。
all admissions（除显式Memory Turn）为zero Memory，无auto inheritance/retrieval/save。

## 38. Archive/Unarchive Acceptance

真实React ACTIVE→Archive→ARCHIVED list，Send/editor被禁止；Unarchive同一ID，real new Turn成功。
另有PENDING Archive→ARCHIVED仍pending→provider release→SUCCEEDED的完整真实观察。以上非only unit。

## 39. Delete Acceptance

无pending disposable Conversation通过default-Cancel modal的独立确认delete，Core Get返回ConversationNotFound；React selected editor消失、无ghost缓存。
PENDING真实bridge request越过UIdisabled检查后仍得到controlled409 ConversationConflict，执行未被取消。

## 40. Failure Acceptance

test-only relay一次provider503产生真实durable FAILED/PROVIDER_UNAVAILABLE；USER仍在，无ASSISTANT。
Runtime startup interruption产生第二种real FAILED/EXECUTION_INTERRUPTED。UI仅受控failure code/text，无raw error body。

## 41. Large Legal Content

Native worst DTO fixture验证Core parse/projection/serializer/budget，包括160-char escaping title、40refs及max decimal。
真实Runtime1000-turn fixture最后10Turns，各USER/ASSISTANT8192个escaping-heavy合法控制字符；WebMessage988644bytes，TS严验、React20段正文全部8192长度，无截断。
另Frontend JSON round-trip同最大内容fixture，普通response cap仍64KiB。

## 42. Capacity / Paging

真实25项预建list和1000-turn history；Frontend upper-bound list/history证明initial list page0，detail仅0/latest99，无100page preload。
当前list/history各至多10项，host authorization≤1000、native pending仅IDs/bindings；无transcript cache或无限scroll积累。

## 43. React Persistence

Conversation/domain/draft/selection仅React memory。localStorage仅workspace.theme；sessionStorage为空，IndexedDB/service worker不存在。
URL只#/conversations，不含identity/title/message。reload从Runtime重建，switch丢弃draft。Delete清UI与host authorization。

## 44. Rendering Security

USER/ASSISTANT/title皆React plain text/bdi/pre，无Markdown/HTML/syntax highlight。
hostile script/img文本保持字面，未生成script/image节点。既有CSP/navigation/frame/popup/download/permission/Release DevTools安全回归PASS。

## 45. UDF Privacy

最终真实M5C gate关闭后扫描374个UDF files，均可读，fresh title/USER/ASSISTANT/failed USER/Memory/Pinyin/history/list markers及actual bearer：0matches。
覆盖list/detail/page/send/result/archive/reload/reopen/restart/renderer crash；AllProfile cleanup PASS，profile InPrivate/autofill/password disabled保持。
实际长答案也扫描UTF-8/UTF-16/escaped JSON；不承诺forensic erase。

## 46. Accessibility

semantic list buttons/selected state、roving ACTIVE/ARCHIVED tabs及arrow keys、headings/role/terminal labels、page controls与editor label。
PENDING live region只状态变化，不逐poll播报。Create/rename/delete/native picker返回焦点已处理；Delete defaultCancel与Escape。
真实Pinyin、visible keyboard focus、实际DPI100%/125%zoom/no horizontal overflow通过。Screen reader与mixed-DPI hardware未实测。

## 47. Frontend Tests

**66 PASS**：原33+新增33（Conversation contracts12、page21）。完整`npm --prefix desktop/frontend test`与tsc/build通过。
覆盖list/status/paging/CRUD/selection/latest/history/all5states、no fabricated assistant、exact refs/stale/switch/consume、unknown/error/no replay、IME/input bytes、non-overlap polls、strict DTO、hostile与max-content render。
native Pinyin并非由unit synthetic composition代替。

## 48. Desktop / Bridge Tests

**233 PASS**：原197+新增36。完整solution suite运行，0fail/error/skip。
包括11allowlist、origin/session/exact schemas/UUID/IDs/turn binding、list/create authorization与delete revoke、reload/admission、cancel races、archive不cancel、Memory scope/revision/epoch、unsafe rejection与max budget。
既有UIA hidden-fixture偶发COM transient在中间运行出现，隔离及最后完整suite通过，UIA生产代码与旧测试未改；真实hotkey/UIA gate也PASS。

## 49. Java Regression

`.\mvnw.cmd clean verify` **105 PASS**，0fail/error/skip，packaged Runtime jar重建。
Java production/test没有改动；SQLite Workspace v3、Runtime API/limits、profile/context、persist-before-execute与backup/restore格式均复用现状。

## 50. M5A/M5B Regression

`main-workspace-smoke.py` PASS：real Release shell/tray/5routes/native entries、hotkey/UIA/helper/real Ollama、clipboard unchanged、125%focus、CSP/external/frame/popup/download/permissions、stale response、reload、failure fallback、privacy。
其历史shell-only IME字段仍partial，按照已批准M5A gate分类不计作新Pinyin证据；新M5C与旧M5B production editor各自真实Pinyin均PASS。
`assistant-translate-smoke.py` PASS：React Ask/Summarize/explicit Memory Ask/Translate/cancel/copy、zero Memory next ask、真实Assistant Pinyin、native quick Ask/Summarize、reload/reopen/crash及no Conversation persistence。
`main-workspace-build-check.py`7项PASS：Release拒绝dev/缺资源/错误manifest，Debug显式dev可build，默认npm ci/build/publish、Release无dev origin、assets完整。

## 51. Legacy ConversationWindow

ConversationWindow与native.openConversations保留。真实M5C gate打开原生窗口、读取durable detail；独立Workspace recovery fixture通过原生窗口真实继续context与explicit Memory。
修复的是acceptance driver的同步modal调度，使用Dispatcher+await关闭；无legacy production移除或行为改变。

## 52. Workspace Backup / Restore Regression

M5C真实export→original-unavailable→empty maintenance restore→restored Runtime→React list/detail→real contextual continuation PASS。
全部logical source字段相等，task不portable、startup0replay。原source由task-owned临时根下rename使其不可用，未触碰用户Workspace。
独立`workspace-backup-smoke.py`全部40项PASS：WPF export/preview/restore/recover、source removal、digest/source equality、corrupt/nonempty-target拒绝、old task不重建。
`desktop-memory-backup-smoke.py`71项PASS，Memory-only restore后四张Conversation表无history；格式1/schema1保持。
`desktop-memory-smoke.py`CRUD/FTS/stale/archive/delete/privacy PASS；`desktop-memory-ask-smoke.py`真实stateless/explicit Memory/Browser拒绝PASS。
旧M3C1脚本仅校准obsolete“无Conversation表”断言为现有v3四表记录数均0，无Runtime/domain改动。

## 53. Browser Regression

`browser-security-smoke.ps1 -Batch` real PASS：native/paired-browser Single/Batch translate、3items/1real Ollama chat、origin/cross-owner/readiness/poll/cancel/revoke/restart/no-CORS。
Browser仍Translate-only，Conversation/Memory personal APIs不开放。使用独立task-owned data与auth，拒绝已有port。
这是synthetic HTTP客户端+真实Ollama协议回归，不宣称本轮real Chrome GUI acceptance。Extension文件/permissions未改。

## 54. Privacy / Security Audit

`privacy-audit.py`新增stdin-only `conversationMarkers`；对source/build/ignored assets/log/evidence/archive bytes搜索fresh values，不输出/persist needles。
Live M5C audit：259 source files、2172 files、34929 byte/archive checks、128 archives、12Conversation markers、actual临时bearer，matches0；无tracked build/backup artifacts。
production frontend静态检查无fetch/XHR/Authorization/domain storage/console/HTML/clipboard；theme例外保持。bridge无content diagnostics。
Post-doc audit PASS：260source files、2174files、34931byte/archive checks、128archives、6个现存native test credentials，0matches；该复核不重新使用已清理的M5C临时marker。
Runtime/provider relay/test control仅位于acceptance脚本；production无test endpoint。credential/tray/userdata与既有listener未被修改/终止。

## 55. Performance

真实最终Release/WebView2154.0.4258.53，DPI100%，下列是单次合成观察而非SLA。CDP/UI wait时间包含轮询采样与bridge成本；working set不是全WebView进程合计。

| Observation | Final value |
| --- | ---: |
| Conversations route ready | 16.21 ms |
| Initial ACTIVE list ready | 187.89 ms |
| Latest selected page ready (last ordinary selection) | 139.52 ms |
| 1000-turn latest 10-turn page open/render, including bounded fetches | 196.78 ms |
| List Next page ready | 62.74 ms |
| Older page / Newer page | 156.47 / 171.86 ms |
| Runtime POST round trip median / range,14 samples incl rejection | 23.72 / 6.81–62.33 ms |
| Runtime detail while provider held median / range,19 samples | 15.84 / 3.15–27.3 ms |
| Production JS | 261353 bytes (+19152 vs M5B242201) |
| Production CSS | 7988 bytes (+1687 vs M5B6301) |
| Desktop working set | 202.14 MiB |
| WebView browser process working set | 151.72 MiB |

Turn2真实context completion775.29ms；显式Memory778.45ms；恢复后context continuation773.71ms。
detail response实测988644bytes，无Runtime domain缩限、silent truncate或全history fetch。

## 56. Known Limitations

仅local candidate，尚未Closing Review/发布。非streaming、仅linear immutable history；Runtime context仍受既有bounded历史窗口约束，1000-turn保留不代表模型一次读取全部。
Draft在switch/reload丢弃；session授权达1000时evict旧ID，需要list重新授权。旧shell4096request budget达到时需显式reload，不自动重放。
模型答案是概率性，中间long-marker/echo断言有失败；最终短fresh marker严格context与Memory gates全部PASS，无生产重试。UIA transient已如48披露。
Chrome GUI、screen reader、mixed-DPI、GPU立即cancel与forensic erase未声称验收。
初次Browser数据目录误放repo下被现有private-location guard拒绝，改为独立TEMP后PASS。
一次含递归清理的PowerShell调用被automatic approval review拒绝（仅返回blocked by policy）；回归改为不执行该清理的task-ownedTEMP。
这些Browser/native smoke专用空domain DB及ignored auth/evidence留本机，不包含用户Conversation；M5C主fixture、WinCred target/provider captures自动清理，UDF marker扫描PASS。

## 57. Deferred to M5D/M5E

M5D Memory CRUD/search management/full Settings React migration；M5E retirement/installer/updater/Java bundling/supervisor/packaging/final acceptance均未开始。
edit/regenerate/branching/fork/streaming/auto-title/automatic Memory/RAG/Knowledge/Finance/Agent/tools/Browser Conversation/Markdown/attachments/search/folders/tags/pins/favorites继续deferred。

## 58. Architecture Compliance

ADR001..010仍Accepted，无新增ADR，无Java/API/schema/backup domain扩张或Browser permission widening。
Runtime durable truth、WPF privileged ownership、trusted local content、exact allowlist/bounds、no credentials in JS、explicit per-turn Memory与logical portability均保持。
M5B transient operations未被用作Conversation truth，无第二transcript database。全部hardGOgatesPASS。

## 59. Git Status

Implementation与closing docs独立commit；本地交付完成后tracked working tree clean。
最后command-local proxy fetch复核main/origin/main仍指定baseline；feature branch保留，remote branch仍main only。
无merge/push/tag/release/feature deletion，historical M5A/M5B Closing Reports及ADR文件未改。提交后的核验以最终Git输出为准。

## 60. Recommended Next Step

提交本candidate给Architecture / Closing Review。只有另行批准后才进行正式delivery。
**M5C — IMPLEMENTED / LOCAL ACCEPTANCE PASS；M5C CLOSING CANDIDATE — GO；M5 — OPEN。**
本轮停止于M5C，M5D/M5E仍NOT STARTED。
