# Find memories while managing them

2026-11-10. Started at `7003f1b08beaab31b47bb975f293eee5acb67d79` on `codex/knowledge-version-compare`. No repository/ancestor AGENTS or repository .agents directory exists. The six user documents, main and previous bundles remain outside this change.

## User problem and scope

The existing explicit memory goal includes finding, reviewing and revising saved memories. Both management editors only offered literal, case-sensitive substring search. A user remembering scattered keywords (for example `java 测试`) could get no results even though the existing conversation selector's lexical recall could find the record. The Runtime already supplies bounded ACTIVE-only recall, so reuse it for management without introducing a model, vector index, automatic selection or storage change. Archived records retain literal search and the existing explicit restore operation.

## Native management flow

The native manager now offers **原文查找** and **查找相关记忆**. Related results feed the existing read-only comparison and explicit same-ID revision workflow without discarding the editor draft. Matching terms appear when comparing the exact returned revision. Refresh, type filtering and post-save/archive refresh keep the chosen search mode; archived filtering returns to literal search. Related results have no misleading next-page button: the UI states the 20-result bound and that zero word matches does not prove absence. Enter repeats the current search mode. No lookup adds Ask references or writes memory.

One new focused synthetic STA workflow passed, 1/1: literal miss -> related results -> retained draft -> comparison -> explicit same-ID Save -> archive -> literal archived lookup -> restore; type filtering, zero results and 21-result truncation are covered in that same flow. Compilation had no warnings/errors. Evidence: `.verification/memory-management-recall-native-20261110/{Check.ps1,run.log,build.log,test.log,test-results/memory-management-recall-native.trx}`. Shared Session 1 wrapper PID 16544 held the mutex for build/check and released at 08:36:51 UTC.

This is synthetic HTTP/WPF control evidence, not real persistence or WebView2 acceptance. No product EXE, browser, Profile, Runtime service, model or real memory was opened. The current development ZIP is still the earlier draft-recovery delivery; a combined package will follow the remaining management workflow work.

Known access restrictions from the prior handoff are retained: no `.verification` enumeration, no retries of the seven unrelated private directories, Crashpad reports or refused process metadata. No new WebView2 root-cause evidence exists; old startup experiments and security changes are not repeated. Historical full EXE/native save/reopen acceptance belongs to `cff8015`. Normal WebView2/default entry, maintenance/migration and formal release remain unaccepted; whole-database zero writes is not claimed.

## Normal web management flow

Native management is committed as `b5a5a9e`. The web manager now offers the same explicit related lookup beside literal Search. Relevance order comes from Runtime and survives the metadata projection; bodies still require explicit preview/read. Results flow into the existing retained-draft revision, Save, Archive and Restore operations. Refresh and type changes keep the search mode, archived filtering returns to literal search, and session changes discard pending results. Search errors retain the requested criteria for an explicit refresh. The UI states ACTIVE-only lexical matching, the 20-result cap, and the meaning of zero matches; related results do not expose false pagination or literal-query highlighting.

The new native Bridge method `memory.recall` accepts only a bounded query and optional existing type. It reuses RuntimeClient recall and the existing bounded per-session record authorization. It returns at most 20 metadata records, exact decimal revisions and total; it neither returns all bodies nor changes conversation selections. Old-session responses cannot authorize a new session. The ordinary Browser client receives no new capability; this method belongs only to the trusted desktop Workspace Bridge.

Necessary checks: two affected .NET Bridge checks passed, 2/2, with zero build warnings/errors; TypeScript passed; the existing literal preview workflow passed, 1/1. The new related-management component workflow initially failed on a test assertion that incorrectly treated the trailing editor-state notification as a lookup. Only that assertion changed; the affected workflow then passed, 1/1, including revision/save/archive/restore, 21-result bound, read failure, empty type filter and session reset during a late read. This is not a WebView2 or real-store acceptance claim.

Evidence: `.verification/memory-management-recall-web-20261110/{CheckBridge.ps1,CheckWeb.ps1,CheckWebRetry.ps1,build.log,typecheck.log,test.log,test-retry.log,bridge-run.log,web-run.log,web-retry-run.log}`. The first failed frontend log is retained. A trailing shell log-display command used the task's changed working directory and returned a missing-path error after the successful retry; exact-path read of `web-retry-run.log` confirms PASS and mutex release, so no test was repeated for that display error. No service/browser/model/database was started.

The initial ordinary native `git add`/commit attempt could not create `.git/index.lock` because the sandbox makes `.git` read-only. Read-only status confirmed nothing staged/committed and no lock. The normal narrowly scoped escalation then committed the five native-module files successfully; this was a filesystem sandbox boundary, not an auto-review rejection. No denied private directory was retried and no access policy changed.
