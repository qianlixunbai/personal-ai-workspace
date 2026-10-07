# W1A-1 Runtime Secure Fetch implementation candidate

Date: 2026-10-08 (Asia/Shanghai). Source review required; this is not W1 closing approval.

Baseline: clean `main / origin/main`, `2a0c4c322ea18c8c2dac478c80f929137c45942b`,
verified after command-local proxy fetch. Feature branch: `codex/w1a1-secure-fetch`.
The candidate and remote feature SHAs are recorded in the delivery handoff.
Authority: [ADR-014](../ADR/ADR-014-controlled-web-access.md) and the explicit W1A-1
implementation request. ADR-014, current status, roadmap and architecture approval records are unchanged.
W1A-2 approval/bridge integration remains NOT AUTHORIZED.

## Production ownership and dependencies

Added under `src/main/java/io/github/qianlixunbai/workspace/web/`:

- `WebPolicy.java`, `WebLimits.java`, `WebTarget.java`
- `PublicAddressPolicy.java`, `ValidatedAddressSet.java`, `WebResolver.java`
- `WebExecution.java`, `PublicWebTransport.java`, `WebHeaderParser.java`
- `WebContentExtractor.java`, `WebFetchResult.java`, `WebFetchService.java`, `WebConfiguration.java`

Other production changes:

- `src/main/java/io/github/qianlixunbai/workspace/api/WebFetchController.java`: native-only POST/GET/DELETE routes.
- `src/main/java/io/github/qianlixunbai/workspace/api/ApiExceptionHandler.java`: controlled Web status mapping.
- `src/main/java/io/github/qianlixunbai/workspace/common/ErrorCode.java` and `ApiError.java`: the twelve specified Web errors, fixed safe messages.
- `src/main/resources/application.yml`: default Web policy and disabled Apache diagnostics.
- `pom.xml`: exactly two added direct production dependencies. Boot 4.1.1 resolves
  `org.apache.httpcomponents.client5:httpclient5` to **5.6.4** without an explicit version override;
  `org.jsoup:jsoup` is explicitly **1.23.2**. Effective dependency tree and resolution both succeeded.
  HttpClient's transitive HttpCore is **5.4.3**. No dnsjava or optional networking extension was added.

## Target, resolution and transport

`WebTarget` accepts absolute hierarchical ASCII HTTPS URLs with LDH DNS labels,
two or more labels, per-label/name/URL bounds, omitted or exactly 443 port, and no
credentials, fragments, authority escapes, IP/numeric authorities, Unicode, controls,
whitespace or backslashes. It lowercases the hostname, removes one terminal root dot,
omits 443 and supplies `/` for an empty path. Raw escapes, query order, duplicate keys,
`+` and empty-query presence survive into the actual HTTP request target. Local/special
suffixes are explicitly denied. Literal dot-segment and leading `//` path forms are
rejected rather than normalized as an approved target. Relative redirect paths are
resolved before target admission; query-only redirects retain the current raw path.

`PublicAddressPolicy` uses explicit byte/CIDR constants, never hostname conversion,
reverse DNS, reachability or InetAddress convenience classification. IPv4 denies all
reviewed IANA special-purpose allocations, including protocol/AS112/AMT exceptions,
plus multicast/reserved space. IPv6 starts from 2000::/3 and denies 2001::/23,
2001:db8::/32, 2002::/16, 2620:4f:8000::/48 and 3fff::/20; all other families, scoped
addresses and mapped IPv6 candidates are rejected. Java-materialized mapped IPv4
receives the complete IPv4 policy.

The single daemon DNS worker has no queue and no replacement workers. It calls
`InetAddress.getAllByName(canonicalHostname + ".")`. Empty, failed or more than 16
answers fail closed. Every entry is classified before deduplication by address bytes;
the immutable set contains address-only InetAddress objects. The caller enforces an
absolute 3-second deadline, including a post-completion check. Timeout/cancellation
cancels the future, invalidates the operation and discards any late result. The worker
is retained if the underlying JDK lookup ignores interruption. No application cache
or persistent DNS state is added; JDK/OS resolver caching remains inherent to system DNS.

Each hop owns an Apache classic client and basic connection manager. `PinnedDns`
accepts only the approved exact hostname/443, returns the frozen set, and implements
canonical-hostname resolution without lookup. The custom connection operator dials
resolved InetSocketAddress objects from that set, at most two sequential attempts
within one connect budget. TCP attempts finish before one TLS upgrade. A TLS failure,
sent request or uncertain outcome never retries. The URI, Host header, SNI and normal
certificate/hostname verification retain the canonical hostname; no IP URI is substituted.
TLS has explicit empty client key managers and normal trust/hostname validation.

The narrow operator is necessary because Apache's default operator advances to the
next address after IOException, including TLS failure. Apache also layers TLS with
`autoClose=false`: cancellation closes the raw TCP socket before the TLS wrapper,
and the managed connection retains both. Response cleanup closes the manager
IMMEDIATELY before entity/client cleanup, so redirect/rejected bodies are not drained.

Routing is direct and sockets use `Proxy.NO_PROXY`; no system-property/proxy-selector
inheritance is enabled. Requests are HTTP/1.1 GET with fixed application User-Agent,
the three allowed Accept types and `Accept-Encoding: identity`. Automatic redirects,
request retries, cookies, decompression, authentication and authentication caching
are disabled. The auth scheme registry is empty; no supplied/ambient credentials
provider is installed. No caller headers, body, cookies, Authorization or Referer are copied.

Only 301/302/303/307/308 redirects are processed manually, at most two. Exactly one
nonblank bounded ASCII Location is required. Each next target is fully canonicalized,
must retain the initial exact hostname, and must not revisit an admitted URL. Each
followed hop obtains fresh DNS classification, pinning and TLS validation.

## Limits and content

| Budget | Implemented ceiling |
| --- | --- |
| DNS / connect per hop / TLS | 3 s / 3 s / 3 s |
| Response headers / body idle / complete hop | 5 s / 3 s / 12 s |
| Queue / total from admission | 5 s / 30 s |
| Concurrent / queued / retained operations | 1 / 2 / 16 including active |
| Terminal retention | 2 minutes using monotonic time; access expiry plus periodic purge |
| Redirects / DNS entries / address attempts | 2 / 16 before dedup / 2 sequential |
| Entity / decoded UTF-8 representation | 512 KiB / 512 KiB |
| Text | 4096 UTF-16 units AND 8192 UTF-8 bytes |
| Title | 160 Unicode scalars AND 640 UTF-8 bytes |
| URL / Location | 2048 ASCII bytes |
| Headers | 32 KiB aggregate, 64 fields, parser line bound 8 KiB |

Phase watchdogs and socket timeouts use the smaller ceiling and remaining hop/operation
deadline. The header parser counts lines before response admission, including interim
responses, and rejects obsolete folding. HTTP trailers are rejected. No global bridge
budget changed.

Only final 200 HTML/plain/XHTML is accepted. MIME comparison is case-insensitive;
parameters use token/quoted-string parsing with duplicate declarations rejected.
Missing/ambiguous Content-Type, attachment disposition, unsupported MIME and any
nonidentity Content-Encoding fail closed before body extraction. The byte limit covers
declared lengths and streamed/chunked bodies. Decoding uses an explicit charset alias
map for UTF-8, US-ASCII, ISO-8859-1, Windows-1252 and GB18030; missing charset means UTF-8.
Malformed/unmappable input is REPORT, never replacement fallback. Only consistent
initial UTF-8 BOM is consumed; UTF-16/32 BOMs, binary signatures, NUL and disallowed
controls are rejected. HTML meta/XML declarations never choose the decoder.

jsoup parses only an already bounded/decoded in-memory String, with maximum depth 128.
Scripts/styles/templates, frames, objects/embeds and other resource containers are
removed. The first useful title and readable body text nodes are extracted with simple
block boundaries and deterministic whitespace normalization. Output respects both
Unicode/byte budgets with explicit truncation flags. Plain text uses the same control
and text budgets. DOM/body/headers are not retained; no networking, scripting, DTD,
subresource, reader-mode or crawling API is used.

## Operations, security and privacy

`WebFetchService` independently owns QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED state.
DISABLED denies admission before DNS or egress. Canonical caller UUIDs identify
transient operations; POST accepts only operationId/url through a strict duplicate-key,
trailing-token and field/type boundary. Duplicate retained IDs reconcile without new
execution; a changed canonical URL is rejected. GET reconciles, DELETE cancels, late
completion cannot publish, terminal operations expire, and restart retains nothing.
There is no WAITING_APPROVAL, AI TaskManager use, automatic POST replay or persistence.

Existing LocalClientFilter already rejects Browser Web routes before mutation body
buffering; it required no change. Controller and service additionally require native
identity. Browser capabilities/CORS/Translate-only ownership remain unchanged.

Results expose only canonical requested/final URL, hostname, bounded title/text,
acquisition time, MIME, extraction version, truncation flags and controlled state/error.
Resolved addresses, raw headers/bodies, TLS/DNS details and library exceptions never
escape. Sensitive records/targets/operations have redacted toString methods. There
are no Web content logs or host metrics; Apache client/core logging is OFF.

## Tests changed and actual verification

Added strongest-owner tests:

- `src/test/java/io/github/qianlixunbai/workspace/web/WebPolicyTest.java`: canonical target,
  real IPv4/IPv6/mixed-address classifier, frozen resolver boundary, noninterruptible
  DNS timeout/cancellation and no replacement worker.
- `src/test/java/io/github/qianlixunbai/workspace/web/WebTransportTest.java`: actual local
  TCP/TLS sockets, supplied-address-only dialing, raw request target, exact logical
  hostname/Host/SNI, certificate-chain and wrong-hostname failures, two TCP attempt
  limit, redirect revalidation/cross-host/loop denial, no retries or ambient proxy/cookie/
  auth headers, entity/header/MIME/encoding/charset/binary/output bounds and blocked
  header/body cancellation/deadlines.
- `src/test/java/io/github/qianlixunbai/workspace/web/WebFetchServiceTest.java`: bounded
  native admission, no replay, read reconciliation, queue expiry, cancellation with late
  completion, retained capacity, monotonic expiry, restart and DISABLED zero work.
- `src/test/java/io/github/qianlixunbai/workspace/web/WebApiFixture.java`: test-only real
  operation owner with synthetic acquisition, so API tests perform no public networking.
- `src/test/resources/web/fixture-cert.pem` and `fixture-key.pem`: committed test-only
  self-signed material for `fetch-fixture.example.com`, explicitly trusted only by the
  local test seam. Fixture accepts TLS only with the expected logical SNI.

Modified `src/test/java/io/github/qianlixunbai/workspace/api/RuntimeApiTest.java` with one
Web flow for native schema/admission/reconciliation, Browser pre-body denial (including
oversized/malformed input and originless Browser GET), and fresh URL/query log canaries.

Executed from repository root (PowerShell):

```powershell
.\mvnw.cmd dependency:tree '-Dincludes=org.apache.httpcomponents.client5:httpclient5,org.jsoup:jsoup'
.\mvnw.cmd dependency:resolve '-DincludeArtifactIds=httpclient5,httpcore5,jsoup'
.\mvnw.cmd dependency:sources '-DincludeArtifactIds=httpclient5,httpcore5,jsoup' -q
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd '-Dtest=WebPolicyTest,WebTransportTest,WebFetchServiceTest,RuntimeApiTest#webNativeStrictAdmissionReconciliationAndBrowserPreBodyDenial+browserSecurityContract+chromeOriginlessGetContract' test
.\mvnw.cmd '-Dtest=WebTransportTest' test
.\mvnw.cmd '-Dtest=WebPolicyTest,WebTransportTest,WebFetchServiceTest,RuntimeApiTest#webNativeStrictAdmissionReconciliationAndBrowserPreBodyDenial' test
.\mvnw.cmd '-Dtest=WebTransportTest#socketPinningAuthorityTlsAndManualRedirectAdmission+strictDecodingInMemoryExtractionAndDualOutputBudgets,RuntimeApiTest#webNativeStrictAdmissionReconciliationAndBrowserPreBodyDenial' test
.\mvnw.cmd '-Dtest=WebPolicyTest#canonicalApprovalAndAllAddressPublicPolicy' test
```

Dependency checks/source inspection and compile: PASS. Initial combined tests passed
policy/service/API/security owners but failed transport because zero Apache head-line
iterations prohibited every response. The parser was corrected to one status-line
iteration (zero empty preludes). Transport-only command was executed three times:
first exposed a redirect fixture predicate that matched its own destination and the
TLS-wrapper cancellation bug; second isolated the cancellation bug after correcting
the fixture; third PASS after raw-socket-first cancellation. Temporary stack-only
diagnostic assertions were removed. The later focused owner command PASS after DNS,
retention and socket-boundary refinements. The final selective command PASS after
resource-container exclusions and fresh API privacy canaries. No unresolved failures.
The final classifier-only command PASS after making complete-set classification an
explicit first pass before any deduplication; no unrelated owner was rerun.
The existing browserSecurityContract and chromeOriginlessGetContract passed in the
initial combined execution and were intentionally not repeated after Web-only changes.

No full Java/Runtime API suite, package, Desktop/Frontend/Browser repository tests,
WorkspaceSanity, Finance tests, public smoke or model inference was run. Test methods
are integrated evidence, not a claim that every budget/permutation was independently
executed. Architecture Guard source review and subsequent native integration remain
future gates.

## Contract notes and remaining limits

No broadened API, dependency, security policy, persistence or task scope. Conservative
additional admission restrictions are explicit: literal dot-segment/leading `//`
approved paths and HTTP trailers are rejected; charset aliases are only the enumerated
map. These choices require source review alongside the complete implementation.

System DNS cannot reliably be interrupted. A stuck resolver causes Web-only failure
until that one worker returns; late completion has neither connection nor publication
authority. Public-network/Windows product acceptance has not been executed. WPF exact
intent confirmation and bridge methods are outside this candidate, not implemented.

Public Web requests executed: **NONE** (local TLS/API fixtures only).
Model inference executed: **NONE**.
Desktop/Frontend changes: **0**.
Finance changes: **0**.
Browser repository changes: **0**.

W1A-1 IMPLEMENTATION CANDIDATE — READY
W1A-2 — NOT AUTHORIZED
ARCHITECTURE GUARD SOURCE REVIEW — REQUIRED
