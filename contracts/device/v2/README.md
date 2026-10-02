# Device v2 contract

Status: shared P0 contract with P2 APK/standalone CLI implementation. Companion 0.2.0 implements pairing, v2 negotiation, controller leases, durable receipts/outbox and outbound WSS. `tools/companion_http.py` remains a v1 caller. [Current capability and setup limits](../../../docs/device-integration.md) describe health and one-package app opening; P3/P4 add rich UI, canonical consequential approvals and verified outcomes. P2 device/CI acceptance is complete in [permanent evidence](../../../docs/evidence/P2-checkpoint.md), including both genuine runtime traces, physical loopback/WSS and reboot reconciliation without replay.

All schemas, clients and examples belong to Companion. Neither `../usix` nor `../usix-termux` is changed. Existing runtime authentication, tool admission, model policy and approvals apply in addition to device checks.

## Validation

Use Python 3.10 or newer in an isolated environment:

```sh
python3 -m venv .venv-contracts
.venv-contracts/bin/python -m pip install -r tools/requirements-contracts.txt
.venv-contracts/bin/python tools/check_contracts.py
.venv-contracts/bin/python -m unittest discover -s tests/contracts -v
```

The validators resolve the six schemas locally by their `urn:usix-companion:device:v2:*` identifiers; they never fetch schemas over the network. The schemas use [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12). Date/time format validation is explicitly enabled, including calendar validity. Unknown envelope fields are rejected. Extension capabilities need a versioned payload schema and server catalog entry before dispatch.

`tools/device_contract.py` is an offline conformance oracle with injected synthetic state and time. It does not consult live permissions, authenticate requests, grant authority, dispatch effects or implement persistence. Its accepted fixtures must not be used as live authority records. Production adapters must enforce these expectations against authenticated identity and canonical device state.

| Artifact | Defines |
| --- | --- |
| `types.schema.json` | Identity/context, scope, leases, authority references, JSON values, errors and evidence references. |
| `command.schema.json` | Bound request/action, operation/payload hash, deadline, cancellation and authority. |
| `receipt.schema.json` | Durable result revision, effect, evidence, cancellation and retry/reconciliation decision. |
| `capabilities.schema.json` | Supported operations, current readiness, required authority/controller/snapshot/account, negotiated limits and pagination. |
| `event.schema.json` | Monotonic cursor, stable event identity, receipt updates, readiness/controller changes and explicit gaps. |
| `authority.schema.json` | Canonical grant or exact approval record; binding, expiry, revision, resolution and budget. |

The examples are synthetic contract data. `mail.send`, the fixture package and account IDs do not advertise current APK support. `trusted-state.json` is test input only; `payload-hash-vector.json` pins the Korean UTF-8 canonical bytes and digest. The other seven examples are wire messages.

`failure-vectors.json` defines 14 portable cases: apply its JSON-pointer replacements to the named command/trusted-state examples; save the original command as a prior action when requested; recompute the hash only when `rehash` is true. Compare the sorted error-code list. Android/external-client implementations can reuse these vectors without changing either runtime.

## Captured context and identity

Every command carries device, runtime, session, task, task revision and workspace IDs. The trusted integration maps `workspaceId` to the captured absolute workspace; the wire does not accept a new `cwd` or an arbitrary filesystem path. Launching or resuming from another directory cannot replace this context. Account scope is an explicit reference or `null`, never an ambient default. IDs are lowercase canonical UUIDs; revisions/cursors are positive interoperable integers.

The authenticated requester must match the context. Receipt lookup has the same identity and privacy checks as action admission. An action is identified by `(deviceId, actionId)`; its context, operation, scope and payload hash are immutable. A different request ID can query the same attempt. An identical authorized duplicate returns its stored receipt; any changed binding returns `ActionConflict` before an adapter call. Recovery is not a new send.

UI operations require the current package, snapshot/reference generation and live resource checks. A controller-required capability needs the active lease ID/revision bound to the requesting runtime and not expired. Switching controllers invalidates earlier revisions. Grants cannot override a lease, Android permission, readiness or an existing runtime gate.

## Canonical payload hash

`payloadHash` is `sha256:` followed by the lowercase SHA-256 digest of the UTF-8 encoding of exactly:

```json
{"operation":"...","payload":{},"scope":{"accountRef":null,"packageId":null,"resourceRefs":[],"snapshotRef":null}}
```

The actual operation, payload and scope replace the example values. Sort object keys in ascending ASCII order recursively, omit whitespace, preserve array order, emit integers in ordinary base-10 form and emit `true`, `false` and `null` in lowercase. Payload keys match `[A-Za-z][A-Za-z0-9_]{0,63}`. Escape quotes/backslashes and JSON control characters (`\b`, `\t`, `\n`, `\f`, `\r`, remaining U+0000–001F as lowercase `\u00xx`). Emit other Unicode scalar values directly; do not normalize Unicode, escape slashes or replace line endings. Non-ASCII keys, lone surrogates, duplicate keys, floats/exponents, NaN/Infinity, integers outside ±9007199254740991 and nesting above 32 are rejected. Decimal quantities, when needed, use capability-defined decimal strings.

Request IDs, deadlines, lease revisions and authority references are not part of this hash. They are independently validated at every dispatch. An approval additionally binds context/task revision, action ID, operation, scope, hash and expiry. A changed recipient/body/package/account/snapshot therefore changes the digest or the immutable context; a matching digest alone is never authority.

Command bodies are capped at 64 KiB or the lower negotiated limit. Schemas also bound string, array and object sizes. Observation/media limits are separately negotiated; no response is silently treated as complete when pagination or resync is required.

## Authority and admission order

1. Bound/decode the request; reject unknown version, invalid fields and malformed/canonical-hash mismatches.
2. Authenticate the caller and match its captured context. Look up the server-owned operation catalog; client arguments cannot classify their own risk.
3. Check cancellation/deadline, supported capability, current readiness, account/resource/snapshot and controller lease.
4. Resolve canonical authority records. Routine grants are limited by context, operation, package/account/resource scope, expiry/revocation and a durable action budget. An approval-required effect needs the exact approved record; a grant never substitutes for it. Concurrent UI/CLI resolution uses compare-and-set on the authority revision.
5. Atomically check/store the immutable action record before dispatch. Recheck volatile readiness and authority immediately before the effect. Never charge or dispatch an identical duplicate again.
6. Invoke the adapter, record the actual outcome, and verify the bound goal through trusted evidence. Runtime approval remains a separate prerequisite.

The offline suite describes these checks with trusted fixture state. Persistence, concurrent action-budget/CAS enforcement and revocation between checks are P2/P4 responsibilities and require implementation tests before live use. Instructions from mail, OCR, tool output or a model cannot supply trusted state, authorize a record or replace an account/workspace.

## Receipts and reconciliation

| State | Effect | Permitted next state |
| --- | --- | --- |
| `Accepted` | `none` | `Executing`, `Cancelled`, or `Failed` before an effect. |
| `Executing` | `possible` | `Dispatched`, `UnknownEffect`, or `Failed`/`Cancelled` only with proof of no effect. |
| `Dispatched` | `dispatched` | `Verified` or `NeedsVerification`. |
| `UnknownEffect` | `possible` | `Verified` or `NeedsVerification` after observation. |
| `NeedsVerification` | `possible`/`dispatched` | `Verified` when matching evidence arrives. |
| `Verified` | `verified` | Immutable final result; return on duplicate. |
| `Failed` / `Cancelled` | `none` | Immutable final result; another attempt needs a new action ID and fresh admission. |

`Verified` requires at least one private, authorized evidence reference bound to the goal and action; a reference's syntax does not prove the evidence is valid. `provider_ack` proves only the declared provider-acknowledged goal, not recipient delivery. A compose intent remains a draft launch with `sent: false` on v1 and cannot become a verified send in v2.

Lost acknowledgement/process death while executing becomes `UnknownEffect`. `UnknownEffect` and `NeedsVerification` require `retry.decision: reconcile`; they cannot return `safe` or transition back to `Executing`. Look up the journal and observe first. Cancelling after possible/known dispatch sets `cancellationRequested` while retaining the effect state; it cannot report `Cancelled`/`none`. Successful cancellation before dispatch needs evidence that the adapter never applied the effect. Receipt revisions increase and timestamps cannot move backwards.

`retry.decision: safe` means a failure is known to have no effect, with fresh admission still required. It is not permission for a model/client to automatically replay a consequential operation. `never` returns a final receipt; `reconcile` requires an observation/status query.

## Events and failures

Cursor scope is the device event stream; `cursor` advances monotonically and `eventId` is stable across replay. Receivers persist acknowledgements and deduplicate before creating a task, including the configured trigger revision. A request older than the retained window yields `gap`, the oldest available cursor and `resyncRequired: true`. Resync establishes fresh observation/journal state before processing new triggers. It does not recreate historical sends. Live Android reply handles remain adapter-only and expire when notifications are replaced/removed.

Expected failures include identity/action conflict, stale lease/snapshot, unsupported/not-ready capabilities, exact approval mismatch, expired/revoked authority, deadline/cancellation, malformed input and uncertain effect. The conformance tests exercise those boundaries with injected time and no device side effects. Local/remote authentication, replay protection, journal/outbox migrations, races, reboot and real goal evidence remain required tests of the implementing phases.
