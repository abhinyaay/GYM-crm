"""Unit tests for scripts/gate_bridge.py (stdlib unittest, no external network).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
The last test class starts a fake GymOps gate server on 127.0.0.1 (loopback only) that verifies signatures the same
way GateAuthenticator / GateSignature do.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import hmac
import http.server
import io
import json
import logging
import os
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

import gate_bridge as gb  # noqa: E402
import gate_simulator as sim  # noqa: E402

SECRET = "a" * 64
logging.disable(logging.CRITICAL)      # the bridge logs every decision; keep the test output readable
UTC = dt.timezone.utc


def ist(y, mo, d, h, mi) -> dt.datetime:
    """A wall-clock time in India (UTC+05:30) as an aware UTC datetime."""
    return dt.datetime(y, mo, d, h, mi, tzinfo=dt.timezone(dt.timedelta(hours=5, minutes=30))).astimezone(UTC)


def card_hash(uid: str) -> str:
    return hashlib.sha256(f"RFID_CARD:{uid}".encode()).hexdigest()


def sample_allowlist(**over) -> dict:
    data = {
        "deviceId": "gd_test", "branchId": 1, "branchCode": "BLR", "timezone": "Asia/Kolkata",
        "generatedAt": "2026-10-11T00:00:00Z", "validUntil": "2026-10-14T00:00:00.123456789Z",
        "openingHours": [
            {"dayOfWeek": 1, "openTime": "06:00:00", "closeTime": "22:00:00", "closed": False},   # Monday
            {"dayOfWeek": 3, "openTime": [6, 0], "closeTime": [22, 0, 0], "closed": False},      # Jackson array form
            {"dayOfWeek": 7, "openTime": None, "closeTime": None, "closed": True},               # Sunday
        ],
        "entries": [
            {"credentialType": "RFID_CARD", "credentialHash": card_hash("D300002A"), "memberRef": "M0042",
             "displayName": "Priya S.", "validFrom": "2026-10-11T00:00:00Z", "validUntil": "2026-10-14T00:00:00Z",
             "dailyAccessStart": None, "dailyAccessEnd": None},
            {"credentialType": "FINGERPRINT",
             "credentialHash": hashlib.sha256(b"TEMPLATE:FINGERPRINT:FP-DEMO-7").hexdigest(),
             "memberRef": "M0007", "displayName": "Off P.", "validFrom": "2026-10-11T00:00:00Z",
             "validUntil": "2026-10-14T00:00:00Z", "dailyAccessStart": "06:00:00", "dailyAccessEnd": "11:00:00"},
            {"credentialType": "RFID_CARD", "credentialHash": card_hash("EXPIRED1"), "memberRef": "M0099",
             "displayName": "Old M.", "validFrom": "2026-10-11T00:00:00Z", "validUntil": "2026-10-12T00:00:00Z",
             "dailyAccessStart": None, "dailyAccessEnd": None},
        ],
        "notes": "", "exitMode": "BUTTON",
    }
    data.update(over)
    return data


def make_config(tmp: str, **over) -> dict:
    raw = {"server_url": "http://gate.test", "device_id": "gd_test", "state_dir": tmp, "inputs": [],
           "relay": {"type": "none"}, "deny_signal": {"type": "none"}, **over}
    path = Path(tmp) / "cfg.json"
    path.write_text(json.dumps(raw))
    return gb.load_config(str(path), environ={"GATE_DEVICE_SECRET": SECRET})


class FakeClient:
    """Stands in for GateClient: records calls, answers from a handler, or raises ServerUnavailable."""

    def __init__(self, handler=None) -> None:
        self.calls: list[tuple[str, str, object]] = []
        self.handler = handler
        self.path_prefix = ""
        self.base = "http://gate.test"
        self.timeout = 1.0

    def call(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if self.handler is None:
            raise gb.ServerUnavailable("down")
        status, payload = self.handler(method, path, body)
        raw = json.dumps(payload).encode()
        return gb.Response(status, payload, raw, 200 <= status < 300)


# ---------------------------------------------------------------------------------------------------------- signing

class SigningTest(unittest.TestCase):
    BODY = b'{"credentialType":"RFID_CARD"}'
    PATH = "/api/v1/access/gate/events"

    def test_canonical_string_matches_the_java_format(self):
        # same vector as GateSignatureTest.canonicalStringCoversMethodPathTimestampNonceAndBodyHash
        c = gb.canonical("post", self.PATH, "1760000000", "nonce-0123456789abcdef", self.BODY)
        self.assertEqual(c, "POST|" + self.PATH + "|1760000000|nonce-0123456789abcdef|"
                         + hashlib.sha256(self.BODY).hexdigest())
        self.assertTrue(gb.canonical("GET", self.PATH, "1", "n", b"")
                        .endswith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))

    def test_signature_is_hmac_sha256_hex_of_the_canonical_string_keyed_by_the_secret_text(self):
        sig = gb.sign_request(SECRET, "POST", self.PATH, "1760000000", "nonce-0123456789abcdef", self.BODY)
        expected = hmac.new(SECRET.encode(), gb.canonical("POST", self.PATH, "1760000000", "nonce-0123456789abcdef",
                                                          self.BODY).encode(), hashlib.sha256).hexdigest()
        self.assertEqual(sig, expected)
        self.assertEqual(len(sig), 64)
        self.assertNotEqual(sig, gb.sign_request(SECRET, "POST", self.PATH, "1760000001", "nonce-0123456789abcdef",
                                                 self.BODY))

    def test_requests_are_signed_exactly_like_gate_simulator(self):
        body = {"deviceEventId": "cli-1", "credentialType": "RFID_CARD", "credentialValue": "D300002A"}
        captured = {}
        payload = b'{"decision":"GRANTED"}'

        class FakeResponse(io.BytesIO):
            status = 200

            def __init__(self, nonce):
                super().__init__(payload)
                self.headers = {"X-Signature": gb.sign_response(SECRET, nonce, payload)}

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def fake_urlopen(req, timeout=None):
            captured["req"] = req
            return FakeResponse(req.get_header("X-nonce"))

        gate = sim.Gate("http://localhost", "gd_test", SECRET)
        with mock.patch.object(sim.urllib.request, "urlopen", fake_urlopen), \
                mock.patch.object(sim.time, "time", return_value=1760000000.5), \
                mock.patch.object(sim.secrets, "token_hex", return_value="0123456789abcdef0123456789abcdef"):
            status, parsed, verified = gate.request("POST", self.PATH, body)
        self.assertTrue(verified, "simulator must accept a response signed by gate_bridge.sign_response")
        req = captured["req"]
        self.assertEqual(req.data, gb.encode_body(body), "same compact JSON bytes")
        expected = gb.signed_headers("gd_test", SECRET, "POST", self.PATH, gb.encode_body(body),
                                     timestamp=1760000000, nonce="0123456789abcdef0123456789abcdef")
        self.assertEqual(req.get_header("X-signature"), expected["X-Signature"])
        self.assertEqual(req.get_header("X-timestamp"), "1760000000")
        self.assertEqual(req.get_header("X-device-id"), "gd_test")

    def test_response_verification_rejects_tampering(self):
        raw = b'{"decision":"GRANTED","openGate":true}'
        sig = gb.sign_response(SECRET, "nonce-aaaaaaaaaaaaaaaa", raw)
        self.assertEqual(sig, hmac.new(SECRET.encode(), b"response|nonce-aaaaaaaaaaaaaaaa|"
                                       + hashlib.sha256(raw).hexdigest().encode(), hashlib.sha256).hexdigest())
        self.assertTrue(gb.verify_response(SECRET, "nonce-aaaaaaaaaaaaaaaa", raw, sig.upper()))
        self.assertFalse(gb.verify_response(SECRET, "nonce-bbbbbbbbbbbbbbbb", raw, sig))
        self.assertFalse(gb.verify_response(SECRET, "nonce-aaaaaaaaaaaaaaaa", raw.replace(b"GRANTED", b"DENIED"), sig))
        self.assertFalse(gb.verify_response(SECRET, "nonce-aaaaaaaaaaaaaaaa", raw, None))

    def test_signed_path_includes_a_server_url_path_prefix(self):
        client = gb.GateClient("https://gym.example.com/gymops/", "gd_test", SECRET)
        self.assertEqual(client.path_prefix, "/gymops")
        self.assertEqual(client.base, "https://gym.example.com/gymops")


# ------------------------------------------------------------------------------------------------- credential hashes

class CredentialTest(unittest.TestCase):
    def test_card_uid_normalisation_matches_credential_hashes(self):
        self.assertEqual(gb.normalize_card_uid(" d3:00-00 2a "), "D300002A")
        self.assertIsNone(gb.normalize_card_uid("abc"))           # too short
        self.assertIsNone(gb.normalize_card_uid("D3/00"))
        self.assertEqual(gb.credential_hash("RFID_CARD", "d3:00:00:2a"), card_hash("D300002A"))

    def test_template_hash(self):
        self.assertEqual(gb.credential_hash("FACE", " ZK-12 "),
                         hashlib.sha256(b"TEMPLATE:FACE:ZK-12").hexdigest())
        self.assertIsNone(gb.credential_hash("FINGERPRINT", "has space"))
        self.assertIsNone(gb.credential_hash("QR", "GQ1.x"))

    def test_decimal_card_numbers_become_hex(self):
        self.assertEqual(gb.decimal_card_to_hex("0003456789"), "0034BF15")
        self.assertEqual(gb.decimal_card_to_hex("0003456789", reverse_bytes=True), "15BF3400")
        self.assertEqual(gb.decimal_card_to_hex("4294967295"), "FFFFFFFF")


# ---------------------------------------------------------------------------------------------------- input parsing

class KeyboardParsingTest(unittest.TestCase):
    KB = dict(gb.DEFAULT_CONFIG["keyboard"])

    def parse(self, line, **over):
        return gb.parse_keyboard_line(line, {**self.KB, **over})

    def test_card_qr_and_templates(self):
        a = self.parse("d300002a\r\n")
        self.assertEqual((a.kind, a.credential_type, a.value), ("scan", "RFID_CARD", "D300002A"))
        qr = "GQ1.16.2bdq9m.AAAAAAAAAAAAAAAAAAAAAA"
        a = self.parse(qr + "\n")
        self.assertEqual((a.kind, a.credential_type, a.value), ("scan", "QR", qr))
        a = self.parse("FP:ZK-UID-4-F1")
        self.assertEqual((a.credential_type, a.value), ("FINGERPRINT", "ZK-UID-4-F1"))
        a = self.parse("face:abc.1")
        self.assertEqual((a.credential_type, a.value), ("FACE", "abc.1"))
        self.assertEqual(self.parse("\x02D300002A\x03").value, "D300002A")   # STX/ETX framing

    def test_decimal_reader_and_byte_order(self):
        a = self.parse("0003456789", card_format="decimal")
        self.assertEqual(a.value, "0034BF15")
        self.assertEqual(self.parse("x123", card_format="decimal").kind, "ignore")
        self.assertEqual(self.parse("0034BF15", reverse_card_bytes=True).value, "15BF3400")

    def test_exit_and_operator_commands(self):
        self.assertEqual(self.parse("EXIT").kind, "exit_button")
        self.assertEqual(self.parse("exit").kind, "exit_button")
        self.assertEqual(self.parse("!exit").kind, "exit_button")
        self.assertEqual(self.parse("!status").kind, "status")
        a = self.parse("!ack 17 FP-ABC123")
        self.assertEqual((a.kind, a.command_id, a.template_ref), ("ack", 17, "FP-ABC123"))
        a = self.parse("!fail 18 finger not read")
        self.assertEqual((a.kind, a.command_id, a.message), ("fail", 18, "finger not read"))
        self.assertEqual(self.parse("!ack x").kind, "ignore")
        self.assertEqual(self.parse("").kind, "ignore")
        self.assertEqual(self.parse("hello world").kind, "ignore")


# ------------------------------------------------------------------------------------------------ offline decisions

class AllowlistDecisionTest(unittest.TestCase):
    def setUp(self):
        self.al = gb.Allowlist(sample_allowlist())

    def decide(self, ctype, value, when, direction="ENTRY", **kw):
        return self.al.decide(ctype, value, direction, when, **kw)

    def test_parses_jackson_instants_with_nanoseconds(self):
        self.assertEqual(gb.parse_instant("2026-10-14T00:00:00.123456789Z"),
                         dt.datetime(2026, 10, 14, 0, 0, 0, 123456, tzinfo=UTC))
        self.assertEqual(gb.parse_instant("2026-10-14T05:30:00+05:30"), dt.datetime(2026, 10, 14, tzinfo=UTC))
        self.assertEqual(gb.parse_instant(1760000000), dt.datetime.fromtimestamp(1760000000, UTC))
        self.assertIsNone(gb.parse_instant("yesterday"))

    def test_grants_a_known_card_during_opening_hours_in_the_branch_time_zone(self):
        monday_7am = ist(2026, 10, 12, 7, 0)            # 2026-10-12 is a Monday; 01:30 UTC
        d = self.decide("RFID_CARD", "d3:00:00:2a", monday_7am)
        self.assertTrue(d.granted)
        self.assertEqual((d.reason, d.member_ref, d.display_name), ("OK", "M0042", "Priya S."))

    def test_time_zone_is_the_branch_zone_not_utc(self):
        monday_7am = ist(2026, 10, 12, 7, 0)
        utc_list = gb.Allowlist(sample_allowlist(timezone="UTC"))   # 01:30 in UTC: before 06:00 opening
        self.assertEqual(utc_list.decide("RFID_CARD", "D300002A", "ENTRY", monday_7am).reason, "BRANCH_CLOSED")

    def test_opening_hours(self):
        self.assertEqual(self.decide("RFID_CARD", "D300002A", ist(2026, 10, 12, 5, 59)).reason, "BRANCH_CLOSED")
        self.assertEqual(self.decide("RFID_CARD", "D300002A", ist(2026, 10, 12, 22, 0)).reason, "BRANCH_CLOSED")
        self.assertTrue(self.decide("RFID_CARD", "D300002A", ist(2026, 10, 12, 21, 59)).granted)
        self.assertEqual(self.decide("RFID_CARD", "D300002A", ist(2026, 10, 11, 9, 0)).reason, "BRANCH_CLOSED")  # Sun
        self.assertEqual(self.decide("RFID_CARD", "D300002A", ist(2026, 10, 13, 9, 0)).reason, "BRANCH_CLOSED")  # Tue
        open_24x7 = gb.Allowlist(sample_allowlist(openingHours=[]))
        self.assertTrue(open_24x7.decide("RFID_CARD", "D300002A", "ENTRY", ist(2026, 10, 13, 3, 0)).granted)

    def test_wednesday_array_form_times(self):
        wednesday = dt.datetime(2026, 10, 14, 6, 0)          # Jackson may write LocalTime as [6, 0]
        self.assertTrue(self.al.branch_open(wednesday))
        self.assertFalse(self.al.branch_open(wednesday.replace(hour=5, minute=59)))
        self.assertFalse(self.al.branch_open(wednesday.replace(hour=22)))

    def test_plan_daily_access_window(self):
        self.assertTrue(self.decide("FINGERPRINT", "FP-DEMO-7", ist(2026, 10, 12, 10, 59)).granted)
        d = self.decide("FINGERPRINT", "FP-DEMO-7", ist(2026, 10, 12, 11, 0))
        self.assertEqual(d.reason, "OUTSIDE_PLAN_HOURS")

    def test_validity_windows(self):
        self.assertEqual(self.decide("RFID_CARD", "EXPIRED1", ist(2026, 10, 12, 9, 0)).reason, "NO_ACTIVE_MEMBERSHIP")
        late = dt.datetime(2026, 10, 14, 0, 0, 1, tzinfo=UTC)
        self.assertEqual(self.decide("RFID_CARD", "D300002A", late).reason, "OFFLINE_DENIED")   # list expired

    def test_unknown_qr_and_exit(self):
        when = ist(2026, 10, 12, 9, 0)
        self.assertEqual(self.decide("RFID_CARD", "FFFF0000", when).reason, "UNKNOWN_CREDENTIAL")
        self.assertEqual(self.decide("QR", "GQ1.a.b.c", when).reason, "OFFLINE_DENIED")
        self.assertTrue(self.decide("RFID_CARD", "FFFF0000", when, "EXIT").granted)
        self.assertFalse(self.decide("RFID_CARD", "FFFF0000", when, "EXIT", allow_unknown_exit=False).granted)
        # exits are allowed even when the branch is closed / the plan window is over
        self.assertTrue(self.decide("FINGERPRINT", "FP-DEMO-7", ist(2026, 10, 11, 23, 0), "EXIT").granted)

    def test_daily_entry_limit_only_when_the_server_sends_it(self):
        data = sample_allowlist()
        data["entries"][0]["maxEntriesPerDay"] = 1
        al = gb.Allowlist(data)
        when = ist(2026, 10, 12, 9, 0)
        self.assertTrue(al.decide("RFID_CARD", "D300002A", "ENTRY", when, entries_today=lambda ref: 0).granted)
        self.assertEqual(al.decide("RFID_CARD", "D300002A", "ENTRY", when, entries_today=lambda ref: 1).reason,
                         "DAILY_ENTRY_LIMIT")
        self.assertTrue(self.al.decide("RFID_CARD", "D300002A", "ENTRY", when, entries_today=lambda ref: 9).granted)


# ------------------------------------------------------------------------------------------- storage: cache + buffer

class StorageTest(unittest.TestCase):
    def test_allowlist_cache_is_reverified_on_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "allowlist.json"
            raw = json.dumps(sample_allowlist()).encode()
            nonce = "0123456789abcdef0123456789abcdef"
            store = gb.AllowlistStore(path, SECRET)
            store.save(nonce, gb.sign_response(SECRET, nonce, raw), raw)
            self.assertEqual(len(gb.AllowlistStore(path, SECRET).load()), 3)
            self.assertIsNone(gb.AllowlistStore(path, "b" * 64).load(), "rotated secret: cache not trusted")
            stored = json.loads(path.read_text())
            stored["body"] = stored["body"].replace("Priya", "Mallory")
            path.write_text(json.dumps(stored))
            self.assertIsNone(gb.AllowlistStore(path, SECRET).load(), "edited cache must be ignored")

    def test_event_buffer_append_remove_and_reject(self):
        with tempfile.TemporaryDirectory() as tmp:
            buf = gb.EventBuffer(Path(tmp) / "b.jsonl", Path(tmp) / "r.jsonl")
            for i in range(3):
                buf.append("scan", {"deviceEventId": f"e{i}"})
            with open(buf.path, "a") as f:
                f.write("{corrupt\n")
            self.assertEqual(buf.depth(), 3)
            buf.remove({"e0", "e2"}, rejected=[{"kind": "scan", "event": {"deviceEventId": "e2"}, "error": "bad"}])
            self.assertEqual([r["event"]["deviceEventId"] for r in buf.read()], ["e1"])
            self.assertIn("e2", buf.rejected_path.read_text())


# ------------------------------------------------------------------------------------------------- bridge behaviour

class BridgeOfflineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg = make_config(self.tmp.name)
        self.client = FakeClient()   # server down
        self.bridge = gb.Bridge(self.cfg, client=self.client)
        raw = json.dumps(sample_allowlist(openingHours=[])).encode()
        self.bridge.allowlist.save("n" * 32, gb.sign_response(SECRET, "n" * 32, raw), raw)

    def tearDown(self):
        self.bridge.shutdown()
        self.tmp.cleanup()

    def test_offline_scan_is_decided_locally_buffered_and_replayed(self):
        when = dt.datetime(2026, 10, 12, 4, 0, tzinfo=UTC)
        with mock.patch.object(gb, "utc_now", return_value=when):
            ok = self.bridge.handle_scan("RFID_CARD", "D300002A", None)
            bad = self.bridge.handle_scan("RFID_CARD", "FFFF0000", None)
            out = self.bridge.handle_exit_button()
        self.assertEqual((ok["decision"], ok["source"], ok["openGate"]), ("GRANTED", "offline", True))
        self.assertEqual((bad["decision"], bad["reasonCode"]), ("DENIED", "UNKNOWN_CREDENTIAL"))
        self.assertTrue(out["openGate"])
        self.assertFalse(self.bridge.state.online)
        records = self.bridge.buffer.read()
        self.assertEqual([r["kind"] for r in records], ["scan", "scan", "exit_button"])
        granted = records[0]["event"]
        # exactly the ScanRequest field names
        self.assertEqual(set(granted), {"deviceEventId", "credentialType", "credentialValue", "direction", "occurredAt",
                                        "offlineDecision"})
        self.assertEqual((granted["offlineDecision"], granted["occurredAt"], granted["direction"]),
                         ("GRANTED", "2026-10-12T04:00:00Z", "ENTRY"))
        self.assertEqual(records[1]["event"]["offlineReason"], "UNKNOWN_CREDENTIAL")
        self.assertEqual(set(records[2]["event"]), {"deviceEventId", "occurredAt"})

        def server(method, path, body):
            if path.endswith("/events/batch"):
                return 200, {"processed": len(body["events"]),
                             "results": [{"deviceEventId": e["deviceEventId"], "decision": e["offlineDecision"],
                                          "reasonCode": "OK"} for e in body["events"]]}
            return 200, {"decision": "GRANTED", "reasonCode": "OK"}

        self.client.handler = server
        self.assertEqual(self.bridge.upload_buffer(), 3)
        self.assertEqual(self.bridge.buffer.depth(), 0)
        batch = [c for c in self.client.calls if c[1].endswith("/events/batch")][-1]
        self.assertEqual(len(batch[2]["events"]), 2)
        self.assertEqual(batch[2]["events"][0]["deviceEventId"], granted["deviceEventId"], "same id: idempotent")
        self.assertTrue(any(c[1].endswith("/exit-button") for c in self.client.calls))

    def test_a_malformed_buffered_event_is_quarantined_not_blocking(self):
        self.bridge.buffer.append("scan", {"deviceEventId": "good", "credentialType": "RFID_CARD",
                                           "credentialValue": "D300002A", "direction": "ENTRY",
                                           "occurredAt": "2026-10-12T04:00:00Z", "offlineDecision": "GRANTED"})
        self.bridge.buffer.append("scan", {"deviceEventId": "bad", "credentialType": "RFID_CARD"})

        def server(method, path, body):
            if any("credentialValue" not in e for e in body["events"]):
                return 400, {"message": "credentialValue must not be blank"}
            return 200, {"processed": len(body["events"]), "results": []}

        self.client.handler = server
        self.bridge.upload_buffer()
        self.assertEqual(self.bridge.buffer.depth(), 0)
        self.assertIn('"bad"', self.bridge.buffer.rejected_path.read_text())

    def test_online_decision_drives_the_relay_and_denials_the_deny_signal(self):
        opened, denied = [], []
        self.bridge.relay.trigger = lambda s, d, r, di: opened.append((s, d, r))
        self.bridge.deny.trigger = lambda s, d, r, di: denied.append((d, r))
        self.client.handler = lambda m, p, b: (200, {"deviceEventId": b["deviceEventId"], "decision": "GRANTED",
                                                     "reasonCode": "OK", "openGate": True, "openSeconds": 7,
                                                     "direction": "ENTRY"})
        out = self.bridge.handle_scan("QR", "GQ1.x.y.z", "ENTRY")
        self.assertEqual((out["source"], out["openSeconds"]), ("server", 7))
        self.assertEqual(opened, [(7, "GRANTED", "OK")])
        self.client.handler = lambda m, p, b: (200, {"decision": "DENIED", "reasonCode": "FROZEN", "openGate": False})
        self.bridge.handle_scan("RFID_CARD", "D300002A")
        self.assertEqual(denied, [("DENIED", "FROZEN")])
        self.assertEqual(self.client.calls[0][2]["direction"], "ENTRY")
        self.assertNotIn("direction", self.client.calls[1][2], "no direction configured: the device default applies")

    def test_unverified_response_is_never_trusted(self):
        def unsigned(method, path, body):
            raise AssertionError("replaced below")
        self.client.call = lambda method, path, body=None, timeout=None: gb.Response(
            200, {"decision": "GRANTED", "openGate": True}, b"{}", False)
        out = self.bridge.handle_scan("RFID_CARD", "FFFF0000", None)
        self.assertEqual((out["source"], out["decision"]), ("offline", "DENIED"))

    def test_exit_button_on_a_server_without_the_endpoint_opens_locally(self):
        self.client.handler = lambda m, p, b: (404, {"message": "No endpoint POST /api/v1/access/gate/exit-button"})
        out = self.bridge.handle_exit_button()
        self.assertEqual((out["source"], out["openGate"]), ("local", True))
        self.assertEqual(self.bridge.buffer.depth(), 0)

    def test_disabled_device_is_denied_not_decided_offline(self):
        self.client.handler = lambda m, p, b: (403, {"message": "Device is disabled"})
        out = self.bridge.handle_scan("RFID_CARD", "D300002A", None)
        self.assertEqual((out["decision"], out["source"]), ("DENIED", "error"))
        self.assertEqual(self.bridge.buffer.depth(), 0)

    def test_gates_switched_off_in_settings_denies_without_offline_fallback(self):
        self.client.handler = lambda m, p, b: (403, {"code": "FEATURE_DISABLED",
                                                     "message": "Gate access control is switched off"})
        out = self.bridge.handle_scan("RFID_CARD", "D300002A", None)
        self.assertEqual((out["decision"], out["reasonCode"], out["openGate"]), ("DENIED", "FEATURE_DISABLED", False))
        self.assertTrue(self.bridge.state.gates_disabled)
        self.assertEqual(self.bridge.buffer.depth(), 0)
        self.assertTrue(self.bridge.handle_exit_button()["openGate"], "exit still opens locally")
        self.assertFalse(self.bridge.heartbeat().ok)          # keeps running, no exception
        self.client.handler = lambda m, p, b: (200, {"serverTime": gb.iso(gb.utc_now()), "deviceStatus": "ACTIVE",
                                                     "pendingCommands": 0})
        self.bridge.heartbeat()
        self.assertFalse(self.bridge.state.gates_disabled, "switched back on")

    def test_heartbeat_logs_exit_mode_and_wakes_command_polling(self):
        self.client.handler = lambda m, p, b: (200, {"serverTime": gb.iso(gb.utc_now()), "deviceStatus": "ACTIVE",
                                                     "pendingCommands": 2, "allowlistRefreshSeconds": 600,
                                                     "exitMode": "VERIFIED"})
        self.bridge.heartbeat()
        self.assertEqual(self.bridge.state.exit_mode, "VERIFIED")
        self.assertTrue(self.bridge.wake_commands.is_set())
        self.assertEqual(self.bridge.allowlist_interval, 600)
        hb = self.client.calls[-1][2]
        self.assertEqual(set(hb), {"firmwareVersion", "uptimeSeconds", "offlineQueueDepth"})


class ConfigTest(unittest.TestCase):
    def test_secret_comes_from_the_environment_and_is_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "c.json"
            p.write_text(json.dumps({"device_id": "gd_x", "device_secret": "from-file"}))
            self.assertEqual(gb.load_config(str(p), environ={"GATE_DEVICE_SECRET": "env"})["_secret"], "env")
            p.write_text(json.dumps({"device_id": "gd_x"}))
            with self.assertRaises(gb.ConfigError):
                gb.load_config(str(p), environ={})
            p.write_text(json.dumps({"device_id": "gd_x", "inputs": ["keyboard", "exit-button"]}))
            with self.assertRaises(gb.ConfigError):
                gb.load_config(str(p), environ={"GATE_DEVICE_SECRET": "s"})
            p.write_text(json.dumps({"device_id": "gd_x", "relay": {"type": "command", "command": "rm -rf /"}}))
            with self.assertRaises(gb.ConfigError):   # must be an argument list, never a shell string
                gb.load_config(str(p), environ={"GATE_DEVICE_SECRET": "s"})

    def test_example_config_loads(self):
        cfg = gb.load_config(str(SCRIPTS / "gate_bridge.example.json"), environ={"GATE_DEVICE_SECRET": "s"})
        self.assertEqual(cfg["inputs"], ["keyboard", "http"])
        self.assertNotIn("device_secret", cfg)

    def test_placeholders_in_relay_specs(self):
        self.assertEqual(gb._fill(["relay", "--open", "{seconds}"], {"seconds": 5}), ["relay", "--open", "5"])
        self.assertEqual(gb._fill("http://r/on?t={seconds}&why={reason}", {"seconds": 3, "reason": "A B"}, quote=True),
                         "http://r/on?t=3&why=A%20B")


# --------------------------------------------------------------------------- loopback: fake server + local HTTP input

class FakeGateServer(http.server.BaseHTTPRequestHandler):
    """Verifies requests like GateAuthenticator (signature over METHOD|URI|ts|nonce|sha256(body), ±5 min, single-use
    nonce) and signs responses like GateController."""
    nonces: set[str] = set()
    seen: dict[str, dict] = {}

    def log_message(self, *a):
        pass

    def _handle(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        ts, nonce = self.headers.get("X-Timestamp", ""), self.headers.get("X-Nonce", "")
        expected = gb.sign_request(SECRET, method, self.path, ts, nonce, body)
        if (self.headers.get("X-Device-Id") != "gd_test" or not hmac.compare_digest(expected, self.headers.get(
                "X-Signature", "")) or abs(int(ts) - int(gb.time.time())) > 300 or nonce in self.nonces):
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"message":"Device authentication failed"}')
            return
        self.nonces.add(nonce)
        req = json.loads(body) if body else None
        if self.path.endswith("/events"):
            prior = self.seen.get(req["deviceEventId"])
            out = prior or {"deviceEventId": req["deviceEventId"], "decision": "GRANTED", "reasonCode": "OK",
                            "openGate": True, "openSeconds": 5, "memberDisplayName": "Priya S.",
                            "direction": req.get("direction", "ENTRY"), "replayed": False}
            self.seen[req["deviceEventId"]] = out
            payload = out
        elif self.path.endswith("/heartbeat"):
            payload = {"serverTime": gb.iso(gb.utc_now()), "deviceStatus": "ACTIVE", "pendingCommands": 0,
                       "allowlistRefreshSeconds": 3600, "exitMode": "BUTTON"}
        elif self.path.endswith("/allowlist"):
            payload = sample_allowlist()
        else:
            self.send_response(404)
            self.end_headers()
            return
        raw = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Signature", gb.sign_response(SECRET, nonce, raw))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):  # noqa: N802
        self._handle("GET")

    def do_POST(self):  # noqa: N802
        self._handle("POST")


class LoopbackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeGateServer)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg = make_config(self.tmp.name, server_url=self.url, http={"bind": "127.0.0.1", "port": 0})
        self.cfg["_http_token"] = "tok"
        self.bridge = gb.Bridge(self.cfg)

    def tearDown(self):
        self.bridge.shutdown()
        self.tmp.cleanup()

    def test_real_signed_round_trip_heartbeat_allowlist_and_scan(self):
        self.assertTrue(self.bridge.heartbeat().ok)
        self.assertEqual(self.bridge.state.exit_mode, "BUTTON")
        allowlist = self.bridge.refresh_allowlist()
        self.assertEqual(len(allowlist), 3)
        self.assertIsNotNone(gb.AllowlistStore(Path(self.tmp.name) / "allowlist.json", SECRET).load())
        out = self.bridge.handle_scan("RFID_CARD", "D300002A", "ENTRY")
        self.assertEqual((out["decision"], out["source"]), ("GRANTED", "server"))

    def test_wrong_secret_is_rejected_by_the_server(self):
        bad = gb.GateClient(self.url, "gd_test", "b" * 64)
        self.assertEqual(bad.call("POST", gb.GATE_PREFIX + "/heartbeat", {}).status, 401)

    def test_local_http_input_requires_the_token_and_returns_the_decision(self):
        self.bridge.start_http()
        port = self.bridge.http_server.server_address[1]

        def post(path, body, token="tok"):
            req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode(),
                                         method="POST", headers={"Content-Type": "application/json",
                                                                 **({"X-Bridge-Token": token} if token else {})})
            try:
                with urllib.request.urlopen(req, timeout=10) as r:
                    return r.status, json.loads(r.read())
            except urllib.error.HTTPError as e:
                return e.code, json.loads(e.read())

        self.assertEqual(post("/scan", {"credentialType": "FINGERPRINT", "credentialValue": "FP-1"}, token=None)[0], 401)
        status, out = post("/scan", {"credentialType": "FINGERPRINT", "credentialValue": "FP-DEMO-7",
                                     "direction": "ENTRY"})
        self.assertEqual((status, out["decision"], out["openGate"]), (200, "GRANTED", True))
        self.assertEqual(post("/scan", {"credentialType": "IRIS", "credentialValue": "x"})[0], 400)
        status, out = post("/event", {"event": "EXIT_BUTTON"})   # fake server has no exit-button: local open
        self.assertEqual((status, out["source"], out["openGate"]), (200, "local", True))


if __name__ == "__main__":
    unittest.main()
