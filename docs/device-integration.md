# Device v2 integration

Companion 0.3.0 implements the shared [device v2 contract](../contracts/device/v2/README.md) through authenticated loopback and a phone-initiated WSS channel. All CLI, broker, schemas and setup material live in this repository. Neither runtime needs a source/catalog/build change. Runtime shell/MCP admission and approval still apply.

## Current capabilities

`device.health` is read-only. `app.open` has a durable receipt and opens the one package explicitly selected during trusted setup. It requires an unexpired session, its bounded setup grant, an unlocked device, an installed launchable app and the selected controller lease. It reports `Dispatched`, never verified business completion. P3 adds [rich observations, waits, screenshot/OCR and controlled UI-state verification](observations.md). UI effects currently require the explicit fixture-only grant. General app authority and verified mail sends remain P4 work; capability readiness reports that boundary.

Legacy v1 health/observations/app opening/draft composing retain their request/response fields. Compose retains `sent: false`. While a v2 controller owns the device, legacy effects return 409. Unbound legacy UI mutations and notification replies return 403 `ApprovalRequired`; they lack the exact Companion authority required for consequential effects. Existing runtime approval alone cannot supply this authority. These routes are retained, with explicit errors and no new mandatory request fields. Legacy effects have no caller action ID, durable retry deduplication or verified completion; never automatically retry them.

## Standalone installation and trusted setup

Use Python 3.10+ on Linux/Termux. Install from an absolute source path into an integration-owned virtual environment; the installed CLI does not inspect a runtime checkout or inherit its caller's working directory.

```sh
python3 -m venv /absolute/companion-tools
/absolute/companion-tools/bin/pip install /absolute/usix-companion/integration
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/termux.json setup \
  --runtime usix-termux --workspace /absolute/workspace --package dev.usix.companion \
  --owner-token-file /absolute/private/companion_token
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/termux.json capabilities
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/termux.json acquire
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/termux.json open \
  --action-id 00000000-0000-4000-8000-000000000040
```

For USIX use `--runtime usix` in a separate profile; invoke this same installed CLI through its existing client `bash`. For Termux use the existing interactive `shell` approval path. `usix-termux -c` continues denying mutating shell calls. This package cannot add a tool, bypass a gate or make a missing runtime execution surface available. No optional MCP server is configured by P2.

Setup reads the owner token from a private file, consumes a single-use 60-second challenge, and saves a private 0600 profile. The device stores only the new session credential hash. Session/setup-grant lifetime is 30 minutes and budget is 16 accepted app-opening attempts; controller lifetime is at most 60 seconds and can be refreshed with `acquire`. Setup captures the absolute workspace and integration context; subsequent `cwd` changes cannot replace it. A missing/moved workspace requires explicit rebinding. The profile UUIDs are integration correlation IDs supplied by trusted setup, **not cryptographic runtime dispatch identity**. Workflows needing an unavailable runtime dispatch binding remain unsupported. Protect the profile using the owning OS account and existing runtime policy.

`execute --request-file /absolute/command.json` accepts bounded structured commands with the captured context and shared schema/hash validation. `receipt --action-id UUID`, `cancel --action-id UUID`, `events --cursor N`, `ack --cursor N`, `resync`, and `release` provide recovery/control. Effect calls require an explicit action ID. After an uncertain response, fetch that ID's receipt; never create another send attempt. Setup/acquire output omits credentials, which are never CLI arguments.

After session expiry, use `renew --owner-token-file /absolute/private/companion_token` on the same private profile through trusted user setup. Renewal consumes a fresh challenge, rotates the session bearer/grant and invalidates its controller lease. It preserves the exact context, selected package, stored receipts, delivered/acknowledged event cursors and remaining 16-action budget; it cannot rebind an existing session or silently replenish authority. Acquire a new lease before another effect. The owner can explicitly restore a revoked session this way.

## WSS broker and remote profile

The long-lived broker routes requests; it owns no model, business scheduler or runtime task/session database. Its TLS ingress accepts the configured device ID and device credential. Its local Unix socket is 0600, its directory must be 0700, and peer UID must match the owning user. The phone keeps its HTTP socket at `127.0.0.1:8760`.

Obtain a TLS certificate/key for the intended endpoint before configuring the broker. Use normal PKIX/hostname validation or explicitly trust and pin one certificate during local setup; hostname/validity checks still apply. Device credentials must be transferred only through a private user-controlled channel, never model text or public artifacts.

```sh
/absolute/companion-tools/bin/companion-broker init \
  --config /absolute/private/broker.json --phone-config /absolute/private/phone.json \
  --device-id DEVICE_UUID --certificate /absolute/private/server.pem \
  --key /absolute/private/server.key --host broker.example.com --listen 0.0.0.0 \
  --socket /absolute/private/ipc/companion.sock
/absolute/companion-tools/bin/companion-broker serve --config /absolute/private/broker.json
```

Install the generated phone settings with a local, owner-authenticated POST to `/v2/admin/remote` using [the setup helper](../tools/configure_companion_remote.py). It reads both secrets internally. The phone persists only an Android-Keystore-encrypted channel credential in DataStore and reconnects with bounded delay/ping/deadlines. Remote ingress cannot alter these local endpoint settings. Configure a host CLI profile using `setup --broker-socket /absolute/private/ipc/companion.sock --device-id DEVICE_UUID`, its explicit workspace and a privately supplied owner-token file. Existing calls thereafter use the paired session credential, not the owner token. A supervised restart preserves registration/configuration and the device journal; a disconnected broker never queues or blindly replays an effect.

The phone accepts strictly increasing per-connection sequence values and unique channel request IDs. Both ends bound frames, queues and calls. Switching the local controller invalidates the old lease revision; one controller mutates the phone across both transports. Read-only queries may coexist under their session context. The app exposes controller selection, pause and runtime revocation; revocation persists and prevents subsequent queries/actions.

## Durable results and events

One Room transaction joins the accepted action, immutable context/operation/scope/hash binding, grant budget and outbox entry. `Executing` is persisted before the external adapter. Identical duplicates return the stored receipt and never consume another budget/dispatch; altered bindings return `ActionConflict`. Receipt/event access is session/context-bound. Cancellation before dispatch records no effect; after execution starts it retains possible/dispatched effect and records cancellation requested.

On startup, previously `Executing` actions become `UnknownEffect`, and undispatched `Accepted` actions become `Cancelled`. Old leases are cleared; revisions keep increasing. Android effects and SQLite cannot commit together: this is durable uncertainty handling, not exactly-once delivery. P2 app-opening receipts remain dispatch-only. P3 can verify the exact originally bound UI-state criterion in the controlled fixture; mail/business verification remains P4 work. Reconciliation reads receipts and refreshes observations; it never replays effects. A dropped response after a stored `Dispatched` receipt returns that same receipt after restart.

The outbox retains 512 events with monotonic persisted cursors and stable event IDs. Pages are bounded to 128. A cursor outside retention returns `EventGap`/`resyncRequired`; `resync` returns the session's bounded receipts/current cursor without recreating effects. Acknowledgements cannot go backwards or exceed a delivered cursor and persist across restart. No notification reply handle or raw screen/message body enters this journal.

## Validation and limits

Run the shared schema/host tests, JVM/core/transport tests, Android persistence/unit/lint checks and real-device evaluation separately. [Build commands](build.md) cover these profiles. Termux aarch64 cannot run Robolectric 4.14.1's native SQLite runtime; the persistence tests remain mandatory on the unchanged Linux CI provider and real Android database. No test is skipped or replaced with fake persistence to obtain a pass. Local JVM TLS tests are separate from physical Android TLS/WSS verification. A direct CLI smoke or a synthetic TLS peer is not a genuine model/device trace.

P2 is complete. [Permanent acceptance evidence](evidence/P2-checkpoint.md) records the installed authenticated APK, all 30 live loopback and 30 native Android WSS checks, four cwd checks, passing genuine v2 traces from both unchanged runtimes and all 27 physical reboot recovery checks. The USIX passing profile used the existing `--yolo` with explicit user permission for one read; its default-profile limitation remains recorded, and this is no ongoing bypass authorization. [The recovery evaluator](../tools/capture_device_v2_reboot.py) verified a changed kernel boot ID, four unchanged receipts, stable events/acknowledgement cursors, restored WSS settings and old-controller rejection without replay. The temporary P2 execution document is deleted. Full Linux-runtime/remote-network workflows, Doze operations, UI/mail verification and broader release repetition remain explicitly bounded by the roadmap matrix and later gates.

P3 is complete in [permanent acceptance evidence](evidence/P3-checkpoint.md). The real USIX model uses its existing default admission and exact `allowed-tools` rule from the Companion directory, without `--yolo`, and passes all 83 controlled checks. The unchanged Termux TUI/model uses one exact approved existing shell and passes all 83 checks with a matching evidence hash. Native/Compose/WebView UI states, stale/ambiguous targets, event waits/cancellation, Latin/Korean image OCR, protected capture and rotation are qualified on Samsung SM-F956N / Android 16 / API 36 through the native Android WSS channel and same-phone broker. Four installed CLI cwd checks retain captured context/workspace/account. Both runtime sources and installed binaries match the pinned current validation baseline; the user's earlier separate USIX update is recorded. UI/OCR qualification requires the fixture foreground and an unlocked device. P3.2's fixture retains the foreground screen; it does not bypass explicit locks or keep the screen on in the background. P4 must add broader task grants, canonical approvals and the mail workflow. External Linux/network deployment and durable background operation retain their later gates.
