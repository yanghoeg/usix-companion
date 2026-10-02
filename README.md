# usix companion

The [agent development plan](docs/agent-development-plan.md) defines the hexagonal
refactor and the roadmap for a general assistant, including integration with both
USIX runtimes, reliable Android actions, durable tasks, and verification gates.
It describes planned work; the behavior documented below is the current implementation.

Development is confined to this Companion repository. Both `../usix` and
`../usix-termux` must be usable without changing their code. The Companion-owned host CLI/broker,
optional external MCP integration, and skill/configuration examples call their existing
authorized interfaces; they do not add built-in runtime tools or change approval policy.

The current [USIX usage instructions](USIX.md) and lightweight
[HTTP caller](tools/companion_http.py) support existing runtime shell tools.
Install the caller at `~/.usix/companion_http.py`; the global USIX.md instructions
and [Termux skill](skills/usix_companion.md) use that path from any launch directory.
This caller uses the legacy HTTP endpoints. The separate [installable v2 CLI and WSS broker](docs/device-integration.md)
use captured profiles, controller leases, durable receipts and bounded events.
The deployed session must actually admit its existing shell/MCP tool. The
[P0 compatibility evidence](docs/evidence/P0-baseline.md) verifies genuine model →
authenticated device-health calls through both unchanged runtimes: full USIX with
its existing user-authorized `--yolo` flag, and `usix-termux` with exact-command
TUI approval and a configured CPU model server. Noninteractive shell approvals
need an existing permitted path; `--yolo` does not add a missing tool. P0 is complete
and [P1's hexagonal foundation](docs/evidence/P1-foundation.md) is complete. The
[architecture guide](docs/architecture.md) describes the pure Kotlin core, injected
adapters, Hilt composition, Compose setup UI and enforced dependency boundaries.
[P2 device execution acceptance](docs/evidence/P2-checkpoint.md) is complete: the GitHub-built APK is installed,
both unchanged runtimes have genuine v2 model/device traces, all local/WSS scenarios pass,
and physical reboot preserves receipts/events while rejecting stale controllers without replay.
[P3 rich observations and controlled UI-state verification](docs/observations.md) are complete: both unchanged runtimes pass their genuine model/device suites. [Acceptance evidence](docs/evidence/P3-checkpoint.md). General app authority and mail verification remain P4 work.

For mail lookup, use the [IMAP caller](tools/companion_mail.py) without switching
apps. It reads inbox headers and selected message bodies directly from the mail server
over verified TLS, using read-only selection and `BODY.PEEK` to preserve unread flags.
Mail lookup never falls back to opening Thunderbird or another app. An empty notification
list or accessibility tree does not establish that the mailbox has no new mail.

Install `tools/companion_mail.py` at `~/.usix/companion_mail.py`, then run setup in
your own terminal with the account and its verified IMAP hostname:

```sh
python3 "$HOME/.usix/companion_mail.py" setup --email person@example.com --host imap.example.com
python3 "$HOME/.usix/companion_mail.py" check
python3 "$HOME/.usix/companion_mail.py" inbox '{"limit":20}'
python3 "$HOME/.usix/companion_mail.py" read '{"uid":"42","uidvalidity":"123"}'
```

Setup prompts privately for the IMAP password, verifies inbox access, and creates
`~/.usix/mail_account.json` with mode 600. It does not overwrite an existing account.
Use the UID and UID validity returned by your actual inbox listing; the numbers above
are examples. `status` reports local configuration, while `check` verifies authentication.
Listings support `unread:true` and pagination through `before_uid`/`next_before_uid`;
each page contains up to 100 headers. Body reads decode MIME plain text or convert HTML
to text without loading remote resources. Large messages fetch text MIME sections
separately so attachment size does not block body reads; text reads have a 10 MiB
limit. Attachments return names/types. Disappeared/unavailable search entries are
reported with `complete:false` and `unavailable_uids`, preserving readable entries.
Unsupported/encrypted
bodies and missing authentication remain explicit incomplete results. This helper
uses Python's standard library and the existing runtime shell, independently of the
APK, accessibility service and notification listener.

For Bizmeka accounts, the official server is `ezmail.bizmeka.com`; its POP3/IMAP
password is configured separately from the webmail password in webmail settings.
Company external-mail access must already be enabled. See the
[official mail settings](https://ezportal.bizmeka.com/help/ko/gw-docs/PRO_000178.html).

Android bridge for the Termux agent. When explicitly requested, Thunderbird mail and conversation screens can be
read and used without a notification. Sign in to the mail account in Thunderbird,
unlock the phone, and enable the companion's accessibility service. Notification
access is only needed for the notification tools.

Install the companion APK and use an existing compatible `usix-termux` build.
Pair them by copying the companion token and running `usix-termux pair` in Termux.
Requests bind to `127.0.0.1:8760`; every endpoint except `/health` requires the paired
`Authorization: Bearer <token>` header.

| Request | JSON body / behavior |
| --- | --- |
| `POST /email/open` | `{}` opens Thunderbird (`net.thunderbird.android`). Optional `package` selects another installed mail app. |
| `POST /email/compose` | `{"to":"person@example.com","subject":"Hello","body":"Message"}` opens a new message with these fields filled. Optional `package` selects the mail app. Success includes `"sent":false`; this never sends mail. |
| `GET /screen?package=net.thunderbird.android` | Reads the mail app's current accessibility tree, including empty editors and scroll containers. |
| `POST /scroll` | Legacy shape remains `{"direction":"down","package":"net.thunderbird.android"}`. P2 rejects unbound UI mutation with 403 `ApprovalRequired`. |
| `POST /type` | Legacy shape remains `{"text":"Reply text","package":"net.thunderbird.android"}`. P2 rejects unbound UI mutation with 403 `ApprovalRequired`. |

Native tool availability depends on the unchanged `usix-termux` version. Existing
UI tools include `ui_dump`, `ui_tap`, `ui_tap_text`, and `ui_type`; a build without
native mail or scrolling tools can call the HTTP endpoints above through its existing
approval-gated `shell`, without adding runtime code. P2 additionally rejects legacy
tap/type/back/scroll/reply without argument-bound Companion authority; the later
approved v2 UI/mail workflow supplies that context. All legacy effects are blocked
while a v2 controller is active. Opening a composer does not prove it was sent.

This uses the account already configured in Thunderbird. It does not read its
private database or connect directly to the mail server. Accessible text and
controls depend on the installed mail app and current screen, so a live-device
check is needed for each supported app flow.

## Independent assistant and scheduled work

The selected runtime owns the model loop and its approval policy. This companion
supplies Android controls through existing authorized tools. Saved tasks, schedules
and workers are available only in unchanged runtime builds that already expose them;
the audited `../usix-termux` checkout does not currently expose `task` or `worker`
commands. The development plan records that compatibility gap without adding runtime
code. The following examples apply only to an existing task-enabled build:

```sh
usix-termux task add "Check my battery and summarize recent notifications"
usix-termux worker --once
usix-termux task list
usix-termux task run 1  # use the returned task ID to resume with human approvals
```

`usix-termux task schedule --every 1h "Check my battery"` creates a repeat schedule;
`usix-termux worker` must remain running to process it. Phone-changing actions pause
for explicit approval. Android may suspend Termux, so execution time is best-effort.
See the [Termux task guide](https://github.com/yanghoeg/usix-termux/blob/main/docs/tasks.md)
for scheduling, cancellation, and recovery behavior.

Build with JDK 17 and Android SDK 34 using the checksum-pinned Gradle 8.10.2 wrapper:

```sh
./gradlew checkArchitecture :core:application:test :protocol:test :adapters:transport:test \
  testDebugUnitTest assembleDebug --no-daemon
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

See the [build guide](docs/build.md) for the audited version tuple, verified SDK
preparation and explicit Termux test profile. The [device v2 contract](contracts/device/v2/README.md)
contains schemas, synthetic examples and executable conformance checks; it is a
contract baseline for planned implementation, not an endpoint supported by the current APK.

Current APK builds use the committed development keystore to preserve personal
sideload updates. Its key and passwords are public, so its signature does not
establish publisher authenticity. Controlled release signing and an installed-app
upgrade migration are included in the development plan.
