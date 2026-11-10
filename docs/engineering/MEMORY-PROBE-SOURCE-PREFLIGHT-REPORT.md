# Profile probe build-input freshness

2026-11-10. Baseline `53c15610cf32a7ba0ad303879e2df6661603b15b`, branch `codex/knowledge-version-compare`.

## Problem and change

The earlier Memory probe compiled successfully with an October 28 source manifest, then stopped at SOURCES before creating a Profile because its Runtime JAR pin was stale. The detailed diagnosis and repaired input are recorded in [the WebView2 investigation](MEMORY-WEBVIEW2-FAILURE-INVESTIGATION.md). A successful C# build had hidden this actionable delivery prerequisite until execution.

The opt-in Profile runtime probe now runs `ValidateRuntimeLifecycleProbeSources` before PrepareForBuild. It requires an explicit existing TrustedDevelopmentManifest and invokes `desktop/frontend/scripts/verify-probe-sources.mjs` using the project's existing Node toolchain. Ordinary unit-test, Desktop and release builds do not enable this target.

For Format 2 local manifests, the script bounds the manifest to 131072 bytes and 5–2048 entries, restricts paths to the named Runtime/JNA JARs or JavaHome subtree, rejects duplicate and linked paths, and streams SHA256 over only the listed files. It stops at the first invalid/missing/unreadable/changed input with an actionable path and, for a mismatch, both hashes. It never enumerates directories, writes source files/manifests, downloads, installs, runs a Profile, or updates trust values automatically.

This is freshness checking, not runtime admission. The C# guard still independently validates and pins actual runtime inputs, links, identity, permissions, Java release and Profile context. Format 3 bundled relative paths are explicitly reported as deferred to publishing/native admission, not reported as checked by this local target. This preserves the existing bundled workflow. That deferred branch was not validated by this stage.

## Focused verification

Only two coordinated checks were run, with no desktop, JVM, model or extra test suite:

1. Invoking only the new MSBuild target with `.verification/trusted-development-runtime-sources.local.json` failed as expected (MSBuild exit 1). It identified `target/personal-ai-workspace-0.1.0.jar`, expected SHA256 `7565540d65b9d666a95cef0475d44d2b79656d80f736e681ca0ced6f719dfb7a`, actual `71f181bf8d991aa5484c9efb83d548ec275644407b388cd68be220421df5dffd`. The wrapper asserted this exact cause, so an unrelated parser, read-permission or tool error could not count as the expected rejection. No application or C# compilation was invoked by that target-only check.
2. The normal no-restore incremental probe build with `.verification/memory-desktop-delivery-20261109/test-sources.json` printed `420 exact hashes match` and passed in 6.00 seconds with zero warnings/errors. FrontendSkipBuild remained enabled. The short window was released afterward.

Evidence directory: `.verification/memory-probe-source-preflight-20261110/`; files `rejected-old-input.log`, `rejected-old-input.json`, and `accepted-build.log`. XML and diff checks also passed. Missing/unreadable/link cases use the same fail-stop path but were not separately exercised; no ACL modifications were made to create test cases.

The existing runtime/JAR/native-helper/source manifests were not changed. The Memory UI and confirmation semantics were not changed. This prevents a known class of late fixture failures; it does not pass actual runtime admission, fix WebView2, or complete Memory save/close/reopen or final-commit package acceptance. Port 18765 and the prior comparison's unobserved browser descendants remain unknown; neither was queried or killed for this work.

## Rollback

Before edits, `.verification/memory-probe-source-preflight-20261110/baseline-53c1561.bundle` was created and verified with exact HEAD and complete history. SHA256: `06ECD3DCF26EDA1E2A08D77BBA9AD379F62F951010325569F8647B842D1B166F`. Only the verifier, test project and this report belong to the checkpoint; protected user documents, verification evidence and binaries are excluded. No main/remote/security configuration change or push is required.
