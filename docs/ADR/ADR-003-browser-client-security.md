# ADR-003 — Browser client security and task ownership

Date: 2026-10-02 (Asia/Shanghai)

Status: Accepted

## 背景

M0/M1/M1.5 的所有 native token 持有人共用一个信任域。Browser Extension 引入新的边界：
不能分发 Runtime bootstrap token，也不能凭 taskId 访问其他客户端的输入结果或取消任务。
ADR-001/002 中的 single trust domain 描述保留为当时事实，本 ADR 演进当前 Runtime 安全模型。

## 已采用决策

- 本机 trusted authority 继续使用现有私有 Runtime token；Windows Credential Manager 不变，无需重新导入。
  所有 native token 持有人映射为 `native-local`，仅共享 native 任务；没有逐个 native 应用隔离。
- 用户通过 trusted action 显式批准一个精确扩展 Origin，随后调用认证的 `POST /api/v1/security/pairings`，
  JSON 包含 `origin`、1–64 字符 ASCII `displayName`、`userApproved: true`。Boolean 是调用方批准契约，
  不能作为服务器证明物理点击的手段；安全权威是已有本机认证加一次性 proof。M2A 不新增 Desktop pairing UI。
- Origin 仅接受 `chrome-extension://` 加 32 个小写 a–p 字符，不接受尾斜线、网页 Origin、localhost Origin 或 wildcard。
  Pairing ID 为随机 UUID，secret 为独立 256-bit base64url 值。内存只保存 secret 的 SHA-256，3 分钟 TTL，单次 exchange。
  Session 不写磁盘，restart 使未完成 pairing 全部失效。成功 proof 在持久化前消耗；失败写入不会发凭据，也不允许重放。
- `POST /api/v1/security/pairings/exchange` 不接收 master token，只接收 pairingId/secret 和匹配 Origin。
  同时检查 loopback、活动 session 和 Fetch Metadata。Webpage、未知扩展、导航、cross-site 和缺失 metadata 都拒绝。
  Browser HTTP contract 要求 `Sec-Fetch-Site: none`、`Sec-Fetch-Mode: cors`、`Sec-Fetch-Dest: empty`。
  不根据 Origin 单独认证；普通网页拿到 Origin 字符串也不能取得 proof 或客户端凭据。
- Exchange 生成服务端随机稳定 clientId，类型 `browser-extension`，独立 256-bit secret。
  Credential 格式 `br1.<UUID>.<base64url-secret>`；SHA-256 verifier 使用 constant-time comparison。
  没有 HMAC key、加密算法、OAuth/OIDC/JWT、账户系统或 refresh-token infrastructure。
  Credential 只在成功 exchange JSON 的 `credential` 字段返回一次，不放 Header、Location、task metadata 或日志。
- Browser v1 权限固定 `{translate}`；只允许 POST Translate 与 GET/DELETE 自己的任务。
  Ask、Summarize、provider readiness、client listing、pairing create、revoke 均不授权 browser。
  所有任务在共享 TaskManager 内绑定 ownerClientId；跨 owner GET/DELETE 与不存在任务返回完全相同的 404 TASK_NOT_FOUND。
  Native authority 也不能直接 GET/DELETE browser 任务；需要控制客户端时撤销其 credential。
- CORS 只对活动 pairing 的 exchange 或已注册扩展的允许路由处理精确 Origin preflight。
  Preflight 不签发 credential、不授权执行；实际请求仍必须带 pairing proof 或正确 credential。
  仅允许 Authorization/Content-Type 请求头，POST 或 GET/DELETE 方法；不设 `*`、cookies 或 allow-credentials。
  注册 origin + 已认证 client 的受控响应允许被扩展读取；普通网页/unknown origin 不返回 allow-origin。
- `GET /api/v1/security/clients` 仅供 native authority，返回安全 metadata。
  `DELETE /api/v1/security/clients/{clientId}` 原子删除 verifier 和注册，是幂等 revoke/server forget。
  Future extension 的 forget 必须删除自己保存的 credential；只删除本地副本不等于 server revoke。
  Revocation 拒绝后续请求，不自动取消先前接受的工作；其结果随既有短期 retention 过期。

## 持久化与有界性

`browser-clients.json` 位于已配置 token 文件的专用私有目录，只保存 version、client identity、Origin、capability 和 verifier。
不保存 conversation、prompt、task/result 或明文 secret，不引入 Memory DB/SQLite。
注册最多 32 个，活动 pairing 最多 8 个，每 session 最多 5 次错误 proof，exchange 总共最多每分钟 60 次。
Pairing admission 预留注册容量；撤销释放容量，无无限 revoked tombstones。Registry 最大 64 KiB，拒绝未知/重复 JSON 字段、
未知版本、重复 clientId、损坏 identity/capability/verifier。Corruption fail closed 为拒绝启动，不静默清空重新配对。

沿用 owner-only POSIX 0700/0600 或 Windows owner-only ACL。拒绝 symbolic link/other directory components；
权限或原子操作不可用时拒绝。`browser-clients.lock` 用 OS exclusive lock 阻止共享 registry 的并行 Runtime 写入。
写入 private pending file、完整写入、force file、ATOMIC_MOVE replace；成功后才更新内存。
启动只信任已提交文件：存在 committed + stale pending 时删除 pending，绝不回滚撤销；仅 pending 存在则拒绝启动。
不承诺任意文件系统/硬件断电下的 directory fsync durability；当前验证覆盖进程重启与写入失败。

## Native compatibility 与边界

Native 仍接受无 Origin、非 cross-site 的原格式 token；API DTO/路径、Windows credential target、hotkey、selection、tray 和 cancel 不改。
Translate/Summarize/Ask 的 prompt、profile、LOCAL_ONLY、provider settings、并发与 deadline 保持既有值。
Native shared domain 和同 OS 用户进程仍是信任假设；Origin/Fetch Metadata 不能防御持有凭据的恶意同用户 native 进程伪造 HTTP。
服务仅 loopback，无 remote/cloud access。

M2A 用受控 HTTP client 模拟 extension 验证 Runtime foundation。真实 Chrome headers、host permission、service worker 与 storage 集成
尚未验收，不宣称 Chrome Extension PASS 或 Browser Convergence complete。Future M2B 应将 credential 保存在 extension-owned storage，
限制 content script 访问（例如 storage access level TRUSTED_CONTEXTS），不得传到 webpage/DOM/message bridge/log，
不能复制 master token。Chrome 的 storage.local 并非 OS credential vault，不隔离同用户已攻陷进程。

## Amendment — M2B-2B-R1 current behavior (2026-10-03)

以上保留 M2A 历史决策与当时尚未真实 Chrome 验收的事实。M2B-2A 后增加 Translate-only readiness。
随后未修改的 M2B-2B candidate `ddfa4a0` 在真实 Chrome/154.0.8037.59 成功 exchange，却以 Origin-absent GET readiness 被拒绝 401。
自然 headers 为 none/cors/empty 和独立 Browser credential；显式 mode=cors 仍没有 Origin。
根因是 Runtime 将所有无 Origin 请求归为 native，而不是 credential 错误、revoke 或 CORS fallback。
本 amendment 基于该真实证据演进 admission，不删除前述 pairing/security/threat-model 历史上下文。

- Native 原格式 token + Origin absent 仍使用原 native policy/owner，保留 Translate/Summarize/Ask/pairing/list/revoke。
  Browser `br1` 只用于选择 credential verifier，绝不授予 native 权限；必须真正 lookup clientId 并以 constant-time 比较存储的 SHA-256 verifier。
  注册删除即 revoke；没有无认证 GET 或 prefix-only trust。
- Pairing exchange 继续 exact approved Origin + none/cors/empty + one-time proof，拒绝 Authorization。
  Browser Origin-present 请求继续 valid chrome-extension Origin、exact registered client.origin、none/cors/empty、route allowlist、capability authorization。
  wrong/unknown extension、普通 https webpage、缺失 metadata 都拒绝。
- Real Chrome privileged GET 可能没有 Origin。兼容仅限 authenticated、未 revoke、Translate-authorized Browser 的
  GET `/api/v1/capabilities/translate/readiness` 和 GET `/api/v1/tasks/{uuid}`，必须完整精确 none/cors/empty Fetch Metadata。
  未明确列入的 GET、Ask/Summarize/admin、HEAD/OPTIONS 以及所有无 Origin POST/DELETE 均拒绝；mutating Browser requests 仍要求 exact Origin。
- Browser identity 始终为 credential 对应的已注册 client。既有 TaskManager ownerClientId 检查保持，
  Browser A→B/native 及 native→Browser task 都返回与不存在相同的 404 TASK_NOT_FOUND。
- **不存在的 Origin 无法验证。** Browser credential 是最终认证的高熵 bearer secret；其身份绑定来自此前用户明确批准的 pairing。
  有 Origin 时提供 additional origin binding；无 Origin GET 的组合为 explicit approved pairing、registered identity、bearer secret、
  Fetch Metadata、exact GET allowlist、capability authorization、per-client task ownership。
  不宣称 Originless GET 仍验证 exact Origin，不能把 Fetch Metadata 当作另一份 bearer 身份证明。
- 不合成 Origin、不引入 X-Extension-Origin/X-Client-Origin/X-Browser-Origin，不让 Extension/webRequest 修改 security headers。
  无 Origin response 不设置 Access-Control-Allow-Origin；没有 wildcard CORS，现有 exact-Origin OPTIONS policy 保持。
  Chrome host_permission 自身控制 privileged fetch 的 response readability；本轮真实 Chrome 已成功读取 readiness 与 structured task result。
- 普通网页仍拒绝。Origin/metadata 是浏览器边界，持有 bearer 的同 OS 用户恶意 native process 能伪造 HTTP headers，
  仍在之前已说明的 Browser-origin isolation 保证之外；没有扩展为同 OS 用户进程隔离保证。

Runtime patch **CLOSED — GO**，限定真实 Chrome security chain 的证据见
[M2B-2B-R1 Closing Report](../milestones/M2B-2B-R1-CHROME-GET-SECURITY-REPORT.md)。
完整 Extension MDN/Dynamic/Selection/cache/offline/revoke/MV3 acceptance 仍待 M2B-2B，不能由该 amendment 推断 M2 CLOSED。

## References

- [Chrome cross-origin requests and extension host permissions](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests)
- [Fetch Metadata specification, extension requests](https://www.w3.org/TR/fetch-metadata/)
- [Chrome storage API and access levels](https://developer.chrome.com/docs/extensions/reference/api/storage)
