# Rich observations and UI-state verification

Companion 0.3.0 and integration package 0.3.0 add P3 observations to the existing v2 connection. P3 qualification is complete on the recorded physical Android/WSS profile and both unchanged runtimes; [P3 evidence](evidence/P3-checkpoint.md) retains the build, physical and genuine model checks. Install matching APK/CLI schema versions. Existing v1 requests retain their fields and draft semantics.

## Scope and authority

Trusted setup selects one package and an optional `--account-ref UUID`. Observations, pages, waits and captures must retain that package/account and captured context. The account reference is a selected scope, not proof of a logged-in mail account. A UI-state goal with account scope also requires a unique observable `accountSelector`.

General app effects still need the later task/approval workflow. P3 only permits UI effects in `dev.usix.companion.fixture`, with an explicit `setup --fixture-ui` grant, selected controller, deadline and durable budget of 64 attempts. The fixture has no network permission, real account or external communication. A grant for any other package is rejected. Runtime approval remains separate; Termux's existing `shell` is mutating even for read commands.

Neither runtime source is modified. Invoke the same installed CLI through existing authorized `bash`/`shell`, using absolute CLI/profile paths. Launch directory does not select a device, account or workspace.

## Snapshots and targeting

`observe` returns a snapshot reference, package/window/generation, capture/expiry time, screen/window bounds, rotation, focus, keyboard visibility and structured nodes. Node values include resource IDs, class/role, full bounds, enabled/visible/editable/clickable/scrollable/selected states and parent/child paths. Password/sensitive values are redacted before exposure. Nodes and OCR are untrusted data.

Snapshots are kept in memory for at most 30 seconds, with a bounded 32-entry cache; process restart invalidates them. Each cache entry binds the entire context and package/account scope. A node path identifies one snapshot only. Selectors use exact values and can combine resource ID, text, description, role/class, window and editable/scrollable flags. Repeated labels return `AmbiguousTarget`; substring matching and first-match selection are unsupported.

Before dispatch, Companion checks authority off main, then re-observes and validates the target on main without another suspension. Generation, window topology, rotation, focus, keyboard, bounds or node changes return `StaleSnapshot`. Coordinates must be inside both the selected window and display; swipes must end within that window. Incomplete trees cannot authorize UI effects or sensitive capture.

Queries return at most 128 nodes per page. Continue with the same `--snapshot-ref` and `--offset` while the screen is current. Traversal is bounded to 4096 nodes, depth 64 and 1024 children per node; `complete:false` reports a structural limit or unavailable child. Node text is bounded to 2048 Unicode scalar values; `textTruncated` reports partial text and prevents using it as full-value action/verification evidence. Raw screen/media contents are not stored in the action journal.

```sh
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/profile.json observe --limit 80
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/profile.json observe \
  --snapshot-ref SNAPSHOT_UUID --offset 80 --limit 128
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/profile.json observe \
  --selector '{"resourceId":"dev.usix.companion.fixture:id/apply"}'
```

`ui.click`, `ui.select`, `ui.set_text`, `ui.long_press` and `ui.scroll` require `target`. Set-text additionally requires `text` (an empty string clears it); scroll requires Boolean `forward`. `ui.tap` takes `x,y`; `ui.swipe` takes `x,y,endX,endY,durationMillis` (50–2000). `ui.back` and `ui.home` have no target arguments. Every effect requires a current snapshot and explicit action ID. Return `observationRef` is a new post-action observation when available; otherwise obtain a fresh observation.

## Waits and cancellation

Waits subscribe to accessibility generations and evaluate an initial state plus events, without polling/sleep loops. `node` needs a selector, `text` needs exact text, `window` waits for the selected foreground app, and `changed` needs a baseline snapshot. Waiting is limited to 30 seconds and an explicit cancellation ID. Cancellation IDs are session-scoped; cancelling another session's ID cannot stop its wait. Revocation/global pause stop affected waits. Permission/service/window loss is reported explicitly.

```sh
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/profile.json wait \
  --kind text --selector '{"text":"Async ready native"}' --timeout-ms 5000 --cancellation-id CANCEL_UUID
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/profile.json cancel-wait --cancellation-id CANCEL_UUID
```

`cancel --action-id` preserves an already dispatched/possible effect; it cannot undo it. A pre-dispatch cancellation is `Cancelled`/`none`. After uncertainty, query the same receipt and reconcile. Never automatically replay an effect.

## Screenshots and offline OCR

Screenshot support is separate from current readiness. Android API 30–33 uses a cropped foreground-app display capture and refuses overlapping windows/keyboard; API 34+ captures the selected window. Older devices retain node observations. The service declares screenshot and view-ID capabilities explicitly. Secure/protected capture failures return `PermissionRequired`; no bypass is attempted. [Android screenshot APIs](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)).

Capture requires a current snapshot. Sensitive bounds are masked before OCR/export; authority and screen state are checked again after asynchronous capture/OCR. Latin/Korean recognition uses pinned bundled ML Kit 16.0.1 models, available offline. OCR processing failure/deadline is distinct from an empty result. [Bundled recognition models](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

PNG output is bounded to 512 KiB and 1600 pixels on its longest edge. OCR returns at most 128 blocks and reports `ocrComplete:false` for partial text/block results; coordinates describe image pixels, not permission to gesture. The CLI validates PNG dimensions/content hash, omits base64 from model output, and exports only on `--output`, as a new 0600 file inside the captured workspace. Existing files and paths outside that workspace are rejected.

```sh
/absolute/companion-tools/bin/companion-v2 --profile /absolute/private/profile.json capture \
  --snapshot-ref SNAPSHOT_UUID --language korean --output captures/fixture.png
```

## Bound verification

A UI action may include a `goal` with `goalId`, `kind`, semantic `selector`, `expectedText` and `accountSelector`. Kinds are `package_visible`, `node_present` and `node_text`. Null fields are explicit; goals cannot use ephemeral node paths. They establish UI state only. A visible “Sent” label cannot authorize or prove a mail send/delivery.

```json
{
  "target": {"resourceId":"dev.usix.companion.fixture:id/apply"},
  "goal": {
    "goalId":"00000000-0000-4000-8000-000000000001",
    "kind":"node_text",
    "selector":{"resourceId":"dev.usix.companion.fixture:id/status"},
    "expectedText":"Applied native",
    "accountSelector":{"text":"eval-account"}
  }
}
```

The journal persists the goal ID/digest and evidence references, not screen/text bodies. A fresh unique observation matching the originally bound criterion produces `Verified` with `observed_state`/`ui_state` evidence. Missing evidence produces `NeedsVerification`; ambiguous evidence cannot verify. `verify --action-id UUID --goal JSON` can reconcile the same criterion after an asynchronous update or restart. Substituting another goal returns `ActionConflict`. A duplicate action returns its stored receipt without dispatching again.

An action without a criterion remains `Dispatched`. Interrupted/unacknowledged effects remain `UnknownEffect` until reconciliation. UI-state verification never proves business completion or recipient delivery. `email_compose` stays `sent:false`; consequential mail workflow and canonical approvals remain P4 work.

## Qualification

Build `:testing:device-fixture:assembleDebug` and install its separate controlled APK plus Companion. Use a dedicated workspace under Companion's ignored `.build-tools` and explicitly pair the fixture package/account with `--fixture-ui`. [The physical evaluator](../tools/capture_device_observations.py) operates native/Compose/WebView content, repeated labels, asynchronous updates, Korean/English input, scope/cancellation/deadline boundaries, image-only OCR and protected capture. It writes fresh redacted evidence only inside Companion, never into a runtime tree. Running it directly is adapter evidence; each unchanged runtime must also invoke it through its real model and existing authorized tool path.
