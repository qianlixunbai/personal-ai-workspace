# Interrupted offline lock preparation — 2026-11-11

Status: unresolved interruption during input copying. The source order and persisted evidence support that restore was not reached; the exit cause and normal cleanup are not proved. This is a recovery record, not a successful run receipt or authorization to retry.

## Run identity and preserved evidence

- Run ID: `06d179db0e54442abf3ebcc5f865808f`.
- Root: `D:\IDEA\Daima\personal-ai-workspace\artifacts\candidate-lock-inputs-06d179db0e54442abf3ebcc5f865808f` (L).
- Product revision: `2ca50415cc6752288110fb31aa0abff6af7d4ed9`.
- Source baseline: `f611dfc1f9758b333ed0e689a5134274284ceec0`, plus the independently reviewed process-local `DOTNET_GENERATE_ASPNET_CERTIFICATE=false` line in `Set-LockEnvironment`.
- L/ownership.json: 318 bytes, SHA256 `059c304f5ebb5d7e2610dc48e164f1c3dbb644a39d36a8bcfeaacecc739ea2a3`; ownership nonce `abc273abc7434caaae6f4bdb5a7b4a4a`.
- Ownership file creation/last-write UTC: `2026-11-11T05:27:20.3766911Z`.
- `.verification/candidate-build-active.json`: 216 bytes, SHA256 `b00e01e40b3074c703b3f0762529c614578cfb088f6e71a49b5f4f47765db244`; state `LOCK_INPUTS_ACTIVE_OR_UNRECONCILED`, guardian PID `40436`, session `1`, root identifying L.
- Activity marker creation UTC: `2026-11-11T05:27:20.3582453Z`; last-write UTC: `2026-11-11T05:27:20.3597747Z`.

The marker and ownership nonce correlate the saved files with this run. They do not record a process creation time or an executable identity for PID 40436; file timestamps are not process birth evidence.

The private control copies matched the approved bytes during recovery:

| File under L/control | Bytes | SHA256 |
| --- | ---: | --- |
| GenerateLocks.ps1 | 22504 | `19b7596552cc9133e17301f35d7fdc3ad54978b297c6df97ef9fd36bebf8edab` |
| lock-inputs.json | 6716 | `6b5c069a8f5fe6256f842e2bf65f1bb5dee303a1f487c4fc370dec5de0759827` |

## Recovery observations

The authorized repository entry was invoked once. Its executor session was 94012; a later status/read attempt failed with `exec-server transport disconnected; failed to resume exec-server session: exec-server protocol error: executor key changed during session recovery`. No second invocation occurred.

At the read-only recovery observation on 2026-11-11 at approximately 05:35 UTC:

- L and ownership were present. `control/pwsh-tree.json` and `control/git-tree.json` existed; `control/dotnet-tree.json` did not. A manifest's presence alone does not prove its entire tree was copied.
- `evidence/input-copy-complete.json`, worker command/start/complete files, worker stdout/stderr, and step 001–003 command/start/process-exit/exit receipts were absent.
- Both `source/desktop/src/PersonalAiWorkspace.{Core,Desktop}/packages.lock.json` files and the corresponding `accepted-locks/{Core,Desktop}.packages.lock.json` copies were absent. No generated lock hashes or actual eight-package/cache/source validation are available.
- `evidence/guardian-result.json`, `guardian-released.json`, `nuget-hashes.json`, and `output-inventory.json` were absent.
- The exact PID 40436 was absent. In observer session 1, opening the existing named mutex `Local\Dot_KAG_Workspace_HeavyRun` found no object. Neither observation proves a normal exit or completion of cleanup.
- The activity marker remained; `.verification/candidate-tools-synthetic-active.json` was absent.

Independent source-order review confirmed that before input-copy-complete is successfully written, the guardian performs only path checks, copying, hashing and JSON operations. Add-Type, Job construction and Git/Pwsh/dotnet child execution occur later. Together with the persisted phase evidence, this supports interruption during tool input copying before restore. Missing receipts are not absolute proof of runtime execution history, and no Job-cleanup success is asserted.

## Scope and remaining blocker

Preserve L, the original activity marker, and all original receipts unchanged. Do not rerun/resume the entry, substitute another root, archive/clear the marker, or infer successful cleanup from a missing PID. Cause, exit code, and orderly cleanup remain unknown. The reported Codex restart does not establish a Windows reboot. The missing process-creation identity remains a limit on marker disposition; the marker blocks the related heavy entry points, not independent source development.

The recovery checkpoint `fbf58a7` recorded the interruption and the reviewed certificate-suppression line only. Historical f611dfc reports and certificate-suppression.json retain their original scope and are not rewritten as run results. No tests, runner, restore, build, product/helper/native entry, certificate operation, or KAG work were performed in that recording/checkpoint round. Subsequent independent source development has its own report. `productionGo=false`; `nativeValidation=NOT_RUN`; generated locks are unavailable.
