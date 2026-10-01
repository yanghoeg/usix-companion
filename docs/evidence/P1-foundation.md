# P1 — Hexagonal foundation acceptance evidence

P1 is complete on 2026-10-01. Its permanent acceptance sources are [the verification record](P1/verification.json), [retained regression manifest](P1/regression-cases.json), [intentional architecture failures](P1/architecture-probes.json), [runtime source observations](P1/runtime-source-state.json), [architecture guide](../architecture.md) and [build reproduction](../build.md). The temporary `docs/chapters/P1.md` execution document is deleted after these checks pass.

## Implementation

Companion now has separate domain/application, protocol, Android, persistence, transport, control UI and testing modules, assembled by the app's Hilt graph. Application code receives ports through constructors; no Android, JSON, DI, storage entity or live notification/accessibility handle crosses the core boundary. The control screen uses Compose, a ViewModel and lifecycle-aware `StateFlow` collection.

The legacy global `UiController`, `NotifStore`, `EmailController`, `BridgeAuth` and `BridgeServer` implementations are replaced by injected instances. HTTP routes decode/map/invoke/encode; application use cases own argument/scope validation, readiness guards and serialization. UI changes, app opening and composer opening share one mutex. Android calls use the injected main dispatcher. Gesture callbacks suspend with a bounded timeout; queued cancellation prevents dispatch. Notification reconnect, replacement, removal, cancelled intents and permission loss invalidate live reply authority.

The unversioned API preserves loopback binding, bearer admission before effect-body reads, bounded workers/headers/bodies/read deadlines, exact endpoint names, response fields, package guards and `sent: false` composer semantics. The installed preference name/key, package identity, minimum SDK and public development signing key remain compatible. A transport shutdown cancels requests and closes sockets; start can be retried. No v2, WSS, durable action receipt or verified send is claimed by P1.

## Exit checks

| Gate | Observed result |
| --- | --- |
| Core builds without Android SDK | `ANDROID_HOME` and `ANDROID_SDK_ROOT` point to `/nonexistent/p1-sdk`; `-PcoreOnly=true --rerun-tasks` freshly compiled domain/application/protocol/transport/fixtures and reran JVM tests. All 22 actionable tasks executed; build passed. |
| Architectural violations fail | Production source/API checks, declared project/library checks and resolved core compile/runtime allowlists passed. Three deliberately forbidden source/API probes and three actual Gradle project/library/transitive probes each exited 1 with the offending reference. |
| Existing bridge regressions | Every one of P0's 24 test method names executed successfully in `:adapters:android`. Original test hashes match the P0 record. No global field replacement or route reflection is used. |
| Application tests use fakes | Eleven pure application tests call inbound ports/use cases with `:testing:fixtures` fake device ports. Validation, independent readiness, serialized effects, queued/active cancellation and readiness loss are exercised. |
| Android composition and static checks | Generated Hilt graph test passed; all Android modules passed `lintDebug`, with zero errors. Release vital lint passed. Version-update, application-icon and data-extraction warnings remain recorded; no suppression/baseline was added. |
| Release artifact | `assembleRelease` passed. APK Signature Scheme v2 verified, and the certificate matches the unchanged development keystore blob. Application ID `dev.usix.companion`, version `0.1.6`/code 6, min/target SDK 24/34. APK was not installed for this evaluation. |
| Companion change boundary | Companion issued only read-only runtime inspections. USIX stayed at its P0 revision; both runtime worktrees were clean at the final observation. The externally advanced Termux revision is recorded below. |

The local Android profile is Termux aarch64, JDK 17.0.20, Gradle 8.10.2, AGP 8.5.2, Kotlin 1.9.24 and SDK 34. Robolectric fixtures use SDK 28 and P0's explicit `ConscryptMode.OFF` provider profile. Linux CI retains its default provider, adds a separate SDK-unavailable core job and runs the same architecture/Android test/lint gates. These local results do not claim a remote CI run or native TLS validation.

| Test area | Passed | Failures/errors/skips |
| --- | ---: | ---: |
| Pure application | 11 | 0 |
| Legacy wire codec | 6 | 0 |
| Real loopback socket adapter | 7 | 0 |
| Android adapters (including 24 baseline cases) | 31 | 0 |
| Installed credential storage/rotation | 3 | 0 |
| Control ViewModel | 2 | 0 |
| Generated production Hilt graph | 1 | 0 |
| **Kotlin/JVM/Android total** | **61** | **0** |
| Python architecture checker | 6 | 0 |
| Existing v2 contract tests | 18 | 0 |
| SDK preparation/runtime evidence tests | 8 | 0 |
| **Python total** | **32** | **0** |

The separate contract conformance command also passed all six schemas, seven wire examples and hash/context checks. These remain synthetic v2 contract checks, not an APK v2 endpoint implementation.

## Runtime revision observation

USIX retained `b7a9755386a4b5017da7d921516e3c21b67244c2`. During P1, the Termux checkout advanced outside Companion work from `f92c164c3a54f505391ed8d38c98445605641768` to `a678eea24e00d5852e1aaf9b460eea88fe68c508` (`Fix review findings and enforce hexagonal core boundaries`). It was not reverted, patched or built by this chapter.

A read-only comparison found `src/tools/bridge.rs`, `ui.rs`, `companion.rs` and `mod.rs` byte-identical to P0. `Shell` still declares `ApprovalClass::Mutating`; its change concerns bounded file reading and formatting. This supports the retained v1 source interface. It does not establish a genuine model/device workflow on the new harness revision: the P0 Termux trace remains evidence for its recorded old revision. P2 must capture the selected revisions/binaries again and evaluate its external integration through their existing authorized tools.

## P2 starting context

Use the existing application ports and composition root for v2 and compatible v1 calls. Add the needed identity/time/session/approval/journal/outbox ports with the actual P2 workflows, implement versioned codecs from the shared schemas, pairing/WSS, controller leases, durable receipts/reconciliation and Companion-owned host CLI/broker packaging. Existing runtime admission/approval policy remains authoritative. Runtime checkouts and launch directories are never connection lookup mechanisms.

Real accessibility remains unproven by this chapter; rich UI/mail workflows, protected content and business-goal verification keep their P3/P4 gates. The public v1 token preference/signing compatibility is preserved, not a Keystore or controlled publisher-key migration. The legacy API still lacks caller-supplied action IDs and durable deduplication; do not automatically retry an uncertain mutation.

No P2 implementation was started. Its next-start request is recorded in the development plan.
