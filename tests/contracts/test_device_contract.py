from __future__ import annotations

import copy
import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from device_contract import Contracts, SCHEMAS, load_json, payload_hash, transition_issues


def example(name):
    return load_json((SCHEMAS / "examples" / (name + ".json")).read_bytes())


class DeviceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contracts = Contracts()

    def setUp(self):
        self.command = example("command-mail-send")
        self.state = example("trusted-state")

    def issues(self):
        return self.contracts.command_issues(self.command, self.state)

    def test_bound_command_and_duplicate_are_valid(self):
        self.assertEqual([], self.issues())
        self.state["actions"] = [copy.deepcopy(self.command)]
        self.command["requestId"] = "00000000-0000-4000-8000-000000000099"
        self.assertEqual([], self.issues())

    def test_workspace_and_requester_cannot_follow_new_cwd(self):
        for field in ("workspaceId", "deviceId", "runtimeId", "sessionId", "taskId", "taskRevision"):
            with self.subTest(field=field):
                old = self.command["context"][field]
                self.command["context"][field] = 2 if field == "taskRevision" else "00000000-0000-4000-8000-000000000099"
                self.assertIn("IdentityMismatch", self.issues())
                self.command["context"][field] = old

    def test_stale_and_replaced_controller_rejected(self):
        self.command["controllerLease"]["revision"] = 1
        self.assertIn("ControllerConflict", self.issues())
        self.command["controllerLease"]["revision"] = 2
        self.state["lease"]["expiresAt"] = self.state["now"]
        self.assertIn("ControllerConflict", self.issues())

    def test_payload_change_is_not_same_action_or_approval(self):
        self.state["actions"] = [copy.deepcopy(self.command)]
        self.command["payload"]["recipient"] = "other@example.invalid"
        self.assertIn("InvalidRequest", self.issues())
        self.command["payloadHash"] = payload_hash(self.command)
        self.assertIn("ActionConflict", self.issues())
        self.assertIn("ApprovalRequired", self.issues())

    def test_approval_revoked_expired_or_pending_does_not_authorize(self):
        record = self.state["authorities"][self.command["authority"]["ref"]]
        for status, code in (("pending", "ApprovalRequired"), ("denied", "ApprovalRequired"),
                             ("revoked", "AuthorityRevoked"), ("expired", "AuthorityExpired")):
            with self.subTest(status=status):
                record["status"] = status
                self.assertIn(code, self.issues())
        record["status"] = "approved"
        record["expiresAt"] = self.state["now"]
        self.assertIn("AuthorityExpired", self.issues())

    def test_grant_cannot_approve_send(self):
        self.command["authority"] = {"kind": "grant", "ref": example("grant")["authorityId"]}
        self.assertIn("ApprovalRequired", self.issues())

    def test_grant_scope_budget_and_operation_enforced(self):
        grant = example("grant")
        self.command["operation"] = "screen.observe"
        self.command["authority"] = {"kind": "grant", "ref": grant["authorityId"]}
        self.command["payload"] = {}
        self.command["payloadHash"] = payload_hash(self.command)
        self.state["capabilities"][0].update(operation="screen.observe", authority="grant")
        self.assertEqual([], self.issues())
        self.state["actionsUsed"] = grant["maxActions"]
        self.assertIn("ApprovalRequired", self.issues())
        self.state["actionsUsed"] = 0
        self.command["scope"]["resourceRefs"] = ["00000000-0000-4000-8000-000000000099"]
        self.command["payloadHash"] = payload_hash(self.command)
        self.assertIn("ApprovalRequired", self.issues())

    def test_stale_wrong_package_or_expired_snapshot_rejected(self):
        self.state["snapshot"]["packageId"] = "dev.other.fixture"
        self.assertIn("StaleSnapshot", self.issues())
        self.state["snapshot"]["packageId"] = self.command["scope"]["packageId"]
        self.state["snapshot"]["expiresAt"] = self.state["now"]
        self.assertIn("StaleSnapshot", self.issues())

    def test_deadline_and_cancellation_stop_dispatch(self):
        self.command["deadline"] = self.state["now"]
        self.assertIn("DeadlineExceeded", self.issues())
        self.command["cancellationId"] = self.command["actionId"]
        self.state["cancelled"] = [self.command["actionId"]]
        self.assertIn("Cancelled", self.issues())

    def test_supported_feature_is_not_readiness(self):
        self.state["capabilities"][0]["readiness"] = "permission_required"
        self.assertIn("PermissionRequired", self.issues())
        self.state["capabilities"][0]["supported"] = False
        self.assertIn("UnsupportedCapability", self.issues())

    def test_unknown_fields_missing_context_and_bad_dates_rejected(self):
        for change in (lambda c: c.update(cwd="/untrusted"),
                       lambda c: c.pop("context"),
                       lambda c: c.update(deadline="2026-02-30T12:00:00Z"),
                       lambda c: c.update(contractVersion="v999")):
            with self.subTest(change=change):
                value = copy.deepcopy(self.command)
                change(value)
                self.assertTrue(self.contracts.errors("command", value))

    def test_strict_json_rejects_ambiguous_or_unbounded_input(self):
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":1.0}',
                    b'{"x":9007199254740992}', b'{"x":"\\ud800"}',
                    b'[' * 34 + b'0' + b']' * 34, b' ' * 65537, b'\xff'):
            with self.subTest(raw=raw[:40]), self.assertRaises((ValueError, UnicodeError)):
                load_json(raw)

    def test_korean_hash_vector_and_order_independence(self):
        vector = example("payload-hash-vector")
        self.assertEqual(vector["sha256"], "sha256:" + hashlib.sha256(vector["canonicalUtf8"].encode()).hexdigest())
        self.assertEqual(vector["sha256"], payload_hash(self.command))
        self.command["payload"] = dict(reversed(list(self.command["payload"].items())))
        self.assertEqual(vector["sha256"], payload_hash(self.command))

    def test_unknown_effect_requires_reconciliation(self):
        receipt = example("receipt-unknown-effect")
        self.assertEqual([], self.contracts.errors("receipt", receipt))
        receipt["retry"]["decision"] = "safe"
        self.assertTrue(self.contracts.errors("receipt", receipt))

    def test_verification_requires_evidence_and_cancel_cannot_erase_effect(self):
        receipt = example("receipt-verified")
        receipt["evidence"] = []
        self.assertTrue(self.contracts.errors("receipt", receipt))
        receipt = example("receipt-unknown-effect")
        receipt["state"] = "Cancelled"
        self.assertTrue(self.contracts.errors("receipt", receipt))

    def test_reconciliation_allows_verification_not_reexecution(self):
        old, new = example("receipt-unknown-effect"), example("receipt-verified")
        self.assertEqual([], transition_issues(old, new, self.contracts))
        replay = copy.deepcopy(old)
        replay.update(state="Executing", revision=4, error=None)
        self.assertEqual(["InvalidTransition"], transition_issues(old, replay, self.contracts))
        self.assertEqual([], transition_issues(new, copy.deepcopy(new), self.contracts))

    def test_gap_explicitly_requires_resync(self):
        event = example("event-gap")
        self.assertEqual([], self.contracts.errors("event", event))
        event["payload"]["resyncRequired"] = False
        self.assertTrue(self.contracts.errors("event", event))

    def test_portable_failure_vectors(self):
        vectors = example("failure-vectors")
        for case in vectors["cases"]:
            with self.subTest(case=case["name"]):
                command, state = example("command-mail-send"), example("trusted-state")
                if case.get("priorAction"):
                    state["actions"] = [copy.deepcopy(command)]
                for value, changes in ((command, case.get("commandChanges", {})),
                                       (state, case.get("stateChanges", {}))):
                    for pointer, replacement in changes.items():
                        parts = pointer.lstrip("/").split("/")
                        parent = value
                        for part in parts[:-1]:
                            parent = parent[int(part)] if isinstance(parent, list) else parent[part]
                        key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
                        parent[key] = replacement
                if case.get("rehash"):
                    command["payloadHash"] = payload_hash(command)
                self.assertEqual(case["expected"], self.contracts.command_issues(command, state))


if __name__ == "__main__":
    unittest.main()
