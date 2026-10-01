# P0 measured baseline

Evaluation date: 2026-10-01 UTC. P0 is **complete**: build/contracts and genuine model/device traces through both unchanged runtimes pass. The final supported profiles are USIX with its existing user-authorized `--yolo` flag in the current deployment, and Termux's unchanged TUI with the recorded CPU-server profile. This permanent evidence remains after deletion of the temporary chapter document.

## Revisions and device

| Component | Audited revision/profile |
| --- | --- |
| Companion baseline HEAD | `c54e928ee891eddc81b6336401dfe3f1f8232ad2`; local P0 build/contract changes. Android source is unchanged. |
| Required USIX checkout | `b7a9755386a4b5017da7d921516e3c21b67244c2`; installed CLI `v0.1.0+b7a975538`. |
| Required Termux checkout | `f92c164c3a54f505391ed8d38c98445605641768`; installed CLI `v0.0.1`. |
| Host/device | Android/Termux, aarch64, Samsung SM-F956N (`q6q`), Android 16 / runtime API 36. |
| Android source app version | 0.1.6 / versionCode 6, min/compile/target 24/34/34. The installed bridge version has not been independently measured. |
| Device readiness | v1 health: `ok`, `auth`, `paired`, `listener` true; `accessibility` false. |

Device release/API were measured through the native read-only system-property API. Python's `platform.android_ver().api_level` reported its build floor 24, so it is not used as the device's runtime SDK measurement. `/system/bin/getprop` execution was unavailable in this agent environment. No new APK was installed for the baseline.

Both sibling checkout statuses remained clean after the runtime attempts; their revisions and final gates are retained in [the source-state checks](P0/runtime-source-state.json). No source, registry, generated clients, lockfiles or manifests were written there. User-level instructions/helper/Termux Markdown skill remain outside those trees in `~/.usix/`, as documented in [USIX.md](../../USIX.md).

## Build and contract evidence

- The official checksum-verified Gradle 8.10.2 wrapper runs on this host with JDK 17.0.20. Existing AGP 8.5.2/Kotlin 1.9.24/SDK 34 pins are retained in the version catalog. [Reproduction and provenance](../build.md).
- `assembleRelease` produced a 635,341-byte APK. Its SHA-256 is `cb5857b0c4114589589091e583091524277278e236826aca2c8c80e1f87a164f`; `apksigner verify` passed with APK Signature Scheme v2 and one signer. It uses the unchanged public development key.
- The default Robolectric provider failed before assertions because Conscrypt 2.5.2 lacks the required `conscrypt_openjdk_jni-linux-aarch_64`. The explicit supported Termux provider profile then passed **all 24 existing tests**: 5 mail, 5 notification, 14 screen; zero failures/errors/skips. Test sources were not changed. This profile does not establish native Conscrypt/TLS behavior.
- The six v2 schemas, seven wire examples, Korean canonical-hash vector and trusted-state example validate. The conformance suite has **18 passing tests**, including 14 shared failure vectors. Three SDK-preparation security tests also pass. [Contract and checks](../../contracts/device/v2/README.md).
- The continuation revalidated all 18 conformance tests and the regression-source/APK hashes. The tools suite now has **eight passing tests**: the three SDK tests plus five [runtime-evidence tests](../../tests/tools/test_runtime_evidence.py) covering fabricated or unrelated success, ambiguous correlation, incomplete turns, disagreeing witnesses and private-output redaction. No Android source/test was changed for this continuation.
- [Build/test counts and source hashes](P0/build-results.json) are recorded separately from local logs. The pre-change HEAD also has a [successful Linux CI build/regression run](https://github.com/yanghoeg/usix-companion/actions/runs/36805570013); that run predates the new wrapper/catalog/contract workflow and is not a new-change CI result.

## Supported-interface and app matrix

“Measured” applies only to the listed behavior. A source-available operation or successful direct CLI request is not a genuine agent workflow.

| Profile/interface | Evidence | Status / requirement |
| --- | --- | --- |
| External v1 HTTP helper from `../usix` | Real loopback `/health`; [direct-only measurements](P0/direct-health-smokes.json) and [genuine model/witness trace](P0/usix-yolo-model-health-trace.json). | Real model → admitted existing `bash` → authenticated health verified with the user-authorized existing `--yolo` flag. |
| External v1 HTTP helper from `../usix-termux` | Same bridge from that launch directory; [direct-only measurements](P0/direct-health-smokes.json) and [approved model/witness trace](P0/termux-model-health-trace.json). | Real model → existing `shell` approval → health response verified through the controlled Companion witness/helper. |
| USIX initial model chat (`-c`) | Earlier genuine daemon-backed turn advertised only web/search/question tools and rejected shell. | Historical failure before the final passing profile; not a current blanket limitation. |
| USIX initial `-c --model code --effort xhigh` | Earlier genuine `qwen3.8-flash-next` stream: `Bash`/`bash` returned `tool_not_available`; remote `fetch_url` blocked loopback/private IP. | Historical admission failure. Code-route selection alone does not grant tools. |
| USIX initial user-requested `--yolo` attempt | Earlier genuine tool rejection. [Redacted events](P0/usix-yolo-tool-events.json). | Historical failure. Approval skipping does not add a tool that the deployment has not admitted. |
| USIX standard code-route attempt | [Redacted events](P0/usix-tool-events.json). | Kept as a measured failure, not a compatibility pass. One initial request also returned HTTP 401 before existing authentication refreshed. |
| USIX earlier current-deployment continuation | [Default-permission turn](P0/usix-current-profile-tool-events.json): 19.962 s, normal exit, no health dispatch; 607 tool-definition tokens. [Exact-command rule attempt](P0/usix-xhigh-continuation-tool-events.json): 180 s limit without model/tool events. | Preserved unsuccessful attempts. A separate [CLI argument failure](P0/usix-continuation-tool-events.json) is not a model attempt. |
| USIX final current-deployment profile, existing `--yolo` | [Passing trace](P0/usix-yolo-model-health-trace.json): genuine `qwen3.8-flash-next`, exact `bash` start/result, one real authenticated health response in 414 ms, completed model turn in 10.878 s, 292 output / 13,062 prompt / 8,377 tool-definition tokens. | **Health gate passes.** User-authorized existing noninteractive flag; no alternate daemon, source patch, listener exposure or deployment-policy change by this evaluation. A [new run without that flag](P0/usix-model-health-trace.json) completed without a device witness. Interactive approval or explicit user authorization of the flag remains necessary for consequential shell execution. |
| USIX local operator Skill route | Read-only audit: script inventory is empty; only existing dotfiles skills remain. | No supported arbitrary script registration was found. Do not patch the registry or repurpose a dotfiles skill. |
| Termux default llama/Qwen3.5-2B-Q5_K_M | Genuine interactive runtime with the existing model; status-check prompts bounded at 240 and 900 seconds did not produce an approved tool call. | GPU platform unavailable in this environment; existing launcher fell back to CPU. These attempts do not pass the model/device gate. |
| Termux installed Hammer2.1-3B-Q4_K_M | Existing per-process `USIX_MODEL` configuration; genuine interactive evaluation also exhausted 900 seconds without approval or a health witness. [Attempt counts](P0/termux-model-attempts.json). | This model/launcher profile remains unverified. Evaluation-owned model servers were stopped; no persistent model setting or runtime source/catalog change. |
| Termux Qwen3.5-2B-Q5_K_M, tuned existing CPU server | [Genuine model/approval/health trace](P0/termux-model-health-trace.json). Unchanged runtime reuses port 8080; GPU layers 0, threads/batch threads 2, context 8,192, one slot, prediction limit 256. | **Health gate passes.** Three model requests, one denied malformed command, one exact-command approval, one real device request, matching final model report and normal TUI exit. Server was stopped afterward. Default GPU/launcher, UI/mail and other model profiles are not thereby validated. |
| Termux `shell` and Markdown skills | Source audit: `shell` is mutating and TUI prompts with exact arguments; one-shot automatically denies it. Installed Companion skill uses the existing loader. | Companion grants cannot suppress this separate gate. User approval must use the existing TUI path. |
| Termux native notifications/UI tools | Existing built-ins and authenticated v1 HTTP paths are present in the source. | Notification/screen contents were not sampled. Readiness/real workflow is not inferred from tool names. |
| Screen/type/tap/scroll | Source regressions pass; real health says accessibility disconnected. | Physical UI workflows not evaluated; enable accessibility and use a controlled fixture for P3. |
| Thunderbird mail | Existing open/compose implementation and five mail regressions; compose preserves `sent: false`. | Installed app/account version and actual draft/send workflow unverified. No mail was opened or sent. |
| Linux full USIX caller, remote WSS/MCP/broker | Source interfaces inspected; no usable Linux evaluation host or implemented v2 remote transport here. | Unverified profiles; WSS/v2 are P2 work. Do not widen the v1 loopback listener. |
| Termux task/resume/scheduler/worker | Read-only audit: current CLI has setup/doctor/pair/chat/one-shot, without task/worker commands. | Required future automation compatibility is a gap; do not add runtime code or silently drop its roadmap gate. |

## Privacy, latency and approval observations

Only health/status data and synthetic contract fixtures are retained. Private notification/mail/screen content, bearer tokens, global instruction content, account IDs and hidden model reasoning are excluded from permanent evidence. Remote model evaluation uses the runtime's existing configured deployment.

USIX's passing trace records one model-generated `bash` command, one matching successful tool result and a completed model turn. The authenticated health response took 414 ms; the overall runtime turn took 10.878 s. The existing `--yolo` flag was authorized by the user and skips interactive approval while static/surface/deployment gates remain enforced. The user demonstrated SMS access separately; no SMS content was recopied into evaluation artifacts. Earlier failed/no-dispatch traces are retained as history, without being substituted for the final real-device witness.

The standalone [capture utility](../../tools/capture_usix_baseline.py) uses the existing CLI and exact-command permission rule, with an explicit optional `--yolo` only when authorized by the user. It refuses to reuse an existing witness and requires a matching successful serial tool result plus a completed model turn before reporting success. A noninteractive model turn exiting with code 0 by itself is insufficient. The final tool-definition token count increased from the earlier 607 to 8,377; the exact server deployment profile/configuration change was not independently measured.

The successful Termux continuation processed its first prompt at 7.72 tokens/s (3,873 prompt tokens, 501.912 s), versus approximately 4 tokens/s in the prior eight-thread CPU-fallback log. That comparison includes different prior model/profile attempts and does not establish a controlled benchmark. Corrected-command generation used 183 prompt tokens / 24.056 s and 51 generated tokens / 9.298 s; final reporting used 140 prompt tokens / 15.423 s and 17 generated tokens / 2.618 s. There were three actual model requests, two approval prompts (one denial, one approval) and one authenticated health call taking 937 ms. Approval waiting time and setup are separate from those server timings. The final report matched `ok/auth/paired/listener: true` and `accessibility: false`. No device request occurred before exact-command approval. Direct helper connectivity remains separate smoke evidence.

A battery-status sample after build/model evaluation reported 11% charge, 37.8°C, unplugged/discharging, instantaneous current -886,874 µA and average -1,005,937 µA. There is no pre-run sample or isolated workflow energy measurement, so these values do not establish per-workflow battery cost. Release latency/thermal/approval budgets need controlled repeated measurements.

At the start of the continuation, a bounded read-only battery sample reported 79%, 37.3°C, AC connected and `NOT_CHARGING`. It is a separate observation, not a before/after workflow energy measurement. No thermal/battery acceptance claim is made from it.

## Exit decision and P1 handoff

All five P0 exit requirements pass: reproducible build/regression evidence, genuine model/device traces from both unchanged runtimes, an explicit interface/device/app matrix, validated schemas/failure cases and unchanged runtime source states. The roadmap records completion and the temporary chapter execution document has been deleted.

P1 can extract the pure domain/application modules, define ports, replace globals with injection and enforce core dependency boundaries while preserving existing v1 endpoints and protections. Carry the passing runtime profiles and permanent fixtures/evidence forward. Accessibility is still disconnected; default GPU/launcher performance, UI/mail workflows, remote v2/WSS/MCP transport and future task/scheduler facilities retain the limitations listed above. Their later chapter gates remain required.

The current APK still exposes v1. Contracts, test evidence and this baseline are permanent artifacts; they are not chapter cleanup targets.
