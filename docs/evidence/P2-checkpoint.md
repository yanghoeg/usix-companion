# P2 implementation and installation checkpoint

Date: 2026-10-01 UTC. **P2 remains in progress.** GitHub build, all 30 physical loopback and 30 native Android WSS scenario steps, four cwd checks and both genuine v2 model gates pass. Physical reboot reconciliation remains required. Keep [the execution document](../chapters/P2.md).

## GitHub APK

[Run 36890107600](https://github.com/yanghoeg/usix-companion/actions/runs/36890107600) built the installed delivery checkpoint `b3c853ea342905b6ade6df36ce91e4ade39d7022`. Both core and Android jobs passed, including the mandatory native Room persistence/migration test task, Android regressions/lint and standalone TLS broker checks. [Evaluator run 36937058592](https://github.com/yanghoeg/usix-companion/actions/runs/36937058592) also passed core/build/native Room/lint at `e700516ff6130c2e6773e80849dcecaf921c836d`; Android source is unchanged from the installed checkpoint. [The renewal build](https://github.com/yanghoeg/usix-companion/actions/runs/36885404502) and [initial build](https://github.com/yanghoeg/usix-companion/actions/runs/36882403814) also passed. This branch is `companion/p2-device-contract`; main was not merged or rewritten.

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
- [First WSS effect scenario](P2/physical-wss-scenarios.json): pairing/health/controller negotiation work, then `app.open` correctly returns `DeviceLocked` and `effect: none`. The original failure is retained. After the user unlocked the phone, [all 30 fresh WSS steps](P2/physical-wss-unlocked-scenarios.json) passed, including physical dispatch, controller/action conflicts, dropped-response receipt reconciliation and event acknowledgements. Only Companion itself was opened; no business verification is claimed.

## Genuine runtime/device observations

| Runtime/profile | Observation | P2 v2 gate |
| --- | --- | --- |
| Installed USIX, configured `qwen3.8-flash-next`, code/xhigh, default exact-command rules | [Noninteractive attempt](P2/usix-legacy-model-health.json) and [TTY attempt](P2/usix-legacy-interactive-xhigh-model-health.json) reached the real model and completed without a tool call. Neither is a device/model pass. No `--yolo` or deployment change was used in those attempts. | Default-profile limitation retained; the separately authorized passing v2 profile follows below. |
| USIX TTY invocation with unsupported `high` effort | [Attempt record](P2/usix-legacy-interactive-model-health.json) exited 2 before reaching a model. Companion's evaluation helper was corrected to the installed CLI's supported `xhigh`; runtime source was untouched. | Failed attempt retained. |
| Installed USIX Termux, existing Qwen3.5-2B-Q5_K_M CPU server | [Genuine trace](P2/termux-legacy-model-health.json): model generated the exact existing `shell` command, one-time approval was granted, fresh real-phone health matched all five final model-reported booleans, TUI exited 0. Two real model requests; initial prompt processing 466,005 ms. | Legacy compatibility health passes; the separate v2 result follows below. |
| Installed USIX Termux, actual v2 integration | [Genuine v2 trace](P2/termux-v2-model-health.json): exact existing shell approval, new v2 capabilities/health witness through the installed CLI, matching five final model booleans and TUI exit 0. Two actual model requests; first prompt 757,653.96 ms, second prompt 44,024.46 ms. | **Pass.** Capability readiness honestly reports a locked phone; no effect/reboot evidence is claimed by this read-only model trace. |
| Installed USIX, default TTY v2 profile | [Original v2 attempt](P2/usix-v2-interactive-model-health.json) completed without a tool; [fixed-helper attempt](P2/usix-v2-explicit-model-health.json) reached its 300-second limit with an unsuccessful unmatched result and no witness. | Default profile not verified; neither failure is relabelled as a pass. |
| Installed USIX, user-authorized existing `--yolo` for one v2 read | [Actual trace](P2/usix-v2-authorized-model-health.json): one exact existing `bash` call, successful matching fresh real-device v2 witness and `done.completed`, exit 0. Actual model `qwen3.8-flash-next`; 18,783 ms total, 2,745 ms witness helper. All five authenticated health booleans are true. | **Pass in this explicitly authorized profile.** The user limited the option to this one state query. Static/surface/deployment gates and runtime source are unchanged; this grants no ongoing bypass permission. |

The controlled traces read no mail, notification contents or private screen data. The evaluation-owned local model server was stopped afterward and port 8080 was confirmed closed. Runtime source, installed-binary hashes and clean worktrees are identical in [before](P2/runtime-before.json) and [after both genuine v2 model runs](P2/runtime-after-usix-v2-model.json) captures. The current Termux source revision and installed binary are recorded independently; the binary is not claimed to have been rebuilt from that revision.

## Physical reboot checkpoint

[The pre-reboot checkpoint](P2/physical-reboot-before.json) passes 17 real-device checks and saves four exact dispatched receipts, both acknowledged event cursors, stable event IDs, a WSS controller lease/revision and a SHA-256 of the kernel boot ID. It is preparation, **not reboot evidence**. The lease lasts at most 60 seconds; expiry alone will not satisfy recovery. The evaluator requires a changed kernel boot ID and a persisted controller-clear event before renewing the WSS session, verifies exact receipts/event history and rejects backwards acknowledgements before advancing any cursor. A new controller revision must exceed the saved revision and reject a new controlled probe using the old lease without creating a receipt. The four saved effect commands are never submitted again.

The same-boot negative check exited 1 with zero device calls/renewals/effect replays. Three new boundary tests pass locally: same boot, unreadable boot identity with redacted diagnostics, and refusal to overwrite previous evidence. The broker is on the phone and will stop during a physical reboot. Restart its same private configuration; do not initialize new registrations or reinstall Android remote settings. The app's native WSS reconnection then tests its persisted DataStore/Keystore settings. If profiles expire, existing trusted renewal preserves context/history/budget; this does not create new sessions.

After the user reboots, unlocks and opens Companion and Termux, restart the broker with the retained private config, then run:

```sh
/data/data/com.termux/files/home/usix-companion/.venv-integration/bin/python \
  /data/data/com.termux/files/home/usix-companion/tools/capture_device_v2_reboot.py \
  --output /data/data/com.termux/files/home/usix-companion/docs/evidence/P2/physical-reboot-after.json \
  after --baseline /data/data/com.termux/files/home/usix-companion/docs/evidence/P2/physical-reboot-before.json
```

## Required continuation

1. Reboot the real phone, reconnect using the existing broker configuration, reconcile the saved action IDs and acknowledged event cursors, and reject old controller revisions without replay. Unit DB reopen and service restoration are separate evidence, not a physical reboot. The concrete checkpoint and evaluator above are ready; physical reboot is still required.
2. Write final permanent acceptance evidence, update the plan and delete the temporary chapter document only after every required exit passes. P3 has not started. Both genuine v2 model gates and the complete local/WSS scenarios are already verified; do not rerun the one-time authorized `--yolo` query.

[Machine-readable checkpoint](P2/verification-checkpoint.json) pins the checks, install probe and explicitly incomplete gates.
