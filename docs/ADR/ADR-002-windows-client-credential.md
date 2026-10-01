# ADR-002 — Windows client credential bootstrap

Date: 2026-10-01

Status: Accepted

## 背景

M0 Runtime 已采用 owner-only 私有 `.runtime/client-token` 与本机 Bearer token 信任域。
M1 增加第一个原生 Windows Client，需要日常无需复制 token，又避免新增浏览器可静默获取 credential 的入口。
本轮 Translate 契约已封版，尚无独立客户端权限需求。

## 决策

采用方案 B，保留 M0 Runtime token 和 HTTP 契约，Java security/task 实现不修改。
首次由用户在 WPF 点击“导入 Runtime 凭据…”并选择本机私有文件；Desktop 不自动搜索 token，
不从 URL、command line、环境变量、网页或共享网络路径读取 token。
拒绝 UNC/network drive、reparse point、非当前用户 owner、允许其他主体读取/写入或改变权限的文件。
打开文件禁止并发 write/delete，校验该 handle 的 ACL，读取最多 128 bytes，验证 M0 的 43 字符 base64url 格式。

用 Windows Credential Manager `CRED_TYPE_GENERIC` 保存 43 bytes，target 为
`PersonalAiWorkspace/Desktop/Runtime/127.0.0.1:8765`，persist 为 `CRED_PERSIST_LOCAL_MACHINE`。
该 persist 指同一机器上当前用户的后续 logon session，不是所有机器用户共享的明文 storage。
读取/写入/释放采用 Win32 CredRead/CredWrite/CredFree；Desktop 无自建 token 文件。
临时 unmanaged credential buffer 用完清零并释放；托管 token 会短暂存在当前用户进程内存。
不承诺隔离已攻陷的同用户进程。

导入后调用认证的 provider readiness 来确认 token；provider/model offline 不妨碍 credential 验证。
missing / malformed / storage unavailable / HTTP 401 有独立状态，要求用户显式重新导入。
“忘记凭据”删除 Desktop 副本，不改变 Runtime token，不撤销其他持有人。
Runtime token 更换后，Desktop 旧副本返回 Unauthorized；不会静默重新读取文件或自行轮换。

没有新增 pairing endpoint，没有 OAuth/CORS 认证替代，没有 cloud identity 或 PKI。
普通网页没有静默读取私有 token 文件或 Windows Credential Manager 的路径。
M0 Origin / cross-site rejection 与 loopback-only 边界继续保持。

## Ownership 与影响

**single trust domain remains**：Windows 与其他 token 持有人共享任务访问权限。
没有 clientId/per-client credential，不能宣称 task ownership isolation。
后续如引入 per-client credential，必须同步定义任务查询/取消 ownership 与凭据撤销策略；不属于 M1。
本方案满足首次显式本机动作、OS 安全存储和日常无需复制 token，同时保持 M0 完全兼容。
不新增 Runtime capability，不涉及 Browser migration 或 Finance。

## 参考

- [Windows CREDENTIALW: generic credential and persistence](https://learn.microsoft.com/en-us/windows/win32/api/wincred/ns-wincred-credentialw)
- [CredWriteW](https://learn.microsoft.com/en-us/windows/win32/api/wincred/nf-wincred-credwritew)
- [UI Automation threading](https://learn.microsoft.com/en-us/windows/win32/winauto/uiauto-threading)
