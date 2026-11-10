# Find memories while managing them

2026-11-10. Started at `7003f1b08beaab31b47bb975f293eee5acb67d79` on `codex/knowledge-version-compare`. No repository/ancestor AGENTS or repository .agents directory exists. The six user documents, main and previous bundles remain outside this change.

## User problem and scope

The existing explicit memory goal includes finding, reviewing and revising saved memories. Both management editors only offered literal, case-sensitive substring search. A user remembering scattered keywords (for example `java 测试`) could get no results even though the existing conversation selector's lexical recall could find the record. The Runtime already supplies bounded ACTIVE-only recall, so reuse it for management without introducing a model, vector index, automatic selection or storage change. Archived records retain literal search and the existing explicit restore operation.

## Native management flow

The native manager now offers **原文查找** and **查找相关记忆**. Related results feed the existing read-only comparison and explicit same-ID revision workflow without discarding the editor draft. Matching terms appear when comparing the exact returned revision. Refresh, type filtering and post-save/archive refresh keep the chosen search mode; archived filtering returns to literal search. Related results have no misleading next-page button: the UI states the 20-result bound and that zero word matches does not prove absence. Enter repeats the current search mode. No lookup adds Ask references or writes memory.

One new focused synthetic STA workflow passed, 1/1: literal miss -> related results -> retained draft -> comparison -> explicit same-ID Save -> archive -> literal archived lookup -> restore; type filtering, zero results and 21-result truncation are covered in that same flow. Compilation had no warnings/errors. Evidence: `.verification/memory-management-recall-native-20261110/{Check.ps1,run.log,build.log,test.log,test-results/memory-management-recall-native.trx}`. Shared Session 1 wrapper PID 16544 held the mutex for build/check and released at 08:36:51 UTC.

This is synthetic HTTP/WPF control evidence, not real persistence or WebView2 acceptance. No product EXE, browser, Profile, Runtime service, model or real memory was opened. The current development ZIP is still the earlier draft-recovery delivery; a combined package will follow the remaining management workflow work.

Known access restrictions from the prior handoff are retained: no `.verification` enumeration, no retries of the seven unrelated private directories, Crashpad reports or refused process metadata. No new WebView2 root-cause evidence exists; old startup experiments and security changes are not repeated. Historical full EXE/native save/reopen acceptance belongs to `cff8015`. Normal WebView2/default entry, maintenance/migration and formal release remain unaccepted; whole-database zero writes is not claimed.
