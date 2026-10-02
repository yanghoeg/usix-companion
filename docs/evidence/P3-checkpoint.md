# P3 observation and verification evidence

Started: 2026-10-02 UTC. **P3 is in progress; complete physical suites and both genuine runtime exits remain required.** Keep `docs/chapters/P3.md` until all exits pass. Unit tests and synthetic wire outputs cannot satisfy the genuine device/model gate.

## Current implementation

Companion owns rich immutable observations, exact semantic selectors, scoped ephemeral references, event waits/cancellation, stale/ambiguous rejection, controlled-fixture UI execution, screenshot masking/bundled OCR and criterion-bound UI-state evidence. General app task grants/consequential approvals and mail verification remain P4 work. [Permanent contract/usage](../observations.md).

Room version 3 preserves existing sessions/actions/outbox and adds selected account/fixture grant plus goal/observation/evidence references. No raw screens/media or full criteria text enter the journal. APK 0.3.0/code 8 retains the installed package/development signing identity. Both APK signatures verify against the existing certificate `1bf19c9316904bfea9df9f422af589a589dbdb425e4329a56bf473d79eca1ded`. The user confirmed both installations and the latest Companion update/accessibility/unlock; actual authenticated P3 routes and controlled UI-state receipts work. Delivered APK hashes are recorded separately from installed bytes, which cannot be read back here.

## Verified so far

| Check | Result and scope |
| --- | --- |
| Pure core application tests | 36 pass; nine new observation/authority/wait/verification boundary tests. SDK-independent `coreOnly` checks pass with invalid Android SDK paths. |
| Protocol tests | 13 pass, including exact selectors, Unicode bounds and rejection of business/ephemeral verification claims. |
| Transport/router tests | 17 pass; new snapshot, verified UI receipt and bounded visual schema outputs. Visual fixture is synthetic; this does not prove device screenshots/OCR. |
| Standalone integration | 19 pass; actual TLS broker and CLI tests, five new argument/account/export/hash/privacy cases. |
| Android adapters/control/composition | 33/2/1 pass; all assertions retained under the documented Termux provider profile. |
| Lint/signed builds/contracts | Pass. Nine schemas, nine actual Kotlin-router outputs, 6 architecture, 18 contract and 28 tooling tests pass. [Local build and APK hashes](P3/build-results.json). |
| Native Room migration/reopen | Five database/migration cases pass in [Linux CI 36961254575](https://github.com/yanghoeg/usix-companion/actions/runs/36961254575), revision `4c426ac89b7ccc0012f8eb00582523db61623a40`. Termux's native SQLite limitation remains. |
| Physical native/Compose/WebView, OCR/protected, cancellation | Native and separate Compose/WebView diagnostics establish controlled goal-specific UI verification, ambiguity/staleness and real events. Full suites remain pending; partial reports never pass the gate. |
| Both genuine unchanged runtime invocations | Pending. P2's consumed one-time USIX `--yolo` permission is not reused. |

## Frozen runtime targets

[Start capture](P3/runtime-before.json) records clean worktrees and full tracked-content/installed-binary hashes. USIX is `b7a9755386a4b5017da7d921516e3c21b67244c2`; Termux is `a678eea24e00d5852e1aaf9b460eea88fe68c508`. Source state must be compared after qualification; neither checkout may be written by Companion.

## Remaining acceptance

Publish actual signed build/native Room evidence, installed app/device profile, controlled physical and genuine model traces for both targets, local/WSS conformance and source-immutability comparison. Keep failed attempts with their real limitations. Only after all P3 exits pass, update the plan, delete the temporary execution document and print the P4 start instruction.

The final local APKs are staged at `/sdcard/Download/usix-companion-0.3.0-P3.apk` and `/sdcard/Download/usix-companion-fixture-P3.apk`. The Termux environment cannot execute `/system/bin/pm` and `adb devices` has no connected device. User-confirmed installation plus actual authenticated P3 responses establish functional installation. P3's model evaluator binds the exact admitted existing tool command to a fresh, complete physical fixture report and its SHA-256; ordinary model completion alone cannot pass.

The initial Linux run failed because migration tests used SQLite `DROP COLUMN`, unavailable in their SDK 28 native SQLite fixture. Tests now recreate the real exported version-2 tables before applying production migrations; no case is skipped and no production migration is replaced. The first genuine Termux trace exited normally but its helper failed importing Python dependencies before any device call. The fixed wrapper executes the isolated Companion interpreter without changing the runtime. The normal USIX profile completed its model turn without a tool call; that trace also fails qualification.
