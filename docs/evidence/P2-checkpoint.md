# P2 implementation and installation checkpoint

Date: 2026-10-01 UTC. **P2 remains in progress.** Build/test evidence and legacy model evidence are recorded below; none substitutes for the remaining physical v2/reboot gates. Keep [the execution document](../chapters/P2.md).

## GitHub APK

[Run 36885404502](https://github.com/yanghoeg/usix-companion/actions/runs/36885404502) built commit `51b3b44064d5ba6b436be1f011c9fcb37020acf2`. Both core and Android jobs passed, including the mandatory native Room persistence/migration test task, Android regressions/lint and standalone TLS broker checks. [The earlier build](https://github.com/yanghoeg/usix-companion/actions/runs/36882403814) also passed. This branch is `companion/p2-device-contract`; main was not merged or rewritten.

| APK property | Verified value |
| --- | --- |
| Application/version | `dev.usix.companion`, `0.2.0`, code `7`, min/target SDK `24/34` |
| APK SHA-256 | `bf6feaa21ba9f22ecfb89eecaa5b63b7b19e4f5985f6ddb4177850d0094dbc9d` |
| Signing certificate SHA-256 | `1bf19c9316904bfea9df9f422af589a589dbdb425e4329a56bf473d79eca1ded`; matches existing development signing |
| Phone installation file | `/sdcard/Download/usix-companion-0.2.0-P2-51b3b44.apk` |
| Installation state | APK copied and installer intent requested. Installation **unverified**; `/v2/pair/challenge` still responds 404. |

`pm install -r` was attempted for the requested data-preserving update; this Termux environment cannot execute Android `/system/bin/pm` (`Operation not permitted`). No ADB device is connected. The phone installer requires its on-device confirmation. Installer-intent success alone is not installation evidence.

## Validation completed

- Local pure JVM application/protocol/transport tests: 27/11/14, all passing. Transport tests include actual TLS, hostname rejection, replay rejection and Kotlin-router outputs; six generated messages pass the unchanged shared schemas.
- Existing Android adapter/control/composition tests: 31/2/1 pass. Lint and release assembly pass locally. Four native Room tests cannot initialize Robolectric's unsupported Linux aarch64 native runtime on this Termux host; they stay enabled and their mandatory Linux CI task passes. No persistence test was skipped or replaced with a fake.
- Python: 6 architecture, 18 contract, 11 integration/TLS/CLI tests and 25 local tooling tests pass. The latest published APK run included the previous 23 tooling tests; the two new tests specifically verify the existing TTY approval boundary.
- Trusted session renewal is owner-authenticated and challenge-bound. It rotates bearer/grant, invalidates old controller/credentials, and preserves the exact context, selected package, receipt history, remaining action budget and delivered/acknowledged cursors. Old requests authorized before credential rotation are rejected. Renewal cannot change an existing task/package or replenish its budget.

## Genuine runtime/device observations

| Runtime/profile | Observation | P2 v2 gate |
| --- | --- | --- |
| Installed USIX, configured `qwen3.8-flash-next`, code/xhigh, exact-command rules | [Noninteractive attempt](P2/usix-legacy-model-health.json) and [TTY attempt](P2/usix-legacy-interactive-xhigh-model-health.json) reached the real model and completed without a tool call. Neither is a device/model pass. No `--yolo` or deployment change was used. | Pending. P0's separately authorized bypass-flag profile remains historical evidence, not a result of these attempts. |
| USIX TTY invocation with unsupported `high` effort | [Attempt record](P2/usix-legacy-interactive-model-health.json) exited 2 before reaching a model. Companion's evaluation helper was corrected to the installed CLI's supported `xhigh`; runtime source was untouched. | Failed attempt retained. |
| Installed USIX Termux, existing Qwen3.5-2B-Q5_K_M CPU server | [Genuine trace](P2/termux-legacy-model-health.json): model generated the exact existing `shell` command, one-time approval was granted, fresh real-phone health matched all five final model-reported booleans, TUI exited 0. Two real model requests; initial prompt processing 466,005 ms. | Legacy compatibility health passes; v2 remains pending. |

The controlled traces read no mail, notification contents or private screen data. The evaluation-owned local model server was stopped afterward and port 8080 was confirmed closed. Runtime source, installed-binary hashes and clean worktrees are identical in [before](P2/runtime-before.json) and [after-model](P2/runtime-after-models.json) captures. The current Termux source revision and installed binary are recorded independently; the binary is not claimed to have been rebuilt from that revision.

## Required continuation

1. Confirm installation/opening of the verified GitHub APK and authenticate a real v2 response; preserve the existing owner pairing token.
2. Create private trusted profiles for both runtimes, selecting only `dev.usix.companion` for the controlled launch checks. Exercise loopback and the real phone's outbound Android WSS channel to the owning-user broker.
3. Run [the physical contract evaluator](../../tools/capture_device_v2_scenarios.py) on fresh evidence paths. It checks duplicate/conflicting IDs, replaced/paused leases, receipt privacy, deadlines, post-dispatch cancellation, dropped acknowledgements and event acknowledgements. It deliberately marks model/reboot evidence false; it cannot supply those gates.
4. Run genuine v2 model/tool/device traces through both installed runtimes. [USIX's helper](../../tools/capture_usix_v2.py) supports the existing `--interactive` one-time exact-command approval path; default/no-dispatch attempts remain failures. Use the unchanged Termux TUI approval path, not its auto-denying one-shot shell path.
5. Reboot/restart the real phone process, reconcile the saved action IDs and acknowledged event cursor, and reject old controller revisions without replay. Unit DB reopen and service restoration are separate evidence, not a physical reboot.
6. Write final permanent acceptance evidence, update the plan and delete the temporary chapter document only after every required exit passes. P3 has not started.

[Machine-readable checkpoint](P2/verification-checkpoint.json) pins the checks, install probe and explicitly incomplete gates.
