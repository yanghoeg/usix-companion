# usix companion

The [agent development plan](docs/agent-development-plan.md) defines the hexagonal
refactor and the roadmap for a general assistant, including integration with both
USIX runtimes, reliable Android actions, durable tasks, and verification gates.
It describes planned work; the behavior documented below is the current implementation.

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

Update both the companion APK and `usix-termux` to use the mail and scrolling tools.
Pair them by copying the companion token and running `usix-termux pair` in Termux.
Requests bind to `127.0.0.1:8760`; every endpoint except `/health` requires the paired
`Authorization: Bearer <token>` header.

| Request | JSON body / behavior |
| --- | --- |
| `POST /email/open` | `{}` opens Thunderbird (`net.thunderbird.android`). Optional `package` selects another installed mail app. |
| `POST /email/compose` | `{"to":"person@example.com","subject":"Hello","body":"Message"}` opens a new message with these fields filled. Optional `package` selects the mail app. Success includes `"sent":false`; this never sends mail. |
| `GET /screen?package=net.thunderbird.android` | Reads the mail app's current accessibility tree, including empty editors and scroll containers. |
| `POST /scroll` | `{"direction":"down","package":"net.thunderbird.android"}` scrolls visible content. Direction is `down` or `up`; package is optional. `ok:false` means no movement was reported. |
| `POST /type` | `{"text":"Reply text","package":"net.thunderbird.android"}` fills the requested app's focused editor. Omitting package preserves the existing input behavior. |

The Termux tools are `email_open`, `email_compose`, `ui_dump`, `ui_scroll`,
`ui_tap`, `ui_tap_text`, and `ui_type`. For a reply, open the original mail and
use its Reply button to preserve the thread. Check the sender account, recipient,
and text before pressing Send; confirm the result in the mail app. Opening a
composer or typing a message does not prove it was sent.

This uses the account already configured in Thunderbird. It does not read its
private database or connect directly to the mail server. Accessible text and
controls depend on the installed mail app and current screen, so a live-device
check is needed for each supported app flow.

## Independent assistant and scheduled work

`usix-termux` owns the model loop, saved task checkpoints, schedules, and human
approvals. This companion supplies the Android controls used by those tasks.
With the task-enabled Termux agent, queue and inspect work with:

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

Build with JDK 17, Gradle 8.10.2, and Android SDK 34:

```sh
gradle testDebugUnitTest assembleDebug --no-daemon
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Current APK builds use the committed development keystore to preserve personal
sideload updates. Its key and passwords are public, so its signature does not
establish publisher authenticity. Controlled release signing and an installed-app
upgrade migration are included in the development plan.
