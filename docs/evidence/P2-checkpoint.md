# P2 implementation and installation checkpoint

Date: 2026-10-01 UTC. **P2 remains in progress.** GitHub build, authenticated physical loopback, native Android WSS health and the genuine Termux v2 model gate pass. WSS effects, the USIX v2 model gate and physical reboot reconciliation remain required. Keep [the execution document](../chapters/P2.md).

## GitHub APK

[Run 36890107600](https://github.com/yanghoeg/usix-companion/actions/runs/36890107600) built commit `b3c853ea342905b6ade6df36ce91e4ade39d7022`. Both core and Android jobs passed, including the mandatory native Room persistence/migration test task, Android regressions/lint and standalone TLS broker checks. [The renewal build](https://github.com/yanghoeg/usix-companion/actions/runs/36885404502) and [initial build](https://github.com/yanghoeg/usix-companion/actions/runs/36882403814) also passed. This branch is `companion/p2-device-contract`; main was not merged or rewritten. Final installation/check-result documentation follows in a metadata-only commit; it changes no APK/evaluator code.

| APK property | Verified value |
| --- | --- |
| Application/version | `dev.usix.companion`, `0.2.0`, code `7`, min/target SDK `24/34` |
| APK SHA-256 | `0f8cfbccf15feb4f8fc9ed324d501a2c26878699f4065f79b3de0bdc3c11b238` |
| Signing certificate SHA-256 | `1bf19c9316904bfea9df9f422af589a589dbdb425e4329a56bf473d79eca1ded`; matches existing development signing |
| Phone installation file | `/sdcard/Download/usix-companion-0.2.0-P2-b3c853e.apk` |
| Installation state | User confirmed installation; a typed v2 response establishes that the v2 APK runs. Subsequent existing `usix-termux pair` succeeded and [authenticated v2 health/cwd checks](P2/physical-cwd-and-pairing.json) pass. Installed APK bytes were not read back. [Initial physical probe](P2/physical-v2-installation.json). |

`pm install -r` was attempted for the requested data-preserving update; this Termux environment cannot execute Android `/system/bin/pm` (`Operation not permitted`). No ADB device is connected. The user subsequently confirmed the phone installation, and an actual v2 route response establishes that the new protocol is running. Installer-intent success alone was not used as installation evidence. The initial saved token was rejected; `auth: true` only means authentication is enabled. Re-pairing then succeeded through the unchanged installed CLI's standard input, without token arguments/output. The owning-user token file is 0600; authenticated v2 responses now report all five health booleans true. No credential enters these evidence files.

## Validation completed

- Local pure JVM application/protocol/transport tests: 27/11/14, all passing. Transport tests include actual TLS, hostname rejection, replay rejection and Kotlin-router outputs; six generated messages pass the unchanged shared schemas.
- Existing Android adapter/control/composition tests: 31/2/1 pass. Lint and release assembly pass locally. Four native Room tests cannot initialize Robolectric's unsupported Linux aarch64 native runtime on this Termux host; they stay enabled and their mandatory Linux CI task passes. No persistence test was skipped or replaced with a fake.
- Python: 6 architecture, 18 contract, 11 integration/TLS/CLI tests and 25 tooling tests pass in Linux CI. The two new tooling tests specifically verify the existing TTY approval boundary and also pass locally.
- Trusted session renewal is owner-authenticated and challenge-bound. It rotates bearer/grant, invalidates old controller/credentials, and preserves the exact context, selected package, receipt history, remaining action budget and delivered/acknowledged cursors. Old requests authorized before credential rotation are rejected. Renewal cannot change an existing task/package or replenish its budget.

## Physical v2 checks

- [Loopback scenarios](P2/physical-loopback-scenarios.json): all 30 controlled steps pass on the real APK, including duplicate and changed action bindings, cross-session receipt rejection, replaced/paused/stale controller leases, dropped acknowledgement recovery by receipt reads, post-dispatch cancellation and event/resync acknowledgements. Only Companion itself was launched; `Dispatched` is not business verification.
- [Four cwd checks](P2/physical-cwd-and-pairing.json): actual installed CLI calls from both runtime source directories, Companion and `/tmp` preserve the trusted captured workspace/context and authenticated health. These direct checks are explicitly not model evidence.
- [Android outbound WSS](P2/physical-wss-connection.json): the actual phone's OkHttp/native TLS, explicit test certificate and owning-user broker return authenticated health. The broker is on the same physical phone. External Linux/network deployment is not established.
- [First WSS effect scenario](P2/physical-wss-scenarios.json): pairing/health/controller negotiation work, then `app.open` correctly returns `DeviceLocked` and `effect: none`. The scenario is recorded as failed, with no automatic replay. Unlocking the phone is required before a fresh complete scenario; this rejection does not indicate an APK defect.

## Genuine runtime/device observations

| Runtime/profile | Observation | P2 v2 gate |
| --- | --- | --- |
| Installed USIX, configured `qwen3.8-flash-next`, code/xhigh, exact-command rules | [Noninteractive attempt](P2/usix-legacy-model-health.json) and [TTY attempt](P2/usix-legacy-interactive-xhigh-model-health.json) reached the real model and completed without a tool call. Neither is a device/model pass. No `--yolo` or deployment change was used. | Pending. P0's separately authorized bypass-flag profile remains historical evidence, not a result of these attempts. |
| USIX TTY invocation with unsupported `high` effort | [Attempt record](P2/usix-legacy-interactive-model-health.json) exited 2 before reaching a model. Companion's evaluation helper was corrected to the installed CLI's supported `xhigh`; runtime source was untouched. | Failed attempt retained. |
| Installed USIX Termux, existing Qwen3.5-2B-Q5_K_M CPU server | [Genuine trace](P2/termux-legacy-model-health.json): model generated the exact existing `shell` command, one-time approval was granted, fresh real-phone health matched all five final model-reported booleans, TUI exited 0. Two real model requests; initial prompt processing 466,005 ms. | Legacy compatibility health passes; the separate v2 result follows below. |
| Installed USIX Termux, actual v2 integration | [Genuine v2 trace](P2/termux-v2-model-health.json): exact existing shell approval, new v2 capabilities/health witness through the installed CLI, matching five final model booleans and TUI exit 0. Two actual model requests; first prompt 757,653.96 ms, second prompt 44,024.46 ms. | **Pass.** Capability readiness honestly reports a locked phone; no effect/reboot evidence is claimed by this read-only model trace. |
| Installed USIX, default TTY v2 profile | [Original v2 attempt](P2/usix-v2-interactive-model-health.json) completed without a tool; [fixed-helper attempt](P2/usix-v2-explicit-model-health.json) reached its 300-second limit with an unsuccessful unmatched result and no witness. | Not verified. The prepared optional existing `--yolo` profile awaits explicit permission for this controlled v2 read. |

The controlled traces read no mail, notification contents or private screen data. The evaluation-owned local model server was stopped afterward and port 8080 was confirmed closed. Runtime source, installed-binary hashes and clean worktrees are identical in [before](P2/runtime-before.json) and [after the genuine v2 model run](P2/runtime-after-termux-v2-model.json) captures. The current Termux source revision and installed binary are recorded independently; the binary is not claimed to have been rebuilt from that revision.

## Required continuation

1. Unlock the physical phone and finish the full WSS scenario on a fresh evidence path. Renew expired profiles through trusted owner setup, preserving context/budget/history; no effects are retried automatically.
2. Finish the USIX genuine v2 model/tool/device trace. [The helper](../../tools/capture_usix_v2.py) preserves the default and `--interactive` paths and adds the existing `--yolo` only when explicitly selected with user permission for the controlled read command. The current request for that permission is unanswered; it has not been used. Termux's v2 gate is already complete.
3. Reboot the real phone, reconcile the saved action IDs and acknowledged event cursor, and reject old controller revisions without replay. Unit DB reopen and service restoration are separate evidence, not a physical reboot.
4. Write final permanent acceptance evidence, update the plan and delete the temporary chapter document only after every required exit passes. P3 has not started.

[Machine-readable checkpoint](P2/verification-checkpoint.json) pins the checks, install probe and explicitly incomplete gates.
